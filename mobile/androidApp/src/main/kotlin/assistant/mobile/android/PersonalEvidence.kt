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
import assistant.mobile.core.personal.PersonalResolution
import assistant.mobile.core.personal.PersonalState
import assistant.mobile.core.personal.personalDuration
import assistant.mobile.core.personal.personalCoverageSeconds
import assistant.mobile.core.personal.personalSleepConflict
import assistant.mobile.design.ui.Spacing
import assistant.mobile.design.R as DesignR

/** Explanation follows the server's decision, including unavailable and unverified evidence. */
@Composable
internal fun PersonalResolutionEvidence(resolution: PersonalResolution, state: PersonalState) {
    var open by remember(resolution.fingerprint) { mutableStateOf(false) }
    TextButton(onClick = { open = !open }, modifier = Modifier.heightIn(min = 48.dp)
        .testTag("personal_resolution_toggle")) { Text(stringResource(DesignR.string.resolution_why)) }
    if (open) Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs), modifier = Modifier.testTag("personal_resolution_evidence")) {
        val conflict = personalSleepConflict(resolution)
        Text(if (conflict != null) personalSleepConflictLabel(conflict) else stringResource(when (resolution.status) {
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
        }), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (resolution.protocolVerified == false && resolution.selected.isNotEmpty())
            Text(stringResource(DesignR.string.resolution_protocol_unverified), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        val seconds = personalCoverageSeconds(resolution.coverage)
        Text(if (seconds == null) stringResource(DesignR.string.resolution_coverage_unknown)
            else stringResource(DesignR.string.resolution_coverage, personalDuration(seconds / 60, state.locale)),
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        resolution.selected.forEach { fact ->
            val source = fact.sourceName ?: fact.sourceId ?: stringResource(DesignR.string.resolution_manual)
            Text(source + (fact.startAt?.let { " · " + personalMoment(it, state.timezone, state.locale) } ?: ""),
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        PersonalQuality((resolution.protocolFlags + resolution.qualityFlags +
            resolution.sleepAnalysis?.episodes.orEmpty().flatMap { it.flags }).distinct())
    }
}

@Composable
internal fun personalSleepConflictLabel(reason: String?): String = stringResource(when (reason) {
    "sleep_report_correction_conflict" -> DesignR.string.resolution_reason_sleep_report_correction_conflict
    "sleep_report_epoch_conflict" -> DesignR.string.resolution_reason_sleep_report_epoch_conflict
    "sleep_report_conflict" -> DesignR.string.resolution_reason_sleep_report_conflict
    "same_source_overlap" -> DesignR.string.personal_sleep_overlap
    else -> DesignR.string.resolution_status_conflict
})

@Composable
internal fun PersonalQuality(flags: List<String>) {
    flags.forEach { flag -> Text(stringResource(when (flag) {
        "protocol_verified" -> DesignR.string.record_quality_flags_protocol_verified
        "protocol_unverified" -> DesignR.string.record_quality_flags_protocol_unverified
        "invalid" -> DesignR.string.record_quality_flags_invalid
        "hrv_statistic_unknown" -> DesignR.string.record_quality_flags_hrv_statistic_unknown
        "stress_scale_unknown" -> DesignR.string.record_quality_flags_stress_scale_unknown
        "partial_day" -> DesignR.string.record_quality_flags_partial_day
        "partial" -> DesignR.string.record_quality_flags_partial
        "incomplete" -> DesignR.string.record_quality_flags_incomplete
        "identity_collision" -> DesignR.string.record_quality_flags_identity_collision
        "sparse" -> DesignR.string.record_quality_flags_sparse
        "missing" -> DesignR.string.record_quality_flags_missing
        "gap" -> DesignR.string.record_quality_flags_gap
        "out_of_range" -> DesignR.string.record_quality_flags_out_of_range
        "ambiguous_layout" -> DesignR.string.record_quality_flags_ambiguous_layout
        "temperature_layout_unconfirmed" -> DesignR.string.record_quality_flags_temperature_layout_unconfirmed
        "ambiguous_time" -> DesignR.string.record_quality_flags_ambiguous_time
        "sleep_taxonomy_unverified" -> DesignR.string.record_quality_flags_sleep_taxonomy_unverified
        "estimated_stages" -> DesignR.string.record_quality_flags_estimated_stages
        "epoch_unknown" -> DesignR.string.record_quality_flags_epoch_unknown
        "unit_unknown" -> DesignR.string.record_quality_flags_unit_unknown
        "sport_type_unknown" -> DesignR.string.record_quality_flags_sport_type_unknown
        "hr_cadence_unknown" -> DesignR.string.record_quality_flags_hr_cadence_unknown
        "home_current_wrapper_unavailable" -> DesignR.string.record_quality_flags_home_current_wrapper_unavailable
        "home_current_wrapper_future" -> DesignR.string.record_quality_flags_home_current_wrapper_future
        "home_current_reference_zero" -> DesignR.string.record_quality_flags_home_current_reference_zero
        "home_day_db_source_query_unqualified" -> DesignR.string.record_quality_flags_home_day_db_source_query_unqualified
        "home_day_db_hr_span_query_unqualified" -> DesignR.string.record_quality_flags_home_day_db_hr_span_query_unqualified
        "home_anchor_source_query_unqualified" -> DesignR.string.record_quality_flags_home_anchor_source_query_unqualified
        "home_anchor_sleep_timestamp_order" -> DesignR.string.record_quality_flags_home_anchor_sleep_timestamp_order
        "home_anchor_hourly_source_unqualified" -> DesignR.string.record_quality_flags_home_anchor_hourly_source_unqualified
        "home_anchor_hourly_timestamp_order" -> DesignR.string.record_quality_flags_home_anchor_hourly_timestamp_order
        "unknown_sleep_stage" -> DesignR.string.personal_sleep_quality_unknown_sleep_stage
        "duration_mismatch" -> DesignR.string.personal_sleep_quality_duration_mismatch
        else -> DesignR.string.record_quality_unknown
    }), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
}
