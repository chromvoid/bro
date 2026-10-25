package assistant.mobile.android

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import assistant.mobile.design.ui.ProgressBar
import assistant.mobile.core.GeneratedStrings
import assistant.mobile.core.SessionLease
import assistant.mobile.core.recordings.Recording
import assistant.mobile.core.recordings.RecordingConversation
import assistant.mobile.core.recordings.RecordingMutation
import assistant.mobile.core.recordings.RecordingsController
import assistant.mobile.core.recordings.RecordingsState
import assistant.mobile.core.recordings.TranscriptStatus
import assistant.mobile.core.recordings.recordingConversations
import assistant.mobile.core.recordings.recordingTranscriptError
import assistant.mobile.core.recordings.recordingTranscriptStatus
import assistant.mobile.platform.AndroidRecordingPlayer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.text.NumberFormat
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt

/** The archive reads account-owned audio and text; transcription never enters the agent. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun RecordingsScreen(
    controller: RecordingsController,
    lease: SessionLease,
    modifier: Modifier = Modifier,
    playbackAllowed: Boolean = true,
    onBeforePlayback: () -> Unit = {},
) {
    val observed by controller.state.collectAsStateWithLifecycle()
    val state = observed?.takeIf { it.namespace == lease.namespace }
    val scope = rememberCoroutineScope()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var visible by remember(lease) { mutableStateOf(lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) }
    var expanded by remember(lease, state?.date) { mutableStateOf<String?>(null) }
    var confirmation by remember(lease, state?.selectedId) { mutableStateOf<RecordingMutation?>(null) }
    var playerRevision by remember(lease) { mutableIntStateOf(0) }
    DisposableEffect(controller, lease, lifecycle) {
        val observer = LifecycleEventObserver { _, event -> when (event) {
            Lifecycle.Event.ON_START -> { visible = true; controller.activate() }
            Lifecycle.Event.ON_STOP -> { visible = false; controller.deactivate() }
            else -> Unit
        } }
        lifecycle.addObserver(observer)
        if (visible) controller.activate()
        onDispose { lifecycle.removeObserver(observer); controller.deactivate() }
    }
    val refresh = {
        if (state?.busy != true) {
            confirmation = null
            playerRevision++
            scope.launch { controller.refresh() }
        }
        Unit
    }
    Column(modifier.fillMaxSize()) {
        NativeTopBar(localized(GeneratedStrings.Recordings.title))
        PullToRefreshBox(isRefreshing = state?.loading == true, onRefresh = refresh, modifier = Modifier.weight(1f).fillMaxWidth()) {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp).testTag("screen_Recordings"),
                verticalArrangement = Arrangement.spacedBy(16.dp)) {
                if (state == null) StatusMessage(GeneratedStrings.State.loading, "recordings_loading")
                state?.let { current ->
                    val enabled = !current.busy && !current.mutationUnknown
                    RecordingDateField(current.date, current.timezone, enabled) {
                        confirmation = null
                        playerRevision++
                        scope.launch { controller.setDate(it) }
                    }
                    val failure = current.error ?: current.pollError
                    if (failure != null) {
                        if (current.loaded) StatusMessage(GeneratedStrings.State.refreshFailed, "recordings_refresh_failed")
                        ErrorMessage(failure, "recordings_error")
                        TextButton(onClick = refresh, enabled = !current.loading && !current.busy,
                            modifier = Modifier.heightIn(min = 48.dp).testTag("recordings_refresh")) {
                            Text(localized(GeneratedStrings.Common.retry))
                        }
                    }
                    if (current.loading && !current.loaded) ProgressBar(modifier = Modifier.fillMaxWidth().testTag("recordings_loading"))
                    if (current.loaded && current.items.isEmpty()) OutlinedCard(Modifier.fillMaxWidth().testTag("recordings_empty")) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(localized(GeneratedStrings.Recordings.emptyTitle), style = MaterialTheme.typography.titleMedium)
                            Text(localized(GeneratedStrings.Recordings.emptyText))
                        }
                    }
                    val talks = remember(current.items) { recordingConversations(current.items) }
                    val groups = remember(talks, current.timezone) { talks.groupBy { recordingDay(it.startMillis, current.timezone) } }
                    groups.forEach { (day, conversations) ->
                        Text(recordingDayLabel(day), style = MaterialTheme.typography.titleSmall,
                            modifier = Modifier.semantics { heading() })
                        conversations.forEach { talk ->
                            RecordingConversationCard(talk, current, expanded == talk.key, enabled,
                                onToggle = {
                                    confirmation = null
                                    val closing = expanded == talk.key
                                    expanded = if (closing) null else talk.key
                                    scope.launch { controller.select(if (!closing && talk.fragments.size == 1) talk.key else null) }
                                }, onSelect = { id ->
                                    confirmation = null
                                    scope.launch { controller.select(id.takeUnless { current.selectedId == id }) }
                                }) { fragment ->
                                RecordingDetail(fragment, current, controller, lease, visible, playerRevision, playbackAllowed, onBeforePlayback,
                                    confirmation, onConfirmation = { confirmation = it }, onRefresh = refresh,
                                    onConfirm = { kind -> scope.launch {
                                        playerRevision++
                                        if (controller.mutate(kind, fragment.id)) confirmation = null
                                    } })
                            }
                        }
                    }
                    if (current.nextCursor != null) OutlinedButton(onClick = { scope.launch { controller.refresh(true) } },
                        enabled = enabled && !current.loading && !current.loadingMore,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("recordings_more")) {
                        Text(localized(if (current.loadingMore) GeneratedStrings.State.loading else GeneratedStrings.Recordings.more))
                    }
                }
            }
        }
    }
}

@Composable
private fun RecordingConversationCard(talk: RecordingConversation, state: RecordingsState, expanded: Boolean,
    enabled: Boolean, onToggle: () -> Unit, onSelect: (String) -> Unit, detail: @Composable (Recording) -> Unit) {
    OutlinedCard(Modifier.fillMaxWidth().testTag("recording_conversation_${talk.key}")) {
        Column {
            Column(Modifier.fillMaxWidth().clickable(enabled = enabled, onClick = onToggle)
                .padding(16.dp)
                .testTag("recording_open_${talk.key}"), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(recordingSpan(talk, state.timezone), style = MaterialTheme.typography.titleMedium)
                val length = talk.endMillis - talk.startMillis
                val duration = if (length < 60_000) GeneratedStrings.Recordings.duration(maxOf(1, (length / 1000.0).roundToInt()).toString())
                    else GeneratedStrings.Recordings.minutes((length / 60_000.0).roundToInt().toString())
                val fragments = if (talk.fragments.size > 1)
                    " · ${localized(GeneratedStrings.Recordings.fragments(talk.fragments.size.toDouble(), talk.fragments.size.toString()))}" else ""
                Text(localized(duration) + fragments, style = MaterialTheme.typography.bodySmall)
                if (talk.done != talk.fragments.size) {
                    val progress = if (talk.fragments.size - talk.done - talk.failed > 0)
                        GeneratedStrings.Recordings.Progress.pending(talk.done.toString(), talk.fragments.size.toString())
                    else GeneratedStrings.Recordings.Progress.failed(talk.failed.toString(), talk.fragments.size.toString())
                    StatusMessage(progress, "recording_progress_${talk.key}")
                }
                if (talk.preview.isNotBlank()) Text(talk.preview, maxLines = 3)
            }
            if (expanded) talk.fragments.forEach { fragment ->
                val selected = state.selectedId == fragment.id
                if (talk.fragments.size > 1) Column(Modifier.fillMaxWidth().clickable(enabled = enabled) { onSelect(fragment.id) }
                    .padding(horizontal = 16.dp, vertical = 12.dp).testTag("recording_fragment_${fragment.id}")) {
                    Text(recordingFragmentSpan(fragment, state.timezone), style = MaterialTheme.typography.bodyMedium)
                    Text(localized(recordingTranscriptStatus(fragment.transcription.status)), style = MaterialTheme.typography.bodySmall)
                } else Text(recordingFragmentSpan(fragment, state.timezone), style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
                if (selected) Column(Modifier.fillMaxWidth().padding(16.dp).testTag("recording_detail_${fragment.id}"),
                    verticalArrangement = Arrangement.spacedBy(12.dp)) { detail(fragment) }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RecordingDetail(recording: Recording, state: RecordingsState, controller: RecordingsController,
    lease: SessionLease, visible: Boolean, playerRevision: Int, playbackAllowed: Boolean, onBeforePlayback: () -> Unit,
    confirmation: RecordingMutation?,
    onConfirmation: (RecordingMutation?) -> Unit, onRefresh: () -> Unit, onConfirm: (RecordingMutation) -> Unit) {
    val scope = rememberCoroutineScope()
    val enabled = !state.busy && !state.mutationUnknown
    if (state.detailError != GeneratedStrings.Recordings.gone)
        RecordingPlayer(controller, recording, lease, visible, playerRevision, enabled && playbackAllowed, onBeforePlayback, onRefresh)
    if (state.detailLoading && state.transcript == null)
        ProgressBar(modifier = Modifier.fillMaxWidth().testTag("recording_transcript_loading"))
    state.detailError?.let {
        ErrorMessage(it, "recording_detail_error")
        TextButton(onClick = { scope.launch { controller.select(recording.id) } },
            modifier = Modifier.heightIn(min = 48.dp).testTag("recording_detail_retry")) {
            Text(localized(GeneratedStrings.Common.retry))
        }
    }
    if (state.detailError == null) state.transcript?.let { transcript ->
        Text(localized(GeneratedStrings.Recordings.transcript), style = MaterialTheme.typography.titleSmall)
        if (transcript.status == TranscriptStatus.Ready) SelectionContainer {
            Text(transcript.text.orEmpty(), modifier = Modifier.testTag("recording_transcript"))
        } else StatusMessage(recordingTranscriptStatus(transcript.status), "recording_transcript_status")
        if (transcript.status == TranscriptStatus.Failed)
            ErrorMessage(recordingTranscriptError(transcript.errorCode), "recording_transcript_error")
        if (transcript.status in setOf(TranscriptStatus.Failed, TranscriptStatus.Empty) && confirmation == null)
            OutlinedButton(onClick = { onConfirmation(RecordingMutation.Retry) }, enabled = enabled,
                modifier = Modifier.heightIn(min = 48.dp).testTag("recording_retry")) { Text(localized(GeneratedStrings.Recordings.retry)) }
    }
    recording.expiresAt?.let { expires -> Text(localized(GeneratedStrings.Recordings.expires(recordingMoment(expires, state.timezone))),
        style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("recording_expires")) }
    confirmation?.let { kind ->
        Column(Modifier.testTag("recording_confirmation"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            val message = when {
                kind == RecordingMutation.Delete -> GeneratedStrings.Recordings.deleteConfirm
                state.transcript?.retryMayRepeat == true -> GeneratedStrings.Recordings.retryRepeatConfirm
                else -> GeneratedStrings.Recordings.retryConfirm
            }
            StatusMessage(message, "recording_confirmation_text")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = { onConfirm(kind) }, enabled = enabled,
                    modifier = Modifier.heightIn(min = 48.dp).testTag("recording_confirm")) {
                    Text(localized(if (kind == RecordingMutation.Delete) GeneratedStrings.Common.delete else GeneratedStrings.Recordings.retry),
                        color = if (kind == RecordingMutation.Delete) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
                }
                TextButton(onClick = { onConfirmation(null) }, enabled = !state.busy,
                    modifier = Modifier.heightIn(min = 48.dp).testTag("recording_cancel")) { Text(localized(GeneratedStrings.Common.cancel)) }
            }
        }
    }
    state.mutationError?.let {
        ErrorMessage(it, "recording_mutation_error")
        if (state.mutationUnknown) StatusMessage(GeneratedStrings.Recordings.mutationUnknown, "recording_mutation_unknown")
        OutlinedButton(onClick = onRefresh, enabled = !state.busy, modifier = Modifier.heightIn(min = 48.dp)
            .testTag("recording_mutation_refresh")) { Text(localized(GeneratedStrings.Recordings.refresh)) }
    }
    if (confirmation == null) TextButton(onClick = { onConfirmation(RecordingMutation.Delete) }, enabled = enabled,
        modifier = Modifier.heightIn(min = 48.dp).testTag("recording_delete")) {
        Text(localized(GeneratedStrings.Recordings.delete), color = MaterialTheme.colorScheme.error)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RecordingPlayer(controller: RecordingsController, recording: Recording, lease: SessionLease,
    visible: Boolean, revision: Int, enabled: Boolean, onBeforePlayback: () -> Unit, onRefresh: () -> Unit) {
    val context = LocalContext.current
    val player = remember(controller, lease, recording.id, revision) { AndroidRecordingPlayer(context) }
    val state by player.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var request by remember(player) { mutableStateOf<Job?>(null) }
    var compatible by remember(player) { mutableStateOf(false) }
    DisposableEffect(player, lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) { request?.cancel(); request = null; player.clear() }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer); request?.cancel(); player.close() }
    }
    LaunchedEffect(player, visible, enabled) {
        if (!visible || !enabled) { request?.cancel(); request = null; player.clear() }
    }
    val load: (Boolean) -> Unit = { fallback ->
        request?.cancel()
        onBeforePlayback()
        compatible = fallback
        player.loading()
        request = scope.launch {
            try {
                val audio = controller.playback(recording.id, fallback)
                if (audio == null) player.failed()
                else player.play(audio.bytes, canUseCompatible = !fallback)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { player.failed() }
        }
    }
    Column(Modifier.fillMaxWidth().testTag("recording_player"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (state.loading) ProgressBar(modifier = Modifier.fillMaxWidth().testTag("recording_player_loading"))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = {
                when { state.playing -> player.pause(); state.ready -> { onBeforePlayback(); player.resume() }; else -> load(compatible) }
            }, enabled = visible && enabled && !state.loading,
                modifier = Modifier.heightIn(min = 48.dp).testTag("recording_player_play")) {
                Text(if (state.playing) stringResource(R.string.recordings_player_pause) else localized(GeneratedStrings.Recordings.listen))
            }
            if (state.ready || state.loading) TextButton(onClick = { request?.cancel(); request = null; player.clear() },
                modifier = Modifier.heightIn(min = 48.dp).testTag("recording_player_stop")) {
                Text(stringResource(R.string.recordings_player_stop))
            }
        }
        if (state.ready) {
            val positionLabel = stringResource(R.string.recordings_player_position)
            Slider(value = state.positionMs.toFloat(), onValueChange = { player.seek(it.toInt()) },
                valueRange = 0f..state.durationMs.coerceAtLeast(1).toFloat(), enabled = visible && enabled,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("recording_player_seek")
                    .semantics { contentDescription = positionLabel })
            Text("${recordingClock(state.positionMs)} / ${recordingClock(state.durationMs)}",
                style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("recording_player_position"))
        }
        if (state.failed) {
            ErrorMessage(GeneratedStrings.Recordings.playbackFailed, "recording_player_error")
            if (state.canUseCompatible) OutlinedButton(onClick = { load(true) }, enabled = visible && enabled,
                modifier = Modifier.heightIn(min = 48.dp).testTag("recording_player_compatible")) {
                Text(localized(GeneratedStrings.Recordings.compatible))
            } else TextButton(onClick = onRefresh, modifier = Modifier.heightIn(min = 48.dp).testTag("recording_player_refresh")) {
                Text(localized(GeneratedStrings.Recordings.refresh))
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RecordingDateField(value: String, timezone: String, enabled: Boolean, onChange: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(localized(GeneratedStrings.Recordings.date), style = MaterialTheme.typography.labelLarge)
        OutlinedButton(onClick = { open = true }, enabled = enabled,
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("recordings_date")) {
            Text(if (value.isBlank()) localized(GeneratedStrings.Recordings.allDates) else recordingDayLabel(value))
        }
        if (recordingZone(timezone) != ZoneId.systemDefault())
            Text(localized(GeneratedStrings.Recordings.zone(timezone)), style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.testTag("recordings_zone"))
    }
    if (open) {
        val picker = rememberDatePickerState(initialSelectedDateMillis = runCatching {
            LocalDate.parse(value).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        }.getOrNull())
        DatePickerDialog(onDismissRequest = { open = false }, confirmButton = {
            TextButton(onClick = { picker.selectedDateMillis?.let {
                onChange(Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate().toString())
            }; open = false }) { Text(localized(GeneratedStrings.Common.save)) }
        }, dismissButton = {
            Row {
                TextButton(onClick = { onChange(""); open = false }, modifier = Modifier.testTag("recordings_all_dates")) {
                    Text(localized(GeneratedStrings.Recordings.allDates))
                }
                TextButton(onClick = { open = false }) { Text(localized(GeneratedStrings.Common.cancel)) }
            }
        }) { DatePicker(picker) }
    }
}

private val recordingLocale = Locale.forLanguageTag("ru")
private fun recordingZone(value: String): ZoneId = runCatching { ZoneId.of(value) }.getOrDefault(ZoneOffset.UTC)
private fun recordingDay(value: Long, timezone: String): String = Instant.ofEpochMilli(value).atZone(recordingZone(timezone)).toLocalDate().toString()
private fun recordingDayLabel(day: String): String = runCatching {
    LocalDate.parse(day).format(DateTimeFormatter.ofPattern("d MMMM yyyy", recordingLocale))
}.getOrDefault(day)
private fun recordingTime(value: Long, timezone: String, seconds: Boolean = false): String =
    Instant.ofEpochMilli(value).atZone(recordingZone(timezone))
        .format(DateTimeFormatter.ofPattern(if (seconds) "HH:mm:ss" else "HH:mm", recordingLocale))
private fun recordingSpan(talk: RecordingConversation, timezone: String): String {
    val from = recordingTime(talk.startMillis, timezone)
    val to = recordingTime(talk.endMillis, timezone)
    return if (from == to) from else "$from–$to"
}
@Composable
private fun recordingFragmentSpan(recording: Recording, timezone: String): String {
    val start = Instant.parse(recording.startedAt).toEpochMilli()
    val duration = NumberFormat.getNumberInstance(recordingLocale).apply { maximumFractionDigits = 1 }.format(recording.durationMs / 1000.0)
    return "${recordingTime(start, timezone, true)}–${recordingTime(start + recording.durationMs, timezone, true)} · ${localized(GeneratedStrings.Recordings.duration(duration))}"
}
private fun recordingMoment(value: String, timezone: String): String = runCatching {
    Instant.parse(value).atZone(recordingZone(timezone)).format(DateTimeFormatter.ofPattern("dd.MM.yyyy, HH:mm", recordingLocale))
}.getOrDefault(value)
private fun recordingClock(value: Int): String = "%d:%02d".format(Locale.ROOT, value / 60_000, value / 1000 % 60)
