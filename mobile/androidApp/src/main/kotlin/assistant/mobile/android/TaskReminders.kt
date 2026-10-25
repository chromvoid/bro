package assistant.mobile.android

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import assistant.mobile.core.SessionLease
import assistant.mobile.core.tasks.Task
import assistant.mobile.core.timers.*
import assistant.mobile.design.R as DesignR
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** A task's reminders belong to this person; task edit permission is not required. */
@Composable
internal fun TaskRemindersSection(task: Task, controller: TimersController, lease: SessionLease) {
    TimerLifecycle(controller, lease)
    val observed by controller.state.collectAsStateWithLifecycle()
    val state = observed?.takeIf { it.namespace == lease.namespace } ?: return
    val scope = rememberCoroutineScope()
    val mine = state.remindersForTask(task.id)
    val closed = task.status == "done" || task.deletedAt != null
    val draft = state.draft?.takeIf { it.taskId == task.id }
    if (!state.canWrite && mine.isEmpty()) return
    Column(Modifier.fillMaxWidth().testTag("task_reminders"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (mine.isNotEmpty()) TaskHeading(stringResource(DesignR.string.tasks_reminder))
        TimerQueryStatus(state.reminders, "task_reminders") { scope.launch { controller.refresh() } }
        TimersPending(state, controller)
        state.error?.let { ErrorMessage(it, "task_reminder_error") }
        mine.forEach { reminder ->
            OutlinedCard(Modifier.fillMaxWidth().testTag("task_reminder_item_${reminder.id}")) {
                Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(taskTimestamp(reminder.dueAt, state.timezone), style = MaterialTheme.typography.bodyMedium)
                    if (state.canWrite) {
                        TextButton(onClick = { scope.launch { controller.cancelReminder(reminder) } },
                            enabled = !state.busy && state.pending.isEmpty(), modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                                .testTag("task_reminder_remove_${reminder.id}")) { Text(stringResource(DesignR.string.tasks_reminder_cancel)) }
                    }
                }
            }
        }
        if (closed && mine.isNotEmpty()) Text(stringResource(DesignR.string.tasks_remind_silent),
            style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("task_reminder_silent"))
        if (state.canWrite && !closed) {
            if (draft == null) OutlinedButton(onClick = { controller.beginReminder(task) },
                enabled = state.draftReady && !state.busy && state.pending.isEmpty(), modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                    .testTag("task_reminder_set")) { Text(stringResource(DesignR.string.tasks_remind_about)) }
            else ReminderCreationForm(state, controller, draft, "task_reminder", scope, showText = false)
        }
    }
}

/** The journal owns the creation draft, so refreshes preserve the person's input. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ReminderCreationForm(state: TimersState, controller: TimersController, draft: ReminderDraft,
    prefix: String, scope: CoroutineScope, showText: Boolean, onOpenTask: ((String) -> Unit)? = null) {
    val editable = state.draftReady && !state.busy && state.pending.isEmpty()
    OutlinedCard(Modifier.fillMaxWidth().testTag("${prefix}_form")) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (!showText) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    reminderPresets(controller.nowEpochMillis(), state.timezone).forEach { preset ->
                        OutlinedButton(onClick = {
                            controller.setReminderAt(preset.at)
                            scope.launch { controller.submitReminder() }
                        }, enabled = editable, modifier = Modifier.heightIn(min = 48.dp)
                            .testTag("${prefix}_preset_${preset.key}")) {
                            Text(stringResource(when (preset.key) {
                                "evening" -> DesignR.string.tasks_remind_preset_evening
                                "morning" -> DesignR.string.tasks_remind_preset_morning
                                else -> DesignR.string.tasks_remind_preset_hour
                            }))
                        }
                    }
                }
            }
            if (showText) OutlinedTextField(value = draft.text, onValueChange = { controller.updateDraft(draft.copy(text = it.take(200))) },
                label = { Text(stringResource(DesignR.string.tasks_reminder_text)) }, enabled = editable,
                modifier = Modifier.fillMaxWidth().testTag("${prefix}_text"))
            ReminderWhenField(draft.at, if (showText) DesignR.string.tasks_reminder_at else DesignR.string.tasks_remind_other,
                editable, "${prefix}_when") { controller.updateDraft(draft.copy(at = it)) }
            if (showText) draft.taskId?.let { id ->
                TextButton(onClick = { onOpenTask?.invoke(id) }, enabled = onOpenTask != null,
                    modifier = Modifier.heightIn(min = 48.dp).testTag("${prefix}_task")) {
                    Text(stringResource(DesignR.string.tasks_reminder_of_task))
                }
            }
            Button(onClick = { scope.launch { controller.submitReminder() } },
                enabled = editable && draft.text.isNotBlank() && draft.at.isNotBlank(),
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                    .testTag("${prefix}_save")) {
                Text(stringResource(if (showText) DesignR.string.tasks_remind else DesignR.string.tasks_remind_set))
            }
            if (!showText) TextButton(onClick = { scope.launch { controller.dismissDraft() } },
                enabled = !state.busy && state.pending.isEmpty(), modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                    .testTag("${prefix}_dismiss")) { Text(stringResource(DesignR.string.common_cancel)) }
            if (showText) Text(stringResource(DesignR.string.tasks_reminders_text),
                style = MaterialTheme.typography.bodySmall)
        }
    }
}

/** Calendar and clock pick in the account's local calendar; the core resolves its time zone. */
@Composable
private fun ReminderWhenField(value: String, label: Int, enabled: Boolean, tag: String, onChange: (String) -> Unit) {
    val context = LocalContext.current
    OutlinedTextField(value = value, onValueChange = onChange, singleLine = true,
        label = { Text(stringResource(label)) }, enabled = enabled,
        modifier = Modifier.fillMaxWidth().testTag(tag), trailingIcon = {
            TextButton(onClick = {
                val initial = runCatching { LocalDateTime.parse(value) }.getOrNull() ?: return@TextButton
                DatePickerDialog(context, { _, year, month, day ->
                    TimePickerDialog(context, { _, hour, minute ->
                        onChange(LocalDateTime.of(year, month + 1, day, hour, minute)
                            .format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm")))
                    }, initial.hour, initial.minute, true).show()
                }, initial.year, initial.monthValue - 1, initial.dayOfMonth).show()
            }, enabled = enabled, modifier = Modifier.heightIn(min = 48.dp).testTag("${tag}_picker")) {
                Text(stringResource(DesignR.string.common_edit))
            }
        })
}
