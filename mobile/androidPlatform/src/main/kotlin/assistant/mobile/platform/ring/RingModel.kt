package assistant.mobile.platform.ring

import assistant.mobile.platform.R

import java.security.MessageDigest
import java.time.Instant
import java.util.UUID
import kotlinx.serialization.json.*

val ringJson = Json { ignoreUnknownKeys = true }
fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
fun JsonObject.bool(key: String): Boolean? = (this[key] as? JsonPrimitive)?.booleanOrNull
fun JsonObject.number(key: String): Int? = (this[key] as? JsonPrimitive)?.intOrNull
fun obj(block: JsonObjectBuilder.() -> Unit): JsonObject = buildJsonObject(block)
fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
    .digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
fun canonical(value: JsonElement): String = when (value) {
    is JsonObject -> JsonObject(value.toSortedMap().mapValues { ringJson.parseToJsonElement(canonical(it.value)) }).toString()
    is JsonArray -> JsonArray(value.map { ringJson.parseToJsonElement(canonical(it)) }).toString()
    else -> value.toString()
}
fun observationFingerprint(record: JsonObject): String {
    fun withoutCaptureTimes(value: JsonElement): JsonElement = when (value) {
        is JsonObject -> JsonObject(value.filterKeys { it !in setOf("captured_at", "received_at", "capture_wall_time_ms", "capture_monotonic_ms") }
            .mapValues { withoutCaptureTimes(it.value) })
        is JsonArray -> JsonArray(value.map(::withoutCaptureTimes))
        else -> value
    }
    var semantic = withoutCaptureTimes(JsonObject(record.filterKeys { it != "version" })) as JsonObject
    val observation = semantic["observation"] as? JsonObject
    if (semantic.string("kind") in setOf("steps", "active_energy", "distance") && observation != null &&
        (observation["quality"] as? JsonArray)?.contains(JsonPrimitive("partial_day")) == true) {
        // A poll timestamp alone does not create a new device revision. Finalization does.
        semantic = JsonObject(semantic + ("observation" to JsonObject(observation.filterKeys { it != "end_at" })))
    }
    val report = semantic["report"] as? JsonObject
    if (report?.string("metric") == "workouts" && report.string("namespace") == "core" && report.number("opcode") == 0x13) {
        // These labels describe the receipt calendar, not the unknown device epoch.
        semantic = JsonObject(semantic + ("report" to JsonObject(report.filterKeys { it !in setOf("observed_at", "local_date", "timezone") })))
    }
    if (report != null && (report.string("shape") == "day_counter_snapshot" ||
        (report.string("namespace") == "bulk" && report.string("metric") == "temperature" && report.string("layout") != null))) {
        // Poll/capture time is archived in the receipt, but an unchanged device
        // counter has no new immutable measurement content. Day, epoch, units,
        // time basis, decoder, quality and actual values remain in the hash.
        semantic = JsonObject(semantic + ("report" to JsonObject(report.filterKeys { it != "observed_at" })))
    }
    if (report?.string("namespace") == "bulk" && report.string("metric") == "temperature") {
        val details = report["details"] as? JsonObject
        val capture = details?.get("soul_time_context") as? JsonObject
        if (capture != null && details != null) {
            // Preserve the first exact receipt on unchanged reads. Context presence,
            // version, parse zone and sync-local day remain real provenance changes.
            val updated = JsonObject(report.filterKeys { it != "observed_at" } + ("details" to
                JsonObject(details + ("soul_time_context" to RingTemperatureCapture.semanticContext(capture)))))
            semantic = JsonObject(semantic + ("report" to updated))
        }
    }
    return sha256(canonical(semantic))
}

class RingFailure(val code: String, override val message: String) : Exception(message)

data class RingGrant(val value: JsonObject) {
    val user get() = value.string("user_id") ?: error("Invalid ring grant")
    val source get() = value.string("source_id") ?: error("Invalid ring grant")
    val token get() = value.string("token") ?: error("Invalid ring grant")
    val identity get() = value.string("collector_id") ?: error("Invalid ring grant")
}

/** Metadata is separate from measurements; this is the only native data exposed to the page. */
data class RingState(
    val phase: String = "idle", val permission: String = "required", val bluetoothEnabled: Boolean = false,
    val selected: JsonObject? = null, val battery: Int? = null, val charging: Boolean? = null,
    val firmware: String? = null, val hardware: String? = null, val backgroundEnabled: Boolean = false,
    val queueCount: Int = 0, val lastSync: String? = null, val lastUpload: String? = null,
    val error: JsonObject? = null, val operationId: String? = null, val user: String? = null,
    val source: String? = null, val scanResults: List<JsonObject> = emptyList(),
    val collectionSupport: JsonObject = obj { put("running_steps", "server_unsupported"); put("running_workouts", "protocol_unverified") },
    val metrics: JsonObject = obj {
        listOf("steps", "sleep", "heart_rate", "spo2", "temperature", "hrv", "stress", "active_energy", "distance", "running_steps", "running_workouts", "workouts").forEach { put(it, "unverified") }
    },
) {
    fun json(): JsonObject = obj {
        put("profile", "qring-r20"); put("phase", phase); put("permission", permission)
        put("bluetooth_enabled", bluetoothEnabled); put("selected", selected ?: JsonNull)
        put("battery", battery?.let(::JsonPrimitive) ?: JsonNull)
        put("charging", charging?.let(::JsonPrimitive) ?: JsonNull)
        put("firmware", firmware?.let(::JsonPrimitive) ?: JsonNull)
        put("hardware", hardware?.let(::JsonPrimitive) ?: JsonNull)
        put("background_enabled", backgroundEnabled); put("queue_count", queueCount)
        put("last_sync", lastSync?.let(::JsonPrimitive) ?: JsonNull)
        put("last_upload", lastUpload?.let(::JsonPrimitive) ?: JsonNull)
        put("error", error ?: JsonNull); put("operation_id", operationId?.let(::JsonPrimitive) ?: JsonNull)
        put("user_id", user?.let(::JsonPrimitive) ?: JsonNull)
        put("source_id", source?.let(::JsonPrimitive) ?: JsonNull)
        put("scan_results", JsonArray(scanResults)); put("metric_status", metrics); put("collection_support", collectionSupport)
    }
}

object RingPolicy {
    const val MAX_RECORDS = 50_000
    const val MAX_BYTES = 32 * 1024 * 1024
    const val BATCH_SIZE = 100
    const val MAX_BATCH_BYTES = 240 * 1024
    const val SESSION_MS = 60_000L
    fun receiptMatches(receipt: JsonObject, source: String, cursor: JsonElement): Boolean {
        val result = receipt["result"] as? JsonObject ?: receipt
        return result.string("source_id") == source && result["cursor"] == cursor
    }
    fun timestamp(): String = Instant.now().toString()
    fun bindingId(phoneInstallation: String, origin: String, user: String, address: String): String =
        UUID.nameUUIDFromBytes("ring-v1\n$phoneInstallation\n$origin\n$user\n${address.uppercase()}".toByteArray(Charsets.UTF_8)).toString()
    fun sourceVersion(grantGeneration: Long, counter: Long): Long {
        if (grantGeneration < 1 || counter !in 1..999_999) throw RingFailure("version_exhausted", RingText.text(R.string.ring_native_25464c8a832e))
        return Math.addExact(Math.multiplyExact(grantGeneration, 1_000_000L), counter)
    }
}
