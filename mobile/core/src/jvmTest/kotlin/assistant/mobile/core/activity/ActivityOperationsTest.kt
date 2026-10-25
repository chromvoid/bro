package assistant.mobile.core.activity

import assistant.mobile.core.AccountNamespace
import assistant.mobile.core.journal.Submission
import assistant.mobile.core.journal.SubmissionState
import assistant.mobile.core.personal.PERSONAL_LOOKUP
import assistant.mobile.core.personal.PERSONAL_OPERATIONS
import kotlinx.serialization.json.*
import kotlin.test.*
import kotlin.time.Instant

/** Inputs and receipt shapes follow server/plugins/activity/models.py and the web form fixtures. */
class ActivityOperationsTest {
    private val now = Instant.parse("2026-10-17T12:00:00Z").toEpochMilliseconds()

    @Test
    fun setPinsParentExerciseAndSetRevisionsWithoutLosingRawEditorValues() {
        val set = activityTestSession().exercises.single().sets.single()
        val exercise = activityTestSession().exercises.single()
        val draft = activitySetDraft(activityTestSession(), exercise, set).copy(repetitions = "0", loadValue = " 0,00 ", implementCount = "2")
        val operation = setActivityOperation(draft)
        assertEquals("activity.set.update", operation.kind)
        assertEquals(mapOf("session-1" to 4L, "performed-1" to 3L, "set-1" to 2L), operation.expectedRevisions)
        assertEquals("0", operation.payload.getValue("repetitions").jsonPrimitive.content)
        assertEquals("0.00", operation.payload.getValue("load_value").jsonPrimitive.content)
        assertEquals("2", operation.payload.getValue("implement_count").jsonPrimitive.content)
        assertEquals(" 0,00 ", draft.loadValue)
        assertFailsWith<IllegalArgumentException> { setActivityOperation(draft, mapOf("session-1" to 4)) }
        assertEquals(mapOf("session-1" to 5L, "performed-1" to 4L, "set-1" to 3L), setActivityOperation(draft,
            mapOf("session-1" to 5, "performed-1" to 4, "set-1" to 3)).expectedRevisions)
        assertEquals(4L, draft.expectedRevisions["session-1"])
    }

    @Test
    fun catalogAndNestedWritesUseTheirOwnRevisionScopes() {
        val session = activityTestSession()
        assertEquals(mapOf("session-1" to 4L), sessionExerciseActivityOperation(session, "bench").expectedRevisions)
        assertEquals(mapOf("session-1" to 4L, "performed-1" to 3L), deleteSessionExerciseActivityOperation(session, session.exercises.single()).expectedRevisions)
        val exercise = ActivityExerciseDraft("bench", 2, " Bench ", " Barbell ", "", "total")
        assertEquals(mapOf("bench" to 2L), exerciseActivityOperation(exercise).expectedRevisions)
        assertEquals("Bench", exerciseActivityOperation(exercise).payload.getValue("name").jsonPrimitive.content)
        val edit = activitySessionDraft(session)
        assertEquals(mapOf("session-1" to 4L), sessionActivityOperation(edit, "2026-10-17", now).expectedRevisions)
        assertEquals(mapOf("session-1" to 4L), deleteSessionActivityOperation(session).expectedRevisions)
    }

    @Test
    fun receiptRejectsMismatchedSessionIdSetValuesAndUnadvancedParents() {
        val session = activityTestSession()
        val exercise = session.exercises.single()
        val operation = setActivityOperation(activitySetDraft(session, exercise, exercise.sets.single()).copy(repetitions = "10"))
        val saved = session.copy(revision = 5, exercises = listOf(exercise.copy(revision = 4, sets = listOf(exercise.sets.single().copy(revision = 3, repetitions = 10)))))
        val receipt = activityTestReceipt(operation.kind, saved, "performed-1", "set-1")
        val entry = activityTestSubmission(operation, now)
        validateActivitySubmissionReceipt(entry, receipt.encodeToByteArray())
        assertFailsWith<IllegalArgumentException> { validateActivitySubmissionReceipt(entry, receipt.replace("\"revision\":5", "\"revision\":4").encodeToByteArray()) }
        assertFailsWith<IllegalArgumentException> { validateActivitySubmissionReceipt(entry, receipt.replace("\"repetitions\":10", "\"repetitions\":9").encodeToByteArray()) }
        assertFailsWith<IllegalArgumentException> { validateActivitySubmissionReceipt(entry, receipt.replace("session-1", "other-session").encodeToByteArray()) }
    }

    @Test
    fun sessionReceiptBindsKindDateTimezoneAndExactProposedValues() {
        val session = activityTestSession()
        val operation = sessionActivityOperation(activitySessionDraft(session).copy(title = "New title"), "2026-10-17", now)
        val receipt = activityTestReceipt(operation.kind, session.copy(revision = 5, title = "New title"))
        val entry = activityTestSubmission(operation, now)
        validateActivitySubmissionReceipt(entry, receipt.encodeToByteArray())
        assertFailsWith<IllegalArgumentException> { validateActivitySubmissionReceipt(entry, receipt.replace("New title", "Wrong title").encodeToByteArray()) }
        assertFailsWith<IllegalArgumentException> { validateActivitySubmissionReceipt(entry, receipt.replace("activity.session.update", "activity.session.add").encodeToByteArray()) }
    }

    @Test
    fun manualStepsPreserveZeroPinRevisionAndValidateSetAndClearReceipts() {
        val draft = ActivityStepsDraft("2026-10-17", "0", "override-1", 2)
        val operation = stepsActivityOperation(draft, "2026-10-17")
        assertEquals(mapOf("override-1" to 2L), operation.expectedRevisions)
        assertEquals(0, operation.payload.getValue("value").jsonPrimitive.int)
        val entry = activityTestSubmission(operation, now).copy(endpoint = PERSONAL_OPERATIONS, lookup = PERSONAL_LOOKUP + "key")
        val receipt = """{"operation_id":"operation-1","kind":"personal.steps.set","date":"2026-10-17","override":{"id":"override-1","revision":3,"value":0,"active":true}}"""
        assertTrue(validateActivityStepsSubmissionReceipt(entry, receipt.encodeToByteArray()))
        assertFailsWith<IllegalArgumentException> { validateActivityStepsSubmissionReceipt(entry, receipt.replace("\"revision\":3", "\"revision\":2").encodeToByteArray()) }
        val clear = stepsActivityOperation(draft, "2026-10-17", clear = true)
        assertTrue(validateActivityStepsSubmissionReceipt(entry.copy(body = clear.bytes()), receipt.replace("steps.set", "steps.clear").replace("\"value\":0", "\"value\":null").replace("\"active\":true", "\"active\":false").encodeToByteArray()))
        assertFailsWith<ActivityValidationException> { stepsActivityOperation(draft.copy(value = "1000001"), "2026-10-17") }
    }
}

internal fun activityTestSession() = ActivitySession("session-1", 4, "strength", "Strength", "2026-10-17", "Europe/Moscow", "2026-10-17T10:00:00Z", "45", null, "",
    listOf(SessionExercise("performed-1", 3, "bench", 0, ExerciseSnapshot("Bench", "Dumbbell", "", "per_implement"), "",
        listOf(ActivitySet("set-1", 2, 0, "working", 8, "20", "kg", 2, "")))))

internal fun activityTestSessionJson(session: ActivitySession): JsonObject = buildJsonObject {
    put("id", session.id); put("revision", session.revision); put("kind", session.kind); put("title", session.title?.let(::JsonPrimitive) ?: JsonNull)
    put("date", session.date); put("timezone", session.timezone); put("started_at", session.startedAt?.let(::JsonPrimitive) ?: JsonNull)
    put("duration_minutes", session.durationMinutes?.let(::JsonPrimitive) ?: JsonNull); put("distance_km", session.distanceKm?.let(::JsonPrimitive) ?: JsonNull)
    put("note", session.note?.let(::JsonPrimitive) ?: JsonNull)
    put("exercises", JsonArray(session.exercises.map { exercise -> buildJsonObject {
        put("id", exercise.id); put("revision", exercise.revision); put("exercise_id", exercise.exerciseId); put("position", exercise.position)
        put("snapshot", buildJsonObject { put("name", exercise.snapshot.name); put("equipment", exercise.snapshot.equipment.orEmpty()); put("variant", exercise.snapshot.variant.orEmpty()); put("load_basis", exercise.snapshot.loadBasis) })
        put("note", exercise.note.orEmpty()); put("sets", JsonArray(exercise.sets.map { set -> buildJsonObject {
            put("id", set.id); put("revision", set.revision); put("position", set.position); put("kind", set.kind)
            put("repetitions", set.repetitions?.let(::JsonPrimitive) ?: JsonNull); put("load_value", set.loadValue?.let(::JsonPrimitive) ?: JsonNull)
            put("load_unit", set.loadUnit); put("implement_count", set.implementCount); put("note", set.note.orEmpty())
        } }))
    } }))
}

internal fun activityTestReceipt(kind: String, session: ActivitySession, exerciseId: String? = null, setId: String? = null, deletedId: String? = null): String = buildJsonObject {
    put("operation_id", "operation-${session.revision}"); put("kind", kind); put("session_id", session.id)
    if (kind != "activity.session.delete") put("session", activityTestSessionJson(session))
    exerciseId?.let { put("session_exercise_id", it) }; setId?.let { put("set_id", it) }; deletedId?.let { put("deleted_id", it) }
}.toString()

private fun activityTestSubmission(operation: ActivityOperation, now: Long) = Submission(AccountNamespace("synthetic", "https://synthetic.example", "owner"), "key", ACTIVITY_OPERATIONS,
    ACTIVITY_LOOKUP + "key", "POST", operation.bytes(), "activity", operation.expectedRevisions.mapValues { it.value.toString() }, "activity/session", SubmissionState.RESOLVED, now)
