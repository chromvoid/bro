package assistant.mobile.android

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import assistant.mobile.design.ui.ProgressBar
import assistant.mobile.core.GeneratedStrings
import assistant.mobile.core.tasks.TaskAssistantController
import assistant.mobile.core.tasks.TaskAssistantGrants
import assistant.mobile.core.tasks.TaskAssistantMode
import assistant.mobile.core.tasks.TaskAssistantState
import assistant.mobile.core.tasks.TaskCompletionCheck
import assistant.mobile.core.tasks.TaskCompletionMode
import assistant.mobile.core.tasks.TaskGrantKind
import assistant.mobile.core.tasks.safeTaskAssistantSource
import assistant.mobile.design.R as DesignR
import kotlinx.coroutines.launch

/** The surrounding task card owns scrolling. Every delegated write starts at a visible button. */
@Composable
internal fun TaskAssistantSection(taskId: String, controller: TaskAssistantController) {
    val observed by controller.state.collectAsStateWithLifecycle()
    val state = observed?.takeIf { it.taskId == taskId }
    val owner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    var advanced by remember(taskId) { mutableStateOf(false) }
    var reviseId by remember(taskId) { mutableStateOf<String?>(null) }
    DisposableEffect(taskId, controller, owner) {
        val observer = LifecycleEventObserver { _, event -> when (event) {
            Lifecycle.Event.ON_START -> controller.activate(taskId)
            Lifecycle.Event.ON_STOP -> controller.deactivate()
            else -> Unit
        } }
        owner.lifecycle.addObserver(observer)
        if (owner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) controller.activate(taskId)
        onDispose { owner.lifecycle.removeObserver(observer); controller.deactivate() }
    }
    Column(Modifier.fillMaxWidth().testTag("task_assistant"), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (state == null || state.snapshot.loading && state.snapshot.data == null) {
            ProgressBar(modifier = Modifier.fillMaxWidth())
            StatusMessage(GeneratedStrings.State.loading)
            return@Column
        }
        if (state.snapshot.stale || state.capabilities.stale) StatusMessage(GeneratedStrings.State.refreshFailed, "task_assistant_stale")
        listOfNotNull(state.error, state.snapshot.error, state.capabilities.error).distinct().forEach { ErrorMessage(it, "task_assistant_error") }
        if (state.snapshot.error != null || state.capabilities.error != null) TextButton(
            onClick = { scope.launch { controller.refresh() } }, modifier = Modifier.heightIn(min = 48.dp).testTag("task_assistant_retry")) {
            Text(stringResource(DesignR.string.common_retry))
        }
        if (state.capabilities.data?.enabled == false) Text(stringResource(DesignR.string.tasks_agent_disabled))
        val delegation = state.snapshot.data?.delegation
        val enabled = !state.busy
        if (delegation != null) OutlinedCard(Modifier.fillMaxWidth().testTag("task_assistant_delegation")) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(taskAssistantStatus(delegation.status), style = MaterialTheme.typography.labelLarge)
                Text(stringResource(if (delegation.mode == TaskAssistantMode.Oneoff) DesignR.string.tasks_agent_modes_oneoff else DesignR.string.tasks_agent_modes_monitor))
                Text(delegation.instruction)
                Text(stringResource(if (delegation.completion == TaskCompletionMode.Manual) DesignR.string.tasks_agent_completions_manual else DesignR.string.tasks_agent_completions_auto))
                if (delegation.mode == TaskAssistantMode.Monitor) Text(stringResource(DesignR.string.tasks_agent_monitor_info, (delegation.intervalMinutes ?: 60).toString()))
                delegation.budget?.let { budget -> Text(stringResource(DesignR.string.tasks_agent_budget,
                    (((budget.activeSeconds ?: 300) + 59) / 60).toString(), (budget.toolCalls ?: 20).toString(), (budget.dailyRuns ?: 48).toString())) }
                delegation.nextRunAt?.let { Text(stringResource(DesignR.string.tasks_agent_next_run, it)) }
                delegation.checks.forEach { Text(taskCheckLabel(it)) }
                TaskAssistantGrants(state, delegation.grants, false, controller)
                if (state.canManage && delegation.active) {
                    if (state.canEditAuthority) OutlinedButton(onClick = { controller.beginEdit() }, enabled = enabled && state.capabilities.data?.enabled == true,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("task_assistant_edit")) { Text(stringResource(DesignR.string.tasks_agent_edit)) }
                    if (delegation.status != "paused" || state.canEditAuthority) OutlinedButton(onClick = {
                        scope.launch { controller.control(if (delegation.status == "paused") "resume" else "pause") }
                    }, enabled = enabled, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("task_assistant_pause_resume")) {
                        Text(stringResource(if (delegation.status == "paused") DesignR.string.tasks_agent_resume else DesignR.string.tasks_agent_pause))
                    }
                    TextButton(onClick = { scope.launch { controller.control("revoke") } }, enabled = enabled,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("task_assistant_revoke")) { Text(stringResource(DesignR.string.tasks_agent_revoke)) }
                }
            }
        }
        if (state.canWrite && state.task?.status != "done" && state.capabilities.data?.enabled == true &&
            (delegation?.active != true || state.draft.editingId != null && state.canEditAuthority)) {
            val draft = state.draft
            Text(stringResource(if (draft.editingId == null) DesignR.string.tasks_agent_delegate else DesignR.string.tasks_agent_edit),
                style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
            Text(stringResource(DesignR.string.tasks_agent_explanation))
            OutlinedTextField(value = draft.instruction, onValueChange = { controller.updateDraft(draft.copy(instruction = it.take(10000))) },
                enabled = enabled, label = { Text(stringResource(DesignR.string.tasks_agent_instruction)) },
                placeholder = { Text(stringResource(DesignR.string.tasks_agent_instruction_placeholder)) }, minLines = 3,
                modifier = Modifier.fillMaxWidth().testTag("task_assistant_instruction"))
            Text(stringResource(DesignR.string.tasks_agent_mode), style = MaterialTheme.typography.labelLarge)
            TaskAssistantMode.entries.forEach { mode -> OutlinedButton(onClick = { controller.updateDraft(draft.copy(mode = mode)) }, enabled = enabled,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("task_assistant_mode_${mode.wire}").semantics { selected = draft.mode == mode }) {
                Text(stringResource(if (mode == TaskAssistantMode.Oneoff) DesignR.string.tasks_agent_modes_oneoff else DesignR.string.tasks_agent_modes_monitor))
            } }
            if (draft.mode == TaskAssistantMode.Monitor) {
                OutlinedTextField(value = draft.interval, onValueChange = { controller.updateDraft(draft.copy(interval = it)) },
                    enabled = enabled, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), singleLine = true,
                    label = { Text(stringResource(DesignR.string.tasks_agent_interval)) }, modifier = Modifier.fillMaxWidth().testTag("task_assistant_interval"))
                Text(stringResource(DesignR.string.tasks_agent_monitor_hint))
            }
            Text(stringResource(DesignR.string.tasks_agent_completion), style = MaterialTheme.typography.labelLarge)
            TaskCompletionMode.entries.forEach { completion -> OutlinedButton(onClick = { controller.updateDraft(draft.copy(completion = completion,
                checks = if (completion == TaskCompletionMode.Auto && draft.checks.isEmpty()) TaskCompletionCheck.entries else draft.checks)) }, enabled = enabled,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("task_assistant_completion_${completion.wire}").semantics { selected = draft.completion == completion }) {
                Text(stringResource(if (completion == TaskCompletionMode.Manual) DesignR.string.tasks_agent_completions_manual else DesignR.string.tasks_agent_completions_auto))
            } }
            if (draft.completion == TaskCompletionMode.Auto) {
                Text(stringResource(DesignR.string.tasks_agent_required_checks), style = MaterialTheme.typography.labelLarge)
                TaskCompletionCheck.entries.forEach { check -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Checkbox(checked = check in draft.checks, onCheckedChange = { selected -> controller.updateDraft(draft.copy(
                        checks = if (selected) (draft.checks + check).distinct() else draft.checks - check)) }, enabled = enabled,
                        modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp).testTag("task_assistant_check_${check.wire}"))
                    Text(taskCheckLabel(check), modifier = Modifier.padding(top = 12.dp))
                } }
                Text(stringResource(DesignR.string.tasks_agent_auto_hint))
            }
            TaskAssistantGrants(state, if (draft.customGrants) draft.grants else requireNotNull(state.capabilities.data).defaultGrants, advanced, controller)
            TextButton(onClick = { controller.customize(); advanced = !advanced }, enabled = enabled,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("task_assistant_permissions")) { Text(stringResource(DesignR.string.tasks_agent_permissions)) }
            if (advanced) Text(stringResource(DesignR.string.tasks_agent_permissions_hint))
            if (draft.executorPermissionsMissing) Text(stringResource(DesignR.string.tasks_agent_worker_permission_hint))
            Button(onClick = { scope.launch { controller.save() } }, enabled = enabled && draft.valid,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("task_assistant_start")) {
                Text(stringResource(if (draft.editingId == null) DesignR.string.tasks_agent_start else DesignR.string.common_save))
            }
            if (draft.editingId != null) TextButton(onClick = { controller.cancelEdit() }, enabled = enabled,
                modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(DesignR.string.common_cancel)) }
        }
        state.snapshot.data?.questions?.forEach { question -> OutlinedCard(Modifier.fillMaxWidth().testTag("task_assistant_question_${question.id}")) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(DesignR.string.tasks_agent_question), style = MaterialTheme.typography.titleSmall)
                if (question.private) Text(stringResource(DesignR.string.tasks_agent_private), modifier = Modifier.testTag("task_assistant_private_question_${question.id}"))
                Text(question.text)
                question.answer?.let { Text(it, modifier = Modifier.testTag("task_assistant_saved_answer_${question.id}")) }
                if (question.pending && state.canManage) {
                    if (question.kind == "proposal") {
                        Button(onClick = { scope.launch { controller.decide(question.id, "approve") } }, enabled = enabled,
                            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("task_assistant_approve_${question.id}")) { Text(stringResource(DesignR.string.tasks_agent_approve)) }
                        OutlinedButton(onClick = { scope.launch { controller.decide(question.id, "decline") } }, enabled = enabled,
                            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("task_assistant_decline_${question.id}")) { Text(stringResource(DesignR.string.tasks_agent_decline)) }
                    } else {
                        OutlinedTextField(value = state.draft.answers[question.id].orEmpty(), onValueChange = { controller.updateDraft(state.draft.copy(
                            answers = state.draft.answers + (question.id to it.take(10000)))) }, enabled = enabled,
                            label = { Text(stringResource(DesignR.string.tasks_agent_answer)) }, modifier = Modifier.fillMaxWidth().testTag("task_assistant_answer_${question.id}"))
                        Button(onClick = { scope.launch { controller.answer(question.id) } }, enabled = enabled && state.draft.answers[question.id]?.isNotBlank() == true,
                            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("task_assistant_send_answer_${question.id}")) { Text(stringResource(DesignR.string.tasks_agent_answer)) }
                    }
                }
            }
        } }
        val uriHandler = LocalUriHandler.current
        state.snapshot.data?.results?.takeIf { it.isNotEmpty() }?.let { results ->
            Text(stringResource(DesignR.string.tasks_agent_results), style = MaterialTheme.typography.titleMedium)
            results.forEach { result -> OutlinedCard(Modifier.fillMaxWidth().testTag("task_assistant_result_${result.id}")) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(taskAssistantStatus(if (result.status == "pending") "waiting_review" else result.status))
                    if (result.private) Text(stringResource(DesignR.string.tasks_agent_private), modifier = Modifier.testTag("task_assistant_private_result_${result.id}"))
                    Text(result.body)
                    result.sources.forEach { source -> safeTaskAssistantSource(source.url)?.let { url -> TextButton(onClick = { uriHandler.openUri(url) },
                        modifier = Modifier.heightIn(min = 48.dp)) { Text(source.title ?: source.url) } } }
                    if (state.canManage && result.reviewable) {
                        Button(onClick = { scope.launch { controller.accept(result.id, false) } }, enabled = enabled,
                            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("task_assistant_accept_${result.id}")) { Text(stringResource(DesignR.string.tasks_agent_accept)) }
                        if (state.task?.status != "done") OutlinedButton(onClick = { scope.launch { controller.accept(result.id, true) } }, enabled = enabled,
                            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("task_assistant_accept_complete_${result.id}")) { Text(stringResource(DesignR.string.tasks_agent_accept_complete)) }
                        if (state.canEditAuthority) TextButton(onClick = { reviseId = result.id }, enabled = enabled && state.capabilities.data?.enabled == true,
                            modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(DesignR.string.tasks_agent_revise)) }
                        if (reviseId == result.id) {
                            OutlinedTextField(value = state.draft.revisions[result.id].orEmpty(), onValueChange = { controller.updateDraft(state.draft.copy(
                                revisions = state.draft.revisions + (result.id to it.take(10000)))) }, enabled = enabled,
                                label = { Text(stringResource(DesignR.string.tasks_agent_revision_instruction)) }, modifier = Modifier.fillMaxWidth())
                            Button(onClick = { scope.launch { controller.revise(result.id) } }, enabled = enabled && state.draft.revisions[result.id]?.isNotBlank() == true,
                                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(stringResource(DesignR.string.tasks_agent_continue)) }
                        }
                    }
                }
            } }
        }
        state.snapshot.data?.runs?.takeIf { it.isNotEmpty() }?.let { runs ->
            HorizontalDivider()
            Text(stringResource(DesignR.string.tasks_agent_history), style = MaterialTheme.typography.titleMedium)
            runs.forEach { run -> OutlinedButton(onClick = { scope.launch { controller.openRun(run.id) } },
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("task_assistant_run_${run.id}")) {
                Column(Modifier.fillMaxWidth()) {
                    Text(taskAssistantStatus(run.status))
                    run.createdAt?.let { Text(it) }
                    run.summary?.let { Text(it) }
                    if (run.failed) Text(stringResource(DesignR.string.tasks_agent_run_error))
                }
            } }
        }
        state.run.error?.let { ErrorMessage(it, "task_assistant_run_error") }
        if (state.run.loading) ProgressBar(modifier = Modifier.fillMaxWidth())
        state.run.data?.let { run -> OutlinedCard(Modifier.fillMaxWidth().testTag("task_assistant_run_detail")) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(taskAssistantStatus(run.status))
                run.summary?.let { Text(it) }
                run.createdAt?.let { Text(it) }
                run.finishedAt?.let { Text(it) }
                if (run.failed || run.status == "failed") Text(stringResource(DesignR.string.tasks_agent_run_error))
            }
        } }
    }
}

@Composable
private fun TaskAssistantGrants(state: TaskAssistantState, grants: TaskAssistantGrants, editing: Boolean, controller: TaskAssistantController) {
    Column(Modifier.fillMaxWidth().testTag("task_assistant_grants"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        TaskGrantKind.entries.forEach { kind ->
            Text(stringResource(when (kind) { TaskGrantKind.Tools -> DesignR.string.tasks_agent_grant_kinds_tools
                TaskGrantKind.Actions -> DesignR.string.tasks_agent_grant_kinds_actions
                TaskGrantKind.Resources -> DesignR.string.tasks_agent_grant_kinds_resources
                TaskGrantKind.Executors -> DesignR.string.tasks_agent_grant_kinds_executors }), style = MaterialTheme.typography.labelLarge,
                modifier = if (kind == TaskGrantKind.Resources) Modifier.testTag("task_assistant_audience") else Modifier)
            val choices = state.capabilities.data?.choices(kind).orEmpty()
            if (editing) {
                if (choices.isEmpty()) Text(stringResource(DesignR.string.tasks_agent_no_choices))
                choices.forEach { choice -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    val mandatory = kind == TaskGrantKind.Resources && choice.id == state.task?.listId
                    Checkbox(checked = mandatory || choice.id in grants.values(kind), enabled = !state.busy && !mandatory,
                        onCheckedChange = { controller.selectGrant(kind, choice.id, it) }, modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp).testTag("task_assistant_grant_${kind.wire}_${choice.id}"))
                    Column(Modifier.weight(1f).padding(top = 12.dp)) {
                        Text(choice.title ?: choice.id)
                        choice.description?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                    }
                } }
            } else if (grants.values(kind).isEmpty()) Text(stringResource(DesignR.string.tasks_agent_no_choices))
            else grants.values(kind).forEach { id -> Text(choices.firstOrNull { it.id == id }?.title ?: id) }
        }
    }
}

@Composable
private fun taskCheckLabel(check: TaskCompletionCheck): String = stringResource(when (check) {
    TaskCompletionCheck.HasResult -> DesignR.string.tasks_agent_checks_has_result
    TaskCompletionCheck.AllSubtasksDone -> DesignR.string.tasks_agent_checks_all_subtasks_done
    TaskCompletionCheck.AllChecklistDone -> DesignR.string.tasks_agent_checks_all_checklist_done
    TaskCompletionCheck.NoPendingQuestions -> DesignR.string.tasks_agent_checks_no_pending_questions
})

@Composable
private fun taskAssistantStatus(status: String): String = when (status) {
    "active" -> stringResource(DesignR.string.tasks_agent_statuses_active)
    "running" -> stringResource(DesignR.string.tasks_agent_statuses_running)
    "queued" -> stringResource(DesignR.string.tasks_agent_statuses_queued)
    "pending", "waiting" -> stringResource(DesignR.string.tasks_agent_statuses_waiting)
    "waiting_user", "waiting_input" -> stringResource(DesignR.string.tasks_agent_statuses_waiting_input)
    "waiting_worker" -> stringResource(DesignR.string.tasks_agent_statuses_waiting_worker)
    "paused" -> stringResource(DesignR.string.tasks_agent_statuses_paused)
    "revoked" -> stringResource(DesignR.string.tasks_agent_statuses_revoked)
    "done", "completed" -> stringResource(DesignR.string.tasks_agent_statuses_completed)
    "cancelled" -> stringResource(DesignR.string.tasks_agent_statuses_cancelled)
    "failed" -> stringResource(DesignR.string.tasks_agent_statuses_failed)
    "ready", "proposed", "review", "pending_review", "waiting_review" -> stringResource(DesignR.string.tasks_agent_statuses_waiting_review)
    "accepted" -> stringResource(DesignR.string.tasks_agent_statuses_accepted)
    "rejected" -> stringResource(DesignR.string.tasks_agent_statuses_rejected)
    "revising" -> stringResource(DesignR.string.tasks_agent_statuses_revising)
    "succeeded" -> stringResource(DesignR.string.tasks_agent_statuses_succeeded)
    "unchanged" -> stringResource(DesignR.string.tasks_agent_statuses_unchanged)
    "budget_exhausted" -> stringResource(DesignR.string.tasks_agent_statuses_budget_exhausted)
    "revision_requested" -> stringResource(DesignR.string.tasks_agent_statuses_revision_requested)
    "blocked" -> stringResource(DesignR.string.tasks_agent_statuses_blocked)
    else -> status
}
