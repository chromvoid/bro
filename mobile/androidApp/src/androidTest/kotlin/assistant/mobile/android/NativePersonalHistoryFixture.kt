package assistant.mobile.android

import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.util.UUID

/** Synthetic archived producer documents; mutations enforce the same revisions as the API. */
internal class NativePersonalHistoryFixture(private val personal: NativePersonalFixture) {
    val date get() = personal.date
    private var policyRevision = 2
    private var policySources = JSONArray()
    private val receipts = mutableMapOf<String, JSONObject>()
    private val bodies = mutableMapOf<String, String>()
    private var login: JSONObject? = null
    private var backfill: JSONObject? = null
    @Volatile var conflictNextPolicy = false
    @Volatile var lookupOnline = true
    @Volatile var disconnectAdmission = false

    fun accepts(path: String) = path.substringBefore('?').startsWith("/v1/personal/")

    @Synchronized fun respond(request: NativeLoopbackServer.Request): Pair<Int, JSONObject> {
        val path = request.path.substringBefore('?')
        if (request.method == "POST") return when {
            path == "/v1/personal/operations" -> operate(request)
            path.startsWith("/v1/personal/ddx/") -> ddxAction(request)
            else -> error("Unknown archived Personal mutation: $path")
        }
        check(request.method == "GET")
        return when {
            path == "/v1/personal/history" -> 200 to history(request.path)
            path == "/v1/personal/resolved" -> 200 to resolved(request.path)
            path == "/v1/personal/sources" -> 200 to JSONObject().put("sources", JSONArray().put(source()))
            // Archived custom sources are present, but this synthetic account has no ring collectors enrolled.
            path == "/v1/personal/rings" -> 200 to JSONObject().put("rings", JSONArray())
            path == "/v1/personal/steps" -> 200 to JSONObject().put("date", date).put("override", JSONObject.NULL)
            path.startsWith("/v1/personal/records/") && path.endsWith("/versions") ->
                200 to versions(path.removeSuffix("/versions").substringAfterLast('/'), sensor = false)
            path.startsWith("/v1/personal/sensor-samples/") && path.endsWith("/versions") ->
                200 to versions(path.removeSuffix("/versions").substringAfterLast('/'), sensor = true)
            path == "/v1/personal/sensor-samples" -> 200 to JSONObject()
                .put("from", Uri.parse(request.path).getQueryParameter("from"))
                .put("to", Uri.parse(request.path).getQueryParameter("to")).put("items", JSONArray().put(sample()))
                .put("count", 1).put("fingerprint", "synthetic-samples").put("next_cursor", JSONObject.NULL)
            path == "/v1/personal/raw-reports" -> 200 to JSONObject().put("from", date).put("to", date)
                .put("items", JSONArray()).put("count", 0).put("fingerprint", "synthetic-raw").put("next_cursor", JSONObject.NULL)
            path.startsWith("/v1/personal/analyses/") -> 200 to JSONObject().put("analysis", analysis())
            path == "/v1/personal/derived-result-versions" -> 200 to archive(request.path)
            path.startsWith("/v1/personal/derived-result-versions/") -> 200 to result()
            path.startsWith("/v1/personal/derived-backfills/") -> 200 to copy(requireNotNull(backfill))
            path.startsWith("/v1/personal/sync-runs/") -> 200 to syncRun()
            path.startsWith("/v1/personal/operations/by-key/") -> lookup(path.substringAfterLast('/'))
            path == "/v1/personal/ddx/status" -> 200 to JSONObject().put("available", true)
                .put("can_disconnect", true).put("active_login", login?.takeIf { it.optString("state") == "waiting_code" } ?: JSONObject.NULL)
            path.startsWith("/v1/personal/ddx/logins/") -> 200 to JSONObject().put("login", copy(requireNotNull(login)))
            else -> 200 to personal.read(request.path)
        }
    }

    @Synchronized fun writeCount() = receipts.size
    @Synchronized fun savedPolicyRevision() = policyRevision
    @Synchronized fun loginState() = login?.optString("state")

    private fun history(path: String): JSONObject {
        val query = Uri.parse(path)
        val kind = query.getQueryParameter("kind")
        val records = listOf(observation(STEPS_ID, "steps", "3200"), sleepRecord())
            .filter { kind == null || kind == "all" || it.getString("kind") == kind }
        val daily = resolved(path).getJSONArray("daily")
        val matchingDays = (0 until daily.length()).map { daily.getJSONObject(it) }
            .filter { kind == null || kind == "all" || it.getString("kind") == kind }
        return JSONObject().put("from", query.getQueryParameter("from") ?: date)
            .put("to", query.getQueryParameter("to") ?: date).put("records", JSONArray(records))
            .put("daily", JSONArray(matchingDays)).put("sources", JSONArray().put(source()))
            .put("source_analyses", JSONArray().put(analysis().apply { remove("payload") })).put("truncated", false)
    }

    private fun resolved(path: String): JSONObject {
        val query = Uri.parse(path)
        val page = personal.read("/v1/personal/resolved")
        val daily = JSONArray()
        fun day(kind: String, value: Any, unit: String, resolution: JSONObject) = JSONObject()
            .put("date", date).put("kind", kind).put("status", resolution.getString("status"))
            .put("value", value).put("unit", unit).put("resolution", resolution)
        daily.put(day("steps", "3200", "count", decision(STEPS_ID)))
        daily.put(day("weight", "72.45", "kg", decision(NativePersonalFixture.WEIGHT_ID)))
        val sleep = decision(SLEEP_ID).put("status", "partial").put("quality", JSONObject()
            .put("flags", JSONArray().put("unknown_sleep_stage")))
            .put("sessions", JSONArray().put(JSONObject().put("id", SLEEP_ID).put("source_id", SOURCE_ID)
                .put("source_name", SOURCE_NAME).put("start_at", at("00:35")).put("end_at", at("05:33"))
                .put("asleep_minutes", JSONObject.NULL).put("stages", JSONArray()
                    .put(stage("light", "00:35", "02:00")).put(stage("unknown:1", "02:00", "05:33")))))
            .put("sleep_analysis", sleepAnalysis())
        daily.put(day("sleep", JSONObject.NULL, "min", sleep))
        daily.put(day("heart_rate", "61", "bpm", decision(SAMPLE_ID)))
        page.put("from", query.getQueryParameter("from") ?: date).put("to", query.getQueryParameter("to") ?: date)
            .put("daily", daily).put("series", JSONArray().put(observation(STEPS_ID, "steps", "3200")
                .put("metric", "steps").put("value", "3200").put("unit", "count"))
                .put(sleepRecord().put("metric", "sleep").put("value", JSONObject.NULL).put("unit", "min"))
                .put(sample())).put("policies", JSONArray().put(policy()))
        return page
    }

    private fun decision(id: String) = JSONObject().put("status", "available")
        .put("selected", JSONArray().put(JSONObject().put("id", id).put("revision", 2)
            .put("source_id", SOURCE_ID).put("source_name", SOURCE_NAME)))
        .put("alternatives", JSONArray().put(JSONObject().put("id", "superseded-$id").put("source_id", SOURCE_ID)
            .put("reason", "superseded_report").put("superseded_by", id)))
        .put("resolver_version", "resolution-v2").put("fingerprint", "synthetic-$id")
        .put("contributions", JSONArray().put(JSONObject().put("source_id", SOURCE_ID).put("source_name", SOURCE_NAME)
            .put("count", 1).put("value", "3200").put("spans", JSONArray())))

    private fun observation(id: String, kind: String, value: String) = JSONObject().put("id", id)
        .put("source_id", SOURCE_ID).put("source_name", SOURCE_NAME).put("external_id", "synthetic-$kind")
        .put("kind", kind).put("start_at", at("07:00")).put("end_at", at("07:00"))
        .put("timezone", "Europe/Moscow").put("local_date", date).put("method", "device_report")
        .put("data", JSONObject().put("value", value)).put("quality", JSONArray()).put("revision", 2)
        .put("origin", JSONObject().put("provider", "synthetic")).put("received_at", at("08:00"))

    private fun sleepRecord() = observation(SLEEP_ID, "sleep", "85").put("start_at", at("00:35"))
        .put("end_at", at("05:33")).put("data", JSONObject().put("value", JSONObject.NULL)
            .put("stages", JSONArray().put(stage("light", "00:35", "02:00")).put(stage("unknown:1", "02:00", "05:33"))))

    private fun sample() = JSONObject().put("id", SAMPLE_ID).put("source_id", SOURCE_ID).put("source_name", SOURCE_NAME)
        .put("metric", "heart_rate").put("external_id", "synthetic-heart-rate").put("start_at", at("07:00"))
        .put("end_at", at("07:00")).put("timezone", "Europe/Moscow").put("local_date", date)
        .put("value", "61").put("reported_min", JSONObject.NULL).put("reported_max", JSONObject.NULL).put("unit", "bpm")
        .put("quality", JSONArray()).put("revision", 2).put("excluded", false).put("annotation_revision", 0)
        .put("origin", JSONObject().put("provider", "synthetic")).put("received_at", at("08:00"))

    private fun versions(id: String, sensor: Boolean): JSONObject {
        val current = if (sensor) sample() else if (id == SLEEP_ID) sleepRecord() else observation(STEPS_ID, "steps", "3200")
        val previous = copy(current).put("revision", 1).put("legacy_note", "untouched archived evidence")
        if (sensor) previous.put("value", "59") else if (id == STEPS_ID) previous.put("data", JSONObject().put("value", "3000"))
        return JSONObject().put("id", id).put(if (sensor) "metric" else "kind", current.getString(if (sensor) "metric" else "kind"))
            .put("source_id", SOURCE_ID).put("source_name", SOURCE_NAME).put("source_status", "connected")
            .put("current_revision", 2).put("count", 2).put("fingerprint", "archive-$id")
            .put("versions", JSONArray().put(version(current, 2)).put(version(previous, 1)))
            .put("annotation", JSONObject().put("excluded", false).put("corrected", false).put("revision", 0)
                .put("observation", JSONObject.NULL)).put("next_cursor", JSONObject.NULL)
    }

    private fun version(snapshot: JSONObject, revision: Int) = JSONObject().put("revision", revision).put("snapshot", snapshot)
        .put("operation_id", "88888888-8888-4888-8888-888888888888").put("created_at", at("08:00"))

    private fun sleepAnalysis(): JSONObject {
        val segment = JSONObject().put("start_at", at("00:35")).put("end_at", at("02:00"))
            .put("state", "sleep").put("stage", "light").put("reason", "reported").put("evidence", JSONArray())
            .put("selected_refs", JSONArray()).put("flags", JSONArray())
        val episode = JSONObject().put("start_at", at("00:35")).put("end_at", at("05:33"))
            .put("status", "partial").put("window_minutes", 298).put("position_changes", JSONArray())
            .put("asleep_minutes", 85).put("complete_asleep_minutes", JSONObject.NULL).put("awake_minutes", 0)
            .put("unknown_minutes", 213).put("conflict_minutes", 0).put("classification_coverage", 85.0 / 298)
            .put("bounds", JSONObject().put("min_minutes", 85).put("max_minutes", 298))
            .put("segments", JSONArray().put(segment).put(copy(segment).put("start_at", at("02:00"))
                .put("end_at", at("05:33")).put("state", "unknown").put("stage", JSONObject.NULL)))
            .put("reports", JSONArray().put(JSONObject().put("id", SLEEP_ID).put("type", "record").put("revision", 2)
                .put("source_id", SOURCE_ID).put("source_name", SOURCE_NAME).put("start_at", at("00:35"))
                .put("end_at", at("05:33")).put("reported_asleep_minutes", JSONObject.NULL)))
            .put("stage_minutes", JSONObject().put("light", 85))
            .put("flags", JSONArray().put("unknown_sleep_stage")).put("forecast", JSONObject()
                .put("status", "unavailable").put("algorithm_version", "sleep-analogs-v1")
                .put("estimated_asleep_minutes", JSONObject.NULL).put("estimated_missing_minutes", JSONObject.NULL)
                .put("training_nights", 0).put("reason", "insufficient_history"))
        return JSONObject().put("version", "sleep-analysis-v1").put("fingerprint", "synthetic-sleep-analysis")
            .put("status", "partial").put("computed_at", at("08:00"))
            .put("summary", JSONObject().put("window_minutes", 298).put("asleep_minutes", 85).put("awake_minutes", 0)
                .put("unknown_minutes", 213).put("conflict_minutes", 0).put("classification_coverage", 85.0 / 298)
                .put("complete_asleep_minutes", JSONObject.NULL).put("forecast_asleep_minutes", JSONObject.NULL))
            .put("episodes", JSONArray().put(episode)).put("segments", episode.getJSONArray("segments"))
    }

    private fun analysis() = JSONObject().put("id", ANALYSIS_ID).put("source_id", SOURCE_ID)
        .put("source_name", SOURCE_NAME).put("provider_record_id", "synthetic-body-analysis")
        .put("measured_at", at("07:00")).put("revision", 2).put("received_at", at("08:00"))
        .put("payload", JSONObject().put("weight", "72.45").put("body_fat_percent", "18.20"))

    private fun source() = JSONObject().put("id", SOURCE_ID).put("name", SOURCE_NAME)
        .put("kinds", JSONArray(listOf("weight", "steps", "sleep", "heart_rate")))
        .put("metric_keys", JSONArray(listOf("weight", "steps", "sleep", "heart_rate")))
        .put("status", "connected").put("revision", 3).put("created_at", at("06:00"))
        .put("last_received_at", at("08:00")).put("last_measured_at", at("07:00"))
        .put("device", JSONObject().put("kind", "custom")).put("connection", JSONObject.NULL)

    private fun policy() = JSONObject().put("id", POLICY_ID).put("metric", "steps").put("source_ids", JSONArray(policySources.toString()))
        .put("effective_from", date).put("revision", policyRevision).put("version", policyRevision).put("baseline", false)

    private fun archive(path: String): JSONObject {
        val query = Uri.parse(path)
        return JSONObject().put("from", query.getQueryParameter("from") ?: date).put("to", query.getQueryParameter("to") ?: date)
            .put("time_zone", "Europe/Moscow").put("scope", "all").put("items", JSONArray().put(result()))
            .put("next_cursor", JSONObject.NULL).put("fingerprint", "synthetic-derived-archive")
            .put("persistence", "immutable_versions").put("total_versions", 1)
            .put("refresh", JSONObject().put("pending", false).put("pending_runs", 0).put("failed_runs", 0))
    }

    private fun result(): JSONObject {
        val score = personal.read("/v1/personal/derived-scores").getJSONArray("days").getJSONObject(0)
            .getJSONArray("scores").getJSONObject(0)
        score.put("components", JSONArray().put(JSONObject().put("id", "hr_7d").put("value", JSONArray().put(55).put(60))
            .put("unit", "bpm").put("quality", JSONArray()).put("synthetic", false)))
            .put("input_revisions", JSONArray()).put("baseline", JSONObject.NULL).put("details", JSONObject())
        return JSONObject().put("result_id", RESULT_ID).put("version", 1).put("definition_id", score.getString("definition_id"))
            .put("formula_version", score.getString("formula_version")).put("source_scope", "ring")
            .put("date", date).put("evaluation", "day").put("time_zone", "Europe/Moscow").put("as_of", at("08:00"))
            .put("temporal_basis", "measurement_capture").put("score", score)
            .put("definition_snapshot", score.getJSONObject("definition_snapshot"))
            .put("normalized_inputs", JSONObject()).put("context_revisions", JSONArray())
            .put("input_adapter_version", "soul-inputs-1.0.1214-v4").put("implementation_digest", "b".repeat(64))
            .put("implementation_source", JSONObject().put("format", "git_blob_sha1").put("files", JSONArray()))
            .put("operation_id", "88888888-8888-4888-8888-888888888888").put("created_at", at("08:00")).put("is_current", true)
    }

    private fun lookup(key: String) = when {
        !lookupOnline -> 503 to JSONObject()
        key in receipts -> 200 to copy(receipts.getValue(key))
        else -> 404 to JSONObject()
    }

    private fun operate(request: NativeLoopbackServer.Request): Pair<Int, JSONObject> {
        val key = requireNotNull(request.headers["idempotency-key"])
        UUID.fromString(key)
        receipts[key]?.let { check(bodies[key] == request.body); return 200 to copy(it) }
        val command = request.json()
        val kind = command.getString("kind")
        val payload = command.getJSONObject("payload")
        val expected = command.getJSONObject("expected_revisions")
        val receipt = JSONObject().put("operation_id", UUID.randomUUID().toString()).put("kind", kind)
        when (kind) {
            "personal.resolution.policy.set" -> {
                check(payload.getString("metric") == "steps")
                check(payload.getString("effective_from") == date)
                if (conflictNextPolicy) {
                    conflictNextPolicy = false
                    policyRevision++
                    return 409 to JSONObject().put("error", "conflict").put("message", "Порядок источников уже изменён")
                }
                if (payload.getInt("revision") != policyRevision || expected.optInt(POLICY_ID, -1) != policyRevision)
                    return 409 to JSONObject().put("error", "conflict")
                policySources = JSONArray(payload.getJSONArray("source_ids").toString())
                policyRevision++
                receipt.put("policy", policy())
            }
            "personal.derived.backfill" -> {
                check(expected.length() == 0)
                val from = payload.getString("from")
                val to = payload.getString("to")
                val days = ChronoUnit.DAYS.between(LocalDate.parse(from), LocalDate.parse(to)).toInt() + 1
                receipt.put("derivation_run_id", BACKFILL_ID).put("state", "queued").put("from", from).put("to", to)
                    .put("as_of", at("08:00")).put("scheduled_days", days).put("results_saved", 0).put("pending", true)
                backfill = JSONObject().put("run_id", BACKFILL_ID).put("state", "succeeded").put("from", from).put("to", to)
                    .put("next_date", LocalDate.parse(to).plusDays(1).toString()).put("as_of", at("08:00"))
                    .put("time_zone", payload.getString("time_zone")).put("source_scope", JSONObject.NULL).put("trigger", "manual")
                    .put("processed_days", days).put("scheduled_days", days).put("results_saved", days)
                    .put("results_unchanged", 0).put("error", JSONObject.NULL).put("created_at", at("08:00"))
                    .put("finished_at", at("08:01"))
            }
            else -> error("Unknown archived Personal operation: $kind")
        }
        receipts[key] = receipt
        bodies[key] = request.body
        if (disconnectAdmission) { disconnectAdmission = false; return 0 to JSONObject() }
        return 201 to copy(receipt)
    }

    private fun ddxAction(request: NativeLoopbackServer.Request): Pair<Int, JSONObject> {
        UUID.fromString(requireNotNull(request.headers["idempotency-key"]))
        val path = request.path
        val body = request.json()
        when {
            path == "/v1/personal/ddx/logins" -> {
                check(body.getString("phone") == "+79990001234")
                check(body.getString("mode") == "connect")
                login = JSONObject().put("id", LOGIN_ID).put("request_key", request.headers["idempotency-key"])
                    .put("state", "waiting_code").put("phone_masked", "+7 *** ***-12-34")
                    .put("expires_at", Instant.now().plusSeconds(300).toString()).put("resend_at", Instant.now().minusSeconds(1).toString())
                    .put("can_resend", true).put("source_id", JSONObject.NULL).put("error", JSONObject.NULL)
            }
            path.endsWith("/code") -> {
                check(body.getString("code") == CODE)
                requireNotNull(login).put("state", "waiting_code").put("error", "invalid_verification_code")
            }
            path.endsWith("/resend") -> {
                check(body.length() == 0)
                requireNotNull(login).put("error", JSONObject.NULL)
            }
            path.endsWith("/cancel") -> {
                check(body.length() == 0)
                requireNotNull(login).put("state", "cancelled").put("error", JSONObject.NULL)
            }
            else -> error("Unknown synthetic DDX action: $path")
        }
        return 200 to JSONObject().put("login", copy(requireNotNull(login)))
    }

    private fun syncRun() = JSONObject().put("id", "synthetic-sync").put("source_id", SOURCE_ID).put("state", "succeeded")
        .put("outcome", JSONObject().put("new_analyses", 1).put("partial", false))
    private fun stage(name: String, start: String, end: String) = JSONObject().put("stage", name)
        .put("start_at", at(start)).put("end_at", at(end))
    private fun at(time: String) = "${date}T${time}:00Z"
    private fun copy(value: JSONObject) = JSONObject(value.toString())

    companion object {
        const val SOURCE_ID = "99999999-9999-4999-8999-999999999999"
        const val SOURCE_NAME = "Synthetic archive"
        const val STEPS_ID = "11111111-2222-4333-8444-555555555555"
        const val SLEEP_ID = "11111111-2222-4333-8444-666666666666"
        const val SAMPLE_ID = "11111111-2222-4333-8444-777777777777"
        const val POLICY_ID = "11111111-2222-4333-8444-888888888888"
        const val ANALYSIS_ID = "11111111-2222-4333-8444-999999999999"
        const val LOGIN_ID = "22222222-2222-4222-8222-222222222222"
        const val CODE = "654321"
        val RESULT_ID = "a".repeat(64)
        val BACKFILL_ID = "c".repeat(64)
    }
}
