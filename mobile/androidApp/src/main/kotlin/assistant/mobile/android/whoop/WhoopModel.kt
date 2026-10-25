package assistant.mobile.android.whoop

import java.security.MessageDigest
import kotlinx.serialization.json.*

internal val whoopJson = Json { ignoreUnknownKeys = true }
internal fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
internal fun JsonObject.bool(key: String): Boolean? = (this[key] as? JsonPrimitive)?.booleanOrNull
internal fun JsonObject.number(key: String): Int? = (this[key] as? JsonPrimitive)?.intOrNull
internal fun obj(block: JsonObjectBuilder.() -> Unit): JsonObject = buildJsonObject(block)
internal fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
    .digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

/** Device facts only; raw history and the collector credential never enter Compose state. */
internal data class WhoopDevice(val address: String, val name: String, val bonded: Boolean = false, val rssi: Int? = null) {
    fun json(): JsonObject = obj {
        put("address", address); put("name", name); put("bonded", bonded)
        rssi?.let { put("rssi", it) }
    }
    companion object {
        fun restore(saved: JsonObject): WhoopDevice? {
            val address = saved.string("address") ?: return null
            return WhoopDevice(address, saved.string("name") ?: address, saved.bool("bonded") ?: false, saved.number("rssi"))
        }
    }
}

internal data class WhoopProblem(val code: String, val message: String) {
    fun json(): JsonObject = obj { put("code", code); put("message", message) }
    companion object {
        fun restore(saved: JsonObject): WhoopProblem? = saved.string("code")?.let {
            WhoopProblem(it, saved.string("message") ?: it)
        }
    }
}

/** Native UI status and binding; it deliberately has no measurement verification claims. */
internal data class WhoopState(
    val phase: String = "idle", val permission: String = "required", val bluetoothEnabled: Boolean = false,
    val selected: WhoopDevice? = null, val battery: Int? = null, val firmware: String? = null, val hardware: String? = null,
    val backgroundEnabled: Boolean = false, val queueCount: Int = 0, val queueBytes: Long = 0,
    val lastSync: String? = null, val lastUpload: String? = null, val historyComplete: Boolean? = null,
    val error: WhoopProblem? = null, val operationId: String? = null, val user: String? = null, val source: String? = null,
    val scanResults: List<WhoopDevice> = emptyList(),
) {
    fun json(): JsonObject = obj {
        put("profile", PROFILE); put("phase", phase); put("permission", permission)
        put("bluetooth_enabled", bluetoothEnabled); put("selected", selected?.json() ?: JsonNull)
        put("battery", battery?.let(::JsonPrimitive) ?: JsonNull)
        put("firmware", firmware?.let(::JsonPrimitive) ?: JsonNull)
        put("hardware", hardware?.let(::JsonPrimitive) ?: JsonNull)
        put("background_enabled", backgroundEnabled); put("queue_count", queueCount); put("queue_bytes", queueBytes)
        put("last_sync", lastSync?.let(::JsonPrimitive) ?: JsonNull)
        put("last_upload", lastUpload?.let(::JsonPrimitive) ?: JsonNull)
        put("history_complete", historyComplete?.let(::JsonPrimitive) ?: JsonNull)
        put("error", error?.json() ?: JsonNull); put("operation_id", operationId?.let(::JsonPrimitive) ?: JsonNull)
        put("user_id", user?.let(::JsonPrimitive) ?: JsonNull)
        put("source_id", source?.let(::JsonPrimitive) ?: JsonNull)
        put("scan_results", JsonArray(scanResults.map(WhoopDevice::json)))
    }

    companion object {
        const val PROFILE = "whoop-5"
        /** Device facts and times survive a restart; running work and old errors do not. */
        fun restore(saved: JsonObject, background: Boolean) = WhoopState(
            selected = (saved["selected"] as? JsonObject)?.let(WhoopDevice::restore), battery = saved.number("battery"),
            firmware = saved.string("firmware"), hardware = saved.string("hardware"), backgroundEnabled = background,
            lastSync = saved.string("last_sync"), lastUpload = saved.string("last_upload"),
            historyComplete = saved.bool("history_complete"))
    }
}
