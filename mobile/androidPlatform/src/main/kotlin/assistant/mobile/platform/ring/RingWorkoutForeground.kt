package assistant.mobile.platform.ring

import assistant.mobile.platform.R

/** A requested stream never substitutes for the current Android permission/resource prerequisites. */
data class RingWorkoutForegroundResources(
    val heartRateNeeded: Boolean, val bluetoothReady: Boolean, val locationReady: Boolean,
)
enum class RingWorkoutForegroundMode(val bluetooth: Boolean, val location: Boolean) {
    CONNECTED_DEVICE(true, false), LOCATION(false, true), BOTH(true, true),
}
data class RingWorkoutForegroundSelection(
    val mode: RingWorkoutForegroundMode, val bluetoothRejected: Boolean, val locationRejected: Boolean,
)
object RingWorkoutForegroundPolicy {
    fun candidates(resources: RingWorkoutForegroundResources): List<RingWorkoutForegroundMode> {
        val bluetooth = resources.heartRateNeeded && resources.bluetoothReady
        return when {
            bluetooth && resources.locationReady -> listOf(RingWorkoutForegroundMode.BOTH,
                RingWorkoutForegroundMode.LOCATION, RingWorkoutForegroundMode.CONNECTED_DEVICE)
            resources.locationReady -> listOf(RingWorkoutForegroundMode.LOCATION)
            bluetooth -> listOf(RingWorkoutForegroundMode.CONNECTED_DEVICE)
            else -> emptyList() // A phone timer has no foreground-service type.
        }
    }
    /** At most three attempts. Re-read prerequisites between attempts: permissions can change during promotion. */
    fun promote(current: RingWorkoutForegroundMode?, resources: () -> RingWorkoutForegroundResources,
        start: (RingWorkoutForegroundMode) -> Unit): RingWorkoutForegroundSelection? {
        val attempts = candidates(resources())
        val preferred = attempts.firstOrNull() ?: return null
        var failure: RuntimeException? = null
        for (mode in attempts) {
            if (mode !in candidates(resources())) continue
            try {
                if (mode != current) start(mode)
                return RingWorkoutForegroundSelection(mode, preferred.bluetooth && !mode.bluetooth,
                    preferred.location && !mode.location)
            } catch (rejected: RuntimeException) { failure = rejected }
        }
        failure?.let { throw it }
        return null
    }
}
