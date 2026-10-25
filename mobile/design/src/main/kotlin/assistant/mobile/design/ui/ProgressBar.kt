package assistant.mobile.design.ui

import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics

/** Determinate or indeterminate progress without Material's trailing stop indicator. */
@Composable
fun ProgressBar(
    progress: (() -> Float)? = null,
    modifier: Modifier = Modifier,
    color: Color? = null,
    contentDescription: String? = null,
    testTag: String? = null,
) {
    val indicatorModifier = modifier
        .then(if (testTag == null) Modifier else Modifier.testTag(testTag))
        .then(if (contentDescription == null) Modifier else Modifier.semantics {
            this.contentDescription = contentDescription
        })
    val indicatorColor = color ?: MaterialTheme.colorScheme.primary
    if (progress == null) {
        LinearProgressIndicator(modifier = indicatorModifier, color = indicatorColor)
    } else {
        LinearProgressIndicator(
            progress = progress,
            modifier = indicatorModifier,
            color = indicatorColor,
            drawStopIndicator = {},
        )
    }
}
