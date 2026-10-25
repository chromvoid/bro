package assistant.mobile.core.activity

import assistant.mobile.core.NativeJson
import assistant.mobile.core.personal.PersonalResolvedDay
import assistant.mobile.core.personal.decodePersonalMetric
import assistant.mobile.core.personal.decodePersonalResolution
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.serialization.json.*
import kotlin.time.Instant

fun decodeActivitySessions(bytes: ByteArray): ActivitySessions = activityObject(bytes).let { row -> ActivitySessions(row.array("sessions").map { decodeActivitySession(it.jsonObject) }, row.optionalBoolean("truncated") ?: false) }
fun decodeActivitySession(bytes: ByteArray): ActivitySession = decodeActivitySession(activityObject(bytes).obj("session"))
fun decodeActivitySession(row: JsonObject): ActivitySession = ActivitySession(row.text("id"), row.revision("revision"), row.text("kind").also { require(it in ACTIVITY_KINDS) }, row.optionalText("title"), row.date("date"), row.zone("timezone"), row.optionalInstant("started_at"), row.amount("duration_minutes"), row.amount("distance_km"), row.optionalText("note"), row.array("exercises").map { value ->
    val exercise = value.jsonObject
    SessionExercise(exercise.text("id"), exercise.revision("revision"), exercise.text("exercise_id"), exercise.int("position"), decodeExerciseSnapshot(exercise.obj("snapshot")), exercise.optionalText("note"), exercise.array("sets").map { setValue ->
        val set = setValue.jsonObject
        ActivitySet(set.text("id"), set.revision("revision"), set.int("position"), set.text("kind").also { require(it in setOf("warmup", "working")) }, set.optionalInt("repetitions"), set.amount("load_value"), set.text("load_unit").also { require(it in setOf("kg", "lb")) }, set.int("implement_count").also { require(it > 0) }, set.optionalText("note"))
    })
})
fun decodeActivityExercises(bytes: ByteArray): List<ExerciseDefinition> = activityObject(bytes).array("exercises").map { decodeExerciseDefinition(it.jsonObject) }
fun decodeExerciseSnapshot(row: JsonObject): ExerciseSnapshot = ExerciseSnapshot(row.text("name"), row.optionalText("equipment"), row.optionalText("variant"), row.text("load_basis").also { require(it in ACTIVITY_LOAD_BASES) })
fun decodeExerciseDefinition(row: JsonObject): ExerciseDefinition = decodeExerciseSnapshot(row).let { ExerciseDefinition(row.text("id"), row.revision("revision"), it.name, it.equipment, it.variant, it.loadBasis) }
fun decodeActivitySummary(bytes: ByteArray): ActivitySummary = activityObject(bytes).let { row ->
    fun values(value: JsonObject): ActivitySummaryValues = ActivitySummaryValues(value.int("sessions"), value.amount("duration_minutes"), value.int("missing_duration_sessions"), value.obj("distance_km_by_kind").mapValues { amount(it.value) }, value.int("working_sets"))
    ActivitySummary(row.date("from"), row.date("to"), values(row.obj("totals")), row.array("daily").map {
        val day = it.jsonObject
        val value = values(day)
        ActivitySummaryDay(day.date("date"), value.sessions, value.durationMinutes, value.missingDurationSessions, value.distanceKmByKind, value.workingSets)
    })
}
fun decodeExerciseProgress(bytes: ByteArray): ExerciseProgress = activityObject(bytes).let { row ->
    ExerciseProgress(decodeExerciseDefinition(row.obj("exercise")), row.date("from"), row.date("to"), row.array("series").map { seriesValue ->
        val series = seriesValue.jsonObject
        ExerciseProgressSeries(decodeExerciseSnapshot(series.obj("snapshot")), series.text("load_unit").also { require(it == "kg") }, series.int("implement_count"), series.array("points").map {
            val point = it.jsonObject
            ExerciseProgressPoint(point.date("date"), point.text("session_id"), point.text("session_exercise_id"), point.text("set_id"), point.optionalInt("repetitions"), point.amount("load_value"), point.amount("load_kg"))
        })
    }, row.array("daily").map { val day = it.jsonObject; ExerciseProgressDay(day.date("date"), day.int("working_sets")) })
}
fun decodeActivitySteps(bytes: ByteArray): ActivityStepsReply = activityObject(bytes).let { row -> ActivityStepsReply(row.date("date"), row.optionalObject("override")?.let { ActivityStepsOverride(it.text("id"), it.revision("revision"), it.optionalInt("value"), it.boolean("active")) }) }
private fun decodeHeartRate(row: JsonObject): WorkoutHeartRate = WorkoutHeartRate(row.number("mean"), row.number("max"))
private fun decodeEffort(row: JsonObject): WorkoutEffortBasis = WorkoutEffortBasis(row.text("rest"), row.text("max"), row.text("basis"))
fun decodeActivityEffort(bytes: ByteArray): ActivityEffort? = activityObject(bytes).optionalObject("effort")?.let { ActivityEffort(it.text("source_name"), decodeHeartRate(it.obj("heart_rate")), it.numbers("zone_seconds"), it.amount("trimp"), it.optionalObject("effort")?.let(::decodeEffort)) }
private fun decodePauses(row: JsonObject): List<WorkoutPause> = row.optionalArray("pauses")?.map { val pause = it.jsonObject; WorkoutPause(pause.instant("start_at"), pause.instant("end_at")) } ?: emptyList()
private fun decodeSportPlan(row: JsonObject): WorkoutSportPlan = WorkoutSportPlan(row.int("requested_id"), row.text("name"), row.text("family"), row.optionalInt("candidate_wire_mode"), row.optionalInt("actual_wire_mode"), row.text("qualification"), row.text("evidence_ref"), row.optionalText("mapping_basis"), row.optionalInt("resolved_activity_id"))
fun decodeDeviceWorkout(row: JsonObject, date: String = row.date("local_date")): DeviceWorkout {
    val data = row.obj("data")
    val origin = row.optionalObject("origin")
    val originSource = origin?.optionalText("source")
    return DeviceWorkout(row.text("id"), row.revision("revision"), row.optionalText("source_id"), row.optionalText("source_name"), row.instant("start_at"), row.instant("end_at"), date, row.optionalText("timezone")?.also { TimeZone.of(it) }, originSource,
        if (originSource == "phone_recorded") origin?.optionalText("record_id") else null, data.text("sport_type"), data.nonnegative("duration_seconds"), data.nonnegative("active_duration_seconds"), data.nonnegative("steps"), data.nonnegative("distance_meters"), data.optionalObject("heart_rate")?.let(::decodeHeartRate), data.numbers("zone_seconds"), data.amount("trimp"), data.optionalObject("effort")?.let(::decodeEffort), decodePauses(data), data.optionalObject("sport_plan")?.let(::decodeSportPlan), originDetails = origin?.optionalObject("details"))
}
private fun decodeDay(row: JsonObject): PersonalResolvedDay {
    val kind = row.text("kind")
    val defaults = mapOf("unit" to JsonPrimitive(when (kind) { "steps", "running_steps" -> "steps"; "active_minutes" -> "minutes"; "distance" -> "m"; "active_energy" -> "kcal"; else -> "workouts" }))
    return PersonalResolvedDay(row.date("date"), decodePersonalMetric(JsonObject(defaults + row)))
}
fun decodeActivityResolved(bytes: ByteArray): ActivityResolvedPage = activityObject(bytes).let { row ->
    val daily = row.array("daily").map { decodeDay(it.jsonObject) }
    val contexts = row.array("daily").map { value ->
        val day = value.jsonObject
        val decision = day.obj("resolution")
        val summary = decision.optionalObject("summary")
        val observed = decision.optionalObject("data")?.optionalInstant("observed_at") ?: decision.optionalObject("freshness")?.optionalInstant("measured_at")
        ActivityDayContext(day.date("date"), day.text("kind"), observed, decision.optionalObject("coverage")?.optionalText("basis") == "daily_counter_snapshot", summary?.let { DeviceWorkoutSummary(it.nonnegative("count"), it.nonnegative("known_duration_seconds"), it.nonnegative("unknown_duration_count")) })
    }
    val workouts = row.array("series").mapNotNull { series ->
        val report = series.jsonObject
        val kind = report.optionalText("kind")
        if (report.optionalText("shape") != "workout_report" || kind !in setOf("running_workouts", "workouts")) return@mapNotNull null
        val sport = report.obj("data").text("sport_type")
        if ((sport == "running") != (kind == "running_workouts")) return@mapNotNull null
        val exact = daily.find { day -> day.metric.kind == kind && day.metric.resolution?.selected?.any { it.id == report.text("id") && it.revision == report.revision("revision") } == true }
        val day = exact ?: daily.find { it.metric.kind == kind && it.date == report.optionalText("local_date") } ?: return@mapNotNull null
        decodeDeviceWorkout(report, day.date).copy(resolution = day.metric.resolution)
    }.distinctBy { it.id }.sortedByDescending { it.startAt }
    ActivityResolvedPage(row.date("from"), row.date("to"), row.zone("timezone"), row.instant("computed_at"), row.text("fingerprint"), daily, workouts, row.optionalText("next_cursor"), contexts)
}
fun decodeActivityHistory(bytes: ByteArray): ActivityHistory = activityObject(bytes).let { row -> ActivityHistory(row.date("from"), row.date("to"), row.array("daily").map { decodeDay(it.jsonObject) }, row.optionalBoolean("truncated") ?: false) }
private val currentArchiveFields = setOf("source_name", "source_status", "is_current", "current_revision", "annotation_id", "annotation_revision", "excluded", "resolution_disposition")
fun decodeActivityReports(bytes: ByteArray): ActivityReportPage = activityObject(bytes).let { row -> ActivityReportPage(row.date("from"), row.date("to"), row.array("items").map { value ->
    val report = value.jsonObject
    ActivityReport(report.text("id"), report.text("source_id"), report.optionalText("source_name"), report.revision("revision"), report.revision("source_version"), report.text("kind"), report.text("shape"), report.date("local_date"), report.zone("timezone"), report.instant("observed_at"), report.optionalInstant("start_at"), report.optionalInstant("end_at"), report.obj("data"), report.obj("origin"), report.strings("quality"), report.optionalBoolean("is_current"), report.optionalLong("current_revision"), report.optionalLong("annotation_revision"), report.optionalBoolean("excluded"), report.optionalText("resolution_disposition"), JsonObject(report.filterKeys { it !in currentArchiveFields }),
        if (report.text("shape") == "workout_report") decodeDeviceWorkout(report) else null)
}, row.text("fingerprint"), row.optionalText("next_cursor")) }
private fun decodeWorkoutContext(row: JsonObject): WorkoutContext = WorkoutContext(row.instant("start_at"), row.instant("end_at"), row.zone("timezone"), row.text("status"), row.text("trigger"), row.optionalText("kind"), row.optionalText("sport_type"), row.optionalInstant("recovery_end_at"), row.optionalText("stop_reason"), decodePauses(row), row)
fun decodeWorkoutHeartRate(bytes: ByteArray): WorkoutHeartRatePage = activityObject(bytes).let { row ->
    val ref = row.obj("report_ref")
    val chunks = row.array("chunks").also { require(it.size <= 100) }.map { value ->
        val chunk = value.jsonObject
        require(chunk.text("time_basis") == "phone_monotonic")
        val anchor = chunk.obj("clock_anchor")
        anchor.instant("utc_at"); require(anchor.long("elapsed_realtime_ms") >= 0); anchor.text("boot_id")
        WorkoutHeartRateChunk(chunk.text("external_id"), chunk.text("workout_id"), chunk.int("sequence").also { require(it in 0..10079) }, chunk.instant("start_at"), chunk.instant("end_at"), chunk.array("samples").also { require(it.size <= 300) }.map {
            val sample = it.jsonObject
            val quality = sample.text("quality").also { require(it in setOf("valid", "invalid", "unknown")) }
            val bpm = sample.int("bpm").also { require(it in 0..300 && (quality != "valid" || it in 20..250)) }
            HeartRateSample(sample.long("offset_ms").also { require(it >= 0) }, bpm, quality)
        }, chunk.array("gaps").map {
            val gap = it.jsonObject
            HeartRateGap(gap.long("start_offset_ms").also { require(it >= 0) }, gap.long("end_offset_ms").also { require(it >= 0) }, gap.text("reason"))
        }, chunk)
    }
    val context = decodeWorkoutContext(row.obj("workout"))
    require(context.status in setOf("completed", "interrupted") && context.trigger in setOf("manual", "automatic", "manual_takeover"))
    WorkoutHeartRatePage(row.text("workout_id"), row.text("source_id"), ref.text("id"), ref.revision("revision"), row.text("fingerprint").also { require(it.length == 64 && it.all { c -> c in '0'..'9' || c in 'a'..'f' }) }, context, chunks, row.optionalText("next_cursor"))
}
private fun decodeTrackPoint(row: JsonObject): TrackPoint = TrackPoint(row.instant("at"), row.number("latitude").also { require(it in -90.0..90.0) }, row.number("longitude").also { require(it in -180.0..180.0) }, row.optionalNumber("accuracy_meters")?.also { require(it >= 0) }, row.text("quality"), row.optionalText("provider"), row.optionalBoolean("paused") ?: false)
private fun decodeTrackSummary(row: JsonObject): WorkoutTrackSummary {
    require(row.text("unit") == "m")
    val contexts = row.obj("contexts").filterKeys { it in setOf("running", "workout", "recovery") }.mapValues { (_, value) ->
        val summary = value.jsonObject
        val quality = summary["quality"]
        TrackContextSummary(summary.optionalNumber("distance_meters")?.also { require(it >= 0) }, summary.int("usable_points").also { require(it >= 0) }, summary.int("reported_points").also { require(it >= 0) }, summary.int("gap_count").also { require(it >= 0) }, summary.number("coverage_seconds").also { require(it >= 0) }, (quality as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content } ?: emptyList(), summary.optionalInt("paused_points"), summary.optionalNumber("paused_seconds"), summary.optionalInt("usable_segments"), if (quality is JsonArray) null else quality?.toString())
    }
    require("recovery" in contexts)
    return WorkoutTrackSummary(row.text("algorithm_version"), row.optionalNumber("value_meters")?.also { require(it >= 0) }, contexts, row.optionalText("fingerprint"), row)
}
fun decodeWorkoutTrack(bytes: ByteArray): WorkoutTrackPage = activityObject(bytes).let { row -> WorkoutTrackPage(row.text("workout_id"), row.text("source_id"), decodeWorkoutContext(row.obj("workout")), row.array("chunks").map { it.jsonObject }, row.array("segments").map {
    val segment = it.jsonObject
    TrackSegment(segment.text("context").also { require(it in setOf("running", "workout", "recovery")) }, segment.text("quality"), segment.array("points").map { decodeTrackPoint(it.jsonObject) })
}, row.text("fingerprint"), row.optionalObject("summary")?.let { it.text("fingerprint"); decodeTrackSummary(it) }, row.optionalText("next_cursor")) }
fun decodeWorkoutTrackVersions(bytes: ByteArray): WorkoutTrackVersionsPage = activityObject(bytes).let { row -> WorkoutTrackVersionsPage(row.text("workout_id"), row.text("source_id"), row.array("items").map {
    val item = it.jsonObject
    val snapshot = item.obj("snapshot")
    val ref = snapshot.obj("report_ref")
    ref.text("id"); require(ref.revision("revision") > 0)
    snapshot.array("input_refs").forEach { value -> val input = value.jsonObject; input.text("id"); input.text("hash"); require(input.int("sequence") >= 0) }
    WorkoutTrackVersion(item.revision("revision").also { require(it > 0) }, decodeTrackSummary(snapshot), item.boolean("is_latest"))
}, row.optionalLong("latest_revision")?.also { require(it > 0) }, row.text("fingerprint").also { require(it.isNotEmpty()) }, row.optionalText("next_cursor")) }
internal fun activityObject(bytes: ByteArray): JsonObject = NativeJson.parseToJsonElement(bytes.decodeToString(throwOnInvalidSequence = true)).jsonObject
private fun JsonObject.text(key: String): String = string(requireNotNull(this[key]))
private fun string(value: JsonElement): String = (value as? JsonPrimitive)?.takeIf { it.isString }?.content ?: error("Expected string")
private fun JsonObject.optionalText(key: String): String? = this[key]?.takeUnless { it == JsonNull }?.let(::string)
private fun JsonObject.obj(key: String): JsonObject = requireNotNull(this[key] as? JsonObject)
private fun JsonObject.optionalObject(key: String): JsonObject? = this[key]?.takeUnless { it == JsonNull }?.let { it as? JsonObject ?: error("Expected object") }
private fun JsonObject.array(key: String): JsonArray = requireNotNull(this[key] as? JsonArray)
private fun JsonObject.optionalArray(key: String): JsonArray? = this[key]?.takeUnless { it == JsonNull }?.let { it as? JsonArray ?: error("Expected array") }
private fun JsonObject.strings(key: String): List<String> = optionalArray(key)?.map(::string) ?: emptyList()
private fun JsonObject.numbers(key: String): List<Double> = optionalArray(key)?.map { requireNotNull(amount(it)?.toDoubleOrNull()) } ?: emptyList()
private fun JsonObject.optionalBoolean(key: String): Boolean? = this[key]?.takeUnless { it == JsonNull }?.let { requireNotNull((it as? JsonPrimitive)?.takeUnless { p -> p.isString }?.booleanOrNull) }
private fun JsonObject.boolean(key: String): Boolean = requireNotNull(optionalBoolean(key))
private fun JsonObject.optionalLong(key: String): Long? = this[key]?.takeUnless { it == JsonNull }?.let { requireNotNull((it as? JsonPrimitive)?.takeUnless { p -> p.isString }?.longOrNull) }
private fun JsonObject.long(key: String): Long = requireNotNull(optionalLong(key))
private fun JsonObject.revision(key: String): Long = long(key).also { require(it >= 0) }
private fun JsonObject.optionalInt(key: String): Int? = this[key]?.takeUnless { it == JsonNull }?.let { requireNotNull((it as? JsonPrimitive)?.takeUnless { p -> p.isString }?.intOrNull) }
private fun JsonObject.int(key: String): Int = requireNotNull(optionalInt(key))
private fun amount(value: JsonElement?): String? {
    if (value == null || value == JsonNull) return null
    val primitive = value as? JsonPrimitive ?: error("Expected decimal")
    require(primitive.content.isNotBlank() && primitive.doubleOrNull?.isFinite() == true)
    return primitive.content
}
private fun JsonObject.amount(key: String): String? = amount(this[key])
private fun JsonObject.optionalNumber(key: String): Double? = amount(key)?.toDouble()
private fun JsonObject.number(key: String): Double = requireNotNull(optionalNumber(key))
private fun JsonObject.nonnegative(key: String): Double? = try { optionalNumber(key)?.takeIf { it >= 0 } } catch (_: Exception) { null }
private fun JsonObject.date(key: String): String = text(key).also { require(LocalDate.parse(it).toString() == it) }
private fun JsonObject.zone(key: String): String = text(key).also { TimeZone.of(it) }
private fun JsonObject.instant(key: String): String = text(key).also { Instant.parse(it) }
private fun JsonObject.optionalInstant(key: String): String? = optionalText(key)?.also { Instant.parse(it) }
