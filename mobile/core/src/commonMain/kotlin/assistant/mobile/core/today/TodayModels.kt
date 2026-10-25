package assistant.mobile.core.today

import assistant.mobile.core.AccountNamespace
import assistant.mobile.core.LocalizedString
import kotlin.math.ceil
import kotlin.math.abs
import kotlin.math.floor

/** Each source retains its own last successful response when a later read fails. */
data class TodayQuery<T>(
    val data: T? = null,
    val loading: Boolean = false,
    val error: LocalizedString? = null,
    val stale: Boolean = false,
) {
    fun loading(): TodayQuery<T> = copy(loading = true, error = null)
    fun loaded(value: T): TodayQuery<T> = copy(data = value, loading = false, error = null, stale = false)
    fun failed(reason: LocalizedString): TodayQuery<T> = copy(loading = false, error = reason, stale = data != null)
}

enum class TodayCard { Food, Tasks, Timers, Notifications, Personal, Steps, WeightHistory }

data class TodayState(
    val namespace: AccountNamespace,
    val day: String,
    val timezone: String,
    val nowMillis: Long,
    val scopes: List<String>,
    val locale: String = "ru",
    val food: TodayQuery<TodayFood>? = null,
    val tasks: TodayQuery<TodayTasks>? = null,
    val timers: TodayQuery<TodayTimers>? = null,
    val notifications: TodayQuery<TodayNotifications>? = null,
    val personal: TodayQuery<TodayPersonal>? = null,
    val steps: TodayQuery<TodayStepsOverride>? = null,
    val weightHistory: TodayQuery<TodayWeightHistory>? = null,
    val timerNowMillis: Long = nowMillis,
) {
    val refreshing: Boolean get() = food?.loading == true || tasks?.loading == true ||
        timers?.loading == true || notifications?.loading == true || personal?.loading == true ||
        steps?.loading == true || weightHistory?.loading == true
}

data class TodayFood(
    val date: String,
    val empty: Boolean,
    val energyKcal: String?,
    val energyComplete: Boolean,
    val targetKcal: String?,
    /** The authoritative signed remaining_kcal, never reconstructed from a partial total. */
    val balanceKcal: String?,
    val remainingComplete: Boolean,
) {
    val over: Boolean get() = (balanceKcal?.toDoubleOrNull() ?: 0.0) < 0
    val progress: Float? get() {
        val energy = energyKcal?.toDoubleOrNull() ?: return null
        val target = targetKcal?.toDoubleOrNull()?.takeIf { it > 0 } ?: return null
        return (energy / target).coerceIn(0.0, 1.0).toFloat()
    }
    val energyText: String get() = (if (!energyComplete && energyKcal != null) "≥ " else "") +
        formatTodayAmount(energyKcal, 0)
}

data class TodayTask(val id: String, val title: String, val dueOn: String?, val overdue: Boolean)
data class TodayTasks(val today: String, val timezone: String, val items: List<TodayTask>)
data class TodayTimer(val id: String, val label: String?, val remainingSeconds: Double) {
    fun remaining(readAtMillis: Long, nowMillis: Long): Long =
        ceil(remainingSeconds - (nowMillis - readAtMillis).coerceAtLeast(0) / 1000.0)
            .coerceAtLeast(0.0).toLong()

    fun clock(readAtMillis: Long, nowMillis: Long): String {
        val seconds = remaining(readAtMillis, nowMillis)
        val hours = seconds / 3600
        val minutes = seconds % 3600 / 60
        val rest = (seconds % 60).toString().padStart(2, '0')
        return if (hours > 0) "$hours:${minutes.toString().padStart(2, '0')}:$rest" else "$minutes:$rest"
    }
}
data class TodayTimers(val items: List<TodayTimer>, val readAtMillis: Long)
data class TodayNotification(val id: String, val title: String)
data class TodayNotifications(val items: List<TodayNotification>)
data class TodayMetric(
    val status: String,
    val value: String?,
    val unit: String,
    val target: String?,
    val complete: Boolean?,
    val measuredAt: String?,
    val partial: Boolean,
    val sources: List<TodayMetricSource> = emptyList(),
)
data class TodayMetricSource(val id: String?, val name: String?)
data class TodayPersonal(val date: String, val weight: TodayMetric, val steps: TodayMetric)
/** A manual override is evidence of an edit, not the resolved daily step total. */
data class TodayStepsOverride(val date: String, val active: Boolean, val value: String?)
data class TodayWeightHistory(val changeKg: String?)

/** Decimal strings stay strings until display. Missing values never pass through Number(null). */
fun formatTodayAmount(value: String?, fraction: Int = 1, locale: String = "ru"): String {
    require(fraction in 0..6)
    val raw = value?.trim()?.takeIf { it.isNotEmpty() && it.toDoubleOrNull()?.isFinite() == true } ?: return "—"
    val negative = raw.startsWith('-')
    val unsigned = raw.removePrefix("-").removePrefix("+")
    val pieces = unsigned.split('.')
    if (pieces.size > 2 || pieces.any { part -> part.any { it !in '0'..'9' } }) return "—"
    val integer = pieces[0].trimStart('0').ifEmpty { "0" }
    val decimal = pieces.getOrElse(1) { "" }
    var digits = (integer + decimal.take(fraction).padEnd(fraction, '0')).toCharArray()
    if (decimal.getOrNull(fraction)?.let { it >= '5' } == true) {
        var index = digits.lastIndex
        while (index >= 0 && digits[index] == '9') { digits[index] = '0'; index-- }
        if (index >= 0) digits[index] = digits[index] + 1 else digits = charArrayOf('1') + digits
    }
    val rounded = digits.concatToString().padStart(fraction + 1, '0')
    val whole = rounded.dropLast(fraction)
    val tail = if (fraction == 0) "" else rounded.takeLast(fraction).trimEnd('0')
    val russian = locale.startsWith("ru")
    val grouped = whole.reversed().chunked(3).joinToString(if (russian) "\u00a0" else ",").reversed()
    val sign = if (negative && rounded.any { it != '0' }) "-" else ""
    return sign + grouped + (if (tail.isEmpty()) "" else (if (russian) "," else ".") + tail)
}

/** The web's signedKilograms rounds a signed delta with Math.round before formatting it. */
fun formatTodayWeightChange(value: String, locale: String = "ru"): String {
    val change = value.toDoubleOrNull()?.takeIf { it.isFinite() } ?: return "—"
    val rounded = floor(change * 10 + 0.5) / 10
    val sign = if (rounded < 0) "−" else if (rounded > 0) "+" else ""
    return sign + formatTodayAmount(abs(rounded).toString(), 1, locale)
}
