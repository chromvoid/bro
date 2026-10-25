package assistant.mobile.android.glasses

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import assistant.mobile.design.ui.ProgressBar
import assistant.mobile.android.R
import assistant.mobile.android.NativeTopBar

/** The shell's offline G2 controls, rendered with the native app's theme and scale. */
@Composable
internal fun EvenG2Screen(
    state: G2Ui,
    onSearch: () -> Unit,
    onStopScan: () -> Unit,
    onSelect: (G2Device) -> Unit,
    onConnect: () -> Unit,
    onShowBounds: () -> Unit,
    onStartTouchTest: () -> Unit,
    onSendTest: () -> Unit,
    onDisconnect: () -> Unit,
    onBack: () -> Unit,
    onSettings: () -> Unit,
) {
    val choosing = state.phase == G2Phase.Idle || state.phase == G2Phase.Scanning
    Surface(Modifier.fillMaxSize().testTag("g2_screen"), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize()) {
            NativeTopBar(stringResource(R.string.g2_title), onBack = onBack)
            Column(
                Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())
                    .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom))
                    .padding(horizontal = 20.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Text(stringResource(R.string.g2_intro))
                Text(stringResource(R.string.g2_connection_note), style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedCard(Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large,
                    colors = CardDefaults.outlinedCardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(stringResource(when (state.phase) {
                            G2Phase.Idle -> R.string.g2_phase_idle
                            G2Phase.Scanning -> R.string.g2_phase_scanning
                            G2Phase.Connecting -> R.string.g2_phase_connecting
                            G2Phase.Connected -> R.string.g2_phase_connected
                            G2Phase.Sending -> R.string.g2_phase_sending
                            G2Phase.Disconnecting -> R.string.g2_phase_disconnecting
                        }), style = MaterialTheme.typography.titleMedium)
                        Text(state.message, Modifier.testTag("g2_status").semantics { liveRegion = LiveRegionMode.Polite })
                        if (state.phase in setOf(G2Phase.Scanning, G2Phase.Connecting, G2Phase.Sending, G2Phase.Disconnecting)) {
                            ProgressBar(modifier = Modifier.fillMaxWidth())
                        }
                    }
                }
                if (choosing) {
                    Button(onClick = if (state.phase == G2Phase.Scanning) onStopScan else onSearch,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("g2_search")) {
                        Text(stringResource(if (state.phase == G2Phase.Scanning) R.string.g2_stop_search else R.string.g2_search))
                    }
                    if (state.devices.isNotEmpty()) {
                        Text(stringResource(R.string.g2_choose_arms), style = MaterialTheme.typography.titleMedium)
                        for (side in G2Side.entries) {
                            Text(stringResource(if (side == G2Side.Left) R.string.g2_left_arm else R.string.g2_right_arm))
                            val devices = state.devices.filter { it.side == side }
                            if (devices.isEmpty()) Text(stringResource(R.string.g2_arm_not_found), style = MaterialTheme.typography.bodySmall)
                            for (device in devices) {
                                val selected = (if (side == G2Side.Left) state.left else state.right)?.address == device.address
                                OutlinedButton(onClick = { onSelect(device) },
                                    modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).testTag("g2_device_${device.address}")) {
                                    Column(Modifier.fillMaxWidth()) {
                                        Text(if (selected) stringResource(R.string.g2_selected, device.name) else device.name)
                                        Text(device.address, style = MaterialTheme.typography.bodySmall)
                                    }
                                }
                            }
                        }
                    }
                    Button(onClick = onConnect, enabled = state.left != null && state.right != null,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("g2_connect")) {
                        Text(stringResource(R.string.g2_connect))
                    }
                    TextButton(onClick = onSettings, modifier = Modifier.heightIn(min = 48.dp).testTag("g2_settings")) {
                        Text(stringResource(R.string.g2_permission_settings_action))
                    }
                } else {
                    val touch = state.touch
                    if (touch != null) {
                        Text(stringResource(R.string.g2_touch_state, touch.page + 1, touch.taps),
                            style = MaterialTheme.typography.titleLarge, modifier = Modifier.testTag("g2_touch_state"))
                        Text(stringResource(R.string.g2_last_action, stringResource(g2InputLabel(touch.lastAction))))
                    } else {
                        Text(stringResource(if (state.test == G2Test.Bounds) R.string.g2_bounds_text else R.string.g2_sample_text),
                            style = MaterialTheme.typography.titleLarge, modifier = Modifier.testTag("g2_test_text"))
                    }
                    Button(onClick = onShowBounds, enabled = state.phase == G2Phase.Connected,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("g2_bounds")) {
                        Text(stringResource(R.string.g2_show_bounds))
                    }
                    Text(stringResource(R.string.g2_touch_instructions))
                    Button(onClick = onStartTouchTest, enabled = state.phase == G2Phase.Connected,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("g2_touch_test")) {
                        Text(stringResource(if (touch == null) R.string.g2_touch_test else R.string.g2_restart_touch_test))
                    }
                    Button(onClick = onSendTest, enabled = state.phase == G2Phase.Connected,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("g2_text_test")) {
                        Text(stringResource(R.string.g2_text_test))
                    }
                    OutlinedButton(onClick = onDisconnect, enabled = state.phase != G2Phase.Disconnecting,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("g2_disconnect")) {
                        Text(stringResource(R.string.g2_disconnect))
                    }
                }
            }
        }
    }
}

internal fun g2InputLabel(type: G2InputType?): Int = when (type) {
    null -> R.string.g2_input_waiting
    G2InputType.Click -> R.string.g2_input_click
    G2InputType.Up -> R.string.g2_input_up
    G2InputType.Down -> R.string.g2_input_down
    G2InputType.DoubleClick -> R.string.g2_input_double_click
    G2InputType.Enter -> R.string.g2_input_enter
    G2InputType.Exit -> R.string.g2_input_exit
    G2InputType.AbnormalExit -> R.string.g2_input_abnormal_exit
    G2InputType.SystemExit -> R.string.g2_input_system_exit
}
