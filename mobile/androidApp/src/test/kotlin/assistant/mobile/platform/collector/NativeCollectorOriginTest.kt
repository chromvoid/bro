package assistant.mobile.platform.collector

import assistant.mobile.core.AccountNamespace
import assistant.mobile.core.NativeIdentity
import assistant.mobile.core.OriginMoveConfigResult
import assistant.mobile.core.OriginMoveCoordinator
import assistant.mobile.core.OriginMoveMeResult
import assistant.mobile.core.OriginMoveProbe
import assistant.mobile.core.OriginMoveRecord
import assistant.mobile.core.OriginMoveRecordStore
import assistant.mobile.core.OriginMoveSessions
import assistant.mobile.core.StoredSession
import assistant.mobile.platform.location.NativeLocationCollector
import assistant.mobile.platform.location.NarrowCollectorTransport
import assistant.mobile.platform.ring.RingGrant
import assistant.mobile.platform.ring.RingHttp
import assistant.mobile.platform.ring.RingPolicy
import com.sun.net.httpserver.HttpServer
import java.io.Closeable
import java.net.InetSocketAddress
import java.util.Collections
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeCollectorOriginTest {
    @Test fun moveChangesExistingRingAndLocationClientsWithoutChangingTheirHomeIdentity() = runBlocking {
        CollectorServer().use { previous -> CollectorServer().use { current ->
            val home = previous.origin
            val namespace = AccountNamespace("native", home, USER)
            val stored = StoredSession(namespace, "synthetic-person-token",
                NativeIdentity(USER, "synthetic", "Synthetic person", "ru", "Europe/Moscow", false, 1,
                    DEVICE, "2027-10-16T12:00:00Z"), DEVICE, "synthetic-session", "2027-10-16T12:00:00Z")
            val records = object : OriginMoveRecordStore {
                var record: OriginMoveRecord? = null
                override fun read() = record
                override fun write(record: OriginMoveRecord) { this.record = record }
            }
            val sessions = object : OriginMoveSessions {
                override fun exists(instance: String, origin: String) = instance == "native" && origin == home
                override suspend fun load(instance: String, origin: String) = stored.takeIf { exists(instance, origin) }
            }
            val probe = object : OriginMoveProbe {
                override suspend fun config(origin: String) = OriginMoveConfigResult.Available(INSTANCE_ID)
                override suspend fun me(origin: String, session: StoredSession): OriginMoveMeResult {
                    assertEquals(current.origin, origin)
                    assertEquals(stored, session)
                    return OriginMoveMeResult.Identity(USER, DEVICE)
                }
            }
            val origins = OriginMoveCoordinator(current.origin, home, sessions, records, probe,
                allowInsecureLoopback = true)
            val serverOrigin = { origins.serverOrigin(home) }
            val ring = RingHttp(home, serverOrigin = serverOrigin)
            val location = NarrowCollectorTransport(home, serverOrigin)
            val bindingBefore = RingPolicy.bindingId("synthetic-installation", home, USER, "AA:BB:CC:DD:EE:FF")

            ring.state(RingGrant(grant()))
            assertEquals(200, location.post(NativeLocationCollector.REVOKE, buildJsonObject {}, COLLECTOR_TOKEN).status)
            assertEquals(2, previous.requests.size)
            assertTrue(current.requests.isEmpty())

            origins.checkPending()
            assertEquals(home, origins.homeOrigin)
            assertEquals(current.origin, origins.serverOrigin(home))
            val enrolled = ring.enroll("synthetic-person-token", USER, "synthetic-installation", "Synthetic ring")
            ring.state(enrolled)
            ring.send(enrolled, buildJsonObject {
                put("key", "synthetic-batch"); put("body", buildJsonObject { put("source_id", SOURCE) })
            })
            ring.receipt(enrolled, "synthetic-batch")
            ring.revoke(enrolled)
            assertEquals(200, location.post(NativeLocationCollector.POINTS, buildJsonObject {}, COLLECTOR_TOKEN).status)
            assertEquals(200, location.post(NativeLocationCollector.REVOKE, buildJsonObject {}, COLLECTOR_TOKEN).status)

            assertEquals(2, previous.requests.size)
            assertEquals(listOf("/v1/me", "/v1/personal/rings/enroll", "/v1/personal/rings/collector/state",
                "/v1/personal/rings/collector/batches", "/v1/personal/rings/collector/receipts/synthetic-batch",
                "/v1/personal/rings/collector/revoke", NativeLocationCollector.POINTS, NativeLocationCollector.REVOKE),
                current.requests.map { it.path })
            assertTrue(current.requests.take(2).all { it.authorization == "Bearer synthetic-person-token" })
            assertTrue(current.requests.drop(2).all { it.authorization == "Bearer $COLLECTOR_TOKEN" })
            assertEquals(namespace, stored.namespace)
            assertEquals(home, records.record?.home)
            assertEquals(bindingBefore, RingPolicy.bindingId("synthetic-installation", origins.homeOrigin,
                USER, "AA:BB:CC:DD:EE:FF"))
        } }
    }

    @Test fun transportsWithoutMoveKeepTheirOriginalDestinationAndCollectorAuthority() = runBlocking {
        CollectorServer().use { server ->
            RingHttp(server.origin).state(RingGrant(grant()))
            NativeCollectorHttp(server.origin, "whoop-5").state(NativeCollectorGrant(grant()))
            NarrowCollectorTransport(server.origin).post(NativeLocationCollector.POINTS, buildJsonObject {}, COLLECTOR_TOKEN)
            assertEquals(listOf("/v1/personal/rings/collector/state", "/v1/personal/rings/collector/state",
                NativeLocationCollector.POINTS), server.requests.map { it.path })
            assertTrue(server.requests.all { it.authorization == "Bearer $COLLECTOR_TOKEN" })
        }
    }
}

private class CollectorServer : Closeable {
    data class Request(val path: String, val authorization: String?)
    val requests = Collections.synchronizedList(mutableListOf<Request>())
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        createContext("/") { exchange ->
            requests += Request(exchange.requestURI.path, exchange.requestHeaders.getFirst("Authorization"))
            exchange.requestBody.use { it.readBytes() }
            val value = when (exchange.requestURI.path) {
                "/v1/me" -> buildJsonObject { put("id", USER) }
                "/v1/personal/rings/enroll" -> grant()
                "/v1/personal/rings/collector/state" -> buildJsonObject {
                    put("source_id", SOURCE); put("collector_id", "synthetic-collector"); put("generation", 1)
                }
                else -> buildJsonObject {}
            }
            val bytes = value.toString().toByteArray(Charsets.UTF_8)
            exchange.responseHeaders.set("Content-Type", "application/json")
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        start()
    }
    val origin = "http://127.0.0.1:${server.address.port}"
    override fun close() { server.stop(0) }
}

private fun grant(): JsonObject = buildJsonObject {
    put("user_id", USER); put("source_id", SOURCE); put("collector_id", "synthetic-collector")
    put("token", COLLECTOR_TOKEN); put("generation", 1)
}
private const val USER = "33333333-3333-4333-8333-333333333333"
private const val DEVICE = "44444444-4444-4444-8444-444444444444"
private const val INSTANCE_ID = "11111111-1111-4111-8111-111111111111"
private const val SOURCE = "synthetic-source"
private const val COLLECTOR_TOKEN = "synthetic-collector-token"
