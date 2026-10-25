package assistant.mobile.core.journal

import assistant.mobile.core.AccountNamespace
import assistant.mobile.core.decodeSubmissionReceipt
import assistant.mobile.core.NativeHttpClient
import assistant.mobile.core.NativeIdentity
import assistant.mobile.core.SessionLease
import assistant.mobile.core.SessionManager
import assistant.mobile.core.SessionSecretStore
import assistant.mobile.core.StoredSession
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.http.headersOf
import java.io.IOException
import java.nio.file.Files
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import assistant.mobile.core.NativeJson
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SubmissionCoordinatorTest {
    private val namespace = AccountNamespace("synthetic", "https://native.example", "user-a")
    private val bytes = "{\n  \"text\": \"сохранить\", \"revision\": 7\n}".encodeToByteArray()
    private val jsonHeaders = headersOf(HttpHeaders.ContentType, "application/json")

    @Test
    fun restartLooksUpDurableSendingWithoutAnotherPost() = runBlocking {
        val directory = Files.createTempDirectory("native-journal-reopen").toFile()
        val file = directory.resolve("journal.db")
        var database = openJournalDatabase(file)
        val methods = mutableListOf<HttpMethod>()
        val first = client(MockEngine { request ->
            methods += request.method
            throw IOException("synthetic lost response")
        }, RoomJournalStore(database))
        try {
            assertEquals(SubmissionState.UNCERTAIN, first.coordinator.submit(first.lease, request()).state)
            first.http.close()
            database.close()
            database = openJournalDatabase(file)
            val second = client(MockEngine { request ->
                methods += request.method
                assertEquals("/v1/tasks/operations/by-key/11111111-1111-4111-8111-111111111111", request.url.encodedPath)
                respond("{\"operation_id\":\"operation-a\"}", HttpStatusCode.OK, jsonHeaders)
            }, RoomJournalStore(database))
            try {
                val recovered = second.coordinator.recover(second.lease).single()
                assertEquals(SubmissionState.RESOLVED, recovered.state)
                assertEquals(listOf(HttpMethod.Post, HttpMethod.Get), methods)
                assertContentEquals(bytes, recovered.body)
                assertEquals("11111111-1111-4111-8111-111111111111", recovered.key)
            } finally { second.http.close() }
        } finally {
            first.http.close()
            database.close()
            directory.deleteRecursively()
        }
    }

    @Test
    fun explicitRetryReusesExactBytesAndKeyAfterLookup404() = runBlocking {
        withStore { store ->
            val bodies = mutableListOf<ByteArray>()
            val keys = mutableListOf<String?>()
            val methods = mutableListOf<HttpMethod>()
            val fixture = client(MockEngine { request ->
                methods += request.method
                if (request.method == HttpMethod.Get) {
                    respond("{\"error\":\"not_found\"}", HttpStatusCode.NotFound, jsonHeaders)
                } else {
                    bodies += bodyBytes(request)
                    keys += request.headers["Idempotency-Key"]
                    if (bodies.size == 1) throw IOException("synthetic lost response")
                    respond("{\"operation_id\":\"operation-a\"}", HttpStatusCode.OK, jsonHeaders)
                }
            }, store)
            try {
                val mutableBody = bytes.copyOf()
                fixture.coordinator.submit(fixture.lease, request().copy(body = mutableBody))
                mutableBody.fill(0)
                assertEquals(SubmissionState.RESOLVED, fixture.coordinator.retry(fixture.lease, "11111111-1111-4111-8111-111111111111").state)
                assertEquals(listOf(HttpMethod.Post, HttpMethod.Get, HttpMethod.Post), methods)
                assertEquals(listOf<String?>("11111111-1111-4111-8111-111111111111", "11111111-1111-4111-8111-111111111111"), keys)
                assertContentEquals(bytes, bodies[0])
                assertContentEquals(bodies[0], bodies[1])
            } finally { fixture.http.close() }
        }
    }

    @Test
    fun accountInstanceAndOriginPartitionAllLocalDataAndLogoutPurgesOnlyItsAccount() = runBlocking {
        withStore { store ->
            val scopes = listOf(namespace, namespace.copy(userId = "user-b"),
                namespace.copy(instance = "other-instance"), namespace.copy(origin = "https://other.example"))
            for ((index, scope) in scopes.withIndex()) {
                store.saveDraft(Draft(scope, "composer", "draft-$index".encodeToByteArray(), index.toLong(), 100))
                store.writeCursor(scope, index.toString())
                store.insertSubmission(entry(scope, key = "same-key"))
            }
            val fixture = client(MockEngine { respond("{}", HttpStatusCode.OK, jsonHeaders) }, store)
            try {
                fixture.session.expire(fixture.lease)
                assertNotNull(store.draft(namespace, "composer"))
                assertNotNull(store.submission(namespace, "same-key"))
                val b = client(MockEngine { respond("{}", HttpStatusCode.OK, jsonHeaders) }, store, scopes[1])
                try {
                    assertEquals(scopes[1], b.coordinator.pending(b.lease).single().namespace)
                    assertEquals("draft-1", store.draft(scopes[1], "composer")!!.json.decodeToString())
                    b.session.logout()
                    assertNull(store.draft(scopes[1], "composer"))
                    assertNull(store.readCursor(scopes[1]))
                    assertTrue(store.submissions(scopes[1]).isEmpty())
                    for (scope in scopes.filter { it != scopes[1] }) {
                        assertNotNull(store.draft(scope, "composer"))
                        assertNotNull(store.readCursor(scope))
                        assertNotNull(store.submission(scope, "same-key"))
                    }
                } finally { b.http.close() }
            } finally { fixture.http.close() }
        }
    }

    @Test
    fun keyedConflictLooksUpOriginalKeyAndRetainsDraft() = runBlocking {
        withStore { store ->
            store.saveDraft(Draft(namespace, "composer", bytes, 7, 100))
            val methods = mutableListOf<HttpMethod>()
            val fixture = client(MockEngine { request ->
                methods += request.method
                if (request.method == HttpMethod.Post) respond("{}", HttpStatusCode.Conflict, jsonHeaders)
                else respond("{\"operation_id\":\"earlier\"}", HttpStatusCode.OK, jsonHeaders)
            }, store)
            try {
                val result = fixture.coordinator.submit(fixture.lease, request())
                assertEquals(SubmissionState.RESOLVED, result.state)
                assertTrue(result.mayHaveBeenAdmitted)
                assertEquals(listOf(HttpMethod.Post, HttpMethod.Get), methods)
                assertContentEquals(bytes, store.draft(namespace, "composer")!!.json)
            } finally { fixture.http.close() }
        }
    }

    @Test
    fun forbiddenRefreshesIdentityWithoutLoginAndUnauthorizedExpiresPreservingDraft() = runBlocking {
        withStore { store ->
            store.saveDraft(Draft(namespace, "composer", bytes, 7, 100))
            val paths = mutableListOf<String>()
            var denied = HttpStatusCode.Forbidden
            val fixture = client(MockEngine { request ->
                paths += request.url.encodedPath
                if (request.url.encodedPath == "/v1/me") respond(NativeJson.encodeToString(identity(namespace)), HttpStatusCode.OK, jsonHeaders)
                else respond("{}", denied, jsonHeaders)
            }, store)
            try {
                assertEquals(SubmissionState.REJECTED, fixture.coordinator.submit(fixture.lease, request()).state)
                assertTrue(fixture.session.isCurrent(fixture.lease))
                assertEquals(listOf("/v1/tasks/operations", "/v1/me"), paths)
                denied = HttpStatusCode.Unauthorized
                assertFailsWith<StaleJournalSession> {
                    fixture.coordinator.submit(fixture.lease, request().copy(key = "22222222-2222-4222-8222-222222222222"))
                }
                assertNull(fixture.session.lease())
                assertNotNull(store.draft(namespace, "composer"))
                assertTrue(store.submission(namespace, "22222222-2222-4222-8222-222222222222")!!.mayHaveBeenAdmitted)
            } finally { fixture.http.close() }
        }
    }

    @Test
    fun retryAfterPreventsEarlyNetworkWritesAndNeverSchedulesResubmission() = runBlocking {
        withStore { store ->
            var time = 1_000L
            var requests = 0
            val fixture = client(MockEngine {
                requests++
                respond("{}", HttpStatusCode.TooManyRequests,
                    headersOf(HttpHeaders.ContentType to listOf("application/json"), HttpHeaders.RetryAfter to listOf("3")))
            }, store, now = { time })
            try {
                val first = fixture.coordinator.submit(fixture.lease, request())
                assertEquals(429, first.failureStatus)
                assertEquals(4_000L, first.retryAtMillis)
                fixture.coordinator.submit(fixture.lease, request().copy(key = "22222222-2222-4222-8222-222222222222"))
                assertEquals(1, requests)
                time = 4_000
                fixture.coordinator.submit(fixture.lease, request().copy(key = "33333333-3333-4333-8333-333333333333"))
                assertEquals(2, requests)
            } finally { fixture.http.close() }
        }
    }

    @Test
    fun storageFailureBeforePreparedOrSendingBlocksHttp() = runBlocking {
        withStore { durable ->
            var networkRequests = 0
            for (failAtSending in listOf(false, true)) {
                val failing = object : JournalStore by durable {
                    override suspend fun insertSubmission(entry: Submission) {
                        if (!failAtSending) throw JournalStorageException(IOException("synthetic disk unavailable"))
                        durable.insertSubmission(entry)
                    }
                    override suspend fun saveSubmission(entry: Submission) {
                        if (entry.state == SubmissionState.SENDING) throw JournalStorageException(IOException("synthetic disk unavailable"))
                        durable.saveSubmission(entry)
                    }
                }
                val fixture = client(MockEngine { networkRequests++; respond("{}", HttpStatusCode.OK, jsonHeaders) }, failing)
                try {
                    assertFailsWith<JournalStorageException> {
                        fixture.coordinator.submit(fixture.lease, request().copy(key = if (failAtSending) "44444444-4444-4444-8444-444444444444" else "55555555-5555-4555-8555-555555555555"))
                    }
                } finally { fixture.http.close() }
            }
            assertEquals(0, networkRequests)
            assertNull(durable.submission(namespace, "55555555-5555-4555-8555-555555555555"))
            val prepared = durable.submission(namespace, "44444444-4444-4444-8444-444444444444")!!
            assertEquals(SubmissionState.PREPARED, prepared.state)
            assertFalse(prepared.mayHaveBeenAdmitted)
        }
    }

    @Test
    fun lateOldAccountResponseIsDiscardedAndCannotRecreatePurgedRows() = runBlocking {
        withStore { store ->
            val dispatched = CompletableDeferred<Unit>()
            val response = CompletableDeferred<Unit>()
            val fixture = client(MockEngine { request ->
                if (request.url.encodedPath == "/v1/auth/native/logout") respond("{}", HttpStatusCode.OK, jsonHeaders)
                else {
                    dispatched.complete(Unit)
                    response.await()
                    respond("{\"operation_id\":\"late\"}", HttpStatusCode.OK, jsonHeaders)
                }
            }, store)
            try {
                val sending = async {
                    assertFailsWith<StaleJournalSession> { fixture.coordinator.submit(fixture.lease, request()) }
                }
                dispatched.await()
                fixture.session.logout()
                response.complete(Unit)
                sending.await()
                assertTrue(store.submissions(namespace).isEmpty())
            } finally { response.complete(Unit); fixture.http.close() }
        }
    }

    @Test
    fun admitted202IsLookedUpAndNeverResentEvenWhenLaterLookupIsMissing() = runBlocking {
        withStore { store ->
            var posts = 0
            val fixture = client(MockEngine { request ->
                if (request.method == HttpMethod.Post) {
                    posts++
                    respond("{\"operation_id\":\"admitted\"}", HttpStatusCode.Accepted, jsonHeaders)
                } else respond("{}", HttpStatusCode.NotFound, jsonHeaders)
            }, store)
            try {
                assertEquals(SubmissionState.ACCEPTED, fixture.coordinator.submit(fixture.lease, request()).state)
                assertEquals(SubmissionState.ACCEPTED, fixture.coordinator.recover(fixture.lease).single().state)
                assertFailsWith<SubmissionRetryBlocked> { fixture.coordinator.retry(fixture.lease, "11111111-1111-4111-8111-111111111111") }
                assertEquals(1, posts)
            } finally { fixture.http.close() }
        }
    }

    @Test
    fun keyMutexMakesLookupWaitForTheOriginalSend() = runBlocking {
        withStore { store ->
            val dispatched = CompletableDeferred<Unit>()
            val response = CompletableDeferred<Unit>()
            val methods = mutableListOf<HttpMethod>()
            val fixture = client(MockEngine { request ->
                methods += request.method
                dispatched.complete(Unit)
                response.await()
                respond("{\"operation_id\":\"complete\"}", HttpStatusCode.OK, jsonHeaders)
            }, store)
            try {
                val sending = async { fixture.coordinator.submit(fixture.lease, request()) }
                dispatched.await()
                val checking = async { fixture.coordinator.check(fixture.lease, "11111111-1111-4111-8111-111111111111") }
                response.complete(Unit)
                assertEquals(SubmissionState.RESOLVED, sending.await().state)
                assertEquals(SubmissionState.RESOLVED, checking.await().state)
                assertEquals(listOf(HttpMethod.Post), methods)
            } finally { response.complete(Unit); fixture.http.close() }
        }
    }

    @Test
    fun malformedCurrentReceiptCannotReuseAnEarlier404AsPermissionToResend() = runBlocking {
        withStore { store ->
            val key = "11111111-1111-4111-8111-111111111111"
            store.insertSubmission(entry(namespace, key).copy(
                state = SubmissionState.UNCERTAIN, mayHaveBeenAdmitted = true, failureStatus = 404,
            ))
            val methods = mutableListOf<HttpMethod>()
            val fixture = client(MockEngine { request ->
                methods += request.method
                respond("{\"malformed_receipt\":true}", HttpStatusCode.OK, jsonHeaders)
            }, store)
            val coordinator = SubmissionCoordinator(store, fixture.http, fixture.session,
                validateReceipt = SubmissionReceiptValidator { _, _ -> error("Synthetic invalid receipt") })
            try {
                assertFailsWith<SubmissionRetryBlocked> { coordinator.retry(fixture.lease, key) }
                assertEquals(listOf(HttpMethod.Get), methods)
                assertNull(store.submission(namespace, key)!!.failureStatus)
                assertTrue(store.submission(namespace, key)!!.mayHaveBeenAdmitted)
            } finally { fixture.http.close() }
        }
    }

    @Test
    fun keyedConflictWithoutLookupRemainsUnknownAndCannotBeResent() = runBlocking {
        withStore { store ->
            var requests = 0
            val fixture = client(MockEngine {
                requests++
                respond("{}", HttpStatusCode.Conflict, jsonHeaders)
            }, store)
            try {
                val result = fixture.coordinator.submit(fixture.lease, request().copy(lookupPrefix = null))
                assertEquals(SubmissionState.UNCERTAIN, result.state)
                assertEquals(409, result.failureStatus)
                assertTrue(result.mayHaveBeenAdmitted)
                assertFailsWith<SubmissionRetryBlocked> { fixture.coordinator.retry(fixture.lease, result.key) }
                assertEquals(1, requests)
            } finally { fixture.http.close() }
        }
    }

    @Test
    fun conversationFixtureLookupsKeepAdmissionAcceptedUntilTerminalDeliveryReceipt() = runBlocking {
        withStore { store ->
            val fixtureBytes = javaClass.classLoader!!.getResourceAsStream("submission.json")!!.use { it.readBytes() }
            val admission = decodeSubmissionReceipt(fixtureBytes)
            var state = admission.state
            val methods = mutableListOf<HttpMethod>()
            val key = "11111111-1111-4111-8111-111111111111"
            val fixture = client(MockEngine { request ->
                methods += request.method
                respond(NativeJson.encodeToString(admission.copy(submissionKey = key, state = state)), HttpStatusCode.OK, jsonHeaders)
            }, store)
            try {
                val conversation = request().copy(endpoint = "/v1/conversation/messages",
                    lookupPrefix = "/v1/conversation/submissions/by-key/")
                // Default found=RESOLVED must not turn an admission-only HTTP200 into completion.
                assertEquals(SubmissionState.ACCEPTED, fixture.coordinator.submit(fixture.lease, conversation).state)
                for (pendingState in listOf("queued", "routing", "processing", "clarification")) {
                    state = pendingState
                    assertEquals(SubmissionState.ACCEPTED, fixture.coordinator.check(fixture.lease, key).state)
                }
                state = "future_state"
                assertEquals(SubmissionState.ACCEPTED, fixture.coordinator.check(fixture.lease, key).state)
                state = "completed"
                assertEquals(SubmissionState.RESOLVED, fixture.coordinator.check(fixture.lease, key).state)
                assertEquals(1, methods.count { it == HttpMethod.Post })
            } finally { fixture.http.close() }
        }
    }

    @Test
    fun uncertainTaskDiscussionHasNoLookupAndNeverPostsAgainDuringRecoveryOrCheck() = runBlocking {
        withStore { store ->
            val methods = mutableListOf<HttpMethod>()
            val fixture = client(MockEngine { request ->
                methods += request.method
                assertEquals("/v1/channels/22222222-2222-4222-8222-222222222222/messages", request.url.encodedPath)
                throw IOException("synthetic committed discussion receipt lost")
            }, store)
            try {
                val delivery = request().copy(endpoint = "/v1/channels/22222222-2222-4222-8222-222222222222/messages", lookupPrefix = null)
                val result = fixture.coordinator.submit(fixture.lease, delivery)
                assertEquals(SubmissionState.UNCERTAIN, result.state)
                assertNull(result.lookup)
                fixture.coordinator.recover(fixture.lease)
                fixture.coordinator.check(fixture.lease, result.key)
                assertFailsWith<SubmissionRetryBlocked> { fixture.coordinator.retry(fixture.lease, result.key) }
                assertEquals(listOf(HttpMethod.Post), methods)
                assertContentEquals(bytes, store.submission(namespace, result.key)!!.body)
                fixture.coordinator.abandonUnkeyed(fixture.lease, result.key)
                assertTrue(store.submissions(namespace).isEmpty())
                assertEquals(listOf(HttpMethod.Post), methods)
            } finally { fixture.http.close() }
        }
    }

    @Test
    fun sharingAndChallengeUnknownOutcomesNeverRepostDuringRecoveryCheckOrRetry() = runBlocking {
        val id = "22222222-2222-4222-8222-222222222222"
        for (endpoint in listOf("/v1/resources/$id", "/v1/resources/$id/grants", "/v1/challenges", "/v1/challenges/$id/join", "/v1/challenges/$id/leave")) {
            withStore { store ->
                val methods = mutableListOf<HttpMethod>()
                val fixture = client(MockEngine { request ->
                    methods += request.method
                    assertEquals(endpoint, request.url.encodedPath)
                    assertEquals("11111111-1111-4111-8111-111111111111", request.headers["Idempotency-Key"])
                    throw IOException("synthetic lost command response")
                }, store)
                try {
                    val result = fixture.coordinator.submit(fixture.lease, request().copy(endpoint = endpoint, lookupPrefix = null))
                    assertEquals(SubmissionState.UNCERTAIN, result.state)
                    assertNull(result.lookup)
                    fixture.coordinator.recover(fixture.lease)
                    fixture.coordinator.check(fixture.lease, result.key)
                    assertFailsWith<SubmissionRetryBlocked> { fixture.coordinator.retry(fixture.lease, result.key) }
                    assertEquals(listOf(HttpMethod.Post), methods)
                    assertContentEquals(bytes, store.submission(namespace, result.key)!!.body)
                    assertEquals(mapOf("item" to "7"), store.submission(namespace, result.key)!!.revisions)
                    fixture.coordinator.abandonUnkeyed(fixture.lease, result.key)
                    assertTrue(store.submissions(namespace).isEmpty())
                } finally { fixture.http.close() }
            }
        }
    }

    @Test
    fun provenRevisionRefusalRetainsTypedCompletionDetailsForExplicitConsent() = runBlocking {
        withStore { store ->
            val refusal = """{"error":"incomplete_checklist","detail":{"task_id":"task","revision":7,"open_subtasks":0,"incomplete_checklist":2}}""".encodeToByteArray()
            val fixture = client(MockEngine { request ->
                if (request.method == HttpMethod.Post) respond(refusal, HttpStatusCode.Conflict, jsonHeaders)
                else respond("{}", HttpStatusCode.NotFound, jsonHeaders)
            }, store)
            try {
                val result = fixture.coordinator.submit(fixture.lease, request())
                assertEquals(SubmissionState.REJECTED, result.state)
                assertEquals(409, result.failureStatus)
                assertFalse(result.mayHaveBeenAdmitted)
                assertContentEquals(refusal, result.response)
            } finally { fixture.http.close() }
        }
    }

    private fun request() = NewSubmission(
        endpoint = "/v1/tasks/operations", lookupPrefix = "/v1/tasks/operations/by-key/", body = bytes,
        audience = "personal:user-a", revisions = mapOf("item" to "7"), draftTarget = "composer", key = "11111111-1111-4111-8111-111111111111",
    )

    private fun entry(scope: AccountNamespace, key: String) = Submission(
        scope, key, "/v1/tasks/operations", "/v1/tasks/operations/by-key/$key", "POST", bytes,
        "personal:${scope.userId}", mapOf("item" to "7"), "composer", SubmissionState.RESOLVED, 100,
    )

    private suspend fun withStore(block: suspend (RoomJournalStore) -> Unit) {
        val directory = Files.createTempDirectory("native-journal").toFile()
        val database = openJournalDatabase(directory.resolve("journal.db"))
        try { block(RoomJournalStore(database)) } finally { database.close(); directory.deleteRecursively() }
    }

    private data class Client(
        val session: SessionManager, val http: NativeHttpClient,
        val coordinator: SubmissionCoordinator, val lease: SessionLease,
    )

    private suspend fun client(
        engine: MockEngine, store: JournalStore, scope: AccountNamespace = namespace, now: () -> Long = { 1_000L },
    ): Client {
        val session = SessionManager(scope.instance, scope.origin, Secrets(record(scope)), store::clearAccount, now)
        val http = NativeHttpClient(engine, scope.origin, session, now)
        session.restore()
        return Client(session, http, SubmissionCoordinator(store, http, session, now), session.lease()!!)
    }

    private fun identity(scope: AccountNamespace) = NativeIdentity(
        scope.userId, scope.userId, "Synthetic", "ru", "UTC", false, 1, "device-a", "2099-01-01T00:00:00Z", listOf("tasks"),
    )

    private fun record(scope: AccountNamespace) = StoredSession(
        scope, "synthetic-credential", identity(scope), "device-a", "session-a", "2099-01-01T00:00:00Z",
    )

    private class Secrets(private var record: StoredSession?) : SessionSecretStore {
        override suspend fun load(instance: String, origin: String): StoredSession? = record
        override suspend fun save(session: StoredSession) { record = session }
        override suspend fun delete(instance: String, origin: String) { record = null }
    }

    private fun bodyBytes(request: HttpRequestData): ByteArray =
        (request.body as OutgoingContent.ByteArrayContent).bytes().copyOf()
}
