package assistant.mobile.core.assistant

import assistant.mobile.core.NativeJson
import assistant.mobile.core.decodeSubmissionReceipt
import assistant.mobile.core.journal.Submission
import assistant.mobile.core.journal.SubmissionState
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

internal const val ASSISTANT_MESSAGES = "/v1/conversation/messages"
internal const val ASSISTANT_LOOKUP = "/v1/conversation/submissions/by-key/"
internal const val ASSISTANT_DRAFT = "assistant.compose"
internal const val ASSISTANT_MOVES = "/v1/conversation/clarifications"

enum class AssistantPendingStatus { Sending, Delivered, Uncertain, Rejected }

data class AssistantPendingSend(
    val key: String,
    val text: String,
    val status: AssistantPendingStatus,
    val canCheck: Boolean,
    val canRetry: Boolean,
    val messageId: String?,
)

internal fun Submission.admittedMessageId(): String? = if (state == SubmissionState.ACCEPTED || state == SubmissionState.RESOLVED) {
    response?.let { bytes -> runCatching { decodeSubmissionReceipt(bytes) }.getOrNull() }
        ?.takeIf { it.submissionKey == key && it.supportedState }?.messageId
} else null

internal fun Submission.knownUnadmitted(): Boolean = state == SubmissionState.REJECTED ||
    (state == SubmissionState.PREPARED && !mayHaveBeenAdmitted)

internal fun Submission.pendingSend(active: Boolean, now: Long): AssistantPendingSend {
    val id = admittedMessageId()
    val text = runCatching {
        (NativeJson.parseToJsonElement(body.decodeToString()).jsonObject["text"] as? JsonPrimitive)
            ?.takeIf { it.isString }?.content.orEmpty()
    }.getOrDefault("")
    val status = when {
        id != null -> AssistantPendingStatus.Delivered
        knownUnadmitted() -> AssistantPendingStatus.Rejected
        active -> AssistantPendingStatus.Sending
        else -> AssistantPendingStatus.Uncertain
    }
    return AssistantPendingSend(
        key, text, status,
        canCheck = !active && !knownUnadmitted() && state != SubmissionState.RESOLVED,
        canRetry = !active && state in setOf(SubmissionState.PREPARED, SubmissionState.SENDING, SubmissionState.UNCERTAIN) &&
            (retryAtMillis == null || retryAtMillis <= now),
        messageId = id,
    )
}

/** Queue eligibility is a projection of current durable history, never of an admission receipt. */
data class AssistantQueue(val running: AssistantMessage?, val movableIds: Set<String>) {
    companion object {
        fun from(messages: List<AssistantMessage>): AssistantQueue {
            val running = messages.lastOrNull {
                it.pending && !it.queued && it.canClarify && !it.runGeneration.isNullOrBlank()
            }
            return AssistantQueue(running, if (running == null) emptySet() else messages.filter {
                it.queued && it.clarificationKey == null && it.replyToMessageId == null &&
                    it.images.isEmpty() && !it.text.isNullOrBlank()
            }.map { it.id }.toSet())
        }
    }
}

@Serializable
internal data class AssistantMoveRequest(
    @SerialName("message_id") val messageId: String,
    val generation: String,
    @SerialName("queued_message_id") val queuedMessageId: String,
)
