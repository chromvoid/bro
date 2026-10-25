package assistant.mobile.core.calls

import assistant.mobile.core.*
import assistant.mobile.core.dictation.*
import io.ktor.client.engine.mock.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import kotlin.test.*

private const val OFFER = "v=0\r\na=candidate:1 1 udp 2122260223 192.168.50.2 5000 typ host\r\na=end-of-candidates\r\n"
private const val ANSWER = "v=0\r\na=candidate:2 1 udp 2122260223 192.168.50.3 5002 typ host\r\na=end-of-candidates\r\n"

@OptIn(ExperimentalCoroutinesApi::class)
class CallsControllerTest {
    @Test
    fun outgoingClaimsAudioBeforeCaptureAndOnlyOffersWhenAcceptedPeerIsPresent() = runTest {
        val f = CallsFixture(this)
        try {
            f.start()
            f.controller.dial("dm", "Synthetic peer")
            f.controller.dial("dm", "Synthetic peer")
            assertEquals(listOf("claim"), f.order)
            runCurrent()
            assertEquals(listOf("claim", "acquire", "start", "ice"), f.order)
            assertTrue(f.awake)
            assertEquals(1, f.api.startKeys.size)
            assertEquals(CallPhase.OUTGOING, f.view.phase)
            val socket = f.socket
            socket.listener.opened(); socket.frame(CallServerFrame.Peer(true))
            runCurrent()
            assertTrue(socket.sent.isEmpty())
            socket.frame(CallServerFrame.State(CallState.ACTIVE, null))
            runCurrent()
            assertEquals(listOf<CallClientFrame>(CallClientFrame.Offer(OFFER)), socket.sent)
            assertEquals(CallPhase.CONNECTING, f.view.phase)
            socket.frame(CallServerFrame.Answer(ANSWER)); socket.frame(CallServerFrame.Answer(ANSWER))
            runCurrent()
            assertEquals(listOf(ANSWER), f.media.appliedAnswers)
            f.media.listener.connection(CallMediaConnection.CONNECTED)
            runCurrent()
            assertEquals(CallPhase.CONNECTED, f.view.phase)
            assertNotNull(f.view.connectedAt)
        } finally { f.close() }
    }

    @Test
    fun incomingAnswerUsesServerIceAndRepeatsSameAnswerWithoutAnotherPeer() = runTest {
        val f = CallsFixture(this)
        try {
            f.api.currentRecord = f.api.record.copy(role = CallRole.CALLEE)
            f.start()
            assertEquals(CallOverlayMode.INCOMING, f.controller.state.value.mode)
            assertTrue(f.claimed)
            assertTrue(f.mediaInstances.isEmpty())
            f.controller.answer(); runCurrent()
            assertEquals(1, f.api.accepts)
            assertEquals(f.api.configuration, f.media.configuration)
            f.socket.frame(CallServerFrame.Offer(OFFER)); runCurrent()
            f.socket.frame(CallServerFrame.Offer(OFFER)); runCurrent()
            assertEquals(listOf(OFFER), f.media.receivedOffers)
            assertEquals(listOf<CallClientFrame>(CallClientFrame.Answer(ANSWER), CallClientFrame.Answer(ANSWER)), f.socket.sent)
            assertEquals(CallPhase.CONNECTING, f.view.phase)
        } finally { f.close() }
    }

    @Test
    fun microphoneFailureDoesNotStartOrAcceptServerCall() = runTest {
        val f = CallsFixture(this)
        try {
            f.acquireFailure = CallMediaFailure(CallMediaProblem.DENIED)
            f.start(); f.controller.dial("dm", "Synthetic peer"); runCurrent()
            assertEquals("calls.denied", f.controller.state.value.errorKey)
            assertTrue(f.api.startKeys.isEmpty())
            assertEquals(0, f.api.accepts)
            assertFalse(f.claimed)
            assertFalse(f.awake)
        } finally { f.close() }
    }

    @Test
    fun retryKeepsTheOriginalStartKeyAndHangupBeforeStartReplyIsCancelled() = runTest {
        val f = CallsFixture(this)
        try {
            f.api.startFailure = CallRequestFailure(HttpOutcome.NetworkFailure)
            val gate = CompletableDeferred<Unit>()
            f.api.startGate = gate
            f.start(); f.controller.dial("dm", "Synthetic peer"); runCurrent()
            advanceTimeBy(500); runCurrent()
            assertEquals(2, f.api.startKeys.size)
            assertEquals(1, f.api.startKeys.distinct().size)
            f.controller.hangUp()
            assertFalse(f.claimed)
            assertFalse(f.awake)
            gate.complete(Unit); runCurrent()
            assertEquals(1, f.api.ends.size)
            assertEquals(CallEndReason.HANGUP, f.api.ends.single().reason)
            assertEquals(1, f.media.closes)
            assertTrue(f.sockets.isEmpty())
        } finally { f.close() }
    }

    @Test
    fun endNetworkRetryDoesNotHoldAudioAndEndsExactlyOneServerCall() = runTest {
        val f = CallsFixture(this)
        try {
            f.connected()
            f.api.endFailures = 1
            f.controller.hangUp(); runCurrent()
            assertFalse(f.awake); assertFalse(f.claimed)
            assertEquals(1, f.media.closes)
            advanceTimeBy(1_000); runCurrent()
            assertEquals(2, f.api.ends.size)
            assertEquals(1, f.api.endedIds.distinct().size)
            assertEquals(f.api.ends.first(), f.api.ends.last())
        } finally { f.close() }
    }

    @Test
    fun logoutAndRevocationStopMediaAndIgnoreLatePeerCallbacks() = runTest {
        val f = CallsFixture(this)
        try {
            f.connected()
            f.sessions.logout(); runCurrent()
            assertEquals(CallOverlayMode.NONE, f.controller.state.value.mode)
            assertEquals(1, f.media.closes)
            assertEquals(1, f.socket.closes)
            assertFalse(f.claimed); assertFalse(f.awake)
            f.media.listener.connection(CallMediaConnection.CONNECTED)
            f.socket.frame(CallServerFrame.State(CallState.ACTIVE, null)); runCurrent()
            assertEquals(CallOverlayMode.NONE, f.controller.state.value.mode)
            assertTrue(f.api.ends.isEmpty())
        } finally { f.close() }
        val revoked = CallsFixture(this)
        try {
            revoked.connected()
            revoked.sessions.expire(assertNotNull(revoked.sessions.lease())); runCurrent()
            assertEquals(CallOverlayMode.NONE, revoked.controller.state.value.mode)
            assertEquals(1, revoked.media.closes)
            assertFalse(revoked.awake)
        } finally { revoked.close() }
    }

    @Test
    fun actualDictationCaptureIsInterruptedBlockedDuringCallAndWorksAfterEnd() = runTest {
        var interruptDictation: () -> Unit = {}
        val f = CallsFixture(this, onClaimExtra = { interruptDictation() })
        val source = CallDictationSource()
        lateinit var liveSocket: FakeLiveSocket
        val live = LiveConnection(backgroundScope, f.sessions, LiveSocketFactory { _, listener ->
            FakeLiveSocket(listener).also { liveSocket = it }
        })
        val dictation = DictationController(backgroundScope,
            DictationReadiness { DictationReadinessResult.Configured }, source, live,
            currentDraft = { DictationDraftSnapshot("", 0) }, applyDraft = {},
            isAudioAvailable = { !f.claimed },
            createSpeechDetector = { error("Speech detection is unavailable in this fixture") })
        interruptDictation = { dictation.interrupt(DictationProblem.AUDIO_FOCUS) }
        try {
            f.start()
            live.setForeground(true)
            liveSocket.open(); runCurrent()
            dictation.start(); runCurrent()
            liveSocket.ready(); runCurrent()
            assertTrue(source.capturing)
            f.controller.dial("dm", "Synthetic peer")
            assertFalse(source.capturing)
            assertEquals(DictationPhase.INTERRUPTED, dictation.state.value.phase)
            assertEquals(DictationProblem.AUDIO_FOCUS, dictation.state.value.problem)
            val prepares = source.prepares
            dictation.start(); runCurrent()
            assertEquals(prepares, source.prepares)
            f.controller.hangUp(); runCurrent()
            dictation.start(); runCurrent()
            liveSocket.ready(); runCurrent()
            assertEquals(prepares + 1, source.prepares)
            assertTrue(source.capturing)
            assertEquals(DictationPhase.RECORDING, dictation.state.value.phase)
        } finally { dictation.close(); live.close(); f.close() }
    }

    @Test
    fun mediaFocusFailureWhileStartIsPendingReleasesAndCancelsLateCall() = runTest {
        val f = CallsFixture(this)
        try {
            val gate = CompletableDeferred<Unit>()
            f.api.startGate = gate
            f.start(); f.controller.dial("dm", "Synthetic peer"); runCurrent()
            f.media.listener.connection(CallMediaConnection.FAILED); runCurrent()
            assertEquals(CallPhase.ENDED, f.view.phase)
            assertFalse(f.awake)
            gate.complete(Unit); runCurrent()
            assertEquals(CallEndReason.ICE_FAILED, f.api.ends.single().reason)
        } finally { f.close() }
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
private class CallsFixture(private val test: TestScope, private val onClaimExtra: () -> Unit = {}) {
    private val identity = NativeIdentity("caller", "caller", "Synthetic", "ru", "UTC", false, 1,
        "device", "2030-01-01T00:00:00Z", listOf("calls.read", "calls.write"))
    private val record = StoredSession(AccountNamespace("synthetic", "https://calls.invalid", identity.id), "synthetic-token", identity,
        "device", "native-session", identity.sessionExpiresAt)
    val sessions = SessionManager("synthetic", record.namespace.origin, CallTestSecrets(record), {}, { 1_000L + test.testScheduler.currentTime })
    private val http = NativeHttpClient(MockEngine { respond("{}") }, record.namespace.origin, sessions)
    val order = mutableListOf<String>()
    val api = FakeCallApi(order)
    val mediaInstances = mutableListOf<FakeCallMedia>()
    val media get() = mediaInstances.last()
    val sockets = mutableListOf<FakeCallSignal>()
    val socket get() = sockets.last()
    var acquireFailure: Exception? = null
    var claimed = false
    var awake = false
    var finished = 0
    val controller = CallsController(test.backgroundScope, sessions, api,
        CallMediaFactory { listener -> FakeCallMedia(listener, order, acquireFailure).also(mediaInstances::add) },
        CallSignalFactory { _, _, listener -> FakeCallSignal(listener).also(sockets::add) },
        onClaimAudio = { order += "claim"; claimed = true; onClaimExtra() }, onReleaseAudio = { order += "release"; claimed = false },
        onKeepAwake = { awake = it }, onCallFinished = { finished++ }, clockMs = { 1_000L + test.testScheduler.currentTime })
    val view get() = assertNotNull(controller.state.value.view)
    suspend fun start() { sessions.restore(); controller.activate(); test.runCurrent() }
    suspend fun offered() {
        start(); controller.dial("dm", "Synthetic peer"); test.runCurrent()
        socket.listener.opened()
        api.currentRecord = api.currentRecord?.copy(state = CallState.ACTIVE)
        socket.frame(CallServerFrame.State(CallState.ACTIVE, null))
        socket.frame(CallServerFrame.Peer(true)); test.runCurrent()
    }
    suspend fun connected() {
        offered()
        socket.frame(CallServerFrame.Answer(ANSWER)); test.runCurrent()
        media.listener.connection(CallMediaConnection.CONNECTED); test.runCurrent()
    }
    fun close() { controller.close(); http.close() }
}

private class FakeCallMedia(val listener: CallMediaListener, private val order: MutableList<String>, private val acquireFailure: Exception?) : CallMedia {
    var configuration: CallIceConfiguration? = null
    var mutedState = false
    var requestedRoute: CallAudioRoute? = null
    var offers = 0
    var closes = 0
    val appliedAnswers = mutableListOf<String>()
    val receivedOffers = mutableListOf<String>()
    override suspend fun acquire() { order += "acquire"; acquireFailure?.let { throw it } }
    override fun configure(configuration: CallIceConfiguration) { this.configuration = configuration }
    override suspend fun createOffer(): String { offers++; return OFFER }
    override suspend fun answerOffer(sdp: String): String { receivedOffers += sdp; return ANSWER }
    override suspend fun applyAnswer(sdp: String) { appliedAnswers += sdp }
    override fun setMuted(muted: Boolean) { this.mutedState = muted }
    override fun setRoute(route: CallAudioRoute) { requestedRoute = route }
    override fun close() { closes++ }
}
private class FakeCallSignal(val listener: CallSignalListener) : CallSignal {
    override val connected = true
    val sent = mutableListOf<CallClientFrame>()
    var closes = 0
    override fun send(frame: CallClientFrame): Boolean { sent += frame; return true }
    override fun close() { closes++ }
    fun frame(frame: CallServerFrame) { listener.frame(frame) }
}
private class FakeCallApi(private val order: MutableList<String>) : CallApi {
    val record = decodeCall(CALL_REPLY.encodeToByteArray())
    var currentRecord: CallRecord? = null
    var currentGate: CompletableDeferred<CallRecord?>? = null
    var startFailure: CallRequestFailure? = null
    var startGate: CompletableDeferred<Unit>? = null
    val startKeys = mutableListOf<String>()
    var accepts = 0
    var endFailures = 0
    val ends = mutableListOf<CallEndBody>()
    val endedIds = mutableListOf<String>()
    val configuration = CallIceConfiguration(listOf(CallIceServer(listOf("turn:relay.invalid"), "temporary", "synthetic")), CallIceTransportPolicy.RELAY)
    override suspend fun current(lease: SessionLease): CallRecord? {
        val gate = currentGate
        if (gate != null) { currentGate = null; return gate.await() }
        return currentRecord
    }
    override suspend fun start(channelId: String, key: String, lease: SessionLease): CallRecord {
        order += "start"; startKeys += key
        startFailure?.let { startFailure = null; throw it }
        startGate?.await()
        return record.copy(channelId = channelId).also { currentRecord = it }
    }
    override suspend fun accept(id: String, lease: SessionLease): CallRecord {
        accepts++
        return (currentRecord ?: record).copy(state = CallState.ACTIVE).also { currentRecord = it }
    }
    override suspend fun end(id: String, body: CallEndBody, lease: SessionLease): CallRecord {
        ends += body; endedIds += id
        if (endFailures > 0) { endFailures--; throw CallRequestFailure(HttpOutcome.NetworkFailure) }
        val ended = (currentRecord ?: record).copy(state = CallState.ENDED,
            outcome = if (currentRecord?.state == CallState.ENDED) currentRecord?.outcome else when (body.reason) {
                CallEndReason.DECLINED -> CallOutcome.DECLINED
                CallEndReason.ICE_FAILED, CallEndReason.ICE_TIMEOUT, CallEndReason.NO_OFFER -> CallOutcome.FAILED
                else -> if (currentRecord?.state == CallState.ACTIVE) CallOutcome.COMPLETED else CallOutcome.CANCELLED
            })
        currentRecord = null
        return ended
    }
    override suspend fun ice(id: String, lease: SessionLease): CallIceConfiguration { order += "ice"; return configuration }
}

private class CallDictationSource : DictationAudioSource {
    var capturing = false
    var prepares = 0
    override suspend fun prepare(): DictationPreparationResult { prepares++; return DictationPreparationResult.Ready }
    override fun start(onFrame: (ByteArray, Float) -> Unit, onInterrupted: (DictationProblem) -> Unit) { capturing = true }
    override fun stop() { capturing = false }
    override fun release() { capturing = false }
}
