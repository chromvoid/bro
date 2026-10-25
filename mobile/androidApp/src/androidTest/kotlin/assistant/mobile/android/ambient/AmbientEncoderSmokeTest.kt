package assistant.mobile.android.ambient

import android.os.Bundle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Black-box native regression: synthetic PCM only; no microphone, network or durable app data. */
@RunWith(AndroidJUnit4::class)
class AmbientEncoderSmokeTest {
    @Test
    fun publicEncoderProducesValidShortAndFullClipsAndSupportsAacFallback() {
        val accepted = setOf(AmbientAudioFormat.OGG_OPUS, AmbientAudioFormat.AAC_ADTS)
        val encoder = AmbientEncoder.discover(accepted)
        val fullPcm = syntheticToneAndNoise()
        // Keep the sub-frame regression at exactly 137 source samples, with audible
        // content so an independent decoder can distinguish it from priming-only AAC.
        val shortPcm = fullPcm.copyOf(SHORT_SAMPLES * BYTES_PER_SAMPLE)
        verifyAndSave("preferred-short", shortPcm, encoder.encode(shortPcm) { true }, accepted)
        verifyAndSave("preferred-30s", fullPcm, encoder.encode(fullPcm) { true }, accepted)

        val aacOnly = setOf(AmbientAudioFormat.AAC_ADTS)
        val fallback = AmbientEncoder.discover(aacOnly)
        val aac = fallback.encode(shortPcm) { true }
        assertEquals("AAC-only discovery must retain the native fallback", AmbientAudioFormat.AAC_ADTS, aac.format)
        verifyAndSave("aac-short", shortPcm, aac, aacOnly)
        verifyAndSave("aac-30s", fullPcm, fallback.encode(fullPcm) { true }, aacOnly)
    }

    private fun verifyAndSave(
        name: String,
        pcm: ByteArray,
        audio: AmbientEncodedAudio,
        accepted: Set<AmbientAudioFormat>,
    ) {
        assertTrue("The negotiated output must be compressed", audio.format in accepted && audio.format != AmbientAudioFormat.WAV)
        assertTrue("Encoded audio must be nonempty and bounded", audio.bytes.size in 1..AmbientAudioFormat.MAX_COMPRESSED_BYTES)
        assertTrue(audio.bytes.size <= audio.format.maximumBytes)
        val sourceSamples = pcm.size / BYTES_PER_SAMPLE.toLong()
        val info = audio.format.validate(audio.bytes)
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val directory = File(instrumentation.targetContext.cacheDir, "ambient-codec-smoke")
        check(directory.isDirectory || directory.mkdirs()) { "Cannot create synthetic codec smoke output directory" }
        val extension = when (audio.format) {
            AmbientAudioFormat.OGG_OPUS -> "ogg"
            AmbientAudioFormat.AAC_ADTS -> "aac"
            AmbientAudioFormat.WAV -> throw AssertionError("PCM fallback is not allowed")
        }
        val file = File(directory, "$name.$extension")
        file.writeBytes(audio.bytes)
        assertEquals(audio.bytes.size.toLong(), file.length())
        val report = JSONObject()
            .put("file", "ambient-codec-smoke/${file.name}")
            .put("format", audio.format.id)
            .put("bytes", audio.bytes.size)
            .put("source_samples", sourceSamples)
            .put("encoded_samples", info.encodedSampleCount)
            .put("source_duration_ms", sourceSamples * 1_000.0 / AmbientWav.SAMPLE_RATE)
            .put("encoded_duration_ms", info.encodedSampleCount * 1_000.0 / info.encodedSampleRate)
        instrumentation.sendStatus(0, Bundle().apply {
            putString("stream", "AMBIENT_ENCODER_SMOKE $report\n")
        })
        assertTrue("Encoded duration must fit the upload contract", info.encodedSampleCount in 1..31L * info.encodedSampleRate)
        when (audio.format) {
            AmbientAudioFormat.OGG_OPUS -> {
                assertEquals("Opus granules use 48 kHz", 48_000, info.encodedSampleRate)
                assertEquals("Ogg end trim must retain every actual source sample", sourceSamples * 3, info.encodedSampleCount)
            }
            AmbientAudioFormat.AAC_ADTS -> {
                assertEquals(AmbientWav.SAMPLE_RATE, info.encodedSampleRate)
                assertTrue("AAC must cover the source samples", info.encodedSampleCount >= sourceSamples)
                // AOSP/FDK AAC-LC delays 1600 samples for MDCT/lookahead, then metadata
                // compensation rounds that to 2048 even with metadata disabled. EOS
                // flushing also rounds the source to complete 1024-sample access units.
                // platform/external/aac/libAACenc/src/{aacenc_lib,metadata_main}.cpp
                val roundedSource = ((sourceSamples + AAC_FRAME_SAMPLES - 1) / AAC_FRAME_SAMPLES) * AAC_FRAME_SAMPLES
                assertEquals("AAC-LC must contain complete access units", 0L, info.encodedSampleCount % AAC_FRAME_SAMPLES)
                assertTrue("AAC output must stay within frame rounding plus native priming",
                    info.encodedSampleCount <= roundedSource + AAC_PRIMING_SAMPLES)
            }
            AmbientAudioFormat.WAV -> throw AssertionError("PCM fallback is not allowed")
        }
    }

    /** Integer 200 Hz triangle tone plus seeded noise; no platform-dependent random generator. */
    private fun syntheticToneAndNoise(): ByteArray = ByteArray(AmbientWav.SEGMENT_PCM_BYTES).also { pcm ->
        var noiseState = 0x2468ace
        repeat(pcm.size / BYTES_PER_SAMPLE) { index ->
            noiseState = noiseState xor (noiseState shl 13)
            noiseState = noiseState xor (noiseState ushr 17)
            noiseState = noiseState xor (noiseState shl 5)
            val phase = index % 80
            val tone = (if (phase < 40) phase else 80 - phase) * 500 - 10_000
            val noise = (((noiseState ushr 16) and 0xffff) - 32_768) / 16
            val sample = tone + noise
            pcm[index * BYTES_PER_SAMPLE] = sample.toByte()
            pcm[index * BYTES_PER_SAMPLE + 1] = (sample ushr 8).toByte()
        }
    }

    companion object {
        private const val SHORT_SAMPLES = 137
        private const val BYTES_PER_SAMPLE = 2
        private const val AAC_FRAME_SAMPLES = 1_024
        private const val AAC_PRIMING_SAMPLES = 2_048
    }
}
