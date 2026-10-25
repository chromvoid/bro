package assistant.mobile.core

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.time.Instant

/** Additive API fields are allowed; required fields retain their wire types. */
val NativeJson = Json { ignoreUnknownKeys = true }

@Serializable
data class AccountNamespace(val instance: String, val origin: String, val userId: String)

@Serializable
data class NativeIdentity(
    val id: String,
    val login: String,
    val name: String,
    val locale: String,
    val timezone: String,
    @SerialName("system_admin") val systemAdmin: Boolean,
    val revision: Long,
    @SerialName("device_id") val deviceId: String,
    @SerialName("session_expires_at") val sessionExpiresAt: String,
    val scopes: List<String> = emptyList(),
)

@Serializable
data class NativeDevice(val id: String, val kind: String, val name: String)

@Serializable
data class NativeSessionDescriptor(val id: String, val transport: String)

@Serializable
data class NativeSessionReply(
    val credential: String,
    val user: NativeIdentity,
    val device: NativeDevice,
    val session: NativeSessionDescriptor,
    @SerialName("expires_at") val expiresAt: String,
    val scopes: List<String>,
) {
    override fun toString(): String = "NativeSessionReply(redacted)"
}

@Serializable
data class SubmissionReceipt(
    @SerialName("submission_key") val submissionKey: String,
    @SerialName("message_id") val messageId: String,
    @SerialName("admitted_at") val admittedAt: String,
    val state: String,
    @SerialName("trace_id") val traceId: String? = null,
) {
    /** Unknown result values stay unsupported, never become a guessed completion. */
    val supportedState: Boolean get() = state in setOf("queued", "routing", "processing", "completed", "failed", "clarification")
}

fun decodeNativeSession(bytes: ByteArray): NativeSessionReply =
    NativeJson.decodeFromString<NativeSessionReply>(bytes.decodeToString()).also {
        require(validSessionCredential(it.credential))
        require(it.user.id.isNotBlank() && it.device.id.isNotBlank() && it.session.id.isNotBlank())
        require(it.session.transport == "native" && it.device.kind == "android")
        require(it.user.deviceId == it.device.id)
        require(it.user.revision >= 0)
        Instant.parse(it.expiresAt)
        Instant.parse(it.user.sessionExpiresAt)
    }

internal fun validSessionCredential(value: String): Boolean =
    value.isNotBlank() && value.length <= 128 && value.none { it.isWhitespace() || it.isISOControl() }

fun decodeSubmissionReceipt(bytes: ByteArray): SubmissionReceipt =
    NativeJson.decodeFromString<SubmissionReceipt>(bytes.decodeToString()).also {
        require(it.submissionKey.isNotBlank() && it.submissionKey.length <= 80)
        require(it.messageId.isNotBlank() && it.messageId.length <= 128 && it.state.isNotBlank())
        require(it.traceId == null || (it.traceId.length == 32 && it.traceId.all { character ->
            character in '0'..'9' || character in 'a'..'f' || character in 'A'..'F'
        }))
        Instant.parse(it.admittedAt)
    }
