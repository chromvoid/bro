package assistant.mobile.design.ui

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryScrollableTabRow
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/** Screen tabs sit on the background and scroll when their labels need more room. */
@Composable
fun ScreenTabs(
    titles: List<String>,
    selected: Int,
    onSelect: (Int) -> Unit,
    testTag: String? = null,
    modifier: Modifier = Modifier,
    tabTestTag: ((Int) -> String?)? = null,
) {
    require(selected in titles.indices)
    val textStyle = MaterialTheme.typography.labelLarge
    val textMeasurer = rememberTextMeasurer()
    val horizontalPadding = with(LocalDensity.current) { (Spacing.s * 2).roundToPx() }
    val tabs: @Composable () -> Unit = {
        titles.forEachIndexed { index, title ->
            Tab(
                selected = index == selected,
                onClick = { onSelect(index) },
                modifier = Modifier.heightIn(min = 48.dp).then(
                    tabTestTag?.invoke(index)?.let { Modifier.testTag(it) } ?: Modifier,
                ),
                selectedContentColor = MaterialTheme.colorScheme.primary,
                unselectedContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
            ) {
                Text(title, style = textStyle, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = Spacing.s))
            }
        }
    }
    val taggedModifier = modifier.fillMaxWidth().then(if (testTag == null) Modifier else Modifier.testTag(testTag))
    BoxWithConstraints(taggedModifier) {
        val slotWidth = constraints.maxWidth / titles.size
        val needsScroll = titles.size >= 5 || titles.any { title ->
            textMeasurer.measure(title, style = textStyle, maxLines = 1).size.width +
                horizontalPadding > slotWidth
        }
        if (needsScroll) {
            PrimaryScrollableTabRow(
                selectedTabIndex = selected,
                modifier = Modifier.fillMaxWidth(),
                containerColor = MaterialTheme.colorScheme.background,
                edgePadding = Spacing.l,
                tabs = tabs,
            )
        } else {
            PrimaryTabRow(
                selectedTabIndex = selected,
                modifier = Modifier.fillMaxWidth(),
                containerColor = MaterialTheme.colorScheme.background,
                tabs = tabs,
            )
        }
    }
}
