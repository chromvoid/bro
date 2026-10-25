package assistant.mobile.core.tasks

import assistant.mobile.core.AccountDrafts
import assistant.mobile.core.FEATURE_ORIGIN
import assistant.mobile.core.FeatureSessionSecrets
import assistant.mobile.core.NativeHttpClient
import assistant.mobile.core.NativeJson
import assistant.mobile.core.SessionManager
import assistant.mobile.core.featureBody
import assistant.mobile.core.featureRecord
import assistant.mobile.core.journal.JournalStore
import assistant.mobile.core.journal.Submission
import assistant.mobile.core.journal.RoomJournalStore
import assistant.mobile.core.journal.SubmissionCoordinator
import assistant.mobile.core.journal.SubmissionReceiptValidator
import assistant.mobile.core.journal.SubmissionState
import assistant.mobile.core.journal.openJournalDatabase
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockEngineConfig
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import java.io.File
import java.io.IOException
import java.nio.file.Files
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
class TasksControllerTest {
    @Test
    fun savePersistsOriginalBodyAndKeyAndConsumesDraftAfterAuthoritativeRead() = runTest {
        val fixture = TasksControllerFixture(this)
        try {
            fixture.start()
            fixture.editTitle("  Купить продукты  ")
            val receipt = assertNotNull(fixture.controller.submitEdit())
            assertEquals("task.update", receipt.kind)
            assertEquals(3L, receipt.tasks.single().revision)
            val post = fixture.posts.single()
            val body = taskRequestBody(post)
            assertEquals("2", body.getValue("expected_revisions").jsonObject.getValue("t1").jsonPrimitive.content)
            assertEquals("Купить продукты", body.getValue("payload").jsonObject.getValue("changes").jsonObject.getValue("title").jsonPrimitive.content)
            assertNotNull(post.headers["Idempotency-Key"])
            assertEquals(1, fixture.serverWrites)
            assertEquals("Купить продукты", fixture.controller.state.value!!.page.data!!.items.single().title)
            assertNull(fixture.controller.state.value!!.draft)
            assertNull(fixture.store.draft(fixture.namespace, "task:t1"))
            assertTrue(fixture.store.submissions(fixture.namespace).isEmpty())
            val postIndex = fixture.requests.indexOf(post)
            assertTrue(fixture.requests.drop(postIndex + 1).any { it.url.encodedPath == "/v1/tasks/items" })
        } finally { fixture.close() }
    }

    @Test
    fun revisionConflictKeepsExactDraftAndOnlyExplicitSaveUsesFreshRevisionAndNewKey() = runTest {
        val fixture = TasksControllerFixture(this)
        try {
            fixture.start()
            fixture.editTitle("  Купить продукты  ")
            val draft = assertNotNull(fixture.controller.state.value!!.draft)
            fixture.rejectNextEdit = true
            assertNull(fixture.controller.submitEdit())
            val state = fixture.controller.state.value!!
            assertTrue(state.conflict)
            assertEquals(draft, state.draft)
            assertEquals(2L, state.draft!!.revision)
            assertEquals(3L, state.task.data!!.revision)
            assertEquals("Чужая правка", state.task.data!!.title)
            assertEquals(draft, fixture.savedDraft())
            assertEquals(listOf(HttpMethod.Post, HttpMethod.Get), fixture.operationRequests.map { it.method })
            assertEquals(0, fixture.serverWrites)
            fixture.controller.refresh()
            assertEquals(1, fixture.posts.size)
            assertEquals(draft, fixture.controller.state.value!!.draft)
            assertNotNull(fixture.controller.submitEdit())
            assertEquals(2, fixture.posts.size)
            assertEquals("3", taskRequestBody(fixture.posts.last()).getValue("expected_revisions").jsonObject.getValue("t1").jsonPrimitive.content)
            assertTrue(fixture.posts.first().headers["Idempotency-Key"] != fixture.posts.last().headers["Idempotency-Key"])
            assertEquals(1, fixture.serverWrites)
            assertEquals("Купить продукты", fixture.serverTitle)
            assertNull(fixture.controller.state.value!!.draft)
            assertFalse(fixture.controller.state.value!!.conflict)
        } finally { fixture.close() }
    }

    @Test
    fun committedWriteWithLostResponseLooksUpOriginalKeyAndNeverPostsAgain() = runTest {
        val fixture = TasksControllerFixture(this)
        try {
            fixture.start()
            fixture.editTitle("Купить продукты")
            fixture.loseNextReceipt = true
            assertNull(fixture.controller.submitEdit())
            val pending = fixture.controller.state.value!!.pending.single()
            assertEquals(SubmissionState.UNCERTAIN, pending.state)
            assertEquals("2", pending.revisions["t1"])
            assertEquals(fixture.posts.single().headers["Idempotency-Key"], pending.key)
            assertEquals(fixture.posts.single().let(::taskRequestBody), taskObject(pending.body))
            assertNotNull(fixture.controller.state.value!!.draft)
            assertEquals(1, fixture.serverWrites)
            fixture.controller.check(pending.key)
            fixture.controller.check(pending.key)
            assertEquals(1, fixture.posts.size)
            assertEquals(1, fixture.serverWrites)
            assertEquals(listOf(TASK_LOOKUP + pending.key), fixture.operationRequests.filter { it.method == HttpMethod.Get }.map { it.url.encodedPath })
            assertEquals("op-1", fixture.controller.state.value!!.receipt!!.operationId)
            assertEquals("Купить продукты", fixture.controller.state.value!!.page.data!!.items.single().title)
            assertTrue(fixture.controller.state.value!!.pending.isEmpty())
            assertNull(fixture.controller.state.value!!.draft)
            assertTrue(fixture.store.submissions(fixture.namespace).isEmpty())
        } finally { fixture.close() }
    }

    @Test
    fun conflictDraftSurvivesCloseReopenAndControllerRecreationThenExplicitlyRetriesLatestRevision() = runTest {
        var fixture = TasksControllerFixture(this)
        try {
            fixture.start()
            fixture.editTitle("  Мой неизменённый черновик  ")
            fixture.rejectNextEdit = true
            fixture.controller.submitEdit()
            val draft = assertNotNull(fixture.controller.state.value!!.draft)
            fixture.controller.closeTask()
            runCurrent()
            fixture.controller.openTask("t1")
            assertEquals(draft, fixture.controller.state.value!!.draft)
            assertTrue(fixture.controller.state.value!!.conflict)
            fixture = fixture.restartProcess()
            fixture.start()
            fixture.controller.openTask("t1")
            assertEquals(draft, fixture.controller.state.value!!.draft)
            assertEquals(draft, fixture.savedDraft())
            assertEquals(3L, fixture.controller.state.value!!.task.data!!.revision)
            assertTrue(fixture.controller.state.value!!.conflict)
            assertEquals(1, fixture.posts.size)
            assertNotNull(fixture.controller.submitEdit())
            assertEquals("3", taskRequestBody(fixture.posts.last()).getValue("expected_revisions").jsonObject.getValue("t1").jsonPrimitive.content)
            assertEquals("Мой неизменённый черновик", fixture.serverTitle)
            assertEquals(2, fixture.posts.size)
            assertNull(fixture.store.draft(fixture.namespace, "task:t1"))
        } finally { fixture.close() }
    }

    @Test
    fun processStartupRecoversCommittedOriginalKeyFromRoomWithoutAnotherPost() = runTest {
        var fixture = TasksControllerFixture(this)
        try {
            fixture.start()
            fixture.editTitle("Купить продукты")
            fixture.loseNextReceipt = true
            fixture.controller.submitEdit()
            val pending = fixture.controller.state.value!!.pending.single()
            assertNotNull(fixture.savedDraft())
            fixture = fixture.restartProcess()
            fixture.start()
            assertEquals("op-1", fixture.controller.state.value!!.receipt!!.operationId)
            assertEquals(1, fixture.posts.size)
            assertEquals(1, fixture.serverWrites)
            assertEquals(listOf(TASK_LOOKUP + pending.key), fixture.operationRequests.filter { it.method == HttpMethod.Get }.map { it.url.encodedPath })
            assertEquals("Купить продукты", fixture.controller.state.value!!.page.data!!.items.single().title)
            assertTrue(fixture.store.submissions(fixture.namespace).isEmpty())
            assertNull(fixture.store.draft(fixture.namespace, "task:t1"))
            fixture.controller.openTask("t1")
            assertEquals("Купить продукты", fixture.controller.state.value!!.draft!!.fields.title)
            assertEquals(3L, fixture.controller.state.value!!.draft!!.revision)
            assertFalse(fixture.controller.state.value!!.conflict)
        } finally { fixture.close() }
    }

}

private const val TASK_CHANNEL = "22222222-2222-4222-8222-222222222222"
private fun taskRequestBody(request: HttpRequestData): JsonObject = taskObject(featureBody(request.body))

@OptIn(ExperimentalCoroutinesApi::class)
private class TasksControllerFixture(private val test: TestScope,
    private val directory: File = Files.createTempDirectory("native-tasks-journal").toFile()) {
    private val database = openJournalDatabase(directory.resolve("journal.db"))
    private val durable = RoomJournalStore(database)
    // Do not suspend Room IO inside the HTTP mock's virtual request timeout. This evidence
    // appears only after the real durable write completes; restart tests read Room itself.
    private val durableWrites = mutableMapOf<String, Submission>()
    val store = object : JournalStore by durable {
        override suspend fun insertSubmission(entry: Submission) {
            durable.insertSubmission(entry)
            durableWrites[entry.key] = entry
        }
        override suspend fun saveSubmission(entry: Submission) {
            durable.saveSubmission(entry)
            durableWrites[entry.key] = entry
        }
        override suspend fun deleteSubmission(namespace: assistant.mobile.core.AccountNamespace, key: String) {
            durable.deleteSubmission(namespace, key)
            durableWrites.remove(key)
        }
    }
    private val now = Instant.parse("2026-10-17T12:00:00Z").toEpochMilliseconds()
    private val record = featureRecord().let { it.copy(identity = it.identity.copy(
        timezone = "Europe/Moscow", scopes = listOf("tasks.read", "tasks.write"))) }
    val namespace = record.namespace
    val sessions = SessionManager("synthetic", FEATURE_ORIGIN, FeatureSessionSecrets(record), store::clearAccount, { now })
    val requests = mutableListOf<HttpRequestData>()
    val operationRequests get() = requests.filter { it.url.encodedPath.startsWith(TASK_OPERATIONS) }
    val posts get() = operationRequests.filter { it.method == HttpMethod.Post }
    var serverTitle = "Купить молоко"
    var serverRevision = 2L
    var serverListId = "list-1"
    var serverWrites = 0
    var rejectNextEdit = false
    var loseNextReceipt = false
    var rejectNextBoardCursor = false
    private var boardGeneration = 1
    private val receipts = mutableMapOf<String, String>()
    val http = NativeHttpClient(MockEngine(MockEngineConfig().apply {
        dispatcher = StandardTestDispatcher(test.testScheduler)
        addHandler { request ->
            requests += request
            val path = request.url.encodedPath
            when {
                path == "/v1/tasks/lists" -> respond(lists().toString())
                path == "/v1/spaces" -> respond("""{"spaces":[]}""")
                path == "/v1/tasks/board" -> respond(board().toString())
                path == "/v1/tasks/items" -> {
                    if (request.url.parameters["cursor"] != null && rejectNextBoardCursor) {
                        rejectNextBoardCursor = false
                        boardGeneration++
                        respond("""{"error":"cursor_stale","message":"Список изменился"}""", HttpStatusCode.Conflict)
                    } else respond(page(if (request.url.parameters["parent_id"] == "t1") emptyList() else listOf(task())).toString())
                }
                path == "/v1/tasks/items/t1" -> respond(task().toString())
                path == "/v1/tasks/items/t1/history" -> respond("""{"items":[],"people":[],"next_cursor":null}""")
                path == "/v1/tasks/items/t1/discussion" -> respond("""{"task_id":"t1","channel_id":"$TASK_CHANNEL"}""")
                path == "/v1/channels/$TASK_CHANNEL/messages" -> respond("""{"channel":{"id":"$TASK_CHANNEL"},"messages":[],"next_cursor":null}""")
                path.startsWith(TASK_LOOKUP) -> {
                    val receipt = receipts[path.removePrefix(TASK_LOOKUP)]
                    if (receipt == null) respond("""{"error":"not_found"}""", HttpStatusCode.NotFound) else respond(receipt)
                }
                path == TASK_OPERATIONS -> {
                    assertEquals(HttpMethod.Post, request.method)
                    val body = taskRequestBody(request)
                    assertEquals("task.update", body.getValue("kind").jsonPrimitive.content)
                    val payload = body.getValue("payload").jsonObject
                    assertEquals("t1", payload.getValue("task_id").jsonPrimitive.content)
                    assertEquals(serverRevision.toString(), body.getValue("expected_revisions").jsonObject.getValue("t1").jsonPrimitive.content)
                    if (rejectNextEdit) {
                        rejectNextEdit = false
                        serverTitle = "Чужая правка"
                        serverRevision++
                        respond("""{"error":"conflict","message":"Задача уже изменена"}""", HttpStatusCode.Conflict)
                    } else {
                        val key = assertNotNull(request.headers["Idempotency-Key"])
                        assertFalse(receipts.containsKey(key), "A committed operation must never be posted twice")
                        val admitted = assertNotNull(durableWrites[key], "The operation must be durable before POST")
                        assertEquals(SubmissionState.SENDING, admitted.state)
                        assertEquals(body, taskObject(admitted.body))
                        serverTitle = payload.getValue("changes").jsonObject.getValue("title").jsonPrimitive.content
                        serverRevision++
                        serverWrites++
                        val receipt = buildJsonObject {
                            put("operation_id", "op-$serverWrites"); put("kind", "task.update")
                            put("tasks", JsonArray(listOf(task())))
                        }.toString()
                        receipts[key] = receipt
                        if (loseNextReceipt) {
                            loseNextReceipt = false
                            throw IOException("synthetic response lost after server commit")
                        }
                        respond(receipt)
                    }
                }
                else -> error("Unexpected tasks request: $path")
            }
        }
    }), FEATURE_ORIGIN, sessions, { now })
    private val submissions = SubmissionCoordinator(store, http, sessions, { now }, SubmissionReceiptValidator(::validateTaskSubmissionReceipt))
    var controller = newController()
        private set

    private fun newController() = TasksController(test.backgroundScope, sessions, http, submissions, AccountDrafts(store, sessions, { now }), { now })

    suspend fun start(listId: String? = null) {
        if (sessions.lease() == null) sessions.restore()
        controller.activate(listId)
        controller.state.first { it != null && it.lists.data != null && (it.page.data != null || it.board.data != null) }
        controller.refresh()
    }
    fun restartProcess(): TasksControllerFixture {
        controller.deactivate()
        test.runCurrent()
        http.close()
        database.close()
        return TasksControllerFixture(test, directory).also {
            it.requests.addAll(requests)
            it.serverTitle = serverTitle
            it.serverRevision = serverRevision
            it.serverListId = serverListId
            it.serverWrites = serverWrites
            it.receipts.putAll(receipts)
            it.boardGeneration = boardGeneration
        }
    }
    suspend fun editTitle(value: String) {
        if (controller.state.value!!.task.data == null) controller.openTask("t1")
        val draft = assertNotNull(controller.state.value!!.draft)
        controller.updateDraft(draft.copy(fields = draft.fields.copy(title = value)))
    }
    suspend fun savedDraft() = store.draft(namespace, "task:t1")?.let {
        NativeJson.decodeFromString<TaskEditDraft>(it.json.decodeToString())
    }
    fun close() {
        controller.deactivate()
        test.runCurrent()
        http.close()
        database.close()
        directory.deleteRecursively()
    }

    private fun task(id: String = "t1", state: TaskState = TaskState.Todo) = buildJsonObject {
        put("id", id); put("list_id", serverListId); put("title", serverTitle)
        put("status", if (state == TaskState.Done) "done" else "open"); put("state", state.wire)
        put("revision", serverRevision); put("priority", 2)
    }
    private fun page(items: List<JsonObject>) = buildJsonObject {
        put("snapshot", "snapshot-$boardGeneration"); put("total", items.size); put("items", JsonArray(items)); put("next_cursor", JsonNull)
        put("today", "2026-10-17"); put("timezone", "Europe/Moscow")
    }
    private fun board() = buildJsonObject {
        put("snapshot", "snapshot-$boardGeneration"); put("total", 4)
        put("today", "2026-10-17"); put("timezone", "Europe/Moscow")
        put("columns", buildJsonObject {
            TaskState.entries.forEach { state -> put(state.wire, buildJsonObject {
                put("snapshot", "snapshot-$boardGeneration"); put("total", 1)
                put("items", JsonArray(listOf(task(if (state == TaskState.Todo) "t1" else "task-${state.wire}", state))))
                put("next_cursor", if (boardGeneration == 1) JsonPrimitive("cursor-${state.wire}-1") else JsonNull)
            }) }
        })
    }
    private fun lists() = buildJsonObject {
        put("lists", JsonArray(listOf("list-1", "project-1", "project-2").map { id -> buildJsonObject {
            put("id", id); put("name", id); put("revision", 1); put("effective", "manage"); put("member_access", "none")
            put("list_kind", if (id.startsWith("project")) "project" else "list")
        } }))
    }
}
