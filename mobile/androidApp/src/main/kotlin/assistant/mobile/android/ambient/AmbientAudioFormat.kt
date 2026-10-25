package assistant.mobile.android.ambient

import java.io.File
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

data class AmbientAudioInfo(val encodedSampleCount: Long, val encodedSampleRate: Int)
private const val COMPRESSED_AUDIO_BYTES = 256 * 1_024

/** A format selects a fixed private filename and wire MIME type, never a supplied path. */
@Serializable
enum class AmbientAudioFormat(val id: String, val fileName: String, val mimeType: String, val maximumBytes: Int) {
    @SerialName("wav") WAV("wav", "audio.wav", "audio/wav", AmbientWav.MAX_WAV_BYTES),
    @SerialName("ogg-opus") OGG_OPUS("ogg-opus", "audio.ogg", "audio/ogg", COMPRESSED_AUDIO_BYTES),
    @SerialName("aac-adts") AAC_ADTS("aac-adts", "audio.aac", "audio/aac", COMPRESSED_AUDIO_BYTES);

    fun validate(bytes: ByteArray): AmbientAudioInfo = when (this) {
        WAV -> AmbientAudioInfo(AmbientWav.validate(bytes).sampleCount, AmbientWav.SAMPLE_RATE)
        OGG_OPUS -> AmbientOgg.validate(bytes)
        AAC_ADTS -> AmbientAdts.validate(bytes)
    }

    fun probe(file: File): AmbientAudioInfo = when (this) {
        WAV -> {
            val header = ByteArray(AmbientWav.HEADER_BYTES)
            file.inputStream().use { input ->
                var offset = 0
                while (offset < header.size) {
                    val read = input.read(header, offset, header.size - offset)
                    require(read > 0)
                    offset += read
                }
            }
            AmbientAudioInfo(AmbientWav.validateHeader(header, file.length()).sampleCount, AmbientWav.SAMPLE_RATE)
        }
        OGG_OPUS -> AmbientOgg.probe(file)
        AAC_ADTS -> AmbientAdts.probe(file)
    }

    companion object {
        const val MAX_COMPRESSED_BYTES = COMPRESSED_AUDIO_BYTES
        const val MAX_SOURCE_SAMPLES = 31L * AmbientWav.SAMPLE_RATE
        fun fromId(id: String): AmbientAudioFormat? = entries.firstOrNull { it.id == id }
    }
}
