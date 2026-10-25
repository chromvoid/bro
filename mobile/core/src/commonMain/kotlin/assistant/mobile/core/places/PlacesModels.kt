package assistant.mobile.core.places

import assistant.mobile.core.AccountNamespace
import assistant.mobile.core.LocalizedString
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.time.Instant

@Serializable data class PlacesDaySummary(val date: String, val points: Int)
@Serializable data class PlacesDays(val timezone: String, val days: List<PlacesDaySummary>)
@Serializable data class PlaceStay(val from: String, val to: String, val minutes: Int, val latitude: Double, val longitude: Double, val place: String?)
@Serializable data class PlaceMove(val from: String, val to: String, @SerialName("distance_m") val distanceMeters: Int)
data class PlaceTrackPoint(val latitude: Double, val longitude: Double, val capturedAt: String)
data class PlacesDay(
    val date: String, val timezone: String, val points: Int, val labelsPending: Int,
    val stays: List<PlaceStay>, val moves: List<PlaceMove>, val track: List<PlaceTrackPoint>,
)

sealed interface PlaceTimelineEntry {
    val from: String
    data class Stay(val stay: PlaceStay) : PlaceTimelineEntry { override val from: String get() = stay.from }
    data class Move(val move: PlaceMove) : PlaceTimelineEntry { override val from: String get() = move.from }
}

/** Keep the server's address, durations and distances. Sorting is presentation only. */
fun timeline(day: PlacesDay): List<PlaceTimelineEntry> =
    (day.stays.map { PlaceTimelineEntry.Stay(it) } + day.moves.map { PlaceTimelineEntry.Move(it) })
        .sortedBy { Instant.parse(it.from) }

data class PlacesQuery<T>(val data: T? = null, val loading: Boolean = false, val error: LocalizedString? = null)
data class PlacesState(
    val namespace: AccountNamespace,
    val days: PlacesQuery<PlacesDays> = PlacesQuery(),
    val day: PlacesQuery<PlacesDay> = PlacesQuery(),
    val selectedDate: String? = null,
)
