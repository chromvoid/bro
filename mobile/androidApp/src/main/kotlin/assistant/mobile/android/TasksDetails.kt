package assistant.mobile.android

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import assistant.mobile.core.GeneratedStrings
import assistant.mobile.design.ui.ActionIconButton
import assistant.mobile.design.ui.AppCardSurface
import assistant.mobile.design.ui.ProgressBar
import assistant.mobile.design.ui.ScreenTabs
import assistant.mobile.design.ui.SelectChip
import assistant.mobile.design.ui.Spacing
import assistant.mobile.core.SessionLease
import assistant.mobile.core.photos.ImageAcquisition
import assistant.mobile.core.photos.PhotoNormalizer
import assistant.mobile.core.photos.PrivateConversationImages
import assistant.mobile.core.tasks.*
import assistant.mobile.core.timers.TimersController
import assistant.mobile.design.R as DesignR
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.launch

/** Only form fields live in the journal draft; server task data remains the revision authority. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TaskDetailScreen(
    controller: TasksController,
    assistantController: TaskAssistantController,
    timersController: TimersController,
    lease: SessionLease,
    images: PrivateConversationImages,
    normalizer: PhotoNormalizer,
    acquisition: ImageAcquisition?,
    export: FileExportBridge,
    taskId: String? = null,
    initialListId: String? = null,
    initialState: TaskState = TaskState.Todo,
    initialTab: String = "details",
    onOpenTask: (String, String) -> Unit,
    onClose: () -> Unit,
) {
    val observed by controller.state.collectAsStateWithLifecycle()
    val state = observed?.takeIf { it.namespace == lease.namespace }
    val scope = rememberCoroutineScope()
    LaunchedEffect(controller, lease, taskId, initialListId, initialState) {
        controller.activate()
        if (taskId != null) controller.openTask(taskId)
        else if (controller.state.value?.draft?.let { it.taskId == null } != true) {
            controller.beginCreate(initialListId)
            if (initialState != TaskState.Todo) controller.state.value?.draft?.let {
                controller.updateDraft(it.copy(fields = it.fields.copy(state = initialState)))
            }
        }
    }
    val task = state?.task?.data
    val draft = state?.draft
    val taskKey = task?.id ?: taskId ?: "new"
    val fields = draft?.fields ?: task?.let(::fieldsOf) ?: TaskFields()
    val canWrite = state?.canWrite == true && (task?.let(controller::canWrite) ?:
        (state.lists.data.orEmpty().find { it.id == draft?.listId }?.writable ?: (draft?.listId == null)))
    val trashed = task?.deletedAt != null
    val editable = canWrite && !trashed && state?.busy == false && state.pending.isEmpty()
    val dirty = draft != null && draft.fields != draft.base
    var waitingAsk by remember(taskKey) { mutableStateOf(false) }
    var waitingReason by remember(taskKey) { mutableStateOf(task?.waitingReason.orEmpty()) }
    val tabs = if (task == null) listOf("details") else if (trashed) listOf("details", "history")
        else listOf("details", "assistant", "discussion", "history")
    var selectedTab by remember(taskId, initialListId, initialTab) { mutableStateOf(initialTab) }
    val tab = selectedTab.takeIf { it in tabs } ?: "details"
    var menu by remember(taskKey) { mutableStateOf(false) }
    var transfer by remember(taskKey) { mutableStateOf(false) }
    var destination by remember(taskKey) { mutableStateOf("") }
    var transferHistory by remember(taskKey) { mutableStateOf(false) }
    var imageGeneration by remember(taskKey) { mutableStateOf(0L) }
    var imageField by remember(taskKey) { mutableStateOf<String?>(null) }
    var photoUploading by remember(taskKey) { mutableStateOf(false) }
    var photoError by remember(taskKey) { mutableStateOf(false) }
    var preview by remember(taskKey) { mutableStateOf<String?>(null) }
    val loaded = if (state?.selection?.layout == TaskLayout.Board) task?.let { state.board.data?.columns?.get(it.state)?.items }.orEmpty()
        else state?.page?.data?.items.orEmpty()
    val neighbours = loaded
    val position = neighbours.indexOfFirst { it.id == task?.id }
    val previous = neighbours.getOrNull(position - 1).takeIf { position > 0 }
    val next = neighbours.getOrNull(position + 1).takeIf { position >= 0 }
    val scroll = rememberScrollState()
    LaunchedEffect(taskKey) { scroll.scrollTo(0) }
    val destinations = state?.lists?.data.orEmpty().filter { it.id != task?.listId && it.writable }
    fun update(next: TaskFields) { draft?.let { controller.updateDraft(it.copy(fields = next)) } }
    fun save() { scope.launch {
        val receipt = controller.submitEdit()
        if (receipt != null && controller.state.value?.let { it.task.data == null && it.draft == null } == true) onClose()
    } }
    CompositionLocalProvider(LocalTaskActionScope provides scope) {
        Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).testTag("tasks_detail_" + taskKey)) {
            TopAppBar(
                title = { Text(stringResource(if (taskId == null && task == null) DesignR.string.tasks_quick_add else DesignR.string.tasks_detail),
                    style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() }.testTag("screen_title")) },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background,
                    scrolledContainerColor = MaterialTheme.colorScheme.background),
                navigationIcon = { ActionIconButton(DesignR.drawable.lucide_arrow_left, stringResource(DesignR.string.common_back),
                    onClick = onClose, testTag = "task_close") },
                actions = {
                    if (task != null) {
                        ActionIconButton(DesignR.drawable.lucide_chevron_left, stringResource(DesignR.string.tasks_previous_task),
                            onClick = { previous?.let { onOpenTask(it.id, tab) } },
                            enabled = previous != null && !dirty && state?.busy == false && !photoUploading, testTag = "task_previous")
                        ActionIconButton(DesignR.drawable.lucide_chevron_right, stringResource(DesignR.string.tasks_next_task),
                            onClick = { next?.let { onOpenTask(it.id, tab) } },
                            enabled = next != null && !dirty && state?.busy == false && !photoUploading, testTag = "task_next")
                        if (canWrite && trashed) ActionIconButton(DesignR.drawable.lucide_rotate_ccw_clock,
                            stringResource(DesignR.string.tasks_restore), onClick = { scope.launch { controller.transition(task, "task.restore") } },
                            enabled = state?.busy == false, testTag = "task_restore")
                        else if (canWrite) Box {
                            ActionIconButton(DesignR.drawable.lucide_ellipsis, stringResource(DesignR.string.common_more),
                                onClick = { menu = true }, enabled = editable && !dirty, testTag = "task_more")
                            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                                if (destinations.isNotEmpty()) DropdownMenuItem(
                                    text = { Text(stringResource(DesignR.string.tasks_move_to_project)) },
                                    onClick = { menu = false; transfer = true }, modifier = Modifier.testTag("task_transfer"))
                                DropdownMenuItem(text = { Text(stringResource(DesignR.string.tasks_to_trash), color = MaterialTheme.colorScheme.error) },
                                    onClick = { menu = false; scope.launch { controller.transition(task, "task.delete") } },
                                    modifier = Modifier.testTag("task_delete"))
                            }
                        }
                    }
                },
            )
            Box(Modifier.weight(1f).fillMaxWidth()) {
                Column(Modifier.fillMaxSize().verticalScroll(scroll).imePadding().padding(Spacing.l)
                    .padding(bottom = 64.dp), verticalArrangement = Arrangement.spacedBy(Spacing.m)) {
                    if (state == null) StatusMessage(GeneratedStrings.State.loading, "tasks_loading_detail")
                    else {
                        if (state.busy) ProgressBar(modifier = Modifier.fillMaxWidth().testTag("task_busy"))
                        TaskQueryStatus(state.task, "detail") { scope.launch { controller.refreshSelected() } }
                        state.error?.let { ErrorMessage(it, "task_error") }
                        TasksPending(state, controller, onChecked = {
                            if (controller.state.value?.let { it.task.data == null && it.draft == null } == true) onClose()
                        })
                        task?.let { current ->
                            current.parentId?.let { parent ->
                                OutlinedButton(onClick = { onOpenTask(parent, tab) }, enabled = !state.busy && !dirty,
                                    modifier = Modifier.testTag("task_parent")) {
                                    Text(stringResource(DesignR.string.tasks_parent, current.parent?.title ?: stringResource(DesignR.string.tasks_detail)))
                                }
                                if (current.status == "open" && current.parent?.deletedAt != null) Text(stringResource(DesignR.string.tasks_parent_deleted))
                                else if (current.status == "open" && current.parent?.state == TaskState.Done) Text(stringResource(DesignR.string.tasks_parent_completed))
                                if (editable) OutlinedButton(onClick = { scope.launch { controller.detachTask(current) } }, enabled = !dirty,
                                    modifier = Modifier.testTag("task_detach")) { Text(stringResource(DesignR.string.tasks_detach)) }
                            }
                            if (!trashed && canWrite) {
                                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).alpha(if (editable && !dirty) 1f else 0.38f),
                                    horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
                                    TaskState.entries.forEach { value ->
                                        val enabled = editable && !dirty && value != current.state
                                        SelectChip(selected = current.state == value, label = taskStateLabel(value), onClick = {
                                            if (enabled) {
                                                if (value == TaskState.Waiting) { waitingReason = current.waitingReason.orEmpty(); waitingAsk = true }
                                                else scope.launch { controller.setTaskState(current, value) }
                                            }
                                        }, testTag = "task_state_" + value.wire, modifier = Modifier.semantics { if (!enabled) disabled() })
                                    }
                                }
                                if (dirty) Text(stringResource(DesignR.string.tasks_save_before_state), style = MaterialTheme.typography.bodySmall)
                            } else Text(if (trashed) stringResource(DesignR.string.tasks_status_trash) else taskStateLabel(current.state))
                            if (dirty && (previous != null || next != null)) Text(stringResource(DesignR.string.tasks_save_before_navigation),
                                style = MaterialTheme.typography.bodySmall)
                            ScreenTabs(tabs.map { name -> stringResource(when (name) {
                                "assistant" -> DesignR.string.tasks_tabs_assistant
                                "discussion" -> DesignR.string.tasks_tabs_discussion
                                "history" -> DesignR.string.tasks_tabs_history
                                else -> DesignR.string.tasks_tabs_details
                            }) }, selected = tabs.indexOf(tab), onSelect = { selectedTab = tabs[it] }, tabTestTag = { "task_tab_" + tabs[it] })
                        }
                        when (tab) {
                            "assistant" -> task?.let { TaskAssistantSection(it.id, assistantController) }
                            "discussion" -> task?.let { TaskDiscussionSection(state, controller, lease) }
                            "history" -> task?.let { TaskHistorySection(state, controller) }
                            else -> {
                                if (state.conflict) TaskSection("task_conflict") {
                                    TaskHeading(stringResource(DesignR.string.tasks_conflict_title))
                                    Text(stringResource(DesignR.string.tasks_conflict_text))
                                    task?.let { TaskConflictComparison(draft, it, state) }
                                    Button(onClick = ::save, enabled = editable && !photoUploading,
                                        modifier = Modifier.fillMaxWidth().testTag("task_conflict_apply")) { Text(stringResource(DesignR.string.tasks_apply)) }
                                    OutlinedButton(onClick = { scope.launch { controller.dismissEdit() } }, enabled = !state.busy,
                                        modifier = Modifier.testTag("task_conflict_discard")) { Text(stringResource(DesignR.string.tasks_discard)) }
                                }
                                if (draft != null || task != null) {
                                    OutlinedTextField(value = fields.title, onValueChange = { update(fields.copy(title = it.take(300))) }, enabled = editable,
                                        label = { Text(stringResource(DesignR.string.tasks_name)) }, trailingIcon = {
                                            TaskImageButton("title", editable && !photoUploading && fields.titleImages.size < 4) {
                                                imageGeneration = controller.draftGeneration; imageField = "title_images"
                                            }
                                        }, modifier = Modifier.fillMaxWidth().testTag("task_title"))
                                    TaskFieldPhotos(task?.id, fields.titleImages, images, editable && !photoUploading,
                                        "title", { controller.removeImage(it, "title_images") }, { preview = it })
                                    OutlinedTextField(value = fields.description, onValueChange = { update(fields.copy(description = it.take(10000))) }, enabled = editable,
                                        minLines = 2, label = { Text(stringResource(DesignR.string.tasks_description)) }, trailingIcon = {
                                            TaskImageButton("description", editable && !photoUploading && fields.descriptionImages.size < 20) {
                                                imageGeneration = controller.draftGeneration; imageField = "description_images"
                                            }
                                        }, modifier = Modifier.fillMaxWidth().testTag("task_description"))
                                    TaskFieldPhotos(task?.id, fields.descriptionImages, images, editable && !photoUploading,
                                        "description", { controller.removeImage(it, "description_images") }, { preview = it })
                                    if (photoUploading) {
                                        ProgressBar(modifier = Modifier.fillMaxWidth().testTag("task_photo_uploading"))
                                        Text(stringResource(DesignR.string.tasks_images_uploading))
                                    }
                                    if (photoError) Text(stringResource(DesignR.string.tasks_images_unavailable), color = MaterialTheme.colorScheme.error,
                                        modifier = Modifier.testTag("task_photo_error"))
                                    if (task != null && !trashed) TaskStepsSection(state, controller, task, editable && !dirty && !photoUploading, images) { id, discussion ->
                                        onOpenTask(id, if (discussion) "discussion" else "details")
                                    }
                                    TaskOptionPicker(stringResource(DesignR.string.tasks_priority), fields.priority.toString(),
                                        listOf("1" to stringResource(DesignR.string.tasks_priorities_1), "2" to stringResource(DesignR.string.tasks_priorities_2),
                                            "3" to stringResource(DesignR.string.tasks_priorities_3)), editable, "task_priority") { update(fields.copy(priority = it.toInt())) }
                                    if (fields.state == TaskState.Waiting) OutlinedTextField(value = fields.waitingReason,
                                        onValueChange = { update(fields.copy(waitingReason = it.take(2000))) }, enabled = editable,
                                        label = { Text(stringResource(DesignR.string.tasks_waiting_reason)) }, modifier = Modifier.fillMaxWidth().testTag("task_waiting_reason"))
                                    val taskList = state.lists.data.orEmpty().find { it.id == (task?.listId ?: draft?.listId) }
                                    if (taskList?.spaceId != null) {
                                        TaskQueryStatus(state.members, "members") { scope.launch { controller.refreshSelected() } }
                                        TaskOptionPicker(stringResource(DesignR.string.circle_assignee), fields.assignedTo,
                                            listOf("" to stringResource(DesignR.string.circle_no_assignee)) + state.members.data.orEmpty().map { it.userId to it.name },
                                            editable, "task_assignee") { update(fields.copy(assignedTo = it)) }
                                    }
                                    TaskDateField(DesignR.string.tasks_planned_on, fields.plannedOn, editable, "task_planned_on") { update(fields.copy(plannedOn = it)) }
                                    TaskDateField(DesignR.string.tasks_due_on, fields.dueOn, editable, "task_due_on") { update(fields.copy(dueOn = it)) }
                                    if (fields.plannedOn.isNotBlank() && fields.dueOn.isNotBlank() && fields.plannedOn > fields.dueOn)
                                        Text(stringResource(DesignR.string.tasks_planned_after_due), color = MaterialTheme.colorScheme.error)
                                    if (trashed) Text(stringResource(DesignR.string.tasks_trash_hint))
                                    if (task != null) {
                                        TaskRemindersSection(task, timersController, lease)
                                        task.updatedAt?.let { Text(stringResource(DesignR.string.tasks_updated, taskTimestamp(it,
                                            state.page.data?.timezone ?: state.board.data?.timezone ?: "UTC")),
                                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                                    }
                                    if (!trashed && canWrite && !state.conflict && (dirty || task == null)) {
                                        Button(onClick = ::save, enabled = editable && !photoUploading && fields.title.isNotBlank(),
                                            modifier = Modifier.fillMaxWidth().testTag("task_save")) { Text(stringResource(if (task == null) DesignR.string.tasks_add else DesignR.string.tasks_save)) }
                                        TextButton(onClick = { scope.launch { controller.dismissEdit(); if (task == null) onClose() } }, enabled = !state.busy,
                                            modifier = Modifier.testTag("task_discard")) { Text(stringResource(DesignR.string.common_cancel)) }
                                    }
                                }
                            }
                        }
                    }
                }
                TaskUndoSnackbar(state, controller, lease, Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(Spacing.l))
            }
        }
        state?.let { TaskCompletionPrompt(it, controller) { id -> onOpenTask(id, tab) } }
    }
    if (transfer && task != null) AlertDialog(onDismissRequest = { transfer = false },
        title = { Text(stringResource(DesignR.string.tasks_move_to_project)) },
        text = { Column(verticalArrangement = Arrangement.spacedBy(Spacing.m)) {
            TaskOptionPicker(stringResource(DesignR.string.tasks_project), destination,
                listOf("" to stringResource(DesignR.string.tasks_choose_project)) + destinations.map { it.id to it.name },
                editable && !dirty, "task_destination") { destination = it }
            TaskCheck(transferHistory, DesignR.string.tasks_move_history_warning, "task_transfer_history") { transferHistory = it }
        } },
        confirmButton = { OutlinedButton(onClick = { scope.launch {
            if (controller.moveTask(task, destination) != null) transfer = false
        } }, enabled = editable && !dirty && destination.isNotBlank() && transferHistory,
            modifier = Modifier.testTag("task_transfer_save")) { Text(stringResource(DesignR.string.tasks_move_to_project)) } },
        dismissButton = { TextButton(onClick = { transfer = false }) { Text(stringResource(DesignR.string.common_cancel)) } })
    if (waitingAsk && task != null) AlertDialog(onDismissRequest = { waitingAsk = false },
        title = { Text(stringResource(DesignR.string.tasks_board_waiting_title)) }, text = {
            OutlinedTextField(value = waitingReason, onValueChange = { waitingReason = it.take(2000) },
                label = { Text(stringResource(DesignR.string.tasks_waiting_reason)) }, modifier = Modifier.testTag("task_waiting_dialog_reason"))
        }, confirmButton = { TextButton(onClick = { waitingAsk = false; scope.launch { controller.setTaskState(task, TaskState.Waiting, waitingReason) } },
            modifier = Modifier.heightIn(min = 48.dp).testTag("task_waiting_dialog_save")) { Text(stringResource(DesignR.string.tasks_board_move_waiting)) } },
        dismissButton = { TextButton(onClick = { waitingAsk = false }) { Text(stringResource(DesignR.string.common_cancel)) } })
    if (imageField != null) {
        val capturedField = imageField!!
        val capturedGeneration = imageGeneration
        PhotoAcquisitionSheet(acquisition = acquisition, onDismiss = { imageField = null }, onChosen = { source ->
            imageField = null
            scope.launch {
                photoUploading = true; photoError = false
                try {
                    val photo = normalizer.normalize(source)
                    val id = images.upload(photo, lease) { _, _ -> }
                    // A late picker from a closed or different task has no authority over its draft.
                    if (controller.draftGeneration == capturedGeneration && controller.state.value?.draft != null) {
                        controller.appendImage(TaskImage(id, photo.width, photo.height), capturedField)
                    }
                } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled
                } catch (_: Exception) { photoError = true
                } finally { photoUploading = false }
            }
        })
    }
    if (preview != null) TaskPhotoViewer(task?.id, preview!!, images, export) { preview = null }
}

@Composable
private fun TaskStepsSection(state: TasksState, controller: TasksController, task: Task, enabled: Boolean,
    images: PrivateConversationImages, onOpen: (String, Boolean) -> Unit) {
    val scope = LocalTaskActionScope.current
    var step by remember(task.id) { mutableStateOf("") }
    var child by remember(task.id) { mutableStateOf("") }
    HorizontalDivider()
    TaskHeading(stringResource(DesignR.string.tasks_checklist))
    task.checklist.forEach { item ->
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = item.done, onCheckedChange = { done -> scope.launch { controller.toggleStep(task, item.id, done) } },
                enabled = enabled, modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp).testTag("task_step_${item.id}"))
            Text(item.text, modifier = Modifier.weight(1f))
            if (enabled) ActionIconButton(DesignR.drawable.lucide_x, stringResource(DesignR.string.tasks_remove_step),
                onClick = { scope.launch { controller.removeStep(task, item.id) } }, testTag = "task_remove_step_${item.id}")
        }
    }
    if (enabled) {
        OutlinedTextField(value = step, onValueChange = { step = it.take(1000) }, label = { Text(stringResource(DesignR.string.tasks_new_step)) },
            modifier = Modifier.fillMaxWidth().testTag("task_new_step"))
        OutlinedButton(onClick = { scope.launch {
            controller.addStep(task, step.trim())
            if (controller.state.value?.error == null && controller.state.value?.pending?.isEmpty() == true) step = ""
        } }, enabled = step.isNotBlank(), modifier = Modifier.heightIn(min = 48.dp).testTag("task_add_step")) { Text(stringResource(DesignR.string.tasks_add)) }
    }
    if (task.parentId == null) {
        TaskHeading(stringResource(DesignR.string.tasks_subtasks))
        TaskQueryStatus(state.children, "children") { scope.launch { controller.refreshSelected() } }
        val children = state.children.data?.items.orEmpty()
        if (children.isNotEmpty()) AppCardSurface(Modifier.fillMaxWidth()) {
            children.forEachIndexed { index, item ->
                if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                TaskRow(item, state.copy(canWrite = controller.canWrite(item)),
                    onOpen = { onOpen(item.id, false) }, onDiscussion = { onOpen(item.id, true) },
                    onToggle = { scope.launch { controller.transition(item, if (item.state == TaskState.Done) "task.reopen" else "task.complete") } },
                    today = state.children.data?.today, images = images)
            }
        }
        if (state.children.data?.nextCursor != null) OutlinedButton(onClick = { scope.launch { controller.loadMoreChildren() } },
            enabled = !state.children.loading, modifier = Modifier.heightIn(min = 48.dp).testTag("task_subtasks_more")) { Text(stringResource(DesignR.string.tasks_more)) }
        if (enabled && task.status == "done") Text(stringResource(DesignR.string.tasks_reopen_before_subtask))
        else if (enabled) {
            OutlinedTextField(value = child, onValueChange = { child = it.take(300) },
                label = { Text(stringResource(DesignR.string.tasks_new_subtask)) }, modifier = Modifier.fillMaxWidth().testTag("task_new_subtask"))
            OutlinedButton(onClick = { scope.launch {
                controller.createSubtask(task, child.trim())
                if (controller.state.value?.error == null && controller.state.value?.pending?.isEmpty() == true) child = ""
            } }, enabled = child.isNotBlank(), modifier = Modifier.heightIn(min = 48.dp).testTag("task_add_subtask")) { Text(stringResource(DesignR.string.tasks_add)) }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TaskOptionPicker(label: String, value: String, choices: List<Pair<String, String>>, enabled: Boolean,
    tag: String, onSelect: (String) -> Unit) {
    var open by remember(tag) { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = open && enabled, onExpandedChange = { if (enabled) open = it }) {
        OutlinedTextField(value = choices.find { it.first == value }?.second.orEmpty(), onValueChange = {},
            readOnly = true, enabled = enabled, label = { Text(label) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = open && enabled) },
            modifier = Modifier.fillMaxWidth().menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable, enabled).testTag(tag))
        ExposedDropdownMenu(expanded = open && enabled, onDismissRequest = { open = false }) {
            choices.forEach { (id, name) -> DropdownMenuItem(text = { Text(name) },
                onClick = { onSelect(id); open = false }, modifier = Modifier.testTag(tag + "_" + id)) }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TaskDateField(label: Int, value: String, enabled: Boolean, tag: String, onChange: (String) -> Unit) {
    var open by remember(tag) { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(stringResource(label), style = MaterialTheme.typography.labelLarge)
        OutlinedButton(onClick = { open = true }, enabled = enabled,
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag(tag)) {
            Text(if (value.isBlank()) stringResource(DesignR.string.tasks_no_date) else taskDayLabel(value))
        }
    }
    if (open) {
        val picker = rememberDatePickerState(initialSelectedDateMillis = runCatching {
            LocalDate.parse(value).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        }.getOrNull())
        DatePickerDialog(onDismissRequest = { open = false }, confirmButton = {
            TextButton(onClick = { picker.selectedDateMillis?.let {
                onChange(Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate().toString())
            }; open = false }) { Text(stringResource(DesignR.string.common_save)) }
        }, dismissButton = {
            Row {
                TextButton(onClick = { onChange(""); open = false }) { Text(stringResource(DesignR.string.tasks_no_date)) }
                TextButton(onClick = { open = false }) { Text(stringResource(DesignR.string.common_cancel)) }
            }
        }) { DatePicker(picker) }
    }
}

@Composable
private fun TaskConflictComparison(draft: TaskEditDraft?, task: Task, state: TasksState) {
    if (draft == null) return
    val latest = fieldsOf(task)
    val fields = draft.fields
    val base = draft.base
    val changed = buildList {
        if (fields.title != base.title) add(Triple(DesignR.string.tasks_name, latest.title, fields.title))
        if (fields.description != base.description) add(Triple(DesignR.string.tasks_description, latest.description, fields.description))
        if (fields.plannedOn != base.plannedOn) add(Triple(DesignR.string.tasks_planned_on, latest.plannedOn, fields.plannedOn))
        if (fields.dueOn != base.dueOn) add(Triple(DesignR.string.tasks_due_on, latest.dueOn, fields.dueOn))
        if (fields.waitingReason != base.waitingReason) add(Triple(DesignR.string.tasks_waiting_reason, latest.waitingReason, fields.waitingReason))
    }
    changed.forEach { (label, server, yours) ->
        Text(stringResource(label), style = MaterialTheme.typography.labelLarge)
        Text(stringResource(DesignR.string.tasks_on_server) + ": " + server.ifBlank { "—" })
        Text(stringResource(DesignR.string.tasks_yours) + ": " + yours.ifBlank { "—" })
    }
    if (fields.state != base.state) {
        Text(stringResource(DesignR.string.tasks_state))
        Text(stringResource(DesignR.string.tasks_on_server) + ": " + taskStateLabel(latest.state))
        Text(stringResource(DesignR.string.tasks_yours) + ": " + taskStateLabel(fields.state))
    }
    if (fields.priority != base.priority) {
        Text(stringResource(DesignR.string.tasks_priority))
        Text(stringResource(DesignR.string.tasks_on_server) + ": " + taskPriorityLabel(latest.priority))
        Text(stringResource(DesignR.string.tasks_yours) + ": " + taskPriorityLabel(fields.priority))
    }
    if (fields.assignedTo != base.assignedTo) {
        fun name(id: String) = state.members.data.orEmpty().find { it.userId == id }?.name
        Text(stringResource(DesignR.string.circle_assignee))
        Text(stringResource(DesignR.string.tasks_on_server) + ": " + (name(latest.assignedTo) ?: stringResource(DesignR.string.circle_no_assignee)))
        Text(stringResource(DesignR.string.tasks_yours) + ": " + (name(fields.assignedTo) ?: stringResource(DesignR.string.circle_no_assignee)))
    }
    if (fields.titleImages != base.titleImages || fields.descriptionImages != base.descriptionImages) {
        Text(stringResource(DesignR.string.tasks_on_server) + ": " + (latest.titleImages.size + latest.descriptionImages.size))
        Text(stringResource(DesignR.string.tasks_yours) + ": " + (fields.titleImages.size + fields.descriptionImages.size))
    }
}

@Composable
private fun taskPriorityLabel(priority: Int) = stringResource(when (priority) {
    1 -> DesignR.string.tasks_priorities_1
    3 -> DesignR.string.tasks_priorities_3
    else -> DesignR.string.tasks_priorities_2
})

@Composable
internal fun TaskCompletionPrompt(state: TasksState, controller: TasksController, onOpenTask: (String) -> Unit) {
    val completion = state.completion ?: return
    val scope = LocalTaskActionScope.current
    AlertDialog(onDismissRequest = controller::cancelCompletion,
        title = { Text(stringResource(DesignR.string.tasks_completion_title)) },
        text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(when (completion.reason) {
                "open_subtasks" -> stringResource(DesignR.string.tasks_completion_open_subtasks, completion.openSubtasks.toString())
                "incomplete_checklist" -> stringResource(DesignR.string.tasks_completion_incomplete_checklist, completion.incompleteChecklist.toString())
                else -> stringResource(DesignR.string.tasks_completion_parent_closed)
            })
            if (completion.reason == "incomplete_checklist" && !completion.canConfirm) Text(stringResource(DesignR.string.tasks_completion_changed))
        } }, confirmButton = {
            TextButton(onClick = {
                if (completion.canConfirm) scope.launch { controller.confirmCompletion() }
                else onOpenTask(completion.parentId ?: completion.taskId)
            }, enabled = !state.busy, modifier = Modifier.heightIn(min = 48.dp).testTag("task_completion_confirm")) {
                Text(stringResource(if (completion.canConfirm) DesignR.string.tasks_completion_confirm else DesignR.string.tasks_completion_inspect))
            }
        }, dismissButton = { TextButton(onClick = controller::cancelCompletion, modifier = Modifier.heightIn(min = 48.dp)) {
            Text(stringResource(DesignR.string.tasks_completion_return))
        } }, modifier = Modifier.testTag("task_completion"))
}

internal fun taskDayLabel(day: String): String = runCatching {
    LocalDate.parse(day).format(DateTimeFormatter.ofPattern("d MMMM yyyy", Locale.forLanguageTag("ru-RU")))
}.getOrDefault(day)
internal fun taskTimestamp(stamp: String, zone: String): String = runCatching {
    Instant.parse(stamp).atZone(ZoneId.of(zone)).format(DateTimeFormatter.ofPattern("d MMMM yyyy HH:mm", Locale.forLanguageTag("ru-RU")))
}.getOrDefault(stamp)
