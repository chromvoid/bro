package assistant.mobile.core.tasks

import assistant.mobile.core.AccountDrafts
import assistant.mobile.core.FEATURE_ORIGIN
import assistant.mobile.core.FeatureSessionSecrets
import assistant.mobile.core.NativeHttpClient
import assistant.mobile.core.NativeJson
import assistant.mobile.core.SessionManager
import assistant.mobile.core.featureBody
import assistant.mobile.core.featureRecord
import assistant.mobile.core.journal.RoomJournalStore
import assistant.mobile.core.journal.SubmissionCoordinator
import assistant.mobile.core.journal.SubmissionReceiptValidator
import assistant.mobile.core.journal.openJournalDatabase
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockEngineConfig
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import java.io.IOException
import java.nio.file.Files
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
class TaskAssistantControllerTest {
    @Test
    fun unknownOutcomePreservesDraftAndLooksUpSameKeyWithoutSecondPost() = runTest {
        val fixture = TaskAssistantControllerFixture(this)
        try {
            fixture.start()
            fixture.controller.updateDraft(fixture.controller.state.value!!.draft.copy(instruction = "Сравни варианты"))
            fixture.loseReceipt = true
            fixture.controller.save()
            val pending = fixture.tasks.state.value!!.pending.single()
            assertEquals("Сравни варианты", fixture.controller.state.value!!.draft.instruction)
            fixture.controller.save()
            assertEquals(1, fixture.posts.size)
            fixture.tasks.check(pending.key)
            assertEquals(1, fixture.posts.size)
            assertEquals(listOf(TASK_LOOKUP + pending.key), fixture.lookups)
            assertTrue(fixture.tasks.state.value!!.pending.isEmpty())
        } finally { fixture.close() }
    }
    @Test
    fun taskSwitchRestoresOnlyThatTasksAnswerDrafts() = runTest {
        val fixture = TaskAssistantControllerFixture(this)
        try {
            fixture.start()
            fixture.controller.updateDraft(fixture.controller.state.value!!.draft.copy(answers = mapOf("question" to "До 5000")))
            runCurrent()
            fixture.controller.activate("other")
            runCurrent()
            assertTrue(fixture.controller.state.value!!.draft.answers.isEmpty())
            fixture.controller.updateDraft(fixture.controller.state.value!!.draft.copy(answers = mapOf("question" to "До 100")))
            runCurrent()
            fixture.controller.activate("task")
            runCurrent()
            assertEquals(mapOf("question" to "До 5000"), fixture.controller.state.value!!.draft.answers)
            assertTrue(fixture.posts.isEmpty())
        } finally { fixture.close() }
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
private class TaskAssistantControllerFixture(private val test: TestScope) {
    private val directory = Files.createTempDirectory("native-task-assistant").toFile()
    private val database = openJournalDatabase(directory.resolve("journal.db"))
    private val store = RoomJournalStore(database)
    private val now = Instant.parse("2026-10-17T12:00:00Z").toEpochMilliseconds()
    private val record = featureRecord().let { it.copy(identity = it.identity.copy(scopes = listOf("tasks.read", "tasks.write"))) }
    private val sessions = SessionManager("synthetic", FEATURE_ORIGIN, FeatureSessionSecrets(record), store::clearAccount, { now })
    val requests = mutableListOf<HttpRequestData>()
    val posts get() = requests.filter { it.method == HttpMethod.Post && it.url.encodedPath == TASK_OPERATIONS }
    val lookups get() = requests.filter { it.url.encodedPath.startsWith(TASK_LOOKUP) }.map { it.url.encodedPath }
    var loseReceipt = false
    var releasePost: CompletableDeferred<Unit>? = null
    val postStarted = CompletableDeferred<Unit>()
    private var delegation: String? = null
    private val receipts = mutableMapOf<String, String>()
    private val http = NativeHttpClient(MockEngine(MockEngineConfig().apply {
        dispatcher = StandardTestDispatcher(test.testScheduler)
        addHandler { request ->
            requests += request
            when {
                request.url.encodedPath == TASK_OPERATIONS -> {
                    postStarted.complete(Unit)
                    releasePost?.await()
                    val sent = NativeJson.parseToJsonElement(featureBody(request.body).decodeToString()).jsonObject
                    val payload = sent.getValue("payload").jsonObject
                    val taskId = payload.getValue("task_id").jsonPrimitive.content
                    delegation = buildJsonObject {
                        put("id", "delegation"); put("task_id", taskId); put("user_id", record.namespace.userId)
                        put("instruction", payload.getValue("instruction")); put("mode", "oneoff"); put("completion_mode", "manual")
                        put("status", "waiting_input"); put("revision", 1); put("grants", NativeJson.parseToJsonElement(assistantCapabilities()).jsonObject.getValue("default_grants"))
                    }.toString()
                    val receipt = """{"operation_id":"operation","kind":"delegation.create","task_id":"$taskId","delegation":$delegation}"""
                    receipts[requireNotNull(request.headers["Idempotency-Key"])] = receipt
                    if (loseReceipt) { loseReceipt = false; throw IOException("synthetic lost receipt") }
                    respond(receipt, HttpStatusCode.Created)
                }
                request.url.encodedPath.startsWith(TASK_LOOKUP) -> receipts[request.url.encodedPath.removePrefix(TASK_LOOKUP)]?.let { respond(it) } ?: respond("{}", HttpStatusCode.NotFound)
                request.url.encodedPath == "/v1/me" -> respond(NativeJson.encodeToString(assistant.mobile.core.NativeIdentity.serializer(), record.identity))
                request.url.encodedPath == "/v1/tasks/lists" -> respond("""{"lists":[]}""")
                request.url.encodedPath == "/v1/tasks/items" -> respond("""{"total":0,"items":[],"today":"2026-10-17","timezone":"UTC"}""")
                request.url.encodedPath == "/v1/spaces" -> respond("""{"spaces":[]}""")
                request.url.encodedPath == "/v1/tasks/assistant/capabilities" -> respond(assistantCapabilities())
                request.url.encodedPath.endsWith("/assistant") -> {
                    val taskId = request.url.encodedPath.removeSuffix("/assistant").substringAfterLast('/')
                    val stored = delegation?.takeIf { NativeJson.parseToJsonElement(it).jsonObject.getValue("task_id").jsonPrimitive.content == taskId }
                    respond("""{"delegation":${stored ?: "null"},"runs":[],"questions":[],"results":[],"can_manage":true}""")
                }
                request.url.encodedPath in setOf("/v1/tasks/items/task", "/v1/tasks/items/other") -> {
                    val id = request.url.encodedPath.substringAfterLast('/')
                    respond("""{"id":"$id","title":"Проверить варианты","status":"open","state":"todo","revision":7}""")
                }
                else -> respond("{}", HttpStatusCode.NotFound)
            }
        }
    }), FEATURE_ORIGIN, sessions, { now })
    private val coordinator = SubmissionCoordinator(store, http, sessions, { now }, validateReceipt = SubmissionReceiptValidator { entry, bytes -> validateTaskSubmissionReceipt(entry, bytes) })
    private val drafts = AccountDrafts(store, sessions, { now })
    val tasks = TasksController(test.backgroundScope, sessions, http, coordinator, drafts, { now })
    val controller = TaskAssistantController(test.backgroundScope, sessions, http, drafts, tasks)
    suspend fun start() {
        sessions.restore()
        test.runCurrent()
        tasks.activate()
        tasks.refresh()
        // The fixture exercises the assistant owner; stop the separate list owner after its
        // authoritative bootstrap so its activation recovery cannot race an injected lost reply.
        tasks.deactivate()
        test.runCurrent()
        controller.activate("task")
        controller.refresh()
        test.runCurrent()
    }
    fun close() { controller.deactivate(); tasks.deactivate(); test.runCurrent(); http.close(); database.close(); directory.deleteRecursively() }
}
