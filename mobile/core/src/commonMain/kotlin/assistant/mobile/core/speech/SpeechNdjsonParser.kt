package assistant.mobile.core.speech

import assistant.mobile.core.NativeJson
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.io.encoding.Base64

/** A single-attempt, bounded protocol decoder. A done frame is not completion until clean EOF. */
class SpeechNdjsonParser(private val expected: SpeechSegmentExpectation) {
    suspend fun read(
        status: Int,
        headers: Map<String, String>,
        channel: ByteReadChannel,
        onPcm: suspend (ByteArray, SpeechAudioFormat) -> Unit = { _, _ -> },
    ): SpeechAudioSegment {
        validateSpeechHeaders(status, headers, expected)
        val chunks = mutableListOf<ByteArray>()
        var format: SpeechAudioFormat? = null
        var size = 0
        var frames = 0
        var done = false
        val line = ByteArray(SPEECH_LINE_MAX_BYTES)
        var pending = 0
        suspend fun consume() {
            val value = try { line.decodeToString(0, pending, throwOnInvalidSequence = true) }
                catch (_: Exception) { invalidSpeech() }
            pending = 0
            if (value.isBlank()) return
            if (++frames > 16_384 || done) invalidSpeech()
            val frame = try { NativeJson.decodeFromString<SpeechAudioFrame>(value) }
                catch (_: Exception) { invalidSpeech() }
            when (frame.type) {
                "start" -> {
                    if (format != null) invalidSpeech()
                    format = when (frame.contentType) {
                        "audio/pcm" -> {
                            if (frame.sampleRate != 24_000 || frame.channels != 1) invalidSpeech()
                            SpeechAudioFormat("audio/pcm", 24_000, 1)
                        }
                        "audio/wav", "audio/mpeg" -> SpeechAudioFormat(frame.contentType)
                        else -> invalidSpeech()
                    }
                }
                "audio" -> {
                    val audioFormat = format ?: invalidSpeech()
                    val bytes = decodeSpeechBase64(frame.data)
                    if (bytes.size > SPEECH_SEGMENT_MAX_BYTES - size ||
                        (audioFormat.contentType == "audio/pcm" && bytes.size % 2 != 0)) invalidSpeech()
                    size += bytes.size
                    chunks += bytes
                    if (audioFormat.contentType == "audio/pcm") onPcm(bytes, audioFormat)
                }
                "done" -> {
                    if (format == null || size == 0) invalidSpeech()
                    done = true
                }
                "error" -> throw SpeechPlaybackFailure(speechPlaybackProblem(frame.code))
                else -> invalidSpeech()
            }
        }
        val buffer = ByteArray(16 * 1024)
        while (true) {
            val count = try { withTimeout(45_000) { channel.readAvailable(buffer, 0, buffer.size) } }
                catch (_: TimeoutCancellationException) { throw SpeechPlaybackFailure(SpeechPlaybackProblem.TIMEOUT) }
            if (count < 0) break
            for (index in 0 until count) {
                val byte = buffer[index]
                if (byte == 10.toByte()) consume() else {
                    if (pending == line.size) invalidSpeech()
                    line[pending++] = byte
                }
            }
        }
        if (pending > 0) consume()
        if (!done) invalidSpeech()
        val complete = ByteArray(size)
        var offset = 0
        for (chunk in chunks) {
            chunk.copyInto(complete, offset)
            offset += chunk.size
        }
        return SpeechAudioSegment(format ?: invalidSpeech(), complete)
    }
}

internal fun validateSpeechHeaders(status: Int, headers: Map<String, String>, expected: SpeechSegmentExpectation) {
    fun number(name: String): Int? = headers[name]?.takeIf { it.isNotEmpty() && it.all { c -> c in '0'..'9' } }?.toIntOrNull()
    val count = number("x-speech-segments")
    val index = number("x-speech-segment")
    val key = headers["x-speech-cache-key"]
    if (status != 200 || headers["content-type"]?.substringBefore(';')?.trim() != "application/x-ndjson" ||
        count == null || count < 1 || index == null || index !in 0 until count ||
        count != expected.segments || index != expected.segment || key != expected.cacheKey ||
        (key != null && !validSpeechCacheKey(key))) invalidSpeech()
}

@Serializable
private data class SpeechAudioFrame(
    val type: String,
    @SerialName("content_type") val contentType: String? = null,
    @SerialName("sample_rate") val sampleRate: Int? = null,
    val channels: Int? = null,
    val data: String? = null,
    val code: String? = null,
) {
    override fun toString(): String = "SpeechAudioFrame(type=$type, content=redacted)"
}

private fun decodeSpeechBase64(value: String?): ByteArray {
    if (value.isNullOrEmpty() || value.length % 4 != 0) invalidSpeech()
    val padding = value.takeLastWhile { it == '=' }.length
    if (padding > 2 || !value.dropLast(padding).all { it in 'A'..'Z' || it in 'a'..'z' || it in '0'..'9' || it == '+' || it == '/' })
        invalidSpeech()
    return try { Base64.decode(value) } catch (_: Exception) { invalidSpeech() }
}

private fun invalidSpeech(): Nothing = throw SpeechPlaybackFailure(SpeechPlaybackProblem.INVALID_RESPONSE)
