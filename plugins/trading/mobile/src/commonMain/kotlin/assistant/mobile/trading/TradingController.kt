package assistant.mobile.trading

import assistant.mobile.pluginapi.InvocationOutcome
import assistant.mobile.pluginapi.PluginError
import assistant.mobile.pluginapi.PluginEvent
import assistant.mobile.pluginapi.PluginHost
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.time.Clock

private val SCREENS = setOf("portfolio", "pnl", "position", "orders", "trades", "cashflow", "sync", "connect")
private const val COALESCE_MS = 300L
private const val KEPT = 24
private const val PAGE = 50
private const val MAX_PAGE = 200
private const val MAX_ROWS = 1000
private const val RESOLVE_MS = 5000L
private val PREFERENCE_TOOLS = setOf("positions.strategy", "positions.method", "positions.core", "flows.mark")

/** Per-installation, per-session reads. Events only invalidate; displayed values come from tools. */
class TradingController(
    scope: CoroutineScope,
    private val host: PluginHost,
    private val clock: () -> Long = { Clock.System.now().toEpochMilliseconds() },
) {
    private val lifetime = SupervisorJob(scope.coroutineContext[Job])
    private val ownerScope = CoroutineScope(scope.coroutineContext + lifetime)
    private val mutableState = MutableStateFlow(TradingState())
    val state: StateFlow<TradingState> = mutableState.asStateFlow()
    private val reads = Mutex()
    private val writes = Mutex()
    private val activeOwners = mutableSetOf<Any>()
    private val active: Boolean get() = activeOwners.isNotEmpty()
    private var closed = false
    private var accessDenied = false
    private var events: Job? = null
    private var refreshJob: Job? = null
    private var refreshAgain = false
    private var eventUpdate: Job? = null
    private var resolving: Job? = null
    private val pending = mutableSetOf<String>()
    private val dirty = SCREENS.toMutableSet()
    private var portfolioPeriod = TradingPeriod.Month
    private var pnlPeriod = TradingPeriod.Month
    private val histories = linkedMapOf<String, TradingRead<PortfolioHistory>>()
    private val lists = linkedMapOf<String, TradingRead<PositionsList>>()
    private val summaries = linkedMapOf<String, TradingRead<PositionSummary>>()
    private val charts = linkedMapOf<String, TradingRead<PositionChart>>()

    init {
        updateCapabilities()
        ownerScope.launch {
            host.pendingWrites.collect { pending ->
                if (!closed && !accessDenied) mutableState.update { it.copy(
                    pendingWrites = pending.filter { write -> write.tool in PREFERENCE_TOOLS },
                    refills = pending.filter { write -> write.tool == "sync.refill" },
                ) }
            }
        }
        ownerScope.launch {
            host.capabilities.collect {
                updateCapabilities()
                if (active && events == null) follow()
            }
        }
    }

    /** Screen changes retain their previous read while a fresh authoritative read is requested. */
    fun show(screen: String, coin: String? = null) {
        if (closed) return
        val selected = screen.takeIf { it in SCREENS } ?: "portfolio"
        val previous = state.value
        val nextCoin = if (selected == "position") coin else previous.coin
        if (selected == previous.screen && nextCoin == previous.coin) return
        mutableState.update {
            it.copy(screen = selected, coin = nextCoin, period = if (selected == "portfolio") portfolioPeriod else pnlPeriod)
        }
        if (selected == "position" && !accessDenied) recallCoin()
        if (active && (selected in dirty || (selected == "position" && nextCoin != previous.coin))) requestRefresh()
    }

    /** NavDisplay transitions can keep several destinations of this installation started together. */
    fun activate(owner: Any, screen: String = state.value.screen, coin: String? = state.value.coin) {
        if (closed) return
        show(screen, coin)
        val firstOwner = !active
        activeOwners.add(owner)
        if (firstOwner) {
            dirty.addAll(SCREENS)
            follow()
        }
        if (state.value.screen in dirty) requestRefresh()
    }

    fun deactivate(owner: Any) {
        if (!activeOwners.remove(owner) || active) return
        stopFollowing()
    }

    private fun stopFollowing() {
        events?.cancel()
        events = null
        eventUpdate?.cancel()
        eventUpdate = null
        resolving?.cancel()
        resolving = null
        pending.clear()
        mutableState.update { it.copy(live = false) }
        dirty.addAll(SCREENS)
    }

    fun close() {
        if (closed) return
        activeOwners.clear()
        stopFollowing()
        closed = true
        lifetime.cancel()
        histories.clear()
        lists.clear()
        summaries.clear()
        charts.clear()
        mutableState.value = TradingState()
    }

    suspend fun refresh() = reads.withLock {
        if (closed || accessDenied) return@withLock
        val selected = state.value
        dirty.remove(selected.screen)
        mutableState.update { it.copy(busy = true) }
        try {
            when (selected.screen) {
                "portfolio" -> loadPortfolio(selected)
                "pnl" -> loadPositions(selected)
                "position" -> loadCoin(selected)
                "orders" -> loadOrders(selected)
                "trades" -> loadTrades(selected)
                "cashflow" -> loadMoney(selected)
                "sync" -> { loadSync(); resolveRefills() }
                "connect" -> loadConfiguration()
            }
        } finally { if (!closed) mutableState.update { it.copy(busy = false) } }
    }

    fun selectPeriod(key: String) { TradingPeriod.entries.firstOrNull { it.key == key }?.let(::selectPeriod) }
    fun selectPeriod(period: TradingPeriod) {
        if (closed || accessDenied || state.value.period == period) return
        if (state.value.screen == "portfolio") {
            portfolioPeriod = period
            mutableState.update { it.copy(period = period, history = histories[period.key] ?: TradingRead.Loading) }
            dirty.add("portfolio")
        } else {
            pnlPeriod = period
            mutableState.update { it.copy(period = period) }
            recallPositions()
            recallCoin()
            dirty.addAll(setOf("pnl", "position"))
        }
        if (active) requestRefresh()
    }

    fun selectChartRange(key: String) { TradingChartRange.entries.firstOrNull { it.key == key }?.let(::selectChartRange) }
    fun selectChartRange(range: TradingChartRange) {
        if (closed || accessDenied || state.value.chartRange == range) return
        mutableState.update { it.copy(chartRange = range) }
        recallCoin()
        dirty.add("position")
        if (active && state.value.screen == "position") requestRefresh()
    }

    fun count(unit: String) {
        if (closed || accessDenied || unit.isBlank() || (state.value.positions is TradingRead.Ready && state.value.shownUnit == unit)) return
        mutableState.update { it.copy(unit = unit) }
        recallPositions()
        recallCoin()
        dirty.addAll(setOf("pnl", "position"))
        if (active) requestRefresh()
    }

    fun selectFlowFilter(key: String) {
        if (closed || accessDenied || key !in setOf("all", "outside", "money", "transfers", "earn") || key == state.value.flowFilter) return
        mutableState.update { it.copy(flowFilter = key, flows = TradingRead.Loading, moreError = null) }
        dirty.add("cashflow")
        if (active && state.value.screen == "cashflow") requestRefresh()
    }

    suspend fun setStrategy(coin: String?) {
        if (closed || accessDenied || !state.value.canSetStrategy || !writes.tryLock()) return
        try {
            if (savePreference("positions.strategy", buildJsonObject { put("coin", coin?.let(::JsonPrimitive) ?: JsonNull) })) {
                mutableState.update { it.copy(unit = null) }
                forgetPositions()
                refresh()
            }
        } finally { writes.unlock() }
    }

    /** Read selection always works; when granted, the explicit choice becomes the owner's default. */
    suspend fun useMethod(method: String) {
        if (method !in setOf("lifo", "fifo") || state.value.acting || closed || accessDenied || !writes.tryLock()) return
        try {
            if (state.value.positions is TradingRead.Ready && state.value.shownMethod == method) return
            mutableState.update { it.copy(method = method) }
            recallPositions()
            recallCoin()
            val saved = state.value.canSetMethod && savePreference("positions.method", buildJsonObject { put("method", method) })
            if (saved) {
                mutableState.update { it.copy(method = null) }
                forgetPositions()
            }
            dirty.addAll(setOf("pnl", "position"))
            refresh()
        } finally { writes.unlock() }
    }

    /** Credentials are only method arguments; the host sends them outside the operation journal. */
    suspend fun connect(request: TradingConnectRequest) {
        val current = state.value.configuration.valueOrNull() ?: return
        if (!state.value.configurationWritable || closed || accessDenied || !validConnectRequest(request) || !writes.tryLock()) return
        try {
            changeConnection(ConnectNotice.Connected) {
                host.configuration.put(
                    settings = buildJsonObject {
                        request.environment?.takeIf(String::isNotEmpty)?.let { put("environment", it) }
                        request.label?.trim()?.takeIf(String::isNotEmpty)?.let { put("label", it) }
                        request.historyDays?.let { put("history_days", it) }
                    },
                    secrets = mapOf("api_key" to request.apiKey.trim(), "api_secret" to request.apiSecret.trim()),
                    expectedRevision = current.revision.takeIf { current.configured },
                    options = if (request.replaceAccount) buildJsonObject { put("replace_account", true) } else null,
                )
            }
        } finally { writes.unlock() }
    }

    suspend fun disconnect() {
        val current = state.value.configuration.valueOrNull() ?: return
        if (!current.configured || !state.value.configurationWritable || closed || accessDenied || !writes.tryLock()) return
        try { changeConnection(ConnectNotice.Disconnected) { host.configuration.delete(current.revision, true) } }
        finally { writes.unlock() }
    }

    fun dismissNotice() { mutableState.update { it.copy(notice = null) } }

    private fun updateCapabilities() {
        if (closed) return
        val capabilities = host.capabilities.value
        val supported = TRADING_REQUIRED_TOOLS.all { (id, major) -> capabilities.tools[id]?.version?.substringBefore('.')?.toIntOrNull() == major }
        if (!supported) denyAccess()
        else if (accessDenied) {
            accessDenied = false
            dirty.addAll(SCREENS)
            mutableState.value = TradingState(screen = state.value.screen, coin = state.value.coin)
            if (active) requestRefresh()
        }
        mutableState.update { it.copy(
            configurationWritable = supported && capabilities.configurationWritable == true,
            canSetStrategy = supported && "positions.strategy" in capabilities.tools,
            canSetMethod = supported && "positions.method" in capabilities.tools,
            canSetCore = supported && "positions.core" in capabilities.tools,
            canMarkFlow = supported && "flows.mark" in capabilities.tools,
            canRefill = supported && "sync.refill" in capabilities.tools,
            eventsAvailable = capabilities.events.isNotEmpty(),
            pendingWrites = if (supported) host.pendingWrites.value.filter { write -> write.tool in PREFERENCE_TOOLS } else emptyList(),
            refills = if (supported) host.pendingWrites.value.filter { write -> write.tool == "sync.refill" } else emptyList(),
        ) }
    }

    private fun requestRefresh() {
        if (closed || !active) return
        if (refreshJob?.isActive == true) { refreshAgain = true; return }
        refreshJob = ownerScope.launch {
            do {
                refreshAgain = false
                refresh()
            } while (refreshAgain && active && !closed)
        }
    }

    private suspend fun loadPortfolio(selection: TradingState) = coroutineScope {
        val overview = async { read("portfolio.get", JsonObject(emptyMap()), selection.overview, ::decodePortfolioOverview, { it.balances.isEmpty() }, { it.freshness }) }
        val history = async { read("portfolio.history", buildJsonObject {
            put("range", periodRange(selection.period.wire)); put("step", selection.period.step)
        }, selection.history, ::decodePortfolioHistory, { it.points.isEmpty() }, { it.freshness }) }
        val result = async { read("positions.list", JsonObject(emptyMap()), selection.result, ::decodePositionsList, freshness = { it.freshness }) }
        overview.await().also { next -> if (!closed && !accessDenied) mutableState.update { it.copy(overview = next) } }
        history.await().also { next ->
            if (!closed && !accessDenied) remember(histories, selection.period.key, next)
            if (!closed && !accessDenied && portfolioPeriod == selection.period) mutableState.update { it.copy(history = next) }
        }
        result.await().also { next -> if (!closed && !accessDenied) mutableState.update { it.copy(result = next) } }
    }

    private suspend fun loadPositions(selection: TradingState) {
        val period = pnlPeriod.wire
        val key = positionArgs(selection, period).toString()
        val next = read("positions.list", positionArgs(selection, period, includeAll = true), selection.positions, ::decodePositionsList, { it.items.isEmpty() }, { it.freshness })
        if (!closed && !accessDenied) remember(lists, key, next)
        if (!closed && !accessDenied && positionsKey(state.value) == key) mutableState.update { it.copy(positions = next) }
    }

    private suspend fun loadCoin(selection: TradingState) = coroutineScope {
        val coin = selection.coin ?: return@coroutineScope
        val period = pnlPeriod.wire
        val summaryKey = positionArgs(selection, period, coin).toString()
        val chartKey = chartKey(selection)
        val summary = async { read("position.summary", positionArgs(selection, period, coin = coin), selection.summary, ::decodePositionSummary, freshness = { it.freshness }) }
        // A deep link also needs the owner's strategy coin and the available counting units.
        val positions = if (selection.positions is TradingRead.Loading) async { loadPositions(selection) } else null
        val chartless = (selection.unit ?: selection.summary.valueOrNull()?.unit) == coin
        val chart = if (!chartless) async { read("position.chart", positionArgs(selection, selection.chartRange.wire, coin), selection.chart, ::decodePositionChart, { it.points.isEmpty() }, { it.freshness }) } else null
        summary.await().also { next ->
            if (!closed && !accessDenied) remember(summaries, summaryKey, next)
            if (!closed && !accessDenied && summaryKey(state.value) == summaryKey) mutableState.update { it.copy(summary = next) }
        }
        chart?.await()?.also { next ->
            if (!closed && !accessDenied) remember(charts, chartKey, next)
            if (!closed && !accessDenied && chartKey(state.value) == chartKey) mutableState.update { it.copy(chart = next) }
        }
        positions?.await()
    }

    private suspend fun loadOrders(selection: TradingState) = coroutineScope {
        val open = async { loadPaged("orders.open", selection.openOrders, ::decodeTradingOrderPage, { it.items.size }, { it.nextCursor }, { it.freshness }, { first, next -> next.copy(items = first.items + next.items) }, current = { state.value.openOrders }) }
        val history = async { loadPaged("orders.history", selection.orderHistory, ::decodeTradingOrderPage, { it.items.size }, { it.nextCursor }, { it.freshness }, { first, next -> next.copy(items = first.items + next.items) }, current = { state.value.orderHistory }) }
        val nextOpen = open.await()
        val nextHistory = history.await()
        if (!closed && !accessDenied) mutableState.update { it.copy(openOrders = nextOpen, orderHistory = nextHistory, moreError = null) }
    }

    private suspend fun loadTrades(selection: TradingState) {
        val next = loadPaged("executions.list", selection.trades, ::decodeTradingExecutionPage, { it.items.size }, { it.nextCursor }, { it.freshness }, { first, rest -> rest.copy(items = first.items + rest.items) }, current = { state.value.trades })
        if (!closed && !accessDenied) mutableState.update { it.copy(trades = next, moreError = null) }
    }

    private suspend fun loadMoney(selection: TradingState) = coroutineScope {
        val cashflow = async { loadPaged("cashflow.list", selection.cashflow, ::decodeTradingCashflowPage, { it.items.size }, { it.nextCursor }, { it.freshness }, { first, rest -> rest.copy(items = first.items + rest.items) }, current = { state.value.cashflow }) }
        val flows = async { loadPaged("flows.list", selection.flows, ::decodeTradingFlowsPage, { it.items.size }, { it.nextCursor }, { it.freshness }, { first, rest -> rest.copy(items = first.items + rest.items) }, flowArgs(selection.flowFilter), current = { state.value.flows }) }
        val nextCashflow = cashflow.await()
        val nextFlows = flows.await()
        if (!closed && !accessDenied) mutableState.update { it.copy(
            cashflow = nextCashflow,
            flows = if (it.flowFilter == selection.flowFilter) nextFlows else it.flows,
            moreError = null,
        ) }
    }

    private suspend fun loadSync() {
        val next = read("sync.status", JsonObject(emptyMap()), state.value.sync, ::decodeTradingSyncStatus, { it.domains.isEmpty() })
        if (!closed && !accessDenied) mutableState.update { it.copy(sync = next) }
    }

    private suspend fun <T> loadPaged(
        tool: String, previous: TradingRead<T>, decode: (JsonElement) -> T,
        size: (T) -> Int, cursor: (T) -> String?, freshness: (T) -> Freshness,
        append: (T, T) -> T, filters: JsonObject = JsonObject(emptyMap()),
        current: () -> TradingRead<T> = { previous },
    ): TradingRead<T> {
        fun wanted(): Int = (current().valueOrNull()?.let(size) ?: PAGE).coerceIn(PAGE, MAX_ROWS)
        var asked = minOf(wanted(), MAX_PAGE)
        var next = read(tool, pageArgs(filters, asked), previous, decode, { size(it) == 0 }, freshness)
        var got = next.valueOrNull()?.let(size) ?: 0
        while (!closed && !accessDenied && next is TradingRead.Ready && next.problem == null && cursor(next.value) != null && got == asked && size(next.value) < wanted()) {
            asked = minOf(wanted() - size(next.value), MAX_PAGE)
            val rest = read(tool, pageArgs(filters, asked, cursor(next.value)), TradingRead.Loading, decode)
            if (rest !is TradingRead.Ready) break
            got = size(rest.value)
            next = next.copy(value = append(next.value, rest.value))
        }
        return next
    }

    suspend fun moreOrders(history: Boolean = false) = morePage(
        if (history) "orders.history" else "orders.open",
        { if (history) it.orderHistory else it.openOrders }, ::decodeTradingOrderPage,
        { it.nextCursor }, { first, next -> next.copy(items = first.items + next.items) },
        { value, next -> if (history) value.copy(orderHistory = next) else value.copy(openOrders = next) },
    )
    suspend fun moreTrades() = morePage("executions.list", { it.trades }, ::decodeTradingExecutionPage, { it.nextCursor }, { first, next -> next.copy(items = first.items + next.items) }, { value, next -> value.copy(trades = next) })
    suspend fun moreCashflow() = morePage("cashflow.list", { it.cashflow }, ::decodeTradingCashflowPage, { it.nextCursor }, { first, next -> next.copy(items = first.items + next.items) }, { value, next -> value.copy(cashflow = next) })
    suspend fun moreFlows() = morePage("flows.list", { it.flows }, ::decodeTradingFlowsPage, { it.nextCursor }, { first, next -> next.copy(items = first.items + next.items) }, { value, next -> value.copy(flows = next) }, flowArgs(state.value.flowFilter))

    private suspend fun <T> morePage(
        tool: String, current: (TradingState) -> TradingRead<T>, decode: (JsonElement) -> T,
        cursor: (T) -> String?, append: (T, T) -> T,
        store: (TradingState, TradingRead<T>) -> TradingState,
        filters: JsonObject = JsonObject(emptyMap()),
    ) {
        if (closed || accessDenied || state.value.loadingMore) return
        val loaded = current(state.value) as? TradingRead.Ready<T> ?: return
        val nextCursor = cursor(loaded.value) ?: return
        mutableState.update { it.copy(loadingMore = true, moreError = null) }
        try {
            val next = read(tool, pageArgs(filters, PAGE, nextCursor), TradingRead.Loading, decode)
            if (closed || accessDenied || current(state.value) !== loaded) return
            mutableState.update { value -> when (next) {
                is TradingRead.Ready -> store(value, loaded.copy(value = append(loaded.value, next.value), empty = false))
                is TradingRead.Denied -> value.copy(moreError = next.error)
                is TradingRead.Unavailable -> value.copy(moreError = next.error)
                else -> value
            } }
        } finally { if (!closed) mutableState.update { it.copy(loadingMore = false) } }
    }

    private suspend fun loadConfiguration() {
        if (host.capabilities.value.configurationWritable == null) {
            mutableState.update { it.copy(configuration = TradingRead.Denied(PluginError("forbidden"))) }
            return
        }
        val previous = state.value.configuration
        val next = tradingReadOutcome(host.configuration.get(), previous, clock(), ::decodeTradingConfiguration, configuration = true)
        if (!closed && !accessDenied) mutableState.update { it.copy(configuration = next) }
        val symbols = read("symbols.list", JsonObject(emptyMap()), state.value.symbols, ::decodeTradingSymbols, { it.items.isEmpty() }, { it.freshness })
        if (!closed && !accessDenied) mutableState.update { it.copy(symbols = symbols) }
    }

    private suspend fun <T> read(
        tool: String,
        args: JsonObject,
        previous: TradingRead<T>,
        decode: (JsonElement) -> T,
        empty: (T) -> Boolean = { false },
        freshness: (T) -> Freshness? = { null },
    ): TradingRead<T> = tradingReadOutcome(host.invoke(tool, args), previous, clock(), decode, empty, freshness)

    private suspend fun savePreference(tool: String, args: JsonObject): Boolean {
        if (host.pendingWrites.value.any { it.tool == tool }) return false
        mutableState.update { it.copy(acting = true, saveError = null) }
        try {
            return settlePreference(tool, resolveAdmitted(host.invoke(tool, args)))
        } finally { if (!closed) mutableState.update { it.copy(acting = false) } }
    }

    /** Core and marks share the host journal with preferences; unfinished keys block another POST. */
    suspend fun setCore(coin: String, qty: String?, since: String? = null): Boolean {
        if (closed || accessDenied || !state.value.canSetCore || coin.isBlank() || !writes.tryLock()) return false
        try {
            val normalized = qty?.let(::tradingDecimalInput)
            if (qty != null && (!validTradingDecimal(normalized, positive = true) || (tradingTimeMillis(since)?.let { it > clock() } != false))) return false
            val saved = savePreference("positions.core", buildJsonObject {
                put("coin", coin)
                put("qty", normalized?.let(::JsonPrimitive) ?: JsonNull)
                put("since", if (normalized == null) JsonNull else since?.let(::JsonPrimitive) ?: JsonNull)
            })
            if (saved) { forgetPositions(); refresh() }
            return saved
        } finally { writes.unlock() }
    }

    suspend fun markFlow(id: String, treatment: String, unitCost: String? = null): Boolean {
        if (closed || accessDenied || !state.value.canMarkFlow || id.isBlank() || treatment !in setOf("auto", "own_wallet", "external") || !writes.tryLock()) return false
        try {
            val cost = unitCost?.let(::tradingDecimalInput)?.takeIf(String::isNotEmpty)
            if (cost != null && treatment == "external" && !validTradingDecimal(cost)) return false
            val preferenceError = state.value.saveError
            mutableState.update { it.copy(savingFlow = id, flowMarkError = null) }
            val saved = savePreference("flows.mark", buildJsonObject {
                put("id", id); put("treatment", treatment)
                if (treatment == "external" && cost != null) put("unit_cost_usd", cost)
            })
            if (!closed && !accessDenied) mutableState.update { it.copy(
                flowMarkError = if (saved) null else FlowMarkError(id, it.saveError ?: PluginError("unexpected_state")),
                saveError = preferenceError,
            ) }
            if (saved) {
                invalidate(setOf("cashflow", "pnl", "position", "portfolio"))
                refresh()
            }
            return saved
        } finally {
            if (!closed) mutableState.update { it.copy(savingFlow = null) }
            writes.unlock()
        }
    }

    /** A pending editor action is checked by its old key; checking cannot submit another write. */
    suspend fun checkWrite(clientKey: String): Boolean {
        if (closed || accessDenied || !writes.tryLock()) return false
        try {
            val write = host.pendingWrites.value.firstOrNull { it.clientKey == clientKey && it.tool in PREFERENCE_TOOLS } ?: return false
            val preferenceError = state.value.saveError
            mutableState.update { it.copy(acting = true, saveError = null) }
            val saved = settlePreference(write.tool, host.lookup(clientKey))
            if (!closed && !accessDenied && write.tool == "flows.mark") {
                val id = (write.arguments["id"] as? JsonPrimitive)?.content.orEmpty()
                mutableState.update { it.copy(flowMarkError = if (saved) null else FlowMarkError(id, it.saveError ?: PluginError("unexpected_state")), saveError = preferenceError) }
            }
            if (saved) {
                val selectedCoin = (write.arguments["coin"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: "USD"
                val selectedMethod = (write.arguments["method"] as? JsonPrimitive)?.takeIf { it.isString }?.content
                if (write.tool == "positions.strategy" && state.value.unit == selectedCoin) mutableState.update { it.copy(unit = null) }
                if (write.tool == "positions.method" && state.value.method == selectedMethod) mutableState.update { it.copy(method = null) }
                forgetPositions()
                invalidate(setOf("cashflow", "portfolio", "pnl", "position"))
                refresh()
            }
            return saved
        } finally {
            if (!closed) mutableState.update { it.copy(acting = false) }
            writes.unlock()
        }
    }

    private suspend fun resolveAdmitted(outcome: InvocationOutcome): InvocationOutcome = when (outcome) {
        is InvocationOutcome.Unknown -> outcome.clientKey?.let { host.lookup(it) } ?: outcome
        is InvocationOutcome.Pending -> host.lookup(outcome.clientKey)
        else -> outcome
    }

    private suspend fun settlePreference(tool: String, outcome: InvocationOutcome): Boolean {
        when (outcome) {
            is InvocationOutcome.Completed -> outcome.clientKey?.let { host.acknowledge(it) }
            is InvocationOutcome.Failed -> outcome.clientKey?.let { host.acknowledge(it) }
            else -> Unit
        }
        if (closed || accessDenied) return false
        val valid = outcome is InvocationOutcome.Completed && runCatching { decodeTradingToolResult(tool, outcome.data) }.isSuccess
        if (!valid) mutableState.update { it.copy(saveError = if (outcome is InvocationOutcome.Completed) PluginError("invalid_output") else outcome.errorOrUnexpected()) }
        return valid
    }

    suspend fun refill() {
        if (!state.value.canRefill || host.pendingWrites.value.any { it.tool == "sync.refill" }) return
        actRefill { resolveAdmitted(host.invoke("sync.refill", JsonObject(emptyMap()))) }
    }

    suspend fun checkRefill(clientKey: String) {
        if (host.pendingWrites.value.none { it.clientKey == clientKey && it.tool == "sync.refill" }) return
        actRefill { host.lookup(clientKey) }
    }

    /** An explicit resend is available only after the host established a definite missing operation. */
    suspend fun resendRefill(clientKey: String) {
        val write = host.pendingWrites.value.firstOrNull { it.clientKey == clientKey && it.tool == "sync.refill" } ?: return
        if (!write.notFound || write.admitted) return
        actRefill { resolveAdmitted(host.retry(clientKey)) }
    }

    fun dismissRefillNotice() { mutableState.update { it.copy(refillNotice = null) } }

    private suspend fun actRefill(call: suspend () -> InvocationOutcome) {
        if (closed || accessDenied || !writes.tryLock()) return
        mutableState.update { it.copy(acting = true, refillNotice = null) }
        try { if (settleRefill(call())) loadSync() }
        finally { if (!closed) mutableState.update { it.copy(acting = false) }; writes.unlock() }
    }

    private suspend fun settleRefill(outcome: InvocationOutcome): Boolean {
        if (closed || accessDenied) return false
        when (outcome) {
            is InvocationOutcome.Completed -> mutableState.update { it.copy(refillNotice =
                if (runCatching { decodeTradingToolResult("sync.refill", outcome.data) }.isSuccess) RefillNotice.Done
                else RefillNotice.Failed(PluginError("invalid_output"))) }
            is InvocationOutcome.Failed -> mutableState.update { it.copy(refillNotice = RefillNotice.Failed(outcome.error)) }
            is InvocationOutcome.Rejected -> mutableState.update { it.copy(refillNotice = RefillNotice.Refused(outcome.error)) }
            else -> return false
        }
        val key = when (outcome) {
            is InvocationOutcome.Completed -> outcome.clientKey
            is InvocationOutcome.Failed -> outcome.clientKey
            else -> null
        }
        key?.let { host.acknowledge(it) }
        return outcome is InvocationOutcome.Completed || outcome is InvocationOutcome.Failed
    }

    private suspend fun resolveRefills() {
        if (closed || accessDenied || !writes.tryLock()) return
        try {
            var finished = false
            for (write in host.pendingWrites.value.filter { it.tool == "sync.refill" }) {
                finished = settleRefill(host.lookup(write.clientKey)) || finished
            }
            if (finished) loadSync()
        } finally { writes.unlock() }
    }

    private fun resolveRefillsSoon() {
        if (resolving?.isActive == true || host.pendingWrites.value.none { it.tool == "sync.refill" }) return
        resolving = ownerScope.launch {
            delay(RESOLVE_MS)
            resolving = null
            if (active) resolveRefills()
        }
    }

    private suspend fun changeConnection(success: ConnectNotice, call: suspend () -> InvocationOutcome) {
        mutableState.update { it.copy(acting = true, notice = null) }
        try {
            val outcome = call()
            if (closed || accessDenied) return
            when (outcome) {
                is InvocationOutcome.Completed -> mutableState.update { it.copy(
                    configuration = tradingReadOutcome(outcome, it.configuration, clock(), ::decodeTradingConfiguration, configuration = true),
                    notice = success,
                ) }
                is InvocationOutcome.Failed -> mutableState.update { it.copy(notice = ConnectNotice.Failed(outcome.error)) }
                is InvocationOutcome.Rejected -> mutableState.update { it.copy(notice = ConnectNotice.Failed(outcome.error)) }
                else -> mutableState.update { it.copy(notice = ConnectNotice.Unknown) }
            }
            if (outcome !is InvocationOutcome.Completed) loadConfiguration()
            dirty.addAll(SCREENS - "connect")
            lists.clear(); summaries.clear(); charts.clear(); histories.clear()
        } finally { if (!closed) mutableState.update { it.copy(acting = false) } }
    }

    private fun follow() {
        if (events?.isActive == true || !active || host.capabilities.value.events.isEmpty()) return
        events = ownerScope.launch {
            try {
                host.observe().collect { event ->
                    if (event is PluginEvent.Denied) denyAccess()
                    mutableState.update { it.copy(live = event !is PluginEvent.Denied) }
                    pending.addAll(affectedTradingScreens(event))
                    resolveRefillsSoon()
                    if (eventUpdate == null && pending.isNotEmpty()) eventUpdate = ownerScope.launch {
                        delay(COALESCE_MS)
                        val affected = pending.toSet()
                        pending.clear()
                        eventUpdate = null
                        invalidate(affected)
                    }
                }
            } catch (error: CancellationException) { throw error }
            finally { if (!closed) mutableState.update { it.copy(live = false) } }
        }
    }

    private fun denyAccess() {
        accessDenied = true
        histories.clear(); lists.clear(); summaries.clear(); charts.clear()
        val denied = TradingRead.Denied(PluginError("forbidden"))
        mutableState.update { it.copy(
            overview = denied, history = denied, result = denied, positions = denied,
            summary = denied, chart = denied, configuration = denied, live = false,
            openOrders = denied, orderHistory = denied, trades = denied, cashflow = denied,
            flows = denied, symbols = denied, sync = denied, pendingWrites = emptyList(), refills = emptyList(),
            configurationWritable = false, canSetStrategy = false, canSetMethod = false, canSetCore = false, canMarkFlow = false, canRefill = false,
        ) }
    }

    private fun invalidate(screens: Set<String>) {
        dirty.addAll(screens)
        // Invalidate kept selections too: a later open must reread, not treat them as authoritative.
        if ("portfolio" in screens) histories.replaceAllValues { it.markStale() }
        if ("pnl" in screens) lists.replaceAllValues { it.markStale() }
        if ("position" in screens) {
            summaries.replaceAllValues { it.markStale() }
            charts.replaceAllValues { it.markStale() }
        }
        mutableState.update { it.copy(
            overview = if ("portfolio" in screens) it.overview.markStale() else it.overview,
            history = if ("portfolio" in screens) it.history.markStale() else it.history,
            result = if ("portfolio" in screens) it.result.markStale() else it.result,
            positions = if ("pnl" in screens) it.positions.markStale() else it.positions,
            summary = if ("position" in screens) it.summary.markStale() else it.summary,
            chart = if ("position" in screens) it.chart.markStale() else it.chart,
            openOrders = if ("orders" in screens) it.openOrders.markStale() else it.openOrders,
            orderHistory = if ("orders" in screens) it.orderHistory.markStale() else it.orderHistory,
            trades = if ("trades" in screens) it.trades.markStale() else it.trades,
            cashflow = if ("cashflow" in screens) it.cashflow.markStale() else it.cashflow,
            flows = if ("cashflow" in screens) it.flows.markStale() else it.flows,
            sync = if ("sync" in screens) it.sync.markStale() else it.sync,
        ) }
        if (state.value.screen in screens && active) requestRefresh()
    }

    private fun recallPositions() {
        mutableState.update { it.copy(positions = lists[positionsKey(it)] ?: TradingRead.Loading) }
    }
    private fun recallCoin() {
        mutableState.update { it.copy(
            summary = summaries[summaryKey(it)] ?: TradingRead.Loading,
            chart = charts[chartKey(it)] ?: TradingRead.Loading,
        ) }
    }
    private fun forgetPositions() {
        lists.clear(); summaries.clear(); charts.clear()
        recallPositions(); recallCoin()
        dirty.addAll(setOf("portfolio", "pnl", "position"))
    }
    private fun positionsKey(value: TradingState): String = positionArgs(value, pnlPeriod.wire).toString()
    private fun summaryKey(value: TradingState): String = positionArgs(value, pnlPeriod.wire, value.coin).toString()
    private fun chartKey(value: TradingState): String = positionArgs(value, value.chartRange.wire, value.coin).toString()
}

private fun periodRange(period: String): JsonObject = buildJsonObject { put("period", period) }
private fun positionArgs(value: TradingState, period: String, coin: String? = null, includeAll: Boolean = false): JsonObject = buildJsonObject {
    put("range", periodRange(period))
    coin?.let { put("coin", it) }
    value.unit?.let { put("unit", it) }
    value.method?.let { put("method", it) }
    if (includeAll) { put("include_closed", true); put("include_stables", true) }
}

private fun <T> remember(kept: MutableMap<String, T>, key: String, value: T) {
    kept.remove(key)
    kept[key] = value
    while (kept.size > KEPT) kept.remove(kept.keys.first())
}
private fun <K, V> MutableMap<K, V>.replaceAllValues(transform: (V) -> V) {
    keys.toList().forEach { key -> this[key] = transform(getValue(key)) }
}
private fun <T> TradingRead<T>.markStale(): TradingRead<T> = if (this is TradingRead.Ready) copy(stale = true) else this

internal fun validConnectRequest(request: TradingConnectRequest): Boolean {
    fun validSecret(value: String, maximum: Int): Boolean = value.trim().let { text ->
        text.length in 10..maximum && text.all { it in 'A'..'Z' || it in 'a'..'z' || it in '0'..'9' }
    }
    return validSecret(request.apiKey, 64) && validSecret(request.apiSecret, 128) &&
        request.label.orEmpty().length <= 64 && (request.historyDays == null || request.historyDays in 1..730)
}

internal fun <T> tradingReadOutcome(
    outcome: InvocationOutcome,
    previous: TradingRead<T>,
    now: Long,
    decode: (JsonElement) -> T,
    empty: (T) -> Boolean = { false },
    freshness: (T) -> Freshness? = { null },
    configuration: Boolean = false,
): TradingRead<T> = when (outcome) {
    is InvocationOutcome.Completed -> runCatching { decode(outcome.data) }.fold(
        onSuccess = { TradingRead.Ready(it, empty(it), freshness(it)?.let { known -> isStale(known, now) } ?: false) },
        onFailure = { keepTradingValue(previous, PluginError("invalid_output")) },
    )
    is InvocationOutcome.Failed -> if (!configuration && outcome.error.code == "not_configured") TradingRead.NotConnected else keepTradingValue(previous, outcome.error)
    is InvocationOutcome.Rejected -> if (outcome.status == 403 || outcome.error.code in setOf("forbidden", "tool_unavailable")) TradingRead.Denied(outcome.error) else keepTradingValue(previous, outcome.error)
    is InvocationOutcome.Unknown -> keepTradingValue(previous, outcome.error ?: PluginError("network_unavailable"))
    is InvocationOutcome.Pending -> keepTradingValue(previous, PluginError("unexpected_state"))
}

private fun <T> keepTradingValue(previous: TradingRead<T>, error: PluginError): TradingRead<T> =
    if (previous is TradingRead.Ready) previous.copy(stale = true, problem = error) else TradingRead.Unavailable(error)

private fun InvocationOutcome.errorOrUnexpected(): PluginError = when (this) {
    is InvocationOutcome.Failed -> error
    is InvocationOutcome.Rejected -> error
    is InvocationOutcome.Unknown -> error ?: PluginError("network_unavailable")
    else -> PluginError("unexpected_state")
}

/** Typed event payloads choose only which reads to repeat; they never modify balances or positions. */
fun affectedTradingScreens(event: PluginEvent): Set<String> = when (event) {
    is PluginEvent.Ready, is PluginEvent.Resync -> SCREENS
    is PluginEvent.Denied -> emptySet()
    is PluginEvent.Delivered -> when (event.type) {
        "trading.connection.changed" -> SCREENS
        "trading.sync.progress" -> setOf("sync")
        "trading.data.changed" -> setOf("sync") + (event.payload["domains"] as? JsonArray).orEmpty().flatMap { domain ->
            when ((domain as? JsonPrimitive)?.takeIf { it.isString }?.content) {
                "wallet" -> listOf("portfolio", "pnl", "position")
                "executions" -> listOf("trades", "pnl", "position", "orders", "portfolio")
                "unified_ledger", "funding_ledger" -> listOf("cashflow", "portfolio", "pnl", "position")
                "funding_wallet", "earn" -> listOf("portfolio")
                "orders", "open_orders" -> listOf("orders")
                "deposits", "internal_deposits", "withdrawals" -> listOf("cashflow")
                else -> SCREENS.toList()
            }
        }.toSet()
        else -> emptySet()
    }
}

private fun pageArgs(filters: JsonObject, limit: Int, cursor: String? = null): JsonObject = buildJsonObject {
    filters.forEach { (key, value) -> put(key, value) }
    put("limit", limit)
    cursor?.let { put("cursor", it) }
}

private fun flowArgs(filter: String): JsonObject = buildJsonObject {
    when (filter) {
        "outside" -> put("internal", false)
        "money" -> put("kinds", JsonArray(listOf("deposit", "withdrawal", "p2p").map(::JsonPrimitive)))
        "transfers" -> put("kinds", JsonArray(listOf("transfer", "bot").map(::JsonPrimitive)))
        "earn" -> put("kinds", JsonArray(listOf("earn", "reward", "interest").map(::JsonPrimitive)))
    }
}

/** Editor fields are structured decimals; a comma keyboard input has the same meaning as on the web. */
fun tradingDecimalInput(text: String): String = text.trim().replace(',', '.')
fun validTradingDecimal(text: String?, positive: Boolean = false): Boolean {
    if (text == null || text.startsWith('-') || roundDecimal(text, 0) == null) return false
    return !positive || text.any { it in '1'..'9' }
}
