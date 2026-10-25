package assistant.mobile.android.ambient

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class AmbientAacInputTest {
    @Test
    fun boundaryInputsKeepTheirExactPrefixAndAppendOnlyBoundedZeroes() {
        assertEquals(1024, AmbientAacInput.FRAME_SAMPLES)
        assertEquals(2048, AmbientAacInput.PRIMING_SAMPLES)
        for ((sourceSamples, accessUnits) in listOf(1 to 3, 137 to 3, 1023 to 3, 1024 to 3, 1025 to 4)) {
            val pcm = patternedPcm(sourceSamples)
            val saved = pcm.copyOf()
            val prepared = AmbientAacInput.prepare(pcm)
            assertNotSame(pcm, prepared)
            assertArrayEquals(saved, pcm)
            assertArrayEquals(saved, prepared.copyOfRange(0, pcm.size))
            assertEquals(accessUnits, AmbientAacInput.retainedAccessUnits(sourceSamples.toLong()))
            assertEquals(accessUnits * 1024 * AmbientWav.BYTES_PER_SAMPLE, prepared.size)
            assertEquals(0, prepared.size / AmbientWav.BYTES_PER_SAMPLE % 1024)
            assertTrue(prepared.size / AmbientWav.BYTES_PER_SAMPLE - sourceSamples in 2048..3071)
            assertTrue(prepared.copyOfRange(pcm.size, prepared.size).all { it == 0.toByte() })
        }
    }

    @Test
    fun emptyOddAndOversizedPcmIsRejectedBeforePadding() {
        for (size in listOf(0, 1, 3, AmbientWav.SEGMENT_PCM_BYTES + 1, AmbientWav.SEGMENT_PCM_BYTES + 2)) {
            assertThrows(IllegalArgumentException::class.java) { AmbientAacInput.prepare(ByteArray(size)) }
        }
    }

    private fun patternedPcm(samples: Int): ByteArray = ByteArray(samples * AmbientWav.BYTES_PER_SAMPLE) {
        ((it * 37 + 11) and 255).toByte()
    }
}
