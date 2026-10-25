package assistant.mobile.platform.ring

import assistant.mobile.platform.R

import android.os.Build
import java.time.ZonedDateTime
import java.util.UUID
import kotlinx.serialization.json.*

/** The compiled portable registry is the authority for hardware support. The page cannot supply it. */
class RingMeasurementProtocol(
    private val generation: Long,
    timezone: String,
    hardware: String?,
    firmware: String?,
) : AutoCloseable {
    private val identity = obj {
        put("model", "qring-r20"); put("hardware", hardware ?: "unknown"); put("firmware", firmware ?: "unknown")
    }
    private val handle: Long
    init {
        val local = ZonedDateTime.now(java.time.ZoneId.of(timezone))
        val config = obj {
            put("generation", generation); put("timezone", timezone); put("local_date", local.toLocalDate().toString())
            put("history_days", 1); put("completed_queries", JsonArray(emptyList()))
            put("clock", obj {
                put("year", local.year); put("month", local.monthValue); put("day", local.dayOfMonth)
                put("hour", local.hour); put("minute", local.minute); put("second", local.second); put("language", 10)
            })
            put("android", obj { put("sdk", Build.VERSION.SDK_INT); put("model", Build.MODEL) })
        }
        if (NativeRing.apiVersion() != 1) throw RingFailure("native_version", RingText.text(R.string.ring_native_09fea3435a3e))
        handle = NativeRing.create(config.toString().toByteArray(Charsets.UTF_8))
        if (handle == 0L) throw RingFailure("native_init", RingText.text(R.string.ring_native_3550486109f4))
    }
    fun dispatch(type: String, fields: JsonObject = obj {}, now: Long = android.os.SystemClock.elapsedRealtime()): List<JsonObject> {
        val input = obj { put("type", type); put("generation", generation); fields.forEach { (key, value) -> put(key, value) } }
        val value = ringJson.parseToJsonElement(NativeRing.dispatch(handle, input.toString().toByteArray(Charsets.UTF_8), now)
            .toString(Charsets.UTF_8)) as? JsonArray ?: throw RingFailure("native_response", RingText.text(R.string.ring_native_67f56ad96ef8))
        return value.map { it as? JsonObject ?: throw RingFailure("native_response", RingText.text(R.string.ring_native_57f9adbbde05)) }
    }
    fun capabilities(): JsonObject = dispatch("control_capabilities", obj { put("identity", identity) })
        .singleOrNull { it.string("type") == "control_capabilities" }
        ?: throw RingFailure("native_response", RingText.text(R.string.ring_native_6c6f8bbd4a76))
    fun deviceCapabilities(): JsonObject = dispatch("device_capabilities", obj { put("identity", identity) })
        .singleOrNull { it.string("type") == "device_capabilities" }
        ?: throw RingFailure("native_response", RingText.text(R.string.ring_native_0bf8c2ad5135))
    fun request(command: JsonObject, operationId: String = UUID.randomUUID().toString()): List<JsonObject> =
        dispatch("control_request", obj { put("request", obj { put("operation_id", operationId); put("command", command) }) })
    fun cancel(operationId: String): List<JsonObject> = dispatch("control_cancel", obj { put("operation_id", operationId) })
    override fun close() = NativeRing.destroy(handle)
}

object RingMeasurementCommands {
    fun capability(capabilities: JsonObject, kind: String): JsonObject? =
        (capabilities["capabilities"] as? JsonArray)?.mapNotNull { it as? JsonObject }?.singleOrNull { it.string("kind") == kind }
    fun supported(capabilities: JsonObject, kind: String): Boolean = capability(capabilities, kind)?.let {
        it.string("status") == "verified" && it.bool("writable") == true
    } == true
    fun readable(capabilities: JsonObject, kind: String): Boolean = capability(capabilities, "${kind}_schedule")?.let {
        it.bool("readable") == true
    } == true
    fun kind(command: JsonObject): String? = when (command.string("type")) {
        "start_workout", "stop_workout" -> "workout"
        "start_live_heart_rate", "stop_live_heart_rate" -> "live_heart_rate"
        "read_settings", "set_schedule" -> command.string("kind")?.let { "${it}_schedule" }
        "set_heart_rate_alerts" -> "heart_rate_alerts"
        "measure_once" -> command.string("metric")?.let { "manual_$it" }
        else -> null
    }
    fun safeStop(capabilities: JsonObject): Boolean = listOf("workout", "live_heart_rate").all {
        capability(capabilities, it)?.let { cap -> cap.string("status") == "verified" && cap.bool("writable") == true && cap.bool("safe_stop_verified") == true } == true
    }
    fun workoutSupported(capabilities: JsonObject): Boolean = supported(capabilities, "workout") && supported(capabilities, "live_heart_rate")
    fun parse(type: String, payload: JsonObject, capabilities: JsonObject): List<JsonObject> = when (type) {
        "ring.measurement.read" -> {
            if (payload.keys != setOf("kinds")) throw RingFailure("bad_payload", RingText.text(R.string.ring_native_a0fcee66e847))
            val kinds = payload["kinds"] as? JsonArray ?: throw RingFailure("bad_payload", RingText.text(R.string.ring_native_030137ffe111))
            val values = kinds.map { (it as? JsonPrimitive)?.takeIf { value -> value.isString }?.content
                ?: throw RingFailure("bad_payload", RingText.text(R.string.ring_native_3856fb8272d0)) }
            if (values.isEmpty() || values.size > 5 || values.distinct().size != values.size ||
                values.any { it !in setOf("heart_rate", "spo2", "temperature", "hrv", "stress") })
                throw RingFailure("bad_payload", RingText.text(R.string.ring_native_1081c87478b1))
            values.map { obj { put("type", "read_settings"); put("kind", it) } }
        }
        "ring.measurement.measure" -> {
            if (payload.keys != setOf("metric")) throw RingFailure("bad_payload", RingText.text(R.string.ring_native_985122d7a339))
            val metric = payload.string("metric")?.takeIf { it in setOf("heart_rate", "spo2", "temperature", "hrv", "stress") }
                ?: throw RingFailure("bad_payload", RingText.text(R.string.ring_native_a80c8a704eeb))
            listOf(obj { put("type", "measure_once"); put("metric", metric) })
        }
        "ring.measurement.configure" -> {
            if (payload.isEmpty() || payload.keys.any { it !in setOf("hr_interval_minutes", "hr_enabled", "temperature_interval_minutes", "temperature_enabled", "spo2_night", "hrv_enabled", "stress_enabled", "heart_rate_alerts") })
                throw RingFailure("bad_payload", RingText.text(R.string.ring_native_9fce2cdfacf0))
            buildList {
                fun schedule(key: String, enabledKey: String, kind: String) {
                    if (key !in payload && enabledKey !in payload) return
                    var interval = if (key in payload) (payload[key] as? JsonPrimitive)?.takeUnless { it.isString }?.intOrNull
                        ?: throw RingFailure("bad_payload", RingText.text(R.string.ring_native_cfd60b833038)) else null
                    if (interval != null && interval !in 1..1_440) throw RingFailure("bad_payload", RingText.text(R.string.ring_native_e0f783748ea9))
                    val enabled = if (enabledKey in payload) (payload[enabledKey] as? JsonPrimitive)?.takeUnless { it.isString }?.booleanOrNull
                        ?: throw RingFailure("bad_payload", RingText.text(R.string.ring_native_e7a62defea43)) else true
                    val cap = capability(capabilities, "${kind}_schedule")
                    if (kind == "temperature" && interval == 1 && cap?.string("status") == "verified") {
                        val intervals = cap["supported_intervals_minutes"] as? JsonArray
                        if (intervals?.contains(JsonPrimitive(1)) != true && intervals?.contains(JsonPrimitive(5)) == true) interval = 5
                    }
                    if (interval != null && cap?.string("status") == "verified" &&
                        (cap["supported_intervals_minutes"] as? JsonArray)?.contains(JsonPrimitive(interval)) != true)
                        throw RingFailure("unsupported_interval", RingText.text(R.string.ring_native_32cf4dca6256))
                    if (enabled && kind == "temperature" && cap?.string("status") == "verified" && cap.bool("autonomous_night") != true)
                        throw RingFailure("unsupported_night_mode", RingText.text(R.string.ring_native_b32cba6396de))
                    add(obj { put("type", "set_schedule"); put("kind", kind); put("enabled", enabled); interval?.let { put("interval_minutes", it) } })
                }
                schedule("hr_interval_minutes", "hr_enabled", "heart_rate")
                schedule("temperature_interval_minutes", "temperature_enabled", "temperature")
                fun enabled(key: String, kind: String, requiresNight: Boolean = true) {
                    val enabled = (payload[key] as? JsonPrimitive)?.takeUnless { it.isString }?.booleanOrNull
                        ?: throw RingFailure("bad_payload", RingText.text(R.string.ring_native_e7a62defea43))
                    val cap = capability(capabilities, "${kind}_schedule")
                    if (requiresNight && enabled && cap?.string("status") == "verified" && cap.bool("autonomous_night") != true)
                        throw RingFailure("unsupported_night_mode", RingText.text(R.string.ring_native_39ff960957dc))
                    add(obj { put("type", "set_schedule"); put("kind", kind); put("enabled", enabled) })
                }
                if ("spo2_night" in payload) enabled("spo2_night", "spo2")
                if ("hrv_enabled" in payload) enabled("hrv_enabled", "hrv")
                if ("stress_enabled" in payload) enabled("stress_enabled", "stress", requiresNight = false)
                if ("heart_rate_alerts" in payload) {
                    val alerts = payload["heart_rate_alerts"] as? JsonObject
                        ?: throw RingFailure("bad_payload", RingText.text(R.string.ring_native_5fc830142f5c))
                    if (alerts.keys != setOf("low_bpm", "high_bpm")) throw RingFailure("bad_payload", RingText.text(R.string.ring_native_d96096450e42))
                    fun threshold(key: String) = (alerts[key] as? JsonPrimitive)?.takeUnless { it.isString }?.intOrNull
                        ?.takeIf { it in 0..255 } ?: throw RingFailure("bad_payload", RingText.text(R.string.ring_native_dcab3dd6659c))
                    add(obj { put("type", "set_heart_rate_alerts"); put("low_bpm", threshold("low_bpm")); put("high_bpm", threshold("high_bpm")) })
                }
            }
        }
        else -> throw RingFailure("unknown_type", RingText.text(R.string.ring_native_0ee01dbec864))
    }
}

/** Page-supplied identity is a fence, never an instruction to switch the recording's owner. */
object RingMeasurementPayload {
    fun cancelOperation(parameters: JsonObject, operationId: String?): String {
        if (parameters.keys != setOf("operation_id")) throw RingFailure("bad_payload", RingText.text(R.string.ring_native_d0fc0fe09e78))
        val id = (parameters["operation_id"] as? JsonPrimitive)?.takeIf { it.isString }?.content
            ?: throw RingFailure("bad_payload", RingText.text(R.string.ring_native_66d4c33e41b4))
        if (operationId == null || id != operationId) throw RingFailure("stale_measurement", RingText.text(R.string.ring_native_0c6b18eb5359))
        return id
    }
    fun validate(payload: JsonObject, owner: RingWorkoutBinding, workoutId: String? = null, requireWorkout: Boolean = false): JsonObject {
        val user = (payload["user_id"] as? JsonPrimitive)?.takeIf { it.isString }?.content
        val source = (payload["source_id"] as? JsonPrimitive)?.takeIf { it.isString }?.content
        val version = (payload["binding_generation"] as? JsonPrimitive)?.takeUnless { it.isString }?.longOrNull
        if (user != owner.userId || source != owner.sourceId || version != owner.grantGeneration)
            throw RingFailure("invalid_binding", RingText.text(R.string.ring_native_dd7938b1b3af))
        if (requireWorkout && payload.string("workout_id") != workoutId)
            throw RingFailure("stale_workout", RingText.text(R.string.ring_native_ead26af84ce8))
        if (!requireWorkout && "workout_id" in payload) throw RingFailure("bad_payload", RingText.text(R.string.ring_native_e4d0373e076e))
        return JsonObject(payload.filterKeys { it !in setOf("user_id", "source_id", "binding_generation", "workout_id") })
    }
}

/** Progress uses the operation's UTC/monotonic anchor, never the phone's later wall clock. */
data class RingManualMeasurement(
    val operationId: String,
    val command: JsonObject,
    val anchor: RingWorkoutClock,
    val phase: String = "starting",
    val startedElapsedMs: Long = anchor.elapsedRealtimeMs,
    val deadlineElapsedMs: Long = anchor.elapsedRealtimeMs + 45_000,
    val stopReason: String? = null,
    val coreStarted: Boolean = false,
) {
    fun progress(effect: JsonObject): RingManualMeasurement? {
        if (effect.string("operation_id") != operationId || effect["command"] != command) return null
        val nextPhase = effect.string("phase")?.takeIf { it in phases } ?: return null
        if (phases.indexOf(nextPhase) < phases.indexOf(phase)) return null
        val start = (effect["started_at_ms"] as? JsonPrimitive)?.takeUnless { it.isString }?.longOrNull ?: return null
        val deadline = (effect["deadline_at_ms"] as? JsonPrimitive)?.takeUnless { it.isString }?.longOrNull ?: return null
        if (start < anchor.elapsedRealtimeMs || deadline < start || deadline - start > 53_000 ||
            coreStarted && start != startedElapsedMs) return null
        val reason = when (val raw = effect["stop_reason"]) {
            JsonNull -> null
            is JsonPrimitive -> raw.takeIf { it.isString }?.content?.takeIf { it in stopReasons } ?: return null
            else -> return null
        }
        // Cancellation can be queued while the start ATT is still outstanding.
        if (nextPhase != "stopping" && reason != null && reason != "cancelled" || nextPhase == "stopping" && reason == null) return null
        return copy(phase = nextPhase, startedElapsedMs = start, deadlineElapsedMs = deadline, stopReason = reason, coreStarted = true)
    }
    fun snapshot(): JsonObject = obj {
        put("operation_id", operationId); put("metric", command["metric"]!!); put("phase", phase)
        put("started_at", anchor.utcAt.plusMillis(startedElapsedMs - anchor.elapsedRealtimeMs).toString())
        put("deadline_at", anchor.utcAt.plusMillis(deadlineElapsedMs - anchor.elapsedRealtimeMs).toString())
        put("stop_reason", stopReason?.let(::JsonPrimitive) ?: JsonNull)
    }
    companion object {
        private val phases = listOf("starting", "measuring", "stopping")
        private val stopReasons = setOf("result", "cancelled", "device_error", "timeout")
    }
}
