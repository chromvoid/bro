package assistant.mobile.android.ambient

/** A retained slice of the original PCM16 mono, 16 kHz capture timeline. */
data class AmbientVadSegment(val startSample: Long, val pcm: ByteArray)

/**
 * Stateful speech gate. Call from one capture worker; model padding must never be passed here.
 * Sample coordinates include omitted silence and remain monotonic across finish/discard.
 */
class AmbientVadGate(private val emit: (AmbientVadSegment) -> Unit) {
    var capturedSamples: Long = 0
        private set

    private val recent = PcmRing(RECENT_SAMPLES * BYTES_PER_SAMPLE)
    private val quiet = ByteArray(END_SILENCE_SAMPLES * BYTES_PER_SAMPLE)
    private var quietSamples = 0
    private var quietStart = 0L
    private var candidateFrames = 0
    private var candidateSamples = 0
    private var candidateStart = 0L
    private var active = false
    private var clip: ByteArray? = null
    private var clipSamples = 0
    private var clipStart = 0L

    fun accept(pcm: ByteArray, probability: Float) {
        require(pcm.isNotEmpty() && pcm.size % BYTES_PER_SAMPLE == 0 && pcm.size <= FRAME_SAMPLES * BYTES_PER_SAMPLE) {
            "VAD gate requires 1..512 actual PCM16 samples"
        }
        require(probability.isFinite() && probability in 0f..1f) { "Invalid VAD probability" }
        val samples = pcm.size / BYTES_PER_SAMPLE
        require(capturedSamples <= Long.MAX_VALUE - samples) { "Capture sample cursor overflow" }
        val start = capturedSamples
        capturedSamples += samples
        recent.append(pcm)

        if (!active) {
            if (probability < START_THRESHOLD) {
                resetCandidate()
                return
            }
            if (candidateFrames == 0) candidateStart = start
            candidateFrames++
            candidateSamples += samples
            if (candidateFrames >= CONFIRMATION_FRAMES && candidateSamples >= MIN_SPEECH_SAMPLES) {
                active = true
                val ringStart = capturedSamples - recent.samples
                val retainedStart = maxOf(ringStart, candidateStart - PRE_ROLL_SAMPLES, 0L)
                val retained = recent.copyAfter((retainedStart - ringStart).toInt())
                resetCandidate()
                appendClip(retained, 0, retained.size / BYTES_PER_SAMPLE, retainedStart)
            }
            return
        }

        if (probability >= CONTINUE_THRESHOLD) {
            if (quietSamples > 0) {
                appendClip(quiet, 0, quietSamples, quietStart)
                quietSamples = 0
            }
            appendClip(pcm, 0, samples, start)
            return
        }

        if (quietSamples == 0) quietStart = start
        val retainedSamples = minOf(samples, END_SILENCE_SAMPLES - quietSamples)
        pcm.copyInto(quiet, quietSamples * BYTES_PER_SAMPLE, 0, retainedSamples * BYTES_PER_SAMPLE)
        quietSamples += retainedSamples
        if (quietSamples == END_SILENCE_SAMPLES) finalizeActive()
    }

    /** Explicit stop: preserve a confirmed phrase and its short tail, discard candidates. */
    fun finish() {
        if (active) finalizeActive()
        clearBufferedState()
    }

    /** Cancellation/error: release all pending audio without producing a segment. */
    fun discard() {
        clearBufferedState()
    }

    private fun finalizeActive() {
        if (quietSamples > 0) appendClip(quiet, 0, minOf(quietSamples, POST_ROLL_SAMPLES), quietStart)
        active = false
        quietSamples = 0
        resetCandidate()
        emitClip()
        clip = null
    }

    private fun appendClip(pcm: ByteArray, offsetSamples: Int, samples: Int, start: Long) {
        var sourceOffset = offsetSamples
        var remaining = samples
        var position = start
        while (remaining > 0) {
            if (clipSamples == 0) clipStart = position
            check(position == clipStart + clipSamples) { "VAD retained audio must be contiguous" }
            val buffer = clip ?: ByteArray(MAX_CLIP_SAMPLES * BYTES_PER_SAMPLE).also { clip = it }
            val copied = minOf(remaining, MAX_CLIP_SAMPLES - clipSamples)
            pcm.copyInto(buffer, clipSamples * BYTES_PER_SAMPLE, sourceOffset * BYTES_PER_SAMPLE,
                (sourceOffset + copied) * BYTES_PER_SAMPLE)
            clipSamples += copied
            sourceOffset += copied
            remaining -= copied
            position += copied
            if (clipSamples == MAX_CLIP_SAMPLES) emitClip()
        }
    }

    private fun emitClip() {
        if (clipSamples == 0) return
        val segment = AmbientVadSegment(clipStart, checkNotNull(clip).copyOf(clipSamples * BYTES_PER_SAMPLE))
        clipSamples = 0
        emit(segment)
    }

    private fun clearBufferedState() {
        active = false
        resetCandidate()
        quietSamples = 0
        quiet.fill(0)
        recent.clear()
        clipSamples = 0
        clip = null
    }

    private fun resetCandidate() {
        candidateFrames = 0
        candidateSamples = 0
    }

    companion object {
        const val SAMPLE_RATE = 16_000
        const val FRAME_SAMPLES = 512
        const val START_THRESHOLD = 0.5f
        const val CONTINUE_THRESHOLD = 0.35f
        const val CONFIRMATION_FRAMES = 3
        const val MIN_SPEECH_SAMPLES = 1_536
        const val PRE_ROLL_SAMPLES = 5_120
        const val END_SILENCE_SAMPLES = 12_800
        const val POST_ROLL_SAMPLES = 4_096
        const val MAX_CLIP_SAMPLES = 480_000
        private const val BYTES_PER_SAMPLE = 2
        private const val RECENT_SAMPLES = PRE_ROLL_SAMPLES + MIN_SPEECH_SAMPLES
    }

    private class PcmRing(capacity: Int) {
        private val bytes = ByteArray(capacity)
        private var writeOffset = 0
        private var size = 0
        val samples: Int get() = size / BYTES_PER_SAMPLE

        fun append(pcm: ByteArray) {
            val first = minOf(pcm.size, bytes.size - writeOffset)
            pcm.copyInto(bytes, writeOffset, 0, first)
            if (first < pcm.size) pcm.copyInto(bytes, 0, first, pcm.size)
            writeOffset = (writeOffset + pcm.size) % bytes.size
            size = minOf(bytes.size, size + pcm.size)
        }

        fun copyAfter(skippedSamples: Int): ByteArray {
            val skipped = skippedSamples * BYTES_PER_SAMPLE
            require(skipped in 0..size)
            val result = ByteArray(size - skipped)
            val start = (writeOffset - size + skipped + bytes.size) % bytes.size
            val first = minOf(result.size, bytes.size - start)
            bytes.copyInto(result, 0, start, start + first)
            if (first < result.size) bytes.copyInto(result, first, 0, result.size - first)
            return result
        }

        fun clear() {
            bytes.fill(0)
            writeOffset = 0
            size = 0
        }
    }
}
