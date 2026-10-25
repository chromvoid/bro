package assistant.mobile.core.calls

import assistant.mobile.core.*
import io.ktor.client.engine.mock.*
import io.ktor.client.request.HttpRequestData
import io.ktor.http.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.*
import kotlin.test.*

private const val SDP = "v=0\r\nm=audio 9 UDP/TLS/RTP/SAVPF 111\r\na=candidate:1 1 udp 2122260223 192.168.50.2 5000 typ host\r\na=end-of-candidates\r\n"

@OptIn(ExperimentalCoroutinesApi::class)
class CallWireTest {
    @Test
    fun offerAndAnswerKeepCompleteSdpIncludingCandidatesInTheExistingFrames() {
        val offer = encodeCallClientFrame(CallClientFrame.Offer(SDP))
        assertEquals(setOf("type", "sdp"), NativeJson.parseToJsonElement(offer).jsonObject.keys)
        assertEquals(CallServerFrame.Offer(SDP), decodeCallServerFrame(offer))
        val answer = encodeCallClientFrame(CallClientFrame.Answer(SDP))
        assertEquals(CallServerFrame.Answer(SDP), decodeCallServerFrame(answer))
        assertEquals("{\"type\":\"ping\"}", encodeCallClientFrame(CallClientFrame.Ping))
        assertEquals(CallServerFrame.Pong, decodeCallServerFrame("""{"type":"pong"}"""))
        assertEquals(CallServerFrame.State(CallState.ENDED, CallOutcome.MISSED),
            decodeCallServerFrame("""{"type":"state","state":"ended","outcome":"missed"}"""))
        assertEquals(CallServerFrame.Peer(true), decodeCallServerFrame("""{"type":"peer","present":true}"""))
    }

    @Test
    fun invalidFramesAndTrickleCandidatesCannotChangeCallState() {
        listOf("not json", "[]", """{"type":"candidate","candidate":"private"}""",
            """{"type":"peer","present":"true"}""", """{"type":"state","state":"future","outcome":null}""",
            """{"type":"offer","sdp":"not SDP"}""", """{"type":"state","state":"ended"}""")
            .forEach { assertNull(decodeCallServerFrame(it)) }
        assertFailsWith<IllegalArgumentException> { encodeCallClientFrame(CallClientFrame.Offer("v=0\u0000")) }
        assertFailsWith<IllegalArgumentException> { encodeCallClientFrame(CallClientFrame.Offer("v=0" + "a".repeat(32_768))) }
    }

    @Test
    fun iceConfigurationUsesOnlyServerPolicyAndTemporaryCredentials() {
        val direct = decodeCallIce("""{"ice_servers":[]}""".encodeToByteArray())
        assertEquals(emptyList(), direct.servers)
        assertEquals(CallIceTransportPolicy.ALL, direct.transportPolicy)
        val relay = decodeCallIce("""{"ice_servers":[{"urls":["turn:relay.invalid:3478","turns:relay.invalid:5349"],"username":"expires:synthetic","credential":"synthetic-secret"}],"ice_transport_policy":"relay","expires_at":"2026-10-17T00:00:00Z"}""".encodeToByteArray())
        assertEquals(CallIceTransportPolicy.RELAY, relay.transportPolicy)
        assertEquals(listOf("turn:relay.invalid:3478", "turns:relay.invalid:5349"), relay.servers.single().urls)
        assertEquals("synthetic-secret", relay.servers.single().credential)
        assertFalse(relay.toString().contains("synthetic-secret"))
        assertFalse(relay.servers.single().toString().contains("expires:synthetic"))
        assertEquals(listOf("stun:stun.invalid"), decodeCallIce("""{"ice_servers":[{"urls":"stun:stun.invalid"}]}""".encodeToByteArray()).servers.single().urls)
    }

    @Test
    fun apiUsesNativeLeaseHeadersKeyAudioAndAllExistingRoutes() = runTest {
        val identity = NativeIdentity("caller", "caller", "Synthetic", "ru", "UTC", false, 1, "device", "2030-01-01T00:00:00Z", listOf("calls.read", "calls.write"))
        val stored = StoredSession(AccountNamespace("synthetic", "https://calls.invalid", identity.id), "synthetic-token", identity, "device", "native-session", identity.sessionExpiresAt)
        val sessions = SessionManager("synthetic", stored.namespace.origin, CallTestSecrets(stored), {}, { 1_000L })
        val requests = mutableListOf<HttpRequestData>()
        val http = NativeHttpClient(MockEngine(MockEngineConfig().apply {
            dispatcher = StandardTestDispatcher(testScheduler)
            addHandler { request ->
                requests += request
                when (request.url.encodedPath) {
                    "/v1/calls/current" -> respond("{\"call\":null}")
                    "/v1/calls/call/ice" -> respond("{\"ice_servers\":[],\"ice_transport_policy\":\"all\"}")
                    else -> respond(CALL_REPLY)
                }
            }
        }), stored.namespace.origin, sessions, { 1_000L })
        try {
            sessions.restore()
            val lease = assertNotNull(sessions.lease())
            val api = NativeCallApi(http)
            assertNull(api.current(lease))
            val call = api.start("dm", "original-key", lease)
            api.accept(call.id, lease)
            api.ice(call.id, lease)
            api.end(call.id, CallEndBody(CallEndReason.HANGUP, CallSummary(ice = "connected")), lease)
            assertEquals(listOf("/v1/calls/current", "/v1/calls", "/v1/calls/call/accept", "/v1/calls/call/ice", "/v1/calls/call/end"), requests.map { it.url.encodedPath })
            requests.forEach {
                assertEquals("Bearer synthetic-token", it.headers["Authorization"])
                assertEquals("caller", it.headers["X-Expected-User"])
            }
            assertEquals("original-key", requests[1].headers["Idempotency-Key"])
            val start = NativeJson.parseToJsonElement((requests[1].body as io.ktor.http.content.OutgoingContent.ByteArrayContent).bytes().decodeToString()).jsonObject
            assertEquals(JsonPrimitive("audio"), start["kind"])
            assertEquals(JsonPrimitive("dm"), start["channel_id"])
            val end = NativeJson.parseToJsonElement((requests.last().body as io.ktor.http.content.OutgoingContent.ByteArrayContent).bytes().decodeToString()).jsonObject
            assertEquals(JsonPrimitive("hangup"), end["reason"])
            assertEquals(setOf("client", "ice"), end.getValue("summary").jsonObject.keys)
        } finally { http.close() }
    }

}

internal const val CALL_REPLY = """{"call":{"id":"call","channel_id":"dm","kind":"audio","state":"ringing","outcome":null,"role":"caller","peer":{"id":"callee","name":"Synthetic peer"},"created_at":"2026-10-17T00:00:00Z","answered_at":null,"ended_at":null}}"""
internal class CallTestSecrets(var stored: StoredSession?) : SessionSecretStore {
    override suspend fun load(instance: String, origin: String) = stored
    override suspend fun save(session: StoredSession) { stored = session }
    override suspend fun delete(instance: String, origin: String) { stored = null }
}
