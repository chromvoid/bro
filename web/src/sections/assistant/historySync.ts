/**
 * Keeps the loaded conversation current one exchange at a time. A change event names the exchange
 * it touched; only that exchange is read again (`?message_id=`), and only an event the loaded
 * history cannot place reads the newest page. Every write goes through here, after the history
 * query's own page loads have finished, so neither overwrites the other. Anything unexpected falls
 * back to invalidating the history, which reloads it whole.
 */
import type { Query, QueryClient, QueryKey } from '@tanstack/vue-query'
import { z } from '@/core/zod'
import type { ChangeEvent } from '@/core/events'
import { ApiError } from '@/core/http'
import type { History, Message } from './model'
import { applyMessages, indexHistory, mergeHead, type HistoryData } from './historyCache'

export interface HistoryTarget {
  messages?: readonly string[]
  turns?: readonly string[]
  /** Read the newest page: something new may exist that no loaded exchange names. */
  head?: boolean
}

export interface HistorySyncOptions {
  client: QueryClient
  /** The history query on screen. */
  key: () => QueryKey
  /** The newest page (null), or the one exchange of a message. */
  read: (messageId: string | null, signal: AbortSignal) => Promise<History>
  /** Whether the loaded history reaches the newest message, so new ones join it. */
  atHead?: () => boolean
  delayMs?: number
}

const Payload = z.looseObject({
  message_id: z.string().nullish(),
  source_message_id: z.string().nullish(),
  turn_id: z.string().nullish(),
  thread_id: z.string().nullish(),
  session_id: z.string().nullish(),
  state: z.string().nullish(),
  stage: z.string().nullish(),
})
const PENDING = new Set(['queued', 'routing', 'processing'])
/** More exchanges than this in one batch are cheaper to reload with the pages. */
const MOST = 10
/** Messages of other chats (shared channels) answer 404 here; remembered so they are read once. */
const FOREIGN = 200

interface Batch {
  messages: Set<string>
  /** Exchanges already read by the caller, applied without reading them again. */
  read: Map<string, Message>
  /** Turn → the message an event named with it, if any. */
  turns: Map<string, string | null>
  head: boolean
  /** Some exchange reached its end: a proposal it settled may have changed an older reply. */
  settled: boolean
  /** Replace the window with the newest page. */
  latest: boolean
  done: Array<() => void>
}

const empty = (): Batch => ({
  messages: new Set(),
  read: new Map(),
  turns: new Map(),
  head: false,
  settled: false,
  latest: false,
  done: [],
})

export class HistorySync {
  private queued = empty()
  private timer: ReturnType<typeof setTimeout> | undefined
  private running = false
  private disposed = false
  private controller = new AbortController()
  private readonly foreign = new Set<string>()

  constructor(private readonly options: HistorySyncOptions) {}

  /** Takes one change event; false leaves it to invalidation (no loaded history to edit). */
  handle(event: ChangeEvent): boolean {
    if (this.disposed || event.topic !== 'assistant.changed') return false
    const query = this.query()
    if (!query?.state.data || query.state.status !== 'success') return false
    const parsed = Payload.safeParse(event.data)
    const data = parsed.success ? parsed.data : {}
    // A step of a running turn is progress only; its exchange changes when the turn settles.
    if (data.stage === 'tool') return true
    const messages = [data.message_id, data.source_message_id].filter((id): id is string => !!id)
    if (!messages.length && !data.turn_id) {
      // Opening or closing a topic changes no exchange; an event that names nothing may be anything.
      if (!data.thread_id && !data.session_id) this.enqueue({ head: true })
      return true
    }
    const batch = this.queued
    for (const id of messages) batch.messages.add(id)
    if (data.turn_id) batch.turns.set(data.turn_id, data.message_id ?? null)
    if (data.state && !PENDING.has(data.state)) batch.settled = true
    this.schedule(this.options.delayMs ?? 250)
    return true
  }

  /** Reads the named exchanges again; resolves once the change is on screen or given up. */
  touch(target: HistoryTarget): Promise<void> {
    return this.enqueue(target, 0)
  }

  /** Applies exchanges the caller has just read itself, in order with the other changes. */
  deliver(messages: readonly Message[]): Promise<void> {
    for (const message of messages) this.queued.read.set(message.id, message)
    return this.enqueue({ messages: messages.map((message) => message.id) }, 0)
  }

  /** Moves the window to the newest page, after any change already queued. */
  latest(): Promise<void> {
    this.queued.latest = true
    return this.enqueue({}, 0)
  }

  /** The query on screen changed (a search opened or closed): queued work belonged to the old one. */
  reset() {
    this.controller.abort()
    this.controller = new AbortController()
    clearTimeout(this.timer)
    this.timer = undefined
    const dropped = this.queued
    this.queued = empty()
    dropped.done.forEach((resolve) => resolve())
  }

  dispose() {
    this.disposed = true
    this.reset()
  }

  private enqueue(target: HistoryTarget, delay = this.options.delayMs ?? 250): Promise<void> {
    if (this.disposed) return Promise.resolve()
    const batch = this.queued
    for (const id of target.messages ?? []) batch.messages.add(id)
    for (const id of target.turns ?? []) if (!batch.turns.has(id)) batch.turns.set(id, null)
    if (target.head) batch.head = true
    const done = new Promise<void>((resolve) => batch.done.push(resolve))
    this.schedule(delay)
    return done
  }

  private schedule(delay: number) {
    if (this.running) return
    if (this.timer && delay > 0) return
    clearTimeout(this.timer)
    this.timer = setTimeout(() => {
      this.timer = undefined
      void this.run()
    }, delay)
  }

  private pending(batch: Batch) {
    return (
      batch.messages.size || batch.turns.size || batch.head || batch.latest || batch.done.length
    )
  }

  private async run() {
    this.running = true
    try {
      while (!this.disposed && this.pending(this.queued)) {
        const batch = this.queued
        this.queued = empty()
        try {
          await this.apply(batch)
        } finally {
          batch.done.forEach((resolve) => resolve())
        }
      }
    } finally {
      this.running = false
    }
  }

  private query(): Query | undefined {
    return this.options.client.getQueryCache().find({ queryKey: this.options.key(), exact: true })
  }

  /** Resolves once the history query is not loading pages, is gone, or this work was dropped. */
  private idle(): Promise<void> {
    const signal = this.controller.signal
    const busy = () => this.query()?.state.fetchStatus === 'fetching' && !signal.aborted
    if (!busy()) return Promise.resolve()
    return new Promise((resolve) => {
      const finish = () => {
        stop()
        signal.removeEventListener('abort', finish)
        resolve()
      }
      const stop = this.options.client.getQueryCache().subscribe(() => {
        if (!busy()) finish()
      })
      signal.addEventListener('abort', finish)
    })
  }

  private invalidate() {
    if (!this.disposed)
      void this.options.client.invalidateQueries({ queryKey: this.options.key(), exact: true })
  }

  private remember(id: string) {
    this.foreign.add(id)
    if (this.foreign.size > FOREIGN) this.foreign.delete(this.foreign.values().next().value!)
  }

  private async apply(batch: Batch, attempt = 0): Promise<void> {
    await this.idle()
    const query = this.query()
    const data = query?.state.data as HistoryData | undefined
    if (this.disposed || !query || !data || query.state.status !== 'success') return
    const started = query.state.dataUpdatedAt
    const index = indexHistory(data)
    const ids = new Set<string>()
    for (const id of batch.messages) {
      ids.add(id)
      for (const source of index.sources.get(id) ?? []) ids.add(source)
    }
    const atHead = this.options.atHead?.() ?? true
    // Away from the newest message, the newest page would not join the window: it is not read.
    let head = (batch.head || batch.latest) && (atHead || batch.latest)
    for (const [turn, message] of batch.turns) {
      const owner = message ?? index.turns.get(turn)
      if (owner) ids.add(owner)
      else if (atHead) head = true
    }
    if (batch.settled) for (const id of index.proposals) ids.add(id)
    for (const id of ids) if (this.foreign.has(id)) ids.delete(id)
    if (!ids.size && !head) return this.related(query)
    if (ids.size > MOST) return this.invalidate()

    const signal = this.controller.signal
    const fetched: Message[] = []
    const missing: string[] = []
    let fresh: History | null = null
    try {
      for (const id of ids) {
        const known = batch.read.get(id)
        if (known) {
          fetched.push(known)
          continue
        }
        let messages: Message[] = []
        try {
          messages = (await this.options.read(id, signal)).messages
        } catch (error) {
          if (!(error instanceof ApiError && error.status === 404)) throw error
        }
        if (messages.length) fetched.push(...messages)
        else if (index.messages.has(id)) missing.push(id)
        else this.remember(id)
      }
      if (head) fresh = await this.options.read(null, signal)
    } catch {
      if (!signal.aborted) this.invalidate()
      return
    }

    await this.idle()
    const current = this.query()
    if (this.disposed || signal.aborted || current !== query || current.state.status !== 'success')
      return
    // Writes here are serial, so a newer cache means a page load wrote meanwhile: what was read
    // may be older than it. Read again.
    if (current.state.dataUpdatedAt !== started)
      return attempt < 2 ? this.apply(batch, attempt + 1) : this.invalidate()
    this.options.client.setQueryData<HistoryData>(this.options.key(), (value) => {
      if (!value) return value
      if (batch.latest && fresh) return { pages: [fresh], pageParams: [null] }
      const merged = fresh && atHead ? mergeHead(value, fresh) : value
      return applyMessages(merged, fetched, missing, atHead)
    })
    this.related(current)
  }

  /** Search results and turn details under the same domain refresh only where they are open. */
  private related(active: Query) {
    void this.options.client.invalidateQueries(
      { queryKey: ['conversation'], predicate: (query) => query.queryHash !== active.queryHash },
      { cancelRefetch: false },
    )
  }
}
