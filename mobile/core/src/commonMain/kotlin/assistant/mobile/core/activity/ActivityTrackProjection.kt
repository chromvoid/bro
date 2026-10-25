package assistant.mobile.core.activity

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.tan

data class ActivityMapPoint(val x: Double, val y: Double)
data class ActivityTrackView(val zoom: Int, val x: Double, val y: Double)
data class ActivityMapTile(
    val key: String,
    val path: String,
    val x: Double,
    val y: Double,
    val zoom: Int,
    val column: Int,
    val row: Int,
)

/** A preserved pause is evidence, never a route connecting its two sides. */
fun drawableSegments(segments: List<TrackSegment>): List<TrackSegment> = segments.flatMap { segment ->
    when (segment.quality) {
        "paused" -> emptyList()
        "valid" -> buildList {
            var points = mutableListOf<TrackPoint>()
            fun flush() {
                if (points.isNotEmpty()) add(segment.copy(points = points.toList()))
                points = mutableListOf()
            }
            segment.points.forEach { point -> if (point.paused) flush() else points.add(point) }
            flush()
        }
        else -> segment.points.filterNot { it.paused }.map { segment.copy(points = listOf(it)) }
    }
}

/** Projection is presentation only. Distance and quality remain server results. */
fun mercator(point: TrackPoint, zoom: Int): ActivityMapPoint {
    require(zoom in 1..19)
    val latitude = point.latitude.coerceIn(-85.05112878, 85.05112878) * PI / 180
    val size = 256 * 2.0.pow(zoom)
    return ActivityMapPoint(
        (point.longitude + 180) / 360 * size,
        ((1 - ln(tan(latitude) + 1 / cos(latitude)) / PI) / 2 * size).coerceIn(0.0, size),
    )
}

/** Only a single server segment is unwrapped. A declared gap never becomes a line. */
fun projectSegment(points: List<TrackPoint>, zoom: Int, anchorX: Double? = null): List<ActivityMapPoint> {
    val size = 256 * 2.0.pow(zoom)
    var previous = anchorX
    return points.map { point ->
        val value = mercator(point, zoom)
        val x = previous?.let { value.x + floor((it - value.x) / size + 0.5) * size } ?: value.x
        previous = x
        value.copy(x = x)
    }
}

fun trackView(points: List<TrackPoint>, width: Int = 640, height: Int = 360): ActivityTrackView? {
    if (points.isEmpty()) return null
    for (zoom in 17 downTo 1) {
        val projected = projectSegment(points, zoom)
        val left = projected.minOf { it.x }
        val right = projected.maxOf { it.x }
        val top = projected.minOf { it.y }
        val bottom = projected.maxOf { it.y }
        if ((right - left <= width - 80 && bottom - top <= height - 80) || zoom == 1) {
            return ActivityTrackView(zoom, (left + right) / 2, (top + bottom) / 2)
        }
    }
    return null
}

/** Only intersecting, valid rows are requested. Longitude wraps at the world edge. */
fun visibleTiles(view: ActivityTrackView, width: Int = 640, height: Int = 360): List<ActivityMapTile> {
    require(view.zoom in 1..19)
    require(width in 1..4096 && height in 1..4096)
    if (!view.x.isFinite() || !view.y.isFinite()) return emptyList()
    val left = view.x - width / 2.0
    val top = view.y - height / 2.0
    val count = 1 shl view.zoom
    return buildList {
        for (row in floor(top / 256).toInt()..floor((top + height - 1) / 256).toInt()) {
            if (row !in 0 until count) continue
            for (column in floor(left / 256).toInt()..floor((left + width - 1) / 256).toInt()) {
                val tileX = ((column % count) + count) % count
                add(ActivityMapTile(
                    key = "${view.zoom}/$column/$row",
                    path = "/v1/maps/osm/${view.zoom}/$tileX/$row.png",
                    x = column * 256.0 - left,
                    y = row * 256.0 - top,
                    zoom = view.zoom,
                    column = tileX,
                    row = row,
                ))
            }
        }
    }
}
