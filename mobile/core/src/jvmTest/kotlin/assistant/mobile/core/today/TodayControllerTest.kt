package assistant.mobile.core.today

import assistant.mobile.core.FEATURE_ORIGIN
import assistant.mobile.core.FeatureSessionSecrets
import assistant.mobile.core.NativeHttpClient
import assistant.mobile.core.NativeJson
import assistant.mobile.core.SessionManager
import assistant.mobile.core.featureRecord
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockEngineConfig
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
class TodayControllerTest {
    @Test
    fun allQueriesUseExistingReadOnlyContractsAndOneErrorDoesNotHideOtherCards() = runTest {
        val fixture = DashboardFixture(this)
        fixture.failed = "/v1/nutrition/day"
        try {
            fixture.start()
            val state = fixture.today.state.value!!
            assertEquals("2026-10-16", state.day)
            assertNotNull(state.food!!.error)
            assertNull(state.food.data)
            assertEquals("Synthetic today task", state.tasks!!.data!!.items.single().title)
            assertEquals("3200", state.personal!!.data!!.steps.value)
            assertEquals(7, fixture.requests.size)
            val paths = fixture.requests.associateBy { it.url.encodedPath }
            assertEquals("2026-10-16", paths.getValue("/v1/nutrition/day").url.parameters["date"])
            assertEquals("today", paths.getValue("/v1/tasks/items").url.parameters["view"])
            assertEquals("created", paths.getValue("/v1/tasks/items").url.parameters["sort"])
            assertEquals("5", paths.getValue("/v1/tasks/items").url.parameters["limit"])
            assertEquals("50", paths.getValue("/v1/notifications").url.parameters["limit"])
            assertEquals("2026-09-16", paths.getValue("/v1/personal/history").url.parameters["from"])
            assertEquals("2026-10-16", paths.getValue("/v1/personal/history").url.parameters["to"])
            assertEquals("weight", paths.getValue("/v1/personal/history").url.parameters["kind"])
            assertTrue(fixture.requests.all { it.method.value == "GET" && it.headers["Idempotency-Key"] == null })
            fixture.failed = null
            fixture.today.refreshCard(TodayCard.Food)
            fixture.failed = "/v1/nutrition/day"
            fixture.today.refreshCard(TodayCard.Food)
            assertTrue(fixture.today.state.value!!.food!!.stale)
            assertEquals("1234.50", fixture.today.state.value!!.food!!.data!!.energyKcal)
            assertNull(fixture.today.state.value!!.tasks!!.error)
        } finally { fixture.close() }
    }

    @Test
    fun absentScopesPreventRequestsAndRevocationImmediatelyRemovesCachedPrivateCards() = runTest {
        val fixture = DashboardFixture(this)
        try {
            fixture.start()
            fixture.identity = fixture.identity.copy(scopes = listOf("tasks.read"), revision = 2)
            fixture.sessions.refreshIdentity(fixture.sessions.lease()!!)
            runCurrent()
            val state = fixture.today.state.value!!
            assertNull(state.food)
            assertNull(state.timers)
            assertNull(state.personal)
            assertNull(state.steps)
            assertNull(state.weightHistory)
            assertNull(state.notifications)
            fixture.requests.clear()
            fixture.today.refresh()
            assertEquals(listOf("/v1/tasks/items"), fixture.requests.map { it.url.encodedPath })
        } finally { fixture.close() }
    }

    @Test
    fun lateResponseCannotRestoreCardAfterPermissionRevocation() = runTest {
        val fixture = DashboardFixture(this)
        try {
            fixture.start()
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            fixture.beforeReply = { request ->
                if (request.url.encodedPath == "/v1/nutrition/day") { entered.complete(Unit); release.await() }
            }
            val pending = async { fixture.today.refreshCard(TodayCard.Food) }
            entered.await()
            fixture.identity = fixture.identity.copy(scopes = listOf("tasks.read"), revision = 2)
            fixture.sessions.refreshIdentity(fixture.sessions.lease()!!)
            runCurrent()
            assertNull(fixture.today.state.value!!.food)
            release.complete(Unit)
            pending.await()
            assertNull(fixture.today.state.value!!.food)
        } finally { fixture.close() }
    }

    @Test
    fun sessionExpiryClearsAllDashboardData() = runTest {
        val fixture = DashboardFixture(this)
        try {
            fixture.start()
            fixture.sessions.expire(fixture.sessions.lease()!!)
            runCurrent()
            assertNull(fixture.today.state.value)
        } finally { fixture.close() }
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
private class DashboardFixture(val test: TestScope) {
    var now = Instant.parse("2026-10-15T21:30:00Z").toEpochMilliseconds()
    var timerNow: Long? = null
    private val record = featureRecord().let { it.copy(identity = it.identity.copy(
        scopes = listOf("session.control", "nutrition.read", "tasks.read", "timers.read", "personal.read"))) }
    var identity = record.identity
    val requests = mutableListOf<HttpRequestData>()
    var failed: String? = null
    var wrongDay: String? = null
    var weightValue = "72.45"
    var beforeReply: suspend (HttpRequestData) -> Unit = {}
    val sessions = SessionManager("synthetic", FEATURE_ORIGIN, FeatureSessionSecrets(record), {}, { now })
    val http = NativeHttpClient(MockEngine(MockEngineConfig().apply {
        dispatcher = StandardTestDispatcher(test.testScheduler)
        addHandler { request ->
            requests += request
            val requestZone = identity.timezone
            val requestedDay = request.url.parameters["date"] ?: todayIn(requestZone, now)
            val requestedWeight = weightValue
            beforeReply(request)
            val path = request.url.encodedPath
            if (path == "/v1/me") respond(NativeJson.encodeToString(identity))
            else if (path == failed) respond("", HttpStatusCode.ServiceUnavailable)
            else {
                val source = when (path) {
                    "/v1/nutrition/day" -> "nutrition"
                    "/v1/tasks/items" -> "tasks"
                    "/v1/timers/running" -> "timers"
                    "/v1/notifications" -> "notifications"
                    "/v1/personal/overview" -> "personal"
                    "/v1/personal/steps" -> "steps"
                    "/v1/personal/history" -> "history"
                    else -> error("Unexpected dashboard request")
                }
                val day = if (path == wrongDay) "2026-10-15" else requestedDay
                respond(todayFixture("full", source).decodeToString().replace("2026-10-16", day)
                    .replace("Europe/Moscow", requestZone).replace("\"72.45\"", "\"$requestedWeight\""))
            }
        }
    }), FEATURE_ORIGIN, sessions, { now })
    val today = TodayController(test.backgroundScope, sessions, http, { now }, { timerNow ?: now })

    suspend fun start() {
        sessions.restore()
        today.activate()
        test.runCurrent()
    }
    fun close() { today.deactivate(); http.close() }
}
