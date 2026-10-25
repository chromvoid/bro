package assistant.mobile.platform.location

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import assistant.mobile.core.location.LocationFix
import assistant.mobile.core.location.PositionPermission
import assistant.mobile.core.location.PositionPrecision
import assistant.mobile.core.location.PositionSource
import assistant.mobile.core.location.PositionStatus
import com.google.android.gms.location.CurrentLocationRequest
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import java.time.Instant
import kotlin.coroutines.resume
import kotlin.math.ceil
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull

/** Permission dialogs belong exclusively to [request], invoked by the person's button. */
class AndroidPositionSource(
    context: Context,
    private val requestPermission: suspend () -> Boolean,
    private val rationale: () -> Boolean = { false },
    private val provider: CurrentPositionProvider = AndroidCurrentPositionProvider(context),
) : PositionSource {
    private val context = context.applicationContext
    private val preferences = this.context.getSharedPreferences("native-location-permission", Context.MODE_PRIVATE)

    override suspend fun status(): PositionStatus {
        val fine = granted(Manifest.permission.ACCESS_FINE_LOCATION)
        val coarse = granted(Manifest.permission.ACCESS_COARSE_LOCATION)
        return when {
            fine -> PositionStatus(PositionPermission.GRANTED, PositionPrecision.PRECISE)
            coarse -> PositionStatus(PositionPermission.GRANTED, PositionPrecision.APPROXIMATE)
            preferences.getBoolean("asked", false) && !rationale() -> PositionStatus(PositionPermission.DENIED)
            else -> PositionStatus(PositionPermission.PROMPT)
        }
    }

    override suspend fun request(): PositionStatus {
        if (status().permission == PositionPermission.GRANTED) return status()
        preferences.edit().putBoolean("asked", true).commit()
        requestPermission()
        return status()
    }

    override suspend fun current(): LocationFix? {
        if (status().permission != PositionPermission.GRANTED) return null
        return provider.current()?.takeIf(::validLocation)?.let(::nativeLocationFix)
    }

    private fun granted(permission: String) = context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
}

/** Injectable Android boundary; instrumentation supplies synthetic positions without family data. */
fun interface CurrentPositionProvider { suspend fun current(): Location? }

class AndroidCurrentPositionProvider(context: Context) : CurrentPositionProvider {
    private val context = context.applicationContext

    @SuppressLint("MissingPermission") // PositionSource checks both foreground permissions immediately before use.
    override suspend fun current(): Location? {
        val fused = withTimeoutOrNull(TIMEOUT_MS + 1_000) {
            suspendCancellableCoroutine<Location?> { continuation ->
                val cancellation = CancellationTokenSource()
                continuation.invokeOnCancellation { cancellation.cancel() }
                try {
                    val request = CurrentLocationRequest.Builder()
                        .setPriority(Priority.PRIORITY_BALANCED_POWER_ACCURACY)
                        .setMaxUpdateAgeMillis(MAX_AGE_MS)
                        .setDurationMillis(TIMEOUT_MS)
                        .build()
                    LocationServices.getFusedLocationProviderClient(context)
                        .getCurrentLocation(request, cancellation.token)
                        .addOnSuccessListener { location -> if (continuation.isActive) continuation.resume(location) }
                        .addOnFailureListener { if (continuation.isActive) continuation.resume(null) }
                } catch (_: Exception) {
                    if (continuation.isActive) continuation.resume(null)
                }
            }
        }
        return fused?.takeIf { validLocation(it) && System.currentTimeMillis() - it.time <= MAX_AGE_MS }
            ?: lastKnown()
    }

    @SuppressLint("MissingPermission")
    private fun lastKnown(): Location? {
        val manager = context.getSystemService(LocationManager::class.java) ?: return null
        return runCatching { manager.getProviders(true) }.getOrDefault(emptyList())
            .mapNotNull { runCatching { manager.getLastKnownLocation(it) }.getOrNull() }
            .filter { validLocation(it) && System.currentTimeMillis() - it.time <= MAX_AGE_MS }
            .maxByOrNull { it.time }
    }

    companion object {
        const val MAX_AGE_MS = 10 * 60_000L
        const val TIMEOUT_MS = 15_000L
    }
}

internal fun validLocation(location: Location): Boolean =
    location.latitude.isFinite() && location.latitude in -90.0..90.0 &&
        location.longitude.isFinite() && location.longitude in -180.0..180.0 &&
        location.hasAccuracy() && location.accuracy.isFinite() && location.accuracy >= 0 && location.time > 0

/** Same rounding and timestamp as shell BridgeProtocol.locationFix and the web position fixture. */
internal fun nativeLocationFix(location: Location): LocationFix = LocationFix(
    capturedAt = Instant.ofEpochMilli(location.time).toString(),
    latitude = location.latitude,
    longitude = location.longitude,
    accuracyMeters = maxOf(1.0, ceil(location.accuracy.toDouble())),
)
