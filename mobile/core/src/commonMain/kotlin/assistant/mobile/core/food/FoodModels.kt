package assistant.mobile.core.food

import assistant.mobile.core.AccountNamespace
import assistant.mobile.core.LocalizedString
import assistant.mobile.core.journal.Submission
import assistant.mobile.core.today.formatTodayAmount
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.plus
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.Serializable
import kotlin.time.Instant

/** The server owns all nutritional quantities and totals. Decimal values never become doubles. */
data class FoodTotal(val known: String, val complete: Boolean) {
    val unknown: Boolean get() = !complete && sameFoodAmount(known, "0")
}

enum class FoodMealKind(val wire: String) {
    Breakfast("breakfast"), Lunch("lunch"), Dinner("dinner"), Snack("snack");
    companion object {
        fun fromWire(value: String): FoodMealKind = entries.first { it.wire == value }
    }
}

sealed interface FoodItem {
    val id: String
    val revision: Long
    val quantity: String
    val unit: String
    val nutrients: Map<String, String?>
    val name: String
    val date: String?
    val estimated: Boolean
    val kind: String
    val mealKind: FoodMealKind?
    val mealId: String?
}

data class FoodEntry(
    override val id: String,
    override val revision: Long,
    override val quantity: String,
    override val unit: String,
    override val date: String,
    override val name: String,
    override val nutrients: Map<String, String?> = emptyMap(),
    override val estimated: Boolean = false,
    override val mealKind: FoodMealKind? = null,
    override val mealId: String? = null,
    val dishId: String? = null,
    val foodId: String? = null,
    val snapshotId: String? = null,
    val loggedAt: String? = null,
    val estimationEvidence: String? = null,
    val sourceProvider: String? = null,
) : FoodItem { override val kind: String get() = "entry" }

data class FoodDish(
    override val id: String,
    override val revision: Long,
    override val quantity: String,
    override val unit: String,
    override val date: String?,
    override val name: String,
    val components: List<FoodEntry>,
    override val nutrients: Map<String, String?> = emptyMap(),
    override val estimated: Boolean = false,
) : FoodItem {
    override val kind: String get() = "dish"
    override val mealKind: FoodMealKind? get() = components.firstOrNull()?.mealKind
    override val mealId: String? get() = components.firstOrNull()?.mealId
}

data class FoodGoal(
    val id: String,
    val calorieTarget: String,
    val effectiveDate: String,
    val revision: Long,
    val macroTargets: Map<String, String?> = emptyMap(),
)

data class FoodUnresolvedDraft(
    val id: String,
    val label: String,
    val status: String,
    val missingFields: List<String>,
    val revision: Long,
    val mealKind: FoodMealKind?,
)

data class FoodDay(
    val date: String,
    val dayRevision: Long,
    val completed: Boolean,
    /** Flat ledger projection, retained for identities; never added to [items] for totals. */
    val entries: List<FoodEntry>,
    val items: List<FoodItem>,
    val totals: Map<String, FoodTotal>,
    val goal: FoodGoal?,
    val remainingKcal: String?,
    val remainingComplete: Boolean,
    val drafts: List<FoodUnresolvedDraft>,
) {
    val sections: List<FoodKindSection> get() = foodByKind(items)
}

data class FoodWeekDay(val date: String, val energyKcal: String, val completed: Boolean, val target: String?)
data class FoodItemTarget(val kind: String, val id: String, val date: String, val revision: Long, val status: String)
data class FoodAppliedEntry(val id: String, val revision: Long?)
data class FoodPreviousMeal(val id: String, val previousKind: FoodMealKind?)

data class FoodReceipt(
    val operationId: String,
    val kind: String,
    val entryId: String? = null,
    val dishId: String? = null,
    val revision: Long? = null,
    val expectedRevisions: Map<String, Long> = emptyMap(),
    val appliedEntries: List<FoodAppliedEntry> = emptyList(),
    val appliedDishes: Map<String, Long> = emptyMap(),
    val undoStatus: String? = null,
    val undoneOperationId: String? = null,
    val previousMeals: List<FoodPreviousMeal> = emptyList(),
    val foodId: String? = null,
    val snapshotId: String? = null,
    val compositionId: String? = null,
) {
    /** The resulting revisions in the receipt authorize a separate, guarded undo operation. */
    val undoRevisions: Map<String, Long> get() = buildMap {
        putAll(expectedRevisions)
        putAll(appliedDishes)
        if (dishId != null && revision != null) put(dishId, revision)
        // The aggregate revision guards all ingredients atomically. A 20-ingredient dish plus
        // its aggregate would exceed the ledger's 20-target bound if expanded here.
        if (dishId == null) appliedEntries.forEach { if (it.revision != null) put(it.id, it.revision) }
        if (entryId != null && revision != null) put(entryId, revision)
    }
    val canUndo: Boolean get() = undoStatus !in setOf("undone", "changed", "unsupported") &&
        undoRevisions.isNotEmpty() && kind != "operation.undo"
}

data class FoodQuery<T>(val data: T? = null, val loading: Boolean = false, val error: LocalizedString? = null, val stale: Boolean = false)

/** Exact user text and original revisions survive conflicts and process recreation. */
@Serializable
data class FoodEditDraft(
    val kind: String,
    val id: String,
    val name: String,
    val quantity: String,
    val unit: String,
    val date: String,
    val revision: Long,
    val mealKind: String? = null,
    val components: Map<String, String> = emptyMap(),
    val originalQuantity: String = quantity,
    val originalDate: String = date,
    val originalName: String = name,
    val originalMealKind: String? = mealKind,
    val originalComponents: Map<String, String> = components,
)

data class FoodState(
    val namespace: AccountNamespace,
    val selectedDay: String,
    val today: String,
    val timezone: String,
    val locale: String,
    val canRead: Boolean,
    val canWrite: Boolean,
    val day: FoodQuery<FoodDay> = FoodQuery(),
    val week: FoodQuery<List<FoodWeekDay>> = FoodQuery(),
    val draft: FoodEditDraft? = null,
    val receipt: FoodReceipt? = null,
    val pending: List<Submission> = emptyList(),
    val busy: Boolean = false,
    val conflict: Boolean = false,
    val error: LocalizedString? = null,
)

fun foodToday(timezone: String, nowMillis: Long): String =
    Instant.fromEpochMilliseconds(nowMillis).toLocalDateTime(TimeZone.of(timezone)).date.toString()

fun shiftFoodDay(day: String, amount: Int): String = LocalDate.parse(day).plus(DatePeriod(days = amount)).toString()
fun foodWeekStart(day: String): String {
    val value = LocalDate.parse(day)
    return shiftFoodDay(day, -value.dayOfWeek.ordinal)
}

/** Only decimal syntax is normalized; this never computes nutrients or changes server amounts. */
fun parseFoodAmount(value: String): String? {
    val text = plainFoodDecimal(value.trim().replace(',', '.')) ?: return null
    if (text.startsWith('-')) return null
    val parts = text.split('.')
    if (parts.size !in 1..2 || parts[0].isEmpty() || parts.any { it.any { c -> c !in '0'..'9' } } ||
        (parts.size == 2 && parts[1].isEmpty()) || text.none { it in '1'..'9' }) return null
    return text
}

fun sameFoodAmount(first: String?, second: String?): Boolean {
    fun normalize(value: String): String {
        val plain = plainFoodDecimal(value) ?: return value
        val negative = plain.startsWith('-')
        val pieces = plain.removePrefix("-").split('.')
        val whole = pieces[0].trimStart('0').ifEmpty { "0" }
        val fractional = pieces.getOrElse(1) { "" }.trimEnd('0')
        return (if (negative && (whole != "0" || fractional.isNotEmpty())) "-" else "") + whole + if (fractional.isEmpty()) "" else ".$fractional"
    }
    return first == second || (first != null && second != null && normalize(first) == normalize(second))
}

val FOOD_NUTRIENTS = listOf("energy_kcal", "protein_g", "fat_g", "carbohydrate_g", "fiber_g")

/** Python Decimal's wire form includes e.g. 0E-9 after server-side dish scaling. */
fun plainFoodDecimal(value: String): String? {
    if (value.isEmpty() || value.length > 1024) return null
    val negative = value.startsWith('-')
    val unsigned = value.removePrefix("-").removePrefix("+")
    if (value.startsWith("-+") || value.startsWith("+-")) return null
    val exponentParts = unsigned.split('e', 'E')
    if (exponentParts.size !in 1..2) return null
    val exponent = if (exponentParts.size == 2) exponentParts[1].toIntOrNull() ?: return null else 0
    if (exponent !in -1024..1024) return null
    val parts = exponentParts[0].split('.')
    if (parts.size !in 1..2 || parts.any { it.isEmpty() || it.any { c -> c !in '0'..'9' } }) return null
    val digits = parts.joinToString("")
    val point = parts[0].length + exponent
    val plain = when {
        point <= 0 -> "0." + "0".repeat(-point) + digits
        point >= digits.length -> digits + "0".repeat(point - digits.length)
        else -> digits.substring(0, point) + "." + digits.substring(point)
    }
    return (if (negative) "-" else "") + plain
}

fun formatFoodAmount(value: String?, fraction: Int = 1, locale: String = "ru"): String =
    formatTodayAmount(value?.let(::plainFoodDecimal), fraction, locale)
