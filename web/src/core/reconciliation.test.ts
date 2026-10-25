import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { CONVERSATION_MESSAGES } from './admission'
import { Api } from './http'
import { LocalKeyLock, SubmissionJournal, type JournalStore, type Submission } from './journal'
import { SubmissionReconciler } from './reconciliation'

function deferred<T>() {
  let resolve!: (value: T) => void
  const promise = new Promise<T>((done) => {
    resolve = done
  })
  return { promise, resolve }
}

const receipt = (key: string) => ({
  message_id: `message-${key}`,
  state: 'queued',
  submission_key: key,
  admitted_at: '2026-10-02T10:00:00Z',
  trace_id: null,
})

function submission(key: string, state: Submission['state'] = 'uncertain'): Submission {
  return {
    key,
    state,
    endpoint: CONVERSATION_MESSAGES,
    lookup: `/v1/conversation/submissions/by-key/${key}`,
    method: 'POST',
    found: 'accepted',
    body: '{"text":"test"}',
    createdAt: 0,
    response: state === 'accepted' ? receipt(key) : null,
    failure: null,
    context: {},
  }
}

const json = (value: unknown, status = 200) =>
  new Response(JSON.stringify(value), { status, headers: { 'Content-Type': 'application/json' } })

describe('SubmissionReconciler', () => {
  let entries: Map<string, Submission>
  let journal: SubmissionJournal
  let recovery: SubmissionReconciler
  let fetcher: ReturnType<typeof vi.fn<typeof fetch>>

  beforeEach(() => {
    vi.useFakeTimers()
    vi.setSystemTime(0)
    entries = new Map()
    const store: JournalStore = {
      submission: async (key) => entries.get(key),
      saveSubmission: async (entry) => {
        entries.set(entry.key, entry)
      },
      deleteSubmission: async (key) => {
        entries.delete(key)
      },
      submissions: async () => [...entries.values()],
    }
    fetcher = vi.fn<typeof fetch>(async () => json({ error: 'not_found' }, 404))
    journal = new SubmissionJournal(store, new Api(fetcher), new LocalKeyLock())
    recovery = new SubmissionReconciler(journal)
  })

  afterEach(() => {
    recovery.close()
    vi.useRealTimers()
  })

  it('uses five bounded read-only attempts; repeated triggers and relays do not reset exhaustion', async () => {
    entries.set('lost', submission('lost'))
    const times: number[] = []
    fetcher.mockImplementation(async () => {
      times.push(Date.now())
      return json({}, 404)
    })
    recovery.trigger()
    recovery.trigger()
    await vi.advanceTimersByTimeAsync(999)
    expect(fetcher).not.toHaveBeenCalled()
    await vi.advanceTimersByTimeAsync(47_001)
    expect(times).toEqual([1_000, 3_000, 8_000, 18_000, 48_000])
    recovery.trigger()
    journal.relay({ entry: entries.get('lost')!, removed: false })
    await vi.advanceTimersByTimeAsync(120_000)
    expect(fetcher).toHaveBeenCalledTimes(5)
    await recovery.check('lost')
    expect(fetcher).toHaveBeenCalledTimes(6)
    await vi.advanceTimersByTimeAsync(120_000)
    expect(fetcher).toHaveBeenCalledTimes(6)
    expect(fetcher.mock.calls.every(([, init]) => init?.method === 'GET')).toBe(true)
    expect(entries.get('lost')?.state).toBe('uncertain')
  })

  it('keeps lookups and admission handlers inside the same two-task gate', async () => {
    for (const key of ['a', 'b', 'c']) entries.set(key, submission(key))
    const lookups = new Map<string, ReturnType<typeof deferred<Response>>>()
    fetcher.mockImplementation((input) => {
      const gate = deferred<Response>()
      lookups.set(String(input).split('/').at(-1)!, gate)
      return gate.promise
    })
    const history = deferred<boolean>()
    const handler = vi.fn(async () => history.promise)
    recovery.register(CONVERSATION_MESSAGES, handler)
    await vi.advanceTimersByTimeAsync(1_000)
    expect([...lookups.keys()]).toEqual(['a', 'b'])
    lookups.get('a')!.resolve(json(receipt('a')))
    await vi.advanceTimersByTimeAsync(0)
    expect(handler).toHaveBeenCalledTimes(1)
    expect([...lookups.keys()]).toEqual(['a', 'b'])
    history.resolve(true)
    await vi.advanceTimersByTimeAsync(0)
    expect([...lookups.keys()]).toEqual(['a', 'b', 'c'])
    lookups.get('b')!.resolve(json(receipt('b')))
    lookups.get('c')!.resolve(json(receipt('c')))
    await vi.advanceTimersByTimeAsync(0)
    expect(handler).toHaveBeenCalledTimes(3)
  })

  it('coalesces same-key manual checks and surfaces failure while automatic retries remain bounded', async () => {
    entries.set('lost', submission('lost'))
    const gate = deferred<Response>()
    fetcher.mockImplementationOnce(() => gate.promise)
    const first = recovery.check('lost')
    const second = recovery.check('lost')
    expect(second).toBe(first)
    recovery.trigger()
    await vi.advanceTimersByTimeAsync(0)
    expect(fetcher).toHaveBeenCalledTimes(1)
    const rejected = expect(first).rejects.toMatchObject({ status: 503 })
    gate.resolve(json({ error: 'unavailable' }, 503))
    await rejected
    await vi.advanceTimersByTimeAsync(48_000)
    expect(fetcher).toHaveBeenCalledTimes(6)
  })

  it('retries exact admitted identity without a lookup until history verifies it', async () => {
    entries.set('admitted', submission('admitted', 'accepted'))
    const handler = vi
      .fn()
      .mockResolvedValueOnce(false)
      .mockRejectedValueOnce(new Error('offline'))
      .mockResolvedValue(true)
    recovery.register(CONVERSATION_MESSAGES, handler)
    await vi.advanceTimersByTimeAsync(8_000)
    expect(handler).toHaveBeenCalledTimes(3)
    expect(handler.mock.calls.map(([entry]) => entry.response.message_id)).toEqual([
      'message-admitted',
      'message-admitted',
      'message-admitted',
    ])
    recovery.trigger()
    await vi.advanceTimersByTimeAsync(120_000)
    expect(handler).toHaveBeenCalledTimes(3)
    expect(fetcher).not.toHaveBeenCalled()
  })

  it('repairs malformed stored admission before invoking the handler and accepts valid legacy receipts', async () => {
    entries.set('broken', {
      ...submission('broken', 'accepted'),
      response: { message_id: 'unsafe' },
    })
    entries.set('legacy', {
      ...submission('legacy', 'accepted'),
      response: { message_id: 'old', state: 'completed' },
    })
    fetcher.mockResolvedValueOnce(json(receipt('broken')))
    const handler = vi.fn<(entry: Submission, signal: AbortSignal) => Promise<boolean>>(
      async () => true,
    )
    recovery.register(CONVERSATION_MESSAGES, handler)
    await vi.advanceTimersByTimeAsync(1_000)
    expect(fetcher).toHaveBeenCalledTimes(1)
    expect(fetcher.mock.calls[0]?.[0]).toBe('/v1/conversation/submissions/by-key/broken')
    expect(handler.mock.calls.map(([entry]) => entry.response)).toEqual(
      expect.arrayContaining([receipt('broken'), { message_id: 'old', state: 'completed' }]),
    )
  })

  it('schedules new journal entries and verifies a later registered handler without resetting an exhausted job', async () => {
    entries.set('new', submission('new'))
    journal.relay({ entry: submission('new'), removed: false })
    await vi.advanceTimersByTimeAsync(48_000)
    expect(fetcher).toHaveBeenCalledTimes(5)
    const admitted = submission('new', 'accepted')
    entries.set('new', admitted)
    journal.relay({ entry: admitted, removed: false })
    const handler = vi.fn(async () => true)
    recovery.register(CONVERSATION_MESSAGES, handler)
    await vi.advanceTimersByTimeAsync(120_000)
    expect(handler).not.toHaveBeenCalled()
    await recovery.check('new')
    expect(handler).toHaveBeenCalledTimes(1)
    expect(fetcher).toHaveBeenCalledTimes(5)
  })

  it('waits behind a live send lock, then verifies its settled receipt without another network request', async () => {
    const sent = deferred<Response>()
    fetcher.mockImplementation(async (input) =>
      String(input) === '/v1/auth/csrf' ? json({ token: 'csrf' }) : sent.promise,
    )
    const handler = vi.fn(async () => true)
    recovery.register(CONVERSATION_MESSAGES, handler)
    const sending = journal.submit({
      key: 'live',
      endpoint: CONVERSATION_MESSAGES,
      lookupPrefix: '/v1/conversation/submissions/by-key/',
      body: { text: 'test' },
      found: 'accepted',
    })
    await vi.advanceTimersByTimeAsync(1_000)
    expect(fetcher).toHaveBeenCalledTimes(2)
    sent.resolve(json(receipt('live'), 202))
    await sending
    await vi.advanceTimersByTimeAsync(0)
    expect(handler).toHaveBeenCalledTimes(1)
    expect(fetcher).toHaveBeenCalledTimes(2)
  })

  it('aborts close, ignores late lookup completion, cancels queued tasks, and never enters a closed account handler', async () => {
    for (const key of ['a', 'b', 'c']) entries.set(key, submission(key))
    const gates: Array<ReturnType<typeof deferred<Response>>> = []
    const signals: AbortSignal[] = []
    fetcher.mockImplementation((_input, init) => {
      const gate = deferred<Response>()
      gates.push(gate)
      signals.push(init!.signal!)
      return gate.promise
    })
    const handler = vi.fn(async () => true)
    recovery.register(CONVERSATION_MESSAGES, handler)
    await vi.advanceTimersByTimeAsync(1_000)
    recovery.close()
    expect(signals).toHaveLength(2)
    expect(signals.every((signal) => signal.aborted)).toBe(true)
    gates[0]!.resolve(json(receipt('a')))
    gates[1]!.resolve(json(receipt('b')))
    await vi.advanceTimersByTimeAsync(120_000)
    recovery.trigger()
    expect(fetcher).toHaveBeenCalledTimes(2)
    expect(handler).not.toHaveBeenCalled()
    expect(entries.get('a')?.state).toBe('uncertain')
  })

  it('ignores rejected and unregistered settled entries and cancels removed pending entries', async () => {
    entries.set('rejected', submission('rejected', 'rejected'))
    entries.set('accepted', submission('accepted', 'accepted'))
    entries.set('resolved', submission('resolved', 'resolved'))
    entries.set('removed', submission('removed'))
    recovery.trigger()
    await vi.advanceTimersByTimeAsync(500)
    await journal.abandon('removed')
    await vi.advanceTimersByTimeAsync(120_000)
    expect(fetcher).not.toHaveBeenCalled()
  })

  it('lets a successful manual handler acknowledge its receipt without rejecting the check', async () => {
    entries.set('acknowledged', submission('acknowledged', 'accepted'))
    const handler = vi.fn(async (entry: Submission, signal: AbortSignal) => {
      await journal.acknowledge(entry.key)
      expect(signal.aborted).toBe(false)
      return true
    })
    recovery.register(CONVERSATION_MESSAGES, handler)
    await expect(recovery.check('acknowledged')).resolves.toBeUndefined()
    await vi.advanceTimersByTimeAsync(120_000)
    expect(handler).toHaveBeenCalledTimes(1)
    expect(entries.has('acknowledged')).toBe(false)
  })

  it('keeps an acknowledging handler cancellable until its account lifecycle finishes', async () => {
    entries.set('admitted', submission('admitted', 'accepted'))
    const gate = deferred<boolean>()
    let activeSignal: AbortSignal | undefined
    recovery.register(CONVERSATION_MESSAGES, async (entry, signal) => {
      activeSignal = signal
      await journal.acknowledge(entry.key)
      return gate.promise
    })
    const checking = recovery.check('admitted')
    const rejected = expect(checking).rejects.toMatchObject({ name: 'AbortError' })
    await vi.advanceTimersByTimeAsync(0)
    recovery.close()
    expect(activeSignal?.aborted).toBe(true)
    gate.resolve(true)
    await rejected
  })

  it('never hands a mismatched durable receipt to history when repair remains unavailable', async () => {
    entries.set('broken', {
      ...submission('broken', 'accepted'),
      response: receipt('different-key'),
    })
    const handler = vi.fn(async () => true)
    recovery.register(CONVERSATION_MESSAGES, handler)
    await vi.advanceTimersByTimeAsync(48_000)
    expect(fetcher).toHaveBeenCalledTimes(5)
    expect(handler).not.toHaveBeenCalled()
    expect(entries.get('broken')?.state).toBe('accepted')
  })

  it('does not look up known unsent preparations but keeps legacy preparations recoverable', async () => {
    entries.set('unsent', { ...submission('unsent', 'prepared'), mayHaveBeenAdmitted: false })
    entries.set('legacy', submission('legacy', 'prepared'))
    recovery.trigger()
    await vi.advanceTimersByTimeAsync(1_000)
    await recovery.check('unsent')
    expect(fetcher).toHaveBeenCalledTimes(1)
    expect(fetcher.mock.calls[0]?.[0]).toBe('/v1/conversation/submissions/by-key/legacy')
    expect(entries.get('unsent')?.state).toBe('prepared')
    // An explicit later send can make this same key uncertain; that state needs recovery.
    const retried = { ...submission('unsent'), mayHaveBeenAdmitted: true }
    entries.set('unsent', retried)
    journal.relay({ entry: retried, removed: false })
    await vi.advanceTimersByTimeAsync(1_000)
    expect(fetcher).toHaveBeenCalledTimes(2)
    expect(fetcher.mock.calls[1]?.[0]).toBe('/v1/conversation/submissions/by-key/unsent')
  })

  it('verifies remaining receipts for a new history consumer while retaining its retry budget', async () => {
    entries.set('admitted', submission('admitted', 'accepted'))
    const first = vi.fn(async () => true)
    const unregister = recovery.register(CONVERSATION_MESSAGES, first)
    await vi.advanceTimersByTimeAsync(1_000)
    expect(first).toHaveBeenCalledTimes(1)
    unregister()
    recovery.trigger()
    await vi.advanceTimersByTimeAsync(0)
    const second = vi.fn(async () => false)
    recovery.register(CONVERSATION_MESSAGES, second)
    await vi.advanceTimersByTimeAsync(47_000)
    expect(second).toHaveBeenCalledTimes(4)
    recovery.trigger()
    await vi.advanceTimersByTimeAsync(120_000)
    expect(second).toHaveBeenCalledTimes(4)
    expect(fetcher).not.toHaveBeenCalled()
  })
})
