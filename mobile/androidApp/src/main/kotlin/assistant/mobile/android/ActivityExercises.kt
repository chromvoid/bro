package assistant.mobile.android

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import assistant.mobile.core.activity.*
import assistant.mobile.design.R as DesignR
import kotlinx.coroutines.launch

@Composable
internal fun ActivityCatalog(state: ActivityState, controller: ActivityController, onProgress: () -> Unit) {
    val scope = rememberCoroutineScope()
    val editable = state.canWrite && !state.busy && state.pending.isEmpty()
    var deleting by remember { mutableStateOf<String?>(null) }
    ActivitySection(stringResource(DesignR.string.activity_exercise_catalog), "activity_catalog_entries") {
        ActivityQueryStatus(state.catalog) { scope.launch { controller.refresh() } }
        if (state.canWrite) ActivityAction(DesignR.string.activity_new_exercise, "activity_catalog_create", editable) { controller.beginExercise() }
        state.exerciseDraft?.let { draft ->
            Text(stringResource(if (draft.exerciseId == null) DesignR.string.activity_new_exercise else DesignR.string.activity_edit_exercise),
                style = MaterialTheme.typography.titleMedium)
            ActivityField(draft.name, { controller.updateExerciseDraft(draft.copy(name = it.take(200))) }, DesignR.string.activity_exercise_name,
                "activity_catalog_name", editable)
            ActivityField(draft.equipment, { controller.updateExerciseDraft(draft.copy(equipment = it.take(200))) }, DesignR.string.activity_equipment,
                "activity_catalog_equipment", editable)
            ActivityField(draft.variant, { controller.updateExerciseDraft(draft.copy(variant = it.take(200))) }, DesignR.string.activity_variant,
                "activity_catalog_variant", editable)
            Text(stringResource(DesignR.string.activity_load_basis))
            ActivityChoices(activityLoadBases, draft.loadBasis, { activityLoadBasis(it) }, "activity_catalog_basis", editable) {
                controller.updateExerciseDraft(draft.copy(loadBasis = it))
            }
            ActivityAction(DesignR.string.common_save, "activity_catalog_save", editable && draft.name.isNotBlank()) { scope.launch { controller.saveExercise() } }
            TextButton(onClick = { scope.launch { controller.dismissDraft("exercise") } }, enabled = !state.busy,
                modifier = Modifier.heightIn(min = 48.dp).testTag("activity_catalog_cancel")) { Text(stringResource(DesignR.string.common_cancel)) }
        }
        if (state.catalog.data.isNullOrEmpty() && !state.catalog.loading) Text(stringResource(DesignR.string.activity_no_exercises))
        state.catalog.data.orEmpty().forEach { exercise ->
            HorizontalDivider()
            Text(exercise.name, style = MaterialTheme.typography.titleMedium, modifier = Modifier.testTag("activity_exercise_${exercise.id}"))
            Text(listOfNotNull(exercise.equipment, exercise.variant).filter(String::isNotBlank).joinToString(" · "))
            Text(activityLoadBasis(exercise.loadBasis), style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = { scope.launch { controller.openProgress(exercise.id) }; onProgress() },
                modifier = Modifier.heightIn(min = 48.dp).testTag("activity_progress_${exercise.id}")) { Text(stringResource(DesignR.string.activity_exercise_history)) }
            if (state.canWrite) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = { controller.beginExercise(exercise.id) }, enabled = editable,
                    modifier = Modifier.heightIn(min = 48.dp).testTag("activity_catalog_edit_${exercise.id}")) { Text(stringResource(DesignR.string.common_edit)) }
                TextButton(onClick = { deleting = exercise.id }, enabled = editable,
                    modifier = Modifier.heightIn(min = 48.dp).testTag("activity_catalog_delete_${exercise.id}")) { Text(stringResource(DesignR.string.common_delete)) }
            }
            if (deleting == exercise.id) {
                Text(stringResource(DesignR.string.activity_confirm_delete_exercise))
                ActivityAction(DesignR.string.common_delete, "activity_catalog_delete_apply", editable) {
                    scope.launch { controller.deleteCatalogExercise(exercise.id); deleting = null }
                }
                TextButton(onClick = { deleting = null }, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(DesignR.string.common_cancel)) }
            }
        }
    }
}

@Composable
internal fun ActivityExerciseProgress(state: ActivityState, controller: ActivityController) {
    val scope = rememberCoroutineScope()
    ActivitySection(stringResource(DesignR.string.activity_redesign_strength_history), "activity_progress") {
        ActivityQueryStatus(state.catalog) { scope.launch { controller.refresh() } }
        val catalog = state.catalog.data.orEmpty()
        val selected = state.progressExerciseId
        val archived = state.progress.data?.exercise
        val choices = if (archived != null && catalog.none { it.id == archived.id }) catalog + archived else catalog
        choices.forEach { exercise -> TextButton(onClick = { scope.launch { controller.openProgress(exercise.id) } },
            modifier = Modifier.heightIn(min = 48.dp).testTag("activity_progress_${exercise.id}")) {
            Text(listOfNotNull(exercise.name, exercise.variant).filter(String::isNotBlank).joinToString(" · "))
        } }
        if (selected == null) {
            Text(stringResource(if (choices.isEmpty()) DesignR.string.activity_detail_no_exercises else DesignR.string.activity_detail_choose_exercise_hint))
            if (choices.isEmpty()) Text(stringResource(DesignR.string.activity_detail_no_exercises_hint))
        }
        ActivityQueryStatus(state.progress) { scope.launch { controller.refresh() } }
        state.progress.data?.let { progress ->
            if (progress.series.isEmpty()) {
                Text(stringResource(DesignR.string.activity_no_progress))
                Text(stringResource(DesignR.string.activity_no_progress_hint))
            }
            progress.series.forEachIndexed { index, series ->
                var charts by remember(progress.exercise.id, series.snapshot, series.loadUnit, series.implementCount) { mutableStateOf(false) }
                HorizontalDivider()
                Text(series.snapshot.name, style = MaterialTheme.typography.titleMedium)
                Text(listOfNotNull(series.snapshot.equipment, series.snapshot.variant, activityLoadBasis(series.snapshot.loadBasis))
                    .filter(String::isNotBlank).joinToString(" · "))
                if (series.snapshot.loadBasis == "per_implement") Text(stringResource(DesignR.string.activity_implements, series.implementCount.toString()))
                if (series.snapshot.loadBasis == "assistance") Text(stringResource(DesignR.string.activity_assistance_hint), style = MaterialTheme.typography.bodySmall)
                Text(stringResource(DesignR.string.activity_detail_comparison_basis), style = MaterialTheme.typography.bodySmall)
                val (latest, previous) = strengthDays(series)
                if (latest != null) ActivityProgressDay(latest, series.snapshot.loadBasis, DesignR.string.activity_detail_latest_sets)
                if (previous != null) ActivityProgressDay(previous, series.snapshot.loadBasis, DesignR.string.activity_detail_previous_day)
                else Text(stringResource(DesignR.string.activity_detail_no_previous_day), style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = { charts = !charts }, modifier = Modifier.heightIn(min = 48.dp)
                    .testTag("activity_progress_charts_$index")) { Text(stringResource(DesignR.string.activity_detail_charts_and_sets)) }
                if (charts) Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(stringResource(if (series.snapshot.loadBasis == "assistance") DesignR.string.activity_detail_assistance_maxima
                        else DesignR.string.activity_detail_separate_maxima), style = MaterialTheme.typography.bodySmall)
                    if (series.snapshot.loadBasis != "bodyweight") {
                        Text(stringResource(if (series.snapshot.loadBasis == "assistance") DesignR.string.activity_lowest_assistance else DesignR.string.activity_heaviest_load))
                        val points = progressPoints(series, "load_kg")
                        if (points.isEmpty()) Text(stringResource(DesignR.string.activity_no_load_data)) else ActivityLineChart(points,
                            stringResource(DesignR.string.activity_load_graph), ::activityChartDate, { it.toString() },
                            Modifier.testTag("activity_progress_chart"))
                    }
                    Text(stringResource(DesignR.string.activity_most_repetitions))
                    val repetitions = progressPoints(series, "repetitions")
                    if (repetitions.isEmpty()) Text(stringResource(DesignR.string.activity_no_repetition_data)) else ActivityLineChart(repetitions,
                        stringResource(DesignR.string.activity_repetitions_graph), ::activityChartDate, { it.toInt().toString() },
                        Modifier.testTag("activity_progress_repetitions_chart"))
                    Text(stringResource(DesignR.string.activity_set_history), style = MaterialTheme.typography.titleSmall)
                    series.points.forEach { point -> Text(activityDay(point.date) + " · " + activityProgressSet(point, series.snapshot.loadBasis),
                        modifier = Modifier.testTag("activity_progress_set_${point.setId}")) }
                }
            }
            if (progress.daily.isNotEmpty()) {
                Text(stringResource(DesignR.string.activity_working_sets), style = MaterialTheme.typography.titleSmall)
                ActivityLineChart(progress.daily.map { ActivityChartPoint(activityDayMillis(it.date), it.workingSets.toDouble()) },
                    stringResource(DesignR.string.activity_detail_working_sets_details), ::activityChartDate, { it.toInt().toString() },
                    Modifier.testTag("activity_progress_working_sets_chart"))
            }
        }
    }
}

@Composable
private fun ActivityProgressDay(day: StrengthDay, basis: String, heading: Int) {
    Text(stringResource(heading), style = MaterialTheme.typography.titleSmall)
    Text(activityDay(day.date))
    day.points.take(3).forEach { Text(activityProgressSet(it, basis)) }
}

@Composable
private fun activityProgressSet(point: ExerciseProgressPoint, basis: String): String {
    val reps = point.repetitions?.toString() ?: stringResource(DesignR.string.activity_unknown)
    if (basis == "bodyweight" && point.loadKg == null) return stringResource(DesignR.string.activity_detail_bodyweight_set, reps)
    val load = (point.loadKg ?: stringResource(DesignR.string.activity_unknown)) + " " + stringResource(DesignR.string.activity_load_units_kg)
    return stringResource(if (basis == "assistance") DesignR.string.activity_detail_assistance_set else DesignR.string.activity_detail_set_pair, load, reps)
}
