package assistant.mobile.platform.ring

import java.time.Instant
import java.time.ZoneId
import kotlinx.serialization.json.*

/** A device record remains an archive fact until its epoch, sport and units are qualified. */
object RingSportHistoryObservation {
    fun convert(raw: JsonObject, zone: ZoneId, firmware: String?, hardware: String?, observedAt: Instant): JsonObject? {
        if (raw.string("metric") != "workouts" || raw.number("opcode") != 0x13 || raw.string("source") != "oudmon_sdk_core" ||
            raw.string("quality") != "protocol_unverified" || raw.string("timezone") != zone.id ||
            raw["value"] !in listOf(null, JsonNull) || raw["unit"] !in listOf(null, JsonNull) ||
            raw["minute_of_day"] !in listOf(null, JsonNull)) return null
        val details = raw["details"] as? JsonObject ?: return null
        val layout = "oudmon-core-13-device-workout-v1"
        if (details.string("type") != "raw_device_workout" || details.string("layout") != layout ||
            details.string("decode_version") != layout || details.string("date_basis") != "phone_capture_local_date" ||
            details.string("time_basis") != "first_phone_capture_day") return null
        val frames = details.number("declared_frames")?.takeIf { it in 4..127 } ?: return null
        val bytes = details.number("payload_bytes")?.takeIf { it == 13 * (frames - 1) } ?: return null
        fun decodeHex(value: String?, size: Int): List<Int>? {
            if (value == null || value.length != size * 2 || value.any { it !in '0'..'9' && it !in 'a'..'f' }) return null
            return value.chunked(2).map { it.toInt(16) }
        }
        val bodyHex = details.string("payload_hex") ?: return null
        val body = decodeHex(bodyHex, bytes) ?: return null
        val header = decodeHex(details.string("header_hex"), 14) ?: return null
        if (header[0] != 0 || header[1] != frames) return null
        val keys = listOf("raw_start_time", "duration_raw", "sport_type_raw", "steps_raw", "distance_raw", "calories_raw", "heart_rate_count")
        val fields = keys.mapIndexed { index, key ->
            val value = (details[key] as? JsonPrimitive)?.longOrNull ?: return null
            if (value !in 0..0xffffffffL) return null
            val wire = (0..3).fold(0L) { result, byte -> result or (body[index * 4 + byte].toLong() shl (byte * 8)) }
            if (value != wire) return null
            value
        }
        val count = fields[6].takeIf { it <= bytes - 28 }?.toInt() ?: return null
        val heartRate = details["heart_rate_bytes"] as? JsonArray ?: return null
        if (heartRate.size != count || heartRate.map { (it as? JsonPrimitive)?.intOrNull } != body.subList(28, 28 + count) ||
            details.number("ignored_tail_bytes") != bytes - 28 - count ||
            details.bool("ignored_tail_nonzero") != body.drop(28 + count).any { it != 0 }) return null
        val identity = "qring-r20:device-workout:${fields[0]}:${fields[2]}"
        return obj {
            put("external_id", identity)
            put("report", obj {
                put("metric", "workouts"); put("namespace", "core"); put("opcode", 0x13); put("layout", layout)
                put("time_basis", "first_phone_capture_day"); put("local_date", observedAt.atZone(zone).toLocalDate().toString())
                put("timezone", zone.id); put("observed_at", observedAt.toString()); put("payload_hex", bodyHex)
                put("details", JsonObject(details - "payload_hex" - "layout" - "time_basis"))
                put("quality", JsonArray(listOf("protocol_unverified", "epoch_unknown", "unit_unknown", "sport_type_unknown", "hr_cadence_unknown").map(::JsonPrimitive)))
                put("origin", obj {
                    put("provider", "herzring"); put("profile", "qring-r20"); put("record_id", identity)
                    put("source", "oudmon_sdk_core"); put("action", "19"); put("date_basis", "phone_capture_local_date")
                    firmware?.let { put("firmware", it) }; hardware?.let { put("hardware", it) }
                })
            })
        }
    }
}

object RingHistoryCheckpoint {
    fun workoutCursor(checkpoint: JsonObject): Long = (checkpoint["workout_cursor"] as? JsonPrimitive)?.longOrNull
        ?.takeIf { it in 0..0xffffffffL } ?: 0L
    fun forSession(saved: JsonObject, localDate: String, timezone: String, fresh: Boolean): JsonObject {
        if (!fresh && saved.string("local_date") == localDate && saved.string("timezone") == timezone) return saved
        return obj { put("completed_queries", JsonArray(emptyList())); put("workout_cursor", if (fresh) 0L else workoutCursor(saved)) }
    }
    fun restart(checkpoint: JsonObject): JsonObject = obj {
        put("completed_queries", JsonArray(emptyList())); put("workout_cursor", workoutCursor(checkpoint))
    }
}
