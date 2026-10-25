package assistant.mobile.design.ui

import androidx.annotation.DrawableRes
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import assistant.mobile.design.R as DesignR

/** Where a held microphone is: at rest, held down while recording, or locked hands-free. */
enum class VoiceHoldPhase { Idle, Held, Locked }

/**
 * The gesture state of [HoldToRecordButton]. The screen reads it to lay out its recording row
 * and calls [reset] when a held or locked recording ends anywhere else (sent, cancelled,
 * interrupted), so the button returns to rest.
 */
@Stable
class VoiceHoldState internal constructor() {
    var phase by mutableStateOf(VoiceHoldPhase.Idle)
        internal set

    /** The slide towards the start: 0 at rest, 1 cancels the recording. */
    var cancelProgress by mutableFloatStateOf(0f)
        internal set

    /** The slide up: 0 at rest, 1 locks the recording. */
    var lockProgress by mutableFloatStateOf(0f)
        internal set

    internal var drag by mutableStateOf(Offset.Zero)

    fun reset() {
        phase = VoiceHoldPhase.Idle
        cancelProgress = 0f
        lockProgress = 0f
        drag = Offset.Zero
    }
}

@Composable
fun rememberVoiceHoldState(): VoiceHoldState = remember { VoiceHoldState() }

private const val HOLD_DELAY_MILLIS = 220L
private val HeldCircle = 96.dp
private val CancelDistance = 120.dp
private val LockDistance = 80.dp
private val LockPillWidth = 40.dp
private val LockPillHeight = 72.dp

// The pill's centre above the button's centre: the held circle's radius, a gap and half the pill.
private val LockPillRise = HeldCircle / 2 + 16.dp + LockPillHeight / 2

/**
 * Telegram's voice button. A tap calls [onTap]. Held past a short delay it calls [onHoldStart]
 * and grows into a filled circle that follows the finger: releasing calls [onRelease], sliding
 * [CancelDistance] towards the start calls [onCancel], sliding [LockDistance] up calls [onLock]
 * and leaves the state [VoiceHoldPhase.Locked] until the screen resets it.
 *
 * The circle and the lock above it draw outside the 48 dp button: no ancestor may clip it.
 * Keep the button at one call site while the row around it changes, or the gesture is lost.
 */
@Composable
fun HoldToRecordButton(
    state: VoiceHoldState,
    @DrawableRes icon: Int,
    contentDescription: String,
    onTap: () -> Unit,
    onHoldStart: () -> Unit,
    onRelease: () -> Unit,
    onCancel: () -> Unit,
    onLock: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    level: () -> Float = { 0f },
    testTag: String? = null,
) {
    val haptics = LocalHapticFeedback.current
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val density = LocalDensity.current
    val cancelDistance = with(density) { CancelDistance.toPx() }
    val lockDistance = with(density) { LockDistance.toPx() }
    val lockRise = with(density) { LockPillRise.toPx() }
    val tap by rememberUpdatedState(onTap)
    val holdStart by rememberUpdatedState(onHoldStart)
    val release by rememberUpdatedState(onRelease)
    val cancel by rememberUpdatedState(onCancel)
    val lock by rememberUpdatedState(onLock)
    val colors = MaterialTheme.colorScheme
    val held = state.phase == VoiceHoldPhase.Held
    val grow by animateFloatAsState(
        targetValue = if (held) 1f else 0f,
        animationSpec = spring(dampingRatio = 0.6f, stiffness = Spring.StiffnessMediumLow),
        label = "voiceHoldGrow",
    )
    Box(
        modifier = modifier
            .size(48.dp)
            .then(if (testTag == null) Modifier else Modifier.testTag(testTag))
            .semantics {
                role = Role.Button
                this.contentDescription = contentDescription
                if (enabled) onClick { tap(); true } else disabled()
            }
            .pointerInput(state, enabled, rtl, cancelDistance, lockDistance) {
                if (!enabled) return@pointerInput
                awaitEachGesture {
                    val down = awaitFirstDown()
                    down.consume()
                    var tapped = false
                    val early = withTimeoutOrNull(HOLD_DELAY_MILLIS) {
                        val up = waitForUpOrCancellation()
                        if (up != null) {
                            up.consume()
                            tapped = true
                        }
                        true
                    }
                    if (early != null) {
                        if (tapped) tap()
                        return@awaitEachGesture
                    }
                    state.phase = VoiceHoldPhase.Held
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    holdStart()
                    var settled = false
                    try {
                        while (true) {
                            val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
                            change.consume()
                            if (!change.pressed) {
                                settled = true
                                if (state.phase == VoiceHoldPhase.Held) {
                                    state.reset()
                                    release()
                                }
                                break
                            }
                            // Cancelled, locked or reset by the screen: wait for the finger to lift.
                            if (state.phase != VoiceHoldPhase.Held) continue
                            val delta = change.position - down.position
                            val towardStart = if (rtl) delta.x else -delta.x
                            state.drag = Offset(
                                x = if (rtl) delta.x.coerceAtLeast(0f) else delta.x.coerceAtMost(0f),
                                y = delta.y.coerceIn(-lockDistance, 0f),
                            )
                            state.cancelProgress = (towardStart / cancelDistance).coerceIn(0f, 1f)
                            state.lockProgress = (-delta.y / lockDistance).coerceIn(0f, 1f)
                            if (state.cancelProgress >= 1f) {
                                haptics.performHapticFeedback(HapticFeedbackType.GestureThresholdActivate)
                                state.reset()
                                settled = true
                                cancel()
                            } else if (state.lockProgress >= 1f) {
                                haptics.performHapticFeedback(HapticFeedbackType.GestureThresholdActivate)
                                state.reset()
                                state.phase = VoiceHoldPhase.Locked
                                settled = true
                                lock()
                            }
                        }
                    } finally {
                        // A gesture the system took away (a dialog over the app, the button leaving
                        // the screen) must not leave a recording running that nobody holds.
                        if (!settled && state.phase == VoiceHoldPhase.Held) {
                            state.reset()
                            cancel()
                        }
                    }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        if (grow > 0.01f) {
            Column(
                modifier = Modifier
                    .requiredSize(LockPillWidth, LockPillHeight)
                    .graphicsLayer {
                        translationY = -lockRise + state.drag.y * 0.3f
                        alpha = grow.coerceIn(0f, 1f)
                    }
                    .background(colors.surface, RoundedCornerShape(LockPillWidth / 2))
                    .border(1.dp, colors.outlineVariant, RoundedCornerShape(LockPillWidth / 2)),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterVertically),
            ) {
                Icon(
                    painter = painterResource(DesignR.drawable.lucide_lock),
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                    tint = lerp(colors.onSurfaceVariant, colors.primary, state.lockProgress),
                )
                Icon(
                    painter = painterResource(DesignR.drawable.lucide_chevron_up),
                    contentDescription = null,
                    modifier = Modifier.size(16.dp).graphicsLayer { alpha = 1f - state.lockProgress },
                    tint = colors.onSurfaceVariant,
                )
            }
            // A soft halo follows the voice level around the circle, as Telegram's does.
            Box(
                Modifier
                    .requiredSize(HeldCircle)
                    .graphicsLayer {
                        translationX = state.drag.x
                        translationY = state.drag.y
                        val scale = grow * (1f + level().coerceIn(0f, 1f) * 0.35f)
                        scaleX = scale
                        scaleY = scale
                        alpha = 0.2f * grow.coerceIn(0f, 1f)
                    }
                    .background(colors.primary, CircleShape),
            )
            Box(
                Modifier
                    .requiredSize(HeldCircle)
                    .graphicsLayer {
                        translationX = state.drag.x
                        translationY = state.drag.y
                        scaleX = grow
                        scaleY = grow
                    }
                    .background(colors.primary, CircleShape),
            )
        }
        val rest = if (enabled) colors.onSurfaceVariant else colors.onSurfaceVariant.copy(alpha = 0.38f)
        Icon(
            painter = painterResource(icon),
            contentDescription = null,
            modifier = Modifier
                .size(24.dp)
                .graphicsLayer {
                    translationX = state.drag.x * grow
                    translationY = state.drag.y * grow
                    val scale = 1f + 0.15f * grow
                    scaleX = scale
                    scaleY = scale
                },
            tint = lerp(rest, colors.onPrimary, grow.coerceIn(0f, 1f)),
        )
    }
}

/** The red dot of a running recording, pulsing slowly. */
@Composable
fun RecordingDot(modifier: Modifier = Modifier) {
    val pulse = rememberInfiniteTransition(label = "recordingDot").animateFloat(
        initialValue = 1f,
        targetValue = 0.25f,
        animationSpec = infiniteRepeatable(tween(600, easing = LinearEasing), RepeatMode.Reverse),
        label = "recordingDotAlpha",
    )
    Box(
        modifier
            .size(10.dp)
            .graphicsLayer { alpha = pulse.value }
            .background(MaterialTheme.colorScheme.error, CircleShape),
    )
}

/** «‹ Cancel» in a held recording's row: it moves towards the start and fades as the finger does. */
@Composable
fun SlideToCancelLabel(text: String, progress: () -> Float, modifier: Modifier = Modifier) {
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val travel = with(LocalDensity.current) { 32.dp.toPx() }
    val color = MaterialTheme.colorScheme.onSurfaceVariant
    Row(
        modifier = modifier.graphicsLayer {
            val moved = progress().coerceIn(0f, 1f)
            translationX = (if (rtl) 1f else -1f) * moved * travel
            alpha = 1f - moved
        },
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            painter = painterResource(DesignR.drawable.lucide_chevron_left),
            contentDescription = null,
            modifier = Modifier.size(16.dp).graphicsLayer { if (rtl) scaleX = -1f },
            tint = color,
        )
        Text(text, style = MaterialTheme.typography.bodyMedium, color = color, maxLines = 1)
    }
}

/**
 * The composer's trailing action: [send] while there is something to send, else [record].
 * They swap with a quick scale and fade. Nothing is clipped, so a held [HoldToRecordButton]
 * inside [record] can grow past its bounds.
 */
@Composable
fun ComposerActionSwitch(
    showSend: Boolean,
    modifier: Modifier = Modifier,
    send: @Composable () -> Unit,
    record: @Composable () -> Unit,
) {
    AnimatedContent(
        targetState = showSend,
        modifier = modifier,
        transitionSpec = {
            (scaleIn(tween(150), initialScale = 0.6f) + fadeIn(tween(150))) togetherWith
                (scaleOut(tween(150), targetScale = 0.6f) + fadeOut(tween(100))) using
                SizeTransform(clip = false)
        },
        contentAlignment = Alignment.Center,
        label = "composerAction",
    ) { sending -> if (sending) send() else record() }
}
