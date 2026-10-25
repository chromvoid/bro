package assistant.mobile.core.activity

import assistant.mobile.core.GeneratedStrings
import assistant.mobile.core.LocalizedString
import assistant.mobile.core.NativeJson
import assistant.mobile.core.journal.Submission
import assistant.mobile.core.personal.PERSONAL_OPERATIONS
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import kotlin.time.Instant

const val ACTIVITY_OPERATIONS = "/v1/activity/operations"
const val ACTIVITY_LOOKUP = "$ACTIVITY_OPERATIONS/by-key/"

/** Editor values and admitted revisions stay unchanged across a conflict or recreation. */
@Serializable
data class ActivitySessionDraft(
    val sessionId: String? = null, val revision: Long = 0, val kind: String = "walking",
    val title: String = "", val date: String, val timezone: String, val time: String = "",
    val duration: String = "", val distance: String = "", val note: String = "",
)

@Serializable
data class ActivitySetDraft(
    val sessionId: String, val sessionExerciseId: String, val setId: String? = null,
    val expectedRevisions: Map<String, Long>, val loadBasis: String,
    val repetitions: String = "", val loadValue: String = "", val loadUnit: String = "kg",
    val implementCount: String = "1", val kind: String = "working", val note: String = "",
)

@Serializable
data class ActivityExerciseDraft(
    val exerciseId: String? = null, val revision: Long = 0, val name: String = "",
    val equipment: String = "", val variant: String = "", val loadBasis: String = "total",
)

@Serializable
data class ActivityExerciseNoteDraft(
    val sessionId: String, val sessionExerciseId: String, val expectedRevisions: Map<String, Long>, val note: String = "",
)

@Serializable
data class ActivityStepsDraft(val date: String, val value: String = "", val overrideId: String? = null, val revision: Long = 0)

data class ActivityOperation(val kind: String, val payload: JsonObject, val expectedRevisions: Map<String, Long> = emptyMap()) {
    fun bytes(): ByteArray = buildJsonObject {
        put("kind", kind); put("payload", payload)
        put("expected_revisions", JsonObject(expectedRevisions.mapValues { JsonPrimitive(it.value) }))
    }.toString().encodeToByteArray()
}

class ActivityValidationException(val messageKey: LocalizedString) : IllegalArgumentException()
private fun invalid(): Nothing = throw ActivityValidationException(GeneratedStrings.Activity.invalidSession)

/** Numeric grammar only: decimal comma is accepted, exponent and non-finite values are not. */
fun parseActivityAmount(input: String, maximum: Double = 100000.0): String? {
    val value = input.trim().replace(',', '.')
    if (value.isEmpty() || value.length > 24) return null
    val parts = value.split('.')
    if (parts.size !in 1..2 || parts.any { it.isEmpty() || it.any { c -> c !in '0'..'9' } } || (parts.getOrNull(1)?.length ?: 0) > 6) return null
    return value.takeIf { it.toDoubleOrNull()?.let { number -> number.isFinite() && number in 0.0..maximum } == true }
}

fun activitySessionDraft(session: ActivitySession): ActivitySessionDraft = ActivitySessionDraft(
    session.id, session.revision, session.kind, session.title.orEmpty(), session.date, session.timezone,
    session.startedAt?.let { Instant.parse(it).toLocalDateTime(TimeZone.of(session.timezone)).time.toString() }.orEmpty(),
    session.durationMinutes.orEmpty(), session.distanceKm.orEmpty(), session.note.orEmpty(),
)

fun sessionActivityOperation(draft: ActivitySessionDraft, today: String, nowMillis: Long, revision: Long = draft.revision): ActivityOperation {
    if (runCatching { LocalDate.parse(draft.date).toString() == draft.date }.getOrDefault(false).not() || draft.date > today) invalid()
    if (draft.kind !in setOf("walking", "running", "cycling", "strength", "swimming", "mobility", "other")) invalid()
    val zone = runCatching { TimeZone.of(draft.timezone) }.getOrElse { invalid() }
    val started = draft.time.takeIf { it.isNotEmpty() }?.let { time ->
        runCatching { LocalDateTime.parse("${draft.date}T$time").toInstant(zone) }.getOrElse { invalid() }
            .also { if (it.toEpochMilliseconds() > nowMillis) invalid() }
    }
    val duration = draft.duration.takeIf { it.isNotBlank() }?.let { parseActivityAmount(it, 10080.0) ?: invalid() }
    val distance = draft.distance.takeIf { it.isNotBlank() }?.let { parseActivityAmount(it) ?: invalid() }
    if (draft.title.trim().length > 200 || draft.note.trim().length > 4000) invalid()
    val id = draft.sessionId
    if (id != null) require(id.isNotBlank() && revision > 0)
    return ActivityOperation(if (id == null) "activity.session.add" else "activity.session.update", buildJsonObject {
        if (id != null) put("session_id", id)
        put("kind", draft.kind); put("title", draft.title.trim()); put("date", draft.date); put("timezone", draft.timezone)
        put("started_at", started?.toString()?.let(::JsonPrimitive) ?: JsonNull)
        put("duration_minutes", duration?.let(::JsonPrimitive) ?: JsonNull)
        put("distance_km", distance?.let(::JsonPrimitive) ?: JsonNull); put("note", draft.note.trim())
    }, if (id == null) emptyMap() else mapOf(id to revision))
}

fun deleteSessionActivityOperation(session: ActivitySession) = ActivityOperation("activity.session.delete",
    buildJsonObject { put("session_id", session.id) }, mapOf(session.id to session.revision))

fun sessionExerciseActivityOperation(session: ActivitySession, exerciseId: String): ActivityOperation {
    require(session.kind == "strength" && exerciseId.isNotBlank())
    return ActivityOperation("activity.session_exercise.add", buildJsonObject {
        put("session_id", session.id); put("exercise_id", exerciseId)
    }, mapOf(session.id to session.revision))
}

fun activityExpectedRevisions(session: ActivitySession, exercise: SessionExercise, set: ActivitySet? = null): Map<String, Long> =
    mapOf(session.id to session.revision, exercise.id to exercise.revision) + (set?.let { mapOf(it.id to it.revision) } ?: emptyMap())

fun activitySetDraft(session: ActivitySession, exercise: SessionExercise, set: ActivitySet? = null) = ActivitySetDraft(
    session.id, exercise.id, set?.id, activityExpectedRevisions(session, exercise, set), exercise.snapshot.loadBasis,
    set?.repetitions?.toString().orEmpty(), set?.loadValue.orEmpty(), set?.loadUnit ?: "kg",
    (set?.implementCount ?: 1).toString(), set?.kind ?: "working", set?.note.orEmpty(),
)

fun setActivityOperation(draft: ActivitySetDraft, revisions: Map<String, Long> = draft.expectedRevisions): ActivityOperation {
    require(draft.sessionId.isNotBlank() && draft.sessionExerciseId.isNotBlank())
    require(draft.loadBasis in setOf("total", "per_implement", "additional", "assistance", "bodyweight"))
    require(draft.kind in setOf("warmup", "working") && draft.loadUnit in setOf("kg", "lb") && draft.note.trim().length <= 2000)
    fun integer(text: String, maximum: Int): Int = text.trim().let { value ->
        if (value.isEmpty() || value.any { it !in '0'..'9' }) invalid()
        value.toIntOrNull()?.takeIf { it in 0..maximum } ?: invalid()
    }
    val reps = draft.repetitions.takeIf { it.isNotBlank() }?.let { integer(it, 10000) }
    val load = if (draft.loadBasis == "bodyweight") null else draft.loadValue.takeIf { it.isNotBlank() }?.let { parseActivityAmount(it) ?: invalid() }
    val count = if (draft.loadBasis == "per_implement") integer(draft.implementCount, 100).also { if (it < 1) invalid() } else 1
    val ids = listOfNotNull(draft.sessionId, draft.sessionExerciseId, draft.setId)
    require(ids.all { (revisions[it] ?: 0) > 0 } && revisions.keys == ids.toSet())
    return ActivityOperation(if (draft.setId == null) "activity.set.add" else "activity.set.update", buildJsonObject {
        put("session_id", draft.sessionId); put("session_exercise_id", draft.sessionExerciseId)
        draft.setId?.let { put("set_id", it) }
        put("repetitions", reps?.let(::JsonPrimitive) ?: JsonNull); put("load_value", load?.let(::JsonPrimitive) ?: JsonNull)
        put("load_unit", draft.loadUnit); put("implement_count", count); put("kind", draft.kind); put("note", draft.note.trim())
    }, revisions)
}

fun deleteSetActivityOperation(session: ActivitySession, exercise: SessionExercise, set: ActivitySet) = ActivityOperation("activity.set.delete",
    buildJsonObject { put("session_id", session.id); put("session_exercise_id", exercise.id); put("set_id", set.id) }, activityExpectedRevisions(session, exercise, set))

fun deleteSessionExerciseActivityOperation(session: ActivitySession, exercise: SessionExercise) = ActivityOperation("activity.session_exercise.delete",
    buildJsonObject { put("session_id", session.id); put("session_exercise_id", exercise.id) }, activityExpectedRevisions(session, exercise))

fun noteActivityOperation(draft: ActivityExerciseNoteDraft, revisions: Map<String, Long> = draft.expectedRevisions): ActivityOperation {
    require(draft.note.trim().length <= 2000 && revisions.keys == setOf(draft.sessionId, draft.sessionExerciseId) && revisions.values.all { it > 0 })
    return ActivityOperation("activity.session_exercise.update", buildJsonObject {
        put("session_id", draft.sessionId); put("session_exercise_id", draft.sessionExerciseId); put("note", draft.note.trim())
    }, revisions)
}

fun exerciseActivityOperation(draft: ActivityExerciseDraft, revision: Long = draft.revision): ActivityOperation {
    require(draft.name.trim().isNotEmpty() && listOf(draft.name, draft.equipment, draft.variant).all { it.trim().length <= 200 })
    require(draft.loadBasis in setOf("total", "per_implement", "additional", "assistance", "bodyweight"))
    if (draft.exerciseId != null) require(revision > 0)
    return ActivityOperation(if (draft.exerciseId == null) "activity.exercise.add" else "activity.exercise.update", buildJsonObject {
        draft.exerciseId?.let { put("exercise_id", it) }; put("name", draft.name.trim()); put("equipment", draft.equipment.trim())
        put("variant", draft.variant.trim()); put("load_basis", draft.loadBasis)
    }, draft.exerciseId?.let { mapOf(it to revision) } ?: emptyMap())
}

fun deleteCatalogExerciseActivityOperation(exercise: ExerciseDefinition) = ActivityOperation("activity.exercise.delete",
    buildJsonObject { put("exercise_id", exercise.id) }, mapOf(exercise.id to exercise.revision))

fun stepsActivityOperation(draft: ActivityStepsDraft, today: String, clear: Boolean = false,
    overrideId: String? = draft.overrideId, revision: Long = draft.revision): ActivityOperation {
    if (runCatching { LocalDate.parse(draft.date).toString() == draft.date }.getOrDefault(false).not() || draft.date > today)
        throw ActivityValidationException(GeneratedStrings.Activity.stepsRange)
    val value = if (clear) null else draft.value.trim().let { text ->
        if (text.isEmpty() || text.any { it !in '0'..'9' }) throw ActivityValidationException(GeneratedStrings.Activity.stepsRange)
        text.toIntOrNull()?.takeIf { it in 0..1000000 } ?: throw ActivityValidationException(GeneratedStrings.Activity.stepsRange)
    }
    if (overrideId != null) require(revision > 0)
    if (clear) require(overrideId != null)
    return ActivityOperation(if (clear) "personal.steps.clear" else "personal.steps.set", buildJsonObject {
        put("date", draft.date); value?.let { put("value", it) }
    }, overrideId?.let { mapOf(it to revision) } ?: emptyMap())
}

/** Personal step commands share the personal endpoint, and are consumed by Activity only. */
fun isActivityStepsSubmission(entry: Submission): Boolean = entry.endpoint == PERSONAL_OPERATIONS && runCatching {
    activityOperationObject(entry.body)["kind"]?.jsonPrimitive?.content in setOf("personal.steps.set", "personal.steps.clear")
}.getOrDefault(false)

fun validateActivityStepsSubmissionReceipt(entry: Submission, bytes: ByteArray): Boolean {
    if (!isActivityStepsSubmission(entry)) return false
    val command = activityOperationObject(entry.body)
    val payload = command.getValue("payload").jsonObject
    val expected = command.getValue("expected_revisions").jsonObject
    val receipt = decodeActivityReceipt(bytes)
    require(receipt.kind == command.requiredText("kind") && receipt.raw["date"] == payload["date"])
    val saved = receipt.raw.getValue("override").jsonObject
    val id = saved.requiredText("id")
    require(expected.isEmpty() || expected.keys == setOf(id))
    require((saved["revision"]?.jsonPrimitive?.longOrNull ?: 0) > (expected[id]?.jsonPrimitive?.longOrNull ?: 0))
    val active = receipt.kind == "personal.steps.set"
    require(saved["active"]?.jsonPrimitive?.booleanOrNull == active)
    require(if (active) saved["value"] == payload["value"] else saved["value"] == JsonNull)
    return true
}

data class ActivityReceipt(val operationId: String, val kind: String, val raw: JsonObject)
fun decodeActivityReceipt(bytes: ByteArray): ActivityReceipt = activityOperationObject(bytes).let {
    ActivityReceipt(it.requiredText("operation_id"), it.requiredText("kind"), it)
}

/** A receipt is authoritative only after its identity, revisions and proposed fields match. */
fun validateActivitySubmissionReceipt(entry: Submission, bytes: ByteArray) {
    if (entry.endpoint != ACTIVITY_OPERATIONS) return
    val command = activityOperationObject(entry.body)
    val kind = command.requiredText("kind")
    val payload = command.getValue("payload").jsonObject
    val expected = command.getValue("expected_revisions").jsonObject.mapValues { requireNotNull(it.value.jsonPrimitive.longOrNull) }
    val receipt = decodeActivityReceipt(bytes)
    require(receipt.kind == kind)
    val row = receipt.raw
    fun id(name: String): String = row.requiredText(name).also { value -> payload[name]?.let { require(it.jsonPrimitive.content == value) } }
    fun advanced(value: JsonObject) { val identity = value.requiredText("id"); require((value["revision"]?.jsonPrimitive?.longOrNull ?: 0) > (expected[identity] ?: 0)) }
    fun same(field: String, value: JsonObject) { if (field in payload) require(activityWireEquals(payload[field], value[field])) }
    if (kind.startsWith("activity.exercise.")) {
        val identity = id("exercise_id")
        if (kind.endsWith(".delete")) { require(row.requiredText("deleted_id") == identity); require(expected[identity]?.let { it > 0 } == true) }
        else {
            val exercise = row.getValue("exercise").jsonObject
            require(exercise.requiredText("id") == identity); advanced(exercise)
            listOf("name", "equipment", "variant", "load_basis").forEach { same(it, exercise) }
        }
        return
    }
    val sessionId = id("session_id")
    if (kind == "activity.session.delete") { require(row.requiredText("deleted_id") == sessionId); require(expected[sessionId]?.let { it > 0 } == true); return }
    val session = row.getValue("session").jsonObject
    require(session.requiredText("id") == sessionId); advanced(session)
    if (kind.startsWith("activity.session.")) {
        listOf("kind", "title", "date", "timezone", "duration_minutes", "distance_km", "note").forEach { same(it, session) }
        val proposed = payload["started_at"]
        val saved = session["started_at"]
        require(if (proposed == JsonNull) saved == JsonNull else proposed?.jsonPrimitive?.contentOrNull?.let { Instant.parse(it) } == saved?.jsonPrimitive?.contentOrNull?.let { Instant.parse(it) })
        return
    }
    val exerciseId = id("session_exercise_id")
    val exercises = session.getValue("exercises").jsonArray.map { it.jsonObject }
    if (kind == "activity.session_exercise.delete") { require(row.requiredText("deleted_id") == exerciseId && exercises.none { it.requiredText("id") == exerciseId }); return }
    val exercise = exercises.first { it.requiredText("id") == exerciseId }; advanced(exercise)
    if (kind.startsWith("activity.session_exercise.")) { listOf("exercise_id", "note", "position").forEach { same(it, exercise) }; return }
    require(kind in setOf("activity.set.add", "activity.set.update", "activity.set.delete"))
    val setId = id("set_id")
    val sets = exercise.getValue("sets").jsonArray.map { it.jsonObject }
    if (kind == "activity.set.delete") require(row.requiredText("deleted_id") == setId && sets.none { it.requiredText("id") == setId })
    else {
        val set = sets.first { it.requiredText("id") == setId }; advanced(set)
        listOf("position", "kind", "repetitions", "load_value", "load_unit", "implement_count", "note").forEach { same(it, set) }
    }
}

internal fun activityOperationObject(bytes: ByteArray): JsonObject = NativeJson.parseToJsonElement(bytes.decodeToString(throwOnInvalidSequence = true)).jsonObject
private fun JsonObject.requiredText(key: String) = requireNotNull(this[key]?.jsonPrimitive?.contentOrNull).also { require(it.isNotBlank()) }
private fun activityWireEquals(first: JsonElement?, second: JsonElement?): Boolean = first == second ||
    (first is JsonPrimitive && second is JsonPrimitive && first != JsonNull && second != JsonNull &&
        first.content.toDoubleOrNull()?.let { it.isFinite() && it == second.content.toDoubleOrNull() } == true)
