package assistant.mobile.core.assistant

import assistant.mobile.core.AccountNamespace
import assistant.mobile.core.NativeJson
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlin.time.Instant

private val HistoryJson = Json(NativeJson) { coerceInputValues = true }

/** Durable exchanges only. Activity previews have no representation in this cache. */
@Serializable
data class AssistantHistory(
    val messages: List<AssistantMessage>,
    val before: String?,
    val after: String? = null,
)

@Serializable
data class AssistantMessage(
    val id: String,
    @SerialName("created_at") val createdAt: String,
    val state: String? = null,
    val error: String? = null,
    val text: String = "",
    val images: List<AssistantImage> = emptyList(),
    val turns: List<AssistantTurn> = emptyList(),
    @SerialName("reply_to_message_id") val replyToMessageId: String? = null,
    @SerialName("transcription_id") val transcriptionId: String? = null,
    @SerialName("run_generation") val runGeneration: String? = null,
    @SerialName("intent_version") val intentVersion: Long? = null,
    @SerialName("intent_revision") val intentRevision: Long? = null,
    @SerialName("can_clarify") @Serializable(with = AssistantPermissionSerializer::class)
    val canClarify: Boolean = false,
    @SerialName("clarification_receipt") val clarificationReceipt: JsonElement? = null,
    @SerialName("stop_requested") val stopRequested: Boolean? = null,
    @SerialName("control_state") val controlState: String? = null,
    @SerialName("control_action") val controlAction: String? = null,
    @SerialName("control_execution") val controlExecution: AssistantExecution? = null,
) {
    val pending: Boolean get() = state in setOf("queued", "routing", "processing")
    val queued: Boolean get() = state == "queued"
    val supportedState: Boolean get() = state == null || state in setOf(
        "queued", "routing", "processing", "completed", "failed", "clarification",
    )
    val replies: List<AssistantReply> by lazy { turns.map(AssistantTurn::toReply) }
    internal val occurredAt: Instant by lazy { Instant.parse(createdAt) }
    /** A receipt identifies the immutable queued source after “send now”. */
    val clarificationKey: String? get() =
        ((clarificationReceipt as? JsonObject)?.get("submission_key") as? JsonPrimitive)
            ?.takeIf { it.isString }?.content
    val clarificationMessageId: String? get() =
        ((clarificationReceipt as? JsonObject)?.get("message_id") as? JsonPrimitive)
            ?.takeIf { it.isString }?.content

    override fun toString(): String = "AssistantMessage(id=$id, state=$state, content=redacted)"
}

@Serializable
data class AssistantImage(
    val id: String,
    val width: Int,
    val height: Int,
    val url: String,
    @SerialName("preview_url") val previewUrl: String? = null,
)

@Serializable
data class AssistantTurn(
    val id: String,
    val state: String,
    @SerialName("reply_message_id") val replyMessageId: String? = null,
    val plugin: String? = null,
    val result: AssistantResult? = null,
    @SerialName("usage") val usageData: JsonElement? = null,
) {
    /** Malformed optional measurements never hide an otherwise durable reply. */
    val usage: AssistantUsage? get() = parseAssistantUsage(usageData)
}

/** A disposable, account-separated last-page cache; this is never command-delivery evidence. */
interface AssistantHistoryCache {
    suspend fun read(namespace: AccountNamespace): AssistantHistory?
    suspend fun write(namespace: AccountNamespace, page: AssistantHistory)
    suspend fun clear(namespace: AccountNamespace)
}

fun decodeAssistantHistory(bytes: ByteArray): AssistantHistory =
    HistoryJson.decodeFromString<AssistantHistory>(bytes.decodeToString(throwOnInvalidSequence = true)).also { page ->
        require(page.before == null || validHistoryId(page.before))
        require(page.after == null || validHistoryId(page.after))
        require(page.messages.map { it.id }.distinct().size == page.messages.size)
        for (message in page.messages) {
            require(validHistoryId(message.id))
            Instant.parse(message.createdAt)
            require(message.intentRevision == null || message.intentRevision >= 0)
            require(message.turns.all { validHistoryId(it.id) && it.state.isNotBlank() })
        }
    }

fun encodeAssistantHistory(page: AssistantHistory): ByteArray =
    HistoryJson.encodeToString(page).encodeToByteArray()

private fun validHistoryId(value: String): Boolean =
    value.isNotBlank() && value.length <= 128 && value.none(Char::isISOControl)

/** Unknown or malformed permissions never enable a control. */
private object AssistantPermissionSerializer : KSerializer<Boolean> {
    override val descriptor = PrimitiveSerialDescriptor("AssistantPermission", PrimitiveKind.BOOLEAN)
    override fun deserialize(decoder: Decoder): Boolean {
        val value = (decoder as JsonDecoder).decodeJsonElement() as? JsonPrimitive ?: return false
        return !value.isString && value.booleanOrNull == true
    }
    override fun serialize(encoder: Encoder, value: Boolean) = encoder.encodeBoolean(value)
}

/** Newest pages first, each page oldest first, matching the web/server cursor contract. */
data class AssistantHistoryWindow(val pages: List<AssistantHistory> = emptyList()) {
    companion object {
        const val MAX_PAGES = 6
        fun around(page: AssistantHistory, messageId: String): AssistantHistoryWindow {
            require(page.messages.any { message -> message.id == messageId ||
                message.turns.any { it.replyMessageId == messageId } })
            return AssistantHistoryWindow(listOf(page.copy(messages = page.messages.sortedWith(assistantMessageOrder))))
        }
    }

    val messages: List<AssistantMessage> by lazy { pages.asReversed().flatMap { it.messages } }
    val before: String? get() = pages.lastOrNull()?.before
    val after: String? get() = pages.firstOrNull()?.after
    val atHead: Boolean get() = after == null
    val newest: AssistantMessage? get() = pages.firstNotNullOfOrNull { it.messages.lastOrNull() }
    /** Only the newest server-sized page belongs on disk, even after exact updates grew the head. */
    val cacheHead: AssistantHistory? get() = if (!atHead) null else pages.firstOrNull()?.let { page ->
        if (page.messages.size <= 50) page else {
            val recent = page.messages.takeLast(50)
            page.copy(messages = recent, before = recent.first().id)
        }
    }

    fun message(id: String): AssistantMessage? = messages.firstOrNull { it.id == id }
    fun ownerOfTurn(id: String): String? = messages.firstOrNull { message -> message.turns.any { it.id == id } }?.id

    fun appendOlder(page: AssistantHistory): AssistantHistoryWindow {
        val known = messages.mapTo(mutableSetOf()) { it.id }
        val older = page.copy(messages = page.messages.filter { known.add(it.id) }.sortedWith(assistantMessageOrder))
        return copy(pages = (pages + older).takeLast(MAX_PAGES)).repairCursor().bounded(keepNewest = false)
    }

    fun prependNewer(page: AssistantHistory): AssistantHistoryWindow {
        val known = messages.mapTo(mutableSetOf()) { it.id }
        val newer = page.copy(messages = page.messages.filter { known.add(it.id) }.sortedWith(assistantMessageOrder))
        return copy(pages = (listOf(newer) + pages).take(MAX_PAGES)).repairCursor().bounded()
    }

    /** The fresh head owns its whole range, including deletions, while older pages survive. */
    fun mergeHead(head: AssistantHistory): AssistantHistoryWindow {
        val shared = share(head)
        val oldest = shared.messages.firstOrNull()
        val latest = newest
        if (pages.isEmpty() || oldest == null || shared.before == null || latest == null ||
            assistantMessageOrder.compare(oldest, latest) > 0) return AssistantHistoryWindow(listOf(shared))
        val merged = pages.mapIndexedNotNull { index, page ->
            val older = page.messages.filter { assistantMessageOrder.compare(it, oldest) < 0 }
            if (index == 0) page.copy(
                messages = older + shared.messages,
                before = if (older.isEmpty()) shared.before else page.before,
                after = shared.after,
            ) else if (older.isEmpty()) null else if (older.size == page.messages.size) page else page.copy(messages = older)
        }
        return AssistantHistoryWindow(merged).repairCursor().rechunk().bounded()
    }

    /** Exact-identity reads replace only the exchanges they name. Unknown older gaps stay unloaded. */
    fun applyMessages(
        fetched: List<AssistantMessage>,
        missing: List<String> = emptyList(),
        atHead: Boolean = this.atHead,
    ): AssistantHistoryWindow {
        if (pages.isEmpty()) return this
        val gone = missing.toSet()
        val byId = fetched.associateByTo(linkedMapOf()) { it.id }
        var next = pages.map { page ->
            val changed = page.messages.mapNotNull { old ->
                if (old.id in gone) null else byId.remove(old.id)?.let { if (old == it) old else it } ?: old
            }
            if (changed.size == page.messages.size && changed.indices.all { changed[it] === page.messages[it] }) page
            else page.copy(messages = changed)
        }
        val latest = next.firstNotNullOfOrNull { it.messages.lastOrNull() }
        for (message in byId.values.sortedWith(assistantMessageOrder)) {
            if (latest == null || assistantMessageOrder.compare(message, latest) > 0) {
                if (atHead) next = listOf(next.first().copy(messages = next.first().messages + message)) + next.drop(1)
                continue
            }
            val target = next.indexOfFirst { page ->
                page.messages.firstOrNull()?.let { assistantMessageOrder.compare(message, it) > 0 } == true
            }.takeIf { it >= 0 } ?: next.lastIndex.takeIf { next.last().before == null } ?: continue
            next = next.mapIndexed { index, page ->
                if (index == target) page.copy(messages = (page.messages + message).sortedWith(assistantMessageOrder)) else page
            }
        }
        return AssistantHistoryWindow(next).repairCursor().rechunk().bounded()
    }

    private fun bounded(keepNewest: Boolean = true): AssistantHistoryWindow {
        val all = messages
        if (all.size <= MAX_PAGES * 50) return this
        val kept = if (keepNewest) all.takeLast(MAX_PAGES * 50) else all.take(MAX_PAGES * 50)
        val olderDropped = kept.first().id != all.first().id
        val newerDropped = kept.last().id != all.last().id
        val chunks = kept.chunked(50).asReversed()
        return AssistantHistoryWindow(chunks.mapIndexed { index, chunk ->
            AssistantHistory(chunk,
                before = if (index == chunks.lastIndex && !olderDropped) before else chunk.first().id,
                after = if (index == 0 && !newerDropped) after else chunk.last().id,
            )
        })
    }

    private fun share(page: AssistantHistory): AssistantHistory {
        val known = messages.associateBy { it.id }
        return page.copy(messages = page.messages.map { fresh -> known[fresh.id]?.takeIf { it == fresh } ?: fresh }
            .sortedWith(assistantMessageOrder))
    }

    private fun repairCursor(): AssistantHistoryWindow {
        val cursor = before ?: return this
        if (messages.any { it.id == cursor }) return this
        val oldest = pages.asReversed().firstNotNullOfOrNull { it.messages.firstOrNull() } ?: return this
        return copy(pages = pages.mapIndexed { index, page ->
            if (index == pages.lastIndex) page.copy(before = oldest.id) else page
        })
    }

    private fun rechunk(size: Int = 50): AssistantHistoryWindow {
        val first = pages.firstOrNull() ?: return this
        if (first.messages.size <= size * 2) return this
        val recent = first.messages.takeLast(size)
        return copy(pages = listOf(
            first.copy(messages = recent, before = recent.first().id),
            first.copy(messages = first.messages.dropLast(size), after = first.messages.dropLast(size).last().id),
        ).plus(pages.drop(1)).take(MAX_PAGES))
    }
}

/** Instants retain microseconds; ids settle equal timestamps. */
val assistantMessageOrder: Comparator<AssistantMessage> = compareBy<AssistantMessage>(
    { it.occurredAt }, { it.id },
)
