package assistant.mobile.android

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import assistant.mobile.core.NativeIdentity
import assistant.mobile.core.ThemePreference
import assistant.mobile.design.navigationBarContainer
import assistant.mobile.design.ui.QuickActionRow
import assistant.mobile.design.ui.QuickActionTile
import assistant.mobile.design.ui.SegmentedChoice
import assistant.mobile.design.ui.Spacing
import assistant.mobile.design.R as DesignR

internal data class NativeNavigationItem(val screen: FamilyScreen, val icon: Int, val scope: String? = null)

internal val primaryNavigation = listOf(
    NativeNavigationItem(FamilyScreen.Today, DesignR.drawable.lucide_house),
    NativeNavigationItem(FamilyScreen.Food, DesignR.drawable.lucide_utensils, "nutrition.read"),
    NativeNavigationItem(FamilyScreen.Tasks, DesignR.drawable.lucide_list_todo, "tasks.read"),
    NativeNavigationItem(FamilyScreen.Assistant, DesignR.drawable.lucide_message_circle),
)

internal val secondaryNavigation = listOf(
    NativeNavigationItem(FamilyScreen.Timers, DesignR.drawable.lucide_timer, "timers.read"),
    NativeNavigationItem(FamilyScreen.Recordings, DesignR.drawable.lucide_audio_lines, "session.control"),
    NativeNavigationItem(FamilyScreen.Circle, DesignR.drawable.lucide_users),
    NativeNavigationItem(FamilyScreen.Activity, DesignR.drawable.lucide_dumbbell, "activity.read"),
    NativeNavigationItem(FamilyScreen.Personal, DesignR.drawable.lucide_user, "personal.read"),
    NativeNavigationItem(FamilyScreen.Places, DesignR.drawable.lucide_route, "places.read"),
    NativeNavigationItem(FamilyScreen.Settings, DesignR.drawable.lucide_settings),
)

/** The current entry determines selection, including addressable children of each tab. */
internal fun Destination.primarySection(): FamilyScreen? = when (this) {
    is Destination.Section -> when (screen) {
        FamilyScreen.Today -> FamilyScreen.Today
        FamilyScreen.Food, FamilyScreen.FoodStats -> FamilyScreen.Food
        FamilyScreen.Tasks -> FamilyScreen.Tasks
        FamilyScreen.Assistant -> FamilyScreen.Assistant
        else -> null
    }
    is Destination.FoodDay -> FamilyScreen.Food
    is Destination.TaskList -> FamilyScreen.Tasks
    is Destination.TaskNew -> FamilyScreen.Tasks
    is Destination.AssistantEntry -> FamilyScreen.Assistant
    is Destination.Detail -> when (screen) {
        FamilyDetail.Task, FamilyDetail.SharedList, FamilyDetail.ListAccess -> FamilyScreen.Tasks
        FamilyDetail.AssistantMessage -> FamilyScreen.Assistant
        else -> null
    }
    else -> null
}

internal fun Destination.isTopLevel(): Boolean = when (this) {
    is Destination.Section -> primaryNavigation.any { it.screen == screen } || secondaryNavigation.any { it.screen == screen }
    is Destination.Plugin -> coin == null && screen in pluginTabScreens
    is Destination.Detail -> screen == FamilyDetail.PluginScreen && (parentId ?: "home") in pluginTabScreens
    else -> false
}

internal fun Destination.pluginInstallation(): String? = when (this) {
    is Destination.Plugin -> installation
    is Destination.Detail -> id.takeIf { screen == FamilyDetail.PluginScreen }
    else -> null
}

internal val pluginTabScreens = setOf("home", "portfolio", "orders", "trades", "pnl", "cashflow", "settings", "sync", "connect")

@Composable
internal fun NativeTabBar(
    scopes: List<String>,
    selected: FamilyScreen?,
    onOpen: (FamilyScreen) -> Unit,
    onVoice: () -> Unit,
) {
    val tabs = primaryNavigation.filter { it.scope == null || it.scope in scopes }
    val voiceIndex = (tabs.size + 1) / 2
    Column {
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        NavigationBar(
            modifier = Modifier.testTag("native_tab_bar"),
            containerColor = MaterialTheme.colorScheme.navigationBarContainer,
            tonalElevation = 0.dp,
        ) {
            for (index in 0..tabs.size) {
                if (index == voiceIndex) {
                    // Material 3's navigation cells are 80 dp; the action stays centred in its own cell.
                    Box(Modifier.weight(1f).height(80.dp), contentAlignment = Alignment.Center) {
                        FilledIconButton(onClick = onVoice, shape = CircleShape,
                            colors = IconButtonDefaults.filledIconButtonColors(
                                containerColor = MaterialTheme.colorScheme.primary,
                                contentColor = MaterialTheme.colorScheme.onPrimary,
                            ),
                            modifier = Modifier.size(48.dp).testTag("voice_tab")) {
                            Icon(painterResource(DesignR.drawable.lucide_mic), stringResource(DesignR.string.dictation_start))
                        }
                    }
                }
                if (index < tabs.size) {
                    val tab = tabs[index]
                    NavigationBarItem(
                        selected = selected == tab.screen,
                        onClick = { onOpen(tab.screen) },
                        icon = { Icon(painterResource(tab.icon), contentDescription = null) },
                        label = { Text(stringResource(tab.screen.title), maxLines = 1) },
                        modifier = Modifier.testTag("tab_${tab.screen.name}"),
                    )
                }
            }
        }
    }
}

@Composable
internal fun RowScope.NativeShellActions(
    identity: NativeIdentity?,
    unread: Int,
    onDevices: () -> Unit,
    onNotifications: () -> Unit,
    onProfile: () -> Unit,
) {
    NativeToolbarIconButton(onClick = onDevices, modifier = Modifier.testTag("open_devices_sheet")) {
        Icon(painterResource(DesignR.drawable.lucide_monitor_smartphone), stringResource(DesignR.string.devices_status_title))
    }
    NotificationBell(unread, onNotifications)
    val description = stringResource(DesignR.string.profile_open)
    NativeToolbarIconButton(onClick = onProfile, modifier = Modifier.testTag("profile_menu").semantics { contentDescription = description }) {
        Surface(shape = CircleShape, color = MaterialTheme.colorScheme.surfaceVariant,
            contentColor = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(32.dp).clearAndSetSemantics {}) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                Text(profileInitials(identity), style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}

private fun profileInitials(identity: NativeIdentity?): String =
    (identity?.name?.takeIf { it.isNotBlank() } ?: identity?.login.orEmpty())
        .split(' ').filter { it.isNotBlank() }.take(2).joinToString("") { it.take(1).uppercase() }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun NativeProfileSheet(
    identity: NativeIdentity,
    plugins: NativePluginsController,
    pluginState: NativePluginsState,
    theme: ThemePreference,
    onThemeChanged: (ThemePreference) -> Unit,
    onDismiss: () -> Unit,
    onOpen: (FamilyScreen) -> Unit,
    onPlugin: (String) -> Unit,
    onLogout: () -> Unit,
) {
    LaunchedEffect(plugins) { plugins.refresh() }
    ModalBottomSheet(onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        modifier = Modifier.testTag("profile_sheet")) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(Spacing.l),
            verticalArrangement = Arrangement.spacedBy(Spacing.l)) {
            Column {
                Text(identity.name, style = MaterialTheme.typography.titleLarge)
                Text("@${identity.login}", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            val tiles = secondaryNavigation.filter { it.scope == null || it.scope in identity.scopes }
                .map { ProfileTile(stringResource(it.screen.title), it.icon, "open_${it.screen.name}") { onOpen(it.screen) } } +
                pluginState.installations.map { installation ->
                    ProfileTile(installation.title, DesignR.drawable.lucide_blocks, "plugin_open_${installation.installationId}") {
                        onPlugin(installation.installationId)
                    }
                }
            val sectionsDescription = stringResource(DesignR.string.profile_sections)
            QuickActionRow(columns = 3, modifier = Modifier.semantics { contentDescription = sectionsDescription }) {
                tiles.forEach { tile ->
                    QuickActionTile(tile.icon, tile.title, onClick = { onDismiss(); tile.open() }, testTag = tile.tag)
                }
            }
            if (pluginState.loading) StatusMessage(assistant.mobile.core.GeneratedStrings.State.loading)
            if (pluginState.error != null) {
                ErrorMessage(assistant.mobile.core.GeneratedStrings.State.errorTitle, "plugins_error")
                FilledTonalButton(onClick = plugins::reload, modifier = Modifier.testTag("plugins_retry")) {
                    Text(stringResource(DesignR.string.common_retry))
                }
            }
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.s)) {
                Text(stringResource(DesignR.string.theme_title), style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                val themes = listOf(ThemePreference.System, ThemePreference.Light, ThemePreference.Dark)
                val themeLabels = mapOf(
                    ThemePreference.System to stringResource(DesignR.string.theme_system),
                    ThemePreference.Light to stringResource(DesignR.string.theme_light),
                    ThemePreference.Dark to stringResource(DesignR.string.theme_dark),
                )
                SegmentedChoice(
                    options = themes,
                    selected = theme,
                    onSelect = onThemeChanged,
                    label = { themeLabels.getValue(it) },
                    icon = {
                        when (it) {
                            ThemePreference.System -> DesignR.drawable.lucide_monitor
                            ThemePreference.Light -> DesignR.drawable.lucide_sun
                            ThemePreference.Dark -> DesignR.drawable.lucide_moon
                        }
                    },
                    testTag = "profile_theme",
                    optionTestTag = { "profile_theme_${it.name.lowercase()}" },
                )
            }
            OutlinedButton(onClick = { onDismiss(); onLogout() }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("profile_logout")) {
                Icon(painterResource(DesignR.drawable.lucide_log_out), contentDescription = null, tint = MaterialTheme.colorScheme.error)
                Text(stringResource(DesignR.string.profile_logout), color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(start = Spacing.s))
            }
        }
    }
}

private data class ProfileTile(val title: String, val icon: Int, val tag: String, val open: () -> Unit)
