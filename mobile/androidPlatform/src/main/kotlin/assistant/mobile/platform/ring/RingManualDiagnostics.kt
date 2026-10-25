package assistant.mobile.platform.ring

import java.time.Instant
import kotlinx.serialization.json.*

/** Explicit manual-operation projections; neither a native effect nor an account object is logged. */
class RingManualDiagnostics(
    private val operationId: String,
    private val metric: String,
    private val sourceId: String,
    private val collectorId: String,
    private val bindingGeneration: Long,
    private val current: () -> Boolean,
    private val clock: () -> Time,
    private val append: (String, JsonObject) -> Unit,
) {
    data class Time(val at: Instant, val elapsedMs: Long, val elapsedNanos: Long)
    private fun matches(value: JsonObject): Boolean = value.string("operation_id") == operationId &&
        (value["command"] as? JsonObject)?.let { it.string("type") == "measure_once" && it.string("metric") == metric } == true

    private fun emit(kind: String, fields: JsonObject = obj {}, received: Time? = null) {
        try {
            val observed = received ?: clock()
            if (!current()) return
            append(kind, obj {
                put("operation_id", operationId); put("metric", metric)
                put("source_id", sourceId); put("collector_id", collectorId); put("binding_generation", bindingGeneration)
                put("observed_at", observed.at.toString()); put("observed_monotonic_ms", observed.elapsedMs)
                put("observed_monotonic_nanos", observed.elapsedNanos)
                fields.forEach { (key, value) -> put(key, value) }
            })
        } catch (_: Exception) { /* Diagnostics never changes a command, its result or its radio fence. */ }
    }

    fun admitted() = emit("manual_request_admitted")
    fun identity(identity: JsonObject) = emit("manual_identity_verified", obj {
        for (key in listOf("model", "hardware", "firmware")) identity.string(key)?.let { put(key, it) }
    })
    fun checkpoint(recovery: JsonObject) {
        if (matches(recovery)) emit("manual_checkpoint_stored")
    }
    fun progress(effect: JsonObject) {
        if (!matches(effect)) return
        emit("manual_native_progress", obj {
            for (key in listOf("phase", "stop_reason", "started_at_ms", "deadline_at_ms"))
                effect[key]?.takeIf { it is JsonPrimitive || it == JsonNull }?.let { put(key, it) }
        })
    }
    private fun writeFields(effect: JsonObject): JsonObject? {
        if (effect.string("query_id") != "control:$operationId" || effect.string("channel") != "core" || effect.bool("response") != true) return null
        val hex = effect.string("bytes_hex")?.takeIf { it.length == 32 && it.take(2) in setOf("69", "6a") &&
            it.all { character -> character in '0'..'9' || character in 'a'..'f' } } ?: return null
        return obj { put("channel", "core"); put("bytes_hex", hex); put("response", true) }
    }
    fun writeStarted(effect: JsonObject) {
        writeFields(effect)?.let { emit("manual_write_started", it) }
    }
    /** The executor records its await return, not a made-up Bluetooth callback timestamp. */
    fun writeAwaitReturned(effect: JsonObject) {
        writeFields(effect)?.let { emit("manual_write_await_returned", JsonObject(it + mapOf(
            "success" to JsonPrimitive(true), "completion_basis" to JsonPrimitive("ring_gatt_write_await_return")))) }
    }
    fun writeFailed(effect: JsonObject, failure: Exception) {
        writeFields(effect)?.let { emit("manual_write_failed", JsonObject(it + mapOf(
            "success" to JsonPrimitive(false), "code" to JsonPrimitive(failureCode(failure))))) }
    }
    /** Preserve callback receipt time even when notices are consumed after the ATT await. */
    fun notice(channel: String, bytes: ByteArray, received: Time) {
        if (channel != "core" || bytes.isEmpty() || (bytes[0].toInt() and 0xff) !in setOf(0x69, 0x6a)) return
        emit("manual_notification_received", obj {
            put("channel", channel); put("byte_count", bytes.size)
            if (bytes.size <= 16) put("bytes_hex", bytes.joinToString("") { "%02x".format(it) })
            else put("bytes_omitted", true)
        }, received)
    }
    fun observation(effect: JsonObject) {
        if (effect.string("operation_id") != operationId) return
        val observation = effect["observation"] as? JsonObject ?: return
        if (observation.string("metric") != metric) return
        emit("manual_decoder_observation", obj {
            for (key in listOf("value", "unit", "received_monotonic_ms", "timestamp_basis", "sample_quality"))
                (observation[key] as? JsonPrimitive)?.let { put(key, it) }
        })
    }
    fun conversion(externalId: String?) = emit("manual_observation_conversion", obj {
        put("accepted", externalId != null); externalId?.let { put("external_id", it) }
    })
    fun observationSkipped(reason: String) = emit("manual_observation_skipped", obj { put("reason", reason) })
    fun observationStored(outcomes: List<JsonObject>) = emit("manual_observation_stored", obj {
        put("records", JsonArray(outcomes.map { outcome -> obj {
            outcome.string("external_id")?.let { put("external_id", it) }
            (outcome["version"] as? JsonPrimitive)?.let { put("version", it) }; outcome.string("status")?.let { put("status", it) }
        } }))
    })
    /** The real outbox transaction remains authoritative and its exception propagates unchanged. */
    fun persistObservation(externalId: String, persist: () -> List<JsonObject>): List<JsonObject> {
        conversion(externalId)
        val outcomes = persist()
        observationStored(outcomes)
        return outcomes
    }
    fun terminal(effect: JsonObject, stored: Boolean) {
        if (!matches(effect)) return
        emit(if (stored) "manual_terminal_stored" else "manual_native_terminal", obj {
            effect.string("status")?.let { put("status", it) }; effect.string("reason")?.let { put("reason", it) }
        })
    }
    fun cancellationRequested() = emit("manual_cancel_requested")
    fun returned(result: JsonObject, cached: Boolean = false) {
        if (!matches(result)) return
        emit("manual_executor_returned", obj {
            result.string("status")?.let { put("status", it) }; result.string("reason")?.let { put("reason", it) }
            put("cached_receipt", cached)
        })
    }
    fun failed(failure: Exception) = emit("manual_executor_failed", obj { put("code", failureCode(failure)) })
    private fun failureCode(failure: Exception) = when (failure) {
        is kotlinx.coroutines.TimeoutCancellationException -> "timeout"
        is kotlinx.coroutines.CancellationException -> "cancelled"
        is RingFailure -> failure.code
        else -> "unavailable"
    }
}
