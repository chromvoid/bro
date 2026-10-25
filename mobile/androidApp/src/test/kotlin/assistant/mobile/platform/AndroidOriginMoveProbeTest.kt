package assistant.mobile.platform

import assistant.mobile.core.AccountNamespace
import assistant.mobile.core.NativeIdentity
import assistant.mobile.core.NativeJson
import assistant.mobile.core.OriginMoveConfigResult
import assistant.mobile.core.OriginMoveMeResult
import assistant.mobile.core.StoredSession
import com.sun.net.httpserver.HttpServer
import java.io.Closeable
import java.net.InetSocketAddress
import java.util.Collections
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class AndroidOriginMoveProbeTest {
    @Test fun configIsAnonymousAndOnlyTheCurrentOriginReceivesTheStoredNativeHeaders() = runBlocking {
        ProbeServer().use { previous -> ProbeServer().use { current ->
            val stored = storedSession(previous.origin)
            val before = NativeJson.encodeToString(StoredSession.serializer(), stored)
            val probe = AndroidOriginMoveProbe(current.origin, allowInsecureLoopback = true)
            current.body = """{"instance_id":"${INSTANCE.uppercase()}"}"""
            previous.body = """{"instance_id":"$INSTANCE"}"""
            assertEquals(OriginMoveConfigResult.Available(INSTANCE), probe.config(current.origin))
            assertEquals(OriginMoveConfigResult.Available(INSTANCE), probe.config(previous.origin))
            assertTrue((current.requests + previous.requests).all { it.authorization == null && it.expectedUser == null })

            current.body = NativeJson.encodeToString(NativeIdentity.serializer(), stored.identity)
            assertEquals(OriginMoveMeResult.Identity(USER, DEVICE), probe.me(current.origin, stored))
            assertEquals(listOf("/v1/client/config", "/v1/me"), current.requests.map { it.path })
            val me = current.requests.last()
            assertEquals("GET", me.method)
            assertEquals("Bearer $CREDENTIAL", me.authorization)
            assertEquals(USER, me.expectedUser)
            assertNull(me.cookie)
            assertNull(me.query)
            assertEquals(listOf("/v1/client/config"), previous.requests.map { it.path })
            assertEquals(before, NativeJson.encodeToString(StoredSession.serializer(), stored))
        } }
    }

    @Test fun deniedMeRejectsTheMoveWithoutInterpretingTheResponseBody() = runBlocking {
        ProbeServer().use { current ->
            val probe = AndroidOriginMoveProbe(current.origin, allowInsecureLoopback = true)
            for (status in listOf(401, 403)) {
                current.status = status
                current.body = "Authorization denied"
                assertEquals(OriginMoveMeResult.Rejected, probe.me(current.origin, storedSession()))
            }
            assertEquals(2, current.requests.size)
        }
    }

    @Test fun serverErrorsAndRedirectsStayUnavailableAndNeverFollowLocation() = runBlocking {
        ProbeServer().use { current -> ProbeServer().use { redirect ->
            val probe = AndroidOriginMoveProbe(current.origin, allowInsecureLoopback = true)
            current.location = "${redirect.origin}/unexpected"
            for (status in listOf(500, 302)) {
                current.status = status
                current.body = "Unavailable"
                assertEquals(OriginMoveConfigResult.Unavailable, probe.config(current.origin))
                assertEquals(OriginMoveMeResult.Unavailable, probe.me(current.origin, storedSession()))
            }
            assertEquals(4, current.requests.size)
            assertTrue(redirect.requests.isEmpty())
        } }
    }

    @Test fun aForeignOriginIsRejectedBeforeAnyRequestCanSendTheCredential() = runBlocking {
        ProbeServer().use { current -> ProbeServer().use { foreign ->
            val probe = AndroidOriginMoveProbe(current.origin, allowInsecureLoopback = true)
            for (origin in listOf(foreign.origin, "${current.origin}/other", "https://user-entered.invalid")) {
                try {
                    probe.me(origin, storedSession())
                    fail("A credential may only be verified at the current build origin")
                } catch (_: IllegalArgumentException) { }
            }
            assertTrue(current.requests.isEmpty())
            assertTrue(foreign.requests.isEmpty())
        } }
    }
}

private class ProbeServer : Closeable {
    data class Request(val method: String, val path: String, val query: String?, val authorization: String?,
        val expectedUser: String?, val cookie: String?)
    val requests = Collections.synchronizedList(mutableListOf<Request>())
    @Volatile var status = 200
    @Volatile var body = "{}"
    @Volatile var location: String? = null
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        createContext("/") { exchange ->
            requests += Request(exchange.requestMethod, exchange.requestURI.path, exchange.requestURI.rawQuery,
                exchange.requestHeaders.getFirst("Authorization"), exchange.requestHeaders.getFirst("X-Expected-User"),
                exchange.requestHeaders.getFirst("Cookie"))
            exchange.requestBody.use { it.readBytes() }
            location?.let { exchange.responseHeaders.set("Location", it) }
            exchange.responseHeaders.set("Content-Type", "application/json")
            val bytes = body.toByteArray(Charsets.UTF_8)
            exchange.sendResponseHeaders(status, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        start()
    }
    val origin = "http://127.0.0.1:${server.address.port}"
    override fun close() { server.stop(0) }
}

private fun storedSession(home: String = "https://previous.synthetic.invalid") = StoredSession(
    AccountNamespace("native", home, USER), CREDENTIAL,
    NativeIdentity(USER, "synthetic", "Synthetic person", "ru", "Europe/Moscow", false, 1,
        DEVICE, "2099-01-01T00:00:00Z"), DEVICE, "synthetic-session", "2099-01-01T00:00:00Z",
)
private const val INSTANCE = "abcdefab-1111-4111-8111-111111111111"
private const val USER = "33333333-3333-4333-8333-333333333333"
private const val DEVICE = "44444444-4444-4444-8444-444444444444"
private const val CREDENTIAL = "synthetic-native-credential"
