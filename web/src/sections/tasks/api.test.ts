import { QueryClient } from '@tanstack/vue-query'
import { describe, expect, it } from 'vitest'
import {
  boardQuery,
  changesOf,
  clock,
  remaining,
  fieldsOf,
  newestTask,
  rememberTasks,
  Task,
  TASKS,
  taskQuery,
  previousSibling,
  type Timer,
} from './api'
import { readTaskFilters } from './taskFilters'

describe('changesOf', () => {
  const base = fieldsOf(
    Task.parse({
      id: 't',
      title: 'Забрать посылку',
      status: 'open',
      revision: 1,
      planned_on: '2026-09-25',
    }),
  )

  it("sends a field's pictures as its whole ordered list of ids once they change", () => {
    const picture = (id: string) => ({ id, width: 40, height: 30 })
    const [a, b, c] = [picture('a'), picture('b'), picture('c')]
    const pictured = fieldsOf(
      Task.parse({
        id: 't',
        title: 'Скриншот',
        status: 'open',
        revision: 1,
        title_images: [a],
        description_images: [b, c],
      }),
    )
    expect(changesOf(pictured, { ...pictured, description_images: [b, c] })).toEqual({})
    expect(changesOf(pictured, { ...pictured, description_images: [c, b] })).toEqual({
      description_images: ['c', 'b'],
    })
    expect(
      changesOf(pictured, { ...pictured, title_images: [], description_images: [a, b, c] }),
    ).toEqual({
      title_images: [],
      description_images: ['a', 'b', 'c'],
    })
    expect(base.title_images).toEqual([])
    // A draft kept from before pictures existed lacks both lists and still saves its text.
    const kept = { ...base, title: 'Новое' } as Partial<typeof base>
    delete kept.title_images
    delete kept.description_images
    expect(changesOf(kept as typeof base, kept as typeof base)).toEqual({})
    expect(changesOf(kept as typeof base, { ...(kept as typeof base), description: 'Да' })).toEqual(
      {
        description: 'Да',
      },
    )
  })

  it('sends only the fields the user changed, clearing with null', () => {
    expect(changesOf(base, { ...base, due_on: '2026-09-27' })).toEqual({ due_on: '2026-09-27' })
    expect(changesOf(base, { ...base, planned_on: '' })).toEqual({ planned_on: null })
    expect(changesOf(base, { ...base, title: '  Забрать посылку на почте ' })).toEqual({
      title: 'Забрать посылку на почте',
    })
    expect(changesOf(base, base)).toEqual({})
  })
})

describe('timers', () => {
  const timer: Timer = {
    id: 't',
    label: 'Паста',
    duration_seconds: 600,
    due_at: '',
    revision: 1,
    remaining_seconds: 90,
  }

  it('counts down from the server value by the time elapsed since reading it', () => {
    expect(remaining(timer, 1000, 1000)).toBe(90)
    expect(remaining(timer, 1000, 31_500)).toBe(60)
    expect(remaining(timer, 1000, 200_000)).toBe(0)
  })

  it('formats minutes and hours', () => {
    expect(clock(0)).toBe('0:00')
    expect(clock(605)).toBe('10:05')
    expect(clock(3725)).toBe('1:02:05')
  })
})

describe('task filters and compatibility', () => {
  it('carries every AND condition and the explicit completed scope on list continuation', () => {
    const filters = readTaskFilters({
      q: 'query',
      important: '1',
      overdue: '1',
      assignee: 'unassigned',
      completed_scope: 'all',
    })
    expect(
      Object.fromEntries(
        taskQuery({ view: 'all', scope: 'all', listId: 'project', filters, cursor: 'next' }),
      ),
    ).toEqual({
      view: 'all',
      scope: 'all',
      sort: 'priority',
      list_id: 'project',
      q: 'query',
      important: '1',
      overdue: '1',
      assignee: 'unassigned',
      completed_scope: 'all',
      cursor: 'next',
    })
  })

  it('preserves server-side scope and filters when loading another page', () => {
    const params = taskQuery({
      view: 'upcoming',
      scope: 'assigned',
      listId: 'shared',
      q: 'Список',
      cursor: 'next',
      sort: 'due',
    })
    expect(Object.fromEntries(params)).toEqual({
      view: 'upcoming',
      scope: 'assigned',
      list_id: 'shared',
      q: 'Список',
      cursor: 'next',
      sort: 'due',
      completed_scope: 'recent',
    })
  })
  it('keeps legacy open tasks editable without overwriting absent tracker fields', () => {
    const fields = fieldsOf(
      Task.parse({ id: 'old', title: 'Старая задача', status: 'open', revision: 2 }),
    )
    expect(fields.state).toBe('todo')
    expect(changesOf(fields, { ...fields, title: 'Новое название' })).toEqual({
      title: 'Новое название',
    })
    expect(changesOf(fields, { ...fields, priority: '3', state: 'in_progress' })).toEqual({
      priority: 3,
      state: 'in_progress',
    })
  })
})

describe('board columns', () => {
  it('keeps the closed parent context returned with an independently visible child', () => {
    const parent = { id: 'parent', title: 'Old project', state: 'done', deleted_at: null }
    expect(
      Task.parse({
        id: 'child',
        title: 'Open step',
        status: 'open',
        revision: 1,
        parent_id: 'parent',
        parent,
      }).parent,
    ).toEqual(parent)
  })

  it('reads roots and open children of closed parents, whatever the date views say', () => {
    expect(
      Object.fromEntries(boardQuery({ listId: 'project', state: 'waiting', sort: 'manual' })),
    ).toEqual({
      view: 'open',
      scope: 'all',
      list_id: 'project',
      sort: 'manual',
      state: 'waiting',
      parent_id: 'board',
      completed_scope: 'recent',
    })
  })

  it('shows a week of done tasks, newest first, and searches subtasks too', () => {
    expect(
      Object.fromEntries(
        boardQuery({ listId: 'project', state: 'done', sort: 'due', q: 'плитка', cursor: 'c' }),
      ),
    ).toEqual({
      view: 'done',
      scope: 'all',
      list_id: 'project',
      sort: 'completed',
      q: 'плитка',
      cursor: 'c',
      parent_id: 'board',
      completed_scope: 'recent',
    })
  })
})

describe('manual task order', () => {
  it('moves only relative to an earlier task in the same list and hierarchy level', () => {
    const make = (id: string, parent_id: string | null = null, list_id = 'list') =>
      Task.parse({ id, list_id, parent_id, title: id, status: 'open', revision: 1 })
    const root = make('parent'),
      first = make('child1', 'parent'),
      second = make('child2', 'parent'),
      other = make('other', null, 'other-list'),
      last = make('last')
    const items = [root, first, other, second, last]
    expect(previousSibling(items, first)).toBeNull()
    expect(previousSibling(items, second)?.id).toBe('child1')
    expect(previousSibling(items, last)?.id).toBe('parent')
  })
})

describe('newestTask', () => {
  const copy = (revision: number) =>
    Task.parse({ id: 't', title: 'Забрать посылку', status: 'open', revision })

  it('takes the later revision of two copies, whichever comes first', () => {
    expect(newestTask(copy(1), copy(3))?.revision).toBe(3)
    expect(newestTask(copy(3), copy(1))?.revision).toBe(3)
  })
  it('keeps the first of equal copies and makes do with one', () => {
    const first = copy(2)
    expect(newestTask(first, copy(2))).toBe(first)
    expect(newestTask(undefined, first)).toBe(first)
    expect(newestTask(first, null)).toBe(first)
    expect(newestTask(null, undefined)).toBeNull()
  })
  it('takes discussion from the explicitly freshest live read independently of task revision', () => {
    const detail = { ...copy(3), discussion: { total: 5, unread: 2 } }
    const read = { ...copy(3), discussion: { total: 5, unread: 0 } }
    expect(newestTask(detail, read, read)).toEqual({ ...detail, discussion: read.discussion })
    const receipt = copy(4)
    expect(newestTask(detail, receipt, read)).toEqual({ ...receipt, discussion: read.discussion })
    expect(newestTask(receipt, detail)).toEqual({ ...receipt, discussion: detail.discussion })
    expect(newestTask(detail, read, { ...read, id: 'unrelated' })).toBe(detail)
  })
})

describe('rememberTasks', () => {
  const copy = (id: string, revision: number, status: 'open' | 'done' = 'open') =>
    Task.parse({ id, title: `Задача ${id}`, status, revision, overdue: false })
  const page = (...items: Task[]) => ({
    items,
    total: items.length,
    next_cursor: null,
    today: '2026-10-06',
    timezone: 'UTC',
  })

  function cached() {
    const queries = new QueryClient()
    const list = { pages: [page(copy('a', 1)), page(copy('b', 4))], pageParams: [null, 'next'] }
    const other = page(copy('b', 4))
    queries.setQueryData(['tasks', 'items', 'today'], list)
    queries.setQueryData(['tasks', 'items', 'today', '', 'card'], page(copy('a', 1)))
    queries.setQueryData(['tasks', 'item', 'a'], copy('a', 1))
    queries.setQueryData(['tasks', 'item', 'b'], copy('b', 4))
    queries.setQueryData(['tasks', 'list', 'shared'], other)
    queries.setQueryData(['tasks', 'lists'], { lists: [{ id: 'a', revision: 0 }] })
    return { queries, list, other }
  }

  it('puts a newer copy into every read that holds an older one', () => {
    const { queries } = cached()
    rememberTasks(queries, [copy('a', 3, 'done')])
    const list = queries.getQueryData<{ pages: { items: Task[] }[] }>(['tasks', 'items', 'today'])
    expect(list?.pages[0]?.items[0]).toMatchObject({ id: 'a', revision: 3, status: 'done' })
    expect(
      queries.getQueryData<{ items: Task[] }>(['tasks', 'items', 'today', '', 'card'])?.items[0],
    ).toMatchObject({ revision: 3, status: 'done' })
    expect(queries.getQueryData<Task>(['tasks', 'item', 'a'])).toMatchObject({ revision: 3 })
  })
  it('keeps what the read knew that the copy does not say', () => {
    const { queries } = cached()
    // A receipt carries the stored task; whether it is overdue is for a read to say.
    const receipt = Task.parse({ id: 'a', title: 'Задача a', status: 'open', revision: 2 })
    rememberTasks(queries, [receipt])
    expect(queries.getQueryData<Task>(['tasks', 'item', 'a'])).toMatchObject({
      revision: 2,
      overdue: false,
    })
  })
  it('refreshes same-revision counters only from live reads, never a replayed receipt', () => {
    const queries = new QueryClient()
    const key = ['tasks', 'item', 'a']
    const read = { ...copy('a', 4), discussion: { total: 8, unread: 2 } }
    queries.setQueryData(key, read)
    const markedRead = { ...read, discussion: { total: 8, unread: 0 } }
    rememberTasks(queries, [markedRead], { source: 'read' })
    expect(queries.getQueryData<Task>(key)?.discussion).toEqual(markedRead.discussion)
    rememberTasks(queries, [read])
    expect(queries.getQueryData<Task>(key)?.discussion).toEqual(markedRead.discussion)
    rememberTasks(queries, [{ ...read, revision: 5 }])
    expect(queries.getQueryData<Task>(key)).toMatchObject({
      revision: 5,
      discussion: { total: 8, unread: 0 },
    })
    rememberTasks(queries, [read], { source: 'read' })
    expect(queries.getQueryData<Task>(key)?.discussion).toEqual(markedRead.discussion)
  })
  it('leaves reads without an older copy as they were and keeps the others due for a refetch', () => {
    const { queries, list, other } = cached()
    rememberTasks(queries, [copy('b', 2), copy('a', 3)])
    const cache = queries.getQueryCache()
    // An older copy of b changes nothing; the page with b stays the same object.
    expect(queries.getQueryData<typeof list>(['tasks', 'items', 'today'])?.pages[1]).toBe(
      list.pages[1],
    )
    expect(queries.getQueryData(['tasks', 'list', 'shared'])).toBe(other)
    expect(cache.find({ queryKey: ['tasks', 'list', 'shared'] })?.state.isInvalidated).toBe(false)
    expect(cache.find({ queryKey: ['tasks', 'lists'] })?.state.isInvalidated).toBe(false)
    // The reads that got the newer copy still list what they listed: their refetch decides.
    expect(cache.find({ queryKey: ['tasks', 'item', 'a'] })?.state.isInvalidated).toBe(true)
    expect(cache.find({ queryKey: ['tasks', 'items', 'today'] })?.state.isInvalidated).toBe(true)
  })
  it('is what the tasks ledger does with a receipt, whatever the receipt holds', () => {
    const { queries } = cached()
    TASKS.remember!(queries, { operation_id: 'o', kind: 'task.reopen', tasks: [copy('a', 5)] })
    TASKS.remember!(queries, { operation_id: 'o', kind: 'list.create' })
    TASKS.remember!(queries, null)
    expect(queries.getQueryData<Task>(['tasks', 'item', 'a'])?.revision).toBe(5)
  })
})
