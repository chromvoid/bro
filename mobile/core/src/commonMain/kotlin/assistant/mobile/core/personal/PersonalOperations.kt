package assistant.mobile.core.personal

import assistant.mobile.core.GeneratedStrings
import assistant.mobile.core.LocalizedString
import assistant.mobile.core.NativeJson
import assistant.mobile.core.body.BODY_PRESENTATION_OPERATION
import assistant.mobile.core.body.validateBodyPresentationSubmissionReceipt
import assistant.mobile.core.journal.Submission
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlin.math.floor
import kotlin.time.Instant

const val PERSONAL_OPERATIONS = "/v1/personal/operations"
const val PERSONAL_LOOKUP = "/v1/personal/operations/by-key/"

/** Exact text and the original baseline survive both a revision conflict and recreation. */
@Serializable
data class PersonalProfileDraft(
    val userId: String, val revision: Long,
    val birthDate: String, val heightCm: String, val gender: String, val timezone: String,
    val originalBirthDate: String = birthDate, val originalHeightCm: String = heightCm,
    val originalGender: String = gender, val originalTimezone: String = timezone,
)

@Serializable
data class PersonalGoalDraft(
    val kind: String, val target: String, val revision: Long, val displayUnits: String,
    val canonicalTarget: String? = null, val initialDisplayTarget: String = target,
)

@Serializable
data class PersonalWeightDraft(val weightKg: String = "", val measuredAt: String, val timezone: String = "")

@Serializable
data class PersonalCaliperDraft(
    val folds: Map<String, String> = emptyMap(), val measuredAt: String,
    val gender: String = "", val genderOverridden: Boolean = false,
    val ageYears: String = "", val note: String = "", val timezone: String = "",
)

@Serializable
data class PersonalUnitsDraft(val userId: String, val revision: Long, val units: String)

data class PersonalOperation(val kind: String, val payload: JsonObject, val expectedRevisions: Map<String, Long> = emptyMap()) {
    fun bytes(): ByteArray = buildJsonObject {
        put("kind", kind); put("payload", payload)
        put("expected_revisions", JsonObject(expectedRevisions.mapValues { JsonPrimitive(it.value) }))
    }.toString().encodeToByteArray()
}

class PersonalValidationException(val messageKey: LocalizedString) : IllegalArgumentException()
private fun invalid(message: LocalizedString): Nothing = throw PersonalValidationException(message)

/** Decimal input syntax only; no observation is inferred or calculated by this normalization. */
fun parsePersonalInputAmount(input: String): String? {
    val value = input.trim().replace(',', '.')
    if (value.isEmpty() || value.length > 100) return null
    val parts = value.split('.')
    if (parts.size !in 1..2 || parts.any { it.isEmpty() || it.any { c -> c !in '0'..'9' } }) return null
    return value.takeIf { it.toDoubleOrNull()?.isFinite() == true }
}

private fun canonicalAmount(input: String, minimum: Double, maximum: Double, error: LocalizedString): String {
    val value = parsePersonalInputAmount(input) ?: invalid(error)
    if (value.toDouble() !in minimum..maximum) invalid(error)
    return value
}

fun personalProfileDraftChanged(draft: PersonalProfileDraft): Boolean =
    draft.birthDate != draft.originalBirthDate || draft.gender != draft.originalGender ||
        draft.timezone != draft.originalTimezone ||
        !samePersonalAmount(draft.heightCm.takeIf { it.isNotBlank() }?.trim()?.replace(',', '.'),
            draft.originalHeightCm.takeIf { it.isNotBlank() })

fun profilePersonalOperation(draft: PersonalProfileDraft, today: String, revision: Long = draft.revision): PersonalOperation {
    require(draft.userId.isNotBlank() && revision >= 0)
    val birth = draft.birthDate.takeIf { it.isNotEmpty() }
    if (birth != null && (runCatching { LocalDate.parse(birth).toString() == birth }.getOrDefault(false).not() || birth > today)) invalid(GeneratedStrings.Personal.birthInvalid)
    val height = draft.heightCm.takeIf { it.isNotBlank() }?.let {
        canonicalAmount(it, 50.0, 260.0, GeneratedStrings.Personal.heightRange)
    }
    if (runCatching { TimeZone.of(draft.timezone) }.isFailure) invalid(GeneratedStrings.Personal.zoneInvalid)
    require(draft.gender in setOf("", "male", "female"))
    val changes = buildJsonObject {
        if (birth != draft.originalBirthDate.takeIf { it.isNotEmpty() }) put("birth_date", birth?.let(::JsonPrimitive) ?: JsonNull)
        if (!samePersonalAmount(height, draft.originalHeightCm.takeIf { it.isNotBlank() })) put("height_cm", height?.let(::JsonPrimitive) ?: JsonNull)
        if (draft.gender != draft.originalGender) put("gender", draft.gender.takeIf { it.isNotEmpty() }?.let(::JsonPrimitive) ?: JsonNull)
        if (draft.timezone != draft.originalTimezone) put("timezone", draft.timezone)
    }
    require(changes.isNotEmpty()) { "Nothing changed" }
    return PersonalOperation("personal.profile.update", changes, mapOf(draft.userId to revision))
}

fun unitsPersonalOperation(draft: PersonalUnitsDraft, revision: Long = draft.revision): PersonalOperation {
    PersonalDisplayUnits.fromWire(draft.units)
    require(draft.userId.isNotBlank() && revision >= 0)
    return PersonalOperation("personal.profile.update", buildJsonObject { put("display_units", draft.units) }, mapOf(draft.userId to revision))
}

fun goalPersonalOperation(draft: PersonalGoalDraft, revision: Long = draft.revision): PersonalOperation {
    require(draft.kind in PERSONAL_TARGET_KINDS && revision >= 0)
    val units = PersonalDisplayUnits.fromWire(draft.displayUnits)
    val raw = draft.target.takeIf { it.isNotBlank() }?.let {
        parsePersonalInputAmount(it) ?: invalid(GeneratedStrings.Errors.invalid)
    }
    val canonical = when {
        raw == null -> null
        draft.kind == "distance" && samePersonalAmount(raw, parsePersonalInputAmount(draft.initialDisplayTarget)) -> draft.canonicalTarget
        draft.kind == "distance" -> personalDistanceTargetMeters(raw, units) ?: invalid(GeneratedStrings.Errors.invalid)
        draft.kind in setOf("weight", "active_energy") -> raw
        else -> floor(raw.toDouble() + 0.5).toLong().toString()
    }
    if (canonical != null) {
        val maximum = if (draft.kind in setOf("sleep", "active_minutes")) 1440.0 else if (draft.kind == "weight") 500.0 else 1000000.0
        if (canonical.toDouble() !in (if (draft.kind == "weight") 20.0 else 0.0)..maximum) invalid(GeneratedStrings.Errors.invalid)
    }
    return PersonalOperation("personal.preference.set", buildJsonObject {
        put("kind", draft.kind); put("target", canonical?.let(::JsonPrimitive) ?: JsonNull); put("revision", revision)
    })
}

fun personalMeasuredInstant(value: String, timezone: String, nowMillis: Long): String {
    val instant = runCatching { LocalDateTime.parse(value).toInstant(TimeZone.of(timezone)) }
        .getOrElse { invalid(GeneratedStrings.Personal.timeRequired) }
    if (instant.toEpochMilliseconds() > nowMillis + 60000) invalid(GeneratedStrings.Personal.futureTime)
    return instant.toString()
}

fun weightPersonalOperation(draft: PersonalWeightDraft, timezone: String, nowMillis: Long): PersonalOperation {
    val weight = canonicalAmount(draft.weightKg, 20.0, 500.0, GeneratedStrings.Personal.weightRange)
    return PersonalOperation("personal.weight.add", buildJsonObject {
        put("weight_kg", weight); put("measured_at", personalMeasuredInstant(draft.measuredAt, timezone, nowMillis))
    })
}

fun caliperPersonalOperation(draft: PersonalCaliperDraft, timezone: String, nowMillis: Long): PersonalOperation {
    require(draft.folds.keys.all { it in PERSONAL_FOLD_SITES })
    val filled = draft.folds.filterValues { it.isNotBlank() }
    if (filled.isEmpty()) invalid(GeneratedStrings.Personal.Caliper.foldRequired)
    val folds = filled.mapValues { (site, value) ->
        val label = when (site) {
            "abdomen" -> "Живот"
            "suprailiac" -> "Бок над тазом"
            "chest" -> "Грудь"
            "thigh" -> "Бедро"
            else -> "Трицепс"
        }
        canonicalAmount(value, 0.000000001, 100.0, GeneratedStrings.Personal.Caliper.foldRange(label))
    }
    val age = if (draft.ageYears.isBlank()) null else draft.ageYears.trim().let { text ->
        if (text.any { it !in '0'..'9' }) invalid(GeneratedStrings.Personal.Caliper.ageRange)
        text.toIntOrNull()?.takeIf { it in 0..120 } ?: invalid(GeneratedStrings.Personal.Caliper.ageRange)
    }
    require(draft.gender in setOf("", "male", "female") && draft.note.trim().length <= 1000)
    return PersonalOperation("personal.caliper.add", buildJsonObject {
        put("folds_mm", JsonObject(folds.mapValues { JsonPrimitive(it.value) }))
        put("measured_at", personalMeasuredInstant(draft.measuredAt, timezone, nowMillis))
        if (draft.genderOverridden) put("gender", draft.gender.takeIf { it.isNotEmpty() }?.let(::JsonPrimitive) ?: JsonNull)
        if (age != null) put("age_years", age)
        if (draft.note.isNotBlank()) put("note", draft.note.trim())
    })
}

data class PersonalReceipt(val operationId: String, val kind: String, val raw: JsonObject)

fun decodePersonalReceipt(bytes: ByteArray): PersonalReceipt = personalOperationObject(bytes).let { row ->
    PersonalReceipt(row.requiredText("operation_id"), row.requiredText("kind"), row)
}

/** Matching a receipt to its admitted proposal precedes any durable RESOLVED transition. */
fun validatePersonalSubmissionReceipt(entry: Submission, bytes: ByteArray) {
    if (entry.endpoint != PERSONAL_OPERATIONS) return
    val command = personalOperationObject(entry.body)
    val receipt = decodePersonalReceipt(bytes)
    require(receipt.kind == command.requiredText("kind"))
    if (receipt.kind in PERSONAL_SOURCE_OPERATION_KINDS) {
        validatePersonalSourceReceipt(entry, bytes)
        return
    }
    val payload = command.getValue("payload").jsonObject
    val expected = command.getValue("expected_revisions").jsonObject
    val row = receipt.raw
    when (receipt.kind) {
        BODY_PRESENTATION_OPERATION -> validateBodyPresentationSubmissionReceipt(entry, bytes)
        "personal.profile.update" -> {
            val saved = row.getValue("profile").jsonObject
            val profile = decodePersonalProfile(saved)
            require(expected.size == 1 && expected[profile.userId]?.jsonPrimitive?.longOrNull?.let { profile.revision > it } == true)
            payload.forEach { (field, proposed) -> require(samePersonalWireValue(saved[field], proposed)) }
        }
        "personal.preference.set" -> {
            val saved = decodePersonalPreference(row.getValue("preference").jsonObject)
            require(saved.kind == payload.requiredText("kind") && saved.revision > requireNotNull(payload["revision"]?.jsonPrimitive?.longOrNull))
            require(samePersonalWireValue(row.getValue("preference").jsonObject["target"], payload["target"]))
        }
        "personal.weight.add" -> {
            val id = row.requiredText("measurement_id")
            val record = row.getValue("record").jsonObject
            require(record.requiredText("id") == id && record.requiredText("kind") == "weight" && record.requiredText("method") == "self_report")
            require(record["revision"]?.jsonPrimitive?.longOrNull?.let { it > 0 } == true)
            require(samePersonalWireValue(row["weight_kg"], payload["weight_kg"]) && samePersonalWireValue(record.getValue("data").jsonObject["value"], payload["weight_kg"]))
            require(Instant.parse(record.requiredText("start_at")) == Instant.parse(payload.requiredText("measured_at")))
        }
        "personal.caliper.add" -> {
            val saved = row.getValue("measurement").jsonObject
            val measurement = decodePersonalCaliperMeasurement(saved)
            require(measurement.id == row.requiredText("measurement_id") && measurement.revision > 0)
            require(Instant.parse(measurement.measuredAt) == Instant.parse(payload.requiredText("measured_at")))
            val folds = payload.getValue("folds_mm").jsonObject
            require(measurement.foldsMm.keys == folds.keys)
            folds.forEach { (site, value) -> require(samePersonalAmount(measurement.foldsMm[site], value.jsonPrimitive.content)) }
            listOf("gender", "age_years", "note").filter { it in payload }.forEach { require(samePersonalWireValue(saved[it], payload[it])) }
        }
        "personal.derived.backfill" -> validatePersonalBackfillSubmissionReceipt(entry, bytes)
        "personal.sensor.exclude", "personal.sensor.restore" -> validatePersonalSensorSubmissionReceipt(entry, bytes)
        else -> error("Unsupported personal receipt")
    }
}

internal fun personalOperationObject(bytes: ByteArray): JsonObject = NativeJson.parseToJsonElement(bytes.decodeToString(throwOnInvalidSequence = true)).jsonObject
private fun JsonObject.requiredText(key: String): String = requireNotNull(this[key]?.jsonPrimitive?.contentOrNull).also { require(it.isNotBlank()) }
private fun samePersonalWireValue(first: JsonElement?, second: JsonElement?): Boolean {
    if (first == second) return true
    if (first == null || first == JsonNull || second == null || second == JsonNull) return false
    val a = (first as? JsonPrimitive)?.content ?: return false
    val b = (second as? JsonPrimitive)?.content ?: return false
    return samePersonalAmount(a, b)
}
internal fun samePersonalAmount(first: String?, second: String?): Boolean = first == second ||
    (first != null && second != null && first.toDoubleOrNull()?.isFinite() == true && first.toDoubleOrNull() == second.toDoubleOrNull())
