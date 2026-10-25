package assistant.mobile.trading

import kotlin.time.Instant
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.log10

const val TRADING_MISSING = "—"
private const val NBSP = "\u00a0"
const val TRADING_STALE_AFTER_MS: Long = 10 * 60_000

/** Exact text rounding, half away from zero: financial values never round through Double. */
fun roundDecimal(value: String, places: Int): String? {
    require(places >= 0)
    val text = value.trim()
    val negative = text.startsWith('-')
    val body = if (negative) text.drop(1) else text
    val parts = body.split('.')
    if (parts.size !in 1..2 || parts.any { it.isEmpty() || it.any { c -> c !in '0'..'9' } }) return null
    val whole = parts[0]
    val fraction = parts.getOrElse(1) { "" }
    val digits = (whole + fraction.padEnd(places, '0').take(places)).toMutableList()
    if (fraction.length > places && fraction[places] >= '5') {
        var index = digits.lastIndex
        while (index >= 0 && digits[index] == '9') { digits[index] = '0'; index-- }
        if (index < 0) digits.add(0, '1') else digits[index] = digits[index] + 1
    }
    val rounded = digits.joinToString("")
    val integer = rounded.dropLast(places).trimStart('0').ifEmpty { "0" }
    val decimal = if (places == 0) "" else rounded.takeLast(places).trimEnd('0')
    val result = if (decimal.isEmpty()) integer else "$integer.$decimal"
    return if (negative && result.any { it in '1'..'9' }) "-$result" else result
}

fun groupThousands(value: String): String {
    val sign = value.takeWhile { it == '-' || it == '+' }
    val parts = value.drop(sign.length).split('.', limit = 2)
    val grouped = parts.first().reversed().chunked(3).joinToString(NBSP).reversed()
    return sign + grouped + if (parts.size == 2) ",${parts[1]}" else ""
}

fun signed(value: String): String = if (!value.startsWith('-') && value.any { it in '1'..'9' }) "+$value" else value

fun usd(value: String?, sign: Boolean = false): String = formatted(value, 2, "$", sign)
fun percent(value: String?, sign: Boolean = true): String = formatted(value, 2, "%", sign)
fun inUnit(value: String?, unit: String, sign: Boolean = false): String = formatted(value, if (unit == "USD") 2 else 8, if (unit == "USD") "$" else unit, sign)

private fun formatted(value: String?, places: Int, suffix: String, sign: Boolean): String {
    val rounded = value?.let { roundDecimal(it, places) } ?: return TRADING_MISSING
    return groupThousands(if (sign) signed(rounded) else rounded) + NBSP + suffix
}

fun usdShort(value: String?, sign: Boolean = false): String {
    val size = value?.let { roundDecimal(it.removePrefix("-"), 0) }
    return usd(if (size != null && size.length >= 3) value?.let { roundDecimal(it, 0) } else value, sign)
}

fun price(value: String?, unit: String): String {
    if (unit != "USD" || value == null) return inUnit(value, unit)
    val size = abs(value.toDoubleOrNull() ?: Double.NaN)
    val places = if (!size.isFinite() || size == 0.0 || size >= 1.0) 2 else minOf(12, 3 - floor(log10(size)).toInt())
    return formatted(value, places, "$", false)
}

fun amount(value: String?): String {
    val text = value?.trim() ?: return TRADING_MISSING
    return if (roundDecimal(text, 0) == null) TRADING_MISSING else groupThousands(text)
}

fun tradingTimeMillis(value: String?): Long? = value?.let { runCatching { Instant.parse(it).toEpochMilliseconds() }.getOrNull() }

fun isStale(freshness: Freshness, now: Long): Boolean =
    freshness.connectionState !in setOf("active", "validating") ||
        tradingTimeMillis(freshness.asOf)?.let { now - it > TRADING_STALE_AFTER_MS } != false

/** Invalid samples do not become zeros on a chart. Original prices/quantities stay exact in the model. */
data class TradingChartSample(val time: Long, val value: Double, val flow: Double? = null, val rebuilt: Boolean = false)
fun samples(history: PortfolioHistory): List<TradingChartSample> = history.points.mapNotNull {
    val time = tradingTimeMillis(it.time) ?: return@mapNotNull null
    val value = it.totalUsd.toDoubleOrNull()?.takeIf(Double::isFinite) ?: return@mapNotNull null
    TradingChartSample(time, value, it.flowUsd?.toDoubleOrNull()?.takeIf(Double::isFinite), it.rebuilt)
}.sortedBy { it.time }
fun samples(chart: PositionChart): List<TradingChartSample> = chart.points.mapNotNull {
    val time = tradingTimeMillis(it.time) ?: return@mapNotNull null
    val value = it.price.toDoubleOrNull()?.takeIf(Double::isFinite) ?: return@mapNotNull null
    TradingChartSample(time, value)
}.sortedBy { it.time }
