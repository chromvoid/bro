package assistant.mobile.design.ui

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import assistant.mobile.design.R as DesignR

/** Selectable filter chip shows a Lucide check only while selected. */
@Composable
fun SelectChip(
    selected: Boolean,
    label: String,
    onClick: () -> Unit,
    testTag: String? = null,
    modifier: Modifier = Modifier,
) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        modifier = modifier.then(if (testTag == null) Modifier else Modifier.testTag(testTag)),
        label = { Text(label, style = MaterialTheme.typography.labelLarge) },
        leadingIcon = if (selected) {
            {
                Icon(
                    painter = painterResource(DesignR.drawable.lucide_check),
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
            }
        } else null,
    )
}

/** Two to four mutually exclusive options share one full-width control; null leaves them unselected. */
@Composable
fun <T> SegmentedChoice(
    options: List<T>,
    selected: T?,
    onSelect: (T) -> Unit,
    label: (T) -> String,
    icon: ((T) -> Int)? = null,
    testTag: String? = null,
    optionTestTag: ((T) -> String?)? = null,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    require(options.size in 2..4)
    require(selected == null || selected in options)
    SingleChoiceSegmentedButtonRow(
        modifier = modifier.fillMaxWidth().then(if (testTag == null) Modifier else Modifier.testTag(testTag)),
    ) {
        options.forEachIndexed { index, option ->
            SegmentedButton(
                selected = option == selected,
                onClick = { onSelect(option) },
                enabled = enabled,
                shape = SegmentedButtonDefaults.itemShape(index, options.size),
                modifier = Modifier.then(
                    optionTestTag?.invoke(option)?.let { Modifier.testTag(it) } ?: Modifier,
                ),
                contentPadding = PaddingValues(horizontal = Spacing.s, vertical = Spacing.s),
                icon = {
                    icon?.invoke(option)?.let { drawable ->
                        Icon(painterResource(drawable), contentDescription = null, modifier = Modifier.size(18.dp))
                    }
                },
                label = {
                    Text(label(option), style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                },
            )
        }
    }
}
