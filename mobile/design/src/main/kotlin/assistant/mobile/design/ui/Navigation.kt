package assistant.mobile.design.ui

import androidx.annotation.DrawableRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import assistant.mobile.design.R as DesignR

/** A destination and its optional icon, description and test tag. */
data class NavigationItem(
    @DrawableRes val icon: Int?,
    val title: String,
    val supporting: String? = null,
    val testTag: String? = null,
    val onClick: () -> Unit,
)

/** Full-width destination row with an optional decorative icon, description and chevron. */
@Composable
fun NavigationRow(
    @DrawableRes icon: Int? = null,
    title: String,
    supporting: String? = null,
    onClick: () -> Unit,
    testTag: String? = null,
    supportingColor: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    modifier: Modifier = Modifier,
) {
    ListItem(
        modifier = modifier.fillMaxWidth().heightIn(min = 56.dp)
            .clickable(role = Role.Button, onClick = onClick)
            .then(if (testTag == null) Modifier else Modifier.testTag(testTag)),
        headlineContent = {
            Text(title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
        },
        supportingContent = supporting?.let { text ->
            {
                Text(text, style = MaterialTheme.typography.bodyMedium, color = supportingColor)
            }
        },
        leadingContent = icon?.let { drawable ->
            {
                Icon(
                    painter = painterResource(drawable),
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        trailingContent = {
            Icon(
                painter = painterResource(DesignR.drawable.lucide_chevron_right),
                contentDescription = null,
                modifier = Modifier.size(20.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
    )
}

/** A padding-free card automatically separates its destination rows with dividers. */
@Composable
fun NavigationGroup(
    items: List<NavigationItem>,
    modifier: Modifier = Modifier,
    testTag: String? = null,
) {
    AppCardSurface(
        modifier = modifier.fillMaxWidth().then(if (testTag == null) Modifier else Modifier.testTag(testTag)),
    ) {
        Column(Modifier.fillMaxWidth()) {
            items.forEachIndexed { index, item ->
                if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                NavigationRow(
                    icon = item.icon,
                    title = item.title,
                    supporting = item.supporting,
                    onClick = item.onClick,
                    testTag = item.testTag,
                )
            }
        }
    }
}
