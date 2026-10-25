import { flushPromises, mount } from '@vue/test-utils'
import { ref } from 'vue'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { Checkbox } from '@/components/ui/checkbox'
import { ApiError } from '@/core/http'
import { Task } from '@/sections/tasks/api'
import TaskCompletionDialog from '@/sections/tasks/TaskCompletionDialog.vue'
import TasksCard from './TasksCard.vue'

const mocks = vi.hoisted(() => ({ operate: vi.fn(), confirm: vi.fn(), push: vi.fn() }))
vi.mock('vue-i18n', async (original) => ({
  ...(await original<typeof import('vue-i18n')>()),
  useI18n: () => ({ t: (key: string) => key }),
}))
vi.mock('vue-router', () => ({ useRouter: () => ({ push: mocks.push }) }))
vi.mock('@/stores/session', () => ({ useSession: () => ({ can: () => true }) }))
vi.mock('@/core/operations', () => ({ useLedger: () => ({ operate: mocks.operate }) }))
vi.mock('@/core/undo', () => ({ useUndo: () => ({ confirm: mocks.confirm }) }))
vi.mock('@tanstack/vue-query', () => ({ useQueryClient: () => ({}) }))
vi.mock('./useTodayTasks', () => ({
  useTodayTasks: () => ({
    data: ref({ items: [task], today: '2026-10-08' }),
    isPending: ref(false),
    isLoadingError: ref(false),
  }),
}))
const task = Task.parse({
  id: 'task',
  list_id: 'list',
  title: 'Review the route',
  status: 'open',
  revision: 3,
})
const mountCard = () =>
  mount(TasksCard, { global: { stubs: { RouterLink: true, TaskCompletionDialog: true } } })
const refusal = () =>
  new ApiError(
    409,
    'incomplete_checklist',
    { task_id: task.id, revision: task.revision, open_subtasks: 0, incomplete_checklist: 1 },
    null,
    null,
  )

beforeEach(() => {
  vi.clearAllMocks()
  mocks.operate
    .mockReset()
    .mockRejectedValueOnce(refusal())
    .mockResolvedValueOnce({
      operation_id: 'operation',
      kind: 'task.complete',
      tasks: [{ ...task, status: 'done', revision: 4 }],
    })
})

describe('today task completion', () => {
  it('renders the guard and leaves the task untouched when completion is cancelled', async () => {
    const wrapper = mountCard()
    wrapper.getComponent(Checkbox).vm.$emit('update:modelValue', true)
    await flushPromises()
    const completion = wrapper.getComponent(TaskCompletionDialog).props('completion')
    expect(completion.pending.value?.reason).toBe('incomplete_checklist')
    completion.cancel()
    await flushPromises()
    expect(mocks.operate).toHaveBeenCalledTimes(1)
    expect(mocks.confirm).not.toHaveBeenCalled()
    wrapper.unmount()
  })

  it('confirms the exact revision from the today card and can open its task page', async () => {
    const wrapper = mountCard()
    wrapper.getComponent(Checkbox).vm.$emit('update:modelValue', true)
    await flushPromises()
    const dialog = wrapper.getComponent(TaskCompletionDialog)
    dialog.props('completion').confirm()
    await flushPromises()
    expect(mocks.operate).toHaveBeenLastCalledWith({
      kind: 'task.complete',
      payload: { task_id: task.id, confirm_checklist_revision: task.revision },
      expected_revisions: { [task.id]: task.revision },
    })
    expect(mocks.confirm).toHaveBeenCalledTimes(1)
    dialog.vm.$emit('inspect', task)
    expect(mocks.push).toHaveBeenCalledWith({
      name: 'tasks',
      query: { list: task.list_id, task: task.id },
    })
    wrapper.unmount()
  })
})
