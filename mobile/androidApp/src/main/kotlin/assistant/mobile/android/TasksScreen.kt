package assistant.mobile.android

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import assistant.mobile.core.GeneratedStrings
import assistant.mobile.core.SessionLease
import assistant.mobile.core.photos.PrivateConversationImages
import assistant.mobile.core.tasks.*
import assistant.mobile.design.R as DesignR
import assistant.mobile.design.ui.ActionIconButton
import assistant.mobile.design.ui.AppCardSurface
import assistant.mobile.design.ui.ProgressBar
import assistant.mobile.design.ui.Spacing
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

internal val LocalTaskActionScope = staticCompositionLocalOf<CoroutineScope> { error("Tasks action scope missing") }

/** The workspace uses the same filters for a list, board and every continuation page. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TasksScreen(
    controller: TasksController,
    lease: SessionLease,
    images: PrivateConversationImages,
    onOpenTask: (String, String) -> Unit,
    onCreateTask: (String?, TaskState) -> Unit,
    initialListId: String? = null,
    modifier: Modifier = Modifier,
    onListAccess: ((String) -> Unit)? = null,
) {
    val observed by controller.state.collectAsStateWithLifecycle()
    val state = observed?.takeIf { it.namespace == lease.namespace }
    val scope = rememberCoroutineScope()
    var filtersOpen by remember(lease.namespace) { mutableStateOf(false) }
    var projectOpen by remember(lease.namespace) { mutableStateOf(false) }
    var editingProject by remember(lease.namespace) { mutableStateOf<TaskList?>(null) }
    var moving by remember(lease.namespace) { mutableStateOf<Task?>(null) }
    var listActivated by rememberSaveable(lease.namespace, initialListId) { mutableStateOf(false) }
    LaunchedEffect(initialListId) {
        if (!listActivated && initialListId != null) {
            controller.activate(initialListId)
            controller.refresh()
        }
        listActivated = true
    }
    val scroll = rememberScrollState()
    var searchOpen by remember(lease.namespace) { mutableStateOf(false) }
    var fabExpanded by remember { mutableStateOf(true) }
    val showSearch = searchOpen || state?.selection?.filters?.q?.isNotEmpty() == true
    val focusRequester = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    fun closeSearch() {
        searchOpen = false
        focusManager.clearFocus()
        keyboard?.hide()
        state?.selection?.let { selection ->
            if (selection.filters.q.isNotEmpty()) scope.launch {
                controller.select(selection.copy(filters = selection.filters.copy(q = "")))
            }
        }
    }
    BackHandler(enabled = showSearch) { closeSearch() }
    LaunchedEffect(scroll) {
        var previous = scroll.value
        snapshotFlow { scroll.value }.collect { position ->
            fabExpanded = position <= previous || position == scroll.maxValue
            previous = position
        }
    }
    fun openTask(task: Task, discussion: Boolean = false) {
        onOpenTask(task.id, if (discussion) "discussion" else "details")
    }
    CompositionLocalProvider(LocalTaskActionScope provides scope) {
    Column(modifier.fillMaxSize()) {
        NativeTopBar(stringResource(DesignR.string.tasks_title),
            onBack = if (showSearch) ({ closeSearch() }) else LocalNativeChrome.current.onBack,
            actions = {
                ActionIconButton(DesignR.drawable.lucide_search, stringResource(DesignR.string.tasks_search),
                    onClick = { if (showSearch) closeSearch() else searchOpen = true }, testTag = "tasks_search_toggle")
                val count = state?.selection?.filters?.activeCount ?: 0
                BadgedBox(badge = { if (count > 0) Badge { Text(count.toString()) } }) {
                    ActionIconButton(DesignR.drawable.lucide_sliders_horizontal,
                        stringResource(if (count > 0) DesignR.string.tasks_filters_action_count else DesignR.string.tasks_filters_action,
                            count.toString()), onClick = { filtersOpen = true }, testTag = "tasks_filters")
                }
            })
        if (showSearch) {
            LaunchedEffect(Unit) { focusRequester.requestFocus() }
            OutlinedTextField(value = state?.selection?.filters?.q.orEmpty(), onValueChange = { text ->
                state?.selection?.let { selection -> scope.launch {
                    controller.select(selection.copy(filters = selection.filters.copy(q = text.take(200))))
                } }
            }, label = { Text(stringResource(DesignR.string.tasks_search)) }, singleLine = true,
                trailingIcon = {
                    ActionIconButton(DesignR.drawable.lucide_x, stringResource(DesignR.string.common_close),
                        onClick = { closeSearch() }, testTag = "tasks_search_clear")
                }, modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.l)
                    .focusRequester(focusRequester).testTag("tasks_search"))
        }
        PullToRefreshBox(isRefreshing = state?.let { it.page.loading || it.board.loading } == true,
            onRefresh = { scope.launch { controller.refresh() } },
            modifier = Modifier.weight(1f).fillMaxWidth().testTag("tasks_pull_refresh")) {
            Column(Modifier.fillMaxSize().verticalScroll(scroll).padding(vertical = Spacing.l).testTag("screen_Tasks"),
                verticalArrangement = Arrangement.spacedBy(Spacing.s)) {
                if (state == null) Column(Modifier.padding(horizontal = Spacing.l)) {
                    StatusMessage(GeneratedStrings.State.loading, "tasks_loading")
                }
                state?.let { current ->
                    val selection = current.selection
                    val project = current.lists.data.orEmpty().find { it.id == selection.listId }
                    val isProject = project?.isProject == true
                    val workspaceCanWrite = current.canWrite && (project?.writable ?: (selection.listId == null))
                    if (current.lists.loading || current.lists.error != null || current.lists.stale) {
                        Column(Modifier.padding(horizontal = Spacing.l)) {
                            TaskQueryStatus(current.lists, "lists") { scope.launch { controller.refresh() } }
                        }
                    }
                    TaskPlaces(current, onSelect = { scope.launch { controller.select(it) } },
                        onNewProject = { editingProject = null; projectOpen = true },
                        onEditProject = { editingProject = it; projectOpen = true }, onListAccess = onListAccess)
                    if (isProject && project != null) {
                        Column(Modifier.padding(horizontal = Spacing.l), verticalArrangement = Arrangement.spacedBy(Spacing.s)) {
                            val progressDescription = stringResource(DesignR.string.tasks_project_progress,
                                project.done.toString(), project.total.toString())
                            Row(Modifier.fillMaxWidth().semantics(mergeDescendants = true) { contentDescription = progressDescription },
                                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
                                ProgressBar(progress = { if (project.total == 0) 0f else project.done.toFloat() / project.total },
                                    modifier = Modifier.weight(1f))
                                Text(stringResource(DesignR.string.tasks_project_progress_short, project.done.toString(), project.total.toString()),
                                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            project.outcome?.takeIf(String::isNotBlank)?.let {
                                Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                    if (selection.layout != TaskLayout.Board || !isProject) {
                        TaskViews(current) { scope.launch { controller.select(it) } }
                    }
                    Column(Modifier.fillMaxWidth().padding(horizontal = Spacing.l),
                        verticalArrangement = Arrangement.spacedBy(Spacing.s)) {
                        TaskFilterSummary(current) { scope.launch { controller.select(it) } }
                        TasksPending(current, controller)
                        current.error?.let { ErrorMessage(it, "tasks_error") }
                        if (!current.canRead) UnavailableCard()
                        else if (selection.layout == TaskLayout.Board && isProject) {
                            TaskQueryStatus(current.board, "board") { scope.launch { controller.refresh() } }
                            current.board.data?.let { board ->
                                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).testTag("tasks_board"),
                                    horizontalArrangement = Arrangement.spacedBy(Spacing.m)) {
                                    TaskState.entries.forEach { columnState ->
                                        val column = board.columns[columnState]
                                        Column(Modifier.width(300.dp).testTag("tasks_board_${columnState.wire}"),
                                            verticalArrangement = Arrangement.spacedBy(Spacing.s)) {
                                            TaskHeading(taskStateLabel(columnState) + " · " + (column?.total ?: 0))
                                            if (workspaceCanWrite) TextButton(onClick = {
                                                onCreateTask(selection.listId, columnState)
                                            }, modifier = Modifier.heightIn(min = 48.dp).testTag("tasks_board_add_${columnState.wire}")) {
                                                Text(stringResource(DesignR.string.tasks_board_add))
                                            }
                                            column?.items?.takeIf { it.isNotEmpty() }?.let { items ->
                                                AppCardSurface(Modifier.fillMaxWidth()) {
                                                    items.forEachIndexed { index, task ->
                                                        if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                                                        TaskRow(task, current.copy(canWrite = controller.canWrite(task)),
                                                            onOpen = { openTask(task) },
                                                            onToggle = { scope.launch { controller.transition(task, if (task.state == TaskState.Done) "task.reopen" else "task.complete") } },
                                                            onMove = { moving = task }, onDiscussion = { openTask(task, discussion = true) },
                                                            today = board.today, board = true, images = images)
                                                    }
                                                }
                                            }
                                            if (column?.items?.isEmpty() == true) Text(stringResource(
                                                if (selection.filters.narrowed) DesignR.string.tasks_filters_none_in_column
                                                else if (columnState == TaskState.Done) DesignR.string.tasks_board_none_done
                                                else DesignR.string.tasks_board_empty))
                                            if (column?.nextCursor != null) TextButton(onClick = { scope.launch { controller.loadMoreBoard(columnState) } },
                                                enabled = !current.board.loading, modifier = Modifier.heightIn(min = 48.dp).testTag("tasks_more_${columnState.wire}")) {
                                                Text(stringResource(DesignR.string.tasks_more))
                                            }
                                        }
                                    }
                                }
                            }
                        } else {
                            TaskQueryStatus(current.page, "page") { scope.launch { controller.refresh() } }
                            current.page.data?.let { page ->
                                if (page.items.isEmpty() && !current.page.loading &&
                                    (selection.filters.activeCount == 0 || selection.filters.q.isNotBlank())) {
                                    TaskEmptyState(selection, taskHasHiddenOpenTasks(selection, current.lists.data.orEmpty())) {
                                        scope.launch { controller.select(selection.copy(view = TaskView.Open)) }
                                    }
                                }
                                if (page.items.isNotEmpty()) AppCardSurface(Modifier.fillMaxWidth()) {
                                    page.items.forEachIndexed { index, task ->
                                        if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                                        TaskRow(task, current.copy(canWrite = controller.canWrite(task)),
                                            onOpen = { openTask(task) },
                                            onToggle = { scope.launch { controller.transition(task, if (task.state == TaskState.Done) "task.reopen" else "task.complete") } },
                                            onDiscussion = { openTask(task, discussion = true) }, today = page.today, images = images,
                                            onMoveUp = if (selection.listId != null && selection.sort == TaskSort.Manual && !selection.filters.narrowed)
                                                previousSibling(page.items, task)?.let { previous -> { scope.launch { controller.reorderTask(task, previous.id) }; Unit } }
                                                else null)
                                    }
                                }
                                if (page.nextCursor != null) TextButton(onClick = { scope.launch { controller.loadMore() } },
                                    enabled = !current.page.loading, modifier = Modifier.heightIn(min = 48.dp).testTag("tasks_more")) {
                                    Text(stringResource(DesignR.string.tasks_more))
                                }
                                Spacer(Modifier.height(96.dp))
                            }
                        }
                    }
                }
            }
            Column(Modifier.align(Alignment.BottomEnd).fillMaxWidth().padding(bottom = Spacing.l),
                horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(Spacing.s)) {
                TaskUndoSnackbar(state, controller, lease, Modifier.fillMaxWidth().padding(horizontal = Spacing.l))
                state?.let { current ->
                    val selection = current.selection
                    val list = current.lists.data.orEmpty().find { it.id == selection.listId }
                    if (current.canWrite && (list?.writable ?: (selection.listId == null)) &&
                        selection.layout != TaskLayout.Board && selection.view !in listOf(TaskView.Done, TaskView.Trash)) {
                        val addLabel = stringResource(DesignR.string.tasks_quick_add)
                        ExtendedFloatingActionButton(onClick = { onCreateTask(selection.listId, TaskState.Todo) }, expanded = fabExpanded,
                            icon = { Icon(painterResource(DesignR.drawable.lucide_plus), contentDescription = null) },
                            text = { Text(addLabel) },
                            modifier = Modifier.padding(horizontal = Spacing.l).testTag("tasks_create")
                                .semantics { contentDescription = addLabel })
                    }
                }
            }
        }
    }
    state?.let { current ->
        if (filtersOpen) TaskFiltersSheet(current, { filtersOpen = false }) { next -> scope.launch { controller.select(next) } }
        if (projectOpen) TaskProjectSheet(current, editingProject, controller) { projectOpen = false }
        moving?.let { task -> TaskMoveSheet(current, task, controller, onDismiss = { moving = null },
            onOpen = { moving = null; openTask(task) }) }
        TaskCompletionPrompt(current, controller) { id -> onOpenTask(id, "details") }
    }
    }
}

@Composable
internal fun TaskUndoSnackbar(state: TasksState?, controller: TasksController, lease: SessionLease,
    modifier: Modifier = Modifier) {
    val scope = rememberCoroutineScope()
    val snackbar = remember(lease.namespace) { SnackbarHostState() }
    var seenReceipt by remember(lease.namespace) { mutableStateOf(state?.receipt?.operationId) }
    val receipt = state?.receipt
    var undoRequested by remember(lease.namespace, receipt?.operationId) { mutableStateOf(false) }
    val toast = taskReceiptToast(receipt)
    val toastMessage = toast?.let { notice ->
        notice.state?.let { stringResource(notice.message, taskStateLabel(it)) } ?: stringResource(notice.message)
    }
    val undoLabel = stringResource(DesignR.string.common_undo)
    LaunchedEffect(receipt?.operationId) {
        if (receipt == null || receipt.operationId == seenReceipt) return@LaunchedEffect
        seenReceipt = receipt.operationId
        snackbar.currentSnackbarData?.dismiss()
        if (toastMessage != null && snackbar.showSnackbar(toastMessage, undoLabel,
                withDismissAction = true, duration = SnackbarDuration.Indefinite) == SnackbarResult.ActionPerformed &&
            controller.state.value?.receipt?.operationId == receipt.operationId) {
            scope.launch { controller.undo() }
        }
    }
    SnackbarHost(snackbar, modifier) { data ->
        Snackbar(action = {
            TextButton(onClick = {
                if (!undoRequested) {
                    undoRequested = true
                    data.performAction()
                }
            }, enabled = !undoRequested,
                modifier = Modifier.testTag("task_undo")) { Text(undoLabel) }
        }, dismissAction = {
            ActionIconButton(DesignR.drawable.lucide_x, stringResource(DesignR.string.common_close), data::dismiss)
        }, modifier = Modifier.testTag("tasks_notice")) { Text(data.visuals.message) }
    }
}

@Composable
private fun TaskEmptyState(selection: TaskSelection, hidden: Boolean, onShowOpen: () -> Unit) {
    val query = selection.filters.q
    Column(Modifier.fillMaxWidth().padding(vertical = Spacing.xl).testTag("tasks_empty"),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(Spacing.m)) {
        Icon(painterResource(DesignR.drawable.lucide_list_todo), contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(48.dp))
        TaskHeading(if (query.isNotBlank()) stringResource(DesignR.string.tasks_search_empty, query)
            else stringResource(taskEmptyLabel(selection.view)))
        if (query.isBlank()) {
            Text(stringResource(if (hidden) DesignR.string.tasks_hidden_by_view else DesignR.string.tasks_empty_text),
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (hidden) OutlinedButton(onClick = onShowOpen, modifier = Modifier.testTag("tasks_show_open")) {
            Text(stringResource(DesignR.string.tasks_show_open))
        }
    }
}

@Composable
internal fun TasksPending(state: TasksState, controller: TasksController, onChecked: () -> Unit = {}) {
    if (state.pending.isEmpty()) return
    val scope = LocalTaskActionScope.current
    TaskSection("tasks_pending") {
        Text(stringResource(DesignR.string.outcome_unknown_title))
        Text(stringResource(DesignR.string.outcome_unknown_text))
        state.pending.forEach { entry ->
            val operation = entry.endpoint == TASK_OPERATIONS
            TextButton(onClick = { scope.launch {
                if (operation) {
                    controller.check(entry.key)
                    onChecked()
                } else controller.refreshSelected()
            } },
                enabled = !state.busy, modifier = Modifier.heightIn(min = 48.dp).testTag("task_pending_check_${entry.key}")) {
                Text(stringResource(DesignR.string.outcome_check))
            }
            if (!operation) TextButton(onClick = { scope.launch { controller.resolveDiscussion(entry.key) } },
                enabled = !state.busy, modifier = Modifier.heightIn(min = 48.dp).testTag("task_pending_discard_${entry.key}")) {
                Text(stringResource(DesignR.string.circle_dont_send))
            }
        }
    }
}

@Composable
internal fun <T> TaskQueryStatus(query: TaskQuery<T>, tag: String, retry: () -> Unit) {
    if (query.loading) ProgressBar(modifier = Modifier.fillMaxWidth().testTag("tasks_loading_$tag"))
    query.error?.let {
        ErrorMessage(it, "tasks_error_$tag")
        TextButton(onClick = retry, enabled = !query.loading, modifier = Modifier.heightIn(min = 48.dp).testTag("tasks_retry_$tag")) {
            Text(stringResource(DesignR.string.common_retry))
        }
    }
    if (query.stale) StatusMessage(GeneratedStrings.State.stale, "tasks_stale_$tag")
}

@Composable
internal fun TaskHeading(text: String) = Text(text, style = MaterialTheme.typography.titleMedium,
    modifier = Modifier.semantics { heading() })

@Composable
internal fun TaskSection(tag: String, content: @Composable ColumnScope.() -> Unit) {
    OutlinedCard(Modifier.fillMaxWidth().testTag(tag)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp), content = content)
    }
}

@Composable
internal fun TaskCheck(checked: Boolean, label: Int, tag: String, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked, onCheckedChange = onChange, modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp).testTag(tag))
        Text(stringResource(label), modifier = Modifier.weight(1f))
    }
}

internal fun taskViewLabel(view: TaskView): Int = when (view) {
    TaskView.Today -> DesignR.string.tasks_views_today
    TaskView.Upcoming -> DesignR.string.tasks_views_upcoming
    TaskView.Open -> DesignR.string.tasks_views_open
    TaskView.Done -> DesignR.string.tasks_views_done
    TaskView.Trash -> DesignR.string.tasks_views_trash
    TaskView.All -> DesignR.string.tasks_views_all
}
internal fun taskEmptyLabel(view: TaskView): Int = when (view) {
    TaskView.Today -> DesignR.string.tasks_empty_today
    TaskView.Upcoming -> DesignR.string.tasks_empty_upcoming
    TaskView.Open -> DesignR.string.tasks_empty_open
    TaskView.Done -> DesignR.string.tasks_empty_done
    TaskView.Trash -> DesignR.string.tasks_empty_trash
    TaskView.All -> DesignR.string.tasks_empty_all
}
internal fun taskSortLabel(sort: TaskSort): Int = when (sort) {
    TaskSort.Priority -> DesignR.string.tasks_sorts_priority
    TaskSort.Due -> DesignR.string.tasks_sorts_due
    TaskSort.Created -> DesignR.string.tasks_sorts_created
    TaskSort.Manual -> DesignR.string.tasks_sorts_manual
}
@Composable
internal fun taskStateLabel(state: TaskState): String = stringResource(when (state) {
    TaskState.Todo -> DesignR.string.tasks_states_todo
    TaskState.InProgress -> DesignR.string.tasks_states_in_progress
    TaskState.Waiting -> DesignR.string.tasks_states_waiting
    TaskState.Done -> DesignR.string.tasks_states_done
})
