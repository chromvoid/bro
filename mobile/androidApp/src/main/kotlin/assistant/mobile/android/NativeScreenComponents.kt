package assistant.mobile.android

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import assistant.mobile.core.LocalizedString
import assistant.mobile.design.localizedString
import assistant.mobile.design.R as DesignR
import kotlinx.coroutines.delay

@Composable
internal fun localized(message: LocalizedString): String = LocalContext.current.localizedString(message)

internal data class NativeChrome(
    val onBack: (() -> Unit)? = null,
    val actions: @Composable RowScope.() -> Unit = {},
)

internal val LocalNativeChrome = staticCompositionLocalOf { NativeChrome() }

@Composable
internal fun NativeToolbarIconButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    IconButton(onClick = onClick, modifier = modifier.size(48.dp), content = content)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun NativeTopBar(
    title: String,
    onBack: (() -> Unit)? = LocalNativeChrome.current.onBack,
    includeSharedActions: Boolean = true,
    actions: @Composable RowScope.() -> Unit = {},
) {
    val chrome = LocalNativeChrome.current
    TopAppBar(
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = MaterialTheme.colorScheme.background,
            scrolledContainerColor = MaterialTheme.colorScheme.background,
        ),
        title = {
            Text(
                title,
                modifier = Modifier.semantics { heading() }.testTag("screen_title"),
                style = MaterialTheme.typography.titleLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        navigationIcon = {
            onBack?.let { back ->
                NativeToolbarIconButton(onClick = back, modifier = Modifier.testTag("back")) {
                    Icon(
                        painterResource(DesignR.drawable.lucide_arrow_left),
                        contentDescription = stringResource(DesignR.string.common_back),
                    )
                }
            }
        },
        actions = {
            actions()
            if (onBack == null && includeSharedActions) chrome.actions(this)
        },
    )
}

@Composable
internal fun NativeTopBar(
    title: Int,
    onBack: (() -> Unit)? = LocalNativeChrome.current.onBack,
    includeSharedActions: Boolean = true,
    actions: @Composable RowScope.() -> Unit = {},
) = NativeTopBar(stringResource(title), onBack, includeSharedActions, actions)

@Composable
internal fun ScreenColumn(
    tag: String,
    modifier: Modifier = Modifier,
    title: String? = null,
    onBack: (() -> Unit)? = LocalNativeChrome.current.onBack,
    includeSharedActions: Boolean = true,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = modifier.fillMaxSize().testTag(tag),
    ) {
        if (title != null) NativeTopBar(title, onBack, includeSharedActions)
        Column(
            modifier = Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            content = content,
        )
    }
}

@Composable
internal fun ScreenColumn(
    tag: String,
    modifier: Modifier = Modifier,
    title: Int,
    onBack: (() -> Unit)? = LocalNativeChrome.current.onBack,
    includeSharedActions: Boolean = true,
    content: @Composable ColumnScope.() -> Unit,
) = ScreenColumn(tag, modifier, stringResource(title), onBack, includeSharedActions, content)

/** Refresh gestures and their indicator belong to content below the fixed screen bar. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun RefreshableScreen(
    tag: String,
    title: String,
    isRefreshing: Boolean,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = LocalNativeChrome.current.onBack,
    refreshTag: String? = null,
    includeSharedActions: Boolean = true,
    actions: @Composable RowScope.() -> Unit = {},
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(modifier.fillMaxSize().testTag(tag)) {
        NativeTopBar(title, onBack, includeSharedActions, actions)
        PullToRefreshBox(
            isRefreshing = isRefreshing,
            onRefresh = onRefresh,
            modifier = Modifier.weight(1f).fillMaxWidth().let { refreshModifier ->
                if (refreshTag == null) refreshModifier else refreshModifier.testTag(refreshTag)
            },
        ) {
            Column(
                modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
                content = content,
            )
        }
    }
}

@Composable
internal fun ScreenTitle(title: Int) = ScreenTitle(stringResource(title))

@Composable
internal fun ScreenTitle(title: String) {
    Text(title, modifier = Modifier.semantics { heading() }.testTag("screen_title"),
        style = MaterialTheme.typography.headlineLarge)
}

@Composable
internal fun ErrorMessage(message: LocalizedString, tag: String = "auth_error") {
    Text(localized(message), color = MaterialTheme.colorScheme.error,
        modifier = Modifier.testTag(tag).semantics { liveRegion = LiveRegionMode.Assertive })
}

@Composable
internal fun StatusMessage(message: LocalizedString, tag: String = "status_message") {
    Text(localized(message), modifier = Modifier.testTag(tag).semantics { liveRegion = LiveRegionMode.Polite })
}

@Composable
internal fun UnavailableCard() {
    OutlinedCard(Modifier.fillMaxWidth()) {
        Text(stringResource(DesignR.string.state_no_access_title),
            modifier = Modifier.padding(20.dp).testTag("placeholder_text"),
            style = MaterialTheme.typography.bodyLarge)
    }
}

/** Retry-After blocks the action until the server's absolute deadline has passed. */
@Composable
internal fun retrySeconds(retryAtMillis: Long?): Long {
    var now by remember(retryAtMillis) { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(retryAtMillis) {
        now = System.currentTimeMillis()
        while (retryAtMillis != null && now < retryAtMillis) {
            delay(250)
            now = System.currentTimeMillis()
        }
    }
    return retryAtMillis?.let { ((it - now + 999) / 1_000).coerceAtLeast(0) } ?: 0
}
