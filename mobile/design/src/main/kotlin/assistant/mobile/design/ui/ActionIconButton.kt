package assistant.mobile.design.ui

import androidx.annotation.DrawableRes
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp

/** Accessible icon action keeps a 48 dp touch target around a 24 dp Lucide icon. */
@Composable
fun ActionIconButton(
    @DrawableRes icon: Int,
    contentDescription: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
    testTag: String? = null,
    modifier: Modifier = Modifier,
) {
    IconButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.size(48.dp).then(if (testTag == null) Modifier else Modifier.testTag(testTag)),
    ) {
        Icon(painterResource(icon), contentDescription = contentDescription, modifier = Modifier.size(24.dp))
    }
}
