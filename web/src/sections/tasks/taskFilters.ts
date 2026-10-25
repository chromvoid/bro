/** One URL contract for the board, its continuation pages and the list layout. */
import type { LocationQuery, LocationQueryRaw } from 'vue-router'

export type TaskAssigneeFilter = 'me' | 'unassigned' | null
export type TaskCompletedScope = 'recent' | 'all'
export type TaskLayout = 'board' | 'list'

export interface TaskFilterState {
  q: string
  important: boolean
  overdue: boolean
  assignee: TaskAssigneeFilter
  completed_scope: TaskCompletedScope
}

export const EMPTY_TASK_FILTERS: Readonly<TaskFilterState> = Object.freeze({
  q: '',
  important: false,
  overdue: false,
  assignee: null,
  completed_scope: 'recent',
})

type Query = LocationQuery | LocationQueryRaw
const FILTER_KEYS = ['q', 'important', 'overdue', 'assignee', 'completed_scope'] as const

/** Repeated values and unknown spellings never turn into an active condition. */
export function readTaskFilters(query: Query): TaskFilterState {
  return {
    q: typeof query.q === 'string' ? query.q.trim() : '',
    important: query.important === '1',
    overdue: query.overdue === '1',
    assignee: query.assignee === 'me' || query.assignee === 'unassigned' ? query.assignee : null,
    completed_scope: query.completed_scope === 'all' ? 'all' : 'recent',
  }
}

/** Conditions narrow every task independently, including children of unmatched parents. */
export function hasTaskNarrowing(filters: TaskFilterState): boolean {
  return !!(filters.q || filters.important || filters.overdue || filters.assignee)
}

export function activeTaskFilterCount(filters: TaskFilterState): number {
  return (
    Number(!!filters.q) +
    Number(filters.important) +
    Number(filters.overdue) +
    Number(filters.assignee !== null) +
    Number(filters.completed_scope === 'all')
  )
}

/** Query keys bind all conditions, including the scope of completed tasks. */
export function taskFilterKey(filters: TaskFilterState) {
  return [
    filters.q,
    filters.important,
    filters.overdue,
    filters.assignee,
    filters.completed_scope,
  ] as const
}

/** A partial UI change keeps the other AND conditions and all unrelated route keys. */
export function taskFiltersQuery(query: Query, patch: Partial<TaskFilterState>): LocationQueryRaw {
  const current = readTaskFilters(query)
  const next: TaskFilterState = {
    q: patch.q === undefined ? current.q : patch.q.trim(),
    important: patch.important === undefined ? current.important : patch.important,
    overdue: patch.overdue === undefined ? current.overdue : patch.overdue,
    assignee: patch.assignee === undefined ? current.assignee : patch.assignee,
    completed_scope:
      patch.completed_scope === undefined ? current.completed_scope : patch.completed_scope,
  }
  return {
    ...query,
    q: next.q || undefined,
    important: next.important ? '1' : undefined,
    overdue: next.overdue ? '1' : undefined,
    assignee: next.assignee ?? undefined,
    completed_scope: next.completed_scope === 'all' ? 'all' : undefined,
  }
}

export function resetTaskFiltersQuery(query: Query): LocationQueryRaw {
  return taskFiltersQuery(query, EMPTY_TASK_FILTERS)
}

/** Widening a list's search must include completed tasks, not keep its open-only view. */
export function taskWholeHistoryQuery(query: Query, layout: TaskLayout): LocationQueryRaw {
  const next = taskFiltersQuery(query, { completed_scope: 'all' })
  return layout === 'list' ? { ...next, view: 'all' } : next
}

/** Switching layout must not silently narrow an explicitly widened search. */
export function taskLayoutQuery(query: Query, layout: TaskLayout): LocationQueryRaw {
  const next: LocationQueryRaw = { ...query, layout }
  if (layout === 'list' && readTaskFilters(query).completed_scope === 'all') next.view = 'all'
  return next
}

/** Add exactly the same server filters to first-page and continuation requests. */
export function applyTaskFilterParams(params: URLSearchParams, filters: TaskFilterState): void {
  for (const key of FILTER_KEYS) params.delete(key)
  if (filters.q) params.set('q', filters.q)
  if (filters.important) params.set('important', '1')
  if (filters.overdue) params.set('overdue', '1')
  if (filters.assignee) params.set('assignee', filters.assignee)
  params.set('completed_scope', filters.completed_scope)
}
