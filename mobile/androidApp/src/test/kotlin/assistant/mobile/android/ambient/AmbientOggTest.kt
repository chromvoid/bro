package assistant.mobile.android.ambient

import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AmbientOggTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test
    fun genuineOpusPacketsKeepPreSkipAndExactPartialSourceDuration() {
        val bytes = AmbientCodecFixtures.ogg()
        val file = temporary.newFile().apply { writeBytes(bytes) }
        assertEquals(AmbientAudioInfo(960, 48_000), AmbientOgg.validate(bytes))
        assertEquals(AmbientOgg.validate(bytes), AmbientOgg.probe(file))
        assertEquals(3, pageOffsets(bytes).size)
        AmbientCodecFixtures.export("silence.ogg", bytes)
        assertEquals(AmbientAudioInfo(3, 48_000), AmbientOgg.validate(AmbientCodecFixtures.ogg(1)))
    }

    @Test
    fun checksumSequenceEosAndSingleStreamAreEnforced() {
        val bytes = AmbientCodecFixtures.ogg()
        val offsets = pageOffsets(bytes)
        expectInvalid { AmbientOgg.validate(bytes.copyOf().apply { this[lastIndex] = (this[lastIndex].toInt() xor 1).toByte() }) }
        expectInvalid { AmbientOgg.validate(bytes + byteArrayOf(0)) }
        expectInvalid { AmbientOgg.validate(bytes + bytes) }
        val wrongSequence = bytes.copyOf().apply { this[offsets.last() + 18] = 9 }
        checksum(wrongSequence, offsets.last())
        expectInvalid { AmbientOgg.validate(wrongSequence) }
        val missingEos = bytes.copyOf().apply { this[offsets.last() + 5] = 0 }
        checksum(missingEos, offsets.last())
        expectInvalid { AmbientOgg.validate(missingEos) }
        val badHint = bytes.copyOf().apply { this[40] = 0 }
        checksum(badHint, 0)
        expectInvalid { AmbientOgg.validate(badHint) }
    }

    private fun pageOffsets(bytes: ByteArray): List<Int> {
        val offsets = mutableListOf<Int>()
        var offset = 0
        while (offset < bytes.size) {
            offsets.add(offset)
            val segments = bytes[offset + 26].toInt() and 0xff
            offset += 27 + segments + (0 until segments).sumOf { bytes[offset + 27 + it].toInt() and 0xff }
        }
        return offsets
    }

    /** Independent bitwise reference for mutating structural fields while retaining valid CRC. */
    private fun checksum(bytes: ByteArray, offset: Int) {
        val segments = bytes[offset + 26].toInt() and 0xff
        val size = 27 + segments + (0 until segments).sumOf { bytes[offset + 27 + it].toInt() and 0xff }
        repeat(4) { bytes[offset + 22 + it] = 0 }
        var crc = 0
        for (index in offset until offset + size) {
            crc = crc xor ((bytes[index].toInt() and 0xff) shl 24)
            repeat(8) { crc = if (crc < 0) (crc shl 1) xor 0x04c11db7 else crc shl 1 }
        }
        repeat(4) { bytes[offset + 22 + it] = (crc ushr (8 * it)).toByte() }
    }

    private fun expectInvalid(block: () -> Unit) {
        try { block(); throw AssertionError("Expected invalid Ogg/Opus") } catch (_: IllegalArgumentException) { }
    }
}
