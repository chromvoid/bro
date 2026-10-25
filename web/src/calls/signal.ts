/**
 * The call's signalling socket (/v1/calls/{id}/signal). It carries the two descriptions and the
 * call state; it reconnects on its own until the call ends (4001) or another socket of this
 * session replaces it (4000).
 */
import { ServerFrame, type ClientFrame } from './api'
import type { Timers } from './network'

export const PING_MS = 15_000
export const SILENCE_MS = 25_000
export const BACKOFF_MS = 500
export const BACKOFF_CAP_MS = 10_000
const OPEN = 1

export function signalUrl(location: { href: string }, callId: string): string {
  const url = new URL(`/v1/calls/${encodeURIComponent(callId)}/signal`, location.href)
  url.protocol = url.protocol === 'https:' ? 'wss:' : 'ws:'
  return url.href
}

export interface SignalHandlers {
  /** Every successful connection, the first one included. */
  open(): void
  frame(frame: ServerFrame): void
  /** The server will not take this socket back: the call ended or another socket replaced it. */
  final(code: 4000 | 4001): void
}

export interface SignalDeps extends Partial<Timers> {
  socket?: (url: string) => WebSocket
  random?: () => number
}

export class SignalSocket {
  private socket: WebSocket | null = null
  private closed = false
  private attempts = 0
  private retry: ReturnType<typeof setTimeout> | undefined
  private ping: ReturnType<typeof setTimeout> | undefined
  private silence: ReturnType<typeof setTimeout> | undefined
  private readonly timers: Timers
  private readonly random: () => number
  private readonly create: (url: string) => WebSocket

  constructor(
    private readonly url: string,
    private readonly handlers: SignalHandlers,
    deps: SignalDeps = {},
  ) {
    this.timers = {
      setTimeout: deps.setTimeout ?? ((handler, ms) => setTimeout(handler, ms)),
      clearTimeout: deps.clearTimeout ?? ((timer) => clearTimeout(timer)),
    }
    this.random = deps.random ?? Math.random
    this.create = deps.socket ?? ((address) => new WebSocket(address))
    this.connect()
  }

  get connected(): boolean {
    return this.socket?.readyState === OPEN
  }

  /** False when the socket is not open: the frame is not queued. */
  send(frame: ClientFrame): boolean {
    if (!this.socket || this.socket.readyState !== OPEN) return false
    this.socket.send(JSON.stringify(frame))
    return true
  }

  /** Final: no reconnect and no handler call. */
  close(): void {
    if (this.closed) return
    this.closed = true
    this.drop()
  }

  private connect(): void {
    if (this.closed) return
    const socket = this.create(this.url)
    this.socket = socket
    // A handshake that hangs counts as silence too.
    this.alive()
    socket.onopen = () => {
      if (this.socket !== socket) return
      this.attempts = 0
      this.alive()
      this.schedulePing()
      this.handlers.open()
    }
    socket.onmessage = (event: MessageEvent) => {
      if (this.socket !== socket) return
      this.alive()
      if (typeof event.data !== 'string') return
      let value: unknown
      try {
        value = JSON.parse(event.data)
      } catch {
        return
      }
      const frame = ServerFrame.safeParse(value)
      if (frame.success) this.handlers.frame(frame.data)
    }
    socket.onclose = (event: CloseEvent) => {
      if (this.socket !== socket) return
      this.drop()
      if (event.code === 4000 || event.code === 4001) {
        this.closed = true
        this.handlers.final(event.code)
      } else this.reconnect()
    }
  }

  /** Forgets the current socket and its timers; its late events are ignored. */
  private drop(): void {
    this.timers.clearTimeout(this.retry)
    this.timers.clearTimeout(this.ping)
    this.timers.clearTimeout(this.silence)
    const socket = this.socket
    this.socket = null
    if (!socket) return
    socket.onopen = socket.onmessage = socket.onclose = socket.onerror = null
    if (socket.readyState <= OPEN) socket.close()
  }

  private reconnect(): void {
    if (this.closed) return
    const base = Math.min(BACKOFF_CAP_MS, BACKOFF_MS * 2 ** this.attempts)
    this.attempts += 1
    const delay = base * (0.8 + 0.4 * this.random())
    this.retry = this.timers.setTimeout(() => this.connect(), delay)
  }

  private schedulePing(): void {
    this.ping = this.timers.setTimeout(() => {
      this.send({ type: 'ping' })
      this.schedulePing()
    }, PING_MS)
  }

  /** A connection that stays silent for 25 s (the server answers every ping) is dead. */
  private alive(): void {
    this.timers.clearTimeout(this.silence)
    this.silence = this.timers.setTimeout(() => {
      this.drop()
      this.reconnect()
    }, SILENCE_MS)
  }
}
