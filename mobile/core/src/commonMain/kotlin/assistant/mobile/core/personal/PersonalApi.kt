package assistant.mobile.core.personal

import assistant.mobile.core.HttpOutcome
import assistant.mobile.core.NativeHttpClient
import assistant.mobile.core.NativeJson
import assistant.mobile.core.SessionLease
import io.ktor.http.encodeURLParameter
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import kotlin.time.Instant

interface PersonalReader {
    suspend fun overview(day: String, lease: SessionLease): PersonalOverview
    suspend fun resolved(day: String, lease: SessionLease): PersonalResolvedPage
    suspend fun derivedScores(day: String, timezone: String, lease: SessionLease): PersonalDerivedScoresPage
    suspend fun caliper(day: String, lease: SessionLease): PersonalCaliperHistory
    suspend fun catalog(lease: SessionLease): List<PersonalCatalogMetric>
}

class PersonalReadException(val outcome: HttpOutcome) : Exception("Personal request failed")

/** The session lease is supplied by the owner; every page belongs to the same dated decision. */
class NativePersonalApi(private val http: NativeHttpClient) : PersonalReader {
    override suspend fun overview(day: String, lease: SessionLease): PersonalOverview =
        get("/v1/personal/overview?date=${day.encodeURLParameter()}", lease, ::decodePersonalOverview).also {
            requireResponse(it.date == day && it.profile.userId == lease.namespace.userId)
        }

    override suspend fun resolved(day: String, lease: SessionLease): PersonalResolvedPage {
        var cursor: String? = null
        var combined: PersonalResolvedPage? = null
        val seen = mutableSetOf<String>()
        do {
            val page = get("/v1/personal/resolved?from=$day&to=$day&limit=500" +
                (cursor?.let { "&cursor=${it.encodeURLParameter()}" } ?: ""), lease, ::decodePersonalResolved)
            requireResponse(page.from == day && page.to == day && page.daily.all { it.date == day })
            val before = combined
            if (before != null) requireResponse(before.fingerprint == page.fingerprint && before.timezone == page.timezone)
            combined = if (before == null) page else before.copy(daily = before.daily + page.daily, nextCursor = page.nextCursor)
            cursor = page.nextCursor
            if (cursor != null) requireResponse(seen.add(cursor))
        } while (cursor != null)
        return requireNotNull(combined)
    }

    override suspend fun derivedScores(day: String, timezone: String, lease: SessionLease): PersonalDerivedScoresPage {
        var cursor: String? = null
        var combined: PersonalDerivedScoresPage? = null
        val seen = mutableSetOf<String>()
        do {
            val page = get("/v1/personal/derived-scores?from=$day&to=$day&time_zone=${timezone.encodeURLParameter()}&limit=250" +
                (cursor?.let { "&cursor=${it.encodeURLParameter()}" } ?: ""), lease, ::decodePersonalDerivedScores)
            requireResponse(page.from == day && page.to == day && page.timezone == timezone && page.days.all { it.date == day })
            val before = combined
            if (before != null) requireResponse(before.fingerprint == page.fingerprint && before.totalSummaries == page.totalSummaries)
            combined = if (before == null) page else before.copy(days = before.days + page.days, nextCursor = page.nextCursor,
                pendingRuns = page.pendingRuns, failedRuns = page.failedRuns)
            cursor = page.nextCursor
            if (cursor != null) requireResponse(seen.add(cursor))
        } while (cursor != null)
        return requireNotNull(combined)
    }

    override suspend fun caliper(day: String, lease: SessionLease): PersonalCaliperHistory =
        get("/v1/personal/caliper?from=1900-01-01&to=$day&limit=100", lease, ::decodePersonalCaliperHistory)

    override suspend fun catalog(lease: SessionLease): List<PersonalCatalogMetric> =
        get("/v1/personal/metrics/catalog", lease, ::decodePersonalCatalog)

    private suspend fun <T> get(path: String, lease: SessionLease, decode: (ByteArray) -> T): T {
        val response = http.request("GET", path, lease = lease)
        if (response !is HttpOutcome.Success) throw PersonalReadException(response)
        return try { decode(response.body) } catch (_: Exception) { throw PersonalReadException(HttpOutcome.InvalidResponse) }
    }

    private fun requireResponse(valid: Boolean) {
        if (!valid) throw PersonalReadException(HttpOutcome.InvalidResponse)
    }
}

fun decodePersonalOverview(bytes: ByteArray): PersonalOverview {
    val row = personalObject(bytes)
    val metrics = row.obj("metrics")
    return PersonalOverview(row.date("date"), row.zone("timezone"), decodePersonalProfile(row.obj("profile")),
        metrics.filterKeys { it != "nutrition" }.mapValues { (_, value) -> decodePersonalMetric(value.jsonObject) },
        metrics.optionalObject("nutrition")?.let {
            PersonalNutritionMetric(it.text("status"), it.amount("value"), it.text("unit"), it.optionalBoolean("complete"),
                it.amount("target"), it.amount("remaining"), it.optionalInt("estimated_count"), it.optionalInt("pending_count"))
        }, row.obj("preferences").mapValues { (_, value) -> decodePersonalPreference(value.jsonObject) },
        row.array("sources").map {
            val source = it.jsonObject
            PersonalSource(source.text("id"), source.text("name"), source.text("status"), source.revision("revision"))
        }, row.optionalObject("metrics_by_source")?.mapValues { (_, values) ->
            (values as JsonArray).map { decodePersonalMetric(it.jsonObject) }
        } ?: emptyMap())
}

fun decodePersonalProfile(row: JsonObject): PersonalProfile = PersonalProfile(
    row.text("user_id"), row.zone("timezone"), PersonalDisplayUnits.fromWire(row.optionalText("display_units") ?: "metric"),
    row.optionalText("birth_date")?.also { require(LocalDate.parse(it).toString() == it) }, row.amount("height_cm"),
    row.optionalText("gender")?.also { require(it in setOf("male", "female")) }, row.revision("revision"), row.optionalText("gender_origin"),
)

fun decodePersonalPreference(row: JsonObject): PersonalPreference =
    PersonalPreference(row.text("kind"), row.amount("target"), row.revision("revision"))

fun decodePersonalMetric(row: JsonObject): PersonalMetric = PersonalMetric(
    row.text("kind"), row.text("status").also { require(it in setOf("available", "no_data", "conflict", "unavailable", "partial")) },
    row.amount("value"), row.text("unit"), row.amount("target"), row.optionalBoolean("complete"),
    row.optionalArray("records")?.map { decodePersonalObservation(it.jsonObject) } ?: emptyList(),
    row.optionalBoolean("source_unavailable") ?: false, row.optionalObject("resolution")?.let(::decodePersonalResolution),
    row.optionalText("source_id"), row.optionalText("source_name"),
)

fun decodePersonalObservation(row: JsonObject): PersonalObservation = PersonalObservation(
    row.text("id"), row.text("kind"), row.obj("data").amount("value"), row.instant("start_at"), row.instant("end_at"),
    row.zone("timezone"), row.text("method"), row.revision("revision"), row.optionalText("source_id"), row.optionalText("source_name"),
    row.optionalText("local_date"), row.strings("quality"), row.optionalBoolean("excluded") ?: false,
    row.optionalBoolean("corrected") ?: false, row.obj("data").optionalArray("stages")?.map { decodeSleepStage(it.jsonObject) } ?: emptyList(),
    (row.optionalObject("original")?.optionalObject("origin") ?: row.optionalObject("origin"))?.optionalText("provider"),
    (row.optionalObject("original")?.optionalObject("origin") ?: row.optionalObject("origin"))?.optionalText("record_id"),
    row.optionalObject("original")?.optionalText("start_at"),
    row.optionalObject("original")?.optionalObject("origin")?.optionalText("record_id"),
    row.optionalNumber("override_revision")?.toLong() ?: 0,
    row.optionalText("algorithm_version"),
)

fun decodePersonalResolved(bytes: ByteArray): PersonalResolvedPage = personalObject(bytes).let { row ->
    PersonalResolvedPage(row.date("from"), row.date("to"), row.zone("timezone"), row.instant("computed_at"),
        row.array("daily").map {
            val day = it.jsonObject
            PersonalResolvedDay(day.date("date"), decodePersonalMetric(day))
        }, row.text("fingerprint"), row.optionalText("next_cursor"), row.optionalArray("series")?.map { it.jsonObject } ?: emptyList())
}

fun decodePersonalCatalog(bytes: ByteArray): List<PersonalCatalogMetric> = personalObject(bytes).array("metrics").map {
    val row = it.jsonObject
    PersonalCatalogMetric(row.text("key"), row.text("unit"), row.text("shape"), row.optionalText("label"))
}

fun decodePersonalResolution(row: JsonObject): PersonalResolution = PersonalResolution(
    row.text("status"), row.text("fingerprint"), row.text("resolver_version"),
    row.optionalArray("selected")?.map { decodeFact(it.jsonObject) } ?: emptyList(),
    row.optionalArray("alternatives")?.map { decodeFact(it.jsonObject) } ?: emptyList(),
    row.optionalObject("protocol")?.optionalBoolean("verified"), row.optionalObject("protocol")?.strings("flags") ?: emptyList(),
    row.optionalObject("quality")?.strings("flags") ?: emptyList(),
    row.optionalObject("coverage")?.let { PersonalCoverage(it.optionalText("status"), it.optionalNumber("reported_seconds"), it.optionalNumber("covered_seconds"), it.optionalBoolean("complete"), it.optionalInt("point_count"), it.optionalArray("intervals")?.size ?: 0) },
    row.optionalObject("freshness")?.optionalText("measured_at"),
    row.optionalArray("sessions")?.map {
        val session = it.jsonObject
        PersonalSleepSession(session.text("id"), session.instant("start_at"), session.instant("end_at"), session.amount("asleep_minutes"),
            session.optionalText("source_id"), session.optionalText("source_name"),
            session.optionalArray("stages")?.map { decodeSleepStage(it.jsonObject) } ?: emptyList(), session.strings("position_changes"))
    } ?: emptyList(), row.optionalObject("sleep_analysis")?.let(::decodePersonalSleepAnalysis),
    row.optionalObject("statistics")?.let(::decodeStatistics), row.optionalInt("selected_count"), row.optionalInt("alternatives_count"),
    row.optionalBoolean("explanation_truncated") ?: false,
    row.optionalArray("contributions")?.map {
        val contribution = it.jsonObject
        PersonalContribution(contribution.optionalText("source_id"), contribution.optionalText("source_name"), contribution.int("count"), contribution.amount("value"),
                contribution.optionalArray("spans")?.map { value -> val span = value.jsonObject
                    PersonalContributionSpan(span.instant("start_at"), span.instant("end_at")) } ?: emptyList())
    } ?: emptyList(),
)

private fun decodeFact(row: JsonObject): PersonalFact = PersonalFact(row.text("id"), row.optionalText("source_id"),
    row.optionalText("source_name"), row.amount("contribution"), row.optionalText("reason"), row.optionalText("start_at"), row.optionalText("end_at"),
    row.optionalLong("revision"), row.optionalText("type"), row.optionalNumber("reported_asleep_minutes"))

private fun decodeSleepStage(row: JsonObject): PersonalSleepStage =
    PersonalSleepStage(row.instant("start_at"), row.instant("end_at"), row.text("stage"))

private fun decodeStatistics(row: JsonObject): PersonalStatistics {
    fun block(key: String): PersonalStatisticsBlock? = row.optionalObject(key)?.let {
        PersonalStatisticsBlock(it.optionalInt("count"), it.amount("mean"), it.amount("min"), it.amount("max"), it.optionalNumber("covered_seconds"),
            it.optionalText("first_at"), it.optionalText("latest_at"), it.optionalInt("sample_count"), it.optionalInt("usable_sample_count"),
            it.optionalNumber("sampled_seconds"), it.optionalNumber("sample_coverage_ratio"), it.optionalInt("gap_count"), it.optionalNumber("max_inter_sample_gap_ms"))
    }
    return PersonalStatistics(block("points"), block("interval_means"), block("ranges"), block("sample_windows"),
        row.optionalArray("groups")?.map { decodeStatistics(it.jsonObject) } ?: emptyList(), row.optionalText("measurement_kind"),
        row.optionalText("sensor_location"), row.optionalText("definition_id"), row.optionalText("temperature_basis"), row.optionalText("context"), row.strings("source_names"))
}

fun decodePersonalSleepAnalysis(row: JsonObject): PersonalSleepAnalysis {
    val summary = row.obj("summary")
    return PersonalSleepAnalysis(row.text("version"), row.text("fingerprint"), row.text("status"), row.instant("computed_at"),
        PersonalSleepSummary(summary.number("window_minutes"), summary.number("asleep_minutes"), summary.number("awake_minutes"),
            summary.number("unknown_minutes"), summary.number("conflict_minutes"), summary.number("classification_coverage"),
            summary.optionalNumber("complete_asleep_minutes"), summary.optionalNumber("forecast_asleep_minutes")),
        row.array("episodes").map {
            val episode = it.jsonObject
            val forecast = episode.obj("forecast")
            PersonalSleepEpisode(episode.instant("start_at"), episode.instant("end_at"), episode.text("status"),
                episode.number("asleep_minutes"), episode.number("awake_minutes"), episode.number("unknown_minutes"), episode.number("conflict_minutes"),
                episode.optionalNumber("complete_asleep_minutes"), episode.obj("stage_minutes").mapValues { (_, amount) -> personalNumber(amount) },
                episode.strings("flags"), PersonalSleepForecast(forecast.text("status"), forecast.text("algorithm_version"),
                    forecast.optionalNumber("estimated_asleep_minutes"), forecast.optionalNumber("estimated_missing_minutes"), forecast.int("training_nights"), forecast.optionalText("reason"), forecast.optionalText("basis"),
                    forecast.optionalText("training_from"), forecast.optionalText("training_to"), forecast.optionalObject("validation")?.let { validation ->
                        PersonalSleepForecastValidation(validation.int("nights"), validation.number("mae_minutes"), validation.number("p90_error_minutes"),
                            validation.number("baseline_mae_minutes"), validation.text("reference"))
                    }),
                episode.array("segments").map { segmentValue ->
                    val segment = segmentValue.jsonObject
                    PersonalSleepSegment(segment.instant("start_at"), segment.instant("end_at"), segment.text("state"), segment.optionalText("stage"), segment.text("reason"), segment.strings("unknown_codes"),
                        segment.optionalArray("evidence")?.map { decodeFact(it.jsonObject) } ?: emptyList(),
                        segment.optionalArray("selected_refs")?.map { decodeFact(it.jsonObject) } ?: emptyList())
                }, episode.strings("position_changes"), episode.optionalNumber("window_minutes"), episode.optionalNumber("classification_coverage"),
                episode.optionalObject("bounds")?.let { PersonalSleepBounds(it.number("min_minutes"), it.number("max_minutes")) },
                episode.optionalObject("night")?.let { night ->
                    val heart = night.optionalObject("heart_rate")
                    val hrv = night.optionalObject("hrv")
                    PersonalSleepNight(night.instant("onset_at"), night.instant("final_wake_at"), night.instant("midpoint_at"),
                        night.optionalNumber("latency_minutes"), night.optionalNumber("efficiency"), night.optionalNumber("waso_minutes"),
                        night.optionalInt("awakenings"), night.optionalNumber("turns_per_hour"), heart?.optionalNumber("lowest"),
                        heart?.optionalNumber("mean"), heart?.optionalInt("readings"), hrv?.optionalNumber("mean"), hrv?.optionalInt("readings"))
                }, episode.optionalArray("reports")?.map { decodeFact(it.jsonObject) } ?: emptyList())
        }, row.optionalArray("input_refs")?.map { decodeFact(it.jsonObject) } ?: emptyList(),
        row.optionalArray("rejected_reports")?.map { decodeFact(it.jsonObject) } ?: emptyList(), row.optionalInt("input_count"))
}

fun decodePersonalDerivedScores(bytes: ByteArray): PersonalDerivedScoresPage = personalObject(bytes).let { row ->
    require(row.text("persistence") == "immutable_versions" && row.text("details_mode") == "archived")
    PersonalDerivedScoresPage(row.date("from"), row.date("to"), row.zone("time_zone"), row.array("days").map {
        val day = it.jsonObject
        PersonalDerivedDay(day.date("date"), day.array("scores").map { decodePersonalDerivedScore(it.jsonObject) })
    }, row.text("fingerprint"), row.optionalText("next_cursor"), row.int("total_summaries"),
        row.optionalObject("refresh")?.optionalInt("pending_runs") ?: 0, row.optionalObject("refresh")?.optionalInt("failed_runs") ?: 0)
}

fun decodePersonalDerivedScore(row: JsonObject): PersonalDerivedScore {
    val definition = row.obj("definition_snapshot")
    require(definition.text("id") == row.text("definition_id") && definition.text("unit") == row.text("unit") &&
        definition.text("formula_version") == row.text("formula_version") && definition.text("qualification") == row.text("qualification"))
    val confidence = row.obj("confidence")
    val coverage = row.obj("coverage")
    val context = row.obj("context")
    return PersonalDerivedScore(row.text("definition_id"), definition.text("label"), row.text("formula_version"), row.text("qualification"),
        row.text("status").also { require(it in setOf("available", "partial", "unavailable", "unqualified")) }, row.amount("value"), row.text("unit"),
        row.strings("missing"), row.strings("quality"), PersonalDerivedConfidence(confidence.text("level"), confidence.int("available_inputs"), confidence.int("required_inputs")),
        PersonalDerivedCoverage(coverage.int("observed"), coverage.int("expected"), coverage.amount("ratio")),
        PersonalDerivedContext(context.text("evaluation"), context.date("date"), context.zone("time_zone"), context.instant("as_of"), context.optionalText("source_id")),
        row.text("result_id").also { require(it.length == 64) }, row.revision("version").also { require(it > 0) }, row.instant("evaluated_at"), row.int("series_count"))
}

fun decodePersonalCaliperHistory(bytes: ByteArray): PersonalCaliperHistory = personalObject(bytes).let { row ->
    PersonalCaliperHistory(row.array("records").map { decodePersonalCaliperMeasurement(it.jsonObject) }, row.boolean("truncated"), row.int("limit").also { require(it > 0) })
}

fun decodePersonalCaliperMeasurement(row: JsonObject): PersonalCaliperMeasurement {
    val calculation = row.obj("calculation")
    return PersonalCaliperMeasurement(row.text("id"), row.text("source_id"), row.instant("measured_at"), row.zone("timezone"),
        row.obj("folds_mm").map { (site, value) ->
            require(site in PERSONAL_FOLD_SITES)
            site to requireNotNull(personalAmount(value))
        }.toMap(), row.optionalText("gender")?.also { require(it in setOf("male", "female")) }, row.optionalInt("age_years"), row.optionalText("note"), row.amount("body_fat_percent"),
        PersonalCaliperCalculation(calculation.text("status").also { require(it in setOf("calculated", "incomplete", "unsupported")) },
            calculation.text("method").also { require(it == "jackson_pollock_3_siri") }, calculation.strings("missing_fields"), calculation.text("algorithm_version"), calculation.optionalText("reason")),
        row.revision("revision").also { require(it > 0) }, row.optionalText("body_fat_record_id"))
}

internal fun personalObject(bytes: ByteArray): JsonObject = NativeJson.parseToJsonElement(bytes.decodeToString(throwOnInvalidSequence = true)).jsonObject
private fun JsonObject.text(key: String): String = personalString(requireNotNull(this[key]))
private fun JsonObject.optionalText(key: String): String? = this[key]?.takeUnless { it == JsonNull }?.let(::personalString)
private fun personalString(value: JsonElement): String = (value as? JsonPrimitive)?.takeIf { it.isString }?.content ?: error("Expected string")
private fun JsonObject.array(key: String): JsonArray = requireNotNull(this[key] as? JsonArray)
private fun JsonObject.optionalArray(key: String): JsonArray? = this[key]?.takeUnless { it == JsonNull }?.let { it as? JsonArray ?: error("Expected array") }
private fun JsonObject.obj(key: String): JsonObject = requireNotNull(this[key] as? JsonObject)
private fun JsonObject.optionalObject(key: String): JsonObject? = this[key]?.takeUnless { it == JsonNull }?.let { it as? JsonObject ?: error("Expected object") }
private fun JsonObject.strings(key: String): List<String> = optionalArray(key)?.map(::personalString) ?: emptyList()
private fun JsonObject.boolean(key: String): Boolean = requireNotNull(optionalBoolean(key))
private fun JsonObject.optionalBoolean(key: String): Boolean? = this[key]?.takeUnless { it == JsonNull }?.let {
    requireNotNull((it as? JsonPrimitive)?.takeUnless { primitive -> primitive.isString }?.booleanOrNull)
}
private fun JsonObject.optionalLong(key: String): Long? = this[key]?.takeUnless { it == JsonNull }?.let {
    requireNotNull((it as? JsonPrimitive)?.takeUnless { primitive -> primitive.isString }?.longOrNull)
}
private fun JsonObject.revision(key: String): Long = requireNotNull(optionalLong(key)).also { require(it >= 0) }
private fun JsonObject.optionalInt(key: String): Int? = this[key]?.takeUnless { it == JsonNull }?.let {
    requireNotNull((it as? JsonPrimitive)?.takeUnless { primitive -> primitive.isString }?.intOrNull)
}
private fun JsonObject.int(key: String): Int = requireNotNull(optionalInt(key))
private fun JsonObject.amount(key: String): String? = personalAmount(this[key])
private fun personalAmount(value: JsonElement?): String? {
    if (value == null || value == JsonNull) return null
    val primitive = value as? JsonPrimitive ?: error("Expected decimal")
    val text = primitive.content
    require(text.isNotBlank() && primitive.doubleOrNull?.isFinite() == true) { "Expected finite decimal" }
    return text
}
private fun personalNumber(value: JsonElement): Double = requireNotNull(personalAmount(value)?.toDoubleOrNull())
private fun JsonObject.number(key: String): Double = personalNumber(requireNotNull(this[key]))
private fun JsonObject.optionalNumber(key: String): Double? = this[key]?.takeUnless { it == JsonNull }?.let(::personalNumber)
private fun JsonObject.date(key: String): String = text(key).also { require(LocalDate.parse(it).toString() == it) }
private fun JsonObject.zone(key: String): String = text(key).also { TimeZone.of(it) }
private fun JsonObject.instant(key: String): String = text(key).also { Instant.parse(it) }
