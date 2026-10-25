package assistant.mobile.android

import android.Manifest
import android.animation.ValueAnimator
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import assistant.mobile.android.ambient.AmbientMessages
import assistant.mobile.android.ambient.AmbientRuntime
import assistant.mobile.android.ambient.AmbientState
import assistant.mobile.core.GeneratedStrings
import assistant.mobile.core.LocalizedString
import assistant.mobile.core.RussianPlural
import assistant.mobile.design.GeneratedColors
import assistant.mobile.design.ui.ActionIconButton
import assistant.mobile.design.ui.AppCardSurface
import assistant.mobile.design.ui.CardTitle
import assistant.mobile.design.ui.NavigationRow
import assistant.mobile.design.ui.Spacing
import assistant.mobile.design.R as DesignR
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

/** Opening controls never asks for permission or starts the microphone. */
@Composable
internal fun AmbientSettingsCard(state: AmbientState, onOpenControls: () -> Unit) {
    OutlinedCard(Modifier.fillMaxWidth().testTag("ambient_settings")) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(localized(GeneratedStrings.AmbientAudio.title),
                modifier = Modifier.semantics { heading() }, style = MaterialTheme.typography.titleMedium)
            Text(localized(GeneratedStrings.AmbientAudio.description), style = MaterialTheme.typography.bodySmall)
            Text(localized(GeneratedStrings.AmbientAudio.retention), style = MaterialTheme.typography.bodySmall)
            Text(localized(if (state.recording) GeneratedStrings.AmbientAudio.recording else GeneratedStrings.AmbientAudio.stopped),
                modifier = Modifier.testTag("ambient_settings_status").semantics { liveRegion = LiveRegionMode.Polite })
            if (state.queuedBatches > 0) Text(localized(GeneratedStrings.AmbientAudio.queue(state.queuedBatches.toString())) +
                " " + localized(GeneratedStrings.AmbientAudio.offline), style = MaterialTheme.typography.bodySmall)
            ambientUploadedAt(state.lastUploadedAt)?.let {
                Text(localized(GeneratedStrings.AmbientAudio.uploaded(it)), style = MaterialTheme.typography.bodySmall)
            }
            visibleAmbientFailure(state)?.let {
                ErrorMessage(ambientErrorMessage(it), "ambient_settings_failure")
            }
            OutlinedButton(onClick = onOpenControls,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("ambient_open_controls")) {
                Text(localized(GeneratedStrings.AmbientAudio.controls))
            }
        }
    }
}

/** The same recording/problem/idle meaning as AmbientDot, with the state also spoken in words. */
@Composable
internal fun TodayAmbientCard(state: AmbientState, onOpenControls: () -> Unit, onRecordings: () -> Unit = {}) {
    val failure = visibleAmbientFailure(state)
    val label = when {
        state.recording -> GeneratedStrings.AmbientAudio.Control.recording
        failure != null -> GeneratedStrings.AmbientAudio.Control.error
        else -> GeneratedStrings.AmbientAudio.Control.idle
    }
    AppCardSurface(modifier = Modifier.fillMaxWidth().testTag("today_ambient")) {
        Column(Modifier.padding(Spacing.l), verticalArrangement = Arrangement.spacedBy(Spacing.m)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.m)) {
                if (state.recording || failure != null) AmbientDot(state.recording)
                CardTitle(localized(GeneratedStrings.AmbientAudio.title), modifier = Modifier.weight(1f))
                ActionIconButton(
                    icon = DesignR.drawable.lucide_settings,
                    contentDescription = localized(GeneratedStrings.AmbientAudio.controls) + ", " + localized(label),
                    onClick = onOpenControls,
                    testTag = "today_ambient_controls",
                )
            }
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                Text(localized(if (state.recording) GeneratedStrings.AmbientAudio.recording else GeneratedStrings.AmbientAudio.stopped),
                    modifier = Modifier.testTag("today_ambient_status").semantics { liveRegion = LiveRegionMode.Polite },
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                failure?.let { ErrorMessage(ambientErrorMessage(it), "today_ambient_failure") }
            }
        }
        NavigationRow(
            title = localized(GeneratedStrings.Recordings.title),
            onClick = onRecordings,
            testTag = "today_recordings",
            modifier = Modifier.padding(bottom = Spacing.l),
        )
    }
}

@Composable
private fun AmbientDot(recording: Boolean) {
    val palette = if (MaterialTheme.colorScheme.background == GeneratedColors.dark.getValue("background"))
        GeneratedColors.dark else GeneratedColors.light
    val alpha = if (recording && ValueAnimator.areAnimatorsEnabled()) {
        val pulse by rememberInfiniteTransition(label = "ambient_recording").animateFloat(
            initialValue = 1f, targetValue = .5f, animationSpec = infiniteRepeatable(tween(1000), RepeatMode.Reverse),
            label = "ambient_recording_opacity")
        pulse
    } else 1f
    Box(Modifier.size(10.dp).graphicsLayer { this.alpha = alpha }
        .background(if (recording) MaterialTheme.colorScheme.error else palette.getValue("warning-fill"),
            CircleShape).testTag(if (recording) "ambient_dot_recording" else "ambient_dot_error"))
}

/** An in-memory request token and consent deliberately cannot survive recreation. */
@Composable
internal fun AmbientControlHost(runtime: AmbientRuntime, open: Boolean, onDismiss: () -> Unit, onSignIn: () -> Unit) {
    if (!open) return
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val state by runtime.state.collectAsStateWithLifecycle()
    var pendingToken by remember(runtime) { mutableStateOf<Long?>(null) }
    var consent by remember(runtime) { mutableStateOf(false) }
    fun verifyAndStart(token: Long) {
        runtime.prepareStart(token) { binding ->
            if (pendingToken != token) return@prepareStart
            pendingToken = null
            if (binding != null && runtime.validToken(token) && owner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
                try { runtime.launchPreparedStart(context, token) } catch (_: Exception) { runtime.stop("interrupted") }
            } else if (binding != null) runtime.stop("interrupted")
        }
    }
    val permissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        val token = pendingToken
        if (token != null) {
            if (!runtime.validToken(token)) pendingToken = null
            else if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                pendingToken = null
                runtime.setError("permission_denied")
            } else verifyAndStart(token)
        }
    }
    DisposableEffect(runtime) {
        onDispose {
            if (pendingToken != null) runtime.stop()
            pendingToken = null
        }
    }
    val cancelPending = {
        if (pendingToken != null) runtime.stop()
        pendingToken = null
    }
    AmbientControlsDialog(state = state, consent = consent, onConsent = { consent = it },
        checking = pendingToken != null || state.checking,
        onStart = {
            if (consent && state.canStart && pendingToken == null) {
                val token = runtime.token()
                pendingToken = token
                val needed = buildList {
                    if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED)
                        add(Manifest.permission.RECORD_AUDIO)
                    if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
                        add(Manifest.permission.POST_NOTIFICATIONS)
                }
                if (needed.isEmpty()) verifyAndStart(token) else permissions.launch(needed.toTypedArray())
            }
        },
        onStop = { pendingToken = null; runtime.stop() },
        onClearQueue = { pendingToken = null; runtime.clear(revokeAuthority = false) },
        onSignIn = { cancelPending(); onDismiss(); onSignIn() },
        onSettings = {
            context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.parse("package:${context.packageName}")))
        },
        onDismiss = { cancelPending(); onDismiss() })
}

@Composable
internal fun AmbientControlsDialog(
    state: AmbientState,
    consent: Boolean,
    onConsent: (Boolean) -> Unit,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onClearQueue: () -> Unit,
    onSignIn: () -> Unit,
    onSettings: () -> Unit,
    onDismiss: () -> Unit,
    checking: Boolean = state.checking,
) {
    var confirming by remember { mutableStateOf(false) }
    var explained by remember { mutableStateOf(false) }
    val action = when {
        state.recording || checking -> AmbientControlAction.Stop
        state.error == "not_authenticated" -> AmbientControlAction.SignIn
        state.error == "permission_denied" -> AmbientControlAction.OpenSettings
        else -> AmbientControlAction.Start
    }
    val title = when {
        state.recording -> R.string.ambient_controls_recording_title
        checking -> R.string.ambient_controls_checking_title
        state.error == "not_authenticated" -> R.string.ambient_controls_sign_in_title
        state.error == "permission_denied" -> R.string.ambient_controls_permission_title
        state.error != null -> R.string.ambient_controls_stopped_title
        else -> R.string.ambient_controls_idle_title
    }
    val explanation = when {
        state.recording -> state.error?.let(AmbientMessages::resource) ?: R.string.ambient_controls_recording_explanation
        checking -> R.string.ambient_controls_checking_explanation
        state.error == "permission_denied" -> R.string.ambient_controls_permission_explanation
        state.error != null -> AmbientMessages.resource(state.error)
        else -> R.string.ambient_controls_idle_explanation
    }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize()) {
            ScreenColumn("ambient_controls", title = stringResource(R.string.ambient_controls_title), onBack = onDismiss) {
                OutlinedCard(Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Box(Modifier.padding(top = 7.dp).size(10.dp).background(
                            if (state.recording || state.error != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.outline,
                            CircleShape))
                        Column(Modifier.weight(1f).semantics { liveRegion = LiveRegionMode.Polite },
                            verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(stringResource(title), style = MaterialTheme.typography.titleMedium,
                                modifier = Modifier.testTag("ambient_controls_status"))
                            Text(stringResource(explanation), modifier = Modifier.testTag("ambient_controls_explanation"))
                            if (state.queuedBatches > 0) {
                                val forms = stringArrayResource(R.array.ambient_controls_fragment_forms)
                                val fragments = String.format(Locale.forLanguageTag("ru"),
                                    forms[RussianPlural.index(state.queuedBatches.toDouble(), forms.size)], state.queuedBatches)
                                Text(stringResource(R.string.ambient_controls_queue, fragments, ambientBytes(state.queuedBytes)),
                                    modifier = Modifier.testTag("ambient_controls_queue"), style = MaterialTheme.typography.bodyMedium)
                            }
                            ambientUploadedAt(state.lastUploadedAt)?.let { Text(localized(GeneratedStrings.AmbientAudio.uploaded(it))) }
                        }
                    }
                }
                if (action == AmbientControlAction.Start) Row(
                    Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("ambient_consent")
                        .toggleable(value = consent, role = Role.Checkbox, onValueChange = onConsent),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Checkbox(checked = consent, onCheckedChange = null)
                    Text(stringResource(R.string.ambient_controls_consent), style = MaterialTheme.typography.bodyLarge)
                }
                Button(onClick = {
                    when (action) {
                        AmbientControlAction.Start -> onStart()
                        AmbientControlAction.Stop -> onStop()
                        AmbientControlAction.SignIn -> onSignIn()
                        AmbientControlAction.OpenSettings -> onSettings()
                    }
                }, enabled = action != AmbientControlAction.Start || consent && state.canStart,
                    colors = if (action == AmbientControlAction.Stop) ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error, contentColor = MaterialTheme.colorScheme.onError)
                    else ButtonDefaults.buttonColors(),
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("ambient_action")) {
                    Text(stringResource(when (action) {
                        AmbientControlAction.Start -> R.string.ambient_controls_start
                        AmbientControlAction.Stop -> R.string.ambient_controls_stop
                        AmbientControlAction.SignIn -> R.string.ambient_controls_sign_in
                        AmbientControlAction.OpenSettings -> R.string.ambient_controls_open_settings
                    }))
                }
                if (state.queuedBatches > 0 && !state.recording && !checking) {
                    OutlinedButton(onClick = { confirming = true },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("ambient_clear_queue")) {
                        Text(stringResource(R.string.ambient_controls_clear))
                    }
                }
                TextButton(onClick = { explained = !explained },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("ambient_explain")) {
                    Text(stringResource(R.string.ambient_controls_how))
                }
                if (explained) listOf(R.string.ambient_controls_how_capture, R.string.ambient_controls_how_background,
                    R.string.ambient_controls_how_queue).forEach { Text(stringResource(it), style = MaterialTheme.typography.bodyMedium) }
                TextButton(onClick = onDismiss,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("ambient_close")) {
                    Text(localized(GeneratedStrings.Common.close))
                }
            }
        }
        if (confirming) AlertDialog(onDismissRequest = { confirming = false },
            title = { Text(stringResource(R.string.ambient_controls_clear_title)) },
            text = { Text(stringResource(R.string.ambient_controls_clear_text)) },
            confirmButton = { TextButton(onClick = { confirming = false; onClearQueue() },
                modifier = Modifier.heightIn(min = 48.dp).testTag("ambient_clear_confirm")) {
                Text(localized(GeneratedStrings.Common.delete), color = MaterialTheme.colorScheme.error)
            } },
            dismissButton = { TextButton(onClick = { confirming = false }) { Text(localized(GeneratedStrings.Common.cancel)) } })
    }
}

private enum class AmbientControlAction { Start, Stop, SignIn, OpenSettings }

@Composable
private fun ambientBytes(value: Long): String {
    val units = stringArrayResource(R.array.ambient_controls_byte_units)
    var amount = value.toDouble()
    var index = 0
    while (amount >= 1024 && index < units.lastIndex) { amount /= 1024; index++ }
    val text = if (index == 0) value.toString() else String.format(Locale.ROOT, "%.1f", amount).removeSuffix(".0").replace('.', ',')
    return "$text ${units[index]}"
}

private fun ambientUploadedAt(value: String?): String? = value?.let {
    runCatching {
        DateTimeFormatter.ofLocalizedDateTime(FormatStyle.SHORT).withLocale(Locale.forLanguageTag("ru"))
            .format(Instant.parse(it).atZone(ZoneId.systemDefault()))
    }.getOrNull()
}

/** A signed-in idle rebind without queued audio has no loss or action to explain. */
private fun visibleAmbientFailure(state: AmbientState): String? = state.error?.takeUnless {
    it == "not_authenticated" && !state.recording && state.queuedBatches == 0 && state.canStart
}

private fun ambientErrorMessage(code: String): LocalizedString = when (code) {
    "permission_denied" -> GeneratedStrings.AmbientAudio.Errors.permission
    "not_authenticated" -> GeneratedStrings.AmbientAudio.Errors.session
    "queue_full" -> GeneratedStrings.AmbientAudio.Errors.full
    "interrupted", "microphone_busy" -> GeneratedStrings.AmbientAudio.Errors.interrupted
    "network_unavailable", "not_connected" -> GeneratedStrings.AmbientAudio.Errors.upload
    "queue_corrupt", "batch_expired" -> GeneratedStrings.AmbientAudio.Errors.queue
    "invalid_receipt", "server_rejected" -> GeneratedStrings.AmbientAudio.Errors.server
    "codec_unavailable" -> GeneratedStrings.AmbientAudio.Errors.codec
    "vad_unavailable" -> GeneratedStrings.AmbientAudio.Errors.vad
    "capture_failed" -> GeneratedStrings.AmbientAudio.Errors.capture
    else -> GeneratedStrings.AmbientAudio.Errors.unknown
}
