package assistant.mobile.core.calls

import assistant.mobile.core.LocalizedString
import assistant.mobile.core.SessionLease
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
enum class CallState { @SerialName("ringing") RINGING, @SerialName("active") ACTIVE, @SerialName("ended") ENDED }
@Serializable
enum class CallRole { @SerialName("caller") CALLER, @SerialName("callee") CALLEE }
@Serializable
enum class CallOutcome {
    @SerialName("completed") COMPLETED, @SerialName("declined") DECLINED,
    @SerialName("cancelled") CANCELLED, @SerialName("missed") MISSED,
    @SerialName("failed") FAILED, @SerialName("lost") LOST,
}
@Serializable
data class CallPeer(val id: String, val name: String)
@Serializable
data class CallRecord(
    val id: String,
    @SerialName("channel_id") val channelId: String,
    val kind: String,
    val state: CallState,
    val outcome: CallOutcome?,
    val role: CallRole,
    val peer: CallPeer,
    @SerialName("created_at") val createdAt: String,
    @SerialName("answered_at") val answeredAt: String?,
    @SerialName("ended_at") val endedAt: String?,
)

enum class CallPhase { OUTGOING, CONNECTING, CONNECTED, UNSTABLE, ENDED }
enum class CallOverlayMode { NONE, INCOMING, ELSEWHERE, SESSION }
enum class CallAudioRoute { EARPIECE, SPEAKER, BLUETOOTH }
data class CallView(
    val role: CallRole,
    val phase: CallPhase,
    val peerName: String,
    val muted: Boolean = false,
    val route: CallAudioRoute = CallAudioRoute.EARPIECE,
    val availableRoutes: Set<CallAudioRoute> = setOf(CallAudioRoute.EARPIECE, CallAudioRoute.SPEAKER),
    val connectedAt: Long? = null,
    val outcome: CallOutcome? = null,
    val signal: Boolean = false,
)
data class CallsState(
    val mode: CallOverlayMode = CallOverlayMode.NONE,
    val call: CallRecord? = null,
    val view: CallView? = null,
    val errorKey: String? = null,
    val error: LocalizedString? = null,
    val serverError: String? = null,
) {
    val busy: Boolean get() = mode == CallOverlayMode.INCOMING || mode == CallOverlayMode.ELSEWHERE ||
        mode == CallOverlayMode.SESSION && view?.phase != CallPhase.ENDED
}

data class CallIceServer(val urls: List<String>, val username: String? = null, val credential: String? = null) {
    override fun toString(): String = "CallIceServer(credentials=redacted)"
}
enum class CallIceTransportPolicy { ALL, RELAY }
data class CallIceConfiguration(
    val servers: List<CallIceServer>,
    val transportPolicy: CallIceTransportPolicy = CallIceTransportPolicy.ALL,
) {
    override fun toString(): String = "CallIceConfiguration(servers=${servers.size}, transportPolicy=$transportPolicy)"
}

enum class CallMediaConnection { NEW, CONNECTING, CONNECTED, DISCONNECTED, FAILED, CLOSED }
enum class CallMediaProblem { DENIED, UNAVAILABLE, UNSUPPORTED, AUDIO_FOCUS }
class CallMediaFailure(val problem: CallMediaProblem) : Exception("Call media unavailable")
interface CallMediaListener {
    fun connection(state: CallMediaConnection)
    fun route(route: CallAudioRoute, available: Set<CallAudioRoute>) {}
}
fun interface CallMediaFactory { fun create(listener: CallMediaListener): CallMedia }
/** Capture precedes start/accept. SDP operations return complete gathered SDP, never trickle ICE. */
interface CallMedia {
    suspend fun acquire()
    fun configure(configuration: CallIceConfiguration)
    suspend fun createOffer(): String
    suspend fun answerOffer(sdp: String): String
    suspend fun applyAnswer(sdp: String)
    fun setMuted(muted: Boolean)
    fun setRoute(route: CallAudioRoute)
    /** Immediate and idempotent: stops microphone, peer, route and audio focus. */
    fun close()
}

sealed interface CallServerFrame {
    data class State(val state: CallState, val outcome: CallOutcome?) : CallServerFrame
    data class Peer(val present: Boolean) : CallServerFrame
    data class Offer(val sdp: String) : CallServerFrame { override fun toString() = "Offer(sdp=redacted)" }
    data class Answer(val sdp: String) : CallServerFrame { override fun toString() = "Answer(sdp=redacted)" }
    data object Pong : CallServerFrame
    data class Error(val code: String) : CallServerFrame
}
sealed interface CallClientFrame {
    data class Offer(val sdp: String) : CallClientFrame { override fun toString() = "Offer(sdp=redacted)" }
    data class Answer(val sdp: String) : CallClientFrame { override fun toString() = "Answer(sdp=redacted)" }
    data object Ping : CallClientFrame
}
interface CallSignalListener {
    fun opened()
    fun disconnected()
    fun frame(frame: CallServerFrame)
    /** 4000 replaces this socket, 4001 ends the call. */
    fun final(code: Int)
}
fun interface CallSignalFactory {
    /** Credentials are headers from this lease, never query parameters or persisted frames. */
    fun open(callId: String, lease: SessionLease, listener: CallSignalListener): CallSignal
}
interface CallSignal {
    val connected: Boolean
    /** False while disconnected; signalling frames are never queued for a later call. */
    fun send(frame: CallClientFrame): Boolean
    fun close()
}

@Serializable
enum class CallEndReason {
    @SerialName("hangup") HANGUP, @SerialName("declined") DECLINED,
    @SerialName("media_lost") MEDIA_LOST, @SerialName("ice_timeout") ICE_TIMEOUT,
    @SerialName("ice_failed") ICE_FAILED, @SerialName("no_offer") NO_OFFER,
}
@Serializable
data class CallSummary(val client: String = "android", val ice: String = "new")
@Serializable
data class CallEndBody(val reason: CallEndReason? = null, val summary: CallSummary? = null)

interface CallApi {
    suspend fun current(lease: SessionLease): CallRecord?
    suspend fun start(channelId: String, key: String, lease: SessionLease): CallRecord
    suspend fun accept(id: String, lease: SessionLease): CallRecord
    suspend fun end(id: String, body: CallEndBody, lease: SessionLease): CallRecord
    suspend fun ice(id: String, lease: SessionLease): CallIceConfiguration
}
