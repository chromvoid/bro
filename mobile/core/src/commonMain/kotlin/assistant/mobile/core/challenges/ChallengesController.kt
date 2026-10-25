package assistant.mobile.core.challenges

import assistant.mobile.core.*
import assistant.mobile.core.circle.CircleQuery
import assistant.mobile.core.circle.circleServerError
import assistant.mobile.core.journal.*
import io.ktor.http.encodeURLParameter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Clock
import kotlin.time.Instant

/** Rules and scores are authoritative reads. Unknown writes only reread: these APIs have no lookup. */
class ChallengesController(private val scope: CoroutineScope, private val sessions: SessionManager,
    private val http: NativeHttpClient, private val submissions: SubmissionCoordinator,
    private val clockMs: () -> Long = { Clock.System.now().toEpochMilliseconds() }) {
    private val mutableState = MutableStateFlow<ChallengesState?>(null)
    val state: StateFlow<ChallengesState?> = mutableState.asStateFlow()
    private var boundLease: SessionLease? = null
    private val defaultVisibilityOwner = Any()
    private val visibilityOwners = mutableSetOf<Any>()
    private val visibility = MutableStateFlow(ChallengeVisibility(false, 0))
    private var generation = 0L
    private var readEpoch = 0L
    private var readAllowed = false
    private val reads = Mutex()
    private val writes = Mutex()
    private val owner = scope.launch {
        combine(sessions.leases, sessions.identity, visibility) { lease, _, visible -> lease to visible }.collectLatest { (lease, visible) ->
            bind(lease)
            if (lease != null && visible.active) refresh()
        }
    }

    fun activate(spaceId: String? = null, challengeId: String? = null, visibilityOwner: Any = defaultVisibilityOwner) {
        require((spaceId == null) != (challengeId == null))
        require(spaceId == null || spaceId.isNotBlank()); require(challengeId == null || challengeId.isNotBlank())
        visibilityOwners.add(visibilityOwner)
        bind(sessions.lease())
        if (state.value?.spaceId != spaceId || state.value?.challengeId != challengeId) {
            generation++
            mutableState.update { it?.copy(spaceId = spaceId, challengeId = challengeId, challenges = CircleQuery(),
                board = CircleQuery(), gone = false, error = null, serverError = null, pending = emptyList()) }
        }
        visibility.update { ChallengeVisibility(true, it.epoch + 1) }
    }
    fun deactivate(visibilityOwner: Any = defaultVisibilityOwner) {
        if (!visibilityOwners.remove(visibilityOwner) || visibilityOwners.isNotEmpty()) return
        visibility.update { it.copy(active = false) }
    }
    fun close() {
        owner.cancel(); visibilityOwners.clear(); visibility.value = ChallengeVisibility(false, 0)
        boundLease = null; mutableState.value = null; generation++
    }
    fun clearError() { mutableState.update { it?.copy(error = null, serverError = null) } }
    fun tick() {
        if (!visibility.value.active) return
        val before = state.value ?: return
        val now = clockMs()
        mutableState.update { it?.copy(nowMillis = now, today = challengeToday(it.timezone, now)) }
        if (before.joinOpen && state.value?.joinOpen == false) scope.launch { refresh() }
    }
    suspend fun refresh() {
        if (!visibility.value.active) return
        val lease = sessions.lease() ?: return
        bind(lease)
        reads.withLock { refreshReads(lease) }
        if (owned(lease)) try { refreshPending(lease) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { change(lease) { copy(error = GeneratedStrings.Assistant.draftStorageError) } }
    }
    suspend fun invalidate(invalidation: EventInvalidation) {
        if (invalidation is EventInvalidation.Domains && invalidation.values.none {
            it in setOf(EventDomain.Challenges, EventDomain.Community, EventDomain.Shared)
        }) return
        readEpoch++
        // Access may have disappeared. Remove the projection before awaiting the recheck.
        mutableState.update { it?.copy(challenges = CircleQuery(loading = it.spaceId != null), board = CircleQuery(loading = it.challengeId != null)) }
        refresh()
    }
    private suspend fun refreshReads(lease: SessionLease) {
        if (!owned(lease)) return
        if (!readAllowed) return
        val selected = generation; val epoch = readEpoch
        val current = state.value ?: return
        tickTime(lease)
        current.spaceId?.let { space ->
            change(lease) { copy(challenges = challenges.copy(loading = true, error = null, serverError = null)) }
            val query = read(lease, "$CHALLENGES_PATH?space_id=${space.encodeURLParameter()}", ::decodeChallenges)
            if (!selected(lease, selected, epoch)) return
            if (query.gone) { gone(lease); return }
            change(lease) { copy(challenges = if (query.denied) query.value else retain(challenges, query.value), gone = false) }
        }
        current.challengeId?.let { id ->
            change(lease) { copy(board = board.copy(loading = true, error = null, serverError = null)) }
            val query = read(lease, challengePath(id)) { decodeChallengeBoard(it, id) }
            if (!selected(lease, selected, epoch)) return
            if (query.gone) { gone(lease); return }
            change(lease) { copy(board = if (query.denied) query.value else retain(board, query.value), gone = false) }
        }
    }

    suspend fun create(draft: ChallengeDraft): String? {
        val current = state.value ?: return null
        val space = current.spaceId ?: return null
        if (!current.canCreate || current.gone) return null
        val error = challengeDraftError(draft, challengeToday(current.timezone, clockMs()))
        if (error != null) { mutableState.update { it?.copy(error = error, serverError = null) }; return null }
        return mutate(CHALLENGES_PATH, createChallengeBody(space, draft), "space:$space")?.let(::challengeCreatedId)
    }
    suspend fun join(): Boolean {
        val current = state.value ?: return false
        val id = current.challengeId ?: return false
        val board = current.board.data ?: return false
        if (current.gone || board.participation != null || !joinOpen(board.challenge, clockMs())) return false
        return mutate("${challengePath(id)}/join", "{}".encodeToByteArray(), "challenge:$id") != null
    }
    suspend fun leave(): Boolean {
        val current = state.value ?: return false
        val id = current.challengeId ?: return false
        if (current.gone || current.board.data?.participation == null) return false
        return mutate("${challengePath(id)}/leave", "{}".encodeToByteArray(), "challenge:$id") != null
    }
    /** Deliberately no retry method: rechecking a missing receipt never emits a second POST. */
    suspend fun check(key: String) {
        if (state.value?.pending?.none { it.key == key } != false) return
        refresh()
    }
    /** The person inspected the refreshed projection and explicitly stopped tracking this write. */
    suspend fun forget(key: String) = writes.withLock {
        val lease = sessions.lease() ?: return@withLock
        if (!owned(lease)) return@withLock
        val current = state.value ?: return@withLock
        if (current.gone || current.spaceId != null && current.challenges.data == null || current.challengeId != null && current.board.data == null) return@withLock
        val entry = current.pending.firstOrNull { it.key == key } ?: return@withLock
        if (entry.state in setOf(SubmissionState.ACCEPTED, SubmissionState.RESOLVED)) submissions.acknowledge(lease, key)
        else if (entry.state != SubmissionState.SENDING) submissions.abandonUnkeyed(lease, key)
        refreshPending(lease)
    }
    private suspend fun mutate(path: String, bytes: ByteArray, audience: String): ByteArray? {
        if (!writes.tryLock()) return null
        try {
            val lease = sessions.lease() ?: return null
            if (!owned(lease) || !visibility.value.active || state.value?.gone == true) return null
            refreshPending(lease)
            if (state.value?.pending?.isNotEmpty() == true) return null
            val selection = generation
            change(lease) { copy(busy = true, error = null, serverError = null) }
            try {
                val result = submissions.submit(lease, NewSubmission(path, null, bytes, audience))
                if (!owned(lease)) return null
                val response = if (result.state == SubmissionState.RESOLVED) result.response else null
                if (response != null) {
                    validateChallengeSubmissionReceipt(result, response)
                    submissions.acknowledge(lease, result.key)
                } else if (result.state == SubmissionState.REJECTED) {
                    change(lease) { copy(error = submissionError(result), serverError = submissionServerError(result)) }
                    submissions.discardRefused(lease, result.key)
                } else if (result.failureStatus == 409) {
                    change(lease) { copy(error = GeneratedStrings.Circle.changed, serverError = submissionServerError(result)) }
                }
                reads.withLock { refreshReads(lease) }
                refreshPending(lease)
                return response?.takeIf { owned(lease) && generation == selection }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { change(lease) { copy(error = GeneratedStrings.Assistant.draftStorageError) }; return null }
            finally { change(lease) { copy(busy = false) } }
        } finally { writes.unlock() }
    }
    private suspend fun refreshPending(lease: SessionLease) {
        val current = state.value ?: return
        val entries = submissions.pending(lease).filter { entry -> isChallengeWriteEndpoint(entry.endpoint) && when {
            current.spaceId != null -> entry.endpoint == CHALLENGES_PATH && entry.audience == "space:${current.spaceId}"
            current.challengeId != null -> entry.audience == "challenge:${current.challengeId}"
            else -> false
        } }
        if (owned(lease) && state.value?.let { it.spaceId == current.spaceId && it.challengeId == current.challengeId } == true)
            change(lease) { copy(pending = entries) }
    }
    private suspend fun <T> read(lease: SessionLease, path: String, decode: (ByteArray) -> T): ChallengeRead<T> {
        val outcome = http.request("GET", path, lease = lease)
        if (!owned(lease)) return ChallengeRead(CircleQuery())
        if (outcome is HttpOutcome.Failure && outcome.status == 404) return ChallengeRead(CircleQuery(), gone = true)
        if (outcome == HttpOutcome.Forbidden) return ChallengeRead(CircleQuery(error = GeneratedStrings.Errors.forbidden), denied = true)
        if (outcome is HttpOutcome.Success) return try { ChallengeRead(CircleQuery(data = decode(outcome.body))) }
            catch (_: Exception) { ChallengeRead(CircleQuery(error = nativeError(HttpOutcome.InvalidResponse))) }
        return ChallengeRead(CircleQuery(error = nativeError(outcome), serverError = circleServerError(outcome)))
    }
    private fun bind(lease: SessionLease?) {
        val identity = sessions.identity.value
        val allowed = "challenges.read" in identity?.scopes.orEmpty()
        if (readAllowed && !allowed) readEpoch++
        readAllowed = allowed
        if (boundLease !== lease) {
            boundLease = lease; generation++; readEpoch++
            mutableState.value = lease?.let {
                val next = ChallengesState(it.namespace, challengeToday(identity?.timezone ?: "UTC", clockMs()),
                    identity?.timezone ?: "UTC", clockMs(), canCreate = "challenges.write" in identity?.scopes.orEmpty())
                if (allowed) next else next.copy(challenges = CircleQuery(error = GeneratedStrings.Errors.forbidden), board = CircleQuery(error = GeneratedStrings.Errors.forbidden))
            }
        } else mutableState.update { current -> current?.let {
            val next = it.copy(canCreate = "challenges.write" in identity?.scopes.orEmpty(), timezone = identity?.timezone ?: "UTC")
            if (allowed) next else next.copy(
                challenges = CircleQuery(error = GeneratedStrings.Errors.forbidden), board = CircleQuery(error = GeneratedStrings.Errors.forbidden))
        } }
    }
    private fun tickTime(lease: SessionLease) = change(lease) { copy(nowMillis = clockMs(), today = challengeToday(timezone, clockMs())) }
    private fun owned(lease: SessionLease) = boundLease === lease && sessions.isCurrent(lease)
    private fun selected(lease: SessionLease, value: Long, epoch: Long) = owned(lease) && generation == value && readEpoch == epoch
    private inline fun change(lease: SessionLease, block: ChallengesState.() -> ChallengesState) { if (owned(lease)) mutableState.update { it?.block() } }
    private fun gone(lease: SessionLease) { readEpoch++; change(lease) { copy(gone = true, challenges = CircleQuery(), board = CircleQuery(), pending = emptyList()) } }
    private fun <T> retain(previous: CircleQuery<T>, next: CircleQuery<T>) = if (next.data != null || previous.data == null) next else next.copy(data = previous.data, stale = true)
    private fun submissionError(entry: Submission): LocalizedString = when (entry.failureStatus) {
        401 -> GeneratedStrings.Errors.session; 403 -> GeneratedStrings.Errors.forbidden
        409 -> GeneratedStrings.Circle.changed; 429 -> GeneratedStrings.Errors.limit
        400, 404, 413, 415, 422 -> GeneratedStrings.Errors.invalid
        else -> GeneratedStrings.Errors.unknown
    }
    private fun submissionServerError(entry: Submission) = entry.response?.let { circleServerError(HttpOutcome.Failure(entry.failureStatus ?: 500, it)) }
}
private data class ChallengeVisibility(val active: Boolean, val epoch: Long)
private data class ChallengeRead<T>(val value: CircleQuery<T>, val gone: Boolean = false, val denied: Boolean = false)
private fun challengeToday(timezone: String, nowMillis: Long) = Instant.fromEpochMilliseconds(nowMillis).toLocalDateTime(TimeZone.of(timezone)).date.toString()
