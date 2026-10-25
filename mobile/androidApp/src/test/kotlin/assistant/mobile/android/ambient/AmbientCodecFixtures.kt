package assistant.mobile.android.ambient

import java.io.File
import java.util.Base64

/** Synthetic silence generated with ffmpeg/libopus 24 kbps and AAC-LC 32 kbps at 16 kHz. */
internal object AmbientCodecFixtures {
    val opusHead: ByteArray get() = Base64.getDecoder().decode("T3B1c0hlYWQBATgBgD4AAAAAAA==")
    val opusPacket: ByteArray get() = Base64.getDecoder().decode("uP/+")
    val aacUnits: List<ByteArray> get() = listOf("3gIATGF2YzYyLjExLjEwMAACMEAO", "ARggBw==").map { Base64.getDecoder().decode(it) }

    fun ogg(sourceSamples: Long = 320): ByteArray {
        val count = ((312 + sourceSamples * 3 + 959) / 960).toInt()
        return AmbientOgg.encode(opusHead, List(count) { opusPacket }, sourceSamples)
    }

    fun adts(): ByteArray = aacUnits.flatMap { AmbientAdts.frame(it).toList() }.toByteArray()

    /** Explicit optional export supports an independent host decoder check without test dependencies. */
    fun export(name: String, bytes: ByteArray) {
        System.getenv("ASSISTANT_AMBIENT_INTEROP_DIR")?.let { path ->
            val directory = File(path)
            require(directory.isDirectory || directory.mkdirs())
            File(directory, name).writeBytes(bytes)
        }
    }
}
