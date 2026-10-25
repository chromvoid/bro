package assistant.mobile.core.tasks

import assistant.mobile.core.NativeJson
import kotlinx.datetime.LocalDate
import kotlinx.serialization.json.*
import kotlin.time.Instant

fun taskObject(bytes: ByteArray): JsonObject = NativeJson.parseToJsonElement(bytes.decodeToString(throwOnInvalidSequence = true)).jsonObject
fun decodeTask(bytes: ByteArray): Task = decodeTask(taskObject(bytes))
internal fun decodeTask(value: JsonObject): Task = with(value) {
    val status = text("status").also { require(it in setOf("open", "done")) }
    Task(text("id"), text("title"), status, positive("revision"), optionalText("list_id"), optionalText("description"),
        optionalText("state")?.let(TaskState::fromWire) ?: if (status == "done") TaskState.Done else TaskState.Todo,
        optionalText("waiting_reason"), optionalInt("priority")?.also { require(it in 1..3) } ?: 2,
        this["position"]?.takeUnless { it == JsonNull }?.let {
            requireNotNull((it as? JsonPrimitive)?.takeUnless { primitive -> primitive.isString }?.doubleOrNull).also { number -> require(number.isFinite()) }
        },
        optionalText("parent_id"), obj("parent")?.let { TaskParent(it.text("id"), it.text("title"), TaskState.fromWire(it.text("state")), it.timestamp("deleted_at")) },
        optionalText("assigned_to"), array("checklist").map { entry -> entry.jsonObject.let { TaskStep(it.text("id"), it.text("text"), it.boolean("done")) } },
        array("title_images").map { decodeTaskImage(it.jsonObject) }, array("description_images").map { decodeTaskImage(it.jsonObject) },
        optionalText("discussion_channel_id"), obj("discussion")?.let { TaskDiscussionCount(it.nonnegative("total"), it.nonnegative("unread")) },
        day("planned_on"), day("due_on"), timestamp("completed_at"), timestamp("deleted_at"), timestamp("updated_at"),
        optionalBoolean("overdue") ?: false, obj("subtasks")?.let { TaskSubtaskCount(it.nonnegative("total"), it.nonnegative("done")) })
}
fun decodeTaskImage(bytes: ByteArray): TaskImage = decodeTaskImage(taskObject(bytes))
private fun decodeTaskImage(value: JsonObject) = TaskImage(value.text("id"), value.nonnegative("width").also { require(it > 0) }, value.nonnegative("height").also { require(it > 0) })
fun decodeTaskPage(bytes: ByteArray): TaskPage = with(taskObject(bytes)) {
    TaskPage(nonnegative("total"), requiredArray("items").map { decodeTask(it.jsonObject) }, optionalText("next_cursor"),
        requireNotNull(day("today")), text("timezone"), optionalText("snapshot"))
}
fun decodeTaskBoard(bytes: ByteArray): TaskBoard = with(taskObject(bytes)) {
    val snapshot = text("snapshot")
    val columns = requireNotNull(obj("columns"))
    TaskBoard(snapshot, nonnegative("total"), TaskState.entries.associateWith { state ->
        val value = requireNotNull(columns.obj(state.wire))
        TaskColumn(value.text("snapshot").also { require(it == snapshot) }, value.nonnegative("total"),
            value.requiredArray("items").map { decodeTask(it.jsonObject) }, value.optionalText("next_cursor"))
            .also { require(it.nextCursor == null || it.items.isNotEmpty()) }
    }, requireNotNull(day("today")), text("timezone"))
}
fun decodeTaskLists(bytes: ByteArray): List<TaskList> = taskObject(bytes).requiredArray("lists").map { decodeTaskList(it.jsonObject) }
private fun decodeTaskList(value: JsonObject) = with(value) {
    TaskList(text("id"), text("name"), positive("revision"), text("effective"), text("member_access"), optionalText("space_id"),
        optionalText("list_kind")?.also { require(it in setOf("list", "project")) } ?: "list", optionalText("outcome"),
        optionalText("project_state")?.also { require(it in setOf("active", "done", "archived")) } ?: "active", optionalText("personal_user_id"),
        obj("counts")?.mapValues { (_, value) -> number(value).also { require(it >= 0) } } ?: emptyMap())
}
fun decodeTaskSpaces(bytes: ByteArray) = taskObject(bytes).requiredArray("spaces").map { it.jsonObject.let { value -> TaskSpace(value.text("id"), value.text("name"), value.text("role")) } }
fun decodeTaskMembers(bytes: ByteArray) = taskObject(bytes).requiredArray("members").map { it.jsonObject.let { value -> TaskMember(value.text("user_id"), value.text("name"), value.text("role")) } }
fun decodeTaskHistory(bytes: ByteArray): TaskHistoryPage = with(taskObject(bytes)) {
    TaskHistoryPage(requiredArray("items").map { element -> element.jsonObject.let { event ->
        val kind = event.text("kind").also { require(it in setOf("created", "updated", "state_changed", "assigned", "unassigned", "deleted", "restored", "moved", "reordered", "parent_changed", "result_accepted")) }
        val markers = setOf("title", "description", "checklist", "waiting_reason", "title_images", "description_images", "list_id", "parent_id", "position", "deleted_at")
        val values = setOf("state", "planned_on", "due_on", "priority", "assigned_to")
        val changes = requireNotNull(event.obj("changes")).mapValues { (key, raw) ->
            require(key in markers || key in values)
            val change = raw.jsonObject
            if (key in markers) { require(change.isEmpty()); TaskHistoryChange(null, null, true) }
            else {
                require(change.keys == setOf("before", "after"))
                fun decode(value: JsonElement?): String? = if (value == JsonNull) null else if (key == "priority")
                    number(requireNotNull(value)).also { require(it in 1..3) }.toString() else strictText(requireNotNull(value)).also {
                    if (key == "state") TaskState.fromWire(it)
                    if (key in setOf("planned_on", "due_on")) LocalDate.parse(it)
                }
                TaskHistoryChange(decode(change["before"]), decode(change["after"]))
            }
        }
        TaskHistoryEvent(event.text("id"), requireNotNull(event.timestamp("created_at")), kind,
            person(requireNotNull(event.obj("actor"))), changes, event.optionalText("reason")?.also { require(it in setOf("member_left", "account_deleted")) },
            event.optionalLong("task_revision")?.also { require(it > 0) })
    } }, requiredArray("people").map { person(it.jsonObject) }, optionalText("next_cursor"))
}
fun decodeTaskDiscussion(bytes: ByteArray, channelId: String): TaskDiscussionPage = with(taskObject(bytes)) {
    obj("channel")?.let { require(it.text("id") == channelId) }
    TaskDiscussionPage(channelId, requiredArray("messages").map { element -> element.jsonObject.let {
        TaskMessage(it.text("id"), it.optionalText("actor_user_id"), it.text("author"), requireNotNull(it.timestamp("created_at")), it.optionalText("name"), it.optionalText("text"))
    } }, optionalText("next_cursor"))
}
fun decodeTaskReceipt(bytes: ByteArray): TaskReceipt = with(taskObject(bytes)) {
    TaskReceipt(text("operation_id"), text("kind"), array("tasks").map { decodeTask(it.jsonObject) },
        array("lists").map { decodeTaskList(it.jsonObject) }, obj("undo")?.let(::decodeTaskOperation), this)
}
private fun person(value: JsonObject) = TaskPerson(value.text("id"), value.text("name"))
private fun strictText(value: JsonElement) = requireNotNull((value as? JsonPrimitive)?.takeIf { it.isString }?.content)
private fun number(value: JsonElement) = requireNotNull((value as? JsonPrimitive)?.takeUnless { it.isString }?.intOrNull)
private fun JsonObject.text(key: String) = strictText(requireNotNull(this[key]))
private fun JsonObject.optionalText(key: String) = this[key]?.takeUnless { it == JsonNull }?.let(::strictText)
private fun JsonObject.optionalLong(key: String) = this[key]?.takeUnless { it == JsonNull }?.let { requireNotNull((it as? JsonPrimitive)?.takeUnless { p -> p.isString }?.longOrNull) }
private fun JsonObject.positive(key: String) = requireNotNull(optionalLong(key)).also { require(it > 0) }
private fun JsonObject.nonnegative(key: String) = number(requireNotNull(this[key])).also { require(it >= 0) }
private fun JsonObject.optionalInt(key: String) = this[key]?.takeUnless { it == JsonNull }?.let(::number)
private fun JsonObject.boolean(key: String) = requireNotNull(optionalBoolean(key))
private fun JsonObject.optionalBoolean(key: String) = this[key]?.takeUnless { it == JsonNull }?.let { requireNotNull((it as? JsonPrimitive)?.takeUnless { p -> p.isString }?.booleanOrNull) }
private fun JsonObject.requiredArray(key: String) = requireNotNull(this[key]) as JsonArray
private fun JsonObject.array(key: String): JsonArray = this[key]?.takeUnless { it == JsonNull }?.let { it as JsonArray } ?: JsonArray(emptyList())
private fun JsonObject.obj(key: String) = this[key]?.takeUnless { it == JsonNull }?.let { it as JsonObject }
private fun JsonObject.day(key: String) = optionalText(key)?.also { require(LocalDate.parse(it).toString() == it) }
private fun JsonObject.timestamp(key: String) = optionalText(key)?.also { Instant.parse(it) }
