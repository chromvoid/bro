/**
 * One call on this device, from the tap to the ended screen (docs/calls-spec.md). The caller
 * always offers, each side sends its complete description once gathering is done, and a lost
 * description is sent again unchanged; there is no ICE restart. Every browser API arrives
 * through CallDeps, so the tests drive it with fakes.
 */
import { shallowRef, type ShallowRef } from 'vue'
import { ApiError, NetworkError } from '@/core/http'
import type { MicrophoneProblem } from '@/dictation/audio'
import {
  errorKey,
  type Call,
  type CallOutcome,
  type ClientFrame,
  type Client,
  type Current,
  type EndBody,
  type EndReason,
  type Kind,
  type Selected,
  type ServerFrame,
  type Summary,
} from './api'
import {
  AUDIO,
  NO_SAMPLE,
  RTC_CONFIG,
  buildSummary,
  gathered,
  parseCandidates,
  sampleStats,
  type Sample,
  type Timers,
} from './network'
import { signalUrl, type SignalHandlers } from './signal'

export { AUDIO, RTC_CONFIG } from './network'

/** From accept to connected media: the callee's accept reply, the caller's first `state active`. */
export const CONNECT_MS = 40_000
/** The callee waits this long for the caller's offer. */
export const NO_OFFER_MS = 20_000
/** Connected media that stays disconnected this long ends the call. */
export const UNSTABLE_MS = 15_000
export const GATHER_MS = 10_000
/** The server ends a ringing call after 45 s; this is the caller's own safety net. */
export const RING_SAFETY_MS = 60_000
export const STATS_MS = 2_000
export const ENDED_SHOW_MS = 3_000
export const END_RETRY_MS = 60_000
const END_BACKOFF_MS = 1_000
const END_BACKOFF_CAP_MS = 10_000
/** Start and accept are asked again this long after the first unknown outcome. */
export const REQUEST_RETRY_MS = 10_000
const REQUEST_BACKOFF_MS = 500

export type Phase = 'outgoing' | 'connecting' | 'connected' | 'unstable' | 'ended'
/** Two steps the screen does not name: the microphone prompt and the accept request. */
type Step = 'acquiring' | 'starting' | 'accepting' | Phase
export type Role = 'caller' | 'callee'

export interface CallView {
  role: Role
  phase: Phase
  peerName: string
  muted: boolean
  /** The browser refused to play the other side: the screen offers «Включить звук». */
  autoplayBlocked: boolean
  /** deps.now() when media connected; the screen counts the call's length from it. */
  connectedAt: number | null
  outcome: CallOutcome | null
  // Developer details, codes and numbers only.
  signal: boolean
  ice: string
  kinds: Kind[]
  selected: Selected | null
  rttMs: number | null
  lossPct: number | null
  /** What was sent with `end`; the ended screen keeps it for developers. */
  summary: Summary | null
}

export interface SignalLike {
  send(frame: ClientFrame): boolean
  close(): void
  readonly connected?: boolean
}

/** Why a call did not start, for the person: an i18n key, or a request error to describe. */
export type Notice = { key: string } | { error: unknown }

export interface CallDeps extends Timers {
  getUserMedia(constraints: MediaStreamConstraints): Promise<MediaStream>
  createPeerConnection(configuration: RTCConfiguration): RTCPeerConnection
  socket(url: string, handlers: SignalHandlers): SignalLike
  api: {
    start(channelId: string, key: string, expectedUser: string): Promise<Call>
    accept(id: string, expectedUser: string): Promise<Call>
    end(id: string, body: EndBody): Promise<Call>
    current(): Promise<Current>
    ice?(id: string): Promise<RTCConfiguration>
  }
  createAudio(): HTMLAudioElement
  keepAwake(on: boolean): void
  client: Client
  now(): number
  notice(notice: Notice): void
  /** The server knows better than this tab: read ['calls'] again. */
  refresh(): void
  /** «closed» leaves the screen at once; «ended» shows the ended screen first. */
  finished(session: CallSession, how: 'closed' | 'ended'): void
}

const FAILED: readonly EndReason[] = ['ice_timeout', 'ice_failed', 'no_offer']

/** The same words as dictation (dictation/audio.ts), plus browsers without WebRTC. */
export function microphoneProblem(error: unknown): MicrophoneProblem {
  const name = (error as { name?: unknown } | null)?.name
  if (name === 'NotAllowedError' || name === 'SecurityError') return 'denied'
  return name === 'NotSupportedError' ? 'unsupported' : 'unavailable'
}

const notice = (error: unknown): Notice => {
  const key = errorKey(error)
  return key ? { key } : { error }
}

/** No reply, or the server failed: the outcome of the request is unknown. */
const transient = (error: unknown): boolean =>
  error instanceof NetworkError || (error instanceof ApiError && error.status >= 500)

export class CallSession {
  readonly view: ShallowRef<CallView>
  /** deps.now() when the call id became known: a `current` read before it says nothing. */
  since: number | null = null
  private step: Step = 'acquiring'
  private call: Call | null = null
  private readonly media: Promise<MediaStream>
  private stream: MediaStream | null = null
  private pc: RTCPeerConnection | null = null
  private socket: SignalLike | null = null
  private readonly remote: MediaStream | null
  private readonly audio: HTMLAudioElement
  private readonly timers = new Map<string, ReturnType<typeof setTimeout>>()
  /** Caller: the server said `active`; callee: accept succeeded. */
  private accepted = false
  /** The other side's socket is attached. */
  private present = false
  private reopened = false
  /** Caller: the offer went out at least once; callee: the answer is ready. */
  private described = false
  private answerApplied = false
  private ice: Summary['ice'] = 'new'
  private sample: Sample = NO_SAMPLE
  private blocked = false
  private torn = false
  private posted = false
  /** The caller hung up while the start request was in flight: sent once the id is known. */
  private pending: EndBody | null = null

  private constructor(
    private readonly deps: CallDeps,
    readonly role: Role,
    peerName: string,
    private readonly expectedUser: string,
  ) {
    this.view = shallowRef<CallView>({
      role,
      phase: role === 'caller' ? 'outgoing' : 'connecting',
      peerName,
      muted: false,
      autoplayBlocked: false,
      connectedAt: null,
      outcome: null,
      signal: false,
      ice: 'new',
      kinds: [],
      selected: null,
      rttMs: null,
      lossPct: null,
      summary: null,
    })
    // Inside the tap, before anything is awaited: the shells play media only after a gesture,
    // and the element keeps that permission when the other side's track arrives later.
    this.remote = typeof MediaStream === 'function' ? new MediaStream() : null
    this.audio = deps.createAudio()
    this.audio.autoplay = true
    this.audio.srcObject = this.remote
    this.audio.hidden = true
    document.body.append(this.audio)
    this.audio.play()?.catch(() => undefined)
    // Without capture the browser hides host addresses, and with no STUN nothing else is left.
    let media: Promise<MediaStream>
    try {
      media = deps.getUserMedia(AUDIO)
    } catch (error) {
      media = Promise.reject(error)
    }
    this.media = media
  }

  /** Must run synchronously in the tap. */
  static dial(
    deps: CallDeps,
    options: { channelId: string; peerName: string; expectedUser: string },
  ): CallSession {
    const session = new CallSession(deps, 'caller', options.peerName, options.expectedUser)
    void session.dialing(options.channelId)
    return session
  }

  /** Must run synchronously in the tap. */
  static answer(deps: CallDeps, options: { call: Call; expectedUser: string }): CallSession {
    const session = new CallSession(deps, 'callee', options.call.peer.name, options.expectedUser)
    session.call = options.call
    session.since = deps.now()
    void session.answering()
    return session
  }

  get callId(): string | null {
    return this.call?.id ?? null
  }

  hangUp(): void {
    this.end('hangup')
  }

  /** Mute only switches the track off: no new track and no new offer. */
  toggleMute(): void {
    const muted = !this.view.value.muted
    for (const track of this.stream?.getAudioTracks() ?? []) track.enabled = !muted
    this.update({ muted })
  }

  /** «Включить звук»: play() inside this tap. */
  enableSound(): void {
    if (!this.torn) void this.play()
  }

  /** A `current` read after the call id was known; a call missing from it is over. */
  observe(current: Current): void {
    if (this.torn || !this.call) return
    if (current.call?.id !== this.call.id) this.over(null)
  }

  /**
   * The page or the account goes away: everything stops here and nothing is sent; the server's
   * presence rule ends the call.
   */
  teardown(): void {
    this.posted = true
    this.pending = null
    this.release()
  }

  private async capture(): Promise<boolean> {
    let stream: MediaStream
    try {
      stream = await this.media
    } catch (error) {
      if (this.torn) return false
      this.release()
      this.deps.notice({ key: `calls.${microphoneProblem(error)}` })
      this.deps.finished(this, 'closed')
      return false
    }
    if (this.torn) {
      for (const track of stream.getTracks()) track.stop()
      return false
    }
    this.stream = stream
    for (const track of stream.getAudioTracks()) track.enabled = !this.view.value.muted
    // From the microphone on, ringing included: a phone that locks loses it (docs/calls-spec.md).
    this.deps.keepAwake(true)
    return true
  }

  /**
   * Start (the same key) and accept (the same session) are safe to send again: a lost reply or a
   * server failure is asked again for a short while. `going` says whether the answer still matters.
   */
  private async retried<T>(request: () => Promise<T>, going: () => boolean): Promise<T> {
    let first: number | null = null
    for (let delay = REQUEST_BACKOFF_MS; ; delay *= 2) {
      try {
        return await request()
      } catch (error) {
        first ??= this.deps.now()
        if (!transient(error) || !going() || this.deps.now() + delay - first > REQUEST_RETRY_MS)
          throw error
        // Not a session timer: release() must not leave this wait hanging.
        await new Promise<void>((resolve) => this.deps.setTimeout(resolve, delay))
        if (!going()) throw error
      }
    }
  }

  private async dialing(channelId: string): Promise<void> {
    if (!(await this.capture())) return
    this.step = 'starting'
    // One key per tap: a repeated start is this call again, never a second one.
    const key = crypto.randomUUID()
    let call: Call
    try {
      call = await this.retried(
        () => this.deps.api.start(channelId, key, this.expectedUser),
        // Hung up meanwhile: the call id is still needed to cancel it.
        () => !this.torn || this.pending !== null,
      )
    } catch (error) {
      if (this.torn) return
      this.release()
      // Both pressed call at once: the other person's call shows up instead.
      if (error instanceof ApiError && error.code === 'incoming') this.deps.refresh()
      else this.deps.notice(notice(error))
      this.deps.finished(this, 'closed')
      return
    }
    this.call = call
    this.since = this.deps.now()
    if (this.torn) {
      if (this.pending) this.post(this.pending)
      return
    }
    if (call.state === 'ended') {
      // A repeated start whose first reply was lost: the call is already over; nothing to end.
      this.posted = true
      return this.over(call.outcome)
    }
    if (!(await this.configureIce())) return
    this.go('outgoing')
    this.open()
    this.later('ring', RING_SAFETY_MS, () => this.end('hangup'))
  }

  private async answering(): Promise<void> {
    const call = this.call!
    if (!(await this.capture())) return
    this.step = 'accepting'
    try {
      this.call = await this.retried(
        () => this.deps.api.accept(call.id, this.expectedUser),
        () => !this.torn,
      )
    } catch (error) {
      if (this.torn) return
      this.release()
      this.deps.notice(notice(error))
      this.deps.refresh()
      this.deps.finished(this, 'closed')
      return
    }
    if (this.torn) return
    this.accepted = true
    if (!(await this.configureIce())) return
    this.go('connecting')
    this.open()
    this.later('offer', NO_OFFER_MS, () => this.end('no_offer'))
    this.later('connect', CONNECT_MS, () => this.end('ice_timeout'))
  }

  private open(): void {
    this.socket = this.deps.socket(signalUrl(location, this.call!.id), {
      open: () => this.opened(),
      frame: (frame) => this.frame(frame),
      // 4000: a newer socket for this side took the call over (another tab of this sign-in);
      // this one steps aside without an end.
      final: (code) => (code === 4001 ? this.over(null) : this.replaced()),
    })
  }

  private opened(): void {
    if (this.torn) return
    this.update({ signal: true })
    // The server announces the other side right after attaching: its return is noticed again.
    this.present = false
    if (this.reopened) void this.check()
    this.reopened = true
  }

  /** After a reconnect: the call may have ended while this side was away. */
  private async check(): Promise<void> {
    let current: Current
    try {
      current = await this.deps.api.current()
    } catch {
      return
    }
    this.observe(current)
  }

  private frame(frame: ServerFrame): void {
    if (this.torn) return
    switch (frame.type) {
      case 'state':
        if (frame.state === 'ended') return this.over(frame.outcome)
        if (frame.state === 'active' && this.role === 'caller') {
          // The server repeats `active` after every reconnect: the deadline starts only once.
          if (!this.accepted) {
            this.accepted = true
            this.forget('ring')
            this.later('connect', CONNECT_MS, () => this.end('ice_timeout'))
          }
          this.offerWhenReady()
        }
        return
      case 'peer': {
        const returned = frame.present && !this.present
        this.present = frame.present
        if (!returned || this.role !== 'caller') return
        this.offerWhenReady()
        // The offer may have been lost while the other side was away: the same one again.
        if (this.described && !this.pc?.remoteDescription) this.describe('offer')
        return
      }
      case 'offer':
        if (this.role === 'callee') void this.answerOffer(frame.sdp)
        return
      case 'answer':
        if (this.role === 'caller') void this.applyAnswer(frame.sdp)
        return
    }
  }

  /** The caller offers once, when the call is active and the callee's socket is there. */
  private offerWhenReady(): void {
    if (this.step === 'outgoing' && this.accepted && this.present) void this.offer()
  }

  private async offer(): Promise<void> {
    this.go('connecting')
    try {
      const pc = this.peer()
      const [track] = this.stream!.getAudioTracks()
      pc.addTransceiver(track!, { direction: 'sendrecv', streams: [this.stream!] })
      await pc.setLocalDescription(await pc.createOffer())
      await gathered(pc, GATHER_MS, this.deps)
      if (this.torn) return
      this.described = true
      this.describe('offer')
    } catch {
      this.end('ice_failed')
    }
  }

  private async applyAnswer(sdp: string): Promise<void> {
    const pc = this.pc
    // Only the answer to the offer this side sent; anything else is stale.
    if (!pc || !this.described || this.answerApplied || pc.signalingState !== 'have-local-offer')
      return
    this.answerApplied = true
    try {
      await pc.setRemoteDescription({ type: 'answer', sdp })
    } catch {
      this.end('ice_failed')
    }
  }

  private async answerOffer(sdp: string): Promise<void> {
    if (this.pc) {
      // The caller sent its offer again: this side's answer was lost on the way.
      if (this.described) this.describe('answer')
      return
    }
    this.forget('offer')
    try {
      const pc = this.peer()
      await pc.setRemoteDescription({ type: 'offer', sdp })
      // Reuses the offered transceiver.
      const [track] = this.stream!.getAudioTracks()
      pc.addTrack(track!, this.stream!)
      await pc.setLocalDescription(await pc.createAnswer())
      await gathered(pc, GATHER_MS, this.deps)
      if (this.torn) return
      this.described = true
      this.describe('answer')
    } catch {
      this.end('ice_failed')
    }
  }

  private describe(type: 'offer' | 'answer'): void {
    const sdp = this.pc?.localDescription?.sdp
    if (!sdp) return
    this.update({ kinds: parseCandidates(sdp).kinds })
    this.socket?.send({ type, sdp })
  }

  private rtcConfiguration: RTCConfiguration = RTC_CONFIG

  private async configureIce(): Promise<boolean> {
    try {
      this.rtcConfiguration = this.deps.api.ice
        ? await this.deps.api.ice(this.call!.id)
        : RTC_CONFIG
      return !this.torn
    } catch (error) {
      if (!this.torn) {
        this.deps.notice(notice(error))
        this.end('ice_failed')
      }
      return false
    }
  }

  private peer(): RTCPeerConnection {
    const pc = this.deps.createPeerConnection(this.rtcConfiguration)
    this.pc = pc
    pc.ontrack = (event) => {
      this.remote?.addTrack(event.track)
      void this.play()
    }
    pc.onconnectionstatechange = () => this.connection(pc.connectionState)
    pc.oniceconnectionstatechange = () => this.iceChanged(pc.iceConnectionState)
    return pc
  }

  private async play(): Promise<void> {
    try {
      await this.audio.play()
      this.update({ autoplayBlocked: false })
    } catch (error) {
      if ((error as { name?: unknown } | null)?.name !== 'NotAllowedError' || this.torn) return
      this.blocked = true
      this.update({ autoplayBlocked: true })
    }
  }

  private connection(state: RTCPeerConnectionState): void {
    if (this.torn) return
    if (state === 'connected' && this.step === 'connecting') {
      this.forget('connect')
      this.forget('offer')
      this.step = 'connected'
      this.update({ phase: 'connected', connectedAt: this.deps.now() })
      void this.stats()
    } else if (state === 'connected' && this.step === 'unstable') {
      this.forget('unstable')
      this.go('connected')
    } else if (state === 'disconnected' && this.step === 'connected') {
      this.go('unstable')
      this.later('unstable', UNSTABLE_MS, () => this.end('media_lost'))
    } else if (state === 'failed') {
      // No ICE restart: before connecting it failed, after connecting the media was lost.
      if (this.step === 'connecting') this.end('ice_failed')
      else if (this.step === 'connected' || this.step === 'unstable') this.end('media_lost')
    }
  }

  private iceChanged(state: RTCIceConnectionState): void {
    if (state === 'checking' && this.ice === 'new') this.ice = 'checking'
    if (state === 'connected' || state === 'completed') this.ice = 'connected'
    this.update({ ice: state })
  }

  private async stats(): Promise<void> {
    const pc = this.pc
    if (!pc || this.torn) return
    try {
      this.sample = await sampleStats(pc, this.sample)
    } catch {
      // A closing connection may refuse; the next sample or the summary goes without it.
    }
    if (this.torn) return
    this.update({
      signal: this.socket?.connected ?? this.view.value.signal,
      selected: this.sample.selected,
      rttMs: this.sample.rtt.at(-1) ?? null,
      lossPct: this.sample.loss,
    })
    this.later('stats', STATS_MS, () => void this.stats())
  }

  private summary(): Summary {
    return buildSummary({
      client: this.deps.client,
      ice: this.ice,
      sdp: this.pc?.localDescription?.sdp,
      sample: this.sample,
      autoplayBlocked: this.blocked,
    })
  }

  /** This side ends the call: local teardown first, then the one `end` request. */
  private end(reason: EndReason): void {
    if (this.torn) return
    const summary = this.summary()
    const outcome: CallOutcome = FAILED.includes(reason)
      ? 'failed'
      : this.accepted
        ? 'completed'
        : this.role === 'caller'
          ? 'cancelled'
          : 'declined'
    this.release()
    this.finish(outcome, summary)
    this.post({ reason, summary })
  }

  /** The server ended the call: the summary still goes with an `end`, which changes nothing. */
  private over(outcome: CallOutcome | null): void {
    if (this.torn) return
    const summary = this.summary()
    this.release()
    this.finish(outcome, summary)
    this.post({ summary })
  }

  private replaced(): void {
    if (this.torn) return
    this.posted = true
    this.release()
    this.finish(null, null)
  }

  private finish(outcome: CallOutcome | null, summary: Summary | null): void {
    this.step = 'ended'
    this.update({ phase: 'ended', outcome, summary, signal: false, autoplayBlocked: false })
    this.deps.finished(this, 'ended')
  }

  private post(body: EndBody): void {
    if (this.posted) return
    if (!this.call) {
      // Hung up before the server named the call: sent if the start request still returns one.
      this.pending = body
      return
    }
    this.posted = true
    void this.send(this.call.id, body, this.deps.now())
  }

  /** Network errors and 5xx are retried for a minute; a 4xx is final. */
  private async send(id: string, body: EndBody, first: number): Promise<void> {
    for (let delay = END_BACKOFF_MS; ; delay = Math.min(delay * 2, END_BACKOFF_CAP_MS)) {
      try {
        const call = await this.deps.api.end(id, body)
        if (call.outcome) this.update({ outcome: call.outcome })
        return
      } catch (error) {
        if (!transient(error) || this.deps.now() + delay - first > END_RETRY_MS) return
        await new Promise<void>((resolve) => this.later('end', delay, resolve))
      }
    }
  }

  /** Stops everything this session holds; safe to call any number of times. */
  private release(): void {
    this.torn = true
    for (const track of this.stream?.getTracks() ?? []) track.stop()
    try {
      this.pc?.close()
    } catch {
      // Already closed.
    }
    this.socket?.close()
    this.audio.pause()
    this.audio.srcObject = null
    this.audio.remove()
    this.deps.keepAwake(false)
    for (const timer of this.timers.values()) this.deps.clearTimeout(timer)
    this.timers.clear()
  }

  private go(step: Step): void {
    this.step = step
    const phase: Phase =
      step === 'acquiring'
        ? this.role === 'caller'
          ? 'outgoing'
          : 'connecting'
        : step === 'starting'
          ? 'outgoing'
          : step === 'accepting'
            ? 'connecting'
            : step
    this.update({ phase })
  }

  private update(patch: Partial<CallView>): void {
    this.view.value = { ...this.view.value, ...patch }
  }

  private later(name: string, ms: number, run: () => void): void {
    this.deps.clearTimeout(this.timers.get(name))
    this.timers.set(
      name,
      this.deps.setTimeout(() => {
        this.timers.delete(name)
        run()
      }, ms),
    )
  }

  private forget(name: string): void {
    this.deps.clearTimeout(this.timers.get(name))
    this.timers.delete(name)
  }
}
