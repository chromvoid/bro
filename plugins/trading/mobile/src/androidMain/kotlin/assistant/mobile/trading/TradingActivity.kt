package assistant.mobile.trading

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import assistant.mobile.pluginapi.PluginError
import kotlinx.coroutines.launch
import java.time.LocalDate

@Composable
internal fun TradingOrders(state: TradingState, controller: TradingController, navigate: (String, String?) -> Unit) {
    val scope = rememberCoroutineScope()
    val refresh = { scope.launch { controller.refresh() }; Unit }
    Column(Modifier.testTag("trading_orders"), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        listOf(false, true).forEach { history ->
            val read = if (history) state.orderHistory else state.openOrders
            val page = read.valueOrNull()
            TradingSection(tradingText(if (history) "orders_history" else "open_orders"),
                if (history) "trading_orders_history" else "trading_orders_open") {
                if (!history || read != TradingRead.NotConnected) TradingReadStatus(read, state, refresh, navigate)
                if (page != null) {
                    if (page.items.isEmpty()) Text(tradingText(if (history) "no_orders" else "no_open_orders"))
                    page.items.forEach { order -> TradingOrderRow(order); HorizontalDivider() }
                    if (page.nextCursor != null) TradingMore(state) { scope.launch { controller.moreOrders(history) } }
                }
            }
        }
    }
}

@Composable
private fun TradingOrderRow(order: TradingOrder) {
    val base = order.baseCoin.orEmpty()
    val quote = order.quoteCoin.orEmpty()
    val parts = mutableListOf<String>()
    if (order.qtyUnit == "quote") {
        parts += tradingText("order_spent", TradingFormat.amount(order.filledValue), TradingFormat.amount(order.qty), quote)
        if (TradingFormat.nonZero(order.filledQty)) parts += tradingText("order_got", TradingFormat.amount(order.filledQty), base)
    } else {
        parts += tradingText("order_filled", TradingFormat.amount(order.filledQty), TradingFormat.amount(order.qty), base)
        if (TradingFormat.nonZero(order.filledValue)) parts += tradingText("order_worth", TradingFormat.amount(order.filledValue), quote)
    }
    if (order.avgPrice != null) parts += tradingText("order_avg", TradingFormat.amount(order.avgPrice), quote)
    else if (order.price != null) parts += tradingText("order_price", TradingFormat.amount(order.price), quote)
    val statuses = setOf("New", "PartiallyFilled", "Filled", "Cancelled", "PartiallyFilledCanceled", "Rejected", "Untriggered", "Triggered", "Deactivated", "Active", "Unknown")
    val status = if (order.status in statuses) tradingText("order_${order.status}") else order.status
    val type = if (order.type in setOf("Limit", "Market")) tradingText("type_${order.type}") else order.type
    Column(Modifier.fillMaxWidth().testTag("trading_order_${order.orderId}"), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        TradingSide(order.side)
        Text("${order.symbol} · $type", style = MaterialTheme.typography.titleMedium)
        Text(parts.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(status, color = when (order.status) {
            "Filled" -> tradingColor("success")
            "New", "PartiallyFilled", "Untriggered", "Triggered" -> MaterialTheme.colorScheme.onSurface
            else -> MaterialTheme.colorScheme.onSurfaceVariant
        })
        Text(tradingText("order_created", TradingFormat.dateTime(order.createdAt)), style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun TradingSide(side: String) {
    val normalized = side.lowercase()
    Text(tradingText(if (normalized in setOf("buy", "sell")) "side_$normalized" else "unknown_value"),
        style = MaterialTheme.typography.labelMedium, color = tradingColor(if (normalized == "sell") "destructive" else "success"))
}

@Composable
internal fun TradingTrades(state: TradingState, controller: TradingController, navigate: (String, String?) -> Unit) {
    val scope = rememberCoroutineScope()
    val page = state.trades.valueOrNull()
    Column(Modifier.testTag("trading_trades"), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        TradingReadStatus(state.trades, state, { scope.launch { controller.refresh() }; Unit }, navigate)
        if (page != null) {
            if (page.items.isEmpty()) Text(tradingText("no_trades"))
            val today = TradingFormat.date(java.time.Instant.now().toString())
            val yesterday = TradingFormat.date(LocalDate.now().minusDays(1).atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toString())
            page.items.groupBy { TradingFormat.date(it.execTime) }.forEach { (day, items) ->
                TradingSection(when (day) { today -> tradingText("today"); yesterday -> tradingText("yesterday"); else -> day }) {
                    items.forEach { trade ->
                        fun coin(value: String?, name: String?) = "${TradingFormat.amount(value)} ${name.orEmpty()}".trim()
                        Column(Modifier.testTag("trading_execution_${trade.execId}"), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            TradingSide(trade.side)
                            Text("${coin(trade.qty, trade.baseCoin)} · ${coin(trade.price, trade.quoteCoin)}", style = MaterialTheme.typography.titleMedium)
                            Text(tradingText("trade_detail", coin(trade.value, trade.quoteCoin), coin(trade.fee, trade.feeCoin),
                                tradingText(if (trade.isMaker) "maker" else "taker")), style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text("${trade.symbol} · ${TradingFormat.dateTime(trade.execTime).drop(11)}", style = MaterialTheme.typography.bodySmall)
                        }
                        HorizontalDivider()
                    }
                }
            }
            if (page.nextCursor != null) TradingMore(state) { scope.launch { controller.moreTrades() } }
        }
    }
}

@Composable
private fun TradingMore(state: TradingState, more: () -> Unit) {
    state.moreError?.let { TradingError(it) }
    OutlinedButton(onClick = more, enabled = !state.loadingMore, modifier = Modifier.heightIn(min = 48.dp).testTag("trading_more")) {
        Text(tradingText(if (state.loadingMore) "loading" else "more"))
    }
}

@Composable
@OptIn(ExperimentalLayoutApi::class)
internal fun TradingCashflow(state: TradingState, controller: TradingController, navigate: (String, String?) -> Unit) {
    val scope = rememberCoroutineScope()
    val page = state.flows.valueOrNull()
    var editing by remember { mutableStateOf<String?>(null) }
    Column(Modifier.testTag("trading_cashflow"), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("all", "outside", "money", "transfers", "earn").forEach { filter ->
                FilterChip(selected = state.flowFilter == filter, onClick = { editing = null; controller.selectFlowFilter(filter) },
                    label = { Text(tradingText("flows_$filter")) }, modifier = Modifier.heightIn(min = 48.dp).testTag("trading_flows_$filter"))
            }
        }
        TradingReadStatus(state.flows, state, { scope.launch { controller.refresh() }; Unit }, navigate)
        if (page != null) {
            TradingSection(tag = "trading_cashflow_totals") {
                page.totals.usd?.let { usd ->
                    TradingMetric(tradingText("came_in"), TradingFormat.usdShort(usd.incoming, true), usd.incoming)
                    TradingMetric(tradingText("went_out"), if (TradingFormat.nonZero(usd.outgoing)) "−${TradingFormat.usdShort(usd.outgoing)}" else TradingFormat.usd("0"), "-${usd.outgoing}")
                    TradingMetric(tradingText("net"), TradingFormat.usdShort(usd.net, true))
                }
                if (page.totals.byMonth.size > 1) TradingSignedColumns(page.totals.byMonth.map { month ->
                    TradingChartColumn(month.month, TradingFormat.month(month.month), TradingFormat.month(month.month, true), listOf(
                        TradingChartPart(month.usdIn.toDoubleOrNull(), tradingText("flow_came", TradingFormat.usd(month.usdIn))),
                        TradingChartPart(month.usdOut.toDoubleOrNull()?.let { -it }, tradingText("flow_went", TradingFormat.usd(month.usdOut))),
                    ))
                }, tradingText("flows_by_month"), "trading_cashflow_months", tradingText("flows_by_month"), { TradingFormat.usdShort(it.toBigDecimal().toPlainString()) })
                var breakdown by remember { mutableStateOf(false) }
                TextButton(onClick = { breakdown = !breakdown }, modifier = Modifier.heightIn(min = 48.dp).testTag("trading_flows_breakdown")) {
                    Text(tradingText("flows_breakdown"))
                }
                if (breakdown) {
                    page.totals.byKind.forEach { row -> TradingLine(tradingFlowKindLabel(row.kind), tradingBoth(row.usdIn, row.usdOut) { TradingFormat.usd(it) }) }
                    val net = tradingText("net")
                    page.totals.byCoin.forEach { row -> TradingLine(row.coin,
                        tradingBoth(row.incoming, row.outgoing, TradingFormat::amount) +
                            if (TradingFormat.nonZero(row.incoming) && TradingFormat.nonZero(row.outgoing)) " · $net ${TradingFormat.amount(row.net)}" else "") }
                }
                page.totals.usd?.unpricedCount?.takeIf { it > 0 }?.let { Text(tradingText("unpriced", it.toString()), style = MaterialTheme.typography.bodySmall) }
                Text(tradingText("flows_hint"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (page.items.isEmpty()) Text(tradingText("no_cashflow"))
            page.items.groupBy { item -> TradingFormat.date(item.occurredAt).let {
                if (it == TradingFormat.Missing) null else "${it.drop(6)}-${it.drop(3).take(2)}"
            } }.forEach { (month, items) ->
                TradingSection(month?.let { TradingFormat.month(it, true) } ?: tradingText("unknown_value")) {
                    items.forEach { item ->
                        Column(Modifier.testTag("trading_cashflow_${item.id}"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            val incoming = item.direction == "in"
                            val here = tradingPlaceLabel(item.account)
                            val there = tradingPlaceLabel(item.counterparty)
                            val route = if (incoming) tradingText("flow_route", there, here) else tradingText("flow_route", here, there)
                            val mark = if (item.treatment == "auto") null else tradingTreatmentLabel(item.treatment, incoming) +
                                (item.unitCostUsd?.let { " ${tradingText("treatment_cost", TradingFormat.usd(it))}" } ?: "")
                            Text(tradingOperationLabel(item.operation, item.note), style = MaterialTheme.typography.titleMedium)
                            Text(listOfNotNull(route, TradingFormat.dateTime(item.occurredAt), mark).joinToString(" · "),
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text("${if (incoming) "+" else "−"}${TradingFormat.amount(item.amount)} ${item.coin}",
                                color = tradingColor(if (incoming) "success" else "destructive"))
                            item.usdValueAtEvent?.let { Text(TradingFormat.usd(it), style = MaterialTheme.typography.bodySmall) }
                            if (item.markable && state.canMarkFlow) {
                                if (editing == item.id) TradingMarkEditor(item, state, controller, cancel = { editing = null }, saved = { editing = null })
                                else TextButton(onClick = { editing = item.id }, modifier = Modifier.heightIn(min = 48.dp).testTag("trading_flow_mark_${item.id}")) {
                                    Text(tradingText("mark_change"))
                                }
                            }
                        }
                        HorizontalDivider()
                    }
                }
            }
            if (page.nextCursor != null) TradingMore(state) { scope.launch { controller.moreFlows() } }
        }
        TradingPendingWrites(state, controller, setOf("flows.mark"), completed = { editing = null })
    }
}

private fun tradingBoth(came: String, went: String, format: (String?) -> String): String = when {
    TradingFormat.nonZero(came) && TradingFormat.nonZero(went) -> "+${format(came)} / −${format(went)}"
    TradingFormat.nonZero(went) -> "−${format(went)}"
    else -> "+${format(came)}"
}

@Composable
private fun tradingOperationLabel(operation: String, note: String?): String {
    val known = setOf("deposit", "internal_deposit", "withdrawal", "internal_withdrawal", "transfer", "p2p_sale", "p2p_sale_cancelled", "p2p_purchase",
        "earn_stake", "earn_redeem", "earn_reward", "earn_other", "bot_transfer", "airdrop", "bonus", "fee_refund", "interest", "convert")
    return if (operation in known) tradingText("op_$operation") else note?.takeIf { it.isNotEmpty() } ?: tradingText("unknown_value")
}

@Composable
private fun tradingFlowKindLabel(kind: String): String = tradingText(if (kind in setOf("deposit", "withdrawal", "transfer", "p2p", "earn", "bot", "reward", "interest", "convert", "other")) "flow_kind_$kind" else "unknown_value")

@Composable
internal fun tradingTreatmentLabel(treatment: String, incoming: Boolean): String = tradingText(
    if (treatment in setOf("own_wallet", "external")) "treatment_${treatment}_${if (incoming) "in" else "out"}" else "treatment_auto")

@Composable
internal fun TradingSync(state: TradingState, controller: TradingController, navigate: (String, String?) -> Unit) {
    val scope = rememberCoroutineScope()
    val status = state.sync.valueOrNull()
    Column(Modifier.testTag("trading_sync"), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        TradingReadStatus(state.sync, state, { scope.launch { controller.refresh() }; Unit }, navigate)
        if (status != null) {
            TradingSection(tag = "trading_sync_status") {
                Text(tradingConnectionLabel(status.connection?.state ?: "not_configured") + (status.connection?.apiKeyHint?.let { " · $it" } ?: ""), style = MaterialTheme.typography.titleMedium)
                Text(tradingText(if (status.stream.state in setOf("live", "reconnecting", "down")) "stream_${status.stream.state}" else "unknown_value"))
            }
            if (status.domains.isNotEmpty()) TradingSection {
                status.domains.forEach { domain ->
                    val domainKnown = domain.domain in setOf("wallet", "executions", "orders", "open_orders", "deposits", "internal_deposits", "withdrawals", "unified_ledger", "funding_ledger", "funding_wallet", "earn")
                    val phaseKnown = domain.phase in setOf("idle", "backfill", "incremental", "refill", "error")
                    val name = tradingText(if (domainKnown) "domain_${domain.domain}" else "unknown_value")
                    val phase = tradingText(if (phaseKnown) "phase_${domain.phase}" else "unknown_value")
                    Text("$name · $phase" + (domain.progressPct?.let { " · ${TradingFormat.percent(it, false)}" } ?: ""), style = MaterialTheme.typography.titleMedium)
                    Text(tradingText("watermark", TradingFormat.dateTime(domain.watermarkAt)), style = MaterialTheme.typography.bodySmall)
                    domain.lastErrorCode?.let { TradingError(PluginError(it)) }
                    domain.coverageGapBefore?.let { Text(tradingText("coverage_gap", TradingFormat.dateTime(it)), style = MaterialTheme.typography.bodySmall) }
                    HorizontalDivider()
                }
            }
        }
        TradingSection(tag = "trading_refill") {
            Text(tradingText("refill_hint"), style = MaterialTheme.typography.bodySmall)
            state.refills.forEach { write ->
                Text(tradingText(if (write.admitted) "refill_pending" else if (write.notFound) "refill_not_found" else "refill_unknown"),
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                Column(Modifier.testTag("trading_refill_check")) {
                    OutlinedButton(onClick = { scope.launch { controller.checkRefill(write.clientKey) } }, enabled = !state.acting,
                        modifier = Modifier.heightIn(min = 48.dp).testTag("trading_check_write_${write.clientKey}")) { Text(tradingText("check_result")) }
                }
                if (!write.admitted && write.notFound) TextButton(onClick = { scope.launch { controller.resendRefill(write.clientKey) } }, enabled = !state.acting,
                    modifier = Modifier.heightIn(min = 48.dp).testTag("trading_refill_resend")) { Text(tradingText("resend")) }
            }
            state.refillNotice?.let { notice ->
                when (notice) {
                    RefillNotice.Done -> Text(tradingText("refill_done"), color = tradingColor("success"), modifier = Modifier.testTag("trading_refill_done"))
                    is RefillNotice.Failed -> Text("${tradingText("refill_failed")} ${tradingErrorText(notice.error)}", color = MaterialTheme.colorScheme.error)
                    is RefillNotice.Refused -> TradingError(notice.error)
                }
                TextButton(onClick = controller::dismissRefillNotice, modifier = Modifier.heightIn(min = 48.dp)) { Text(tradingText("dismiss")) }
            }
            if (state.canRefill) Button(onClick = { scope.launch { controller.refill() } }, enabled = !state.acting && state.refills.isEmpty(),
                modifier = Modifier.heightIn(min = 48.dp).testTag("trading_sync_refill")) { Text(tradingText("refill")) }
            else Text(tradingText("no_write"), style = MaterialTheme.typography.bodySmall)
        }
    }
}

/** An unresolved write is recovered by its original operation key, never by submitting the form again. */
@Composable
internal fun TradingPendingWrites(state: TradingState, controller: TradingController, tools: Set<String>, completed: () -> Unit = {}) {
    val scope = rememberCoroutineScope()
    state.pendingWrites.filter { it.tool in tools }.forEach { write ->
        Column(Modifier.testTag("trading_check_write")) {
            OutlinedButton(onClick = { scope.launch { if (controller.checkWrite(write.clientKey)) completed() } }, enabled = !state.acting,
                modifier = Modifier.heightIn(min = 48.dp).testTag("trading_check_write_${write.clientKey}")) { Text(tradingText("check_result")) }
        }
    }
}
