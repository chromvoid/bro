package assistant.mobile.trading

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Tool pages preserve service decimals and future codes without guessing their meaning. */
@Serializable
data class TradingOrder(
    @SerialName("order_id") val orderId: String,
    val symbol: String,
    @SerialName("base_coin") val baseCoin: String? = null,
    @SerialName("quote_coin") val quoteCoin: String? = null,
    val side: String,
    val type: String,
    val status: String,
    val price: String? = null,
    val qty: String,
    @SerialName("qty_unit") val qtyUnit: String? = null,
    @SerialName("filled_qty") val filledQty: String,
    @SerialName("filled_value") val filledValue: String? = null,
    @SerialName("avg_price") val avgPrice: String? = null,
    @SerialName("created_at") val createdAt: String,
    @SerialName("updated_at") val updatedAt: String,
)

@Serializable
data class TradingOrderPage(
    val items: List<TradingOrder> = emptyList(),
    @SerialName("next_cursor") val nextCursor: String? = null,
    val freshness: Freshness,
)

@Serializable
data class TradingExecution(
    @SerialName("exec_id") val execId: String,
    @SerialName("order_id") val orderId: String,
    val symbol: String,
    @SerialName("base_coin") val baseCoin: String? = null,
    @SerialName("quote_coin") val quoteCoin: String? = null,
    val side: String,
    val price: String,
    val qty: String,
    val value: String,
    val fee: String,
    @SerialName("fee_coin") val feeCoin: String? = null,
    @SerialName("is_maker") val isMaker: Boolean = false,
    @SerialName("exec_time") val execTime: String,
)

@Serializable
data class TradingExecutionPage(
    val items: List<TradingExecution> = emptyList(),
    @SerialName("next_cursor") val nextCursor: String? = null,
    val freshness: Freshness,
)

@Serializable
data class TradingCashflowItem(
    val id: String,
    val kind: String,
    val coin: String,
    val amount: String,
    val status: String,
    @SerialName("occurred_at") val occurredAt: String,
    val chain: String? = null,
    @SerialName("usd_value_at_event") val usdValueAtEvent: String? = null,
)

@Serializable
data class CashflowCoinTotals(val coin: String, val deposited: String, val withdrawn: String, val net: String)

@Serializable
data class CashflowUsdTotals(val deposited: String, val withdrawn: String, @SerialName("unpriced_count") val unpricedCount: Int = 0)

@Serializable
data class TradingCashflowTotals(@SerialName("by_coin") val byCoin: List<CashflowCoinTotals> = emptyList(), val usd: CashflowUsdTotals? = null)

@Serializable
data class TradingCashflowPage(
    val items: List<TradingCashflowItem> = emptyList(),
    val totals: TradingCashflowTotals = TradingCashflowTotals(),
    @SerialName("next_cursor") val nextCursor: String? = null,
    val freshness: Freshness,
)

@Serializable
data class TradingFlow(
    val id: String,
    val account: String,
    val kind: String,
    val operation: String,
    val direction: String,
    val coin: String,
    val amount: String,
    val counterparty: String? = null,
    val external: Boolean = true,
    val note: String? = null,
    @SerialName("occurred_at") val occurredAt: String,
    @SerialName("usd_value_at_event") val usdValueAtEvent: String? = null,
    val markable: Boolean = false,
    val treatment: String = "auto",
    @SerialName("unit_cost_usd") val unitCostUsd: String? = null,
)

@Serializable
data class FlowCoinTotals(val coin: String, @SerialName("in") val incoming: String, @SerialName("out") val outgoing: String, val net: String)

@Serializable
data class FlowKindTotals(val kind: String, val count: Int = 0, @SerialName("usd_in") val usdIn: String, @SerialName("usd_out") val usdOut: String)

@Serializable
data class FlowUsdTotals(@SerialName("in") val incoming: String, @SerialName("out") val outgoing: String, val net: String, @SerialName("unpriced_count") val unpricedCount: Int = 0)

@Serializable
data class FlowMonth(val month: String, @SerialName("usd_in") val usdIn: String, @SerialName("usd_out") val usdOut: String)

@Serializable
data class TradingFlowTotals(
    @SerialName("by_coin") val byCoin: List<FlowCoinTotals> = emptyList(),
    @SerialName("by_kind") val byKind: List<FlowKindTotals> = emptyList(),
    val usd: FlowUsdTotals? = null,
    @SerialName("by_month") val byMonth: List<FlowMonth> = emptyList(),
)

@Serializable
data class TradingFlowsPage(
    val items: List<TradingFlow> = emptyList(),
    val totals: TradingFlowTotals = TradingFlowTotals(),
    @SerialName("next_cursor") val nextCursor: String? = null,
    val freshness: Freshness,
)

@Serializable
data class FlowMarkResult(val id: String, val treatment: String, @SerialName("unit_cost_usd") val unitCostUsd: String? = null)

@Serializable
data class TradingSymbol(val symbol: String, @SerialName("base_coin") val baseCoin: String, @SerialName("quote_coin") val quoteCoin: String)

@Serializable
data class TradingSymbols(val items: List<TradingSymbol> = emptyList(), val freshness: Freshness)

@Serializable
data class SyncConnection(
    val state: String,
    val environment: String = "",
    @SerialName("api_key_hint") val apiKeyHint: String? = null,
    @SerialName("key_expires_at") val keyExpiresAt: String? = null,
)

@Serializable
data class SyncStream(val state: String)

@Serializable
data class SyncDomain(
    val domain: String,
    val phase: String,
    @SerialName("watermark_at") val watermarkAt: String? = null,
    @SerialName("progress_pct") val progressPct: String? = null,
    @SerialName("last_error_code") val lastErrorCode: String? = null,
    @SerialName("coverage_gap_before") val coverageGapBefore: String? = null,
)

@Serializable
data class TradingSyncStatus(val connection: SyncConnection? = null, val stream: SyncStream, val domains: List<SyncDomain> = emptyList())

@Serializable
data class RefillDomain(val domain: String, @SerialName("watermark_at") val watermarkAt: String? = null)

@Serializable
data class TradingRefillResult(val domains: List<RefillDomain> = emptyList())
