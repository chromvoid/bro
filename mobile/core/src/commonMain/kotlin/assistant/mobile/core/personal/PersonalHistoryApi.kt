package assistant.mobile.core.personal

import assistant.mobile.core.HttpOutcome
import assistant.mobile.core.NativeHttpClient
import assistant.mobile.core.SessionLease
import io.ktor.http.encodeURLParameter
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.plus
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

interface PersonalHistoryReader {
    suspend fun history(from: String, to: String, metric: String?, lease: SessionLease): PersonalHistoryPage
    suspend fun resolved(from: String, to: String, metric: String?, lease: SessionLease): PersonalResolvedPage
    suspend fun sensors(from: String, to: String, metric: String, timezone: String, lease: SessionLease): PersonalSensorPage
    suspend fun versions(id: String, metric: String, sourceId: String?, lease: SessionLease): PersonalRecordVersions
    suspend fun raw(from: String, to: String, metric: String, lease: SessionLease): PersonalRawReportPage
    suspend fun analysis(id: String, lease: SessionLease): PersonalSourceAnalysis
    suspend fun derived(from: String, to: String, timezone: String, lease: SessionLease): PersonalDerivedScoresPage
    suspend fun derivedVersions(from: String, to: String, timezone: String, lease: SessionLease): PersonalDerivedArchivePage
    suspend fun derivedResult(id: String, lease: SessionLease): PersonalArchivedResult
    suspend fun backfillRun(id: String, lease: SessionLease): PersonalBackfillRun
}

/** Each paginated read is collected atomically: mixed fingerprints never reach presentation. */
class NativePersonalHistoryApi(private val http: NativeHttpClient) : PersonalHistoryReader {
    override suspend fun history(from: String, to: String, metric: String?, lease: SessionLease): PersonalHistoryPage =
        get("/v1/personal/history" + historyQuery("from" to from, "to" to to, "kind" to metric?.takeUnless { it == "all" },
            "projection" to "body".takeIf { metric == "body" }), lease, ::decodePersonalHistory).also {
            require(it.from == from && it.to == to)
        }

    override suspend fun resolved(from: String, to: String, metric: String?, lease: SessionLease): PersonalResolvedPage {
        val pages = collect { cursor -> get("/v1/personal/resolved" + historyQuery("from" to from, "to" to to,
            "metric" to metric?.takeUnless { it == "all" }, "limit" to "500", "cursor" to cursor), lease, ::decodePersonalResolved) }
        require(pages.all { it.from == from && it.to == to && it.fingerprint == pages.first().fingerprint && it.timezone == pages.first().timezone })
        val series = pages.flatMap { it.series }
        if (metric in PERSONAL_SENSOR_KINDS) require(series.map(::decodePersonalSensorSample).all { it.metric == metric })
        return pages.first().copy(daily = pages.flatMap { it.daily }, series = series, nextCursor = null)
    }

    override suspend fun sensors(from: String, to: String, metric: String, timezone: String, lease: SessionLease): PersonalSensorPage {
        require(metric in PERSONAL_SENSOR_KINDS)
        val start = LocalDate.parse(from).atStartOfDayIn(TimeZone.of(timezone)).toString()
        val end = LocalDate.parse(to).plus(1, DateTimeUnit.DAY).atStartOfDayIn(TimeZone.of(timezone)).toString()
        val pages = collect { cursor -> get("/v1/personal/sensor-samples" + historyQuery("from" to start, "to" to end,
            "metric" to metric, "limit" to "500", "cursor" to cursor), lease, ::decodePersonalSensorPage) }
        require(coherentPersonalSensorPages(pages) && pages.all { Instant.parse(it.from) == Instant.parse(start) && Instant.parse(it.to) == Instant.parse(end) })
        require(pages.flatMap { it.items }.all { it.metric == metric && (it.raw["user_id"] == null || it.raw.hText("user_id") == lease.namespace.userId) })
        return pages.first().copy(items = pages.flatMap { it.items }, nextCursor = null)
    }

    override suspend fun versions(id: String, metric: String, sourceId: String?, lease: SessionLease): PersonalRecordVersions {
        val sensor = metric in PERSONAL_SENSOR_KINDS
        require(sensor || metric in setOf("steps", "sleep"))
        require(!sensor || !sourceId.isNullOrBlank())
        val path = if (sensor) "sensor-samples" else "records"
        val pages = collect { cursor -> get("/v1/personal/$path/${id.encodeURLParameter()}/versions" + historyQuery(
            (if (sensor) "metric" else "kind") to metric, "source_id" to sourceId, "limit" to "50", "cursor" to cursor), lease, ::decodePersonalRecordVersions) }
        require(coherentPersonalRecordVersions(pages) && pages.all { it.id == id && it.metric == metric && it.sourceId == sourceId })
        val versions = pages.flatMap { it.versions }
        versions.forEach { saved ->
            val snapshot = saved.snapshot
            fun string(field: String): String? = (snapshot[field] as? JsonPrimitive)?.takeIf { it.isString }?.content
            require(string("user_id")?.let { it == lease.namespace.userId } != false)
            require(string("id")?.let { it == id } != false)
            require(string(if (sensor) "metric" else "kind")?.let { it == metric } != false)
            if (snapshot["source_id"] == JsonNull || string("source_id") != null) require(string("source_id") == sourceId)
            (snapshot["revision"] as? JsonPrimitive)?.takeUnless { it.isString }?.longOrNull?.let { require(it == saved.revision) }
        }
        return pages.first().copy(versions = versions, nextCursor = null)
    }

    override suspend fun raw(from: String, to: String, metric: String, lease: SessionLease): PersonalRawReportPage {
        require(metric in setOf("temperature", "workouts"))
        val pages = collect { cursor -> get("/v1/personal/raw-reports" + historyQuery("from" to from, "to" to to,
            "metric" to metric, "limit" to "100", "cursor" to cursor), lease, ::decodePersonalRawReports) }
        require(pages.all { it.from == from && it.to == to && it.fingerprint == pages.first().fingerprint && it.count == pages.first().count })
        val items = pages.flatMap { it.items }
        require(items.size <= pages.first().count && items.all { it.metric == metric && it.raw.hText("user_id") == lease.namespace.userId } && items.map { Triple(it.id, it.revision, it.sourceVersion) }.toSet().size == items.size)
        return pages.first().copy(items = items, nextCursor = null)
    }

    override suspend fun analysis(id: String, lease: SessionLease): PersonalSourceAnalysis =
        get("/v1/personal/analyses/${id.encodeURLParameter()}", lease, ::decodePersonalSourceAnalysis).also { require(it.summary.id == id) }

    override suspend fun derived(from: String, to: String, timezone: String, lease: SessionLease): PersonalDerivedScoresPage {
        val pages = collect { cursor -> get("/v1/personal/derived-scores" + historyQuery("from" to from, "to" to to,
            "time_zone" to timezone, "limit" to "250", "cursor" to cursor), lease, ::decodePersonalDerivedScores) }
        require(pages.all { it.from == from && it.to == to && it.timezone == timezone && it.fingerprint == pages.first().fingerprint &&
            it.totalSummaries == pages.first().totalSummaries && it.days.all { day -> day.date in from..to } })
        val scores = pages.flatMap { it.days }.flatMap { it.scores }
        require(scores.size <= pages.first().totalSummaries && scores.map { it.resultId }.toSet().size == scores.size)
        return pages.first().copy(days = pages.flatMap { it.days }.groupBy { it.date }.map { (date, days) ->
            PersonalDerivedDay(date, days.flatMap { it.scores }) }, nextCursor = null)
    }

    override suspend fun derivedVersions(from: String, to: String, timezone: String, lease: SessionLease): PersonalDerivedArchivePage {
        val pages = collect { cursor -> get("/v1/personal/derived-result-versions" + historyQuery("from" to from, "to" to to,
            "time_zone" to timezone, "scope" to "all", "limit" to "100", "cursor" to cursor), lease, ::decodePersonalDerivedArchive) }
        require(pages.all { it.from == from && it.to == to && it.timezone == timezone && it.scope == "all" &&
            it.fingerprint == pages.first().fingerprint && it.totalVersions == pages.first().totalVersions })
        val items = pages.flatMap { it.items }
        require(items.size <= pages.first().totalVersions && items.map { it.resultId }.toSet().size == items.size && items.all { it.date in from..to && it.timezone == timezone })
        return pages.first().copy(items = items, nextCursor = null)
    }

    override suspend fun derivedResult(id: String, lease: SessionLease): PersonalArchivedResult =
        get("/v1/personal/derived-result-versions/${id.encodeURLParameter()}", lease, ::decodePersonalArchivedResult).also { require(it.resultId == id) }

    override suspend fun backfillRun(id: String, lease: SessionLease): PersonalBackfillRun =
        get("/v1/personal/derived-backfills/${id.encodeURLParameter()}", lease, ::decodePersonalBackfillRun).also { require(it.runId == id) }

    private suspend fun <T> get(path: String, lease: SessionLease, decode: (ByteArray) -> T): T {
        val outcome = http.request("GET", path, lease = lease)
        if (outcome !is HttpOutcome.Success) throw PersonalReadException(outcome)
        return try { decode(outcome.body) } catch (_: Exception) { throw PersonalReadException(HttpOutcome.InvalidResponse) }
    }

    private suspend fun <T> collect(fetch: suspend (String?) -> T): List<T> {
        val pages = mutableListOf<T>()
        val seen = mutableSetOf<String>()
        var cursor: String? = null
        do {
            val page = fetch(cursor)
            pages += page
            cursor = when (page) {
                is PersonalResolvedPage -> page.nextCursor
                is PersonalSensorPage -> page.nextCursor
                is PersonalRecordVersions -> page.nextCursor
                is PersonalRawReportPage -> page.nextCursor
                is PersonalDerivedScoresPage -> page.nextCursor
                is PersonalDerivedArchivePage -> page.nextCursor
                else -> error("Unsupported history page")
            }
            require(cursor == null || seen.add(cursor))
        } while (cursor != null)
        return pages
    }
}

private fun historyQuery(vararg values: Pair<String, String?>): String = values.filter { it.second != null }
    .joinToString("&", prefix = "?") { (key, value) -> "$key=${requireNotNull(value).encodeURLParameter()}" }

fun decodePersonalHistory(bytes: ByteArray): PersonalHistoryPage = personalObject(bytes).let { row ->
    val records = row.hArray("records").map { it.jsonObject }
    PersonalHistoryPage(row.hDate("from"), row.hDate("to"), records.map(::decodePersonalObservation),
        row.hArray("daily").map { value -> val day = value.jsonObject
            PersonalResolvedDay(day.hDate("date"), PersonalMetric(day.hText("kind"), day.hText("status"), day.hAmount("value"),
                day.hOptionalText("unit") ?: personalHistoryUnit(day.hText("kind")), day.hAmount("target"),
                resolution = day.hObjectOrNull("resolution")?.let(::decodePersonalResolution))) },
        records.associateBy { it.hText("id") }, row.hOptionalArray("source_analyses").map { decodePersonalAnalysisSummary(it.jsonObject) },
        row.hOptionalArray("sources").map { value -> val source = value.jsonObject
            PersonalSource(source.hText("id"), source.hText("name"), source.hText("status"), source.hLong("revision")) },
        row.hBooleanOrNull("truncated") ?: false,
        row.hOptionalArray("sources").map { it.jsonObject }.filter { it.hOptionalText("error") == "history_restricted" }.map { source ->
            PersonalSource(source.hText("id"), source.hText("name"), source.hText("status"), source.hLong("revision")) })
}

fun personalHistoryUnit(metric: String): String = when (metric) {
    "weight", "body_fat_mass", "skeletal_muscle_mass", "fat_free_mass", "body_protein", "body_minerals" -> "кг"
    "sleep", "active_minutes" -> "мин"
    "steps" -> "шагов"
    "active_energy" -> "ккал"
    "distance" -> "м"
    "body_fat_percent", "spo2" -> "%"
    "body_water" -> "л"
    "bmi" -> "кг/м²"
    "basal_metabolic_rate" -> "ккал/день"
    "visceral_fat_level" -> "ур."
    "heart_rate" -> "bpm"
    "temperature" -> "celsius"
    "hrv" -> "ms"
    "stress" -> "index"
    else -> ""
}

fun decodePersonalSensorPage(bytes: ByteArray): PersonalSensorPage = personalObject(bytes).let { row ->
    PersonalSensorPage(row.hInstant("from"), row.hInstant("to"), row.hArray("items").map { value -> val sample = value.jsonObject
        decodePersonalSensorSample(sample) },
        row.hText("fingerprint"), row.hInt("count"), row.hOptionalText("next_cursor"))
}
fun decodePersonalSensorSample(sample: JsonObject): PersonalSensorSample =
    PersonalSensorSample(sample.hText("id"), sample.hText("source_id"), sample.hText("source_name"),
            sample.hText("metric").also { require(it in PERSONAL_SENSOR_KINDS) }, sample.hInstant("start_at"), sample.hInstant("end_at"),
            sample.hZone("timezone"), sample.hOptionalText("local_date"), sample.hAmount("value"), sample.hAmount("reported_min"),
            sample.hAmount("reported_max"), sample.hText("unit"), sample.hStrings("quality"), sample.hLong("revision"),
            sample.hBooleanOrNull("excluded") ?: false, sample.hLongOrNull("annotation_revision") ?: 0, sample)

fun coherentPersonalSensorPages(pages: List<PersonalSensorPage>): Boolean {
    val first = pages.firstOrNull() ?: return true
    val items = pages.flatMap { it.items }
    return pages.all { it.fingerprint == first.fingerprint && it.count == first.count && it.from == first.from && it.to == first.to } &&
        items.size <= first.count && items.map { it.id }.toSet().size == items.size
}

fun decodePersonalRecordVersions(bytes: ByteArray): PersonalRecordVersions = personalObject(bytes).let { row ->
    row.hObject("annotation").let { it.hBoolean("excluded"); it.hBoolean("corrected"); it.hLong("revision"); require("observation" in it) }
    PersonalRecordVersions(row.hText("id"), row.hOptionalText("kind") ?: row.hText("metric"), row.hOptionalText("source_id"),
        row.hOptionalText("source_name"), row.hOptionalText("source_status"), row.hPositiveRevision("current_revision"), row.hInt("count"), row.hText("fingerprint"),
        row.hArray("versions").map { value -> val version = value.jsonObject
            PersonalRecordVersion(version.hPositiveRevision("revision"), version.hObject("snapshot"), version.hOptionalText("operation_id"),
                version.hOptionalText("created_at")?.also { Instant.parse(it) }) }, row.hObject("annotation"), row.hOptionalText("next_cursor"))
}
fun coherentPersonalRecordVersions(pages: List<PersonalRecordVersions>): Boolean {
    val first = pages.firstOrNull() ?: return true
    if (pages.any { it.id != first.id || it.metric != first.metric || it.sourceId != first.sourceId ||
        it.currentRevision != first.currentRevision || it.count != first.count || it.fingerprint != first.fingerprint }) return false
    val versions = pages.flatMap { it.versions }
    return versions.size <= first.count && versions.withIndex().all { (index, row) ->
        row.revision <= first.currentRevision && (index == 0 || row.revision < versions[index - 1].revision) }
}

fun decodePersonalRawReports(bytes: ByteArray): PersonalRawReportPage = personalObject(bytes).let { row ->
    PersonalRawReportPage(row.hDate("from"), row.hDate("to"), row.hArray("items").map { value -> val report = value.jsonObject
        val metric = report.hText("metric")
        require(metric in setOf("temperature", "workouts") && report.hText("qualification") == "source_only")
        require(report.hText("payload_hex").length <= 4096)
        PersonalRawReport(report.hText("id"), report.hText("source_id"), report.hText("source_name"), metric,
            report.hPositiveRevision("revision"), report.hPositiveRevision("source_version"), report.hDate("local_date"), report.hZone("timezone"),
            report.hInstant("observed_at"), report.hInstant("received_at"), report.hText("layout"), report.hText("reason"), report.hText("payload_hex"),
            report.hStrings("quality"), report.hBoolean("is_current"), report.hPositiveRevision("current_revision"), report.hObjectOrNull("interpretation"), report)
    }, row.hInt("count"), row.hText("fingerprint"), row.hOptionalText("next_cursor"))
}

internal fun decodePersonalAnalysisSummary(row: JsonObject): PersonalAnalysisSummary = PersonalAnalysisSummary(
    row.hText("id"), row.hText("source_id"), row.hOptionalText("source_name"), row.hText("provider_record_id"), row.hInstant("measured_at"),
    row.hPositiveRevision("revision"), row.hInstant("received_at"))
fun decodePersonalSourceAnalysis(bytes: ByteArray): PersonalSourceAnalysis = personalObject(bytes).hObject("analysis").let {
    PersonalSourceAnalysis(decodePersonalAnalysisSummary(it), it.hObject("payload"))
}

internal fun JsonObject.hText(key: String): String = ((this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: error("Expected text: $key"))
internal fun JsonObject.hOptionalText(key: String): String? = this[key]?.takeUnless { it == JsonNull }?.let { hText(key) }
internal fun JsonObject.hObject(key: String): JsonObject = requireNotNull(this[key] as? JsonObject)
internal fun JsonObject.hObjectOrNull(key: String): JsonObject? = this[key]?.takeUnless { it == JsonNull }?.let { it as? JsonObject ?: error("Expected object") }
internal fun JsonObject.hArray(key: String): JsonArray = requireNotNull(this[key] as? JsonArray)
internal fun JsonObject.hOptionalArray(key: String): List<JsonElement> = this[key]?.takeUnless { it == JsonNull }?.let { it as? JsonArray ?: error("Expected array") } ?: emptyList()
internal fun JsonObject.hStrings(key: String): List<String> = hOptionalArray(key).map { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content ?: error("Expected string") }
internal fun JsonObject.hLongOrNull(key: String): Long? = this[key]?.takeUnless { it == JsonNull }?.let { requireNotNull((it as? JsonPrimitive)?.takeUnless { p -> p.isString }?.longOrNull).also { n -> require(n >= 0) } }
internal fun JsonObject.hLong(key: String): Long = requireNotNull(hLongOrNull(key))
internal fun JsonObject.hPositiveRevision(key: String): Long = hLong(key).also { require(it > 0) }
internal fun JsonObject.hInt(key: String): Int = requireNotNull((this[key] as? JsonPrimitive)?.takeUnless { it.isString }?.intOrNull).also { require(it >= 0) }
internal fun JsonObject.hBooleanOrNull(key: String): Boolean? = this[key]?.takeUnless { it == JsonNull }?.let { requireNotNull((it as? JsonPrimitive)?.takeUnless { p -> p.isString }?.booleanOrNull) }
internal fun JsonObject.hBoolean(key: String): Boolean = requireNotNull(hBooleanOrNull(key))
internal fun JsonObject.hAmount(key: String): String? = this[key]?.takeUnless { it == JsonNull }?.let { value ->
    val primitive = value as? JsonPrimitive ?: error("Expected decimal")
    require(primitive.doubleOrNull?.isFinite() == true)
    primitive.content
}
internal fun JsonObject.hDate(key: String): String = hText(key).also { require(LocalDate.parse(it).toString() == it) }
internal fun JsonObject.hInstant(key: String): String = hText(key).also { Instant.parse(it) }
internal fun JsonObject.hZone(key: String): String = hText(key).also { TimeZone.of(it) }
