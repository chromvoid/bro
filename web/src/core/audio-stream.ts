/** Bounded speech frames. A recording is complete only after a terminal done frame and EOF. */
export interface AudioFormat {
  contentType: 'audio/pcm' | 'audio/wav' | 'audio/mpeg'
  sampleRate?: number
  channels?: number
}

export interface AudioStreamExpectation {
  segments: number
  segment: number
  cacheKey: string | null
}

export interface AudioStreamReply extends AudioFormat, AudioStreamExpectation {
  audio: Uint8Array
}

export type AudioChunk = (chunk: Uint8Array, format: AudioFormat) => void

export const AUDIO_STREAM_LIMIT = 10 * 1024 * 1024
export const AUDIO_LINE_LIMIT = 1024 * 1024
const FRAME_LIMIT = 16_384
const SAFE_CODES = new Set([
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

/** Carries no provider text, frame data, audio, credentials or parser diagnostics. */
export class AudioStreamError extends Error {
  readonly code: string
  constructor(code = 'provider_error') {
    super('Speech stream failed')
    this.code = SAFE_CODES.has(code) ? code : 'provider_error'
  }
}

function invalid(): never {
  throw new AudioStreamError()
}

function format(frame: Record<string, unknown>): AudioFormat {
  const contentType = frame.content_type
  if (contentType !== 'audio/pcm' && contentType !== 'audio/wav' && contentType !== 'audio/mpeg')
    return invalid()
  if (contentType === 'audio/pcm') {
    if (frame.sample_rate !== 24000 || frame.channels !== 1) return invalid()
    return { contentType, sampleRate: 24000, channels: 1 }
  }
  return { contentType }
}

function decode(value: unknown): Uint8Array {
  if (
    typeof value !== 'string' ||
    !value.length ||
    value.length % 4 !== 0 ||
    !/^(?:[A-Za-z0-9+/]{4})*(?:[A-Za-z0-9+/]{2}==|[A-Za-z0-9+/]{3}=)?$/.test(value)
  )
    return invalid()
  const binary = atob(value)
  return Uint8Array.from(binary, (character) => character.charCodeAt(0))
}

export async function readAudioStream(
  response: Response,
  signal: AbortSignal,
  onAudio?: AudioChunk,
  expected?: AudioStreamExpectation,
): Promise<AudioStreamReply> {
  const segments = Number(response.headers.get('X-Speech-Segments') ?? '1')
  const segment = Number(response.headers.get('X-Speech-Segment') ?? '0')
  const cacheKey = response.headers.get('X-Speech-Cache-Key')
  const contentType = response.headers.get('Content-Type')?.split(';')[0]?.trim()
  if (
    contentType !== 'application/x-ndjson' ||
    !response.body ||
    !Number.isSafeInteger(segments) ||
    segments < 1 ||
    !Number.isSafeInteger(segment) ||
    segment < 0 ||
    segment >= segments ||
    (cacheKey !== null && !/^[a-f0-9]{64}$/.test(cacheKey)) ||
    (expected !== undefined &&
      (segments !== expected.segments ||
        segment !== expected.segment ||
        cacheKey !== expected.cacheKey))
  ) {
    void response.body?.cancel().catch(() => undefined)
    return invalid()
  }
  signal.throwIfAborted()
  const reader = response.body.getReader()
  const cancel = () => {
    void reader.cancel(signal.reason).catch(() => undefined)
  }
  signal.addEventListener('abort', cancel, { once: true })
  const decoder = new TextDecoder('utf-8', { fatal: true })
  const chunks: Uint8Array[] = []
  let audioFormat: AudioFormat | undefined
  let size = 0
  let frames = 0
  let done = false
  let complete = false
  let pending = new Uint8Array(0)
  const line = (bytes: Uint8Array) => {
    if (bytes.byteLength > AUDIO_LINE_LIMIT) return invalid()
    let value: unknown
    try {
      const text = decoder.decode(bytes)
      if (!text.trim()) return
      if (++frames > FRAME_LIMIT) return invalid()
      value = JSON.parse(text)
    } catch {
      return invalid()
    }
    if (done || !value || typeof value !== 'object' || Array.isArray(value)) return invalid()
    const frame = value as Record<string, unknown>
    if (frame.type === 'error')
      throw new AudioStreamError(typeof frame.code === 'string' ? frame.code : undefined)
    if (frame.type === 'start') {
      if (audioFormat) return invalid()
      audioFormat = format(frame)
    } else if (frame.type === 'audio') {
      if (!audioFormat) return invalid()
      const chunk = decode(frame.data)
      size += chunk.byteLength
      if (
        size > AUDIO_STREAM_LIMIT ||
        (audioFormat.contentType === 'audio/pcm' && chunk.byteLength % 2)
      )
        return invalid()
      chunks.push(chunk)
      signal.throwIfAborted()
      if (audioFormat.contentType === 'audio/pcm') onAudio?.(chunk, audioFormat)
      signal.throwIfAborted()
    } else if (frame.type === 'done') {
      if (!audioFormat || !size) return invalid()
      done = true
    } else return invalid()
  }
  try {
    while (true) {
      signal.throwIfAborted()
      const next = await reader.read()
      signal.throwIfAborted()
      if (next.done) break
      let start = 0
      for (let index = 0; index < next.value.byteLength; index++) {
        if (next.value[index] !== 10) continue
        const part = next.value.subarray(start, index)
        if (pending.byteLength + part.byteLength > AUDIO_LINE_LIMIT) return invalid()
        if (pending.byteLength) {
          const combined = new Uint8Array(pending.byteLength + part.byteLength)
          combined.set(pending)
          combined.set(part, pending.byteLength)
          line(combined)
        } else line(part)
        pending = new Uint8Array(0)
        start = index + 1
      }
      const remaining = next.value.subarray(start)
      if (pending.byteLength + remaining.byteLength > AUDIO_LINE_LIMIT) return invalid()
      if (remaining.byteLength) {
        const combined = new Uint8Array(pending.byteLength + remaining.byteLength)
        combined.set(pending)
        combined.set(remaining, pending.byteLength)
        pending = combined
      }
    }
    if (pending.byteLength) line(pending)
    if (!done || !audioFormat) return invalid()
    const audio = new Uint8Array(size)
    let offset = 0
    for (const chunk of chunks) {
      audio.set(chunk, offset)
      offset += chunk.byteLength
    }
    complete = true
    return { ...audioFormat, audio, segments, segment, cacheKey }
  } finally {
    signal.removeEventListener('abort', cancel)
    if (!complete) void reader.cancel().catch(() => undefined)
    reader.releaseLock()
  }
}
