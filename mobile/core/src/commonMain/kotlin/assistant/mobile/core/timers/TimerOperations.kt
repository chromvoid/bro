package assistant.mobile.core.timers

import assistant.mobile.core.journal.Submission
import kotlinx.serialization.json.*

const val TIMER_OPERATIONS = "/v1/timers/operations"
const val TIMER_LOOKUP = "$TIMER_OPERATIONS/by-key/"
data class TimerOperation(val kind: String, val payload: JsonObject, val expectedRevisions: Map<String, Long> = emptyMap()) {
    fun bytes(): ByteArray = buildJsonObject {
        put("kind", kind); put("payload", payload)
        put("expected_revisions", JsonObject(expectedRevisions.mapValues { JsonPrimitive(it.value) }))
    }.toString().encodeToByteArray()
}
fun startTimerOperation(minutes: Int, label: String): TimerOperation {
    require(minutes in 1..1440 && label.trim().length <= 60)
    return TimerOperation("timer.start", buildJsonObject {
        put("duration_seconds", minutes * 60)
        label.trim().takeIf { it.isNotEmpty() }?.let { put("label", it) }
    })
}
fun cancelTimerOperation(timer: Timer) = TimerOperation("timer.cancel",
    buildJsonObject { put("timer_ids", JsonArray(listOf(JsonPrimitive(timer.id)))) }, mapOf(timer.id to timer.revision))
fun cancelReminderOperation(reminder: Reminder) = TimerOperation("reminder.cancel",
    buildJsonObject { put("reminder_ids", JsonArray(listOf(JsonPrimitive(reminder.id)))) }, mapOf(reminder.id to reminder.revision))
fun createReminderOperation(draft: ReminderDraft, timezone: String): TimerOperation {
    require(draft.text.trim().isNotEmpty() && draft.text.trim().length <= 200)
    return TimerOperation("reminder.create", buildJsonObject {
        put("at", reminderInstant(draft, timezone)); put("text", draft.text.trim())
        draft.taskId?.let { put("task_id", it) }
    })
}
/** Identity validation accepts the server's sparse receipt, never fabricates a live revision. */
fun validateTimerSubmissionReceipt(entry: Submission, response: ByteArray) {
    if (entry.endpoint != TIMER_OPERATIONS) return
    val request = timerObject(entry.body)
    val kind = request.getValue("kind").jsonPrimitive.content
    val payload = request.getValue("payload").jsonObject
    val receipt = decodeTimerReceipt(response)
    require(kind in setOf("timer.start", "timer.cancel", "reminder.create", "reminder.cancel") && receipt.kind == kind)
    when (kind) {
        "timer.start" -> {
            require(receipt.timerIds.size == 1)
            val timer = receipt.raw.getValue("timers").jsonArray.single().jsonObject
            require(timer.getValue("duration_seconds") == payload.getValue("duration_seconds"))
            require(timer["label"]?.takeUnless { it == JsonNull } == payload["label"]?.takeUnless { it == JsonNull })
            kotlin.time.Instant.parse(timer.getValue("due_at").jsonPrimitive.content)
        }
        "timer.cancel" -> require(receipt.timerIds.toSet() == payload.getValue("timer_ids").jsonArray.map { it.jsonPrimitive.content }.toSet())
        "reminder.create" -> {
            require(receipt.reminderIds.size == 1)
            val reminder = receipt.raw.getValue("reminders").jsonArray.single().jsonObject
            require(reminder.getValue("text") == payload.getValue("text"))
            require(kotlin.time.Instant.parse(reminder.getValue("due_at").jsonPrimitive.content) == kotlin.time.Instant.parse(payload.getValue("at").jsonPrimitive.content))
            require(reminder["task_id"]?.takeUnless { it == JsonNull } == payload["task_id"]?.takeUnless { it == JsonNull })
        }
        "reminder.cancel" -> require(receipt.reminderIds.toSet() == payload.getValue("reminder_ids").jsonArray.map { it.jsonPrimitive.content }.toSet())
    }
}
