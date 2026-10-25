package assistant.mobile.android

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalConfiguration
import assistant.mobile.core.GeneratedStrings
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

internal fun chatMessageMoment(value: String, timezone: String, includeDate: Boolean = false): String = runCatching {
    val zone = ZoneId.of(timezone)
    val moment = Instant.parse(value).atZone(zone)
    val today = Instant.now().atZone(zone).toLocalDate()
    val pattern = if (includeDate && moment.toLocalDate() != today) "d MMM, HH:mm" else "HH:mm"
    moment.format(DateTimeFormatter.ofPattern(pattern, Locale.getDefault()))
}.getOrDefault(value)

internal fun chatMessageDay(value: String, timezone: String): LocalDate? = runCatching {
    Instant.parse(value).atZone(ZoneId.of(timezone)).toLocalDate()
}.getOrNull()

@Composable
internal fun chatMessageDayLabel(day: LocalDate, timezone: String): String {
    val locale = LocalConfiguration.current.locales[0]
    val today = Instant.now().atZone(ZoneId.of(timezone)).toLocalDate()
    return when (day) {
        today -> localized(GeneratedStrings.Day.today)
        today.minusDays(1) -> localized(GeneratedStrings.Day.yesterday)
        else -> day.format(DateTimeFormatter.ofPattern(if (day.year == today.year) "d MMMM" else "d MMMM yyyy", locale))
    }
}
