package assistant.mobile.android

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import assistant.mobile.core.tasks.TaskFilters
import assistant.mobile.core.tasks.TaskLayout
import assistant.mobile.core.tasks.TaskList
import assistant.mobile.core.tasks.TaskScope
import assistant.mobile.core.tasks.TaskSelection
import assistant.mobile.core.tasks.TaskSort
import assistant.mobile.core.tasks.TaskView
import assistant.mobile.core.tasks.TasksState
import assistant.mobile.design.ui.ActionIconButton
import assistant.mobile.design.ui.CardTitle
import assistant.mobile.design.ui.SegmentedChoice
import assistant.mobile.design.ui.SelectChip
import assistant.mobile.design.ui.Spacing
import assistant.mobile.design.R as DesignR

@Composable
internal fun TaskPlaces(
    state: TasksState,
    onSelect: (TaskSelection) -> Unit,
    onNewProject: () -> Unit,
    onEditProject: (TaskList) -> Unit,
    onListAccess: ((String) -> Unit)?,
) {
    val selection = state.selection
    val lists = state.lists.data.orEmpty()
    val currentList = lists.find { it.id == selection.listId }
    val scopes = if (lists.any { it.spaceId != null }) listOf(TaskScope.All, TaskScope.Assigned, TaskScope.Personal)
        else listOf(TaskScope.All, TaskScope.Personal)
    val own = lists.filter { it.personalUserId == null }
    val active = own.filter { it.isProject && it.projectState == "active" } + own.filter { !it.isProject }
    val finished = own.filter { it.isProject && it.projectState != "active" }
    var finishedOpen by remember { mutableStateOf(false) }
    val placesLabel = stringResource(DesignR.string.tasks_places_label)
    fun selectList(list: TaskList) = onSelect(selection.copy(
        listId = list.id, scope = TaskScope.All, view = TaskView.Open, filters = TaskFilters(),
        sort = if (list.isProject) TaskSort.Manual else TaskSort.Priority,
        layout = if (list.isProject) TaskLayout.Board else TaskLayout.List,
    ))
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Row(
            Modifier.weight(1f).horizontalScroll(rememberScrollState())
                .padding(start = Spacing.l, end = if (currentList == null) Spacing.l else Spacing.s)
                .semantics { contentDescription = placesLabel },
            horizontalArrangement = Arrangement.spacedBy(Spacing.s),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            scopes.forEach { scope ->
                SelectChip(
                    selected = selection.listId == null && selection.scope == scope,
                    label = stringResource(when (scope) {
                        TaskScope.All -> DesignR.string.tasks_places_all
                        TaskScope.Assigned -> DesignR.string.tasks_places_assigned
                        TaskScope.Personal -> DesignR.string.tasks_places_personal
                    }),
                    onClick = { onSelect(TaskSelection(scope = scope)) },
                    testTag = "tasks_scope_${scope.wire}",
                )
            }
            active.forEach { list ->
                SelectChip(selection.listId == list.id, list.name, { selectList(list) }, "tasks_list_${list.id}")
            }
            if (finished.isNotEmpty()) Box {
                FilterChip(
                    selected = finished.any { it.id == selection.listId },
                    onClick = { finishedOpen = true },
                    label = { Text(stringResource(DesignR.string.tasks_places_finished)) },
                    leadingIcon = { TaskControlIcon(DesignR.drawable.lucide_archive) },
                    modifier = Modifier.testTag("tasks_finished_projects"),
                )
                DropdownMenu(expanded = finishedOpen, onDismissRequest = { finishedOpen = false }) {
                    finished.forEach { list ->
                        DropdownMenuItem(
                            text = { Text(list.name) },
                            onClick = { finishedOpen = false; selectList(list) },
                            modifier = Modifier.testTag("tasks_list_${list.id}").semantics { selected = selection.listId == list.id },
                        )
                    }
                }
            }
            if (state.canWrite) FilterChip(
                selected = false,
                onClick = onNewProject,
                label = { Text(stringResource(DesignR.string.tasks_places_new_project)) },
                leadingIcon = { TaskControlIcon(DesignR.drawable.lucide_plus) },
                modifier = Modifier.testTag("tasks_new_project"),
            )
        }
        currentList?.let { list ->
            Row(Modifier.padding(end = Spacing.l), verticalAlignment = Alignment.CenterVertically) {
                if (list.isProject) {
                    val board = selection.layout == TaskLayout.Board
                    ActionIconButton(
                        icon = if (board) DesignR.drawable.lucide_list else DesignR.drawable.lucide_columns_3,
                        contentDescription = stringResource(if (board) DesignR.string.tasks_list_view else DesignR.string.tasks_board_view),
                        onClick = { onSelect(selection.copy(layout = if (board) TaskLayout.List else TaskLayout.Board, view = TaskView.Open)) },
                        testTag = if (board) "tasks_layout_list" else "tasks_layout_board",
                    )
                }
                TaskListActions(state, list, onSelect, onEditProject, onListAccess)
            }
        }
    }
}

@Composable
private fun TaskListActions(
    state: TasksState,
    list: TaskList,
    onSelect: (TaskSelection) -> Unit,
    onEditProject: (TaskList) -> Unit,
    onListAccess: ((String) -> Unit)?,
) {
    var expanded by remember(list.id) { mutableStateOf(false) }
    Box {
        ActionIconButton(
            icon = DesignR.drawable.lucide_ellipsis,
            contentDescription = stringResource(DesignR.string.tasks_list_actions),
            onClick = { expanded = true },
            testTag = "tasks_list_actions",
        )
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            TaskSortMenu(state.selection) { expanded = false; onSelect(it) }
            if (list.effective == "manage" && list.personalUserId == null) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                DropdownMenuItem(
                    text = { Text(stringResource(DesignR.string.tasks_project_settings)) },
                    onClick = { expanded = false; onEditProject(list) },
                    modifier = Modifier.testTag("tasks_project_settings"),
                )
            }
            if (list.spaceId != null && list.effective == "manage" && onListAccess != null) DropdownMenuItem(
                text = { Text(stringResource(DesignR.string.circle_list_access)) },
                onClick = { expanded = false; onListAccess(list.id) },
                modifier = Modifier.testTag("list_access"),
            )
        }
    }
}

@Composable
internal fun TaskViews(state: TasksState, onSelect: (TaskSelection) -> Unit) {
    val selection = state.selection
    val quickViews = listOf(TaskView.Today, TaskView.Upcoming, TaskView.Open)
    val quickLabels = mapOf(
        TaskView.Today to stringResource(DesignR.string.tasks_quick_views_today),
        TaskView.Upcoming to stringResource(DesignR.string.tasks_quick_views_upcoming),
        TaskView.Open to stringResource(DesignR.string.tasks_quick_views_open),
    )
    val otherView = selection.view.takeIf { it in listOf(TaskView.Done, TaskView.Trash) }
    var expanded by remember { mutableStateOf(false) }
    val choicesWidth = 288.dp * LocalDensity.current.fontScale
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = Spacing.l),
        horizontalArrangement = Arrangement.spacedBy(Spacing.s), verticalAlignment = Alignment.CenterVertically) {
        SegmentedChoice(
            options = quickViews,
            selected = selection.view.takeIf { it in quickViews },
            onSelect = { onSelect(selection.copy(view = it)) },
            label = { quickLabels.getValue(it) },
            optionTestTag = { "tasks_view_$it" },
            testTag = "tasks_views",
            modifier = Modifier.width(choicesWidth),
        )
        Box {
            val label = stringResource(otherView?.let(::taskViewLabel) ?: DesignR.string.tasks_more_views)
            val buttonContent: @Composable () -> Unit = {
                Text(label, maxLines = 1)
                TaskControlIcon(DesignR.drawable.lucide_chevron_down, Modifier.padding(start = Spacing.xs))
            }
            if (otherView != null) FilledTonalButton(
                onClick = { expanded = true }, modifier = Modifier.testTag("tasks_more_views").semantics { selected = true },
            ) { buttonContent() }
            else TextButton(onClick = { expanded = true }, modifier = Modifier.testTag("tasks_more_views")) { buttonContent() }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                listOf(TaskView.Done, TaskView.Trash).forEach { view ->
                    DropdownMenuItem(
                        text = { Text(stringResource(taskViewLabel(view))) },
                        onClick = { expanded = false; onSelect(selection.copy(view = view)) },
                        leadingIcon = { RadioButton(selected = selection.view == view, onClick = null) },
                        modifier = Modifier.testTag("tasks_view_$view").semantics { selected = selection.view == view },
                    )
                }
                if (selection.listId == null) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    TaskSortMenu(selection) { expanded = false; onSelect(it) }
                }
            }
        }
    }
}

@Composable
private fun TaskSortMenu(selection: TaskSelection, onSelect: (TaskSelection) -> Unit) {
    Text(stringResource(DesignR.string.tasks_sort), style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = Spacing.l, vertical = Spacing.s))
    TaskSort.entries.filter { it != TaskSort.Manual || selection.listId != null }.forEach { sort ->
        DropdownMenuItem(
            text = { Text(stringResource(taskSortLabel(sort))) },
            onClick = { onSelect(selection.copy(sort = sort)) },
            leadingIcon = { RadioButton(selected = selection.sort == sort, onClick = null) },
            modifier = Modifier.testTag("tasks_sort_$sort").semantics { selected = selection.sort == sort },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TaskFiltersSheet(state: TasksState, onDismiss: () -> Unit, onSelect: (TaskSelection) -> Unit) {
    val selection = state.selection
    val filters = selection.filters
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(Spacing.xl).testTag("tasks_filters_sheet"),
            verticalArrangement = Arrangement.spacedBy(Spacing.m),
        ) {
            CardTitle(stringResource(DesignR.string.tasks_filters_action))
            Text(stringResource(DesignR.string.tasks_filters_combine), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Column {
                TaskFilterCheck(filters.important, DesignR.string.tasks_filters_important, "tasks_filter_important") {
                    onSelect(selection.copy(filters = filters.copy(important = it)))
                }
                TaskFilterCheck(filters.overdue, DesignR.string.tasks_filters_overdue, "tasks_filter_overdue") {
                    onSelect(selection.copy(filters = filters.copy(overdue = it)))
                }
            }
            if (state.lists.data.orEmpty().any { it.id == selection.listId && it.spaceId != null }) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Text(stringResource(DesignR.string.tasks_filters_assignee), style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Column {
                    listOf(null to DesignR.string.tasks_filters_assignees_any, "me" to DesignR.string.tasks_filters_assignees_me,
                        "unassigned" to DesignR.string.tasks_filters_assignees_unassigned).forEach { (value, label) ->
                        Row(
                            Modifier.fillMaxWidth().heightIn(min = 48.dp).selectable(
                                selected = filters.assignee == value, role = Role.RadioButton,
                                onClick = { onSelect(selection.copy(filters = filters.copy(assignee = value))) },
                            ).testTag("tasks_filter_assignee_${value ?: "any"}"),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(Spacing.m),
                        ) {
                            RadioButton(selected = filters.assignee == value, onClick = null)
                            Text(stringResource(label))
                        }
                    }
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Text(stringResource(if (filters.completedScope == "all") DesignR.string.tasks_filters_completed_all
                else DesignR.string.tasks_filters_completed_recent), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            TextButton(
                onClick = {
                    if (filters.completedScope == "all") onSelect(selection.copy(filters = filters.copy(completedScope = "recent")))
                    else { onSelect(wholeHistorySelection(selection)); onDismiss() }
                },
                modifier = Modifier.testTag("tasks_filter_history"),
            ) {
                Text(stringResource(if (filters.completedScope == "all") DesignR.string.tasks_filters_recent_only
                    else DesignR.string.tasks_filters_search_all_history))
            }
            if (filters.activeCount > 0) OutlinedButton(
                onClick = { onSelect(selection.copy(filters = TaskFilters())); onDismiss() },
                modifier = Modifier.testTag("tasks_filter_reset"),
            ) { Text(stringResource(DesignR.string.tasks_filters_reset)) }
        }
    }
}

@Composable
private fun TaskFilterCheck(checked: Boolean, label: Int, tag: String, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp).toggleable(value = checked, role = Role.Checkbox, onValueChange = onChange).testTag(tag),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.m),
    ) {
        Checkbox(checked = checked, onCheckedChange = null)
        Text(stringResource(label))
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun TaskFilterSummary(state: TasksState, onSelect: (TaskSelection) -> Unit) {
    val selection = state.selection
    val filters = selection.filters
    if (filters.activeCount == 0) return
    val project = state.lists.data.orEmpty().find { it.id == selection.listId }
    val board = selection.layout == TaskLayout.Board && project?.isProject == true
    val total = if (board) state.board.data?.total else state.page.data?.total
    val pending = if (board) state.board.loading else state.page.loading
    val chips = buildList {
        if (filters.q.isNotBlank()) add(TaskFilterSummaryChip("q", stringResource(DesignR.string.tasks_filters_query, filters.q), filters.copy(q = "")))
        if (filters.important) add(TaskFilterSummaryChip("important", stringResource(DesignR.string.tasks_filters_important), filters.copy(important = false)))
        if (filters.overdue) add(TaskFilterSummaryChip("overdue", stringResource(DesignR.string.tasks_filters_overdue), filters.copy(overdue = false)))
        filters.assignee?.let {
            add(TaskFilterSummaryChip("assignee", stringResource(if (it == "me") DesignR.string.tasks_filters_assignees_me
                else DesignR.string.tasks_filters_assignees_unassigned), filters.copy(assignee = null)))
        }
        if (filters.completedScope == "all") add(TaskFilterSummaryChip("completed_scope", stringResource(DesignR.string.tasks_filters_all_history),
            filters.copy(completedScope = "recent")))
    }
    val summaryLabel = stringResource(DesignR.string.tasks_filters_active)
    Column(Modifier.fillMaxWidth().testTag("tasks_filters_summary").semantics { contentDescription = summaryLabel },
        verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
            chips.forEach { chip ->
                val removeLabel = stringResource(DesignR.string.tasks_filters_remove, chip.label)
                InputChip(
                    selected = false,
                    onClick = { onSelect(selection.copy(filters = chip.filters)) },
                    label = { Text(chip.label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    trailingIcon = { TaskControlIcon(DesignR.drawable.lucide_x) },
                    modifier = Modifier.testTag("tasks_filter_remove_${chip.key}").semantics { contentDescription = removeLabel },
                )
            }
            TextButton(onClick = { onSelect(selection.copy(filters = TaskFilters())) },
                modifier = Modifier.testTag("tasks_filters_summary_reset")) { Text(stringResource(DesignR.string.tasks_filters_reset)) }
        }
        val result = if (pending || total == null) stringResource(DesignR.string.tasks_filters_updating)
            else stringResource(DesignR.string.tasks_filters_results, total.toString())
        Text(result + (project?.let { " · " + stringResource(DesignR.string.tasks_filters_project_scope, it.name) } ?: ""),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
        if (filters.narrowed && filters.completedScope == "recent") TextButton(
            onClick = { onSelect(wholeHistorySelection(selection)) },
            modifier = Modifier.testTag("tasks_filter_whole_history"),
        ) { Text(stringResource(DesignR.string.tasks_filters_search_all_history)) }
        if (!pending && total == 0 && filters.q.isBlank()) Text(stringResource(if (project != null) DesignR.string.tasks_filters_none_in_project else DesignR.string.tasks_filters_none),
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private data class TaskFilterSummaryChip(val key: String, val label: String, val filters: TaskFilters)

private fun wholeHistorySelection(selection: TaskSelection) = selection.copy(
    filters = selection.filters.copy(completedScope = "all"),
    view = if (selection.layout == TaskLayout.List) TaskView.All else selection.view,
)

@Composable
private fun TaskControlIcon(drawable: Int, modifier: Modifier = Modifier) {
    Icon(painterResource(drawable), contentDescription = null, modifier = modifier.size(18.dp))
}
