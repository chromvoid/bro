package assistant.mobile.android

import android.app.TimePickerDialog
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import assistant.mobile.core.GeneratedStrings
import assistant.mobile.core.SessionLease
import assistant.mobile.core.notifications.NotificationDeviceState
import assistant.mobile.core.notifications.NotificationPermission
import assistant.mobile.core.notifications.NotificationsController
import kotlinx.coroutines.launch

@Composable
internal fun NotificationSettingsScreen(
    controller: NotificationsController,
    lease: SessionLease,
    device: NotificationDeviceState,
    onEnable: () -> Unit,
    onDisable: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val observed by controller.state.collectAsStateWithLifecycle()
    val state = observed?.takeIf { it.namespace == lease.namespace }
    val scope = rememberCoroutineScope()
    NotificationsLifecycle(controller, lease)
    ScreenColumn("screen_NotificationSettings", modifier, title = localized(GeneratedStrings.NotificationSettings.title)) {
        Text(localized(GeneratedStrings.NotificationSettings.text))
        OutlinedCard(Modifier.fillMaxWidth().testTag("notifications_device")) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                TaskHeading(localized(GeneratedStrings.NotificationSettings.device))
                Text(localized(GeneratedStrings.NotificationSettings.shell), style = MaterialTheme.typography.bodyMedium)
                if (device.schedulePending) StatusMessage(GeneratedStrings.NotificationSettings.schedulePending,
                    "notifications_schedule_pending")
                if (device.permission == NotificationPermission.Denied) {
                    StatusMessage(GeneratedStrings.NotificationSettings.denied, "notifications_permission_denied")
                } else {
                    val deviceLabel = localized(if (device.enabled) GeneratedStrings.NotificationSettings.deviceOn
                        else GeneratedStrings.NotificationSettings.deviceHint)
                    NotificationToggleRow(deviceLabel, device.enabled, state?.busy != true, "notifications_device_toggle") {
                        if (it) onEnable() else onDisable()
                    }
                }
                OutlinedButton(onClick = { scope.launch { controller.test() } }, enabled = state?.busy != true,
                    modifier = Modifier.heightIn(min = 48.dp).testTag("notifications_test")) {
                    Text(localized(GeneratedStrings.NotificationSettings.test))
                }
            }
        }
        if (state == null) StatusMessage(GeneratedStrings.State.loading, "notifications_settings_loading")
        state?.let { current ->
            NotificationsQueryStatus(current.settings, "settings") { scope.launch { controller.refresh() } }
            current.error?.let { ErrorMessage(it, "notifications_settings_error") }
            current.notice?.let { StatusMessage(it, "notifications_settings_notice") }
            current.settings.data?.let { settings ->
                var draft by remember(lease.namespace, settings.revision) { mutableStateOf(settings.draft()) }
                OutlinedCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        NotificationToggleRow(localized(GeneratedStrings.NotificationSettings.quiet), draft.quiet, !current.busy,
                            "notifications_quiet_toggle") { draft = draft.copy(quiet = it) }
                        if (draft.quiet) Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            NotificationTimePicker(localized(GeneratedStrings.NotificationSettings.from), draft.start,
                                !current.busy, "notifications_quiet_start", Modifier.weight(1f)) { draft = draft.copy(start = it) }
                            NotificationTimePicker(localized(GeneratedStrings.NotificationSettings.to), draft.end,
                                !current.busy, "notifications_quiet_end", Modifier.weight(1f)) { draft = draft.copy(end = it) }
                        }
                        Text(localized(GeneratedStrings.NotificationSettings.quietHint), style = MaterialTheme.typography.bodySmall)
                    }
                }
                if (settings.topics.isNotEmpty()) OutlinedCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        TaskHeading(localized(GeneratedStrings.NotificationSettings.topicsTitle))
                        settings.topics.forEach { item ->
                            NotificationToggleRow(notificationTopicLabel(item.topic), !draft.mutedTopics.contains(item.topic),
                                !current.busy, "notifications_topic_${item.topic}") { on ->
                                draft = draft.copy(mutedTopics = if (on) draft.mutedTopics.filterNot { it == item.topic }
                                    else (draft.mutedTopics + item.topic).distinct())
                            }
                        }
                        Text(localized(GeneratedStrings.NotificationSettings.topicsHint), style = MaterialTheme.typography.bodySmall)
                    }
                }
                Button(onClick = { scope.launch { controller.saveSettings(draft) } }, enabled = !current.busy,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("notifications_settings_save")) {
                    Text(localized(GeneratedStrings.NotificationSettings.save))
                }
            }
        }
    }
}

@Composable
private fun NotificationToggleRow(label: String, checked: Boolean, enabled: Boolean, tag: String, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp)
        .toggleable(value = checked, enabled = enabled, role = Role.Switch, onValueChange = onChange)
        .testTag(tag), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(label, Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = null, enabled = enabled)
    }
}

@Composable
private fun NotificationTimePicker(label: String, value: String, enabled: Boolean, tag: String,
    modifier: Modifier = Modifier, onChange: (String) -> Unit) {
    val context = LocalContext.current
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium)
        OutlinedButton(onClick = {
            val parts = value.split(':')
            TimePickerDialog(context, { _, hour, minute ->
                onChange("${hour.toString().padStart(2, '0')}:${minute.toString().padStart(2, '0')}")
            }, parts.getOrNull(0)?.toIntOrNull() ?: 0, parts.getOrNull(1)?.toIntOrNull() ?: 0, true).show()
        }, enabled = enabled, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag(tag)
            .semantics { contentDescription = label }) { Text(value) }
    }
}

@Composable
private fun notificationTopicLabel(topic: String): String = when (topic) {
    "app.test" -> localized(GeneratedStrings.NotificationSettings.Topics.app_test)
    "timers.fired" -> localized(GeneratedStrings.NotificationSettings.Topics.timers_fired)
    "timers.reminder" -> localized(GeneratedStrings.NotificationSettings.Topics.timers_reminder)
    "agents.message" -> localized(GeneratedStrings.NotificationSettings.Topics.agents_message)
    "system.backup" -> localized(GeneratedStrings.NotificationSettings.Topics.system_backup)
    "system.deploy" -> localized(GeneratedStrings.NotificationSettings.Topics.system_deploy)
    "system.disk" -> localized(GeneratedStrings.NotificationSettings.Topics.system_disk)
    "system.errors" -> localized(GeneratedStrings.NotificationSettings.Topics.system_errors)
    "system.plugin" -> localized(GeneratedStrings.NotificationSettings.Topics.system_plugin)
    "calls.missed" -> localized(GeneratedStrings.NotificationSettings.Topics.calls_missed)
    "nutrition.refined" -> localized(GeneratedStrings.NotificationSettings.Topics.nutrition_refined)
    "research.sources" -> localized(GeneratedStrings.NotificationSettings.Topics.research_sources)
    "research.ready" -> localized(GeneratedStrings.NotificationSettings.Topics.research_ready)
    "tasks.assistant.result" -> localized(GeneratedStrings.NotificationSettings.Topics.tasks_assistant_result)
    "tasks.assistant.question" -> localized(GeneratedStrings.NotificationSettings.Topics.tasks_assistant_question)
    "tasks.assistant.failed" -> localized(GeneratedStrings.NotificationSettings.Topics.tasks_assistant_failed)
    else -> topic
}
