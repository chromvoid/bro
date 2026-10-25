package assistant.mobile.core.food

import assistant.mobile.core.AccountNamespace
import assistant.mobile.core.LocalizedString
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.LocalDate
import kotlinx.datetime.plus
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonObject

enum class FoodStatsPeriodKind(val wire: String) { Week("week"), Month("month") }
enum class FoodStatsExportFormat(val wire: String, val mimeType: String) {
    Csv("csv", "text/csv"), Json("json", "application/json")
}

data class FoodStatsPeriod(val kind: FoodStatsPeriodKind, val start: String, val end: String)

fun foodStatsPeriod(kind: FoodStatsPeriodKind, day: String): FoodStatsPeriod {
    val date = LocalDate.parse(day)
    if (kind == FoodStatsPeriodKind.Week) {
        val start = foodWeekStart(day)
        return FoodStatsPeriod(kind, start, shiftFoodDay(start, 6))
    }
    val first = LocalDate(date.year, date.month, 1)
    val next = first.plus(DatePeriod(months = 1))
    return FoodStatsPeriod(kind, first.toString(), next.plus(DatePeriod(days = -1)).toString())
}

fun shiftFoodStatsPeriod(period: FoodStatsPeriod, step: Int): FoodStatsPeriod {
    require(step == -1 || step == 1)
    return foodStatsPeriod(period.kind, shiftFoodDay(if (step < 0) period.start else period.end, step))
}

/** Values are the server's daily totals. Missing completeness never proves a complete total. */
data class FoodStatsDay(
    val date: String,
    val logged: Boolean,
    val energy: FoodTotal?,
    val macros: Map<String, FoodTotal?>,
    val completed: Boolean,
    val target: String?,
    val macroTargets: Map<String, String?>,
    val kcalByKind: Map<String, String?>,
) {
    // The range API exposes completeness only for energy and protein. Missing fat/carbohydrate
    // flags keep their amounts partial, but do not by themselves contradict a completed day.
    val incomplete: Boolean get() = logged && (!completed || energy?.complete != true || macros["protein_g"]?.complete != true)
}

data class FoodStatsRange(val days: List<FoodStatsDay>)
data class FoodStatsMacro(val average: FoodTotal?, val target: String?)
data class FoodStatsKind(val kind: String, val kcal: FoodTotal, val share: Double)
data class FoodStatsSummary(
    val days: Int,
    val logged: Int,
    val completed: Int,
    val energy: FoodTotal?,
    val target: String?,
    val macros: Map<String, FoodStatsMacro>,
    val kinds: List<FoodStatsKind>,
    val nearGoal: Int,
    val overGoal: Int,
    val underGoal: Int,
    val withGoal: Int,
    val energyComplete: Boolean,
)

data class FoodStatsState(
    val namespace: AccountNamespace,
    val today: String,
    val locale: String,
    val canRead: Boolean,
    val period: FoodStatsPeriod,
    val anchorDay: String = period.start,
    val range: FoodQuery<FoodStatsRange> = FoodQuery(),
    val exporting: Boolean = false,
    val error: LocalizedString? = null,
) {
    val summary: FoodStatsSummary get() = summarizeFoodStats(range.data?.days.orEmpty())
    val canGoLater: Boolean get() = period.end < today
}

val FOOD_STATS_MACROS = listOf("protein_g", "fat_g", "carbohydrate_g")
private val foodStatsKinds = FoodMealKind.entries.map { it.wire } + "none"

fun decodeFoodStatsRange(bytes: ByteArray, expected: FoodStatsPeriod? = null): FoodStatsRange {
    val rows = requireNotNull(foodObject(bytes)["days"] as? JsonArray)
    val days = rows.map { element ->
        val row = element.jsonObject
        val date = row.statsString("date").also { require(LocalDate.parse(it).toString() == it) }
        fun total(key: String, completeness: String) = row.statsDecimal(key)?.let { FoodTotal(it, row.statsOptionalBoolean(completeness) ?: false) }
        FoodStatsDay(date, row.statsBoolean("logged"), total("energy_kcal", "energy_complete"),
            FOOD_STATS_MACROS.associateWith { total(it, it.removeSuffix("_g") + "_complete") },
            row.statsBoolean("completed"), row.statsDecimal("target"),
            row.statsObject("macro_targets")?.filterKeys { it in FOOD_STATS_MACROS }?.mapValues { statsDecimal(it.value) }.orEmpty(),
            requireNotNull(row.statsObject("kcal_by_kind")).also { require(it.keys.all { key -> key in foodStatsKinds }) }
                .mapValues { statsDecimal(it.value) })
    }
    require(days.map { it.date }.distinct().size == days.size)
    if (expected != null) {
        val dates = buildList {
            var day = expected.start
            while (day <= expected.end) { add(day); day = shiftFoodDay(day, 1) }
        }
        require(days.map { it.date } == dates) { "Nutrition range does not match requested dates" }
    }
    return FoodStatsRange(days)
}

/** As in the web fixture, empty days never dilute a per-logged-day average. */
fun summarizeFoodStats(days: List<FoodStatsDay>): FoodStatsSummary {
    val logged = days.filter { it.logged }
    val goals = logged.filter { it.target != null }
    fun average(values: List<String?>): String? = if (values.isEmpty() || values.all { it == null }) null
        else statsMean(values.filterNotNull(), values.size)
    fun total(values: List<FoodTotal?>): FoodTotal? = average(values.map { it?.known })?.let {
        FoodTotal(it, values.all { value -> value?.complete == true })
    }
    val macros = FOOD_STATS_MACROS.associateWith { key ->
        FoodStatsMacro(total(logged.map { it.macros[key] }), average(logged.mapNotNull { it.macroTargets[key] }))
    }
    val kindTotals = foodStatsKinds.mapNotNull { kind ->
        val values = logged.mapNotNull { it.kcalByKind[kind] }
        if (values.isEmpty()) null else kind to statsMean(values, 1)
    }
    // Floating point is confined to chart geometry and comparison; displayed amounts stay decimal.
    val allKinds = kindTotals.sumOf { it.second.toDoubleOrNull() ?: 0.0 }
    val energyComplete = logged.isNotEmpty() && logged.all { it.energy?.complete == true }
    val kinds = if (allKinds > 0 && allKinds.isFinite()) kindTotals.mapNotNull { (kind, amount) ->
        val numeric = amount.toDoubleOrNull() ?: return@mapNotNull null
        if (numeric <= 0) null else FoodStatsKind(kind, FoodTotal(amount, energyComplete), numeric / allKinds)
    } else emptyList()
    val knownGoals = goals.filter { it.energy?.complete == true && (it.target?.toDoubleOrNull() ?: 0.0) > 0 }
    fun offset(day: FoodStatsDay) = (requireNotNull(day.energy).known.toDouble() - requireNotNull(day.target).toDouble()) / day.target.toDouble()
    return FoodStatsSummary(days.size, logged.size, days.count { it.completed }, total(logged.map { it.energy }),
        average(goals.map { it.target }), macros, kinds,
        knownGoals.count { kotlin.math.abs(offset(it)) <= .1 }, knownGoals.count { offset(it) > .1 },
        knownGoals.count { offset(it) < -.1 }, goals.size, energyComplete)
}

/** Sum decimal strings and divide by a small day count without changing persisted quantities. */
private fun statsMean(values: List<String>, count: Int): String {
    require(count > 0)
    val plain = values.map { requireNotNull(plainFoodDecimal(it)) }
    val scale = plain.maxOfOrNull { it.substringAfter('.', "").length } ?: 0
    var sum = "0"
    for (value in plain) {
        val digits = value.substringBefore('.') + value.substringAfter('.', "").padEnd(scale, '0')
        val width = maxOf(sum.length, digits.length)
        val left = sum.padStart(width, '0')
        val right = digits.padStart(width, '0')
        var carry = 0
        val result = StringBuilder()
        for (index in width - 1 downTo 0) {
            val digit = left[index].digitToInt() + right[index].digitToInt() + carry
            result.append(digit % 10); carry = digit / 10
        }
        if (carry != 0) result.append(carry)
        sum = result.reverse().toString().trimStart('0').ifEmpty { "0" }
    }
    val quotient = StringBuilder()
    var remainder = 0
    val precision = maxOf(scale, 6)
    for (digit in sum + "0".repeat(precision - scale)) {
        remainder = remainder * 10 + digit.digitToInt()
        quotient.append(remainder / count); remainder %= count
    }
    val digits = quotient.toString().padStart(precision + 1, '0')
    val whole = digits.dropLast(precision).trimStart('0').ifEmpty { "0" }
    val fraction = digits.takeLast(precision).trimEnd('0')
    return whole + if (fraction.isEmpty()) "" else ".$fraction"
}

private fun JsonObject.statsString(key: String): String = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: error("Expected string")
private fun JsonObject.statsBoolean(key: String): Boolean = requireNotNull(statsOptionalBoolean(key))
private fun JsonObject.statsOptionalBoolean(key: String): Boolean? = this[key]?.takeUnless { it == JsonNull }?.let {
    requireNotNull((it as? JsonPrimitive)?.takeUnless { value -> value.isString }?.booleanOrNull)
}
private fun JsonObject.statsObject(key: String): JsonObject? = this[key]?.takeUnless { it == JsonNull }?.let {
    it as? JsonObject ?: error("Expected object")
}
private fun JsonObject.statsDecimal(key: String): String? = statsDecimal(this[key])
private fun statsDecimal(value: JsonElement?): String? {
    if (value == null || value == JsonNull) return null
    val text = (value as? JsonPrimitive)?.takeIf { it.isString }?.content ?: error("Expected decimal string")
    val plain = requireNotNull(plainFoodDecimal(text))
    require(!plain.startsWith('-'))
    return text
}
