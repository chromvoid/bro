package assistant.mobile.android.ambient

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AmbientAdtsTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test
    fun genuineSyntheticAccessUnitsAreFramedAndMeasuredWithoutDecoding() {
        val bytes = AmbientCodecFixtures.adts()
        val file = temporary.newFile().apply { writeBytes(bytes) }
        assertEquals(AmbientAudioInfo(2_048, 16_000), AmbientAdts.validate(bytes))
        assertEquals(AmbientAdts.validate(bytes), AmbientAdts.probe(file))
        assertArrayEquals(AmbientCodecFixtures.aacUnits.first(), bytes.copyOfRange(7, 7 + AmbientCodecFixtures.aacUnits.first().size))
        AmbientCodecFixtures.export("silence.aac", bytes)
    }

    @Test
    fun everyFrameMustStayWithinTheOwnCodecProfileAndBoundaries() {
        val framed = AmbientAdts.frame(byteArrayOf(1, 2, 3))
        assertEquals(AmbientAudioInfo(1_024, 16_000), AmbientAdts.validate(framed))
        for ((index, value) in listOf(0 to 0, 1 to 0xf0, 2 to 0x20, 3 to 0x80, 6 to 0xfd)) {
            expectInvalid { AmbientAdts.validate(framed.copyOf().apply { this[index] = value.toByte() }) }
        }
        expectInvalid { AmbientAdts.validate(framed.copyOf(framed.size - 1)) }
        expectInvalid { AmbientAdts.validate(framed + byteArrayOf(0)) }
        expectInvalid { AmbientAdts.frame(byteArrayOf()) }
        expectInvalid { AmbientAdts.frame(ByteArray(8_185)) }
        expectInvalid { AmbientAdts.validate(ByteArray(AmbientAudioFormat.MAX_COMPRESSED_BYTES + 1)) }
    }

    private fun expectInvalid(block: () -> Unit) {
        try { block(); throw AssertionError("Expected invalid ADTS") } catch (_: IllegalArgumentException) { }
    }
}
