/**
 * Pure edits of the loaded conversation history: pages newest first, each page oldest first, as
 * the server sends them. Exchanges an event names are replaced, inserted or removed in place, so
 * one change never needs every loaded page again.
 */
import { replaceEqualDeep, type InfiniteData } from '@tanstack/vue-query'
import type { History, Message } from './model'

export type HistoryData = InfiniteData<History, string | null>

/** The server's history order: (created_at, id), ascending. */
export function order(a: Message, b: Message): number {
  const time = Date.parse(a.created_at) - Date.parse(b.created_at)
  if (time) return time
  // Equal milliseconds: the timestamps share one format, so their text keeps the microseconds' order.
  if (a.created_at !== b.created_at) return a.created_at < b.created_at ? -1 : 1
  return a.id < b.id ? -1 : a.id > b.id ? 1 : 0
}

export interface HistoryIndex {
  messages: Set<string>
  /** Turn id → the user message it answers. */
  turns: Map<string, string>
  /** Clarified message → the messages that carry its clarification receipts. */
  sources: Map<string, string[]>
  /** Messages with a proposal still waiting for consent. */
  proposals: Set<string>
}

export function indexHistory(data: HistoryData): HistoryIndex {
  const index: HistoryIndex = {
    messages: new Set(),
    turns: new Map(),
    sources: new Map(),
    proposals: new Set(),
  }
  for (const page of data.pages)
    for (const message of page.messages) {
      index.messages.add(message.id)
      for (const turn of message.turns ?? []) {
        index.turns.set(turn.id, message.id)
        if (turn.result?.confirmation_pending) index.proposals.add(message.id)
      }
      const root = message.clarification_receipt?.message_id
      if (root) index.sources.set(root, [...(index.sources.get(root) ?? []), message.id])
    }
  return index
}

function newest(data: HistoryData): Message | undefined {
  return data.pages.find((page) => page.messages.length)?.messages.at(-1)
}

function withPages(data: HistoryData, pages: History[], pageParams = data.pageParams): HistoryData {
  return { pages, pageParams }
}

/**
 * Replaces fetched messages where they are and inserts new ones in order. A message newer than
 * everything loaded joins the newest page only while the view shows the newest page; one older
 * than everything loaded is left for "earlier" to bring, unless the oldest page is the start of
 * the conversation. Missing messages leave the cache; the oldest page's cursor moves to the oldest
 * message left, so loading earlier never asks for a message that is gone.
 */
export function applyMessages(
  data: HistoryData,
  fetched: readonly Message[],
  missing: readonly string[],
  atHead = true,
): HistoryData {
  const gone = new Set(missing)
  const byId = new Map(fetched.map((message) => [message.id, message]))
  let pages = data.pages.map((page) => {
    const messages = page.messages.flatMap((message) => {
      if (gone.has(message.id)) return []
      const fresh = byId.get(message.id)
      if (fresh) byId.delete(message.id)
      return [fresh ?? message]
    })
    return messages.length === page.messages.length &&
      messages.every((message, i) => message === page.messages[i])
      ? page
      : { ...page, messages }
  })
  const latest = newest({ ...data, pages })
  for (const message of [...byId.values()].sort(order)) {
    if (!latest || order(message, latest) > 0) {
      if (!atHead || !pages.length) continue
      pages = [{ ...pages[0]!, messages: [...pages[0]!.messages, message] }, ...pages.slice(1)]
      continue
    }
    const target = pages.findIndex(
      (page) => page.messages[0] && order(message, page.messages[0]) > 0,
    )
    const last = pages.length - 1
    const index = target >= 0 ? target : pages[last]?.before === null ? last : -1
    if (index < 0) continue
    const page = pages[index]!
    const messages = [...page.messages, message].sort(order)
    pages = pages.map((item, i) => (i === index ? { ...page, messages } : item))
  }
  return repairCursor(withPages(data, pages))
}

/** The oldest page names the cursor for "earlier": a loaded message, else the oldest one left. */
function repairCursor(data: HistoryData): HistoryData {
  const before = data.pages.at(-1)?.before
  if (before == null) return data
  if (data.pages.some((page) => page.messages.some((message) => message.id === before))) return data
  const oldest = data.pages.findLast((page) => page.messages.length)?.messages[0]
  if (!oldest) return data
  const pages = data.pages.map((page, i) =>
    i === data.pages.length - 1 ? { ...page, before: oldest.id } : page,
  )
  return withPages(data, pages)
}

/**
 * Merges a fresh newest page. Within its range the server is authoritative: loaded messages it no
 * longer lists are gone. A fresh page that does not reach back to the loaded messages (more new
 * messages than a page) or that is the whole conversation replaces the loaded history.
 */
export function mergeHead(data: HistoryData | undefined, head: History): HistoryData {
  const oldest = head.messages[0]
  const latest = data && newest(data)
  if (!data || !oldest || head.before === null || !latest || order(oldest, latest) > 0)
    return { pages: [head], pageParams: [null] }
  const pages: History[] = []
  const pageParams: Array<string | null> = []
  data.pages.forEach((page, i) => {
    const older = page.messages.filter((message) => order(message, oldest) < 0)
    // A newest page left with nothing older than the fresh one continues from the fresh cursor.
    if (i === 0)
      pages.push({
        ...page,
        messages: [...older, ...head.messages],
        before: older.length ? page.before : head.before,
      })
    else if (older.length)
      pages.push(older.length === page.messages.length ? page : { ...page, messages: older })
    else return
    pageParams.push(data.pageParams[i] ?? null)
  })
  return rechunk(repairCursor({ pages, pageParams }))
}

/** Splits a newest page that grew past twice the page size back into server-sized pages. */
export function rechunk(data: HistoryData, size = 50): HistoryData {
  const first = data.pages[0]
  if (!first || first.messages.length <= size * 2) return data
  const recent = first.messages.slice(-size)
  const rest = first.messages.slice(0, -size)
  const cursor = recent[0]!.id
  return {
    pages: [
      { ...first, messages: recent, before: cursor },
      { ...first, messages: rest },
      ...data.pages.slice(1),
    ],
    pageParams: [data.pageParams[0] ?? null, cursor, ...data.pageParams.slice(1)],
  }
}

/**
 * Structural sharing by message id: a refetch shifts messages between pages, which index-based
 * sharing treats as all new. Unchanged messages keep their objects, and so their exchanges.
 */
export function shareMessages(previous: unknown, next: unknown): unknown {
  const old = previous as HistoryData | undefined
  const fresh = next as HistoryData | undefined
  if (!old?.pages || !fresh?.pages) return replaceEqualDeep(previous, next)
  const known = new Map<string, Message>()
  for (const page of old.pages) for (const message of page.messages) known.set(message.id, message)
  const pages = fresh.pages.map((page, i) => {
    const messages = page.messages.map((message) => {
      const before = known.get(message.id)
      return before ? replaceEqualDeep(before, message) : message
    })
    const same = old.pages[i]
    return same &&
      same.before === page.before &&
      same.messages.length === messages.length &&
      messages.every((message, j) => message === same.messages[j])
      ? same
      : { ...page, messages }
  })
  const unchanged =
    pages.length === old.pages.length &&
    pages.every((page, i) => page === old.pages[i]) &&
    fresh.pageParams.length === old.pageParams.length &&
    fresh.pageParams.every((param, i) => param === old.pageParams[i])
  return unchanged ? old : { ...fresh, pages }
}
