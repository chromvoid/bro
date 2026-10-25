package assistant.mobile.android

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import assistant.mobile.core.GeneratedStrings
import assistant.mobile.core.food.formatFoodAmount
import assistant.mobile.core.personal.PersonalContribution
import assistant.mobile.core.personal.PersonalSourceEntry
import assistant.mobile.core.personal.PersonalSourceRow
import assistant.mobile.core.personal.PersonalSourcesState
import assistant.mobile.design.GeneratedColors
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.abs
import kotlin.math.roundToInt

private val personalTimedSourceRows = setOf("steps", "active_minutes", "heart_rate", "spo2", "stress", "sleep", "hrv", "temperature")
private val personalVitalSourceMetrics = setOf("heart_rate", "hrv", "spo2", "temperature", "stress")

/** These shares and comparisons explain the server's choice; they do not change any reading. */
@Composable
internal fun PersonalSourcePolicyEvidence(row: PersonalSourceRow, state: PersonalSourcesState, candidates: List<PersonalSourceEntry>, onRefresh: () -> Unit) {
    val metric = row.metrics.first()
    val unit = state.combined.data?.catalog?.find { it.key == metric }?.unit
        ?: state.combined.data?.daily?.firstOrNull { it.metric.kind == metric }?.metric?.unit
    val unitLabel = personalSourceComparisonUnit(unit)
    val timed = row.key in personalTimedSourceRows
    val contributions = row.metrics.firstNotNullOfOrNull { key ->
        state.combined.data?.daily?.find { it.date == state.day && it.metric.kind == key }?.metric?.resolution?.contributions?.takeIf { it.isNotEmpty() }
    }.orEmpty().sortedWith(compareByDescending<PersonalContribution> { it.value?.toDoubleOrNull() ?: 0.0 }.thenByDescending { it.count })
    if (timed) {
        Text(if (state.day == state.today) localized(GeneratedStrings.Sources.today) else state.day, style = MaterialTheme.typography.titleSmall)
        if (contributions.isEmpty()) Text(localized(if (state.day == state.today) GeneratedStrings.Sources.todayNone else GeneratedStrings.Personal.noData))
        else {
            PersonalSourceSplitBar(contributions, state)
            val colours = personalSourceChartColours()
            Column(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.testTag("personal_policy_day_contributions")) {
                contributions.forEachIndexed { index, contribution ->
                    val name = personalPolicySourceName(contribution.sourceId, contribution.sourceName, state)
                    val value = contribution.value?.let { personalComparisonAmount(it, unitLabel, 0, state.locale) }
                        ?: (contribution.spans.take(3).joinToString(", ") {
                            personalSourceTime(it.startAt, state.timezone, state.locale) + "–" + personalSourceTime(it.endAt, state.timezone, state.locale)
                        } + if (contribution.spans.size > 3) " …" else "")
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Canvas(Modifier.padding(top = 5.dp).size(10.dp)) { drawCircle(colours[index % colours.size]) }
                        Text(listOf(name, value).filter { it.isNotBlank() }.joinToString(" · "), style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
    if (candidates.size > 1 && (timed || metric in personalVitalSourceMetrics)) PersonalSourceWeekComparisons(row, state, unitLabel, onRefresh)
}

/** Day boundaries use the account zone, so a DST day is allowed to be shorter or longer. */
@Composable
private fun PersonalSourceSplitBar(contributions: List<PersonalContribution>, state: PersonalSourcesState) {
    val boundaries = runCatching {
        val day = LocalDate.parse(state.day)
        val zone = ZoneId.of(state.timezone)
        day.atStartOfDay(zone).toInstant().toEpochMilli() to day.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
    }.getOrNull() ?: return
    val first = boundaries.first
    val span = (boundaries.second - first).toDouble().takeIf { it > 0 } ?: return
    val colours = personalSourceChartColours()
    val background = MaterialTheme.colorScheme.surfaceVariant
    Canvas(Modifier.fillMaxWidth().height(12.dp).testTag("personal_policy_day_split")) {
        val radius = CornerRadius(size.height / 2)
        drawRoundRect(background, cornerRadius = radius)
        contributions.forEachIndexed { index, contribution ->
            contribution.spans.forEach { segment ->
                val start = runCatching { Instant.parse(segment.startAt).toEpochMilli() }.getOrNull() ?: return@forEach
                val end = runCatching { Instant.parse(segment.endAt).toEpochMilli() }.getOrNull() ?: return@forEach
                val left = ((maxOf(start, first) - first) / span).coerceIn(0.0, 1.0)
                val right = ((minOf(end, boundaries.second) - first) / span).coerceIn(0.0, 1.0)
                if (right > left) drawRoundRect(colours[index % colours.size],
                    topLeft = Offset((left * size.width).toFloat(), 0f),
                    size = Size(((right - left).coerceAtLeast(.005) * size.width).toFloat().coerceAtMost(size.width * (1 - left).toFloat()), size.height),
                    cornerRadius = radius)
            }
        }
    }
}

@Composable
private fun PersonalSourceWeekComparisons(row: PersonalSourceRow, state: PersonalSourcesState, unitLabel: String, onRefresh: () -> Unit) {
    Text(localized(GeneratedStrings.Sources.compare), style = MaterialTheme.typography.titleSmall)
    val comparisons = state.comparison.data?.comparisons.orEmpty()
    PersonalQueryStatus(state.comparison, onRefresh)
    if (comparisons.isEmpty() && !state.comparison.loading && state.comparison.error == null)
        Text(localized(GeneratedStrings.Sources.noComparison), style = MaterialTheme.typography.bodySmall)
    Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.testTag("personal_policy_week_comparisons")) {
        comparisons.forEach { comparison ->
            val first = personalPolicySourceName(comparison.sourceIds.getOrNull(0), null, state)
            val second = personalPolicySourceName(comparison.sourceIds.getOrNull(1), null, state)
            OutlinedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(localized(GeneratedStrings.Sources.pair(first, second)), style = MaterialTheme.typography.titleSmall)
                    if (row.metrics.first() in personalVitalSourceMetrics) {
                        Text(localized(GeneratedStrings.Sources.meanDifference(personalComparisonAmount(comparison.meanAbsoluteDifference.toString(), unitLabel, 1, state.locale))),
                            style = MaterialTheme.typography.bodySmall)
                        Text(localized(GeneratedStrings.Sources.matches(comparison.count.toDouble(), comparison.count.toString())), style = MaterialTheme.typography.bodySmall)
                        if (comparison.outsideReference > 0) Text(localized(GeneratedStrings.Sources.outside(comparison.outsideReference.toString())), style = MaterialTheme.typography.bodySmall)
                    } else {
                        val mine = comparison.totals.getOrElse(0) { 0.0 }
                        val theirs = comparison.totals.getOrElse(1) { 0.0 }
                        val share = if (theirs != 0.0) (abs(mine / theirs - 1) * 100).roundToInt() else 0
                        Text(localized(if (share < 1) GeneratedStrings.Sources.same else if (mine > theirs)
                            GeneratedStrings.Sources.more(first, share.toString()) else GeneratedStrings.Sources.less(first, share.toString())), style = MaterialTheme.typography.bodySmall)
                        Text(localized(GeneratedStrings.Sources.hours(comparison.count.toDouble(), comparison.count.toString())), style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
}

@Composable
private fun personalPolicySourceName(id: String?, fallback: String?, state: PersonalSourcesState): String =
    state.sources.data.orEmpty().find { it.id == id }?.let { personalSourceShortName(it, state) }
        ?: fallback ?: localized(GeneratedStrings.Sources.Device.custom)

private fun personalComparisonAmount(value: String, unit: String, fraction: Int, locale: String): String =
    listOf(formatFoodAmount(value, fraction, locale), unit).filter { it.isNotBlank() }.joinToString(" ")

@Composable
private fun personalSourceComparisonUnit(unit: String?): String = when (unit) {
    "bpm" -> localized(GeneratedStrings.Sources.Unit.bpm)
    "%" -> localized(GeneratedStrings.Sources.Unit.Key0025)
    "ms" -> localized(GeneratedStrings.Sources.Unit.ms)
    "°C" -> localized(GeneratedStrings.Sources.Unit.Key00b00043)
    "steps" -> localized(GeneratedStrings.Sources.Unit.steps)
    "min" -> localized(GeneratedStrings.Sources.Unit.min)
    "kcal" -> localized(GeneratedStrings.Sources.Unit.kcal)
    "m" -> localized(GeneratedStrings.Sources.Unit.m)
    "kg" -> localized(GeneratedStrings.Sources.Unit.kg)
    else -> ""
}

@Composable
private fun personalSourceChartColours(): List<androidx.compose.ui.graphics.Color> {
    val palette = if (MaterialTheme.colorScheme.primary == GeneratedColors.dark.getValue("primary")) GeneratedColors.dark else GeneratedColors.light
    return (1..5).map { palette.getValue("chart-$it") }
}
