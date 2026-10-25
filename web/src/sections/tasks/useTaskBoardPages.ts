import { hashKey, useQuery, useQueryClient, type QueryClient } from '@tanstack/vue-query'
import { computed, nextTick, onScopeDispose, reactive, ref, shallowRef, watch, type Ref } from 'vue'
import { api } from '@/core/client'
import { ApiError } from '@/core/http'
import { STATES, TaskBoardPage, TaskPage, boardQuery, type Task, type TaskState } from './api'
import { applyTaskFilterParams, readTaskFilters } from './taskFilters'

export interface TaskBoardInput {
  listId: string
  sort: string
  q: string
  filters?: Record<string, string>
}

/** Every column contains all its loaded pages from the same server snapshot. */
export interface TaskBoardPages extends TaskBoardPage {
  pageDepths: Record<TaskState, number>
  /** Live discussion counts belong to the GET that supplied each retained row. */
  readAtByTask: Record<string, number>
}

function perState<T>(value: () => T): Record<TaskState, T> {
  return Object.fromEntries(STATES.map((state) => [state, value()])) as Record<TaskState, T>
}

function unique(items: Task[]): Task[] {
  const seen = new Set<string>()
  return items.filter((item) => {
    if (seen.has(item.id)) return false
    seen.add(item.id)
    return true
  })
}

function stale(): ApiError {
  return new ApiError(409, 'cursor_stale', null, null, null)
}

function isStatus(error: unknown, ...statuses: number[]): error is ApiError {
  return error instanceof ApiError && statuses.includes(error.status)
}

function record(value: unknown): Record<string, unknown> | null {
  return value && typeof value === 'object' ? (value as Record<string, unknown>) : null
}

/** Only the task projection shapes used by list, board, children and detail queries. */
function taskRows(value: unknown): Record<string, unknown>[] {
  const data = record(value)
  if (!data) return []
  if (Array.isArray(data.pages)) return data.pages.flatMap(taskRows)
  const columns = record(data.columns)
  if (columns) return STATES.flatMap((state) => taskRows(columns[state]))
  if (Array.isArray(data.items))
    return data.items.flatMap((item) => {
      const row = record(item)
      return row ? [row] : []
    })
  return typeof data.id === 'string' && typeof data.title === 'string' ? [data] : []
}

function queryList(key: readonly unknown[]): unknown {
  if (key[0] !== 'tasks') return undefined
  if (key[1] === 'list') return key[2]
  if (key[1] === 'items' && key[2] === 'board') return key[3]
  // The Today card has a shorter key and is a mixed projection, not a list called "card".
  if (key[1] === 'items' && key.length >= 7) return key[4]
  return undefined
}

export function evictList(queries: QueryClient, listId: string, reason: ApiError) {
  const cache = queries.getQueryCache()
  const all = cache.getAll()
  const ids = new Set<string>()
  const channels = new Set<string>()
  const remember = (row: Record<string, unknown>) => {
    if (typeof row.id === 'string') ids.add(row.id)
    if (typeof row.parent_id === 'string') ids.add(row.parent_id)
    if (typeof row.discussion_channel_id === 'string') channels.add(row.discussion_channel_id)
  }
  for (const query of all) {
    if (query.queryKey[0] !== 'tasks') continue
    const bound = queryList(query.queryKey) === listId
    for (const row of taskRows(query.state.data))
      if (row.list_id === listId || (bound && row.list_id === undefined)) remember(row)
  }
  const channelCatalog = all.find(
    (query) => query.queryKey[0] === 'community' && query.queryKey[1] === 'channels',
  )
  const catalog = record(channelCatalog?.state.data)
  if (Array.isArray(catalog?.channels)) {
    for (const value of catalog.channels) {
      const channel = record(value)
      if (
        !channel ||
        (channel.audience_resource_id !== listId &&
          !(channel.subject_kind === 'task' && ids.has(String(channel.subject_id))))
      )
        continue
      if (typeof channel.id === 'string') channels.add(channel.id)
      if (channel.subject_kind === 'task' && typeof channel.subject_id === 'string')
        ids.add(channel.subject_id)
    }
  }
  // A cached children page can identify tasks that were never loaded on the board itself.
  for (const query of all) {
    if (query.queryKey[0] !== 'tasks' || !ids.has(String(query.queryKey[2]))) continue
    if (query.queryKey[1] === 'children') taskRows(query.state.data).forEach(remember)
  }
  for (const query of all) {
    if (query.queryKey[0] !== 'tasks' || !ids.has(String(query.queryKey[2]))) continue
    if (query.queryKey[1] === 'discussion') {
      const channel = record(query.state.data)?.channel_id
      if (typeof channel === 'string') channels.add(channel)
    }
  }
  const taskQueries = new Set([
    'item',
    'children',
    'discussion',
    'assistant',
    'assistant-capabilities',
    'history',
  ])
  for (const query of all) {
    const key = query.queryKey
    const data = record(query.state.data)
    const collection =
      key[0] === 'tasks' && key[1] === 'lists'
        ? 'lists'
        : key[0] === 'community' && key[1] === 'channels'
          ? 'channels'
          : null
    if (collection && Array.isArray(data?.[collection])) {
      const rows = data[collection] as unknown[]
      const retained = rows.filter((value) => {
        const id = record(value)?.id
        return collection === 'lists' ? id !== listId : !channels.has(String(id))
      })
      if (retained.length !== rows.length) {
        void query.cancel({ silent: true, revert: false })
        queries.setQueryData(key, { ...data, [collection]: retained })
        void queries.invalidateQueries({ queryKey: key, exact: true, refetchType: 'none' })
      }
      continue
    }
    const related =
      key[0] === 'tasks'
        ? queryList(key) === listId ||
          (taskQueries.has(String(key[1])) && ids.has(String(key[2]))) ||
          taskRows(data).some(
            (row) => row.list_id === listId || (!queryList(key) && ids.has(String(row.id))),
          )
        : key[0] === 'community' &&
          ((key[1] === 'grants' && key[2] === listId) ||
            (key[1] === 'messages' && channels.has(String(key[2] === 'task' ? key[3] : key[2]))))
    if (!related) continue
    // Clearing a mixed paginated projection also discards its old cursors. Independent
    // personal/other-list queries stay intact. Cancel without reverting to its previous data,
    // then notify mounted observers before eviction: removal alone retains their old result.
    void query.cancel({ silent: true, revert: false })
    query.setState({
      data: undefined,
      error: reason,
      status: 'error',
      fetchStatus: 'idle',
      isInvalidated: true,
      dataUpdatedAt: 0,
      errorUpdatedAt: Date.now(),
    })
    cache.remove(query)
  }
}

/**
 * A disabled observer keeps the aggregate cache alive. This hook schedules reads itself:
 * invalidations during a read set a dirty bit instead of cancelling a multi-page refresh.
 * Explicit callers use refetch(), while the normal domain invalidations still refresh it.
 */
export function useTaskBoardPages(
  input: () => TaskBoardInput,
  container?: Readonly<Ref<HTMLElement | null>>,
) {
  const queries = useQueryClient()
  const criteria = computed(() => {
    const value = input()
    const filters = Object.fromEntries(
      Object.entries(value.filters ?? {}).sort(([a], [b]) => a.localeCompare(b)),
    )
    const options = { ...value, filters }
    const key = ['tasks', 'items', 'board', value.listId, value.sort, value.q, filters] as const
    return { options, key, identity: hashKey(key) }
  })
  const observerVersion = ref(0)
  useQuery<TaskBoardPages>(
    computed(() => ({
      queryKey: criteria.value.key,
      enabled: false,
      meta: { boardObserverVersion: observerVersion.value },
    })),
  )

  type Context = (typeof criteria)['value'] & {
    disposed: boolean
    dirty: boolean
    queued: boolean
    refresh: Promise<void> | null
    read: AbortController | null
    more: Partial<Record<TaskState, AbortController>>
    requestedDepths: Record<TaskState, number>
  }
  let current: Context | undefined
  let disposed = false
  const display = shallowRef<{ context: Context; board: TaskBoardPages }>()
  const data = computed(() => display.value?.board)
  const error = shallowRef<unknown>(null)
  const isFetching = ref(false)
  const loadingMore = reactive(perState(() => false))
  const moreError = reactive(perState<unknown>(() => null))
  const alive = (context: Context) => !disposed && current === context && !context.disposed

  function stopMore(context: Context) {
    for (const state of STATES) {
      context.more[state]?.abort()
      delete context.more[state]
      if (alive(context)) loadingMore[state] = false
    }
  }

  function stop(context: Context) {
    context.disposed = true
    context.read?.abort()
    stopMore(context)
  }

  function denied(context: Context, reason: ApiError) {
    if (!alive(context)) return
    stopMore(context)
    display.value = undefined
    error.value = reason
    evictList(queries, context.options.listId, reason)
  }

  function boardParams(context: Context): URLSearchParams {
    const { listId, sort, q, filters } = context.options
    const params = new URLSearchParams(filters)
    params.set('list_id', listId)
    params.set('sort', sort)
    applyTaskFilterParams(params, readTaskFilters({ ...filters, q }))
    return params
  }

  function pageParams(context: Context, state: TaskState, cursor: string): URLSearchParams {
    return boardQuery({ ...context.options, state, cursor })
  }

  async function read(context: Context, signal: AbortSignal): Promise<TaskBoardPages> {
    const old = queries.getQueryData<TaskBoardPages>(context.key)
    const board = TaskBoardPage.parse(
      await api.get(`/v1/tasks/board?${boardParams(context)}`, signal),
    )
    const readAt = Date.now()
    signal.throwIfAborted()
    const readAtByTask = Object.fromEntries(
      STATES.flatMap((state) => board.columns[state].items.map((item) => [item.id, readAt])),
    ) as Record<string, number>
    const pageDepths = perState(() => 1)
    for (const state of STATES) {
      const column = board.columns[state]
      if (column.snapshot !== board.snapshot) throw stale()
      if (column.next_cursor && !column.items.length) throw stale()
      column.items = unique(column.items)
      const depth = Math.max(old?.pageDepths[state] ?? 1, context.requestedDepths[state])
      while (column.next_cursor && pageDepths[state] < depth) {
        const page = TaskPage.parse(
          await api.get(
            `/v1/tasks/items?${pageParams(context, state, column.next_cursor)}`,
            signal,
          ),
        )
        const pageReadAt = Date.now()
        signal.throwIfAborted()
        if (page.snapshot !== board.snapshot || page.next_cursor === column.next_cursor)
          throw stale()
        const items = unique([...column.items, ...page.items])
        if (page.next_cursor && items.length === column.items.length) throw stale()
        for (const item of page.items) readAtByTask[item.id] ??= pageReadAt
        column.items = items
        column.next_cursor = page.next_cursor
        pageDepths[state]++
      }
    }
    return { ...board, pageDepths, readAtByTask }
  }

  async function fresh(context: Context, signal: AbortSignal): Promise<TaskBoardPages> {
    try {
      return await read(context, signal)
    } catch (reason) {
      signal.throwIfAborted()
      // A stale cursor discards the entire staged read, including every fresh cursor.
      if (!isStatus(reason, 409)) throw reason
      return read(context, signal)
    }
  }

  function refresh(context: Context): Promise<void> {
    if (!alive(context)) return Promise.resolve()
    if (context.refresh) return context.refresh
    stopMore(context)
    isFetching.value = true
    const controller = new AbortController()
    context.read = controller
    // Assign the promise before beginning the first read: a synchronous invalidation can then
    // only dirty this pass, never admit a second concurrent refresh.
    context.refresh = Promise.resolve()
      .then(async () => {
        if (!alive(context) || controller.signal.aborted) return
        do {
          context.dirty = false
          // Query.invalidate emits once until this flag is cleared. Consume the triggering
          // event now so the very first event DURING this pass cannot disappear.
          queries.getQueryCache().find({ queryKey: context.key, exact: true })?.setState({
            isInvalidated: false,
          })
          try {
            const board = await fresh(context, controller.signal)
            if (!alive(context) || controller.signal.aborted) return
            if (!context.dirty) {
              error.value = null
              for (const state of STATES) moreError[state] = null
              context.requestedDepths = { ...board.pageDepths }
              queries.setQueryData(context.key, board)
            }
          } catch (reason) {
            if (!alive(context) || controller.signal.aborted) return
            if (isStatus(reason, 403, 404)) {
              denied(context, reason)
              return
            }
            error.value = reason
          }
        } while (context.dirty && alive(context))
      })
      .finally(() => {
        context.refresh = null
        context.read = null
        if (alive(context)) {
          isFetching.value = false
          // An event may arrive in the microtask between the final publication and finally.
          if (context.dirty) queueRefresh(context)
        }
      })
    return context.refresh
  }

  function queueRefresh(context: Context) {
    context.dirty = true
    if (context.refresh || context.queued) return
    context.queued = true
    queueMicrotask(() => {
      context.queued = false
      if (alive(context)) void refresh(context)
    })
  }

  const unsubscribe = queries.getQueryCache().subscribe((event) => {
    const context = current
    if (!context || !alive(context) || event.query.queryHash !== context.identity) return
    if (event.type === 'removed') {
      stop(context)
      display.value = undefined
      isFetching.value = false
      for (const state of STATES) loadingMore[state] = false
    } else if (event.type === 'updated') {
      if (event.action.type === 'invalidate') queueRefresh(context)
      else if (event.action.type === 'success' && event.query.state.data) {
        display.value = {
          context,
          board: event.query.state.data as TaskBoardPages,
        }
      }
    }
  })

  function start() {
    if (current) stop(current)
    const context: Context = {
      ...criteria.value,
      disposed: false,
      dirty: false,
      queued: false,
      refresh: null,
      read: null,
      more: {},
      requestedDepths: perState(() => 1),
    }
    current = context
    // Removal on access refusal detaches the old query from the cache. Rebuild before the
    // request so invalidations during a permission retry also reach this subscription.
    queries.getQueryCache().build(queries, { queryKey: context.key })
    observerVersion.value++
    const cached = queries.getQueryData<TaskBoardPages>(context.key)
    display.value = cached ? { context, board: cached } : undefined
    error.value = null
    for (const state of STATES) {
      loadingMore[state] = false
      moreError[state] = null
    }
    void refresh(context)
  }
  watch(() => criteria.value.identity, start, { immediate: true, flush: 'sync' })

  async function more(state: TaskState): Promise<void> {
    const context = current
    const board = context && queries.getQueryData<TaskBoardPages>(context.key)
    const cursor = board?.columns[state].next_cursor
    if (!context || !alive(context) || loadingMore[state] || !board || !cursor) return
    // A click can arrive as an invalidation starts. Keep its requested depth even when the
    // refresh owns the network read, or aborts a continuation that already began.
    if (context.requestedDepths[state] > board.pageDepths[state] && context.refresh) {
      await context.refresh
      return
    }
    context.requestedDepths[state] = board.pageDepths[state] + 1
    if (context.refresh) {
      queueRefresh(context)
      await context.refresh
      return
    }
    const controller = new AbortController()
    context.more[state] = controller
    loadingMore[state] = true
    moreError[state] = null
    try {
      const page = TaskPage.parse(
        await api.get(`/v1/tasks/items?${pageParams(context, state, cursor)}`, controller.signal),
      )
      const pageReadAt = Date.now()
      if (!alive(context) || controller.signal.aborted) return
      if (page.snapshot !== board.snapshot || page.next_cursor === cursor) throw stale()
      if (
        page.next_cursor &&
        unique([...board.columns[state].items, ...page.items]).length ===
          board.columns[state].items.length
      )
        throw stale()
      // Another column may have appended since this request began. Merge into that current
      // aggregate, never the captured board, and only if its cursor/snapshot still match.
      queries.setQueryData<TaskBoardPages>(context.key, (latest) => {
        if (!latest || latest.snapshot !== board.snapshot) return latest
        const column = latest.columns[state]
        if (column.next_cursor !== cursor) return latest
        const readAtByTask = { ...latest.readAtByTask }
        for (const item of page.items) readAtByTask[item.id] ??= pageReadAt
        return {
          ...latest,
          readAtByTask,
          columns: {
            ...latest.columns,
            [state]: {
              ...column,
              items: unique([...column.items, ...page.items]),
              next_cursor: page.next_cursor,
            },
          },
          pageDepths: { ...latest.pageDepths, [state]: latest.pageDepths[state] + 1 },
        }
      })
    } catch (reason) {
      if (!alive(context) || controller.signal.aborted) return
      if (isStatus(reason, 403, 404)) denied(context, reason)
      else if (isStatus(reason, 409)) {
        await refresh(context)
        if (alive(context)) moreError[state] = error.value
      } else {
        context.requestedDepths[state] = board.pageDepths[state]
        moreError[state] = reason
      }
    } finally {
      if (context.more[state] === controller) {
        delete context.more[state]
        if (alive(context)) loadingMore[state] = false
      }
    }
  }

  let publication = 0
  watch(
    display,
    (after, before) => {
      const ticket = ++publication
      const context = current
      const root = container?.value
      if (!context || !root || !after || before?.context !== after.context) return
      // Capture immediately before Vue replaces the cards, rather than before the network
      // request: the person may have scrolled while waiting for the refreshed board.
      const anchor = captureAnchor(root)
      if (!anchor) return
      void nextTick(() => {
        if (alive(context) && ticket === publication && container?.value === root)
          restoreAnchor(root, anchor)
      })
    },
    { flush: 'pre' },
  )

  onScopeDispose(() => {
    disposed = true
    if (current) stop(current)
    unsubscribe()
  })

  return {
    data,
    error,
    isFetching,
    isPending: computed(() => !data.value && !error.value),
    isLoadingError: computed(() => !data.value && !!error.value),
    isRefetchError: computed(() => !!data.value && !!error.value),
    refetch: () => {
      if (disposed) return Promise.resolve()
      if (current?.disposed) start()
      return current ? refresh(current) : Promise.resolve()
    },
    more,
    loadingMore,
    moreError,
  }
}

interface BoardAnchor {
  scroll: HTMLElement | null
  candidates: Array<{ id: string; top: number; left: number }>
}

function captureAnchor(root: HTMLElement): BoardAnchor | null {
  let scroll = root.parentElement
  while (scroll) {
    const overflow = getComputedStyle(scroll).overflowY
    if (['auto', 'scroll'].includes(overflow) && scroll.scrollHeight > scroll.clientHeight) break
    scroll = scroll.parentElement
  }
  const board = root.getBoundingClientRect()
  const viewport = scroll?.getBoundingClientRect()
  const top = Math.max(0, viewport?.top ?? 0)
  const bottom = Math.min(window.innerHeight, viewport?.bottom ?? window.innerHeight)
  const left = Math.max(0, board.left)
  const right = Math.min(window.innerWidth, board.right)
  const elements = [...root.querySelectorAll<HTMLElement>('button[data-task]')]
  const visible = elements.find((element) => {
    const rect = element.getBoundingClientRect()
    return rect.bottom > top && rect.top < bottom && rect.right > left && rect.left < right
  })
  if (!visible) return null
  const column = visible.closest('[data-state]') ?? root
  const siblings = [...column.querySelectorAll<HTMLElement>('button[data-task]')]
  const selected = siblings.indexOf(visible)
  // If the selected task disappears, retain the nearest surviving task's OWN old offset.
  const candidates = siblings
    .map((element, index) => ({ element, index }))
    .sort(
      (a, b) => Math.abs(a.index - selected) - Math.abs(b.index - selected) || b.index - a.index,
    )
    .map(({ element }) => {
      const rect = element.getBoundingClientRect()
      return { id: element.dataset.task!, top: rect.top, left: rect.left }
    })
  return { scroll, candidates }
}

function restoreAnchor(root: HTMLElement, anchor: BoardAnchor) {
  const elements = new Map(
    [...root.querySelectorAll<HTMLElement>('button[data-task]')].map((element) => [
      element.dataset.task,
      element,
    ]),
  )
  for (const candidate of anchor.candidates) {
    const element = elements.get(candidate.id)
    if (!element) continue
    const rect = element.getBoundingClientRect()
    root.scrollLeft += rect.left - candidate.left
    const delta = rect.top - candidate.top
    if (anchor.scroll) anchor.scroll.scrollTop += delta
    else if (delta) window.scrollBy({ top: delta, behavior: 'instant' })
    return
  }
}
