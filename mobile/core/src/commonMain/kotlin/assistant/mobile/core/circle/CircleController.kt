package assistant.mobile.core.circle

import assistant.mobile.core.*
import assistant.mobile.core.journal.*
import assistant.mobile.core.tasks.decodeTaskLists
import io.ktor.http.encodeURLParameter
import io.ktor.http.encodeURLPathPart
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import kotlin.time.Clock
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/** Circle owns only published conversation text. Private assistant attachments never enter here. */
class CircleController(
    private val scope: CoroutineScope,
    private val sessions: SessionManager,
    private val http: NativeHttpClient,
    private val submissions: SubmissionCoordinator,
    private val drafts: AccountDrafts,
    private val clockMs: () -> Long = { Clock.System.now().toEpochMilliseconds() },
) {
    private val mutableState = MutableStateFlow<CircleState?>(null)
    val state: StateFlow<CircleState?> = mutableState.asStateFlow()
    private val defaultVisibilityOwner = Any()
    private val visibilityOwners = mutableSetOf<Any>()
    private val visibility = MutableStateFlow(CircleVisibility(false, 0))
    private var boundLease: SessionLease? = null
    private var generation = 0L
    private var readEpoch = 0L
    private var draftVersion = 0L
    private val draftVersions = mutableMapOf<String, Long>()
    private val handedOffVersions = mutableMapOf<String, Long>()
    private val reads = Mutex()
    private val writes = Mutex()
    private val draftLock = Mutex()
    private val readMarkers = Mutex()
    private val acknowledged = mutableSetOf<Pair<String, String>>()
    private val reportKeys = mutableMapOf<Triple<ReportTarget, String, String>, String>()
    private val owner = scope.launch {
        combine(sessions.leases, sessions.identity, visibility) { lease, _, visible -> lease to visible }.collectLatest { (lease, visible) ->
            bind(lease)
            if (lease != null && visible.active) refresh()
        }
    }

    fun activate(spaceId: String? = null, channelId: String? = null, visibilityOwner: Any = defaultVisibilityOwner) {
        require(spaceId == null || spaceId.isNotBlank()); require(channelId == null || channelId.isNotBlank())
        visibilityOwners.add(visibilityOwner)
        bind(sessions.lease())
        val previous = state.value
        if (previous?.spaceId != spaceId || previous?.channelId != channelId) {
            generation++; draftVersion++
            mutableState.update { it?.copy(spaceId = spaceId, channelId = channelId, members = CircleQuery(),
                history = CircleQuery(), draft = CircleDraft(), pending = emptyList(), gone = false,
                person = null, error = null, serverError = null) }
            val lease = sessions.lease()
            if (lease != null && channelId != null) {
                val selected = generation; val version = draftVersion
                scope.launch { restoreDraft(lease, channelId, selected, version) }
            }
        }
        visibility.update { CircleVisibility(true, it.epoch + 1) }
    }
    fun deactivate(visibilityOwner: Any = defaultVisibilityOwner) {
        if (!visibilityOwners.remove(visibilityOwner) || visibilityOwners.isNotEmpty()) return
        visibility.update { it.copy(active = false) }
    }
    fun close() {
        owner.cancel(); visibilityOwners.clear(); visibility.update { it.copy(active = false) }
        boundLease = null; mutableState.value = null; acknowledged.clear(); reportKeys.clear()
    }
    fun clearError() { mutableState.update { it?.copy(error = null, serverError = null, searchNotFound = false) } }
    fun clearPerson() { mutableState.update { it?.copy(person = null, searchNotFound = false) } }

    suspend fun refresh() {
        if (!visibility.value.active) return
        val lease = sessions.lease() ?: return
        bind(lease)
        reads.withLock { refreshReads(lease) }
        writes.withLock {
            if (!owned(lease)) return@withLock
            try { refreshPending(lease) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { change(lease) { copy(error = GeneratedStrings.Assistant.draftStorageError) } }
        }
    }
    suspend fun invalidate(invalidation: EventInvalidation) {
        if (invalidation is EventInvalidation.Domains && invalidation.values.none { it in setOf(EventDomain.Community, EventDomain.Tasks, EventDomain.Shared, EventDomain.Calls) }) return
        // A revoked audience must not remain visible while authoritative access is rechecked.
        if (invalidation is EventInvalidation.All || invalidation is EventInvalidation.Domains && EventDomain.Community in invalidation.values) {
            readEpoch++
            mutableState.update { it?.copy(history = CircleQuery(loading = it.channelId != null), members = CircleQuery(loading = it.spaceId != null)) }
        }
        refresh()
    }

    private suspend fun refreshReads(lease: SessionLease) {
        if (!owned(lease)) return
        val selection = generation
        val epoch = readEpoch
        val spaceId = state.value?.spaceId; val channelId = state.value?.channelId
        change(lease) { copy(spaces = spaces.copy(loading = true, error = null, serverError = null),
            channels = channels.copy(loading = true, error = null, serverError = null),
            relationships = relationships.copy(loading = true, error = null, serverError = null)) }
        val spaces = retained(state.value?.spaces, read(lease, "/v1/spaces", ::decodeCircleSpaces))
        if (!readSelected(lease, selection, epoch)) return
        change(lease) { copy(spaces = spaces) }
        if (spaceId != null && spaces.data != null && spaces.data.spaces.none { it.id == spaceId && it.archived != true }) {
            gone(lease); return
        }
        val previousChannel = state.value?.channel
        val channels = retained(state.value?.channels, read(lease, "/v1/channels", ::decodeCircleChannels))
        if (!readSelected(lease, selection, epoch)) return
        change(lease) { copy(channels = channels) }
        if (channelId != null && previousChannel != null && channels.data != null && channels.data.none { it.id == channelId }) {
            gone(lease); return
        }
        val relationships = retained(state.value?.relationships, read(lease, "/v1/relationships", ::decodeCircleRelationships))
        if (!readSelected(lease, selection, epoch)) return
        change(lease) { copy(relationships = relationships) }
        if (spaceId != null) {
            change(lease) { copy(members = members.copy(loading = true), lists = lists.copy(loading = true)) }
            val members = retained(state.value?.members, read(lease, spacePath(spaceId, "members"), ::decodeCircleMembers,
                goneOn404 = true, canMarkGone = { readSelected(lease, selection, epoch) }))
            if (!readSelected(lease, selection, epoch) || state.value?.gone == true) return
            change(lease) { copy(members = members) }
            val lists = retained(state.value?.lists, read(lease, "/v1/tasks/lists", ::decodeTaskLists))
            if (!readSelected(lease, selection, epoch)) return
            change(lease) { copy(lists = lists) }
        }
        if (channelId != null) {
            change(lease) { copy(history = history.copy(loading = true, error = null, serverError = null)) }
            val previous = state.value?.history
            var history = retained(previous, read(lease, messagesPath(channelId), { decodeCircleHistory(it, channelId) },
                goneOn404 = true, canMarkGone = { readSelected(lease, selection, epoch) }))
            if (!readSelected(lease, selection, epoch) || state.value?.gone == true) return
            val oldPage = previous?.data; val freshPage = history.data
            if (history.error == null && oldPage?.channelId == channelId && freshPage != null) {
                val freshById = freshPage.messages.associateBy { it.id }
                val merged = (oldPage.messages.map { freshById[it.id] ?: it } + freshPage.messages).distinctBy { it.id }
                history = history.copy(data = freshPage.copy(messages = merged, nextCursor = oldPage.nextCursor))
            }
            change(lease) { copy(history = history) }
        }
    }

    suspend fun createSpace(name: String): String? {
        val value = name.trim().takeIf { it.isNotEmpty() && it.length <= 100 } ?: return null
        return actionId("/v1/spaces", buildJsonObject { put("name", value) })
    }
    suspend fun joinSpace(id: String): Boolean = action(spacePath(id, "join"), buildJsonObject {})
    suspend fun openDirect(userId: String): String? {
        if (state.value?.relationships?.data?.none { it.userId == userId && it.state == RelationshipState.Accepted } != false) return null
        return actionId("/v1/channels/direct", buildJsonObject { put("user_id", userId) })
    }
    suspend fun relationship(userId: String, action: RelationshipAction, label: String = ""): Boolean {
        if (userId == state.value?.namespace?.userId) return false
        val relation = state.value?.relationships?.data?.firstOrNull { it.userId == userId }
        if (action == RelationshipAction.Accept && (relation?.state != RelationshipState.Pending || relation.recipientId != state.value?.namespace?.userId)) return false
        if (action == RelationshipAction.Label && (relation?.state != RelationshipState.Accepted || label.trim().length > 80)) return false
        val done = this.action("/v1/relationships", buildJsonObject {
            put("user_id", userId); put("action", action.wire); if (action == RelationshipAction.Label) put("label", label.trim())
        })
        return done
    }
    suspend fun searchPerson(login: String): CirclePerson? {
        val exact = login.trim().removePrefix("@").takeIf { it.isNotEmpty() } ?: return null
        val lease = sessions.lease() ?: return null
        bind(lease)
        change(lease) { copy(searching = true, searchNotFound = false, person = null, error = null, serverError = null) }
        try {
            val outcome = http.request("GET", "/v1/relationships?login=${exact.encodeURLParameter()}", lease = lease)
            if (!owned(lease)) return null
            if (outcome !is HttpOutcome.Success) { failure(lease, outcome); return null }
            val person = try { decodeCirclePerson(outcome.body) } catch (_: Exception) { failure(lease, HttpOutcome.InvalidResponse); return null }
            when {
                person == null -> change(lease) { copy(searchNotFound = true, error = GeneratedStrings.Access.notFound) }
                person.id == lease.namespace.userId -> change(lease) { copy(error = GeneratedStrings.Access.notYourself) }
                else -> { change(lease) { copy(person = person) }; return person }
            }
            return null
        } finally { change(lease) { copy(searching = false) } }
    }
    suspend fun invite(spaceId: String, userId: String): Boolean {
        if (!can(spaceId)?.invite.orFalse() || state.value?.members?.data?.any { it.userId == userId } == true) return false
        return action(spacePath(spaceId, "invitations"), buildJsonObject { put("user_id", userId) })
    }
    suspend fun changeRole(spaceId: String, userId: String, role: CircleRole?): Boolean {
        val lease = sessions.lease() ?: return false
        val current = state.value ?: return false
        if (current.spaceId != spaceId) return false
        val me = current.currentMember ?: return false
        val target = current.members.data?.firstOrNull { it.userId == userId } ?: return false
        if (role == null) {
            if (!mayRemove(me, target) || soleOwner(current.members.data.orEmpty(), userId)) return false
        } else {
            if (!abilities(me.role).changeRoles || target.role == role) return false
            if (target.role == CircleRole.Owner && role != CircleRole.Owner && soleOwner(current.members.data.orEmpty(), userId)) return false
        }
        val done = action(spacePath(spaceId, "members"), buildJsonObject { put("user_id", userId); put("role", role?.let { JsonPrimitive(it.wire) } ?: JsonNull) })
        if (!owned(lease)) return false
        if (done && role == null && userId == me.userId) gone(lease)
        return done
    }
    suspend fun archiveSpace(spaceId: String): Boolean {
        val lease = sessions.lease() ?: return false
        val space = state.value?.space?.takeIf { it.id == spaceId && abilities(it.role).archive } ?: return false
        val done = action(spacePath(spaceId, "archive"), buildJsonObject { put("revision", space.revision) })
        if (!owned(lease)) return false
        if (done) gone(lease)
        return done
    }
    suspend fun createList(spaceId: String, name: String): Boolean {
        val value = name.trim().takeIf { it.isNotEmpty() && it.length <= 120 } ?: return false
        if (state.value?.spaceId != spaceId || state.value?.space == null || state.value?.gone == true || state.value?.canWriteTasks != true) return false
        return action("/v1/tasks/lists", buildJsonObject { put("name", value); put("space_id", spaceId) })
    }

    fun updateDraft(text: String) = updateDraftValue { copy(text = text.take(4000)) }
    fun replyTo(id: String?) {
        if (id != null && state.value?.history?.data?.messages?.none { it.id == id && it.call == null } != false) return
        updateDraftValue { copy(replyToId = id) }
    }
    private fun updateDraftValue(update: CircleDraft.() -> CircleDraft) {
        val lease = sessions.lease() ?: return
        val channel = state.value?.channelId ?: return
        if (!owned(lease) || state.value?.gone == true) return
        val value = state.value!!.draft.update()
        val version = ++draftVersion
        draftVersions[channel] = version
        change(lease) { copy(draft = value, error = null) }
        scope.launch {
            try { draftLock.withLock {
                if (!owned(lease) || draftVersions[channel] != version || (handedOffVersions[channel] ?: -1) >= version) return@withLock
                drafts.save(lease, draftTarget(channel), NativeJson.encodeToString(value).encodeToByteArray(), version)
            } } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { change(lease) { copy(error = GeneratedStrings.Assistant.draftStorageError) } }
        }
    }
    private suspend fun restoreDraft(lease: SessionLease, channel: String, selection: Long, version: Long) {
        try {
            val draft = drafts.read(lease, draftTarget(channel)) ?: return
            val decoded = NativeJson.decodeFromString<CircleDraft>(draft.json.decodeToString())
            require(decoded.text.length <= 4000)
            if (selected(lease, selection) && state.value?.channelId == channel && draftVersion == version) {
                draftVersion = maxOf(draftVersion, draft.revision)
                change(lease) { copy(draft = decoded) }
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { change(lease) { copy(error = GeneratedStrings.Assistant.draftStorageError) } }
    }

    suspend fun send(assistant: Boolean = false) = writes.withLock {
        val lease = sessions.lease() ?: return@withLock
        val current = state.value ?: return@withLock
        val channel = current.channelId ?: return@withLock
        val text = current.draft.text.trim()
        if (!owned(lease) || current.gone || current.history.data?.channelId != channel || text.isEmpty() || text.length > 4000 || current.channel?.kind !in setOf(ChannelKind.Space, ChannelKind.Direct, ChannelKind.Resource)) return@withLock
        try {
            refreshPending(lease)
            if (state.value?.pending?.isNotEmpty() == true) return@withLock
            change(lease) { copy(busy = true, error = null, serverError = null) }
            val version = draftVersion; val selection = generation
            val body = buildJsonObject {
                put("text", text); if (assistant) put("assistant", true)
                current.draft.replyToId?.let { put("reply_to_id", it) }
            }.toString().encodeToByteArray()
            val result = submissions.submit(lease, NewSubmission(messagesPath(channel), null, body,
                audience = "channel:$channel", draftTarget = draftTarget(channel)))
            if (!owned(lease)) return@withLock
            if (result.state != SubmissionState.REJECTED) {
                draftLock.withLock {
                    handedOffVersions[channel] = maxOf(handedOffVersions[channel] ?: -1, version)
                    val stored = drafts.read(lease, draftTarget(channel))
                    if (stored != null && stored.revision <= version && runCatching { NativeJson.decodeFromString<CircleDraft>(stored.json.decodeToString()) }.getOrNull() == current.draft) {
                        drafts.delete(lease, draftTarget(channel))
                    }
                    if (selected(lease, selection) && draftVersion == version) {
                        ++draftVersion; change(lease) { copy(draft = CircleDraft()) }
                    }
                }
            } else {
                change(lease) { copy(error = if (result.failureStatus == 403) GeneratedStrings.Circle.friendsOnly else GeneratedStrings.Errors.invalid) }
                submissions.abandonUnkeyed(lease, result.key)
            }
            reads.withLock { refreshReads(lease) }
            refreshPending(lease)
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { change(lease) { copy(error = GeneratedStrings.Assistant.draftStorageError) } }
        finally { change(lease) { copy(busy = false) } }
    }

    /** There is no by-key message lookup API. Checking only re-reads; it never guesses by text. */
    suspend fun checkMessage(key: String) {
        val lease = sessions.lease() ?: return
        if (!owned(lease) || state.value?.pending?.none { it.key == key } != false) return
        refresh()
    }
    /** The person inspected history and explicitly chose to stop tracking an unknown send. */
    suspend fun forgetMessage(key: String) = writes.withLock {
        val lease = sessions.lease() ?: return@withLock
        val current = state.value ?: return@withLock
        if (current.gone || current.history.data?.channelId != current.channelId) return@withLock
        val entry = submissions.pending(lease).firstOrNull { it.key == key && it.endpoint == current.channelId?.let(::messagesPath) } ?: return@withLock
        if (entry.state == SubmissionState.RESOLVED || entry.state == SubmissionState.ACCEPTED) submissions.acknowledge(lease, key)
        else submissions.abandonUnkeyed(lease, key)
        refreshPending(lease)
    }
    private suspend fun refreshPending(lease: SessionLease) {
        val channel = state.value?.channelId ?: return
        val entries = submissions.pending(lease).filter { it.endpoint == messagesPath(channel) }
        val history = state.value?.history?.data
        val pending = entries.filter { entry ->
            val id = entry.response?.let { runCatching { NativeJson.parseToJsonElement(it.decodeToString()).jsonObject["message_id"]?.jsonPrimitive?.content }.getOrNull() }
            if (entry.state in setOf(SubmissionState.RESOLVED, SubmissionState.ACCEPTED) && id != null && history?.channelId == channel && history.messages.any { it.id == id }) {
                submissions.acknowledge(lease, entry.key); false
            } else true
        }
        if (state.value?.channelId == channel) change(lease) { copy(pending = pending) }
    }

    suspend fun loadEarlier() = reads.withLock {
        val lease = sessions.lease() ?: return@withLock
        val current = state.value ?: return@withLock
        val channel = current.channelId ?: return@withLock
        val old = current.history.data ?: return@withLock
        val cursor = old.nextCursor ?: return@withLock
        if (!owned(lease) || current.gone || current.history.loading) return@withLock
        val selection = generation
        val epoch = readEpoch
        change(lease) { copy(history = history.copy(loading = true, error = null, serverError = null)) }
        val next = read(lease, "${messagesPath(channel)}?before=${cursor.encodeURLParameter()}", { decodeCircleHistory(it, channel) },
            goneOn404 = true, canMarkGone = { readSelected(lease, selection, epoch) })
        if (!readSelected(lease, selection, epoch) || state.value?.gone == true) return@withLock
        change(lease) { copy(history = if (next.data != null) next.copy(data = old.copy(messages = (next.data.messages + old.messages).distinctBy { it.id }, nextCursor = next.data.nextCursor)) else history.copy(loading = false, error = next.error, serverError = next.serverError)) }
    }

    /** Only the keyed marker after this channel's latest fetched message acknowledges history. */
    suspend fun markRead(channelId: String, throughMessageId: String, markerVisible: Boolean, resumed: Boolean) = readMarkers.withLock {
        val lease = sessions.lease() ?: return@withLock
        val current = state.value ?: return@withLock
        val key = channelId to throughMessageId
        if (!owned(lease) || !visibility.value.active || !markerVisible || !resumed || current.gone || current.channelId != channelId ||
            current.history.data?.channelId != channelId || current.history.data.latestMessageId != throughMessageId || key in acknowledged) return@withLock
        val selection = generation
        val epoch = readEpoch
        val outcome = http.request("POST", "/v1/channels/${channelId.encodeURLPathPart()}/read",
            buildJsonObject { put("through_message_id", throughMessageId) }.toString().encodeToByteArray(), lease)
        if (!readSelected(lease, selection, epoch)) return@withLock
        if (outcome is HttpOutcome.Success) {
            acknowledged += key
            val channels = retained(state.value?.channels, read(lease, "/v1/channels", ::decodeCircleChannels))
            if (readSelected(lease, selection, epoch)) change(lease) { copy(channels = channels) }
        } else if (circleGone(outcome)) gone(lease)
    }

    @OptIn(ExperimentalUuidApi::class)
    suspend fun report(target: ReportTarget, targetId: String, reason: String): Boolean {
        if (!writes.tryLock()) return false
        try {
            val lease = sessions.lease() ?: return false
            val value = reason.trim().takeIf { it.isNotEmpty() && it.length <= 1000 } ?: return false
            if (!owned(lease)) return false
            val identity = Triple(target, targetId, value)
            val key = reportKeys.getOrPut(identity) { Uuid.random().toString() }
            change(lease) { copy(busy = true, error = null, serverError = null) }
            try {
                val body = buildJsonObject { put("target_type", target.wire); put("target_id", targetId); put("reason", value) }.toString().encodeToByteArray()
                val outcome = http.request("POST", "/v1/community/reports", body, lease, key)
                if (!owned(lease)) return false
                return if (outcome is HttpOutcome.Success) { reportKeys.remove(identity); true } else { failure(lease, outcome); false }
            } finally { change(lease) { copy(busy = false) } }
        } finally { writes.unlock() }
    }

    private fun can(spaceId: String) = state.value?.takeIf { it.spaceId == spaceId }?.currentMember?.let { abilities(it.role) }
    private suspend fun action(path: String, body: JsonObject): Boolean = mutate(path, body) != null
    private suspend fun actionId(path: String, body: JsonObject): String? = mutate(path, body)?.let { runCatching { decodeCircleId(it) }.getOrNull() }
    private suspend fun mutate(path: String, body: JsonObject): ByteArray? {
        if (!writes.tryLock()) return null
        try {
            val lease = sessions.lease() ?: return null
            bind(lease)
            val selection = state.value?.let { it.spaceId to it.channelId }
            change(lease) { copy(busy = true, error = null, serverError = null) }
            try {
                val outcome = http.request("POST", path, body.toString().encodeToByteArray(), lease)
                if (!owned(lease)) return null
                if (outcome !is HttpOutcome.Success) failure(lease, outcome)
                // Even refusals refresh revisions, membership and friendship like useCircleActions.
                reads.withLock { refreshReads(lease) }
                if (!owned(lease) || state.value?.let { it.spaceId to it.channelId } != selection) return null
                return (outcome as? HttpOutcome.Success)?.body
            } finally { change(lease) { copy(busy = false) } }
        } finally { writes.unlock() }
    }
    private suspend fun <T> read(lease: SessionLease, path: String, decode: (ByteArray) -> T, goneOn404: Boolean = false,
        canMarkGone: () -> Boolean = { true }): CircleQuery<T> {
        val outcome = http.request("GET", path, lease = lease)
        if (!owned(lease)) return CircleQuery()
        if (goneOn404 && circleGone(outcome)) { if (canMarkGone()) gone(lease); return CircleQuery() }
        if (outcome is HttpOutcome.Success) {
            try { return CircleQuery(data = decode(outcome.body)) }
            catch (_: Exception) { return CircleQuery(error = nativeError(HttpOutcome.InvalidResponse)) }
        }
        return CircleQuery(error = nativeError(outcome), serverError = circleServerError(outcome))
    }
    private fun failure(lease: SessionLease, outcome: HttpOutcome) = change(lease) { copy(error = if (outcome is HttpOutcome.Conflict) GeneratedStrings.Circle.changed else nativeError(outcome), serverError = circleServerError(outcome)) }
    private fun gone(lease: SessionLease) {
        generation++; draftVersion++
        change(lease) {
            val revokedSpace = spaceId ?: channel?.spaceId
            copy(gone = true, members = CircleQuery(), history = CircleQuery(), draft = CircleDraft(), pending = emptyList(), person = null,
                channels = channels.copy(data = channels.data?.filterNot { it.id == channelId || revokedSpace != null && it.spaceId == revokedSpace }),
                spaces = spaces.copy(data = spaces.data?.let { it.copy(spaces = it.spaces.filterNot { space -> space.id == revokedSpace }) }),
                lists = CircleQuery())
        }
    }
    private fun bind(lease: SessionLease?) {
        if (boundLease === lease) {
            mutableState.update { it?.copy(canWriteTasks = "tasks.write" in sessions.identity.value?.scopes.orEmpty()) }
            return
        }
        boundLease = lease; generation++; draftVersion++; acknowledged.clear(); reportKeys.clear(); draftVersions.clear(); handedOffVersions.clear()
        mutableState.value = lease?.let { CircleState(it.namespace, canWriteTasks = "tasks.write" in sessions.identity.value?.scopes.orEmpty()) }
    }
    private fun owned(lease: SessionLease) = boundLease === lease && sessions.isCurrent(lease)
    private fun selected(lease: SessionLease, selected: Long) = owned(lease) && generation == selected
    private fun readSelected(lease: SessionLease, selection: Long, epoch: Long) = selected(lease, selection) && readEpoch == epoch
    private inline fun change(lease: SessionLease, update: CircleState.() -> CircleState) { if (owned(lease)) mutableState.update { it?.update() } }
    private fun Boolean?.orFalse() = this == true
    private fun <T> retained(previous: CircleQuery<T>?, next: CircleQuery<T>): CircleQuery<T> =
        if (next.data != null || previous?.data == null) next else next.copy(data = previous.data, stale = true)
    companion object {
        fun draftTarget(channelId: String) = "circle/channel:$channelId"
        fun messagesPath(channelId: String) = "/v1/channels/${channelId.encodeURLPathPart()}/messages"
        fun spacePath(spaceId: String, tail: String) = "/v1/spaces/${spaceId.encodeURLPathPart()}/$tail"
    }
}
private data class CircleVisibility(val active: Boolean, val epoch: Long)
