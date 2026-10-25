package assistant.mobile.core.assistant

import assistant.mobile.core.AccountChangeEvent
import assistant.mobile.core.EventDomain
import assistant.mobile.core.EventInvalidation
import assistant.mobile.core.GeneratedStrings
import assistant.mobile.core.HttpOutcome
import assistant.mobile.core.LocalizedString
import assistant.mobile.core.NativeHttpClient
import assistant.mobile.core.NativeJson
import assistant.mobile.core.SessionLease
import assistant.mobile.core.SessionManager
import assistant.mobile.core.affectedDomains
import io.ktor.http.encodeURLParameter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.time.Clock
import kotlin.time.Instant

enum class AssistantSuggestionMode { Ask, Start }

data class AssistantSuggestion(
    val key: String,
    val mode: AssistantSuggestionMode,
    val label: LocalizedString,
    val text: LocalizedString = label,
    val trailingSpace: Boolean = false,
)

@Serializable
enum class AssistantMealKind {
    @SerialName("breakfast") Breakfast,
    @SerialName("lunch") Lunch,
    @SerialName("dinner") Dinner,
    @SerialName("snack") Snack,
}

fun suggestedAssistantMealKind(hour: Int): AssistantMealKind {
    require(hour in 0..23)
    return when (hour) {
        in 5..11 -> AssistantMealKind.Breakfast
        in 12..15 -> AssistantMealKind.Lunch
        in 16..21 -> AssistantMealKind.Dinner
        else -> AssistantMealKind.Snack
    }
}

data class AssistantSuggestionDay(
    val hour: Int,
    val meal: AssistantMealKind,
    val mealRecorded: Boolean?,
    val foodRecorded: Boolean?,
    val tasksToday: Boolean?,
)

/** Clock and authorized day snapshots only; the person's words are never interpreted here. */
fun assistantDaySuggestions(day: AssistantSuggestionDay): List<AssistantSuggestion> = buildList {
    require(day.hour in 0..23)
    if (day.mealRecorded == false) when (day.meal) {
        AssistantMealKind.Breakfast -> add(AssistantSuggestion("breakfast", AssistantSuggestionMode.Start,
            GeneratedStrings.Assistant.Suggestions.breakfast, GeneratedStrings.Assistant.Suggestions.breakfastStart, true))
        AssistantMealKind.Lunch -> add(AssistantSuggestion("lunch", AssistantSuggestionMode.Start,
            GeneratedStrings.Assistant.Suggestions.lunch, GeneratedStrings.Assistant.Suggestions.lunchStart, true))
        AssistantMealKind.Dinner -> add(AssistantSuggestion("dinner", AssistantSuggestionMode.Start,
            GeneratedStrings.Assistant.Suggestions.dinner, GeneratedStrings.Assistant.Suggestions.dinnerStart, true))
        AssistantMealKind.Snack -> Unit
    }
    if (day.tasksToday == true) add(if (day.hour < 12)
        AssistantSuggestion("dayAhead", AssistantSuggestionMode.Ask, GeneratedStrings.Assistant.Suggestions.dayAhead)
        else AssistantSuggestion("dayLeft", AssistantSuggestionMode.Ask, GeneratedStrings.Assistant.Suggestions.dayLeft))
    if (day.hour >= 20 && day.foodRecorded == true)
        add(AssistantSuggestion("summary", AssistantSuggestionMode.Ask, GeneratedStrings.Assistant.Suggestions.summary))
}

fun quietAssistantConversation(lastMessageAt: String?, nowMillis: Long): Boolean =
    lastMessageAt == null || runCatching {
        nowMillis - Instant.parse(lastMessageAt).toEpochMilliseconds() >= 30 * 60_000
    }.getOrDefault(false)

data class AssistantSuggestionsState(
    val settled: Boolean = false,
    val nowMillis: Long = 0,
    val items: List<AssistantSuggestion> = emptyList(),
)

/** Resolve text in the UI, then remove duplicate translated text before drawing the row. */
fun assistantSuggestionItems(state: AssistantSuggestionsState, includeExamples: Boolean): List<AssistantSuggestion> {
    if (!state.settled) return emptyList()
    if (!includeExamples) return state.items
    val examples = listOf(GeneratedStrings.Assistant.Examples.item0, GeneratedStrings.Assistant.Examples.item1,
        GeneratedStrings.Assistant.Examples.item2, GeneratedStrings.Assistant.Examples.item3)
    return state.items + examples.mapIndexed { index, text ->
        AssistantSuggestion("example$index", AssistantSuggestionMode.Start, text)
    }
}

@Serializable
internal data class AssistantSuggestionFoodItem(
    val id: String,
    val kind: String? = null,
    @SerialName("meal_kind") val mealKind: AssistantMealKind? = null,
    val components: List<AssistantSuggestionFoodItem> = emptyList(),
) {
    val recordedKind: AssistantMealKind? get() = if (kind == "dish") components.firstOrNull()?.mealKind else mealKind
}

@Serializable
internal data class AssistantSuggestionFoodDraft(
    val id: String,
    @SerialName("meal_kind") val mealKind: AssistantMealKind? = null,
)

@Serializable
internal data class AssistantSuggestionDiary(
    val items: List<AssistantSuggestionFoodItem>,
    val drafts: List<AssistantSuggestionFoodDraft>,
) {
    fun mealRecorded(kind: AssistantMealKind): Boolean =
        items.any { it.recordedKind == kind } || drafts.any { it.mealKind == kind }
    val foodRecorded: Boolean get() = items.isNotEmpty() || drafts.isNotEmpty()
}

@Serializable
private data class AssistantSuggestionTask(val id: String)

@Serializable
private data class AssistantSuggestionTasks(val items: List<AssistantSuggestionTask>)

/** Disposable day reads share the native lease and the existing account change stream. */
class AssistantSuggestionsController(
    scope: CoroutineScope,
    private val sessions: SessionManager,
    private val http: NativeHttpClient,
    private val clockMs: () -> Long = { Clock.System.now().toEpochMilliseconds() },
) {
    private val visible = MutableStateFlow(false)
    private val tick = MutableStateFlow(clockMs())
    private val revision = MutableStateFlow(0L)
    private val mutableState = MutableStateFlow(AssistantSuggestionsState(nowMillis = clockMs()))
    val state: StateFlow<AssistantSuggestionsState> = mutableState.asStateFlow()
    private var loadedKey: SuggestionReadKey? = null
    private var diary: AssistantSuggestionDiary? = null
    private var tasksToday: Boolean? = null

    init {
        scope.launch {
            visible.collectLatest { active ->
                if (active) {
                    tick.value = clockMs()
                    while (true) {
                        delay(60_000)
                        tick.value = clockMs()
                    }
                }
            }
        }
        scope.launch {
            combine(sessions.leases, sessions.identity, visible, tick, revision) { lease, identity, active, now, version ->
                if (!active || lease == null || identity == null || identity.id != lease.namespace.userId) null
                else {
                    val zone = runCatching { TimeZone.of(identity.timezone) }.getOrDefault(TimeZone.UTC)
                    val local = Instant.fromEpochMilliseconds(now).toLocalDateTime(zone)
                    SuggestionContext(SuggestionReadKey(lease, local.date.toString(), identity.timezone,
                        "nutrition.read" in identity.scopes, "nutrition.write" in identity.scopes,
                        "tasks.read" in identity.scopes, version), local.hour, now)
                }
            }.collectLatest { context ->
                if (context == null) {
                    loadedKey = null
                    diary = null
                    tasksToday = null
                    mutableState.value = AssistantSuggestionsState(nowMillis = clockMs())
                    return@collectLatest
                }
                val key = context.key
                if (loadedKey != key) {
                    mutableState.value = AssistantSuggestionsState(nowMillis = context.nowMillis)
                    val reads = coroutineScope {
                        val food = async { if (key.readsFood) readDiary(key) else null }
                        val tasks = async { if (key.readsTasks) readTasks(key.lease) else null }
                        food.await() to tasks.await()
                    }
                    if (!sessions.isCurrent(key.lease) || !visible.value) return@collectLatest
                    diary = reads.first
                    tasksToday = reads.second
                    loadedKey = key
                }
                val meal = suggestedAssistantMealKind(context.hour)
                mutableState.value = AssistantSuggestionsState(true, context.nowMillis,
                    assistantDaySuggestions(AssistantSuggestionDay(context.hour, meal,
                        if (key.writesFood) diary?.mealRecorded(meal) else null, diary?.foodRecorded, tasksToday)))
            }
        }
    }

    fun activate() { visible.value = true }

    fun deactivate() {
        visible.value = false
        mutableState.value = AssistantSuggestionsState(nowMillis = clockMs())
    }

    fun refresh() { revision.value += 1 }

    fun onChange(event: AccountChangeEvent) {
        val lease = sessions.lease() ?: return
        if (event.namespace != lease.namespace || event.generation != lease.generation) return
        when (val affected = affectedDomains(event.topic)) {
            EventInvalidation.All -> refresh()
            is EventInvalidation.Domains -> if (affected.values.any { it == EventDomain.Nutrition || it == EventDomain.Tasks }) refresh()
        }
    }

    private suspend fun readDiary(key: SuggestionReadKey): AssistantSuggestionDiary? = optionalRead {
        when (val response = http.request("GET", "/v1/nutrition/day?date=${key.day.encodeURLParameter()}", lease = key.lease)) {
            is HttpOutcome.Success -> NativeJson.decodeFromString<AssistantSuggestionDiary>(response.body.decodeToString())
            else -> null
        }
    }

    private suspend fun readTasks(lease: SessionLease): Boolean? = optionalRead {
        when (val response = http.request("GET", "/v1/tasks/items?view=today&sort=created&limit=5", lease = lease)) {
            is HttpOutcome.Success -> NativeJson.decodeFromString<AssistantSuggestionTasks>(response.body.decodeToString()).items.isNotEmpty()
            else -> null
        }
    }
}

private data class SuggestionReadKey(
    val lease: SessionLease,
    val day: String,
    val zone: String,
    val readsFood: Boolean,
    val writesFood: Boolean,
    val readsTasks: Boolean,
    val revision: Long,
)

private data class SuggestionContext(val key: SuggestionReadKey, val hour: Int, val nowMillis: Long)

private suspend fun <T> optionalRead(read: suspend () -> T?): T? = try {
    read()
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (_: Exception) {
    null
}
