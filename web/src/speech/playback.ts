/** One explicit player across screens, with a bounded cache for this signed-in tab only. */
import { readonly, shallowRef } from 'vue'
import { api } from '@/core/client'
import {
  ApiError,
  NetworkError,
  type AudioStreamReply,
  type AudioFormat,
  type WriteOptions,
} from '@/core/http'
import { SpeechCache, type CachedSegment } from './cache'
import { PcmPlayback } from './pcm'

export interface PlaybackState {
  key: string | null
  status: 'idle' | 'loading' | 'playing'
  problem: string | null
}

type AudioRequest = (
  path: string,
  value: unknown,
  options: WriteOptions,
  onAudio: (chunk: Uint8Array, format: AudioFormat) => void,
  expected?: { segments: number; segment: number; cacheKey: string | null },
) => Promise<{ data: AudioStreamReply }>
type PrepareRequest = (
  path: string,
  value: unknown,
  options: WriteOptions,
) => Promise<{ data: unknown }>

interface PlaybackRun {
  controller: AbortController
  context: AudioContext | null
  closing?: Promise<void>
  pcm?: PcmPlayback
  connection?: string
  outputError?: unknown
}

const KNOWN_ERRORS = new Set([
  'not_configured',
  'no_access',
  'auth_required',
  'application_limit',
  'provider_limit',
  'busy',
  'timeout',
  'connection',
  'provider_error',
  'secret_unavailable',
  'execution_disabled',
])

function problem(error: unknown): string {
  if (error instanceof ApiError) {
    if (error.status === 401) return 'session_expired'
    return KNOWN_ERRORS.has(error.code ?? '') ? error.code! : 'provider_error'
  }
  if (error instanceof NetworkError) return error.timedOut ? 'timeout' : 'connection'
  return 'playback'
}

function invalidResponse(): ApiError {
  return new ApiError(502, 'provider_error', null, null, null)
}

function descriptor(value: unknown) {
  const data = value as { cache_key?: unknown; segments?: unknown; connection_id?: unknown } | null
  if (
    !data ||
    typeof data.cache_key !== 'string' ||
    !/^[a-f0-9]{64}$/.test(data.cache_key) ||
    typeof data.segments !== 'number' ||
    !Number.isSafeInteger(data.segments) ||
    data.segments < 1 ||
    typeof data.connection_id !== 'string' ||
    !data.connection_id
  )
    throw invalidResponse()
  return { key: data.cache_key, segments: data.segments, connection: data.connection_id }
}

export class SpeechPlayback {
  private readonly current = shallowRef<PlaybackState>({ key: null, status: 'idle', problem: null })
  readonly state = readonly(this.current)
  private run: PlaybackRun | null = null
  private userId: string | undefined
  private mutation = 0
  private readonly invalidated = new Map<string, number>()

  constructor(
    private readonly request: AudioRequest = (path, value, options, onAudio, expected) =>
      api.postAudioStream(path, value, options, onAudio, expected),
    private readonly context: () => AudioContext = () => new AudioContext(),
    private readonly prepare: PrepareRequest = (path, value, options) =>
      api.post(path, value, options),
    private readonly cache = new SpeechCache(),
  ) {}

  account(userId: string | undefined): void {
    if (this.userId === userId) return
    this.stop()
    this.cache.clear()
    this.invalidated.clear()
    this.userId = userId
  }

  invalidateConnection(connection: string): void {
    this.invalidated.set(connection, ++this.mutation)
    this.cache.invalidateConnection(connection)
    if (this.run?.connection === connection) this.stop()
  }

  stop(): void {
    const run = this.run
    this.run = null
    run?.controller.abort()
    if (run) void this.close(run)
    this.current.value = { key: null, status: 'idle', problem: null }
  }

  turn(turnId: string): Promise<void> {
    return this.play(
      `turn:${turnId}`,
      `/v1/conversation/turns/${encodeURIComponent(turnId)}/speech`,
      {
        playback_id: crypto.randomUUID(),
      },
    )
  }

  preview(connectionId: string, voice: string, model?: string | null): Promise<void> {
    return this.play(
      'preview',
      '/v1/me/speech/preview',
      {
        connection_id: connectionId,
        voice,
        ...(model ? { tts_model: model } : {}),
      },
      connectionId,
    )
  }

  private async play(
    key: string,
    path: string,
    body: object,
    previewConnection?: string,
  ): Promise<void> {
    this.stop()
    const run: PlaybackRun = {
      controller: new AbortController(),
      context: null,
      connection: previewConnection,
    }
    this.run = run
    const user = this.userId
    const started = this.mutation
    const { signal } = run.controller
    const options = { signal, expectedUser: user }
    this.current.value = { key, status: 'loading', problem: null }
    let staged: CachedSegment[] = []
    try {
      // Resume within the click gesture, before either metadata or audio network requests.
      const context = this.context()
      run.context = context
      await context.resume()
      signal.throwIfAborted()
      const plan =
        key === 'preview'
          ? null
          : descriptor((await this.prepare(`${path}/prepare`, {}, options)).data)
      signal.throwIfAborted()
      if (plan) run.connection = plan.connection
      if ((this.invalidated.get(run.connection ?? '') ?? 0) > started) {
        this.stop()
        return
      }
      const cached = plan && user ? this.cache.get(plan.key, plan.connection) : undefined
      if (cached && cached.length === plan?.segments) {
        for (const segment of cached) {
          signal.throwIfAborted()
          await this.outputSegment(run, key, segment)
        }
      } else {
        const total = plan?.segments ?? 1
        let stagedBytes = 0
        let cacheable = !!plan && !!user
        const cacheComplete = () => {
          signal.throwIfAborted()
          if (cacheable && plan && user === this.userId && staged.length === total)
            this.cache.put(plan.key, plan.connection, staged)
          staged = []
          cacheable = false
        }
        for (let segment = 0; segment < total; segment++) {
          signal.throwIfAborted()
          this.current.value = { key, status: 'loading', problem: null }
          const { data } = await this.request(
            path,
            plan ? { ...body, segment, cache_key: plan.key } : body,
            options,
            (chunk, format) => {
              signal.throwIfAborted()
              if (format.contentType !== 'audio/pcm') return
              if (!run.pcm) run.pcm = this.pcm(run, key, format.sampleRate ?? 24000)
              run.pcm.write(chunk)
            },
            { segment, segments: total, cacheKey: plan?.key ?? null },
          )
          signal.throwIfAborted()
          if (
            data.segment !== segment ||
            data.segments !== total ||
            (plan && data.cacheKey !== plan.key)
          )
            throw invalidResponse()
          if (cacheable) {
            stagedBytes += data.audio.byteLength
            if (stagedBytes > this.cache.maxBytes) {
              staged = []
              cacheable = false
            } else staged.push(data)
          }
          if (run.pcm) {
            if (segment === total - 1) cacheComplete()
            await run.pcm.finish()
            run.pcm = undefined
          } else
            await this.outputSegment(
              run,
              key,
              data,
              segment === total - 1 ? cacheComplete : undefined,
            )
        }
        signal.throwIfAborted()
      }
      if (this.run === run) this.current.value = { key: null, status: 'idle', problem: null }
    } catch (error) {
      run.pcm?.cancel(error)
      if (error instanceof ApiError && error.status === 401) this.cache.clear()
      if (this.run === run && (!signal.aborted || run.outputError !== undefined))
        this.current.value = { key, status: 'idle', problem: problem(run.outputError ?? error) }
    } finally {
      staged = []
      await this.close(run)
      if (this.run === run) this.run = null
    }
  }

  private pcm(run: PlaybackRun, key: string, sampleRate: number): PcmPlayback {
    return new PcmPlayback(
      run.context!,
      sampleRate,
      run.controller.signal,
      (playing) => {
        if (this.run === run && !run.controller.signal.aborted)
          this.current.value = { key, status: playing ? 'playing' : 'loading', problem: null }
      },
      (error) => {
        if (this.run !== run || run.controller.signal.aborted) return
        run.outputError = error
        run.controller.abort(error)
        this.current.value = { key, status: 'idle', problem: 'playback' }
        void this.close(run)
      },
    )
  }

  private async outputSegment(
    run: PlaybackRun,
    key: string,
    audio: CachedSegment,
    decoded?: () => void,
  ): Promise<void> {
    const { signal } = run.controller
    signal.throwIfAborted()
    if (audio.contentType === 'audio/pcm') {
      decoded?.()
      run.pcm = this.pcm(run, key, audio.sampleRate ?? 24000)
      run.pcm.write(audio.audio)
      await run.pcm.finish()
      run.pcm = undefined
      return
    }
    const context = run.context!
    const buffer = await context.decodeAudioData(audio.audio.slice().buffer)
    signal.throwIfAborted()
    decoded?.()
    this.current.value = { key, status: 'playing', problem: null }
    await this.output(context, buffer, signal)
  }

  private close(run: PlaybackRun): Promise<void> {
    run.pcm?.cancel()
    if (!run.closing)
      run.closing =
        run.context && run.context.state !== 'closed'
          ? run.context.close().catch(() => undefined)
          : Promise.resolve()
    return run.closing
  }

  private output(context: AudioContext, buffer: AudioBuffer, signal: AbortSignal): Promise<void> {
    return new Promise((resolve, reject) => {
      const source = context.createBufferSource()
      source.buffer = buffer
      source.connect(context.destination)
      const clean = () => {
        signal.removeEventListener('abort', abort)
        source.onended = null
        source.disconnect()
      }
      const abort = () => {
        clean()
        source.stop()
        reject(signal.reason)
      }
      source.onended = () => {
        clean()
        resolve()
      }
      signal.addEventListener('abort', abort, { once: true })
      source.start()
    })
  }
}

export const speechPlayback = new SpeechPlayback()
