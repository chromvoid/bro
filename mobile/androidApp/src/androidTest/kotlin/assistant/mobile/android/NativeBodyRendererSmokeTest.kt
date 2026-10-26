package assistant.mobile.android

import android.os.Bundle
import android.os.Looper
import android.system.Os
import android.system.OsConstants
import android.os.SystemClock
import android.view.MotionEvent
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import assistant.mobile.android.body.BodyFilamentView
import assistant.mobile.android.body.BodyRendererDiagnostics
import assistant.mobile.android.body.assets.AndroidBodyAssetRepository
import assistant.mobile.core.NativeHttpClient
import assistant.mobile.core.SessionManager
import assistant.mobile.core.SessionResult
import assistant.mobile.core.SessionSecretStore
import assistant.mobile.core.StoredSession
import assistant.mobile.core.body.*
import assistant.mobile.core.bodyassets.BodyAssetError
import assistant.mobile.core.bodyassets.BodyAssetFailure
import assistant.mobile.core.bodyassets.BodyAssetPhase
import assistant.mobile.core.bodyassets.BodyAssetProgress
import assistant.mobile.platform.NativeCorePlatform
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference
import java.util.zip.ZipFile
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Real EGL/gltfio, exact shipped bytes and synthetic authenticated loopback HTTP on 16 KiB. */
@RunWith(AndroidJUnit4::class)
class NativeBodyRendererSmokeTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var server: NativeLoopbackServer
    private lateinit var sessions: SessionManager
    private lateinit var http: NativeHttpClient
    private lateinit var directory: File
    private var renderer: BodyFilamentView? = null
    private val failure = AtomicReference<Throwable?>()

    @Before fun setup() {
        assertEquals(16_384L, Os.sysconf(OsConstants._SC_PAGESIZE))
        server = NativeLoopbackServer().apply { personalEnabled = true; bodyEnabled = true }
        val secrets = object : SessionSecretStore {
            private var stored: StoredSession? = null
            override suspend fun load(instance: String, origin: String) = stored
            override suspend fun save(session: StoredSession) { stored = session }
            override suspend fun delete(instance: String, origin: String) { stored = null }
        }
        sessions = SessionManager("body-${UUID.randomUUID()}", server.origin, secrets, {}, allowInsecureLoopback = true)
        http = NativeHttpClient(NativeCorePlatform.httpEngine(), server.origin, sessions, allowInsecureLoopback = true)
        assertTrue(runBlocking { sessions.login("person", "Synthetic Password 123!") } is SessionResult.SignedIn)
        directory = File(context.cacheDir, "body-test-${UUID.randomUUID()}")
    }

    @After fun teardown() {
        compose.runOnIdle { renderer?.close(); renderer = null }
        if (::sessions.isInitialized) runBlocking { sessions.logout() }
        if (::http.isInitialized) http.close()
        if (::server.isInitialized) server.close()
        if (::directory.isInitialized) directory.deleteRecursively()
    }

    @Test fun exactThreeBodiesSkinMorphAnimateAndReleaseGpuAcrossLifecycle() {
        // Exercise the real gltfio renderer with the unused Filament utils JNI excluded.
        ZipFile(context.applicationInfo.sourceDir).use { apk ->
            assertFalse(apk.entries().asSequence().any { it.name.endsWith("/libfilament-utils-jni.so") })
        }
        val repo = repository()
        val files = runBlocking { repo.load(assetNames(BodyVariant.Male)) }
        val mounted = mutableStateOf(true)
        var base = BodyRendererFrame(visual = BodyVisualState(fat = 0.4, muscle = 0.5),
            yaw = 1.1, pitch = 1.2, distance = 5.4)
        val clock = BodyAnimationClock()
        compose.setContent {
            val lifecycleOwner = LocalLifecycleOwner.current
            if (mounted.value) {
                AndroidView(modifier = Modifier.fillMaxSize(), factory = { viewContext ->
                    BodyFilamentView(viewContext).also { view ->
                        renderer = view
                        view.onFailure = { failure.set(it) }
                        view.onYaw = { base = base.copy(yaw = it) }
                        view.onFrameTime = { nanos -> view.setFrame(clock.advance(nanos, base)) }
                        view.setFrame(base)
                        view.loadFiles(files.require("male.glb"), files.require("male-lookaround.glb"))
                        view.setActive(true)
                    }
                })
                DisposableEffect(lifecycleOwner) {
                    val observer = LifecycleEventObserver { _, event -> when (event) {
                        Lifecycle.Event.ON_START -> renderer?.setActive(true)
                        Lifecycle.Event.ON_STOP -> renderer?.setActive(false)
                        else -> Unit
                    } }
                    lifecycleOwner.lifecycle.addObserver(observer)
                    onDispose { lifecycleOwner.lifecycle.removeObserver(observer); renderer?.close() }
                }
            }
        }
        for (variant in BodyVariant.entries) {
            val variantFiles = runBlocking { repo.load(assetNames(variant)) }
            val loadedAtFrame = rendererFrames()
            if (variant != BodyVariant.Male) compose.runOnIdle {
                clock.reset()
                base = base.copy(visual = base.visual.copy(template = if (variant == BodyVariant.Female) BodyTemplate.Female else BodyTemplate.Male))
                renderer!!.loadFiles(variantFiles.require("${variant.wire}.glb"), variantFiles.require("${variant.wire}-lookaround.glb"))
            }
            awaitRenderer { it.loadedMorphTargetCount == 34 && it.renderedFrames >= loadedAtFrame + 8 }
            compose.runOnIdle {
                val view = renderer!!.diagnostics
                assertTrue("Exact asset skin joints", view.skinningJointCount >= 100)
                assertTrue(view.animationNames.containsAll(listOf("relaxed", "scenario_calm", "scenario_athletic", "scenario_stage", BODY_LOOKAROUND_CLIP)))
                assertEquals(BODY_LOOKAROUND_CLIP, view.sampledClip)
                assertEquals(BodyPoint(0.0, 1.625, 0.0), view.orbitTarget)
                assertEquals(bodyOrbitPosition(requireNotNull(view.orbitTarget), base.yaw, base.pitch, base.distance), view.orbitPosition)
                assertEquals(34, view.lastMorphWeights.size)
                assertEquals(1f, view.lastMorphWeights.take(30).sum(), 0.00001f)
            }
            val before = jointSnapshot()
            var beforeSample = 0.0
            var beforeNormalization: BodyPoint? = null
            var beforeCamera: BodyPoint? = null
            compose.runOnIdle {
                val diagnostics = renderer!!.diagnostics
                beforeSample = diagnostics.sampledSeconds
                beforeNormalization = diagnostics.normalizationTranslation
                beforeCamera = diagnostics.orbitPosition
                val axis = requireNotNull(diagnostics.normalizedRestAxis)
                assertEquals(0.0, axis.x, 1e-10)
                assertEquals(0.0, axis.z, 1e-10)
            }
            val frameCount = rendererFrames()
            awaitRenderer { it.renderedFrames >= frameCount + 12 }
            var afterSample = 0.0
            compose.runOnIdle {
                val diagnostics = renderer!!.diagnostics
                afterSample = diagnostics.sampledSeconds
                assertEquals(beforeNormalization, diagnostics.normalizationTranslation)
                assertEquals(beforeCamera, diagnostics.orbitPosition)
                assertEquals(BodyPoint(0.0, 1.625, 0.0), diagnostics.orbitTarget)
            }
            assertNotEquals("The external lookaround must move the ${variant.wire} main skeleton " +
                "(sample_seconds=$beforeSample->$afterSample)", before, jointSnapshot())
            compose.runOnIdle {
                val view = renderer!!
                val beforeYaw = base.yaw
                val start = SystemClock.uptimeMillis()
                for ((action, x, offset) in listOf(Triple(MotionEvent.ACTION_DOWN, 160f, 0L),
                    Triple(MotionEvent.ACTION_MOVE, 260f, 20L), Triple(MotionEvent.ACTION_UP, 260f, 40L))) {
                    MotionEvent.obtain(start, start + offset, action, x, 200f, 0).also {
                        view.dispatchTouchEvent(it)
                        it.recycle()
                    }
                }
                assertNotEquals("Finger drag must rotate the real native view", beforeYaw, base.yaw)
            }
            for (layer in BodyLayer.entries) {
                compose.runOnIdle {
                    base = base.copy(layer = layer, visual = base.visual.copy(fat = 0.85, muscle = 0.8))
                    renderer!!.setFrame(clock.advance(System.nanoTime(), base))
                }
                awaitRenderer { it.layer == layer && it.lastMorphWeights.take(30).zip(bodyMorphWeights(base.visual)).all { (actual, expected) -> kotlin.math.abs(actual - expected) < 0.00001 } }
            }
            compose.runOnIdle {
                base = base.copy(comparison = true)
                renderer!!.setFrame(clock.advance(System.nanoTime(), base))
            }
            awaitRenderer { it.sampledClip == "relaxed" && !it.frameCallbackScheduled }
            compose.runOnIdle {
                val view = renderer!!.diagnostics
                assertEquals(BodyPoint(0.0, 1.625, 0.0), view.orbitTarget)
                assertEquals(bodyOrbitPosition(requireNotNull(view.orbitTarget), base.yaw, base.pitch, base.distance), view.orbitPosition)
                base = base.copy(comparison = false)
                renderer!!.setFrame(clock.advance(System.nanoTime(), base))
            }
            awaitRenderer { it.sampledClip == BODY_LOOKAROUND_CLIP }
        }
        compose.runOnIdle {
            base = base.copy(reducedMotion = true)
            renderer!!.setFrame(base)
        }
        awaitRenderer { !it.frameCallbackScheduled }
        compose.runOnIdle { assertEquals(0, BodyFilamentView.pendingCallbacks.get()) }
        compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        assertEquals(0, BodyFilamentView.pendingCallbacks.get())
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        compose.runOnIdle { base = base.copy(reducedMotion = false); renderer!!.setFrame(base); renderer!!.setActive(true) }
        val frames = rendererFrames()
        awaitRenderer { it.renderedFrames >= frames + 8 }
        compose.runOnIdle {
            val samples = renderer!!.diagnostics.submittedFrameCpuNanos.take(60)
            assertTrue("A bounded real renderer timing sample is required", samples.isNotEmpty())
            val sorted = samples.sorted()
            val intervals = renderer!!.diagnostics.frameIntervalsNanos.take(60)
            assertTrue(intervals.isNotEmpty())
            val sortedIntervals = intervals.sorted()
            emit("BodyAtlas frame sample frames=${samples.size} mean_cpu_ms=${samples.average() / 1e6} " +
                "p95_cpu_ms=${sorted[((sorted.size - 1) * .95).toInt()] / 1e6} " +
                "mean_frame_interval_ms=${intervals.average() / 1e6} " +
                "p95_frame_interval_ms=${sortedIntervals[((sortedIntervals.size - 1) * .95).toInt()] / 1e6} page_bytes=16384")
            mounted.value = false
        }
        compose.waitForIdle()
        assertEquals(0, BodyFilamentView.pendingCallbacks.get())
        assertEquals(0, BodyFilamentView.liveRenderers.get())
        compose.activityRule.scenario.recreate()
        assertEquals(0, BodyFilamentView.pendingCallbacks.get())
        assertEquals(0, BodyFilamentView.liveRenderers.get())
        assertTrue(server.failures.toString(), server.failures.isEmpty())
    }

    @Test fun authenticatedDownloadsVerifyHashesExposeProgressAndReopenWithoutNetwork() {
        val progress = mutableListOf<BodyAssetProgress>()
        val repo = repository()
        val names = assetNames(BodyVariant.Male)
        val files = runBlocking { repo.load(names) {
            assertNotEquals("The entire loader must run off the main thread", Looper.getMainLooper(), Looper.myLooper())
            progress.add(it)
        } }
        assertTrue(progress.any { it.phase == BodyAssetPhase.Downloading && it.completedBytes > 0 })
        assertEquals(BodyAssetPhase.Ready, progress.last().phase)
        assertEquals(1f, progress.last().fraction, 0f)
        assertEquals(names, files.files.keys)
        assertTrue(files.require("male.glb").length() > 1_000_000)
        assertTrue(files.require("male-lookaround.glb").length() > 1_000)
        assertTrue(files.require("male.webp").length() > 0)
        assertEquals(names.sumOf { files.manifest.files.getValue(it).bytes }, progress.last().totalBytes)
        val requests = server.body.assetRequests.get()
        assertEquals("One authenticated manifest and only the selected figure's three files", 4, requests)
        server.body.online = false
        runBlocking { repo.load(names) }
        assertEquals("Repeat screen entry must make no HTTP request", requests, server.body.assetRequests.get())
        server.body.online = true
        for (variant in BodyVariant.entries.filter { it != BodyVariant.Male }) {
            val selected = runBlocking { repo.load(assetNames(variant)) }
            assertEquals(assetNames(variant), selected.files.keys)
            assertTrue(selected.require("${variant.wire}.glb").length() > 1_000_000)
            assertTrue(selected.require("${variant.wire}-lookaround.glb").length() > 1_000)
        }
        assertEquals("Switching figures keeps earlier manifest hashes cached", requests + 6, server.body.assetRequests.get())
        assertTrue(files.files.values.all { it.isFile })
        server.body.corruptFile = "male.glb"
        val corruptedDirectory = File(directory, "corrupt")
        val corrupted = repository(corruptedDirectory)
        try {
            runBlocking { corrupted.load(names) }
            fail("The altered bytes must not enter the content-addressed cache")
        } catch (error: BodyAssetFailure) {
            assertEquals(BodyAssetError.Integrity, error.error)
        }
        val maleHash = files.manifest.files.getValue("male.glb").sha256
        assertFalse("Corrupt bytes must never become a cache entry", File(corruptedDirectory, maleHash).exists())
        assertFalse("Failed downloads must remove their partial file", corruptedDirectory.listFiles().orEmpty().any { it.extension == "part" })
        server.body.corruptFile = null
        server.body.online = false
        try {
            runBlocking { repository(File(directory, "offline")).load(names) }
            fail("A missing cache must expose the network error")
        } catch (error: BodyAssetFailure) {
            assertEquals(BodyAssetError.Network, error.error)
        }
        assertTrue(server.failures.toString(), server.failures.isEmpty())
    }

    private fun assetNames(variant: BodyVariant) = setOf("${variant.wire}.glb", "${variant.wire}-lookaround.glb", "${variant.wire}.webp")
    private fun repository(cache: File = directory) = AndroidBodyAssetRepository(context, http, sessions, cache)
    private fun awaitRenderer(predicate: (BodyRendererDiagnostics) -> Boolean) = compose.waitUntil(30_000) {
        failure.get()?.let { throw AssertionError("Filament renderer failed on CI EGL/GPU: ${it.message}", it) }
        var ready = false
        compose.runOnIdle { renderer?.let { ready = predicate(it.diagnostics) } }
        ready
    }
    private fun rendererFrames(): Long { var frames = 0L; compose.runOnIdle { frames = renderer!!.diagnostics.renderedFrames }; return frames }
    private fun jointSnapshot(): Map<String, List<Float>> { var value = emptyMap<String, List<Float>>(); compose.runOnIdle { value = renderer!!.jointTransformSnapshot() }; return value }
    private fun emit(value: String) = InstrumentationRegistry.getInstrumentation().sendStatus(2, Bundle().apply { putString("stream", "\n$value\n") })
}
