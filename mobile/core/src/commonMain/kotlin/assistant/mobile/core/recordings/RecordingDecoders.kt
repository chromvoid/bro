package assistant.mobile.core.recordings

import assistant.mobile.core.NativeJson
import kotlinx.serialization.Serializable
import kotlin.time.Instant

fun decodeRecordingPage(bytes: ByteArray): RecordingPage = NativeJson.decodeFromString<RecordingPage>(bytes.decodeToString()).also { page ->
    page.recordings.forEach { item ->
        require(recordingId(item.id) && recordingId(item.sessionId))
        require(item.sequence >= 0 && item.stored && item.state == "stored" && item.bytes >= 0 && item.durationMs > 0)
        Instant.parse(item.startedAt)
        Instant.parse(item.receivedAt)
        item.expiresAt?.let(Instant::parse)
        validateSummary(item.transcription)
    }
}

fun decodeRecordingTranscript(bytes: ByteArray, expectedId: String): RecordingTranscript =
    NativeJson.decodeFromString<RecordingTranscript>(bytes.decodeToString()).also {
        require(it.id == expectedId && recordingId(it.id))
        require(it.text == null || it.text.length <= 16_000)
        require(it.status != TranscriptStatus.Ready || !it.text.isNullOrBlank())
        it.updatedAt?.let(Instant::parse)
        it.transcribedAt?.let(Instant::parse)
    }

fun decodeRecordingUsage(bytes: ByteArray): RecordingUsage = NativeJson.decodeFromString<RecordingUsage>(bytes.decodeToString()).also {
    require(it.usedBytes >= 0 && it.limits.maxUserBytes > 0)
    require(it.limits.retentionDays == null || it.limits.retentionDays >= 0)
}

@Serializable
private data class RecordingDeletion(val id: String, val deleted: Boolean)

fun decodeRecordingDeletion(bytes: ByteArray, expectedId: String) {
    val receipt = NativeJson.decodeFromString<RecordingDeletion>(bytes.decodeToString())
    require(receipt.id == expectedId && recordingId(receipt.id) && receipt.deleted)
}

private fun validateSummary(summary: RecordingSummary) {
    require(summary.preview == null || summary.preview.length <= 200)
    summary.updatedAt?.let(Instant::parse)
}

/** UUID is a wire identifier alphabet, never an interpretation of human text. */
internal fun recordingId(value: String): Boolean = value.length == 36 && value.withIndex().all { (index, character) ->
    if (index in setOf(8, 13, 18, 23)) character == '-'
    else character in '0'..'9' || character in 'a'..'f' || character in 'A'..'F'
}

/** Reject successful HTML/error pages before passing private bytes to a native decoder. */
internal fun recordingAudio(bytes: ByteArray, mime: String?, format: RecordingFormat): RecordingAudio? {
    val type = mime?.substringBefore(';')?.trim()?.lowercase()
    val valid = when (format) {
        RecordingFormat.Wav -> type in setOf("audio/wav", "audio/x-wav", "audio/wave") && bytes.size >= 12 &&
            bytes.copyOfRange(0, 4).decodeToString() == "RIFF" && bytes.copyOfRange(8, 12).decodeToString() == "WAVE"
        RecordingFormat.OggOpus -> type in setOf("audio/ogg", "application/ogg", "audio/opus") && bytes.size >= 4 &&
            bytes.copyOfRange(0, 4).decodeToString() == "OggS"
        RecordingFormat.AacAdts -> type in setOf("audio/aac", "audio/aacp") && bytes.size >= 7 &&
            bytes[0].toInt() and 255 == 255 && bytes[1].toInt() and 246 == 240
    }
    return if (valid) RecordingAudio(bytes, requireNotNull(type)) else null
}
