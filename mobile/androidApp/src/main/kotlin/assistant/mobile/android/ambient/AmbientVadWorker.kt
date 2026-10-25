package assistant.mobile.android.ambient

import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

interface AmbientVadDetector : AutoCloseable {
    fun probability(pcm: ByteArray): Float
    fun cancel() { }
}

/** Bounded inference stage: the microphone never waits for ONNX, encoding, disk or network. */
class AmbientVadWorker(
    private val detector: AmbientVadDetector,
    private val valid: () -> Boolean,
    private val emit: (AmbientVadSegment) -> Unit,
    private val failure: (String) -> Unit,
) {
    private val frames = ArrayBlockingQueue<ByteArray>(16)
    private val inputDone = AtomicBoolean()
    private val aborted = AtomicBoolean()
    private val reportedFailure = AtomicBoolean()
    private val thread = Thread({ process() }, "ambient-vad").apply { start() }

    /** Ownership is copied so AudioRecord may reuse its small input buffer immediately. */
    fun accept(pcm: ByteArray): Boolean {
        require(pcm.isNotEmpty() && pcm.size % 2 == 0 && pcm.size <= AmbientVadGate.FRAME_SAMPLES * 2)
        if (aborted.get() || inputDone.get() || !valid()) return false
        if (frames.offer(pcm.copyOf())) return true
        // Losing an input frame would silently join unrelated time coordinates. Stop instead.
        abort("capture_failed")
        return false
    }

    /** Finish confirmed speech only. Padding belongs to the detector, never to the gate or storage. */
    fun finish() {
        inputDone.set(true)
        try { thread.join(5_000) }
        catch (_: InterruptedException) { Thread.currentThread().interrupt(); abort("interrupted") }
        if (thread.isAlive) abort("vad_unavailable")
    }

    fun cancel() {
        aborted.set(true)
        frames.clear()
        detector.cancel()
    }

    /** Service ownership outlives a timed-out inference; never accumulate native sessions. */
    fun awaitClosed() {
        var interrupted = false
        while (thread.isAlive) {
            try { thread.join(250) }
            catch (_: InterruptedException) { interrupted = true; cancel() }
        }
        if (interrupted) Thread.currentThread().interrupt()
    }

    private fun abort(code: String) {
        cancel()
        if (reportedFailure.compareAndSet(false, true)) failure(code)
    }

    private fun process() {
        val gate = AmbientVadGate { segment -> if (!aborted.get() && valid()) emit(segment) }
        try {
            while (!aborted.get() && valid()) {
                val frame = frames.poll(50, TimeUnit.MILLISECONDS)
                if (frame != null) gate.accept(frame, detector.probability(frame))
                else if (inputDone.get()) break
            }
            if (!aborted.get() && valid()) gate.finish() else gate.discard()
        } catch (problem: AmbientProblem) {
            gate.discard()
            if (!aborted.get()) abort(problem.code)
        } catch (_: Exception) {
            gate.discard()
            if (!aborted.get()) abort("vad_unavailable")
        } finally {
            frames.clear()
            detector.close()
        }
    }
}
