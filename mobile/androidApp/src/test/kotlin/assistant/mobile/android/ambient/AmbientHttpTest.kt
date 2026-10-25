package assistant.mobile.android.ambient

import assistant.mobile.core.AccountNamespace
import assistant.mobile.core.NativeHttpClient
import assistant.mobile.core.NativeIdentity
import assistant.mobile.core.OriginMoveConfigResult
import assistant.mobile.core.OriginMoveCoordinator
import assistant.mobile.core.OriginMoveMeResult
import assistant.mobile.core.OriginMoveProbe
import assistant.mobile.core.OriginMoveRecord
import assistant.mobile.core.OriginMoveRecordStore
import assistant.mobile.core.OriginMoveSessions
import assistant.mobile.core.OriginMoveState
import assistant.mobile.core.SessionLease
import assistant.mobile.core.SessionManager
import assistant.mobile.core.SessionSecretStore
import assistant.mobile.core.StoredSession
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import io.ktor.client.engine.mock.MockEngine
import java.io.IOException
import java.net.InetSocketAddress
import java.net.URI
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Original wire invariants, authenticated through native account leases rather than WebView cookies. */
class AmbientHttpTest {
    @get:Rule val temporary = TemporaryFolder()
    private val user = "00000000-0000-4000-8000-000000000001"
    private val device = "00000000-0000-4000-8000-000000000002"
    private val session = "00000000-0000-4000-8000-000000000003"
    private val expiry = "2099-01-01T00:00:00Z"

    @Test
    fun moveAfterReadinessUploadsReopenedHomeQueueWithoutChangingItsOwnership() {
        val oldRequests = mutableListOf<String>()
        val newRequests = mutableListOf<String>()
        val batch = AtomicReference<QueuedBatch>()
        val payload = AmbientCodecFixtures.ogg()
        withServer({ exchange ->
            oldRequests += exchange.requestURI.path
            when (exchange.requestURI.path) {
                "/v1/me" -> respond(exchange, """{"id":"$user","device_id":"$device","session_expires_at":"$expiry"}""")
                "/v1/ambient-audio" -> respond(exchange,
                    """{"accepted_formats":["ogg-opus","aac-adts","wav"],"limits":{"max_compressed_batch_bytes":262144,"max_batch_duration_ms":31000}}""")
                else -> throw AssertionError("Unexpected old-server request ${exchange.requestURI}")
            }
        }) { home ->
            withServer({ exchange ->
                newRequests += exchange.requestURI.path
                assertEquals("Bearer synthetic-native-credential-never-valid", exchange.requestHeaders.getFirst("Authorization"))
                assertEquals(user, exchange.requestHeaders.getFirst("X-Expected-User"))
                assertNull(exchange.requestHeaders.getFirst("Cookie"))
                when (exchange.requestURI.path) {
                    "/v1/me" -> respond(exchange, """{"id":"$user","device_id":"$device","session_expires_at":"$expiry"}""")
                    "/v1/ambient-audio" -> respond(exchange,
                        """{"accepted_formats":["ogg-opus","aac-adts","wav"],"limits":{"max_compressed_batch_bytes":262144,"max_batch_duration_ms":31000}}""")
                    "/v1/ambient-audio/batches" -> {
                        assertEquals("POST", exchange.requestMethod)
                        assertEquals(batch.get().id, exchange.requestHeaders.getFirst("X-Audio-Batch-ID"))
                        assertEquals(batch.get().sha256, exchange.requestHeaders.getFirst("X-Audio-SHA256"))
                        assertArrayEquals(payload, exchange.requestBody.use { it.readBytes() })
                        respond(exchange, receipt(batch.get()))
                    }
                    else -> throw AssertionError("Unexpected new-server request ${exchange.requestURI}")
                }
            }) { current ->
                val namespace = AccountNamespace("native", home, user)
                val identity = NativeIdentity(user, "synthetic", "Synthetic person", "ru", "Europe/Moscow", false, 1, device, expiry)
                val stored = StoredSession(namespace, "synthetic-native-credential-never-valid", identity, device, session, expiry)
                val secrets = object : SessionSecretStore {
                    override suspend fun load(instance: String, origin: String): StoredSession {
                        assertEquals("native", instance); assertEquals(home, origin)
                        return stored
                    }
                    override suspend fun save(session: StoredSession) { throw AssertionError("The session must stay under home") }
                    override suspend fun delete(instance: String, origin: String) { throw AssertionError("The session must remain valid") }
                }
                val sessions = SessionManager("native", home, secrets, {}, allowInsecureLoopback = true)
                var decision: OriginMoveRecord? = null
                val origins = OriginMoveCoordinator(current, home,
                    object : OriginMoveSessions {
                        override fun exists(instance: String, origin: String) = instance == "native" && origin == home
                        override suspend fun load(instance: String, origin: String) = secrets.load(instance, origin)
                    },
                    object : OriginMoveRecordStore {
                        override fun read() = decision
                        override fun write(record: OriginMoveRecord) { decision = record }
                    },
                    object : OriginMoveProbe {
                        override suspend fun config(origin: String): OriginMoveConfigResult {
                            assertTrue(origin in setOf(home, current))
                            return OriginMoveConfigResult.Available("11111111-1111-4111-8111-111111111111")
                        }
                        override suspend fun me(origin: String, session: StoredSession): OriginMoveMeResult {
                            assertEquals(current, origin); assertEquals(stored, session)
                            return OriginMoveMeResult.Identity(user, device)
                        }
                    }, allowInsecureLoopback = true)
                assertTrue(origins.resolve().pending)
                val nativeHttp = NativeHttpClient(MockEngine { throw AssertionError("Ambient keeps its bounded upload transport") },
                    home, sessions, allowInsecureLoopback = true, serverOrigin = { origins.serverOrigin(home) })
                try {
                    runBlocking { sessions.restore() }
                    val authority = NativeAmbientSessionPort(sessions) { nativeHttp.serverOrigin }
                    val client = AmbientHttp(authority) { true }
                    val (owner, lease) = client.verify()
                    client.readiness(owner, lease)
                    assertEquals(AmbientBinding(home, user, device, expiry), owner)
                    val directory = temporary.newFolder()
                    val queue = AmbientQueue(directory)
                    val queued = queue.enqueueEncoded(owner, session, 7, "2026-10-06T12:00:00Z", 320, AmbientAudioFormat.OGG_OPUS, payload)
                    batch.set(queued)
                    val proofFile = java.io.File(temporary.newFolder(), "native-owner.json")
                    AmbientNativeOwnershipStore(proofFile).save(AmbientNativeOwnership(namespace, owner))
                    val proof = proofFile.readBytes()
                    runBlocking { origins.checkPending() }
                    assertEquals(OriginMoveState.MOVED, decision?.state)
                    assertEquals(home, decision?.home)
                    assertEquals(current, decision?.server)
                    assertEquals(owner, client.verify().first)
                    client.readiness(owner, lease)
                    val reopened = AmbientQueue(directory)
                    val confirmed = client.upload(reopened.list().single(), reopened.audioFile(queued), lease)
                    assertEquals(listOf(queued), reopened.list())
                    assertArrayEquals(proof, proofFile.readBytes())
                    assertEquals(home, authority.origin)
                    assertEquals(current, authority.serverOrigin)
                    assertEquals(home, sessions.lease()!!.namespace.origin)
                    assertTrue(sessions.isCurrent(lease))
                    assertTrue(reopened.acknowledge(confirmed.id, confirmed.sha256))
                    assertTrue(AmbientQueue(directory).list().isEmpty())
                    assertEquals(listOf("/v1/me", "/v1/ambient-audio"), oldRequests)
                    assertEquals(listOf("/v1/me", "/v1/ambient-audio", "/v1/ambient-audio/batches"), newRequests)
                } finally { nativeHttp.close() }
            }
        }
    }

    @Test
    fun uncertainUploadSurvivesProcessRestartAndExactAckLeavesOneArchivedBatch() {
        val archive = ConcurrentHashMap<String, String>()
        val disconnectFirstUpload = AtomicBoolean(true)
        val attempts = AtomicInteger()
        withServer({ exchange ->
            if (exchange.requestURI.path == "/v1/ambient-audio/recordings") {
                respond(exchange, """{"recordings":[${archive.keys.joinToString(",") { "\"$it\"" }}]}""")
            } else {
                assertEquals("POST", exchange.requestMethod)
                val id = exchange.requestHeaders.getFirst("X-Audio-Batch-ID")
                val hash = exchange.requestHeaders.getFirst("X-Audio-SHA256")
                exchange.requestBody.use { it.readBytes() }
                attempts.incrementAndGet()
                assertTrue(archive.putIfAbsent(id, hash).let { it == null || it == hash })
                if (disconnectFirstUpload.compareAndSet(true, false)) exchange.close()
                else respond(exchange, """{"id":"$id","sha256":"$hash","stored":true}""")
            }
        }) { origin ->
            val directory = temporary.newFolder()
            val original = AmbientQueue(directory)
            val batch = original.enqueueEncoded(AmbientBinding(origin, user, device, expiry), session, 0,
                "2026-10-06T12:00:00Z", 320, AmbientAudioFormat.OGG_OPUS, AmbientCodecFixtures.ogg())
            val port = NativeSessionFixture(origin)
            assertThrows(IOException::class.java) { AmbientHttp(port) { true }.upload(batch, original.audioFile(batch), port.activeLease!!) }
            assertEquals(listOf(batch), original.list())
            // A new queue and new in-memory lease model restoring only durable bytes after process death.
            val restored = AmbientQueue(directory)
            val newPort = NativeSessionFixture(origin)
            assertEquals(listOf(batch), restored.list())
            val receipt = AmbientHttp(newPort) { true }.upload(restored.list().single(), restored.audioFile(batch), newPort.activeLease!!)
            assertTrue(restored.acknowledge(receipt.id, receipt.sha256))
            assertFalse(restored.acknowledge(receipt.id, receipt.sha256))
            assertTrue(AmbientQueue(directory).list().isEmpty())
            assertEquals(2, attempts.get())
            assertEquals(mapOf(batch.id to batch.sha256), archive.toMap())
            val request = URI("$origin/v1/ambient-audio/recordings").toURL().openConnection()
            request.setRequestProperty("Authorization", "Bearer ${newPort.activeLease!!.credential}")
            val archived = request.getInputStream().bufferedReader().use { it.readText() }
            assertEquals("""{"recordings":["${batch.id}"]}""", archived)
        }
    }

    @Test
    fun receiptAfterAccountChangeCannotAcknowledgeOrSendQueuedAudioToAnotherPerson() {
        val port = AtomicReference<NativeSessionFixture>()
        val batch = AtomicReference<QueuedBatch>()
        withServer({ exchange ->
            exchange.requestBody.use { it.readBytes() }
            port.get().switchAccount()
            respond(exchange, receipt(batch.get()))
        }) { origin ->
            val queue = AmbientQueue(temporary.newFolder())
            val queued = queue.enqueueEncoded(AmbientBinding(origin, user, device, expiry), session, 0,
                "2026-10-06T12:00:00Z", 320, AmbientAudioFormat.OGG_OPUS, AmbientCodecFixtures.ogg())
            batch.set(queued)
            val native = NativeSessionFixture(origin)
            port.set(native)
            val lease = native.activeLease!!
            assertEquals("not_authenticated", assertThrows(AmbientProblem::class.java) {
                AmbientHttp(native) { true }.upload(queued, queue.audioFile(queued), lease)
            }.code)
            assertEquals(listOf(queued), queue.list())
            assertEquals("not_authenticated", assertThrows(AmbientProblem::class.java) {
                AmbientHttp(native) { true }.upload(queued, queue.audioFile(queued), native.activeLease!!)
            }.code)
            assertEquals(listOf(queued), queue.list())
        }
    }

    @Test
    fun expiredSessionRetainsReadyAudioAndStopsBeforeNetwork() {
        val requests = AtomicInteger()
        withServer({ exchange -> requests.incrementAndGet(); respond(exchange, "{}") }) { origin ->
            val queue = AmbientQueue(temporary.newFolder())
            val queued = queue.enqueueEncoded(AmbientBinding(origin, user, device, expiry), session, 0,
                "2026-10-06T12:00:00Z", 320, AmbientAudioFormat.OGG_OPUS, AmbientCodecFixtures.ogg())
            val port = NativeSessionFixture(origin)
            port.activeIdentity = port.activeIdentity!!.copy(sessionExpiresAt = "2020-01-01T00:00:00Z")
            val lease = port.activeLease!!
            assertEquals("not_authenticated", assertThrows(AmbientProblem::class.java) {
                AmbientHttp(port) { true }.upload(queued, queue.audioFile(queued), lease)
            }.code)
            assertNull(port.activeLease)
            assertEquals(listOf(queued), AmbientQueue(queue.audioFile(queued).parentFile!!.parentFile!!).list())
            assertEquals(0, requests.get())
        }
    }

    @Test
    fun freshNativeSessionCanUploadTheSameOwnersHistoricalExpiredBatch() {
        val uploads = AtomicInteger()
        val batch = AtomicReference<QueuedBatch>()
        withServer({ exchange ->
            assertEquals("POST", exchange.requestMethod)
            assertEquals("Bearer synthetic-native-credential-never-valid", exchange.requestHeaders.getFirst("Authorization"))
            assertEquals(user, exchange.requestHeaders.getFirst("X-Expected-User"))
            exchange.requestBody.use { it.readBytes() }
            uploads.incrementAndGet()
            respond(exchange, receipt(batch.get()))
        }) { origin ->
            val directory = temporary.newFolder()
            val queue = AmbientQueue(directory)
            val queued = queue.enqueueEncoded(AmbientBinding(origin, user, device, "2020-01-01T00:00:00Z"), session, 0,
                "2019-12-31T12:00:00Z", 320, AmbientAudioFormat.OGG_OPUS, AmbientCodecFixtures.ogg())
            batch.set(queued)
            val port = NativeSessionFixture(origin)
            val receipt = AmbientHttp(port) { true }.upload(queued, queue.audioFile(queued), port.activeLease!!)
            assertEquals(queued.id, receipt.id)
            assertEquals(queued.sha256, receipt.sha256)
            assertEquals(listOf(queued), queue.list())
            assertTrue(queue.acknowledge(receipt.id, receipt.sha256))
            assertTrue(AmbientQueue(directory).list().isEmpty())
            assertEquals(1, uploads.get())
        }
    }

    @Test
    fun aDifferentNativeDeviceCannotUploadAnotherDevicesHistoricalBatch() {
        val requests = AtomicInteger()
        withServer({ exchange -> requests.incrementAndGet(); respond(exchange, "{}") }) { origin ->
            val queue = AmbientQueue(temporary.newFolder())
            val queued = queue.enqueueEncoded(AmbientBinding(origin, user, device, "2020-01-01T00:00:00Z"), session, 0,
                "2019-12-31T12:00:00Z", 320, AmbientAudioFormat.OGG_OPUS, AmbientCodecFixtures.ogg())
            val port = NativeSessionFixture(origin)
            port.activeIdentity = port.activeIdentity!!.copy(deviceId = "00000000-0000-4000-8000-000000000005")
            assertEquals("not_authenticated", assertThrows(AmbientProblem::class.java) {
                AmbientHttp(port) { true }.upload(queued, queue.audioFile(queued), port.activeLease!!)
            }.code)
            assertEquals(listOf(queued), queue.list())
            assertEquals(0, requests.get())
        }
    }

    private fun receipt(batch: QueuedBatch) = """{"id":"${batch.id}","sha256":"${batch.sha256}","stored":true,"format":"${batch.format.id}"}"""

    private fun withServer(handler: (HttpExchange) -> Unit, block: (String) -> Unit) {
        val failure = AtomicReference<Throwable>()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            try { handler(exchange) } catch (problem: Throwable) { failure.set(problem); exchange.close() }
        }
        server.start()
        try { block("http://127.0.0.1:${server.address.port}"); failure.get()?.let { throw it } }
        finally { server.stop(0) }
    }

    private fun respond(exchange: HttpExchange, body: String) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        exchange.responseHeaders.add("Content-Type", "application/json")
        exchange.sendResponseHeaders(200, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
        exchange.close()
    }

    private inner class NativeSessionFixture(override val origin: String) : AmbientSessionPort {
        @Volatile var activeLease: SessionLease? = SessionLease(AccountNamespace("native-fixture", origin, user), 1,
            "synthetic-native-credential-never-valid", session)
        @Volatile var activeIdentity: NativeIdentity? = NativeIdentity(user, "synthetic", "Synthetic person", "ru", "Europe/Moscow",
            false, 1, device, expiry)
        override fun lease(): SessionLease? = activeLease
        override fun identity(): NativeIdentity? = activeIdentity
        override fun isCurrent(lease: SessionLease): Boolean = lease === activeLease
        override fun expire(lease: SessionLease) { if (isCurrent(lease)) { activeLease = null; activeIdentity = null } }
        fun switchAccount() {
            val next = "00000000-0000-4000-8000-000000000004"
            activeLease = SessionLease(AccountNamespace("native-fixture", origin, next), 2, "synthetic-other-never-valid", session)
            activeIdentity = activeIdentity!!.copy(id = next)
        }
    }
}
