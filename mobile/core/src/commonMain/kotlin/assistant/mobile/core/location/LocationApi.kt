package assistant.mobile.core.location

import assistant.mobile.core.HttpOutcome
import assistant.mobile.core.NativeHttpClient
import assistant.mobile.core.NativeJson
import assistant.mobile.core.SessionLease
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.time.Instant

interface LocationApi {
    suspend fun snapshot(lease: SessionLease): LocationSnapshot
    suspend fun sendFix(fix: LocationFix, lease: SessionLease): LocationAcknowledgement
    suspend fun deleteHistory(lease: SessionLease): LocationDeletion
    suspend fun enroll(installationId: String, lease: SessionLease): LocationCollectorGrant
}
class LocationRequestException(val outcome: HttpOutcome) : Exception("Location request failed")

/** Collector enrollment uses the current human native session; the returned bearer is never reused here. */
class NativeLocationApi(private val http: NativeHttpClient) : LocationApi {
    override suspend fun snapshot(lease: SessionLease): LocationSnapshot = request("GET", "/v1/me/location", lease = lease) { bytes ->
        NativeJson.decodeFromString<LocationSnapshot>(bytes.decodeToString()).also { value ->
            require(value.history.points >= 0)
            value.history.since?.let(Instant::parse)
            value.current?.let {
                LocationFix(it.capturedAt, it.latitude, it.longitude, it.accuracyMeters.toDouble()).normalized()
                require(it.ageMinutes >= 0)
            }
        }
    }
    override suspend fun sendFix(fix: LocationFix, lease: SessionLease): LocationAcknowledgement {
        val body = "{\"points\":[${NativeJson.encodeToString(fix.normalized())}]}".encodeToByteArray()
        return request("POST", "/v1/me/location/points", body, lease) { bytes ->
            NativeJson.decodeFromString<LocationAcknowledgement>(bytes.decodeToString()).also {
                require(LocationQueuePolicy.acknowledges(it, 1))
            }
        }
    }
    override suspend fun deleteHistory(lease: SessionLease): LocationDeletion = request("DELETE", "/v1/me/location/history", lease = lease) { bytes ->
        NativeJson.decodeFromString<LocationDeletion>(bytes.decodeToString()).also { require(it.deletedPoints >= 0) }
    }
    override suspend fun enroll(installationId: String, lease: SessionLease): LocationCollectorGrant {
        require(installationId.isNotBlank() && installationId.length <= 80)
        val body = buildJsonObject { put("installation_id", installationId) }.toString().encodeToByteArray()
        return request("POST", "/v1/me/location/collectors", body, lease) { bytes ->
            NativeJson.decodeFromString<LocationCollectorGrant>(bytes.decodeToString()).also {
                require(it.userId == lease.namespace.userId && it.collectorId.isNotBlank())
                require(it.token.isNotBlank() && it.token.length <= 128 && it.token.none { character -> character.isWhitespace() || character.isISOControl() })
                Instant.parse(it.expiresAt)
            }
        }
    }
    private suspend fun <T> request(method: String, path: String, body: ByteArray? = null, lease: SessionLease, decode: (ByteArray) -> T): T {
        val reply = http.request(method, path, body, lease)
        if (reply !is HttpOutcome.Success) throw LocationRequestException(reply)
        return try { decode(reply.body) } catch (_: Exception) { throw LocationRequestException(HttpOutcome.InvalidResponse) }
    }
}
