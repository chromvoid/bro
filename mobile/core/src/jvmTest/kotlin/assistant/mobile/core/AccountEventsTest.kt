package assistant.mobile.core

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockEngineConfig
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteChannel
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.writeStringUtf8
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class AccountEventsTest {
    @Test
    fun singleForegroundStreamPersistsOnlyAfterProcessingThenReplaysFromCursor() = runTest {
        val fixture = EventFixture(this)
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        fixture.effects.beforeInvalidation = {
            entered.complete(Unit)
            release.await()
        }
        fixture.events.setForeground(true)
        fixture.events.setForeground(true)
        runCurrent()
        assertEquals(listOf("identity:a", "recover:a"), fixture.order)
        assertEquals(1, fixture.requests.size)
        val first = fixture.requests.single()
        assertEquals("latest", first.url.parameters["cursor"])
        assertNull(first.headers["Last-Event-ID"])
        assertEquals("Bearer synthetic-a", first.headers["Authorization"])
        assertEquals("a", first.headers["X-Expected-User"])
        fixture.channels.single().writeStringUtf8(change(7, "tasks.changed"))
        runCurrent()
        assertTrue(entered.isCompleted)
        assertNull(fixture.cursors.values[fixture.namespace("a")])
        release.complete(Unit)
        runCurrent()
        assertEquals("7", fixture.cursors.values[fixture.namespace("a")])

        fixture.events.setForeground(false)
        runCurrent()
        assertTrue(fixture.channels.first().isClosedForRead)
        fixture.events.setForeground(true)
        runCurrent()
        assertEquals(2, fixture.requests.size)
        assertNull(fixture.requests.last().url.parameters["cursor"])
        assertEquals("7", fixture.requests.last().headers["Last-Event-ID"])
        // Replayed duplicates do not reprocess a domain or move its durable cursor backward.
        fixture.channels.last().writeStringUtf8(change(7, "tasks.changed"))
        runCurrent()
        assertEquals(1, fixture.effects.invalidations.size)
        fixture.close()
    }

    @Test
    fun resyncRefetchesAndRecoversAndAccessRefreshesIdentityBeforeInvalidating() = runTest {
        val fixture = EventFixture(this)
        fixture.events.setForeground(true)
        runCurrent()
        fixture.channels.single().writeStringUtf8(
            "id: 1\nevent: resync\ndata: {}\n\n" +
                change(2, "access.changed") + change(3, "future.domain.changed"),
        )
        runCurrent()
        assertEquals(
            listOf(
                "identity:a", "recover:a", "identity:a", "refetch:a", "recover:a",
                "identity:a", "invalidate:a", "invalidate:a",
            ),
            fixture.order,
        )
        assertEquals(listOf<EventInvalidation>(EventInvalidation.All, EventInvalidation.All), fixture.effects.invalidations)
        assertEquals("3", fixture.cursors.values[fixture.namespace("a")])
        assertEquals(3L, fixture.sessions.identity.value?.revision)
        fixture.close()
    }

    @Test
    fun unauthorizedClosesSessionAndForbiddenStopsWithoutLoginLoop() = runTest {
        for (status in listOf(HttpStatusCode.Unauthorized, HttpStatusCode.Forbidden)) {
            val fixture = EventFixture(this, denied = status)
            fixture.events.setForeground(true)
            runCurrent()
            advanceTimeBy(120_000)
            runCurrent()
            assertEquals(1, fixture.requests.size, "No reconnect after $status")
            if (status == HttpStatusCode.Unauthorized) {
                assertNull(fixture.sessions.lease())
                assertNull(fixture.secrets.record)
            } else {
                assertNotNull(fixture.sessions.lease())
                assertIs<AccountEventState.Denied>(fixture.events.state.value)
                assertEquals(listOf("identity:a", "recover:a", "identity:a"), fixture.order)
            }
            fixture.close()
        }
    }

    @Test
    fun switchingAccountsCancelsOldProcessingAndKeepsCursorsIsolated() = runTest {
        val fixture = EventFixture(this)
        val entered = CompletableDeferred<Unit>()
        val never = CompletableDeferred<Unit>()
        fixture.effects.beforeInvalidation = {
            if (fixture.sessions.lease()?.namespace?.userId == "a") {
                entered.complete(Unit)
                never.await()
            }
        }
        fixture.events.setForeground(true)
        runCurrent()
        fixture.channels.first().writeStringUtf8(change(11, "tasks.changed"))
        runCurrent()
        assertTrue(entered.isCompleted)
        assertIs<SessionResult.SignedIn>(fixture.sessions.login("b", "synthetic-password"))
        runCurrent()
        assertTrue(fixture.channels.first().isClosedForRead)
        assertNull(fixture.cursors.values[fixture.namespace("a")])
        assertEquals("b", fixture.requests.last().headers["X-Expected-User"])
        assertEquals("latest", fixture.requests.last().url.parameters["cursor"])
        fixture.channels.last().writeStringUtf8(change(12, "tasks.changed"))
        runCurrent()
        assertNull(fixture.cursors.values[fixture.namespace("a")])
        assertEquals("12", fixture.cursors.values[fixture.namespace("b")])
        fixture.close()
    }

    @Test
    fun unavailableCursorNeverBecomesLatestAndFailedSaveStopsStream() = runTest {
        for (failRead in listOf(true, false)) {
            val fixture = EventFixture(this)
            fixture.cursors.failRead = failRead
            fixture.cursors.failWrite = !failRead
            fixture.events.setForeground(true)
            runCurrent()
            if (!failRead) {
                fixture.channels.single().writeStringUtf8(change(21, "tasks.changed"))
                runCurrent()
            }
            assertIs<AccountEventState.StorageUnavailable>(fixture.events.state.value)
            advanceTimeBy(120_000)
            runCurrent()
            assertEquals(if (failRead) 0 else 1, fixture.requests.size)
            assertTrue(fixture.cursors.values.isEmpty())
            fixture.close()
        }
    }

    @Test
    fun parserPreservesSplitUtf8CrLfAndMultilineDataWithoutDispatchingHeartbeat() {
        val parser = SseByteParser()
        val bytes = "\uFEFF: heartbeat\r\nid: 5\r\nevent: assistant.changed\r\ndata: {\"topic\":\r\ndata: \"питание.changed\"}\r\n\r\n"
            .encodeToByteArray()
        val actual = bytes.flatMap { parser.push(byteArrayOf(it)) }
        assertEquals(
            listOf(SseMessage("5", "assistant.changed", "{\"topic\":\n\"питание.changed\"}")),
            actual,
        )
        assertEquals(EventInvalidation.All, affectedDomains("future.changed"))
        assertEquals(EventInvalidation.All, affectedDomains("workers.changed"))
        assertEquals(EventInvalidation.Domains(setOf(EventDomain.Activity)), affectedDomains("activity.changed"))
        for (topic in listOf("resource", "resource.changed")) {
            assertEquals(EventInvalidation.Domains(setOf(EventDomain.Community, EventDomain.Shared,
                EventDomain.Tasks, EventDomain.Challenges)), affectedDomains(topic))
        }
        for (topic in listOf("access", "access.changed")) assertEquals(EventInvalidation.All, affectedDomains(topic))
        for (topic in listOf("challenges", "challenges.changed")) {
            assertEquals(EventInvalidation.Domains(setOf(EventDomain.Challenges)), affectedDomains(topic))
        }
        assertEquals(
            EventInvalidation.Domains(setOf(EventDomain.Calls)), affectedDomains("calls.changed"),
        )
    }
}

private fun change(id: Long, topic: String): String =
    "id: $id\nevent: assistant.changed\ndata: {\"topic\":\"$topic\"}\n\n"

@OptIn(ExperimentalCoroutinesApi::class)
private class EventFixture(
    private val scope: TestScope,
    private val denied: HttpStatusCode? = null,
    private val rateLimitFirst: Boolean = false,
    private val emptyStreams: Boolean = false,
) {
    private val origin = "https://events.synthetic.invalid"
    val order = mutableListOf<String>()
    val requests = mutableListOf<HttpRequestData>()
    val channels = mutableListOf<ByteChannel>()
    val cursors = MemoryEventCursors()
    val secrets = EventSecrets(stored("a"))
    val sessions = SessionManager("synthetic-instance", origin, secrets, onLogout = {},
        clockMs = { scope.testScheduler.currentTime })
    private var identityRevision = 0L
    private val engine = MockEngine(MockEngineConfig().apply {
        dispatcher = StandardTestDispatcher(scope.testScheduler)
        addHandler { request ->
            when (request.url.encodedPath) {
                "/v1/me" -> {
                    val user = request.headers["X-Expected-User"] ?: error("Missing account binding")
                    order.add("identity:$user")
                    respond(NativeJson.encodeToString(identity(user).copy(revision = ++identityRevision)),
                        headers = headersOf("Content-Type", "application/json"))
                }
                "/v1/auth/native/login" -> {
                    val record = stored("b")
                    respond(NativeJson.encodeToString(NativeSessionReply(
                        record.credential, record.identity,
                        NativeDevice(record.deviceId, "android", "Synthetic"),
                        NativeSessionDescriptor(record.sessionId, "native"),
                        record.expiresAt, record.identity.scopes,
                    )), headers = headersOf("Content-Type", "application/json"))
                }
                "/v1/me/events" -> {
                    requests.add(request)
                    when {
                        denied != null -> respond("{}", denied)
                        rateLimitFirst && requests.size == 1 -> respond(
                            "{}", HttpStatusCode.TooManyRequests, headersOf("Retry-After", "8"),
                        )
                        emptyStreams -> respond("", headers = headersOf("Content-Type", "text/event-stream"))
                        else -> {
                            val channel = ByteChannel(autoFlush = true)
                            channels.add(channel)
                            respond(channel as ByteReadChannel, headers = headersOf("Content-Type", "text/event-stream"))
                        }
                    }
                }
                else -> error("Unexpected synthetic request: ${request.url.encodedPath}")
            }
        }
    })
    private val http = NativeHttpClient(engine, origin, sessions, clockMs = { scope.testScheduler.currentTime })
    val effects = RecordingEventEffects(order)
    val events = AccountEvents(scope.backgroundScope, sessions, http, cursors, effects,
        EventStreamPolicy(random = { 1.0 }, nowMillis = { scope.testScheduler.currentTime }))

    init {
        // restore() never performs HTTP, and this fake credential store never suspends.
        scope.backgroundScope.launch { sessions.restore() }
    }

    fun namespace(user: String): AccountNamespace = AccountNamespace("synthetic-instance", origin, user)

    suspend fun close() {
        events.close()
        http.close()
        engine.close()
    }

    private fun identity(user: String) = NativeIdentity(
        user, user, "Synthetic $user", "ru", "Europe/Moscow", false, 0,
        "device-$user", "2099-01-01T00:00:00Z", listOf("session.control", "tasks.read"),
    )

    private fun stored(user: String): StoredSession {
        val identity = identity(user)
        return StoredSession(namespace(user), "synthetic-$user", identity, identity.deviceId,
            "session-$user", identity.sessionExpiresAt)
    }
}

private class EventSecrets(var record: StoredSession?) : SessionSecretStore {
    override suspend fun load(instance: String, origin: String): StoredSession? = record
    override suspend fun save(session: StoredSession) { record = session }
    override suspend fun delete(instance: String, origin: String) { record = null }
}

private class MemoryEventCursors : EventCursorStore {
    val values = mutableMapOf<AccountNamespace, String>()
    var failRead = false
    var failWrite = false
    override suspend fun read(namespace: AccountNamespace): String? {
        if (failRead) throw EventCursorUnavailable()
        return values[namespace]
    }
    override suspend fun write(namespace: AccountNamespace, value: String) {
        if (failWrite) throw EventCursorUnavailable()
        values[namespace] = value
    }
}

private class RecordingEventEffects(private val order: MutableList<String>) : AccountEventEffects {
    var beforeInvalidation: suspend () -> Unit = {}
    val invalidations = mutableListOf<EventInvalidation>()
    override suspend fun refetchAll(lease: SessionLease) { order.add("refetch:${lease.namespace.userId}") }
    override suspend fun recover(lease: SessionLease) { order.add("recover:${lease.namespace.userId}") }
    override suspend fun invalidate(lease: SessionLease, invalidation: EventInvalidation) {
        order.add("invalidate:${lease.namespace.userId}")
        beforeInvalidation()
        invalidations.add(invalidation)
    }
}
