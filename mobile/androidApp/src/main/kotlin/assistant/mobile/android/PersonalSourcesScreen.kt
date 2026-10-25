package assistant.mobile.android

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import assistant.mobile.core.GeneratedStrings
import assistant.mobile.core.LocalizedString
import assistant.mobile.core.SessionLease
import assistant.mobile.core.personal.PERSONAL_SOURCE_ROWS
import assistant.mobile.core.personal.PersonalDdxController
import assistant.mobile.core.personal.PersonalMetric
import assistant.mobile.core.personal.PersonalSourceEntry
import assistant.mobile.core.personal.PersonalSourceReading
import assistant.mobile.core.personal.PersonalSourcesController
import assistant.mobile.core.personal.PersonalSourcesState
import assistant.mobile.core.personal.personalAmountText
import assistant.mobile.core.personal.personalMetricSources
import assistant.mobile.core.personal.personalSensorAmount
import assistant.mobile.core.personal.personalSourceItems
import assistant.mobile.core.personal.personalSourcePin
import assistant.mobile.core.personal.personalSourceRole
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale
import kotlin.math.abs
import kotlinx.coroutines.launch

@Composable
internal fun PersonalSourcesScreen(
    controller: PersonalSourcesController,
    lease: SessionLease,
    onOpenSource: (String) -> Unit,
    onOpenHistory: (String, Int) -> Unit = { _, _ -> },
    modifier: Modifier = Modifier,
) {
    PersonalSourcesActivation(controller, lease)
    val observed by controller.state.collectAsStateWithLifecycle()
    val state = observed?.takeIf { it.namespace == lease.namespace }
    val scope = rememberCoroutineScope()
    var connecting by remember(lease.namespace) { mutableStateOf(false) }
    var disabledOpen by remember(lease.namespace) { mutableStateOf(false) }
    ScreenColumn("screen_PersonalSources", modifier, title = localized(GeneratedStrings.Sources.title)) {
        if (state == null) Text(localized(GeneratedStrings.State.loading))
        state?.let { current ->
            if (!current.canRead) UnavailableCard()
            else {
                PersonalQueryStatus(current.sources) { scope.launch { controller.refresh() } }
                if (current.policyDraft == null) PersonalSourcesWriteStatus(current, controller)
                val sources = personalVisibleSources(current)
                if (current.sources.data != null) {
                    PersonalSourcesCard(GeneratedStrings.Sources.devices, "personal_source_devices") {
                        sources.filter { it.active }.forEach { source -> PersonalSourceLine(source, current) { onOpenSource(source.id) } }
                        if (current.canWrite) TextButton(onClick = { connecting = true },
                            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("personal_source_connect")) {
                            Text(localized(GeneratedStrings.Sources.connect))
                        }
                        val disabled = sources.filterNot { it.active }
                        if (disabled.isNotEmpty()) {
                            TextButton(onClick = { disabledOpen = !disabledOpen }, modifier = Modifier.heightIn(min = 48.dp)) {
                                Text(localized(GeneratedStrings.Sources.disabledDevices(disabled.size.toString())))
                            }
                            if (disabledOpen) disabled.forEach { source -> PersonalSourceLine(source, current) { onOpenSource(source.id) } }
                        }
                    }
                    val rows = PERSONAL_SOURCE_ROWS.filter { row -> sources.any { it.active && row.metrics.any { metric -> metric in it.metricKeys } } }
                    if (rows.isNotEmpty()) {
                        Text(localized(GeneratedStrings.Sources.whereFrom), style = MaterialTheme.typography.titleMedium)
                        rows.groupBy { it.group }.forEach { (group, entries) ->
                            PersonalSourcesCard(personalSourceGroupLabel(group), "personal_source_group_$group") {
                                entries.forEach { row ->
                                    Column(Modifier.fillMaxWidth().clickable { controller.beginPolicy(row.key) }
                                        .heightIn(min = 56.dp).padding(vertical = 8.dp).testTag("personal_source_row_${row.key}"),
                                        verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                        Text(personalSourceRowLabel(row.key), style = MaterialTheme.typography.titleSmall)
                                        val pinned = personalSourcePin(row, current.combined.data?.policies.orEmpty(), current.today)
                                        val pin = sources.firstOrNull { it.id == pinned }
                                        val contributors = row.metrics.flatMap { metric ->
                                            current.combined.data?.daily.orEmpty().filter { it.date == current.today && it.metric.kind == metric }
                                                .flatMap { personalMetricSources(it.metric) }
                                        }.distinct().map { name -> sources.find { it.name == name || it.id == name }?.let { personalSourceShortName(it, current) } ?: name }
                                        val names = if (contributors.isNotEmpty()) contributors else sources.filter { it.active && row.metrics.any { key -> key in it.metricKeys } }.map { personalSourceShortName(it, current) }
                                        Text(if (pin != null) localized(GeneratedStrings.Sources.always(personalSourceShortName(pin, current))) else names.joinToString(", "),
                                            style = MaterialTheme.typography.bodySmall)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
    if (connecting) PersonalSheet(localized(GeneratedStrings.Sources.connectTitle), "personal_source_connect_options", { connecting = false }) {
        OutlinedButton(onClick = { connecting = false; onOpenSource("ddx") },
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("personal_source_connect_ddx")) {
            Text(localized(GeneratedStrings.Sources.Device.ddx))
        }
    }
    state?.let { PersonalSourcePolicySheet(it, controller, onOpenHistory) }
}

@Composable
internal fun PersonalSourceScreen(
    controller: PersonalSourcesController,
    ddxController: PersonalDdxController,
    lease: SessionLease,
    sourceId: String,
    onOpenRow: (String, String) -> Unit,
    onOpenHistory: (String, Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    PersonalSourcesActivation(controller, lease, sourceId)
    val observed by controller.state.collectAsStateWithLifecycle()
    val state = observed?.takeIf { it.namespace == lease.namespace }
    val scope = rememberCoroutineScope()
    var details by remember(lease.namespace, sourceId) { mutableStateOf(false) }
    val source = state?.let { personalVisibleSources(it).find { item -> item.id == sourceId } }
    ScreenColumn("screen_PersonalSource", modifier, title = source?.let { personalSourceName(it) } ?: if (sourceId == "ddx") localized(GeneratedStrings.Sources.Device.ddx) else localized(GeneratedStrings.Sources.title)) {
        if (state == null) Text(localized(GeneratedStrings.State.loading))
        state?.let { current ->
            if (!current.canRead) UnavailableCard()
            else {
                PersonalQueryStatus(current.sources) { scope.launch { controller.refresh() } }
                PersonalSourcesWriteStatus(current, controller)
                if (source == null && sourceId != "ddx" && current.sources.data != null) Text(localized(GeneratedStrings.Sources.notFound))
                source?.let { entry ->
                    if (entry.device.kind in setOf("ring", "whoop")) Text(entry.name, style = MaterialTheme.typography.bodySmall)
                    Text(personalSourceStatus(entry, current), modifier = Modifier.testTag("personal_source_summary"))
                    val rows = PERSONAL_SOURCE_ROWS.filter { row -> row.metrics.any { it in entry.metricKeys } }
                    if (rows.isNotEmpty()) PersonalSourcesCard(GeneratedStrings.Sources.data, "personal_source_measures") {
                        Text(current.day, style = MaterialTheme.typography.labelMedium)
                        PersonalQueryStatus(current.own) { scope.launch { controller.refresh() } }
                        rows.forEach { row ->
                            Column(Modifier.fillMaxWidth().clickable { onOpenRow(entry.id, row.key) }
                                .heightIn(min = 56.dp).padding(vertical = 8.dp).testTag("personal_source_measure_${row.key}"),
                                verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(personalSourceRowLabel(row.key), style = MaterialTheme.typography.titleSmall)
                                val items = personalSourceItems(row, current.own.data, current.day)
                                val item = if (row.metrics.size > 1) items.firstOrNull { (it.metric.value?.toDoubleOrNull() ?: 0.0) > 0.0 } else items.firstOrNull()
                                item?.let { PersonalSourceAmount(it.metric, current) }
                                personalSourceRole(entry.id, row, current.combined.data, current.day)?.let { role ->
                                    Text(localized(if (role == "primary") GeneratedStrings.Sources.Role.primary else GeneratedStrings.Sources.Role.backup),
                                        style = MaterialTheme.typography.labelMedium)
                                }
                            }
                        }
                    }
                    TextButton(onClick = { details = !details }, modifier = Modifier.heightIn(min = 48.dp).testTag("personal_source_details_toggle")) {
                        Text(localized(GeneratedStrings.Sources.details))
                    }
                    if (details) {
                        entry.createdAt?.let { Text(localized(GeneratedStrings.Sources.connectedAt(personalMoment(it, current.timezone, current.locale)))) }
                        PersonalSourceLinks(entry, current, controller)
                        if (entry.device.kind == "custom") {
                            Text(localized(GeneratedStrings.Sources.setupId), style = MaterialTheme.typography.bodySmall)
                            Text(entry.id)
                        }
                    }
                    if (entry.device.kind == "custom" && current.canWrite) OutlinedButton(
                        onClick = { scope.launch { controller.switchSource(entry.id) } }, enabled = !current.busy && current.pending.isEmpty(),
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("personal_source_switch"),
                    ) { Text(localized(if (entry.status == "disabled") GeneratedStrings.Sources.turnOn else GeneratedStrings.Sources.turnOff)) }
                }
                if (sourceId == "ddx" || source?.device?.kind == "ddx")
                    PersonalDdxCard(ddxController, controller, lease, current, onOpenHistory)
            }
        }
    }
}

@Composable
internal fun PersonalSourceDataScreen(
    controller: PersonalSourcesController,
    lease: SessionLease,
    sourceId: String,
    rowKey: String,
    onOpenHistory: (String, Int) -> Unit = { _, _ -> },
    initialDay: String? = null,
    modifier: Modifier = Modifier,
) {
    PersonalSourcesActivation(controller, lease, sourceId, rowKey, initialDay)
    val observed by controller.state.collectAsStateWithLifecycle()
    val state = observed?.takeIf { it.namespace == lease.namespace }
    val scope = rememberCoroutineScope()
    val row = PERSONAL_SOURCE_ROWS.find { it.key == rowKey }
    ScreenColumn("screen_PersonalSourceData", modifier, title = personalSourceRowLabel(rowKey)) {
        if (state == null) Text(localized(GeneratedStrings.State.loading))
        state?.let { current ->
            if (!current.canRead) UnavailableCard()
            else {
                PersonalQueryStatus(current.sources) { scope.launch { controller.refresh() } }
                if (current.policyDraft == null) PersonalSourcesWriteStatus(current, controller)
                val source = personalVisibleSources(current).find { it.id == sourceId }
                if (source == null || row == null) {
                    if (current.sources.data != null) Text(localized(GeneratedStrings.Sources.notFound))
                } else {
                    Text(personalSourceName(source), style = MaterialTheme.typography.titleMedium)
                    PersonalSourceDayNavigation(current) { scope.launch { controller.selectDay(it) } }
                    PersonalQueryStatus(current.own) { scope.launch { controller.refresh() } }
                    val items = personalSourceItems(row, current.own.data, current.day)
                    if (current.own.data != null && items.isEmpty()) Text(localized(GeneratedStrings.Sources.dayEmpty))
                    if (items.isNotEmpty()) Text(localized(when (personalSourceRole(source.id, row, current.combined.data, current.day)) {
                        "primary" -> GeneratedStrings.Sources.RoleDay.primary
                        "backup" -> GeneratedStrings.Sources.RoleDay.backup
                        else -> GeneratedStrings.Sources.RoleDay.unused
                    }), modifier = Modifier.testTag("personal_source_data_role"))
                    items.forEach { entry ->
                        val metric = entry.metric
                        OutlinedCard(Modifier.fillMaxWidth().testTag("personal_source_data_${metric.kind}")) {
                            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                if (row.metrics.size > 1) Text(personalMetricLabel(metric.kind), style = MaterialTheme.typography.labelLarge)
                                val analysis = metric.resolution?.sleepAnalysis
                                if (metric.kind == "sleep" && analysis != null) PersonalSleepHistoryAnalysis(analysis, current.timezone, current.locale)
                                else PersonalSourceAmount(metric, current)
                                if (metric.kind == "sleep" && analysis == null) PersonalSleepHistorySessions(metric.resolution?.sessions.orEmpty(), current.timezone, current.locale)
                                val readings = current.own.data?.series.orEmpty().filter { it.metric == metric.kind && it.value?.toDoubleOrNull()?.isFinite() == true }
                                    .sortedBy { it.startAt }
                                if (metric.kind in setOf("heart_rate", "hrv", "spo2", "temperature", "stress") && readings.isNotEmpty())
                                    PersonalSourceReadings(readings, metric.kind, current)
                                metric.resolution?.let { PersonalHistoryResolution(it, current.timezone, current.locale) }
                            }
                        }
                    }
                    OutlinedButton(onClick = { controller.beginPolicy(row.key) }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                        .testTag("personal_source_choose")) { Text(localized(GeneratedStrings.Sources.choose)) }
                }
            }
        }
    }
    state?.let { PersonalSourcePolicySheet(it, controller, onOpenHistory) }
}

@Composable
private fun PersonalSourcePolicySheet(state: PersonalSourcesState, controller: PersonalSourcesController, onOpenHistory: (String, Int) -> Unit) {
    val draft = state.policyDraft ?: return
    val row = PERSONAL_SOURCE_ROWS.find { it.key == draft.rowKey } ?: return
    val scope = rememberCoroutineScope()
    val candidates = personalVisibleSources(state).filter { it.active && row.metrics.any { metric -> metric in it.metricKeys } }
    PersonalSheet(personalSourceRowLabel(row.key), "personal_source_policy", { scope.launch { controller.dismissPolicy() } }) {
        PersonalSourcesWriteStatus(state, controller)
        PersonalQueryStatus(state.combined) { scope.launch { controller.refresh() } }
        Column(Modifier.selectableGroup().testTag("personal_policy_draft"), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            PersonalSourcePolicyChoice(GeneratedStrings.Sources.automatic, GeneratedStrings.Sources.automaticText,
                draft.sourceId == null, "personal_policy_auto", state.canWrite && !state.busy && state.pending.isEmpty()) { controller.choosePolicy(null) }
            candidates.forEach { source -> PersonalSourcePolicyChoice(GeneratedStrings.Sources.always(personalSourceShortName(source, state)),
                GeneratedStrings.Sources.alwaysText, draft.sourceId == source.id, "personal_policy_source_${source.id}", state.canWrite && !state.busy && state.pending.isEmpty()) {
                controller.choosePolicy(source.id)
            } }
            if (state.canWrite) OutlinedTextField(value = draft.effectiveFrom, onValueChange = controller::setPolicyDate,
                label = { Text(localized(GeneratedStrings.Sources.effectiveFrom)) }, enabled = !state.busy && state.pending.isEmpty(), singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii), modifier = Modifier.fillMaxWidth().testTag("personal_policy_from"))
            PersonalSourcePolicyEvidence(row, state, candidates) { scope.launch { controller.refresh() } }
            OutlinedButton(onClick = { onOpenHistory(if (row.key == "body") "body" else row.metrics.first(), 7) }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("personal_policy_history")) {
                Text(localized(GeneratedStrings.Sources.history))
            }
            val changed = draft.sourceId != personalSourcePin(row, state.combined.data?.policies.orEmpty(), state.today) || draft.effectiveFrom != state.today
            if (state.canWrite) Button(onClick = { scope.launch { controller.savePolicy() } }, enabled = changed && !state.busy && state.pending.isEmpty() && state.combined.data != null && !state.combined.loading && state.combined.error == null,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("personal_policy_save")) { Text(localized(GeneratedStrings.Sources.save)) }
        }
    }
}

@Composable
private fun PersonalSourcePolicyChoice(title: LocalizedString, detail: LocalizedString, selected: Boolean, tag: String, enabled: Boolean, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().selectable(selected = selected, enabled = enabled, role = Role.RadioButton, onClick = onClick).heightIn(min = 56.dp).testTag(tag),
        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        RadioButton(selected = selected, enabled = enabled, onClick = null)
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(localized(title), style = MaterialTheme.typography.titleSmall)
            Text(localized(detail), style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun PersonalSourceLinks(source: PersonalSourceEntry, state: PersonalSourcesState, controller: PersonalSourcesController) {
    val scope = rememberCoroutineScope()
    val identities = state.combined.data?.identities.orEmpty()
    val physical = identities[source.id]?.takeIf { it.confirmed }?.physicalId
    val others = personalVisibleSources(state).filter { it.id != source.id && it.device.kind == source.device.kind }
    val linked = others.filter { physical != null && identities[it.id]?.confirmed == true && identities[it.id]?.physicalId == physical }
    val candidates = others.filterNot { it in linked }
    if (linked.isNotEmpty() || (state.canWrite && candidates.isNotEmpty())) {
        Text(localized(GeneratedStrings.Resolution.linksTitle), style = MaterialTheme.typography.titleSmall)
        if (linked.isNotEmpty()) {
            linked.forEach { Text(personalSourceShortName(it, state)) }
            if (state.canWrite) TextButton(onClick = { scope.launch { controller.unlinkSource(source.id) } }, enabled = !state.busy && state.pending.isEmpty(),
                modifier = Modifier.heightIn(min = 48.dp)) { Text(localized(GeneratedStrings.Resolution.unlink)) }
        } else if (state.canWrite) candidates.forEach { other ->
            OutlinedButton(onClick = { scope.launch { controller.linkSources(source.id, other.id) } }, enabled = !state.busy && state.pending.isEmpty(),
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                Text(localized(GeneratedStrings.Resolution.link) + ": " + personalSourceShortName(other, state))
            }
        }
    }
}

@Composable
private fun PersonalSourceReadings(readings: List<PersonalSourceReading>, metric: String, state: PersonalSourcesState) {
    var open by remember(state.namespace, state.day, metric) { mutableStateOf(false) }
    val values = readings.mapNotNull { it.value?.toDoubleOrNull() }
    if (values.size > 1) {
        val unit = readings.first().unit
        Text(localized(GeneratedStrings.Sources.extremes(
            personalSensorAmount(values.min().toString(), unit, state.displayUnits, state.locale).orEmpty(),
            personalSensorAmount(values.max().toString(), unit, state.displayUnits, state.locale).orEmpty(),
        )), style = MaterialTheme.typography.bodySmall)
        PersonalSourceChart(readings, localized(GeneratedStrings.Sources.chart(personalSourceRowLabel(metric))))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(personalSourceTime(readings.first().startAt, state.timezone, state.locale), style = MaterialTheme.typography.labelSmall)
            Text(personalSourceTime(readings.last().startAt, state.timezone, state.locale), style = MaterialTheme.typography.labelSmall)
        }
    }
    TextButton(onClick = { open = !open }, modifier = Modifier.heightIn(min = 48.dp).testTag("personal_source_readings_$metric")) {
        Text(localized(GeneratedStrings.Sources.readings(readings.size.toDouble(), readings.size.toString())))
    }
    if (open) readings.asReversed().forEach { point ->
        HorizontalDivider()
        Text(personalSourceTime(point.startAt, state.timezone, state.locale) + " · " +
            personalSensorAmount(point.value, point.unit, state.displayUnits, state.locale).orEmpty())
    }
}

/** A thirty-minute gap stays a gap; a source's value is never mixed with another's. */
@Composable
private fun PersonalSourceChart(readings: List<PersonalSourceReading>, label: String) {
    val points = readings.mapNotNull { item ->
        val at = runCatching { Instant.parse(item.startAt).toEpochMilli() }.getOrNull()
        val value = item.value?.toDoubleOrNull()?.takeIf { it.isFinite() }
        if (at != null && value != null) at to value else null
    }
    val colour = MaterialTheme.colorScheme.primary
    Canvas(Modifier.fillMaxWidth().height(160.dp).testTag("personal_source_chart").semantics { contentDescription = label }) {
        if (points.size < 2) return@Canvas
        val first = points.first().first
        val span = (points.last().first - first).coerceAtLeast(1).toDouble()
        val min = points.minOf { it.second }
        val delta = (points.maxOf { it.second } - min).takeIf { abs(it) > 0.00001 } ?: 1.0
        fun position(point: Pair<Long, Double>) = Offset(((point.first - first) / span * size.width).toFloat(),
            (size.height - 8.dp.toPx() - (point.second - min) / delta * (size.height - 16.dp.toPx())).toFloat())
        points.zipWithNext().forEach { (a, b) -> if (b.first - a.first <= 30 * 60 * 1000) drawLine(colour, position(a), position(b), 2.dp.toPx()) }
        points.forEach { drawCircle(colour, 2.dp.toPx(), position(it)) }
    }
}

@Composable
private fun PersonalSourcesActivation(controller: PersonalSourcesController, lease: SessionLease,
    sourceId: String? = null, rowKey: String? = null, initialDay: String? = null) {
    val owner = LocalLifecycleOwner.current
    DisposableEffect(controller, lease, owner, sourceId, rowKey, initialDay) {
        val visibilityOwner = Any()
        var initial = true
        fun activate() { controller.activate(sourceId, rowKey, if (initial) initialDay else null, visibilityOwner); initial = false }
        val observer = LifecycleEventObserver { _, event -> when (event) {
            Lifecycle.Event.ON_START -> activate()
            Lifecycle.Event.ON_STOP -> controller.deactivate(visibilityOwner)
            else -> Unit
        } }
        owner.lifecycle.addObserver(observer)
        if (owner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) activate()
        onDispose { owner.lifecycle.removeObserver(observer); controller.deactivate(visibilityOwner) }
    }
}

@Composable
private fun PersonalSourcesWriteStatus(state: PersonalSourcesState, controller: PersonalSourcesController) {
    val scope = rememberCoroutineScope()
    if (state.conflict) {
        ErrorMessage(GeneratedStrings.Personal.conflict, "personal_policy_conflict")
        StatusMessage(GeneratedStrings.PersonalForms.draftKept, "personal_policy_draft_kept")
    }
    if (!state.conflict) state.error?.let { ErrorMessage(it, "personal_source_error") }
    if (state.pending.isNotEmpty()) {
        StatusMessage(GeneratedStrings.Outcome.unknownText, "personal_source_unknown")
        state.pending.forEach { pending -> OutlinedButton(onClick = { scope.launch { controller.check(pending.key) } }, enabled = !state.busy,
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("personal_source_verify_${pending.key}")) { Text(localized(GeneratedStrings.Outcome.check)) } }
    }
}

@Composable
private fun PersonalSourceDayNavigation(state: PersonalSourcesState, go: (String) -> Unit) {
    Text(state.day, style = MaterialTheme.typography.titleMedium)
    val previous = localized(GeneratedStrings.Day.previous)
    val next = localized(GeneratedStrings.Day.next)
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        TextButton(onClick = { go(LocalDate.parse(state.day).minusDays(1).toString()) },
            modifier = Modifier.heightIn(min = 48.dp).testTag("personal_source_previous_day").semantics { contentDescription = previous }) { Text("‹") }
        TextButton(onClick = { go(state.today) }, enabled = state.day != state.today,
            modifier = Modifier.heightIn(min = 48.dp).testTag("personal_source_today")) { Text(localized(GeneratedStrings.Day.today)) }
        TextButton(onClick = { go(LocalDate.parse(state.day).plusDays(1).toString()) }, enabled = state.day < state.today,
            modifier = Modifier.heightIn(min = 48.dp).testTag("personal_source_next_day").semantics { contentDescription = next }) { Text("›") }
    }
}

@Composable
private fun PersonalSourceAmount(metric: PersonalMetric, state: PersonalSourcesState) {
    val text = when {
        metric.unit == "workouts" -> metric.value?.toDoubleOrNull()?.let { localized(GeneratedStrings.Sources.workouts(it, it.toInt().toString())) }
        metric.unit == "steps" -> personalAmountText("steps", metric.value, state.displayUnits, state.locale)
        metric.kind in setOf("heart_rate", "hrv", "spo2", "temperature", "stress") -> personalSensorAmount(metric.value, metric.unit, state.displayUnits, state.locale)
        else -> personalAmountText(metric.kind, metric.value, state.displayUnits, state.locale)
    }
    if (text != null) Text(text, style = MaterialTheme.typography.titleLarge)
}

@Composable
private fun PersonalSourceLine(source: PersonalSourceEntry, state: PersonalSourcesState, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth().clickable(onClick = onClick).heightIn(min = 56.dp).padding(vertical = 8.dp)
        .testTag("personal_source_${source.id}"), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(personalSourceName(source) + if (source.device.kind in setOf("ring", "whoop")) " · " + source.name else "", style = MaterialTheme.typography.titleSmall)
        Text(personalSourceStatus(source, state), style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun PersonalSourcesCard(title: LocalizedString, tag: String, content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    OutlinedCard(Modifier.fillMaxWidth().testTag(tag)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(localized(title), style = MaterialTheme.typography.titleMedium)
            content()
        }
    }
}

@Composable
private fun personalSourceName(source: PersonalSourceEntry): String = when (source.device.kind) {
    "ring" -> localized(GeneratedStrings.Sources.Device.ring)
    "whoop" -> localized(GeneratedStrings.Sources.Device.whoop)
    "ddx" -> localized(GeneratedStrings.Sources.Device.ddx)
    "caliper" -> localized(GeneratedStrings.Sources.Device.caliper)
    else -> source.name
}

@Composable
internal fun personalSourceShortName(source: PersonalSourceEntry, state: PersonalSourcesState): String {
    val named = source.device.kind in setOf("ring", "whoop", "ddx", "caliper")
    val base = if (named) localized(when (source.device.kind) {
        "ring" -> GeneratedStrings.Sources.Short.ring
        "whoop" -> GeneratedStrings.Sources.Short.whoop
        "ddx" -> GeneratedStrings.Sources.Short.ddx
        else -> GeneratedStrings.Sources.Short.caliper
    }) else source.name
    val count = state.sources.data.orEmpty().count { it.active &&
        (if (named) it.device.kind == source.device.kind else it.device.kind !in setOf("ring", "whoop", "ddx", "caliper") && it.name == source.name) }
    val createdAt = source.createdAt
    if (source.active && count > 1 && createdAt != null) {
        val date = runCatching {
            DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(Locale.forLanguageTag(state.locale))
                .format(Instant.parse(createdAt).atZone(ZoneId.of(state.timezone)).toLocalDate())
        }.getOrDefault(createdAt)
        return localized(GeneratedStrings.Sources.since(base, date))
    }
    return base
}

@Composable
private fun personalSourceStatus(source: PersonalSourceEntry, state: PersonalSourcesState): String = when {
    !source.active -> localized(GeneratedStrings.Sources.off)
    source.status == "unavailable" -> localized(GeneratedStrings.Personal.SourceState.unavailable)
    source.lastReceivedAt != null -> localized(GeneratedStrings.Sources.dataAt(personalMoment(requireNotNull(source.lastReceivedAt), state.timezone, state.locale)))
    else -> localized(GeneratedStrings.Sources.noData)
}

private fun personalVisibleSources(state: PersonalSourcesState): List<PersonalSourceEntry> = state.sources.data.orEmpty()
    .sortedWith(compareBy<PersonalSourceEntry> { listOf("ring", "whoop", "ddx", "caliper", "collector", "custom").indexOf(it.device.kind) }.thenByDescending { it.createdAt })

private fun personalSourceGroupLabel(key: String): LocalizedString = when (key) {
    "activity" -> GeneratedStrings.Sources.Group.activity
    "sleep" -> GeneratedStrings.Sources.Group.sleep
    "vitals" -> GeneratedStrings.Sources.Group.vitals
    else -> GeneratedStrings.Sources.Group.body
}

internal fun personalSourceTime(value: String, timezone: String, locale: String): String = runCatching {
    DateTimeFormatter.ofPattern("HH:mm", Locale.forLanguageTag(locale)).withZone(ZoneId.of(timezone)).format(Instant.parse(value))
}.getOrDefault(value)
