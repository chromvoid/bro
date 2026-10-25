package assistant.mobile.platform.ring

import java.time.Instant
import java.time.ZoneId
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class RingActivityReportTest {
    private val zone = ZoneId.of("Europe/Moscow")
    private val requestedAt = Instant.parse("2026-10-06T09:00:00Z")
    private fun clock(at: Instant = requestedAt) = RingRunningClock(zone, at.minusSeconds(2), 10_000, at, 12_000)
    private fun snapshot(value: Int = 512, at: Instant = requestedAt.plusSeconds(1), proof: JsonObject = clock().proof(at, 13_000)) = obj {
        put("metric", "running_steps"); put("opcode", 72); put("unit", "count"); put("quality", "valid")
        put("validation_status", "source_only"); put("local_date", "2026-10-06"); put("timezone", zone.id)
        put("value", value); put("details", obj {
            put("type", "running_snapshot"); put("total_steps", 8_000); put("calories_raw", 320)
            put("distance_raw", 55); put("duration_minutes", 42); put("clock_proof", proof)
        })
    }
    private fun convert(raw: JsonObject = snapshot()) = RingActivityReport.runningSnapshot(raw, zone, "RF20", "R20")
    private fun quantity(kind: String, rawValue: Int, at: Instant = requestedAt.plusSeconds(1), proof: JsonObject = clock().proof(at, 13_000)): JsonObject {
        val unit = if (kind == "active_energy") "kcal" else "m"
        val divisor = if (kind == "active_energy") 1000 else 1
        val original = snapshot(at = at, proof = proof)
        return JsonObject(original + mapOf(
            "metric" to JsonPrimitive(kind), "unit" to JsonPrimitive(unit), "value" to JsonPrimitive(rawValue.toDouble() / divisor),
            "raw_value" to JsonPrimitive(rawValue), "source" to JsonPrimitive("oudmon_sdk_core"),
            "details" to obj {
                put("type", "activity_quantity_snapshot"); put("total_steps", 8000); put("calories_raw", if (kind == "active_energy") rawValue else 113719)
                put("distance_raw", if (kind == "distance") rawValue else 2600); put("duration_minutes", 42); put("clock_proof", proof)
                put("decode_version", "oudmon-core-48-quantity-v1")
                put("unit_evidence", obj {
                    put("id", "soul-1.0.1214-core-48-quantity-v1"); put("namespace", "core"); put("opcode", 0x48)
                    put("quantity", kind); put("unit", unit); put("divisor", divisor)
                    put("source_reference", if (kind == "active_energy") "soul-1.0.1214:sib.C:dex:0x0397,0x039b"
                        else "soul-1.0.1214:sib.C:dex:0x070c,0x0712,float@0x0027")
                })
                put("soul_display_value", if (kind == "active_energy") (rawValue / 1000).toDouble() else rawValue.toDouble() / 1000)
                put("soul_display_unit", if (kind == "active_energy") "kcal" else "km")
            },
        ))
    }
    @Test fun qualifiedQuantityUnitsKeepFractionalEnergyAndMetersAsSeparateSnapshots() {
        for ((kind, rawValue, canonical, unit) in listOf(
            listOf("active_energy", "113719", "113.719", "kcal"), listOf("distance", "2600", "2600", "m"))) {
            val raw = quantity(kind, rawValue.toInt())
            val record = RingActivityReport.quantitySnapshot(raw, zone, "RF20", "R20", requestedAt.plusSeconds(10))!!
            val report = record["report"] as JsonObject
            assertEquals("day_counter_snapshot", report.string("shape"))
            assertEquals(canonical, (report["data"] as JsonObject).string("value"))
            assertEquals(unit, (report["data"] as JsonObject).string("unit"))
            val origin = report["origin"] as JsonObject
            assertEquals(rawValue.toInt(), origin.number("raw_value"))
            assertEquals("counter", (origin["resolution"] as JsonObject).string("shape"))
            assertTrue((report["quality"] as JsonArray).contains(JsonPrimitive("protocol_unverified")))
            val conversion = RingObservation.explain(raw, zone, "RF20_2.10.07_260131", "RF20_V2.1", requestedAt.plusSeconds(10))
            assertFalse(conversion.protocolVerified); assertNotNull(conversion.record)
            val converted = conversion.record!!["report"] as JsonObject
            assertEquals(canonical, (converted["data"] as JsonObject).string("value"))
            assertEquals("RF20_2.10.07_260131", (converted["origin"] as JsonObject).string("firmware"))
            assertNotEquals(observationFingerprint(record), observationFingerprint(conversion.record!!))
            val moved = requestedAt.plusSeconds(3600)
            val repeated = RingActivityReport.quantitySnapshot(quantity(kind, rawValue.toInt(), moved.plusSeconds(1),
                clock(moved).proof(moved.plusSeconds(1), 13_000)), zone, "RF20", "R20", moved.plusSeconds(10))!!
            assertEquals(observationFingerprint(record), observationFingerprint(repeated))
            val changed = RingActivityReport.quantitySnapshot(quantity(kind, rawValue.toInt() + 1), zone, "RF20", "R20", requestedAt.plusSeconds(10))!!
            assertNotEquals(observationFingerprint(record), observationFingerprint(changed))
        }
    }
    @Test fun newMetricsGpsAndStateNegotiateAndRoundTripWithoutDroppingRecords() {
        val state = obj { put("external_id", "device-state:2026-10-06T09:00:00Z"); put("device_state", obj {
            put("observed_at", "2026-10-06T09:00:00Z"); put("battery_percent", 50)
        }) }
        val gps = obj { put("external_id", "gps:synthetic"); put("workout_id", "synthetic"); put("sequence", 0)
            put("time_basis", "phone_monotonic"); put("points", JsonArray(emptyList())); put("gaps", JsonArray(emptyList())) }
        val sensor = obj { put("external_id", "hrv:synthetic"); put("metric", "hrv") }
        val caps = RingBatchRecords.supportedKinds(obj {
            put("supported_device_state", true); put("supported_workout_track_chunks", true)
            put("supported_sensor_metrics", JsonArray(listOf(JsonPrimitive("hrv"))))
        })
        for (record in listOf(state, gps, sensor)) {
            assertFalse(RingBatchRecords.supported(record, emptySet())); assertTrue(RingBatchRecords.supported(record, caps))
        }
        val body = RingBatchRecords.body(listOf(sensor, gps, state), JsonNull, "cursor")
        assertEquals(listOf(sensor, gps, state), RingBatchRecords.records(body))
        assertEquals(state["device_state"], body["device_state"])
        try { RingBatchRecords.body(listOf(state, state), JsonNull, "cursor"); fail("A singular state cannot silently discard another record") }
        catch (failure: RingFailure) { assertEquals("invalid_batch", failure.code) }
    }

    @Test fun midnightClockJumpStaleProofAndMissingProofCannotAssignADay() {
        val midnight = Instant.parse("2026-10-06T20:59:59Z")
        val crossing = clock(midnight).proof(midnight.plusSeconds(2), 14_000)
        assertFalse(crossing.bool("clock_unchanged")!!)
        assertNull(convert(snapshot(proof = crossing)))
        val jumped = clock().proof(requestedAt.plusSeconds(120), 13_000)
        assertFalse(jumped.bool("clock_unchanged")!!)
        assertNull(convert(snapshot(proof = jumped)))
        val stale = clock().proof(requestedAt.plusSeconds(9), 21_000)
        assertNull(convert(snapshot(proof = stale)))
        val differentZone = JsonObject(clock().proof(requestedAt.plusSeconds(1), 13_000) + ("timezone" to JsonPrimitive("UTC")))
        assertNull(convert(snapshot(proof = differentZone)))
        assertNull(convert(JsonObject(snapshot() + ("details" to obj { put("type", "running_snapshot") }))))
        assertNull(convert(snapshot(-1)))
    }
    @Test fun unknownAndMixedRecordTypesFailBeforeSuccessfulSerialization() {
        for (record in listOf(obj { put("external_id", "unknown"); put("kind", "future_kind") },
            JsonObject(convert()!! + ("metric" to JsonPrimitive("heart_rate"))))) {
            try { RingBatchRecords.body(listOf(record), JsonNull, "cursor"); fail("Unknown record must stop batch formation") }
            catch (failure: RingFailure) { assertEquals("unknown_record_type", failure.code) }
        }
        val wrongCategory = obj { put("samples", JsonArray(listOf(convert()!!))) }
        try { RingBatchRecords.records(wrongCategory); fail("Category mismatch must not be acknowledged") }
        catch (failure: RingFailure) { assertEquals("invalid_batch", failure.code) }
    }
}
