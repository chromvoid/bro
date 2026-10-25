package assistant.mobile.core.food

import assistant.mobile.core.NativeJson
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FoodOperationsTest {
    @Test
    fun portionEditCarriesOriginalRevisionAndAChangedDecimalWithoutClientNutrientMath() {
        val draft = FoodEditDraft("entry", "e1", "Йогурт", "150.000", "g", "2026-10-03", 2)
            .copy(quantity = " 125,5 ")
        val operation = editFoodOperation(draft)
        assertEquals("entry.update", operation.kind)
        assertEquals(mapOf("e1" to 2L), operation.expectedRevisions)
        assertEquals(setOf("entry_id", "quantity"), operation.payload.keys)
        assertEquals("125.5", operation.payload.getValue("quantity").jsonPrimitive.content)
        assertEquals("e1", operation.payload.getValue("entry_id").jsonPrimitive.content)
        val bytes = NativeJson.parseToJsonElement(operation.bytes().decodeToString()).jsonObject
        assertEquals("2", bytes.getValue("expected_revisions").jsonObject.getValue("e1").jsonPrimitive.content)
    }

    @Test
    fun itemMoveAndDishCorrectionUseTypedAggregateOperations() {
        val original = FoodEditDraft("dish", "d1", "Завтрак", "350", "g", "2026-10-03", 8,
            mealKind = "breakfast", components = mapOf("e1" to "150", "e2" to "200"))
        val operation = editFoodOperation(original.copy(name = "Новый завтрак", date = "2026-10-02",
            mealKind = "lunch", components = mapOf("e1" to "120.50", "e2" to "200.000")))
        assertEquals("dish.update", operation.kind)
        assertEquals(mapOf("d1" to 8L), operation.expectedRevisions)
        assertEquals("2026-10-02", operation.payload.getValue("date").jsonPrimitive.content)
        assertEquals("lunch", operation.payload.getValue("meal_kind").jsonPrimitive.content)
        assertEquals("Новый завтрак", operation.payload.getValue("name").jsonPrimitive.content)
        val components = operation.payload.getValue("components") as JsonArray
        assertEquals(1, components.size)
        assertEquals("e1", components.single().jsonObject.getValue("entry_id").jsonPrimitive.content)
        assertEquals("120.50", components.single().jsonObject.getValue("quantity").jsonPrimitive.content)
        assertFalse("quantity" in operation.payload)
        assertFailsWith<IllegalArgumentException> {
            editFoodOperation(original.copy(quantity = "300", components = mapOf("e1" to "120", "e2" to "200")))
        }
    }

    @Test
    fun groupingGuardsEverySelectedEntryAndRejectsIngredientsAlreadyInADish() {
        val entries = listOf(
            FoodEntry("oats", 2, "100", "g", "2026-10-03", "Овсянка"),
            FoodEntry("banana", 4, "100", "g", "2026-10-03", "Банан"),
        )
        val operation = groupFoodOperation(entries, " Завтрак ", "200", "g")
        assertEquals("dish.group", operation.kind)
        assertEquals(mapOf("oats" to 2L, "banana" to 4L), operation.expectedRevisions)
        assertEquals(listOf("oats", "banana"), (operation.payload.getValue("entry_ids") as JsonArray).map { it.jsonPrimitive.content })
        assertEquals("Завтрак", operation.payload.getValue("name").jsonPrimitive.content)
        assertFailsWith<IllegalArgumentException> {
            groupFoodOperation(listOf(entries.first().copy(dishId = "existing")), "Завтрак", "100", "g")
        }
        assertFailsWith<IllegalArgumentException> {
            groupFoodOperation(listOf(entries.first(), entries.last().copy(date = "2026-10-02")), "Завтрак", "200", "g")
        }
        assertFailsWith<IllegalArgumentException> { deleteFoodOperation(entries.first().copy(dishId = "existing")) }
    }

    @Test
    fun undoIsANewGuardedServerOperationUsingReceiptResultRevision() {
        val receipt = decodeFoodReceipt("""{"operation_id":"op2","kind":"entry.update","entry_id":"e1","revision":3}""".encodeToByteArray())
        assertTrue(receipt.canUndo)
        val undo = undoFoodOperation(receipt)
        assertEquals("operation.undo", undo.kind)
        assertEquals(mapOf("e1" to 3L), undo.expectedRevisions)
        assertEquals(mapOf("operation_id" to "op2"), undo.payload.mapValues { it.value.jsonPrimitive.content })
        assertFalse(receipt.copy(undoStatus = "changed").canUndo)
        assertFalse(receipt.copy(undoStatus = "undone").canUndo)
        assertFalse(FoodReceipt("op3", "operation.undo", undoneOperationId = "op2").canUndo)
        assertFailsWith<IllegalArgumentException> { undoFoodOperation(receipt.copy(undoStatus = "changed")) }
    }

    @Test
    fun aggregateUndoUsesDishRevisionWithoutExceedingLedgerTargetBound() {
        val receipt = FoodReceipt("dish-op", "dish.update", dishId = "dish", revision = 5,
            appliedEntries = (1..20).map { FoodAppliedEntry("ingredient-$it", 4) },
            appliedDishes = mapOf("neighbor" to 2))
        assertEquals(mapOf("dish" to 5L, "neighbor" to 2L), receipt.undoRevisions)
        assertEquals(receipt.undoRevisions, undoFoodOperation(receipt).expectedRevisions)
    }

    @Test
    fun receiptCannotTreatUnsupportedDomainStatusAsApplied() {
        val applied = """{"operation_id":"op2","kind":"entry.update","entry_id":"e1","revision":3,"status":"applied"}"""
        assertEquals("op2", decodeFoodReceipt(applied.encodeToByteArray()).operationId)
        for (status in listOf("rejected", "queued", "future-status")) {
            assertFailsWith<IllegalArgumentException> {
                decodeFoodReceipt(applied.replace("\"applied\"", "\"$status\"").encodeToByteArray())
            }
        }
    }
}
