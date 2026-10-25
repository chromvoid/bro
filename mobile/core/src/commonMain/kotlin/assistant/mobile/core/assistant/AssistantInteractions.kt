package assistant.mobile.core.assistant

import assistant.mobile.core.AccountNamespace
import assistant.mobile.core.NativeJson
import assistant.mobile.core.journal.Submission
import assistant.mobile.core.journal.SubmissionState
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlin.time.Instant

internal const val ASSISTANT_CONTROLS = "/v1/conversation/controls"

@Serializable
enum class AssistantControlAction {
    @SerialName("stop") Stop,
    @SerialName("withdraw") Withdraw,
}

@Serializable
enum class AssistantClarificationStatus {
    Sending, Unknown,
    @SerialName("accepted") Accepted,
    @SerialName("delivered") Delivered,
    @SerialName("applied") Applied,
    @SerialName("queued") Queued,
    @SerialName("rejected") Rejected,
    @SerialName("paused") Paused,
}

@Serializable
enum class AssistantControlStatus {
    Sending, Unknown,
    @SerialName("accepted") Accepted,
    @SerialName("settled") Settled,
    @SerialName("rejected") Rejected,
}

@Serializable
data class AssistantClarificationReceipt(
    val id: String,
    @SerialName("submission_key") val submissionKey: String,
    @SerialName("message_id") val messageId: String,
    val generation: String,
    @SerialName("source_message_id") val sourceMessageId: String,
    @SerialName("source_revision") val sourceRevision: Int = 1,
    @SerialName("intent_revision") val intentRevision: Long,
    val state: AssistantClarificationStatus,
    val reason: String? = null,
    val question: String? = null,
    @SerialName("admitted_at") val admittedAt: String,
    @SerialName("delivered_at") val deliveredAt: String? = null,
    @SerialName("applied_at") val appliedAt: String? = null,
) {
    override fun toString(): String = "AssistantClarificationReceipt(state=$state, content=redacted)"
}

@Serializable
internal data class AssistantClarificationRequest(
    @SerialName("message_id") val messageId: String,
    val generation: String,
    val text: String? = null,
    @SerialName("queued_message_id") val queuedMessageId: String? = null,
)

@Serializable
internal data class AssistantControlRequest(
    @SerialName("message_id") val messageId: String,
    val generation: String,
    val action: AssistantControlAction,
)

@Serializable
data class AssistantControlReceipt(
    val id: String,
    @SerialName("submission_key") val submissionKey: String,
    @SerialName("message_id") val messageId: String,
    val generation: String,
    @SerialName("intent_revision") val intentRevision: Long,
    val action: AssistantControlAction,
    val state: AssistantControlStatus,
    val reason: String? = null,
    @SerialName("admitted_at") val admittedAt: String,
    @SerialName("settled_at") val settledAt: String? = null,
    val execution: AssistantExecution? = null,
) {
    override fun toString(): String = "AssistantControlReceipt(action=$action, state=$state, content=redacted)"
}

data class AssistantClarificationItem(
    val key: String,
    val text: String,
    val targetMessageId: String,
    val generation: String,
    val sourceMessageId: String?,
    val status: AssistantClarificationStatus,
    val reason: String?,
    val question: String?,
    val canCheck: Boolean,
    val canRetry: Boolean,
) {
    override fun toString(): String = "AssistantClarificationItem(status=$status, content=redacted)"
}

data class AssistantPendingControl(
    val key: String,
    val messageId: String,
    val generation: String,
    val action: AssistantControlAction,
    val status: AssistantControlStatus,
    val reason: String?,
    val execution: AssistantExecution?,
    val canCheck: Boolean,
    val canRetry: Boolean,
) {
    val unknownOutcome: Boolean get() = status == AssistantControlStatus.Unknown || execution?.effects == "unknown"
}

data class AssistantInteractionsState(
    val namespace: AccountNamespace? = null,
    val clarifications: List<AssistantClarificationItem> = emptyList(),
    val controls: List<AssistantPendingControl> = emptyList(),
    val busy: Set<String> = emptySet(),
    val error: assistant.mobile.core.LocalizedString? = null,
)

fun AssistantMessage.supportsClarification(): Boolean = pending && !queued && canClarify &&
    !runGeneration.isNullOrBlank() && stopRequested != true

fun AssistantMessage.availableControls(): Set<AssistantControlAction> = when {
    !pending || runGeneration.isNullOrBlank() || stopRequested == true -> emptySet()
    queued -> setOf(AssistantControlAction.Withdraw)
    else -> setOf(AssistantControlAction.Stop)
}

internal fun decodeClarificationReceipt(bytes: ByteArray): AssistantClarificationReceipt =
    NativeJson.decodeFromString<AssistantClarificationReceipt>(bytes.decodeToString(throwOnInvalidSequence = true)).also(::validateClarification)

private fun validateClarification(receipt: AssistantClarificationReceipt) {
    require(receipt.id.isNotBlank() && receipt.messageId.isNotBlank() && receipt.generation.isNotBlank() &&
        receipt.submissionKey.isNotBlank() && receipt.sourceMessageId.isNotBlank())
    require(receipt.sourceRevision == 1 && receipt.intentRevision >= 0)
    require(receipt.state !in setOf(AssistantClarificationStatus.Sending, AssistantClarificationStatus.Unknown))
    require(receipt.state != AssistantClarificationStatus.Paused || receipt.appliedAt == null)
    Instant.parse(receipt.admittedAt)
    receipt.deliveredAt?.let { Instant.parse(it) }
    receipt.appliedAt?.let { Instant.parse(it) }
}

/** Both authored text and queue moves share a route; each receipt must prove its original body. */
internal fun validateAssistantClarificationReceipt(body: ByteArray, response: ByteArray, key: String): AssistantClarificationReceipt {
    val request = NativeJson.decodeFromString<AssistantClarificationRequest>(body.decodeToString(throwOnInvalidSequence = true))
    require((request.text != null) != (request.queuedMessageId != null))
    val receipt = decodeClarificationReceipt(response)
    require(receipt.submissionKey == key && receipt.messageId == request.messageId && receipt.generation == request.generation)
    require(request.queuedMessageId == null || receipt.sourceMessageId == request.queuedMessageId)
    return receipt
}

internal fun validateAssistantControlReceipt(body: ByteArray, response: ByteArray, key: String): AssistantControlReceipt {
    val request = NativeJson.decodeFromString<AssistantControlRequest>(body.decodeToString(throwOnInvalidSequence = true))
    val receipt = NativeJson.decodeFromString<AssistantControlReceipt>(response.decodeToString(throwOnInvalidSequence = true))
    require(receipt.submissionKey == key && receipt.messageId == request.messageId &&
        receipt.generation == request.generation && receipt.action == request.action)
    require(receipt.id.isNotBlank() && receipt.intentRevision >= 0)
    require(receipt.state !in setOf(AssistantControlStatus.Sending, AssistantControlStatus.Unknown))
    require(receipt.execution == null || receipt.execution.supported)
    Instant.parse(receipt.admittedAt)
    receipt.settledAt?.let { Instant.parse(it) }
    return receipt
}

/** A history row owns only its immutable source; a receipt copied to its target owns nothing. */
fun historyAssistantClarification(message: AssistantMessage): AssistantClarificationItem? = runCatching {
    val value = message.clarificationReceipt as? JsonObject ?: return null
    val receipt = NativeJson.decodeFromJsonElement(AssistantClarificationReceipt.serializer(), value)
    validateClarification(receipt)
    if (receipt.sourceMessageId != message.id) return null
    receipt.item(message.text, canCheck = receipt.state in liveClarificationStates)
}.getOrNull()

internal fun AssistantClarificationReceipt.item(text: String, canCheck: Boolean, canRetry: Boolean = false) =
    AssistantClarificationItem(submissionKey, text, messageId, generation, sourceMessageId, state, reason, question, canCheck, canRetry)

internal fun Submission.clarificationItem(active: Boolean, now: Long): AssistantClarificationItem? = runCatching {
    val request = NativeJson.decodeFromString<AssistantClarificationRequest>(body.decodeToString())
    val receipt = response?.let { runCatching { validateAssistantClarificationReceipt(body, it, key) }.getOrNull() }
    val receiptStatus = receipt?.state
    val status = when {
        receiptStatus != null -> receiptStatus
        active -> AssistantClarificationStatus.Sending
        knownUnadmitted() -> AssistantClarificationStatus.Rejected
        else -> AssistantClarificationStatus.Unknown
    }
    AssistantClarificationItem(key, request.text.orEmpty(), request.messageId, request.generation,
        receipt?.sourceMessageId ?: request.queuedMessageId, status, receipt?.reason, receipt?.question,
        canCheck = !active && !knownUnadmitted() && (receipt == null || status in liveClarificationStates),
        canRetry = !active && receipt == null && retryAvailable(now))
}.getOrNull()

internal fun Submission.controlItem(active: Boolean, now: Long): AssistantPendingControl? = runCatching {
    val request = NativeJson.decodeFromString<AssistantControlRequest>(body.decodeToString())
    val receipt = response?.let { runCatching { validateAssistantControlReceipt(body, it, key) }.getOrNull() }
    val status = when {
        receipt != null -> receipt.state
        active -> AssistantControlStatus.Sending
        knownUnadmitted() -> AssistantControlStatus.Rejected
        else -> AssistantControlStatus.Unknown
    }
    AssistantPendingControl(key, request.messageId, request.generation, request.action, status,
        receipt?.reason, receipt?.execution,
        canCheck = !active && !knownUnadmitted() && status in setOf(AssistantControlStatus.Unknown, AssistantControlStatus.Accepted),
        canRetry = !active && receipt == null && retryAvailable(now))
}.getOrNull()

private fun Submission.retryAvailable(now: Long): Boolean = state in setOf(
    SubmissionState.PREPARED, SubmissionState.SENDING, SubmissionState.UNCERTAIN,
) && (retryAtMillis == null || retryAtMillis <= now)

internal val liveClarificationStates = setOf(AssistantClarificationStatus.Accepted, AssistantClarificationStatus.Delivered)
