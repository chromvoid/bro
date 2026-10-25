package assistant.mobile.core.food

import assistant.mobile.core.NativeJson
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

/** Synthetic cases from web food api.test.ts, meals.test.ts and week.test.ts. */
class FoodModelsTest {
    @Test
    fun nullMissingAndIncompleteNutrientsRemainDistinctFromKnownZero() {
        val day = decodeFoodDay(foodFixture("day"))
        assertTrue(day.items.first().nutrients.containsKey("protein_g"))
        assertNull(day.items.first().nutrients["protein_g"])
        assertFalse(day.items.first().nutrients.containsKey("fiber_g"))
        assertNull(day.items.last().nutrients["energy_kcal"])
        assertTrue(day.totals.getValue("protein_g").unknown)
        assertFalse(day.totals.getValue("fat_g").unknown)
        assertEquals("0.00", day.totals.getValue("fat_g").known)
        assertNull(day.goal!!.macroTargets["carbohydrate_g"])
        assertNull(day.remainingKcal)
        assertFalse(day.remainingComplete)
        val withoutGoal = decodeFoodDay(changedDay("goal" to JsonNull))
        assertNull(withoutGoal.goal)
        assertNull(withoutGoal.remainingKcal)
    }

    @Test
    fun decimalStringsKeepServerPrecisionIncludingScientificNotation() {
        val day = decodeFoodDay(foodFixture("day").decodeToString()
            .replace("\"90.25\"", "\"9.025E+1\"")
            .replace("\"0.00\"", "\"0E-8\"").encodeToByteArray())
        assertEquals("9.025E+1", day.totals.getValue("energy_kcal").known)
        assertEquals("0E-8", day.items.first().nutrients["fat_g"])
        assertEquals("0E-8", day.totals.getValue("fat_g").known)
        assertTrue(FoodTotal("0E-8", false).unknown)
        assertEquals("150", plainFoodDecimal("1.50E+2"))
        assertEquals("0.00000001", plainFoodDecimal("1E-8"))
        assertTrue(sameFoodAmount("1.50E+2", "150.000"))
    }

    @Test
    fun dayBoundaryUsesPersonsTimezoneAndWeeksStartOnMonday() {
        val before = Instant.parse("2026-10-03T20:59:59.999Z").toEpochMilliseconds()
        assertEquals("2026-10-03", foodToday("Europe/Moscow", before))
        assertEquals("2026-10-04", foodToday("Europe/Moscow", before + 1))
        assertEquals("2026-10-03", foodToday("UTC", before + 1))
        assertEquals("2026-10-04", foodToday("Pacific/Kiritimati", Instant.parse("2026-10-03T10:00:00Z").toEpochMilliseconds()))
        assertEquals("2026-09-28", foodWeekStart("2026-10-03"))
        assertEquals("2026-09-28", foodWeekStart("2026-09-28"))
        assertEquals("2026-09-28", foodWeekStart("2026-10-04"))
        assertEquals("2027-01-01", shiftFoodDay("2026-12-31", 1))
        assertEquals("2028-02-29", shiftFoodDay("2028-03-01", -1))
    }

}

internal fun foodFixture(name: String): ByteArray = requireNotNull(FoodModelsTest::class.java.getResourceAsStream("/food/$name.json"))
    .use { it.readBytes() }

internal fun changedDay(vararg replacements: Pair<String, kotlinx.serialization.json.JsonElement>): ByteArray {
    val day = NativeJson.parseToJsonElement(foodFixture("day").decodeToString()).jsonObject
    return JsonObject(day + replacements.toMap()).toString().encodeToByteArray()
}
