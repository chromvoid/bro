package assistant.mobile.core.challenges

import assistant.mobile.core.NativeJson
import assistant.mobile.core.journal.Submission
import io.ktor.http.encodeURLPathPart
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.serialization.json.*
import kotlin.time.Instant

const val CHALLENGES_PATH = "/v1/challenges"
fun challengePath(id: String) = "$CHALLENGES_PATH/${id.encodeURLPathPart()}"
fun decodeChallenges(bytes: ByteArray): List<Challenge> = challengeObject(bytes).array("challenges").map {
    decodeChallenge(requireNotNull(it as? JsonObject) { "Expected challenge object" })
}.also { require(it.map(Challenge::id).distinct().size == it.size) }
fun decodeChallengeBoard(bytes: ByteArray, expectedId: String): ChallengeBoard {
    val root = challengeObject(bytes)
    val challenge = decodeChallenge(root.objectField("challenge")).also { require(it.id == expectedId) }
    val participation = root.optionalText("participation")?.let { when (it) {
        "pending" -> Participation.Pending; "active" -> Participation.Active; "no_goal" -> Participation.NoGoal
        else -> throw IllegalArgumentException("Unsupported participation")
    } }
    val terms = root["terms"]?.takeUnless { it == JsonNull }?.let { value ->
        val it = requireNotNull(value as? JsonObject) { "Expected terms object" }
        ChallengeTerms(it.decimal("calorie_target").also { value -> require(value.toDouble() > 0) },
            it.text("timezone").also(TimeZone::of))
    }
    // Never retain a goal in a state in which the server says there is no fixed goal.
    require(terms == null || participation == Participation.Active)
    val ranking = root.array("ranking").map { row -> requireNotNull(row as? JsonObject) { "Expected ranking row" }.let {
        ChallengeRank(it.text("user_id"), it.text("name"), it.nonnegativeInt("days"),
            it.nonnegativeInt("streak"), it.nonnegativeInt("place").also { place -> require(place > 0) })
    } }.also { require(it.map(ChallengeRank::userId).distinct().size == it.size) }
    return ChallengeBoard(challenge, participation, terms, ranking)
}
private fun decodeChallenge(row: JsonObject): Challenge {
    val start = row.text("start_date").also { require(LocalDate.parse(it).toString() == it) }
    val end = row.text("end_date").also { require(LocalDate.parse(it).toString() == it && it >= start) }
    return Challenge(row.text("id"), row.text("name"), start, end, row.text("starts_at").also(Instant::parse),
        row.decimal("tolerance").also { require(it.toDouble() in 0.0..100.0) })
}
fun createChallengeBody(spaceId: String, draft: ChallengeDraft): ByteArray = buildJsonObject {
    require(spaceId.isNotBlank() && draft.name.trim().isNotEmpty() && draft.name.trim().length <= 100)
    put("space_id", spaceId); put("name", draft.name.trim()); put("start_date", draft.start); put("end_date", draft.end)
    put("tolerance", draft.tolerance.replace(',', '.').toDouble().also { require(it.isFinite() && it in 0.0..100.0) })
}.toString().encodeToByteArray()
fun isChallengeWriteEndpoint(endpoint: String): Boolean = endpoint == CHALLENGES_PATH || endpoint.split('/').let {
    it.size == 5 && it.take(3) == listOf("", "v1", "challenges") && it[3].isNotBlank() && it[4] in setOf("join", "leave")
}
/** These endpoints have no receipt lookup or revision contract; never invent either. */
fun validateChallengeSubmissionReceipt(entry: Submission, response: ByteArray) {
    require(isChallengeWriteEndpoint(entry.endpoint) && entry.method == "POST" && entry.lookup == null)
    val body = challengeObject(entry.body)
    val receipt = challengeObject(response)
    if (entry.endpoint == CHALLENGES_PATH) {
        require(body.keys == setOf("space_id", "name", "start_date", "end_date", "tolerance"))
        body.text("space_id"); body.text("name")
        require(LocalDate.parse(body.text("end_date")) >= LocalDate.parse(body.text("start_date")))
        require(body.decimal("tolerance").toDouble() in 0.0..100.0)
        receipt.text("id")
    } else {
        require(body.isEmpty())
        val joined = requireNotNull(receipt["joined"] as? JsonPrimitive) { "Expected joined boolean" }.let {
            require(!it.isString); requireNotNull(it.booleanOrNull) { "Expected joined boolean" }
        }
        require(joined == entry.endpoint.endsWith("/join"))
    }
}
internal fun challengeObject(bytes: ByteArray) = requireNotNull(
    NativeJson.parseToJsonElement(bytes.decodeToString(throwOnInvalidSequence = true)) as? JsonObject,
) { "Expected challenge response object" }
internal fun challengeCreatedId(bytes: ByteArray) = challengeObject(bytes).text("id")
private fun JsonObject.objectField(key: String) = requireNotNull(get(key) as? JsonObject) { "Expected object: $key" }
private fun JsonObject.array(key: String) = requireNotNull(get(key) as? JsonArray) { "Expected array: $key" }
private fun JsonObject.text(key: String) = requireNotNull((get(key) as? JsonPrimitive)?.takeIf { it.isString }?.content) {
    "Expected string: $key"
}.also { require(it.isNotBlank()) }
private fun JsonObject.optionalText(key: String) = get(key)?.takeUnless { it == JsonNull }?.let { value ->
    requireNotNull((value as? JsonPrimitive)?.takeIf { it.isString }?.content) { "Expected string: $key" }
}
private fun JsonObject.decimal(key: String) = requireNotNull((get(key) as? JsonPrimitive)?.takeIf { it != JsonNull && it.booleanOrNull == null }?.content) {
    "Expected decimal: $key"
}.also {
    require(it.toDoubleOrNull()?.isFinite() == true)
}
private fun JsonObject.nonnegativeInt(key: String) = requireNotNull((get(key) as? JsonPrimitive)?.takeUnless { it.isString }?.intOrNull) {
    "Expected integer: $key"
}.also { require(it >= 0) }
