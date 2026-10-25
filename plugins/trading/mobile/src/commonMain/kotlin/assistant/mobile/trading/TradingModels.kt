package assistant.mobile.trading

import assistant.mobile.pluginapi.PluginError
import assistant.mobile.pluginapi.PendingPluginWrite
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Service decimals and future enum codes stay strings. Null means unknown, never zero. */
@Serializable
data class Freshness(
    @SerialName("as_of") val asOf: String? = null,
    @SerialName("connection_state") val connectionState: String = "",
    val stream: String = "",
    @SerialName("last_reconcile_at") val lastReconcileAt: String? = null,
    @SerialName("history_complete") val historyComplete: Boolean = false,
    @SerialName("coverage_from") val coverageFrom: String? = null,
)

@Serializable
data class PortfolioChange(
    @SerialName("usd_delta") val usdDelta: String,
    val pct: String? = null,
    @SerialName("net_flow_usd") val netFlowUsd: String? = null,
    @SerialName("market_usd") val marketUsd: String? = null,
    @SerialName("market_pct") val marketPct: String? = null,
)

@Serializable
data class PortfolioChanges(val day: PortfolioChange? = null, val week: PortfolioChange? = null, val month: PortfolioChange? = null)

@Serializable
data class BalanceDay(
    @SerialName("quantity_delta") val quantityDelta: String? = null,
    @SerialName("usd_delta") val usdDelta: String? = null,
    val pct: String? = null,
)

@Serializable
data class BalanceAccounts(val unified: String? = null, val funding: String? = null, val earn: String? = null)

@Serializable
data class Balance(
    val coin: String,
    val quantity: String,
    @SerialName("usd_value") val usdValue: String? = null,
    val accounts: BalanceAccounts? = null,
    @SerialName("price_usd") val priceUsd: String? = null,
    @SerialName("share_pct") val sharePct: String? = null,
    val day: BalanceDay = BalanceDay(),
)

@Serializable
data class EarnPosition(
    val coin: String,
    val category: String,
    @SerialName("product_id") val productId: String,
    val amount: String,
    @SerialName("yield_pending") val yieldPending: String? = null,
    val pnl: String? = null,
)

@Serializable
data class PortfolioOverview(
    @SerialName("total_usd") val totalUsd: String? = null,
    val change: PortfolioChanges = PortfolioChanges(),
    @SerialName("by_account") val byAccount: Map<String, String> = emptyMap(),
    val balances: List<Balance> = emptyList(),
    val earn: List<EarnPosition> = emptyList(),
    val freshness: Freshness,
)

@Serializable
data class HistoryPoint(
    val time: String,
    @SerialName("total_usd") val totalUsd: String,
    @SerialName("flow_usd") val flowUsd: String? = null,
    val rebuilt: Boolean = false,
)

@Serializable
data class PortfolioHistory(
    val points: List<HistoryPoint> = emptyList(),
    @SerialName("earliest_available") val earliestAvailable: String? = null,
    val freshness: Freshness,
)

@Serializable
data class CorePosition(
    val qty: String?,
    val asked: String,
    val since: String,
    val entry: String? = null,
    val unrealized: String? = null,
    @SerialName("sold_qty") val soldQty: String?,
    @SerialName("sold_price") val soldPrice: String? = null,
    @SerialName("sold_unrealized") val soldUnrealized: String? = null,
)

@Serializable
data class PositionIssue(
    val code: String,
    val coin: String,
    @SerialName("occurred_at") val occurredAt: String,
    @SerialName("entry_id") val entryId: String?,
)

@Serializable
data class PositionItem(
    val coin: String,
    @SerialName("open_qty") val openQty: String,
    val value: String? = null,
    @SerialName("held_qty") val heldQty: String? = null,
    @SerialName("unexplained_qty") val unexplainedQty: String? = null,
    @SerialName("traded_against") val tradedAgainst: String? = null,
    val closed: Boolean = false,
    val result: String? = null,
    val core: CorePosition? = null,
    @SerialName("avg_entry") val avgEntry: String? = null,
    @SerialName("break_even") val breakEven: String? = null,
    @SerialName("cycle_started_at") val cycleStartedAt: String? = null,
    @SerialName("realized_in_cycle") val realizedInCycle: String? = null,
    val mark: String? = null,
    val unrealized: String? = null,
    @SerialName("unrealized_pct") val unrealizedPct: String? = null,
    val realized: String?,
    @SerialName("realized_in_range") val realizedInRange: String?,
    val income: String?,
    @SerialName("income_in_range") val incomeInRange: String?,
    val fees: String?,
    @SerialName("avg_buy") val avgBuy: String? = null,
    val issues: List<PositionIssue>,
)

@Serializable
data class RealizedMonth(val month: String, val realized: String?)

@Serializable
data class PositionTotals(
    val realized: String?,
    @SerialName("realized_in_range") val realizedInRange: String?,
    val unrealized: String?,
    val income: String?,
    @SerialName("income_in_range") val incomeInRange: String?,
    val fees: String?,
    val value: String?,
    val result: String?,
    @SerialName("realized_by_month") val realizedByMonth: List<RealizedMonth> = emptyList(),
    val issues: List<PositionIssue>,
)

@Serializable
data class PositionsList(
    val unit: String,
    @SerialName("strategy_coin") val strategyCoin: String? = null,
    @SerialName("lot_method") val lotMethod: String = "lifo",
    @SerialName("as_of") val asOf: String? = null,
    val items: List<PositionItem> = emptyList(),
    val totals: PositionTotals,
    val freshness: Freshness,
)

@Serializable
data class PositionOpening(val qty: String, val at: String)

@Serializable
data class PositionLot(val qty: String, @SerialName("unit_cost") val unitCost: String? = null, val core: Boolean = false)

/** The summary's position fields are decoded from the same object as its extra detail. */
data class PositionSummary(
    val position: PositionItem,
    val unit: String,
    val lotMethod: String = "lifo",
    val asOf: String? = null,
    val openCost: String? = null,
    val opening: PositionOpening? = null,
    val awayQty: String = "0",
    val shortQty: String = "0",
    val unpricedEvents: Int = 0,
    val lots: List<PositionLot> = emptyList(),
    val lotsTotal: Int = 0,
    val realizedByMonth: List<RealizedMonth> = emptyList(),
    val freshness: Freshness,
)

@Serializable
internal data class PositionDetail(
    val unit: String,
    @SerialName("lot_method") val lotMethod: String = "lifo",
    @SerialName("as_of") val asOf: String? = null,
    @SerialName("open_cost") val openCost: String? = null,
    val opening: PositionOpening? = null,
    @SerialName("away_qty") val awayQty: String = "0",
    @SerialName("short_qty") val shortQty: String = "0",
    @SerialName("unpriced_events") val unpricedEvents: Int = 0,
    val lots: List<PositionLot> = emptyList(),
    @SerialName("lots_total") val lotsTotal: Int = 0,
    @SerialName("realized_by_month") val realizedByMonth: List<RealizedMonth> = emptyList(),
    val freshness: Freshness,
)

@Serializable
data class PricePoint(val time: String, val price: String)

@Serializable
data class ChartTrade(val time: String, val side: String, val qty: String, val fills: Int = 1, val price: String)

@Serializable
data class PositionLines(
    @SerialName("avg_entry") val avgEntry: String? = null,
    @SerialName("break_even") val breakEven: String? = null,
    @SerialName("core_entry") val coreEntry: String? = null,
    val mark: String? = null,
)

@Serializable
data class PositionChart(
    val coin: String,
    val unit: String,
    val from: String,
    val points: List<PricePoint> = emptyList(),
    val trades: List<ChartTrade> = emptyList(),
    val lines: PositionLines,
    val issues: List<PositionIssue> = emptyList(),
    val freshness: Freshness,
)

@Serializable
data class StrategyResult(@SerialName("strategy_coin") val strategyCoin: String? = null)

@Serializable
data class MethodResult(@SerialName("lot_method") val lotMethod: String)

@Serializable
data class CoreMark(val qty: String, val since: String)

@Serializable
data class CoreResult(val coin: String, val core: CoreMark?)

@Serializable
data class ConfigurationSettings(val environment: String? = null, val label: String? = null, @SerialName("history_days") val historyDays: Int? = null)

@Serializable
data class SecretPresence(val present: Boolean = false, val hint: String? = null)

@Serializable
data class ReadinessIssue(val code: String, val recovery: String? = null)

@Serializable
data class ConfigurationReadiness(val state: String = "", val issues: List<ReadinessIssue> = emptyList())

@Serializable
data class ConnectionKeyInfo(
    @SerialName("read_only") val readOnly: Boolean? = null,
    @SerialName("ip_bound") val ipBound: Boolean? = null,
    @SerialName("expires_at") val expiresAt: String? = null,
)

@Serializable
data class ConfigurationView(
    val status: String,
    val revision: Long = 0,
    @SerialName("public") val settings: ConfigurationSettings = ConfigurationSettings(),
    @SerialName("secret_state") val secretState: Map<String, SecretPresence> = emptyMap(),
    val readiness: ConfigurationReadiness = ConfigurationReadiness(),
    @SerialName("connection_state") val connectionState: String? = null,
    @SerialName("key_info") val keyInfo: ConnectionKeyInfo? = null,
    val environments: List<String> = emptyList(),
    @SerialName("egress_ips") val egressIps: List<String> = emptyList(),
) {
    val configured: Boolean get() = status == "configured"
}

sealed interface TradingRead<out T> {
    data object Loading : TradingRead<Nothing>
    data class Ready<T>(val value: T, val empty: Boolean = false, val stale: Boolean = false, val problem: PluginError? = null) : TradingRead<T>
    data object NotConnected : TradingRead<Nothing>
    data class Denied(val error: PluginError) : TradingRead<Nothing>
    data class Unavailable(val error: PluginError) : TradingRead<Nothing>
}

fun <T> TradingRead<T>.valueOrNull(): T? = (this as? TradingRead.Ready<T>)?.value

enum class TradingPeriod(val key: String, val wire: String, val step: String, val label: String) {
    Day("day", "24h", "1h", "period_day"),
    Week("week", "7d", "1h", "period_week"),
    Month("month", "30d", "1d", "period_month"),
    Quarter("quarter", "90d", "1d", "period_quarter"),
    Year("year", "1y", "1d", "period_year"),
    All("all", "all", "1w", "period_all"),
}

enum class TradingChartRange(val key: String, val wire: String, val label: String) {
    Quarter("quarter", "90d", "period_quarter"), Year("year", "1y", "period_year"), All("all", "all", "period_all"),
}

/** Transient method arguments only: no data-class toString, serialization, journal or saved state. */
class TradingConnectRequest(
    val apiKey: String,
    val apiSecret: String,
    val environment: String? = null,
    val label: String? = null,
    val historyDays: Int? = null,
    val replaceAccount: Boolean = false,
)

sealed interface ConnectNotice {
    data object Connected : ConnectNotice
    data object Disconnected : ConnectNotice
    data class Failed(val error: PluginError) : ConnectNotice
    data object Unknown : ConnectNotice
}

data class FlowMarkError(val id: String, val error: PluginError)

sealed interface RefillNotice {
    data object Done : RefillNotice
    data class Failed(val error: PluginError) : RefillNotice
    data class Refused(val error: PluginError) : RefillNotice
}

data class TradingState(
    val screen: String = "portfolio",
    val coin: String? = null,
    val period: TradingPeriod = TradingPeriod.Month,
    val chartRange: TradingChartRange = TradingChartRange.Year,
    val unit: String? = null,
    val method: String? = null,
    val overview: TradingRead<PortfolioOverview> = TradingRead.Loading,
    val history: TradingRead<PortfolioHistory> = TradingRead.Loading,
    val result: TradingRead<PositionsList> = TradingRead.Loading,
    val positions: TradingRead<PositionsList> = TradingRead.Loading,
    val summary: TradingRead<PositionSummary> = TradingRead.Loading,
    val chart: TradingRead<PositionChart> = TradingRead.Loading,
    val configuration: TradingRead<ConfigurationView> = TradingRead.Loading,
    val openOrders: TradingRead<TradingOrderPage> = TradingRead.Loading,
    val orderHistory: TradingRead<TradingOrderPage> = TradingRead.Loading,
    val trades: TradingRead<TradingExecutionPage> = TradingRead.Loading,
    val cashflow: TradingRead<TradingCashflowPage> = TradingRead.Loading,
    val flows: TradingRead<TradingFlowsPage> = TradingRead.Loading,
    val symbols: TradingRead<TradingSymbols> = TradingRead.Loading,
    val sync: TradingRead<TradingSyncStatus> = TradingRead.Loading,
    val flowFilter: String = "all",
    val loadingMore: Boolean = false,
    val moreError: PluginError? = null,
    val savingFlow: String? = null,
    val flowMarkError: FlowMarkError? = null,
    val pendingWrites: List<PendingPluginWrite> = emptyList(),
    val refills: List<PendingPluginWrite> = emptyList(),
    val refillNotice: RefillNotice? = null,
    val busy: Boolean = false,
    val acting: Boolean = false,
    val live: Boolean = false,
    val eventsAvailable: Boolean = false,
    val configurationWritable: Boolean = false,
    val canSetStrategy: Boolean = false,
    val canSetMethod: Boolean = false,
    val canSetCore: Boolean = false,
    val canMarkFlow: Boolean = false,
    val canRefill: Boolean = false,
    val saveError: PluginError? = null,
    val notice: ConnectNotice? = null,
) {
    val shownUnit: String get() = positions.valueOrNull()?.unit ?: unit ?: summary.valueOrNull()?.unit ?: "USD"
    val shownMethod: String get() = positions.valueOrNull()?.lotMethod ?: method ?: summary.valueOrNull()?.lotMethod ?: "lifo"
}
