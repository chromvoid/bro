// @vitest-environment node
import { readdirSync, readFileSync } from 'node:fs'
import { join } from 'node:path'
import { describe, expect, it, vi } from 'vitest'
import { ref, shallowRef } from 'vue'
import type {
  InvocationOutcome,
  PendingWrite,
  PluginCapabilities,
  PluginEvent,
  PluginHost,
} from '@/plugins/api'
import * as contracts from './contracts'
import { TOOLS } from './contracts'
import {
  CHART_RANGES,
  CoinModel,
  FLOW_FILTERS,
  FlowsModel,
  PERIODS,
  Paged,
  PnlModel,
  PortfolioModel,
  RESOLVE_MS,
  SyncModel,
  Trading,
  affected,
  pluginError,
  read,
  tradingFor,
  valueOf,
} from './model'

const EXAMPLES = join(__dirname, '../package/examples')

function example(tool: string, name: string): { data?: unknown; error?: unknown } {
  return JSON.parse(readFileSync(join(EXAMPLES, tool, `${name}.json`), 'utf8'))
}

const OUTPUTS: Record<string, { safeParse(value: unknown): { success: boolean } }> = {
  [TOOLS.portfolio]: contracts.PortfolioOverview,
  [TOOLS.history]: contracts.PortfolioHistory,
  [TOOLS.position]: contracts.PositionSummary,
  [TOOLS.positions]: contracts.PositionsList,
  [TOOLS.openOrders]: contracts.OrderPage,
  [TOOLS.orderHistory]: contracts.OrderPage,
  [TOOLS.executions]: contracts.ExecutionPage,
  [TOOLS.cashflow]: contracts.CashflowPage,
  [TOOLS.flows]: contracts.FlowsPage,
  [TOOLS.mark]: contracts.MarkResult,
  [TOOLS.strategy]: contracts.StrategyResult,
  [TOOLS.method]: contracts.MethodResult,
  [TOOLS.chart]: contracts.PositionChart,
  [TOOLS.core]: contracts.CoreResult,
  [TOOLS.symbols]: contracts.SymbolList,
  [TOOLS.syncStatus]: contracts.SyncStatus,
  [TOOLS.refill]: contracts.RefillResult,
}

/** A host answering tools from a script; writes are recorded. */
function fakeHost(answers: Record<string, InvocationOutcome | (() => InvocationOutcome)>) {
  const calls: Array<{ tool: string; args: Record<string, unknown> }> = []
  const acknowledged: string[] = []
  const pendingWrites = shallowRef<PendingWrite[]>([])
  const capabilities = ref<PluginCapabilities>({
    status: 'ready',
    note: null,
    grantedScopes: [],
    tools: Object.fromEntries(
      Object.values(TOOLS).map((id) => [
        id,
        {
          id,
          version: '1.0.0',
          effect: [TOOLS.refill, TOOLS.mark, TOOLS.strategy, TOOLS.method, TOOLS.core].includes(
            id as never,
          )
            ? 'write'
            : 'read',
          scopes: [],
          surfaces: [],
        },
      ]),
    ),
    configurationWritable: true,
    events: ['trading.data.changed'],
  })
  const listeners: Array<(event: PluginEvent) => void> = []
  const host: PluginHost = {
    installationId: 'trading-main',
    packageId: 'trading',
    locale: 'ru',
    capabilities,
    lifecycle: ref('foreground'),
    route: ref({ name: 'home', ids: {} }),
    pendingWrites,
    configuration: {
      get: async () => ({
        kind: 'completed',
        data: { status: 'not_configured' },
        receipts: [],
        clientKey: null,
      }),
      put: async () => ({ kind: 'unknown', clientKey: null, notFound: false, error: null }),
      delete: async () => ({ kind: 'unknown', clientKey: null, notFound: false, error: null }),
    },
    invoke: async (tool, args = {}) => {
      calls.push({ tool, args })
      const answer = answers[tool]
      if (!answer)
        return {
          kind: 'rejected',
          error: pluginError('tool_unavailable'),
          status: null,
          clientKey: null,
        }
      return typeof answer === 'function' ? answer() : answer
    },
    lookup: async (key) => {
      const answer = answers[`lookup:${key}`]
      return typeof answer === 'function' ? answer() : answer!
    },
    retry: async (key) => ({ kind: 'unknown', clientKey: key, notFound: true, error: null }),
    acknowledge: async (key) => {
      acknowledged.push(key)
      pendingWrites.value = pendingWrites.value.filter((write) => write.clientKey !== key)
    },
    observe: (listener) => {
      listeners.push(listener)
      return () => listeners.splice(listeners.indexOf(listener), 1)
    },
    navigate: () => undefined,
    close: () => undefined,
  }
  return {
    host,
    calls,
    acknowledged,
    pendingWrites,
    capabilities,
    emit: (event: PluginEvent) => listeners.forEach((l) => l(event)),
  }
}

const completed = (data: unknown): InvocationOutcome => ({
  kind: 'completed',
  data,
  receipts: [],
  clientKey: null,
})
const NOW = Date.parse('2026-09-25T10:05:00Z')

describe('trading contracts', () => {
  it('reads every example of the package', () => {
    const tools = readdirSync(EXAMPLES)
    expect(tools.sort()).toEqual(Object.keys(OUTPUTS).sort())
    for (const tool of tools)
      for (const file of readdirSync(join(EXAMPLES, tool))) {
        const { data } = example(tool, file.replace(/\.json$/, ''))
        if (data !== undefined)
          expect(OUTPUTS[tool]!.safeParse(data).success, `${tool}/${file}`).toBe(true)
      }
  })
})

describe('trading reads', () => {
  it('keeps the last value, marked stale, when a refresh fails', async () => {
    const { host } = fakeHost({
      [TOOLS.portfolio]: completed(example(TOOLS.portfolio, 'overview').data),
    })
    const first = await read(
      host,
      TOOLS.portfolio,
      {},
      contracts.PortfolioOverview,
      { kind: 'loading' },
      NOW,
      undefined,
      (value) => value.freshness,
    )
    expect(first).toMatchObject({ kind: 'ready', stale: false, problem: null })
    const failing = fakeHost({
      [TOOLS.portfolio]: { kind: 'unknown', clientKey: null, notFound: false, error: null },
    })
    const kept = await read(
      failing.host,
      TOOLS.portfolio,
      {},
      contracts.PortfolioOverview,
      first,
      NOW,
    )
    expect(kept).toMatchObject({
      kind: 'ready',
      stale: true,
      problem: { code: 'network_unavailable' },
    })
    expect(
      await read(
        failing.host,
        TOOLS.portfolio,
        {},
        contracts.PortfolioOverview,
        { kind: 'loading' },
        NOW,
      ),
    ).toMatchObject({ kind: 'unavailable' })
  })

  it('tells a missing key and missing rights apart from failures', async () => {
    const notConnected = example(TOOLS.portfolio, 'not-connected').error as {
      code: string
      message: string
    }
    const { host } = fakeHost({
      [TOOLS.portfolio]: {
        kind: 'failed',
        error: { ...pluginError(notConnected.code), message: notConnected.message },
        receipts: [],
        clientKey: null,
      },
      [TOOLS.history]: {
        kind: 'rejected',
        error: pluginError('forbidden'),
        status: 403,
        clientKey: null,
      },
      [TOOLS.symbols]: completed({ nonsense: true }),
    })
    expect(
      (await read(host, TOOLS.portfolio, {}, contracts.PortfolioOverview, { kind: 'loading' }, NOW))
        .kind,
    ).toBe('not_connected')
    expect(
      (await read(host, TOOLS.history, {}, contracts.PortfolioHistory, { kind: 'loading' }, NOW))
        .kind,
    ).toBe('denied')
    expect(
      await read(host, TOOLS.symbols, {}, contracts.SymbolList, { kind: 'loading' }, NOW),
    ).toMatchObject({
      kind: 'unavailable',
      error: { code: 'invalid_output' },
    })
  })

  it('appends the next page only to the list it belongs to', async () => {
    const page = example(TOOLS.executions, 'recent').data as { items: unknown[] }
    const { host, calls } = fakeHost({
      [TOOLS.executions]: () =>
        completed(
          calls.length === 1 ? { ...page, next_cursor: 'p2' } : { ...page, next_cursor: null },
        ),
    })
    const trades = new Paged(host, () => NOW, TOOLS.executions, contracts.ExecutionPage)
    await trades.refresh()
    await trades.more()
    expect(calls.map((call) => call.args)).toEqual([{ limit: 50 }, { limit: 50, cursor: 'p2' }])
    const value = trades.page.value.kind === 'ready' ? trades.page.value.value : null
    expect(value?.items).toHaveLength(page.items.length * 2)
    expect(value?.next_cursor).toBeNull()
  })
})

describe('long lists', () => {
  it('reload as many rows as were shown when data changes', async () => {
    const page = example(TOOLS.executions, 'recent').data as { items: Array<{ exec_id: string }> }
    const rows = (count: number, from: number) =>
      Array.from({ length: count }, (_, index) => ({
        ...page.items[0]!,
        exec_id: `e${from + index}`,
      }))
    const { host, calls } = fakeHost({
      [TOOLS.executions]: () => {
        const args = calls.at(-1)!.args as { limit: number; cursor?: string }
        const from = args.cursor ? Number(args.cursor) : 0
        return completed({
          ...page,
          items: rows(args.limit, from),
          next_cursor: String(from + args.limit),
        })
      },
    })
    const trades = new Paged(host, () => NOW, TOOLS.executions, contracts.ExecutionPage)
    await trades.refresh()
    for (let index = 0; index < 5; index += 1) await trades.more()
    await trades.refresh()
    expect(calls.slice(6).map((call) => call.args)).toEqual([
      { limit: 200 },
      { limit: 100, cursor: '200' },
    ])
    const value = trades.page.value.kind === 'ready' ? trades.page.value.value : null
    expect(value?.items).toHaveLength(300)
    expect(value?.next_cursor).toBe('300')
  })
})

describe('money movements', () => {
  it('asks for the chosen filter on every page and starts over when it changes', async () => {
    const page = example(TOOLS.flows, 'year').data as { items: unknown[] }
    const { host, calls } = fakeHost({
      [TOOLS.flows]: () => completed({ ...page, next_cursor: calls.length === 2 ? 'p2' : null }),
    })
    const flows = new FlowsModel(host, () => NOW)
    await flows.refresh()
    flows.select(FLOW_FILTERS.find((filter) => filter.key === 'outside')!)
    await new Promise((resolve) => setTimeout(resolve, 0))
    await flows.list.more()
    expect(calls.map((call) => call.args)).toEqual([
      { limit: 50 },
      { internal: false, limit: 50 },
      { internal: false, limit: 50, cursor: 'p2' },
    ])
  })
})

describe('positions', () => {
  it('count in a chosen unit and make it the strategy coin', async () => {
    const list = example(TOOLS.positions, 'all').data as Record<string, unknown>
    const { host, calls } = fakeHost({
      [TOOLS.positions]: () => {
        const unit = (calls.at(-1)!.args.unit as string | undefined) ?? 'USD'
        return completed({ ...list, unit })
      },
      [TOOLS.position]: completed(example(TOOLS.position, 'ltc').data),
      [TOOLS.chart]: completed(example(TOOLS.chart, 'ltc').data),
      [TOOLS.strategy]: completed(example(TOOLS.strategy, 'btc').data),
    })
    const pnl = new PnlModel(host, () => NOW)
    await pnl.refresh()
    expect(pnl.shownUnit()).toBe('USD')
    pnl.count('BTC')
    await new Promise((resolve) => setTimeout(resolve, 0))
    expect(pnl.shownUnit()).toBe('BTC')
    const reads = calls.filter((call) => call.tool !== TOOLS.strategy)
    // The list reads every coin; a coin's page is read only when it is shown.
    expect(reads.map((call) => call.tool)).toEqual([TOOLS.positions, TOOLS.positions])
    expect(reads.at(-1)!.args).toEqual({
      range: { period: '30d' },
      unit: 'BTC',
      include_closed: true,
      include_stables: true,
    })
    const coin = new CoinModel(host, () => NOW, pnl)
    coin.select('LTC')
    await coin.refresh()
    // A coin's page reads its position and its chart, in the list's unit.
    expect(calls.slice(-2)).toEqual([
      { tool: TOOLS.position, args: { range: { period: '30d' }, unit: 'BTC', coin: 'LTC' } },
      { tool: TOOLS.chart, args: { range: { period: '1y' }, unit: 'BTC', coin: 'LTC' } },
    ])
    expect(valueOf(coin.chart.value)?.trades).toHaveLength(2)
    coin.show(CHART_RANGES[2])
    await new Promise((resolve) => setTimeout(resolve, 0))
    expect(calls.at(-1)!.args.range).toEqual({ period: 'all' })
    await pnl.setStrategy('BTC')
    expect(calls.find((call) => call.tool === TOOLS.strategy)!.args).toEqual({ coin: 'BTC' })
    expect(pnl.unit.value).toBeNull() // the service now counts in the strategy coin by default
  })

  it('shows the book by the other lot method and keeps it as the owner’s', async () => {
    const list = example(TOOLS.positions, 'all').data as Record<string, unknown>
    let saved = 'lifo'
    const { host, calls } = fakeHost({
      [TOOLS.positions]: () =>
        completed({
          ...list,
          lot_method: (calls.at(-1)!.args.method as string | undefined) ?? saved,
        }),
      [TOOLS.position]: completed(example(TOOLS.position, 'ltc').data),
      [TOOLS.method]: () => {
        saved = calls.at(-1)!.args.method as string
        return completed({ lot_method: saved })
      },
    })
    const pnl = new PnlModel(host, () => NOW)
    await pnl.refresh()
    expect(pnl.shownMethod()).toBe('lifo')
    await pnl.useMethod('fifo')
    expect(calls.filter((call) => call.tool === TOOLS.method).map((call) => call.args)).toEqual([
      { method: 'fifo' },
    ])
    expect(pnl.method.value).toBeNull() // saved: the service answers by the owner's method
    expect(pnl.shownMethod()).toBe('fifo')
    expect(calls.filter((call) => call.tool === TOOLS.positions).at(-1)!.args).toEqual({
      range: { period: '30d' },
      include_closed: true,
      include_stables: true,
    })
  })

  it('marks a core and reads the positions again', async () => {
    const { host, calls } = fakeHost({
      [TOOLS.positions]: completed(example(TOOLS.positions, 'all').data),
      [TOOLS.position]: completed(example(TOOLS.position, 'ltc').data),
      [TOOLS.core]: completed(example(TOOLS.core, 'gram').data),
    })
    const pnl = new PnlModel(host, () => NOW)
    await pnl.refresh()
    const reads = calls.length
    expect(await pnl.setCore('GRAM', '70000', '2025-08-19T00:00:00.000Z')).toBe(true)
    expect(calls[reads]).toEqual({
      tool: TOOLS.core,
      args: { coin: 'GRAM', qty: '70000', since: '2025-08-19T00:00:00.000Z' },
    })
    expect(calls.slice(reads + 1).map((call) => call.tool)).toEqual([TOOLS.positions])
    const gram = valueOf(pnl.positions.value)!.items.find((item) => item.coin === 'GRAM')!
    expect(gram.core?.entry).toBe('3.5')
  })

  it('keeps the chosen lot method on screen when it cannot be saved', async () => {
    const list = example(TOOLS.positions, 'all').data as Record<string, unknown>
    const { host, calls } = fakeHost({
      [TOOLS.positions]: () =>
        completed({
          ...list,
          lot_method: (calls.at(-1)!.args.method as string | undefined) ?? 'lifo',
        }),
      [TOOLS.position]: completed(example(TOOLS.position, 'ltc').data),
      [TOOLS.method]: {
        kind: 'failed',
        error: pluginError('forbidden'),
        receipts: [],
        clientKey: null,
      },
    })
    const pnl = new PnlModel(host, () => NOW)
    await pnl.refresh()
    await pnl.useMethod('fifo')
    expect(pnl.saveError.value?.code).toBe('forbidden')
    expect(pnl.method.value).toBe('fifo')
    expect(pnl.shownMethod()).toBe('fifo')
    expect(calls.filter((call) => call.tool === TOOLS.positions).at(-1)!.args).toEqual({
      range: { period: '30d' },
      method: 'fifo',
      include_closed: true,
      include_stables: true,
    })
  })
})

describe('marks', () => {
  it('looks a lost mark up, forgets it and reads the list again', async () => {
    const page = example(TOOLS.flows, 'year').data
    const { host, calls, acknowledged } = fakeHost({
      [TOOLS.flows]: completed(page),
      [TOOLS.mark]: { kind: 'unknown', clientKey: 'mark-key', notFound: false, error: null },
      'lookup:mark-key': {
        kind: 'completed',
        data: example(TOOLS.mark, 'own-wallet').data,
        receipts: [],
        clientKey: 'mark-key',
      },
    })
    const flows = new FlowsModel(host, () => NOW)
    await flows.refresh()
    expect(await flows.mark('funding:NjA0ODI4MjY4MA==', 'external', '61000')).toBe(true)
    expect(calls.filter((call) => call.tool === TOOLS.mark).map((call) => call.args)).toEqual([
      { id: 'funding:NjA0ODI4MjY4MA==', treatment: 'external', unit_cost_usd: '61000' },
    ])
    expect(acknowledged).toEqual(['mark-key'])
    expect(calls.filter((call) => call.tool === TOOLS.flows)).toHaveLength(2)
    expect(flows.saving.value).toBeNull()
  })
})

describe('refill', () => {
  it('resolves an unknown refill by lookup and acknowledges it once finished', async () => {
    const write: PendingWrite = {
      clientKey: 'key-0000000000000001',
      tool: TOOLS.refill,
      arguments: {},
      state: 'uncertain',
      createdAt: 1,
      notFound: false,
      admitted: false,
    }
    const fake = fakeHost({
      [TOOLS.syncStatus]: completed(example(TOOLS.syncStatus, 'live').data),
      [TOOLS.refill]: () => {
        fake.pendingWrites.value = [write]
        return { kind: 'unknown', clientKey: write.clientKey, notFound: false, error: null }
      },
      [`lookup:${write.clientKey}`]: {
        kind: 'completed',
        data: example(TOOLS.refill, 'executions').data,
        receipts: [],
        clientKey: write.clientKey,
      },
    })
    const sync = new SyncModel(fake.host, () => NOW)
    await sync.refill()
    expect(sync.notice.value).toBeNull()
    expect(sync.refills.value).toHaveLength(1)
    // While one refill is unresolved, no second one starts.
    await sync.refill()
    expect(fake.calls.filter((call) => call.tool === TOOLS.refill)).toHaveLength(1)
    await sync.check(write.clientKey)
    expect(sync.notice.value).toEqual({ kind: 'done' })
    expect(sync.refills.value).toEqual([])
  })

  it('offers no refill without the write tool', () => {
    const fake = fakeHost({})
    delete fake.capabilities.value.tools[TOOLS.refill]
    expect(new SyncModel(fake.host, () => NOW).canRefill.value).toBe(false)
  })
})

describe('events', () => {
  it('maps data domains to the screens they make stale', () => {
    const delivered = (type: string, domains: unknown[] = []): PluginEvent => ({
      kind: 'delivered',
      eventId: 'e',
      type,
      occurredAt: '',
      cursor: 'c',
      payload: { domains },
      operationKey: null,
    })
    expect([...affected(delivered('trading.data.changed', ['wallet']))].sort()).toEqual([
      'pnl',
      'portfolio',
      'position',
      'sync',
    ])
    expect([...affected(delivered('trading.data.changed', ['something_new']))]).toHaveLength(8)
    expect([...affected(delivered('trading.sync.progress'))]).toEqual(['sync'])
    expect([...affected({ kind: 'denied', status: 403 })]).toEqual([])
    expect([...affected({ kind: 'resync', cursor: 'c' })]).toHaveLength(8)
  })

  it('reloads the visible screen once for a burst of events', async () => {
    const fake = fakeHost({
      [TOOLS.portfolio]: completed(example(TOOLS.portfolio, 'overview').data),
      [TOOLS.history]: completed(example(TOOLS.history, 'week').data),
    })
    const trading = new Trading(fake.host, () => NOW)
    trading.start()
    trading.show('portfolio')
    await new Promise((resolve) => setTimeout(resolve, 0))
    const before = fake.calls.length
    for (let index = 0; index < 5; index += 1)
      fake.emit({
        kind: 'delivered',
        eventId: `e${index}`,
        type: 'trading.data.changed',
        occurredAt: '',
        cursor: `c${index}`,
        payload: { domains: ['wallet'] },
        operationKey: null,
      })
    expect(trading.live.value).toBe(true)
    await new Promise((resolve) => setTimeout(resolve, 400))
    // One reload: the overview, the history and the trading result.
    expect(fake.calls.length - before).toBe(3)
    trading.close()
  })
})

/** Holds the reads `held` picks until `open` is called. */
function holding(
  host: PluginHost,
  held: (tool: string, args: Record<string, unknown>) => boolean = () => true,
) {
  let open!: () => void
  const gate = new Promise<void>((resolve) => (open = resolve))
  const invoke = host.invoke
  host.invoke = async (tool, args = {}) => {
    if (held(tool, args)) await gate
    return invoke(tool, args)
  }
  return () => open()
}
const settled = () => new Promise((resolve) => setTimeout(resolve, 0))

describe('reads that overlap', () => {
  it('keeps the newest chart after switching range A to B and back to A', async () => {
    const original = example(TOOLS.chart, 'ltc').data as Record<string, unknown>
    let finish!: (result: InvocationOutcome) => void
    const older = new Promise<InvocationOutcome>((resolve) => (finish = resolve))
    const fake = fakeHost({})
    let calls = 0
    fake.host.invoke = async () => {
      if (++calls === 1) return older
      return completed({ ...original, points: [{ time: '2026-09-25T00:00:00Z', price: '222' }] })
    }
    const coin = new CoinModel(fake.host, () => NOW, new PnlModel(fake.host, () => NOW))
    coin.select('LTC')
    coin.show(CHART_RANGES[2])
    coin.show(CHART_RANGES[0])
    coin.show(CHART_RANGES[2])
    await settled()
    expect(valueOf(coin.chart.value)!.points[0]!.price).toBe('222')
    finish(completed({ ...original, points: [{ time: '2026-09-25T00:00:00Z', price: '111' }] }))
    await settled()
    expect(valueOf(coin.chart.value)!.points[0]!.price).toBe('222')
  })

  it('does not restore chart data from before a core or default preference changed', async () => {
    const original = example(TOOLS.chart, 'ltc').data
    let finish!: (result: InvocationOutcome) => void
    const older = new Promise<InvocationOutcome>((resolve) => (finish = resolve))
    const fake = fakeHost({})
    fake.host.invoke = () => older
    const coin = new CoinModel(fake.host, () => NOW, new PnlModel(fake.host, () => NOW))
    coin.select('LTC')
    coin.show(CHART_RANGES[2])
    coin.forget()
    coin.recall()
    finish(completed(original))
    await settled()
    expect(coin.chart.value.kind).toBe('loading')
    coin.recall()
    expect(coin.chart.value.kind).toBe('loading')
  })

  it('drops an older next page after refresh even when the cursor is unchanged', async () => {
    const original = contracts.OrderPage.parse(example(TOOLS.openOrders, 'one').data)
    let finish!: (result: InvocationOutcome) => void
    const older = new Promise<InvocationOutcome>((resolve) => (finish = resolve))
    const fake = fakeHost({})
    fake.host.invoke = async (_, args = {}) =>
      args.cursor ? older : completed({ ...original, next_cursor: 'same-boundary' })
    const list = new Paged(fake.host, () => NOW, TOOLS.openOrders, contracts.OrderPage)
    await list.refresh()
    const more = list.more()
    await list.refresh()
    finish(
      completed({
        ...original,
        items: [{ ...original.items[0]!, order_id: 'cancelled-before-refresh' }],
        next_cursor: null,
      }),
    )
    await more
    expect(valueOf(list.page.value)!.items).toEqual(original.items)
    expect(valueOf(list.page.value)!.next_cursor).toBe('same-boundary')
  })

  it('refresh includes rows that more added before the refreshed first page returned', async () => {
    const original = contracts.OrderPage.parse(example(TOOLS.openOrders, 'one').data)
    const rows = (start: number, count: number) =>
      Array.from({ length: count }, (_, index) => ({
        ...original.items[0]!,
        order_id: String(start + index),
      }))
    let finish!: (result: InvocationOutcome) => void
    const refreshed = new Promise<InvocationOutcome>((resolve) => (finish = resolve))
    const fake = fakeHost({})
    let firstPages = 0
    fake.host.invoke = async (_, args = {}) => {
      if (args.cursor) return completed({ ...original, items: rows(50, 50), next_cursor: null })
      if (++firstPages === 2) return refreshed
      return completed({ ...original, items: rows(0, 50), next_cursor: 'next' })
    }
    const list = new Paged(fake.host, () => NOW, TOOLS.openOrders, contracts.OrderPage)
    await list.refresh()
    const refreshing = list.refresh()
    await list.more()
    expect(valueOf(list.page.value)!.items).toHaveLength(100)
    finish(completed({ ...original, items: rows(0, 50), next_cursor: 'next' }))
    await refreshing
    expect(valueOf(list.page.value)!.items.map((item) => item.order_id)).toEqual(
      rows(0, 100).map((item) => item.order_id),
    )
  })

  it('reads the chosen PnL period and keeps its data separate from other periods', async () => {
    const original = contracts.PositionsList.parse(example(TOOLS.positions, 'all').data)
    const fake = fakeHost({
      [TOOLS.positions]: () => {
        const range = fake.calls.at(-1)!.args.range as { period: string }
        const value = String(PERIODS.findIndex((period) => period.wire === range.period) + 1)
        return completed({
          ...original,
          totals: { ...original.totals, realized_in_range: value },
        })
      },
    })
    const pnl = new PnlModel(fake.host, () => NOW)
    await pnl.refresh()
    for (const [index, period] of PERIODS.entries()) {
      pnl.select(period)
      await settled()
      expect(fake.calls.at(-1)!.args).toEqual({
        range: { period: period.wire },
        include_closed: true,
        include_stables: true,
      })
      expect(valueOf(pnl.positions.value)!.totals.realized_in_range).toBe(String(index + 1))
    }
    const open = holding(fake.host)
    pnl.select(PERIODS[0])
    expect(valueOf(pnl.positions.value)!.totals.realized_in_range).toBe('1')
    open()
    await settled()
  })

  it('drops PnL from a previous period when its response arrives last', async () => {
    const original = contracts.PositionsList.parse(example(TOOLS.positions, 'all').data)
    const fake = fakeHost({
      [TOOLS.positions]: () => {
        const range = fake.calls.at(-1)!.args.range as { period: string }
        return completed({
          ...original,
          totals: { ...original.totals, realized_in_range: range.period === '24h' ? '1' : '99' },
        })
      },
    })
    const open = holding(
      fake.host,
      (_, args) => (args.range as { period: string }).period === '24h',
    )
    const pnl = new PnlModel(fake.host, () => NOW)
    pnl.select(PERIODS[0])
    pnl.select(PERIODS[5])
    await settled()
    expect(valueOf(pnl.positions.value)!.totals.realized_in_range).toBe('99')
    open()
    await settled()
    expect(fake.calls.at(-1)!.args.range).toEqual({ period: '24h' })
    expect(valueOf(pnl.positions.value)!.totals.realized_in_range).toBe('99')
    expect(pnl.period.value).toBe(PERIODS[5])
  })

  it('show a read that was running when data changed, then read once more', async () => {
    const fake = fakeHost({ [TOOLS.positions]: completed(example(TOOLS.positions, 'all').data) })
    const open = holding(fake.host)
    const pnl = new PnlModel(fake.host, () => NOW)
    const first = pnl.refresh()
    pnl.update()
    pnl.update() // a burst of changes: one more read, not one each
    open()
    await first
    expect(pnl.positions.value.kind).toBe('ready') // not dropped for a newer read
    await settled()
    expect(fake.calls.filter((call) => call.tool === TOOLS.positions)).toHaveLength(2)
  })

  it('show a period read before at once when it is chosen again', async () => {
    const fake = fakeHost({
      [TOOLS.portfolio]: completed(example(TOOLS.portfolio, 'overview').data),
      [TOOLS.history]: completed(example(TOOLS.history, 'week').data),
      [TOOLS.positions]: completed(example(TOOLS.positions, 'all').data),
    })
    const portfolio = new PortfolioModel(fake.host, () => NOW)
    await portfolio.refresh()
    portfolio.select(PERIODS[5])
    expect(portfolio.history.value.kind).toBe('loading')
    await settled()
    portfolio.select(PERIODS[2])
    expect(portfolio.history.value.kind).toBe('ready')
  })

  it('drop a chart that comes back after the unit changed', async () => {
    const chart = example(TOOLS.chart, 'ltc').data as Record<string, unknown>
    const fake = fakeHost({
      [TOOLS.positions]: completed(example(TOOLS.positions, 'all').data),
      [TOOLS.position]: completed(example(TOOLS.position, 'ltc').data),
      [TOOLS.chart]: () =>
        completed({ ...chart, unit: (fake.calls.at(-1)!.args.unit as string) ?? 'BTC' }),
    })
    const pnl = new PnlModel(fake.host, () => NOW)
    const coin = new CoinModel(fake.host, () => NOW, pnl)
    coin.select('LTC')
    await coin.refresh()
    const open = holding(fake.host, (tool, args) => tool === TOOLS.chart && !args.unit)
    coin.show(CHART_RANGES[2]) // held: in the strategy coin
    pnl.unit.value = 'USD'
    coin.recall()
    await coin.refresh()
    expect(valueOf(coin.chart.value)!.unit).toBe('USD')
    open()
    await settled()
    expect(valueOf(coin.chart.value)!.unit).toBe('USD')
  })

  it('count a coin opened by a link in any unit before the list was read', () => {
    const fake = fakeHost({ [TOOLS.positions]: completed(example(TOOLS.positions, 'all').data) })
    const pnl = new PnlModel(fake.host, () => NOW)
    pnl.count('USD') // the page may show the strategy coin: USD is a change
    expect(pnl.unit.value).toBe('USD')
  })
})

describe('the plugin reopened', () => {
  it('shows what was read at once, then reads it again', async () => {
    const fake = fakeHost({
      [TOOLS.portfolio]: completed(example(TOOLS.portfolio, 'overview').data),
      [TOOLS.history]: completed(example(TOOLS.history, 'week').data),
      [TOOLS.positions]: completed(example(TOOLS.positions, 'all').data),
    })
    const trading = tradingFor(fake.host)
    trading.show('portfolio')
    trading.start()
    await settled()
    trading.close()
    expect(tradingFor(fake.host)).toBe(trading)
    expect(trading.portfolio.overview.value.kind).toBe('ready')
    const before = fake.calls.length
    trading.show('portfolio')
    trading.start()
    expect(trading.portfolio.overview.value.kind).toBe('ready')
    await settled()
    expect(fake.calls.length - before).toBe(3)
    trading.close()
  })

  it('looks an unfinished refill up once for a burst of events', async () => {
    vi.useFakeTimers()
    try {
      const write: PendingWrite = {
        clientKey: 'key-0000000000000002',
        tool: TOOLS.refill,
        arguments: {},
        state: 'uncertain',
        createdAt: 1,
        notFound: false,
        admitted: true,
      }
      const fake = fakeHost({
        [TOOLS.portfolio]: completed(example(TOOLS.portfolio, 'overview').data),
        [TOOLS.history]: completed(example(TOOLS.history, 'week').data),
        [TOOLS.positions]: completed(example(TOOLS.positions, 'all').data),
        [`lookup:${write.clientKey}`]: {
          kind: 'unknown',
          clientKey: write.clientKey,
          notFound: false,
          error: null,
        },
      })
      fake.pendingWrites.value = [write]
      let lookups = 0
      const lookup = fake.host.lookup
      fake.host.lookup = (key) => ((lookups += 1), lookup(key))
      const trading = new Trading(fake.host, () => NOW)
      trading.show('portfolio')
      trading.start()
      for (let index = 0; index < 5; index += 1) {
        fake.emit({
          kind: 'delivered',
          eventId: `r${index}`,
          type: 'trading.data.changed',
          occurredAt: '',
          cursor: `r${index}`,
          payload: { domains: ['executions'] },
          operationKey: null,
        })
        await vi.advanceTimersByTimeAsync(1000)
      }
      await vi.advanceTimersByTimeAsync(RESOLVE_MS)
      expect(lookups).toBe(1)
      trading.close()
    } finally {
      vi.useRealTimers()
    }
  })
})
