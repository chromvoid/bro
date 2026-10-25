package assistant.mobile.android

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import assistant.mobile.core.GeneratedStrings
import assistant.mobile.platform.ring.RingDeviceState
import assistant.mobile.platform.ring.RingNotificationAction
import assistant.mobile.platform.ring.RingNotificationState
import assistant.mobile.platform.ring.RingPhoneController

/** Screen lifetime controls observation only; configured forwarding belongs to the application. */
@Composable
internal fun RingPhoneControls(controller: RingPhoneController, device: RingDeviceState, disabled: Boolean = false,
    expectedUserId: String? = null) {
    val notifications by controller.notificationStates.collectAsStateWithLifecycle()
    val gestures by controller.gestureStates.collectAsStateWithLifecycle()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(controller, lifecycle, device.userId, device.sourceId, device.bindingGeneration) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) controller.refreshPhoneControls() }
        lifecycle.addObserver(observer)
        if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) controller.refreshPhoneControls()
        onDispose { lifecycle.removeObserver(observer) }
    }
    val actions = remember(device.userId, device.sourceId, device.bindingGeneration) { RingUiActions() }
    val notificationOwner = notifications.owner
    val gestureOwner = gestures.owner
    if (notificationOwner != null && (expectedUserId == null || notificationOwner.userId == expectedUserId) &&
        notificationOwner.userId == device.userId && notificationOwner.sourceId == device.sourceId &&
        notificationOwner.bindingGeneration == device.bindingGeneration) {
        RingNotificationControlsContent(notifications, device, disabled) { action -> actions.run { controller.notification(action) } }
    }
    if (gestureOwner != null && (expectedUserId == null || gestureOwner.userId == expectedUserId) &&
        gestureOwner.userId == device.userId && gestureOwner.sourceId == device.sourceId &&
        gestureOwner.bindingGeneration == device.bindingGeneration) {
        RingGestureControlsContent(gestures, disabled || device.writeBlocked) { action -> actions.run { controller.gesture(action) } }
    }
    actions.error?.let { RingError(it.code, it.message, "ring_phone_action_error") }
}

@Composable
internal fun RingNotificationControlsContent(state: RingNotificationState, device: RingDeviceState,
    disabled: Boolean = false, onAction: (RingNotificationAction) -> Unit) {
    val owner = state.owner ?: return
    var packages by remember(owner, state.allowedPackages) { mutableStateOf(state.allowedPackages) }
    var expanded by remember(owner) { mutableStateOf(false) }
    val blocked = disabled || device.writeBlocked || state.error != null
    val capability = device.capability("notifications")
    val writable = capability?.canWrite == true
    Column(Modifier.fillMaxWidth().testTag("ring_phone_notifications"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        RingHeading(GeneratedStrings.Ring.Device.notifications)
        Text(localized(GeneratedStrings.Ring.Device.notificationsPrivacy))
        Text(localized(if (state.accessGranted) GeneratedStrings.Ring.Device.androidAccessGranted else GeneratedStrings.Ring.Device.androidAccessDenied))
        Text(localized(if (state.enabled) GeneratedStrings.Ring.Device.enabled else GeneratedStrings.Ring.Device.disabled))
        if (!writable) {
            Text(localized(when (capability?.status) {
                "unsupported" -> GeneratedStrings.Ring.Device.notificationsUnsupported
                "verified" -> GeneratedStrings.Ring.Device.notificationsUnavailable
                else -> GeneratedStrings.Ring.Device.notificationsUnconfirmed
            }))
        } else {
            if (!state.accessGranted) {
                Text(localized(GeneratedStrings.Ring.Device.notificationsAccessHint))
                RingButton(GeneratedStrings.Ring.Device.notificationsAccess, "ring_notifications_access", !blocked) {
                    onAction(RingNotificationAction.OpenAccess(owner))
                }
            } else {
                if (blocked) Text(localized(GeneratedStrings.Ring.Device.notificationsBlocked))
                RingButton(GeneratedStrings.Ring.Device.allowedAppsSelected(packages.size.toString()), "ring_notifications_apps") { expanded = !expanded }
                if (expanded) {
                    Text(localized(GeneratedStrings.Ring.Device.allowedApps))
                    state.apps.forEach { app ->
                        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(checked = app.packageName in packages, enabled = !blocked,
                                onCheckedChange = { checked -> packages = if (checked) packages + app.packageName else packages - app.packageName },
                                modifier = Modifier.testTag("ring_notification_app_${app.packageName}").semantics { contentDescription = app.label })
                            Text(app.label)
                        }
                    }
                    if (state.apps.isEmpty()) Text(localized(GeneratedStrings.Ring.Device.appsUnavailable))
                }
            }
            RingButton(GeneratedStrings.Ring.Device.notificationsEnable, "ring_notifications_enable", !blocked && state.accessGranted) {
                onAction(RingNotificationAction.Configure(owner, true, packages))
            }
            RingButton(GeneratedStrings.Ring.Device.notificationsDisable, "ring_notifications_disable", !blocked) {
                onAction(RingNotificationAction.Configure(owner, false, state.allowedPackages))
            }
        }
        state.error?.let { RingError(it.code, it.message, "ring_notifications_error") }
    }
}
