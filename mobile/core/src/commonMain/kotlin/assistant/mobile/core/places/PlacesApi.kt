package assistant.mobile.core.places

import assistant.mobile.core.HttpOutcome
import assistant.mobile.core.NativeHttpClient
import assistant.mobile.core.NativeJson
import assistant.mobile.core.SessionLease
import assistant.mobile.core.activity.ActivityReadException
import assistant.mobile.core.activity.NativeActivityApi
import io.ktor.http.encodeURLPathPart
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlin.time.Instant

interface PlacesReader {
    suspend fun days(lease: SessionLease): PlacesDays
    suspend fun day(date: String, lease: SessionLease): PlacesDay
    suspend fun tile(z: Int, x: Int, y: Int, lease: SessionLease): ByteArray
}
class PlacesRequestException(val outcome: HttpOutcome) : Exception("Places request failed")

class NativePlacesApi(private val http: NativeHttpClient) : PlacesReader {
    override suspend fun days(lease: SessionLease): PlacesDays = get("/v1/places/days?limit=60", lease, ::decodePlacesDays)
    override suspend fun day(date: String, lease: SessionLease): PlacesDay {
        require(validPlacesDate(date))
        return get("/v1/places/days/${date.encodeURLPathPart()}", lease, ::decodePlacesDay).also {
            if (it.date != date) throw PlacesRequestException(HttpOutcome.InvalidResponse)
        }
    }
    /** Reuse the activity tile boundary: same origin, private lease, PNG, bounded response, no redirect. */
    override suspend fun tile(z: Int, x: Int, y: Int, lease: SessionLease): ByteArray = try {
        NativeActivityApi(http).tile(z, x, y, lease)
    } catch (failure: ActivityReadException) { throw PlacesRequestException(failure.outcome) }
    private suspend fun <T> get(path: String, lease: SessionLease, decode: (ByteArray) -> T): T {
        val response = http.request("GET", path, lease = lease)
        if (response !is HttpOutcome.Success) throw PlacesRequestException(response)
        return try { decode(response.body) } catch (_: Exception) { throw PlacesRequestException(HttpOutcome.InvalidResponse) }
    }
}

fun decodePlacesDays(bytes: ByteArray): PlacesDays = NativeJson.decodeFromString<PlacesDays>(bytes.decodeToString()).also {
    TimeZone.of(it.timezone)
    require(it.days.all { row -> validPlacesDate(row.date) && row.points >= 0 })
    require(it.days.map { row -> row.date }.distinct().size == it.days.size)
}

@Serializable
private data class PlacesDayWire(
    val date: String, val timezone: String, val points: Int,
    @SerialName("labels_pending") val labelsPending: Int,
    val stays: List<PlaceStay>, val moves: List<PlaceMove>, val track: List<List<JsonElement>>,
)

fun decodePlacesDay(bytes: ByteArray): PlacesDay {
    val wire = NativeJson.decodeFromString<PlacesDayWire>(bytes.decodeToString())
    require(validPlacesDate(wire.date) && wire.points >= 0 && wire.labelsPending >= 0)
    TimeZone.of(wire.timezone)
    wire.stays.forEach {
        require(Instant.parse(it.from) <= Instant.parse(it.to) && it.minutes >= 0)
        coordinates(it.latitude, it.longitude)
    }
    wire.moves.forEach { require(Instant.parse(it.from) <= Instant.parse(it.to) && it.distanceMeters >= 0) }
    val track = wire.track.map { point ->
        require(point.size == 3)
        val latitude = point[0].number()
        val longitude = point[1].number()
        coordinates(latitude, longitude)
        val capturedAt = requireNotNull((point[2] as? JsonPrimitive)?.takeIf { it.isString }?.content) { "Invalid point time" }
        Instant.parse(capturedAt)
        PlaceTrackPoint(latitude, longitude, capturedAt)
    }
    return PlacesDay(wire.date, wire.timezone, wire.points, wire.labelsPending, wire.stays, wire.moves, track)
}

internal fun validPlacesDate(value: String): Boolean = runCatching { LocalDate.parse(value).toString() == value }.getOrDefault(false)
private fun coordinates(latitude: Double, longitude: Double) {
    require(latitude.isFinite() && latitude in -90.0..90.0 && longitude.isFinite() && longitude in -180.0..180.0)
}
private fun JsonElement.number(): Double = requireNotNull((this as? JsonPrimitive)?.takeUnless { it.isString }?.doubleOrNull) { "Invalid coordinate" }
