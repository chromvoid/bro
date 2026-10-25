package assistant.mobile.android

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.produceState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import assistant.mobile.core.GeneratedStrings
import assistant.mobile.core.GeneratedStrings.PersonalHistory as HistoryStrings
import assistant.mobile.core.LocalizedString
import assistant.mobile.core.personal.*
import assistant.mobile.design.R as DesignR
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull

internal fun JsonObject.archiveText(key: String): String? = (get(key) as? JsonPrimitive)?.contentOrNull
internal fun JsonObject.archiveObject(key: String): JsonObject? = get(key) as? JsonObject
private val archiveJson = Json { prettyPrint = true }

@Composable
internal fun PersonalHistoryDisclosure(title: String, tag: String, content: @Composable () -> Unit) {
    var open by remember(tag) { mutableStateOf(false) }
    TextButton(onClick = { open = !open }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag(tag)) { Text(title) }
    if (open) Column(verticalArrangement = Arrangement.spacedBy(8.dp), content = { content() })
}

@Composable
internal fun PersonalHistoryJson(title: String, value: JsonElement, tag: String) {
    PersonalHistoryDisclosure(title, tag) {
        SelectionContainer { Text(archiveJson.encodeToString(JsonElement.serializer(), value), style = MaterialTheme.typography.bodySmall) }
    }
}

/** The saved scalars retain their own nulls and units; current annotations stay outside snapshots. */
@Composable
internal fun PersonalHistoryRecordEvidence(record: JsonObject, timezone: String, locale: String, tag: String = "personal_record_evidence") {
    PersonalHistoryDisclosure(localized(HistoryStrings.Record.title), tag) {
        val base = listOf(
            "source_name" to HistoryStrings.Record.source,
            "start_at" to HistoryStrings.Record.start,
            "end_at" to HistoryStrings.Record.end,
            "local_date" to HistoryStrings.Record.date,
            "timezone" to HistoryStrings.Record.timezone,
            "received_at" to HistoryStrings.Record.received,
            "method" to HistoryStrings.Record.method,
            "algorithm_version" to HistoryStrings.Record.algorithm,
            "revision" to HistoryStrings.Record.revision,
        )
        base.forEach { (key, label) -> record.archiveText(key)?.takeUnless { key == "end_at" && it == record.archiveText("start_at") }?.let { value ->
            Text(localized(label), style = MaterialTheme.typography.labelMedium)
            Text(if (key in listOf("start_at", "end_at", "received_at")) personalMoment(value, timezone, locale) else archiveValue(value), style = MaterialTheme.typography.bodyMedium)
        } }
        val metadata = linkedMapOf<String, JsonPrimitive>()
        fun fields(value: JsonElement?) {
            (value as? JsonObject)?.forEach { (key, item) ->
                if (key in listOf("details", "resolution")) fields(item)
                else if (item is JsonPrimitive && item != JsonNull && archiveFieldLabel(key) != null) metadata[key] = item
            }
        }
        fields(record["origin"]); fields(record["data"])
        listOf("value", "reported_min", "reported_max", "unit").forEach { key -> (record[key] as? JsonPrimitive)?.takeIf { it != JsonNull }?.let { metadata[key] = it } }
        metadata.forEach { (key, value) ->
            archiveFieldLabel(key)?.let { Text(localized(it), style = MaterialTheme.typography.labelMedium) }
            Text(archiveValue(value.content), style = MaterialTheme.typography.bodyMedium)
        }
        if ((record["excluded"] as? JsonPrimitive)?.booleanOrNull == true) Text(localized(HistoryStrings.Record.excluded))
        if ((record["corrected"] as? JsonPrimitive)?.booleanOrNull == true) Text(localized(HistoryStrings.Record.corrected))
        PersonalQuality((record["quality"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNull })
        PersonalHistoryJson(localized(HistoryStrings.Record.original), JsonObject(record.filterKeys { it in listOf("value", "reported_min", "reported_max", "unit", "data", "origin", "quality", "original") }), "${tag}_original")
        PersonalHistoryJson(localized(HistoryStrings.Record.identifiers), JsonObject(record.filterKeys { it in listOf("id", "external_id", "source_id") }), "${tag}_identifiers")
    }
}

@Composable
private fun archiveValue(value: String): String {
    val message = when (value) {
    "measured" -> HistoryStrings.Record.Values.measured
    "observed" -> HistoryStrings.Record.Values.observed
    "interval_mean" -> HistoryStrings.Record.Values.`interval_mean`
    "reported_range" -> HistoryStrings.Record.Values.`reported_range`
    "sample_window_mean" -> HistoryStrings.Record.Values.`sample_window_mean`
    "derived" -> HistoryStrings.Record.Values.derived
    "running" -> HistoryStrings.Record.Values.running
    "recovery" -> HistoryStrings.Record.Values.recovery
    "background" -> HistoryStrings.Record.Values.background
    "unknown" -> HistoryStrings.Record.Values.unknown
    "phone_recorded" -> HistoryStrings.Record.Values.`phone_recorded`
    "ring" -> HistoryStrings.Record.Values.ring
    "manual" -> HistoryStrings.Record.Values.manual
    "automatic" -> HistoryStrings.Record.Values.automatic
    "manual_takeover" -> HistoryStrings.Record.Values.`manual_takeover`
    "completed" -> HistoryStrings.Record.Values.completed
    "interrupted" -> HistoryStrings.Record.Values.interrupted
    else -> return value
    }
    return localized(message)
}

private fun archiveFieldLabel(key: String): LocalizedString? = when (key) {
    "provider" -> HistoryStrings.Record.Fields.provider
    "device" -> HistoryStrings.Record.Fields.device
    "source" -> HistoryStrings.Record.Fields.source
    "record_id" -> HistoryStrings.Record.Fields.`record_id`
    "hardware" -> HistoryStrings.Record.Fields.hardware
    "firmware" -> HistoryStrings.Record.Fields.firmware
    "protocol" -> HistoryStrings.Record.Fields.protocol
    "opcode" -> HistoryStrings.Record.Fields.opcode
    "interpretation_version" -> HistoryStrings.Record.Fields.`interpretation_version`
    "measurement_kind" -> HistoryStrings.Record.Fields.`measurement_kind`
    "context" -> HistoryStrings.Record.Fields.context
    "sensor_location" -> HistoryStrings.Record.Fields.`sensor_location`
    "temperature_basis" -> HistoryStrings.Record.Fields.`temperature_basis`
    "definition_id" -> HistoryStrings.Record.Fields.`definition_id`
    "formula_version" -> HistoryStrings.Record.Fields.`formula_version`
    "time_basis" -> HistoryStrings.Record.Fields.`time_basis`
    "sport_type" -> HistoryStrings.Record.Fields.`sport_type`
    "status" -> HistoryStrings.Record.Fields.status
    "trigger" -> HistoryStrings.Record.Fields.trigger
    "stop_reason" -> HistoryStrings.Record.Fields.`stop_reason`
    "recovery_end_at" -> HistoryStrings.Record.Fields.`recovery_end_at`
    "sample_count" -> HistoryStrings.Record.Fields.`sample_count`
    "usable_sample_count" -> HistoryStrings.Record.Fields.`usable_sample_count`
    "sampled_seconds" -> HistoryStrings.Record.Fields.`sampled_seconds`
    "sample_coverage_ratio" -> HistoryStrings.Record.Fields.`sample_coverage_ratio`
    "gap_count" -> HistoryStrings.Record.Fields.`gap_count`
    "value" -> HistoryStrings.Record.Fields.value
    "reported_min" -> HistoryStrings.Record.Fields.`reported_min`
    "reported_max" -> HistoryStrings.Record.Fields.`reported_max`
    "unit" -> HistoryStrings.Record.Fields.unit
    "shape" -> HistoryStrings.Record.Fields.shape
    "unit_evidence" -> HistoryStrings.Record.Fields.`unit_evidence`
    "date_basis" -> HistoryStrings.Record.Fields.`date_basis`
    "provider_namespace" -> HistoryStrings.Record.Fields.`provider_namespace`
    "lineage_key" -> HistoryStrings.Record.Fields.`lineage_key`
    "confidence" -> HistoryStrings.Record.Fields.confidence
    else -> null
}

@Composable
internal fun PersonalHistoryVersions(page: PersonalRecordVersions, timezone: String, locale: String, units: PersonalDisplayUnits) {
    Text(localized(HistoryStrings.Versions.explanation), style = MaterialTheme.typography.bodySmall)
    if ((page.annotation["excluded"] as? JsonPrimitive)?.booleanOrNull == true) Text(localized(HistoryStrings.Versions.excluded))
    if ((page.annotation["corrected"] as? JsonPrimitive)?.booleanOrNull == true) Text(localized(HistoryStrings.Versions.corrected))
    Text(localized(HistoryStrings.Versions.count(page.count.toString())))
    PersonalHistoryJson(localized(HistoryStrings.Versions.annotations), page.annotation, "personal_record_annotations")
    if (page.versions.isEmpty()) Text(localized(HistoryStrings.Versions.empty))
    page.versions.forEach { version ->
        val raw = version.snapshot
        val value = raw.archiveText("value") ?: raw.archiveObject("data")?.archiveText("value")
        val unit = raw.archiveText("unit").orEmpty()
        val label = (if (page.metric in PERSONAL_SENSOR_KINDS) personalSensorAmount(value, unit, units, locale) else personalAmountText(page.metric, value, units, locale)) ?: stringResource(DesignR.string.personal_no_value)
        PersonalHistoryDisclosure("${personalMetricLabel(page.metric)} · $label · ${page.sourceName ?: localized(if (page.sourceId == null) HistoryStrings.Versions.manual else HistoryStrings.Versions.unnamedSource)} · v${version.revision} · ${localized(if (version.revision == page.currentRevision) HistoryStrings.Versions.current else HistoryStrings.Versions.previous)}", "personal_record_version_${version.revision}") {
            val minimum = raw.archiveText("reported_min")
            val maximum = raw.archiveText("reported_max")
            if (minimum != null || maximum != null) Text(localized(HistoryStrings.Versions.range(
                personalSensorAmount(minimum, unit, units, locale) ?: "—", personalSensorAmount(maximum, unit, units, locale) ?: "—")))
            Text(localized(version.createdAt?.let { HistoryStrings.Versions.savedAt(personalMoment(it, timezone, locale)) } ?: HistoryStrings.Versions.savedAtUnknown))
            PersonalHistoryRecordEvidence(raw, timezone, locale, "personal_record_version_evidence_${version.revision}")
            Text(localized(HistoryStrings.Versions.receipt(version.revision.toString(), version.operationId ?: localized(HistoryStrings.Versions.receiptUnknown))), style = MaterialTheme.typography.bodySmall)
            PersonalHistoryJson(localized(HistoryStrings.Versions.fullSnapshot), raw, "personal_record_snapshot_${version.revision}")
        }
    }
}

@Composable
internal fun PersonalHistoryRawReports(page: PersonalRawReportPage, timezone: String, locale: String) {
    val temperature = page.items.firstOrNull()?.metric != "workouts"
    var source by remember(page.from, page.to) { mutableStateOf("") }
    Text(localized(if (temperature) HistoryStrings.Raw.temperatureTitle else HistoryStrings.Raw.workoutTitle), style = MaterialTheme.typography.titleMedium)
    Text(localized(if (temperature) HistoryStrings.Raw.temperatureExplanation else HistoryStrings.Raw.workoutExplanation), style = MaterialTheme.typography.bodySmall)
    Text(localized(HistoryStrings.Raw.count(page.count.toString())), style = MaterialTheme.typography.bodySmall)
    val sources = page.items.associate { it.sourceId to it.sourceName }
    if (sources.size > 1) PersonalHistoryChoice(source, listOf("" to localized(HistoryStrings.Raw.allSources)) + sources.toList(), localized(if (temperature) HistoryStrings.Raw.temperatureSource else HistoryStrings.Raw.workoutSource), "personal_raw_source") { source = it }
    page.items.filter { source.isEmpty() || it.sourceId == source }.forEach { report ->
        PersonalHistoryDisclosure("${report.sourceName} · ${personalMoment(report.observedAt, timezone, locale)} · v${report.sourceVersion} · ${localized(if (report.isCurrent) HistoryStrings.Versions.current else HistoryStrings.Versions.previous)}", "personal_raw_report_${report.id}_${report.sourceVersion}") {
            Text(localized(HistoryStrings.Raw.received(localized(if (temperature) HistoryStrings.Raw.ringDate else HistoryStrings.Raw.phoneDate), report.localDate, report.timezone, personalMoment(report.receivedAt, timezone, locale))))
            Text(localized(HistoryStrings.Raw.format(report.layout, report.raw.archiveText("qualification") ?: report.reason, localized(if (temperature) HistoryStrings.Raw.ambiguousLayout else HistoryStrings.Raw.unconfirmedUnits))))
            if (!temperature) Text(localized(HistoryStrings.Raw.dateBasis(report.raw.archiveText("time_basis").orEmpty())))
            PersonalHistoryRecordEvidence(report.raw, timezone, locale, "personal_raw_evidence_${report.id}_${report.sourceVersion}")
            report.interpretation?.let { RawTemperatureInterpretation(it, report.id + report.sourceVersion) }
            PersonalHistoryJson(localized(HistoryStrings.Raw.packet), JsonObject(report.raw.filterKeys { it in listOf("namespace", "opcode", "payload_hex", "details", "source_hash") }), "personal_raw_packet_${report.id}_${report.sourceVersion}")
        }
    }
}

@Composable
private fun RawTemperatureInterpretation(value: JsonObject, tag: String) {
    val current = value.archiveText("definition_id") == "soul1230-temperature-legacy25"
    Text(localized(if (current) HistoryStrings.Raw.soul1230 else HistoryStrings.Raw.soul1214), style = MaterialTheme.typography.titleSmall)
    Text(localized(HistoryStrings.Raw.interpretationWarning))
    Text(listOfNotNull(value.archiveText("definition_id"), value.archiveText("formula_version"), value.archiveText("layout") ?: localized(HistoryStrings.Raw.projectionUnavailable)).joinToString(" · "))
    if (value.archiveText("status") == "unavailable") {
        val missing = (value["missing"] as? JsonArray)?.firstOrNull()?.let { (it as? JsonPrimitive)?.contentOrNull }
        Text(localized(if (missing == "temperature_sync_start_context_invalid") HistoryStrings.Raw.invalidTimeContext else HistoryStrings.Raw.missingTimeContext))
    } else {
        val context = value.archiveObject("time_context") ?: JsonObject(emptyMap())
        Text(localized(if (current) HistoryStrings.Raw.timeContext(value.archiveText("capture_local_date").orEmpty(), value.archiveText("day_start").orEmpty(), context.archiveText("timezone").orEmpty(), context.archiveText("sync_started_at").orEmpty(), context.archiveText("parsed_at").orEmpty(), value.archiveText("parse_local_date").orEmpty(), value.archiveText("ring_local_date").orEmpty())
            else HistoryStrings.Raw.legacyTimeContext(value.archiveText("capture_local_date").orEmpty(), value.archiveText("day_start").orEmpty(), value.archiveText("ring_local_date").orEmpty())))
        Text(localized(HistoryStrings.Raw.interval(value.archiveText("interval_minutes").orEmpty(), value.archiveText("as_of").orEmpty())))
        if (value.archiveText("status") == "empty") Text(localized(HistoryStrings.Raw.emptyInterpretation))
        (value["points"] as? JsonArray).orEmpty().forEach { item -> (item as? JsonObject)?.let { point ->
            Text(localized(HistoryStrings.Raw.offset(point.archiveText("offset_minutes").orEmpty(), localized(if (current) HistoryStrings.Raw.requestMidnight else HistoryStrings.Raw.receiptMidnight), point.archiveText("value").orEmpty(), point.archiveText("unit").orEmpty())))
        } }
    }
    value["legacy_capture_projection"]?.let { PersonalHistoryJson(localized(HistoryStrings.Raw.legacyProjection), it, "personal_raw_legacy_$tag") }
    PersonalHistoryJson(localized(HistoryStrings.Raw.conversionBasis), value, "personal_raw_conversion_$tag")
}

private data class SourceArchiveField(val path: String, val parts: List<String>, val key: String, val value: JsonElement)
private data class SourceArchiveSearch(val owner: String, val query: String, val page: Int, val rows: List<SourceArchiveField>, val total: Int, val failed: Boolean = false)

/** Bracket paths preserve literal dots, brackets and special provider keys without ambiguity. */
private fun sourceArchiveFields(value: JsonElement, path: String = "$", parts: List<String> = emptyList()): Sequence<SourceArchiveField> = sequence {
    when {
        value is JsonObject && value.isNotEmpty() -> value.forEach { (key, item) -> yieldAll(sourceArchiveFields(item, "$path[${JsonPrimitive(key)}]", parts + key)) }
        value is JsonArray && value.isNotEmpty() -> value.forEachIndexed { index, item -> yieldAll(sourceArchiveFields(item, "$path[$index]", parts + "[$index]")) }
        else -> yield(SourceArchiveField(path, parts, parts.lastOrNull() ?: "$", value))
    }
}

private val sourceArchiveLabels = mapOf(
    "total_weight" to DesignR.string.personal_metric_weight,
    "total_fat_percent" to DesignR.string.personal_metric_body_fat_percent,
    "body_fat_weight" to DesignR.string.personal_metric_body_fat_mass,
    "body_skeleton_muscle_weight" to DesignR.string.personal_metric_skeletal_muscle_mass,
    "fat_free_mass" to DesignR.string.personal_metric_fat_free_mass,
    "body_water" to DesignR.string.personal_metric_body_water,
    "body_protein" to DesignR.string.personal_metric_body_protein,
    "body_mineral" to DesignR.string.personal_metric_body_minerals,
    "total_bmi" to DesignR.string.personal_metric_bmi,
    "basal_metabolic_rate" to DesignR.string.personal_metric_basal_metabolic_rate,
    "visceral_fat_level" to DesignR.string.personal_metric_visceral_fat_level,
    "target_weight" to DesignR.string.personal_archive_fields_target_weight,
    "recommended_calories" to DesignR.string.personal_archive_fields_recommended_calories,
    "created_at" to DesignR.string.personal_archive_fields_created_at,
    "id" to DesignR.string.personal_archive_fields_id,
)

@Composable
internal fun PersonalHistorySourceAnalysis(analysis: PersonalSourceAnalysis, timezone: String, locale: String) {
    Text(analysis.summary.sourceName ?: stringResource(DesignR.string.personal_unnamed_source))
    Text(stringResource(DesignR.string.personal_archive_saved, personalMoment(analysis.summary.receivedAt, timezone, locale)))
    Text(stringResource(DesignR.string.personal_archive_version, analysis.summary.revision.toString()))
    Text(stringResource(DesignR.string.personal_archive_read_only), style = MaterialTheme.typography.bodySmall)
    var query by remember(analysis.summary.id) { mutableStateOf("") }
    var page by remember(analysis.summary.id, query) { mutableStateOf(0) }
    OutlinedTextField(query, { query = it.take(200) }, label = { Text(stringResource(DesignR.string.personal_archive_search)) }, modifier = Modifier.fillMaxWidth().testTag("personal_analysis_search"))
    val labels = sourceArchiveLabels.mapValues { (_, resource) -> stringResource(resource) }
    // Keep only a page and yield while traversing a large provider payload, as the web archive does.
    val search by produceState<SourceArchiveSearch?>(null, analysis, query, page, labels) {
        val expectedAnalysis = analysis
        val expectedQuery = query
        val expectedPage = page
        value = null
        try {
            value = withContext(Dispatchers.Default) {
                val needle = expectedQuery.trim()
                val rows = mutableListOf<SourceArchiveField>()
                var count = 0
                var work = 0
                for (field in sourceArchiveFields(expectedAnalysis.payload)) {
                    val rawValue = if (field.value is JsonPrimitive && field.value != JsonNull) field.value.content else field.value.toString()
                    val matches = needle.isEmpty() || field.path.contains(needle, true) || rawValue.contains(needle, true) || field.parts.any { labels[it]?.contains(needle, true) == true }
                    if (matches) {
                        if (count >= expectedPage * 40 && rows.size < 40) rows += field
                        count++
                    }
                    if (++work % 256 == 0) kotlinx.coroutines.yield()
                }
                SourceArchiveSearch(expectedAnalysis.summary.id, expectedQuery, expectedPage, rows, count)
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { value = SourceArchiveSearch(expectedAnalysis.summary.id, expectedQuery, expectedPage, emptyList(), 0, failed = true) }
    }
    val result = search?.takeIf { it.owner == analysis.summary.id && it.query == query && it.page == page }
    when {
        result == null -> Text(stringResource(DesignR.string.personal_archive_searching))
        result.failed -> Text(stringResource(DesignR.string.personal_archive_search_failed), color = MaterialTheme.colorScheme.error)
        else -> {
            Text(stringResource(DesignR.string.personal_archive_count, result.total.toString()))
            result.rows.forEachIndexed { index, field ->
                Text(labels[field.key] ?: field.key, style = MaterialTheme.typography.labelMedium)
                if (field.key in labels) Text(field.key, style = MaterialTheme.typography.bodySmall)
                Text(stringResource(when (field.parts.firstOrNull()) { "detail" -> DesignR.string.personal_archive_detail; "summary" -> DesignR.string.personal_archive_summary; else -> DesignR.string.personal_archive_source_data }), style = MaterialTheme.typography.bodySmall)
                Text(field.path, style = MaterialTheme.typography.bodySmall)
                val value = field.value
                val label = when {
                    value == JsonNull -> stringResource(DesignR.string.personal_archive_null)
                    value is JsonPrimitive && !value.isString && value.booleanOrNull != null -> stringResource(if (value.booleanOrNull == true) DesignR.string.personal_archive_true else DesignR.string.personal_archive_false)
                    value is JsonPrimitive && value.isString && value.content.isEmpty() -> stringResource(DesignR.string.personal_archive_empty_string)
                    value is JsonPrimitive -> value.content
                    value is JsonArray -> stringResource(DesignR.string.personal_archive_empty_array)
                    else -> stringResource(DesignR.string.personal_archive_empty_object)
                }
                var offset by remember(analysis.summary.id, query, page, field.path) { mutableStateOf(0) }
                SelectionContainer { Text(label.drop(offset).take(2000), style = MaterialTheme.typography.bodyMedium) }
                if (label.length > 2000) {
                    Text(stringResource(DesignR.string.personal_archive_text_part, (offset + 1).toString(), minOf(offset + 2000, label.length).toString(), label.length.toString()), style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = { offset = (offset - 2000).coerceAtLeast(0) }, enabled = offset > 0, modifier = Modifier.heightIn(min = 48.dp).testTag("personal_analysis_previous_text_$index")) { Text(stringResource(DesignR.string.personal_archive_previous_text)) }
                    TextButton(onClick = { offset += 2000 }, enabled = offset + 2000 < label.length, modifier = Modifier.heightIn(min = 48.dp).testTag("personal_analysis_more_$index")) { Text(stringResource(DesignR.string.personal_archive_next_text)) }
                }
            }
            if (result.rows.isEmpty()) Text(stringResource(DesignR.string.personal_archive_no_matches))
            if (result.total > 40) {
                Text(stringResource(DesignR.string.personal_archive_page, (page + 1).toString(), ((result.total + 39) / 40).toString()))
                TextButton(onClick = { page-- }, enabled = page > 0, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(DesignR.string.personal_archive_previous)) }
                TextButton(onClick = { page++ }, enabled = (page + 1) * 40 < result.total, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(DesignR.string.personal_archive_next)) }
            }
        }
    }
}
