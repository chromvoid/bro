package assistant.mobile.core.food

import assistant.mobile.core.AccountDrafts
import assistant.mobile.core.EventDomain
import assistant.mobile.core.EventInvalidation
import assistant.mobile.core.GeneratedStrings
import assistant.mobile.core.HttpOutcome
import assistant.mobile.core.NativeHttpClient
import assistant.mobile.core.NativeIdentity
import assistant.mobile.core.NativeJson
import assistant.mobile.core.SessionLease
import assistant.mobile.core.SessionManager
import assistant.mobile.core.journal.NewSubmission
import assistant.mobile.core.journal.StaleJournalSession
import assistant.mobile.core.journal.Submission
import assistant.mobile.core.journal.SubmissionCoordinator
import assistant.mobile.core.journal.SubmissionState
import assistant.mobile.core.nativeError
import io.ktor.http.encodeURLPathPart
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
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.time.Clock

/** Screen owner over existing reads and the shared journal; it never owns a second SSE stream. */
class FoodController(
    private val scope: CoroutineScope,
    private val sessions: SessionManager,
    private val http: NativeHttpClient,
    private val submissions: SubmissionCoordinator,
    private val drafts: AccountDrafts,
    private val clockMs: () -> Long = { Clock.System.now().toEpochMilliseconds() },
) {
    private val mutableState = MutableStateFlow<FoodState?>(null)
    val state: StateFlow<FoodState?> = mutableState.asStateFlow()
    private val visible = MutableStateFlow(false)
    private var boundLease: SessionLease? = null
    private val dayLock = Mutex()
    private val weekLock = Mutex()
    private val mutationLock = Mutex()
    private val draftLock = Mutex()
    private var draftVersion = 0L
    private val addController = FoodAddController(sessions, http, { state.value }, clockMs,
        ::performAddOperation, { mutableState.update { it?.copy(error = GeneratedStrings.Food.invalidQuantity) } })
    val additions: StateFlow<FoodAddState?> = addController.state

    suspend fun openAdd() {
        mutableState.update { it?.copy(error = null, conflict = false) }
        addController.open()
    }
    fun closeAdd() = addController.close()
    suspend fun refreshAdd() = addController.refresh()
    suspend fun searchFoods(query: String) = addController.search(query)
    fun selectFood(food: FoodCatalogItem) = addController.select(food)
    suspend fun selectRemembered(food: FoodRemembered) = addController.selectRemembered(food)
    fun selectComposition(composition: FoodComposition) = addController.selectComposition(composition)
    fun clearAddSelection() = addController.clearSelection()
    fun updatePortion(quantity: String, unit: String? = null) = addController.updatePortion(quantity, unit)
    fun choosePortion(portion: FoodPortion?) = addController.choosePortion(portion)
    fun updatePortionCount(count: String) = addController.updateCount(count)
    fun updateCompositionCount(count: String) = addController.updateCompositionCount(count)
    fun updateAddMealKind(kind: FoodMealKind?) = addController.updateMealKind(kind)
    suspend fun estimatePortion() = addController.estimate()
    suspend fun recordPortion() = addController.record()
    suspend fun logComposition() = addController.logComposition()
    suspend fun toggleFavorite(food: FoodRemembered) = addController.toggleFavorite(food)
    suspend fun repeatRemembered(food: FoodRemembered) = addController.repeatRemembered(food)
    suspend fun createFood(name: String, brand: String, basisQuantity: String, basisUnit: String, nutrients: Map<String, String?>) =
        addController.createFood(name, brand, basisQuantity, basisUnit, nutrients)

    suspend fun repeatDish(dish: FoodDish) = mutate { lease ->
        val before = state.value ?: return@mutate
        val operation = runCatching { repeatFoodDishOperation(dish, before.selectedDay, dish.mealKind) }.getOrNull()
            ?: run { change(lease) { copy(error = GeneratedStrings.Food.invalidQuantity) }; return@mutate }
        deliver(lease, operation, null)
    }

    suspend fun saveComposition(dish: FoodDish, name: String) = mutate { lease ->
        val operation = runCatching { saveFoodCompositionOperation(dish, name) }.getOrNull()
            ?: run { change(lease) { copy(error = GeneratedStrings.Food.invalidQuantity) }; return@mutate }
        deliver(lease, operation, null)
    }

    private suspend fun performAddOperation(expectedLease: SessionLease, operation: FoodOperation): FoodReceipt? {
        var receipt: FoodReceipt? = null
        mutate(expectedLease) { lease -> receipt = deliver(lease, operation, null) }
        return receipt
    }

    init {
        scope.launch {
            combine(sessions.leases, sessions.identity, visible) { lease, identity, active -> Triple(lease, identity, active) }
                .collectLatest { (lease, identity, active) ->
                    val changed = bind(lease, identity)
                    if (!active || lease == null || identity == null) return@collectLatest
                    if (changed || state.value?.draft == null) restoreDraft(lease)
                    refresh()
                }
        }
    }

    fun activate(day: String? = null) {
        bind(sessions.lease(), sessions.identity.value)
        if (day != null) {
            require(LocalDate.parse(day).toString() == day)
            mutableState.update { before -> before?.let {
                val selected = minOf(day, it.today)
                if (it.selectedDay == selected) it else it.copy(selectedDay = selected, day = FoodQuery(), week = FoodQuery())
            } }
        }
        visible.value = true
    }

    fun deactivate() { visible.value = false }

    suspend fun selectDay(day: String) {
        require(LocalDate.parse(day).toString() == day)
        val before = state.value ?: return
        require(day <= before.today)
        closeAdd()
        mutableState.update { it?.copy(selectedDay = day, day = FoodQuery(), week = FoodQuery(), error = null) }
        refresh()
    }

    /** Hidden sections retain stale markers and perform no background reads. */
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
        if (invalidation is EventInvalidation.Domains && EventDomain.Nutrition !in invalidation.values && EventDomain.Catalog !in invalidation.values) return
        mutableState.update { it?.copy(day = it.day.copy(stale = it.day.data != null), week = it.week.copy(stale = it.week.data != null)) }
        refresh()
        refreshAdd()
    }

    /** Resolve canonical identity: an entry may now belong to a dish or have moved to another day. */
    suspend fun openItem(kind: String, id: String) {
        require(kind in setOf("entry", "dish") && id.isNotBlank())
        val lease = sessions.lease() ?: return
        if (state.value?.canRead != true) return
        val outcome = http.request("GET", "/v1/nutrition/items/$kind/${id.encodeURLPathPart()}", lease = lease)
        if (!owned(lease)) return
        if (outcome !is HttpOutcome.Success) { change(lease) { copy(error = nativeError(outcome)) }; return }
        val target = runCatching { decodeFoodItemTarget(outcome.body) }.getOrNull()
        if (target == null || target.status != "active") { change(lease) { copy(error = GeneratedStrings.Errors.notFound) }; return }
        selectDay(target.date)
        if (!owned(lease)) return
        val item = state.value?.day?.data?.items?.firstOrNull { it.kind == target.kind && it.id == target.id }
        if (item == null) change(lease) { copy(error = GeneratedStrings.Errors.notFound) } else beginEdit(item)
    }

    fun beginEdit(item: FoodItem) {
        if (state.value?.canRead != true || state.value?.busy == true) return
        val date = item.date ?: state.value?.selectedDay ?: return
        val draft = FoodEditDraft(item.kind, item.id, item.name, plainFoodDecimal(item.quantity) ?: item.quantity, item.unit,
            date, item.revision, item.mealKind?.wire,
            if (item is FoodDish) item.components.associate { it.id to (plainFoodDecimal(it.quantity) ?: it.quantity) } else emptyMap())
        updateDraft(draft)
    }

    fun updateDraft(draft: FoodEditDraft) {
        val lease = sessions.lease() ?: return
        if (!owned(lease) || state.value?.busy == true) return
        draftVersion++
        val version = draftVersion
        change(lease) { copy(draft = draft, error = null) }
        scope.launch {
            try { persistDraft(lease, draft, version) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { change(lease) { copy(error = GeneratedStrings.Assistant.draftStorageError) } }
        }
    }

    suspend fun dismissEdit() {
        val lease = sessions.lease() ?: return
        if (!owned(lease) || state.value?.busy == true) return
        draftVersion++
        draftLock.withLock { drafts.delete(lease, EDIT_TARGET) }
        change(lease) { copy(draft = null, conflict = false, error = null) }
    }

    suspend fun submitEdit() = mutate { lease ->
        val before = state.value ?: return@mutate
        val draft = before.draft ?: return@mutate
        val current = before.day.data?.items?.firstOrNull { it.id == draft.id && it.kind == draft.kind }
        if (before.conflict && current == null) {
            change(lease) { copy(error = GeneratedStrings.Food.conflict) }
            return@mutate
        }
        // A conflict is a proven rejection (409 + lookup404). Only this new explicit Save can
        // propose the preserved edit against the reread revision; no refresh changes the draft.
        val revision = if (before.conflict) requireNotNull(current).revision else draft.revision
        val operation = try { editFoodOperation(draft, revision) } catch (_: Exception) {
            change(lease) { copy(error = GeneratedStrings.Food.invalidQuantity) }; return@mutate
        }
        persistDraft(lease, draft, draftVersion)
        deliver(lease, operation, EDIT_TARGET)
    }

    suspend fun deleteItem(item: FoodItem) = mutate { lease -> deliver(lease, deleteFoodOperation(item), null) }

    suspend fun groupEntries(ids: List<String>, name: String, quantity: String, unit: String) = mutate { lease ->
        val day = state.value?.day?.data ?: return@mutate
        val entries = ids.map { id -> day.items.filterIsInstance<FoodEntry>().first { it.id == id } }
        val operation = try { groupFoodOperation(entries, name, quantity, unit) } catch (_: Exception) {
            change(lease) { copy(error = GeneratedStrings.Food.invalidQuantity) }; return@mutate
        }
        deliver(lease, operation, null)
    }

    suspend fun completeDay() = mutate { lease ->
        val day = state.value?.day?.data ?: return@mutate
        if (day.entries.isEmpty() || day.drafts.isNotEmpty() || day.totals["energy_kcal"]?.complete != true) return@mutate
        deliver(lease, FoodOperation("day.complete", buildJsonObject {
            put("date", day.date); put("revision", day.dayRevision)
        }, emptyMap()), null)
    }

    suspend fun undo() = mutate { lease ->
        val receipt = state.value?.receipt?.takeIf { it.canUndo } ?: return@mutate
        deliver(lease, undoFoodOperation(receipt), null)
    }

    /** An uncertain write only looks up its original key. It never creates or retries a POST. */
    suspend fun check(key: String) = mutationLock.withLock {
        val lease = sessions.lease() ?: return@withLock
        if (!owned(lease)) return@withLock
        change(lease) { copy(busy = true, error = null) }
        try {
            val original = submissions.pending(lease).firstOrNull { it.endpoint == FOOD_OPERATIONS && it.key == key } ?: return@withLock
            settle(lease, submissions.check(lease, original.key))
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { change(lease) { copy(error = GeneratedStrings.Errors.network) } }
        finally { change(lease) { copy(busy = false) }; pendingOrError(lease) }
    }

    /** Only a person can resend a proven uncommitted proposal, with the original durable key. */
    suspend fun retry(key: String) = mutationLock.withLock {
        val lease = sessions.lease() ?: return@withLock
        if (!owned(lease) || state.value?.canWrite != true) return@withLock
        change(lease) { copy(busy = true, error = null) }
        try {
            val original = submissions.pending(lease).firstOrNull { it.endpoint == FOOD_OPERATIONS && it.key == key } ?: return@withLock
            val result = submissions.retry(lease, original.key)
            settle(lease, result)
            if (owned(lease) && result.state == SubmissionState.RESOLVED && decodeFoodReceipt(requireNotNull(result.response)).kind in
                setOf("entry.add_many", "composition.log", "dish.log")) closeAdd()
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: StaleJournalSession) { /* The originating account is no longer active. */ }
        catch (_: Exception) { change(lease) { copy(error = GeneratedStrings.Errors.network) } }
        finally { change(lease) { copy(busy = false) }; if (owned(lease)) pendingOrError(lease) }
    }

    fun clearReceipt() { mutableState.update { it?.copy(receipt = null) } }

    private suspend fun mutate(expectedLease: SessionLease? = sessions.lease(), block: suspend (SessionLease) -> Unit) {
        // A second tap must not queue a second user action while the first owns delivery.
        if (!mutationLock.tryLock()) return
        try {
        val lease = sessions.lease() ?: return
        if (lease !== expectedLease) return
        if (!owned(lease) || state.value?.canWrite != true) return
        try {
            // A failed journal read blocks the proposal. Cached empty state is never evidence
            // that another delivery does not exist.
            refreshPending(lease)
            if (state.value?.pending?.isNotEmpty() == true) return
            change(lease) { copy(busy = true, error = null) }
            block(lease)
        }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: StaleJournalSession) { /* The new session owns the screen. */ }
        catch (_: Exception) { change(lease) { copy(error = GeneratedStrings.Assistant.draftStorageError) } }
        finally { change(lease) { copy(busy = false) }; if (owned(lease)) pendingOrError(lease) }
        } finally { mutationLock.unlock() }
    }

    private suspend fun deliver(lease: SessionLease, operation: FoodOperation, draftTarget: String?): FoodReceipt? {
        val result = submissions.submit(lease, NewSubmission(FOOD_OPERATIONS, FOOD_LOOKUP, operation.bytes(), "nutrition",
            revisions = operation.expectedRevisions.mapValues { it.value.toString() }, draftTarget = draftTarget))
        settle(lease, result)
        return if (owned(lease) && result.state == SubmissionState.RESOLVED) result.response?.let(::decodeFoodReceipt) else null
    }

    private suspend fun settle(lease: SessionLease, result: Submission) {
        if (!owned(lease)) return
        if (result.state == SubmissionState.RESOLVED && result.response != null) {
            applyReceipt(lease, result)
            if (visible.value) refreshReads(lease)
            // A refreshed domain read plus its displayed receipt consumes this local delivery.
            if (visible.value && state.value?.day?.data != null && state.value?.day?.error == null) submissions.acknowledge(lease, result.key)
        } else if (result.state == SubmissionState.REJECTED) {
            val conflict = result.failureStatus == 409
            change(lease) { copy(conflict = conflict, error = if (conflict) GeneratedStrings.Food.conflict else GeneratedStrings.Errors.server) }
            refresh()
        }
    }

    private suspend fun applyReceipt(lease: SessionLease, result: Submission) {
        val response = requireNotNull(result.response)
        validateFoodSubmissionReceipt(result, response)
        val receipt = decodeFoodReceipt(response)
        change(lease) { copy(receipt = receipt, conflict = false, error = null) }
        if (result.draftTarget != EDIT_TARGET) return
        val snapshot = state.value?.draft ?: return
        if (!draftMatchesSubmission(snapshot, result)) return
        val version = draftVersion
        draftLock.withLock {
            val persisted = drafts.read(lease, EDIT_TARGET)?.let { NativeJson.decodeFromString<FoodEditDraft>(it.json.decodeToString()) }
            if (persisted != snapshot || state.value?.draft != snapshot || draftVersion != version) return@withLock
            drafts.delete(lease, EDIT_TARGET)
            change(lease) { if (draft == snapshot) copy(draft = null) else this }
        }
    }

    private fun draftMatchesSubmission(draft: FoodEditDraft, result: Submission): Boolean = runCatching {
        val revision = result.revisions[draft.id]?.toLongOrNull() ?: return@runCatching false
        foodObject(editFoodOperation(draft, revision).bytes()) == foodObject(result.body)
    }.getOrDefault(false)

    private suspend fun refreshPending(lease: SessionLease, consumeResolved: Boolean = false) {
        val entries = submissions.pending(lease).filter { it.endpoint == FOOD_OPERATIONS }.map { entry ->
            if (consumeResolved && entry.state in setOf(SubmissionState.SENDING, SubmissionState.UNCERTAIN, SubmissionState.ACCEPTED)) {
                submissions.check(lease, entry.key)
            } else entry
        }
        if (consumeResolved && owned(lease)) {
            val resolvedEntries = entries.filter { it.state == SubmissionState.RESOLVED }.sortedBy { it.createdAtMillis }
            for (resolved in resolvedEntries) applyReceipt(lease, resolved)
            // A lookup can observe a commit that the first day GET preceded. Read again after
            // its receipt so delivery evidence never consumes an older domain projection.
            if (resolvedEntries.isNotEmpty()) refreshReads(lease)
            for (resolved in resolvedEntries) {
                if (state.value?.day?.data != null && state.value?.day?.error == null) submissions.acknowledge(lease, resolved.key)
            }
        }
        val pending = entries.filter { it.state !in setOf(SubmissionState.REJECTED, SubmissionState.RESOLVED) }
        change(lease) { copy(pending = pending) }
    }

    private suspend fun pendingOrError(lease: SessionLease) {
        try { refreshPending(lease) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { change(lease) { copy(error = GeneratedStrings.Assistant.draftStorageError) } }
    }

    private suspend fun refreshReads(lease: SessionLease) = coroutineScope {
        listOf(async { refreshDay(lease) }, async { refreshWeek(lease) }).awaitAll()
        Unit
    }

    private suspend fun refreshDay(lease: SessionLease) = dayLock.withLock {
        val day = state.value?.selectedDay ?: return@withLock
        if (!owned(lease) || state.value?.canRead != true) return@withLock
        change(lease, day) { copy(day = this.day.copy(loading = true, error = null)) }
        val outcome = http.request("GET", "/v1/nutrition/day?date=$day", lease = lease)
        if (outcome is HttpOutcome.Success) {
            val data = runCatching { decodeFoodDay(outcome.body).also { require(it.date == day) } }.getOrNull()
            change(lease, day) { copy(day = if (data != null) FoodQuery(data) else this.day.copy(loading = false,
                error = GeneratedStrings.Errors.server, stale = this.day.data != null)) }
        } else change(lease, day) { copy(day = this.day.copy(loading = false, error = nativeError(outcome), stale = this.day.data != null)) }
    }

    private suspend fun refreshWeek(lease: SessionLease) = weekLock.withLock {
        val day = state.value?.selectedDay ?: return@withLock
        if (!owned(lease) || state.value?.canRead != true) return@withLock
        val start = foodWeekStart(day)
        val end = shiftFoodDay(start, 6)
        change(lease, day) { copy(week = week.copy(loading = true, error = null)) }
        val outcome = http.request("GET", "/v1/nutrition/days?start=$start&end=$end", lease = lease)
        if (outcome is HttpOutcome.Success) {
            val data = runCatching { decodeFoodWeek(outcome.body).also { values -> require(values.all { it.date in start..end }) } }.getOrNull()
            change(lease, day) { copy(week = if (data != null) FoodQuery(data) else week.copy(loading = false,
                error = GeneratedStrings.Errors.server, stale = week.data != null)) }
        } else change(lease, day) { copy(week = week.copy(loading = false, error = nativeError(outcome), stale = week.data != null)) }
    }

    private suspend fun restoreDraft(lease: SessionLease) {
        try {
            val stored = drafts.read(lease, EDIT_TARGET) ?: return
            val draft = NativeJson.decodeFromString<FoodEditDraft>(stored.json.decodeToString())
            draftVersion = maxOf(draftVersion, stored.revision)
            change(lease) { if (this.draft == null) copy(draft = draft) else this }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { change(lease) { copy(error = GeneratedStrings.Assistant.draftStorageError) } }
    }

    private suspend fun persistDraft(lease: SessionLease, draft: FoodEditDraft, version: Long) = draftLock.withLock {
        if (version != draftVersion || state.value?.draft != draft) return@withLock
        drafts.save(lease, EDIT_TARGET, NativeJson.encodeToString(draft).encodeToByteArray(), version)
    }

    private fun bind(lease: SessionLease?, identity: NativeIdentity?): Boolean {
        if (lease == null || identity == null || identity.id != lease.namespace.userId) {
            addController.bind(null, readable = false)
            boundLease = null; mutableState.value = null; return false
        }
        val changed = boundLease !== lease
        val today = foodToday(identity.timezone, clockMs())
        boundLease = lease
        mutableState.update { old ->
            val before = if (changed) null else old
            val base = before ?: FoodState(lease.namespace, today, today, identity.timezone, identity.locale,
                "nutrition.read" in identity.scopes, "nutrition.write" in identity.scopes)
            val canRead = "nutrition.read" in identity.scopes
            base.copy(today = today, timezone = identity.timezone, locale = identity.locale,
                canRead = canRead, canWrite = "nutrition.write" in identity.scopes,
                day = if (canRead) base.day else FoodQuery(), week = if (canRead) base.week else FoodQuery(),
                draft = if (canRead) base.draft else null, receipt = if (canRead) base.receipt else null,
                pending = if (canRead) base.pending else emptyList())
        }
        addController.bind(lease, readable = state.value?.canRead == true)
        return changed
    }

    private fun owned(lease: SessionLease): Boolean = sessions.isCurrent(lease) && boundLease === lease
    private fun change(lease: SessionLease, day: String? = null, update: FoodState.() -> FoodState) {
        if (!owned(lease) || "nutrition.read" !in (sessions.identity.value?.scopes ?: emptyList())) return
        mutableState.update { before ->
            if (before == null || !before.canRead || (day != null && before.selectedDay != day)) before else before.update()
        }
    }

    private companion object { const val EDIT_TARGET = "food/edit" }
}
