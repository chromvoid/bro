package assistant.mobile.platform.ring

import java.time.Instant
import java.time.ZoneId
import java.util.UUID
import kotlinx.serialization.json.*

/** The recording belongs to the grant which created it, even while the phone is offline. */
data class RingWorkoutBinding(
    val origin: String, val userId: String, val sourceId: String, val collectorId: String,
    val address: String, val grantGeneration: Long, val timezone: String,
) {
    init { require(grantGeneration > 0); ZoneId.of(timezone) }
    val identity: String get() = sha256(listOf(origin, userId, sourceId, collectorId,
        address.uppercase(), grantGeneration.toString(), timezone).joinToString("\n"))
    val uploadIdentity: String get() = sha256(listOf(origin, userId, sourceId, collectorId,
        address.uppercase()).joinToString("\n"))
}

/** UTC is anchored once; later clock changes cannot stretch or compress a workout. */
data class RingWorkoutClock(val utcAt: Instant, val elapsedRealtimeMs: Long, val bootId: String,
    val elapsedRealtimeNanos: Long = elapsedRealtimeMs * 1_000_000) {
    init { require(elapsedRealtimeMs >= 0 && bootId.isNotBlank() && elapsedRealtimeNanos / 1_000_000 == elapsedRealtimeMs) }
    fun at(elapsedMs: Long): Instant = utcAt.plusMillis(elapsedMs - elapsedRealtimeMs)
}
