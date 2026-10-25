package assistant.mobile.android.ambient

import android.app.ActivityManager
import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import android.os.Parcel
import android.os.Process
import android.util.AtomicFile
import assistant.mobile.core.AccountNamespace
import assistant.mobile.core.NativeIdentity
import assistant.mobile.core.SessionLease
import java.io.File
import java.io.IOException
import java.time.Instant
import java.util.Base64
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import org.json.JSONObject

/** Debug APK only. The instrumentation process owns the synthetic server while this process dies. */
class AmbientProcessRecoveryProbe : Service() {
    private val started = AtomicBoolean()

    override fun onBind(intent: Intent): IBinder {
        val runId = requireNotNull(intent.getStringExtra("run_id"))
        require(UUID.fromString(runId).toString() == runId)
        val phase = requireNotNull(intent.getStringExtra("phase"))
        require(phase in setOf("uncertain", "recover"))
        val parentPid = intent.getIntExtra("parent_pid", -1)
        return object : Binder() {
            override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
                if (code != IBinder.FIRST_CALL_TRANSACTION) return super.onTransact(code, data, reply, flags)
                check(Binder.getCallingUid() == Process.myUid())
                if (started.compareAndSet(false, true)) Thread({ execute(runId, phase, parentPid) }, "ambient-recovery-probe").start()
                reply?.writeNoException()
                return true
            }
        }
    }

    private fun execute(runId: String, phase: String, parentPid: Int) {
        val directory = File(cacheDir, "ambient-process-smoke/$runId")
        val evidence = JSONObject().put("pid", Process.myPid()).put("phase", "failed")
        try {
            check(Process.myPid() != parentPid && parentPid > 0)
            val process = (getSystemService(ACTIVITY_SERVICE) as ActivityManager).runningAppProcesses
                .single { it.pid == Process.myPid() }
            check(process.processName == "$packageName:ambientRecoverySmoke")
            val config = JSONObject(File(directory, "config.json").readText())
            val origin = config.getString("origin")
            check(AmbientContract.validOrigin(origin) && java.net.URI(origin).host == "127.0.0.1")
            val binding = AmbientBinding(origin, config.getString("user_id"), config.getString("device_id"),
                config.getString("expires_at"))
            val lease = SessionLease(AccountNamespace("ambient-process-smoke", origin, binding.userId), 1,
                config.getString("credential"), runId)
            val identity = NativeIdentity(binding.userId, "synthetic", "Synthetic person", "ru", "Europe/Moscow",
                false, 1, binding.deviceId, binding.expiresAt)
            val port = object : AmbientSessionPort {
                override val origin = binding.origin
                override fun lease(): SessionLease = lease
                override fun identity(): NativeIdentity = identity
                override fun isCurrent(candidate: SessionLease): Boolean = candidate === lease
                override fun expire(lease: SessionLease) { error("Synthetic session unexpectedly expired") }
            }
            val queue = AmbientQueue(File(directory, "queue"))
            val before = queue.list()
            val batch = if (phase == "uncertain") {
                check(before.isEmpty())
                val head = Base64.getDecoder().decode("T3B1c0hlYWQBATgBgD4AAAAAAA==")
                val packet = Base64.getDecoder().decode("uP/+")
                queue.enqueueEncoded(binding, runId, 0, Instant.now().toString(), 320,
                    AmbientAudioFormat.OGG_OPUS, AmbientOgg.encode(head, listOf(packet, packet), 320))
            } else before.single()
            evidence.put("batch_id", batch.id).put("sha256", batch.sha256).put("before_count", before.size)
            val client = AmbientHttp(port) { true }
            if (phase == "uncertain") {
                try {
                    client.upload(batch, queue.audioFile(batch), lease)
                    error("The fixture must lose the first admitted upload response")
                } catch (failure: IOException) {
                    check(failure !is AmbientProblem) { "The synthetic upload must fail at the transport" }
                    check(queue.list() == listOf(batch))
                    evidence.put("phase", "uncertain").put("pending_count", queue.snapshot().count)
                }
            } else {
                val receipt = client.upload(batch, queue.audioFile(batch), lease)
                check(queue.acknowledge(receipt.id, receipt.sha256))
                check(!queue.acknowledge(receipt.id, receipt.sha256))
                evidence.put("phase", "acknowledged").put("pending_count", queue.snapshot().count)
            }
        } catch (failure: Throwable) {
            evidence.put("error", failure.javaClass.simpleName).put("detail", failure.message?.take(300))
        }
        val output = AtomicFile(File(directory, "evidence.json"))
        val stream = output.startWrite()
        try {
            stream.write(evidence.toString().toByteArray())
            output.finishWrite(stream)
        } catch (failure: Throwable) {
            output.failWrite(stream)
            throw failure
        }
        // This Service exists only in the debug manifest and always lives in its dedicated process.
        // Killing it cannot terminate the instrumentation process or its synthetic server.
        if (Process.myPid() != parentPid) Process.killProcess(Process.myPid())
    }
}
