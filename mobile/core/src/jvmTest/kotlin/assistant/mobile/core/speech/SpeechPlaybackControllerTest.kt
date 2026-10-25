package assistant.mobile.core.speech

import assistant.mobile.core.AccountNamespace
import assistant.mobile.core.NativeIdentity
import assistant.mobile.core.SessionLease
import assistant.mobile.core.SessionManager
import assistant.mobile.core.SessionSecretStore
import assistant.mobile.core.StoredSession
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class SpeechPlaybackControllerTest {
    @Test
    fun segmentsWaitForDoneEofAndPlaybackDrainBeforeTheNextPost() = runTest {
        val sessions = speechTestSessions().also { it.restore() }
        val transport = FakeSpeechTransport()
        val sink = FakeSpeechSink()
        val eof = CompletableDeferred<Unit>()
        val drain = CompletableDeferred<Unit>()
        transport.afterPcm = { index -> if (index == 0) eof.await() }
        sink.drain = { if (sink.finishes == 1) drain.await() }
        val controller = SpeechPlaybackController(backgroundScope, sessions, transport, sink)
        controller.turn("turn-one")
        runCurrent()
        assertEquals(SpeechPlaybackPhase.PLAYING, controller.state.value.phase)
        assertEquals(listOf(0), transport.segments.map { it.index })
        assertEquals(0, sink.finishes)
        eof.complete(Unit)
        runCurrent()
        assertEquals(1, sink.finishes)
        assertEquals(1, transport.segments.size)
        drain.complete(Unit)
        runCurrent()
        assertEquals(listOf(0, 1), transport.segments.map { it.index })
        assertEquals(1, transport.segments.map { it.playbackId }.distinct().size)
        assertEquals(listOf("a".repeat(64), "a".repeat(64)), transport.segments.map { it.key })
        assertEquals(SpeechPlaybackPhase.IDLE, controller.state.value.phase)
        controller.close()
    }

    @Test
    fun incompleteGenerationNeverRetriesAutomaticallyAndExplicitRetryUsesFreshId() = runTest {
        val sessions = speechTestSessions().also { it.restore() }
        val transport = FakeSpeechTransport().apply { failSegment = 1 }
        val sink = FakeSpeechSink()
        val cache = SpeechAudioCache()
        val controller = SpeechPlaybackController(backgroundScope, sessions, transport, sink, cache)
        controller.turn("turn-one")
        runCurrent()
        assertEquals(SpeechPlaybackPhase.FAILED, controller.state.value.phase)
        assertEquals(SpeechPlaybackProblem.CONNECTION, controller.state.value.problem)
        assertEquals(0, cache.byteCount)
        advanceTimeBy(120_000)
        runCurrent()
        assertEquals(2, transport.segments.size)
        transport.failSegment = null
        controller.retry()
        runCurrent()
        assertEquals(listOf(0, 1, 0, 1), transport.segments.map { it.index })
        assertNotEquals(transport.segments[0].playbackId, transport.segments[2].playbackId)
        assertEquals(SpeechPlaybackPhase.IDLE, controller.state.value.phase)
        assertEquals(8, cache.byteCount)
        controller.close()
    }

    @Test
    fun stopCancelsGenerationAndAudioFocusInterruptNeedsAnExplicitReplay() = runTest {
        val sessions = speechTestSessions().also { it.restore() }
        val transport = FakeSpeechTransport().apply { afterPcm = { CompletableDeferred<Unit>().await() } }
        val sink = FakeSpeechSink()
        val controller = SpeechPlaybackController(backgroundScope, sessions, transport, sink)
        controller.turn("turn-one")
        runCurrent()
        assertTrue(controller.state.value.active)
        val stops = sink.stops
        controller.stop()
        assertEquals(stops + 1, sink.stops)
        assertFalse(controller.state.value.active)
        runCurrent()
        assertEquals(1, transport.segments.size)
        controller.turn("turn-one")
        runCurrent()
        controller.interrupt()
        assertEquals(SpeechPlaybackProblem.AUDIO_FOCUS, controller.state.value.problem)
        assertEquals(SpeechPlaybackPhase.FAILED, controller.state.value.phase)
        advanceTimeBy(60_000)
        runCurrent()
        assertEquals(2, transport.segments.size)
        controller.close()
    }

    @Test
    fun accountLeaseChangeStopsPlaybackErasesCacheAndForgetsRetry() = runTest {
        val sessions = speechTestSessions().also { it.restore() }
        val transport = FakeSpeechTransport()
        val cache = SpeechAudioCache()
        val controller = SpeechPlaybackController(backgroundScope, sessions, transport, FakeSpeechSink(), cache)
        controller.turn("turn-one")
        runCurrent()
        assertEquals(8, cache.byteCount)
        val blocked = CompletableDeferred<Unit>()
        transport.afterPcm = { blocked.await() }
        transport.plan = transport.plan.copy(cacheKey = "b".repeat(64))
        controller.turn("turn-two")
        runCurrent()
        sessions.expire(sessions.lease()!!)
        runCurrent()
        assertEquals(SpeechPlaybackPhase.IDLE, controller.state.value.phase)
        assertEquals(0, cache.byteCount)
        val before = transport.segments.size
        controller.retry()
        blocked.complete(Unit)
        runCurrent()
        assertEquals(before, transport.segments.size)
        controller.close()
    }
}

private class FakeSpeechTransport : SpeechPlaybackTransport {
    data class Segment(val index: Int, val playbackId: String, val key: String)
    var plan = SpeechPlaybackPlan("a".repeat(64), 2, "connection-one")
    var prepares = 0
    val segments = mutableListOf<Segment>()
    val previews = mutableListOf<Triple<String, String, String?>>()
    var afterPcm: suspend (Int) -> Unit = {}
    var failSegment: Int? = null
    var previewFails = false
    var encoded = false

    override suspend fun prepare(turnId: String, lease: SessionLease): SpeechPlaybackPlan { prepares++; return plan }
    override suspend fun segment(
        turnId: String, playbackId: String, plan: SpeechPlaybackPlan, segment: Int, lease: SessionLease,
        onPcm: suspend (ByteArray, SpeechAudioFormat) -> Unit,
    ): SpeechAudioSegment {
        segments += Segment(segment, playbackId, plan.cacheKey)
        if (failSegment == segment) throw SpeechPlaybackFailure(SpeechPlaybackProblem.CONNECTION)
        val audio = fakeAudio()
        if (!encoded) onPcm(audio.audio, audio.format)
        afterPcm(segment)
        return audio
    }
    override suspend fun preview(
        connectionId: String, voice: String, model: String?, lease: SessionLease,
        onPcm: suspend (ByteArray, SpeechAudioFormat) -> Unit,
    ): SpeechAudioSegment {
        previews += Triple(connectionId, voice, model)
        if (previewFails) throw SpeechPlaybackFailure(SpeechPlaybackProblem.PROVIDER_ERROR)
        val audio = fakeAudio()
        if (!encoded) onPcm(audio.audio, audio.format)
        afterPcm(0)
        return audio
    }
    private fun fakeAudio() = SpeechAudioSegment(if (encoded) SpeechAudioFormat("audio/wav") else
        SpeechAudioFormat("audio/pcm", 24000, 1), byteArrayOf(0, 1, 2, 3))
}

private class FakeSpeechSink : SpeechAudioSink {
    var focusAvailable = true
    var writes = 0
    var finishes = 0
    var stops = 0
    var encoded = 0
    var drain: suspend () -> Unit = {}
    override suspend fun prepare() = focusAvailable
    override suspend fun startPcm(format: SpeechAudioFormat) = Unit
    override suspend fun writePcm(bytes: ByteArray) { writes++ }
    override suspend fun finishPcm() { finishes++; drain() }
    override suspend fun playEncoded(segment: SpeechAudioSegment) { encoded++ }
    override fun stop() { stops++ }
}

internal const val SPEECH_TEST_ORIGIN = "https://assistant.invalid"
internal fun speechTestSessions(): SessionManager {
    val identity = NativeIdentity("synthetic-owner", "fixture", "Synthetic", "ru", "UTC", false,
        1, "synthetic-device", "2099-01-01T00:00:00Z")
    val stored = StoredSession(AccountNamespace("synthetic", SPEECH_TEST_ORIGIN, identity.id), "synthetic-credential",
        identity, identity.deviceId, "synthetic-session", identity.sessionExpiresAt)
    return SessionManager("synthetic", SPEECH_TEST_ORIGIN, object : SessionSecretStore {
        override suspend fun load(instance: String, origin: String) = stored
        override suspend fun save(session: StoredSession) = Unit
        override suspend fun delete(instance: String, origin: String) = Unit
    }, {}, { 0 })
}
