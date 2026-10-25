package assistant.mobile.core.location

import assistant.mobile.core.SessionLease
import assistant.mobile.core.SessionManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlin.time.Clock

const val LOCATION_REPORT_EVERY_MS = 10 * 60_000L

/** Same cadence as useLocationReporter.ts: visible start/resume and ten minutes, with no queue. */
class ForegroundLocationReporter(
    private val sessions: SessionManager,
    private val api: LocationApi,
    private val position: PositionSource,
    private val now: () -> Long = { Clock.System.now().toEpochMilliseconds() },
) {
    private val lock = Mutex()
    private var visible = false
    private var owner: SessionLease? = null
    private var last: Long? = null

    suspend fun onVisible() { visible = true; report() }
    fun onHidden() { visible = false }
    suspend fun tick() = report()

    private suspend fun report() {
        if (!visible || !lock.tryLock()) return
        try {
            val lease = sessions.lease() ?: run { owner = null; last = null; return }
            if (owner !== lease) { owner = lease; last = null }
            if (last?.let { now() - it < LOCATION_REPORT_EVERY_MS } == true) return
            if (position.status().permission != PositionPermission.GRANTED || !sessions.isCurrent(lease) || !visible) return
            val fix = position.current() ?: return
            if (!sessions.isCurrent(lease) || !visible) return
            api.sendFix(fix, lease)
            if (sessions.isCurrent(lease)) last = now()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // A failed visible send waits until the next chance, never persists or retries itself.
        } finally { lock.unlock() }
    }
}
