package assistant.mobile.android.whoop

import assistant.mobile.core.*
import com.sun.net.httpserver.HttpServer
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.headersOf
import java.net.InetSocketAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

private const val ORIGIN = "https://whoop-native.invalid"
private const val NOW = 1791158400_000L
private val DEVICE = WhoopDevice("AA:BB:CC:DD:EE:01", "WHOOP synthetic", bonded = true, rssi = -50)
private fun syntheticGrant(user: String = "A") = WhoopGrant(obj {
    put("user_id", user); put("token", "synthetic-collector-$user"); put("collector_id", "collector-$user")
    put("source_id", "source-$user"); put("generation", 1)
})
private fun syntheticLease(user: String = "A") = SessionLease(AccountNamespace("native", ORIGIN, user), 1, "synthetic-person-$user")

private class MemoryWhoopStore : WhoopStorePort {
    val metadata = mutableMapOf<String, JsonElement>()
    val segments = mutableListOf<List<ByteArray>>()
    var pending = false
    var failFence = false
    var unreadable = false
    var clears = 0
    var acknowledges = 0
    var rebases = 0
    private var frozen: JsonObject? = null
    val closed = mutableListOf<Pair<JsonObject, Int>>()
    override fun forgetPending() = pending
    override fun markForgetPending() { if (failFence) throw WhoopFailure("store_unavailable"); pending = true }
    override fun get(key: String): JsonElement? {
        if (unreadable) throw java.security.GeneralSecurityException("Synthetic foreign-origin ciphertext")
        return metadata[key]
    }
    override fun put(key: String, value: JsonElement) { metadata[key] = value }
    override fun count() = segments.size
    override fun bytes() = segments.sumOf { frames -> frames.sumOf { it.size }.toLong() }
    override fun full() = false
    override fun append(frames: List<ByteArray>): Boolean { segments += frames; return true }
    override fun batch(cursor: JsonElement, deviceState: JsonObject?): JsonObject? {
        frozen?.let { return it }
        if (segments.isEmpty() && deviceState == null) return null
        return obj {
            put("key", "synthetic-key")
            put("body", obj { put("cursor", "next-cursor"); put("expected_cursor", cursor); deviceState?.let { put("device_state", it) } })
        }.also { frozen = it }
    }
    override fun rebase(batch: JsonObject, cursor: JsonElement): JsonObject {
        rebases++
        return JsonObject(batch + ("key" to JsonPrimitive("synthetic-rebased")) +
            ("body" to JsonObject((batch["body"] as JsonObject) + ("expected_cursor" to cursor)))).also { frozen = it }
    }
    override fun acknowledge(batch: JsonObject) {
        acknowledges++; segments.clear(); frozen = null
        metadata["device_state"] = JsonNull
    }
    override fun closeBinding(reason: String): JsonObject? {
        val original = metadata["grant"] as? JsonObject ?: return null
        val receipt = obj { put("reason", reason); put("status", "closed_without_transfer"); put("user_id", original["user_id"]!!); put("records", count()) }
        closed += receipt to count()
        clear()
        return receipt
    }
    override fun clear() { clears++; metadata.clear(); segments.clear(); frozen = null; pending = false }
    override fun clearForServerSwitch() { clear(); closed.clear(); unreadable = false }
    fun bound(user: String = "A", background: Boolean = true, native: Boolean = true) {
        metadata["grant"] = syntheticGrant(user).value
        metadata["state"] = WhoopState(selected = DEVICE).json()
        metadata["background"] = JsonPrimitive(background)
        if (native) metadata["native_enrollment"] = obj {
            put("instance", "native"); put("origin", ORIGIN); put("user_id", user); put("device_id", "device-$user")
            put("collector_id", "collector-$user"); put("source_id", "source-$user"); put("generation", 1)
        }
    }
}

private class FakeWhoopHttp : WhoopHttp {
    var enrolled: SessionLease? = null
    var states = 0
    var sends = 0
    var revokeFails = false
    var revokeCalls = 0
    val revoked = mutableListOf<WhoopGrant>()
    var onState: (suspend () -> Unit)? = null
    var onReceipt: (suspend (WhoopGrant, String) -> WhoopReply)? = null
    var onSend: (suspend (WhoopGrant, JsonObject) -> WhoopReply)? = null
    override suspend fun enroll(lease: SessionLease, installation: String, name: String,
        checkpoint: () -> Unit, issued: (WhoopGrant) -> Unit): WhoopGrant {
        checkpoint(); enrolled = lease
        return syntheticGrant(lease.namespace.userId).also(issued)
    }
    override suspend fun state(grant: WhoopGrant, checkpoint: () -> Unit): JsonObject {
        states++; onState?.invoke(); checkpoint()
        return obj {
            put("source_id", grant.source); put("collector_id", grant.identity); put("generation", 1)
            put("profile", WhoopState.PROFILE); put("cursor", "remote-cursor")
        }
    }
    override suspend fun receipt(grant: WhoopGrant, key: String): WhoopReply =
        onReceipt?.invoke(grant, key) ?: WhoopReply(404, obj {})
    override suspend fun send(grant: WhoopGrant, batch: JsonObject): WhoopReply {
        sends++
        return onSend?.invoke(grant, batch) ?: WhoopReply(201, obj {
            put("source_id", grant.source); put("cursor", (batch["body"] as JsonObject)["cursor"]!!)
        })
    }
    override suspend fun revoke(grant: WhoopGrant) {
        revokeCalls++
        if (revokeFails) throw WhoopFailure("unavailable")
        revoked += grant
    }
}

private class FakeWhoopWork : WhoopWorkScheduler {
    val scheduled = mutableListOf<String>()
    val uploads = mutableListOf<String>()
    val cancelled = mutableListOf<String>()
    var cancels = 0
    var revocations = 0
    override fun schedule(collector: String) { scheduled += collector }
    override fun continueHistory(collector: String) = Unit
    override fun upload(collector: String) { uploads += collector }
    override fun cancelCollect(collector: String) { cancelled += collector }
    override fun cancelAll() { cancels++ }
    override fun revoke() { revocations++ }
}

private class MemoryWhoopRevokes : WhoopRevokeQueue {
    val grants = mutableListOf<WhoopGrant>()
    var failAdd = false
    override fun add(grant: WhoopGrant) {
        if (failAdd) throw WhoopFailure("store_unavailable")
        grants.removeAll { it.identity == grant.identity }; grants += grant
    }
    override fun all() = grants.toList()
    override fun remove(grant: WhoopGrant) { grants.removeAll { it.identity == grant.identity } }
}

/** The complete native path talks to a scripted strap through the same WhoopSession as Android. */
private class FakeWhoopBluetooth : WhoopBluetooth {
    var permissions = "granted"
    var enabled = true
    var scans = 0
    var onOpen: (suspend () -> Unit)? = null
    var onClose: (() -> Unit)? = null
    val bonds = mutableListOf<String>()
    val opens = mutableListOf<String>()
    override fun permissionNames() = arrayOf("synthetic.BLUETOOTH_SCAN", "synthetic.BLUETOOTH_CONNECT")
    override fun permissionState() = permissions
    override fun bluetoothEnabled() = enabled
    override suspend fun scan(): List<WhoopDevice> { scans++; return listOf(DEVICE) }
    override suspend fun bond(address: String) { bonds += address }
    override fun connection(address: String): WhoopConnection {
        val opening = onOpen
        val closing = onClose
        return CompleteWhoopConnection(onOpen = { opens += address; opening?.invoke() }, onClose = { closing?.invoke() })
    }
}
private class CompleteWhoopConnection(
    private val onOpen: suspend () -> Unit = {},
    private val onClose: () -> Unit = {},
) : WhoopConnection {
    private val notices = Channel<WhoopNotice>(Channel.UNLIMITED)
    private fun frame(inner: ByteArray): ByteArray {
        val padded = inner + ByteArray((4 - inner.size % 4) % 4)
        val head = byteArrayOf(0xAA.toByte(), 1, (padded.size + 4).toByte(), ((padded.size + 4) ushr 8).toByte(), 1, 0)
        val crc16 = WhoopProtocol.crc16(head)
        val crc32 = WhoopProtocol.crc32(padded, 0, padded.size)
        return head + byteArrayOf(crc16.toByte(), (crc16 ushr 8).toByte()) + padded + ByteArray(4) { (crc32 ushr (8 * it)).toByte() }
    }
    override suspend fun open() { onOpen() }
    override suspend fun write(bytes: ByteArray) {
        val origin = bytes[9]; val command = bytes[10]
        val body = if ((command.toInt() and 0xFF) == WhoopProtocol.GET_CLOCK)
            byteArrayOf(1) + ByteArray(4) { ((NOW / 1000) ushr (8 * it)).toByte() } + ByteArray(2) else ByteArray(0)
        notices.send(WhoopNotice("cmd", frame(byteArrayOf(WhoopProtocol.RESPONSE.toByte(), 0, command, origin, 1) + body)))
        if ((command.toInt() and 0xFF) == WhoopProtocol.SEND_HISTORY) {
            notices.send(WhoopNotice("data", frame(byteArrayOf(WhoopProtocol.METADATA.toByte(), 0, WhoopProtocol.HISTORY_COMPLETE.toByte()))))
        }
    }
    override suspend fun receive() = notices.receive()
    override suspend fun battery() = 71
    override suspend fun version(kind: String) = "synthetic-$kind"
    override fun close() { onClose(); notices.close() }
}

private class WhoopHarness(bound: String? = null, background: Boolean = true, native: Boolean = true, autoLogin: Boolean = true, unreadableStore: Boolean = false) : AutoCloseable {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    val store = MemoryWhoopStore().apply { bound?.let { this.bound(it, background, native) }; unreadable = unreadableStore }
    val bluetooth = FakeWhoopBluetooth()
    val http = FakeWhoopHttp()
    val work = FakeWhoopWork()
    val revokes = MemoryWhoopRevokes()
    var current: SessionLease? = null
    val repository = WhoopRepository(ORIGIN, bluetooth, store, http, work, revokes,
        installation = { "synthetic-native-installation" }, message = { it }, scope = scope,
        now = { NOW }, sessionIsCurrent = { it === current })
    init { if (autoLogin) bound?.let { login(it) } }
    fun login(user: String = "A", deviceId: String = "device-$user"): SessionLease = syntheticLease(user).also {
        current = it; repository.updateSession(it, deviceId)
    }
    override fun close() { scope.cancel() }
}

class WhoopRepositoryTest {
    @Test fun serverSwitchWaitsForLateEnrollmentAndDurablyQueuesItsUnusedGrant() = runBlocking {
        val minted = CompletableDeferred<Unit>()
        val release = CountDownLatch(1)
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/v1/personal/rings/enroll") { exchange ->
                exchange.requestBody.use { it.readBytes() }
                val response = syntheticGrant().value.toString().toByteArray()
                minted.complete(Unit)
                check(release.await(10, TimeUnit.SECONDS)) { "Synthetic enrollment response was not released" }
                exchange.sendResponseHeaders(200, response.size.toLong())
                exchange.responseBody.use { it.write(response) }
            }
            start()
        }
        val origin = "http://127.0.0.1:${server.address.port}"
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val store = MemoryWhoopStore()
        val work = FakeWhoopWork()
        val revokes = MemoryWhoopRevokes()
        val bluetooth = FakeWhoopBluetooth()
        var current: SessionLease? = SessionLease(AccountNamespace("native", origin, "A"), 1, "synthetic-person-A")
        val repository = WhoopRepository(origin, bluetooth, store, NativeWhoopHttp(origin), work, revokes,
            installation = { "synthetic-installation" }, message = { it }, scope = scope,
            now = { NOW }, sessionIsCurrent = { it === current })
        try {
            repository.updateSession(current, "device-A")
            repository.scan(); repository.select(DEVICE.address)
            withTimeout(5_000) { minted.await() }
            current = null
            repository.updateSession(null)
            val cleanup = async(start = CoroutineStart.UNDISPATCHED) { repository.clearForServerSwitch() }
            assertFalse("Local erasure must await the minted grant callback", cleanup.isCompleted)
            release.countDown()
            withTimeout(5_000) { cleanup.await() }
            assertEquals(listOf("collector-A"), revokes.all().map { it.identity })
            assertEquals("synthetic-collector-A", revokes.all().single().token)
            assertTrue(work.revocations > 0)
            assertNull(store.get("grant")); assertEquals(0, store.count())
            assertTrue(bluetooth.opens.isEmpty())
        } finally {
            release.countDown(); scope.cancel(); server.stop(0)
        }
    }

    @Test fun legacyGrantCannotResumeAtStartupOrAfterLoginAndExplicitEnrollmentClosesItsQueue() = runBlocking {
        WhoopHarness("A", native = false, autoLogin = false).use { h ->
            h.store.append(listOf(byteArrayOf(1, 2, 3)))
            assertTrue(h.work.scheduled.isEmpty()); assertTrue(h.work.uploads.isEmpty())
            h.repository.upload("collector-A"); h.repository.collect("collector-A", continuation = true)
            h.login("B")
            assertEquals("native_enrollment_required", h.repository.states.value.error?.code)
            assertNull(h.repository.states.value.selected)
            h.repository.upload("collector-A"); h.repository.collect("collector-A", continuation = true)
            assertTrue(h.bluetooth.opens.isEmpty()); assertEquals(0, h.http.states); assertEquals(0, h.http.sends)
            assertEquals(1, h.store.count()); assertEquals("A", (h.store.get("grant") as JsonObject).string("user_id"))
            h.repository.scan(); h.repository.select(DEVICE.address)
            assertEquals(1, h.store.closed.single().second)
            assertEquals("closed_without_transfer", h.store.closed.single().first.string("status"))
            assertEquals("A", h.store.closed.single().first.string("user_id"))
            assertEquals("B", (h.store.get("grant") as JsonObject).string("user_id"))
            assertEquals("device-B", (h.store.get("native_enrollment") as JsonObject).string("device_id"))
            assertEquals(0, h.store.count()); assertEquals(0, h.http.sends)
            assertEquals(1, h.bluetooth.opens.size)
        }
    }

    @Test fun newLoginDeviceFencesTheSamePersonsNativeGrantWithoutDeletingPendingFrames() = runBlocking {
        WhoopHarness("A").use { h ->
            h.store.append(listOf(byteArrayOf(4, 5)))
            val replacement = h.login("A", "replacement-device")
            h.repository.upload("collector-A"); h.repository.collect("collector-A", continuation = true)
            h.repository.logout(replacement.namespace)
            assertEquals(1, h.store.count()); assertNotNull(h.store.get("grant"))
            assertTrue(h.bluetooth.opens.isEmpty()); assertEquals(0, h.http.sends)
            assertEquals("native_enrollment_required", h.repository.states.value.error?.code)
            assertTrue(h.revokes.all().isEmpty())
        }
    }


    @Test fun lapsePreservesIndependentBackgroundAndAnotherPersonOnlyFencesIt() = runBlocking {
        WhoopHarness("A").use { h ->
            h.store.append(listOf(byteArrayOf(1, 2)))
            h.login(); h.current = null; h.repository.updateSession(null)
            assertEquals("A", h.repository.states.value.user)
            assertTrue(h.repository.states.value.backgroundEnabled)
            assertNotNull(h.store.get("grant")); assertEquals(1, h.store.count())
            h.repository.collect("collector-A", continuation = false)
            assertEquals(1, h.bluetooth.opens.size)
            h.login("B")
            assertEquals("A", (h.store.get("grant") as JsonObject).string("user_id")); assertEquals(1, h.store.count())
            assertNull(h.repository.states.value.selected)
            assertEquals("B", h.repository.states.value.user)
            assertEquals("native_enrollment_required", h.repository.states.value.error?.code)
            assertTrue(h.revokes.all().isEmpty())
            h.repository.upload("collector-A"); h.repository.collect("collector-A", continuation = true)
            assertEquals(1, h.bluetooth.opens.size); assertEquals(0, h.http.sends)
        }
    }

    @Test fun serverSwitchWithoutALeaseErasesLegacyAndClosedBindingsAndKeepsDurableRevocation() = runBlocking {
        WhoopHarness("A", native = false, autoLogin = false).use { h ->
            h.store.append(listOf(byteArrayOf(1)))
            h.store.closeBinding("synthetic-closed-binding")
            h.store.bound("A", native = false)
            h.store.append(listOf(byteArrayOf(2, 3)))
            h.http.revokeFails = true

            h.repository.clearForServerSwitch()

            assertEquals(0, h.store.count()); assertTrue(h.store.closed.isEmpty())
            assertNull(h.store.get("grant")); assertNull(h.store.get("native_enrollment"))
            assertFalse(h.store.pending); assertNull(h.repository.states.value.error)
            assertNull(h.repository.states.value.user); assertNull(h.repository.states.value.selected)
            h.repository.reconcile()
            assertTrue(h.store.metadata.isEmpty())
            assertTrue(h.work.cancels > 0); assertTrue(h.work.revocations > 0)
            assertEquals(listOf("A"), h.revokes.all().map { it.user })
            assertFalse(h.repository.revokePending())
            assertEquals(0, h.store.count()); assertTrue(h.store.closed.isEmpty())
            h.http.revokeFails = false
            assertTrue(h.repository.revokePending()); assertTrue(h.revokes.all().isEmpty())
        }
    }

    @Test fun failedForgetDurabilityStillFencesWorkersAndPreservesTheQueue() = runBlocking {
        for (revokeFailure in listOf(false, true)) WhoopHarness("A").use { h ->
            h.login(); h.store.append(listOf(byteArrayOf(3)))
            h.store.failFence = !revokeFailure; h.revokes.failAdd = revokeFailure
            h.repository.forget().join()
            assertEquals("store_unavailable", h.repository.states.value.error?.code)
            assertTrue(h.work.cancels > 0)
            assertNotNull(h.store.get("grant")); assertEquals(1, h.store.count())
            h.repository.collect("collector-A", continuation = true)
            h.repository.upload("collector-A")
            assertTrue(h.bluetooth.opens.isEmpty()); assertEquals(0, h.http.sends)
            assertEquals(revokeFailure, h.store.pending)
        }
    }

    @Test fun confirmedReceiptAloneCanRemoveTheQueuedFrames() = runBlocking {
        WhoopHarness("A").use { h ->
            h.store.append(listOf(byteArrayOf(9)))
            h.http.onSend = { _, _ -> WhoopReply(200, obj { put("source_id", "wrong"); put("cursor", "next-cursor") }) }
            assertFalse(h.repository.upload("collector-A"))
            assertEquals(1, h.store.count()); assertEquals(0, h.store.acknowledges)
            assertEquals("invalid_receipt", h.repository.states.value.error?.code)
            h.http.onSend = null
            assertTrue(h.repository.upload("collector-A"))
            assertEquals(0, h.store.count()); assertEquals(1, h.store.acknowledges)
        }
    }

    @Test fun sameUserReceiptForAnotherOriginOrInstanceCannotClearOrFenceTheCollector() = runBlocking {
        for (foreign in listOf(syntheticLease("A").namespace.copy(origin = "https://another-origin.invalid"),
            syntheticLease("A").namespace.copy(instance = "another-instance"))) {
            WhoopHarness("A", autoLogin = false).use { h ->
                h.store.append(listOf(byteArrayOf(3, 4, 5)))
                val original = h.store.metadata.toMap()
                h.repository.logout(foreign)
                assertEquals(original, h.store.metadata)
                assertEquals(1, h.store.count()); assertEquals(0, h.store.clears); assertFalse(h.store.pending)
                assertEquals(0, h.work.cancels); assertTrue(h.revokes.all().isEmpty())
                assertTrue(h.bluetooth.opens.isEmpty()); assertEquals(0, h.http.sends)
            }
        }
    }

    @Test fun deletionReceiptCleanupPreservesUnreadableForeignCollectorWithoutFailingTheCurrentAccount() = runBlocking {
        WhoopHarness("A", autoLogin = false, unreadableStore = true).use { h ->
            h.store.metadata["native_enrollment"] = JsonObject((h.store.metadata["native_enrollment"] as JsonObject) +
                ("origin" to JsonPrimitive("https://previous-origin.invalid")))
            h.store.append(listOf(byteArrayOf(7, 8, 9)))
            val originalMetadata = h.store.metadata.toMap()
            val originalFrames = h.store.segments.map { frames -> frames.map { it.toList() } }
            val namespace = syntheticLease("A").namespace
            val identity = NativeIdentity("A", "synthetic", "Synthetic", "ru", "UTC", false, 1, "device-A", "2099-01-01T00:00:00Z")
            var person: StoredSession? = StoredSession(namespace, "synthetic-person-A", identity,
                "device-A", "session-A", identity.sessionExpiresAt)
            val personSecrets = object : SessionSecretStore {
                override suspend fun load(instance: String, origin: String) = person
                override suspend fun save(session: StoredSession) { person = session }
                override suspend fun delete(instance: String, origin: String) { person = null }
            }
            val deletionId = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"
            val acceptedReceipt = StoredAccountDeletion(namespace, receipt = AccountDeletionReceipt(deletionId,
                DeletionStatus.PENDING, "synthetic-deletion-receipt-token", "/v1/account-deletions/$deletionId"))
            var protectedReceipt: StoredAccountDeletion? = acceptedReceipt
            val receiptSecrets = object : DeletionSecretStore {
                override suspend fun load(instance: String, origin: String) = protectedReceipt
                override suspend fun save(record: StoredAccountDeletion) { protectedReceipt = record }
                override suspend fun delete(instance: String, origin: String) { protectedReceipt = null }
            }
            val sessions = SessionManager("native", ORIGIN, personSecrets, h.repository::logout, clockMs = { NOW })
            val requests = mutableListOf<String>()
            val http = NativeHttpClient(MockEngine { request ->
                requests += request.url.encodedPath
                respond("{}", headers = headersOf("Content-Type", "application/json"))
            }, ORIGIN, sessions, clockMs = { NOW })
            try {
                sessions.restore(); h.repository.bind(sessions)
                val deletion = AccountDeletionController(sessions, http, receiptSecrets, h.repository::logout)
                deletion.restore() // Receipt admission expires the lease before invoking account-addressed cleanup.
                assertEquals(DeletionStatus.PENDING, deletion.state.value.status)
                assertNull(deletion.state.value.failure); assertFalse(deletion.state.value.busy)
                assertNull(person); assertNull(sessions.lease()); assertEquals(acceptedReceipt, protectedReceipt)
                val reopened = AccountDeletionController(sessions, http, receiptSecrets, h.repository::logout)
                reopened.restore()
                assertEquals(DeletionStatus.PENDING, reopened.state.value.status)
                assertNull(reopened.state.value.failure); assertEquals(acceptedReceipt, protectedReceipt)
                assertEquals(originalMetadata, h.store.metadata)
                assertEquals(originalFrames, h.store.segments.map { frames -> frames.map { it.toList() } })
                assertEquals(0, h.store.clears); assertFalse(h.store.pending)
                assertTrue(h.revokes.all().isEmpty()); assertTrue(h.work.cancels > 0)
                assertTrue(h.bluetooth.opens.isEmpty()); assertEquals(0, h.http.sends)
                assertTrue(requests.isEmpty())
            } finally { http.close() }
        }
    }

    @Test fun nativeSessionManagerLogoutRunsCollectorCleanupButExpiryKeepsItsGrant() = runBlocking {
        WhoopHarness("A").use { h ->
            val identity = NativeIdentity("A", "synthetic", "Synthetic", "ru", "UTC", false, 1, "device-A", "2099-01-01T00:00:00Z")
            var saved: StoredSession? = StoredSession(syntheticLease().namespace, "synthetic-person-A", identity,
                "device-A", "session-A", identity.sessionExpiresAt)
            val secrets = object : SessionSecretStore {
                override suspend fun load(instance: String, origin: String) = saved
                override suspend fun save(session: StoredSession) { saved = session }
                override suspend fun delete(instance: String, origin: String) { saved = null }
            }
            var cleanups = 0
            val sessions = SessionManager("native", ORIGIN, secrets, { namespace ->
                cleanups++; h.repository.logout(namespace)
            }, clockMs = { NOW })
            val requests = mutableListOf<String>()
            val http = NativeHttpClient(MockEngine { request ->
                requests += request.url.encodedPath
                assertEquals("Bearer synthetic-person-A", request.headers["Authorization"])
                assertEquals("A", request.headers["X-Expected-User"])
                respond("{}", headers = headersOf("Content-Type", "application/json"))
            }, ORIGIN, sessions, clockMs = { NOW })
            try {
                sessions.restore(); h.repository.bind(sessions)
                val lease = requireNotNull(sessions.lease())
                sessions.expire(lease)
                assertEquals(0, cleanups); assertNotNull(h.store.get("grant"))
                saved = StoredSession(lease.namespace, "synthetic-person-A", identity, "device-A", "session-A", identity.sessionExpiresAt)
                sessions.restore()
                sessions.logout()
                assertEquals(1, cleanups); assertNull(h.store.get("grant"))
                assertTrue(h.work.cancels > 0); assertTrue(h.work.revocations > 0)
                assertEquals(listOf("/v1/auth/native/logout"), requests)
            } finally { http.close() }
        }
    }
}
