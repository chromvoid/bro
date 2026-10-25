package assistant.mobile.trading

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import assistant.mobile.design.GeneratedColors
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.pow

@Composable
internal fun tradingColor(token: String): Color {
    val palette = if (MaterialTheme.colorScheme.background.luminance() < 0.5f) GeneratedColors.dark else GeneratedColors.light
    return palette.getValue(token)
}

internal data class TradingChartPoint(val at: Long, val value: Double, val note: String? = null, val flow: Int = 0)
internal data class TradingChartTrade(val at: Long, val value: Double, val sell: Boolean, val label: String)
internal data class TradingChartLevel(val value: Double, val label: String, val token: String)
internal data class TradingChartBar(val label: String, val value: Double?, val text: String)
internal data class TradingChartPart(val value: Double?, val text: String)
internal data class TradingChartColumn(val key: String, val tick: String, val label: String, val parts: List<TradingChartPart>)
internal data class TradingChartRank(val key: String, val label: String, val value: Double?, val text: String)

/** Rounded chart grid only; service decimals remain the source of all financial text. */
private fun chartTicks(minimum: Double, maximum: Double, count: Int = 3): List<Double> {
    if (!minimum.isFinite() || !maximum.isFinite()) return emptyList()
    var low = minimum
    var high = maximum
    if (high - low <= max(abs(low), abs(high)) * 1e-9) {
        val pad = (abs(low) * 0.05).takeIf { it > 0 } ?: 1.0
        low -= pad
        high += pad
    }
    val rough = (high - low) / count
    val power = 10.0.pow(floor(log10(rough)))
    val step = listOf(1.0, 2.0, 5.0, 10.0).first { it * power >= rough } * power
    val first = floor(low / step)
    val intervals = ceil(high / step - first - 1e-9).toInt().coerceIn(1, count * 3)
    return (0..intervals).map { (first + it) * step }
}

@Composable
internal fun TradingAreaChart(points: List<TradingChartPoint>, label: String, tag: String, format: (Double) -> String) {
    TradingLineChart(points, label, tag, format, area = true)
}

/** Native Canvas chart; every data point and trade is also reachable through accessibility actions. */
@Composable
internal fun TradingLineChart(
    points: List<TradingChartPoint>,
    label: String,
    tag: String,
    format: (Double) -> String,
    area: Boolean = false,
    trades: List<TradingChartTrade> = emptyList(),
    levels: List<TradingChartLevel> = emptyList(),
) {
    if (points.isEmpty()) return
    var active by remember(points) { mutableStateOf<Int?>(null) }
    val shown = points[(active ?: points.lastIndex).coerceIn(points.indices)]
    val primary = tradingColor(if (area) "chart-1" else "foreground")
    val border = tradingColor("border")
    val up = tradingColor("success")
    val down = tradingColor("destructive")
    val muted = tradingColor("muted-foreground")
    val levelColors = levels.map { tradingColor(it.token) }
    val textMeasurer = rememberTextMeasurer()
    val axisStyle = MaterialTheme.typography.labelSmall.copy(color = muted)
    val prices = points.map { it.value } + trades.map { it.value }
    val low = prices.minOrNull() ?: 0.0
    val high = prices.maxOrNull() ?: 1.0
    val difference = max(high - low, max(abs(high) * 0.02, 0.00000001))
    val nearLevels = levels.withIndex().filter { it.value.value > low - difference && it.value.value < high + difference }
    val grid = if (area) chartTicks(low, high) else emptyList()
    val bottom = grid.firstOrNull() ?: (minOf(low, nearLevels.minOfOrNull { it.value.value } ?: low) - difference * 0.08)
    val top = grid.lastOrNull() ?: (maxOf(high, nearLevels.maxOfOrNull { it.value.value } ?: high) + difference * 0.08)
    val axisLabels = grid.map { textMeasurer.measure(format(it), axisStyle) }
    val first = points.first().at
    val last = points.last().at
    val span = max(last - first, 1L)
    val nearestTrades = if (active == null) emptyList() else trades.filter {
        abs(it.at - shown.at) <= span / max(points.size - 1, 1) / 2
    }
    val accessibility = buildString {
        append(label)
        append(": ")
        append(TradingFormat.dateTime(java.time.Instant.ofEpochMilli(first).toString()))
        append(" – ")
        append(TradingFormat.dateTime(java.time.Instant.ofEpochMilli(last).toString()))
        append(" · ")
        append(format(low))
        append(" – ")
        append(format(high))
    }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(if (active == null) "${TradingFormat.dateTime(java.time.Instant.ofEpochMilli(first).toString())} – ${TradingFormat.dateTime(java.time.Instant.ofEpochMilli(last).toString())}"
            else TradingFormat.dateTime(java.time.Instant.ofEpochMilli(shown.at).toString()),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(format(shown.value), style = MaterialTheme.typography.titleMedium)
            if (area && points.first().value != 0.0) {
                val change = ((shown.value - points.first().value) / abs(points.first().value)) * 100
                Text(TradingFormat.percent(change.toBigDecimal().toPlainString()),
                    style = MaterialTheme.typography.bodyMedium, color = if (change >= 0) up else down)
            }
        }
        shown.note?.takeIf { active != null }?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        nearestTrades.forEach { Text(it.label, style = MaterialTheme.typography.bodySmall) }
        if (!area) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(format(top), style = MaterialTheme.typography.labelSmall)
            Text(format(bottom), style = MaterialTheme.typography.labelSmall, textAlign = TextAlign.End)
        }
        Canvas(Modifier.fillMaxWidth().height(if (area) 180.dp else 220.dp).testTag(tag)
            .semantics {
                contentDescription = accessibility
                role = Role.Image
                customActions = points.mapIndexed { index, point ->
                    CustomAccessibilityAction("${TradingFormat.dateTime(java.time.Instant.ofEpochMilli(point.at).toString())} · ${format(point.value)}${point.note?.let { " · $it" } ?: ""}") {
                        active = index; true
                    }
                } + trades.map { trade ->
                    CustomAccessibilityAction("${TradingFormat.dateTime(java.time.Instant.ofEpochMilli(trade.at).toString())} · ${trade.label}") {
                        active = points.indices.minByOrNull { abs(points[it].at - trade.at) }; true
                    }
                }
            }.onKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) false else when (event.key) {
                    Key.DirectionRight -> { active = ((active ?: -1) + 1).coerceAtMost(points.lastIndex); true }
                    Key.DirectionLeft -> { active = ((active ?: points.size) - 1).coerceAtLeast(0); true }
                    Key.Escape -> { active = null; true }
                    else -> false
                }
            }.focusable().pointerInput(points, axisLabels) {
                detectTapGestures { point ->
                    val inset = 12.dp.toPx()
                    val gutter = if (area) max(inset, (axisLabels.maxOfOrNull { it.size.width } ?: 0).toFloat() + 6.dp.toPx()) else inset
                    val sought = first + (((point.x - gutter) / (size.width - gutter - inset)).coerceIn(0f, 1f) * span).toLong()
                    active = points.indices.minByOrNull { abs(points[it].at - sought) }
                }
            }) {
            val inset = 12.dp.toPx()
            val gutter = if (area) max(inset, (axisLabels.maxOfOrNull { it.size.width } ?: 0).toFloat() + 6.dp.toPx()) else inset
            fun x(at: Long) = if (first == last) (gutter + size.width - inset) / 2 else gutter + ((at - first).toDouble() / span).toFloat() * (size.width - gutter - inset)
            fun y(value: Double) = inset + ((top - value) / (top - bottom)).toFloat() * (size.height - 2 * inset)
            if (area) grid.forEachIndexed { index, value ->
                val at = y(value)
                drawLine(border, Offset(gutter, at), Offset(size.width - inset, at),
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(2.dp.toPx(), 4.dp.toPx())))
                val measured = axisLabels[index]
                drawText(measured, topLeft = Offset(gutter - measured.size.width - 6.dp.toPx(), at - measured.size.height / 2))
            } else repeat(4) { index ->
                val at = inset + (size.height - 2 * inset) * index / 3
                drawLine(border, Offset(inset, at), Offset(size.width - inset, at),
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(2.dp.toPx(), 4.dp.toPx())))
            }
            val path = Path().apply {
                points.forEachIndexed { index, point ->
                    if (index == 0) moveTo(x(point.at), y(point.value)) else lineTo(x(point.at), y(point.value))
                }
            }
            if (area) {
                val fill = Path().apply {
                    addPath(path)
                    lineTo(x(last), size.height - inset)
                    lineTo(x(first), size.height - inset)
                    close()
                }
                drawPath(fill, primary.copy(alpha = 0.15f))
            }
            nearLevels.forEach { (index, level) ->
                drawLine(levelColors[index], Offset(inset, y(level.value)), Offset(size.width - inset, y(level.value)),
                    strokeWidth = 1.5.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 4.dp.toPx())))
            }
            drawPath(path, primary, style = Stroke(width = 1.75.dp.toPx()))
            points.filter { it.flow != 0 }.forEach { point ->
                val left = x(point.at)
                val at = size.height - inset / 2
                val direction = if (point.flow > 0) -1 else 1
                val mark = Path().apply {
                    moveTo(left - 3.dp.toPx(), at - direction * 3.dp.toPx())
                    lineTo(left, at + direction * 3.dp.toPx())
                    lineTo(left + 3.dp.toPx(), at - direction * 3.dp.toPx())
                    close()
                }
                drawPath(mark, if (point.flow > 0) up else down)
            }
            trades.forEach { trade ->
                val left = x(trade.at)
                val at = y(trade.value)
                val side = if (trade.sell) -1 else 1
                val mark = Path().apply {
                    moveTo(left - 4.dp.toPx(), at + side * 10.dp.toPx())
                    lineTo(left, at + side * 3.dp.toPx())
                    lineTo(left + 4.dp.toPx(), at + side * 10.dp.toPx())
                    close()
                }
                drawPath(mark, if (trade.sell) down else up)
            }
            active?.let {
                drawLine(border, Offset(x(shown.at), inset), Offset(x(shown.at), size.height - inset))
                drawCircle(primary, 4.dp.toPx(), Offset(x(shown.at), y(shown.value)))
            }
        }
        levels.forEachIndexed { index, level ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Canvas(Modifier.height(16.dp).weight(0.1f)) {
                    drawLine(levelColors[index], Offset(0f, size.height / 2), Offset(size.width, size.height / 2),
                        strokeWidth = 1.5.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 4.dp.toPx())))
                }
                Text("${level.label} ${format(level.value)}", Modifier.weight(0.9f), style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

/** One-part monthly bars share the same signed scale and selection as cashflow columns. */
@Composable
internal fun TradingSignedBars(bars: List<TradingChartBar>, label: String, tag: String) {
    TradingSignedColumns(bars.mapIndexed { index, bar ->
        TradingChartColumn(index.toString(), bar.label, bar.label, listOf(TradingChartPart(bar.value, bar.text)))
    }, label, tag)
}

/** Each period stacks money in above zero and money out below it, including unknown parts as text. */
@Composable
internal fun TradingSignedColumns(
    columns: List<TradingChartColumn>,
    label: String,
    tag: String,
    idle: String = "",
    formatTick: (Double) -> String = { TradingFormat.amount(it.toBigDecimal().stripTrailingZeros().toPlainString()) },
) {
    if (columns.isEmpty()) return
    var active by remember(columns) { mutableStateOf<Int?>(null) }
    val up = tradingColor("success")
    val down = tradingColor("destructive")
    val border = tradingColor("border")
    val muted = tradingColor("muted-foreground")
    val highlight = tradingColor("muted")
    val warning = tradingColor("warning")
    val textMeasurer = rememberTextMeasurer()
    val axisStyle = MaterialTheme.typography.labelSmall.copy(color = muted)
    val values = columns.map { column -> column.parts.mapNotNull { it.value?.takeIf(Double::isFinite) } }
    val grid = chartTicks(minOf(0.0, values.minOf { parts -> parts.sumOf { minOf(it, 0.0) } }),
        maxOf(0.0, values.maxOf { parts -> parts.sumOf { maxOf(it, 0.0) } }))
    val axisLabels = grid.map { textMeasurer.measure(formatTick(it), axisStyle) }
    val tickLabels = columns.map { textMeasurer.measure(it.tick, axisStyle) }
    val selected = active?.let(columns::getOrNull)
    val unknown = columns.filter { column -> column.parts.any { it.value == null || !it.value.isFinite() } }
    fun description(column: TradingChartColumn) = "${column.label}: ${column.parts.joinToString(" · ") { it.text }}"
    Column(Modifier.testTag(tag), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(selected?.let(::description) ?: idle.ifEmpty { "\u00a0" }, style = MaterialTheme.typography.bodySmall,
            color = if (selected == null) muted else MaterialTheme.colorScheme.onSurface)
        Canvas(Modifier.fillMaxWidth().height(160.dp).semantics {
            contentDescription = "$label: " + columns.joinToString(" · ", transform = ::description)
            role = Role.Image
            customActions = columns.mapIndexed { index, column ->
                CustomAccessibilityAction(description(column)) { active = index; true }
            }
        }.onKeyEvent { event ->
            if (event.type != KeyEventType.KeyDown) false else when (event.key) {
                Key.DirectionRight -> { active = ((active ?: -1) + 1).coerceAtMost(columns.lastIndex); true }
                Key.DirectionLeft -> { active = ((active ?: columns.size) - 1).coerceAtLeast(0); true }
                Key.Escape -> { active = null; true }
                else -> false
            }
        }.focusable().pointerInput(columns, axisLabels) {
            detectTapGestures { point ->
                val gutter = (axisLabels.maxOfOrNull { it.size.width } ?: 0).toFloat() + 6.dp.toPx()
                val slot = (size.width - gutter - 4.dp.toPx()) / columns.size
                active = floor((point.x - gutter) / slot).toInt().takeIf { it in columns.indices }
            }
        }) {
            val top = 12.dp.toPx()
            val bottom = size.height - (tickLabels.maxOfOrNull { it.size.height } ?: 0) - 4.dp.toPx()
            val gutter = (axisLabels.maxOfOrNull { it.size.width } ?: 0).toFloat() + 6.dp.toPx()
            val right = size.width - 4.dp.toPx()
            val slot = (right - gutter) / columns.size
            val width = minOf(28.dp.toPx(), max(2.dp.toPx(), slot * 0.66f))
            fun y(value: Double) = bottom - ((value - grid.first()) / (grid.last() - grid.first())).toFloat() * (bottom - top)
            active?.let { drawRect(highlight, Offset(gutter + it * slot, top), Size(slot, bottom - top)) }
            grid.forEachIndexed { index, value ->
                val at = y(value)
                drawLine(if (value == 0.0) muted.copy(alpha = 0.5f) else border, Offset(gutter, at), Offset(right, at),
                    pathEffect = if (value == 0.0) null else PathEffect.dashPathEffect(floatArrayOf(2.dp.toPx(), 4.dp.toPx())))
                val measured = axisLabels[index]
                drawText(measured, topLeft = Offset(gutter - measured.size.width - 6.dp.toPx(), at - measured.size.height / 2))
            }
            val every = ceil(((tickLabels.maxOfOrNull { it.size.width } ?: 0) + 8.dp.toPx()) / slot).toInt().coerceAtLeast(1)
            columns.forEachIndexed { index, column ->
                val center = gutter + (index + 0.5f) * slot
                var above = 0.0
                var below = 0.0
                column.parts.forEach { part ->
                    val value = part.value?.takeIf { it.isFinite() && it != 0.0 } ?: return@forEach
                    val from = if (value > 0) above else below
                    val to = from + value
                    if (value > 0) above = to else below = to
                    val rectTop = y(maxOf(from, to))
                    val height = max(1.dp.toPx(), y(minOf(from, to)) - rectTop)
                    drawRect(if (value > 0) up else down, Offset(center - width / 2, rectTop), Size(width, height))
                }
                if (index % every == 0) {
                    val measured = tickLabels[index]
                    drawText(measured, topLeft = Offset((center - measured.size.width / 2).coerceIn(0f, max(0f, size.width - measured.size.width)), bottom + 4.dp.toPx()))
                }
            }
        }
        if (unknown.isNotEmpty()) Text(unknown.joinToString(" · ") { column ->
            "${column.label}: ${column.parts.filter { it.value == null || !it.value.isFinite() }.joinToString(", ") { it.text }}"
        }, color = warning, style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("${tag}_unknown"))
    }
}

@Composable
internal fun TradingShareBar(items: List<Pair<String, Double>>, label: String) {
    val colors = listOf(1, 3, 2, 4, 5).map { tradingColor("chart-$it") }
    val restColor = tradingColor("muted-foreground").copy(alpha = 0.4f)
    val positive = items.filter { it.second > 0 && it.second.isFinite() }.sortedByDescending { it.second }
    val total = positive.sumOf { it.second }
    if (total <= 0 || !total.isFinite()) return
    val head = positive.take(if (positive.size > 5) 4 else 5)
    val parts = head + if (positive.size > head.size) listOf(tradingText("others") to positive.drop(head.size).sumOf { it.second }) else emptyList()
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
    Canvas(Modifier.fillMaxWidth().height(10.dp).semantics {
        contentDescription = "$label: " + parts.joinToString(" · ") {
            "${it.first} ${TradingFormat.percent((it.second / total * 100).toString(), false)}"
        }
        role = Role.Image
    }) {
        var start = 0f
        parts.forEachIndexed { index, item ->
            val width = (item.second.coerceAtLeast(0.0) / total * size.width).toFloat()
            drawRect(if (index >= head.size) restColor else colors[index % colors.size], Offset(start, 0f), Size(max(width - size.width * 0.004f, size.width * 0.002f), size.height))
            start += width
        }
    }
    parts.forEachIndexed { index, item ->
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Canvas(Modifier.padding(top = 4.dp).height(8.dp).widthIn(min = 8.dp)) {
                drawCircle(if (index >= head.size) restColor else colors[index % colors.size])
            }
            Text("${item.first} ${TradingFormat.percent((item.second / total * 100).toBigDecimal().toPlainString(), false)}", style = MaterialTheme.typography.bodySmall)
        }
    }
    }
}

/** A shared signed scale keeps gains and losses comparable; all values remain readable as text. */
@Composable
internal fun TradingRankBars(items: List<TradingChartRank>, label: String, tag: String, onSelect: ((String) -> Unit)? = null) {
    if (items.isEmpty()) return
    val up = tradingColor("success")
    val down = tradingColor("destructive")
    val border = tradingColor("border")
    val muted = tradingColor("muted-foreground")
    val values = items.mapNotNull { it.value?.takeIf(Double::isFinite) }
    val low = minOf(0.0, values.minOrNull() ?: 0.0)
    val high = maxOf(0.0, values.maxOrNull() ?: 0.0).takeIf { it != low } ?: low + 1
    val zero = (-low / (high - low)).toFloat()
    Column(Modifier.testTag(tag).semantics { contentDescription = label }, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        items.forEach { item ->
            val value = item.value?.takeIf(Double::isFinite)
            val tone = if (value == null) muted else if (value >= 0) up else down
            val selection = if (onSelect != null) Modifier.clickable(role = Role.Button) { onSelect(item.key) } else Modifier
            Row(Modifier.fillMaxWidth().then(selection).heightIn(min = 48.dp).padding(horizontal = 8.dp, vertical = 6.dp)
                .testTag("trading_rank_${item.key}").semantics(mergeDescendants = true) {},
                horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Text(item.label, Modifier.widthIn(max = 64.dp), style = MaterialTheme.typography.bodyMedium)
                Canvas(Modifier.weight(1f).height(12.dp)) {
                    if (value != null) {
                        val end = ((value - low) / (high - low)).toFloat()
                        drawLine(border, Offset(zero * size.width, 0f), Offset(zero * size.width, size.height))
                        drawRect(tone, Offset(minOf(zero, end) * size.width, 1.dp.toPx()),
                            Size(max(size.width * abs(end - zero), size.width * 0.005f), size.height - 2.dp.toPx()))
                    }
                }
                Text(item.text, Modifier.widthIn(max = 144.dp), style = MaterialTheme.typography.bodyMedium, color = tone, textAlign = TextAlign.End)
            }
        }
    }
}
