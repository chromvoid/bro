/** Tasks and timers as the server sends them; dates are calendar days in the account's zone. */
import type { QueryClient } from '@tanstack/vue-query'
import type { Ledger } from '@/core/operations'
import { z } from '@/core/zod'
import {
  applyTaskFilterParams,
  EMPTY_TASK_FILTERS,
  readTaskFilters,
  type TaskFilterState,
} from './taskFilters'

export const STATES = ['todo', 'in_progress', 'waiting', 'done'] as const
export type TaskState = (typeof STATES)[number]
export const ChecklistItem = z.object({ id: z.string(), text: z.string(), done: z.boolean() })
/** A picture under the title or the description: the server's photo id and its stored size. */
export const TaskImage = z.object({ id: z.string(), width: z.number(), height: z.number() })
export type TaskImage = z.infer<typeof TaskImage>
export const IMAGE_FIELDS = ['title_images', 'description_images'] as const
export type ImageField = (typeof IMAGE_FIELDS)[number]
export const IMAGE_LIMITS: Record<ImageField, number> = { title_images: 4, description_images: 20 }

export const Task = z.looseObject({
  id: z.string(),
  list_id: z.string().nullish(),
  title: z.string(),
  description: z.string().nullish(),
  status: z.enum(['open', 'done']),
  state: z.enum(STATES).optional(),
  waiting_reason: z.string().nullish(),
  priority: z.number().int().min(1).max(3).optional(),
  position: z.number().optional(),
  parent_id: z.string().nullish(),
  parent: z
    .object({
      id: z.string(),
      title: z.string(),
      state: z.enum(STATES),
      deleted_at: z.string().nullish(),
    })
    .nullish(),
  assigned_to: z.string().nullish(),
  checklist: z.array(ChecklistItem).optional(),
  title_images: z.array(TaskImage).optional(),
  description_images: z.array(TaskImage).optional(),
  discussion_channel_id: z.string().nullish(),
  /** Viewer-specific live projection; durable operation receipts do not carry it. */
  discussion: z
    .object({ total: z.number().int().nonnegative(), unread: z.number().int().nonnegative() })
    .optional(),
  planned_on: z.string().nullish(),
  due_on: z.string().nullish(),
  completed_at: z.string().nullish(),
  deleted_at: z.string().nullish(),
  updated_at: z.string().nullish(),
  revision: z.number(),
  overdue: z.boolean().nullish(),
  /** Subtasks outside the trash; only a list page sends them. */
  subtasks: z.object({ total: z.number(), done: z.number() }).optional(),
})
export type Task = z.infer<typeof Task>

export const TaskPage = z.object({
  snapshot: z.string().optional(),
  total: z.number().int().nonnegative(),
  items: z.array(Task),
  next_cursor: z.string().nullable(),
  today: z.string(),
  timezone: z.string(),
})
export type TaskPage = z.infer<typeof TaskPage>

/** A project's board in one read: the first page of each column (GET /v1/tasks/board). */
export const TaskBoardPage = z.object({
  snapshot: z.string(),
  total: z.number().int().nonnegative(),
  columns: z.object({
    todo: z.object({
      snapshot: z.string(),
      total: z.number().int().nonnegative(),
      items: z.array(Task),
      next_cursor: z.string().nullable(),
    }),
    in_progress: z.object({
      snapshot: z.string(),
      total: z.number().int().nonnegative(),
      items: z.array(Task),
      next_cursor: z.string().nullable(),
    }),
    waiting: z.object({
      snapshot: z.string(),
      total: z.number().int().nonnegative(),
      items: z.array(Task),
      next_cursor: z.string().nullable(),
    }),
    done: z.object({
      snapshot: z.string(),
      total: z.number().int().nonnegative(),
      items: z.array(Task),
      next_cursor: z.string().nullable(),
    }),
  }),
  today: z.string(),
  timezone: z.string(),
})
export type TaskBoardPage = z.infer<typeof TaskBoardPage>

export const TaskReceipt = z.looseObject({
  operation_id: z.string(),
  kind: z.string(),
  tasks: z.array(Task).nullish(),
  undo: z
    .object({
      kind: z.literal('task.undo'),
      payload: z.object({ task_id: z.string(), operation_id: z.string() }),
      expected_revisions: z.record(z.string(), z.number()),
    })
    .optional(),
})

/**
 * Date views of a list: «Сегодня · Скоро · Все», then «Выполненные» and «Корзина». Inbox, undated
 * and in-progress views gave way to the places (Личные) and the project board.
 */
export const VIEWS = ['today', 'upcoming', 'open', 'done', 'trash', 'all'] as const
export type View = (typeof VIEWS)[number]

export const TASKS: Ledger = {
  endpoint: '/v1/tasks/operations',
  lookupPrefix: '/v1/tasks/operations/by-key/',
  domain: 'tasks',
  remember: (queries, receipt) => {
    const parsed = TaskReceipt.safeParse(receipt)
    if (parsed.success) rememberTasks(queries, parsed.data.tasks ?? [])
  },
}

export const Timer = z.looseObject({
  id: z.string(),
  label: z.string().nullish(),
  duration_seconds: z.number(),
  due_at: z.string(),
  revision: z.number(),
  remaining_seconds: z.number(),
})
export type Timer = z.infer<typeof Timer>
export const Timers = z.object({ timers: z.array(Timer) })
export const Reminder = z.looseObject({
  id: z.string(),
  text: z.string(),
  due_at: z.string(),
  revision: z.number(),
  /** The task it belongs to, when set from one; it stays silent while that task is closed. */
  task_id: z.string().nullish(),
})
export type Reminder = z.infer<typeof Reminder>
export const Reminders = z.object({ reminders: z.array(Reminder) })
export const TIMERS = {
  endpoint: '/v1/timers/operations',
  lookupPrefix: '/v1/timers/operations/by-key/',
  domain: 'timers',
}

/** The editable fields of a task; only the ones the user changed are sent. */
export interface TaskFields {
  title: string
  description: string
  planned_on: string
  due_on: string
  state: TaskState
  waiting_reason: string
  priority: string
  assigned_to: string
  title_images: TaskImage[]
  description_images: TaskImage[]
}

export function fieldsOf(task: Task): TaskFields {
  return {
    title: task.title,
    description: task.description ?? '',
    planned_on: task.planned_on ?? '',
    due_on: task.due_on ?? '',
    state: stateOf(task),
    waiting_reason: task.waiting_reason ?? '',
    priority: String(task.priority ?? 2),
    assigned_to: task.assigned_to ?? '',
    title_images: task.title_images ?? [],
    description_images: task.description_images ?? [],
  }
}

/**
 * The changes between what the user started from and what they typed, as the server expects
 * them (empty date or description = null). A field the user did not touch is never sent, so
 * applying a draft to a newer version keeps someone else's edits to other fields.
 */
export function changesOf(
  base: TaskFields,
  draft: TaskFields,
): Record<string, string | number | null | string[]> {
  const changes: Record<string, string | number | null | string[]> = {}
  for (const key of [
    'title',
    'description',
    'planned_on',
    'due_on',
    'state',
    'waiting_reason',
    'priority',
    'assigned_to',
  ] as const) {
    if (draft[key] === base[key]) continue
    const value = key === 'title' ? draft[key].trim() : draft[key].trim() || null
    changes[key] = key === 'priority' ? Number(value) : value
  }
  // Pictures go as the whole ordered list of ids; the server resolves their sizes. A draft saved
  // before tasks had pictures has neither list, and saves its other fields as before.
  for (const key of IMAGE_FIELDS) {
    const ids = (draft[key] ?? []).map((image) => image.id)
    if (ids.join() !== (base[key] ?? []).map((image) => image.id).join()) changes[key] = ids
  }
  return changes
}

export function stateOf(task: Task): TaskState {
  return task.state ?? (task.status === 'done' ? 'done' : 'todo')
}

/** URL parameters remain server-side filters, including every page of a shared list. */
export function taskQuery(options: {
  view: View
  scope: string
  listId?: string
  sort?: string
  q?: string
  filters?: TaskFilterState
  cursor?: string | null
}): URLSearchParams {
  const params = new URLSearchParams({
    view: options.view,
    scope: options.scope,
    sort: options.sort ?? 'priority',
  })
  if (options.listId) params.set('list_id', options.listId)
  applyTaskFilterParams(params, options.filters ?? { ...EMPTY_TASK_FILTERS, q: options.q ?? '' })
  if (options.cursor) params.set('cursor', options.cursor)
  return params
}

/**
 * The next page of one column of a project's board (its first comes with the board). The board
 * ignores the date views: a column holds every open task in its state, and «Готово» the tasks done
 * in the last seven days, newest first. Subtasks stay with their parent unless the board is
 * searched. Open children of completed or deleted parents stay visible independently.
 */
export function boardQuery(options: {
  listId: string
  state: TaskState
  sort: string
  q?: string
  filters?: Record<string, string>
  cursor?: string | null
}): URLSearchParams {
  const done = options.state === 'done'
  const params = new URLSearchParams({
    view: done ? 'done' : 'open',
    scope: 'all',
    list_id: options.listId,
    sort: done ? 'completed' : options.sort,
  })
  if (!done) params.set('state', options.state)
  params.set('parent_id', 'board')
  applyTaskFilterParams(params, readTaskFilters({ ...options.filters, q: options.q }))
  if (options.cursor) params.set('cursor', options.cursor)
  return params
}

/** Seconds left of a running timer: the server's count minus the time since it was read. */
export function remaining(timer: Timer, readAt: number, now: number): number {
  return Math.max(0, Math.ceil(timer.remaining_seconds - (now - readAt) / 1000))
}

export function clock(seconds: number): string {
  const hours = Math.floor(seconds / 3600)
  const minutes = Math.floor((seconds % 3600) / 60)
  const rest = String(seconds % 60).padStart(2, '0')
  return hours ? `${hours}:${String(minutes).padStart(2, '0')}:${rest}` : `${minutes}:${rest}`
}

/**
 * The later of two copies of one task. An action is sent with the copy's revision and refused as a
 * conflict when that is not the latest, so a screen holding two copies acts on the newer one.
 */
export function newestTask(
  first: Task | null | undefined,
  second: Task | null | undefined,
  discussionSource?: Task | null,
): Task | null {
  const newest =
    !first || !second
      ? (first ?? second ?? null)
      : second.revision > first.revision
        ? second
        : first
  if (!newest) return null
  // Task revisions order persistent fields, never the reader's unread counter. The screen
  // chooses this source by live query publication time, not a previously picked card.
  const source =
    discussionSource?.id === newest.id
      ? discussionSource
      : newest.discussion
        ? newest
        : [first, second].find((task) => task?.id === newest.id && task.discussion)
  return source?.discussion && source.discussion !== newest.discussion
    ? { ...newest, discussion: source.discussion }
    : newest
}

/**
 * Puts the copies of tasks a receipt or a read returned into the cached reads that hold an older
 * copy, so an action taken before those reads are refetched is sent with the newer revision.
 * Which tasks a view lists is still the refetch's to say: the reads stay due for it.
 */
export function rememberTasks(
  queries: QueryClient,
  tasks: readonly Task[],
  options: { source?: 'receipt' | 'read' } = {},
) {
  const latest = new Map(tasks.map((task) => [task.id, task]))
  if (!latest.size) return
  const fresh = (task: Task): Task => {
    const copy = latest.get(task.id)
    if (!copy || copy.revision < task.revision) return task
    const discussion =
      options.source === 'read' ? (copy.discussion ?? task.discussion) : task.discussion
    if (copy.revision === task.revision)
      return discussion !== task.discussion ? { ...task, discussion } : task
    return { ...task, ...copy, discussion }
  }
  // A task (one read), a page of them, or the pages of an endless list.
  const update = (data: unknown): unknown => {
    if (!data || typeof data !== 'object') return data
    if ('pages' in data && Array.isArray(data.pages)) {
      const before: unknown[] = data.pages
      const pages = before.map(update)
      return pages.some((page, index) => page !== before[index]) ? { ...data, pages } : data
    }
    if ('items' in data && Array.isArray(data.items)) {
      const before: Task[] = data.items
      const items = before.map(fresh)
      return items.some((item, index) => item !== before[index]) ? { ...data, items } : data
    }
    return 'id' in data && 'revision' in data ? fresh(data as Task) : data
  }
  for (const query of queries.getQueryCache().findAll({ queryKey: ['tasks'] })) {
    const data = update(query.state.data)
    if (data === query.state.data) continue
    queries.setQueryData(query.queryKey, data)
    void queries.invalidateQueries({ queryKey: query.queryKey, exact: true, refetchType: 'none' })
  }
}

/** Ordering is local to a list and a subtask level, even in a mixed filtered view. */
export function previousSibling(tasks: Task[], task: Task): Task | null {
  const index = tasks.findIndex((item) => item.id === task.id)
  for (let i = index - 1; i >= 0; i--) {
    const item = tasks[i]!
    if (item.list_id === task.list_id && (item.parent_id ?? null) === (task.parent_id ?? null))
      return item
  }
  return null
}
