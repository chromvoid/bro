import { QueryClient, VueQueryPlugin } from '@tanstack/vue-query'
import { ChangeClaims } from '@/core/changes'
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { defineComponent, h, ref, shallowReactive } from 'vue'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { AccountServices } from '@/core/account'
import { Api, ApiError } from '@/core/http'
import { LocalKeyLock, SubmissionJournal, type Submission } from '@/core/journal'
import { SubmissionReconciler } from '@/core/reconciliation'
import { ENDPOINT, LOOKUP } from './delivery'
import { useConversation } from './useConversation'

const mocks = vi.hoisted(() => ({
  useSession: vi.fn(),
  get: vi.fn<(path: string, signal?: AbortSignal) => Promise<unknown>>(),
  send: vi.fn(),
  upload: vi.fn(),
}))
vi.mock('@/core/client', () => ({
  api: { get: mocks.get, send: mocks.send, upload: mocks.upload },
}))
vi.mock('@/stores/session', () => ({ useSession: mocks.useSession }))

function deferred<T>() {
  let resolve!: (value: T) => void
  const promise = new Promise<T>((done) => {
    resolve = done
  })
  return { promise, resolve }
}

function submission(key = 'old-key', messageId = 'old-message'): Submission {
  return {
    key,
    method: 'POST',
    endpoint: ENDPOINT,
    lookup: LOOKUP + encodeURIComponent(key),
    found: 'accepted',
    body: JSON.stringify({ text: 'Синтетический вчерашний завтрак' }),
    createdAt: Date.parse('2026-09-30T08:00:00Z'),
    state: 'accepted',
    response: { message_id: messageId, state: 'completed' },
    failure: null,
    context: {},
  }
}

function message(id: string) {
  return { id, created_at: '2026-09-30T08:00:00Z', text: 'Synthetic meal', state: 'completed' }
}

function account(userId: string, initial: Submission[]) {
  const entries = new Map(initial.map((entry) => [entry.key, entry]))
  let draft: { submissionKey?: string | null; attempts?: Array<{ key: string }> } | undefined
  const store = {
    userId,
    value: vi.fn(async () => draft),
    submission: async (key: string) => entries.get(key),
    submissions: async () => [...entries.values()],
    saveSubmission: async (entry: Submission) => {
      entries.set(entry.key, entry)
    },
    deleteSubmission: async (key: string) => {
      entries.delete(key)
    },
  }
  const network = vi.fn<typeof fetch>(async () => {
    throw new Error('Automatic recovery must not resend the message')
  })
  const journal = new SubmissionJournal(store, new Api(network), new LocalKeyLock())
  const reconciliation = new SubmissionReconciler(journal)
  accounts.push(reconciliation)
  const acknowledge = vi.spyOn(journal, 'acknowledge')
  const retry = vi.spyOn(journal, 'retry')
  const services = {
    store,
    journal,
    reconciliation,
    stream: { subscribe: () => () => undefined },
    changes: new ChangeClaims(),
    close: () => reconciliation.close(),
  } as unknown as AccountServices
  return {
    services,
    journal,
    entries,
    acknowledge,
    retry,
    network,
    readDraft: store.value,
    setDraft(value: typeof draft) {
      draft = value
    },
    changed(entry: Submission) {
      journal.relay({ entry, removed: false })
    },
  }
}

const exact = (id: string) => `${ENDPOINT}?message_id=${encodeURIComponent(id)}`
const latestPage = { messages: [message('latest-message')], before: 'older-page' }
const accounts: SubmissionReconciler[] = []

describe('conversation admission reconciliation', () => {
  const mounted: Array<{ wrapper: VueWrapper; client: QueryClient }> = []

  beforeEach(() => {
    mocks.get.mockReset()
    mocks.send.mockReset()
    mocks.upload.mockReset()
  })
  afterEach(() => {
    accounts.splice(0).forEach((account) => account.close())
    for (const { wrapper, client } of mounted.splice(0)) {
      wrapper.unmount()
      client.clear()
    }
    vi.restoreAllMocks()
  })

  async function open(owner: ReturnType<typeof account>) {
    const session = shallowReactive({ account: owner.services as AccountServices | null })
    mocks.useSession.mockReturnValue(session)
    const client = new QueryClient({
      defaultOptions: { queries: { retry: false, gcTime: 0, staleTime: Infinity } },
    })
    let conversation!: ReturnType<typeof useConversation>
    const wrapper = mount(
      defineComponent({
        setup() {
          conversation = useConversation(ref(null))
          return () => h('div')
        },
      }),
      { global: { plugins: [[VueQueryPlugin, { queryClient: client }]] } },
    )
    mounted.push({ wrapper, client })
    await vi.waitFor(() => expect(conversation.history.isSuccess.value).toBe(true))
    return { conversation, session, wrapper }
  }

  it('confirms a completed message outside the page, then waits for durable composer handoff', async () => {
    const entry = submission()
    const owner = account('owner', [entry])
    owner.setDraft({ submissionKey: entry.key })
    const reply = deferred<unknown>()
    mocks.get.mockImplementation(async (path) => {
      if (path === ENDPOINT) return latestPage
      if (path === exact('old-message')) return reply.promise
      throw new Error(`Unexpected GET ${path}`)
    })
    const { conversation } = await open(owner)
    await vi.waitFor(
      () => expect(mocks.get).toHaveBeenCalledWith(exact('old-message'), expect.any(AbortSignal)),
      { timeout: 2500 },
    )
    expect(conversation.pending.value).toMatchObject([{ key: entry.key, status: 'delivered' }])

    // Older than the loaded page: confirmed by exact identity, but left for "earlier" to show.
    const old = { ...message('old-message'), created_at: '2026-09-29T08:00:00Z' }
    reply.resolve({ messages: [old], before: null })
    await vi.waitFor(() => expect(conversation.pending.value).toEqual([]))
    await flushPromises()
    expect(conversation.exchanges.value.map((item) => item.id)).toEqual(['latest-message'])
    expect(owner.acknowledge).not.toHaveBeenCalled()
    expect(owner.entries.has(entry.key)).toBe(true)

    owner.setDraft({ submissionKey: null, attempts: [{ key: entry.key }] })
    await conversation.acknowledgeVisible()
    expect(owner.acknowledge).not.toHaveBeenCalled()
    owner.setDraft(undefined)
    await conversation.acknowledgeVisible()
    expect(owner.acknowledge).toHaveBeenCalledWith(entry.key)
    expect(owner.entries.has(entry.key)).toBe(false)
    expect(owner.network).not.toHaveBeenCalled()
    expect(owner.retry).not.toHaveBeenCalled()
    expect(mocks.send).not.toHaveBeenCalled()
  })

  it.each(['wrong identity', 'empty page', 'not found'] as const)(
    'retains the delivered card when exact lookup returns %s',
    async (result) => {
      const entry = submission()
      const owner = account('owner', [entry])
      mocks.get.mockImplementation(async (path) => {
        if (path === ENDPOINT) return latestPage
        if (path !== exact('old-message')) throw new Error(`Unexpected GET ${path}`)
        if (result === 'not found') throw new ApiError(404, 'not_found', null, null, null)
        return {
          messages: result === 'wrong identity' ? [message('other-message')] : [],
          before: null,
        }
      })
      const { conversation } = await open(owner)
      await vi.waitFor(
        () => expect(mocks.get).toHaveBeenCalledWith(exact('old-message'), expect.any(AbortSignal)),
        { timeout: 2500 },
      )
      await flushPromises()

      expect(conversation.pending.value).toMatchObject([
        { key: entry.key, status: 'delivered', messageId: 'old-message' },
      ])
      expect(owner.acknowledge).not.toHaveBeenCalled()
      expect(owner.entries.has(entry.key)).toBe(true)
      expect(owner.retry).not.toHaveBeenCalled()
      expect(owner.network).not.toHaveBeenCalled()
      expect(mocks.send).not.toHaveBeenCalled()
    },
  )

  it('retries a transient exact-history failure without resending the admitted message', async () => {
    const entry = submission()
    const owner = account('owner', [entry])
    let attempts = 0
    mocks.get.mockImplementation(async (path) => {
      if (path === ENDPOINT) return latestPage
      if (++attempts === 1) throw new TypeError('offline')
      return { messages: [message('old-message')], before: null }
    })
    const { conversation } = await open(owner)
    await vi.waitFor(() => expect(owner.acknowledge).toHaveBeenCalledWith(entry.key), {
      timeout: 4500,
    })
    expect(attempts).toBe(2)
    expect(conversation.pending.value).toEqual([])
    expect(owner.network).not.toHaveBeenCalled()
    expect(owner.retry).not.toHaveBeenCalled()
  })

  it('ignores a delayed exact lookup after switching accounts', async () => {
    const firstEntry = submission('first-key', 'shared-id')
    const first = account('first-owner', [firstEntry])
    // Reusing an identity in this synthetic fixture detects any leaked confirmation across accounts.
    const secondEntry = submission('second-key', 'shared-id')
    const second = account('second-owner', [secondEntry])
    const late = deferred<unknown>()
    let exactReads = 0
    mocks.get.mockImplementation(async (path) => {
      if (path === ENDPOINT) return latestPage
      if (path !== exact('shared-id')) throw new Error(`Unexpected GET ${path}`)
      return ++exactReads === 1 ? late.promise : { messages: [], before: null }
    })
    const { conversation, session } = await open(first)
    await vi.waitFor(() => expect(exactReads).toBe(1), { timeout: 2500 })
    session.account = second.services
    await vi.waitFor(() => expect(exactReads).toBe(2), { timeout: 2500 })
    await flushPromises()
    expect(conversation.pending.value).toMatchObject([
      { key: secondEntry.key, status: 'delivered' },
    ])

    late.resolve({ messages: [message('shared-id')], before: null })
    await flushPromises()
    expect(conversation.pending.value).toMatchObject([
      { key: secondEntry.key, status: 'delivered' },
    ])
    expect(first.acknowledge).not.toHaveBeenCalled()
    expect(second.acknowledge).not.toHaveBeenCalled()
    expect(first.entries.has(firstEntry.key)).toBe(true)
    expect(second.entries.has(secondEntry.key)).toBe(true)
    expect(mocks.send).not.toHaveBeenCalled()
  })

  it('dismisses a delivered recovery card only after its durable composer ownership clears', async () => {
    const entry = submission()
    const owner = account('owner', [entry])
    owner.setDraft({ submissionKey: entry.key })
    mocks.get.mockImplementation(async (path) =>
      path === ENDPOINT ? latestPage : { messages: [], before: null },
    )
    const { conversation } = await open(owner)
    await vi.waitFor(() => expect(conversation.pending.value).toHaveLength(1))

    expect(await conversation.dismiss(entry.key)).toBe(false)
    owner.setDraft({ attempts: [{ key: entry.key }] })
    expect(await conversation.dismiss(entry.key)).toBe(false)
    expect(owner.acknowledge).not.toHaveBeenCalled()
    expect(owner.entries.has(entry.key)).toBe(true)

    owner.setDraft(undefined)
    expect(await conversation.dismiss(entry.key)).toBe(true)
    await vi.waitFor(() => expect(conversation.pending.value).toEqual([]))
    expect(owner.acknowledge).toHaveBeenCalledWith(entry.key)
    expect(owner.entries.has(entry.key)).toBe(false)
    expect(owner.network).not.toHaveBeenCalled()
    expect(owner.retry).not.toHaveBeenCalled()
    expect(mocks.send).not.toHaveBeenCalled()
  })

  it('cannot acknowledge a manual dismissal after the account changes during the ownership read', async () => {
    const firstEntry = submission('first-key')
    const first = account('first-owner', [firstEntry])
    const secondEntry = submission('second-key')
    const second = account('second-owner', [secondEntry])
    mocks.get.mockImplementation(async (path) =>
      path === ENDPOINT ? latestPage : { messages: [], before: null },
    )
    const { conversation, session } = await open(first)
    await flushPromises()
    const entered = deferred<undefined>()
    const ownership = deferred<undefined>()
    first.readDraft.mockImplementationOnce(async () => {
      entered.resolve(undefined)
      return ownership.promise
    })
    const dismissing = conversation.dismiss(firstEntry.key)
    const rejected = expect(dismissing).rejects.toThrow('Conversation account changed')
    await entered.promise
    session.account = second.services
    await vi.waitFor(() =>
      expect(conversation.pending.value).toMatchObject([{ key: secondEntry.key }]),
    )
    ownership.resolve(undefined)
    await rejected

    expect(first.acknowledge).not.toHaveBeenCalled()
    expect(second.acknowledge).not.toHaveBeenCalled()
    expect(first.entries.has(firstEntry.key)).toBe(true)
    expect(second.entries.has(secondEntry.key)).toBe(true)
    expect(first.network).not.toHaveBeenCalled()
    expect(second.network).not.toHaveBeenCalled()
    expect(mocks.send).not.toHaveBeenCalled()
  })

  it('ignores a delayed exact lookup after the conversation is unmounted', async () => {
    const entry = submission()
    const owner = account('owner', [entry])
    const late = deferred<unknown>()
    mocks.get.mockImplementation(async (path) => (path === ENDPOINT ? latestPage : late.promise))
    const { wrapper } = await open(owner)
    await vi.waitFor(
      () => expect(mocks.get).toHaveBeenCalledWith(exact('old-message'), expect.any(AbortSignal)),
      { timeout: 2500 },
    )
    wrapper.unmount()
    late.resolve({ messages: [message('old-message')], before: null })
    await flushPromises()
    expect(owner.acknowledge).not.toHaveBeenCalled()
    expect(owner.entries.has(entry.key)).toBe(true)
  })

  it('keeps the latest same-account journal read when an older read resolves last', async () => {
    const oldEntry = { ...submission('old-key'), state: 'uncertain' as const, response: null }
    const newEntry = { ...submission('new-key'), state: 'uncertain' as const, response: null }
    const owner = account('owner', [newEntry])
    const late = deferred<Submission[]>()
    const reads = vi
      .spyOn(owner.journal, 'pending')
      .mockImplementationOnce(() => late.promise)
      .mockResolvedValue([newEntry])
    mocks.get.mockResolvedValue(latestPage)
    const { conversation } = await open(owner)
    expect(reads).toHaveBeenCalled()
    owner.changed(newEntry)
    await vi.waitFor(() =>
      expect(conversation.pending.value.map((item) => item.key)).toEqual(['new-key']),
    )

    late.resolve([oldEntry])
    await flushPromises()
    expect(conversation.pending.value.map((item) => item.key)).toEqual(['new-key'])
    expect(owner.acknowledge).not.toHaveBeenCalled()
    expect(mocks.send).not.toHaveBeenCalled()
  })

  it('rebuilds only the exchanges whose messages changed in a refetch', async () => {
    let answer = 'Синтетический ответ'
    mocks.get.mockImplementation(async (path) => {
      if (path !== ENDPOINT) throw new Error(`Unexpected GET ${path}`)
      return {
        messages: [
          message('older-message'),
          {
            ...message('latest-message'),
            turns: [{ id: 'turn', state: 'completed', result: { reply: answer } }],
          },
        ],
        before: null,
      }
    })
    const { conversation } = await open(account('owner', []))
    const byId = () => new Map(conversation.exchanges.value.map((item) => [item.id, item]))
    const before = byId()

    answer = 'Синтетический ответ, дополненный'
    await conversation.history.refetch()
    const after = byId()
    expect(after.get('older-message')).toBe(before.get('older-message'))
    expect(after.get('latest-message')).not.toBe(before.get('latest-message'))
    expect(after.get('latest-message')!.replies[0]!.text).toBe(answer)
  })
})
