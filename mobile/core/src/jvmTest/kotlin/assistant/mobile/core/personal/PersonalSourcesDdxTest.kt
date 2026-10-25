package assistant.mobile.core.personal

import assistant.mobile.core.AccountDrafts
import assistant.mobile.core.FEATURE_ORIGIN
import assistant.mobile.core.FeatureSessionSecrets
import assistant.mobile.core.NativeHttpClient
import assistant.mobile.core.NativeJson
import assistant.mobile.core.SessionManager
import assistant.mobile.core.featureBody
import assistant.mobile.core.featureRecord
import assistant.mobile.core.journal.RoomJournalStore
import assistant.mobile.core.journal.Submission
import assistant.mobile.core.journal.SubmissionCoordinator
import assistant.mobile.core.journal.SubmissionReceiptValidator
import assistant.mobile.core.journal.SubmissionState
import assistant.mobile.core.journal.openJournalDatabase
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockEngineConfig
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import java.io.IOException
import java.nio.file.Files
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

/** Shapes and policy vectors mirror sources.test.ts/ddx.test.ts and server DDX projections. */
@OptIn(ExperimentalCoroutinesApi::class)
class PersonalSourcesDdxTest {
    @Test
    fun sourceAndDdxContractsKeepProvenanceButStripProviderSecrets() {
        val fixture = personalFixtureObject("sources-ddx")
        val source = decodePersonalSources(fixture.toString().encodeToByteArray()).first()
        assertEquals("ddx", source.device.kind)
        assertTrue(source.active)
        assertEquals(3L, source.connection!!.revision)
        val day = decodePersonalSourceDay(fixture.getValue("day").toString().encodeToByteArray())
        assertEquals("raw-1", day.series.single().reportId)
        assertEquals("finger", day.series.single().sensorLocation)
        val record = buildJsonObject { put("id", "record-2"); put("kind", "weight"); put("source_id", "inbody")
            put("start_at", "2026-10-13T10:00:00Z"); put("revision", 1)
            put("data", buildJsonObject { put("value", "74") }) }
        val recordPage = JsonObject(fixture.getValue("day").jsonObject + ("series" to JsonArray(listOf(record))))
        val ownRecord = decodePersonalSourceDay(recordPage.toString().encodeToByteArray()).series.single()
        assertEquals("weight", ownRecord.metric)
        assertEquals("74", ownRecord.value)
        assertEquals(2L, day.identities.getValue("inbody").revision)
        assertEquals("primary", personalSourceRole("inbody", PERSONAL_SOURCE_ROWS.first { it.key == "weight" }, day, "2026-10-13"))
        val login = decodePersonalDdxLogin(fixture.getValue("login").jsonObject)
        assertTrue(login.active)
        assertTrue(login.canResend)
        val printable = "$source $login"
        for (secret in listOf("123456", "synthetic-password", "synthetic-secret", "credential_ref", "+79000000001")) assertFalse(secret in printable)
        val run = decodePersonalDdxRun(fixture.getValue("run").jsonObject)
        assertEquals("partial", run.state)
        assertEquals(2L, run.outcome!!.newAnalyses)
        assertTrue(run.outcome.projectionIncomplete)
    }

    @Test
    fun policyConflictPreservesDraftAndUnknownOutcomeOnlyLooksUpOriginalKey() = runTest {
        val f = SourcesDdxFixture(this)
        try {
            f.startSources()
            f.sources.beginPolicy("weight"); f.sources.choosePolicy("inbody"); f.sources.setPolicyDate(f.day)
            f.rejectPolicy = true
            f.sources.savePolicy()
            assertTrue(f.sources.state.value!!.conflict)
            assertEquals("inbody", f.sources.state.value!!.policyDraft!!.sourceId)
            assertTrue(f.store.draft(f.namespace, "personal/source-policy")!!.json.decodeToString().contains("inbody"))
            assertEquals(4L, f.sources.state.value!!.combined.data!!.policies.single().revision)
            val conflictKey = f.policyKeys.single()
            // A 409 is checked before it can become a definite rejection: this key has no receipt.
            assertEquals(listOf(conflictKey to 404), f.operationLookups)
            f.loseReceipt = true
            f.sources.savePolicy()
            val pending = f.sources.state.value!!.pending.single()
            assertEquals(SubmissionState.UNCERTAIN, pending.state)
            assertEquals(pending.key, f.policyKeys.last())
            assertNotEquals(conflictKey, pending.key)
            assertEquals(4L, f.policyCommands.last().getValue("payload").jsonObject.sLong("revision"))
            f.sources.savePolicy()
            assertEquals(2, f.policyCommands.size)
            assertEquals(listOf(conflictKey to 404), f.operationLookups)
            f.sources.check(pending.key)
            assertEquals(2, f.policyCommands.size)
            assertEquals(listOf(conflictKey to 404, pending.key to 200), f.operationLookups)
            assertNull(f.sources.state.value!!.policyDraft)
            assertNull(f.store.draft(f.namespace, "personal/source-policy"))
            assertTrue(f.sources.state.value!!.pending.isEmpty())
        } finally { f.close() }
    }

    @Test
    fun changedReceiptRevisionOrSourceCannotResolveAPolicy() = runTest {
        val f = SourcesDdxFixture(this)
        try {
            f.startSources()
            val operation = sourcePolicyPersonalOperation(PersonalSourcePolicyDraft("weight", "inbody", f.day),
                f.sources.state.value!!.sources.data!!, f.sources.state.value!!.combined.data!!.policies)
            val entry = Submission(f.namespace, "synthetic-key", PERSONAL_OPERATIONS, PERSONAL_LOOKUP + "synthetic-key", "POST",
                operation.bytes(), "personal", emptyMap(), null, SubmissionState.RESOLVED, 1)
            val saved = buildJsonObject { put("id", "policy-1"); put("metric", "weight"); put("effective_from", f.day)
                put("revision", 4); put("source_ids", JsonArray(listOf(JsonPrimitive("inbody")))) }
            val receipt = buildJsonObject { put("operation_id", "op"); put("kind", operation.kind); put("policy", saved) }
            validatePersonalSourceReceipt(entry, receipt.toString().encodeToByteArray())
            assertFails { validatePersonalSourceReceipt(entry, JsonObject(receipt + ("policy" to JsonObject(saved + ("revision" to JsonPrimitive(3))))).toString().encodeToByteArray()) }
            assertFails { validatePersonalSourceReceipt(entry, JsonObject(receipt + ("policy" to JsonObject(saved + ("source_ids" to JsonArray(emptyList()))))).toString().encodeToByteArray()) }
        } finally { f.close() }
    }

    @Test
    fun uncertainDdxCodeUsesStatusAndGetWithoutASecondCodePost() = runTest {
        val f = SourcesDdxFixture(this)
        try {
            f.startDdx(); f.ddx.start("connect", "+79000000001")
            f.loseDdxCodeReply = true
            f.ddx.submitCode("001234")
            assertTrue(f.ddx.state.value!!.uncertain)
            f.ddx.submitCode("001234")
            assertEquals(1, f.codePosts)
            f.ddx.refresh()
            assertFalse(f.ddx.state.value!!.uncertain)
            assertEquals("verifying", f.ddx.state.value!!.login!!.state)
            f.loginState = "ready"; f.ddx.refresh()
            assertEquals("ready", f.ddx.state.value!!.login!!.state)
            assertNull(f.store.draft(f.namespace, "personal/ddx-login"))
            assertEquals(1, f.codePosts)
            assertTrue(f.store.submissions(f.namespace).isEmpty())
        } finally { f.close() }
    }

    @Test
    fun summaryOnlyAccountCannotFetchSourcesDetailsOrDdxEnrollment() = runTest {
        val f = SourcesDdxFixture(this, scopes = listOf("personal.summary"))
        try {
            f.startSources(); f.startDdx()
            f.sources.beginPolicy("weight"); f.sources.refresh(); f.ddx.refresh()
            assertFalse(f.sources.state.value!!.canRead)
            assertFalse(f.ddx.state.value!!.canWrite)
            assertEquals(0, f.personalGets)
        } finally { f.close() }
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
private class SourcesDdxFixture(private val test: TestScope, scopes: List<String> = listOf("personal.read", "personal.write")) {
    val day = "2026-10-13"
    private val root = personalFixtureObject("sources-ddx")
    private val directory = Files.createTempDirectory("personal-sources-ddx").toFile()
    private val database = openJournalDatabase(directory.resolve("journal.db"))
    val store = RoomJournalStore(database)
    private val now = Instant.parse("2026-10-17T12:00:00Z").toEpochMilliseconds()
    private val record = featureRecord().let { it.copy(identity = it.identity.copy(timezone = "Europe/Moscow", scopes = scopes)) }
    val namespace = record.namespace
    private val sessions = SessionManager("synthetic", FEATURE_ORIGIN, FeatureSessionSecrets(record), store::clearAccount, { now })
    var rejectPolicy = false
    var loseReceipt = false
    var policyRevision = 3L
    var policySource = "manual"
    val policyCommands = mutableListOf<JsonObject>()
    val policyKeys = mutableListOf<String>()
    val operationLookups = mutableListOf<Pair<String, Int>>()
    private val receipts = mutableMapOf<String, String>()
    var loginState: String? = null
    var loginGets = 0
    var resends = 0
    var codePosts = 0
    var personalGets = 0
    var loseDdxCodeReply = false
    var loseDdxStartReply = false
    var hideActiveLogin = false
    var connectionRevision = 3L
    val loginCommands = mutableListOf<JsonObject>()
    val ddxPostKeys = mutableListOf<Pair<String, String>>()
    val disconnectRevisions = mutableListOf<Long>()
    val weekQueries = mutableListOf<Triple<String, String, String>>()
    private var loginKey = ""
    private val http = NativeHttpClient(MockEngine(MockEngineConfig().apply {
        dispatcher = StandardTestDispatcher(test.testScheduler)
        addHandler { request ->
            val path = request.url.encodedPath
            if (path.startsWith("/v1/personal/") && request.method == HttpMethod.Get) personalGets++
            if (path.startsWith("/v1/personal/ddx/") && request.method == HttpMethod.Post) {
                ddxPostKeys += path to assertNotNull(request.headers["Idempotency-Key"])
                assertEquals(namespace.userId, request.headers["X-Expected-User"])
            }
            when {
                path == "/v1/me" -> respond(NativeJson.encodeToString(record.identity))
                path == "/v1/personal/sources" -> respond(root.toString())
                path == "/v1/personal/resolved" -> {
                    val chosenDay = request.url.parameters["from"]!!
                    val metric = request.url.parameters["metric"]
                    if (metric != null) {
                        weekQueries += Triple(chosenDay, request.url.parameters["to"]!!, metric)
                        assertEquals("1", request.url.parameters["limit"])
                        respond(root.getValue("week").toString())
                    } else {
                        val original = root.getValue("day").jsonObject
                        val current = JsonObject(original + mapOf("from" to JsonPrimitive(chosenDay), "to" to JsonPrimitive(chosenDay),
                            "daily" to JsonArray(original.sArray("daily").map { JsonObject(it.jsonObject + ("date" to JsonPrimitive(chosenDay))) }),
                            "policies" to JsonArray(listOf(policy()))))
                        respond(current.toString())
                    }
                }
                path == "/v1/personal/overview" -> respond("{}", HttpStatusCode.ServiceUnavailable)
                path.startsWith(PERSONAL_LOOKUP) -> {
                    val key = path.removePrefix(PERSONAL_LOOKUP)
                    val receipt = receipts[key]
                    operationLookups += key to (if (receipt != null) 200 else 404)
                    receipt?.let { respond(it) } ?: respond("{}", HttpStatusCode.NotFound)
                }
                path == PERSONAL_OPERATIONS -> {
                    val command = personalObject(featureBody(request.body)); policyCommands += command
                    policyKeys += assertNotNull(request.headers["Idempotency-Key"])
                    if (rejectPolicy) { rejectPolicy = false; policyRevision++; respond("{}", HttpStatusCode.Conflict) }
                    else {
                        val payload = command.getValue("payload").jsonObject
                        assertEquals(policyRevision, payload.sLong("revision"))
                        policyRevision++; policySource = payload.sStrings("source_ids").single()
                        val receipt = buildJsonObject { put("operation_id", "synthetic-operation"); put("kind", command.sText("kind")); put("policy", policy()) }.toString()
                        receipts[request.headers["Idempotency-Key"]!!] = receipt
                        if (loseReceipt) { loseReceipt = false; throw IOException("synthetic lost response") }
                        respond(receipt)
                    }
                }
                path == "/v1/personal/ddx/status" -> respond(buildJsonObject {
                    put("available", true); put("can_disconnect", true)
                    put("active_login", if (!hideActiveLogin && loginState in setOf("sending", "waiting_code", "verifying", "staged")) login() else kotlinx.serialization.json.JsonNull)
                }.toString())
                path == "/v1/personal/ddx/logins" -> {
                    val body = personalObject(featureBody(request.body))
                    assertEquals("+79000000001", body.sText("phone"))
                    loginCommands += JsonObject(body - "phone")
                    if (body.sText("mode") != "connect" && body.sLong("expected_revision") != connectionRevision)
                        respond("{\"error\":\"connection_changed\"}", HttpStatusCode.Conflict)
                    else {
                        loginKey = request.headers["Idempotency-Key"]!!; loginState = "waiting_code"
                        if (loseDdxStartReply) { loseDdxStartReply = false; throw IOException("synthetic lost start response") }
                        respond(loginReply())
                    }
                }
                path == "/v1/personal/ddx/logins/synthetic-login" -> { loginGets++; respond(loginReply()) }
                path.endsWith("/resend") -> { resends++; loginState = "waiting_code"; respond(loginReply()) }
                path.endsWith("/code") -> {
                    assertEquals("001234", personalObject(featureBody(request.body)).sText("code"))
                    codePosts++; loginState = "verifying"
                    if (loseDdxCodeReply) { loseDdxCodeReply = false; throw IOException("synthetic lost code response") }
                    respond(loginReply())
                }
                path.endsWith("/cancel") -> { loginState = "cancelled"; respond(loginReply()) }
                path == "/v1/personal/ddx/connections/inbody/disconnect" -> {
                    val revision = personalObject(featureBody(request.body)).sLong("expected_revision")
                    disconnectRevisions += revision
                    assertEquals(connectionRevision, revision)
                    respond("{\"operation_id\":\"synthetic-disconnect\",\"kind\":\"personal.ddx.disconnected\",\"source_id\":\"inbody\",\"disconnected\":true}")
                }
                else -> error("Unexpected synthetic route: $path")
            }
        }
    }), FEATURE_ORIGIN, sessions, { now })
    private val drafts = AccountDrafts(store, sessions, { now })
    private val submissions = SubmissionCoordinator(store, http, sessions, { now }, SubmissionReceiptValidator(::validatePersonalSourceReceipt))
    val sources = PersonalSourcesController(test.backgroundScope, sessions, http, submissions, drafts, { now })
    val ddx = newDdx()
    fun newDdx(): PersonalDdxController = PersonalDdxController(test.backgroundScope, sessions, http, drafts, { now })
    suspend fun startSources() {
        sessions.restore()
        sources.activate(day = day)
        // Room uses its own IO dispatcher; runCurrent cannot complete its initial draft read.
        sources.state.first { it != null && (!it.canRead || (it.sources.data != null && it.combined.data != null)) }
        sources.refresh()
    }
    suspend fun startDdx() {
        if (sessions.lease() == null) sessions.restore()
        ddx.activate()
        val ready = ddx.state.first { it != null && (!it.canWrite || (it.available != null && !it.checking)) }!!
        if (ready.canWrite) {
            assertEquals(true, ready.available)
            assertNull(ready.errorCode)
        }
    }
    private fun policy(): JsonObject = buildJsonObject { put("id", "policy-1"); put("metric", "weight"); put("effective_from", day)
        put("revision", policyRevision); put("source_ids", JsonArray(listOf(JsonPrimitive(policySource)))) }
    private fun login(): JsonObject = JsonObject(root.getValue("login").jsonObject + mapOf("state" to JsonPrimitive(loginState ?: "waiting_code"), "request_key" to JsonPrimitive(loginKey)))
    private fun loginReply(): String = buildJsonObject { put("login", login()) }.toString()
    suspend fun close() { sources.deactivate(); ddx.deactivate(); http.close(); database.close(); directory.deleteRecursively() }
}
