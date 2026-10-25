package assistant.mobile.core.food

import kotlinx.datetime.LocalDate
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

fun foodPortionItem(draft: FoodPortionDraft): JsonObject = buildJsonObject {
    require(draft.food.snapshotId.isNotBlank())
    put("label", draft.food.name)
    put("snapshot_id", draft.food.snapshotId)
    val portion = draft.portion
    if (portion == null) {
        require(draft.unit == draft.food.basisUnit) { "Use a source portion to convert units" }
        put("quantity", requireNotNull(parseFoodAmount(draft.quantity)))
        put("unit", draft.unit)
    } else {
        require(portion in draft.food.portions + draft.food.savedPortions)
        put(if (portion.saved) "portion_id" else "reference_portion_id", portion.id)
        put("portion_count", requireNotNull(parseFoodAmount(draft.count)))
    }
}

fun addFoodOperation(draft: FoodPortionDraft, date: String, mealKind: FoodMealKind?): FoodOperation =
    FoodOperation("entry.add_many", buildJsonObject {
        foodDate(date); put("items", JsonArray(listOf(foodPortionItem(draft)))); foodMealKind(mealKind)
    }, emptyMap())

fun logFoodCompositionOperation(composition: FoodComposition, count: String, date: String, mealKind: FoodMealKind?): FoodOperation =
    FoodOperation("composition.log", buildJsonObject {
        require(composition.id.isNotBlank())
        put("composition_id", composition.id); put("count", requireNotNull(parseFoodAmount(count)))
        foodDate(date); foodMealKind(mealKind)
    }, emptyMap())

/** Repeating a consumed dish logs a new aggregate from frozen sources. It never updates the old dish. */
fun repeatFoodDishOperation(dish: FoodDish, date: String, mealKind: FoodMealKind?): FoodOperation =
    FoodOperation("dish.log", buildJsonObject {
        require(dish.components.size in 1..20)
        put("name", dish.name); put("quantity", requireNotNull(parseFoodAmount(dish.quantity))); put("unit", dish.unit)
        foodDate(date); foodMealKind(mealKind)
        put("items", JsonArray(dish.components.map { component -> buildJsonObject {
            put("label", component.name); put("snapshot_id", requireNotNull(component.snapshotId))
            put("quantity", requireNotNull(parseFoodAmount(component.quantity))); put("unit", component.unit)
            put("estimated", component.estimated)
            component.estimationEvidence?.let { put("estimation_evidence", it) }
        } }))
    }, emptyMap())

fun saveFoodCompositionOperation(dish: FoodDish, name: String): FoodOperation {
    require(dish.components.size in 1..20 && dish.components.map { it.id }.distinct().size == dish.components.size)
    return FoodOperation("composition.save", buildJsonObject {
        put("name", name.trim().also { require(it.isNotEmpty() && it.length <= 200) })
        put("entry_ids", JsonArray(dish.components.map { JsonPrimitive(it.id) }))
    }, dish.components.associate { it.id to it.revision })
}

fun favoriteFoodOperation(food: FoodRemembered): FoodOperation {
    require(food.preferenceRevision >= 0)
    return FoodOperation("food.favorite", buildJsonObject {
        put("food_id", food.foodId); put("favorite", !food.favorite)
    }, mapOf(food.foodId to food.preferenceRevision))
}

fun createCatalogFoodOperation(name: String, brand: String, basisQuantity: String, basisUnit: String,
    nutrients: Map<String, String?>): FoodOperation = FoodOperation("food.create", buildJsonObject {
    put("name", name.trim().also { require(it.isNotEmpty() && it.length <= 200) })
    if (brand.isNotBlank()) put("brand", brand.trim().also { require(it.length <= 200) })
    require(basisUnit in setOf("g", "ml"))
    put("basis_quantity", requireNotNull(parseFoodAmount(basisQuantity))); put("basis_unit", basisUnit)
    require(nutrients.keys.all { it in FOOD_NUTRIENTS })
    nutrients.forEach { (key, value) ->
        if (!value.isNullOrBlank()) {
            val decimal = plainFoodDecimal(value.trim().replace(',', '.'))
            require(decimal != null && !decimal.startsWith('-'))
            put(key, decimal)
        }
    }
}, emptyMap())

private fun kotlinx.serialization.json.JsonObjectBuilder.foodDate(date: String) {
    require(LocalDate.parse(date).toString() == date); put("date", date)
}
private fun kotlinx.serialization.json.JsonObjectBuilder.foodMealKind(kind: FoodMealKind?) {
    kind?.let { put("meal_kind", it.wire) }
}
