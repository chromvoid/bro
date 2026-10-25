import { dayIn, dayLabel } from '@/core/dates'
import type { Notification } from '@/sections/notifications/model'
import type { Exchange } from './model'
import type { PendingSend } from './pending'

export type FeedItem =
  | { kind: 'exchange'; key: string; at: string; exchange: Exchange }
  | { kind: 'notice'; key: string; at: string; notice: Notification }
  | { kind: 'pending'; key: string; at: string; pending: PendingSend }

/** Timestamped browser submissions belong in the same day as the messages around them. */
export function conversationDays(
  exchanges: Exchange[],
  notices: Notification[],
  pending: PendingSend[],
  zone: string,
  now = new Date(),
) {
  const today = dayIn(zone, now)
  const feed: FeedItem[] = [
    ...exchanges.map((exchange) => ({
      kind: 'exchange' as const,
      key: exchange.id,
      at: exchange.createdAt,
      exchange,
    })),
    ...notices.map((notice) => ({
      kind: 'notice' as const,
      key: 'notice-' + notice.id,
      at: notice.updated_at,
      notice,
    })),
    ...pending.flatMap((entry) =>
      entry.createdAt
        ? [
            {
              kind: 'pending' as const,
              key: 'pending-' + entry.key,
              at: entry.createdAt,
              pending: entry,
            },
          ]
        : [],
    ),
  ]
  // Each timestamp is parsed once, not in every comparison of the sort.
  const timed = feed.map((item) => ({ item, time: Date.parse(item.at) }))
  timed.sort((a, b) => a.time - b.time)
  const result: Array<{ day: string; label: string; items: FeedItem[] }> = []
  for (const { item, time } of timed) {
    const day = dayIn(zone, new Date(time))
    const last = result.at(-1)
    if (last?.day === day) last.items.push(item)
    else result.push({ day, label: dayLabel(day, today), items: [item] })
  }
  return result
}
