package assistant.mobile.platform.ring

import assistant.mobile.platform.R

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.Looper
import android.os.SystemClock
import androidx.core.content.ContextCompat
import kotlinx.serialization.json.*

/** Typed OS capture port; a screen's visibility is distinct from an accepted location FGS. */
interface RingWorkoutLocationPort {
    fun access(): RingLocationAccess
    fun ready(foreground: Boolean): Boolean
    fun capturing(): Boolean
    fun state(visible: Boolean): JsonObject
    fun defer(current: RingWorkoutSession, failure: String)
    fun start(current: RingWorkoutSession, foreground: Boolean)
    fun tick(current: RingWorkoutSession)
    fun stop(endElapsedMs: Long? = null)
    fun unavailable(failure: String)
}

/** OS location capture has no BLE dependency. Every callback carries the session which registered it. */
class RingWorkoutLocation(
    private val context: Context,
    private val commit: (RingWorkoutSession, RingTrackPoint) -> Boolean,
    private val gap: (RingWorkoutSession, Long, Long, String) -> Unit,
    private val changed: () -> Unit,
    private val dispatch: (() -> Unit) -> Unit,
) : RingWorkoutLocationPort {
    private val manager get() = context.getSystemService(LocationManager::class.java)
    private var session: RingWorkoutSession? = null
    private var listener: LocationListener? = null
    private var epoch = 0L
    private var captureStartedMs = 0L
    private var captureStartedNanos = 0L
    private var lastFixMs: Long? = null
    private var gapStartMs: Long? = null
    private var gapReason = "no_fix"
    private var gapCount = 0
    private var status = "idle"
    private var reason: String? = null
    private var lastFix: JsonObject? = null
    override fun access(): RingLocationAccess {
        fun granted(permission: String) = ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
        val permission = when {
            granted(Manifest.permission.ACCESS_FINE_LOCATION) -> "precise"
            granted(Manifest.permission.ACCESS_COARSE_LOCATION) -> "approximate"
            else -> "denied"
        }
        val enabled = try {
            if (Build.VERSION.SDK_INT >= 28) manager.isLocationEnabled
            else manager.isProviderEnabled(LocationManager.GPS_PROVIDER) || manager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
        } catch (_: RuntimeException) { false }
        return RingLocationAccess(permission, Build.VERSION.SDK_INT < 29 || granted(Manifest.permission.ACCESS_BACKGROUND_LOCATION), enabled)
    }
    override fun ready(foreground: Boolean): Boolean = access().unavailableReason(foreground || listener != null) == null
    override fun capturing(): Boolean = listener != null
    override fun state(visible: Boolean): JsonObject {
        val access = access()
        return obj {
            put("permission", access.permission); put("background_permission", if (access.background) "granted" else "required")
            put("location_enabled", access.enabled); put("status", status); put("reason", reason?.let(::JsonPrimitive) ?: JsonNull)
            put("last_fix", if (visible) lastFix ?: JsonNull else JsonNull); put("gap_count", gapCount)
        }
    }
    /** Keep the requested gap visible without claiming a location service or registering a callback. */
    override fun defer(current: RingWorkoutSession, failure: String) {
        if (session?.id != current.id) { stop(); session = current; gapCount = 0; lastFix = null; lastFixMs = null }
        if (gapStartMs == null) gapStartMs = current.pauses.lastOrNull()?.endElapsedMs ?: current.startElapsedMs
        gapReason = failure; status = "unavailable"; reason = failure; changed()
    }
    @SuppressLint("MissingPermission")
    override fun start(current: RingWorkoutSession, foreground: Boolean) {
        if (session?.id != current.id) { stop(); session = current; gapCount = 0; lastFix = null; lastFixMs = null }
        if (listener != null) return
        val access = access()
        val unavailable = access.unavailableReason(foreground)
        if (unavailable != null) { if (gapStartMs == null) gapStartMs = current.startElapsedMs; gapReason = unavailable; status = "unavailable"; reason = unavailable; changed(); return }
        val registration = ++epoch
        captureStartedNanos = SystemClock.elapsedRealtimeNanos()
        captureStartedMs = captureStartedNanos / 1_000_000
        val captureBoundary = maxOf(current.startElapsedMs, current.pauses.lastOrNull()?.endElapsedMs ?: current.startElapsedMs)
        if (gapStartMs == null) gapStartMs = maxOf(captureBoundary, (lastFixMs ?: (captureBoundary - 1)) + 1)
        gapReason = "no_fix"; status = "waiting_fix"; reason = null
        val callback = object : LocationListener {
            override fun onLocationChanged(location: Location) { dispatch { received(current, registration, location) } }
            override fun onProviderDisabled(provider: String) { dispatch { unavailable(current, registration, "location_disabled") } }
            override fun onProviderEnabled(provider: String) { dispatch { changed() } }
            @Deprecated("Legacy provider callback")
            override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) { }
        }
        listener = callback
        try {
            val providers = if (access.permission == "precise") listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
                else listOf(LocationManager.NETWORK_PROVIDER)
            val enabled = providers.filter { manager.allProviders.contains(it) && manager.isProviderEnabled(it) }
            if (enabled.isEmpty()) throw IllegalStateException("No location provider")
            enabled.forEach { manager.requestLocationUpdates(it, 1_000L, 0f, callback, Looper.getMainLooper()) }
        } catch (_: SecurityException) { removeListener(); status = "unavailable"; reason = "permission_denied" }
        catch (_: RuntimeException) { removeListener(); status = "unavailable"; reason = "location_unavailable" }
        changed()
    }
    private fun received(owner: RingWorkoutSession, registration: Long, fix: Location) {
        if (registration != epoch || listener == null || session?.id != owner.id || session?.binding != owner.binding) return
        val now = SystemClock.elapsedRealtimeNanos()
        // requestLocationUpdates may deliver a cached fix. Its own clock, not receipt time, is authoritative.
        if (!RingLocationFixTime.fresh(captureStartedNanos, fix.elapsedRealtimeNanos, now) || !fix.hasAccuracy()) return
        val mock = if (Build.VERSION.SDK_INT >= 31) fix.isMock else @Suppress("DEPRECATION") fix.isFromMockProvider
        val quality = when { mock -> "invalid"; access().permission == "approximate" -> "approximate"; else -> "valid" }
        val point = try { RingTrackPoint(fix.elapsedRealtimeNanos, fix.latitude, fix.longitude, fix.accuracy.toDouble(), quality,
            fix.provider?.takeIf { it.length in 1..32 }) } catch (_: IllegalArgumentException) { return }
        if (lastFixMs?.let { point.elapsedRealtimeMs <= it } == true || gapStartMs?.let { point.elapsedRealtimeMs < it } == true) return
        try {
            closeGap(owner, point.elapsedRealtimeMs)
            if (!commit(owner, point)) return
            lastFixMs = maxOf(lastFixMs ?: point.elapsedRealtimeMs, point.elapsedRealtimeMs)
            lastFix = obj {
                put("at", owner.anchor.at(point.elapsedRealtimeMs).toString()); put("latitude", point.latitude); put("longitude", point.longitude)
                put("accuracy_meters", point.accuracyMeters); put("quality", point.quality)
            }
            status = "recording"; reason = null; changed()
        } catch (failure: Exception) {
            removeListener(); status = "unavailable"; reason = (failure as? RingFailure)?.code ?: "store_unavailable"; changed()
        }
    }
    private fun unavailable(owner: RingWorkoutSession, registration: Long, failure: String) {
        if (registration != epoch || session?.id != owner.id) return
        beginGap(owner, failure); status = "gap"; reason = failure; changed()
    }
    private fun beginGap(owner: RingWorkoutSession, failure: String) {
        if (gapStartMs == null) { gapStartMs = maxOf(owner.startElapsedMs, (lastFixMs ?: captureStartedMs) + 1); gapCount++ }
        gapReason = failure
    }
    private fun closeGap(owner: RingWorkoutSession, end: Long) {
        val start = gapStartMs ?: return
        if (end <= start) return
        gap(owner, start, end, gapReason)
        gapStartMs = null
    }
    override fun tick(current: RingWorkoutSession) {
        if (session?.id != current.id) return
        val access = access(); val now = SystemClock.elapsedRealtime()
        if (listener != null && (access.permission == "denied" || !access.enabled)) {
            beginGap(current, if (access.permission == "denied") "permission_denied" else "location_disabled")
            removeListener(); status = "unavailable"; reason = gapReason; changed()
        } else if (listener != null && now - (lastFixMs ?: captureStartedMs) >= 10_000) {
            beginGap(current, "no_fix"); status = "gap"; reason = "no_fix"; changed()
        }
        // Bound open gaps durably too; a restart must not lose minutes without a fix.
        val start = gapStartMs
        if (start != null && now - start >= 60_000) try { closeGap(current, now); gapStartMs = now }
        catch (failure: Exception) { removeListener(); status = "unavailable"; reason = (failure as? RingFailure)?.code ?: "store_unavailable"; changed() }
    }
    @SuppressLint("MissingPermission")
    private fun removeListener() {
        ++epoch
        listener?.let { try { manager.removeUpdates(it) } catch (_: RuntimeException) { } }
        listener = null
    }
    override fun stop(endElapsedMs: Long?) {
        var failureReason: String? = null
        try { session?.let { closeGap(it, minOf(SystemClock.elapsedRealtime(), endElapsedMs ?: Long.MAX_VALUE)) } }
        catch (failure: Exception) { failureReason = (failure as? RingFailure)?.code ?: "store_unavailable" }
        removeListener(); session = null; gapStartMs = null; status = if (status == "idle") "idle" else "stopped"; reason = failureReason
    }
    override fun unavailable(failure: String) {
        session?.let { beginGap(it, failure) }; removeListener(); status = "unavailable"; reason = failure; changed()
    }
}
