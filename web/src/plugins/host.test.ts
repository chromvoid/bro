import 'fake-indexeddb/auto'
import { afterEach, beforeEach, describe, expect, it } from 'vitest'
import { ref } from 'vue'
import { Api } from '@/core/http'
import { LocalKeyLock, SubmissionJournal } from '@/core/journal'
import { AccountStore, purgeAccount } from '@/core/storage'
import type { PluginEvent, PluginRoute } from './api'
import { Catalog } from './catalog'
import { PlatformPluginHost } from './host'

const BASE = '/v1/plugins/installations/trading-main'
type Reply = Response | Error

function server() {
  const routes = new Map<string, Reply[]>()
  const requests: Array<{ path: string; method: string; body: string | null; headers: Headers }> =
    []
  const fetcher = (async (input: RequestInfo | URL, init: RequestInit = {}) => {
    const path = String(input)
    requests.push({
      path,
      method: init.method ?? 'GET',
      body: typeof init.body === 'string' ? init.body : null,
      headers: new Headers(init.headers),
    })
    if (path === '/v1/auth/csrf') return json({ token: 'csrf' })
    const reply = routes.get(path)?.shift()
    if (!reply) throw new Error(`unexpected ${path}`)
    if (reply instanceof Error) throw reply
    return reply
  }) as typeof fetch
  const on = (path: string, ...replies: Reply[]) =>
    routes.set(path, [...(routes.get(path) ?? []), ...replies])
  return { fetcher, on, requests }
}

const json = (value: unknown, status = 200) =>
  new Response(JSON.stringify(value), { status, headers: { 'Content-Type': 'application/json' } })
const result = (state: string, data: unknown = null) => ({
  contract: 'assistant.result/v1',
  operation_key: 'op',
  state,
  data,
  receipts: [],
  effects: [],
})

const [installation] = Catalog.parse({
  installations: [
    {
      installation_id: 'trading-main',
      package: { id: 'trading' },
      title: 'Торговля',
      status: 'ready',
      tools: [
        { id: 'portfolio.get', version: '1.0.0', effect: 'read' },
        { id: 'sync.refill', version: '1.0.0', effect: 'write' },
      ],
      ui: [],
      configuration: { writable: true },
      events: [{ type: 'trading.data.changed', version: '1.0.0' }],
    },
  ],
}).installations

describe('PlatformPluginHost', () => {
  let store: AccountStore
  let http: ReturnType<typeof server>
  let host: PlatformPluginHost
  let navigated: PluginRoute[]

  beforeEach(async () => {
    store = await AccountStore.open('plugin-user')
    http = server()
    const api = new Api(http.fetcher)
    navigated = []
    host = new PlatformPluginHost({
      api,
      journal: new SubmissionJournal(store, api, new LocalKeyLock()),
      installation: installation!,
      route: ref({ name: 'home', ids: {} }),
      navigate: (route) => navigated.push(route),
      close: () => undefined,
      fetcher: http.fetcher,
      firstBackoffMs: 1,
      wait: async () => undefined,
    })
  })

  afterEach(async () => {
    host.shutdown()
    store.close()
    await purgeAccount('plugin-user')
  })

  it('reads directly at the catalog version and refuses unknown tools locally', async () => {
    http.on(`${BASE}/invoke`, json(result('completed', { total_usd: '1520.5' })))
    expect(await host.invoke('portfolio.get')).toMatchObject({
      kind: 'completed',
      data: { total_usd: '1520.5' },
      clientKey: null,
    })
    const sent = http.requests.find((request) => request.path.endsWith('/invoke'))!
    expect(JSON.parse(sent.body!)).toEqual({
      tool_id: 'portfolio.get',
      tool_version: '1.0.0',
      arguments: {},
    })
    expect(sent.headers.get('Idempotency-Key')).toBeNull()
    expect(await host.invoke('orders.cancel')).toMatchObject({
      kind: 'rejected',
      error: { code: 'tool_unavailable' },
      status: null,
    })
  })

  it('journals a write, resolves a lost response by lookup and resends only when asked', async () => {
    http.on(`${BASE}/invoke`, new TypeError('Failed to fetch'))
    const lost = await host.invoke('sync.refill')
    expect(lost).toMatchObject({ kind: 'unknown', notFound: false })
    const key = lost.clientKey!
    expect(host.pendingWrites.value).toMatchObject([
      { clientKey: key, tool: 'sync.refill', state: 'uncertain', admitted: false },
    ])

    http.on(`${BASE}/operations/by-key/${key}`, json({ status: 'not_found' }, 404))
    expect(await host.lookup(key)).toMatchObject({ kind: 'unknown', notFound: true })
    expect(host.pendingWrites.value[0]!.notFound).toBe(true)

    // The explicit retry looks up once more, then sends the same key and bytes.
    http.on(`${BASE}/operations/by-key/${key}`, json({ status: 'not_found' }, 404))
    http.on(`${BASE}/invoke`, json(result('running'), 202))
    expect(await host.retry(key)).toMatchObject({ kind: 'pending', clientKey: key })
    const writes = http.requests.filter(
      (request) => request.method === 'POST' && request.path.endsWith('/invoke'),
    )
    expect(writes.map((request) => request.headers.get('Idempotency-Key'))).toEqual([key, key])
    expect(writes[0]!.body).toBe(writes[1]!.body)

    // Running on the server: a lookup later finds it finished.
    http.on(
      `${BASE}/operations/by-key/${key}`,
      json({
        contract: 'assistant.lookup/v1',
        status: 'found',
        result: result('completed', { domains: [] }),
      }),
    )
    expect(await host.lookup(key)).toMatchObject({ kind: 'completed', clientKey: key })
    await host.acknowledge(key)
    expect(host.pendingWrites.value).toEqual([])
  })

  it('forgets a write refused before admission', async () => {
    http.on(
      `${BASE}/invoke`,
      json({ error: 'rejected', detail: { code: 'forbidden', message: 'Нет прав' } }, 403),
    )
    expect(await host.invoke('sync.refill')).toMatchObject({
      kind: 'rejected',
      status: 403,
      error: { code: 'forbidden' },
    })
    expect(host.pendingWrites.value).toEqual([])
  })

  it('sends configuration directly with If-Match and never journals secrets', async () => {
    http.on(
      `${BASE}/configuration`,
      json(result('completed', { status: 'configured', revision: 4 })),
    )
    const outcome = await host.configuration.put(
      { environment: 'testnet' },
      { api_key: 'AAAAAAAAAA11', api_secret: 'BBBBBBBBBB22' },
      3,
    )
    expect(outcome).toMatchObject({ kind: 'completed', data: { revision: 4 } })
    const put = http.requests.find((request) => request.method === 'PUT')!
    expect(put.headers.get('If-Match')).toBe('"3"')
    expect(put.headers.get('Idempotency-Key')).toMatch(/^[0-9a-f-]{36}$/)
    expect(JSON.parse(put.body!)).toMatchObject({ secret: { api_key: 'AAAAAAAAAA11' } })
    expect(JSON.stringify(await store.submissions())).not.toContain('BBBBBBBBBB22')

    host.update({ ...installation!, configuration: { writable: false } })
    expect(await host.configuration.delete(4)).toMatchObject({
      kind: 'rejected',
      error: { code: 'forbidden' },
    })
  })

  it('follows events once each, remembers the cursor and stops when access ends', async () => {
    const frame = (name: string, data: unknown) =>
      `event: ${name}\ndata: ${JSON.stringify(data)}\n\n`
    const event = {
      event_id: 'e1',
      installation_id: 'trading-main',
      type: 'trading.data.changed',
      occurred_at: '2026-09-25T10:00:00Z',
      cursor: 'c2',
      payload: { domains: ['wallet'] },
    }
    const stream = (body: string) =>
      new Response(body, { headers: { 'Content-Type': 'text/event-stream' } })
    http.on(
      `${BASE}/events`,
      stream(
        frame('stream.ready', { cursor: 'c1' }) +
          frame('trading.data.changed', event) +
          frame('trading.data.changed', event) +
          frame('trading.data.changed', { ...event, installation_id: 'other' }),
      ),
      stream(frame('trading.data.changed', event)),
      json({}, 403),
    )
    const events: PluginEvent[] = []
    await new Promise<void>((resolve) => {
      host.observe((received) => {
        events.push(received)
        if (received.kind === 'denied') resolve()
      })
    })
    expect(events.map((item) => item.kind)).toEqual(['ready', 'delivered', 'denied'])
    const reconnects = http.requests.filter((request) => request.path.endsWith('/events'))
    expect(reconnects.map((request) => request.headers.get('Last-Event-ID'))).toEqual([
      null,
      'c2',
      'c2',
    ])
  })

  it('navigates only to well-formed routes', () => {
    host.navigate({ name: 'pnl', ids: { symbol: 'BTCUSDT' } })
    host.navigate({ name: 'Bad Route', ids: {} })
    host.navigate({ name: 'pnl', ids: { symbol: 'secret token with spaces' } })
    expect(navigated).toEqual([{ name: 'pnl', ids: { symbol: 'BTCUSDT' } }])
  })
})
