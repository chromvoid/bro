package assistant.mobile.core.notifications

import assistant.mobile.core.AccountNamespace
import assistant.mobile.core.LocalizedString
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.plus
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.json.*
import kotlin.time.Instant

/** The server owns unread, resolved and snoozed state; the clock never changes these locally. */
data class AppNotification(
    val id: String, val source: String, val topic: String, val severity: String,
    val title: String, val body: String, val link: String?, val state: String,
    val resolution: String?, val count: Int, val updatedAt: String, val readAt: String?,
    val resolvedAt: String?, val snoozedUntil: String?, val unread: Boolean,
) {
    val active: Boolean get() = state == "active"
    val isOpenProblem: Boolean get() = active && unread && severity != "info"
}

data class NotificationList(val notifications: List<AppNotification>, val unread: Int, val before: String?)
data class NotificationTopic(val topic: String, val severity: String)
data class NotificationSettings(
    val quietStart: String?, val quietEnd: String?, val mutedTopics: List<String>,
    val revision: Long, val topics: List<NotificationTopic>,
) {
    fun draft() = NotificationSettingsDraft(quietStart != null, quietStart ?: "23:00", quietEnd ?: "08:00", mutedTopics)
}
data class NotificationSettingsDraft(
    val quiet: Boolean = true, val start: String = "23:00", val end: String = "08:00",
    val mutedTopics: List<String> = emptyList(),
)
data class NotificationQuery<T>(val data: T? = null, val loading: Boolean = false,
    val error: LocalizedString? = null, val stale: Boolean = false)
data class NotificationsState(
    val namespace: AccountNamespace, val timezone: String,
    val list: NotificationQuery<NotificationList> = NotificationQuery(),
    val settings: NotificationQuery<NotificationSettings> = NotificationQuery(),
    val busy: Boolean = false, val error: LocalizedString? = null, val notice: LocalizedString? = null,
) {
    val items: List<AppNotification> get() = list.data?.notifications.orEmpty()
    val unread: Int get() = list.data?.unread ?: 0
}

/** «До утра» uses 08:00 tomorrow in this account's zone, including changes in UTC offset. */
fun notificationNextMorning(nowEpochMillis: Long, timezone: String): String {
    val zone = TimeZone.of(timezone)
    val tomorrow = Instant.fromEpochMilliseconds(nowEpochMillis).toLocalDateTime(zone).date.plus(DatePeriod(days = 1))
    return LocalDateTime(tomorrow, LocalTime(8, 0)).toInstant(zone).toString()
}

internal fun notificationObject(bytes: ByteArray): JsonObject =
    Json.parseToJsonElement(bytes.decodeToString(throwOnInvalidSequence = true)).jsonObject
private fun JsonObject.text(name: String) = getValue(name).jsonPrimitive.content
private fun JsonObject.optional(name: String) = get(name)?.takeUnless { it == JsonNull }?.jsonPrimitive?.content
private fun String.checkedInstant() = also { Instant.parse(it) }
private fun JsonObject.instant(name: String) = text(name).checkedInstant()
private fun JsonObject.optionalInstant(name: String) = optional(name)?.checkedInstant()
private fun String.identity() = also { require(isNotBlank()) }

fun decodeNotificationList(bytes: ByteArray): NotificationList {
    val root = notificationObject(bytes)
    val items = root.getValue("notifications").jsonArray.map { value ->
        val item = value.jsonObject
        AppNotification(item.text("id").identity(), item.text("source"), item.text("topic"),
            item.text("severity").also { require(it in setOf("info", "important", "urgent")) },
            item.text("title"), item.text("body"), item.optional("link"),
            item.text("state").also { require(it in setOf("active", "resolved")) },
            item.optional("resolution"), item.getValue("count").jsonPrimitive.int.also { require(it >= 1) },
            item.instant("updated_at"), item.optionalInstant("read_at"), item.optionalInstant("resolved_at"),
            item.optionalInstant("snoozed_until"), item.getValue("unread").jsonPrimitive.boolean)
    }
    require(items.map { it.id }.distinct().size == items.size)
    return NotificationList(items, root.getValue("unread").jsonPrimitive.int.also { require(it >= 0) }, root.optional("before"))
}

fun decodeNotificationSettings(bytes: ByteArray): NotificationSettings {
    val root = notificationObject(bytes)
    val start = root.optional("quiet_start")?.also(::requireNotificationTime)
    val end = root.optional("quiet_end")?.also(::requireNotificationTime)
    require((start == null) == (end == null))
    return NotificationSettings(start, end,
        root.getValue("muted_topics").jsonArray.map { it.jsonPrimitive.content.identity() },
        root.getValue("revision").jsonPrimitive.long.also { require(it >= 0) },
        root.getValue("topics").jsonArray.map { NotificationTopic(it.jsonObject.text("topic").identity(), it.jsonObject.text("severity")) })
}

internal fun requireNotificationTime(value: String) {
    require(value.length == 5 && value[2] == ':' && value.take(2).all(Char::isDigit) && value.takeLast(2).all(Char::isDigit))
    require(value.take(2).toInt() in 0..23 && value.takeLast(2).toInt() in 0..59)
}

fun notificationSettingsBody(draft: NotificationSettingsDraft, current: NotificationSettings): ByteArray {
    if (draft.quiet) { requireNotificationTime(draft.start); requireNotificationTime(draft.end) }
    require(draft.mutedTopics.all { muted -> current.topics.any { it.topic == muted } })
    return buildJsonObject {
        put("quiet_start", if (draft.quiet) JsonPrimitive(draft.start) else JsonNull)
        put("quiet_end", if (draft.quiet) JsonPrimitive(draft.end) else JsonNull)
        put("muted_topics", JsonArray(draft.mutedTopics.distinct().map(::JsonPrimitive)))
        put("revision", current.revision)
    }.toString().encodeToByteArray()
}
