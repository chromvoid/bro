package assistant.mobile.core.sharing

import assistant.mobile.core.*
import assistant.mobile.core.journal.*
import io.ktor.client.engine.mock.*
import io.ktor.client.request.HttpRequestData
import io.ktor.http.*
import java.io.IOException
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.*
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class SharingControllerTest {
    @Test
    fun twoUsersSeeOnlyExplicitSummaryOrDetailAndRevocationClearsTheirNextRead() = runTest {
        val fixture = SharingFixture(this)
        try {
            fixture.start()
            fixture.b.controller.activate(SharingSurface.Shared, sharedId = SHARE_RESOURCE)
            runCurrent()
            assertTrue(fixture.b.current.gone)
            assertNull(fixture.b.current.projection.data)
            assertFalse(fixture.b.requests.any { it.url.encodedPath == "/v1/shared/$SHARE_RESOURCE" })
            assertEquals(SHARE_B, fixture.a.controller.searchPerson(" @synthetic-b ")!!.id)
            assertTrue(fixture.a.controller.grant(SHARE_RESOURCE, SHARE_B, SharingPermission.Summary, 1))
            fixture.b.controller.refresh()
            assertEquals("summary", fixture.b.current.projection.data!!.permission)
            assertNull(fixture.b.current.projection.data!!.profile)
            assertTrue(fixture.a.controller.grant(SHARE_RESOURCE, SHARE_B, SharingPermission.Detail, 2))
            fixture.b.controller.invalidate(EventInvalidation.Domains(setOf(EventDomain.Shared)))
            assertNotNull(fixture.b.current.projection.data!!.profile)
            assertTrue(fixture.a.controller.revoke(SHARE_RESOURCE, SHARE_B, 3))
            fixture.b.controller.refresh()
            assertTrue(fixture.b.current.gone)
            assertNull(fixture.b.current.projection.data)
            assertTrue(fixture.b.current.opened.isEmpty())
            assertTrue(fixture.a.store.entries.isEmpty())
            assertTrue(fixture.b.store.entries.isEmpty())
        } finally { fixture.close() }
    }

    @Test
    fun revisionConflictRefreshesWithoutResendingAndNextExplicitActionUsesNewRevision() = runTest {
        val fixture = SharingFixture(this)
        try {
            fixture.start()
            fixture.server.personalRevision = 2
            assertFalse(fixture.a.controller.grant(SHARE_RESOURCE, SHARE_B, SharingPermission.Detail, 1))
            assertEquals(GeneratedStrings.Access.changed, fixture.a.current.error)
            assertEquals(2L, fixture.a.current.overview.data!!.sections.single().revision)
            assertTrue(fixture.a.current.pending.isEmpty())
            assertNull(fixture.server.permission)
            assertTrue(fixture.a.controller.grant(SHARE_RESOURCE, SHARE_B, SharingPermission.Detail, 2))
            assertEquals(listOf("1", "2"), fixture.a.posts.map { body(it).getValue("revision").jsonPrimitive.content })
            assertEquals(2, fixture.a.posts.map { it.headers["Idempotency-Key"] }.distinct().size)
            assertTrue(fixture.a.admitted.all { it.state == SubmissionState.SENDING && it.lookup == null && it.revisions[SHARE_RESOURCE] != null })
        } finally { fixture.close() }
    }

    @Test
    fun lostReceiptOnlyRereadsAndNeverPostsAgainOnCheckEventsOrRecreation() = runTest {
        val fixture = SharingFixture(this)
        try {
            fixture.start()
            fixture.server.dropNextWrite = true
            assertFalse(fixture.a.controller.grant(SHARE_RESOURCE, SHARE_B, SharingPermission.Summary, 1))
            val entry = fixture.a.current.pending.single()
            assertEquals(SubmissionState.UNCERTAIN, entry.state)
            assertEquals(1, fixture.a.posts.size)
            assertEquals("summary", fixture.server.permission)
            fixture.a.controller.checkPending(entry.key)
            fixture.a.controller.invalidate(EventInvalidation.All)
            fixture.a.submissions.recover(fixture.a.sessions.lease()!!)
            assertEquals(1, fixture.a.posts.size)
            assertEquals(2L, fixture.a.current.overview.data!!.sections.single().revision)
            fixture.a.controller.forgetPending(entry.key)
            assertTrue(fixture.a.current.pending.isEmpty())
            assertTrue(fixture.a.store.entries.isEmpty())
            assertEquals(1, fixture.a.posts.size)
        } finally { fixture.close() }
    }

    @Test
    fun incomingRevocationInvalidatesInFlightDetailAndA404DoesNotRetainTheProjection() = runTest {
        val fixture = SharingFixture(this)
        try {
            fixture.server.permission = "detail"
            fixture.start()
            fixture.b.controller.activate(SharingSurface.Shared, sharedId = SHARE_RESOURCE)
            runCurrent()
            assertNotNull(fixture.b.current.projection.data!!.profile)
            val gate = CompletableDeferred<Unit>()
            fixture.server.projectionGate = gate
            val oldRead = async { fixture.b.controller.refresh() }
            runCurrent()
            assertNull(fixture.b.current.projection.data)
            fixture.server.permission = null
            val invalidation = async { fixture.b.controller.invalidate(EventInvalidation.Domains(setOf(EventDomain.Shared))) }
            runCurrent()
            gate.complete(Unit)
            oldRead.await(); invalidation.await()
            assertNull(fixture.b.current.projection.data)
            assertTrue(fixture.b.current.gone)
            fixture.server.permission = "detail"
            fixture.server.projectionGate = null
            fixture.b.controller.refresh()
            assertNotNull(fixture.b.current.projection.data)
            fixture.server.denyProjection = true
            fixture.b.controller.refresh()
            assertTrue(fixture.b.current.gone)
            assertNull(fixture.b.current.projection.data)
        } finally { fixture.close() }
    }

}

private const val SHARE_A = "11111111-1111-4111-8111-111111111111"
private const val SHARE_B = "55555555-5555-4555-8555-555555555555"
private const val SHARE_RESOURCE = "66666666-6666-4666-8666-666666666666"
private const val SHARE_LIST = "77777777-7777-4777-8777-777777777777"
private const val SHARE_SPACE = "44444444-4444-4444-8444-444444444444"
private fun body(request: HttpRequestData) = NativeJson.parseToJsonElement(featureBody(request.body).decodeToString()).jsonObject

private class SharingServer {
    var permission: String? = null
    var personalRevision = 1L
    var listRevision = 1L
    var defaultAccess = "edit"
    var listPermission: String? = null
    var dropNextWrite = false
    var failOverviewAfterWrite = false
    var denyProjection = false
    var projectionGate: CompletableDeferred<Unit>? = null
    suspend fun reply(user: String, request: HttpRequestData): Pair<String, HttpStatusCode> {
        val path = request.url.encodedPath
        fun okay(json: String) = json to HttpStatusCode.OK
        fun missing() = "{}" to HttpStatusCode.NotFound
        if (request.method == HttpMethod.Get) return when {
            path == "/v1/me/sharing" -> if (failOverviewAfterWrite && personalRevision > 1) "{}" to HttpStatusCode.ServiceUnavailable else okay(buildJsonObject {
                putJsonArray("sections") { if (user == SHARE_A) add(buildJsonObject { put("id", SHARE_RESOURCE); put("name", "personal"); put("owner_id", SHARE_A); put("revision", personalRevision) }) }
                putJsonArray("grants") { if (user == SHARE_A && permission != null) add(buildJsonObject {
                    put("resource_id", SHARE_RESOURCE); put("user_id", SHARE_B); put("name", "Synthetic B"); put("login", "synthetic-b"); put("permission", permission)
                }) }
                putJsonArray("shared") { if (user == SHARE_B && permission != null) add(buildJsonObject {
                    put("id", SHARE_RESOURCE); put("name", "personal"); put("owner_id", SHARE_A); put("owner_name", "Synthetic A"); put("owner_login", "synthetic-a"); put("permission", permission)
                }) }
            }.toString())
            path == "/v1/shared/$SHARE_RESOURCE" -> {
                val captured = permission
                projectionGate?.await()
                if (user != SHARE_B || captured == null || denyProjection) missing() else okay(sharingPersonal(captured).decodeToString())
            }
            path == "/v1/spaces" -> okay("""{"spaces":[{"id":"$SHARE_SPACE","name":"Synthetic family","role":"${if (user == SHARE_A) "owner" else "member"}","revision":1}]}""")
            path == "/v1/tasks/lists" -> okay("""{"lists":[{"id":"$SHARE_LIST","name":"Synthetic list","space_id":"$SHARE_SPACE","effective":"${if (user == SHARE_A) "manage" else listPermission ?: defaultAccess}","member_access":"$defaultAccess","revision":$listRevision}]}""")
            path == "/v1/relationships" -> okay(if (request.url.parameters["login"] == null) """{"relationships":[]}""" else """{"user":{"id":"$SHARE_B","name":"Synthetic B","login":"synthetic-b"}}""")
            path == "/v1/spaces/$SHARE_SPACE/members" -> okay("""{"members":[{"user_id":"$SHARE_A","name":"Synthetic A","role":"owner"},{"user_id":"$SHARE_B","name":"Synthetic B","role":"member"}]}""")
            path == "/v1/resources/$SHARE_LIST/grants" -> if (user != SHARE_A && listPermission != "manage") missing() else okay(buildJsonObject {
                putJsonObject("resource") { put("id", SHARE_LIST); put("name", "Synthetic list"); put("space_id", SHARE_SPACE); put("member_access", defaultAccess); put("revision", listRevision) }
                putJsonArray("grants") { listPermission?.let { add(buildJsonObject { put("user_id", SHARE_B); put("name", "Synthetic B"); put("permission", it) }) } }
            }.toString())
            else -> error("Unexpected sharing GET $path")
        }
        val command = body(request)
        val list = path.contains(SHARE_LIST)
        val revision = if (list) listRevision else personalRevision
        if (command.getValue("revision").jsonPrimitive.long != revision) return """{"message":"Права уже изменены"}""" to HttpStatusCode.Conflict
        if (list) {
            if (user != SHARE_A && listPermission != "manage") return missing()
            if (path.endsWith("/grants")) listPermission = command.getValue("permission").takeUnless { it == JsonNull }?.jsonPrimitive?.content
            else defaultAccess = command.getValue("member_access").jsonPrimitive.content
            listRevision++
        } else {
            if (user != SHARE_A) return missing()
            permission = command.getValue("permission").takeUnless { it == JsonNull }?.jsonPrimitive?.content
            personalRevision++
        }
        if (dropNextWrite) { dropNextWrite = false; throw IOException("Synthetic lost receipt") }
        return okay("""{"revision":${revision + 1}}""")
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
private class SharingFixture(test: TestScope) {
    val server = SharingServer()
    val a = SharingClient(test, server, SHARE_A)
    val b = SharingClient(test, server, SHARE_B)
    suspend fun start() { a.sessions.restore(); b.sessions.restore(); a.controller.activate(); b.controller.activate(SharingSurface.Shared); a.controller.refresh(); b.controller.refresh(); testSchedulerRun() }
    private val testSchedulerRun: () -> Unit = { test.runCurrent() }
    fun close() { a.close(); b.close() }
}

@OptIn(ExperimentalCoroutinesApi::class)
private class SharingClient(test: TestScope, server: SharingServer, user: String) {
    val store = SharingMemoryJournal()
    private val record = featureRecord().let { it.copy(namespace = it.namespace.copy(userId = user), identity = it.identity.copy(id = user,
        scopes = listOf("personal.read", "nutrition.read", "tasks.read", "tasks.write"))) }
    val sessions = SessionManager("synthetic", FEATURE_ORIGIN, FeatureSessionSecrets(record), store::clearAccount, { FEATURE_NOW })
    val requests = mutableListOf<HttpRequestData>()
    val posts get() = requests.filter { it.method == HttpMethod.Post }
    val admitted = mutableListOf<Submission>()
    val http = NativeHttpClient(MockEngine(MockEngineConfig().apply {
        dispatcher = StandardTestDispatcher(test.testScheduler)
        addHandler { request ->
            requests += request
            assertEquals(user, request.headers["X-Expected-User"])
            if (request.method == HttpMethod.Post) {
                val key = assertNotNull(request.headers["Idempotency-Key"])
                val entry = assertNotNull(store.submission(record.namespace, key))
                assertEquals(SubmissionState.SENDING, entry.state)
                assertContentEquals(featureBody(request.body), entry.body)
                admitted += entry
            }
            val (json, status) = server.reply(user, request)
            respond(json, status)
        }
    }), FEATURE_ORIGIN, sessions, { FEATURE_NOW })
    val submissions = SubmissionCoordinator(store, http, sessions, { FEATURE_NOW }, SubmissionReceiptValidator(::validateSharingSubmissionReceipt))
    val controller = SharingController(test.backgroundScope, sessions, http, submissions, { FEATURE_NOW })
    val current get() = assertNotNull(controller.state.value)
    fun close() { controller.close(); http.close() }
}

private class SharingMemoryJournal : JournalStore {
    val entries = mutableMapOf<Pair<AccountNamespace, String>, Submission>()
    override suspend fun insertSubmission(entry: Submission) { check(entries.putIfAbsent(entry.namespace to entry.key, entry) == null) }
    override suspend fun saveSubmission(entry: Submission) { check(entries.containsKey(entry.namespace to entry.key)); entries[entry.namespace to entry.key] = entry }
    override suspend fun submission(namespace: AccountNamespace, key: String) = entries[namespace to key]
    override suspend fun submissions(namespace: AccountNamespace) = entries.values.filter { it.namespace == namespace }
    override suspend fun deleteSubmission(namespace: AccountNamespace, key: String) { entries.remove(namespace to key) }
    override suspend fun saveDraft(draft: Draft) = error("Sharing must not persist personal data")
    override suspend fun draft(namespace: AccountNamespace, target: String): Draft? = null
    override suspend fun deleteDraft(namespace: AccountNamespace, target: String) = Unit
    override suspend fun readCursor(namespace: AccountNamespace): String? = null
    override suspend fun writeCursor(namespace: AccountNamespace, cursor: String) = Unit
    override suspend fun clearAccount(namespace: AccountNamespace) { entries.keys.removeAll { it.first == namespace } }
}
