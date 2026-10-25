package assistant.mobile.android

import android.graphics.Bitmap
import assistant.mobile.design.GeneratedColors
import androidx.compose.ui.graphics.toArgb
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** Synthetic payload shapes shared with web places/api.test.ts and server test_places.py. */
internal class NativePlacesFixture {
    val date = LocalDate.now(ZoneId.of("Europe/Moscow")).toString()
    @Volatile var historyDeleted = false
    @Volatile var receivedFix: JSONObject? = null
    @Volatile var backgroundPoints = 0
    @Volatile var daysEmpty = false
    data class Response(val status: Int, val bytes: ByteArray, val mediaType: String = "application/json")

    fun accepts(path: String): Boolean = path.substringBefore('?').let {
        it.startsWith("/v1/places/") || it.startsWith("/v1/me/location") || it.startsWith("/v1/maps/osm/")
    }

    @Synchronized fun respond(request: NativeLoopbackServer.Request): Response {
        val path = request.path.substringBefore('?')
        if (path.startsWith("/v1/me/location/collector/")) {
            check(request.headers["authorization"] == "Bearer synthetic-location-collector-never-valid")
            check(!request.headers.containsKey("x-expected-user"))
        } else {
            check(request.headers["authorization"] == "Bearer ${NativeLoopbackServer.credential(request.userId())}")
            check(request.headers["x-expected-user"] == request.userId())
        }
        check(!request.headers.containsKey("cookie"))
        if (path.startsWith("/v1/maps/osm/")) {
            check(request.method == "GET")
            val bitmap = Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888).apply {
                eraseColor(GeneratedColors.light.getValue("muted").toArgb())
            }
            val bytes = ByteArrayOutputStream().use { buffer ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, buffer)
                buffer.toByteArray()
            }
            bitmap.recycle()
            return Response(200, bytes, "image/png")
        }
        return when {
            request.method == "GET" && path == "/v1/places/days" -> json(JSONObject().put("timezone", "Europe/Moscow")
                .put("days", if (daysEmpty || historyDeleted) JSONArray() else JSONArray().put(JSONObject().put("date", date).put("points", 12))))
            request.method == "GET" && path == "/v1/places/days/$date" -> json(day())
            request.method == "GET" && path == "/v1/me/location" -> json(snapshot())
            request.method == "POST" && path == "/v1/me/location/collectors" -> json(JSONObject()
                .put("collector_id", "synthetic-location-collector").put("user_id", request.userId())
                .put("token", "synthetic-location-collector-never-valid")
                .put("expires_at", Instant.now().plusSeconds(3_600).toString()))
            request.method == "POST" && path == "/v1/me/location/collector/points" -> {
                val points = request.json().getJSONArray("points")
                receivedFix = points.getJSONObject(0)
                check(receivedFix!!.getString("source") == "background")
                backgroundPoints += points.length()
                json(JSONObject().put("accepted", points.length()).put("duplicates", 0))
            }
            request.method == "POST" && path == "/v1/me/location/collector/revoke" -> json(JSONObject())
            request.method == "POST" && path == "/v1/me/location/points" -> {
                receivedFix = request.json().getJSONArray("points").getJSONObject(0)
                json(JSONObject().put("accepted", 1).put("duplicates", 0))
            }
            request.method == "DELETE" && path == "/v1/me/location/history" -> {
                historyDeleted = true
                json(JSONObject().put("deleted_points", 12))
            }
            else -> error("Unexpected synthetic places request ${request.method} $path")
        }
    }

    private fun day(): JSONObject = JSONObject().put("date", date).put("timezone", "Europe/Moscow")
        .put("points", 12).put("labels_pending", 0)
        .put("stays", JSONArray().put(stay("06:00", "06:30", 30, 55.75, 37.61, "Synthetic home"))
            .put(stay("07:00", "08:05", 65, 55.76, 37.63, "Synthetic park")))
        .put("moves", JSONArray().put(JSONObject().put("from", at("06:30")).put("to", at("07:00")).put("distance_m", 1850)))
        .put("track", JSONArray().put(point(55.75, 37.61, "06:00")).put(point(55.755, 37.62, "06:45"))
            .put(point(55.76, 37.63, "07:00")))

    private fun snapshot(): JSONObject {
        val fix = receivedFix
        return JSONObject().put("current", if (historyDeleted) JSONObject.NULL else JSONObject()
            .put("place", "Synthetic park").put("country", "RU")
            .put("latitude", fix?.getDouble("latitude") ?: 55.76).put("longitude", fix?.getDouble("longitude") ?: 37.63)
            .put("accuracy_m", fix?.getDouble("accuracy_m")?.toInt() ?: 40).put("age_minutes", 5)
            .put("device", "Synthetic Android").put("captured_at", fix?.getString("captured_at") ?: Instant.now().minusSeconds(300).toString()))
            .put("history", JSONObject().put("points", if (historyDeleted) 0 else 12 + backgroundPoints)
                .put("since", if (historyDeleted) JSONObject.NULL else at("06:00")))
            .put("background", JSONObject().put("enabled", false))
    }
    private fun at(time: String) = "${date}T${time}:00Z"
    private fun point(latitude: Double, longitude: Double, time: String) = JSONArray().put(latitude).put(longitude).put(at(time))
    private fun stay(from: String, to: String, minutes: Int, latitude: Double, longitude: Double, place: String) =
        JSONObject().put("from", at(from)).put("to", at(to)).put("minutes", minutes)
            .put("latitude", latitude).put("longitude", longitude).put("place", place)
    private fun json(value: JSONObject) = Response(200, value.toString().toByteArray())
}
