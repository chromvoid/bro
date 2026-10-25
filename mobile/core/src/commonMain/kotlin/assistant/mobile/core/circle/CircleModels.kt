package assistant.mobile.core.circle

import assistant.mobile.core.AccountNamespace
import assistant.mobile.core.LocalizedString
import assistant.mobile.core.journal.Submission
import assistant.mobile.core.tasks.TaskList
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
enum class CircleRole(val wire: String) {
    @SerialName("owner") Owner("owner"), @SerialName("admin") Admin("admin"), @SerialName("member") Member("member");
}
@Serializable
enum class ChannelKind(val wire: String) {
    @SerialName("personal") Personal("personal"), @SerialName("space") Space("space"),
    @SerialName("direct") Direct("direct"), @SerialName("resource") Resource("resource");
}
@Serializable
enum class RelationshipState(val wire: String) {
    @SerialName("pending") Pending("pending"), @SerialName("accepted") Accepted("accepted"), @SerialName("blocked") Blocked("blocked");
}
enum class RelationshipAction(val wire: String) { Request("request"), Accept("accept"), Remove("remove"), Block("block"), Unblock("unblock"), Label("label") }
enum class ReportTarget(val wire: String) { Message("message"), User("user"), Resource("resource") }
@Serializable
data class CircleSpace(val id: String, val name: String, val revision: Long, val role: CircleRole, val archived: Boolean? = null)
@Serializable
data class CircleInvitation(@SerialName("space_id") val spaceId: String, val name: String,
    @SerialName("inviter_name") val inviterName: String? = null, @SerialName("expires_at") val expiresAt: String? = null)
@Serializable
data class CircleSpaces(val spaces: List<CircleSpace>, val invitations: List<CircleInvitation> = emptyList())
@Serializable
data class CircleMember(@SerialName("user_id") val userId: String, val role: CircleRole, val name: String, val login: String? = null)
@Serializable
data class CirclePerson(val id: String, val name: String, val login: String)
@Serializable
data class CircleRelationship(@SerialName("user_id") val userId: String, @SerialName("sender_id") val senderId: String,
    @SerialName("recipient_id") val recipientId: String, val state: RelationshipState, val name: String,
    val login: String? = null, val label: String? = null)
@Serializable
data class CircleChannel(val id: String, val kind: ChannelKind, val name: String,
    @SerialName("space_id") val spaceId: String? = null, val unread: Int = 0,
    @SerialName("audience_resource_id") val audienceResourceId: String? = null,
    @SerialName("subject_kind") val subjectKind: String? = null, @SerialName("subject_id") val subjectId: String? = null,
    @SerialName("peer_id") val peerId: String? = null, @SerialName("peer_name") val peerName: String? = null,
    @SerialName("peer_login") val peerLogin: String? = null)
@Serializable
data class CircleCall(@SerialName("call_id") val callId: String, val kind: String, val outcome: String,
    @SerialName("duration_s") val durationSeconds: Int?)
@Serializable
data class CircleMessage(val id: String, val author: String, @SerialName("created_at") val createdAt: String,
    @SerialName("actor_user_id") val actorUserId: String? = null, @SerialName("reply_to_id") val replyToId: String? = null,
    val name: String? = null, val text: String? = null, val call: CircleCall? = null)
@Serializable
data class CircleHistoryChannel(val id: String)
@Serializable
data class CircleHistory(val channel: CircleHistoryChannel? = null, val messages: List<CircleMessage>,
    @SerialName("next_cursor") val nextCursor: String? = null) {
    val channelId get() = channel?.id
    val latestMessageId get() = messages.lastOrNull()?.id
}
/** Channel drafts deliberately have no image, transcription or conversation identifiers. */
@Serializable
data class CircleDraft(val text: String = "", val replyToId: String? = null)
data class CircleAudience(val title: String, val kind: ChannelKind)
data class CircleAbilities(val invite: Boolean, val changeRoles: Boolean, val archive: Boolean, val manageResources: Boolean)
fun abilities(role: CircleRole) = CircleAbilities(role != CircleRole.Member, role == CircleRole.Owner, role == CircleRole.Owner, role != CircleRole.Member)
fun mayRemove(actor: CircleMember, target: CircleMember) = actor.userId == target.userId || actor.role == CircleRole.Owner ||
    (actor.role == CircleRole.Admin && target.role == CircleRole.Member)
fun soleOwner(members: List<CircleMember>, user: String) = members.filter { it.role == CircleRole.Owner }.let { it.size == 1 && it.single().userId == user }
fun audienceOf(channel: CircleChannel, spaceName: String?) = if (channel.kind == ChannelKind.Direct)
    CircleAudience(channel.peerName ?: channel.name, ChannelKind.Direct) else CircleAudience(spaceName ?: channel.name, ChannelKind.Space)
data class RelationshipGroups(val incoming: List<CircleRelationship>, val friends: List<CircleRelationship>,
    val outgoing: List<CircleRelationship>, val blocked: List<CircleRelationship>)
fun relationshipGroups(items: List<CircleRelationship>, me: String) = RelationshipGroups(
    items.filter { it.state == RelationshipState.Pending && it.recipientId == me }, items.filter { it.state == RelationshipState.Accepted },
    items.filter { it.state == RelationshipState.Pending && it.senderId == me }, items.filter { it.state == RelationshipState.Blocked })
data class CircleQuery<T>(val data: T? = null, val loading: Boolean = false, val error: LocalizedString? = null,
    val serverError: String? = null, val stale: Boolean = false)
data class CircleState(val namespace: AccountNamespace, val spaces: CircleQuery<CircleSpaces> = CircleQuery(),
    val channels: CircleQuery<List<CircleChannel>> = CircleQuery(), val relationships: CircleQuery<List<CircleRelationship>> = CircleQuery(),
    val members: CircleQuery<List<CircleMember>> = CircleQuery(), val lists: CircleQuery<List<TaskList>> = CircleQuery(),
    val history: CircleQuery<CircleHistory> = CircleQuery(), val spaceId: String? = null, val channelId: String? = null,
    val draft: CircleDraft = CircleDraft(), val pending: List<Submission> = emptyList(), val gone: Boolean = false,
    val busy: Boolean = false, val error: LocalizedString? = null, val serverError: String? = null,
    val person: CirclePerson? = null, val searchNotFound: Boolean = false, val searching: Boolean = false,
    val canWriteTasks: Boolean = false) {
    val space get() = spaces.data?.spaces?.firstOrNull { it.id == spaceId }
    val channel get() = channels.data?.firstOrNull { it.id == channelId }
    val spaceLists get() = lists.data.orEmpty().filter { it.spaceId == spaceId }
    val spaceChat get() = channels.data?.firstOrNull { it.spaceId == spaceId && it.kind == ChannelKind.Space }
    val audience get() = channel?.let { audienceOf(it, spaces.data?.spaces?.firstOrNull { space -> space.id == it.spaceId }?.name) }
    val currentMember get() = members.data?.firstOrNull { it.userId == namespace.userId }
    val groups get() = relationshipGroups(relationships.data.orEmpty(), namespace.userId)
    val activeSpaces get() = spaces.data?.spaces.orEmpty().filter { it.archived != true }
    val conversations get() = channels.data.orEmpty().filter { it.kind != ChannelKind.Personal }
    val attention get() = conversations.sumOf { it.unread } + spaces.data?.invitations.orEmpty().size
}
