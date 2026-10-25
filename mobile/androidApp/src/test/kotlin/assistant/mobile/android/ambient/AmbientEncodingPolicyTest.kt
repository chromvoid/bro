package assistant.mobile.android.ambient

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class AmbientEncodingPolicyTest {
    @Test
    fun compressedNegotiationPrefersOpusAndKeepsAacFallback() {
        val all = AmbientAudioFormat.entries.toSet()
        assertEquals(listOf(AmbientAudioFormat.OGG_OPUS, AmbientAudioFormat.AAC_ADTS), AmbientEncodingPolicy.preferred(all, all, 29))
        assertEquals(listOf(AmbientAudioFormat.AAC_ADTS), AmbientEncodingPolicy.preferred(all, all, 26))
        assertEquals(listOf(AmbientAudioFormat.AAC_ADTS), AmbientEncodingPolicy.preferred(all, setOf(AmbientAudioFormat.AAC_ADTS), 36))
        assertFalse(AmbientEncodingPolicy.preferred(all, all, 36).contains(AmbientAudioFormat.WAV))
    }

}
