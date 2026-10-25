package assistant.mobile.platform.location

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.location.Location
import android.location.LocationManager
import android.os.Build
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** A replaceable OS boundary; the recording policy itself remains in NativeLocationCollector. */
interface LocationUpdateRegistration {
    fun start()
    fun stop()
}

class AndroidLocationUpdateRegistration(context: Context, private val origin: String) : LocationUpdateRegistration {
    private val context = context.applicationContext
    @Volatile private var active = false

    @SuppressLint("MissingPermission")
    override fun start() {
        active = true
        val intent = requireNotNull(pendingIntent())
        try {
            val request = LocationRequest.Builder(Priority.PRIORITY_BALANCED_POWER_ACCURACY, INTERVAL_MS)
                .setMinUpdateDistanceMeters(100f)
                .setMaxUpdateDelayMillis(BATCH_MS)
                .build()
            LocationServices.getFusedLocationProviderClient(context).requestLocationUpdates(request, intent)
                .addOnFailureListener { if (active) fallback(intent) }
        } catch (_: Exception) { fallback(intent) }
    }

    @SuppressLint("MissingPermission")
    private fun fallback(intent: PendingIntent) {
        if (!active) return
        val manager = context.getSystemService(LocationManager::class.java) ?: return
        val provider = listOf(LocationManager.NETWORK_PROVIDER, LocationManager.GPS_PROVIDER)
            .firstOrNull { runCatching { manager.isProviderEnabled(it) }.getOrDefault(false) } ?: return
        runCatching { manager.requestLocationUpdates(provider, INTERVAL_MS, 100f, intent) }
    }

    override fun stop() {
        active = false
        val intent = pendingIntent(create = false) ?: return
        runCatching { LocationServices.getFusedLocationProviderClient(context).removeLocationUpdates(intent) }
        runCatching { context.getSystemService(LocationManager::class.java)?.removeUpdates(intent) }
        intent.cancel()
    }

    private fun pendingIntent(create: Boolean = true): PendingIntent? = PendingIntent.getBroadcast(
        context, locationDigest(origin).hashCode(),
        Intent(context, NativeLocationUpdatesReceiver::class.java)
            .setData(android.net.Uri.parse("native-location://updates/${locationDigest(origin)}"))
            .putExtra(NativeLocationCollector.INPUT_ORIGIN, origin),
        (if (create) PendingIntent.FLAG_UPDATE_CURRENT else PendingIntent.FLAG_NO_CREATE) or
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0,
    )

    private companion object {
        const val INTERVAL_MS = 10 * 60_000L
        const val BATCH_MS = 30 * 60_000L
    }
}

class NativeLocationUpdatesReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val origin = intent.getStringExtra(NativeLocationCollector.INPUT_ORIGIN) ?: return
        if (NativeLocationCollector.cancelIfForeignOrigin(context, origin)) return
        val locations = LocationResult.extractResult(intent)?.locations ?: listOfNotNull(
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
                intent.getParcelableExtra(LocationManager.KEY_LOCATION_CHANGED, Location::class.java)
            else @Suppress("DEPRECATION") intent.getParcelableExtra(LocationManager.KEY_LOCATION_CHANGED),
        )
        val pending = goAsync()
        receiverScope.launch {
            try { NativeLocationCollector.get(context, origin).received(locations) }
            finally { pending?.finish() }
        }
    }
}

class NativeLocationBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action !in setOf(Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED)) return
        val pending = goAsync()
        receiverScope.launch {
            try { NativeLocationCollector.installed(context).forEach { it.resume() } }
            finally { pending?.finish() }
        }
    }
}

class NativeLocationUploadWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        val origin = inputData.getString(NativeLocationCollector.INPUT_ORIGIN) ?: return Result.failure()
        if (NativeLocationCollector.cancelIfForeignOrigin(applicationContext, origin)) return Result.success()
        return if (NativeLocationCollector.get(applicationContext, origin).upload()) Result.success() else Result.retry()
    }
}

private val receiverScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

/** Retires the OS registration without importing or erasing the old parent-bound location grant. */
object LegacyLocationRegistration {
    fun close(context: Context) {
        val intent = Intent().setComponent(android.content.ComponentName(context.packageName,
            "assistant.android.location.LocationUpdatesReceiver"))
        val pending = PendingIntent.getBroadcast(context, 0, intent, PendingIntent.FLAG_NO_CREATE or
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0) ?: return
        runCatching { LocationServices.getFusedLocationProviderClient(context).removeLocationUpdates(pending) }
        runCatching { context.getSystemService(LocationManager::class.java)?.removeUpdates(pending) }
        pending.cancel()
    }
}
