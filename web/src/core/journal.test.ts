import 'fake-indexeddb/auto'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { Api } from './http'
import { LocalKeyLock, SubmissionJournal, type JournalChange, type Submission } from './journal'
import { AccountStore, purgeAccount } from './storage'
import { SubmissionReconciler } from './reconciliation'
import { CLARIFICATIONS, clarificationReceipt } from '../sections/assistant/clarifications'

type Route = (init: RequestInit) => Response | Error

/** A scripted server: each path answers from its own queue, and every request is recorded. */
function server() {
  const routes = new Map<string, Route[]>()
  const requests: Array<{ path: string; method: string; body: unknown; key: string | null }> = []
  const fetcher = (async (input: RequestInfo | URL, init: RequestInit = {}) => {
    const path = String(input)
    const headers = new Headers(init.headers)
    requests.push({
      path,
      method: init.method ?? 'GET',
      body: init.body,
      key: headers.get('Idempotency-Key'),
    })
    if (path === '/v1/auth/csrf') return json({ token: 'csrf' })
    const reply = routes.get(path)?.shift()?.(init)
    if (!reply) throw new Error(`unexpected ${path}`)
    if (reply instanceof Error) throw reply
    return reply
  }) as typeof fetch
  const on = (path: string, ...replies: Route[]) =>
    routes.set(path, [...(routes.get(path) ?? []), ...replies])
  const writes = () => requests.filter((request) => request.method !== 'GET')
  return { fetcher, on, requests, writes }
}

const json = (value: unknown, status = 200) =>
  new Response(JSON.stringify(value), { status, headers: { 'Content-Type': 'application/json' } })
const reply =
  (value: unknown, status = 200): Route =>
  () =>
    json(value, status)
const lost: Route = () => new TypeError('Failed to fetch')

function apiWithOwnerCapture(fetcher: typeof fetch, observed: string[]) {
  return new Api(async (input, init) => {
    if (String(input) === operation.endpoint && init?.method === 'POST')
      observed.push(new Headers(init.headers).get('X-Expected-User') ?? '')
    return fetcher(input, init)
  })
}

const operation = {
  endpoint: '/v1/tasks/operations',
  lookupPrefix: '/v1/tasks/operations/by-key/',
  body: { kind: 'task.create', payload: { title: 'Купить молоко' } },
  key: 'key-1',
}
const lookup = '/v1/tasks/operations/by-key/key-1'

describe('SubmissionJournal', () => {
  let store: AccountStore
  let http: ReturnType<typeof server>
  let journal: SubmissionJournal
  let changes: JournalChange[]

  beforeEach(async () => {
    store = await AccountStore.open('user-1')
    http = server()
    journal = new SubmissionJournal(store, new Api(http.fetcher), new LocalKeyLock(), () => 1000)
    changes = []
    journal.subscribe((change) => changes.push(change))
  })

  afterEach(async () => {
    store.close()
    await purgeAccount('user-1')
  })

  it('stores the exact request before sending and resolves with the receipt', async () => {
    http.on('/v1/tasks/operations', (init) => {
      // The entry is durable before the first network call.
      expect(init.body).toBe(JSON.stringify(operation.body))
      return json({ operation_id: 'op-1' }, 201)
    })
    const entry = await journal.submit(operation)
    expect(entry.state).toBe('resolved')
    expect(entry.response).toEqual({ operation_id: 'op-1' })
    expect(changes.map((change) => change.entry.state)).toEqual(['prepared', 'sending', 'resolved'])
    expect((await store.submission('key-1'))?.state).toBe('resolved')
    expect(http.writes()[0]?.key).toBe('key-1')
  })

  it('marks admission-only endpoints accepted', async () => {
    http.on(
      '/v1/conversation/messages',
      reply(
        {
          message_id: 'm1',
          state: 'queued',
          submission_key: 'msg-1',
          admitted_at: '2026-10-02T12:00:00Z',
          trace_id: null,
        },
        202,
      ),
    )
    const entry = await journal.submit({
      endpoint: '/v1/conversation/messages',
      lookupPrefix: '/v1/conversation/submissions/by-key/',
      body: { text: 'Привет' },
      found: 'accepted',
      key: 'msg-1',
    })
    expect(entry.state).toBe('accepted')
  })

  it('retains a first CSRF failure as a known refusal, including after restarting the journal', async () => {
    const api = new Api(async () => {
      throw new TypeError('offline')
    })
    journal = new SubmissionJournal(store, api, new LocalKeyLock())
    await expect(journal.submit(operation)).rejects.toThrow()
    expect(await store.submission(operation.key)).toMatchObject({
      state: 'rejected',
      mayHaveBeenAdmitted: false,
      attempt: { number: 1, disposition: 'not_dispatched' },
      diagnostic: { phase: 'csrf' },
      body: JSON.stringify(operation.body),
    })
    const restarted = new SubmissionJournal(store, new Api(http.fetcher), new LocalKeyLock())
    await restarted.recover()
    expect(http.requests).toHaveLength(0)
  })

  it('does not erase prior uncertainty after missing lookup and a later CSRF failure', async () => {
    let csrfFails = false
    const api = new Api((input, init) => {
      if (csrfFails && String(input) === '/v1/auth/csrf')
        return Promise.reject(new TypeError('offline'))
      return http.fetcher(input, init)
    })
    journal = new SubmissionJournal(store, api, new LocalKeyLock())
    http.on(operation.endpoint, lost)
    await expect(journal.submit(operation)).rejects.toThrow()
    api.forget()
    csrfFails = true
    http.on(lookup, reply({ error: 'not_found' }, 404))
    await expect(journal.retry(operation.key)).rejects.toThrow()
    expect(await store.submission(operation.key)).toMatchObject({
      state: 'uncertain',
      mayHaveBeenAdmitted: true,
      attempt: { number: 2, disposition: 'not_dispatched' },
      body: JSON.stringify(operation.body),
    })
    expect(http.writes()).toHaveLength(1)
  })

  it('keeps a malformed 202 unknown and resolves only a matching complete receipt', async () => {
    const request = {
      ...operation,
      endpoint: '/v1/conversation/messages',
      lookupPrefix: '/v1/conversation/submissions/by-key/',
      found: 'accepted' as const,
    }
    http.on(request.endpoint, reply({ message_id: 'unverified' }, 202))
    await expect(journal.submit(request)).rejects.toThrow('Invalid admission')
    expect((await store.submission(request.key))?.state).toBe('uncertain')
    const receipt = {
      submission_key: request.key,
      message_id: 'verified',
      state: 'completed',
      admitted_at: '2026-10-02T12:00:00Z',
      trace_id: null,
    }
    http.on(
      request.lookupPrefix + request.key,
      reply({ ...receipt, submission_key: 'wrong' }),
      reply(receipt),
    )
    await expect(journal.check(request.key)).rejects.toThrow('Invalid admission')
    expect((await store.submission(request.key))?.state).toBe('uncertain')
    expect(await journal.check(request.key)).toMatchObject({ state: 'accepted', response: receipt })
    expect(http.writes()).toHaveLength(1)
  })

  it('does not overwrite a durable existing key through a second submit', async () => {
    http.on(operation.endpoint, lost)
    await expect(journal.submit(operation)).rejects.toThrow()
    const original = await store.submission(operation.key)
    await expect(journal.submit({ ...operation, body: { text: 'different' } })).rejects.toThrow(
      'key already exists',
    )
    expect(await store.submission(operation.key)).toEqual(original)
    expect(http.writes()).toHaveLength(1)
  })

  it('never starts network delivery when the write-ahead IndexedDB save fails', async () => {
    vi.spyOn(store, 'saveSubmission').mockRejectedValueOnce(new Error('storage unavailable'))
    await expect(journal.submit(operation)).rejects.toThrow('storage unavailable')
    expect(http.requests).toHaveLength(0)
    expect(await store.submission(operation.key)).toBeUndefined()
  })

  it('retains proof of no dispatch when IndexedDB cannot persist the sending marker', async () => {
    const save = store.saveSubmission.bind(store)
    vi.spyOn(store, 'saveSubmission').mockImplementation(async (entry) => {
      if (entry.state === 'sending') throw new Error('storage unavailable')
      await save(entry)
    })
    await expect(journal.submit(operation)).rejects.toThrow('storage unavailable')
    expect(await store.submission(operation.key)).toMatchObject({
      state: 'prepared',
      mayHaveBeenAdmitted: false,
    })
    expect(http.requests).toHaveLength(0)
    await journal.recover()
    expect(http.requests).toHaveLength(0)
    await journal.acknowledge(operation.key)
    expect(await store.submission(operation.key)).toBeUndefined()
  })

  it('rechecks account ownership after persistence and before the first network write', async () => {
    let current = true
    journal.subscribe(({ entry }) => {
      if (entry.state === 'sending') current = false
    })
    await expect(
      journal.submit({
        ...operation,
        beforeSend() {
          if (!current) throw new Error('account changed')
        },
      }),
    ).rejects.toThrow('account changed')
    expect(http.writes()).toHaveLength(0)
    expect(await store.submission(operation.key)).toMatchObject({
      state: 'rejected',
      attempt: { disposition: 'not_dispatched' },
      mayHaveBeenAdmitted: false,
    })
  })

  it('binds a legacy retry to the originating account while preserving its key and body', async () => {
    http.on(operation.endpoint, lost, reply({ operation_id: 'op' }, 201))
    await expect(journal.submit(operation)).rejects.toThrow('network')
    http.on(lookup, reply({ error: 'not_found' }, 404))
    const send = http.fetcher
    const observed: string[] = []
    const original = apiWithOwnerCapture(send, observed)
    const retryJournal = new SubmissionJournal(store, original, new LocalKeyLock())
    await retryJournal.retry(operation.key, () => undefined, 'owner-A')
    expect(observed).toEqual(['owner-A'])
    expect((await store.submission(operation.key))?.context.expectedUser).toBe('owner-A')
    expect(http.writes().map(({ key }) => key)).toEqual(['key-1', 'key-1'])
    expect(http.writes()[0]?.body).toBe(http.writes()[1]?.body)
  })

  it('looks up a running write again only when told how to read the receipt', async () => {
    http.on(operation.endpoint, reply({ state: 'running' }, 202))
    http.on(
      lookup,
      reply({ result: { state: 'running' } }),
      reply({ result: { state: 'completed' } }),
    )
    const accepted = await journal.submit({ ...operation, found: 'accepted' })
    expect(accepted.state).toBe('accepted')
    // Without a classifier an admitted write is settled: nothing is looked up.
    expect((await journal.check('key-1')).state).toBe('accepted')
    const classify = (response: unknown) =>
      (response as { result: { state: string } }).result.state === 'completed'
        ? 'resolved'
        : 'accepted'
    expect((await journal.check('key-1', classify)).state).toBe('accepted')
    const done = await journal.check('key-1', classify)
    expect(done.state).toBe('resolved')
    expect(done.response).toEqual({ result: { state: 'completed' } })
    expect(http.writes()).toHaveLength(1)
  })

  /** The receipt a keyed write's HTTP 200 must carry: a clarification's, as an example. */
  function receiptOf(entry: Submission) {
    try {
      return clarificationReceipt(entry.response, entry.key, JSON.parse(entry.body))
    } catch {
      return null
    }
  }
  const classifyReceipt = (entry: Submission) => (response: unknown) =>
    ['applied', 'queued', 'rejected', 'paused'].includes(
      clarificationReceipt(response, entry.key, JSON.parse(entry.body)).state,
    )
      ? ('resolved' as const)
      : ('accepted' as const)

  function clarificationFixture() {
    const body = {
      message_id: crypto.randomUUID(),
      generation: crypto.randomUUID(),
      text: 'Нет, яблоко 200 г',
    }
    const request = {
      endpoint: CLARIFICATIONS,
      lookupPrefix: `${CLARIFICATIONS}/by-key/`,
      body,
      key: 'clarification-key',
      found: 'accepted' as const,
    }
    const receipt = {
      id: crypto.randomUUID(),
      submission_key: request.key,
      message_id: body.message_id,
      generation: body.generation,
      source_message_id: crypto.randomUUID(),
      source_revision: 1,
      intent_revision: 1,
      state: 'queued',
      reason: 'request_finished',
      question: null,
      admitted_at: '2026-10-05T09:00:00Z',
      delivered_at: null,
      applied_at: null,
    }
    return { request, receipt, path: request.lookupPrefix + request.key }
  }

  it.each(['malformed', 'foreign'] as const)(
    'checks a %s HTTP200 clarification receipt by the same key without another POST',
    async (kind) => {
      const { request, receipt, path } = clarificationFixture()
      const wrong =
        kind === 'malformed' ? { state: 'queued' } : { ...receipt, message_id: crypto.randomUUID() }
      http.on(CLARIFICATIONS, reply(wrong))
      http.on(path, reply(receipt))
      const entry = await journal.submit(request)
      expect(entry.state).toBe('resolved')
      const recovery = new SubmissionReconciler(journal)
      try {
        recovery.register(CLARIFICATIONS, async (pending, signal) => {
          const checked = await journal.check(pending.key, classifyReceipt(pending), signal)
          return receiptOf(checked)?.source_message_id === receipt.source_message_id
        })
        await Promise.all([recovery.check(entry.key), recovery.check(entry.key)])
        const checked = (await store.submission(entry.key))!
        expect(receiptOf(checked)).toMatchObject(receipt)
        expect(http.requests.filter((item) => item.path === path)).toHaveLength(1)
        expect(http.writes()).toHaveLength(1)
        expect(http.writes()[0]?.key).toBe(request.key)
      } finally {
        recovery.close()
      }
    },
  )

  it('keeps a validated terminal HTTP200 clarification settled without a lookup', async () => {
    const { request, receipt, path } = clarificationFixture()
    http.on(CLARIFICATIONS, reply(receipt))
    const entry = await journal.submit(request)
    const recovery = new SubmissionReconciler(journal)
    try {
      recovery.register(CLARIFICATIONS, async (pending, signal) => {
        const checked = await journal.check(pending.key, classifyReceipt(pending), signal)
        return receiptOf(checked)?.source_message_id === receipt.source_message_id
      })
      await recovery.check(entry.key)
      expect((await journal.check(entry.key, classifyReceipt(entry))).response).toEqual(receipt)
      expect(http.requests.filter((item) => item.path === path)).toHaveLength(0)
      expect(http.writes()).toHaveLength(1)
    } finally {
      recovery.close()
    }
  })

  it('keeps a lost response uncertain and never resends on its own', async () => {
    http.on('/v1/tasks/operations', lost)
    await expect(journal.submit(operation)).rejects.toThrow('network')
    expect((await store.submission('key-1'))?.state).toBe('uncertain')

    http.on(lookup, reply({ error: 'not_found' }, 404))
    expect((await journal.check('key-1')).state).toBe('uncertain')
    http.on(lookup, reply({ operation_id: 'op-1' }))
    const checked = await journal.check('key-1')
    expect(checked.state).toBe('resolved')
    expect(checked.response).toEqual({ operation_id: 'op-1' })
    expect(http.writes()).toHaveLength(1)
  })

  it('keeps original transport diagnostics through missing lookups and clears them on an explicit retry', async () => {
    http.on(operation.endpoint, lost, reply({ operation_id: 'op-1' }, 201))
    await expect(journal.submit(operation)).rejects.toThrow('network')
    const initial = (await store.submission(operation.key))!
    expect(initial.diagnostic).toMatchObject({ kind: 'network', phase: 'request', status: null })
    http.on(lookup, reply({ error: 'not_found' }, 404), reply({ error: 'not_found' }, 404))
    const checked = await journal.check(operation.key)
    expect(checked.state).toBe('uncertain')
    expect(checked.diagnostic).toEqual(initial.diagnostic)
    expect(http.writes()).toHaveLength(1)
    const retried = await journal.retry(operation.key)
    expect(retried.diagnostic).toBeNull()
    expect(http.writes().map(({ key }) => key)).toEqual([operation.key, operation.key])
  })

  it('persists the server trace for a failed admission without attempting the write again', async () => {
    const trace = '0123456789abcdef0123456789abcdef'
    http.on(
      operation.endpoint,
      () =>
        new Response(JSON.stringify({ error: 'unavailable' }), {
          status: 502,
          headers: { 'X-Trace-ID': trace },
        }),
    )
    await expect(journal.submit(operation)).rejects.toMatchObject({ status: 502 })
    expect(await store.submission(operation.key)).toMatchObject({
      state: 'uncertain',
      diagnostic: { kind: 'http', phase: 'response', status: 502, traceId: trace },
    })
    expect(http.writes()).toHaveLength(1)
  })

  it('preserves an admission when a later lookup is missing and prevents resending it', async () => {
    const receipt = { message_id: 'message-1', status: 'queued' }
    http.on(operation.endpoint, reply(receipt, 202))
    await journal.submit({ ...operation, found: 'accepted' })
    http.on(lookup, reply({ error: 'not_found' }, 404))
    const checked = await journal.check(operation.key, () => 'resolved')
    expect(checked).toMatchObject({
      state: 'accepted',
      response: receipt,
      failure: { status: 404, code: 'not_found' },
    })
    await expect(journal.retry(operation.key)).rejects.toThrow('Cannot resend')
    expect(http.writes()).toHaveLength(1)
  })

  it('resends the same key and bytes only on an explicit retry after a missing receipt', async () => {
    http.on('/v1/tasks/operations', lost, reply({ operation_id: 'op-1' }, 201))
    await expect(journal.submit(operation)).rejects.toThrow()
    http.on(lookup, reply({ error: 'not_found' }, 404))
    const entry = await journal.retry('key-1')
    expect(entry.state).toBe('resolved')
    const [first, second] = http.writes()
    expect(second?.key).toBe(first?.key)
    expect(second?.body).toBe(first?.body)
  })

  it('does not resend when the retry lookup finds the earlier write', async () => {
    http.on('/v1/tasks/operations', lost)
    await expect(journal.submit(operation)).rejects.toThrow()
    http.on(lookup, reply({ operation_id: 'op-1' }))
    expect((await journal.retry('key-1')).state).toBe('resolved')
    expect(http.writes()).toHaveLength(1)
  })

  it('treats a keyed conflict as the earlier acceptance when its receipt exists', async () => {
    http.on('/v1/tasks/operations', reply({ error: 'conflict' }, 409))
    http.on(lookup, reply({ operation_id: 'op-earlier' }))
    expect((await journal.submit(operation)).state).toBe('resolved')
  })

  it('rejects a conflict without a receipt and keeps its details for the draft', async () => {
    http.on(
      '/v1/tasks/operations',
      reply({ error: 'revision_conflict', detail: { revision: 4 } }, 409),
    )
    http.on(lookup, reply({ error: 'not_found' }, 404))
    await expect(journal.submit(operation)).rejects.toMatchObject({ status: 409 })
    const entry = (await store.submission('key-1')) as Submission
    expect(entry.state).toBe('rejected')
    expect(entry.failure).toEqual({
      status: 409,
      code: 'revision_conflict',
      detail: { revision: 4 },
    })
    await expect(journal.retry('key-1')).rejects.toThrow('Cannot resend: the write is rejected')
  })

  it('rejects refusals that happened before any write', async () => {
    http.on('/v1/tasks/operations', reply({ error: 'invalid', detail: { title: 'required' } }, 422))
    await expect(journal.submit(operation)).rejects.toMatchObject({ status: 422 })
    expect((await store.submission('key-1'))?.state).toBe('rejected')
  })

  it.each([409, 503])(
    'keeps a typed chat readiness refusal editable on %s without a second lookup',
    async (status) => {
      const request = {
        ...operation,
        endpoint: '/v1/conversation/messages',
        found: 'accepted' as const,
        lookupPrefix: '/v1/conversation/submissions/by-key/',
      }
      http.on(request.endpoint, reply({ error: 'chat_unavailable' }, status))
      await expect(journal.submit(request)).rejects.toMatchObject({
        status,
        code: 'chat_unavailable',
      })
      expect((await store.submission(request.key))?.state).toBe('rejected')
      expect(http.requests.filter((request) => request.path.includes('/by-key/'))).toHaveLength(0)
      expect(http.writes()).toHaveLength(1)
    },
  )

  it('recovers writes interrupted by a closed tab through lookup only', async () => {
    const base = {
      method: 'POST' as const,
      endpoint: '/v1/tasks/operations',
      found: 'resolved' as const,
      body: '{}',
      createdAt: 1,
      response: null,
      failure: null,
      context: {},
    }
    await store.saveSubmission({
      ...base,
      key: 'a',
      state: 'sending',
      lookup: '/v1/tasks/operations/by-key/a',
    })
    await store.saveSubmission({ ...base, key: 'b', state: 'prepared', lookup: null })
    await store.saveSubmission({ ...base, key: 'c', state: 'resolved', lookup: null })
    http.on('/v1/tasks/operations/by-key/a', reply({ operation_id: 'op-a' }))
    const states = Object.fromEntries(
      (await journal.recover()).map((entry) => [entry.key, entry.state]),
    )
    expect(states).toEqual({ a: 'resolved', b: 'uncertain', c: 'resolved' })
    expect(http.writes()).toHaveLength(0)
  })

  it('continues recovery when another tab acknowledged an entry from the snapshot', async () => {
    const entries: Submission[] = ['gone', 'remaining'].map((key) => ({
      key,
      method: 'POST',
      endpoint: operation.endpoint,
      lookup: `/v1/tasks/operations/by-key/${key}`,
      found: 'resolved',
      body: '{}',
      createdAt: 1,
      state: 'uncertain',
      response: null,
      failure: null,
      context: {},
    }))
    for (const entry of entries) await store.saveSubmission(entry)
    const recoveryStore = {
      submission: store.submission.bind(store),
      saveSubmission: store.saveSubmission.bind(store),
      deleteSubmission: store.deleteSubmission.bind(store),
      submissions: async () => {
        const snapshot = await store.submissions()
        await store.deleteSubmission('gone')
        return snapshot
      },
    }
    const recovering = new SubmissionJournal(
      recoveryStore,
      new Api(http.fetcher),
      new LocalKeyLock(),
    )
    http.on('/v1/tasks/operations/by-key/remaining', reply({ operation_id: 'operation-2' }))
    expect(await recovering.recover()).toMatchObject([{ key: 'remaining', state: 'resolved' }])
    expect(http.writes()).toHaveLength(0)
  })

  it('dismisses only settled writes', async () => {
    http.on('/v1/tasks/operations', lost)
    await expect(journal.submit(operation)).rejects.toThrow()
    await expect(journal.acknowledge('key-1')).rejects.toThrow(
      'Cannot dismiss: the write is uncertain',
    )
    http.on(lookup, reply({ operation_id: 'op-1' }))
    await journal.check('key-1')
    await journal.acknowledge('key-1')
    expect(await store.submission('key-1')).toBeUndefined()
    expect(changes.at(-1)).toMatchObject({ removed: true, local: true })
  })

  it('acknowledges the same proven receipt twice without losing a recovery result', async () => {
    http.on('/v1/tasks/operations', reply({ error: 'temporary' }, 503))
    await expect(journal.submit(operation)).rejects.toMatchObject({ status: 503 })
    await expect(journal.acknowledge('key-1')).rejects.toThrow(
      'Cannot dismiss: the write is uncertain',
    )
    http.on(lookup, reply({ operation_id: 'op-1' }))
    const checked = await journal.check('key-1')
    expect(checked.state).toBe('resolved')
    await Promise.all([journal.acknowledge('key-1'), journal.acknowledge('key-1')])
    expect(await store.submission('key-1')).toBeUndefined()
    expect(checked.response).toEqual({ operation_id: 'op-1' })
    expect(http.writes()).toHaveLength(1)
  })

  it('lets the user abandon only an unresolved write', async () => {
    http.on('/v1/tasks/operations', lost)
    await expect(journal.submit(operation)).rejects.toThrow()
    await journal.abandon('key-1')
    expect(await store.submission('key-1')).toBeUndefined()
    http.on('/v1/tasks/operations', reply({ operation_id: 'op-2' }, 201))
    await journal.submit({ ...operation, key: 'key-2' })
    await expect(journal.abandon('key-2')).rejects.toThrow('Cannot abandon: the write is resolved')
  })

  it('serializes work on one key so a check waits for the send', async () => {
    let release!: () => void
    const gate = new Promise<void>((resolve) => (release = resolve))
    http.on('/v1/tasks/operations', reply({ operation_id: 'op-1' }, 201))
    const gated = (async (input: RequestInfo | URL, init?: RequestInit) => {
      if (String(input) === '/v1/tasks/operations') await gate
      return http.fetcher(input, init)
    }) as typeof fetch
    journal = new SubmissionJournal(store, new Api(gated), new LocalKeyLock())
    const sending = journal.submit(operation)
    const checking = journal.check('key-1')
    await new Promise((resolve) => setTimeout(resolve, 10))
    release()
    expect((await sending).state).toBe('resolved')
    expect((await checking).state).toBe('resolved')
    // The check saw the settled write, so it had nothing to look up.
    expect(http.requests.some((request) => request.path === lookup)).toBe(false)
  })
})
