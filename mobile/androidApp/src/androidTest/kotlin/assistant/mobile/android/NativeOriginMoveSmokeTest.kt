package assistant.mobile.android

import android.Manifest
import android.content.Context
import android.content.Intent
import android.database.sqlite.SQLiteDatabase
import android.location.Location
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import android.util.AtomicFile
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.WorkManager
import assistant.mobile.core.AccountNamespace
import assistant.mobile.core.NativeIdentity
import assistant.mobile.core.NativeJson
import assistant.mobile.core.OriginMoveCoordinator
import assistant.mobile.core.OriginMoveSessions
import assistant.mobile.core.OriginMoveState
import assistant.mobile.core.StoredSession
import assistant.mobile.core.journal.RoomJournalStore
import assistant.mobile.core.journal.Submission
import assistant.mobile.core.journal.SubmissionState
import assistant.mobile.core.location.LocationCollectorGrant
import assistant.mobile.platform.AndroidOriginMoveProbe
import assistant.mobile.platform.AndroidOriginMoveRecordStore
import assistant.mobile.platform.AndroidNativeOrigins
import assistant.mobile.platform.AndroidNativeOriginsTestAccess
import assistant.mobile.platform.KeystoreSessionSecretStore
import assistant.mobile.platform.NativeCorePlatform
import assistant.mobile.platform.location.CollectorReply
import assistant.mobile.platform.location.CollectorTransport
import assistant.mobile.platform.location.LocationUpdateRegistration
import assistant.mobile.platform.location.NativeLocationCollector
import assistant.mobile.platform.location.NarrowCollectorTransport
import assistant.mobile.platform.ring.RingRepository
import java.io.BufferedInputStream
import java.io.Closeable
import java.io.EOFException
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.security.MessageDigest
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** CI emulator only: real Keystore, Room and encrypted collector data; both servers are synthetic. */
@RunWith(AndroidJUnit4::class)
class NativeOriginMoveSmokeTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    @Test fun verifiedMoveRestoresTheNativeRuntimeAndUploadsHomeDataToTheNewServer() = runBlocking {
        val instanceId = UUID.randomUUID().toString()
        MoveServer(instanceId).use { previous ->
            MoveServer(instanceId).use { current ->
                Installation(previous, current).use { installation ->
                    installation.seed()
                    current.configStatus = 503
                    installation.launchRuntime("${current.origin}/recordings")
                    await("current-origin link opens recordings while the move is pending") {
                        previous.requests.any { it.path == "/v1/ambient-audio/recordings" }
                    }
                    await("unavailable current server leaves verification pending") {
                        503 in current.configResponses
                    }
                    assertEquals(previous.origin, installation.runtime.core.sessions.origin)
                    assertEquals(previous.origin, installation.runtime.core.http.serverOrigin)
                    assertTrue(installation.origins.resolve().pending)
                    assertTrue(current.requests.none { "authorization" in it.headers })
                    installation.closeRuntime()
                    installation.nextProcess()
                    current.configStatus = 200
                    val sessionBefore = installation.sessionFile.readBytes()
                    val collectorBefore = installation.collectorSnapshot()
                    assertTrue(installation.origins.resolve().pending)
                    assertEquals(previous.origin, installation.origins.homeOrigin)
                    assertEquals(previous.origin, RingRepository.configuredOrigin(context))

                    val moved = installation.origins.checkPending()
                    assertFalse(moved.pending)
                    assertEquals(previous.origin, moved.homeOrigin)
                    assertEquals(current.origin, moved.serverOrigin)
                    assertEquals(OriginMoveState.MOVED, installation.records.read()?.state)
                    assertArrayEquals(sessionBefore, installation.sessionFile.readBytes())
                    assertEquals(collectorBefore, installation.collectorSnapshot())
                    assertTrue(previous.requests.filter { it.path == "/v1/client/config" }
                        .all { "authorization" !in it.headers })
                    val verification = current.requests.filter { "authorization" in it.headers }.single()
                    assertEquals("/v1/me", verification.path)
                    assertEquals("Bearer $SESSION_TOKEN", verification.headers["authorization"])
                    assertEquals(installation.namespace.userId, verification.headers["x-expected-user"])
                    assertFalse("cookie" in verification.headers)
                    assertEquals(previous.origin, AndroidNativeOrigins.homeOrigin(context))
                    assertEquals(current.origin, AndroidNativeOrigins.serverOrigin(context, previous.origin))
                    assertEquals(previous.origin, RingRepository.configuredOrigin(context))
                    val previousRequestCount = previous.requests.size

                    installation.launchRuntime()
                    await("stored native session restored") { installation.runtime.ready.value }
                    val lease = requireNotNull(installation.runtime.core.sessions.lease())
                    assertEquals(installation.namespace, lease.namespace)
                    assertEquals(current.origin, installation.runtime.core.http.serverOrigin)
                    assertEquals(installation.key, installation.journal.submissions(installation.namespace).single().key)
                    installation.runtime.core.submissions.retry(lease, installation.key)
                    assertTrue(current.requests.any { it.method == "POST" && it.path == "/v1/conversation/messages" &&
                        it.headers["idempotency-key"] == installation.key })
                    assertEquals(SubmissionState.RESOLVED,
                        installation.journal.submission(installation.namespace, installation.key)?.state)

                    installation.assertCollectorHome()
                    assertEquals(1, installation.collector.status(installation.namespace).queued)
                    installation.uploadsAllowed.set(true)
                    assertTrue(installation.collector.upload())
                    assertEquals(0, installation.collector.status(installation.namespace).queued)
                    val upload = current.requests.single { it.path == NativeLocationCollector.POINTS }
                    assertEquals("Bearer ${installation.collectorToken}", upload.headers["authorization"])
                    val point = JSONObject(upload.body).getJSONArray("points").getJSONObject(0)
                    assertEquals(62.12345, point.getDouble("latitude"), 0.0)
                    assertEquals("background", point.getString("source"))
                    assertTrue(previous.requests.drop(previousRequestCount).none { "authorization" in it.headers })
                    assertTrue((previous.requests + current.requests).none { it.path.startsWith("/v1/auth/native/login") })
                    assertNull(installation.secrets.load("native", current.origin))
                    assertTrue(previous.failures.isEmpty())
                    assertTrue(current.failures.isEmpty())
                }
            }
        }
    }

    private inner class Installation(val previous: MoveServer, val current: MoveServer) : Closeable {
        val secrets = KeystoreSessionSecretStore(context)
        val records = AndroidOriginMoveRecordStore(context, allowInsecureLoopback = true)
        var origins = newCoordinator()
            private set

        private fun newCoordinator() = OriginMoveCoordinator(current.origin, previous.origin,
            object : OriginMoveSessions {
                override fun exists(instance: String, origin: String) = secrets.exists(instance, origin)
                override suspend fun load(instance: String, origin: String) = secrets.load(instance, origin)
            }, records, AndroidOriginMoveProbe(current.origin, allowInsecureLoopback = true),
            allowInsecureLoopback = true)
        private val previousCoordinator = AndroidNativeOriginsTestAccess.replace(origins)
        val namespace = AccountNamespace("native", previous.origin, current.identity.id)
        val stored = StoredSession(namespace, SESSION_TOKEN, current.identity, current.identity.deviceId,
            "origin-move-session", current.identity.sessionExpiresAt)
        val sessionFile = File(context.noBackupFilesDir, "native-sessions/${digest("native\u0000${previous.origin}")}")
        private val database = NativeCorePlatform.journal(context)
        val journal = RoomJournalStore(database)
        val key = UUID.randomUUID().toString()
        val collectorToken = "synthetic-location-${UUID.randomUUID()}"
        val uploadsAllowed = AtomicBoolean(false)
        private val transport = NarrowCollectorTransport(previous.origin,
            serverOrigin = { AndroidNativeOrigins.serverOrigin(context, previous.origin) })
        val collector = NativeLocationCollector(context, previous.origin,
            CollectorTransport { path, body, token ->
                if (uploadsAllowed.get()) transport.post(path, body, token) else CollectorReply(503, "{}")
            }, object : LocationUpdateRegistration { override fun start() = Unit; override fun stop() = Unit })
        lateinit var runtime: NativeRuntime
        private var scenario: ActivityScenario<MainActivity>? = null

        suspend fun seed() {
            AtomicFile(File(context.noBackupFilesDir, "native-origin-move.json")).delete()
            grantBackgroundLocation()
            secrets.save(stored)
            journal.insertSubmission(Submission(namespace, key, "/v1/conversation/messages",
                "/v1/conversation/submissions/by-key/$key", "POST",
                "{\"text\":\"Synthetic origin move message\"}".encodeToByteArray(),
                "assistant", emptyMap(), null, SubmissionState.ACCEPTED, System.currentTimeMillis()))
            collector.enable(namespace, "origin-move-installation", LocationCollectorGrant(
                "origin-move-collector", namespace.userId, collectorToken,
                Instant.ofEpochMilli(System.currentTimeMillis() + 3_600_000).toString()))
            collector.received(listOf(Location("synthetic-origin-move").apply {
                latitude = 62.12345; longitude = 42.54321; accuracy = 20f
                time = System.currentTimeMillis(); elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
            }))
            assertEquals(1, collector.status(namespace).queued)
        }

        fun nextProcess() {
            origins = newCoordinator()
            AndroidNativeOriginsTestAccess.replace(origins)
        }

        fun launchRuntime(link: String? = null) {
            NativeRuntimeFactory.testFactory = { runtimeContext ->
                NativeRuntime(runtimeContext).also { runtime = it }
            }
            val launched = ActivityScenario.launch<MainActivity>(Intent(context, MainActivity::class.java))
            scenario = launched
            if (link != null) {
                await("home session restored before the warm incoming link") {
                    runtime.ready.value && runtime.core.sessions.lease()?.namespace == namespace
                }
                val marker = UUID.randomUUID().toString()
                lateinit var launchIntent: Intent
                launched.onActivity { activity ->
                    launchIntent = Intent(activity.intent)
                    activity.startActivity(Intent(activity, MainActivity::class.java).setAction(Intent.ACTION_VIEW)
                        .setData(Uri.parse(link)).putExtra("origin_move_smoke_link_attempt", marker)
                        .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP))
                }
                await("current-origin warm link delivered") {
                    var delivered = false
                    launched.onActivity {
                        delivered = it.intent.getStringExtra("origin_move_smoke_link_attempt") == marker
                    }
                    delivered
                }
                launched.onActivity { it.intent = Intent(launchIntent) }
            }
        }

        suspend fun closeRuntime() {
            val closing = scenario ?: return
            scenario = null
            closing.close()
            // Wait for cancellation and pending store work before taking the process-boundary snapshot.
            runtime.core.close()
        }

        fun assertCollectorHome() {
            assertEquals(previous.origin, collector.origin)
            assertTrue(collector.status(namespace).enabled)
            val registered = context.getSharedPreferences("native-location-origins", Context.MODE_PRIVATE)
                .getStringSet("origins", emptySet()).orEmpty()
            assertTrue(previous.origin in registered)
            assertFalse(current.origin in registered)
            assertTrue(collectorFile(previous.origin).exists())
            if (collectorFile(current.origin).exists()) SQLiteDatabase.openDatabase(
                collectorFile(current.origin).path, null, SQLiteDatabase.OPEN_READONLY,
            ).use { db ->
                db.rawQuery("SELECT (SELECT count(*) FROM metadata)+(SELECT count(*) FROM points)", null).use { cursor ->
                    assertTrue(cursor.moveToFirst())
                    assertEquals(0, cursor.getInt(0))
                }
            }
            val work = WorkManager.getInstance(context)
            assertTrue(work.getWorkInfosForUniqueWork("native-location-periodic-${locationStorageId(previous.origin)}").get().isNotEmpty())
            assertTrue(work.getWorkInfosForUniqueWork("native-location-periodic-${locationStorageId(current.origin)}").get().isEmpty())
        }

        /** Compare persisted sealed rows, avoiding SQLite journal/checkpoint byte differences. */
        fun collectorSnapshot(): List<String> = SQLiteDatabase.openDatabase(
            collectorFile(previous.origin).path, null, SQLiteDatabase.OPEN_READONLY,
        ).use { db ->
            listOf("SELECT key,hex(sealed) FROM metadata ORDER BY key",
                "SELECT captured_at,hex(sealed) FROM points ORDER BY captured_at").flatMap { query ->
                db.rawQuery(query, null).use { cursor -> buildList {
                    while (cursor.moveToNext()) add("${cursor.getString(0)}:${cursor.getString(1)}")
                } }
            }
        }

        override fun close() {
            try { runBlocking { closeRuntime() } } finally {
                try {
                    NativeRuntimeFactory.testFactory = null
                    collector.disableLocally(namespace)
                    runBlocking {
                        secrets.delete("native", previous.origin)
                        secrets.delete("native", current.origin)
                        journal.clearAccount(namespace)
                    }
                    database.close()
                    AtomicFile(File(context.noBackupFilesDir, "native-origin-move.json")).delete()
                } finally {
                    AndroidNativeOriginsTestAccess.replace(previousCoordinator)
                }
            }
        }
    }

    private fun grantBackgroundLocation() {
        instrumentation.uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.ACCESS_COARSE_LOCATION)
        instrumentation.uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.ACCESS_FINE_LOCATION)
        if (Build.VERSION.SDK_INT >= 29) instrumentation.uiAutomation.grantRuntimePermission(
            context.packageName, Manifest.permission.ACCESS_BACKGROUND_LOCATION)
    }

    private fun collectorFile(origin: String) = File(context.noBackupFilesDir, "native-location-${locationStorageId(origin)}.sqlite")

    /** The location store and its work names use 16 digest bytes; session filenames use all 32. */
    private fun locationStorageId(origin: String) = digest(origin).take(32)

    private fun await(message: String, condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 10_000
        while (!condition() && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(10)
        assertTrue(message, condition())
    }

    /** Two independent listeners prove that a changed host/port affects every actual dispatch. */
    private class MoveServer(private val instanceId: String) : Closeable {
        private val listener = ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"))
        private val workers = Executors.newCachedThreadPool()
        private val sockets = CopyOnWriteArrayList<Socket>()
        private val closed = AtomicBoolean(false)
        val origin = "http://127.0.0.1:${listener.localPort}"
        val requests = CopyOnWriteArrayList<Request>()
        val failures = CopyOnWriteArrayList<String>()
        val configResponses = CopyOnWriteArrayList<Int>()
        @Volatile var configStatus = 200
        val identity = NativeIdentity("origin-move-user", "person", "Person", "ru", "Europe/Moscow", false, 1,
            "origin-move-device", "2099-11-15T12:00:00Z", listOf("session.control", "assistant.write"))

        init { workers.execute {
            while (!closed.get()) try {
                val socket = listener.accept()
                sockets += socket
                workers.execute { handle(socket) }
            } catch (failure: SocketException) { if (!closed.get()) failures += failure.javaClass.simpleName }
        } }

        private fun handle(socket: Socket) {
            try { socket.use {
                socket.soTimeout = 10_000
                val input = BufferedInputStream(socket.getInputStream())
                val start = line(input).split(' ')
                val headers = linkedMapOf<String, String>()
                while (true) {
                    val header = line(input)
                    if (header.isEmpty()) break
                    headers[header.substringBefore(':').lowercase()] = header.substringAfter(':').trim()
                }
                val bytes = ByteArray(headers["content-length"]?.toInt() ?: 0)
                var offset = 0
                while (offset < bytes.size) {
                    val count = input.read(bytes, offset, bytes.size - offset)
                    if (count < 0) throw EOFException()
                    offset += count
                }
                val request = Request(start[0], start[1].substringBefore('?'), headers, bytes.decodeToString())
                requests += request
                val output = socket.getOutputStream()
                if (request.path == "/v1/me/events") {
                    socket.soTimeout = 0
                    output.write("HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\nConnection: close\r\n\r\n: ready\n\n".encodeToByteArray())
                    output.flush()
                    while (!closed.get() && input.read() >= 0) Unit
                    return
                }
                val (status, body) = when {
                    request.path == "/v1/client/config" -> configStatus to
                        JSONObject().put("instance_id", instanceId).toString()
                    request.path == "/v1/me" -> 200 to NativeJson.encodeToString(NativeIdentity.serializer(), identity)
                    request.method == "POST" && request.path == "/v1/conversation/messages" -> 200 to JSONObject()
                        .put("submission_key", headers.getValue("idempotency-key"))
                        .put("message_id", "origin-move-message").put("admitted_at", "2026-10-21T12:00:00Z")
                        .put("state", "completed").toString()
                    request.path == NativeLocationCollector.POINTS -> 200 to JSONObject()
                        .put("accepted", JSONObject(request.body).getJSONArray("points").length()).put("duplicates", 0).toString()
                    else -> 404 to "{}"
                }
                val response = body.encodeToByteArray()
                output.write("HTTP/1.1 $status Fixture\r\nContent-Type: application/json\r\nContent-Length: ${response.size}\r\nConnection: close\r\n\r\n".encodeToByteArray())
                output.write(response); output.flush()
                if (request.path == "/v1/client/config") configResponses += status
            } } catch (failure: Exception) {
                if (!closed.get() && failure !is SocketException && failure !is EOFException)
                    failures += failure.javaClass.simpleName
            } finally { sockets -= socket }
        }

        override fun close() {
            closed.set(true)
            listener.close()
            sockets.forEach { runCatching { it.close() } }
            workers.shutdownNow()
        }

        private fun line(input: BufferedInputStream): String {
            val result = StringBuilder()
            while (result.length < 8_192) {
                val value = input.read()
                if (value < 0) throw EOFException()
                if (value == '\n'.code) return result.toString().removeSuffix("\r")
                result.append(value.toChar())
            }
            error("Synthetic HTTP header is too large")
        }
    }

    private data class Request(val method: String, val path: String, val headers: Map<String, String>, val body: String)

    private companion object {
        const val SESSION_TOKEN = "synthetic-origin-move-native-credential"
        fun digest(value: String) = MessageDigest.getInstance("SHA-256").digest(value.encodeToByteArray())
            .joinToString("") { "%02x".format(it) }
    }
}
