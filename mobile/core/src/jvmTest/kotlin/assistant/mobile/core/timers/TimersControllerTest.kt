package assistant.mobile.core.timers

import assistant.mobile.core.*
import assistant.mobile.core.journal.*
import assistant.mobile.core.tasks.Task
import io.ktor.client.engine.mock.*
import io.ktor.client.request.HttpRequestData
import io.ktor.http.*
import java.io.File
import java.io.IOException
import java.nio.file.Files
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.*
import kotlin.test.*
import kotlin.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
class TimersControllerTest {
    @Test
    fun unknownCommittedStartRecoversFromDurableOriginalKeyWithoutAnotherPost() = runTest {
        var fixture = TimerControllerFixture(this)
        try {
            fixture.start()
            fixture.loseNextReceipt = true
            assertNull(fixture.controller.start(5, "Чай"))
            val pending = fixture.controller.state.value!!.pending.single()
            assertEquals(SubmissionState.UNCERTAIN, pending.state)
            assertEquals(fixture.posts.single().headers["Idempotency-Key"], pending.key)
            assertEquals(1, fixture.serverTimers.size)
            fixture = fixture.restart()
            fixture.start()
            assertEquals(1, fixture.posts.size)
            assertEquals(1, fixture.serverWrites)
            assertEquals(1, fixture.controller.state.value!!.running.data!!.size)
            assertTrue(fixture.controller.state.value!!.pending.isEmpty())
            assertTrue(fixture.requests.any { it.url.encodedPath == TIMER_LOOKUP + pending.key })
            assertTrue(fixture.store.submissions(fixture.namespace).isEmpty())
        } finally { fixture.close() }
    }

    @Test
    fun unknownCancellationUsesOnlyLookupAndThenAuthoritativeRead() = runTest {
        val fixture = TimerControllerFixture(this)
        try {
            fixture.start()
            fixture.controller.start(10)
            fixture.loseNextReceipt = true
            assertNull(fixture.controller.cancel(fixture.controller.state.value!!.running.data!!.single()))
            val key = fixture.controller.state.value!!.pending.single().key
            fixture.controller.check(key)
            fixture.controller.check(key)
            assertEquals(2, fixture.posts.size)
            assertEquals(2, fixture.serverWrites)
            assertTrue(fixture.controller.state.value!!.running.data!!.isEmpty())
            assertEquals(1, fixture.requests.count { it.url.encodedPath == TIMER_LOOKUP + key })
        } finally { fixture.close() }
    }

    @Test
    fun unknownReminderCreationRecoversOriginalKeyAndConsumesOnlyItsDurableDraft() = runTest {
        var fixture = TimerControllerFixture(this)
        try {
            fixture.start()
            fixture.controller.beginReminder(Task("task-milk", "Купить молоко", "open", 1))
            fixture.controller.setReminderAt("2026-10-17T15:15:12.345Z")
            val original = fixture.controller.state.value!!.draft!!
            fixture.loseNextReceipt = true
            assertNull(fixture.controller.submitReminder())
            val key = fixture.controller.state.value!!.pending.single().key
            assertEquals(original, fixture.controller.state.value!!.draft)
            assertEquals(original, fixture.savedDraft())
            fixture = fixture.restart()
            fixture.start()
            assertEquals(1, fixture.posts.size)
            assertEquals(1, fixture.serverWrites)
            assertEquals(1, fixture.requests.count { it.url.encodedPath == TIMER_LOOKUP + key })
            assertEquals("2026-10-17T15:15:12.345Z", fixture.controller.state.value!!.remindersForTask("task-milk").last().dueAt)
            assertTrue(fixture.controller.state.value!!.pending.isEmpty())
            assertNull(fixture.savedDraft())
            assertNull(fixture.controller.state.value!!.draft)
        } finally { fixture.close() }
    }

    @Test
    fun cancellationRevisionConflictRereadsAndKeepsUnrelatedCreationDraft() = runTest {
        val fixture = TimerControllerFixture(this)
        try {
            fixture.start()
            fixture.controller.beginReminder()
            fixture.controller.updateDraft(fixture.controller.state.value!!.draft!!.copy(text = "Не потерять черновик"))
            val draft = fixture.controller.state.value!!.draft!!
            val stale = fixture.controller.state.value!!.reminders.data!!.single()
            fixture.serverReminders[stale.id] = JsonObject(fixture.serverReminders.getValue(stale.id) + ("revision" to JsonPrimitive(3)))
            assertNull(fixture.controller.cancelReminder(stale))
            assertEquals(GeneratedStrings.Errors.conflict, fixture.controller.state.value!!.error)
            assertEquals(draft, fixture.controller.state.value!!.draft)
            assertEquals(draft, fixture.savedDraft())
            val current = fixture.controller.state.value!!.reminders.data!!.single()
            assertEquals(3L, current.revision)
            fixture.controller.refresh()
            assertEquals(1, fixture.posts.size)
            assertNotNull(fixture.controller.cancelReminder(current))
            assertEquals(listOf("reminder.cancel", "reminder.cancel"), fixture.posts.map { timerBody(it).getValue("kind").jsonPrimitive.content })
            assertEquals("3", timerBody(fixture.posts.last()).getValue("expected_revisions").jsonObject.getValue(stale.id).jsonPrimitive.content)
            assertEquals(draft, fixture.savedDraft())
        } finally { fixture.close() }
    }

}

private fun timerBody(request: HttpRequestData) = timerObject(featureBody(request.body))

@OptIn(ExperimentalCoroutinesApi::class)
private class TimerControllerFixture(private val test: TestScope,
    private val directory: File = Files.createTempDirectory("native-timers-journal").toFile()) {
    private val database = openJournalDatabase(directory.resolve("journal.db"))
    private val durable = RoomJournalStore(database)
    private val durableWrites = mutableMapOf<String, Submission>()
    val store = object : JournalStore by durable {
        override suspend fun insertSubmission(entry: Submission) { durable.insertSubmission(entry); durableWrites[entry.key] = entry }
        override suspend fun saveSubmission(entry: Submission) { durable.saveSubmission(entry); durableWrites[entry.key] = entry }
        override suspend fun deleteSubmission(namespace: AccountNamespace, key: String) { durable.deleteSubmission(namespace, key); durableWrites.remove(key) }
    }
    var epochNow = Instant.parse("2026-10-17T12:00:00Z").toEpochMilliseconds()
    var timerNow = 0L
    private val record = featureRecord().let { it.copy(identity = it.identity.copy(timezone = "Europe/Moscow", scopes = listOf("timers.read", "timers.write"))) }
    val namespace = record.namespace
    val sessions = SessionManager("synthetic", FEATURE_ORIGIN, FeatureSessionSecrets(record), store::clearAccount, { epochNow })
    val requests = mutableListOf<HttpRequestData>()
    val posts get() = requests.filter { it.method == HttpMethod.Post }
    val runningReads get() = requests.count { it.url.encodedPath == "/v1/timers/running" }
    val serverTimers = linkedMapOf<String, JsonObject>()
    val serverReminders = linkedMapOf("r1" to buildJsonObject {
        put("id", "r1"); put("text", "Купить молоко"); put("due_at", "2026-10-17T14:00:00Z"); put("revision", 2); put("task_id", "task-milk")
    })
    private val receipts = mutableMapOf<String, String>()
    var loseNextReceipt = false
    var runningReadGate: CompletableDeferred<Unit>? = null
    var serverWrites = 0
    val http = NativeHttpClient(MockEngine(MockEngineConfig().apply {
        dispatcher = StandardTestDispatcher(test.testScheduler)
        addHandler { request ->
            requests += request
            val path = request.url.encodedPath
            when {
                path == "/v1/timers/running" -> {
                    runningReadGate?.await()
                    respond(buildJsonObject { put("timers", JsonArray(serverTimers.values.toList())) }.toString())
                }
                path == "/v1/timers/reminders" -> respond(buildJsonObject { put("reminders", JsonArray(serverReminders.values.toList())) }.toString())
                path.startsWith(TIMER_LOOKUP) -> receipts[path.removePrefix(TIMER_LOOKUP)]?.let { respond(it) }
                    ?: respond("""{"error":"not_found"}""", HttpStatusCode.NotFound)
                path == TIMER_OPERATIONS -> {
                    val key = assertNotNull(request.headers["Idempotency-Key"])
                    val admitted = assertNotNull(durableWrites[key], "Journal must persist before dispatch")
                    assertEquals(SubmissionState.SENDING, admitted.state)
                    val body = timerBody(request)
                    assertEquals(body, timerObject(admitted.body))
                    assertFalse(receipts.containsKey(key), "An admitted key must never be posted twice")
                    val kind = body.getValue("kind").jsonPrimitive.content
                    val payload = body.getValue("payload").jsonObject
                    val conflict = when (kind) {
                        "timer.cancel" -> payload.getValue("timer_ids").jsonArray.any {
                            val id = it.jsonPrimitive.content
                            serverTimers[id]?.get("revision") != body.getValue("expected_revisions").jsonObject[id]
                        }
                        "reminder.cancel" -> payload.getValue("reminder_ids").jsonArray.any {
                            val id = it.jsonPrimitive.content
                            serverReminders[id]?.get("revision") != body.getValue("expected_revisions").jsonObject[id]
                        }
                        else -> false
                    }
                    if (conflict) {
                        respond("""{"error":"conflict"}""", HttpStatusCode.Conflict)
                    } else {
                        val items: List<JsonObject> = when (kind) {
                            "timer.start" -> {
                                val id = "timer-${serverWrites + 1}"
                                val duration = payload.getValue("duration_seconds")
                                val timer = buildJsonObject {
                                    put("id", id); put("label", payload["label"] ?: JsonNull); put("duration_seconds", duration)
                                    put("due_at", Instant.fromEpochMilliseconds(epochNow + duration.jsonPrimitive.long * 1000).toString())
                                }
                                serverTimers[id] = JsonObject(timer + mapOf("revision" to JsonPrimitive(1), "remaining_seconds" to duration))
                                listOf(timer)
                            }
                            "timer.cancel" -> payload.getValue("timer_ids").jsonArray.map {
                                val id = it.jsonPrimitive.content
                                val timer = serverTimers.getValue(id)
                                assertEquals(timer.getValue("revision"), body.getValue("expected_revisions").jsonObject.getValue(id))
                                serverTimers.remove(id)
                                JsonObject(timer - setOf("revision", "remaining_seconds", "due_at"))
                            }
                            "reminder.create" -> {
                                val id = "reminder-${serverWrites + 1}"
                                val reminder = buildJsonObject {
                                    put("id", id); put("text", payload.getValue("text")); put("due_at", payload.getValue("at")); put("task_id", payload["task_id"] ?: JsonNull)
                                }
                                serverReminders[id] = JsonObject(reminder + ("revision" to JsonPrimitive(1)))
                                listOf(reminder)
                            }
                            "reminder.cancel" -> payload.getValue("reminder_ids").jsonArray.map {
                                val id = it.jsonPrimitive.content
                                val reminder = serverReminders.getValue(id)
                                assertEquals(reminder.getValue("revision"), body.getValue("expected_revisions").jsonObject.getValue(id))
                                serverReminders.remove(id)
                                JsonObject(reminder - "revision")
                            }
                            else -> error("Unexpected timer operation: $kind")
                        }
                        serverWrites++
                        val receipt = buildJsonObject {
                            put("operation_id", "op-$serverWrites"); put("kind", kind)
                            put(if (kind.startsWith("timer.")) "timers" else "reminders", JsonArray(items))
                        }.toString()
                        receipts[key] = receipt
                        if (loseNextReceipt) { loseNextReceipt = false; throw IOException("synthetic receipt lost after commit") }
                        respond(receipt)
                    }
                }
                else -> error("Unexpected timer request: $path")
            }
        }
    }), FEATURE_ORIGIN, sessions, { epochNow })
    private val submissions = SubmissionCoordinator(store, http, sessions, { epochNow }, SubmissionReceiptValidator(::validateTimerSubmissionReceipt))
    val controller = TimersController(test.backgroundScope, sessions, http, submissions, AccountDrafts(store, sessions, { epochNow }), { epochNow }, { timerNow })

    suspend fun start() {
        if (sessions.lease() == null) sessions.restore()
        controller.activate()
        controller.state.first { it?.draftReady == true && it.running.data != null && it.reminders.data != null }
        controller.refresh()
    }
    suspend fun savedDraft(): ReminderDraft? = store.draft(namespace, "timers/reminder")?.let { NativeJson.decodeFromString<ReminderDraft>(it.json.decodeToString()) }
    fun restart(): TimerControllerFixture {
        controller.deactivate(); test.runCurrent(); http.close(); database.close()
        return TimerControllerFixture(test, directory).also {
            it.requests.addAll(requests); it.serverTimers.putAll(serverTimers)
            it.serverReminders.clear(); it.serverReminders.putAll(serverReminders)
            it.receipts.putAll(receipts); it.serverWrites = serverWrites; it.epochNow = epochNow; it.timerNow = timerNow
        }
    }
    fun close() { controller.deactivate(); test.runCurrent(); http.close(); database.close(); directory.deleteRecursively() }
}
