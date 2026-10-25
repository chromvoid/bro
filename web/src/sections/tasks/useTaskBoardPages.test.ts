import { QueryClient, QueryObserver, VueQueryPlugin } from '@tanstack/vue-query'
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { defineComponent, h, nextTick, ref } from 'vue'
import { ApiError } from '@/core/http'
import { STATES, newestTask, type Task, type TaskBoardPage, type TaskState } from './api'
import { useTaskBoardPages, type TaskBoardInput, type TaskBoardPages } from './useTaskBoardPages'

const mocks = vi.hoisted(() => ({ get: vi.fn() }))
vi.mock('@/core/client', () => ({ api: mocks }))

const task = (id: string): Task => ({ id, title: id, status: 'open', revision: 1 })
const tasks = (start: number, count: number) =>
  Array.from({ length: count }, (_, index) => task(`task-${start + index}`))
const page = (snapshot: string, items: Task[], next_cursor: string | null = null) => ({
  snapshot,
  total: items.length,
  items,
  next_cursor,
  today: '2026-10-08',
  timezone: 'Europe/Moscow',
})
function board(
  snapshot: string,
  columns: Partial<
    Record<TaskState, { items: Task[]; next_cursor?: string | null; total?: number }>
  > = {},
): TaskBoardPage {
  return {
    snapshot,
    total: STATES.reduce(
      (sum, state) => sum + (columns[state]?.total ?? columns[state]?.items.length ?? 0),
      0,
    ),
    today: '2026-10-08',
    timezone: 'Europe/Moscow',
    columns: Object.fromEntries(
      STATES.map((state) => [
        state,
        {
          snapshot,
          total: columns[state]?.items.length ?? 0,
          items: [],
          next_cursor: null,
          ...columns[state],
        },
      ]),
    ) as TaskBoardPage['columns'],
  }
}
function deferred() {
  let resolve!: (value: unknown) => void
  let reject!: (reason: unknown) => void
  const promise = new Promise((yes, no) => {
    resolve = yes
    reject = no
  })
  return { promise, resolve, reject }
}
const conflict = () => new ApiError(409, 'cursor_stale', null, null, null)
const mounted: Array<{ wrapper: VueWrapper; client: QueryClient }> = []
function screen(initial: Partial<TaskBoardInput> = {}) {
  const options = ref<TaskBoardInput>({ listId: 'project', sort: 'manual', q: '', ...initial })
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  let result!: ReturnType<typeof useTaskBoardPages>
  const wrapper = mount(
    defineComponent({
      setup() {
        const container = ref<HTMLElement | null>(null)
        result = useTaskBoardPages(() => options.value, container)
        return () =>
          h(
            'div',
            { ref: container, 'data-testid': 'board' },
            STATES.map((state) =>
              h(
                'section',
                { 'data-state': state },
                (result.data.value?.columns[state].items ?? []).map((item) =>
                  h('button', { key: item.id, 'data-task': item.id }, item.title),
                ),
              ),
            ),
          )
      },
    }),
    { attachTo: document.body, global: { plugins: [[VueQueryPlugin, { queryClient: client }]] } },
  )
  mounted.push({ wrapper, client })
  return { ...result, options, wrapper, client }
}

beforeEach(() => {
  mocks.get.mockReset()
})
afterEach(() => {
  for (const { wrapper, client } of mounted.splice(0)) {
    wrapper.unmount()
    client.clear()
  }
  vi.restoreAllMocks()
  vi.unstubAllGlobals()
})

describe('the board aggregate', () => {
  it('does not revive unread messages from an old row when a later page follows a newer detail read', async () => {
    let now = 100
    vi.spyOn(Date, 'now').mockImplementation(() => now)
    const unread = { ...task('first'), discussion: { total: 2, unread: 2 } }
    mocks.get.mockResolvedValueOnce(
      board('same', { todo: { items: [unread], next_cursor: 'next' } }),
    )
    const view = screen()
    await flushPromises()
    expect(view.data.value?.readAtByTask.first).toBe(100)

    // The detail GET reflects a read acknowledgement at the same task revision.
    const detailRead = {
      task: { ...unread, discussion: { total: 2, unread: 0 } },
      readAt: 200,
    }
    const selected = () => {
      const loaded = view.data.value!.columns.todo.items[0]!
      const source =
        detailRead.readAt >= view.data.value!.readAtByTask[loaded.id]! ? detailRead.task : loaded
      return newestTask(detailRead.task, loaded, source)
    }
    expect(selected()?.discussion?.unread).toBe(0)

    now = 300
    mocks.get.mockResolvedValueOnce(page('same', [unread, task('second')]))
    await view.more('todo')
    expect(view.data.value?.readAtByTask).toEqual({ first: 100, second: 300 })
    expect(view.data.value?.columns.todo.items).toHaveLength(2)
    expect(selected()?.discussion?.unread).toBe(0)
  })

  it('keeps each GET time while publishing a complete refresh with restored pages', async () => {
    let now = 100
    vi.spyOn(Date, 'now').mockImplementation(() => now)
    mocks.get.mockResolvedValueOnce(
      board('old', { todo: { items: [task('first')], next_cursor: 'next' } }),
    )
    const view = screen()
    await flushPromises()
    mocks.get.mockResolvedValueOnce(page('old', [task('second')]))
    await view.more('todo')
    const old = view.data.value

    now = 300
    const continuation = deferred()
    mocks.get
      .mockResolvedValueOnce(
        board('new', {
          todo: { items: [task('first')], next_cursor: 'new-next' },
          waiting: { items: [task('waiting')] },
        }),
      )
      .mockReturnValueOnce(continuation.promise)
    const refreshing = view.refetch()
    await flushPromises()
    expect(view.data.value).toBe(old)
    now = 500
    continuation.resolve(page('new', [task('first'), task('second')]))
    await refreshing
    expect(view.data.value?.snapshot).toBe('new')
    expect(view.data.value?.readAtByTask).toEqual({ first: 300, waiting: 300, second: 500 })
  })

  it('keeps exact server totals separate from the loaded prefix through continuation', async () => {
    mocks.get.mockResolvedValueOnce(
      board('same', { todo: { items: [task('first')], next_cursor: 'next', total: 57 } }),
    )
    const view = screen({ q: 'needle', filters: { important: '1', completed_scope: 'all' } })
    await flushPromises()
    expect(view.data.value?.total).toBe(57)
    expect(view.data.value?.columns.todo.total).toBe(57)
    expect(view.data.value?.columns.todo.items).toHaveLength(1)
    mocks.get.mockResolvedValueOnce({ ...page('same', [task('second')]), total: 57 })
    await view.more('todo')
    expect(view.data.value?.columns.todo.items).toHaveLength(2)
    expect(view.data.value?.columns.todo.total).toBe(57)
    const request = new URL(mocks.get.mock.calls.at(-1)![0], 'https://example.test').searchParams
    expect(request.get('q')).toBe('needle')
    expect(request.get('important')).toBe('1')
    expect(request.get('completed_scope')).toBe('all')
    expect(request.get('parent_id')).toBe('board')
  })

  it('refreshes all 130 loaded tasks with new cursors and publishes only the complete snapshot', async () => {
    mocks.get.mockResolvedValueOnce(
      board('old', { todo: { items: tasks(0, 50), next_cursor: 'old-50' } }),
    )
    const view = screen()
    await flushPromises()
    mocks.get.mockResolvedValueOnce(page('old', tasks(50, 50), 'old-100'))
    await view.more('todo')
    mocks.get.mockResolvedValueOnce(page('old', tasks(100, 30)))
    await view.more('todo')
    expect(view.data.value?.columns.todo.items).toHaveLength(130)
    expect(view.data.value?.pageDepths.todo).toBe(3)
    const original = view.data.value
    const middle = deferred()
    const last = deferred()
    mocks.get
      .mockResolvedValueOnce(
        board('new', { todo: { items: tasks(1000, 50), next_cursor: 'new-50' } }),
      )
      .mockReturnValueOnce(middle.promise)
      .mockReturnValueOnce(last.promise)
    const refreshing = view.refetch()
    await flushPromises()
    expect(view.data.value).toBe(original)
    expect(view.isFetching.value).toBe(true)
    expect(mocks.get.mock.calls.at(-1)![0]).toContain('cursor=new-50')
    middle.resolve(page('new', tasks(1050, 50), 'new-100'))
    await flushPromises()
    expect(view.data.value).toBe(original)
    expect(mocks.get.mock.calls.at(-1)![0]).toContain('cursor=new-100')
    last.resolve(page('new', tasks(1100, 30)))
    await refreshing
    expect(view.isFetching.value).toBe(false)
    expect(view.data.value?.snapshot).toBe('new')
    expect(view.data.value?.columns.todo.items).toEqual(tasks(1000, 130))
    expect(view.data.value?.pageDepths.todo).toBe(3)
    expect(view.client.getQueryData(['tasks', 'items', 'board', 'project', 'manual', '', {}])).toBe(
      view.data.value,
    )
  })

  it('keeps every old page when a later refreshed page fails and retries from a new board', async () => {
    mocks.get.mockResolvedValueOnce(
      board('old', { todo: { items: [task('first')], next_cursor: 'old-next' } }),
    )
    const view = screen()
    await flushPromises()
    mocks.get.mockResolvedValueOnce(page('old', [task('second')]))
    await view.more('todo')
    const old = view.data.value
    const failure = new TypeError('offline')
    mocks.get
      .mockResolvedValueOnce(
        board('new', { todo: { items: [task('changed')], next_cursor: 'new-next' } }),
      )
      .mockRejectedValueOnce(failure)
    await view.refetch()
    expect(view.data.value).toBe(old)
    expect(view.error.value).toBe(failure)
    expect(view.isRefetchError.value).toBe(true)
    expect(view.isLoadingError.value).toBe(false)
    mocks.get
      .mockResolvedValueOnce(
        board('retry', { todo: { items: [task('changed')], next_cursor: 'retry-next' } }),
      )
      .mockResolvedValueOnce(page('retry', [task('second')]))
    await view.refetch()
    expect(view.error.value).toBeNull()
    expect(view.data.value?.snapshot).toBe('retry')
    expect(mocks.get.mock.calls.at(-1)![0]).toContain('cursor=retry-next')
  })

  it('throws away a stale staged refresh and retries the entire read exactly once', async () => {
    mocks.get.mockResolvedValueOnce(
      board('old', { todo: { items: [task('first')], next_cursor: 'old-next' } }),
    )
    const view = screen()
    await flushPromises()
    mocks.get.mockResolvedValueOnce(page('old', [task('second')]))
    await view.more('todo')
    mocks.get
      .mockResolvedValueOnce(
        board('stale', { todo: { items: [task('discard')], next_cursor: 'stale-next' } }),
      )
      .mockRejectedValueOnce(conflict())
      .mockResolvedValueOnce(
        board('fresh', { todo: { items: [task('keep')], next_cursor: 'fresh-next' } }),
      )
      .mockResolvedValueOnce(page('fresh', [task('second')]))
    await view.refetch()
    expect(view.data.value?.columns.todo.items.map((item) => item.id)).toEqual(['keep', 'second'])
    expect(mocks.get).toHaveBeenCalledTimes(6)
    const old = view.data.value
    const failure = conflict()
    mocks.get.mockRejectedValueOnce(conflict()).mockRejectedValueOnce(failure)
    await view.refetch()
    expect(mocks.get).toHaveBeenCalledTimes(8)
    expect(view.data.value).toBe(old)
    expect(view.error.value).toBe(failure)
  })

  it('merges concurrent column pages into the latest aggregate without duplicate tasks', async () => {
    mocks.get.mockResolvedValueOnce(
      board('same', {
        todo: { items: [task('todo-first')], next_cursor: 'todo-next' },
        waiting: { items: [task('waiting-first')], next_cursor: 'waiting-next' },
      }),
    )
    const view = screen()
    await flushPromises()
    const todo = deferred()
    const waiting = deferred()
    mocks.get.mockReturnValueOnce(todo.promise).mockReturnValueOnce(waiting.promise)
    const loadingTodo = view.more('todo')
    const loadingWaiting = view.more('waiting')
    expect(view.loadingMore.todo).toBe(true)
    expect(view.loadingMore.waiting).toBe(true)
    await view.more('todo')
    expect(mocks.get).toHaveBeenCalledTimes(3)
    waiting.resolve(page('same', [task('waiting-last')]))
    await loadingWaiting
    todo.resolve(page('same', [task('todo-first'), task('todo-last')]))
    await loadingTodo
    expect(view.data.value?.columns.todo.items.map((item) => item.id)).toEqual([
      'todo-first',
      'todo-last',
    ])
    expect(view.data.value?.columns.waiting.items.map((item) => item.id)).toEqual([
      'waiting-first',
      'waiting-last',
    ])
    expect(view.data.value?.pageDepths).toEqual({ todo: 2, in_progress: 1, waiting: 2, done: 1 })
    expect(view.loadingMore.todo).toBe(false)
    expect(view.loadingMore.waiting).toBe(false)
  })

  it('never appends a page from another snapshot, even if its HTTP request succeeded', async () => {
    mocks.get.mockResolvedValueOnce(
      board('old', { todo: { items: [task('first')], next_cursor: 'old-next' } }),
    )
    const view = screen()
    await flushPromises()
    mocks.get
      .mockResolvedValueOnce(page('different', [task('must-not-appear')]))
      .mockResolvedValueOnce(board('fresh', { todo: { items: [task('fresh')] } }))
    await view.more('todo')
    expect(view.data.value?.columns.todo.items.map((item) => item.id)).toEqual(['fresh'])
    expect(view.moreError.todo).toBeNull()
    expect(mocks.get).toHaveBeenCalledTimes(3)
  })

  it('rejects a board whose columns are not from its top-level snapshot', async () => {
    const invalid = board('top')
    invalid.columns.waiting.snapshot = 'other'
    mocks.get.mockResolvedValue(invalid)
    const view = screen()
    await flushPromises()
    expect(mocks.get).toHaveBeenCalledTimes(2)
    expect(view.data.value).toBeUndefined()
    expect(view.isLoadingError.value).toBe(true)
    expect(view.isPending.value).toBe(false)
  })

  it.each([
    { items: [task('first')], next_cursor: 'next' },
    { items: [], next_cursor: 'another' },
    { items: [task('first')], next_cursor: 'another' },
  ])('refreshes pages that make no pagination progress: %j', async (broken) => {
    mocks.get.mockResolvedValueOnce(
      board('same', { todo: { items: [task('first')], next_cursor: 'next' } }),
    )
    const view = screen()
    await flushPromises()
    mocks.get
      .mockResolvedValueOnce(page('same', broken.items, broken.next_cursor))
      .mockResolvedValueOnce(board('fresh', { todo: { items: [task('first')] } }))
    await view.more('todo')
    expect(mocks.get).toHaveBeenCalledTimes(3)
    expect(view.data.value?.snapshot).toBe('fresh')
    expect(view.data.value?.columns.todo.next_cursor).toBeNull()
  })

  it('keeps a failed next page retryable without replacing the board', async () => {
    mocks.get.mockResolvedValueOnce(
      board('old', { todo: { items: [task('first')], next_cursor: 'next' } }),
    )
    const view = screen()
    await flushPromises()
    const old = view.data.value
    const failure = new TypeError('offline')
    mocks.get.mockRejectedValueOnce(failure)
    await view.more('todo')
    expect(view.data.value).toBe(old)
    expect(view.moreError.todo).toBe(failure)
    expect(view.loadingMore.todo).toBe(false)
    mocks.get.mockResolvedValueOnce(page('old', [task('last')]))
    await view.more('todo')
    expect(view.moreError.todo).toBeNull()
    expect(view.data.value?.columns.todo.items).toHaveLength(2)
  })
})

describe('board request ownership and invalidation', () => {
  it.each([
    { listId: 'another' },
    { sort: 'priority' },
    { q: 'flowers' },
    { filters: { important: '1' } },
  ])('aborts old pagination immediately when criteria change: %j', async (change) => {
    mocks.get.mockResolvedValueOnce(
      board('old', { todo: { items: [task('first')], next_cursor: 'old-next' } }),
    )
    const view = screen()
    await flushPromises()
    const late = deferred()
    mocks.get.mockReturnValueOnce(late.promise)
    const loading = view.more('todo')
    const signal = mocks.get.mock.calls.at(-1)![1] as AbortSignal
    mocks.get.mockResolvedValueOnce(board('new', { todo: { items: [task('new')] } }))
    view.options.value = { ...view.options.value, ...change }
    expect(signal.aborted).toBe(true)
    expect(view.data.value).toBeUndefined()
    await flushPromises()
    late.resolve(page('old', [task('private-old-page')]))
    await loading
    expect(view.data.value?.columns.todo.items.map((item) => item.id)).toEqual(['new'])
    expect(view.loadingMore.todo).toBe(false)
    const old = view.client.getQueryData<TaskBoardPages>([
      'tasks',
      'items',
      'board',
      'project',
      'manual',
      '',
      {},
    ])
    expect(old?.columns.todo.items.map((item) => item.id)).toEqual(['first'])
  })

  it('aborts an old first read and ignores its late failure after changing projects', async () => {
    const late = deferred()
    mocks.get.mockReturnValueOnce(late.promise)
    const view = screen()
    await flushPromises()
    const signal = mocks.get.mock.calls[0]![1] as AbortSignal
    mocks.get.mockResolvedValueOnce(board('new', { todo: { items: [task('new')] } }))
    view.options.value.listId = 'another'
    expect(signal.aborted).toBe(true)
    await flushPromises()
    late.reject(new ApiError(403, null, null, null, null))
    await flushPromises()
    expect(view.data.value?.snapshot).toBe('new')
    expect(view.error.value).toBeNull()
  })

  it('coalesces invalidations and preserves the first dirty event during every pending pass', async () => {
    const first = deferred()
    const second = deferred()
    const third = deferred()
    mocks.get
      .mockReturnValueOnce(first.promise)
      .mockReturnValueOnce(second.promise)
      .mockReturnValueOnce(third.promise)
    const view = screen()
    await flushPromises()
    await view.client.invalidateQueries({ queryKey: ['tasks'] })
    await view.client.invalidateQueries({ queryKey: ['tasks'] })
    expect(mocks.get).toHaveBeenCalledTimes(1)
    first.resolve(board('discard-first'))
    await flushPromises()
    expect(mocks.get).toHaveBeenCalledTimes(2)
    expect(view.data.value).toBeUndefined()
    // The first invalidation of the follow-up pass must also cause a fresh pass.
    await view.client.invalidateQueries({ queryKey: ['tasks'] })
    second.resolve(board('discard-second'))
    await flushPromises()
    expect(mocks.get).toHaveBeenCalledTimes(3)
    expect(view.data.value).toBeUndefined()
    third.resolve(board('final'))
    await flushPromises()
    expect(view.data.value?.snapshot).toBe('final')
    expect(view.isFetching.value).toBe(false)
  })

  it.each(['pending-page', 'refresh-started'] as const)(
    'retains the requested third page when invalidation wins the race: %s',
    async (timing) => {
      mocks.get.mockResolvedValueOnce(
        board('old', { todo: { items: tasks(0, 50), next_cursor: 'old-50' } }),
      )
      const view = screen()
      await flushPromises()
      mocks.get.mockResolvedValueOnce(page('old', tasks(50, 50), 'old-100'))
      await view.more('todo')
      expect(view.data.value?.columns.todo.items).toHaveLength(100)
      const late = deferred()
      const fresh = deferred()
      if (timing === 'pending-page') mocks.get.mockReturnValueOnce(late.promise)
      mocks.get.mockReturnValueOnce(fresh.promise).mockImplementation((url: string) => {
        if (url.includes('/board?'))
          return Promise.resolve(
            board('fresh', { todo: { items: tasks(1000, 50), next_cursor: 'fresh-50' } }),
          )
        return Promise.resolve(
          url.includes('cursor=fresh-50')
            ? page('fresh', tasks(1050, 50), 'fresh-100')
            : page('fresh', tasks(1100, 30)),
        )
      })
      let loading: Promise<void>
      if (timing === 'pending-page') {
        loading = view.more('todo')
        const signal = mocks.get.mock.calls.at(-1)![1] as AbortSignal
        await view.client.invalidateQueries({ queryKey: ['tasks'] })
        await flushPromises()
        expect(signal.aborted).toBe(true)
      } else {
        await view.client.invalidateQueries({ queryKey: ['tasks'] })
        await flushPromises()
        loading = view.more('todo')
      }
      fresh.resolve(board('fresh', { todo: { items: tasks(1000, 50), next_cursor: 'fresh-50' } }))
      await flushPromises()
      late.resolve(page('old', tasks(100, 30)))
      await loading
      expect(view.data.value?.snapshot).toBe('fresh')
      expect(view.data.value?.columns.todo.items).toEqual(tasks(1000, 130))
      expect(view.data.value?.pageDepths.todo).toBe(3)
      expect(view.loadingMore.todo).toBe(false)
    },
  )

  it('refreshes domain invalidations and cancels pagination before starting the fresh read', async () => {
    mocks.get.mockResolvedValueOnce(
      board('old', { todo: { items: [task('first')], next_cursor: 'old-next' } }),
    )
    const view = screen()
    await flushPromises()
    const late = deferred()
    mocks.get.mockReturnValueOnce(late.promise)
    const loading = view.more('todo')
    const signal = mocks.get.mock.calls.at(-1)![1] as AbortSignal
    mocks.get.mockResolvedValueOnce(board('fresh'))
    await view.client.invalidateQueries({ queryKey: ['tasks'] })
    await flushPromises()
    expect(signal.aborted).toBe(true)
    expect(view.loadingMore.todo).toBe(false)
    expect(view.data.value?.snapshot).toBe('fresh')
    late.resolve(page('old', [task('discard')]))
    await loading
    expect(view.data.value?.snapshot).toBe('fresh')
  })

  it('binds normalized filters to the cache and every page without resetting on key order', async () => {
    mocks.get.mockResolvedValueOnce(
      board('same', { todo: { items: [task('first')], next_cursor: 'next' } }),
    )
    const view = screen({ filters: { overdue: '1', important: '1', completed_scope: 'all' } })
    await flushPromises()
    const first = new URL(mocks.get.mock.calls[0]![0], 'https://example.test').searchParams
    expect(first.get('important')).toBe('1')
    expect(first.get('overdue')).toBe('1')
    expect(first.get('completed_scope')).toBe('all')
    view.options.value.filters = { completed_scope: 'all', important: '1', overdue: '1' }
    await flushPromises()
    expect(mocks.get).toHaveBeenCalledTimes(1)
    mocks.get.mockResolvedValueOnce(page('same', [task('last')]))
    await view.more('todo')
    const next = new URL(mocks.get.mock.calls[1]![0], 'https://example.test').searchParams
    expect(next.get('important')).toBe('1')
    expect(next.get('overdue')).toBe('1')
    expect(next.get('completed_scope')).toBe('all')
    expect(next.get('parent_id')).toBe('board')
    expect(next.get('cursor')).toBe('next')
    expect(
      view.client.getQueryData([
        'tasks',
        'items',
        'board',
        'project',
        'manual',
        '',
        { completed_scope: 'all', important: '1', overdue: '1' },
      ]),
    ).toBe(view.data.value)
  })

  it.each([403, 404])('hides and evicts every project variant on HTTP %s', async (status) => {
    mocks.get.mockResolvedValueOnce(board('old', { todo: { items: [task('private')] } }))
    const view = screen()
    await flushPromises()
    view.client.setQueryData(['tasks', 'items', 'board', 'project', 'priority', 'secret', {}], {
      ...view.data.value,
    })
    view.client.setQueryData(['tasks', 'items', 'board', 'other', 'manual', '', {}], {
      ...view.data.value,
    })
    const failure = new ApiError(status, null, null, null, null)
    mocks.get.mockRejectedValueOnce(failure)
    await view.refetch()
    expect(view.data.value).toBeUndefined()
    expect(view.error.value).toBe(failure)
    expect(view.isLoadingError.value).toBe(true)
    expect(view.isFetching.value).toBe(false)
    expect(
      view.client.getQueryCache().findAll({ queryKey: ['tasks', 'items', 'board', 'project'] }),
    ).toHaveLength(0)
    expect(
      view.client.getQueryCache().findAll({ queryKey: ['tasks', 'items', 'board', 'other'] }),
    ).toHaveLength(1)
  })

  it('evicts on a pagination access refusal and can explicitly retry after access returns', async () => {
    mocks.get.mockResolvedValueOnce(
      board('old', { todo: { items: [task('private')], next_cursor: 'next' } }),
    )
    const view = screen()
    await flushPromises()
    mocks.get.mockRejectedValueOnce(new ApiError(403, null, null, null, null))
    await view.more('todo')
    expect(view.data.value).toBeUndefined()
    expect(view.loadingMore.todo).toBe(false)
    mocks.get.mockResolvedValueOnce(board('restored'))
    await view.refetch()
    expect(view.data.value?.snapshot).toBe('restored')
    expect(view.error.value).toBeNull()
    mocks.get.mockResolvedValueOnce(board('changed-after-restoration'))
    await view.client.invalidateQueries({ queryKey: ['tasks'] })
    await flushPromises()
    expect(view.data.value?.snapshot).toBe('changed-after-restoration')
  })

  it('evicts revoked task projections and subqueries without clearing independent personal data', async () => {
    const privateTask = { ...task('private'), list_id: 'project', discussion_channel_id: 'thread' }
    const ownTask = { ...task('own'), list_id: 'personal' }
    const child = { ...task('child'), list_id: 'project', parent_id: 'private' }
    mocks.get.mockResolvedValueOnce(board('old', { todo: { items: [privateTask] } }))
    const view = screen()
    await flushPromises()
    const remove = [
      { key: ['tasks', 'item', 'private'], data: privateTask },
      { key: ['tasks', 'children', 'private'], data: { pages: [page('old', [child])] } },
      { key: ['tasks', 'assistant', 'child'], data: { delegation: null, results: ['secret'] } },
      { key: ['tasks', 'assistant-capabilities', 'private'], data: { resources: ['secret'] } },
      { key: ['tasks', 'discussion', 'child'], data: { channel_id: 'child-thread' } },
      { key: ['tasks', 'history', 'child'], data: { pages: [{ events: ['secret'] }] } },
      { key: ['tasks', 'list', 'project'], data: page('old', [privateTask]) },
      { key: ['tasks', 'items', 'open', 'all', 'project', 'manual', ''], data: { pages: [] } },
      { key: ['tasks', 'items', 'today', '', 'card'], data: page('old', [ownTask, privateTask]) },
      {
        key: ['tasks', 'items', 'today', 'all', '', 'manual', ''],
        data: { pages: [page('old', [ownTask]), page('old', [privateTask])] },
      },
      { key: ['community', 'messages', 'task', 'thread'], data: { pages: ['secret'] } },
      { key: ['community', 'messages', 'task', 'child-thread'], data: { pages: ['secret'] } },
      { key: ['community', 'messages', 'thread'], data: { messages: ['secret'] } },
      { key: ['community', 'grants', 'project'], data: { grants: ['secret'] } },
    ]
    const keep = [
      { key: ['tasks', 'item', 'own'], data: ownTask },
      { key: ['tasks', 'list', 'personal'], data: page('own', [ownTask]) },
      {
        key: ['tasks', 'items', 'open', 'personal', 'personal', 'manual', ''],
        data: { pages: [page('own', [ownTask])] },
      },
      { key: ['tasks', 'assistant', 'own'], data: { delegation: null } },
      { key: ['community', 'messages', 'personal-thread'], data: { messages: ['own'] } },
    ]
    for (const item of [...remove, ...keep]) view.client.setQueryData(item.key, item.data)
    view.client.setQueryData(['tasks', 'lists'], { lists: [{ id: 'project' }, { id: 'personal' }] })
    view.client.setQueryData(['community', 'channels'], {
      channels: [
        {
          id: 'thread',
          audience_resource_id: 'project',
          subject_kind: 'task',
          subject_id: 'private',
        },
        { id: 'personal-thread', kind: 'personal' },
      ],
    })
    mocks.get.mockRejectedValueOnce(new ApiError(403, null, null, null, null))
    await view.refetch()
    for (const item of remove) expect(view.client.getQueryData(item.key)).toBeUndefined()
    for (const item of keep) expect(view.client.getQueryData(item.key)).toBe(item.data)
    expect(view.client.getQueryData(['tasks', 'lists'])).toEqual({ lists: [{ id: 'personal' }] })
    expect(view.client.getQueryData(['community', 'channels'])).toEqual({
      channels: [{ id: 'personal-thread', kind: 'personal' }],
    })
  })

  it('clears a mounted detail observer and cancels its read without restoring old successful data', async () => {
    const privateTask = { ...task('private'), list_id: 'project' }
    mocks.get.mockResolvedValueOnce(board('old', { todo: { items: [privateTask] } }))
    const view = screen()
    await flushPromises()
    const key = ['tasks', 'item', 'private']
    view.client.setQueryData(key, privateTask)
    const late = deferred()
    let signal!: AbortSignal
    const observer = new QueryObserver(view.client, {
      queryKey: key,
      staleTime: Infinity,
      retry: false,
      queryFn: (context) => {
        signal = context.signal
        return late.promise
      },
    })
    const unsubscribe = observer.subscribe(() => undefined)
    const reading = observer.refetch()
    const failure = new ApiError(404, null, null, null, null)
    mocks.get.mockRejectedValueOnce(failure)
    await view.refetch()
    expect(signal.aborted).toBe(true)
    expect(observer.getCurrentResult().data).toBeUndefined()
    expect(observer.getCurrentResult().error).toBe(failure)
    late.resolve(privateTask)
    await reading
    expect(observer.getCurrentResult().data).toBeUndefined()
    expect(view.client.getQueryData(key)).toBeUndefined()
    unsubscribe()
    observer.destroy()
  })

  it('cancels pending reads on unmount and cannot repopulate the cache with a late answer', async () => {
    const late = deferred()
    mocks.get.mockReturnValueOnce(late.promise)
    const view = screen()
    await flushPromises()
    const signal = mocks.get.mock.calls[0]![1] as AbortSignal
    view.wrapper.unmount()
    expect(signal.aborted).toBe(true)
    late.resolve(board('late'))
    await flushPromises()
    expect(view.client.getQueryData(['tasks', 'items', 'board', 'project', 'manual', '', {}])).toBe(
      undefined,
    )
  })
})

describe('the visible card during board publication', () => {
  function viewport() {
    let position = 0
    vi.stubGlobal('innerHeight', 120)
    vi.stubGlobal('innerWidth', 400)
    vi.spyOn(HTMLElement.prototype, 'getBoundingClientRect').mockImplementation(function (
      this: HTMLElement,
    ) {
      if (!this.dataset.task) return new DOMRect(0, 0, 300, 120)
      const siblings = [...this.parentElement!.querySelectorAll('[data-task]')]
      return new DOMRect(0, siblings.indexOf(this) * 40 - position, 280, 40)
    })
    const scroll = vi
      .spyOn(window, 'scrollBy')
      .mockImplementation((options: number | ScrollToOptions, y?: number) => {
        position += typeof options === 'number' ? (y ?? 0) : (options.top ?? 0)
      })
    return { scroll, move: (value: number) => (position = value) }
  }

  it('captures the currently visible card at publication, including scrolling during the fetch', async () => {
    const { scroll, move } = viewport()
    mocks.get.mockResolvedValueOnce(
      board('old', { todo: { items: ['a', 'b', 'c', 'd'].map(task) } }),
    )
    const view = screen()
    await flushPromises()
    const pending = deferred()
    mocks.get.mockReturnValueOnce(pending.promise)
    const refreshing = view.refetch()
    await flushPromises()
    move(50)
    expect(view.wrapper.get('[data-task="b"]').element.getBoundingClientRect().top).toBe(-10)
    pending.resolve(board('new', { todo: { items: ['b', 'c', 'd'].map(task) } }))
    await refreshing
    await nextTick()
    expect(scroll).toHaveBeenCalledExactlyOnceWith({ top: -40, behavior: 'instant' })
    expect(view.wrapper.get('[data-task="b"]').element.getBoundingClientRect().top).toBe(-10)
  })

  it('keeps the nearest surviving task at its old offset when the visible task was deleted', async () => {
    const { move } = viewport()
    mocks.get.mockResolvedValueOnce(
      board('old', { todo: { items: ['a', 'b', 'c', 'd'].map(task) } }),
    )
    const view = screen()
    await flushPromises()
    move(50)
    expect(view.wrapper.get('[data-task="c"]').element.getBoundingClientRect().top).toBe(30)
    mocks.get.mockResolvedValueOnce(board('new', { todo: { items: ['a', 'c', 'd'].map(task) } }))
    await view.refetch()
    await nextTick()
    expect(view.wrapper.get('[data-task="c"]').element.getBoundingClientRect().top).toBe(30)
  })

  it('does not restore an old project anchor after changing criteria', async () => {
    const { scroll, move } = viewport()
    mocks.get.mockResolvedValueOnce(board('old', { todo: { items: ['a', 'b', 'c'].map(task) } }))
    const view = screen()
    await flushPromises()
    move(50)
    mocks.get.mockResolvedValueOnce(board('new', { todo: { items: ['b', 'c'].map(task) } }))
    view.options.value.listId = 'another'
    await flushPromises()
    expect(scroll).not.toHaveBeenCalled()
  })
})
