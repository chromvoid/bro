package assistant.mobile.android

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import assistant.mobile.core.AuthLink
import assistant.mobile.core.GeneratedStrings
import assistant.mobile.core.LocalizedString
import assistant.mobile.core.NativeAuthDestination
import assistant.mobile.core.NativeServerCheckResult
import assistant.mobile.core.NativeServerSelectionController
import assistant.mobile.core.NativeServerSwitchResult
import assistant.mobile.core.parseNativeAuthInput
import assistant.mobile.core.parseNativeInviteCode
import assistant.mobile.design.ui.AppCard
import assistant.mobile.design.ui.CardTitle

internal enum class NativeServerEntry { Invite, Custom }

@Composable
internal fun SelectedServerRow(name: String, host: String, onChange: () -> Unit, enabled: Boolean = true) {
    Row(Modifier.fillMaxWidth().testTag("selected_server"), verticalAlignment = Alignment.CenterVertically) {
        Text(localized(GeneratedStrings.NativeServer.selected(name, host)), modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyMedium)
        TextButton(onClick = onChange, enabled = enabled,
            modifier = Modifier.heightIn(min = 48.dp).testTag("server_change")) {
            Text(localized(GeneratedStrings.NativeServer.change))
        }
    }
}

@Composable
internal fun ServerSettingsSection(name: String, origin: String, onChange: () -> Unit) {
    AppCard(modifier = Modifier.testTag("settings_server")) {
        CardTitle(localized(GeneratedStrings.NativeServer.title))
        Text(name, modifier = Modifier.testTag("settings_server_name"))
        Text(origin, modifier = Modifier.testTag("settings_server_origin"), style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        OutlinedButton(onClick = onChange,
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("settings_change_server")) {
            Text(localized(GeneratedStrings.NativeServer.switchAction))
        }
    }
}

@Composable
internal fun ServerSelectionScreen(
    entry: NativeServerEntry,
    currentName: String,
    currentOrigin: String,
    defaultOrigin: String,
    hasSession: Boolean,
    hasCurrentData: Boolean,
    check: suspend (String) -> NativeServerCheckResult,
    switchServer: suspend (NativeServerCheckResult.Compatible, AuthLink?) -> NativeServerSwitchResult,
    onRedeem: (AuthLink) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    destination: NativeAuthDestination? = null,
) {
    val scope = rememberCoroutineScope()
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val latestCheck by rememberUpdatedState(check)
    val latestSwitch by rememberUpdatedState(switchServer)
    val latestRedeem by rememberUpdatedState(onRedeem)
    val latestClose by rememberUpdatedState(onClose)
    val controller = remember(scope, currentOrigin) {
        NativeServerSelectionController(scope, currentOrigin,
            check = { latestCheck(it) }, switchServer = { target, invite -> latestSwitch(target, invite) },
            onRedeem = { latestRedeem(it) }, onClose = { latestClose() })
    }
    val state by controller.state.collectAsStateWithLifecycle()
    // Invitation material stays in this composition's memory and has no Saver or navigation key.
    var address by remember { mutableStateOf("") }
    var invitation by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<LocalizedString?>(null) }
    val busy = state.busy

    LaunchedEffect(controller, destination) {
        error = null
        destination?.let(controller::accept)
    }
    DisposableEffect(controller) {
        onDispose { controller.close() }
    }
    val back = {
        val form = controller.state.value
        if (!form.busy) {
            if (form.selected != null && destination == null) {
                controller.clear()
                error = null
            } else onClose()
        }
    }
    BackHandler { back() }
    val inviteEntry = entry == NativeServerEntry.Invite
    ScreenColumn(if (inviteEntry) "screen_ServerInvite" else "screen_OwnServer", modifier,
        title = localized(if (inviteEntry) GeneratedStrings.NativeServer.inviteTitle else GeneratedStrings.NativeServer.ownTitle),
        onBack = back, includeSharedActions = false) {
        val checked = state.selected
        if (checked != null) {
            AppCard(modifier = Modifier.testTag("server_confirmation")) {
                CardTitle(checked.target.name)
                Text(checked.target.origin, modifier = Modifier.testTag("server_confirmation_origin"))
                Button(onClick = { controller.requestConnect(hasSession, hasCurrentData) }, enabled = !busy,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("server_connect")) {
                    Text(localized(GeneratedStrings.NativeServer.connect))
                }
            }
        } else if (inviteEntry) {
            OutlinedTextField(value = invitation, onValueChange = { invitation = it; error = null; controller.clearError() }, enabled = !busy,
                singleLine = true, label = { Text(localized(GeneratedStrings.NativeServer.inviteInput)) },
                keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, imeAction = ImeAction.Done),
                modifier = Modifier.fillMaxWidth().testTag("server_invite_input"))
            Button(onClick = {
                val value = parseNativeAuthInput(invitation, currentOrigin, defaultOrigin)
                if (value == null) error = GeneratedStrings.Errors.invalid
                else {
                    invitation = ""
                    keyboard?.hide()
                    focus.clearFocus()
                    error = null
                    controller.accept(value)
                }
            }, enabled = !busy && invitation.isNotBlank(),
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("server_invite_continue")) {
                Text(localized(GeneratedStrings.Common.`continue`))
            }
        } else {
            OutlinedTextField(value = address, onValueChange = { address = it; error = null; controller.clearError() }, enabled = !busy,
                singleLine = true, label = { Text(localized(GeneratedStrings.NativeServer.address)) },
                placeholder = { Text(localized(GeneratedStrings.NativeServer.addressPlaceholder)) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, autoCorrectEnabled = false, imeAction = ImeAction.Next),
                modifier = Modifier.fillMaxWidth().testTag("server_address"))
            OutlinedTextField(value = invitation, onValueChange = { invitation = it; error = null; controller.clearError() }, enabled = !busy,
                singleLine = true, label = { Text(localized(GeneratedStrings.NativeServer.inviteCode)) },
                keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, imeAction = ImeAction.Done),
                modifier = Modifier.fillMaxWidth().testTag("server_invite_code"))
            Button(onClick = {
                val invite = invitation.takeIf { it.isNotBlank() }?.let(::parseNativeInviteCode)
                if (invitation.isNotBlank() && invite == null) error = GeneratedStrings.Errors.invalid
                else {
                    invitation = ""
                    keyboard?.hide()
                    focus.clearFocus()
                    error = null
                    controller.probe(address.trim(), invite)
                }
            }, enabled = !busy && address.isNotBlank(),
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("server_check")) {
                Text(localized(GeneratedStrings.NativeServer.check))
            }
            if (currentOrigin != defaultOrigin) {
                TextButton(onClick = {
                    invitation = ""
                    keyboard?.hide()
                    focus.clearFocus()
                    error = null
                    controller.probe(defaultOrigin)
                }, enabled = !busy, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("server_return_default")) {
                    Text(localized(GeneratedStrings.NativeServer.returnDefault))
                }
            }
        }
        (error ?: state.error)?.let { ErrorMessage(it, "server_error") }
    }
    if (state.confirmation != null) AlertDialog(
        onDismissRequest = controller::dismissConfirmation,
        title = { Text(localized(GeneratedStrings.NativeServer.switchTitle)) },
        text = { Text(localized(GeneratedStrings.NativeServer.switchMessage(currentName))) },
        confirmButton = {
            TextButton(onClick = controller::confirmSwitch, modifier = Modifier.testTag("server_switch_confirm")) {
                Text(localized(GeneratedStrings.NativeServer.switchAction))
            }
        },
        dismissButton = {
            TextButton(onClick = controller::dismissConfirmation) { Text(localized(GeneratedStrings.Common.cancel)) }
        },
    )
}
