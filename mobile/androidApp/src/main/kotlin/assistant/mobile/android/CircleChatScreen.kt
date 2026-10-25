package assistant.mobile.android

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
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
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import assistant.mobile.core.GeneratedStrings
import assistant.mobile.core.SessionLease
import assistant.mobile.core.circle.ChannelKind
import assistant.mobile.core.circle.CircleController
import assistant.mobile.core.circle.CircleMessage
import assistant.mobile.core.circle.CircleState
import assistant.mobile.core.circle.ReportTarget
import assistant.mobile.core.journal.Submission
import assistant.mobile.core.journal.SubmissionState
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** A channel has a text-only, channel-scoped composer and its audience stays above the history. */
@Composable
internal fun CircleChatScreen(controller: CircleController, lease: SessionLease, channelId: String,
    onToCircle: () -> Unit, onToAssistant: () -> Unit, timezone: String = "UTC",
    onCall: ((String, String) -> Unit)? = null, callsBusy: Boolean = false) {
    val observed by controller.state.collectAsStateWithLifecycle()
    val state = observed?.takeIf { it.namespace == lease.namespace && it.channelId == channelId }
    val scope = rememberCoroutineScope()
    var asking by remember(lease.namespace, channelId) { mutableStateOf(false) }
    val list = rememberLazyListState()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var resumed by remember(lifecycle) { mutableStateOf(lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) }
    var visibleMarker by remember(lease.namespace, channelId) { mutableStateOf<String?>(null) }
    CircleLifecycle(controller, lease, channelId = channelId)
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, _ -> resumed = lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(list, channelId) {
        snapshotFlow { list.layoutInfo.visibleItemsInfo.firstOrNull { it.contentType == "read_marker" }?.key as? String }
            .collect { visibleMarker = it }
    }
    val latestId = state?.history?.data?.latestMessageId
    val gone = state?.gone == true
    val markerKey = latestId?.let { "read_marker:$channelId:$it" }
    val markerVisible = markerKey != null && visibleMarker == markerKey
    LaunchedEffect(channelId, latestId, visibleMarker, resumed, gone, state?.history?.data?.channelId) {
        if (state?.history?.data?.channelId == channelId && !gone && latestId != null)
            controller.markRead(channelId, latestId, markerVisible = markerVisible, resumed = resumed)
    }
    LaunchedEffect(latestId) {
        if (latestId != null && !gone) { withFrameNanos { }; list.scrollToItem(0) }
    }
    LaunchedEffect(gone) { if (gone) asking = false }
    Column(Modifier.fillMaxSize().testTag("screen_CircleChat")) {
        NativeTopBar(state?.audience?.title ?: localized(GeneratedStrings.Circle.chat))
        if (gone) { CircleGone(onToCircle, onToAssistant); return@Column }
        val audience = state?.audience
        val channel = state?.channel
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            audience?.let {
                Text(localized(if (it.kind == ChannelKind.Direct) GeneratedStrings.Circle.onlyYouAnd(it.title)
                    else GeneratedStrings.Circle.everyoneIn(it.title)), modifier = Modifier.testTag("circle_audience"))
            }
            if (channel?.kind == ChannelKind.Direct && channel.peerId != null && onCall != null) {
                OutlinedButton(onClick = { onCall(channelId, audience?.title.orEmpty()) }, enabled = !callsBusy,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("circle_call")) {
                    Text(localized(GeneratedStrings.Calls.callTo(audience?.title.orEmpty())))
                }
            }
            state?.channel?.audienceResourceId?.let { CircleReportButton(controller, lease, ReportTarget.Resource, it) }
        }
        if (state == null) StatusMessage(GeneratedStrings.State.loading, "circle_chat_loading")
        LazyColumn(state = list, reverseLayout = true, modifier = Modifier.weight(1f).fillMaxWidth().testTag("circle_messages"),
            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            state?.let { current -> current.pending.asReversed().forEach { entry ->
                item(key = "pending_${entry.key}") { CirclePending(entry, controller, current.busy) }
            } }
            if (markerKey != null) item(key = markerKey, contentType = "read_marker") {
                Box(Modifier.fillMaxWidth().height(1.dp).testTag("circle_read_marker"))
            }
            val messages = state?.history?.data?.messages.orEmpty()
            val byId = messages.associateBy { it.id }
            items(messages.asReversed(), key = { it.id }) { message ->
                CircleMessageRow(message, byId[message.replyToId], state, lease, controller, timezone)
            }
            if (messages.isEmpty() && state?.history?.loading == false && state.history.error == null && state.history.serverError == null)
                item(key = "empty") { Text(localized(GeneratedStrings.Circle.startTalking), modifier = Modifier.padding(vertical = 20.dp)) }
            if (state?.history?.data?.nextCursor != null) item(key = "earlier") {
                TextButton(onClick = { scope.launch { controller.loadEarlier() } }, enabled = state.history.loading != true,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("circle_load_earlier")) {
                    Text(localized(if (state.history.loading) GeneratedStrings.State.loading else GeneratedStrings.Common.more))
                }
            }
        }
        state?.let { current ->
            Surface(color = MaterialTheme.colorScheme.background, tonalElevation = 2.dp) {
                Column(Modifier.fillMaxWidth().imePadding().padding(horizontal = 20.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    CircleQueryStatus(current.history, "circle_history") { scope.launch { controller.refresh() } }
                    CircleError(current.error, current.serverError)
                    current.draft.replyToId?.let { id ->
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            val reply = current.history.data?.messages?.firstOrNull { it.id == id }
                            Text(reply?.text.orEmpty(), maxLines = 2, modifier = Modifier.weight(1f).testTag("circle_reply_preview"))
                            TextButton(onClick = { controller.replyTo(null) }, modifier = Modifier.heightIn(min = 48.dp).testTag("circle_cancel_reply")) {
                                Text(localized(GeneratedStrings.Circle.cancelReply))
                            }
                        }
                    }
                    OutlinedTextField(value = current.draft.text, onValueChange = { controller.updateDraft(it.take(4000)) }, minLines = 2, maxLines = 5,
                        label = { Text(localized(audience?.let { GeneratedStrings.Circle.messageTo(it.title) } ?: GeneratedStrings.Circle.message)) },
                        modifier = Modifier.fillMaxWidth().testTag("circle_composer"))
                    // Vertical actions remain reachable at the app's maximum 125% text size.
                    Button(onClick = { scope.launch { controller.send() } }, enabled = current.draft.text.isNotBlank() && !current.busy && current.pending.isEmpty() &&
                            current.audience != null && current.history.data?.channelId == channelId,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("circle_send")) { Text(localized(GeneratedStrings.Circle.send)) }
                    OutlinedButton(onClick = { asking = true }, enabled = current.draft.text.isNotBlank() && !current.busy && current.pending.isEmpty() &&
                            current.audience != null && current.history.data?.channelId == channelId,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("circle_ask_assistant")) { Text(localized(GeneratedStrings.Circle.askAssistant)) }
                }
            }
        }
    }
    if (asking && !gone) CircleSheet(localized(GeneratedStrings.Circle.askTitle), "circle_ask_dialog", onDismiss = { asking = false }) {
        val audience = state?.audience
        CircleNotice(if (audience?.kind == ChannelKind.Direct) GeneratedStrings.Circle.answerDirect(audience.title)
            else GeneratedStrings.Circle.answerSpace(audience?.title.orEmpty()), GeneratedStrings.Circle.answerText, "circle_ask_audience")
        Text(state?.draft?.text.orEmpty())
        CircleAction(if (audience?.kind == ChannelKind.Direct) GeneratedStrings.Circle.askHereDirect(audience.title)
            else GeneratedStrings.Circle.askHere, "circle_ask_confirm", state?.busy != true && audience != null) {
            scope.launch { asking = false; controller.send(assistant = true) }
        }
        TextButton(onClick = { asking = false; onToAssistant() }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("circle_ask_privately")) {
            Text(localized(GeneratedStrings.Circle.askPrivately))
        }
    }
}

@Composable
private fun CircleMessageRow(message: CircleMessage, reply: CircleMessage?, state: CircleState?, lease: SessionLease,
    controller: CircleController, timezone: String) {
    if (message.call != null) {
        Text("${circleCallLine(message, lease.namespace.userId)} · ${chatMessageMoment(message.createdAt, timezone, includeDate = true)}",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            modifier = Modifier.fillMaxWidth().testTag("circle_call_line_${message.id}"))
        return
    }
    val mine = message.actorUserId == lease.namespace.userId && message.author != "assistant"
    val author = when {
        message.author == "assistant" -> localized(GeneratedStrings.Circle.assistant)
        mine -> localized(GeneratedStrings.Circle.you)
        else -> message.name ?: localized(GeneratedStrings.Circle.participant)
    }
    Column(Modifier.fillMaxWidth().testTag("circle_message_${message.id}"),
        horizontalAlignment = if (mine) Alignment.End else Alignment.Start, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("$author · ${chatMessageMoment(message.createdAt, timezone, includeDate = true)}", style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Surface(shape = MaterialTheme.shapes.large,
            color = if (mine) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceVariant,
            contentColor = MaterialTheme.colorScheme.onSurface) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                reply?.text?.let { Text(it.take(80), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.testTag("circle_message_reply_${message.id}")); HorizontalDivider() }
                Text(message.text.orEmpty())
            }
        }
        TextButton(onClick = { controller.replyTo(message.id) }, modifier = Modifier.heightIn(min = 48.dp).testTag("circle_reply_${message.id}")) {
            Text(localized(GeneratedStrings.Circle.reply))
        }
        if (state?.channel?.kind != ChannelKind.Personal && message.actorUserId != lease.namespace.userId)
            CircleReportButton(controller, lease, ReportTarget.Message, message.id)
    }
}

/** The caller sees an unanswered call; the other participant sees a missed one. */
@Composable
private fun circleCallLine(message: CircleMessage, me: String): String = when (message.call?.outcome) {
    "completed" -> localized(GeneratedStrings.Calls.Line.call(callDuration((message.call?.durationSeconds ?: 0).toLong())))
    "lost" -> localized(GeneratedStrings.Calls.Line.lost(callDuration((message.call?.durationSeconds ?: 0).toLong())))
    "declined" -> localized(GeneratedStrings.Calls.Line.declined)
    "missed", "cancelled" -> localized(if (message.actorUserId == me) GeneratedStrings.Calls.Line.missedOut else GeneratedStrings.Calls.Line.missedIn)
    else -> message.text.orEmpty()
}

@Composable
private fun CirclePending(entry: Submission, controller: CircleController, busy: Boolean) {
    val scope = rememberCoroutineScope()
    val text = remember(entry.key) { runCatching {
        Json.parseToJsonElement(entry.body.decodeToString()).jsonObject["text"]?.jsonPrimitive?.content.orEmpty()
    }.getOrDefault("") }
    Column(Modifier.fillMaxWidth().testTag("circle_pending_${entry.key}"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(text)
        if (entry.state == SubmissionState.UNCERTAIN) {
            CircleHeading(GeneratedStrings.Outcome.unknownTitle)
            Text(localized(GeneratedStrings.Circle.unsentText))
            CircleAction(GeneratedStrings.Outcome.check, "circle_pending_check_${entry.key}", !busy) { scope.launch { controller.checkMessage(entry.key) } }
            CircleAction(GeneratedStrings.Circle.dontSend, "circle_pending_forget_${entry.key}", !busy) { scope.launch { controller.forgetMessage(entry.key) } }
        } else if (entry.state == SubmissionState.REJECTED) {
            ErrorMessage(GeneratedStrings.Errors.unknown, "circle_pending_rejected_${entry.key}")
            CircleAction(GeneratedStrings.Circle.dontSend, "circle_pending_forget_${entry.key}", !busy) { scope.launch { controller.forgetMessage(entry.key) } }
        } else {
            StatusMessage(GeneratedStrings.State.loading, "circle_pending_sending_${entry.key}")
            if (entry.state == SubmissionState.PREPARED) CircleAction(GeneratedStrings.Circle.dontSend,
                "circle_pending_forget_${entry.key}", !busy) { scope.launch { controller.forgetMessage(entry.key) } }
        }
    }
}
