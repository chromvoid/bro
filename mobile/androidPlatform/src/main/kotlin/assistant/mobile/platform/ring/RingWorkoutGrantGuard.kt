package assistant.mobile.platform.ring

/** Network absence does not revoke an accepted offline workout. */
enum class RingWorkoutGrantStatus { VERIFIED, REVOKED, UNAVAILABLE }

/** Verifies the active recorder independently of any screen, upload queue or BLE operation. */
class RingWorkoutGrantGuard(
    private val current: () -> RingWorkoutBinding?,
    private val verify: suspend (RingWorkoutBinding) -> RingWorkoutGrantStatus,
    private val revoked: (RingWorkoutBinding) -> Unit,
) {
    suspend fun check() {
        val owner = current() ?: return
        // Cancellation is deliberately propagated; an interrupted request is never evidence of revocation.
        if (verify(owner) == RingWorkoutGrantStatus.REVOKED && current() == owner) revoked(owner)
    }
}
