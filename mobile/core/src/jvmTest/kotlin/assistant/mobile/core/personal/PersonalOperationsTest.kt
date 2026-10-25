package assistant.mobile.core.personal

import assistant.mobile.core.AccountNamespace
import assistant.mobile.core.NativeJson
import assistant.mobile.core.journal.Submission
import assistant.mobile.core.journal.SubmissionState
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.time.Instant

class PersonalOperationsTest {
    private val now = Instant.parse("2026-10-17T12:00:00Z").toEpochMilliseconds()

    @Test
    fun profileOnlyChangesExplicitFieldsAndCapturesOriginalRevisionIncludingZero() {
        val draft = PersonalProfileDraft("owner", 0, "", "", "", "Europe/Moscow")
            .copy(heightCm = " 175,50 ", gender = "female")
        val operation = profilePersonalOperation(draft, "2026-10-17")
        assertEquals("personal.profile.update", operation.kind)
        assertEquals(mapOf("owner" to 0L), operation.expectedRevisions)
        assertEquals(setOf("height_cm", "gender"), operation.payload.keys)
        assertEquals("175.50", operation.payload.getValue("height_cm").jsonPrimitive.content)
        assertEquals(mapOf("owner" to 3L), profilePersonalOperation(draft, "2026-10-17", 3).expectedRevisions)
        assertEquals(0L, draft.revision)
        assertEquals(" 175,50 ", draft.heightCm)
    }

    @Test
    fun unitsAreAGuardedProfileOperationAndDoNotConvertStoredMeasurements() {
        val operation = unitsPersonalOperation(PersonalUnitsDraft("owner", 7, "imperial"))
        assertEquals(mapOf("owner" to 7L), operation.expectedRevisions)
        assertEquals(setOf("display_units"), operation.payload.keys)
        assertEquals("imperial", operation.payload.getValue("display_units").jsonPrimitive.content)
        assertFailsWith<IllegalArgumentException> { unitsPersonalOperation(PersonalUnitsDraft("owner", 7, "other")) }
    }

    @Test
    fun goalsKeepExactCanonicalDistanceWhenUnchangedAndConvertOnlyANewInput() {
        val draft = PersonalGoalDraft("distance", "1.234", 4, "metric", "1234.00000001")
        assertEquals("1234.00000001", goalPersonalOperation(draft).payload.getValue("target").jsonPrimitive.content)
        assertEquals(1609.344, goalPersonalOperation(draft.copy(target = "1", initialDisplayTarget = "2", displayUnits = "imperial"))
            .payload.getValue("target").jsonPrimitive.content.toDouble())
        assertEquals("481", goalPersonalOperation(PersonalGoalDraft("sleep", "480.5", 0, "metric")).payload.getValue("target").jsonPrimitive.content)
        val clear = goalPersonalOperation(draft.copy(target = ""), revision = 8)
        assertEquals(JsonNull, clear.payload["target"])
        assertEquals("8", clear.payload.getValue("revision").jsonPrimitive.content)
        assertEquals(emptyMap(), clear.expectedRevisions)
        assertFailsWith<IllegalArgumentException> { goalPersonalOperation(draft.copy(kind = "body_fat_percent")) }
    }

    @Test
    fun weightInputStaysKilogramsAndUsesMeasurementMomentInAccountTimezone() {
        val draft = PersonalWeightDraft(" 72,50 ", "2026-10-17T14:30", "Europe/Moscow")
        val operation = weightPersonalOperation(draft, draft.timezone, now)
        assertEquals("72.50", operation.payload.getValue("weight_kg").jsonPrimitive.content)
        assertEquals("2026-10-17T11:30:00Z", operation.payload.getValue("measured_at").jsonPrimitive.content)
        assertEquals(emptyMap(), operation.expectedRevisions)
        assertFailsWith<PersonalValidationException> { weightPersonalOperation(draft.copy(weightKg = "19.9"), draft.timezone, now) }
        assertFailsWith<PersonalValidationException> { weightPersonalOperation(draft.copy(measuredAt = "2026-10-17T16:00"), draft.timezone, now) }
        assertNull(parsePersonalInputAmount("NaN"))
        assertNull(parsePersonalInputAmount("1e3"))
    }

    @Test
    fun receiptMustMatchTheOperationOwnerKindResultAndAdvancedRevision() {
        val operation = unitsPersonalOperation(PersonalUnitsDraft("owner", 2, "imperial"))
        val submission = Submission(AccountNamespace("synthetic", "https://synthetic.example", "owner"), "key", PERSONAL_OPERATIONS, PERSONAL_LOOKUP + "key", "POST",
            operation.bytes(), "personal", mapOf("owner" to "2"), "personal/units", SubmissionState.RESOLVED, now)
        val receipt = """{"operation_id":"op-1","kind":"personal.profile.update","profile":{"user_id":"owner","timezone":"UTC","display_units":"imperial","height_cm":null,"birth_date":null,"gender":null,"revision":3}}"""
        validatePersonalSubmissionReceipt(submission, receipt.encodeToByteArray())
        assertFailsWith<IllegalArgumentException> { validatePersonalSubmissionReceipt(submission, receipt.replace("\"owner\"", "\"other\"").encodeToByteArray()) }
        assertFailsWith<IllegalArgumentException> { validatePersonalSubmissionReceipt(submission, receipt.replace("\"revision\":3", "\"revision\":2").encodeToByteArray()) }
        assertFailsWith<IllegalArgumentException> { validatePersonalSubmissionReceipt(submission, receipt.replace("\"imperial\"", "\"metric\"").encodeToByteArray()) }
        assertEquals(operation.kind, NativeJson.parseToJsonElement(operation.bytes().decodeToString()).jsonObject.getValue("kind").jsonPrimitive.content)
    }
}
