package assistant.mobile.android

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import assistant.mobile.core.activity.ActivityChartPoint
import assistant.mobile.core.activity.WorkoutPulse
import assistant.mobile.core.activity.workoutPoints
import assistant.mobile.design.GeneratedColors
import assistant.mobile.design.R as DesignR
import java.text.NumberFormat
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.roundToInt

@Composable
internal fun activityPalette(): Map<String, Color> =
    if (MaterialTheme.colorScheme.background == GeneratedColors.dark.getValue("background")) GeneratedColors.dark
    else GeneratedColors.light

/** Actual observations only. Running/workout/recovery are separate, with no interpolation. */
@Composable
internal fun ActivityHeartRateChart(samples: List<WorkoutPulse>, timezone: String, modifier: Modifier = Modifier) {
    val units = stringResource(DesignR.string.ring_workout_bpm)
    val zone = runCatching { ZoneId.of(timezone) }.getOrDefault(ZoneId.of("UTC"))
    val clock = remember(zone) { DateTimeFormatter.ofPattern("HH:mm:ss", Locale.forLanguageTag("ru-RU")).withZone(zone) }
    val number = remember { NumberFormat.getIntegerInstance(Locale.forLanguageTag("ru-RU")) }
    val series = remember(samples) { listOf("running", "workout", "recovery").associateWith { workoutPoints(samples, it) } }
    Column(modifier.fillMaxWidth().testTag("activity_heart_rate_chart"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (samples.any { it.quality == "unknown" }) Text(stringResource(DesignR.string.workout_pulse_quality_warning),
            style = MaterialTheme.typography.bodySmall, color = activityPalette().getValue("warning"))
        if (samples.isNotEmpty() && series.values.all { it.isEmpty() }) Text(
            stringResource(DesignR.string.workout_pulse_no_confirmed_samples), style = MaterialTheme.typography.bodySmall)
        series.forEach { (phase, points) ->
            if (points.isNotEmpty()) {
                val label = stringResource(when (phase) {
                    "running" -> DesignR.string.ring_workout_chart_running
                    "workout" -> DesignR.string.ring_workout_chart_workout
                    else -> DesignR.string.ring_workout_chart_recovery
                })
                Text(label, style = MaterialTheme.typography.titleSmall)
                ActivityLineChart(points, label, { clock.format(Instant.ofEpochMilli(it.toLong())) },
                    { "${number.format(it)} $units" }, pointsOnly = true, gapMillis = null,
                    modifier = Modifier.testTag("activity_heart_rate_$phase"))
            }
        }
        Text(stringResource(DesignR.string.ring_workout_chart_actual_samples), style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Shared Canvas chart for dynamics and exercise progress; missing days remain disconnected. */
@Composable
internal fun ActivityLineChart(
    points: List<ActivityChartPoint>,
    label: String,
    formatX: (Double) -> String,
    formatY: (Double) -> String,
    modifier: Modifier = Modifier,
    pointsOnly: Boolean = false,
    gapMillis: Double? = 86_400_000.0,
    formatTick: (Double) -> String = formatY,
) {
    val observations = remember(points) { points.filter { it.x.isFinite() && it.y.isFinite() } }
    if (observations.isEmpty()) return
    var active by remember(observations) { mutableIntStateOf(0) }
    val palette = activityPalette()
    val textMeasurer = rememberTextMeasurer()
    val tickStyle = MaterialTheme.typography.labelSmall.copy(color = palette.getValue("muted-foreground"))
    val ticks = remember(observations) { activityChartTicks(observations.minOf { it.y }, observations.maxOf { it.y }) }
    var minY = minOf(observations.minOf { it.y }, ticks.first())
    var maxY = maxOf(observations.maxOf { it.y }, ticks.last())
    if (minY == maxY) {
        val padding = abs(minY) * 0.05
        minY -= if (padding == 0.0) 1.0 else padding
        maxY += if (padding == 0.0) 1.0 else padding
    }
    val minX = observations.minOf { it.x }
    val maxX = observations.maxOf { it.x }
    val focused = observations[active.coerceIn(observations.indices)]
    val focusedLabel = "${formatX(focused.x)} · ${formatY(focused.y)}"
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(focusedLabel, style = MaterialTheme.typography.bodySmall)
        Canvas(Modifier.fillMaxWidth().height(if (pointsOnly) 140.dp else 180.dp)
            .semantics {
                contentDescription = label
                stateDescription = focusedLabel
                progressBarRangeInfo = ProgressBarRangeInfo(active.toFloat(), 0f..observations.lastIndex.toFloat(),
                    (observations.size - 2).coerceAtLeast(0))
                setProgress { value -> active = value.roundToInt().coerceIn(observations.indices); true }
            }
            .onKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) false
                else when (event.key) {
                    Key.DirectionRight -> { active = (active + 1).coerceAtMost(observations.lastIndex); true }
                    Key.DirectionLeft -> { active = (active - 1).coerceAtLeast(0); true }
                    else -> false
                }
            }.focusable()
            .pointerInput(observations, minX, maxX) {
                detectTapGestures { offset ->
                    val left = 60.dp.toPx()
                    val inner = (size.width - left - 12.dp.toPx()).coerceAtLeast(1f)
                    val x = minX + ((offset.x - left) / inner).coerceIn(0f, 1f) * (maxX - minX)
                    active = observations.indices.minBy { abs(observations[it].x - x) }
                }
            }) {
            val left = 60.dp.toPx()
            val padding = 12.dp.toPx()
            val width = (size.width - left - padding).coerceAtLeast(1f)
            val height = (size.height - padding * 2).coerceAtLeast(1f)
            fun x(value: Double) = left + if (minX == maxX) width / 2 else ((value - minX) / (maxX - minX) * width).toFloat()
            fun y(value: Double) = padding + ((1 - (value - minY) / (maxY - minY)) * height).toFloat()
            ticks.forEach { tick ->
                val top = y(tick)
                drawLine(palette.getValue("border"), Offset(left, top), Offset(size.width - padding, top))
                val text = textMeasurer.measure(formatTick(tick), tickStyle)
                drawText(text, topLeft = Offset((left - 8.dp.toPx() - text.size.width).coerceAtLeast(0f), top - text.size.height / 2f))
            }
            observations.forEachIndexed { index, point ->
                val location = Offset(x(point.x), y(point.y))
                val previous = observations.getOrNull(index - 1)
                if (!pointsOnly && previous != null && (gapMillis == null || point.x - previous.x <= gapMillis)) {
                    drawLine(palette.getValue("chart-1"), Offset(x(previous.x), y(previous.y)), location, 2.5.dp.toPx())
                }
                drawCircle(palette.getValue("chart-1"), 2.5.dp.toPx(), location)
            }
            val selected = Offset(x(focused.x), y(focused.y))
            drawLine(palette.getValue("border"), Offset(selected.x, padding), Offset(selected.x, size.height - padding))
            drawCircle(palette.getValue("background"), 5.dp.toPx(), selected)
            drawCircle(palette.getValue("chart-1"), 5.dp.toPx(), selected,
                style = androidx.compose.ui.graphics.drawscope.Stroke(2.5.dp.toPx()))
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(formatX(observations.first().x), style = MaterialTheme.typography.labelSmall)
            if (observations.size > 1) Text(formatX(observations.last().x), style = MaterialTheme.typography.labelSmall)
        }
    }
}

/** The web chart's rounded grid: 1, 2, 2.5 or 5 times a power of ten. */
private fun activityChartTicks(min: Double, max: Double): List<Double> {
    val span = (max - min).takeUnless { it == 0.0 } ?: abs(max).takeUnless { it == 0.0 } ?: 1.0
    val raw = span / 4
    val power = 10.0.pow(floor(log10(raw)))
    val step = (listOf(1.0, 2.0, 2.5, 5.0, 10.0).firstOrNull { it * power >= raw } ?: 10.0) * power
    val first = floor(min / step) * step
    return buildList {
        var value = first
        while (value <= max + step * 1e-9 && size < 12) { add(value); value += step }
        if (isEmpty() || last() < max) add(value)
    }
}
