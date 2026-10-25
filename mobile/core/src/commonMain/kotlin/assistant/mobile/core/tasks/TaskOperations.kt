package assistant.mobile.core.tasks

import assistant.mobile.core.journal.Submission
import kotlinx.datetime.LocalDate
import kotlinx.serialization.json.*

const val TASK_OPERATIONS = "/v1/tasks/operations"
const val TASK_LOOKUP = "$TASK_OPERATIONS/by-key/"
data class TaskOperation(val kind: String, val payload: JsonObject, val expectedRevisions: Map<String, Long> = emptyMap(), val targetResourceId: String? = null) {
    fun bytes(): ByteArray = buildJsonObject {
        put("kind", kind); put("payload", payload)
        put("expected_revisions", JsonObject(expectedRevisions.mapValues { JsonPrimitive(it.value) }))
        targetResourceId?.let { put("target_resource_id", it) }
    }.toString().encodeToByteArray()
}
fun decodeTaskOperation(value: JsonObject) = TaskOperation(value.getValue("kind").jsonPrimitive.content,
    value.getValue("payload").jsonObject, value["expected_revisions"]?.jsonObject?.mapValues { it.value.jsonPrimitive.long } ?: emptyMap(),
    value["target_resource_id"]?.takeUnless { it == JsonNull }?.jsonPrimitive?.content)
fun changesOf(base: TaskFields, draft: TaskFields): JsonObject = buildJsonObject {
    fun text(key: String, old: String, value: String, required: Boolean = false) {
        if (old == value) return
        val clean = value.trim()
        if (required) { require(clean.isNotEmpty() && clean.length <= 300); put(key, clean) }
        else if (clean.isEmpty()) put(key, JsonNull) else put(key, clean)
    }
    text("title", base.title, draft.title, true); text("description", base.description, draft.description)
    text("planned_on", base.plannedOn, draft.plannedOn); text("due_on", base.dueOn, draft.dueOn)
    text("waiting_reason", base.waitingReason, draft.waitingReason); text("assigned_to", base.assignedTo, draft.assignedTo)
    if (base.state != draft.state) put("state", draft.state.wire)
    if (base.priority != draft.priority) put("priority", draft.priority.also { require(it in 1..3) })
    for (date in listOf(draft.plannedOn, draft.dueOn)) if (date.isNotBlank()) require(LocalDate.parse(date.trim()).toString() == date.trim())
    for ((key, old, values) in listOf(Triple("title_images", base.titleImages, draft.titleImages), Triple("description_images", base.descriptionImages, draft.descriptionImages))) {
        require(values.size <= if (key == "title_images") 4 else 20)
        require(values.map { it.id }.distinct().size == values.size)
        if (old.map { it.id } != values.map { it.id }) put(key, JsonArray(values.map { JsonPrimitive(it.id) }))
    }
}
fun editTaskOperation(draft: TaskEditDraft, revision: Long = draft.revision): TaskOperation {
    if (draft.taskId != null) {
        require(revision > 0)
        val changes = changesOf(draft.base, draft.fields).also { require(it.isNotEmpty()) }
        return TaskOperation("task.update", buildJsonObject { put("task_id", draft.taskId); put("changes", changes) }, mapOf(draft.taskId to revision))
    }
    val fields = draft.fields
    require(fields.title.trim().isNotEmpty() && fields.title.trim().length <= 300)
    val payload = buildJsonObject {
        put("title", fields.title.trim())
        // Comparing against an empty field set keeps optional creation fields truly absent.
        val changes = changesOf(TaskFields(), fields)
        for ((key, value) in changes) if (key != "title") put(key, value)
        draft.parentId?.let { put("parent_id", it) }
    }
    return TaskOperation("task.create", payload, if (draft.parentId != null && revision > 0) mapOf(draft.parentId to revision) else emptyMap(), draft.listId)
}
fun transitionTaskOperation(task: Task, kind: String): TaskOperation {
    require(kind in setOf("task.complete", "task.reopen", "task.delete", "task.restore"))
    return TaskOperation(kind, buildJsonObject { put("task_id", task.id) }, mapOf(task.id to task.revision))
}
/** Validate identity and revision before any domain adapter consumes a journal receipt. */
fun validateTaskSubmissionReceipt(entry: Submission, response: ByteArray) {
    if (entry.endpoint != TASK_OPERATIONS) return
    val sent = decodeTaskOperation(taskObject(entry.body))
    val receipt = decodeTaskReceipt(response)
    require(receipt.operationId.isNotBlank() && receipt.kind == sent.kind)
    validateTaskAssistantSubmissionReceipt(entry, response)
    val taskId = sent.payload["task_id"]?.jsonPrimitive?.content
    val listId = sent.payload["list_id"]?.jsonPrimitive?.content
    if (sent.kind.startsWith("task.") && sent.kind != "task.create") {
        requireNotNull(taskId)
        val task = requireNotNull(receipt.tasks.firstOrNull { it.id == taskId })
        require(task.revision > requireNotNull(sent.expectedRevisions[taskId]))
    }
    if (sent.kind == "task.create") require(receipt.tasks.isNotEmpty())
    if (sent.kind == "list.update") {
        val id = requireNotNull(listId)
        require(receipt.lists.any { it.id == id && it.revision > requireNotNull(sent.expectedRevisions[id]) })
    }
    if (sent.kind == "list.create") require(receipt.lists.isNotEmpty())
}
