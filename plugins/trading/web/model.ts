/**
 * State of the trading screens for one host, kept while the host lives: coming back to the plugin shows what
 * was read at once and reads it again. Screens load when shown; events, followed only while the plugin is in
 * the foreground, are coalesced for 300 ms, reload the visible screen and mark the others stale. A failed
 * refresh keeps the previous value, marked stale; nothing is replaced by zeros.
 */
import { computed, ref, shallowRef, watch, type ComputedRef } from 'vue'
import type { z } from '@/core/zod'
import type {
  InvocationOutcome,
  PendingWrite,
  PluginError,
  PluginEvent,
  PluginHost,
} from '@/plugins/api'
import {
  ConfigurationView,
  CoreResult,
  ExecutionPage,
  FlowsPage,
  MarkResult,
  MethodResult,
  OrderPage,
  PortfolioHistory,
  PortfolioOverview,
  PositionChart,
  PositionSummary,
  PositionsList,
  StrategyResult,
  SyncStatus,
  TOOLS,
  configured,
  type Freshness,
  type LotMethod,
  type Treatment,
} from './contracts'
import { isStale } from './format'

export type ReadModel<T> =
  | { kind: 'loading' }
  | { kind: 'ready'; value: T; empty: boolean; stale: boolean; problem: PluginError | null }
  /** No Bybit key is connected for this user. */
  | { kind: 'not_connected' }
  | { kind: 'denied'; error: PluginError }
  | { kind: 'unavailable'; error: PluginError }

export const LOADING = { kind: 'loading' } as const

/** Reads kept per selection (a period, a coin, a unit), so going back to one shows it at once. */
const KEPT = 24
function remember<T>(kept: Map<string, T>, key: string, value: T): void {
  kept.delete(key)
  kept.set(key, value)
  if (kept.size > KEPT) kept.delete(kept.keys().next().value!)
}

export function valueOf<T>(model: ReadModel<T>): T | null {
  return model.kind === 'ready' ? model.value : null
}

export function pluginError(code: string, message = code): PluginError {
  return { code, message, recovery: 'none', fieldErrors: [], retryAfterMs: null }
}
const NO_DATA = pluginError('invalid_output', 'Сервис вернул данные в неизвестном формате')

export function denied(error: PluginError, status: number | null): boolean {
  return status === 403 || error.code === 'forbidden' || error.code === 'tool_unavailable'
}

function keep<T>(previous: ReadModel<T>, error: PluginError): ReadModel<T> {
  return previous.kind === 'ready'
    ? { ...previous, stale: true, problem: error }
    : { kind: 'unavailable', error }
}

/** One read through the host, checked against this module's contract. Reads may repeat. */
export async function read<T>(
  host: PluginHost,
  tool: string,
  args: Record<string, unknown>,
  schema: z.ZodType<T>,
  previous: ReadModel<T>,
  now: number,
  empty: (value: T) => boolean = () => false,
  freshness: (value: T) => Freshness | null = () => null,
): Promise<ReadModel<T>> {
  const outcome = await host.invoke(tool, args)
  switch (outcome.kind) {
    case 'completed': {
      const parsed = schema.safeParse(outcome.data)
      if (!parsed.success) return keep(previous, NO_DATA)
      const known = freshness(parsed.data)
      return {
        kind: 'ready',
        value: parsed.data,
        empty: empty(parsed.data),
        stale: known ? isStale(known, now) : false,
        problem: null,
      }
    }
    case 'failed':
      return outcome.error.code === 'not_configured'
        ? { kind: 'not_connected' }
        : keep(previous, outcome.error)
    case 'rejected':
      return denied(outcome.error, outcome.status)
        ? { kind: 'denied', error: outcome.error }
        : keep(previous, outcome.error)
    case 'unknown':
      return keep(previous, outcome.error ?? pluginError('network_unavailable'))
    case 'pending':
      return keep(previous, pluginError('unexpected_state'))
  }
}

type Clock = () => number

/** A screen's data: loads on demand and after invalidation; a superseded load is discarded. */
abstract class Model {
  /** The data may be out of date; reload the next time the screen is shown. */
  dirty = true
  /** A refresh is running. */
  readonly busy = ref(false)
  private generation = 0
  /** Something changed while a refresh ran: read once more after it. */
  private again = false

  constructor(
    protected readonly host: PluginHost,
    protected readonly clock: Clock,
  ) {}

  invalidate(): void {
    this.dirty = true
  }

  refresh(): Promise<void> {
    this.dirty = false
    this.again = false
    const ticket = ++this.generation
    this.busy.value = true
    return this.load(() => ticket === this.generation).finally(() => {
      if (ticket !== this.generation) return
      this.busy.value = false
      if (this.again) void this.refresh()
    })
  }

  /**
   * Reads again after a change. A refresh already running still shows what it reads, and one more follows it:
   * changes that come faster than a read takes (a long sync) never keep the screen from updating.
   */
  update(): void {
    if (this.busy.value) this.again = true
    else void this.refresh()
  }

  /** How fresh the screen's main data is, once read. */
  freshness(): Freshness | null {
    return null
  }

  protected abstract load(current: () => boolean): Promise<void>
}

export const PERIODS = [
  { key: 'day', wire: '24h', step: '1h', label: 'period_day' },
  { key: 'week', wire: '7d', step: '1h', label: 'period_week' },
  { key: 'month', wire: '30d', step: '1d', label: 'period_month' },
  { key: 'quarter', wire: '90d', step: '1d', label: 'period_quarter' },
  { key: 'year', wire: '1y', step: '1d', label: 'period_year' },
  { key: 'all', wire: 'all', step: '1w', label: 'period_all' },
] as const
export type Period = (typeof PERIODS)[number]
const MONTH = PERIODS[2]

export class PortfolioModel extends Model {
  readonly overview = shallowRef<ReadModel<PortfolioOverview>>(LOADING)
  readonly history = shallowRef<ReadModel<PortfolioHistory>>(LOADING)
  /** The trading result in the owner's unit and method, for its card. */
  readonly result = shallowRef<ReadModel<PositionsList>>(LOADING)
  readonly period = shallowRef<Period>(MONTH)
  private readonly histories = new Map<string, ReadModel<PortfolioHistory>>()
  private historyTicket = 0

  freshness(): Freshness | null {
    return valueOf(this.overview.value)?.freshness ?? null
  }

  protected async load(current: () => boolean): Promise<void> {
    await Promise.all([
      (async () => {
        const next = await read(
          this.host,
          TOOLS.portfolio,
          {},
          PortfolioOverview,
          this.overview.value,
          this.clock(),
          (value) => value.balances.length === 0,
          (value) => value.freshness,
        )
        if (current()) this.overview.value = next
      })(),
      this.loadHistory(),
      (async () => {
        const next = await read(
          this.host,
          TOOLS.positions,
          {},
          PositionsList,
          this.result.value,
          this.clock(),
          () => false,
          (value) => value.freshness,
        )
        if (current()) this.result.value = next
      })(),
    ])
  }

  select(period: Period): void {
    if (period === this.period.value) return
    this.period.value = period
    this.history.value = this.histories.get(period.key) ?? LOADING
    void this.loadHistory()
  }

  /** The history of the chosen period; only the latest read of it is shown. */
  private async loadHistory(): Promise<void> {
    const period = this.period.value
    const ticket = ++this.historyTicket
    const next = await read(
      this.host,
      TOOLS.history,
      { range: { period: period.wire }, step: period.step },
      PortfolioHistory,
      this.history.value,
      this.clock(),
      (value) => value.points.length === 0,
      (value) => value.freshness,
    )
    if (ticket !== this.historyTicket) return
    this.history.value = next
    remember(this.histories, period.key, next)
  }
}

interface PageShape {
  items: unknown[]
  next_cursor?: string | null
  freshness: Freshness
}

/**
 * A cursor-paged list: `more` appends the next page; a refresh reloads as many rows as were shown,
 * so an update event does not cut a long list back to its first page. `args` are the list's
 * filters; a cursor is only valid with the filters it was issued for.
 */
export class Paged<P extends PageShape> extends Model {
  readonly page = shallowRef<ReadModel<P>>(LOADING)
  readonly loadingMore = ref(false)
  readonly moreError = shallowRef<PluginError | null>(null)

  constructor(
    host: PluginHost,
    clock: Clock,
    private readonly tool: string,
    private readonly schema: z.ZodType<P>,
    private readonly append: (first: P, next: P) => P = (first, next) =>
      ({ ...next, items: [...first.items, ...next.items] }) as P,
    private readonly args: () => Record<string, unknown> = () => ({}),
  ) {
    super(host, clock)
  }

  freshness(): Freshness | null {
    return valueOf(this.page.value)?.freshness ?? null
  }

  protected async load(current: () => boolean): Promise<void> {
    const args = this.args()
    // As many rows as are shown, counted as the read goes: a page «more» adds meanwhile is read too.
    const wanted = () =>
      Math.min(
        Math.max(PAGE, this.page.value.kind === 'ready' ? this.page.value.value.items.length : 0),
        MAX_ROWS,
      )
    let asked = Math.min(wanted(), MAX_PAGE)
    let next = await read(
      this.host,
      this.tool,
      { ...args, limit: asked },
      this.schema,
      this.page.value,
      this.clock(),
      (value) => value.items.length === 0,
      (value) => value.freshness,
    )
    // More than one page was shown: keep reading full pages until as many rows are back.
    let got = next.kind === 'ready' ? next.value.items.length : 0
    while (
      current() &&
      next.kind === 'ready' &&
      !next.problem &&
      next.value.next_cursor &&
      got === asked &&
      next.value.items.length < wanted()
    ) {
      asked = Math.min(wanted() - next.value.items.length, MAX_PAGE)
      const rest = await read(
        this.host,
        this.tool,
        { ...args, limit: asked, cursor: next.value.next_cursor },
        this.schema,
        LOADING,
        this.clock(),
      )
      if (rest.kind !== 'ready') break // the rows read so far are current; `more` continues from them
      got = rest.value.items.length
      next = { ...next, value: this.append(next.value, rest.value) }
    }
    if (!current()) return
    this.page.value = next
    this.moreError.value = null
  }

  async more(): Promise<void> {
    const loaded = this.page.value
    const cursor = loaded.kind === 'ready' ? loaded.value.next_cursor : null
    if (!cursor || this.loadingMore.value) return
    this.loadingMore.value = true
    this.moreError.value = null
    const next = await read(
      this.host,
      this.tool,
      { ...this.args(), limit: PAGE, cursor },
      this.schema,
      LOADING,
      this.clock(),
    )
    const base = this.page.value
    this.loadingMore.value = false
    // A refresh meanwhile replaced the list: this page belongs to the old one.
    if (base !== loaded || base.kind !== 'ready' || base.value.next_cursor !== cursor) return
    if (next.kind === 'ready')
      this.page.value = { ...base, value: this.append(base.value, next.value), empty: false }
    else if (next.kind === 'unavailable' || next.kind === 'denied')
      this.moreError.value = next.error
  }
}

/** Filters of the money-movements screen, as `flows.list` arguments. */
export const FLOW_FILTERS = [
  { key: 'all', label: 'flows_all', args: {} },
  { key: 'outside', label: 'flows_outside', args: { internal: false } },
  { key: 'money', label: 'flows_money', args: { kinds: ['deposit', 'withdrawal', 'p2p'] } },
  { key: 'transfers', label: 'flows_transfers', args: { kinds: ['transfer', 'bot'] } },
  { key: 'earn', label: 'flows_earn', args: { kinds: ['earn', 'reward', 'interest'] } },
] as const
export type FlowFilter = (typeof FLOW_FILTERS)[number]

/** Every movement of money outside trading, with one filter chosen at a time; the owner marks deposits and
 * withdrawals that went to or came from their own wallet. */
export class FlowsModel extends Model {
  readonly filter = shallowRef<FlowFilter>(FLOW_FILTERS[0])
  readonly list: Paged<FlowsPage>
  /** The id of the movement whose mark is being saved. */
  readonly saving = ref<string | null>(null)
  readonly markError = shallowRef<{ id: string; error: PluginError } | null>(null)
  readonly canMark: ComputedRef<boolean>

  constructor(host: PluginHost, clock: Clock) {
    super(host, clock)
    this.list = new Paged(host, clock, TOOLS.flows, FlowsPage, undefined, () => ({
      ...this.filter.value.args,
    }))
    this.canMark = computed(() => TOOLS.mark in host.capabilities.value.tools)
  }

  /**
   * Saves a mark. Setting a mark twice changes nothing, so a lost response is looked up once and the
   * list re-read shows what holds; the journal forgets every terminal outcome.
   */
  async mark(id: string, treatment: Treatment, unitCost: string | null = null): Promise<boolean> {
    if (!this.canMark.value || this.saving.value) return false
    this.saving.value = id
    this.markError.value = null
    try {
      const args: Record<string, unknown> = { id, treatment }
      if (treatment === 'external' && unitCost) args.unit_cost_usd = unitCost
      let outcome = await this.host.invoke(TOOLS.mark, args)
      if (outcome.kind === 'unknown' && outcome.clientKey)
        outcome = await this.host.lookup(outcome.clientKey)
      if ((outcome.kind === 'completed' || outcome.kind === 'failed') && outcome.clientKey)
        await this.host.acknowledge(outcome.clientKey)
      if (outcome.kind === 'completed' && MarkResult.safeParse(outcome.data).success) {
        await this.list.refresh()
        return true
      }
      const error =
        'error' in outcome && outcome.error ? outcome.error : pluginError('unexpected_state')
      this.markError.value = { id, error }
      return false
    } finally {
      this.saving.value = null
    }
  }

  freshness(): Freshness | null {
    return this.list.freshness()
  }

  protected load(): Promise<void> {
    return this.list.refresh()
  }

  select(filter: FlowFilter): void {
    if (filter === this.filter.value) return
    this.filter.value = filter
    this.list.page.value = LOADING
    void this.list.refresh()
  }
}

export class OrdersModel extends Model {
  readonly open: Paged<OrderPage>
  readonly history: Paged<OrderPage>

  constructor(host: PluginHost, clock: Clock) {
    super(host, clock)
    this.open = new Paged(host, clock, TOOLS.openOrders, OrderPage)
    this.history = new Paged(host, clock, TOOLS.orderHistory, OrderPage)
  }

  freshness(): Freshness | null {
    return this.open.freshness() ?? this.history.freshness()
  }

  protected async load(): Promise<void> {
    await Promise.all([this.open.refresh(), this.history.refresh()])
  }
}

/**
 * Positions per coin for a period and the details of one coin, counted in a unit: the owner's strategy coin
 * unless another one (or USD) is chosen here.
 */
export class PnlModel extends Model {
  readonly positions = shallowRef<ReadModel<PositionsList>>(LOADING)
  readonly period = shallowRef<Period>(MONTH)
  /** The chosen unit; null leaves it to the service (the strategy coin, else USD). */
  readonly unit = ref<string | null>(null)
  /** The chosen lot method; null leaves it to the service (the owner's, LIFO by default). */
  readonly method = ref<LotMethod | null>(null)
  readonly saving = ref(false)
  /** The last failed preference write (strategy coin, lot method or core). */
  readonly saveError = shallowRef<PluginError | null>(null)
  readonly canSetStrategy: ComputedRef<boolean>
  readonly canSetMethod: ComputedRef<boolean>
  readonly canSetCore: ComputedRef<boolean>
  /**
   * Called when the period, the unit, the method or a mark changes what a coin's page shows; `reset` when what
   * was read before no longer holds (the owner's defaults or a core changed).
   */
  changed: (reset: boolean) => void = () => undefined
  private readonly lists = new Map<string, ReadModel<PositionsList>>()

  constructor(host: PluginHost, clock: Clock) {
    super(host, clock)
    this.canSetStrategy = computed(() => TOOLS.strategy in host.capabilities.value.tools)
    this.canSetMethod = computed(() => TOOLS.method in host.capabilities.value.tools)
    this.canSetCore = computed(() => TOOLS.core in host.capabilities.value.tools)
  }

  freshness(): Freshness | null {
    return valueOf(this.positions.value)?.freshness ?? null
  }

  /** The unit the shown numbers are in. */
  shownUnit(): string {
    return valueOf(this.positions.value)?.unit ?? this.unit.value ?? 'USD'
  }

  /** The lot method the shown numbers follow. */
  shownMethod(): string {
    return valueOf(this.positions.value)?.lot_method ?? this.method.value ?? 'lifo'
  }

  /** Arguments of the position reads: the period, and the unit and method when chosen here. */
  args(extra: Record<string, unknown> = {}): Record<string, unknown> {
    const args: Record<string, unknown> = { range: { period: this.period.value.wire }, ...extra }
    if (this.unit.value) args.unit = this.unit.value
    if (this.method.value) args.method = this.method.value
    return args
  }

  /** What the list shows now: its period, and the unit and method chosen here. */
  private key(): string {
    return JSON.stringify(this.args())
  }

  /** Shows what was read for the period, unit and method now chosen, if anything, until it is read again. */
  private recall(): void {
    this.positions.value = this.lists.get(this.key()) ?? LOADING
  }

  /** Every coin, sold-out and stable ones too: the chart of results shows them all. */
  protected async load(current: () => boolean): Promise<void> {
    const key = this.key()
    const positions = await read(
      this.host,
      TOOLS.positions,
      this.args({ include_closed: true, include_stables: true }),
      PositionsList,
      this.positions.value,
      this.clock(),
      (value) => value.items.length === 0,
      (value) => value.freshness,
    )
    if (!current() || this.key() !== key) return
    this.positions.value = positions
    remember(this.lists, key, positions)
  }

  select(period: Period): void {
    if (period === this.period.value) return
    this.period.value = period
    this.recall()
    this.changed(false)
    void this.refresh()
  }

  /**
   * Counts everything in `unit` (USD or a coin) until changed again or the screen is left. Before the list was
   * read (a coin's page opened by a link) any choice counts: what the page shows may be another unit.
   */
  count(unit: string): void {
    if (this.positions.value.kind === 'ready' && unit === this.shownUnit()) return
    this.unit.value = unit
    this.recall()
    this.changed(false)
    void this.refresh()
  }

  /** Makes `coin` (null: USD) the unit results are counted in by default, here and in the chat. */
  async setStrategy(coin: string | null): Promise<void> {
    if (!this.canSetStrategy.value || this.saving.value) return
    if (await this.save(TOOLS.strategy, { coin }, StrategyResult)) {
      this.unit.value = null
      this.lists.clear()
      this.changed(true)
      await this.refresh()
    }
  }

  /**
   * Shows the book by `method` and, when the owner may, keeps it as theirs (here and in the chat);
   * a failed save leaves the numbers shown by `method` and the error on the screen.
   */
  async useMethod(method: LotMethod): Promise<void> {
    if (this.positions.value.kind === 'ready' && method === this.shownMethod()) return
    if (this.saving.value) return
    this.method.value = method
    this.recall()
    const saved =
      this.canSetMethod.value && (await this.save(TOOLS.method, { method }, MethodResult))
    if (saved) {
      this.method.value = null // the owner's method now: what was read by the old one no longer holds
      this.lists.clear()
    }
    this.changed(saved)
    await this.refresh()
  }

  /**
   * Marks `qty` of `coin` held on `since` as its core (null removes the mark); true when saved, after which the
   * positions are read again.
   */
  async setCore(coin: string, qty: string | null, since: string | null = null): Promise<boolean> {
    if (!this.canSetCore.value || this.saving.value) return false
    const saved = await this.save(TOOLS.core, { coin, qty, since }, CoreResult)
    if (saved) {
      this.lists.clear()
      this.changed(true)
      await this.refresh()
    }
    return saved
  }

  /** One preference write through the host's journal; false (and `saveError`) when it did not complete. */
  private async save(
    tool: string,
    args: Record<string, unknown>,
    schema: z.ZodType,
  ): Promise<boolean> {
    this.saving.value = true
    this.saveError.value = null
    try {
      let outcome = await this.host.invoke(tool, args)
      if (outcome.kind === 'unknown' && outcome.clientKey)
        outcome = await this.host.lookup(outcome.clientKey)
      if ((outcome.kind === 'completed' || outcome.kind === 'failed') && outcome.clientKey)
        await this.host.acknowledge(outcome.clientKey)
      if (outcome.kind === 'completed' && schema.safeParse(outcome.data).success) return true
      this.saveError.value =
        'error' in outcome && outcome.error ? outcome.error : pluginError('unexpected_state')
      return false
    } finally {
      this.saving.value = false
    }
  }
}

/** Ranges of a coin's price chart. */
export const CHART_RANGES = [
  { key: 'quarter', wire: '90d', label: 'period_quarter' },
  { key: 'year', wire: '1y', label: 'period_year' },
  { key: 'all', wire: 'all', label: 'period_all' },
] as const
export type ChartRange = (typeof CHART_RANGES)[number]

/**
 * One coin's page: its position in the unit, method and period chosen for the list, and its price chart. What
 * was read for a coin and choice is shown at once when they come back, while it is read again.
 */
export class CoinModel extends Model {
  readonly coin = ref<string | null>(null)
  readonly summary = shallowRef<ReadModel<PositionSummary>>(LOADING)
  readonly chart = shallowRef<ReadModel<PositionChart>>(LOADING)
  readonly range = shallowRef<ChartRange>(CHART_RANGES[1])
  private readonly summaries = new Map<string, ReadModel<PositionSummary>>()
  private readonly charts = new Map<string, ReadModel<PositionChart>>()
  private chartTicket = 0

  constructor(
    host: PluginHost,
    clock: Clock,
    readonly pnl: PnlModel,
  ) {
    super(host, clock)
  }

  freshness(): Freshness | null {
    return valueOf(this.summary.value)?.freshness ?? null
  }

  private summaryKey(): string {
    return JSON.stringify(this.pnl.args({ coin: this.coin.value }))
  }

  /** A chart does not depend on the list's period: it has its own range. */
  private chartKey(): string {
    const { unit = null, method = null } = this.pnl.args()
    return JSON.stringify([this.coin.value, unit, method, this.range.value.wire])
  }

  /** Shows what was read for the coin and choices now made, if anything, until it is read again. */
  recall(): void {
    this.summary.value = this.summaries.get(this.summaryKey()) ?? LOADING
    this.chart.value = this.charts.get(this.chartKey()) ?? LOADING
  }

  /** Forgets every read: the owner's defaults or a core changed what they show. */
  forget(): void {
    this.chartTicket += 1
    this.summaries.clear()
    this.charts.clear()
  }

  /** Shows `coin` next (read when the page is shown). */
  select(coin: string): void {
    if (coin === this.coin.value) return
    this.coin.value = coin
    this.recall()
    this.invalidate()
  }

  /** Shows the price over `range`. */
  show(range: ChartRange): void {
    if (range === this.range.value) return
    this.range.value = range
    this.chart.value = this.charts.get(this.chartKey()) ?? LOADING
    void this.loadChart(() => true)
  }

  /** In its own unit a coin's price is always one: there is no chart to read. */
  private chartless(): boolean {
    const unit = this.pnl.unit.value ?? valueOf(this.summary.value)?.unit
    return !!unit && unit === this.coin.value
  }

  protected async load(current: () => boolean): Promise<void> {
    const coin = this.coin.value
    if (!coin) return
    // Opened by a link: the list tells the owner's strategy coin to the page's count settings.
    if (this.pnl.positions.value.kind === 'loading' && !this.pnl.busy.value) void this.pnl.refresh()
    const key = this.summaryKey()
    const shown = this.summary.value
    await Promise.all([
      (async () => {
        const next = await read(
          this.host,
          TOOLS.position,
          this.pnl.args({ coin }),
          PositionSummary,
          shown.kind === 'ready' && shown.value.coin === coin ? shown : LOADING,
          this.clock(),
          () => false,
          (value) => value.freshness,
        )
        if (!current() || this.summaryKey() !== key) return
        this.summary.value = next
        remember(this.summaries, key, next)
      })(),
      this.chartless() ? Promise.resolve() : this.loadChart(current),
    ])
  }

  /** The chart of the coin, range, unit and method chosen; a read that comes back after they changed is dropped. */
  private async loadChart(current: () => boolean): Promise<void> {
    const coin = this.coin.value
    if (!coin) return
    const ticket = ++this.chartTicket
    const key = this.chartKey()
    const shown = this.chart.value
    const next = await read(
      this.host,
      TOOLS.chart,
      { ...this.pnl.args({ coin }), range: { period: this.range.value.wire } },
      PositionChart,
      shown.kind === 'ready' && shown.value.coin === coin ? shown : LOADING,
      this.clock(),
      (value) => value.points.length === 0,
      (value) => value.freshness,
    )
    if (ticket !== this.chartTicket || !current() || this.chartKey() !== key) return
    this.chart.value = next
    remember(this.charts, key, next)
  }
}

export type RefillNotice =
  | { kind: 'done' }
  | { kind: 'failed'; error: PluginError }
  /** Refused before admission: nothing happened; a new request is allowed. */
  | { kind: 'refused'; error: PluginError }

/**
 * Sync status and this UI's only write, `sync.refill`. The host journals it before sending; an
 * unknown outcome is resolved by lookups, and a resend of the same key is offered only after a
 * lookup found nothing.
 */
export class SyncModel extends Model {
  readonly status = shallowRef<ReadModel<SyncStatus>>(LOADING)
  /** A refill, check or resend is in flight (reads have `busy`). */
  readonly acting = ref(false)
  readonly notice = shallowRef<RefillNotice | null>(null)
  /** Journaled refills not yet acknowledged; while one exists no new refill starts. */
  readonly refills: ComputedRef<PendingWrite[]>
  readonly canRefill: ComputedRef<boolean>

  constructor(host: PluginHost, clock: Clock) {
    super(host, clock)
    this.refills = computed(() =>
      host.pendingWrites.value.filter((write) => write.tool === TOOLS.refill),
    )
    this.canRefill = computed(() => TOOLS.refill in host.capabilities.value.tools)
  }

  protected async load(current: () => boolean): Promise<void> {
    await this.loadStatus(current)
    await this.resolve()
  }

  private async loadStatus(current: () => boolean = () => true): Promise<void> {
    const next = await read(
      this.host,
      TOOLS.syncStatus,
      {},
      SyncStatus,
      this.status.value,
      this.clock(),
      (value) => value.domains.length === 0,
    )
    if (current()) this.status.value = next
  }

  refill(): Promise<void> {
    if (!this.canRefill.value || this.acting.value || this.refills.value.length)
      return Promise.resolve()
    return this.act(() => this.host.invoke(TOOLS.refill, {}))
  }

  /** «Проверить результат»: a lookup by the journaled key; never sends the write. */
  check(key: string): Promise<void> {
    return this.act(() => this.host.lookup(key))
  }

  /** «Повторить отправку»: the same key and bytes, only after a lookup found nothing. */
  resend(key: string): Promise<void> {
    const write = this.refills.value.find((item) => item.clientKey === key)
    if (!write || !write.notFound || write.admitted) return Promise.resolve()
    return this.act(() => this.host.retry(key))
  }

  dismiss(): void {
    this.notice.value = null
  }

  /** Looks up unfinished refills after events or when the screen opens; lookups only. */
  async resolve(): Promise<void> {
    let finished = false
    for (const write of this.refills.value)
      if (!this.acting.value)
        finished = (await this.settle(await this.host.lookup(write.clientKey))) || finished
    if (finished) await this.loadStatus()
  }

  private resolving: ReturnType<typeof setTimeout> | undefined

  /**
   * Looks up unfinished refills a little later, once for every event that comes meanwhile: a refill's history
   * walk sends events every second for minutes.
   */
  resolveSoon(): void {
    if (this.resolving !== undefined || !this.refills.value.length) return
    this.resolving = setTimeout(() => {
      this.resolving = undefined
      void this.resolve()
    }, RESOLVE_MS)
  }

  stop(): void {
    clearTimeout(this.resolving)
    this.resolving = undefined
  }

  private async act(call: () => Promise<InvocationOutcome>): Promise<void> {
    if (this.acting.value) return
    this.acting.value = true
    try {
      if (await this.settle(await call())) await this.loadStatus()
    } finally {
      this.acting.value = false
    }
  }

  /** Shows the outcome; a terminal one is acknowledged so the journal forgets it. */
  private async settle(outcome: InvocationOutcome): Promise<boolean> {
    if (outcome.kind === 'completed') this.notice.value = { kind: 'done' }
    else if (outcome.kind === 'failed') this.notice.value = { kind: 'failed', error: outcome.error }
    else if (outcome.kind === 'rejected')
      this.notice.value = { kind: 'refused', error: outcome.error }
    if (outcome.kind !== 'completed' && outcome.kind !== 'failed') return false
    if (outcome.clientKey) await this.host.acknowledge(outcome.clientKey)
    return true
  }
}

export type ConnectNotice =
  | { kind: 'connected' }
  | { kind: 'disconnected' }
  | { kind: 'failed'; error: PluginError }
  /** The response was lost; the view was re-read and its revision tells what happened. */
  | { kind: 'unknown' }

export interface ConnectRequest {
  apiKey: string
  apiSecret: string
  environment: string | null
  label: string | null
  historyDays: number | null
  replaceAccount: boolean
}

/**
 * The Bybit key connection (`assistant.configuration/v1`). Key and secret are parameters of
 * `connect` only, handed straight to the host: they never reach any state.
 */
export class ConnectionModel extends Model {
  readonly view = shallowRef<ReadModel<ConfigurationView>>(LOADING)
  /** A connect or disconnect is in flight (reads have `busy`). */
  readonly acting = ref(false)
  readonly notice = shallowRef<ConnectNotice | null>(null)
  readonly writable: ComputedRef<boolean>

  constructor(
    host: PluginHost,
    clock: Clock,
    private readonly changed: () => void,
  ) {
    super(host, clock)
    this.writable = computed(() => host.capabilities.value.configurationWritable === true)
  }

  protected async load(current: () => boolean = () => true): Promise<void> {
    if (this.host.capabilities.value.configurationWritable === null) {
      this.view.value = { kind: 'denied', error: pluginError('forbidden') }
      return
    }
    const next = viewOf(await this.host.configuration.get(), this.view.value)
    if (current()) this.view.value = next
  }

  connect(request: ConnectRequest): Promise<void> {
    const current = valueOf(this.view.value)
    if (!this.writable.value || this.acting.value || !current) return Promise.resolve()
    const settings: Record<string, unknown> = {}
    if (request.environment) settings.environment = request.environment
    if (request.label?.trim()) settings.label = request.label.trim()
    if (request.historyDays !== null) settings.history_days = request.historyDays
    const secrets = { api_key: request.apiKey, api_secret: request.apiSecret }
    const options = request.replaceAccount ? { replace_account: true } : null
    const revision = configured(current) ? current.revision : null
    return this.change({ kind: 'connected' }, () =>
      this.host.configuration.put(settings, secrets, revision, options),
    )
  }

  /** Disconnects; the service also deletes this user's synced data. */
  disconnect(): Promise<void> {
    const current = valueOf(this.view.value)
    if (!current || !configured(current) || !this.writable.value || this.acting.value)
      return Promise.resolve()
    return this.change({ kind: 'disconnected' }, () =>
      this.host.configuration.delete(current.revision, true),
    )
  }

  dismiss(): void {
    this.notice.value = null
  }

  private async change(success: ConnectNotice, call: () => Promise<InvocationOutcome>) {
    this.acting.value = true
    this.notice.value = null
    try {
      const outcome = await call()
      if (outcome.kind === 'completed') {
        this.view.value = viewOf(outcome, this.view.value)
        this.notice.value = success
      } else if (outcome.kind === 'failed' || outcome.kind === 'rejected') {
        this.notice.value = { kind: 'failed', error: outcome.error }
      } else this.notice.value = { kind: 'unknown' }
      if (outcome.kind !== 'completed') await this.load()
      this.changed()
    } finally {
      this.acting.value = false
    }
  }
}

function viewOf(
  outcome: InvocationOutcome,
  previous: ReadModel<ConfigurationView>,
): ReadModel<ConfigurationView> {
  switch (outcome.kind) {
    case 'completed': {
      const parsed = ConfigurationView.safeParse(outcome.data)
      return parsed.success
        ? { kind: 'ready', value: parsed.data, empty: false, stale: false, problem: null }
        : keep(previous, NO_DATA)
    }
    case 'rejected':
      return denied(outcome.error, outcome.status)
        ? { kind: 'denied', error: outcome.error }
        : keep(previous, outcome.error)
    case 'failed':
      return keep(previous, outcome.error)
    case 'unknown':
      return keep(previous, outcome.error ?? pluginError('network_unavailable'))
    case 'pending':
      return keep(previous, pluginError('unexpected_state'))
  }
}

export const SCREENS = [
  'portfolio',
  'orders',
  'trades',
  'pnl',
  'position',
  'cashflow',
  'sync',
  'connect',
] as const
export type Screen = (typeof SCREENS)[number]

/** The switcher's tabs; orders and trades share one, as do sync and connection (behind the settings icon). */
export const TABS = ['portfolio', 'pnl', 'orders', 'cashflow'] as const
export type Tab = (typeof TABS)[number] | 'settings'
export const TAB_OF: Record<Screen, Tab> = {
  portfolio: 'portfolio',
  pnl: 'pnl',
  position: 'pnl',
  orders: 'orders',
  trades: 'orders',
  cashflow: 'cashflow',
  sync: 'settings',
  connect: 'settings',
}

/** The route of a screen; the portfolio is the plugin's home. */
export const routeOf = (screen: Screen) => (screen === 'portfolio' ? 'home' : screen)
export const screenOf = (route: string): Screen =>
  (SCREENS as readonly string[]).includes(route) ? (route as Screen) : 'portfolio'

const DOMAINS: Record<string, Screen[]> = {
  wallet: ['portfolio', 'pnl', 'position'],
  executions: ['trades', 'pnl', 'position', 'orders', 'portfolio'],
  orders: ['orders'],
  open_orders: ['orders'],
  deposits: ['cashflow'],
  internal_deposits: ['cashflow'],
  withdrawals: ['cashflow'],
  // Money moved in or out changes the portfolio's split between market result and flows, and positions.
  unified_ledger: ['cashflow', 'portfolio', 'pnl', 'position'],
  funding_ledger: ['cashflow', 'portfolio', 'pnl', 'position'],
  funding_wallet: ['portfolio'],
  earn: ['portfolio'],
}

/** Screens an event makes stale. Events are signals to re-read, not a change log. */
export function affected(event: PluginEvent): Set<Screen> {
  switch (event.kind) {
    case 'ready':
    case 'resync':
      return new Set(SCREENS)
    case 'denied':
      return new Set()
    case 'delivered':
      if (event.type === 'trading.data.changed') {
        const domains = Array.isArray(event.payload.domains) ? event.payload.domains : []
        const screens = new Set<Screen>(['sync'])
        for (const domain of domains)
          for (const screen of (typeof domain === 'string' && DOMAINS[domain]) || SCREENS)
            screens.add(screen)
        return screens
      }
      if (event.type === 'trading.sync.progress') return new Set(['sync'])
      if (event.type === 'trading.connection.changed') return new Set(SCREENS)
      return new Set()
  }
}

export const PAGE = 50
/** The largest page the list tools serve, and how many rows a refresh reloads at most. */
export const MAX_PAGE = 200
export const MAX_ROWS = 1000
export const COALESCE_MS = 300
/** How long lookups of an unfinished refill wait for more events. */
export const RESOLVE_MS = 5000

/** Everything the trading screens show for one host. */
export class Trading {
  readonly portfolio: PortfolioModel
  readonly orders: OrdersModel
  readonly trades: Paged<ExecutionPage>
  readonly pnl: PnlModel
  readonly coin: CoinModel
  readonly cashflow: FlowsModel
  readonly sync: SyncModel
  readonly connection: ConnectionModel
  /** Whether events arrive; without them screens refresh when opened. */
  readonly live = ref(false)
  private current: Screen = 'portfolio'
  private stops: Array<() => void> = []
  private stopEvents: (() => void) | null = null
  private pending = new Set<Screen>()
  private timer: ReturnType<typeof setTimeout> | undefined

  constructor(
    private readonly host: PluginHost,
    clock: Clock = Date.now,
  ) {
    this.portfolio = new PortfolioModel(host, clock)
    this.orders = new OrdersModel(host, clock)
    this.trades = new Paged(host, clock, TOOLS.executions, ExecutionPage)
    this.pnl = new PnlModel(host, clock)
    this.coin = new CoinModel(host, clock, this.pnl)
    this.pnl.changed = (reset) => {
      if (reset) this.coin.forget()
      this.coin.recall()
      this.coin.invalidate()
      if (this.current === 'position') void this.coin.refresh()
    }
    this.cashflow = new FlowsModel(host, clock)
    this.sync = new SyncModel(host, clock)
    this.connection = new ConnectionModel(host, clock, () => {
      for (const screen of SCREENS) if (screen !== 'connect') this.model(screen).invalidate()
    })
  }

  start(): void {
    if (this.stops.length) return
    let followed = false
    this.stops.push(
      watch(
        this.host.lifecycle,
        (lifecycle) => {
          this.live.value = false
          this.stopEvents?.()
          this.stopEvents = null
          if (lifecycle !== 'foreground') {
            this.quiet()
            // Nothing is followed in the background: whatever was loaded may be old on return.
            if (followed) for (const screen of SCREENS) this.model(screen).invalidate()
            followed = false
            return
          }
          followed = true
          const model = this.model(this.current)
          if (model.dirty) void model.refresh()
          this.follow()
        },
        { immediate: true },
      ),
    )
  }

  show(screen: Screen): void {
    this.current = screen
    const model = this.model(screen)
    if (model.dirty) void model.refresh()
  }

  refresh(): void {
    void this.model(this.current).refresh()
  }

  /**
   * Stops following while the plugin is not shown. Its state is kept (see `tradingFor`): what was read shows at
   * once when it opens again, and is read again, since nothing was followed meanwhile.
   */
  close(): void {
    for (const stop of this.stops) stop()
    this.stops = []
    this.stopEvents?.()
    this.stopEvents = null
    this.live.value = false
    this.quiet()
    for (const screen of SCREENS) this.model(screen).invalidate()
  }

  /** Drops events waiting to be applied and the lookups they asked for. */
  private quiet(): void {
    clearTimeout(this.timer)
    this.timer = undefined
    this.pending = new Set()
    this.sync.stop()
  }

  model(screen: Screen): Model {
    return {
      portfolio: this.portfolio,
      orders: this.orders,
      trades: this.trades,
      pnl: this.pnl,
      position: this.coin,
      cashflow: this.cashflow,
      sync: this.sync,
      connect: this.connection,
    }[screen]
  }

  private follow(): void {
    if (!this.host.capabilities.value.events.length) return
    this.stopEvents = this.host.observe((event) => {
      this.live.value = event.kind !== 'denied'
      for (const screen of affected(event)) this.pending.add(screen)
      if (this.timer === undefined)
        this.timer = setTimeout(() => {
          this.timer = undefined
          const screens = this.pending
          this.pending = new Set()
          this.invalidate(screens)
        }, COALESCE_MS)
    })
  }

  private invalidate(screens: Set<Screen>): void {
    for (const screen of screens) {
      const model = this.model(screen)
      model.invalidate()
      if (screen === this.current) model.update()
    }
    if (screens.has('sync') && this.current !== 'sync') this.sync.resolveSoon()
  }
}

const kept = new WeakMap<PluginHost, Trading>()

/** The trading state of a host, kept as long as the host (one account's session). */
export function tradingFor(host: PluginHost): Trading {
  let found = kept.get(host)
  if (!found) kept.set(host, (found = new Trading(host)))
  return found
}

/** Portfolio history as chart points (epoch ms, value); unreadable points are skipped. */
export function samples(
  history: PortfolioHistory,
): Array<{ x: number; y: number; flow: number | null; rebuilt: boolean }> {
  return history.points
    .map((point) => ({
      x: Date.parse(point.time),
      y: Number(point.total_usd),
      flow: point.flow_usd == null ? null : Number(point.flow_usd),
      rebuilt: point.rebuilt,
    }))
    .filter((point) => Number.isFinite(point.x) && Number.isFinite(point.y))
    .sort((a, b) => a.x - b.x)
}
