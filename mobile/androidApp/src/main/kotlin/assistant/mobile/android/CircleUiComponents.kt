package assistant.mobile.android

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import assistant.mobile.core.GeneratedStrings
import assistant.mobile.core.LocalizedString
import assistant.mobile.core.SessionLease
import assistant.mobile.core.circle.CircleController
import assistant.mobile.core.circle.CircleQuery
import assistant.mobile.core.circle.CircleRole
import assistant.mobile.core.circle.ReportTarget
import kotlinx.coroutines.launch

@Composable
internal fun CircleLifecycle(controller: CircleController, lease: SessionLease, spaceId: String? = null, channelId: String? = null) {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val visibilityOwner = remember(controller, lease, spaceId, channelId) { Any() }
    DisposableEffect(controller, lease, lifecycle, spaceId, channelId, visibilityOwner) {
        val observer = LifecycleEventObserver { _, event -> when (event) {
            Lifecycle.Event.ON_START -> controller.activate(spaceId = spaceId, channelId = channelId, visibilityOwner = visibilityOwner)
            Lifecycle.Event.ON_STOP -> controller.deactivate(visibilityOwner)
            else -> Unit
        } }
        lifecycle.addObserver(observer)
        if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) controller.activate(spaceId = spaceId, channelId = channelId, visibilityOwner = visibilityOwner)
        onDispose { lifecycle.removeObserver(observer); controller.deactivate(visibilityOwner) }
    }
}

@Composable
internal fun CircleHeading(message: LocalizedString) {
    Text(localized(message), style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
}

@Composable
internal fun CircleNotice(title: LocalizedString, description: LocalizedString, tag: String = "circle_notice") {
    OutlinedCard(Modifier.fillMaxWidth().testTag(tag)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            CircleHeading(title)
            Text(localized(description))
        }
    }
}

@Composable
internal fun CircleError(error: LocalizedString?, serverError: String?, tag: String = "circle_error") {
    if (serverError != null) Text(serverError, color = MaterialTheme.colorScheme.error,
        modifier = Modifier.testTag(tag).semantics { liveRegion = LiveRegionMode.Assertive })
    else error?.let { ErrorMessage(it, tag) }
}

@Composable
internal fun <T> CircleQueryStatus(query: CircleQuery<T>, tag: String, onRetry: () -> Unit) {
    if (query.loading && query.data == null) StatusMessage(GeneratedStrings.State.loading, "${tag}_loading")
    if (query.error != null || query.serverError != null) {
        if (query.data != null) StatusMessage(GeneratedStrings.State.refreshFailed, "${tag}_refresh_failed")
        else CircleError(query.error, query.serverError, "${tag}_error")
        TextButton(onClick = onRetry, enabled = !query.loading,
            modifier = Modifier.heightIn(min = 48.dp).testTag("${tag}_retry")) { Text(localized(GeneratedStrings.Common.retry)) }
    }
}

@Composable
internal fun CircleRow(title: String, description: String?, tag: String, badge: String? = null,
    enabled: Boolean = true, onClick: () -> Unit) {
    OutlinedCard(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().clickable(enabled = enabled, onClick = onClick).heightIn(min = 64.dp)
            .padding(16.dp).testTag(tag), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                description?.let { Text(it, style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
            badge?.let { Text(it, style = MaterialTheme.typography.labelLarge) }
        }
    }
}

@Composable
internal fun CircleGone(onToCircle: () -> Unit, onToAssistant: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(20.dp).testTag("circle_gone"), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        CircleHeading(GeneratedStrings.Circle.goneTitle)
        Text(localized(GeneratedStrings.Circle.goneText))
        Button(onClick = onToCircle, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("circle_gone_spaces")) {
            Text(localized(GeneratedStrings.Circle.toSpaces))
        }
        TextButton(onClick = onToAssistant, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("circle_gone_assistant")) {
            Text(localized(GeneratedStrings.Circle.toAssistant))
        }
    }
}

internal fun circleRoleLabel(role: CircleRole): LocalizedString = when (role) {
    CircleRole.Owner -> GeneratedStrings.Circle.Role.owner
    CircleRole.Admin -> GeneratedStrings.Circle.Role.admin
    CircleRole.Member -> GeneratedStrings.Circle.Role.member
}

internal fun circleRoleDescription(role: CircleRole): LocalizedString = when (role) {
    CircleRole.Owner -> GeneratedStrings.Circle.RoleText.owner
    CircleRole.Admin -> GeneratedStrings.Circle.RoleText.admin
    CircleRole.Member -> GeneratedStrings.Circle.RoleText.member
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CircleSheet(title: String, tag: String, onDismiss: () -> Unit,
    onBack: (() -> Unit)? = null, content: @Composable ColumnScope.() -> Unit) {
    val scrollState = rememberScrollState()
    LaunchedEffect(title) { scrollState.scrollTo(0) }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        BackHandler(enabled = onBack != null) { onBack?.invoke() }
        NativeTopBar(title, onBack = onBack, includeSharedActions = false)
        Column(Modifier.fillMaxWidth().verticalScroll(scrollState).imePadding().padding(horizontal = 20.dp)
            .padding(bottom = 24.dp).testTag(tag), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            content()
            TextButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("${tag}_close")) {
                Text(localized(GeneratedStrings.Common.close))
            }
        }
    }
}

@Composable
internal fun CircleReportButton(controller: CircleController, lease: SessionLease, target: ReportTarget, targetId: String) {
    var open by remember(lease.namespace, target, targetId) { mutableStateOf(false) }
    var sent by remember(lease.namespace, target, targetId) { mutableStateOf(false) }
    TextButton(onClick = { open = true; sent = false }, modifier = Modifier.heightIn(min = 48.dp)
        .testTag("circle_report_${target.wire}_${targetId}")) { Text(localized(GeneratedStrings.Moderation.report)) }
    if (sent) StatusMessage(GeneratedStrings.Moderation.sent, "circle_report_sent")
    if (open) CircleReportSheet(controller, lease, target, targetId,
        onDismiss = { open = false }, onSent = { open = false; sent = true })
}

@Composable
internal fun CircleReportSheet(controller: CircleController, lease: SessionLease, target: ReportTarget,
    targetId: String, onDismiss: () -> Unit, onSent: () -> Unit) {
    val scope = rememberCoroutineScope()
    val observed by controller.state.collectAsStateWithLifecycle()
    val current = observed?.takeIf { it.namespace == lease.namespace }
    var reason by remember(lease.namespace, target, targetId) { mutableStateOf("") }
    var busy by remember(lease.namespace, target, targetId) { mutableStateOf(false) }
    CircleSheet(localized(GeneratedStrings.Moderation.report), "circle_report_dialog", onDismiss = { if (!busy) onDismiss() }) {
        Text(localized(GeneratedStrings.Moderation.description))
        OutlinedTextField(value = reason, onValueChange = { reason = it.take(1000) }, enabled = !busy,
            label = { Text(localized(GeneratedStrings.Moderation.reason)) }, modifier = Modifier.fillMaxWidth().testTag("circle_report_reason"))
        CircleError(current?.error, current?.serverError, "circle_report_error")
        Button(onClick = { scope.launch { busy = true
            try { if (controller.report(target, targetId, reason.trim())) onSent() }
            finally { busy = false }
        } }, enabled = !busy && reason.isNotBlank(), modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("circle_report_submit")) {
            Text(localized(GeneratedStrings.Moderation.submit))
        }
    }
}
