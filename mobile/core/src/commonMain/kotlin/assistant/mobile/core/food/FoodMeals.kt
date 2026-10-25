package assistant.mobile.core.food

import kotlin.time.Instant

data class FoodMeal(val key: String, val at: String?, val items: List<FoodItem>, val mealIds: List<String>)
data class FoodKindSection(val kind: FoodMealKind?, val meals: List<FoodMeal>)

/** Web meals.ts grouping semantics; this projection never calculates nutritional totals. */
fun foodMeals(items: List<FoodItem>): List<FoodMeal> {
    data class Positioned(val item: FoodItem, val at: Long?, val meal: String?, val index: Int)
    val ordered = items.mapIndexed { index, item ->
        val entries = if (item is FoodDish) item.components else listOf(item as FoodEntry)
        val at = entries.mapNotNull { it.loggedAt?.let { raw -> runCatching { Instant.parse(raw).toEpochMilliseconds() }.getOrNull() } }.minOrNull()
        Positioned(item, at, entries.firstOrNull()?.mealId, index)
    }.sortedWith(compareBy<Positioned> { it.at ?: Long.MAX_VALUE }.thenBy { it.index })
    val parents = IntArray(ordered.size) { it }
    fun root(index: Int): Int {
        var result = index
        while (parents[result] != result) result = parents[result]
        var current = index
        while (parents[current] != current) {
            val next = parents[current]
            parents[current] = result
            current = next
        }
        return result
    }
    fun join(first: Int, second: Int) {
        val a = root(first)
        val b = root(second)
        parents[maxOf(a, b)] = minOf(a, b)
    }
    val firstOfMeal = mutableMapOf<String, Int>()
    var previousAt: Long? = null
    var lastStart: Int? = null
    ordered.forEachIndexed { index, row ->
        val first = row.meal?.let(firstOfMeal::get)
        if (first != null) {
            join(first, index)
        } else {
            if (row.meal != null) firstOfMeal[row.meal] = index
            val previous = lastStart
            val previousTime = previousAt
            if (previous != null && ((row.at != null && previousTime != null && row.at - previousTime <= 45 * 60_000) ||
                    (row.at == null && previousTime == null))) join(previous, index)
            lastStart = index
            previousAt = row.at ?: previousAt
        }
    }
    return ordered.indices.groupBy(::root).values.map { indices ->
        val first = ordered[indices.first()]
        FoodMeal(first.item.id, first.at?.let { Instant.fromEpochMilliseconds(it).toString() },
            indices.map { ordered[it].item }, indices.mapNotNull { ordered[it].meal }.distinct())
    }
}

fun foodByKind(items: List<FoodItem>): List<FoodKindSection> =
    (FoodMealKind.entries.toList() + listOf(null)).mapNotNull { kind ->
        foodMeals(items.filter { it.mealKind == kind }).takeIf { it.isNotEmpty() }?.let { FoodKindSection(kind, it) }
    }
