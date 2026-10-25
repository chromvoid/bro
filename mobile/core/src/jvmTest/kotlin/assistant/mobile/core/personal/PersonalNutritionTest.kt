package assistant.mobile.core.personal

import assistant.mobile.core.food.FoodTotal
import assistant.mobile.core.food.decodeFoodDay
import kotlin.test.Test
import kotlin.test.assertEquals

/** Mirrors dashboardNutrition.test.ts using the shared synthetic server diary contract. */
class PersonalNutritionTest {
    private fun diary() = requireNotNull(javaClass.getResourceAsStream("/food/day.json")).use {
        decodeFoodDay(it.readBytes())
    }

    @Test fun completedGoalsUseOriginalDecimalsAndPartialProteinCannotConfirmFailure() {
        val day = diary().copy(completed = true, totals = mapOf(
            "energy_kcal" to FoodTotal("2100.0000000000001", true),
            "protein_g" to FoodTotal("130", true),
        ))
        val over = personalNutritionSummary(day)
        assertEquals(PersonalNutritionStatus.OverLimit, personalNutritionStatus(over))
        val row = over.copy(energy = "2.1E+3", protein = "129.999999999999999", proteinComplete = true)
        assertEquals(PersonalNutritionStatus.ProteinLow, personalNutritionStatus(row))
        assertEquals(PersonalNutritionStatus.ProteinUnknown, personalNutritionStatus(row.copy(proteinComplete = false)))
        assertEquals(PersonalNutritionStatus.BothGoals, personalNutritionStatus(row.copy(protein = "130.0")))
    }

}
