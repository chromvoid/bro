package assistant.mobile.android

import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

/** Synthetic Personal API: decimal/null/provenance shapes match the web resolver fixtures. */
internal class NativePersonalFixture {
    val date: String = LocalDate.now(ZoneId.of("Europe/Moscow")).toString()
    private val profile = JSONObject().put("user_id", USER_ID).put("timezone", "Europe/Moscow")
        .put("birth_date", "1990-01-01").put("gender", "male").put("gender_origin", "manual")
        .put("height_cm", "180.00").put("display_units", "metric").put("revision", 3)
    private val preferences = JSONObject().put("weight", JSONObject().put("kind", "weight")
        .put("target", "70.00").put("revision", 2))
    private var weight = "72.45"
    private var weightRecord = observation(WEIGHT_ID, "weight", weight, "self_report")
    private val calipers = JSONArray()
    private val receipts = mutableMapOf<String, JSONObject>()
    private val bodies = mutableMapOf<String, String>()
    @Volatile var conflictNext = false
    @Volatile var lookupOnline = true
    @Volatile var disconnectAdmission = false

    @Synchronized fun writeCount(): Int = receipts.size
    @Synchronized fun profile(): JSONObject = copy(profile)
    @Synchronized fun preference(kind: String): JSONObject = copy(preferences.getJSONObject(kind))

    @Synchronized fun overview(day: String = date): JSONObject {
        val metrics = JSONObject()
        fun metric(kind: String, unit: String, value: Any, records: JSONArray = JSONArray()): JSONObject =
            JSONObject().put("kind", kind).put("unit", unit).put("value", value)
                .put("status", if (value == JSONObject.NULL) "no_data" else "available")
                .put("target", preferences.optJSONObject(kind)?.opt("target") ?: JSONObject.NULL)
                .put("records", records)
        metrics.put("weight", metric("weight", "kg", weight, JSONArray().put(copy(weightRecord))))
        metrics.put("sleep", metric("sleep", "min", JSONObject.NULL).put("complete", false)
            .put("resolution", sleepResolution(day)))
        metrics.put("steps", metric("steps", "count", "3200"))
        metrics.put("active_minutes", metric("active_minutes", "min", JSONObject.NULL))
        metrics.put("active_energy", metric("active_energy", "kcal", "250"))
        metrics.put("distance", metric("distance", "m", "2400"))
        listOf("body_fat_percent" to "%", "body_fat_mass" to "kg", "skeletal_muscle_mass" to "kg",
            "fat_free_mass" to "kg", "body_water" to "kg", "body_protein" to "kg", "body_minerals" to "kg",
            "bmi" to "kg/m2", "basal_metabolic_rate" to "kcal", "visceral_fat_level" to "level").forEach { (kind, unit) ->
            metrics.put(kind, metric(kind, unit, if (kind == "body_fat_percent") "18.20" else JSONObject.NULL))
        }
        metrics.put("nutrition", JSONObject().put("status", "available").put("value", "1234.50")
            .put("unit", "kcal").put("complete", false).put("target", "2000.00")
            .put("remaining", "765.50").put("estimated_count", 1).put("pending_count", 0))
        return JSONObject().put("date", day).put("timezone", profile.getString("timezone"))
            .put("profile", copy(profile)).put("preferences", copy(preferences)).put("metrics", metrics)
            .put("sources", JSONArray().put(source()))
    }

    @Synchronized fun read(path: String): JSONObject = when (path.substringBefore('?')) {
        "/v1/personal/overview" -> overview(Uri.parse(path).getQueryParameter("date") ?: date)
        "/v1/personal/resolved" -> resolved(Uri.parse(path).getQueryParameter("from") ?: date)
        "/v1/personal/derived-scores" -> derived(path)
        "/v1/personal/caliper" -> JSONObject().put("records", JSONArray(calipers.toString()))
            .put("truncated", false).put("limit", 100)
        "/v1/personal/metrics/catalog" -> JSONObject().put("metrics", JSONArray()
            .put(JSONObject().put("key", "weight").put("label", "Вес").put("unit", "kg").put("shape", "point")))
            .put("resolver_version", "resolution-v2")
        else -> error("Unknown synthetic personal read: $path")
    }

    @Synchronized fun lookup(key: String): Pair<Int, JSONObject> = when {
        !lookupOnline -> 503 to JSONObject()
        key !in receipts -> 404 to JSONObject()
        else -> 200 to copy(receipts.getValue(key))
    }

    /** Returns null only after durable admission, deliberately losing the HTTP receipt. */
    @Synchronized fun operate(request: NativeLoopbackServer.Request): Pair<Int, JSONObject>? {
        val key = requireNotNull(request.headers["idempotency-key"])
        UUID.fromString(key)
        receipts[key]?.let {
            check(bodies.getValue(key) == request.body) { "A Personal key changed its body" }
            return 200 to copy(it)
        }
        val body = request.json()
        val kind = body.getString("kind")
        val payload = body.getJSONObject("payload")
        val expected = body.getJSONObject("expected_revisions")
        if (conflictNext) {
            conflictNext = false
            when (kind) {
                "personal.profile.update" -> profile.put("height_cm", "181.00").put("revision", profile.getInt("revision") + 1)
                "personal.preference.set" -> preferences.getJSONObject(payload.getString("kind")).let {
                    it.put("target", "69.00").put("revision", it.getInt("revision") + 1)
                }
                else -> error("Conflict fixture requires a revision-bound operation")
            }
            return conflict()
        }
        val receipt = JSONObject().put("operation_id", UUID.randomUUID().toString()).put("kind", kind)
            .put("event_seq", receipts.size + 1)
        when (kind) {
            "personal.profile.update" -> {
                if (expected.optInt(USER_ID, -1) != profile.getInt("revision")) return conflict()
                check(expected.length() == 1)
                payload.keys().forEach { field -> profile.put(field, payload.get(field)) }
                if (payload.has("gender")) profile.put("gender_origin", "manual")
                profile.put("revision", profile.getInt("revision") + 1)
                receipt.put("profile", copy(profile))
            }
            "personal.preference.set" -> {
                check(expected.length() == 0)
                val metric = payload.getString("kind")
                val previous = preferences.optJSONObject(metric)
                if (payload.getInt("revision") != (previous?.getInt("revision") ?: 0)) return conflict()
                val next = JSONObject().put("kind", metric).put("target", payload.get("target"))
                    .put("revision", payload.getInt("revision") + 1)
                preferences.put(metric, next)
                receipt.put("preference", copy(next))
            }
            "personal.weight.add" -> {
                check(expected.length() == 0)
                check(payload.get("weight_kg") is String)
                weight = payload.getString("weight_kg")
                weightRecord = observation(UUID.randomUUID().toString(), "weight", weight, "self_report")
                    .put("start_at", payload.getString("measured_at")).put("end_at", payload.getString("measured_at"))
                receipt.put("measurement_id", weightRecord.getString("id")).put("weight_kg", weight)
                    .put("record", copy(weightRecord))
            }
            "personal.caliper.add" -> {
                check(expected.length() == 0)
                val folds = payload.getJSONObject("folds_mm")
                folds.keys().forEach { check(folds.get(it) is String) }
                // Published-density fixture from tests/test_caliper.py, not a client calculation.
                check(folds.length() == 3 && folds.getString("chest").toDouble() == 12.0 &&
                    folds.getString("abdomen").toDouble() == 25.0 && folds.getString("thigh").toDouble() == 18.0)
                check((payload.opt("gender") ?: profile.get("gender")) == "male")
                check(payload.optInt("age_years", 36) == 36)
                val measurement = JSONObject().put("id", UUID.randomUUID().toString()).put("source_id", "caliper")
                    .put("measured_at", payload.getString("measured_at")).put("timezone", profile.getString("timezone"))
                    .put("folds_mm", copy(folds)).put("gender", payload.opt("gender") ?: profile.get("gender"))
                    .put("age_years", payload.opt("age_years") ?: 36).put("note", payload.opt("note") ?: JSONObject.NULL)
                    .put("body_fat_percent", "17.21").put("body_fat_record_id", UUID.randomUUID().toString())
                    .put("revision", 1).put("calculation", JSONObject().put("status", "calculated")
                        .put("method", "jackson_pollock_3_siri").put("algorithm_version", "jackson_pollock_3_siri_v1")
                        .put("missing_fields", JSONArray()))
                calipers.put(measurement)
                receipt.put("measurement_id", measurement.getString("id")).put("measurement", copy(measurement))
            }
            else -> error("Unknown synthetic personal operation: $kind")
        }
        bodies[key] = request.body
        receipts[key] = receipt
        if (disconnectAdmission) {
            disconnectAdmission = false
            return null
        }
        return 201 to copy(receipt)
    }

    private fun resolved(day: String): JSONObject {
        // Same null scalar / known range case as resolverFixtures.page(): no fabricated SpO2 mean.
        val range = JSONObject().put("status", "available").put("selected", JSONArray().put(
            JSONObject().put("id", "sample").put("revision", 2).put("source_id", "ring").put("source_name", "Synthetic ring")))
            .put("alternatives", JSONArray()).put("resolver_version", "resolution-v2").put("fingerprint", "synthetic-range")
            .put("protocol", JSONObject().put("verified", false).put("flags", JSONArray().put("protocol_unverified")))
            .put("statistics", JSONObject().put("ranges", JSONObject().put("count", 1).put("mean", JSONObject.NULL)
                .put("min", 94).put("max", 99).put("covered_seconds", 3600)))
        fun daily(kind: String, value: Any, unit: String, resolution: JSONObject) = JSONObject()
            .put("date", day).put("kind", kind).put("status", resolution.getString("status"))
            .put("value", value).put("unit", unit).put("resolution", resolution)
        return JSONObject().put("from", day).put("to", day).put("timezone", "Europe/Moscow")
            .put("computed_at", "${day}T12:00:00Z").put("fingerprint", "synthetic-personal-page")
            .put("next_cursor", JSONObject.NULL).put("series", JSONArray()).put("policies", JSONArray())
            .put("daily", JSONArray().put(daily("spo2", JSONObject.NULL, "%", range))
                .put(daily("heart_rate", "61", "bpm", copy(range).put("fingerprint", "synthetic-hr")))
                .put(daily("sleep", JSONObject.NULL, "min", sleepResolution(day))))
    }

    private fun sleepResolution(day: String): JSONObject {
        val nightStart = "${day}T00:35:00Z"
        val nightEnd = "${day}T05:33:00Z"
        val session = JSONObject().put("id", "night").put("source_id", "ring").put("source_name", "Synthetic ring")
            .put("start_at", nightStart).put("end_at", nightEnd).put("asleep_minutes", JSONObject.NULL)
            .put("stages", JSONArray().put(JSONObject().put("stage", "unknown:1")
                .put("start_at", nightStart).put("end_at", nightEnd)))
        return JSONObject().put("status", "partial").put("selected", JSONArray().put(copy(session)))
            .put("alternatives", JSONArray()).put("sessions", JSONArray().put(session))
            .put("resolver_version", "resolution-v2").put("fingerprint", "synthetic-sleep")
            .put("quality", JSONObject().put("flags", JSONArray().put("unknown_sleep_stage")))
    }

    private fun derived(path: String): JSONObject {
        val query = Uri.parse(path)
        // Personal asks for one day (from == to); history ranges keep the fixture day their result ids belong to.
        val day = query.getQueryParameter("from")?.takeIf { it == query.getQueryParameter("to") } ?: date
        val context = JSONObject().put("evaluation", "day").put("date", day).put("time_zone", "Europe/Moscow")
            .put("as_of", "${day}T08:00:00Z").put("source_id", "ring").put("source_name", "Synthetic ring")
        val score = JSONObject().put("definition_id", "soul-rhr-energy").put("formula_version", "soul-1.0.1214-v1")
            .put("qualification", "qualified").put("status", "available").put("value", 55).put("unit", "bpm")
            .put("missing", JSONArray()).put("quality", JSONArray()).put("context", context)
            .put("confidence", JSONObject().put("level", "complete").put("available_inputs", 1).put("required_inputs", 1))
            .put("coverage", JSONObject().put("observed", 1).put("expected", 1).put("ratio", 1))
            .put("fingerprint", "rhr-score").put("result_id", "a".repeat(64)).put("version", 1)
            .put("evaluated_at", context.getString("as_of")).put("temporal_basis", "measurement_capture")
            .put("input_adapter_version", "soul-inputs-1.0.1214-v4").put("implementation_digest", "b".repeat(64))
            .put("details_mode", "archived").put("series_count", 0).put("definition_snapshot", JSONObject()
                .put("id", "soul-rhr-energy").put("label", "Пульс покоя · Energy Soul").put("unit", "bpm")
                .put("formula_version", "soul-1.0.1214-v1").put("qualification", "qualified"))
        return JSONObject().put("from", query.getQueryParameter("from") ?: date).put("to", query.getQueryParameter("to") ?: date)
            .put("time_zone", query.getQueryParameter("time_zone") ?: "Europe/Moscow").put("as_of", context.getString("as_of"))
            .put("definitions", JSONArray()).put("days", JSONArray().put(JSONObject().put("date", day)
                .put("scores", JSONArray().put(score)))).put("fingerprint", "summary-collection")
            .put("persistence", "immutable_versions").put("details_mode", "archived").put("total_summaries", 1)
            .put("next_cursor", JSONObject.NULL).put("limit", 250).put("preview", JSONObject.NULL)
            .put("refresh", JSONObject().put("pending", false).put("pending_runs", 0).put("failed_runs", 0)
                .put("owner_pending", false).put("owner_pending_runs", 0).put("owner_failed_runs", 0))
    }

    private fun observation(id: String, kind: String, value: String, method: String): JSONObject = JSONObject()
        .put("id", id).put("source_id", JSONObject.NULL).put("source_name", JSONObject.NULL).put("kind", kind)
        .put("start_at", "${date}T07:00:00Z").put("end_at", "${date}T07:00:00Z")
        .put("timezone", "Europe/Moscow").put("local_date", date).put("method", method)
        .put("data", JSONObject().put("value", value)).put("quality", JSONArray()).put("revision", 1)
        .put("origin", JSONObject.NULL).put("received_at", "${date}T07:00:00Z")

    private fun source(): JSONObject = JSONObject().put("id", "ring").put("name", "Synthetic ring")
        .put("kinds", JSONArray().put("sleep").put("heart_rate").put("spo2"))
        .put("status", "connected").put("revision", 1)

    private fun conflict() = 409 to JSONObject().put("error", "conflict").put("message", "Данные изменились")
    private fun copy(value: JSONObject) = JSONObject(value.toString())

    companion object {
        const val USER_ID = "synthetic-user-a"
        const val WEIGHT_ID = "77777777-7777-4777-8777-777777777777"
        val READ_PATHS = setOf("/v1/personal/overview", "/v1/personal/resolved", "/v1/personal/derived-scores",
            "/v1/personal/caliper", "/v1/personal/metrics/catalog")
    }
}
