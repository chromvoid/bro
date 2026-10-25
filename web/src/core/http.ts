/** The only way the app talks to /v1: session cookie, CSRF, idempotency keys and timeouts. */
import {
  AudioStreamError,
  readAudioStream,
  type AudioChunk,
  type AudioStreamReply,
  type AudioStreamExpectation,
} from './audio-stream'
export type {
  AudioFormat,
  AudioStreamReply,
  AudioChunk,
  AudioStreamExpectation,
} from './audio-stream'

export const TIMEOUT = { read: 15_000, write: 30_000 } as const

/** Evidence about this invocation only; it cannot settle an earlier lost write. */
export type WriteAttempt = 'not_dispatched' | 'refused' | 'unknown'
const attempts = new WeakMap<object, WriteAttempt>()
export function writeAttempt(error: unknown): WriteAttempt {
  return error instanceof Object ? (attempts.get(error) ?? 'unknown') : 'unknown'
}

/** Transport facts only: no URLs, request bodies, exception messages or credentials. */
export interface RequestDiagnostic {
  kind: 'network' | 'timeout' | 'http'
  phase: 'csrf' | 'request' | 'response'
  elapsedMs: number
  status: number | null
  traceId: string | null
}

/** The server answered with an error status; the request reached a handler or a guard. */
export class ApiError extends Error {
  constructor(
    readonly status: number,
    readonly code: string | null,
    readonly detail: unknown,
    readonly retryAfter: string | null,
    readonly requestId: string | null,
    /** A user-facing explanation the server wrote (validation, conflicts, sign-in). */
    readonly serverMessage: string | null = null,
    readonly diagnostic?: RequestDiagnostic,
  ) {
    super(`HTTP ${status}${code ? ` ${code}` : ''}`)
  }
}

/** No response arrived (offline, timeout, reset). For a write the outcome is unknown. */
export class NetworkError extends Error {
  constructor(
    readonly timedOut: boolean,
    readonly diagnostic?: RequestDiagnostic,
  ) {
    super(timedOut ? 'timeout' : 'network')
  }
}

export interface Reply<T = unknown> {
  status: number
  data: T
}

export interface AudioReply {
  audio: Blob
  segments: number
  segment: number
}

export interface WriteOptions {
  key?: string
  ifMatch?: string
  signal?: AbortSignal
  /** Revalidate the caller after asynchronous CSRF work, including a refreshed-token retry. */
  beforeSend?: () => void
  /** The server rejects a cookie belonging to another account before executing the write. */
  expectedUser?: string
}

type Method = 'POST' | 'PUT' | 'PATCH' | 'DELETE'
type ResponseType = 'json' | 'audio' | 'audio-stream'

export interface ApiHooks {
  /** A 401 means the session ended; the app routes to login. */
  unauthorized?: () => void
  /** Every API response names the server's current web build. */
  build?: (build: string) => void
}

export class Api {
  private csrf: string | null = null
  private session = new AbortController()

  constructor(
    private readonly fetcher: typeof fetch = (input, init) => fetch(input, init),
    public hooks: ApiHooks = {},
  ) {}

  /** Drops the CSRF token; the next write fetches a fresh one for the current session. */
  forget(): void {
    this.session.abort(new DOMException('Session changed', 'AbortError'))
    this.session = new AbortController()
    this.csrf = null
  }

  async get<T = unknown>(path: string, signal?: AbortSignal): Promise<T> {
    const reply = await this.request<T>('GET', path, undefined, {}, signal, TIMEOUT.read)
    return reply.data
  }

  /** Sends exactly [body]; a journaled write passes its stored bytes so a resend is identical. */
  async send<T = unknown>(
    method: Method,
    path: string,
    body: string | undefined,
    options: WriteOptions = {},
  ): Promise<Reply<T>> {
    const headers: Record<string, string> = {}
    if (body !== undefined) headers['Content-Type'] = 'application/json'
    if (options.key) headers['Idempotency-Key'] = options.key
    if (options.ifMatch) headers['If-Match'] = options.ifMatch
    if (options.expectedUser) headers['X-Expected-User'] = options.expectedUser
    return this.write<T>(method, path, body, headers, options.signal, options.beforeSend)
  }

  post<T = unknown>(path: string, value: unknown, options: WriteOptions = {}): Promise<Reply<T>> {
    return this.send<T>('POST', path, JSON.stringify(value), options)
  }

  /** Protected, ephemeral audio: the body is never decoded as text or persisted in a journal. */
  postAudio(path: string, value: unknown, options: WriteOptions = {}): Promise<Reply<AudioReply>> {
    const headers: Record<string, string> = { 'Content-Type': 'application/json' }
    if (options.expectedUser) headers['X-Expected-User'] = options.expectedUser
    return this.write<AudioReply>(
      'POST',
      path,
      JSON.stringify(value),
      headers,
      options.signal,
      options.beforeSend,
      'audio',
    )
  }

  /** Streamed audio uses the same session guards and never enters the write journal. */
  postAudioStream(
    path: string,
    value: unknown,
    options: WriteOptions = {},
    onAudio?: AudioChunk,
    expected?: AudioStreamExpectation,
  ): Promise<Reply<AudioStreamReply>> {
    const headers: Record<string, string> = {
      'Content-Type': 'application/json',
      Accept: 'application/x-ndjson',
    }
    if (options.expectedUser) headers['X-Expected-User'] = options.expectedUser
    return this.write<AudioStreamReply>(
      'POST',
      path,
      JSON.stringify(value),
      headers,
      options.signal,
      options.beforeSend,
      'audio-stream',
      onAudio,
      expected,
    )
  }

  /** A write the server accepts without a session (sign-in, invitation); no CSRF token. */
  postPublic<T = unknown>(path: string, value: unknown, signal?: AbortSignal): Promise<Reply<T>> {
    return this.request<T>(
      'POST',
      path,
      JSON.stringify(value),
      { 'Content-Type': 'application/json' },
      signal,
      TIMEOUT.write,
    )
  }

  /** Raw upload, e.g. a normalized JPEG for /v1/conversation/images. */
  upload<T = unknown>(path: string, blob: Blob, options: WriteOptions = {}): Promise<Reply<T>> {
    const headers: Record<string, string> = { 'Content-Type': blob.type }
    if (options.expectedUser) headers['X-Expected-User'] = options.expectedUser
    return this.write<T>('POST', path, blob, headers, options.signal, options.beforeSend)
  }

  private async write<T>(
    method: Method,
    path: string,
    body: BodyInit | undefined,
    headers: Record<string, string>,
    signal?: AbortSignal,
    beforeSend?: () => void,
    responseType: ResponseType = 'json',
    onAudio?: AudioChunk,
    expectedAudio?: AudioStreamExpectation,
  ): Promise<Reply<T>> {
    let disposition: WriteAttempt = 'not_dispatched'
    const owner = this.session.signal
    const current = () => {
      owner.throwIfAborted()
      signal?.throwIfAborted()
      beforeSend?.()
    }
    try {
      current()
      const token = await this.token(signal)
      current()
      disposition = 'unknown'
      try {
        return await this.request<T>(
          method,
          path,
          body,
          { ...headers, 'X-CSRF-Token': token },
          signal,
          responseType === 'json' ? TIMEOUT.write : 65_000,
          'request',
          responseType,
          onAudio,
          expectedAudio,
        )
      } catch (error) {
        // A CSRF refusal proves that this attempt did not reach the write handler.
        if (!(error instanceof ApiError) || error.status !== 403) throw error
        // Speech providers can refuse after generation has been dispatched. Only the
        // legacy bare CSRF guard proves a speech request can be sent again safely.
        if (responseType !== 'json' && error.code !== null && error.code !== 'account_changed')
          throw error
        disposition = 'refused'
        if (error.code === 'account_changed') throw error
        current()
        this.csrf = null
        const fresh = await this.token(signal)
        current()
        if (fresh === token) throw error
        disposition = 'unknown'
        return await this.request<T>(
          method,
          path,
          body,
          { ...headers, 'X-CSRF-Token': fresh },
          signal,
          responseType === 'json' ? TIMEOUT.write : 65_000,
          'request',
          responseType,
          onAudio,
          expectedAudio,
        )
      }
    } catch (error) {
      // AbortSignal.reason can be the SAME object in several concurrent writes. Facts must
      // belong to this invocation, so never annotate the shared underlying exception.
      const failure =
        error instanceof ApiError
          ? new ApiError(
              error.status,
              error.code,
              error.detail,
              error.retryAfter,
              error.requestId,
              error.serverMessage,
              error.diagnostic,
            )
          : error instanceof NetworkError
            ? new NetworkError(error.timedOut, error.diagnostic)
            : new Error(error instanceof Error ? error.message : 'Write failed', { cause: error })
      if (error instanceof Error) failure.name = error.name
      attempts.set(failure, disposition)
      throw failure
    }
  }

  private async token(signal?: AbortSignal): Promise<string> {
    if (this.csrf) return this.csrf
    const owner = this.session.signal
    const {
      data: { token },
    } = await this.request<{ token: string }>(
      'GET',
      '/v1/auth/csrf',
      undefined,
      {},
      signal,
      TIMEOUT.read,
      'csrf',
    )
    owner.throwIfAborted()
    if (typeof token !== 'string' || !token) throw new Error('Invalid CSRF response')
    this.csrf = token
    return token
  }

  private async request<T>(
    method: string,
    path: string,
    body: BodyInit | undefined,
    headers: Record<string, string>,
    signal: AbortSignal | undefined,
    timeout: number,
    initialPhase: RequestDiagnostic['phase'] = 'request',
    responseType: ResponseType = 'json',
    onAudio?: AudioChunk,
    expectedAudio?: AudioStreamExpectation,
  ): Promise<Reply<T>> {
    if (!path.startsWith('/v1/') || path.includes('#')) throw new Error(`Not an API path: ${path}`)
    const owner = this.session.signal
    owner.throwIfAborted()
    signal?.throwIfAborted()
    const deadline = AbortSignal.timeout(timeout)
    const pending = AbortSignal.any([owner, deadline, ...(signal ? [signal] : [])])
    const started = performance.now()
    let phase = initialPhase
    let response: Response | undefined
    const diagnostic = (kind: RequestDiagnostic['kind']): RequestDiagnostic => ({
      kind,
      phase,
      elapsedMs: Math.max(0, Math.round(performance.now() - started)),
      status: response?.status ?? null,
      traceId: traceId(response),
    })
    let text = ''
    let audio: AudioReply | AudioStreamReply | undefined
    try {
      response = await this.fetcher(path, {
        method,
        body,
        headers,
        credentials: 'same-origin',
        redirect: 'error',
        signal: pending,
      })
      if (initialPhase !== 'csrf') phase = 'response'
      if (response.ok && responseType === 'audio-stream') {
        audio = await readAudioStream(response, pending, onAudio, expectedAudio)
      } else if (response.ok && responseType === 'audio') {
        const blob = await response.blob()
        const segments = Number(response.headers.get('X-Speech-Segments') ?? '1')
        const segment = Number(response.headers.get('X-Speech-Segment') ?? '0')
        if (
          !blob.type.startsWith('audio/') ||
          !blob.size ||
          !Number.isSafeInteger(segments) ||
          segments < 1 ||
          !Number.isSafeInteger(segment) ||
          segment < 0 ||
          segment >= segments
        )
          throw new Error('Invalid audio response')
        audio = { audio: blob, segments, segment }
      } else text = await response.text()
      signal?.throwIfAborted()
      owner.throwIfAborted()
    } catch (error) {
      owner.throwIfAborted()
      if (signal?.aborted) throw error
      if (error instanceof AudioStreamError)
        throw new ApiError(502, error.code, null, null, null, null, diagnostic('http'))
      throw new NetworkError(deadline.aborted, diagnostic(deadline.aborted ? 'timeout' : 'network'))
    }
    const build = response.headers.get('X-Client-Build')
    if (build) this.hooks.build?.(build)
    const data: unknown = audio ?? (text ? parse(text) : null)
    if (response.ok) return { status: response.status, data: data as T }
    if (response.status === 401) {
      this.csrf = null
      this.hooks.unauthorized?.()
    }
    const fields =
      typeof data === 'object' && data !== null ? (data as Record<string, unknown>) : {}
    throw new ApiError(
      response.status,
      typeof fields.error === 'string' ? fields.error : null,
      fields.detail ?? null,
      response.headers.get('Retry-After'),
      typeof fields.request_id === 'string' ? fields.request_id : null,
      typeof fields.message === 'string'
        ? fields.message
        : typeof data === 'string' && data.length < 300
          ? data
          : null,
      diagnostic('http'),
    )
  }
}

function traceId(response?: Response): string | null {
  const value = response?.headers.get('X-Trace-ID')
  return value && /^[a-f0-9]{32}$/i.test(value) ? value.toLowerCase() : null
}

function parse(text: string): unknown {
  try {
    return JSON.parse(text)
  } catch {
    return text
  }
}
