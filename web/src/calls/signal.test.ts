import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { SignalSocket, signalUrl, type SignalHandlers } from './signal'

class FakeSocket {
  readyState = 0
  sent: string[] = []
  onopen: (() => void) | null = null
  onmessage: ((event: { data: unknown }) => void) | null = null
  onclose: ((event: { code: number }) => void) | null = null
  onerror: (() => void) | null = null
  close = vi.fn(() => {
    this.readyState = 3
  })
  send(data: string) {
    this.sent.push(data)
  }
  open() {
    this.readyState = 1
    this.onopen?.()
  }
  receive(data: unknown) {
    this.onmessage?.({ data: typeof data === 'string' ? data : JSON.stringify(data) })
  }
  closed(code: number) {
    this.readyState = 3
    this.onclose?.({ code })
  }
}

function connect(random = 0.5) {
  const sockets: FakeSocket[] = []
  const handlers = { open: vi.fn(), frame: vi.fn(), final: vi.fn() } satisfies SignalHandlers
  const signal = new SignalSocket('wss://a.example/v1/calls/c1/signal', handlers, {
    socket: () => {
      const socket = new FakeSocket()
      sockets.push(socket)
      return socket as unknown as WebSocket
    },
    random: () => random,
  })
  const last = () => sockets.at(-1)!
  return { signal, sockets, handlers, last }
}

beforeEach(() => vi.useFakeTimers())
afterEach(() => vi.useRealTimers())

describe('signalUrl', () => {
  it('follows the page scheme', () => {
    expect(signalUrl({ href: 'https://a.example/circle/chat/x' }, 'c 1')).toBe(
      'wss://a.example/v1/calls/c%201/signal',
    )
    expect(signalUrl({ href: 'http://127.0.0.1:5173/' }, 'c1')).toBe(
      'ws://127.0.0.1:5173/v1/calls/c1/signal',
    )
  })
})

describe('SignalSocket', () => {
  it('pings every 15 s while frames keep arriving', () => {
    const { last, handlers } = connect()
    last().open()
    expect(handlers.open).toHaveBeenCalledOnce()
    vi.advanceTimersByTime(15_000)
    last().receive({ type: 'pong' })
    vi.advanceTimersByTime(15_000)
    last().receive({ type: 'pong' })
    expect(last().sent).toEqual([
      JSON.stringify({ type: 'ping' }),
      JSON.stringify({ type: 'ping' }),
    ])
    expect(handlers.frame).toHaveBeenCalledTimes(2)
  })

  it('reconnects after 25 s of silence', () => {
    const { sockets, last, handlers } = connect()
    last().open()
    vi.advanceTimersByTime(24_999)
    expect(sockets).toHaveLength(1)
    vi.advanceTimersByTime(1)
    expect(sockets[0]!.close).toHaveBeenCalled()
    vi.advanceTimersByTime(500)
    expect(sockets).toHaveLength(2)
    last().open()
    expect(handlers.open).toHaveBeenCalledTimes(2)
  })

  it('backs off from 0.5 s to a 10 s cap and starts over after a connection', () => {
    const { sockets, last } = connect()
    const delays: number[] = []
    for (let attempt = 0; attempt < 7; attempt += 1) {
      last().closed(1006)
      const before = sockets.length
      let waited = 0
      while (sockets.length === before) {
        vi.advanceTimersByTime(100)
        waited += 100
      }
      delays.push(waited)
    }
    expect(delays).toEqual([500, 1000, 2000, 4000, 8000, 10_000, 10_000])
    last().open()
    last().closed(1001)
    vi.advanceTimersByTime(500)
    expect(sockets).toHaveLength(9)
  })

  it('jitters each delay by ±20 %', () => {
    const low = connect(0)
    low.last().closed(4008)
    vi.advanceTimersByTime(399)
    expect(low.sockets).toHaveLength(1)
    vi.advanceTimersByTime(1)
    expect(low.sockets).toHaveLength(2)
    const high = connect(1)
    high.last().closed(1006)
    vi.advanceTimersByTime(599)
    expect(high.sockets).toHaveLength(1)
    vi.advanceTimersByTime(1)
    expect(high.sockets).toHaveLength(2)
  })

  it.each([4000, 4001] as const)('treats %i as final', (code) => {
    const { sockets, last, handlers } = connect()
    last().open()
    last().closed(code)
    vi.advanceTimersByTime(60_000)
    expect(handlers.final).toHaveBeenCalledWith(code)
    expect(sockets).toHaveLength(1)
  })

  it('ignores frames that are not valid JSON frames', () => {
    const { last, handlers } = connect()
    last().open()
    last().receive('not json')
    last().receive({ type: 'candidate', candidate: 'a=candidate:1' })
    last().receive({ type: 'state', state: 'ringing' })
    last().onmessage?.({ data: new ArrayBuffer(4) })
    expect(handlers.frame).not.toHaveBeenCalled()
    last().receive({ type: 'peer', present: true })
    expect(handlers.frame).toHaveBeenCalledWith({ type: 'peer', present: true })
  })

  it('sends only while open and stops for good on close()', () => {
    const { signal, sockets, last } = connect()
    expect(signal.send({ type: 'ping' })).toBe(false)
    last().open()
    expect(signal.send({ type: 'offer', sdp: 'v=0\r\n' })).toBe(true)
    expect(last().sent).toEqual([JSON.stringify({ type: 'offer', sdp: 'v=0\r\n' })])
    signal.close()
    expect(last().close).toHaveBeenCalled()
    vi.advanceTimersByTime(60_000)
    expect(sockets).toHaveLength(1)
    expect(signal.send({ type: 'ping' })).toBe(false)
  })
})
