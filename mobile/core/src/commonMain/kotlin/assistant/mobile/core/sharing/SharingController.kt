package assistant.mobile.core.sharing

import assistant.mobile.core.*
import assistant.mobile.core.circle.*
import assistant.mobile.core.journal.*
import assistant.mobile.core.tasks.decodeTaskLists
import assistant.mobile.core.today.todayIn
import io.ktor.http.encodeURLParameter
import io.ktor.http.encodeURLPathPart
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.datetime.LocalDate
import kotlinx.serialization.json.*
import kotlin.time.Clock

/** Owner grants and ephemeral projections share revisions, but never share persisted data. */
class SharingController(
    scope: CoroutineScope,
    private val sessions: SessionManager,
    private val http: NativeHttpClient,
    private val submissions: SubmissionCoordinator,
    private val clockMs: () -> Long = { Clock.System.now().toEpochMilliseconds() },
) {
    private val mutableState = MutableStateFlow<SharingState?>(null)
    val state: StateFlow<SharingState?> = mutableState.asStateFlow()
    private val defaultOwner = Any()
    private val owners = mutableSetOf<Any>()
    private val visibility = MutableStateFlow(SharingVisibility(false, 0))
    private var boundLease: SessionLease? = null
    private var generation = 0L
    private var readEpoch = 0L
    private var searchEpoch = 0L
    private var requestedDay: String? = null
    private val reads = Mutex()
    private val writes = Mutex()
    private val owner = scope.launch {
        combine(sessions.leases, sessions.identity, visibility) { lease, _, visible -> lease to visible }.collectLatest { (lease, visible) ->
            bind(lease)
            if (lease != null && visible.active) refresh()
        }
    }

    fun activate(surface: SharingSurface = SharingSurface.Settings, sharedId: String? = null, listId: String? = null,
        day: String? = null, owner: Any = defaultOwner) {
        require(sharedId == null || sharedId.isNotBlank()); require(listId == null || listId.isNotBlank())
        day?.let(LocalDate::parse)
        owners += owner
        bind(sessions.lease())
        val before = state.value
        if (before?.surface != surface || before.sharedId != sharedId || before.listId != listId || requestedDay != day) {
            generation++; readEpoch++; searchEpoch++
            mutableState.update { it?.copy(surface = surface, sharedId = sharedId, listId = listId, day = day,
                projection = CircleQuery(), grants = CircleQuery(), members = CircleQuery(), gone = false,
                person = null, error = null, serverError = null, pending = emptyList()) }
        }
        requestedDay = day
        visibility.update { SharingVisibility(true, it.epoch + 1) }
    }

    fun deactivate(owner: Any = defaultOwner) {
        if (!owners.remove(owner) || owners.isNotEmpty()) return
        visibility.update { it.copy(active = false) }
        // Another person's data is never retained after leaving the screen, including back stack.
        readEpoch++; searchEpoch++
        mutableState.update { it?.copy(projection = CircleQuery(), person = null) }
    }
    fun close() { owner.cancel(); owners.clear(); visibility.update { it.copy(active = false) }; generation++; readEpoch++; boundLease = null; mutableState.value = null }
    fun clearPerson() { searchEpoch++; mutableState.update { it?.copy(person = null, error = null, serverError = null) } }

    suspend fun selectDay(day: String?) {
        day?.let(LocalDate::parse)
        readEpoch++
        mutableState.update { it?.copy(day = day, projection = CircleQuery(), gone = false) }
        refresh()
    }

    suspend fun invalidate(invalidation: EventInvalidation) {
        if (invalidation is EventInvalidation.Domains && invalidation.values.none {
            it in setOf(EventDomain.Shared, EventDomain.Tasks, EventDomain.Community)
        }) return
        readEpoch++
        mutableState.update { it?.copy(projection = CircleQuery()) }
        refresh()
    }

    suspend fun refresh() {
        if (!visibility.value.active) return
        val lease = sessions.lease() ?: return
        bind(lease)
        // Clear before the first suspension: a downgrade must never leave stale detail on screen.
        val epoch = ++readEpoch
        change(lease) { copy(projection = CircleQuery(loading = surface == SharingSurface.Shared && sharedId != null)) }
        reads.withLock {
            val selected = generation
            if (!currentRead(lease, selected, epoch)) return@withLock
            when (state.value?.surface) {
                SharingSurface.Settings -> refreshSettings(lease, selected, epoch)
                SharingSurface.Shared -> refreshShared(lease, selected, epoch)
                SharingSurface.ListGrants -> refreshList(lease, selected, epoch)
                null -> Unit
            }
            if (currentRead(lease, selected, epoch)) refreshPending(lease)
        }
    }

    private suspend fun refreshSettings(lease: SessionLease, selected: Long, epoch: Long) {
        change(lease) { copy(overview = overview.copy(loading = true), spaces = spaces.copy(loading = true),
            lists = lists.copy(loading = true), relationships = relationships.copy(loading = true)) }
        val overview = retained(state.value?.overview, read(lease, "/v1/me/sharing", ::decodeSharingOverview))
        if (!currentRead(lease, selected, epoch)) return
        change(lease) { copy(overview = overview) }
        val spaces = retained(state.value?.spaces, read(lease, "/v1/spaces", ::decodeCircleSpaces))
        if (!currentRead(lease, selected, epoch)) return
        change(lease) { copy(spaces = spaces) }
        if ("tasks.read" in state.value?.scopes.orEmpty()) {
            val lists = retained(state.value?.lists, read(lease, "/v1/tasks/lists", ::decodeTaskLists))
            if (!currentRead(lease, selected, epoch)) return
            change(lease) { copy(lists = lists) }
        } else change(lease) { copy(lists = CircleQuery()) }
        val relationships = retained(state.value?.relationships, read(lease, "/v1/relationships", ::decodeCircleRelationships))
        if (currentRead(lease, selected, epoch)) change(lease) { copy(relationships = relationships) }
    }

    private suspend fun refreshShared(lease: SessionLease, selected: Long, epoch: Long) {
        change(lease) { copy(overview = CircleQuery(loading = true)) }
        val overview = read(lease, "/v1/me/sharing", ::decodeSharingOverview)
        if (!currentRead(lease, selected, epoch)) return
        change(lease) { copy(overview = overview) }
        val current = state.value ?: return
        val id = current.sharedId ?: return
        if (overview.data == null) { change(lease) { copy(projection = CircleQuery()) }; return }
        val section = current.selected
        if (section == null) { change(lease) { copy(gone = true, projection = CircleQuery()) }; return }
        val path = "/v1/shared/${id.encodeURLPathPart()}" + current.day?.let { "?date=${it.encodeURLParameter()}" }.orEmpty()
        val outcome = http.request("GET", path, lease = lease)
        if (!currentRead(lease, selected, epoch)) return
        if (outcome is HttpOutcome.Failure && outcome.status == 404) {
            change(lease) { copy(gone = true, projection = CircleQuery()) }; return
        }
        val projection = result(outcome) { decodeSharedProjection(it, section.name) }
        change(lease) { copy(gone = false, projection = projection) }
    }

    private suspend fun refreshList(lease: SessionLease, selected: Long, epoch: Long) {
        val id = state.value?.listId ?: return
        change(lease) { copy(lists = lists.copy(loading = true), grants = grants.copy(loading = true), members = members.copy(loading = true)) }
        val lists = read(lease, "/v1/tasks/lists", ::decodeTaskLists)
        if (!currentRead(lease, selected, epoch)) return
        change(lease) { copy(lists = lists) }
        val list = state.value?.list
        if (lists.data != null && (list == null || list.spaceId == null || list.effective != "manage")) {
            change(lease) { copy(gone = true, grants = CircleQuery(), members = CircleQuery()) }; return
        }
        if (list == null) { change(lease) { copy(grants = CircleQuery(), members = CircleQuery()) }; return }
        val outcome = http.request("GET", resourcePath(id, "/grants"), lease = lease)
        if (!currentRead(lease, selected, epoch)) return
        if (outcome is HttpOutcome.Failure && outcome.status == 404) {
            change(lease) { copy(gone = true, grants = CircleQuery(), members = CircleQuery()) }; return
        }
        change(lease) { copy(gone = false, grants = result(outcome) { decodeListGrants(it, id) }) }
        val members = read(lease, "/v1/spaces/${list.spaceId!!.encodeURLPathPart()}/members", ::decodeCircleMembers)
        if (currentRead(lease, selected, epoch)) change(lease) { copy(members = members) }
    }

    suspend fun searchPerson(login: String): CirclePerson? {
        val exact = login.trim().removePrefix("@")
        val lease = sessions.lease() ?: return null
        bind(lease)
        if (exact.isBlank()) { change(lease) { copy(error = GeneratedStrings.Access.loginRequired) }; return null }
        val search = ++searchEpoch
        change(lease) { copy(searching = true, person = null, error = null, serverError = null) }
        try {
            val outcome = http.request("GET", "/v1/relationships?login=${exact.encodeURLParameter()}", lease = lease)
            if (!owned(lease) || search != searchEpoch) return null
            if (outcome !is HttpOutcome.Success) { failure(lease, outcome); return null }
            val person = try { decodeCirclePerson(outcome.body) } catch (_: Exception) { failure(lease, HttpOutcome.InvalidResponse); return null }
            when {
                person == null -> change(lease) { copy(error = GeneratedStrings.Access.notFound) }
                person.id == lease.namespace.userId -> change(lease) { copy(error = GeneratedStrings.Access.notYourself) }
                else -> { change(lease) { copy(person = person) }; return person }
            }
            return null
        } finally { if (search == searchEpoch) change(lease) { copy(searching = false) } }
    }

    suspend fun grant(sectionId: String, userId: String, permission: SharingPermission, revision: Long): Boolean {
        val current = state.value ?: return false
        if (current.surface != SharingSurface.Settings || current.namespace.userId == userId ||
            current.overview.data?.sections?.none { it.id == sectionId && it.ownerId == current.namespace.userId } != false) return false
        return resourceAction(sectionId, "/grants", buildJsonObject { put("user_id", userId); put("permission", permission.wire); put("revision", revision) }, revision)
    }
    suspend fun revoke(sectionId: String, userId: String, revision: Long): Boolean {
        val current = state.value ?: return false
        if (current.surface != SharingSurface.Settings || current.overview.data?.grants?.none { it.resourceId == sectionId && it.userId == userId } != false) return false
        return resourceAction(sectionId, "/grants", buildJsonObject { put("user_id", userId); put("permission", JsonNull); put("revision", revision) }, revision)
    }
    suspend fun saveDefault(memberAccess: String, revision: Long): Boolean {
        val current = state.value ?: return false
        if (memberAccess !in setOf("view", "edit") || current.surface != SharingSurface.ListGrants || current.list?.effective != "manage") return false
        return resourceAction(current.listId ?: return false, "", buildJsonObject { put("member_access", memberAccess); put("revision", revision) }, revision)
    }
    suspend fun grantList(userId: String, permission: ListPermission?, revision: Long): Boolean {
        val current = state.value ?: return false
        if (current.surface != SharingSurface.ListGrants || current.list?.effective != "manage") return false
        if (permission != null && current.grantable.none { it.userId == userId }) return false
        if (permission == null && current.grants.data?.grants?.none { it.userId == userId } != false) return false
        return resourceAction(current.listId ?: return false, "/grants", buildJsonObject {
            put("user_id", userId); put("permission", permission?.let { JsonPrimitive(it.wire) } ?: JsonNull); put("revision", revision)
        }, revision)
    }

    /** The routes expose no receipt lookup. Recovery only re-reads, never sends another POST. */
    suspend fun checkPending(key: String) {
        if (state.value?.pending?.none { it.key == key } != false) return
        refresh()
    }
    suspend fun forgetPending(key: String) = writes.withLock {
        val lease = sessions.lease() ?: return@withLock
        if (!owned(lease) || state.value?.pending?.none { it.key == key } != false) return@withLock
        val current = state.value ?: return@withLock
        val inspected = when (current.surface) {
            SharingSurface.Settings -> current.overview.let { it.data != null && !it.loading && it.error == null && it.serverError == null && !it.stale }
            SharingSurface.ListGrants -> !current.gone && current.grants.let { it.data != null && !it.loading && it.error == null && it.serverError == null && !it.stale }
            SharingSurface.Shared -> false
        }
        if (!inspected) return@withLock
        val entry = submissions.pending(lease).firstOrNull { it.key == key && isSharingResourceEndpoint(it.endpoint) } ?: return@withLock
        if (entry.state in setOf(SubmissionState.RESOLVED, SubmissionState.ACCEPTED)) submissions.acknowledge(lease, key)
        else if (entry.state == SubmissionState.REJECTED) submissions.discardRefused(lease, key)
        else if (entry.state != SubmissionState.SENDING) submissions.abandonUnkeyed(lease, key)
        refreshPending(lease)
    }

    private suspend fun resourceAction(id: String, suffix: String, body: JsonObject, revision: Long): Boolean {
        if (!writes.tryLock()) return false
        try {
            val lease = sessions.lease() ?: return false
            if (!owned(lease) || revision < 0 || state.value?.gone == true || !visibility.value.active) return false
            refreshPending(lease)
            if (state.value?.pending?.any { it.endpoint == resourcePath(id) || it.endpoint == resourcePath(id, "/grants") } == true) return false
            change(lease) { copy(busy = true, error = null, serverError = null) }
            try {
                val result = submissions.submit(lease, NewSubmission(resourcePath(id, suffix), null, body.toString().encodeToByteArray(),
                    audience = "resource:$id", revisions = mapOf(id to revision.toString())))
                if (!owned(lease)) return false
                val done = result.state == SubmissionState.RESOLVED && result.response?.let {
                    runCatching { validateSharingSubmissionReceipt(result, it) }.isSuccess
                } == true
                when {
                    done -> submissions.acknowledge(lease, result.key)
                    result.state == SubmissionState.REJECTED -> {
                        change(lease) { copy(error = if (result.failureStatus == 409) GeneratedStrings.Access.changed
                            else nativeError(HttpOutcome.Failure(result.failureStatus ?: 400, result.response ?: ByteArray(0))),
                            person = if (result.failureStatus == 409) null else person) }
                        submissions.discardRefused(lease, result.key)
                    }
                    result.failureStatus == 409 -> change(lease) { copy(error = GeneratedStrings.Access.changed, person = null) }
                    else -> change(lease) { copy(error = GeneratedStrings.Outcome.unknownTitle) }
                }
                refresh()
                return done && owned(lease)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { change(lease) { copy(error = GeneratedStrings.Assistant.draftStorageError) }; return false }
            finally { change(lease) { copy(busy = false) } }
        } finally { writes.unlock() }
    }

    private suspend fun refreshPending(lease: SessionLease) {
        val current = state.value ?: return
        val sectionIds = current.overview.data?.sections.orEmpty().map { it.id }.toSet()
        val entries = submissions.pending(lease).filter { entry -> isSharingResourceEndpoint(entry.endpoint) &&
            when (current.surface) {
                SharingSurface.Settings -> sectionIds.any { entry.endpoint == resourcePath(it) || entry.endpoint == resourcePath(it, "/grants") }
                SharingSurface.ListGrants -> current.listId?.let { entry.endpoint == resourcePath(it) || entry.endpoint == resourcePath(it, "/grants") } == true
                SharingSurface.Shared -> false
            }
        }
        change(lease) { copy(pending = entries) }
    }
    private suspend fun <T> read(lease: SessionLease, path: String, decode: (ByteArray) -> T): CircleQuery<T> = result(http.request("GET", path, lease = lease), decode)
    private fun <T> result(outcome: HttpOutcome, decode: (ByteArray) -> T): CircleQuery<T> {
        if (outcome is HttpOutcome.Success) {
            try { return CircleQuery(data = decode(outcome.body)) }
            catch (_: Exception) { return CircleQuery(error = nativeError(HttpOutcome.InvalidResponse)) }
        }
        return CircleQuery(error = nativeError(outcome), serverError = circleServerError(outcome))
    }
    private fun failure(lease: SessionLease, outcome: HttpOutcome) = change(lease) { copy(error = nativeError(outcome), serverError = circleServerError(outcome)) }
    private fun bind(lease: SessionLease?) {
        if (boundLease !== lease) {
            boundLease = lease; generation++; readEpoch++; searchEpoch++
            mutableState.value = lease?.let { SharingState(it.namespace) }
        }
        val identity = sessions.identity.value
        mutableState.update { it?.copy(scopes = identity?.scopes.orEmpty().toSet(), locale = identity?.locale ?: "ru",
            today = todayIn(identity?.timezone ?: "UTC", clockMs())) }
    }
    private fun owned(lease: SessionLease) = boundLease === lease && sessions.isCurrent(lease)
    private fun currentRead(lease: SessionLease, selected: Long, epoch: Long) = owned(lease) && generation == selected && readEpoch == epoch && visibility.value.active
    private inline fun change(lease: SessionLease, update: SharingState.() -> SharingState) { if (owned(lease)) mutableState.update { it?.update() } }
    private fun <T> retained(before: CircleQuery<T>?, after: CircleQuery<T>) = if (after.data != null || before?.data == null) after else after.copy(data = before.data, stale = true)
    companion object { fun resourcePath(id: String, suffix: String = "") = "/v1/resources/${id.encodeURLPathPart()}$suffix" }
}
private data class SharingVisibility(val active: Boolean, val epoch: Long)
