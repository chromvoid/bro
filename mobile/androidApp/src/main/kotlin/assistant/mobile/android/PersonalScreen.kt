package assistant.mobile.android

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import assistant.mobile.design.ui.ProgressBar
import assistant.mobile.design.ui.ActionIconButton
import assistant.mobile.design.ui.AppCard
import assistant.mobile.design.ui.CardTitle
import assistant.mobile.design.ui.SegmentedChoice
import assistant.mobile.design.ui.Spacing
import assistant.mobile.android.body.assets.AndroidBodyAssetRepository
import assistant.mobile.core.body.BodyPresentationController
import assistant.mobile.core.body.isBodyPresentationSubmission
import assistant.mobile.core.SessionLease
import assistant.mobile.core.food.formatFoodAmount
import assistant.mobile.core.personal.PERSONAL_BODY_KINDS
import assistant.mobile.core.personal.PERSONAL_FOLD_SITES
import assistant.mobile.core.personal.PERSONAL_SENSOR_KINDS
import assistant.mobile.core.personal.PERSONAL_TARGET_KINDS
import assistant.mobile.core.personal.PersonalCaliperMeasurement
import assistant.mobile.core.personal.PersonalController
import assistant.mobile.core.personal.PersonalDerivedScore
import assistant.mobile.core.personal.PersonalDisplayUnits
import assistant.mobile.core.personal.PersonalMetric
import assistant.mobile.core.personal.PersonalQuery
import assistant.mobile.core.personal.PersonalSleepAnalysis
import assistant.mobile.core.personal.PersonalState
import assistant.mobile.core.personal.personalAgeOn
import assistant.mobile.core.personal.personalAmountText
import assistant.mobile.core.personal.personalDuration
import assistant.mobile.core.personal.personalHeightText
import assistant.mobile.core.personal.personalMetricSources
import assistant.mobile.core.personal.personalProvenance
import assistant.mobile.core.personal.personalRequiredFoldSites
import assistant.mobile.core.personal.personalSensorAmount
import assistant.mobile.core.personal.personalSleepConflict
import assistant.mobile.core.personal.personalScoreValue
import assistant.mobile.core.personal.personalDerivedMissingMessage
import assistant.mobile.core.personal.personalProfileDraftChanged
import assistant.mobile.design.GeneratedColors
import assistant.mobile.design.R as DesignR
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.launch

/** Forms belong to the account journal; only the open metric and disclosures are local UI state. */
@Composable
internal fun PersonalScreen(
    controller: PersonalController,
    lease: SessionLease,
    onOpenProfile: () -> Unit,
    onOpenGoals: () -> Unit,
    onOpenHistory: () -> Unit,
    onOpenSources: () -> Unit,
    onOpenDerived: (PersonalDerivedScore) -> Unit,
    initialDay: String? = null,
    openWeight: Boolean = false,
    recoveryKey: String? = null,
    bodyPresentation: BodyPresentationController? = null,
    bodyAssets: AndroidBodyAssetRepository? = null,
    modifier: Modifier = Modifier,
) {
    val observed by controller.state.collectAsStateWithLifecycle()
    val state = observed?.takeIf { it.namespace == lease.namespace }
    PersonalActivation(controller, lease, initialDay)
    val scope = rememberCoroutineScope()
    var detail by remember(lease.namespace) { mutableStateOf<PersonalMetric?>(null) }
    var detailFromSource by remember(lease.namespace) { mutableStateOf(false) }
    var weightOpened by remember(lease.namespace, openWeight) { mutableStateOf(false) }
    var moreOpen by remember(lease.namespace) { mutableStateOf(false) }
    LaunchedEffect(state?.canWrite, openWeight, weightOpened) {
        if (openWeight && !weightOpened && state?.canWrite == true) {
            weightOpened = true
            controller.beginWeight()
        }
    }
    LaunchedEffect(state?.namespace, recoveryKey) {
        if (state != null && recoveryKey != null) {
            controller.check(recoveryKey)
            bodyPresentation?.check(recoveryKey)
        }
    }
    RefreshableScreen(
        tag = "screen_Personal",
        title = stringResource(DesignR.string.personal_title),
        isRefreshing = state?.overview?.let { it.loading && it.data != null } == true,
        onRefresh = { scope.launch { controller.refresh() } },
        modifier = modifier,
        refreshTag = "personal_pull_refresh",
        actions = {
            Box {
                ActionIconButton(DesignR.drawable.lucide_ellipsis, stringResource(DesignR.string.common_more),
                    onClick = { moreOpen = true }, testTag = "personal_more")
                DropdownMenu(expanded = moreOpen, onDismissRequest = { moreOpen = false }) {
                    DropdownMenuItem(
                        text = { Text(stringResource(DesignR.string.dashboard_all_measurements)) },
                        leadingIcon = { Icon(painterResource(DesignR.drawable.lucide_rotate_ccw_clock), null, Modifier.size(20.dp)) },
                        onClick = { moreOpen = false; onOpenHistory() },
                        modifier = Modifier.heightIn(min = 48.dp).testTag("personal_open_history"),
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(DesignR.string.personal_body_profile_title)) },
                        leadingIcon = { Icon(painterResource(DesignR.drawable.lucide_user_round), null, Modifier.size(20.dp)) },
                        onClick = { moreOpen = false; onOpenProfile() },
                        modifier = Modifier.heightIn(min = 48.dp).testTag("personal_open_profile"),
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(DesignR.string.personal_sources)) },
                        leadingIcon = { Icon(painterResource(DesignR.drawable.lucide_plug), null, Modifier.size(20.dp)) },
                        onClick = { moreOpen = false; onOpenSources() },
                        modifier = Modifier.heightIn(min = 48.dp).testTag("personal_open_sources"),
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(DesignR.string.sources_goals)) },
                        leadingIcon = { Icon(painterResource(DesignR.drawable.lucide_goal), null, Modifier.size(20.dp)) },
                        onClick = { moreOpen = false; onOpenGoals() },
                        modifier = Modifier.heightIn(min = 48.dp).testTag("personal_open_goals"),
                    )
                }
            }
        },
    ) {
        Text(stringResource(R.string.privacy_health_disclaimer), style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (state == null) Text(stringResource(DesignR.string.state_loading))
        state?.let { current ->
            PersonalDayNavigation(current) { day -> scope.launch { controller.selectDay(day) } }
            PersonalQueryStatus(current.overview) { scope.launch { controller.refresh() } }
            if (!current.canRead) UnavailableCard()
            if (current.weightDraft == null && current.caliperDraft == null) PersonalWriteStatus(current, controller, bodyPresentation)
            current.overview.data?.let { overview ->
                val units = overview.profile.displayUnits
                overview.metrics["weight"]?.let { metric ->
                    PersonalSection(DesignR.string.personal_metric_weight, "personal_weight") {
                        PersonalMetricRow(metric, units, current, showLabel = false) { detailFromSource = false; detail = metric }
                        if (current.canWrite) PersonalAction(DesignR.string.personal_add_weight, "personal_add_weight",
                            enabled = !current.busy) { controller.beginWeight() }
                    }
                }
                if (bodyPresentation != null && bodyAssets != null) PersonalOverviewBodyAtlas(
                    overview, current.selectedDay, current.canWrite, current.timezone, current.locale, lease,
                    bodyPresentation, bodyAssets, onOpenProfile)
                PersonalSection(DesignR.string.personal_body_composition, "personal_body") {
                    PERSONAL_BODY_KINDS.forEach { kind -> overview.metrics[kind]?.let { metric ->
                        PersonalMetricRow(metric, units, current) { detailFromSource = false; detail = metric }
                    } }
                    overview.metricsBySource["body_fat_percent"].orEmpty().forEach { metric ->
                        PersonalMetricRow(metric, units, current, "personal_source_metric_${metric.sourceId.orEmpty()}_body_fat_percent") {
                            detailFromSource = true
                            detail = metric
                        }
                    }
                    PersonalQueryStatus(current.caliper) { scope.launch { controller.refresh() } }
                    current.caliper.data?.records?.maxByOrNull { it.measuredAt }?.let {
                        PersonalCaliperResult(it, current)
                    }
                    if (current.canWrite) PersonalAction(DesignR.string.personal_caliper_add, "personal_add_caliper",
                        enabled = !current.busy) { controller.beginCaliper() }
                }
                PersonalNutrition(current, controller)
                PersonalSection(DesignR.string.personal_day_metrics, "personal_daily") {
                    overview.metrics["sleep"]?.let { metric ->
                        PersonalMetricRow(metric, units, current) { detailFromSource = false; detail = metric }
                    }
                }
                val sensors = current.resolved.data?.daily.orEmpty().map { it.metric }
                    .filter { it.kind in PERSONAL_SENSOR_KINDS && it.value != null }
                if (current.resolved.loading || current.resolved.error != null || sensors.isNotEmpty()) {
                    PersonalSection(DesignR.string.sensor_day_summary_title, "personal_sensors") {
                        PersonalQueryStatus(current.resolved) { scope.launch { controller.refresh() } }
                        sensors.forEach { metric -> PersonalMetricRow(metric, units, current) { detailFromSource = false; detail = metric } }
                    }
                }
                PersonalSection(DesignR.string.derived_scores_title, "personal_derived_scores") {
                    PersonalQueryStatus(current.derivedScores) { scope.launch { controller.refresh() } }
                    val scores = current.derivedScores.data?.days.orEmpty().flatMap { it.scores }
                    if (scores.isEmpty() && !current.derivedScores.loading && current.derivedScores.error == null)
                        Text(stringResource(DesignR.string.personal_no_data))
                    scores.forEach { score -> PersonalDerivedSummary(score, current) { onOpenDerived(score) } }
                }
            }
        }
    }
    detail?.let { selected ->
        val current = state
        val updated = if (detailFromSource) current?.overview?.data?.metricsBySource?.get(selected.kind)
            ?.firstOrNull { it.sourceId == selected.sourceId } ?: selected
        else current?.overview?.data?.metrics?.get(selected.kind)
            ?: current?.resolved?.data?.daily?.firstOrNull { it.metric.kind == selected.kind }?.metric ?: selected
        PersonalSheet(personalMetricLabel(selected.kind), "personal_metric_detail", onDismiss = { detail = null }) {
            if (current != null) {
                val records = updated.records.sortedByDescending { it.startAt }
                Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                    PersonalMetricValue(updated, current.overview.data?.profile?.displayUnits ?: PersonalDisplayUnits.Metric, current)
                    if (records.isEmpty()) {
                        (updated.resolution?.measuredAt ?: updated.resolution?.selected?.firstOrNull { it.startAt != null }?.startAt)
                            ?.let { Text(personalMoment(it, current.timezone, current.locale), modifier = Modifier.testTag("personal_detail_date"),
                                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                        personalMetricSources(updated).forEach { Text(it, modifier = Modifier.testTag("personal_detail_source"),
                            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    }
                }
                updated.resolution?.let { PersonalResolutionEvidence(it, current) }
                records.forEach { record ->
                    HorizontalDivider()
                    Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                        Text(personalAmountText(record.kind, record.value, current.overview.data?.profile?.displayUnits
                            ?: PersonalDisplayUnits.Metric, current.locale) ?: stringResource(DesignR.string.personal_no_data),
                            style = MaterialTheme.typography.headlineSmall)
                        Text(personalMoment(record.startAt, current.timezone, current.locale),
                            modifier = Modifier.testTag("personal_detail_date"), style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(personalProvenance(record, current.locale), modifier = Modifier.testTag("personal_detail_source"),
                            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
    state?.let { current ->
        if (current.weightDraft != null) PersonalWeightSheet(current, controller)
        if (current.caliperDraft != null) PersonalCaliperSheet(current, controller, onOpenProfile)
    }
}

@Composable
private fun PersonalActivation(controller: PersonalController, lease: SessionLease, initialDay: String? = null) {
    val owner = LocalLifecycleOwner.current
    DisposableEffect(controller, lease, owner, initialDay) {
        val visibilityOwner = Any()
        var initial = true
        fun activate() { controller.activate(if (initial) initialDay else null, visibilityOwner); initial = false }
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
internal fun PersonalProfileScreen(controller: PersonalController, lease: SessionLease, modifier: Modifier = Modifier) {
    val observed by controller.state.collectAsStateWithLifecycle()
    val state = observed?.takeIf { it.namespace == lease.namespace }
    PersonalActivation(controller, lease)
    val scope = rememberCoroutineScope()
    LaunchedEffect(state?.overview?.data?.profile?.revision, state?.profileDraft == null, state?.busy) {
        if (state?.overview?.data != null && state.profileDraft == null && !state.busy) controller.beginProfile()
    }
    ScreenColumn("screen_PersonalProfile", modifier, title = stringResource(DesignR.string.personal_profile)) {
        if (state == null) Text(stringResource(DesignR.string.state_loading))
        state?.let { current ->
            PersonalQueryStatus(current.overview) { scope.launch { controller.refresh() } }
            if (!current.canRead) UnavailableCard()
            PersonalWriteStatus(current, controller)
            current.overview.data?.profile?.let { profile ->
                PersonalDisplayUnits(current, controller)
                AppCard {
                    CardTitle(stringResource(DesignR.string.personal_body_profile_title))
                    Text(stringResource(DesignR.string.personal_body_profile_hint))
                    current.profileDraft?.let { draft ->
                        val editable = current.canWrite && !current.busy && current.pending.isEmpty()
                        Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                            PersonalField(draft.birthDate, { controller.updateProfileDraft(draft.copy(birthDate = it)) },
                                DesignR.string.personal_birth_date, "personal_profile_birth", editable)
                            Text(stringResource(DesignR.string.personal_birth_hint), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            val age = personalAgeOn(draft.birthDate.ifBlank { null }, current.today)
                            Text(stringResource(DesignR.string.personal_age) + " · " + (age?.toString() ?: "—"),
                                modifier = Modifier.testTag("personal_profile_age"))
                        }
                        PersonalGenderChoice(draft.gender, editable, "personal_profile_gender") {
                            controller.updateProfileDraft(draft.copy(gender = it))
                        }
                        Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                            profile.heightCm?.let { height -> personalHeightText(height, profile.displayUnits, current.locale)?.let {
                                Text(it, modifier = Modifier.testTag("personal_profile_height_display"))
                            } }
                            Text(stringResource(DesignR.string.personal_forms_height_input_hint), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            PersonalField(draft.heightCm, { controller.updateProfileDraft(draft.copy(heightCm = it)) },
                                DesignR.string.personal_height, "personal_profile_height", editable, KeyboardType.Decimal)
                        }
                        Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                            PersonalField(draft.timezone, { controller.updateProfileDraft(draft.copy(timezone = it)) },
                                DesignR.string.personal_timezone, "personal_profile_zone", editable)
                            Text(stringResource(DesignR.string.personal_timezone_hint), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        if (current.canWrite) Button(onClick = { scope.launch { controller.saveProfile() } },
                            enabled = editable && personalProfileDraftChanged(draft),
                            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("personal_profile_save")) {
                            Text(stringResource(DesignR.string.personal_save_profile))
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun PersonalGoalsScreen(controller: PersonalController, lease: SessionLease, modifier: Modifier = Modifier) {
    val observed by controller.state.collectAsStateWithLifecycle()
    val state = observed?.takeIf { it.namespace == lease.namespace }
    PersonalActivation(controller, lease)
    val scope = rememberCoroutineScope()
    ScreenColumn("screen_PersonalGoals", modifier, title = stringResource(DesignR.string.sources_goals)) {
        if (state == null) Text(stringResource(DesignR.string.state_loading))
        state?.let { current ->
            PersonalQueryStatus(current.overview) { scope.launch { controller.refresh() } }
            if (!current.canRead) UnavailableCard()
            PersonalWriteStatus(current, controller)
            current.overview.data?.let { overview ->
                PersonalDisplayUnits(current, controller)
                PersonalSection(DesignR.string.resolution_targets, "personal_goals") {
                    PERSONAL_TARGET_KINDS.forEach { kind ->
                        Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs), modifier = Modifier.fillMaxWidth()
                            .testTag("personal_goal_$kind")) {
                            Text(personalMetricLabel(kind), style = MaterialTheme.typography.titleMedium)
                            overview.preferences[kind]?.target?.let { target ->
                                Text(stringResource(DesignR.string.personal_target,
                                    personalAmountText(kind, target, overview.profile.displayUnits, current.locale).orEmpty()))
                            }
                            val draft = current.goalDraft?.takeIf { it.kind == kind }
                            if (draft == null && current.canWrite) OutlinedButton(onClick = {
                                scope.launch { controller.dismissDraft("goal"); controller.beginGoal(kind) }
                            },
                                enabled = !current.busy, modifier = Modifier.heightIn(min = 48.dp)
                                    .testTag("personal_goal_edit_$kind")) { Text(stringResource(DesignR.string.common_edit)) }
                            if (draft != null) {
                                val editable = current.canWrite && !current.busy && current.pending.isEmpty()
                                OutlinedTextField(value = draft.target,
                                    onValueChange = { controller.updateGoalDraft(draft.copy(target = it)) },
                                    label = { Text(stringResource(DesignR.string.personal_target_of, personalGoalInputUnit(kind, draft.displayUnits))) },
                                    enabled = editable, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                                    modifier = Modifier.fillMaxWidth().testTag("personal_goal_value"))
                                Button(onClick = { scope.launch { controller.saveGoal() } }, enabled = editable,
                                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("personal_goal_save")) {
                                    Text(stringResource(DesignR.string.common_save))
                                }
                                TextButton(onClick = { scope.launch { controller.dismissDraft("goal") } }, enabled = !current.busy,
                                    modifier = Modifier.heightIn(min = 48.dp).testTag("personal_goal_cancel")) {
                                    Text(stringResource(DesignR.string.common_cancel))
                                }
                            }
                        }
                        HorizontalDivider()
                    }
                }
            }
        }
    }
}

@Composable
private fun PersonalDisplayUnits(state: PersonalState, controller: PersonalController) {
    val units = state.overview.data?.profile?.displayUnits ?: return
    val scope = rememberCoroutineScope()
    PersonalSection(DesignR.string.display_units_title, "personal_display_units") {
        Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
            Text(stringResource(if (units == PersonalDisplayUnits.Imperial) DesignR.string.display_units_imperial_summary
                else DesignR.string.display_units_metric_summary), style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(stringResource(DesignR.string.display_units_hint), style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        val labels = mapOf(
            PersonalDisplayUnits.Metric to stringResource(DesignR.string.display_units_metric),
            PersonalDisplayUnits.Imperial to stringResource(DesignR.string.display_units_imperial),
        )
        SegmentedChoice(
            options = listOf(PersonalDisplayUnits.Metric, PersonalDisplayUnits.Imperial),
            selected = units,
            onSelect = { scope.launch { controller.setDisplayUnits(it.wire) } },
            label = { labels.getValue(it) },
            enabled = state.canWrite && !state.busy && state.pending.isEmpty(),
            optionTestTag = { "personal_units_${it.wire}" },
        )
    }
}

@Composable
private fun PersonalDayNavigation(state: PersonalState, onSelect: (String) -> Unit) {
    val today = state.selectedDay == state.today
    val day = personalDay(state.selectedDay, state.locale)
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Spacing.s), verticalAlignment = Alignment.CenterVertically) {
        ActionIconButton(DesignR.drawable.lucide_chevron_left, stringResource(DesignR.string.day_previous),
            onClick = { onSelect(LocalDate.parse(state.selectedDay).minusDays(1).toString()) }, testTag = "personal_previous_day")
        Box(Modifier.weight(1f).heightIn(min = 48.dp).testTag("personal_today")
            .then(if (today) Modifier else Modifier.clickable(role = Role.Button) { onSelect(state.today) }),
            contentAlignment = Alignment.Center) {
            Text(if (today) stringResource(DesignR.string.day_today) + " · " + day else day,
                style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center, modifier = Modifier.testTag("personal_date"))
        }
        ActionIconButton(DesignR.drawable.lucide_chevron_right, stringResource(DesignR.string.day_next),
            onClick = { onSelect(LocalDate.parse(state.selectedDay).plusDays(1).toString()) },
            enabled = state.selectedDay < state.today, testTag = "personal_next_day")
    }
}

@Composable
private fun PersonalMetricRow(metric: PersonalMetric, units: PersonalDisplayUnits, state: PersonalState,
    tag: String = "personal_metric_${metric.kind}", showLabel: Boolean = true, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth().clickable(onClick = onClick).heightIn(min = 64.dp)
        .testTag(tag), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
        if (showLabel) Text(personalMetricLabel(metric.kind), style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        PersonalMetricValue(metric, units, state)
        personalMetricSources(metric).takeIf { it.isNotEmpty() }?.let { Text(it.joinToString(" · "),
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        metric.resolution?.measuredAt?.let { Text(personalMoment(it, state.timezone, state.locale),
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        metric.resolution?.let { PersonalResolutionEvidence(it, state) }
    }
}

@Composable
private fun PersonalMetricValue(metric: PersonalMetric, units: PersonalDisplayUnits, state: PersonalState) {
    val analysis = metric.resolution?.sleepAnalysis
    if (metric.kind == "sleep" && analysis != null) PersonalSleepSummary(analysis, state)
    else {
        Text(if (metric.status == "conflict") personalSleepConflictLabel(personalSleepConflict(metric.resolution))
            else (if (metric.kind in PERSONAL_SENSOR_KINDS) personalSensorAmount(metric.value, metric.unit, units, state.locale)
                else personalAmountText(metric.kind, metric.value, units, state.locale)) ?: stringResource(DesignR.string.personal_no_data),
            style = MaterialTheme.typography.headlineSmall, modifier = Modifier.testTag("personal_value_${metric.kind}"))
        if (metric.kind == "sleep" && metric.value == null) metric.resolution?.sessions.orEmpty().forEach { session ->
            Text(personalInterval(session.startAt, session.endAt, state.timezone, state.locale),
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    if (metric.sourceUnavailable || metric.status == "unavailable") Text(stringResource(DesignR.string.personal_source_down),
        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    else if (metric.complete == false && metric.value != null) Text(stringResource(DesignR.string.personal_incomplete),
        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    metric.target?.let { target -> Text(stringResource(DesignR.string.personal_target,
        personalAmountText(metric.kind, target, units, state.locale).orEmpty()), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
}

@Composable
private fun PersonalSleepSummary(analysis: PersonalSleepAnalysis, state: PersonalState) {
    val summary = analysis.summary
    val palette = if (MaterialTheme.colorScheme.background == GeneratedColors.dark.getValue("background"))
        GeneratedColors.dark else GeneratedColors.light
    val unclassified = summary.asleepMinutes == 0.0 && summary.awakeMinutes == 0.0
    Text(when {
        summary.completeAsleepMinutes != null -> personalDuration(requireNotNull(summary.completeAsleepMinutes), state.locale)
        analysis.episodes.isNotEmpty() && unclassified -> stringResource(DesignR.string.personal_period_only)
        analysis.episodes.isNotEmpty() -> stringResource(DesignR.string.personal_sleep_analysis_recognized,
            personalDuration(summary.asleepMinutes, state.locale))
        analysis.status == "conflict" -> stringResource(DesignR.string.personal_sleep_analysis_report_conflict)
        else -> stringResource(DesignR.string.personal_no_data)
    }, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.testTag("personal_value_sleep"))
    analysis.episodes.forEach { episode ->
        Text(personalInterval(episode.startAt, episode.endAt, state.timezone, state.locale), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        val total = runCatching { Duration.between(Instant.parse(episode.startAt), Instant.parse(episode.endAt)).seconds.toDouble() }.getOrDefault(0.0)
        val stripLabel = stringResource(DesignR.string.personal_metric_sleep)
        if (total > 0 && episode.segments.isNotEmpty()) Row(Modifier.fillMaxWidth().height(14.dp)
            .semantics { contentDescription = stripLabel }) {
            episode.segments.forEach { segment ->
                val seconds = runCatching { Duration.between(Instant.parse(segment.startAt), Instant.parse(segment.endAt)).seconds.toFloat() }
                    .getOrDefault(0f)
                if (seconds > 0) androidx.compose.foundation.layout.Box(Modifier.weight(seconds).height(14.dp)
                    .background(when (segment.state) {
                        "sleep" -> MaterialTheme.colorScheme.primary
                        "awake" -> palette.getValue("warning-fill")
                        "conflict" -> MaterialTheme.colorScheme.error
                        else -> MaterialTheme.colorScheme.surfaceVariant
                    }))
            }
        }
    }
    if (summary.completeAsleepMinutes == null && analysis.episodes.isNotEmpty()) Text(stringResource(
        DesignR.string.personal_sleep_analysis_unclassified, personalDuration(summary.unknownMinutes, state.locale)),
        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    summary.forecastAsleepMinutes?.let { Text(stringResource(DesignR.string.personal_sleep_analysis_forecast,
        personalDuration(kotlin.math.floor(it / 5 + 0.5) * 5, state.locale)), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    if (summary.conflictMinutes > 0) Text(stringResource(DesignR.string.personal_sleep_analysis_conflict_subset,
        personalDuration(summary.conflictMinutes, state.locale)), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun PersonalDerivedSummary(score: PersonalDerivedScore, state: PersonalState, onOpen: () -> Unit) {
    Column(Modifier.fillMaxWidth().clickable(onClick = onOpen).testTag("personal_score_${score.definitionId}"), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
        Text(score.label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        val visible = personalScoreValue(score)
        Text(visible?.let { "${formatFoodAmount(it, 2)} ${score.unit}" } ?: stringResource(DesignR.string.personal_no_data), style = MaterialTheme.typography.headlineSmall)
        Text(stringResource(when (score.status) {
            "available" -> DesignR.string.derived_scores_status_available
            "partial" -> DesignR.string.derived_scores_status_partial
            "unqualified" -> DesignR.string.derived_scores_status_unqualified
            else -> DesignR.string.derived_scores_status_unavailable
        }), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(personalMoment(score.evaluatedAt, state.timezone, state.locale), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(score.context.sourceId?.let { stringResource(DesignR.string.sources_line, it) }
            ?: stringResource(DesignR.string.derived_scores_owner_context), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        PersonalQuality(score.quality)
        if (score.missing.isNotEmpty()) {
            Text(stringResource(DesignR.string.derived_missing_inputs_title), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            score.missing.forEach { code ->
                val message = personalDerivedMissingMessage(code)
                Text(localized(message.withLabel(localized(message.label))), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        HorizontalDivider()
    }
}

@Composable
internal fun <T> PersonalQueryStatus(query: PersonalQuery<T>, onRefresh: () -> Unit) {
    if (query.loading) ProgressBar(modifier = Modifier.fillMaxWidth())
    query.error?.let { error ->
        if (query.data != null) Text(stringResource(DesignR.string.state_refresh_failed), modifier = Modifier.semantics {
            liveRegion = LiveRegionMode.Polite })
        ErrorMessage(error, "personal_query_error")
        OutlinedButton(onClick = onRefresh, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(DesignR.string.common_retry)) }
    }
}

@Composable
internal fun PersonalWriteStatus(state: PersonalState, controller: PersonalController, bodyPresentation: BodyPresentationController? = null) {
    val scope = rememberCoroutineScope()
    if (state.conflict) {
        Text(stringResource(DesignR.string.personal_conflict), color = MaterialTheme.colorScheme.error,
            modifier = Modifier.testTag("personal_conflict").semantics { liveRegion = LiveRegionMode.Assertive })
        Text(stringResource(DesignR.string.personal_forms_draft_kept), modifier = Modifier.testTag("personal_draft_kept"))
    }
    state.error?.let { ErrorMessage(it, "personal_error") }
    if (state.pending.isNotEmpty()) PersonalSection(DesignR.string.outcome_unknown_title, "personal_pending") {
        Text(stringResource(DesignR.string.outcome_unknown_text))
        state.pending.forEach { pending -> OutlinedButton(onClick = { scope.launch {
            if (isBodyPresentationSubmission(pending) && bodyPresentation != null) bodyPresentation.check(pending.key)
            else controller.check(pending.key)
        } },
            enabled = !state.busy, modifier = Modifier.heightIn(min = 48.dp).testTag("personal_pending_check_${pending.key}")) {
            Text(stringResource(DesignR.string.outcome_check))
        } }
    }
    state.receipt?.let { receipt ->
        val label = when (receipt.kind.removePrefix("personal.")) {
            "profile.update" -> DesignR.string.personal_profile_saved
            "preference.set" -> DesignR.string.personal_target_saved
            "weight.add" -> DesignR.string.personal_weight_saved
            "caliper.add" -> DesignR.string.personal_caliper_saved
            else -> null
        }
        if (label != null) Text(stringResource(label), modifier = Modifier.testTag("personal_receipt")
            .semantics { liveRegion = LiveRegionMode.Polite })
    }
}

@Composable
internal fun PersonalSection(title: Int, tag: String, content: @Composable ColumnScope.() -> Unit) {
    AppCard(testTag = tag) {
        CardTitle(stringResource(title))
        content()
    }
}

@Composable
internal fun PersonalAction(label: Int, tag: String, enabled: Boolean = true, onClick: () -> Unit) {
    FilledTonalButton(onClick = onClick, enabled = enabled, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag(tag)) {
        Text(stringResource(label))
    }
}

@Composable
internal fun PersonalField(value: String, onChange: (String) -> Unit, label: Int, tag: String, enabled: Boolean,
    keyboardType: KeyboardType = KeyboardType.Text) {
    OutlinedTextField(value = value, onValueChange = onChange, enabled = enabled,
        label = { Text(stringResource(label)) }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
        modifier = Modifier.fillMaxWidth().testTag(tag))
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PersonalSheet(title: String, tag: String, onDismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).imePadding().padding(Spacing.l).testTag(tag),
            verticalArrangement = Arrangement.spacedBy(Spacing.m)) {
            Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
            content()
            OutlinedButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("${tag}_close")) {
                Text(stringResource(DesignR.string.common_close))
            }
        }
    }
}

@Composable
internal fun PersonalGenderChoice(value: String, enabled: Boolean, tag: String, onChange: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
        Text(stringResource(DesignR.string.personal_gender), style = MaterialTheme.typography.labelLarge)
        val labels = mapOf(
            "" to stringResource(DesignR.string.personal_gender_unset),
            "male" to stringResource(DesignR.string.personal_gender_male),
            "female" to stringResource(DesignR.string.personal_gender_female),
        )
        SegmentedChoice(options = labels.keys.toList(), selected = value, onSelect = onChange,
            label = { labels.getValue(it) }, enabled = enabled, testTag = tag,
            optionTestTag = { "${tag}_${it.ifBlank { "unset" }}" })
    }
}

@Composable
internal fun personalMetricLabel(kind: String): String = stringResource(when (kind) {
    "weight" -> DesignR.string.personal_metric_weight
    "sleep" -> DesignR.string.personal_metric_sleep
    "steps" -> DesignR.string.personal_metric_steps
    "active_minutes" -> DesignR.string.personal_metric_active_minutes
    "active_energy" -> DesignR.string.personal_metric_active_energy
    "distance" -> DesignR.string.personal_metric_distance
    "body_fat_percent" -> DesignR.string.personal_metric_body_fat_percent
    "body_fat_mass" -> DesignR.string.personal_metric_body_fat_mass
    "skeletal_muscle_mass" -> DesignR.string.personal_metric_skeletal_muscle_mass
    "fat_free_mass" -> DesignR.string.personal_metric_fat_free_mass
    "body_water" -> DesignR.string.personal_metric_body_water
    "body_protein" -> DesignR.string.personal_metric_body_protein
    "body_minerals" -> DesignR.string.personal_metric_body_minerals
    "bmi" -> DesignR.string.personal_metric_bmi
    "basal_metabolic_rate" -> DesignR.string.personal_metric_basal_metabolic_rate
    "visceral_fat_level" -> DesignR.string.personal_metric_visceral_fat_level
    "heart_rate" -> DesignR.string.ring_metric_heart_rate
    "spo2" -> DesignR.string.ring_metric_spo2
    "temperature" -> DesignR.string.ring_metric_temperature
    "hrv" -> DesignR.string.ring_metric_hrv
    "stress" -> DesignR.string.ring_metric_stress
    else -> DesignR.string.personal_metric_nutrition
})

internal fun personalGoalInputUnit(kind: String, units: String): String = when (kind) {
    "distance" -> if (units == "imperial") "мили" else "км"
    "weight" -> "кг"
    "sleep", "active_minutes" -> "мин"
    "steps" -> "шагов"
    "active_energy" -> "ккал"
    else -> ""
}

internal fun personalMoment(value: String, timezone: String, locale: String): String = runCatching {
    Instant.parse(value).atZone(ZoneId.of(timezone))
        .format(DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm", Locale.forLanguageTag(locale)))
}.getOrDefault(value)

private fun personalDay(value: String, locale: String): String = runCatching {
    LocalDate.parse(value).format(DateTimeFormatter.ofPattern("d MMMM yyyy", Locale.forLanguageTag(locale)))
}.getOrDefault(value)

private fun personalInterval(start: String, end: String, timezone: String, locale: String): String = runCatching {
    val zone = ZoneId.of(timezone)
    val first = Instant.parse(start).atZone(zone)
    val last = Instant.parse(end).atZone(zone)
    val formatter = DateTimeFormatter.ofPattern("HH:mm", Locale.forLanguageTag(locale))
    val firstLabel = if (first.toLocalDate() == last.toLocalDate()) first.format(formatter)
        else first.format(DateTimeFormatter.ofPattern("d MMM HH:mm", Locale.forLanguageTag(locale)))
    "$firstLabel–${last.format(formatter)}"
}.getOrDefault("$start–$end")
