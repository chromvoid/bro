package assistant.mobile.core.assistant

import assistant.mobile.core.NativeJson
import io.ktor.http.URLProtocol
import io.ktor.http.Url
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Receipt identities and effects are facts, never client-side operation instructions. */
@Serializable
data class AssistantReceipt(
    @SerialName("operation_id") val operationId: String? = null,
    val kind: String? = null,
)

@Serializable
data class AssistantEntity(
    val id: String,
    val kind: String,
    val revision: JsonPrimitive? = null,
    val label: String? = null,
    val status: String? = null,
    val date: String? = null,
)

@Serializable
data class AssistantEffect(
    @SerialName("operation_ref") val operationRef: String,
    val kind: String,
    val entities: List<AssistantEntity>,
    val undoes: String? = null,
)

@Serializable
data class AssistantExecution(
    val effects: String,
    val completion: String,
    @SerialName("safe_to_resubmit") val safeToResubmit: Boolean,
) {
    val supported: Boolean get() = effects in setOf("none", "confirmed", "unknown") &&
        completion in setOf("completed", "failed", "partial")
}

@Serializable
data class AssistantSource(
    val url: String,
    val title: String? = null,
    val text: String? = null,
    val relation: String? = null,
    @SerialName("published_at") val publishedAt: String? = null,
    val provenance: String? = null,
)

@Serializable
data class AssistantServiceResult(
    val service: String? = null,
    val status: String? = null,
    @SerialName("receipt_text") val receiptText: String? = null,
    val message: String? = null,
    val error: String? = null,
    val question: String? = null,
    @SerialName("confirmation_pending") val confirmationPending: Boolean? = null,
    @SerialName("proposal_id") val proposalId: String? = null,
    @SerialName("proposal_status") val proposalStatus: String? = null,
    @SerialName("operation_key") val operationKey: String? = null,
    val receipts: List<AssistantReceipt>? = null,
    val effects: List<AssistantEffect>? = null,
    /** Other plugins also use “sources” for non-link records; only valid citations are projected. */
    @SerialName("sources") val sourceData: JsonElement? = null,
    @SerialName("research_status") val researchStatus: String? = null,
    val limitations: List<String>? = null,
    @SerialName("account_login_ids") val accountLoginIds: List<String>? = null,
) {
    val sources: List<AssistantSource> by lazy {
        (sourceData as? JsonArray)?.mapNotNull { item ->
            runCatching { NativeJson.decodeFromJsonElement(AssistantSource.serializer(), item) }.getOrNull()
        } ?: emptyList()
    }
}

/** Known optional fields keep their wire types. Unknown result shapes degrade to inert text. */
@Serializable(with = AssistantResultSerializer::class)
data class AssistantResult(
    val execution: AssistantExecution? = null,
    val reply: String? = null,
    val question: String? = null,
    val confirmationPending: Boolean? = null,
    val proposalId: String? = null,
    val proposalStatus: String? = null,
    val receipts: List<AssistantReceipt>? = null,
    val services: List<AssistantServiceResult>? = null,
    val error: String? = null,
    val accountLoginIds: List<String>? = null,
    val unsupported: Boolean = false,
    val fallbackText: String? = null,
) {
    override fun toString(): String = "AssistantResult(unsupported=$unsupported, content=redacted)"
}

@Serializable
private data class AssistantResultWire(
    val execution: AssistantExecution? = null,
    val reply: String? = null,
    val question: String? = null,
    @SerialName("confirmation_pending") val confirmationPending: Boolean? = null,
    @SerialName("proposal_id") val proposalId: String? = null,
    @SerialName("proposal_status") val proposalStatus: String? = null,
    val receipts: List<AssistantReceipt>? = null,
    val services: List<AssistantServiceResult>? = null,
    val error: String? = null,
    @SerialName("account_login_ids") val accountLoginIds: List<String>? = null,
)

private object AssistantResultSerializer : KSerializer<AssistantResult> {
    override val descriptor: SerialDescriptor = AssistantResultWire.serializer().descriptor

    override fun deserialize(decoder: Decoder): AssistantResult {
        val element = (decoder as JsonDecoder).decodeJsonElement()
        val document = element as? JsonObject
        fun fallback(): AssistantResult {
            val text = if (document == null) (element as? JsonPrimitive)?.takeIf { it.isString }?.content
            else listOf("reply", "text", "message").firstNotNullOfOrNull { name ->
                (document[name] as? JsonPrimitive)?.takeIf { it.isString }?.content
            }
            return AssistantResult(unsupported = true, fallbackText = text)
        }
        if (document == null || document.keys.none { it in resultFields } ||
            document["type"]?.let { it != JsonNull } == true || document["kind"]?.let { it != JsonNull } == true) return fallback()
        val wire = runCatching {
            NativeJson.decodeFromJsonElement(AssistantResultWire.serializer(), document)
        }.getOrNull() ?: return fallback()
        return AssistantResult(
            wire.execution, wire.reply, wire.question, wire.confirmationPending, wire.proposalId,
            wire.proposalStatus, wire.receipts, wire.services, wire.error, wire.accountLoginIds,
        )
    }

    override fun serialize(encoder: Encoder, value: AssistantResult) {
        val json = encoder as JsonEncoder
        if (value.unsupported) {
            json.encodeJsonElement(buildJsonObject {
                put("type", "unsupported")
                value.fallbackText?.let { put("text", it) }
            })
        } else {
            json.encodeJsonElement(NativeJson.encodeToJsonElement(AssistantResultWire.serializer(), AssistantResultWire(
                value.execution, value.reply, value.question, value.confirmationPending, value.proposalId,
                value.proposalStatus, value.receipts, value.services, value.error, value.accountLoginIds,
            )))
        }
    }

    private val resultFields = setOf(
        "execution", "reply", "question", "confirmation_pending", "proposal_id", "proposal_status",
        "receipts", "services", "error", "account_login_ids",
    )
}

/** Presentation carries facts and typed states, and exposes no action from a result payload. */
data class AssistantReply(
    val turnId: String,
    val messageId: String?,
    val completed: Boolean,
    val text: String,
    val question: String?,
    val execution: AssistantExecution?,
    val committed: Boolean,
    val confirmationPending: Boolean,
    val proposalStatus: String?,
    val unknownOutcome: Boolean,
    val interrupted: Boolean,
    val responseFailed: Boolean,
    val unsupported: Boolean,
    val fallbackText: String?,
    val sources: List<AssistantSource>,
    val allowedLinks: List<String>,
    val limitations: List<String> = emptyList(),
) {
    override fun toString(): String = "AssistantReply(turnId=$turnId, content=redacted)"
}

fun AssistantTurn.toReply(): AssistantReply {
    val value = result ?: AssistantResult()
    val services = value.services.orEmpty()
    val unsupported = value.unsupported || value.execution?.supported == false ||
        (value.proposalStatus != null && value.proposalStatus !in proposalStates)
    val proposalStatus = value.proposalStatus ?: services.firstOrNull {
        it.confirmationPending == true && it.proposalStatus == "open"
    }?.proposalStatus ?: services.firstNotNullOfOrNull { it.proposalStatus }
    val proposalClosed = proposalStatus != null && proposalStatus != "open"
    val failed = !value.error.isNullOrEmpty() || state == "failed"
    val sources = services.flatMap { it.sources }.distinctBy { it.url }
    return AssistantReply(
        turnId = id,
        messageId = replyMessageId,
        completed = state == "completed",
        text = if (value.unsupported) "" else value.reply.orEmpty().trim(),
        question = if (unsupported || failed || proposalClosed && value.confirmationPending == true) null
            else value.question?.trim()?.takeIf { it.isNotEmpty() },
        execution = value.execution?.takeIf { it.supported },
        committed = value.execution?.effects == "confirmed" || !value.receipts.isNullOrEmpty() || services.any {
            !it.receiptText.isNullOrEmpty() || !it.receipts.isNullOrEmpty() || !it.effects.isNullOrEmpty()
        },
        confirmationPending = !unsupported && value.confirmationPending == true && !proposalClosed,
        proposalStatus = proposalStatus,
        unknownOutcome = value.execution?.effects == "unknown" || value.error == "outcome_pending" ||
            services.any { it.status == "unknown" },
        interrupted = value.error == "harness_interrupted",
        responseFailed = failed,
        unsupported = unsupported,
        fallbackText = value.fallbackText,
        sources = sources,
        allowedLinks = if (unsupported) emptyList() else sources.map { it.url }.filter(::safeAssistantWebLink),
        limitations = if (unsupported) emptyList() else services.flatMap { it.limitations.orEmpty() }.distinct(),
    )
}

/** Only exact cited HTTP(S) addresses can be linked by the renderer. */
fun safeAssistantWebLink(value: String): Boolean = runCatching {
    val url = Url(value)
    (url.protocol == URLProtocol.HTTP || url.protocol == URLProtocol.HTTPS) && url.host.isNotBlank() &&
        url.user.isNullOrEmpty() && url.password.isNullOrEmpty() &&
        value.none { it.isISOControl() || it.isWhitespace() } && value.contains("://")
}.getOrDefault(false)

private val proposalStates = setOf("open", "resolved", "cancelled", "superseded", "expired")
