package assistant.mobile.core.personal

import assistant.mobile.core.AccountDrafts
import assistant.mobile.core.AccountNamespace
import assistant.mobile.core.EventDomain
import assistant.mobile.core.EventInvalidation
import assistant.mobile.core.GeneratedStrings
import assistant.mobile.core.HttpOutcome
import assistant.mobile.core.LocalizedString
import assistant.mobile.core.NativeHttpClient
import assistant.mobile.core.NativeIdentity
import assistant.mobile.core.NativeJson
import assistant.mobile.core.SessionLease
import assistant.mobile.core.SessionManager
import assistant.mobile.core.activity.isActivityStepsSubmission
import assistant.mobile.core.food.FoodDay
import assistant.mobile.core.food.decodeFoodDay
import assistant.mobile.core.journal.NewSubmission
import assistant.mobile.core.journal.StaleJournalSession
import assistant.mobile.core.journal.Submission
import assistant.mobile.core.journal.SubmissionCoordinator
import assistant.mobile.core.journal.SubmissionState
import assistant.mobile.core.nativeError
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
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
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlin.time.Clock
import kotlin.time.Instant

data class PersonalQuery<T>(val data: T? = null, val loading: Boolean = false, val error: LocalizedString? = null, val stale: Boolean = false)

enum class PersonalRecoveryOwner { Overview, History, Sources }

data class PersonalState(
    val namespace: AccountNamespace, val selectedDay: String, val today: String,
    val timezone: String, val locale: String, val canRead: Boolean, val canWrite: Boolean,
    val overview: PersonalQuery<PersonalOverview> = PersonalQuery(),
    val resolved: PersonalQuery<PersonalResolvedPage> = PersonalQuery(),
    val derivedScores: PersonalQuery<PersonalDerivedScoresPage> = PersonalQuery(),
    val caliper: PersonalQuery<PersonalCaliperHistory> = PersonalQuery(),
    val catalog: PersonalQuery<List<PersonalCatalogMetric>> = PersonalQuery(),
    val nutritionDay: PersonalQuery<FoodDay> = PersonalQuery(),
    val profileDraft: PersonalProfileDraft? = null, val goalDraft: PersonalGoalDraft? = null,
    val weightDraft: PersonalWeightDraft? = null, val caliperDraft: PersonalCaliperDraft? = null,
    val unitsDraft: PersonalUnitsDraft? = null,
    val pending: List<Submission> = emptyList(), val receipt: PersonalReceipt? = null,
    val busy: Boolean = false, val conflict: Boolean = false, val conflictDraft: String? = null,
    val error: LocalizedString? = null,
)

/** One screen owner over authoritative reads and the shared durable write journal. */
class PersonalController(
    private val scope: CoroutineScope,
    private val sessions: SessionManager,
    private val http: NativeHttpClient,
    private val submissions: SubmissionCoordinator,
    private val drafts: AccountDrafts,
    private val clockMs: () -> Long = { Clock.System.now().toEpochMilliseconds() },
    private val reader: PersonalReader = NativePersonalApi(http),
) {
    private val mutableState = MutableStateFlow<PersonalState?>(null)
    val state: StateFlow<PersonalState?> = mutableState.asStateFlow()
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
                    val changed = bind(lease, identity)
                    if (!active || lease == null || identity == null) return@collectLatest
                    if (changed || TARGETS.any { snapshot(it) == null }) restoreDrafts(lease)
                    refresh()
                }
        }
    }

    /** Navigation transitions may keep two Personal destinations alive at the same time. */
    fun activate(day: String? = null, owner: Any = DEFAULT_OWNER) {
        bind(sessions.lease(), sessions.identity.value)
        val lease = boundLease ?: return
        activeOwners[owner] = lease
        day?.let { requestedDay ->
            require(LocalDate.parse(requestedDay).toString() == requestedDay)
            mutableState.update { before -> before?.let { value ->
                val selected = minOf(requestedDay, value.today)
                if (value.selectedDay == selected) value else value.withDay(selected)
            } }
        }
        visible.value = true
    }

    fun deactivate(owner: Any = DEFAULT_OWNER) {
        activeOwners.remove(owner)
        visible.value = activeOwners.values.any { it === boundLease }
    }

    suspend fun selectDay(day: String) {
        val before = state.value ?: return
        require(LocalDate.parse(day).toString() == day && day <= before.today)
        mutableState.update { it?.withDay(day) }
        refresh()
    }

    suspend fun refresh() {
        if (!visible.value) return
        val lease = sessions.lease() ?: return
        bind(lease, sessions.identity.value)
        if (state.value?.canRead != true) return
        refreshReads(lease)
        try { refreshPending(lease, consumeResolved = true) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { change(lease) { copy(error = GeneratedStrings.Assistant.draftStorageError) } }
    }

    suspend fun invalidate(invalidation: EventInvalidation) {
        if (invalidation is EventInvalidation.Domains && invalidation.values.none { it in setOf(EventDomain.Personal, EventDomain.Nutrition, EventDomain.Catalog) }) return
        mutableState.update { it?.copy(overview = it.overview.stale(), resolved = it.resolved.stale(),
            derivedScores = it.derivedScores.stale(), caliper = it.caliper.stale(), catalog = it.catalog.stale(), nutritionDay = it.nutritionDay.stale()) }
        refresh()
    }

    fun beginProfile() {
        val before = state.value ?: return
        if (!before.canRead || before.busy || before.profileDraft != null) return
        val profile = before.overview.data?.profile ?: return
        updateProfileDraft(PersonalProfileDraft(profile.userId, profile.revision, profile.birthDate.orEmpty(),
            profile.heightCm.orEmpty(), profile.gender.orEmpty(), profile.timezone))
    }

    fun updateProfileDraft(draft: PersonalProfileDraft) = edit(PROFILE) { copy(profileDraft = draft) }

    suspend fun saveProfile() = mutate { lease ->
        val before = state.value ?: return@mutate
        val draft = before.profileDraft ?: return@mutate
        if (!personalProfileDraftChanged(draft)) return@mutate
        if (before.conflictDraft == PROFILE && (before.overview.error != null || before.overview.stale)) return@mutate
        val revision = if (before.conflictDraft == PROFILE) before.overview.data?.profile?.revision ?: return@mutate else draft.revision
        val operation = profilePersonalOperation(draft, before.today, revision)
        deliver(lease, operation, PROFILE)
    }

    suspend fun setDisplayUnits(units: String) = mutate { lease ->
        val profile = state.value?.overview?.data?.profile ?: return@mutate
        PersonalDisplayUnits.fromWire(units)
        if (profile.displayUnits.wire == units && state.value?.unitsDraft == null) return@mutate
        val draft = PersonalUnitsDraft(profile.userId, profile.revision, units)
        edit(UNITS, force = true) { copy(unitsDraft = draft) }
        deliver(lease, unitsPersonalOperation(draft), UNITS)
    }

    fun beginGoal(kind: String) {
        require(kind in PERSONAL_TARGET_KINDS)
        val before = state.value ?: return
        if (!before.canRead || before.busy || before.goalDraft != null) return
        val overview = before.overview.data ?: return
        val preference = overview.preferences[kind]
        val target = preference?.target
        val input = if (kind == "distance") personalDistanceInput(target, overview.profile.displayUnits) else target.orEmpty()
        updateGoalDraft(PersonalGoalDraft(kind, input, preference?.revision ?: 0, overview.profile.displayUnits.wire, target))
    }

    fun updateGoalDraft(draft: PersonalGoalDraft) = edit(GOAL) { copy(goalDraft = draft) }

    suspend fun saveGoal() = mutate { lease ->
        val before = state.value ?: return@mutate
        val draft = before.goalDraft ?: return@mutate
        if (before.conflictDraft == GOAL && (before.overview.error != null || before.overview.stale)) return@mutate
        val revision = if (before.conflictDraft == GOAL) before.overview.data?.preferences?.get(draft.kind)?.revision ?: 0 else draft.revision
        deliver(lease, goalPersonalOperation(draft, revision), GOAL)
    }

    fun beginWeight() {
        val before = state.value ?: return
        if (!before.canRead || before.busy || before.weightDraft != null) return
        updateWeightDraft(PersonalWeightDraft(measuredAt = measurementTime(before), timezone = before.timezone))
    }

    fun updateWeightDraft(draft: PersonalWeightDraft) = edit(WEIGHT) { copy(weightDraft = draft) }

    suspend fun saveWeight() = mutate { lease ->
        val before = state.value ?: return@mutate
        val draft = before.weightDraft ?: return@mutate
        deliver(lease, weightPersonalOperation(draft, draft.timezone.ifBlank { before.timezone }, clockMs()), WEIGHT)
    }

    fun beginCaliper() {
        val before = state.value ?: return
        if (!before.canRead || before.busy || before.caliperDraft != null) return
        updateCaliperDraft(PersonalCaliperDraft(measuredAt = measurementTime(before),
            gender = before.overview.data?.profile?.gender.orEmpty(), timezone = before.timezone))
    }

    fun updateCaliperDraft(draft: PersonalCaliperDraft) = edit(CALIPER) { copy(caliperDraft = draft) }

    suspend fun saveCaliper() = mutate { lease ->
        val before = state.value ?: return@mutate
        val draft = before.caliperDraft ?: return@mutate
        deliver(lease, caliperPersonalOperation(draft, draft.timezone.ifBlank { before.timezone }, clockMs()), CALIPER)
    }

    suspend fun dismissDraft(kind: String) {
        val lease = sessions.lease() ?: return
        if (!owned(lease) || state.value?.busy == true) return
        val target = target(kind)
        draftVersions[target] = (draftVersions[target] ?: 0) + 1
        draftLock.withLock { drafts.delete(lease, target) }
        change(lease) { withoutDraft(target).copy(conflict = false, conflictDraft = null, error = null) }
    }

    /** Uncertain writes only look up their original key; no path here resubmits a POST. */
    suspend fun check(key: String) = action { lease ->
        change(lease) { copy(busy = true, error = null) }
        try {
            val original = submissions.pending(lease).firstOrNull {
                it.endpoint == PERSONAL_OPERATIONS && !isActivityStepsSubmission(it) && it.key == key && ownsSubmission(it)
            } ?: return@action
            settle(lease, submissions.check(lease, original.key))
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { change(lease) { copy(error = GeneratedStrings.Errors.network) } }
        finally { change(lease) { copy(busy = false) }; pendingOrError(lease) }
    }

    suspend fun recover(key: String) { activate(); check(key) }
    suspend fun recoveryOwner(key: String): PersonalRecoveryOwner? {
        val lease = sessions.lease() ?: return null
        val entry = submissions.pending(lease).firstOrNull {
            it.endpoint == PERSONAL_OPERATIONS && !isActivityStepsSubmission(it) && it.key == key
        } ?: return null
        if (!sessions.isCurrent(lease)) return null
        val kind = personalOperationObject(entry.body).getValue("kind").jsonPrimitive.content
        return when {
            kind == assistant.mobile.core.body.BODY_PRESENTATION_OPERATION -> PersonalRecoveryOwner.Overview
            kind in PERSONAL_SOURCE_OPERATION_KINDS -> PersonalRecoveryOwner.Sources
            kind in setOf("personal.derived.backfill", "personal.sensor.exclude", "personal.sensor.restore") -> PersonalRecoveryOwner.History
            ownsSubmission(entry) -> PersonalRecoveryOwner.Overview
            else -> null
        }
    }
    fun clearReceipt() { mutableState.update { it?.copy(receipt = null) } }

    private fun edit(target: String, force: Boolean = false, update: PersonalState.() -> PersonalState) {
        val lease = sessions.lease() ?: return
        if (!owned(lease) || state.value?.canRead != true || (!force && state.value?.busy == true)) return
        draftVersions[target] = (draftVersions[target] ?: 0) + 1
        val version = draftVersions.getValue(target)
        change(lease) { update().copy(error = null) }
        val json = snapshot(target) ?: return
        scope.launch {
            try { persistDraft(lease, target, json, version) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { change(lease) { copy(error = GeneratedStrings.Assistant.draftStorageError) } }
        }
    }

    /** The sheet can disappear when its receipt clears the draft. Its waiter may then cancel,
     * while the originating account's action still has to finish its reads and acknowledgement. */
    private suspend fun action(block: suspend (SessionLease) -> Unit) {
        val lease = sessions.lease() ?: return
        scope.async {
            mutationLock.withLock {
                // Capture before dispatch: queued work can never switch to a later account.
                if (!owned(lease)) return@withLock
                block(lease)
            }
        }.await()
    }

    private suspend fun mutate(block: suspend (SessionLease) -> Unit) = action { lease ->
        if (state.value?.canWrite != true) return@action
        try {
            // A failed durable read cannot establish that no earlier delivery is outstanding.
            refreshPending(lease)
            if (state.value?.pending?.isNotEmpty() == true) return@action
            change(lease) { copy(busy = true, error = null) }
            block(lease)
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (invalid: PersonalValidationException) { change(lease) { copy(error = invalid.messageKey) } }
        catch (_: IllegalArgumentException) { change(lease) { copy(error = GeneratedStrings.Errors.invalid) } }
        catch (_: StaleJournalSession) { /* The newer lease owns presentation. */ }
        catch (_: Exception) { change(lease) { copy(error = GeneratedStrings.Assistant.draftStorageError) } }
        finally { change(lease) { copy(busy = false) }; if (owned(lease)) pendingOrError(lease) }
    }

    private suspend fun deliver(lease: SessionLease, operation: PersonalOperation, target: String) {
        val json = snapshot(target) ?: return
        persistDraft(lease, target, json, draftVersions[target] ?: 0)
        val result = submissions.submit(lease, NewSubmission(PERSONAL_OPERATIONS, PERSONAL_LOOKUP, operation.bytes(), "personal",
            revisions = operation.expectedRevisions.mapValues { it.value.toString() }, draftTarget = target))
        settle(lease, result)
    }

    private suspend fun settle(lease: SessionLease, result: Submission) {
        if (!owned(lease)) return
        if (result.state == SubmissionState.RESOLVED && result.response != null) {
            applyReceipt(lease, result)
            if (visible.value) refreshReads(lease)
            if (authoritativeReadSucceeded()) submissions.acknowledge(lease, result.key)
        } else if (result.state == SubmissionState.REJECTED) {
            val conflict = result.failureStatus == 409
            change(lease) { copy(conflict = conflict, conflictDraft = if (conflict) result.draftTarget else null,
                error = if (conflict) GeneratedStrings.Personal.conflict else GeneratedStrings.Errors.server) }
            if (visible.value) refreshReads(lease)
        }
    }

    private suspend fun applyReceipt(lease: SessionLease, result: Submission) {
        val response = requireNotNull(result.response)
        validatePersonalSubmissionReceipt(result, response)
        val receipt = decodePersonalReceipt(response)
        change(lease) { copy(receipt = receipt, conflict = false, conflictDraft = null, error = null) }
        if (receipt.kind == "personal.profile.update") {
            val saved = decodePersonalProfile(receipt.raw.getValue("profile").jsonObject)
            change(lease) { copy(overview = overview.copy(data = overview.data?.let { value ->
                if (value.profile.userId == saved.userId && value.profile.revision <= saved.revision) value.copy(profile = saved, timezone = saved.timezone) else value
            }), timezone = saved.timezone) }
            if (saved.timezone != sessions.identity.value?.timezone) sessions.refreshIdentity(lease)
        }
        val target = result.draftTarget?.takeIf { it in TARGETS } ?: return
        val json = snapshot(target) ?: return
        if (!draftMatchesSubmission(target, result)) return
        val version = draftVersions[target] ?: 0
        draftLock.withLock {
            val stored = drafts.read(lease, target)?.json?.decodeToString()
            if (stored != json || snapshot(target) != json || (draftVersions[target] ?: 0) != version) return@withLock
            drafts.delete(lease, target)
            change(lease) { withoutDraft(target) }
        }
    }

    private fun draftMatchesSubmission(target: String, submission: Submission): Boolean = runCatching {
        val before = state.value ?: return@runCatching false
        val command = personalOperationObject(submission.body)
        val expected = command.getValue("expected_revisions").jsonObject
        val operation = when (target) {
            PROFILE -> before.profileDraft?.let { profilePersonalOperation(it, before.today, expected[it.userId]?.jsonPrimitive?.longOrNull ?: return@runCatching false) }
            GOAL -> before.goalDraft?.let { goalPersonalOperation(it, command.getValue("payload").jsonObject.getValue("revision").jsonPrimitive.longOrNull ?: return@runCatching false) }
            WEIGHT -> before.weightDraft?.let { weightPersonalOperation(it, it.timezone.ifBlank { before.timezone }, clockMs()) }
            CALIPER -> before.caliperDraft?.let { caliperPersonalOperation(it, it.timezone.ifBlank { before.timezone }, clockMs()) }
            UNITS -> before.unitsDraft?.let { unitsPersonalOperation(it, expected[it.userId]?.jsonPrimitive?.longOrNull ?: return@runCatching false) }
            else -> null
        }
        operation?.let { personalOperationObject(it.bytes()) == command } == true
    }.getOrDefault(false)

    private suspend fun refreshPending(lease: SessionLease, consumeResolved: Boolean = false) {
        val entries = submissions.pending(lease).filter {
            it.endpoint == PERSONAL_OPERATIONS && !isActivityStepsSubmission(it)
        }.map { entry ->
            if (consumeResolved && ownsSubmission(entry) && entry.state in setOf(SubmissionState.SENDING, SubmissionState.UNCERTAIN, SubmissionState.ACCEPTED)) submissions.check(lease, entry.key) else entry
        }
        if (consumeResolved && owned(lease)) {
            val resolved = entries.filter { it.state == SubmissionState.RESOLVED && ownsSubmission(it) }.sortedBy { it.createdAtMillis }
            for (entry in resolved) applyReceipt(lease, entry)
            if (resolved.isNotEmpty()) refreshReads(lease)
            if (authoritativeReadSucceeded()) for (entry in resolved) submissions.acknowledge(lease, entry.key)
        }
        change(lease) { copy(pending = entries.filter { it.state !in setOf(SubmissionState.RESOLVED, SubmissionState.REJECTED) }) }
    }

    private fun ownsSubmission(entry: Submission): Boolean = runCatching {
        personalOperationObject(entry.body).getValue("kind").jsonPrimitive.content in setOf(
            "personal.profile.update", "personal.preference.set", "personal.weight.add", "personal.caliper.add",
        )
    }.getOrDefault(false)

    private suspend fun pendingOrError(lease: SessionLease) {
        try { refreshPending(lease) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { change(lease) { copy(error = GeneratedStrings.Assistant.draftStorageError) } }
    }

    private suspend fun refreshReads(lease: SessionLease) = readLock.withLock {
        val before = state.value ?: return@withLock
        if (!owned(lease) || !before.canRead) return@withLock
        val day = before.selectedDay
        change(lease, day) { copy(overview = overview.loading(), resolved = resolved.loading(), derivedScores = derivedScores.loading(),
            caliper = caliper.loading(), catalog = catalog.loading(), nutritionDay = if (canReadNutrition()) nutritionDay.loading() else PersonalQuery()) }
        coroutineScope {
            listOf(
                async { read(lease, day, { reader.overview(day, lease) }, { overview }) { copy(overview = it, timezone = it.data?.timezone ?: timezone) } },
                async { read(lease, day, { reader.resolved(day, lease) }, { resolved }) { copy(resolved = it) } },
                async { read(lease, day, { reader.derivedScores(day, before.timezone, lease) }, { derivedScores }) { copy(derivedScores = it) } },
                async { read(lease, day, { reader.caliper(day, lease) }, { caliper }) { copy(caliper = it) } },
                async { read(lease, day, { reader.catalog(lease) }, { catalog }) { copy(catalog = it) } },
                async {
                    if (canReadNutrition()) read(lease, day, {
                        val outcome = http.request("GET", "/v1/nutrition/day?date=$day", lease = lease)
                        if (outcome is HttpOutcome.Success) decodeFoodDay(outcome.body).also { require(it.date == day) }
                        else throw PersonalReadException(outcome)
                    }, { nutritionDay }) { copy(nutritionDay = if (canReadNutrition()) it else PersonalQuery()) }
                },
            ).awaitAll()
        }
    }

    private suspend fun <T> read(lease: SessionLease, day: String, fetch: suspend () -> T,
        current: PersonalState.() -> PersonalQuery<T>, update: PersonalState.(PersonalQuery<T>) -> PersonalState) {
        try {
            val data = fetch()
            change(lease, day) { update(PersonalQuery(data)) }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) {
            val error = if (failure is PersonalReadException) nativeError(failure.outcome) else GeneratedStrings.Errors.server
            change(lease, day) { update(current().copy(loading = false, error = error, stale = current().data != null)) }
        }
    }

    private suspend fun restoreDrafts(lease: SessionLease) {
        for (target in TARGETS) {
            try {
                val stored = drafts.read(lease, target) ?: continue
                draftVersions[target] = maxOf(draftVersions[target] ?: 0, stored.revision)
                if (snapshot(target) != null) continue
                val json = stored.json.decodeToString()
                change(lease) { when (target) {
                    PROFILE -> copy(profileDraft = NativeJson.decodeFromString<PersonalProfileDraft>(json))
                    GOAL -> copy(goalDraft = NativeJson.decodeFromString<PersonalGoalDraft>(json))
                    WEIGHT -> copy(weightDraft = NativeJson.decodeFromString<PersonalWeightDraft>(json))
                    CALIPER -> copy(caliperDraft = NativeJson.decodeFromString<PersonalCaliperDraft>(json))
                    else -> copy(unitsDraft = NativeJson.decodeFromString<PersonalUnitsDraft>(json))
                } }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { change(lease) { copy(error = GeneratedStrings.Assistant.draftStorageError) } }
        }
    }

    private suspend fun persistDraft(lease: SessionLease, target: String, json: String, version: Long) = draftLock.withLock {
        if ((draftVersions[target] ?: 0) != version || snapshot(target) != json) return@withLock
        drafts.save(lease, target, json.encodeToByteArray(), version)
    }

    private fun snapshot(target: String): String? = state.value?.let { before -> when (target) {
        PROFILE -> before.profileDraft?.let { NativeJson.encodeToString(it) }
        GOAL -> before.goalDraft?.let { NativeJson.encodeToString(it) }
        WEIGHT -> before.weightDraft?.let { NativeJson.encodeToString(it) }
        CALIPER -> before.caliperDraft?.let { NativeJson.encodeToString(it) }
        UNITS -> before.unitsDraft?.let { NativeJson.encodeToString(it) }
        else -> null
    } }

    private fun measurementTime(before: PersonalState): String {
        val time = Instant.fromEpochMilliseconds(clockMs()).toLocalDateTime(TimeZone.of(before.timezone)).time.toString().take(5)
        return "${before.selectedDay}T$time"
    }

    private fun authoritativeReadSucceeded(): Boolean = visible.value && state.value?.overview?.let { it.data != null && it.error == null } == true
    private fun canReadNutrition(): Boolean = "nutrition.read" in (sessions.identity.value?.scopes ?: emptyList())

    private fun bind(lease: SessionLease?, identity: NativeIdentity?): Boolean {
        if (lease == null || identity == null || identity.id != lease.namespace.userId) {
            boundLease = null; mutableState.value = null
            activeOwners.clear(); visible.value = false
            return false
        }
        val changed = boundLease !== lease
        if (changed) {
            draftVersions.clear()
            activeOwners.entries.removeAll { it.value !== lease }
            visible.value = activeOwners.isNotEmpty()
        }
        boundLease = lease
        val today = Instant.fromEpochMilliseconds(clockMs()).toLocalDateTime(TimeZone.of(identity.timezone)).date.toString()
        mutableState.update { old ->
            val base = (if (changed) null else old) ?: PersonalState(lease.namespace, today, today, identity.timezone, identity.locale,
                "personal.read" in identity.scopes, "personal.write" in identity.scopes)
            if ("personal.read" !in identity.scopes) PersonalState(lease.namespace, today, today, identity.timezone, identity.locale, false, false)
            else base.copy(today = today, timezone = identity.timezone, locale = identity.locale, canRead = true,
                canWrite = "personal.write" in identity.scopes, nutritionDay = if (canReadNutrition()) base.nutritionDay else PersonalQuery())
        }
        return changed
    }

    private fun owned(lease: SessionLease): Boolean = sessions.isCurrent(lease) && boundLease === lease
    private fun change(lease: SessionLease, day: String? = null, update: PersonalState.() -> PersonalState) {
        if (!owned(lease) || "personal.read" !in (sessions.identity.value?.scopes ?: emptyList())) return
        mutableState.update { before -> if (before == null || !before.canRead || (day != null && before.selectedDay != day)) before else before.update() }
    }

    private fun PersonalState.withDay(day: String): PersonalState = if (selectedDay == day) this else copy(selectedDay = day,
        overview = PersonalQuery(), resolved = PersonalQuery(), derivedScores = PersonalQuery(), caliper = PersonalQuery(), nutritionDay = PersonalQuery(), error = null)
    private fun PersonalState.withoutDraft(target: String): PersonalState = when (target) {
        PROFILE -> copy(profileDraft = null)
        GOAL -> copy(goalDraft = null)
        WEIGHT -> copy(weightDraft = null)
        CALIPER -> copy(caliperDraft = null)
        UNITS -> copy(unitsDraft = null)
        else -> this
    }
    private fun <T> PersonalQuery<T>.stale(): PersonalQuery<T> = copy(stale = data != null)
    private fun <T> PersonalQuery<T>.loading(): PersonalQuery<T> = copy(loading = true, error = null)
    private fun target(kind: String): String = "personal/$kind".also { require(it in TARGETS) }

    private companion object {
        val DEFAULT_OWNER = Any()
        const val PROFILE = "personal/profile"
        const val GOAL = "personal/goal"
        const val WEIGHT = "personal/weight"
        const val CALIPER = "personal/caliper"
        const val UNITS = "personal/units"
        val TARGETS = listOf(PROFILE, GOAL, WEIGHT, CALIPER, UNITS)
    }
}
