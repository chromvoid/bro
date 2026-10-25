package assistant.mobile.android

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import assistant.mobile.core.GeneratedStrings
import assistant.mobile.core.LocalizedString
import assistant.mobile.core.SessionLease
import assistant.mobile.core.ring.RingCollectorsController
import assistant.mobile.platform.ring.RingController
import assistant.mobile.platform.ring.RingNativeState
import assistant.mobile.platform.ring.RingNativeError
import assistant.mobile.platform.ring.RingFailure
import assistant.mobile.platform.ring.RingOtaController
import assistant.mobile.platform.ring.RingPhoneController
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.launch

/** Both entry points use the same native session owner and immutable ring snapshots. */
@Composable
internal fun RingCard(
    controller: RingController,
    timezone: String = "UTC",
    collectors: RingCollectorsController? = null,
    lease: SessionLease? = null,
    onOpenOriginals: ((String) -> Unit)? = null,
) {
    val observed by controller.states.collectAsStateWithLifecycle()
    val expectedUserId = lease?.namespace?.userId
    val state = observed.takeIf { expectedUserId == null || it.userId == expectedUserId }
    val actions = remember(expectedUserId, state?.userId, state?.sourceId) { RingUiActions() }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { answers ->
        actions.run { controller.permissionResult(answers.isNotEmpty() && answers.values.all { it }) }
    }
    if (state == null) {
        OutlinedCard(Modifier.fillMaxWidth().testTag("devices_ring")) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                RingHeading(GeneratedStrings.Ring.title)
                RingStatus(GeneratedStrings.Ring.initializing, "ring_initializing")
            }
        }
        return
    }
    RingCardContent(state, timezone,
        onPermission = { actions.run { permission.launch(controller.permissionNames()) } },
        onScan = { actions.run { controller.scan() } },
        onSelect = { address, _ -> actions.run { controller.select(address) } },
        onSync = { actions.run { controller.sync() } },
        onBackground = { enabled -> actions.run { controller.background(enabled) } },
        onForget = { actions.run { controller.forget() } },
        settings = {
            RingDeviceSettings(controller, expectedUserId)
            val device by controller.deviceStates.collectAsStateWithLifecycle()
            (controller as? RingPhoneController)?.let {
                RingPhoneControls(it, device, state.busy, expectedUserId)
            }
            (controller as? RingOtaController)?.let {
                RingFirmwareUpdate(controller, it, expectedUserId)
            }
        },
        measurements = { RingMeasurementControls(controller, timezone, expectedUserId) },
        stored = { collectors?.let { RingCollectors(it, lease, state.sourceId, timezone, onOpenOriginals) } },
    )
    actions.error?.let { RingError(it.code, it.message, "ring_action_error") }
}

internal class RingUiActions {
    var error by mutableStateOf<RingNativeError?>(null)
        private set
    fun run(action: () -> Unit) {
        error = null
        try { action() }
        catch (failure: RingFailure) { error = RingNativeError(failure.code, failure.message) }
        catch (failure: Exception) { error = RingNativeError("request_failed", failure.message.orEmpty()) }
    }
}

@Composable
internal fun RingCardContent(
    state: RingNativeState,
    timezone: String = "UTC",
    onPermission: () -> Unit,
    onScan: () -> Unit,
    onSelect: (String, String?) -> Unit,
    onSync: () -> Unit,
    onBackground: (Boolean) -> Unit,
    onForget: () -> Unit,
    settings: @Composable () -> Unit = {},
    measurements: @Composable () -> Unit = {},
    stored: @Composable () -> Unit = {},
) {
    var forgetting by remember(state.userId, state.sourceId) { mutableStateOf(false) }
    val busy = state.operationId != null || state.phase in setOf("scanning", "connecting", "syncing", "uploading")
    val storageBlocked = state.error?.code == "store_unavailable"
    OutlinedCard(Modifier.fillMaxWidth().testTag("devices_ring")) {
        Column(Modifier.padding(16.dp).testTag("ring_card"), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            RingHeading(GeneratedStrings.Ring.title)
            Text(localized(GeneratedStrings.Ring.metrics), style = MaterialTheme.typography.bodySmall)
            Text(localized(if (state.selected == null) GeneratedStrings.Ring.notSelected else ringPhase(state.phase)))
            if (!state.bluetoothEnabled) RingStatus(GeneratedStrings.Ring.bluetoothOff, "ring_bluetooth_off")
            state.selected?.let { selected ->
                Text(selected.name, style = MaterialTheme.typography.titleMedium)
                state.battery?.let { battery ->
                    Text(localized(GeneratedStrings.Ring.battery(battery.toString())) +
                        if (state.charging == true) " · ${localized(GeneratedStrings.Ring.charging)}" else "")
                }
                state.firmware?.let { Text(localized(GeneratedStrings.Ring.firmware(it))) }
                state.hardware?.let { Text(localized(GeneratedStrings.Ring.hardware(it))) }
                RingValue(GeneratedStrings.Ring.lastSync, ringMoment(state.lastSync, timezone))
                RingValue(GeneratedStrings.Ring.lastUpload, ringMoment(state.lastUpload, timezone))
                RingValue(GeneratedStrings.Ring.queue, state.queueCount.toString())
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Switch(checked = state.backgroundEnabled, onCheckedChange = onBackground,
                        enabled = !busy && !storageBlocked, modifier = Modifier.testTag("ring_background"))
                    Text(localized(GeneratedStrings.Ring.background), Modifier.weight(1f))
                }
                Text(localized(GeneratedStrings.Ring.backgroundHint), style = MaterialTheme.typography.bodySmall)
                state.metricStatus.forEach { (metric, status) ->
                    Text("${ringMetric(metric)}: ${localized(ringMetricStatus(status))}",
                        style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("ring_metric_$metric"))
                }
            } ?: if (!storageBlocked) Text(localized(GeneratedStrings.Ring.connectHint)) else Unit
            if (state.permission != "granted") RingButton(GeneratedStrings.Ring.permission, "ring_permission", !busy, onPermission)
            else if (state.selected == null) RingButton(GeneratedStrings.Ring.scan, "ring_scan",
                !busy && state.bluetoothEnabled && !storageBlocked, onScan)
            if (state.selected != null) RingButton(GeneratedStrings.Ring.sync, "ring_sync",
                !busy && state.permission == "granted" && state.bluetoothEnabled && !storageBlocked, onSync)
            if (state.selected != null && !storageBlocked) TextButton(onClick = { forgetting = true },
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("ring_forget")) {
                Text(localized(GeneratedStrings.Ring.forget))
            }
            if (state.selected == null) state.scanResults.forEach { device ->
                OutlinedCard(Modifier.fillMaxWidth().testTag("ring_scan_${device.address}")) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(device.name, style = MaterialTheme.typography.titleSmall)
                        Text("${device.address} · ${device.rssi} dBm", style = MaterialTheme.typography.bodySmall)
                        RingButton(GeneratedStrings.Ring.select, "ring_select_${device.address}", !busy && !storageBlocked) {
                            onSelect(device.address, device.name)
                        }
                    }
                }
            }
            state.error?.let { RingError(it.code, it.message, "ring_error") }
            if (state.selected != null) { settings(); measurements() }
            stored()
        }
    }
    if (forgetting && !storageBlocked) AlertDialog(onDismissRequest = { forgetting = false },
        title = { Text(localized(GeneratedStrings.Ring.forget)) },
        text = { Text(localized(GeneratedStrings.Ring.forgetHint)) },
        confirmButton = { Button(onClick = { forgetting = false; onForget() },
            modifier = Modifier.heightIn(min = 48.dp).testTag("ring_forget_confirm")) {
            Text(localized(GeneratedStrings.Ring.forget))
        } }, dismissButton = { TextButton(onClick = { forgetting = false }) { Text(localized(GeneratedStrings.Common.cancel)) } })
}

@Composable
private fun RingCollectors(controller: RingCollectorsController, lease: SessionLease?, sourceId: String?, timezone: String,
    onOpenOriginals: ((String) -> Unit)?) {
    val observed by controller.state.collectAsStateWithLifecycle()
    val state = observed?.takeIf { lease != null && it.namespace == lease.namespace }
    val scope = rememberCoroutineScope()
    val owner = remember { Any() }
    DisposableEffect(controller, lease, owner) {
        controller.activate(owner)
        onDispose { controller.deactivate(owner) }
    }
    state?.let { current ->
        current.items.filter { it.profile == "qring-r20" }.forEach { collector ->
            RingCollectorDetails(collector, timezone, active = collector.sourceId == sourceId,
                busy = current.busyCollector != null,
                onDisable = { scope.launch { controller.disable(collector.collectorId) } }, onOpenOriginals = onOpenOriginals)
        }
        current.error?.let { ErrorMessage(it, "ring_collectors_error") }
        current.serverError?.let { Text(it) }
    }
}

@Composable
internal fun RingHeading(message: LocalizedString) {
    Text(localized(message), style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.semantics { heading() })
}

@Composable
internal fun RingButton(message: LocalizedString, tag: String, enabled: Boolean = true, onClick: () -> Unit) {
    OutlinedButton(onClick = onClick, enabled = enabled,
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag(tag)) { Text(localized(message)) }
}

@Composable
internal fun RingStatus(message: LocalizedString, tag: String) {
    Text(localized(message), modifier = Modifier.testTag(tag).semantics { liveRegion = LiveRegionMode.Polite })
}

@Composable
internal fun RingValue(label: LocalizedString, value: String) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(localized(label), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelMedium)
        Text(value)
    }
}

@Composable
internal fun ringMoment(value: String?, timezone: String): String = value?.let {
    runCatching { DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm").withZone(ZoneId.of(timezone)).format(Instant.parse(it)) }.getOrDefault(it)
} ?: localized(GeneratedStrings.Ring.never)

internal fun ringPhase(phase: String): LocalizedString = when (phase) {
    "scanning" -> GeneratedStrings.Ring.Phase.scanning
    "connecting" -> GeneratedStrings.Ring.Phase.connecting
    "syncing" -> GeneratedStrings.Ring.Phase.syncing
    "uploading" -> GeneratedStrings.Ring.Phase.uploading
    "error" -> GeneratedStrings.Ring.Phase.error
    else -> GeneratedStrings.Ring.Phase.idle
}

@Composable
internal fun ringMetric(metric: String): String {
    val message = when (metric) {
    "heart_rate" -> GeneratedStrings.Ring.Metric.heart_rate
    "spo2" -> GeneratedStrings.Ring.Metric.spo2
    "temperature" -> GeneratedStrings.Ring.Metric.temperature
    "hrv" -> GeneratedStrings.Ring.Metric.hrv
    "stress" -> GeneratedStrings.Ring.Metric.stress
    "steps" -> GeneratedStrings.Ring.Metric.steps
    "sleep" -> GeneratedStrings.Ring.Metric.sleep
    "active_energy" -> GeneratedStrings.Ring.Metric.active_energy
    "active_minutes" -> GeneratedStrings.Ring.Metric.active_minutes
    "distance" -> GeneratedStrings.Ring.Metric.distance
    "running_steps" -> GeneratedStrings.Ring.Metric.running_steps
    "workouts" -> GeneratedStrings.Ring.Metric.workouts
    else -> return metric
    }
    return localized(message)
}

private fun ringMetricStatus(status: String): LocalizedString = when (status) {
    "data" -> GeneratedStrings.Ring.MetricStatus.data
    "no_data" -> GeneratedStrings.Ring.MetricStatus.no_data
    "unsupported" -> GeneratedStrings.Ring.MetricStatus.unsupported
    "invalid" -> GeneratedStrings.Ring.MetricStatus.invalid
    "timeout" -> GeneratedStrings.Ring.MetricStatus.timeout
    else -> GeneratedStrings.Ring.MetricStatus.unverified
}

@Composable
internal fun RingError(code: String, fallback: String?, tag: String) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val translated = context.resources.getIdentifier("ring_error_$code", "string", context.packageName)
    Text(if (code == "store_unavailable") stringResource(assistant.mobile.platform.R.string.ring_native_b305eb5c604e)
        else if (translated != 0) stringResource(translated) else fallback ?: code,
        color = MaterialTheme.colorScheme.error,
        modifier = Modifier.testTag(tag).semantics { liveRegion = LiveRegionMode.Assertive })
}
