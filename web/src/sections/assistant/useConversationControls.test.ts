import 'fake-indexeddb/auto'
import { ChangeClaims } from '@/core/changes'
import { QueryClient, VueQueryPlugin } from '@tanstack/vue-query'
import { mount, type VueWrapper } from '@vue/test-utils'
import { defineComponent, h, shallowReactive } from 'vue'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { AccountServices } from '@/core/account'
import { AccountStore, purgeAccount } from '@/core/storage'
import { Api } from '@/core/http'
import { LocalKeyLock, SubmissionJournal } from '@/core/journal'
import { SubmissionReconciler } from '@/core/reconciliation'
import type { MessageBody } from './delivery'
import { CONTROL_ENDPOINT, CONTROL_LOOKUP, controlReceipt } from './controls'
import { toExchange } from './model'
import { useConversationControls } from './useConversationControls'

const mocks = vi.hoisted(() => ({ useSession: vi.fn() }))
vi.mock('@/stores/session', () => ({ useSession: mocks.useSession }))

const body = {
  text: 'Synthetic speech',
  image_id: 'photo',
  transcription_id: 'speech',
  reply_to_message_id: 'reply',
}
const exchange = toExchange({
  id: 'message',
  created_at: '2026-10-05T12:00:00Z',
  state: 'queued',
  run_generation: 'generation',
  text: body.text,
  transcription_id: body.transcription_id,
  reply_to_message_id: body.reply_to_message_id,
  images: [{ id: 'photo', url: '/photo', width: 1, height: 1 }],
})

function json(value: unknown, status = 200) {
  return new Response(JSON.stringify(value), {
    status,
    headers: { 'Content-Type': 'application/json' },
  })
}

describe('durable conversation control recovery', () => {
  let store: AccountStore
  let user: string
  let post: 'accepted' | 'lost'
  let lookup: 'missing' | 'accepted' | 'settled' | 'rejected' | 'unsafe'
  let writes: number
  let storedKey: string
  let action: 'stop' | 'withdraw'
  let network: typeof fetch
  let session: { account: AccountServices | null }
  let journal: SubmissionJournal
  const reconcilers: SubmissionReconciler[] = []
  const mounted: Array<{ wrapper: VueWrapper; client: QueryClient }> = []

  beforeEach(async () => {
    user = crypto.randomUUID()
    store = await AccountStore.open(user)
    post = 'accepted'
    lookup = 'settled'
    writes = 0
    storedKey = ''
    action = 'withdraw'
    network = vi.fn<typeof fetch>(async (input, init = {}) => {
      const path = String(input)
      if (path === '/v1/auth/csrf') return json({ token: 'synthetic-csrf' })
      if (path === CONTROL_ENDPOINT && init.method === 'POST') {
        writes++
        storedKey = new Headers(init.headers).get('Idempotency-Key')!
        expect(new Headers(init.headers).get('X-Expected-User')).toBe(user)
        const saved = await store.submission(storedKey)
        expect(saved?.body).toBe(init.body)
        expect(JSON.parse(saved!.context.envelope!)).toEqual(body)
        const request = JSON.parse(String(init.body)) as { action: 'stop' | 'withdraw' }
        action = request.action
        if (post === 'lost') throw new TypeError('Synthetic lost response')
        return json(receipt('accepted'), 202)
      }
      if (path === CONTROL_LOOKUP + storedKey && (init.method ?? 'GET') === 'GET') {
        if (lookup === 'missing') return json({ error: 'missing' }, 404)
        return json(receipt(lookup))
      }
      throw new Error(`Unexpected synthetic request: ${path}`)
    })
    session = shallowReactive({ account: services() })
    mocks.useSession.mockReturnValue(session)
  })
  afterEach(async () => {
    for (const { wrapper, client } of mounted.splice(0)) {
      wrapper.unmount()
      client.clear()
    }
    reconcilers.splice(0).forEach((reconciler) => reconciler.close())
    store.close()
    await purgeAccount(user)
    vi.restoreAllMocks()
  })

  function receipt(state: typeof lookup) {
    const settled = state === 'settled' || state === 'unsafe'
    return {
      id: 'control',
      submission_key: storedKey,
      message_id: exchange.id,
      generation: exchange.generation,
      intent_revision: 0,
      action,
      state: state === 'unsafe' ? 'settled' : state,
      reason: state === 'rejected' ? 'request_started' : null,
      admitted_at: exchange.createdAt,
      settled_at: settled || state === 'rejected' ? exchange.createdAt : null,
      execution: settled
        ? {
            effects: state === 'unsafe' ? 'unknown' : 'none',
            completion: 'failed',
            safe_to_resubmit: state !== 'unsafe',
          }
        : null,
      ...(settled && action === 'withdraw' ? { message_body: body } : {}),
    }
  }

  function services(): AccountServices {
    journal = new SubmissionJournal(store, new Api(network), new LocalKeyLock())
    const reconciliation = new SubmissionReconciler(journal)
    reconcilers.push(reconciliation)
    return {
      store,
      journal,
      reconciliation,
      stream: { subscribe: () => () => undefined },
      changes: new ChangeClaims(),
      close: () => reconciliation.close(),
    } as unknown as AccountServices
  }

  function open(
    restore = vi
      .fn<(body: MessageBody, preview: string, key: string) => Promise<boolean>>()
      .mockResolvedValue(true),
  ) {
    const client = new QueryClient({ defaultOptions: { queries: { retry: false, gcTime: 0 } } })
    let controls!: ReturnType<typeof useConversationControls>
    const wrapper = mount(
      defineComponent({
        setup() {
          controls = useConversationControls(restore, () => undefined)
          return () => h('div')
        },
      }),
      { global: { plugins: [[VueQueryPlugin, { queryClient: client }]] } },
    )
    mounted.push({ wrapper, client })
    return { controls, restore, wrapper }
  }

  it('retains a lost withdrawal receipt and original envelope across remount, then restores by GET only', async () => {
    post = 'lost'
    lookup = 'missing'
    const first = open()
    await expect(
      first.controls.submit(exchange, 'withdraw', true, 'Synthetic reply'),
    ).resolves.toBeUndefined()
    expect(first.restore).not.toHaveBeenCalled()
    expect(writes).toBe(1)
    expect((await journal.pending())[0]).toMatchObject({
      state: 'uncertain',
      context: { purpose: 'edit' },
    })
    expect(JSON.parse((await journal.pending())[0]!.context.envelope!)).toEqual(body)
    first.wrapper.unmount()
    session.account!.close()
    session.account = services()
    lookup = 'settled'
    const next = open()
    // The remounted account's bounded reconciler reads the durable receipt.
    await vi.waitFor(
      () => expect(next.restore).toHaveBeenCalledWith(body, 'Synthetic reply', exchange.id),
      { timeout: 3000 },
    )
    await vi.waitFor(async () => expect(await journal.pending()).toEqual([]))
    expect(writes).toBe(1)
  })

  it('waits for settled stop proof instead of announcing completion on admission', async () => {
    lookup = 'accepted'
    const { controls, restore } = open()
    await controls.submit({ ...exchange, queued: false }, 'stop')
    expect(controlReceipt((await journal.pending())[0]!)?.state).toBe('accepted')
    expect(restore).not.toHaveBeenCalled()
    lookup = 'settled'
    await controls.check(storedKey)
    expect(await journal.pending()).toEqual([])
    expect(writes).toBe(1)
    expect(restore).not.toHaveBeenCalled()
  })

  it('keeps the confirmed edit until an occupied composer can persist the handoff', async () => {
    const restore = vi
      .fn<(body: MessageBody, preview: string, key: string) => Promise<boolean>>()
      .mockResolvedValue(false)
    const { controls } = open(restore)
    await controls.submit(exchange, 'withdraw', true, 'Synthetic reply')
    expect(controlReceipt((await journal.pending())[0]!)?.state).toBe('settled')
    expect(await journal.pending()).toHaveLength(1)
    restore.mockResolvedValue(true)
    await controls.check(storedKey)
    expect(await journal.pending()).toEqual([])
    expect(writes).toBe(1)
  })

  it('does not restore an edit when claim won or a contradictory receipt has unknown effects', async () => {
    lookup = 'rejected'
    const { controls, restore } = open()
    await controls.submit(exchange, 'withdraw', true)
    expect(restore).not.toHaveBeenCalled()
    expect(controlReceipt((await journal.pending())[0]!)?.reason).toBe('request_started')
    await controls.dismiss(storedKey)
    lookup = 'unsafe'
    await controls.submit(exchange, 'withdraw', true)
    expect(restore).not.toHaveBeenCalled()
    expect(await journal.pending()).toHaveLength(1)
    expect(writes).toBe(2)
  })
})
