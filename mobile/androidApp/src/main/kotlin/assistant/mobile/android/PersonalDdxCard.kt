package assistant.mobile.android

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import assistant.mobile.design.ui.ProgressBar
import assistant.mobile.core.GeneratedStrings
import assistant.mobile.core.LocalizedString
import assistant.mobile.core.SessionLease
import assistant.mobile.core.personal.PersonalDdxController
import assistant.mobile.core.personal.PersonalSourceEntry
import assistant.mobile.core.personal.PersonalSourcesController
import assistant.mobile.core.personal.PersonalSourcesState
import java.time.Instant
import kotlinx.coroutines.launch

/** Phone and verification input are disposable UI values, never account drafts or submissions. */
@Composable
internal fun PersonalDdxCard(
    controller: PersonalDdxController,
    sourcesController: PersonalSourcesController,
    lease: SessionLease,
    sourcesState: PersonalSourcesState,
    onOpenHistory: (String, Int) -> Unit,
) {
    PersonalDdxActivation(controller, lease)
    val observed by controller.state.collectAsStateWithLifecycle()
    val state = observed?.takeIf { it.namespace == lease.namespace }
    val scope = rememberCoroutineScope()
    val sources = sourcesState.sources.data.orEmpty().filter { it.connection != null }
    val source = sources.firstOrNull { it.connection?.authState != "disconnected" }
    val connection = source?.connection
    var formOpen by remember(lease.namespace) { mutableStateOf(false) }
    var mode by remember(lease.namespace) { mutableStateOf("connect") }
    var phone by remember(lease.namespace) { mutableStateOf("") }
    var code by remember(lease.namespace) { mutableStateOf("") }
    var manage by remember(lease.namespace, source?.id) { mutableStateOf(false) }
    var disconnectOpen by remember(lease.namespace, source?.id) { mutableStateOf(false) }
    var archivedOpen by remember(lease.namespace) { mutableStateOf(false) }
    val login = state?.login
    val loginActive = login?.state in setOf("sending", "waiting_code", "verifying", "staged")
    val uncertain = state?.uncertain == true
    val busy = state?.busy == true || sourcesState.busy || sourcesState.pending.isNotEmpty()
    val canWrite = sourcesState.canWrite
    val available = state?.available == true
    val resendWait = retrySeconds(personalDdxInstant(state?.retryAt))
    val syncWait = retrySeconds(personalDdxInstant(connection?.retryAt))
    val run = sourcesState.run.data?.takeIf { it.sourceId == source?.id } ?: connection?.activeRun
    val syncing = run?.state in setOf("queued", "running")
    val outcome = run?.outcome ?: connection?.lastResult
    val showForm = canWrite && (formOpen || loginActive || uncertain)
    LaunchedEffect(canWrite, login?.state) {
        if (!canWrite || (login != null && !loginActive)) code = ""
        if (login?.state == "ready") { phone = ""; formOpen = false; sourcesController.refresh() }
        if (!canWrite) { phone = ""; formOpen = false; manage = false; disconnectOpen = false }
    }
    fun begin(next: String) {
        if (!canWrite || !available || loginActive || uncertain) return
        controller.clear()
        phone = ""
        code = ""
        mode = next
        formOpen = true
    }
    OutlinedCard(Modifier.fillMaxWidth().testTag("personal_ddx_card")) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(localized(GeneratedStrings.Personal.Ddx.title), style = MaterialTheme.typography.titleMedium)
            Text(localized(GeneratedStrings.Personal.Ddx.intro), style = MaterialTheme.typography.bodySmall)
            if (busy) ProgressBar(modifier = Modifier.fillMaxWidth())
            connection?.let { current ->
                val source = requireNotNull(source)
                Text(localized(when (current.authState) {
                    "active" -> GeneratedStrings.Personal.Ddx.Connection.active
                    "auth_required" -> GeneratedStrings.Personal.Ddx.Connection.auth_required
                    else -> GeneratedStrings.Personal.Ddx.Connection.disconnected
                }), modifier = Modifier.testTag("personal_ddx_connection"))
                current.phoneMasked?.let { Text(it) }
                PersonalDdxDetail(GeneratedStrings.Personal.Ddx.lastMeasurement,
                    current.lastMeasurementAt?.let { personalMoment(it, sourcesState.timezone, sourcesState.locale) }
                        ?: localized(GeneratedStrings.Personal.Ddx.noMeasurements))
                PersonalDdxDetail(GeneratedStrings.Personal.Ddx.lastSync,
                    current.lastSuccessAt?.let { personalMoment(it, sourcesState.timezone, sourcesState.locale) }
                        ?: localized(GeneratedStrings.Personal.Ddx.notSynced))
                if (current.automatic) current.nextSyncAt?.let {
                    PersonalDdxDetail(GeneratedStrings.Personal.Ddx.nextSync, personalMoment(it, sourcesState.timezone, sourcesState.locale))
                }
                if (current.warning == "history_restricted" || source.error == "history_restricted")
                    StatusMessage(GeneratedStrings.Personal.SourceError.history_restricted)
                if (current.warning == "projection_incomplete" || source.error == "projection_incomplete" || outcome?.projectionIncomplete == true)
                    StatusMessage(GeneratedStrings.Personal.SourceError.projection_incomplete)
                current.error?.let { ErrorMessage(personalDdxErrorLabel(it), "personal_ddx_connection_error") }
                val automaticLabel = localized(GeneratedStrings.Personal.Ddx.automatic)
                Text(automaticLabel)
                Switch(checked = current.automatic, enabled = canWrite && !busy && available,
                    onCheckedChange = { value -> scope.launch { sourcesController.setAutomatic(source.id, value) } },
                    modifier = Modifier.testTag("personal_ddx_automatic").semantics { contentDescription = automaticLabel })
                Text(localized(if (current.automatic) GeneratedStrings.Personal.Ddx.hourly else GeneratedStrings.Personal.Ddx.manualOnly),
                    style = MaterialTheme.typography.bodySmall)
                if (canWrite && current.authState == "active") PersonalDdxButton(
                    if (syncing) GeneratedStrings.Personal.Ddx.syncing else GeneratedStrings.Personal.Ddx.syncNow,
                    "personal_ddx_sync", !busy && !syncing && syncWait == 0L && available,
                ) { scope.launch { sourcesController.requestSync(source.id) } }
                if (canWrite && current.authState == "auth_required" && current.canReconnect)
                    PersonalDdxButton(GeneratedStrings.Personal.Ddx.reconnect, "personal_ddx_reconnect", available && !loginActive) { begin("reconnect") }
                PersonalDdxButton(GeneratedStrings.Personal.bodyHistory, "personal_ddx_history", true) { onOpenHistory("body", 365) }
                if (syncWait > 0) StatusMessage(GeneratedStrings.Personal.Ddx.syncWait(syncWait.toString()))
                run?.let { StatusMessage(personalDdxRunLabel(it.state), "personal_ddx_run") }
                PersonalQueryStatus(sourcesState.run) { scope.launch { sourcesController.refresh() } }
                if (outcome != null && (run == null || run.state in setOf("succeeded", "partial")))
                    StatusMessage(GeneratedStrings.Personal.Ddx.syncCounts(outcome.newAnalyses.toString(), outcome.updatedAnalyses.toString()))
                outcome?.error?.let { ErrorMessage(personalDdxErrorLabel(it), "personal_ddx_run_error") }
                if (canWrite) {
                    TextButton(onClick = { manage = !manage }, modifier = Modifier.heightIn(min = 48.dp).testTag("personal_ddx_manage")) {
                        Text(localized(GeneratedStrings.Personal.Ddx.manage))
                    }
                    if (manage) {
                        if (!current.canReconnect) Text(localized(GeneratedStrings.Personal.Ddx.legacyConnection))
                        if (current.canReconnect) PersonalDdxButton(GeneratedStrings.Personal.Ddx.reconnect,
                            "personal_ddx_manage_reconnect", available && !loginActive && !busy) { begin("reconnect") }
                        PersonalDdxButton(GeneratedStrings.Personal.Ddx.changeAccount, "personal_ddx_replace", available && !loginActive && !busy) { begin("replace") }
                        PersonalDdxButton(GeneratedStrings.Personal.Ddx.disconnect, "personal_ddx_disconnect_open",
                            !busy && !loginActive && state?.canDisconnect == true) { disconnectOpen = true }
                    }
                    if (disconnectOpen) {
                        Text(localized(GeneratedStrings.Personal.Ddx.disconnectHint))
                        PersonalDdxButton(GeneratedStrings.Personal.Ddx.disconnect, "personal_ddx_disconnect", !busy && state?.canDisconnect == true) {
                            scope.launch { controller.disconnect(source.id, current.revision); sourcesController.refresh(); if (controller.state.value?.errorCode == null) disconnectOpen = false }
                        }
                        PersonalDdxButton(GeneratedStrings.Common.cancel, "personal_ddx_disconnect_cancel", !busy) { disconnectOpen = false }
                    }
                }
            }
            if (canWrite) {
                if (state?.available == false) Text(localized(GeneratedStrings.Personal.Ddx.unavailable))
                else if (state?.available == null && state?.checking == true) Text(localized(GeneratedStrings.Personal.Ddx.checking))
                if (connection == null && !showForm && available)
                    PersonalDdxButton(GeneratedStrings.Personal.Ddx.connect, "personal_ddx_connect", !busy) { begin("connect") }
                if (showForm) {
                    HorizontalDivider()
                    if (!loginActive && !uncertain) {
                        Text(localized(when (mode) {
                            "reconnect" -> GeneratedStrings.Personal.Ddx.samePhone
                            "replace" -> GeneratedStrings.Personal.Ddx.replaceHint
                            else -> GeneratedStrings.Personal.Ddx.connectHint
                        }))
                        OutlinedTextField(value = phone, onValueChange = { if (it.length <= 32) phone = it },
                            label = { Text(localized(GeneratedStrings.Personal.Ddx.phone)) },
                            placeholder = { Text(localized(GeneratedStrings.Personal.Ddx.phonePlaceholder)) },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone), singleLine = true,
                            enabled = !busy, modifier = Modifier.fillMaxWidth().testTag("personal_ddx_phone"))
                        PersonalDdxButton(if (resendWait > 0) GeneratedStrings.Personal.Ddx.resendWait(resendWait.toString()) else GeneratedStrings.Personal.Ddx.getCode,
                            "personal_ddx_start", !busy && phone.isNotBlank() && available && resendWait == 0L) {
                            val input = phone
                            scope.launch { controller.start(mode, input, source?.id.takeUnless { mode == "connect" }, connection?.revision.takeUnless { mode == "connect" }) }
                        }
                        PersonalDdxButton(GeneratedStrings.Common.cancel, "personal_ddx_dismiss", !busy) {
                            formOpen = false; phone = ""; code = ""; controller.clear()
                        }
                    }
                    if (loginActive && login != null) {
                        StatusMessage(personalDdxLoginLabel(login.state), "personal_ddx_login_state")
                        login.phoneMasked?.let { Text(it) }
                        if (login.state == "waiting_code" && !uncertain) {
                            Text(localized(GeneratedStrings.Personal.Ddx.codeHint), style = MaterialTheme.typography.bodySmall)
                            OutlinedTextField(value = code, onValueChange = { if (it.length <= 6) code = it },
                                label = { Text(localized(GeneratedStrings.Personal.Ddx.code)) }, singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword), enabled = !busy,
                                modifier = Modifier.fillMaxWidth().testTag("personal_ddx_code"))
                            PersonalDdxButton(GeneratedStrings.Personal.Ddx.verify, "personal_ddx_verify",
                                !busy && code.trim().length == 6 && code.trim().all { it in '0'..'9' } && available) {
                                val input = code
                                code = ""
                                scope.launch { controller.submitCode(input) }
                            }
                        }
                        login.expiresAt?.let { Text(localized(GeneratedStrings.Personal.Ddx.expires(personalMoment(it, sourcesState.timezone, sourcesState.locale))),
                            style = MaterialTheme.typography.bodySmall) }
                        if (login.state == "waiting_code") PersonalDdxButton(
                            if (resendWait > 0) GeneratedStrings.Personal.Ddx.resendWait(resendWait.toString()) else GeneratedStrings.Personal.Ddx.resend,
                            "personal_ddx_resend", !busy && !uncertain && resendWait == 0L && login.canResend && available,
                        ) { code = ""; scope.launch { controller.resend() } }
                        PersonalDdxButton(GeneratedStrings.Common.cancel, "personal_ddx_cancel", !busy) {
                            code = ""; phone = ""; scope.launch { controller.cancel(); if (controller.state.value?.login?.state == "cancelled") formOpen = false }
                        }
                    }
                    if (uncertain) {
                        StatusMessage(GeneratedStrings.Personal.Ddx.uncertain, "personal_ddx_uncertain")
                        PersonalDdxButton(GeneratedStrings.Personal.Ddx.checkStatus, "personal_ddx_check", state?.checking != true && !busy) {
                            scope.launch { controller.refresh() }
                        }
                        if (state?.retryStart == true) PersonalDdxButton(GeneratedStrings.Personal.Ddx.retryStart,
                            "personal_ddx_retry_start", !busy) { scope.launch { controller.repeatStart() } }
                    }
                }
                if (login != null && !loginActive && (login.state != "ready" || (connection?.authState == "active" && login.sourceId == source?.id)))
                    StatusMessage(personalDdxLoginLabel(login.state), "personal_ddx_login_state")
                (state?.errorCode ?: login?.error)?.let { ErrorMessage(personalDdxErrorLabel(it), "personal_ddx_error") }
                if (state?.available == null && state?.errorCode != null)
                    PersonalDdxButton(GeneratedStrings.Personal.Ddx.checkStatus, "personal_ddx_availability_check", state.checking != true) { scope.launch { controller.refresh() } }
            }
            val archived = sources.filter { it.connection?.authState == "disconnected" }
            if (archived.isNotEmpty()) {
                TextButton(onClick = { archivedOpen = !archivedOpen }, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(localized(GeneratedStrings.Personal.Ddx.previousConnections))
                }
                if (archivedOpen) archived.forEach { item ->
                    Text(listOfNotNull(item.name, item.connection?.phoneMasked, localized(GeneratedStrings.Personal.Ddx.historyKept)).joinToString(" · "))
                }
            }
        }
    }
}

@Composable
private fun PersonalDdxActivation(controller: PersonalDdxController, lease: SessionLease) {
    val owner = LocalLifecycleOwner.current
    DisposableEffect(controller, lease, owner) {
        val visibilityOwner = Any()
        val observer = LifecycleEventObserver { _, event -> when (event) {
            Lifecycle.Event.ON_START -> controller.activate(visibilityOwner)
            Lifecycle.Event.ON_STOP -> controller.deactivate(visibilityOwner)
            else -> Unit
        } }
        owner.lifecycle.addObserver(observer)
        if (owner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) controller.activate(visibilityOwner)
        onDispose { owner.lifecycle.removeObserver(observer); controller.deactivate(visibilityOwner) }
    }
}

@Composable
private fun PersonalDdxDetail(label: LocalizedString, value: String) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(localized(label), style = MaterialTheme.typography.labelMedium)
        Text(value)
    }
}

@Composable
private fun PersonalDdxButton(label: LocalizedString, tag: String, enabled: Boolean, onClick: () -> Unit) {
    OutlinedButton(onClick = onClick, enabled = enabled,
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag(tag)) { Text(localized(label)) }
}

private fun personalDdxInstant(value: String?): Long? = value?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() }
