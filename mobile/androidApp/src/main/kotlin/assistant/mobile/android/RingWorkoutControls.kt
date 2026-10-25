package assistant.mobile.android

import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import assistant.mobile.core.GeneratedStrings
import assistant.mobile.core.LocalizedString
import assistant.mobile.core.SessionLease
import assistant.mobile.core.activity.WorkoutPulse
import assistant.mobile.design.R as DesignR
import assistant.mobile.platform.ring.RingController
import assistant.mobile.platform.ring.RingWorkoutAction
import assistant.mobile.platform.ring.RingWorkoutNativeState
import assistant.mobile.platform.ring.RingWorkoutPermission
import java.time.Instant
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first

private val workoutScreenGeneration = AtomicLong()

private data class WorkoutUiOwner(
    val userId: String, val sourceId: String?, val grantGeneration: Long?, val leaseGeneration: Long,
    val canRead: Boolean, val canWrite: Boolean,
)

private data class WorkoutPermissionRequest(
    val owner: WorkoutUiOwner, val kind: RingWorkoutPermission, val start: RingWorkoutAction.Start? = null,
)

private data class WorkoutPermissionCompletion(val request: WorkoutPermissionRequest, val granted: Boolean)

/** A visible screen owns observation only. Accepted recordings remain owned by the native runtime. */
@Composable
internal fun LiveRingWorkoutCard(
    controller: RingController,
    lease: SessionLease,
    timezone: String,
    canRead: Boolean,
    canWrite: Boolean,
) {
    val ring by controller.states.collectAsStateWithLifecycle()
    val device by controller.deviceStates.collectAsStateWithLifecycle()
    val observed by controller.workoutStates.collectAsStateWithLifecycle()
    val userId = lease.namespace.userId
    val sourceId = ring.sourceId.takeIf { ring.userId == userId }
    val grant = device.bindingGeneration.takeIf { device.userId == userId && device.sourceId == sourceId && it > 0 }
    val owner = WorkoutUiOwner(userId, sourceId, grant, lease.generation, canRead, canWrite)
    val currentOwner by rememberUpdatedState(owner)
    val actions = remember(owner) { RingUiActions() }
    val lifecycleOwner = LocalLifecycleOwner.current
    val generation = remember(controller, lifecycleOwner, owner) { workoutScreenGeneration.incrementAndGet() }
    var pending by remember(owner) { mutableStateOf<WorkoutPermissionRequest?>(null) }
    var completed by remember(owner) { mutableStateOf<WorkoutPermissionCompletion?>(null) }
    val permissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { answers ->
        val request = pending
        if (request != null && request.owner == currentOwner)
            completed = WorkoutPermissionCompletion(request, answers.isNotEmpty() && answers.values.all { it })
    }
    val settings = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        val request = pending
        if (request != null && request.owner == currentOwner) completed = WorkoutPermissionCompletion(request, true)
    }
    DisposableEffect(controller, lifecycleOwner, owner) {
        fun observe(visible: Boolean) {
            if (sourceId != null && grant != null) actions.run {
                controller.workoutScreen(visible, generation, userId, sourceId, grant, canRead, canWrite)
            }
        }
        val observer = LifecycleEventObserver { _, event -> when (event) {
            Lifecycle.Event.ON_START -> observe(true)
            Lifecycle.Event.ON_STOP -> observe(false)
            else -> Unit
        } }
        lifecycleOwner.lifecycle.addObserver(observer)
        if (lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) observe(true)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            observe(false)
        }
    }
    LaunchedEffect(controller, lifecycleOwner, owner, completed) {
        val result = completed ?: return@LaunchedEffect
        // ActivityResult may arrive before other ON_START observers, and settings may stop the Activity.
        lifecycleOwner.lifecycle.currentStateFlow.first { it.isAtLeast(Lifecycle.State.STARTED) }
        if (result.request.owner == currentOwner && sourceId != null && grant != null) actions.run {
            controller.workoutScreen(true, generation, userId, sourceId, grant, canRead, canWrite)
            // Rebind the private permission receipt after a lifecycle detach; this does not open system UI.
            controller.workoutPermissionNames(result.request.kind)
            controller.workoutPermissionResult(result.request.kind, result.granted)
            if (result.granted) result.request.start?.let { controller.workout(it) }
        }
        pending = null
        completed = null
    }
    val state = observed.takeIf { canRead && it.userId == userId && sourceId != null &&
        it.sourceId == sourceId && grant != null && it.bindingGeneration == grant }
    val currentState by rememberUpdatedState(state)
    var now by remember(owner) { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(controller, lifecycleOwner, owner) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                now = System.currentTimeMillis()
                val latest = currentState
                if (latest != null && latest.recordingSource == "phone_only" && latest.workoutId != null &&
                    latest.endedAt == null && latest.phase in setOf("recording", "grace", "paused", "pausing", "resuming")) {
                    actions.run { controller.workout(RingWorkoutAction.Refresh) }
                }
                delay(1_000)
            }
        }
    }
    fun permission(kind: RingWorkoutPermission, start: RingWorkoutAction.Start? = null) {
        if (!canWrite || sourceId == null || grant == null || pending != null) return
        actions.run {
            val names = controller.workoutPermissionNames(kind)
            when {
                kind == RingWorkoutPermission.BACKGROUND_LOCATION && Build.VERSION.SDK_INT >= 30 -> {
                    pending = WorkoutPermissionRequest(owner, kind)
                    settings.launch(controller.workoutLocationSettingsIntent())
                }
                names.isEmpty() -> {
                    controller.workoutPermissionResult(kind, true)
                    start?.let { controller.workout(it) }
                }
                else -> {
                    pending = WorkoutPermissionRequest(owner, kind, start)
                    permissions.launch(names)
                }
            }
        }
    }
    if (!canRead) return
    RingWorkoutCardContent(state, timezone, canWrite, now, disabled = pending != null,
        onAction = { action ->
            if (action is RingWorkoutAction.Start) permission(RingWorkoutPermission.NOTIFICATIONS, action)
            else actions.run { controller.workout(action) }
        }, onPermission = { permission(it) })
    actions.error?.let { RingError(it.code, it.message, "ring_workout_action_error") }
}

/** The same snapshot drives the compact Activity card and the full recorder sheet. */
@Composable
internal fun RingWorkoutCardContent(
    state: RingWorkoutNativeState?,
    timezone: String = "UTC",
    canWrite: Boolean = true,
    now: Long = System.currentTimeMillis(),
    disabled: Boolean = false,
    onAction: (RingWorkoutAction) -> Unit,
    onPermission: (RingWorkoutPermission) -> Unit,
) {
    var detailsOpen by remember(state?.userId, state?.sourceId, state?.bindingGeneration) { mutableStateOf(false) }
    val active = state?.let { it.commandUnknown || it.phase in activeWorkoutPhases } == true
    val busy = disabled || state?.phase in setOf("starting", "pausing", "resuming", "stopping")
    if (active && state != null) ActivitySection(state.sportPlan?.name ?: localized(GeneratedStrings.Ring.Workout.title), "live_workout_active") {
        Text(localized(workoutPhase(state.phase)), modifier = Modifier.testTag("live_workout_phase"))
        state.startedAt?.let { Text(stringResource(DesignR.string.activity_native_started, ringMoment(it, timezone))) }
        state.trigger?.let { Text(localized(workoutTrigger(it)), style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.testTag("live_workout_trigger")) }
        if (state.autoBlocked) RingStatus(GeneratedStrings.Ring.Workout.autoBlocked, "live_workout_auto_blocked")
        val pulse = state.lastHr?.takeIf { it.quality != "invalid" }
        if (pulse != null) {
            Text("${pulse.bpm} ${localized(GeneratedStrings.Ring.Workout.bpm)}", style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.testTag("live_workout_pulse"))
            val timestamp = runCatching { Instant.parse(pulse.observedAt).toEpochMilli() }.getOrNull()
            val stale = timestamp == null || timestamp > now || now - timestamp > 15_000
            Text(ringMoment(pulse.observedAt, timezone) + " · " + localized(if (stale) GeneratedStrings.Ring.Workout.stale
                else GeneratedStrings.Ring.Workout.recent), style = MaterialTheme.typography.bodySmall,
                color = if (stale) activityPalette().getValue("warning") else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.testTag("live_workout_pulse_recency"))
            if (pulse.quality == "unknown") RingStatus(GeneratedStrings.Ring.Workout.qualityUnknown, "live_workout_pulse_quality")
        } else Text(stringResource(DesignR.string.activity_native_no_pulse))
        if (state.phase == "paused") RingStatus(GeneratedStrings.Ring.Workout.pausedHint, "live_workout_paused")
        if (state.commandUnknown && state.endedAt != null) RingStatus(GeneratedStrings.Ring.Workout.commandPendingHint, "live_workout_command_pending")
        RingWorkoutTimer(state)
        if (state.phase == "unknown") RingStatus(GeneratedStrings.Ring.Workout.unknownHint, "live_workout_unknown")
        RingWorkoutActions(state, canWrite, busy, "live_workout", onAction)
        TextButton(onClick = { detailsOpen = true }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
            .testTag("live_workout_details_trigger")) { Text(stringResource(DesignR.string.activity_native_details)) }
        state.error?.let { RingError(it.code, it.message, "live_workout_error") }
    } else TextButton(onClick = { detailsOpen = true }, modifier = Modifier.heightIn(min = 48.dp).testTag("live_workout_details_trigger")) {
        Text(localized(GeneratedStrings.Ring.Workout.title))
    }
    if (detailsOpen) PersonalSheet(localized(GeneratedStrings.Ring.Workout.title), "live_workout_details", { detailsOpen = false }) {
        RingWorkoutControls(state, timezone, canWrite, busy, onAction, onPermission)
    }
}

@Composable
internal fun RingWorkoutControls(
    state: RingWorkoutNativeState?,
    timezone: String = "UTC",
    canWrite: Boolean = true,
    busy: Boolean = false,
    onAction: (RingWorkoutAction) -> Unit,
    onPermission: (RingWorkoutPermission) -> Unit,
) {
    if (state == null) {
        Text(localized(GeneratedStrings.Ring.Workout.connectHint))
        return
    }
    val active = state.commandUnknown || state.phase in activeWorkoutPhases
    val idle = state.phase in setOf("idle", "unavailable")
    var sportId by remember(state.userId, state.sourceId, state.bindingGeneration) { mutableStateOf<Int?>(null) }
    var gpsRequested by remember(state.userId, state.sourceId, state.bindingGeneration) { mutableStateOf(false) }
    var sportsOpen by remember { mutableStateOf(false) }
    LaunchedEffect(state.sportCatalog, state.sportPlan, state.phase) {
        val catalog = state.sportCatalog
        val savedPlan = state.sportPlan
        sportId = if (!idle && savedPlan != null && catalog.any { it.requestedId == savedPlan.requestedId }) savedPlan.requestedId
            else sportId?.takeIf { selected -> catalog.any { it.requestedId == selected } }
                ?: catalog.firstOrNull { it.requestedId == 7 }?.requestedId ?: catalog.firstOrNull()?.requestedId
    }
    val plan = state.sportCatalog.firstOrNull { it.requestedId == sportId }
    LaunchedEffect(plan) { if (plan?.appOutdoorGps != true) gpsRequested = false }
    val gpsReady = state.gps.locationEnabled && state.gps.permission in setOf("precise", "approximate")
    val confirmed = listOf("workout", "live_heart_rate").all { kind -> state.capabilities.any { it.kind == kind && it.status == "verified" } }
    val bound = state.userId != null && state.sourceId != null && state.bindingGeneration > 0
    val takeover = state.ownership == "automatic" && state.phase in setOf("recording", "grace")
    Text(localized(workoutPhase(state.phase)), modifier = Modifier.testTag("live_workout_detail_phase"))
    state.trigger?.let { Text(localized(workoutTrigger(it)), modifier = Modifier.testTag("live_workout_detail_trigger")) }
    if (state.autoBlocked) RingStatus(GeneratedStrings.Ring.Workout.autoBlocked, "live_workout_detail_auto_blocked")
    if (state.sportCatalog.isNotEmpty()) {
        Text(localized(GeneratedStrings.Ring.Workout.sport), style = MaterialTheme.typography.labelLarge)
        Column {
            OutlinedButton(onClick = { sportsOpen = true }, enabled = !busy && canWrite && idle,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("ring_workout_sport")) {
                Text((plan?.name ?: localized(GeneratedStrings.Ring.Workout.sport)) +
                    if (plan?.appOutdoorGps == true) localized(GeneratedStrings.Ring.Workout.phoneGpsSuffix) else "")
            }
            DropdownMenu(expanded = sportsOpen, onDismissRequest = { sportsOpen = false }) {
                state.sportCatalog.forEach { sport -> DropdownMenuItem(
                    text = { Text(sport.name + if (sport.appOutdoorGps) localized(GeneratedStrings.Ring.Workout.phoneGpsSuffix) else "") },
                    onClick = { sportId = sport.requestedId; sportsOpen = false },
                    modifier = Modifier.testTag("ring_workout_sport_${sport.requestedId}")) }
            }
        }
        if (plan?.appOutdoorGps == true) Row(Modifier.fillMaxWidth().heightIn(min = 48.dp)
            .toggleable(value = gpsRequested, enabled = !busy && canWrite && idle, role = Role.Checkbox,
                onValueChange = { gpsRequested = it }).testTag("ring_workout_gps_requested"), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Checkbox(checked = gpsRequested, onCheckedChange = null, enabled = !busy && canWrite && idle)
            Text(localized(GeneratedStrings.Ring.Workout.recordGps), modifier = Modifier.weight(1f))
        }
        if (gpsRequested && !gpsReady) RingStatus(GeneratedStrings.Ring.Workout.gpsPermissionHint, "ring_workout_gps_permission_hint")
        Text(localized(GeneratedStrings.Ring.Workout.sportModeHint), style = MaterialTheme.typography.bodySmall)
        plan?.let { Text(it.family + " · " + localized(if (it.qualification == "verified") GeneratedStrings.Ring.Workout.modeVerified
            else GeneratedStrings.Ring.Workout.modeUnverified), style = MaterialTheme.typography.bodySmall) }
    }
    state.sportPlan?.let { saved -> RingDetails(localized(GeneratedStrings.Ring.Workout.planDetails), "workout_sport_plan") {
        Text(saved.name + " · " + saved.family)
        Text(localized(GeneratedStrings.Ring.Workout.ringMode(saved.actualWireMode?.toString() ?: localized(GeneratedStrings.Ring.Workout.modeNotVerified))))
        Text(localized(GeneratedStrings.Ring.Workout.commandCandidate(saved.candidateWireMode?.toString() ?: localized(GeneratedStrings.Ring.Workout.noCandidate))) +
            " · ${saved.qualification} · ${saved.evidenceRef}")
    } }
    state.recordingSource?.let { recording ->
        Text(localized(when (recording) {
            "phone_only" -> GeneratedStrings.Ring.Workout.RecordingSource.phone_only
            "phone_with_ring_hr" -> GeneratedStrings.Ring.Workout.RecordingSource.phone_with_ring_hr
            else -> GeneratedStrings.Ring.Workout.RecordingSource.ring_controlled
        }) + " · " + localized(if (state.gpsRequested) GeneratedStrings.Ring.Workout.routeSelected else GeneratedStrings.Ring.Workout.routeNotSelected))
    }
    if (!confirmed) Text(localized(GeneratedStrings.Ring.Workout.unverified), style = MaterialTheme.typography.bodySmall)
    ActivitySection(localized(GeneratedStrings.Ring.Workout.pulseTitle), "workout_hr_status") {
        Text(localized(workoutStreamStatus(state.hrStatus)))
        state.hrReason?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
    }
    ActivitySection(localized(GeneratedStrings.Ring.Workout.phoneGps), "workout_gps_status") {
        Text(localized(workoutStreamStatus(state.gps.status)))
        state.gps.reason?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        state.gps.lastFix?.let { fix -> Text(localized(GeneratedStrings.Ring.Workout.gpsAccuracy(ringMoment(fix.at, timezone),
            fix.accuracyMeters.toString(), fix.quality))) }
        if (state.gps.gapCount > 0) Text(localized(GeneratedStrings.Ring.Workout.gpsGaps(state.gps.gapCount.toString())))
        if (canWrite && state.gps.permission != "precise") RingButton(GeneratedStrings.Ring.Workout.locationPermission,
            "ring_workout_location_permission", !busy && bound) { onPermission(RingWorkoutPermission.LOCATION) }
        if (canWrite && state.gps.backgroundPermission != "granted") RingButton(GeneratedStrings.Ring.Workout.backgroundLocationPermission,
            "ring_workout_background_location_permission", !busy && bound) { onPermission(RingWorkoutPermission.BACKGROUND_LOCATION) }
    }
    Text(localized(GeneratedStrings.Ring.Workout.independentStreams), style = MaterialTheme.typography.bodySmall)
    if (state.activityRecognition.available) {
        Text(localized(if (state.activityRecognition.registered) GeneratedStrings.Ring.Workout.autoReady
            else GeneratedStrings.Ring.Workout.autoPermissionHint), style = MaterialTheme.typography.bodySmall)
        if (canWrite && state.activityRecognition.permission != "granted") RingButton(GeneratedStrings.Ring.Workout.permission,
            "ring_workout_activity_permission", !busy && bound) { onPermission(RingWorkoutPermission.ACTIVITY) }
    }
    if (!active) RingWorkoutTimer(state)
    if (!bound) Text(localized(GeneratedStrings.Ring.Workout.connectHint))
    state.startedAt?.let { Text(stringResource(DesignR.string.activity_native_started, ringMoment(it, timezone))) }
    val ended = state.endedAt?.let { runCatching { Instant.parse(it) }.getOrNull() }
    val samples = state.samples.map { sample ->
        val observed = runCatching { Instant.parse(sample.observedAt) }.getOrNull()
        WorkoutPulse(sample.observedAt, sample.bpm, sample.quality,
            if (ended != null && observed != null && observed >= ended) "recovery"
            else if (state.sportType != "running") "workout" else "running")
    }
    if (samples.isNotEmpty()) ActivityHeartRateChart(samples, timezone)
    else Text(localized(GeneratedStrings.Ring.Workout.noSamples))
    if (samples.isNotEmpty() || state.lastHr != null) RingDetails(localized(GeneratedStrings.Ring.Workout.rawPulse), "live_workout_raw_pulse") {
        state.lastHr?.let { Text(it.toString(), style = MaterialTheme.typography.bodySmall) }
        state.samples.forEach { Text(it.toString(), style = MaterialTheme.typography.bodySmall) }
    }
    if (state.gaps > 0) Text(localized(GeneratedStrings.Ring.Workout.gaps(state.gaps.toString())))
    if (state.phase == "unknown") RingStatus(GeneratedStrings.Ring.Workout.unknownHint, "live_workout_detail_unknown")
    Text(localized(GeneratedStrings.Ring.Workout.offlineHint), style = MaterialTheme.typography.bodySmall)
    state.error?.let { RingError(it.code, it.message, "live_workout_detail_error") }
    if (canWrite && (!active || takeover)) RingButton(if (takeover) GeneratedStrings.Ring.Workout.takeover else GeneratedStrings.Ring.Workout.start,
        "live_workout_start", !busy && bound && !state.commandUnknown && (plan != null || confirmed || gpsReady) && (idle || takeover)) {
        onAction(RingWorkoutAction.Start(sportId ?: 7, gpsRequested))
    }
    RingWorkoutActions(state, canWrite, busy, "live_workout_detail", onAction)
}

@Composable
private fun RingWorkoutTimer(state: RingWorkoutNativeState) {
    if (state.elapsedSeconds != null || state.activeDurationSeconds != null) Column(Modifier.testTag("workout_timer")) {
        state.elapsedSeconds?.let { Text(localized(GeneratedStrings.Ring.Workout.totalDuration(workoutDuration(it.toLong())))) }
        state.activeDurationSeconds?.let { Text(localized(GeneratedStrings.Ring.Workout.activeDuration(workoutDuration(it.toLong())))) }
    }
}

@Composable
private fun workoutDuration(seconds: Long): String {
    val total = seconds.coerceAtLeast(0)
    return (if (total >= 3600) stringResource(DesignR.string.admin_quota_hours, (total / 3600).toString()) + " " else "") +
        stringResource(DesignR.string.tasks_minutes_short, (total / 60 % 60).toString()) + " " +
        stringResource(DesignR.string.assistant_activity_seconds, (total % 60).toString())
}

@Composable
private fun RingWorkoutActions(state: RingWorkoutNativeState, canWrite: Boolean, busy: Boolean, tag: String,
    onAction: (RingWorkoutAction) -> Unit) {
    val workoutId = state.workoutId
    val bound = state.userId != null && state.sourceId != null && state.bindingGeneration > 0
    if (canWrite && state.canPause) RingButton(GeneratedStrings.Ring.Workout.pause, "${tag}_pause", !busy && bound && workoutId != null) {
        if (workoutId != null) onAction(RingWorkoutAction.Pause(workoutId))
    }
    if (canWrite && state.canResume) RingButton(GeneratedStrings.Ring.Workout.resume, "${tag}_resume", !busy && bound && workoutId != null) {
        if (workoutId != null) onAction(RingWorkoutAction.Resume(workoutId))
    }
    val canStop = workoutId != null && (state.phase in setOf("starting", "recording", "grace", "paused") ||
        state.phase == "unknown" && state.endedAt == null && state.startedAt != null)
    if (canWrite && canStop) RingButton(GeneratedStrings.Ring.Workout.stop, "${tag}_stop", !busy && bound) {
        if (workoutId != null) onAction(RingWorkoutAction.Stop(workoutId))
    }
    RingButton(GeneratedStrings.Ring.Workout.refresh, "${tag}_refresh", !busy) { onAction(RingWorkoutAction.Refresh) }
}

private val activeWorkoutPhases = setOf("starting", "recording", "pausing", "paused", "resuming", "grace", "recovery", "stopping", "unknown")

private fun workoutTrigger(trigger: String): LocalizedString = when (trigger) {
    "automatic" -> GeneratedStrings.Ring.Workout.Trigger.automatic
    "manual_takeover" -> GeneratedStrings.Ring.Workout.Trigger.manual_takeover
    else -> GeneratedStrings.Ring.Workout.Trigger.manual
}

private fun workoutPhase(phase: String): LocalizedString = when (phase) {
    "starting" -> GeneratedStrings.Ring.Workout.Phase.starting
    "recording" -> GeneratedStrings.Ring.Workout.Phase.recording
    "pausing" -> GeneratedStrings.Ring.Workout.Phase.pausing
    "paused" -> GeneratedStrings.Ring.Workout.Phase.paused
    "resuming" -> GeneratedStrings.Ring.Workout.Phase.resuming
    "grace" -> GeneratedStrings.Ring.Workout.Phase.grace
    "recovery" -> GeneratedStrings.Ring.Workout.Phase.recovery
    "stopping" -> GeneratedStrings.Ring.Workout.Phase.stopping
    "interrupted" -> GeneratedStrings.Ring.Workout.Phase.interrupted
    "unknown" -> GeneratedStrings.Ring.Workout.Phase.unknown
    "unavailable" -> GeneratedStrings.Ring.Workout.Phase.unavailable
    else -> GeneratedStrings.Ring.Workout.Phase.idle
}

private fun workoutStreamStatus(status: String?): LocalizedString = when (status) {
    "waiting_sample" -> GeneratedStrings.Ring.Workout.StreamStatus.waiting_sample
    "waiting_fix" -> GeneratedStrings.Ring.Workout.StreamStatus.waiting_fix
    "recording" -> GeneratedStrings.Ring.Workout.StreamStatus.recording
    "unavailable" -> GeneratedStrings.Ring.Workout.StreamStatus.unavailable
    "gap" -> GeneratedStrings.Ring.Workout.StreamStatus.gap
    "stopped" -> GeneratedStrings.Ring.Workout.StreamStatus.stopped
    "unknown" -> GeneratedStrings.Ring.Workout.StreamStatus.unknown
    else -> GeneratedStrings.Ring.Workout.StreamStatus.idle
}
