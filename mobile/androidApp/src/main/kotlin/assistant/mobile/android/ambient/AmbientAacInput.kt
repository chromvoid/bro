package assistant.mobile.android.ambient

/** Bounded AAC-LC input padding and whole-access-unit retention for the native encoder. */
object AmbientAacInput {
    const val FRAME_SAMPLES = 1024
    const val PRIMING_SAMPLES = 2048
    private const val MAX_SOURCE_SAMPLES = AmbientWav.SEGMENT_PCM_BYTES / AmbientWav.BYTES_PER_SAMPLE

    /**
     * Push the captured tail through the encoder even when an older C2 AAC encoder does not
     * drain its lookahead at EOS. Appended zeroes are encoder input only; source timing remains
     * the original PCM length. Later native flush packets are omitted using retainedAccessUnits.
     */
    fun prepare(pcm: ByteArray): ByteArray {
        require(pcm.isNotEmpty() && pcm.size % AmbientWav.BYTES_PER_SAMPLE == 0 &&
            pcm.size <= AmbientWav.SEGMENT_PCM_BYTES) { "AAC input must contain 1 to 480000 PCM16 samples" }
        val sourceSamples = pcm.size / AmbientWav.BYTES_PER_SAMPLE
        val preparedSamples = retainedAccessUnits(sourceSamples.toLong()) * FRAME_SAMPLES
        return pcm.copyOf(preparedSamples * AmbientWav.BYTES_PER_SAMPLE)
    }

    /** Keep the rounded source plus two AAC-LC priming frames; all arithmetic follows bounds checks. */
    fun retainedAccessUnits(sourceSamples: Long): Int {
        require(sourceSamples in 1L..MAX_SOURCE_SAMPLES.toLong()) { "AAC source must contain 1 to 480000 samples" }
        return ((sourceSamples + FRAME_SAMPLES - 1) / FRAME_SAMPLES + PRIMING_SAMPLES / FRAME_SAMPLES).toInt()
    }
}
