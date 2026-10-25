package assistant.mobile.core.speech

import assistant.mobile.core.LiveSocketFactory
import assistant.mobile.core.dictation.FakeLiveSocket

import assistant.mobile.core.AccountNamespace
import assistant.mobile.core.FEATURE_NOW
import assistant.mobile.core.FEATURE_ORIGIN
import assistant.mobile.core.FeatureSessionSecrets
import assistant.mobile.core.NativeClientCore
import assistant.mobile.core.NativeClientData
import assistant.mobile.core.EventInvalidation
import assistant.mobile.core.GeneratedStrings
import assistant.mobile.core.NativeHttpClient
import assistant.mobile.core.NativeJson
import assistant.mobile.core.SessionLease
import assistant.mobile.core.SessionManager
import assistant.mobile.core.featureBody
import assistant.mobile.core.featureRecord
import assistant.mobile.core.journal.Draft
import assistant.mobile.core.journal.JournalStore
import assistant.mobile.core.journal.Submission
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class SpeechSettingsControllerTest {
    @Test
    fun personalConnectionCreateEditKeepKeyAndDeleteUseRevisionsAndInvalidatePlayback() = runTest {
        val sessions = SessionManager("synthetic", FEATURE_ORIGIN, FeatureSessionSecrets(featureRecord()), {}, { FEATURE_NOW })
        var snapshot = syntheticSettings()
        val invalidated = mutableListOf<String>()
        val writes = mutableListOf<String>()
        val http = NativeHttpClient(MockEngine { request ->
            when (request.method.value) {
                "GET" -> if (request.url.encodedPath.endsWith("/models"))
                    respond(NativeJson.encodeToString(SpeechModelCatalog(listOf(asrModel, ttsModel))))
                    else respond(settingsJson(snapshot))
                "PUT" -> {
                    assertNull(request.headers["Idempotency-Key"])
                    val body = featureBody(request.body).decodeToString()
                    writes += body
                    val input = NativeJson.parseToJsonElement(body).jsonObject
                    val revision = input.getValue("revision").jsonPrimitive.content.toLong()
                    assertEquals(snapshot.connections.firstOrNull()?.revision ?: 0, revision)
                    snapshot = snapshot.copy(connections = listOf(SpeechConnection(connectionId, "personal", "openai",
                        input.getValue("label").jsonPrimitive.content,
                        input.getValue("enabled").jsonPrimitive.content.toBoolean(), revision + 1, true, listOf("asr", "tts"))))
                    respond("{}")
                }
                "DELETE" -> {
                    assertEquals("2", NativeJson.parseToJsonElement(featureBody(request.body).decodeToString()).jsonObject.getValue("revision").jsonPrimitive.content)
                    snapshot = snapshot.copy(connections = emptyList())
                    respond("""{"deleted":true}""")
                }
                else -> error("Unexpected request")
            }
        }, FEATURE_ORIGIN, sessions)
        try {
            sessions.restore()
            val controller = SpeechSettingsController(backgroundScope, sessions, http, invalidated::add, createId = { connectionId })
            runCurrent()
            controller.refresh()
            controller.openConnection()
            controller.editConnectionLabel(" Synthetic connection ")
            controller.saveConnection("synthetic-request-only-key")
            assertEquals("Synthetic connection", controller.state.value!!.personalConnections.single().label)
            assertTrue(writes.first().contains("synthetic-request-only-key"))
            assertNull(controller.state.value!!.editor)
            assertTrue(connectionId in invalidated)
            controller.openConnection(connectionId)
            controller.editConnectionLabel("Renamed")
            controller.saveConnection("")
            assertFalse(writes.last().contains("api_key"))
            assertEquals(2L, controller.state.value!!.personalConnections.single().revision)
            controller.openConnection(connectionId)
            controller.deleteConnection()
            assertTrue(controller.state.value!!.personalConnections.isEmpty())
            assertEquals(GeneratedStrings.SpeechSettings.removed, controller.state.value!!.notice)
        } finally { http.close() }
    }

    @Test
    fun missingOrRefusedKeyNeverEntersJournalCacheStateOrUpstreamErrorText() = runTest {
        val journal = SpeechRecordingJournal()
        val secret = "synthetic-never-persist-key"
        var writes = 0
        val core = NativeClientCore(backgroundScope, "synthetic", FEATURE_ORIGIN, FeatureSessionSecrets(featureRecord()),
            journal, MockEngine { request ->
                if (request.method.value == "PUT") {
                    writes++
                    assertNull(request.headers["Idempotency-Key"])
                    assertTrue(featureBody(request.body).decodeToString().contains(secret))
                    respond("""{"error":"invalid","message":"Provider rejected $secret"}""", HttpStatusCode.BadRequest)
                } else respond(settingsJson(syntheticSettings()))
            }, object : NativeClientData {
                override suspend fun refetchAll(lease: SessionLease) = Unit
                override suspend fun invalidate(lease: SessionLease, invalidation: EventInvalidation) = Unit
            }, LiveSocketFactory { _, listener -> FakeLiveSocket(listener) }, { FEATURE_NOW })
        try {
            core.restore()
            val controller = SpeechSettingsController(backgroundScope, core.sessions, core.http, createId = { connectionId })
            runCurrent()
            controller.refresh()
            controller.openConnection()
            controller.editConnectionLabel("Synthetic")
            controller.saveConnection("")
            assertEquals(GeneratedStrings.SpeechSettings.keyMissing, controller.state.value!!.error)
            assertEquals(0, writes)
            controller.saveConnection(secret)
            assertEquals(1, writes)
            assertEquals(GeneratedStrings.Errors.invalid, controller.state.value!!.error)
            assertFalse(controller.state.value.toString().contains(secret))
            assertTrue(journal.entries.isEmpty())
            assertTrue(journal.drafts.isEmpty())
            assertTrue(controller.state.value!!.catalogs.isEmpty())
            runCurrent()
            assertEquals(1, writes)
            controller.closeConnection()
            assertNull(controller.state.value!!.editor)
        } finally { core.close() }
    }

    @Test
    fun lateSnapshotCannotRestorePrivateSettingsAfterExpiry() = runTest {
        val sessions = SessionManager("synthetic", FEATURE_ORIGIN, FeatureSessionSecrets(featureRecord()), {}, { FEATURE_NOW })
        val started = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        val http = NativeHttpClient(MockEngine {
            started.complete(Unit)
            finish.await()
            respond(settingsJson(syntheticSettings(connection = true)))
        }, FEATURE_ORIGIN, sessions)
        try {
            sessions.restore()
            val controller = SpeechSettingsController(backgroundScope, sessions, http)
            runCurrent()
            val pending = async { controller.refresh() }
            started.await()
            sessions.expire(sessions.lease()!!)
            runCurrent()
            assertNull(controller.state.value)
            finish.complete(Unit)
            pending.await()
            assertNull(controller.state.value)
        } finally { http.close() }
    }
}

private const val connectionId = "22222222-2222-4222-8222-222222222222"
private val asrModel = SpeechModel("synthetic-asr", "Synthetic ASR", listOf("asr"), "streaming", emptyList(), null, null, true, null)
private val ttsModel = SpeechModel("synthetic-tts", "Synthetic TTS", listOf("tts"), null, listOf("alloy", "echo"), "alloy", 4000, true, null)
private fun syntheticSettings(connection: Boolean = false) = SpeechSettingsSnapshot(
    providers = listOf(
        SpeechProvider("codex", "Codex", listOf("asr", "tts"), "subscription", models = listOf(asrModel, ttsModel)),
        SpeechProvider("openai", "OpenAI", listOf("asr", "tts"), "api_key", dynamicModels = true),
    ),
    connections = if (connection) listOf(SpeechConnection(connectionId, "personal", "openai", "Synthetic", true, 1, true, listOf("asr", "tts"))) else emptyList(),
    preferences = SpeechPreferences(0, "auto", null, "auto", "alloy"),
    asr = SpeechReadiness("configured"), tts = SpeechReadiness("configured"),
)
private fun settingsJson(snapshot: SpeechSettingsSnapshot): String = NativeJson.encodeToString(snapshot)

private class SpeechRecordingJournal : JournalStore {
    val entries = mutableListOf<Submission>()
    val drafts = mutableListOf<Draft>()
    override suspend fun insertSubmission(entry: Submission) { entries += entry }
    override suspend fun saveSubmission(entry: Submission) { entries += entry }
    override suspend fun submission(namespace: AccountNamespace, key: String): Submission? = entries.find { it.namespace == namespace && it.key == key }
    override suspend fun submissions(namespace: AccountNamespace): List<Submission> = entries.filter { it.namespace == namespace }
    override suspend fun deleteSubmission(namespace: AccountNamespace, key: String) = Unit
    override suspend fun saveDraft(draft: Draft) { drafts += draft }
    override suspend fun draft(namespace: AccountNamespace, target: String): Draft? = drafts.find { it.namespace == namespace && it.target == target }
    override suspend fun deleteDraft(namespace: AccountNamespace, target: String) = Unit
    override suspend fun readCursor(namespace: AccountNamespace): String? = null
    override suspend fun writeCursor(namespace: AccountNamespace, cursor: String) = Unit
    override suspend fun clearAccount(namespace: AccountNamespace) = Unit
}
