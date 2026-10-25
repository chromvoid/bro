/**
 * One dictation from the microphone to a final transcript. Nothing is sent as a message:
 * the result only fills the draft, and the user decides whether to send it.
 *
 * Idle → Permission → Recording → Finalizing → Review, or Cancelled,
 * Interrupted (connection, device or the page went away) and Failed.
 */
import { ApiError, NetworkError } from '@/core/http'
import { live, type LiveSocket } from '@/core/live'
import { MicrophoneError, type AudioSource, type MicrophoneProblem } from './audio'

export type DictationState =
  | 'idle'
  | 'permission'
  | 'recording'
  | 'finalizing'
  | 'review'
  | 'cancelled'
  | 'interrupted'
  | 'failed'

/** Why a dictation stopped short; each maps to a message and a way forward. */
export type DictationProblem =
  | MicrophoneProblem
  | 'not_configured'
  | 'no_access'
  | 'auth_required'
  | 'session_expired'
  | 'application_limit'
  | 'provider_limit'
  | 'timeout'
  | 'provider_error'
  | 'invalid_audio'
  | 'secret_unavailable'
  | 'execution_disabled'
  | 'busy'
  | 'connection'
  | 'limit'
  | 'too_long'
  | 'server'
  | 'backgrounded'
  | 'device'

export interface DictationFailure {
  action?: string
  retryAfter?: number
  traceId?: string
}

export interface SpeechAvailability {
  asr: { state: 'configured' | 'unavailable'; code?: string; action?: string; retry_after?: number }
}

export interface DictationEvents {
  state(state: DictationState, problem?: DictationProblem, detail?: DictationFailure): void
  /** The running transcript so far; it replaces, not extends, the previous partial. */
  partial(text: string): void
  final(result: { text: string; transcriptionId: string }): void
}

export interface DictationOptions {
  preflight?: (signal: AbortSignal) => Promise<SpeechAvailability>
  live?: LiveSocket
  readyTimeout?: number
  finalTimeout?: number
}

const MAX_BUFFERED = 32_000 * 10
const MIN_CAPTURED = 32_000 * 0.7
const SERVER_PROBLEMS: Record<string, DictationProblem> = {
  transcript_too_long: 'too_long',
  asr_busy: 'busy',
  limit_exceeded: 'application_limit',
  not_configured: 'not_configured',
  no_access: 'no_access',
  auth_required: 'auth_required',
  session_expired: 'session_expired',
  application_limit: 'application_limit',
  provider_limit: 'provider_limit',
  busy: 'busy',
  timeout: 'timeout',
  connection: 'connection',
  provider_error: 'provider_error',
  invalid_audio: 'invalid_audio',
  too_long: 'too_long',
  secret_unavailable: 'secret_unavailable',
  execution_disabled: 'execution_disabled',
}

export function dictationProblem(error: unknown): DictationProblem {
  if (error instanceof ApiError) {
    if (error.status === 401) return 'session_expired'
    return SERVER_PROBLEMS[error.code ?? ''] ?? 'server'
  }
  return error instanceof NetworkError && error.timedOut ? 'timeout' : 'connection'
}

export class DictationSession {
  private readonly live: LiveSocket
  private id = crypto.randomUUID()
  private unsubscribe: (() => void) | undefined
  private startSent = false
  private controller = new AbortController()
  private current: DictationState = 'idle'
  private finished = false
  private finishSent = false
  private captureStopped = false
  private serverReady = false
  private capturedBytes = 0
  private bufferedBytes = 0
  private readonly frames: ArrayBuffer[] = []
  private readonly audio: ArrayBuffer[] = []
  private retryable = false
  private replaying = false
  private drain: ReturnType<typeof setTimeout> | undefined
  private timer: ReturnType<typeof setTimeout> | undefined

  constructor(
    private readonly source: AudioSource,
    private readonly events: DictationEvents,
    private readonly options: DictationOptions = {},
  ) {
    this.live = options.live ?? live
  }

  get state(): DictationState {
    return this.current
  }

  get active(): boolean {
    return ['permission', 'recording', 'finalizing'].includes(this.current)
  }

  get canRetry(): boolean {
    return this.retryable && this.audio.length > 0
  }

  async start(): Promise<void> {
    if (this.current !== 'idle') throw new Error('A dictation session starts once')
    this.move('permission')
    // Ask for the microphone inside the tap; readiness must never hold up capture.
    const preparing = this.source.prepare()
    if (this.options.preflight) void this.checkReadiness()
    this.connect()
    try {
      await preparing
    } catch (error) {
      // A permission prompt or audio setup can reject after this session was cancelled.
      if (!this.is('permission')) return this.source.release()
      return this.end('failed', error instanceof MicrophoneError ? error.problem : 'unavailable')
    }
    // Cancelled while the permission prompt was open.
    if (!this.is('permission')) return this.source.release()
    this.move('recording')
    try {
      this.source.begin({
        frame: (pcm) => this.send(pcm),
        limit: () => {
          this.events.state('recording', 'limit')
          void this.finish()
        },
        interrupted: () => this.interrupt('device'),
      })
    } catch {
      return this.end('failed', 'unavailable')
    }
    if (this.active && !this.serverReady) this.waitForReady()
  }

  /** Replay the stopped recording as a new dictation, without opening the microphone. */
  retry(): void {
    if (!this.canRetry) return
    this.retryable = false
    this.replaying = true
    this.id = crypto.randomUUID()
    this.controller = new AbortController()
    this.startSent = this.finishSent = this.serverReady = false
    this.captureStopped = true
    this.frames.push(...this.audio)
    this.bufferedBytes = this.capturedBytes
    this.move('finalizing')
    this.waitForReady()
    if (this.options.preflight) void this.checkReadiness()
    this.connect()
  }

  private waitForReady(): void {
    this.timer = setTimeout(
      () => this.end('failed', 'timeout'),
      this.options.readyTimeout ?? 35_000,
    )
  }

  private connect(): void {
    this.unsubscribe = this.live.subscribe({
      open: () => this.startDictation(),
      message: (message) => this.received(message),
      close: (reason) => {
        if (reason === 'backgrounded') this.interrupt(reason)
        else this.end(this.serverReady ? 'interrupted' : 'failed', 'connection')
      },
    })
    this.live.ensure()
    if (this.live.connected) this.startDictation()
  }

  private startDictation(): void {
    if (!this.active || this.startSent) return
    this.startSent = this.live.send(JSON.stringify({ type: 'dictation.start', id: this.id }))
    if (!this.startSent) this.end('failed', 'connection')
  }

  private async checkReadiness(): Promise<void> {
    const signal = this.controller.signal
    try {
      const { asr } = await this.options.preflight!(signal)
      if (!this.active || signal.aborted) return
      if (asr.state !== 'configured')
        return this.end('failed', SERVER_PROBLEMS[asr.code ?? ''] ?? 'not_configured', {
          action: asr.action,
          retryAfter: retryAfter(asr.retry_after),
        })
    } catch (error) {
      if (this.active && !signal.aborted) this.end('failed', dictationProblem(error))
    }
  }

  /** Stop capture now; a late ready still flushes the audio before the one finish request. */
  async finish(): Promise<void> {
    if (this.current === 'permission') return this.end('cancelled')
    if (this.current !== 'recording' || this.finished) return
    this.finished = true
    this.move('finalizing')
    try {
      await this.source.stop()
    } catch {
      // The last frame may be lost; the transcript of what arrived is still useful.
    }
    this.captureStopped = true
    this.source.release()
    if (!this.is('finalizing')) return
    if (this.capturedBytes < MIN_CAPTURED) return this.end('cancelled')
    this.flush()
  }

  cancel(): void {
    this.audio.length = 0
    if (!this.active) return
    this.end('cancelled')
  }

  /** The page was hidden or the audio session was taken: stop without resuming later. */
  interrupt(problem: DictationProblem = 'backgrounded'): void {
    if (!this.active) return
    this.end('interrupted', problem)
  }

  private received(message: Record<string, unknown>): void {
    if (!this.active || message.id !== this.id) return
    switch (message.type) {
      case 'dictation.ready':
        if (this.serverReady) return
        if (message.sample_rate !== undefined && message.sample_rate !== 16000)
          return this.end('failed', 'server')
        this.serverReady = true
        clearTimeout(this.timer)
        if (this.replaying)
          this.timer = setTimeout(
            () => this.end('interrupted', 'timeout'),
            this.options.finalTimeout ?? 65_000,
          )
        this.flush()
        return
      case 'dictation.partial':
        if (typeof message.text === 'string') this.events.partial(message.text)
        return
      case 'dictation.final':
        if (!this.finishSent || this.current !== 'finalizing' || typeof message.text !== 'string')
          return
        if (!message.text.trim() || this.capturedBytes === 0) return this.end('cancelled')
        if (typeof message.transcription_id !== 'string') return this.end('failed', 'server')
        this.events.final({ text: message.text, transcriptionId: message.transcription_id })
        return this.end('review')
      case 'dictation.error':
        if (message.error === 'empty_transcript') return this.end('cancelled')
        return this.end('failed', SERVER_PROBLEMS[String(message.error)] ?? 'server', {
          action: typeof message.action === 'string' ? message.action : undefined,
          retryAfter: retryAfter(message.retry_after),
          traceId:
            typeof message.trace_id === 'string' && /^[a-f0-9]{32}$/i.test(message.trace_id)
              ? message.trace_id
              : undefined,
        })
      default:
        return this.end('failed', 'server')
    }
  }

  private send(pcm: ArrayBuffer): void {
    // The last, shorter frame is flushed after Stop, so audio is accepted until stop completes.
    const capturing =
      this.current === 'recording' || (this.current === 'finalizing' && !this.captureStopped)
    if (!capturing) return
    this.capturedBytes += pcm.byteLength
    this.audio.push(pcm)
    if (this.bufferedBytes + pcm.byteLength > MAX_BUFFERED)
      return this.end('interrupted', 'connection')
    this.frames.push(pcm)
    this.bufferedBytes += pcm.byteLength
    this.flush()
  }

  private flush(): void {
    if (!this.active || !this.serverReady) return
    // Frames enter and leave this FIFO synchronously, including frames flushed by stop().
    while (this.frames.length) {
      const pcm = this.frames[0]!
      if (this.replaying && this.live.bufferedAmount + pcm.byteLength > MAX_BUFFERED) {
        this.drain = setTimeout(() => this.flush(), 50)
        return
      }
      // Never drop samples silently: a stalled connection ends the recording instead.
      if (this.live.bufferedAmount > MAX_BUFFERED || !this.live.send(pcm))
        return this.end('interrupted', 'connection')
      this.frames.shift()
      this.bufferedBytes -= pcm.byteLength
    }
    if (!this.captureStopped || this.finishSent) return
    this.finishSent = true
    if (!this.live.send(JSON.stringify({ type: 'dictation.finish', id: this.id })))
      return this.end('interrupted', 'connection')
    clearTimeout(this.timer)
    this.timer = setTimeout(
      () => this.end('interrupted', 'timeout'),
      this.options.finalTimeout ?? 65_000,
    )
  }

  /** Reads the state afresh: awaits in between may have changed it. */
  private is(state: DictationState): boolean {
    return this.current === state
  }

  private move(state: DictationState, problem?: DictationProblem, detail?: DictationFailure): void {
    this.current = state
    this.events.state(state, problem, detail)
  }

  private end(state: DictationState, problem?: DictationProblem, detail?: DictationFailure): void {
    this.controller.abort()
    clearTimeout(this.timer)
    clearTimeout(this.drain)
    this.retryable =
      this.finished &&
      this.capturedBytes >= MIN_CAPTURED &&
      (state === 'failed' || state === 'interrupted') &&
      ['connection', 'timeout', 'provider_error', 'server', 'busy'].includes(problem ?? '')
    if (!this.retryable) this.audio.length = 0
    this.source.release()
    this.frames.length = 0
    this.bufferedBytes = 0
    this.unsubscribe?.()
    this.unsubscribe = undefined
    if (this.startSent && state !== 'review')
      this.live.send(JSON.stringify({ type: 'dictation.cancel', id: this.id }))
    this.move(state, problem, detail)
  }
}

function retryAfter(value: unknown): number | undefined {
  return typeof value === 'number' && Number.isFinite(value) && value >= 0
    ? Math.ceil(value)
    : undefined
}
