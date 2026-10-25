import { describe, expect, it, vi } from 'vitest'
import type { Api, WriteOptions } from '@/core/http'
import { dateRange, recordingSource, recordingsClient, Transcript } from './api'

const ID = '00000000-0000-4000-8000-000000000001'
const OTHER = '00000000-0000-4000-8000-000000000002'
const TIME = '2026-10-05T12:00:00Z'
const transcript = (id = ID) => ({
  id,
  status: 'ready' as const,
  text: 'Synthetic conversation text',
  provider: 'synthetic-provider',
  model: 'synthetic-model',
  transcribed_at: TIME,
  updated_at: TIME,
  error_code: null,
  retry_may_repeat: false,
})

function transport() {
  const api = {
    get: vi.fn<Api['get']>(),
    send: vi.fn<Api['send']>(),
  }
  // Vitest resolves generic return types to unknown; the fixture still returns each API DTO.
  return { ...api, client: recordingsClient(api as unknown as Pick<Api, 'get' | 'send'>) }
}

describe('recordings read contract', () => {
  it('sends date bounds and an opaque pagination cursor through the same-origin URL', async () => {
    const api = transport()
    api.get.mockResolvedValue({ recordings: [], next_cursor: 'next' })
    const signal = new AbortController().signal
    const range = dateRange('2026-10-05', 'Europe/Moscow')
    const cursor = 'opaque+/=& cursor'
    expect(await api.client.list(range, cursor, signal)).toEqual({
      recordings: [],
      next_cursor: 'next',
    })
    const [path, receivedSignal] = api.get.mock.calls[0]!
    const url = new URL(path, 'https://synthetic.example')
    expect(url.pathname).toBe('/v1/ambient-audio/recordings')
    expect([...url.searchParams]).toEqual([
      ['limit', '30'],
      ['cursor', cursor],
      ['from', '2026-10-05T00:00:00+03:00'],
      ['to', '2026-10-06T00:00:00+03:00'],
    ])
    expect(path.startsWith('/')).toBe(true)
    expect(receivedSignal).toBe(signal)
  })

  it('does not send empty bounds or cursor on the first page', async () => {
    const api = transport()
    api.get.mockResolvedValue({ recordings: [], next_cursor: null })
    const signal = new AbortController().signal
    await api.client.list({}, null, signal)
    expect(api.get).toHaveBeenCalledExactlyOnceWith('/v1/ambient-audio/recordings?limit=30', signal)
  })

  it('uses the account calendar day as a half-open range across a clock change', () => {
    const range = dateRange('2026-03-08', 'America/New_York')
    expect(range).toEqual({
      from: '2026-03-08T00:00:00-05:00',
      to: '2026-03-09T00:00:00-04:00',
    })
    expect(Date.parse(range.to!) - Date.parse(range.from!)).toBe(23 * 60 * 60 * 1000)
    expect(dateRange('2026-10-05', 'Europe/Moscow')).toEqual({
      from: '2026-10-05T00:00:00+03:00',
      to: '2026-10-06T00:00:00+03:00',
    })
  })

  it.each(['', '2026-02-30', '2026-10-5', 'synthetic'])('ignores invalid day %j', (day) => {
    expect(dateRange(day, 'UTC')).toEqual({})
  })

  it('reads storage usage through the owner-protected endpoint', async () => {
    const api = transport()
    const value = { used_bytes: 42, limits: { retention_days: 7, max_user_bytes: 1024 } }
    api.get.mockResolvedValue(value)
    const signal = new AbortController().signal
    expect(await api.client.usage(signal)).toEqual(value)
    expect(api.get).toHaveBeenCalledExactlyOnceWith('/v1/ambient-audio', signal)
  })

  it.each([null, '', '  \n\t'])('rejects ready transcript without text: %j', (text) => {
    expect(Transcript.safeParse({ ...transcript(), text }).success).toBe(false)
  })

  it('accepts pending and empty outcomes without fabricating transcript text', () => {
    for (const status of ['pending', 'processing', 'empty', 'failed']) {
      expect(Transcript.parse({ ...transcript(), status, text: null }).text).toBeNull()
    }
    expect(Transcript.parse(transcript()).text).toBe('Synthetic conversation text')
  })

  it('requires the transcription response to name the requested recording', async () => {
    const api = transport()
    api.get.mockResolvedValue(transcript(OTHER))
    await expect(api.client.transcription(ID, new AbortController().signal)).rejects.toThrow(
      'Invalid recording identity',
    )
  })
})

describe('recordings write receipts', () => {
  it('passes the exact owner and before-send guards to deletion and retry', async () => {
    const api = transport()
    api.send
      .mockResolvedValueOnce({ status: 200, data: { id: ID, deleted: true } })
      .mockResolvedValueOnce({ status: 202, data: transcript() })
    const options: WriteOptions = {
      expectedUser: OTHER,
      signal: new AbortController().signal,
      beforeSend: vi.fn(),
    }
    const client = api.client
    await client.remove(ID, options)
    expect(api.send).toHaveBeenCalledExactlyOnceWith(
      'DELETE',
      `/v1/ambient-audio/batches/${ID}`,
      undefined,
      options,
    )
    expect(await client.retry(ID, options)).toEqual(transcript())
    expect(api.send).toHaveBeenNthCalledWith(
      2,
      'POST',
      `/v1/ambient-audio/batches/${ID}/transcription/retry`,
      undefined,
      options,
    )
    expect(api.send).toHaveBeenCalledTimes(2)
  })

  it('rejects a deletion receipt for another recording', async () => {
    const api = transport()
    api.send.mockResolvedValue({ status: 200, data: { id: OTHER, deleted: true } })
    await expect(api.client.remove(ID, {})).rejects.toThrow('Invalid deletion receipt')
  })

  it('requires an affirmative deletion receipt', async () => {
    const api = transport()
    api.send.mockResolvedValue({ status: 200, data: { id: ID, deleted: false } })
    await expect(api.client.remove(ID, {})).rejects.toThrow()
  })

  it('rejects a retry receipt for another recording', async () => {
    const api = transport()
    api.send.mockResolvedValue({ status: 202, data: transcript(OTHER) })
    await expect(api.client.retry(ID, {})).rejects.toThrow('Invalid recording identity')
  })
})

describe('protected recording playback', () => {
  it.each([
    ['wav', 'audio/wav'],
    ['ogg-opus', 'audio/ogg; codecs="opus"'],
    ['aac-adts', 'audio/aac'],
  ] as const)('uses the stored %s only when the browser supports its MIME type', (format, mime) => {
    const canPlay = vi.fn(() => 'probably')
    expect(recordingSource(ID, format, canPlay)).toBe(`/v1/ambient-audio/batches/${ID}`)
    expect(canPlay).toHaveBeenCalledExactlyOnceWith(mime)
    canPlay.mockReturnValue('')
    expect(recordingSource(ID, format, canPlay)).toBe(
      `/v1/ambient-audio/batches/${ID}/playback?format=wav`,
    )
  })

  it('uses the WAV playback endpoint after a decoder error even if support was advertised', () => {
    const canPlay = vi.fn(() => 'maybe')
    expect(recordingSource(ID, 'aac-adts', canPlay, true)).toBe(
      `/v1/ambient-audio/batches/${ID}/playback?format=wav`,
    )
    expect(canPlay).not.toHaveBeenCalled()
  })
})
