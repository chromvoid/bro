package assistant.mobile.core.food

import assistant.mobile.core.GeneratedStrings
import assistant.mobile.core.HttpOutcome
import assistant.mobile.core.NativeHttpClient
import assistant.mobile.core.SessionLease
import assistant.mobile.core.SessionManager
import assistant.mobile.core.nativeError
import io.ktor.http.encodeURLParameter
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.time.Instant

/** Read-only catalog and preview state, sharing the diary's one guarded mutation channel. */
internal class FoodAddController(
    private val sessions: SessionManager,
    private val http: NativeHttpClient,
    private val diary: () -> FoodState?,
    private val nowMs: () -> Long,
    private val operate: suspend (SessionLease, FoodOperation) -> FoodReceipt?,
    private val invalid: () -> Unit,
) {
    private val mutableState = MutableStateFlow<FoodAddState?>(null)
    val state = mutableState.asStateFlow()
    private var lease: SessionLease? = null
    private var selectionVersion = 0L
    private var searchVersion = 0L

    fun bind(current: SessionLease?, readable: Boolean) {
        if (current !== lease || !readable || mutableState.value == null) {
            lease = current
            selectionVersion++
            searchVersion++
            mutableState.value = if (current != null && readable) FoodAddState() else null
        }
    }

    suspend fun open() {
        if (diary()?.canRead != true || diary()?.busy == true) return
        selectionVersion++
        mutableState.value = FoodAddState(open = true, mealKind = defaultKind())
        refresh()
    }

    fun close() {
        selectionVersion++
        mutableState.update { it?.copy(open = false, estimate = FoodQuery()) }
    }

    suspend fun refresh() = coroutineScope {
        if (state.value?.open != true) return@coroutineScope
        listOf(async { search(state.value?.query.orEmpty()) }, async { refreshMemory() }).awaitAll()
        Unit
    }

    suspend fun search(query: String) = coroutineScope {
        val current = activeLease() ?: return@coroutineScope
        require(query.length <= 200)
        val normalized = query.trim()
        val version = ++searchVersion
        val previous = state.value
        val sameQuery = previous?.query == normalized
        val priorFoods = if (sameQuery) previous?.foods?.data else null
        val priorCompositions = if (sameQuery) previous?.compositions?.data else null
        mutableState.update { it?.copy(query = normalized, foods = FoodQuery(priorFoods, loading = true),
            compositions = FoodQuery(priorCompositions, loading = true)) }
        listOf(async {
            val result = read(current, "/v1/nutrition/foods/search?q=${normalized.encodeURLParameter()}", ::decodeFoodSearch)
            if (activeLease() === current && version == searchVersion) mutableState.update { it?.copy(foods = result.retaining(priorFoods)) }
        }, async {
            val result = read(current, "/v1/nutrition/compositions?q=${normalized.encodeURLParameter()}", ::decodeFoodCompositions)
            if (activeLease() === current && version == searchVersion) mutableState.update { it?.copy(compositions = result.retaining(priorCompositions)) }
        }).awaitAll()
        Unit
    }

    private suspend fun refreshMemory() {
        val current = activeLease() ?: return
        val date = diary()?.selectedDay ?: return
        val previous = state.value?.memory?.data
        mutableState.update { it?.copy(memory = it.memory.copy(loading = true, error = null)) }
        val result = read(current, "/v1/nutrition/memory?date=$date", ::decodeFoodMemory)
        if (activeLease() === current && diary()?.selectedDay == date) mutableState.update { it?.copy(memory = result.retaining(previous)) }
    }

    fun select(food: FoodCatalogItem) {
        if (!editable()) return
        selectionVersion++
        mutableState.update { it?.copy(selection = FoodPortionDraft(food), chosenComposition = null, estimate = FoodQuery()) }
    }

    suspend fun selectRemembered(food: FoodRemembered) {
        val current = activeLease() ?: return
        if (!editable()) return
        val version = ++selectionVersion
        mutableState.update { it?.copy(query = food.name, selection = null, chosenComposition = null,
            foods = FoodQuery(loading = true), compositions = FoodQuery(), estimate = FoodQuery()) }
        // Opening a remembered row uses its current catalog card, as the web portion panel does.
        val result = read(current, "/v1/nutrition/foods/search?q=${food.name.encodeURLParameter()}", ::decodeFoodSearch)
        if (activeLease() !== current || version != selectionVersion) return
        mutableState.update { it?.copy(foods = result) }
        if (result.error != null) return
        val selected = result.data?.firstOrNull { it.foodId == food.foodId }
        if (selected == null) { search(food.name); return }
        mutableState.update { it?.copy(selection = FoodPortionDraft(selected,
            quantity = food.lastQuantity?.takeIf { food.lastUnit == selected.basisUnit } ?: selected.basisQuantity)) }
    }

    fun clearSelection() {
        if (!editable()) return
        selectionVersion++
        mutableState.update { it?.copy(selection = null, chosenComposition = null, estimate = FoodQuery()) }
    }

    fun selectComposition(composition: FoodComposition) {
        if (!editable()) return
        selectionVersion++
        mutableState.update { it?.copy(selection = null, chosenComposition = composition, compositionCount = "1", estimate = FoodQuery()) }
    }

    fun updatePortion(quantity: String, unit: String?) = updateSelection { copy(quantity = quantity, unit = unit ?: this.unit) }
    fun choosePortion(portion: FoodPortion?) = updateSelection { copy(portion = portion, count = "1") }
    fun updateCount(count: String) = updateSelection { copy(count = count) }
    fun updateCompositionCount(count: String) { if (editable()) mutableState.update { it?.copy(compositionCount = count) } }
    fun updateMealKind(kind: FoodMealKind?) { if (editable()) mutableState.update { it?.copy(mealKind = kind) } }

    private fun updateSelection(update: FoodPortionDraft.() -> FoodPortionDraft) {
        if (!editable()) return
        selectionVersion++
        mutableState.update { it?.copy(selection = it.selection?.update(), estimate = FoodQuery()) }
    }

    suspend fun estimate() {
        val current = activeLease() ?: return
        val selected = state.value?.selection ?: return
        val item = runCatching { foodPortionItem(selected) }.getOrNull() ?: run { invalid(); return }
        val version = selectionVersion
        mutableState.update { it?.copy(estimate = FoodQuery(loading = true)) }
        val body = buildJsonObject { put("items", JsonArray(listOf(item))) }.toString().encodeToByteArray()
        val outcome = http.request("POST", "/v1/nutrition/estimate", body, lease = current)
        if (activeLease() !== current || version != selectionVersion) return
        val result: FoodQuery<FoodEstimate> = when (outcome) {
            is HttpOutcome.Success -> runCatching { decodeFoodEstimate(outcome.body) }.getOrNull()?.let { FoodQuery(it) }
                ?: FoodQuery(error = GeneratedStrings.Errors.server)
            else -> FoodQuery(error = nativeError(outcome))
        }
        mutableState.update { it?.copy(estimate = result) }
    }

    suspend fun record() {
        val current = activeLease() ?: return
        val before = state.value ?: return
        val selected = before.selection ?: return
        val date = diary()?.selectedDay ?: return
        val version = selectionVersion
        val operation = runCatching { addFoodOperation(selected, date, before.mealKind) }.getOrNull() ?: run { invalid(); return }
        if (perform(current, operation) != null && activeLease() === current && version == selectionVersion) close()
    }

    suspend fun logComposition() {
        val current = activeLease() ?: return
        val before = state.value ?: return
        val composition = before.chosenComposition ?: return
        val date = diary()?.selectedDay ?: return
        val version = selectionVersion
        val operation = runCatching { logFoodCompositionOperation(composition, before.compositionCount, date, before.mealKind) }.getOrNull()
            ?: run { invalid(); return }
        if (perform(current, operation) != null && activeLease() === current && version == selectionVersion) close()
    }

    suspend fun toggleFavorite(food: FoodRemembered) {
        val current = activeLease() ?: return
        if (perform(current, favoriteFoodOperation(food)) != null && activeLease() === current) refreshMemory()
    }

    suspend fun repeatRemembered(food: FoodRemembered) {
        val current = activeLease() ?: return
        val catalog = food.catalogItem() ?: run { invalid(); return }
        if (!food.repeatable) return
        val date = diary()?.selectedDay ?: return
        val operation = runCatching {
            addFoodOperation(FoodPortionDraft(catalog, requireNotNull(food.lastQuantity), requireNotNull(food.lastUnit)), date,
                state.value?.mealKind ?: defaultKind())
        }.getOrNull() ?: run { invalid(); return }
        if (perform(current, operation) != null && activeLease() === current) close()
    }

    suspend fun createFood(name: String, brand: String, basisQuantity: String, basisUnit: String, nutrients: Map<String, String?>) {
        val current = activeLease() ?: return
        val operation = runCatching { createCatalogFoodOperation(name, brand, basisQuantity, basisUnit, nutrients) }.getOrNull()
            ?: run { invalid(); return }
        val receipt = perform(current, operation) ?: return
        if (activeLease() !== current) return
        val foodId = receipt.foodId ?: return
        val snapshotId = receipt.snapshotId ?: return
        select(FoodCatalogItem(foodId, snapshotId, name.trim(), brand.trim().takeIf { it.isNotEmpty() },
            requireNotNull(parseFoodAmount(basisQuantity)), basisUnit,
            FOOD_NUTRIENTS.associateWith { key -> nutrients[key]?.takeIf { it.isNotBlank() }?.let { plainFoodDecimal(it.trim().replace(',', '.')) } }, personal = true))
    }

    private fun defaultKind(): FoodMealKind? {
        val state = diary() ?: return null
        if (state.selectedDay != state.today) return null
        val hour = Instant.fromEpochMilliseconds(nowMs()).toLocalDateTime(TimeZone.of(state.timezone)).hour
        return when (hour) { in 5..11 -> FoodMealKind.Breakfast; in 12..15 -> FoodMealKind.Lunch; in 16..21 -> FoodMealKind.Dinner; else -> FoodMealKind.Snack }
    }
    private fun editable(): Boolean = activeLease() != null && diary()?.busy != true
    private var writing = false
    private suspend fun perform(current: SessionLease, operation: FoodOperation): FoodReceipt? {
        if (writing || diary()?.busy == true) return null
        writing = true
        try { return operate(current, operation) }
        finally { writing = false }
    }
    private fun activeLease(): SessionLease? = lease?.takeIf { sessions.isCurrent(it) && diary()?.canRead == true && state.value?.open == true }

    private suspend fun <T> read(current: SessionLease, path: String, decode: (ByteArray) -> T): FoodQuery<T> =
        when (val outcome = http.request("GET", path, lease = current)) {
            is HttpOutcome.Success -> runCatching { decode(outcome.body) }.getOrNull()?.let { FoodQuery(it) }
                ?: FoodQuery(error = GeneratedStrings.Errors.server)
            else -> FoodQuery(error = nativeError(outcome))
        }

    private fun <T> FoodQuery<T>.retaining(previous: T?): FoodQuery<T> =
        if (error != null && previous != null) copy(data = previous, stale = true) else this
}
