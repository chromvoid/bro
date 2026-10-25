package assistant.mobile.core.assistant

import assistant.mobile.core.GeneratedStrings
import assistant.mobile.core.HttpOutcome
import assistant.mobile.core.NativeHttpClient
import assistant.mobile.core.NativeJson
import assistant.mobile.core.SessionLease
import assistant.mobile.core.SessionManager
import assistant.mobile.core.journal.NewSubmission
import assistant.mobile.core.journal.StaleJournalSession
import assistant.mobile.core.journal.Submission
import assistant.mobile.core.journal.SubmissionCoordinator
import assistant.mobile.core.journal.SubmissionState
import assistant.mobile.core.journal.UnpersistedSubmissionOutcome
import assistant.mobile.core.nativeError
import io.ktor.http.encodeURLParameter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.encodeToString
import kotlin.time.Clock
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/** Owns commands on an exact exchange. Reconnect/visibility may read receipts, never resend. */
class AssistantInteractionsController(
    private val scope: CoroutineScope,
    private val sessions: SessionManager,
    private val http: NativeHttpClient,
    private val submissions: SubmissionCoordinator,
    private val onChanged: suspend (Set<String>) -> Unit = {},
    private val clockMs: () -> Long = { Clock.System.now().toEpochMilliseconds() },
) {
    private val mutableState = MutableStateFlow(AssistantInteractionsState())
    val state: StateFlow<AssistantInteractionsState> = mutableState.asStateFlow()
    private val visible = MutableStateFlow(false)
    private var boundLease: SessionLease? = null
    private var messages: List<AssistantMessage> = emptyList()
    private var entries: List<Submission> = emptyList()
    private val sending = linkedMapOf<String, Submission>()
    private val checkedHistory = mutableMapOf<String, AssistantClarificationReceipt>()
    private val readMutex = Mutex()
    private val submitMutex = Mutex()

    init {
        scope.launch {
            combine(sessions.leases, visible) { lease, active -> lease to active }
                .collectLatest { (lease, active) ->
                    bind(lease)
                    if (lease != null && active) refresh()
                }
        }
    }

    fun activate() { visible.value = true }
    fun deactivate() { visible.value = false }

    /** Caller supplies only authoritative account-bound history, never activity previews. */
    fun updateMessages(value: List<AssistantMessage>) {
        val lease = currentLease() ?: return
        messages = value
        for (message in value) {
            val item = historyAssistantClarification(message) ?: continue
            val checked = checkedHistory[item.key] ?: continue
            if (item.sourceMessageId == checked.sourceMessageId && clarificationProgress(item.status) >= clarificationProgress(checked.state)) {
                checkedHistory.remove(item.key)
            }
        }
        publish(lease)
        scope.launch { reload(lease) }
    }

    /** Reconciliation reads the already journalled key; it never creates a new command. */
    suspend fun refresh() {
        val lease = currentLease() ?: return
        readMutex.withLock {
            if (!visible.value || !sessions.isCurrent(lease)) return
            reload(lease)
            val snapshot = entries
            for (entry in snapshot) {
                if (!sessions.isCurrent(lease) || !visible.value) return
                if (entry.key in state.value.busy || entry.knownUnadmitted()) continue
                if (entry.state in setOf(SubmissionState.SENDING, SubmissionState.UNCERTAIN, SubmissionState.ACCEPTED)) {
                    try {
                        submissions.check(lease, entry.key)
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: StaleJournalSession) { return }
                    catch (_: Exception) { update(lease) { copy(error = GeneratedStrings.Errors.network) } }
                }
            }
            reload(lease)
        }
    }

    /** A captured generation keeps a late answer on its original request, even after completion. */
    suspend fun clarify(messageId: String, text: String, generation: String? = null): Boolean =
        scope.async(start = CoroutineStart.UNDISPATCHED) {
            val lease = currentLease() ?: return@async false
            val message = messages.firstOrNull { it.id == messageId } ?: return@async false
            val targetGeneration = generation ?: message.runGeneration?.takeIf { message.supportsClarification() }
                ?: return@async false
            if (targetGeneration.isBlank() || text.isBlank() || text.length > 20000) return@async false
            val body = NativeJson.encodeToString(AssistantClarificationRequest(messageId, targetGeneration, text = text.trim()))
                .encodeToByteArray()
            submit(lease, ASSISTANT_MOVES, body)
        }.await()

    suspend fun control(messageId: String, action: AssistantControlAction) {
        scope.async(start = CoroutineStart.UNDISPATCHED) {
            val lease = currentLease() ?: return@async
            val message = messages.firstOrNull { it.id == messageId } ?: return@async
            if (action !in message.availableControls()) return@async
            val body = NativeJson.encodeToString(AssistantControlRequest(messageId, requireNotNull(message.runGeneration), action))
                .encodeToByteArray()
            submit(lease, ASSISTANT_CONTROLS, body)
        }.await()
    }

    suspend fun check(key: String) {
        val lease = currentLease() ?: return
        val entry = entries.firstOrNull { it.key == key }
        if (entry == null) checkHistoryClarification(key) else deliveryAction(lease, entry, retry = false)
    }

    /** Explicit retry is the sole resend path, always using coordinator-owned original bytes/key. */
    suspend fun retry(key: String) {
        val lease = currentLease() ?: return
        val entry = entries.firstOrNull { it.key == key } ?: return
        deliveryAction(lease, entry, retry = true)
    }

    suspend fun checkClarification(key: String) = check(key)

    suspend fun checkHistoryClarification(key: String) {
        val lease = currentLease() ?: return
        val item = state.value.clarifications.firstOrNull { it.key == key && it.sourceMessageId != null } ?: return
        if (!item.canCheck || key in state.value.busy) return
        update(lease) { copy(busy = busy + key, error = null) }
        try {
            when (val response = http.request("GET", "$ASSISTANT_MOVES/by-key/${key.encodeURLParameter()}", lease = lease)) {
                is HttpOutcome.Success -> {
                    val receipt = decodeClarificationReceipt(response.body)
                    require(receipt.submissionKey == key && receipt.messageId == item.targetMessageId &&
                        receipt.generation == item.generation && receipt.sourceMessageId == item.sourceMessageId)
                    if (!sessions.isCurrent(lease)) return
                    checkedHistory[key] = receipt
                    publish(lease)
                    onChanged(setOf(item.targetMessageId, requireNotNull(item.sourceMessageId)))
                }
                else -> update(lease) { copy(error = nativeError(response)) }
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { update(lease) { copy(error = GeneratedStrings.Errors.network) } }
        finally { update(lease) { copy(busy = busy - key) }; publish(lease) }
    }

    /** Rejected commands may be dismissed; uncertain outcomes retain all durable evidence. */
    suspend fun dismiss(key: String) {
        val lease = currentLease() ?: return
        val entry = entries.firstOrNull { it.key == key } ?: return
        // A definite transport refusal remains in the generic journal; it has no receipt to acknowledge.
        if (entry.state != SubmissionState.RESOLVED && entry.state != SubmissionState.ACCEPTED) return
        val item = entry.controlItem(false, clockMs())
        if (item?.status !in setOf(AssistantControlStatus.Settled, AssistantControlStatus.Rejected)) return
        try { submissions.acknowledge(lease, key); reload(lease) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { update(lease) { copy(error = GeneratedStrings.Errors.server) } }
    }

    private suspend fun submit(lease: SessionLease, endpoint: String, body: ByteArray): Boolean = submitMutex.withLock {
        try { submitOwned(lease, endpoint, body) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: StaleJournalSession) { false }
        catch (_: Exception) { update(lease) { copy(error = GeneratedStrings.Errors.server) }; false }
    }

    private suspend fun submitOwned(lease: SessionLease, endpoint: String, body: ByteArray): Boolean {
        // A second tap cannot mint a fresh key while this exact authored operation is unresolved.
        if (state.value.busy.isNotEmpty()) return false
        val previous = submissions.pending(lease).firstOrNull {
            it.endpoint == endpoint && it.body.contentEquals(body) && !it.knownUnadmitted() &&
                it.state in setOf(SubmissionState.PREPARED, SubmissionState.SENDING, SubmissionState.UNCERTAIN, SubmissionState.ACCEPTED)
        }
        if (previous != null) { reload(lease); return true }
        val key = interactionKey()
        val transient = Submission(lease.namespace, key, endpoint, "$endpoint/by-key/$key", "POST", body,
            lease.namespace.userId, emptyMap(), null, SubmissionState.ACCEPTED, clockMs())
        sending[key] = transient
        update(lease) { copy(busy = busy + key, error = null) }
        publish(lease)
        var owned = false
        try {
            val result = try {
                submissions.submit(lease, NewSubmission(endpoint, "$endpoint/by-key/", body, lease.namespace.userId,
                    found = SubmissionState.ACCEPTED, key = key))
            } catch (failure: UnpersistedSubmissionOutcome) { failure.submission }
            if (!sessions.isCurrent(lease)) return false
            owned = !result.knownUnadmitted()
            if (!owned) update(lease) { copy(error = result.failureStatus?.let { nativeError(HttpOutcome.Failure(it, byteArrayOf())) }
                ?: GeneratedStrings.Errors.server) }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: StaleJournalSession) { return false }
        catch (_: Exception) { update(lease) { copy(error = GeneratedStrings.Errors.server) } }
        finally {
            if (sessions.isCurrent(lease)) {
                sending.remove(key)
                update(lease) { copy(busy = busy - key) }
                reload(lease)
                val persisted = entries.firstOrNull { it.key == key }
                if (persisted != null && !persisted.knownUnadmitted()) owned = true
                changed(lease, persisted)
            }
        }
        return owned
    }

    private suspend fun deliveryAction(lease: SessionLease, entry: Submission, retry: Boolean) {
        if (entry.key in state.value.busy) return
        update(lease) { copy(busy = busy + entry.key, error = null) }
        try {
            if (retry) submissions.retry(lease, entry.key) else submissions.check(lease, entry.key)
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { update(lease) { copy(error = GeneratedStrings.Errors.network) } }
        finally {
            update(lease) { copy(busy = busy - entry.key) }
            reload(lease)
            changed(lease, entries.firstOrNull { it.key == entry.key })
        }
    }

    private suspend fun changed(lease: SessionLease, entry: Submission?) {
        if (entry == null || !sessions.isCurrent(lease)) return
        val ids = if (entry.endpoint == ASSISTANT_CONTROLS) entry.controlItem(false, clockMs())?.let { setOf(it.messageId) }
        else entry.clarificationItem(false, clockMs())?.let { setOfNotNull(it.targetMessageId, it.sourceMessageId) }
        if (!ids.isNullOrEmpty()) onChanged(ids)
    }

    private suspend fun reload(lease: SessionLease) {
        if (!sessions.isCurrent(lease) || boundLease !== lease) return
        try {
            val pending = submissions.pending(lease).filter { it.endpoint in setOf(ASSISTANT_MOVES, ASSISTANT_CONTROLS) }
            if (!sessions.isCurrent(lease) || boundLease !== lease) return
            entries = pending
            publish(lease)
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { update(lease) { copy(error = GeneratedStrings.Errors.server) } }
    }

    private fun publish(lease: SessionLease) {
        if (!sessions.isCurrent(lease) || boundLease !== lease) return
        val all = (entries + sending.values).associateBy { it.key }.values.sortedBy { it.createdAtMillis }
        val history = messages.mapNotNull(::historyAssistantClarification)
            .map { if (it.key in state.value.busy) it.copy(canCheck = false) else it }
            .associateByTo(linkedMapOf()) { it.key }
        for ((key, receipt) in checkedHistory) {
            val source = history[key] ?: continue
            history[key] = receipt.item(source.text, receipt.state in liveClarificationStates && key !in state.value.busy)
        }
        for (entry in all.filter { it.endpoint == ASSISTANT_MOVES }) {
            // Authoritative history supersedes the journal's earlier receipt for the same source.
            if (entry.key !in history) entry.clarificationItem(entry.key in sending || entry.key in state.value.busy, clockMs())
                ?.let { history[entry.key] = it }
        }
        update(lease) { copy(clarifications = history.values.toList(), controls = all.filter { it.endpoint == ASSISTANT_CONTROLS }
            .mapNotNull { it.controlItem(it.key in sending || it.key in busy, clockMs()) }) }
    }

    private fun bind(lease: SessionLease?) {
        if (boundLease === lease) return
        boundLease = lease
        messages = emptyList()
        entries = emptyList()
        sending.clear()
        checkedHistory.clear()
        mutableState.value = AssistantInteractionsState(namespace = lease?.namespace)
    }

    private fun currentLease(): SessionLease? = sessions.lease()?.takeIf { boundLease === it }
    private fun update(lease: SessionLease, change: AssistantInteractionsState.() -> AssistantInteractionsState) {
        if (sessions.isCurrent(lease) && boundLease === lease) mutableState.update(change)
    }
}

@OptIn(ExperimentalUuidApi::class)
private fun interactionKey(): String = Uuid.random().toString()

private fun clarificationProgress(status: AssistantClarificationStatus): Int = when (status) {
    AssistantClarificationStatus.Sending, AssistantClarificationStatus.Unknown -> -1
    AssistantClarificationStatus.Accepted -> 0
    AssistantClarificationStatus.Delivered -> 1
    else -> 2
}
