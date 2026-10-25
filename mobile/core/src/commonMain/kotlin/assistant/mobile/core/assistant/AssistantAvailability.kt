package assistant.mobile.core.assistant

import assistant.mobile.core.NativeJson
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.time.Instant

@Serializable
data class AssistantAvailability(
    val state: String,
    val reason: String?,
    val action: String? = null,
    @SerialName("retry_at") val retryAt: String?,
    @SerialName("checked_at") val checkedAt: String,
) {
    val supported: Boolean get() = state in setOf("configured", "blocked", "temporary") &&
        (action == null || action in setOf("configure_access", "open_accounts", "contact_admin"))
}

fun decodeAssistantAvailability(bytes: ByteArray): AssistantAvailability =
    NativeJson.decodeFromString<AssistantAvailability>(bytes.decodeToString(throwOnInvalidSequence = true)).also {
        Instant.parse(it.checkedAt)
        it.retryAt?.let(Instant::parse)
    }

@Serializable
data class AssistantReplyTarget(val messageId: String, val preview: String)
