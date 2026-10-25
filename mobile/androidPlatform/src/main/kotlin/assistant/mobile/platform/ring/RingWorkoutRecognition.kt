package assistant.mobile.platform.ring

import assistant.mobile.platform.R

import android.Manifest
import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.net.Uri
import java.util.UUID
import androidx.core.content.ContextCompat
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.google.android.gms.location.ActivityRecognition
import com.google.android.gms.location.ActivityTransition
import com.google.android.gms.location.ActivityTransitionRequest
import com.google.android.gms.location.ActivityTransitionResult
import com.google.android.gms.location.DetectedActivity
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.put

/** An explicit private PendingIntent, never a WebView-provided transition or time. */
class RingWorkoutRecognition(private val context: Context, private val origin: String) {
    private data class Registration(val binding: RingWorkoutBinding, val intent: PendingIntent, val epoch: Long)
    private var requested: Registration? = null
    private var registered: Registration? = null
    private var epoch = 0L
    @Volatile private var failure: String? = null
    fun available(): Boolean = GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(context) == ConnectionResult.SUCCESS
    fun permissionGranted(): Boolean = Build.VERSION.SDK_INT < 29 ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACTIVITY_RECOGNITION) == PackageManager.PERMISSION_GRANTED
    @Synchronized fun state(): JsonObject = obj {
        put("available", available()); put("permission", if (permissionGranted()) "granted" else "required")
        put("registered", registered != null); failure?.let { put("error", it) }
    }
    private fun pending(binding: RingWorkoutBinding) = PendingIntent.getBroadcast(context, 0,
        Intent(context, RingRunningTransitionReceiver::class.java).setAction(ACTION).setPackage(context.packageName)
            .setData(Uri.parse("assistant-ring://activity/${binding.identity}/${UUID.randomUUID()}"))
            .putExtra("binding_identity", binding.identity).putExtra("binding_generation", binding.grantGeneration)
            .putExtra("user_id", binding.userId).putExtra("source_id", binding.sourceId).putExtra("origin", origin),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE)
    @SuppressLint("MissingPermission")
    @Synchronized fun register(binding: RingWorkoutBinding) {
        if (!permissionGranted() || !available() || registered?.binding == binding || requested?.binding == binding) return
        unregister()
        val registration = Registration(binding, pending(binding), ++epoch)
        requested = registration
        val transitions = listOf(ActivityTransition.ACTIVITY_TRANSITION_ENTER, ActivityTransition.ACTIVITY_TRANSITION_EXIT)
            .map { ActivityTransition.Builder().setActivityType(DetectedActivity.RUNNING).setActivityTransition(it).build() }
        try {
            ActivityRecognition.getClient(context).requestActivityTransitionUpdates(ActivityTransitionRequest(transitions), registration.intent)
                .addOnSuccessListener { synchronized(this) {
                    if (epoch == registration.epoch && requested === registration) {
                        registered = registration; requested = null; failure = null
                    } else remove(registration)
                } }
                .addOnFailureListener { synchronized(this) {
                    if (epoch == registration.epoch && requested === registration) {
                        requested = null; registered = null; failure = "registration_failed"
                    }
                    registration.intent.cancel()
                } }
        } catch (_: SecurityException) {
            requested = null; registered = null; failure = "permission_denied"; registration.intent.cancel()
        } catch (_: RuntimeException) {
            requested = null; registered = null; failure = "registration_failed"; registration.intent.cancel()
        }
    }
    private fun remove(registration: Registration) {
        try { ActivityRecognition.getClient(context).removeActivityTransitionUpdates(registration.intent) }
        catch (_: SecurityException) { /* Permission may be revoked after the registration; manual control remains available. */ }
        catch (_: RuntimeException) { /* Play services may become unavailable; invalidate this private intent anyway. */ }
        registration.intent.cancel()
    }
    @Synchronized fun unregister() {
        ++epoch
        val previous = listOfNotNull(requested, registered).distinct()
        requested = null; registered = null
        previous.forEach(::remove)
    }
    companion object { const val ACTION = "assistant.ring.RUNNING_TRANSITIONS" }
}

class RingRunningTransitionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != RingWorkoutRecognition.ACTION) return
        val origin = RingRepository.configuredOrigin(context) ?: return
        if (intent.getStringExtra("origin") != origin) {
            PendingIntent.getBroadcast(context, 0, intent, PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_MUTABLE)?.let {
                try { ActivityRecognition.getClient(context).removeActivityTransitionUpdates(it) }
                catch (_: RuntimeException) { /* Cancelling the private intent still fences stale delivery. */ }
                it.cancel()
            }
            return
        }
        if (!ActivityTransitionResult.hasResult(intent)) return
        val result = ActivityTransitionResult.extractResult(intent) ?: return
        val events = result.transitionEvents.filter { it.activityType == DetectedActivity.RUNNING }.mapNotNull {
            val kind = when (it.transitionType) {
                ActivityTransition.ACTIVITY_TRANSITION_ENTER -> RingRunningTransition.Kind.ENTER
                ActivityTransition.ACTIVITY_TRANSITION_EXIT -> RingRunningTransition.Kind.EXIT
                else -> return@mapNotNull null
            }
            RingRunningTransition(kind, it.elapsedRealTimeNanos / 1_000_000L, it.elapsedRealTimeNanos)
        }
        if (events.isEmpty()) return
        RingRepository.get(context.applicationContext, origin).runningTransitions(events,
            intent.getStringExtra("binding_identity"), intent.getStringExtra("user_id"),
            intent.getStringExtra("source_id"), intent.getLongExtra("binding_generation", -1))
    }
}

/** Startup recovery persists interruption; it does not re-send a start or an uncertain stop. */
class RingWorkoutBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action !in setOf(Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED)) return
        val origin = RingRepository.configuredOrigin(context) ?: return
        RingRepository.get(context.applicationContext, origin).recoverWorkout()
    }
}
