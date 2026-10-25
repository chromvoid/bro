package assistant.mobile.android

import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.ui.graphics.toArgb
import assistant.mobile.design.GeneratedColors
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

/** Synthetic server contracts from runningFixtures.ts and the Activity service; no family data. */
internal class NativeActivityFixture {
    val date = LocalDate.now(ZoneId.of("Europe/Moscow")).toString()
    private val personal = NativePersonalFixture()
    private val sessions = linkedMapOf<String, JSONObject>()
    private val receipts = linkedMapOf<String, JSONObject>()
    private val bodies = linkedMapOf<String, String>()
    private val exercise = JSONObject().put("id", EXERCISE_ID).put("revision", 2)
        .put("name", "Synthetic squat").put("equipment", "Synthetic barbell")
        .put("variant", "Synthetic full range").put("load_basis", "total")
    private var steps = 2400
    private var override: JSONObject? = null
    @Volatile var conflictNext = false
    @Volatile var lookupOnline = true
    @Volatile var disconnectAdmission = false
    @Volatile var workoutTimezone = "Europe/Moscow"

    data class Response(val status: Int, val bytes: ByteArray, val mediaType: String = "application/json",
        val headers: Map<String, String> = emptyMap())

    fun accepts(path: String): Boolean = path.substringBefore('?').let {
        it.startsWith("/v1/activity/") || it in PERSONAL_PATHS ||
            it.startsWith("/v1/personal/rings/workouts/") || it.startsWith("/v1/maps/osm/")
    }

    @Synchronized fun writeCount() = receipts.size
    @Synchronized fun sessionIds() = sessions.keys.toList()
    @Synchronized fun session(id: String) = copy(requireNotNull(sessions[id]))
    @Synchronized fun updateDailySteps(value: Int) { steps = value }

    /** Three dates make progress and its range navigation observable without fixture writes. */
    @Synchronized fun seedProgress() {
        repeat(3) { index ->
            val id = "92000000-0000-4000-8000-00000000000${index + 1}"
            val performedId = "93000000-0000-4000-8000-00000000000${index + 1}"
            val setId = "94000000-0000-4000-8000-00000000000${index + 1}"
            sessions[id] = JSONObject().put("id", id).put("revision", 3).put("kind", "strength")
                .put("title", "Synthetic strength ${index + 1}")
                .put("date", LocalDate.parse(date).minusDays((2 - index).toLong()).toString())
                .put("timezone", "Europe/Moscow").put("started_at", JSONObject.NULL)
                .put("duration_minutes", "45").put("distance_km", JSONObject.NULL).put("note", "")
                .put("exercises", JSONArray().put(JSONObject().put("id", performedId).put("revision", 2)
                    .put("exercise_id", EXERCISE_ID).put("position", 0).put("snapshot", snapshot()).put("note", "")
                    .put("sets", JSONArray().put(JSONObject().put("id", setId).put("revision", 1).put("position", 0)
                        .put("kind", "working").put("repetitions", 8).put("load_value", listOf("80", "82.5", "85")[index])
                        .put("load_unit", "kg").put("implement_count", 1).put("note", "")))))
        }
    }

    @Synchronized fun respond(request: NativeLoopbackServer.Request): Response {
        check(request.headers["authorization"] == "Bearer ${NativeLoopbackServer.credential(request.userId())}")
        check(!request.headers.containsKey("cookie"))
        val path = request.path.substringBefore('?')
        val query = Uri.parse(request.path)
        if (path.startsWith("/v1/personal/rings/workouts/")) {
            check(path.removePrefix("/v1/personal/rings/workouts/").substringBefore('/') == WORKOUT_ID)
            check(query.getQueryParameter("source_id") == SOURCE_ID)
        }
        if (request.method == "GET" && path.startsWith("/v1/maps/osm/")) {
            check(path.removePrefix("/v1/maps/osm/").split('/').let { parts ->
                parts.size == 3 && parts[0].toInt() in 0..19 && parts[1].toInt() >= 0 &&
                    parts[2].removeSuffix(".png").toInt() >= 0
            })
            val bitmap = Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888)
            bitmap.eraseColor(GeneratedColors.light.getValue("background").toArgb())
            val bytes = ByteArrayOutputStream().use { stream ->
                check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream))
                stream.toByteArray()
            }
            bitmap.recycle()
            return Response(200, bytes, "image/png", mapOf("Cache-Control" to "private, max-age=3600"))
        }
        val body = when {
            request.method == "GET" && path == "/v1/personal/overview" -> overview(query.getQueryParameter("date") ?: date)
            request.method == "GET" && path == "/v1/personal/steps" -> JSONObject()
                .put("date", query.getQueryParameter("date") ?: date).put("override", override ?: JSONObject.NULL)
            request.method == "GET" && path == "/v1/personal/history" -> history(query)
            request.method == "GET" && path == "/v1/personal/resolved" -> resolved(query)
            request.method == "GET" && path == "/v1/personal/effort" -> JSONObject().put("effort", JSONObject()
                .put("source_name", "Synthetic strap").put("heart_rate", JSONObject().put("mean", 142).put("max", 168))
                .put("zone_seconds", JSONArray(listOf(0, 240, 900, 300, 0))).put("trimp", "42.50")
                .put("effort", JSONObject().put("rest", "55").put("max", "190").put("basis", "profile")))
            request.method == "GET" && path == "/v1/personal/activity-reports" -> reports(query)
            request.method == "GET" && path.endsWith("/heart-rate") -> heartRate()
            request.method == "GET" && path.endsWith("/track-summary-versions") -> trackVersions()
            request.method == "GET" && path.endsWith("/track") -> track()
            request.method == "GET" && path == "/v1/activity/sessions" -> JSONObject()
                .put("sessions", JSONArray(selected(query).map(::copy))).put("truncated", false).put("limit", 1000)
                .put("from", query.getQueryParameter("from") ?: date).put("to", query.getQueryParameter("to") ?: date)
            request.method == "GET" && path.startsWith("/v1/activity/sessions/") -> {
                val session = sessions[path.substringAfterLast('/')]
                    ?: return json(404, JSONObject().put("error", "not_found"))
                JSONObject().put("session", copy(session))
            }
            request.method == "GET" && path == "/v1/activity/exercises" -> JSONObject()
                .put("exercises", JSONArray().put(copy(exercise)))
            request.method == "GET" && path == "/v1/activity/summary" -> summary(query)
            request.method == "GET" && path == "/v1/activity/progress" -> {
                check(query.getQueryParameter("exercise_id") == EXERCISE_ID)
                progress(query)
            }
            request.method == "GET" && path.startsWith("/v1/activity/operations/by-key/") -> {
                if (!lookupOnline) return json(503, JSONObject())
                val receipt = receipts[path.substringAfterLast('/')] ?: return json(404, JSONObject())
                copy(receipt)
            }
            request.method == "POST" && path == "/v1/activity/operations" -> return operate(request)
            else -> error("Unknown synthetic activity route: ${request.method} $path")
        }
        return json(200, body)
    }

    private fun overview(day: String): JSONObject = personal.overview().put("date", day).apply {
        val metrics = getJSONObject("metrics")
        metrics.put("steps", metric("steps", steps, "count").put("target", "8000"))
        metrics.put("active_minutes", metric("active_minutes", "35", "min"))
        metrics.put("distance", metric("distance", "1850", "m"))
        metrics.put("active_energy", metric("active_energy", "220", "kcal"))
    }

    private fun metric(kind: String, value: Any, unit: String) = JSONObject().put("kind", kind)
        .put("status", "available").put("value", value).put("unit", unit).put("records", JSONArray())

    private fun history(query: Uri): JSONObject {
        val kind = query.getQueryParameter("kind") ?: "steps"
        val daily = JSONArray()
        repeat(3) { index ->
            val day = LocalDate.parse(date).minusDays((2 - index).toLong()).toString()
            if (day in (query.getQueryParameter("from") ?: "0000")..(query.getQueryParameter("to") ?: "9999"))
                daily.put(metric(kind, if (kind == "steps") 1600 + index * 400 else 25 + index * 5,
                    if (kind == "steps") "count" else "min").put("date", day))
        }
        return JSONObject().put("from", query.getQueryParameter("from") ?: date)
            .put("to", query.getQueryParameter("to") ?: date).put("daily", daily).put("records", JSONArray())
    }

    /** A day-counter snapshot remains separate from daily steps, as in runningFixtures.ts. */
    private fun resolved(query: Uri): JSONObject {
        val kind = query.getQueryParameter("metric") ?: "running_steps"
        val isSteps = kind == "running_steps"
        val included = date in (query.getQueryParameter("from") ?: "0000")..(query.getQueryParameter("to") ?: "9999")
        val episodes = if (kind == "running_workouts" && included) JSONArray().put(workoutReport()) else JSONArray()
        val decision = JSONObject().put("status", if (isSteps) "partial" else "available")
            .put("fingerprint", "synthetic-$kind-inputs").put("resolver_version", "resolution-running-v1")
            .put("selected", if (isSteps) JSONArray().put(JSONObject().put("id", SNAPSHOT_ID).put("revision", 1)
                .put("source_id", SOURCE_ID).put("source_name", "Synthetic ring").put("contribution", 420))
                else JSONArray((0 until episodes.length()).map { index -> episodes.getJSONObject(index).let {
                    JSONObject().put("id", it.getString("id")).put("revision", it.getInt("revision"))
                        .put("source_id", SOURCE_ID).put("source_name", "Synthetic ring")
                } }))
            .put("alternatives", JSONArray()).put("episodes", episodes)
            .put("protocol", JSONObject().put("verified", false).put("flags", JSONArray().put("protocol_unverified")))
        if (isSteps) {
            decision.put("data", JSONObject().put("shape", "day_counter_snapshot")
                .put("observed_at", "${date}T08:00:00Z").put("value", 420).put("local_date", date))
                .put("coverage", JSONObject().put("basis", "daily_counter_snapshot").put("complete", false))
        } else decision.put("summary", JSONObject().put("count", episodes.length())
            .put("known_duration_seconds", 1800 * episodes.length()).put("unknown_duration_count", 0))
        val daily = JSONArray()
        if (included) daily.put(JSONObject().put("date", date).put("kind", kind).put("status", "available")
            .put("value", if (isSteps) 420 else episodes.length()).put("unit", if (isSteps) "steps" else "workouts")
            .put("complete", false).put("resolution", decision))
        return JSONObject().put("from", query.getQueryParameter("from") ?: date)
            .put("to", query.getQueryParameter("to") ?: date).put("timezone", "Europe/Moscow")
            .put("computed_at", "${date}T12:00:00Z").put("fingerprint", "synthetic-$kind-page")
            .put("daily", daily).put("series", episodes).put("next_cursor", JSONObject.NULL)
    }

    private fun workoutReport(): JSONObject = JSONObject().put("id", DEVICE_REPORT_ID).put("revision", 1)
        .put("source_id", SOURCE_ID).put("source_name", "Synthetic ring").put("source_version", 1)
        .put("shape", "workout_report").put("kind", "running_workouts").put("local_date", date)
        .put("timezone", workoutTimezone).put("observed_at", "${date}T07:30:00Z")
        .put("start_at", "${date}T07:00:00Z").put("end_at", "${date}T07:30:00Z")
        .put("data", JSONObject().put("sport_type", "running").put("duration_seconds", 1800)
            .put("active_duration_seconds", 1740).put("steps", 3000).put("distance_meters", 5000)
            .put("heart_rate", JSONObject().put("mean", 142).put("max", 168))
            .put("zone_seconds", JSONArray(listOf(0, 240, 900, 300, 0))).put("trimp", "42.50")
            .put("effort", JSONObject().put("rest", "55").put("max", "190").put("basis", "profile")))
        .put("origin", JSONObject().put("source", "phone_recorded").put("record_id", WORKOUT_ID)
            .put("details", JSONObject().put("status", "completed").put("trigger", "manual")))
        .put("quality", JSONArray()).put("is_current", true).put("current_revision", 1)
        .put("excluded", false).put("resolution_disposition", "selected")

    private fun reports(query: Uri) = JSONObject().put("from", query.getQueryParameter("from") ?: date)
        .put("to", query.getQueryParameter("to") ?: date).put("items", JSONArray().put(workoutReport()))
        .put("next_cursor", JSONObject.NULL).put("fingerprint", "synthetic-report-archive")

    private fun workoutContext() = JSONObject().put("kind", "running_workouts").put("sport_type", "running")
        .put("recording_source", "phone_recorded").put("gps_requested", true)
        .put("duration_seconds", 1800).put("active_duration_seconds", 1740)
        .put("start_at", "${date}T07:00:00Z").put("end_at", "${date}T07:30:00Z")
        .put("timezone", workoutTimezone).put("status", "completed").put("trigger", "manual")

    private fun heartRate() = JSONObject().put("workout_id", WORKOUT_ID).put("source_id", SOURCE_ID)
        .put("report_ref", JSONObject().put("id", DEVICE_REPORT_ID).put("revision", 1))
        .put("fingerprint", "a".repeat(64)).put("workout", workoutContext()).put("next_cursor", JSONObject.NULL)
        .put("chunks", JSONArray().put(JSONObject().put("external_id", "synthetic-pulse-0")
            .put("workout_id", WORKOUT_ID).put("sequence", 0).put("start_at", "${date}T07:00:00Z")
            .put("end_at", "${date}T07:30:10Z").put("time_basis", "phone_monotonic")
            .put("clock_anchor", JSONObject().put("utc_at", "${date}T07:00:00Z")
                .put("elapsed_realtime_ms", 1000).put("boot_id", "synthetic-boot"))
            .put("samples", JSONArray().put(sample(0, 112)).put(sample(60_000, 142))
                .put(sample(120_000, 168)).put(sample(180_000, 250, "invalid"))
                .put(sample(1_799_000, 135)).put(sample(1_801_000, 120)))
            .put("gaps", JSONArray().put(JSONObject().put("start_offset_ms", 180_000)
                .put("end_offset_ms", 1_799_000).put("reason", "missing_chunk")))))

    private fun sample(offset: Int, bpm: Int, quality: String = "valid") = JSONObject()
        .put("offset_ms", offset).put("bpm", bpm).put("quality", quality)

    private fun track() = JSONObject().put("workout_id", WORKOUT_ID).put("source_id", SOURCE_ID)
        .put("workout", workoutContext()).put("chunks", JSONArray()).put("next_cursor", JSONObject.NULL)
        .put("fingerprint", "synthetic-track").put("summary", trackSummary())
        .put("segments", JSONArray().put(JSONObject().put("context", "running").put("quality", "valid")
            .put("points", JSONArray().put(point("07:00:00", 55.75, 37.61)).put(point("07:03:00", 55.751, 37.612))
                .put(point("07:06:00", 55.752, 37.615))))
            .put(JSONObject().put("context", "recovery").put("quality", "valid").put("points", JSONArray()
                .put(point("07:30:01", 55.752, 37.615)).put(point("07:31:00", 55.7525, 37.616)))))

    private fun point(time: String, latitude: Double, longitude: Double) = JSONObject()
        .put("at", "${date}T${time}Z").put("latitude", latitude).put("longitude", longitude)
        .put("accuracy_meters", 5).put("quality", "valid").put("provider", "gps")

    private fun context(distance: Int, count: Int) = JSONObject().put("distance_meters", distance)
        .put("usable_points", count).put("reported_points", count).put("gap_count", 0)
        .put("coverage_seconds", 360).put("quality", JSONArray().put("gps_estimate"))

    private fun trackSummary() = JSONObject().put("algorithm_version", "gps-distance-v1")
        .put("value_meters", 1850).put("unit", "m").put("contexts", JSONObject()
            .put("running", context(1850, 3)).put("recovery", context(120, 2)))
        .put("input_refs", JSONArray()).put("fingerprint", "synthetic-track")

    private fun trackVersions() = JSONObject().put("workout_id", WORKOUT_ID).put("source_id", SOURCE_ID)
        .put("latest_revision", 1).put("fingerprint", "synthetic-track-history").put("next_cursor", JSONObject.NULL)
        .put("items", JSONArray().put(JSONObject().put("revision", 1).put("is_latest", true)
            .put("snapshot", trackSummary().put("report_ref", JSONObject().put("id", DEVICE_REPORT_ID).put("revision", 1)))))

    private fun selected(query: Uri) = sessions.values.filter {
        val kind = query.getQueryParameter("kind")
        val from = query.getQueryParameter("from") ?: "0000"
        val to = query.getQueryParameter("to") ?: "9999"
        (kind == null || kind == it.getString("kind")) && it.getString("date") in from..to
    }

    private fun summary(query: Uri): JSONObject {
        val rows = selected(query)
        val setCount = rows.sumOf { row -> row.getJSONArray("exercises").objects().sumOf { performed ->
            performed.getJSONArray("sets").objects().count { it.getString("kind") == "working" }
        } }
        val total = JSONObject().put("sessions", rows.size).put("duration_minutes", JSONObject.NULL)
            .put("missing_duration_sessions", rows.count { it.isNull("duration_minutes") })
            .put("distance_km_by_kind", JSONObject()).put("working_sets", setCount)
        return JSONObject().put("from", query.getQueryParameter("from") ?: date)
            .put("to", query.getQueryParameter("to") ?: date).put("totals", total)
            .put("daily", JSONArray().put(copy(total).put("date", date)))
    }

    private fun progress(query: Uri): JSONObject {
        val points = JSONArray()
        selected(query).forEach { session -> session.getJSONArray("exercises").objects().forEach { performed ->
            if (performed.getString("exercise_id") == EXERCISE_ID) performed.getJSONArray("sets").objects()
                .filter { it.getString("kind") == "working" }.forEach { set ->
                    points.put(JSONObject().put("date", session.getString("date")).put("session_id", session.getString("id"))
                        .put("session_exercise_id", performed.getString("id")).put("set_id", set.getString("id"))
                        .put("repetitions", set.get("repetitions")).put("load_value", set.get("load_value"))
                        .put("load_kg", set.get("load_value")))
                }
        } }
        return JSONObject().put("exercise", copy(exercise)).put("from", query.getQueryParameter("from") ?: date)
            .put("to", query.getQueryParameter("to") ?: date).put("series", JSONArray().apply {
                if (points.length() > 0) put(JSONObject().put("snapshot", snapshot()).put("load_unit", "kg")
                    .put("implement_count", 1).put("points", points))
            }).put("daily", JSONArray().put(JSONObject().put("date", date).put("working_sets", points.length())))
    }

    private fun operate(request: NativeLoopbackServer.Request): Response {
        check(request.headers["x-expected-user"] == request.userId())
        check(request.headers["content-type"]?.substringBefore(';') == "application/json")
        val key = requireNotNull(request.headers["idempotency-key"])
        UUID.fromString(key)
        receipts[key]?.let {
            check(bodies.getValue(key) == request.body) { "An Activity operation key changed its body" }
            return json(200, copy(it))
        }
        val body = request.json()
        check(body.keys().asSequence().all { it in setOf("kind", "payload", "expected_revisions") })
        val kind = body.getString("kind")
        val payload = body.getJSONObject("payload")
        val expected = body.getJSONObject("expected_revisions")
        val sessionId = payload.optString("session_id").takeIf { it.isNotEmpty() }
        val session = sessionId?.let { requireNotNull(sessions[it]) }
        if (session != null) {
            if (conflictNext) {
                conflictNext = false
                session.put("title", "Synthetic concurrent edit").put("revision", session.getInt("revision") + 1)
                return conflict()
            }
            if (expected.optInt(sessionId, -1) != session.getInt("revision")) return conflict()
        }
        val receipt = JSONObject().put("operation_id", UUID.randomUUID().toString())
            .put("kind", kind).put("event_seq", receipts.size + 1)
        when (kind) {
            "activity.session.add" -> {
                check(expected.length() == 0)
                val id = UUID.randomUUID().toString()
                val row = JSONObject().put("id", id).put("revision", 1).put("exercises", JSONArray())
                    .put("title", JSONObject.NULL).put("timezone", "Europe/Moscow").put("started_at", JSONObject.NULL)
                    .put("duration_minutes", JSONObject.NULL).put("distance_km", JSONObject.NULL).put("note", "")
                payload.keys().forEach { row.put(it, payload.get(it)) }
                check(row.getString("kind") in setOf("walking", "running", "cycling", "strength", "swimming", "mobility", "other"))
                sessions[id] = row
                receipt.put("session_id", id).put("session", copy(row))
            }
            "activity.session.update" -> {
                val row = requireNotNull(session)
                check(expected.length() == 1)
                payload.keys().forEach { if (it != "session_id") row.put(it, payload.get(it)) }
                bump(row)
                receipt.put("session_id", sessionId).put("session", copy(row))
            }
            "activity.session.delete" -> {
                check(expected.length() == 1)
                check(sessions.remove(sessionId) != null)
                receipt.put("session_id", sessionId).put("deleted_id", sessionId)
            }
            "activity.session_exercise.add" -> {
                val row = requireNotNull(session)
                check(expected.length() == 1)
                check(payload.getString("exercise_id") == EXERCISE_ID)
                val id = UUID.randomUUID().toString()
                row.getJSONArray("exercises").put(JSONObject().put("id", id).put("revision", 1)
                    .put("exercise_id", EXERCISE_ID).put("position", row.getJSONArray("exercises").length())
                    .put("snapshot", snapshot()).put("note", "").put("sets", JSONArray()))
                bump(row)
                receipt.put("session_id", sessionId).put("session_exercise_id", id).put("session", copy(row))
            }
            "activity.set.add", "activity.set.update", "activity.set.delete" -> {
                val row = requireNotNull(session)
                val performed = row.getJSONArray("exercises").objects().single { it.getString("id") == payload.getString("session_exercise_id") }
                if (expected.optInt(performed.getString("id"), -1) != performed.getInt("revision")) return conflict()
                val sets = performed.getJSONArray("sets")
                val setId = if (kind == "activity.set.add") UUID.randomUUID().toString() else payload.getString("set_id")
                val set = if (kind == "activity.set.add") JSONObject().put("id", setId).put("revision", 1)
                    .put("position", sets.length()).put("kind", "working").put("load_unit", "kg")
                    .put("implement_count", 1).put("note", "").put("repetitions", JSONObject.NULL).put("load_value", JSONObject.NULL)
                    else sets.objects().single { it.getString("id") == setId }.also {
                        if (expected.optInt(setId, -1) != it.getInt("revision")) return conflict()
                        bump(it)
                    }
                check(expected.length() == if (kind == "activity.set.add") 2 else 3)
                if (kind == "activity.set.delete") {
                    val remaining = sets.objects().filterNot { it.getString("id") == setId }
                    performed.put("sets", JSONArray(remaining))
                    receipt.put("deleted_id", setId)
                } else {
                    payload.keys().forEach { if (it !in setOf("session_id", "session_exercise_id", "set_id")) set.put(it, payload.get(it)) }
                    check(set.isNull("load_value") || set.get("load_value") is String)
                    if (kind == "activity.set.add") sets.put(set)
                }
                bump(performed); bump(row)
                receipt.put("session_id", sessionId).put("session_exercise_id", performed.getString("id"))
                    .put("set_id", setId).put("session", copy(row))
            }
            "steps.set", "steps.clear" -> {
                val previous = override
                if (previous != null && expected.optInt(previous.getString("id"), -1) != previous.getInt("revision")) return conflict()
                override = JSONObject().put("id", previous?.getString("id") ?: UUID.randomUUID().toString())
                    .put("revision", (previous?.getInt("revision") ?: 0) + 1).put("active", kind == "steps.set")
                    .put("value", if (kind == "steps.set") payload.getInt("value") else JSONObject.NULL)
                if (kind == "steps.set") steps = payload.getInt("value")
                receipt.put("date", payload.getString("date")).put("override", copy(requireNotNull(override)))
            }
            else -> error("Unknown synthetic Activity operation: $kind")
        }
        receipts[key] = copy(receipt)
        bodies[key] = request.body
        if (disconnectAdmission) {
            disconnectAdmission = false
            return Response(0, byteArrayOf())
        }
        return json(200, receipt)
    }

    private fun snapshot() = copy(exercise).apply { remove("id"); remove("revision") }
    private fun bump(row: JSONObject) { row.put("revision", row.getInt("revision") + 1) }
    private fun conflict() = json(409, JSONObject().put("error", "conflict").put("message", "Данные изменились"))
    private fun copy(row: JSONObject) = JSONObject(row.toString())
    private fun json(status: Int, row: JSONObject) = Response(status, row.toString().toByteArray())
    private fun JSONArray.objects() = (0 until length()).map { getJSONObject(it) }

    companion object {
        const val DEVICE_REPORT_ID = "91000000-0000-4000-8000-000000000001"
        const val WORKOUT_ID = "91000000-0000-4000-8000-000000000002"
        const val SOURCE_ID = "91000000-0000-4000-8000-000000000003"
        const val EXERCISE_ID = "91000000-0000-4000-8000-000000000004"
        const val SNAPSHOT_ID = "91000000-0000-4000-8000-000000000005"
        val PERSONAL_PATHS = setOf("/v1/personal/overview", "/v1/personal/steps", "/v1/personal/history",
            "/v1/personal/resolved", "/v1/personal/effort", "/v1/personal/activity-reports")
    }
}
