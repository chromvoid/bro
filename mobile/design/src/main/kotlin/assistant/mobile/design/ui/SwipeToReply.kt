package assistant.mobile.design.ui

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import assistant.mobile.design.R as DesignR
import kotlinx.coroutines.launch

private val ReplyThreshold = 56.dp
private val ReplyLimit = 88.dp

/**
 * Telegram's swipe on a message row: dragged towards the start, the row reveals a reply arrow at
 * its end; let go past [ReplyThreshold] and it calls [onReply]. The row springs back either way.
 * Vertical drags stay with the list. The screen still offers reply in the message's menu for
 * TalkBack and for anyone who does not swipe.
 */
@Composable
fun SwipeToReply(
    onReply: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable () -> Unit,
) {
    val haptics = LocalHapticFeedback.current
    val toward = if (LocalLayoutDirection.current == LayoutDirection.Rtl) 1f else -1f
    val density = LocalDensity.current
    val threshold = with(density) { ReplyThreshold.toPx() }
    val limit = with(density) { ReplyLimit.toPx() }
    val reply by rememberUpdatedState(onReply)
    val scope = rememberCoroutineScope()
    // The distance travelled towards the start, in pixels, never negative.
    var travelled by remember { mutableFloatStateOf(0f) }
    var armed by remember { mutableStateOf(false) }
    val drag = rememberDraggableState { delta ->
        val step = delta * toward
        // Past the threshold the row follows the finger reluctantly, as Telegram's does.
        val resisted = if (travelled + step > threshold && step > 0f) step * 0.35f else step
        travelled = (travelled + resisted).coerceIn(0f, limit)
        val reached = travelled >= threshold
        if (reached && !armed) haptics.performHapticFeedback(HapticFeedbackType.GestureThresholdActivate)
        armed = reached
    }
    Box(
        modifier.draggable(
            state = drag,
            orientation = Orientation.Horizontal,
            enabled = enabled,
            onDragStopped = {
                if (armed) reply()
                armed = false
                scope.launch {
                    animate(
                        initialValue = travelled,
                        targetValue = 0f,
                        animationSpec = spring(dampingRatio = Spring.DampingRatioLowBouncy, stiffness = Spring.StiffnessMedium),
                    ) { value, _ -> travelled = value }
                }
            },
        ),
    ) {
        Icon(
            painter = painterResource(DesignR.drawable.lucide_reply),
            contentDescription = null,
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .padding(end = 8.dp)
                .graphicsLayer {
                    val shown = (travelled / threshold).coerceIn(0f, 1f)
                    alpha = shown
                    val scale = 0.6f + 0.4f * shown
                    scaleX = scale
                    scaleY = scale
                }
                .size(32.dp)
                .background(MaterialTheme.colorScheme.secondaryContainer, CircleShape)
                .padding(6.dp),
            tint = MaterialTheme.colorScheme.onSecondaryContainer,
        )
        Box(Modifier.graphicsLayer { translationX = travelled * toward }) { content() }
    }
}
