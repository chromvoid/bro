package assistant.mobile.trading

import assistant.mobile.pluginapi.InvocationOutcome
import assistant.mobile.pluginapi.PendingPluginWrite
import assistant.mobile.pluginapi.PluginCapabilities
import assistant.mobile.pluginapi.PluginConfiguration
import assistant.mobile.pluginapi.PluginError
import assistant.mobile.pluginapi.PluginEvent
import assistant.mobile.pluginapi.PluginHost
import assistant.mobile.pluginapi.PluginScreen
import assistant.mobile.pluginapi.PluginTool
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class TradingControllerTest {
    @Test
    fun portfolioPositionsAndCoinReadTheirContractsAndVisibleEventsCoalesce() = runTest {
        val host = TradingFakeHost()
        val controller = TradingController(backgroundScope, host) { fixtureNow }
        val visibilityOwner = Any()
        try {
            controller.activate(visibilityOwner)
            runCurrent()
            assertNotNull(controller.state.value.overview.valueOrNull()?.totalUsd)
            assertTrue(controller.state.value.history.valueOrNull()!!.points.isNotEmpty())
            assertEquals(listOf("portfolio.get", "portfolio.history", "positions.list"), host.invokes.map { it.first })
            controller.show("pnl")
            runCurrent()
            assertEquals("USD", controller.state.value.positions.valueOrNull()!!.unit)
            val positionsArgs = host.invokes.last().second
            assertEquals("true", positionsArgs["include_closed"].toString())
            assertEquals("true", positionsArgs["include_stables"].toString())
            controller.show("position", "LTC")
            runCurrent()
            assertEquals("LTC", controller.state.value.summary.valueOrNull()!!.position.coin)
            assertEquals("LTC", controller.state.value.chart.valueOrNull()!!.coin)
            assertTrue(samples(controller.state.value.chart.valueOrNull()!!).isNotEmpty())
            val chartArgs = host.invokes.last { it.first == "position.chart" }.second
            assertEquals("1y", chartArgs.getValue("range").jsonObject.getValue("period").jsonPrimitive.content)
            val before = host.invokes.size
            host.events.emit(walletEvent)
            host.events.emit(walletEvent)
            runCurrent()
            assertTrue(controller.state.value.live)
            advanceTimeBy(299)
            runCurrent()
            assertEquals(before, host.invokes.size)
            advanceTimeBy(1)
            runCurrent()
            assertEquals(before + 2, host.invokes.size)
            assertTrue(assertIs<TradingRead.Ready<PortfolioOverview>>(controller.state.value.overview).stale)
            host.events.emit(PluginEvent.Denied(403))
            runCurrent()
            assertIs<TradingRead.Denied>(controller.state.value.overview)
            assertIs<TradingRead.Denied>(controller.state.value.summary)
            val revoked = host.invokes.size
            controller.refresh()
            assertEquals(revoked, host.invokes.size)
            controller.deactivate(visibilityOwner)
            val stopped = host.invokes.size
            host.events.emit(walletEvent)
            advanceTimeBy(400)
            runCurrent()
            assertEquals(stopped, host.invokes.size)
            assertFalse(controller.state.value.live)
        } finally { controller.close() }
    }

    @Test
    fun outgoingDestinationCannotStopTheNewOwnersEventsOrVisibleRefresh() = runTest {
        val host = TradingFakeHost()
        val controller = TradingController(backgroundScope, host) { fixtureNow }
        val portfolioOwner = Any()
        val coinOwner = Any()
        try {
            controller.activate(portfolioOwner, "portfolio")
            runCurrent()
            assertEquals(1, host.events.subscriptionCount.value)
            controller.activate(coinOwner, "position", "LTC")
            runCurrent()
            assertEquals("position", controller.state.value.screen)
            assertEquals(1, host.events.subscriptionCount.value)
            controller.deactivate(portfolioOwner)
            controller.deactivate(portfolioOwner) // ON_STOP and disposal can both release the same entry.
            runCurrent()
            assertEquals(1, host.events.subscriptionCount.value)
            val summary = tradingData("position.summary", "ltc").jsonObject
            host.results["position.summary"] = InvocationOutcome.Completed(
                JsonObject(summary + ("open_qty" to JsonPrimitive("222"))),
            )
            val readsBefore = host.invokes.count { it.first == "position.summary" }
            host.events.emit(walletEvent)
            runCurrent()
            advanceTimeBy(300)
            runCurrent()
            assertEquals(readsBefore + 1, host.invokes.count { it.first == "position.summary" })
            assertEquals("222", controller.state.value.summary.valueOrNull()!!.position.openQty)
            assertEquals("position", controller.state.value.screen)
            assertTrue(controller.state.value.live)
            // Last-owner removal closes the subscription and cancels an event already being coalesced.
            host.events.emit(walletEvent)
            runCurrent()
            val stoppedReads = host.invokes.size
            controller.deactivate(coinOwner)
            runCurrent()
            assertEquals(0, host.events.subscriptionCount.value)
            assertFalse(controller.state.value.live)
            host.events.emit(walletEvent)
            advanceTimeBy(400)
            runCurrent()
            assertEquals(stoppedReads, host.invokes.size)
        } finally { controller.close() }
    }

    @Test
    fun outageKeepsKnownPortfolioUntilServiceSaysDisconnectedOrPermissionIsGone() = runTest {
        val host = TradingFakeHost()
        val controller = TradingController(backgroundScope, host) { fixtureNow }
        val visibilityOwner = Any()
        try {
            controller.activate(visibilityOwner)
            runCurrent()
            val value = controller.state.value.overview.valueOrNull()!!.totalUsd
            host.results["portfolio.get"] = InvocationOutcome.Unknown(error = PluginError("network_unavailable"))
            controller.refresh()
            val kept = assertIs<TradingRead.Ready<PortfolioOverview>>(controller.state.value.overview)
            assertEquals(value, kept.value.totalUsd)
            assertTrue(kept.stale)
            assertEquals("network_unavailable", kept.problem!!.code)
            host.results["portfolio.get"] = InvocationOutcome.Failed(PluginError("not_configured"))
            controller.refresh()
            assertIs<TradingRead.NotConnected>(controller.state.value.overview)
            host.results["portfolio.get"] = InvocationOutcome.Rejected(PluginError("forbidden"), 403)
            controller.refresh()
            assertIs<TradingRead.Denied>(controller.state.value.overview)
        } finally { controller.close() }
    }

    @Test
    fun connectionUsesCurrentRevisionWithoutKeepingSecretsAndRereadsAnUnknownOutcome() = runTest {
        val host = TradingFakeHost()
        val controller = TradingController(backgroundScope, host) { fixtureNow }
        val visibilityOwner = Any()
        try {
            controller.activate(visibilityOwner, "connect")
            runCurrent()
            assertFalse(controller.state.value.configuration.valueOrNull()!!.configured)
            val request = TradingConnectRequest("SyntheticKey12345", "SyntheticSecret12345", "mainnet", " Bybit ", 30)
            assertFalse(request.toString().contains(request.apiSecret))
            controller.connect(request)
            assertNull(host.puts.single().revision)
            assertEquals("Bybit", host.puts.single().settings.getValue("label").jsonPrimitive.content)
            assertEquals(30, host.puts.single().settings.getValue("history_days").jsonPrimitive.content.toInt())
            assertIs<ConnectNotice.Connected>(controller.state.value.notice)
            assertEquals(4L, controller.state.value.configuration.valueOrNull()!!.revision)
            assertFalse(controller.state.value.toString().contains(request.apiKey))
            assertFalse(controller.state.value.toString().contains(request.apiSecret))
            assertTrue(host.pendingWrites.value.isEmpty())
            val replacement = TradingConnectRequest("SyntheticOtherKey123", "SyntheticOtherSecret123", replaceAccount = true)
            host.unknownNextPut = true
            val readsBefore = host.configurationReads
            controller.connect(replacement)
            assertEquals(4L, host.puts.last().revision)
            assertEquals("true", host.puts.last().options!!.getValue("replace_account").toString())
            assertEquals(2, host.puts.size)
            assertEquals(readsBefore + 1, host.configurationReads)
            assertEquals(5L, controller.state.value.configuration.valueOrNull()!!.revision)
            assertIs<ConnectNotice.Unknown>(controller.state.value.notice)
            controller.disconnect()
            assertEquals(listOf(5L to true), host.deletes)
            assertIs<ConnectNotice.Disconnected>(controller.state.value.notice)
            assertFalse(controller.state.value.configuration.valueOrNull()!!.configured)
        } finally { controller.close() }
    }

    @Test
    fun acceptedAndUnknownPreferenceWritesResolveByLookupWithoutAnotherInvoke() = runTest {
        val host = TradingFakeHost()
        val controller = TradingController(backgroundScope, host) { fixtureNow }
        val visibilityOwner = Any()
        try {
            controller.activate(visibilityOwner, "pnl")
            runCurrent()
            host.results["positions.strategy"] = InvocationOutcome.Pending("strategy-key", "running")
            host.lookups["strategy-key"] = InvocationOutcome.Completed(buildJsonObject { put("strategy_coin", "BTC") }, clientKey = "strategy-key")
            controller.setStrategy("BTC")
            assertEquals(1, host.invokes.count { it.first == "positions.strategy" })
            assertEquals(listOf("strategy-key"), host.lookedUp)
            assertEquals(listOf("strategy-key"), host.acknowledged)
            host.results["positions.method"] = InvocationOutcome.Unknown("method-key")
            host.lookups["method-key"] = InvocationOutcome.Completed(buildJsonObject { put("lot_method", "fifo") }, clientKey = "method-key")
            controller.useMethod("fifo")
            assertEquals(1, host.invokes.count { it.first == "positions.method" })
            assertEquals(listOf("strategy-key", "method-key"), host.lookedUp)
            assertEquals(listOf("strategy-key", "method-key"), host.acknowledged)
            assertNull(controller.state.value.saveError)
            assertFalse(controller.state.value.acting)
        } finally { controller.close() }
    }

    @Test
    fun readOnlyCountingDoesNotRequireWriteRightsAndOwnCoinSkipsPriceChart() = runTest {
        val host = TradingFakeHost()
        host.capabilities.value = host.capabilities.value.copy(tools = host.capabilities.value.tools - setOf("positions.strategy", "positions.method"), configurationWritable = false)
        val controller = TradingController(backgroundScope, host) { fixtureNow }
        val visibilityOwner = Any()
        try {
            controller.activate(visibilityOwner, "pnl")
            runCurrent()
            assertFalse(controller.state.value.canSetStrategy)
            assertFalse(controller.state.value.configurationWritable)
            controller.count("LTC")
            runCurrent()
            controller.useMethod("fifo")
            assertEquals(0, host.invokes.count { it.first == "positions.method" })
            assertEquals("fifo", controller.state.value.method)
            controller.show("position", "LTC")
            runCurrent()
            assertEquals(0, host.invokes.count { it.first == "position.chart" })
            assertEquals("LTC", host.invokes.last { it.first == "position.summary" }.second.getValue("unit").jsonPrimitive.content)
        } finally { controller.close() }
    }

    @Test
    fun connectionInputValidationMatchesTheWebContract() {
        assertTrue(validConnectRequest(TradingConnectRequest("SyntheticKey123", "SyntheticSecret123", historyDays = 730)))
        assertFalse(validConnectRequest(TradingConnectRequest("short", "SyntheticSecret123")))
        assertFalse(validConnectRequest(TradingConnectRequest("SyntheticKey123", "secret-with-hyphen")))
        assertFalse(validConnectRequest(TradingConnectRequest("SyntheticKey123", "SyntheticSecret123", historyDays = 731)))
        assertFalse(validConnectRequest(TradingConnectRequest("SyntheticKey123", "SyntheticSecret123", label = "a".repeat(65))))
    }
}

internal val fixtureNow = requireNotNull(tradingTimeMillis("2026-09-25T10:00:00Z"))
private val walletEvent = PluginEvent.Delivered("event-1", "trading.data.changed", "2026-09-25T10:00:00Z", "1", buildJsonObject { put("domains", kotlinx.serialization.json.JsonArray(listOf(JsonPrimitive("wallet")))) })

internal class TradingFakeHost : PluginHost {
    override val installationId = "synthetic-trading"
    override val packageId = "trading"
    override val locale = "ru"
    override val capabilities = MutableStateFlow(PluginCapabilities(
        "ready", null, listOf("trading.read", "trading.write"),
        (TRADING_REQUIRED_TOOLS + mapOf("positions.strategy" to 1, "positions.method" to 1, "positions.core" to 1, "flows.mark" to 1, "sync.refill" to 1)).mapValues { (id, major) -> PluginTool(id, "$major.0.0", if (id in TRADING_REQUIRED_TOOLS) "read" else "write") },
        true, listOf("trading.data.changed", "trading.connection.changed"),
    ))
    override val pendingWrites = MutableStateFlow<List<PendingPluginWrite>>(emptyList())
    val invokes = mutableListOf<Pair<String, JsonObject>>()
    val events = MutableSharedFlow<PluginEvent>(extraBufferCapacity = 8)
    val results = mutableMapOf<String, InvocationOutcome>()
    var response: (suspend (String, JsonObject) -> InvocationOutcome?)? = null
    val retried = mutableListOf<String>()
    val retries = mutableMapOf<String, InvocationOutcome>()
    val lookups = mutableMapOf<String, InvocationOutcome>()
    val lookedUp = mutableListOf<String>()
    val acknowledged = mutableListOf<String>()
    var configured = false
    var revision = 3L
    var configurationReads = 0
    var unknownNextPut = false
    data class Put(val settings: JsonObject, val revision: Long?, val options: JsonObject?)
    val puts = mutableListOf<Put>()
    val deletes = mutableListOf<Pair<Long, Boolean>>()
    private fun configurationView(): JsonElement = buildJsonObject {
        put("status", if (configured) "configured" else "not_configured")
        put("revision", revision)
        put("public", buildJsonObject { put("environment", "mainnet") })
        put("connection_state", if (configured) "active" else "not_configured")
        put("secret_state", buildJsonObject { put("api_key", buildJsonObject { put("present", configured); if (configured) put("hint", "…1234") }) })
    }
    override val configuration = object : PluginConfiguration {
        override suspend fun get(): InvocationOutcome { configurationReads++; return InvocationOutcome.Completed(configurationView()) }
        override suspend fun put(settings: JsonObject, secrets: Map<String, String>?, expectedRevision: Long?, options: JsonObject?): InvocationOutcome {
            puts += Put(settings, expectedRevision, options)
            assertEquals(setOf("api_key", "api_secret"), secrets!!.keys)
            configured = true
            revision++
            if (unknownNextPut) { unknownNextPut = false; return InvocationOutcome.Unknown(error = PluginError("network_unavailable")) }
            return InvocationOutcome.Completed(configurationView())
        }
        override suspend fun delete(expectedRevision: Long, purge: Boolean): InvocationOutcome {
            deletes += expectedRevision to purge
            configured = false
            revision++
            return InvocationOutcome.Completed(configurationView())
        }
    }
    override suspend fun invoke(tool: String, args: JsonObject): InvocationOutcome {
        invokes += tool to args
        val selected = response?.invoke(tool, args) ?: results[tool]
        if (selected != null) {
            val key = when (selected) {
                is InvocationOutcome.Pending -> selected.clientKey
                is InvocationOutcome.Unknown -> selected.clientKey
                else -> null
            }
            if (key != null && pendingWrites.value.none { it.clientKey == key }) {
                pendingWrites.value += PendingPluginWrite(key, tool, args, if (selected is InvocationOutcome.Pending) "running" else "unknown", 0, false, selected is InvocationOutcome.Pending)
            }
            return selected
        }
        val example = when (tool) {
            "portfolio.get" -> "overview"
            "portfolio.history" -> "week"
            "positions.list" -> "all"
            "position.summary", "position.chart" -> "ltc"
            "orders.open" -> "one"
            "orders.history" -> "filled"
            "executions.list" -> "recent"
            "cashflow.list", "flows.list" -> "year"
            "sync.status" -> "live"
            "sync.refill" -> "executions"
            "symbols.list" -> "all"
            "positions.core" -> "gram"
            "flows.mark" -> "own-wallet"
            "positions.strategy" -> "btc"
            "positions.method" -> "fifo"
            else -> error("Unexpected fake tool")
        }
        return InvocationOutcome.Completed(tradingData(tool, example))
    }
    override suspend fun lookup(clientKey: String): InvocationOutcome {
        lookedUp += clientKey
        val outcome = lookups[clientKey] ?: InvocationOutcome.Unknown(clientKey, notFound = true)
        if (outcome is InvocationOutcome.Unknown) pendingWrites.value = pendingWrites.value.map { if (it.clientKey == clientKey) it.copy(notFound = outcome.notFound) else it }
        return outcome
    }
    override suspend fun retry(clientKey: String): InvocationOutcome {
        retried += clientKey
        return retries[clientKey] ?: error("No retry configured")
    }
    override suspend fun acknowledge(clientKey: String) {
        acknowledged += clientKey
        pendingWrites.value = pendingWrites.value.filterNot { it.clientKey == clientKey }
    }
    override fun observe(): Flow<PluginEvent> = events
    override fun navigate(destination: PluginScreen) = Unit
    override fun close() = Unit
}
