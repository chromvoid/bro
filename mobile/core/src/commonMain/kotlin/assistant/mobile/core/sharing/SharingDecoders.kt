package assistant.mobile.core.sharing

import assistant.mobile.core.NativeJson
import assistant.mobile.core.food.decodeFoodDay
import assistant.mobile.core.journal.Submission
import assistant.mobile.core.personal.PERSONAL_BODY_KINDS
import kotlinx.datetime.LocalDate
import kotlinx.serialization.json.*

fun decodeSharingOverview(bytes: ByteArray): SharingOverview {
    val result = NativeJson.decodeFromString<SharingOverview>(bytes.decodeToString(throwOnInvalidSequence = true))
    sharingObject(bytes).getValue("sections").jsonArray.forEach { sharingRevision(it.jsonObject.getValue("revision")) }
    result.sections.forEach { require(it.id.isNotBlank() && it.ownerId.isNotBlank() && it.name in setOf("personal", "nutrition") && it.revision >= 0) }
    require(result.sections.map { it.id }.distinct().size == result.sections.size)
    result.grants.forEach { require(it.resourceId.isNotBlank() && it.userId.isNotBlank() && it.name.isNotBlank()) }
    result.shared.orEmpty().forEach { require(it.id.isNotBlank() && it.ownerId.isNotBlank() && it.ownerName.isNotBlank() && it.name in setOf("personal", "nutrition") && it.permission in setOf("summary", "detail", "manage")) }
    return result
}

fun decodeListGrants(bytes: ByteArray, expectedId: String): ListGrants = NativeJson.decodeFromString<ListGrants>(bytes.decodeToString(throwOnInvalidSequence = true)).also {
    val resource = it.resource
    sharingRevision(sharingObject(bytes).getValue("resource").jsonObject.getValue("revision"))
    require(resource.id == expectedId && resource.name.isNotBlank() && resource.memberAccess in setOf("view", "edit") && resource.revision >= 0)
    it.grants.forEach { grant -> require(grant.userId.isNotBlank() && grant.name.isNotBlank()) }
    require(it.grants.map { grant -> grant.userId }.distinct().size == it.grants.size)
}

/** Permission chooses the projection before decoding any optional detail fields. */
fun decodeSharedProjection(bytes: ByteArray, expectedSection: String): SharedProjection {
    val root = sharingObject(bytes)
    val section = root.sharingText("section").also { require(it == expectedSection) }
    val permission = root.sharingText("permission").also { require(it in setOf("summary", "detail", "manage")) }
    val data = root.getValue("data").jsonObject
    val date = data.sharingOptionalText("date")?.also { LocalDate.parse(it) }
    if (section == "nutrition") {
        return if (permission == "summary") SharedProjection(section, permission, date,
            summary = SharedNutritionSummary(data.sharingDecimal("value"), data.sharingDecimal("target"), data.sharingDecimal("remaining")))
        else SharedProjection(section, permission, date, diary = decodeFoodDay(data.toString().encodeToByteArray()))
    }
    require(section == "personal")
    val metrics = data["metrics"]?.jsonObject.orEmpty()
    val kinds = listOf("weight", "sleep", "steps", "active_minutes", "active_energy", "distance") + PERSONAL_BODY_KINDS
    val visible = kinds.mapNotNull { kind -> metrics[kind]?.jsonObject?.let { metric ->
        SharedMetric(kind, metric.sharingText("status"), metric.sharingDecimal("value"))
    } }
    val profile = if (permission != "summary") data["profile"]?.takeUnless { it == JsonNull }?.jsonObject?.let {
        SharedProfile(it.sharingDecimal("height_cm"), it.sharingOptionalText("birth_date")?.also { date -> LocalDate.parse(date) })
    } else null
    return SharedProjection(section, permission, date, metrics = visible, profile = profile)
}

fun decodeSharingRevision(bytes: ByteArray): Long = sharingRevision(sharingObject(bytes).getValue("revision"))
fun isSharingResourceEndpoint(endpoint: String): Boolean = endpoint.split('/').let {
    it.size in 4..5 && it.take(3) == listOf("", "v1", "resources") && it[3].isNotBlank() && (it.size == 4 || it[4] == "grants")
}
fun validateSharingSubmissionReceipt(entry: Submission, response: ByteArray) {
    require(isSharingResourceEndpoint(entry.endpoint))
    val body = sharingObject(entry.body)
    val revision = sharingRevision(body.getValue("revision"))
    require(revision < Long.MAX_VALUE && decodeSharingRevision(response) == revision + 1)
}
internal fun sharingObject(bytes: ByteArray) = NativeJson.parseToJsonElement(bytes.decodeToString(throwOnInvalidSequence = true)).jsonObject
private fun sharingRevision(value: JsonElement): Long {
    require(value is JsonPrimitive && !value.isString)
    return requireNotNull(value.longOrNull).also { require(it >= 0) }
}
private fun JsonObject.sharingText(key: String) = getValue(key).let { require(it is JsonPrimitive && it.isString); it.content }
private fun JsonObject.sharingOptionalText(key: String) = get(key)?.takeUnless { it == JsonNull }?.let { require(it is JsonPrimitive && it.isString); it.content }
private fun JsonObject.sharingDecimal(key: String): String? = get(key)?.takeUnless { it == JsonNull }?.let {
    require(it is JsonPrimitive)
    it.content.also { value -> require(value.toDoubleOrNull()?.isFinite() == true) }
}
