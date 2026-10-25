package assistant.mobile.platform.ring

import org.junit.Assert.*
import org.junit.Test

class RingWorkoutTrackTest {
    private fun point(ms: Long) = RingTrackPoint(ms * 1_000_000, 55.75, 37.61, 8.0, "valid", "gps")

    @Test fun permissionAdmissionSeparatesForegroundAndBackgroundWithoutRejectingApproximate() {
        for (permission in listOf("precise", "approximate")) {
            val access = RingLocationAccess(permission, false, true)
            assertNull(access.unavailableReason(true))
            assertEquals("background_limited", access.unavailableReason(false))
            assertNull(access.copy(background = true).unavailableReason(false))
            assertEquals("location_disabled", access.copy(enabled = false).unavailableReason(true))
        }
        assertEquals("permission_denied", RingLocationAccess("denied", true, true).unavailableReason(true))
    }

    @Test fun chunksKeepRealFixesAccuracyAndGapsAcrossTheMinuteBoundary() {
        val points = listOf(point(1_000), point(60_999), point(61_000), point(62_000).copy(quality = "approximate", accuracyMeters = 150.0))
        val gaps = listOf(RingWorkoutGap(1_001, 60_999, "no_fix"))
        val chunks = RingTrackChunker.split(points, gaps)
        assertEquals(points, chunks.flatMap { it.points })
        assertEquals(2, chunks.size)
        assertEquals(61_000L, chunks.first().endElapsedMs)
        assertEquals(61_000L, chunks.last().startElapsedMs)
        assertEquals(gaps, chunks.flatMap { it.gaps })
        assertEquals(150.0, chunks.last().points.last().accuracyMeters, 0.0)
        assertEquals("approximate", chunks.last().points.last().quality)
    }

    @Test fun invalidCoordinatesDuplicatesAndOverlappingGapsFailBeforePersistence() {
        fun invalid(action: () -> Unit) { try { action(); fail("Expected invalid location") } catch (_: IllegalArgumentException) { } }
        invalid { point(1_000).copy(latitude = Double.NaN) }
        invalid { point(1_000).copy(longitude = 181.0) }
        invalid { point(1_000).copy(accuracyMeters = 0.0) }
        invalid { RingTrackChunker.split(listOf(point(1_000), point(1_000)), emptyList()) }
        invalid { RingTrackChunker.split(listOf(point(1_000)), listOf(RingWorkoutGap(999, 1_001, "paused"))) }
    }

}
