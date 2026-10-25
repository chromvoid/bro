package assistant.mobile.core.assistant

import assistant.mobile.core.AccountDrafts
import assistant.mobile.core.AccountChangeEvent
import assistant.mobile.core.AccountNamespace
import assistant.mobile.core.NativeHttpClient
import assistant.mobile.core.NativeIdentity
import assistant.mobile.core.NativeJson
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
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith

@OptIn(ExperimentalCoroutinesApi::class)
class AssistantControllerTest {
    @Test
    fun editingCaptionDuringPhotoAdmissionClearsTheSentImageAndPreservesNewText() = runTest {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val store = AssistantTestJournal()
        val fixture = fixture(backgroundScope, store, MockEngine { request ->
            if (request.method.value == "POST") {
                entered.complete(Unit)
                release.await()
                respond(admission(request.headers["Idempotency-Key"]!!))
            } else respond(history(listOf(message(RUNNING, "completed"))))
        })
        try {
            fixture.controller.activate()
            fixture.controller.state.first { it.draftReady }
            fixture.controller.setDraft("Sent caption")
            fixture.controller.setDraftImage(QUEUED)
            val send = launch { assertTrue(fixture.controller.send()) }
            entered.await()
            fixture.controller.setDraft("New text belongs to the next message")
            release.complete(Unit)
            send.join()
            assertEquals("New text belongs to the next message", fixture.controller.state.value.draftText)
            assertEquals(null, fixture.controller.state.value.draftImageId)
            val saved = NativeJson.parseToJsonElement(store.draft(record().namespace, ASSISTANT_DRAFT)!!.json.decodeToString()).jsonObject
            assertEquals("New text belongs to the next message", saved.getValue("text").jsonPrimitive.content)
            assertTrue(saved["imageId"] == null || saved["imageId"].toString() == "null")
        } finally { release.complete(Unit); fixture.http.close() }
    }

    @Test
    fun replyDraftSurvivesNavigationAndNewReplyDuringAdmissionBelongsToTheNextSend() = runTest {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var sent: JsonObject? = null
        val store = AssistantTestJournal()
        val fixture = fixture(backgroundScope, store, MockEngine { request ->
            if (request.method.value == "POST") {
                sent = NativeJson.parseToJsonElement(body(request).decodeToString()).jsonObject
                entered.complete(Unit)
                release.await()
                respond(admission(request.headers["Idempotency-Key"]!!))
            } else respond(history(listOf(message(RUNNING, "completed"))))
        })
        try {
            fixture.controller.activate()
            fixture.controller.state.first { it.draftReady }
            fixture.controller.replyTo("original-reply", "Original")
            fixture.controller.setDraft("First answer")
            fixture.controller.deactivate()
            fixture.controller.activate()
            fixture.controller.state.first { it.draftReady }
            assertEquals("original-reply", fixture.controller.state.value.replyTarget?.messageId)
            val send = launch { fixture.controller.send() }
            entered.await()
            fixture.controller.replyTo("next-reply", "Next")
            fixture.controller.setDraft("Second answer")
            release.complete(Unit)
            send.join()
            assertEquals("original-reply", sent!!.getValue("reply_to_message_id").jsonPrimitive.content)
            assertEquals("Second answer", fixture.controller.state.value.draftText)
            assertEquals("next-reply", fixture.controller.state.value.replyTarget?.messageId)
            val saved = NativeJson.parseToJsonElement(store.draft(record().namespace, ASSISTANT_DRAFT)!!.json.decodeToString()).jsonObject
            assertEquals("next-reply", saved.getValue("replyTarget").jsonObject.getValue("messageId").jsonPrimitive.content)
        } finally { release.complete(Unit); fixture.http.close() }
    }

    @Test
    fun confirmationUsesItsOwnReplyWithoutConsumingAnUnsentComposer() = runTest {
        var sent: JsonObject? = null
        val fixture = fixture(backgroundScope, AssistantTestJournal(), MockEngine { request ->
            if (request.method.value == "POST") {
                sent = NativeJson.parseToJsonElement(body(request).decodeToString()).jsonObject
                respond(admission(request.headers["Idempotency-Key"]!!))
            } else respond(history(listOf(message(RUNNING, "completed"))))
        })
        try {
            fixture.controller.activate()
            fixture.controller.state.first { it.draftReady }
            fixture.controller.replyTo("unsent-reply", "Draft target")
            fixture.controller.setDraft("Unsent text")
            fixture.controller.sendReply("proposal-reply", "Confirm")
            assertEquals("proposal-reply", sent!!.getValue("reply_to_message_id").jsonPrimitive.content)
            assertEquals("Confirm", sent!!.getValue("text").jsonPrimitive.content)
            assertEquals("Unsent text", fixture.controller.state.value.draftText)
            assertEquals("unsent-reply", fixture.controller.state.value.replyTarget?.messageId)
        } finally { fixture.http.close() }
    }

    @Test
    fun interruptedComposerEnvelopeIsOwnedByJournalAndCannotBeSubmittedTwice() = runTest {
        val store = AssistantTestJournal()
        val namespace = record().namespace
        store.saveDraft(Draft(namespace, ASSISTANT_DRAFT,
            """{"text":"already handed off","submissionKey":"$KEY"}""".encodeToByteArray(), 8, 0))
        store.insertSubmission(submission(namespace, SubmissionState.SENDING))
        var posts = 0
        val fixture = fixture(backgroundScope, store, MockEngine { request ->
            if (request.method.value == "POST") posts++
            respond("", HttpStatusCode.ServiceUnavailable)
        })
        try {
            fixture.controller.activate()
            runCurrent()
            fixture.controller.state.first { it.draftReady }
            assertEquals("", fixture.controller.state.value.draftText)
            assertEquals(AssistantPendingStatus.Uncertain, fixture.controller.state.value.pending.single().status)
            assertTrue(fixture.controller.state.value.pending.single().canRetry)
            fixture.controller.send()
            assertEquals(0, posts)
            assertEquals("", NativeJson.parseToJsonElement(store.draft(namespace, ASSISTANT_DRAFT)!!.json.decodeToString())
                .jsonObject.getValue("text").jsonPrimitive.content)
            assertEquals(KEY, store.submissions(namespace).single().key)
        } finally { fixture.http.close() }
    }

    @Test
    fun unknownAdmissionIsVisibleAndExplicitLookupNeverReposts() = runTest {
        var posts = 0
        var lookups = 0
        var online = false
        var sentKey = ""
        val fixture = fixture(backgroundScope, AssistantTestJournal(), MockEngine { request ->
            when {
                request.method.value == "POST" -> {
                    posts++
                    sentKey = request.headers["Idempotency-Key"]!!
                    throw IOException("synthetic admission response lost")
                }
                request.url.encodedPath.startsWith(ASSISTANT_LOOKUP) -> {
                    lookups++
                    respond(admission(sentKey))
                }
                online -> respond(history(listOf(message(RUNNING, "completed"))))
                else -> respond("", HttpStatusCode.ServiceUnavailable)
            }
        })
        try {
            fixture.controller.activate()
            runCurrent()
            fixture.controller.state.first { it.draftReady }
            fixture.controller.setDraft("synthetic unknown send")
            fixture.controller.send()
            val pending = fixture.controller.state.value.pending.single()
            assertEquals(AssistantPendingStatus.Uncertain, pending.status)
            assertEquals("", fixture.controller.state.value.draftText)
            assertTrue(pending.canCheck)
            online = true
            fixture.controller.check(pending.key)
            assertEquals(1, posts)
            assertEquals(1, lookups)
            assertEquals(listOf(RUNNING), fixture.controller.state.value.messages.map { it.id })
            assertTrue(fixture.controller.state.value.pending.isEmpty())
            assertTrue(fixture.store.submissions(record().namespace).isEmpty())
        } finally { fixture.http.close() }
    }

    @Test
    fun cancellingScreenCallerDoesNotInterruptDurableComposerHandoff() = runTest {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var posts = 0
        var admitted = false
        val fixture = fixture(backgroundScope, AssistantTestJournal(), MockEngine { request ->
            if (request.method.value == "POST") {
                posts++
                entered.complete(Unit)
                release.await()
                admitted = true
                respond(admission(request.headers["Idempotency-Key"]!!))
            } else respond(history(if (admitted) listOf(message(RUNNING, "completed")) else emptyList()))
        })
        try {
            fixture.controller.activate()
            fixture.controller.state.first { it.draftReady }
            fixture.controller.setDraft("survives navigation")
            val caller = launch { fixture.controller.send() }
            entered.await()
            caller.cancelAndJoin()
            fixture.controller.deactivate()
            release.complete(Unit)
            fixture.controller.state.first { !it.sending && it.draftText.isEmpty() }
            fixture.controller.activate()
            fixture.controller.state.first { it.messages.any { row -> row.id == RUNNING } }
            fixture.controller.send()
            assertEquals(1, posts)
            assertEquals("", fixture.controller.state.value.draftText)
            assertTrue(fixture.controller.state.value.pending.isEmpty())
        } finally { fixture.http.close() }
    }

    @Test
    fun lateHistoryFromPreviousSessionCannotReachStateOrCache() = runTest {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val cache = AssistantTestCache()
        val secrets = AssistantTestSecrets(record())
        val fixture = fixture(backgroundScope, AssistantTestJournal(), MockEngine { request ->
            if (request.headers["Authorization"] == "Bearer credential-a") {
                entered.complete(Unit)
                withContext(NonCancellable) { release.await() }
                respond(history(listOf(message(RUNNING, "completed"))))
            } else respond(history(emptyList()))
        }, secrets, cache)
        try {
            fixture.controller.activate()
            entered.await()
            secrets.value = record("b")
            fixture.sessions.restore()
            runCurrent()
            assertEquals(record("b").namespace, fixture.controller.state.value.namespace)
            assertTrue(fixture.controller.state.value.messages.isEmpty())
            release.complete(Unit)
            runCurrent()
            fixture.controller.refresh()
            assertEquals(record("b").namespace, fixture.controller.state.value.namespace)
            assertTrue(fixture.controller.state.value.messages.isEmpty())
            assertFalse(record().namespace in cache.values)
            assertTrue(cache.values.values.all { page -> page.messages.isEmpty() })
        } finally { release.complete(Unit); fixture.http.close() }
    }

    @Test
    fun sendNowReusesQueuedIdentityKeyAndBytesAfterMismatchedReceipt() = runTest {
        val bodies = mutableListOf<ByteArray>()
        val keys = mutableListOf<String>()
        var lookups = 0
        var moved = false
        fun rows() = listOf(
            message(RUNNING, "processing").copy(runGeneration = GENERATION, canClarify = true),
            message(QUEUED, if (moved) "clarification" else "queued").copy(clarificationReceipt =
                if (moved) JsonObject(mapOf("submission_key" to JsonPrimitive(keys.last()))) else null),
        )
        val fixture = fixture(backgroundScope, AssistantTestJournal(), MockEngine { request ->
            when {
                request.url.encodedPath.endsWith("/activity") -> respond("", HttpStatusCode.NotFound)
                request.url.encodedPath.startsWith("$ASSISTANT_MOVES/by-key/") -> {
                    lookups++
                    respond("", HttpStatusCode.NotFound)
                }
                request.method.value == "POST" -> {
                    bodies += body(request)
                    keys += request.headers["Idempotency-Key"]!!
                    if (bodies.size == 2) moved = true
                    respond(moveReceipt(keys.last(), if (moved) QUEUED else "wrong-source"))
                }
                else -> {
                    val exact = request.url.parameters["message_id"]
                    respond(history(rows().filter { exact == null || it.id == exact }))
                }
            }
        })
        try {
            fixture.controller.activate()
            fixture.controller.state.first { QUEUED in it.movableIds }
            fixture.controller.sendNow(QUEUED)
            val uncertain = fixture.store.submissions(record().namespace).single()
            assertEquals(SubmissionState.UNCERTAIN, uncertain.state)
            assertFailsWith<IllegalArgumentException> { fixture.submissions.acknowledge(fixture.sessions.lease()!!, uncertain.key) }
            fixture.controller.sendNow(QUEUED)
            assertEquals(2, bodies.size)
            assertEquals(1, keys.distinct().size)
            assertTrue(bodies[0].contentEquals(bodies[1]))
            assertEquals(1, lookups)
            val request = NativeJson.parseToJsonElement(bodies.first().decodeToString()).jsonObject
            assertEquals(setOf("message_id", "generation", "queued_message_id"), request.keys)
            assertEquals(QUEUED, request.getValue("queued_message_id").jsonPrimitive.content)
            assertEquals(RUNNING, request.getValue("message_id").jsonPrimitive.content)
            assertTrue(fixture.controller.state.value.movableIds.isEmpty())
            assertTrue(fixture.store.submissions(record().namespace).isEmpty())
        } finally { fixture.http.close() }
    }

    @Test
    fun definiteMoveRefusalAllowsNewExplicitActionWithNewKey() = runTest {
        val keys = mutableListOf<String>()
        val rows = listOf(message(RUNNING, "processing").copy(runGeneration = GENERATION, canClarify = true),
            message(QUEUED, "queued"))
        val fixture = fixture(backgroundScope, AssistantTestJournal(), MockEngine { request ->
            when {
                request.url.encodedPath.endsWith("/activity") -> respond("", HttpStatusCode.NotFound)
                request.method.value == "POST" -> {
                    keys += request.headers["Idempotency-Key"]!!
                    respond("", HttpStatusCode.BadRequest)
                }
                else -> respond(history(rows.filter { request.url.parameters["message_id"]?.let { id -> it.id == id } ?: true }))
            }
        })
        try {
            fixture.controller.activate()
            fixture.controller.state.first { QUEUED in it.movableIds }
            fixture.controller.sendNow(QUEUED)
            fixture.controller.sendNow(QUEUED)
            assertEquals(2, keys.size)
            assertEquals(2, keys.distinct().size)
            assertTrue(fixture.store.submissions(record().namespace).all { it.state == SubmissionState.REJECTED })
            assertTrue(QUEUED in fixture.controller.state.value.movableIds)
        } finally { fixture.http.close() }
    }

    @Test
    fun accessChangeClearsImmediatelyAndDiscardsInFlightHistoryFromSameSession() = runTest {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val cache = AssistantTestCache()
        var delayNext = false
        var revoked = false
        var activityRequests = 0
        val fixture = fixture(backgroundScope, AssistantTestJournal(), MockEngine { request ->
            when {
                request.url.encodedPath.endsWith("/activity") -> {
                    activityRequests++
                    respond("", HttpStatusCode.NotFound)
                }
                delayNext -> {
                    delayNext = false
                    entered.complete(Unit)
                    withContext(NonCancellable) { release.await() }
                    respond(history(listOf(message(RUNNING, "processing").copy(runGeneration = GENERATION))))
                }
                revoked -> respond(history(emptyList()))
                else -> respond(history(listOf(message(RUNNING, "completed"))))
            }
        }, cache = cache)
        try {
            fixture.controller.activate()
            fixture.controller.state.first { it.messages.isNotEmpty() }
            fixture.controller.refresh()
            val seen = mutableListOf<AssistantState>()
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                fixture.controller.state.collect { seen += it }
            }
            val lease = fixture.sessions.lease()!!
            delayNext = true
            val oldRead = launch { fixture.controller.refresh() }
            entered.await()
            revoked = true
            val change = launch(start = CoroutineStart.UNDISPATCHED) {
                fixture.controller.onChange(AccountChangeEvent(lease.namespace, lease.generation,
                    "2", "change", "access.changed", null))
            }
            assertTrue(fixture.sessions.lease() === lease)
            assertTrue(fixture.controller.state.value.messages.isEmpty())
            assertEquals(null, fixture.controller.state.value.activity)
            val afterClear = seen.size
            release.complete(Unit)
            oldRead.join()
            change.join()
            assertTrue(seen.drop(afterClear).all { it.messages.isEmpty() && it.activity == null })
            assertTrue(cache.values.getValue(lease.namespace).messages.isEmpty())
            assertEquals(0, activityRequests)
        } finally { release.complete(Unit); fixture.http.close() }
    }
}

private data class AssistantFixture(
    val controller: AssistantController,
    val sessions: SessionManager,
    val http: NativeHttpClient,
    val submissions: SubmissionCoordinator,
    val store: AssistantTestJournal,
)

private suspend fun fixture(
    scope: CoroutineScope,
    store: AssistantTestJournal,
    engine: MockEngine,
    secrets: AssistantTestSecrets = AssistantTestSecrets(record()),
    cache: AssistantTestCache = AssistantTestCache(),
): AssistantFixture {
    val sessions = SessionManager("synthetic", ORIGIN, secrets, store::clearAccount, { 0 })
    val http = NativeHttpClient(engine, ORIGIN, sessions, { 0 })
    sessions.restore()
    val submissions = SubmissionCoordinator(store, http, sessions, { 0 })
    return AssistantFixture(AssistantController(scope, sessions, http, submissions,
        AccountDrafts(store, sessions, { 0 }), cache), sessions, http, submissions, store)
}

private const val ORIGIN = "https://assistant.invalid"
private const val RUNNING = "11111111-1111-4111-8111-111111111111"
private const val QUEUED = "22222222-2222-4222-8222-222222222222"
private const val GENERATION = "33333333-3333-4333-8333-333333333333"
private const val KEY = "44444444-4444-4444-8444-444444444444"
private const val DATE = "2026-10-16T00:00:00Z"

private fun record(user: String = "a"): StoredSession {
    val identity = NativeIdentity(user, user, user, "ru", "UTC", false, 1, "device-$user", "2099-01-01T00:00:00Z")
    return StoredSession(AccountNamespace("synthetic", ORIGIN, user), "credential-$user", identity,
        "device-$user", "session-$user", identity.sessionExpiresAt)
}

private fun message(id: String, state: String) = AssistantMessage(id, DATE, state = state, text = "synthetic $id")
private fun submission(namespace: AccountNamespace, state: SubmissionState) = Submission(
    namespace, KEY, ASSISTANT_MESSAGES, ASSISTANT_LOOKUP + KEY, "POST",
    """{"text":"already handed off"}""".encodeToByteArray(), namespace.userId, emptyMap(),
    ASSISTANT_DRAFT, SubmissionState.ACCEPTED, 0, state = state, mayHaveBeenAdmitted = true,
)
private fun history(rows: List<AssistantMessage>): String = NativeJson.encodeToString(AssistantHistory(rows, null))
private fun admission(key: String) = """{"submission_key":"$key","message_id":"$RUNNING","admitted_at":"$DATE","state":"completed"}"""
private fun moveReceipt(key: String, source: String) = """{"id":"move","submission_key":"$key","message_id":"$RUNNING","generation":"$GENERATION","source_message_id":"$source","intent_revision":1,"state":"applied","admitted_at":"$DATE","applied_at":"$DATE"}"""
private fun body(request: HttpRequestData): ByteArray = when (val value = request.body) {
    is OutgoingContent.ByteArrayContent -> value.bytes()
    else -> error("Expected immutable JSON bytes")
}

private class AssistantTestSecrets(var value: StoredSession?) : SessionSecretStore {
    override suspend fun load(instance: String, origin: String) = value
    override suspend fun save(session: StoredSession) { value = session }
    override suspend fun delete(instance: String, origin: String) { value = null }
}

private class AssistantTestCache : AssistantHistoryCache {
    val values = mutableMapOf<AccountNamespace, AssistantHistory>()
    override suspend fun read(namespace: AccountNamespace) = values[namespace]
    override suspend fun write(namespace: AccountNamespace, page: AssistantHistory) { values[namespace] = page }
    override suspend fun clear(namespace: AccountNamespace) { values.remove(namespace) }
}

private class AssistantTestJournal : JournalStore {
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
        entries.keys.removeAll { it.first == namespace }
        drafts.keys.removeAll { it.first == namespace }
        cursors.remove(namespace)
    }
}
