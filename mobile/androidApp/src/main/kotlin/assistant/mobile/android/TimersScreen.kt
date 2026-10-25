package assistant.mobile.android

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
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
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import assistant.mobile.design.ui.ActionIconButton
import assistant.mobile.design.ui.AppCard
import assistant.mobile.design.ui.AppCardSurface
import assistant.mobile.design.ui.CardTitle
import assistant.mobile.design.ui.ProgressBar
import assistant.mobile.design.ui.Spacing
import assistant.mobile.core.GeneratedStrings
import assistant.mobile.core.SessionLease
import assistant.mobile.core.timers.*
import assistant.mobile.design.R as DesignR
import kotlinx.coroutines.launch

/** A local reading never fires a timer: only a refreshed server response changes its state. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TimersScreen(
    controller: TimersController,
    lease: SessionLease,
    onOpenTask: (String) -> Unit,
    modifier: Modifier = Modifier,
    initialTimerId: String? = null,
    initialReminderId: String? = null,
) {
    val observed by controller.state.collectAsStateWithLifecycle()
    val state = observed?.takeIf { it.namespace == lease.namespace }
    val scope = rememberCoroutineScope()
    var minutes by remember(lease.namespace) { mutableStateOf("10") }
    var label by remember(lease.namespace) { mutableStateOf("") }
    TimerLifecycle(controller, lease)
    LaunchedEffect(state?.namespace, state?.canWrite, state?.draftReady, state?.draft) {
        if (state?.canWrite == true && state.draftReady && state.draft == null) controller.beginReminder()
    }
    Column(modifier.fillMaxSize()) {
        NativeTopBar(stringResource(DesignR.string.tasks_timers_title))
        PullToRefreshBox(isRefreshing = state?.let { it.running.loading || it.reminders.loading } == true,
            onRefresh = { scope.launch { controller.refresh() } }, modifier = Modifier.weight(1f).fillMaxWidth().testTag("timers_pull_refresh")) {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp).testTag("screen_Timers"),
                verticalArrangement = Arrangement.spacedBy(16.dp)) {
                if (state == null) StatusMessage(GeneratedStrings.State.loading, "timers_loading")
                state?.let { current ->
                    TimersPending(current, controller)
                    current.error?.let { ErrorMessage(it, "timers_error") }
                    if (!current.canRead) UnavailableCard()
                    else {
                        TimerQueryStatus(current.running, "running") { scope.launch { controller.refresh() } }
                        current.running.data.orEmpty().forEach { timer ->
                            TimerObjectTarget(timer.id, initialTimerId) {
                            TimerRunningCard(timer, current.remaining(timer), current.canWrite && !current.busy && current.pending.isEmpty()) {
                                scope.launch { controller.cancel(timer) }
                            }
                            }
                        }
                        if (current.finished.isNotEmpty()) {
                            CardTitle(stringResource(DesignR.string.tasks_recent))
                            AppCardSurface(Modifier.fillMaxWidth().testTag("timers_recent")) {
                                current.finished.forEachIndexed { index, timer ->
                                    if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                                    Text(timer.label?.takeIf(String::isNotBlank) ?: stringResource(DesignR.string.tasks_timer),
                                        style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium,
                                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                                            .padding(horizontal = Spacing.l, vertical = Spacing.m).testTag("timer_recent_${timer.id}"))
                                }
                            }
                        }
                        val empty = current.running.data?.isEmpty() == true && !current.running.loading && current.running.error == null
                        if (!current.canWrite && empty) {
                            Column(Modifier.fillMaxWidth().padding(vertical = Spacing.xl), horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(Spacing.m)) {
                                Icon(painterResource(DesignR.drawable.lucide_timer), contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(48.dp))
                                Text(stringResource(DesignR.string.tasks_timers_empty), style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.testTag("timers_empty"))
                            }
                        }
                        if (current.canWrite) AppCard(testTag = "timer_create") {
                            Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                                CardTitle(stringResource(DesignR.string.tasks_new_timer))
                                if (empty) Text(stringResource(DesignR.string.tasks_timers_empty), style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.testTag("timers_empty"))
                            }
                            val presetsLabel = stringResource(DesignR.string.tasks_presets)
                            Row(Modifier.fillMaxWidth().semantics { contentDescription = presetsLabel },
                                horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                                TIMER_PRESET_MINUTES.forEach { preset ->
                                    OutlinedButton(onClick = { scope.launch {
                                        if (controller.start(preset, label) != null) label = ""
                                    } }, enabled = !current.busy && current.pending.isEmpty(),
                                        contentPadding = PaddingValues(horizontal = Spacing.xs, vertical = Spacing.s),
                                        modifier = Modifier.weight(1f).testTag("timer_preset_$preset")) {
                                        Text(stringResource(DesignR.string.tasks_minutes_short, preset.toString()))
                                    }
                                }
                            }
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Spacing.m),
                                verticalAlignment = Alignment.CenterVertically) {
                                OutlinedTextField(value = minutes, onValueChange = { minutes = it }, singleLine = true,
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                    label = { Text(stringResource(DesignR.string.tasks_minutes)) }, enabled = !current.busy,
                                    modifier = Modifier.width(96.dp).testTag("timer_minutes"))
                                OutlinedTextField(value = label, onValueChange = { label = it.take(60) }, singleLine = true,
                                    label = { Text(stringResource(DesignR.string.tasks_label)) }, enabled = !current.busy,
                                    modifier = Modifier.weight(1f).testTag("timer_label"))
                            }
                            Button(onClick = { minutes.toIntOrNull()?.let { duration -> scope.launch {
                                if (controller.start(duration, label) != null) label = ""
                            } } }, enabled = !current.busy && current.pending.isEmpty() && minutes.toIntOrNull()?.let { it in 1..1440 } == true,
                                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("timer_start")) {
                                Text(stringResource(DesignR.string.tasks_start))
                            }
                        }
                        TaskHeading(stringResource(DesignR.string.tasks_reminders))
                        TimerQueryStatus(current.reminders, "reminders") { scope.launch { controller.refresh() } }
                        if (current.reminders.data?.isEmpty() == true)
                            Text(stringResource(DesignR.string.tasks_reminders_empty), Modifier.testTag("reminders_empty"))
                        current.reminders.data.orEmpty().forEach { reminder ->
                            TimerObjectTarget(reminder.id, initialReminderId) {
                            ReminderCard(reminder, current.timezone, current.canWrite && !current.busy && current.pending.isEmpty(),
                                onDelete = { scope.launch { controller.cancelReminder(reminder) } },
                                onOpenTask = onOpenTask)
                            }
                        }
                        if (current.canWrite) current.draft?.let { draft ->
                            ReminderCreationForm(current, controller, draft, "reminder", scope, showText = true, onOpenTask = onOpenTask)
                        }
                    }
                }
            }
        }
    }
}

/** A typed notification target focuses its existing server record once it is laid out. */
@Composable
private fun TimerObjectTarget(id: String, selectedId: String?, content: @Composable () -> Unit) {
    val requester = remember(id) { BringIntoViewRequester() }
    LaunchedEffect(id, selectedId) {
        if (id == selectedId) {
            androidx.compose.runtime.withFrameNanos { }
            requester.bringIntoView()
        }
    }
    Box(Modifier.fillMaxWidth().bringIntoViewRequester(requester)) { content() }
}

@Composable
internal fun TimerLifecycle(controller: TimersController, lease: SessionLease) {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val owner = remember(controller, lease) { Any() }
    DisposableEffect(controller, lease, lifecycle, owner) {
        val observer = LifecycleEventObserver { _, event -> when (event) {
            Lifecycle.Event.ON_START -> controller.activate(owner)
            Lifecycle.Event.ON_STOP -> controller.deactivate(owner)
            else -> Unit
        } }
        lifecycle.addObserver(observer)
        if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) controller.activate(owner)
        onDispose { lifecycle.removeObserver(observer); controller.deactivate(owner) }
    }
}

@Composable
internal fun TimerRunningCard(timer: Timer, seconds: Int, canCancel: Boolean, onCancel: () -> Unit) {
    val label = timer.label?.takeIf(String::isNotBlank) ?: stringResource(DesignR.string.tasks_timer)
    AppCardSurface(Modifier.fillMaxWidth().testTag("timer_running_${timer.id}")) {
        Row(Modifier.fillMaxWidth().padding(Spacing.l), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.m)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(if (seconds == 0) stringResource(DesignR.string.tasks_finished) else timerClock(seconds),
                    style = MaterialTheme.typography.headlineLarge.copy(fontFeatureSettings = "tnum"), fontWeight = FontWeight.SemiBold,
                    color = if (seconds == 0) activityPalette().getValue("warning") else MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.testTag("timer_countdown_${timer.id}").semantics { contentDescription = label })
            }
            if (canCancel) ActionIconButton(DesignR.drawable.lucide_x, stringResource(DesignR.string.tasks_cancel),
                onClick = onCancel, testTag = "timer_cancel_${timer.id}")
        }
    }
}

@Composable
internal fun ReminderCard(reminder: Reminder, timezone: String, canWrite: Boolean,
    onDelete: () -> Unit, onOpenTask: (String) -> Unit) {
    OutlinedCard(Modifier.fillMaxWidth().testTag("reminder_item_${reminder.id}")) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(reminder.text, style = MaterialTheme.typography.titleMedium)
            Text(taskTimestamp(reminder.dueAt, timezone), style = MaterialTheme.typography.bodyMedium)
            reminder.taskId?.let { taskId ->
                TextButton(onClick = { onOpenTask(taskId) }, modifier = Modifier.heightIn(min = 48.dp)
                    .testTag("reminder_task_${reminder.id}")) { Text(stringResource(DesignR.string.tasks_reminder_of_task)) }
            }
            if (canWrite) {
                TextButton(onClick = onDelete, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                    .testTag("reminder_delete_${reminder.id}")) { Text(stringResource(DesignR.string.tasks_reminder_cancel)) }
            }
        }
    }
}

@Composable
internal fun <T> TimerQueryStatus(query: TimerQuery<T>, tag: String, retry: () -> Unit) {
    if (query.loading) ProgressBar(modifier = Modifier.fillMaxWidth().testTag("timers_loading_$tag"))
    query.error?.let {
        ErrorMessage(it, "timers_error_$tag")
        TextButton(onClick = retry, enabled = !query.loading, modifier = Modifier.heightIn(min = 48.dp).testTag("timers_retry_$tag")) {
            Text(stringResource(DesignR.string.common_retry))
        }
    }
    if (query.stale) StatusMessage(GeneratedStrings.State.stale, "timers_stale_$tag")
}

@Composable
internal fun TimersPending(state: TimersState, controller: TimersController) {
    if (state.pending.isEmpty()) return
    val scope = rememberCoroutineScope()
    OutlinedCard(Modifier.fillMaxWidth().testTag("timers_pending")) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(DesignR.string.outcome_unknown_title))
            Text(stringResource(DesignR.string.outcome_unknown_text))
            state.pending.forEach { entry ->
                TextButton(onClick = { scope.launch { controller.check(entry.key) } }, enabled = !state.busy,
                    modifier = Modifier.heightIn(min = 48.dp).testTag("timer_pending_check_${entry.key}")) {
                    Text(stringResource(DesignR.string.outcome_check))
                }
            }
        }
    }
}
