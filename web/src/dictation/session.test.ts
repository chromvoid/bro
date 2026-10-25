import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { LiveSocket } from '@/core/live'
import { MicrophoneError, type AudioSource, type CaptureHandlers } from './audio'
import {
  DictationSession,
  type DictationOptions,
  type DictationProblem,
  type DictationState,
  type DictationFailure,
} from './session'

class FakeSocket {
  static readonly OPEN = 1
  readyState = 0
  bufferedAmount = 0
  binaryType = 'blob'
  sent: Array<string | ArrayBuffer> = []
  closed = false
  onopen: (() => void) | null = null
  onmessage: ((event: { data: unknown }) => void) | null = null
  onclose: (() => void) | null = null
  onerror: (() => void) | null = null

  send(value: string | ArrayBuffer) {
    this.sent.push(value)
  }
  close() {
    this.closed = true
    this.readyState = 3
  }
  get commands(): Array<{ type: string; id: string }> {
    return this.sent.filter((value) => typeof value === 'string').map((value) => JSON.parse(value))
  }
  get id(): string {
    return this.commands.findLast((message) => message.type === 'dictation.start')!.id
  }
  open() {
    this.readyState = 1
    this.onopen?.()
    this.onmessage?.({ data: JSON.stringify({ type: 'hello' }) })
  }
  /** The server side of the conversation. */
  server(message: object) {
    if (this.readyState === 0) this.open()
    this.onmessage?.({ data: JSON.stringify({ id: this.id, ...message }) })
  }
  drop() {
    this.readyState = 3
    this.onclose?.()
  }
}

function fakeSource(problem?: MicrophoneError) {
  let handlers: CaptureHandlers | null = null
  const source = {
    released: 0,
    stopped: 0,
    prepare: vi.fn(async () => {
      if (problem) throw problem
    }),
    begin: vi.fn((value: CaptureHandlers) => void (handlers = value)),
    stop: vi.fn(async () => {
      source.stopped++
      handlers?.frame(new ArrayBuffer(256))
    }),
    release: vi.fn(() => void source.released++),
    emit: (bytes = 2560) => handlers?.frame(new ArrayBuffer(bytes)),
    limit: () => handlers?.limit(),
  }
  return source
}

const connections: LiveSocket[] = []

function transport() {
  const sockets: FakeSocket[] = []
  const create = vi.fn(() => {
    const socket = new FakeSocket()
    sockets.push(socket)
    return socket as unknown as WebSocket
  })
  const live = new LiveSocket(document, create)
  connections.push(live)
  live.start()
  return { live, sockets, create }
}

function setup(
  problem?: MicrophoneError,
  options: DictationOptions = {},
  connection = transport(),
) {
  const socket = connection.sockets.at(-1)!
  const source = fakeSource(problem)
  const states: Array<[DictationState, DictationProblem | undefined]> = []
  const failures: Array<DictationFailure | undefined> = []
  const partials: string[] = []
  const finals: Array<{ text: string; transcriptionId: string }> = []
  const session = new DictationSession(
    source as unknown as AudioSource,
    {
      state: (state, reason, detail) => {
        states.push([state, reason])
        failures.push(detail)
      },
      partial: (text) => partials.push(text),
      final: (result) => finals.push(result),
    },
    {
      live: connection.live,
      ...options,
    },
  )
  return { socket, source, session, states, partials, finals, failures, connection }
}

describe('DictationSession', () => {
  beforeEach(() => {
    vi.useFakeTimers()
    vi.stubGlobal('WebSocket', FakeSocket)
    vi.spyOn(document, 'visibilityState', 'get').mockReturnValue('visible')
  })
  afterEach(() => {
    for (const live of connections.splice(0)) live.stop()
    vi.restoreAllMocks()
    vi.useRealTimers()
    vi.unstubAllGlobals()
  })

  it('captures during readiness and reports a failed check with its action', async () => {
    let answer!: (value: { asr: { state: 'unavailable'; code: string; action: string } }) => void
    const preflight = vi.fn(
      () =>
        new Promise<{
          asr: { state: 'unavailable'; code: string; action: string }
        }>((resolve) => (answer = resolve)),
    )
    const { session, source, socket, states, failures } = setup(undefined, { preflight })
    await session.start()
    expect(source.begin).toHaveBeenCalledOnce()
    expect(socket.binaryType).toBe('arraybuffer')
    source.emit()
    expect(socket.sent).toEqual([])
    socket.server({ type: 'dictation.ready' })
    expect(socket.sent).toHaveLength(2)
    answer({ asr: { state: 'unavailable', code: 'not_configured', action: 'settings' } })
    await Promise.resolve()
    expect(states.at(-1)).toEqual(['failed', 'not_configured'])
    expect(source.release).toHaveBeenCalled()
    expect(socket.closed).toBe(false)
    expect(failures.at(-1)?.action).toBe('settings')
  })

  it('retains partial text and the login action when the session expires while recording', async () => {
    const { session, source, socket, states, partials, failures } = setup()
    await session.start()
    socket.server({ type: 'dictation.ready', sample_rate: 16000 })
    socket.server({ type: 'dictation.partial', text: 'Сохранить этот текст' })
    socket.server({ type: 'dictation.error', error: 'session_expired', action: 'login' })
    expect(states.at(-1)).toEqual(['failed', 'session_expired'])
    expect(failures.at(-1)?.action).toBe('login')
    expect(partials).toEqual(['Сохранить этот текст'])
    expect(source.release).toHaveBeenCalled()
    expect(socket.closed).toBe(false)
  })

  it('ignores a pending readiness check after cancelling capture', async () => {
    let answer!: (value: { asr: { state: 'configured' } }) => void
    const preflight = vi.fn(
      () =>
        new Promise<{ asr: { state: 'configured' } }>((resolve) => {
          answer = resolve
        }),
    )
    const { session, source, states } = setup(undefined, { preflight })
    const starting = session.start()
    session.cancel()
    answer({ asr: { state: 'configured' } })
    await starting
    expect(source.prepare).toHaveBeenCalledOnce()
    expect(source.begin).not.toHaveBeenCalled()
    expect(states.at(-1)).toEqual(['cancelled', undefined])
  })

  it.each([
    'not_configured',
    'no_access',
    'auth_required',
    'application_limit',
    'provider_limit',
    'busy',
    'timeout',
    'connection',
    'provider_error',
    'invalid_audio',
    'secret_unavailable',
    'execution_disabled',
  ])('shows the specific startup failure %s before ready', async (code) => {
    const { session, source, socket, states, failures } = setup()
    await session.start()
    socket.server({
      type: 'dictation.error',
      error: code,
      action: 'settings',
      retry_after: 7.2,
      trace_id: '0123456789abcdef0123456789abcdef',
    })
    expect(states.at(-1)).toEqual(['failed', code])
    expect(source.begin).toHaveBeenCalledOnce()
    expect(source.release).toHaveBeenCalled()
    expect(failures.at(-1)).toEqual({
      action: 'settings',
      retryAfter: 8,
      traceId: '0123456789abcdef0123456789abcdef',
    })
  })

  it.each([false, true])(
    'flushes early frames in order and finalizes once when stop precedes ready: %s',
    async (stopBeforeReady) => {
      const { socket, source, session, states, partials, finals } = setup()
      await session.start()
      expect(source.begin).toHaveBeenCalledOnce()
      const first = new ArrayBuffer(12_000)
      const second = new ArrayBuffer(12_000)
      source.begin.mock.calls[0]![0].frame(first)
      source.begin.mock.calls[0]![0].frame(second)
      expect(socket.sent).toEqual([])
      if (stopBeforeReady) {
        await session.finish()
        expect(session.state).toBe('finalizing')
        expect(socket.sent).toEqual([])
      }
      socket.open()
      expect(socket.sent).toEqual([JSON.stringify({ type: 'dictation.start', id: socket.id })])
      socket.server({ type: 'dictation.ready', sample_rate: 16000 })
      const live = new ArrayBuffer(2560)
      if (!stopBeforeReady) source.begin.mock.calls[0]![0].frame(live)
      socket.server({ type: 'dictation.partial', text: 'купить' })
      socket.server({ type: 'dictation.partial', text: 'купить хлеб' })
      await session.finish()
      await session.finish()
      socket.server({ type: 'dictation.final', text: 'Купить хлеб.', transcription_id: 't1' })

      expect(socket.sent[1]).toBe(first)
      expect(socket.sent[2]).toBe(second)
      if (!stopBeforeReady) expect(socket.sent[3]).toBe(live)
      expect(
        socket.sent.map((frame) => (typeof frame === 'string' ? frame : frame.byteLength)),
      ).toEqual([
        JSON.stringify({ type: 'dictation.start', id: socket.id }),
        12_000,
        12_000,
        ...(stopBeforeReady ? [] : [2560]),
        256,
        JSON.stringify({ type: 'dictation.finish', id: socket.id }),
      ])
      expect(partials).toEqual(['купить', 'купить хлеб'])
      expect(finals).toEqual([{ text: 'Купить хлеб.', transcriptionId: 't1' }])
      expect(states.map(([state]) => state)).toEqual([
        'permission',
        'recording',
        'finalizing',
        'review',
      ])
      expect(source.stopped).toBe(1)
      expect(source.released).toBeGreaterThan(0)
      expect(socket.closed).toBe(false)
    },
  )

  it('reports overflow instead of dropping audio beyond ten seconds before ready', async () => {
    const { socket, source, session, states } = setup()
    await session.start()
    for (let second = 0; second < 10; second++) source.emit(32_000)
    expect(session.state).toBe('recording')
    expect(socket.sent).toEqual([])
    source.emit(2)
    expect(states.at(-1)).toEqual(['interrupted', 'connection'])
    expect(source.release).toHaveBeenCalled()
    expect(socket.closed).toBe(false)
    socket.open()
    expect(socket.sent).toEqual([])
  })

  it.each([false, true])('silently cancels a short capture with ready: %s', async (ready) => {
    const { socket, source, session, states, finals } = setup()
    await session.start()
    if (ready) socket.server({ type: 'dictation.ready' })
    source.emit(22_142)
    await session.finish()
    expect(states.at(-1)).toEqual(['cancelled', undefined])
    expect(socket.commands).toEqual(
      ready
        ? [
            { type: 'dictation.start', id: socket.id },
            { type: 'dictation.cancel', id: socket.id },
          ]
        : [],
    )
    expect(finals).toEqual([])
    expect(socket.closed).toBe(false)
  })

  it('reports a refused microphone without starting a dictation on a connecting socket', async () => {
    const { session, states, socket } = setup(new MicrophoneError('denied'))
    await session.start()
    expect(states.at(-1)).toEqual(['failed', 'denied'])
    expect(socket.sent).toEqual([])
  })

  it('releases a microphone granted after cancel without starting a dictation', async () => {
    const { session, source, socket, states } = setup()
    let grant = () => {}
    source.prepare.mockImplementationOnce(() => new Promise<void>((resolve) => (grant = resolve)))
    const started = session.start()
    session.cancel()
    grant()
    await started
    expect(states.map(([state]) => state)).toEqual(['permission', 'cancelled'])
    expect(source.released).toBe(2)
    socket.open()
    expect(socket.sent).toEqual([])
    expect(source.begin).not.toHaveBeenCalled()
  })

  it.each(['cancel', 'interrupt'] as const)(
    'ignores late microphone rejection after %s while another recording is active',
    async (ending) => {
      const previous = setup()
      let refuse!: (error: unknown) => void
      previous.source.prepare.mockImplementationOnce(
        () => new Promise<void>((_resolve, reject) => (refuse = reject)),
      )
      const starting = previous.session.start()
      previous.session[ending]()

      const current = setup()
      await current.session.start()
      current.socket.server({ type: 'dictation.ready' })
      refuse(new MicrophoneError('denied'))
      await starting

      expect(previous.states).toEqual([
        ['permission', undefined],
        ending === 'cancel' ? ['cancelled', undefined] : ['interrupted', 'backgrounded'],
      ])
      expect(previous.source.released).toBe(2)
      expect(previous.source.begin).not.toHaveBeenCalled()
      expect(previous.socket.sent).toEqual([])
      expect(current.session.state).toBe('recording')
      expect(current.source.released).toBe(0)
      current.session.cancel()
    },
  )

  it('releases the microphone when the server fails after finish', async () => {
    const { session, source, socket, states } = setup()
    await session.start()
    socket.server({ type: 'dictation.ready' })
    source.emit(24_000)
    await session.finish()
    const released = source.released
    socket.server({ type: 'dictation.error', error: 'asr_busy' })
    expect(states.at(-1)).toEqual(['failed', 'busy'])
    expect(source.released).toBeGreaterThan(released)
    expect(socket.closed).toBe(false)
  })

  it('retries a stopped recording after socket loss, preserving PCM order and waiting for drain', async () => {
    const { session, source, socket, connection, finals } = setup()
    await session.start()
    socket.server({ type: 'dictation.ready' })
    for (let index = 1; index <= 12; index++)
      source.begin.mock.calls[0]![0].frame(new Uint8Array(32_000).fill(index).buffer)
    await session.finish()
    const originalId = socket.id
    const audio = socket.sent.filter((data) => data instanceof ArrayBuffer)
    socket.drop()
    expect(session.canRetry).toBe(true)

    session.retry()
    const retrySocket = connection.sockets.at(-1)!
    vi.spyOn(retrySocket, 'send').mockImplementation((data) => {
      retrySocket.sent.push(data)
      if (data instanceof ArrayBuffer) retrySocket.bufferedAmount += data.byteLength
    })
    retrySocket.open()
    expect(retrySocket.id).not.toBe(originalId)
    expect(retrySocket.sent).toHaveLength(1)
    retrySocket.server({ type: 'dictation.ready' })
    expect(retrySocket.bufferedAmount).toBe(320_000)
    expect(retrySocket.sent).toHaveLength(11)
    expect(retrySocket.commands.at(-1)?.type).toBe('dictation.start')

    retrySocket.bufferedAmount = 0
    vi.advanceTimersByTime(50)
    expect(
      retrySocket.sent
        .filter((data) => data instanceof ArrayBuffer)
        .map((data) => new Uint8Array(data)),
    ).toEqual(audio.map((data) => new Uint8Array(data)))
    expect(retrySocket.commands.at(-1)).toEqual({
      type: 'dictation.finish',
      id: retrySocket.id,
    })
    retrySocket.server({ type: 'dictation.final', text: 'Recovered', transcription_id: 'retry' })
    expect(finals).toEqual([{ text: 'Recovered', transcriptionId: 'retry' }])
    expect(session.canRetry).toBe(false)
    session.retry()
    expect(source.prepare).toHaveBeenCalledOnce()
    expect(source.begin).toHaveBeenCalledOnce()
    expect(retrySocket.commands).toHaveLength(2)
  })

  it('treats an unexplained refused connection as a connection problem and a dropped one as an interruption', async () => {
    const refused = setup()
    await refused.session.start()
    refused.socket.drop()
    expect(refused.states.at(-1)).toEqual(['failed', 'connection'])

    const dropped = setup()
    await dropped.session.start()
    dropped.socket.server({ type: 'dictation.ready' })
    dropped.socket.drop()
    expect(dropped.states.at(-1)).toEqual(['interrupted', 'connection'])
    expect(dropped.session.canRetry).toBe(false)
    expect(dropped.source.released).toBeGreaterThan(0)
  })

  it.each([
    { type: 'dictation.error', error: 'empty_transcript' },
    { type: 'dictation.final', text: '  ' },
  ])('silently cancels an empty result: %j', async (result) => {
    const { session, socket, source, states, finals } = setup()
    await session.start()
    socket.server({ type: 'dictation.ready' })
    source.emit(24_000)
    await session.finish()
    socket.server(result)
    expect(states.at(-1)).toEqual(['cancelled', undefined])
    expect(finals).toEqual([])
    expect(socket.closed).toBe(false)
  })

  it('silently cancels zero audio and preserves the ready and final deadlines', async () => {
    const empty = setup()
    await empty.session.start()
    empty.source.stop.mockImplementationOnce(async () => undefined)
    await empty.session.finish()
    expect(empty.states.at(-1)).toEqual(['cancelled', undefined])
    expect(empty.socket.sent).toEqual([])

    const late = setup()
    await late.session.start()
    late.source.emit(24_000)
    await late.session.finish()
    vi.advanceTimersByTime(35_000)
    expect(late.states.at(-1)).toEqual(['failed', 'timeout'])

    const slow = setup()
    await slow.session.start()
    slow.socket.server({ type: 'dictation.ready' })
    slow.source.emit(24_000)
    await slow.session.finish()
    vi.advanceTimersByTime(65_000)
    expect(slow.states.at(-1)).toEqual(['interrupted', 'timeout'])
  })

  it('ends a stalled upload instead of dropping audio', async () => {
    const { socket, source, session, states } = setup()
    await session.start()
    socket.server({ type: 'dictation.ready' })
    socket.bufferedAmount = 400_000
    source.emit()
    expect(states.at(-1)).toEqual(['interrupted', 'connection'])
  })

  it('stops at the two-minute limit and still finalizes', async () => {
    const { socket, source, session, states } = setup()
    await session.start()
    socket.server({ type: 'dictation.ready' })
    source.emit(32_000)
    source.limit()
    await vi.advanceTimersByTimeAsync(0)
    expect(states.some(([, reason]) => reason === 'limit')).toBe(true)
    expect(session.state).toBe('finalizing')
    expect(socket.commands.at(-1)).toEqual({ type: 'dictation.finish', id: socket.id })
  })

  it('cancels and interrupts without resuming', async () => {
    const cancelled = setup()
    await cancelled.session.start()
    cancelled.socket.server({ type: 'dictation.ready' })
    cancelled.session.cancel()
    expect(cancelled.states.at(-1)).toEqual(['cancelled', undefined])
    expect(cancelled.socket.closed).toBe(false)

    const hidden = setup()
    await hidden.session.start()
    hidden.socket.server({ type: 'dictation.ready' })
    hidden.session.interrupt()
    expect(hidden.states.at(-1)).toEqual(['interrupted', 'backgrounded'])
  })

  it('starts immediately on an open socket and completes two dictations without reconnecting', async () => {
    const connection = transport()
    const socket = connection.sockets[0]!
    socket.open()
    const ids: string[] = []
    for (const text of ['First recording', 'Second recording']) {
      const { session, source, finals } = setup(undefined, {}, connection)
      const starting = session.start()
      ids.push(socket.id)
      expect(socket.commands.at(-1)).toEqual({ type: 'dictation.start', id: socket.id })
      // Ready may arrive while permission is still being prepared.
      socket.server({ type: 'dictation.ready', sample_rate: 16000 })
      await starting
      source.emit(24_000)
      await session.finish()
      expect(socket.commands.at(-1)).toEqual({ type: 'dictation.finish', id: socket.id })
      socket.server({ type: 'dictation.final', text, transcription_id: text })
      expect(finals).toEqual([{ text, transcriptionId: text }])
      expect(session.state).toBe('review')
      expect(socket.closed).toBe(false)
    }
    expect(ids[0]).not.toBe(ids[1])
    expect(connection.create).toHaveBeenCalledOnce()
    expect(connection.create).toHaveBeenCalledWith(expect.stringMatching(/^wss?:\/\/.*\/v1\/live$/))
  })

  it('keeps a waiting dictation on its connecting socket when online fires', async () => {
    const { session, source, socket, connection, finals } = setup()
    await session.start()
    source.emit(24_000)
    window.dispatchEvent(new Event('online'))
    expect(connection.create).toHaveBeenCalledOnce()
    expect(socket.closed).toBe(false)
    expect(session.state).toBe('recording')
    expect(source.release).not.toHaveBeenCalled()
    expect(socket.sent).toEqual([])
    socket.server({ type: 'dictation.ready' })
    expect(socket.sent[1]).toBeInstanceOf(ArrayBuffer)
    await session.finish()
    socket.server({ type: 'dictation.final', text: 'Still recording', transcription_id: 'online' })
    expect(finals).toEqual([{ text: 'Still recording', transcriptionId: 'online' }])
    expect(session.state).toBe('review')
  })

  it('ends a dropped dictation and reconnects immediately for the next tap during backoff', async () => {
    vi.spyOn(Math, 'random').mockReturnValue(0.5)
    const first = setup()
    await first.session.start()
    first.socket.server({ type: 'dictation.ready' })
    first.source.emit(24_000)
    first.socket.drop()
    expect(first.states.at(-1)).toEqual(['interrupted', 'connection'])
    expect(first.source.release).toHaveBeenCalled()
    // Failed handshakes reach the ten-second backoff; a tap must not wait for that timer.
    for (const delay of [500, 1000, 2000, 4000, 8000]) {
      vi.advanceTimersByTime(delay)
      first.connection.sockets.at(-1)!.drop()
    }
    const attempts = first.connection.sockets.length
    const next = setup(undefined, {}, first.connection)
    await next.session.start()
    expect(first.connection.create).toHaveBeenCalledTimes(attempts + 1)
    const reconnected = first.connection.sockets.at(-1)!
    reconnected.server({ type: 'dictation.ready' })
    expect(reconnected.id).not.toBe(first.socket.id)
    next.source.emit(24_000)
    await next.session.finish()
    reconnected.server({
      type: 'dictation.final',
      text: 'After reconnect',
      transcription_id: 'new',
    })
    expect(next.finals).toEqual([{ text: 'After reconnect', transcriptionId: 'new' }])
    expect(next.session.state).toBe('review')
    expect(first.states.at(-1)).toEqual(['interrupted', 'connection'])
  })

  it('ignores stale ready, partial, final and error messages after cancelling a dictation', async () => {
    const first = setup()
    await first.session.start()
    first.socket.server({ type: 'dictation.ready' })
    const previousId = first.socket.id
    first.session.cancel()
    expect(first.socket.commands.at(-1)).toEqual({ type: 'dictation.cancel', id: previousId })
    const next = setup(undefined, {}, first.connection)
    await next.session.start()
    next.source.emit(24_000)
    await next.session.finish()
    const before = [...next.socket.sent]
    for (const type of ['ready', 'partial', 'final', 'error'])
      next.socket.server({
        type: `dictation.${type}`,
        id: previousId,
        text: 'Stale text',
        transcription_id: 'stale',
        error: 'provider_error',
      })
    expect(next.socket.sent).toEqual(before)
    expect(next.partials).toEqual([])
    expect(next.finals).toEqual([])
    expect(next.session.state).toBe('finalizing')
    next.socket.server({ type: 'dictation.ready' })
    next.socket.server({ type: 'dictation.final', text: 'Current', transcription_id: 'current' })
    expect(next.finals).toEqual([{ text: 'Current', transcriptionId: 'current' }])
  })

  it('opens only while signed in and visible, closes on hiding, and stays closed after sign-out', async () => {
    const visibility = vi.spyOn(document, 'visibilityState', 'get').mockReturnValue('hidden')
    const connection = transport()
    expect(connection.create).not.toHaveBeenCalled()
    visibility.mockReturnValue('visible')
    document.dispatchEvent(new Event('visibilitychange'))
    connection.live.start()
    expect(connection.create).toHaveBeenCalledOnce()
    const current = setup(undefined, {}, connection)
    await current.session.start()
    current.socket.server({ type: 'dictation.ready' })
    visibility.mockReturnValue('hidden')
    document.dispatchEvent(new Event('visibilitychange'))
    expect(current.socket.closed).toBe(true)
    expect(current.states.at(-1)).toEqual(['interrupted', 'backgrounded'])
    vi.advanceTimersByTime(30_000)
    window.dispatchEvent(new Event('online'))
    expect(connection.create).toHaveBeenCalledOnce()
    visibility.mockReturnValue('visible')
    document.dispatchEvent(new Event('visibilitychange'))
    expect(connection.create).toHaveBeenCalledTimes(2)
    expect(connection.sockets[1]!.sent).toEqual([])
    connection.live.stop()
    expect(connection.sockets[1]!.closed).toBe(true)
    document.dispatchEvent(new Event('visibilitychange'))
    window.dispatchEvent(new Event('online'))
    vi.advanceTimersByTime(30_000)
    expect(connection.create).toHaveBeenCalledTimes(2)
  })

  it('backs off from 0.5 to 10 seconds with jitter and retries immediately on online', () => {
    vi.spyOn(Math, 'random').mockReturnValue(1)
    const connection = transport()
    for (const base of [500, 1000, 2000, 4000, 8000, 10_000, 10_000]) {
      const count = connection.sockets.length
      connection.sockets.at(-1)!.drop()
      vi.advanceTimersByTime(base * 1.2 - 1)
      expect(connection.sockets).toHaveLength(count)
      vi.advanceTimersByTime(1)
      expect(connection.sockets).toHaveLength(count + 1)
    }
    connection.sockets.at(-1)!.drop()
    const count = connection.sockets.length
    window.dispatchEvent(new Event('online'))
    expect(connection.sockets).toHaveLength(count + 1)
    connection.sockets.at(-1)!.open()
    vi.advanceTimersByTime(12_000)
    expect(connection.sockets).toHaveLength(count + 1)
  })
})
