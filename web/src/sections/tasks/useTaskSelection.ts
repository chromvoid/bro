/**
 * The task open in the panel, named by the address: its own read and the newest copy the screen
 * holds, its tab, its neighbours in the list or the board column, and whether it may be changed.
 */
import { useQuery } from '@tanstack/vue-query'
import { computed, ref, shallowRef, watch, type Ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { api } from '@/core/client'
import { ApiError } from '@/core/http'
import type { useTaskLists } from '@/sections/circle/useCircle'
import { useSession } from '@/stores/session'
import { Task, newestTask } from './api'
import { taskNeighbours } from './taskWorkspace'
import type { useTaskListPages } from './useTaskListPages'

/** What the board shows of its snapshot (TaskBoard's exposed state). */
interface BoardSnapshot {
  loadedColumns: ReadonlyArray<{ tasks: Task[] }>
  readAtByTask: Record<string, number>
}

export interface SelectionOptions {
  /** A project shown as its board, and the board itself. */
  board: Readonly<Ref<boolean>>
  boardPanel: Readonly<Ref<BoardSnapshot | null>>
  list: ReturnType<typeof useTaskListPages>['list']
  tasks: Readonly<Ref<Task[]>>
  lists: ReturnType<typeof useTaskLists>
}

export function useTaskSelection({ board, boardPanel, list, tasks, lists }: SelectionOptions) {
  const route = useRoute()
  const router = useRouter()
  const session = useSession()
  const taskId = computed(() => String(route.params.task ?? route.query.task ?? ''))
  // A receipt advances the cache timestamp too. Only actual GETs may order live unread data.
  const detailRead = shallowRef<{ task: Task; readAt: number } | null>(null)
  const detail = useQuery({
    queryKey: computed(() => ['tasks', 'item', taskId.value]),
    enabled: computed(() => !!taskId.value),
    queryFn: async ({ signal }) => {
      const task = Task.parse(
        await api.get(`/v1/tasks/items/${encodeURIComponent(taskId.value)}`, signal),
      )
      signal.throwIfAborted()
      detailRead.value = { task, readAt: Date.now() }
      return task
    },
  })
  const panelOpen = computed({
    get: () => !!taskId.value,
    set: (value) => {
      if (!value)
        void router.replace({
          name: 'tasks',
          query: { ...route.query, task: undefined, task_tab: undefined },
        })
    },
  })
  // Reopened, the panel first shows its read cached from the last time it was open; the list may
  // already hold a later revision (the task was completed from the list meanwhile). A task picked
  // on the board shows at once while its own read arrives.
  const picked = ref<Task | null>(null)
  watch(
    () => detail.error.value,
    (error) => {
      if (!(error instanceof ApiError) || ![403, 404].includes(error.status)) return
      picked.value = null
      panelOpen.value = false
    },
  )
  const loadedTasks = computed(() =>
    board.value
      ? (boardPanel.value?.loadedColumns.flatMap((column) => column.tasks) ?? [])
      : tasks.value,
  )
  const taskTab = computed(() => {
    const tab = route.query.task_tab
    return tab === 'discussion' || tab === 'assistant' || tab === 'history' ? tab : 'details'
  })
  function setTaskTab(tab: string) {
    const selected =
      tab === 'discussion' || tab === 'assistant' || tab === 'history' ? tab : undefined
    if (route.query.task_tab !== selected)
      void router.replace({ query: { ...route.query, task_tab: selected } })
  }
  const discussionSource = computed(() => {
    const task = loadedTasks.value.find((item) => item.id === taskId.value)
    const page = list.data.value?.pages.find((item) =>
      item.items.some((task) => task.id === taskId.value),
    )
    const readAt = board.value
      ? (boardPanel.value?.readAtByTask[taskId.value] ?? 0)
      : (page?.readAt ?? 0)
    const read = detailRead.value?.task.id === taskId.value ? detailRead.value : null
    return read && (!task || read.readAt >= readAt) ? read.task : task
  })
  const selected = computed(() =>
    newestTask(
      detail.data.value,
      loadedTasks.value.find((task) => task.id === taskId.value) ??
        (picked.value?.id === taskId.value ? picked.value : null),
      discussionSource.value,
    ),
  )
  const neighbours = computed(() => taskNeighbours(selected.value, loadedTasks.value))
  const selectedList = computed(
    () => lists.data.value?.lists.find((item) => item.id === selected.value?.list_id) ?? null,
  )
  const selectedCanWrite = computed(
    () =>
      session.can('tasks.write') &&
      !!selectedList.value &&
      ['edit', 'manage'].includes(selectedList.value.effective),
  )
  function select(task: Task, tab?: 'discussion') {
    picked.value = task
    void router.push({ name: 'tasks', query: { ...route.query, task: task.id, task_tab: tab } })
  }
  const openDiscussion = (task: Task) => select(task, 'discussion')
  function taskDenied(id: string) {
    if (id !== taskId.value) return
    picked.value = null
    detailRead.value = null
    panelOpen.value = false
  }

  return {
    taskId,
    detail,
    /** The panel is open while the address names a task; closing it drops the name. */
    panelOpen,
    taskTab,
    setTaskTab,
    selected,
    neighbours,
    selectedCanWrite,
    select,
    openDiscussion,
    taskDenied,
  }
}
