package assistant.mobile.design.ui

import androidx.annotation.DrawableRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.constrainHeight
import androidx.compose.ui.unit.dp

/** Compact destination tile places its primary-coloured icon above the label. */
@Composable
fun QuickActionTile(
    @DrawableRes icon: Int,
    label: String,
    onClick: () -> Unit,
    testTag: String? = null,
    modifier: Modifier = Modifier,
) {
    OutlinedCard(
        onClick = onClick,
        modifier = modifier.widthIn(min = 48.dp).heightIn(min = 64.dp)
            .then(if (testTag == null) Modifier else Modifier.testTag(testTag)),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.outlinedCardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(Spacing.s),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(Spacing.s, Alignment.CenterVertically),
        ) {
            Icon(
                painter = painterResource(icon),
                contentDescription = null,
                modifier = Modifier.size(20.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
            Text(label, style = MaterialTheme.typography.labelLarge, textAlign = TextAlign.Center)
        }
    }
}

/** Quick actions share equal-width columns and equal tile heights per row, wrapping at [columns]. */
@Composable
fun QuickActionRow(
    columns: Int? = null,
    modifier: Modifier = Modifier,
    testTag: String? = null,
    content: @Composable () -> Unit,
) {
    require(columns == null || columns > 0)
    Layout(
        content = content,
        modifier = modifier.fillMaxWidth().then(if (testTag == null) Modifier else Modifier.testTag(testTag)),
    ) { measurables, constraints ->
        val gap = Spacing.s.roundToPx()
        val columnCount = columns ?: measurables.size.coerceAtLeast(1)
        val availableWidth = (constraints.maxWidth - gap * (columnCount - 1)).coerceAtLeast(0)
        val tileWidth = availableWidth / columnCount
        val rows = measurables.chunked(columnCount).map { row ->
            val rowHeight = row.maxOf { it.minIntrinsicHeight(tileWidth) }.coerceAtMost(constraints.maxHeight)
            row.map {
                it.measure(constraints.copy(
                    minWidth = tileWidth,
                    maxWidth = tileWidth,
                    minHeight = rowHeight,
                    maxHeight = rowHeight,
                ))
            }
        }
        val rowHeights = rows.map { row -> row.maxOf { it.height } }
        val height = rowHeights.sum() + gap * (rows.size - 1).coerceAtLeast(0)
        layout(constraints.maxWidth, constraints.constrainHeight(height)) {
            var y = 0
            rows.forEachIndexed { rowIndex, row ->
                row.forEachIndexed { columnIndex, tile ->
                    tile.placeRelative(columnIndex * (tileWidth + gap), y)
                }
                y += rowHeights[rowIndex] + gap
            }
        }
    }
}
