/**
 * The conversation as the rows of a virtual list: a day heading, then that day's exchanges,
 * notices and sends. Only the rows near the screen are rendered; each row's key stays the same
 * across reloads, so its measured height and the reader's position survive them.
 */
import type { Notification } from '@/sections/notifications/model'
import type { Exchange } from './model'
import type { PendingSend } from './pending'
import type { FeedItem } from './timeline'

export type FeedRow =
  | { kind: 'day'; key: string; day: string; label: string }
  | { kind: 'exchange'; key: string; exchange: Exchange }
  | { kind: 'notice'; key: string; notice: Notification }
  | { kind: 'pending'; key: string; pending: PendingSend }

function row(item: FeedItem): FeedRow {
  if (item.kind === 'exchange') return { kind: 'exchange', key: item.key, exchange: item.exchange }
  if (item.kind === 'notice') return { kind: 'notice', key: item.key, notice: item.notice }
  return { kind: 'pending', key: item.key, pending: item.pending }
}

export function feedRows(
  groups: ReadonlyArray<{ day: string; label: string; items: FeedItem[] }>,
): FeedRow[] {
  return groups.flatMap((group) => [
    { kind: 'day' as const, key: 'day-' + group.day, day: group.day, label: group.label },
    ...group.items.map(row),
  ])
}

/** Characters per line of a bubble on a phone; a guess, corrected once the row is measured. */
const LINE = 42

function lines(text: string) {
  return text ? Math.ceil(text.length / LINE) + (text.match(/\n/g)?.length ?? 0) : 0
}

/** The photo box: at most 240px high, never taller than its own proportions allow on a phone. */
function photo(image: { width: number; height: number }) {
  return Math.min(240, Math.round((image.height / Math.max(1, image.width)) * 300))
}

// On a touch screen only the newest answer lays out its actions (ReplyBlock); with a mouse an
// earlier answer keeps their room for the hover.
const touch = typeof matchMedia === 'function' && matchMedia('(pointer: coarse)').matches

function exchangeHeight(exchange: Exchange) {
  // The time sits inside the message, after its last line.
  let height = 28 + lines(exchange.text) * 24
  for (const image of exchange.images) height += photo(image) + 8
  for (const reply of exchange.replies)
    height += (touch ? 12 : 56) + lines(reply.text) * 26 + (reply.question ? 72 : 0)
  if (exchange.status === 'pending') height += 72
  if (exchange.status === 'failed') height += 120
  return height
}

/** An estimate of a row's height before it is measured, including the gap above it. */
export function estimateRow(row: FeedRow | undefined): number {
  if (!row) return 120
  if (row.kind === 'day') return 48
  if (row.kind === 'exchange') return exchangeHeight(row.exchange) + 20
  return 140
}

/** The day each row belongs to, by its heading: what the floating date shows over a row. */
export function rowDays(rows: readonly FeedRow[]): string[] {
  let label = ''
  return rows.map((row) => (row.kind === 'day' ? (label = row.label) : label))
}

/** The row that shows a message, or the reply of an exchange, opened from search. */
export function rowOf(rows: readonly FeedRow[], messageId: string): number {
  return rows.findIndex(
    (row) =>
      row.kind === 'exchange' &&
      (row.exchange.id === messageId ||
        row.exchange.replies.some((reply) => reply.messageId === messageId)),
  )
}
