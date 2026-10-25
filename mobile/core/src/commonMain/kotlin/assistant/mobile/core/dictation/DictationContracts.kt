package assistant.mobile.core.dictation

import assistant.mobile.core.NativeJson
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

const val DICTATION_SAMPLE_RATE = 16_000
const val DICTATION_BYTES_PER_SECOND = DICTATION_SAMPLE_RATE * 2
const val DICTATION_MAX_SECONDS = 120
const val DICTATION_MAX_FRAME_BYTES = DICTATION_BYTES_PER_SECOND
const val DICTATION_MAX_BACKLOG_BYTES = DICTATION_BYTES_PER_SECOND * 10L
const val DICTATION_MAX_BUFFER_BYTES = DICTATION_BYTES_PER_SECOND * 10L

enum class DictationPhase {
    IDLE, PERMISSION, RECORDING, FINALIZING, REVIEW, CANCELLED, INTERRUPTED, FAILED,
}

enum class DictationProblem {
    PERMISSION_DENIED, PERMISSION_REVOKED, UNAVAILABLE, DEVICE, BACKGROUNDED, AUDIO_FOCUS,
    CONNECTION, NOT_CONFIGURED, NO_ACCESS, AUTH_REQUIRED, SESSION_EXPIRED, APPLICATION_LIMIT,
    PROVIDER_LIMIT, TIMEOUT, PROVIDER_ERROR, INVALID_AUDIO, SECRET_UNAVAILABLE,
    EXECUTION_DISABLED, BUSY, LIMIT, TOO_LONG, SERVER, BACKLOG, NOT_RECOGNIZED,
}

data class DictationState(
    val phase: DictationPhase = DictationPhase.IDLE,
    val partial: String = "",
    val level: Float = 0f,
    val elapsedSeconds: Int = 0,
    val problem: DictationProblem? = null,
    val finalText: String? = null,
    val transcriptionId: String? = null,
    val draftPreserved: Boolean = false,
    val reachedLimit: Boolean = false,
    val canRetry: Boolean = false,
) {
    val active: Boolean get() = phase in setOf(DictationPhase.PERMISSION,
        DictationPhase.RECORDING, DictationPhase.FINALIZING)
    override fun toString(): String = "DictationState(phase=$phase, problem=$problem, content=redacted)"
}

sealed interface DictationReadinessResult {
    data object Configured : DictationReadinessResult
    data class Unavailable(val problem: DictationProblem) : DictationReadinessResult
}

fun interface DictationReadiness {
    suspend fun check(): DictationReadinessResult
}

enum class DictationPermissionResult { Granted, NotAnswered, Blocked }

sealed interface DictationPreparationResult {
    data object Ready : DictationPreparationResult
    data object NotAnswered : DictationPreparationResult
    data class Unavailable(val problem: DictationProblem) : DictationPreparationResult
}

/** Permission is requested by prepare; capture starts before server readiness. */
interface DictationAudioSource {
    suspend fun prepare(): DictationPreparationResult
    /** Callbacks run on the controller's dispatcher; PCM is mono PCM16LE at 16 kHz. */
    fun start(onFrame: (ByteArray, Float) -> Unit, onInterrupted: (DictationProblem) -> Unit)
    /** Immediately stops and releases capture; never waits for another read or frame boundary. */
    fun stop()
    /** Idempotent immediate abort, including while prepare is awaiting permission. */
    fun release()
}

/** One recording's local speech verdict. Inference must never block capture or the caller. */
interface DictationSpeechDetector {
    /** Enqueue immutable mono PCM16LE at 16 kHz without waiting for inference. */
    fun accept(pcm: ByteArray)
    /** Drain every queued frame off the UI thread and report at least 250 ms of speech. */
    suspend fun finish(): Boolean
    /** Drop queued audio and release the detector without waiting for inference. */
    fun cancel()
}

interface DictationSessionListener {
    fun text(value: String)
    fun failed()
}

/** A recording on the shared live connection; cancel leaves that connection open. */
interface DictationSession {
    val queuedBytes: Long
    fun sendPcm(bytes: ByteArray): Boolean
    fun finish(): Boolean
    /** Nonblocking cancellation of this recording only. */
    fun cancel()
}

/** No audio, transcript, credential, URL or user identifier is part of latency telemetry. */
data class DictationLatency(
    val stopToFinalMillis: Long,
    val stopToFinishMillis: Long,
    val finalDeliveryMillis: Long,
    val clientAddedMillis: Long,
)

@Serializable
internal data class DictationWireMessage(
    val type: String,
    val id: String? = null,
    val text: String? = null,
    @SerialName("sample_rate") val sampleRate: Int? = null,
    @SerialName("transcription_id") val transcriptionId: String? = null,
    val error: String? = null,
) {
    override fun toString(): String = "DictationWireMessage(type=$type, content=redacted)"
}

internal fun decodeDictationMessage(value: String): DictationWireMessage {
    require(value.length <= 32_000)
    return NativeJson.decodeFromString<DictationWireMessage>(value)
}

/** These are machine-defined server error codes; no transcript meaning is interpreted here. */
internal fun dictationServerProblem(code: String?): DictationProblem = when (code) {
    "transcript_too_long", "too_long" -> DictationProblem.TOO_LONG
    "asr_busy", "busy" -> DictationProblem.BUSY
    "limit_exceeded", "application_limit" -> DictationProblem.APPLICATION_LIMIT
    "not_configured" -> DictationProblem.NOT_CONFIGURED
    "no_access" -> DictationProblem.NO_ACCESS
    "auth_required" -> DictationProblem.AUTH_REQUIRED
    "session_expired" -> DictationProblem.SESSION_EXPIRED
    "provider_limit" -> DictationProblem.PROVIDER_LIMIT
    "timeout" -> DictationProblem.TIMEOUT
    "connection", "asr_interrupted" -> DictationProblem.CONNECTION
    "provider_error" -> DictationProblem.PROVIDER_ERROR
    "invalid_audio" -> DictationProblem.INVALID_AUDIO
    "secret_unavailable" -> DictationProblem.SECRET_UNAVAILABLE
    "execution_disabled" -> DictationProblem.EXECUTION_DISABLED
    else -> DictationProblem.SERVER
}
