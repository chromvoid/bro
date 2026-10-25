package assistant.mobile.android

import android.net.Uri
import androidx.browser.customtabs.CustomTabsIntent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import assistant.mobile.core.AuthController
import assistant.mobile.core.GeneratedStrings
import assistant.mobile.core.LocalizedString
import assistant.mobile.core.NativeUpdateController
import assistant.mobile.core.NativeUpdateError
import assistant.mobile.core.NativeUpdatePhase
import assistant.mobile.core.SessionManager
import assistant.mobile.core.SettingsController
import assistant.mobile.design.R as DesignR
import assistant.mobile.design.ui.AppCard
import assistant.mobile.design.ui.CardTitle
import assistant.mobile.design.ui.NavigationGroup
import assistant.mobile.design.ui.NavigationItem
import assistant.mobile.design.ui.NavigationRow
import assistant.mobile.design.ui.ProgressBar
import assistant.mobile.design.ui.Spacing
import kotlinx.coroutines.launch

@Composable
internal fun SettingsScreen(
    settings: SettingsController,
    sessions: SessionManager,
    auth: AuthController,
    onNavigate: (FamilyScreen) -> Unit,
    onOpenManagement: () -> Unit,
    appVersion: String,
    updates: NativeUpdateController,
    onInstallPermission: () -> Unit,
    serverName: String,
    serverOrigin: String,
    onChangeServer: () -> Unit,
) {
    val context = LocalContext.current
    val state by settings.state.collectAsStateWithLifecycle()
    val identity by sessions.identity.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var loggingOut by remember { mutableStateOf(false) }
    val wait = retrySeconds(state?.retryAtMillis)
    val form = state?.takeIf { it.identity.id == identity?.id }
    LaunchedEffect(settings) { settings.refresh() }
    Column(Modifier.fillMaxSize().testTag("screen_Settings")) {
        NativeTopBar(localized(GeneratedStrings.Settings.title))
        Column(
            Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(Spacing.l),
            verticalArrangement = Arrangement.spacedBy(Spacing.l),
        ) {
            if (form == null) StatusMessage(GeneratedStrings.State.loading)
            else {
                AppCard {
                    CardTitle(localized(GeneratedStrings.Settings.account))
                    AuthTextField(form.name, settings::updateName, GeneratedStrings.Settings.name, "settings_name", !form.busy)
                    AuthTextField(form.locale, settings::updateLocale, GeneratedStrings.Settings.locale, "settings_locale", !form.busy)
                    AuthTextField(form.timezone, settings::updateTimezone, GeneratedStrings.Settings.timezone, "settings_timezone", !form.busy)
                    Text(localized(GeneratedStrings.Settings.timezoneHint), style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    form.error?.let { ErrorMessage(it, "settings_error") }
                    if (form.saved) StatusMessage(GeneratedStrings.Settings.saved, "settings_saved")
                    if (wait > 0) StatusMessage(GeneratedStrings.Auth.pauseText(wait.toString()), "settings_retry")
                    Button(onClick = { scope.launch { settings.save() } }, enabled = !form.busy && wait == 0L,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("settings_save")) {
                        Text(localized(GeneratedStrings.Common.save))
                    }
                }
            }
            ServerSettingsSection(serverName, serverOrigin, onChangeServer)
            val personalItems = if (identity?.scopes?.contains("personal.read") == true) listOf(
                NavigationItem(DesignR.drawable.lucide_user_round, localized(GeneratedStrings.Personal.bodyProfileTitle),
                    localized(GeneratedStrings.Personal.profileSummary), "open_Profile") { onNavigate(FamilyScreen.Profile) },
                NavigationItem(DesignR.drawable.lucide_plug, localized(GeneratedStrings.Sources.title),
                    localized(GeneratedStrings.Sources.settingsText), "open_Sources") { onNavigate(FamilyScreen.Sources) },
            ) else emptyList()
            NavigationGroup(items = personalItems + listOf(
                NavigationItem(DesignR.drawable.lucide_mic, localized(GeneratedStrings.SpeechSettings.title),
                    localized(GeneratedStrings.SpeechSettings.linkText), "open_SpeechSettings") { onNavigate(FamilyScreen.SpeechSettings) },
                NavigationItem(DesignR.drawable.lucide_share_2, localized(GeneratedStrings.Settings.sharing),
                    localized(GeneratedStrings.Settings.sharingText), "open_SharingSettings") { onNavigate(FamilyScreen.SharingSettings) },
                NavigationItem(DesignR.drawable.lucide_monitor_smartphone, localized(GeneratedStrings.Settings.devices),
                    localized(GeneratedStrings.Settings.devicesText), "open_Devices") { onNavigate(FamilyScreen.Devices) },
                NavigationItem(DesignR.drawable.lucide_bell_ring, localized(GeneratedStrings.Settings.notifications),
                    localized(GeneratedStrings.Settings.notificationsText), "open_NotificationSettings") { onNavigate(FamilyScreen.NotificationSettings) },
                NavigationItem(DesignR.drawable.lucide_map_pin, localized(GeneratedStrings.Settings.location),
                    localized(GeneratedStrings.Settings.locationText), "open_LocationSettings") { onNavigate(FamilyScreen.LocationSettings) },
                NavigationItem(DesignR.drawable.lucide_layout_grid, localized(GeneratedStrings.Settings.capabilities),
                    localized(GeneratedStrings.Settings.capabilitiesText), "open_Capabilities") { onNavigate(FamilyScreen.Capabilities) },
            ))
            if (form != null) {
                AppCard {
                    CardTitle(localized(GeneratedStrings.Settings.limits))
                    if (form.loadingLimits) StatusMessage(GeneratedStrings.State.loading, "limits_loading")
                    form.limitsError?.let { error ->
                        ErrorMessage(error, "limits_error")
                        OutlinedButton(onClick = { scope.launch { settings.refreshLimits() } }, enabled = wait == 0L) {
                            Text(localized(GeneratedStrings.Common.retry))
                        }
                    }
                    form.limits.forEach { limit ->
                        Column(Modifier.fillMaxWidth().testTag("limit_${limit.kind}"),
                            verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                            Text(localized(limit.label), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                            Text(localized(limit.usage), style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                            limit.sharePercent?.let { percent ->
                                ProgressBar(progress = { percent / 100f }, modifier = Modifier.fillMaxWidth(),
                                    contentDescription = localized(limit.label))
                            }
                            if (limit.exhausted) Text(localized(GeneratedStrings.Settings.usedUp),
                                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            }
            if (identity?.systemAdmin == true) {
                NavigationRow(icon = DesignR.drawable.lucide_external_link,
                    title = localized(GeneratedStrings.NativeAccount.openManagement),
                    onClick = onOpenManagement, testTag = "settings_management")
            }
            Text(localized(GeneratedStrings.NativeAccount.version(appVersion)), modifier = Modifier.testTag("settings_version"),
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (!BuildConfig.PLAY_STORE) UpdateSettings(updates, onInstallPermission)
            OutlinedButton(
                onClick = {
                    loggingOut = true
                    scope.launch { auth.signOut() }
                },
                enabled = !loggingOut,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("settings_logout"),
            ) { Text(localized(GeneratedStrings.Settings.signOut)) }
            Text(localized(GeneratedStrings.Settings.signOutHint), style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            NavigationRow(title = localized(GeneratedStrings.AccountDeletion.title),
                onClick = { onNavigate(FamilyScreen.AccountDeletion) }, testTag = "settings_deletion")
            NavigationRow(title = localized(GeneratedStrings.Installation.privacy),
                onClick = { CustomTabsIntent.Builder().build().launchUrl(context,
                    Uri.parse("https://bro.chromvoid.com/privacy")) }, testTag = "settings_privacy")
        }
    }
}

@Composable
private fun UpdateSettings(updates: NativeUpdateController, onInstallPermission: () -> Unit) {
    val state by updates.state.collectAsStateWithLifecycle()
    val message = when (state.phase) {
        NativeUpdatePhase.Idle -> null
        NativeUpdatePhase.Checking -> GeneratedStrings.AppUpdate.checking
        NativeUpdatePhase.UpToDate -> GeneratedStrings.AppUpdate.upToDate
        NativeUpdatePhase.Available -> state.offer?.let { GeneratedStrings.AppUpdate.available(it.versionName) }
        NativeUpdatePhase.Downloading -> GeneratedStrings.AppUpdate.downloading((state.progress * 100).toInt().toString())
        NativeUpdatePhase.Verifying -> GeneratedStrings.AppUpdate.verifying
        NativeUpdatePhase.PermissionRequired -> GeneratedStrings.AppUpdate.Errors.blocked
        NativeUpdatePhase.Installing -> GeneratedStrings.AppUpdate.installing
        NativeUpdatePhase.Confirmation -> GeneratedStrings.AppUpdate.confirm
        NativeUpdatePhase.Installed -> GeneratedStrings.AppUpdate.installed
        NativeUpdatePhase.Failed -> state.error?.message() ?: GeneratedStrings.AppUpdate.Errors.other
    }
    message?.let {
        Text(localized(it), style = MaterialTheme.typography.bodyMedium,
            color = if (state.phase == NativeUpdatePhase.Failed) MaterialTheme.colorScheme.error
            else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.testTag("settings_update_status").semantics { liveRegion = LiveRegionMode.Polite })
    }
    if (state.phase == NativeUpdatePhase.Downloading) {
        ProgressBar(progress = { state.progress },
            modifier = Modifier.fillMaxWidth().testTag("settings_update_progress"))
    }
    if (state.phase == NativeUpdatePhase.Available) {
        FilledTonalButton(onClick = updates::downloadAndInstall,
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("settings_update_install")) {
            Text(localized(GeneratedStrings.AppUpdate.install))
        }
    }
    if (state.phase == NativeUpdatePhase.PermissionRequired) {
        FilledTonalButton(onClick = onInstallPermission,
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("settings_update_permission")) {
            Text(localized(GeneratedStrings.AppUpdate.permission))
        }
    }
    val canCheck = state.phase in setOf(NativeUpdatePhase.Idle, NativeUpdatePhase.UpToDate,
        NativeUpdatePhase.Available, NativeUpdatePhase.Installed, NativeUpdatePhase.Failed)
    OutlinedButton(onClick = updates::check, enabled = canCheck,
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("settings_update_check")) {
        Text(localized(GeneratedStrings.AppUpdate.check))
    }
}

private fun NativeUpdateError.message(): LocalizedString = when (this) {
    NativeUpdateError.WrongPackage -> GeneratedStrings.AppUpdate.Errors.wrong_package
    NativeUpdateError.Downgrade -> GeneratedStrings.AppUpdate.Errors.downgrade
    NativeUpdateError.Certificate, NativeUpdateError.Incompatible -> GeneratedStrings.AppUpdate.Errors.incompatible
    NativeUpdateError.Integrity -> GeneratedStrings.AppUpdate.Errors.integrity
    NativeUpdateError.InvalidManifest -> GeneratedStrings.AppUpdate.Errors.manifest
    NativeUpdateError.Unauthorized -> GeneratedStrings.AppUpdate.Errors.session
    NativeUpdateError.Forbidden -> GeneratedStrings.Errors.forbidden
    NativeUpdateError.RateLimited -> GeneratedStrings.Errors.limit
    NativeUpdateError.PermissionRequired, NativeUpdateError.Blocked -> GeneratedStrings.AppUpdate.Errors.blocked
    NativeUpdateError.Cancelled -> GeneratedStrings.AppUpdate.Errors.cancelled
    NativeUpdateError.Storage -> GeneratedStrings.AppUpdate.Errors.storage
    NativeUpdateError.Network -> GeneratedStrings.AppUpdate.Errors.download_failed
    NativeUpdateError.InstallFailed -> GeneratedStrings.AppUpdate.Errors.other
}

@Composable
private fun SectionHeading(title: String) {
    Text(title, modifier = Modifier.semantics { heading() }, style = MaterialTheme.typography.titleMedium)
}

@Composable
internal fun CapabilitiesScreen(settings: SettingsController, sessions: SessionManager) {
    val state by settings.state.collectAsStateWithLifecycle()
    val identity by sessions.identity.collectAsStateWithLifecycle()
    ScreenColumn("screen_Capabilities", title = localized(GeneratedStrings.Capabilities.title)) {
        SectionHeading(localized(GeneratedStrings.Capabilities.yours))
        state?.takeIf { it.identity.id == identity?.id }?.capabilities?.forEach { capability ->
            OutlinedCard(Modifier.fillMaxWidth().testTag("capability_${capability.key}")) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(localized(capability.title), style = MaterialTheme.typography.titleMedium)
                    Text(localized(capability.description))
                    Text(localized(if (capability.available) GeneratedStrings.Capabilities.available else GeneratedStrings.Capabilities.notAvailable))
                }
            }
        }
        SectionHeading(localized(GeneratedStrings.Capabilities.managed))
        Text(localized(GeneratedStrings.Capabilities.managedText))
    }
}
