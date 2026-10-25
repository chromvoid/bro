/**
 * The trading UI's own view of the tool outputs, checked against package/examples. Decimals stay
 * strings; enums stay strings so a value added by a newer service gets a neutral label.
 */
import { z } from '@/core/zod'

export const TOOLS = {
  portfolio: 'portfolio.get',
  history: 'portfolio.history',
  position: 'position.summary',
  chart: 'position.chart',
  positions: 'positions.list',
  strategy: 'positions.strategy',
  method: 'positions.method',
  core: 'positions.core',
  openOrders: 'orders.open',
  orderHistory: 'orders.history',
  executions: 'executions.list',
  cashflow: 'cashflow.list',
  flows: 'flows.list',
  mark: 'flows.mark',
  symbols: 'symbols.list',
  syncStatus: 'sync.status',
  refill: 'sync.refill',
} as const

/** Reads the screens need; the refill write is optional (it needs trading.write). */
export const READS = [
  TOOLS.portfolio,
  TOOLS.history,
  TOOLS.position,
  TOOLS.chart,
  TOOLS.positions,
  TOOLS.openOrders,
  TOOLS.orderHistory,
  TOOLS.executions,
  TOOLS.flows,
  TOOLS.syncStatus,
]

/** Major versions this module speaks; tools not listed are at 1. */
export const MAJORS: Record<string, number> = { [TOOLS.position]: 5, [TOOLS.positions]: 4 }

const text = z.string()
const maybe = z.string().nullish()
const amount = z.string().nullable()

export const Freshness = z.looseObject({
  as_of: maybe,
  connection_state: z.string().default(''),
  stream: z.string().default(''),
  last_reconcile_at: maybe,
  history_complete: z.boolean().default(false),
  coverage_from: maybe,
})
export type Freshness = z.infer<typeof Freshness>

/** A change of value; `market_*` is what is left after the money moved in or out (`net_flow_usd`). */
const Change = z.looseObject({
  usd_delta: text,
  pct: maybe,
  net_flow_usd: maybe,
  market_usd: maybe,
  market_pct: maybe,
})
export type Change = z.infer<typeof Change>

export const ACCOUNTS = ['unified', 'funding', 'earn'] as const

export const Balance = z.looseObject({
  coin: text,
  quantity: text,
  usd_value: maybe,
  accounts: z.looseObject({ unified: maybe, funding: maybe, earn: maybe }).nullish(),
  price_usd: maybe,
  share_pct: maybe,
  day: z.looseObject({ quantity_delta: maybe, usd_delta: maybe, pct: maybe }).default({}),
})
export type Balance = z.infer<typeof Balance>

export const EarnPosition = z.looseObject({
  coin: text,
  category: text,
  product_id: text,
  amount: text,
  yield_pending: maybe,
  pnl: maybe,
})

export const PortfolioOverview = z.looseObject({
  total_usd: maybe,
  change: z
    .looseObject({ day: Change.nullish(), week: Change.nullish(), month: Change.nullish() })
    .default({}),
  by_account: z.record(z.string(), text).default({}),
  balances: z.array(Balance).default([]),
  earn: z.array(EarnPosition).default([]),
  freshness: Freshness,
})
export type PortfolioOverview = z.infer<typeof PortfolioOverview>

export const PortfolioHistory = z.looseObject({
  /** `flow_usd`: money moved in (positive) or out since the previous point, which the change includes. */
  points: z
    .array(
      z.looseObject({
        time: text,
        total_usd: text,
        flow_usd: maybe,
        /** Worked back from the trade history where no snapshot was taken. */
        rebuilt: z.boolean().default(false),
      }),
    )
    .default([]),
  earliest_available: maybe,
  freshness: Freshness,
})
export type PortfolioHistory = z.infer<typeof PortfolioHistory>

export const Execution = z.looseObject({
  exec_id: text,
  order_id: text,
  symbol: text,
  base_coin: maybe,
  quote_coin: maybe,
  side: text,
  price: text,
  qty: text,
  value: text,
  fee: text,
  fee_coin: maybe,
  is_maker: z.boolean().default(false),
  exec_time: text,
})
export type Execution = z.infer<typeof Execution>

export const Order = z.looseObject({
  order_id: text,
  symbol: text,
  base_coin: maybe,
  quote_coin: maybe,
  side: text,
  type: text,
  status: text,
  price: maybe,
  /** Counted in the base coin, or in the quote coin for a Market buy of an amount (`qty_unit`). */
  qty: text,
  qty_unit: maybe,
  filled_qty: text,
  filled_value: maybe,
  avg_price: maybe,
  created_at: text,
  updated_at: text,
})
export type Order = z.infer<typeof Order>

export const OrderPage = z.looseObject({
  items: z.array(Order).default([]),
  next_cursor: maybe,
  freshness: Freshness,
})
export type OrderPage = z.infer<typeof OrderPage>

export const ExecutionPage = z.looseObject({
  items: z.array(Execution).default([]),
  next_cursor: maybe,
  freshness: Freshness,
})
export type ExecutionPage = z.infer<typeof ExecutionPage>

export const CashflowItem = z.looseObject({
  id: text,
  kind: text,
  coin: text,
  amount: text,
  status: text,
  occurred_at: text,
  chain: maybe,
  usd_value_at_event: maybe,
})
export type CashflowItem = z.infer<typeof CashflowItem>

export const CashflowPage = z.looseObject({
  items: z.array(CashflowItem).default([]),
  totals: z
    .looseObject({
      by_coin: z
        .array(z.looseObject({ coin: text, deposited: text, withdrawn: text, net: text }))
        .default([]),
      usd: z
        .looseObject({ deposited: text, withdrawn: text, unpriced_count: z.number().default(0) })
        .nullish(),
    })
    .default({ by_coin: [] }),
  next_cursor: maybe,
  freshness: Freshness,
})
export type CashflowPage = z.infer<typeof CashflowPage>

/** A balance change outside trading, from Bybit's own account ledgers. */
export const FlowItem = z.looseObject({
  id: text,
  account: text,
  kind: text,
  operation: text,
  direction: text,
  coin: text,
  amount: text,
  counterparty: maybe,
  external: z.boolean().default(true),
  note: maybe,
  occurred_at: text,
  usd_value_at_event: maybe,
  /** A deposit or withdrawal the owner can mark as to or from their own wallet, or as money from outside. */
  markable: z.boolean().default(false),
  treatment: z.string().default('auto'),
  unit_cost_usd: maybe,
})
export type FlowItem = z.infer<typeof FlowItem>
export const TREATMENTS = ['auto', 'own_wallet', 'external'] as const
export type Treatment = (typeof TREATMENTS)[number]

export const MarkResult = z.looseObject({ id: text, treatment: text, unit_cost_usd: maybe })

export const FlowsPage = z.looseObject({
  items: z.array(FlowItem).default([]),
  totals: z
    .looseObject({
      by_coin: z.array(z.looseObject({ coin: text, in: text, out: text, net: text })).default([]),
      by_kind: z
        .array(
          z.looseObject({ kind: text, count: z.number().default(0), usd_in: text, usd_out: text }),
        )
        .default([]),
      usd: z
        .looseObject({ in: text, out: text, net: text, unpriced_count: z.number().default(0) })
        .nullish(),
      /** Money in and out by calendar month, oldest first. */
      by_month: z.array(z.looseObject({ month: text, usd_in: text, usd_out: text })).default([]),
    })
    .default({ by_coin: [], by_kind: [], by_month: [] }),
  next_cursor: maybe,
  freshness: Freshness,
})
export type FlowsPage = z.infer<typeof FlowsPage>

export const SymbolList = z.looseObject({
  items: z.array(z.looseObject({ symbol: text, base_coin: text, quote_coin: text })).default([]),
  freshness: Freshness,
})
export type SymbolList = z.infer<typeof SymbolList>

/**
 * The owner's core of a coin: from `since`, `qty` of the coins then held are the position held for long; what was
 * sold from it and not bought back yet, and what that sale gains or loses at today's price.
 */
export const CorePosition = z.looseObject({
  qty: amount,
  asked: text,
  since: text,
  entry: maybe,
  unrealized: maybe,
  sold_qty: amount,
  sold_price: maybe,
  sold_unrealized: maybe,
})
export type CorePosition = z.infer<typeof CorePosition>

/** A source event that prevents a complete calculation; unknown codes remain readable. */
export const PositionIssue = z.looseObject({
  code: text,
  coin: text,
  occurred_at: text,
  entry_id: text.nullable(),
})
export type PositionIssue = z.infer<typeof PositionIssue>
const PositionIssues = z.array(PositionIssue)

/** One coin's position on all accounts, in the list's unit: USD or a coin (calc/book.py of the service). */
export const PositionItem = z.looseObject({
  coin: text,
  open_qty: text,
  value: maybe,
  held_qty: maybe,
  unexplained_qty: maybe,
  /** The coin it was traded against most (LTC: BTC). */
  traded_against: maybe,
  /** Sold out, or only dust is left. */
  closed: z.boolean().default(false),
  /** Everything the coin brought; null when a complete cost or valuation cannot be established. */
  result: maybe,
  core: CorePosition.nullish(),
  avg_entry: maybe,
  break_even: maybe,
  cycle_started_at: maybe,
  realized_in_cycle: maybe,
  mark: maybe,
  unrealized: maybe,
  unrealized_pct: maybe,
  realized: amount,
  realized_in_range: amount,
  income: amount,
  income_in_range: amount,
  fees: amount,
  avg_buy: maybe,
  issues: PositionIssues,
})
export type PositionItem = z.infer<typeof PositionItem>

/** Which purchases a sale closes: the latest (the default) or the oldest. */
export const LOT_METHODS = ['lifo', 'fifo'] as const
export type LotMethod = (typeof LOT_METHODS)[number]

/** Realized results inside the selected range; null months have an incomplete calculation. */
const Months = z.array(z.looseObject({ month: text, realized: amount })).default([])

export const PositionsList = z.looseObject({
  unit: text,
  strategy_coin: maybe,
  lot_method: z.string().default('lifo'),
  as_of: maybe,
  items: z.array(PositionItem).default([]),
  totals: z.looseObject({
    realized: amount,
    realized_in_range: amount,
    unrealized: amount,
    income: amount,
    income_in_range: amount,
    fees: amount,
    value: amount,
    result: amount,
    realized_by_month: Months,
    issues: PositionIssues,
  }),
  freshness: Freshness,
})
export type PositionsList = z.infer<typeof PositionsList>

export const PositionSummary = PositionItem.extend({
  unit: text,
  lot_method: z.string().default('lifo'),
  as_of: maybe,
  open_cost: maybe,
  opening: z.looseObject({ qty: text, at: text }).nullish(),
  away_qty: z.string().default('0'),
  short_qty: z.string().default('0'),
  unpriced_events: z.number().default(0),
  lots: z
    .array(z.looseObject({ qty: text, unit_cost: maybe, core: z.boolean().default(false) }))
    .default([]),
  lots_total: z.number().default(0),
  realized_by_month: Months,
  freshness: Freshness,
})
export type PositionSummary = z.infer<typeof PositionSummary>

/** A coin's price in the unit over a range, its buys and sells there, and the prices it is judged by. */
export const PositionChart = z.looseObject({
  coin: text,
  unit: text,
  from: text,
  points: z.array(z.looseObject({ time: text, price: text })).default([]),
  trades: z
    .array(
      z.looseObject({
        time: text,
        side: text,
        qty: text,
        fills: z.number().default(1),
        price: text,
      }),
    )
    .default([]),
  lines: z.looseObject({
    avg_entry: maybe,
    break_even: maybe,
    core_entry: maybe,
    mark: maybe,
  }),
  issues: PositionIssues.default([]),
  freshness: Freshness,
})
export type PositionChart = z.infer<typeof PositionChart>

export const StrategyResult = z.looseObject({ strategy_coin: maybe })
export const MethodResult = z.looseObject({ lot_method: text })
export const CoreResult = z.looseObject({
  coin: text,
  core: z.looseObject({ qty: text, since: text }).nullable(),
})

export const SyncStatus = z.looseObject({
  connection: z
    .looseObject({
      state: text,
      environment: z.string().default(''),
      api_key_hint: maybe,
      key_expires_at: maybe,
    })
    .nullish(),
  stream: z.looseObject({ state: text }),
  domains: z
    .array(
      z.looseObject({
        domain: text,
        phase: text,
        watermark_at: maybe,
        progress_pct: maybe,
        last_error_code: maybe,
        coverage_gap_before: maybe,
      }),
    )
    .default([]),
})
export type SyncStatus = z.infer<typeof SyncStatus>

export const RefillResult = z.looseObject({
  domains: z.array(z.looseObject({ domain: text, watermark_at: maybe })).default([]),
})

/** `assistant.configuration/v1` of this plugin: secrets never come back, only their presence. */
export const ConfigurationView = z.looseObject({
  status: text,
  revision: z.number().default(0),
  public: z
    .looseObject({ environment: maybe, label: maybe, history_days: z.number().nullish() })
    .default({}),
  secret_state: z
    .record(z.string(), z.looseObject({ present: z.boolean().default(false), hint: maybe }))
    .default({}),
  readiness: z
    .looseObject({
      state: z.string().default(''),
      issues: z.array(z.looseObject({ code: text, recovery: maybe })).default([]),
    })
    .default({ state: '', issues: [] }),
  connection_state: maybe,
  key_info: z
    .looseObject({
      read_only: z.boolean().nullish(),
      ip_bound: z.boolean().nullish(),
      expires_at: maybe,
    })
    .nullish(),
  environments: z.array(z.string()).default([]),
  egress_ips: z.array(z.string()).default([]),
})
export type ConfigurationView = z.infer<typeof ConfigurationView>

export const configured = (view: ConfigurationView) => view.status === 'configured'
