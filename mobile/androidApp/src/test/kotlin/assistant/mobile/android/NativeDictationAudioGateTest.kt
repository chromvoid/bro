package assistant.mobile.android

import assistant.mobile.core.dictation.DictationAudioSource
import assistant.mobile.core.dictation.DictationProblem
import assistant.mobile.core.dictation.DictationPreparationResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeDictationAudioGateTest {
    @Test fun microphoneIsReleasedFromAmbientBeforeDictationAndCallCannotBeReleasedByDictation() = runBlocking {
        val owners = mutableSetOf("ambient")
        var call = false
        val order = mutableListOf<String>()
        val source = Source(order)
        val gate = NativeDictationAudioGate(source, { call }, { claimed ->
            if (claimed) { owners.remove("ambient"); owners.add("dictation") }
            else owners.remove("dictation")
            order += "claim:$claimed"
        }, { order += "playback.stop" })
        assertEquals(DictationPreparationResult.Ready, gate.prepare())
        assertEquals(setOf("dictation"), owners)
        gate.start({ _, _ -> }, {})
        assertTrue(order.indexOf("claim:true") < order.indexOf("source.start"))
        assertTrue(order.indexOf("playback.stop") < order.indexOf("source.start"))
        owners += "call"
        call = true
        gate.release()
        assertEquals(setOf("call"), owners)
        val starts = order.count { it == "source.start" }
        assertEquals(DictationPreparationResult.Unavailable(DictationProblem.AUDIO_FOCUS), gate.prepare())
        gate.start({ _, _ -> }, { assertEquals(DictationProblem.AUDIO_FOCUS, it) })
        assertEquals(starts, order.count { it == "source.start" })
    }

    @Test fun permissionCancellationAndLateReplyCannotReclaimAmbientMicrophone() = runBlocking {
        val ready = CompletableDeferred<DictationPreparationResult>()
        var claimed = false
        val source = Source(mutableListOf(), ready)
        val gate = NativeDictationAudioGate(source, { false }, { claimed = it }, {})
        val attempt = launch(start = CoroutineStart.UNDISPATCHED) {
            assertEquals(DictationPreparationResult.Unavailable(DictationProblem.AUDIO_FOCUS), gate.prepare())
        }
        assertTrue(claimed)
        gate.release()
        ready.complete(DictationPreparationResult.Ready)
        attempt.join()
        assertFalse(claimed)
        assertEquals(0, source.starts)
    }

    private class Source(private val order: MutableList<String>,
        private val permission: CompletableDeferred<DictationPreparationResult>? = null) : DictationAudioSource {
        var starts = 0
        override suspend fun prepare(): DictationPreparationResult = permission?.await() ?: DictationPreparationResult.Ready
        override fun start(onFrame: (ByteArray, Float) -> Unit, onInterrupted: (DictationProblem) -> Unit) {
            starts++
            order += "source.start"
        }
        override fun stop() { order += "source.stop" }
        override fun release() { order += "source.release" }
    }
}
