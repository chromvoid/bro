import { flushPromises, mount } from '@vue/test-utils'
import { ref } from 'vue'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { Checkbox } from '@/components/ui/checkbox'
import { ApiError } from '@/core/http'
import { Task } from '@/sections/tasks/api'
import TaskCompletionDialog from '@/sections/tasks/TaskCompletionDialog.vue'
import ListView from './ListView.vue'

const mocks = vi.hoisted(() => ({
  operate: vi.fn(),
  push: vi.fn(),
  success: vi.fn(),
  error: vi.fn(),
}))
vi.mock('vue-i18n', async (original) => ({
  ...(await original<typeof import('vue-i18n')>()),
  useI18n: () => ({ t: (key: string) => key }),
}))
// The header's profile menu boots the plugin catalog and application router on import.
// This test owns list completion; isolate the already-stubbed header before loading its graph.
vi.mock('@/components/PageHeader.vue', () => ({
  default: { name: 'PageHeader', template: '<header />' },
}))
vi.mock('vue-router', () => ({
  useRoute: () => ({ params: { list: 'list' } }),
  useRouter: () => ({ push: mocks.push }),
}))
vi.mock('vue-sonner', () => ({ toast: { success: mocks.success, error: mocks.error } }))
vi.mock('@/stores/session', () => ({
  useSession: () => ({ me: { timezone: 'UTC' }, can: () => true }),
}))
vi.mock('@/core/operations', () => ({
  operationKind: () => 'task.complete',
  useLedger: () => ({
    operate: mocks.operate,
    unresolved: ref([]),
    check: vi.fn(),
    resend: vi.fn(),
    abandon: vi.fn(),
  }),
}))
vi.mock('@tanstack/vue-query', () => ({
  useQuery: () => query(null),
  useQueryClient: () => ({}),
  useInfiniteQuery: () => query(null),
}))
vi.mock('./useCircle', () => ({
  useTaskLists: () =>
    query({
      lists: [{ id: 'list', name: 'Shared plans', effective: 'manage', space_id: 'space' }],
    }),
  useSpaces: () => query({ spaces: [{ id: 'space', name: 'Family' }] }),
  useMembers: () => query({ members: [] }),
  useListTasks: () => query({ items: [task] }),
  useGrants: () => query(null),
  useCircleActions: () => ({ act: vi.fn(), post: vi.fn(), busy: ref(false) }),
  isGone: () => false,
}))
function query(data: unknown) {
  return {
    data: ref(data),
    error: ref(null),
    isPending: ref(false),
    isLoadingError: ref(false),
    isRefetchError: ref(false),
    isFetching: ref(false),
    refetch: vi.fn(),
  }
}
const task = Task.parse({
  id: 'task',
  list_id: 'list',
  title: 'Choose a hotel',
  status: 'open',
  revision: 2,
})
const mountList = () =>
  mount(ListView, {
    global: {
      stubs: {
        PageHeader: true,
        HeaderAction: true,
        Panel: true,
        TaskPanel: true,
        TaskCompletionDialog: true,
        UnresolvedWrites: true,
      },
    },
  })
function refusal(reason = 'incomplete_checklist') {
  return new ApiError(
    409,
    reason,
    {
      task_id: task.id,
      revision: task.revision,
      open_subtasks: reason === 'open_subtasks' ? 1 : 0,
      incomplete_checklist: 1,
    },
    null,
    null,
  )
}

beforeEach(() => {
  vi.clearAllMocks()
  mocks.operate.mockReset().mockResolvedValue({})
})

describe('shared list completion', () => {
  it('confirms only the displayed revision and reports success after the accepted write', async () => {
    mocks.operate.mockRejectedValueOnce(refusal())
    const wrapper = mountList()
    wrapper.getComponent(Checkbox).vm.$emit('update:modelValue', true)
    await flushPromises()
    expect(mocks.success).not.toHaveBeenCalled()
    wrapper.getComponent(TaskCompletionDialog).props('completion').confirm()
    await flushPromises()
    expect(mocks.operate).toHaveBeenLastCalledWith({
      kind: 'task.complete',
      payload: { task_id: task.id, confirm_checklist_revision: task.revision },
      expected_revisions: { [task.id]: task.revision },
    })
    expect(mocks.success).toHaveBeenCalledExactlyOnceWith('tasks.completed')
    wrapper.unmount()
  })

  it('never overrides open subtasks and can inspect the parent on the tasks page', async () => {
    mocks.operate.mockRejectedValueOnce(refusal('open_subtasks'))
    const wrapper = mountList()
    wrapper.getComponent(Checkbox).vm.$emit('update:modelValue', true)
    await flushPromises()
    const dialog = wrapper.getComponent(TaskCompletionDialog)
    const completion = dialog.props('completion')
    expect(completion.pending.value?.canConfirm).toBe(false)
    completion.cancel()
    dialog.vm.$emit('inspect', task)
    await flushPromises()
    expect(mocks.operate).toHaveBeenCalledTimes(1)
    expect(mocks.success).not.toHaveBeenCalled()
    expect(mocks.error).not.toHaveBeenCalled()
    expect(mocks.push).toHaveBeenCalledWith({
      name: 'tasks',
      query: { list: task.list_id, task: task.id },
    })
    wrapper.unmount()
  })
})
