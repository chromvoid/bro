import { flushPromises, mount } from '@vue/test-utils'
import { nextTick, ref, type Ref } from 'vue'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { ApiError } from '@/core/http'
import { Task, fieldsOf } from './api'
import TaskPanel from './TaskPanel.vue'
import TaskCompletionDialog from './TaskCompletionDialog.vue'
import TaskDiscussion from './TaskDiscussion.vue'
import TaskHistory from './TaskHistory.vue'
import TaskSteps from './TaskSteps.vue'
const mocks = vi.hoisted(() => ({
  operate: vi.fn(),
  upload: vi.fn(),
  get: vi.fn(),
  clear: vi.fn(),
  resetScroll: vi.fn(),
  evictList: vi.fn(),
  draft: null as null | { value: Ref<unknown>; restored: Ref<boolean> },
}))
vi.mock('vue-i18n', async (original) => ({
  ...(await original<typeof import('vue-i18n')>()),
  useI18n: () => ({ t: (key: string) => key }),
}))
vi.mock('@/core/client', () => ({ api: { get: mocks.get } }))
vi.mock('./useTaskBoardPages', () => ({ evictList: mocks.evictList }))
vi.mock('./images', async (original) => ({
  ...(await original<typeof import('./images')>()),
  uploadTaskImage: mocks.upload,
}))
vi.mock('@/stores/session', () => ({
  useSession: () => ({ me: { id: 'user', timezone: 'UTC' }, can: () => false }),
}))
// «Напомнить» opens the timers screen.
vi.mock('vue-router', () => ({ useRouter: () => ({ push: vi.fn() }) }))
vi.mock('@/core/operations', () => ({
  operationKind: () => 'task.update',
  useLedger: () => ({
    operate: mocks.operate,
    unresolved: ref([]),
    check: vi.fn(),
    resend: vi.fn(),
    abandon: vi.fn(),
  }),
}))
vi.mock('@/core/useDraft', () => ({
  useDraft: (_target: unknown, empty: () => object) => {
    const value = ref(empty())
    const restored = ref(false)
    mocks.draft = { value, restored }
    return {
      value,
      restored,
      start: (data: object) => {
        if (!restored.value) value.value = data
      },
      clear: mocks.clear,
    }
  },
}))
vi.mock('@/sections/circle/useCircle', () => ({
  useTaskLists: () => ({ data: ref({ lists: [{ id: 'list', effective: 'manage' }] }) }),
  useMembers: () => ({ data: ref({ members: [] }) }),
}))
vi.mock('@tanstack/vue-query', () => ({
  useQuery: () => ({ data: ref(null), error: ref(null), refetch: vi.fn() }),
  useQueryClient: () => ({}),
}))
const task = Task.parse({
  id: 'task',
  list_id: 'list',
  title: 'Original',
  description: '',
  status: 'open',
  revision: 1,
})
const options = {
  props: { task, today: '2026-10-03', open: true, canWrite: true },
  global: {
    stubs: {
      Panel: {
        props: ['open'],
        methods: { resetScroll: mocks.resetScroll },
        template:
          '<div v-if="open"><slot name="actions" /><slot /><div v-if="$slots.footer" data-testid="task-save-bar"><slot name="footer" /></div></div>',
      },
      TaskSteps: true,
      TaskAssistant: true,
      TaskDiscussion: true,
      TaskHistory: true,
      TaskCompletionDialog: true,
    },
  },
}
beforeEach(() => {
  mocks.operate.mockReset().mockResolvedValue({})
  mocks.clear.mockReset().mockResolvedValue(undefined)
  mocks.resetScroll.mockReset()
  mocks.evictList.mockReset()
})

describe('task card concurrent edits', () => {
  it('opens history explicitly without mounting discussion and reads it only while open', async () => {
    const wrapper = mount(TaskPanel, {
      ...options,
      props: { ...options.props, initialTab: 'history' },
    })
    expect(wrapper.getComponent(TaskHistory).props()).toMatchObject({
      taskId: task.id,
      revision: task.revision,
      active: true,
    })
    expect(wrapper.findComponent(TaskDiscussion).exists()).toBe(false)
    expect(wrapper.find('[data-testid="task-title-text"]').exists()).toBe(false)
    await wrapper.setProps({ open: false })
    expect(wrapper.findComponent(TaskHistory).exists()).toBe(false)
    wrapper.unmount()
  })

  it('keeps deletion history available in the trash without showing editable task details', async () => {
    const wrapper = mount(TaskPanel, {
      ...options,
      props: {
        ...options.props,
        task: { ...task, deleted_at: '2026-10-08T12:00:00Z' },
        initialTab: 'history',
      },
    })
    expect(wrapper.findAll('[role="tab"]').map((item) => item.text())).toEqual([
      'tasks.tabs.details',
      'tasks.tabs.history',
    ])
    expect(wrapper.findComponent(TaskHistory).exists()).toBe(true)
    expect(wrapper.findAll('button').some((item) => item.text() === 'tasks.restore')).toBe(false)
    await wrapper.get('[role="tab"]').trigger('click')
    expect(wrapper.findComponent(TaskHistory).exists()).toBe(false)
    expect(wrapper.findAll('button').some((item) => item.text() === 'tasks.restore')).toBe(true)
    wrapper.unmount()
  })

  it('returns a deleted task to details when its requested discussion tab is unavailable', () => {
    const wrapper = mount(TaskPanel, {
      ...options,
      props: {
        ...options.props,
        task: { ...task, deleted_at: '2026-10-08T12:00:00Z' },
        initialTab: 'discussion',
      },
    })
    expect(wrapper.findComponent(TaskDiscussion).exists()).toBe(false)
    expect(wrapper.find('[data-testid="task-title-text"]').exists()).toBe(true)
    wrapper.unmount()
  })

  it('closes a refused history read and clears its list through the shared access eviction', async () => {
    const wrapper = mount(TaskPanel, {
      ...options,
      props: { ...options.props, initialTab: 'history' },
    })
    const error = new ApiError(403, 'forbidden', null, null, null)
    wrapper.getComponent(TaskHistory).vm.$emit('denied', error)
    await nextTick()
    expect(mocks.evictList).toHaveBeenCalledWith(expect.anything(), task.list_id, error)
    expect(wrapper.emitted('update:open')?.at(-1)).toEqual([false])
    expect(wrapper.emitted('denied')?.at(-1)).toEqual([task.id, error])
    wrapper.unmount()
  })

  it('opens an explicit discussion tab and enables reading only while that panel tab is shown', async () => {
    const wrapper = mount(TaskPanel, {
      ...options,
      props: { ...options.props, initialTab: 'discussion' },
    })
    expect(wrapper.getComponent(TaskDiscussion).props('active')).toBe(true)
    const details = wrapper
      .findAll('[role="tab"]')
      .find((item) => item.text() === 'tasks.tabs.details')!
    await details.trigger('click')
    expect(wrapper.findComponent(TaskDiscussion).exists()).toBe(false)
    expect(wrapper.emitted('tab')?.at(-1)).toEqual(['details'])
    await wrapper.setProps({ initialTab: 'details' })
    await wrapper.setProps({ initialTab: 'discussion' })
    expect(wrapper.getComponent(TaskDiscussion).props('active')).toBe(true)
    await wrapper.setProps({ open: false })
    expect(wrapper.findComponent(TaskDiscussion).exists()).toBe(false)
    wrapper.unmount()
  })

  it('creates a separate discussion instance when another task opens in that tab', async () => {
    const wrapper = mount(TaskPanel, {
      ...options,
      props: { ...options.props, initialTab: 'discussion' },
    })
    const previous = wrapper.getComponent(TaskDiscussion).vm
    await wrapper.setProps({ task: { ...task, id: 'next-task', title: 'Next task' } })
    expect(wrapper.getComponent(TaskDiscussion).vm).not.toBe(previous)
    expect(wrapper.getComponent(TaskDiscussion).props('active')).toBe(true)
    wrapper.unmount()
  })

  it('keeps the panel open when completion is cancelled', async () => {
    mocks.operate.mockRejectedValueOnce(
      new ApiError(
        409,
        'incomplete_checklist',
        { task_id: task.id, revision: 1, open_subtasks: 0, incomplete_checklist: 2 },
        null,
        null,
      ),
    )
    const wrapper = mount(TaskPanel, options)
    await wrapper
      .get('[data-testid="task-state"]')
      .findAll('button')
      .find((button) => button.text() === 'tasks.states.done')!
      .trigger('click')
    await flushPromises()
    const completion = wrapper.getComponent(TaskCompletionDialog).props('completion')
    expect(completion.pending.value?.detail.incomplete_checklist).toBe(2)
    expect(wrapper.find('[data-testid="task-conflict"]').exists()).toBe(false)
    completion.cancel()
    await flushPromises()
    expect(wrapper.emitted('update:open')).toBeUndefined()
    expect(mocks.operate).toHaveBeenCalledTimes(1)
    wrapper.unmount()
  })

  it('retains edits when an update completion guard is declined', async () => {
    const wrapper = mount(TaskPanel, options)
    const base = fieldsOf(task)
    mocks.draft!.value.value = {
      base,
      fields: { ...base, state: 'done', title: 'Edited' },
      revision: 1,
    }
    mocks.operate.mockRejectedValueOnce(
      new ApiError(
        409,
        'incomplete_checklist',
        { task_id: task.id, revision: 1, open_subtasks: 0, incomplete_checklist: 1 },
        null,
        null,
      ),
    )
    await nextTick()
    await wrapper
      .findAll('button')
      .find((button) => button.text() === 'tasks.save')!
      .trigger('click')
    await flushPromises()
    wrapper.getComponent(TaskCompletionDialog).props('completion').cancel()
    await flushPromises()
    expect(wrapper.get('[data-testid="task-title-text"]').text()).toBe('Edited')
    expect(wrapper.find('[data-testid="task-save-bar"]').exists()).toBe(true)
    expect(wrapper.emitted('update:open')).toBeUndefined()
    wrapper.unmount()
  })

  it('shows the closed parent and detaches with the child revision', async () => {
    const child = {
      ...task,
      parent_id: 'parent',
      parent: { id: 'parent', title: 'Parent', state: 'done' as const, deleted_at: null },
    }
    const wrapper = mount(TaskPanel, { ...options, props: { ...options.props, task: child } })
    expect(wrapper.text()).toContain('tasks.parentCompleted')
    await wrapper
      .findAll('button')
      .find((button) => button.text() === 'tasks.detach')!
      .trigger('click')
    await flushPromises()
    expect(mocks.operate).toHaveBeenCalledWith({
      kind: 'task.update',
      payload: { task_id: task.id, changes: { parent_id: null } },
      expected_revisions: { [task.id]: task.revision },
    })
    mocks.get.mockResolvedValueOnce({ ...task, id: 'parent', title: 'Parent' })
    await wrapper
      .findAll('button')
      .find((button) => button.text() === 'tasks.parent')!
      .trigger('click')
    await flushPromises()
    expect(wrapper.emitted('select')?.[0]?.[0]).toMatchObject({ id: 'parent' })
    wrapper.unmount()
  })

  it('keeps links inline with full text and swaps the field into an editor', async () => {
    const wrapper = mount(TaskPanel, {
      ...options,
      props: {
        ...options.props,
        task: {
          ...task,
          title: 'Read https://example.com/title',
          description: 'See https://example.com/details.',
        },
      },
    })
    expect(wrapper.findAll('a').map((link) => link.attributes('href'))).toEqual([
      'https://example.com/title',
      'https://example.com/details',
    ])
    expect(wrapper.get('[data-testid="task-description-text"]').text()).toBe(
      'See https://example.com/details.',
    )
    expect(wrapper.find('#task-description').exists()).toBe(false)
    await wrapper.get('#task-description-edit').trigger('click')
    await wrapper.get('#task-description').setValue('Changed https://example.com/new')
    await wrapper.get('#task-description').trigger('keydown', { key: 'Enter', ctrlKey: true })
    expect(wrapper.find('a[href="https://example.com/details"]').exists()).toBe(false)
    expect(wrapper.find('a[href="https://example.com/new"]').exists()).toBe(true)
    expect(mocks.operate).not.toHaveBeenCalled()
    wrapper.unmount()
  })
  it('keeps links available to viewers without enabling edits', () => {
    const wrapper = mount(TaskPanel, {
      ...options,
      props: {
        ...options.props,
        canWrite: false,
        task: { ...task, description: 'https://example.com/view' },
      },
    })
    expect(wrapper.get('a').attributes('href')).toBe('https://example.com/view')
    expect(wrapper.get('fieldset').attributes('disabled')).toBeDefined()
    wrapper.unmount()
  })
  it('starts from the task when a restored draft holds no changes', async () => {
    const wrapper = mount(TaskPanel, options)
    // A copy saved just before the panel closed: the task as it was, nothing typed.
    const old = { ...fieldsOf(task), title: 'Before the receipt', state: 'done' as const }
    mocks.draft!.value.value = { base: old, fields: old, revision: 0 }
    mocks.draft!.restored.value = true
    await nextTick()
    await nextTick()
    expect(wrapper.get('[data-testid="task-title-text"]').text()).toBe('Original')
    expect(mocks.draft!.restored.value).toBe(false)
    wrapper.unmount()
  })
  it('keeps a restored draft with changes', async () => {
    const wrapper = mount(TaskPanel, options)
    const base = fieldsOf(task)
    mocks.draft!.value.value = { base, fields: { ...base, title: 'Typed' }, revision: 1 }
    mocks.draft!.restored.value = true
    await nextTick()
    await nextTick()
    expect(wrapper.get('[data-testid="task-title-text"]').text()).toBe('Typed')
    expect(mocks.draft!.restored.value).toBe(true)
    wrapper.unmount()
  })
  it('keeps the revision of a dirty draft when query refresh brings a newer task', async () => {
    const wrapper = mount(TaskPanel, options)
    await wrapper.get('#task-due').setValue('2026-10-10')
    await wrapper.setProps({ task: { ...task, title: 'Renamed by someone else', revision: 2 } })
    expect(wrapper.get('[data-testid="task-title-text"]').text()).toBe('Original')
    await wrapper
      .findAll('button')
      .find((button) => button.text() === 'tasks.save')!
      .trigger('click')
    await flushPromises()
    expect(mocks.operate).toHaveBeenCalledWith({
      kind: 'task.update',
      payload: { task_id: 'task', changes: { due_on: '2026-10-10' } },
      expected_revisions: { task: 1 },
    })
    wrapper.unmount()
  })
  it('refreshes a clean draft and saves only a subsequent edit against the new revision', async () => {
    const wrapper = mount(TaskPanel, options)
    await wrapper.setProps({ task: { ...task, title: 'Renamed by someone else', revision: 2 } })
    expect(wrapper.get('[data-testid="task-title-text"]').text()).toBe('Renamed by someone else')
    await wrapper.get('#task-priority').setValue('1')
    await wrapper
      .findAll('button')
      .find((button) => button.text() === 'tasks.save')!
      .trigger('click')
    await flushPromises()
    expect(mocks.operate).toHaveBeenCalledWith({
      kind: 'task.update',
      payload: { task_id: 'task', changes: { priority: 1 } },
      expected_revisions: { task: 2 },
    })
    wrapper.unmount()
  })
  it('changes the state at once, and not over unsaved edits', async () => {
    mocks.operate.mockResolvedValue({
      operation_id: 'operation',
      kind: 'task.set_state',
      tasks: [{ ...task, state: 'in_progress', revision: 2 }],
    })
    const wrapper = mount(TaskPanel, options)
    const states = () => wrapper.get('[data-testid="task-state"]')
    await states()
      .findAll('button')
      .find((button) => button.text() === 'tasks.states.in_progress')!
      .trigger('click')
    await flushPromises()
    expect(mocks.operate).toHaveBeenCalledWith({
      kind: 'task.set_state',
      payload: { task_id: 'task', state: 'in_progress' },
      expected_revisions: { task: 1 },
    })
    await wrapper.get('#task-title-edit').trigger('click')
    await wrapper.get('#task-title').setValue('Edited')
    expect(states().attributes('disabled')).toBeDefined()
    expect(states().text()).toContain('tasks.saveBeforeState')
    wrapper.unmount()
  })
  it('saves pictures by id, once their upload is done', async () => {
    let finish: (image: unknown) => void = () => undefined
    mocks.upload.mockReset().mockReturnValue(new Promise((resolve) => (finish = resolve)))
    const kept = { id: 'kept', width: 40, height: 30 }
    const wrapper = mount(TaskPanel, {
      ...options,
      props: { ...options.props, task: { ...task, title_images: [kept] } },
    })
    expect(wrapper.get('[data-testid="task-images"] img').attributes('src')).toBe(
      '/v1/tasks/items/task/images/kept/preview?v=1',
    )
    await wrapper.get('#task-title-edit').trigger('click')
    await wrapper.get('#task-title').setValue('Edited')
    await wrapper.get('#task-description-edit').trigger('click')
    const paste = new Event('paste', { cancelable: true })
    Object.assign(paste, {
      clipboardData: {
        getData: () => '',
        files: [new File([new Uint8Array([1])], 'shot.png', { type: 'image/png' })],
        items: [],
      },
    })
    wrapper.get('#task-description').element.dispatchEvent(paste)
    await flushPromises()
    const save = () => wrapper.findAll('button').find((button) => button.text() === 'tasks.save')!
    expect(save().attributes('disabled')).toBeDefined()
    finish({ id: 'pasted', width: 1280, height: 800 })
    await flushPromises()
    expect(save().attributes('disabled')).toBeUndefined()
    await save().trigger('click')
    await flushPromises()
    expect(mocks.operate).toHaveBeenCalledWith({
      kind: 'task.update',
      payload: { task_id: 'task', changes: { title: 'Edited', description_images: ['pasted'] } },
      expected_revisions: { task: 1 },
    })
    wrapper.unmount()
  })
  it('offers saving only over an edit and leaves completing to the state switch', async () => {
    const wrapper = mount(TaskPanel, options)
    const texts = () => wrapper.findAll('button').map((button) => button.text())
    expect(wrapper.find('[data-testid="task-save-bar"]').exists()).toBe(false)
    expect(texts()).not.toContain('tasks.complete')
    expect(texts()).not.toContain('tasks.toTrash')
    expect(wrapper.find('button[aria-label="tasks.moreActions"]').exists()).toBe(true)
    await wrapper.get('#task-title-edit').trigger('click')
    await wrapper.get('#task-title').setValue('Edited')
    const bar = wrapper.get('[data-testid="task-save-bar"]')
    expect(bar.findAll('button').map((button) => button.text())).toEqual([
      'tasks.save',
      'common.cancel',
    ])
    await bar.findAll('button')[1]!.trigger('click')
    await flushPromises()
    expect(wrapper.get('[data-testid="task-title-text"]').text()).toBe('Original')
    expect(wrapper.find('[data-testid="task-save-bar"]').exists()).toBe(false)
    wrapper.unmount()
  })
  it('returns to reading when switching to another task during a field edit', async () => {
    const wrapper = mount(TaskPanel, options)
    await wrapper.get('#task-title-edit').trigger('click')
    await wrapper.get('#task-title').setValue('Pending edit')
    await wrapper.setProps({ task: { ...task, id: 'another', title: 'Another task' } })
    expect(wrapper.find('#task-title').exists()).toBe(false)
    expect(wrapper.get('[data-testid="task-title-text"]').text()).toBe('Another task')
    wrapper.unmount()
  })
  it('renders a viewer card without task mutation controls', () => {
    const wrapper = mount(TaskPanel, { ...options, props: { ...options.props, canWrite: false } })
    expect(wrapper.get('fieldset').attributes('disabled')).toBeDefined()
    expect(wrapper.find('button[aria-label="tasks.moreActions"]').exists()).toBe(false)
    expect(wrapper.findAll('button').some((button) => button.text() === 'tasks.save')).toBe(false)
    expect(wrapper.findAll('button').some((button) => button.text() === 'tasks.toTrash')).toBe(
      false,
    )
    wrapper.unmount()
  })
})

describe('task workspace', () => {
  const next = { ...task, id: 'next', title: 'Next task' }

  it('opens a loaded neighbour in a modal panel and resets only the panel scroll', async () => {
    const wrapper = mount(TaskPanel, { ...options, props: { ...options.props, next } })
    const navigation = wrapper.get('nav[aria-label="tasks.cardNavigation"]')
    expect(navigation.findAll('button')[0]!.attributes('disabled')).toBeDefined()
    await navigation.findAll('button')[1]!.trigger('click')
    expect(wrapper.emitted('select')).toEqual([[next]])
    await wrapper.setProps({ task: next, previous: task, next: null })
    await nextTick()
    expect(mocks.resetScroll).toHaveBeenCalledTimes(2)
    expect(wrapper.get('[data-testid="task-title-text"]').text()).toBe('Next task')
    wrapper.unmount()
  })

  it('keeps an unsaved edit in place until it is saved or discarded', async () => {
    const wrapper = mount(TaskPanel, { ...options, props: { ...options.props, next } })
    await wrapper.get('#task-priority').setValue('1')
    const navigation = wrapper.get('nav[aria-label="tasks.cardNavigation"]')
    expect(
      navigation.findAll('button').every((button) => button.attributes('disabled') !== undefined),
    ).toBe(true)
    expect(wrapper.text()).toContain('tasks.saveBeforeNavigation')
    await navigation.findAll('button')[1]!.trigger('click')
    expect(wrapper.emitted('select')).toBeUndefined()
    expect(wrapper.getComponent(TaskSteps).props('canWrite')).toBe(false)
    await wrapper.get('[data-testid="task-save-bar"]').findAll('button')[1]!.trigger('click')
    await flushPromises()
    expect(navigation.findAll('button')[1]!.attributes('disabled')).toBeUndefined()
    wrapper.unmount()
  })

  it('leaves navigation to the board when docked and presents steps before properties', () => {
    const wrapper = mount(TaskPanel, {
      ...options,
      props: { ...options.props, docked: true, next },
    })
    expect(wrapper.find('nav').exists()).toBe(false)
    const steps = wrapper.getComponent(TaskSteps).element
    const properties = wrapper.get('[data-testid="task-properties"]').element
    expect(
      steps.compareDocumentPosition(properties) & Node.DOCUMENT_POSITION_FOLLOWING,
    ).toBeTruthy()
    wrapper.unmount()
  })

  it('does not clear or close another task after an earlier save completes', async () => {
    let finish!: (value: object) => void
    mocks.operate.mockReturnValueOnce(
      new Promise((resolve) => {
        finish = resolve
      }),
    )
    const wrapper = mount(TaskPanel, { ...options, props: { ...options.props, next } })
    await wrapper.get('#task-priority').setValue('1')
    await wrapper.get('[data-testid="task-save-bar"]').findAll('button')[0]!.trigger('click')
    expect(wrapper.get('nav').findAll('button')[1]!.attributes('disabled')).toBeDefined()
    // A docked board can select another card while the receipt for this one is pending.
    await wrapper.setProps({ task: next })
    finish({})
    await flushPromises()
    expect(mocks.clear).toHaveBeenCalledWith('task:task')
    expect(wrapper.get('[data-testid="task-title-text"]').text()).toBe('Next task')
    expect(wrapper.emitted('update:open')).toBeUndefined()
    wrapper.unmount()
  })
})
