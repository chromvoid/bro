package assistant.mobile.core.assistant

import assistant.mobile.core.AccountNamespace
import assistant.mobile.core.NativeHttpClient
import assistant.mobile.core.NativeIdentity
import assistant.mobile.core.NativeJson
import assistant.mobile.core.SessionManager
import assistant.mobile.core.SessionSecretStore
import assistant.mobile.core.StoredSession
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockEngineConfig
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteChannel
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.writeStringUtf8
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class AssistantActivityTest {
    @Test
    fun reconnectUsesOnlyGetAndTerminalRereadsDurableInsteadOfPersistingPreview() = runTest {
        val fixture = ActivityFixture(this, listOf(
            ActivityReply.Body(frame(snapshot(seq = 2))),
            ActivityReply.Body(frame(snapshot(seq = 1, text = "Старый")) +
                frame(snapshot(seq = 2, text = "Полный")) +
                frame(snapshot(seq = 3).copy(status = AssistantActivityStatus.Completed, previewText = ""))),
        ))
        fixture.restore()
        fixture.collect()
        assertEquals(2, fixture.requests.size)
        assertTrue(fixture.requests.all { it.method.value == "GET" })
        assertTrue(fixture.requests.all { it.url.encodedPath == "/v1/conversation/messages/message/activity" })
        assertTrue(fixture.requests.all { it.headers["Last-Event-ID"] == null })
        assertEquals(1, fixture.durableReads)
        assertEquals("Полный", fixture.states.last().preview)
        assertEquals(AssistantActivityConnection.Ended, fixture.states.last().connection)
        fixture.close()
    }

    @Test
    fun denialNeverReconnectsAndDiscardsReceipts() = runTest {
        for (status in listOf(HttpStatusCode.Forbidden, HttpStatusCode.NotFound, HttpStatusCode.Gone)) {
            val fixture = ActivityFixture(this, listOf(ActivityReply.Status(status)))
            fixture.restore()
            fixture.collect()
            assertEquals(1, fixture.requests.size)
            assertEquals(AssistantActivityConnection.Denied, fixture.states.last().connection)
            assertEquals(emptyList(), fixture.states.last().confirmed)
            assertEquals(0, fixture.durableReads)
            fixture.close()
        }
    }

    @Test
    fun cancellationAndReplacedLeaseRejectLateSnapshots() = runTest {
        val channel = ByteChannel(autoFlush = true)
        val fixture = ActivityFixture(this, listOf(ActivityReply.Channel(channel)))
        fixture.restore()
        val job = launch { fixture.collect() }
        runCurrent()
        fixture.sessions.expire(requireNotNull(fixture.sessions.lease()))
        channel.writeStringUtf8(frame(snapshot().copy(status = AssistantActivityStatus.Completed)))
        runCurrent()
        job.join()
        assertEquals(0, fixture.durableReads)
        assertTrue(fixture.states.none { it.snapshot != null })
        fixture.close()
    }
}

private fun snapshot(run: String = "run", seq: Long = 1, text: String = "Первый") = AssistantActivitySnapshot(
    messageId = "message", turnId = "turn", runId = run, seq = seq,
    status = AssistantActivityStatus.Active, phase = AssistantActivityPhase.Generating,
    previewText = text, source = AssistantActivitySource.Assistant,
    updatedAt = "2026-10-16T10:00:00Z", elapsedMs = 1_000.0, modelCall = 1, attempt = 1,
    steps = emptyList(),
)

private fun frame(value: AssistantActivitySnapshot) = "event: activity\ndata: ${NativeJson.encodeToString(value)}\n\n"

private sealed interface ActivityReply {
    data class Body(val value: String) : ActivityReply
    data class Channel(val value: ByteChannel) : ActivityReply
    data class Status(val value: HttpStatusCode) : ActivityReply
}

@OptIn(ExperimentalCoroutinesApi::class)
private class ActivityFixture(scope: TestScope, replies: List<ActivityReply>) {
    private val origin = "https://synthetic.example"
    private val identity = NativeIdentity("person", "person", "Synthetic", "ru", "Europe/Moscow", false,
        0, "device", "2099-01-01T00:00:00Z", listOf("session.control", "conversation.read"))
    val sessions = SessionManager("synthetic", origin, object : SessionSecretStore {
        private var record: StoredSession? = StoredSession(AccountNamespace("synthetic", origin, "person"),
            "synthetic-credential", identity, "device", "session", identity.sessionExpiresAt)
        override suspend fun load(instance: String, origin: String) = record
        override suspend fun save(session: StoredSession) { record = session }
        override suspend fun delete(instance: String, origin: String) { record = null }
    }, {})
    val requests = mutableListOf<HttpRequestData>()
    val states = mutableListOf<AssistantActivityState>()
    var durableReads = 0
    private val pending = replies.toMutableList()
    private val engine = MockEngine(MockEngineConfig().apply {
        dispatcher = kotlinx.coroutines.test.StandardTestDispatcher(scope.testScheduler)
        addHandler { request ->
            if (request.url.encodedPath == "/v1/me") {
                respond(NativeJson.encodeToString(identity), headers = headersOf("Content-Type", "application/json"))
            } else {
                requests += request
                val reply = if (pending.isEmpty()) ActivityReply.Status(HttpStatusCode.ServiceUnavailable)
                    else pending.removeAt(0)
                when (reply) {
                    is ActivityReply.Body -> respond(reply.value, headers = headersOf("Content-Type", "text/event-stream"))
                    is ActivityReply.Channel -> respond(reply.value as ByteReadChannel, headers = headersOf("Content-Type", "text/event-stream"))
                    is ActivityReply.Status -> respond("{}", reply.value)
                }
            }
        }
    })
    private val http = NativeHttpClient(engine, origin, sessions, clockMs = { scope.testScheduler.currentTime })
    private val stream = AssistantActivityStream(sessions, http,
        AssistantActivityPolicy(nowMillis = { scope.testScheduler.currentTime }))

    suspend fun restore() { sessions.restore() }
    suspend fun collect() = stream.collect(requireNotNull(sessions.lease()), "message", { states += it }) { durableReads++ }
    fun close() { http.close(); engine.close() }
}
