/**
 * Use one Button per screen; other actions use FilledTonalButton or OutlinedButton.
 * TextButton is for dialogs or one secondary action beside the primary action.
 */
package assistant.mobile.design.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/** Bordered surface with the web card's padding and spacing between its contents. */
@Composable
fun AppCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    testTag: String? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    AppCardSurface(
        modifier = modifier.fillMaxWidth().then(if (testTag == null) Modifier else Modifier.testTag(testTag)),
        onClick = onClick,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(Spacing.l),
            verticalArrangement = Arrangement.spacedBy(Spacing.m),
            content = content,
        )
    }
}

/** Card heading uses the shared title role and exposes heading semantics. */
@Composable
fun CardTitle(text: String, modifier: Modifier = Modifier, testTag: String? = null) {
    Text(
        text = text,
        modifier = modifier.semantics { heading() }.then(if (testTag == null) Modifier else Modifier.testTag(testTag)),
        style = MaterialTheme.typography.titleMedium,
    )
}

/** Shared card shell for rows and mixed content that supply their own padding. */
@Composable
fun AppCardSurface(
    modifier: Modifier,
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val shape = RoundedCornerShape(12.dp)
    val colors = CardDefaults.outlinedCardColors(containerColor = MaterialTheme.colorScheme.surface)
    val border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    if (onClick == null) {
        OutlinedCard(modifier = modifier, shape = shape, colors = colors, border = border, content = content)
    } else {
        OutlinedCard(
            onClick = onClick,
            modifier = modifier.heightIn(min = 48.dp),
            shape = shape,
            colors = colors,
            border = border,
            content = content,
        )
    }
}
