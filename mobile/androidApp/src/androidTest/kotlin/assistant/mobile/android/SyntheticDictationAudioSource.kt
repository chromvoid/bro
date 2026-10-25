package assistant.mobile.android

import assistant.mobile.core.dictation.DictationAudioSource
import assistant.mobile.core.dictation.DictationProblem
import assistant.mobile.core.dictation.DictationPreparationResult
import java.util.concurrent.atomic.AtomicInteger

/** Deterministic 80 ms PCM16LE frame; capture never opens the microphone. */
internal class SyntheticDictationAudioSource : DictationAudioSource {
    val preparations = AtomicInteger()
    val starts = AtomicInteger()
    val releases = AtomicInteger()
    @Volatile var preparation: DictationPreparationResult = DictationPreparationResult.Ready

    override suspend fun prepare(): DictationPreparationResult {
        preparations.incrementAndGet()
        return preparation
    }

    override fun start(onFrame: (ByteArray, Float) -> Unit, onInterrupted: (DictationProblem) -> Unit) {
        starts.incrementAndGet()
        onFrame(ByteArray(2_560) { index -> if (index % 2 == 0) 0 else 8 }, 0.25f)
    }

    override fun stop() = Unit

    override fun release() {
        releases.incrementAndGet()
    }
}
