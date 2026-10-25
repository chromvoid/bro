package assistant.mobile.android.ambient

import android.app.ActivityManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.os.Parcel
import android.os.Process
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import assistant.mobile.android.NativeAmbientFixture
import assistant.mobile.android.NativeLoopbackServer
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** The uploader really dies between admission and ACK; the loopback archive stays in this process. */
@RunWith(AndroidJUnit4::class)
class AmbientProcessRecoverySmokeTest {
    @Test
    fun killedUploaderRestoresUncertainBatchAndExactAckDoesNotDuplicateTheArchive() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val runId = UUID.randomUUID().toString()
        val directory = File(context.cacheDir, "ambient-process-smoke/$runId")
        check(directory.mkdirs())
        NativeLoopbackServer().use { server ->
            server.ambientEnabled = true
            server.ambient.disconnectUploadAfterAdmission = true
            File(directory, "config.json").writeText(JSONObject()
                .put("origin", server.origin)
                .put("user_id", NativeAmbientFixture.USER_A)
                .put("device_id", NativeAmbientFixture.DEVICE_ID)
                .put("expires_at", server.ambient.expiresAt)
                .put("credential", NativeLoopbackServer.credential(NativeAmbientFixture.USER_A)).toString())
            try {
                val interrupted = runProcess(context, directory, runId, "uncertain", "uncertain")
                val firstPid = interrupted.getInt("pid")
                val id = interrupted.getString("batch_id")
                val sha256 = interrupted.getString("sha256")
                assertNotEquals(Process.myPid(), firstPid)
                assertEquals(0, interrupted.getInt("before_count"))
                assertEquals(1, interrupted.getInt("pending_count"))
                val durable = AmbientQueue(File(directory, "queue"))
                assertEquals(id, durable.list().single().id)
                assertEquals(sha256, durable.list().single().sha256)
                assertEquals(1, server.ambient.count())
                assertTrue(server.ambient.contains(id))
                assertEquals(listOf(id), server.ambient.uploadIds.toList())

                val recovered = runProcess(context, directory, runId, "recover", "acknowledged")
                assertNotEquals("Recovery must execute in a fresh Android process", firstPid, recovered.getInt("pid"))
                assertNotEquals(Process.myPid(), recovered.getInt("pid"))
                assertEquals(id, recovered.getString("batch_id"))
                assertEquals(sha256, recovered.getString("sha256"))
                assertEquals(1, recovered.getInt("before_count"))
                assertEquals(0, recovered.getInt("pending_count"))
                assertTrue(AmbientQueue(File(directory, "queue")).list().isEmpty())
                assertEquals(listOf(id, id), server.ambient.uploadIds.toList())
                assertEquals("Retry of the same admitted ID cannot create another archive entry", 1, server.ambient.count())
                assertTrue(server.ambient.contains(id))
                assertTrue(server.failures.toString(), server.failures.isEmpty())
            } finally {
                directory.deleteRecursively()
            }
        }
    }

    private fun runProcess(context: Context, directory: File, runId: String, phase: String, expected: String): JSONObject {
        val disconnected = CountDownLatch(1)
        val sent = AtomicBoolean()
        val binderFailure = AtomicReference<Throwable>()
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, binder: IBinder) {
                // Android may recreate a bound service after death. Only this explicit transaction
                // runs a probe; reconnecting alone never uploads another batch.
                if (!sent.compareAndSet(false, true)) return
                val request = Parcel.obtain()
                val reply = Parcel.obtain()
                try {
                    check(binder.transact(IBinder.FIRST_CALL_TRANSACTION, request, reply, 0))
                    reply.readException()
                } catch (failure: Throwable) {
                    binderFailure.set(failure)
                } finally {
                    request.recycle()
                    reply.recycle()
                }
            }
            override fun onServiceDisconnected(name: ComponentName) { disconnected.countDown() }
            override fun onBindingDied(name: ComponentName) { disconnected.countDown() }
            override fun onNullBinding(name: ComponentName) { binderFailure.set(IllegalStateException("Null probe binding")) }
        }
        val intent = Intent(context, AmbientProcessRecoveryProbe::class.java)
            .setAction("assistant.mobile.ambient.PROCESS_RECOVERY.$runId.$phase")
            .putExtra("run_id", runId).putExtra("phase", phase).putExtra("parent_pid", Process.myPid())
        check(context.bindService(intent, connection, Context.BIND_AUTO_CREATE))
        try {
            val deadline = SystemClock.elapsedRealtime() + 45_000
            var result: JSONObject? = null
            while (SystemClock.elapsedRealtime() < deadline) {
                val evidence = runCatching { JSONObject(File(directory, "evidence.json").readText()) }.getOrNull()
                if (evidence?.optString("phase") == "failed") throw AssertionError("Recovery probe failed: $evidence")
                if (evidence?.optString("phase") == expected) { result = evidence; break }
                SystemClock.sleep(25)
            }
            val evidence = result ?: throw AssertionError("No $expected evidence from probe; Binder=${binderFailure.get()}")
            assertTrue("The separate uploader must actually die", disconnected.await(10, TimeUnit.SECONDS))
            val pid = evidence.getInt("pid")
            val activity = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val deathDeadline = SystemClock.elapsedRealtime() + 5_000
            while (activity.runningAppProcesses.any { it.pid == pid } && SystemClock.elapsedRealtime() < deathDeadline)
                SystemClock.sleep(25)
            assertFalse("The recorded uploader PID must be absent after Binder death",
                activity.runningAppProcesses.any { it.pid == pid })
            return evidence
        } finally {
            context.unbindService(connection)
        }
    }
}
