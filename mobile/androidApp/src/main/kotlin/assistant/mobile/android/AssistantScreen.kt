package assistant.mobile.android

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.RowScope
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.sp
import assistant.mobile.design.ui.ChatBubble
import assistant.mobile.design.ui.ChatBubbleText
import assistant.mobile.design.ui.ChatBubbleAction
import assistant.mobile.design.ui.SwipeToReply
import assistant.mobile.design.ui.StatusTone
import assistant.mobile.design.ui.contentColor
import assistant.mobile.design.ui.containerColor
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.SuggestionChipDefaults
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import assistant.mobile.core.dictation.DictationPhase
import assistant.mobile.design.ui.ActionIconButton
import assistant.mobile.design.ui.ComposerActionSwitch
import assistant.mobile.design.ui.HoldToRecordButton
import assistant.mobile.design.ui.SlideToCancelLabel
import assistant.mobile.design.ui.VoiceHoldPhase
import assistant.mobile.design.ui.VoiceHoldState
import assistant.mobile.design.ui.rememberVoiceHoldState
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.IntSize
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import assistant.mobile.core.GeneratedStrings
import assistant.mobile.core.SessionLease
import assistant.mobile.core.speech.SpeechPlaybackController
import assistant.mobile.core.dictation.DictationController
import assistant.mobile.core.photos.ImageAcquisition
import assistant.mobile.core.photos.PhotoNormalizer
import assistant.mobile.core.photos.PhotoAttachmentController
import assistant.mobile.core.photos.PhotoAttachmentStatus
import assistant.mobile.core.photos.PhotoAttachmentState
import assistant.mobile.core.photos.PrivateConversationImages
import assistant.mobile.core.assistant.AssistantActivityState
import assistant.mobile.core.assistant.AssistantActivityStepKind
import assistant.mobile.core.assistant.AssistantActivityStepStatus
import assistant.mobile.core.circle.CircleController
import assistant.mobile.core.circle.ReportTarget
import assistant.mobile.core.assistant.AssistantController
import assistant.mobile.core.assistant.AssistantMessage
import assistant.mobile.core.assistant.AssistantPendingSend
import assistant.mobile.core.assistant.AssistantPendingStatus
import assistant.mobile.core.assistant.AssistantReply
import assistant.mobile.core.assistant.AssistantState
import assistant.mobile.core.assistant.assistantPhaseLabel
import assistant.mobile.core.assistant.assistantStepLabel
import assistant.mobile.core.assistant.AssistantControlAction
import assistant.mobile.core.assistant.AssistantInteractionsState
import assistant.mobile.core.assistant.AssistantClarificationItem
import assistant.mobile.core.assistant.AssistantPendingControl
import assistant.mobile.core.assistant.AssistantSuggestionMode
import assistant.mobile.core.assistant.assistantSuggestionItems
import assistant.mobile.core.assistant.quietAssistantConversation
import assistant.mobile.core.assistant.availableControls
import assistant.mobile.core.assistant.historyAssistantClarification
import assistant.mobile.core.assistant.supportsClarification
import assistant.mobile.design.R as DesignR
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** The lazy viewport owns presentation; the account-scoped controller owns delivery and drafts. */
@Composable
internal fun AssistantScreen(
    controller: AssistantController,
    circle: CircleController,
    lease: SessionLease,
    dictation: DictationController,
    dictationPermission: DictationPermissionBridge,
    speech: SpeechPlaybackController,
    photos: PhotoAttachmentController,
    images: PrivateConversationImages,
    photoNormalizer: PhotoNormalizer,
    photoAcquisition: ImageAcquisition?,
    fileExport: FileExportBridge,
    onSpeechSettings: () -> Unit,
    modifier: Modifier = Modifier,
    timezone: String = "UTC",
    initialMessageId: String? = null,
    initialRecoveryKey: String? = null,
    dictationRequest: Long? = null,
    onDictationRequestHandled: (Long) -> Boolean = { true },
    onFixAccess: ((String) -> Unit)? = null,
    onOpenDictationSettings: (() -> Unit)? = null,
) {
    val context = LocalContext.current
    val observed by controller.state.collectAsStateWithLifecycle()
    val state = observed.takeIf { it.namespace == lease.namespace } ?: AssistantState()
    val observedInteractions by controller.interactions.state.collectAsStateWithLifecycle()
    val interactions = observedInteractions.takeIf { it.namespace == lease.namespace } ?: AssistantInteractionsState()
    val suggestions by controller.suggestions.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val dictationState by dictation.state.collectAsStateWithLifecycle()
    val observedPhoto by photos.state.collectAsStateWithLifecycle()
    val photo = observedPhoto.takeIf { it.namespace == lease.namespace } ?: PhotoAttachmentState()
    var photoSheetOpen by rememberSaveable(lease.namespace) { mutableStateOf(false) }
    var viewedPhoto by remember(lease.namespace) { mutableStateOf<String?>(null) }
    DictationLifecycle(dictation, dictationPermission)
    val owner = LocalLifecycleOwner.current
    val visibilityOwner = remember(controller, lease) { Any() }
    var searchOpen by remember(lease.namespace) { mutableStateOf(false) }
    var reportTarget by remember(lease.namespace) { mutableStateOf<String?>(null) }
    var reportSent by remember(lease.namespace) { mutableStateOf(false) }
    var clarificationTarget by remember(lease.namespace) { mutableStateOf<AssistantMessage?>(null) }
    var clarificationSending by remember(lease.namespace) { mutableStateOf(false) }
    val holdState = rememberVoiceHoldState()
    var sendDictationWhenReady by remember(lease.namespace) { mutableStateOf(false) }
    var ignoredHold by remember(lease.namespace) { mutableStateOf(false) }
    DisposableEffect(dictation, lease.namespace) {
        onDispose {
            sendDictationWhenReady = false
            ignoredHold = false
            holdState.reset()
        }
    }
    LaunchedEffect(dictationRequest, state.draftReady, owner) {
        val request = dictationRequest ?: return@LaunchedEffect
        if (!state.draftReady) return@LaunchedEffect
        owner.lifecycle.currentStateFlow.first { it.isAtLeast(Lifecycle.State.RESUMED) }
        if (onDictationRequestHandled(request)) dictation.start()
    }
    DisposableEffect(controller, lease, owner, initialMessageId) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> controller.activate(initialMessageId, visibilityOwner)
                Lifecycle.Event.ON_STOP -> controller.deactivate(visibilityOwner)
                else -> Unit
            }
        }
        owner.lifecycle.addObserver(observer)
        if (owner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) controller.activate(initialMessageId, visibilityOwner)
        onDispose {
            owner.lifecycle.removeObserver(observer)
            controller.deactivate(visibilityOwner)
        }
    }
    val list = rememberLazyListState()
    var anchoredNavigation by remember(lease.namespace) { mutableStateOf<Long?>(null) }
    var revealedNavigation by remember(lease.namespace) { mutableStateOf<Long?>(null) }
    val messages = state.messages
    val messageIds = remember(messages) { messages.mapTo(mutableSetOf()) { it.id } }
    LaunchedEffect(clarificationTarget?.id, messageIds) {
        if (clarificationTarget?.id?.let { it !in messageIds } == true) {
            clarificationTarget = null
        }
    }
    val orphanClarifications = interactions.clarifications.filter {
        (it.sourceMessageId ?: it.targetMessageId) !in messageIds && it.targetMessageId !in messageIds
    }
    val orphanControls = interactions.controls.filter { it.messageId !in messageIds }
    val newest = state.pending.lastOrNull()?.key ?: messages.lastOrNull()?.id
    LaunchedEffect(newest, messages.lastOrNull()?.turns, state.activity?.preview) {
        // NavDisplay and Scaffold may remember this effect during subcomposition in a measure.
        // A scroll forces remeasurement, so wait for the next frame before touching the list.
        withFrameNanos { }
        val current = controller.state.value
        if (current.namespace == lease.namespace && current.follow.following && !current.follow.held && current.anchorId == null) {
            list.scrollToItem(0)
        }
    }
    LaunchedEffect(state.navigationVersion, state.highlightedMessageId, messages) {
        val id = state.highlightedMessageId ?: return@LaunchedEffect
        if (anchoredNavigation == state.navigationVersion) return@LaunchedEffect
        val index = messages.asReversed().indexOfFirst { it.id == id || it.turns.any { turn -> turn.replyMessageId == id } }
        if (index >= 0) {
            withFrameNanos { }
            list.scrollToItem(index + state.pending.size + orphanClarifications.size + orphanControls.size + (if (state.after != null) 1 else 0))
            anchoredNavigation = state.navigationVersion
        }
    }
    var recoveryRevealed by rememberSaveable(lease.namespace, initialRecoveryKey) { mutableStateOf(false) }
    LaunchedEffect(initialRecoveryKey, state.pending, interactions.clarifications, interactions.controls, messages) {
        val recovery = initialRecoveryKey ?: return@LaunchedEffect
        if (recoveryRevealed) return@LaunchedEffect
        val pendingIndex = state.pending.asReversed().indexOfFirst { it.key == recovery }
        val clarificationIndex = orphanClarifications.indexOfFirst { it.key == recovery }
        val controlIndex = orphanControls.indexOfFirst { it.key == recovery }
        val messageIndex = messages.asReversed().indexOfFirst { message ->
            interactions.controls.any { it.key == recovery && it.messageId == message.id } ||
                interactions.clarifications.any { it.key == recovery &&
                    (it.sourceMessageId == message.id || it.targetMessageId == message.id) }
        }
        val index = when {
            pendingIndex >= 0 -> pendingIndex
            clarificationIndex >= 0 -> state.pending.size + clarificationIndex
            controlIndex >= 0 -> state.pending.size + orphanClarifications.size + controlIndex
            messageIndex >= 0 -> state.pending.size + orphanClarifications.size + orphanControls.size + messageIndex
            else -> return@LaunchedEffect
        }
        controller.holdSentMessage()
        withFrameNanos { }
        list.scrollToItem(index + if (state.after != null) 1 else 0)
        recoveryRevealed = true
    }
    LaunchedEffect(list, controller) {
        snapshotFlow { Triple(list.firstVisibleItemIndex, list.firstVisibleItemScrollOffset, list.isScrollInProgress) }
            .collect { (index, offset, scrolling) -> controller.readerPosition(index == 0 && offset < 48, userScrolled = scrolling) }
    }
    val openMessage: (String) -> Unit = { id -> scope.launch { controller.openMessage(id) } }
    val checkInteraction: (String) -> Unit = { key -> scope.launch { controller.interactions.check(key) } }
    val retryInteraction: (String) -> Unit = { key -> scope.launch { controller.interactions.retry(key) } }
    suspend fun sendDraftAndShow() {
        if (dictation.state.value.active) return
        dictation.reset()
        val previous = controller.state.value
        val target = clarificationTarget
        if (target != null) {
            if (clarificationSending || previous.draftText.isBlank()) return
            clarificationSending = true
            try {
                if (controller.interactions.clarify(target.id, previous.draftText, target.runGeneration)) {
                    clarificationTarget = null
                    if (controller.state.value.draftText == previous.draftText) controller.setDraft("")
                }
            } finally { clarificationSending = false }
            return
        }
        val pendingKeys = previous.pending.mapTo(mutableSetOf()) { it.key }
        val latestId = previous.messages.lastOrNull()?.id
        controller.holdSentMessage()
        val chosen = photos.state.value
        if (chosen.present && chosen.status != PhotoAttachmentStatus.Ready) return
        photos.sendWithAttachment(previous.draftImageId, controller::send)
        val current = controller.state.value
        val admittedPending = current.pending.any { it.key !in pendingKeys && it.status != AssistantPendingStatus.Rejected }
        val savedMessage = current.messages.lastOrNull()?.id != latestId && current.draftText.isEmpty()
        if (current.namespace == lease.namespace && (admittedPending || savedMessage)) list.scrollToItem(0)
    }
    fun cancelHeldDictation() {
        ignoredHold = false
        sendDictationWhenReady = false
        holdState.reset()
        dictation.cancel()
    }
    fun finishHeldDictation() {
        if (ignoredHold) {
            ignoredHold = false
            holdState.reset()
            return
        }
        val current = dictation.state.value
        if (current.phase == DictationPhase.FINALIZING) return
        if (current.phase != DictationPhase.RECORDING) {
            cancelHeldDictation()
        } else dictation.stop()
    }
    LaunchedEffect(dictationState.phase) {
        if (!dictationState.active) {
            val shouldSend = sendDictationWhenReady && dictationState.phase == DictationPhase.REVIEW &&
                !dictationState.draftPreserved && controller.state.value.draftText.isNotBlank()
            sendDictationWhenReady = false
            ignoredHold = false
            holdState.reset()
            if (shouldSend) scope.launch { sendDraftAndShow() }
        }
    }
    LaunchedEffect(list, controller) {
        snapshotFlow {
            val visible = list.layoutInfo.visibleItemsInfo
            val current = controller.state.value
            Triple(
                current.before?.takeIf { visible.any { item ->
                    val oldestVisible = current.messages.indexOfFirst { "message:${it.id}" == item.key }
                    oldestVisible in 0..4
                } },
                current.after?.takeIf {
                    (current.anchorId == null || anchoredNavigation == current.navigationVersion) &&
                        visible.any { it.key == "newer" }
                },
                current.loading,
            )
        }.collect { (older, newer, loading) ->
            if (!loading) {
                if (older != null) controller.loadOlder()
                if (newer != null) {
                    val previous = controller.state.value
                    // Keep the reader on a message instead of pinning the newer-page loader.
                    val anchor = list.layoutInfo.visibleItemsInfo.firstOrNull { item ->
                        previous.messages.any { "message:${it.id}" == item.key }
                    }
                    controller.loadNewer()
                    withFrameNanos { }
                    val current = controller.state.value
                    val atEdge = list.layoutInfo.visibleItemsInfo.any { it.key == "newer" } ||
                        current.after == null && list.firstVisibleItemIndex == 0
                    if (anchor != null && atEdge && current.navigationVersion == previous.navigationVersion) {
                        val index = current.messages.asReversed().indexOfFirst { "message:${it.id}" == anchor.key }
                        if (index >= 0) {
                            val ids = current.messages.mapTo(mutableSetOf()) { it.id }
                            val interactionsNow = controller.interactions.state.value
                            val beforeMessages = current.pending.size + interactionsNow.clarifications.count {
                                (it.sourceMessageId ?: it.targetMessageId) !in ids && it.targetMessageId !in ids
                            } + interactionsNow.controls.count { it.messageId !in ids } +
                                if (current.after != null) 1 else 0
                            list.scrollToItem(index + beforeMessages, -anchor.offset)
                        }
                    }
                }
            }
        }
    }
    Column(modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).testTag("screen_Assistant")) {
        AssistantTopBar(state.activity, state.activityMessageId != null,
            onSearch = { cancelHeldDictation(); searchOpen = true })
        AssistantAvailabilityNotice(state,
            onRefresh = { scope.launch { controller.refreshAvailability() } }, onFixAccess = onFixAccess)
        if (state.loading && messages.isEmpty()) Text(localized(GeneratedStrings.State.loading),
            modifier = Modifier.padding(horizontal = 16.dp).testTag("assistant_loading"))
        state.error?.let { error ->
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(localized(error), color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.weight(1f).testTag("assistant_error"))
                TextButton(onClick = { scope.launch { controller.refresh() } }, enabled = !state.loading,
                    modifier = Modifier.heightIn(min = 48.dp).testTag("assistant_refresh")) {
                    Text(localized(GeneratedStrings.Common.retry))
                }
            }
        }
        interactions.error?.let { ErrorMessage(it, "assistant_interaction_error") }
        if (reportSent) StatusMessage(GeneratedStrings.Moderation.sent, "assistant_report_sent")
        Box(Modifier.weight(1f).fillMaxWidth()) {
            LazyColumn(
                state = list, reverseLayout = true,
                contentPadding = PaddingValues(vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
                modifier = Modifier.fillMaxSize().testTag("assistant_history"),
            ) {
                if (state.after != null) item(key = "newer", contentType = "pagination") {
                    AssistantHistoryLoading(state.loadingNewer, "assistant_loading_newer")
                }
                items(state.pending.asReversed(), key = { "submission:${it.key}" }, contentType = { "submission" }) { pending ->
                    val previousPending = state.pending.indexOf(pending) > 0
                    val previousMessage = messages.lastOrNull()
                    PendingAssistantSend(pending, checking = pending.key in state.checking,
                        joinedToPrevious = previousPending || previousMessage?.let { it.replies.isEmpty() && (!it.pending || it.queued) } == true,
                        onCheck = { scope.launch { controller.check(pending.key) } },
                        onRetry = { scope.launch { controller.retry(pending.key) } })
                }
                items(orphanClarifications, key = { "clarification:${it.key}" }) { item ->
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        ChatBubble(outgoing = true) { Text(item.text) }
                        AssistantClarificationStatus(item, item.key in interactions.busy,
                            { checkInteraction(item.key) }, { retryInteraction(item.key) }, openMessage)
                    }
                }
                items(orphanControls, key = { "control:${it.key}" }) { item ->
                    AssistantControlStatus(item, item.key in interactions.busy,
                        { checkInteraction(item.key) }, { retryInteraction(item.key) })
                }
                items(messages.asReversed(), key = { "message:${it.id}" }, contentType = { "exchange" }) { message ->
                    val previous = messages.getOrNull(messages.indexOf(message) - 1)
                    val showDay = previous == null || chatMessageDay(previous.createdAt, timezone) != chatMessageDay(message.createdAt, timezone)
                    val joinedUser = !showDay && previous?.replies?.isEmpty() == true && (!previous.pending || previous.queued)
                    val ownClarifications = interactions.clarifications.filter {
                        it.sourceMessageId == message.id || it.sourceMessageId !in messageIds && it.targetMessageId == message.id
                    }
                    val historyClarification = historyAssistantClarification(message)
                    val clarifications = (ownClarifications + listOfNotNull(historyClarification))
                        .distinctBy { it.key }
                    AssistantExchange(message,
                        images = images, onOpenPhoto = { viewedPhoto = it },
                        highlightedMessageId = state.highlightedMessageId,
                        anchorNavigationVersion = state.navigationVersion.takeIf {
                            anchoredNavigation == it && revealedNavigation != it
                        },
                        onAnchorRevealed = { version ->
                            if (controller.state.value.navigationVersion == version) revealedNavigation = version
                        },
                        activity = state.activity.takeIf { state.activityMessageId == message.id },
                        movable = message.id in state.movableIds,
                        moving = message.id in state.moving || state.sending,
                        controls = interactions.controls.filter { it.messageId == message.id },
                        clarifications = clarifications, interactionBusy = interactions.busy,
                        onSendNow = { scope.launch { controller.sendNow(message.id) } },
                        onClarify = { cancelHeldDictation(); controller.clearReply(); clarificationTarget = message },
                        onControl = { action -> scope.launch { controller.interactions.control(message.id, action) } },
                        onCheck = checkInteraction, onRetry = retryInteraction, onOpen = openMessage,
                        onReply = { id, text -> clarificationTarget = null; controller.replyTo(id, text) },
                        onRead = { id -> if (!dictationState.active) speech.turn(id) },
                        onReport = { reportSent = false; reportTarget = it },
                        readEnabled = !dictationState.active, controller = controller, speech = speech, timezone = timezone,
                        showDay = showDay, joinedUser = joinedUser)
                }
                if (state.before != null) item(key = "earlier", contentType = "pagination") {
                    AssistantHistoryLoading(state.loadingOlder, "assistant_loading_older")
                }
                if (messages.isEmpty() && state.pending.isEmpty() && !state.loading) item(key = "empty") {
                    Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(localized(GeneratedStrings.Assistant.emptyTitle), style = MaterialTheme.typography.titleLarge)
                        Text(localized(GeneratedStrings.Assistant.emptyText))
                    }
                }
            }
            if (state.anchorId != null || !state.follow.following || state.follow.unseen > 0) {
                val label = localized(if (state.follow.unseen > 0)
                    GeneratedStrings.Assistant.newAnswers(state.follow.unseen.toDouble(), state.follow.unseen.toString())
                    else if (state.anchorId != null) GeneratedStrings.Assistant.latest else GeneratedStrings.Assistant.newMessages)
                BadgedBox(badge = {
                    if (state.follow.unseen > 0) Badge(containerColor = MaterialTheme.colorScheme.primary) {
                        Text(state.follow.unseen.toString())
                    }
                }, modifier = Modifier.align(Alignment.BottomEnd).padding(12.dp)) {
                    Surface(shape = CircleShape, color = MaterialTheme.colorScheme.surface,
                        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                        shadowElevation = 2.dp) {
                        IconButton(onClick = { scope.launch {
                            controller.latest(); list.scrollToItem(0); controller.readerPosition(true)
                        } }, modifier = Modifier.size(44.dp).testTag("assistant_new_below")) {
                            Icon(painterResource(DesignR.drawable.lucide_chevron_down), contentDescription = label,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(24.dp))
                        }
                    }
                }
            }
        }
        val showSuggestions = state.anchorId == null && state.draftReady && state.draftText.isBlank() && state.replyTarget == null &&
            state.pending.isEmpty() && !state.sending && !state.loading && state.activityMessageId == null &&
            clarificationTarget == null && !dictationState.active && !photo.present && state.draftImageId == null &&
            quietAssistantConversation(messages.lastOrNull()?.createdAt, suggestions.nowMillis)
        AssistantComposer(state, controller::setDraft,
            onRetryDraft = { scope.launch { controller.refresh() } },
            replyTarget = clarificationTarget?.let { it.id to it.text }
                ?: state.replyTarget?.let { it.messageId to it.preview },
            onClearReply = { clarificationTarget = null; controller.clearReply() }, onOpenReply = openMessage,
            sending = state.sending || clarificationSending, clarifying = clarificationTarget != null,
            dictation = dictation, holdState = holdState, heldDictation = sendDictationWhenReady,
            onHoldStart = {
                ignoredHold = dictation.state.value.active
                if (!ignoredHold) {
                    sendDictationWhenReady = true
                    dictation.start()
                    if (!dictation.state.value.active) cancelHeldDictation()
                }
            },
            onHoldRelease = ::finishHeldDictation,
            onHoldCancel = {
                if (ignoredHold) {
                    ignoredHold = false
                    holdState.reset()
                } else cancelHeldDictation()
            },
            onSpeechSettings = onSpeechSettings,
            onOpenDictationSettings = onOpenDictationSettings ?: { openDictationAppSettings(context) },
            photo = photo, restoredImageId = state.draftImageId, images = images,
            onPhoto = { photoSheetOpen = true },
            onRemovePhoto = { photos.cancel(); controller.setDraftImage(null) },
            onRetryPhoto = photos::retry,
            onOpenPhoto = { viewedPhoto = it },
            suggestions = {
                if (showSuggestions) {
                    val items = assistantSuggestionItems(suggestions, includeExamples = messages.isEmpty())
                    val prepared = items.map { it to (localized(it.text) + if (it.trailingSpace) " " else "") }.distinctBy { it.second }
                    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp)
                        .testTag("assistant_suggestions"), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        prepared.forEach { (item, text) ->
                            SuggestionChip(onClick = {
                                controller.setDraft(text)
                                if (item.mode == AssistantSuggestionMode.Ask) scope.launch { sendDraftAndShow() }
                            }, label = { Text(localized(item.label), style = MaterialTheme.typography.labelLarge) },
                                colors = SuggestionChipDefaults.suggestionChipColors(containerColor = MaterialTheme.colorScheme.surface),
                                border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant))
                        }
                    }
                }
            },
            onSend = { scope.launch { sendDraftAndShow() } })
    }
    if (searchOpen) AssistantSearchSheet(controller.search,
        onDismiss = { searchOpen = false; controller.search.close() },
        onPick = { id -> searchOpen = false; controller.search.close(); openMessage(id) })
    if (photoSheetOpen) PhotoAcquisitionSheet(
        acquisition = photoAcquisition,
        onDismiss = { photoSheetOpen = false },
        onChosen = { source ->
            photoSheetOpen = false
            controller.setDraftImage(null)
            photos.prepare(source, photoNormalizer)
        },
    )
    reportTarget?.let { id -> CircleReportSheet(circle, lease, ReportTarget.Message, id,
        onDismiss = { reportTarget = null }, onSent = { reportTarget = null; reportSent = true }) }
    viewedPhoto?.let { id -> AssistantPhotoViewer(id, images, fileExport, onDismiss = { viewedPhoto = null }) }
}

@Composable
private fun AssistantHistoryLoading(loading: Boolean, tag: String) {
    Box(Modifier.fillMaxWidth().height(if (loading) 48.dp else 1.dp), contentAlignment = Alignment.Center) {
        if (loading) CircularProgressIndicator(Modifier.size(24.dp).testTag(tag), strokeWidth = 2.dp)
    }
}

@Composable
private fun AssistantComposer(
    state: AssistantState,
    onDraft: (String) -> Unit,
    onRetryDraft: () -> Unit,
    replyTarget: Pair<String, String>?,
    onClearReply: () -> Unit,
    onOpenReply: (String) -> Unit,
    sending: Boolean,
    clarifying: Boolean,
    dictation: DictationController,
    holdState: VoiceHoldState,
    heldDictation: Boolean,
    onHoldStart: () -> Unit,
    onHoldRelease: () -> Unit,
    onHoldCancel: () -> Unit,
    onSpeechSettings: () -> Unit,
    onOpenDictationSettings: () -> Unit,
    photo: PhotoAttachmentState,
    restoredImageId: String?,
    images: PrivateConversationImages,
    onPhoto: () -> Unit,
    onRemovePhoto: () -> Unit,
    onRetryPhoto: () -> Unit,
    onOpenPhoto: (String) -> Unit,
    suggestions: @Composable () -> Unit,
    onSend: () -> Unit,
) {
    val dictationState by dictation.state.collectAsStateWithLifecycle()
    val keyboard = LocalSoftwareKeyboardController.current
    val focus = LocalFocusManager.current
    val colors = MaterialTheme.colorScheme
    val imeVisible = WindowInsets.ime.getBottom(LocalDensity.current) > 0
    var draftFocused by remember { mutableStateOf(false) }
    var imeWasVisible by remember { mutableStateOf(false) }
    LaunchedEffect(imeVisible, draftFocused) {
        // Dismissing the IME must let the next editor tap start a fresh input session.
        if (imeWasVisible && !imeVisible && draftFocused) focus.clearFocus()
        imeWasVisible = imeVisible
    }
    val recording = dictationState.active || heldDictation
    Column(Modifier.fillMaxWidth().background(colors.surface).navigationBarsPadding()) {
        HorizontalDivider(thickness = 1.dp, color = colors.outlineVariant)
        state.draftError?.let { ErrorMessage(it, "draft_error") }
        if (!state.draftReady && state.draftError != null) TextButton(onClick = onRetryDraft,
            modifier = Modifier.heightIn(min = 48.dp).testTag("draft_retry")) {
            Text(localized(GeneratedStrings.Common.retry))
        }
        suggestions()
        AssistantDictationFeedback(dictationState, onSpeechSettings, onOpenDictationSettings, dictation::retry)
        AssistantPhotoAttachment(photo, restoredImageId, images, onRemovePhoto, onRetryPhoto, onOpenPhoto)
        replyTarget?.let { (id, preview) ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Icon(painterResource(DesignR.drawable.lucide_reply), contentDescription = null,
                    tint = colors.primary, modifier = Modifier.padding(horizontal = 12.dp).size(20.dp))
                Box(Modifier.width(2.dp).height(32.dp).background(colors.primary))
                Text(localized(GeneratedStrings.Assistant.replyingTo(preview)),
                    modifier = Modifier.weight(1f).clickable { onOpenReply(id) }.padding(12.dp),
                    style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                CompositionLocalProvider(LocalContentColor provides colors.onSurfaceVariant) {
                    ActionIconButton(DesignR.drawable.lucide_x, localized(GeneratedStrings.Assistant.cancelReply),
                        onClearReply, testTag = "assistant_reply_cancel")
                }
            }
        }
        // Keep the trailing record button at this call site while the row changes around it.
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
            if (recording) {
                if (heldDictation) {
                    Row(Modifier.weight(1f).heightIn(min = 48.dp).padding(start = 12.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        AssistantRecordingStatus(dictationState)
                        Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                            if (holdState.phase == VoiceHoldPhase.Locked) {
                                TextButton(onClick = onHoldCancel, modifier = Modifier.testTag("assistant_dictation_cancel")) {
                                    Text(localized(GeneratedStrings.Common.cancel))
                                }
                            } else if (holdState.phase == VoiceHoldPhase.Held &&
                                dictationState.phase != DictationPhase.FINALIZING) {
                                SlideToCancelLabel(localized(GeneratedStrings.Common.cancel), { holdState.cancelProgress })
                            }
                        }
                    }
                } else {
                    CompositionLocalProvider(LocalContentColor provides colors.onSurfaceVariant) {
                        ActionIconButton(DesignR.drawable.lucide_x, localized(GeneratedStrings.Dictation.cancel),
                            dictation::cancel, testTag = "assistant_dictation_cancel")
                    }
                    Box(Modifier.weight(1f).heightIn(min = 48.dp), contentAlignment = Alignment.Center) {
                        AssistantRecordingStatus(dictationState)
                    }
                }
            } else {
                CompositionLocalProvider(LocalContentColor provides colors.onSurfaceVariant) {
                    ActionIconButton(DesignR.drawable.lucide_paperclip, localized(GeneratedStrings.Assistant.attachPhoto), {
                        focus.clearFocus(); keyboard?.hide(); onPhoto()
                    }, enabled = state.draftReady && !sending && !clarifying, testTag = "assistant_photo")
                }
                val label = localized(GeneratedStrings.Assistant.messageLabel)
                BasicTextField(value = state.draftText, onValueChange = onDraft,
                    enabled = state.draftReady,
                    textStyle = MaterialTheme.typography.bodyLarge.copy(color = colors.onSurface),
                    cursorBrush = SolidColor(colors.primary), minLines = 1, maxLines = 6,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Default),
                    modifier = Modifier.weight(1f).heightIn(min = 48.dp).testTag("draft")
                        .onFocusChanged { draftFocused = it.isFocused }
                        .semantics { contentDescription = label },
                    decorationBox = { inner ->
                        Box(Modifier.fillMaxWidth().padding(vertical = 12.dp), contentAlignment = Alignment.CenterStart) {
                            if (state.draftText.isEmpty()) Text(localized(GeneratedStrings.Assistant.placeholder),
                                color = colors.onSurfaceVariant, style = MaterialTheme.typography.bodyLarge)
                            inner()
                        }
                    })
            }
            ComposerActionSwitch(
                showSend = !recording && (state.draftText.isNotBlank() || photo.present || restoredImageId != null),
                send = {
                    CompositionLocalProvider(LocalContentColor provides colors.primary) {
                        ActionIconButton(DesignR.drawable.lucide_send_horizontal,
                            localized(if (sending) GeneratedStrings.Assistant.sendingNow else GeneratedStrings.Assistant.send),
                            onSend, enabled = state.draftReady && !sending &&
                                (if (clarifying) state.draftText.isNotBlank()
                                else !photo.present || photo.status == PhotoAttachmentStatus.Ready), testTag = "assistant_send")
                    }
                },
                record = {
                    Box {
                        val showHold = !recording || heldDictation && holdState.phase != VoiceHoldPhase.Locked
                        HoldToRecordButton(state = holdState, icon = DesignR.drawable.lucide_mic,
                            contentDescription = localized(GeneratedStrings.Dictation.start), onTap = dictation::start,
                            onHoldStart = onHoldStart, onRelease = onHoldRelease, onCancel = onHoldCancel, onLock = {},
                            enabled = state.draftReady && !sending && (!recording || heldDictation),
                            level = { dictationState.level },
                            testTag = if (showHold) "assistant_dictation_start" else null,
                            modifier = if (showHold) Modifier else Modifier.graphicsLayer { alpha = 0f }.clearAndSetSemantics { })
                        if (heldDictation && holdState.phase == VoiceHoldPhase.Locked) {
                            AssistantDictationAction(DesignR.drawable.lucide_send_horizontal,
                                localized(GeneratedStrings.Assistant.send), onHoldRelease,
                                enabled = dictationState.phase == DictationPhase.RECORDING,
                                modifier = Modifier.testTag("assistant_dictation_send"))
                        } else if (recording && !heldDictation) {
                            AssistantDictationAction(DesignR.drawable.lucide_check,
                                localized(GeneratedStrings.Dictation.stopDictation), dictation::stop,
                                enabled = dictationState.phase == DictationPhase.RECORDING,
                                modifier = Modifier.testTag("assistant_dictation_stop"))
                        }
                    }
                },
            )
        }
    }
}

private data class AssistantMessageAction(
    val label: String,
    val icon: Int,
    val tag: String,
    val enabled: Boolean = true,
    val run: () -> Unit,
)

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
private fun Modifier.assistantActions(label: String, actions: List<AssistantMessageAction>, open: () -> Unit): Modifier =
    combinedClickable(onClickLabel = label, onClick = open, onLongClick = open).semantics {
        customActions = actions.filter { it.enabled }.map { action ->
            CustomAccessibilityAction(action.label) { action.run(); true }
        }
    }

@Composable
private fun AssistantMessageMenu(expanded: Boolean, dismiss: () -> Unit, actions: List<AssistantMessageAction>) {
    DropdownMenu(expanded = expanded, onDismissRequest = dismiss) {
        actions.forEach { action ->
            DropdownMenuItem(text = { Text(action.label) },
                leadingIcon = { Icon(painterResource(action.icon), contentDescription = null, modifier = Modifier.size(if (action.icon == DesignR.drawable.ic_stop) 24.dp else 20.dp)) },
                onClick = { dismiss(); action.run() }, enabled = action.enabled,
                modifier = Modifier.heightIn(min = 48.dp).testTag(action.tag))
        }
    }
}

@Composable
private fun AssistantOutgoingMeta(label: String?, status: AssistantPendingStatus = AssistantPendingStatus.Delivered, queued: Boolean = false) {
    val color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.7f)
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
        if (label != null) Text(label, color = color,
            style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp, fontFeatureSettings = "tnum"))
        val icon = when {
            status == AssistantPendingStatus.Rejected -> DesignR.drawable.lucide_circle_alert
            queued || status == AssistantPendingStatus.Sending || status == AssistantPendingStatus.Uncertain -> DesignR.drawable.lucide_clock_3
            else -> DesignR.drawable.lucide_check
        }
        Icon(painterResource(icon), contentDescription = null,
            tint = if (status == AssistantPendingStatus.Rejected) MaterialTheme.colorScheme.error else color,
            modifier = Modifier.size(if (icon == DesignR.drawable.lucide_clock_3) 12.dp else 14.dp))
    }
}

@Composable
private fun PendingAssistantSend(pending: AssistantPendingSend, checking: Boolean, joinedToPrevious: Boolean,
    onCheck: () -> Unit, onRetry: () -> Unit) {
    val clipboard = LocalClipboardManager.current
    var menuOpen by remember(pending.key) { mutableStateOf(false) }
    val actions = listOf(AssistantMessageAction(localized(GeneratedStrings.Assistant.copy), DesignR.drawable.lucide_copy,
        "assistant_copy_${pending.key}") { clipboard.setText(AnnotatedString(pending.text)) })
    val label = localized(when (pending.status) {
        AssistantPendingStatus.Sending -> GeneratedStrings.Assistant.sendingNow
        AssistantPendingStatus.Delivered -> GeneratedStrings.Assistant.delivered
        AssistantPendingStatus.Uncertain -> GeneratedStrings.Outcome.unknownText
        AssistantPendingStatus.Rejected -> GeneratedStrings.Assistant.rejectedTitle
    })
    val you = localized(GeneratedStrings.Assistant.you)
    Box(Modifier.padding(horizontal = 16.dp).padding(top = if (joinedToPrevious) 0.dp else 6.dp)) {
        ChatBubble(outgoing = true, joinedToPrevious = joinedToPrevious, highlighted = menuOpen,
            modifier = Modifier.testTag("assistant_pending_${pending.key}").semantics { stateDescription = you; liveRegion = LiveRegionMode.Polite }
                .assistantActions(localized(GeneratedStrings.Assistant.answerActions), actions) { menuOpen = true },
            inlineActions = if (pending.canCheck || pending.canRetry) ({
                if (pending.canCheck) ChatBubbleAction(localized(GeneratedStrings.Assistant.pendingCheck), onCheck,
                    enabled = !checking, modifier = Modifier.testTag("assistant_pending_check_${pending.key}"))
                if (pending.canRetry) ChatBubbleAction(localized(GeneratedStrings.Assistant.pendingResend), onRetry,
                    enabled = !checking, modifier = Modifier.testTag("assistant_pending_retry_${pending.key}"))
            }) else null) {
            ChatBubbleText(pending.text, meta = { AssistantOutgoingMeta(label, pending.status) })
            AssistantMessageMenu(menuOpen, { menuOpen = false }, actions)
        }
    }
}

@Composable
private fun AssistantExchange(
    message: AssistantMessage,
    images: PrivateConversationImages,
    onOpenPhoto: (String) -> Unit,
    highlightedMessageId: String?,
    anchorNavigationVersion: Long?,
    onAnchorRevealed: (Long) -> Unit,
    activity: AssistantActivityState?,
    movable: Boolean,
    moving: Boolean,
    controls: List<AssistantPendingControl>,
    clarifications: List<AssistantClarificationItem>,
    interactionBusy: Set<String>,
    onSendNow: () -> Unit,
    onClarify: () -> Unit,
    onControl: (AssistantControlAction) -> Unit,
    onCheck: (String) -> Unit,
    onRetry: (String) -> Unit,
    onOpen: (String) -> Unit,
    onReply: (String, String) -> Unit,
    onRead: (String) -> Unit,
    onReport: (String) -> Unit,
    readEnabled: Boolean,
    controller: AssistantController,
    speech: SpeechPlaybackController,
    timezone: String,
    showDay: Boolean,
    joinedUser: Boolean,
) {
    val clipboard = LocalClipboardManager.current
    var menuOpen by remember(message.id) { mutableStateOf(false) }
    val userHighlighted = highlightedMessageId == message.id
    val highlighted = userHighlighted || message.replies.any { it.messageId != null && it.messageId == highlightedMessageId }
    val unresolved = controls.any { it.status in setOf(assistant.mobile.core.assistant.AssistantControlStatus.Sending,
        assistant.mobile.core.assistant.AssistantControlStatus.Unknown, assistant.mobile.core.assistant.AssistantControlStatus.Accepted) }
    val available = message.availableControls()
    val canStop = AssistantControlAction.Stop in available
    val canWithdraw = AssistantControlAction.Withdraw in available
    val stopping = message.stopRequested == true || controls.any { it.action == AssistantControlAction.Stop &&
        it.status in setOf(assistant.mobile.core.assistant.AssistantControlStatus.Sending, assistant.mobile.core.assistant.AssistantControlStatus.Accepted) }
    val actions = buildList {
        add(AssistantMessageAction(localized(GeneratedStrings.Assistant.copy), DesignR.drawable.lucide_copy,
            "assistant_copy_${message.id}") { clipboard.setText(AnnotatedString(message.text)) })
        if (message.supportsClarification()) add(AssistantMessageAction(localized(GeneratedStrings.Assistant.reply),
            DesignR.drawable.lucide_reply, "assistant_clarify_${message.id}", !unresolved, onClarify))
        if (message.queued) {
            if (movable) add(AssistantMessageAction(localized(GeneratedStrings.Assistant.Controls.sendNow),
                DesignR.drawable.lucide_send_horizontal, "assistant_send_now_menu_${message.id}", !moving && !unresolved, onSendNow))
            if (canWithdraw) add(AssistantMessageAction(localized(GeneratedStrings.Assistant.Controls.withdraw),
                DesignR.drawable.lucide_x, "assistant_withdraw_menu_${message.id}", !unresolved) { onControl(AssistantControlAction.Withdraw) })
        }
    }
    Column(Modifier.fillMaxWidth().padding(top = if (joinedUser) 0.dp else 6.dp)
        .testTag("assistant_message_${message.id}").semantics { selected = highlighted },
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (showDay) chatMessageDay(message.createdAt, timezone)?.let { day ->
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = CircleShape) {
                    Text(chatMessageDayLabel(day, timezone), modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                        style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        AssistantMessageAnchor(userHighlighted, anchorNavigationVersion.takeIf { userHighlighted }, onAnchorRevealed) {
            SwipeToReply(onReply = onClarify, enabled = message.supportsClarification() && !unresolved) {
                val you = localized(GeneratedStrings.Assistant.you)
                ChatBubble(outgoing = true, joinedToPrevious = joinedUser, highlighted = menuOpen,
                    modifier = Modifier.testTag("assistant_user_${message.id}").semantics { stateDescription = you }
                        .assistantActions(localized(GeneratedStrings.Assistant.answerActions), actions) { menuOpen = true },
                    inlineActions = if (message.queued && (movable || canWithdraw)) ({
                        if (movable) ChatBubbleAction(localized(GeneratedStrings.Assistant.Controls.sendNow), onSendNow,
                            enabled = !moving && !unresolved, modifier = Modifier.testTag("assistant_send_now_${message.id}"))
                        if (canWithdraw) ChatBubbleAction(localized(GeneratedStrings.Assistant.Controls.withdraw),
                            { onControl(AssistantControlAction.Withdraw) }, enabled = !unresolved,
                            modifier = Modifier.testTag("assistant_withdraw_${message.id}"))
                    }) else null) {
                    message.replyToMessageId?.let { source ->
                        TextButton(onClick = { onOpen(source) }, modifier = Modifier.heightIn(min = 48.dp)
                            .testTag("assistant_reply_origin_${message.id}")) { Text(localized(GeneratedStrings.Assistant.previousReply)) }
                    }
                    message.images.forEach { image -> AssistantPrivatePhoto(image.id, images, onClick = { onOpenPhoto(image.id) }) }
                    ChatBubbleText(message.text, meta = { AssistantOutgoingMeta(
                        if (message.queued) localized(GeneratedStrings.Assistant.Controls.queued) else chatMessageMoment(message.createdAt, timezone),
                        queued = message.queued) })
                    AssistantMessageMenu(menuOpen, { menuOpen = false }, actions)
                }
            }
        }
        controls.forEach { item -> AssistantControlStatus(item, item.key in interactionBusy, { onCheck(item.key) }, { onRetry(item.key) }) }
        clarifications.forEach { item ->
            if (item.sourceMessageId != message.id && item.text.isNotBlank()) Box(Modifier.padding(horizontal = 16.dp)) {
                ChatBubble(outgoing = true) { Text(item.text) }
            }
            AssistantClarificationStatus(item, item.key in interactionBusy, { onCheck(item.key) }, { onRetry(item.key) }, onOpen)
        }
        if (message.controlState == "settled" && controls.isEmpty()) {
            AssistantServiceLine(localized(if (message.controlAction == "withdraw") GeneratedStrings.Assistant.Controls.withdrawn else GeneratedStrings.Assistant.Controls.stopped))
            when (message.controlExecution?.effects) {
                "confirmed" -> AssistantServiceLine(localized(GeneratedStrings.Assistant.Controls.keptChanges))
                "unknown" -> AssistantServiceLine(localized(GeneratedStrings.Assistant.Controls.unknownChanges))
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            message.replies.forEachIndexed { index, reply ->
                val replyHighlighted = reply.messageId != null && reply.messageId == highlightedMessageId
                AssistantMessageAnchor(replyHighlighted, anchorNavigationVersion.takeIf { replyHighlighted }, onAnchorRevealed) {
                    SwipeToReply(onReply = { reply.messageId?.let { onReply(it, reply.text.ifBlank { reply.question.orEmpty() }) } },
                        enabled = reply.messageId != null) {
                        AssistantReplyBlock(reply,
                            onReply = { reply.messageId?.let { onReply(it, reply.text.ifBlank { reply.question.orEmpty() }) } },
                            onRead = { onRead(reply.turnId) }, onReport = onReport, readEnabled = readEnabled, controller = controller,
                            speech = speech, highlighted = replyHighlighted, joinedToPrevious = index > 0)
                    }
                }
            }
            if (message.pending && !message.queued) Box(Modifier.padding(horizontal = 16.dp)) {
                ChatBubble(outgoing = false, joinedToPrevious = message.replies.isNotEmpty(),
                    inlineActions = if (canStop || stopping) ({
                        ChatBubbleAction(localized(if (stopping) GeneratedStrings.Assistant.Controls.stopping else GeneratedStrings.Assistant.Controls.stop),
                            { onControl(AssistantControlAction.Stop) }, enabled = !unresolved && !stopping,
                            icon = DesignR.drawable.ic_stop, modifier = Modifier.testTag("assistant_stop_${message.id}"))
                    }) else null) { AssistantActivity(activity) }
            }
        }
        if (message.state == "failed" && message.replies.isEmpty()) Box(Modifier.padding(horizontal = 16.dp)) {
            ChatBubble(outgoing = false) {
                Text(localized(GeneratedStrings.Assistant.failed), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (!message.supportedState) AssistantServiceLine(localized(GeneratedStrings.Assistant.ResultActions.unavailable))
    }
}

@Composable
private fun AssistantServiceLine(text: String) {
    Text(text, Modifier.fillMaxWidth(), style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
}

/** The lazy row is materialized first; then the exact message's opening enters the viewport. */
@Composable
private fun AssistantMessageAnchor(
    highlighted: Boolean,
    navigationVersion: Long?,
    onRevealed: (Long) -> Unit,
    content: @Composable () -> Unit,
) {
    if (!highlighted) {
        Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) { content() }
        return
    }
    val requester = remember { BringIntoViewRequester() }
    var size by remember { mutableStateOf(IntSize.Zero) }
    val onRevealedNow by rememberUpdatedState(onRevealed)
    val openingHeight = with(LocalDensity.current) { 48.dp.toPx() }
    LaunchedEffect(navigationVersion, size) {
        val version = navigationVersion ?: return@LaunchedEffect
        if (size == IntSize.Zero) return@LaunchedEffect
        withFrameNanos { }
        requester.bringIntoView(Rect(0f, 0f, size.width.toFloat(), minOf(size.height.toFloat(), openingHeight)))
        onRevealedNow(version)
    }
    Column(Modifier.fillMaxWidth().bringIntoViewRequester(requester)
        .onGloballyPositioned { size = it.size }
        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f))
        .testTag("assistant_anchor_highlight")) {
        Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) { content() }
    }
}

@Composable
private fun AssistantActivity(activity: AssistantActivityState?) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (!activity?.preview.isNullOrEmpty()) AssistantMarkdown(activity!!.preview, modifier = Modifier.testTag("assistant_preview"))
        else Text(localized(activity?.let(::assistantPhaseLabel) ?: GeneratedStrings.Assistant.processing),
            color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.testTag("assistant_activity_phase").semantics { liveRegion = LiveRegionMode.Polite })
        val steps = remember(activity?.snapshot?.steps) {
            activity?.snapshot?.steps.orEmpty().filter { it.kind != AssistantActivityStepKind.Compaction }
        }
        if (steps.isNotEmpty()) Column(Modifier.testTag("assistant_activity_steps"), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            steps.forEach { step ->
                val status = when (step.status) {
                    AssistantActivityStepStatus.Running -> GeneratedStrings.Assistant.Activity.Step.running
                    AssistantActivityStepStatus.Completed -> GeneratedStrings.Assistant.Activity.Step.completed
                    AssistantActivityStepStatus.Failed -> GeneratedStrings.Assistant.Activity.Step.failed
                }
                Text("${localized(assistantStepLabel(step))} · ${localized(status)}",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        activity?.confirmed.orEmpty().forEach { confirmation ->
            Surface(color = StatusTone.Success.containerColor, contentColor = StatusTone.Success.contentColor, shape = RoundedCornerShape(10.dp)) {
                Text(confirmation.text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(8.dp)
                    .testTag("assistant_confirmed_${confirmation.id}"))
            }
        }
    }
}

@Composable
private fun AssistantReplyBlock(reply: AssistantReply, onReply: () -> Unit, onRead: () -> Unit,
    onReport: (String) -> Unit, readEnabled: Boolean,
    controller: AssistantController, speech: SpeechPlaybackController, highlighted: Boolean = false, joinedToPrevious: Boolean = false) {
    var menuOpen by remember(reply.turnId) { mutableStateOf(false) }
    var sourcesOpen by remember(reply.turnId) { mutableStateOf(false) }
    var detailsOpen by remember(reply.turnId) { mutableStateOf(false) }
    var confirming by remember(reply.turnId) { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current
    val observed by controller.state.collectAsStateWithLifecycle()
    val playback by speech.state.collectAsStateWithLifecycle()
    val playing = playback.active && playback.key == "turn:${reply.turnId}"
    val confirmText = localized(GeneratedStrings.Assistant.confirmText)
    val declineText = localized(GeneratedStrings.Assistant.declineText)
    val replyMessageId = reply.messageId
    val copyText = (listOf(reply.fallbackText ?: reply.text) + listOfNotNull(reply.question))
        .filter(String::isNotBlank).distinct().joinToString("\n\n")
    val actions = buildList {
        if (replyMessageId != null) add(AssistantMessageAction(localized(GeneratedStrings.Assistant.reply), DesignR.drawable.lucide_reply,
            "assistant_reply_to_$replyMessageId", run = onReply))
        add(AssistantMessageAction(localized(GeneratedStrings.Assistant.copy), DesignR.drawable.lucide_copy,
            "assistant_copy_${reply.turnId}") { clipboard.setText(AnnotatedString(copyText)) })
        if (reply.completed && reply.text.isNotBlank() && !reply.responseFailed && !reply.unsupported) {
            add(AssistantMessageAction(localized(if (playing) GeneratedStrings.SpeechPlayback.stop else GeneratedStrings.SpeechPlayback.play),
                if (playing) DesignR.drawable.ic_stop else DesignR.drawable.lucide_volume_2,
                "assistant_read_${reply.turnId}", playing || readEnabled) { if (playing) speech.stop() else onRead() })
        }
        if (reply.allowedLinks.isNotEmpty()) add(AssistantMessageAction("${localized(GeneratedStrings.Assistant.sources)} · ${reply.allowedLinks.size}",
            DesignR.drawable.lucide_library, "assistant_sources_${reply.turnId}") { sourcesOpen = true })
        if (replyMessageId != null && reply.completed) add(AssistantMessageAction(
            localized(GeneratedStrings.Moderation.report), DesignR.drawable.lucide_circle_alert,
            "assistant_report_${reply.turnId}") { onReport(replyMessageId) })
        add(AssistantMessageAction(localized(GeneratedStrings.Assistant.details), DesignR.drawable.lucide_info,
            "assistant_details_${reply.turnId}") { detailsOpen = true })
    }
    val canAccept = reply.completed && reply.text.isNotBlank() && !reply.responseFailed && !reply.unknownOutcome
    ChatBubble(outgoing = false, joinedToPrevious = joinedToPrevious, highlighted = menuOpen,
        modifier = Modifier.testTag("assistant_reply_${reply.turnId}").semantics { selected = highlighted }
            .assistantActions(localized(GeneratedStrings.Assistant.answerActions), actions) { menuOpen = true },
        inlineActions = if (reply.confirmationPending && replyMessageId != null) ({
            ChatBubbleAction(localized(GeneratedStrings.Assistant.confirm), {
                scope.launch { confirming = true; try { controller.sendReply(replyMessageId, confirmText) } finally { confirming = false } }
            }, enabled = !observed.sending && !confirming && canAccept, modifier = Modifier.testTag("assistant_confirm_${reply.turnId}"))
            ChatBubbleAction(localized(GeneratedStrings.Assistant.decline), {
                scope.launch { confirming = true; try { controller.sendReply(replyMessageId, declineText) } finally { confirming = false } }
            }, enabled = !observed.sending && !confirming, modifier = Modifier.testTag("assistant_decline_${reply.turnId}"))
        }) else if (reply.unknownOutcome) ({
            ChatBubbleAction(localized(GeneratedStrings.Assistant.pendingCheck), { detailsOpen = true },
                modifier = Modifier.testTag("assistant_pending_details_${reply.turnId}"))
        }) else null) {
        if (reply.unsupported) {
            (reply.fallbackText ?: reply.text).takeIf(String::isNotBlank)?.let { Text(it) }
            Text(localized(GeneratedStrings.Assistant.ResultActions.unavailable), color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else if (reply.text.isNotBlank()) AssistantMarkdown(reply.text, reply.allowedLinks, Modifier.testTag("assistant_reply_text_${reply.turnId}"))
        if (reply.confirmationPending) Text(localized(GeneratedStrings.Assistant.confirmTitle))
        reply.question?.takeIf(String::isNotBlank)?.let { Text(it) }
        val proposal = when (reply.proposalStatus) {
            "resolved" -> GeneratedStrings.Assistant.Proposal.resolved
            "cancelled" -> GeneratedStrings.Assistant.Proposal.cancelled
            "superseded" -> GeneratedStrings.Assistant.Proposal.superseded
            "expired" -> GeneratedStrings.Assistant.Proposal.expired
            else -> null
        }
        if (!reply.unsupported && proposal != null) Text(localized(proposal), color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (reply.responseFailed || reply.unknownOutcome || reply.execution?.completion == "failed") {
            when {
                reply.unknownOutcome -> Text(localized(GeneratedStrings.Assistant.resultUnknown), color = MaterialTheme.colorScheme.onSurfaceVariant)
                reply.responseFailed -> Text(localized(if (reply.committed) GeneratedStrings.Assistant.responseFailed else GeneratedStrings.Assistant.responseFailedText),
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (reply.execution?.effects == "none") Text(localized(GeneratedStrings.Assistant.noChanges), color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (reply.execution?.completion == "partial") Text(localized(GeneratedStrings.Assistant.partialResult), color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (reply.committed) Text(localized(GeneratedStrings.Assistant.confirmedChanges), color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        AssistantMessageMenu(menuOpen, { menuOpen = false }, actions)
    }
    if (sourcesOpen) AssistantSourcesSheet(reply) { sourcesOpen = false }
    if (detailsOpen) AssistantTurnDetailsSheet(reply.turnId, controller) { detailsOpen = false }
}
