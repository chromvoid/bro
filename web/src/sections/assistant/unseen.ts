import type { Exchange } from './model'

/**
 * What the reader had when they left the newest messages: the answers already there and the
 * oldest loaded exchange. Earlier pages loaded meanwhile come before it and are not new.
 */
export interface SeenMark {
  replies: ReadonlySet<string>
  from: string | undefined
}

export function markSeen(exchanges: readonly Exchange[]): SeenMark {
  return {
    replies: new Set(
      exchanges.flatMap((exchange) =>
        exchange.replies.filter((reply) => reply.completed).map((reply) => reply.turnId),
      ),
    ),
    from: exchanges[0]?.id,
  }
}

/** Answers completed since the mark, for the count on the way back to the newest messages. */
export function unseenReplies(exchanges: readonly Exchange[], mark: SeenMark): number {
  const start = Math.max(
    0,
    exchanges.findIndex((exchange) => exchange.id === mark.from),
  )
  let count = 0
  for (const exchange of exchanges.slice(start))
    for (const reply of exchange.replies)
      if (reply.completed && !mark.replies.has(reply.turnId)) count++
  return count
}
