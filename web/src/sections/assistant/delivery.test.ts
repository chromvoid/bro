import { describe, expect, it, vi } from 'vitest'
import { Api, ApiError, NetworkError } from '@/core/http'
import { LocalKeyLock, SubmissionJournal, type Submission } from '@/core/journal'
import { deliverMessage, ENDPOINT, RejectedDelivery } from './delivery'

function setup(
  mode: 'accepted' | 'upload-failed' | 'lost' | 'server-failed' | 'refused' | 'unavailable',
  failOutcomePersistence = false,
) {
  const entries = new Map<string, Submission>()
  const fetcher = vi.fn(async (input: RequestInfo | URL) => {
    const path = String(input)
    if (path === '/v1/auth/csrf') return Response.json({ token: 'csrf' })
    if (path.endsWith('/images')) {
      if (mode === 'upload-failed') throw new TypeError('network lost')
      return Response.json({ id: 'image', width: 20, height: 10, url: '/image' })
    }
    if (mode === 'lost') throw new TypeError('network lost')
    if (mode === 'server-failed') return Response.json({ error: 'internal' }, { status: 503 })
    if (mode === 'refused') return Response.json({ error: 'queue_full' }, { status: 429 })
    if (mode === 'unavailable') return Response.json({ error: 'chat_unavailable' }, { status: 409 })
    return Response.json(
      {
        message_id: 'message',
        state: 'queued',
        submission_key: 'key',
        admitted_at: '2026-10-02T12:00:00Z',
        trace_id: null,
      },
      { status: 202 },
    )
  })
  const api = new Api(fetcher)
  const journal = new SubmissionJournal(
    {
      submission: async (key) => entries.get(key),
      submissions: async () => [...entries.values()],
      saveSubmission: async (entry) => {
        if (failOutcomePersistence && !['prepared', 'sending'].includes(entry.state))
          throw new Error('storage unavailable')
        entries.set(entry.key, entry)
      },
      deleteSubmission: async (key) => {
        entries.delete(key)
      },
    },
    api,
    new LocalKeyLock(),
  )
  return { api, journal, fetcher, entries }
}

const body = { text: 'Это был завтрак', reply_to_message_id: 'reply', transcription_id: 'voice' }
const photo = new Blob(['photo'], { type: 'image/jpeg' })

describe('conversation delivery', () => {
  it('does not mistake a failed upload for a journaled, uncertain message', async () => {
    const { journal, api, entries, fetcher } = setup('upload-failed')
    await expect(deliverMessage(journal, api, body, photo, 'key')).rejects.toBeInstanceOf(
      NetworkError,
    )
    expect(entries.size).toBe(0)
    expect(fetcher.mock.calls.some(([url]) => url === ENDPOINT)).toBe(false)
  })

  it.each(['lost', 'server-failed'] as const)(
    'keeps the exact full envelope on %s admission',
    async (mode) => {
      const { journal, api, entries, fetcher } = setup(mode)
      const delivery = await deliverMessage(journal, api, body, photo, 'key')
      expect(delivery.state).toBe('uncertain')
      expect(JSON.parse(entries.get('key')!.body)).toEqual({ ...body, image_id: 'image' })
      expect(entries.get('key')!.state).toBe('uncertain')
      expect(fetcher.mock.calls.filter(([url]) => url === ENDPOINT)).toHaveLength(1)
    },
  )

  it.each(['refused', 'unavailable'] as const)(
    'retains a known %s refusal until the caller establishes who owns the original input',
    async (mode) => {
      const { journal, api, entries } = setup(mode)
      const rejected = await deliverMessage(journal, api, body, photo, 'key').catch(
        (error: unknown) => error,
      )
      expect(rejected).toBeInstanceOf(RejectedDelivery)
      expect((rejected as RejectedDelivery).reason).toBeInstanceOf(ApiError)
      expect(entries.get('key')?.state).toBe('rejected')
      expect(JSON.parse(entries.get('key')!.body)).toEqual({ ...body, image_id: 'image' })
    },
  )

  it('admits the uploaded image with the original reply and transcription bindings', async () => {
    const { journal, api } = setup('accepted')
    const delivery = await deliverMessage(journal, api, body, photo, 'key')
    expect(delivery.state).toBe('accepted')
    expect(delivery.submission.key).toBe('key')
    expect(JSON.parse(delivery.submission.body)).toEqual({ ...body, image_id: 'image' })
    expect(body).not.toHaveProperty('image_id')
  })

  it('does not hand off a different draft using a receipt that belongs to an existing key', async () => {
    const { journal, api, fetcher } = setup('accepted')
    await deliverMessage(journal, api, body, null, 'key')
    await expect(
      deliverMessage(journal, api, { text: 'Другой черновик' }, null, 'key'),
    ).rejects.toThrow('key already exists')
    expect(fetcher.mock.calls.filter(([path]) => path === ENDPOINT)).toHaveLength(1)
  })

  it('does not claim recovery exists when local persistence fails before sending', async () => {
    const { journal, api, fetcher } = setup('accepted')
    vi.spyOn(journal, 'submit').mockRejectedValue(new Error('storage full'))
    await expect(deliverMessage(journal, api, body, null, 'key')).rejects.toThrow('storage full')
    expect(fetcher).not.toHaveBeenCalled()
  })

  it.each(['accepted', 'lost'] as const)(
    'keeps the journal handoff when %s outcome cannot be persisted or reread',
    async (mode) => {
      const { journal, api, entries, fetcher } = setup(mode, true)
      vi.spyOn(journal, 'pending').mockRejectedValue(new Error('storage unavailable'))
      const result = await deliverMessage(journal, api, body, null, 'key')
      expect(result.state).toBe(mode === 'accepted' ? 'accepted' : 'uncertain')
      expect(result.submission.body).toBe(JSON.stringify(body))
      expect(entries.get('key')).toMatchObject({ state: 'sending', mayHaveBeenAdmitted: true })
      expect(fetcher.mock.calls.filter(([url]) => url === ENDPOINT)).toHaveLength(1)
    },
  )

  it('does not submit after the account changes during image upload', async () => {
    const { journal, api, fetcher } = setup('accepted')
    let current = true
    const upload = api.upload.bind(api)
    vi.spyOn(api, 'upload').mockImplementation(async (...args) => {
      const result = await upload(...args)
      current = false
      return result
    })
    // A photo of its own: an upload started earlier would otherwise be reused (photoUploads).
    const fresh = new Blob(['another photo'], { type: 'image/jpeg' })
    await expect(
      deliverMessage(journal, api, body, fresh, 'key', () => {
        if (!current) throw new Error('account changed')
      }),
    ).rejects.toThrow('account changed')
    expect(fetcher.mock.calls.some(([url]) => url === ENDPOINT)).toBe(false)
  })
})
