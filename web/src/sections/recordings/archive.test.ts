import { describe, expect, it, vi } from 'vitest'
import { Api, ApiError, NetworkError, type WriteOptions } from '@/core/http'
import type { Recording, RecordingsClient, Transcript, Usage } from './api'
import { RecordingsArchive } from './archive'

const OWNER = '00000000-0000-4000-8000-000000000101'
const OTHER_OWNER = '00000000-0000-4000-8000-000000000102'
const TIME = '2026-10-05T12:00:00Z'
const LATER = '2026-10-05T12:01:00Z'
const id = (number: number) => `00000000-0000-4000-8000-${String(number).padStart(12, '0')}`
const usage: Usage = { used_bytes: 100, limits: { retention_days: 7, max_user_bytes: 1024 } }

function row(number: number, status: Transcript['status'] = 'pending'): Recording {
  return {
    id: id(number),
    session_id: id(1000),
    sequence: number,
    sha256: '0'.repeat(64),
    stored: true,
    format: 'ogg-opus',
    bytes: 100,
    duration_ms: 30_000,
    expires_at: null,
    started_at: TIME,
    received_at: TIME,
    state: 'stored',
    transcription: {
      status,
      preview: status === 'ready' ? `Synthetic speech ${number}` : null,
      error_code: status === 'failed' ? 'synthetic_failure' : null,
      updated_at: TIME,
      retry_may_repeat: false,
    },
  }
}

function text(number: number, status: Transcript['status'] = 'ready'): Transcript {
  return {
    id: id(number),
    status,
    text: status === 'ready' ? `Synthetic speech ${number}` : null,
    provider: 'synthetic-provider',
    model: 'synthetic-model',
    transcribed_at: status === 'ready' ? LATER : null,
    updated_at: LATER,
    error_code: status === 'failed' ? 'synthetic_failure' : null,
    retry_may_repeat: false,
  }
}

function deferred<T>() {
  let resolve!: (value: T) => void
  let reject!: (reason: unknown) => void
  const promise = new Promise<T>((yes, no) => {
    resolve = yes
    reject = no
  })
  return { promise, resolve, reject }
}

function setup(items = [row(1)]) {
  const client = {
    list: vi
      .fn<RecordingsClient['list']>()
      .mockResolvedValue({ recordings: items, next_cursor: null }),
    usage: vi.fn<RecordingsClient['usage']>().mockResolvedValue(usage),
    transcription: vi
      .fn<RecordingsClient['transcription']>()
      .mockImplementation(async (value) => text(Number(value.slice(-12)))),
    remove: vi.fn<RecordingsClient['remove']>().mockResolvedValue(undefined),
    retry: vi.fn<RecordingsClient['retry']>().mockResolvedValue(text(1, 'pending')),
  }
  const archive = new RecordingsArchive(client)
  archive.account(OWNER)
  return { client, archive }
}

const denied = () => new ApiError(403, 'no_access', null, null, null)

function expectPurged(archive: RecordingsArchive) {
  expect(archive.items.value).toEqual([])
  expect(archive.usage.value).toBeNull()
  expect(archive.next.value).toBeNull()
  expect(archive.selected.value).toBeNull()
  expect(archive.transcript.value).toBeNull()
  expect(archive.loaded.value).toBe(false)
}

describe('recordings archive reads', () => {
  it('loads pages with the active filter, deduplicates recording IDs and updates the cursor', async () => {
    const { archive, client } = setup()
    const range = { from: '2026-10-05T00:00:00+03:00', to: '2026-10-06T00:00:00+03:00' }
    archive.account(OWNER, range)
    client.list
      .mockResolvedValueOnce({ recordings: [row(1)], next_cursor: 'synthetic-next' })
      .mockResolvedValueOnce({ recordings: [row(1, 'ready'), row(2)], next_cursor: null })
    await archive.refresh()
    await archive.refresh(true)
    expect(client.list.mock.calls.map(([filter, cursor]) => [filter, cursor])).toEqual([
      [range, null],
      [range, 'synthetic-next'],
    ])
    expect(archive.items.value.map((item) => [item.id, item.transcription.status])).toEqual([
      [id(1), 'ready'],
      [id(2), 'pending'],
    ])
    expect(archive.usage.value).toEqual(usage)
    expect(archive.next.value).toBeNull()
    expect(archive.loaded.value).toBe(true)
    await archive.refresh(true)
    expect(client.list).toHaveBeenCalledTimes(2)
  })

  it.each([OWNER, OTHER_OWNER])(
    'aborts and ignores reads after account or filter changes to %s',
    async (owner) => {
      const { archive, client } = setup()
      const oldPage = deferred<Awaited<ReturnType<RecordingsClient['list']>>>()
      const oldUsage = deferred<Usage>()
      client.list.mockReturnValueOnce(oldPage.promise)
      client.usage.mockReturnValueOnce(oldUsage.promise)
      const obsolete = archive.refresh()
      const oldSignal = client.list.mock.calls[0]![2]
      const range = { from: '2026-10-06T00:00:00Z', to: '2026-10-07T00:00:00Z' }
      archive.account(owner, range)
      expect(oldSignal.aborted).toBe(true)
      client.list.mockResolvedValueOnce({ recordings: [row(2)], next_cursor: null })
      client.usage.mockResolvedValueOnce({ ...usage, used_bytes: 200 })
      await archive.refresh()
      oldPage.resolve({ recordings: [row(1, 'ready')], next_cursor: 'old-cursor' })
      oldUsage.resolve(usage)
      await obsolete
      expect(archive.items.value.map((item) => item.id)).toEqual([id(2)])
      expect(archive.usage.value?.used_bytes).toBe(200)
      expect(archive.next.value).toBeNull()
      expect(client.list.mock.calls[1]![0]).toEqual(range)
    },
  )

  it('aborts a replaced refresh and ignores its late successful response', async () => {
    const { archive, client } = setup()
    const page = deferred<Awaited<ReturnType<RecordingsClient['list']>>>()
    client.list.mockReturnValueOnce(page.promise)
    const first = archive.refresh()
    const signal = client.list.mock.calls[0]![2]
    client.list.mockResolvedValueOnce({ recordings: [row(2)], next_cursor: null })
    await archive.refresh()
    expect(signal.aborted).toBe(true)
    page.resolve({ recordings: [row(1)], next_cursor: 'stale' })
    await first
    expect(archive.items.value.map((item) => item.id)).toEqual([id(2)])
    expect(archive.loading.value).toBe(false)
  })

  it('clears all personal content and transient state on logout', async () => {
    const { archive, client } = setup([row(1, 'ready')])
    await archive.refresh()
    await archive.select(id(1))
    archive.next.value = 'synthetic-cursor'
    archive.error.value = new Error('synthetic-read-error')
    archive.usageError.value = new Error('synthetic-usage-error')
    archive.detailError.value = new Error('synthetic-detail-error')
    archive.pollError.value = new Error('synthetic-poll-error')
    archive.mutationError.value = new Error('synthetic-mutation-error')
    archive.mutationUnknown.value = true
    archive.loading.value = true
    archive.loadingMore.value = true
    archive.detailLoading.value = true
    archive.busy.value = true
    archive.account(null)
    expectPurged(archive)
    for (const state of [
      archive.error,
      archive.usageError,
      archive.detailError,
      archive.pollError,
      archive.mutationError,
    ])
      expect(state.value).toBeNull()
    for (const state of [
      archive.loading,
      archive.loadingMore,
      archive.detailLoading,
      archive.busy,
      archive.mutationUnknown,
    ])
      expect(state.value).toBe(false)
    await archive.refresh()
    await archive.select(null)
    await archive.poll()
    expect(client.list).toHaveBeenCalledOnce()
    expect(client.transcription).toHaveBeenCalledOnce()
  })

  it('keeps a newer selected detail when an aborted selection resolves late', async () => {
    const { archive, client } = setup([row(1), row(2)])
    await archive.refresh()
    const oldDetail = deferred<Transcript>()
    const newDetail = deferred<Transcript>()
    client.transcription
      .mockReturnValueOnce(oldDetail.promise)
      .mockReturnValueOnce(newDetail.promise)
    const first = archive.select(id(1))
    const signal = client.transcription.mock.calls[0]![1]
    const second = archive.select(id(2))
    expect(signal.aborted).toBe(true)
    newDetail.resolve(text(2))
    await second
    oldDetail.resolve(text(1))
    await first
    expect(archive.selected.value).toBe(id(2))
    expect(archive.transcript.value).toEqual(text(2))
    expect(archive.items.value[0]!.transcription.status).toBe('pending')
    expect(archive.detailLoading.value).toBe(false)
  })

  it('does not replace a newer transcript with an older poll response', async () => {
    const { archive, client } = setup()
    await archive.refresh()
    const pollDetail = deferred<Transcript>()
    client.transcription.mockReturnValueOnce(pollDetail.promise)
    const polling = archive.poll()
    await archive.select(id(1))
    pollDetail.resolve({ ...text(1, 'processing'), updated_at: TIME })
    await polling
    expect(archive.transcript.value).toEqual(text(1))
    expect(archive.items.value[0]!.transcription.status).toBe('ready')
  })
})

describe('recordings access revocation', () => {
  it.each(['list', 'usage', 'detail', 'poll', 'delete', 'retry'] as const)(
    'purges cached audio metadata and transcript after a 403 from %s',
    async (source) => {
      const { archive, client } = setup()
      await archive.refresh()
      await archive.select(id(1))
      archive.next.value = 'synthetic-next'
      if (source === 'list') {
        client.list.mockRejectedValueOnce(denied())
        await archive.refresh()
      } else if (source === 'usage') {
        client.usage.mockRejectedValueOnce(denied())
        await archive.refresh()
      } else if (source === 'detail') {
        client.transcription.mockRejectedValueOnce(denied())
        await archive.select(id(1))
      } else if (source === 'poll') {
        archive.items.value[0]!.transcription.status = 'pending'
        client.transcription.mockRejectedValueOnce(denied())
        await archive.poll()
      } else {
        client[source === 'delete' ? 'remove' : 'retry'].mockRejectedValueOnce(denied())
        expect(await archive.mutate(source, id(1))).toBe(false)
      }
      expectPurged(archive)
      expect(archive.error.value).toMatchObject({ status: 403 })
    },
  )

  it('aborts concurrent reads and never restores personal state after a denied detail', async () => {
    const { archive, client } = setup()
    await archive.refresh()
    const latePage = deferred<Awaited<ReturnType<RecordingsClient['list']>>>()
    const lateUsage = deferred<Usage>()
    client.list.mockReturnValueOnce(latePage.promise)
    client.usage.mockReturnValueOnce(lateUsage.promise)
    const reading = archive.refresh()
    const signal = client.list.mock.calls[1]![2]
    client.transcription.mockRejectedValueOnce(denied())
    await archive.select(id(1))
    expect(signal.aborted).toBe(true)
    latePage.resolve({ recordings: [row(1, 'ready')], next_cursor: 'private-next' })
    lateUsage.resolve(usage)
    await reading
    expectPurged(archive)
  })
})

describe('recordings polling', () => {
  it('polls only pending rows in bounded rotating batches, prioritizing the selected row', async () => {
    const pendingRows = Array.from({ length: 16 }, (_, index) => row(index + 1))
    const { archive, client } = setup([
      ...pendingRows,
      row(20, 'ready'),
      row(21, 'failed'),
      row(22, 'empty'),
    ])
    await archive.refresh()
    client.transcription.mockImplementation(async (value) =>
      text(Number(value.slice(-12)), 'processing'),
    )
    await archive.select(id(11))
    client.transcription.mockClear()
    await archive.poll()
    expect(client.transcription).toHaveBeenCalledTimes(8)
    expect(client.transcription.mock.calls[0]![0]).toBe(id(11))
    await archive.poll()
    expect(client.transcription).toHaveBeenCalledTimes(16)
    await archive.poll()
    expect(client.transcription).toHaveBeenCalledTimes(24)
    const requested = new Set(client.transcription.mock.calls.map(([value]) => value))
    expect([...requested].sort()).toEqual(pendingRows.map((item) => item.id).sort())
    expect(archive.hasPending.value).toBe(true)
  })

  it('does not overlap polls and ignores results after cancellation', async () => {
    const { archive, client } = setup([row(1), row(2, 'processing')])
    await archive.refresh()
    const first = deferred<Transcript>()
    const second = deferred<Transcript>()
    client.transcription.mockReturnValueOnce(first.promise).mockReturnValueOnce(second.promise)
    const polling = archive.poll()
    await archive.poll()
    expect(client.transcription).toHaveBeenCalledTimes(2)
    const signal = client.transcription.mock.calls[0]![1]
    archive.cancelPolling()
    expect(signal.aborted).toBe(true)
    first.resolve(text(1))
    second.resolve(text(2))
    await polling
    expect(archive.items.value.map((item) => item.transcription.status)).toEqual([
      'pending',
      'processing',
    ])
    expect(archive.pollError.value).toBeNull()
  })

  it('does not query again when all transcripts are finished or the owner has left', async () => {
    const { archive, client } = setup([row(1, 'ready'), row(2, 'failed'), row(3, 'empty')])
    await archive.refresh()
    expect(archive.hasPending.value).toBe(false)
    await archive.poll()
    archive.account(null)
    await archive.poll()
    expect(client.transcription).not.toHaveBeenCalled()
  })
})

describe('recordings writes', () => {
  it.each(['delete', 'retry'] as const)(
    'guards %s against late account changes before dispatch',
    async (kind) => {
      const { archive, client } = setup()
      await archive.refresh()
      const result = deferred<Transcript | undefined>()
      let options!: WriteOptions
      if (kind === 'delete')
        client.remove.mockImplementationOnce(async (_id, received) => {
          options = received
          await result.promise
        })
      else
        client.retry.mockImplementationOnce(async (_id, received) => {
          options = received
          return (await result.promise)!
        })
      const writing = archive.mutate(kind, id(1))
      expect(options.expectedUser).toBe(OWNER)
      expect(() => options.beforeSend?.()).not.toThrow()
      archive.account(OTHER_OWNER)
      expect(options.signal?.aborted).toBe(true)
      expect(() => options.beforeSend?.()).toThrowError(/Account changed/)
      result.resolve(kind === 'delete' ? undefined : text(1))
      expect(await writing).toBe(false)
      expectPurged(archive)
      expect(archive.busy.value).toBe(false)
      expect(archive.mutationError.value).toBeNull()
    },
  )

  it('prevents overlapping writes and replaces the pending status with the exact retry result', async () => {
    const { archive, client } = setup([row(1, 'failed')])
    await archive.refresh()
    const retry = deferred<Transcript>()
    client.retry.mockReturnValueOnce(retry.promise)
    const writing = archive.mutate('retry', id(1))
    expect(await archive.mutate('delete', id(1))).toBe(false)
    expect(client.remove).not.toHaveBeenCalled()
    retry.resolve(text(1, 'pending'))
    expect(await writing).toBe(true)
    expect(archive.items.value[0]!.transcription.status).toBe('pending')
    expect(archive.busy.value).toBe(false)
  })

  it('clears a deleted selection and refreshes list and storage usage after its receipt', async () => {
    const { archive, client } = setup([row(1, 'ready'), row(2)])
    await archive.refresh()
    await archive.select(id(1))
    client.list.mockResolvedValueOnce({ recordings: [row(2)], next_cursor: null })
    client.usage.mockResolvedValueOnce({ ...usage, used_bytes: 0 })
    expect(await archive.mutate('delete', id(1))).toBe(true)
    expect(archive.items.value.map((item) => item.id)).toEqual([id(2)])
    expect(archive.selected.value).toBeNull()
    expect(archive.transcript.value).toBeNull()
    expect(archive.usage.value?.used_bytes).toBe(0)
    expect(client.remove).toHaveBeenCalledWith(
      id(1),
      expect.objectContaining({ expectedUser: OWNER }),
    )
  })

  it.each(['delete', 'retry'] as const)(
    'retains content and marks a lost %s outcome without sending it again',
    async (kind) => {
      const { archive, client } = setup([row(1, 'ready')])
      await archive.refresh()
      await archive.select(id(1))
      const method = client[kind === 'delete' ? 'remove' : 'retry']
      method.mockRejectedValueOnce(new NetworkError(false))
      expect(await archive.mutate(kind, id(1))).toBe(false)
      expect(archive.mutationUnknown.value).toBe(true)
      expect(archive.items.value.map((item) => item.id)).toEqual([id(1)])
      expect(archive.transcript.value?.text).toBe('Synthetic speech 1')
      expect(method).toHaveBeenCalledOnce()
      await archive.refresh()
      expect(archive.mutationUnknown.value).toBe(false)
      expect(method).toHaveBeenCalledOnce()
    },
  )

  it.each(['delete', 'retry'] as const)(
    'keeps an uncertain %s guarded across closing and reopening until a verified refresh',
    async (kind) => {
      const { archive, client } = setup([row(1, 'failed')])
      client.transcription.mockResolvedValue(text(1, 'failed'))
      await archive.refresh()
      await archive.select(id(1))
      const method = client[kind === 'delete' ? 'remove' : 'retry']
      const failure = new NetworkError(false)
      method.mockRejectedValueOnce(failure)
      expect(await archive.mutate(kind, id(1))).toBe(false)

      await archive.select(null)
      expect(archive.mutationUnknown.value).toBe(true)
      expect(archive.mutationError.value).toBe(failure)
      await archive.select(id(1))
      expect(archive.mutationUnknown.value).toBe(true)
      expect(archive.mutationError.value).toBe(failure)
      expect(await archive.mutate('delete', id(1))).toBe(false)
      expect(await archive.mutate('retry', id(1))).toBe(false)
      expect(method).toHaveBeenCalledOnce()

      archive.next.value = 'another-page'
      client.list.mockResolvedValueOnce({ recordings: [row(2)], next_cursor: null })
      await archive.refresh(true)
      expect(archive.mutationUnknown.value).toBe(true)
      expect(archive.mutationError.value).toBe(failure)
      expect(await archive.mutate(kind, id(1))).toBe(false)

      client.list.mockRejectedValueOnce(new NetworkError(false))
      await archive.refresh()
      expect(archive.mutationUnknown.value).toBe(true)
      expect(archive.mutationError.value).toBe(failure)
      expect(await archive.mutate(kind, id(1))).toBe(false)
      expect(method).toHaveBeenCalledOnce()

      await archive.refresh()
      expect(archive.mutationUnknown.value).toBe(false)
      expect(archive.mutationError.value).toBeNull()
      if (kind === 'delete')
        client.list.mockResolvedValueOnce({ recordings: [], next_cursor: null })
      expect(await archive.mutate(kind, id(1))).toBe(true)
      expect(method).toHaveBeenCalledTimes(2)
    },
  )

  it.each(['delete', 'retry'] as const)(
    'keeps the selected recording attached to an in-flight %s failure',
    async (kind) => {
      const { archive, client } = setup([row(1, 'failed'), row(2, 'failed')])
      client.transcription.mockImplementation(async (value) =>
        text(Number(value.slice(-12)), 'failed'),
      )
      await archive.refresh()
      await archive.select(id(1))
      const result = deferred<Transcript | undefined>()
      if (kind === 'delete')
        client.remove.mockImplementationOnce(async () => {
          await result.promise
        })
      else client.retry.mockImplementationOnce(async () => (await result.promise)!)
      const writing = archive.mutate(kind, id(1))
      expect(archive.busy.value).toBe(true)

      await archive.select(id(2))
      expect(archive.selected.value).toBe(id(1))
      expect(archive.transcript.value?.id).toBe(id(1))
      expect(client.transcription).toHaveBeenCalledOnce()

      const failure = new ApiError(409, 'conflict', null, null, null)
      result.reject(failure)
      expect(await writing).toBe(false)
      expect(archive.selected.value).toBe(id(1))
      expect(archive.mutationError.value).toBe(failure)
      expect(archive.busy.value).toBe(false)
    },
  )

  it.each([
    [new ApiError(409, 'conflict', null, null, null), false],
    [new ApiError(503, 'unavailable', null, null, null), true],
    [new Error('Invalid deletion receipt'), true],
  ])('distinguishes a refused mutation from an uncertain response %#', async (error, unknown) => {
    const { archive, client } = setup()
    await archive.refresh()
    client.remove.mockRejectedValueOnce(error)
    expect(await archive.mutate('delete', id(1))).toBe(false)
    expect(archive.mutationUnknown.value).toBe(unknown)
    expect(archive.items.value).toHaveLength(1)
  })

  it('does not call a failure before HTTP dispatch an unknown write outcome', async () => {
    const fetcher = vi.fn<typeof fetch>()
    const api = new Api(fetcher)
    const failure = await api
      .send('DELETE', '/synthetic-write', undefined, {
        beforeSend: () => {
          throw new DOMException('Account changed', 'AbortError')
        },
      })
      .catch((error: unknown) => error)
    expect(fetcher).not.toHaveBeenCalled()
    const { archive, client } = setup()
    await archive.refresh()
    client.remove.mockRejectedValueOnce(failure)
    expect(await archive.mutate('delete', id(1))).toBe(false)
    expect(archive.mutationUnknown.value).toBe(false)
  })
})
