package assistant.mobile.platform.ring

import assistant.mobile.platform.R

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import java.lang.ref.WeakReference
import kotlinx.coroutines.*

/** An already confirmed updater may finish after the screen leaves the foreground. */
class RingOtaService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var runtime: RingOtaRuntime? = null
    private var operation: String? = null
    private var wake: PowerManager.WakeLock? = null
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        RingText.initialize(this)
        val id = intent?.getStringExtra(OPERATION)
        val selected = id?.let(RingOtaServiceOwner::get)
        if (id == null || selected == null) { stopSelf(); return START_NOT_STICKY }
        runtime = selected; operation = id
        val manager = getSystemService(NotificationManager::class.java)
        try {
            manager.createNotificationChannel(NotificationChannel(CHANNEL, RingText.text(R.string.ring_ota_d67586d382c3), NotificationManager.IMPORTANCE_LOW))
            val initial = notification(0, 0)
            if (Build.VERSION.SDK_INT >= 29) startForeground(NOTIFICATION, initial, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
            else startForeground(NOTIFICATION, initial)
            wake = (getSystemService(POWER_SERVICE) as PowerManager).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "assistant:ring-ota")
                .apply { acquire(30 * 60_000L) }
            RingOtaServiceOwner.started(id)
        } catch (_: RuntimeException) { RingOtaServiceOwner.failed(id); stopSelf(); return START_NOT_STICKY }
        scope.launch {
            var lastPublished = 0L
            selected.states.collect { state ->
                if (state.phase !in setOf("starting", "initializing", "transferring", "checking", "finalizing", "verifying")) {
                    stopForeground(STOP_FOREGROUND_REMOVE); stopSelf(); return@collect
                }
                val now = SystemClock.elapsedRealtime()
                if (now - lastPublished >= 1_000) {
                    lastPublished = now
                    manager.notify(NOTIFICATION, notification(state.progressBytes, state.totalBytes))
                }
            }
        }
        return START_NOT_STICKY // Restart restores UNKNOWN in the journal; it never starts another flash.
    }
    private fun notification(progress: Int, total: Int): Notification = Notification.Builder(this, CHANNEL)
        .setSmallIcon(android.R.drawable.stat_sys_upload).setContentTitle(RingText.text(R.string.ring_ota_cc2a4ef83cba))
        .setContentText(RingText.text(R.string.ring_ota_221eee2ffc5d)).setOngoing(true).setOnlyAlertOnce(true)
        .setProgress(total, progress, total == 0).build()
    override fun onDestroy() {
        operation?.let { id -> runtime?.serviceStopped(id); RingOtaServiceOwner.detach(id) }
        wake?.let { if (it.isHeld) it.release() }; wake = null
        scope.cancel(); super.onDestroy()
    }
    companion object {
        const val OPERATION = "operation_id"
        private const val CHANNEL = "assistant_ring_ota"
        private const val NOTIFICATION = 4383
    }
}

object RingOtaServiceOwner {
    private data class Entry(val runtime: WeakReference<RingOtaRuntime>, val ready: CompletableDeferred<Boolean>)
    private val owners = mutableMapOf<String, Entry>()
    @Synchronized fun attach(id: String, runtime: RingOtaRuntime) { owners[id] = Entry(WeakReference(runtime), CompletableDeferred()) }
    @Synchronized fun get(id: String): RingOtaRuntime? = owners[id]?.runtime?.get()
    @Synchronized fun readiness(id: String): CompletableDeferred<Boolean>? = owners[id]?.ready
    @Synchronized fun started(id: String) { owners[id]?.ready?.complete(true) }
    @Synchronized fun failed(id: String) { owners[id]?.ready?.complete(false) }
    @Synchronized fun detach(id: String) { owners.remove(id) }
}
