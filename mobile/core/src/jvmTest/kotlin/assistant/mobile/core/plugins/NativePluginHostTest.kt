package assistant.mobile.core.plugins

import assistant.mobile.core.AccountNamespace
import assistant.mobile.core.NativeHttpClient
import assistant.mobile.core.NativeIdentity
import assistant.mobile.core.SessionManager
import assistant.mobile.core.SessionSecretStore
import assistant.mobile.core.StoredSession
import assistant.mobile.core.journal.Draft
import assistant.mobile.core.journal.JournalStore
import assistant.mobile.core.journal.Submission
import assistant.mobile.core.journal.SubmissionCoordinator
import assistant.mobile.core.journal.SubmissionState
import assistant.mobile.pluginapi.InvocationOutcome
import assistant.mobile.pluginapi.PluginConfigurationAccess
import assistant.mobile.pluginapi.PluginEvent
import assistant.mobile.pluginapi.PluginInstallation
import assistant.mobile.pluginapi.PluginPackage
import assistant.mobile.pluginapi.PluginTool
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.http.headersOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NativePluginHostTest {
    @Test
    fun acceptedWriteIsResolvedByLookupAndNeverPostsAgainEvenOnRetry() = runTest {
        val methods = mutableListOf<HttpMethod>()
        val fixture = fixture(backgroundScope, MockEngine { request ->
            methods += request.method
            if (request.method == HttpMethod.Post) {
                respond("""{"state":"running","progress":{"done":1}}""", HttpStatusCode.Accepted, json)
            } else {
                respond("""{"contract":"assistant.lookup/v1","status":"found","result":{"state":"completed","data":{"ok":true},"receipts":[{"id":"receipt"}]}}""", headers = json)
            }
        })
        try {
            val pending = assertIs<InvocationOutcome.Pending>(fixture.host.invoke("sync.refill"))
            assertEquals(SubmissionState.ACCEPTED, fixture.store.rows.getValue(pending.clientKey).state)
            fixture.host.acknowledge(pending.clientKey)
            assertEquals(1, fixture.store.rows.size)
            fixture.host.update(installation.copy(status = "disabled", tools = emptyList()))
            assertIs<InvocationOutcome.Completed>(fixture.host.retry(pending.clientKey))
            assertEquals(SubmissionState.RESOLVED, fixture.store.rows.getValue(pending.clientKey).state)
            assertIs<InvocationOutcome.Completed>(fixture.host.retry(pending.clientKey))
            assertEquals(1, methods.count { it == HttpMethod.Post })
            fixture.host.acknowledge(pending.clientKey)
            assertTrue(fixture.store.rows.isEmpty())
        } finally { fixture.close() }
    }

    @Test
    fun unknownWriteRecoveryOnlyLooksUpAndExplicitRetryReusesTheExactBytesAndKey() = runTest {
        val posted = mutableListOf<Pair<String?, ByteArray>>()
        var lookups = 0
        val fixture = fixture(backgroundScope, MockEngine { request ->
            if (request.method == HttpMethod.Post) {
                posted += request.headers["Idempotency-Key"] to body(request)
                if (posted.size == 1) throw IOException("synthetic lost response")
                respond("""{"state":"completed","data":null}""", headers = json)
            } else {
                lookups++
                respond("{}", HttpStatusCode.NotFound, json)
            }
        })
        try {
            val unknown = assertIs<InvocationOutcome.Unknown>(fixture.host.invoke("sync.refill", JsonObject(mapOf("note" to JsonPrimitive("synthetic")))))
            val key = assertNotNull(unknown.clientKey)
            assertFalse(unknown.notFound)
            fixture.host.restore()
            assertEquals(1, posted.size)
            assertEquals(1, lookups)
            assertTrue(fixture.host.pendingWrites.value.single().notFound)
            assertIs<InvocationOutcome.Completed>(fixture.host.retry(key))
            assertEquals(2, lookups)
            assertEquals(listOf(key, key), posted.map { it.first })
            assertContentEquals(posted[0].second, posted[1].second)
        } finally { fixture.close() }
    }

    @Test
    fun failedLookupAndRevokedAccessCannotAuthorizeASecondPost() = runTest {
        var posts = 0
        val fixture = fixture(backgroundScope, MockEngine { request ->
            if (request.method == HttpMethod.Post) posts++
            throw IOException("synthetic unavailable")
        })
        try {
            val unknown = assertIs<InvocationOutcome.Unknown>(fixture.host.invoke("sync.refill"))
            val key = assertNotNull(unknown.clientKey)
            assertIs<InvocationOutcome.Unknown>(fixture.host.retry(key))
            assertEquals(1, posts)
            fixture.host.update(installation.copy(status = "disabled"))
            assertEquals("dependency_unavailable", assertIs<InvocationOutcome.Rejected>(fixture.host.retry(key)).error.code)
            assertEquals(1, posts)
        } finally { fixture.close() }
    }

    @Test
    fun removedOrIncompatibleToolCanOnlyLookUpAnUnknownWrite() = runTest {
        for (changed in listOf(emptyList(), listOf(PluginTool("sync.refill", "2.0.0", "write")))) {
            var posts = 0
            var lookups = 0
            var found = false
            val fixture = fixture(backgroundScope, MockEngine { request ->
                if (request.method == HttpMethod.Post) {
                    posts++
                    throw IOException("synthetic response lost")
                }
                lookups++
                if (found) respond("""{"state":"completed","data":null}""", headers = json)
                else respond("{}", HttpStatusCode.NotFound, json)
            })
            try {
                val key = assertNotNull(assertIs<InvocationOutcome.Unknown>(fixture.host.invoke("sync.refill")).clientKey)
                fixture.host.update(installation.copy(tools = changed))
                assertEquals("tool_unavailable", assertIs<InvocationOutcome.Rejected>(fixture.host.retry(key)).error.code)
                assertEquals(1, posts)
                assertEquals(1, lookups)
                found = true
                assertIs<InvocationOutcome.Completed>(fixture.host.retry(key))
                assertEquals(1, posts)
                assertEquals(2, lookups)
            } finally { fixture.close() }
        }
    }

    @Test
    fun structuredForbiddenSurvivesIdentityRefreshForReadsWritesAndConfiguration() = runTest {
        for (action in listOf("read", "write", "configuration")) {
            var identityReads = 0
            val fixture = fixture(backgroundScope, MockEngine { request ->
                if (request.url.encodedPath == "/v1/me") {
                    identityReads++
                    respond("""{"id":"owner","login":"owner","name":"Synthetic","locale":"ru","timezone":"UTC","system_admin":false,"revision":2,"device_id":"device","session_expires_at":"2099-01-01T00:00:00Z"}""", headers = json)
                } else respond("""{"contract":"assistant.rejected/v1","error":"permission_denied","detail":{"code":"permission_denied","message":"Нет доступа","recovery":"reauthenticate","field_errors":[{"path":"coin","code":"forbidden"}]}}""",
                    HttpStatusCode.Forbidden, json)
            })
            try {
                val outcome = assertIs<InvocationOutcome.Rejected>(when (action) {
                    "read" -> fixture.host.invoke("portfolio.get")
                    "write" -> fixture.host.invoke("sync.refill")
                    else -> fixture.host.configuration.put(JsonObject(emptyMap()), mapOf("api_key" to "synthetic-secret-only"), 2)
                })
                assertEquals(403, outcome.status)
                assertEquals("permission_denied", outcome.error.code)
                assertEquals("Нет доступа", outcome.error.message)
                assertEquals("reauthenticate", outcome.error.recovery)
                assertEquals("coin", outcome.error.fieldErrors.single().path)
                assertEquals(1, identityReads)
                assertNotNull(fixture.sessions.lease())
                assertTrue(fixture.store.rows.isEmpty())
                assertEquals(0, fixture.store.draftWrites)
            } finally { fixture.close() }
        }
    }

    @Test
    fun configurationRevisionsAndSecretsStayOutsideTheDurableJournal() = runTest {
        val calls = mutableListOf<Triple<HttpMethod, String?, String>>()
        val fixture = fixture(backgroundScope, MockEngine { request ->
            calls += Triple(request.method, request.headers["If-Match"], body(request).decodeToString())
            when (request.method) {
                HttpMethod.Get -> respond("""{"contract":"assistant.configuration/v1","configured":false,"revision":0,"public":{},"secret_state":{}}""", headers = json)
                HttpMethod.Put -> {
                    assertNotNull(request.headers["Idempotency-Key"])
                    assertEquals("synthetic-key-only", pluginObject(body(request))?.get("secret")?.let { it as JsonObject }?.pluginString("api_key"))
                    respond("""{"state":"completed","data":{"revision":3}}""", headers = json)
                }
                else -> {
                    assertEquals("0", request.url.parameters["purge"])
                    respond("""{"state":"completed","data":{"revision":4}}""", headers = json)
                }
            }
        })
        try {
            assertIs<InvocationOutcome.Completed>(fixture.host.configuration.get())
            assertIs<InvocationOutcome.Completed>(fixture.host.configuration.put(JsonObject(mapOf("environment" to JsonPrimitive("mainnet"))),
                mapOf("api_key" to "synthetic-key-only", "api_secret" to "synthetic-secret-only"), expectedRevision = 2))
            assertIs<InvocationOutcome.Completed>(fixture.host.configuration.delete(3, purge = false))
            assertEquals(listOf(null, "\"2\"", "\"3\""), calls.map { it.second })
            assertTrue(fixture.store.rows.isEmpty())
            assertEquals(0, fixture.store.draftWrites)
            assertTrue(fixture.host.pendingWrites.value.isEmpty())
            assertFalse(fixture.host.toString().contains("synthetic-key-only"))
            fixture.host.update(installation.copy(configuration = PluginConfigurationAccess(false)))
            assertIs<InvocationOutcome.Rejected>(fixture.host.configuration.put(JsonObject(emptyMap()), mapOf("api_key" to "synthetic-other")))
            assertEquals(3, calls.size)
        } finally { fixture.close() }
    }

    @Test
    fun configurationConflictPreservesRevisionRefusalAndUnknownPutOnlyAllowsANewRead() = runTest {
        var puts = 0
        val methods = mutableListOf<HttpMethod>()
        val fixture = fixture(backgroundScope, MockEngine { request ->
            methods += request.method
            if (request.method == HttpMethod.Put) {
                puts++
                if (puts == 1) respond("""{"detail":{"code":"revision_conflict","recovery":"refresh_state"}}""", HttpStatusCode.Conflict, json)
                else throw IOException("synthetic configuration response lost")
            } else respond("""{"revision":5,"configured":true,"public":{}}""", headers = json)
        })
        try {
            val conflict = assertIs<InvocationOutcome.Rejected>(fixture.host.configuration.put(JsonObject(emptyMap()), expectedRevision = 3))
            assertEquals(409, conflict.status)
            assertEquals("revision_conflict", conflict.error.code)
            val unknown = assertIs<InvocationOutcome.Unknown>(fixture.host.configuration.put(JsonObject(emptyMap()), expectedRevision = 4))
            assertNull(unknown.clientKey)
            assertIs<InvocationOutcome.Completed>(fixture.host.configuration.get())
            assertEquals(listOf(HttpMethod.Put, HttpMethod.Put, HttpMethod.Get), methods)
            assertTrue(fixture.store.rows.isEmpty())
        } finally { fixture.close() }
    }

    @Test
    fun sessionAndGrantRevocationDeliverDeniedAndStopTheInstallationStream() = runTest {
        for (status in listOf(HttpStatusCode.Unauthorized, HttpStatusCode.Forbidden)) {
            var streams = 0
            var identityReads = 0
            val fixture = fixture(backgroundScope, MockEngine { request ->
                if (request.url.encodedPath == "/v1/me") {
                    identityReads++
                    respond("""{"id":"owner","login":"owner","name":"Synthetic","locale":"ru","timezone":"UTC","system_admin":false,"revision":2,"device_id":"device","session_expires_at":"2099-01-01T00:00:00Z"}""", headers = json)
                } else {
                    streams++
                    respond("{}", status, json)
                }
            })
            try {
                assertEquals(listOf(PluginEvent.Denied(status.value)), fixture.host.observe().toList())
                assertEquals(1, streams)
                assertEquals(if (status == HttpStatusCode.Forbidden) 1 else 0, identityReads)
                if (status == HttpStatusCode.Unauthorized) assertNull(fixture.sessions.lease())
            } finally { fixture.close() }
        }
    }

    private suspend fun fixture(scope: CoroutineScope, engine: MockEngine): Fixture {
        val store = MemoryJournal()
        val namespace = AccountNamespace("synthetic", "https://assistant.example", "owner")
        val identity = NativeIdentity("owner", "owner", "Synthetic", "ru", "UTC", false, 1, "device", "2099-01-01T00:00:00Z")
        val stored = StoredSession(namespace, "synthetic-native-credential", identity, "device", "session", "2099-01-01T00:00:00Z")
        val secrets = object : SessionSecretStore {
            var record: StoredSession? = stored
            override suspend fun load(instance: String, origin: String): StoredSession? = record
            override suspend fun save(session: StoredSession) { record = session }
            override suspend fun delete(instance: String, origin: String) { record = null }
        }
        val sessions = SessionManager(namespace.instance, namespace.origin, secrets, store::clearAccount, { 1_000 })
        val http = NativeHttpClient(engine, namespace.origin, sessions, { 1_000 })
        sessions.restore()
        val coordinator = SubmissionCoordinator(store, http, sessions, { 1_000 })
        return Fixture(sessions, http, store, NativePluginHost(scope, sessions, http, coordinator, installation,
            firstBackoffMs = 1, clockMs = { 1_000 }))
    }

    private data class Fixture(val sessions: SessionManager, val http: NativeHttpClient, val store: MemoryJournal, val host: NativePluginHost) {
        fun close() { host.shutdown(); http.close() }
    }

    private class MemoryJournal : JournalStore {
        val rows = mutableMapOf<String, Submission>()
        var draftWrites = 0
        var cursorWrites = 0
        override suspend fun insertSubmission(entry: Submission) { check(entry.key !in rows); rows[entry.key] = entry }
        override suspend fun saveSubmission(entry: Submission) { rows[entry.key] = entry }
        override suspend fun submission(namespace: AccountNamespace, key: String) = rows[key]?.takeIf { it.namespace == namespace }
        override suspend fun submissions(namespace: AccountNamespace) = rows.values.filter { it.namespace == namespace }
        override suspend fun deleteSubmission(namespace: AccountNamespace, key: String) { rows.remove(key) }
        override suspend fun saveDraft(draft: Draft) { draftWrites++ }
        override suspend fun draft(namespace: AccountNamespace, target: String): Draft? = null
        override suspend fun deleteDraft(namespace: AccountNamespace, target: String) = Unit
        override suspend fun readCursor(namespace: AccountNamespace): String? = null
        override suspend fun writeCursor(namespace: AccountNamespace, cursor: String) { cursorWrites++ }
        override suspend fun clearAccount(namespace: AccountNamespace) { rows.entries.removeAll { it.value.namespace == namespace } }
    }

    companion object {
        private val json = headersOf("Content-Type", "application/json")
        private val installation = PluginInstallation("synthetic.installation~1", PluginPackage("trading", "1.0.0"), "Трейдинг", "ready",
            tools = listOf(PluginTool("portfolio.get", "1.2.0", "read"), PluginTool("sync.refill", "1.0.0", "write")),
            configuration = PluginConfigurationAccess(true))
        private fun body(request: HttpRequestData): ByteArray =
            (request.body as? OutgoingContent.ByteArrayContent)?.bytes()?.copyOf() ?: byteArrayOf()
    }
}
