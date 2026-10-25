package assistant.mobile.platform.collector

import assistant.mobile.platform.location.NativeLocationCollector
import assistant.mobile.platform.location.NarrowCollectorTransport
import com.sun.net.httpserver.HttpServer
import java.io.BufferedInputStream
import java.io.Closeable
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.system.measureTimeMillis
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeCollectorHttpTest {
    @Test fun cancellationClosesAStalledRequestWithoutSendingItsCredentialToTheNewOrigin() = runBlocking {
        StalledCollector().use { previous -> ReplyCollector().use { current ->
            var origin = previous.origin
            val collector = NativeCollectorHttp(origin, "whoop-5", serverOrigin = { origin })
            val received = AtomicInteger()
            val request = async(start = CoroutineStart.UNDISPATCHED) {
                collector.request("POST", "/v1/personal/rings/collector/revoke", buildJsonObject {},
                    token = "synthetic-previous-collector", received = { received.incrementAndGet() })
            }
            assertTrue(withContext(Dispatchers.IO) { previous.requested.await(5, TimeUnit.SECONDS) })
            delay(100)
            origin = current.origin
            val elapsed = measureTimeMillis { withTimeout(2_000) { request.cancelAndJoin() } }
            assertTrue("Cancellation took $elapsed ms", elapsed < 2_000)
            assertTrue(withContext(Dispatchers.IO) { previous.disconnected.await(5, TimeUnit.SECONDS) })
            assertEquals("POST /v1/personal/rings/collector/revoke HTTP/1.1", previous.requestLine)
            assertEquals("Bearer synthetic-previous-collector", previous.authorization)
            assertEquals(0, current.requests.get())
            assertEquals(0, received.get())
        } }
    }

    @Test fun locationCancellationClosesAStalledResponseBodyOnItsOriginalOrigin() = runBlocking {
        StalledCollector().use { previous -> ReplyCollector().use { current ->
            var origin = previous.origin
            val collector = NarrowCollectorTransport(origin, serverOrigin = { origin })
            val request = async(start = CoroutineStart.UNDISPATCHED) {
                collector.post(NativeLocationCollector.REVOKE, buildJsonObject {}, "synthetic-previous-location")
            }
            assertTrue(withContext(Dispatchers.IO) { previous.requested.await(5, TimeUnit.SECONDS) })
            delay(100)
            origin = current.origin
            val elapsed = measureTimeMillis { withTimeout(2_000) { request.cancelAndJoin() } }
            assertTrue("Cancellation took $elapsed ms", elapsed < 2_000)
            assertTrue(withContext(Dispatchers.IO) { previous.disconnected.await(5, TimeUnit.SECONDS) })
            assertEquals("POST ${NativeLocationCollector.REVOKE} HTTP/1.1", previous.requestLine)
            assertEquals("Bearer synthetic-previous-location", previous.authorization)
            assertEquals(0, current.requests.get())
        } }
    }

    @Test fun redirectsAreRejectedAndDenialBodiesRetainTheirStatus() = runBlocking {
        ReplyCollector().use { previous -> ReplyCollector().use { current ->
            val collector = NativeCollectorHttp(previous.origin, "whoop-5")
            previous.status = 302
            previous.location = current.origin + "/v1/me"
            val redirect = runCatching { collector.request("GET", "/v1/me", token = "synthetic-person") }.exceptionOrNull()
            assertEquals("redirect_rejected", (redirect as NativeCollectorFailure).code)
            assertEquals(0, current.requests.get())
            previous.status = 403
            previous.body = "Synthetic denial"
            val reply = collector.request("GET", "/v1/me", token = "synthetic-person")
            assertEquals(403, reply.status)
            assertEquals(buildJsonObject {}, reply.value)
            previous.status = 200
            val invalid = runCatching { collector.request("GET", "/v1/me") }.exceptionOrNull()
            assertEquals("invalid_response", (invalid as NativeCollectorFailure).code)
            previous.body = "x".repeat(1_048_577)
            val oversized = runCatching { collector.request("GET", "/v1/me") }.exceptionOrNull()
            assertEquals("response_too_large", (oversized as NativeCollectorFailure).code)
        } }
    }

    @Test fun enrollmentCapturesTheIssuedGrantBeforeTheAccountCheckpointCancelsIt() = runBlocking {
        ReplyCollector().use { server ->
            server.body = buildJsonObject {
                put("user_id", "synthetic-user"); put("token", "synthetic-issued-collector")
            }.toString()
            var issued: NativeCollectorGrant? = null
            val failure = runCatching {
                NativeCollectorHttp(server.origin, "whoop-5").enroll("synthetic-person", "synthetic-user",
                    "synthetic-installation", "Synthetic WHOOP", checkpoint = {
                        if (issued != null) throw CancellationException("Synthetic account changed")
                    }, issued = { issued = it })
            }.exceptionOrNull()
            assertTrue(failure is CancellationException)
            assertEquals("synthetic-issued-collector", issued?.token)
        }
    }

    private class ReplyCollector : Closeable {
        val requests = AtomicInteger()
        @Volatile var status = 200
        @Volatile var body = "{}"
        @Volatile var location: String? = null
        private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/") { exchange ->
                requests.incrementAndGet()
                exchange.requestBody.use { it.readBytes() }
                location?.let { exchange.responseHeaders.set("Location", it) }
                val bytes = body.toByteArray()
                exchange.sendResponseHeaders(status, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
            }
            start()
        }
        val origin = "http://127.0.0.1:${server.address.port}"
        override fun close() { server.stop(0) }
    }

    private class StalledCollector : Closeable {
        val requested = CountDownLatch(1)
        val disconnected = CountDownLatch(1)
        @Volatile var requestLine: String? = null
        @Volatile var authorization: String? = null
        @Volatile private var socket: Socket? = null
        private val listener = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        private val executor = Executors.newSingleThreadExecutor()
        val origin = "http://127.0.0.1:${listener.localPort}"

        init {
            executor.execute {
                try {
                    listener.accept().use { connection ->
                        socket = connection
                        connection.soTimeout = 10_000
                        val input = BufferedInputStream(connection.getInputStream())
                        requestLine = line(input)
                        var length = 0
                        while (true) {
                            val header = line(input)
                            if (header.isEmpty()) break
                            when (header.substringBefore(':').lowercase()) {
                                "authorization" -> authorization = header.substringAfter(':').trim()
                                "content-length" -> length = header.substringAfter(':').trim().toInt()
                            }
                        }
                        repeat(length) { check(input.read() >= 0) }
                        connection.getOutputStream().apply {
                            write("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: 100\r\nConnection: close\r\n\r\n{".toByteArray())
                            flush()
                        }
                        requested.countDown()
                        if (input.read() < 0) disconnected.countDown()
                    }
                } catch (_: IOException) { disconnected.countDown() }
            }
        }

        override fun close() { socket?.close(); listener.close(); executor.shutdownNow() }

        private fun line(input: BufferedInputStream): String {
            val result = StringBuilder()
            while (result.length < 8_192) {
                val value = input.read()
                check(value >= 0)
                if (value == '\n'.code) return result.toString().removeSuffix("\r")
                result.append(value.toChar())
            }
            error("Synthetic HTTP header is too large")
        }
    }
}
