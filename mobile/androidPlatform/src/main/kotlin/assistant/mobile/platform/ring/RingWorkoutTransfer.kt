package assistant.mobile.platform.ring

/** The encrypted HTTP outbox accepted these exact producer IDs, not a server import receipt. */
data class RingWorkoutTransferReceipt(val uploadIdentity: String, val externalIds: Set<String>)

/** A late scheduler result belongs to one attempt. */
data class RingWorkoutTransferRetry(val binding: RingWorkoutBinding, val id: String)
