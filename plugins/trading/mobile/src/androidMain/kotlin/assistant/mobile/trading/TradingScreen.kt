package assistant.mobile.trading

import android.annotation.SuppressLint
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.PrimaryScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import assistant.mobile.design.ui.ProgressBar
import assistant.mobile.pluginapi.PluginError
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.max

@Composable
@SuppressLint("DiscouragedApi")
internal fun tradingText(key: String, vararg arguments: String): String {
    val context = LocalContext.current
    GeneratedTradingStrings.validateArguments(key, arguments)
    val resourceName = GeneratedTradingStrings.resourceName(key)
    val id = context.resources.getIdentifier(resourceName, "string", context.packageName)
    check(id != 0) { "Missing generated trading string: $key" }
    return if (arguments.isEmpty()) context.getString(id) else context.getString(id, *arguments)
}

/** Installation-scoped native screens. Navigation stays with the application host. */
@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
fun TradingScreen(
    controller: TradingController,
    screen: String = "portfolio",
    coin: String? = null,
    navigate: (String, String?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by controller.state.collectAsStateWithLifecycle()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val scope = rememberCoroutineScope()
    // Nav3 may compose the entering and leaving destinations together with one controller.
    val visibilityOwner = remember(controller) { Any() }
    DisposableEffect(controller, lifecycle, visibilityOwner, screen, coin) {
        val observer = LifecycleEventObserver { _, event -> when (event) {
            Lifecycle.Event.ON_START -> controller.activate(visibilityOwner, screen, coin)
            Lifecycle.Event.ON_STOP -> controller.deactivate(visibilityOwner)
            else -> Unit
        } }
        lifecycle.addObserver(observer)
        if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) controller.activate(visibilityOwner, screen, coin)
        onDispose { lifecycle.removeObserver(observer); controller.deactivate(visibilityOwner) }
    }
    val tabs = listOf("portfolio", "orders", "pnl", "cashflow", "settings")
    val selectedTab = when (screen) {
        "position" -> "pnl"
        "trades" -> "orders"
        "sync", "connect" -> "settings"
        else -> screen
    }
    Column(modifier.fillMaxSize().testTag("screen_Trading")) {
        PrimaryScrollableTabRow(
            selectedTabIndex = tabs.indexOf(selectedTab).coerceAtLeast(0),
            modifier = Modifier.fillMaxWidth(),
            edgePadding = 0.dp,
        ) {
            tabs.forEach { tab ->
                Tab(selected = selectedTab == tab,
                    onClick = { navigate(if (tab == "settings") "sync" else tab, null) },
                    text = { Text(tradingText("tab_$tab")) },
                    modifier = Modifier.heightIn(min = 48.dp).testTag("trading_tab_$tab"))
            }
        }
        ScreenColumn("trading_content", Modifier.weight(1f).fillMaxWidth()) {
            val pair = when (screen) {
                "orders", "trades" -> listOf("orders", "trades")
                "sync", "connect", "settings" -> listOf("sync", "connect")
                else -> emptyList()
            }
            if (pair.isNotEmpty()) FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                pair.forEach { item -> FilterChip(selected = screen == item || (screen == "settings" && item == "sync"),
                    onClick = { navigate(item, null) }, label = { Text(tradingText("tab_$item")) },
                    modifier = Modifier.heightIn(min = 48.dp).testTag(if (item == "orders") "trading_view_orders" else "trading_tab_$item")) }
            }
            if (screen != "connect") {
                TradingFreshness(state) { scope.launch { controller.refresh() } }
                if (!state.live && state.eventsAvailable) Text(tradingText("live_off"), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            when (screen) {
                "pnl" -> TradingPnl(state, controller, navigate)
                "position" -> TradingCoin(state, controller, navigate)
                "orders" -> TradingOrders(state, controller, navigate)
                "trades" -> TradingTrades(state, controller, navigate)
                "cashflow" -> TradingCashflow(state, controller, navigate)
                "sync", "settings" -> TradingSync(state, controller, navigate)
                "connect" -> TradingConnect(state, controller, navigate)
                else -> TradingPortfolio(state, controller, navigate)
            }
        }
    }
}

@Composable
private fun TradingFreshness(state: TradingState, refresh: () -> Unit) {
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { delay(30_000); now = System.currentTimeMillis() } }
    val freshness = when (state.screen) {
        "pnl" -> state.positions.valueOrNull()?.freshness
        "position" -> state.summary.valueOrNull()?.freshness
        "orders" -> state.openOrders.valueOrNull()?.freshness ?: state.orderHistory.valueOrNull()?.freshness
        "trades" -> state.trades.valueOrNull()?.freshness
        "cashflow" -> state.flows.valueOrNull()?.freshness
        "sync", "settings" -> null
        else -> state.overview.valueOrNull()?.freshness ?: state.history.valueOrNull()?.freshness
    }
    val stale = freshness?.let { isStale(it, now) } == true
    val text = if (freshness == null) tradingText("fresh_none") else {
        val at = TradingFormat.moment(freshness.asOf, now)
        if (stale) tradingText("fresh_stale", at) + if (freshness.connectionState == "active") "" else " · ${tradingConnectionLabel(freshness.connectionState)}"
        else tradingText(if (state.live) "fresh_live" else "fresh_at", at)
    }
    Row(Modifier.fillMaxWidth().testTag("trading_freshness"), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(text, Modifier.weight(1f).semantics { liveRegion = LiveRegionMode.Polite }, style = MaterialTheme.typography.bodySmall,
            color = if (stale) tradingColor("warning") else MaterialTheme.colorScheme.onSurfaceVariant)
        TextButton(onClick = refresh, modifier = Modifier.heightIn(min = 48.dp).testTag("trading_refresh")) {
            Text(tradingText("refresh"))
        }
    }
    if (state.busy) ProgressBar(modifier = Modifier.fillMaxWidth())
}

@Composable
internal fun TradingSection(title: String? = null, tag: String = "", content: @Composable ColumnScope.() -> Unit) {
    OutlinedCard(Modifier.fillMaxWidth().then(if (tag.isEmpty()) Modifier else Modifier.testTag(tag))) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            title?.let { Text(it, style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() }) }
            content()
        }
    }
}

@Composable
internal fun TradingMetric(label: String, value: String, raw: String? = null, large: Boolean = false) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
        Text(value, color = tradingValueColor(raw), style = if (large) MaterialTheme.typography.headlineMedium else MaterialTheme.typography.titleMedium)
    }
}

@Composable
internal fun TradingLine(label: String, value: String, raw: String? = null) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium,
            color = tradingValueColor(raw), textAlign = androidx.compose.ui.text.style.TextAlign.End)
    }
}

@Composable
internal fun tradingValueColor(raw: String?) = when {
    TradingFormat.negative(raw) -> tradingColor("destructive")
    TradingFormat.nonZero(raw) -> tradingColor("success")
    else -> MaterialTheme.colorScheme.onSurface
}

@Composable
internal fun TradingReadStatus(
    read: TradingRead<*>, state: TradingState, refresh: () -> Unit, navigate: (String, String?) -> Unit,
) {
    when (read) {
        TradingRead.Loading -> {
            val label = tradingText("loading")
            ProgressBar(modifier = Modifier.fillMaxWidth().semantics { contentDescription = label })
        }
        TradingRead.NotConnected -> TradingSection(tag = "trading_not_configured") {
            Text(tradingText("not_connected"))
            if (state.configurationWritable) Button(onClick = { navigate("connect", null) }, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(tradingText("connect_action"))
            }
        }
        is TradingRead.Denied -> Text(tradingText("denied"), color = MaterialTheme.colorScheme.error,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Assertive }.testTag("trading_denied"))
        is TradingRead.Unavailable -> {
            TradingError(read.error)
            OutlinedButton(onClick = refresh, modifier = Modifier.heightIn(min = 48.dp)) { Text(tradingText("refresh")) }
        }
        is TradingRead.Ready<*> -> read.problem?.let { TradingError(it) }
    }
}

@Composable
internal fun tradingErrorText(error: PluginError, egress: List<String> = emptyList()): String {
    val key = when (error.code) {
        "key_not_read_only", "credential_rejected", "permission_missing", "unsupported_account", "key_expired", "account_changed",
        "revision_conflict", "dependency_unavailable", "secret_unavailable", "invalid_arguments", "quota_exceeded", "deadline_exceeded",
        "internal", "refill_in_progress", "range_too_large", "symbol_unknown", "invalid_output" -> "error_${error.code}"
        "not_configured" -> "not_connected"
        "forbidden", "tool_unavailable" -> "error_forbidden"
        "network_unavailable" -> "error_network"
        "ip_not_whitelisted" -> return tradingText("error_ip_not_whitelisted", egress.joinToString(", ").ifEmpty { TradingFormat.Missing })
        else -> return tradingText("error_other", error.message.take(300))
    }
    return tradingText(key)
}

@Composable
internal fun TradingError(error: PluginError, egress: List<String> = emptyList()) {
    Text(tradingErrorText(error, egress), color = MaterialTheme.colorScheme.error,
        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Assertive }.testTag("trading_error"))
}

@Composable
internal fun tradingConnectionLabel(code: String?): String = tradingText(when (code) {
    "active", "validating", "degraded", "auth_failed", "ip_blocked", "key_expired", "secret_unavailable", "disconnected", "not_configured" -> "state_$code"
    else -> "unknown_value"
})

@Composable
internal fun tradingPlaceLabel(code: String?): String = tradingText(when (code) {
    "unified", "funding", "earn", "spot", "chain", "bybit_user", "p2p", "bybit", "bot", "market" -> "place_$code"
    else -> "unknown_value"
})

@Composable
internal fun TradingCalculationIssues(issues: List<PositionIssue>, missing: Boolean = false) {
    val texts = issues.map { if (it.code == "p2p_cancellation_unlinked") tradingText("calculation_p2p_cancellation", it.coin) else tradingText("calculation_missing") }.distinct()
    texts.forEach { Text(it, color = tradingColor("warning"), style = MaterialTheme.typography.bodySmall) }
    if (missing && texts.isEmpty()) Text(tradingText("calculation_missing"), color = tradingColor("warning"), style = MaterialTheme.typography.bodySmall)
}

@Composable
@OptIn(ExperimentalLayoutApi::class)
internal fun TradingPeriods(selected: TradingPeriod, choose: (TradingPeriod) -> Unit) {
    FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
        TradingPeriod.entries.forEach { period ->
            FilterChip(selected = selected == period, onClick = { choose(period) }, label = { Text(tradingText(period.label)) },
                modifier = Modifier.heightIn(min = 48.dp).testTag("trading_period_${period.key}"))
        }
    }
}

@Composable
private fun TradingPortfolio(state: TradingState, controller: TradingController, navigate: (String, String?) -> Unit) {
    val scope = rememberCoroutineScope()
    val refresh = { scope.launch { controller.refresh() }; Unit }
    val overview = state.overview.valueOrNull()
    val history = state.history.valueOrNull()
    val result = state.result.valueOrNull()
    Column(Modifier.testTag("trading_portfolio"), verticalArrangement = Arrangement.spacedBy(16.dp)) {
    TradingReadStatus(state.overview, state, refresh, navigate)
    if (overview != null) {
        TradingSection(tradingText("total")) {
            Text(TradingFormat.usd(overview.totalUsd), style = MaterialTheme.typography.headlineLarge, modifier = Modifier.testTag("trading_portfolio_total"))
            if (overview.byAccount.size > 1) Text(overview.byAccount.entries.map { (key, value) -> "${tradingPlaceLabel(key)} ${TradingFormat.usd(value)}" }.joinToString(" · "),
                style = MaterialTheme.typography.bodySmall)
            listOf("change_day" to overview.change.day, "change_week" to overview.change.week, "change_month" to overview.change.month).forEach { (label, change) ->
                val moved = change?.marketUsd ?: change?.usdDelta
                TradingLine(tradingText(label), "${TradingFormat.usdShort(moved, true)} · ${TradingFormat.percent(if (change?.marketUsd != null) change.marketPct else change?.pct)}", moved)
            }
            Text(tradingText("market_note"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        TradingSection(tradingText("result_total"), "trading_result") {
            TextButton(onClick = { navigate("pnl", null) }, modifier = Modifier.heightIn(min = 48.dp).testTag("trading_all_positions")) { Text(tradingText("all_positions")) }
            if (result != null) {
                val totals = result.totals
                val unknown = tradingText("calculation_unknown")
                fun money(value: String?) = value?.let { TradingFormat.money(it, result.unit, true) } ?: unknown
                Text(money(totals.result), color = tradingValueColor(totals.result), style = MaterialTheme.typography.headlineMedium)
                TradingCalculationIssues(totals.issues, listOf(totals.result, totals.realized, totals.unrealized, totals.income).any { it == null })
                TradingResultSplit(totals.realized, totals.unrealized, money(totals.realized), money(totals.unrealized))
                TradingLine(tradingText("realized_short"), money(totals.realized), totals.realized)
                TradingLine(tradingText("open_short"), money(totals.unrealized), totals.unrealized)
                TradingLine(tradingText("income_short"), money(totals.income))
            } else TradingReadStatus(state.result, state, refresh, navigate)
        }
    }
    TradingSection(tradingText("history_title")) {
        TradingPeriods(state.period) { controller.selectPeriod(it) }
        if (state.history != TradingRead.NotConnected) TradingReadStatus(state.history, state, refresh, navigate)
        if (history != null) {
            val rebuilt = tradingText("point_rebuilt")
            val points = samples(history).mapIndexed { index, point ->
                val flow = point.flow?.takeIf { index > 0 && abs(it) >= max(10.0, point.value * 0.005) }
                val note = listOfNotNull(flow?.let { tradingText(if (it > 0) "flow_in" else "flow_out", TradingFormat.usd(abs(it).toString())) },
                    rebuilt.takeIf { point.rebuilt }).joinToString(" · ").ifEmpty { null }
                TradingChartPoint(point.time, point.value, note, flow?.let { if (it > 0) 1 else -1 } ?: 0)
            }
            if (points.isEmpty()) Text(tradingText("history_empty"), style = MaterialTheme.typography.bodySmall)
            else TradingAreaChart(points, tradingText("history_title"), "trading_portfolio_chart", { TradingFormat.usd(it.toBigDecimal().toPlainString()) })
            if (history.points.any { it.rebuilt }) Text(tradingText("history_rebuilt"), style = MaterialTheme.typography.bodySmall)
        }
    }
    if (overview != null) {
        TradingSection(tradingText("holdings"), "trading_balances") {
            if (overview.balances.all { it.usdValue != null }) TradingShareBar(overview.balances.map { it.coin to (it.usdValue?.toDoubleOrNull() ?: 0.0) }, tradingText("holdings_share"))
            else Text(tradingText("portfolio_shares_unknown"), color = tradingColor("warning"), style = MaterialTheme.typography.bodySmall)
            if (overview.balances.isEmpty()) Text(tradingText("empty_balances"))
            overview.balances.forEach { balance ->
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    TradingLine(balance.coin, TradingFormat.usd(balance.usdValue))
                    Text("${TradingFormat.amount(balance.quantity)} · ${TradingFormat.price(balance.priceUsd, "USD")}", style = MaterialTheme.typography.bodySmall)
                    val accounts = listOf("unified" to balance.accounts?.unified, "funding" to balance.accounts?.funding, "earn" to balance.accounts?.earn).filter { it.second != null }
                    if (accounts.size > 1 || accounts.firstOrNull()?.first?.let { it != "unified" } == true)
                        Text(accounts.map { (key, value) -> "${tradingPlaceLabel(key)} ${TradingFormat.amount(value)}" }.joinToString(" · "), style = MaterialTheme.typography.bodySmall)
                    Text(TradingFormat.percent(balance.day.pct), style = MaterialTheme.typography.bodySmall, color = tradingValueColor(balance.day.pct))
                }
                HorizontalDivider()
            }
        }
        if (overview.earn.isNotEmpty()) TradingSection(tradingText("earn_title")) {
            overview.earn.forEach { position ->
                val category = tradingText(when (position.category) { "FlexibleSaving", "OnChain", "FixedTerm" -> "earn_${position.category}"; else -> "unknown_value" })
                Text(tradingText("earn_line", TradingFormat.amount(position.amount), position.coin, category))
                position.pnl?.let { Text(tradingText("earn_pnl", TradingFormat.amount(it), position.coin), style = MaterialTheme.typography.bodySmall) }
            }
        }
    }
    }
}

@Composable
private fun TradingResultSplit(realized: String?, unrealized: String?, realizedText: String, unrealizedText: String) {
    val a = realized?.toDoubleOrNull() ?: return
    val b = unrealized?.toDoubleOrNull() ?: return
    val sum = abs(a) + abs(b)
    if (!sum.isFinite() || sum == 0.0) return
    val up = tradingColor("success")
    val down = tradingColor("destructive")
    val background = tradingColor("card")
    val description = tradingText("result_split", realizedText, unrealizedText)
    Canvas(Modifier.fillMaxWidth().height(10.dp).semantics { contentDescription = description }) {
        val cut = (abs(a) / sum * size.width).toFloat()
        listOf(Triple(0f, cut, a), Triple(cut, size.width - cut, b)).forEachIndexed { index, (left, width, value) ->
            drawRect((if (value < 0) down else up).copy(alpha = if (index == 0) 1f else 0.6f), Offset(left, 0f), Size(width, size.height))
            if (value < 0) {
                var at = left
                while (at < left + width) { drawLine(background, Offset(at, 0f), Offset(at, size.height), 1.dp.toPx()); at += 4.dp.toPx() }
            }
        }
    }
}

internal val TradingStables = setOf("USDT", "USDC", "FDUSD", "USDE", "DAI", "TUSD")

@Composable
internal fun TradingCountSettings(state: TradingState, controller: TradingController, coin: String? = null, showError: Boolean = true) {
    val scope = rememberCoroutineScope()
    var unitMenu by remember { mutableStateOf(false) }
    var methodMenu by remember { mutableStateOf(false) }
    val list = state.positions.valueOrNull()
    val summary = state.summary.valueOrNull()?.takeIf { it.position.coin == coin }
    val unit = summary?.unit ?: state.shownUnit
    val strategy = list?.strategyCoin ?: "USD"
    val units = (listOf("USD", list?.strategyCoin, unit, summary?.position?.tradedAgainst, coin) +
        if (coin == null) list?.items.orEmpty().map { it.tradedAgainst } + list?.items.orEmpty().filter { !it.closed }.map { it.coin } else emptyList())
        .filterNotNull().filter { it !in TradingStables }.distinct().take(8)
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Column {
            OutlinedButton(onClick = { unitMenu = true }, modifier = Modifier.heightIn(min = 48.dp).testTag("trading_unit_menu")) { Text(tradingText("count_in_short", unit)) }
            DropdownMenu(expanded = unitMenu, onDismissRequest = { unitMenu = false }) {
                DropdownMenuItem(text = { Text(tradingText("count_in_hint")) }, enabled = false, onClick = {})
                units.forEach { value -> DropdownMenuItem(text = { Text(value + if (value == strategy) " ★" else "") },
                    onClick = { unitMenu = false; controller.count(value) }, modifier = Modifier.testTag("trading_unit_$value")) }
                if (state.canSetStrategy && unit != strategy) DropdownMenuItem(
                    text = { Text(tradingText("strategy_make", unit)) }, enabled = !state.acting && state.pendingWrites.none { it.tool == "positions.strategy" },
                    onClick = { unitMenu = false; scope.launch { controller.setStrategy(unit.takeUnless { it == "USD" }) } },
                    modifier = Modifier.testTag("trading_strategy_save"))
            }
        }
        Column {
            OutlinedButton(onClick = { methodMenu = true }, modifier = Modifier.heightIn(min = 48.dp).testTag("trading_method_menu")) {
                Text(tradingText(when (state.shownMethod) { "lifo", "fifo" -> "method_button_${state.shownMethod}"; else -> "unknown_value" }))
            }
            DropdownMenu(expanded = methodMenu, onDismissRequest = { methodMenu = false }) {
                DropdownMenuItem(text = { Text(tradingText("lot_method")) }, enabled = false, onClick = {})
                listOf("lifo", "fifo").forEach { method -> DropdownMenuItem(text = {
                    Column { Text(tradingText("method_$method")); Text(tradingText("method_hint_$method"), style = MaterialTheme.typography.bodySmall) }
                }, onClick = { methodMenu = false; scope.launch { controller.useMethod(method) } }, enabled = !state.acting && state.pendingWrites.none { it.tool == "positions.method" },
                    modifier = Modifier.testTag("trading_method_$method")) }
            }
        }
    }
    if (showError) state.saveError?.let { TradingError(it) }
    TradingPendingWrites(state, controller, setOf("positions.strategy", "positions.method"))
}

@Composable
private fun TradingPnl(state: TradingState, controller: TradingController, navigate: (String, String?) -> Unit) {
    val scope = rememberCoroutineScope()
    val refresh = { scope.launch { controller.refresh() }; Unit }
    val list = state.positions.valueOrNull()
    val unknown = tradingText("calculation_unknown")
    val unit = state.shownUnit
    fun money(value: String?, sign: Boolean = false) = value?.let { TradingFormat.money(it, unit, sign) } ?: unknown
    Column(Modifier.testTag("trading_pnl"), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        TradingCountSettings(state, controller)
        TradingPeriods(state.period) { controller.selectPeriod(it) }
        TradingReadStatus(state.positions, state, refresh, navigate)
        if (list != null) {
            val totals = list.totals
            TradingCalculationIssues(totals.issues, listOf(totals.result, totals.realized, totals.realizedInRange, totals.unrealized, totals.income, totals.incomeInRange, totals.fees, totals.value).any { it == null })
            TradingSection(tradingText("realized_range"), "trading_pnl_totals") {
                Text(tradingText(state.period.label), style = MaterialTheme.typography.bodySmall)
                Text(money(totals.realizedInRange, true), style = MaterialTheme.typography.headlineMedium, color = tradingValueColor(totals.realizedInRange))
                Text(tradingText("pnl_period_hint"), style = MaterialTheme.typography.bodySmall)
                TradingLine(tradingText("income_range"), money(totals.incomeInRange, true), totals.incomeInRange)
            }
            TradingSection(tradingText("pnl_realized_by_coin")) {
                val ranked = list.items.filter { it.realizedInRange == null || TradingFormat.nonZero(it.realizedInRange) }
                    .sortedByDescending { it.realizedInRange?.toDoubleOrNull() ?: Double.NEGATIVE_INFINITY }.take(10)
                if (ranked.isEmpty()) Text(tradingText("pnl_no_realized"), style = MaterialTheme.typography.bodySmall)
                else TradingRankBars(ranked.map { TradingChartRank(it.coin, it.coin, it.realizedInRange?.toDoubleOrNull(), money(it.realizedInRange, true)) },
                    tradingText("pnl_realized_by_coin"), "trading_pnl_rank", onSelect = { navigate("position", it) })
            }
            if (totals.realizedByMonth.isNotEmpty()) TradingSection(tradingText("realized_by_month")) {
                TradingSignedBars(totals.realizedByMonth.map { TradingChartBar(TradingFormat.month(it.month, true), it.realized?.toDoubleOrNull(), money(it.realized, true)) },
                    tradingText("realized_by_month"), "trading_pnl_months")
            }
            Text(tradingText("pnl_current_title"), style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
            Text(tradingText("pnl_current_hint"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            TradingSection(tradingText("pnl_history_total")) {
                Text(money(totals.result, true), style = MaterialTheme.typography.headlineMedium, color = tradingValueColor(totals.result))
                Text(tradingText("result_hint"), style = MaterialTheme.typography.bodySmall)
                TradingLine(tradingText("unrealized"), money(totals.unrealized, true), totals.unrealized)
                TradingLine(tradingText("realized"), money(totals.realized, true), totals.realized)
                TradingLine(tradingText("income"), money(totals.income, true), totals.income)
                TradingLine(tradingText("fees"), money(totals.fees))
            }
            TradingSection(tradingText("pnl_open_positions"), "trading_positions") {
                val open = list.items.filter { !it.closed && it.coin !in TradingStables }.sortedByDescending { it.value?.toDoubleOrNull() ?: Double.NEGATIVE_INFINITY }
                if (open.isEmpty()) Text(tradingText("no_positions"))
                open.forEach { item -> TradingPositionRow(item, unit, false) { navigate("position", item.coin) } }
            }
            val closed = list.items.filter { it.closed || it.coin in TradingStables }.sortedByDescending { it.result?.toDoubleOrNull() ?: Double.NEGATIVE_INFINITY }
            if (closed.isNotEmpty()) {
                var expanded by remember { mutableStateOf(false) }
                TradingSection {
                    TextButton(onClick = { expanded = !expanded }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("trading_closed")) {
                        Text(tradingText("closed_positions", closed.size.toString()))
                    }
                    if (expanded) closed.forEach { item -> TradingPositionRow(item, unit, true) { navigate("position", item.coin) } }
                }
            }
        }
    }
}

@Composable
private fun TradingPositionRow(item: PositionItem, unit: String, closed: Boolean, open: () -> Unit) {
    val unknown = tradingText("calculation_unknown")
    val value = item.value?.let { TradingFormat.money(it, unit) } ?: unknown
    val result = item.result?.let { TradingFormat.money(it, unit, true) } ?: unknown
    Column(Modifier.fillMaxWidth().clickable(onClick = open).heightIn(min = 48.dp).padding(vertical = 8.dp).testTag("trading_position_${item.coin}"),
        verticalArrangement = Arrangement.spacedBy(4.dp)) {
        TradingLine(item.coin, if (closed) result else value, if (closed) item.result else null)
        if (!closed) {
            Text(item.avgEntry?.let { tradingText("entry_to_mark", TradingFormat.price(it, unit), TradingFormat.price(item.mark, unit)) }
                ?: TradingFormat.price(item.mark, unit), style = MaterialTheme.typography.bodySmall)
            Text((item.unrealized?.let { TradingFormat.money(it, unit, true) } ?: unknown) +
                (item.unrealizedPct?.let { " (${TradingFormat.percent(it)})" } ?: ""), color = tradingValueColor(item.unrealized), style = MaterialTheme.typography.bodySmall)
        }
    }
    HorizontalDivider()
}
