import { enableAutoUnmount, mount } from '@vue/test-utils'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { defineComponent, h, nextTick, onMounted, onUnmounted, ref, toValue, type Ref } from 'vue'
import { TaskList } from '@/sections/circle/api'
import { Task } from './api'
import TaskPanel from './TaskPanel.vue'
import TasksView from './TasksView.vue'

const mocks = vi.hoisted(() => ({
  lists: vi.fn(),
  query: vi.fn(),
  discussionMounted: vi.fn(),
  discussionUnmounted: vi.fn(),
  observers: [] as Array<{
    target: Ref<HTMLElement | null>
    callback: (entries: Array<{ target: Element; contentRect: { width: number } }>) => void
  }>,
}))
// core/dates labels through the shared i18n instance, so the module keeps its real createI18n.
vi.mock('vue-i18n', async (original) => ({
  ...(await original<typeof import('vue-i18n')>()),
  useI18n: () => ({ t: (key: string) => key }),
}))
vi.mock('@/components/PageHeader.vue', () => ({ default: { template: '<header />' } }))
vi.mock('vue-router', () => ({
  useRoute: () => ({
    name: 'tasks',
    params: {},
    query: { list: 'project', task: 'task', task_tab: 'discussion' },
  }),
  useRouter: () => ({ push: vi.fn(), replace: vi.fn() }),
}))
vi.mock('@vueuse/core', async (original) => ({
  ...(await original<typeof import('@vueuse/core')>()),
  useMediaQuery: () => ref(true),
  useResizeObserver: (
    target: (typeof mocks.observers)[number]['target'],
    callback: (typeof mocks.observers)[number]['callback'],
  ) => mocks.observers.push({ target, callback }),
}))
vi.mock('@tanstack/vue-query', async (original) => ({
  ...(await original<typeof import('@tanstack/vue-query')>()),
  useQuery: mocks.query,
  useQueryClient: () => ({}),
}))
vi.mock('@/stores/session', () => ({
  useSession: () => ({ me: { id: 'user', timezone: 'UTC' }, can: () => false }),
}))
vi.mock('@/core/operations', () => ({
  operationKind: () => 'task.update',
  useLedger: () => ({ unresolved: ref([]), operate: vi.fn() }),
}))
vi.mock('@/core/useDraft', () => ({
  useDraft: (_target: unknown, empty: () => object) => {
    const value = ref(empty())
    return {
      value,
      restored: ref(false),
      start: (data: object) => (value.value = data),
      clear: vi.fn(),
    }
  },
}))
vi.mock('@/sections/circle/useCircle', () => ({
  useTaskLists: mocks.lists,
  useMembers: () => ({ data: ref({ members: [] }) }),
}))
vi.mock('./useTaskActions', () => ({ useTaskActions: () => ({}) }))
vi.mock('./useTaskListActions', () => ({
  useTaskListActions: () => ({ title: ref(''), adding: ref(false), actions: { completion: {} } }),
}))
vi.mock('./useTaskListPages', () => ({
  TONES: {},
  useTaskListPages: () => ({
    boardResults: ref({ total: 0, pending: false }),
    list: {
      data: ref(undefined),
      isPending: ref(false),
      isLoadingError: ref(false),
      hasNextPage: ref(false),
    },
    tasks: ref([]),
    today: ref('2026-10-17'),
  }),
}))

enableAutoUnmount(afterEach)
const listsData = ref<{ lists: TaskList[] }>()
const listsPending = ref(true)
const listsLoadingError = ref(false)
const listsError = ref<Error | null>(null)
const frames = new Map<number, FrameRequestCallback>()
let sequence = 0
const task = Task.parse({
  id: 'task',
  list_id: 'project',
  title: 'Project task',
  status: 'open',
  revision: 1,
})
const project = TaskList.parse({
  id: 'project',
  name: 'Project',
  list_kind: 'project',
  member_access: 'none',
  effective: 'manage',
  revision: 1,
})
const Discussion = defineComponent({
  setup() {
    onMounted(mocks.discussionMounted)
    onUnmounted(mocks.discussionUnmounted)
    return () => h('div', { 'data-testid': 'discussion' })
  },
})

beforeEach(() => {
  localStorage.clear()
  listsData.value = undefined
  listsPending.value = true
  listsLoadingError.value = false
  listsError.value = null
  mocks.observers.splice(0)
  mocks.discussionMounted.mockClear()
  mocks.discussionUnmounted.mockClear()
  mocks.lists.mockReturnValue({
    data: listsData,
    isPending: listsPending,
    isLoadingError: listsLoadingError,
    error: listsError,
    refetch: vi.fn(),
  })
  mocks.query.mockImplementation(({ queryKey }: { queryKey: Ref<string[]> }) => ({
    data: ref(toValue(queryKey)[1] === 'item' ? task : { channel_id: 'discussion' }),
    error: ref(null),
    isLoadingError: ref(false),
    refetch: vi.fn(),
  }))
  frames.clear()
  sequence = 0
  vi.stubGlobal('requestAnimationFrame', (callback: FrameRequestCallback) => {
    frames.set(++sequence, callback)
    return sequence
  })
  vi.stubGlobal('cancelAnimationFrame', (id: number) => frames.delete(id))
  vi.stubGlobal('getComputedStyle', () => ({ fontSize: '16px' }))
})
afterEach(() => vi.unstubAllGlobals())

function screen() {
  return mount(TasksView, {
    global: {
      stubs: {
        PageHeader: true,
        TaskFilters: true,
        SegmentedControl: true,
        DropdownMenu: true,
        UnresolvedWrites: true,
        ProjectHeader: true,
        ProjectPanel: true,
        RefreshFailed: true,
        TaskListMenu: true,
        TaskCompletionDialog: true,
        TaskDiscussion: Discussion,
        TaskBoard: {
          data: () => ({ loadedColumns: [], readAtByTask: {} }),
          template: '<div />',
        },
        Sheet: { props: ['open'], template: '<div v-if="open"><slot /></div>' },
        SheetContent: { template: '<section><slot /></section>' },
        SheetHeader: { template: '<header data-slot="sheet-header"><slot /></header>' },
        SheetTitle: { template: '<h2><slot /></h2>' },
        SheetClose: { template: '<span><slot /></span>' },
      },
    },
  })
}

async function receiveLists() {
  listsData.value = { lists: [project] }
  listsPending.value = false
  listsLoadingError.value = false
  listsError.value = null
  await nextTick()
}

async function failLists() {
  listsPending.value = false
  listsLoadingError.value = true
  listsError.value = new Error('Lists unavailable')
  await nextTick()
}

async function resyncLists() {
  listsData.value = undefined
  listsPending.value = true
  listsLoadingError.value = false
  listsError.value = null
  await nextTick()
}

function resize(width: number) {
  for (const { target, callback } of mocks.observers)
    callback([{ target: target.value!, contentRect: { width } }])
}

async function drawNextObserver() {
  const [id, callback] = frames.entries().next().value!
  frames.delete(id)
  callback(0)
  await nextTick()
}

async function draw() {
  while (frames.size) await drawNextObserver()
}

describe('task panel initial layout', () => {
  it.each([
    { width: 1024, docked: false },
    { width: 1120, docked: true },
  ])(
    'waits for the project lists at $width px and mounts discussion once',
    async ({ width, docked }) => {
      const wrapper = screen()
      await nextTick()
      expect(wrapper.findComponent(TaskPanel).exists()).toBe(false)
      expect(mocks.discussionMounted).not.toHaveBeenCalled()

      resize(width)
      await draw()
      // At 1024px an ordinary list has room for a column, but this project's board does not.
      expect(wrapper.findComponent(TaskPanel).exists()).toBe(false)
      expect(mocks.discussionMounted).not.toHaveBeenCalled()

      await receiveLists()
      expect(wrapper.getComponent(TaskPanel).props('docked')).toBe(docked)
      expect(wrapper.find('aside[data-testid="task-panel"]').exists()).toBe(docked)
      expect(wrapper.getComponent(TaskPanel).find('[data-slot="sheet-header"]').exists()).toBe(
        !docked,
      )
      const discussion = wrapper.get('[data-testid="discussion"]').element
      expect(mocks.discussionMounted).toHaveBeenCalledTimes(1)

      await receiveLists()
      resize(width)
      await draw()
      expect(wrapper.get('[data-testid="discussion"]').element).toBe(discussion)
      expect(mocks.discussionMounted).toHaveBeenCalledTimes(1)
      expect(mocks.discussionUnmounted).not.toHaveBeenCalled()
    },
  )

  it('waits for the board measurement when its lists arrive before the first frame', async () => {
    const wrapper = screen()
    await receiveLists()
    expect(wrapper.findComponent(TaskPanel).exists()).toBe(false)
    expect(mocks.discussionMounted).not.toHaveBeenCalled()

    resize(1120)
    // ResizeObserver delivery alone does not commit the width; each observer applies it in a frame.
    expect(wrapper.findComponent(TaskPanel).exists()).toBe(false)
    await drawNextObserver()
    expect(wrapper.findComponent(TaskPanel).exists()).toBe(false)
    expect(mocks.discussionMounted).not.toHaveBeenCalled()

    await draw()
    expect(wrapper.getComponent(TaskPanel).props('docked')).toBe(true)
    expect(wrapper.find('aside[data-testid="task-panel"]').exists()).toBe(true)
    expect(mocks.discussionMounted).toHaveBeenCalledTimes(1)
    expect(mocks.discussionUnmounted).not.toHaveBeenCalled()
  })

  it('keeps the mounted panel through repeated list errors and pending resyncs', async () => {
    const wrapper = screen()
    await nextTick()
    resize(1024)
    await draw()
    expect(wrapper.findComponent(TaskPanel).exists()).toBe(false)
    expect(mocks.discussionMounted).not.toHaveBeenCalled()

    await failLists()
    const panel = wrapper.getComponent(TaskPanel)
    const panelInstance = panel.vm.$
    const column = panel.get('aside[data-testid="task-panel"]').element
    const discussion = wrapper.get('[data-testid="discussion"]').element
    expect(panel.props('docked')).toBe(true)
    expect(mocks.discussionMounted).toHaveBeenCalledTimes(1)

    for (const updateLists of [resyncLists, failLists, resyncLists]) {
      await updateLists()
      expect(wrapper.getComponent(TaskPanel).vm.$).toBe(panelInstance)
      expect(wrapper.get('aside[data-testid="task-panel"]').element).toBe(column)
      expect(wrapper.get('[data-testid="discussion"]').element).toBe(discussion)
      expect(mocks.discussionMounted).toHaveBeenCalledTimes(1)
      expect(mocks.discussionUnmounted).not.toHaveBeenCalled()
    }
  })

  it('changes to a sheet when a later successful list read identifies the project', async () => {
    const wrapper = screen()
    await nextTick()
    resize(1024)
    await draw()
    await failLists()
    const panelInstance = wrapper.getComponent(TaskPanel).vm.$
    const discussion = wrapper.get('[data-testid="discussion"]').element
    expect(wrapper.getComponent(TaskPanel).props('docked')).toBe(true)

    await resyncLists()
    expect(wrapper.get('[data-testid="discussion"]').element).toBe(discussion)
    await receiveLists()
    expect(wrapper.getComponent(TaskPanel).vm.$).toBe(panelInstance)
    expect(wrapper.getComponent(TaskPanel).props('docked')).toBe(false)
    expect(wrapper.find('aside[data-testid="task-panel"]').exists()).toBe(false)
    expect(wrapper.getComponent(TaskPanel).find('[data-slot="sheet-header"]').exists()).toBe(true)
    expect(wrapper.get('[data-testid="discussion"]').element).not.toBe(discussion)
    expect(mocks.discussionMounted).toHaveBeenCalledTimes(2)
    expect(mocks.discussionUnmounted).toHaveBeenCalledTimes(1)
  })
})
