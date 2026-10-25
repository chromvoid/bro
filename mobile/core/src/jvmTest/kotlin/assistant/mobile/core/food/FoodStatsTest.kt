package assistant.mobile.core.food

import assistant.mobile.core.FEATURE_NOW
import assistant.mobile.core.FEATURE_ORIGIN
import assistant.mobile.core.FeatureSessionSecrets
import assistant.mobile.core.NativeHttpClient
import assistant.mobile.core.SessionManager
import assistant.mobile.core.featureRecord
import assistant.mobile.core.files.ExportFile
import assistant.mobile.core.files.FileExportDestination
import assistant.mobile.core.files.FileExportPort
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockEngineConfig
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class FoodStatsTest {
    @Test
    fun periodsMatchWebCalendarFixtureAndCrossYearLeapBoundaries() {
        val week = FoodStatsPeriod(FoodStatsPeriodKind.Week, "2026-10-12", "2026-10-18")
        repeat(7) { offset ->
            assertEquals(week, foodStatsPeriod(FoodStatsPeriodKind.Week, shiftFoodDay(week.start, offset)))
        }
        assertEquals(FoodStatsPeriod(FoodStatsPeriodKind.Week, "2026-09-28", "2026-10-04"),
            foodStatsPeriod(FoodStatsPeriodKind.Week, "2026-10-03"))
        val month = foodStatsPeriod(FoodStatsPeriodKind.Month, "2026-02-14")
        assertEquals(FoodStatsPeriod(FoodStatsPeriodKind.Month, "2026-02-01", "2026-02-28"), month)
        assertEquals(FoodStatsPeriod(FoodStatsPeriodKind.Month, "2026-01-01", "2026-01-31"), shiftFoodStatsPeriod(month, -1))
        assertEquals(FoodStatsPeriod(FoodStatsPeriodKind.Month, "2026-03-01", "2026-03-31"), shiftFoodStatsPeriod(month, 1))
        assertEquals("2024-02-29", foodStatsPeriod(FoodStatsPeriodKind.Month, "2024-02-17").end)
        assertEquals("2027-01-01", shiftFoodStatsPeriod(foodStatsPeriod(FoodStatsPeriodKind.Month, "2026-12-15"), 1).start)
    }

    @Test
    fun readPermissionAndExpiredAccountDoNotExposeCachedStatisticsOrExport() = runTest {
        val fixture = FoodStatsFixture(this, canRead = false)
        try {
            fixture.start("2026-10-12")
            fixture.controller.export(FoodStatsExportFormat.Csv, FileExportDestination.SAVE)
            assertTrue(fixture.requests.isEmpty())
            assertNull(fixture.controller.state.value!!.range.data)
            fixture.sessions.expire(requireNotNull(fixture.sessions.lease()))
            runCurrent()
            assertNull(fixture.controller.state.value)
            assertTrue(fixture.exports.files.isEmpty())
        } finally { fixture.http.close() }
    }
}

private fun statsDay(
    date: String,
    logged: Boolean = false,
    energy: String? = "0",
    protein: String? = "0",
    completed: Boolean = false,
    target: String? = null,
    macroTarget: String? = null,
    energyComplete: Boolean = true,
    proteinComplete: Boolean = true,
    kinds: Map<String, String> = emptyMap(),
): JsonObject = buildJsonObject {
    put("date", date); put("logged", logged); put("energy_kcal", energy?.let(::JsonPrimitive) ?: JsonNull)
    put("energy_complete", energyComplete); put("protein_g", protein?.let(::JsonPrimitive) ?: JsonNull)
    put("protein_complete", proteinComplete); put("fat_g", "0"); put("carbohydrate_g", "0")
    put("completed", completed); put("target", target?.let(::JsonPrimitive) ?: JsonNull)
    put("macro_targets", if (macroTarget == null) JsonNull else buildJsonObject { put("protein_g", macroTarget) })
    put("kcal_by_kind", buildJsonObject { kinds.forEach { (kind, amount) -> put(kind, amount) } })
}

private fun statsBytes(rows: List<JsonObject>): ByteArray = buildJsonObject {
    put("days", buildJsonArray { rows.forEach { add(it) } })
}.toString().encodeToByteArray()

private class StatsTestExports : FileExportPort {
    val files = mutableListOf<ExportFile>()
    val destinations = mutableListOf<FileExportDestination>()
    override suspend fun export(file: ExportFile, destination: FileExportDestination): Boolean {
        files += file; destinations += destination; return true
    }
    override fun clear() = Unit
}

@OptIn(ExperimentalCoroutinesApi::class)
private class FoodStatsFixture(val test: TestScope, canRead: Boolean = true) {
    private val record = featureRecord().let { it.copy(identity = it.identity.copy(scopes = if (canRead) listOf("nutrition.read") else emptyList())) }
    val sessions = SessionManager("synthetic", FEATURE_ORIGIN, FeatureSessionSecrets(record), {}, { FEATURE_NOW })
    val requests = mutableListOf<HttpRequestData>()
    val exports = StatsTestExports()
    var rejectExport = false
    var emptyCsv = false
    val http = NativeHttpClient(MockEngine(MockEngineConfig().apply {
        dispatcher = StandardTestDispatcher(test.testScheduler)
        addHandler { request ->
            requests += request
            when (request.url.encodedPath) {
                "/v1/nutrition/range" -> {
                    val start = requireNotNull(request.url.parameters["start"])
                    val end = requireNotNull(request.url.parameters["end"])
                    val rows = buildList {
                        var date = start
                        while (date <= end) {
                            add(statsDay(date, logged = date <= shiftFoodDay(start, 1),
                                energy = if (date == start) "1900" else if (date == shiftFoodDay(start, 1)) "2500" else "0"))
                            date = shiftFoodDay(date, 1)
                        }
                    }
                    respond(statsBytes(rows))
                }
                "/v1/nutrition/export" -> if (rejectExport) respond("{}", HttpStatusCode.ServiceUnavailable)
                    else respond(if (request.url.parameters["format"] == "csv") {
                        if (emptyCsv) "" else "date,energy_kcal\n2026-10-12,1900\n"
                    } else "{\"entries\":[]}")
                else -> error("Unexpected nutrition statistics request")
            }
        }
    }), FEATURE_ORIGIN, sessions, { FEATURE_NOW })
    val controller = FoodStatsController(test.backgroundScope, sessions, http, exports, { FEATURE_NOW })
    suspend fun start(day: String) {
        sessions.restore()
        controller.activate(day)
        test.runCurrent()
    }
}
