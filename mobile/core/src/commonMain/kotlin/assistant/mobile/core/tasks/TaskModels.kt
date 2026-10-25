package assistant.mobile.core.tasks

import assistant.mobile.core.AccountNamespace
import assistant.mobile.core.LocalizedString
import assistant.mobile.core.journal.Submission
import io.ktor.http.encodeURLParameter
import io.ktor.http.encodeURLPathPart
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

@Serializable
enum class TaskState(val wire: String) {
    Todo("todo"), InProgress("in_progress"), Waiting("waiting"), Done("done");
    companion object { fun fromWire(value: String) = entries.first { it.wire == value } }
}
@Serializable
enum class TaskView(val wire: String) { Today("today"), Upcoming("upcoming"), Open("open"), Done("done"), Trash("trash"), All("all") }
@Serializable
enum class TaskScope(val wire: String) { All("all"), Personal("personal"), Assigned("assigned") }
@Serializable
enum class TaskSort(val wire: String) { Priority("priority"), Due("due"), Created("created"), Manual("manual") }
@Serializable
enum class TaskLayout { List, Board }
@Serializable
data class TaskFilters(val q: String = "", val important: Boolean = false, val overdue: Boolean = false,
    val assignee: String? = null, val completedScope: String = "recent") {
    init { require(assignee in setOf(null, "me", "unassigned") && completedScope in setOf("recent", "all")) }
    val narrowed get() = q.isNotBlank() || important || overdue || assignee != null
    val activeCount get() = (if (q.isNotBlank()) 1 else 0) + (if (important) 1 else 0) +
        (if (overdue) 1 else 0) + (if (assignee != null) 1 else 0) + (if (completedScope == "all") 1 else 0)
}
@Serializable
data class TaskSelection(val view: TaskView = TaskView.Today, val scope: TaskScope = TaskScope.All,
    val listId: String? = null, val sort: TaskSort = TaskSort.Priority, val layout: TaskLayout = TaskLayout.List,
    val filters: TaskFilters = TaskFilters())
@Serializable
data class TaskImage(val id: String, val width: Int, val height: Int)
@Serializable
data class TaskStep(val id: String, val text: String, val done: Boolean)
data class TaskParent(val id: String, val title: String, val state: TaskState, val deletedAt: String?)
data class TaskDiscussionCount(val total: Int, val unread: Int)
data class TaskSubtaskCount(val total: Int, val done: Int)
data class Task(
    val id: String, val title: String, val status: String, val revision: Long,
    val listId: String? = null, val description: String? = null,
    val state: TaskState = if (status == "done") TaskState.Done else TaskState.Todo,
    val waitingReason: String? = null, val priority: Int = 2, val position: Double? = null,
    val parentId: String? = null, val parent: TaskParent? = null, val assignedTo: String? = null,
    val checklist: List<TaskStep> = emptyList(), val titleImages: List<TaskImage> = emptyList(),
    val descriptionImages: List<TaskImage> = emptyList(), val discussionChannelId: String? = null,
    val discussion: TaskDiscussionCount? = null, val plannedOn: String? = null, val dueOn: String? = null,
    val completedAt: String? = null, val deletedAt: String? = null, val updatedAt: String? = null,
    val overdue: Boolean = false, val subtasks: TaskSubtaskCount? = null,
)
data class TaskPage(val total: Int, val items: List<Task>, val nextCursor: String?, val today: String,
    val timezone: String, val snapshot: String? = null)
data class TaskColumn(val snapshot: String, val total: Int, val items: List<Task>, val nextCursor: String?)
data class TaskBoard(val snapshot: String, val total: Int, val columns: Map<TaskState, TaskColumn>,
    val today: String, val timezone: String)
data class TaskList(val id: String, val name: String, val revision: Long, val effective: String,
    val memberAccess: String, val spaceId: String? = null, val listKind: String = "list", val outcome: String? = null,
    val projectState: String = "active", val personalUserId: String? = null, val counts: Map<String, Int> = emptyMap()) {
    val writable get() = effective in setOf("edit", "manage")
    val isProject get() = listKind == "project"
    val total get() = counts.values.sum()
    val done get() = counts["done"] ?: 0
}
data class TaskSpace(val id: String, val name: String, val role: String)
data class TaskMember(val userId: String, val name: String, val role: String)
data class TaskPerson(val id: String, val name: String)
data class TaskHistoryChange(val before: String?, val after: String?, val marker: Boolean = false)
data class TaskHistoryEvent(val id: String, val createdAt: String, val kind: String, val actor: TaskPerson,
    val changes: Map<String, TaskHistoryChange>, val reason: String?, val taskRevision: Long?)
data class TaskHistoryPage(val items: List<TaskHistoryEvent>, val people: List<TaskPerson>, val nextCursor: String?)
data class TaskMessage(val id: String, val actorUserId: String?, val author: String, val createdAt: String,
    val name: String?, val text: String?)
data class TaskDiscussionPage(val channelId: String, val messages: List<TaskMessage>, val nextCursor: String?)
@Serializable
data class TaskFields(val title: String = "", val description: String = "", val plannedOn: String = "", val dueOn: String = "",
    val state: TaskState = TaskState.Todo, val waitingReason: String = "", val priority: Int = 2, val assignedTo: String = "",
    val titleImages: List<TaskImage> = emptyList(), val descriptionImages: List<TaskImage> = emptyList())
@Serializable
data class TaskEditDraft(val taskId: String? = null, val revision: Long = 0, val base: TaskFields = TaskFields(),
    val fields: TaskFields = base, val listId: String? = null, val parentId: String? = null)
data class TaskReceipt(val operationId: String, val kind: String, val tasks: List<Task>, val lists: List<TaskList>,
    val undo: TaskOperation?, val raw: JsonObject)
data class TaskCompletion(val reason: String, val taskId: String, val revision: Long, val openSubtasks: Int,
    val incompleteChecklist: Int, val parentId: String?, val operation: TaskOperation, val canConfirm: Boolean)
data class TaskQuery<T>(val data: T? = null, val loading: Boolean = false, val error: LocalizedString? = null, val stale: Boolean = false)
data class TasksState(val namespace: AccountNamespace, val canRead: Boolean, val canWrite: Boolean,
    val selection: TaskSelection = TaskSelection(), val lists: TaskQuery<List<TaskList>> = TaskQuery(),
    val spaces: TaskQuery<List<TaskSpace>> = TaskQuery(), val members: TaskQuery<List<TaskMember>> = TaskQuery(),
    val page: TaskQuery<TaskPage> = TaskQuery(), val board: TaskQuery<TaskBoard> = TaskQuery(),
    val task: TaskQuery<Task> = TaskQuery(), val children: TaskQuery<TaskPage> = TaskQuery(),
    val history: TaskQuery<TaskHistoryPage> = TaskQuery(), val discussion: TaskQuery<TaskDiscussionPage> = TaskQuery(),
    val draft: TaskEditDraft? = null, val pending: List<Submission> = emptyList(), val receipt: TaskReceipt? = null,
    val discussionDraft: String = "", val completion: TaskCompletion? = null, val busy: Boolean = false, val conflict: Boolean = false,
    val error: LocalizedString? = null)

fun fieldsOf(task: Task) = TaskFields(task.title, task.description.orEmpty(), task.plannedOn.orEmpty(), task.dueOn.orEmpty(),
    task.state, task.waitingReason.orEmpty(), task.priority, task.assignedTo.orEmpty(), task.titleImages, task.descriptionImages)
fun readTaskFilters(query: Map<String, String?>) = TaskFilters(query["q"]?.trim().orEmpty(), query["important"] == "1",
    query["overdue"] == "1", query["assignee"]?.takeIf { it in setOf("me", "unassigned") },
    if (query["completed_scope"] == "all") "all" else "recent")
internal fun taskFilterParams(filters: TaskFilters) = buildMap {
    if (filters.q.isNotBlank()) put("q", filters.q.trim())
    if (filters.important) put("important", "1")
    if (filters.overdue) put("overdue", "1")
    filters.assignee?.let { put("assignee", it) }
    put("completed_scope", filters.completedScope)
}
internal fun taskParams(params: Map<String, String>) = params.entries.joinToString("&") { (key, value) ->
    "${key.encodeURLParameter()}=${value.encodeURLParameter()}"
}
fun taskQuery(selection: TaskSelection, cursor: String? = null): String = taskParams(buildMap {
    put("view", selection.view.wire); put("scope", selection.scope.wire); put("sort", selection.sort.wire)
    selection.listId?.let { put("list_id", it) }; putAll(taskFilterParams(selection.filters)); cursor?.let { put("cursor", it) }
})
fun boardQuery(selection: TaskSelection, state: TaskState? = null, cursor: String? = null): String = taskParams(buildMap {
    put("list_id", requireNotNull(selection.listId)); put("sort", if (state == TaskState.Done) "completed" else selection.sort.wire)
    if (state != null) { put("view", if (state == TaskState.Done) "done" else "open"); put("scope", "all")
        put("parent_id", "board"); if (state != TaskState.Done) put("state", state.wire) }
    putAll(taskFilterParams(selection.filters)); cursor?.let { put("cursor", it) }
})
fun taskImagePath(taskId: String, imageId: String, preview: Boolean = true) =
    "/v1/tasks/items/${taskId.encodeURLPathPart()}/images/${imageId.encodeURLPathPart()}" + if (preview) "/preview?v=1" else ""
fun newestTask(first: Task?, second: Task?, discussionSource: Task? = null): Task? {
    val newest = if (first == null || second != null && second.revision > first.revision) second else first
    return newest?.copy(discussion = discussionSource?.takeIf { it.id == newest.id }?.discussion ?: newest.discussion ?: first?.discussion ?: second?.discussion)
}
fun previousSibling(tasks: List<Task>, task: Task): Task? = tasks.take(tasks.indexOfFirst { it.id == task.id }.coerceAtLeast(0))
    .lastOrNull { it.listId == task.listId && it.parentId == task.parentId }
