package assistant.mobile.core.food

import kotlinx.datetime.LocalDate
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** A typed client proposal, sent only after an explicit user action through the journal. */
data class FoodOperation(val kind: String, val payload: JsonObject, val expectedRevisions: Map<String, Long>) {
    fun bytes(): ByteArray = buildJsonObject {
        put("kind", kind)
        put("payload", payload)
        put("expected_revisions", JsonObject(expectedRevisions.mapValues { JsonPrimitive(it.value) }))
    }.toString().encodeToByteArray()
}

fun editFoodOperation(draft: FoodEditDraft, revision: Long = draft.revision): FoodOperation {
    require(draft.kind in setOf("entry", "dish") && draft.id.isNotBlank() && revision > 0)
    require(LocalDate.parse(draft.date).toString() == draft.date)
    val amount = requireNotNull(parseFoodAmount(draft.quantity))
    draft.mealKind?.let(FoodMealKind::fromWire)
    val components = draft.components.filter { (id, quantity) -> !sameFoodAmount(quantity, draft.originalComponents[id]) }
    val resize = !sameFoodAmount(amount, draft.originalQuantity)
    require(!resize || components.isEmpty()) { "Resize a portion or correct components separately" }
    val payload = buildJsonObject {
        put("${draft.kind}_id", draft.id)
        if (resize) put("quantity", amount)
        if (draft.date != draft.originalDate) put("date", draft.date)
        if (draft.mealKind != draft.originalMealKind) {
            // The current server can move to a named kind, but null means preserve its kind.
            put("meal_kind", requireNotNull(draft.mealKind))
        }
        if (draft.kind == "dish") {
            if (draft.name != draft.originalName) put("name", draft.name.trim().also { require(it.isNotEmpty() && it.length <= 200) })
            if (components.isNotEmpty()) put("components", JsonArray(components.map { (id, quantity) ->
                buildJsonObject { put("entry_id", id); put("quantity", requireNotNull(parseFoodAmount(quantity))) }
            }))
        } else require(components.isEmpty())
    }
    require(payload.size > 1) { "Nothing changed" }
    return FoodOperation("${draft.kind}.update", payload, mapOf(draft.id to revision))
}

fun deleteFoodOperation(item: FoodItem): FoodOperation {
    require(item !is FoodEntry || item.dishId == null) { "A component belongs to its aggregate dish" }
    return FoodOperation("${item.kind}.delete", buildJsonObject { put("${item.kind}_id", item.id) }, mapOf(item.id to item.revision))
}

fun groupFoodOperation(entries: List<FoodEntry>, name: String, quantity: String, unit: String): FoodOperation {
    require(entries.size in 1..20 && entries.map { it.id }.distinct().size == entries.size)
    require(entries.all { it.dishId == null } && entries.map { it.date }.distinct().size == 1)
    require(name.trim().isNotEmpty() && name.trim().length <= 200 && unit in setOf("g", "ml", "serving"))
    return FoodOperation("dish.group", buildJsonObject {
        put("entry_ids", JsonArray(entries.map { JsonPrimitive(it.id) }))
        put("name", name.trim())
        put("quantity", requireNotNull(parseFoodAmount(quantity)))
        put("unit", unit)
    }, entries.associate { it.id to it.revision })
}

fun undoFoodOperation(receipt: FoodReceipt): FoodOperation {
    require(receipt.canUndo)
    return FoodOperation("operation.undo", buildJsonObject { put("operation_id", receipt.operationId) }, receipt.undoRevisions)
}
