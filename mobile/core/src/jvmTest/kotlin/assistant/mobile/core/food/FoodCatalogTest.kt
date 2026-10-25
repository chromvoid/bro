package assistant.mobile.core.food

import assistant.mobile.core.NativeJson
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Product values from web/e2e/food.spec.ts; wire states from the web portion and memory panels. */
class FoodCatalogTest {
    @Test
    fun gramsAndMillilitersUseSourceUnitsAndKeepExactDecimalInput() {
        val food = decodeFoodSearch(foodFixture("catalog")).single()
        val item = foodPortionItem(FoodPortionDraft(food, " 150,25 "))
        assertEquals("150.25", item.text("quantity"))
        assertEquals("g", item.text("unit"))
        val milk = food.copy(basisUnit = "ml")
        assertEquals("ml", foodPortionItem(FoodPortionDraft(milk, "200")).text("unit"))
        assertFails { foodPortionItem(FoodPortionDraft(food, "150", unit = "ml")) }
        assertFails { foodPortionItem(FoodPortionDraft(food, "0")) }
    }

    @Test
    fun repeatDishUsesFrozenSourcesAndSaveCompositionGuardsEverySourceEntry() {
        val dish = decodeFoodDay(foodFixture("day")).items.filterIsInstance<FoodDish>().single().let {
            it.copy(components = it.components.map { entry -> entry.copy(snapshotId = "frozen-source") })
        }
        val repeated = repeatFoodDishOperation(dish, "2026-10-02", FoodMealKind.Lunch)
        assertEquals("dish.log", repeated.kind)
        assertTrue(repeated.expectedRevisions.isEmpty())
        assertFalse("dish_id" in repeated.payload)
        val item = repeated.payload.array("items").single().jsonObject
        assertEquals("frozen-source", item.text("snapshot_id"))
        assertEquals(dish.components.single().quantity, item.text("quantity"))
        val saved = saveFoodCompositionOperation(dish, "Мой завтрак")
        assertEquals("composition.save", saved.kind)
        assertEquals(dish.components.associate { it.id to it.revision }, saved.expectedRevisions)
        assertEquals(dish.components.map { it.id }, (saved.payload["entry_ids"] as JsonArray).map { it.jsonPrimitive.content })
        assertEquals("dish.log", NativeJson.parseToJsonElement(repeated.bytes().decodeToString()).jsonObject.text("kind"))
    }
}
