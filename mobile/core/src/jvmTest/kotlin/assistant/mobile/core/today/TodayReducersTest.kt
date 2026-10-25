package assistant.mobile.core.today

import assistant.mobile.core.NativeJson
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Instant

class TodayReducersTest {
    @Test
    fun dayUsesTheAccountsZoneAcrossMidnightAndDst() {
        val boundary = Instant.parse("2026-10-15T21:00:00Z").toEpochMilliseconds()
        assertEquals("2026-10-15", todayIn("Europe/Moscow", boundary - 1))
        assertEquals("2026-10-16", todayIn("Europe/Moscow", boundary))
        assertEquals("2026-10-15", todayIn("UTC", boundary))
        assertEquals("2026-10-15", todayIn("America/Los_Angeles", boundary))
        assertEquals("2026-11-01", todayIn("America/New_York",
            Instant.parse("2026-11-01T06:00:00Z").toEpochMilliseconds()))
    }

    @Test
    fun decimalDisplayMatchesRussianFixtureRoundingWithoutBinaryPrecisionLoss() {
        assertEquals("1\u00a0235", formatTodayAmount("1234.50", 0))
        assertEquals("72,5", formatTodayAmount("72.45"))
        assertEquals("-0,9", formatTodayAmount("-0.85"))
        assertEquals("10", formatTodayAmount("9.995", 2))
        assertEquals("9\u00a0007\u00a0199\u00a0254\u00a0740\u00a0993", formatTodayAmount("9007199254740993", 0))
        assertEquals("0", formatTodayAmount("0.00"))
        for (unknown in listOf(null, "", "NaN", "Infinity", "true")) assertEquals("—", formatTodayAmount(unknown))
    }

    @Test
    fun signedWeightDeltaMatchesTheWebRoundingAndTypography() {
        assertEquals("−0,8", formatTodayWeightChange("-0.85"))
        assertEquals("0", formatTodayWeightChange("-0.05"))
        assertEquals("+0,1", formatTodayWeightChange("0.05"))
        assertEquals("+1,3", formatTodayWeightChange("1.25"))
        assertEquals("−1,2", formatTodayWeightChange("-1.25"))
    }
}

internal fun todayFixture(name: String, source: String): ByteArray = todayFixtureObject(name)
    .getValue(source).toString().encodeToByteArray()

internal fun todayFixtureObject(name: String): JsonObject {
    val bytes = requireNotNull(TodayReducersTest::class.java.getResourceAsStream("/today/$name.json")).use { it.readBytes() }
    return NativeJson.parseToJsonElement(bytes.decodeToString()).jsonObject
}
