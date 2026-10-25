package assistant.mobile.core.personal

import kotlin.test.Test
import kotlin.test.assertEquals

class PersonalDerivedMetadataTest {
    @Test
    fun unknownCodesAndSuffixesStayUnknownWhileSpecialConditionsKeepTheirExactMeaning() {
        listOf("unrecognized", "unrecognized:invalid", "night_hr:INVALID", "night_hr:four_samples:future").forEach { code ->
            val message = personalDerivedMissingMessage(code)
            assertEquals(PersonalDerivedMissingQualifier.Direct, message.qualifier)
            assertEquals("derivedMissingInputs.unknown", message.label.key.webKey)
        }
        val condition = personalDerivedMissingMessage("night_hr:four_samples")
        assertEquals(PersonalDerivedMissingQualifier.Direct, condition.qualifier)
        assertEquals("derivedMissingInputs.conditions.night_hr_four_samples", condition.label.key.webKey)
        assertEquals("personal.metric.weight", personalDerivedMissingMessage("weight_kg").label.key.webKey)
        assertEquals("personal.age", personalDerivedMissingMessage("age").label.key.webKey)
        assertEquals("activity.steps", personalDerivedMissingMessage("steps").label.key.webKey)
    }
}
