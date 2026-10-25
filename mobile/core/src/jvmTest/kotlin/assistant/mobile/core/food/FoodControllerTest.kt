package assistant.mobile.core.food

import assistant.mobile.core.AccountDrafts
import assistant.mobile.core.FEATURE_ORIGIN
import assistant.mobile.core.FeatureSessionSecrets
import assistant.mobile.core.NativeHttpClient
import assistant.mobile.core.NativeJson
import assistant.mobile.core.SessionManager
import assistant.mobile.core.featureBody
import assistant.mobile.core.featureRecord
import assistant.mobile.core.journal.Draft
import assistant.mobile.core.journal.JournalStorageException
import assistant.mobile.core.journal.JournalStore
import assistant.mobile.core.journal.RoomJournalStore
import assistant.mobile.core.journal.Submission
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
import java.io.IOException
import java.nio.file.Files
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
class FoodControllerTest {
    @Test
    fun revisionConflictRereadsDayAndPreservesExactDurableDraftUntilAnotherExplicitSave() = runTest {
        val fixture = FoodControllerFixture(this)
        try {
            fixture.start()
            fixture.editQuantity(" 125,50 ")
            val originalDraft = fixture.controller.state.value!!.draft
            fixture.rejectNextEdit = true
            fixture.controller.submitEdit()
            val state = fixture.controller.state.value!!
            assertTrue(state.conflict)
            assertEquals(originalDraft, state.draft)
            assertEquals(2L, state.draft!!.revision)
            assertEquals(3L, state.day.data!!.items.first().revision)
            assertEquals("150.000", state.day.data!!.items.first().quantity)
            val saved = assertNotNull(fixture.store.draft(fixture.namespace, "food/edit"))
            assertEquals(originalDraft, NativeJson.decodeFromString<FoodEditDraft>(saved.json.decodeToString()))
            assertEquals(listOf(HttpMethod.Post, HttpMethod.Get), fixture.operationRequests.map { it.method })
            assertEquals(0, fixture.serverWrites)
            fixture.controller.refresh()
            assertEquals(1, fixture.posts.size)
            fixture.controller.submitEdit()
            assertEquals(2, fixture.posts.size)
            assertEquals("3", jsonBody(fixture.posts.last()).getValue("expected_revisions").jsonObject.getValue("e1").jsonPrimitive.content)
            assertTrue(fixture.posts.first().headers["Idempotency-Key"] != fixture.posts.last().headers["Idempotency-Key"])
            assertEquals(1, fixture.serverWrites)
            assertEquals("125.50", fixture.controller.state.value!!.day.data!!.items.first().quantity)
            assertNull(fixture.controller.state.value!!.draft)
            assertFalse(fixture.controller.state.value!!.conflict)
        } finally { fixture.close() }
    }

    @Test
    fun uncertainWriteLooksUpOriginalKeyWithoutMakingASecondRecordOrPost() = runTest {
        val fixture = FoodControllerFixture(this)
        try {
            fixture.start()
            fixture.editQuantity("125")
            fixture.loseNextReceipt = true
            fixture.controller.submitEdit()
            val pending = fixture.controller.state.value!!.pending.single()
            assertEquals(SubmissionState.UNCERTAIN, pending.state)
            assertNotNull(fixture.controller.state.value!!.draft)
            assertEquals(1, fixture.serverWrites)
            assertEquals(1, fixture.posts.size)
            fixture.controller.check(pending.key)
            fixture.controller.check(pending.key)
            assertEquals(1, fixture.posts.size)
            assertEquals(1, fixture.serverWrites)
            assertEquals(listOf(FOOD_LOOKUP + pending.key), fixture.operationRequests.filter { it.method == HttpMethod.Get }.map { it.url.encodedPath })
            assertEquals("op-1", fixture.controller.state.value!!.receipt!!.operationId)
            assertEquals("125", fixture.controller.state.value!!.day.data!!.items.first().quantity)
            assertTrue(fixture.controller.state.value!!.pending.isEmpty())
            assertNull(fixture.controller.state.value!!.draft)
        } finally { fixture.close() }
    }

    @Test
    fun receiptForAnotherItemRemainsUncertainUntilValidatedLookup() = runTest {
        val fixture = FoodControllerFixture(this)
        try {
            fixture.start()
            fixture.editQuantity("125")
            fixture.wrongNextReceipt = true
            fixture.controller.submitEdit()
            val pending = fixture.controller.state.value!!.pending.single()
            assertEquals(SubmissionState.UNCERTAIN, pending.state)
            assertNull(fixture.controller.state.value!!.receipt)
            assertNotNull(fixture.controller.state.value!!.draft)
            fixture.controller.check(pending.key)
            assertEquals("e1", fixture.controller.state.value!!.receipt!!.entryId)
            assertEquals(1, fixture.posts.size)
            assertEquals(1, fixture.serverWrites)
            assertNull(fixture.controller.state.value!!.draft)
        } finally { fixture.close() }
    }

    @Test
    fun startupLookupRereadsAuthoritativeDayAndKeepsNewerDurableEdit() = runTest {
        val fixture = FoodControllerFixture(this)
        try {
            val sent = editedDraft("125")
            val newer = sent.copy(quantity = "140,75")
            fixture.seedReceipt(sent, newer, resolved = false)
            fixture.start(receipt = true)
            val state = fixture.controller.state.value!!
            assertEquals("recovered-op", state.receipt!!.operationId)
            assertEquals(newer, state.draft)
            assertEquals("125", state.day.data!!.items.first().quantity)
            assertEquals(3L, state.day.data!!.items.first().revision)
            val lookupIndex = fixture.requests.indexOfFirst { it.url.encodedPath == FOOD_LOOKUP + RECOVERED_KEY }
            assertTrue(lookupIndex >= 0)
            assertTrue(fixture.requests.drop(lookupIndex + 1).any { it.url.encodedPath == "/v1/nutrition/day" })
            val persisted = assertNotNull(fixture.store.draft(fixture.namespace, "food/edit"))
            assertEquals(newer, NativeJson.decodeFromString<FoodEditDraft>(persisted.json.decodeToString()))
            assertTrue(fixture.posts.isEmpty())
            assertEquals(1, fixture.operationRequests.size)
        } finally { fixture.close() }
    }

}

private const val RECOVERED_KEY = "11111111-1111-4111-8111-111111111111"
private fun editedDraft(quantity: String) = FoodEditDraft("entry", "e1", "Йогурт", "150.000", "g", "2026-10-03", 2,
    mealKind = "breakfast").copy(quantity = quantity)
private fun jsonBody(request: HttpRequestData) = NativeJson.parseToJsonElement(featureBody(request.body).decodeToString()).jsonObject

@OptIn(ExperimentalCoroutinesApi::class)
private class FoodControllerFixture(private val test: TestScope) {
    private val directory = Files.createTempDirectory("native-food-journal").toFile()
    private val database = openJournalDatabase(directory.resolve("journal.db"))
    private val durable = RoomJournalStore(database)
    var failPendingReads = false
    val store = object : JournalStore by durable {
        override suspend fun submissions(namespace: assistant.mobile.core.AccountNamespace): List<Submission> {
            if (failPendingReads) throw JournalStorageException(IOException("synthetic journal unavailable"))
            return durable.submissions(namespace)
        }
    }
    private val now = Instant.parse("2026-10-03T12:00:00Z").toEpochMilliseconds()
    private val record = featureRecord().let { it.copy(identity = it.identity.copy(
        timezone = "Europe/Moscow", scopes = listOf("nutrition.read", "nutrition.write"))) }
    val namespace = record.namespace
    val sessions = SessionManager("synthetic", FEATURE_ORIGIN, FeatureSessionSecrets(record), store::clearAccount, { now })
    val requests = mutableListOf<HttpRequestData>()
    val operationRequests get() = requests.filter { it.url.encodedPath.startsWith(FOOD_OPERATIONS) }
    val posts get() = operationRequests.filter { it.method == HttpMethod.Post }
    var rejectNextEdit = false
    var loseNextReceipt = false
    var wrongNextReceipt = false
    var serverRevision = 2L
    var serverQuantity = "150.000"
    var serverWrites = 0
    private val receipts = mutableMapOf<String, String>()
    private var lookupCommit: (() -> Unit)? = null
    val http = NativeHttpClient(MockEngine(MockEngineConfig().apply {
        dispatcher = StandardTestDispatcher(test.testScheduler)
        addHandler { request ->
            requests += request
            val path = request.url.encodedPath
            when {
                path == "/v1/nutrition/day" -> respond(foodFixture("day").decodeToString()
                    .replace("2026-10-03", request.url.parameters["date"]!!)
                    .replace("\"revision\": 2", "\"revision\": $serverRevision")
                    .replace("\"150.000\"", "\"$serverQuantity\""))
                path == "/v1/nutrition/days" -> respond(foodFixture("week"))
                path.startsWith(FOOD_LOOKUP) -> {
                    lookupCommit?.invoke()
                    lookupCommit = null
                    val receipt = receipts[path.removePrefix(FOOD_LOOKUP)]
                    if (receipt == null) respond("{}", HttpStatusCode.NotFound) else respond(receipt)
                }
                path == FOOD_OPERATIONS -> {
                    assertEquals(HttpMethod.Post, request.method)
                    val body = jsonBody(request)
                    val payload = body.getValue("payload").jsonObject
                    val revision = body.getValue("expected_revisions").jsonObject.getValue("e1").jsonPrimitive.content.toLong()
                    assertEquals(serverRevision, revision)
                    if (rejectNextEdit) {
                        rejectNextEdit = false
                        serverRevision++
                        respond("{}", HttpStatusCode.Conflict)
                    } else {
                        val key = assertNotNull(request.headers["Idempotency-Key"])
                        assertFalse(receipts.containsKey(key), "The client resent an admitted operation")
                        serverWrites++
                        serverRevision++
                        val kind = body.getValue("kind").jsonPrimitive.content
                        val receipt = if (kind == "operation.undo") {
                            assertEquals("op-1", payload.getValue("operation_id").jsonPrimitive.content)
                            serverQuantity = "150.000"
                            """{"operation_id":"undo-op","kind":"operation.undo","applied_entries":[{"entry_id":"e1","revision":$serverRevision}],"applied_dishes":[],"undone_operation_id":"op-1"}"""
                        } else {
                            assertEquals("entry.update", kind)
                            serverQuantity = payload.getValue("quantity").jsonPrimitive.content
                            """{"operation_id":"op-$serverWrites","kind":"entry.update","entry_id":"e1","revision":$serverRevision}"""
                        }
                        receipts[key] = receipt
                        if (loseNextReceipt) {
                            loseNextReceipt = false
                            throw IOException("synthetic response lost after server commit")
                        }
                        if (wrongNextReceipt) {
                            wrongNextReceipt = false
                            respond(receipt.replace("\"e1\"", "\"other-item\""))
                        } else respond(receipt)
                    }
                }
                else -> error("Unexpected food request: $path")
            }
        }
    }), FEATURE_ORIGIN, sessions, { now })
    val submissions = SubmissionCoordinator(store, http, sessions, { now }, SubmissionReceiptValidator(::validateFoodSubmissionReceipt))
    val controller = FoodController(test.backgroundScope, sessions, http, submissions, AccountDrafts(store, sessions, { now }), { now })

    suspend fun start(receipt: Boolean = false) {
        sessions.restore()
        controller.activate("2026-10-03")
        controller.state.first { state -> state?.day?.data != null && state.week.data != null && (!receipt || state.receipt != null) }
        // Await the entire recovery cycle, including the post-lookup read and acknowledgement.
        controller.refresh()
    }

    fun editQuantity(quantity: String) {
        controller.beginEdit(controller.state.value!!.day.data!!.items.first())
        controller.updateDraft(controller.state.value!!.draft!!.copy(quantity = quantity))
    }

    suspend fun seedReceipt(sent: FoodEditDraft, stored: FoodEditDraft, resolved: Boolean) {
        val operation = editFoodOperation(sent)
        val receipt = """{"operation_id":"recovered-op","kind":"entry.update","entry_id":"e1","revision":3}"""
        durable.saveDraft(Draft(namespace, "food/edit", NativeJson.encodeToString(stored).encodeToByteArray(), 8, now))
        durable.insertSubmission(Submission(namespace, RECOVERED_KEY, FOOD_OPERATIONS, FOOD_LOOKUP + RECOVERED_KEY,
            "POST", operation.bytes(), "nutrition", mapOf("e1" to "2"), "food/edit", SubmissionState.RESOLVED, now,
            state = if (resolved) SubmissionState.RESOLVED else SubmissionState.SENDING,
            response = if (resolved) receipt.encodeToByteArray() else null, mayHaveBeenAdmitted = true, attempt = 1))
        if (resolved) {
            serverRevision = 3
            serverQuantity = "125"
        } else {
            receipts[RECOVERED_KEY] = receipt
            lookupCommit = { serverRevision = 3; serverQuantity = "125" }
        }
    }

    fun close() {
        controller.deactivate()
        test.runCurrent()
        http.close()
        database.close()
        directory.deleteRecursively()
    }
}
