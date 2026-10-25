package assistant.mobile.android

import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.util.UUID

/** Two synthetic people and the same wire shapes as community/routes.py and channels.py. */
internal class NativeCircleFixture {
    data class Reply(val status: Int, val body: JSONObject)
    private data class Space(
        val id: String,
        val channel: String,
        val name: String,
        val members: MutableMap<String, String>,
        var revision: Int = 1,
        var archived: Boolean = false,
    )
    private data class Relationship(var sender: String, var recipient: String, var state: String, var label: String? = null)

    private val spaces = linkedMapOf(
        SPACE_ID to Space(SPACE_ID, SPACE_CHANNEL_ID, SPACE_NAME, linkedMapOf(USER_A to "owner", USER_B to "member")),
        INVITED_SPACE_ID to Space(INVITED_SPACE_ID, INVITED_CHANNEL_ID, INVITED_SPACE_NAME, linkedMapOf(USER_B to "owner")),
    )
    private val invitations = linkedMapOf(INVITED_SPACE_ID to USER_A)
    private val history = linkedMapOf<String, MutableList<JSONObject>>()
    private val receipts = linkedMapOf<String, Pair<String, JSONObject>>()
    private val reportReceipts = linkedMapOf<String, Pair<String, JSONObject>>()
    private val reads = linkedMapOf<Pair<String, String>, String>()
    private var relationship: Relationship? = null
    private var directCreated = false
    private var sequence = 10
    @Volatile var disconnectNextMessage = false
    @Volatile var historyOnline = true

    init {
        history[SPACE_CHANNEL_ID] = mutableListOf(message(SPACE_MESSAGE_ID, USER_B, "Synthetic shared history"))
        history[INVITED_CHANNEL_ID] = mutableListOf(message(INVITED_MESSAGE_ID, USER_B, "Synthetic invited history"))
        history[DIRECT_CHANNEL_ID] = mutableListOf(message(DIRECT_MESSAGE_ID, USER_B, "Synthetic direct greeting"))
    }

    fun accepts(path: String): Boolean = path.substringBefore('?').let {
        it == "/v1/spaces" || it.startsWith("/v1/spaces/") || it == "/v1/relationships" ||
            it == "/v1/channels" || it.startsWith("/v1/channels/") ||
            it == "/v1/community/reports" || it == "/v1/tasks/lists"
    }

    @Synchronized fun respond(request: NativeLoopbackServer.Request): Reply {
        val user = request.userId()
        check(user in setOf(USER_A, USER_B))
        check(request.headers["authorization"] == "Bearer ${NativeLoopbackServer.credential(user)}")
        check(!request.headers.containsKey("cookie"))
        if (request.method == "POST") {
            check(request.headers["x-expected-user"] == user)
            if (request.path.endsWith("/messages") || request.path == "/v1/community/reports")
                UUID.fromString(requireNotNull(request.headers["idempotency-key"]))
        }
        val path = request.path.substringBefore('?')
        val query = Uri.parse(request.path)
        return when {
            request.method == "GET" && path == "/v1/spaces" -> ok(spaces(user))
            request.method == "POST" && path == "/v1/spaces" -> {
                val body = request.json()
                fields(body, "name")
                val id = nextId()
                val channel = nextId()
                spaces[id] = Space(id, channel, body.getString("name"), linkedMapOf(user to "owner"))
                history[channel] = mutableListOf()
                ok(JSONObject().put("id", id).put("channel_id", channel).put("task_list_id", nextId()), 201)
            }
            path.startsWith("/v1/spaces/") -> spaceRequest(request, user, path)
            request.method == "GET" && path == "/v1/relationships" -> {
                val login = query.getQueryParameter("login")
                if (login != null) ok(JSONObject().put("user", when (login) {
                    "person" -> person(USER_A)
                    "other" -> person(USER_B)
                    "guest" -> JSONObject().put("id", "synthetic-invitee").put("name", "Synthetic Guest").put("login", "guest")
                    else -> JSONObject.NULL
                })) else ok(JSONObject().put("relationships", JSONArray().also { array ->
                    relationship?.takeUnless { it.state == "blocked" && it.sender != user }?.let { array.put(relation(it, user)) }
                }))
            }
            request.method == "POST" && path == "/v1/relationships" -> relationshipRequest(request, user)
            request.method == "GET" && path == "/v1/channels" -> ok(channels(user))
            request.method == "POST" && path == "/v1/channels/direct" -> {
                fields(request.json(), "user_id")
                check(request.json().getString("user_id") == peer(user))
                if (relationship?.state != "accepted") ok(JSONObject(), 403)
                else {
                    directCreated = true
                    ok(JSONObject().put("id", DIRECT_CHANNEL_ID))
                }
            }
            path.startsWith("/v1/channels/") -> channelRequest(request, user, path)
            request.method == "GET" && path == "/v1/tasks/lists" -> ok(JSONObject().put("lists", JSONArray()
                .put(JSONObject().put("id", LIST_ID).put("name", "Synthetic shared tasks").put("space_id", SPACE_ID)
                    .put("member_access", "edit").put("revision", 1).put("effective", "manage").put("list_kind", "list"))))
            request.method == "POST" && path == "/v1/community/reports" -> {
                val body = request.json()
                fields(body, "target_type", "target_id", "reason")
                check(body.getString("target_type") in setOf("message", "user", "resource"))
                check(body.getString("reason").isNotBlank())
                val key = requireNotNull(request.headers["idempotency-key"])
                val previous = reportReceipts[key]
                if (previous != null) {
                    check(previous.first == body.toString())
                    ok(copy(previous.second))
                } else {
                    val receipt = JSONObject().put("report", JSONObject().put("id", nextId()).put("status", "open")
                        .put("created_at", Instant.now().toString()))
                    reportReceipts[key] = body.toString() to receipt
                    ok(copy(receipt))
                }
            }
            else -> error("Unknown synthetic circle route: ${request.method} $path")
        }
    }

    @Synchronized fun acceptFriendRequest() {
        val current = requireNotNull(relationship)
        check(current.state == "pending" && current.recipient == USER_B)
        current.state = "accepted"
    }

    @Synchronized fun incomingFriendRequest() { relationship = Relationship(USER_B, USER_A, "pending") }
    @Synchronized fun establishFriendship() { relationship = Relationship(USER_B, USER_A, "accepted"); directCreated = true }
    @Synchronized fun relationshipState(): String? = relationship?.state
    @Synchronized fun role(space: String, user: String): String? = spaces.getValue(space).members[user]
    @Synchronized fun isArchived(space: String): Boolean = spaces.getValue(space).archived
    @Synchronized fun readThrough(channel: String, user: String = USER_A): String? = reads[channel to user]
    @Synchronized fun sentCount(): Int = receipts.size
    @Synchronized fun latestMessageId(channel: String): String? = history[channel]?.lastOrNull()?.getString("id")

    @Synchronized fun postPeerMessage(channel: String, text: String): String = nextId().also { id ->
        history.getValue(channel) += message(id, USER_B, text)
    }

    @Synchronized fun recordCall(callId: String, outcome: String): String = nextId().also { id ->
        history.getValue(DIRECT_CHANNEL_ID) += message(id, USER_A, "").put("call",
            JSONObject().put("call_id", callId).put("kind", "audio").put("outcome", outcome)
                .put("duration_s", if (outcome in setOf("completed", "lost")) 2 else JSONObject.NULL))
    }

    @Synchronized fun revokeSpaceMembership(space: String) { spaces.getValue(space).members.remove(USER_A) }
    @Synchronized fun blockFromPeer() { relationship = Relationship(USER_B, USER_A, "blocked") }

    private fun spaceRequest(request: NativeLoopbackServer.Request, user: String, path: String): Reply {
        val segments = path.removePrefix("/v1/spaces/").split('/')
        val space = spaces[segments[0]] ?: return ok(JSONObject(), 404)
        val action = segments.getOrNull(1) ?: return ok(JSONObject(), 404)
        if (space.archived) return ok(JSONObject(), 404)
        if (request.method == "POST" && action == "join") {
            fields(request.json())
            if (invitations[space.id] != user) return ok(JSONObject(), 404)
            invitations.remove(space.id)
            space.members[user] = "member"
            return ok(JSONObject().put("joined", true))
        }
        val role = space.members[user] ?: return ok(JSONObject(), 404)
        return when {
            request.method == "GET" && action == "members" -> ok(JSONObject().put("members", JSONArray(
                space.members.map { (id, memberRole) -> person(id).put("user_id", id).put("role", memberRole) })))
            request.method == "POST" && action == "invitations" -> {
                fields(request.json(), "user_id")
                if (role !in setOf("owner", "admin")) return ok(JSONObject(), 403)
                val target = request.json().getString("user_id")
                if (target in space.members) return ok(JSONObject(), 409)
                invitations[space.id] = target
                ok(JSONObject().put("invited", true).put("includes_all_history", true))
            }
            request.method == "POST" && action == "members" -> {
                val body = request.json()
                fields(body, "user_id", "role")
                val target = body.getString("user_id")
                val old = space.members[target] ?: return ok(JSONObject(), 404)
                val next = if (body.isNull("role")) null else body.getString("role")
                check(next == null || next in setOf("owner", "admin", "member"))
                if (!(target == user && next == null) && role != "owner" &&
                    !(role == "admin" && old == "member" && next in setOf("member", null))) return ok(JSONObject(), 403)
                if (old == "owner" && next != "owner" && space.members.values.count { it == "owner" } == 1)
                    return ok(JSONObject(), 409)
                if (next == null) space.members.remove(target) else space.members[target] = next
                ok(JSONObject().put("updated", true))
            }
            request.method == "POST" && action == "archive" -> {
                fields(request.json(), "revision")
                if (role != "owner") return ok(JSONObject(), 403)
                if (request.json().getInt("revision") != space.revision) return ok(JSONObject(), 409)
                space.archived = true
                space.revision++
                ok(JSONObject().put("archived", true))
            }
            else -> error("Unknown synthetic circle space route: ${request.method} $path")
        }
    }

    private fun relationshipRequest(request: NativeLoopbackServer.Request, user: String): Reply {
        val body = request.json()
        check(body.keys().asSequence().toSet().all { it in setOf("user_id", "action", "label") })
        check(body.getString("user_id") == peer(user))
        when (body.getString("action")) {
            "request" -> if (relationship == null) relationship = Relationship(user, peer(user), "pending")
            "accept" -> {
                val old = relationship
                if (old?.state != "pending" || old.recipient != user) return ok(JSONObject(), 409)
                old.state = "accepted"
            }
            "remove" -> relationship = null
            "block" -> relationship = Relationship(user, peer(user), "blocked")
            "unblock" -> if (relationship?.sender == user) relationship = null
            "label" -> relationship?.label = body.getString("label")
            else -> error("Unknown synthetic circle relationship action")
        }
        return ok(JSONObject().put("updated", true))
    }

    private fun channelRequest(request: NativeLoopbackServer.Request, user: String, path: String): Reply {
        val parts = path.removePrefix("/v1/channels/").split('/')
        val channel = parts[0]
        if (!visibleChannel(user, channel)) return ok(JSONObject(), 404)
        return when {
            request.method == "GET" && parts.getOrNull(1) == "messages" -> {
                if (!historyOnline) return ok(JSONObject(), 503)
                val before = Uri.parse(request.path).getQueryParameter("before")
                val all = history.getValue(channel)
                val end = if (before == null) all.size else all.indexOfFirst { it.getString("id") == before }.coerceAtLeast(0)
                val start = (end - 60).coerceAtLeast(0)
                ok(JSONObject().put("channel", channel(channel, user)).put("messages", JSONArray(all.subList(start, end).map(::copy)))
                    .put("next_cursor", if (start > 0) all[start].getString("id") else JSONObject.NULL))
            }
            request.method == "POST" && parts.getOrNull(1) == "messages" -> {
                val body = request.json()
                check(body.keys().asSequence().toSet().all { it in setOf("text", "assistant", "reply_to_id") })
                check(body.getString("text").isNotBlank())
                check(!body.has("assistant") || body.get("assistant") is Boolean)
                val reply = body.optString("reply_to_id").takeIf { it.isNotBlank() && it != "null" }
                check(reply == null || history.getValue(channel).any { it.getString("id") == reply })
                val key = requireNotNull(request.headers["idempotency-key"])
                val fingerprint = "$user:$channel:$body"
                val previous = receipts[key]
                if (previous != null) {
                    check(previous.first == fingerprint)
                    return ok(copy(previous.second), 201)
                }
                val id = nextId()
                history.getValue(channel) += message(id, user, body.getString("text"), reply = reply)
                val invoke = body.optBoolean("assistant", false)
                if (invoke) history.getValue(channel) += message(nextId(), user, ASSISTANT_ANSWER, author = "assistant", reply = id)
                val receipt = JSONObject().put("message_id", id).put("state", if (invoke) "completed" else "sent")
                    .put("queued", false)
                if (invoke) receipt.put("submission_key", "channel:$key").put("admitted_at", Instant.now().toString())
                    .put("trace_id", JSONObject.NULL)
                receipts[key] = fingerprint to receipt
                if (disconnectNextMessage) {
                    disconnectNextMessage = false
                    Reply(0, JSONObject())
                } else ok(copy(receipt), 201)
            }
            request.method == "POST" && parts.getOrNull(1) == "read" -> {
                fields(request.json(), "through_message_id")
                val id = request.json().getString("through_message_id")
                val all = history.getValue(channel)
                val next = all.indexOfFirst { it.getString("id") == id }
                if (next < 0) return ok(JSONObject(), 404)
                val previous = all.indexOfFirst { it.getString("id") == reads[channel to user] }
                if (next > previous) reads[channel to user] = id
                ok(JSONObject().put("read", true))
            }
            else -> error("Unknown synthetic circle channel route: ${request.method} $path")
        }
    }

    private fun spaces(user: String) = JSONObject().put("spaces", JSONArray(spaces.values.filter { user in it.members }.map {
        JSONObject().put("id", it.id).put("name", it.name).put("revision", it.revision)
            .put("archived", it.archived).put("role", it.members.getValue(user))
    })).put("invitations", JSONArray(spaces.values.filter { invitations[it.id] == user && !it.archived }.map {
        JSONObject().put("space_id", it.id).put("name", it.name).put("inviter_name", "Other Person")
            .put("expires_at", "2099-01-01T00:00:00Z").put("includes_all_history", true)
    }))

    private fun channels(user: String) = JSONObject().put("channels", JSONArray(
        history.keys.filter { visibleChannel(user, it) }.map { channel(it, user) }))

    private fun visibleChannel(user: String, id: String): Boolean = if (id == DIRECT_CHANNEL_ID)
        directCreated && relationship?.state == "accepted" else spaces.values.any { it.channel == id && !it.archived && user in it.members }

    private fun channel(id: String, user: String): JSONObject {
        val space = spaces.values.firstOrNull { it.channel == id }
        val all = history.getValue(id)
        val seen = all.indexOfFirst { it.getString("id") == reads[id to user] }
        return JSONObject().put("id", id).put("kind", if (space == null) "direct" else "space")
            .put("name", if (space == null) "Личная переписка" else "Общий чат")
            .put("space_id", space?.id ?: JSONObject.NULL)
            .put("unread", all.drop(seen + 1).count { it.optString("actor_user_id") != user })
            .put("peer_id", if (space == null) peer(user) else JSONObject.NULL)
            .put("peer_name", if (space == null) person(peer(user)).getString("name") else JSONObject.NULL)
            .put("peer_login", if (space == null) person(peer(user)).getString("login") else JSONObject.NULL)
    }

    private fun relation(value: Relationship, user: String) = person(peer(user)).put("user_id", peer(user))
        .put("sender_id", value.sender).put("recipient_id", value.recipient).put("state", value.state)
        .put("label", value.label ?: JSONObject.NULL)

    private fun person(id: String) = JSONObject().put("id", id).put("name", if (id == USER_A) "Person" else "Other Person")
        .put("login", if (id == USER_A) "person" else "other")

    private fun message(id: String, actor: String, text: String, author: String = "user", reply: String? = null) = JSONObject()
        .put("id", id).put("actor_user_id", actor).put("author", author).put("reply_to_id", reply ?: JSONObject.NULL)
        .put("created_at", Instant.now().plusMillis(sequence.toLong()).toString())
        .put("name", person(actor).getString("name")).put("text", text)

    private fun nextId(): String = "16161616-1616-4616-8616-${(++sequence).toString().padStart(12, '0')}"
    private fun peer(user: String) = if (user == USER_A) USER_B else USER_A
    private fun fields(body: JSONObject, vararg names: String) = check(body.keys().asSequence().toSet() == names.toSet())
    private fun copy(body: JSONObject) = JSONObject(body.toString())
    private fun ok(body: JSONObject, status: Int = 200) = Reply(status, body)

    companion object {
        const val USER_A = "synthetic-user-a"
        const val USER_B = "synthetic-user-b"
        const val SPACE_ID = "16161616-1616-4616-8616-000000000001"
        const val SPACE_CHANNEL_ID = "16161616-1616-4616-8616-000000000002"
        const val LIST_ID = "16161616-1616-4616-8616-000000000003"
        const val DIRECT_CHANNEL_ID = "16161616-1616-4616-8616-000000000004"
        const val DIRECT_MESSAGE_ID = "16161616-1616-4616-8616-000000000005"
        const val SPACE_MESSAGE_ID = "16161616-1616-4616-8616-000000000006"
        const val INVITED_SPACE_ID = "16161616-1616-4616-8616-000000000007"
        const val INVITED_CHANNEL_ID = "16161616-1616-4616-8616-000000000008"
        const val INVITED_MESSAGE_ID = "16161616-1616-4616-8616-000000000009"
        const val SPACE_NAME = "Synthetic family"
        const val INVITED_SPACE_NAME = "Synthetic invitation"
        const val ASSISTANT_ANSWER = "Synthetic answer visible to this channel"
    }
}
