package assistant.mobile.android

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import assistant.mobile.design.ui.ProgressBar
import assistant.mobile.core.SessionLease
import assistant.mobile.core.activity.*
import assistant.mobile.core.personal.*
import assistant.mobile.design.R as DesignR
import assistant.mobile.platform.ring.RingController
import java.time.LocalDate
import java.time.ZoneOffset
import kotlinx.coroutines.launch

@Composable
internal fun ActivityScreen(controller: ActivityController, lease: SessionLease, onOpenSession: (String) -> Unit,
    onNewSession: () -> Unit, initialDay: String? = null, recoveryKey: String? = null, modifier: Modifier = Modifier,
    ringController: RingController? = null) {
    val observed by controller.state.collectAsStateWithLifecycle()
    val state = observed?.takeIf { it.namespace == lease.namespace }
    val scope = rememberCoroutineScope()
    ActivityActivation(controller, lease, initialDay)
    val tab = state?.tab ?: "overview"
    var subject by remember(lease.namespace) { mutableStateOf("steps") }
    var device by remember(lease.namespace) { mutableStateOf<DeviceWorkout?>(null) }
    var dataOpen by remember(lease.namespace) { mutableStateOf(false) }
    fun openTab(next: String) { scope.launch { controller.selectTab(next) } }
    LaunchedEffect(state?.namespace, recoveryKey) { if (state != null && recoveryKey != null) controller.check(recoveryKey) }
    RefreshableScreen("screen_Activity", title = stringResource(DesignR.string.activity_title),
        isRefreshing = state?.let { it.overview.loading && it.overview.data != null } == true,
        onRefresh = { scope.launch { controller.refresh() } }, modifier = modifier,
        refreshTag = "activity_pull_refresh") {
        if (state == null) Text(stringResource(DesignR.string.state_loading))
        state?.let { current ->
            ActivityWriteStatus(current, controller)
            ringController?.let { ring -> LiveRingWorkoutCard(ring, lease, current.timezone,
                canRead = current.canPersonal, canWrite = current.canSteps) }
            if (current.canWrite) ActivityAction(DesignR.string.activity_add_session, "activity_session_create",
                !current.busy && current.pending.isEmpty()) {
                val previousDraft = controller.state.value?.sessionDraft
                controller.closeSession()
                controller.beginSession()
                if (previousDraft == null) controller.state.value?.sessionDraft?.let { controller.updateSessionDraft(it.copy(date =
                    if (tab == "overview") current.selectedDay else current.anchor)) }
                onNewSession()
            }
            ActivityChoices(listOf("overview", "journal", "progress"), tab, { stringResource(when (it) {
                "overview" -> DesignR.string.activity_tabs_overview
                "journal" -> DesignR.string.activity_tabs_journal
                else -> DesignR.string.activity_redesign_dynamics
            }) }, "activity_tab") { openTab(it) }
            if (!current.canRead) UnavailableCard()
            if (tab == "overview") {
                ActivityDayNav(current.selectedDay, current.today) { day -> scope.launch { controller.selectDay(day) } }
                if (current.canPersonal) ActivityMovementDay(current, controller) { subject = "steps"; openTab("progress") }
                else Text(stringResource(DesignR.string.activity_no_personal_access))
            } else ActivityRangeNavigation(current, controller)
            if (tab == "overview" || tab == "journal") {
                if (tab == "journal") ActivityChoices(listOf("all") + activityKinds, current.range.kind ?: "all", { activityKind(it) },
                    "activity_kind") { kind -> scope.launch { controller.selectRange(current.period, current.anchor, kind.takeIf { it != "all" }) } }
                ActivityQueryStatus(current.sessions) { scope.launch { controller.refresh() } }
                ActivityQueryStatus(current.runningWorkouts) { scope.launch { controller.refresh() } }
                ActivityQueryStatus(current.otherWorkouts) { scope.launch { controller.refresh() } }
                ActivitySection(stringResource(if (tab == "overview") DesignR.string.activity_redesign_recent_entries
                    else DesignR.string.activity_tabs_journal), "activity_journal") {
                    val sessions = current.sessions.data?.sessions.orEmpty()
                    val workouts = (current.runningWorkouts.data?.workouts.orEmpty() + current.otherWorkouts.data?.workouts.orEmpty()).distinctBy { it.id }
                    val entries = journalGroups(sessions, workouts, if (tab == "overview") null else current.range.kind)
                        .flatMap { (_, items) -> items }
                    if (entries.isEmpty() && !current.sessions.loading && !current.runningWorkouts.loading && !current.otherWorkouts.loading)
                        Text(stringResource(DesignR.string.activity_redesign_no_entries))
                    (if (tab == "overview") entries.take(3) else entries).forEach { item ->
                        Text(activityDay(item.date), style = MaterialTheme.typography.labelLarge)
                        when (item) {
                            is ActivityJournalEntry.Manual -> {
                                val entry = item.session
                                TextButton(onClick = { onOpenSession(entry.id) }, modifier = Modifier.fillMaxWidth()
                                    .heightIn(min = 48.dp).testTag("activity_session_${entry.id}")) {
                                    Text((entry.title?.takeIf(String::isNotBlank) ?: activityKind(entry.kind)) + " · " +
                                        stringResource(DesignR.string.activity_redesign_manual))
                                }
                            }
                            is ActivityJournalEntry.Device -> {
                                val entry = item.workout
                                TextButton(onClick = { device = entry; scope.launch {
                                    val workoutId = entry.workoutId
                                    if (entry.originSource == "phone_recorded" && workoutId != null && entry.sourceId != null)
                                        controller.openWorkout(workoutId, entry.sourceId)
                                } }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("activity_device_workout_${entry.id}")) {
                                    Text(activityWorkoutTitle(entry.sportType) + " · " + entry.sourceName.orEmpty())
                                }
                            }
                        }
                    }
                    if (current.sessions.data?.truncated == true) Text(stringResource(DesignR.string.activity_truncated))
                    if (tab == "overview") TextButton(onClick = { openTab("journal") }, modifier = Modifier.heightIn(min = 48.dp)
                        .testTag("activity_all_sessions")) { Text(stringResource(DesignR.string.activity_all_sessions)) }
                }
            }
            if (tab == "progress") {
                ActivityChoices(listOf("steps", "active_minutes", "sessions", "strength"), subject,
                    { activitySubject(it) }, "activity_subject") { subject = it }
                if (subject == "strength") ActivityExerciseProgress(current, controller) else ActivityDynamics(current, controller, subject)
            }
            TextButton(onClick = { dataOpen = true }, enabled = current.canPersonal,
                modifier = Modifier.heightIn(min = 48.dp).testTag("activity_movement_data")) {
                Text(stringResource(DesignR.string.activity_redesign_movement_data))
            }
            TextButton(onClick = { openTab("exercises") }, modifier = Modifier.heightIn(min = 48.dp).testTag("activity_catalog")) {
                Text(stringResource(DesignR.string.activity_exercise_catalog))
            }
            if (tab == "exercises") ActivityCatalog(current, controller) { subject = "strength"; openTab("progress") }
        }
    }
    state?.let { current ->
        if (current.canPersonal) device?.let { selected ->
            val latest = (current.runningWorkouts.data?.workouts.orEmpty() + current.otherWorkouts.data?.workouts.orEmpty()).firstOrNull { it.id == selected.id }
                ?: current.reports.data?.items?.firstOrNull { it.id == selected.id }?.workout
                ?: selected.takeIf { current.runningWorkouts.data == null && current.otherWorkouts.data == null && current.reports.data == null }
            if (latest != null) PersonalSheet(activityWorkoutTitle(latest.sportType), "activity_workout_detail", {
                device = null; controller.closeWorkout()
            }) { ActivityDeviceWorkout(latest, current, controller) }
        }
        current.stepsDraft?.let { ActivityStepsEditor(current, controller) }
        if (dataOpen && current.canPersonal) PersonalSheet(stringResource(DesignR.string.activity_redesign_movement_data), "activity_data_detail", { dataOpen = false }) {
            ActivityRunningData(current, controller)
            ActivityReportHistory(current, controller) { report -> dataOpen = false; device = report }
        }
    }
}

@Composable
internal fun <T> ActivityQueryStatus(query: ActivityQuery<T>, retry: () -> Unit) {
    if (query.loading && query.data == null) Text(stringResource(DesignR.string.state_loading))
    if (query.loading && query.data != null) ProgressBar(modifier = Modifier.fillMaxWidth())
    query.error?.let {
        ErrorMessage(it, "activity_query_error")
        TextButton(onClick = retry, modifier = Modifier.heightIn(min = 48.dp).testTag("activity_retry")) {
            Text(stringResource(DesignR.string.common_retry))
        }
    }
}

@Composable
internal fun ActivityWriteStatus(state: ActivityState, controller: ActivityController) {
    val scope = rememberCoroutineScope()
    if (state.conflict) {
        Text(stringResource(DesignR.string.personal_conflict), color = MaterialTheme.colorScheme.error,
            modifier = Modifier.testTag("activity_conflict").semantics { liveRegion = LiveRegionMode.Assertive })
        Text(stringResource(DesignR.string.personal_forms_draft_kept), modifier = Modifier.testTag("activity_draft_kept"))
    }
    state.error?.let { ErrorMessage(it, "activity_error") }
    if (state.pending.isNotEmpty()) ActivitySection(stringResource(DesignR.string.outcome_unknown_title), "activity_pending") {
        Text(stringResource(DesignR.string.outcome_unknown_text))
        state.pending.forEach { pending -> TextButton(onClick = { scope.launch { controller.check(pending.key) } },
            enabled = !state.busy, modifier = Modifier.heightIn(min = 48.dp).testTag("activity_pending_check_${pending.key}")) {
            Text(stringResource(DesignR.string.outcome_check))
        } }
    }
    state.receipt?.let { Text(stringResource(DesignR.string.activity_saved), modifier = Modifier.testTag("activity_receipt")
        .semantics { liveRegion = LiveRegionMode.Polite }) }
}

@Composable
private fun ActivityRangeNavigation(state: ActivityState, controller: ActivityController) {
    val scope = rememberCoroutineScope()
    var anchorDraft by remember(state.anchor) { mutableStateOf(state.anchor) }
    Text(activityDay(state.range.from) + " — " + activityDay(state.anchor), modifier = Modifier.testTag("activity_range"))
    ActivityChoices(listOf("7", "30", "90"), state.period.toString(), { stringResource(DesignR.string.activity_period, it) },
        "activity_period") { scope.launch { controller.selectRange(it.toInt(), state.anchor, state.range.kind) } }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        TextButton(onClick = { scope.launch { controller.selectRange(state.period, activityShiftDay(state.anchor, -state.period), state.range.kind) } },
            modifier = Modifier.heightIn(min = 48.dp).testTag("activity_range_previous")) { Text(stringResource(DesignR.string.activity_previous_period)) }
        TextButton(onClick = { scope.launch { controller.selectRange(state.period, minOf(state.today, activityShiftDay(state.anchor, state.period)), state.range.kind) } },
            enabled = state.anchor < state.today, modifier = Modifier.heightIn(min = 48.dp).testTag("activity_range_next")) {
            Text(stringResource(DesignR.string.activity_next_period))
        }
    }
    ActivityField(anchorDraft, { anchorDraft = it }, DesignR.string.activity_until, "activity_range_until", true)
    TextButton(onClick = { scope.launch { controller.selectRange(state.period, anchorDraft, state.range.kind) } },
        enabled = runCatching { LocalDate.parse(anchorDraft).toString() == anchorDraft && anchorDraft <= state.today }.getOrDefault(false),
        modifier = Modifier.heightIn(min = 48.dp).testTag("activity_range_apply")) { Text(stringResource(DesignR.string.activity_change_period)) }
}

@Composable
private fun ActivityMovementDay(state: ActivityState, controller: ActivityController, onDynamics: () -> Unit) {
    val scope = rememberCoroutineScope()
    var detail by remember(state.selectedDay) { mutableStateOf<String?>(null) }
    ActivityQueryStatus(state.overview) { scope.launch { controller.refresh() } }
    ActivitySection(stringResource(DesignR.string.activity_day_title), "activity_movement_day") {
        val overview = state.overview.data
        listOf("steps", "active_minutes", "active_energy", "distance").forEach { kind ->
            val metric = overview?.metrics?.get(kind)
            if (kind in listOf("steps", "active_minutes") || metric?.status?.let { it != "no_data" } == true) {
                TextButton(onClick = { detail = kind }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                    .testTag("activity_movement_$kind")) {
                    Text(personalMetricLabel(kind) + " · " + activityMetricAmount(metric, kind, overview?.profile?.displayUnits ?: PersonalDisplayUnits.Metric, state.locale))
                }
                metric?.target?.toDoubleOrNull()?.takeIf { it > 0 }?.let { Text(stringResource(DesignR.string.activity_day_goal,
                    personalAmountText(kind, metric.target, overview?.profile?.displayUnits ?: PersonalDisplayUnits.Metric, state.locale).orEmpty())) }
                Text(activityCoverage(metric), style = MaterialTheme.typography.bodySmall)
            }
        }
        Text(stringResource(DesignR.string.activity_day_active_hint), style = MaterialTheme.typography.bodySmall)
        if (state.canSteps) TextButton(onClick = { controller.beginSteps() }, enabled = !state.busy && state.pending.isEmpty(),
            modifier = Modifier.heightIn(min = 48.dp).testTag("activity_correct_steps")) {
            Text(stringResource(DesignR.string.activity_day_correct_steps))
        }
        ActivityQueryStatus(state.weekSteps) { scope.launch { controller.refresh() } }
        val week = state.weekSteps.data?.daily.orEmpty().filter { it.metric.kind == "steps" }.sortedBy { it.date }
        if (week.isNotEmpty()) {
            Text(stringResource(DesignR.string.activity_day_week_title))
            state.weekSteps.data?.let { Text(activityDay(it.from) + " — " + activityDay(it.to), style = MaterialTheme.typography.bodySmall) }
            ActivityLineChart(week.mapNotNull { row -> activityMetricNumber(row.metric)?.let {
                ActivityChartPoint(activityDayMillis(row.date), it)
            } }, stringResource(DesignR.string.activity_day_week_title), ::activityChartDate, { it.toInt().toString() },
                Modifier.testTag("activity_steps_week_chart"))
            week.forEach { row -> TextButton(onClick = { scope.launch { controller.selectDay(row.date) } }, modifier = Modifier.heightIn(min = 48.dp)
                .testTag("activity_week_${row.date}")) { Text(activityDay(row.date) + " · " + activityMetricAmount(row.metric, "steps",
                    overview?.profile?.displayUnits ?: PersonalDisplayUnits.Metric, state.locale)) } }
        }
        TextButton(onClick = onDynamics, modifier = Modifier.heightIn(min = 48.dp).testTag("activity_open_dynamics")) {
            Text(stringResource(DesignR.string.activity_redesign_open_dynamics))
        }
    }
    detail?.let { kind -> PersonalSheet(personalMetricLabel(kind), "activity_metric_detail", { detail = null }) {
        state.overview.data?.metrics?.get(kind)?.let { metric ->
            Text(activityMetricAmount(metric, kind, state.overview.data?.profile?.displayUnits ?: PersonalDisplayUnits.Metric, state.locale))
            ActivityEvidence(metric.resolution, state)
            metric.records.forEach { record -> Text(activityMoment(record.startAt, state.timezone) + " · " +
                (record.sourceName ?: record.sourceId ?: stringResource(DesignR.string.resolution_manual))) }
        }
    } }
}

@Composable
private fun activityMetricAmount(metric: PersonalMetric?, kind: String, units: PersonalDisplayUnits, locale: String): String {
    val value = activityMetricNumber(metric)
    return if (value == null) activityStatus(metric?.status ?: "no_data") else personalAmountText(kind, metric?.value, units, locale)
        ?: value.toString()
}

private fun activityMetricNumber(metric: PersonalMetric?): Double? = metric?.takeIf {
    it.status == "available" && it.resolution?.status != "conflict"
}?.value?.toDoubleOrNull()?.takeIf { it.isFinite() && it >= 0 }

@Composable
private fun activityCoverage(metric: PersonalMetric?): String = stringResource(when {
    metric?.complete == false || metric?.resolution?.coverage?.complete == false || metric?.resolution?.status == "partial" -> DesignR.string.activity_day_partial
    metric?.complete == true || metric?.resolution?.coverage?.complete == true -> DesignR.string.activity_day_complete
    else -> DesignR.string.activity_day_coverage_unknown
})

@Composable
internal fun ActivityEvidence(resolution: PersonalResolution?, state: ActivityState) {
    if (resolution == null) return
    var open by remember(resolution.fingerprint) { mutableStateOf(false) }
    TextButton(onClick = { open = !open }, modifier = Modifier.heightIn(min = 48.dp).testTag("activity_resolution_toggle")) {
        Text(stringResource(DesignR.string.resolution_why))
    }
    if (open) {
        Text(activityStatus(resolution.status))
        if (resolution.protocolVerified == false && resolution.selected.isNotEmpty()) Text(stringResource(DesignR.string.resolution_protocol_unverified))
        resolution.selected.forEach { fact -> Text((fact.sourceName ?: fact.sourceId ?: stringResource(DesignR.string.resolution_manual)) +
            (fact.startAt?.let { " · " + activityMoment(it, state.timezone) } ?: "")) }
        PersonalQuality(resolution.protocolFlags + resolution.qualityFlags)
    }
}

@Composable
private fun activitySubject(subject: String): String = stringResource(when (subject) {
    "active_minutes" -> DesignR.string.activity_redesign_subjects_active_minutes
    "sessions" -> DesignR.string.activity_redesign_subjects_sessions
    "strength" -> DesignR.string.activity_redesign_subjects_strength
    else -> DesignR.string.activity_redesign_subjects_steps
})

@Composable
private fun ActivityDynamics(state: ActivityState, controller: ActivityController, subject: String) {
    val scope = rememberCoroutineScope()
    var measure by remember { mutableStateOf("sessions") }
    ActivitySection(activitySubject(subject), "activity_dynamics") {
        if (subject == "sessions") {
            ActivityQueryStatus(state.summary) { scope.launch { controller.refresh() } }
            val summary = state.summary.data
            ActivityChoices(listOf("sessions", "duration_minutes", "working_sets"), measure, { stringResource(when (it) {
                "duration_minutes" -> DesignR.string.activity_redesign_session_measures_duration_minutes
                "working_sets" -> DesignR.string.activity_redesign_session_measures_working_sets
                else -> DesignR.string.activity_redesign_session_measures_sessions
            }) }, "activity_measure") { measure = it }
            val points = summary?.daily.orEmpty().mapNotNull { row ->
                val value = when (measure) {
                    "duration_minutes" -> row.durationMinutes?.toDoubleOrNull()?.takeIf { row.sessions > row.missingDurationSessions }
                    "working_sets" -> row.workingSets.toDouble()
                    else -> row.sessions.toDouble()
                }
                value?.let { ActivityChartPoint(activityDayMillis(row.date), it) }
            }
            if (summary?.totals?.sessions == 0) Text(stringResource(DesignR.string.activity_empty_period))
            else if (points.isNotEmpty()) ActivityLineChart(points, activitySubject(subject), ::activityChartDate, { it.toInt().toString() },
                Modifier.testTag("activity_dynamics_chart"))
            summary?.daily.orEmpty().forEach { row ->
                Text(activityDay(row.date) + " · " + stringResource(DesignR.string.activity_sessions_count) + ": ${row.sessions} · " +
                    stringResource(DesignR.string.activity_working_sets) + ": ${row.workingSets}")
            }
        } else {
            val query = if (subject == "active_minutes") state.activeHistory else state.stepsHistory
            ActivityQueryStatus(query) { scope.launch { controller.refresh() } }
            val rows = query.data?.daily.orEmpty().filter { it.metric.kind == subject }
            val points = rows.mapNotNull { row -> activityMetricNumber(row.metric)?.let { ActivityChartPoint(activityDayMillis(row.date), it) } }
            Text(stringResource(DesignR.string.activity_redesign_available_days, points.size.toString()))
            if (points.isNotEmpty()) ActivityLineChart(points, activitySubject(subject), ::activityChartDate, { it.toInt().toString() },
                Modifier.testTag("activity_dynamics_chart"))
            else if (!query.loading && query.error == null) Text(stringResource(if (subject == "steps") DesignR.string.activity_no_steps_data
                else DesignR.string.activity_no_active_data))
            Text(stringResource(DesignR.string.activity_redesign_history_coverage), style = MaterialTheme.typography.bodySmall)
            rows.forEach { row -> TextButton(onClick = { scope.launch { controller.selectDay(row.date); controller.selectTab("overview") } },
                modifier = Modifier.heightIn(min = 48.dp).testTag("activity_history_${row.date}")) {
                Text(activityDay(row.date) + " · " + activityMetricAmount(row.metric, subject, state.overview.data?.profile?.displayUnits
                    ?: PersonalDisplayUnits.Metric, state.locale) + " · " + activityCoverage(row.metric))
            } }
        }
    }
}

internal fun activityDayMillis(day: String): Double = runCatching { LocalDate.parse(day).atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli().toDouble() }.getOrDefault(0.0)
internal fun activityChartDate(value: Double): String = java.time.Instant.ofEpochMilli(value.toLong()).atZone(ZoneOffset.UTC).toLocalDate().toString()

@Composable
private fun ActivityStepsEditor(state: ActivityState, controller: ActivityController) {
    val draft = state.stepsDraft ?: return
    val scope = rememberCoroutineScope()
    val editable = state.canSteps && !state.busy && state.pending.isEmpty()
    PersonalSheet(stringResource(DesignR.string.activity_manual_steps), "activity_steps_form", {
        scope.launch { controller.dismissDraft("steps") }
    }) {
        ActivityWriteStatus(state, controller)
        Text(activityDay(draft.date))
        ActivityField(draft.value, { controller.updateStepsDraft(draft.copy(value = it)) }, DesignR.string.activity_steps_total,
            "activity_steps_total", editable, androidx.compose.ui.text.input.KeyboardType.Number)
        Text(stringResource(DesignR.string.activity_steps_hint), style = MaterialTheme.typography.bodySmall)
        ActivityAction(DesignR.string.common_save, "activity_steps_save", editable) { scope.launch { controller.saveSteps() } }
        if (state.steps.data?.override?.active == true) TextButton(onClick = { scope.launch { controller.clearSteps() } },
            enabled = editable, modifier = Modifier.heightIn(min = 48.dp).testTag("activity_steps_clear")) {
            Text(stringResource(DesignR.string.activity_clear_steps))
        }
    }
}
