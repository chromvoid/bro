package assistant.mobile.platform.ring

import org.junit.Assert.*
import org.junit.Test

class RingWorkoutForegroundTest {
    private val both = RingWorkoutForegroundResources(true, true, true)
    @Test fun bluetoothRevokedDuringCombinedPromotionRetriesLocationWithoutBlockingGps() {
        var resources = both
        val started = mutableListOf<RingWorkoutForegroundMode>()
        val result = RingWorkoutForegroundPolicy.promote(null, { resources }) { mode ->
            started += mode
            if (mode == RingWorkoutForegroundMode.BOTH) {
                resources = resources.copy(bluetoothReady = false)
                throw SecurityException("Synthetic BLUETOOTH_CONNECT revocation")
            }
        }!!
        assertEquals(listOf(RingWorkoutForegroundMode.BOTH, RingWorkoutForegroundMode.LOCATION), started)
        assertTrue(result.bluetoothRejected); assertFalse(result.locationRejected)
        assertTrue(result.mode.location); assertFalse(result.mode.bluetooth)
    }
    @Test fun locationRevokedDuringCombinedPromotionKeepsEligibleHeartRate() {
        var resources = both
        val started = mutableListOf<RingWorkoutForegroundMode>()
        val result = RingWorkoutForegroundPolicy.promote(null, { resources }) { mode ->
            started += mode
            if (mode == RingWorkoutForegroundMode.BOTH) {
                resources = resources.copy(locationReady = false)
                throw SecurityException("Synthetic location revocation")
            }
        }!!
        assertEquals(listOf(RingWorkoutForegroundMode.BOTH, RingWorkoutForegroundMode.CONNECTED_DEVICE), started)
        assertTrue(result.locationRejected); assertFalse(result.bluetoothRejected)
        assertTrue(result.mode.bluetooth); assertFalse(result.mode.location)
    }
    @Test fun staleFallbackCannotUsePermissionRevokedAfterFirstFailure() {
        var resources = both
        val attempted = mutableListOf<RingWorkoutForegroundMode>()
        try {
            RingWorkoutForegroundPolicy.promote(null, { resources }) { mode ->
                attempted += mode; resources = resources.copy(bluetoothReady = false, locationReady = false)
                throw SecurityException("Synthetic both grants revoked")
            }
            fail("Expected original platform rejection")
        } catch (failure: SecurityException) { assertEquals("Synthetic both grants revoked", failure.message) }
        assertEquals(listOf(RingWorkoutForegroundMode.BOTH), attempted)
    }
}
