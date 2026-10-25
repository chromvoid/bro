package assistant.mobile.core.personal

import assistant.mobile.core.NativeJson
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Frozen synthetic shapes from resolverFixtures.ts, derivedScores.fixtures.ts and test_caliper.py. */
class PersonalModelsTest {
    @Test
    fun displayUnitsUseWebConversionConstantsAndNullIsNotZero() {
        assertEquals("100 lb", personalMassText("45.359237", PersonalDisplayUnits.Imperial))
        assertEquals("72,45 кг", personalAmountText("weight", "72.4500"))
        assertEquals("1 мили", personalAmountText("distance", "1609.344", PersonalDisplayUnits.Imperial))
        assertEquals("1,61 км", personalAmountText("distance", "1609.344"))
        assertEquals("98,6 °F", personalSensorAmount("37", "celsius", PersonalDisplayUnits.Imperial))
        assertEquals("5 ft 10,9 in", personalHeightText("180", PersonalDisplayUnits.Imperial))
        assertEquals("180 см", personalHeightText("180", PersonalDisplayUnits.Metric))
        assertNull(personalAmountText("weight", null))
        assertNull(personalSensorAmount(null, "bpm"))
        assertNull(personalHeightText(null, PersonalDisplayUnits.Imperial))
        assertEquals("0 кг", personalAmountText("weight", "0"))
    }

    @Test
    fun sensorFormattingRetainsUnitsAndRejectsNonFiniteAmounts() {
        assertEquals("65,25 уд/мин", personalSensorAmount("65.25", "bpm"))
        assertEquals("28 мс", personalSensorAmount("28", "ms"))
        assertEquals("12 индекс", personalSensorAmount("12", "index"))
        assertNull(personalSensorAmount("NaN", "index"))
        assertNull(personalAmountText("weight", ""))
        assertNull(personalAmountText("weight", "Infinity"))
        assertEquals("72,45 кг", personalAmountText("weight", "7.245E1"))
    }

    @Test
    fun newDistanceGoalsMultiplyDecimalsExactlyAndUneditedInputRetainsSixDigits() {
        assertEquals("1609.344", personalDistanceTargetMeters("1", PersonalDisplayUnits.Imperial))
        assertEquals("1986.79954176", personalDistanceTargetMeters("1,23454", PersonalDisplayUnits.Imperial))
        assertEquals("1250", personalDistanceTargetMeters("1.25", PersonalDisplayUnits.Metric))
        assertEquals("0", personalDistanceTargetMeters("0", PersonalDisplayUnits.Metric))
        assertEquals("1", personalDistanceInput("1609.344", PersonalDisplayUnits.Imperial))
        assertEquals("1.234568", personalDistanceInput("1234.56789", PersonalDisplayUnits.Metric))
        assertNull(personalDistanceTargetMeters("-1", PersonalDisplayUnits.Metric))
        assertNull(personalDistanceTargetMeters("NaN", PersonalDisplayUnits.Metric))
    }

    @Test
    fun completedAgeMatchesMeasurementLocalCalendarDate() {
        assertEquals(35, personalAgeOn("1990-10-05", "2026-10-04"))
        assertEquals(36, personalAgeOn("1990-10-05", "2026-10-05"))
        assertEquals(36, personalAgeOn("1990-10-05", "2026-10-06"))
        assertNull(personalAgeOn("2027-01-01", "2026-10-05"))
        assertNull(personalAgeOn(null, "2026-10-05"))
    }

}

internal fun personalFixture(name: String): ByteArray = requireNotNull(PersonalModelsTest::class.java.getResourceAsStream("/personal/$name.json")).use { it.readBytes() }
internal fun personalFixtureObject(name: String): JsonObject = NativeJson.parseToJsonElement(personalFixture(name).decodeToString()).jsonObject
