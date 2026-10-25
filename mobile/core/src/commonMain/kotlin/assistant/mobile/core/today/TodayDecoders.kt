package assistant.mobile.core.today

import assistant.mobile.core.NativeJson
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject

fun decodeTodayFood(bytes: ByteArray): TodayFood {
    val row = objectOf(bytes)
    val energy = row.objectOrNull("totals")?.objectOrNull("energy_kcal")
    return TodayFood(
        row.text("date"), row.array("items").isEmpty() && row.array("drafts").isEmpty(),
        energy?.amount("known"), energy?.boolean("complete") ?: true,
        row.objectOrNull("goal")?.amount("calorie_target"), row.amount("remaining_kcal"),
        row.boolean("remaining_complete") ?: true,
    )
}

fun decodeTodayTasks(bytes: ByteArray): TodayTasks {
    val row = objectOf(bytes)
    return TodayTasks(row.text("today"), row.text("timezone"), row.array("items").map { element ->
        val task = element.jsonObject
        TodayTask(task.text("id"), task.text("title"), task.optionalText("due_on"), task.boolean("overdue") == true)
    })
}

fun decodeTodayTimers(bytes: ByteArray, readAtMillis: Long): TodayTimers = TodayTimers(
    objectOf(bytes).array("timers").map { element ->
        val timer = element.jsonObject
        TodayTimer(timer.text("id"), timer.optionalText("label"),
            requireNotNull(timer.amount("remaining_seconds")?.toDoubleOrNull()))
    }, readAtMillis,
)

fun decodeTodayNotifications(bytes: ByteArray): TodayNotifications = TodayNotifications(
    objectOf(bytes).array("notifications").mapNotNull { element ->
        val item = element.jsonObject
        val severity = item.text("severity")
        require(severity in setOf("info", "important", "urgent"))
        if (severity != "info" && item.text("state") == "active" && item.boolean("unread") == true)
            TodayNotification(item.text("id"), item.text("title")) else null
    },
)

fun decodeTodayPersonal(bytes: ByteArray): TodayPersonal {
    val row = objectOf(bytes)
    val metrics = requireNotNull(row.objectOrNull("metrics"))
    return TodayPersonal(row.text("date"), decodeMetric(metrics.objectOrNull("weight"), useRecordDate = true),
        decodeMetric(metrics.objectOrNull("steps")))
}

private fun decodeMetric(row: JsonObject?, useRecordDate: Boolean = false): TodayMetric {
    if (row == null) return TodayMetric("no_data", null, "", null, null, null, false)
    val status = row.text("status")
    require(status in setOf("available", "no_data", "conflict", "unavailable"))
    val resolution = row.objectOrNull("resolution")
    val record = (row["records"] as? JsonArray)?.lastOrNull() as? JsonObject
    val complete = row.boolean("complete")
    return TodayMetric(status, if (status == "available") row.amount("value") else null,
        row.optionalText("unit") ?: "", row.amount("target"), complete,
        if (useRecordDate) record?.optionalText("start_at")
        else resolution?.objectOrNull("freshness")?.optionalText("measured_at"),
        complete == false || resolution?.objectOrNull("coverage")?.boolean("complete") == false ||
            resolution?.optionalText("status") == "partial",
        (resolution?.get("contributions") as? JsonArray).orEmpty().map { it.jsonObject }
            .sortedWith(compareByDescending<JsonObject> { it.amount("value")?.toDoubleOrNull() ?: 0.0 }
                .thenByDescending { it.amount("count")?.toDoubleOrNull() ?: 0.0 })
            .map { TodayMetricSource(it.optionalText("source_id"), it.optionalText("source_name")) })
}

fun decodeTodayStepsOverride(bytes: ByteArray): TodayStepsOverride {
    val row = objectOf(bytes)
    val override = row.objectOrNull("override")
    return TodayStepsOverride(row.text("date"), override?.boolean("active") == true, override?.amount("value"))
}

fun decodeTodayWeightHistory(bytes: ByteArray): TodayWeightHistory {
    val rows = objectOf(bytes).array("daily").mapNotNull { element ->
        val row = element.jsonObject
        if (row.optionalText("kind") != "weight" || row.optionalText("status") == "conflict") null
        else row.amount("value")?.let { row.text("date") to it }
    }.sortedBy { it.first }
    return TodayWeightHistory(if (rows.size < 2) null else decimalDifference(rows.last().second, rows.first().second))
}

private fun objectOf(bytes: ByteArray): JsonObject =
    NativeJson.parseToJsonElement(bytes.decodeToString(throwOnInvalidSequence = true)).jsonObject
private fun JsonObject.array(key: String): JsonArray = requireNotNull(this[key] as? JsonArray)
private fun JsonObject.objectOrNull(key: String): JsonObject? {
    val element = this[key] ?: return null
    if (element == JsonNull) return null
    return element as? JsonObject ?: error("Expected object")
}
private fun JsonObject.text(key: String): String = requireNotNull(optionalText(key))
private fun JsonObject.optionalText(key: String): String? {
    val element = this[key] ?: return null
    if (element == JsonNull) return null
    val primitive = requireNotNull(element as? JsonPrimitive)
    require(primitive.isString)
    return primitive.content
}
private fun JsonObject.boolean(key: String): Boolean? {
    val value = this[key] ?: return null
    if (value == JsonNull) return null
    return requireNotNull((value as? JsonPrimitive)?.booleanOrNull)
}
private fun JsonObject.amount(key: String): String? {
    val element = this[key] ?: return null
    if (element == JsonNull) return null
    val raw = requireNotNull((element as? JsonPrimitive)?.contentOrNull)
    require(raw.isNotBlank() && raw.toDoubleOrNull()?.isFinite() == true)
    require(raw.removePrefix("-").removePrefix("+").split('.').let { parts ->
        parts.size in 1..2 && parts.all { part -> part.isNotEmpty() && part.all { it in '0'..'9' } }
    })
    return raw
}

/** Body weights are nonnegative decimals; subtract their digit strings without binary rounding. */
private fun decimalDifference(last: String, first: String): String {
    require(!last.startsWith('-') && !first.startsWith('-'))
    val places = maxOf(last.substringAfter('.', "").length, first.substringAfter('.', "").length)
    fun scaled(value: String): String = (value.substringBefore('.') + value.substringAfter('.', "").padEnd(places, '0'))
        .trimStart('0').ifEmpty { "0" }
    val a = scaled(last)
    val b = scaled(first)
    val comparison = if (a.length != b.length) a.length.compareTo(b.length) else a.compareTo(b)
    val larger = if (comparison >= 0) a else b
    val smaller = (if (comparison >= 0) b else a).padStart(larger.length, '0')
    var borrow = 0
    val digits = CharArray(larger.length)
    for (index in larger.lastIndex downTo 0) {
        var digit = larger[index].digitToInt() - smaller[index].digitToInt() - borrow
        borrow = if (digit < 0) 1 else 0
        if (digit < 0) digit += 10
        digits[index] = '0' + digit
    }
    val result = digits.concatToString().padStart(places + 1, '0')
    val whole = result.dropLast(places).trimStart('0').ifEmpty { "0" }
    val fraction = if (places == 0) "" else result.takeLast(places).trimEnd('0')
    return (if (comparison < 0) "-" else "") + whole + (if (fraction.isEmpty()) "" else ".$fraction")
}
