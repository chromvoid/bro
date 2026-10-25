package assistant.mobile.core.calls

import assistant.mobile.core.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.Clock
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

const val CALL_CONNECT_MS = 40_000L
const val CALL_NO_OFFER_MS = 20_000L
const val CALL_UNSTABLE_MS = 15_000L
const val CALL_GATHER_MS = 10_000L
const val CALL_RING_SAFETY_MS = 60_000L
const val CALL_ENDED_SHOW_MS = 3_000L
private const val REQUEST_RETRY_MS = 10_000L
private const val END_RETRY_MS = 60_000L

/** The app's one call, independent of navigation and confined to its original session lease. */
class CallsController(
    private val scope: CoroutineScope,
    private val sessions: SessionManager,
    private val api: CallApi,
    private val mediaFactory: CallMediaFactory,
    private val signalFactory: CallSignalFactory,
    private val onClaimAudio: () -> Unit = {},
    private val onReleaseAudio: () -> Unit = {},
    private val onKeepAwake: (Boolean) -> Unit = {},
    private val onCallFinished: () -> Unit = {},
    private val clockMs: () -> Long = { Clock.System.now().toEpochMilliseconds() },
) {
    constructor(
        scope: CoroutineScope, sessions: SessionManager, http: NativeHttpClient,
        mediaFactory: CallMediaFactory, signalFactory: CallSignalFactory,
        onClaimAudio: () -> Unit = {}, onReleaseAudio: () -> Unit = {},
        onKeepAwake: (Boolean) -> Unit = {}, onCallFinished: () -> Unit = {},
        clockMs: () -> Long = { Clock.System.now().toEpochMilliseconds() },
    ) : this(scope, sessions, NativeCallApi(http), mediaFactory, signalFactory,
        onClaimAudio, onReleaseAudio, onKeepAwake, onCallFinished, clockMs)

    private val mutableState = MutableStateFlow(CallsState())
    val state: StateFlow<CallsState> = mutableState.asStateFlow()
    val busy: Boolean get() = state.value.busy
    private val foreground = MutableStateFlow(false)
    private var lease: SessionLease? = null
    private var current: CallRecord? = null
    private var active: Run? = null
    private var finishedId: String? = null
    private var removal: Job? = null
    private var claimed = false
    private var closed = false
    private var readsSequence = 0L
    private val reads = Mutex()
    private val owner = scope.launch {
        combine(sessions.leases, sessions.identity, foreground) { value, identity, visible ->
            Triple(value, identity, visible)
        }.collectLatest { (value, identity, visible) ->
            bind(value)
            if (!enabled(identity)) {
                teardown()
            } else if (value != null && visible) refresh()
        }
    }

    fun activate() {
        if (closed) return
        bind(sessions.lease())
        if (foreground.value) scope.launch { refresh() }
        foreground.value = true
    }
    /** Incoming calls are foreground only. Existing capture also stops when the app leaves. */
    fun deactivate() {
        foreground.value = false
        teardown()
    }
    fun clearError() { mutableState.update { it.copy(errorKey = null, error = null, serverError = null) } }

    suspend fun refresh() = reads.withLock {
        val expected = sessions.lease() ?: return@withLock
        bind(expected)
        if (closed || !enabled(sessions.identity.value) || !foreground.value && active == null) return@withLock
        val sent = ++readsSequence
        try {
            val result = api.current(expected)
            if (!owned(expected)) return@withLock
            current = result
            val run = active
            if (run != null && run.knownAtSequence != null && sent > run.knownAtSequence!!) run.observe(result)
            render()
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { /* An event read failing must not terminate functioning media. */ }
    }
    suspend fun invalidate(invalidation: EventInvalidation) {
        if (invalidation is EventInvalidation.Domains && EventDomain.Calls !in invalidation.values) return
        refresh()
    }

    fun dial(channelId: String, peerName: String) {
        if (closed || channelId.isBlank()) return
        bind(sessions.lease())
        val expected = lease ?: return
        if (!enabled(sessions.identity.value) || active != null || current != null) return
        clearError()
        val run = Run(expected, CallRole.CALLER, peerName)
        active = run
        render()
        run.launchDial(channelId)
    }
    fun answer() {
        bind(sessions.lease())
        val expected = lease ?: return
        val call = current?.takeIf { it.role == CallRole.CALLEE && it.state == CallState.RINGING } ?: return
        if (closed || active != null || !enabled(sessions.identity.value)) return
        clearError()
        val run = Run(expected, CallRole.CALLEE, call.peer.name)
        run.identify(call)
        active = run
        render()
        run.launchAnswer()
    }
    fun decline() {
        val call = current?.takeIf { it.state == CallState.RINGING && it.role == CallRole.CALLEE } ?: return
        endFromHere(call, CallEndBody(CallEndReason.DECLINED))
    }
    fun hangUp() {
        active?.let { it.end(CallEndReason.HANGUP); return }
        current?.let { endFromHere(it, CallEndBody()) }
    }
    fun toggleMute() { active?.toggleMute() }
    fun setRoute(route: CallAudioRoute) { active?.setRoute(route) }
    fun dismiss() {
        if (active?.view?.phase != CallPhase.ENDED) return
        removal?.cancel(); removal = null
        active = null
        render()
    }
    /** Sign-out/revocation releases locally; socket presence remains the server's authority. */
    fun teardown() {
        removal?.cancel(); removal = null
        active?.teardown(); active = null
        current = null; finishedId = null
        mutableState.value = CallsState()
        setClaimed(false)
        onKeepAwake(false)
    }
    fun close() {
        closed = true
        owner.cancel()
        teardown()
    }

    private fun bind(value: SessionLease?) {
        if (lease === value) return
        teardown()
        lease = value
    }
    private fun owned(expected: SessionLease): Boolean = !closed && lease === expected && sessions.isCurrent(expected)
    private fun enabled(identity: NativeIdentity?): Boolean = identity?.scopes?.let {
        "calls.read" in it && "calls.write" in it
    } == true
    private fun render() {
        val old = mutableState.value
        val run = active
        val call = current?.takeUnless { it.id == finishedId || it.state == CallState.ENDED }
        val next = when {
            run != null -> old.copy(mode = CallOverlayMode.SESSION, call = run.call, view = run.view)
            call == null -> old.copy(mode = CallOverlayMode.NONE, call = null, view = null)
            call.state == CallState.RINGING && call.role == CallRole.CALLEE -> old.copy(mode = CallOverlayMode.INCOMING, call = call, view = null)
            else -> old.copy(mode = CallOverlayMode.ELSEWHERE, call = call, view = null)
        }
        mutableState.value = next
        setClaimed(next.mode == CallOverlayMode.INCOMING || next.mode == CallOverlayMode.SESSION && next.view?.phase != CallPhase.ENDED)
    }
    private fun setClaimed(value: Boolean) {
        if (claimed == value) return
        claimed = value
        if (value) onClaimAudio() else onReleaseAudio()
    }
    private fun endFromHere(call: CallRecord, body: CallEndBody) {
        val expected = lease ?: return
        finishedId = call.id
        render()
        scope.launch {
            try { api.end(call.id, body, expected) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                if (owned(expected)) { finishedId = null; failure(error) }
            } finally {
                if (owned(expected)) { onCallFinished(); refresh() }
            }
        }
    }
    private fun failure(error: Exception) {
        when (error) {
            is CallMediaFailure -> mutableState.update { it.copy(errorKey = when (error.problem) {
                CallMediaProblem.DENIED -> "calls.denied"
                CallMediaProblem.UNSUPPORTED -> "calls.unsupported"
                else -> "calls.unavailable"
            }) }
            is CallRequestFailure -> mutableState.update {
                val key = callErrorKey(error.code)
                it.copy(errorKey = key, error = if (key == null) nativeError(error.outcome) else null)
            }
            else -> mutableState.update { it.copy(error = nativeError(HttpOutcome.InvalidResponse)) }
        }
    }
    private fun ended(run: Run) {
        if (active !== run) return
        finishedId = run.call?.id
        render()
        onCallFinished()
        removal?.cancel()
        removal = scope.launch { delay(CALL_ENDED_SHOW_MS); if (active === run) dismiss() }
        scope.launch { refresh() }
    }
    private fun failedStart(run: Run, error: Exception) {
        if (active !== run || !owned(run.expected)) return
        if (error !is CallRequestFailure || error.code != "incoming") failure(error)
        active = null
        render()
        scope.launch { refresh() }
    }

    private inner class Run(val expected: SessionLease, val role: CallRole, peerName: String) {
        var view = CallView(role, if (role == CallRole.CALLER) CallPhase.OUTGOING else CallPhase.CONNECTING, peerName)
        var call: CallRecord? = null
        var knownAtSequence: Long? = null
        private var media: CallMedia? = null
        private var socket: CallSignal? = null
        private val timers = mutableMapOf<String, Job>()
        private var accepted = false
        private var present = false
        private var reopened = false
        private var offerStarted = false
        private var answering = false
        private var answerApplied = false
        private var localDescription: CallClientFrame? = null
        private var ice = "new"
        private var torn = false
        private var posted = false
        private var pending: CallEndBody? = null

        fun identify(value: CallRecord) { call = value; knownAtSequence = readsSequence; update { this } }
        fun launchDial(channelId: String) { scope.launch { dialing(channelId) } }
        fun launchAnswer() { scope.launch { answering() } }
        private suspend fun capture(): Boolean {
            if (torn || !owned(expected)) return false
            try {
                val acquired = mediaFactory.create(object : CallMediaListener {
                    override fun connection(state: CallMediaConnection) { scope.launch { this@Run.connection(state) } }
                    override fun route(route: CallAudioRoute, available: Set<CallAudioRoute>) {
                        scope.launch { if (!torn) update { copy(route = route, availableRoutes = available) } }
                    }
                })
                media = acquired
                acquired.acquire()
                if (torn || !owned(expected)) { acquired.close(); return false }
                acquired.setMuted(view.muted)
                onKeepAwake(true)
                return true
            } catch (cancelled: CancellationException) { release(); throw cancelled }
            catch (error: Exception) {
                if (!torn) { release(); failedStart(this, error) }
                return false
            }
        }
        @OptIn(ExperimentalUuidApi::class)
        private suspend fun dialing(channelId: String) {
            if (!capture()) return
            val key = Uuid.random().toString()
            val value = try { retried({ api.start(channelId, key, expected) }) { !torn || pending != null } }
            catch (cancelled: CancellationException) { release(); throw cancelled }
            catch (error: Exception) {
                if (!torn) { release(); failedStart(this, error) }
                return
            }
            identify(value)
            if (torn) { pending?.let(::post); return }
            if (value.state == CallState.ENDED) { posted = true; over(value.outcome); return }
            if (!configureIce()) return
            open()
            later("ring", CALL_RING_SAFETY_MS) { end(CallEndReason.HANGUP) }
        }
        private suspend fun answering() {
            val id = call?.id ?: return
            if (!capture()) return
            val value = try { retried({ api.accept(id, expected) }) { !torn } }
            catch (cancelled: CancellationException) { release(); throw cancelled }
            catch (error: Exception) {
                if (!torn) { release(); failedStart(this, error) }
                return
            }
            if (torn || !owned(expected)) return
            identify(value)
            if (value.state == CallState.ENDED) { posted = true; over(value.outcome); return }
            accepted = true
            if (!configureIce()) return
            update { copy(phase = CallPhase.CONNECTING) }
            open()
            later("offer", CALL_NO_OFFER_MS) { end(CallEndReason.NO_OFFER) }
            later("connect", CALL_CONNECT_MS) { end(CallEndReason.ICE_TIMEOUT) }
        }
        private suspend fun configureIce(): Boolean = try {
            val configuration = api.ice(call!!.id, expected)
            if (torn || !owned(expected)) false else { media!!.configure(configuration); true }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) {
            if (!torn) { failure(error); end(CallEndReason.ICE_FAILED) }
            false
        }
        private suspend fun <T> retried(request: suspend () -> T, going: () -> Boolean): T {
            var first = -1L
            var wait = 500L
            while (true) {
                try { return request() }
                catch (failure: CallRequestFailure) {
                    if (first < 0) first = clockMs()
                    if (!failure.transient || !going() || !owned(expected) || clockMs() + wait - first > REQUEST_RETRY_MS) throw failure
                    delay(wait)
                    if (!going() || !owned(expected)) throw failure
                    wait *= 2
                }
            }
        }
        private fun open() {
            if (torn || !owned(expected)) return
            try {
                socket = signalFactory.open(call!!.id, expected, object : CallSignalListener {
                    override fun opened() { scope.launch { this@Run.opened() } }
                    override fun disconnected() { scope.launch { if (!torn) update { copy(signal = false) } } }
                    override fun frame(frame: CallServerFrame) { scope.launch { this@Run.frame(frame) } }
                    override fun final(code: Int) { scope.launch {
                        if (torn) return@launch
                        if (code == 4000) { posted = true; release(); finish(null) }
                        else over(null)
                    } }
                })
            } catch (_: Exception) { end(CallEndReason.ICE_FAILED) }
        }
        private fun opened() {
            if (torn || !owned(expected)) return
            update { copy(signal = true) }
            present = false
            if (reopened) scope.launch {
                try { val value = api.current(expected); if (owned(expected)) observe(value) }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { /* Another event or a media deadline will settle this call. */ }
            }
            reopened = true
        }
        fun observe(value: CallRecord?) {
            if (torn || call == null) return
            if (value?.id != call!!.id) over(null)
            else if (value.state == CallState.ENDED) over(value.outcome)
        }
        private fun frame(value: CallServerFrame) {
            if (torn || !owned(expected)) return
            when (value) {
                is CallServerFrame.State -> {
                    if (value.state == CallState.ENDED) { over(value.outcome); return }
                    if (value.state == CallState.ACTIVE && role == CallRole.CALLER) {
                        if (!accepted) {
                            accepted = true; forget("ring")
                            later("connect", CALL_CONNECT_MS) { end(CallEndReason.ICE_TIMEOUT) }
                        }
                        offerWhenReady()
                    }
                }
                is CallServerFrame.Peer -> {
                    val returned = value.present && !present
                    present = value.present
                    if (returned && role == CallRole.CALLER) {
                        offerWhenReady()
                        if (!answerApplied) localDescription?.let { socket?.send(it) }
                    }
                }
                is CallServerFrame.Offer -> if (role == CallRole.CALLEE) answerOffer(value.sdp)
                is CallServerFrame.Answer -> if (role == CallRole.CALLER) applyAnswer(value.sdp)
                CallServerFrame.Pong, is CallServerFrame.Error -> Unit
            }
        }
        private fun offerWhenReady() {
            if (view.phase != CallPhase.OUTGOING || !accepted || !present || offerStarted) return
            offerStarted = true
            update { copy(phase = CallPhase.CONNECTING) }
            scope.launch {
                try {
                    ice = "checking"
                    val offer = media!!.createOffer()
                    if (torn || !owned(expected)) return@launch
                    val frame = CallClientFrame.Offer(offer)
                    encodeCallClientFrame(frame) // Check the same SDP bounds the server accepts.
                    localDescription = frame
                    socket?.send(frame)
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { end(CallEndReason.ICE_FAILED) }
            }
        }
        private fun answerOffer(sdp: String) {
            if (answering) { localDescription?.let { socket?.send(it) }; return }
            answering = true
            forget("offer")
            scope.launch {
                try {
                    ice = "checking"
                    val answer = media!!.answerOffer(sdp)
                    if (torn || !owned(expected)) return@launch
                    val frame = CallClientFrame.Answer(answer)
                    encodeCallClientFrame(frame)
                    localDescription = frame
                    socket?.send(frame)
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { end(CallEndReason.ICE_FAILED) }
            }
        }
        private fun applyAnswer(sdp: String) {
            if (localDescription !is CallClientFrame.Offer || answerApplied) return
            answerApplied = true
            scope.launch {
                try { media?.applyAnswer(sdp) }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { end(CallEndReason.ICE_FAILED) }
            }
        }
        private fun connection(value: CallMediaConnection) {
            if (torn || !owned(expected)) return
            when (value) {
                CallMediaConnection.CONNECTED -> {
                    ice = "connected"
                    if (view.phase == CallPhase.CONNECTING) {
                        forget("connect"); forget("offer")
                        update { copy(phase = CallPhase.CONNECTED, connectedAt = clockMs()) }
                    } else if (view.phase == CallPhase.UNSTABLE) {
                        forget("unstable"); update { copy(phase = CallPhase.CONNECTED) }
                    }
                }
                CallMediaConnection.DISCONNECTED -> if (view.phase == CallPhase.CONNECTED) {
                    update { copy(phase = CallPhase.UNSTABLE) }
                    later("unstable", CALL_UNSTABLE_MS) { end(CallEndReason.MEDIA_LOST) }
                }
                CallMediaConnection.FAILED, CallMediaConnection.CLOSED -> when (view.phase) {
                    CallPhase.OUTGOING, CallPhase.CONNECTING -> end(CallEndReason.ICE_FAILED)
                    CallPhase.CONNECTED, CallPhase.UNSTABLE -> end(CallEndReason.MEDIA_LOST)
                    else -> Unit
                }
                else -> Unit
            }
        }
        fun toggleMute() {
            if (torn) return
            val value = !view.muted
            media?.setMuted(value)
            update { copy(muted = value) }
        }
        fun setRoute(route: CallAudioRoute) {
            if (!torn && route in view.availableRoutes) media?.setRoute(route)
        }
        fun end(reason: CallEndReason) {
            if (torn) return
            val summary = CallSummary(ice = ice)
            val outcome = when {
                reason in setOf(CallEndReason.ICE_FAILED, CallEndReason.ICE_TIMEOUT, CallEndReason.NO_OFFER) -> CallOutcome.FAILED
                accepted -> CallOutcome.COMPLETED
                role == CallRole.CALLER -> CallOutcome.CANCELLED
                else -> CallOutcome.DECLINED
            }
            release(); finish(outcome); post(CallEndBody(reason, summary))
        }
        private fun over(outcome: CallOutcome?) {
            if (torn) return
            val summary = CallSummary(ice = ice)
            release(); finish(outcome); post(CallEndBody(summary = summary))
        }
        private fun finish(outcome: CallOutcome?) {
            update { copy(phase = CallPhase.ENDED, outcome = outcome, signal = false) }
            ended(this)
        }
        private fun post(body: CallEndBody) {
            if (posted || !owned(expected)) return
            val id = call?.id
            if (id == null) { pending = body; return }
            posted = true; pending = null
            scope.launch {
                val first = clockMs()
                var wait = 1_000L
                while (owned(expected)) {
                    try {
                        val result = api.end(id, body, expected)
                        if (owned(expected)) {
                            if (active === this@Run && result.outcome != null) update { copy(outcome = result.outcome) }
                            onCallFinished()
                            refresh()
                        }
                        return@launch
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (failure: CallRequestFailure) {
                        if (!failure.transient || clockMs() + wait - first > END_RETRY_MS) return@launch
                        delay(wait); wait = minOf(wait * 2, 10_000)
                    } catch (_: Exception) { return@launch }
                }
            }
        }
        fun teardown() { posted = true; pending = null; release() }
        private fun release() {
            if (torn) return
            torn = true
            timers.values.forEach { it.cancel() }; timers.clear()
            runCatching { media?.close() }
            runCatching { socket?.close() }
            onKeepAwake(false)
        }
        private fun update(block: CallView.() -> CallView) {
            view = view.block()
            if (active === this && owned(expected)) render()
        }
        private fun later(name: String, milliseconds: Long, action: () -> Unit) {
            forget(name)
            timers[name] = scope.launch { delay(milliseconds); timers.remove(name); if (!torn && owned(expected)) action() }
        }
        private fun forget(name: String) { timers.remove(name)?.cancel() }
    }
}
