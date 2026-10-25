package assistant.mobile.core.personal

import assistant.mobile.core.food.FoodDay
import assistant.mobile.core.food.plainFoodDecimal

/** The same selected-day projection as dashboardNutrition.ts; totals are never rebuilt from items. */
data class PersonalNutritionSummary(
    val logged: Boolean,
    val completed: Boolean,
    val energy: String?,
    val protein: String?,
    val fat: String?,
    val carbs: String?,
    val energyComplete: Boolean,
    val proteinComplete: Boolean?,
    val target: String?,
    val minimum: String?,
    val remaining: String?,
    val drafts: Int,
)

enum class PersonalNutritionStatus {
    Empty, GoalsMissing, DayOpen, EnergyUnknown, BothGoals, OverLimit, ProteinLow, ProteinUnknown,
}

fun personalNutritionSummary(day: FoodDay): PersonalNutritionSummary {
    val logged = day.entries.isNotEmpty()
    val energyComplete = day.totals["energy_kcal"]?.complete == true
    fun amount(key: String): String? = if (logged) day.totals[key]?.known else null
    return PersonalNutritionSummary(
        logged, day.completed && logged && day.drafts.isEmpty() && energyComplete,
        amount("energy_kcal"), amount("protein_g"), amount("fat_g"), amount("carbohydrate_g"),
        energyComplete, day.totals["protein_g"]?.complete,
        day.goal?.calorieTarget, day.goal?.macroTargets?.get("protein_g"),
        day.remainingKcal?.takeIf { logged && day.remainingComplete && it.isNotBlank() && it.toDoubleOrNull()?.isFinite() == true },
        day.drafts.size,
    )
}

fun personalNutritionStatus(row: PersonalNutritionSummary): PersonalNutritionStatus {
    if (!row.logged) return PersonalNutritionStatus.Empty
    if (comparePersonalNutritionAmounts(row.target, "0") == null ||
        comparePersonalNutritionAmounts(row.minimum, "0") == null) return PersonalNutritionStatus.GoalsMissing
    if (!row.completed) return PersonalNutritionStatus.DayOpen
    val energy = comparePersonalNutritionAmounts(row.energy, row.target)
    if (!row.energyComplete || energy == null) return PersonalNutritionStatus.EnergyUnknown
    val protein = comparePersonalNutritionAmounts(row.protein, row.minimum)
    if (energy <= 0 && protein != null && protein >= 0) return PersonalNutritionStatus.BothGoals
    if (energy > 0) return PersonalNutritionStatus.OverLimit
    if (protein != null && protein < 0 && row.proteinComplete == true) return PersonalNutritionStatus.ProteinLow
    return PersonalNutritionStatus.ProteinUnknown
}

/** Status compares original decimals, including Python Decimal exponents, before display rounding. */
internal fun comparePersonalNutritionAmounts(first: String?, second: String?): Int? {
    fun parts(value: String?): Pair<String, String>? {
        val plain = value?.let(::plainFoodDecimal) ?: return null
        if (plain.startsWith('-') || plain.toDoubleOrNull()?.isFinite() != true) return null
        return plain.substringBefore('.').trimStart('0').ifEmpty { "0" } to plain.substringAfter('.', "").trimEnd('0')
    }
    val a = parts(first) ?: return null
    val b = parts(second) ?: return null
    if (a.first.length != b.first.length) return a.first.length.compareTo(b.first.length)
    val whole = a.first.compareTo(b.first)
    if (whole != 0) return whole
    val places = maxOf(a.second.length, b.second.length)
    return a.second.padEnd(places, '0').compareTo(b.second.padEnd(places, '0'))
}
