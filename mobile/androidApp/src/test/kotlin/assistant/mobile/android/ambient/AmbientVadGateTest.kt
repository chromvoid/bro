package assistant.mobile.android.ambient

import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.float
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AmbientVadGateTest {
    @Test
    fun sharedPolicyAndEveryPcmSliceConformToTheCrossPlatformFixture() {
        val fixture = Json.parseToJsonElement(contractFile().readText()).jsonObject
        val defaults = mapOf(
            "version" to 1,
            "sample_rate" to AmbientVadGate.SAMPLE_RATE,
            "frame_samples" to AmbientVadGate.FRAME_SAMPLES,
            "min_speech_samples" to AmbientVadGate.MIN_SPEECH_SAMPLES,
            "pre_roll_samples" to AmbientVadGate.PRE_ROLL_SAMPLES,
            "end_silence_samples" to AmbientVadGate.END_SILENCE_SAMPLES,
            "post_roll_samples" to AmbientVadGate.POST_ROLL_SAMPLES,
            "max_clip_samples" to AmbientVadGate.MAX_CLIP_SAMPLES,
        )
        defaults.forEach { (key, expected) -> assertEquals(key, expected, fixture.getValue(key).jsonPrimitive.int) }
        assertEquals(AmbientVadGate.START_THRESHOLD, fixture.getValue("start_threshold").jsonPrimitive.float, 0f)
        assertEquals(AmbientVadGate.CONTINUE_THRESHOLD, fixture.getValue("continue_threshold").jsonPrimitive.float, 0f)
        assertEquals(3, AmbientVadGate.CONFIRMATION_FRAMES)

        for (element in fixture.getValue("cases").jsonArray) {
            val case = element.jsonObject
            val name = case.getValue("name").jsonPrimitive.content
            val harness = Harness()
            for (runElement in case.getValue("runs").jsonArray) {
                val run = runElement.jsonObject
                repeat(run.getValue("frames").jsonPrimitive.int) {
                    harness.accept(512, run.getValue("probability").jsonPrimitive.float)
                }
            }
            case["tail_samples"]?.let {
                harness.accept(it.jsonPrimitive.int, case.getValue("tail_probability").jsonPrimitive.float)
            }
            if (case["discard"]?.jsonPrimitive?.booleanOrNull == true) harness.gate.discard() else harness.gate.finish()
            assertEquals("$name captured samples", harness.cursor, harness.gate.capturedSamples)
            val expected = case.getValue("expected_clips").jsonArray
            assertEquals("$name clip count", expected.size, harness.segments.size)
            expected.forEachIndexed { index, expectedElement ->
                val clip = expectedElement.jsonObject
                assertSegment(name, harness.segments[index], clip.getValue("start_sample").jsonPrimitive.long,
                    clip.getValue("sample_count").jsonPrimitive.int)
            }
        }
    }

    @Test
    fun exactMaximumSplitAndQuietFlushDoNotDuplicateOrLoseSamples() {
        val harness = Harness()
        repeat(937) { harness.accept(512, 0.9f) }
        harness.accept(256, 0.9f)
        assertEquals(1, harness.segments.size)
        assertSegment("full clip", harness.segments.single(), 0, 480_000)
        harness.gate.finish()
        assertEquals(1, harness.segments.size)

        harness.accept(512, 0.9f)
        harness.accept(512, 0.9f)
        harness.accept(512, 0.9f)
        harness.gate.finish()
        assertSegment("after explicit stop", harness.segments[1], 480_000, 1536)

        val quietSplit = Harness()
        repeat(935) { quietSplit.accept(512, 0.9f) }
        quietSplit.accept(137, 0.9f)
        repeat(20) { quietSplit.accept(512, 0f) }
        quietSplit.accept(111, 0.35f)
        quietSplit.gate.finish()
        assertEquals(2, quietSplit.segments.size)
        assertSegment("quiet split first", quietSplit.segments[0], 0, 480_000)
        assertSegment("quiet split remainder", quietSplit.segments[1], 480_000, 9208)
    }

    @Test
    fun explicitStopLimitsQuietTailAndIsIdempotentWhileDiscardNeverEmitsPendingAudio() {
        for (quietCount in listOf(3, 9, 24)) {
            val harness = Harness()
            repeat(3) { harness.accept(512, 0.9f) }
            repeat(quietCount) { harness.accept(512, 0f) }
            harness.gate.finish()
            harness.gate.finish()
            assertEquals(1, harness.segments.size)
            assertSegment("stop with $quietCount quiet frames", harness.segments.single(), 0,
                1536 + minOf(quietCount * 512, 4096))
        }

        val cancelled = Harness()
        repeat(20) { cancelled.accept(512, 0f) }
        repeat(10) { cancelled.accept(512, 0.9f) }
        cancelled.gate.discard()
        cancelled.gate.finish()
        assertTrue(cancelled.segments.isEmpty())
        assertEquals(15_360L, cancelled.gate.capturedSamples)
        repeat(3) { cancelled.accept(512, 0.9f) }
        cancelled.gate.finish()
        assertSegment("fresh audio after discard", cancelled.segments.single(), 15_360, 1536)
    }

    @Test
    fun invalidConfidenceOrPcmDoesNotAdvanceCaptureOrPersistAnything() {
        val harness = Harness()
        repeat(3) { harness.accept(512, 0.9f) }
        for (confidence in listOf(Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY, -0.1f, 1.1f)) {
            expectInvalid { harness.gate.accept(sourcePcm(1536, 512), confidence) }
            assertEquals(1536L, harness.gate.capturedSamples)
            assertTrue(harness.segments.isEmpty())
        }
        for (pcm in listOf(byteArrayOf(), byteArrayOf(1), ByteArray(1026))) {
            expectInvalid { harness.gate.accept(pcm, 0.9f) }
            assertEquals(1536L, harness.gate.capturedSamples)
        }
        harness.accept(137, 0.9f)
        harness.gate.finish()
        assertSegment("state after invalid input", harness.segments.single(), 0, 1673)
    }

    private class Harness {
        val segments = mutableListOf<AmbientVadSegment>()
        val gate = AmbientVadGate { segments += it }
        var cursor = 0L
            private set

        fun accept(samples: Int, probability: Float) {
            gate.accept(sourcePcm(cursor, samples), probability)
            cursor += samples
        }
    }

    private fun assertSegment(name: String, segment: AmbientVadSegment, start: Long, samples: Int) {
        assertEquals("$name start", start, segment.startSample)
        assertEquals("$name samples", samples * 2, segment.pcm.size)
        assertArrayEquals("$name source PCM slice", sourcePcm(start, samples), segment.pcm)
    }

    private fun contractFile(): File = generateSequence(File("").absoluteFile) { it.parentFile }
        .map { File(it, "contracts/ambient-vad.json") }.first { it.isFile }

    private fun expectInvalid(block: () -> Unit) {
        try { block(); throw AssertionError("Expected invalid VAD input") } catch (_: IllegalArgumentException) { }
    }

    companion object {
        private fun sourcePcm(start: Long, samples: Int): ByteArray = ByteArray(samples * 2).also { bytes ->
            repeat(samples) { offset ->
                val index = start + offset
                val sample = ((index * 31) xor (index ushr 16)).toInt()
                bytes[offset * 2] = sample.toByte()
                bytes[offset * 2 + 1] = (sample ushr 8).toByte()
            }
        }
    }
}
