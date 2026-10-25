package assistant.mobile.android

import android.Manifest
import android.app.ActivityManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Build
import android.os.IBinder
import android.os.Parcel
import android.os.Process
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import assistant.mobile.android.whoop.WhoopStore
import assistant.mobile.core.NativeServerCheckResult
import assistant.mobile.platform.AndroidNativeOrigins
import assistant.mobile.platform.ring.RingStore
import java.io.File
import java.util.UUID
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

/** Real process death, MainActivity, Keystore, Room and WorkManager; every stored record is synthetic. */
@RunWith(AndroidJUnit4::class)
class NativeServerSwitchSmokeTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    @Test fun serverSwitchErasesExpiredOwnersAndRestartsOnCustomThenDefaultOrigin() {
        grantLocation()
        val runId = UUID.randomUUID().toString()
        val directory = NativeServerSwitchSmokeFixture.directory(context, runId).apply { check(mkdirs()) }
        val selection = File(context.noBackupFilesDir, "native-server.json")
        val original = selection.takeIf(File::exists)?.readBytes()
        val defaultOrigin = requireNotNull(context.packageManager.getApplicationInfo(context.packageName,
            PackageManager.GET_META_DATA).metaData.getString("assistant.origin"))
        val instance = UUID.randomUUID().toString()
        NativeServerSwitchSmokeFixture.write(File(directory, "config.json"), JSONObject()
            .put("default_origin", defaultOrigin).put("custom_origin", "https://switch-$runId.invalid")
            .put("instance_id", instance))
        AndroidNativeOrigins.select(context,
            NativeServerCheckResult.Compatible(defaultOrigin, instance, "Synthetic default server"))
        try {
            val custom = runProcess(runId, directory, "to_custom")
            assertNotEquals(Process.myPid(), custom.getInt("pid"))
            assertEquals("https://switch-$runId.invalid", custom.getString("home"))
            assertTrue(custom.getBoolean("main_activity_composed"))
            assertTrue(custom.getBoolean("private_stores_readable"))

            val restored = runProcess(runId, directory, "to_default")
            assertNotEquals(custom.getInt("pid"), restored.getInt("pid"))
            assertNotEquals(Process.myPid(), restored.getInt("pid"))
            assertEquals(defaultOrigin, restored.getString("home"))
            assertTrue(restored.getBoolean("main_activity_composed"))
            assertTrue(restored.getBoolean("private_stores_readable"))
        } finally {
            stopProcess(runId)
            RingStore(context, defaultOrigin).use { it.clearForServerSwitch() }
            WhoopStore(context, defaultOrigin).use { it.clearForServerSwitch() }
            if (original == null) selection.delete() else selection.writeBytes(original)
            File(context.cacheDir, "native-server-switch-smoke/active.json").delete()
            directory.deleteRecursively()
        }
    }

    private fun runProcess(runId: String, directory: File, phase: String): JSONObject {
        val sent = AtomicBoolean()
        val binderFailure = AtomicReference<Throwable>()
        val connection = connection(sent, binderFailure)
        check(context.bindService(probe(runId, phase), connection, Context.BIND_AUTO_CREATE))
        try {
            val deadline = SystemClock.elapsedRealtime() + TimeUnit.SECONDS.toMillis(60)
            while (SystemClock.elapsedRealtime() < deadline) {
                val failure = File(directory, "$phase-failed.json")
                if (failure.exists()) throw AssertionError("Server switch probe failed: ${failure.readText()}")
                val evidence = runCatching { JSONObject(File(directory, "$phase-restarted.json").readText()) }.getOrNull()
                if (evidence != null) {
                    val seeded = JSONObject(File(directory, "$phase-seeded.json").readText())
                    assertNotEquals(seeded.getInt("pid"), evidence.getInt("pid"))
                    val activity = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
                    assertFalse("The old application PID must be gone",
                        activity.runningAppProcesses.any { it.pid == seeded.getInt("pid") })
                    return evidence
                }
                SystemClock.sleep(25)
            }
            throw AssertionError("No restarted $phase evidence; Binder=${binderFailure.get()}")
        } finally { context.unbindService(connection) }
    }

    private fun stopProcess(runId: String) {
        val bound = AtomicBoolean(true)
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, binder: IBinder) {
                // Remove AUTO_CREATE before the deliberate death, so Android cannot restart it.
                if (!bound.compareAndSet(true, false)) return
                context.unbindService(this)
                val request = Parcel.obtain()
                val response = Parcel.obtain()
                try { binder.transact(IBinder.FIRST_CALL_TRANSACTION, request, response, 0) }
                catch (_: android.os.DeadObjectException) { }
                finally { request.recycle(); response.recycle() }
            }
            override fun onServiceDisconnected(name: ComponentName) = Unit
        }
        if (!context.bindService(probe(runId, "stop"), connection, Context.BIND_AUTO_CREATE)) return
        try {
            val activity = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val deadline = SystemClock.elapsedRealtime() + 5_000
            while ((bound.get() || activity.runningAppProcesses.any { it.processName == context.packageName + ":serverSwitchSmoke" }) &&
                SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(25)
            assertFalse("The smoke process must stop before its storage is cleaned",
                activity.runningAppProcesses.any { it.processName == context.packageName + ":serverSwitchSmoke" })
        } finally { if (bound.compareAndSet(true, false)) context.unbindService(connection) }
    }

    private fun connection(sent: AtomicBoolean, failure: AtomicReference<Throwable>) = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            if (!sent.compareAndSet(false, true)) return
            val request = Parcel.obtain()
            val response = Parcel.obtain()
            try {
                check(binder.transact(IBinder.FIRST_CALL_TRANSACTION, request, response, 0))
                response.readException()
            } catch (error: Throwable) { failure.set(error) }
            finally { request.recycle(); response.recycle() }
        }
        override fun onServiceDisconnected(name: ComponentName) = Unit
    }

    private fun probe(runId: String, phase: String) = Intent(context, NativeServerSwitchSmokeProbe::class.java)
        .setAction("assistant.mobile.SERVER_SWITCH.$runId.$phase").putExtra("run_id", runId).putExtra("phase", phase)

    private fun grantLocation() {
        instrumentation.uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.ACCESS_COARSE_LOCATION)
        instrumentation.uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.ACCESS_FINE_LOCATION)
        if (Build.VERSION.SDK_INT >= 29)
            instrumentation.uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.ACCESS_BACKGROUND_LOCATION)
    }
}
