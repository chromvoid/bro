package assistant.mobile.platform.ring

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.net.Uri
import androidx.core.app.ServiceCompat
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*
import assistant.mobile.platform.R

/** Eligible HR/GPS capture stays alive after WebView destruction; Android never resumes it blindly. */
class RingWorkoutService : Service() {
    private var session: String? = null
    private lateinit var repository: RingRepository
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var wakeLock: PowerManager.WakeLock? = null
    private var renewal: Job? = null
    private var ticker: Job? = null
    private var activeMode: RingWorkoutForegroundMode? = null
    private var locationBlocked = false
    private var bluetoothBlocked = false
    override fun onCreate() {
        super.onCreate()
        RingText.initialize(this)
        val origin = RingRepository.configuredOrigin(this) ?: return
        repository = RingRepository.get(applicationContext, origin)
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, RingText.text(R.string.ring_workout_eeaa1c1f500b), NotificationManager.IMPORTANCE_LOW))
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!::repository.isInitialized) { stopSelf(); return START_NOT_STICKY }
        if (intent?.action == STOP) {
            repository.stopWorkoutFromNotification(intent.getStringExtra(SESSION))
            return START_NOT_STICKY
        }
        val requested = intent?.getStringExtra(SESSION)
        if (requested == null || !repository.isWorkoutSession(requested)) { stopSelf(); return START_NOT_STICKY }
        try {
            session = requested
            locationBlocked = false; bluetoothBlocked = false
            if (!promote(requested)) { stopSelf(); return START_NOT_STICKY }
            if (wakeLock == null) {
                wakeLock = getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "assistant:ring-workout")
                    .apply { setReferenceCounted(false); acquire(300_000) }
                renewal = scope.launch { while (isActive) { delay(240_000); wakeLock?.acquire(300_000) } }
            }
            if (repository.needsHeartRateRecorder(requested)) repository.recordWorkout(requested) { }
            if (ticker?.isActive != true) ticker = scope.launch {
                while (isActive) {
                    try {
                        withContext(Dispatchers.IO) { repository.workoutTick() }
                        val current = repository.currentWorkoutSession()
                        if (current == null) { stopSelf(); break }
                        if (session != current) {
                            session = current; activeMode = null; locationBlocked = false; bluetoothBlocked = false
                            if (repository.needsHeartRateRecorder(current)) repository.recordWorkout(current) { }
                        }
                        if (!promote(current)) { stopSelf(); break }
                    } catch (_: RuntimeException) {
                        session?.let(repository::workoutServiceFailed); stopSelf(); break
                    }
                    delay(1_000)
                }
            }
        } catch (_: RuntimeException) {
            repository.workoutServiceFailed(requested)
            stopSelf()
        }
        return START_NOT_STICKY
    }
    private fun promote(id: String): Boolean {
        val notifications = getSystemService(NotificationManager::class.java)
        val visible = notifications.areNotificationsEnabled() &&
            notifications.getNotificationChannel(CHANNEL)?.importance != NotificationManager.IMPORTANCE_NONE &&
            (Build.VERSION.SDK_INT < 33 || checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED)
        if (!visible) { repository.workoutServiceFailed(id); return false }
        val selection = RingWorkoutForegroundPolicy.promote(activeMode, {
            RingWorkoutForegroundResources(!bluetoothBlocked && repository.needsHeartRateRecorder(id),
                repository.workoutBluetoothReady(), !locationBlocked && repository.workoutLocationReady())
        }) { mode ->
            val type = if (Build.VERSION.SDK_INT >= 29)
                (if (mode.bluetooth) ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE else 0) or
                    (if (mode.location) ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION else 0) else 0
            ServiceCompat.startForeground(this, NOTIFICATION, notification(id), type)
        } ?: return false
        activeMode = selection.mode
        // A rejected Bluetooth type must never mark GPS blocked. A rejected GPS type must never stop HR.
        if (selection.bluetoothRejected) bluetoothBlocked = true
        if (selection.locationRejected) {
            locationBlocked = true
            if (repository.workoutLocationReady()) repository.workoutLocationFailed("background_limited")
        }
        if (selection.mode.location) repository.startWorkoutLocation(id)
        return true
    }
    private fun notification(id: String): Notification {
        val stop = PendingIntent.getService(this, 1, Intent(this, RingWorkoutService::class.java)
            .setAction(STOP).setData(Uri.parse("assistant-ring://workout/$id/stop")).putExtra(SESSION, id), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        // The home URI is the PendingIntent identity; the native app accepts it after a server move.
        val open = PendingIntent.getActivity(this, 2, (packageManager.getLaunchIntentForPackage(packageName) ?: Intent(Intent.ACTION_MAIN).setPackage(packageName))
            .setAction(Intent.ACTION_VIEW).setData(Uri.parse("${RingRepository.configuredOrigin(this)}/activity")), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, CHANNEL).setSmallIcon(applicationInfo.icon)
            .setContentTitle(RingText.text(R.string.ring_workout_eeaa1c1f500b)).setContentText(RingText.text(R.string.ring_workout_51033fc071c3))
            .setContentIntent(open).setOngoing(true).setCategory(NotificationCompat.CATEGORY_WORKOUT)
            .addAction(Notification.Action.Builder(null, RingText.text(R.string.ring_workout_8be2bbf39a83), stop).build()).build()
    }
    override fun onDestroy() {
        if (::repository.isInitialized) session?.let(repository::workoutServiceDestroyed)
        ticker?.cancel(); renewal?.cancel(); scope.cancel()
        wakeLock?.let { if (it.isHeld) it.release() }; wakeLock = null
        super.onDestroy()
    }
    override fun onBind(intent: Intent?): IBinder? = null
    companion object {
        const val START = "assistant.ring.WORKOUT_START"
        const val STOP = "assistant.ring.WORKOUT_STOP"
        const val SESSION = "workout_session"
        private const val CHANNEL = "ring-workout"
        private const val NOTIFICATION = 9107
    }
}
