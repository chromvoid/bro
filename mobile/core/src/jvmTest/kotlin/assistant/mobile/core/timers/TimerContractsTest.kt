package assistant.mobile.core.timers

import assistant.mobile.core.featureRecord
import assistant.mobile.core.journal.Submission
import assistant.mobile.core.journal.SubmissionState
import kotlinx.serialization.json.*
import kotlin.test.*
import kotlin.time.Instant

class TimerContractsTest {
    @Test
    fun serverReceiptsStaySparseAndValidateWithoutInventingLiveFields() {
        val fixture = timerFixture("server-contract.json")
        val cases = listOf(
            "start" to TimerOperation("timer.start", buildJsonObject { put("duration_seconds", 90); put("label", "Synthetic tea") }),
            "cancel" to cancelTimerOperation(decodeTimers(fixture.getValue("running").bytes()).single()),
            "create" to TimerOperation("reminder.create", buildJsonObject { put("at", "2026-10-17T13:00:00Z"); put("text", "Synthetic task"); put("task_id", "task-milk") }),
            "remove" to cancelReminderOperation(decodeReminders(fixture.getValue("reminders").bytes()).single()),
        )
        for ((key, operation) in cases) {
            val response = fixture.getValue(key).bytes()
            validateTimerSubmissionReceipt(timerEntry(operation), response)
            assertEquals(operation.kind, decodeTimerReceipt(response).kind)
        }
        assertFailsWith<IllegalArgumentException> {
            val wrong = TimerOperation("timer.cancel", buildJsonObject { put("timer_ids", JsonArray(listOf(JsonPrimitive("other")))) }, mapOf("other" to 1))
            validateTimerSubmissionReceipt(timerEntry(wrong), fixture.getValue("cancel").bytes())
        }
    }

    @Test
    fun decodersRejectBadRevisionsDuplicatesAndNonFiniteRemaining() {
        val fixture = timerFixture("web-contract.json")
        val valid = fixture.getValue("running").jsonObject.getValue("timers").jsonArray.single().jsonObject
        fun envelope(vararg items: JsonObject) = buildJsonObject { put("timers", JsonArray(items.toList())) }.bytes()
        for (revision in listOf(JsonPrimitive(0), JsonPrimitive(-1), JsonPrimitive("bad"))) {
            val invalid = JsonObject(valid + ("revision" to revision))
            assertFails { decodeTimers(envelope(invalid)) }
        }
        assertFails { decodeTimers(envelope(valid, valid)) }
        val nonFinite = JsonObject(valid + ("remaining_seconds" to JsonPrimitive("NaN")))
        assertFails { decodeTimers(envelope(nonFinite)) }
    }

    @Test
    fun countdownUsesServerRemainderAndMonotonicElapsedTime() {
        val timer = decodeTimers(timerFixture("web-contract.json").getValue("running").bytes()).single()
        assertEquals(60, remaining(timer, 1_000, 1_000))
        assertEquals(59, remaining(timer, 1_000, 1_500))
        assertEquals(1, remaining(timer, 1_000, 60_000))
        assertEquals(0, remaining(timer, 1_000, 61_000))
        assertEquals(60, remaining(timer, 1_000, 500))
        assertEquals("0:00", timerClock(0))
        assertEquals("1:05", timerClock(65))
        assertEquals("1:01:01", timerClock(3661))
    }

    @Test
    fun operationsEnforceWebDurationLimitsAndBindTaskReminder() {
        assertEquals(86_400, startTimerOperation(1440, "  чай  ").payload.getValue("duration_seconds").jsonPrimitive.int)
        for (minutes in listOf(0, -1, 1441)) assertFails { startTimerOperation(minutes, "") }
        assertFails { startTimerOperation(5, "x".repeat(61)) }
        val draft = ReminderDraft("  Молоко  ", "2026-10-17T16:00", "task-milk")
        val create = createReminderOperation(draft, "Europe/Moscow")
        assertEquals("2026-10-17T13:00:00Z", create.payload.getValue("at").jsonPrimitive.content)
        assertEquals("Молоко", create.payload.getValue("text").jsonPrimitive.content)
        assertEquals("task-milk", create.payload.getValue("task_id").jsonPrimitive.content)
        assertEquals("2026-10-17T16:00", reminderLocalInput("2026-10-17T13:00:00Z", "Europe/Moscow"))
        assertEquals("2026-10-17T13:00:12.345Z", reminderInstant(reminderLocalInput("2026-10-17T13:00:12.345Z", "Europe/Moscow"), "Europe/Moscow"))
        val cancel = cancelReminderOperation(Reminder("reminder", "Молоко", "2026-10-17T13:00:00Z", 4, "task-milk"))
        assertEquals(mapOf("reminder" to 4L), cancel.expectedRevisions)
    }

    @Test
    fun selectedPresetKeepsItsOccurrenceAcrossAutumnClockFoldUntilDateChanges() {
        val zone = "Europe/Belgrade"
        val now = Instant.parse("2026-10-25T00:30:00Z")
        val hour = reminderPresets(now.toEpochMilliseconds(), zone).first { it.key == "hour" }
        assertEquals("2026-10-25T01:30:00Z", hour.at)
        val selected = ReminderDraft(text = "Через час", at = reminderLocalInput(hour.at, zone),
            taskId = "task-milk", exactAt = hour.at)
        assertEquals("2026-10-25T02:30", selected.at)
        assertEquals("2026-10-25T00:30:00Z", reminderInstant(selected.at, zone))
        val selectedOperation = createReminderOperation(selected, zone)
        assertEquals(hour.at, selectedOperation.payload.getValue("at").jsonPrimitive.content)
        assertEquals("task-milk", selectedOperation.payload.getValue("task_id").jsonPrimitive.content)
        val manuallyChanged = selected.copy(at = "2026-10-26T02:30")
        assertEquals("2026-10-26T01:30:00Z", createReminderOperation(manuallyChanged, zone).payload.getValue("at").jsonPrimitive.content)
    }
}

internal fun timerFixture(name: String): JsonObject = TimerContractsTest::class.java.getResourceAsStream("/timers/$name")!!.use {
    timerObject(it.readBytes())
}
internal fun JsonElement.bytes() = toString().encodeToByteArray()
private fun timerEntry(operation: TimerOperation) = Submission(featureRecord().namespace,
    "11111111-1111-4111-8111-111111111111", TIMER_OPERATIONS, TIMER_LOOKUP + "11111111-1111-4111-8111-111111111111",
    "POST", operation.bytes(), "timers", operation.expectedRevisions.mapValues { it.value.toString() }, null,
    SubmissionState.RESOLVED, 0)
