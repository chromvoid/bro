package assistant.mobile.trading

import assistant.mobile.pluginapi.InvocationOutcome
import assistant.mobile.pluginapi.PluginError
import assistant.mobile.pluginapi.PluginEvent
import java.io.File
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

internal fun tradingFixture(tool: String, name: String): JsonObject = requireNotNull(
    TradingContractsTest::class.java.classLoader.getResourceAsStream("examples/$tool/$name.json"),
).use { TradingJson.parseToJsonElement(it.readBytes().decodeToString()).jsonObject }

internal fun tradingData(tool: String, name: String): JsonElement = tradingFixture(tool, name).getValue("data")

class TradingContractsTest {
    @Test
    fun everyPackageExampleDecodesAndEveryManifestToolHasAnExample() {
        val examples = File(requireNotNull(javaClass.classLoader.getResource("examples")).toURI())
        val files = examples.walkTopDown().filter { it.isFile && it.extension == "json" }.toList()
        val covered = mutableSetOf<String>()
        var dataCount = 0
        var errorCount = 0
        for (file in files) {
            val tool = file.parentFile.name
            covered += tool
            val example = TradingJson.parseToJsonElement(file.readText()).jsonObject
            assertIs<JsonObject>(example["arguments"], file.path)
            val data = example["data"]
            if (data != null) {
                assertNotNull(decodeTradingToolResult(tool, data), file.path)
                dataCount++
            } else {
                val error = assertIs<JsonObject>(example["error"], file.path)
                assertTrue(error.getValue("code").jsonPrimitive.content.isNotBlank())
                assertTrue(error.getValue("message").jsonPrimitive.content.isNotBlank())
                assertEquals("none", error.getValue("recovery").jsonPrimitive.content)
                errorCount++
            }
        }
        val manifest = requireNotNull(javaClass.classLoader.getResourceAsStream("manifest.json")).use {
            TradingJson.parseToJsonElement(it.readBytes().decodeToString()).jsonObject
        }
        val declared = (manifest.getValue("tools") as JsonArray).map { it.jsonObject.getValue("id").jsonPrimitive.content }.toSet()
        assertEquals(declared, covered)
        assertEquals(17, covered.size)
        assertEquals(21, files.size)
        assertEquals(20, dataCount)
        assertEquals(1, errorCount)
    }

    @Test
    fun positionUnknownValuesStayUnknownAndNewCodesRemainReadable() {
        val summary = decodePositionSummary(tradingData("position.summary", "incomplete"))
        assertNull(summary.position.realized)
        assertTrue(summary.position.issues.isNotEmpty())
        val original = tradingData("positions.list", "all").jsonObject
        val future = JsonObject(original + ("lot_method" to JsonPrimitive("future_method")))
        assertEquals("future_method", decodePositionsList(future).lotMethod)
        val chart = tradingData("position.chart", "ltc").jsonObject
        val trades = chart.getValue("trades") as JsonArray
        val trade = JsonObject(trades.first().jsonObject + ("side" to JsonPrimitive("future_side")))
        val parsed = decodePositionChart(JsonObject(chart + ("trades" to JsonArray(listOf(trade)))))
        assertEquals("future_side", parsed.trades.single().side)
        assertFails { decodePositionSummary(buildJsonObject { put("coin", "LTC") }) }
        assertFails { decodeTradingToolResult("unknown.tool", original) }
    }

    @Test
    fun decimalFormatsRoundHalfAwayFromZeroWithoutBinaryLoss() {
        assertEquals("123456789012345678901234567891", roundDecimal("123456789012345678901234567890.5", 0))
        assertEquals("-1.01", roundDecimal("-1.005", 2))
        assertEquals("0", roundDecimal("-0.0001", 2))
        assertEquals("1", roundDecimal("0.999", 2))
        assertEquals("9\u00a0999,5\u00a0$", usd("9999.5"))
        assertEquals("+0,0125\u00a0BTC", inUnit("0.012500001", "BTC", true))
        assertEquals("0,0007123\u00a0$", price("0.000712345", "USD"))
        assertEquals("+1\u00a0500\u00a0$", usdShort("1499.99", true))
        assertEquals("—", usd(null))
        assertEquals("—", amount("NaN"))
        listOf("", ".5", "1.", "+1", "1e3", "1,5", "-", "1.2.3").forEach { assertNull(roundDecimal(it, 2), it) }
    }

    @Test
    fun freshnessUsesSnapshotAndConnectionStateInsteadOfFillingMissingNumbers() {
        val now = assertNotNull(tradingTimeMillis("2026-09-25T10:00:00Z"))
        val fresh = Freshness("2026-09-25T09:51:00Z", "active")
        assertFalse(isStale(fresh, now))
        assertFalse(isStale(fresh.copy(connectionState = "validating"), now))
        assertTrue(isStale(fresh.copy(connectionState = "future_state"), now))
        assertTrue(isStale(fresh.copy(asOf = "2026-09-25T09:49:59Z"), now))
        assertTrue(isStale(fresh.copy(asOf = null), now))
        assertTrue(isStale(fresh.copy(asOf = "invalid"), now))
        val history = PortfolioHistory(listOf(HistoryPoint("invalid", "10"), HistoryPoint("2026-09-25T09:50:00Z", "NaN"), HistoryPoint("2026-09-25T10:00:00Z", "15", rebuilt = true)), freshness = fresh)
        assertEquals(1, samples(history).size)
        assertTrue(samples(history).single().rebuilt)
        assertEquals(15.0, samples(history).single().value)
    }

    @Test
    fun failedRefreshKeepsLastKnownValueButDeniedAndDisconnectedReplaceIt() {
        val known = TradingRead.Ready(decodePortfolioOverview(tradingData("portfolio.get", "overview")))
        val outage = tradingReadOutcome(InvocationOutcome.Unknown(error = PluginError("network_unavailable")), known, 0, ::decodePortfolioOverview)
        assertIs<TradingRead.Ready<PortfolioOverview>>(outage)
        assertEquals(known.value.totalUsd, outage.value.totalUsd)
        assertTrue(outage.stale)
        assertEquals("network_unavailable", outage.problem!!.code)
        val invalid = tradingReadOutcome(InvocationOutcome.Completed(JsonObject(emptyMap())), known, 0, ::decodePortfolioOverview)
        assertEquals("invalid_output", assertIs<TradingRead.Ready<PortfolioOverview>>(invalid).problem!!.code)
        val denied = tradingReadOutcome(InvocationOutcome.Rejected(PluginError("forbidden"), 403), known, 0, ::decodePortfolioOverview)
        assertIs<TradingRead.Denied>(denied)
        assertIs<TradingRead.NotConnected>(tradingReadOutcome(InvocationOutcome.Failed(PluginError("not_configured")), known, 0, ::decodePortfolioOverview))
    }

    @Test
    fun eventsOnlySelectAffectedReadsAndUnknownDomainsInvalidateAllScreens() {
        fun event(type: String, domains: List<JsonElement>) = PluginEvent.Delivered("event", type, "2026-09-25T10:00:00Z", "cursor", buildJsonObject { put("domains", JsonArray(domains)) })
        assertEquals(setOf("portfolio", "pnl", "position", "sync"), affectedTradingScreens(event("trading.data.changed", listOf(JsonPrimitive("wallet")))))
        assertEquals(setOf("portfolio", "sync"), affectedTradingScreens(event("trading.data.changed", listOf(JsonPrimitive("earn")))))
        assertEquals(setOf("orders", "sync"), affectedTradingScreens(event("trading.data.changed", listOf(JsonPrimitive("orders")))))
        assertEquals(setOf("portfolio", "pnl", "position", "orders", "trades", "cashflow", "sync", "connect"), affectedTradingScreens(event("trading.data.changed", listOf(JsonPrimitive("future_domain")))))
        assertEquals(emptySet(), affectedTradingScreens(event("future.event", emptyList())))
        assertEquals(emptySet(), affectedTradingScreens(PluginEvent.Denied(403)))
    }
}
