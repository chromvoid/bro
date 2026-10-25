package assistant.mobile.android.ambient

import java.net.URI
import java.time.Instant
import java.util.UUID
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull

@Serializable
data class AmbientBinding(
    val origin: String,
    @SerialName("user_id") val userId: String,
    @SerialName("device_id") val deviceId: String,
    @SerialName("expires_at") val expiresAt: String,
) {
    init {
        require(AmbientContract.validOrigin(origin)) { "Invalid ambient origin" }
        require(AmbientContract.isUuid(userId) && AmbientContract.isUuid(deviceId)) { "Invalid ambient owner" }
        Instant.parse(expiresAt)
    }

    val expiresInstant: Instant get() = Instant.parse(expiresAt)

    fun validAt(now: Instant): Boolean = now.isBefore(expiresInstant)

    /** Session expiry is part of ownership; equivalent UTC spelling remains equivalent. */
    fun matches(other: AmbientBinding): Boolean = origin == other.origin && userId == other.userId &&
        deviceId == other.deviceId && expiresInstant == other.expiresInstant
}

data class AmbientReceipt(val id: String, val sha256: String, val stored: Boolean = true)

object AmbientContract {
    private val json = Json
    private val digestPattern = Regex("[0-9a-f]{64}")

    fun isUuid(value: String): Boolean = try {
        UUID.fromString(value).toString() == value
    } catch (_: IllegalArgumentException) { false }

    fun isSha256(value: String): Boolean = digestPattern.matches(value)

    fun validOrigin(value: String): Boolean = try {
        val uri = URI(value)
        uri.host != null && uri.rawUserInfo == null && uri.rawQuery == null && uri.rawFragment == null &&
            uri.rawPath.isNullOrEmpty() && (uri.scheme == "https" ||
            (uri.scheme == "http" && uri.host in setOf("127.0.0.1", "localhost")))
    } catch (_: Exception) { false }

    /** Native account message: no credentials or arbitrary URLs may accompany the binding. */
    fun parseBinding(body: String, origin: String): AmbientBinding? = try {
        val value = json.parseToJsonElement(body) as? JsonObject
        val required = setOf("user_id", "device_id", "expires_at")
        if (value == null || value.keys !in setOf(required, required + "origin")) null
        else if ("origin" in value && string(value, "origin") != origin) null
        else AmbientBinding(origin, string(value, "user_id"), string(value, "device_id"), string(value, "expires_at"))
    } catch (_: Exception) { null }

    /** /v1/me has other public profile fields, but these three must be actual strings. */
    fun parseMeBinding(body: String, origin: String): AmbientBinding? = try {
        val value = json.parseToJsonElement(body) as JsonObject
        AmbientBinding(origin, string(value, "id"), string(value, "device_id"), string(value, "session_expires_at"))
    } catch (_: Exception) { null }

    fun acceptedFormats(body: String): Set<AmbientAudioFormat>? = try {
        val value = json.parseToJsonElement(body) as JsonObject
        val formats = value["accepted_formats"] as JsonArray
        val limits = value["limits"] as JsonObject
        fun integer(key: String): Int = (limits[key] as? JsonPrimitive)?.takeUnless { it.isString }?.intOrNull ?: 0
        if (integer("max_compressed_batch_bytes") < AmbientAudioFormat.MAX_COMPRESSED_BYTES ||
            integer("max_batch_duration_ms") < AmbientWav.SEGMENT_SECONDS * 1_000) null
        else formats.map { entry ->
            val id = (entry as? JsonPrimitive)?.takeIf { it.isString }?.content ?: throw IllegalArgumentException()
            AmbientAudioFormat.fromId(id)
        }.filterNotNull().toSet().takeIf { it.any { format -> format != AmbientAudioFormat.WAV } }
    } catch (_: Exception) { null }

    /** Only a durable receipt for the exact segment and hash permits deleting local audio. */
    fun parseReceipt(body: String): AmbientReceipt? = try {
        val value = json.parseToJsonElement(body) as JsonObject
        val id = string(value, "id")
        val sha256 = string(value, "sha256")
        val stored = value["stored"] as? JsonPrimitive
        if (!value.keys.containsAll(setOf("id", "sha256", "stored")) || !isUuid(id) || !isSha256(sha256) ||
            stored == null || stored.isString || stored.booleanOrNull != true) null
        else AmbientReceipt(id, sha256)
    } catch (_: Exception) { null }

    private fun string(value: JsonObject, key: String): String =
        (value[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
            ?: throw IllegalArgumentException("Missing string field")
}
