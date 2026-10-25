package assistant.mobile.core.body

import assistant.mobile.core.AccountNamespace
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
import assistant.mobile.core.personal.PERSONAL_LOOKUP
import assistant.mobile.core.personal.PERSONAL_OPERATIONS
import assistant.mobile.core.personal.validatePersonalSubmissionReceipt
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockEngineConfig
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpStatusCode
import java.io.IOException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
class BodyPresentationControllerTest {
    @Test
    fun selectionUsesTheReadRevisionAndOnlyTheValidatedReceiptChangesTheCharacter() = runTest {
        val fixture = BodyPreferenceFixture(this)
        try {
            fixture.start()
            assertEquals(BodyPresentation(BodyCharacter.Sculpture, 4), fixture.controller.state.value?.presentation)
            fixture.controller.select(BodyCharacter.Spartan)
            assertEquals(1, fixture.posts.size)
            val command = command(fixture.posts.single())
            assertEquals(BODY_PRESENTATION_OPERATION, command.getValue("kind").jsonPrimitive.content)
            assertEquals(4L, command.getValue("payload").jsonObject.getValue("revision").jsonPrimitive.long)
            assertEquals("spartan", command.getValue("payload").jsonObject.getValue("character_id").jsonPrimitive.content)
            assertTrue(command.getValue("expected_revisions").jsonObject.isEmpty())
            assertEquals(BodyPresentation(BodyCharacter.Spartan, 5), fixture.controller.state.value?.presentation)
            assertTrue(fixture.store.entries.isEmpty())
            fixture.controller.select(BodyCharacter.Spartan)
            assertEquals(1, fixture.posts.size)
        } finally { fixture.close() }
    }

    @Test
    fun lostReceiptOnlyLooksUpItsOriginalKeyWithoutSendingTheSelectionAgain() = runTest {
        val fixture = BodyPreferenceFixture(this)
        try {
            fixture.start(); fixture.loseReply = true
            fixture.controller.select(BodyCharacter.Spartan)
            val unknown = fixture.controller.state.value!!.pending.single()
            assertEquals(SubmissionState.UNCERTAIN, unknown.state)
            assertEquals(BodyCharacter.Sculpture, fixture.controller.state.value?.character)
            assertFalse(fixture.controller.state.value!!.writable)
            fixture.controller.select(BodyCharacter.Spartan)
            assertEquals(1, fixture.posts.size)
            fixture.controller.check(unknown.key)
            assertEquals(listOf(unknown.key), fixture.lookupKeys)
            assertEquals(1, fixture.posts.size)
            assertEquals(BodyPresentation(BodyCharacter.Spartan, 5), fixture.controller.state.value?.presentation)
            assertTrue(fixture.controller.state.value!!.pending.isEmpty()); assertTrue(fixture.store.entries.isEmpty())
        } finally { fixture.close() }
    }
}

private fun command(request: HttpRequestData): JsonObject = NativeJson.parseToJsonElement(featureBody(request.body).decodeToString()).jsonObject
@OptIn(ExperimentalCoroutinesApi::class)
private class BodyPreferenceFixture(private val test: TestScope) {
    val store = BodyJournal()
    private val now = Instant.parse("2026-10-17T12:00:00Z").toEpochMilliseconds()
    val secrets = FeatureSessionSecrets(featureRecord().let { it.copy(identity = it.identity.copy(scopes = listOf("personal.read", "personal.write"))) })
    val sessions = SessionManager("synthetic", FEATURE_ORIGIN, secrets, store::clearAccount, { now })
    val posts = mutableListOf<HttpRequestData>()
    val lookupKeys = mutableListOf<String>()
    var historyReads = 0
    var saved = BodyPresentation(BodyCharacter.Sculpture, 4)
    var loseReply = false
    var corruptReceipt = false
    var conflict = false
    var expireDuringPost = false
    private var receipt: String? = null
    val http = NativeHttpClient(MockEngine(MockEngineConfig().apply {
        dispatcher = StandardTestDispatcher(test.testScheduler)
        addHandler { request ->
            when (val path = request.url.encodedPath) {
                BODY_PRESENTATION_PATH -> respond("""{"character_id":"${saved.character.wire}","revision":${saved.revision}}""")
                PERSONAL_OPERATIONS -> {
                    posts += request
                    val payload = command(request).getValue("payload").jsonObject
                    if (conflict) {
                        saved = BodyPresentation(BodyCharacter.Sculpture, 6)
                        respond("""{"error":"revision_conflict"}""", HttpStatusCode.Conflict)
                    } else {
                        saved = BodyPresentation(BodyCharacter.fromWire(payload.getValue("character_id").jsonPrimitive.content), payload.getValue("revision").jsonPrimitive.long + 1)
                        receipt = buildJsonObject {
                            put("operation_id", "synthetic-body-operation"); put("kind", BODY_PRESENTATION_OPERATION)
                            put("body_presentation", buildJsonObject {
                                put("character_id", if (corruptReceipt) "sculpture" else saved.character.wire); put("revision", saved.revision)
                            })
                        }.toString()
                        if (expireDuringPost) sessions.expire(assertNotNull(sessions.lease()))
                        if (loseReply) throw IOException("Synthetic reply lost after admission")
                        respond(requireNotNull(receipt))
                    }
                }
                "/v1/personal/history" -> {
                    historyReads++
                    assertEquals("body", request.url.parameters["kind"])
                    assertEquals("body", request.url.parameters["projection"])
                    respond("""{"from":"${request.url.parameters["from"]}","to":"${request.url.parameters["to"]}","records":[],"daily":[]}""")
                }
                else -> if (path.startsWith(PERSONAL_LOOKUP)) {
                    lookupKeys += path.removePrefix(PERSONAL_LOOKUP)
                    if (conflict || receipt == null) respond("{}", HttpStatusCode.NotFound) else respond(requireNotNull(receipt))
                } else respond("{}", HttpStatusCode.NotFound)
            }
        }
    }), FEATURE_ORIGIN, sessions, { now })
    private val submissions = SubmissionCoordinator(store, http, sessions, { now }, SubmissionReceiptValidator(::validatePersonalSubmissionReceipt))
    val controller = BodyPresentationController(test.backgroundScope, sessions, http, submissions)
    suspend fun start() {
        sessions.restore(); controller.activate()
        controller.state.first { it?.presentation != null }
        controller.refresh()
    }
    fun close() { controller.deactivate(); http.close() }
}

private class BodyJournal : JournalStore {
    val entries = mutableMapOf<Pair<AccountNamespace, String>, Submission>()
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
    override suspend fun writeCursor(namespace: AccountNamespace, value: String) { cursors[namespace] = value }
    override suspend fun clearAccount(namespace: AccountNamespace) {
        entries.keys.removeAll { it.first == namespace }; drafts.keys.removeAll { it.first == namespace }; cursors.remove(namespace)
    }
}
