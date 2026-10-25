package assistant.mobile.android

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import assistant.mobile.core.SessionLease
import assistant.mobile.core.activity.*
import assistant.mobile.design.R as DesignR
import kotlinx.coroutines.launch
import kotlinx.serialization.json.jsonPrimitive

/** Every edit stays in the controller's journal draft, including a revision conflict. */
@Composable
internal fun ActivitySessionScreen(controller: ActivityController, lease: SessionLease, sessionId: String?,
    onBack: () -> Unit, modifier: Modifier = Modifier) {
    val observed by controller.state.collectAsStateWithLifecycle()
    val state = observed?.takeIf { it.namespace == lease.namespace }
    val scope = rememberCoroutineScope()
    ActivityActivation(controller, lease)
    var ready by remember(lease.namespace, sessionId) { mutableStateOf(false) }
    var displayedSessionId by remember(lease.namespace, sessionId) { mutableStateOf<String?>(null) }
    LaunchedEffect(controller, lease.namespace, sessionId) {
        controller.clearReceipt()
        if (sessionId != null) controller.openSession(sessionId) else { controller.closeSession(); controller.beginSession() }
        ready = true
    }
    val selectedSession = state?.session?.data?.takeIf { ready && (sessionId == null || it.id == sessionId) }
    var deleting by remember(lease.namespace, sessionId) { mutableStateOf(false) }
    LaunchedEffect(selectedSession?.id) { selectedSession?.let { displayedSessionId = it.id } }
    LaunchedEffect(state?.receipt?.operationId, ready) {
        val receipt = state?.receipt
        if (ready && receipt?.kind == "activity.session.delete" && displayedSessionId != null &&
            receipt.raw["session_id"]?.jsonPrimitive?.content == displayedSessionId) onBack()
    }
    ScreenColumn("screen_ActivitySession", modifier,
        title = selectedSession?.title?.takeIf(String::isNotBlank)
            ?: selectedSession?.kind?.let { activityKind(it) } ?: stringResource(DesignR.string.activity_session),
        onBack = onBack) {
        if (state == null || !ready) Text(stringResource(DesignR.string.state_loading))
        state?.let { current ->
            ActivityWriteStatus(current, controller)
            ActivityQueryStatus(current.session) { scope.launch { controller.refresh() } }
            current.sessionDraft?.let { ActivitySessionEditor(current, controller) }
            selectedSession?.let { session ->
                if (current.sessionDraft == null) {
                    ActivitySessionSummary(session)
                    if (current.canWrite) {
                        ActivityAction(DesignR.string.common_edit, "activity_session_edit", !current.busy && current.pending.isEmpty()) {
                            controller.beginSession(session.id)
                        }
                        TextButton(onClick = { deleting = true }, enabled = !current.busy && current.pending.isEmpty(),
                            modifier = Modifier.heightIn(min = 48.dp).testTag("activity_session_delete")) {
                            Text(stringResource(DesignR.string.common_delete))
                        }
                    }
                }
                if (deleting) ActivitySection(stringResource(DesignR.string.activity_confirm_delete_session), "activity_session_delete_confirm") {
                    ActivityAction(DesignR.string.common_delete, "activity_session_delete_apply", !current.busy) {
                        scope.launch { controller.deleteSession(); deleting = false }
                    }
                    TextButton(onClick = { deleting = false }, modifier = Modifier.heightIn(min = 48.dp)) {
                        Text(stringResource(DesignR.string.common_cancel))
                    }
                }
                if (session.kind == "strength") ActivitySessionExercises(current, controller)
                current.sessionEffort.data?.let { effort ->
                    ActivitySection(stringResource(DesignR.string.activity_running_strap_effort), "activity_session_effort") {
                        Text(effort.sourceName, modifier = Modifier.testTag("activity_effort_source"))
                        ActivityWorkoutEffort(effort.heartRate, effort.zoneSeconds, effort.trimp, effort.effort, current.locale)
                    }
                }
            }
        }
    }
}

@Composable
private fun ActivitySessionSummary(session: ActivitySession) {
    Text(activityDay(session.date) + " · " + activityKind(session.kind))
    Text(stringResource(DesignR.string.activity_duration) + ": " + (session.durationMinutes?.let {
        it + " " + stringResource(DesignR.string.activity_minutes)
    } ?: stringResource(DesignR.string.activity_unknown)))
    Text(stringResource(DesignR.string.activity_distance) + ": " + (session.distanceKm?.let {
        it + " " + stringResource(DesignR.string.activity_kilometers)
    } ?: stringResource(DesignR.string.activity_unknown)))
    session.startedAt?.let { Text(activityMoment(it, session.timezone)) }
    session.note?.takeIf(String::isNotBlank)?.let { Text(it) }
}

@Composable
private fun ActivitySessionEditor(state: ActivityState, controller: ActivityController) {
    val draft = state.sessionDraft ?: return
    val scope = rememberCoroutineScope()
    val editable = state.canWrite && !state.busy && state.pending.isEmpty()
    ActivitySection(stringResource(if (draft.sessionId == null) DesignR.string.activity_add_session else DesignR.string.activity_edit_session),
        "activity_session_form") {
        ActivityChoices(activityKinds, draft.kind, { activityKind(it) }, "activity_session_kind",
            editable && state.session.data?.exercises.isNullOrEmpty()) { controller.updateSessionDraft(draft.copy(kind = it)) }
        ActivityField(draft.title, { controller.updateSessionDraft(draft.copy(title = it.take(200))) },
            if (draft.kind == "other") DesignR.string.activity_custom_name else DesignR.string.activity_session_title,
            "activity_session_name", editable)
        ActivityField(draft.date, { controller.updateSessionDraft(draft.copy(date = it)) }, DesignR.string.activity_session_date,
            "activity_session_date", editable)
        ActivityField(draft.time, { controller.updateSessionDraft(draft.copy(time = it)) }, DesignR.string.activity_session_time,
            "activity_session_time", editable)
        Text(stringResource(DesignR.string.personal_zone_hint, draft.timezone), style = MaterialTheme.typography.bodySmall)
        ActivityField(draft.duration, { controller.updateSessionDraft(draft.copy(duration = it)) }, DesignR.string.activity_duration,
            "activity_session_duration", editable, KeyboardType.Decimal)
        ActivityField(draft.distance, { controller.updateSessionDraft(draft.copy(distance = it)) }, DesignR.string.activity_distance,
            "activity_session_distance", editable, KeyboardType.Decimal)
        ActivityField(draft.note, { controller.updateSessionDraft(draft.copy(note = it.take(4000))) }, DesignR.string.activity_note,
            "activity_session_note", editable, singleLine = false)
        ActivityAction(DesignR.string.common_save, "activity_session_save", editable) { scope.launch { controller.saveSession() } }
        if (draft.sessionId != null) TextButton(onClick = { scope.launch { controller.dismissDraft("session") } },
            enabled = !state.busy, modifier = Modifier.heightIn(min = 48.dp).testTag("activity_session_cancel")) {
            Text(stringResource(DesignR.string.common_cancel))
        }
    }
}

@Composable
private fun ActivitySessionExercises(state: ActivityState, controller: ActivityController) {
    val session = state.session.data ?: return
    val scope = rememberCoroutineScope()
    val editable = state.canWrite && !state.busy && state.pending.isEmpty()
    var catalogOpen by remember(session.id) { mutableStateOf(false) }
    var deletingExercise by remember(session.id) { mutableStateOf<String?>(null) }
    var deletingSet by remember(session.id) { mutableStateOf<String?>(null) }
    ActivityQueryStatus(state.catalog) { scope.launch { controller.refresh() } }
    ActivitySection(stringResource(DesignR.string.activity_detail_catalog), "activity_session_exercises") {
        if (state.canWrite) TextButton(onClick = { catalogOpen = !catalogOpen }, enabled = editable,
            modifier = Modifier.heightIn(min = 48.dp).testTag("activity_exercise_catalog")) {
            Text(stringResource(DesignR.string.activity_choose_exercise))
        }
        if (catalogOpen) {
            if (state.catalog.data.isNullOrEmpty()) Text(stringResource(DesignR.string.activity_no_exercises))
            state.catalog.data.orEmpty().forEach { exercise ->
                TextButton(onClick = { scope.launch { controller.addExercise(exercise.id); catalogOpen = false } },
                    enabled = editable, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                        .testTag("activity_exercise_add_${exercise.id}")) {
                    Text(listOfNotNull(exercise.name, exercise.equipment, exercise.variant).filter(String::isNotBlank).joinToString(" · "))
                }
            }
        }
        if (session.exercises.isEmpty()) Text(stringResource(DesignR.string.activity_no_session_exercises))
        session.exercises.sortedBy { it.position }.forEach { exercise ->
            HorizontalDivider()
            Text(exercise.snapshot.name, style = MaterialTheme.typography.titleMedium)
            Text(listOfNotNull(exercise.snapshot.equipment, exercise.snapshot.variant).filter(String::isNotBlank).joinToString(" · "))
            Text(activityLoadBasis(exercise.snapshot.loadBasis), style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = { scope.launch { controller.openProgress(exercise.exerciseId) } },
                modifier = Modifier.heightIn(min = 48.dp).testTag("activity_progress_${exercise.exerciseId}")) {
                Text(stringResource(DesignR.string.activity_exercise_history))
            }
            exercise.note?.takeIf(String::isNotBlank)?.let { Text(it) }
            if (editable) {
                TextButton(onClick = { controller.beginExerciseNote(exercise.id) }, modifier = Modifier.heightIn(min = 48.dp)
                    .testTag("activity_exercise_note_edit_${exercise.id}")) { Text(stringResource(DesignR.string.common_edit)) }
                TextButton(onClick = { deletingExercise = exercise.id }, modifier = Modifier.heightIn(min = 48.dp)
                    .testTag("activity_exercise_delete_${exercise.id}")) { Text(stringResource(DesignR.string.common_delete)) }
            }
            if (deletingExercise == exercise.id) {
                Text(stringResource(DesignR.string.activity_confirm_delete_session_exercise))
                ActivityAction(DesignR.string.common_delete, "activity_exercise_delete_apply", editable) {
                    scope.launch { controller.deleteExercise(exercise.id); deletingExercise = null }
                }
                TextButton(onClick = { deletingExercise = null }) { Text(stringResource(DesignR.string.common_cancel)) }
            }
            if (exercise.sets.isEmpty()) Text(stringResource(DesignR.string.activity_no_sets))
            exercise.sets.sortedBy { it.position }.forEachIndexed { index, set ->
                Text("${index + 1}. " + stringResource(if (set.kind == "warmup") DesignR.string.activity_set_kinds_warmup
                    else DesignR.string.activity_set_kinds_working) + " · " + (set.repetitions?.toString()
                    ?: stringResource(DesignR.string.activity_unknown)) + " " + stringResource(DesignR.string.activity_repetitions_short)
                    + " · " + activitySetLoad(exercise.snapshot.loadBasis, set), modifier = Modifier.testTag("activity_set_${set.id}"))
                set.note?.takeIf(String::isNotBlank)?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                if (state.canWrite) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = { controller.beginSet(exercise.id, set.id) }, enabled = editable,
                        modifier = Modifier.heightIn(min = 48.dp).testTag("activity_set_edit_${set.id}")) { Text(stringResource(DesignR.string.common_edit)) }
                    TextButton(onClick = { deletingSet = set.id }, enabled = editable, modifier = Modifier.heightIn(min = 48.dp)
                        .testTag("activity_set_delete_${set.id}")) { Text(stringResource(DesignR.string.common_delete)) }
                }
                if (deletingSet == set.id) {
                    Text(stringResource(DesignR.string.activity_confirm_delete_set))
                    ActivityAction(DesignR.string.common_delete, "activity_set_delete_apply", editable) {
                        scope.launch { controller.deleteSet(exercise.id, set.id); deletingSet = null }
                    }
                    TextButton(onClick = { deletingSet = null }) { Text(stringResource(DesignR.string.common_cancel)) }
                }
            }
            if (state.canWrite) ActivityAction(DesignR.string.activity_add_set, "activity_set_add_${exercise.id}", editable) {
                controller.beginSet(exercise.id)
            }
            if (state.setDraft?.sessionExerciseId == exercise.id) ActivitySetEditor(state, controller)
            state.exerciseNoteDraft?.takeIf { it.sessionExerciseId == exercise.id }?.let { draft ->
                ActivityField(draft.note, { controller.updateExerciseNoteDraft(draft.copy(note = it)) }, DesignR.string.activity_exercise_note,
                    "activity_exercise_note", editable, singleLine = false)
                ActivityAction(DesignR.string.common_save, "activity_exercise_note_save", editable) {
                    scope.launch { controller.saveExerciseNote() }
                }
            }
        }
    }
    if (state.progress.data != null) ActivityExerciseProgress(state, controller)
}

@Composable
private fun ActivitySetEditor(state: ActivityState, controller: ActivityController) {
    val draft = state.setDraft ?: return
    val scope = rememberCoroutineScope()
    val editable = state.canWrite && !state.busy && state.pending.isEmpty()
    Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.testTag("activity_set_form")) {
        Text(stringResource(if (draft.setId == null) DesignR.string.activity_add_set else DesignR.string.activity_edit_set),
            style = MaterialTheme.typography.titleMedium)
        ActivityField(draft.repetitions, { controller.updateSetDraft(draft.copy(repetitions = it)) }, DesignR.string.activity_repetitions,
            "activity_set_reps", editable, KeyboardType.Number)
        if (draft.loadBasis != "bodyweight") ActivityField(draft.loadValue, { controller.updateSetDraft(draft.copy(loadValue = it)) },
            DesignR.string.activity_load, "activity_set_weight", editable, KeyboardType.Decimal)
        ActivityChoices(listOf("kg", "lb"), draft.loadUnit, { stringResource(if (it == "lb") DesignR.string.activity_load_units_lb
            else DesignR.string.activity_load_units_kg) }, "activity_set_unit", editable) { controller.updateSetDraft(draft.copy(loadUnit = it)) }
        if (draft.loadBasis == "per_implement") ActivityField(draft.implementCount, { controller.updateSetDraft(draft.copy(implementCount = it)) },
            DesignR.string.activity_implement_count, "activity_set_count", editable, KeyboardType.Number)
        ActivityChoices(listOf("working", "warmup"), draft.kind, { stringResource(if (it == "warmup") DesignR.string.activity_set_kinds_warmup
            else DesignR.string.activity_set_kinds_working) }, "activity_set_kind", editable) { controller.updateSetDraft(draft.copy(kind = it)) }
        ActivityField(draft.note, { controller.updateSetDraft(draft.copy(note = it)) }, DesignR.string.activity_set_note,
            "activity_set_note", editable, singleLine = false)
        ActivityAction(DesignR.string.common_save, "activity_set_save", editable) { scope.launch { controller.saveSet() } }
        TextButton(onClick = { scope.launch { controller.dismissDraft("set") } }, enabled = !state.busy,
            modifier = Modifier.heightIn(min = 48.dp).testTag("activity_set_cancel")) { Text(stringResource(DesignR.string.common_cancel)) }
    }
}

@Composable
private fun activitySetLoad(basis: String, set: ActivitySet): String {
    if (basis == "bodyweight") return activityLoadBasis(basis)
    return (set.loadValue ?: stringResource(DesignR.string.activity_unknown)) + " " + stringResource(
        if (set.loadUnit == "lb") DesignR.string.activity_load_units_lb else DesignR.string.activity_load_units_kg) +
        if (basis == "per_implement") " × ${set.implementCount}" else ""
}
