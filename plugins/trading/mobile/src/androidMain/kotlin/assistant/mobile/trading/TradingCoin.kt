package assistant.mobile.trading

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

@Composable
@OptIn(ExperimentalLayoutApi::class)
internal fun TradingCoin(state: TradingState, controller: TradingController, navigate: (String, String?) -> Unit) {
    val scope = rememberCoroutineScope()
    val refresh = { scope.launch { controller.refresh() }; Unit }
    val summary = state.summary.valueOrNull()?.takeIf { it.position.coin == state.coin }
    val position = summary?.position
    val unit = summary?.unit ?: state.shownUnit
    val unknown = tradingText("calculation_unknown")
    var editingCore by remember(state.coin) { mutableStateOf(false) }
    fun money(value: String?, sign: Boolean = false) = value?.let { TradingFormat.money(it, unit, sign) } ?: unknown
    fun cost(value: String?) = value?.let { TradingFormat.price(it, unit) } ?: unknown
    fun result(value: String?, pct: String? = null) = money(value, true) + (pct?.let { " (${TradingFormat.percent(it)})" } ?: "")
    Column(Modifier.testTag("trading_coin_${state.coin.orEmpty()}"), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        TradingCountSettings(state, controller, state.coin, showError = !editingCore)
        TradingReadStatus(state.summary, state, refresh, navigate)
        if (summary != null && position != null) {
            TradingCalculationIssues(position.issues,
                listOf(position.result, position.realized, position.realizedInRange, position.unrealized, position.income, position.incomeInRange, position.fees, position.value).any { it == null })
            Text(position.coin, style = MaterialTheme.typography.headlineLarge, modifier = Modifier.semantics { heading() })
            Text("${tradingText("held")}: ${TradingFormat.amount(position.openQty)} ${position.coin} · ${money(position.value)}",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            position.tradedAgainst?.takeIf { it !in TradingStables && it != unit }?.let { against ->
                Text(tradingText("traded_against", against), style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = { controller.count(against) }, modifier = Modifier.heightIn(min = 48.dp)) { Text(tradingText("show_in", against)) }
            }
            TradingMetric(tradingText("coin_result"), result(position.result), position.result, large = true)
            TradingSection(tradingText("price_chart")) {
                FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    TradingChartRange.entries.forEach { range ->
                        FilterChip(selected = range == state.chartRange, onClick = { controller.selectChartRange(range) },
                            label = { Text(tradingText(range.label)) }, modifier = Modifier.heightIn(min = 48.dp).testTag("trading_chart_range_${range.key}"))
                    }
                }
                if (unit == position.coin) Text(tradingText("chart_same_unit", position.coin), style = MaterialTheme.typography.bodySmall)
                else {
                    TradingReadStatus(state.chart, state, refresh, navigate)
                    val chart = state.chart.valueOrNull()?.takeIf { it.coin == state.coin }
                    if (chart != null) {
                        TradingCalculationIssues(chart.issues)
                        val points = samples(chart).map { TradingChartPoint(it.time, it.value) }
                        if (points.size < 2) Text(tradingText("chart_empty"), style = MaterialTheme.typography.bodySmall)
                        else {
                            val trades = chart.trades.mapNotNull { trade ->
                                val at = TradingFormat.instant(trade.time) ?: return@mapNotNull null
                                val value = trade.price.toDoubleOrNull()?.takeIf { it.isFinite() } ?: return@mapNotNull null
                                val sell = trade.side == "sell"
                                TradingChartTrade(at, value, sell,
                                    tradingText(if (sell) "chart_sold" else "chart_bought", "${TradingFormat.amount(trade.qty)} ${chart.coin}", cost(trade.price)))
                            }
                            val levels = listOf(
                                Triple(chart.lines.avgEntry, "level_entry", "chart-3"),
                                Triple(chart.lines.breakEven, "level_even", "chart-2"),
                                Triple(chart.lines.coreEntry, "level_core", "chart-4"),
                            ).mapNotNull { (raw, label, token) ->
                                val value = raw?.toDoubleOrNull()?.takeIf { it.isFinite() && it >= 0 } ?: return@mapNotNull null
                                TradingChartLevel(value, tradingText(label), token)
                            }
                            TradingLineChart(points, tradingText("price_chart"), "trading_coin_chart", { cost(it.toBigDecimal().toPlainString()) }, trades = trades, levels = levels)
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        Text("▲ ${tradingText("legend_buy")}", style = MaterialTheme.typography.bodySmall)
                        Text("▼ ${tradingText("legend_sell")}", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            TradingSection {
                TradingMetric(tradingText("avg_entry"), cost(position.avgEntry), large = true)
                Text(tradingText("avg_entry_hint"), style = MaterialTheme.typography.bodySmall)
                TradingLine(tradingText("mark_price"), cost(position.mark))
                TradingLine(tradingText("break_even"), if (TradingFormat.negative(position.breakEven)) tradingText("break_even_done") else cost(position.breakEven))
                if (position.cycleStartedAt != null && position.breakEven != null) Text(tradingText("break_even_since", TradingFormat.dateTime(position.cycleStartedAt)), style = MaterialTheme.typography.bodySmall)
                position.realizedInCycle?.let { TradingLine(tradingText("realized_cycle"), result(it), it) }
                TradingLine(tradingText("avg_buy"), cost(position.avgBuy))
            }
            TradingSection {
                TradingMetric(tradingText("unrealized"), result(position.unrealized, position.unrealizedPct), position.unrealized, large = true)
                TradingLine(tradingText("realized_range"), result(position.realizedInRange), position.realizedInRange)
                TradingLine(tradingText("realized"), result(position.realized), position.realized)
                if (position.income == null || TradingFormat.nonZero(position.income)) TradingLine(tradingText("income"), result(position.income), position.income)
                TradingLine(tradingText("fees"), money(position.fees))
            }
            if (summary.realizedByMonth.isNotEmpty()) TradingSection(tradingText("realized_by_month")) {
                Text(tradingText(state.period.label), style = MaterialTheme.typography.bodySmall)
                TradingSignedBars(summary.realizedByMonth.map { TradingChartBar(TradingFormat.month(it.month, true), it.realized?.toDoubleOrNull(), result(it.realized)) },
                    tradingText("realized_by_month"), "trading_coin_months")
            }
            val core = position.core
            TradingSection(tradingText("core_title"), "trading_core") {
                if (core == null) Text(tradingText("core_none"), style = MaterialTheme.typography.bodySmall)
                else {
                    TradingLine(tradingText("core_held"), tradingText("core_line", "${core.qty?.let(TradingFormat::amount) ?: unknown} ${position.coin}", TradingFormat.date(core.since)))
                    TradingLine(tradingText("core_entry"), cost(core.entry))
                    TradingLine(tradingText("core_unrealized"), result(core.unrealized), core.unrealized)
                    if (core.soldQty == null || TradingFormat.nonZero(core.soldQty)) TradingLine(
                        if (core.soldQty == null) tradingText("core_sold_unknown") else tradingText("core_sold", "${TradingFormat.amount(core.soldQty)} ${position.coin}", cost(core.soldPrice)),
                        result(core.soldUnrealized), core.soldUnrealized)
                    val held = TradingFormat.number(core.qty)
                    val asked = TradingFormat.number(core.asked)
                    if (held != null && asked != null && held < asked)
                        Text(tradingText("core_partial", "${TradingFormat.amount(core.qty)} ${position.coin}"), color = tradingColor("warning"), style = MaterialTheme.typography.bodySmall)
                    if (core.qty == null || core.soldQty == null) Text(tradingText("core_allocation_unknown"), color = tradingColor("warning"), style = MaterialTheme.typography.bodySmall)
                }
                if (editingCore) TradingCoreEditor(position, state, controller, cancel = { editingCore = false }, saved = { editingCore = false })
                else if (state.canSetCore) {
                    OutlinedButton(onClick = { editingCore = true }, enabled = !state.acting,
                        modifier = Modifier.heightIn(min = 48.dp).testTag(if (core == null) "trading_core_mark" else "trading_core_edit")) {
                        Text(tradingText(if (core == null) "core_mark" else "core_edit"))
                    }
                    if (core != null) TextButton(onClick = { scope.launch { controller.setCore(position.coin, null) } },
                        enabled = !state.acting && state.pendingWrites.none { it.tool == "positions.core" },
                        modifier = Modifier.heightIn(min = 48.dp).testTag("trading_core_remove")) { Text(tradingText("core_remove")) }
                }
                TradingPendingWrites(state, controller, setOf("positions.core"), completed = { editingCore = false })
            }
            summary.opening?.takeIf { TradingFormat.nonZero(it.qty) }?.let { opening ->
                Text(if (TradingFormat.negative(opening.qty)) tradingText("opening_negative", TradingFormat.dateTime(opening.at)) else
                    tradingText("opening_note", TradingFormat.dateTime(opening.at), "${TradingFormat.amount(opening.qty)} ${position.coin}"),
                    style = MaterialTheme.typography.bodySmall, color = if (TradingFormat.negative(opening.qty)) tradingColor("warning") else MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (TradingFormat.nonZero(position.unexplainedQty)) Text(tradingText("unexplained_note", "${TradingFormat.amount(position.unexplainedQty)} ${position.coin}"), color = tradingColor("warning"), style = MaterialTheme.typography.bodySmall)
            if (TradingFormat.nonZero(summary.shortQty)) Text(tradingText("short_note", "${TradingFormat.amount(summary.shortQty)} ${position.coin}"), color = tradingColor("warning"), style = MaterialTheme.typography.bodySmall)
            if (summary.unpricedEvents > 0) Text(tradingText("unpriced_note", summary.unpricedEvents.toString()), color = tradingColor("warning"), style = MaterialTheme.typography.bodySmall)
            if (summary.lots.isNotEmpty() && (core == null || (core.qty != null && core.soldQty != null))) {
                var expanded by remember(state.coin) { mutableStateOf(false) }
                TradingSection {
                    TextButton(onClick = { expanded = !expanded }, modifier = Modifier.heightIn(min = 48.dp).testTag("trading_lots")) {
                        Text(tradingText("lots_title", summary.lots.size.toString(), summary.lotsTotal.toString()))
                    }
                    if (expanded) summary.lots.forEach { lot ->
                        Text(tradingText(if (lot.core) "core_lot" else "lot_line", "${TradingFormat.amount(lot.qty)} ${position.coin}", cost(lot.unitCost)), style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
}
