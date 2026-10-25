package assistant.mobile.core.assistant

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
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class AssistantInteractionsTest {
    @Test
    fun controlsRequireExactIdentityActionAndSupportedExecution() {
        val body = controlBody()
        val response = controlReceipt(KEY, "settled", "unknown").encodeToByteArray()
        val receipt = validateAssistantControlReceipt(body, response, KEY)
        assertEquals("unknown", receipt.execution?.effects)
        assertFalse(requireNotNull(receipt.execution).safeToResubmit)
        for (changed in listOf(
            response.decodeToString().replace(GENERATION, "another-generation"),
            response.decodeToString().replace("\"action\":\"stop\"", "\"action\":\"withdraw\""),
            response.decodeToString().replace("\"effects\":\"unknown\"", "\"effects\":\"future_effect\""),
        )) assertFailsWith<IllegalArgumentException> { validateAssistantControlReceipt(body, changed.encodeToByteArray(), KEY) }
        val item = entry(ASSISTANT_CONTROLS, body).copy(state = SubmissionState.RESOLVED, response = response).controlItem(false, 0)!!
        assertEquals(AssistantControlStatus.Settled, item.status)
        assertTrue(item.unknownOutcome)
        assertFalse(item.canRetry)
        assertFalse(item.canCheck)
    }

    @Test
    fun pendingEligibilityUsesAuthoritativeGenerationAndStopFence() {
        val running = message(TARGET, "processing")
        assertTrue(running.supportsClarification())
        assertEquals(setOf(AssistantControlAction.Stop), running.availableControls())
        val queued = running.copy(state = "queued")
        assertFalse(queued.supportsClarification())
        assertEquals(setOf(AssistantControlAction.Withdraw), queued.availableControls())
        for (ineligible in listOf(running.copy(stopRequested = true), running.copy(runGeneration = null), running.copy(state = "completed"))) {
            assertFalse(ineligible.supportsClarification())
            assertTrue(ineligible.availableControls().isEmpty())
        }
    }

    @Test
    fun lostStopReceiptCanOnlyResendAfterExplicitLookup404WithIdenticalBytesAndKey() = runTest {
        var lookupStatus = HttpStatusCode.ServiceUnavailable
        val bodies = mutableListOf<ByteArray>()
        val keys = mutableListOf<String>()
        var gets = 0
        val fixture = fixture(backgroundScope, MockEngine { request ->
            if (request.method.value == "POST") {
                bodies += requestBytes(request)
                keys += request.headers["Idempotency-Key"]!!
                if (bodies.size == 1) throw IOException("synthetic lost stop response")
                respond(controlReceipt(keys.last(), "settled", "unknown"), HttpStatusCode.Accepted)
            } else {
                gets++
                respond("", lookupStatus)
            }
        })
        try {
            runCurrent()
            fixture.controller.updateMessages(listOf(message(TARGET, "processing")))
            fixture.controller.control(TARGET, AssistantControlAction.Stop)
            val pending = fixture.controller.state.value.controls.single()
            assertEquals(AssistantControlStatus.Unknown, pending.status)
            fixture.controller.control(TARGET, AssistantControlAction.Stop)
            assertEquals(1, bodies.size)
            fixture.controller.retry(pending.key)
            assertEquals(1, bodies.size)
            assertEquals(AssistantControlStatus.Unknown, fixture.controller.state.value.controls.single().status)
            lookupStatus = HttpStatusCode.NotFound
            fixture.controller.retry(pending.key)
            assertEquals(2, bodies.size)
            assertEquals(2, gets)
            assertEquals(1, keys.distinct().size)
            assertContentEquals(bodies[0], bodies[1])
            val settled = fixture.controller.state.value.controls.single()
            assertEquals(AssistantControlStatus.Settled, settled.status)
            assertTrue(settled.unknownOutcome)
            assertFalse(settled.canRetry)
        } finally { fixture.http.close() }
    }

    @Test
    fun unknownFutureControlReceiptRemainsUncertainUntilExactLookup() = runTest {
        var key = ""
        var posts = 0
        val fixture = fixture(backgroundScope, MockEngine { request ->
            if (request.method.value == "POST") {
                posts++
                key = request.headers["Idempotency-Key"]!!
                respond(controlReceipt(key, "future_state"), HttpStatusCode.Accepted)
            } else respond(controlReceipt(key, "settled"), HttpStatusCode.OK)
        })
        try {
            runCurrent()
            fixture.controller.updateMessages(listOf(message(TARGET, "processing")))
            fixture.controller.control(TARGET, AssistantControlAction.Stop)
            assertEquals(AssistantControlStatus.Unknown, fixture.controller.state.value.controls.single().status)
            assertEquals(SubmissionState.UNCERTAIN, fixture.store.submissions(record().namespace).single().state)
            fixture.controller.check(key)
            assertEquals(1, posts)
            assertEquals(AssistantControlStatus.Settled, fixture.controller.state.value.controls.single().status)
        } finally { fixture.http.close() }
    }

    @Test
    fun lateControlResponseCannotRepopulateAnotherAccountsState() = runTest {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val secrets = InteractionSecrets(record())
        val fixture = fixture(backgroundScope, MockEngine { request ->
            entered.complete(Unit)
            withContext(NonCancellable) { release.await() }
            respond(controlReceipt(request.headers["Idempotency-Key"]!!, "settled"))
        }, secrets)
        try {
            runCurrent()
            fixture.controller.updateMessages(listOf(message(TARGET, "processing")))
            val action = async { fixture.controller.control(TARGET, AssistantControlAction.Stop) }
            entered.await()
            secrets.value = record("b")
            fixture.sessions.restore()
            runCurrent()
            assertEquals(record("b").namespace, fixture.controller.state.value.namespace)
            assertTrue(fixture.controller.state.value.controls.isEmpty())
            release.complete(Unit)
            action.await()
            assertTrue(fixture.controller.state.value.controls.isEmpty())
            assertTrue(fixture.store.submissions(record("b").namespace).isEmpty())
        } finally { release.complete(Unit); fixture.http.close() }
    }
}

private data class InteractionFixture(
    val controller: AssistantInteractionsController,
    val sessions: SessionManager,
    val http: NativeHttpClient,
    val submissions: SubmissionCoordinator,
    val store: InteractionJournal,
)

private suspend fun fixture(
    scope: CoroutineScope,
    engine: MockEngine,
    secrets: InteractionSecrets = InteractionSecrets(record()),
    changed: suspend (Set<String>) -> Unit = {},
): InteractionFixture {
    val store = InteractionJournal()
    val sessions = SessionManager("synthetic", ORIGIN, secrets, store::clearAccount, { 0 })
    val http = NativeHttpClient(engine, ORIGIN, sessions, { 0 })
    sessions.restore()
    val submissions = SubmissionCoordinator(store, http, sessions, { 0 })
    return InteractionFixture(AssistantInteractionsController(scope, sessions, http, submissions, changed, { 0 }), sessions, http, submissions, store)
}

private const val ORIGIN = "https://assistant.invalid"
private const val TARGET = "11111111-1111-4111-8111-111111111111"
private const val GENERATION = "33333333-3333-4333-8333-333333333333"
private const val KEY = "44444444-4444-4444-8444-444444444444"
private const val DATE = "2026-10-16T00:00:00Z"
private fun message(id: String, state: String) = AssistantMessage(id, DATE, state = state,
    text = "synthetic message", runGeneration = GENERATION, canClarify = true)
private fun controlBody() = """{"message_id":"$TARGET","generation":"$GENERATION","action":"stop"}""".encodeToByteArray()
private fun controlReceipt(key: String, state: String, effects: String = "none") =
    """{"id":"control","submission_key":"$key","message_id":"$TARGET","generation":"$GENERATION","intent_revision":0,"action":"stop","state":"$state","reason":null,"admitted_at":"$DATE","settled_at":null,"execution":{"effects":"$effects","completion":"failed","safe_to_resubmit":${effects == "none"}}}"""
private fun entry(endpoint: String, body: ByteArray) = Submission(record().namespace, KEY, endpoint, "$endpoint/by-key/$KEY", "POST",
    body, "a", emptyMap(), null, SubmissionState.ACCEPTED, 0)
private fun requestBytes(request: HttpRequestData): ByteArray = (request.body as OutgoingContent.ByteArrayContent).bytes()
private fun record(user: String = "a"): StoredSession {
    val identity = NativeIdentity(user, user, user, "ru", "UTC", false, 1, "device-$user", "2099-01-01T00:00:00Z")
    return StoredSession(AccountNamespace("synthetic", ORIGIN, user), "credential-$user", identity,
        "device-$user", "session-$user", identity.sessionExpiresAt)
}
private class InteractionSecrets(var value: StoredSession?) : SessionSecretStore {
    override suspend fun load(instance: String, origin: String) = value
    override suspend fun save(session: StoredSession) { value = session }
    override suspend fun delete(instance: String, origin: String) { value = null }
}
private class InteractionJournal : JournalStore {
    private val entries = mutableMapOf<Pair<AccountNamespace, String>, Submission>()
    private val drafts = mutableMapOf<Pair<AccountNamespace, String>, Draft>()
    private val cursors = mutableMapOf<AccountNamespace, String>()
    override suspend fun insertSubmission(entry: Submission) { check(entries.putIfAbsent(entry.namespace to entry.key, entry) == null) }
    override suspend fun saveSubmission(entry: Submission) { entries[entry.namespace to entry.key] = entry }
    override suspend fun submission(namespace: AccountNamespace, key: String) = entries[namespace to key]
    override suspend fun submissions(namespace: AccountNamespace) = entries.values.filter { it.namespace == namespace }
    override suspend fun deleteSubmission(namespace: AccountNamespace, key: String) { entries.remove(namespace to key) }
    override suspend fun saveDraft(draft: Draft) { drafts[draft.namespace to draft.target] = draft }
    override suspend fun draft(namespace: AccountNamespace, target: String) = drafts[namespace to target]
    override suspend fun deleteDraft(namespace: AccountNamespace, target: String) { drafts.remove(namespace to target) }
    override suspend fun readCursor(namespace: AccountNamespace) = cursors[namespace]
    override suspend fun writeCursor(namespace: AccountNamespace, cursor: String) { cursors[namespace] = cursor }
    override suspend fun clearAccount(namespace: AccountNamespace) {
        entries.keys.removeAll { it.first == namespace }; drafts.keys.removeAll { it.first == namespace }; cursors.remove(namespace)
    }
}
