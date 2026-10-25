package assistant.mobile.android

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import assistant.mobile.core.AccountDeletionController
import assistant.mobile.core.AccountDeletionState
import assistant.mobile.core.AccountNamespace
import assistant.mobile.core.DeletionStatus
import assistant.mobile.core.GeneratedStrings
import assistant.mobile.core.HttpOutcome
import assistant.mobile.core.nativeError
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun AccountDeletionScreen(
    deletion: AccountDeletionController,
    modifier: Modifier = Modifier,
    statusOnly: Boolean = false,
    account: AccountNamespace? = null,
    onFinished: () -> Unit = {},
) {
    val observed by deletion.state.collectAsStateWithLifecycle()
    val state = if (account != null && observed.namespace != account) AccountDeletionState() else observed
    val scope = rememberCoroutineScope()
    // This value is never part of saved state or the password-free recovery record.
    var password by remember(account) { mutableStateOf("") }
    val wait = retrySeconds((state.failure as? HttpOutcome.RateLimited)?.retryAtMillis)
    LaunchedEffect(deletion, statusOnly, account) {
        if (!statusOnly && state.status == null) deletion.loadOptions()
    }
    LaunchedEffect(deletion, state.status) {
        while (deletion.state.value.status != null && deletion.state.value.status != DeletionStatus.COMPLETED) {
            deletion.refreshStatus()
            delay(5_000)
        }
    }
    ScreenColumn("screen_AccountDeletion", modifier,
        title = if (statusOnly) null else localized(GeneratedStrings.AccountDeletion.title)) {
        if (statusOnly) ScreenTitle(localized(GeneratedStrings.AccountDeletion.title))
        when {
            state.status != null -> {
                StatusMessage(if (state.status == DeletionStatus.COMPLETED) GeneratedStrings.AccountDeletion.completed
                    else GeneratedStrings.AccountDeletion.pending, "deletion_status")
                if (state.status == DeletionStatus.COMPLETED) {
                    Button(onClick = onFinished, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("deletion_finished")) {
                        Text(localized(GeneratedStrings.Auth.backToSignIn))
                    }
                } else TextButton(onClick = { scope.launch { deletion.refreshStatus() } }, enabled = !state.busy && wait == 0L,
                    modifier = Modifier.heightIn(min = 48.dp).testTag("deletion_refresh")) {
                    Text(localized(GeneratedStrings.AccountDeletion.check))
                }
            }
            state.hasRecovery -> {
                Text(localized(GeneratedStrings.Outcome.unknownText))
                Button(onClick = { scope.launch { withContext(NonCancellable) { deletion.recover() } } },
                    enabled = !state.busy && wait == 0L,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("deletion_recover")) {
                    Text(localized(GeneratedStrings.AccountDeletion.check))
                }
                if (account != null && state.options != null) {
                    PasswordField(password, { password = it }, GeneratedStrings.AccountDeletion.password,
                        "deletion_password", !state.busy)
                    Button(onClick = {
                        val secret = password
                        password = ""
                        scope.launch { withContext(NonCancellable) { deletion.submit(secret) } }
                    }, enabled = !state.busy && wait == 0L && password.isNotEmpty(),
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("deletion_submit")) {
                        Text(localized(GeneratedStrings.AccountDeletion.confirm))
                    }
                }
            }
            statusOnly -> StatusMessage(GeneratedStrings.AccountDeletion.retry)
            else -> {
                Text(localized(GeneratedStrings.AccountDeletion.description))
                if (state.busy && state.options == null) StatusMessage(GeneratedStrings.State.loading)
                val options = state.options
                if (options?.lastAdmin == true) Text(localized(GeneratedStrings.AccountDeletion.lastAdmin))
                options?.warning?.let { Text(it) }
                options?.spaces?.forEach { space ->
                    OutlinedCard(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(localized(GeneratedStrings.AccountDeletion.successor(space.name)), style = MaterialTheme.typography.titleMedium)
                            SuccessorChoice(localized(GeneratedStrings.AccountDeletion.archive), state.successors[space.id] == null,
                                !state.busy, "successor_${space.id}_archive") { deletion.selectSuccessor(space.id, null) }
                            space.successors.forEach { candidate ->
                                SuccessorChoice(candidate.name, state.successors[space.id] == candidate.id,
                                    !state.busy, "successor_${space.id}_${candidate.id}") { deletion.selectSuccessor(space.id, candidate.id) }
                            }
                        }
                    }
                }
                PasswordField(password, { password = it }, GeneratedStrings.AccountDeletion.password, "deletion_password", !state.busy)
                Button(
                    onClick = {
                        val secret = password
                        password = ""
                        scope.launch { withContext(NonCancellable) { deletion.submit(secret) } }
                    },
                    enabled = options != null && !state.busy && wait == 0L && password.isNotEmpty(),
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("deletion_submit"),
                ) { Text(localized(GeneratedStrings.AccountDeletion.confirm)) }
                if (state.failure != null && options == null) TextButton(
                    onClick = { scope.launch { deletion.loadOptions() } }, enabled = !state.busy && wait == 0L,
                ) { Text(localized(GeneratedStrings.Common.retry)) }
            }
        }
        if (wait > 0) StatusMessage(GeneratedStrings.Auth.pauseText(wait.toString()), "deletion_retry")
        state.failure?.let { ErrorMessage(nativeError(it), "deletion_error") }
    }
}

@Composable
private fun SuccessorChoice(label: String, selected: Boolean, enabled: Boolean, tag: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
            .selectable(selected = selected, enabled = enabled, role = Role.RadioButton, onClick = onClick).testTag(tag),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, enabled = enabled, onClick = null)
        Text(label, modifier = Modifier.padding(start = 12.dp))
    }
}
