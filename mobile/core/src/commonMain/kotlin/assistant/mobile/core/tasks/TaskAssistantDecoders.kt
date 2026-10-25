package assistant.mobile.core.tasks

import assistant.mobile.core.NativeJson
import io.ktor.http.URLProtocol
import io.ktor.http.Url
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.jsonObject
import kotlin.time.Instant

fun decodeTaskAssistantSnapshot(bytes: ByteArray, taskId: String): TaskAssistantSnapshot = assistantObject(bytes).let { row ->
    TaskAssistantSnapshot(row.optionalObject("delegation")?.let { delegation ->
        require(delegation.text("task_id") == taskId) { "Delegation belongs to another task" }
        TaskDelegation(delegation.text("id"), taskId, delegation.text("user_id"), delegation.number("revision"),
            delegation.text("instruction"), TaskAssistantMode.entries.first { it.wire == delegation.text("mode") },
            TaskCompletionMode.entries.first { it.wire == delegation.text("completion_mode") }, delegation.text("status"),
            grants(delegation.obj("grants")), delegation.optionalObject("completion_criteria")?.array("required_checks")?.map { element ->
                TaskCompletionCheck.entries.first { it.wire == text(element) }
            } ?: emptyList(), delegation.optionalNumber("interval_minutes"), delegation.time("next_run_at"),
            delegation.optionalObject("budget")?.let { TaskAssistantBudget(it.optionalNumber("active_seconds"), it.optionalNumber("tool_calls"), it.optionalNumber("daily_runs")) })
    }, row.array("runs").map { run(it.jsonObject) }, row.array("questions").map { element ->
        val question = element.jsonObject
        question.optionalText("task_id")?.let { require(it == taskId) }
        TaskAssistantQuestion(question.text("id"), question.text("text"), question.text("status"), question.number("revision"),
            question.optionalBoolean("audience_private") ?: false,
            (question.optionalText("kind") ?: "question").also { require(it in setOf("question", "proposal")) },
            question.optionalText("answer"), question.optionalText("decision")?.also { require(it in setOf("approve", "decline")) })
    }, row.array("results").map { element ->
        val result = element.jsonObject
        result.optionalText("task_id")?.let { require(it == taskId) }
        TaskAssistantResult(result.text("id"), result.text("body"), result.text("status"), result.number("revision"),
            result.optionalBoolean("audience_private") ?: false, result.array("sources").map { source ->
                source.jsonObject.let { TaskAssistantSource(it.optionalText("title"), it.text("url")) }
            }, result.time("created_at"))
    }, row.optionalBoolean("can_manage"))
}

fun decodeTaskAssistantCapabilities(bytes: ByteArray): TaskAssistantCapabilities = assistantObject(bytes).let { row ->
    fun choices(key: String) = row.array(key).map { element -> element.jsonObject.let { TaskAssistantChoice(it.text("id"), it.optionalText("title"), it.optionalText("description")) } }
    TaskAssistantCapabilities(row.optionalBoolean("enabled") ?: true, row.optionalObject("default_grants")?.let(::grants) ?: TaskAssistantGrants(),
        choices("tools"), choices("actions"), choices("resources"), choices("executors"))
}

fun decodeTaskAssistantRun(bytes: ByteArray, taskId: String, runId: String): TaskAssistantRun = run(assistantObject(bytes)).also {
    require(it.id == runId && it.taskId == taskId) { "Run belongs to another task" }
}

/** A source opens only as an external HTTP(S) document, never a credential-bearing URL. */
fun safeTaskAssistantSource(value: String): String? = runCatching {
    val url = Url(value)
    require(url.protocol in setOf(URLProtocol.HTTP, URLProtocol.HTTPS) && url.host.isNotBlank() &&
        url.user.isNullOrEmpty() && url.password.isNullOrEmpty() && value.none(Char::isISOControl))
    url.toString()
}.getOrNull()

private fun run(row: JsonObject) = TaskAssistantRun(row.text("id"), row.text("status"), row.time("created_at"), row.time("finished_at"),
    row.optionalText("summary"), row.optionalText("reason"), row["error"]?.let { it != JsonNull } ?: false,
    row.optionalText("task_id"), row.optionalText("delegation_id"), row.optionalNumber("tool_calls", true))
private fun grants(row: JsonObject) = TaskAssistantGrants(row.array("tools").map(::text), row.array("actions").map(::text),
    row.array("resources").map(::text), row.array("executors").map(::text))
private fun assistantObject(bytes: ByteArray): JsonObject = NativeJson.parseToJsonElement(bytes.decodeToString(throwOnInvalidSequence = true)).jsonObject
private fun text(value: JsonElement): String = (value as? JsonPrimitive)?.takeIf { it.isString }?.content ?: error("Expected string")
private fun JsonObject.text(key: String): String = text(requireNotNull(this[key]))
private fun JsonObject.optionalText(key: String): String? = this[key]?.takeUnless { it == JsonNull }?.let(::text)
private fun JsonObject.obj(key: String): JsonObject = requireNotNull(this[key] as? JsonObject)
private fun JsonObject.optionalObject(key: String): JsonObject? = this[key]?.takeUnless { it == JsonNull }?.let { it as? JsonObject ?: error("Expected object") }
private fun JsonObject.array(key: String): JsonArray = this[key]?.takeUnless { it == JsonNull }?.let { it as? JsonArray ?: error("Expected array") } ?: JsonArray(emptyList())
private fun JsonObject.optionalBoolean(key: String): Boolean? = this[key]?.takeUnless { it == JsonNull }?.let {
    requireNotNull((it as? JsonPrimitive)?.takeUnless { primitive -> primitive.isString }?.booleanOrNull)
}
private fun JsonObject.number(key: String): Long = requireNotNull(optionalNumber(key))
private fun JsonObject.optionalNumber(key: String, zero: Boolean = false): Long? = this[key]?.takeUnless { it == JsonNull }?.let {
    requireNotNull((it as? JsonPrimitive)?.takeUnless { primitive -> primitive.isString }?.longOrNull).also { number -> require(number >= if (zero) 0 else 1) }
}
private fun JsonObject.time(key: String): String? = optionalText(key)?.also { Instant.parse(it) }

/** An unrelated or stale assistant receipt must never settle the shared journal entry. */
fun validateTaskAssistantSubmissionReceipt(entry: assistant.mobile.core.journal.Submission, response: ByteArray) {
    if (entry.endpoint != TASK_OPERATIONS) return
    val sent = assistantObject(entry.body)
    val kind = sent.text("kind")
    if (kind !in setOf("delegation.create", "delegation.update", "delegation.pause", "delegation.resume", "delegation.revoke", "question.answer", "result.accept", "result.revise")) return
    val receipt = assistantObject(response)
    require(receipt.text("operation_id").isNotBlank() && receipt.text("kind") == kind)
    val payload = sent.obj("payload")
    val expected = sent.obj("expected_revisions")
    val targetKind = when { kind.startsWith("delegation.") -> "delegation"; kind == "question.answer" -> "question"; else -> "result" }
    val record = receipt.obj(targetKind)
    val taskId = receipt.text("task_id")
    require(record.text("task_id") == taskId)
    if (kind == "delegation.create") {
        require(taskId == payload.text("task_id") && record.number("revision") >= 1 && record.text("id").isNotBlank())
        require(record.text("user_id") == entry.namespace.userId)
        require(expected.number(taskId) > 0)
        require(record.text("instruction") == payload.text("instruction"))
    } else {
        val id = payload.text("${targetKind}_id")
        require(record.text("id") == id && record.number("revision") > expected.number(id))
        // The client includes its current task revision as a scope fence in every assistant write.
        require(expected.keys - id == setOf(taskId)) { "Receipt belongs to another task" }
    }
    when (kind) {
        "question.answer" -> {
            require(record.text("status") == "answered")
            payload.optionalText("answer")?.let { require(record.text("answer") == it) }
            payload.optionalText("decision")?.let { require(record.text("decision") == it) }
        }
        "result.accept" -> {
            require(record.text("status") == "accepted")
            if (payload.optionalBoolean("complete_task") == true) {
                val task = receipt.array("tasks").firstOrNull { it.jsonObject.text("id") == taskId }?.jsonObject ?: error("Completed task missing")
                require(task.text("status") == "done" && task.number("revision") > expected.number(taskId))
            }
        }
        "result.revise" -> require(record.text("status") == "revision_requested")
        "delegation.pause", "delegation.update" -> require(record.text("status") == "paused")
        "delegation.resume" -> require(record.text("status") == "active")
        "delegation.revoke" -> require(record.text("status") == "revoked")
    }
}
