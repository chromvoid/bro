package assistant.mobile.android

import android.content.Context
import assistant.mobile.android.ambient.AmbientSileroVad
import assistant.mobile.android.ambient.AmbientVadGate
import assistant.mobile.core.dictation.DictationSpeechDetector
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.ensureActive

/** The ambient Silero model and probability thresholds, with a private state per dictation. */
class SileroDictationSpeechDetector(context: Context) : DictationSpeechDetector {
    private val applicationContext = context.applicationContext
    // The controller caps the recording at 120 seconds. Frames share its immutable in-memory PCM;
    // inference never waits on the UI thread and cannot put backpressure on capture or the socket.
    private val frames = Channel<ByteArray>(Channel.UNLIMITED)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val result = scope.async {
        AmbientSileroVad.fromAssets(applicationContext).use { detector ->
            val window = ByteArray(AmbientVadGate.FRAME_SAMPLES * 2)
            var filled = 0
            var speechSamples = 0
            var speaking = false
            fun infer(pcm: ByteArray) {
                val probability = detector.probability(pcm)
                speaking = probability >= if (speaking) AmbientVadGate.CONTINUE_THRESHOLD else AmbientVadGate.START_THRESHOLD
                if (speaking) speechSamples += pcm.size / 2
            }
            for (pcm in frames) {
                scope.ensureActive()
                var offset = 0
                while (offset < pcm.size) {
                    val count = minOf(window.size - filled, pcm.size - offset)
                    pcm.copyInto(window, filled, offset, offset + count)
                    filled += count
                    offset += count
                    if (filled == window.size) {
                        infer(window)
                        filled = 0
                    }
                }
            }
            if (filled > 0) infer(window.copyOf(filled))
            speechSamples >= AmbientVadGate.SAMPLE_RATE / 4
        }
    }

    override fun accept(pcm: ByteArray) {
        check(frames.trySend(pcm).isSuccess) { "Dictation speech detector is closed" }
    }

    override suspend fun finish(): Boolean {
        frames.close()
        return try { result.await() } finally { cancel() }
    }

    override fun cancel() {
        frames.cancel()
        scope.cancel()
    }
}
