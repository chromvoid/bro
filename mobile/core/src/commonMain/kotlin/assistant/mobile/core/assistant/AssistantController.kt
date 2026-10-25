package assistant.mobile.core.assistant

import assistant.mobile.core.AccountChangeEvent
import assistant.mobile.core.AccountDrafts
import assistant.mobile.core.AccountNamespace
import assistant.mobile.core.GeneratedStrings
import assistant.mobile.core.EventInvalidation
import assistant.mobile.core.HttpOutcome
import assistant.mobile.core.LocalizedString
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
import assistant.mobile.core.photos.validConversationImageId
import assistant.mobile.core.affectedDomains
import io.ktor.http.encodeURLParameter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.time.Clock
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

data class AssistantState(
    val namespace: AccountNamespace? = null,
    val messages: List<AssistantMessage> = emptyList(),
    val pending: List<AssistantPendingSend> = emptyList(),
    val before: String? = null,
    val after: String? = null,
    val anchorId: String? = null,
    val highlightedMessageId: String? = null,
    val navigationVersion: Long = 0,
    val loadingNewer: Boolean = false,
    val replyTarget: AssistantReplyTarget? = null,
    val availability: AssistantAvailability? = null,
    val availabilityLoading: Boolean = false,
    val availabilityFailed: Boolean = false,
    val follow: AssistantFollowState = AssistantFollowState(),
    val loading: Boolean = false,
    val loadingOlder: Boolean = false,
    val draftText: String = "",
    val draftImageId: String? = null,
    val draftReady: Boolean = false,
    val draftError: LocalizedString? = null,
    val error: LocalizedString? = null,
    val sending: Boolean = false,
    val checking: Set<String> = emptySet(),
    val moving: Set<String> = emptySet(),
    val movableIds: Set<String> = emptySet(),
    val activityMessageId: String? = null,
    val activity: AssistantActivityState? = null,
)

private data class AssistantVisibility(val active: Boolean = false, val revision: Long = 0)

@Serializable
private data class AssistantSavedDraft(
    val text: String,
    val submissionKey: String? = null,
    val replyTarget: AssistantReplyTarget? = null,
    val imageId: String? = null,
)

/**
 * Account-scoped presentation owner. History and journal receipts are authoritative; activity is
 * disposable preview. Visibility owns reads and the per-message stream, never automatic writes.
 */
class AssistantController(
    private val scope: CoroutineScope,
    private val sessions: SessionManager,
    private val http: NativeHttpClient,
    private val submissions: SubmissionCoordinator,
    private val drafts: AccountDrafts,
    private val cache: AssistantHistoryCache,
    private val activityStream: AssistantActivityStream = AssistantActivityStream(sessions, http),
    private val clockMs: () -> Long = { Clock.System.now().toEpochMilliseconds() },
) {
    private val mutableState = MutableStateFlow(AssistantState())
    val state: StateFlow<AssistantState> = mutableState.asStateFlow()
    val search = AssistantSearchController(scope, sessions, http)
    val suggestions = AssistantSuggestionsController(scope, sessions, http, clockMs)
    val interactions = AssistantInteractionsController(scope, sessions, http, submissions,
        onChanged = { ids -> currentLease()?.let { refreshMessages(it, ids) } }, clockMs = clockMs)
    private val defaultVisibilityOwner = Any()
    private val visibilityOwners = mutableSetOf<Any>()
    private val visible = MutableStateFlow(AssistantVisibility())
    private var boundLease: SessionLease? = null
    private var hydrated = false
    private var readEpoch = 0L
    private var cacheInvalid = false
    private var window = AssistantHistoryWindow()
    private var entries: List<Submission> = emptyList()
    private val activeSends = mutableSetOf<String>()
    private val confirmed = mutableSetOf<String>()
    private val historyMutex = Mutex()
    private val hydrationMutex = Mutex()
    var draftRevision = 0L
        private set
    private var draftWrite: Deferred<Result<Unit>>? = null
    private var activityJob: Job? = null
    private var visibleRead: Job? = null
    private var activityTarget: Pair<String, String?>? = null
    private var availabilityJob: Job? = null
    private var availabilityEpoch = 0L
    private var requestedAnchor: String? = null

    init {
        scope.launch {
            combine(sessions.leases, visible) { lease, visibility -> lease to visibility.active }
                .collect { (lease, active) ->
                    visibleRead?.cancel()
                    availabilityJob?.cancel()
                    bind(lease)
                    if (lease != null && active) {
                        interactions.activate()
                        suggestions.activate()
                        visibleRead = scope.launch {
                            hydrate(lease)
                            val anchor = requestedAnchor
                            requestedAnchor = null
                            if (anchor != null) openMessage(anchor) else refresh(lease)
                        }
                        availabilityJob = scope.launch {
                            while (isVisible(lease)) {
                                refreshAvailability()
                                delay(15_000)
                            }
                        }
                    } else {
                        interactions.deactivate()
                        suggestions.deactivate()
                    }
                }
        }
    }

    /** Navigation can compose both outgoing and incoming scenes during its transition. */
    fun activate(initialMessageId: String? = null, visibilityOwner: Any = defaultVisibilityOwner) {
        if (!visibilityOwners.add(visibilityOwner)) return
        if (initialMessageId != null && validAssistantIdentity(initialMessageId)) {
            requestedAnchor = initialMessageId
            if (visible.value.active && currentLease() != null) scope.launch {
                // Initial hydration and a newly attached scene may both reach this request.
                if (requestedAnchor == initialMessageId) {
                    requestedAnchor = null
                    openMessage(initialMessageId)
                }
            }
        }
        if (!visible.value.active) {
            // A stop/start in one frame can conflate the inactive value. The revision still
            // restarts cancelled reads when the collector next observes the active state.
            visible.value = AssistantVisibility(true, visible.value.revision + 1)
        }
    }

    fun deactivate(visibilityOwner: Any = defaultVisibilityOwner) {
        if (!visibilityOwners.remove(visibilityOwner) || visibilityOwners.isNotEmpty()) return
        visible.value = AssistantVisibility(false, visible.value.revision + 1)
        requestedAnchor = null
        readEpoch++
        availabilityEpoch++
        currentLease()?.let { lease -> update(lease) {
            copy(loading = false, loadingOlder = false, loadingNewer = false, availabilityLoading = false)
        } }
        visibleRead?.cancel()
        availabilityJob?.cancel()
        interactions.deactivate()
        suggestions.deactivate()
        search.close()
        cancelActivity()
    }

    fun setDraft(value: String) {
        val lease = currentLease() ?: return
        if (!state.value.draftReady) return
        draftRevision++
        update(lease) { copy(draftText = value, draftError = null, error = null) }
        queueDraft(lease, AssistantSavedDraft(value, replyTarget = state.value.replyTarget, imageId = state.value.draftImageId), draftRevision)
    }

    /** Ready uploads persist by id; source files and their bytes never enter the draft journal. */
    fun setDraftImage(imageId: String?) {
        val lease = currentLease() ?: return
        if (!state.value.draftReady || (imageId != null && !validConversationImageId(imageId)) || state.value.draftImageId == imageId) return
        draftRevision++
        update(lease) { copy(draftImageId = imageId, draftError = null, error = null) }
        queueDraft(lease, AssistantSavedDraft(state.value.draftText, replyTarget = state.value.replyTarget, imageId = imageId), draftRevision)
    }

    fun replyTo(messageId: String, preview: String) {
        val lease = currentLease() ?: return
        if (!state.value.draftReady || !validAssistantIdentity(messageId)) return
        draftRevision++
        val target = AssistantReplyTarget(messageId, preview)
        update(lease) { copy(replyTarget = target, draftError = null) }
        queueDraft(lease, AssistantSavedDraft(state.value.draftText, replyTarget = target, imageId = state.value.draftImageId), draftRevision)
    }

    fun clearReply() {
        val lease = currentLease() ?: return
        if (!state.value.draftReady) return
        draftRevision++
        update(lease) { copy(replyTarget = null, draftError = null) }
        queueDraft(lease, AssistantSavedDraft(state.value.draftText, imageId = state.value.draftImageId), draftRevision)
    }

    fun readerPosition(atBottom: Boolean, userScrolled: Boolean = true) {
        val lease = currentLease() ?: return
        update(lease) { copy(follow = follow.position(atBottom, userScrolled, messages)) }
    }

    fun holdSentMessage() {
        val lease = currentLease() ?: return
        update(lease) { copy(follow = follow.hold(messages)) }
    }

    /** Search and deep links use the same account-guarded window and immutable highlight identity. */
    suspend fun openMessage(messageId: String) {
        val lease = currentLease() ?: return
        if (!isVisible(lease) || !validAssistantIdentity(messageId)) return
        val epoch = ++readEpoch
        cancelActivity()
        window = AssistantHistoryWindow()
        update(lease) { copy(messages = emptyList(), before = null, after = null,
            anchorId = messageId, highlightedMessageId = null, navigationVersion = navigationVersion + 1, loading = true,
            loadingOlder = false, loadingNewer = false, error = null,
            follow = AssistantFollowState(following = false, atBottom = false)) }
        historyMutex.withLock {
            if (!isVisible(lease) || !isReadCurrent(lease, epoch)) return
            try {
                val page = readHistory(lease, "?around=${messageId.encodeURLParameter()}")
                if (!isVisible(lease) || !isReadCurrent(lease, epoch)) return
                window = AssistantHistoryWindow.around(page, messageId)
                update(lease) { copy(highlightedMessageId = messageId) }
                publishHistory(lease, epoch)
                reconcileEntries(lease, epoch)
            } catch (cancelled: CancellationException) { throw cancelled
            } catch (failure: AssistantReadFailure) {
                if (isReadCurrent(lease, epoch)) update(lease) { copy(error = nativeError(failure.outcome)) }
            } catch (_: Exception) {
                if (isReadCurrent(lease, epoch)) update(lease) { copy(error = GeneratedStrings.Errors.server) }
            } finally {
                if (isReadCurrent(lease, epoch)) update(lease) { copy(loading = false) }
            }
        }
    }

    /** Reset the range before admitting a new send, so an old anchor cannot hide its answer. */
    suspend fun latest() {
        val lease = currentLease() ?: return
        if (!isVisible(lease)) return
        readEpoch++
        cancelActivity()
        window = AssistantHistoryWindow()
        update(lease) { copy(messages = emptyList(), before = null, after = null,
            anchorId = null, highlightedMessageId = null, loadingOlder = false,
            loadingNewer = false, follow = AssistantFollowState()) }
        refresh(lease)
    }

    suspend fun refreshAvailability() {
        val lease = currentLease() ?: return
        if (!isVisible(lease) || state.value.availabilityLoading) return
        val epoch = availabilityEpoch
        fun current() = isVisible(lease) && availabilityEpoch == epoch
        update(lease) { copy(availabilityLoading = true) }
        try {
            val result = http.request("GET", "/v1/conversation/availability", lease = lease)
            if (!current()) return
            if (result is HttpOutcome.Success) {
                val availability = decodeAssistantAvailability(result.body)
                update(lease) { copy(availability = availability, availabilityFailed = false) }
            } else update(lease) { copy(availabilityFailed = true) }
        } catch (cancelled: CancellationException) { throw cancelled
        } catch (_: Exception) {
            if (current()) update(lease) { copy(availabilityFailed = true) }
        } finally { if (current()) update(lease) { copy(availabilityLoading = false) } }
    }

    /** Reading details also asks the server to settle an unknown turn from its receipts. */
    suspend fun turnDetails(turnId: String): AssistantTurn? {
        val lease = currentLease() ?: return null
        if (!isVisible(lease) || !validAssistantIdentity(turnId)) return null
        val epoch = readEpoch
        try {
            val result = http.request("GET", "/v1/conversation/turns/${turnId.encodeURLParameter()}", lease = lease)
            if (!isVisible(lease) || !isReadCurrent(lease, epoch)) return null
            if (result is HttpOutcome.Success) {
                val turn = NativeJson.decodeFromString<AssistantTurn>(result.body.decodeToString())
                require(turn.id == turnId && turn.state.isNotBlank())
                window.ownerOfTurn(turnId)?.let { refreshMessages(lease, setOf(it)) }
                return turn.takeIf { isVisible(lease) && isReadCurrent(lease, epoch) }
            }
            update(lease) { copy(error = nativeError(result)) }
        } catch (cancelled: CancellationException) { throw cancelled
        } catch (_: Exception) { update(lease) { copy(error = GeneratedStrings.Errors.server) } }
        return null
    }

    /** Revocation clears presentation before waiting for any older read or disk operation. */
    suspend fun invalidateHistory(lease: SessionLease) {
        if (!sessions.isCurrent(lease) || boundLease !== lease) return
        val epoch = ++readEpoch
        availabilityEpoch++
        search.reset()
        cacheInvalid = true
        visibleRead?.cancel()
        cancelActivity()
        window = AssistantHistoryWindow()
        confirmed.clear()
        interactions.updateMessages(emptyList())
        update(lease) { copy(loading = false, loadingOlder = false, loadingNewer = false,
            highlightedMessageId = null, availability = null, availabilityLoading = false,
            availabilityFailed = false, error = null) }
        publishHistory(lease, epoch)
        try {
            historyMutex.withLock {
                if (!isReadCurrent(lease, epoch)) return
                sessions.withLease(lease) { cache.clear(lease.namespace) }
                if (isReadCurrent(lease, epoch)) cacheInvalid = false
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            update(lease) { copy(error = GeneratedStrings.Errors.server) }
        }
    }

    /** Manual refresh and account resync are reads. A hidden screen refreshes on activation. */
    suspend fun refresh(lease: SessionLease? = sessions.lease()) {
        if (lease == null || !isVisible(lease)) return
        hydrate(lease)
        historyMutex.withLock {
            if (!isVisible(lease)) return
            val epoch = readEpoch
            update(lease) { copy(loading = true, error = null) }
            try {
                val anchor = state.value.anchorId
                val fresh = readHistory(lease, anchor?.let { "?around=${it.encodeURLParameter()}" }.orEmpty())
                if (!isVisible(lease) || !isReadCurrent(lease, epoch)) return
                window = if (anchor == null) window.mergeHead(fresh)
                    else if (window.pages.isEmpty()) AssistantHistoryWindow.around(fresh, anchor)
                    else window.applyMessages(fresh.messages)
                publishHistory(lease, epoch)
                cacheHead(lease, epoch)
                reconcileEntries(lease, epoch)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: AssistantReadFailure) {
                if (isReadCurrent(lease, epoch)) update(lease) { copy(error = nativeError(failure.outcome)) }
            } catch (_: Exception) {
                if (isReadCurrent(lease, epoch)) update(lease) { copy(error = GeneratedStrings.Errors.server) }
            } finally {
                if (isReadCurrent(lease, epoch)) update(lease) { copy(loading = false) }
            }
        }
    }

    suspend fun loadOlder() {
        val lease = currentLease() ?: return
        if (!isVisible(lease) || state.value.loadingOlder) return
        historyMutex.withLock {
            val before = window.before ?: return
            if (!isVisible(lease)) return
            val epoch = readEpoch
            update(lease) { copy(loadingOlder = true, error = null) }
            try {
                val page = readHistory(lease, "?before=${before.encodeURLParameter()}")
                require(page.before == null || page.before != before)
                if (!isVisible(lease) || !isReadCurrent(lease, epoch)) return
                window = window.appendOlder(page)
                publishHistory(lease, epoch)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: AssistantReadFailure) {
                if (isReadCurrent(lease, epoch)) update(lease) { copy(error = nativeError(failure.outcome)) }
            } catch (_: Exception) {
                if (isReadCurrent(lease, epoch)) update(lease) { copy(error = GeneratedStrings.Errors.server) }
            } finally {
                if (isReadCurrent(lease, epoch)) update(lease) { copy(loadingOlder = false) }
            }
        }
    }

    suspend fun loadNewer() {
        val lease = currentLease() ?: return
        if (!isVisible(lease) || state.value.loadingNewer) return
        historyMutex.withLock {
            val after = window.after ?: return
            if (!isVisible(lease)) return
            val epoch = readEpoch
            update(lease) { copy(loadingNewer = true, error = null) }
            try {
                val page = readHistory(lease, "?after=${after.encodeURLParameter()}")
                require(page.after == null || page.after != after)
                if (!isVisible(lease) || !isReadCurrent(lease, epoch)) return
                window = window.prependNewer(page)
                publishHistory(lease, epoch)
                cacheHead(lease, epoch)
            } catch (cancelled: CancellationException) { throw cancelled
            } catch (failure: AssistantReadFailure) {
                if (isReadCurrent(lease, epoch)) update(lease) { copy(error = nativeError(failure.outcome)) }
            } catch (_: Exception) {
                if (isReadCurrent(lease, epoch)) update(lease) { copy(error = GeneratedStrings.Errors.server) }
            } finally {
                if (isReadCurrent(lease, epoch)) update(lease) { copy(loadingNewer = false) }
            }
        }
    }

    /** Topic payloads identify rows; they never supply an assistant answer or queue authority. */
    suspend fun onChange(event: AccountChangeEvent) {
        val lease = currentLease() ?: return
        if (lease.namespace != event.namespace || lease.generation != event.generation) return
        suggestions.onChange(event)
        if (event.topic == "access.changed") {
            invalidateHistory(lease)
            refresh(lease)
            refreshAvailability()
            return
        }
        if (affectedDomains(event.topic) == EventInvalidation.All) {
            invalidateHistory(lease)
            refresh(lease)
            refreshAvailability()
            return
        }
        if (!isVisible(lease) || event.topic != "assistant.changed") return
        val payload = event.data as? JsonObject
        if (payload?.string("stage") == "tool") return
        val ids = listOfNotNull(payload?.string("message_id"), payload?.string("source_message_id")).toMutableSet()
        payload?.string("turn_id")?.let { turn -> window.ownerOfTurn(turn)?.let(ids::add) }
        if (ids.isEmpty()) {
            if (payload?.string("thread_id") == null && payload?.string("session_id") == null) refresh(lease)
        } else refreshMessages(lease, ids)
        interactions.refresh()
    }

    /** Admission takes ownership of this exact snapshot; newer edits remain in the composer. */
    suspend fun send(imageId: String? = state.value.draftImageId): Boolean =
        scope.async(start = CoroutineStart.UNDISPATCHED) { sendOwned(imageId) }.await()

    /** Proposal consent answers the immutable reply without taking over an existing draft. */
    suspend fun sendReply(messageId: String, text: String) = scope.async(start = CoroutineStart.UNDISPATCHED) {
        val lease = currentLease() ?: return@async
        if (state.value.sending || text.isBlank() || !validAssistantIdentity(messageId)) return@async
        val key = newKey()
        activeSends += key
        update(lease) { copy(sending = true, error = null) }
        try {
            if (state.value.anchorId != null || state.value.after != null) latest()
            if (!sessions.isCurrent(lease)) return@async
            val outcome = try {
                submissions.submit(lease, NewSubmission(ASSISTANT_MESSAGES, ASSISTANT_LOOKUP,
                    buildJsonObject { put("text", text.trim()); put("reply_to_message_id", messageId) }
                        .toString().encodeToByteArray(),
                    lease.namespace.userId, found = SubmissionState.ACCEPTED, key = key))
            } catch (failure: UnpersistedSubmissionOutcome) { failure.submission }
            if (outcome.knownUnadmitted()) update(lease) { copy(error = outcome.failureStatus?.let {
                nativeError(HttpOutcome.Failure(it, byteArrayOf()))
            } ?: GeneratedStrings.Errors.server) }
        } catch (cancelled: CancellationException) { throw cancelled
        } catch (_: StaleJournalSession) { return@async
        } catch (_: Exception) { update(lease) { copy(error = GeneratedStrings.Errors.server) }
        } finally {
            activeSends -= key
            update(lease) { copy(sending = false) }
            if (sessions.isCurrent(lease)) reloadEntries(lease)
        }
        refresh(lease)
    }.await()

    // Navigation may cancel the UI caller, but this journal/composer transfer belongs to runtime.
    private suspend fun sendOwned(imageId: String?): Boolean {
        val lease = currentLease() ?: return false
        val current = state.value
        if (!current.draftReady || current.sending || (current.draftText.isBlank() && imageId == null) ||
            (imageId != null && !validConversationImageId(imageId))) return false
        val revision = draftRevision
        val text = current.draftText
        val replyTarget = current.replyTarget
        val key = newKey()
        var handedOff = false
        activeSends += key
        update(lease) { copy(sending = true, error = null) }
        try {
            // The key is persisted before journal insertion, making interrupted handoff recoverable.
            queueDraft(lease, AssistantSavedDraft(text, key, replyTarget, imageId), revision).await().getOrThrow()
            if (!sessions.isCurrent(lease)) return false
            if (current.anchorId != null || current.after != null) latest()
            if (!sessions.isCurrent(lease)) return false
            val request = NewSubmission(
                ASSISTANT_MESSAGES, ASSISTANT_LOOKUP,
                buildJsonObject {
                    put("text", text.trim())
                    imageId?.let { put("image_id", it) }
                    replyTarget?.let { put("reply_to_message_id", it.messageId) }
                }.toString().encodeToByteArray(),
                lease.namespace.userId, draftTarget = ASSISTANT_DRAFT,
                found = SubmissionState.ACCEPTED, key = key,
            )
            val outcome = try {
                submissions.submit(lease, request)
            } catch (failure: UnpersistedSubmissionOutcome) {
                failure.submission
            }
            if (!sessions.isCurrent(lease)) return false
            if (outcome.knownUnadmitted()) update(lease) { copy(error = outcome.failureStatus?.let { nativeError(HttpOutcome.Failure(it, byteArrayOf())) }
                ?: GeneratedStrings.Errors.server) }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: StaleJournalSession) {
            return false
        } catch (_: Exception) {
            update(lease) { copy(error = GeneratedStrings.Errors.server) }
        } finally {
            if (sessions.isCurrent(lease)) {
                // Read persisted evidence, including when saving the admission response failed.
                // Failure to establish ownership blocks a new send until draft hydration recovers.
                try {
                    val owned = submissions.pending(lease).firstOrNull { it.key == key }
                    if (owned != null && !owned.knownUnadmitted()) {
                        handedOff = true
                        handoff(lease, revision, imageId)
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    hydrated = false
                    update(lease) { copy(draftReady = false, draftError = GeneratedStrings.Errors.server) }
                }
                activeSends -= key
                update(lease) { copy(sending = false) }
                reloadEntries(lease)
            }
        }
        refresh(lease)
        return handedOff
    }

    suspend fun check(key: String) = deliveryAction(key, retry = false)

    /** This button is the only path to coordinator.retry; reconnect and resync use GET only. */
    suspend fun retry(key: String) = deliveryAction(key, retry = true)

    suspend fun sendNow(messageId: String) {
        val lease = currentLease() ?: return
        val queue = AssistantQueue.from(state.value.messages)
        val running = queue.running ?: return
        if (messageId !in queue.movableIds || state.value.moving.isNotEmpty() || state.value.sending) return
        update(lease) { copy(moving = moving + messageId, error = null) }
        try {
            val request = AssistantMoveRequest(running.id, requireNotNull(running.runGeneration), messageId)
            val body = NativeJson.encodeToString(request).encodeToByteArray()
            // An uncertain prior move retains its original key and exact generation/body.
            val prior = submissions.pending(lease).lastOrNull {
                it.endpoint == ASSISTANT_MOVES && it.body.contentEquals(body) && it.state in setOf(
                    SubmissionState.PREPARED, SubmissionState.SENDING, SubmissionState.UNCERTAIN,
                )
            }
            val outcome = when {
                prior == null -> submissions.submit(lease, NewSubmission(
                    ASSISTANT_MOVES, "$ASSISTANT_MOVES/by-key/", body, lease.namespace.userId,
                    key = newKey(),
                ))
                else -> submissions.retry(lease, prior.key)
            }
            if (outcome.state != SubmissionState.RESOLVED && outcome.state != SubmissionState.ACCEPTED) {
                update(lease) { copy(error = GeneratedStrings.Errors.network) }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            update(lease) { copy(error = GeneratedStrings.Errors.server) }
        } finally {
            update(lease) { copy(moving = moving - messageId) }
        }
        refreshMessages(lease, setOf(messageId, running.id))
    }

    private suspend fun deliveryAction(key: String, retry: Boolean) {
        val lease = currentLease() ?: return
        if (key in state.value.checking || key in activeSends) return
        update(lease) { copy(checking = checking + key, error = null) }
        try {
            if (retry) submissions.retry(lease, key) else submissions.check(lease, key)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            update(lease) { copy(error = GeneratedStrings.Errors.network) }
        } finally {
            if (sessions.isCurrent(lease)) {
                update(lease) { copy(checking = checking - key) }
                reloadEntries(lease)
            }
        }
        refresh(lease)
    }

    private fun bind(lease: SessionLease?) {
        if (boundLease === lease) return
        cancelActivity()
        boundLease = lease
        hydrated = false
        readEpoch++
        availabilityEpoch++
        cacheInvalid = false
        window = AssistantHistoryWindow()
        entries = emptyList()
        activeSends.clear()
        confirmed.clear()
        draftRevision = 0
        mutableState.value = AssistantState(namespace = lease?.namespace)
        interactions.updateMessages(emptyList())
    }

    private suspend fun hydrate(lease: SessionLease) = hydrationMutex.withLock {
        if (hydrated || !sessions.isCurrent(lease) || boundLease !== lease) return
        try {
            draftWrite?.join()
            val draft = drafts.read(lease, ASSISTANT_DRAFT)
            val saved = draft?.let { NativeJson.decodeFromString<AssistantSavedDraft>(it.json.decodeToString()) }
                ?: AssistantSavedDraft("")
            require(saved.imageId == null || validConversationImageId(saved.imageId))
            val pending = submissions.pending(lease)
            if (!sessions.isCurrent(lease)) return
            draftRevision = draft?.revision ?: 0
            val alreadyJournaled = pending.any { it.key == saved.submissionKey && !it.knownUnadmitted() }
            update(lease) { copy(draftText = if (alreadyJournaled) "" else saved.text,
                draftImageId = if (alreadyJournaled) null else saved.imageId,
                replyTarget = if (alreadyJournaled) null else saved.replyTarget,
                draftReady = true, draftError = null) }
            if (alreadyJournaled) {
                draftRevision++
                queueDraft(lease, AssistantSavedDraft(""), draftRevision).await().getOrThrow()
            }
            entries = pending
            publishPending(lease)
            hydrated = true
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            update(lease) { copy(draftReady = false, draftError = GeneratedStrings.Errors.server) }
        }
        // Cache failure never prevents the authoritative read or erases a usable composer.
        try {
            val epoch = readEpoch
            val cached = if (cacheInvalid) null else cache.read(lease.namespace)
            if (cached != null && isReadCurrent(lease, epoch) && !cacheInvalid && window.messages.isEmpty()) {
                window = AssistantHistoryWindow(listOf(cached))
                publishHistory(lease, epoch)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            update(lease) { copy(error = GeneratedStrings.Errors.server) }
        }
    }

    private fun queueDraft(lease: SessionLease, saved: AssistantSavedDraft, revision: Long): Deferred<Result<Unit>> {
        val previous = draftWrite
        val write = scope.async(start = CoroutineStart.UNDISPATCHED) {
            previous?.join()
            try {
                drafts.save(lease, ASSISTANT_DRAFT, NativeJson.encodeToString(saved).encodeToByteArray(), revision)
                update(lease) { copy(draftError = null) }
                Result.success(Unit)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                update(lease) { copy(draftError = GeneratedStrings.Errors.server) }
                Result.failure(failure)
            }
        }
        draftWrite = write
        return write
    }

    private suspend fun handoff(lease: SessionLease, revision: Long, imageId: String?) {
        if (!sessions.isCurrent(lease)) return
        if (revision == draftRevision) {
            draftRevision++
            update(lease) { copy(draftText = "", replyTarget = null, draftImageId = null) }
            queueDraft(lease, AssistantSavedDraft(""), draftRevision).await().getOrThrow()
        } else if (imageId != null && state.value.draftImageId == imageId) {
            // Caption edits keep their text, but a sent image is no longer the new draft's photo.
            draftRevision++
            update(lease) { copy(draftImageId = null) }
            queueDraft(lease, AssistantSavedDraft(state.value.draftText, replyTarget = state.value.replyTarget), draftRevision)
                .await().getOrThrow()
        }
    }

    private suspend fun refreshMessages(lease: SessionLease, ids: Set<String>) {
        if (!isVisible(lease)) return
        historyMutex.withLock {
            if (!isVisible(lease)) return
            val epoch = readEpoch
            try {
                val fetched = mutableListOf<AssistantMessage>()
                val missing = mutableListOf<String>()
                for (id in ids) {
                    if (!isReadCurrent(lease, epoch)) return
                    when (val response = http.request("GET", "$ASSISTANT_MESSAGES?message_id=${id.encodeURLParameter()}", lease = lease)) {
                        is HttpOutcome.Success -> fetched += decodeAssistantHistory(response.body).messages
                        is HttpOutcome.Failure -> if (response.status == 404) missing += id else throw AssistantReadFailure(response)
                        else -> throw AssistantReadFailure(response)
                    }
                }
                if (!isVisible(lease) || !isReadCurrent(lease, epoch)) return
                window = window.applyMessages(fetched, missing)
                publishHistory(lease, epoch)
                cacheHead(lease, epoch)
                reconcileEntries(lease, epoch)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: AssistantReadFailure) {
                if (isReadCurrent(lease, epoch)) update(lease) { copy(error = nativeError(failure.outcome)) }
            } catch (_: Exception) {
                if (isReadCurrent(lease, epoch)) update(lease) { copy(error = GeneratedStrings.Errors.server) }
            }
        }
    }

    private suspend fun readHistory(lease: SessionLease, suffix: String = ""): AssistantHistory =
        when (val response = http.request("GET", ASSISTANT_MESSAGES + suffix, lease = lease)) {
            is HttpOutcome.Success -> decodeAssistantHistory(response.body)
            else -> throw AssistantReadFailure(response)
        }

    private suspend fun cacheHead(lease: SessionLease, epoch: Long) {
        if (!isReadCurrent(lease, epoch)) return
        val head = window.cacheHead ?: return
        sessions.withLease(lease) {
            if (isReadCurrent(lease, epoch) && !cacheInvalid) cache.write(lease.namespace, head)
        }
    }

    private suspend fun reloadEntries(lease: SessionLease) {
        try {
            val all = submissions.pending(lease)
            if (!sessions.isCurrent(lease)) return
            entries = all
            publishPending(lease)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            update(lease) { copy(error = GeneratedStrings.Errors.server) }
        }
    }

    /** A receipt disappears only after history owns its identity AND the saved composer lets go. */
    private suspend fun reconcileEntries(lease: SessionLease, epoch: Long) {
        reloadEntries(lease)
        draftWrite?.join()
        if (!isReadCurrent(lease, epoch)) return
        val saved = drafts.read(lease, ASSISTANT_DRAFT)?.let {
            NativeJson.decodeFromString<AssistantSavedDraft>(it.json.decodeToString())
        }
        if (!isReadCurrent(lease, epoch)) return
        val known = window.messages.map { it.id }.toSet()
        for (entry in entries) {
            if (entry.endpoint == ASSISTANT_MESSAGES) {
                val id = entry.admittedMessageId() ?: continue
                var durable = id in known || id in confirmed
                if (!durable) {
                    val response = http.request("GET", "$ASSISTANT_MESSAGES?message_id=${id.encodeURLParameter()}", lease = lease)
                    if (response is HttpOutcome.Success) {
                        durable = decodeAssistantHistory(response.body).messages.any { it.id == id }
                    }
                }
                if (!isReadCurrent(lease, epoch)) return
                if (durable) confirmed += id
                if (durable && saved?.submissionKey != entry.key) submissions.acknowledge(lease, entry.key)
            } else if (entry.endpoint == ASSISTANT_MOVES && entry.state in setOf(SubmissionState.ACCEPTED, SubmissionState.RESOLVED) &&
                window.messages.any { it.clarificationKey == entry.key }) {
                submissions.acknowledge(lease, entry.key)
            }
        }
        reloadEntries(lease)
    }

    private fun publishHistory(lease: SessionLease, epoch: Long) {
        if (!isReadCurrent(lease, epoch)) return
        val messages = window.messages
        update(lease) { copy(messages = messages, before = window.before, after = window.after,
            movableIds = AssistantQueue.from(messages).movableIds, follow = follow.updated(messages)) }
        interactions.updateMessages(messages)
        publishPending(lease)
        reconcileActivity(lease)
    }

    private fun publishPending(lease: SessionLease) {
        val known = state.value.messages.map { it.id }.toSet() + confirmed
        update(lease) { copy(pending = entries.asSequence().filter { it.endpoint == ASSISTANT_MESSAGES }
            .filter { it.admittedMessageId()?.let(known::contains) != true }
            .sortedBy { it.createdAtMillis }.map { it.pendingSend(it.key in activeSends, clockMs()) }.toList()) }
    }

    private fun reconcileActivity(lease: SessionLease) {
        if (!isVisible(lease)) return
        val running = state.value.messages.lastOrNull { it.pending && !it.queued }
        val target = running?.let { it.id to it.runGeneration }
        if (target == activityTarget) return
        cancelActivity()
        if (target == null) return
        activityTarget = target
        val epoch = readEpoch
        update(lease) { copy(activityMessageId = target.first, activity = null) }
        activityJob = scope.launch {
            activityStream.collect(lease, target.first, onState = { activity ->
                if (isVisible(lease) && isReadCurrent(lease, epoch) && activityTarget == target) update(lease) { copy(activity = activity) }
            }, onTerminal = {
                // The durable row ends this stream; its read must not cancel itself mid-cache write.
                if (isVisible(lease) && isReadCurrent(lease, epoch) && activityTarget == target) scope.launch {
                    if (isReadCurrent(lease, epoch)) refreshMessages(lease, setOf(target.first))
                }
            })
        }
    }

    private fun cancelActivity() {
        activityJob?.cancel()
        activityJob = null
        activityTarget = null
        mutableState.update { it.copy(activityMessageId = null, activity = null) }
    }

    private fun currentLease(): SessionLease? = sessions.lease()?.takeIf { boundLease === it }
    private fun isReadCurrent(lease: SessionLease, epoch: Long): Boolean =
        readEpoch == epoch && sessions.isCurrent(lease) && boundLease === lease
    private fun isVisible(lease: SessionLease): Boolean = visible.value.active && sessions.isCurrent(lease) && boundLease === lease
    private fun update(lease: SessionLease, change: AssistantState.() -> AssistantState) {
        if (sessions.isCurrent(lease) && boundLease === lease) mutableState.update(change)
    }
}

private class AssistantReadFailure(val outcome: HttpOutcome) : Exception()
private fun JsonObject.string(key: String): String? = (get(key) as? JsonPrimitive)?.takeIf { it.isString }?.content

@OptIn(ExperimentalUuidApi::class)
private fun newKey(): String = Uuid.random().toString()
