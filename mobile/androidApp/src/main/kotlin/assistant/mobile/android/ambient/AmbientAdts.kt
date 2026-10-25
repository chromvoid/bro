package assistant.mobile.android.ambient

import java.io.File
import java.io.RandomAccessFile

/** Our narrow ADTS profile: MPEG-4 AAC-LC, mono 16 kHz, one raw block and no CRC. */
object AmbientAdts {
    const val HEADER_BYTES = 7
    private const val FRAME_SAMPLES = 1_024L
    private const val MAX_FRAME_BYTES = 8_191

    fun frame(rawAccessUnit: ByteArray): ByteArray {
        require(rawAccessUnit.isNotEmpty() && rawAccessUnit.size <= MAX_FRAME_BYTES - HEADER_BYTES)
        val length = rawAccessUnit.size + HEADER_BYTES
        return byteArrayOf(
            0xff.toByte(), 0xf1.toByte(), 0x60.toByte(),
            (0x40 or (length ushr 11)).toByte(), (length ushr 3).toByte(),
            (((length and 7) shl 5) or 0x1f).toByte(), 0xfc.toByte(),
        ) + rawAccessUnit
    }

    fun validate(bytes: ByteArray): AmbientAudioInfo {
        require(bytes.size in (HEADER_BYTES + 1)..AmbientAudioFormat.MAX_COMPRESSED_BYTES)
        var offset = 0
        var frames = 0L
        while (offset < bytes.size) {
            require(bytes.size - offset >= HEADER_BYTES)
            val length = frameBytes(bytes, offset)
            require(length <= bytes.size - offset)
            offset += length
            frames++
            require(frames * FRAME_SAMPLES <= MAX_SAMPLES)
        }
        return AmbientAudioInfo(frames * FRAME_SAMPLES, AmbientWav.SAMPLE_RATE)
    }

    /** Read frame headers and seek over access units; no codec or audio payload read is needed. */
    fun probe(file: File): AmbientAudioInfo = RandomAccessFile(file, "r").use { input ->
        val bytes = input.length()
        require(bytes in (HEADER_BYTES + 1).toLong()..AmbientAudioFormat.MAX_COMPRESSED_BYTES.toLong())
        var offset = 0L
        var frames = 0L
        val header = ByteArray(HEADER_BYTES)
        while (offset < bytes) {
            require(bytes - offset >= HEADER_BYTES)
            input.readFully(header)
            val length = frameBytes(header, 0)
            require(length <= bytes - offset)
            offset += length
            input.seek(offset)
            frames++
            require(frames * FRAME_SAMPLES <= MAX_SAMPLES)
        }
        AmbientAudioInfo(frames * FRAME_SAMPLES, AmbientWav.SAMPLE_RATE)
    }

    private fun frameBytes(bytes: ByteArray, offset: Int): Int {
        fun byte(index: Int) = bytes[offset + index].toInt() and 0xff
        require(byte(0) == 0xff && byte(1) == 0xf1) // Sync, MPEG-4, layer zero, no CRC.
        require(byte(2) ushr 6 == 1 && ((byte(2) ushr 2) and 15) == 8) // LC and 16 kHz.
        require((((byte(2) and 1) shl 2) or (byte(3) ushr 6)) == 1) // Mono, no PCE.
        require(byte(6) and 3 == 0) // One raw data block.
        val length = ((byte(3) and 3) shl 11) or (byte(4) shl 3) or (byte(5) ushr 5)
        require(length in (HEADER_BYTES + 1)..MAX_FRAME_BYTES)
        return length
    }

    private const val MAX_SAMPLES = 31L * AmbientWav.SAMPLE_RATE
}
