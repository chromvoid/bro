package assistant.mobile.core.personal

import assistant.mobile.core.HttpOutcome
import assistant.mobile.core.NativeHttpClient
import assistant.mobile.core.SessionLease
import io.ktor.http.encodeURLParameter
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.plus
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull

interface PersonalSourcesReader {
    suspend fun sources(lease: SessionLease): List<PersonalSourceEntry>
    suspend fun day(day: String, sourceId: String?, lease: SessionLease): PersonalSourceDay
    suspend fun syncRun(id: String, lease: SessionLease): PersonalDdxRun
    suspend fun week(metric: String, day: String, lease: SessionLease): PersonalSourceDay
}

class NativePersonalSourcesApi(private val http: NativeHttpClient) : PersonalSourcesReader {
    override suspend fun sources(lease: SessionLease): List<PersonalSourceEntry> =
        get("/v1/personal/sources", lease, ::decodePersonalSources)

    override suspend fun day(day: String, sourceId: String?, lease: SessionLease): PersonalSourceDay {
        require(LocalDate.parse(day).toString() == day)
        var cursor: String? = null
        var result: PersonalSourceDay? = null
        val seen = mutableSetOf<String>()
        var count = 0
        do {
            val path = "/v1/personal/resolved?from=$day&to=$day&limit=${if (sourceId == null) 1 else 500}" +
                (sourceId?.let { "&source_id=${it.encodeURLParameter()}" } ?: "") +
                (cursor?.let { "&cursor=${it.encodeURLParameter()}" } ?: "")
            val page = get(path, lease, ::decodePersonalSourceDay)
            require(page.resolved.from == day && page.resolved.to == day && page.daily.all { it.date == day })
            if (sourceId != null) require(page.series.all { it.sourceId == sourceId })
            val old = result
            require(old == null || (old.resolved.fingerprint == page.resolved.fingerprint && old.resolved.timezone == page.resolved.timezone))
            result = if (old == null) page else old.copy(series = old.series + page.series,
                resolved = old.resolved.copy(nextCursor = page.resolved.nextCursor))
            cursor = if (sourceId == null) null else page.resolved.nextCursor
            count++
            if (cursor != null) require(seen.add(cursor))
        } while (cursor != null && count < 40)
        // Never silently present an incomplete source day as complete.
        if (cursor != null) throw PersonalReadException(HttpOutcome.InvalidResponse)
        return requireNotNull(result)
    }

    override suspend fun syncRun(id: String, lease: SessionLease): PersonalDdxRun =
        get("/v1/personal/sync-runs/${id.encodeURLParameter()}?wait_seconds=0", lease, ::decodePersonalDdxRun)
            .also { require(it.id == id) }

    override suspend fun week(metric: String, day: String, lease: SessionLease): PersonalSourceDay {
        require(LocalDate.parse(day).toString() == day)
        val first = LocalDate.parse(day).plus(-6, DateTimeUnit.DAY).toString()
        return get("/v1/personal/resolved?from=$first&to=$day&metric=${metric.encodeURLParameter()}&limit=1", lease, ::decodePersonalSourceDay)
            .also { page -> require(page.resolved.from == first && page.resolved.to == day &&
                page.daily.all { it.date in first..day && it.metric.kind == metric }) }
    }

    private suspend fun <T> get(path: String, lease: SessionLease, decode: (ByteArray) -> T): T {
        val reply = http.request("GET", path, lease = lease)
        if (reply !is HttpOutcome.Success) throw PersonalReadException(reply)
        return try { decode(reply.body) } catch (_: Exception) { throw PersonalReadException(HttpOutcome.InvalidResponse) }
    }
}

fun decodePersonalSources(bytes: ByteArray): List<PersonalSourceEntry> = personalObject(bytes).sArray("sources").map {
    val row = it.jsonObject
    val device = row.sObjectOrNull("device")
    PersonalSourceEntry(row.sText("id"), row.sText("name"), row.sStrings("kinds"),
        row.sText("status").also { require(it in setOf("connected", "unavailable", "disabled")) }, row.sLong("revision"),
        row.sStrings("metric_keys"), PersonalSourceDevice(device?.sTextOrNull("kind") ?: "custom",
            device?.sTextOrNull("profile"), device?.sTextOrNull("collector_id"), device?.sBooleanOrNull("collector_enabled")),
        row.sTextOrNull("last_received_at"), row.sTextOrNull("last_measured_at"), row.sTextOrNull("created_at"),
        row.sTextOrNull("error"), row.sObjectOrNull("connection")?.let(::decodePersonalDdxConnection))
}

fun decodePersonalSourceDay(bytes: ByteArray): PersonalSourceDay {
    val row = personalObject(bytes)
    return PersonalSourceDay(decodePersonalResolved(bytes), row.sArrayOrEmpty("series").map { value ->
        val item = value.jsonObject
        PersonalSourceReading(item.sText("id"), item.sTextOrNull("metric") ?: item.sText("kind"), item.sText("start_at"), item.sTextOrNull("end_at"),
            item.sAmount("value") ?: item.sObjectOrNull("data")?.sAmount("value"), item.sTextOrNull("unit") ?: "", item.sTextOrNull("source_id"), item.sTextOrNull("source_name"),
            item.sLongOrNull("revision"), item.sTextOrNull("measurement_kind"), item.sTextOrNull("sensor_location"),
            item.sTextOrNull("definition_id"), item.sTextOrNull("temperature_basis"), item.sTextOrNull("context"), item.sTextOrNull("report_id"))
    }, row.sArrayOrEmpty("policies").map { decodePersonalSourcePolicy(it.jsonObject) },
        row.sObjectOrNull("source_identities")?.mapValues { (_, value) -> value.jsonObject.let {
            PersonalSourceIdentity(it.sTextOrNull("physical_id"), it.sBoolean("confirmed"), it.sLong("revision"))
        } } ?: emptyMap(), combinePersonalSourceComparisons(row.sArray("daily").flatMap { value ->
            runCatching {
                value.jsonObject.sObjectOrNull("resolution")?.sArrayOrEmpty("comparisons")?.map { comparison ->
                    val item = comparison.jsonObject
                    val ids = item.sStrings("source_ids").also { require(it.size == 2) }
                    val totals = item.sArray("totals").map { number ->
                        requireNotNull((number as JsonPrimitive).content.toDoubleOrNull()).also { require(it.isFinite()) }
                    }.also { require(it.size == 2) }
                    PersonalSourceComparison(ids, item.sLong("count").toInt().also { require(it >= 0) }, totals,
                        requireNotNull(item.sAmount("mean_difference")).toDouble(),
                        requireNotNull(item.sAmount("mean_absolute_difference")).toDouble(),
                        (item.sLongOrNull("outside_reference") ?: 0).toInt().also { require(it >= 0) })
                }.orEmpty()
            }.getOrDefault(emptyList())
        }), row.sArrayOrEmpty("catalog").map { value -> value.jsonObject.let {
            PersonalCatalogMetric(it.sText("key"), it.sText("unit"), it.sText("shape"), it.sTextOrNull("label"))
        } })
}

fun decodePersonalSourcePolicy(row: JsonObject): PersonalSourcePolicy = PersonalSourcePolicy(row.sTextOrNull("id"),
    row.sText("metric"), row.sArray("source_ids").map { if (it == JsonNull) null else (it as JsonPrimitive).content },
    row.sText("effective_from").also { require(LocalDate.parse(it).toString() == it) }, row.sLong("revision"))

internal fun JsonObject.sText(key: String): String = requireNotNull(sTextOrNull(key))
internal fun JsonObject.sTextOrNull(key: String): String? = this[key]?.takeUnless { it == JsonNull }?.let {
    require(it is JsonPrimitive && it.isString); it.content
}
internal fun JsonObject.sArray(key: String): JsonArray = requireNotNull(this[key] as? JsonArray)
internal fun JsonObject.sArrayOrEmpty(key: String): JsonArray = this[key]?.takeUnless { it == JsonNull }?.let { it as JsonArray } ?: JsonArray(emptyList())
internal fun JsonObject.sObjectOrNull(key: String): JsonObject? = this[key]?.takeUnless { it == JsonNull }?.jsonObject
internal fun JsonObject.sLong(key: String): Long = requireNotNull(sLongOrNull(key)).also { require(it >= 0) }
internal fun JsonObject.sLongOrNull(key: String): Long? = this[key]?.takeUnless { it == JsonNull }?.let {
    require(it is JsonPrimitive && !it.isString); requireNotNull(it.longOrNull)
}
internal fun JsonObject.sBoolean(key: String): Boolean = requireNotNull(sBooleanOrNull(key))
internal fun JsonObject.sBooleanOrNull(key: String): Boolean? = this[key]?.takeUnless { it == JsonNull }?.let {
    require(it is JsonPrimitive && !it.isString); requireNotNull(it.booleanOrNull)
}
internal fun JsonObject.sStrings(key: String): List<String> = sArrayOrEmpty(key).map { require(it is JsonPrimitive && it.isString); it.content }
internal fun JsonObject.sAmount(key: String): String? = this[key]?.takeUnless { it == JsonNull }?.let {
    require(it is JsonPrimitive && it.content.toDoubleOrNull()?.isFinite() == true); it.content
}
