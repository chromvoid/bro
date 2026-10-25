package assistant.mobile.core.timers

import assistant.mobile.core.AccountNamespace
import assistant.mobile.core.LocalizedString
import assistant.mobile.core.journal.Submission
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.plus
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlin.math.ceil
import kotlin.time.Instant
import kotlin.time.TimeSource

data class Timer(val id: String, val label: String?, val durationSeconds: Int, val dueAt: String,
    val revision: Long, val remainingSeconds: Double)
data class Reminder(val id: String, val text: String, val dueAt: String, val revision: Long, val taskId: String?)
data class TimerQuery<T>(val data: T? = null, val loading: Boolean = false, val error: LocalizedString? = null,
    val stale: Boolean = false)
data class FinishedTimer(val id: String, val label: String?)
@Serializable
data class ReminderDraft(val text: String = "", val at: String = "", val taskId: String? = null, val exactAt: String? = null)
data class TimerReceipt(val operationId: String, val kind: String, val timerIds: List<String>,
    val reminderIds: List<String>, val raw: JsonObject)
data class TimersState(val namespace: AccountNamespace, val canRead: Boolean, val canWrite: Boolean,
    val timezone: String, val running: TimerQuery<List<Timer>> = TimerQuery(),
    val reminders: TimerQuery<List<Reminder>> = TimerQuery(), val readAtMillis: Long = 0, val nowMillis: Long = 0,
    val finished: List<FinishedTimer> = emptyList(), val draft: ReminderDraft? = null, val draftReady: Boolean = false,
    val pending: List<Submission> = emptyList(), val receipt: TimerReceipt? = null,
    val busy: Boolean = false, val error: LocalizedString? = null) {
    fun remaining(timer: Timer): Int = remaining(timer, readAtMillis, nowMillis)
    fun remindersForTask(taskId: String) = reminders.data.orEmpty().filter { it.taskId == taskId }
}

val TIMER_PRESET_MINUTES = listOf(1, 5, 10, 15, 30)
data class ReminderPreset(val key: String, val at: String)
/** This is a display only count from the last server read; it never fires or settles a timer. */
fun remaining(timer: Timer, readAtMillis: Long, nowMillis: Long): Int =
    ceil(timer.remainingSeconds - (nowMillis - readAtMillis).coerceAtLeast(0) / 1000.0).coerceAtLeast(0.0).toInt()
fun timerClock(seconds: Int): String {
    val value = seconds.coerceAtLeast(0)
    val rest = (value % 60).toString().padStart(2, '0')
    val minutes = (value % 3600) / 60
    return if (value >= 3600) "${value / 3600}:${minutes.toString().padStart(2, '0')}:$rest" else "$minutes:$rest"
}
fun reminderLocalInput(instant: String, timezone: String): String =
    Instant.parse(instant).toLocalDateTime(TimeZone.of(timezone)).toString()
fun reminderInstant(localInput: String, timezone: String): String =
    LocalDateTime.parse(localInput).toInstant(TimeZone.of(timezone)).toString()
/** Preserve the chosen occurrence of a repeated local time until the person changes the field. */
fun reminderInstant(draft: ReminderDraft, timezone: String): String {
    val exact = draft.exactAt?.let(Instant::parse)
    if (exact != null && reminderLocalInput(exact.toString(), timezone) == draft.at) return exact.toString()
    return reminderInstant(draft.at, timezone)
}
/** Same three choices as web reminders.ts, interpreted in the account's calendar zone. */
fun reminderPresets(nowEpochMillis: Long, timezone: String): List<ReminderPreset> {
    val now = Instant.fromEpochMilliseconds(nowEpochMillis)
    val zone = TimeZone.of(timezone)
    val today = now.toLocalDateTime(zone).date
    val evening = LocalDateTime(today, LocalTime(19, 0)).toInstant(zone)
    val morning = LocalDateTime(today.plus(DatePeriod(days = 1)), LocalTime(9, 0)).toInstant(zone)
    return buildList {
        add(ReminderPreset("hour", Instant.fromEpochMilliseconds(nowEpochMillis + 3_600_000).toString()))
        if (evening.toEpochMilliseconds() - nowEpochMillis > 1_800_000) add(ReminderPreset("evening", evening.toString()))
        add(ReminderPreset("morning", morning.toString()))
    }
}
internal fun monotonicTimerClock(): () -> Long {
    val origin = TimeSource.Monotonic.markNow()
    return { origin.elapsedNow().inWholeMilliseconds }
}
