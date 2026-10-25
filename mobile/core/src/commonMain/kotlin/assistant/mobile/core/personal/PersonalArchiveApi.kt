package assistant.mobile.core.personal

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject

private val temporalBases = setOf("measurement_capture", "owner_context_anchor", "historical_day_end", "source_sample")

fun decodePersonalArchivedResult(bytes: ByteArray): PersonalArchivedResult = decodePersonalArchivedResult(personalObject(bytes))
fun decodePersonalArchivedResult(row: JsonObject): PersonalArchivedResult {
    val definition = row.hObject("definition_snapshot").let { definition ->
        PersonalDerivedDefinition(definition.hText("id"), definition.hText("label"), definition.hText("unit"),
            definition.hText("formula_version"), definition.hText("qualification"), definition.hStrings("required_inputs"),
            definition.hStrings("evidence"), definition)
    }
    val score = decodePersonalArchivedScore(row.hObject("score"))
    val result = PersonalArchivedResult(row.hText("result_id").also { require(it.length == 64) }, row.hPositiveRevision("version"),
        row.hText("definition_id"), row.hText("formula_version"), row.hOptionalText("source_scope"), row.hDate("date"),
        row.hText("evaluation").also { require(it in setOf("day", "night", "instant")) }, row.hZone("time_zone"), row.hInstant("as_of"),
        row.hText("temporal_basis").also { require(it in temporalBases) }, score, definition, row.hObject("normalized_inputs"),
        row.hArray("context_revisions").map { decodePersonalDerivedInput(it.jsonObject) }, row.hText("input_adapter_version"),
        row.hText("implementation_digest"), row.hObject("implementation_source"), row.hText("operation_id"), row.hInstant("created_at"),
        row.hBoolean("is_current"), row)
    require(result.definitionId == definition.id && result.definitionId == score.definitionId && result.formulaVersion == definition.formulaVersion &&
        result.formulaVersion == score.formulaVersion && definition.unit == score.unit && definition.qualification == score.qualification &&
        result.sourceScope == score.context.sourceId && result.date == score.context.date && result.evaluation == score.context.evaluation &&
        result.timezone == score.context.timezone && result.asOf == score.context.asOf)
    require(result.implementationSource.hText("format") == "git_blob_sha1")
    result.implementationSource.hArray("files").forEach { value -> val file = value.jsonObject
        file.hText("path"); file.hText("git_blob_sha1"); file.hText("sha256") }
    return result
}

fun decodePersonalArchivedScore(row: JsonObject): PersonalArchivedScore {
    val context = row.hObject("context")
    val confidence = row.hObject("confidence")
    val coverage = row.hObject("coverage")
    val components = row.hArray("components").map { value -> val component = value.jsonObject
        val synthetic = component.hBoolean("synthetic")
        val amount = requireNotNull(component["value"])
        validateComponentValue(amount)
        val reason = component.hOptionalText("reason")
        if (synthetic) require(amount is JsonPrimitive && !amount.isString && amount.doubleOrNull?.isFinite() == true &&
            reason in setOf("soul_reference_default", "soul_age_reference"))
        PersonalDerivedComponent(component.hText("id"), amount, component.hText("unit"), synthetic, component.hStrings("quality"), reason)
    }
    return PersonalArchivedScore(row.hText("definition_id"), row.hText("formula_version"), row.hText("qualification"),
        row.hText("status").also { require(it in setOf("available", "partial", "unavailable", "unqualified")) }, row.hAmount("value"),
        row.hText("unit"), row.hStrings("missing"), row.hStrings("quality"),
        PersonalDerivedConfidence(confidence.hText("level"), confidence.hInt("available_inputs"), confidence.hInt("required_inputs")),
        PersonalDerivedCoverage(coverage.hInt("observed"), coverage.hInt("expected"), coverage.hAmount("ratio")),
        PersonalDerivedContext(context.hText("evaluation").also { require(it in setOf("day", "night", "instant")) }, context.hDate("date"),
            context.hZone("time_zone"), context.hInstant("as_of"), context.hOptionalText("source_id")), components,
        row.hArray("input_revisions").map { decodePersonalDerivedInput(it.jsonObject) }, row.hObjectOrNull("baseline"), row.hText("fingerprint"),
        row.hObjectOrNull("details")?.hOptionalArray("series")?.map { decodePersonalArchivedScore(it.jsonObject) } ?: emptyList(), row)
}

private fun validateComponentValue(value: JsonElement) {
    when (value) {
        JsonNull -> Unit
        is JsonPrimitive -> require(!value.isString && (value.booleanOrNull != null || value.doubleOrNull?.isFinite() == true))
        is JsonArray -> value.forEach { item -> when (item) {
            is JsonPrimitive -> require(!item.isString && item.doubleOrNull?.let { it.isFinite() && it >= 0 } == true)
            is JsonObject -> require(item.hText("stage") in setOf("deep", "light", "rem", "awake") && requireNotNull(item.hAmount("minutes")).toDouble() >= 0)
            else -> error("Invalid component value")
        } }
        else -> error("Invalid component value")
    }
}

/** Source measurements and captured context remain different reference variants. */
fun decodePersonalDerivedInput(row: JsonObject): PersonalDerivedInput {
    val type = row.hText("type")
    val common = setOf("type", "id", "revision")
    val fields: Set<String> = when (type) {
        "record", "sample", "activity_report", "manual_day_total" -> {
            row.hLong("override_revision"); row.hLong("annotation_revision"); row.hPositiveRevision("source_version")
            row.hOptionalText("annotation_id"); row.hText("metric")
            common + setOf("source_id", "metric", "observed_at", "source_version", "override_revision", "annotation_revision", "annotation_id")
        }
        "profile", "preference" -> {
            require(row["source_id"] == JsonNull && row["observed_at"] == JsonNull)
            row.hText("metric")
            common + setOf("source_id", "metric", "observed_at")
        }
        "resolution_state" -> { row.hText("fingerprint"); common + "fingerprint" }
        "evaluation_capture" -> {
            row.hInstant("captured_at"); require(row.hText("basis") in temporalBases)
            common + setOf("captured_at", "basis")
        }
        "derivation_context" -> { row.hText("fingerprint"); common + setOf("source_id", "fingerprint") }
        "software_context" -> {
            val choices = setOf("formula_version", "producer_version", "fingerprint")
            require(choices.count { it in row } == 1)
            choices.filter { it in row }.forEach { row.hText(it) }
            common + choices + "source_id"
        }
        "source_identity" -> {
            row.hText("source_id"); row.hOptionalText("physical_id"); row.hBoolean("confirmed")
            common + setOf("source_id", "physical_id", "confirmed")
        }
        else -> error("Unsupported derived input reference")
    }
    require(row.keys.all { it in fields })
    return PersonalDerivedInput(type, row.hText("id"), row.hLong("revision"), row.hOptionalText("source_id"),
        row.hOptionalText("metric"), row.hOptionalText("observed_at"), row)
}

fun decodePersonalDerivedArchive(bytes: ByteArray): PersonalDerivedArchivePage = personalObject(bytes).let { row ->
    require(row.hText("persistence") == "immutable_versions")
    val refresh = row.hObject("refresh")
    PersonalDerivedArchivePage(row.hDate("from"), row.hDate("to"), row.hZone("time_zone"),
        row.hText("scope").also { require(it in setOf("all", "owner", "source")) },
        row.hArray("items").map { decodePersonalArchivedResult(it.jsonObject) }, row.hInt("total_versions"), row.hText("fingerprint"),
        PersonalDerivedRefresh(refresh.hBoolean("pending"), refresh.hInt("pending_runs"), refresh.hInt("failed_runs")), row.hOptionalText("next_cursor"))
}

fun personalArchivedScoreValue(score: PersonalArchivedScore): String? = score.value?.takeIf {
    score.status in setOf("available", "partial") && it.toDoubleOrNull()?.isFinite() == true
}
