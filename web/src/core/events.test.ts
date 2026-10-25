import { describe, expect, it, vi } from 'vitest'
import { EventStream, SseParser, followVisibility, type ChangeEvent } from './events'

describe('SseParser', () => {
  it('assembles events across chunk boundaries and line endings', () => {
    const parser = new SseParser()
    expect(parser.push('id: 7\nevent: assistant.changed\nda')).toEqual([])
    expect(parser.push('ta: {"topic":"tasks.changed"}\r')).toEqual([])
    expect(parser.push('\n\r\n: heartbeat\n\n')).toEqual([
      { id: '7', event: 'assistant.changed', data: '{"topic":"tasks.changed"}' },
    ])
    expect(parser.push('data: a\ndata: b\n\n')).toEqual([
      { id: '7', event: 'message', data: 'a\nb' },
    ])
  })
})

/** A response whose body the test writes to and closes. */
function streamed() {
  let controller!: ReadableStreamDefaultController<Uint8Array>
  const cancelled = vi.fn()
  const body = new ReadableStream<Uint8Array>({
    start: (value) => void (controller = value),
    cancel: cancelled,
  })
  const encoder = new TextEncoder()
  return {
    response: new Response(body, { status: 200, headers: { 'Content-Type': 'text/event-stream' } }),
    send: (text: string) => controller.enqueue(encoder.encode(text)),
    end: () => controller.close(),
    body,
    cancelled,
  }
}

function harness(...replies: Array<Response | (() => Response)>) {
  const requests: Array<{ url: string; cursor: string | null }> = []
  const fetcher = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
    requests.push({ url: String(input), cursor: new Headers(init?.headers).get('Last-Event-ID') })
    const next = replies.shift()
    // Like fetch, a pending request rejects when its signal aborts.
    if (!next)
      return new Promise<Response>((_, reject) =>
        init?.signal?.addEventListener('abort', () => reject(init.signal?.reason)),
      )
    return typeof next === 'function' ? next() : next
  })
  let saved: number | null = null
  const changes: ChangeEvent[] = []
  const log: string[] = []
  const delays: number[] = []
  const stream = new EventStream({
    url: '/v1/me/events',
    cursor: {
      load: async () => saved,
      save: async (value) => {
        log.push(`save ${value}`)
        saved = value
      },
    },
    onChange: (event) => {
      log.push(`change ${event.id}`)
      changes.push(event)
    },
    onResync: () => log.push('resync'),
    onDenied: (status) => log.push(`denied ${status}`),
    fetcher: fetcher as unknown as typeof fetch,
    random: () => 1,
    wait: async (ms) => void delays.push(ms),
  })
  return { stream, requests, changes, log, delays, cursor: () => saved }
}

const tick = () => new Promise((resolve) => setTimeout(resolve, 5))

describe('EventStream', () => {
  it.each(['cursor.save', 'onChange', 'onResync'])(
    'closes and unlocks the previous response before reconnecting after %s fails',
    async (fault) => {
      const first = streamed(),
        second = streamed()
      let saved: number | null = null
      let fail = true
      const failOnce = () => {
        if (fail) {
          fail = false
          throw new Error('Synthetic observer failure')
        }
      }
      const requests: Array<RequestInit | undefined> = []
      const stream = new EventStream({
        url: '/v1/me/events',
        cursor: {
          load: async () => saved,
          save: async (id) => {
            if (fault === 'cursor.save') failOnce()
            saved = id
          },
        },
        onChange: () => {
          if (fault === 'onChange') failOnce()
        },
        onResync: () => {
          if (fault === 'onResync') failOnce()
        },
        onDenied: vi.fn(),
        fetcher: async (_url, init) => {
          requests.push(init)
          if (requests.length === 1) return first.response
          expect(first.cancelled).toHaveBeenCalledOnce()
          expect(first.body.locked).toBe(false)
          return second.response
        },
        wait: async () => {},
      })
      const frame = `id: 7\nevent: ${fault === 'onResync' ? 'resync' : 'change'}\ndata: {"topic":"assistant.changed"}\n\n`
      stream.start()
      await tick()
      first.send(frame)
      await tick()
      expect(requests).toHaveLength(2)
      expect(saved).toBeNull()
      expect(new Headers(requests[1]?.headers).get('Last-Event-ID')).toBeNull()
      second.send(frame)
      await tick()
      expect(saved).toBe(7)
      stream.stop()
      await stream.settled()
      expect(second.cancelled).toHaveBeenCalledOnce()
      expect(second.body.locked).toBe(false)
    },
  )

  it('cancels a rejected response body before another connection attempt', async () => {
    const rejected = streamed()
    const { stream, requests } = harness(new Response(rejected.body, { status: 503 }))
    stream.start()
    await tick()
    expect(rejected.cancelled).toHaveBeenCalledOnce()
    expect(requests).toHaveLength(2)
    stream.stop()
    await stream.settled()
  })

  it('shares progress once, signals resync and removes subscribers without affecting replay', async () => {
    const first = streamed()
    const { stream, changes, cursor } = harness(first.response)
    const progress: ChangeEvent[] = []
    const unsubscribe = stream.subscribe((event) => progress.push(event))
    stream.subscribe(() => {
      throw new Error('Progress view failed')
    })
    stream.start()
    await tick()
    first.send('id: 3\nevent: resync\ndata: {}\n\n')
    first.send(
      'id: 4\nevent: assistant.changed\ndata: {"topic":"assistant.changed","stage":"tool","tool":"nutrition.search"}\n\n',
    )
    first.send('id: 4\nevent: assistant.changed\ndata: {"topic":"assistant.changed"}\n\n')
    await tick()
    expect(progress.map((event) => event.id)).toEqual([3, 4])
    expect(progress[0]?.name).toBe('resync')
    expect(progress[1]?.data).toMatchObject({ stage: 'tool', tool: 'nutrition.search' })
    expect(cursor()).toBe(4)
    unsubscribe()
    first.send('id: 5\nevent: assistant.changed\ndata: {"topic":"assistant.changed"}\n\n')
    await tick()
    expect(progress).toHaveLength(2)
    expect(changes.map((event) => event.id)).toEqual([4, 5])
    expect(cursor()).toBe(5)
    first.end()
    await tick()
    stream.stop()
    await stream.settled()
  })

  it('starts at the current position, then resumes from the saved cursor', async () => {
    const first = streamed()
    const { stream, requests, changes, log, cursor } = harness(first.response)
    stream.start()
    await tick()
    first.send('id: 40\nevent: resync\ndata: {}\n\n')
    first.send('id: 41\nevent: assistant.changed\ndata: {"topic":"nutrition.changed"}\n\n')
    first.send('id: 41\nevent: assistant.changed\ndata: {"topic":"nutrition.changed"}\n\n')
    await tick()
    expect(requests[0]).toEqual({ url: '/v1/me/events?cursor=latest', cursor: null })
    expect(log).toEqual(['resync', 'save 40', 'change 41', 'save 41'])
    expect(changes[0]?.topic).toBe('nutrition.changed')
    first.end()
    await tick()
    expect(requests[1]).toEqual({ url: '/v1/me/events', cursor: '41' })
    expect(cursor()).toBe(41)
    stream.stop()
    await stream.settled()
  })

  it('stops for good when the session or its access ends', async () => {
    const { stream, requests, log } = harness(new Response(null, { status: 401 }))
    stream.start()
    await stream.settled()
    expect(log).toEqual(['denied 401'])
    expect(requests).toHaveLength(1)
    expect(stream.active).toBe(false)
  })

  it('backs off exponentially up to thirty seconds while the server is unavailable', async () => {
    const busy = () => new Response(null, { status: 503 })
    const { stream, delays } = harness(...Array.from({ length: 7 }, () => busy))
    stream.start()
    await tick()
    expect(delays.slice(0, 7)).toEqual([1000, 2000, 4000, 8000, 16000, 30000, 30000])
    stream.stop()
  })
})

describe('followVisibility', () => {
  it('runs only while the page is visible, with a grace period when hidden', () => {
    vi.useFakeTimers()
    const stream = { start: vi.fn(), stop: vi.fn() } as unknown as EventStream
    let state: DocumentVisibilityState = 'visible'
    Object.defineProperty(document, 'visibilityState', { configurable: true, get: () => state })
    const dispose = followVisibility(stream, document, 30_000)
    expect(stream.start).toHaveBeenCalledOnce()
    state = 'hidden'
    document.dispatchEvent(new Event('visibilitychange'))
    vi.advanceTimersByTime(29_000)
    expect(stream.stop).not.toHaveBeenCalled()
    state = 'visible'
    document.dispatchEvent(new Event('visibilitychange'))
    vi.advanceTimersByTime(60_000)
    expect(stream.stop).not.toHaveBeenCalled()
    window.dispatchEvent(new Event('pagehide'))
    expect(stream.stop).toHaveBeenCalledOnce()
    dispose()
    vi.useRealTimers()
  })
})

describe('connection generations and deadlines', () => {
  it('ignores a late denied response from the previous generation and cancels its body', async () => {
    let resolve!: (value: Response) => void
    const old = streamed(),
      fresh = streamed()
    const denied = vi.fn(),
      connection = vi.fn(),
      change = vi.fn()
    const fetcher = vi
      .fn<typeof fetch>()
      .mockImplementationOnce(
        () =>
          new Promise((done) => {
            resolve = done
          }),
      )
      .mockResolvedValueOnce(fresh.response)
    const stream = new EventStream({
      url: '/events',
      cursor: { load: async () => null, save: async () => {} },
      onChange: change,
      onDenied: denied,
      onResync: vi.fn(),
      onConnection: connection,
      fetcher,
    })
    stream.start()
    await tick()
    stream.stop()
    stream.start()
    await tick()
    resolve(new Response(old.body, { status: 401 }))
    await tick()
    fresh.send('id: 1\ndata: {"topic":"tasks.changed"}\n\n')
    await tick()
    expect(denied).not.toHaveBeenCalled()
    expect(old.cancelled).toHaveBeenCalledOnce()
    expect(connection.mock.calls).toEqual([[true]])
    expect(change).toHaveBeenCalledOnce()
    expect(stream.active).toBe(true)
    stream.stop()
    await stream.settled()
  })

  it('does not apply the rest of an old chunk after a pending cursor save completes', async () => {
    const old = streamed(),
      fresh = streamed()
    let resolve!: () => void
    const change = vi.fn()
    const stream = new EventStream({
      url: '/events',
      cursor: {
        load: async () => null,
        save: () =>
          new Promise<void>((done) => {
            resolve = done
          }),
      },
      onChange: change,
      onDenied: vi.fn(),
      onResync: vi.fn(),
      fetcher: vi
        .fn<typeof fetch>()
        .mockResolvedValueOnce(old.response)
        .mockResolvedValueOnce(fresh.response),
    })
    stream.start()
    await tick()
    old.send('id: 1\ndata: {}\n\nid: 2\ndata: {}\n\n')
    await tick()
    stream.stop()
    stream.start()
    resolve()
    await tick()
    expect(change).toHaveBeenCalledOnce()
    stream.stop()
    await stream.settled()
  })

  it('reconnects after a header or heartbeat deadline without allowing late responses through', async () => {
    const fresh = streamed()
    const fetcher = vi
      .fn<typeof fetch>()
      .mockImplementation(() => new Promise(() => {}))
      .mockImplementationOnce(() => new Promise(() => {}))
      .mockResolvedValueOnce(fresh.response)
    const stream = new EventStream({
      url: '/events',
      headerMs: 10,
      idleMs: 10,
      cursor: { load: async () => null, save: async () => {} },
      onChange: vi.fn(),
      onDenied: vi.fn(),
      onResync: vi.fn(),
      fetcher,
      wait: async () => {},
    })
    stream.start()
    await new Promise((resolve) => setTimeout(resolve, 35))
    expect(fetcher.mock.calls.length).toBeGreaterThanOrEqual(2)
    expect(fresh.cancelled).toHaveBeenCalledOnce()
    stream.stop()
    await stream.settled()
  })
})
