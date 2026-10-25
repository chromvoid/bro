package assistant.mobile.core.personal

import assistant.mobile.core.AccountNamespace
import assistant.mobile.core.EventDomain
import assistant.mobile.core.EventInvalidation
import assistant.mobile.core.GeneratedStrings
import assistant.mobile.core.LocalizedString
import assistant.mobile.core.NativeHttpClient
import assistant.mobile.core.NativeIdentity
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
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
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
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.plus
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Clock
import kotlin.time.Instant

data class PersonalHistoryState(
    val namespace: AccountNamespace, val today: String, val end: String, val timezone: String, val locale: String,
    val canRead: Boolean, val canWrite: Boolean, val metric: String = "weight", val period: Int = 30, val mode: String = "result",
    val overview: PersonalQuery<PersonalOverview> = PersonalQuery(),
    val history: PersonalQuery<PersonalHistoryPage> = PersonalQuery(),
    val resolved: PersonalQuery<PersonalResolvedPage> = PersonalQuery(),
    val sensors: PersonalQuery<PersonalSensorPage> = PersonalQuery(),
    val raw: PersonalQuery<PersonalRawReportPage> = PersonalQuery(),
    val derived: PersonalQuery<PersonalDerivedScoresPage> = PersonalQuery(),
    val derivedVersions: PersonalQuery<PersonalDerivedArchivePage> = PersonalQuery(),
    val recordVersions: PersonalQuery<PersonalRecordVersions> = PersonalQuery(),
    val sourceAnalysis: PersonalQuery<PersonalSourceAnalysis> = PersonalQuery(),
    val derivedResult: PersonalQuery<PersonalArchivedResult> = PersonalQuery(),
    val backfillRun: PersonalQuery<PersonalBackfillRun> = PersonalQuery(),
    val activeRunId: String? = null, val submittedDays: Int? = null,
    val pending: List<Submission> = emptyList(), val busy: Boolean = false, val error: LocalizedString? = null,
) {
    val units: PersonalDisplayUnits get() = overview.data?.profile?.displayUnits ?: PersonalDisplayUnits.Metric
    val selectedSensors: List<PersonalSensorSample> get() = resolved.data?.series?.map(::decodePersonalSensorSample) ?: emptyList()
    val from: String get() = LocalDate.parse(end).plus(1 - period, DateTimeUnit.DAY).toString()
    val to: String get() = end
    val displayedRecords: List<PersonalObservation> get() {
        val data = history.data ?: return emptyList()
        val days = data.daily.filter { it.metric.resolution != null && (metric == "all" || it.metric.kind == metric) }
        if (mode == "all" || days.isEmpty()) return data.records.sortedByDescending { it.startAt }
        val ids = days.flatMap { it.metric.resolution!!.selected.map(PersonalFact::id) }.toSet()
        return data.records.filter { it.id in ids }.sortedByDescending { it.startAt }
    }
    val ownPending: List<Submission> get() = pending.filter { entry -> runCatching {
        personalOperationObject(entry.body).hText("kind") in setOf("personal.derived.backfill", "personal.sensor.exclude", "personal.sensor.restore")
    }.getOrDefault(false) }
    val backfillBlocked: Boolean get() = !canWrite || busy || pending.isNotEmpty() ||
        derivedVersions.data?.refresh?.pending == true || (derived.data?.pendingRuns ?: 0) > 0 ||
        (activeRunId != null && (backfillRun.data == null || backfillRun.data.state == "queued")) || period > 366
}

/** Reads and immutable captures belong to an account lease and one exact range. */
class PersonalHistoryController(
    private val scope: CoroutineScope,
    private val sessions: SessionManager,
    http: NativeHttpClient,
    private val submissions: SubmissionCoordinator,
    private val reader: PersonalHistoryReader = NativePersonalHistoryApi(http),
    private val clockMs: () -> Long = { Clock.System.now().toEpochMilliseconds() },
    private val overviewReader: PersonalReader = NativePersonalApi(http),
) {
    private val mutableState = MutableStateFlow<PersonalHistoryState?>(null)
    val state: StateFlow<PersonalHistoryState?> = mutableState.asStateFlow()
    private var boundLease: SessionLease? = null
    private val owners = mutableMapOf<Any, SessionLease>()
    private val visible = MutableStateFlow(false)
    private val reads = Mutex()
    private val mutations = Mutex()
    private var detailGeneration = 0L

    init {
        scope.launch {
            combine(sessions.leases, sessions.identity, visible) { lease, identity, active -> Triple(lease, identity, active) }
                .collectLatest { (lease, identity, active) ->
                    bind(lease, identity)
                    if (!active || lease == null || identity == null || state.value?.canRead != true) return@collectLatest
                    refresh()
                    while (visible.value && owned(lease)) {
                        delay(5000)
                        if (state.value?.activeRunId != null && (state.value?.backfillRun?.data == null || state.value?.backfillRun?.data?.state == "queued")) {
                            refreshBackfill()
                        }
                    }
                }
        }
    }

    fun activate(metric: String? = null, owner: Any = DEFAULT_OWNER) {
        bind(sessions.lease(), sessions.identity.value)
        val lease = boundLease ?: return
        owners[owner] = lease
        if (metric != null) {
            require(metric in PERSONAL_HISTORY_METRICS)
            mutableState.update { old -> old?.let { if (it.metric == metric) it else it.withRange(metric = metric, period = defaultPeriod(metric)) } }
        }
        visible.value = true
    }

    fun deactivate(owner: Any = DEFAULT_OWNER) {
        owners.remove(owner)
        visible.value = owners.values.any { it === boundLease }
    }

    suspend fun selectMetric(metric: String) {
        require(metric in PERSONAL_HISTORY_METRICS)
        val lease = boundLease ?: return
        change(lease) { withRange(metric = metric, period = defaultPeriod(metric)) }
        detailGeneration++
        refresh()
    }

    suspend fun selectPeriod(period: Int) {
        require(period in PERSONAL_HISTORY_PERIODS)
        val lease = boundLease ?: return
        change(lease) { withRange(period = period) }
        detailGeneration++
        refresh()
    }

    suspend fun movePeriod(direction: Int) {
        require(direction in setOf(-1, 1))
        val lease = boundLease ?: return
        change(lease) { withRange(end = minOf(today, LocalDate.parse(end).plus(direction * period, DateTimeUnit.DAY).toString())) }
        detailGeneration++
        refresh()
    }

    fun setMode(mode: String) {
        require(mode in setOf("result", "all"))
        val lease = boundLease ?: return
        change(lease) { copy(mode = mode) }
    }

    suspend fun refresh() {
        if (!visible.value) return
        val lease = sessions.lease() ?: return
        bind(lease, sessions.identity.value)
        if (!owned(lease) || state.value?.canRead != true) return
        refreshReads(lease)
        try { refreshPending(lease, recover = true) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { change(lease) { copy(error = GeneratedStrings.Assistant.draftStorageError) } }
    }

    suspend fun invalidate(invalidation: EventInvalidation) {
        if (invalidation is EventInvalidation.Domains && invalidation.values.none { it in setOf(EventDomain.Personal, EventDomain.Catalog) }) return
        val lease = boundLease ?: return
        change(lease) { copy(history = history.markStale(), resolved = resolved.markStale(), sensors = sensors.markStale(),
            raw = raw.markStale(), derived = derived.markStale(), derivedVersions = derivedVersions.markStale()) }
        refresh()
    }

    suspend fun versions(id: String, metric: String, sourceId: String? = null) {
        val lease = readableLease() ?: return
        val generation = ++detailGeneration
        change(lease) { clearDetails().copy(recordVersions = PersonalQuery(loading = true)) }
        query(lease, generation, { reader.versions(id, metric, sourceId, lease) }, { recordVersions }) { copy(recordVersions = it) }
    }

    suspend fun analysis(id: String) {
        val lease = readableLease() ?: return
        val generation = ++detailGeneration
        val expected = state.value?.history?.data?.analyses?.firstOrNull { it.id == id }
        change(lease) { clearDetails().copy(sourceAnalysis = PersonalQuery(loading = true)) }
        query(lease, generation, { reader.analysis(id, lease).also { saved ->
            if (expected != null) require(saved.summary.sourceId == expected.sourceId && saved.summary.providerRecordId == expected.providerRecordId && saved.summary.revision >= expected.revision)
        } }, { sourceAnalysis }) { copy(sourceAnalysis = it) }
    }

    suspend fun derivedDetail(id: String) {
        val lease = readableLease() ?: return
        // An archive/snapshot identifier must already belong to this range before its details open.
        val before = state.value ?: return
        val archived = before.derivedVersions.data?.items?.firstOrNull { it.resultId == id }
        val summary = before.derived.data?.days?.flatMap { it.scores }?.firstOrNull { it.resultId == id }
        if (archived == null && summary == null) return
        val generation = ++detailGeneration
        change(lease) { clearDetails().copy(derivedResult = PersonalQuery(loading = true)) }
        query(lease, generation, {
            reader.derivedResult(id, lease).also { saved ->
                if (archived != null) require(saved.resultId == archived.resultId && saved.definitionId == archived.definitionId &&
                    saved.formulaVersion == archived.formulaVersion && saved.date == archived.date && saved.timezone == archived.timezone && saved.sourceScope == archived.sourceScope)
                if (summary != null) require(saved.resultId == summary.resultId && saved.definitionId == summary.definitionId &&
                    saved.formulaVersion == summary.formulaVersion && saved.date == summary.context.date && saved.timezone == summary.context.timezone && saved.sourceScope == summary.context.sourceId)
            }
        }, { derivedResult }) { copy(derivedResult = it) }
    }

    fun closeDetails() {
        detailGeneration++
        val lease = boundLease ?: return
        change(lease) { clearDetails() }
    }

    suspend fun backfill() = action(write = true) { lease ->
        refreshPending(lease)
        val before = state.value ?: return@action
        if (before.backfillBlocked) return@action
        change(lease) { copy(busy = true, error = null) }
        try {
            val operation = personalBackfillOperation(before.from, before.to, before.timezone)
            val result = submissions.submit(lease, NewSubmission(PERSONAL_OPERATIONS, PERSONAL_LOOKUP, operation.bytes(), "personal"))
            settle(lease, result)
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { change(lease) { copy(error = GeneratedStrings.Errors.network) } }
        finally { change(lease) { copy(busy = false) }; refreshPending(lease) }
    }

    suspend fun exclude(sample: PersonalSensorSample) = action(write = true) { lease ->
        refreshPending(lease)
        val before = state.value ?: return@action
        if (before.busy || before.pending.isNotEmpty()) return@action
        if ((before.sensors.data?.items.orEmpty() + before.selectedSensors).none { it.id == sample.id && it.revision == sample.revision }) return@action
        change(lease) { copy(busy = true, error = null) }
        try {
            val operation = personalSensorExclusionOperation(sample)
            settle(lease, submissions.submit(lease, NewSubmission(PERSONAL_OPERATIONS, PERSONAL_LOOKUP, operation.bytes(), "personal",
                revisions = operation.expectedRevisions.mapValues { it.value.toString() })))
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { change(lease) { copy(error = GeneratedStrings.Errors.network) } }
        finally { change(lease) { copy(busy = false) }; refreshPending(lease) }
    }

    /** Recovery only follows the original lookup; no second POST exists in this controller. */
    suspend fun check(key: String) = action { lease ->
        val pending = submissions.pending(lease).firstOrNull { it.key == key && it.isHistoryOperation() } ?: return@action
        change(lease) { copy(busy = true, error = null) }
        try { settle(lease, submissions.check(lease, pending.key)) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { change(lease) { copy(error = GeneratedStrings.Errors.network) } }
        finally { change(lease) { copy(busy = false) }; refreshPending(lease) }
    }

    suspend fun refreshBackfill() {
        val lease = readableLease() ?: return
        if (!visible.value) return
        val id = state.value?.activeRunId ?: return
        val old = state.value?.backfillRun?.data?.state
        change(lease) { copy(backfillRun = backfillRun.copy(loading = true, error = null)) }
        try {
            val result = reader.backfillRun(id, lease)
            if (state.value?.activeRunId != id) return
            change(lease) { copy(backfillRun = PersonalQuery(result)) }
            if (result.state != "queued" && result.state != old) refreshReads(lease)
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { if (state.value?.activeRunId == id) change(lease) {
            copy(backfillRun = backfillRun.copy(loading = false, error = failure.messageKey(), stale = backfillRun.data != null))
        } }
    }

    private suspend fun action(write: Boolean = false, block: suspend (SessionLease) -> Unit) {
        val lease = readableLease() ?: return
        if (write && state.value?.canWrite != true) return
        scope.async { mutations.withLock {
            if (!owned(lease)) return@withLock
            try { block(lease) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { change(lease) { copy(busy = false, error = GeneratedStrings.Assistant.draftStorageError) } }
        } }.await()
    }

    private suspend fun settle(lease: SessionLease, submission: Submission) {
        if (!owned(lease) || !submission.isHistoryOperation()) return
        if (!submission.isBackfill()) {
            if (submission.state == SubmissionState.RESOLVED && submission.response != null) {
                validatePersonalSensorSubmissionReceipt(submission, submission.response)
                refreshReads(lease)
                if (owned(lease) && state.value?.sensors?.let { it.data != null && it.error == null && !it.stale } == true &&
                    state.value?.resolved?.let { it.data != null && it.error == null && !it.stale } == true) submissions.acknowledge(lease, submission.key)
            } else if (submission.state == SubmissionState.REJECTED) {
                change(lease) { copy(error = if (submission.failureStatus == 409) GeneratedStrings.Personal.conflict else GeneratedStrings.Errors.server) }
                refreshReads(lease)
            }
            return
        }
        if (submission.state == SubmissionState.RESOLVED && submission.response != null) {
            validatePersonalBackfillSubmissionReceipt(submission, submission.response)
            val receipt = decodePersonalBackfillReceipt(submission.response)
            val payload = personalOperationObject(submission.body).hObject("payload")
            val before = state.value ?: return
            if (receipt.from != before.from || receipt.to != before.to || payload.hText("time_zone") != before.timezone) return
            change(lease) { copy(activeRunId = receipt.runId, submittedDays = receipt.scheduledDays, backfillRun = PersonalQuery(), error = null) }
            refreshBackfill()
            if (owned(lease) && state.value?.backfillRun?.data?.runId == receipt.runId) submissions.acknowledge(lease, submission.key)
        } else if (submission.state == SubmissionState.REJECTED) {
            change(lease) { copy(error = if (submission.failureStatus == 409) GeneratedStrings.Personal.conflict else GeneratedStrings.Errors.server) }
        }
    }

    private suspend fun refreshPending(lease: SessionLease, recover: Boolean = false) {
        val entries = submissions.pending(lease).filter { it.endpoint == PERSONAL_OPERATIONS }
        for (entry in entries.filter { it.isHistoryOperation() }) {
            val result = if (recover && entry.state in setOf(SubmissionState.SENDING, SubmissionState.UNCERTAIN, SubmissionState.ACCEPTED))
                submissions.check(lease, entry.key) else entry
            if (result.state == SubmissionState.RESOLVED) settle(lease, result)
        }
        val remaining = submissions.pending(lease).filter { it.endpoint == PERSONAL_OPERATIONS && it.state !in setOf(SubmissionState.REJECTED, SubmissionState.RESOLVED) }
        change(lease) { copy(pending = remaining) }
    }

    private suspend fun refreshReads(lease: SessionLease) = reads.withLock {
        val before = state.value ?: return@withLock
        if (!owned(lease) || !before.canRead || !visible.value) return@withLock
        val key = before.rangeKey()
        val sensor = before.metric in PERSONAL_SENSOR_KINDS
        val derivedMode = before.metric == "derived_scores"
        val rawMetric = when (before.metric) { "temperature" -> "temperature"; "all" -> "workouts"; else -> null }
        change(lease) { copy(overview = overview.loading(), history = if (!sensor && !derivedMode) history.loading() else PersonalQuery(),
            resolved = if (sensor) resolved.loading() else PersonalQuery(), sensors = if (sensor) sensors.loading() else PersonalQuery(),
            raw = if (rawMetric != null) raw.loading() else PersonalQuery(), derived = if (derivedMode) derived.loading() else PersonalQuery(),
            derivedVersions = if (derivedMode) derivedVersions.loading() else PersonalQuery()) }
        coroutineScope {
            val jobs = mutableListOf<kotlinx.coroutines.Deferred<Unit>>()
            jobs += async { rangeQuery(lease, key, { overviewReader.overview(before.today, lease) }, { overview }) { copy(overview = it) } }
            if (!sensor && !derivedMode) jobs += async {
                rangeQuery(lease, key, { reader.history(before.from, before.to, before.metric.takeUnless { it == "all" }, lease) }, { history }) { copy(history = it) }
            }
            if (sensor) {
                jobs += async { rangeQuery(lease, key, { reader.resolved(before.from, before.to, before.metric, lease) }, { resolved }) { copy(resolved = it) } }
                jobs += async { rangeQuery(lease, key, { reader.sensors(before.from, before.to, before.metric, before.timezone, lease) }, { sensors }) { copy(sensors = it) } }
            }
            if (rawMetric != null) jobs += async { rangeQuery(lease, key, { reader.raw(before.from, before.to, rawMetric, lease) }, { raw }) { copy(raw = it) } }
            if (derivedMode) {
                jobs += async { rangeQuery(lease, key, { reader.derived(before.from, before.to, before.timezone, lease) }, { derived }) { copy(derived = it) } }
                jobs += async { rangeQuery(lease, key, { reader.derivedVersions(before.from, before.to, before.timezone, lease) }, { derivedVersions }) { copy(derivedVersions = it) } }
            }
            jobs.awaitAll()
        }
    }

    private suspend fun <T> rangeQuery(lease: SessionLease, key: List<String>, fetch: suspend () -> T,
        current: PersonalHistoryState.() -> PersonalQuery<T>, apply: PersonalHistoryState.(PersonalQuery<T>) -> PersonalHistoryState) {
        try {
            val result = fetch()
            if (state.value?.rangeKey() == key) change(lease) { apply(PersonalQuery(result)) }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) {
            if (state.value?.rangeKey() == key) change(lease) { apply(current().copy(loading = false, error = failure.messageKey(), stale = current().data != null)) }
        }
    }

    private suspend fun <T> query(lease: SessionLease, generation: Long, fetch: suspend () -> T,
        current: PersonalHistoryState.() -> PersonalQuery<T>, apply: PersonalHistoryState.(PersonalQuery<T>) -> PersonalHistoryState) {
        try {
            val result = fetch()
            if (generation == detailGeneration) change(lease) { apply(PersonalQuery(result)) }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { if (generation == detailGeneration) change(lease) { apply(current().copy(loading = false, error = failure.messageKey())) } }
    }

    private fun bind(lease: SessionLease?, identity: NativeIdentity?) {
        if (lease == null || identity == null || lease.namespace.userId != identity.id) {
            boundLease = null; owners.clear(); visible.value = false; mutableState.value = null; detailGeneration++
            return
        }
        val changed = boundLease !== lease
        if (changed) { owners.entries.removeAll { it.value !== lease }; visible.value = owners.isNotEmpty(); detailGeneration++ }
        boundLease = lease
        val today = Instant.fromEpochMilliseconds(clockMs()).toLocalDateTime(TimeZone.of(identity.timezone)).date.toString()
        mutableState.update { old ->
            val fresh = PersonalHistoryState(lease.namespace, today, today, identity.timezone, identity.locale,
                "personal.read" in identity.scopes, "personal.write" in identity.scopes)
            if (changed || old == null || !fresh.canRead) fresh
            else if (old.timezone != identity.timezone) fresh.copy(metric = old.metric, period = old.period, end = minOf(old.end, today))
            else old.copy(today = today, canRead = fresh.canRead, canWrite = fresh.canWrite, locale = identity.locale)
        }
    }

    private fun readableLease(): SessionLease? = sessions.lease()?.takeIf { owned(it) && state.value?.canRead == true }
    private fun owned(lease: SessionLease): Boolean = boundLease === lease && sessions.isCurrent(lease)
    private fun change(lease: SessionLease, update: PersonalHistoryState.() -> PersonalHistoryState) {
        if (!owned(lease) || "personal.read" !in (sessions.identity.value?.scopes ?: emptyList())) return
        mutableState.update { old -> if (old?.canRead == true) old.update() else old }
    }

    private fun PersonalHistoryState.rangeKey(): List<String> = listOf(metric, from, to, timezone)
    private fun PersonalHistoryState.withRange(metric: String = this.metric, period: Int = this.period, end: String = this.end): PersonalHistoryState =
        copy(metric = metric, period = period, end = end, history = PersonalQuery(), resolved = PersonalQuery(), sensors = PersonalQuery(),
            raw = PersonalQuery(), derived = PersonalQuery(), derivedVersions = PersonalQuery(), recordVersions = PersonalQuery(),
            sourceAnalysis = PersonalQuery(), derivedResult = PersonalQuery(), activeRunId = null, submittedDays = null, backfillRun = PersonalQuery(), error = null)
    private fun PersonalHistoryState.clearDetails(): PersonalHistoryState = copy(recordVersions = PersonalQuery(), sourceAnalysis = PersonalQuery(), derivedResult = PersonalQuery())
    private fun <T> PersonalQuery<T>.markStale(): PersonalQuery<T> = copy(stale = data != null)
    private fun <T> PersonalQuery<T>.loading(): PersonalQuery<T> = copy(loading = true, error = null)
    private fun Exception.messageKey(): LocalizedString = if (this is PersonalReadException) nativeError(outcome) else GeneratedStrings.Errors.server
    private fun Submission.isHistoryOperation(): Boolean = endpoint == PERSONAL_OPERATIONS && runCatching {
        personalOperationObject(body).hText("kind") in setOf("personal.derived.backfill", "personal.sensor.exclude", "personal.sensor.restore")
    }.getOrDefault(false)
    private fun Submission.isBackfill(): Boolean = endpoint == PERSONAL_OPERATIONS && runCatching { personalOperationObject(body).hText("kind") == "personal.derived.backfill" }.getOrDefault(false)

    private companion object {
        val DEFAULT_OWNER = Any()
        fun defaultPeriod(metric: String): Int = if (metric == "body") 365 else if (metric in PERSONAL_SENSOR_KINDS) 7 else 30
    }
}
