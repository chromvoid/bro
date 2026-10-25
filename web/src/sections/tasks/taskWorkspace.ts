/** Neighbours are the tasks already loaded in the current column, in their displayed order. */
import { stateOf, type Task } from './api'

export function taskNeighbours(
  selected: Task | null | undefined,
  loaded: readonly Task[],
): { previous: Task | null; next: Task | null } {
  if (!selected) return { previous: null, next: null }
  const column = loaded.filter(
    (task) => task.list_id === selected.list_id && stateOf(task) === stateOf(selected),
  )
  const index = column.findIndex((task) => task.id === selected.id)
  return index < 0
    ? { previous: null, next: null }
    : { previous: column[index - 1] ?? null, next: column[index + 1] ?? null }
}
