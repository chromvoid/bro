package assistant.mobile.platform.ring

import java.io.File
import java.time.Instant
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class RingManualObservationTest {
    private val fixture = ringJson.parseToJsonElement(File(System.getProperty("assistant.ring.manual.fixture")!!).readText()) as JsonObject
    private val source = fixture["binding"] as JsonObject
    private val owner = RingWorkoutBinding(source.string("origin")!!, source.string("user_id")!!, source.string("source_id")!!,
        source.string("collector_id")!!, source.string("address")!!, source["grant_generation"]!!.jsonPrimitive.long, source.string("timezone")!!)
    private val cases = (fixture["cases"] as JsonArray).map { it as JsonObject }
    private fun clock(case: JsonObject): RingWorkoutClock {
        val value = case["clock"] as JsonObject
        return RingWorkoutClock(Instant.parse(value.string("utc_at")!!), value["elapsed_realtime_ms"]!!.jsonPrimitive.long, value.string("boot_id")!!)
    }
    @Test fun exactEncodedRecordsShareTheServerNormalizationFixture() {
        for (case in cases) assertEquals(case.string("name"), case["record"], RingManualObservation.encode(owner, clock(case), case["observation"] as JsonObject))
    }
    @Test fun wrongUnitTimeQualityMetricOrWireBoundsCannotBePersisted() {
        val case = cases.first(); val raw = case["observation"] as JsonObject
        for (change in listOf("unit" to JsonPrimitive("%"), "received_monotonic_ms" to JsonPrimitive(999),
            "sample_quality" to JsonPrimitive("unknown"), "metric" to JsonPrimitive("hrv"), "value" to JsonPrimitive(301),
            "value" to JsonPrimitive("72"))) {
            assertNull(RingManualObservation.encode(owner, clock(case), JsonObject(raw + change)))
        }
        assertNull(RingManualObservation.encode(owner, clock(case), JsonObject(raw - "sample_quality")))
    }
    @Test fun captureUsesThePersistedManualPointForAllFiveMetrics() {
        val first = cases.first(); val raw = first["observation"] as JsonObject
        val records = cases.map { RingManualObservation.encode(owner, clock(it), it["observation"] as JsonObject)!! } +
            listOf("hrv" to "ms", "stress" to "index").map { (metric, unit) ->
                RingManualObservation.encode(owner, clock(first), JsonObject(raw + mapOf(
                    "metric" to JsonPrimitive(metric), "unit" to JsonPrimitive(unit), "value" to JsonPrimitive(42))))!!
            }
        assertEquals(setOf("heart_rate", "spo2", "temperature", "hrv", "stress"), records.map { it.string("metric") }.toSet())
        for (record in records) {
            val metric = record.string("metric")!!
            val capture = RingManualObservation.capture(record)!!
            assertEquals(setOf(metric), capture.keys)
            assertEquals(obj {
                put("captured_at", record["start_at"]!!); put("status", "complete"); put("scope", "spot"); put("context", "manual")
            }, capture[metric])
        }
        // Delivery of an invalid numeric result is still a completed read, not a valid physiological value.
        assertTrue((records.first()["quality"] as JsonArray).contains(JsonPrimitive("invalid")))
    }
}
