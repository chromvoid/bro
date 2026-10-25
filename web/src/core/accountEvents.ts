/** One account SSE per browser profile. Followers refetch state and receive disposable events. */
import { EventStream, type ChangeEvent, type Cursor, type StreamOptions } from './events'

interface Channel {
  postMessage(value: unknown): void
  onmessage: ((event: MessageEvent) => void) | null
  close(): void
}
interface Options {
  userId: string
  cursor: Cursor
  onChange(event: ChangeEvent): void
  onResync(): void
  onDenied(status: number): void
  page?: Document
  locks?: LockManager | null
  channel?: Channel | null
  makeStream?: (options: StreamOptions) => EventStream
  pollMs?: number
}

export class AccountEvents {
  private readonly page: Document
  private readonly locks: LockManager | null
  private channel: Channel | null
  private readonly listeners = new Set<(event: ChangeEvent) => void>()
  private controller: AbortController | null = null
  private stream: EventStream | null = null
  private release: (() => void) | null = null
  private poll: ReturnType<typeof setInterval> | undefined
  private closed = false
  private started = false
  private leader: string | null = null
  private readonly id = crypto.randomUUID()
  private cursor: number | null = null

  constructor(private readonly options: Options) {
    this.page = options.page ?? document
    this.locks = options.locks === undefined ? (navigator.locks ?? null) : options.locks
    this.channel = options.channel === undefined ? this.openChannel() : options.channel
    if (this.channel) this.channel.onmessage = (event) => this.receive(event.data)
  }

  private openChannel(): Channel | null {
    try {
      return typeof BroadcastChannel === 'undefined'
        ? null
        : new BroadcastChannel(`assistant-events/${this.options.userId}`)
    } catch {
      return null
    }
  }

  subscribe(listener: (event: ChangeEvent) => void): () => void {
    this.listeners.add(listener)
    return () => this.listeners.delete(listener)
  }

  get active(): boolean {
    return this.controller !== null || this.poll !== undefined
  }

  start(): void {
    if (this.started || this.closed) return
    this.started = true
    this.page.addEventListener('visibilitychange', this.visible)
    this.page.defaultView?.addEventListener('pagehide', this.suspend)
    this.page.defaultView?.addEventListener('pageshow', this.visible)
    this.page.defaultView?.addEventListener('online', this.online)
    this.visible()
  }

  /** A refreshed identity can restore stream access without replacing this account's journal. */
  refresh(): void {
    this.visible()
  }

  stop(): void {
    if (this.closed) return
    this.closed = true
    this.page.removeEventListener('visibilitychange', this.visible)
    this.page.defaultView?.removeEventListener('pagehide', this.suspend)
    this.page.defaultView?.removeEventListener('pageshow', this.visible)
    this.page.defaultView?.removeEventListener('online', this.online)
    this.suspend()
    this.channel?.close()
    this.channel = null
    this.listeners.clear()
  }

  private readonly visible = () => {
    if (this.closed) return
    if (this.page.visibilityState !== 'visible') return this.suspend()
    // Shared cursor is not evidence that this tab has applied the intervening changes.
    this.resync()
    if (this.active) return
    if (!this.locks || !this.channel) return this.fallback()
    const controller = new AbortController()
    this.controller = controller
    const current = () =>
      !this.closed && this.controller === controller && !controller.signal.aborted
    this.post({ type: 'join' })
    void this.locks
      .request(
        `assistant-events/${this.options.userId}`,
        { signal: controller.signal },
        async () => {
          if (!current()) return
          this.leader = this.id
          this.post({ type: 'leader' })
          this.resync()
          const stream = (this.options.makeStream ?? ((options) => new EventStream(options)))({
            url: '/v1/me/events',
            cursor: {
              load: async () => {
                try {
                  const saved = await this.options.cursor.load()
                  if (current() && saved !== null)
                    this.cursor = Math.max(saved, this.cursor ?? saved)
                } catch {
                  // Persistence failure loses replay, never live invalidations. Resync every connect.
                  if (current()) this.resync()
                }
                return this.cursor
              },
              save: async (id) => {
                if (!current()) return
                this.cursor = id
                await this.options.cursor.save(id).catch(() => undefined)
              },
            },
            onChange: (event) => {
              if (!current()) return
              this.publish(event)
              this.post({ type: 'event', event })
            },
            onResync: (cursor) => {
              if (!current()) return
              if (cursor !== undefined) this.cursor = cursor
              this.resync()
              this.post({ type: 'resync', cursor })
            },
            onDenied: (status) => {
              if (!current()) return
              this.post({ type: 'denied', status })
              this.options.onDenied(status)
              this.suspend()
            },
            onConnection: (connected) => {
              if (!current()) return
              if (connected) {
                this.resync()
                this.post({ type: 'resync' })
              } else {
                this.clearProgress()
                this.post({ type: 'reset' })
              }
            },
          })
          this.stream = stream
          const holding = new Promise<void>((resolve) => {
            this.release = resolve
          })
          stream.start()
          await holding
          stream.stop()
          await stream.settled()
        },
      )
      .catch(() => {
        if (!current()) return
        this.controller = null
        this.fallback()
      })
  }

  private readonly online = () => {
    if (this.closed || this.page.visibilityState !== 'visible') return
    this.suspend()
    this.visible()
  }

  private readonly suspend = () => {
    this.controller?.abort()
    this.controller = null
    this.stream?.stop()
    this.stream = null
    this.release?.()
    this.release = null
    this.leader = null
    clearInterval(this.poll)
    this.poll = undefined
  }

  private fallback(): void {
    // Without both APIs opening a connection in each tab can exhaust HTTP/1.1's pool.
    this.poll = setInterval(() => {
      if (!this.closed && this.page.visibilityState === 'visible') this.resync()
    }, this.options.pollMs ?? 15_000)
  }

  private post(value: Record<string, unknown>): void {
    this.channel?.postMessage({ ...value, sender: this.id })
  }

  private receive(value: unknown): void {
    if (
      this.closed ||
      this.page.visibilityState !== 'visible' ||
      !value ||
      typeof value !== 'object'
    )
      return
    const message = value as Record<string, unknown>
    if (typeof message.sender !== 'string' || message.sender === this.id) return
    if (message.type === 'join') {
      if (this.leader === this.id) this.post({ type: 'leader' })
      return
    }
    if (message.type === 'leader') {
      if (this.leader !== message.sender) {
        this.leader = message.sender
        this.resync()
      }
      return
    }
    if (message.sender !== this.leader) return
    if (message.type === 'resync') {
      if (typeof message.cursor === 'number' && Number.isSafeInteger(message.cursor))
        this.cursor = message.cursor
      this.resync()
    } else if (message.type === 'reset') this.clearProgress()
    else if (message.type === 'denied' && (message.status === 401 || message.status === 403)) {
      // Another tab's request may belong to an older cookie session. Recheck with our
      // own reads and take over its released lock; never sign out from a relayed 401.
      this.leader = null
      this.resync()
    } else if (message.type === 'event' && message.event && typeof message.event === 'object') {
      const event = message.event as ChangeEvent
      if (
        !Number.isSafeInteger(event.id) ||
        typeof event.name !== 'string' ||
        (event.topic !== null && typeof event.topic !== 'string')
      )
        return
      if (this.cursor !== null && event.id <= this.cursor) return
      this.cursor = event.id
      this.publish(event)
    }
  }

  private publish(event: ChangeEvent): void {
    this.options.onChange(event)
    this.notify(event)
  }

  private resync(): void {
    this.options.onResync()
    this.clearProgress()
  }

  private clearProgress(): void {
    this.notify({ id: this.cursor ?? 0, name: 'resync', topic: null, data: null })
  }

  private notify(event: ChangeEvent): void {
    for (const listener of this.listeners) {
      try {
        listener(event)
      } catch {
        /* Optional views do not own the shared connection. */
      }
    }
  }
}
