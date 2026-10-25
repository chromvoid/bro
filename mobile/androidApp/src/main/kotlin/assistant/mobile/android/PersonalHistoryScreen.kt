package assistant.mobile.android

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import assistant.mobile.core.GeneratedStrings.PersonalHistory as HistoryStrings
import assistant.mobile.android.body.assets.AndroidBodyAssetRepository
import assistant.mobile.core.body.*
import assistant.mobile.core.food.formatFoodAmount
import assistant.mobile.core.personal.*
import assistant.mobile.design.R as DesignR
import java.time.Instant
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

private data class HistoryRecordSelection(val id: String, val metric: String, val sourceId: String?, val raw: JsonObject)

/** Range and journal state live in the controller; disclosures never outlive their account/range. */
@Composable
internal fun PersonalHistoryScreen(
    controller: PersonalHistoryController,
    lease: assistant.mobile.core.SessionLease,
    onBack: () -> Unit = {},
    initialMetric: String? = null,
    initialResultId: String? = null,
    initialPeriod: Int? = null,
    bodyPresentation: BodyPresentationController? = null,
    bodyAssets: AndroidBodyAssetRepository? = null,
    onOpenProfile: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val observed by controller.state.collectAsStateWithLifecycle()
    val state = observed?.takeIf { it.namespace == lease.namespace }
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    DisposableEffect(controller, lease, lifecycleOwner, initialMetric) {
        val owner = Any()
        var first = true
        fun activate() { controller.activate(if (first) initialMetric else null, owner); first = false }
        val observer = LifecycleEventObserver { _, event -> when (event) {
            Lifecycle.Event.ON_START -> activate()
            Lifecycle.Event.ON_STOP -> controller.deactivate(owner)
            else -> Unit
        } }
        lifecycleOwner.lifecycle.addObserver(observer)
        if (lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) activate()
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer); controller.deactivate(owner) }
    }
    var initialPeriodApplied by remember(lease.namespace, initialPeriod) { mutableStateOf(false) }
    LaunchedEffect(state?.namespace, initialPeriod, initialPeriodApplied) {
        if (state?.canRead == true && !initialPeriodApplied && initialPeriod in PERSONAL_HISTORY_PERIODS) {
            initialPeriodApplied = true
            controller.selectPeriod(requireNotNull(initialPeriod))
        }
    }
    val current = state
    var record by remember(current?.namespace, current?.metric, current?.from, current?.to) { mutableStateOf<HistoryRecordSelection?>(null) }
    var detail by remember(current?.namespace, current?.metric, current?.from, current?.to) { mutableStateOf<String?>(null) }
    var selectedAnalysisId by remember(current?.namespace, current?.metric, current?.from) { mutableStateOf<String?>(null) }
    var selectedResultId by remember(current?.namespace, current?.metric, current?.from) { mutableStateOf<String?>(null) }
    var initialResultOpened by remember(current?.namespace, initialResultId) { mutableStateOf(false) }
    LaunchedEffect(current?.derived?.data, initialResultId, initialResultOpened) {
        if (!initialResultOpened && initialResultId != null && current?.derived?.data?.days?.flatMap { it.scores }?.any { it.resultId == initialResultId } == true) {
            initialResultOpened = true; selectedResultId = initialResultId; detail = "derived"
            controller.derivedDetail(initialResultId)
        }
    }
    fun close() { detail = null; record = null; selectedAnalysisId = null; selectedResultId = null; controller.closeDetails() }
    BackHandler(enabled = detail != null) { close() }
    val refresh = { scope.launch { controller.refresh() }; Unit }
    key(current?.namespace, current?.metric, detail, record?.id) {
        RefreshableScreen(
            tag = "screen_PersonalHistory",
            title = if (detail == "versions") localized(HistoryStrings.Versions.title)
                else if (detail == "analysis") stringResource(DesignR.string.personal_archive_title)
                else if (detail == "derived") localized(HistoryStrings.Derived.title)
                else if (detail == "record") personalMetricLabel(record?.metric.orEmpty())
                else stringResource(if (current?.metric == "body") DesignR.string.personal_body_composition else DesignR.string.personal_history_title),
            isRefreshing = current?.let { it.history.loading && it.history.data != null || it.derived.loading && it.derived.data != null || it.resolved.loading && it.resolved.data != null } == true,
            onRefresh = refresh,
            modifier = modifier,
            onBack = { if (detail != null) close() else onBack() },
            refreshTag = "personal_history_pull_refresh",
        ) {
            if (current == null) Text(stringResource(DesignR.string.state_loading))
            else if (!current.canRead) UnavailableCard()
            else if (detail == "record") record?.let { selected ->
                Column(Modifier.testTag("personal_history_record_detail"), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    PersonalHistoryRecordEvidence(selected.raw, current.timezone, current.locale)
                    if (selected.metric in PERSONAL_SENSOR_KINDS || selected.metric in listOf("steps", "sleep")) {
                        OutlinedButton(onClick = { detail = "versions"; scope.launch { controller.versions(selected.id, selected.metric, selected.sourceId) } }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("personal_history_versions")) { Text(localized(HistoryStrings.Versions.title)) }
                    }
                }
            } else if (detail == "versions") {
                Column(Modifier.testTag("personal_history_record_versions"), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    PersonalQueryStatus(current.recordVersions) { record?.let { scope.launch { controller.versions(it.id, it.metric, it.sourceId) } } }
                    current.recordVersions.data?.let { PersonalHistoryVersions(it, current.timezone, current.locale, current.units) }
                }
            } else if (detail == "analysis") {
                PersonalQueryStatus(current.sourceAnalysis) { selectedAnalysisId?.let { scope.launch { controller.analysis(it) } } }
                current.sourceAnalysis.data?.let { PersonalHistorySourceAnalysis(it, current.timezone, current.locale) }
            } else if (detail == "derived") {
                Column(Modifier.testTag("personal_history_derived_detail"), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    PersonalQueryStatus(current.derivedResult) { selectedResultId?.let { scope.launch { controller.derivedDetail(it) } } }
                    current.derivedResult.data?.let { PersonalHistoryDerivedResult(it, current.timezone, current.locale) }
                }
            } else {
                PersonalHistoryRange(current, controller)
                if (current.metric == "derived_scores") {
                    PersonalHistoryDerived(current, controller) { resultId -> selectedResultId = resultId; detail = "derived"; scope.launch { controller.derivedDetail(resultId) } }
                } else {
                    PersonalHistoryOperationStatus(current, controller)
                    if (current.metric == "body") {
                        PersonalQueryStatus(current.history, refresh)
                        PersonalHistoryBody(current, lease, bodyPresentation, bodyAssets, onOpenProfile, onRecord = { selected -> record = selected; detail = "record" }, onAnalysis = { id ->
                            selectedAnalysisId = id; detail = "analysis"; scope.launch { controller.analysis(id) }
                        })
                    } else {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf("result" to DesignR.string.resolution_result, "all" to DesignR.string.resolution_all_sources).forEach { (mode, label) ->
                                OutlinedButton(onClick = { controller.setMode(mode) }, modifier = Modifier.weight(1f).heightIn(min = 48.dp)
                                    .testTag("personal_history_mode_$mode").semantics { selected = mode == current.mode }) { Text(stringResource(label)) }
                            }
                        }
                        val sensor = current.metric in PERSONAL_SENSOR_KINDS
                        if (sensor) PersonalQueryStatus(if (current.mode == "result") current.resolved else current.sensors, refresh)
                        else PersonalQueryStatus(current.history, refresh)
                        val days = (if (sensor) current.resolved.data?.daily else current.history.data?.daily).orEmpty()
                            .filter { current.metric == "all" || it.metric.kind == current.metric }.sortedByDescending { it.date }
                        if (current.mode == "result") days.filter { it.metric.resolution != null }.forEach { day -> PersonalHistoryDaily(day, current) }
                        if (current.metric == "weight") {
                            val points = current.displayedRecords.filter { !it.excluded && it.value?.toDoubleOrNull() != null }
                            if (points.size > 1) PersonalHistoryTrend(points.map { it.startAt to requireNotNull(it.value).toDouble() }, personalMetricLabel("weight"), gapDays = 7)
                        }
                        if (sensor) {
                            val samples = if (current.mode == "all") current.sensors.data?.items.orEmpty() else current.selectedSensors
                            samples.forEach { sample ->
                                Column(Modifier.fillMaxWidth().clickable { record = HistoryRecordSelection(sample.id, sample.metric, sample.sourceId, sample.raw); detail = "record" }
                                    .heightIn(min = 64.dp).padding(vertical = 12.dp).testTag("personal_history_record_${sample.id}"), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Text(personalSensorAmount(sample.value, sample.unit, current.units, current.locale) ?: stringResource(DesignR.string.personal_no_value), style = MaterialTheme.typography.titleMedium)
                                    if (sample.value == null && (sample.reportedMin != null || sample.reportedMax != null)) Text(localized(HistoryStrings.Sensor.rangeOnly))
                                    if (sample.reportedMin != null || sample.reportedMax != null) Text(stringResource(DesignR.string.ring_reported_range,
                                        personalSensorAmount(sample.reportedMin, sample.unit, current.units, current.locale) ?: "—", personalSensorAmount(sample.reportedMax, sample.unit, current.units, current.locale) ?: "—"))
                                    Text("${personalMoment(sample.startAt, current.timezone, current.locale)} · ${sample.sourceName}", style = MaterialTheme.typography.bodySmall)
                                    if (sample.endAt != sample.startAt) Text(stringResource(DesignR.string.ring_interval_end, personalMoment(sample.endAt, current.timezone, current.locale)))
                                    if (sample.excluded) Text(stringResource(DesignR.string.resolution_excluded))
                                    PersonalQuality(sample.quality)
                                }
                                if (current.canWrite) TextButton(onClick = { scope.launch { controller.exclude(sample) } }, enabled = !current.busy && current.pending.isEmpty(),
                                    modifier = Modifier.heightIn(min = 48.dp).testTag("personal_sensor_exclude_${sample.id}")) {
                                    Text(stringResource(if (sample.excluded) DesignR.string.resolution_restore else DesignR.string.resolution_exclude))
                                }
                                HorizontalDivider()
                            }
                            if (current.canWrite) Text(stringResource(DesignR.string.resolution_sensor_exclude_hint), style = MaterialTheme.typography.bodySmall)
                            if (samples.isEmpty() && (current.mode == "all" || days.isEmpty()) && !current.sensors.loading && current.sensors.error == null) Text(stringResource(DesignR.string.personal_history_empty))
                        } else {
                            current.displayedRecords.forEach { item -> HistoryObservation(item, current) {
                                record = HistoryRecordSelection(item.id, item.kind, item.sourceId, current.history.data?.evidenceById?.get(item.id) ?: observationEvidence(item)); detail = "record"
                            } }
                            if (current.displayedRecords.isEmpty() && days.isEmpty() && !current.history.loading && current.history.error == null) Text(stringResource(DesignR.string.personal_history_empty))
                        }
                    }
                    PersonalQueryStatus(current.raw, refresh)
                    current.raw.data?.let { PersonalHistoryRawReports(it, current.timezone, current.locale) }
                }
            }
        }
    }
}

@Composable
private fun PersonalHistoryRange(state: PersonalHistoryState, controller: PersonalHistoryController) {
    val scope = rememberCoroutineScope()
    PersonalHistoryChoice(state.metric, PERSONAL_HISTORY_METRICS.map { metric -> metric to when (metric) {
        "body" -> stringResource(DesignR.string.personal_body_composition)
        "derived_scores" -> stringResource(DesignR.string.derived_scores_title)
        "all" -> stringResource(DesignR.string.personal_all_kinds)
        else -> personalMetricLabel(metric)
    } }, stringResource(DesignR.string.personal_kind_filter), "personal_history_metric") { scope.launch { controller.selectMetric(it) } }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        PERSONAL_HISTORY_PERIODS.forEach { days -> OutlinedButton(onClick = { scope.launch { controller.selectPeriod(days) } }, modifier = Modifier.weight(1f).heightIn(min = 48.dp)
            .testTag("personal_history_period_$days").semantics { selected = state.period == days }) {
            Text(stringResource(if (days == 365) DesignR.string.personal_year else DesignR.string.personal_days, days.toString()))
        } }
    }
    Text("${state.from} — ${state.to}", style = MaterialTheme.typography.titleSmall, modifier = Modifier.testTag("personal_history_range"))
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        TextButton(onClick = { scope.launch { controller.movePeriod(-1) } }, modifier = Modifier.weight(1f).heightIn(min = 48.dp).testTag("personal_history_previous")) { Text(stringResource(DesignR.string.personal_previous_period)) }
        TextButton(onClick = { scope.launch { controller.movePeriod(1) } }, enabled = state.end < state.today, modifier = Modifier.weight(1f).heightIn(min = 48.dp).testTag("personal_history_next")) { Text(stringResource(DesignR.string.personal_next_period)) }
    }
}

@Composable
internal fun PersonalHistoryChoice(value: String, options: List<Pair<String, String>>, label: String, tag: String, onSelect: (String) -> Unit) {
    var open by remember(tag) { mutableStateOf(false) }
    Text(label, style = MaterialTheme.typography.labelLarge)
    Box(Modifier.fillMaxWidth()) {
        OutlinedButton(onClick = { open = true }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag(tag)) { Text(options.firstOrNull { it.first == value }?.second ?: value) }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            options.forEach { (id, title) -> DropdownMenuItem(text = { Text(title) }, onClick = { open = false; onSelect(id) }, modifier = Modifier.heightIn(min = 48.dp)
                .testTag(if (tag == "personal_history_metric") "personal_history_kind_$id" else "${tag}_$id").semantics { selected = value == id }) }
        }
    }
}

@Composable
private fun PersonalHistoryDaily(day: PersonalResolvedDay, state: PersonalHistoryState) {
    val metric = day.metric
    val resolution = metric.resolution ?: return
    PersonalSection(when (metric.kind) { "sleep" -> DesignR.string.personal_metric_sleep; else -> DesignR.string.personal_history }, "personal_history_daily_${day.date}_${metric.kind}") {
        Text("${day.date} · ${personalMetricLabel(metric.kind)}", style = MaterialTheme.typography.titleSmall)
        if (metric.kind == "sleep" && resolution.sleepAnalysis != null) PersonalSleepHistoryAnalysis(requireNotNull(resolution.sleepAnalysis), state.timezone, state.locale)
        else if (metric.kind == "sleep" && (resolution.status == "conflict" || personalSleepConflict(resolution) != null)) Text(personalSleepConflictLabel(personalSleepConflict(resolution)))
        else if (metric.value != null) Text(historyAmount(metric.kind, metric.value, metric.unit, state), style = MaterialTheme.typography.titleLarge)
        else if (metric.kind == "sleep" && resolution.sessions.isNotEmpty()) PersonalSleepHistorySessions(resolution.sessions, state.timezone, state.locale)
        else Text(historyResolutionStatus(resolution.status))
        val groups = resolution.statistics?.let { it.groups.ifEmpty { listOf(it) } }.orEmpty()
        groups.forEach { stats ->
            if (groups.size > 1 || metric.kind == "temperature" || stats.measurementKind?.let { it !in listOf("observed", "measured") } == true || stats.context?.let { it != "unknown" } == true) {
                Text(listOfNotNull(stats.measurementKind?.let { historyStatisticsContext("measurement", it) },
                    stats.sensorLocation?.takeIf { metric.kind == "temperature" }?.let { historyStatisticsContext("location", it) },
                    stats.temperatureBasis?.takeIf { metric.kind == "temperature" }?.let { historyStatisticsContext("temperature", it) },
                    stats.context?.takeIf { it != "unknown" }?.let { historyStatisticsContext("context", it) },
                    stats.sourceNames.takeIf { it.isNotEmpty() }?.joinToString(" · ")).joinToString(" · "), style = MaterialTheme.typography.bodySmall)
            }
            listOf(DesignR.string.resolution_points to stats.points, DesignR.string.resolution_interval_means to stats.intervalMeans,
                DesignR.string.resolution_ranges to stats.ranges, DesignR.string.resolution_sample_windows_title to stats.sampleWindows).forEach { (label, block) ->
                if (block != null && (block.count ?: 0) > 0) {
                    Text(stringResource(label), style = MaterialTheme.typography.titleSmall)
                    if (label == DesignR.string.resolution_sample_windows_title) {
                        if (block.usableSampleCount != null && block.sampleCount != null) Text(stringResource(DesignR.string.resolution_sample_windows_count,
                            block.count.toString(), block.usableSampleCount.toString(), block.sampleCount.toString()))
                        else Text(stringResource(DesignR.string.resolution_count, block.count.toString()))
                    } else Text(stringResource(DesignR.string.resolution_count, block.count.toString()))
                    if (label == DesignR.string.resolution_points) {
                        block.firstAt?.let { Text(stringResource(DesignR.string.resolution_first_measurement, personalMoment(it, state.timezone, state.locale))) }
                        block.latestAt?.let { Text(stringResource(DesignR.string.resolution_latest_measurement, personalMoment(it, state.timezone, state.locale))) }
                    }
                    block.mean?.let { Text("${stringResource(when (label) { DesignR.string.resolution_points -> DesignR.string.resolution_point_mean; DesignR.string.resolution_sample_windows_title -> DesignR.string.resolution_sample_windows_mean; else -> DesignR.string.resolution_interval_mean })}: ${historyAmount(metric.kind, it, metric.unit, state)}") }
                    block.min?.let { Text("${stringResource(DesignR.string.resolution_min)}: ${historyAmount(metric.kind, it, metric.unit, state)}") }
                    block.max?.let { Text("${stringResource(DesignR.string.resolution_max)}: ${historyAmount(metric.kind, it, metric.unit, state)}") }
                    if (label == DesignR.string.resolution_ranges) Text(stringResource(DesignR.string.resolution_range_no_mean))
                    if (label == DesignR.string.resolution_interval_means) Text(stringResource(DesignR.string.resolution_interval_extrema))
                    block.coveredSeconds?.let { Text(stringResource(DesignR.string.resolution_coverage, personalDuration(it / 60, state.locale))) }
                    if (label == DesignR.string.resolution_sample_windows_title) {
                        block.sampledSeconds?.let { Text(stringResource(DesignR.string.resolution_sample_windows_seconds, formatFoodAmount(it.toString(), 0))) }
                        block.sampleCoverageRatio?.let { Text(stringResource(DesignR.string.resolution_sample_windows_ratio, formatFoodAmount((it * 100).toString(), 1))) }
                        block.gapCount?.takeIf { it > 0 }?.let { Text(stringResource(DesignR.string.ring_workout_gaps, it.toString())) }
                        block.maxInterSampleGapMs?.let { Text(stringResource(DesignR.string.resolution_sample_windows_max_gap, formatFoodAmount((it / 1000).toString(), 1))) }
                        Text(stringResource(DesignR.string.resolution_sample_windows_discrete_hint), style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
        PersonalHistoryResolution(resolution, state.timezone, state.locale)
    }
}

@Composable
private fun HistoryObservation(item: PersonalObservation, state: PersonalHistoryState, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth().clickable(onClick = onClick).heightIn(min = 64.dp).padding(vertical = 12.dp).testTag("personal_history_record_${item.id}"), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(personalAmountText(item.kind, item.value, state.units, state.locale) ?: if (item.kind == "sleep") "${personalMoment(item.startAt, state.timezone, state.locale)} — ${personalMoment(item.endAt, state.timezone, state.locale)}" else stringResource(DesignR.string.personal_no_value), style = MaterialTheme.typography.titleMedium)
        if (state.metric == "all") Text(personalMetricLabel(item.kind))
        Text("${personalMoment(item.startAt, state.timezone, state.locale)} · ${personalProvenance(item, state.locale)}", style = MaterialTheme.typography.bodySmall)
        if (item.excluded) Text(stringResource(DesignR.string.personal_excluded))
        if (item.corrected) Text(localized(HistoryStrings.Record.corrected))
        PersonalQuality(item.quality)
    }
    HorizontalDivider()
}

@Composable
private fun PersonalHistoryBody(state: PersonalHistoryState, lease: assistant.mobile.core.SessionLease,
    bodyPresentation: BodyPresentationController?, bodyAssets: AndroidBodyAssetRepository?, onOpenProfile: () -> Unit, onRecord: (HistoryRecordSelection) -> Unit, onAnalysis: (String) -> Unit) {
    val page = state.history.data ?: return
    val groups = remember(page) { bodyAnalyses(page.records, page.analyses, page.evidenceById) }
    if (page.truncated) Text(stringResource(DesignR.string.personal_atlas_truncated), style = MaterialTheme.typography.bodySmall)
    if (page.restrictedSources.isNotEmpty()) {
        Text(stringResource(DesignR.string.personal_restricted_history), style = MaterialTheme.typography.titleSmall)
        Text(stringResource(DesignR.string.personal_source_error_history_restricted), style = MaterialTheme.typography.bodySmall)
    }
    val sources = groups.map { it.sourceId.orEmpty() to (it.sourceName ?: stringResource(DesignR.string.personal_unnamed_source)) }.distinctBy { it.first }
    var source by rememberSaveable(state.from, state.to) { mutableStateOf(sources.firstOrNull()?.first.orEmpty()) }
    val selectedSource = source.takeIf { selected -> sources.any { it.first == selected } } ?: sources.firstOrNull()?.first.orEmpty()
    if (sources.size > 1) PersonalHistoryChoice(selectedSource, sources, stringResource(DesignR.string.personal_preferred_source), "personal_body_source") { source = it }
    val sourceGroups = groups.filter { it.sourceId.orEmpty() == selectedSource }
    var selectedIndex by rememberSaveable(state.from, state.to, selectedSource) { mutableStateOf(0) }
    val selection = sourceGroups.getOrNull(selectedIndex) ?: sourceGroups.firstOrNull()
    if (sourceGroups.isNotEmpty()) {
        PersonalHistoryChoice((sourceGroups.indexOf(selection).coerceAtLeast(0)).toString(), sourceGroups.mapIndexed { index, group -> index.toString() to (personalMoment(group.measuredAt, state.timezone, state.locale) + " · " + stringResource(DesignR.string.personal_atlas_scan_number, (sourceGroups.size - index).toString())) }, stringResource(DesignR.string.personal_atlas_select_analysis), "personal_body_analysis") { selectedIndex = it.toInt() }
        if (selection != null && bodyPresentation != null && bodyAssets != null) {
            BodyAtlas(selection, sourceGroups, state.overview.data?.profile, state.canWrite, state.timezone, state.locale,
                lease, bodyPresentation, bodyAssets, onOpenProfile)
        }
        selection?.archive?.let { archive ->
            TextButton(onClick = { onAnalysis(archive.id) }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("personal_history_analysis_${archive.id}")) {
                Text("${stringResource(DesignR.string.personal_archive_title)} · v${archive.revision}")
            }
        }
    }
    var kind by remember(source) { mutableStateOf("body_fat_percent") }
    val kinds = (listOf("weight") + PERSONAL_BODY_KINDS).filter { option -> sourceGroups.any { group -> group.records.any { it.kind == option } } }
    if (kinds.isNotEmpty()) {
        val selectedKind = kind.takeIf { it in kinds } ?: kinds.first()
        Text(stringResource(DesignR.string.personal_body_trend), style = MaterialTheme.typography.titleMedium)
        PersonalHistoryChoice(selectedKind, kinds.map { it to personalMetricLabel(it) }, stringResource(DesignR.string.personal_kind_filter), "personal_body_kind") { kind = it }
        val candidates = sourceGroups.mapNotNull { group ->
            group.records.filter { it.kind == selectedKind && !it.excluded }.singleOrNull()?.value?.toDoubleOrNull()?.takeIf { it.isFinite() }?.let { group.measuredAt to it }
        }
        val counts = candidates.groupingBy { it.first }.eachCount()
        val points = candidates.filter { counts[it.first] == 1 }
        if (points.size > 1) PersonalHistoryTrend(points, stringResource(DesignR.string.personal_body_trend_label, personalMetricLabel(selectedKind)))
        else Text(stringResource(DesignR.string.personal_body_trend_empty), style = MaterialTheme.typography.bodySmall)
    }
    var listPage by remember(state.from, state.to) { mutableStateOf(0) }
    groups.drop(listPage * 12).take(12).forEach { group ->
        OutlinedCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(personalMoment(group.measuredAt, state.timezone, state.locale), style = MaterialTheme.typography.titleMedium)
                Text(group.sourceName ?: stringResource(DesignR.string.personal_unnamed_source), style = MaterialTheme.typography.bodySmall)
                group.records.forEach { item ->
                    Text(personalMetricLabel(item.kind), style = MaterialTheme.typography.labelLarge)
                    HistoryObservation(item, state) { onRecord(HistoryRecordSelection(item.id, item.kind, item.sourceId, page.evidenceById[item.id] ?: observationEvidence(item))) }
                }
            }
        }
    }
    if (groups.size > 12) {
        Text(stringResource(DesignR.string.personal_archive_page, (listPage + 1).toString(), ((groups.size + 11) / 12).toString()))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            TextButton(onClick = { listPage-- }, enabled = listPage > 0, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(DesignR.string.personal_archive_previous)) }
            TextButton(onClick = { listPage++ }, enabled = (listPage + 1) * 12 < groups.size, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(DesignR.string.personal_archive_next)) }
        }
    }
    if (groups.isEmpty() && !state.history.loading && state.history.error == null) {
        Text(stringResource(DesignR.string.personal_body_history_empty))
        Text(stringResource(DesignR.string.personal_body_history_empty_text), style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun historyAmount(kind: String, value: String?, unit: String, state: PersonalHistoryState): String =
    (if (kind in PERSONAL_SENSOR_KINDS) personalSensorAmount(value, unit, state.units, state.locale) else personalAmountText(kind, value, state.units, state.locale)) ?: stringResource(DesignR.string.personal_no_value)

private fun observationEvidence(record: PersonalObservation): JsonObject = JsonObject(mapOf(
    "id" to JsonPrimitive(record.id), "kind" to JsonPrimitive(record.kind), "source_id" to JsonPrimitive(record.sourceId),
    "source_name" to JsonPrimitive(record.sourceName), "start_at" to JsonPrimitive(record.startAt), "end_at" to JsonPrimitive(record.endAt),
    "timezone" to JsonPrimitive(record.timezone), "method" to JsonPrimitive(record.method), "revision" to JsonPrimitive(record.revision),
    "value" to JsonPrimitive(record.value), "excluded" to JsonPrimitive(record.excluded), "corrected" to JsonPrimitive(record.corrected),
))

@Composable
private fun PersonalHistoryTrend(points: List<Pair<String, Double>>, label: String, gapDays: Int? = null) {
    val ordered = points.mapNotNull { (at, value) -> runCatching { Instant.parse(at).toEpochMilli() to value }.getOrNull() }.sortedBy { it.first }
    if (ordered.size < 2) return
    val colour = MaterialTheme.colorScheme.primary
    val axis = MaterialTheme.colorScheme.outlineVariant
    val first = ordered.first().first
    val span = (ordered.last().first - first).coerceAtLeast(1)
    val min = ordered.minOf { it.second }; val max = ordered.maxOf { it.second }
    val range = (max - min).takeIf { it > 0 } ?: 1.0
    Text(label, style = MaterialTheme.typography.titleSmall)
    Canvas(Modifier.fillMaxWidth().height(160.dp).testTag("personal_history_trend").semantics { contentDescription = label }) {
        drawLine(axis, Offset(0f, size.height), Offset(size.width, size.height))
        fun offset(point: Pair<Long, Double>) = Offset((point.first - first).toFloat() / span * size.width, size.height - ((point.second - min) / range * (size.height - 12.dp.toPx())).toFloat() - 6.dp.toPx())
        ordered.zipWithNext().forEach { (a, b) ->
            if (gapDays == null || b.first - a.first <= gapDays * 86_400_000L) drawLine(colour, offset(a), offset(b), 2.dp.toPx())
        }
        ordered.forEach { drawCircle(colour, 3.dp.toPx(), offset(it)) }
    }
}

@Composable
private fun historyStatisticsContext(scope: String, value: String): String {
    val resource = when ("$scope:$value") {
        "measurement:measured" -> DesignR.string.resolution_context_measurement_measured
        "measurement:observed" -> DesignR.string.resolution_context_measurement_observed
        "measurement:resting" -> DesignR.string.resolution_context_measurement_resting
        "measurement:sample_window_mean" -> DesignR.string.resolution_context_measurement_sample_window_mean
        "measurement:skin" -> DesignR.string.resolution_context_measurement_skin
        "measurement:device_estimate" -> DesignR.string.resolution_context_measurement_device_estimate
        "measurement:unknown" -> DesignR.string.resolution_context_measurement_unknown
        "location:skin" -> DesignR.string.resolution_context_location_skin
        "location:body" -> DesignR.string.resolution_context_location_body
        "location:wrist" -> DesignR.string.resolution_context_location_wrist
        "location:finger" -> DesignR.string.resolution_context_location_finger
        "location:unknown" -> DesignR.string.resolution_context_location_unknown
        "temperature:absolute" -> DesignR.string.resolution_context_temperature_absolute
        "temperature:deviation", "temperature:baseline_deviation" -> DesignR.string.resolution_context_temperature_baseline_deviation
        "temperature:unknown" -> DesignR.string.resolution_context_temperature_unknown
        "context:resting" -> DesignR.string.resolution_context_context_resting
        "context:exercise" -> DesignR.string.resolution_context_context_exercise
        "context:running" -> DesignR.string.resolution_context_context_running
        "context:workout" -> DesignR.string.resolution_context_context_workout
        "context:recovery" -> DesignR.string.resolution_context_context_recovery
        "context:sleep" -> DesignR.string.resolution_context_context_sleep
        else -> null
    }
    return resource?.let { stringResource(it) } ?: value
}
