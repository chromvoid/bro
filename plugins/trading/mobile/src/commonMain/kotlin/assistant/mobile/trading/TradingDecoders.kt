package assistant.mobile.trading

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.decodeFromJsonElement

/** Required reads for all native screens; writes remain optional capabilities. */
val TRADING_REQUIRED_TOOLS: Map<String, Int> = mapOf(
    "portfolio.get" to 1,
    "portfolio.history" to 1,
    "positions.list" to 4,
    "position.summary" to 5,
    "position.chart" to 1,
    "orders.open" to 1,
    "orders.history" to 1,
    "executions.list" to 1,
    "cashflow.list" to 1,
    "flows.list" to 1,
    "symbols.list" to 1,
    "sync.status" to 1,
)

fun decodeTradingOrderPage(data: JsonElement): TradingOrderPage = TradingJson.decodeFromJsonElement(data)
fun decodeTradingExecutionPage(data: JsonElement): TradingExecutionPage = TradingJson.decodeFromJsonElement(data)
fun decodeTradingCashflowPage(data: JsonElement): TradingCashflowPage = TradingJson.decodeFromJsonElement(data)
fun decodeTradingFlowsPage(data: JsonElement): TradingFlowsPage = TradingJson.decodeFromJsonElement(data)
fun decodeTradingSymbols(data: JsonElement): TradingSymbols = TradingJson.decodeFromJsonElement(data)
fun decodeTradingSyncStatus(data: JsonElement): TradingSyncStatus = TradingJson.decodeFromJsonElement(data)

fun decodePortfolioOverview(data: JsonElement): PortfolioOverview = TradingJson.decodeFromJsonElement(data)
fun decodePortfolioHistory(data: JsonElement): PortfolioHistory = TradingJson.decodeFromJsonElement(data)
fun decodePositionsList(data: JsonElement): PositionsList = TradingJson.decodeFromJsonElement(data)
fun decodePositionChart(data: JsonElement): PositionChart = TradingJson.decodeFromJsonElement(data)
fun decodeTradingConfiguration(data: JsonElement): ConfigurationView = TradingJson.decodeFromJsonElement<ConfigurationView>(data).also { require(it.revision >= 0) }

fun decodePositionSummary(data: JsonElement): PositionSummary {
    val position = TradingJson.decodeFromJsonElement<PositionItem>(data)
    val detail = TradingJson.decodeFromJsonElement<PositionDetail>(data)
    return PositionSummary(
        position, detail.unit, detail.lotMethod, detail.asOf, detail.openCost, detail.opening,
        detail.awayQty, detail.shortQty, detail.unpricedEvents, detail.lots, detail.lotsTotal,
        detail.realizedByMonth, detail.freshness,
    )
}

/** Entire current package contract, including future screens; fails closed for an unknown tool. */
fun decodeTradingToolResult(tool: String, data: JsonElement): Any = when (tool) {
    "portfolio.get" -> decodePortfolioOverview(data)
    "portfolio.history" -> decodePortfolioHistory(data)
    "positions.list" -> decodePositionsList(data)
    "position.summary" -> decodePositionSummary(data)
    "position.chart" -> decodePositionChart(data)
    "positions.strategy" -> TradingJson.decodeFromJsonElement<StrategyResult>(data)
    "positions.method" -> TradingJson.decodeFromJsonElement<MethodResult>(data)
    "positions.core" -> TradingJson.decodeFromJsonElement<CoreResult>(data)
    "orders.open", "orders.history" -> TradingJson.decodeFromJsonElement<TradingOrderPage>(data)
    "executions.list" -> TradingJson.decodeFromJsonElement<TradingExecutionPage>(data)
    "cashflow.list" -> TradingJson.decodeFromJsonElement<TradingCashflowPage>(data)
    "flows.list" -> TradingJson.decodeFromJsonElement<TradingFlowsPage>(data)
    "flows.mark" -> TradingJson.decodeFromJsonElement<FlowMarkResult>(data)
    "symbols.list" -> TradingJson.decodeFromJsonElement<TradingSymbols>(data)
    "sync.status" -> TradingJson.decodeFromJsonElement<TradingSyncStatus>(data)
    "sync.refill" -> TradingJson.decodeFromJsonElement<TradingRefillResult>(data)
    else -> error("Unsupported trading tool")
}
