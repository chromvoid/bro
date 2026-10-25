package assistant.mobile.android

import assistant.mobile.core.dictation.DictationAudioSource
import assistant.mobile.core.dictation.DictationProblem
import assistant.mobile.core.dictation.DictationPreparationResult

/** The app's recorders share a UID: Android audio focus alone cannot arbitrate between them. */
internal class NativeDictationAudioGate(
    private val source: DictationAudioSource,
    private val callOwnsMicrophone: () -> Boolean,
    private val claim: (Boolean) -> Unit,
    private val stopPlayback: () -> Unit,
) : DictationAudioSource, AutoCloseable {
    private var generation = 0L

    override suspend fun prepare(): DictationPreparationResult {
        if (callOwnsMicrophone()) return DictationPreparationResult.Unavailable(DictationProblem.AUDIO_FOCUS)
        val attempt = ++generation
        claim(true)
        var prepared = false
        try {
            val preparation = source.prepare()
            if (attempt != generation || callOwnsMicrophone()) return DictationPreparationResult.Unavailable(DictationProblem.AUDIO_FOCUS)
            prepared = preparation == DictationPreparationResult.Ready
            return preparation
        } finally {
            if (!prepared && attempt == generation) claim(false)
        }
    }

    override fun start(onFrame: (ByteArray, Float) -> Unit, onInterrupted: (DictationProblem) -> Unit) {
        if (callOwnsMicrophone()) {
            release()
            onInterrupted(DictationProblem.AUDIO_FOCUS)
            return
        }
        val attempt = ++generation
        claim(true)
        stopPlayback()
        try {
            source.start(onFrame) { problem ->
                if (attempt == generation) claim(false)
                onInterrupted(problem)
            }
        } catch (failure: Exception) {
            release()
            throw failure
        }
    }

    override fun stop() {
        ++generation
        try { source.stop() } finally { claim(false) }
    }

    override fun release() {
        ++generation
        try { source.release() } finally { claim(false) }
    }

    override fun close() {
        release()
        (source as? AutoCloseable)?.close()
    }
}
