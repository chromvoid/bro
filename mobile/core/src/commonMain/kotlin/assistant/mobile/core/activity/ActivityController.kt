package assistant.mobile.core.activity

import assistant.mobile.core.*
import assistant.mobile.core.journal.*
import assistant.mobile.core.personal.PersonalOverview
import assistant.mobile.core.personal.PERSONAL_OPERATIONS
import assistant.mobile.core.personal.PERSONAL_LOOKUP
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.datetime.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import kotlin.time.Clock
import kotlin.time.Instant

data class ActivityQuery<T>(val data: T? = null, val loading: Boolean = false, val error: LocalizedString? = null, val stale: Boolean = false)

data class ActivityState(
    val namespace: AccountNamespace, val selectedDay: String, val today: String,
    val timezone: String, val locale: String, val canRead: Boolean, val canWrite: Boolean,
    val canPersonal: Boolean, val canSteps: Boolean, val anchor: String, val range: ActivityRange, val period: Int = 30,
    val tab: String = "overview",
    val overview: ActivityQuery<PersonalOverview> = ActivityQuery(),
    val sessions: ActivityQuery<ActivitySessions> = ActivityQuery(), val summary: ActivityQuery<ActivitySummary> = ActivityQuery(),
    val catalog: ActivityQuery<List<ExerciseDefinition>> = ActivityQuery(), val session: ActivityQuery<ActivitySession> = ActivityQuery(),
    val sessionEffort: ActivityQuery<ActivityEffort?> = ActivityQuery(), val progress: ActivityQuery<ExerciseProgress> = ActivityQuery(),
    val steps: ActivityQuery<ActivityStepsReply> = ActivityQuery(),
    val weekSteps: ActivityQuery<ActivityHistory> = ActivityQuery(),
    val stepsHistory: ActivityQuery<ActivityHistory> = ActivityQuery(), val activeHistory: ActivityQuery<ActivityHistory> = ActivityQuery(),
    val runningSteps: ActivityQuery<ActivityResolvedPage> = ActivityQuery(), val runningWorkouts: ActivityQuery<ActivityResolvedPage> = ActivityQuery(),
    val otherWorkouts: ActivityQuery<ActivityResolvedPage> = ActivityQuery(),
    val reports: ActivityQuery<ActivityReportPage> = ActivityQuery(),
    val selectedSessionId: String? = null, val progressExerciseId: String? = null,
    val workoutId: String? = null, val workoutSourceId: String? = null,
    val heartRate: ActivityQuery<List<WorkoutHeartRatePage>> = ActivityQuery(),
    val track: ActivityQuery<List<WorkoutTrackPage>> = ActivityQuery(), val trackVersions: ActivityQuery<List<WorkoutTrackVersionsPage>> = ActivityQuery(),
    val sessionDraft: ActivitySessionDraft? = null, val setDraft: ActivitySetDraft? = null,
    val exerciseDraft: ActivityExerciseDraft? = null, val exerciseNoteDraft: ActivityExerciseNoteDraft? = null,
    val stepsDraft: ActivityStepsDraft? = null,
    val pending: List<Submission> = emptyList(), val receipt: ActivityReceipt? = null,
    val busy: Boolean = false, val conflict: Boolean = false, val conflictDraft: String? = null, val error: LocalizedString? = null,
)

/** Screen ownership and account leases bound reads, drafts and durable operation receipts. */
class ActivityController(
    private val scope: CoroutineScope, private val sessions: SessionManager, http: NativeHttpClient,
    private val submissions: SubmissionCoordinator, private val drafts: AccountDrafts,
    private val clockMs: () -> Long = { Clock.System.now().toEpochMilliseconds() },
    private val reader: ActivityReader = NativeActivityApi(http),
) {
    private val mutableState = MutableStateFlow<ActivityState?>(null)
    val state: StateFlow<ActivityState?> = mutableState.asStateFlow()
    private val visible = MutableStateFlow(false)
    private val activeOwners = mutableMapOf<Any, SessionLease>()
    private var boundLease: SessionLease? = null
    private val readLock = Mutex()
    private val mutationLock = Mutex()
    private val draftLock = Mutex()
    private val draftVersions = mutableMapOf<String, Long>()

    init {
        scope.launch {
            combine(sessions.leases, sessions.identity, visible) { lease, identity, active -> Triple(lease, identity, active) }
                .collectLatest { (lease, identity, active) ->
                    bind(lease, identity)
                    if (!active || lease == null || identity == null) return@collectLatest
                    restoreDrafts(lease)
                    refresh()
                }
        }
    }

    fun activate(day: String? = null, owner: Any = DEFAULT_OWNER) {
        bind(sessions.lease(), sessions.identity.value)
        val lease = boundLease ?: return
        activeOwners[owner] = lease
        day?.let { value ->
            require(validDay(value))
            mutableState.update { it?.withDay(minOf(value, it.today)) }
        }
        visible.value = true
    }

    fun deactivate(owner: Any = DEFAULT_OWNER) {
        activeOwners.remove(owner)
        visible.value = activeOwners.values.any { it === boundLease }
    }

    suspend fun selectDay(day: String) {
        require(validDay(day) && day <= (state.value?.today ?: day))
        mutableState.update { it?.withDay(day) }
        refresh()
    }

    suspend fun selectRange(period: Int, anchor: String, kind: String? = null) {
        require(period in listOf(7, 30, 90) && validDay(anchor) && anchor <= (state.value?.today ?: anchor) && (kind == null || kind in ACTIVITY_KINDS))
        val range = rangeFor(anchor, period, kind)
        mutableState.update { before -> before?.let { if (it.range == range) it else it.copy(period = period, anchor = anchor, range = range,
            sessions = ActivityQuery(), summary = ActivityQuery(), progress = ActivityQuery(), stepsHistory = ActivityQuery(), activeHistory = ActivityQuery(),
            runningSteps = ActivityQuery(), runningWorkouts = ActivityQuery(), otherWorkouts = ActivityQuery(), reports = ActivityQuery()) } }
        refresh()
    }

    suspend fun selectTab(tab: String) {
        require(tab in listOf("overview", "journal", "progress", "exercises"))
        mutableState.update { before -> before?.let { if (it.tab == tab) it else it.copy(tab = tab,
            sessions = ActivityQuery(), runningSteps = ActivityQuery(), runningWorkouts = ActivityQuery(), otherWorkouts = ActivityQuery(), reports = ActivityQuery()) } }
        refresh()
    }

    suspend fun openSession(id: String) {
        require(id.isNotBlank())
        bind(sessions.lease(), sessions.identity.value)
        mutableState.update { it?.let { value -> if (value.selectedSessionId == id) value else value.copy(selectedSessionId = id, session = ActivityQuery(), sessionEffort = ActivityQuery(), error = null) } }
        refresh()
    }

    fun closeSession() { mutableState.update { it?.copy(selectedSessionId = null, session = ActivityQuery(), sessionEffort = ActivityQuery()) } }

    suspend fun openProgress(exerciseId: String) {
        require(exerciseId.isNotBlank())
        mutableState.update { it?.let { value -> if (value.progressExerciseId == exerciseId) value else value.copy(progressExerciseId = exerciseId, progress = ActivityQuery()) } }
        refresh()
    }

    fun closeProgress() { mutableState.update { it?.copy(progressExerciseId = null, progress = ActivityQuery()) } }

    suspend fun openWorkout(id: String, sourceId: String? = null) {
        val before = state.value
        val source = sourceId ?: (before?.runningWorkouts?.data?.workouts.orEmpty() + before?.otherWorkouts?.data?.workouts.orEmpty()).firstOrNull { it.id == id || it.workoutId == id }?.sourceId ?: return
        require(id.isNotBlank() && source.isNotBlank())
        mutableState.update { it?.let { value -> if (value.workoutId == id && value.workoutSourceId == source) value else value.copy(
            workoutId = id, workoutSourceId = source, heartRate = ActivityQuery(), track = ActivityQuery(), trackVersions = ActivityQuery()) } }
        refresh()
    }

    fun closeWorkout() { mutableState.update { it?.copy(workoutId = null, workoutSourceId = null, heartRate = ActivityQuery(), track = ActivityQuery(), trackVersions = ActivityQuery()) } }

    suspend fun tile(z: Int, x: Int, y: Int): ByteArray? {
        val lease = sessions.lease() ?: return null
        if (!owned(lease) || state.value?.canPersonal != true) return null
        return try { reader.tile(z, x, y, lease).takeIf { owned(lease) && state.value?.canPersonal == true } }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { null }
    }

    suspend fun refresh() {
        if (!visible.value) return
        val lease = sessions.lease() ?: return
        bind(lease, sessions.identity.value)
        if (state.value?.canRead != true) return
        refreshReads(lease)
        try { refreshPending(lease, true) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { change(lease) { copy(error = GeneratedStrings.Assistant.draftStorageError) } }
    }

    suspend fun invalidate(invalidation: EventInvalidation) {
        if (invalidation is EventInvalidation.Domains && invalidation.values.none { it in setOf(EventDomain.Activity, EventDomain.Personal) }) return
        mutableState.update { it?.copy(overview = it.overview.stale(), sessions = it.sessions.stale(), summary = it.summary.stale(),
            catalog = it.catalog.stale(), session = it.session.stale(), sessionEffort = it.sessionEffort.stale(), progress = it.progress.stale(),
            steps = it.steps.stale(), weekSteps = it.weekSteps.stale(), stepsHistory = it.stepsHistory.stale(), activeHistory = it.activeHistory.stale(), runningSteps = it.runningSteps.stale(),
            runningWorkouts = it.runningWorkouts.stale(), otherWorkouts = it.otherWorkouts.stale(), reports = it.reports.stale(), heartRate = it.heartRate.stale(), track = it.track.stale(), trackVersions = it.trackVersions.stale()) }
        refresh()
    }

    fun beginSession(sessionId: String? = null, kind: String = "walking") {
        val before = state.value ?: return
        if (!before.canWrite || before.busy || before.sessionDraft != null) return
        val draft = if (sessionId == null) ActivitySessionDraft(date = before.selectedDay, timezone = before.timezone, kind = kind)
        else (before.session.data?.takeIf { it.id == sessionId } ?: before.sessions.data?.sessions?.firstOrNull { it.id == sessionId })?.let(::activitySessionDraft) ?: return
        updateSessionDraft(draft)
    }

    fun updateSessionDraft(draft: ActivitySessionDraft) = edit(SESSION) { copy(sessionDraft = draft) }
    suspend fun saveSession() = mutate { lease ->
        val before = state.value ?: return@mutate
        val draft = before.sessionDraft ?: return@mutate
        val revision = if (before.conflictDraft == SESSION && draft.sessionId != null) freshSession(draft.sessionId).revision else draft.revision
        deliver(lease, sessionActivityOperation(draft, before.today, clockMs(), revision), SESSION)
    }
    suspend fun deleteSession() = mutate { lease -> state.value?.session?.data?.let { deliver(lease, deleteSessionActivityOperation(it)) } }

    suspend fun addExercise(exerciseId: String) = mutate { lease ->
        val session = state.value?.session?.data ?: return@mutate
        require(state.value?.catalog?.data?.any { it.id == exerciseId } == true)
        deliver(lease, sessionExerciseActivityOperation(session, exerciseId))
    }

    suspend fun deleteExercise(exerciseId: String) = mutate { lease ->
        val session = state.value?.session?.data ?: return@mutate
        deliver(lease, deleteSessionExerciseActivityOperation(session, session.exercises.first { it.id == exerciseId }))
    }

    fun beginSet(exerciseId: String, setId: String? = null) {
        val before = state.value ?: return
        if (!before.canWrite || before.busy || before.setDraft != null) return
        val session = before.session.data ?: return
        val exercise = session.exercises.firstOrNull { it.id == exerciseId } ?: return
        val set = setId?.let { id -> exercise.sets.firstOrNull { it.id == id } ?: return }
        updateSetDraft(activitySetDraft(session, exercise, set))
    }
    fun updateSetDraft(draft: ActivitySetDraft) = edit(SET) { copy(setDraft = draft) }
    suspend fun saveSet() = mutate { lease ->
        val before = state.value ?: return@mutate
        val draft = before.setDraft ?: return@mutate
        val revisions = if (before.conflictDraft == SET) freshRevisions(draft.sessionId, draft.sessionExerciseId, draft.setId) else draft.expectedRevisions
        deliver(lease, setActivityOperation(draft, revisions), SET)
    }
    suspend fun deleteSet(exerciseId: String, setId: String) = mutate { lease ->
        val session = state.value?.session?.data ?: return@mutate
        val exercise = session.exercises.first { it.id == exerciseId }
        deliver(lease, deleteSetActivityOperation(session, exercise, exercise.sets.first { it.id == setId }))
    }

    fun beginExercise(exerciseId: String? = null) {
        val before = state.value ?: return
        if (!before.canWrite || before.busy || before.exerciseDraft != null) return
        val exercise = exerciseId?.let { id -> before.catalog.data?.firstOrNull { it.id == id } ?: return }
        updateExerciseDraft(exercise?.let { ActivityExerciseDraft(it.id, it.revision, it.name, it.equipment.orEmpty(), it.variant.orEmpty(), it.loadBasis) } ?: ActivityExerciseDraft())
    }
    fun updateExerciseDraft(draft: ActivityExerciseDraft) = edit(EXERCISE) { copy(exerciseDraft = draft) }
    suspend fun saveExercise() = mutate { lease ->
        val before = state.value ?: return@mutate
        val draft = before.exerciseDraft ?: return@mutate
        val revision = if (before.conflictDraft == EXERCISE && draft.exerciseId != null) {
            require(!before.catalog.stale && before.catalog.error == null)
            requireNotNull(before.catalog.data?.firstOrNull { it.id == draft.exerciseId }).revision
        } else draft.revision
        deliver(lease, exerciseActivityOperation(draft, revision), EXERCISE)
    }
    suspend fun deleteCatalogExercise(exerciseId: String) = mutate { lease ->
        val exercise = state.value?.catalog?.data?.firstOrNull { it.id == exerciseId } ?: return@mutate
        deliver(lease, deleteCatalogExerciseActivityOperation(exercise))
    }

    fun beginExerciseNote(exerciseId: String) {
        val before = state.value ?: return
        if (!before.canWrite || before.busy || before.exerciseNoteDraft != null) return
        val session = before.session.data ?: return
        val exercise = session.exercises.firstOrNull { it.id == exerciseId } ?: return
        updateExerciseNoteDraft(ActivityExerciseNoteDraft(session.id, exercise.id, activityExpectedRevisions(session, exercise), exercise.note.orEmpty()))
    }
    fun updateExerciseNoteDraft(draft: ActivityExerciseNoteDraft) = edit(NOTE) { copy(exerciseNoteDraft = draft) }
    suspend fun saveExerciseNote() = mutate { lease ->
        val before = state.value ?: return@mutate
        val draft = before.exerciseNoteDraft ?: return@mutate
        val revisions = if (before.conflictDraft == NOTE) freshRevisions(draft.sessionId, draft.sessionExerciseId) else draft.expectedRevisions
        deliver(lease, noteActivityOperation(draft, revisions), NOTE)
    }

    fun beginSteps() {
        val before = state.value ?: return
        if (!before.canSteps || before.busy || before.stepsDraft != null || before.steps.data?.date != before.selectedDay || before.steps.error != null || before.steps.stale) return
        val override = before.steps.data?.override
        edit(STEPS) { copy(stepsDraft = ActivityStepsDraft(selectedDay, override?.takeIf { it.active }?.value?.toString().orEmpty(), override?.id, override?.revision ?: 0)) }
    }
    fun updateStepsDraft(draft: ActivityStepsDraft) = edit(STEPS) { copy(stepsDraft = draft) }
    suspend fun saveSteps() = writeSteps(false)
    suspend fun clearSteps() = writeSteps(true)
    private suspend fun writeSteps(clear: Boolean) = mutate(personal = true) { lease ->
        val before = state.value ?: return@mutate
        val draft = before.stepsDraft ?: return@mutate
        val current = if (before.conflictDraft == STEPS) {
            require(before.steps.data?.date == draft.date && !before.steps.stale && before.steps.error == null)
            before.steps.data?.override
        } else null
        val operation = if (before.conflictDraft == STEPS) stepsActivityOperation(draft, before.today, clear, current?.id, current?.revision ?: 0)
        else stepsActivityOperation(draft, before.today, clear)
        deliver(lease, operation, STEPS)
    }

    suspend fun dismissDraft(kind: String = "session") {
        val target = "activity/$kind".also { require(it in TARGETS) }
        val lease = sessions.lease() ?: return
        if (!owned(lease) || state.value?.busy == true) return
        draftVersions[target] = (draftVersions[target] ?: 0) + 1
        draftLock.withLock { drafts.delete(lease, target) }
        change(lease) { withoutDraft(target).copy(conflict = false, conflictDraft = null, error = null) }
    }

    /** An unknown outcome only reads the original idempotency key; it never posts again. */
    suspend fun check(key: String) = action { lease ->
        change(lease) { copy(busy = true, error = null) }
        try {
            val original = submissions.pending(lease).firstOrNull { ownsSubmission(it) && it.key == key } ?: return@action
            settle(lease, submissions.check(lease, original.key))
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { change(lease) { copy(error = GeneratedStrings.Errors.network) } }
        finally { change(lease) { copy(busy = false) }; pendingOrError(lease) }
    }
    suspend fun recover(key: String) { activate(); check(key) }
    fun clearReceipt() { mutableState.update { it?.copy(receipt = null) } }

    private fun freshSession(id: String): ActivitySession {
        val query = requireNotNull(state.value).session
        require(query.error == null && !query.stale)
        return requireNotNull(query.data?.takeIf { it.id == id })
    }
    private fun freshRevisions(sessionId: String, exerciseId: String, setId: String? = null): Map<String, Long> {
        val session = freshSession(sessionId)
        val exercise = session.exercises.first { it.id == exerciseId }
        return activityExpectedRevisions(session, exercise, setId?.let { id -> exercise.sets.first { it.id == id } })
    }

    private fun edit(target: String, update: ActivityState.() -> ActivityState) {
        val lease = sessions.lease() ?: return
        if (!owned(lease) || (if (target == STEPS) state.value?.canSteps else state.value?.canWrite) != true || state.value?.busy == true) return
        val version = (draftVersions[target] ?: 0) + 1
        draftVersions[target] = version
        change(lease) { update().copy(error = null) }
        val json = snapshot(target) ?: return
        scope.launch {
            try { persistDraft(lease, target, json, version) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { change(lease) { copy(error = GeneratedStrings.Assistant.draftStorageError) } }
        }
    }

    private suspend fun action(block: suspend (SessionLease) -> Unit) {
        val lease = sessions.lease() ?: return
        scope.async { mutationLock.withLock { if (owned(lease)) block(lease) } }.await()
    }
    private suspend fun mutate(personal: Boolean = false, block: suspend (SessionLease) -> Unit) = action { lease ->
        if ((if (personal) state.value?.canSteps else state.value?.canWrite) != true) return@action
        try {
            refreshPending(lease)
            if (state.value?.pending?.isNotEmpty() == true) return@action
            change(lease) { copy(busy = true, error = null) }
            block(lease)
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (invalid: ActivityValidationException) { change(lease) { copy(error = invalid.messageKey) } }
        catch (_: IllegalArgumentException) { change(lease) { copy(error = GeneratedStrings.Errors.invalid) } }
        catch (_: StaleJournalSession) { /* A replacement lease owns presentation. */ }
        catch (_: Exception) { change(lease) { copy(error = GeneratedStrings.Assistant.draftStorageError) } }
        finally { change(lease) { copy(busy = false) }; if (owned(lease)) pendingOrError(lease) }
    }
    private suspend fun deliver(lease: SessionLease, operation: ActivityOperation, target: String? = null) {
        target?.let { snapshot(it)?.let { json -> persistDraft(lease, it, json, draftVersions[it] ?: 0) } }
        val personal = operation.kind.startsWith("personal.steps.")
        settle(lease, submissions.submit(lease, NewSubmission(if (personal) PERSONAL_OPERATIONS else ACTIVITY_OPERATIONS, if (personal) PERSONAL_LOOKUP else ACTIVITY_LOOKUP, operation.bytes(), if (personal) "personal" else "activity",
            revisions = operation.expectedRevisions.mapValues { it.value.toString() }, draftTarget = target)))
    }
    private suspend fun settle(lease: SessionLease, result: Submission) {
        if (!owned(lease)) return
        if (result.state == SubmissionState.RESOLVED && result.response != null) {
            applyReceipt(lease, result)
            if (visible.value) refreshReads(lease)
            if (authoritativeReadSucceeded(result)) submissions.acknowledge(lease, result.key)
        } else if (result.state == SubmissionState.REJECTED) {
            val conflict = result.failureStatus == 409
            change(lease) { copy(conflict = conflict, conflictDraft = if (conflict) result.draftTarget else null,
                error = if (conflict) GeneratedStrings.Errors.conflict else GeneratedStrings.Errors.server) }
            if (visible.value) refreshReads(lease)
        }
    }
    private suspend fun applyReceipt(lease: SessionLease, result: Submission) {
        val response = requireNotNull(result.response)
        if (!validateActivityStepsSubmissionReceipt(result, response)) validateActivitySubmissionReceipt(result, response)
        val receipt = decodeActivityReceipt(response)
        change(lease) { copy(receipt = receipt, conflict = false, conflictDraft = null, error = null) }
        val savedSession = receipt.raw["session"]?.jsonObject?.let(::decodeActivitySession)
        val deletedSession = receipt.kind == "activity.session.delete"
        change(lease) {
            if (deletedSession && selectedSessionId == receipt.raw["session_id"]?.jsonPrimitive?.content) copy(selectedSessionId = null, session = ActivityQuery(), sessionEffort = ActivityQuery())
            else if (savedSession != null && (selectedSessionId == null && receipt.kind == "activity.session.add" || selectedSessionId == savedSession.id)) copy(selectedSessionId = savedSession.id, session = ActivityQuery(savedSession))
            else this
        }
        val target = result.draftTarget?.takeIf { it in TARGETS } ?: return
        val json = snapshot(target) ?: return
        if (!draftMatchesSubmission(target, result)) return
        val version = draftVersions[target] ?: 0
        draftLock.withLock {
            if (drafts.read(lease, target)?.json?.decodeToString() != json || snapshot(target) != json || (draftVersions[target] ?: 0) != version) return@withLock
            drafts.delete(lease, target)
            change(lease) { withoutDraft(target) }
        }
    }
    private fun draftMatchesSubmission(target: String, submission: Submission): Boolean = runCatching {
        val before = state.value ?: return@runCatching false
        val command = activityOperationObject(submission.body)
        val expected = command.getValue("expected_revisions").jsonObject.mapValues { it.value.jsonPrimitive.long }
        val operation = when (target) {
            SESSION -> before.sessionDraft?.let { sessionActivityOperation(it, before.today, clockMs(), it.sessionId?.let { id -> expected[id] } ?: it.revision) }
            SET -> before.setDraft?.let { setActivityOperation(it, expected) }
            EXERCISE -> before.exerciseDraft?.let { exerciseActivityOperation(it, it.exerciseId?.let { id -> expected[id] } ?: it.revision) }
            NOTE -> before.exerciseNoteDraft?.let { noteActivityOperation(it, expected) }
            STEPS -> before.stepsDraft?.let { stepsActivityOperation(it, before.today, command["kind"]?.jsonPrimitive?.content == "personal.steps.clear", expected.keys.singleOrNull(), expected.values.singleOrNull() ?: 0) }
            else -> null
        }
        operation?.let { activityOperationObject(it.bytes()) == command } == true
    }.getOrDefault(false)
    private suspend fun refreshPending(lease: SessionLease, consumeResolved: Boolean = false) {
        val entries = submissions.pending(lease).filter(::ownsSubmission).map { entry ->
            if (consumeResolved && entry.state in setOf(SubmissionState.SENDING, SubmissionState.UNCERTAIN, SubmissionState.ACCEPTED)) submissions.check(lease, entry.key) else entry
        }
        if (consumeResolved && owned(lease)) {
            val resolved = entries.filter { it.state == SubmissionState.RESOLVED }.sortedBy { it.createdAtMillis }
            for (entry in resolved) applyReceipt(lease, entry)
            if (resolved.isNotEmpty()) refreshReads(lease)
            for (entry in resolved) if (authoritativeReadSucceeded(entry)) submissions.acknowledge(lease, entry.key)
        }
        change(lease) { copy(pending = entries.filter { it.state !in setOf(SubmissionState.RESOLVED, SubmissionState.REJECTED) }) }
    }
    private suspend fun pendingOrError(lease: SessionLease) {
        try { refreshPending(lease) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { change(lease) { copy(error = GeneratedStrings.Assistant.draftStorageError) } }
    }

    private suspend fun refreshReads(lease: SessionLease) = readLock.withLock {
        val before = state.value ?: return@withLock
        if (!owned(lease) || !before.canRead) return@withLock
        val key = before.readKey()
        val listingRange = if (before.tab == "overview") rangeFor(before.selectedDay, 30) else before.range
        val dayAge = LocalDate.parse(before.selectedDay).daysUntil(LocalDate.parse(before.today)).coerceAtLeast(0)
        val weekEnd = LocalDate.parse(before.today).minus(dayAge / 7 * 7, DateTimeUnit.DAY).toString()
        coroutineScope {
            val jobs = mutableListOf(
                async { read(lease, key, { reader.sessions(listingRange, lease) }, { sessions }) { copy(sessions = it) } },
                async { read(lease, key, { reader.summary(before.range, lease) }, { summary }) { copy(summary = it) } },
                async { read(lease, key, { reader.exercises(lease) }, { catalog }) { copy(catalog = it) } },
            )
            if (before.selectedSessionId != null) jobs += async {
                read(lease, key, { reader.session(before.selectedSessionId, lease) }, { session }) { copy(session = it) }
                if (before.canPersonal) state.value?.session?.data?.takeIf { it.id == before.selectedSessionId }?.let { session ->
                    read(lease, key, { reader.effort(session, lease) }, { sessionEffort }) { copy(sessionEffort = it) }
                }
            }
            if (before.progressExerciseId != null) jobs += async {
                read(lease, key, { reader.progress(before.progressExerciseId, before.range, lease) }, { progress }) { copy(progress = it) }
            }
            if (before.canPersonal) {
                jobs += listOf(
                    async { read(lease, key, { reader.overview(before.selectedDay, lease) }, { overview }) { copy(overview = it) } },
                    async { read(lease, key, { reader.steps(before.selectedDay, lease) }, { steps }) { copy(steps = it) } },
                    async { read(lease, key, { reader.history(rangeFor(weekEnd, 7), "steps", lease) }, { weekSteps }) { copy(weekSteps = it) } },
                    async { read(lease, key, { reader.history(before.range, "steps", lease) }, { stepsHistory }) { copy(stepsHistory = it) } },
                    async { read(lease, key, { reader.history(before.range, "active_minutes", lease) }, { activeHistory }) { copy(activeHistory = it) } },
                    async { read(lease, key, { reader.resolved(listingRange, "running_steps", lease) }, { runningSteps }) { copy(runningSteps = it) } },
                    async { read(lease, key, { reader.resolved(listingRange, "running_workouts", lease) }, { runningWorkouts }) { copy(runningWorkouts = it) } },
                    async { read(lease, key, { reader.resolved(listingRange, "workouts", lease) }, { otherWorkouts }) { copy(otherWorkouts = it) } },
                    async { read(lease, key, { reader.reports(listingRange, lease) }, { reports }) { copy(reports = it) } },
                )
                if (before.workoutId != null && before.workoutSourceId != null) jobs += listOf(
                    async { read(lease, key, { reader.heartRate(before.workoutId, before.workoutSourceId, lease) }, { heartRate }) { copy(heartRate = it) } },
                    async { read(lease, key, { reader.track(before.workoutId, before.workoutSourceId, lease) }, { track }) { copy(track = it) } },
                    async { read(lease, key, { reader.trackVersions(before.workoutId, before.workoutSourceId, lease) }, { trackVersions }) { copy(trackVersions = it) } },
                )
            }
            jobs.awaitAll()
        }
    }
    private suspend fun <T> read(lease: SessionLease, key: ReadKey, fetch: suspend () -> T,
        current: ActivityState.() -> ActivityQuery<T>, update: ActivityState.(ActivityQuery<T>) -> ActivityState) {
        change(lease, key) { update(current().copy(loading = true, error = null)) }
        try { val data = fetch(); change(lease, key) { update(ActivityQuery(data)) } }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) {
            val error = if (failure is ActivityReadException) nativeError(failure.outcome) else GeneratedStrings.Errors.server
            change(lease, key) { update(current().copy(loading = false, error = error, stale = current().data != null)) }
        }
    }
    private suspend fun restoreDrafts(lease: SessionLease) {
        for (target in TARGETS) {
            if (snapshot(target) != null) continue
            try {
                val stored = drafts.read(lease, target) ?: continue
                draftVersions[target] = maxOf(draftVersions[target] ?: 0, stored.revision)
                if (snapshot(target) != null) continue
                val json = stored.json.decodeToString()
                change(lease) { when (target) {
                    SESSION -> copy(sessionDraft = NativeJson.decodeFromString<ActivitySessionDraft>(json))
                    SET -> copy(setDraft = NativeJson.decodeFromString<ActivitySetDraft>(json))
                    EXERCISE -> copy(exerciseDraft = NativeJson.decodeFromString<ActivityExerciseDraft>(json))
                    NOTE -> copy(exerciseNoteDraft = NativeJson.decodeFromString<ActivityExerciseNoteDraft>(json))
                    else -> copy(stepsDraft = NativeJson.decodeFromString<ActivityStepsDraft>(json))
                } }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { change(lease) { copy(error = GeneratedStrings.Assistant.draftStorageError) } }
        }
    }
    private suspend fun persistDraft(lease: SessionLease, target: String, json: String, version: Long) = draftLock.withLock {
        if ((draftVersions[target] ?: 0) == version && snapshot(target) == json) drafts.save(lease, target, json.encodeToByteArray(), version)
    }
    private fun snapshot(target: String): String? = state.value?.let { before -> when (target) {
        SESSION -> before.sessionDraft?.let { NativeJson.encodeToString(it) }
        SET -> before.setDraft?.let { NativeJson.encodeToString(it) }
        EXERCISE -> before.exerciseDraft?.let { NativeJson.encodeToString(it) }
        NOTE -> before.exerciseNoteDraft?.let { NativeJson.encodeToString(it) }
        STEPS -> before.stepsDraft?.let { NativeJson.encodeToString(it) }
        else -> null
    } }
    private fun authoritativeReadSucceeded(entry: Submission): Boolean = visible.value && state.value?.let {
        when {
            isActivityStepsSubmission(entry) -> it.steps.data?.date == activityOperationObject(entry.body).getValue("payload").jsonObject["date"]?.jsonPrimitive?.content && it.steps.error == null && !it.steps.stale
            activityOperationObject(entry.body)["kind"]?.jsonPrimitive?.content?.startsWith("activity.exercise.") == true -> it.catalog.data != null && it.catalog.error == null && !it.catalog.stale
            else -> it.sessions.data != null && it.sessions.error == null && !it.sessions.stale &&
                (it.selectedSessionId == null || it.session.data?.id == it.selectedSessionId && it.session.error == null && !it.session.stale)
        }
    } == true

    private fun bind(lease: SessionLease?, identity: NativeIdentity?) {
        if (lease == null || identity == null || identity.id != lease.namespace.userId) {
            boundLease = null; mutableState.value = null; activeOwners.clear(); visible.value = false; return
        }
        val changed = boundLease !== lease
        if (changed) { draftVersions.clear(); activeOwners.entries.removeAll { it.value !== lease }; visible.value = activeOwners.isNotEmpty() }
        boundLease = lease
        val today = Instant.fromEpochMilliseconds(clockMs()).toLocalDateTime(TimeZone.of(identity.timezone)).date.toString()
        val canRead = "activity.read" in identity.scopes
        val canPersonal = "personal.read" in identity.scopes
        mutableState.update { old ->
            val base = (if (changed) null else old) ?: ActivityState(lease.namespace, today, today, identity.timezone, identity.locale,
                canRead, "activity.write" in identity.scopes, canPersonal, canPersonal && "personal.write" in identity.scopes,
                today, rangeFor(today, 30))
            if (!canRead) ActivityState(lease.namespace, today, today, identity.timezone, identity.locale, false, false, false, false, today, rangeFor(today, 30))
            else base.copy(today = today, timezone = identity.timezone, locale = identity.locale, canRead = true, canWrite = "activity.write" in identity.scopes,
                canPersonal = canPersonal, canSteps = canPersonal && "personal.write" in identity.scopes,
                overview = if (canPersonal) base.overview else ActivityQuery(), steps = if (canPersonal) base.steps else ActivityQuery(),
                weekSteps = if (canPersonal) base.weekSteps else ActivityQuery(),
                stepsHistory = if (canPersonal) base.stepsHistory else ActivityQuery(), activeHistory = if (canPersonal) base.activeHistory else ActivityQuery(),
                runningSteps = if (canPersonal) base.runningSteps else ActivityQuery(), runningWorkouts = if (canPersonal) base.runningWorkouts else ActivityQuery(),
                otherWorkouts = if (canPersonal) base.otherWorkouts else ActivityQuery(),
                reports = if (canPersonal) base.reports else ActivityQuery(), sessionEffort = if (canPersonal) base.sessionEffort else ActivityQuery(),
                heartRate = if (canPersonal) base.heartRate else ActivityQuery(), track = if (canPersonal) base.track else ActivityQuery(),
                trackVersions = if (canPersonal) base.trackVersions else ActivityQuery(), workoutId = if (canPersonal) base.workoutId else null,
                workoutSourceId = if (canPersonal) base.workoutSourceId else null)
        }
    }
    private fun owned(lease: SessionLease): Boolean = sessions.isCurrent(lease) && boundLease === lease
    private fun change(lease: SessionLease, key: ReadKey? = null, update: ActivityState.() -> ActivityState) {
        if (!owned(lease) || "activity.read" !in (sessions.identity.value?.scopes ?: emptyList())) return
        mutableState.update { before -> if (before == null || !before.canRead || key != null && key != before.readKey()) before else before.update() }
    }
    private fun ActivityState.withDay(day: String): ActivityState = if (selectedDay == day) this else copy(selectedDay = day,
        overview = ActivityQuery(), steps = ActivityQuery(), weekSteps = ActivityQuery(), error = null,
        sessions = if (tab == "overview") ActivityQuery() else sessions, runningSteps = if (tab == "overview") ActivityQuery() else runningSteps,
        runningWorkouts = if (tab == "overview") ActivityQuery() else runningWorkouts, otherWorkouts = if (tab == "overview") ActivityQuery() else otherWorkouts,
        reports = if (tab == "overview") ActivityQuery() else reports)
    private fun ActivityState.withoutDraft(target: String): ActivityState = when (target) {
        SESSION -> copy(sessionDraft = null)
        SET -> copy(setDraft = null)
        EXERCISE -> copy(exerciseDraft = null)
        NOTE -> copy(exerciseNoteDraft = null)
        STEPS -> copy(stepsDraft = null)
        else -> this
    }
    private fun ownsSubmission(entry: Submission) = entry.endpoint == ACTIVITY_OPERATIONS || isActivityStepsSubmission(entry)
    private fun ActivityState.readKey() = ReadKey(selectedDay, range, selectedSessionId, progressExerciseId, workoutId, workoutSourceId, canPersonal, tab)
    private data class ReadKey(val day: String, val range: ActivityRange, val session: String?, val exercise: String?, val workout: String?, val source: String?, val personal: Boolean, val tab: String)
    private fun <T> ActivityQuery<T>.stale(): ActivityQuery<T> = copy(stale = data != null)
    private companion object {
        val DEFAULT_OWNER = Any()
        const val SESSION = "activity/session"
        const val SET = "activity/set"
        const val EXERCISE = "activity/exercise"
        const val NOTE = "activity/note"
        const val STEPS = "activity/steps"
        val TARGETS = listOf(SESSION, SET, EXERCISE, NOTE, STEPS)
        fun validDay(value: String): Boolean = runCatching { LocalDate.parse(value).toString() == value }.getOrDefault(false)
        fun rangeFor(anchor: String, period: Int, kind: String? = null) = ActivityRange(LocalDate.parse(anchor).minus(period - 1, DateTimeUnit.DAY).toString(), anchor, kind)
    }
}
