package assistant.mobile.platform.ring

import java.math.BigDecimal
import kotlinx.serialization.json.*

/** Encodes a real verified-protocol receipt; numeric quality is separate from protocol provenance. */
object RingManualObservation {
    private data class Metric(val unit: String, val minimum: Int, val maximum: Int)
    private val metrics = mapOf("heart_rate" to Metric("bpm", 0, 300), "spo2" to Metric("%", 0, 100),
        "temperature" to Metric("°C", -40, 100), "hrv" to Metric("ms", 0, 255), "stress" to Metric("index", 0, 255))
    /** Capture telemetry refers to the persisted point, without inventing a live/background read. */
    fun capture(record: JsonObject): JsonObject? {
        val metric = record.string("metric")?.takeIf { it in metrics } ?: return null
        val at = record.string("start_at")?.takeIf { it == record.string("end_at") } ?: return null
        val origin = record["origin"] as? JsonObject ?: return null
        val resolution = origin["resolution"] as? JsonObject ?: return null
        if (origin.string("source") != "manual_measurement" || resolution.string("shape") != "point" ||
            resolution.string("context") != "manual") return null
        return obj { put(metric, obj {
            put("captured_at", at); put("status", "complete"); put("scope", "spot"); put("context", "manual")
        }) }
    }
    fun encode(binding: RingWorkoutBinding, clock: RingWorkoutClock, observation: JsonObject): JsonObject? {
        val metric = observation.string("metric") ?: return null
        val specification = metrics[metric] ?: return null
        if (observation.string("unit") != specification.unit || observation.string("timestamp_basis") != "received_monotonic" ||
            observation["received_monotonic_ms"]?.jsonPrimitive?.longOrNull != clock.elapsedRealtimeMs) return null
        val numeric = (observation["value"] as? JsonPrimitive)?.takeUnless { it.isString } ?: return null
        val value = numeric.content.toBigDecimalOrNull() ?: return null
        if (value < BigDecimal(specification.minimum) || value > BigDecimal(specification.maximum)) return null
        val decimal = value.toPlainString().takeIf { it.length <= 24 } ?: return null
        val nativeQuality = observation.string("sample_quality")?.takeIf { it in setOf("valid", "invalid") } ?: return null
        val deviceIndex = metric in setOf("hrv", "stress")
        if (deviceIndex && value.stripTrailingZeros().scale() > 0) return null
        val invalid = nativeQuality == "invalid" || metric == "heart_rate" && (value < BigDecimal(20) || value > BigDecimal(250)) ||
            deviceIndex && value.signum() == 0
        val id = "phone-control:${binding.collectorId}:$metric:${clock.bootId}:${clock.elapsedRealtimeMs}"
        return obj {
            put("external_id", id); put("metric", metric); put("value", decimal)
            put("start_at", clock.utcAt.toString()); put("end_at", clock.utcAt.toString()); put("timezone", binding.timezone)
            put("unit", specification.unit)
            put("quality", JsonArray(listOfNotNull(JsonPrimitive("protocol_verified"), if (invalid) JsonPrimitive("invalid") else null,
                if (metric == "hrv") JsonPrimitive("hrv_statistic_unknown") else null,
                if (metric == "stress") JsonPrimitive("stress_scale_unknown") else null)))
            put("origin", obj {
                put("provider", "herzring"); put("profile", "qring-r20"); put("source", "manual_measurement")
                put("record_id", id); put("date_basis", "phone_received")
                put("resolution", obj {
                    put("shape", "point"); put("context", "manual"); put("measurement_kind", if (metric == "temperature") "skin" else "spot"); put("location", "finger")
                    if (metric == "temperature") put("temperature_basis", "absolute")
                    if (metric == "hrv") put("hrv_statistic", "unknown")
                    if (metric == "stress") put("definition_id", "oudmon-device-stress-unknown-v1")
                })
            })
        }
    }
}
