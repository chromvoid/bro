package assistant.mobile.core.timers

import kotlinx.serialization.json.*
import kotlin.time.Instant

internal fun timerObject(bytes: ByteArray): JsonObject = Json.parseToJsonElement(bytes.decodeToString(throwOnInvalidSequence = true)).jsonObject
private fun JsonObject.text(key: String): String = getValue(key).jsonPrimitive.content.also { require(it.isNotBlank()) }
private fun JsonObject.optional(key: String) = get(key)?.takeUnless { it == JsonNull }?.jsonPrimitive?.content
private fun JsonObject.instant(key: String): String = text(key).also { Instant.parse(it) }
private fun JsonObject.revision(): Long = getValue("revision").jsonPrimitive.long.also { require(it > 0) }
fun decodeTimers(bytes: ByteArray): List<Timer> = timerObject(bytes).getValue("timers").jsonArray.map { element ->
    val item = element.jsonObject
    Timer(item.text("id"), item.optional("label"), item.getValue("duration_seconds").jsonPrimitive.int.also { require(it in 1..86400) },
        item.instant("due_at"), item.revision(), item.getValue("remaining_seconds").jsonPrimitive.double.also { require(it.isFinite() && it >= 0) })
}.also { items -> require(items.map { it.id }.distinct().size == items.size) }
fun decodeReminders(bytes: ByteArray): List<Reminder> = timerObject(bytes).getValue("reminders").jsonArray.map { element ->
    val item = element.jsonObject
    Reminder(item.text("id"), item.text("text"), item.instant("due_at"), item.revision(), item.optional("task_id"))
}.also { items -> require(items.map { it.id }.distinct().size == items.size) }
/** Ledger receipts deliberately omit live revision and remaining_seconds; always reread them. */
fun decodeTimerReceipt(bytes: ByteArray): TimerReceipt {
    val root = timerObject(bytes)
    fun ids(field: String) = root[field]?.takeUnless { it == JsonNull }?.jsonArray?.map { it.jsonObject.text("id") }.orEmpty()
    return TimerReceipt(root.text("operation_id"), root.text("kind"), ids("timers"), ids("reminders"), root)
}
