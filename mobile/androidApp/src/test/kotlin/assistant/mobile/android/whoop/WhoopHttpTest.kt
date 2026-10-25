package assistant.mobile.android.whoop

import assistant.mobile.core.AccountNamespace
import assistant.mobile.core.OriginMoveConfigResult
import assistant.mobile.core.OriginMoveCoordinator
import assistant.mobile.core.OriginMoveMeResult
import assistant.mobile.core.OriginMoveProbe
import assistant.mobile.core.OriginMoveRecord
import assistant.mobile.core.OriginMoveRecordStore
import assistant.mobile.core.OriginMoveSessions
import assistant.mobile.core.OriginMoveState
import assistant.mobile.core.SessionLease
import assistant.mobile.core.StoredSession
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.util.Collections
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class WhoopHttpTest {
    @Test fun revokeDrainUsesEveryQueuesOwnOriginAndContinuesAfterAnotherOriginsFailure() = runBlocking {
        val requests = Collections.synchronizedList(mutableListOf<Pair<String, String?>>())
        var failA = true
        fun server(label: String): HttpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/v1/personal/rings/collector/revoke") { exchange ->
                requests += label to exchange.requestHeaders.getFirst("Authorization")
                exchange.requestBody.use { it.readBytes() }
                val bytes = "{}".toByteArray()
                exchange.sendResponseHeaders(if (label == "A" && failA) 503 else 200, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
            }
            start()
        }
        val serverA = server("A"); val serverB = server("B")
        try {
            val originA = "http://127.0.0.1:${serverA.address.port}"
            val originB = "http://127.0.0.1:${serverB.address.port}"
            fun queue(label: String): WhoopRevokeQueue = object : WhoopRevokeQueue {
                private val grants = mutableListOf(WhoopGrant(obj {
                    put("user_id", label); put("source_id", "source-$label"); put("collector_id", "collector-$label")
                    put("token", "synthetic-old-origin-$label")
                }))
                override fun all() = grants.toList()
                override fun add(grant: WhoopGrant) { grants += grant }
                override fun remove(grant: WhoopGrant) { grants.remove(grant) }
            }
            val queues = mapOf(originA to queue("A"), originB to queue("B"))
            suspend fun drain() = drainWhoopRevocations(queues.keys, { queues.getValue(it) }, { it })

            assertFalse(drain())
            assertEquals(listOf("A" to "Bearer synthetic-old-origin-A", "B" to "Bearer synthetic-old-origin-B"), requests.toList())
            assertEquals(1, queues.getValue(originA).all().size); assertTrue(queues.getValue(originB).all().isEmpty())
            failA = false
            assertTrue(drain())
            assertEquals(3, requests.size); assertEquals("A" to "Bearer synthetic-old-origin-A", requests.last())
            assertTrue(queues.values.all { it.all().isEmpty() })
        } finally { serverA.stop(0); serverB.stop(0) }
    }

    @Test fun movedRecordRoutesHomeEnrollmentAndCollectorRequestsWithoutChangingTheirAuthority() = runBlocking {
        for (moved in listOf(false, true)) {
            val oldRequests = Collections.synchronizedList(mutableListOf<String>())
            val newRequests = Collections.synchronizedList(mutableListOf<String>())
            val grant = WhoopGrant(obj {
                put("user_id", "synthetic-user"); put("source_id", "synthetic-source"); put("collector_id", "synthetic-collector")
                put("token", "synthetic-narrow-token"); put("generation", 3)
            })
            fun handle(exchange: HttpExchange, requests: MutableList<String>) {
                val path = exchange.requestURI.path
                requests += path
                val enroll = path.endsWith("/enroll")
                assertEquals(if (enroll) "Bearer synthetic-person-token" else "Bearer synthetic-narrow-token",
                    exchange.requestHeaders.getFirst("Authorization"))
                assertEquals(if (enroll) "synthetic-user" else null, exchange.requestHeaders.getFirst("X-Expected-User"))
                assertNull(exchange.requestHeaders.getFirst("Cookie"))
                exchange.requestBody.use { it.readBytes() }
                val body = if (enroll) grant.value else obj {
                    put("source_id", grant.source); put("collector_id", grant.identity); put("generation", 3)
                }
                val bytes = body.toString().toByteArray()
                exchange.sendResponseHeaders(200, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
            }
            val oldServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
            val newServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
            oldServer.createContext("/") { handle(it, oldRequests) }
            newServer.createContext("/") { handle(it, newRequests) }
            oldServer.start(); newServer.start()
            try {
                val home = "http://127.0.0.1:${oldServer.address.port}"
                val current = "http://127.0.0.1:${newServer.address.port}"
                val origins = origins(home, current, moved)
                assertEquals(home, origins.homeOrigin)
                val http = NativeWhoopHttp(home) { origins.serverOrigin(home) }
                val lease = SessionLease(AccountNamespace("native", home, "synthetic-user"), 1, "synthetic-person-token")
                val enrolled = http.enroll(lease, "native-binding", "WHOOP synthetic", {}, {})
                assertEquals(grant, enrolled)
                http.state(enrolled)
                http.receipt(enrolled, "synthetic-key")
                http.send(enrolled, obj { put("key", "synthetic-key"); put("body", obj { put("cursor", "synthetic-cursor") }) })
                http.revoke(enrolled)
                val expected = listOf("/v1/personal/rings/enroll", "/v1/personal/rings/collector/state",
                    "/v1/personal/rings/collector/receipts/synthetic-key", "/v1/personal/rings/collector/batches",
                    "/v1/personal/rings/collector/revoke")
                assertEquals(expected, if (moved) newRequests.toList() else oldRequests.toList())
                assertTrue((if (moved) oldRequests else newRequests).isEmpty())
                assertEquals(home, lease.namespace.origin)
            } finally { oldServer.stop(0); newServer.stop(0) }
        }
    }

    @Test fun onlyEnrollmentUsesTheNativePersonSessionAndIngestUsesCollectorAuthority() = runBlocking {
        data class Request(val path: String, val authorization: String?, val expectedUser: String?, val key: String?, val cookie: String?, val body: JsonObject?)
        val requests = Collections.synchronizedList(mutableListOf<Request>())
        val grant = WhoopGrant(obj {
            put("user_id", "synthetic-user"); put("source_id", "synthetic-source"); put("collector_id", "synthetic-collector")
            put("token", "synthetic-narrow-token"); put("generation", 3)
        })
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/v1/personal/rings/") { exchange ->
            val raw = exchange.requestBody.readBytes().toString(Charsets.UTF_8)
            requests += Request(exchange.requestURI.path, exchange.requestHeaders.getFirst("Authorization"),
                exchange.requestHeaders.getFirst("X-Expected-User"), exchange.requestHeaders.getFirst("Idempotency-Key"),
                exchange.requestHeaders.getFirst("Cookie"), raw.takeIf { it.isNotEmpty() }?.let { whoopJson.parseToJsonElement(it).jsonObject })
            val value = when {
                exchange.requestURI.path.endsWith("/enroll") -> grant.value
                exchange.requestURI.path.endsWith("/state") -> obj {
                    put("source_id", grant.source); put("collector_id", grant.identity); put("generation", 3); put("profile", "whoop-5")
                }
                else -> obj { put("source_id", grant.source); put("cursor", "synthetic-cursor") }
            }
            val bytes = value.toString().toByteArray(Charsets.UTF_8)
            exchange.responseHeaders.set("Content-Type", "application/json")
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        try {
            val origin = "http://127.0.0.1:${server.address.port}"
            val http = NativeWhoopHttp(origin)
            val lease = SessionLease(AccountNamespace("native", origin, "synthetic-user"), 1, "synthetic-person-token")
            var issued: WhoopGrant? = null
            val enrolled = http.enroll(lease, "native-binding", "WHOOP synthetic", {}, { issued = it })
            assertEquals(grant, enrolled); assertEquals(grant, issued)
            http.state(enrolled)
            http.receipt(enrolled, "synthetic-key")
            http.send(enrolled, obj { put("key", "synthetic-key"); put("body", obj { put("cursor", "synthetic-cursor") }) })
            http.revoke(enrolled)
            assertEquals(5, requests.size)
            val enrollment = requests.first()
            assertEquals("Bearer synthetic-person-token", enrollment.authorization)
            assertEquals("synthetic-user", enrollment.expectedUser)
            assertEquals("whoop-5", enrollment.body?.string("profile"))
            assertEquals("native-binding", enrollment.body?.string("installation_id"))
            assertTrue(requests.drop(1).all { it.authorization == "Bearer synthetic-narrow-token" && it.expectedUser == null })
            assertTrue(requests.all { it.cookie == null })
            assertEquals("synthetic-key", requests.single { it.path.endsWith("/batches") }.key)
        } finally { server.stop(0) }
    }

    @Test fun redirectsAreRejectedBeforeAnyCredentialCanReachAnotherRoute() = runBlocking {
        var redirected = 0
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/v1/personal/rings/enroll") { exchange ->
            exchange.responseHeaders.set("Location", "/unexpected")
            exchange.sendResponseHeaders(302, -1); exchange.close()
        }
        server.createContext("/unexpected") { exchange ->
            redirected++; exchange.sendResponseHeaders(200, -1); exchange.close()
        }
        server.start()
        try {
            val origin = "http://127.0.0.1:${server.address.port}"
            val http = NativeWhoopHttp(origin)
            try {
                http.enroll(SessionLease(AccountNamespace("native", origin, "synthetic-user"), 1, "synthetic-person-token"),
                    "native-binding", "WHOOP synthetic", {}, {})
                fail("A collector may never follow a redirect")
            } catch (failure: WhoopFailure) { assertEquals("redirect_rejected", failure.code) }
            assertEquals(0, redirected)
        } finally { server.stop(0) }
    }

    private fun origins(home: String, current: String, moved: Boolean): OriginMoveCoordinator = OriginMoveCoordinator(
        current, home,
        object : OriginMoveSessions {
            override fun exists(instance: String, origin: String) = origin == home
            override suspend fun load(instance: String, origin: String): StoredSession? = error("No session decryption expected")
        },
        object : OriginMoveRecordStore {
            override fun read(): OriginMoveRecord? = if (moved) OriginMoveRecord(home, current,
                "11111111-1111-4111-8111-111111111111", OriginMoveState.MOVED, "2026-10-21T12:00:00Z") else null
            override fun write(record: OriginMoveRecord) = error("No move verification expected")
        },
        object : OriginMoveProbe {
            override suspend fun config(origin: String): OriginMoveConfigResult = error("No move verification expected")
            override suspend fun me(origin: String, session: StoredSession): OriginMoveMeResult = error("No move verification expected")
        },
        allowInsecureLoopback = true,
    )
}
