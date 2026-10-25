package assistant.mobile.android

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import assistant.mobile.core.GeneratedStrings
import assistant.mobile.core.food.formatFoodAmount
import assistant.mobile.core.personal.PersonalSleepAnalysis
import assistant.mobile.core.personal.PersonalSleepSession
import assistant.mobile.core.personal.personalDuration
import assistant.mobile.design.GeneratedColors
import assistant.mobile.design.R as DesignR
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt

private data class NightPart(val start: String, val end: String, val lane: String)
private val sleepLaneOrder = listOf("awake", "in_bed", "rem", "light", "deep", "sleep", "unknown", "conflict")
private fun analysisLane(state: String, stage: String?): String = when (state) {
    "sleep" -> stage?.takeIf { it in listOf("rem", "light", "deep") } ?: "sleep"
    "awake" -> if (stage == "awake_in_bed") "in_bed" else "awake"
    "conflict" -> "conflict"
    else -> "unknown"
}
private fun stageLane(stage: String): String = when (stage) {
    "sleeping" -> "sleep"
    "awake_in_bed" -> "in_bed"
    "awake", "out_of_bed" -> "awake"
    "rem", "light", "deep" -> stage
    else -> "unknown"
}

/** A state has its own row as well as a token colour, including known zero waking. */
@Composable
private fun PersonalSleepNightChart(start: String, end: String, parts: List<NightPart>, changes: List<String>, timezone: String, locale: String) {
    val first = runCatching { Instant.parse(start).toEpochMilli() }.getOrDefault(0L)
    val last = runCatching { Instant.parse(end).toEpochMilli() }.getOrDefault(first + 1)
    val span = (last - first).coerceAtLeast(1)
    fun at(value: String): Float = runCatching { (Instant.parse(value).toEpochMilli() - first).toFloat() / span }.getOrDefault(0f)
    fun length(part: NightPart): Long = runCatching { Duration.between(Instant.parse(part.start), Instant.parse(part.end)).toMillis() }.getOrDefault(0)
    val known = parts.filter { it.lane != "unknown" && it.lane != "conflict" }.sumOf(::length)
    val slept = parts.any { it.lane in listOf("sleep", "rem", "light", "deep") }
    val lanes = sleepLaneOrder.filter { lane -> parts.any { it.lane == lane } || (lane == "awake" && slept && known >= span - 60_000) }
    val scheme = MaterialTheme.colorScheme
    // The active scheme is already derived from web tokens; chart colours come from the same map.
    val colours = if (scheme.primary == GeneratedColors.dark.getValue("primary")) GeneratedColors.dark else GeneratedColors.light
    val staged = lanes.any { it in listOf("rem", "light", "deep") }
    val ticks = remember(start, end, timezone) {
        val steps = listOf(1, 2, 3, 4, 6, 12)
        val step = steps.firstOrNull { span.toDouble() / 3_600_000 / it <= 4 } ?: 24
        val zone = runCatching { ZoneId.of(timezone) }.getOrDefault(ZoneId.of("UTC"))
        buildList {
            var time = ((first + 899_999) / 900_000) * 900_000
            while (time < last) {
                val local = Instant.ofEpochMilli(time).atZone(zone)
                if (time > first && (local.hour * 60 + local.minute) % (step * 60) == 0) add(time)
                time += 900_000
            }
        }
    }
    Column(Modifier.fillMaxWidth().testTag("personal_sleep_night_chart"), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        lanes.forEach { lane ->
            val own = parts.filter { it.lane == lane }
            val label = sleepLaneLabel(lane, staged)
            val total = personalDuration((own.sumOf(::length).toDouble() / 60_000).roundToInt().toDouble(), locale)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Column(Modifier.width(104.dp)) {
                    Text(label, style = MaterialTheme.typography.labelMedium)
                    Text(total, style = MaterialTheme.typography.bodySmall)
                }
                val fill = when (lane) {
                    "awake" -> colours.getValue("warning-fill")
                    "in_bed" -> colours.getValue("warning-fill").copy(alpha = .5f)
                    "rem" -> colours.getValue("chart-3")
                    "light" -> colours.getValue("chart-2")
                    "deep" -> colours.getValue("primary")
                    "sleep" -> colours.getValue("primary").copy(alpha = if (staged) .5f else 1f)
                    "conflict" -> colours.getValue("destructive")
                    else -> colours.getValue("muted-foreground").copy(alpha = .4f)
                }
                Canvas(Modifier.weight(1f).height(30.dp).testTag("personal_sleep_lane_$lane")
                    .semantics { contentDescription = "$label · $total" }) {
                    drawRect(colours.getValue("muted"))
                    ticks.forEach { tick ->
                        val x = (tick - first).toFloat() / span * size.width
                        drawLine(colours.getValue("border"), Offset(x, 0f), Offset(x, size.height))
                    }
                    own.forEach { part ->
                        val x = at(part.start).coerceIn(0f, 1f) * size.width
                        val endX = at(part.end).coerceIn(0f, 1f) * size.width
                        if (lane == "conflict") {
                            var stripe = x
                            while (stripe < endX) {
                                drawRect(fill, Offset(stripe, 0f), Size(minOf(4.dp.toPx(), endX - stripe), size.height))
                                stripe += 8.dp.toPx()
                            }
                        } else drawRect(fill, Offset(x, 0f), Size((endX - x).coerceAtLeast(0f), size.height))
                    }
                }
            }
        }
        val turns = changes.filter { at(it) >= 0 && at(it) < 1 }
        if (turns.isNotEmpty()) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.width(104.dp)) {
                Text(stringResource(DesignR.string.personal_sleep_chart_turns), style = MaterialTheme.typography.labelMedium)
                Text(localized(GeneratedStrings.Personal.SleepChart.turnCount(turns.size.toDouble(), turns.size.toString())), style = MaterialTheme.typography.bodySmall)
            }
            Canvas(Modifier.weight(1f).height(26.dp).testTag("personal_sleep_turns")) {
                drawRect(colours.getValue("muted"))
                turns.forEach { mark -> val x = at(mark) * size.width
                    drawLine(colours.getValue("foreground"), Offset(x, size.height * .2f), Offset(x, size.height * .8f), 2.dp.toPx()) }
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(sleepTime(start, timezone, locale), style = MaterialTheme.typography.bodySmall)
            ticks.filter { (it - first).toDouble() / span > .2 && (it - first).toDouble() / span < .8 }.forEach {
                Text(sleepTime(Instant.ofEpochMilli(it).toString(), timezone, locale), style = MaterialTheme.typography.bodySmall)
            }
            Text(sleepTime(end, timezone, locale), style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
internal fun PersonalSleepHistoryAnalysis(analysis: PersonalSleepAnalysis, timezone: String, locale: String) {
    val summary = analysis.summary
    Text(when {
        summary.completeAsleepMinutes != null -> personalDuration(requireNotNull(summary.completeAsleepMinutes), locale)
        analysis.episodes.isNotEmpty() && summary.asleepMinutes == 0.0 && summary.awakeMinutes == 0.0 -> stringResource(DesignR.string.personal_period_only)
        analysis.episodes.isNotEmpty() -> stringResource(DesignR.string.personal_sleep_analysis_recognized, personalDuration(summary.asleepMinutes, locale))
        analysis.status == "conflict" -> stringResource(DesignR.string.personal_sleep_analysis_report_conflict)
        else -> stringResource(DesignR.string.personal_no_data)
    }, style = MaterialTheme.typography.titleLarge)
    analysis.episodes.forEachIndexed { index, episode ->
        Column(Modifier.fillMaxWidth().testTag("personal_sleep_night_$index"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(sleepIntervalLabel(episode.startAt, episode.endAt, timezone, locale), style = MaterialTheme.typography.titleSmall)
            PersonalSleepNightChart(episode.startAt, episode.endAt, episode.segments.map { NightPart(it.startAt, it.endAt, analysisLane(it.state, it.stage)) }, episode.positionChanges, timezone, locale)
            episode.night?.let { night ->
                night.latencyMinutes?.let { SleepNightValue(DesignR.string.personal_sleep_night_latency, personalDuration(it, locale), "latency") }
                night.efficiency?.let { SleepNightValue(DesignR.string.personal_sleep_night_efficiency, "${formatFoodAmount((it * 100).toString(), 0)}%", "efficiency") }
                night.awakenings?.let { count -> SleepNightValue(DesignR.string.personal_sleep_night_awakenings,
                    localized(GeneratedStrings.Personal.SleepNight.awakeningCount(count.toDouble(), count.toString(), personalDuration(night.wasoMinutes ?: 0.0, locale))), "awakenings") }
                night.turnsPerHour?.let { SleepNightValue(DesignR.string.personal_sleep_night_turns, stringResource(DesignR.string.personal_sleep_night_turn_rate, formatFoodAmount(it.toString(), 1)), "turns") }
                if (night.heartRateLowest != null && night.heartRateMean != null) SleepNightValue(DesignR.string.personal_sleep_night_heart_rate,
                    stringResource(DesignR.string.personal_sleep_night_heart_rate_value, formatFoodAmount(night.heartRateLowest.toString(), 0), formatFoodAmount(night.heartRateMean.toString(), 0)), "heart_rate")
                night.hrvMean?.let { SleepNightValue(DesignR.string.personal_sleep_night_hrv, stringResource(DesignR.string.personal_sleep_night_hrv_value, formatFoodAmount(it.toString(), 0)), "hrv") }
                SleepNightValue(DesignR.string.personal_sleep_night_midpoint, sleepTime(night.midpointAt, timezone, locale), "midpoint")
            }
            episode.classificationCoverage?.takeIf { it < 1 }?.let { Text(stringResource(DesignR.string.personal_sleep_analysis_coverage, (it * 100).roundToInt().toString())) }
            if (episode.unknownMinutes > 0) Text(stringResource(DesignR.string.personal_sleep_analysis_unclassified, personalDuration(episode.unknownMinutes, locale)))
            episode.bounds?.takeIf { episode.unknownMinutes > 0 }?.let { Text(stringResource(DesignR.string.personal_sleep_analysis_bounds, personalDuration(it.minMinutes, locale), personalDuration(it.maxMinutes, locale))) }
            if (episode.conflictMinutes > 0) Text(stringResource(DesignR.string.personal_sleep_analysis_conflict_subset, personalDuration(episode.conflictMinutes, locale)))
            PersonalQuality(episode.flags.filter { it in listOf("duration_mismatch", "sleep_taxonomy_unverified", "estimated_stages") })
            if (episode.forecast.status == "available" && episode.forecast.estimatedAsleepMinutes != null) {
                Text(stringResource(DesignR.string.personal_sleep_analysis_forecast, personalDuration(kotlin.math.floor(requireNotNull(episode.forecast.estimatedAsleepMinutes) / 5 + .5) * 5, locale)))
                Text(stringResource(DesignR.string.personal_sleep_analysis_forecast_basis, episode.forecast.trainingNights, personalDuration(episode.forecast.estimatedMissingMinutes ?: 0.0, locale)), style = MaterialTheme.typography.bodySmall)
            } else if (episode.forecast.reason != "complete_report") Text(sleepReason(episode.forecast.reason), style = MaterialTheme.typography.bodySmall)
            var evidence by remember(analysis.fingerprint, episode.startAt) { mutableStateOf(false) }
            TextButton(onClick = { evidence = !evidence }, modifier = Modifier.heightIn(min = 48.dp).testTag("personal_sleep_evidence_$index")) { Text(stringResource(DesignR.string.personal_sleep_analysis_evidence)) }
            if (evidence) {
                Text(stringResource(DesignR.string.personal_sleep_analysis_window, personalDuration(episode.windowMinutes ?: (Duration.between(Instant.parse(episode.startAt), Instant.parse(episode.endAt)).seconds.toDouble() / 60), locale)))
                if ("protocol_unverified" in episode.flags) Text(sleepReason("protocol_unverified"))
                episode.stageMinutes.forEach { (stage, minutes) -> Text("${sleepLaneLabel(stageLane(stage), true)}: ${personalDuration(minutes, locale)}") }
                episode.segments.forEach { segment ->
                    Text("${sleepIntervalLabel(segment.startAt, segment.endAt, timezone, locale)} · ${sleepLaneLabel(analysisLane(segment.state, segment.stage), true)}")
                    val refs = segment.selectedRefs.ifEmpty { segment.evidence }
                    Text(sleepReason(segment.reason) + (if (refs.isNotEmpty()) " · " + refs.map { it.sourceName ?: stringResource(DesignR.string.personal_unnamed_source) }.distinct().joinToString(", ") else ""), style = MaterialTheme.typography.bodySmall)
                }
                episode.forecast.validation?.let { Text(stringResource(DesignR.string.personal_sleep_analysis_validation, it.nights.toString(), it.maeMinutes.roundToInt().toString(), it.p90ErrorMinutes.roundToInt().toString()), style = MaterialTheme.typography.bodySmall) }
                episode.reports.forEach { report -> report.reportedAsleepMinutes?.let { Text(stringResource(DesignR.string.personal_sleep_analysis_original_total,
                    report.sourceName ?: stringResource(DesignR.string.personal_unnamed_source), personalDuration(it, locale)), style = MaterialTheme.typography.bodySmall) } }
            }
        }
    }
    analysis.rejectedReports.forEach { Text("${it.sourceName ?: stringResource(DesignR.string.personal_unnamed_source)} · ${historyReason(it.reason)}", style = MaterialTheme.typography.bodySmall) }
}

@Composable
internal fun PersonalSleepHistorySessions(sessions: List<PersonalSleepSession>, timezone: String, locale: String) {
    sessions.forEach { session ->
        Text(sleepIntervalLabel(session.startAt, session.endAt, timezone, locale), style = MaterialTheme.typography.titleSmall)
        Text(session.asleepMinutes?.toDoubleOrNull()?.let { personalDuration(it, locale) } ?: stringResource(DesignR.string.personal_period_only))
        session.sourceName?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        if (session.stages.isNotEmpty()) PersonalSleepNightChart(session.startAt, session.endAt,
            session.stages.map { NightPart(it.startAt, it.endAt, stageLane(it.stage)) }, session.positionChanges, timezone, locale)
    }
}

@Composable
private fun sleepLaneLabel(lane: String, staged: Boolean): String = stringResource(when (lane) {
    "awake" -> DesignR.string.personal_sleep_stage_awake
    "in_bed" -> DesignR.string.personal_sleep_chart_in_bed
    "rem" -> DesignR.string.personal_sleep_stage_rem
    "light" -> DesignR.string.personal_sleep_stage_light
    "deep" -> DesignR.string.personal_sleep_stage_deep
    "sleep" -> if (staged) DesignR.string.personal_sleep_stage_sleeping else DesignR.string.personal_metric_sleep
    "conflict" -> DesignR.string.personal_sleep_analysis_legend_conflict
    else -> DesignR.string.personal_sleep_analysis_legend_unknown
})

@Composable
private fun sleepReason(reason: String?): String = stringResource(when (reason) {
    "duration_mismatch" -> DesignR.string.personal_sleep_analysis_reason_duration_mismatch
    "sleep_taxonomy_unverified" -> DesignR.string.personal_sleep_analysis_reason_sleep_taxonomy_unverified
    "estimated_stages" -> DesignR.string.personal_sleep_analysis_reason_estimated_stages
    "protocol_unverified" -> DesignR.string.personal_sleep_analysis_reason_protocol_unverified
    "reported" -> DesignR.string.personal_sleep_analysis_reason_reported
    "agreement" -> DesignR.string.personal_sleep_analysis_reason_agreement
    "gap_filled" -> DesignR.string.personal_sleep_analysis_reason_gap_filled
    "source_priority" -> DesignR.string.personal_sleep_analysis_reason_source_priority
    "state_disagreement" -> DesignR.string.personal_sleep_analysis_reason_state_disagreement
    "stage_disagreement" -> DesignR.string.personal_sleep_analysis_reason_stage_disagreement
    "unclassified" -> DesignR.string.personal_sleep_analysis_reason_unclassified
    "complete_report" -> DesignR.string.personal_sleep_analysis_reason_complete_report
    "insufficient_history" -> DesignR.string.personal_sleep_analysis_reason_insufficient_history
    "insufficient_validation" -> DesignR.string.personal_sleep_analysis_reason_insufficient_validation
    "validation_failed" -> DesignR.string.personal_sleep_analysis_reason_validation_failed
    "unsupported_quality" -> DesignR.string.personal_sleep_analysis_reason_unsupported_quality
    "unsupported_gap" -> DesignR.string.personal_sleep_analysis_reason_unsupported_gap
    "conflicting_evidence" -> DesignR.string.personal_sleep_analysis_reason_conflicting_evidence
    else -> DesignR.string.personal_sleep_analysis_reason_unsupported_report
})

private fun sleepTime(value: String, timezone: String, locale: String): String = runCatching {
    Instant.parse(value).atZone(ZoneId.of(timezone)).format(DateTimeFormatter.ofPattern("HH:mm", Locale.forLanguageTag(locale)))
}.getOrDefault(value)
private fun sleepIntervalLabel(start: String, end: String, timezone: String, locale: String): String =
    "${personalMoment(start, timezone, locale)} — ${sleepTime(end, timezone, locale)}"

@Composable
private fun SleepNightValue(label: Int, value: String, tag: String) {
    Text(stringResource(label), style = MaterialTheme.typography.labelLarge)
    Text(value, modifier = Modifier.testTag("personal_sleep_night_$tag"))
}
