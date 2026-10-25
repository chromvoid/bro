package assistant.mobile.android

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import assistant.mobile.core.GeneratedStrings
import assistant.mobile.core.SessionLease
import assistant.mobile.core.places.PlaceTimelineEntry
import assistant.mobile.core.places.PlacesController
import assistant.mobile.core.places.PlacesQuery
import assistant.mobile.core.places.timeline
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.launch

@Composable
internal fun PlacesScreen(controller: PlacesController, lease: SessionLease, onDay: (String) -> Unit) {
    val observed by controller.state.collectAsStateWithLifecycle()
    val state = observed?.takeIf { it.namespace == lease.namespace }
    val scope = rememberCoroutineScope()
    LaunchedEffect(controller, lease) { controller.activate() }
    DisposableEffect(controller, lease) { onDispose { controller.deactivate() } }
    val query = state?.days
    RefreshableScreen("screen_Places", title = localized(GeneratedStrings.Places.title),
        isRefreshing = query?.let { it.loading && it.data != null } == true,
        onRefresh = { scope.launch { controller.refresh() } }) {
        if (query == null) StatusMessage(GeneratedStrings.State.loading, "places_loading")
        else {
            PlacesQueryStatus(query, "places") { scope.launch { controller.refresh() } }
            query.data?.let { days ->
                if (days.days.isEmpty()) {
                    Text(localized(GeneratedStrings.Places.emptyTitle), style = MaterialTheme.typography.titleMedium)
                    Text(localized(GeneratedStrings.Places.emptyText))
                } else OutlinedCard(Modifier.fillMaxWidth().testTag("places_days")) {
                    Column {
                        days.days.forEachIndexed { index, day ->
                            if (index > 0) HorizontalDivider()
                            TextButton(onClick = { onDay(day.date) }, modifier = Modifier.fillMaxWidth()
                                .heightIn(min = 48.dp).testTag("places_day_${day.date}")) {
                                Text(placesDayLabel(day.date, days.timezone, localized(GeneratedStrings.Day.today),
                                    localized(GeneratedStrings.Day.yesterday), localized(GeneratedStrings.Day.tomorrow)), modifier = Modifier.fillMaxWidth())
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun PlaceDayScreen(controller: PlacesController, lease: SessionLease, date: String, onBack: () -> Unit) {
    val observed by controller.state.collectAsStateWithLifecycle()
    val state = observed?.takeIf { it.namespace == lease.namespace && it.selectedDate == date }
    val scope = rememberCoroutineScope()
    LaunchedEffect(controller, lease, date) { controller.activate(date) }
    DisposableEffect(controller, lease) { onDispose { controller.deactivate() } }
    val query = state?.day
    RefreshableScreen("screen_PlaceDay", title = placesFullDayLabel(date),
        isRefreshing = query?.let { it.loading && it.data != null } == true,
        onRefresh = { scope.launch { controller.refresh() } }, onBack = onBack) {
        if (query == null) StatusMessage(GeneratedStrings.State.loading, "places_day_loading")
        else {
            PlacesQueryStatus(query, "places_day") { scope.launch { controller.refresh() } }
            query.data?.let { day ->
                val entries = timeline(day)
                if (entries.isEmpty()) Text(localized(GeneratedStrings.Places.dayEmptyTitle))
                if (day.track.isNotEmpty()) PlaceMap(day, lease.namespace, controller::tile)
                if (entries.isNotEmpty()) OutlinedCard(Modifier.fillMaxWidth().testTag("places_timeline")) {
                    Column {
                        entries.forEachIndexed { index, entry ->
                            if (index > 0) HorizontalDivider()
                            Column(Modifier.fillMaxWidth().padding(16.dp).testTag("places_entry_$index"),
                                verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                when (entry) {
                                    is PlaceTimelineEntry.Stay -> {
                                        val stay = entry.stay
                                        Text(stay.place ?: localized(GeneratedStrings.Places.unnamed),
                                            style = MaterialTheme.typography.titleMedium,
                                            modifier = Modifier.semantics { heading() }.testTag("places_stay_$index"))
                                        Text(placesSpan(stay.from, stay.to, day.timezone) + " · " + placeDuration(stay.minutes),
                                            style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("places_stay_details_$index"))
                                    }
                                    is PlaceTimelineEntry.Move -> Text(placesSpan(entry.move.from, entry.move.to, day.timezone) +
                                        " · " + placeDistance(entry.move.distanceMeters), style = MaterialTheme.typography.bodySmall,
                                        modifier = Modifier.testTag("places_move_$index"))
                                }
                            }
                        }
                    }
                }
                if (day.track.isEmpty()) PlacesAttribution()
            }
        }
    }
}

@Composable
internal fun <T> PlacesQueryStatus(query: PlacesQuery<T>, tag: String, retry: () -> Unit) {
    if (query.loading && query.data == null) StatusMessage(GeneratedStrings.State.loading, "${tag}_loading")
    if (query.error != null) {
        if (query.data != null) StatusMessage(GeneratedStrings.State.refreshFailed, "${tag}_refresh_failed")
        else query.error?.let { ErrorMessage(it, "${tag}_error") }
        TextButton(onClick = retry, modifier = Modifier.heightIn(min = 48.dp).testTag("${tag}_retry")) {
            Text(localized(GeneratedStrings.Common.retry))
        }
    }
}

private val placesLocale = Locale.forLanguageTag("ru-RU")
internal fun placesFullDayLabel(date: String): String = runCatching {
    LocalDate.parse(date).format(DateTimeFormatter.ofPattern("EEEE, d MMMM yyyy 'г.'", placesLocale))
}.getOrDefault(date)
internal fun placesDayLabel(date: String, timezone: String, todayLabel: String? = null, yesterdayLabel: String? = null,
    tomorrowLabel: String? = null): String = runCatching {
    val day = LocalDate.parse(date)
    val today = LocalDate.now(ZoneId.of(timezone))
    when {
        day == today && todayLabel != null -> todayLabel
        day == today.minusDays(1) && yesterdayLabel != null -> yesterdayLabel
        day == today.plusDays(1) && tomorrowLabel != null -> tomorrowLabel
        else -> day.format(DateTimeFormatter.ofPattern(if (day.year == today.year) "d MMMM" else "d MMMM yyyy 'г.'", placesLocale))
    }
}.getOrDefault(date)
@Composable
internal fun placesMoment(instant: String, timezone: String): String {
    val today = localized(GeneratedStrings.Day.today)
    val yesterday = localized(GeneratedStrings.Day.yesterday)
    val tomorrow = localized(GeneratedStrings.Day.tomorrow)
    return runCatching {
        val value = Instant.parse(instant).atZone(ZoneId.of(timezone))
        placesDayLabel(value.toLocalDate().toString(), timezone, today, yesterday, tomorrow) + ", " +
            value.format(DateTimeFormatter.ofPattern("HH:mm", placesLocale))
    }.getOrDefault(instant)
}
private fun placesSpan(from: String, to: String, timezone: String): String = runCatching {
    val format = DateTimeFormatter.ofPattern("HH:mm", placesLocale).withZone(ZoneId.of(timezone))
    format.format(Instant.parse(from)) + "–" + format.format(Instant.parse(to))
}.getOrDefault("$from–$to")

@Composable
private fun placeDuration(minutes: Int): String = localized(when {
    minutes < 60 -> GeneratedStrings.Places.minutes(minutes.toString())
    minutes % 60 != 0 -> GeneratedStrings.Places.hours((minutes / 60).toString(), (minutes % 60).toString())
    else -> GeneratedStrings.Places.hoursOnly((minutes / 60).toString())
})

@Composable
private fun placeDistance(meters: Int): String = localized(if (meters >= 1000) {
    // The web uses Number.toFixed(1): round the exact binary Double, without grouping.
    val number = BigDecimal(meters / 1000.0).setScale(1, RoundingMode.HALF_UP).toPlainString().replace('.', ',')
    GeneratedStrings.Places.km(number)
} else GeneratedStrings.Places.meters(meters.toString()))
