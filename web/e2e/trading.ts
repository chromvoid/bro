import { readFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import type { Page, Route } from '@playwright/test'

/**
 * A scripted trading installation in the browser, answered from plugins/trading/package/examples:
 * the real backend keeps the session, while /v1/plugins/* is served here, so no test needs a
 * Bybit key and nothing reaches the trading service.
 */
const PACKAGE = fileURLToPath(new URL('../../plugins/trading/package/', import.meta.url))
const read = <T>(path: string) => JSON.parse(readFileSync(`${PACKAGE}${path}`, 'utf8')) as T
const example = (tool: string, name: string) =>
  read<{ data?: unknown }>(`examples/${tool}/${name}.json`)

const MANIFEST = read<{
  tools: Array<{ descriptor: string }>
  ui: Array<{ id: string; requires_tools: Record<string, number> }>
}>('manifest.json')
/** The package's tools at the versions its contracts declare, as the catalog lists them. */
const TOOLS = MANIFEST.tools.map(({ descriptor }) => {
  const { id, version, effect } = read<{ id: string; version: string; effect: string }>(descriptor)
  return { id, version, effect }
})
const HOME = MANIFEST.ui.find((contribution) => contribution.id === 'trading.home')!

export const INSTALLATION = 'trading-e2e'
const BASE = `/v1/plugins/installations/${INSTALLATION}`
const DATA: Record<string, unknown> = {
  'portfolio.get': example('portfolio.get', 'overview').data,
  'portfolio.history': example('portfolio.history', 'week').data,
  'position.summary': example('position.summary', 'ltc').data,
  'position.chart': example('position.chart', 'ltc').data,
  'positions.list': example('positions.list', 'all').data,
  'orders.open': example('orders.open', 'one').data,
  'orders.history': example('orders.history', 'filled').data,
  'executions.list': example('executions.list', 'recent').data,
  'cashflow.list': example('cashflow.list', 'year').data,
  'flows.list': example('flows.list', 'year').data,
  'symbols.list': example('symbols.list', 'all').data,
  'sync.status': example('sync.status', 'live').data,
  'sync.refill': example('sync.refill', 'executions').data,
  'flows.mark': example('flows.mark', 'own-wallet').data,
  'positions.strategy': example('positions.strategy', 'btc').data,
  'positions.method': example('positions.method', 'fifo').data,
  'positions.core': example('positions.core', 'gram').data,
}

type RefillMode = 'complete' | 'lose-response'

export interface TradingOptions {
  clientApi?: number
  connected?: boolean
}

export async function fakeTrading(page: Page, options: TradingOptions = {}) {
  let connected = options.connected ?? true
  let revision = connected ? 3 : 0
  let refillMode: RefillMode = 'complete'
  let strategy: string | null = null
  let method = 'lifo'
  let core: Record<string, unknown> | null = null
  const refills = new Map<string, unknown>()
  const invocations: Array<{ tool: string; key: string | null; body: string }> = []
  const configurations: Array<{ method: string; ifMatch: string | null; body: string | null }> = []
  const json = (route: Route, value: unknown, status = 200) =>
    route.fulfill({ status, contentType: 'application/json', body: JSON.stringify(value) })
  const result = (state: string, data: unknown = null) => ({
    contract: 'assistant.result/v1',
    operation_key: 'op',
    state,
    data,
    receipts: [],
    effects: [],
  })
  const view = () => ({
    contract: 'assistant.configuration/v1',
    status: connected ? 'configured' : 'not_configured',
    revision,
    public: { environment: 'testnet', history_days: 730 },
    secret_state: {
      api_key: connected ? { present: true, hint: '…AB12' } : { present: false },
      api_secret: { present: connected },
    },
    readiness: { state: connected ? 'ready' : 'action_required', issues: [] },
    connection_state: connected ? 'active' : null,
    key_info: connected ? { read_only: true, ip_bound: true, expires_at: null } : null,
    environments: ['mainnet', 'testnet'],
    egress_ips: ['203.0.113.10'],
  })

  await page.route('**/v1/plugins', (route) =>
    json(route, {
      contract: 'assistant.catalog/v1',
      installations: [
        {
          installation_id: INSTALLATION,
          package: { id: 'trading', version: '1.0.0', digest: 'sha256:e2e' },
          title: 'Торговля',
          description: 'Портфель, ордера, сделки и PnL на Bybit, только чтение.',
          status: 'ready',
          note: null,
          granted_scopes: ['trading.read', 'trading.write'],
          tools: TOOLS,
          ui: [
            {
              id: 'trading.home',
              kind: 'native',
              title: 'Торговля',
              client_api: options.clientApi ?? 1,
              requires_tools: HOME.requires_tools,
            },
          ],
          configuration: { writable: true },
          events: [{ type: 'trading.data.changed', version: '1.0.0' }],
        },
      ],
    }),
  )
  await page.route(`**${BASE}/invoke`, async (route) => {
    const request = route.request()
    const body = request.postData() ?? '{}'
    const { tool_id: tool } = JSON.parse(body) as { tool_id: string }
    const key = request.headers()['idempotency-key'] ?? null
    invocations.push({ tool, key, body })
    if (!connected && tool !== 'sync.status')
      return json(route, {
        ...result('failed'),
        error: { code: 'not_configured', message: 'Ключ Bybit не подключён', recovery: 'none' },
      })
    if (tool === 'sync.refill') {
      refills.set(key!, result('completed', DATA[tool]))
      if (refillMode === 'lose-response') {
        refillMode = 'complete'
        return route.abort('connectionreset')
      }
    }
    const args = JSON.parse(body).arguments as Record<string, string | undefined>
    if (tool === 'positions.strategy') strategy = args.coin ?? null
    if (tool === 'positions.method') {
      method = args.method!
      return json(route, result('completed', { lot_method: method }))
    }
    if (tool === 'positions.core') {
      const since = args.since ?? new Date().toISOString()
      core = args.qty
        ? { qty: args.qty, asked: args.qty, since, entry: '81.9', unrealized: '-16038' }
        : null
      return json(
        route,
        result('completed', { coin: args.coin, core: core && { qty: args.qty, since } }),
      )
    }
    if (tool === 'positions.list' || tool === 'position.summary') {
      // Answers in the unit and by the method asked for, or the owner's, as the service does; the summary (LTC)
      // carries the core marked in this test.
      const unit = args.unit ?? strategy ?? 'USD'
      const data = { ...(DATA[tool] as object), unit, lot_method: args.method ?? method }
      if (tool === 'position.summary' && core)
        Object.assign(data, {
          core: { sold_qty: '0', sold_price: null, sold_unrealized: null, ...core },
        })
      return json(route, result('completed', { ...data, strategy_coin: strategy }))
    }
    return json(route, result('completed', DATA[tool]))
  })
  await page.route(`**${BASE}/operations/by-key/*`, (route) => {
    const key = decodeURIComponent(route.request().url().split('/').pop()!)
    const found = refills.get(key)
    return found
      ? json(route, {
          contract: 'assistant.lookup/v1',
          status: 'found',
          operation_key: key,
          result: found,
        })
      : json(route, { error: 'not_found', status: 'not_found', operation_key: key }, 404)
  })
  await page.route(`**${BASE}/configuration`, async (route) => {
    const request = route.request()
    configurations.push({
      method: request.method(),
      ifMatch: request.headers()['if-match'] ?? null,
      body: request.postData(),
    })
    if (request.method() === 'GET') return json(route, view())
    connected = request.method() === 'PUT'
    revision += 1
    return json(route, result('completed', view()))
  })
  await page.route(`**${BASE}/events`, (route) =>
    route.fulfill({
      status: 200,
      contentType: 'text/event-stream',
      body: `event: stream.ready\ndata: ${JSON.stringify({ cursor: 'c1' })}\n\n`,
    }),
  )

  return {
    invocations,
    configurations,
    loseNextRefill: () => (refillMode = 'lose-response'),
  }
}
