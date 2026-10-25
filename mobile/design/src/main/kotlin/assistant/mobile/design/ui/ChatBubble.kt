package assistant.mobile.design.ui

import androidx.annotation.DrawableRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.AlignmentLine
import androidx.compose.ui.layout.FirstBaseline
import androidx.compose.ui.layout.SubcomposeLayout
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.constrainHeight
import androidx.compose.ui.unit.constrainWidth
import androidx.compose.ui.unit.dp
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.roundToInt

private enum class BubbleSlot { Content, Actions, Text, Meta }

/** A message surface and its optional inline actions, independent of the conversation model. */
@Composable
fun ChatBubble(
    outgoing: Boolean,
    joinedToPrevious: Boolean = false,
    modifier: Modifier = Modifier,
    highlighted: Boolean = false,
    inlineActions: (@Composable RowScope.() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(
        topStart = if (!outgoing && joinedToPrevious) 6.dp else 18.dp,
        topEnd = if (outgoing && joinedToPrevious) 6.dp else 18.dp,
        bottomEnd = if (outgoing) 6.dp else 18.dp,
        bottomStart = if (outgoing) 18.dp else 6.dp,
    )
    SubcomposeLayout(
        Modifier.fillMaxWidth().then(
            if (highlighted) Modifier.background(colors.primary.copy(alpha = 0.08f)) else Modifier,
        ),
    ) { constraints ->
        require(constraints.hasBoundedWidth) { "ChatBubble requires a bounded width" }
        val maxBubbleWidth = (constraints.maxWidth * if (outgoing) 0.8f else 0.9f).roundToInt()
        val bubble = subcompose(BubbleSlot.Content) {
            Surface(
                modifier = modifier,
                shape = shape,
                color = if (outgoing) colors.secondaryContainer else colors.surface,
                contentColor = if (outgoing) colors.onSecondaryContainer else colors.onSurface,
                border = if (outgoing) null else BorderStroke(1.dp, colors.outlineVariant),
            ) {
                ProvideTextStyle(MaterialTheme.typography.bodyLarge) {
                    Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), content = content)
                }
            }
        }.single().measure(
            constraints.copy(
                minWidth = if (inlineActions == null) 0 else maxBubbleWidth,
                maxWidth = maxBubbleWidth,
                minHeight = 0,
            ),
        )
        val actions = inlineActions?.let { actions ->
            subcompose(BubbleSlot.Actions) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    content = actions,
                )
            }.single().measure(Constraints.fixedWidth(bubble.width))
        }
        layout(constraints.maxWidth, bubble.height + (actions?.height ?: 0)) {
            val x = if (outgoing) constraints.maxWidth - bubble.width else 0
            bubble.placeRelative(x, 0)
            actions?.placeRelative(x, bubble.height)
        }
    }
}

/** A 44 dp bot-style action inside a 48 dp touch zone; siblings share the bubble width. */
@Composable
fun RowScope.ChatBubbleAction(
    label: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
    @DrawableRes icon: Int? = null,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    Box(
        modifier.weight(1f).height(48.dp).clickable(enabled = enabled, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth().height(44.dp),
            shape = RoundedCornerShape(12.dp),
            color = colors.secondaryContainer,
            contentColor = colors.primary.copy(alpha = if (enabled) 1f else 0.38f),
        ) {
            Row(
                modifier = Modifier.fillMaxSize().padding(horizontal = 8.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (icon != null) {
                    Icon(painterResource(icon), contentDescription = null, modifier = Modifier.size(24.dp))
                    Spacer(Modifier.width(4.dp))
                }
                Text(label, style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}

/** Plain text leaves room for metadata on its final line when that line has enough space. */
@Composable
fun ChatBubbleText(
    text: String,
    meta: @Composable () -> Unit,
    modifier: Modifier = Modifier,
) {
    val textMeasurer = rememberTextMeasurer()
    val textStyle = LocalTextStyle.current
    val direction = LocalLayoutDirection.current
    SubcomposeLayout(modifier) { constraints ->
        require(constraints.hasBoundedWidth) { "ChatBubbleText requires a bounded width" }
        val loose = constraints.copy(minWidth = 0, minHeight = 0)
        val metaPlaceable = subcompose(BubbleSlot.Meta, meta).single().measure(loose)
        val measuredText = textMeasurer.measure(text, style = textStyle, constraints = loose)
        val textPlaceable = subcompose(BubbleSlot.Text) {
            Text(text, style = textStyle)
        }.single().measure(loose.copy(minWidth = measuredText.size.width, maxWidth = measuredText.size.width))
        val lastLine = measuredText.lineCount - 1
        val gap = 4.dp.roundToPx()
        val lastLineWidth = ceil(
            measuredText.getLineRight(lastLine) - measuredText.getLineLeft(lastLine),
        ).toInt()
        val inline = lastLineWidth + gap + metaPlaceable.width <= constraints.maxWidth
        val contentWidth = if (inline) {
            max(textPlaceable.width, lastLineWidth + gap + metaPlaceable.width)
        } else {
            max(textPlaceable.width, metaPlaceable.width)
        }
        val width = constraints.constrainWidth(contentWidth)
        val baseline = metaPlaceable[FirstBaseline]
        val metaY = if (inline) {
            if (baseline != AlignmentLine.Unspecified) {
                (measuredText.getLineBaseline(lastLine) - baseline).roundToInt().coerceAtLeast(0)
            } else {
                (textPlaceable.height - metaPlaceable.height).coerceAtLeast(0)
            }
        } else {
            textPlaceable.height + 2.dp.roundToPx()
        }
        layout(width, constraints.constrainHeight(max(textPlaceable.height, metaY + metaPlaceable.height))) {
            val textX = if (direction == LayoutDirection.Rtl) width - textPlaceable.width else 0
            textPlaceable.place(textX, 0)
            val metaX = if (direction == LayoutDirection.Rtl) 0 else width - metaPlaceable.width
            metaPlaceable.place(metaX, metaY)
        }
    }
}
