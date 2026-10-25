/** One foreground connection per tab; recordings only borrow its transport. */
export interface LiveListener {
  open(): void
  message(message: Record<string, unknown>): void
  close(reason: 'connection' | 'backgrounded'): void
}

export class LiveSocket {
  private socket: WebSocket | null = null
  private started = false
  private attempts = 0
  private retry: ReturnType<typeof setTimeout> | undefined
  private readonly listeners = new Set<LiveListener>()

  constructor(
    private readonly page: Document = document,
    private readonly create: (url: string) => WebSocket = (url) => new WebSocket(url),
  ) {}

  get connected(): boolean {
    return this.socket?.readyState === WebSocket.OPEN
  }

  get bufferedAmount(): number {
    return this.socket?.bufferedAmount ?? 0
  }

  subscribe(listener: LiveListener): () => void {
    this.listeners.add(listener)
    return () => this.listeners.delete(listener)
  }

  /** An interaction bypasses backoff, but reuses a connection already in progress. */
  ensure(): void {
    if (this.socket) return
    this.attempts = 0
    this.connect()
  }

  send(data: string | ArrayBuffer): boolean {
    if (!this.connected) return false
    try {
      this.socket!.send(data)
      return true
    } catch {
      return false
    }
  }

  start(): void {
    if (this.started) return
    this.started = true
    this.page.addEventListener('visibilitychange', this.visible)
    this.page.defaultView?.addEventListener('pagehide', this.suspend)
    this.page.defaultView?.addEventListener('pageshow', this.visible)
    this.page.defaultView?.addEventListener('online', this.online)
    this.visible()
  }

  stop(): void {
    this.started = false
    this.page.removeEventListener('visibilitychange', this.visible)
    this.page.defaultView?.removeEventListener('pagehide', this.suspend)
    this.page.defaultView?.removeEventListener('pageshow', this.visible)
    this.page.defaultView?.removeEventListener('online', this.online)
    this.drop('connection')
  }

  private readonly visible = () => {
    if (this.page.visibilityState !== 'visible') return this.suspend()
    this.connect()
  }

  private readonly suspend = () => this.drop('backgrounded')

  private readonly online = () => this.ensure()

  private connect(): void {
    if (!this.started || this.page.visibilityState !== 'visible' || this.socket) return
    clearTimeout(this.retry)
    const url = new URL('/v1/live', this.page.location.href)
    url.protocol = url.protocol === 'https:' ? 'wss:' : 'ws:'
    let socket: WebSocket
    try {
      socket = this.create(url.href)
    } catch {
      this.reconnect()
      return
    }
    this.socket = socket
    socket.binaryType = 'arraybuffer'
    socket.onopen = () => {
      if (this.socket !== socket) return
      this.attempts = 0
      for (const listener of this.listeners) listener.open()
    }
    socket.onmessage = ({ data }) => {
      if (this.socket !== socket || typeof data !== 'string') return
      let message: unknown
      try {
        message = JSON.parse(data)
      } catch {
        return
      }
      if (!message || typeof message !== 'object' || Array.isArray(message)) return
      for (const listener of this.listeners) listener.message(message as Record<string, unknown>)
    }
    // /v1/live uses WebSocket control ping/pong, handled by the browser, not JSON pings.
    socket.onerror = () => undefined
    socket.onclose = () => {
      if (this.socket !== socket) return
      this.drop('connection')
      this.reconnect()
    }
  }

  private drop(reason: 'connection' | 'backgrounded'): void {
    clearTimeout(this.retry)
    const socket = this.socket
    this.socket = null
    if (socket) {
      socket.onopen = socket.onmessage = socket.onclose = socket.onerror = null
      if (socket.readyState <= WebSocket.OPEN) socket.close()
    }
    for (const listener of this.listeners) listener.close(reason)
  }

  private reconnect(): void {
    if (!this.started || this.page.visibilityState !== 'visible') return
    const base = Math.min(10_000, 500 * 2 ** this.attempts++)
    this.retry = setTimeout(() => this.connect(), base * (0.8 + 0.4 * Math.random()))
  }
}

export const live = new LiveSocket()
