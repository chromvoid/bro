package assistant.mobile.core.activity

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ActivityTrackProjectionTest {
    private fun point(latitude: Double, longitude: Double, paused: Boolean = false) = TrackPoint(
        at = "2026-10-09T08:00:00Z", latitude = latitude, longitude = longitude,
        accuracyMeters = 5.0, quality = "valid", paused = paused,
    )

    @Test fun fitsDatelineWithoutCrossingTheWorld() {
        val points = listOf(point(10.0, 179.999), point(10.001, -179.999))
        val view = assertNotNull(trackView(points))
        val projected = projectSegment(points, view.zoom, view.x)
        assertTrue(view.zoom > 10)
        assertTrue(abs(projected[1].x - projected[0].x) < 560)
        assertTrue(visibleTiles(view).size <= 12)
        assertTrue(visibleTiles(view).all { it.path.startsWith("/v1/maps/osm/") })
    }

    @Test fun absentCoordinatesDoNotSynthesizePointOrViewport() {
        assertNull(trackView(emptyList()))
        assertTrue(projectSegment(emptyList(), 10).isEmpty())
    }

    @Test fun pausesSplitSegmentsAndUnconfirmedAccuracyRemainsSeparatePoints() {
        val original = TrackSegment("workout", "valid", listOf(
            point(55.0, 37.0), point(55.0, 37.01, true), point(55.0, 37.02),
        ))
        assertEquals(listOf(1, 1), drawableSegments(listOf(original)).map { it.points.size })
        assertEquals(3, original.points.size)
        assertTrue(drawableSegments(listOf(original.copy(quality = "paused"))).isEmpty())
        assertEquals(listOf(1, 1), drawableSegments(listOf(original.copy(
            quality = "unknown", points = listOf(point(55.0, 37.0), point(55.0, 37.01)),
        ))).map { it.points.size })
    }
}
