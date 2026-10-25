package assistant.mobile.core.personal

import assistant.mobile.core.AccountNamespace
import assistant.mobile.core.NativeJson
import assistant.mobile.core.journal.Submission
import assistant.mobile.core.journal.SubmissionState
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Frozen producer shapes from web derivedScores.fixtures.ts / sleepAnalysis.fixture.json and
 * server integration test_personal_fact_versions.py / test_personal_soul1230_temperature_capture.py. */
class PersonalHistoryModelsTest {
    @Test
    fun archivePagesMustBelongToOneSnapshotAndStrictlyDescendingVersions() {
        val page = decodePersonalRecordVersions(personalFixture("history-record-versions"))
        val first = page.copy(versions = page.versions.take(1), nextCursor = "second")
        val second = page.copy(versions = page.versions.drop(1))
        assertTrue(coherentPersonalRecordVersions(listOf(first, second)))
        assertFalse(coherentPersonalRecordVersions(listOf(first, second.copy(fingerprint = "changed-annotation"))))
        assertFalse(coherentPersonalRecordVersions(listOf(first, second.copy(currentRevision = 3))))
        assertFalse(coherentPersonalRecordVersions(listOf(first, first)))
        assertFalse(coherentPersonalRecordVersions(listOf(page.copy(versions = page.versions.reversed()))))
    }

    @Test
    fun archivedScoreCannotBeRelabelledOrAttachedToAnotherScopeDateOrFormula() {
        val original = personalFixtureObject("history-derived-result")
        for ((field, changed) in listOf("source_scope" to "other", "date" to "2026-10-10", "formula_version" to "different", "time_zone" to "Europe/Moscow")) {
            assertFails { decodePersonalArchivedResult(JsonObject(original + (field to JsonPrimitive(changed)))) }
        }
        val definition = original.getValue("definition_snapshot").jsonObject
        assertFails { decodePersonalArchivedResult(JsonObject(original + ("definition_snapshot" to JsonObject(definition + ("unit" to JsonPrimitive("index")))))) }
    }

    @Test
    fun backfillOperationHasAnExactCalendarRangeAndReceiptCannotResolveAnotherCommand() {
        val operation = personalBackfillOperation("2026-01-01", "2027-01-01", "Europe/Moscow")
        assertEquals("personal.derived.backfill", operation.kind)
        assertTrue(operation.expectedRevisions.isEmpty())
        assertFails { personalBackfillOperation("2026-01-01", "2027-01-02", "UTC") }
        assertFails { personalBackfillOperation("2026-10-10", "2026-10-09", "UTC") }
        val oneDay = personalBackfillOperation("2026-10-09", "2026-10-09", "UTC")
        val receipt = """{"operation_id":"operation","kind":"personal.derived.backfill","derivation_run_id":"${"d".repeat(64)}","state":"queued","from":"2026-10-09","to":"2026-10-09","as_of":"2026-10-17T12:00:00Z","scheduled_days":1,"results_saved":0,"pending":true}""".encodeToByteArray()
        validatePersonalBackfillSubmissionReceipt(historyEntry(oneDay), receipt)
        assertEquals(1, decodePersonalBackfillReceipt(receipt).scheduledDays)
        assertFails { validatePersonalBackfillSubmissionReceipt(historyEntry(operation), receipt) }
    }

    @Test
    fun sensorExclusionGuardsBothSampleAndAnnotationRevisions() {
        val sample = decodePersonalSensorSample(NativeJson.parseToJsonElement("""{"id":"sample","source_id":"ring","source_name":"Synthetic ring","metric":"spo2","start_at":"2026-10-09T01:00:00Z","end_at":"2026-10-09T02:00:00Z","timezone":"UTC","local_date":null,"value":null,"reported_min":"94","reported_max":"98","unit":"%","quality":[],"revision":4,"excluded":false,"annotation_revision":2}""").jsonObject)
        val operation = personalSensorExclusionOperation(sample)
        assertEquals(mapOf("sample" to 4L), operation.expectedRevisions)
        assertEquals(JsonPrimitive(2), operation.payload["annotation_revision"])
        val receipt = """{"operation_id":"operation","kind":"personal.sensor.exclude","sample_id":"sample","excluded":true,"annotation_id":"annotation","annotation_revision":3}""".encodeToByteArray()
        validatePersonalSensorSubmissionReceipt(historyEntry(operation), receipt)
        assertFails { validatePersonalSensorSubmissionReceipt(historyEntry(operation), receipt.decodeToString().replace("\"annotation_revision\":3", "\"annotation_revision\":2").encodeToByteArray()) }
        assertEquals("personal.sensor.restore", personalSensorExclusionOperation(sample.copy(excluded = true)).kind)
    }
}

private fun historyEntry(operation: PersonalOperation): Submission = Submission(AccountNamespace("synthetic", "https://synthetic.example", "owner"),
    "11111111-1111-4111-8111-111111111111", PERSONAL_OPERATIONS, PERSONAL_LOOKUP + "11111111-1111-4111-8111-111111111111", "POST", operation.bytes(),
    "personal", operation.expectedRevisions.mapValues { it.value.toString() }, null, SubmissionState.RESOLVED, 0)
