package assistant.mobile.android

import android.view.WindowManager
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedIconToggleButton
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import assistant.mobile.core.GeneratedStrings
import assistant.mobile.core.LocalizedString
import assistant.mobile.core.calls.CallAudioRoute
import assistant.mobile.core.calls.CallOutcome
import assistant.mobile.core.calls.CallOverlayMode
import assistant.mobile.core.calls.CallPhase
import assistant.mobile.core.calls.CallsState
import assistant.mobile.core.calls.CallState
import kotlinx.coroutines.delay

/** The app's one modal call surface is independent of the navigation back stack. */
@Composable
internal fun NativeCallOverlay(
    state: CallsState,
    onAnswer: () -> Unit,
    onDecline: () -> Unit,
    onHangUp: () -> Unit,
    onToggleMute: () -> Unit,
    onRouteChange: (CallAudioRoute) -> Unit,
    routeNames: Map<CallAudioRoute, String> = emptyMap(),
    onClearError: () -> Unit = {},
) {
    if (state.mode != CallOverlayMode.INCOMING && state.mode != CallOverlayMode.SESSION) return
    val incoming = state.mode == CallOverlayMode.INCOMING
    val view = state.view
    val name = if (incoming) state.call?.peer?.name.orEmpty() else view?.peerName.orEmpty()
    val phase = localized(if (incoming) GeneratedStrings.Calls.incoming else callPhase(view?.phase, view?.outcome))
    val talking = !incoming && view?.phase in setOf(CallPhase.CONNECTING, CallPhase.CONNECTED, CallPhase.UNSTABLE)
    var now by remember(view?.connectedAt) { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(view?.connectedAt, view?.phase) {
        while (view?.connectedAt != null && view.phase != CallPhase.ENDED) {
            now = System.currentTimeMillis()
            delay(1_000)
        }
    }
    val status = if (view?.phase == CallPhase.CONNECTED)
        callDuration((now - (view.connectedAt ?: now)) / 1_000) else phase
    val keyboard = LocalSoftwareKeyboardController.current
    val focus = remember { FocusRequester() }
    // Dialog installs another AndroidComposeView; keep NativeTheme's neutral font scale.
    // Read on every recomposition so configuration density changes also reach this window.
    val themedDensity = LocalDensity.current

    Dialog(onDismissRequest = {}, properties = DialogProperties(
        dismissOnBackPress = false, dismissOnClickOutside = false,
        usePlatformDefaultWidth = false, decorFitsSystemWindows = false,
    )) {
        CompositionLocalProvider(LocalDensity provides themedDensity) {
            val window = (LocalView.current.parent as? DialogWindowProvider)?.window
            val holding = state.mode == CallOverlayMode.SESSION && view?.phase != CallPhase.ENDED
            DisposableEffect(window, holding) {
                if (holding) window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                else window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                onDispose { window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
            }
            Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
                Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxWidth().weight(1f)) {
                    BoxWithConstraints(Modifier.fillMaxSize().testTag("call_overlay").focusRequester(focus).focusable()) {
                        // The whole surface scrolls on small/landscape screens and at the 125% type limit.
                        Column(
                            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).heightIn(min = maxHeight)
                                .systemBarsPadding().padding(horizontal = 24.dp, vertical = 28.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(20.dp, Alignment.CenterVertically),
                        ) {
                            Box(Modifier.size(96.dp).background(MaterialTheme.colorScheme.surfaceVariant, CircleShape)
                                .clearAndSetSemantics {}, contentAlignment = Alignment.Center) {
                                Text(name.trim().firstOrNull()?.uppercase() ?: "?", style = MaterialTheme.typography.headlineLarge,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Text(name, style = MaterialTheme.typography.headlineMedium, textAlign = TextAlign.Center,
                                modifier = Modifier.fillMaxWidth().semantics { heading() }.testTag("call_peer"))
                            // Only the phase is announced: the ticking duration never interrupts a conversation.
                            Box(Modifier.testTag("call_phase").semantics {
                                contentDescription = phase
                                liveRegion = LiveRegionMode.Polite
                            }) {
                                Text(status, style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.testTag("call_status").clearAndSetSemantics {})
                            }
                            if (view?.phase == CallPhase.OUTGOING) Text(localized(GeneratedStrings.Calls.reachHint(name)),
                                style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center,
                                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.testTag("call_reach_hint"))
                            if (talking && view?.muted == true) Text(localized(GeneratedStrings.Calls.muted),
                                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.testTag("call_muted"))
                            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                when {
                                    incoming -> {
                                        Button(onClick = onAnswer, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("call_accept")) {
                                            Text(localized(GeneratedStrings.Calls.answer))
                                        }
                                        CallEndButton(GeneratedStrings.Calls.decline, "call_decline", onDecline)
                                    }
                                    view?.phase == CallPhase.OUTGOING -> CallEndButton(GeneratedStrings.Calls.cancel, "call_hangup", onHangUp)
                                    talking && view != null -> {
                                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally)) {
                                            CallAudioRoute.entries.filter { it in view.availableRoutes }.forEach { route ->
                                                val label = routeNames[route] ?: localized(GeneratedStrings.Calls.enableSound)
                                                OutlinedIconToggleButton(checked = view.route == route, onCheckedChange = { onRouteChange(route) },
                                                    modifier = Modifier.size(56.dp).testTag(when (route) {
                                                        CallAudioRoute.EARPIECE -> "call_earpiece"
                                                        CallAudioRoute.SPEAKER -> "call_speaker"
                                                        CallAudioRoute.BLUETOOTH -> "call_bluetooth"
                                                    }).semantics { contentDescription = label }) { CallRouteIcon(route) }
                                            }
                                        }
                                        OutlinedButton(onClick = onToggleMute,
                                            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("call_mute")
                                                .semantics { toggleableState = if (view.muted) ToggleableState.On else ToggleableState.Off }) {
                                            Text(localized(if (view.muted) GeneratedStrings.Calls.unmute else GeneratedStrings.Calls.mute))
                                        }
                                        CallEndButton(GeneratedStrings.Calls.hangUp, "call_hangup", onHangUp)
                                    }
                                }
                            }
                        }
                    }
                }
                // Reserve the refusal's height: the snackbar must never cover Answer or Decline.
                if (incoming) NativeCallNotice(state, onClearError, allowIncoming = true, overlay = false)
            }
            LaunchedEffect(Unit) { keyboard?.hide(); focus.requestFocus() }
        }
    }
}

/** The web reports refusals as a toast; the native snackbar uses those same messages. */
@Composable
internal fun NativeCallNotice(state: CallsState, onDismiss: () -> Unit, modifier: Modifier = Modifier,
    allowIncoming: Boolean = false, overlay: Boolean = true) {
    val message = state.serverError ?: callNoticeMessage(state)?.let { localized(it) }
    val close = localized(GeneratedStrings.Common.close)
    val host = remember { SnackbarHostState() }
    val dismiss by rememberUpdatedState(onDismiss)
    LaunchedEffect(message, state.mode, allowIncoming) {
        // The dialog hosts incoming refusals; a local session first shows its brief ended state.
        if (message != null && state.mode != CallOverlayMode.SESSION &&
            (state.mode != CallOverlayMode.INCOMING || allowIncoming)) {
            host.showSnackbar(message = message, actionLabel = close, duration = SnackbarDuration.Short)
            dismiss()
        }
    }
    if (!overlay && host.currentSnackbarData == null) return
    val placement = if (overlay) modifier.fillMaxSize().navigationBarsPadding().imePadding()
        else modifier.fillMaxWidth().navigationBarsPadding()
    Box(placement.padding(16.dp), contentAlignment = Alignment.BottomCenter) {
        SnackbarHost(hostState = host) { data ->
            Snackbar(data, modifier = Modifier.testTag("call_error"),
                containerColor = MaterialTheme.colorScheme.errorContainer,
                contentColor = MaterialTheme.colorScheme.onErrorContainer,
                actionColor = MaterialTheme.colorScheme.onErrorContainer)
        }
    }
}

/** Keys are machine codes from CallsController, never interpreted human language. */
private fun callNoticeMessage(state: CallsState): LocalizedString? = when (state.errorKey) {
    "calls.denied" -> GeneratedStrings.Calls.denied
    "calls.unavailable" -> GeneratedStrings.Calls.unavailable
    "calls.unsupported" -> GeneratedStrings.Calls.unsupported
    "calls.error.busy" -> GeneratedStrings.Calls.Error.busy
    "calls.error.inCall" -> GeneratedStrings.Calls.Error.inCall
    "calls.error.notFriends" -> GeneratedStrings.Calls.Error.notFriends
    "calls.error.notAvailable" -> GeneratedStrings.Calls.Error.notAvailable
    "calls.error.tooMany" -> GeneratedStrings.Calls.Error.tooMany
    "calls.error.answeredElsewhere" -> GeneratedStrings.Calls.Error.answeredElsewhere
    "calls.error.ended" -> GeneratedStrings.Calls.Error.ended
    else -> state.error ?: state.errorKey?.let { GeneratedStrings.Errors.unknown }
}

/** Same status and action as the web status line when another device owns the call. */
@Composable
internal fun NativeCallElsewhere(state: CallsState, onHangUp: () -> Unit) {
    if (state.mode != CallOverlayMode.ELSEWHERE) return
    val ringing = state.call?.state == CallState.RINGING
    Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxWidth().testTag("call_elsewhere")) {
        Column(Modifier.padding(horizontal = 20.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            StatusMessage(if (ringing) GeneratedStrings.Calls.elsewhereCalling else GeneratedStrings.Calls.elsewhere, "call_elsewhere_status")
            CallEndButton(if (ringing) GeneratedStrings.Calls.cancel else GeneratedStrings.Calls.hangUp, "call_elsewhere_end", onHangUp)
        }
    }
}

@Composable
private fun CallEndButton(label: LocalizedString, tag: String, onClick: () -> Unit) {
    Button(onClick = onClick, colors = ButtonDefaults.buttonColors(
        containerColor = MaterialTheme.colorScheme.error, contentColor = MaterialTheme.colorScheme.onError,
    ), modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag(tag)) { Text(localized(label)) }
}

private fun callPhase(phase: CallPhase?, outcome: CallOutcome?): LocalizedString = when (phase) {
    CallPhase.OUTGOING -> GeneratedStrings.Calls.calling
    CallPhase.CONNECTING -> GeneratedStrings.Calls.connecting
    CallPhase.CONNECTED -> GeneratedStrings.Calls.connected
    CallPhase.UNSTABLE -> GeneratedStrings.Calls.unstable
    CallPhase.ENDED -> when (outcome) {
        CallOutcome.DECLINED -> GeneratedStrings.Calls.Ended.declined
        CallOutcome.CANCELLED -> GeneratedStrings.Calls.Ended.cancelled
        CallOutcome.MISSED -> GeneratedStrings.Calls.Ended.missed
        CallOutcome.FAILED -> GeneratedStrings.Calls.Ended.failed
        CallOutcome.LOST -> GeneratedStrings.Calls.Ended.lost
        else -> GeneratedStrings.Calls.Ended.completed
    }
    null -> GeneratedStrings.Calls.connecting
}

internal fun callDuration(seconds: Long): String {
    val total = seconds.coerceAtLeast(0)
    val hours = total / 3_600
    val minutes = total % 3_600 / 60
    val rest = (total % 60).toString().padStart(2, '0')
    return if (hours > 0) "$hours:${minutes.toString().padStart(2, '0')}:$rest" else "$minutes:$rest"
}

/** Native route controls use system device names, with no separate translation source. */
@Composable
private fun CallRouteIcon(route: CallAudioRoute) {
    val colour = LocalContentColor.current
    Canvas(Modifier.size(24.dp).clearAndSetSemantics {}) {
        val unit = size.width / 24f
        val stroke = Stroke(width = 2f * unit, cap = StrokeCap.Round)
        fun at(x: Float, y: Float) = Offset(x * unit, y * unit)
        when (route) {
            CallAudioRoute.EARPIECE -> {
                drawRoundRect(colour, topLeft = at(6f, 2f), size = Size(12f * unit, 20f * unit),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(2f * unit), style = stroke)
                drawLine(colour, at(10f, 5f), at(14f, 5f), strokeWidth = 2f * unit, cap = StrokeCap.Round)
                drawCircle(colour, radius = unit, center = at(12f, 19f))
            }
            CallAudioRoute.SPEAKER -> {
                val cone = Path().apply {
                    moveTo(3f * unit, 9f * unit); lineTo(7f * unit, 9f * unit)
                    lineTo(12f * unit, 5f * unit); lineTo(12f * unit, 19f * unit)
                    lineTo(7f * unit, 15f * unit); lineTo(3f * unit, 15f * unit); close()
                }
                drawPath(cone, colour, style = stroke)
                drawArc(colour, -55f, 110f, useCenter = false, topLeft = at(11f, 7f), size = Size(8f * unit, 10f * unit), style = stroke)
                drawArc(colour, -55f, 110f, useCenter = false, topLeft = at(11f, 3f), size = Size(12f * unit, 18f * unit), style = stroke)
            }
            CallAudioRoute.BLUETOOTH -> {
                val rune = Path().apply {
                    moveTo(7f * unit, 7f * unit); lineTo(17f * unit, 17f * unit)
                    lineTo(12f * unit, 22f * unit); lineTo(12f * unit, 2f * unit)
                    lineTo(17f * unit, 7f * unit); lineTo(7f * unit, 17f * unit)
                }
                drawPath(rune, colour, style = stroke)
            }
        }
    }
}
