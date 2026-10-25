package assistant.mobile.platform.ring

import java.time.Instant
import java.time.ZoneId
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class RingObservationTest {
    private val zone = ZoneId.of("Europe/Moscow")
    private val now = Instant.parse("2026-10-05T14:00:00Z")
    private val firmware = "RF20_2.10.07_260131"
    private val hardware = "RF20_V2.1"
    private fun sleepCandidate(): JsonObject {
        val fixture = requireNotNull(javaClass.getResourceAsStream("/ring/sleep-candidate.json")) {
            "Missing shared ring sleep fixture on the native JVM test classpath"
        }
        return fixture.bufferedReader().use { ringJson.parseToJsonElement(it.readText()) as JsonObject }
    }
    private fun sleepWithStages(vararg stages: JsonObject): JsonObject {
        val raw = sleepCandidate()
        return JsonObject(raw + ("details" to JsonObject((raw["details"] as JsonObject) + ("stages" to JsonArray(stages.toList())))))
    }
    private fun stage(type: Int, duration: Int) = obj { put("raw_type", type); put("duration_minutes", duration) }
    private fun sample(metric: String, unit: String, value: JsonElement = JsonPrimitive(72)) = obj {
        put("metric", metric); put("unit", unit); put("value", value); put("local_date", "2026-10-05")
        put("timezone", zone.id); put("minute_of_day", 60); put("sample_index", 4)
        put("source", "oudmon_sdk_bulk"); put("validation_status", "source_only"); put("quality", "valid")
        put("details", obj { put("type", "interval"); put("interval_minutes", 5) })
    }
    private fun hourlySteps(minute: Int = 60, value: Int = 1234) = obj {
        put("metric", "steps"); put("unit", "count"); put("value", value); put("opcode", 0x43)
        put("local_date", "2026-10-05"); put("timezone", zone.id)
        put("minute_of_day", minute); put("sample_index", minute / 15)
        put("source", "oudmon_sdk_core"); put("validation_status", "source_only"); put("quality", "valid")
        put("details", obj {
            put("type", "steps"); put("interval_minutes", 60); put("duration_minutes", 60)
            put("calories_raw", 0); put("distance_raw", 0)
        })
    }
    private fun scalar(metric: String = "hrv", value: Int = 255) = obj {
        put("metric", metric); put("unit", if (metric == "hrv") "ms" else "index"); put("value", value.toDouble()); put("raw_value", value)
        put("opcode", if (metric == "hrv") 0x39 else 0x37); put("local_date", "2026-10-05"); put("timezone", zone.id)
        put("minute_of_day", 60); put("sample_index", 1); put("source", "oudmon_sdk_core")
        put("validation_status", "source_only"); put("quality", "valid")
        put("details", obj {
            put("type", "core_scalar_history"); put("interval_minutes", 60); put("declared_frames", 3); put("wire_day_offset", 0)
            put("received_samples", 25); put("ignored_tail_samples", 1); put("ignored_tail_nonzero", false)
            put("decode_version", "oudmon-core-scalar-v1")
        })
    }
    private fun rawTemperature(frame: String = "bc2504008a82001e6481") = obj {
        put("metric", "temperature"); put("opcode", 0x25); put("local_date", "2026-10-05"); put("timezone", zone.id)
        put("source", "oudmon_sdk_bulk"); put("quality", "temperature_layout_unconfirmed"); put("day_offset", 0)
        put("details", obj {
            put("type", "raw_temperature"); put("layout", "oudmon-temperature-25-ambiguous"); put("payload_hex", frame)
            put("day_offset", 0); put("page_index", 0); put("interval_minutes", 30); put("decode_version", "oudmon-bulk-25-raw-v1")
            put("candidates", JsonArray(listOf(JsonPrimitive("file_u8_tenths_plus20"), JsonPrimitive("legacy_u8_tenths_plus32_rle"))))
        })
    }
    @Test fun ambiguousTemperatureIsArchivedAsCheckedRawBytesWithoutAClinicalScalar() {
        val converted = RingObservation.explain(rawTemperature(), zone, firmware, hardware, now)
        assertFalse(converted.protocolVerified)
        val record = converted.record!!; val report = record["report"] as JsonObject
        assertEquals("bulk", report.string("namespace")); assertEquals(0x25, report.number("opcode"))
        assertEquals("bc2504008a82001e6481", report.string("payload_hex"))
        assertNull(report["unit"]); assertNull(report["value"]); assertNull(report["start_at"])
        assertEquals(RingBatchRecords.Category.RAW_REPORT, RingBatchRecords.category(record))
        assertFalse(RingBatchRecords.supported(record, emptySet()))
        val caps = RingBatchRecords.supportedKinds(obj { put("supported_raw_report_metrics", JsonArray(listOf(JsonPrimitive("temperature")))) })
        assertTrue(RingBatchRecords.supported(record, caps))
        assertEquals(listOf(record), RingBatchRecords.records(RingBatchRecords.body(listOf(record), JsonNull, "raw")))
        val repeated = RingObservation.convert(rawTemperature(), zone, firmware, hardware, now.plusSeconds(3600))!!
        assertEquals(observationFingerprint(record), observationFingerprint(repeated))
        assertNull(RingObservation.convert(rawTemperature("bc2504008b82001e6481"), zone, firmware, hardware, now))
        assertNull(RingObservation.convert(JsonObject(rawTemperature() + ("unit" to JsonPrimitive("celsius"))), zone, firmware, hardware, now))
    }
    @Test fun scalarCoreHistoryKeepsDeviceValuesAndUnknownDefinitionsEvenOnKnownFirmware() {
        for (metric in listOf("hrv", "stress")) {
            val conversion = RingObservation.explain(scalar(metric), zone, firmware, hardware, now)
            assertFalse(conversion.protocolVerified); assertEquals("unverified", conversion.metricStatus)
            val record = conversion.record!!
            assertEquals("255.0", record.string("value"))
            assertEquals(if (metric == "hrv") "ms" else "index", record.string("unit"))
            assertEquals("2026-10-04T22:00:00Z", record.string("start_at"))
            assertEquals(record.string("start_at"), record.string("end_at"))
            val origin = record["origin"] as JsonObject
            assertEquals(255, origin.number("raw_value"))
            assertEquals("oudmon_sdk_core", origin.string("source"))
            assertEquals("core_scalar_history", (origin["details"] as JsonObject).string("type"))
            val semantics = origin["resolution"] as JsonObject
            assertEquals("reported_unknown", semantics.string("shape"))
            if (metric == "hrv") assertEquals("unknown", semantics.string("hrv_statistic"))
            else assertEquals("oudmon-device-stress-unknown-v1", semantics.string("definition_id"))
            assertTrue((record["quality"] as JsonArray).contains(JsonPrimitive("protocol_unverified")))
        }
    }
    @Test fun confirmedHourlyStepsShareImportQualityAndNativeStatus() {
        val result = RingObservation.explain(hourlySteps(), zone, firmware, hardware, now)
        assertTrue(result.protocolVerified)
        assertEquals("data", result.metricStatus)
        assertNull(result.reason)
        val record = result.record!!
        val observation = record["observation"] as JsonObject
        assertEquals("qring-r20:steps:2026-10-05:67:60:4:oudmon_sdk_core", record.string("external_id"))
        assertEquals("2026-10-04T22:00:00Z", observation.string("start_at"))
        assertEquals("2026-10-04T23:00:00Z", observation.string("end_at"))
        assertEquals(1234, (observation["data"] as JsonObject).number("value"))
        assertEquals(JsonArray(emptyList()), observation["quality"])
        assertEquals("qring-r20-hourly-v1", observation.string("algorithm_version"))
        assertEquals(obj {
            put("provider", "herzring"); put("device", "qring-r20:$firmware"); put("record_id", record.string("external_id")!!)
            put("profile", "qring-r20"); put("date_basis", "ring_local_date"); put("source", "oudmon_sdk_core")
            put("firmware", firmware); put("hardware", hardware); put("action", "67")
            put("details", obj {
                put("type", "steps"); put("interval_minutes", 60); put("duration_minutes", 60)
                put("calories_raw", 0); put("distance_raw", 0)
            })
        }, observation["origin"])
    }
    @Test fun confirmedHourRevisesTheExistingWholeHourIdentityWithoutPollingDuplicates() {
        val raw = hourlySteps()
        val oldDetails = JsonObject((raw["details"] as JsonObject) + mapOf(
            "interval_minutes" to JsonPrimitive(15), "duration_minutes" to JsonPrimitive(15)))
        val old = RingObservation.convert(JsonObject(raw + ("details" to oldDetails)), zone, firmware, hardware, now)!!
        val current = RingObservation.convert(raw, zone, firmware, hardware, now)!!
        val repeated = RingObservation.convert(raw, zone, firmware, hardware, now.plusSeconds(100))!!
        val changed = RingObservation.convert(hourlySteps(value = 1300), zone, firmware, hardware, now)!!
        assertEquals(old.string("external_id"), current.string("external_id"))
        assertEquals(current.string("external_id"), changed.string("external_id"))
        assertEquals("2026-10-04T22:15:00Z", (old["observation"] as JsonObject).string("end_at"))
        assertEquals("2026-10-04T23:00:00Z", (current["observation"] as JsonObject).string("end_at"))
        assertNotEquals(observationFingerprint(old), observationFingerprint(current))
        assertEquals(observationFingerprint(current), observationFingerprint(repeated))
        assertNotEquals(observationFingerprint(current), observationFingerprint(changed))
    }
    @Test fun sdkVitalsKeepQualityUnitsAndStableIdentity() {
        val raw = sample("heart_rate", "bpm")
        val result = RingObservation.convert(raw, zone, "RF20", "R20", now)!!
        assertEquals("bpm", result.string("unit"))
        assertEquals(JsonArray(listOf(JsonPrimitive("protocol_unverified"))), result["quality"])
        assertEquals("2026-10-04T22:00:00Z", result.string("start_at"))
        assertEquals(result.string("external_id"), RingObservation.convert(raw, zone, "RF20", "R20", now.plusSeconds(100))!!.string("external_id"))
    }
    @Test fun hourlySpo2RangeIsNotInventedAverage() {
        val raw = JsonObject(sample("spo2", "percent", JsonNull) + ("details" to obj {
            put("type", "range"); put("minimum", 95); put("maximum", 99); put("interval_minutes", 60)
        }))
        val result = RingObservation.convert(raw, zone, null, null, now)!!
        assertNull(result["value"]); assertEquals("95", result.string("reported_min")); assertEquals("99", result.string("reported_max"))
    }
    @Test fun receiptsMustProveSourceAndExactBatchCursor() {
        val cursor = JsonPrimitive("cursor")
        assertTrue(RingPolicy.receiptMatches(obj { put("result", obj { put("source_id", "source"); put("cursor", cursor) }) }, "source", cursor))
        assertFalse(RingPolicy.receiptMatches(obj { put("source_id", "other"); put("cursor", cursor) }, "source", cursor))
        assertFalse(RingPolicy.receiptMatches(obj { put("source_id", "source"); put("cursor", "old") }, "source", cursor))
    }
    @Test fun bindingAndVersionEpochPreservePhysicalRingAcrossLogout() {
        val first = RingPolicy.bindingId("phone", "https://example.test", "user", "C0:00:00:00:00:01")
        assertEquals(first, RingPolicy.bindingId("phone", "https://example.test", "user", "c0:00:00:00:00:01"))
        assertNotEquals(first, RingPolicy.bindingId("phone", "https://example.test", "user", "C0:00:00:00:00:02"))
        assertNotEquals(first, RingPolicy.bindingId("phone", "https://example.test", "other", "C0:00:00:00:00:01"))
        assertTrue(RingPolicy.sourceVersion(2, 1) > RingPolicy.sourceVersion(1, 999_999))
    }
    @Test fun sdkSleepStagesExcludeAwakeFromScalarMinutes() {
        // Output of the Rust synthetic_sleep_candidate_for_native_import_contract parser test.
        val raw = sleepCandidate()
        val details = raw["details"] as JsonObject
        val result = RingObservation.convert(raw, zone, "RF20", "R20", now)!!
        val observation = result["observation"] as JsonObject
        val data = observation["data"] as JsonObject
        assertEquals("420", data.string("value"))
        assertEquals("2026-10-04T20:00:00Z", observation.string("start_at"))
        assertEquals("2026-10-05T04:00:00Z", observation.string("end_at"))
        assertEquals("awake", ((data["stages"] as JsonArray).last() as JsonObject).string("stage"))
        assertEquals(result.string("external_id"), RingObservation.convert(JsonObject(raw + ("sample_index" to JsonPrimitive(99))), zone, "RF20", "R20", now)!!.string("external_id"))
        val unknown = JsonObject(details + ("stages" to JsonArray(listOf(obj { put("raw_type", 1); put("duration_minutes", 480) }))))
        val unresolved = RingObservation.convert(JsonObject(raw + ("details" to unknown)), zone, "RF20", "R20", now)!!
        assertEquals(JsonNull, ((unresolved["observation"] as JsonObject)["data"] as JsonObject)["value"])
    }
    @Test fun sleepIdentityFollowsReportDateAndActionWhileContentChangesItsFingerprint() {
        val raw = sleepCandidate()
        val details = raw["details"] as JsonObject
        val first = RingObservation.convert(raw, zone, "RF20", "R20", now)!!
        assertEquals("qring-r20:sleep-v2:2026-10-05:39:oudmon_sdk_bulk", first.string("external_id"))
        val variants = listOf(
            JsonObject(raw + ("details" to JsonObject(details + ("start_minute" to JsonPrimitive(1381))))),
            JsonObject(raw + ("details" to JsonObject(details + ("end_minute" to JsonPrimitive(421))))),
            sleepWithStages(stage(3, 240), stage(3, 120), stage(4, 60), stage(5, 60)),
        )
        for (changed in variants) {
            val converted = RingObservation.convert(changed, zone, "RF20", "R20", now)!!
            assertEquals(first.string("external_id"), converted.string("external_id"))
            assertNotEquals(observationFingerprint(first), observationFingerprint(converted))
        }
        val repeated = JsonObject(raw + mapOf("sample_index" to JsonPrimitive(9), "external_id" to JsonPrimitive("old-boundary-identity")))
        assertEquals(first, RingObservation.convert(repeated, zone, "RF20", "R20", now.plusSeconds(100)))
        val nap = RingObservation.convert(JsonObject(raw + ("opcode" to JsonPrimitive(0x3e))), zone, "RF20", "R20", now)!!
        assertEquals("qring-r20:sleep-v2:2026-10-05:62:oudmon_sdk_bulk", nap.string("external_id"))
        val nextDay = JsonObject(raw + mapOf("local_date" to JsonPrimitive("2026-10-06"), "details" to JsonObject(details + mapOf(
            "start_date" to JsonPrimitive("2026-10-05"), "end_date" to JsonPrimitive("2026-10-06")))))
        assertNotEquals(first.string("external_id"), RingObservation.convert(nextDay, zone, "RF20", "R20", now.plusSeconds(86_400))!!.string("external_id"))
        assertNull(RingObservation.convert(JsonObject(raw + ("opcode" to JsonPrimitive(0x44))), zone, "RF20", "R20", now))
        assertNull(RingObservation.convert(JsonObject(raw + ("source" to JsonPrimitive("other"))), zone, "RF20", "R20", now))
        assertNull(RingObservation.convert(JsonObject(raw + ("local_date" to JsonPrimitive("2026-10-06"))), zone, "RF20", "R20", now))
    }
}
