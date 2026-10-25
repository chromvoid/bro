package assistant.mobile.android.ambient

import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class AmbientWavTest {
    @Test
    fun captureDurationComesFromSamplesAndHeaderDescribesThePcm() {
        val pcm = ByteArray(AmbientWav.SEGMENT_PCM_BYTES) { (it % 253).toByte() }
        val wav = AmbientWav.encode(pcm)
        val header = ByteBuffer.wrap(wav).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals("RIFF", String(wav.copyOfRange(0, 4), Charsets.US_ASCII))
        assertEquals(wav.size - 8, header.getInt(4))
        assertEquals(16_000, header.getInt(24))
        assertEquals(32_000, header.getInt(28))
        assertEquals(2, header.getShort(32).toInt())
        assertEquals(16, header.getShort(34).toInt())
        assertEquals(pcm.size, header.getInt(40))
        assertArrayEquals(pcm, wav.copyOfRange(AmbientWav.HEADER_BYTES, wav.size))
        assertEquals(480_000L, AmbientWav.validate(wav).sampleCount)
        assertEquals(30_000L, AmbientWav.validate(wav).durationMillis)
    }

    @Test
    fun persistedHeaderValidationRejectsFileLengthAndFormatMismatch() {
        val wav = AmbientWav.encode(byteArrayOf(1, 2, 3, 4))
        val header = wav.copyOf(AmbientWav.HEADER_BYTES)
        assertEquals(2L, AmbientWav.validateHeader(header, wav.size.toLong()).sampleCount)
        expectInvalid { AmbientWav.validateHeader(header, wav.size + 2L) }
        expectInvalid { AmbientWav.validateHeader(header.copyOf(20), wav.size.toLong()) }
        val compressed = header.copyOf()
        ByteBuffer.wrap(compressed).order(ByteOrder.LITTLE_ENDIAN).putShort(20, 3)
        expectInvalid { AmbientWav.validateHeader(compressed, wav.size.toLong()) }
    }

    private fun expectInvalid(block: () -> Unit) {
        try { block(); throw AssertionError("Expected invalid WAV") } catch (_: IllegalArgumentException) { }
    }
}
