package assistant.mobile.core.food

import assistant.mobile.core.AccountDrafts
import assistant.mobile.core.FEATURE_ORIGIN
import assistant.mobile.core.FeatureSessionSecrets
import assistant.mobile.core.NativeHttpClient
import assistant.mobile.core.NativeJson
import assistant.mobile.core.SessionManager
import assistant.mobile.core.featureBody
import assistant.mobile.core.featureRecord
import assistant.mobile.core.journal.RoomJournalStore
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
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
class FoodAddFlowTest {
    @Test
    fun searchSelectPreviewThenExplicitRecordUsesTypedSourceAndReceipt() = runTest {
        val fixture = FoodAddFixture(this)
        try {
            fixture.start()
            fixture.controller.openAdd()
            fixture.controller.searchFoods(" Творог & молоко ")
            val search = fixture.requests.last { it.url.encodedPath.endsWith("foods/search") }
            assertEquals("Творог & молоко", search.url.parameters["q"])
            assertEquals("Творог & молоко", fixture.requests.last { it.url.encodedPath.endsWith("compositions") }.url.parameters["q"])
            fixture.controller.selectFood(fixture.controller.additions.value!!.foods.data!!.single())
            fixture.controller.updatePortion("150,25")
            fixture.controller.estimatePortion()
            assertEquals("181.5", fixture.controller.additions.value!!.estimate.data!!.totals["energy_kcal"]!!.known)
            assertTrue(fixture.posts.isEmpty())
            assertTrue(fixture.store.submissions(fixture.namespace).isEmpty())
            assertEquals(0, fixture.writes)
            fixture.controller.recordPortion()
            assertEquals(1, fixture.writes)
            assertEquals("entry.add_many", fixture.controller.state.value!!.receipt!!.kind)
            assertEquals("150.25", body(fixture.posts.single()).obj("payload").array("items").single().jsonObject.text("quantity"))
            assertTrue(body(fixture.posts.single()).obj("expected_revisions").isEmpty())
            assertNotNull(fixture.posts.single().headers["Idempotency-Key"])
            assertFalse(fixture.controller.additions.value!!.open)
            assertTrue(fixture.store.submissions(fixture.namespace).isEmpty())
        } finally { fixture.close() }
    }

    @Test
    fun estimateFailureOrRecordedTrueCannotWriteAnything() = runTest {
        val fixture = FoodAddFixture(this)
        try {
            fixture.start()
            fixture.controller.openAdd()
            fixture.controller.selectFood(fixture.controller.additions.value!!.foods.data!!.single())
            fixture.badEstimate = true
            fixture.controller.estimatePortion()
            assertNull(fixture.controller.additions.value!!.estimate.data)
            assertNotNull(fixture.controller.additions.value!!.estimate.error)
            assertTrue(fixture.posts.isEmpty())
            assertTrue(fixture.store.submissions(fixture.namespace).isEmpty())
        } finally { fixture.close() }
    }

    @Test
    fun lostReceiptChecksOriginalKeyAndExplicitRetryReusesExactBody() = runTest {
        val fixture = FoodAddFixture(this)
        try {
            fixture.start()
            fixture.controller.openAdd()
            fixture.controller.selectFood(fixture.controller.additions.value!!.foods.data!!.single())
            fixture.failBeforeCommit = true
            fixture.controller.recordPortion()
            val pending = fixture.controller.state.value!!.pending.single()
            assertEquals(SubmissionState.UNCERTAIN, pending.state)
            fixture.controller.check(pending.key)
            assertEquals(404, fixture.controller.state.value!!.pending.single().failureStatus)
            assertEquals(1, fixture.posts.size)
            fixture.controller.retry(pending.key)
            assertEquals(2, fixture.posts.size)
            assertEquals(fixture.posts.first().headers["Idempotency-Key"], fixture.posts.last().headers["Idempotency-Key"])
            assertTrue(featureBody(fixture.posts.first().body).contentEquals(featureBody(fixture.posts.last().body)))
            assertEquals(1, fixture.writes)
            assertTrue(fixture.controller.state.value!!.pending.isEmpty())
            assertFalse(fixture.controller.additions.value!!.open)
        } finally { fixture.close() }
    }

    @Test
    fun twoTapsDuringDeliveryProduceOneOperation() = runTest {
        val fixture = FoodAddFixture(this)
        try {
            fixture.start()
            fixture.controller.openAdd()
            fixture.controller.selectFood(fixture.controller.additions.value!!.foods.data!!.single())
            val release = CompletableDeferred<Unit>()
            fixture.holdWrite = release
            val first = launch { fixture.controller.recordPortion() }
            runCurrent()
            fixture.controller.recordPortion()
            release.complete(Unit)
            first.join()
            assertEquals(1, fixture.posts.size)
            assertEquals(1, fixture.writes)
        } finally { fixture.close() }
    }
}

private fun body(request: HttpRequestData) = NativeJson.parseToJsonElement(featureBody(request.body).decodeToString()).jsonObject

@OptIn(ExperimentalCoroutinesApi::class)
private class FoodAddFixture(private val test: TestScope) {
    private val directory = Files.createTempDirectory("native-food-add").toFile()
    private val database = openJournalDatabase(directory.resolve("journal.db"))
    val store = RoomJournalStore(database)
    private val now = Instant.parse("2026-10-03T12:00:00Z").toEpochMilliseconds()
    private val record = featureRecord().let { it.copy(identity = it.identity.copy(timezone = "Europe/Moscow", scopes = listOf("nutrition.read", "nutrition.write"))) }
    val namespace = record.namespace
    val sessions = SessionManager("synthetic", FEATURE_ORIGIN, FeatureSessionSecrets(record), store::clearAccount, { now })
    val requests = mutableListOf<HttpRequestData>()
    val posts get() = requests.filter { it.url.encodedPath == FOOD_OPERATIONS && it.method == HttpMethod.Post }
    var writes = 0
    var loggedEntries = 0
    var favorite = false
    var badEstimate = false
    var failCatalogReads = false
    var failBeforeCommit = false
    var holdWrite: CompletableDeferred<Unit>? = null
    private val receipts = mutableMapOf<String, String>()
    val http = NativeHttpClient(MockEngine(MockEngineConfig().apply {
        dispatcher = StandardTestDispatcher(test.testScheduler)
        addHandler { request ->
            requests += request
            val path = request.url.encodedPath
            when {
                path == "/v1/nutrition/day" -> respond(foodFixture("day"))
                path == "/v1/nutrition/days" -> respond(foodFixture("week"))
                path == "/v1/nutrition/estimate" -> respond(foodFixture("estimate").decodeToString().let {
                    if (badEstimate) it.replace("\"recorded\": false", "\"recorded\": true") else it
                })
                path in setOf("/v1/nutrition/foods/search", "/v1/nutrition/memory", "/v1/nutrition/compositions") -> {
                    if (failCatalogReads) respond("{}", HttpStatusCode.InternalServerError)
                    else {
                        val name = when { path.endsWith("search") -> "catalog"; path.endsWith("memory") -> "memory"; else -> "compositions" }
                        var json = foodFixture(name).decodeToString()
                        if (favorite && name == "memory") json = json.replace("\"favorite\": false", "\"favorite\": true")
                            .replace("\"preference_revision\": 0", "\"preference_revision\": 1")
                        respond(json)
                    }
                }
                path.startsWith(FOOD_LOOKUP) -> receipts[path.removePrefix(FOOD_LOOKUP)]?.let { respond(it) }
                    ?: respond("{}", HttpStatusCode.NotFound)
                path == FOOD_OPERATIONS -> {
                    holdWrite?.await()
                    if (failBeforeCommit) {
                        failBeforeCommit = false
                        throw IOException("synthetic transport failure before server admission")
                    }
                    val key = assertNotNull(request.headers["Idempotency-Key"])
                    assertFalse(receipts.containsKey(key), "An admitted operation was resent")
                    val command = body(request)
                    val kind = command.text("kind")
                    writes++
                    val receipt = if (kind == "food.favorite") {
                        favorite = true
                        """{"operation_id":"op-$writes","kind":"food.favorite","food_id":"curd","favorite":true,"revision":1}"""
                    } else {
                        loggedEntries++
                        val aggregate = if (kind in setOf("dish.log", "composition.log")) ",\"dish_id\":\"new-dish-$writes\",\"revision\":1" else ""
                        """{"operation_id":"op-$writes","kind":"$kind","date":"2026-10-03","status":"applied","applied_entries":[{"entry_id":"new-entry-$writes","revision":1}]$aggregate}"""
                    }
                    receipts[key] = receipt
                    respond(receipt)
                }
                else -> error("Unexpected nutrition request: $path")
            }
        }
    }), FEATURE_ORIGIN, sessions, { now })
    private val submissions = SubmissionCoordinator(store, http, sessions, { now }, SubmissionReceiptValidator(::validateFoodSubmissionReceipt))
    val controller = FoodController(test.backgroundScope, sessions, http, submissions, AccountDrafts(store, sessions, { now }), { now })
    suspend fun start() {
        sessions.restore()
        controller.activate("2026-10-03")
        controller.state.first { it?.day?.data != null && it.week.data != null }
        controller.refresh()
    }
    fun close() {
        controller.deactivate()
        test.runCurrent()
        http.close()
        database.close()
        directory.deleteRecursively()
    }
}
