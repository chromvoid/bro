package assistant.mobile.android

import android.graphics.BitmapFactory
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import assistant.mobile.core.GeneratedStrings
import assistant.mobile.core.activity.TrackPoint
import assistant.mobile.core.activity.projectSegment
import assistant.mobile.core.activity.trackView
import assistant.mobile.core.activity.visibleTiles
import assistant.mobile.core.places.PlacesDay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlin.math.log2
import kotlin.math.pow
import kotlin.math.roundToInt

/** Map presentation shares Activity's projection; transport remains the private session API. */
@Composable
internal fun PlaceMap(day: PlacesDay, accountKey: Any, loadTile: suspend (Int, Int, Int) -> ByteArray?) {
    val track = remember(day) { day.track.map { TrackPoint(it.capturedAt, it.latitude, it.longitude, null, "valid") } }
    val stays = remember(day) { day.stays.map { TrackPoint(it.from, it.latitude, it.longitude, null, "valid") } }
    val all = remember(track, stays) { track + stays }
    val cacheKey = accountKey to day.date
    var view by remember(cacheKey, all) { mutableStateOf(trackView(all)?.let { fitted ->
        if (fitted.zoom <= 16) fitted else fitted.copy(zoom = 16, x = fitted.x / 2, y = fitted.y / 2)
    }) }
    val current = view ?: return
    val tiles = remember(current) { visibleTiles(current) }
    val images = remember(cacheKey) { mutableStateMapOf<String, ImageBitmap>() }
    val fetch by rememberUpdatedState(loadTile)
    LaunchedEffect(cacheKey, tiles.map { it.path }) {
        val visible = tiles.map { it.path }.toSet()
        images.keys.toList().filterNot { it in visible }.forEach { images.remove(it) }
        val limiter = Semaphore(2)
        coroutineScope {
            tiles.distinctBy { it.path }.filterNot { it.path in images }.map { tile ->
                async {
                    val image = limiter.withPermit {
                        val bytes = fetch(tile.zoom, tile.column, tile.row) ?: return@withPermit null
                        withContext(Dispatchers.IO) { decodePlacesTile(bytes) }
                    }
                    if (image != null) images[tile.path] = image
                }
            }.awaitAll()
        }
    }
    val palette = activityPalette()
    val trackColor = palette.getValue("chart-1")
    val label = localized(GeneratedStrings.Places.map)
    val zoomIn = localized(GeneratedStrings.WorkoutTrack.zoomIn)
    val zoomOut = localized(GeneratedStrings.WorkoutTrack.zoomOut)
    Column(Modifier.fillMaxWidth().testTag("places_map"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            fun zoom(direction: Int) {
                val next = (current.zoom + direction).coerceIn(1, 19)
                val factor = 2.0.pow(next - current.zoom)
                view = current.copy(zoom = next, x = current.x * factor, y = current.y * factor)
            }
            OutlinedButton(onClick = { zoom(1) }, enabled = current.zoom < 19,
                modifier = Modifier.heightIn(min = 48.dp).testTag("places_map_zoom_in").semantics { contentDescription = zoomIn }) { Text("+") }
            OutlinedButton(onClick = { zoom(-1) }, enabled = current.zoom > 1,
                modifier = Modifier.heightIn(min = 48.dp).testTag("places_map_zoom_out").semantics { contentDescription = zoomOut }) { Text("−") }
        }
        Canvas(Modifier.fillMaxWidth().aspectRatio(640f / 360f).clip(RoundedCornerShape(12.dp))
            .background(palette.getValue("muted")).testTag("places_map_canvas").semantics { contentDescription = label }
            .pointerInput(cacheKey) {
                var scaleRemainder = 1.0
                detectTransformGestures { _, pan, zoom, _ ->
                    val old = view ?: return@detectTransformGestures
                    scaleRemainder *= zoom
                    val direction = log2(scaleRemainder).toInt()
                    val next = (old.zoom + direction).coerceIn(1, 19)
                    val factor = 2.0.pow(next - old.zoom)
                    if (next != old.zoom) scaleRemainder /= factor
                    view = old.copy(zoom = next,
                        x = (old.x - pan.x * 640 / size.width) * factor,
                        y = (old.y - pan.y * 360 / size.height) * factor)
                }
            }) {
            scale(size.width / 640f, size.height / 360f, pivot = Offset.Zero) {
                tiles.forEach { tile -> images[tile.path]?.let { bitmap ->
                    drawImage(bitmap, dstOffset = IntOffset(tile.x.roundToInt(), tile.y.roundToInt()), dstSize = IntSize(256, 256))
                } }
                val projectedTrack = projectSegment(track, current.zoom, current.x)
                fun location(point: assistant.mobile.core.activity.ActivityMapPoint) = Offset(
                    (point.x - current.x + 320).toFloat(), (point.y - current.y + 180).toFloat())
                if (projectedTrack.size > 1) {
                    val route = Path().apply {
                        moveTo(location(projectedTrack.first()).x, location(projectedTrack.first()).y)
                        projectedTrack.drop(1).forEach { point -> val at = location(point); lineTo(at.x, at.y) }
                    }
                    drawPath(route, trackColor, style = Stroke(4f, cap = StrokeCap.Round, join = StrokeJoin.Round))
                } else projectedTrack.singleOrNull()?.let { drawCircle(trackColor, 4f, location(it)) }
                projectSegment(stays, current.zoom, current.x).forEach { drawCircle(trackColor, 7f, location(it)) }
            }
        }
        PlacesAttribution()
    }
}

@Composable
internal fun PlacesAttribution() {
    val uriHandler = LocalUriHandler.current
    TextButton(onClick = { uriHandler.openUri("https://www.openstreetmap.org/copyright") },
        modifier = Modifier.heightIn(min = 48.dp).testTag("places_attribution")) {
        Text(localized(GeneratedStrings.Places.attribution), style = MaterialTheme.typography.bodySmall)
    }
}

private fun decodePlacesTile(bytes: ByteArray): ImageBitmap? {
    if (bytes.size > 512 * 1024) return null
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    if (bounds.outWidth != 256 || bounds.outHeight != 256 || bounds.outMimeType != "image/png") return null
    return BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
}
