package assistant.mobile.core.dictation

import assistant.mobile.core.FEATURE_NOW
import assistant.mobile.core.FEATURE_ORIGIN
import assistant.mobile.core.FeatureSessionSecrets
import assistant.mobile.core.LiveConnection
import assistant.mobile.core.LiveSocket
import assistant.mobile.core.LiveSocketFactory
import assistant.mobile.core.LiveSocketListener
import assistant.mobile.core.NativeJson
import assistant.mobile.core.NativeHttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import assistant.mobile.core.SessionManager
import assistant.mobile.core.featureRecord
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.assertNotEquals
import java.util.UUID

@OptIn(ExperimentalCoroutinesApi::class)
class DictationControllerTest {
    @Test
    fun twoDictationsReuseOneForegroundSocketAndIgnoreStaleIds() = runTest {
        val fixture = Fixture(backgroundScope)
        runCurrent()
        assertEquals(1, fixture.sockets.size)
        assertTrue(fixture.socket.controls.isEmpty())
        fixture.controller.start()
        runCurrent()
        val firstId = fixture.socket.id
        assertEquals(firstId, UUID.fromString(firstId).toString())
        fixture.socket.ready()
        runCurrent()
        fixture.audio.frame()
        fixture.controller.stop()
        fixture.socket.final("first")
        runCurrent()
        assertEquals(DictationPhase.REVIEW, fixture.state.phase)
        assertFalse(fixture.socket.cancelled)

        fixture.controller.start()
        runCurrent()
        val secondId = fixture.socket.id
        assertNotEquals(firstId, secondId)
        val captured = ByteArray(22_400) { 7 }
        fixture.audio.frame(captured)
        fixture.socket.ready(firstId)
        fixture.socket.text("dictation.partial", text = "stale preview", id = firstId)
        fixture.socket.text("dictation.error", error = "provider_error", id = firstId)
        fixture.socket.final("stale result", firstId)
        runCurrent()
        assertEquals(DictationPhase.RECORDING, fixture.state.phase)
        assertEquals("", fixture.state.partial)
        assertEquals("first", fixture.draft.text)
        assertEquals(1, fixture.socket.frames.size)
        fixture.socket.ready()
        runCurrent()
        assertContentEquals(captured, fixture.socket.frames.last())
        fixture.controller.stop()
        fixture.socket.final("second")
        runCurrent()
        assertEquals("first second", fixture.draft.text)
        assertEquals(DictationPhase.REVIEW, fixture.state.phase)
        assertEquals(1, fixture.sockets.size)
        assertEquals(listOf("dictation.start", "dictation.finish", "dictation.start", "dictation.finish"),
            fixture.socket.controls.map { it.type })
        assertEquals(listOf(firstId, firstId, secondId, secondId), fixture.socket.controls.map { it.id })
    }

    @Test
    fun dictationWhileConnectingCapturesImmediatelyAndFlushesOnlyAfterReady() = runTest {
        val fixture = Fixture(backgroundScope, autoOpen = false)
        runCurrent()
        fixture.controller.start()
        runCurrent()
        assertTrue(fixture.audio.capturing)
        val captured = listOf(ByteArray(8000) { 1 }, ByteArray(16_000) { 2 })
        captured.forEach { fixture.audio.frame(it) }
        fixture.controller.stop()
        assertTrue(fixture.socket.controls.isEmpty())
        assertTrue(fixture.socket.frames.isEmpty())
        fixture.socket.open()
        runCurrent()
        assertEquals("dictation.start", fixture.socket.controls.single().type)
        assertTrue(fixture.socket.frames.isEmpty())
        fixture.socket.ready()
        runCurrent()
        assertEquals(listOf("pcm:8000", "pcm:16000", "finish"), fixture.socket.sent)
        captured.zip(fixture.socket.frames).forEach { (expected, actual) -> assertContentEquals(expected, actual) }
        fixture.socket.final("captured before connect")
        runCurrent()
        assertEquals("captured before connect", fixture.draft.text)
        assertEquals(1, fixture.sockets.size)
    }

    @Test
    fun droppedDictationIsInterruptedAndNextTapReconnectsWithoutWaitingForBackoff() = runTest {
        val fixture = Fixture(backgroundScope, autoOpen = false)
        fixture.controller.start()
        runCurrent()
        val dropped = fixture.socket
        dropped.open()
        runCurrent()
        dropped.ready()
        runCurrent()
        fixture.audio.frame()
        dropped.listener.failed()
        runCurrent()
        assertEquals(DictationPhase.INTERRUPTED, fixture.state.phase)
        assertEquals(DictationProblem.CONNECTION, fixture.state.problem)
        assertFalse(fixture.audio.capturing)
        assertTrue(dropped.cancelled)
        assertEquals(1, fixture.sockets.size)
        val tappedAt = testScheduler.currentTime
        fixture.controller.start()
        runCurrent()
        assertEquals(tappedAt, testScheduler.currentTime)
        assertEquals(2, fixture.sockets.size)
        assertTrue(fixture.audio.capturing)
        assertTrue(fixture.socket.controls.isEmpty())
        assertTrue(fixture.socket.frames.isEmpty())
        fixture.audio.frame()
        fixture.socket.open()
        runCurrent()
        assertEquals("dictation.start", fixture.socket.controls.single().type)
        fixture.socket.ready()
        dropped.final("lost recording")
        dropped.listener.closed()
        runCurrent()
        assertEquals(1, fixture.socket.frames.size)
        fixture.controller.stop()
        fixture.socket.final("next recording")
        runCurrent()
        assertEquals(DictationPhase.REVIEW, fixture.state.phase)
        assertEquals("next recording", fixture.draft.text)
        advanceTimeBy(500)
        runCurrent()
        assertEquals(2, fixture.sockets.size)
    }

    @Test
    fun foregroundAccountChangesAndNetworkRecoveryOwnTheSocket() = runTest {
        val fixture = Fixture(backgroundScope, foreground = false)
        runCurrent()
        assertTrue(fixture.sockets.isEmpty())
        fixture.live.setForeground(true)
        fixture.live.setForeground(true)
        runCurrent()
        assertEquals(1, fixture.sockets.size)
        fixture.live.networkAvailable()
        assertEquals(1, fixture.sockets.size)
        fixture.socket.listener.failed()
        runCurrent()
        fixture.live.networkAvailable()
        runCurrent()
        assertEquals(2, fixture.sockets.size)
        advanceTimeBy(500)
        runCurrent()
        assertEquals(2, fixture.sockets.size)
        fixture.live.setForeground(false)
        assertTrue(fixture.socket.cancelled)
        fixture.live.networkAvailable()
        advanceTimeBy(20_000)
        runCurrent()
        assertEquals(2, fixture.sockets.size)
        fixture.live.setForeground(true)
        runCurrent()
        val oldAccountSocket = fixture.socket
        val previous = featureRecord()
        fixture.secrets.value = previous.copy(namespace = previous.namespace.copy(userId = "another"),
            identity = previous.identity.copy(id = "another"))
        fixture.sessions.restore()
        runCurrent()
        assertTrue(oldAccountSocket.cancelled)
        assertEquals(4, fixture.sockets.size)
        val http = NativeHttpClient(MockEngine { respond("{}") }, FEATURE_ORIGIN, fixture.sessions)
        try { fixture.sessions.logout() } finally { http.close() }
        runCurrent()
        assertTrue(fixture.socket.cancelled)
        fixture.live.networkAvailable()
        advanceTimeBy(20_000)
        runCurrent()
        assertEquals(4, fixture.sockets.size)
    }

    @Test
    fun failedUpgradesBackOffToTenSecondsAndNetworkRecoverySkipsTheWait() = runTest {
        val fixture = Fixture(backgroundScope, autoOpen = false)
        runCurrent()
        for (pause in listOf(500L, 1000L, 2000L, 4000L, 8000L, 10_000L, 10_000L)) {
            val before = fixture.sockets.size
            fixture.socket.listener.failed()
            runCurrent()
            advanceTimeBy(pause - 1)
            runCurrent()
            assertEquals(before, fixture.sockets.size)
            advanceTimeBy(1)
            runCurrent()
            assertEquals(before + 1, fixture.sockets.size)
        }
        fixture.socket.listener.failed()
        runCurrent()
        val before = fixture.sockets.size
        fixture.live.networkAvailable()
        runCurrent()
        assertEquals(before + 1, fixture.sockets.size)
        fixture.live.close()
        assertTrue(fixture.socket.cancelled)
    }

    @Test
    fun cachedUnavailableIsRetriedDuringCaptureAndConfiguredReadinessIsReused() = runTest {
        var checks = 0
        val refreshed = CompletableDeferred<DictationReadinessResult>()
        val fixture = Fixture(backgroundScope, readiness = DictationReadiness {
            checks++
            if (checks == 1) DictationReadinessResult.Unavailable(DictationProblem.CONNECTION)
                else refreshed.await()
        })
        fixture.controller.refreshReadiness()
        runCurrent()
        assertEquals(1, checks)
        fixture.controller.start()
        runCurrent()
        assertEquals(DictationPhase.RECORDING, fixture.state.phase)
        assertEquals(null, fixture.state.problem)
        assertTrue(fixture.audio.capturing)
        assertEquals(2, checks)
        val captured = ByteArray(22_400) { 1 }
        fixture.audio.frame(captured)
        assertTrue(fixture.socket.sent.isEmpty())
        refreshed.complete(DictationReadinessResult.Configured)
        runCurrent()
        assertTrue(fixture.audio.capturing)
        fixture.socket.ready()
        runCurrent()
        assertEquals(listOf("pcm:22400"), fixture.socket.sent)
        assertContentEquals(captured, fixture.socket.frames.single())
        assertEquals(null, fixture.state.problem)
        val configured = Fixture(backgroundScope, readiness = DictationReadiness {
            checks++
            DictationReadinessResult.Configured
        })
        configured.controller.refreshReadiness()
        runCurrent()
        configured.controller.start()
        runCurrent()
        assertTrue(configured.audio.capturing)
        assertEquals(3, checks)
    }

    @Test
    fun permissionDenialKeepsItsErrorAndGrantStartsCaptureBeforeReady() = runTest {
        val fixture = Fixture(backgroundScope)
        fixture.audio.permission = DictationProblem.PERMISSION_DENIED
        fixture.controller.start()
        runCurrent()
        assertEquals(DictationProblem.PERMISSION_DENIED, fixture.state.problem)
        assertEquals(DictationPhase.FAILED, fixture.state.phase)
        assertEquals(0, fixture.audio.starts)
        assertTrue(fixture.sockets.single().controls.isEmpty())

        val granted = Fixture(backgroundScope)
        val permission = CompletableDeferred<Unit>()
        granted.audio.permissionGate = permission
        granted.controller.start()
        runCurrent()
        assertEquals(DictationPhase.PERMISSION, granted.state.phase)
        assertFalse(granted.audio.capturing)
        permission.complete(Unit)
        runCurrent()
        assertEquals(DictationPhase.RECORDING, granted.state.phase)
        assertTrue(granted.audio.capturing)
        assertTrue(granted.socket.sent.isEmpty())
    }

    @Test
    fun partialIsPreviewAndFinalOnlyFillsDraftAfterFinish() = runTest {
        val fixture = Fixture(backgroundScope)
        fixture.draft = DictationDraftSnapshot("Already typed", 4)
        fixture.controller.start()
        runCurrent()
        fixture.socket.ready()
        runCurrent()
        fixture.audio.frame(ByteArray(22_400), .75f)
        fixture.socket.text("dictation.partial", text = "running transcript")
        runCurrent()
        assertEquals("running transcript", fixture.state.partial)
        assertEquals("Already typed", fixture.draft.text)
        assertEquals(.75f, fixture.state.level)
        assertEquals(listOf("pcm:22400"), fixture.socket.sent)
        fixture.controller.stop()
        assertFalse(fixture.audio.capturing)
        assertEquals(listOf("pcm:22400", "finish"), fixture.socket.sent)
        assertEquals(DictationPhase.FINALIZING, fixture.state.phase)
        fixture.socket.final("recognized text")
        runCurrent()
        assertEquals(DictationPhase.REVIEW, fixture.state.phase)
        assertEquals("Already typed recognized text", fixture.draft.text)
        assertEquals("recognized text", fixture.state.finalText)
        assertEquals(1, fixture.applied.size)
        assertEquals(1, fixture.latencies.size)
        assertFalse(fixture.socket.cancelled)
        assertEquals(listOf("dictation.start", "dictation.finish"), fixture.socket.controls.map { it.type })
    }

    @Test
    fun finalKeepsManualEditsVisibleEvenWhenTextWasRestored() = runTest {
        val fixture = Fixture(backgroundScope)
        fixture.draft = DictationDraftSnapshot("Original", 8)
        fixture.controller.start()
        runCurrent()
        fixture.socket.ready()
        runCurrent()
        fixture.audio.frame()
        fixture.controller.stop()
        fixture.draft = DictationDraftSnapshot("Original", 10)
        fixture.socket.final("late recognition")
        runCurrent()
        assertEquals("Original", fixture.draft.text)
        assertTrue(fixture.applied.isEmpty())
        assertTrue(fixture.state.draftPreserved)
        assertEquals("late recognition", fixture.state.finalText)
        assertEquals(DictationPhase.REVIEW, fixture.state.phase)
        val review = fixture.state
        fixture.controller.cancel()
        assertEquals(review, fixture.state)
    }

    @Test
    fun cancelDuringRecordingOrFinalizingPreservesTheEditableDraft() = runTest {
        for (finalizing in listOf(false, true)) {
            val fixture = Fixture(backgroundScope)
            fixture.draft = DictationDraftSnapshot("Already typed", 4)
            fixture.controller.start()
            runCurrent()
            fixture.socket.ready()
            runCurrent()
            fixture.audio.frame()
            fixture.socket.text("dictation.partial", text = "uncommitted recognition")
            runCurrent()
            if (finalizing) fixture.controller.stop()
            fixture.draft = DictationDraftSnapshot("Already typed and edited", 5)
            fixture.controller.cancel()
            fixture.socket.final("late recognition")
            runCurrent()
            assertEquals(DictationPhase.CANCELLED, fixture.state.phase)
            assertEquals("Already typed and edited", fixture.draft.text)
            assertTrue(fixture.applied.isEmpty())
            assertFalse(fixture.audio.capturing)
            assertTrue(fixture.socket.controls.any { it.type == "dictation.cancel" })
        }
    }

    @Test
    fun cancelImmediatelyReleasesMicAndDropsOldFinalAcrossRestart() = runTest {
        val fixture = Fixture(backgroundScope)
        fixture.controller.start()
        runCurrent()
        fixture.socket.ready()
        runCurrent()
        fixture.audio.frame()
        fixture.controller.stop()
        val oldSocket = fixture.socket
        val oldId = oldSocket.id
        fixture.controller.cancel()
        assertFalse(fixture.audio.capturing)
        assertFalse(oldSocket.cancelled)
        assertEquals("dictation.cancel", oldSocket.controls.last().type)
        assertEquals(DictationPhase.CANCELLED, fixture.state.phase)
        oldSocket.final("cancelled session")
        runCurrent()
        assertEquals("", fixture.draft.text)
        fixture.controller.start()
        runCurrent()
        fixture.socket.ready()
        oldSocket.final("still cancelled", oldId)
        runCurrent()
        assertEquals(DictationPhase.RECORDING, fixture.state.phase)
        assertEquals("", fixture.draft.text)
        assertEquals(1, fixture.sockets.size)
        assertNotEquals(oldId, fixture.socket.id)
    }

    @Test
    fun captureAndLevelStartWhileReadinessAndConnectionArePending() = runTest {
        val check = CompletableDeferred<DictationReadinessResult>()
        val fixture = Fixture(backgroundScope, readiness = DictationReadiness { check.await() })
        fixture.controller.start()
        runCurrent()
        assertEquals(DictationPhase.RECORDING, fixture.state.phase)
        assertTrue(fixture.audio.capturing)
        val buffered = listOf(ByteArray(8000) { 1 }, ByteArray(16_000) { 2 })
        buffered.forEach { fixture.audio.frame(it, .75f) }
        assertEquals(.75f, fixture.state.level)
        assertTrue(fixture.socket.sent.isEmpty())
        fixture.socket.ready()
        runCurrent()
        val live = ByteArray(640) { 3 }
        fixture.audio.frame(live)
        assertEquals(listOf("pcm:8000", "pcm:16000", "pcm:640"), fixture.socket.sent)
        (buffered + listOf(live)).zip(fixture.socket.frames).forEach { (expected, actual) ->
            assertContentEquals(expected, actual)
        }
        check.complete(DictationReadinessResult.Configured)
        runCurrent()
        assertEquals(3, fixture.socket.frames.size)
        fixture.controller.cancel()
        fixture.socket.final("cancelled recognition")
        runCurrent()
        assertEquals(DictationPhase.CANCELLED, fixture.state.phase)
        assertFalse(fixture.audio.capturing)
        assertTrue(fixture.applied.isEmpty())
    }

    @Test
    fun stopBeforeReadyFlushesCapturedAndStopTailBeforeFinish() = runTest {
        val fixture = Fixture(backgroundScope)
        fixture.controller.start()
        runCurrent()
        val captured = ByteArray(22_000) { 1 }
        val tail = ByteArray(400) { 2 }
        fixture.audio.frame(captured)
        fixture.audio.onStop = { fixture.audio.frame(tail) }
        fixture.controller.stop()
        assertFalse(fixture.audio.capturing)
        assertEquals(DictationPhase.FINALIZING, fixture.state.phase)
        assertTrue(fixture.socket.sent.isEmpty())
        fixture.socket.ready()
        runCurrent()
        assertEquals(listOf("pcm:22000", "pcm:400", "finish"), fixture.socket.sent)
        assertContentEquals(captured, fixture.socket.frames[0])
        assertContentEquals(tail, fixture.socket.frames[1])
        fixture.socket.final("buffered recognition")
        runCurrent()
        assertEquals("buffered recognition", fixture.draft.text)

        val awaitingReady = Fixture(backgroundScope)
        awaitingReady.controller.start()
        runCurrent()
        awaitingReady.audio.frame()
        awaitingReady.controller.stop()
        advanceTimeBy(34_999)
        runCurrent()
        assertEquals(DictationPhase.FINALIZING, awaitingReady.state.phase)
        advanceTimeBy(1)
        runCurrent()
        assertEquals(DictationProblem.TIMEOUT, awaitingReady.state.problem)
        assertTrue(awaitingReady.socket.sent.isEmpty())
    }

    @Test
    fun bufferOverflowAndLateReadinessFailureReleaseCaptureWithTheirError() = runTest {
        val fullBuffer = Fixture(backgroundScope)
        fullBuffer.controller.start()
        runCurrent()
        repeat(10) { fullBuffer.audio.frame() }
        fullBuffer.socket.growQueue = true
        fullBuffer.socket.ready()
        runCurrent()
        assertEquals(10, fullBuffer.socket.frames.size)
        assertEquals(DICTATION_MAX_BUFFER_BYTES, fullBuffer.socket.queuedBytes)
        assertTrue(fullBuffer.audio.capturing)
        assertEquals(null, fullBuffer.state.problem)
        fullBuffer.controller.stop()
        assertEquals("finish", fullBuffer.socket.sent.last())

        val fixture = Fixture(backgroundScope)
        fixture.controller.start()
        runCurrent()
        repeat(10) { fixture.audio.frame() }
        assertTrue(fixture.audio.capturing)
        assertTrue(fixture.socket.sent.isEmpty())
        fixture.audio.frame(ByteArray(2))
        assertEquals(DictationProblem.CONNECTION, fixture.state.problem)
        assertEquals(DictationPhase.INTERRUPTED, fixture.state.phase)
        assertFalse(fixture.audio.capturing)
        assertTrue(fixture.socket.controls.any { it.type == "dictation.cancel" })
        fixture.socket.ready()
        runCurrent()
        assertTrue(fixture.socket.sent.isEmpty())

        val check = CompletableDeferred<DictationReadinessResult>()
        val unavailable = Fixture(backgroundScope, readiness = DictationReadiness { check.await() })
        unavailable.controller.start()
        runCurrent()
        assertTrue(unavailable.audio.capturing)
        check.complete(DictationReadinessResult.Unavailable(DictationProblem.NOT_CONFIGURED))
        runCurrent()
        assertEquals(DictationProblem.NOT_CONFIGURED, unavailable.state.problem)
        assertFalse(unavailable.audio.capturing)
        assertEquals("dictation.cancel", unavailable.socket.controls.last().type)
        val failure = unavailable.state
        unavailable.controller.cancel()
        assertEquals(failure, unavailable.state)
    }

    @Test
    fun shortRecordingsCancelAndUnrecognizedSpeechKeepsAudioForRetry() = runTest {
        for (ready in listOf(false, true)) {
            for (capturedBytes in listOf(0, 22_398)) {
                val fixture = Fixture(backgroundScope)
                fixture.draft = DictationDraftSnapshot("Typed", 3)
                fixture.controller.start()
                runCurrent()
                if (ready) { fixture.socket.ready(); runCurrent() }
                if (capturedBytes > 0) fixture.audio.frame(ByteArray(capturedBytes))
                fixture.controller.stop()
                assertEquals(DictationPhase.CANCELLED, fixture.state.phase)
                assertEquals(null, fixture.state.problem)
                assertEquals("Typed", fixture.draft.text)
                assertTrue(fixture.applied.isEmpty())
                assertFalse(fixture.socket.sent.contains("finish"))
                assertTrue(fixture.socket.controls.any { it.type == "dictation.cancel" })
            }
        }
        for (result in listOf("dictation.final", "dictation.error")) {
            val fixture = Fixture(backgroundScope)
            fixture.draft = DictationDraftSnapshot("Typed", 3)
            fixture.controller.start()
            runCurrent()
            fixture.socket.ready()
            runCurrent()
            fixture.audio.frame()
            fixture.controller.stop()
            fixture.socket.text(result, text = "", error = "empty_transcript")
            runCurrent()
            assertEquals(DictationPhase.FAILED, fixture.state.phase)
            assertEquals(DictationProblem.NOT_RECOGNIZED, fixture.state.problem)
            assertTrue(fixture.state.canRetry)
            assertEquals("Typed", fixture.draft.text)
            assertTrue(fixture.applied.isEmpty())
            assertFalse(fixture.socket.cancelled)
        }
    }

    @Test
    fun noSpeechCancelsSilentlyAfterDrainingDetectorWithoutDelayingPcm() = runTest {
        val verdict = CompletableDeferred<Boolean>()
        val detector = FakeSpeechDetector(verdict)
        val fixture = Fixture(backgroundScope, createSpeechDetector = { detector })
        fixture.controller.start()
        runCurrent()
        fixture.socket.ready()
        runCurrent()
        val captured = listOf(ByteArray(16_000) { 1 }, ByteArray(16_000) { 2 })
        captured.forEach { fixture.audio.frame(it) }
        assertEquals(2, fixture.socket.frames.size)
        fixture.controller.stop()
        assertFalse(fixture.audio.capturing)
        assertEquals(DictationPhase.FINALIZING, fixture.state.phase)
        assertFalse(fixture.socket.sent.contains("finish"))
        verdict.complete(false)
        runCurrent()
        captured.zip(detector.frames).forEach { (expected, actual) -> assertContentEquals(expected, actual) }
        assertEquals(DictationPhase.CANCELLED, fixture.state.phase)
        assertEquals(null, fixture.state.problem)
        assertFalse(fixture.state.canRetry)
        assertEquals(listOf("dictation.start", "dictation.cancel"), fixture.socket.controls.map { it.type })
        assertTrue(fixture.applied.isEmpty())
    }

    @Test
    fun failedDetectorFallsBackToSpeechHeard() = runTest {
        val failed = CompletableDeferred<Boolean>().apply { completeExceptionally(IllegalStateException("Model unavailable")) }
        val fixture = Fixture(backgroundScope, createSpeechDetector = { FakeSpeechDetector(failed) })
        fixture.controller.start()
        runCurrent()
        fixture.audio.frame()
        fixture.controller.stop()
        fixture.socket.ready()
        runCurrent()
        assertEquals(listOf("pcm:32000", "finish"), fixture.socket.sent)
        fixture.socket.final("speech with unavailable VAD")
        runCurrent()
        assertEquals("speech with unavailable VAD", fixture.draft.text)
        assertEquals(DictationPhase.REVIEW, fixture.state.phase)
    }

    @Test
    fun retryAfterSocketLossReplays120SecondsWithSlowBacklogProgressAndAppliesFinal() = runTest {
        val fixture = Fixture(backgroundScope)
        fixture.controller.start()
        runCurrent()
        fixture.socket.ready()
        runCurrent()
        val captured = (1..DICTATION_MAX_SECONDS).map { value -> ByteArray(32_000) { value.toByte() } }
        captured.forEach { fixture.audio.frame(it) }
        fixture.controller.stop()
        val firstSocket = fixture.socket
        firstSocket.listener.failed()
        runCurrent()
        assertEquals(DictationProblem.CONNECTION, fixture.state.problem)
        assertTrue(fixture.state.canRetry)
        fixture.controller.retry()
        runCurrent()
        val retrySocket = fixture.socket
        assertNotEquals(firstSocket.id, retrySocket.id)
        assertEquals(1, fixture.audio.starts)
        retrySocket.growQueue = true
        retrySocket.ready()
        runCurrent()
        assertEquals(10, retrySocket.frames.size)
        assertEquals(DICTATION_MAX_BACKLOG_BYTES, retrySocket.queuedBytes)
        assertFalse(retrySocket.sent.contains("finish"))
        val replayStartedAt = testScheduler.currentTime
        repeat(DICTATION_MAX_SECONDS - 10) { index ->
            advanceTimeBy(1000)
            runCurrent()
            assertEquals(DictationPhase.FINALIZING, fixture.state.phase)
            retrySocket.queuedBytes -= DICTATION_BYTES_PER_SECOND
            advanceTimeBy(20)
            runCurrent()
            assertEquals(11 + index, retrySocket.frames.size)
            assertTrue(retrySocket.queuedBytes <= DICTATION_MAX_BACKLOG_BYTES)
        }
        assertTrue(testScheduler.currentTime - replayStartedAt > 35_000)
        assertEquals(DICTATION_MAX_SECONDS, retrySocket.frames.size)
        assertEquals(null, fixture.state.problem)
        captured.zip(retrySocket.frames).forEach { (expected, actual) -> assertContentEquals(expected, actual) }
        assertEquals("finish", retrySocket.sent.last())
        firstSocket.final("stale result")
        retrySocket.final("retried recognition")
        runCurrent()
        assertEquals("retried recognition", fixture.draft.text)
        assertEquals(1, fixture.applied.size)
        assertFalse(fixture.state.canRetry)
        fixture.controller.retry()
        assertEquals(1, retrySocket.controls.count { it.type == "dictation.start" })
    }

    @Test
    fun retryableFailureWaitsForTheSameDetectorAndKeepsOnlySpeech() = runTest {
        for (finalizing in listOf(false, true)) {
            for (heardSpeech in listOf(false, true, null)) {
                val verdict = CompletableDeferred<Boolean>()
                val detector = FakeSpeechDetector(verdict)
                var detectors = 0
                val fixture = Fixture(backgroundScope, createSpeechDetector = { detectors++; detector })
                fixture.controller.start()
                runCurrent()
                fixture.socket.ready()
                runCurrent()
                val captured = listOf(ByteArray(16_000) { 1 }, ByteArray(16_000) { 2 })
                captured.forEach { fixture.audio.frame(it) }
                if (finalizing) fixture.controller.stop()
                fixture.socket.listener.failed()
                runCurrent()
                assertFalse(fixture.audio.capturing)
                assertEquals(DictationPhase.FINALIZING, fixture.state.phase)
                assertEquals(null, fixture.state.problem)
                assertFalse(fixture.state.canRetry)
                assertFalse(fixture.socket.sent.contains("finish"))
                assertEquals(1, detectors)
                assertEquals(1, detector.finishes)
                assertEquals(captured.size, detector.frames.size)
                captured.zip(detector.frames).forEach { (expected, actual) -> assertContentEquals(expected, actual) }
                if (heardSpeech == null) verdict.completeExceptionally(IllegalStateException("Model unavailable"))
                else verdict.complete(heardSpeech)
                runCurrent()
                if (heardSpeech == false) {
                    assertEquals(DictationPhase.CANCELLED, fixture.state.phase)
                    assertEquals(null, fixture.state.problem)
                    assertFalse(fixture.state.canRetry)
                    fixture.controller.retry()
                    runCurrent()
                    assertEquals(1, fixture.sockets.size)
                    assertTrue(fixture.applied.isEmpty())
                } else {
                    assertEquals(DictationPhase.INTERRUPTED, fixture.state.phase)
                    assertEquals(DictationProblem.CONNECTION, fixture.state.problem)
                    assertTrue(fixture.state.canRetry)
                    fixture.controller.retry()
                    runCurrent()
                    fixture.socket.ready()
                    runCurrent()
                    assertEquals(captured.size, fixture.socket.frames.size)
                    captured.zip(fixture.socket.frames).forEach { (expected, actual) -> assertContentEquals(expected, actual) }
                    assertEquals("finish", fixture.socket.sent.last())
                    assertEquals(1, detectors)
                    fixture.socket.final("retried speech")
                    runCurrent()
                    assertEquals("retried speech", fixture.draft.text)
                }
            }
        }
    }

    @Test
    fun cancelNewRecordingAndAccountResetDropFailedAudio() = runTest {
        for (action in listOf("cancel", "start", "reset", "close")) {
            val fixture = Fixture(backgroundScope)
            fixture.controller.start()
            runCurrent()
            fixture.socket.ready()
            runCurrent()
            fixture.audio.frame()
            fixture.controller.stop()
            fixture.socket.text("dictation.error", error = "provider_error")
            runCurrent()
            assertTrue(fixture.state.canRetry)
            when (action) {
                "cancel" -> fixture.controller.cancel()
                "start" -> fixture.controller.start()
                "reset" -> fixture.controller.reset()
                "close" -> fixture.controller.close()
            }
            runCurrent()
            assertFalse(fixture.state.canRetry)
            if (action == "start") {
                fixture.audio.frame(ByteArray(640))
                fixture.controller.stop()
            }
            val controls = fixture.socket.controls.size
            fixture.controller.retry()
            runCurrent()
            assertEquals(controls, fixture.socket.controls.size)
            assertTrue(fixture.applied.isEmpty())
        }
    }

    @Test
    fun cancelWhilePermissionPromptIsOpenNeverStartsAudio() = runTest {
        val permission = CompletableDeferred<Unit>()
        val fixture = Fixture(backgroundScope)
        fixture.audio.permissionGate = permission
        fixture.controller.start()
        runCurrent()
        assertEquals(DictationPhase.PERMISSION, fixture.state.phase)
        fixture.controller.cancel()
        permission.complete(Unit)
        runCurrent()
        assertEquals(DictationPhase.CANCELLED, fixture.state.phase)
        assertFalse(fixture.audio.capturing)
        assertTrue(fixture.sockets.single().controls.isEmpty())
    }

    @Test
    fun accountResetErasesReviewAndInvalidatesQueuedOldResults() = runTest {
        val fixture = Fixture(backgroundScope)
        fixture.controller.start()
        runCurrent()
        fixture.socket.ready()
        runCurrent()
        fixture.audio.frame()
        fixture.socket.text("dictation.partial", text = "old owner partial")
        runCurrent()
        fixture.controller.stop()
        fixture.socket.final("old owner final")
        runCurrent()
        assertEquals(DictationPhase.REVIEW, fixture.state.phase)
        assertEquals("old owner final", fixture.state.finalText)
        assertEquals("old owner partial", fixture.state.partial)
        fixture.controller.reset()
        assertEquals(DictationState(), fixture.state)
        fixture.draft = DictationDraftSnapshot("new owner", 0)
        fixture.socket.final("old owner late final")
        runCurrent()
        assertEquals(DictationState(), fixture.state)
        assertEquals("new owner", fixture.draft.text)
        assertFalse(fixture.audio.capturing)
    }

    @Test
    fun serverErrorsAndConnectionLossOfferOnlyExplicitRetryForRetryableProblems() = runTest {
        val cases = mapOf("not_configured" to (DictationProblem.NOT_CONFIGURED to false),
            "busy" to (DictationProblem.BUSY to true), "provider_error" to (DictationProblem.PROVIDER_ERROR to true),
            "invalid_audio" to (DictationProblem.INVALID_AUDIO to false), "too_long" to (DictationProblem.TOO_LONG to false),
            "session_expired" to (DictationProblem.SESSION_EXPIRED to false), "future_error" to (DictationProblem.SERVER to true))
        for ((code, expected) in cases) {
            val fixture = Fixture(backgroundScope)
            fixture.controller.start()
            runCurrent()
            fixture.socket.ready()
            runCurrent()
            fixture.audio.frame()
            fixture.controller.stop()
            fixture.socket.text("dictation.error", error = code)
            runCurrent()
            assertEquals(expected.first, fixture.state.problem)
            assertEquals(expected.second, fixture.state.canRetry)
            assertFalse(fixture.audio.capturing)
            assertFalse(fixture.socket.cancelled)
            assertEquals(1, fixture.sockets.size)
        }
        val fixture = Fixture(backgroundScope)
        fixture.controller.start()
        runCurrent()
        fixture.socket.ready()
        runCurrent()
        fixture.audio.frame()
        fixture.socket.listener.failed()
        runCurrent()
        assertEquals(DictationPhase.INTERRUPTED, fixture.state.phase)
        assertEquals(DictationProblem.CONNECTION, fixture.state.problem)
        assertEquals(1, fixture.sockets.size)
    }

}

private class Fixture(
    scope: CoroutineScope,
    readiness: DictationReadiness = DictationReadiness { DictationReadinessResult.Configured },
    nowMillis: () -> Long = { 0L },
    isAudioAvailable: () -> Boolean = { true },
    autoOpen: Boolean = true,
    foreground: Boolean = true,
    createSpeechDetector: () -> DictationSpeechDetector = { FakeSpeechDetector() },
) {
    val audio = FakeAudio()
    val sockets = mutableListOf<FakeLiveSocket>()
    val socket: FakeLiveSocket get() = sockets.last()
    val secrets = FeatureSessionSecrets(featureRecord())
    val sessions = SessionManager("synthetic", FEATURE_ORIGIN, secrets, {}, { FEATURE_NOW })
    val live = LiveConnection(scope, sessions, LiveSocketFactory { _, listener ->
        FakeLiveSocket(listener).also { next ->
            sockets.add(next)
            if (autoOpen) scope.launch { next.open() }
        }
    }, random = { 0.5 })
    init {
        live.setForeground(foreground)
        scope.launch { sessions.restore() }
    }
    var draft = DictationDraftSnapshot("", 0)
    val applied = mutableListOf<String>()
    val latencies = mutableListOf<DictationLatency>()
    val controller = DictationController(scope, readiness, audio, live,
        { draft }, { value -> applied.add(value); draft = DictationDraftSnapshot(value, draft.revision + 1) },
        latencies::add, nowMillis, isAudioAvailable, createSpeechDetector)
    val state get() = controller.state.value
}

private class FakeSpeechDetector(
    private val verdict: CompletableDeferred<Boolean> = CompletableDeferred(true),
) : DictationSpeechDetector {
    val frames = mutableListOf<ByteArray>()
    var finishes = 0
    override fun accept(pcm: ByteArray) { frames.add(pcm.copyOf()) }
    override suspend fun finish(): Boolean { finishes++; return verdict.await() }
    override fun cancel() { }
}

private class FakeAudio : DictationAudioSource {
    var prepares = 0
    var starts = 0
    var capturing = false
    var permission: DictationProblem? = null
    var notAnswered = false
    var permissionGate: CompletableDeferred<Unit>? = null
    var uncancellablePermission = false
    var onStop: () -> Unit = {}
    private var receive: (ByteArray, Float) -> Unit = { _, _ -> }
    var interrupted: (DictationProblem) -> Unit = {}
    override suspend fun prepare(): DictationPreparationResult {
        prepares++
        val pending = permissionGate
        if (pending != null) {
            if (uncancellablePermission) withContext(NonCancellable) { pending.await() } else pending.await()
        }
        return if (notAnswered) DictationPreparationResult.NotAnswered
            else permission?.let { DictationPreparationResult.Unavailable(it) } ?: DictationPreparationResult.Ready
    }
    override fun start(onFrame: (ByteArray, Float) -> Unit, onInterrupted: (DictationProblem) -> Unit) {
        starts++
        capturing = true
        receive = onFrame
        interrupted = onInterrupted
    }
    fun frame(bytes: ByteArray = ByteArray(DICTATION_BYTES_PER_SECOND), level: Float = .5f) = receive(bytes, level)
    override fun stop() { onStop(); capturing = false }
    override fun release() { capturing = false }
}

internal class FakeLiveSocket(val listener: LiveSocketListener) : LiveSocket {
    override var queuedBytes = 0L
    var growQueue = false
    var cancelled = false
    val controls = mutableListOf<DictationWireMessage>()
    val id: String get() = controls.last { it.type == "dictation.start" }.id!!
    val sent = mutableListOf<String>()
    val frames = mutableListOf<ByteArray>()
    fun open() { listener.opened(); listener.text("""{"type":"hello"}""") }
    fun text(type: String, text: String? = null, error: String? = null, id: String = this.id) =
        listener.text(NativeJson.encodeToString(DictationWireMessage(type, id, text, error = error)))
    fun ready(id: String = this.id) = listener.text(
        NativeJson.encodeToString(DictationWireMessage("dictation.ready", id, sampleRate = 16000)))
    fun final(value: String, id: String = this.id) = listener.text(NativeJson.encodeToString(
        DictationWireMessage("dictation.final", id, text = value, transcriptionId = "synthetic-transcription")))
    override fun sendPcm(bytes: ByteArray): Boolean {
        sent.add("pcm:${bytes.size}")
        frames.add(bytes.copyOf())
        if (growQueue) queuedBytes += bytes.size
        return true
    }
    override fun sendText(value: String): Boolean {
        val frame = decodeDictationMessage(value)
        controls.add(frame)
        if (frame.type == "dictation.finish") sent.add("finish")
        return true
    }
    override fun cancel() { cancelled = true }
}
