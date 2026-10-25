package assistant.mobile.core.food

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

fun decodeFoodSearch(bytes: ByteArray): List<FoodCatalogItem> = foodObject(bytes).array("foods").map { element ->
    val row = element.jsonObject
    FoodCatalogItem(row.text("food_id"), row.text("snapshot_id"),
        row.optionalText("common_ru")?.takeIf { it.isNotBlank() } ?: row.optionalText("name_ru")?.takeIf { it.isNotBlank() } ?: row.text("name"),
        row.optionalText("brand"), row.decimal("basis_quantity"), row.text("basis_unit"),
        row.filterKeys { it in FOOD_NUTRIENTS }.mapValues { (_, value) -> decimalOrNull(value) },
        row.optionalArray("portions")?.map { portion(it.jsonObject, saved = false) } ?: emptyList(),
        row.optionalArray("saved_portions")?.map { portion(it.jsonObject, saved = true) } ?: emptyList(),
        row.optionalBoolean("personal") ?: false, row.optionalText("source_provider"))
}

private fun portion(row: JsonObject, saved: Boolean): FoodPortion = FoodPortion(row.text("id"), row.text("name"),
    row.decimal("quantity"), row.text("unit"), if (saved) row.revision("revision") else null)

fun decodeFoodMemory(bytes: ByteArray): List<FoodRemembered> = foodObject(bytes).array("foods").map { element ->
    val row = element.jsonObject
    FoodRemembered(row.text("food_id"), row.text("name"), row.optionalText("brand"), row.boolean("favorite"),
        row.revision("preference_revision", allowZero = true), row.optionalText("last_eaten"),
        row.nullableDecimal("last_quantity"), row.optionalText("last_unit"), row.optionalText("snapshot_id"),
        row.nullableDecimal("basis_quantity"), row.optionalText("basis_unit"), row.nullableDecimal("energy_kcal"))
}

fun decodeFoodCompositions(bytes: ByteArray): List<FoodComposition> = foodObject(bytes).array("compositions").map { element ->
    val row = element.jsonObject
    FoodComposition(row.text("id"), row.text("name"), row.array("components").map { component ->
        val part = component.jsonObject
        FoodCompositionComponent(part.text("label"), part.decimal("quantity"), part.text("unit"))
    })
}

/** Never reconstruct ingredients from generated prose: only the server's typed calculation is used. */
fun decodeFoodEstimate(bytes: ByteArray): FoodEstimate {
    val row = foodObject(bytes)
    require(!row.boolean("recorded")) { "Estimate must not record food" }
    return FoodEstimate(row.array("items").map { element ->
        val item = element.jsonObject
        FoodEstimateItem(item.filterKeys { it in FOOD_NUTRIENTS }.mapValues { (_, amount) -> decimalOrNull(amount) },
            item.optionalBoolean("estimated") ?: false)
    }, row.obj("totals").filterKeys { it in FOOD_NUTRIENTS }.mapValues { (_, element) ->
        val total = element.jsonObject
        FoodTotal(total.decimal("known"), total.boolean("complete"))
    }, recorded = false)
}
