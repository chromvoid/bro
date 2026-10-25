package assistant.mobile.core.location

import assistant.mobile.core.AccountNamespace
import assistant.mobile.core.LocalizedString
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.math.ceil
import kotlin.time.Instant

enum class PositionPermission { GRANTED, PROMPT, DENIED, UNAVAILABLE }
enum class PositionPrecision { PRECISE, APPROXIMATE, NONE }
data class PositionStatus(val permission: PositionPermission, val precision: PositionPrecision = PositionPrecision.NONE)

/** Status and current never request permission. Only an explicit owner action calls request. */
interface PositionSource {
    suspend fun status(): PositionStatus
    suspend fun request(): PositionStatus
    suspend fun current(): LocationFix?
}

@Serializable
data class LocationFix(
    @SerialName("captured_at") val capturedAt: String,
    val latitude: Double,
    val longitude: Double,
    @SerialName("accuracy_m") val accuracyMeters: Double,
) {
    fun normalized(): LocationFix {
        Instant.parse(capturedAt)
        require(latitude.isFinite() && latitude in -90.0..90.0)
        require(longitude.isFinite() && longitude in -180.0..180.0)
        require(accuracyMeters.isFinite() && accuracyMeters > 0 && accuracyMeters <= 100_000)
        return copy(accuracyMeters = ceil(accuracyMeters).coerceAtLeast(1.0))
    }
}

data class BackgroundLocationState(val enabled: Boolean, val permission: PositionPermission, val queued: Int)
interface BackgroundRecording {
    suspend fun status(namespace: AccountNamespace): BackgroundLocationState
    suspend fun enable(namespace: AccountNamespace): BackgroundLocationState
    suspend fun disable(): BackgroundLocationState
}

@Serializable
data class LocationCollectorGrant(
    @SerialName("collector_id") val collectorId: String,
    @SerialName("user_id") val userId: String,
    val token: String,
    @SerialName("expires_at") val expiresAt: String,
) {
    override fun toString(): String = "LocationCollectorGrant(redacted)"
}

@Serializable
data class LocationCurrent(
    val place: String?, val country: String?, val latitude: Double, val longitude: Double,
    @SerialName("accuracy_m") val accuracyMeters: Int,
    @SerialName("age_minutes") val ageMinutes: Int,
    val device: String?, @SerialName("captured_at") val capturedAt: String,
)
@Serializable data class LocationHistory(val points: Int, val since: String?)
@Serializable data class LocationBackground(val enabled: Boolean)
@Serializable data class LocationSnapshot(val current: LocationCurrent?, val history: LocationHistory, val background: LocationBackground)
@Serializable data class LocationAcknowledgement(val accepted: Int, val duplicates: Int)
@Serializable data class LocationDeletion(@SerialName("deleted_points") val deletedPoints: Int)

data class LocationQuery<T>(val data: T? = null, val loading: Boolean = false, val error: LocalizedString? = null)
data class LocationState(
    val namespace: AccountNamespace,
    val snapshot: LocationQuery<LocationSnapshot> = LocationQuery(),
    val position: PositionStatus = PositionStatus(PositionPermission.UNAVAILABLE),
    val background: BackgroundLocationState? = null,
    val busy: Boolean = false,
    val error: LocalizedString? = null,
)
