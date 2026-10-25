package assistant.mobile.trading

import assistant.mobile.pluginapi.InvocationOutcome
import assistant.mobile.pluginapi.PendingPluginWrite
import assistant.mobile.pluginapi.PluginError
import assistant.mobile.pluginapi.PluginEvent
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
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
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class TradingOperationsTest {
    @Test
    fun newRoutesReadPackageExamplesAndKeepDecimalValuesAndTotals() = runTest {
        val host = TradingFakeHost()
        val controller = TradingController(backgroundScope, host) { fixtureNow }
        try {
            controller.activate(Any(), "orders")
            runCurrent()
            assertEquals(decodeTradingOrderPage(tradingData("orders.open", "one")), controller.state.value.openOrders.valueOrNull())
            assertEquals(decodeTradingOrderPage(tradingData("orders.history", "filled")), controller.state.value.orderHistory.valueOrNull())
            assertEquals("50", host.invokes.first { it.first == "orders.open" }.second["limit"].toString())
            controller.show("trades")
            runCurrent()
            assertEquals(decodeTradingExecutionPage(tradingData("executions.list", "recent")), controller.state.value.trades.valueOrNull())
            controller.show("cashflow")
            runCurrent()
            assertEquals(decodeTradingCashflowPage(tradingData("cashflow.list", "year")), controller.state.value.cashflow.valueOrNull())
            assertEquals(decodeTradingFlowsPage(tradingData("flows.list", "year")), controller.state.value.flows.valueOrNull())
            assertEquals("1000.06", controller.state.value.flows.valueOrNull()!!.totals.usd!!.net)
            controller.selectFlowFilter("outside")
            runCurrent()
            assertEquals("false", host.invokes.last { it.first == "flows.list" }.second["internal"].toString())
            controller.selectFlowFilter("money")
            runCurrent()
            assertEquals(listOf("deposit", "withdrawal", "p2p"), (host.invokes.last { it.first == "flows.list" }.second["kinds"] as JsonArray).map { it.jsonPrimitive.content })
            controller.show("sync")
            runCurrent()
            assertEquals(decodeTradingSyncStatus(tradingData("sync.status", "live")), controller.state.value.sync.valueOrNull())
            controller.show("connect")
            runCurrent()
            assertEquals(decodeTradingSymbols(tradingData("symbols.list", "all")), controller.state.value.symbols.valueOrNull())
        } finally { controller.close() }
    }

    @Test
    fun paginationAppendsAndRefreshRetainsTheNumberOfShownRows() = runTest {
        val host = TradingFakeHost()
        val example = tradingData("orders.history", "filled").jsonObject
        val order = (example.getValue("items") as JsonArray).first().jsonObject
        host.response = { tool, args ->
            if (tool != "orders.history") null else {
                val offset = if (args["cursor"] == null) 0 else 50
                val limit = args.getValue("limit").jsonPrimitive.content.toInt()
                val count = minOf(limit, 75 - offset)
                InvocationOutcome.Completed(JsonObject(example + mapOf(
                    "items" to JsonArray((offset until offset + count).map { JsonObject(order + ("order_id" to JsonPrimitive("synthetic-$it"))) }),
                    "next_cursor" to if (offset + count < 75) JsonPrimitive("second") else JsonNull,
                )))
            }
        }
        val controller = TradingController(backgroundScope, host) { fixtureNow }
        try {
            controller.activate(Any(), "orders")
            runCurrent()
            assertEquals(50, controller.state.value.orderHistory.valueOrNull()!!.items.size)
            controller.moreOrders(history = true)
            assertEquals(75, controller.state.value.orderHistory.valueOrNull()!!.items.size)
            assertEquals("second", host.invokes.last { it.first == "orders.history" }.second["cursor"]!!.jsonPrimitive.content)
            controller.refresh()
            assertEquals(75, controller.state.value.orderHistory.valueOrNull()!!.items.size)
            assertEquals("75", host.invokes.last { it.first == "orders.history" }.second.getValue("limit").toString())
            assertNull(controller.state.value.orderHistory.valueOrNull()!!.nextCursor)
        } finally { controller.close() }
    }

    @Test
    fun paginationFailureKeepsRowsAndARefreshDiscardsAnOldCursorResponse() = runTest {
        val host = TradingFakeHost()
        val initial = JsonObject(tradingData("executions.list", "recent").jsonObject + ("next_cursor" to JsonNull))
        val paged = JsonObject(initial + ("next_cursor" to JsonPrimitive("next")))
        host.results["executions.list"] = InvocationOutcome.Completed(paged)
        val controller = TradingController(backgroundScope, host) { fixtureNow }
        try {
            controller.activate(Any(), "trades")
            runCurrent()
            val before = controller.state.value.trades.valueOrNull()!!.items
            host.results["executions.list"] = InvocationOutcome.Unknown(error = PluginError("network_unavailable"))
            controller.moreTrades()
            assertEquals(before, controller.state.value.trades.valueOrNull()!!.items)
            assertEquals("network_unavailable", controller.state.value.moreError!!.code)
            val deferred = CompletableDeferred<InvocationOutcome>()
            host.response = { tool, args -> if (tool == "executions.list" && args["cursor"] != null) deferred.await() else null }
            host.results["executions.list"] = InvocationOutcome.Completed(initial)
            val more = async { controller.moreTrades() }
            runCurrent()
            controller.refresh()
            deferred.complete(InvocationOutcome.Completed(initial))
            more.await()
            assertEquals(before, controller.state.value.trades.valueOrNull()!!.items)
            assertNull(controller.state.value.trades.valueOrNull()!!.nextCursor)
            assertFalse(controller.state.value.loadingMore)
        } finally { controller.close() }
    }

    @Test
    fun coreAndFlowMarksResolveAcceptedAndLostResponsesWithTheOriginalKey() = runTest {
        val host = TradingFakeHost()
        val controller = TradingController(backgroundScope, host) { fixtureNow }
        try {
            controller.activate(Any(), "position", "GRAM")
            runCurrent()
            host.results["positions.core"] = InvocationOutcome.Pending("core-key", "running")
            host.lookups["core-key"] = InvocationOutcome.Completed(tradingData("positions.core", "gram"), clientKey = "core-key")
            assertTrue(controller.setCore("GRAM", " 25,5 ", "2026-09-01T00:00:00Z"))
            assertEquals("25.5", host.invokes.single { it.first == "positions.core" }.second.getValue("qty").jsonPrimitive.content)
            controller.show("cashflow")
            runCurrent()
            host.results["flows.mark"] = InvocationOutcome.Unknown("mark-key")
            host.lookups["mark-key"] = InvocationOutcome.Completed(tradingData("flows.mark", "own-wallet"), clientKey = "mark-key")
            assertTrue(controller.markFlow("synthetic-flow", "external", "1,25"))
            assertEquals("1.25", host.invokes.single { it.first == "flows.mark" }.second.getValue("unit_cost_usd").jsonPrimitive.content)
            assertEquals(listOf("core-key", "mark-key"), host.lookedUp)
            assertEquals(listOf("core-key", "mark-key"), host.acknowledged)
            assertTrue(host.pendingWrites.value.isEmpty())
            assertNull(controller.state.value.flowMarkError)
        } finally { controller.close() }
    }

    @Test
    fun unresolvedEditorRestoresFromJournalAndNeverSubmitsASecondWrite() = runTest {
        val host = TradingFakeHost()
        var controller = TradingController(backgroundScope, host) { fixtureNow }
        try {
            controller.activate(Any(), "position", "GRAM")
            runCurrent()
            host.results["positions.core"] = InvocationOutcome.Unknown("core-unknown")
            assertFalse(controller.setCore("GRAM", "10", "2026-09-01T00:00:00Z"))
            assertFalse(controller.setCore("GRAM", "20", "2026-09-01T00:00:00Z"))
            assertEquals(1, host.invokes.count { it.first == "positions.core" })
            controller.close()
            controller = TradingController(backgroundScope, host) { fixtureNow }
            controller.activate(Any(), "position", "GRAM")
            runCurrent()
            val recovered = controller.state.value.pendingWrites.single()
            assertEquals("core-unknown", recovered.clientKey)
            assertEquals("10", recovered.arguments.getValue("qty").jsonPrimitive.content)
            host.lookups[recovered.clientKey] = InvocationOutcome.Completed(tradingData("positions.core", "gram"), clientKey = recovered.clientKey)
            assertTrue(controller.checkWrite(recovered.clientKey))
            assertEquals(1, host.invokes.count { it.first == "positions.core" })
            assertEquals(listOf("core-unknown", "core-unknown"), host.lookedUp)
            assertTrue(host.pendingWrites.value.isEmpty())
        } finally { controller.close() }
    }

    @Test
    fun unresolvedPreferencesAndFlowMarksBlockDuplicatesUntilLookupCompletes() = runTest {
        val host = TradingFakeHost()
        val controller = TradingController(backgroundScope, host) { fixtureNow }
        try {
            controller.activate(Any(), "pnl")
            runCurrent()
            host.results["positions.strategy"] = InvocationOutcome.Unknown("strategy-unknown")
            controller.setStrategy("BTC")
            controller.setStrategy("LTC")
            host.results["positions.method"] = InvocationOutcome.Pending("method-pending", "running")
            controller.useMethod("fifo")
            controller.useMethod("lifo")
            host.results["flows.mark"] = InvocationOutcome.Unknown("mark-unknown")
            assertFalse(controller.markFlow("movement", "own_wallet"))
            assertFalse(controller.markFlow("movement", "external", "1"))
            assertEquals(1, host.invokes.count { it.first == "positions.strategy" })
            assertEquals(1, host.invokes.count { it.first == "positions.method" })
            assertEquals(1, host.invokes.count { it.first == "flows.mark" })
            assertEquals("movement", controller.state.value.flowMarkError!!.id)
            host.lookups["mark-unknown"] = InvocationOutcome.Failed(PluginError("invalid_argument"), clientKey = "mark-unknown")
            assertFalse(controller.checkWrite("mark-unknown"))
            assertEquals("invalid_argument", controller.state.value.flowMarkError!!.error.code)
            assertTrue("mark-unknown" in host.acknowledged)
            assertFalse(host.pendingWrites.value.any { it.tool == "flows.mark" })
        } finally { controller.close() }
    }

    @Test
    fun refillPendingIsLookedUpAndEventsResolveTheJournalWithoutAnotherPost() = runTest {
        val host = TradingFakeHost()
        val controller = TradingController(backgroundScope, host) { fixtureNow }
        try {
            controller.activate(Any(), "sync")
            runCurrent()
            host.results["sync.refill"] = InvocationOutcome.Pending("refill-key", "running")
            host.lookups["refill-key"] = InvocationOutcome.Pending("refill-key", "running")
            controller.refill()
            runCurrent()
            assertEquals(listOf("refill-key"), host.lookedUp)
            assertEquals("refill-key", controller.state.value.refills.single().clientKey)
            controller.refill()
            assertEquals(1, host.invokes.count { it.first == "sync.refill" })
            host.lookups["refill-key"] = InvocationOutcome.Completed(tradingData("sync.refill", "executions"), clientKey = "refill-key")
            host.events.emit(PluginEvent.Delivered("sync-event", "trading.sync.progress", "2026-09-25T10:00:00Z", "cursor", JsonObject(emptyMap())))
            runCurrent()
            advanceTimeBy(5001)
            runCurrent()
            assertIs<RefillNotice.Done>(controller.state.value.refillNotice)
            assertTrue(host.pendingWrites.value.isEmpty())
            assertEquals(1, host.invokes.count { it.first == "sync.refill" })
            assertEquals(listOf("refill-key"), host.acknowledged)
            assertTrue(host.invokes.count { it.first == "sync.status" } >= 2)
        } finally { controller.close() }
    }

    @Test
    fun refillUnknownOnlyOffersExplicitSameKeyRetryAfterDefiniteMissingLookup() = runTest {
        val host = TradingFakeHost()
        val controller = TradingController(backgroundScope, host) { fixtureNow }
        try {
            controller.activate(Any(), "sync")
            runCurrent()
            host.results["sync.refill"] = InvocationOutcome.Unknown("lost-refill")
            host.lookups["lost-refill"] = InvocationOutcome.Unknown("lost-refill", notFound = false)
            controller.refill()
            controller.resendRefill("lost-refill")
            assertTrue(host.retried.isEmpty())
            host.lookups["lost-refill"] = InvocationOutcome.Unknown("lost-refill", notFound = true)
            controller.checkRefill("lost-refill")
            host.retries["lost-refill"] = InvocationOutcome.Completed(tradingData("sync.refill", "executions"), clientKey = "lost-refill")
            controller.resendRefill("lost-refill")
            assertEquals(listOf("lost-refill"), host.retried)
            assertEquals(1, host.invokes.count { it.first == "sync.refill" })
            assertIs<RefillNotice.Done>(controller.state.value.refillNotice)
            controller.dismissRefillNotice()
            assertNull(controller.state.value.refillNotice)
            host.results["sync.refill"] = InvocationOutcome.Rejected(PluginError("forbidden"), 403)
            controller.refill()
            assertIs<RefillNotice.Refused>(controller.state.value.refillNotice)
            assertEquals("forbidden", (controller.state.value.refillNotice as RefillNotice.Refused).error.code)
        } finally { controller.close() }
    }

    @Test
    fun deniedAccessClearsEveryNewReadAndJournalViewAndStopsWrites() = runTest {
        val host = TradingFakeHost()
        host.pendingWrites.value = listOf(PendingPluginWrite("restored", "positions.core", buildJsonObject { put("coin", "GRAM") }, "unknown", 0, false, false))
        val controller = TradingController(backgroundScope, host) { fixtureNow }
        try {
            controller.activate(Any(), "cashflow")
            runCurrent()
            assertTrue(controller.state.value.flows is TradingRead.Ready)
            assertEquals("restored", controller.state.value.pendingWrites.single().clientKey)
            host.events.emit(PluginEvent.Denied(403))
            runCurrent()
            assertIs<TradingRead.Denied>(controller.state.value.flows)
            assertIs<TradingRead.Denied>(controller.state.value.cashflow)
            assertIs<TradingRead.Denied>(controller.state.value.openOrders)
            assertIs<TradingRead.Denied>(controller.state.value.orderHistory)
            assertIs<TradingRead.Denied>(controller.state.value.trades)
            assertIs<TradingRead.Denied>(controller.state.value.sync)
            assertIs<TradingRead.Denied>(controller.state.value.symbols)
            assertTrue(controller.state.value.pendingWrites.isEmpty())
            assertFalse(controller.state.value.canSetCore)
            assertFalse(controller.state.value.canMarkFlow)
            assertFalse(controller.state.value.canRefill)
            assertFalse(controller.markFlow("movement", "auto"))
            controller.refill()
            assertTrue(host.invokes.none { it.first == "flows.mark" || it.first == "sync.refill" })
        } finally { controller.close() }
    }

    @Test
    fun editorValidationAndUnknownServiceCodesStayReadable() {
        assertEquals("12.5", tradingDecimalInput(" 12,5 "))
        assertTrue(validTradingDecimal("0"))
        assertFalse(validTradingDecimal("0.00", positive = true))
        assertTrue(validTradingDecimal("0.0001", positive = true))
        listOf("-1", "+1", "1e3", ".5", "1.", "NaN", "").forEach { assertFalse(validTradingDecimal(it), it) }
        val original = tradingData("orders.open", "one").jsonObject
        val item = (original.getValue("items") as JsonArray).first().jsonObject
        val future = JsonObject(original + ("items" to JsonArray(listOf(JsonObject(item + mapOf("side" to JsonPrimitive("future_side"), "status" to JsonPrimitive("future_status")))))))
        assertEquals("future_status", decodeTradingOrderPage(future).items.single().status)
        assertEquals("future_side", decodeTradingOrderPage(future).items.single().side)
        val sync = tradingData("sync.status", "live").jsonObject
        val futureSync = JsonObject(sync + ("stream" to buildJsonObject { put("state", "future_stream") }))
        assertEquals("future_stream", decodeTradingSyncStatus(futureSync).stream.state)
    }
}
