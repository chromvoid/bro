import { describe, expect, it, vi } from 'vitest'
import {
  AUDIO_LINE_LIMIT,
  AUDIO_STREAM_LIMIT,
  AudioStreamError,
  readAudioStream,
} from './audio-stream'

const start = { type: 'start', content_type: 'audio/pcm', sample_rate: 24000, channels: 1 }
const audio = { type: 'audio', data: 'AQACAAMABAA=' }
const done = { type: 'done' }
const lines = (...frames: unknown[]) =>
  frames.map((frame) => JSON.stringify(frame)).join('\n') + '\n'
const headers = {
  'Content-Type': 'application/x-ndjson',
  'X-Speech-Segments': '2',
  'X-Speech-Segment': '1',
  'X-Speech-Cache-Key': 'a'.repeat(64),
}
const response = (...frames: unknown[]) => new Response(lines(...frames), { headers })

function stream() {
  let controller!: ReadableStreamDefaultController<Uint8Array>
  const cancelled = vi.fn()
  const body = new ReadableStream<Uint8Array>({
    start(value) {
      controller = value
    },
    cancel: cancelled,
  })
  return {
    response: new Response(body, { headers }),
    append: (value: string) => controller.enqueue(new TextEncoder().encode(value)),
    close: () => controller.close(),
    cancelled,
  }
}

describe('speech NDJSON transport', () => {
  it('emits PCM before EOF, handles split lines, and only returns a complete recording after EOF', async () => {
    const wire = stream()
    const onAudio = vi.fn()
    const complete = vi.fn()
    const pending = readAudioStream(wire.response, new AbortController().signal, onAudio).then(
      (reply) => {
        complete(reply)
        return reply
      },
    )
    const value = lines(start, audio)
    wire.append(value.slice(0, 15))
    wire.append(value.slice(15, -3))
    expect(onAudio).not.toHaveBeenCalled()
    wire.append(value.slice(-3))
    await vi.waitFor(() => expect(onAudio).toHaveBeenCalledOnce())
    expect(onAudio).toHaveBeenCalledWith(new Uint8Array([1, 0, 2, 0, 3, 0, 4, 0]), {
      contentType: 'audio/pcm',
      sampleRate: 24000,
      channels: 1,
    })
    wire.append(lines(done))
    await Promise.resolve()
    expect(complete).not.toHaveBeenCalled()
    wire.close()
    expect(await pending).toEqual({
      audio: new Uint8Array([1, 0, 2, 0, 3, 0, 4, 0]),
      contentType: 'audio/pcm',
      sampleRate: 24000,
      channels: 1,
      segments: 2,
      segment: 1,
      cacheKey: 'a'.repeat(64),
    })
  })

  it.each(['audio/wav', 'audio/mpeg'])(
    'buffers %s rather than delivering unsupported PCM chunks',
    async (content_type) => {
      const onAudio = vi.fn()
      const reply = await readAudioStream(
        response({ type: 'start', content_type }, audio, done),
        new AbortController().signal,
        onAudio,
      )
      expect(reply.contentType).toBe(content_type)
      expect(reply.audio.byteLength).toBe(8)
      expect(onAudio).not.toHaveBeenCalled()
    },
  )

  it.each([
    { segments: 3, segment: 1, cacheKey: 'a'.repeat(64) },
    { segments: 2, segment: 0, cacheKey: 'a'.repeat(64) },
    { segments: 2, segment: 1, cacheKey: 'b'.repeat(64) },
    { segments: 2, segment: 1, cacheKey: null },
  ])('rejects metadata mismatching the manifest before any PCM callback: %o', async (expected) => {
    const onAudio = vi.fn()
    await expect(
      readAudioStream(
        response(start, audio, done),
        new AbortController().signal,
        onAudio,
        expected,
      ),
    ).rejects.toMatchObject({ code: 'provider_error' })
    expect(onAudio).not.toHaveBeenCalled()
  })

  it.each([
    ['missing done', [start, audio]],
    ['missing start', [audio, done]],
    ['duplicate start', [start, start, audio, done]],
    ['duplicate done', [start, audio, done, done]],
    ['trailing audio', [start, audio, done, audio]],
    ['no audio', [start, done]],
    ['wrong PCM rate', [{ ...start, sample_rate: 16000 }, audio, done]],
    ['wrong PCM channels', [{ ...start, channels: 2 }, audio, done]],
    ['missing PCM metadata', [{ type: 'start', content_type: 'audio/pcm' }, audio, done]],
    ['unknown content type', [{ type: 'start', content_type: 'text/html' }, audio, done]],
    [
      'invalid content type value',
      [{ type: 'start', content_type: { toString: null } }, audio, done],
    ],
    ['invalid base64', [start, { type: 'audio', data: 'secret%invalid' }, done]],
    ['empty audio', [start, { type: 'audio', data: '' }, done]],
    ['unaligned PCM', [start, { type: 'audio', data: 'AA==' }, done]],
    ['unknown frame', [start, { type: 'private-text', text: 'secret' }, done]],
    ['array frame', [[], start, audio, done]],
  ])('rejects %s without exposing frame contents', async (_label, frames) => {
    const failure = await readAudioStream(response(...frames), new AbortController().signal).catch(
      (error: unknown) => error,
    )
    expect(failure).toBeInstanceOf(AudioStreamError)
    expect(failure).toMatchObject({ code: 'provider_error' })
    expect(JSON.stringify(failure)).not.toContain('secret')
  })

  it('accepts a complete final line without a newline but rejects truncated JSON', async () => {
    const complete = new Response(lines(start, audio) + JSON.stringify(done), { headers })
    expect((await readAudioStream(complete, new AbortController().signal)).audio.byteLength).toBe(8)
    const broken = new Response(lines(start, audio) + '{"type":', { headers })
    await expect(readAudioStream(broken, new AbortController().signal)).rejects.toMatchObject({
      code: 'provider_error',
    })
  })

  it.each(['provider_limit', 'auth_required', 'no_access', 'provider-private-detail'])(
    'retains only the safe error code %s and does not complete audio',
    async (code) => {
      const failure = await readAudioStream(
        response(start, audio, { type: 'error', code, message: 'synthetic-secret' }),
        new AbortController().signal,
      ).catch((error: unknown) => error)
      expect(failure).toMatchObject({
        code: code === 'provider-private-detail' ? 'provider_error' : code,
      })
      expect(JSON.stringify(failure)).not.toContain('synthetic-secret')
    },
  )

  it('limits a single frame even when no newline arrives', async () => {
    const wire = stream()
    const pending = readAudioStream(wire.response, new AbortController().signal)
    wire.append('x'.repeat(AUDIO_LINE_LIMIT + 1))
    await expect(pending).rejects.toMatchObject({ code: 'provider_error' })
    expect(wire.cancelled).toHaveBeenCalledOnce()
  })

  it('bounds accumulated decoded bytes across individually valid frames', async () => {
    const chunkBytes = 128 * 1024
    const chunk = { type: 'audio', data: btoa('\0'.repeat(chunkBytes)) }
    const frames = [
      start,
      ...Array.from({ length: AUDIO_STREAM_LIMIT / chunkBytes + 1 }, () => chunk),
      done,
    ]
    await expect(
      readAudioStream(response(...frames), new AbortController().signal),
    ).rejects.toMatchObject({ code: 'provider_error' })
  })

  it('bounds tiny frame overhead before it can allocate millions of byte arrays', async () => {
    const frames = [
      start,
      ...Array.from({ length: 16_384 }, () => ({ type: 'audio', data: 'AAA=' })),
      done,
    ]
    await expect(
      readAudioStream(response(...frames), new AbortController().signal),
    ).rejects.toMatchObject({ code: 'provider_error' })
  })

  it('cancels a pending reader immediately on abort', async () => {
    const wire = stream()
    const controller = new AbortController()
    const pending = readAudioStream(wire.response, controller.signal)
    wire.append(lines(start))
    controller.abort()
    await expect(pending).rejects.toMatchObject({ name: 'AbortError' })
    expect(wire.cancelled).toHaveBeenCalledOnce()
  })

  it('stops callbacks immediately when the consumer cancels from an audio callback', async () => {
    const controller = new AbortController()
    const onAudio = vi.fn(() => controller.abort())
    await expect(
      readAudioStream(response(start, audio, audio, done), controller.signal, onAudio),
    ).rejects.toMatchObject({ name: 'AbortError' })
    expect(onAudio).toHaveBeenCalledOnce()
  })

  it.each([
    { 'Content-Type': 'audio/mpeg' },
    { 'X-Speech-Segments': '0' },
    { 'X-Speech-Segment': '2' },
    { 'X-Speech-Cache-Key': 'provider-private-detail' },
  ])('rejects invalid response metadata %o', async (changed) => {
    await expect(
      readAudioStream(
        new Response(lines(start, audio, done), { headers: { ...headers, ...changed } }),
        new AbortController().signal,
      ),
    ).rejects.toMatchObject({ code: 'provider_error' })
  })
})
