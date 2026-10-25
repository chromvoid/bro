package assistant.mobile.core.circle

import assistant.mobile.core.HttpOutcome
import assistant.mobile.core.NativeJson
import assistant.mobile.core.decodeSubmissionReceipt
import assistant.mobile.core.journal.Submission
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import kotlin.time.Instant

@Serializable private data class MembersReply(val members: List<CircleMember>)
@Serializable private data class RelationshipsReply(val relationships: List<CircleRelationship>)
@Serializable private data class ChannelsReply(val channels: List<CircleChannel>)
@Serializable private data class PersonReply(val user: CirclePerson?)
fun decodeCircleSpaces(bytes: ByteArray) = NativeJson.decodeFromString<CircleSpaces>(bytes.decodeToString(throwOnInvalidSequence = true)).also {
    circleObject(bytes).getValue("spaces").jsonArray.forEach { entry ->
        strictNumber(entry.jsonObject.getValue("revision"))
        entry.jsonObject["archived"]?.takeUnless { value -> value == JsonNull }?.let { value ->
            require(value is JsonPrimitive && !value.isString && value.booleanOrNull != null)
        }
    }
    it.spaces.forEach { space -> require(space.id.isNotBlank() && space.name.isNotBlank() && space.revision >= 0) }
    it.invitations.forEach { invite -> require(invite.spaceId.isNotBlank() && invite.name.isNotBlank()); invite.expiresAt?.let(Instant::parse) }
    require(it.spaces.map { s -> s.id }.distinct().size == it.spaces.size)
}
fun decodeCircleMembers(bytes: ByteArray) = NativeJson.decodeFromString<MembersReply>(bytes.decodeToString(throwOnInvalidSequence = true)).members.also {
    it.forEach { member -> require(member.userId.isNotBlank() && member.name.isNotBlank()) }
    require(it.map { m -> m.userId }.distinct().size == it.size)
}
fun decodeCircleRelationships(bytes: ByteArray) = NativeJson.decodeFromString<RelationshipsReply>(bytes.decodeToString(throwOnInvalidSequence = true)).relationships.also {
    it.forEach { relation -> require(relation.userId.isNotBlank() && relation.name.isNotBlank() && relation.senderId.isNotBlank() && relation.recipientId.isNotBlank() && relation.senderId != relation.recipientId) }
}
fun decodeCirclePerson(bytes: ByteArray) = NativeJson.decodeFromString<PersonReply>(bytes.decodeToString(throwOnInvalidSequence = true)).user.also {
    it?.let { person -> require(person.id.isNotBlank() && person.login.isNotBlank() && person.name.isNotBlank()) }
}
fun decodeCircleChannels(bytes: ByteArray) = NativeJson.decodeFromString<ChannelsReply>(bytes.decodeToString(throwOnInvalidSequence = true)).channels.also {
    circleObject(bytes).getValue("channels").jsonArray.forEach { entry -> entry.jsonObject["unread"]?.let(::strictNumber) }
    it.forEach { channel -> require(channel.id.isNotBlank() && channel.name.isNotBlank() && channel.unread >= 0) }
    require(it.map { channel -> channel.id }.distinct().size == it.size)
}
fun decodeCircleHistory(bytes: ByteArray, expectedChannel: String): CircleHistory = NativeJson.decodeFromString<CircleHistory>(bytes.decodeToString(throwOnInvalidSequence = true)).also {
    circleObject(bytes).getValue("messages").jsonArray.forEach { entry ->
        entry.jsonObject["call"]?.takeUnless { value -> value == JsonNull }?.jsonObject?.get("duration_s")?.takeUnless { value -> value == JsonNull }?.let(::strictNumber)
    }
    it.channel?.let { channel -> require(channel.id == expectedChannel) }
    it.messages.forEach { message ->
        require(message.id.isNotBlank() && message.author.isNotBlank()); Instant.parse(message.createdAt)
        message.call?.let { call -> require(call.outcome in setOf("completed", "lost", "declined", "missed", "cancelled") && (call.durationSeconds == null || call.durationSeconds >= 0)) }
    }
    require(it.messages.map { message -> message.id }.distinct().size == it.messages.size)
}
fun circleServerError(outcome: HttpOutcome): String? {
    val bytes = when (outcome) { is HttpOutcome.Failure -> outcome.body; is HttpOutcome.Conflict -> outcome.body; else -> return null }
    return runCatching { NativeJson.parseToJsonElement(bytes.decodeToString()).jsonObject["message"]?.jsonPrimitive?.contentOrNull }.getOrNull()
}
internal fun decodeCircleId(bytes: ByteArray): String = NativeJson.parseToJsonElement(bytes.decodeToString()).jsonObject.getValue("id").jsonPrimitive.let {
    require(it.isString); it.content.also { value -> require(value.isNotBlank()) }
}
internal fun circleGone(outcome: HttpOutcome) = outcome is HttpOutcome.Failure && outcome.status == 404

/** The server has two explicit success contracts: sent human text and assistant admission. */
fun isCircleMessageEndpoint(endpoint: String): Boolean = endpoint.split('/').let {
    it.size == 5 && it.take(3) == listOf("", "v1", "channels") && it[3].isNotBlank() && it[4] == "messages"
}
fun validateCircleSubmissionReceipt(entry: Submission, response: ByteArray) {
    require(isCircleMessageEndpoint(entry.endpoint))
    val body = circleObject(entry.body)
    require(body.keys.all { it in setOf("text", "assistant", "reply_to_id") })
    require("text" in body)
    require(body.getValue("text").jsonPrimitive.let { it.isString && it.content.isNotBlank() && it.content.length <= 4000 })
    body["reply_to_id"]?.let { require(it is JsonPrimitive && it.isString && it.content.isNotBlank()) }
    val assistant = body["assistant"]?.let { value ->
        require(value is JsonPrimitive && !value.isString); requireNotNull(value.booleanOrNull)
    } ?: false
    if (assistant) {
        val receipt = decodeSubmissionReceipt(response)
        require(receipt.submissionKey == "channel:${entry.key}" && receipt.supportedState)
    } else {
        val receipt = circleObject(response)
        require(receipt.keys.containsAll(setOf("message_id", "state")))
        require(receipt.getValue("message_id").jsonPrimitive.let { it.isString && it.content.isNotBlank() })
        require(receipt.getValue("state").jsonPrimitive.let { it.isString && it.content == "sent" })
    }
}
private fun circleObject(bytes: ByteArray) = NativeJson.parseToJsonElement(bytes.decodeToString(throwOnInvalidSequence = true)).jsonObject
private fun strictNumber(value: JsonElement) { require(value is JsonPrimitive && !value.isString && value.longOrNull != null) }
