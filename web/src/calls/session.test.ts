import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { ApiError, NetworkError } from '@/core/http'
import type { Call, Current, ServerFrame } from './api'
import {
  CONNECT_MS,
  NO_OFFER_MS,
  REQUEST_RETRY_MS,
  RING_SAFETY_MS,
  RTC_CONFIG,
  UNSTABLE_MS,
  CallSession,
  type CallDeps,
} from './session'
import type { SignalHandlers } from './signal'

const OFFER = 'v=0\r\no=- 1 2 IN IP4 127.0.0.1\r\nm=audio 9 UDP/TLS/RTP/SAVPF 111\r\n'
const ANSWER = 'v=0\r\no=- 3 4 IN IP4 127.0.0.1\r\nm=audio 9 UDP/TLS/RTP/SAVPF 111\r\n'
const CANDIDATE = 'a=candidate:1 1 udp 2122260223 192.168.1.7 50000 typ host\r\n'

class FakeStream {
  tracks: unknown[] = []
  addTrack(track: unknown) {
    this.tracks.push(track)
  }
}

class FakePeer {
  signalingState: RTCSignalingState = 'stable'
  iceGatheringState: RTCIceGatheringState = 'new'
  connectionState: RTCPeerConnectionState = 'new'
  iceConnectionState: RTCIceConnectionState = 'new'
  localDescription: RTCSessionDescriptionInit | null = null
  remoteDescription: RTCSessionDescriptionInit | null = null
  ontrack: ((event: { track: unknown }) => void) | null = null
  onconnectionstatechange: (() => void) | null = null
  oniceconnectionstatechange: (() => void) | null = null
  onicegatheringstatechange: (() => void) | null = null
  addTransceiver = vi.fn()
  addTrack = vi.fn()
  restartIce = vi.fn()
  close = vi.fn()
  getStats = vi.fn(async () => new Map())
  createOffer = vi.fn(async (): Promise<RTCSessionDescriptionInit> => {
    if (this.role === 'callee') throw new Error('the callee never offers')
    return { type: 'offer', sdp: OFFER }
  })
  createAnswer = vi.fn(async (): Promise<RTCSessionDescriptionInit> => ({
    type: 'answer',
    sdp: ANSWER,
  }))
  setLocalDescription = vi.fn(async (description: RTCSessionDescriptionInit) => {
    this.localDescription = { ...description, sdp: `${description.sdp}${CANDIDATE}` }
    this.signalingState = description.type === 'offer' ? 'have-local-offer' : 'stable'
    this.iceGatheringState = 'complete'
  })
  setRemoteDescription = vi.fn(async (description: RTCSessionDescriptionInit) => {
    this.remoteDescription = description
    this.signalingState = description.type === 'offer' ? 'have-remote-offer' : 'stable'
  })

  constructor(readonly role: 'caller' | 'callee') {}

  connection(state: RTCPeerConnectionState) {
    this.connectionState = state
    this.onconnectionstatechange?.()
  }
}

const view = (role: 'caller' | 'callee', state: Call['state'] = 'ringing'): Call => ({
  id: 'call-1',
  channel_id: 'ch1',
  kind: 'audio',
  state,
  outcome: null,
  role,
  peer: { id: 'peer', name: 'Анна' },
  created_at: '2026-09-23T12:00:00+00:00',
  answered_at: state === 'ringing' ? null : '2026-09-23T12:00:05+00:00',
  ended_at: null,
})

function setup(role: 'caller' | 'callee') {
  const order: string[] = []
  const track = { kind: 'audio', enabled: true, stop: vi.fn() }
  const stream = { getTracks: () => [track], getAudioTracks: () => [track] }
  const peers: FakePeer[] = []
  const sockets: Array<{
    handlers: SignalHandlers
    send: ReturnType<typeof vi.fn>
    close: ReturnType<typeof vi.fn>
    connected: boolean
  }> = []
  let media = Promise.resolve(stream as unknown as MediaStream)
  const audio = document.createElement('audio')
  Object.defineProperty(audio, 'srcObject', { value: null, writable: true })
  audio.play = vi.fn(async () => void order.push('play'))
  audio.pause = vi.fn()
  const deps = {
    getUserMedia: vi.fn(() => {
      order.push('getUserMedia')
      return media.then((value) => {
        order.push('media')
        return value
      })
    }),
    createPeerConnection: vi.fn((configuration: RTCConfiguration) => {
      expect(configuration).toBe(RTC_CONFIG)
      order.push('peer')
      const peer = new FakePeer(role)
      peers.push(peer)
      return peer as unknown as RTCPeerConnection
    }),
    socket: vi.fn((_url: string, handlers: SignalHandlers) => {
      const socket = { handlers, send: vi.fn(() => true), close: vi.fn(), connected: true }
      sockets.push(socket)
      return socket
    }),
    api: {
      start: vi.fn(async () => view('caller')),
      accept: vi.fn(async () => view('callee', 'active')),
      end: vi.fn(async (): Promise<Call> => ({ ...view(role, 'ended'), outcome: 'completed' })),
      current: vi.fn(async (): Promise<Current> => ({ call: view(role, 'active') })),
    },
    createAudio: vi.fn(() => {
      order.push('audio')
      return audio
    }),
    keepAwake: vi.fn(),
    client: 'browser' as const,
    now: () => Date.now(),
    setTimeout: (handler: () => void, ms: number) => setTimeout(handler, ms),
    clearTimeout: (timer: ReturnType<typeof setTimeout> | undefined) => clearTimeout(timer),
    notice: vi.fn(),
    refresh: vi.fn(),
    finished: vi.fn(),
  } satisfies CallDeps
  return {
    deps,
    order,
    track,
    audio,
    peers,
    sockets,
    peer: () => peers.at(-1)!,
    socket: () => sockets.at(-1)!,
    sent: () => sockets.flatMap((socket) => socket.send.mock.calls.map(([frame]) => frame)),
    hold(promise: Promise<MediaStream>) {
      media = promise
    },
  }
}

/** Lets every pending promise chain run (fake timers do not need to move). */
async function settle() {
  for (let index = 0; index < 50; index += 1) await Promise.resolve()
}

const frame = (env: ReturnType<typeof setup>, value: ServerFrame) =>
  env.socket().handlers.frame(value)

async function dialed() {
  const env = setup('caller')
  const session = CallSession.dial(env.deps, {
    channelId: 'ch1',
    peerName: 'Анна',
    expectedUser: 'me',
  })
  await settle()
  env.socket().handlers.open()
  return { ...env, session }
}

/** The caller with its offer sent. */
async function offered() {
  const env = await dialed()
  frame(env, { type: 'state', state: 'active', outcome: null })
  frame(env, { type: 'peer', present: true })
  await settle()
  return env
}

async function answered() {
  const env = setup('callee')
  const session = CallSession.answer(env.deps, { call: view('callee'), expectedUser: 'me' })
  await settle()
  env.socket().handlers.open()
  frame(env, { type: 'state', state: 'active', outcome: null })
  frame(env, { type: 'peer', present: true })
  return { ...env, session }
}

beforeEach(() => {
  vi.useFakeTimers()
  vi.stubGlobal('MediaStream', FakeStream)
})
afterEach(() => {
  vi.useRealTimers()
  vi.unstubAllGlobals()
  document.body.innerHTML = ''
})

describe('media first', () => {
  it('plays inside the tap, before the microphone is awaited', () => {
    const env = setup('caller')
    CallSession.dial(env.deps, { channelId: 'ch1', peerName: 'Анна', expectedUser: 'me' })
    expect(env.order).toEqual(['audio', 'play', 'getUserMedia'])
    expect(env.audio.isConnected).toBe(true)
    expect(env.audio.hidden).toBe(true)
  })

  it.each(['caller', 'callee'] as const)(
    'the %s has the microphone before any peer connection exists',
    async (role) => {
      const env = setup(role)
      let resolve!: (stream: MediaStream) => void
      const stream = { getTracks: () => [env.track], getAudioTracks: () => [env.track] }
      env.hold(new Promise((done) => (resolve = done)))
      if (role === 'caller')
        CallSession.dial(env.deps, { channelId: 'ch1', peerName: 'Анна', expectedUser: 'me' })
      else CallSession.answer(env.deps, { call: view('callee'), expectedUser: 'me' })
      await settle()
      expect(env.deps.api.start).not.toHaveBeenCalled()
      expect(env.deps.api.accept).not.toHaveBeenCalled()
      resolve(stream as unknown as MediaStream)
      await settle()
      env.socket().handlers.open()
      frame(env, { type: 'state', state: 'active', outcome: null })
      frame(env, { type: 'peer', present: true })
      if (role === 'callee') frame(env, { type: 'offer', sdp: OFFER })
      await settle()
      expect(env.order.indexOf('media')).toBeLessThan(env.order.indexOf('peer'))
      expect(env.order).toContain('peer')
    },
  )

  it.each(['caller', 'callee'] as const)(
    'the %s keeps the screen on from the moment it holds the microphone',
    async (role) => {
      const env = setup(role)
      let resolve!: (stream: MediaStream) => void
      const stream = { getTracks: () => [env.track], getAudioTracks: () => [env.track] }
      env.hold(new Promise((done) => (resolve = done)))
      const session =
        role === 'caller'
          ? CallSession.dial(env.deps, { channelId: 'ch1', peerName: 'Анна', expectedUser: 'me' })
          : CallSession.answer(env.deps, { call: view('callee'), expectedUser: 'me' })
      await settle()
      expect(env.deps.keepAwake).not.toHaveBeenCalled()
      resolve(stream as unknown as MediaStream)
      await settle()
      // Ringing or still accepting: no media yet, the screen already stays on.
      expect(env.deps.keepAwake).toHaveBeenCalledExactlyOnceWith(true)
      const request = role === 'caller' ? env.deps.api.start : env.deps.api.accept
      expect(env.deps.keepAwake.mock.invocationCallOrder[0]).toBeLessThan(
        request.mock.invocationCallOrder[0]!,
      )
      session.hangUp()
      expect(env.deps.keepAwake).toHaveBeenLastCalledWith(false)
    },
  )

  it.each(['caller', 'callee'] as const)(
    'a refused microphone sends nothing (%s)',
    async (role) => {
      const env = setup(role)
      env.hold(Promise.reject(new DOMException('no', 'NotAllowedError')))
      const session =
        role === 'caller'
          ? CallSession.dial(env.deps, { channelId: 'ch1', peerName: 'Анна', expectedUser: 'me' })
          : CallSession.answer(env.deps, { call: view('callee'), expectedUser: 'me' })
      await settle()
      expect(env.deps.api.start).not.toHaveBeenCalled()
      expect(env.deps.api.accept).not.toHaveBeenCalled()
      expect(env.deps.api.end).not.toHaveBeenCalled()
      expect(env.deps.notice).toHaveBeenCalledWith({ key: 'calls.denied' })
      expect(env.deps.finished).toHaveBeenCalledWith(session, 'closed')
      expect(env.audio.isConnected).toBe(false)
      expect(env.deps.keepAwake).not.toHaveBeenCalledWith(true)
    },
  )
})

describe('caller', () => {
  it('starts with a fresh key on behalf of the signed-in person', async () => {
    const env = await dialed()
    expect(env.deps.api.start).toHaveBeenCalledWith('ch1', expect.any(String), 'me')
    expect(env.deps.socket).toHaveBeenCalledWith(
      'ws://localhost:3000/v1/calls/call-1/signal',
      expect.anything(),
    )
    expect(env.session.view.value.phase).toBe('outgoing')
  })

  it('offers once, only when the call is active and the callee is there', async () => {
    const env = await dialed()
    frame(env, { type: 'state', state: 'active', outcome: null })
    await settle()
    expect(env.deps.createPeerConnection).not.toHaveBeenCalled()
    frame(env, { type: 'peer', present: true })
    frame(env, { type: 'state', state: 'active', outcome: null })
    frame(env, { type: 'peer', present: true })
    await settle()
    expect(env.deps.createPeerConnection).toHaveBeenCalledOnce()
    expect(env.peer().addTransceiver).toHaveBeenCalledWith(env.track, {
      direction: 'sendrecv',
      streams: [expect.anything()],
    })
    expect(env.sent()).toEqual([{ type: 'offer', sdp: OFFER + CANDIDATE }])
    expect(env.session.view.value).toMatchObject({ phase: 'connecting', kinds: ['lan4'] })
  })

  it('a present callee alone is not enough', async () => {
    const env = await dialed()
    frame(env, { type: 'peer', present: true })
    frame(env, { type: 'state', state: 'ringing', outcome: null })
    await settle()
    expect(env.deps.createPeerConnection).not.toHaveBeenCalled()
  })

  it('sends the same offer again when the callee comes back without answering', async () => {
    const env = await offered()
    frame(env, { type: 'peer', present: false })
    frame(env, { type: 'peer', present: true })
    expect(env.sent()).toEqual([
      { type: 'offer', sdp: OFFER + CANDIDATE },
      { type: 'offer', sdp: OFFER + CANDIDATE },
    ])
    // Its own reconnect counts too: the server announces the callee again.
    env.socket().handlers.open()
    frame(env, { type: 'peer', present: true })
    expect(env.sent()).toHaveLength(3)
    frame(env, { type: 'answer', sdp: ANSWER })
    await settle()
    frame(env, { type: 'peer', present: false })
    frame(env, { type: 'peer', present: true })
    expect(env.sent()).toHaveLength(3)
    expect(env.peer().createOffer).toHaveBeenCalledOnce()
  })

  it('ignores stale answers', async () => {
    const env = await dialed()
    frame(env, { type: 'answer', sdp: ANSWER })
    frame(env, { type: 'state', state: 'active', outcome: null })
    frame(env, { type: 'peer', present: true })
    await settle()
    frame(env, { type: 'answer', sdp: ANSWER })
    frame(env, { type: 'answer', sdp: 'v=0\r\nother\r\n' })
    await settle()
    expect(env.peer().setRemoteDescription).toHaveBeenCalledOnce()
    expect(env.peer().setRemoteDescription).toHaveBeenCalledWith({ type: 'answer', sdp: ANSWER })
  })

  it('hangs up by itself when nobody answers', async () => {
    const env = await dialed()
    await vi.advanceTimersByTimeAsync(RING_SAFETY_MS)
    expect(env.deps.api.end).toHaveBeenCalledWith('call-1', {
      reason: 'hangup',
      summary: expect.objectContaining({ client: 'browser', ice: 'new' }),
    })
  })

  it('ends a call cancelled during the start request once the server names it', async () => {
    const env = setup('caller')
    let started!: (call: Call) => void
    env.deps.api.start.mockReturnValueOnce(new Promise((done) => (started = done)))
    const session = CallSession.dial(env.deps, {
      channelId: 'ch1',
      peerName: 'Анна',
      expectedUser: 'me',
    })
    await settle()
    session.hangUp()
    expect(session.view.value).toMatchObject({ phase: 'ended', outcome: 'cancelled' })
    started(view('caller'))
    await settle()
    expect(env.deps.socket).not.toHaveBeenCalled()
    expect(env.deps.api.end).toHaveBeenCalledOnce()
    expect(env.deps.api.end).toHaveBeenCalledWith(
      'call-1',
      expect.objectContaining({ reason: 'hangup' }),
    )
  })

  it('asks again with the same key when the reply to start is lost', async () => {
    const env = setup('caller')
    env.deps.api.start
      .mockRejectedValueOnce(new NetworkError(true))
      .mockRejectedValueOnce(new ApiError(502, null, null, null, null))
    const session = CallSession.dial(env.deps, {
      channelId: 'ch1',
      peerName: 'Анна',
      expectedUser: 'me',
    })
    await settle()
    expect(env.deps.api.start).toHaveBeenCalledOnce()
    await vi.advanceTimersByTimeAsync(500)
    expect(env.deps.api.start).toHaveBeenCalledTimes(2)
    await vi.advanceTimersByTimeAsync(1_000)
    expect(env.deps.api.start).toHaveBeenCalledTimes(3)
    const keys = env.deps.api.start.mock.calls.map((args) => (args as unknown[])[1])
    expect(new Set(keys).size).toBe(1)
    expect(env.deps.socket).toHaveBeenCalledOnce()
    expect(env.deps.notice).not.toHaveBeenCalled()
    expect(session.view.value.phase).toBe('outgoing')
  })

  it('gives up on start after about 10 s and never repeats a refusal', async () => {
    const env = setup('caller')
    env.deps.api.start.mockRejectedValue(new NetworkError(false))
    const session = CallSession.dial(env.deps, {
      channelId: 'ch1',
      peerName: 'Анна',
      expectedUser: 'me',
    })
    await settle()
    await vi.advanceTimersByTimeAsync(REQUEST_RETRY_MS)
    // 0.5, 1, 2 and 4 s apart; the next pause would end past the 10 s.
    expect(env.deps.api.start).toHaveBeenCalledTimes(5)
    expect(env.deps.notice).toHaveBeenCalledWith({ error: expect.any(NetworkError) })
    expect(env.deps.finished).toHaveBeenCalledWith(session, 'closed')
    expect(env.deps.keepAwake).toHaveBeenLastCalledWith(false)
    await vi.advanceTimersByTimeAsync(60_000)
    expect(env.deps.api.start).toHaveBeenCalledTimes(5)
    expect(env.deps.socket).not.toHaveBeenCalled()
  })

  it('a repeated start that finds the call over ends here, without a socket or an end', async () => {
    const env = setup('caller')
    env.deps.api.start
      .mockRejectedValueOnce(new NetworkError(true))
      .mockResolvedValueOnce({ ...view('caller', 'ended'), outcome: 'declined' })
    const session = CallSession.dial(env.deps, {
      channelId: 'ch1',
      peerName: 'Анна',
      expectedUser: 'me',
    })
    await settle()
    await vi.advanceTimersByTimeAsync(500)
    expect(session.view.value).toMatchObject({ phase: 'ended', outcome: 'declined' })
    expect(env.deps.finished).toHaveBeenCalledWith(session, 'ended')
    expect(env.deps.socket).not.toHaveBeenCalled()
    expect(env.deps.api.end).not.toHaveBeenCalled()
    expect(env.track.stop).toHaveBeenCalled()
  })

  it('a cancel while start is asked again still reaches the call', async () => {
    const env = setup('caller')
    env.deps.api.start.mockRejectedValueOnce(new NetworkError(true))
    const session = CallSession.dial(env.deps, {
      channelId: 'ch1',
      peerName: 'Анна',
      expectedUser: 'me',
    })
    await settle()
    session.hangUp()
    await vi.advanceTimersByTimeAsync(500)
    expect(env.deps.api.start).toHaveBeenCalledTimes(2)
    expect(env.deps.socket).not.toHaveBeenCalled()
    expect(env.deps.api.end).toHaveBeenCalledExactlyOnceWith(
      'call-1',
      expect.objectContaining({ reason: 'hangup' }),
    )
  })

  it('leaves «incoming» to the other call and explains other refusals', async () => {
    const env = setup('caller')
    env.deps.api.start.mockRejectedValueOnce(new ApiError(409, 'incoming', null, null, null))
    CallSession.dial(env.deps, { channelId: 'ch1', peerName: 'Анна', expectedUser: 'me' })
    await settle()
    expect(env.deps.refresh).toHaveBeenCalled()
    expect(env.deps.notice).not.toHaveBeenCalled()
    expect(env.track.stop).toHaveBeenCalled()

    const busy = setup('caller')
    busy.deps.api.start.mockRejectedValueOnce(new ApiError(409, 'busy', null, null, null))
    CallSession.dial(busy.deps, { channelId: 'ch1', peerName: 'Анна', expectedUser: 'me' })
    await settle()
    expect(busy.deps.notice).toHaveBeenCalledWith({ key: 'calls.error.busy' })
    await vi.advanceTimersByTimeAsync(REQUEST_RETRY_MS)
    expect(busy.deps.api.start).toHaveBeenCalledOnce()
  })
})

describe('callee', () => {
  it('accepts, answers the offer and never offers itself', async () => {
    const env = await answered()
    expect(env.deps.api.accept).toHaveBeenCalledWith('call-1', 'me')
    frame(env, { type: 'offer', sdp: OFFER })
    await settle()
    const peer = env.peer()
    expect(peer.setRemoteDescription).toHaveBeenCalledWith({ type: 'offer', sdp: OFFER })
    expect(peer.addTrack).toHaveBeenCalledWith(env.track, expect.anything())
    expect(peer.createOffer).not.toHaveBeenCalled()
    expect(env.sent()).toEqual([{ type: 'answer', sdp: ANSWER + CANDIDATE }])
  })

  it('answers a repeated offer with the same answer', async () => {
    const env = await answered()
    frame(env, { type: 'offer', sdp: OFFER })
    await settle()
    frame(env, { type: 'offer', sdp: OFFER })
    await settle()
    expect(env.sent()).toEqual([
      { type: 'answer', sdp: ANSWER + CANDIDATE },
      { type: 'answer', sdp: ANSWER + CANDIDATE },
    ])
    expect(env.deps.createPeerConnection).toHaveBeenCalledOnce()
    expect(env.peer().createAnswer).toHaveBeenCalledOnce()
  })

  it('asks again from the same session when the reply to accept is lost', async () => {
    const env = setup('callee')
    env.deps.api.accept.mockRejectedValueOnce(new NetworkError(true))
    const session = CallSession.answer(env.deps, { call: view('callee'), expectedUser: 'me' })
    await settle()
    expect(env.deps.socket).not.toHaveBeenCalled()
    await vi.advanceTimersByTimeAsync(500)
    expect(env.deps.api.accept.mock.calls).toEqual([
      ['call-1', 'me'],
      ['call-1', 'me'],
    ])
    expect(env.deps.socket).toHaveBeenCalledOnce()
    expect(env.deps.notice).not.toHaveBeenCalled()
    expect(session.view.value.phase).toBe('connecting')
  })

  it('stops asking to accept once the person hangs up', async () => {
    const env = setup('callee')
    env.deps.api.accept.mockRejectedValue(new NetworkError(false))
    const session = CallSession.answer(env.deps, { call: view('callee'), expectedUser: 'me' })
    await settle()
    session.hangUp()
    await vi.advanceTimersByTimeAsync(REQUEST_RETRY_MS)
    expect(env.deps.api.accept).toHaveBeenCalledOnce()
    expect(env.deps.api.end).toHaveBeenCalledExactlyOnceWith(
      'call-1',
      expect.objectContaining({ reason: 'hangup' }),
    )
    expect(env.deps.notice).not.toHaveBeenCalled()
  })

  it('a refused accept closes with the reason', async () => {
    const env = setup('callee')
    env.deps.api.accept.mockRejectedValueOnce(
      new ApiError(409, 'answered_elsewhere', null, null, null),
    )
    const session = CallSession.answer(env.deps, { call: view('callee'), expectedUser: 'me' })
    await settle()
    expect(env.deps.notice).toHaveBeenCalledWith({ key: 'calls.error.answeredElsewhere' })
    expect(env.deps.refresh).toHaveBeenCalled()
    expect(env.deps.finished).toHaveBeenCalledWith(session, 'closed')
    expect(env.deps.socket).not.toHaveBeenCalled()
    expect(env.deps.api.end).not.toHaveBeenCalled()
  })
})

describe('deadlines', () => {
  it('20 s without media ends as ice_timeout', async () => {
    const env = await offered()
    await vi.advanceTimersByTimeAsync(CONNECT_MS - 1)
    expect(env.deps.api.end).not.toHaveBeenCalled()
    await vi.advanceTimersByTimeAsync(1)
    expect(env.deps.api.end).toHaveBeenCalledWith(
      'call-1',
      expect.objectContaining({ reason: 'ice_timeout' }),
    )
    expect(env.session.view.value).toMatchObject({ phase: 'ended', outcome: 'completed' })
  })

  it('the caller counts its 20 s from the accept, even when the callee never arrives', async () => {
    const env = await dialed()
    await vi.advanceTimersByTimeAsync(10_000)
    frame(env, { type: 'state', state: 'active', outcome: null })
    await vi.advanceTimersByTimeAsync(CONNECT_MS - 1)
    expect(env.deps.api.end).not.toHaveBeenCalled()
    expect(env.session.view.value.phase).toBe('outgoing')
    await vi.advanceTimersByTimeAsync(1)
    expect(env.deps.api.end).toHaveBeenCalledExactlyOnceWith(
      'call-1',
      expect.objectContaining({ reason: 'ice_timeout' }),
    )
    // The ringing safety net is gone: nothing ends it a second time as a hang-up.
    await vi.advanceTimersByTimeAsync(RING_SAFETY_MS)
    expect(env.deps.api.end).toHaveBeenCalledOnce()
  })

  it('a reconnect during the call starts no deadline again', async () => {
    const env = await offered()
    frame(env, { type: 'answer', sdp: ANSWER })
    await settle()
    env.peer().connection('connected')
    env.socket().handlers.open()
    frame(env, { type: 'state', state: 'active', outcome: null })
    frame(env, { type: 'peer', present: true })
    await vi.advanceTimersByTimeAsync(RING_SAFETY_MS + CONNECT_MS)
    expect(env.deps.api.end).not.toHaveBeenCalled()
    expect(env.session.view.value.phase).toBe('connected')
    expect(env.peer().createOffer).toHaveBeenCalledOnce()
  })

  it('a failed connection ends at once, without a restart', async () => {
    const env = await offered()
    env.peer().connection('failed')
    expect(env.deps.api.end).toHaveBeenCalledWith(
      'call-1',
      expect.objectContaining({ reason: 'ice_failed' }),
    )
    expect(env.peer().restartIce).not.toHaveBeenCalled()
    expect(env.peer().createOffer).toHaveBeenCalledOnce()
    expect(env.session.view.value.outcome).toBe('failed')
  })

  it('the callee waits 15 s for an offer', async () => {
    const env = await answered()
    await vi.advanceTimersByTimeAsync(NO_OFFER_MS)
    expect(env.deps.api.end).toHaveBeenCalledWith(
      'call-1',
      expect.objectContaining({ reason: 'no_offer' }),
    )
  })

  it('connected media lost for 15 s ends as media_lost; a return in time keeps the call', async () => {
    const env = await offered()
    frame(env, { type: 'answer', sdp: ANSWER })
    await settle()
    env.peer().connection('connected')
    expect(env.session.view.value.phase).toBe('connected')
    expect(env.deps.keepAwake).toHaveBeenLastCalledWith(true)
    env.peer().connection('disconnected')
    expect(env.session.view.value.phase).toBe('unstable')
    await vi.advanceTimersByTimeAsync(UNSTABLE_MS - 1)
    env.peer().connection('connected')
    expect(env.session.view.value.phase).toBe('connected')
    // The connect deadline no longer applies once connected.
    await vi.advanceTimersByTimeAsync(CONNECT_MS)
    expect(env.deps.api.end).not.toHaveBeenCalled()
    env.peer().connection('disconnected')
    await vi.advanceTimersByTimeAsync(UNSTABLE_MS)
    expect(env.deps.api.end).toHaveBeenCalledWith(
      'call-1',
      expect.objectContaining({ reason: 'media_lost' }),
    )
  })
})

describe('in the call', () => {
  it('mute only switches the track', async () => {
    const env = await offered()
    env.session.toggleMute()
    expect(env.track.enabled).toBe(false)
    expect(env.session.view.value.muted).toBe(true)
    env.session.toggleMute()
    expect(env.track.enabled).toBe(true)
    expect(env.peer().createOffer).toHaveBeenCalledOnce()
    expect(env.sent()).toHaveLength(1)
  })

  it('plays the remote track and offers sound when autoplay is refused', async () => {
    const env = await offered()
    vi.mocked(env.audio.play).mockRejectedValueOnce(new DOMException('no', 'NotAllowedError'))
    env.peer().ontrack?.({ track: { kind: 'audio' } })
    await settle()
    expect(env.session.view.value.autoplayBlocked).toBe(true)
    env.session.enableSound()
    await settle()
    expect(env.session.view.value.autoplayBlocked).toBe(false)
  })
})

describe('the end', () => {
  type Env = Awaited<ReturnType<typeof offered>>
  const paths: Array<[string, (env: Env) => void | Promise<void>, object]> = [
    ['hang-up', (env) => env.session.hangUp(), { reason: 'hangup' }],
    [
      'a state frame',
      (env) => frame(env, { type: 'state', state: 'ended', outcome: 'declined' }),
      {},
    ],
    ['close 4001', (env) => env.socket().handlers.final(4001), {}],
    ['a failed connection', (env) => env.peer().connection('failed'), { reason: 'ice_failed' }],
    [
      'the connect deadline',
      async () => void (await vi.advanceTimersByTimeAsync(CONNECT_MS)),
      { reason: 'ice_timeout' },
    ],
  ]

  it.each(paths)('after %s releases everything and sends one end', async (_, act, body) => {
    const env = await offered()
    await act(env)
    env.session.hangUp()
    frame(env, { type: 'state', state: 'ended', outcome: 'completed' })
    await settle()
    expect(env.track.stop).toHaveBeenCalled()
    expect(env.peer().close).toHaveBeenCalled()
    expect(env.socket().close).toHaveBeenCalled()
    expect(env.deps.keepAwake).toHaveBeenLastCalledWith(false)
    expect(env.audio.isConnected).toBe(false)
    expect(env.audio.srcObject).toBeNull()
    expect(env.deps.api.end).toHaveBeenCalledOnce()
    const [, sent] = env.deps.api.end.mock.calls[0]! as unknown as [string, object]
    expect(sent).toEqual({ ...body, summary: expect.objectContaining({ client: 'browser' }) })
    expect(env.session.view.value.phase).toBe('ended')
    expect(env.deps.finished).toHaveBeenCalledWith(env.session, 'ended')
  })

  it('takes the outcome of the state frame, then the one the server stored', async () => {
    const env = await offered()
    env.deps.api.end.mockResolvedValueOnce({ ...view('caller', 'ended'), outcome: 'missed' })
    frame(env, { type: 'state', state: 'ended', outcome: 'declined' })
    expect(env.session.view.value.outcome).toBe('declined')
    await settle()
    expect(env.session.view.value.outcome).toBe('missed')
  })

  it.each(['signed out', 'page hidden'])('sends nothing when the %s', async () => {
    const env = await offered()
    env.session.teardown()
    await vi.advanceTimersByTimeAsync(RING_SAFETY_MS)
    expect(env.deps.api.end).not.toHaveBeenCalled()
    expect(env.track.stop).toHaveBeenCalled()
    expect(env.peer().close).toHaveBeenCalled()
    expect(env.socket().close).toHaveBeenCalled()
    expect(env.deps.keepAwake).toHaveBeenLastCalledWith(false)
  })

  it('a replaced socket tears down without an end', async () => {
    const env = await offered()
    env.socket().handlers.final(4000)
    await settle()
    expect(env.deps.api.end).not.toHaveBeenCalled()
    expect(env.session.view.value.phase).toBe('ended')
  })

  it('a current call without this id ends the session', async () => {
    const env = await offered()
    env.session.observe({ call: view('caller', 'active') })
    expect(env.session.view.value.phase).toBe('connecting')
    env.session.observe({ call: null })
    expect(env.session.view.value.phase).toBe('ended')
    expect(env.deps.api.end).toHaveBeenCalledWith('call-1', {
      summary: expect.objectContaining({ client: 'browser' }),
    })
  })

  it('checks the current call after each reconnect', async () => {
    const env = await offered()
    env.deps.api.current.mockRejectedValueOnce(new NetworkError(false))
    env.socket().handlers.open()
    await settle()
    expect(env.session.view.value.phase).toBe('connecting')
    env.deps.api.current.mockResolvedValueOnce({ call: null })
    env.socket().handlers.open()
    await settle()
    expect(env.deps.api.current).toHaveBeenCalledTimes(2)
    expect(env.session.view.value.phase).toBe('ended')
  })

  it('retries the end after network errors and server failures, never after a refusal', async () => {
    const env = await offered()
    env.deps.api.end
      .mockRejectedValueOnce(new NetworkError(false))
      .mockRejectedValueOnce(new ApiError(503, null, null, null, null))
    env.session.hangUp()
    await vi.advanceTimersByTimeAsync(10_000)
    expect(env.deps.api.end).toHaveBeenCalledTimes(3)

    const refused = await offered()
    refused.deps.api.end.mockRejectedValueOnce(new ApiError(404, 'not_found', null, null, null))
    refused.session.hangUp()
    await vi.advanceTimersByTimeAsync(60_000)
    expect(refused.deps.api.end).toHaveBeenCalledOnce()
  })
})
