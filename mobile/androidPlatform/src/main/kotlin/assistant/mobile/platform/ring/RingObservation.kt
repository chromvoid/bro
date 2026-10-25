package assistant.mobile.platform.ring

import java.time.*
import kotlinx.serialization.json.*

/** Reject ambiguous timestamps/units instead of turning an unknown payload into a health value. */
object RingObservation {
    data class Conversion(val record: JsonObject?, val reason: String?, val protocolVerified: Boolean = false) {
        val metricStatus: String get() = if (protocolVerified) "data" else "unverified"
    }
    private data class Timestamp(val instant: Instant?, val reason: String? = null)
    private fun rejected(reason: String) = Conversion(null, reason)
    private fun accepted(record: JsonObject, protocolVerified: Boolean = false) = Conversion(record, null, protocolVerified)
    private fun JsonObject.unreadable(key: String) = if (this[key] == null || this[key] == JsonNull) "missing" else "invalid"

    fun convert(raw: JsonObject, zone: ZoneId, firmware: String?, hardware: String?, now: Instant = Instant.now()): JsonObject? =
        explain(raw, zone, firmware, hardware, now).record

    /** Diagnostics and native status share the same qualification as the imported record. */
    fun explain(raw: JsonObject, zone: ZoneId, firmware: String?, hardware: String?, now: Instant = Instant.now()): Conversion {
        val metric = raw.string("metric") ?: return rejected("metric_${raw.unreadable("metric")}")
        if (metric == "workouts" && raw.number("opcode") == 0x13) {
            val record = RingSportHistoryObservation.convert(raw, zone, firmware, hardware, now)
            return if (record == null) rejected("workout_history_rejected") else accepted(record)
        }
        if (metric == "temperature" && (raw["details"] as? JsonObject)?.string("type") == "raw_temperature") {
            val record = rawTemperature(raw, zone, firmware, hardware, now)
            return if (record == null) rejected("raw_temperature_rejected") else accepted(record)
        }
        if (metric in setOf("active_energy", "distance") && raw.number("opcode") == 0x48) {
            val record = RingActivityReport.quantitySnapshot(raw, zone, firmware, hardware, now)
            return if (record == null) rejected("quantity_snapshot_rejected") else accepted(record)
        }
        if (metric !in setOf("steps", "sleep", "heart_rate", "spo2", "temperature", "hrv", "stress")) return rejected("metric_unsupported")
        val dateText = raw.string("local_date") ?: return rejected("local_date_${raw.unreadable("local_date")}")
        val date = try { LocalDate.parse(dateText) } catch (_: DateTimeException) { return rejected("local_date_invalid") }
        if (raw.string("timezone") != zone.id) return rejected("timezone_mismatch")
        when (raw.string("quality")) {
            "missing" -> return rejected("quality_missing")
            "out_of_range" -> return rejected("quality_out_of_range")
            "temperature_layout_unconfirmed" -> return rejected("quality_temperature_layout_unconfirmed")
            "ambiguous_time" -> return rejected("quality_ambiguous_time")
        }
        val details = raw["details"] as? JsonObject ?: obj {}
        if (metric == "steps" && raw.number("opcode") == 0x48) return rejected("steps_redundant_aggregate") // 43 detail buckets are canonical.
        fun minute(key: String): Timestamp {
            val value = raw.number(key) ?: return Timestamp(null, "${key}_${raw.unreadable(key)}")
            if (value !in -1440..2880) return Timestamp(null, "${key}_out_of_range")
            val localTime = date.atStartOfDay().plusMinutes(value.toLong())
            val offsets = zone.rules.getValidOffsets(localTime)
            return if (offsets.size == 1) Timestamp(localTime.toInstant(offsets.first())) else Timestamp(null, "${key}_ambiguous")
        }
        fun sleepInstant(boundary: String): Timestamp {
            val dateKey = "${boundary}_date"
            val minuteKey = "${boundary}_minute"
            val dayText = details.string(dateKey) ?: return Timestamp(null, "sleep_${dateKey}_${details.unreadable(dateKey)}")
            val day = try { LocalDate.parse(dayText) } catch (_: DateTimeException) { return Timestamp(null, "sleep_${dateKey}_invalid") }
            val minute = details.number(minuteKey) ?: return Timestamp(null, "sleep_${minuteKey}_${details.unreadable(minuteKey)}")
            if (minute !in 0..1440) return Timestamp(null, "sleep_${minuteKey}_out_of_range")
            return try {
                val localTime = day.atStartOfDay().plusMinutes(minute.toLong())
                val offsets = zone.rules.getValidOffsets(localTime)
                if (offsets.size == 1) Timestamp(localTime.toInstant(offsets.first())) else Timestamp(null, "sleep_${boundary}_time_ambiguous")
            } catch (_: DateTimeException) { Timestamp(null, "sleep_${boundary}_time_out_of_range") }
        }
        fun midnight(day: LocalDate, boundary: String): Timestamp {
            val localTime = day.atStartOfDay(); val offsets = zone.rules.getValidOffsets(localTime)
            return if (offsets.size == 1) Timestamp(localTime.toInstant(offsets.first())) else Timestamp(null, "${boundary}_midnight_ambiguous")
        }
        val startTime = if (metric == "sleep") sleepInstant("start") else {
            val first = minute("start_minute")
            val sampled = if (first.instant != null) first else minute("minute_of_day").let {
                if (it.instant != null || raw.unreadable("start_minute") == "missing") it else first
            }
            if (sampled.instant != null || metric != "steps") sampled else midnight(date, "start")
        }
        val start = startTime.instant ?: return rejected(startTime.reason!!)
        val interval = details.number("interval_minutes")
        val endTime = if (metric == "sleep") sleepInstant("end") else {
            val explicit = minute("end_minute")
            if (explicit.instant != null) explicit else if (metric == "steps") {
                if (interval != null && raw.number("minute_of_day") != null) Timestamp(start.plusSeconds(interval.toLong() * 60))
                else midnight(date.plusDays(1), "end")
            } else Timestamp(start)
        }
        val measuredEnd = endTime.instant ?: return rejected(endTime.reason!!)
        val partial = metric == "steps" && date == now.atZone(zone).toLocalDate() && measuredEnd > now
        val end = if (partial) now else measuredEnd
        if (start > now) return rejected("start_in_future")
        if (end > now) return rejected("end_in_future")
        if (end < start) return rejected("end_before_start")
        if (metric == "steps" && end == start) return rejected("steps_empty_interval")
        val value = raw["value"]?.jsonPrimitive?.contentOrNull
        val min = raw["reported_min"]?.jsonPrimitive?.contentOrNull ?: details["minimum"]?.jsonPrimitive?.contentOrNull
        val max = raw["reported_max"]?.jsonPrimitive?.contentOrNull ?: details["maximum"]?.jsonPrimitive?.contentOrNull
        val source = raw.string("source") ?: "history"
        val opcode = raw.number("opcode")
        if (metric in setOf("hrv", "stress")) {
            if (source != "oudmon_sdk_core" || opcode != (if (metric == "hrv") 0x39 else 0x37) ||
                details.string("type") != "core_scalar_history" || details.string("decode_version") != "oudmon-core-scalar-v1")
                return rejected("scalar_history_source_unsupported")
            val spacing = interval ?: return rejected("scalar_history_interval_missing")
            val frames = details.number("declared_frames") ?: return rejected("scalar_history_frames_missing")
            val received = details.number("received_samples") ?: return rejected("scalar_history_samples_missing")
            val index = raw.number("sample_index") ?: return rejected("scalar_history_index_missing")
            val rawValue = raw.number("raw_value") ?: return rejected("scalar_history_raw_missing")
            if (spacing !in 1..127 || 1440 % spacing != 0 || frames !in 2..127 || received != 12 + (frames - 2) * 13 ||
                details.number("wire_day_offset") !in 0..29 || index !in 0 until minOf(received, 1440 / spacing) ||
                raw.number("minute_of_day") != index * spacing || rawValue !in 1..255 ||
                value?.toBigDecimalOrNull()?.compareTo(rawValue.toBigDecimal()) != 0 || raw.string("quality") != "valid" ||
                details.number("ignored_tail_samples") != received - minOf(received, 1440 / spacing) ||
                details.bool("ignored_tail_nonzero") == null) return rejected("scalar_history_layout_invalid")
        }
        if (metric == "sleep") {
            if (opcode !in setOf(0x27, 0x3e)) return rejected("sleep_opcode_unsupported")
            if (source != "oudmon_sdk_bulk") return rejected("sleep_source_unsupported")
            if (details.string("end_date") != date.toString()) return rejected("sleep_report_date_mismatch")
        }
        val position = when (metric) {
            "steps" -> if (raw.number("minute_of_day") != null && interval != null) "${raw.number("minute_of_day")}:${raw.number("sample_index") ?: 0}" else "day"
            else -> "${raw.number("minute_of_day") ?: raw.number("start_minute") ?: 0}:${raw.number("sample_index") ?: 0}"
        }
        // Each bulk action reports one revisable sleep record per ring-local day.
        // Its boundaries and stages are content, not the logical record identity.
        val identity = if (metric == "sleep") "qring-r20:sleep-v2:$date:$opcode:$source"
            else raw.string("external_id") ?: "qring-r20:$metric:$date:${opcode ?: "unknown"}:$position:$source"
        // Soul comparison confirmed hourly sums for this exact ring identity. Its
        // SDK uses quarter-hour coordinates; an hourly record keeps the first
        // quarter's index so existing whole-hour identities receive a revision.
        val minuteOfDay = raw.number("minute_of_day")
        val protocolVerified = metric == "steps" && opcode == 0x43 && source == "oudmon_sdk_core" &&
            firmware == "RF20_2.10.07_260131" && hardware == "RF20_V2.1" && raw.string("quality") == "valid" &&
            details.string("type") == "steps" && interval == 60 && details.number("duration_minutes") == 60 &&
            minuteOfDay != null && minuteOfDay in 0 until 1440 && minuteOfDay % 60 == 0 &&
            raw.number("sample_index") == minuteOfDay / 15 && minute("minute_of_day").instant == start &&
            measuredEnd == start.plusSeconds(3600)
        val flags = mutableListOf<JsonPrimitive>()
        if (!protocolVerified) flags += JsonPrimitive("protocol_unverified")
        if (metric == "hrv") flags += JsonPrimitive("hrv_statistic_unknown")
        if (metric == "stress") flags += JsonPrimitive("stress_scale_unknown")
        if (partial) flags += JsonPrimitive("partial_day")
        val origin = obj {
            put("provider", "herzring"); put("profile", "qring-r20"); put("record_id", identity)
            firmware?.let { put("firmware", it) }; hardware?.let { put("hardware", it) }
            put("date_basis", "ring_local_date"); put("source", source)
            raw["raw_value"]?.let { put("raw_value", it) }
            raw["opcode"]?.jsonPrimitive?.contentOrNull?.let { put("action", it) }
            put("details", details)
            if (metric in setOf("hrv", "stress")) put("resolution", obj {
                put("shape", "reported_unknown"); put("context", "background")
                if (metric == "hrv") put("hrv_statistic", "unknown")
                else put("definition_id", "oudmon-device-stress-unknown-v1")
            })
            raw["sensor_location"]?.let { put("sensor_location", it) }
        }
        if (metric in setOf("steps", "sleep")) {
            if (raw.string("unit") != if (metric == "steps") "count" else "minutes") return rejected("unit_mismatch")
            val steps = try { value?.toBigDecimalOrNull()?.longValueExact() } catch (_: ArithmeticException) { null }
            if (metric == "steps" && (steps?.let { it in 0..1_000_000 } != true)) return rejected("steps_value_invalid")
            if (metric == "sleep" && end == start) return rejected("sleep_empty_interval")
            if (metric == "sleep" && details.string("time_interpretation").isNullOrEmpty()) return rejected("sleep_time_interpretation_missing")
            var sleepMinutes: Long? = null
            var sleepStages: JsonArray? = null
            if (metric == "sleep") {
                val rawStages = details["stages"] as? JsonArray ?: JsonArray(emptyList())
                // Opcode 62 has a different, unverified taxonomy. Keep its raw
                // codes and interval without presenting night-stage meanings.
                val taxonomyKnown = opcode == 0x27
                val stageNames = if (taxonomyKnown) mapOf(2 to "light", 3 to "deep", 4 to "rem", 5 to "awake") else emptyMap()
                if (!taxonomyKnown) flags += JsonPrimitive("sleep_taxonomy_unverified")
                var position = start
                var asleep = 0L
                var positioned = true
                val unknown = rawStages.any { (it as? JsonObject)?.number("raw_type") !in stageNames }
                val stages = mutableListOf<JsonElement>()
                rawStages.forEach { rawStage ->
                    if (!positioned) return@forEach
                    val stage = rawStage as? JsonObject
                    val type = stage?.number("raw_type")
                    val duration = stage?.number("duration_minutes")
                    if (type == null || type !in 0..255 || duration == null || duration < 0) {
                        positioned = false
                        return@forEach
                    }
                    val next = position.plusSeconds(duration.toLong() * 60)
                    if (next > end) {
                        positioned = false
                        return@forEach
                    }
                    if (duration > 0) stages += obj {
                        put("start_at", position.toString()); put("end_at", next.toString())
                        put("stage", stageNames[type] ?: "unknown:$type")
                    }
                    if (type in setOf(2, 3, 4)) asleep += duration
                    position = next
                }
                val complete = positioned && position == end && rawStages.isNotEmpty()
                if (!complete) flags += JsonPrimitive("duration_mismatch")
                if (unknown) flags += JsonPrimitive("unknown_sleep_stage")
                if (complete && taxonomyKnown && !unknown) sleepMinutes = asleep
                sleepStages = JsonArray(stages)
            }
            val observation = obj {
                put("kind", metric); put("start_at", start.toString()); put("end_at", end.toString()); put("timezone", zone.id)
                put("method", "device_report"); put("quality", JsonArray(flags))
                if (protocolVerified) put("algorithm_version", "qring-r20-hourly-v1")
                put("origin", JsonObject(origin + ("device" to JsonPrimitive("qring-r20:${firmware ?: "unknown"}"))))
                put("data", obj {
                    if (metric == "steps") put("value", steps!!)
                    else { put("value", sleepMinutes?.let { JsonPrimitive(it.toString()) } ?: JsonNull); sleepStages?.let { put("stages", it) } }
                })
            }
            return accepted(obj { put("external_id", identity); put("kind", metric); put("observation", observation) }, protocolVerified)
        }
        val unit = when (metric) { "heart_rate" -> "bpm"; "spo2" -> "%"; "hrv" -> "ms"; "stress" -> "index"; else -> "°C" }
        val rawUnit = when (metric) { "heart_rate" -> "bpm"; "spo2" -> "percent"; "hrv" -> "ms"; "stress" -> "index"; else -> "celsius" }
        if (raw.string("unit") !in setOf(unit, rawUnit)) return rejected("unit_mismatch")
        val range = when (metric) { "heart_rate" -> 20.0..250.0; "spo2" -> 0.0..100.0; "hrv", "stress" -> 1.0..255.0; else -> -20.0..60.0 }
        if (value != null && value.toDoubleOrNull()?.let { it in range } != true) return rejected("sensor_value_invalid")
        if (value == null && (min?.toDoubleOrNull()?.let { it in range } != true || max?.toDoubleOrNull()?.let { it in range } != true)) return rejected("sensor_range_invalid")
        if (min != null && max != null && min.toDouble() > max.toDouble()) return rejected("sensor_range_reversed")
        return accepted(obj {
            put("external_id", identity); put("metric", metric); put("start_at", start.toString()); put("end_at", end.toString())
            put("timezone", zone.id); put("unit", unit); put("quality", JsonArray(flags)); put("origin", origin)
            value?.let { put("value", it) }; min?.let { put("reported_min", it) }; max?.let { put("reported_max", it) }
        })
    }

    /** Preserve a checked frame without selecting a candidate numeric layout. */
    private fun rawTemperature(raw: JsonObject, zone: ZoneId, firmware: String?, hardware: String?, now: Instant): JsonObject? {
        val details = raw["details"] as? JsonObject ?: return null
        if (raw.number("opcode") != 0x25 || raw.string("source") != "oudmon_sdk_bulk" || raw.string("timezone") != zone.id ||
            raw.string("quality") != "temperature_layout_unconfirmed" || raw["value"] !in listOf(null, JsonNull) ||
            raw["unit"] !in listOf(null, JsonNull) || raw["minute_of_day"] !in listOf(null, JsonNull)) return null
        val date = try { LocalDate.parse(raw.string("local_date") ?: return null) } catch (_: DateTimeException) { return null }
        val layout = details.string("layout") ?: return null
        val frame = details.string("payload_hex") ?: return null
        val day = details.number("day_offset") ?: return null
        val interval = details.number("interval_minutes") ?: return null
        if (layout != "oudmon-temperature-25-ambiguous" || details.string("decode_version") != "oudmon-bulk-25-raw-v1" ||
            details.number("page_index") != 0 || day !in 0..29 || raw.number("day_offset") != day ||
            interval !in 1..255 || 1440 % interval != 0 || frame.length !in 16..4096 || frame.length % 2 != 0 ||
            frame.any { it !in '0'..'9' && it !in 'a'..'f' } || !frame.startsWith("bc25")) return null
        val bytes = frame.chunked(2).map { it.toInt(16) }
        if (bytes[2] + bytes[3] * 256 != bytes.size - 6 || bytes[6] != day || bytes[7] != interval ||
            bytes.size - 8 > 1440 / interval) return null
        var crc = 0xffff
        for (byte in bytes.drop(6)) {
            crc = crc xor byte
            repeat(8) { crc = if (crc and 1 != 0) (crc ushr 1) xor 0xa001 else crc ushr 1 }
        }
        if (bytes[4] + bytes[5] * 256 != crc) return null
        val candidates = details["candidates"] as? JsonArray ?: return null
        if (candidates != JsonArray(listOf(JsonPrimitive("file_u8_tenths_plus20"), JsonPrimitive("legacy_u8_tenths_plus32_rle")))) return null
        // A clock adjustment after parsing can invalidate optional timing proof,
        // but must not discard the checked device frame or poison its upload.
        val capturedDetails = if (details["soul_time_context"]?.let {
            RingTemperatureCapture.checkedContext(it, now) == null } == true)
            JsonObject(details - "soul_time_context") else details
        val identity = "qring-r20:raw-temperature:$date:$day:0:$layout"
        return obj {
            put("external_id", identity)
            put("report", obj {
                put("metric", "temperature"); put("local_date", date.toString()); put("timezone", zone.id)
                put("observed_at", now.toString()); put("namespace", "bulk"); put("opcode", 0x25); put("layout", layout)
                put("payload_hex", frame); put("details", JsonObject(capturedDetails - "payload_hex" - "layout"))
                put("quality", JsonArray(listOf(JsonPrimitive("protocol_unverified"), JsonPrimitive("ambiguous_layout"))))
                put("origin", obj {
                    put("provider", "herzring"); put("profile", "qring-r20"); put("record_id", identity)
                    put("source", "oudmon_sdk_bulk"); put("action", "37"); put("date_basis", "ring_local_date")
                    firmware?.let { put("firmware", it) }; hardware?.let { put("hardware", it) }
                })
            })
        }
    }
}
