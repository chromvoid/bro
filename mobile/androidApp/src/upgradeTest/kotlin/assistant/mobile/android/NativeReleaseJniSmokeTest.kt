package assistant.mobile.android

import android.Manifest
import android.os.SystemClock
import android.util.Base64
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import assistant.mobile.android.ambient.AmbientSileroVad
import assistant.mobile.android.ambient.AmbientVadGate
import assistant.mobile.android.ambient.AmbientVadSegment
import assistant.mobile.android.body.BodyFilamentView
import assistant.mobile.core.calls.CallIceConfiguration
import assistant.mobile.core.calls.CallMediaConnection
import assistant.mobile.core.calls.CallMediaListener
import assistant.mobile.platform.AndroidCallMediaFactory
import assistant.mobile.platform.ring.NativeRing
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.FixMethodOrder
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters

/** Application entry points only: the tested release owns every third-party JNI class. */
@RunWith(AndroidJUnit4::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class NativeReleaseJniSmokeTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()

    @Test
    fun filamentAndGltfioLoadTheRealBodyWithoutAWindow() {
        val context = instrumentation.targetContext
        val directory = context.cacheDir.resolve("release-jni-body-${SystemClock.elapsedRealtimeNanos()}")
        check(directory.mkdirs())
        try {
            val model = directory.resolve("male.glb")
            val lookaround = directory.resolve("male-lookaround.glb")
            for (file in listOf(model, lookaround)) {
                instrumentation.context.assets.open("body/${file.name}").use { source ->
                    file.outputStream().use { destination -> source.copyTo(destination) }
                }
            }
            var failure: Throwable? = null
            instrumentation.runOnMainSync {
                runCatching {
                    // Loading creates the EGL engine and native resources synchronously. An
                    // unattached, inactive view needs no window, swapchain or frame callback.
                    BodyFilamentView(context).use { view ->
                        view.loadFiles(model, lookaround)
                        assertNull("Filament/gltfio loading failed", view.diagnostics.failure)
                        assertTrue("The real body must finish loading", view.diagnostics.ready)
                        assertNotNull("Framing must cross getAxisAlignedBoundingBox JNI", view.diagnostics.orbitTarget)
                        assertTrue("The real body's morph targets must load", view.diagnostics.loadedMorphTargetCount > 0)
                    }
                }.onFailure { failure = it }
            }
            failure?.let { throw it }
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun webRtcNativeOfferAndAnswerCallbacksReturnThroughTheAppFactory() = runBlocking {
        val context = instrumentation.targetContext
        instrumentation.uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.RECORD_AUDIO)
        // The production factory acquires audio focus only while the app is in the foreground.
        ActivityScenario.launch(MainActivity::class.java).use {
            AndroidCallMediaFactory(context, requestPermission = { true }).use { factory ->
                val listener = object : CallMediaListener {
                    override fun connection(state: CallMediaConnection) = Unit
                }
                val caller = factory.create(listener)
                val offer = try {
                    caller.acquire()
                    caller.configure(CallIceConfiguration(emptyList()))
                    withTimeout(20_000) { caller.createOffer() }
                } finally {
                    // Release capture and audio focus before the second production peer acquires them.
                    caller.close()
                }
                assertTrue("Native SDP creation must return through SdpObserver", offer.contains("m=audio "))
                val callee = factory.create(listener)
                try {
                    callee.acquire()
                    callee.configure(CallIceConfiguration(emptyList()))
                    // The release-upgrade emulator is offline. SDP create/set callbacks need
                    // no server, connected peer or available interface for host ICE candidates.
                    val answer = withTimeout(20_000) { callee.answerOffer(offer) }
                    assertTrue("Native SDP answer must return through SdpObserver", answer.contains("m=audio "))
                    assertNotEquals("The native answer must differ from the offer", offer, answer)
                } finally {
                    callee.close()
                }
            }
        }
    }

    @Test
    fun onnxSileroVadRecognizesTheSharedSyntheticSpeechFixture() {
        val fixture = Json.parseToJsonElement(instrumentation.context.assets.open("ambient-vad-audio.json")
            .bufferedReader().use { it.readText() }).jsonObject
        val speech = Base64.decode(fixture.getValue("pcm16le_base64").jsonPrimitive.content, Base64.DEFAULT)
        assertEquals(16_000, fixture.getValue("sample_rate").jsonPrimitive.int)
        assertEquals(fixture.getValue("sample_count").jsonPrimitive.int * 2, speech.size)
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
        assertFalse("The application VAD must retain speech", clips.isEmpty())
        assertEquals(source.size / 2L, gate.capturedSamples)
        assertTrue(clips.first().startSample > 0)
        assertTrue(clips.last().startSample + clips.last().pcm.size / 2 < source.size / 2)
        assertTrue(clips.sumOf { it.pcm.size } < source.size - silenceBytes)
        clips.forEach { clip ->
            val offset = clip.startSample.toInt() * 2
            assertArrayEquals(source.copyOfRange(offset, offset + clip.pcm.size), clip.pcm)
        }
    }

    @Test
    fun ringRustSessionRoundTripsOwnedNativeBuffers() {
        assertEquals(1, NativeRing.apiVersion())
        val config = """{"generation":1,"timezone":"Europe/Moscow","local_date":"2026-10-05","history_days":30,"clock":{"year":2026,"month":10,"day":5,"hour":12,"minute":0,"second":0,"language":10},"android":{"sdk":36,"model":"Synthetic"}}"""
        val handle = NativeRing.create(config.encodeToByteArray())
        assertNotEquals(0L, handle)
        try {
            val bytes = NativeRing.dispatch(handle, """{"type":"connected","generation":1}""".encodeToByteArray(), 0)
            assertTrue("Rust must return its effects through JNI", Json.parseToJsonElement(bytes.decodeToString()) is JsonArray)
        } finally {
            NativeRing.destroy(handle)
        }
    }
}
