package assistant.mobile.platform.ring

import java.time.Instant
import java.time.ZoneId
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class RingSportHistoryObservationTest {
    private val zone = ZoneId.of("Europe/Moscow")
    private val at = Instant.parse("2026-10-05T14:00:00Z")
    private fun raw(start: Long = 200, sport: Long = 17): JsonObject {
        val fields = listOf(start, 600L, sport, 321L, 2345L, 999L, 3L)
        val body = fields.flatMap { value -> (0..3).map { ((value shr (8 * it)) and 0xff).toInt() } } + listOf(80, 0, 250) + List(8) { 0 }
        return obj {
            put("metric", "workouts"); put("opcode", 0x13); put("source", "oudmon_sdk_core"); put("timezone", zone.id)
            put("local_date", "2026-10-05"); put("quality", "protocol_unverified"); put("value", JsonNull); put("unit", JsonNull)
            put("minute_of_day", JsonNull); put("details", obj {
                put("type", "raw_device_workout"); put("layout", "oudmon-core-13-device-workout-v1")
                put("decode_version", "oudmon-core-13-device-workout-v1"); put("date_basis", "phone_capture_local_date")
                put("time_basis", "first_phone_capture_day"); put("declared_frames", 4); put("payload_bytes", 39)
                put("header_hex", "0004000000000000000000000000"); put("payload_hex", body.joinToString("") { "%02x".format(it) })
                listOf("raw_start_time", "duration_raw", "sport_type_raw", "steps_raw", "distance_raw", "calories_raw", "heart_rate_count")
                    .zip(fields).forEach { (key, value) -> put(key, value) }
                put("heart_rate_bytes", JsonArray(listOf(80, 0, 250).map(::JsonPrimitive)))
                put("ignored_tail_bytes", 8); put("ignored_tail_nonzero", false)
            })
        }
    }
    private fun convert(candidate: JsonObject = raw(), observed: Instant = at) =
        RingObservation.explain(candidate, zone, "RF20_2.10.07_260131", "RF20_V2.1", observed)

    @Test fun sourceDeviceHistoryIsEncryptedQueueCompatibleWithoutInventedWorkoutSemantics() {
        val result = convert(); assertFalse(result.protocolVerified)
        val record = result.record!!; val report = record["report"] as JsonObject
        assertEquals("qring-r20:device-workout:200:17", record.string("external_id"))
        assertEquals("core", report.string("namespace")); assertEquals(0x13, report.number("opcode"))
        assertEquals("first_phone_capture_day", report.string("time_basis"))
        for (key in listOf("start_at", "end_at", "unit", "value", "sport_plan", "sport_type")) assertNull(report[key])
        val details = report["details"] as JsonObject
        assertEquals(JsonArray(listOf(80, 0, 250).map(::JsonPrimitive)), details["heart_rate_bytes"])
        assertNull(details["hr_interval_seconds"]); assertNull(details["time_basis"])
        assertEquals("phone_capture_local_date", (report["origin"] as JsonObject).string("date_basis"))
        assertFalse(RingBatchRecords.supported(record, setOf("workouts")))
        val caps = RingBatchRecords.supportedKinds(obj { put("supported_raw_report_metrics", JsonArray(listOf(JsonPrimitive("workouts")))) })
        assertTrue(RingBatchRecords.supported(record, caps))
        assertEquals(listOf(record), RingBatchRecords.records(RingBatchRecords.body(listOf(record), JsonNull, "cursor")))
    }
    @Test fun aLaterReceiptDayOrZoneDoesNotReviseTheSameDeviceBody() {
        val first = convert().record!!
        val later = convert(observed = at.plusSeconds(3 * 86400)).record!!
        assertNotEquals((first["report"] as JsonObject).string("local_date"), (later["report"] as JsonObject).string("local_date"))
        assertEquals(observationFingerprint(first), observationFingerprint(later))
        val utcRaw = JsonObject(raw() + ("timezone" to JsonPrimitive("UTC")))
        val utc = RingSportHistoryObservation.convert(utcRaw, ZoneId.of("UTC"), "RF20_2.10.07_260131", "RF20_V2.1", at)!!
        assertEquals(observationFingerprint(first), observationFingerprint(utc))
        assertNotEquals(observationFingerprint(first), observationFingerprint(convert(raw(201)).record!!))
        assertNotEquals(observationFingerprint(first), observationFingerprint(convert(raw(sport = 18)).record!!))
        val maximum = (convert(raw(0xffffffffL)).record!!["report"] as JsonObject)["details"] as JsonObject
        assertEquals(0xffffffffL, maximum["raw_start_time"]!!.jsonPrimitive.long)
    }
    @Test fun cursorSurvivesReceiptCalendarResetAndFreshHistoryExplicitlyRestartsIt() {
        val saved = obj { put("local_date", "2026-10-05"); put("timezone", zone.id)
            put("completed_queries", JsonArray(listOf(JsonPrimitive("workouts:100:0")))); put("workout_cursor", 200) }
        assertEquals(saved, RingHistoryCheckpoint.forSession(saved, "2026-10-05", zone.id, false))
        val nextDay = RingHistoryCheckpoint.forSession(saved, "2026-10-06", zone.id, false)
        assertEquals(200L, RingHistoryCheckpoint.workoutCursor(nextDay)); assertEquals(JsonArray(emptyList()), nextDay["completed_queries"])
        assertEquals(200L, RingHistoryCheckpoint.workoutCursor(RingHistoryCheckpoint.restart(saved)))
        assertEquals(0L, RingHistoryCheckpoint.workoutCursor(RingHistoryCheckpoint.forSession(saved, "2026-10-05", zone.id, true)))
    }
}
