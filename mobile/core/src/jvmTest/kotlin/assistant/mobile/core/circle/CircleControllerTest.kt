package assistant.mobile.core.circle

import assistant.mobile.core.AccountDrafts
import assistant.mobile.core.AccountNamespace
import assistant.mobile.core.EventDomain
import assistant.mobile.core.EventInvalidation
import assistant.mobile.core.FEATURE_ORIGIN
import assistant.mobile.core.FeatureSessionSecrets
import assistant.mobile.core.NativeHttpClient
import assistant.mobile.core.NativeJson
import assistant.mobile.core.SessionManager
import assistant.mobile.core.featureBody
import assistant.mobile.core.featureRecord
import assistant.mobile.core.journal.Draft
import assistant.mobile.core.journal.JournalStore
import assistant.mobile.core.journal.Submission
import assistant.mobile.core.journal.SubmissionCoordinator
import assistant.mobile.core.journal.SubmissionReceiptValidator
import assistant.mobile.core.journal.SubmissionState
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockEngineConfig
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
class CircleControllerTest {
    @Test
    fun directSpaceAndPrivateAssistantDraftsStaySeparateAcrossControllerRecreation() = runTest {
        val fixture = CircleControllerFixture(this)
        try {
            val privateDraft = """{"text":"Private assistant text","imageId":"private-image","transcriptionId":"private-transcription"}""".encodeToByteArray()
            fixture.store.saveDraft(Draft(fixture.namespace, "assistant.compose", privateDraft, 1, fixture.now))
            fixture.start(channel = CIRCLE_DM)
            fixture.controller.updateDraft("DM draft")
            fixture.controller.replyTo("message-1")
            runCurrent()
            fixture.start(space = CIRCLE_SPACE, channel = CIRCLE_CHAT)
            assertEquals(CircleDraft(), fixture.controller.state.value!!.draft)
            fixture.controller.updateDraft("Space draft")
            runCurrent()
            fixture.start(channel = CIRCLE_DM)
            assertEquals(CircleDraft("DM draft", "message-1"), fixture.controller.state.value!!.draft)
            fixture.recreateController()
            fixture.start(space = CIRCLE_SPACE, channel = CIRCLE_CHAT)
            assertEquals(CircleDraft("Space draft"), fixture.controller.state.value!!.draft)
            assertContentEquals(privateDraft, fixture.store.draft(fixture.namespace, "assistant.compose")!!.json)
            assertEquals(setOf("assistant.compose", CircleController.draftTarget(CIRCLE_DM), CircleController.draftTarget(CIRCLE_CHAT)), fixture.store.savedDrafts.keys.map { it.second }.toSet())
            assertTrue(fixture.messagePosts.isEmpty())
        } finally { fixture.close() }
    }

    @Test
    fun sendJournalsExactKeyedTextAndReplyBeforePostAndNeverCopiesPrivateAttachments() = runTest {
        val fixture = CircleControllerFixture(this)
        try {
            fixture.store.saveDraft(Draft(fixture.namespace, "assistant.compose", """{"text":"Private","imageId":"private-image","transcriptionId":"private-transcription"}""".encodeToByteArray(), 1, fixture.now))
            fixture.start(channel = CIRCLE_DM)
            fixture.controller.updateDraft("  Synthetic channel text  ")
            fixture.controller.replyTo("message-1")
            runCurrent()
            fixture.controller.send()
            val request = fixture.messagePosts.single()
            assertEquals(setOf("text", "reply_to_id"), requestBody(request).keys)
            assertEquals("Synthetic channel text", requestBody(request).getValue("text").jsonPrimitive.content)
            assertEquals("message-1", requestBody(request).getValue("reply_to_id").jsonPrimitive.content)
            assertNotNull(request.headers["Idempotency-Key"])
            val admitted = fixture.admitted.single()
            assertEquals("channel:$CIRCLE_DM", admitted.audience)
            assertEquals(request.headers["Idempotency-Key"], admitted.key)
            assertContentEquals(featureBody(request.body), admitted.body)
            assertEquals(SubmissionState.SENDING, admitted.state)
            assertNull(admitted.lookup)
            assertFalse(admitted.body.decodeToString().contains("private-"))
            assertEquals(CircleDraft(), fixture.controller.state.value!!.draft)
            assertTrue(fixture.controller.state.value!!.pending.isEmpty())
            assertTrue(fixture.store.submissions(fixture.namespace).isEmpty())
            assertNull(fixture.store.draft(fixture.namespace, CircleController.draftTarget(CIRCLE_DM)))
            assertNotNull(fixture.store.draft(fixture.namespace, "assistant.compose"))
        } finally { fixture.close() }
    }

    @Test
    fun lostReceiptSurvivesStartupRefreshAndCheckWithoutResendOrInventedLookup() = runTest {
        val fixture = CircleControllerFixture(this)
        try {
            fixture.start(channel = CIRCLE_DM)
            fixture.controller.updateDraft("Synthetic uncertain message")
            runCurrent()
            fixture.loseNextReceipt = true
            fixture.controller.send()
            val uncertain = fixture.controller.state.value!!.pending.single()
            assertEquals(SubmissionState.UNCERTAIN, uncertain.state)
            assertNull(uncertain.lookup)
            assertEquals(fixture.messagePosts.single().headers["Idempotency-Key"], uncertain.key)
            assertTrue(fixture.controller.state.value!!.history.data!!.messages.any { it.text == "Synthetic uncertain message" })
            fixture.recreateController()
            fixture.start(channel = CIRCLE_DM)
            fixture.controller.refresh()
            fixture.controller.checkMessage(uncertain.key)
            fixture.controller.checkMessage(uncertain.key)
            assertEquals(1, fixture.messagePosts.size)
            assertEquals(uncertain.key, fixture.controller.state.value!!.pending.single().key)
            assertFalse(fixture.requests.any { it.url.encodedPath.contains("by-key") })
            fixture.controller.updateDraft("An accidental second send")
            fixture.controller.send()
            assertEquals(1, fixture.messagePosts.size)
            fixture.controller.forgetMessage(uncertain.key)
            assertTrue(fixture.controller.state.value!!.pending.isEmpty())
            assertTrue(fixture.store.submissions(fixture.namespace).isEmpty())
        } finally { fixture.close() }
    }

    @Test
    fun accountReplacementWhileArchiveWaitsForRefreshCannotMarkNewAccountGone() = runTest {
        val fixture = CircleControllerFixture(this)
        try {
            fixture.start(space = CIRCLE_SPACE, channel = CIRCLE_CHAT)
            val gate = CircleResponseGate()
            fixture.nextHistoryGate = gate
            val refreshing = async { fixture.controller.refresh() }
            gate.started.await()
            val archiving = async { fixture.controller.archiveSpace(CIRCLE_SPACE) }
            runCurrent()
            assertEquals(1, fixture.requests.count { it.url.encodedPath.endsWith("/archive") })
            fixture.replaceAccount(CIRCLE_PEER)
            fixture.controller.activate()
            runCurrent()
            gate.release.complete(Unit)
            refreshing.await()
            archiving.await()
            runCurrent()
            val state = fixture.controller.state.value!!
            assertEquals(CIRCLE_PEER, state.namespace.userId)
            assertFalse(state.gone)
            assertNull(state.channelId)
            assertNull(state.history.data)
            assertEquals(CircleDraft(), state.draft)
        } finally { fixture.close() }
    }

    @Test
    fun history404AndChannelRevocationClearPreviouslyVisibleHistoryAndComposer() = runTest {
        val fixture = CircleControllerFixture(this)
        try {
            fixture.start(channel = CIRCLE_DM)
            fixture.controller.updateDraft("Do not show after revocation")
            runCurrent()
            fixture.historyGone = true
            fixture.controller.invalidate(EventInvalidation.Domains(setOf(EventDomain.Community)))
            assertGone(fixture.controller.state.value!!)
            fixture.controller.send()
            assertTrue(fixture.messagePosts.isEmpty())
            fixture.historyGone = false
            fixture.start(channel = CIRCLE_CHAT)
            assertFalse(fixture.controller.state.value!!.gone)
            fixture.omitActiveChannel = true
            fixture.controller.refresh()
            assertGone(fixture.controller.state.value!!)
        } finally { fixture.close() }
    }

    @Test
    fun ownerRoleChangeUsesWireRoleAndSoleOwnerCannotLeaveOrDemoteSelf() = runTest {
        val fixture = CircleControllerFixture(this)
        try {
            fixture.start(space = CIRCLE_SPACE)
            assertFalse(fixture.controller.changeRole(CIRCLE_SPACE, CIRCLE_ME, null))
            assertFalse(fixture.controller.changeRole(CIRCLE_SPACE, CIRCLE_ME, CircleRole.Member))
            assertTrue(fixture.membershipPosts.isEmpty())
            assertTrue(fixture.controller.changeRole(CIRCLE_SPACE, CIRCLE_PEER, CircleRole.Admin))
            assertEquals("admin", requestBody(fixture.membershipPosts.single()).getValue("role").jsonPrimitive.content)
            assertEquals(CircleRole.Admin, fixture.controller.state.value!!.members.data!!.first { it.userId == CIRCLE_PEER }.role)
            fixture.selfRole = CircleRole.Admin
            fixture.members = fixture.members.map { if (it.userId == CIRCLE_ME) it.copy(role = CircleRole.Admin) else it }
            fixture.controller.refresh()
            assertFalse(fixture.controller.changeRole(CIRCLE_SPACE, CIRCLE_PEER, CircleRole.Owner))
            assertFalse(fixture.controller.changeRole(CIRCLE_SPACE, CIRCLE_PEER, null))
            assertFalse(fixture.controller.archiveSpace(CIRCLE_SPACE))
            assertEquals(1, fixture.membershipPosts.size)
        } finally { fixture.close() }
    }

}

private fun requestBody(request: HttpRequestData): JsonObject = NativeJson.parseToJsonElement(featureBody(request.body).decodeToString()).jsonObject
private fun assertGone(state: CircleState) {
    assertTrue(state.gone)
    assertNull(state.history.data)
    assertNull(state.members.data)
    assertNull(state.lists.data)
    assertEquals(CircleDraft(), state.draft)
    assertTrue(state.pending.isEmpty())
}

private class CircleResponseGate {
    val started = CompletableDeferred<Unit>()
    val release = CompletableDeferred<Unit>()
    suspend fun await() { started.complete(Unit); release.await() }
}

@OptIn(ExperimentalCoroutinesApi::class)
private class CircleControllerFixture(private val test: TestScope) {
    val now = Instant.parse("2026-10-17T12:00:00Z").toEpochMilliseconds()
    private val record = featureRecord()
    val namespace = record.namespace
    val store = CircleMemoryJournal()
    private val secrets = FeatureSessionSecrets(record)
    val sessions = SessionManager("synthetic", FEATURE_ORIGIN, secrets, store::clearAccount, { now })
    val requests = mutableListOf<HttpRequestData>()
    val admitted = mutableListOf<Submission>()
    val messagePosts get() = requests.filter { it.method == HttpMethod.Post && it.url.encodedPath.endsWith("/messages") }
    val readPosts get() = requests.filter { it.method == HttpMethod.Post && it.url.encodedPath.endsWith("/read") }
    val membershipPosts get() = requests.filter { it.method == HttpMethod.Post && it.url.encodedPath.endsWith("/members") }
    var messages = decodeCircleHistory(circleFixture("history-server"), CIRCLE_DM).messages
    var members = decodeCircleMembers(circleFixture("members"))
    var selfRole = CircleRole.Owner
    var spaceVisible = true
    var historyGone = false
    var omitActiveChannel = false
    var failNextRead = false
    var failNextReport = false
    var loseNextReceipt = false
    var malformedNextReceipt = false
    var failHistory = false
    var failLists = false
    var nextSendGate: CircleResponseGate? = null
    var nextHistoryGate: CircleResponseGate? = null
    private var writes = 0
    private val http = NativeHttpClient(MockEngine(MockEngineConfig().apply {
        dispatcher = StandardTestDispatcher(test.testScheduler)
        addHandler { request ->
            requests += request
            val path = request.url.encodedPath
            when {
                path == "/v1/spaces" -> {
                    assertEquals(HttpMethod.Get, request.method)
                    val spaces = decodeCircleSpaces(circleFixture("spaces")).let { all -> all.copy(spaces = all.spaces.filter { it.id != CIRCLE_SPACE || spaceVisible }.map { if (it.id == CIRCLE_SPACE) it.copy(role = selfRole) else it }) }
                    respond(NativeJson.encodeToString(spaces))
                }
                path == "/v1/channels" -> {
                    val channels = decodeCircleChannels(circleFixture("channels")).filter { channel ->
                        (spaceVisible || channel.spaceId != CIRCLE_SPACE) && (!omitActiveChannel || channel.id != controller.state.value?.channelId)
                    }
                    respond(buildJsonObject { put("channels", NativeJson.encodeToJsonElement(channels)) }.toString())
                }
                path == "/v1/relationships" && request.method == HttpMethod.Get -> {
                    if (request.url.parameters["login"] != null) respond("""{"user":{"id":"$CIRCLE_PEER","name":"Synthetic friend","login":"synthetic-friend"}}""")
                    else respond(circleFixture("relationships"))
                }
                path == "/v1/channels/direct" -> respond("""{"id":"$CIRCLE_DM"}""", HttpStatusCode.Created)
                path == "/v1/tasks/lists" -> if (failLists) respond("", HttpStatusCode.ServiceUnavailable) else respond(circleFixture("lists"))
                path == "/v1/spaces/$CIRCLE_SPACE/members" && request.method == HttpMethod.Get -> {
                    if (!spaceVisible) respond("""{"error":"not_found"}""", HttpStatusCode.NotFound)
                    else respond(buildJsonObject { put("members", NativeJson.encodeToJsonElement(members)) }.toString())
                }
                path == "/v1/spaces/$CIRCLE_SPACE/members" -> {
                    val body = requestBody(request)
                    val user = body.getValue("user_id").jsonPrimitive.content
                    val role = body.getValue("role")
                    members = if (role == JsonNull) members.filter { it.userId != user }
                        else members.map { if (it.userId == user) it.copy(role = CircleRole.entries.single { value -> value.wire == role.jsonPrimitive.content }) else it }
                    if (user == CIRCLE_ME && role == JsonNull) spaceVisible = false
                    respond("""{"updated":true}""")
                }
                path == "/v1/spaces/$CIRCLE_SPACE/archive" -> { spaceVisible = false; respond("""{"archived":true}""") }
                path == "/v1/community/reports" -> {
                    if (failNextReport) { failNextReport = false; throw IOException("synthetic report response lost") }
                    respond("""{"id":"synthetic-report","state":"received"}""", HttpStatusCode.Created)
                }
                path.endsWith("/read") -> {
                    if (failNextRead) { failNextRead = false; respond("", HttpStatusCode.ServiceUnavailable) }
                    else respond("""{"read":true}""")
                }
                path.startsWith("/v1/channels/") && path.endsWith("/messages") && request.method == HttpMethod.Get -> {
                    nextHistoryGate?.also { nextHistoryGate = null }?.await()
                    if (historyGone) respond("""{"error":"not_found"}""", HttpStatusCode.NotFound)
                    else if (failHistory) respond("", HttpStatusCode.ServiceUnavailable)
                    else {
                        val channel = path.removePrefix("/v1/channels/").removeSuffix("/messages")
                        val earlier = request.url.parameters["before"] != null
                        val page = if (earlier) listOf(messages.first().copy(id = "message-older", text = "Older history", createdAt = "2026-10-17T11:58:00Z"), messages.first()) else messages
                        respond(NativeJson.encodeToString(CircleHistory(CircleHistoryChannel(channel), page, if (earlier) null else "message-before-1")))
                    }
                }
                path.startsWith("/v1/channels/") && path.endsWith("/messages") && request.method == HttpMethod.Post -> {
                    val key = assertNotNull(request.headers["Idempotency-Key"])
                    assertFalse(admitted.any { it.key == key }, "An unknown channel send must not be transmitted twice")
                    val durable = assertNotNull(store.submission(namespace, key), "The exact request must be journaled before the network send")
                    assertEquals(SubmissionState.SENDING, durable.state)
                    assertContentEquals(featureBody(request.body), durable.body)
                    admitted += durable
                    writes++
                    val id = "sent-$writes"
                    val body = requestBody(request)
                    val message = CircleMessage(id, "user", "2026-10-17T12:00:00Z", actorUserId = CIRCLE_ME,
                        replyToId = body["reply_to_id"]?.jsonPrimitive?.content, name = "Synthetic person", text = body.getValue("text").jsonPrimitive.content)
                    messages = messages + message
                    nextSendGate?.also { nextSendGate = null }?.await()
                    if (loseNextReceipt) { loseNextReceipt = false; throw IOException("synthetic channel receipt lost after commit") }
                    if (malformedNextReceipt) { malformedNextReceipt = false; respond("""{"state":"sent"}""", HttpStatusCode.Created) }
                    else if (body["assistant"] == JsonPrimitive(true)) respond("""{"submission_key":"channel:$key","message_id":"$id","admitted_at":"2026-10-17T12:00:00Z","state":"queued"}""", HttpStatusCode.Accepted)
                    else respond("""{"message_id":"$id","state":"sent"}""", HttpStatusCode.Created)
                }
                else -> error("Unexpected Circle request: ${request.method.value} $path")
            }
        }
    }), FEATURE_ORIGIN, sessions, { now })
    private val submissions = SubmissionCoordinator(store, http, sessions, { now }, SubmissionReceiptValidator(::validateCircleSubmissionReceipt))
    var controller: CircleController = newController()
        private set
    private fun newController() = CircleController(test.backgroundScope, sessions, http, submissions, AccountDrafts(store, sessions, { now }), { now })

    suspend fun start(space: String? = null, channel: String? = null) {
        if (sessions.lease() == null) sessions.restore()
        controller.activate(space, channel)
        test.runCurrent()
        controller.refresh()
        controller.state.first { it != null && it.spaces.data != null && it.channels.data != null &&
            (space == null || it.members.data != null || it.gone) && (channel == null || it.history.data != null || it.gone) }
    }
    suspend fun replaceAccount(user: String) {
        secrets.value = record.copy(namespace = record.namespace.copy(userId = user), identity = record.identity.copy(id = user))
        sessions.restore()
    }
    fun recreateController() { controller.close(); test.runCurrent(); controller = newController() }
    fun close() { controller.close(); test.runCurrent(); http.close() }
}

private class CircleMemoryJournal : JournalStore {
    private val entries = mutableMapOf<Pair<AccountNamespace, String>, Submission>()
    val savedDrafts = mutableMapOf<Pair<AccountNamespace, String>, Draft>()
    private val cursors = mutableMapOf<AccountNamespace, String>()
    override suspend fun insertSubmission(entry: Submission) {
        check(entries.putIfAbsent(entry.namespace to entry.key, entry) == null)
    }
    override suspend fun saveSubmission(entry: Submission) { check(entries.containsKey(entry.namespace to entry.key)); entries[entry.namespace to entry.key] = entry }
    override suspend fun submission(namespace: AccountNamespace, key: String) = entries[namespace to key]
    override suspend fun submissions(namespace: AccountNamespace) = entries.values.filter { it.namespace == namespace }
    override suspend fun deleteSubmission(namespace: AccountNamespace, key: String) { entries.remove(namespace to key) }
    override suspend fun saveDraft(draft: Draft) { savedDrafts[draft.namespace to draft.target] = draft }
    override suspend fun draft(namespace: AccountNamespace, target: String) = savedDrafts[namespace to target]
    override suspend fun deleteDraft(namespace: AccountNamespace, target: String) { savedDrafts.remove(namespace to target) }
    override suspend fun readCursor(namespace: AccountNamespace) = cursors[namespace]
    override suspend fun writeCursor(namespace: AccountNamespace, cursor: String) { cursors[namespace] = cursor }
    override suspend fun clearAccount(namespace: AccountNamespace) {
        entries.keys.removeAll { it.first == namespace }; savedDrafts.keys.removeAll { it.first == namespace }; cursors.remove(namespace)
    }
}
