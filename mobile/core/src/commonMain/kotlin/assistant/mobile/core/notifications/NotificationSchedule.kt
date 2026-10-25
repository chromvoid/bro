package assistant.mobile.core.notifications

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.time.Instant

/** Native reminders are authority of one login, never merely of the person or installation. */
@Serializable
data class NotificationSession(
    val instance: String,
    val origin: String,
    val userId: String,
    val deviceId: String,
    val sessionId: String,
    val expiresAt: Long,
    val previews: Boolean = false,
) {
    fun active(now: Long): Boolean = now < expiresAt
    fun sameSession(other: NotificationSession): Boolean = instance == other.instance && origin == other.origin &&
        userId == other.userId && deviceId == other.deviceId && sessionId == other.sessionId
}

@Serializable
data class NotificationSchedule(
    @SerialName("snapshot_id") val snapshotId: String,
    @SerialName("server_now") val serverNow: String,
    @SerialName("expires_at") val expiresAt: String,
    val items: List<NotificationScheduleItem>,
)

@Serializable
data class NotificationScheduleItem(
    val key: String,
    val revision: Long,
    @SerialName("due_at") val dueAt: String,
    val title: String,
    val body: String,
    val link: String?,
)

@Serializable
sealed interface NotificationDestination {
    @Serializable data class Task(val id: String) : NotificationDestination
    @Serializable data class Timer(val id: String? = null) : NotificationDestination
    @Serializable data class Reminder(val id: String? = null) : NotificationDestination
    @Serializable data class Message(val id: String) : NotificationDestination
    @Serializable data object Notifications : NotificationDestination
    @Serializable data class Screen(val path: String) : NotificationDestination
}

/** Parse the app's route grammar, without passing unchecked URLs into Android navigation. */
fun notificationDestination(link: String?): NotificationDestination? {
    if (link == null || !link.startsWith('/') || link.startsWith("//") ||
        link.any { it != '/' && it != '_' && it != '-' && it !in 'A'..'Z' && it !in 'a'..'z' && it !in '0'..'9' }) return null
    val parts = link.removePrefix("/").split('/')
    if (parts.any { it.isEmpty() }) return null
    return when {
        parts.size == 2 && parts[0] == "tasks" -> NotificationDestination.Task(parts[1])
        parts.size == 2 && parts[0] == "timers" -> NotificationDestination.Timer(parts[1])
        parts.size == 2 && parts[0] == "reminders" -> NotificationDestination.Reminder(parts[1])
        parts.size == 3 && parts[0] == "assistant" && parts[1] == "m" -> NotificationDestination.Message(parts[2])
        link == "/timers" -> NotificationDestination.Timer()
        link == "/notifications" -> NotificationDestination.Notifications
        else -> NotificationDestination.Screen(link)
    }
}

enum class NotificationPermission { Granted, Denied, NotDetermined }

data class NotificationDeviceState(
    val permission: NotificationPermission = NotificationPermission.NotDetermined,
    val enabled: Boolean = false,
    val exactAlarmsAllowed: Boolean = false,
    val schedulePending: Boolean = false,
)

@Serializable
data class NotificationScheduleAck(val sessionId: String, val snapshotId: String, val installed: List<String>)

@Serializable
data class InstalledNotificationAlarm(
    val token: String,
    val key: String,
    val revision: Long,
    val id: Int,
    val fireAt: Long,
    val title: String,
    val body: String,
    val destination: NotificationDestination,
)

@Serializable
data class DeliveredNotificationOccurrence(
    val key: String,
    val revision: Long,
    val token: String = "",
    val destination: NotificationDestination? = null,
)

data class NotificationOpen(
    val session: NotificationSession,
    val destination: NotificationDestination,
    val key: String,
    val revision: Long,
    val token: String,
)

@Serializable
data class NotificationAlarmSnapshot(
    val session: NotificationSession? = null,
    val enabled: Boolean = false,
    val installed: List<InstalledNotificationAlarm> = emptyList(),
    val delivered: List<DeliveredNotificationOccurrence> = emptyList(),
    val pendingAck: NotificationScheduleAck? = null,
)

/** Persistence and OS calls have explicit success receipts; an attempted alarm is never ACKed. */
interface NotificationAlarmBackend {
    fun save(snapshot: NotificationAlarmSnapshot): Boolean
    fun arm(alarm: InstalledNotificationAlarm): Boolean
    fun cancel(alarm: InstalledNotificationAlarm)
    fun cancelDisplayed()
}

interface NotificationAlarmPlatform {
    fun state(): NotificationDeviceState
    fun bind(session: NotificationSession)
    fun clear()
    fun setEnabled(enabled: Boolean)
    fun install(snapshot: NotificationSchedule): List<String>
    fun pendingAck(): NotificationScheduleAck?
    fun acknowledged(snapshotId: String)
}

/** The persisted snapshot is the only authority an alarm intent may consume. */
class NotificationAlarmEngine(
    private val backend: NotificationAlarmBackend,
    initial: NotificationAlarmSnapshot = NotificationAlarmSnapshot(),
) {
    var snapshot: NotificationAlarmSnapshot = initial
        private set

    fun bind(next: NotificationSession) {
        val previous = snapshot.session
        when {
            previous == null || !previous.sameSession(next) -> {
                clear()
                commit(NotificationAlarmSnapshot(session = next))
            }
            previous != next -> {
                if (next.expiresAt < previous.expiresAt || next.previews != previous.previews) cancelInstalled()
                commit(snapshot.copy(session = next))
            }
        }
    }

    fun setEnabled(enabled: Boolean) {
        if (!enabled) cancelInstalled()
        commit(snapshot.copy(enabled = enabled))
    }

    fun clear() {
        snapshot.installed.forEach(backend::cancel)
        backend.cancelDisplayed()
        commit(NotificationAlarmSnapshot())
    }

    fun replace(schedule: NotificationSchedule, now: Long, permitted: Boolean): List<String> {
        val session = snapshot.session ?: return emptyList()
        if (!session.active(now)) { clear(); return emptyList() }
        if (!snapshot.enabled || !permitted) { cancelInstalled(); return emptyList() }
        val candidates = requireNotNull(validate(schedule, session, now)) { "Invalid native notification schedule" }
        if (!cancelInstalled()) return emptyList()
        val installed = mutableListOf<InstalledNotificationAlarm>()
        for (alarm in candidates) {
            if (snapshot.delivered.any { it.key == alarm.key && it.revision == alarm.revision }) continue
            if (!backend.arm(alarm)) continue
            val accepted = installed + alarm
            if (!commit(snapshot.copy(installed = accepted))) {
                backend.cancel(alarm)
                return emptyList()
            }
            installed += alarm
        }
        val keys = installed.map { it.key }
        // Persist the exact receipt before a network request can outlive this process.
        if (!commit(snapshot.copy(pendingAck = NotificationScheduleAck(session.sessionId, schedule.snapshotId, keys)))) {
            cancelInstalled()
            return emptyList()
        }
        return keys
    }

    fun acknowledged(snapshotId: String) {
        if (snapshot.pendingAck?.snapshotId == snapshotId) commit(snapshot.copy(pendingAck = null))
    }

    /** BOOT_COMPLETED only restores this same session's still-future, accepted alarms. */
    fun restore(now: Long, verified: NotificationSession?, permitted: Boolean) {
        val owner = snapshot.session
        if (owner == null || verified == null || !owner.sameSession(verified) ||
            !owner.active(now) || !verified.active(now)) { clear(); return }
        if (!snapshot.enabled || !permitted) { cancelInstalled(); return }
        val ahead = snapshot.installed.filter { it.fireAt > now && it.fireAt <= minOf(owner.expiresAt, verified.expiresAt) }
        snapshot.installed.filterNot { it in ahead }.forEach(backend::cancel)
        val accepted = ahead.filter { backend.arm(it) }
        ahead.filterNot { it in accepted }.forEach(backend::cancel)
        commit(snapshot.copy(session = owner.copy(expiresAt = minOf(owner.expiresAt, verified.expiresAt)), installed = accepted,
            pendingAck = snapshot.pendingAck.takeIf { accepted.map { alarm -> alarm.token } == snapshot.installed.map { alarm -> alarm.token } }))
    }

    /** Commit consumption before notification posting, so retries and stale intents never ring twice. */
    fun consume(token: String, now: Long, verified: NotificationSession?, permitted: Boolean): InstalledNotificationAlarm? {
        val owner = snapshot.session ?: return null
        if (verified == null || !owner.sameSession(verified) || !owner.active(now) || !verified.active(now)) {
            clear(); return null
        }
        val alarm = snapshot.installed.firstOrNull { it.token == token } ?: return null
        if (!snapshot.enabled || !permitted || now < alarm.fireAt) return null
        val occurrence = DeliveredNotificationOccurrence(alarm.key, alarm.revision, alarm.token, alarm.destination)
        if (snapshot.delivered.any { it.key == alarm.key && it.revision == alarm.revision }) return null
        if (!commit(snapshot.copy(installed = snapshot.installed.filterNot { it.token == token },
                delivered = snapshot.delivered + occurrence))) return null
        return alarm
    }

    private fun cancelInstalled(): Boolean {
        snapshot.installed.forEach(backend::cancel)
        return commit(snapshot.copy(installed = emptyList(), pendingAck = null))
    }

    private fun commit(next: NotificationAlarmSnapshot): Boolean {
        if (!backend.save(next)) {
            (snapshot.installed + next.installed).distinctBy { it.token }.forEach(backend::cancel)
            backend.cancelDisplayed()
            snapshot = NotificationAlarmSnapshot()
            backend.save(snapshot)
            return false
        }
        snapshot = next
        return true
    }

    private fun validate(schedule: NotificationSchedule, session: NotificationSession, now: Long): List<InstalledNotificationAlarm>? {
        if (!uuid(schedule.snapshotId) || schedule.items.size > 60) return null
        val serverNow = millis(schedule.serverNow) ?: return null
        val expires = millis(schedule.expiresAt) ?: return null
        if (expires <= serverNow || expires > session.expiresAt) return null
        val seen = mutableSetOf<String>()
        return schedule.items.mapIndexedNotNull { index, item ->
            if (item.key.isEmpty() || item.key.encodeToByteArray().size > 256 || item.key.any(Char::isISOControl) ||
                !seen.add(item.key) || item.revision <= 0 || item.title.isEmpty() || item.title.length > 120 || item.body.length > 1000) return null
            val due = millis(item.dueAt) ?: return null
            if (due <= serverNow || due > expires || due - serverNow > Long.MAX_VALUE - now) return null
            val fireAt = now + due - serverNow
            if (fireAt > session.expiresAt) return@mapIndexedNotNull null
            val target = if (item.link == null) NotificationDestination.Notifications
                else notificationDestination(item.link) ?: return null
            val occurrence = item.key.split(':', limit = 3)
            val destination = when {
                target is NotificationDestination.Timer && occurrence.size == 3 && occurrence[0] == "timer" -> NotificationDestination.Timer(occurrence[1])
                target is NotificationDestination.Timer && occurrence.size == 3 && occurrence[0] == "reminder" -> NotificationDestination.Reminder(occurrence[1])
                else -> target
            }
            InstalledNotificationAlarm("${session.sessionId}/${schedule.snapshotId}/$index", item.key, item.revision,
                item.key.hashCode() and Int.MAX_VALUE, fireAt, item.title, item.body, destination)
        }
    }

    private fun millis(value: String): Long? = runCatching { Instant.parse(value).toEpochMilliseconds() }.getOrNull()
    private fun uuid(value: String): Boolean = value.length == 36 && value.indices.all { index ->
        if (index in setOf(8, 13, 18, 23)) value[index] == '-' else value[index] in '0'..'9' || value[index] in 'a'..'f'
    }
}
