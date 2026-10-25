package assistant.mobile.core.assistant

import assistant.mobile.core.GeneratedStrings
import assistant.mobile.core.HttpOutcome
import assistant.mobile.core.LocalizedString
import assistant.mobile.core.NativeHttpClient
import assistant.mobile.core.NativeJson
import assistant.mobile.core.SessionManager
import assistant.mobile.core.nativeError
import io.ktor.http.encodeURLParameter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.plus
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.time.Instant

@Serializable
data class AssistantSearchHit(
    val id: String,
    val author: String,
    @SerialName("occurred_at") val occurredAt: String,
    val text: String,
)

@Serializable
data class AssistantSearchPage(val messages: List<AssistantSearchHit>, val cursor: String?)

data class AssistantSearchBounds(val after: String?, val before: String?)

/** Input days belong to the account's calendar; the server's upper boundary is exclusive. */
fun assistantSearchBounds(after: String, through: String): AssistantSearchBounds? = runCatching {
    fun day(value: String): LocalDate? = value.takeIf { it.isNotEmpty() }?.let {
        LocalDate.parse(it).also { parsed -> require(parsed.toString() == value) }
    }
    val start = day(after)
    val end = day(through)
    require(start == null || end == null || start <= end)
    AssistantSearchBounds(start?.toString(), end?.plus(1, DateTimeUnit.DAY)?.toString())
}.getOrNull()

fun decodeAssistantSearch(bytes: ByteArray): AssistantSearchPage =
    NativeJson.decodeFromString<AssistantSearchPage>(bytes.decodeToString(throwOnInvalidSequence = true)).also { page ->
        require(page.cursor == null || page.cursor.isNotBlank() && page.cursor.length <= 4096 && page.cursor.none(Char::isISOControl))
        require(page.messages.map { it.id }.distinct().size == page.messages.size)
        page.messages.forEach {
            require(validAssistantIdentity(it.id) && it.author in setOf("user", "assistant"))
            Instant.parse(it.occurredAt)
        }
    }

data class AssistantSearchState(
    val text: String = "",
    val afterDay: String = "",
    val throughDay: String = "",
    val hits: List<AssistantSearchHit> = emptyList(),
    val cursor: String? = null,
    val loading: Boolean = false,
    val completed: Boolean = false,
    val error: LocalizedString? = null,
    val revision: Long = 0,
) {
    val validBounds: Boolean get() = assistantSearchBounds(afterDay, throughDay) != null
    val ready: Boolean get() = text.trim().length in 2..160 && validBounds

    fun criteria(text: String, afterDay: String, throughDay: String): AssistantSearchState =
        if (this.text == text && this.afterDay == afterDay && this.throughDay == throughDay) this
        else AssistantSearchState(text, afterDay, throughDay, revision = revision + 1)

    /** A late page cannot restore results or a cursor from older criteria, including A → B → A. */
    fun received(page: AssistantSearchPage, requestRevision: Long, append: Boolean): AssistantSearchState {
        if (requestRevision != revision || !ready) return this
        val combined = if (append) (hits + page.messages).distinctBy { it.id } else page.messages
        return copy(hits = combined, cursor = page.cursor, loading = false, completed = true, error = null)
    }
}

class AssistantSearchController(
    private val scope: CoroutineScope,
    private val sessions: SessionManager,
    private val http: NativeHttpClient,
) {
    private val mutableState = MutableStateFlow(AssistantSearchState())
    val state: StateFlow<AssistantSearchState> = mutableState.asStateFlow()
    private var scheduled: Job? = null
    private var requestVersion = 0L

    init {
        scope.launch { sessions.leases.collect { reset() } }
    }

    fun setCriteria(text: String, afterDay: String = state.value.afterDay, throughDay: String = state.value.throughDay) {
        val next = state.value.criteria(text.take(160), afterDay, throughDay)
        if (next === state.value) return
        scheduled?.cancel()
        requestVersion++
        mutableState.value = next
        if (next.ready) scheduled = scope.launch { delay(300); scheduled = null; search() }
    }

    fun reset() {
        scheduled?.cancel()
        requestVersion++
        mutableState.value = AssistantSearchState(revision = state.value.revision + 1)
    }

    fun close() {
        scheduled?.cancel()
        requestVersion++
        mutableState.value = state.value.copy(loading = false)
    }

    suspend fun search() {
        scheduled?.cancel()
        scheduled = null
        read(append = false)
    }
    suspend fun loadMore() = read(append = true)

    private suspend fun read(append: Boolean) {
        val lease = sessions.lease() ?: return
        val snapshot = state.value
        val bounds = assistantSearchBounds(snapshot.afterDay, snapshot.throughDay) ?: return
        if (!snapshot.ready || append && (snapshot.cursor == null || snapshot.loading)) return
        val version = ++requestVersion
        mutableState.value = snapshot.copy(loading = true, error = null)
        val query = buildList {
            add("query=${snapshot.text.trim().encodeURLParameter()}")
            bounds.after?.let { add("after=${it.encodeURLParameter()}") }
            bounds.before?.let { add("before=${it.encodeURLParameter()}") }
            if (append) snapshot.cursor?.let { add("cursor=${it.encodeURLParameter()}") }
        }.joinToString("&")
        fun current() = version == requestVersion && sessions.isCurrent(lease) && state.value.revision == snapshot.revision
        try {
            val response = http.request("GET", "/v1/conversation/search?$query", lease = lease)
            if (!current()) return
            if (response is HttpOutcome.Success) {
                val page = decodeAssistantSearch(response.body)
                require(!append || page.cursor == null || page.cursor != snapshot.cursor)
                mutableState.value = state.value.received(page, snapshot.revision, append)
            } else mutableState.value = state.value.copy(loading = false, error = nativeError(response))
        } catch (cancelled: CancellationException) {
            if (current()) mutableState.value = state.value.copy(loading = false)
            throw cancelled
        } catch (_: Exception) {
            if (current()) mutableState.value = state.value.copy(loading = false, error = GeneratedStrings.Errors.server)
        }
    }
}

internal fun validAssistantIdentity(value: String): Boolean =
    value.isNotBlank() && value.length <= 128 && value.none(Char::isISOControl)
