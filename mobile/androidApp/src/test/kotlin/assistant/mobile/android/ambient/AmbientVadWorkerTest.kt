package assistant.mobile.android.ambient

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.Assert.*
import org.junit.Test

class AmbientVadWorkerTest {
    @Test
    fun partialFrameIsInferredButOnlyActualConfirmedSamplesAreSaved() {
        val sizes = mutableListOf<Int>()
        val clips = mutableListOf<AmbientVadSegment>()
        val errors = mutableListOf<String>()
        val closed = AtomicBoolean()
        val detector = object : AmbientVadDetector {
            override fun probability(pcm: ByteArray): Float { sizes.add(pcm.size / 2); return 0.9f }
            override fun close() { closed.set(true) }
        }
        val worker = AmbientVadWorker(detector, { true }, clips::add, errors::add)
        repeat(3) { assertTrue(worker.accept(ByteArray(1024))) }
        assertTrue(worker.accept(ByteArray(274)))
        worker.finish()
        assertEquals(listOf(512, 512, 512, 137), sizes)
        assertTrue(errors.isEmpty())
        assertTrue(closed.get())
        assertEquals(0L, clips.single().startSample)
        assertEquals((1536 + 137) * 2, clips.single().pcm.size)
    }

    @Test
    fun aSlowDetectorHasBoundedBackpressureAndCannotJoinAcrossDroppedFrames() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val clips = mutableListOf<AmbientVadSegment>()
        val errors = mutableListOf<String>()
        val closed = AtomicBoolean()
        val detector = object : AmbientVadDetector {
            override fun probability(pcm: ByteArray): Float { entered.countDown(); check(release.await(2, TimeUnit.SECONDS)); return 0.9f }
            override fun close() { closed.set(true) }
        }
        val worker = AmbientVadWorker(detector, { true }, clips::add, errors::add)
        try {
            assertTrue(worker.accept(ByteArray(1024)))
            assertTrue(entered.await(1, TimeUnit.SECONDS))
            repeat(16) { assertTrue(worker.accept(ByteArray(1024))) }
            assertFalse(worker.accept(ByteArray(1024)))
            assertFalse(worker.accept(ByteArray(1024)))
        } finally { release.countDown(); worker.finish() }
        assertEquals(listOf("capture_failed"), errors)
        assertTrue(clips.isEmpty())
        assertTrue(closed.get())
    }

    @Test
    fun accountInvalidationWhileInferenceRunsDropsPendingAudioAndClosesTheSession() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val valid = AtomicBoolean(true)
        val closed = AtomicBoolean()
        val canceled = AtomicBoolean()
        val clips = mutableListOf<AmbientVadSegment>()
        val detector = object : AmbientVadDetector {
            override fun probability(pcm: ByteArray): Float { entered.countDown(); check(release.await(2, TimeUnit.SECONDS)); return 0.9f }
            override fun cancel() { canceled.set(true) }
            override fun close() { closed.set(true) }
        }
        val worker = AmbientVadWorker(detector, valid::get, clips::add) { fail("identity invalidation must simply discard") }
        try {
            assertTrue(worker.accept(ByteArray(1024)))
            assertTrue(entered.await(1, TimeUnit.SECONDS))
            repeat(3) { assertTrue(worker.accept(ByteArray(1024))) }
            valid.set(false)
            worker.cancel()
        } finally { release.countDown(); worker.finish() }
        assertTrue(clips.isEmpty())
        assertTrue(canceled.get())
        assertTrue(closed.get())
    }

}
