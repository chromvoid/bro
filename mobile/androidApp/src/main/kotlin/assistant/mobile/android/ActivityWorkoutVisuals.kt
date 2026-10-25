package assistant.mobile.android

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import assistant.mobile.design.ui.ProgressBar
import assistant.mobile.core.activity.ActivityController
import assistant.mobile.core.activity.ActivityState
import assistant.mobile.core.activity.TrackContextSummary
import assistant.mobile.core.activity.WorkoutTrackSummary
import assistant.mobile.core.activity.coherentTrackVersionPages
import assistant.mobile.core.activity.coherentWorkoutHeartRatePages
import assistant.mobile.core.activity.coherentWorkoutTrackPages
import assistant.mobile.core.activity.workoutHeartRateSamples
import assistant.mobile.core.personal.PersonalDisplayUnits
import assistant.mobile.core.personal.personalDistanceText
import assistant.mobile.design.R as DesignR
import java.text.NumberFormat
import java.util.Locale
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** Recorded history belongs to a workout/source pair, independently of a live ring workout. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ActivityWorkoutVisuals(state: ActivityState, controller: ActivityController, sourceName: String? = null) {
    if (!state.canPersonal || state.workoutId == null || state.workoutSourceId == null) return
    val key = listOf(state.namespace, state.workoutId, state.workoutSourceId)
    var heartRateOpen by remember(key) { mutableStateOf(false) }
    var trackOpen by remember(key) { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = { heartRateOpen = !heartRateOpen }, modifier = Modifier.heightIn(min = 48.dp)
                .testTag("activity_workout_heart_rate").semantics { selected = heartRateOpen }) {
                Text(stringResource(if (heartRateOpen) DesignR.string.ring_workout_hide_heart_rate else DesignR.string.ring_workout_show_heart_rate))
            }
            TextButton(onClick = { trackOpen = !trackOpen }, modifier = Modifier.heightIn(min = 48.dp)
                .testTag("activity_workout_track").semantics { selected = trackOpen }) {
                Text(stringResource(if (trackOpen) DesignR.string.workout_device_hide_track else DesignR.string.workout_device_show_track))
            }
        }
        if (heartRateOpen) ActivityRecordedHeartRate(state, sourceName) { scope.launch { controller.refresh() } }
        if (trackOpen) ActivityRecordedTrack(state, controller, key) { scope.launch { controller.refresh() } }
    }
}

@Composable
private fun ActivityRecordedHeartRate(state: ActivityState, sourceName: String?, retry: () -> Unit) {
    val query = state.heartRate
    val stored = query.data.orEmpty()
    val incomplete = query.stale || !coherentWorkoutHeartRatePages(stored)
    val pages = if (!incomplete && query.error == null) stored else emptyList()
    val samples = remember(pages) { workoutHeartRateSamples(pages) }
    Column(Modifier.fillMaxWidth().testTag("activity_heart_rate_history"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(DesignR.string.ring_workout_phone_source) + sourceName?.let { " · $it" }.orEmpty(),
            style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("activity_heart_rate_source"))
        if (query.loading) {
            Text(stringResource(DesignR.string.ring_workout_loading_history), style = MaterialTheme.typography.bodySmall)
            ProgressBar(modifier = Modifier.fillMaxWidth())
        }
        if (incomplete) ActivityVisualWarning(DesignR.string.workout_pulse_incomplete)
        else query.error?.let { ErrorMessage(it, "activity_heart_rate_error") }
        if (samples.isNotEmpty()) ActivityHeartRateChart(samples, state.timezone)
        else if (!query.loading && query.error == null && !incomplete) Text(stringResource(DesignR.string.ring_workout_no_samples))
        val gaps = pages.sumOf { page -> page.chunks.sumOf { it.gaps.size } }
        if (gaps > 0) Text(stringResource(DesignR.string.ring_workout_gaps, gaps.toString()),
            style = MaterialTheme.typography.bodySmall, color = activityPalette().getValue("warning"))
        if (pages.any { page -> page.chunks.any { chunk -> chunk.samples.any { it.quality == "unknown" } } }) {
            Text(stringResource(DesignR.string.ring_workout_quality_unknown), style = MaterialTheme.typography.bodySmall)
        }
        if (pages.isNotEmpty()) ActivityVisualDisclosure(stringResource(DesignR.string.workout_pulse_raw_samples), "activity_heart_rate_raw") {
            ActivityRawJson(JsonArray(pages.flatMap { page -> page.chunks.map { it.raw } }))
        }
        if (incomplete || query.error != null) ActivityVisualRetry(DesignR.string.workout_pulse_refresh, query.loading, retry)
    }
}

@Composable
private fun ActivityRecordedTrack(state: ActivityState, controller: ActivityController, cacheKey: Any, retry: () -> Unit) {
    val query = state.track
    val stored = query.data.orEmpty()
    val incomplete = query.stale || !coherentWorkoutTrackPages(stored)
    val pages = if (!incomplete && query.error == null) stored else emptyList()
    val summary = pages.firstOrNull()?.summary
    val units = state.overview.data?.profile?.displayUnits ?: PersonalDisplayUnits.Metric
    Column(Modifier.fillMaxWidth().testTag("activity_track_history"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (query.loading) ProgressBar(modifier = Modifier.fillMaxWidth())
        if (incomplete) ActivityVisualWarning(DesignR.string.workout_track_incomplete)
        else query.error?.let { ErrorMessage(it, "activity_track_error") }
        val segments = pages.flatMap { it.segments }
        if (segments.any { it.points.isNotEmpty() }) ActivityTrackMap(segments, cacheKey, controller::tile)
        else if (!query.loading && query.error == null && !incomplete) Text(stringResource(DesignR.string.workout_track_empty),
            style = MaterialTheme.typography.bodySmall)
        summary?.let { value ->
            Text(stringResource(DesignR.string.workout_track_gps_distance,
                personalDistanceText(value.valueMeters?.toString(), units, state.locale) ?: stringResource(DesignR.string.workout_track_unknown)),
                style = MaterialTheme.typography.titleSmall, modifier = Modifier.testTag("activity_gps_distance"))
            ActivityTrackContexts(value, units, state.locale, archived = false)
            ActivityVisualDisclosure(stringResource(DesignR.string.workout_track_current_details), "activity_track_raw") {
                Text("${value.algorithmVersion} · ${state.timezone}", style = MaterialTheme.typography.bodySmall)
                ActivityRawJson(value.raw)
                ActivityRawJson(JsonArray(pages.flatMap { it.chunks }))
            }
        }
        if (pages.lastOrNull()?.nextCursor != null) Text(stringResource(DesignR.string.workout_track_loaded_segments),
            style = MaterialTheme.typography.bodySmall)
        if (incomplete || query.error != null) ActivityVisualRetry(DesignR.string.workout_track_refresh, query.loading, retry)
        ActivityVisualDisclosure(stringResource(DesignR.string.workout_track_versions), "activity_gps_versions") {
            val versionsQuery = state.trackVersions
            val storedVersions = versionsQuery.data.orEmpty()
            val versionsFailed = versionsQuery.stale || !coherentTrackVersionPages(storedVersions)
            if (versionsQuery.loading) ProgressBar(modifier = Modifier.fillMaxWidth())
            if (versionsFailed) ActivityVisualWarning(DesignR.string.workout_track_versions_incomplete)
            else versionsQuery.error?.let { ErrorMessage(it, "activity_gps_versions_error") }
            val versions = if (versionsFailed || versionsQuery.error != null) emptyList() else storedVersions.flatMap { it.items }
            if (versions.isEmpty() && !versionsQuery.loading && versionsQuery.error == null && !versionsFailed) {
                Text(stringResource(DesignR.string.workout_track_no_versions), style = MaterialTheme.typography.bodySmall)
            }
            versions.forEach { version ->
                OutlinedCard(Modifier.fillMaxWidth().testTag("activity_gps_version_${version.revision}")) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(stringResource(DesignR.string.workout_track_version, version.revision.toString(),
                            stringResource(if (version.isLatest) DesignR.string.workout_track_latest else DesignR.string.workout_track_previous)),
                            style = MaterialTheme.typography.titleSmall, modifier = Modifier.semantics { heading() })
                        Text(stringResource(DesignR.string.workout_track_gps_distance,
                            personalDistanceText(version.snapshot.valueMeters?.toString(), units, state.locale) ?: stringResource(DesignR.string.workout_track_unknown)))
                        Text(stringResource(DesignR.string.workout_track_formula, version.snapshot.algorithmVersion))
                        ActivityTrackContexts(version.snapshot, units, state.locale, archived = true)
                        ActivityTrackVersionEvidence(version.snapshot.raw)
                        ActivityVisualDisclosure(stringResource(DesignR.string.workout_track_all_version_fields), "activity_gps_version_raw_${version.revision}") {
                            ActivityRawJson(version.snapshot.raw)
                        }
                    }
                }
            }
            if (versionsFailed || versionsQuery.error != null) ActivityVisualRetry(DesignR.string.workout_track_refresh_versions, versionsQuery.loading, retry)
        }
    }
}

@Composable
private fun ActivityTrackContexts(summary: WorkoutTrackSummary, units: PersonalDisplayUnits, locale: String, archived: Boolean) {
    listOf("running", "workout", "recovery").forEach { context ->
        summary.contexts[context]?.let { value ->
            OutlinedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(stringResource(when (context) {
                        "running" -> DesignR.string.workout_track_contexts_running
                        "workout" -> DesignR.string.workout_track_contexts_workout
                        else -> DesignR.string.workout_track_contexts_recovery
                    }), style = MaterialTheme.typography.titleSmall)
                    Text(personalDistanceText(value.distanceMeters?.toString(), units, locale) ?: stringResource(DesignR.string.workout_track_unknown_distance))
                    Text(stringResource(DesignR.string.workout_track_coordinates, value.usablePoints.toString(), value.reportedPoints.toString()))
                    Text(stringResource(DesignR.string.workout_track_coverage, activityVisualNumber(value.coverageSeconds), value.gapCount.toString()))
                    if (archived) {
                        Text(stringResource(DesignR.string.workout_track_segments,
                            value.usableSegments?.toString() ?: stringResource(DesignR.string.workout_track_unknown_segments)))
                        Text(stringResource(DesignR.string.workout_track_quality, activityTrackQuality(value)))
                    }
                    value.pausedPoints?.let { count -> Text(stringResource(if (archived) DesignR.string.workout_track_paused_coordinates
                        else DesignR.string.workout_track_paused_excluded, count.toString())) }
                    if (archived) value.pausedSeconds?.let { seconds -> Text(stringResource(DesignR.string.workout_track_pause_duration, activityVisualNumber(seconds))) }
                }
            }
        }
    }
}

@Composable
private fun ActivityTrackVersionEvidence(raw: JsonObject) {
    fun field(name: String) = (raw[name] as? JsonPrimitive)?.contentOrNull
    Text(stringResource(DesignR.string.workout_track_speed_limit, field("speed_limit_meters_per_second")?.let {
        stringResource(DesignR.string.workout_track_speed_value, it)
    } ?: stringResource(DesignR.string.workout_track_not_saved_masculine)), style = MaterialTheme.typography.bodySmall)
    Text(stringResource(DesignR.string.workout_track_max_interval, field("max_segment_seconds")?.let {
        stringResource(DesignR.string.workout_track_seconds_value, it)
    } ?: stringResource(DesignR.string.workout_track_not_saved_masculine)), style = MaterialTheme.typography.bodySmall)
    Text(stringResource(DesignR.string.workout_track_max_accuracy, field("max_accuracy_meters")?.let {
        stringResource(DesignR.string.workout_track_meters_value, it)
    } ?: stringResource(DesignR.string.workout_track_not_saved_feminine)), style = MaterialTheme.typography.bodySmall)
    Text(stringResource(DesignR.string.workout_track_pause_policy, when (val policy = field("pause_policy")) {
        "exclude_points_and_crossing_segments-v1" -> stringResource(DesignR.string.workout_track_exclude_paused)
        null -> stringResource(DesignR.string.workout_track_not_saved_neuter)
        else -> policy
    }), style = MaterialTheme.typography.bodySmall)
    (raw["report_ref"] as? JsonObject)?.let { report -> Text(stringResource(DesignR.string.workout_track_report_ref,
        (report["id"] as? JsonPrimitive)?.content.orEmpty(), (report["revision"] as? JsonPrimitive)?.content.orEmpty()),
        style = MaterialTheme.typography.bodySmall) }
    val inputs = raw["input_refs"] as? JsonArray ?: return
    ActivityVisualDisclosure(stringResource(DesignR.string.workout_track_input_count, inputs.size.toString()), "activity_gps_version_inputs") {
        inputs.forEach { item ->
            (item as? JsonObject)?.let { input -> Text(stringResource(DesignR.string.workout_track_input_ref,
                (input["id"] as? JsonPrimitive)?.content.orEmpty(), (input["sequence"] as? JsonPrimitive)?.content.orEmpty(),
                (input["hash"] as? JsonPrimitive)?.content.orEmpty()), style = MaterialTheme.typography.bodySmall) }
        }
        if (inputs.isEmpty()) Text(stringResource(DesignR.string.workout_track_no_inputs), style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun activityTrackQuality(value: TrackContextSummary): String {
    value.rawQuality?.let { return it }
    if (value.quality.isEmpty()) return stringResource(DesignR.string.workout_track_no_quality_limits)
    return value.quality.map { quality ->
        val resource = when (quality) {
            "gps_estimate" -> DesignR.string.workout_track_quality_flags_gps_estimate
            "paused" -> DesignR.string.workout_track_quality_flags_paused
            "partial" -> DesignR.string.workout_track_quality_flags_partial
            "no_usable_segments" -> DesignR.string.workout_track_quality_flags_no_usable_segments
            "missing_chunk" -> DesignR.string.workout_track_quality_flags_missing_chunk
            "gap" -> DesignR.string.workout_track_quality_flags_gap
            "approximate" -> DesignR.string.workout_track_quality_flags_approximate
            "unusable_fix" -> DesignR.string.workout_track_quality_flags_unusable_fix
            "sparse" -> DesignR.string.workout_track_quality_flags_sparse
            "implausible_segment" -> DesignR.string.workout_track_quality_flags_implausible_segment
            else -> null
        }
        resource?.let { stringResource(it) } ?: quality
    }.joinToString(", ")
}

@Composable
private fun ActivityVisualDisclosure(title: String, tag: String, content: @Composable ColumnScope.() -> Unit) {
    var open by remember(title, tag) { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        TextButton(onClick = { open = !open }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag(tag)
            .semantics { selected = open }) { Text(title) }
        if (open) content()
    }
}

@Composable
private fun ActivityRawJson(value: JsonElement) {
    SelectionContainer { Text(value.toString(), style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace)) }
}

@Composable
private fun ActivityVisualWarning(resource: Int) {
    Text(stringResource(resource), style = MaterialTheme.typography.bodySmall, color = activityPalette().getValue("warning"),
        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Assertive })
}

@Composable
private fun ActivityVisualRetry(resource: Int, loading: Boolean, retry: () -> Unit) {
    TextButton(onClick = retry, enabled = !loading, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(resource)) }
}

private fun activityVisualNumber(value: Double): String = NumberFormat.getIntegerInstance(Locale.forLanguageTag("ru-RU")).format(value)
