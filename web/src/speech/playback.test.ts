import { describe, expect, it, vi } from 'vitest'
import { ApiError, type AudioFormat, type AudioStreamReply, type WriteOptions } from '@/core/http'
import { SpeechCache } from './cache'
import { SpeechPlayback } from './playback'

const KEY = 'a'.repeat(64)
const OTHER_KEY = 'b'.repeat(64)
const PCM: AudioFormat = { contentType: 'audio/pcm', sampleRate: 24000, channels: 1 }
const bytes = () => new Uint8Array(1024)
function setup(cache = new SpeechCache()) {
  const sources: Array<{
    onended: (() => void) | null
    start: ReturnType<typeof vi.fn>
    stop: ReturnType<typeof vi.fn>
  }> = []
  const contexts: Array<{
    resume: ReturnType<typeof vi.fn>
    close: ReturnType<typeof vi.fn>
    createBuffer: ReturnType<typeof vi.fn>
    decodeAudioData: ReturnType<typeof vi.fn>
  }> = []
  const context = () => {
    const value = {
      state: 'running',
      currentTime: 0,
      destination: {},
      resume: vi.fn(async () => undefined),
      close: vi.fn(async () => {
        value.state = 'closed'
      }),
      createBuffer: vi.fn((_channels, frames, sampleRate) => ({
        duration: frames / sampleRate,
        getChannelData: () => new Float32Array(frames),
      })),
      decodeAudioData: vi.fn(async () => ({})),
      createBufferSource: vi.fn(() => {
        const source = {
          onended: null as (() => void) | null,
          connect: vi.fn(),
          disconnect: vi.fn(),
          start: vi.fn(),
          stop: vi.fn(),
        }
        sources.push(source)
        return source
      }),
    }
    contexts.push(value)
    return value as unknown as AudioContext
  }
  const prepare = vi.fn(async () => ({
    data: { cache_key: KEY, segments: 1, connection_id: 'connection' },
  }))
  const request = vi.fn(
    async (
      _path: string,
      body: unknown,
      _options: WriteOptions,
      onAudio: (chunk: Uint8Array, format: AudioFormat) => void,
    ): Promise<{ data: AudioStreamReply }> => {
      const input = body as { segment?: number; cache_key?: string }
      const audio = bytes()
      onAudio(audio, PCM)
      return {
        data: {
          ...PCM,
          audio,
          segments: 1,
          segment: input.segment ?? 0,
          cacheKey: input.cache_key ?? null,
        },
      }
    },
  )
  const player = new SpeechPlayback(request, context, prepare, cache)
  player.account('user')
  const end = () => {
    for (const source of [...sources]) source.onended?.()
  }
  return { player, request, prepare, sources, contexts, end, cache }
}

describe('speech playback', () => {
  it('resumes within the gesture and starts PCM before the upstream finishes', async () => {
    const { player, request, prepare, sources, contexts, end } = setup()
    let complete!: () => void
    request.mockImplementationOnce(async (_path, _body, _options, onAudio) => {
      onAudio(bytes(), PCM)
      await new Promise<void>((resolve) => {
        complete = resolve
      })
      return { data: { ...PCM, audio: bytes(), segments: 1, segment: 0, cacheKey: KEY } }
    })
    const playing = player.turn('turn/one')
    expect(contexts[0]!.resume).toHaveBeenCalledOnce()
    await vi.waitFor(() => expect(sources).toHaveLength(1))
    expect(sources[0]!.start).toHaveBeenCalledOnce()
    expect(player.state.value.status).toBe('playing')
    expect(contexts[0]!.decodeAudioData).not.toHaveBeenCalled()
    expect(prepare).toHaveBeenCalledWith(
      '/v1/conversation/turns/turn%2Fone/speech/prepare',
      {},
      expect.objectContaining({ expectedUser: 'user' }),
    )
    expect(request.mock.calls[0]![1]).toMatchObject({
      segment: 0,
      cache_key: KEY,
      playback_id: expect.any(String),
    })
    complete()
    end()
    await playing
    expect(player.state.value.status).toBe('idle')
  })

  it('replays a complete answer from memory after revalidating its current server fingerprint', async () => {
    const { player, request, prepare, sources, end } = setup()
    const first = player.turn('turn')
    await vi.waitFor(() => expect(sources).toHaveLength(1))
    end()
    await first
    const again = player.turn('turn')
    await vi.waitFor(() => expect(sources).toHaveLength(2))
    expect(prepare).toHaveBeenCalledTimes(2)
    expect(request).toHaveBeenCalledOnce()
    end()
    await again
    prepare.mockResolvedValueOnce({
      data: { cache_key: OTHER_KEY, segments: 1, connection_id: 'connection' },
    })
    const changed = player.turn('turn')
    await vi.waitFor(() => expect(sources).toHaveLength(3))
    expect(request).toHaveBeenCalledTimes(2)
    end()
    await changed
  })

  it('reuses fully received audio after Stop while it is still playing', async () => {
    const { player, request, prepare, sources, end, cache } = setup()
    const first = player.turn('turn')
    await vi.waitFor(() => expect(cache.get(KEY, 'connection')).toBeDefined())
    expect(player.state.value.status).toBe('playing')
    player.stop()
    await first
    expect(sources[0]!.stop).toHaveBeenCalledOnce()
    const again = player.turn('turn')
    await vi.waitFor(() => expect(sources).toHaveLength(2))
    expect(prepare).toHaveBeenCalledTimes(2)
    expect(request).toHaveBeenCalledOnce()
    end()
    await again
  })

  it('stops early audio on a late provider error and never caches it', async () => {
    const { player, request, sources, end } = setup()
    request.mockImplementationOnce(async (_path, _body, _options, onAudio) => {
      onAudio(bytes(), PCM)
      throw new ApiError(502, 'provider_error', null, null, null, 'private message')
    })
    await player.turn('turn')
    expect(sources[0]!.stop).toHaveBeenCalledOnce()
    expect(player.state.value.problem).toBe('provider_error')
    const retry = player.turn('turn')
    await vi.waitFor(() => expect(sources).toHaveLength(2))
    expect(request).toHaveBeenCalledTimes(2)
    end()
    await retry
  })

  it('aborts pending generation and all scheduled PCM sources on stop', async () => {
    const { player, request, sources } = setup()
    request.mockImplementationOnce((_path, _body, options, onAudio) => {
      onAudio(new Uint8Array(100_000), PCM)
      return new Promise((_resolve, reject) =>
        options.signal!.addEventListener('abort', () => reject(options.signal!.reason)),
      )
    })
    const playing = player.turn('turn')
    await vi.waitFor(() => expect(sources).toHaveLength(6))
    player.stop()
    await playing
    expect(sources.every((source) => source.stop.mock.calls.length === 1)).toBe(true)
    expect(player.state.value).toEqual({ key: null, status: 'idle', problem: null })
  })

  it('limits decoded PCM ahead while consuming all network bytes independently', async () => {
    const { player, request, sources, contexts, end } = setup()
    request.mockImplementationOnce(async (_path, _body, _options, onAudio) => {
      const audio = new Uint8Array(2048 * 2 * 20)
      onAudio(audio, PCM)
      return { data: { ...PCM, audio, segments: 1, segment: 0, cacheKey: KEY } }
    })
    const playing = player.turn('turn')
    await vi.waitFor(() => expect(sources).toHaveLength(6))
    expect(contexts[0]!.createBuffer).toHaveBeenCalledTimes(6)
    expect(contexts[0]!.createBuffer.mock.calls.every((call) => call[1] <= 2048)).toBe(true)
    for (let i = 0; i < 5; i++) end()
    await playing
    expect(sources).toHaveLength(20)
  })

  it('waits for complete compressed audio before decoding WAV/MP3', async () => {
    const { player, request, sources, contexts, end } = setup()
    let finish!: () => void
    request.mockImplementationOnce(async () => {
      await new Promise<void>((resolve) => {
        finish = resolve
      })
      return {
        data: { contentType: 'audio/wav', audio: bytes(), segments: 1, segment: 0, cacheKey: KEY },
      }
    })
    const playing = player.turn('turn')
    await vi.waitFor(() => expect(request).toHaveBeenCalledOnce())
    expect(sources).toHaveLength(0)
    finish()
    await vi.waitFor(() => expect(sources).toHaveLength(1))
    expect(contexts[0]!.decodeAudioData).toHaveBeenCalledOnce()
    end()
    await playing
  })

  it('clears cached audio and rejects a late old-user response after account change', async () => {
    const { player, request, sources, end } = setup()
    const first = player.turn('turn')
    await vi.waitFor(() => expect(sources).toHaveLength(1))
    end()
    await first
    player.account('other')
    const other = player.turn('turn')
    await vi.waitFor(() => expect(sources).toHaveLength(2))
    expect(request).toHaveBeenCalledTimes(2)
    end()
    await other
    let finish!: () => void
    request.mockImplementationOnce(async () => {
      await new Promise<void>((resolve) => {
        finish = resolve
      })
      return { data: { ...PCM, audio: bytes(), segments: 1, segment: 0, cacheKey: OTHER_KEY } }
    })
    player.account('third')
    const pending = player.turn('late')
    await vi.waitFor(() => expect(request).toHaveBeenCalledTimes(3))
    player.account(undefined)
    finish()
    await pending
    expect(sources).toHaveLength(2)
    expect(player.state.value.status).toBe('idle')
  })

  it('invalidates only the changed connection and stops its active generation', async () => {
    const { player, request, sources, end } = setup()
    const first = player.turn('turn')
    await vi.waitFor(() => expect(sources).toHaveLength(1))
    player.invalidateConnection('unrelated')
    expect(sources[0]!.stop).not.toHaveBeenCalled()
    end()
    await first
    player.invalidateConnection('connection')
    const next = player.turn('turn')
    await vi.waitFor(() => expect(sources).toHaveLength(2))
    expect(request).toHaveBeenCalledTimes(2)
    player.invalidateConnection('connection')
    await next
    expect(sources[1]!.stop).toHaveBeenCalledOnce()
  })

  it('does not reuse a descriptor that was in flight during connection invalidation', async () => {
    const { player, prepare, request } = setup()
    let finish!: () => void
    prepare.mockImplementationOnce(async () => {
      await new Promise<void>((resolve) => {
        finish = resolve
      })
      return { data: { cache_key: KEY, segments: 1, connection_id: 'connection' } }
    })
    const pending = player.turn('turn')
    await vi.waitFor(() => expect(prepare).toHaveBeenCalledOnce())
    player.invalidateConnection('connection')
    finish()
    await pending
    expect(request).not.toHaveBeenCalled()
  })

  it('plays every segment when the complete answer exceeds the cache budget', async () => {
    const { player, prepare, request, sources, end, cache } = setup(new SpeechCache(1500))
    prepare.mockResolvedValue({
      data: { cache_key: KEY, segments: 2, connection_id: 'connection' },
    })
    request.mockImplementation(async (_path, body, _options, onAudio) => {
      onAudio(bytes(), PCM)
      return {
        data: {
          ...PCM,
          audio: bytes(),
          segments: 2,
          segment: (body as { segment: number }).segment,
          cacheKey: KEY,
        },
      }
    })
    const first = player.turn('turn')
    await vi.waitFor(() => expect(sources).toHaveLength(1))
    end()
    await vi.waitFor(() => expect(sources).toHaveLength(2))
    end()
    await first
    expect(cache.get(KEY, 'connection')).toBeUndefined()
    expect(request).toHaveBeenCalledTimes(2)
  })

  it('does not cache completed initial segments if the rest of the answer fails', async () => {
    const { player, prepare, request, sources, end, cache } = setup()
    prepare.mockResolvedValue({
      data: { cache_key: KEY, segments: 2, connection_id: 'connection' },
    })
    request.mockImplementationOnce(async (_path, _body, _options, onAudio) => {
      onAudio(bytes(), PCM)
      return { data: { ...PCM, audio: bytes(), segments: 2, segment: 0, cacheKey: KEY } }
    })
    request.mockRejectedValueOnce(new ApiError(502, 'provider_error', null, null, null))
    const playing = player.turn('turn')
    await vi.waitFor(() => expect(sources).toHaveLength(1))
    end()
    await playing
    expect(request).toHaveBeenCalledTimes(2)
    expect(cache.get(KEY, 'connection')).toBeUndefined()
    expect(player.state.value.problem).toBe('provider_error')
  })

  it('contains asynchronous PCM scheduling failures and stops the remaining scheduled buffers', async () => {
    const { player, request, sources, contexts } = setup()
    request.mockImplementationOnce(async (_path, _body, _options, onAudio) => {
      const audio = new Uint8Array(2048 * 2 * 20)
      onAudio(audio, PCM)
      return { data: { ...PCM, audio, segments: 1, segment: 0, cacheKey: KEY } }
    })
    const playing = player.turn('turn')
    await vi.waitFor(() => expect(sources).toHaveLength(6))
    contexts[0]!.createBuffer.mockImplementationOnce(() => {
      throw new Error('Audio device lost')
    })
    expect(() => sources[0]!.onended?.()).not.toThrow()
    await playing
    expect(player.state.value.problem).toBe('playback')
    expect(sources.slice(1).every((source) => source.stop.mock.calls.length === 1)).toBe(true)
  })

  it('cancels an open provider request immediately when the audio device fails asynchronously', async () => {
    const { player, request, sources, contexts } = setup()
    let signal: AbortSignal | undefined
    request.mockImplementationOnce((_path, _body, options, onAudio) => {
      signal = options.signal
      onAudio(new Uint8Array(2048 * 2 * 20), PCM)
      return new Promise((_resolve, reject) =>
        signal!.addEventListener('abort', () => reject(signal!.reason)),
      )
    })
    const playing = player.turn('turn')
    await vi.waitFor(() => expect(sources).toHaveLength(6))
    contexts[0]!.createBuffer.mockImplementationOnce(() => {
      throw new Error('Device lost')
    })
    expect(() => sources[0]!.onended?.()).not.toThrow()
    expect(signal?.aborted).toBe(true)
    await playing
    expect(player.state.value.problem).toBe('playback')
    expect(sources.slice(1).every((source) => source.stop.mock.calls.length === 1)).toBe(true)
  })

  it('keeps preview explicit, uncached and tied to its selected connection/model/voice', async () => {
    const { player, prepare, request, sources, end } = setup()
    const playing = player.preview('router', 'Kore', 'google/gemini-3.8-flash-tts')
    await vi.waitFor(() => expect(sources).toHaveLength(1))
    expect(prepare).not.toHaveBeenCalled()
    expect(request.mock.calls[0]!.slice(0, 2)).toEqual([
      '/v1/me/speech/preview',
      { connection_id: 'router', voice: 'Kore', tts_model: 'google/gemini-3.8-flash-tts' },
    ])
    end()
    await playing
  })
})
