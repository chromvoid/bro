package assistant.mobile.core.recordings

import assistant.mobile.core.AccountNamespace
import assistant.mobile.core.GeneratedStrings
import assistant.mobile.core.LocalizedString
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.plus
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.time.Instant

@Serializable
enum class TranscriptStatus {
    @SerialName("pending") Pending,
    @SerialName("processing") Processing,
    @SerialName("ready") Ready,
    @SerialName("empty") Empty,
    @SerialName("failed") Failed;
    val pending: Boolean get() = this == Pending || this == Processing
}

@Serializable
enum class RecordingFormat(val mime: String) {
    @SerialName("wav") Wav("audio/wav"),
    @SerialName("ogg-opus") OggOpus("audio/ogg"),
    @SerialName("aac-adts") AacAdts("audio/aac"),
}

@Serializable
data class RecordingSummary(
    val status: TranscriptStatus,
    val preview: String?,
    @SerialName("error_code") val errorCode: String?,
    @SerialName("updated_at") val updatedAt: String?,
    @SerialName("retry_may_repeat") val retryMayRepeat: Boolean,
)

@Serializable
data class Recording(
    val id: String,
    @SerialName("session_id") val sessionId: String,
    val sequence: Long,
    val sha256: String,
    val stored: Boolean,
    val format: RecordingFormat,
    val bytes: Long,
    @SerialName("duration_ms") val durationMs: Long,
    @SerialName("expires_at") val expiresAt: String?,
    @SerialName("started_at") val startedAt: String,
    @SerialName("received_at") val receivedAt: String,
    val state: String,
    val transcription: RecordingSummary,
) {
    override fun toString(): String = "Recording(id=$id, audio=redacted, transcription=redacted)"
}

@Serializable
data class RecordingTranscript(
    val id: String,
    val status: TranscriptStatus,
    val text: String?,
    @SerialName("error_code") val errorCode: String?,
    @SerialName("updated_at") val updatedAt: String?,
    @SerialName("retry_may_repeat") val retryMayRepeat: Boolean,
    val provider: String?,
    val model: String?,
    @SerialName("transcribed_at") val transcribedAt: String?,
) {
    override fun toString(): String = "RecordingTranscript(id=$id, status=$status, text=redacted)"
    fun summary(): RecordingSummary = RecordingSummary(status, text?.take(200), errorCode, updatedAt, retryMayRepeat)
}

@Serializable
data class RecordingPage(val recordings: List<Recording>, @SerialName("next_cursor") val nextCursor: String?)

@Serializable
data class RecordingLimits(@SerialName("retention_days") val retentionDays: Int?, @SerialName("max_user_bytes") val maxUserBytes: Long)

@Serializable
data class RecordingUsage(@SerialName("used_bytes") val usedBytes: Long, val limits: RecordingLimits)

enum class RecordingMutation { Delete, Retry }

/** Personal audio and text exist only while this account's archive is open. */
data class RecordingsState(
    val namespace: AccountNamespace,
    val timezone: String,
    val date: String = "",
    val items: List<Recording> = emptyList(),
    val nextCursor: String? = null,
    val loaded: Boolean = false,
    val loading: Boolean = false,
    val loadingMore: Boolean = false,
    val error: LocalizedString? = null,
    val pollError: LocalizedString? = null,
    val usage: RecordingUsage? = null,
    val usageError: LocalizedString? = null,
    val selectedId: String? = null,
    val transcript: RecordingTranscript? = null,
    val detailLoading: Boolean = false,
    val detailError: LocalizedString? = null,
    val busy: Boolean = false,
    val mutationError: LocalizedString? = null,
    val mutationUnknown: Boolean = false,
) {
    val hasPending: Boolean get() = items.any { it.transcription.status.pending }
}

/** The player owns these bytes in memory; no URL, credential or durable cache is exposed. */
class RecordingAudio(val bytes: ByteArray, val mime: String) {
    override fun toString(): String = "RecordingAudio(bytes=redacted)"
}

data class RecordingDateRange(val from: String? = null, val to: String? = null)

fun recordingDateRange(day: String, timezone: String): RecordingDateRange {
    val date = runCatching { LocalDate.parse(day) }.getOrNull() ?: return RecordingDateRange()
    val zone = runCatching { TimeZone.of(timezone) }.getOrDefault(TimeZone.UTC)
    return RecordingDateRange(date.atStartOfDayIn(zone).toString(), (date + DatePeriod(days = 1)).atStartOfDayIn(zone).toString())
}

const val RECORDING_CONVERSATION_GAP_MS = 120_000L

data class RecordingConversation(
    val key: String,
    val startMillis: Long,
    val endMillis: Long,
    val fragments: List<Recording>,
    val done: Int,
    val failed: Int,
    val preview: String,
)

/** Same-session fragments join only while the silence is strictly under two minutes. */
fun recordingConversations(items: List<Recording>): List<RecordingConversation> {
    fun start(item: Recording) = Instant.parse(item.startedAt).toEpochMilliseconds()
    fun end(item: Recording) = start(item) + item.durationMs
    val open = mutableMapOf<String, MutableList<Recording>>()
    val groups = mutableListOf<MutableList<Recording>>()
    items.sortedWith(compareBy<Recording> { start(it) }.thenBy { it.sequence }).forEach { item ->
        val group = open[item.sessionId]
        val previous = group?.lastOrNull()
        if (group != null && previous != null && start(item) - end(previous) < RECORDING_CONVERSATION_GAP_MS) group += item
        else mutableListOf(item).also { groups += it; open[item.sessionId] = it }
    }
    return groups.map { fragments -> RecordingConversation(
        fragments.first().id, start(fragments.first()), fragments.maxOf(::end), fragments,
        fragments.count { it.transcription.status in setOf(TranscriptStatus.Ready, TranscriptStatus.Empty) },
        fragments.count { it.transcription.status == TranscriptStatus.Failed },
        fragments.filter { it.transcription.status == TranscriptStatus.Ready }
            .mapNotNull { it.transcription.preview?.trim()?.takeIf(String::isNotEmpty) }.joinToString(" "),
    ) }.sortedByDescending { it.startMillis }
}

fun recordingTranscriptStatus(status: TranscriptStatus): LocalizedString = when (status) {
    TranscriptStatus.Pending -> GeneratedStrings.Recordings.Status.pending
    TranscriptStatus.Processing -> GeneratedStrings.Recordings.Status.processing
    TranscriptStatus.Ready -> GeneratedStrings.Recordings.Status.ready
    TranscriptStatus.Empty -> GeneratedStrings.Recordings.Status.empty
    TranscriptStatus.Failed -> GeneratedStrings.Recordings.Status.failed
}

fun recordingTranscriptError(code: String?): LocalizedString = when (code) {
    "not_configured", "auth_required", "secret_unavailable" -> GeneratedStrings.Recordings.Errors.connection
    "no_access", "execution_disabled", "session_expired" -> GeneratedStrings.Recordings.Errors.access
    "application_limit", "provider_limit" -> GeneratedStrings.Recordings.Errors.limit
    "unknown_outcome" -> GeneratedStrings.Recordings.Errors.unknownOutcome
    "invalid_audio", "decode_failed", "decoder_unavailable", "audio_missing" -> GeneratedStrings.Recordings.Errors.audio
    else -> GeneratedStrings.Recordings.Errors.transcription
}
