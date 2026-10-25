package assistant.mobile.trading

import java.math.BigDecimal
import java.time.Instant
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Exact service decimals keep the web client's rounding, grouping and missing-value meaning. */
internal object TradingFormat {
    const val Missing = "—"
    private val decimal = Regex("-?[0-9]+(?:\\.[0-9]+)?")

    fun number(raw: String?): BigDecimal? = raw?.trim()?.takeIf(decimal::matches)?.toBigDecimalOrNull()
    fun nonZero(raw: String?): Boolean = number(raw)?.signum()?.let { it != 0 } == true
    fun negative(raw: String?): Boolean = number(raw)?.signum()?.let { it < 0 } == true
    fun amount(raw: String?): String = assistant.mobile.trading.amount(raw)
    fun usd(raw: String?, sign: Boolean = false): String = assistant.mobile.trading.usd(raw, sign)
    fun usdShort(raw: String?, sign: Boolean = false): String = assistant.mobile.trading.usdShort(raw, sign)
    fun percent(raw: String?, sign: Boolean = true): String = assistant.mobile.trading.percent(raw, sign)
    fun money(raw: String?, unit: String, sign: Boolean = false): String = assistant.mobile.trading.inUnit(raw, unit, sign)
    fun price(raw: String?, unit: String): String = assistant.mobile.trading.price(raw, unit)
    fun instant(raw: String?): Long? = runCatching { Instant.parse(raw).toEpochMilli() }.getOrNull()
    fun dateTime(raw: String?): String = instant(raw)?.let { at ->
        DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm").withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(at))
    } ?: Missing
    fun date(raw: String?): String = dateTime(raw).takeIf { it != Missing }?.take(10) ?: Missing
    fun moment(raw: String?, now: Long): String {
        val shown = dateTime(raw)
        val today = dateTime(Instant.ofEpochMilli(now).toString())
        return when {
            shown == Missing -> Missing
            shown.take(10) == today.take(10) -> shown.drop(11)
            shown.substring(6, 10) == today.substring(6, 10) -> shown.take(5) + " " + shown.drop(11)
            else -> shown
        }
    }
    fun month(raw: String, long: Boolean = false): String = runCatching {
        YearMonth.parse(raw).format(DateTimeFormatter.ofPattern(if (long) "LLLL yyyy" else "LLL", Locale.forLanguageTag("ru"))).trimEnd('.')
    }.getOrDefault(raw)
}
