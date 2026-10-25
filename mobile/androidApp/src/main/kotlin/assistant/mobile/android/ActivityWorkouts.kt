package assistant.mobile.android

import androidx.compose.foundation.layout.heightIn
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
import assistant.mobile.core.personal.PersonalDisplayUnits
import assistant.mobile.core.personal.personalAmountText
import assistant.mobile.core.personal.personalDuration
import assistant.mobile.design.R as DesignR
import assistant.mobile.core.today.formatTodayAmount
import kotlinx.coroutines.launch
import kotlinx.serialization.json.jsonPrimitive

@Composable
internal fun activityWorkoutTitle(sport: String): String = when (sport) {
    "running" -> stringResource(DesignR.string.activity_running_workout)
    "workout" -> stringResource(DesignR.string.activity_running_other_workout)
    else -> stringResource(DesignR.string.activity_running_other_workout) + " · " + sport
}

@Composable
internal fun ActivityDeviceWorkout(report: DeviceWorkout, state: ActivityState, controller: ActivityController) {
    val zone = report.timezone ?: state.timezone
    val units = state.overview.data?.profile?.displayUnits ?: PersonalDisplayUnits.Metric
    val source = stringResource(when (report.originSource) {
        "phone_recorded" -> DesignR.string.ring_workout_phone_source
        "history_r18" -> DesignR.string.activity_running_detected_source
        else -> DesignR.string.activity_running_ring_source
    })
    Text(source + (report.sourceName?.let { " · $it" } ?: ""), modifier = Modifier.testTag("activity_workout_source"))
    Text(activityMoment(report.startAt, zone) + " — " + activityMoment(report.endAt, zone))
    if (zone != state.timezone) Text(zone, modifier = Modifier.testTag("activity_workout_timezone"))
    report.originDetails?.let { details ->
        val status = details["status"]?.jsonPrimitive?.content
        val trigger = details["trigger"]?.jsonPrimitive?.content
        if (status != null && trigger != null) Text(stringResource(if (status == "interrupted") DesignR.string.ring_workout_status_interrupted
            else DesignR.string.ring_workout_status_completed) + " · " + stringResource(when (trigger) {
            "automatic" -> DesignR.string.ring_workout_trigger_automatic
            "manual_takeover" -> DesignR.string.ring_workout_trigger_manual_takeover
            else -> DesignR.string.ring_workout_trigger_manual
        }))
    }
    Text((report.durationSeconds?.let { personalDuration(it / 60, state.locale) }
        ?: stringResource(DesignR.string.activity_running_unknown_duration)) +
        (report.steps?.let { " · " + it.toLong() + " " + stringResource(DesignR.string.activity_running_step_unit) } ?: "") +
        (report.distanceMeters?.let { " · " + personalAmountText("distance", it.toString(), units, state.locale) } ?: ""),
        modifier = Modifier.testTag("activity_workout_values"))
    report.heartRate?.let { ActivityWorkoutEffort(it, report.zoneSeconds, report.trimp, report.effort, state.locale) }
    report.activeDurationSeconds?.let { Text(stringResource(DesignR.string.workout_device_active_duration,
        personalDuration(it / 60, state.locale)), style = MaterialTheme.typography.bodySmall) }
    report.sportPlan?.let { plan ->
        var open by remember(report.id, plan) { mutableStateOf(false) }
        TextButton(onClick = { open = !open }, modifier = Modifier.heightIn(min = 48.dp).testTag("activity_workout_sport_plan")) {
            Text(stringResource(DesignR.string.workout_device_sport_plan))
        }
        if (open) {
            Text(plan.name + " · " + plan.family)
            Text(stringResource(DesignR.string.workout_device_requested, plan.requestedId.toString(),
                plan.candidateWireMode?.toString() ?: stringResource(DesignR.string.workout_device_no_candidate),
                plan.actualWireMode?.toString() ?: stringResource(DesignR.string.workout_device_unconfirmed_mode)))
            Text(plan.qualification + " · " + plan.evidenceRef)
        }
    }
    if (report.pauses.isNotEmpty()) {
        var open by remember(report.id) { mutableStateOf(false) }
        TextButton(onClick = { open = !open }, modifier = Modifier.heightIn(min = 48.dp).testTag("activity_workout_pauses")) {
            Text(stringResource(DesignR.string.workout_device_pauses, report.pauses.size.toString()))
        }
        if (open) report.pauses.forEach { Text(activityMoment(it.startAt, zone) + " — " + activityMoment(it.endAt, zone)) }
    }
    if (report.originSource == "phone_recorded" && report.workoutId != null && report.sourceId != null &&
        state.workoutId == report.workoutId && state.workoutSourceId == report.sourceId) ActivityWorkoutVisuals(state.copy(timezone = zone), controller, report.sourceName)
    ActivityEvidence(report.resolution, state)
}

@Composable
internal fun ActivityWorkoutEffort(heartRate: WorkoutHeartRate, zones: List<Double>, trimp: String?, effort: WorkoutEffortBasis?, locale: String) {
    Text(stringResource(DesignR.string.activity_running_heart_rate, formatTodayAmount(heartRate.mean.toString(), 0, locale), formatTodayAmount(heartRate.max.toString(), 0, locale)),
        modifier = Modifier.testTag("activity_workout_effort"))
    val shown = zones.mapIndexedNotNull { index, seconds -> if (seconds >= 30) index + 1 to seconds else null }.reversed()
    if (shown.isNotEmpty()) {
        Text(stringResource(DesignR.string.activity_running_zones), style = MaterialTheme.typography.labelLarge)
        shown.forEach { (zone, seconds) -> Text(stringResource(DesignR.string.activity_running_zone, zone.toString(),
            (50 + (zone - 1) * 10).toString(), (60 + (zone - 1) * 10).toString()) + " · " + personalDuration(seconds / 60, locale)) }
    }
    trimp?.let { Text(stringResource(DesignR.string.activity_running_load, formatTodayAmount(it, 0, locale)), modifier = Modifier.testTag("activity_workout_load")) }
    effort?.let { Text(stringResource(DesignR.string.activity_running_effort, formatTodayAmount(it.rest, 0, locale), formatTodayAmount(it.max, 0, locale),
        stringResource(if (it.basis == "tanaka") DesignR.string.activity_running_effort_basis_tanaka
            else DesignR.string.activity_running_effort_basis_default)), style = MaterialTheme.typography.bodySmall) }
}

@Composable
internal fun ActivityRunningData(state: ActivityState, controller: ActivityController) {
    val scope = rememberCoroutineScope()
    ActivitySection(stringResource(DesignR.string.activity_running_steps), "activity_running_steps") {
        ActivityQueryStatus(state.runningSteps) { scope.launch { controller.refresh() } }
        val row = state.runningSteps.data?.daily?.firstOrNull { it.date == state.selectedDay && it.metric.kind == "running_steps" }
        val context = state.runningSteps.data?.contexts?.firstOrNull { it.date == state.selectedDay && it.kind == "running_steps" }
        val value = activityMetricValue(row?.metric)?.toDoubleOrNull()?.takeIf { it >= 0 && it % 1 == 0.0 }
        if (value != null) {
            Text(value.toLong().toString() + " " + stringResource(DesignR.string.activity_running_step_unit),
                style = MaterialTheme.typography.headlineSmall, modifier = Modifier.testTag("activity_running_steps_value"))
            if (row?.metric?.complete == false || row?.metric?.resolution?.status == "partial" || context?.snapshot == true) Text(stringResource(DesignR.string.activity_running_snapshot),
                modifier = Modifier.testTag("activity_running_steps_snapshot"))
        } else Text(activityStatus(row?.metric?.status ?: "no_data"))
        (context?.observedAt ?: row?.metric?.resolution?.measuredAt)?.let { Text(stringResource(DesignR.string.activity_running_observed, activityMoment(it, state.timezone))) }
        Text(stringResource(DesignR.string.activity_running_no_addition), style = MaterialTheme.typography.bodySmall)
        ActivityEvidence(row?.metric?.resolution, state)
    }
    ActivitySection(stringResource(DesignR.string.activity_running_device_workouts), "activity_running_summary") {
        ActivityQueryStatus(state.runningWorkouts) { scope.launch { controller.refresh() } }
        val summaries = state.runningWorkouts.data?.contexts.orEmpty().filter { it.kind == "running_workouts" }.mapNotNull { it.workoutSummary }
        val count = summaries.mapNotNull { it.count }.filter { it % 1 == 0.0 }.sum().toLong()
        val known = summaries.mapNotNull { it.knownDurationSeconds }
        val unknown = summaries.mapNotNull { it.unknownDurationCount }.filter { it % 1 == 0.0 }.sum().toLong()
        if (count > 0) {
            Text(stringResource(DesignR.string.activity_running_count, count.toString()))
            if (known.isNotEmpty()) Text(stringResource(DesignR.string.activity_known_duration) + ": " + personalDuration(known.sum() / 60, state.locale))
            if (unknown > 0) Text(stringResource(DesignR.string.activity_missing_duration, unknown.toString()))
        } else Text(stringResource(DesignR.string.activity_running_workout_status_no_data))
        Text(stringResource(DesignR.string.activity_running_separate_summary), style = MaterialTheme.typography.bodySmall)
        state.runningWorkouts.data?.daily.orEmpty().filter { it.metric.status == "conflict" }.forEach {
            Text(activityDay(it.date) + " · " + activityStatus("conflict")); ActivityEvidence(it.metric.resolution, state)
        }
    }
}

@Composable
internal fun ActivityReportHistory(state: ActivityState, controller: ActivityController, onOpen: (DeviceWorkout) -> Unit) {
    val scope = rememberCoroutineScope()
    var source by remember(state.range) { mutableStateOf("all") }
    ActivitySection(stringResource(DesignR.string.activity_reports_title), "activity_report_history") {
        Text(stringResource(DesignR.string.activity_reports_description), style = MaterialTheme.typography.bodySmall)
        ActivityQueryStatus(state.reports) { scope.launch { controller.refresh() } }
        val reports = state.reports.data?.items.orEmpty()
        val sources = reports.distinctBy { it.sourceId }
        ActivityChoices(listOf("all") + sources.map { it.sourceId }, source, { id -> if (id == "all")
            stringResource(DesignR.string.activity_reports_all_sources) else sources.firstOrNull { it.sourceId == id }?.sourceName ?: id },
            "activity_report_source") { source = it }
        if (state.reports.stale || state.reports.error != null) Text(stringResource(DesignR.string.activity_reports_incomplete))
        else {
            val visible = reports.filter { source == "all" || it.sourceId == source }
            if (visible.isEmpty() && !state.reports.loading) Text(stringResource(DesignR.string.activity_reports_empty))
            visible.forEach { report ->
                var fields by remember(report.key) { mutableStateOf(false) }
                Text(activityDay(report.date) + " · v${report.sourceVersion}", modifier = Modifier.testTag("activity_report_${report.key}"))
                if (report.isCurrent == false) Text(stringResource(DesignR.string.activity_reports_previous_version))
                if (report.excluded == true) Text(stringResource(DesignR.string.activity_reports_disposition_excluded))
                report.resolutionDisposition?.let { disposition ->
                    Text(activityReportDisposition(disposition))
                    if (disposition !in setOf("current", "excluded", "conflict", "superseded")) {
                        var technical by remember(report.key, disposition) { mutableStateOf(false) }
                        TextButton(onClick = { technical = !technical }, modifier = Modifier.heightIn(min = 48.dp)) {
                            Text(stringResource(DesignR.string.activity_reports_technical_disposition))
                        }
                        if (technical) Text(disposition)
                    }
                }
                Text((report.sourceName ?: report.sourceId) + " · " + activityMoment(report.observedAt, state.timezone))
                val workout = report.workout
                if (workout != null) TextButton(onClick = { onOpen(workout); scope.launch {
                    val workoutId = workout.workoutId
                    if (workout.originSource == "phone_recorded" && workoutId != null && workout.sourceId != null)
                        controller.openWorkout(workoutId, workout.sourceId)
                } }, modifier = Modifier.heightIn(min = 48.dp).testTag("activity_report_workout_${report.key}")) {
                    Text(activityWorkoutTitle(workout.sportType))
                } else {
                    Text(when (report.kind) {
                        "running_steps" -> stringResource(DesignR.string.activity_reports_running_steps)
                        "active_energy" -> stringResource(DesignR.string.activity_reports_active_energy_original)
                        "distance" -> stringResource(DesignR.string.activity_reports_distance_original)
                        else -> report.kind
                    })
                    report.data["value"]?.jsonPrimitive?.content?.let { value ->
                        val unit = report.data["unit"]?.jsonPrimitive?.content
                        Text(formatTodayAmount(value, 0, state.locale) + when (unit) {
                            "kcal" -> " " + stringResource(DesignR.string.activity_reports_kcal)
                            "m" -> " " + stringResource(DesignR.string.activity_reports_meters)
                            null -> ""
                            else -> " $unit"
                        })
                    }
                    if (report.shape == "day_counter_snapshot") Text(stringResource(DesignR.string.activity_reports_counter_snapshot))
                }
                TextButton(onClick = { fields = !fields }, modifier = Modifier.heightIn(min = 48.dp).testTag("activity_report_fields_${report.key}")) {
                    Text(stringResource(DesignR.string.workout_device_saved_fields))
                }
                if (fields) { Text(stringResource(DesignR.string.workout_device_saved_fields_description)); Text(report.snapshot.toString()) }
            }
        }
    }
}

@Composable
private fun activityReportDisposition(value: String): String = stringResource(when (value) {
    "current" -> DesignR.string.activity_reports_disposition_current
    "excluded" -> DesignR.string.activity_reports_disposition_excluded
    "conflict" -> DesignR.string.activity_reports_disposition_conflict
    "superseded" -> DesignR.string.activity_reports_disposition_superseded
    else -> DesignR.string.activity_reports_unknown_disposition
})
