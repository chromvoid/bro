package assistant.mobile.android

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import assistant.mobile.core.GeneratedStrings
import assistant.mobile.core.LocalizedString
import assistant.mobile.core.assistant.AssistantClarificationItem
import assistant.mobile.core.assistant.AssistantClarificationStatus
import assistant.mobile.core.assistant.AssistantControlAction
import assistant.mobile.core.assistant.AssistantControlStatus
import assistant.mobile.core.assistant.AssistantPendingControl
import assistant.mobile.core.assistant.AssistantState

@Composable
internal fun AssistantClarificationStatus(item: AssistantClarificationItem, busy: Boolean,
    onCheck: () -> Unit, onRetry: () -> Unit, onOpen: (String) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)
        .testTag("assistant_clarification_status_${item.sourceMessageId ?: item.targetMessageId}"),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(localized(clarificationLabel(item.status)), style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
        if (item.status == AssistantClarificationStatus.Queued) Text(localized(GeneratedStrings.LiveClarification.queued),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center)
        clarificationReason(item.reason)?.let { Text(localized(it), style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center) }
        item.question?.takeIf(String::isNotBlank)?.let { Text(it, style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center) }
        if (item.canCheck) TextButton(onClick = onCheck, enabled = !busy,
            modifier = Modifier.heightIn(min = 48.dp).testTag("assistant_clarification_check_${item.key}")) {
            Text(localized(GeneratedStrings.LiveClarification.check))
        }
        if (item.canRetry) TextButton(onClick = onRetry, enabled = !busy,
            modifier = Modifier.heightIn(min = 48.dp).testTag("assistant_clarification_retry_${item.key}")) {
            Text(localized(GeneratedStrings.Assistant.pendingResend))
        }
        if (item.status == AssistantClarificationStatus.Queued) item.sourceMessageId?.let { source ->
            TextButton(onClick = { onOpen(source) }, modifier = Modifier.heightIn(min = 48.dp)) { Text(localized(GeneratedStrings.LiveClarification.open)) }
        }
        TextButton(onClick = { onOpen(item.targetMessageId) }, modifier = Modifier.heightIn(min = 48.dp)) {
            Text(localized(GeneratedStrings.LiveClarification.origin))
        }
    }
}

@Composable
internal fun AssistantControlStatus(item: AssistantPendingControl, busy: Boolean, onCheck: () -> Unit, onRetry: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp).testTag("assistant_control_status_${item.messageId}"),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp)) {
        val label = when {
            item.unknownOutcome -> GeneratedStrings.Assistant.Controls.unknownChanges
            item.status == AssistantControlStatus.Rejected -> controlReason(item.reason)
            item.status == AssistantControlStatus.Settled -> if (item.action == AssistantControlAction.Stop)
                GeneratedStrings.Assistant.Controls.stopped else GeneratedStrings.Assistant.Controls.withdrawn
            item.status == AssistantControlStatus.Sending -> GeneratedStrings.Assistant.Controls.stopping
            else -> GeneratedStrings.Assistant.Controls.awaitingReceipt
        }
        Text(localized(label), style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
        if (item.execution?.effects == "confirmed") Text(localized(GeneratedStrings.Assistant.Controls.keptChanges),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center)
        if (item.canCheck) TextButton(onClick = onCheck, enabled = !busy,
            modifier = Modifier.heightIn(min = 48.dp).testTag("assistant_control_check_${item.key}")) {
            Text(localized(GeneratedStrings.Assistant.Controls.check))
        }
        if (item.canRetry) TextButton(onClick = onRetry, enabled = !busy,
            modifier = Modifier.heightIn(min = 48.dp).testTag("assistant_control_retry_${item.key}")) {
            Text(localized(GeneratedStrings.Assistant.Controls.retry))
        }
    }
}

@Composable
internal fun AssistantAvailabilityNotice(state: AssistantState, onRefresh: () -> Unit, onFixAccess: ((String) -> Unit)? = null) {
    val availability = state.availability
    if (!state.availabilityFailed && availability?.state == "configured") return
    val label = when {
        state.availabilityFailed -> GeneratedStrings.Assistant.Availability.checkFailed
        availability == null -> if (state.availabilityLoading) GeneratedStrings.Assistant.Availability.checking else GeneratedStrings.Assistant.Availability.unknown
        else -> when (availability.reason) {
            "no_account_access" -> GeneratedStrings.Assistant.Availability.no_account_access
            "no_compatible_connection" -> GeneratedStrings.Assistant.Availability.no_compatible_connection
            "auth_required" -> GeneratedStrings.Assistant.Availability.auth_required
            "auth_unavailable" -> GeneratedStrings.Assistant.Availability.auth_unavailable
            "execution_disabled" -> GeneratedStrings.Assistant.Availability.execution_disabled
            "chat_disabled" -> GeneratedStrings.Assistant.Availability.chat_disabled
            "quota_exhausted" -> GeneratedStrings.Assistant.Availability.quota_exhausted
            "daily_limit" -> GeneratedStrings.Assistant.Availability.daily_limit
            "transport_error" -> GeneratedStrings.Assistant.Availability.transport_error
            else -> GeneratedStrings.Assistant.Availability.unavailable
        }
    }
    Surface(color = MaterialTheme.colorScheme.surfaceVariant, contentColor = MaterialTheme.colorScheme.onSurface,
        shape = RoundedCornerShape(12.dp), modifier = Modifier.padding(horizontal = 16.dp)) {
        Column(Modifier.fillMaxWidth().padding(12.dp).testTag("assistant_availability"), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(localized(label), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
            if (state.availabilityLoading && (availability != null || state.availabilityFailed)) Text(localized(GeneratedStrings.Assistant.Availability.checking))
            if (availability?.action == "contact_admin") Text(localized(GeneratedStrings.Assistant.contactAdmin))
            availability?.retryAt?.let { Text(localized(GeneratedStrings.Assistant.Availability.retryAt(it))) }
            val action = availability?.action
            if (onFixAccess != null && (action == "open_accounts" || action == "configure_access")) {
                TextButton(onClick = { onFixAccess(action) }, modifier = Modifier.heightIn(min = 48.dp).testTag("assistant_fix_access")) {
                    Text(localized(GeneratedStrings.Assistant.fixAccess))
                }
            }
            TextButton(onClick = onRefresh, enabled = !state.availabilityLoading, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(localized(GeneratedStrings.Assistant.Availability.refresh))
            }
        }
    }
}

private fun clarificationLabel(status: AssistantClarificationStatus): LocalizedString = when (status) {
    AssistantClarificationStatus.Sending -> GeneratedStrings.Assistant.sendingNow
    AssistantClarificationStatus.Unknown -> GeneratedStrings.Outcome.unknownText
    AssistantClarificationStatus.Accepted -> GeneratedStrings.LiveClarification.State.accepted
    AssistantClarificationStatus.Delivered -> GeneratedStrings.LiveClarification.State.delivered
    AssistantClarificationStatus.Applied -> GeneratedStrings.LiveClarification.State.applied
    AssistantClarificationStatus.Queued -> GeneratedStrings.LiveClarification.State.queued
    AssistantClarificationStatus.Rejected -> GeneratedStrings.LiveClarification.State.rejected
    AssistantClarificationStatus.Paused -> GeneratedStrings.LiveClarification.State.paused
}
private fun clarificationReason(reason: String?): LocalizedString? = when (reason) {
    "generation_changed" -> GeneratedStrings.LiveClarification.Reason.generation_changed
    "budget_exhausted" -> GeneratedStrings.LiveClarification.Reason.budget_exhausted
    "run_interrupted" -> GeneratedStrings.LiveClarification.Reason.run_interrupted
    else -> null
}
private fun controlReason(reason: String?): LocalizedString = when (reason) {
    "request_started" -> GeneratedStrings.Assistant.Controls.Reasons.request_started
    "request_queued" -> GeneratedStrings.Assistant.Controls.Reasons.request_queued
    "request_finished" -> GeneratedStrings.Assistant.Controls.Reasons.request_finished
    "generation_changed" -> GeneratedStrings.Assistant.Controls.Reasons.generation_changed
    "control_unavailable" -> GeneratedStrings.Assistant.Controls.Reasons.control_unavailable
    else -> GeneratedStrings.Assistant.Controls.rejected
}
