package assistant.mobile.android

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
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
import androidx.compose.ui.unit.dp
import assistant.mobile.core.SessionLease
import assistant.mobile.core.tasks.*
import assistant.mobile.design.R as DesignR
import kotlinx.coroutines.launch

@Composable
internal fun TaskHistorySection(state: TasksState, controller: TasksController) {
    val scope = LocalTaskActionScope.current
    val page = state.history.data
    val zone = state.page.data?.timezone ?: state.board.data?.timezone ?: "UTC"
    Column(Modifier.fillMaxWidth().testTag("task_history"), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(stringResource(DesignR.string.tasks_history_scope), style = MaterialTheme.typography.bodySmall)
        TaskQueryStatus(state.history, "history") { scope.launch { controller.refreshSelected() } }
        if (page?.items?.isEmpty() == true) Text(stringResource(DesignR.string.tasks_history_empty))
        page?.items.orEmpty().forEach { event ->
            TaskSection("task_history_${event.id}") {
                TaskHeading(stringResource(taskHistoryKind(event.kind)))
                Text(event.actor.name.ifBlank { stringResource(DesignR.string.tasks_history_unknown_person) } + " · " + taskTimestamp(event.createdAt, zone),
                    style = MaterialTheme.typography.bodySmall)
                event.changes.forEach { (field, change) ->
                    if (change.marker) taskHistoryMarker(field)?.let { Text(stringResource(it)) }
                    else taskHistoryField(field)?.let { label ->
                        Text(stringResource(label) + ": " + taskHistoryValue(field, change.before, page?.people.orEmpty()) + " → " +
                            taskHistoryValue(field, change.after, page?.people.orEmpty()))
                    }
                }
                if (event.reason == "member_left") Text(stringResource(DesignR.string.tasks_history_member_left))
                if (event.reason == "account_deleted") Text(stringResource(DesignR.string.tasks_history_account_deleted))
            }
        }
        if (page?.nextCursor != null) OutlinedButton(onClick = { scope.launch { controller.loadMoreHistory() } },
            enabled = !state.history.loading, modifier = Modifier.heightIn(min = 48.dp).testTag("task_history_more")) {
            Text(stringResource(DesignR.string.tasks_history_load_more))
        }
    }
}

@Composable
private fun taskHistoryValue(field: String, value: String?, people: List<TaskPerson>): String {
    if (value == null) return stringResource(if (field == "assigned_to") DesignR.string.tasks_history_unassigned else DesignR.string.tasks_history_not_set)
    return when (field) {
        "state" -> taskStateLabel(TaskState.fromWire(value))
        "priority" -> stringResource(when (value) { "1" -> DesignR.string.tasks_priorities_1; "3" -> DesignR.string.tasks_priorities_3; else -> DesignR.string.tasks_priorities_2 })
        "assigned_to" -> people.find { it.id == value }?.name ?: stringResource(DesignR.string.tasks_history_unknown_person)
        else -> taskDayLabel(value)
    }
}
private fun taskHistoryKind(kind: String): Int = when (kind) {
    "created" -> DesignR.string.tasks_history_kinds_created
    "state_changed" -> DesignR.string.tasks_history_kinds_state_changed
    "assigned" -> DesignR.string.tasks_history_kinds_assigned
    "unassigned" -> DesignR.string.tasks_history_kinds_unassigned
    "deleted" -> DesignR.string.tasks_history_kinds_deleted
    "restored" -> DesignR.string.tasks_history_kinds_restored
    "moved" -> DesignR.string.tasks_history_kinds_moved
    "reordered" -> DesignR.string.tasks_history_kinds_reordered
    "parent_changed" -> DesignR.string.tasks_history_kinds_parent_changed
    "result_accepted" -> DesignR.string.tasks_history_kinds_result_accepted
    else -> DesignR.string.tasks_history_kinds_updated
}
private fun taskHistoryField(field: String): Int? = when (field) {
    "state" -> DesignR.string.tasks_state
    "priority" -> DesignR.string.tasks_priority
    "planned_on" -> DesignR.string.tasks_planned_on
    "due_on" -> DesignR.string.tasks_due_on
    "assigned_to" -> DesignR.string.circle_assignee
    else -> null
}
private fun taskHistoryMarker(field: String): Int? = when (field) {
    "title" -> DesignR.string.tasks_history_fields_title
    "description" -> DesignR.string.tasks_history_fields_description
    "checklist" -> DesignR.string.tasks_history_fields_checklist
    "waiting_reason" -> DesignR.string.tasks_history_fields_waiting_reason
    "title_images" -> DesignR.string.tasks_history_fields_title_images
    "description_images" -> DesignR.string.tasks_history_fields_description_images
    "list_id" -> DesignR.string.tasks_history_fields_list_id
    "parent_id" -> DesignR.string.tasks_history_fields_parent_id
    "position" -> DesignR.string.tasks_history_fields_position
    else -> null
}

@Composable
internal fun TaskDiscussionSection(state: TasksState, controller: TasksController, lease: SessionLease) {
    val taskId = state.task.data?.id ?: return
    val scope = LocalTaskActionScope.current
    val message = state.discussionDraft
    val page = state.discussion.data
    val zone = state.page.data?.timezone ?: state.board.data?.timezone ?: "UTC"
    val writable = state.task.data?.let(controller::canWrite) == true
    LaunchedEffect(taskId, page?.channelId, page?.messages?.lastOrNull()?.id) {
        if (page != null && state.discussion.error == null) controller.markDiscussionRead()
    }
    Column(Modifier.fillMaxWidth().testTag("task_discussion"), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(stringResource(DesignR.string.tasks_discussion_audience), style = MaterialTheme.typography.bodySmall)
        TaskQueryStatus(state.discussion, "discussion") { scope.launch { controller.refreshSelected() } }
        if (page?.nextCursor != null) TextButton(onClick = { scope.launch { controller.loadMoreDiscussion() } },
            enabled = !state.discussion.loading, modifier = Modifier.heightIn(min = 48.dp).testTag("task_discussion_more")) {
            Text(stringResource(DesignR.string.tasks_earlier_messages))
        }
        if (page?.messages?.isEmpty() == true) Text(stringResource(DesignR.string.circle_start_talking))
        page?.messages.orEmpty().forEach { entry ->
            TaskSection("task_discussion_${entry.id}") {
                Text((when {
                    entry.author == "assistant" -> stringResource(DesignR.string.circle_assistant)
                    entry.actorUserId == lease.namespace.userId -> stringResource(DesignR.string.circle_you)
                    else -> entry.name ?: stringResource(DesignR.string.circle_participant)
                }) + " · " + taskTimestamp(entry.createdAt, zone), style = MaterialTheme.typography.bodySmall)
                Text(entry.text.orEmpty())
            }
        }
        if (writable && page != null) {
            OutlinedTextField(value = message, onValueChange = controller::updateDiscussionDraft, enabled = !state.busy,
                label = { Text(stringResource(DesignR.string.circle_message)) }, modifier = Modifier.fillMaxWidth().testTag("task_discussion_text"))
            Button(onClick = { scope.launch {
                controller.sendDiscussion(message)
            } }, enabled = !state.busy && message.isNotBlank(), modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("task_discussion_send")) {
                Text(stringResource(DesignR.string.circle_send))
            }
            OutlinedButton(onClick = { scope.launch {
                controller.sendDiscussion(message, assistant = true)
            } }, enabled = !state.busy && message.isNotBlank(), modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("task_discussion_ask_assistant")) {
                Text(stringResource(DesignR.string.circle_ask_assistant))
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TaskProjectSheet(state: TasksState, project: TaskList?, controller: TasksController, onDismiss: () -> Unit) {
    val scope = LocalTaskActionScope.current
    var name by remember(project?.id) { mutableStateOf(project?.name.orEmpty()) }
    var outcome by remember(project?.id) { mutableStateOf(project?.outcome.orEmpty()) }
    var space by remember(project?.id) { mutableStateOf(project?.spaceId.orEmpty()) }
    var status by remember(project?.id) { mutableStateOf(project?.projectState ?: "active") }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).imePadding().padding(20.dp).testTag("tasks_project_sheet"),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            TaskHeading(stringResource(if (project == null) DesignR.string.tasks_new_project else DesignR.string.tasks_project_settings))
            state.error?.let { ErrorMessage(it, "tasks_project_error") }
            TasksPending(state, controller)
            OutlinedTextField(value = name, onValueChange = { name = it.take(120) }, enabled = !state.busy,
                label = { Text(stringResource(DesignR.string.tasks_project_name)) }, modifier = Modifier.fillMaxWidth().testTag("tasks_project_name"))
            OutlinedTextField(value = outcome, onValueChange = { outcome = it.take(10000) }, enabled = !state.busy,
                label = { Text(stringResource(DesignR.string.tasks_project_outcome)) }, modifier = Modifier.fillMaxWidth().testTag("tasks_project_outcome"))
            if (project == null && !state.spaces.data.isNullOrEmpty()) TaskOptionPicker(stringResource(DesignR.string.tasks_project_audience), space,
                listOf("" to stringResource(DesignR.string.tasks_only_me)) + state.spaces.data.orEmpty().map { it.id to it.name },
                !state.busy, "tasks_project_space") { space = it }
            else if (project != null) TaskOptionPicker(stringResource(DesignR.string.tasks_state), status,
                listOf("active" to stringResource(DesignR.string.tasks_project_states_active), "done" to stringResource(DesignR.string.tasks_project_states_done),
                    "archived" to stringResource(DesignR.string.tasks_project_states_archived)), !state.busy, "tasks_project_state") { status = it }
            Button(onClick = { scope.launch {
                val previous = controller.state.value?.receipt?.operationId
                if (project == null) controller.createProject(name.trim(), outcome.trim(), space.takeIf(String::isNotBlank))
                else controller.updateProject(project, name.trim(), outcome.trim(), status)
                val after = controller.state.value
                if (after?.receipt?.operationId != previous && after?.error == null && after?.pending?.isEmpty() == true) onDismiss()
            } }, enabled = !state.busy && state.pending.isEmpty() && name.isNotBlank(),
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("tasks_project_save")) {
                Text(stringResource(if (project == null) DesignR.string.tasks_create_project else DesignR.string.common_save))
            }
            TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(DesignR.string.common_close)) }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TaskMoveSheet(state: TasksState, task: Task, controller: TasksController, onDismiss: () -> Unit, onOpen: () -> Unit) {
    val scope = LocalTaskActionScope.current
    var waiting by remember(task.id) { mutableStateOf(false) }
    var reason by remember(task.id) { mutableStateOf(task.waitingReason.orEmpty()) }
    val column = state.board.data?.columns?.get(task.state)
    val siblings = column?.items.orEmpty().filter { it.listId == task.listId && it.parentId == task.parentId }
    val index = siblings.indexOfFirst { it.id == task.id }
    fun move(next: TaskState, waitingReason: String? = null) {
        onDismiss()
        scope.launch { controller.setTaskState(task, next, waitingReason) }
    }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).imePadding().padding(20.dp).testTag("task_move_sheet"),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            TaskHeading(stringResource(if (waiting) DesignR.string.tasks_board_waiting_title else DesignR.string.tasks_board_move_to))
            Text(task.title)
            if (waiting) {
                OutlinedTextField(value = reason, onValueChange = { reason = it.take(2000) },
                    label = { Text(stringResource(DesignR.string.tasks_waiting_reason)) }, modifier = Modifier.fillMaxWidth().testTag("task_move_waiting_reason"))
                Button(onClick = { move(TaskState.Waiting, reason) }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("task_move_waiting_save")) {
                    Text(stringResource(DesignR.string.tasks_board_move_waiting))
                }
            } else {
                TaskState.entries.forEach { next -> OutlinedButton(onClick = {
                    if (next == TaskState.Waiting) waiting = true else move(next)
                }, enabled = next != task.state && !state.busy,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("task_move_state_${next.wire}")) {
                    Text(taskStateLabel(next) + " · " + (state.board.data?.columns?.get(next)?.total ?: 0))
                } }
                if (task.state != TaskState.Done) when {
                    state.selection.filters.narrowed -> Text(stringResource(DesignR.string.tasks_queue_filtered))
                    state.selection.sort != TaskSort.Manual -> OutlinedButton(onClick = {
                        onDismiss(); scope.launch { controller.select(state.selection.copy(sort = TaskSort.Manual)) }
                    }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("task_queue_manual")) {
                        Text(stringResource(DesignR.string.tasks_queue_use_manual))
                    }
                    else -> {
                        TaskHeading(stringResource(DesignR.string.tasks_queue_label))
                        val targets = listOf("top" to siblings.firstOrNull()?.id, "up" to siblings.getOrNull(index - 1)?.id,
                            "down" to siblings.getOrNull(index + 2)?.id, "end" to null)
                        targets.forEach { (command, before) ->
                            val enabled = when (command) {
                                "top", "up" -> index > 0
                                "down" -> index >= 0 && (index < siblings.lastIndex || column?.nextCursor != null)
                                else -> index >= 0
                            }
                            OutlinedButton(onClick = {
                                if (command == "down" && before == null && column?.nextCursor != null) {
                                    scope.launch { controller.loadMoreBoard(task.state) }
                                } else { onDismiss(); scope.launch { controller.reorderTask(task, before) } }
                            }, enabled = enabled && !state.busy, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("task_queue_$command")) {
                                Text(stringResource(when (command) {
                                    "top" -> DesignR.string.tasks_queue_top
                                    "up" -> DesignR.string.tasks_queue_up
                                    "down" -> DesignR.string.tasks_queue_down
                                    else -> DesignR.string.tasks_queue_end
                                }))
                            }
                        }
                        if (column?.nextCursor != null) Text(stringResource(DesignR.string.tasks_queue_partial_tail), style = MaterialTheme.typography.bodySmall)
                    }
                }
                Button(onClick = onOpen, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("task_move_open")) {
                    Text(stringResource(DesignR.string.tasks_board_open))
                }
            }
            TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(DesignR.string.common_close)) }
        }
    }
}
