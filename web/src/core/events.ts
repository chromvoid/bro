/**
 * The account's change stream over fetch (not EventSource): it needs the cursor header on the
 * first connect, has to tell a revoked session (stop) from a full server (back off), and only
 * runs while the page is visible. Domain state is refetched; subscribers may also use events
 * for transient progress, which never replaces an authoritative result or receipt.
 */

export interface ServerEvent {
  id: string | null
  event: string
  data: string
}

/** Incremental text/event-stream parser (WHATWG HTML, server-sent events). */
export class SseParser {
  private buffer = ''
  private id: string | null = null
  private event = ''
  private data: string[] = []

  push(chunk: string): ServerEvent[] {
    this.buffer += chunk
    const events: ServerEvent[] = []
    for (;;) {
      const end = this.buffer.search(/\r\n|\r|\n/)
      // A lone \r at the end may still become \r\n; wait for the next chunk.
      if (end < 0 || (end === this.buffer.length - 1 && this.buffer[end] === '\r')) break
      const line = this.buffer.slice(0, end)
      this.buffer = this.buffer.slice(end + (this.buffer.startsWith('\r\n', end) ? 2 : 1))
      const event = this.line(line)
      if (event) events.push(event)
    }
    return events
  }

  private line(line: string): ServerEvent | null {
    if (line === '') {
      const event = this.data.length
        ? { id: this.id, event: this.event || 'message', data: this.data.join('\n') }
        : null
      this.event = ''
      this.data = []
      return event
    }
    if (line.startsWith(':')) return null
    const colon = line.indexOf(':')
    const field = colon < 0 ? line : line.slice(0, colon)
    let value = colon < 0 ? '' : line.slice(colon + 1)
    if (value.startsWith(' ')) value = value.slice(1)
    if (field === 'data') this.data.push(value)
    else if (field === 'event') this.event = value
    else if (field === 'id' && !value.includes('\0')) this.id = value
    return null
  }
}

export interface ChangeEvent {
  id: number
  name: string
  /** The changed domain, e.g. `nutrition.changed`; null from servers that do not send it. */
  topic: string | null
  data: unknown
}

export interface Cursor {
  load(): Promise<number | null>
  save(value: number): Promise<void>
}

export interface StreamOptions {
  url: string
  cursor: Cursor
  onChange(event: ChangeEvent): void
  /** The server cannot replay from the cursor: reload everything on screen. */
  onResync(cursor?: number): void
  /** 401/403: the session ended or lost access to the stream; it does not reconnect. */
  onDenied(status: number): void
  onConnection?(connected: boolean): void
  fetcher?: typeof fetch
  random?: () => number
  wait?: (ms: number, signal: AbortSignal) => Promise<void>
  headerMs?: number
  idleMs?: number
}

const FIRST_DELAY = 1_000
const MAX_DELAY = 30_000

export class EventStream {
  private controller: AbortController | null = null
  private running: Promise<void> | null = null
  private listeners = new Set<(event: ChangeEvent) => void>()

  constructor(private readonly options: StreamOptions) {}

  /** Observe progress and resync without opening another account stream. */
  subscribe(listener: (event: ChangeEvent) => void): () => void {
    this.listeners.add(listener)
    return () => this.listeners.delete(listener)
  }

  private publish(event: ChangeEvent): void {
    for (const listener of this.listeners) {
      try {
        listener(event)
      } catch {
        // Optional progress rendering must not interrupt durable cursor handling.
      }
    }
  }

  get active(): boolean {
    return this.controller !== null
  }

  start(): void {
    if (this.controller) return
    const controller = new AbortController()
    this.controller = controller
    this.running = this.loop(controller.signal).finally(() => {
      if (this.controller === controller) this.controller = null
    })
  }

  stop(): void {
    this.controller?.abort()
    this.controller = null
  }

  /** Resolves when the current loop has fully ended (tests and orderly shutdown). */
  async settled(): Promise<void> {
    await this.running
  }

  private async loop(signal: AbortSignal): Promise<void> {
    const { fetcher = fetch, random = Math.random, wait = delay } = this.options
    let backoff = FIRST_DELAY
    while (!signal.aborted) {
      try {
        const cursor = await this.options.cursor.load()
        if (signal.aborted) return
        const response = await openEventResponse(
          cursor === null ? `${this.options.url}?cursor=latest` : this.options.url,
          {
            headers: cursor === null ? {} : { 'Last-Event-ID': String(cursor) },
            credentials: 'same-origin',
            cache: 'no-store',
          },
          signal,
          fetcher,
          this.options.headerMs,
        )
        if (signal.aborted) {
          await response.body?.cancel().catch(() => undefined)
          return
        }
        if (response.status === 401 || response.status === 403) {
          await response.body?.cancel()
          if (signal.aborted) return
          this.options.onDenied(response.status)
          // The callback may have synchronously replaced this connection.
          if (this.controller?.signal === signal) this.stop()
          return
        }
        if (!response.ok || !response.body) {
          await response.body?.cancel()
          throw new Error(`HTTP ${response.status}`)
        }
        backoff = FIRST_DELAY
        try {
          this.options.onConnection?.(true)
          await this.read(response.body, cursor, signal)
        } finally {
          if (!response.body.locked) await response.body.cancel().catch(() => undefined)
          if (!signal.aborted) this.options.onConnection?.(false)
        }
      } catch {
        if (signal.aborted) return
      }
      // Jitter spreads reconnects after a server restart.
      await wait(backoff * (0.5 + random() / 2), signal).catch(() => undefined)
      backoff = Math.min(backoff * 2, MAX_DELAY)
    }
  }

  private async read(
    body: ReadableStream<Uint8Array>,
    start: number | null,
    signal: AbortSignal,
  ): Promise<void> {
    const parser = new SseParser()
    const decoder = new TextDecoder()
    const reader = body.getReader()
    let cursor = start
    const cancel = () => {
      void reader.cancel().catch(() => undefined)
    }
    let idle: ReturnType<typeof setTimeout> | undefined
    signal.addEventListener('abort', cancel, { once: true })
    try {
      while (!signal.aborted) {
        clearTimeout(idle)
        // Account heartbeat is every 10s; allow three missed heartbeats plus scheduling slack.
        idle = setTimeout(cancel, this.options.idleMs ?? 45_000)
        const { value, done } = await reader.read()
        if (done || signal.aborted) return
        for (const event of parser.push(decoder.decode(value, { stream: true }))) {
          if (signal.aborted) return
          const id = event.id !== null && /^\d+$/.test(event.id) ? Number(event.id) : null
          if (id === null || !Number.isSafeInteger(id)) continue
          if (event.event === 'resync') {
            this.options.onResync(id)
            if (signal.aborted) return
            this.publish({ id, name: 'resync', topic: null, data: null })
          } else {
            if (cursor !== null && id <= cursor) continue
            const data = parse(event.data)
            const topic =
              typeof data === 'object' &&
              data !== null &&
              typeof (data as { topic?: unknown }).topic === 'string'
                ? (data as { topic: string }).topic
                : null
            const change = { id, name: event.event, topic, data }
            this.options.onChange(change)
            if (signal.aborted) return
            this.publish(change)
          }
          // Handle the change before saving the cursor, so a crash replays it instead of losing it.
          if (signal.aborted) return
          await this.options.cursor.save(id)
          cursor = id
        }
      }
    } finally {
      clearTimeout(idle)
      signal.removeEventListener('abort', cancel)
      // A failed cursor write or consumer must not leave this SSE connection alive
      // while the outer loop opens its replacement.
      await reader.cancel().catch(() => undefined)
      reader.releaseLock()
    }
  }
}

/** Runs the stream only for a visible page; a hidden page keeps it for a short grace period. */
export function followVisibility(
  stream: EventStream,
  page: Document,
  graceMs = 30_000,
): () => void {
  let timer: ReturnType<typeof setTimeout> | undefined
  const update = () => {
    clearTimeout(timer)
    if (page.visibilityState === 'visible') stream.start()
    else timer = setTimeout(() => stream.stop(), graceMs)
  }
  // Leaving the page (or entering the back-forward cache) closes the connection at once.
  const leave = () => {
    clearTimeout(timer)
    stream.stop()
  }
  const view = page.defaultView
  page.addEventListener('visibilitychange', update)
  view?.addEventListener('pagehide', leave)
  view?.addEventListener('pageshow', update)
  update()
  return () => {
    page.removeEventListener('visibilitychange', update)
    view?.removeEventListener('pagehide', leave)
    view?.removeEventListener('pageshow', update)
    leave()
  }
}

function parse(text: string): unknown {
  try {
    return JSON.parse(text)
  } catch {
    return null
  }
}

/** Bound header acquisition even when a fetch implementation delivers a late response. */
export function openEventResponse(
  url: string,
  init: RequestInit,
  signal: AbortSignal,
  fetcher: typeof fetch = fetch,
  timeoutMs = 20_000,
): Promise<Response> {
  return new Promise((resolve, reject) => {
    const request = new AbortController()
    let finished = false
    const finish = () => {
      clearTimeout(timer)
      signal.removeEventListener('abort', abort)
    }
    const abort = () => {
      if (finished) return
      finished = true
      request.abort()
      finish()
      reject(new Error('event_connection_aborted'))
    }
    const timer = setTimeout(abort, timeoutMs)
    signal.addEventListener('abort', abort, { once: true })
    if (signal.aborted) {
      abort()
      return
    }
    ;(async () => fetcher(url, { ...init, signal: request.signal }))().then(
      (response) => {
        if (finished || signal.aborted) {
          void response.body?.cancel().catch(() => undefined)
          return
        }
        finished = true
        finish()
        resolve(response)
      },
      (error) => {
        if (finished) return
        finished = true
        finish()
        reject(error)
      },
    )
  })
}

function delay(ms: number, signal: AbortSignal): Promise<void> {
  return new Promise((resolve) => {
    const finish = () => {
      clearTimeout(timer)
      signal.removeEventListener('abort', finish)
      resolve()
    }
    const timer = setTimeout(finish, ms)
    if (signal.aborted) finish()
    else signal.addEventListener('abort', finish, { once: true })
  })
}
