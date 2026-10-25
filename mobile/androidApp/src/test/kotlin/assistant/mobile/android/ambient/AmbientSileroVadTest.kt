package assistant.mobile.android.ambient

import assistant.mobile.android.BuildConfig
import java.io.File
import java.security.MessageDigest
import java.util.Base64
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.*
import org.junit.Test

class AmbientSileroVadTest {
    @Test
    fun pinnedGraphRejectsSilenceAndRetainsSyntheticSpeechWithItsSourceCoordinates() {
        val fixture = Json.parseToJsonElement(File(requireNotNull(System.getProperty("assistant.vad.audio"))).readText()).jsonObject
        assertEquals(16_000, fixture.getValue("sample_rate").jsonPrimitive.int)
        val speech = Base64.getDecoder().decode(fixture.getValue("pcm16le_base64").jsonPrimitive.content)
        assertEquals(fixture.getValue("sample_count").jsonPrimitive.int * 2, speech.size)
        assertEquals(fixture.getValue("sha256").jsonPrimitive.content, sha256(speech))
        val silenceBytes = 100 * AmbientVadGate.FRAME_SAMPLES * 2
        val source = ByteArray(silenceBytes + speech.size + silenceBytes)
        speech.copyInto(source, silenceBytes)
        val clips = mutableListOf<AmbientVadSegment>()
        val gate = AmbientVadGate(clips::add)
        var strongestRun = 0
        var run = 0
        var frames = 0
        val started = System.nanoTime()
        AmbientSileroVad.fromModel(modelBytes(), BuildConfig.AMBIENT_VAD_MODEL_SHA256).use { detector ->
            val inferenceStarted = System.nanoTime()
            for (offset in source.indices step AmbientVadGate.FRAME_SAMPLES * 2) {
                val frame = source.copyOfRange(offset, minOf(source.size, offset + AmbientVadGate.FRAME_SAMPLES * 2))
                val probability = detector.probability(frame)
                if (offset < silenceBytes) assertTrue("zero PCM must remain below speech threshold", probability < 0.5f)
                run = if (probability >= 0.5f) run + 1 else 0
                strongestRun = maxOf(strongestRun, run)
                gate.accept(frame, probability)
                frames++
            }
            gate.finish()
            val inferenceNanos = System.nanoTime() - inferenceStarted
            println("Silero 1.24.2 host CPU: $frames continuous frames, ${inferenceNanos / 1_000_000.0} ms total, ${inferenceNanos / frames / 1_000_000.0} ms/frame; session+warmup ${(inferenceStarted - started) / 1_000_000.0} ms. This is not phone battery measurement.")
        }
        assertTrue("synthetic voice must contain at least 3 strong windows", strongestRun >= 3)
        assertTrue("synthetic speech must produce retained audio", clips.isNotEmpty())
        assertEquals(source.size / 2L, gate.capturedSamples)
        assertTrue("long leading silence must be omitted", clips.first().startSample > 0)
        assertTrue("long trailing silence must be omitted", clips.last().startSample + clips.last().pcm.size / 2 < source.size / 2)
        assertTrue(clips.sumOf { it.pcm.size } < source.size - silenceBytes)
        clips.forEach { clip ->
            val start = clip.startSample.toInt() * 2
            assertArrayEquals(source.copyOfRange(start, start + clip.pcm.size), clip.pcm)
        }
    }

    private fun modelBytes(): ByteArray = File(requireNotNull(System.getProperty("assistant.vad.model"))).readBytes()
    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
