package assistant.mobile.core.notifications

import assistant.mobile.core.*
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockEngineConfig
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import java.io.IOException
import kotlin.test.*
import kotlin.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
class NativeNotificationCoordinatorTest {
    @Test
    fun acknowledgeContainsOnlyKeysAcceptedByTheAlarmBackend() = runTest {
        val fixture = CoordinatorFixture(this)
        try {
            fixture.prepare()
            fixture.rejectedKeys += "reminder:r2:2026-10-16T12:02:00Z"
            assertIs<HttpOutcome.Success>(fixture.coordinator.sync())
            val ack = fixture.body(fixture.acks.single())
            assertEquals(listOf(fixture.firstKey), ack.getValue("installed").jsonArray.map { it.jsonPrimitive.content })
            assertEquals(listOf(fixture.firstKey), fixture.platform.engine.snapshot.installed.map { it.key })
            assertNull(fixture.platform.pendingAck())
            assertFalse(fixture.coordinator.state.value.schedulePending)
        } finally { fixture.close() }
    }

    @Test
    fun lostAckReplyRetriesTheSameDurableSnapshotAndDoesNotInstallTwice() = runTest {
        val fixture = CoordinatorFixture(this)
        try {
            fixture.prepare()
            fixture.ackOutcomes += -1 // The server may have committed before the connection disappeared.
            assertIs<HttpOutcome.Success>(fixture.coordinator.sync())
            assertEquals(1, fixture.gets.size)
            assertEquals(1, fixture.platform.installs)
            assertEquals(2, fixture.acks.size)
            assertContentEquals(featureBody(fixture.acks[0].body), featureBody(fixture.acks[1].body))
            assertNull(fixture.platform.pendingAck())
        } finally { fixture.close() }
    }

    @Test
    fun revokedLeaseWhileSnapshotIsLoadingCannotInstallOrAcknowledge() = runTest {
        val fixture = CoordinatorFixture(this)
        try {
            fixture.prepare()
            val gate = CompletableDeferred<Unit>()
            fixture.snapshotGate = gate
            val sync = async { fixture.coordinator.sync() }
            runCurrent()
            fixture.sessions.expire(fixture.sessions.lease()!!)
            gate.complete(Unit)
            assertEquals(HttpOutcome.StaleSession, sync.await())
            assertEquals(0, fixture.platform.installs)
            assertTrue(fixture.acks.isEmpty())
        } finally { fixture.close() }
    }

    @Test
    fun boundSessionExpiresWithoutAnotherForegroundRequestAndRevocationClearsAlarms() = runTest {
        val fixture = CoordinatorFixture(this, lifetimeMillis = 120_000)
        try {
            fixture.prepare()
            fixture.coordinator.start()
            runCurrent()
            assertTrue(fixture.platform.engine.snapshot.installed.isNotEmpty())
            val requests = fixture.requests.size
            fixture.now += 120_000
            advanceTimeBy(120_000)
            runCurrent()
            assertNull(fixture.sessions.lease())
            assertNull(fixture.platform.engine.snapshot.session)
            assertTrue(fixture.platform.engine.snapshot.installed.isEmpty())
            assertEquals(requests, fixture.requests.size)
            assertFalse(fixture.coordinator.state.value.enabled)
        } finally { fixture.close() }
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
private class CoordinatorFixture(private val test: TestScope, lifetimeMillis: Long = 3_600_000) {
    var now = FEATURE_NOW
    private val expiry = Instant.fromEpochMilliseconds(now + lifetimeMillis).toString()
    val record = featureRecord().let { it.copy(expiresAt = expiry, identity = it.identity.copy(sessionExpiresAt = expiry)) }
    val sessions = SessionManager("synthetic", FEATURE_ORIGIN, FeatureSessionSecrets(record), {}, { now })
    val requests = mutableListOf<HttpRequestData>()
    val gets get() = requests.filter { it.method == HttpMethod.Get }
    val acks get() = requests.filter { it.method == HttpMethod.Post }
    val ackOutcomes = mutableListOf<Int>()
    val rejectedKeys = mutableSetOf<String>()
    val firstKey = "timer:t1:2026-10-16T12:01:00Z"
    var malformedAck = false
    var snapshotGate: CompletableDeferred<Unit>? = null
    val platform = CoordinatorPlatform({ now }, rejectedKeys)
    val http = NativeHttpClient(MockEngine(MockEngineConfig().apply {
        dispatcher = StandardTestDispatcher(test.testScheduler)
        addHandler { request ->
            requests += request
            when (request.url.encodedPath) {
                "/v1/notifications/schedule" -> {
                    snapshotGate?.await()
                    val sequence = gets.size.toString().padStart(12, '0')
                    val schedule = NotificationSchedule("00000000-0000-4000-8000-$sequence",
                        Instant.fromEpochMilliseconds(now).toString(), expiry,
                        listOf(NotificationScheduleItem(firstKey, 1, "2026-10-16T12:01:00Z", "Таймер", "", "/timers"),
                            NotificationScheduleItem("reminder:r2:2026-10-16T12:02:00Z", 1, "2026-10-16T12:02:00Z", "Напоминание", "", "/timers")))
                    respond(NativeJson.encodeToString(NotificationSchedule.serializer(), schedule))
                }
                "/v1/notifications/schedule/ack" -> {
                    val status = if (ackOutcomes.isEmpty()) 200 else ackOutcomes.removeAt(0)
                    if (status == -1) throw IOException("synthetic ACK response lost")
                    if (status != 200) respond("""{"error":"conflict"}""", HttpStatusCode.fromValue(status))
                    else respond(if (malformedAck) """{"installed":["unaccepted"]}""" else
                        buildJsonObject { put("installed", body(request).getValue("installed")) }.toString())
                }
                else -> error("Unexpected schedule request: ${request.url.encodedPath}")
            }
        }
    }), FEATURE_ORIGIN, sessions, { now })
    val coordinator = NativeNotificationCoordinator(test.backgroundScope, sessions, http, platform, { now })
    fun body(request: HttpRequestData) = notificationObject(featureBody(request.body))
    suspend fun prepare() {
        sessions.restore()
        platform.bind(NotificationSession(record.namespace.instance, record.namespace.origin, record.namespace.userId,
            record.deviceId, record.sessionId, Instant.parse(expiry).toEpochMilliseconds()))
        platform.setEnabled(true)
    }
    fun close() { http.close() }
}

private class CoordinatorPlatform(private val now: () -> Long, private val rejectedKeys: Set<String>) : NotificationAlarmPlatform {
    var installs = 0
    val engine = NotificationAlarmEngine(object : NotificationAlarmBackend {
        override fun save(snapshot: NotificationAlarmSnapshot) = true
        override fun arm(alarm: InstalledNotificationAlarm) = alarm.key !in rejectedKeys
        override fun cancel(alarm: InstalledNotificationAlarm) {}
        override fun cancelDisplayed() {}
    })
    override fun state() = NotificationDeviceState(NotificationPermission.Granted,
        engine.snapshot.enabled && engine.snapshot.session?.active(now()) == true, true, engine.snapshot.pendingAck != null)
    override fun bind(session: NotificationSession) { engine.bind(session) }
    override fun clear() { engine.clear() }
    override fun setEnabled(enabled: Boolean) { engine.setEnabled(enabled) }
    override fun install(snapshot: NotificationSchedule): List<String> { installs++; return engine.replace(snapshot, now(), true) }
    override fun pendingAck() = engine.snapshot.pendingAck
    override fun acknowledged(snapshotId: String) { engine.acknowledged(snapshotId) }
}
