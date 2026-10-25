package assistant.mobile.android.ambient

import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Strict Android Opus CSD framing; the returned Ogg header always describes our mono source. */
object AmbientOpusConfig {
    fun normalize(csd0: ByteArray, separateDelay: ByteArray? = null): ByteArray {
        val externalDelay = separateDelay?.let(::delayNanos)
        val head: ByteArray
        val packedDelay: Long?
        if (csd0.size == HEAD_BYTES) {
            head = csd0.copyOf()
            packedDelay = null
        } else {
            require(csd0.size == PACKED_BYTES) { "Invalid Android Opus CSD length" }
            val input = ByteBuffer.wrap(csd0).order(ByteOrder.nativeOrder())
            var packedHead: ByteArray? = null
            var delay: Long? = null
            var preroll: Long? = null
            while (input.hasRemaining()) {
                require(input.remaining() >= RECORD_HEADER_BYTES) { "Truncated Android Opus CSD record" }
                val marker = ByteArray(MARKER_BYTES).also(input::get).toString(Charsets.US_ASCII)
                val length = input.long
                val expectedLength = when (marker) {
                    "AOPUSHDR" -> HEAD_BYTES
                    "AOPUSDLY", "AOPUSPRL" -> DELAY_BYTES
                    else -> throw IllegalArgumentException("Unknown Android Opus CSD record")
                }
                require(length == expectedLength.toLong() && length <= input.remaining()) { "Invalid Android Opus CSD record length" }
                val payload = ByteArray(expectedLength).also(input::get)
                when (marker) {
                    "AOPUSHDR" -> { require(packedHead == null) { "Duplicate Android Opus header" }; packedHead = payload }
                    "AOPUSDLY" -> { require(delay == null) { "Duplicate Android Opus delay" }; delay = delayNanos(payload) }
                    "AOPUSPRL" -> { require(preroll == null) { "Duplicate Android Opus preroll" }; preroll = delayNanos(payload) }
                }
            }
            require(packedHead != null && delay != null && preroll != null) { "Missing Android Opus CSD record" }
            head = packedHead
            packedDelay = delay
            require(externalDelay == null || externalDelay == packedDelay) { "Conflicting Android Opus delays" }
        }

        require(head.copyOfRange(0, MARKER_BYTES).contentEquals(OPUS_HEAD) &&
            head[8] == 1.toByte() && head[9] == 1.toByte() && head[18] == 0.toByte() &&
            head[16] == 0.toByte() && head[17] == 0.toByte()) { "Unsupported Opus header" }
        val output = ByteBuffer.wrap(head).order(ByteOrder.LITTLE_ENDIAN)
        val delay = packedDelay ?: externalDelay
        if (delay != null) {
            val preSkip = (delay * 48_000 + 500_000_000) / 1_000_000_000
            output.putShort(10, preSkip.toShort())
        }
        output.putInt(12, 16_000)
        return head
    }

    private fun delayNanos(bytes: ByteArray): Long {
        require(bytes.size == DELAY_BYTES) { "Invalid Android Opus delay length" }
        return ByteBuffer.wrap(bytes).order(ByteOrder.nativeOrder()).long.also {
            require(it in 0..1_000_000_000L) { "Invalid Android Opus delay" }
        }
    }

    private const val MARKER_BYTES = 8
    private const val DELAY_BYTES = 8
    private const val HEAD_BYTES = 19
    private const val RECORD_HEADER_BYTES = MARKER_BYTES + 8
    private const val PACKED_BYTES = 83
    private val OPUS_HEAD = "OpusHead".toByteArray(Charsets.US_ASCII)
}
