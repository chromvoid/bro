/**
 * Where tasks are looked at: the overview of every list, what is assigned to me, my own list,
 * and each project or shared list. A project is a place of its own, shown with its progress.
 */
import type { TaskList } from '@/sections/circle/api'

export type Scope = 'all' | 'assigned' | 'personal'

export interface Place {
  /** A scope or a list id. */
  key: string
  name: string
  kind: 'scope' | 'project' | 'list'
  query: Record<string, string>
  /** Done of all, for a project. */
  progress?: { done: number; total: number }
}

export function progressOf(list: TaskList): { done: number; total: number } {
  const counts = list.counts ?? {}
  const total = Object.values(counts).reduce((sum, value) => sum + value, 0)
  return { done: counts.done ?? 0, total }
}

/**
 * The places in the order they are offered: the scopes (assigned only once something is shared),
 * active projects, then other lists. Finished and archived projects are kept apart.
 */
export function placesOf(
  lists: TaskList[],
  scopeName: (scope: Scope) => string,
): { places: Place[]; finished: Place[] } {
  const shared = lists.some((list) => !!list.space_id)
  const scopes: Scope[] = shared ? ['all', 'assigned', 'personal'] : ['all', 'personal']
  const places: Place[] = scopes.map((scope) => ({
    key: scope,
    name: scopeName(scope),
    kind: 'scope',
    // «Все списки» names its scope: an address without one opens the last place instead.
    query: { scope },
  }))
  const project = (list: TaskList): Place => ({
    key: list.id,
    name: list.name,
    kind: 'project',
    query: { list: list.id },
    progress: progressOf(list),
  })
  const own = lists.filter((list) => !list.personal_user_id)
  const projects = own.filter((list) => list.list_kind === 'project')
  places.push(
    ...projects.filter((list) => (list.project_state ?? 'active') === 'active').map(project),
    ...own
      .filter((list) => list.list_kind !== 'project')
      .map((list): Place => ({
        key: list.id,
        name: list.name,
        kind: 'list',
        query: { list: list.id },
      })),
  )
  const finished = projects
    .filter((list) => (list.project_state ?? 'active') !== 'active')
    .map(project)
  return { places, finished }
}

/*
 * The last place and a project shown as a list are remembered on this device: «Дела» opens where
 * the person left off. Storage can be missing or refuse (private mode); then nothing is kept.
 */
const PLACE = 'assistant.tasks.place'
const LAYOUTS = 'assistant.tasks.layouts'
const ORDERS = 'assistant.tasks.orders'
export const TASK_ORDERS = ['manual', 'priority', 'due', 'created'] as const
export type TaskOrder = (typeof TASK_ORDERS)[number]

function read<T>(key: string): T | null {
  try {
    const value = localStorage.getItem(key)
    return value ? (JSON.parse(value) as T) : null
  } catch {
    return null
  }
}

function write(key: string, value: unknown) {
  try {
    localStorage.setItem(key, JSON.stringify(value))
  } catch {
    // Not kept on this device.
  }
}

export function lastPlace(): Record<string, string> | null {
  const value = read<Record<string, unknown>>(PLACE)
  if (!value) return null
  const query: Record<string, string> = {}
  if (typeof value.list === 'string') query.list = value.list
  else if (value.scope === 'assigned' || value.scope === 'personal') query.scope = value.scope
  return query
}

export function rememberPlace(query: Record<string, string>) {
  write(PLACE, query)
}

/** 'list' when the person switched this project from its board to the list. */
export function projectLayout(list: string): 'list' | undefined {
  return read<Record<string, string>>(LAYOUTS)?.[list] === 'list' ? 'list' : undefined
}

export function rememberLayout(list: string, layout: 'list' | 'board') {
  const layouts = read<Record<string, string>>(LAYOUTS) ?? {}
  if (layout === 'list') layouts[list] = 'list'
  else delete layouts[list]
  write(LAYOUTS, layouts)
}

/** Explicit addresses win; each project's chosen order otherwise stays on this device. */
export function projectOrder(list: string, explicit?: unknown): TaskOrder {
  if (TASK_ORDERS.includes(explicit as TaskOrder)) return explicit as TaskOrder
  const stored = read<Record<string, unknown>>(ORDERS)?.[list]
  return TASK_ORDERS.includes(stored as TaskOrder) ? (stored as TaskOrder) : 'manual'
}

export function rememberOrder(list: string, order: TaskOrder) {
  const orders = read<Record<string, TaskOrder>>(ORDERS) ?? {}
  orders[list] = order
  write(ORDERS, orders)
}
