package assistant.mobile.core.personal

import assistant.mobile.core.AccountDrafts
import assistant.mobile.core.AccountNamespace
import assistant.mobile.core.FEATURE_ORIGIN
import assistant.mobile.core.FeatureSessionSecrets
import assistant.mobile.core.NativeHttpClient
import assistant.mobile.core.NativeJson
import assistant.mobile.core.SessionLease
import assistant.mobile.core.SessionManager
import assistant.mobile.core.featureBody
import assistant.mobile.core.featureRecord
import assistant.mobile.core.journal.Draft
import assistant.mobile.core.journal.JournalStorageException
import assistant.mobile.core.journal.JournalStore
import assistant.mobile.core.journal.RoomJournalStore
import assistant.mobile.core.journal.Submission
import assistant.mobile.core.journal.SubmissionCoordinator
import assistant.mobile.core.journal.SubmissionReceiptValidator
import assistant.mobile.core.journal.SubmissionState
import assistant.mobile.core.journal.openJournalDatabase
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockEngineConfig
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import java.io.IOException
import java.nio.file.Files
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
class PersonalControllerTest {
    @Test
    fun profileConflictPreservesExactDurableDraftAndExplicitNextSaveUsesRereadRevision() = runTest {
        val fixture = PersonalControllerFixture(this)
        try {
            fixture.start()
            fixture.controller.beginProfile()
            fixture.controller.saveProfile()
            assertTrue(fixture.posts.isEmpty())
            assertNull(fixture.controller.state.value!!.error)
            fixture.controller.updateProfileDraft(fixture.controller.state.value!!.profileDraft!!.copy(heightCm = " 181,50 "))
            val typed = fixture.controller.state.value!!.profileDraft
            fixture.rejectNext = true
            fixture.controller.saveProfile()
            val conflict = fixture.controller.state.value!!
            assertTrue(conflict.conflict)
            assertEquals(typed, conflict.profileDraft)
            assertEquals(2L, conflict.profileDraft!!.revision)
            assertEquals(3L, conflict.overview.data!!.profile.revision)
            assertEquals("190", conflict.overview.data!!.profile.heightCm)
            assertEquals(typed, NativeJson.decodeFromString<PersonalProfileDraft>(fixture.store.draft(fixture.namespace, "personal/profile")!!.json.decodeToString()))
            assertEquals(listOf(HttpMethod.Post, HttpMethod.Get), fixture.operationRequests.map { it.method })
            fixture.controller.refresh()
            assertEquals(1, fixture.posts.size)
            fixture.controller.saveProfile()
            assertEquals(2, fixture.posts.size)
            assertEquals("3", fixture.body(fixture.posts.last()).getValue("expected_revisions").jsonObject.getValue(fixture.namespace.userId).jsonPrimitive.content)
            assertTrue(fixture.posts.first().headers["Idempotency-Key"] != fixture.posts.last().headers["Idempotency-Key"])
            assertEquals("181.50", fixture.controller.state.value!!.overview.data!!.profile.heightCm)
            assertNull(fixture.controller.state.value!!.profileDraft)
            assertNull(fixture.store.draft(fixture.namespace, "personal/profile"))
            assertEquals(1, fixture.serverWrites)
        } finally { fixture.close() }
    }

    @Test
    fun unknownWeightOutcomeChecksOriginalKeyAndNeverCreatesASecondPost() = runTest {
        val fixture = PersonalControllerFixture(this)
        try {
            fixture.start()
            fixture.controller.beginWeight()
            fixture.controller.updateWeightDraft(fixture.controller.state.value!!.weightDraft!!.copy(weightKg = " 72,50 "))
            fixture.loseNextReceipt = true
            fixture.controller.saveWeight()
            val pending = fixture.controller.state.value!!.pending.single()
            assertEquals(SubmissionState.UNCERTAIN, pending.state)
            assertNotNull(fixture.controller.state.value!!.weightDraft)
            fixture.controller.saveWeight()
            assertEquals(1, fixture.posts.size)
            fixture.controller.check(pending.key)
            fixture.controller.check(pending.key)
            assertEquals(1, fixture.posts.size)
            assertEquals(1, fixture.serverWrites)
            assertEquals(listOf(PERSONAL_LOOKUP + pending.key), fixture.operationRequests.filter { it.method == HttpMethod.Get }.map { it.url.encodedPath })
            assertEquals("72.50", fixture.controller.state.value!!.overview.data!!.metrics.getValue("weight").value)
            assertNull(fixture.controller.state.value!!.weightDraft)
            assertTrue(fixture.store.submissions(fixture.namespace).isEmpty())
        } finally { fixture.close() }
    }

    @Test
    fun startupRecoveryRereadsAfterLookupAndPreservesANewerDraft() = runTest {
        val fixture = PersonalControllerFixture(this)
        try {
            val sent = PersonalWeightDraft("72.50", "2026-10-17T14:30", "Europe/Moscow")
            val newer = sent.copy(weightKg = "73,25")
            fixture.seedUnknownWeight(sent, newer)
            fixture.start()
            assertEquals(newer, fixture.controller.state.value!!.weightDraft)
            assertEquals("72.50", fixture.controller.state.value!!.overview.data!!.metrics.getValue("weight").value)
            assertEquals("op-recovered", fixture.controller.state.value!!.receipt!!.operationId)
            assertTrue(fixture.posts.isEmpty())
            assertEquals(1, fixture.operationRequests.size)
            assertTrue(fixture.overviewReadsAfterLookup > 0)
            assertNotNull(fixture.store.draft(fixture.namespace, "personal/weight"))
        } finally { fixture.close() }
    }

    @Test
    fun revokedNutritionScopeCannotRepopulateAnInFlightPersonalNutritionRead() = runTest {
        val fixture = PersonalControllerFixture(this, nutrition = true)
        try {
            fixture.start()
            assertNotNull(fixture.controller.state.value!!.nutritionDay.data)
            fixture.nutritionGate = CompletableDeferred()
            val refreshing = backgroundScope.launch { fixture.controller.refresh() }
            fixture.nutritionStarted.first { it }
            fixture.identityScopes = listOf("personal.read", "personal.write")
            fixture.sessions.refreshIdentity()
            runCurrent()
            fixture.nutritionGate!!.complete(Unit)
            refreshing.join()
            assertNull(fixture.controller.state.value!!.nutritionDay.data)
        } finally { fixture.close() }
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
private class PersonalControllerFixture(private val test: TestScope, nutrition: Boolean = false) {
    private val directory = Files.createTempDirectory("native-personal-journal").toFile()
    private val database = openJournalDatabase(directory.resolve("journal.db"))
    private val durable = RoomJournalStore(database)
    var failJournalReads = false
    val store = object : JournalStore by durable {
        override suspend fun submissions(namespace: AccountNamespace): List<Submission> {
            if (failJournalReads) throw JournalStorageException(IOException("synthetic journal unavailable"))
            return durable.submissions(namespace)
        }
    }
    private val now = Instant.parse("2026-10-17T12:00:00Z").toEpochMilliseconds()
    private val record = featureRecord().let { it.copy(identity = it.identity.copy(timezone = "Europe/Moscow",
        scopes = listOf("personal.read", "personal.write") + if (nutrition) listOf("nutrition.read") else emptyList())) }
    val namespace = record.namespace
    var identityScopes = record.identity.scopes
    val sessions = SessionManager("synthetic", FEATURE_ORIGIN, FeatureSessionSecrets(record), store::clearAccount, { now })
    var profile = PersonalProfile(namespace.userId, "Europe/Moscow", PersonalDisplayUnits.Metric, "1990-01-01", "175", "male", 2, "manual")
    var preference = PersonalPreference("distance", "5000", 4)
    private var weight = "71"
    private var calipers = emptyList<PersonalCaliperMeasurement>()
    val requests = mutableListOf<HttpRequestData>()
    val operationRequests get() = requests.filter { it.url.encodedPath.startsWith(PERSONAL_OPERATIONS) }
    val posts get() = operationRequests.filter { it.method == HttpMethod.Post }
    var rejectNext = false
    var loseNextReceipt = false
    var serverWrites = 0
    var overviewReads = 0
    var overviewReadsAfterLookup = 0
    private var sawLookup = false
    private val receipts = mutableMapOf<String, String>()
    var nutritionGate: CompletableDeferred<Unit>? = null
    var caliperReadGate: CompletableDeferred<Unit>? = null
    val nutritionStarted = kotlinx.coroutines.flow.MutableStateFlow(false)
    val http = NativeHttpClient(MockEngine(MockEngineConfig().apply {
        dispatcher = StandardTestDispatcher(test.testScheduler)
        addHandler { request ->
            requests += request
            val path = request.url.encodedPath
            when {
                path.startsWith(PERSONAL_LOOKUP) -> {
                    sawLookup = true
                    val receipt = receipts[path.removePrefix(PERSONAL_LOOKUP)]
                    if (receipt == null) respond("{}", HttpStatusCode.NotFound) else respond(receipt)
                }
                path == PERSONAL_OPERATIONS -> {
                    if (rejectNext) {
                        rejectNext = false
                        profile = profile.copy(heightCm = "190", revision = profile.revision + 1)
                        respond("{}", HttpStatusCode.Conflict)
                    } else {
                        val body = body(request)
                        val payload = body.getValue("payload").jsonObject
                        val kind = body.getValue("kind").jsonPrimitive.content
                        val key = assertNotNull(request.headers["Idempotency-Key"])
                        assertFalse(key in receipts)
                        serverWrites++
                        val result = when (kind) {
                            "personal.profile.update" -> {
                                assertEquals(profile.revision.toString(), body.getValue("expected_revisions").jsonObject.getValue(namespace.userId).jsonPrimitive.content)
                                profile = profile.copy(heightCm = payload["height_cm"]?.jsonPrimitive?.contentOrNull ?: profile.heightCm,
                                    displayUnits = payload["display_units"]?.jsonPrimitive?.content?.let(PersonalDisplayUnits::fromWire) ?: profile.displayUnits,
                                    revision = profile.revision + 1)
                                "\"profile\":${profileJson()}"
                            }
                            "personal.preference.set" -> {
                                assertEquals(preference.revision.toString(), payload.getValue("revision").jsonPrimitive.content)
                                preference = PersonalPreference(payload.getValue("kind").jsonPrimitive.content, payload["target"]?.jsonPrimitive?.contentOrNull, preference.revision + 1)
                                "\"preference\":{\"kind\":\"${preference.kind}\",\"target\":${preference.target?.let { "\"$it\"" } ?: "null"},\"revision\":${preference.revision}}"
                            }
                            "personal.weight.add" -> {
                                weight = payload.getValue("weight_kg").jsonPrimitive.content
                                weightResult(weight, payload.getValue("measured_at").jsonPrimitive.content)
                            }
                            "personal.caliper.add" -> {
                                val measurement = caliperJson(payload)
                                calipers = listOf(decodePersonalCaliperMeasurement(NativeJson.parseToJsonElement(measurement).jsonObject))
                                "\"measurement_id\":\"caliper-1\",\"measurement\":$measurement"
                            }
                            else -> error("Unexpected personal operation $kind")
                        }
                        val receipt = "{\"operation_id\":\"op-$serverWrites\",\"kind\":\"$kind\",$result}"
                        receipts[key] = receipt
                        if (loseNextReceipt) { loseNextReceipt = false; throw IOException("synthetic response lost after commit") }
                        respond(receipt)
                    }
                }
                path == "/v1/nutrition/day" -> {
                    nutritionGate?.let { nutritionStarted.value = true; it.await() }
                    val content = assertNotNull(javaClass.classLoader.getResourceAsStream("food/day.json")).use { it.readBytes().decodeToString() }
                    respond(content.replace("2026-10-03", "2026-10-17"))
                }
                path == "/v1/me" -> respond(NativeJson.encodeToString(record.identity.copy(scopes = identityScopes)))
                else -> error("Unexpected personal request $path")
            }
        }
    }), FEATURE_ORIGIN, sessions, { now })
    private val reader = object : PersonalReader {
        override suspend fun overview(day: String, lease: SessionLease): PersonalOverview {
            overviewReads++; if (sawLookup) overviewReadsAfterLookup++
            return PersonalOverview(day, profile.timezone, profile, mapOf("weight" to PersonalMetric("weight", "available", weight, "kg")),
                null, mapOf(preference.kind to preference), emptyList())
        }
        override suspend fun resolved(day: String, lease: SessionLease) = PersonalResolvedPage(day, day, profile.timezone, "2026-10-17T12:00:00Z", emptyList(), "resolved-fingerprint", null)
        override suspend fun derivedScores(day: String, timezone: String, lease: SessionLease) = PersonalDerivedScoresPage(day, day, timezone, emptyList(), "derived-fingerprint", null, 0)
        override suspend fun caliper(day: String, lease: SessionLease): PersonalCaliperHistory {
            caliperReadGate?.await()
            return PersonalCaliperHistory(calipers.toList(), false, 100)
        }
        override suspend fun catalog(lease: SessionLease) = emptyList<PersonalCatalogMetric>()
    }
    private val submissions = SubmissionCoordinator(store, http, sessions, { now }, SubmissionReceiptValidator(::validatePersonalSubmissionReceipt))
    val controller = PersonalController(test.backgroundScope, sessions, http, submissions, AccountDrafts(store, sessions, { now }), { now }, reader)

    suspend fun start() {
        sessions.restore()
        controller.activate("2026-10-17")
        controller.state.first { it?.overview?.data != null && it.caliper.data != null }
        controller.refresh()
    }

    suspend fun seedUnknownWeight(sent: PersonalWeightDraft, stored: PersonalWeightDraft) {
        val key = "11111111-1111-4111-8111-111111111111"
        val operation = weightPersonalOperation(sent, sent.timezone, now)
        durable.saveDraft(Draft(namespace, "personal/weight", NativeJson.encodeToString(stored).encodeToByteArray(), 8, now))
        durable.insertSubmission(Submission(namespace, key, PERSONAL_OPERATIONS, PERSONAL_LOOKUP + key, "POST", operation.bytes(), "personal",
            emptyMap(), "personal/weight", SubmissionState.RESOLVED, now, state = SubmissionState.SENDING, mayHaveBeenAdmitted = true, attempt = 1))
        weight = sent.weightKg
        receipts[key] = "{\"operation_id\":\"op-recovered\",\"kind\":\"personal.weight.add\",${weightResult(sent.weightKg, operation.payload.getValue("measured_at").jsonPrimitive.content)}}"
    }

    fun body(request: HttpRequestData) = NativeJson.parseToJsonElement(featureBody(request.body).decodeToString()).jsonObject
    private fun profileJson(): String = """{"user_id":"${profile.userId}","timezone":"${profile.timezone}","display_units":"${profile.displayUnits.wire}","birth_date":"${profile.birthDate}","height_cm":"${profile.heightCm}","gender":"${profile.gender}","gender_origin":"manual","revision":${profile.revision}}"""
    private fun weightResult(amount: String, time: String): String = """"measurement_id":"weight-1","weight_kg":"$amount","record":{"id":"weight-1","kind":"weight","method":"self_report","start_at":"$time","revision":1,"data":{"value":"$amount"}}"""
    private fun caliperJson(payload: JsonObject): String = """{"id":"caliper-1","source_id":"caliper-source","measured_at":${payload.getValue("measured_at")},"timezone":"Europe/Moscow","folds_mm":${payload.getValue("folds_mm")},"gender":"male","age_years":${payload["age_years"] ?: JsonPrimitive(36)},"note":null,"body_fat_percent":"20.56","calculation":{"status":"calculated","method":"jackson_pollock_3_siri","missing_fields":[],"algorithm_version":"jackson_pollock_3_siri_v1"},"revision":1,"body_fat_record_id":"body-fat-1"}"""

    fun close() {
        controller.deactivate(); test.runCurrent(); http.close(); database.close(); directory.deleteRecursively()
    }
}
