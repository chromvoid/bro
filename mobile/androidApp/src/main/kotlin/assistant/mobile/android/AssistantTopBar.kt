package assistant.mobile.android

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import assistant.mobile.core.GeneratedStrings
import assistant.mobile.core.assistant.AssistantActivityState
import assistant.mobile.core.assistant.assistantPhaseLabel
import assistant.mobile.design.ui.ActionIconButton
import assistant.mobile.design.ui.generatedColor
import assistant.mobile.design.R as DesignR

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AssistantTopBar(
    activity: AssistantActivityState?,
    responding: Boolean,
    onSearch: () -> Unit,
) {
    val onBack = LocalNativeChrome.current.onBack
    TopAppBar(
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = MaterialTheme.colorScheme.background,
            scrolledContainerColor = MaterialTheme.colorScheme.background,
        ),
        navigationIcon = {
            onBack?.let { back ->
                ActionIconButton(
                    DesignR.drawable.lucide_arrow_left,
                    stringResource(DesignR.string.common_back),
                    back,
                    testTag = "back",
                )
            }
        },
        title = {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Surface(shape = CircleShape, color = generatedColor("icon-background"), modifier = Modifier.size(40.dp)) {
                    Image(painterResource(R.drawable.ic_bro_foreground), contentDescription = null)
                }
                Column {
                    Text(
                        stringResource(DesignR.string.nav_assistant),
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.semantics { heading() }.testTag("screen_title"),
                    )
                    if (responding) {
                        Text(
                            localized(activity?.let(::assistantPhaseLabel) ?: GeneratedStrings.Assistant.processing),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.testTag("assistant_header_phase").semantics { liveRegion = LiveRegionMode.Polite },
                        )
                    }
                }
            }
        },
        actions = {
            ActionIconButton(
                DesignR.drawable.lucide_search,
                localized(GeneratedStrings.Assistant.search),
                onSearch,
                testTag = "assistant_search_open",
            )
        },
    )
}
