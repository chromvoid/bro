package assistant.mobile.android

import android.graphics.BitmapFactory
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import assistant.mobile.core.activity.TrackSegment
import assistant.mobile.core.activity.drawableSegments
import assistant.mobile.core.activity.projectSegment
import assistant.mobile.core.activity.trackView
import assistant.mobile.core.activity.visibleTiles
import assistant.mobile.design.R as DesignR
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlin.math.pow
import kotlin.math.roundToInt

/** Receives only authenticated private-session tile bytes; no map SDK or external transport. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ActivityTrackMap(
    segments: List<TrackSegment>,
    cacheKey: Any,
    loadTile: suspend (zoom: Int, column: Int, row: Int) -> ByteArray?,
    modifier: Modifier = Modifier,
) {
    val drawable = remember(segments) { drawableSegments(segments) }
    val points = remember(drawable) { drawable.flatMap { it.points } }
    var view by remember(cacheKey, points) { mutableStateOf(trackView(points)) }
    val current = view ?: return
    val tiles = remember(current) { visibleTiles(current) }
    val images = remember(cacheKey) { mutableStateMapOf<String, ImageBitmap>() }
    val fetch by rememberUpdatedState(loadTile)
    LaunchedEffect(cacheKey, tiles.map { it.path }) {
        val visible = tiles.map { it.path }.toSet()
        images.keys.toList().filterNot { it in visible }.forEach { images.remove(it) }
        coroutineScope {
            tiles.distinctBy { it.path }.filterNot { it.path in images }.map { tile ->
                async {
                    val bytes = fetch(tile.zoom, tile.column, tile.row) ?: return@async
                    val bitmap = withContext(Dispatchers.IO) { decodeMapTile(bytes) } ?: return@async
                    images[tile.path] = bitmap
                }
            }.awaitAll()
        }
    }
    val palette = activityPalette()
    val label = stringResource(DesignR.string.workout_track_map_label)
    val uriHandler = LocalUriHandler.current
    Column(modifier.fillMaxWidth().testTag("activity_track_map"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            fun zoom(direction: Int) {
                val next = (current.zoom + direction).coerceIn(1, 19)
                val scale = 2.0.pow(next - current.zoom)
                view = current.copy(zoom = next, x = current.x * scale, y = current.y * scale)
            }
            @Composable fun control(text: String, description: String, tag: String, action: () -> Unit) {
                OutlinedButton(onClick = action, modifier = Modifier.heightIn(min = 48.dp).testTag(tag)
                    .semantics { contentDescription = description }) { Text(text) }
            }
            control("+", stringResource(DesignR.string.workout_track_zoom_in), "activity_map_zoom_in") { zoom(1) }
            control("−", stringResource(DesignR.string.workout_track_zoom_out), "activity_map_zoom_out") { zoom(-1) }
            control("←", stringResource(DesignR.string.workout_track_pan_left), "activity_map_left") { view = current.copy(x = current.x - 160) }
            control("↑", stringResource(DesignR.string.workout_track_pan_up), "activity_map_up") { view = current.copy(y = current.y - 90) }
            control("↓", stringResource(DesignR.string.workout_track_pan_down), "activity_map_down") { view = current.copy(y = current.y + 90) }
            control("→", stringResource(DesignR.string.workout_track_pan_right), "activity_map_right") { view = current.copy(x = current.x + 160) }
            TextButton(onClick = { view = trackView(points) }, modifier = Modifier.heightIn(min = 48.dp).testTag("activity_map_fit")) {
                Text(stringResource(DesignR.string.workout_track_fit))
            }
        }
        Canvas(Modifier.fillMaxWidth().aspectRatio(640f / 360f).clip(RoundedCornerShape(12.dp))
            .background(palette.getValue("muted")).testTag("activity_track_canvas")
            .semantics { contentDescription = label }) {
            scale(size.width / 640f, size.height / 360f, pivot = Offset.Zero) {
                tiles.forEach { tile ->
                    images[tile.path]?.let { image ->
                        drawImage(image, dstOffset = IntOffset(tile.x.roundToInt(), tile.y.roundToInt()), dstSize = IntSize(256, 256))
                    }
                }
                drawable.forEach { segment ->
                    val projected = projectSegment(segment.points, current.zoom, current.x)
                    val color = palette.getValue(when {
                        segment.quality != "valid" -> "muted-foreground"
                        segment.context == "recovery" -> "warning"
                        else -> "primary"
                    })
                    fun location(index: Int) = Offset((projected[index].x - current.x + 320).toFloat(),
                        (projected[index].y - current.y + 180).toFloat())
                    if (projected.size == 1) drawCircle(color, 4f, location(0))
                    else if (projected.isNotEmpty()) {
                        val path = Path().apply {
                            moveTo(location(0).x, location(0).y)
                            for (index in 1 until projected.size) lineTo(location(index).x, location(index).y)
                        }
                        drawPath(path, color, style = Stroke(4f, cap = StrokeCap.Round, join = StrokeJoin.Round,
                            pathEffect = if (segment.context == "recovery") PathEffect.dashPathEffect(floatArrayOf(8f, 6f)) else null))
                    }
                }
            }
        }
        Text(stringResource(DesignR.string.workout_track_map_explanation), style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        val attribution = listOf(stringResource(DesignR.string.workout_track_map_attribution_prefix),
            stringResource(DesignR.string.workout_track_map_attribution_name), stringResource(DesignR.string.workout_track_map_attribution_suffix)).joinToString(" ")
        TextButton(onClick = { uriHandler.openUri("https://www.openstreetmap.org/copyright") }, modifier = Modifier.heightIn(min = 48.dp)) {
            Text(attribution, style = MaterialTheme.typography.bodySmall)
        }
    }
}

private fun decodeMapTile(bytes: ByteArray): ImageBitmap? {
    if (bytes.size > 512 * 1024) return null
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    if (bounds.outWidth != 256 || bounds.outHeight != 256 || bounds.outMimeType != "image/png") return null
    return BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
}
