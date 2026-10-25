package assistant.mobile.platform.ring

import assistant.mobile.platform.R

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.abs
import kotlinx.serialization.json.*

/** A phone-assigned day is evidence only when the entire exchange follows one wall clock. */
data class RingRunningClock(
    val zone: ZoneId,
    val sessionAt: Instant,
    val sessionElapsed: Long,
    val requestedAt: Instant,
    val requestedElapsed: Long,
) {
    fun proof(observedAt: Instant, observedElapsed: Long): JsonObject {
        val sessionDate = sessionAt.atZone(zone).toLocalDate()
        val requestDate = requestedAt.atZone(zone).toLocalDate()
        val responseDate = observedAt.atZone(zone).toLocalDate()
        fun unchanged(at: Instant, elapsed: Long): Boolean = elapsed >= sessionElapsed &&
            abs((at.toEpochMilli() - sessionAt.toEpochMilli()) - (elapsed - sessionElapsed)) <= 2_000
        return obj {
            put("request_local_date", requestDate.toString()); put("response_local_date", responseDate.toString())
            put("timezone", zone.id); put("request_wall_time_ms", requestedAt.toEpochMilli())
            put("response_wall_time_ms", observedAt.toEpochMilli()); put("request_monotonic_ms", requestedElapsed)
            put("response_monotonic_ms", observedElapsed)
            put("clock_unchanged", requestDate == sessionDate && responseDate == sessionDate &&
                unchanged(requestedAt, requestedElapsed) && unchanged(observedAt, observedElapsed))
        }
    }
}

object RingActivityReport {
    private data class SnapshotTime(val date: LocalDate, val observedAt: Instant)
    private fun snapshotTime(raw: JsonObject, zone: ZoneId): SnapshotTime? {
        val details = raw["details"] as? JsonObject ?: return null
        val proof = details["clock_proof"] as? JsonObject ?: return null
        if (proof.bool("clock_unchanged") != true) return null
        val requested = proof["request_wall_time_ms"]?.jsonPrimitive?.longOrNull ?: return null
        val observed = proof["response_wall_time_ms"]?.jsonPrimitive?.longOrNull ?: return null
        val requestElapsed = proof["request_monotonic_ms"]?.jsonPrimitive?.longOrNull ?: return null
        val observedElapsed = proof["response_monotonic_ms"]?.jsonPrimitive?.longOrNull ?: return null
        if (observedElapsed < requestElapsed || observedElapsed - requestElapsed > 8_000 ||
            observed < requested || abs((observed - requested) - (observedElapsed - requestElapsed)) > 2_000)
            return null
        val date = try { LocalDate.parse(raw.string("local_date") ?: return null) } catch (_: Exception) { return null }
        val observedAt = Instant.ofEpochMilli(observed)
        if (Instant.ofEpochMilli(requested).atZone(zone).toLocalDate() != date ||
            observedAt.atZone(zone).toLocalDate() != date || proof.string("request_local_date") != date.toString() ||
            proof.string("response_local_date") != date.toString() || proof.string("timezone") != zone.id) return null
        return SnapshotTime(date, observedAt)
    }

    /** Called only for a Rust observation released after successful clock initialization and ATT ACK. */
    fun runningSnapshot(raw: JsonObject, zone: ZoneId, firmware: String?, hardware: String?): JsonObject? {
        if (raw.string("metric") != "running_steps" || raw.number("opcode") != 0x48 ||
            raw.string("unit") != "count" || raw.string("quality") != "valid" || raw.string("timezone") != zone.id)
            return null
        val details = raw["details"] as? JsonObject ?: return null
        if (details.string("type") != "running_snapshot") return null
        val time = snapshotTime(raw, zone) ?: return null
        val date = time.date; val observedAt = time.observedAt
        val value = try { raw["value"]?.jsonPrimitive?.content?.toBigDecimalOrNull()?.longValueExact() }
            catch (_: ArithmeticException) { null }
        if (value == null || value !in 0..0xFFFFFF) return null
        val identity = "qring-r20:running_steps:$date"
        return obj {
            put("external_id", identity); put("kind", "running_steps")
            put("report", obj {
                put("kind", "running_steps"); put("shape", "day_counter_snapshot")
                put("local_date", date.toString()); put("timezone", zone.id); put("observed_at", observedAt.toString())
                put("data", obj { put("value", value) })
                put("quality", JsonArray(listOf(JsonPrimitive("protocol_unverified"), JsonPrimitive("partial_day"))))
                put("origin", obj {
                    put("provider", "herzring"); put("profile", "qring-r20"); put("record_id", identity)
                    put("action", "72"); put("date_basis", "phone_local_after_clock_init")
                    firmware?.let { put("firmware", it) }; hardware?.let { put("hardware", it) }
                    put("details", JsonObject(details.filterKeys { it in setOf("total_steps", "calories_raw", "distance_raw", "duration_minutes") }))
                })
            })
        }
    }

    /** Core 0x48 quantities have their own namespace-qualified unit evidence.
     * Hourly 0x43 raw fields cannot borrow these scales. Neither is an increment. */
    fun quantitySnapshot(raw: JsonObject, zone: ZoneId, firmware: String?, hardware: String?, now: Instant = Instant.now()): JsonObject? {
        val kind = raw.string("metric")?.takeIf { it in setOf("active_energy", "distance") } ?: return null
        val unit = if (kind == "active_energy") "kcal" else "m"
        val divisor = if (kind == "active_energy") 1000 else 1
        if (raw.number("opcode") != 0x48 || raw.string("source") != "oudmon_sdk_core" || raw.string("unit") != unit ||
            raw.string("quality") != "valid" || raw.string("timezone") != zone.id) return null
        val details = raw["details"] as? JsonObject ?: return null
        if (details.string("type") != "activity_quantity_snapshot" || details.string("decode_version") != "oudmon-core-48-quantity-v1") return null
        val evidence = details["unit_evidence"] as? JsonObject ?: return null
        val reference = if (kind == "active_energy") "soul-1.0.1214:sib.C:dex:0x0397,0x039b"
            else "soul-1.0.1214:sib.C:dex:0x070c,0x0712,float@0x0027"
        if (evidence.string("id") != "soul-1.0.1214-core-48-quantity-v1" || evidence.string("namespace") != "core" ||
            evidence.number("opcode") != 0x48 || evidence.string("quantity") != kind || evidence.string("unit") != unit ||
            evidence.number("divisor") != divisor || evidence.string("source_reference") != reference) return null
        val rawValue = raw.number("raw_value")?.takeIf { it in 0..0xFFFFFF } ?: return null
        if (details.number(if (kind == "active_energy") "calories_raw" else "distance_raw") != rawValue) return null
        if (listOf("total_steps", "calories_raw", "distance_raw").any { details.number(it) !in 0..0xFFFFFF } ||
            details.number("duration_minutes") !in 0..0xFFFF) return null
        val canonicalValue = rawValue.toBigDecimal().divide(divisor.toBigDecimal())
        if (raw["value"]?.jsonPrimitive?.content?.toBigDecimalOrNull()?.compareTo(canonicalValue) != 0) return null
        val display = if (kind == "active_energy") (rawValue / 1000).toBigDecimal() else rawValue.toBigDecimal().divide(1000.toBigDecimal())
        val displayUnit = if (kind == "active_energy") "kcal" else "km"
        if (details["soul_display_value"]?.jsonPrimitive?.content?.toBigDecimalOrNull()?.compareTo(display) != 0 ||
            details.string("soul_display_unit") != displayUnit) return null
        val time = snapshotTime(raw, zone) ?: return null
        if (time.observedAt > now) return null
        val identity = "qring-r20:$kind:snapshot:${time.date}"
        return obj {
            put("external_id", identity); put("kind", kind)
            put("report", obj {
                put("kind", kind); put("shape", "day_counter_snapshot"); put("local_date", time.date.toString())
                put("timezone", zone.id); put("observed_at", time.observedAt.toString())
                put("data", obj { put("value", canonicalValue.toPlainString()); put("unit", unit) })
                put("quality", JsonArray(listOf(JsonPrimitive("protocol_unverified"), JsonPrimitive("partial_day"))))
                put("origin", obj {
                    put("provider", "herzring"); put("profile", "qring-r20"); put("record_id", identity); put("source", "oudmon_sdk_core")
                    put("action", "72"); put("raw_value", rawValue); put("date_basis", "phone_local_after_clock_init")
                    firmware?.let { put("firmware", it) }; hardware?.let { put("hardware", it) }
                    put("resolution", obj { put("shape", "counter"); put("context", "background"); put("epoch_id", "qring-r20:local-day:${time.date}") })
                    put("details", JsonObject(details - "clock_proof" + mapOf("soul_display_value" to JsonPrimitive(display.toPlainString()))))
                })
            })
        }
    }
}

/** Known record categories are exhaustive: a future record must never disappear from a successful batch. */
object RingBatchRecords {
    enum class Category { CHANGE, SAMPLE, ACTIVITY, RAW_REPORT, WORKOUT_HR_CHUNK, WORKOUT_TRACK_CHUNK, DEVICE_STATE }
    val activityKinds = setOf("running_steps", "running_workouts", "workouts", "active_energy", "distance")
    private val observationKinds = setOf("steps", "sleep", "active_energy", "distance")
    private val sensorMetrics = setOf("heart_rate", "spo2", "temperature", "hrv", "stress")
    fun supportedKinds(state: JsonObject): Set<String> = buildSet {
        (state["supported_activity_report_kinds"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
            ?.filter { it in activityKinds }?.let(::addAll)
        (state["supported_observation_kinds"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
            ?.filter { it in observationKinds }?.forEach { add("observation:$it") }
        (state["supported_sensor_metrics"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
            ?.filter { it in sensorMetrics }?.forEach { add("sensor:$it") }
        (state["supported_raw_report_metrics"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
            ?.filter { it in setOf("temperature", "workouts") }?.forEach { add("raw:$it") }
        if (state.bool("supported_workout_hr_chunks") == true) add("workout_hr_chunks")
        if (state.bool("supported_workout_track_chunks") == true) add("workout_track_chunks")
        if (state.bool("supported_device_state") == true) add("device_state")
    }
    fun category(record: JsonObject): Category {
        val report = record["report"] as? JsonObject
        val observation = record["observation"] as? JsonObject
        val metric = record.string("metric")
        val deviceState = record["device_state"] as? JsonObject
        if (deviceState != null && report == null && observation == null && metric == null && record.string("kind") == null &&
            !deviceState.string("observed_at").isNullOrEmpty() &&
            record.string("external_id") == "device-state:${deviceState.string("observed_at")}") return Category.DEVICE_STATE
        if (deviceState != null) throw RingFailure("unknown_record_type", RingText.text(R.string.ring_native_042e7e492425))
        if (report != null && report.string("metric") == "temperature" && report.string("namespace") == "bulk" &&
            report.number("opcode") == 0x25 && report.string("layout") == "oudmon-temperature-25-ambiguous" &&
            report.string("kind") == null && observation == null && metric == null && record.string("kind") == null)
            return Category.RAW_REPORT
        if (report != null && report.string("metric") == "workouts" && report.string("namespace") == "core" &&
            report.number("opcode") == 0x13 && report.string("layout") == "oudmon-core-13-device-workout-v1" &&
            report.string("kind") == null && observation == null && metric == null && record.string("kind") == null)
            return Category.RAW_REPORT
        if (record.string("time_basis") == "phone_monotonic" && record.string("workout_id") != null &&
            record["points"] is JsonArray && record["samples"] == null && record["gaps"] is JsonArray &&
            report == null && observation == null && metric == null)
            return Category.WORKOUT_TRACK_CHUNK
        if (record.string("time_basis") == "phone_monotonic" && record.string("workout_id") != null &&
            record["samples"] is JsonArray && record["points"] == null && record["gaps"] is JsonArray && report == null && observation == null && metric == null)
            return Category.WORKOUT_HR_CHUNK
        if (report != null && observation == null && metric == null && report.string("kind") in activityKinds &&
            (record.string("kind") == null || record.string("kind") == report.string("kind"))) return Category.ACTIVITY
        if (observation != null && report == null && metric == null && record.string("kind") in observationKinds &&
            observation.string("kind") == record.string("kind")) return Category.CHANGE
        if (metric in sensorMetrics && report == null && observation == null && record.string("kind") == null)
            return Category.SAMPLE
        throw RingFailure("unknown_record_type", RingText.text(R.string.ring_native_0302a65d8081))
    }
    fun supported(record: JsonObject, kinds: Set<String>): Boolean = when (category(record)) {
        Category.ACTIVITY -> {
            val report = record["report"] as JsonObject
            val data = report["data"] as? JsonObject
            val details = (report["origin"] as? JsonObject)?.get("details") as? JsonObject
            val extendedRunning = report.string("kind") == "running_workouts" &&
                (data?.keys?.any { it in setOf("sport_plan", "active_duration_seconds", "pauses") } == true ||
                    details?.keys?.any { it in setOf("recording_source", "gps_requested") } == true)
            report.string("kind") in kinds && (!extendedRunning || "workouts" in kinds)
        }
        Category.RAW_REPORT -> "raw:${(record["report"] as JsonObject).string("metric")}" in kinds
        Category.WORKOUT_HR_CHUNK -> "workout_hr_chunks" in kinds
        Category.WORKOUT_TRACK_CHUNK -> "workout_track_chunks" in kinds
        Category.DEVICE_STATE -> "device_state" in kinds
        Category.SAMPLE -> record.string("metric") in setOf("heart_rate", "spo2", "temperature") || "sensor:${record.string("metric")}" in kinds
        Category.CHANGE -> record.string("kind") in setOf("steps", "sleep") || "observation:${record.string("kind")}" in kinds
    }
    fun body(records: List<JsonObject>, expectedCursor: JsonElement, cursor: String): JsonObject {
        val categories = records.groupBy(::category)
        val states = categories[Category.DEVICE_STATE] ?: emptyList()
        if (states.size > 1) throw RingFailure("invalid_batch", RingText.text(R.string.ring_native_d0cb8adb08c2))
        return obj {
            put("expected_cursor", expectedCursor); put("cursor", cursor)
            put("changes", JsonArray(categories[Category.CHANGE] ?: emptyList()))
            put("samples", JsonArray(categories[Category.SAMPLE] ?: emptyList()))
            // Older servers reject unknown request fields, so omit the extension when it is unused.
            categories[Category.ACTIVITY]?.let { put("activity_reports", JsonArray(it)) }
            categories[Category.RAW_REPORT]?.let { put("raw_reports", JsonArray(it)) }
            categories[Category.WORKOUT_HR_CHUNK]?.let { put("workout_hr_chunks", JsonArray(it)) }
            categories[Category.WORKOUT_TRACK_CHUNK]?.let { put("workout_track_chunks", JsonArray(it)) }
            states.singleOrNull()?.let { put("device_state", it["device_state"]!!) }
        }
    }
    fun records(body: JsonObject): List<JsonObject> = listOf("changes", "samples", "activity_reports", "raw_reports", "workout_hr_chunks", "workout_track_chunks").flatMap { field ->
        val expected = when (field) { "changes" -> Category.CHANGE; "samples" -> Category.SAMPLE; "workout_hr_chunks" -> Category.WORKOUT_HR_CHUNK;
            "workout_track_chunks" -> Category.WORKOUT_TRACK_CHUNK; "raw_reports" -> Category.RAW_REPORT; else -> Category.ACTIVITY }
        (body[field] as? JsonArray ?: if (body[field] == null) JsonArray(emptyList()) else
            throw RingFailure("invalid_batch", RingText.text(R.string.ring_native_c5029992bbee))).map {
            val record = it as? JsonObject ?: throw RingFailure("invalid_batch", RingText.text(R.string.ring_native_9a8cfc1f2a24))
            if (category(record) != expected) throw RingFailure("invalid_batch", RingText.text(R.string.ring_native_5995f9bdd8f8))
            record
        }
    } + if (body["device_state"] != null) {
        val state = body["device_state"] as? JsonObject ?: throw RingFailure("invalid_batch", RingText.text(R.string.ring_native_f6001e31d955))
        if (state.string("observed_at").isNullOrEmpty()) throw RingFailure("invalid_batch", RingText.text(R.string.ring_native_204896f731b3))
        listOf(obj { put("external_id", "device-state:${state.string("observed_at")}"); put("device_state", state) })
    } else emptyList()
    fun supportedBody(body: JsonObject, kinds: Set<String>): Boolean = records(body).all { supported(it, kinds) }
}
