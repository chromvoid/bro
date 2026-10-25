import { flushPromises } from '@vue/test-utils'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { AccountEvents } from './accountEvents'
import type { EventStream, StreamOptions } from './events'

function page() {
  const view = new EventTarget()
  const target = new EventTarget()
  let visibilityState = 'visible'
  return {
    document: Object.assign(target, {
      get visibilityState() {
        return visibilityState
      },
      defaultView: view,
    }) as unknown as Document,
    visibility(state: string) {
      visibilityState = state
      Object.defineProperty(target, 'visibilityState', { value: state, configurable: true })
      target.dispatchEvent(new Event('visibilitychange'))
    },
    show: () => view.dispatchEvent(new Event('pageshow')),
  }
}

function channels() {
  const peers = new Set<ReturnType<typeof create>>()
  function create() {
    const channel = {
      onmessage: null as ((event: MessageEvent) => void) | null,
      postMessage(data: unknown) {
        for (const peer of peers)
          if (peer !== channel) peer.onmessage?.(new MessageEvent('message', { data }))
      },
      close() {
        peers.delete(channel)
      },
    }
    peers.add(channel)
    return channel
  }
  return create
}

function locks() {
  let busy = false
  const queue: Array<{ signal: AbortSignal; callback: () => Promise<void>; resolve: () => void }> =
    []
  const next = () => {
    if (busy) return
    const task = queue.shift()
    if (!task) return
    if (task.signal.aborted) {
      task.resolve()
      next()
      return
    }
    busy = true
    void task.callback().finally(() => {
      busy = false
      task.resolve()
      next()
    })
  }
  return {
    request: (_name: string, options: { signal: AbortSignal }, callback: () => Promise<void>) =>
      new Promise<void>((resolve) => {
        queue.push({ signal: options.signal, callback, resolve })
        next()
      }),
  } as unknown as LockManager
}

describe('account SSE coordination', () => {
  const opened: AccountEvents[] = []
  afterEach(() => {
    opened.splice(0).forEach((stream) => stream.stop())
    vi.useRealTimers()
  })
  function tab(
    sharedLocks: LockManager | null,
    channel: ReturnType<ReturnType<typeof channels>> | null,
  ) {
    const visible = page()
    const streams: Array<StreamOptions & { stop: ReturnType<typeof vi.fn> }> = []
    const onChange = vi.fn(),
      onResync = vi.fn(),
      onDenied = vi.fn()
    const cursor = { load: vi.fn(async () => null), save: vi.fn(async () => {}) }
    const coordinator = new AccountEvents({
      userId: 'synthetic-user',
      page: visible.document,
      locks: sharedLocks,
      channel,
      cursor,
      onChange,
      onResync,
      onDenied,
      makeStream(options) {
        const stop = vi.fn()
        streams.push({ ...options, stop })
        return { start: vi.fn(), stop, settled: async () => {} } as unknown as EventStream
      },
    })
    opened.push(coordinator)
    coordinator.start()
    return { coordinator, visible, streams, onChange, onResync, onDenied, cursor }
  }

  it('holds one stream, fans out events and resyncs joining, returning and promoted tabs', async () => {
    const channel = channels(),
      lock = locks()
    const first = tab(lock, channel()),
      second = tab(lock, channel())
    await flushPromises()
    expect(first.streams).toHaveLength(1)
    expect(second.streams).toHaveLength(0)
    const event = {
      id: 10,
      name: 'change',
      topic: 'assistant.changed',
      data: { state: 'processing' },
    }
    first.streams[0]!.onChange(event)
    expect(second.onChange).toHaveBeenCalledWith(event)
    const joined = second.onResync.mock.calls.length
    second.visible.visibility('hidden')
    first.streams[0]!.onChange({ ...event, id: 11 })
    expect(second.onChange).toHaveBeenCalledTimes(1)
    second.visible.visibility('visible')
    expect(second.onResync.mock.calls.length).toBeGreaterThan(joined)
    const returned = second.onResync.mock.calls.length
    second.visible.show()
    expect(second.onResync.mock.calls.length).toBeGreaterThan(returned)
    first.coordinator.stop()
    await flushPromises()
    expect(first.streams[0]!.stop).toHaveBeenCalled()
    expect(second.streams).toHaveLength(1)
    const changed = second.onChange.mock.calls.length
    // A callback from the old leader cannot mutate either account projection.
    first.streams[0]!.onChange({ ...event, id: 99 })
    first.streams[0]!.onDenied(401)
    expect(second.onChange).toHaveBeenCalledTimes(changed)
    expect(first.onDenied).not.toHaveBeenCalled()
  })

  it('uses visible-only reads when locking or broadcasting is unavailable', () => {
    vi.useFakeTimers()
    const h = tab(null, null)
    expect(h.streams).toHaveLength(0)
    const initial = h.onResync.mock.calls.length
    vi.advanceTimersByTime(30_000)
    expect(h.onResync).toHaveBeenCalledTimes(initial + 2)
    h.visible.visibility('hidden')
    vi.advanceTimersByTime(60_000)
    expect(h.onResync).toHaveBeenCalledTimes(initial + 2)
    h.visible.visibility('visible')
    expect(h.onResync).toHaveBeenCalledTimes(initial + 3)
  })

  it('continues invalidation and uses an in-memory cursor when IndexedDB fails', async () => {
    const h = tab(locks(), channels()())
    h.cursor.load.mockRejectedValue(new Error('storage unavailable'))
    h.cursor.save.mockRejectedValue(new Error('storage unavailable'))
    const stream = h.streams[0]!
    expect(await stream.cursor.load()).toBeNull()
    await stream.cursor.save(7)
    expect(await stream.cursor.load()).toBe(7)
    stream.onChange({ id: 8, name: 'change', topic: 'nutrition.changed', data: {} })
    expect(h.onChange).toHaveBeenCalledOnce()
  })

  it('clears follower progress on disconnect and accepts events after an authoritative cursor reset', () => {
    const channel = channels(),
      lock = locks()
    const first = tab(lock, channel()),
      second = tab(lock, channel())
    const listener = vi.fn()
    second.coordinator.subscribe(listener)
    first.streams[0]!.onChange({ id: 100, name: 'change', topic: 'assistant.changed', data: {} })
    first.streams[0]!.onConnection?.(false)
    expect(listener.mock.calls.at(-1)?.[0].name).toBe('resync')
    first.streams[0]!.onResync(0)
    first.streams[0]!.onChange({ id: 1, name: 'change', topic: 'nutrition.changed', data: {} })
    expect(second.onChange.mock.calls.at(-1)?.[0].id).toBe(1)
  })

  it('rechecks a relayed denial instead of signing out a follower with a newer cookie session', async () => {
    const channel = channels(),
      lock = locks()
    const first = tab(lock, channel()),
      second = tab(lock, channel())
    const resyncs = second.onResync.mock.calls.length
    first.streams[0]!.onDenied(401)
    await flushPromises()
    expect(first.onDenied).toHaveBeenCalledWith(401)
    expect(second.onDenied).not.toHaveBeenCalled()
    expect(second.onResync.mock.calls.length).toBeGreaterThan(resyncs)
    expect(second.streams).toHaveLength(1)
  })
})
