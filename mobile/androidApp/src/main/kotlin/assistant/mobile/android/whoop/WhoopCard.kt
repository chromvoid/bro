package assistant.mobile.android.whoop

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import assistant.mobile.android.deviceMoment
import assistant.mobile.android.localized
import assistant.mobile.android.R
import assistant.mobile.core.GeneratedStrings
import assistant.mobile.core.LocalizedString
import assistant.mobile.design.GeneratedColors
import java.util.Locale

/** The same strap controls as WhoopCard.vue, bound directly to the native person session. */
@Composable
internal fun WhoopCard(repository: WhoopRepository, timezone: String = "UTC", accountUser: String) {
    val observed by repository.states.collectAsStateWithLifecycle()
    val state = observed.takeIf { it.user == accountUser }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        repository.permissionResult(result.isNotEmpty() && result.values.all { it })
    }
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner, repository) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) repository.reconcile()
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
    WhoopCard(
        state = state,
        timezone = timezone,
        onPermission = { permission.launch(repository.permissionNames()) },
        onScan = { repository.scan() },
        onSelect = { repository.select(it.address) },
        onSync = { repository.sync() },
        onBackground = { repository.background(it) },
        onForget = { repository.forget() },
        onInitialize = { repository.reconcile() },
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun WhoopCard(
    state: WhoopState?,
    timezone: String = "UTC",
    busy: Boolean = state?.operationId != null,
    onPermission: () -> Unit,
    onScan: () -> Unit,
    onSelect: (WhoopDevice) -> Unit,
    onSync: () -> Unit,
    onBackground: (Boolean) -> Unit,
    onForget: () -> Unit,
    onInitialize: () -> Unit,
) {
    var forgetting by remember(state?.user) { mutableStateOf(false) }
    val active = state?.let(::whoopBusy) == true
    val storageBlocked = state?.error?.code == "store_unavailable"
    val palette = if (MaterialTheme.colorScheme.background == GeneratedColors.dark.getValue("background"))
        GeneratedColors.dark else GeneratedColors.light
    val forgetLabel = GeneratedStrings.Whoop.forget
    OutlinedCard(Modifier.fillMaxWidth().testTag("whoop_card")) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(localized(GeneratedStrings.Whoop.title), style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.testTag("whoop_title").semantics { heading() })
                    Text(localized(GeneratedStrings.Whoop.metrics), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (state != null) Surface(color = palette.getValue("secondary"), contentColor = palette.getValue("secondary-foreground"),
                    shape = MaterialTheme.shapes.small) {
                    Text(localized(if (state.selected != null || active) whoopPhase(state.phase) else GeneratedStrings.Whoop.notSelected),
                        style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp))
                }
            }
            if (state == null) {
                Text(localized(GeneratedStrings.Whoop.initializing), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                OutlinedButton(onClick = onInitialize, enabled = !busy,
                    modifier = Modifier.heightIn(min = 48.dp).testTag("whoop_initialize")) {
                    Text(localized(GeneratedStrings.Whoop.checkConnection))
                }
            } else {
                if (!state.bluetoothEnabled) Text(localized(GeneratedStrings.Whoop.bluetoothOff),
                    style = MaterialTheme.typography.bodySmall, color = palette.getValue("warning"),
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                state.selected?.let { selected ->
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(selected.name, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.testTag("whoop_selected"))
                        state.battery?.let { Text(localized(GeneratedStrings.Whoop.battery(it.toString())), style = MaterialTheme.typography.bodySmall) }
                        state.firmware?.takeIf { it.isNotEmpty() }?.let {
                            Text(localized(GeneratedStrings.Whoop.firmware(it)), style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        WhoopFact(GeneratedStrings.Whoop.lastSync, whoopMoment(state.lastSync, timezone))
                        WhoopFact(GeneratedStrings.Whoop.lastUpload, whoopMoment(state.lastUpload, timezone))
                        WhoopFact(GeneratedStrings.Whoop.queue, localized(if (state.queueCount == 0) GeneratedStrings.Whoop.queueEmpty
                            else GeneratedStrings.Whoop.queueSize(state.queueCount.toString(), String.format(Locale.ROOT, "%.1f", state.queueBytes / 1048576.0))))
                    }
                    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).toggleable(
                        value = state.backgroundEnabled,
                        enabled = !busy && !active && !storageBlocked,
                        role = Role.Switch,
                        onValueChange = onBackground,
                    ).testTag("whoop_background"), verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        val backgroundLabel = localized(GeneratedStrings.Whoop.background)
                        Switch(checked = state.backgroundEnabled, onCheckedChange = null,
                            enabled = !busy && !active && !storageBlocked)
                        Text(backgroundLabel, style = MaterialTheme.typography.bodyMedium)
                    }
                }
                if (state.selected == null && !storageBlocked) Text(localized(GeneratedStrings.Whoop.connectHint),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (state.permission != "granted") {
                        OutlinedButton(onClick = onPermission, enabled = !busy,
                            modifier = Modifier.heightIn(min = 48.dp).testTag("whoop_permission")) {
                            Text(localized(GeneratedStrings.Whoop.permission))
                        }
                    } else if (state.selected == null) {
                        OutlinedButton(onClick = onScan, enabled = !busy && !active && state.bluetoothEnabled && !storageBlocked,
                            modifier = Modifier.heightIn(min = 48.dp).testTag("whoop_scan")) {
                            Text(localized(GeneratedStrings.Whoop.scan))
                        }
                    }
                    if (state.selected != null) {
                        OutlinedButton(onClick = onSync, enabled = !busy && !whoopSyncBlocked(state),
                            modifier = Modifier.heightIn(min = 48.dp).testTag("whoop_sync")) {
                            Text(localized(GeneratedStrings.Whoop.sync))
                        }
                    }
                    if (state.selected != null && !storageBlocked) TextButton(onClick = { forgetting = true }, enabled = !busy,
                        modifier = Modifier.heightIn(min = 48.dp).testTag("whoop_forget")) {
                        Text(localized(forgetLabel))
                    }
                }
                if (state.selected == null && state.scanResults.isNotEmpty()) {
                    Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surface,
                        modifier = Modifier.fillMaxWidth().testTag("whoop_scan_results")) {
                        Column {
                            state.scanResults.forEachIndexed { index, device ->
                                if (index > 0) HorizontalDivider()
                                Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                        Text(device.name, style = MaterialTheme.typography.bodyMedium)
                                        device.rssi?.let { Text(localized(GeneratedStrings.Whoop.signal(it.toString())),
                                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                                    }
                                    OutlinedButton(onClick = { onSelect(device) }, enabled = !busy && !active && !storageBlocked,
                                        modifier = Modifier.heightIn(min = 48.dp).testTag("whoop_select_${device.address}")) {
                                        Text(localized(GeneratedStrings.Whoop.select))
                                    }
                                }
                            }
                        }
                    }
                }
                state.error?.let { Text(whoopErrorText(it), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("whoop_error").semantics { liveRegion = LiveRegionMode.Assertive }) }
            }
        }
    }
    if (forgetting && !storageBlocked) AlertDialog(
        onDismissRequest = { forgetting = false },
        title = { Text(localized(forgetLabel)) },
        text = { Text(localized(GeneratedStrings.Whoop.forgetHint)) },
        dismissButton = { TextButton(onClick = { forgetting = false }, modifier = Modifier.heightIn(min = 48.dp).testTag("whoop_forget_cancel")) {
            Text(localized(GeneratedStrings.Common.cancel))
        } },
        confirmButton = { TextButton(onClick = { forgetting = false; onForget() }, enabled = !busy,
            modifier = Modifier.heightIn(min = 48.dp).testTag("whoop_forget_confirm")) {
            Text(localized(forgetLabel), color = MaterialTheme.colorScheme.error)
        } },
    )
}

@Composable
internal fun WhoopHeaderRow(repository: WhoopRepository, timezone: String, onOpenSource: (String?) -> Unit, accountUser: String) {
    val state by repository.states.collectAsStateWithLifecycle()
    if (state.user == accountUser) WhoopHeaderRow(state, timezone, onSync = { repository.sync() }, onOpenSource = onOpenSource)
}

/** Like DevicesSheet.vue, a selected strap leads to its source and can sync in place. */
@Composable
internal fun WhoopHeaderRow(state: WhoopState, timezone: String, onSync: () -> Unit, onOpenSource: (String?) -> Unit) {
    val selected = state.selected ?: return
    val palette = if (MaterialTheme.colorScheme.background == GeneratedColors.dark.getValue("background"))
        GeneratedColors.dark else GeneratedColors.light
    val line = when {
        !state.bluetoothEnabled -> localized(GeneratedStrings.Whoop.bluetoothOff)
        whoopBusy(state) -> localized(whoopPhase(state.phase))
        state.error != null -> whoopErrorText(state.error)
        else -> localized(GeneratedStrings.Whoop.lastSync) + ": " + whoopMoment(state.lastSync, timezone)
    }
    Surface(shape = MaterialTheme.shapes.medium, tonalElevation = 1.dp, modifier = Modifier.fillMaxWidth().testTag("devices_whoop")) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.weight(1f).clickable(role = Role.Button) { onOpenSource(state.source) }.padding(vertical = 8.dp)
                .testTag("whoop_source"), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(localized(GeneratedStrings.Devices.Status.whoop) + " " + selected.name, style = MaterialTheme.typography.bodyMedium)
                Text(localized(state.battery?.let { GeneratedStrings.Whoop.battery(it.toString()) }
                    ?: GeneratedStrings.Devices.Status.whoopUnknown), style = MaterialTheme.typography.bodySmall)
                Text(line, style = MaterialTheme.typography.bodySmall,
                    color = if (!state.bluetoothEnabled || state.error != null) palette.getValue("warning")
                    else MaterialTheme.colorScheme.onSurfaceVariant)
            }
            TextButton(onClick = onSync, enabled = state.operationId == null && !whoopSyncBlocked(state),
                modifier = Modifier.heightIn(min = 48.dp).testTag("whoop_header_sync")) {
                Text(localized(GeneratedStrings.Whoop.sync))
            }
        }
    }
}

@Composable
private fun WhoopFact(label: LocalizedString, value: String) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(localized(label), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun whoopMoment(value: String?, timezone: String): String =
    value?.let { deviceMoment(it, timezone) } ?: localized(GeneratedStrings.Whoop.never)

internal fun whoopBusy(state: WhoopState): Boolean = state.phase != "idle" && state.phase != "error"

internal fun whoopSyncBlocked(state: WhoopState): Boolean = whoopBusy(state) || state.permission != "granted" ||
    !state.bluetoothEnabled || state.error?.code == "store_unavailable"

private fun whoopPhase(phase: String): LocalizedString = when (phase) {
    "idle" -> GeneratedStrings.Whoop.Phase.idle
    "scanning" -> GeneratedStrings.Whoop.Phase.scanning
    "pairing" -> GeneratedStrings.Whoop.Phase.pairing
    "connecting" -> GeneratedStrings.Whoop.Phase.connecting
    "syncing" -> GeneratedStrings.Whoop.Phase.syncing
    else -> GeneratedStrings.Whoop.Phase.error
}

@Composable
private fun whoopErrorText(error: WhoopProblem): String {
    if (error.code == "store_unavailable") return stringResource(R.string.whoop_runtime_store_unavailable)
    val message = when (error.code) {
        "request_failed" -> GeneratedStrings.Whoop.Error.request_failed
        "permission_denied" -> GeneratedStrings.Whoop.Error.permission_denied
        "bluetooth_off" -> GeneratedStrings.Whoop.Error.bluetooth_off
        "bluetooth_unavailable" -> GeneratedStrings.Whoop.Error.bluetooth_unavailable
        "scan_failed" -> GeneratedStrings.Whoop.Error.scan_failed
        "not_scanned" -> GeneratedStrings.Whoop.Error.not_scanned
        "already_bound" -> GeneratedStrings.Whoop.Error.already_bound
        "busy" -> GeneratedStrings.Whoop.Error.busy
        "whoop_pairing_failed" -> GeneratedStrings.Whoop.Error.whoop_pairing_failed
        "whoop_not_paired" -> GeneratedStrings.Whoop.Error.whoop_not_paired
        "whoop_no_reply" -> GeneratedStrings.Whoop.Error.whoop_no_reply
        "whoop_history_refused" -> GeneratedStrings.Whoop.Error.whoop_history_refused
        "wrong_device" -> GeneratedStrings.Whoop.Error.wrong_device
        "disconnected" -> GeneratedStrings.Whoop.Error.disconnected
        "timeout" -> GeneratedStrings.Whoop.Error.timeout
        "queue_full" -> GeneratedStrings.Whoop.Error.queue_full
        "not_authenticated" -> GeneratedStrings.Whoop.Error.not_authenticated
        "grant_revoked" -> GeneratedStrings.Whoop.Error.grant_revoked
        "upload_failed" -> GeneratedStrings.Whoop.Error.upload_failed
        "unavailable" -> GeneratedStrings.Whoop.Error.unavailable
        else -> null
    }
    return if (message != null) localized(message)
    else if (error.message.isNotEmpty()) error.message
    else localized(GeneratedStrings.Whoop.Error.request_failed)
}
