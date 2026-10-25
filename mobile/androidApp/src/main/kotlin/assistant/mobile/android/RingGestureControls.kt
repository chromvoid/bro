package assistant.mobile.android

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import assistant.mobile.core.GeneratedStrings
import assistant.mobile.core.LocalizedString
import assistant.mobile.platform.ring.RingGestureAction
import assistant.mobile.platform.ring.RingGestureActionRequest
import assistant.mobile.platform.ring.RingGestureInput
import assistant.mobile.platform.ring.RingGestureResult
import assistant.mobile.platform.ring.RingGestureState

@Composable
internal fun RingGestureControlsContent(state: RingGestureState, disabled: Boolean = false,
    onAction: (RingGestureActionRequest) -> Unit) {
    val owner = state.owner ?: return
    var draft by remember(owner) { mutableStateOf(state.mappings) }
    var dirty by remember(owner) { mutableStateOf(false) }
    var accessDisclosure by remember(owner) { mutableStateOf(false) }
    LaunchedEffect(state.mappings) {
        if (!dirty || state.mappings == draft) { draft = state.mappings; dirty = false }
    }
    Column(Modifier.fillMaxWidth().testTag("ring_host_gestures"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        RingHeading(GeneratedStrings.Ring.HostGestures.title)
        Text(state.reason)
        Text(localized(if (state.accessGranted) GeneratedStrings.Ring.HostGestures.accessGranted else GeneratedStrings.Ring.HostGestures.accessDenied))
        Text(localized(GeneratedStrings.Ring.HostGestures.hint))
        RingButton(GeneratedStrings.Ring.HostGestures.access, "ring_gesture_access", !disabled) {
            accessDisclosure = true
        }
        if (accessDisclosure) AlertDialog(onDismissRequest = { accessDisclosure = false },
            title = { Text(stringResource(R.string.privacy_ring_access_title)) },
            text = { Text(stringResource(R.string.privacy_ring_access_text)) },
            confirmButton = { TextButton(onClick = {
                accessDisclosure = false
                onAction(RingGestureActionRequest.OpenAccess(owner))
            }) { Text(stringResource(R.string.privacy_continue)) } },
            dismissButton = { TextButton(onClick = { accessDisclosure = false }) {
                Text(stringResource(R.string.privacy_cancel))
            } })
        RingGestureInput.entries.forEach { input ->
            var expanded by remember(owner, input) { mutableStateOf(false) }
            Column {
                Text(localized(ringGestureInputLabel(input)))
                OutlinedButton(onClick = { expanded = true }, enabled = !disabled,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("ring_gesture_mapping_${input.wire}")) {
                    Text(localized(ringGestureActionLabel(draft[input] ?: RingGestureAction.NONE)))
                }
                DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                    RingGestureAction.entries.forEach { action ->
                        DropdownMenuItem(text = { Text(localized(ringGestureActionLabel(action))) },
                            modifier = Modifier.heightIn(min = 48.dp).testTag("ring_gesture_choice_${input.wire}_${action.wire}"),
                            onClick = { draft = draft + (input to action); dirty = true; expanded = false })
                    }
                }
            }
        }
        RingButton(GeneratedStrings.Ring.HostGestures.save, "ring_gesture_save", !disabled && dirty) {
            onAction(RingGestureActionRequest.Configure(owner, false, draft))
        }
        RingButton(GeneratedStrings.Ring.HostGestures.enable, "ring_gesture_enable", !disabled && state.accessGranted && state.qualified) {
            onAction(RingGestureActionRequest.Configure(owner, true, draft))
        }
        if (state.enabled) RingButton(GeneratedStrings.Ring.HostGestures.disable, "ring_gesture_disable", !disabled) {
            onAction(RingGestureActionRequest.Configure(owner, false, state.mappings))
        }
        state.lastAction?.let {
            Text(localized(ringGestureActionLabel(it.action)) + " · " + localized(ringGestureResultLabel(it.result)),
                modifier = Modifier.testTag("ring_gesture_last_action"))
        }
    }
}

private fun ringGestureInputLabel(value: RingGestureInput): LocalizedString = when (value) {
    RingGestureInput.TAP -> GeneratedStrings.Ring.HostGestures.Inputs.tap
    RingGestureInput.DOUBLE_TAP -> GeneratedStrings.Ring.HostGestures.Inputs.doubleTap
    RingGestureInput.SWIPE_LEFT -> GeneratedStrings.Ring.HostGestures.Inputs.swipeLeft
    RingGestureInput.SWIPE_RIGHT -> GeneratedStrings.Ring.HostGestures.Inputs.swipeRight
    RingGestureInput.SWIPE_UP -> GeneratedStrings.Ring.HostGestures.Inputs.swipeUp
    RingGestureInput.SWIPE_DOWN -> GeneratedStrings.Ring.HostGestures.Inputs.swipeDown
}
private fun ringGestureActionLabel(value: RingGestureAction): LocalizedString = when (value) {
    RingGestureAction.NONE -> GeneratedStrings.Ring.HostGestures.Actions.none
    RingGestureAction.BACK -> GeneratedStrings.Ring.HostGestures.Actions.back
    RingGestureAction.HOME -> GeneratedStrings.Ring.HostGestures.Actions.home
    RingGestureAction.RECENTS -> GeneratedStrings.Ring.HostGestures.Actions.recents
    RingGestureAction.NOTIFICATIONS -> GeneratedStrings.Ring.HostGestures.Actions.notifications
    RingGestureAction.VOLUME_UP -> GeneratedStrings.Ring.HostGestures.Actions.volumeUp
    RingGestureAction.VOLUME_DOWN -> GeneratedStrings.Ring.HostGestures.Actions.volumeDown
    RingGestureAction.PLAY_PAUSE -> GeneratedStrings.Ring.HostGestures.Actions.playPause
    RingGestureAction.NEXT -> GeneratedStrings.Ring.HostGestures.Actions.next
    RingGestureAction.PREVIOUS -> GeneratedStrings.Ring.HostGestures.Actions.previous
    RingGestureAction.TAP_CENTER -> GeneratedStrings.Ring.HostGestures.Actions.tapCenter
    RingGestureAction.DOUBLE_TAP_CENTER -> GeneratedStrings.Ring.HostGestures.Actions.doubleTapCenter
    RingGestureAction.TAP_LEFT -> GeneratedStrings.Ring.HostGestures.Actions.tapLeft
    RingGestureAction.TAP_RIGHT -> GeneratedStrings.Ring.HostGestures.Actions.tapRight
    RingGestureAction.SWIPE_UP -> GeneratedStrings.Ring.HostGestures.Actions.swipeUp
    RingGestureAction.SWIPE_DOWN -> GeneratedStrings.Ring.HostGestures.Actions.swipeDown
    RingGestureAction.SWIPE_LEFT -> GeneratedStrings.Ring.HostGestures.Actions.swipeLeft
    RingGestureAction.SWIPE_RIGHT -> GeneratedStrings.Ring.HostGestures.Actions.swipeRight
}
private fun ringGestureResultLabel(value: RingGestureResult): LocalizedString = when (value) {
    RingGestureResult.DISPATCHING -> GeneratedStrings.Ring.HostGestures.Results.dispatching
    RingGestureResult.ANDROID_ACCEPTED -> GeneratedStrings.Ring.HostGestures.Results.androidAccepted
    RingGestureResult.ANDROID_COMPLETED -> GeneratedStrings.Ring.HostGestures.Results.androidCompleted
    RingGestureResult.ANDROID_CANCELLED -> GeneratedStrings.Ring.HostGestures.Results.androidCancelled
    RingGestureResult.ANDROID_REJECTED -> GeneratedStrings.Ring.HostGestures.Results.androidRejected
}
