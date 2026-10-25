package assistant.mobile.core.speech

import assistant.mobile.core.SessionLease
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

const val SPEECH_SEGMENT_MAX_BYTES = 10 * 1024 * 1024
const val SPEECH_LINE_MAX_BYTES = 1024 * 1024

data class SpeechAudioFormat(
    val contentType: String,
    val sampleRate: Int? = null,
    val channels: Int? = null,
)

data class SpeechAudioSegment(val format: SpeechAudioFormat, val audio: ByteArray) {
    override fun toString(): String = "SpeechAudioSegment(format=$format, audio=redacted)"
}

/** prepare pins this selection; every segment must echo its key and segment count. */
@Serializable
data class SpeechPlaybackPlan(
    @SerialName("cache_key") val cacheKey: String,
    val segments: Int,
    @SerialName("connection_id") val connectionId: String,
) {
    fun validate() {
        if (!validSpeechCacheKey(cacheKey) || segments !in 1..10_000 ||
            connectionId.isBlank() || connectionId.length > 200 || connectionId.any(Char::isISOControl))
            throw SpeechPlaybackFailure(SpeechPlaybackProblem.INVALID_RESPONSE)
    }
    override fun toString(): String = "SpeechPlaybackPlan(segments=$segments, selection=redacted)"
}

data class SpeechSegmentExpectation(val segment: Int, val segments: Int, val cacheKey: String?)

enum class SpeechPlaybackPhase { IDLE, PREPARING, LOADING, PLAYING, FAILED }

enum class SpeechPlaybackProblem {
    SESSION_EXPIRED, NO_ACCESS, NOT_CONFIGURED, AUTH_REQUIRED, APPLICATION_LIMIT, PROVIDER_LIMIT,
    BUSY, TIMEOUT, CONNECTION, PROVIDER_ERROR, SECRET_UNAVAILABLE, EXECUTION_DISABLED,
    INVALID_RESPONSE, CHANGED, PLAYBACK, AUDIO_FOCUS,
}

data class SpeechPlaybackState(
    val key: String? = null,
    val phase: SpeechPlaybackPhase = SpeechPlaybackPhase.IDLE,
    val problem: SpeechPlaybackProblem? = null,
    val segment: Int = 0,
    val segments: Int = 0,
) {
    val active: Boolean get() = phase in setOf(SpeechPlaybackPhase.PREPARING,
        SpeechPlaybackPhase.LOADING, SpeechPlaybackPhase.PLAYING)
    override fun toString(): String = "SpeechPlaybackState(phase=$phase, problem=$problem, segment=$segment, segments=$segments)"
}

/** Focus belongs to the whole playback. stop must immediately release focus and every output. */
interface SpeechAudioSink {
    suspend fun prepare(): Boolean
    suspend fun startPcm(format: SpeechAudioFormat)
    suspend fun writePcm(bytes: ByteArray)
    /** Waits for already submitted PCM to finish, without abandoning the playback's focus. */
    suspend fun finishPcm()
    /** Called only after done AND EOF, with the complete WAV/MP3 segment in memory. */
    suspend fun playEncoded(segment: SpeechAudioSegment)
    fun stop()
}

interface SpeechPlaybackTransport {
    suspend fun prepare(turnId: String, lease: SessionLease): SpeechPlaybackPlan
    suspend fun segment(
        turnId: String, playbackId: String, plan: SpeechPlaybackPlan, segment: Int, lease: SessionLease,
        onPcm: suspend (ByteArray, SpeechAudioFormat) -> Unit,
    ): SpeechAudioSegment
    suspend fun preview(
        connectionId: String, voice: String, model: String?, lease: SessionLease,
        onPcm: suspend (ByteArray, SpeechAudioFormat) -> Unit,
    ): SpeechAudioSegment
}

/** No response body, provider message, audio, key or parser detail escapes in an exception. */
class SpeechPlaybackFailure(val problem: SpeechPlaybackProblem) : Exception("Speech playback failed")

internal fun validSpeechCacheKey(key: String): Boolean =
    key.length == 64 && key.all { it in '0'..'9' || it in 'a'..'f' }

internal fun speechPlaybackProblem(code: String?): SpeechPlaybackProblem = when (code) {
    "not_configured" -> SpeechPlaybackProblem.NOT_CONFIGURED
    "no_access" -> SpeechPlaybackProblem.NO_ACCESS
    "auth_required" -> SpeechPlaybackProblem.AUTH_REQUIRED
    "session_expired" -> SpeechPlaybackProblem.SESSION_EXPIRED
    "application_limit" -> SpeechPlaybackProblem.APPLICATION_LIMIT
    "provider_limit" -> SpeechPlaybackProblem.PROVIDER_LIMIT
    "busy" -> SpeechPlaybackProblem.BUSY
    "timeout" -> SpeechPlaybackProblem.TIMEOUT
    "connection" -> SpeechPlaybackProblem.CONNECTION
    "secret_unavailable" -> SpeechPlaybackProblem.SECRET_UNAVAILABLE
    "execution_disabled" -> SpeechPlaybackProblem.EXECUTION_DISABLED
    else -> SpeechPlaybackProblem.PROVIDER_ERROR
}
