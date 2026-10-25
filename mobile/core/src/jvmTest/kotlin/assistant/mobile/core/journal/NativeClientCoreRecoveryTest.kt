package assistant.mobile.core.journal

import assistant.mobile.core.LiveSocketFactory
import assistant.mobile.core.dictation.FakeLiveSocket

import assistant.mobile.core.AccountNamespace
import assistant.mobile.core.EventInvalidation
import assistant.mobile.core.NativeClientCore
import assistant.mobile.core.NativeClientData
import assistant.mobile.core.NativeIdentity
import assistant.mobile.core.NativeJson
import assistant.mobile.core.SessionLease
import assistant.mobile.core.SessionResult
import assistant.mobile.core.SessionSecretStore
import assistant.mobile.core.StoredSession
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteChannel
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.writeStringUtf8
import java.nio.file.Files
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.encodeToString
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NativeClientCoreRecoveryTest {
    @Test
    fun movedServerRestoresHomeIdentityDraftJournalAndCursorWithoutLogin() = runBlocking {
        val directory = Files.createTempDirectory("native-core-recovery").toFile()
        val file = directory.resolve("journal.db")
        val namespace = AccountNamespace("integration", "https://native.example", "user-a")
        val serverOrigin = "https://moved.example"
        val original = """{"kind":"task.update","payload":{"task_id":"item","changes":{"title":"Synthetic recovered task"}},"expected_revisions":{"item":7}}""".encodeToByteArray()
        var database = openJournalDatabase(file)
        var journal = RoomJournalStore(database)
        journal.saveDraft(Draft(namespace, "composer", original, 7, 100))
        journal.writeCursor(namespace, "12")
        journal.insertSubmission(Submission(
            namespace, "durable-key", "/v1/tasks/operations", "/v1/tasks/operations/by-key/durable-key",
            "POST", original, "personal:user-a", mapOf("item" to "7"), "composer",
            SubmissionState.RESOLVED, 100, state = SubmissionState.SENDING,
            mayHaveBeenAdmitted = true, attempt = 1, disposition = AttemptDisposition.UNKNOWN,
        ))
        journal.insertSubmission(Submission(
            namespace, "prepared-key", "/v1/tasks/operations", "/v1/tasks/operations/by-key/prepared-key",
            "POST", original, "personal:user-a", mapOf("item" to "7"), "composer",
            SubmissionState.RESOLVED, 101,
        ))
        database.close()
        database = openJournalDatabase(file)
        journal = RoomJournalStore(database)
        val identity = NativeIdentity("user-a", "synthetic", "Synthetic", "ru", "UTC", false, 1,
            "device-a", "2099-01-01T00:00:00Z", listOf("tasks"))
        val record = StoredSession(namespace, "synthetic-credential", identity, "device-a", "session-a", identity.sessionExpiresAt)
        val secrets = object : SessionSecretStore {
            private var saved: StoredSession? = record
            override suspend fun load(instance: String, origin: String): StoredSession? {
                assertEquals(namespace.instance, instance)
                assertEquals(namespace.origin, origin)
                return saved
            }
            override suspend fun save(session: StoredSession) {
                assertEquals(namespace, session.namespace)
                saved = session
            }
            override suspend fun delete(instance: String, origin: String) {
                assertEquals(namespace.instance, instance)
                assertEquals(namespace.origin, origin)
                saved = null
            }
        }
        val requests = mutableListOf<Pair<HttpMethod, String>>()
        val engine = MockEngine { request ->
            assertEquals(serverOrigin, "${request.url.protocol.name}://${request.url.host}")
            assertEquals("Bearer ${record.credential}", request.headers["Authorization"])
            assertEquals(namespace.userId, request.headers["X-Expected-User"])
            val path = request.url.encodedPath
            requests += request.method to path
            when (path) {
                "/v1/me" -> respond(NativeJson.encodeToString(identity), HttpStatusCode.OK,
                    headersOf(HttpHeaders.ContentType, "application/json"))
                "/v1/tasks/operations/by-key/durable-key" -> respond("""{"operation_id":"recovered","kind":"task.update","tasks":[{"id":"item","title":"Synthetic recovered task","status":"open","revision":8}]}""", HttpStatusCode.OK,
                    headersOf(HttpHeaders.ContentType, "application/json"))
                "/v1/tasks/operations" -> {
                    assertEquals(HttpMethod.Post, request.method)
                    assertEquals("prepared-key", request.headers["Idempotency-Key"])
                    assertContentEquals(original, (request.body as io.ktor.http.content.OutgoingContent.ByteArrayContent).bytes())
                    respond("""{"operation_id":"sent","kind":"task.update","tasks":[{"id":"item","title":"Synthetic recovered task","status":"open","revision":8}]}""", HttpStatusCode.OK,
                        headersOf(HttpHeaders.ContentType, "application/json"))
                }
                "/v1/me/events" -> {
                    assertEquals("12", request.headers["Last-Event-ID"])
                    assertNull(request.url.parameters["cursor"])
                    val channel = ByteChannel(autoFlush = true)
                    channel.writeStringUtf8("id: 13\nevent: change\ndata: {\"topic\":\"tasks.changed\"}\n\n")
                    respond(channel as ByteReadChannel, HttpStatusCode.OK,
                        headersOf(HttpHeaders.ContentType, "text/event-stream"))
                }
                "/v1/auth/native/logout" -> respond("{}", HttpStatusCode.OK,
                    headersOf(HttpHeaders.ContentType, "application/json"))
                else -> error("Unexpected synthetic endpoint")
            }
        }
        var invalidations = 0
        var cachedAccount: AccountNamespace? = namespace
        val graph = NativeClientCore(this, namespace.instance, namespace.origin, secrets, journal, engine,
            object : NativeClientData {
                override suspend fun refetchAll(lease: SessionLease) = Unit
                override suspend fun invalidate(lease: SessionLease, invalidation: EventInvalidation) { invalidations++ }
            }, liveSockets = LiveSocketFactory { _, listener -> FakeLiveSocket(listener) }, clockMs = { 1_000L }, clearCachedAccount = { account ->
                assertEquals(namespace, account)
                cachedAccount = null
            }, serverOrigin = { serverOrigin })
        try {
            assertIs<SessionResult.SignedIn>(graph.restore())
            assertTrue(requests.isEmpty())
            val lease = graph.sessions.lease()!!
            assertEquals(namespace, lease.namespace)
            assertContentEquals(original, graph.drafts.read(lease, "composer")!!.json)
            val sent = graph.submissions.retry(lease, "prepared-key")
            assertEquals(namespace, sent.namespace)
            assertEquals(SubmissionState.RESOLVED, sent.state)
            val changed = async(start = CoroutineStart.UNDISPATCHED) {
                withTimeout(30_000) { graph.events.changes.first { it.id == "13" } }
            }
            graph.setForeground(true)
            changed.await()
            assertEquals(listOf(
                HttpMethod.Post to "/v1/tasks/operations",
                HttpMethod.Get to "/v1/me",
                HttpMethod.Get to "/v1/tasks/operations/by-key/durable-key",
                HttpMethod.Get to "/v1/me/events",
            ), requests)
            assertEquals(1, invalidations)
            assertEquals("13", journal.readCursor(namespace))
            assertEquals(SubmissionState.RESOLVED, journal.submission(namespace, "durable-key")!!.state)
            graph.sessions.logout()
            assertTrue(journal.submissions(namespace).isEmpty())
            assertNull(journal.draft(namespace, "composer"))
            assertNull(journal.readCursor(namespace))
            assertNull(cachedAccount)
            assertEquals(HttpMethod.Post to "/v1/auth/native/logout", requests.last())
            assertNull(graph.sessions.lease())
        } finally {
            graph.close()
            database.close()
            directory.deleteRecursively()
        }
    }
}
