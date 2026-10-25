import { flushPromises, mount } from '@vue/test-utils'
import { ref } from 'vue'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { ApiError } from '@/core/http'
import { Task } from './api'
import { AssistantSnapshot, AssistantCapabilities, safeSource } from './assistant'
import TaskAssistant from './TaskAssistant.vue'
import TaskCompletionDialog from './TaskCompletionDialog.vue'

const mocks = vi.hoisted(() => ({
  operate: vi.fn(),
  snapshot: {} as Record<string, unknown>,
  capabilities: {} as Record<string, unknown>,
  refreshFailed: false,
  refetch: vi.fn(),
}))
vi.mock('vue-i18n', () => ({ useI18n: () => ({ t: (key: string) => key, te: () => true }) }))
vi.mock('@/stores/session', () => ({ useSession: () => ({ me: { id: 'user', timezone: 'UTC' } }) }))
vi.mock('@/core/operations', () => ({ useLedger: () => ({ operate: mocks.operate }) }))
vi.mock('@/core/useDraft', () => ({
  useDraft: (_target: unknown, empty: () => object) => ({ value: ref(empty()), clear: vi.fn() }),
}))
vi.mock('@tanstack/vue-query', () => ({
  useQuery: (options: { queryKey: { value: unknown[] } }) => ({
    data: ref(options.queryKey.value[1] === 'assistant' ? mocks.snapshot : mocks.capabilities),
    isPending: ref(false),
    isLoadingError: ref(false),
    isRefetchError: ref(mocks.refreshFailed && options.queryKey.value[1] === 'assistant'),
    isFetching: ref(false),
    error: ref(null),
    refetch: mocks.refetch,
  }),
}))
const task = Task.parse({
  id: 'task',
  list_id: 'list',
  title: 'Проверить варианты',
  status: 'open',
  state: 'todo',
  revision: 7,
})
const delegation = {
  id: 'delegation',
  task_id: 'task',
  user_id: 'user',
  instruction: 'Изучить варианты',
  mode: 'oneoff',
  completion_mode: 'manual',
  status: 'waiting_review',
  revision: 3,
  grants: { tools: [], actions: [], resources: ['list'], executors: [] },
}
const button = (wrapper: ReturnType<typeof mount>, key: string) =>
  wrapper.findAll('button').find((item) => item.text() === key)!

beforeEach(() => {
  mocks.operate.mockReset().mockResolvedValue({})
  mocks.refreshFailed = false
  mocks.refetch.mockReset()
  mocks.snapshot = AssistantSnapshot.parse({
    delegation: null,
    runs: [],
    questions: [],
    results: [],
    can_manage: true,
  })
  mocks.capabilities = AssistantCapabilities.parse({
    enabled: true,
    tools: [],
    actions: [],
    executors: [],
  })
})

describe('task delegation UI', () => {
  it('asks before accepting a result and completing an unfinished checklist', async () => {
    mocks.snapshot = AssistantSnapshot.parse({
      delegation,
      can_manage: true,
      results: [{ id: 'result', body: 'Результат', status: 'pending', revision: 4, sources: [] }],
    })
    mocks.operate.mockRejectedValueOnce(
      new ApiError(
        409,
        'incomplete_checklist',
        { task_id: task.id, revision: task.revision, open_subtasks: 0, incomplete_checklist: 1 },
        null,
        null,
      ),
    )
    const wrapper = mount(TaskAssistant, {
      props: { task, canWrite: true },
      global: { stubs: { TaskCompletionDialog: true } },
    })
    await button(wrapper, 'tasks.agent.acceptComplete').trigger('click')
    await flushPromises()
    expect(mocks.refetch).not.toHaveBeenCalled()
    wrapper.getComponent(TaskCompletionDialog).props('completion').confirm()
    await flushPromises()
    expect(mocks.operate).toHaveBeenLastCalledWith({
      kind: 'result.accept',
      payload: { result_id: 'result', complete_task: true, confirm_checklist_revision: 7 },
      expected_revisions: { result: 4, task: 7 },
    })
    expect(mocks.refetch).toHaveBeenCalledTimes(1)
    wrapper.unmount()
  })

  it('creates an explicit manual one-off instruction without broadening server defaults', async () => {
    const wrapper = mount(TaskAssistant, { props: { task, canWrite: true } })
    await wrapper.get('#delegation-instruction').setValue('  Исследуй варианты и сохрани ссылки  ')
    await wrapper.get('form').trigger('submit')
    await flushPromises()
    expect(mocks.operate).toHaveBeenCalledWith({
      kind: 'delegation.create',
      payload: {
        task_id: 'task',
        instruction: 'Исследуй варианты и сохрани ссылки',
        mode: 'oneoff',
        completion_mode: 'manual',
        interval_minutes: 60,
        completion_criteria: { required_checks: [] },
      },
      expected_revisions: { task: 7 },
    })
    wrapper.unmount()
  })
  it('offers only deterministic completion criteria for automatic completion', async () => {
    const wrapper = mount(TaskAssistant, { props: { task, canWrite: true } })
    await wrapper.get('#delegation-instruction').setValue('Подготовь результат')
    await wrapper.get('select[aria-label="tasks.agent.completion"]').setValue('auto')
    expect(wrapper.find('#delegation-checks').exists()).toBe(false)
    await wrapper.get('form').trigger('submit')
    await flushPromises()
    expect(mocks.operate).toHaveBeenCalledWith(
      expect.objectContaining({
        payload: expect.objectContaining({
          completion_mode: 'auto',
          completion_criteria: {
            required_checks: [
              'has_result',
              'all_subtasks_done',
              'all_checklist_done',
              'no_pending_questions',
            ],
          },
        }),
      }),
    )
    wrapper.unmount()
  })
  it('requires a separate action to accept a result and complete the task with both revisions', async () => {
    mocks.snapshot = AssistantSnapshot.parse({
      delegation,
      can_manage: true,
      results: [{ id: 'result', body: 'Результат', status: 'pending', revision: 4, sources: [] }],
    })
    const wrapper = mount(TaskAssistant, { props: { task, canWrite: true } })
    expect(mocks.operate).not.toHaveBeenCalled()
    await button(wrapper, 'tasks.agent.acceptComplete').trigger('click')
    await flushPromises()
    expect(mocks.operate).toHaveBeenCalledWith({
      kind: 'result.accept',
      payload: { result_id: 'result', complete_task: true },
      expected_revisions: { result: 4, task: 7 },
    })
    wrapper.unmount()
  })
  it('binds an answer to the precise pending question and its revision', async () => {
    mocks.snapshot = AssistantSnapshot.parse({
      delegation: { ...delegation, status: 'waiting_input' },
      can_manage: true,
      questions: [{ id: 'question', text: 'Какой город?', status: 'pending', revision: 2 }],
    })
    const wrapper = mount(TaskAssistant, { props: { task, canWrite: true } })
    await wrapper.get('textarea').setValue('Москва')
    await wrapper.get('form').trigger('submit')
    await flushPromises()
    expect(mocks.operate).toHaveBeenCalledWith({
      kind: 'question.answer',
      payload: { question_id: 'question', answer: 'Москва' },
      expected_revisions: { question: 2 },
    })
    wrapper.unmount()
  })
  it('decides a stored proposal with buttons, never with a typed answer', async () => {
    mocks.snapshot = AssistantSnapshot.parse({
      delegation: { ...delegation, status: 'waiting_input' },
      can_manage: true,
      questions: [
        { id: 'proposal', text: 'Обновить?', status: 'pending', revision: 3, kind: 'proposal' },
      ],
    })
    const wrapper = mount(TaskAssistant, { props: { task, canWrite: true } })
    expect(wrapper.find('textarea').exists()).toBe(false)
    await button(wrapper, 'tasks.agent.decline').trigger('click')
    await flushPromises()
    expect(mocks.operate).toHaveBeenCalledWith({
      kind: 'question.answer',
      payload: { question_id: 'proposal', decision: 'decline' },
      expected_revisions: { proposal: 3 },
    })
    wrapper.unmount()
  })
  it('shows a shared result without management controls for a viewer, escaping report text and unsafe links', () => {
    mocks.snapshot = AssistantSnapshot.parse({
      delegation,
      can_manage: false,
      results: [
        {
          id: 'result',
          body: '<script>run()</script>',
          status: 'pending',
          revision: 4,
          sources: [
            { title: 'Unsafe', url: 'javascript:alert(1)' },
            { title: 'Source', url: 'https://example.org' },
          ],
        },
      ],
    })
    const wrapper = mount(TaskAssistant, { props: { task, canWrite: false } })
    expect(wrapper.find('script').exists()).toBe(false)
    expect(wrapper.text()).toContain('<script>run()</script>')
    expect(wrapper.findAll('a').map((link) => link.attributes('href'))).toEqual([
      'https://example.org/',
    ])
    expect(wrapper.find('form').exists()).toBe(false)
    expect(button(wrapper, 'tasks.agent.accept')).toBeUndefined()
    expect(button(wrapper, 'tasks.agent.revoke')).toBeUndefined()
    wrapper.unmount()
  })
  it('does not offer starting work when the background feature is disabled', () => {
    mocks.capabilities = AssistantCapabilities.parse({ enabled: false })
    const wrapper = mount(TaskAssistant, { props: { task, canWrite: true } })
    expect(wrapper.find('form').exists()).toBe(false)
    expect(wrapper.text()).toContain('tasks.agent.disabled')
    wrapper.unmount()
  })
})

describe('result sources', () => {
  it('only permits HTTP(S) source links', () => {
    expect(safeSource('javascript:alert(1)')).toBeNull()
    expect(safeSource('data:text/html,hello')).toBeNull()
    expect(safeSource('/relative')).toBeNull()
    expect(safeSource('https://example.org/paper')).toBe('https://example.org/paper')
  })
  it('keeps the delegation on screen with a retry when its refresh fails', async () => {
    mocks.snapshot = AssistantSnapshot.parse({
      delegation,
      runs: [],
      questions: [],
      results: [],
      can_manage: true,
    })
    mocks.refreshFailed = true
    const wrapper = mount(TaskAssistant, { props: { task, canWrite: true } })
    expect(wrapper.text()).toContain('Изучить варианты')
    expect(wrapper.find('[data-state="error"]').exists()).toBe(false)
    const notice = wrapper.get('[data-state="refresh-failed"]')
    expect(notice.text()).toContain('state.refreshFailed')
    await notice.get('button').trigger('click')
    // Only the snapshot failed; the capabilities that refreshed are not asked again.
    expect(mocks.refetch).toHaveBeenCalledOnce()
  })
})
