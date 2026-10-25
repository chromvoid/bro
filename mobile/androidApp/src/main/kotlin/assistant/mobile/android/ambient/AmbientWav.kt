package assistant.mobile.android.ambient

import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Legacy V1 WAV support; microphone PCM16, mono 16 kHz is now compressed before enqueue. */
object AmbientWav {
    const val SAMPLE_RATE = 16_000
    const val BYTES_PER_SAMPLE = 2
    const val SEGMENT_SECONDS = 30
    const val HEADER_BYTES = 44
    const val MAX_SECONDS = 31
    const val SEGMENT_PCM_BYTES = SAMPLE_RATE * BYTES_PER_SAMPLE * SEGMENT_SECONDS
    const val MAX_PCM_BYTES = SAMPLE_RATE * BYTES_PER_SAMPLE * MAX_SECONDS
    const val MAX_WAV_BYTES = MAX_PCM_BYTES + HEADER_BYTES

    data class Info(val sampleCount: Long) {
        val durationMillis: Long get() = sampleCount * 1_000 / SAMPLE_RATE
    }

    fun encode(pcm: ByteArray): ByteArray {
        require(pcm.isNotEmpty() && pcm.size % BYTES_PER_SAMPLE == 0 && pcm.size <= MAX_PCM_BYTES) {
            "A segment must contain between one sample and 31 seconds of PCM16"
        }
        return ByteBuffer.allocate(HEADER_BYTES + pcm.size).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray(Charsets.US_ASCII)); putInt(36 + pcm.size)
            put("WAVEfmt ".toByteArray(Charsets.US_ASCII)); putInt(16)
            putShort(1); putShort(1); putInt(SAMPLE_RATE)
            putInt(SAMPLE_RATE * BYTES_PER_SAMPLE); putShort(BYTES_PER_SAMPLE.toShort()); putShort(16)
            put("data".toByteArray(Charsets.US_ASCII)); putInt(pcm.size); put(pcm)
        }.array()
    }

    fun validate(wav: ByteArray): Info {
        require(wav.size >= HEADER_BYTES) { "Invalid WAV length" }
        return validateHeader(wav.copyOfRange(0, HEADER_BYTES), wav.size.toLong())
    }

    /** Validate persisted metadata without reading the audio payload during a status scan. */
    fun validateHeader(header: ByteArray, fileBytes: Long): Info {
        require(header.size == HEADER_BYTES && fileBytes in (HEADER_BYTES + BYTES_PER_SAMPLE).toLong()..MAX_WAV_BYTES.toLong()) {
            "Invalid WAV length"
        }
        val buffer = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
        fun marker(expected: String) {
            val actual = ByteArray(expected.length)
            buffer.get(actual)
            require(actual.contentEquals(expected.toByteArray(Charsets.US_ASCII))) { "Invalid WAV header" }
        }
        marker("RIFF"); require(buffer.int.toLong() == fileBytes - 8)
        marker("WAVEfmt "); require(buffer.int == 16)
        require(buffer.short.toInt() == 1 && buffer.short.toInt() == 1)
        require(buffer.int == SAMPLE_RATE && buffer.int == SAMPLE_RATE * BYTES_PER_SAMPLE)
        require(buffer.short.toInt() == BYTES_PER_SAMPLE && buffer.short.toInt() == 16)
        marker("data")
        val pcmBytes = buffer.int
        require(pcmBytes.toLong() == fileBytes - HEADER_BYTES && pcmBytes % BYTES_PER_SAMPLE == 0) { "Invalid WAV samples" }
        return Info((pcmBytes / BYTES_PER_SAMPLE).toLong())
    }
}
