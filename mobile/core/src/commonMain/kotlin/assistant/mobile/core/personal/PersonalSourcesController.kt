package assistant.mobile.core.personal

import assistant.mobile.core.AccountDrafts
import assistant.mobile.core.AccountNamespace
import assistant.mobile.core.EventDomain
import assistant.mobile.core.EventInvalidation
import assistant.mobile.core.GeneratedStrings
import assistant.mobile.core.LocalizedString
import assistant.mobile.core.NativeHttpClient
import assistant.mobile.core.NativeIdentity
import assistant.mobile.core.NativeJson
import assistant.mobile.core.SessionLease
import assistant.mobile.core.SessionManager
import assistant.mobile.core.journal.NewSubmission
import assistant.mobile.core.journal.Submission
import assistant.mobile.core.journal.SubmissionCoordinator
import assistant.mobile.core.journal.SubmissionState
import assistant.mobile.core.nativeError
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.time.Clock
import kotlin.time.Instant

data class PersonalSourcesState(
    val namespace: AccountNamespace, val today: String, val day: String, val timezone: String, val locale: String,
    val canRead: Boolean, val canWrite: Boolean, val sourceId: String? = null, val rowKey: String? = null,
    val displayUnits: PersonalDisplayUnits = PersonalDisplayUnits.Metric,
    val sources: PersonalQuery<List<PersonalSourceEntry>> = PersonalQuery(),
    val combined: PersonalQuery<PersonalSourceDay> = PersonalQuery(), val own: PersonalQuery<PersonalSourceDay> = PersonalQuery(),
    val comparison: PersonalQuery<PersonalSourceDay> = PersonalQuery(),
    val policyDraft: PersonalSourcePolicyDraft? = null, val pending: List<Submission> = emptyList(),
    val run: PersonalQuery<PersonalDdxRun> = PersonalQuery(), val busy: Boolean = false,
    val conflict: Boolean = false, val error: LocalizedString? = null,
)

class PersonalSourcesController(
    private val scope: CoroutineScope, private val sessions: SessionManager, http: NativeHttpClient,
    private val submissions: SubmissionCoordinator, private val drafts: AccountDrafts,
    private val clockMs: () -> Long = { Clock.System.now().toEpochMilliseconds() },
    private val reader: PersonalSourcesReader = NativePersonalSourcesApi(http),
    private val profileReader: PersonalReader = NativePersonalApi(http),
) {
    private val mutableState = MutableStateFlow<PersonalSourcesState?>(null)
    val state: StateFlow<PersonalSourcesState?> = mutableState.asStateFlow()
    private var boundLease: SessionLease? = null
    private val visible = MutableStateFlow(false)
    private val owners = mutableMapOf<Any, SessionLease>()
    private val reads = Mutex()
    private val mutations = Mutex()
    private val draftWrites = Mutex()
    private var draftVersion = 0L
    private var restored = false
    private var explicitDay = false
    private var comparisonGeneration = 0L

    init {
        scope.launch {
            combine(sessions.leases, sessions.identity, visible) { lease, identity, active -> Triple(lease, identity, active) }
                .collectLatest { (lease, identity, active) ->
                    bind(lease, identity)
                    if (!active || lease == null || state.value?.canRead != true) return@collectLatest
                    refresh()
                    while (visible.value && owned(lease)) {
                        delay(2000)
                        val run = state.value?.run?.data
                        if (run?.active == true) refreshRun(run.id, run.sourceId)
                    }
                }
        }
    }

    fun activate(sourceId: String? = null, rowKey: String? = null, day: String? = null, owner: Any = DEFAULT_OWNER) {
        bind(sessions.lease(), sessions.identity.value)
        val lease = boundLease ?: return
        require(rowKey == null || PERSONAL_SOURCE_ROWS.any { it.key == rowKey })
        require(day == null || LocalDate.parse(day).toString() == day)
        owners[owner] = lease
        val wasVisible = visible.value
        val before = state.value ?: return
        val nextDay = day?.let { minOf(it, before.today) } ?: if (sourceId == before.sourceId) before.day else before.today
        explicitDay = day != null
        change(lease) { if (sourceId == this.sourceId && rowKey == this.rowKey && nextDay == this.day) this
            else copy(sourceId = sourceId, rowKey = rowKey, day = nextDay, own = PersonalQuery(), combined = PersonalQuery(), error = null) }
        visible.value = true
        if (wasVisible) scope.launch { refresh() }
    }
    fun deactivate(owner: Any = DEFAULT_OWNER) { owners.remove(owner); visible.value = owners.values.any { it === boundLease } }

    suspend fun selectDay(day: String) {
        val before = state.value ?: return
        require(LocalDate.parse(day).toString() == day && day <= before.today)
        explicitDay = true
        val lease = boundLease ?: return
        change(lease) { copy(day = day, own = PersonalQuery(), combined = PersonalQuery()) }
        refresh()
    }

    suspend fun refresh() {
        val lease = boundLease ?: return
        if (!owned(lease) || !visible.value || state.value?.canRead != true) return
        try {
            restoreDraft(lease)
            refreshReads(lease)
            refreshPending(lease, recover = true)
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { change(lease) { copy(error = GeneratedStrings.Assistant.draftStorageError) } }
    }
    suspend fun invalidate(invalidation: EventInvalidation) {
        if (invalidation is EventInvalidation.Domains && EventDomain.Personal !in invalidation.values) return
        val lease = boundLease ?: return
        change(lease) { copy(sources = sources.copy(stale = sources.data != null),
            combined = combined.copy(stale = combined.data != null), own = own.copy(stale = own.data != null),
            comparison = comparison.copy(stale = comparison.data != null)) }
        refresh()
    }

    fun beginPolicy(rowKey: String) {
        val before = state.value ?: return
        val row = requireNotNull(PERSONAL_SOURCE_ROWS.find { it.key == rowKey })
        val draft = PersonalSourcePolicyDraft(rowKey, personalSourcePin(row, before.combined.data?.policies.orEmpty(), before.today), before.today)
        if (before.canRead && !before.canWrite) boundLease?.let { lease -> change(lease) { copy(policyDraft = draft) } }
        else editPolicy(draft)
        if (state.value?.policyDraft?.rowKey == rowKey) {
            comparisonGeneration++
            boundLease?.let { lease ->
                change(lease) { copy(comparison = PersonalQuery()) }
                scope.launch { refreshComparison(lease) }
            }
        }
    }
    fun choosePolicy(sourceId: String?) { state.value?.policyDraft?.let { editPolicy(it.copy(sourceId = sourceId)) } }
    fun setPolicyDate(day: String) { state.value?.policyDraft?.let { editPolicy(it.copy(effectiveFrom = day)) } }
    fun updatePolicyDraft(draft: PersonalSourcePolicyDraft) = editPolicy(draft)
    private fun editPolicy(draft: PersonalSourcePolicyDraft) {
        val lease = boundLease ?: return
        if (!owned(lease) || !restored || state.value?.canWrite != true || state.value?.busy == true || state.value?.pending?.isNotEmpty() == true) return
        draftVersion++
        val version = draftVersion
        change(lease) { copy(policyDraft = draft, error = null) }
        scope.launch {
            try { persistDraft(lease, draft, version) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { change(lease) { copy(error = GeneratedStrings.Assistant.draftStorageError) } }
        }
    }
    suspend fun dismissPolicy() {
        val lease = boundLease ?: return
        if (!owned(lease) || state.value?.busy == true || state.value?.pending?.any { it.draftTarget == POLICY } == true) return
        draftWrites.withLock { draftVersion++; drafts.delete(lease, POLICY); comparisonGeneration++
            change(lease) { copy(policyDraft = null, conflict = false, comparison = PersonalQuery()) } }
    }
    suspend fun savePolicy() = mutate { lease ->
        val before = state.value ?: return@mutate
        val draft = before.policyDraft ?: return@mutate
        require(draft.effectiveFrom <= before.today)
        persistDraft(lease, draft, draftVersion)
        submit(lease, sourcePolicyPersonalOperation(draft, before.sources.data.orEmpty(), before.combined.data?.policies.orEmpty()), POLICY)
    }
    suspend fun requestSync(sourceId: String) = mutate { lease ->
        val source = source(sourceId)
        require(source.connection?.authState == "active" && source.connection.activeRun?.active != true)
        require(source.connection.retryAt?.let { Instant.parse(it).toEpochMilliseconds() > clockMs() } != true)
        submit(lease, PersonalOperation("personal.source.sync.request", buildJsonObject { put("source_id", sourceId) }))
    }
    suspend fun setAutomatic(sourceId: String, automatic: Boolean) = mutate { lease ->
        val connection = requireNotNull(source(sourceId).connection)
        submit(lease, PersonalOperation("personal.source.automatic.update", buildJsonObject {
            put("source_id", sourceId); put("automatic", automatic)
        }, mapOf(sourceId to connection.revision)))
    }
    suspend fun switchSource(sourceId: String) = mutate { lease ->
        val source = source(sourceId)
        require(source.connection == null)
        submit(lease, PersonalOperation("personal.source.update", buildJsonObject {
            put("source_id", sourceId); put("status", if (source.status == "disabled") "connected" else "disabled")
        }, mapOf(sourceId to source.revision)))
    }
    suspend fun createSource(name: String, kinds: List<String>) = mutate { lease ->
        require(name.trim().isNotEmpty() && name.trim().length <= 100 && kinds.isNotEmpty())
        submit(lease, PersonalOperation("personal.source.create", buildJsonObject {
            put("name", name.trim()); put("kinds", JsonArray(kinds.distinct().map(::JsonPrimitive)))
        }))
    }
    suspend fun linkSources(firstId: String, secondId: String) = mutate { lease ->
        val first = source(firstId); val second = source(secondId)
        require(firstId != secondId && first.device.kind == second.device.kind)
        val identities = state.value?.combined?.data?.identities.orEmpty()
        submit(lease, PersonalOperation("personal.resolution.source.link", buildJsonObject {
            put("source_ids", JsonArray(listOf(firstId, secondId).map(::JsonPrimitive)))
            put("identity_revisions", JsonObject(listOf(firstId, secondId).associateWith { JsonPrimitive(identities[it]?.revision ?: 0) }))
        }, mapOf(firstId to first.revision, secondId to second.revision)))
    }
    suspend fun unlinkSource(sourceId: String) = mutate { lease ->
        val source = source(sourceId)
        val identity = requireNotNull(state.value?.combined?.data?.identities?.get(sourceId))
        submit(lease, PersonalOperation("personal.resolution.source.unlink", buildJsonObject {
            put("source_id", sourceId); put("identity_revision", identity.revision)
        }, mapOf(sourceId to source.revision)))
    }

    suspend fun check(key: String) = action { lease ->
        val entry = submissions.pending(lease).find { it.key == key && isOwnOperation(it) } ?: return@action
        settle(lease, submissions.check(lease, entry.key))
        refreshPending(lease)
    }

    private suspend fun action(block: suspend (SessionLease) -> Unit) {
        val lease = boundLease ?: return
        scope.async { mutations.withLock {
            if (!owned(lease)) return@withLock
            try { change(lease) { copy(busy = true, error = null) }; block(lease) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: IllegalArgumentException) { change(lease) { copy(error = GeneratedStrings.Errors.invalid) } }
            catch (_: Exception) { change(lease) { copy(error = GeneratedStrings.Assistant.draftStorageError) } }
            finally { change(lease) { copy(busy = false) } }
        } }.await()
    }
    private suspend fun mutate(block: suspend (SessionLease) -> Unit) = action { lease ->
        if (state.value?.canWrite != true || "personal.write" !in (sessions.identity.value?.scopes ?: emptyList())) return@action
        if (!restored) { restoreDraft(lease); if (!restored) return@action }
        refreshPending(lease)
        if (state.value?.pending?.isNotEmpty() == true) return@action
        block(lease)
        refreshPending(lease)
    }
    private suspend fun submit(lease: SessionLease, operation: PersonalOperation, target: String? = null) {
        if (!owned(lease) || state.value?.canWrite != true || "personal.write" !in (sessions.identity.value?.scopes ?: emptyList())) return
        settle(lease, submissions.submit(lease, NewSubmission(PERSONAL_OPERATIONS, PERSONAL_LOOKUP, operation.bytes(), "personal",
            revisions = operation.expectedRevisions.mapValues { it.value.toString() }, draftTarget = target)))
    }
    private suspend fun settle(lease: SessionLease, entry: Submission) {
        if (!owned(lease)) return
        if (entry.state == SubmissionState.RESOLVED && entry.response != null) {
            validatePersonalSourceReceipt(entry, entry.response)
            val receipt = personalOperationObject(entry.response)
            if (entry.draftTarget == POLICY) {
                val draft = state.value?.policyDraft
                val command = personalOperationObject(entry.body)
                val payload = requireNotNull(command.sObjectOrNull("payload"))
                val entries = payload.sArrayOrEmpty("policies").map { it as JsonObject }.ifEmpty { listOf(payload) }
                if (draft != null && draft.effectiveFrom == payload.sText("effective_from") &&
                    entries.all { it.sArray("source_ids").map { value -> (value as JsonPrimitive).content } == listOfNotNull(draft.sourceId) } &&
                    entries.all { it.sText("metric") in PERSONAL_SOURCE_ROWS.first { row -> row.key == draft.rowKey }.metrics }) {
                    draftWrites.withLock { drafts.delete(lease, POLICY); draftVersion++; comparisonGeneration++
                        change(lease) { copy(policyDraft = null, comparison = PersonalQuery()) } }
                }
            }
            change(lease) { copy(conflict = false, error = null) }
            refreshReads(lease)
            if (receipt.sText("kind") == "personal.source.sync.request") refreshRun(receipt.sText("run_id"), receipt.sText("source_id"))
            if (owned(lease) && state.value?.sources?.let { it.data != null && it.error == null } == true &&
                state.value?.combined?.let { it.data != null && it.error == null } == true) submissions.acknowledge(lease, entry.key)
        } else if (entry.state == SubmissionState.REJECTED) {
            val conflict = entry.failureStatus == 409
            change(lease) { copy(conflict = conflict, error = if (conflict) GeneratedStrings.Personal.conflict else GeneratedStrings.Errors.server) }
            refreshReads(lease)
        }
    }
    private suspend fun refreshPending(lease: SessionLease, recover: Boolean = false) {
        try {
            val entries = submissions.pending(lease).filter(::isOwnOperation)
            if (recover) for (entry in entries) {
                val next = if (entry.state in setOf(SubmissionState.SENDING, SubmissionState.UNCERTAIN, SubmissionState.ACCEPTED)) submissions.check(lease, entry.key) else entry
                if (next.state == SubmissionState.RESOLVED) settle(lease, next)
            }
            val pending = submissions.pending(lease).filter(::isOwnOperation).filter { it.state !in setOf(SubmissionState.REJECTED, SubmissionState.RESOLVED) }
            change(lease) { copy(pending = pending) }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { change(lease) { copy(error = GeneratedStrings.Assistant.draftStorageError) }; throw IllegalStateException("Journal unavailable") }
    }
    private fun isOwnOperation(entry: Submission): Boolean = entry.endpoint == PERSONAL_OPERATIONS && runCatching {
        personalOperationObject(entry.body).sText("kind") in PERSONAL_SOURCE_OPERATION_KINDS
    }.getOrDefault(false)

    private suspend fun refreshReads(lease: SessionLease) = reads.withLock {
        if (!owned(lease) || state.value?.canRead != true) return@withLock
        val sourceId = state.value?.sourceId
        read(lease, { reader.sources(lease) }, current = { sources }) { copy(sources = it) }
        val source = state.value?.sources?.data?.find { it.id == sourceId }
        if (!explicitDay && source != null) {
            val measured = source.lastMeasuredAt ?: source.lastReceivedAt
            val day = measured?.let { runCatching { Instant.parse(it).toLocalDateTime(TimeZone.of(state.value!!.timezone)).date.toString() }.getOrNull() }
            change(lease) { copy(day = minOf(day ?: today, today)) }
        }
        val day = state.value?.day ?: return@withLock
        read(lease, { reader.day(day, null, lease) }, day, current = { combined }) { copy(combined = it) }
        if (source != null) read(lease, { reader.day(day, source.id, lease) }, day, source.id, { own }) { copy(own = it) }
        try {
            val overview = profileReader.overview(day, lease)
            change(lease, day) { copy(displayUnits = overview.profile.displayUnits) }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { /* Units stay at the last authoritative value. */ }
        val active = (source ?: state.value?.sources?.data?.firstOrNull { it.connection?.activeRun != null })?.connection?.activeRun
        if (active != null) change(lease) { copy(run = PersonalQuery(active)) }
        if (state.value?.policyDraft != null) refreshComparison(lease)
    }
    private suspend fun refreshComparison(lease: SessionLease) {
        val before = state.value ?: return
        val rowKey = before.policyDraft?.rowKey ?: return
        val row = PERSONAL_SOURCE_ROWS.first { it.key == rowKey }
        if (row.key !in PERSONAL_TIMED_SOURCE_ROWS || before.sources.data.orEmpty().count { source ->
                source.active && row.metrics.any { it in source.metricKeys }
            } < 2) return
        val generation = ++comparisonGeneration
        val day = before.today
        fun current(): Boolean = owned(lease) && comparisonGeneration == generation && state.value?.today == day &&
            state.value?.policyDraft?.rowKey == rowKey
        change(lease) { copy(comparison = comparison.copy(loading = true, error = null)) }
        try {
            val data = reader.week(row.metrics.first(), day, lease)
            if (current()) change(lease) { copy(comparison = PersonalQuery(data)) }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) {
            if (current()) change(lease) { copy(comparison = comparison.copy(loading = false, stale = comparison.data != null,
                error = if (failure is PersonalReadException) nativeError(failure.outcome) else GeneratedStrings.Errors.server)) }
        }
    }
    private suspend fun refreshRun(id: String, sourceId: String) {
        val lease = boundLease ?: return
        val old = state.value?.run?.data
        read(lease, { reader.syncRun(id, lease).also { require(it.sourceId == sourceId) } }, current = { run }) { copy(run = it) }
        if (old?.active == true && state.value?.run?.data?.active == false) refreshReads(lease)
    }
    private suspend fun <T> read(lease: SessionLease, fetch: suspend () -> T, day: String? = null, sourceId: String? = null,
        current: PersonalSourcesState.() -> PersonalQuery<T>,
        update: PersonalSourcesState.(PersonalQuery<T>) -> PersonalSourcesState) {
        change(lease, day, sourceId) { update(current().copy(loading = true, error = null)) }
        try { val data = fetch(); change(lease, day, sourceId) { update(PersonalQuery(data)) } }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { change(lease, day, sourceId) { update(current().copy(loading = false, stale = current().data != null, error =
            if (failure is PersonalReadException) nativeError(failure.outcome) else GeneratedStrings.Errors.server)) } }
    }
    private suspend fun restoreDraft(lease: SessionLease) {
        if (restored || !owned(lease)) return
        try {
            val stored = drafts.read(lease, POLICY)
            if (stored != null) {
                val draft = NativeJson.decodeFromString<PersonalSourcePolicyDraft>(stored.json.decodeToString())
                draftVersion = maxOf(draftVersion, stored.revision)
                change(lease) { if (policyDraft == null) copy(policyDraft = draft) else this }
            }
            restored = true
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { change(lease) { copy(error = GeneratedStrings.Assistant.draftStorageError) } }
    }
    private suspend fun persistDraft(lease: SessionLease, draft: PersonalSourcePolicyDraft, version: Long) = draftWrites.withLock {
        if (!owned(lease) || version != draftVersion || state.value?.policyDraft != draft) return@withLock
        drafts.save(lease, POLICY, NativeJson.encodeToString(draft).encodeToByteArray(), version)
    }
    private fun source(id: String): PersonalSourceEntry = requireNotNull(state.value?.sources?.data?.find { it.id == id })
    private fun bind(lease: SessionLease?, identity: NativeIdentity?) {
        if (lease == null || identity == null || identity.id != lease.namespace.userId) {
            boundLease = null; mutableState.value = null; owners.clear(); visible.value = false; return
        }
        val changed = boundLease !== lease
        if (changed) { restored = false; draftVersion = 0; explicitDay = false; comparisonGeneration++
            owners.entries.removeAll { it.value !== lease }; visible.value = owners.isNotEmpty() }
        boundLease = lease
        val today = Instant.fromEpochMilliseconds(clockMs()).toLocalDateTime(TimeZone.of(identity.timezone)).date.toString()
        val canRead = "personal.read" in identity.scopes
        mutableState.update { previous ->
            val base = (if (changed || !canRead) null else previous) ?: PersonalSourcesState(lease.namespace, today, today, identity.timezone, identity.locale, canRead, false)
            base.copy(today = today, timezone = identity.timezone, locale = identity.locale, canRead = canRead, canWrite = canRead && "personal.write" in identity.scopes)
        }
    }
    private fun owned(lease: SessionLease): Boolean = boundLease === lease && sessions.isCurrent(lease)
    private fun change(lease: SessionLease, day: String? = null, sourceId: String? = null, update: PersonalSourcesState.() -> PersonalSourcesState) {
        if (!owned(lease) || "personal.read" !in (sessions.identity.value?.scopes ?: emptyList())) return
        mutableState.update { before -> if (before == null || !before.canRead || (day != null && before.day != day) ||
            (sourceId != null && before.sourceId != sourceId)) before else before.update() }
    }
    private companion object { val DEFAULT_OWNER = Any(); const val POLICY = "personal/source-policy" }
}
