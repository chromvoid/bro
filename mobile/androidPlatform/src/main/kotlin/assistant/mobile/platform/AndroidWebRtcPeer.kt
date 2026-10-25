package assistant.mobile.platform

import android.content.Context
import android.media.AudioAttributes
import android.os.Handler
import android.os.Looper
import assistant.mobile.core.calls.CallIceConfiguration
import assistant.mobile.core.calls.CallIceTransportPolicy
import assistant.mobile.core.calls.CallMediaConnection
import assistant.mobile.core.calls.CallMediaListener
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import org.webrtc.AudioSource
import org.webrtc.AudioTrack
import org.webrtc.DataChannel
import org.webrtc.IceCandidate
import org.webrtc.Logging
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RTCStatsReport
import org.webrtc.RtpReceiver
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.audio.JavaAudioDeviceModule
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Audio-only native peer. SDP includes gathered ICE candidates, matching the existing WS. */
class AndroidWebRtcPeer(
    context: Context,
    private val listener: CallMediaListener,
    audioModule: JavaAudioDeviceModule? = null,
) : AutoCloseable {
    private val main = Handler(Looper.getMainLooper())
    private val descriptions = Mutex()
    private val gathered = CompletableDeferred<Unit>()
    private val module: JavaAudioDeviceModule
    private val factory: PeerConnectionFactory
    private val source: AudioSource
    private val track: AudioTrack
    private var configuration = CallIceConfiguration(emptyList())
    private var connection: PeerConnection? = null
    private var trackAdded = false
    @Volatile private var closed = false

    init {
        initialize(context)
        module = audioModule ?: JavaAudioDeviceModule.builder(context.applicationContext)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            .setEnableVolumeLogger(false)
            .setAudioRecordErrorCallback(object : JavaAudioDeviceModule.AudioRecordErrorCallback {
                override fun onWebRtcAudioRecordInitError(errorMessage: String) = failed()
                override fun onWebRtcAudioRecordStartError(
                    errorCode: JavaAudioDeviceModule.AudioRecordStartErrorCode,
                    errorMessage: String,
                ) = failed()
                override fun onWebRtcAudioRecordError(errorMessage: String) = failed()
            })
            .setAudioTrackErrorCallback(object : JavaAudioDeviceModule.AudioTrackErrorCallback {
                override fun onWebRtcAudioTrackInitError(errorMessage: String) = failed()
                override fun onWebRtcAudioTrackStartError(
                    errorCode: JavaAudioDeviceModule.AudioTrackStartErrorCode,
                    errorMessage: String,
                ) = failed()
                override fun onWebRtcAudioTrackError(errorMessage: String) = failed()
            })
            .createAudioDeviceModule()
        factory = PeerConnectionFactory.builder().setAudioDeviceModule(module).createPeerConnectionFactory()
        source = factory.createAudioSource(MediaConstraints())
        track = factory.createAudioTrack("call-audio", source)
    }

    fun configure(configuration: CallIceConfiguration) {
        check(connection == null && !closed)
        this.configuration = configuration
    }

    suspend fun createOffer(): String = descriptions.withLock {
        val peer = peer()
        addTrack(peer)
        setLocal(peer, createDescription { peer.createOffer(it, MediaConstraints()) })
        fullDescription(peer)
    }

    suspend fun answerOffer(sdp: String): String = descriptions.withLock {
        val peer = peer()
        setRemote(peer, SessionDescription(SessionDescription.Type.OFFER, sdp))
        // Reuse the remote offer's audio transceiver rather than adding a second m-line.
        addTrack(peer)
        setLocal(peer, createDescription { peer.createAnswer(it, MediaConstraints()) })
        fullDescription(peer)
    }

    suspend fun applyAnswer(sdp: String) = descriptions.withLock {
        val peer = checkNotNull(connection)
        check(!closed)
        setRemote(peer, SessionDescription(SessionDescription.Type.ANSWER, sdp))
    }

    fun setMuted(muted: Boolean) {
        if (!closed) track.setEnabled(!muted)
    }

    /** Counts only audio RTP, for a controlled native peer in instrumentation. No SDP or IPs. */
    suspend fun audioStats(): AndroidCallAudioStats = withTimeoutOrNull(2_000) {
        val peer = connection ?: return@withTimeoutOrNull AndroidCallAudioStats()
        suspendCancellableCoroutine { continuation ->
            peer.getStats { report ->
                if (continuation.isActive) continuation.resume(report.audioStats())
            }
        }
    } ?: AndroidCallAudioStats()

    override fun close() {
        if (closed) return
        closed = true
        gathered.complete(Unit)
        connection?.close()
        connection?.dispose()
        connection = null
        track.dispose()
        source.dispose()
        factory.dispose()
        module.release()
    }

    private fun peer(): PeerConnection {
        check(!closed)
        connection?.let { return it }
        val servers = configuration.servers.map { server ->
            PeerConnection.IceServer.builder(server.urls)
                .setUsername(server.username.orEmpty())
                .setPassword(server.credential.orEmpty())
                .createIceServer()
        }
        val rtc = PeerConnection.RTCConfiguration(servers).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            bundlePolicy = PeerConnection.BundlePolicy.MAXBUNDLE
            rtcpMuxPolicy = PeerConnection.RtcpMuxPolicy.REQUIRE
            iceCandidatePoolSize = 0
            iceTransportsType = if (configuration.transportPolicy == CallIceTransportPolicy.RELAY)
                PeerConnection.IceTransportsType.RELAY else PeerConnection.IceTransportsType.ALL
        }
        val observer = object : PeerConnection.Observer {
            override fun onSignalingChange(state: PeerConnection.SignalingState) = Unit
            override fun onIceConnectionChange(state: PeerConnection.IceConnectionState) = Unit
            override fun onIceConnectionReceivingChange(receiving: Boolean) = Unit
            override fun onIceGatheringChange(state: PeerConnection.IceGatheringState) {
                if (state == PeerConnection.IceGatheringState.COMPLETE) gathered.complete(Unit)
            }
            // The server accepts full descriptions, never separate trickle-candidate messages.
            override fun onIceCandidate(candidate: IceCandidate) = Unit
            override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>) = Unit
            override fun onAddStream(stream: MediaStream) = Unit
            override fun onRemoveStream(stream: MediaStream) = Unit
            override fun onDataChannel(channel: DataChannel) = Unit
            override fun onRenegotiationNeeded() = Unit
            override fun onAddTrack(receiver: RtpReceiver, mediaStreams: Array<out MediaStream>) {
                (receiver.track() as? AudioTrack)?.setEnabled(true)
            }
            override fun onConnectionChange(state: PeerConnection.PeerConnectionState) {
                val mapped = when (state) {
                    PeerConnection.PeerConnectionState.NEW -> CallMediaConnection.NEW
                    PeerConnection.PeerConnectionState.CONNECTING -> CallMediaConnection.CONNECTING
                    PeerConnection.PeerConnectionState.CONNECTED -> CallMediaConnection.CONNECTED
                    PeerConnection.PeerConnectionState.DISCONNECTED -> CallMediaConnection.DISCONNECTED
                    PeerConnection.PeerConnectionState.FAILED -> CallMediaConnection.FAILED
                    PeerConnection.PeerConnectionState.CLOSED -> CallMediaConnection.CLOSED
                }
                main.post { if (!closed) listener.connection(mapped) }
            }
        }
        return checkNotNull(factory.createPeerConnection(rtc, observer)).also { connection = it }
    }

    private fun addTrack(peer: PeerConnection) {
        if (trackAdded) return
        checkNotNull(peer.addTrack(track, listOf("call-stream")))
        trackAdded = true
    }

    private suspend fun fullDescription(peer: PeerConnection): String {
        if (peer.iceGatheringState() != PeerConnection.IceGatheringState.COMPLETE) {
            withTimeoutOrNull(10_000) { gathered.await() }
        }
        check(!closed)
        return checkNotNull(peer.localDescription).description
    }

    private suspend fun createDescription(create: (SdpObserver) -> Unit): SessionDescription =
        suspendCancellableCoroutine { continuation ->
            create(object : EmptySdpObserver() {
                override fun onCreateSuccess(description: SessionDescription) {
                    if (continuation.isActive) continuation.resume(description)
                }
                override fun onCreateFailure(error: String) {
                    if (continuation.isActive) continuation.resumeWithException(IllegalStateException("SDP create failed"))
                }
            })
        }

    private suspend fun setLocal(peer: PeerConnection, description: SessionDescription) =
        setDescription { peer.setLocalDescription(it, description) }

    private suspend fun setRemote(peer: PeerConnection, description: SessionDescription) =
        setDescription { peer.setRemoteDescription(it, description) }

    private suspend fun setDescription(set: (SdpObserver) -> Unit): Unit =
        suspendCancellableCoroutine { continuation ->
            set(object : EmptySdpObserver() {
                override fun onSetSuccess() {
                    if (continuation.isActive) continuation.resume(Unit)
                }
                override fun onSetFailure(error: String) {
                    if (continuation.isActive) continuation.resumeWithException(IllegalStateException("SDP set failed"))
                }
            })
        }

    private fun failed() { main.post { if (!closed) listener.connection(CallMediaConnection.FAILED) } }

    private open class EmptySdpObserver : SdpObserver {
        override fun onCreateSuccess(description: SessionDescription) = Unit
        override fun onSetSuccess() = Unit
        override fun onCreateFailure(error: String) = Unit
        override fun onSetFailure(error: String) = Unit
    }

    companion object {
        private var initialized = false

        /** Loads the packaged JNI, also used by the 16 KiB emulator smoke. */
        @Synchronized
        fun initialize(context: Context) {
            if (initialized) return
            PeerConnectionFactory.initialize(
                PeerConnectionFactory.InitializationOptions.builder(context.applicationContext)
                    .setEnableInternalTracer(false)
                    .createInitializationOptions(),
            )
            // Candidate addresses and raw SDP must never enter Android logs.
            Logging.enableLogToDebugOutput(Logging.Severity.LS_NONE)
            initialized = true
        }
    }
}

data class AndroidCallAudioStats(
    val sentPackets: Long = 0,
    val receivedPackets: Long = 0,
    val receivedBytes: Long = 0,
    val receivedEnergy: Double = 0.0,
)

private fun RTCStatsReport.audioStats(): AndroidCallAudioStats {
    var sent = 0L
    var received = 0L
    var bytes = 0L
    var energy = 0.0
    for (stat in statsMap.values) {
        val members = stat.members
        if ((members["kind"] ?: members["mediaType"]) != "audio") continue
        if (stat.type == "outbound-rtp") sent += (members["packetsSent"] as? Number)?.toLong() ?: 0L
        if (stat.type == "inbound-rtp") {
            received += (members["packetsReceived"] as? Number)?.toLong() ?: 0L
            bytes += (members["bytesReceived"] as? Number)?.toLong() ?: 0L
            energy += (members["totalAudioEnergy"] as? Number)?.toDouble() ?: 0.0
        }
    }
    return AndroidCallAudioStats(sent, received, bytes, energy)
}
