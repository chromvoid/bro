package assistant.mobile.android

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import assistant.mobile.core.personal.PersonalFact
import assistant.mobile.core.personal.PersonalResolution
import assistant.mobile.core.personal.personalCoverageSeconds
import assistant.mobile.core.personal.personalDuration
import assistant.mobile.core.personal.personalSleepConflict
import assistant.mobile.design.R as DesignR

@Composable
internal fun PersonalHistoryResolution(resolution: PersonalResolution, timezone: String, locale: String) {
    var open by remember(resolution.fingerprint) { mutableStateOf(false) }
    TextButton(onClick = { open = !open }, modifier = Modifier.heightIn(min = 48.dp).testTag("personal_history_resolution_toggle")) { Text(stringResource(DesignR.string.resolution_why)) }
    if (open) Column(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.testTag("personal_history_resolution_evidence")) {
        Text(personalSleepConflict(resolution)?.let { personalSleepConflictLabel(it) } ?: historyResolutionStatus(resolution.status))
        if (resolution.protocolVerified == false && resolution.selected.isNotEmpty()) Text(stringResource(DesignR.string.resolution_protocol_unverified))
        Text(personalCoverageSeconds(resolution.coverage)?.let { stringResource(DesignR.string.resolution_coverage, personalDuration(it / 60, locale)) } ?: stringResource(DesignR.string.resolution_coverage_unknown))
        resolution.measuredAt?.let { Text(stringResource(DesignR.string.resolution_measured_at, personalMoment(it, timezone, locale))) }
        if (resolution.explanationTruncated) Text(stringResource(DesignR.string.resolution_explanation_truncated))
        Text(stringResource(DesignR.string.resolution_selected) + (resolution.selectedCount?.let { ": $it" } ?: ""), style = MaterialTheme.typography.titleSmall)
        if (resolution.selected.isEmpty()) Text(stringResource(DesignR.string.resolution_no_selected))
        resolution.selected.forEach { HistoryFact(it, timezone, locale, false) }
        if (resolution.alternatives.isNotEmpty() || (resolution.alternativesCount ?: 0) > 0) {
            Text(stringResource(DesignR.string.resolution_alternatives) + (resolution.alternativesCount?.let { ": $it" } ?: ""), style = MaterialTheme.typography.titleSmall)
            resolution.alternatives.forEach { HistoryFact(it, timezone, locale, true) }
        }
        PersonalQuality((resolution.protocolFlags + resolution.qualityFlags).distinct())
    }
}

@Composable
private fun HistoryFact(fact: PersonalFact, timezone: String, locale: String, alternative: Boolean) {
    val source = fact.sourceName ?: stringResource(if (fact.sourceId == null) DesignR.string.resolution_manual else DesignR.string.personal_unnamed_source)
    Text(listOfNotNull(source, if (alternative) historyReason(fact.reason) else fact.contribution,
        fact.startAt?.let { personalMoment(it, timezone, locale) }, fact.endAt?.takeIf { it != fact.startAt }?.let { personalMoment(it, timezone, locale) }).joinToString(" · "), style = MaterialTheme.typography.bodySmall)
}

@Composable
internal fun historyResolutionStatus(status: String): String = stringResource(when (status) {
    "available" -> DesignR.string.resolution_status_available
    "no_data" -> DesignR.string.resolution_status_no_data
    "conflict" -> DesignR.string.resolution_status_conflict
    "needs_source_selection" -> DesignR.string.resolution_status_needs_source_selection
    "analysis_conflict" -> DesignR.string.resolution_status_analysis_conflict
    "analysis_policy_conflict" -> DesignR.string.resolution_status_analysis_policy_conflict
    "missing_in_latest_analysis" -> DesignR.string.resolution_status_missing_in_latest_analysis
    "unavailable" -> DesignR.string.resolution_status_unavailable
    "partial" -> DesignR.string.resolution_status_partial
    else -> DesignR.string.resolution_status_unknown
})

@Composable
internal fun historyReason(reason: String?): String {
    val resource = when (reason) {
        "excluded" -> DesignR.string.resolution_reason_excluded
        "duplicate", "confirmed_duplicate" -> DesignR.string.resolution_reason_confirmed_duplicate
        "superseded" -> DesignR.string.resolution_reason_superseded
        "superseded_revision" -> DesignR.string.resolution_reason_superseded_revision
        "lower_priority" -> DesignR.string.resolution_reason_lower_priority
        "overlap" -> DesignR.string.resolution_reason_overlap
        "partial_overlap" -> DesignR.string.resolution_reason_partial_overlap
        "same_source_overlap" -> DesignR.string.resolution_reason_same_source_overlap
        "superseded_report" -> DesignR.string.resolution_reason_superseded_report
        "sleep_report_conflict" -> DesignR.string.resolution_reason_sleep_report_conflict
        "sleep_report_epoch_conflict" -> DesignR.string.resolution_reason_sleep_report_epoch_conflict
        "sleep_report_correction_conflict" -> DesignR.string.resolution_reason_sleep_report_correction_conflict
        "daily_total", "daily_total_alternative" -> DesignR.string.resolution_reason_daily_total
        "needs_source_selection", "source_selection_required" -> DesignR.string.resolution_reason_source_selection_required
        "incompatible_semantics" -> DesignR.string.resolution_reason_incompatible_semantics
        "cross_day_interval", "day_crossing_interval" -> DesignR.string.resolution_reason_day_crossing_interval
        "open_interval" -> DesignR.string.resolution_reason_open_interval
        "counter_baseline_unknown" -> DesignR.string.resolution_reason_counter_baseline_unknown
        "unknown_semantics" -> DesignR.string.resolution_reason_unknown_semantics
        "older_measurement" -> DesignR.string.resolution_reason_older_measurement
        "different_method" -> DesignR.string.resolution_reason_different_method
        "future_observation" -> DesignR.string.resolution_reason_future_observation
        "invalid_quality" -> DesignR.string.resolution_reason_invalid_quality
        "invalid_observation" -> DesignR.string.resolution_reason_invalid_observation
        "lineage_conflict" -> DesignR.string.resolution_reason_lineage_conflict
        "counter_epoch_required" -> DesignR.string.resolution_reason_counter_epoch_required
        "missing_value" -> DesignR.string.resolution_reason_missing_value
        "counter_baseline" -> DesignR.string.resolution_reason_counter_baseline
        "counter_time_conflict" -> DesignR.string.resolution_reason_counter_time_conflict
        "counter_reset" -> DesignR.string.resolution_reason_counter_reset
        "interval_outside_period" -> DesignR.string.resolution_reason_interval_outside_period
        "simultaneous_measurements" -> DesignR.string.resolution_reason_simultaneous_measurements
        "unsupported_semantics" -> DesignR.string.resolution_reason_unsupported_semantics
        "unknown_activity_definition" -> DesignR.string.resolution_reason_unknown_activity_definition
        "equal_priority_points" -> DesignR.string.resolution_reason_equal_priority_points
        "lower_priority_point_source" -> DesignR.string.resolution_reason_lower_priority_point_source
        "equal_priority_sleep_source" -> DesignR.string.resolution_reason_equal_priority_sleep_source
        "lower_priority_sleep_report" -> DesignR.string.resolution_reason_lower_priority_sleep_report
        "equal_priority_overlap" -> DesignR.string.resolution_reason_equal_priority_overlap
        "lower_priority_overlap" -> DesignR.string.resolution_reason_lower_priority_overlap
        "incompatible_definition" -> DesignR.string.resolution_reason_incompatible_definition
        "older_analysis" -> DesignR.string.resolution_reason_older_analysis
        "lower_priority_analysis" -> DesignR.string.resolution_reason_lower_priority_analysis
        "conflicting_source_policies" -> DesignR.string.resolution_reason_conflicting_source_policies
        "not_selected", null -> DesignR.string.resolution_reason_not_selected
        else -> null
    }
    return resource?.let { stringResource(it) } ?: reason.orEmpty()
}
