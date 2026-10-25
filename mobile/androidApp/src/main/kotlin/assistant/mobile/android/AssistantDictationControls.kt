package assistant.mobile.android

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import assistant.mobile.core.GeneratedStrings
import assistant.mobile.core.LocalizedString
import assistant.mobile.core.dictation.DictationController
import assistant.mobile.core.dictation.DictationPhase
import assistant.mobile.core.dictation.DictationProblem
import assistant.mobile.core.dictation.DictationState
import assistant.mobile.design.ui.RecordingDot
import assistant.mobile.design.ui.StatusTone
import assistant.mobile.design.ui.containerColor
import assistant.mobile.design.ui.contentColor
import assistant.mobile.design.R as DesignR

@Composable
internal fun DictationLifecycle(controller: DictationController, permission: DictationPermissionBridge) {
    val state by controller.state.collectAsStateWithLifecycle()
    BackHandler(enabled = state.active || state.canRetry, onBack = controller::cancel)
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val haptics = LocalHapticFeedback.current
    LaunchedEffect(state.phase) {
        if (state.phase == DictationPhase.RECORDING) {
            haptics.performHapticFeedback(HapticFeedbackType.GestureThresholdActivate)
            focus.clearFocus()
            keyboard?.hide()
        }
    }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val permissionOwner = remember { Any() }
    val context = LocalContext.current
    val activity = checkNotNull(LocalActivity.current)
    val history = remember(context) { context.getSharedPreferences("dictation_permission", Context.MODE_PRIVATE) }
    var rationaleBefore by remember { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val after = activity.shouldShowRequestPermissionRationale(Manifest.permission.RECORD_AUDIO)
        val result = classifyDictationPermission(granted, rationaleBefore, after, history.getBoolean("denied", false))
        if (granted) history.edit().remove("denied").apply()
        else if (after) history.edit().putBoolean("denied", true).apply()
        permission.result(result)
    }
    DisposableEffect(controller, permission, lifecycle, activity, context) {
        permission.attach(permissionOwner) {
            rationaleBefore = activity.shouldShowRequestPermissionRationale(Manifest.permission.RECORD_AUDIO)
            launcher.launch(Manifest.permission.RECORD_AUDIO)
        }
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME && controller.state.value.problem in
                setOf(DictationProblem.PERMISSION_DENIED, DictationProblem.PERMISSION_REVOKED) &&
                context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                controller.reset()
            }
            if (event == Lifecycle.Event.ON_RESUME) controller.refreshReadiness()
            // The system permission dialog can pause the scene before capture exists.
            if (event == Lifecycle.Event.ON_STOP || event == Lifecycle.Event.ON_PAUSE &&
                controller.state.value.phase != DictationPhase.PERMISSION) {
                if (controller.state.value.active) controller.interrupt(DictationProblem.BACKGROUNDED)
            }
        }
        lifecycle.addObserver(observer)
        if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) controller.refreshReadiness()
        onDispose {
            lifecycle.removeObserver(observer)
            if (controller.state.value.active || controller.state.value.canRetry) controller.cancel()
            permission.detach(permissionOwner)
        }
    }
}

@Composable
internal fun AssistantDictationFeedback(state: DictationState, onSpeechSettings: () -> Unit, onOpenSettings: () -> Unit,
    onRetry: () -> Unit) {
    val hasPartial = state.active && state.partial.isNotEmpty()
    if (state.problem == null && !hasPartial && !state.draftPreserved && !state.reachedLimit) return
    Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface)) {
        if (state.problem != null || state.reachedLimit) {
            // The message keeps the strip's width; its action sits under it, as in a Telegram service bar.
            Row(
                Modifier.fillMaxWidth().background(StatusTone.Warning.containerColor).padding(horizontal = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(painterResource(DesignR.drawable.lucide_circle_alert), contentDescription = null,
                    tint = StatusTone.Warning.contentColor, modifier = Modifier.padding(top = 10.dp).size(20.dp))
                Column(Modifier.weight(1f).padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    state.problem?.let { problem ->
                        Text(localized(dictationProblemLabel(problem)), style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.testTag("assistant_dictation_status").semantics { liveRegion = LiveRegionMode.Polite })
                    }
                    if (state.reachedLimit) Text(localized(GeneratedStrings.Dictation.limit),
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
                    if (state.problem == DictationProblem.PERMISSION_DENIED || state.problem == DictationProblem.PERMISSION_REVOKED) {
                        TextButton(onClick = onOpenSettings, modifier = Modifier.offset(x = (-12).dp).testTag("assistant_dictation_settings")) {
                            Text(localized(GeneratedStrings.NativeDictation.openSettings))
                        }
                    }
                    if (state.problem in setOf(DictationProblem.NOT_CONFIGURED, DictationProblem.NO_ACCESS,
                            DictationProblem.AUTH_REQUIRED, DictationProblem.SECRET_UNAVAILABLE)) {
                        TextButton(onClick = onSpeechSettings, modifier = Modifier.offset(x = (-12).dp).testTag("dictation_speech_settings")) {
                            Text(localized(GeneratedStrings.Dictation.configure))
                        }
                    }
                    if (state.canRetry) {
                        TextButton(onClick = onRetry, modifier = Modifier.offset(x = (-12).dp).testTag("dictation_retry")) {
                            Text(localized(GeneratedStrings.Common.retry))
                        }
                    }
                }
            }
        }
        if (hasPartial) {
            Text(state.partial, modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)
                .testTag("assistant_dictation_partial"),
                style = MaterialTheme.typography.bodyMedium, maxLines = 3, overflow = TextOverflow.Ellipsis)
        }
        if (state.draftPreserved) {
            // The final remains available for review while the user's edited draft stays intact.
            state.finalText?.let { Text(it,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)
                    .testTag("assistant_dictation_preserved_final"),
                style = MaterialTheme.typography.bodyMedium, maxLines = 3, overflow = TextOverflow.Ellipsis) }
        }
    }
}

@Composable
internal fun AssistantRecordingStatus(state: DictationState, modifier: Modifier = Modifier) {
    val status = localized(when (state.phase) {
        DictationPhase.FINALIZING -> GeneratedStrings.Dictation.finalizing
        else -> GeneratedStrings.Dictation.recording
    })
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically) {
        if (state.phase != DictationPhase.FINALIZING) {
            RecordingDot()
            val time = "${state.elapsedSeconds / 60}:${(state.elapsedSeconds % 60).toString().padStart(2, '0')}"
            val elapsedLabel = localized(GeneratedStrings.Dictation.elapsed(time))
            Text(time, style = MaterialTheme.typography.bodyMedium.copy(fontFeatureSettings = "tnum"),
                modifier = Modifier.testTag("assistant_dictation_timer").semantics {
                    contentDescription = elapsedLabel
                    stateDescription = status
                })
        } else {
            CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
            Text(status, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.testTag("assistant_dictation_progress").semantics { liveRegion = LiveRegionMode.Polite })
        }
    }
}

@Composable
internal fun AssistantDictationAction(@DrawableRes icon: Int, label: String, onClick: () -> Unit,
    modifier: Modifier = Modifier, enabled: Boolean = true) {
    val colors = MaterialTheme.colorScheme
    IconButton(onClick = onClick, enabled = enabled, modifier = modifier.size(48.dp)) {
        Box(Modifier.size(40.dp).background(
            if (enabled) colors.primary else colors.onSurface.copy(alpha = 0.12f), CircleShape,
        ), contentAlignment = Alignment.Center) {
            Icon(painterResource(icon), contentDescription = label, modifier = Modifier.size(24.dp),
                tint = if (enabled) colors.onPrimary else colors.onSurface.copy(alpha = 0.38f))
        }
    }
}

private fun dictationProblemLabel(problem: DictationProblem): LocalizedString = when (problem) {
    DictationProblem.NOT_RECOGNIZED -> GeneratedStrings.Dictation.not_recognized
    DictationProblem.NOT_CONFIGURED -> GeneratedStrings.Dictation.not_configured
    DictationProblem.NO_ACCESS -> GeneratedStrings.Dictation.no_access
    DictationProblem.AUTH_REQUIRED -> GeneratedStrings.Dictation.auth_required
    DictationProblem.SESSION_EXPIRED -> GeneratedStrings.Dictation.session_expired
    DictationProblem.APPLICATION_LIMIT -> GeneratedStrings.Dictation.application_limit
    DictationProblem.PROVIDER_LIMIT -> GeneratedStrings.Dictation.provider_limit
    DictationProblem.TIMEOUT -> GeneratedStrings.Dictation.timeout
    DictationProblem.PROVIDER_ERROR -> GeneratedStrings.Dictation.provider_error
    DictationProblem.INVALID_AUDIO -> GeneratedStrings.Dictation.invalid_audio
    DictationProblem.SECRET_UNAVAILABLE -> GeneratedStrings.Dictation.secret_unavailable
    DictationProblem.EXECUTION_DISABLED -> GeneratedStrings.Dictation.execution_disabled
    DictationProblem.BUSY -> GeneratedStrings.Dictation.busy
    DictationProblem.CONNECTION -> GeneratedStrings.Dictation.connection
    DictationProblem.TOO_LONG -> GeneratedStrings.Dictation.too_long
    DictationProblem.BACKGROUNDED -> GeneratedStrings.Dictation.backgrounded
    DictationProblem.DEVICE -> GeneratedStrings.Dictation.device
    DictationProblem.PERMISSION_DENIED, DictationProblem.PERMISSION_REVOKED -> GeneratedStrings.NativeDictation.denied
    DictationProblem.UNAVAILABLE -> GeneratedStrings.Dictation.unavailable
    DictationProblem.AUDIO_FOCUS -> GeneratedStrings.Dictation.device
    DictationProblem.BACKLOG -> GeneratedStrings.Dictation.connection
    DictationProblem.LIMIT -> GeneratedStrings.Dictation.limit
    else -> GeneratedStrings.Dictation.server
}

internal fun openDictationAppSettings(context: Context) {
    context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
        Uri.parse("package:${context.packageName}")))
}
