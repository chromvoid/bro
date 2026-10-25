package assistant.mobile.android.ambient

import assistant.mobile.android.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AmbientAccountPolicyTest {
    @Test
    fun signingOutOrSwitchingWhileIdleWithNothingQueuedIsNotAnEndedSession() {
        assertNull(AmbientAccountPolicy.afterChange(interrupted = false, queuedBatches = 0))
    }

    @Test
    fun aStoppedRecordingOrAudioLeftBehindIsReported() {
        assertEquals("not_authenticated", AmbientAccountPolicy.afterChange(interrupted = true, queuedBatches = 0))
        assertEquals("not_authenticated", AmbientAccountPolicy.afterChange(interrupted = false, queuedBatches = 3))
    }

    @Test
    fun theSameAccountConfirmedClearsOnlyAStaleSessionErrorOfAnIdleRecorder() {
        assertNull(AmbientAccountPolicy.afterConfirmed("not_authenticated", recording = false))
        assertEquals("not_authenticated", AmbientAccountPolicy.afterConfirmed("not_authenticated", recording = true))
        assertEquals("permission_denied", AmbientAccountPolicy.afterConfirmed("permission_denied", recording = false))
        assertNull(AmbientAccountPolicy.afterConfirmed(null, recording = false))
    }

    @Test
    fun anExpiredBindingAtAppStartMattersOnlyWithQueuedAudio() {
        assertNull(AmbientAccountPolicy.afterExpiry(queuedBatches = 0))
        assertEquals("not_authenticated", AmbientAccountPolicy.afterExpiry(queuedBatches = 1))
    }

    @Test
    fun anEndedSessionUsesTheOriginalActionableResource() {
        assertEquals(R.string.ambient_error_not_authenticated, AmbientMessages.resource("not_authenticated"))
    }
}
