import type { Task } from './api'

export const QUEUE_COMMANDS = ['top', 'up', 'down', 'end'] as const
export type QueueCommand = (typeof QUEUE_COMMANDS)[number]
export type QueueTarget = { beforeId: string | null } | { more: true } | null

/** A root and a visible child of a closed parent still belong to different sibling queues. */
export function sameLevel(first: Task, second: Task) {
  return (
    first.list_id === second.list_id && (first.parent_id ?? null) === (second.parent_id ?? null)
  )
}

/** `more` means the next sibling is not known yet; the end of a loaded prefix is not its tail. */
export function queueTarget(
  items: readonly Task[],
  task: Task,
  command: QueueCommand,
  hasMore: boolean,
): QueueTarget {
  const siblings = items.filter((item) => sameLevel(item, task))
  const index = siblings.findIndex((item) => item.id === task.id)
  if (index < 0) return null
  if (command === 'end') return { beforeId: null }
  if (command === 'top') return index ? { beforeId: siblings[0]!.id } : null
  if (command === 'up') return index ? { beforeId: siblings[index - 1]!.id } : null
  const afterNext = siblings[index + 2]
  if (afterNext) return { beforeId: afterNext.id }
  if (hasMore) return { more: true }
  return siblings[index + 1] ? { beforeId: null } : null
}

/** A drop at a partial column's visible end changes state only; an explicit end command exists. */
export function dropTarget(
  items: readonly Task[],
  task: Task,
  index: number,
  hasMore: boolean,
): string | null | undefined {
  const after = items.slice(index + 1).find((item) => sameLevel(item, task))
  return after ? after.id : hasMore ? undefined : null
}
