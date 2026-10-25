package assistant.mobile.android.ambient

import android.system.Os
import android.system.OsConstants
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import assistant.mobile.android.BuildConfig
import java.security.MessageDigest
import java.util.Base64
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Actual bundled ONNX graph and JNI on CI's disposable 16 KiB emulator; no microphone access. */
@RunWith(AndroidJUnit4::class)
class AmbientSileroVadSmokeTest {
    @Test
    fun packagedGraphAndLicensesHaveTheOriginalProvenance() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assertEquals(16_384L, Os.sysconf(OsConstants._SC_PAGESIZE))
        val model = context.assets.open(BuildConfig.AMBIENT_VAD_MODEL_FILE).use { it.readBytes() }
        assertEquals(BuildConfig.AMBIENT_VAD_MODEL_SHA256, sha256(model))
        assertEquals("1a153a22f4509e292a94e67d6f9b85e8deb25b4988682b7e174c65279d8788e3", sha256(model))
        for (name in listOf("SILERO-LICENSE", "ONNXRUNTIME-LICENSE.txt", "ONNXRUNTIME-ThirdPartyNotices.txt")) {
            val license = context.assets.open(name).bufferedReader().use { it.readText() }
            assertFalse("Missing original license $name", license.isBlank())
        }
    }

    @Test
    fun actualBundledVadRetainsSpeechAndOmitsLongSilenceWithOriginalCoordinates() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val fixture = JSONObject(instrumentation.context.assets.open("ambient-vad-audio.json").bufferedReader().use { it.readText() })
        val speech = Base64.getDecoder().decode(fixture.getString("pcm16le_base64"))
        assertEquals(16_000, fixture.getInt("sample_rate"))
        assertEquals(fixture.getInt("sample_count") * 2, speech.size)
        assertEquals(fixture.getString("sha256"), sha256(speech))
        val silenceBytes = 100 * AmbientVadGate.FRAME_SAMPLES * 2
        val source = ByteArray(silenceBytes + speech.size + silenceBytes)
        speech.copyInto(source, silenceBytes)
        val clips = mutableListOf<AmbientVadSegment>()
        val gate = AmbientVadGate(clips::add)
        var run = 0
        var strongestRun = 0
        AmbientSileroVad.fromAssets(instrumentation.targetContext).use { detector ->
            for (offset in source.indices step AmbientVadGate.FRAME_SAMPLES * 2) {
                val frame = source.copyOfRange(offset, minOf(source.size, offset + AmbientVadGate.FRAME_SAMPLES * 2))
                val probability = detector.probability(frame)
                if (offset < silenceBytes) assertTrue("Silence cannot become speech", probability < 0.5f)
                run = if (probability >= 0.5f) run + 1 else 0
                strongestRun = maxOf(strongestRun, run)
                gate.accept(frame, probability)
            }
            gate.finish()
        }
        assertTrue("The actual graph must find sustained synthetic speech", strongestRun >= 3)
        assertTrue(clips.isNotEmpty())
        assertEquals(source.size / 2L, gate.capturedSamples)
        assertTrue(clips.first().startSample > 0)
        assertTrue(clips.last().startSample + clips.last().pcm.size / 2 < source.size / 2)
        assertTrue(clips.sumOf { it.pcm.size } < source.size - silenceBytes)
        clips.forEach { clip ->
            val offset = clip.startSample.toInt() * 2
            assertArrayEquals(source.copyOfRange(offset, offset + clip.pcm.size), clip.pcm)
        }
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it) }
}
