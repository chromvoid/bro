package assistant.mobile.android

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import assistant.mobile.core.AuthController
import assistant.mobile.core.AuthField
import assistant.mobile.core.AuthLinkKind
import assistant.mobile.core.AuthMode
import assistant.mobile.core.GeneratedStrings
import assistant.mobile.core.LocalizedString
import assistant.mobile.core.SessionManager
import kotlinx.coroutines.launch

@Composable
internal fun AuthenticationScreen(
    auth: AuthController,
    modifier: Modifier = Modifier,
    pendingDeletion: Boolean,
    onOpenDeletion: () -> Unit,
    serverName: String,
    serverHost: String,
    onChangeServer: () -> Unit,
    onInvite: () -> Unit,
    onOwnServer: () -> Unit,
) {
    val state by auth.state.collectAsStateWithLifecycle()
    BackHandler(enabled = state.mode != AuthMode.SignIn) {
        if (state.mode != AuthMode.Redeem || !state.busy) auth.showSignIn()
    }
    val wait = retrySeconds(state.retryAtMillis)
    val scope = rememberCoroutineScope()
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    // Secrets deliberately have no Saver and never enter the Activity's saved state.
    var password by remember(state.mode, state.linkKind) { mutableStateOf("") }
    var repeat by remember(state.mode, state.linkKind) { mutableStateOf("") }
    val invite = state.linkKind == AuthLinkKind.Invite
    val submit = {
        val secret = password
        val confirmation = repeat
        password = ""
        repeat = ""
        keyboard?.hide()
        focus.clearFocus()
        scope.launch {
            if (state.mode == AuthMode.Redeem) auth.redeem(secret, confirmation) else auth.signIn(secret)
        }
        Unit
    }
    ScreenColumn(if (state.mode == AuthMode.Redeem) "screen_Redeem" else "screen_Login", modifier) {
        if (state.mode == AuthMode.SignIn) SelectedServerRow(serverName, serverHost, onChangeServer, enabled = !state.busy)
        if (pendingDeletion) TextButton(onClick = onOpenDeletion, modifier = Modifier.testTag("login_deletion")) {
            Text(localized(GeneratedStrings.AccountDeletion.check))
        }
        when (state.mode) {
            AuthMode.SignIn -> {
                ScreenTitle(localized(GeneratedStrings.Auth.welcomeBack))
                Text(localized(GeneratedStrings.Auth.signInHint))
                if (state.expired) {
                    Text(localized(GeneratedStrings.Auth.expiredTitle), style = MaterialTheme.typography.titleMedium)
                    StatusMessage(GeneratedStrings.Auth.expiredText, "auth_expired")
                }
                AuthTextField(state.login, auth::updateLogin, GeneratedStrings.Auth.login, "login_login",
                    enabled = !state.busy, error = state.fieldErrors[AuthField.Login])
                PasswordField(password, { password = it }, GeneratedStrings.Auth.password, "login_password",
                    enabled = !state.busy, onDone = { if (!state.busy && wait == 0L && password.isNotEmpty()) submit() })
                state.error?.let { ErrorMessage(it) }
                if (wait > 0) StatusMessage(GeneratedStrings.Auth.pauseText(wait.toString()), "auth_retry")
                Button(
                    onClick = submit,
                    enabled = !state.busy && wait == 0L && state.login.isNotBlank() && password.isNotEmpty(),
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("login_submit"),
                ) { Text(localized(if (state.busy) GeneratedStrings.Auth.signingIn else GeneratedStrings.Auth.signIn)) }
                TextButton(onClick = auth::showRestore, modifier = Modifier.heightIn(min = 48.dp).testTag("login_restore")) {
                    Text(localized(GeneratedStrings.Auth.forgot))
                }
                OutlinedButton(onClick = onInvite, enabled = !state.busy,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("login_invite")) {
                    Text(localized(GeneratedStrings.NativeServer.inviteTitle),
                        modifier = Modifier.fillMaxWidth().wrapContentHeight(), textAlign = TextAlign.Center)
                }
                OutlinedButton(onClick = onOwnServer, enabled = !state.busy,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("login_own_server")) {
                    Text(localized(GeneratedStrings.NativeServer.ownTitle),
                        modifier = Modifier.fillMaxWidth().wrapContentHeight(), textAlign = TextAlign.Center)
                }
            }
            AuthMode.Restore -> {
                ScreenTitle(localized(GeneratedStrings.Auth.restoreTitle))
                Text(localized(GeneratedStrings.Auth.restoreText))
                Text(localized(GeneratedStrings.Auth.restoreLink))
                Button(onClick = auth::showSignIn, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                    Text(localized(GeneratedStrings.Auth.backToSignIn))
                }
            }
            AuthMode.LinkExpired -> {
                ScreenTitle(localized(GeneratedStrings.Auth.linkExpiredTitle))
                StatusMessage(GeneratedStrings.Auth.linkExpiredText)
                Button(onClick = auth::showSignIn, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                    Text(localized(GeneratedStrings.Auth.backToSignIn))
                }
            }
            AuthMode.Redeem -> {
                ScreenTitle(localized(if (invite) GeneratedStrings.Auth.inviteTitle else GeneratedStrings.Auth.resetTitle))
                Text(localized(if (invite) GeneratedStrings.Auth.inviteHint else GeneratedStrings.Auth.resetHint))
                if (invite) {
                    AuthTextField(state.name, auth::updateName, GeneratedStrings.Auth.name, "redeem_name",
                        enabled = !state.busy, error = state.fieldErrors[AuthField.Name])
                    AuthTextField(state.login, auth::updateLogin, GeneratedStrings.Auth.login, "redeem_login",
                        enabled = !state.busy, error = state.fieldErrors[AuthField.Login])
                    Text(localized(GeneratedStrings.Auth.loginRule), style = MaterialTheme.typography.bodySmall)
                }
                PasswordField(password, { password = it }, GeneratedStrings.Auth.newPassword, "redeem_password",
                    enabled = !state.busy, error = state.fieldErrors[AuthField.Password])
                Text(localized(GeneratedStrings.Auth.passwordRule("12")), style = MaterialTheme.typography.bodySmall)
                PasswordField(repeat, { repeat = it }, GeneratedStrings.Auth.repeatPassword, "redeem_repeat",
                    enabled = !state.busy, error = state.fieldErrors[AuthField.Repeat])
                if (invite) {
                    AuthTextField(state.timezone, auth::updateTimezone, GeneratedStrings.Auth.timezone, "redeem_timezone",
                        enabled = !state.busy, error = state.fieldErrors[AuthField.Timezone])
                    Text(localized(GeneratedStrings.Auth.timezoneHint), style = MaterialTheme.typography.bodySmall)
                }
                state.error?.let { ErrorMessage(it) }
                if (wait > 0) StatusMessage(GeneratedStrings.Auth.pauseText(wait.toString()), "auth_retry")
                Button(onClick = submit, enabled = !state.busy && wait == 0L && password.isNotEmpty() && repeat.isNotEmpty(),
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("redeem_submit")) {
                    Text(localized(if (invite) GeneratedStrings.Auth.acceptInvite else GeneratedStrings.Auth.savePassword))
                }
                Text(localized(GeneratedStrings.Auth.oneTimeLink), style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = auth::showSignIn, enabled = !state.busy, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(localized(GeneratedStrings.Auth.backToSignIn))
                }
            }
        }
    }
}

@Composable
internal fun AuthTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: LocalizedString,
    tag: String,
    enabled: Boolean = true,
    error: LocalizedString? = null,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        enabled = enabled,
        singleLine = true,
        isError = error != null,
        label = { Text(localized(label)) },
        supportingText = error?.let { message -> { Text(localized(message)) } },
        keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, imeAction = ImeAction.Next),
        modifier = Modifier.fillMaxWidth().testTag(tag),
    )
}

@Composable
internal fun PasswordField(
    value: String,
    onValueChange: (String) -> Unit,
    label: LocalizedString,
    tag: String,
    enabled: Boolean = true,
    error: LocalizedString? = null,
    onDone: () -> Unit = {},
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        enabled = enabled,
        singleLine = true,
        isError = error != null,
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false, imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { onDone() }),
        label = { Text(localized(label)) },
        supportingText = error?.let { message -> { Text(localized(message)) } },
        modifier = Modifier.fillMaxWidth().testTag(tag),
    )
}

@Composable
internal fun WelcomeScreen(sessions: SessionManager, onNavigate: (FamilyScreen) -> Unit) {
    val identity by sessions.identity.collectAsStateWithLifecycle()
    ScreenColumn("screen_Welcome") {
        ScreenTitle(localized(GeneratedStrings.Welcome.title(identity?.name.orEmpty())))
        Text(localized(GeneratedStrings.Welcome.text))
        val starts = buildList {
            add(Triple(FamilyScreen.Assistant, GeneratedStrings.Welcome.ask, GeneratedStrings.Welcome.askText))
            if (identity?.scopes?.contains("nutrition.write") == true)
                add(Triple(FamilyScreen.Food, GeneratedStrings.Welcome.food, GeneratedStrings.Welcome.foodText))
            if (identity?.scopes?.contains("tasks.write") == true)
                add(Triple(FamilyScreen.Tasks, GeneratedStrings.Welcome.task, GeneratedStrings.Welcome.taskText))
        }
        starts.forEach { (screen, title, description) ->
            Button(onClick = { onNavigate(screen) }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                Column { Text(localized(title)); Text(localized(description), style = MaterialTheme.typography.bodySmall) }
            }
        }
        TextButton(onClick = { onNavigate(FamilyScreen.Today) }, modifier = Modifier.heightIn(min = 48.dp).testTag("welcome_continue")) {
            Text(localized(GeneratedStrings.Welcome.later))
        }
    }
}

@Composable
internal fun NoAccessScreen(onToday: () -> Unit) {
    ScreenColumn("screen_NoAccess") {
        ScreenTitle(localized(GeneratedStrings.State.noAccessTitle))
        Text(localized(GeneratedStrings.State.noAccessText))
        Button(onClick = onToday, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
            Text(localized(GeneratedStrings.Nav.today))
        }
    }
}
