package assistant.mobile.android

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import assistant.mobile.core.food.formatFoodAmount
import assistant.mobile.core.personal.PersonalController
import assistant.mobile.core.personal.PersonalNutritionStatus
import assistant.mobile.core.personal.PersonalState
import assistant.mobile.core.personal.personalNutritionStatus
import assistant.mobile.core.personal.personalNutritionSummary
import assistant.mobile.design.ui.Spacing
import assistant.mobile.design.R as DesignR
import kotlinx.coroutines.launch

/** The dashboard uses the diary's totals and completion; overview is the read-only fallback. */
@Composable
internal fun PersonalNutrition(state: PersonalState, controller: PersonalController) {
    val scope = rememberCoroutineScope()
    PersonalSection(DesignR.string.dashboard_nutrition_title, "personal_nutrition") {
        PersonalQueryStatus(state.nutritionDay) { scope.launch { controller.refresh() } }
        val day = state.nutritionDay.data
        if (day == null) {
            val metric = state.overview.data?.nutrition
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                Text(metric?.value?.let { formatFoodAmount(it, 0) + " " + stringResource(DesignR.string.food_kcal) }
                    ?: stringResource(DesignR.string.personal_no_data), style = MaterialTheme.typography.headlineSmall,
                    modifier = Modifier.testTag("personal_nutrition_value"))
                if (metric?.complete == false) Text(stringResource(DesignR.string.dashboard_nutrition_energy_unknown),
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            val row = personalNutritionSummary(day)
            fun shown(value: String?, places: Int): String = value?.let { formatFoodAmount(it, places) } ?: "—"
            val energy = if (row.logged && !row.energyComplete && row.energy != null)
                stringResource(DesignR.string.dashboard_nutrition_known, shown(row.energy, 0)) else shown(row.energy, 0)
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                Text(energy, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.testTag("personal_nutrition_value"))
                Text(row.target?.let { stringResource(DesignR.string.dashboard_nutrition_of, formatFoodAmount(it, 0)) }
                    ?: stringResource(DesignR.string.dashboard_nutrition_kcal),
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (row.target == null) Text(stringResource(DesignR.string.dashboard_nutrition_no_limit),
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                row.remaining?.let { remaining -> Text(stringResource(if (remaining.startsWith("-"))
                    DesignR.string.dashboard_nutrition_above else DesignR.string.dashboard_nutrition_remaining,
                    formatFoodAmount(remaining.removePrefix("-"), 0)), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
            val nutrients = listOf(
                Triple("protein_g", row.protein, DesignR.string.dashboard_nutrition_protein),
                Triple("fat_g", row.fat, DesignR.string.dashboard_nutrition_fat),
                Triple("carbohydrate_g", row.carbs, DesignR.string.dashboard_nutrition_carbs),
            )
            nutrients.forEach { (kind, value, label) ->
                Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                    Text(stringResource(label), style = MaterialTheme.typography.labelLarge)
                    val amount = if (row.logged && day.totals[kind]?.complete != true && value != null)
                        stringResource(DesignR.string.dashboard_nutrition_known, shown(value, 1)) else shown(value, 1)
                    Text(amount + " " + stringResource(DesignR.string.dashboard_nutrition_grams),
                        modifier = Modifier.testTag("personal_nutrition_$kind"))
                }
            }
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                Text(row.minimum?.let { stringResource(DesignR.string.dashboard_nutrition_minimum, formatFoodAmount(it, 1)) }
                    ?: stringResource(DesignR.string.dashboard_nutrition_no_minimum), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(stringResource(when (personalNutritionStatus(row)) {
                    PersonalNutritionStatus.Empty -> DesignR.string.dashboard_nutrition_empty
                    PersonalNutritionStatus.GoalsMissing -> DesignR.string.dashboard_nutrition_goals_missing
                    PersonalNutritionStatus.DayOpen -> DesignR.string.dashboard_nutrition_day_open
                    PersonalNutritionStatus.EnergyUnknown -> DesignR.string.dashboard_nutrition_energy_unknown
                    PersonalNutritionStatus.BothGoals -> DesignR.string.dashboard_nutrition_both_goals
                    PersonalNutritionStatus.OverLimit -> DesignR.string.dashboard_nutrition_over_limit
                    PersonalNutritionStatus.ProteinLow -> DesignR.string.dashboard_nutrition_protein_low
                    PersonalNutritionStatus.ProteinUnknown -> DesignR.string.dashboard_nutrition_protein_unknown
                }), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.testTag("personal_nutrition_status"))
                if (row.logged && !row.energyComplete) Text(stringResource(DesignR.string.dashboard_nutrition_energy_unknown),
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (row.logged && row.proteinComplete != true) Text(stringResource(DesignR.string.dashboard_nutrition_protein_partial),
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (row.drafts > 0) Text(stringResource(DesignR.string.dashboard_nutrition_drafts, row.drafts.toString()),
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
