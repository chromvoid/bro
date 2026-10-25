package assistant.mobile.core.personal

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
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockEngineConfig
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import java.io.IOException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
class PersonalHistoryApiTest {
    @Test
    fun recordAndSamplePagesUseTheirOwnRoutesAndPreserveTheWholeArchive() = runTest {
        for ((metric, fixture) in listOf("steps" to "history-record-versions", "spo2" to "history-sample-versions")) {
            val original = personalFixtureObject(fixture)
            val versions = original.getValue("versions") as JsonArray
            val requests = mutableListOf<HttpRequestData>()
            val env = historyApiEnvironment(this) { request ->
                requests += request
                val next = request.url.parameters["cursor"] != null
                val page = JsonObject(original + mapOf("versions" to JsonArray(listOf(versions[if (next) 1 else 0])),
                    "next_cursor" to if (next) JsonNull else JsonPrimitive("saved snapshot / page 2")))
                page.toString()
            }
            try {
                env.sessions.restore()
                val lease = assertNotNull(env.sessions.lease())
                val page = env.api.versions(original.getValue("id").jsonPrimitive.content, metric, "ring-a", lease)
                assertEquals(listOf(2L, 1L), page.versions.map { it.revision })
                assertEquals(2, requests.size)
                assertTrue(requests.all { it.method == HttpMethod.Get && it.url.encodedPath.contains(if (metric == "steps") "/records/" else "/sensor-samples/") })
                assertEquals("saved snapshot / page 2", requests.last().url.parameters["cursor"])
                assertEquals(metric, requests.first().url.parameters[if (metric == "steps") "kind" else "metric"])
            } finally { env.http.close() }
        }
    }

    @Test
    fun sensorCalendarRangeUsesLocalMidnightsAcrossTheClockChange() = runTest {
        val env = historyApiEnvironment(this) { request ->
            assertEquals("2026-10-24T22:00:00Z", request.url.parameters["from"])
            assertEquals("2026-10-25T23:00:00Z", request.url.parameters["to"])
            """{"from":"2026-10-24T22:00:00Z","to":"2026-10-25T23:00:00Z","items":[],"fingerprint":"empty-dst-day","count":0,"next_cursor":null}"""
        }
        try {
            env.sessions.restore()
            val page = env.api.sensors("2026-10-25", "2026-10-25", "heart_rate", "Europe/Berlin", assertNotNull(env.sessions.lease()))
            assertTrue(page.items.isEmpty())
        } finally { env.http.close() }
    }

    @Test
    fun aLostBackfillReceiptOnlyLooksUpTheOriginalKeyAndThenReadsRunProgress() = runTest {
        val fixture = HistoryBackfillFixture(this)
        try {
            fixture.start()
            fixture.controller.backfill()
            val uncertain = fixture.controller.state.value!!.pending.single()
            assertEquals(SubmissionState.UNCERTAIN, uncertain.state)
            assertEquals(1, fixture.posts)
            fixture.controller.check(uncertain.key)
            val state = fixture.controller.state.value!!
            assertEquals(1, fixture.posts)
            assertEquals(1, fixture.lookups)
            assertEquals(uncertain.key, fixture.lookupKey)
            assertEquals("queued", state.backfillRun.data!!.state)
            assertEquals(30, state.backfillRun.data.scheduledDays)
            assertTrue(state.pending.isEmpty())
            assertTrue(fixture.store.submissions(fixture.namespace).isEmpty())
            assertTrue(state.backfillBlocked)
        } finally { fixture.controller.deactivate(); fixture.http.close() }
    }

}

private data class HistoryApiEnvironment(val sessions: SessionManager, val http: NativeHttpClient, val api: NativePersonalHistoryApi)
@OptIn(ExperimentalCoroutinesApi::class)
private fun historyApiEnvironment(test: TestScope, response: suspend (HttpRequestData) -> String): HistoryApiEnvironment {
    val now = Instant.parse("2026-10-17T12:00:00Z").toEpochMilliseconds()
    val record = featureRecord().let { it.copy(identity = it.identity.copy(scopes = listOf("personal.read", "personal.write"))) }
    val sessions = SessionManager("synthetic", FEATURE_ORIGIN, FeatureSessionSecrets(record), {}, { now })
    val http = NativeHttpClient(MockEngine(MockEngineConfig().apply {
        dispatcher = StandardTestDispatcher(test.testScheduler)
        addHandler { request -> respond(response(request)) }
    }), FEATURE_ORIGIN, sessions, { now })
    return HistoryApiEnvironment(sessions, http, NativePersonalHistoryApi(http))
}

@OptIn(ExperimentalCoroutinesApi::class)
private class HistoryBackfillFixture(test: TestScope) {
    val store = HistoryJournal()
    private val now = Instant.parse("2026-10-17T12:00:00Z").toEpochMilliseconds()
    private val record = featureRecord().let { it.copy(identity = it.identity.copy(timezone = "UTC", scopes = listOf("personal.read", "personal.write"))) }
    val namespace = record.namespace
    val sessions = SessionManager("synthetic", FEATURE_ORIGIN, FeatureSessionSecrets(record), store::clearAccount, { now })
    var posts = 0
    var lookups = 0
    var lookupKey: String? = null
    private var receipt: String? = null
    private var firstDay = "2026-09-18"
    private var lastDay = "2026-10-17"
    val http = NativeHttpClient(MockEngine(MockEngineConfig().apply {
        dispatcher = StandardTestDispatcher(test.testScheduler)
        addHandler { request ->
            when (val path = request.url.encodedPath) {
                PERSONAL_OPERATIONS -> {
                    posts++
                    val body = NativeJson.parseToJsonElement(featureBody(request.body).decodeToString()).jsonObject
                    assertEquals("personal.derived.backfill", body.getValue("kind").jsonPrimitive.content)
                    val payload = body.getValue("payload").jsonObject
                    firstDay = payload.getValue("from").jsonPrimitive.content
                    lastDay = payload.getValue("to").jsonPrimitive.content
                    receipt = """{"operation_id":"backfill-operation","kind":"personal.derived.backfill","derivation_run_id":"${"d".repeat(64)}","state":"queued","from":"$firstDay","to":"$lastDay","as_of":"2026-10-17T12:00:00Z","scheduled_days":30,"results_saved":0,"pending":true}"""
                    throw IOException("Synthetic response lost after admission")
                }
                "/v1/personal/overview" -> respond("""{"date":"${request.url.parameters["date"]}","timezone":"UTC","profile":{"user_id":"${namespace.userId}","timezone":"UTC","display_units":"imperial","birth_date":null,"height_cm":null,"gender":null,"revision":1},"metrics":{},"preferences":{},"sources":[]}""")
                "/v1/personal/history" -> respond("""{"from":"${request.url.parameters["from"]}","to":"${request.url.parameters["to"]}","records":[],"daily":[]}""")
                "/v1/personal/resolved" -> respond("""{"from":"${request.url.parameters["from"]}","to":"${request.url.parameters["to"]}","timezone":"UTC","computed_at":"2026-10-17T12:00:00Z","daily":[],"series":[],"fingerprint":"empty-resolved","next_cursor":null}""")
                "/v1/personal/sensor-samples" -> respond("""{"from":"${request.url.parameters["from"]}","to":"${request.url.parameters["to"]}","items":[],"count":0,"fingerprint":"empty-sensors","next_cursor":null}""")
                else -> when {
                    path.startsWith(PERSONAL_LOOKUP) -> { lookups++; lookupKey = path.removePrefix(PERSONAL_LOOKUP); respond(assertNotNull(receipt)) }
                    path.startsWith("/v1/personal/derived-backfills/") -> respond("""{"run_id":"${"d".repeat(64)}","state":"queued","from":"$firstDay","to":"$lastDay","next_date":"$firstDay","as_of":"2026-10-17T12:00:00Z","time_zone":"UTC","source_scope":null,"trigger":"explicit_backfill","processed_days":0,"scheduled_days":30,"results_saved":0,"results_unchanged":0,"error":null,"created_at":"2026-10-17T12:00:00Z","finished_at":null}""")
                    else -> respond("{}", HttpStatusCode.NotFound)
                }
            }
        }
    }), FEATURE_ORIGIN, sessions, { now })
    private val submissions = SubmissionCoordinator(store, http, sessions, { now }, SubmissionReceiptValidator(::validatePersonalSubmissionReceipt))
    val controller = PersonalHistoryController(test.backgroundScope, sessions, http, submissions, clockMs = { now })
    suspend fun start() {
        sessions.restore(); controller.activate()
        controller.state.first { it?.history?.data != null && it.overview.data != null }
        controller.refresh()
    }
}

private class HistoryJournal : JournalStore {
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
