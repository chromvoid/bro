package assistant.mobile.platform.ring

import org.junit.Assert.*
import org.junit.Test

class RingWorkoutAccountPolicyTest {
    @Test fun lapsedCookieAndReattachingItsBoundOwnerPreserveAcquisition() {
        assertFalse(RingWorkoutAccountPolicy.interrupt("owner", null, "owner", "lapsed"))
        assertFalse(RingWorkoutAccountPolicy.interrupt(null, null, "owner", "lapsed"))
        assertFalse(RingWorkoutAccountPolicy.interrupt(null, "owner", "owner", "switch"))
        assertFalse(RingWorkoutAccountPolicy.interrupt("owner", "owner", "owner", "switch"))
    }
    @Test fun logoutAndActualOwnerSwitchStillInterruptAcquisition() {
        assertTrue(RingWorkoutAccountPolicy.interrupt("owner", null, "owner", "logout"))
        assertTrue(RingWorkoutAccountPolicy.interrupt("owner", "other", "owner", "switch"))
        assertTrue(RingWorkoutAccountPolicy.interrupt(null, "other", "owner", "switch"))
        assertTrue(RingWorkoutAccountPolicy.interrupt("owner", null, "owner", "switch"))
        assertTrue(RingWorkoutAccountPolicy.interrupt(null, null, "owner", "logout"))
    }
    @Test fun malformedLapseCannotPretendToChangeTheOwnerWithoutInterruption() {
        for ((user, reason) in listOf("other" to "lapsed", null to "unknown")) {
            try { RingWorkoutAccountPolicy.interrupt("owner", user, "owner", reason); fail("Expected invalid account transition") }
            catch (failure: RingFailure) { assertEquals("bad_payload", failure.code) }
        }
    }
}
