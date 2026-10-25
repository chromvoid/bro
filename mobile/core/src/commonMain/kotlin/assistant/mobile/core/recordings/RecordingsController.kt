package assistant.mobile.core.recordings

import assistant.mobile.core.GeneratedStrings
import assistant.mobile.core.HttpOutcome
import assistant.mobile.core.LocalizedString
import assistant.mobile.core.NativeHttpClient
import assistant.mobile.core.SessionLease
import assistant.mobile.core.SessionManager
import assistant.mobile.core.nativeError
import io.ktor.http.encodeURLParameter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.Instant

/** No domain operation is exposed here: a transcript is display-only private text. */
class RecordingsController(
    scope: CoroutineScope,
    private val sessions: SessionManager,
    private val http: NativeHttpClient,
) {
    private val mutableState = MutableStateFlow<RecordingsState?>(null)
    val state: StateFlow<RecordingsState?> = mutableState.asStateFlow()
    private val visibility = MutableStateFlow(ArchiveVisibility(false, 0))
    private val date = MutableStateFlow("")
    private var boundLease: SessionLease? = null
    private var generation = 0L
    private var pollOffset = 0
    private var selectionGeneration = 0L
    private val reads = Mutex()
    private val polls = Mutex()
    private val writes = Mutex()
    private val owner = scope.launch {
        combine(sessions.leases, sessions.identity, visibility, date) { lease, identity, visible, day ->
            ArchiveOwner(lease, identity?.timezone ?: "UTC", visible, day)
        }.distinctUntilChanged().collectLatest { current ->
            if (!current.visible.active || current.lease == null) {
                clear()
                return@collectLatest
            }
            bind(current.lease, current.timezone, current.date)
            refresh()
            while (true) {
                delay(10_000)
                poll()
            }
        }
    }

    fun activate() { visibility.update { ArchiveVisibility(true, it.generation + 1) } }
    fun deactivate() { visibility.update { it.copy(active = false) }; clear() }
    fun close() { owner.cancel(); deactivate() }

    /** The UI's clearable calendar supplies an ISO date; invalid input means all dates as in web. */
    fun setDate(day: String) {
        val value = day.takeIf { recordingDateRange(it, "UTC").from != null } ?: ""
        if (state.value?.busy == true || state.value?.mutationUnknown == true) return
        date.value = value
    }

    suspend fun refresh(more: Boolean = false) = reads.withLock {
        val lease = sessions.lease() ?: return@withLock
        val current = state.value ?: return@withLock
        if (!owned(lease, generation) || (more && current.nextCursor == null)) return@withLock
        val revision = generation
        val range = recordingDateRange(current.date, current.timezone)
        val params = mutableListOf("limit=30")
        if (more) current.nextCursor?.let { params += "cursor=${it.encodeURLParameter()}" }
        range.from?.let { params += "from=${it.encodeURLParameter()}" }
        range.to?.let { params += "to=${it.encodeURLParameter()}" }
        change(lease, revision) { copy(loading = !more, loadingMore = more, error = null) }
        val (listReply, usageReply) = coroutineScope {
            val list = async { http.request("GET", "/v1/ambient-audio/recordings?${params.joinToString("&")}", lease = lease) }
            val usage = async { http.request("GET", "/v1/ambient-audio", lease = lease) }
            list.await() to usage.await()
        }
        if (!owned(lease, revision)) return@withLock
        if (denied(lease, revision, listReply) || denied(lease, revision, usageReply)) return@withLock
        val page = (listReply as? HttpOutcome.Success)?.let { runCatching { decodeRecordingPage(it.body) }.getOrNull() }
        val usage = (usageReply as? HttpOutcome.Success)?.let { runCatching { decodeRecordingUsage(it.body) }.getOrNull() }
        var reselect: String? = null
        change(lease, revision) {
            val entries = page?.let { if (more) (items + it.recordings).associateBy(Recording::id).values.toList() else it.recordings }
            val selected = selectedId?.takeIf { id -> entries == null || entries.any { it.id == id } }
            reselect = selected?.takeIf { !more && page != null }
            copy(
                items = entries ?: items, nextCursor = if (page == null) nextCursor else page.nextCursor,
                loaded = loaded || page != null, loading = false, loadingMore = false,
                error = if (page == null) failure(listReply) else null,
                usage = usage ?: this.usage, usageError = if (usage == null) GeneratedStrings.Recordings.usageFailed else null,
                selectedId = selected, transcript = if (selected == null) null else transcript,
                detailError = if (selected == null) null else detailError,
                mutationUnknown = if (!more && page != null) false else mutationUnknown,
            )
        }
        reselect?.let { select(it) }
    }

    suspend fun select(id: String?) {
        val lease = boundLease ?: return
        val revision = generation
        val current = state.value ?: return
        if (current.busy && id != null && id != current.selectedId) return
        if (id != null && current.items.none { it.id == id }) return
        val selection = ++selectionGeneration
        change(lease, revision) { copy(selectedId = id, transcript = null, detailError = null, detailLoading = id != null,
            mutationError = if (!mutationUnknown && !busy) null else mutationError) }
        if (id == null) return
        // A previous selection can finish later, but never replace the currently opened fragment.
        val reply = http.request("GET", "/v1/ambient-audio/batches/$id/transcription", lease = lease)
        if (!owned(lease, revision) || selection != selectionGeneration) return
        if (denied(lease, revision, reply)) return
        val value = (reply as? HttpOutcome.Success)?.let { runCatching { decodeRecordingTranscript(it.body, id) }.getOrNull() }
        if (value != null) merge(lease, revision, value)
        change(lease, revision) { copy(detailLoading = false, detailError = if (value == null) failure(reply) else null) }
    }

    /** At most eight reads per tick, with the selected fragment first and fair rotation after it. */
    suspend fun poll() {
        if (!polls.tryLock()) return
        try {
            val lease = boundLease ?: return
            val revision = generation
            val current = state.value ?: return
            if (!owned(lease, revision)) return
            val waiting = current.items.filter { it.transcription.status.pending }
            if (waiting.isEmpty()) return
            val priority = waiting.firstOrNull { it.id == current.selectedId }
            val others = waiting.filterNot { it === priority }
            val offset = if (others.isNotEmpty()) pollOffset % others.size else 0
            val rotated = others.drop(offset) + others.take(offset)
            val selected = (listOfNotNull(priority) + rotated).take(8)
            pollOffset = if (others.isNotEmpty()) (offset + selected.size - if (priority != null) 1 else 0) % others.size else 0
            val results = coroutineScope { selected.map { item -> async {
                item.id to http.request("GET", "/v1/ambient-audio/batches/${item.id}/transcription", lease = lease)
            } }.awaitAll() }
            if (!owned(lease, revision)) return
            change(lease, revision) { copy(pollError = null) }
            for ((id, reply) in results) {
                if (denied(lease, revision, reply)) break
                val transcript = (reply as? HttpOutcome.Success)?.let { runCatching { decodeRecordingTranscript(it.body, id) }.getOrNull() }
                if (transcript != null) merge(lease, revision, transcript)
                else change(lease, revision) { copy(pollError = failure(reply)) }
            }
        } finally { polls.unlock() }
    }

    /** Every write comes from a confirmation. Unknown outcomes block another write until refresh. */
    suspend fun mutate(kind: RecordingMutation, id: String): Boolean {
        if (!writes.tryLock()) return false
        try {
            val lease = boundLease ?: return false
            val revision = generation
            val current = state.value ?: return false
            if (!owned(lease, revision) || current.busy || current.mutationUnknown || current.items.none { it.id == id }) return false
            change(lease, revision) { copy(busy = true, mutationError = null, mutationUnknown = false) }
            try {
                val reply = when (kind) {
                    RecordingMutation.Delete -> http.request("DELETE", "/v1/ambient-audio/batches/$id", lease = lease)
                    RecordingMutation.Retry -> http.request("POST", "/v1/ambient-audio/batches/$id/transcription/retry", lease = lease)
                }
                if (!owned(lease, revision) || denied(lease, revision, reply)) return false
                if (reply !is HttpOutcome.Success) {
                    change(lease, revision) { copy(mutationError = failure(reply), mutationUnknown = uncertain(reply)) }
                    return false
                }
                val valid = runCatching {
                    if (kind == RecordingMutation.Delete) decodeRecordingDeletion(reply.body, id)
                    else merge(lease, revision, decodeRecordingTranscript(reply.body, id))
                }.isSuccess
                if (!valid) {
                    change(lease, revision) { copy(mutationError = nativeError(HttpOutcome.InvalidResponse), mutationUnknown = true) }
                    return false
                }
                if (kind == RecordingMutation.Delete) {
                    change(lease, revision) { copy(items = items.filterNot { it.id == id },
                        selectedId = selectedId?.takeIf { it != id }, transcript = transcript?.takeIf { it.id != id }) }
                    refresh()
                }
                return owned(lease, revision)
            } finally { change(lease, revision) { copy(busy = false) } }
        } finally { writes.unlock() }
    }

    /** MediaPlayer receives private bytes, never a URL or a Bearer credential. */
    suspend fun playback(id: String, compatible: Boolean = false): RecordingAudio? {
        val lease = boundLease ?: return null
        val revision = generation
        val item = state.value?.items?.firstOrNull { it.id == id } ?: return null
        if (!owned(lease, revision)) return null
        val path = "/v1/ambient-audio/batches/$id" + if (compatible) "/playback?format=wav" else ""
        val reply = http.request("GET", path, lease = lease)
        if (!owned(lease, revision) || denied(lease, revision, reply)) return null
        if (reply !is HttpOutcome.Success) return null
        return recordingAudio(reply.body, reply.headers["content-type"], if (compatible) RecordingFormat.Wav else item.format)
    }

    private fun bind(lease: SessionLease, timezone: String, day: String) {
        if (boundLease === lease && state.value?.timezone == timezone && state.value?.date == day) return
        generation++
        selectionGeneration++
        pollOffset = 0
        boundLease = lease
        mutableState.value = RecordingsState(lease.namespace, timezone, day)
    }

    private fun clear() {
        generation++
        selectionGeneration++
        boundLease = null
        mutableState.value = null
    }

    private fun owned(lease: SessionLease, revision: Long): Boolean =
        boundLease === lease && revision == generation && sessions.isCurrent(lease)

    private inline fun change(lease: SessionLease, revision: Long, update: RecordingsState.() -> RecordingsState) {
        if (owned(lease, revision)) mutableState.update { it?.update() }
    }

    private fun merge(lease: SessionLease, revision: Long, value: RecordingTranscript) {
        change(lease, revision) {
            val previous = items.firstOrNull { it.id == value.id } ?: return@change this
            val oldTime = previous.transcription.updatedAt?.let { Instant.parse(it).toEpochMilliseconds() } ?: Long.MIN_VALUE
            val newTime = value.updatedAt?.let { Instant.parse(it).toEpochMilliseconds() } ?: Long.MIN_VALUE
            if (newTime < oldTime) return@change this
            copy(items = items.map { if (it.id == value.id) it.copy(transcription = value.summary()) else it },
                transcript = if (selectedId == value.id) value else transcript,
                detailError = if (selectedId == value.id) null else detailError)
        }
    }

    private fun denied(lease: SessionLease, revision: Long, reply: HttpOutcome): Boolean {
        if (reply !in listOf(HttpOutcome.Unauthorized, HttpOutcome.Forbidden)) return false
        if (owned(lease, revision)) {
            generation++
            selectionGeneration++
            mutableState.value = state.value?.let { RecordingsState(it.namespace, it.timezone, it.date, error = nativeError(reply)) }
        }
        return true
    }
}

private data class ArchiveVisibility(val active: Boolean, val generation: Long)
private data class ArchiveOwner(val lease: SessionLease?, val timezone: String, val visible: ArchiveVisibility, val date: String)

private fun failure(reply: HttpOutcome): LocalizedString =
    if (reply is HttpOutcome.Failure && reply.status in setOf(404, 410)) GeneratedStrings.Recordings.gone
    else nativeError(if (reply is HttpOutcome.Success) HttpOutcome.InvalidResponse else reply)

private fun uncertain(reply: HttpOutcome): Boolean = when (reply) {
    HttpOutcome.NetworkFailure, HttpOutcome.InvalidResponse -> true
    is HttpOutcome.Failure -> reply.status >= 500
    else -> false
}
