package assistant.mobile.android

import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.util.UUID

/** Existing notification routes with synthetic inbox, revisioned settings and session schedules. */
internal class NativeNotificationsFixture {
    private val items = linkedMapOf<String, JSONObject>()
    private val receipts = linkedMapOf<String, Pair<String, Pair<Int, JSONObject>>>()
    private var settings = JSONObject().put("quiet_start", "23:00").put("quiet_end", "08:00")
        .put("muted_topics", JSONArray()).put("revision", 0).put("topics", JSONArray()
            .put(JSONObject().put("topic", "timers.fired").put("severity", "important"))
            .put(JSONObject().put("topic", "timers.reminder").put("severity", "important")))
    private var scheduleItems = JSONArray()
    private var snapshotId = UUID.randomUUID().toString()
    private val issuedSnapshots = linkedMapOf<String, Set<String>>()
    @Volatile var ackOnline = true
    @Volatile var scheduleStatus = 200

    init {
        items[TASK_NOTIFICATION_ID] = notification(TASK_NOTIFICATION_ID, "Synthetic task reminder",
            "/tasks/${NativeTasksFixture.TASK_ID}", topic = "timers.reminder")
        items[TIMER_NOTIFICATION_ID] = notification(TIMER_NOTIFICATION_ID, "Synthetic finished timer", "/timers")
        items[RESOLVED_NOTIFICATION_ID] = notification(RESOLVED_NOTIFICATION_ID, "Synthetic resolved reminder", null)
            .put("state", "resolved").put("unread", false).put("resolved_at", Instant.now().toString())
            .put("resolution", "Synthetic resolution")
    }

    fun accepts(path: String): Boolean = path.substringBefore('?').let {
        it == "/v1/notifications" || it.startsWith("/v1/notifications/")
    }

    @Synchronized fun unread(): Int = items.values.count { it.getBoolean("unread") }
    @Synchronized fun item(id: String): JSONObject = copy(items.getValue(id))
    @Synchronized fun settings(): JSONObject = copy(settings)

    /** The due instant stays stable across reads, exactly as the server's occurrence key does. */
    @Synchronized fun replaceSchedule(
        delaySeconds: Long = 8,
        link: String = "/tasks/${NativeTasksFixture.TASK_ID}",
        id: String = NativeTasksFixture.TASK_ID,
    ): String {
        val due = Instant.now().plusSeconds(delaySeconds).toString()
        val key = "reminder:$id:$due"
        snapshotId = UUID.randomUUID().toString()
        scheduleItems = JSONArray().put(JSONObject().put("key", key).put("revision", 1)
            .put("due_at", due).put("title", "Synthetic alarm").put("body", "Synthetic alarm body")
            .put("link", link))
        return key
    }

    @Synchronized fun emptySchedule() {
        snapshotId = UUID.randomUUID().toString()
        scheduleItems = JSONArray()
    }

    @Synchronized fun respond(request: NativeLoopbackServer.Request): Pair<Int, JSONObject> {
        check(request.headers["authorization"] == "Bearer ${NativeLoopbackServer.credential(request.userId())}")
        check(!request.headers.containsKey("cookie"))
        val path = request.path.substringBefore('?')
        if (request.method == "GET") return when (path) {
            "/v1/notifications" -> 200 to JSONObject().put("notifications", JSONArray(items.values.map(::copy)))
                .put("unread", unread()).put("before", JSONObject.NULL)
            "/v1/notifications/settings" -> 200 to settings()
            "/v1/notifications/schedule" -> {
                issuedSnapshots[snapshotId] = scheduleItems.objects().map { it.getString("key") }.toSet()
                scheduleStatus to JSONObject().put("snapshot_id", snapshotId)
                    .put("server_now", Instant.now().toString())
                    .put("expires_at", Instant.now().plusSeconds(1_800).toString())
                    .put("items", JSONArray(scheduleItems.toString()))
            }
            else -> error("Unknown synthetic notification read: $path")
        }
        check(request.headers["x-expected-user"] == request.userId())
        check(request.headers["content-type"]?.substringBefore(';') == "application/json")
        val key = request.headers["idempotency-key"]?.also(UUID::fromString)
        val fingerprint = "${request.method} $path ${request.body}"
        key?.let(receipts::get)?.let { (saved, receipt) ->
            check(saved == fingerprint) { "Notification operation key changed its request" }
            return receipt.first to copy(receipt.second)
        }
        val body = request.json()
        val response = when {
            request.method == "POST" && path == "/v1/notifications/read" -> {
                check(body.keys().asSequence().all { it in setOf("ids", "all") })
                val ids = if (body.optBoolean("all")) items.keys.toList() else body.getJSONArray("ids").strings()
                var changed = 0
                ids.forEach { id -> items[id]?.let { item ->
                    if (item.isNull("read_at") || !item.isNull("snoozed_until")) changed++
                    item.put("read_at", Instant.now().toString()).put("snoozed_until", JSONObject.NULL).put("unread", false)
                } }
                200 to JSONObject().put("read", changed)
            }
            request.method == "POST" && path.endsWith("/snooze") -> {
                check(body.keys().asSequence().all { it in setOf("minutes", "until") })
                check(body.has("minutes") != body.has("until"))
                val until = if (body.has("minutes")) Instant.now().plusSeconds(body.getLong("minutes") * 60)
                    else Instant.parse(body.getString("until"))
                check(until.isAfter(Instant.now()))
                val item = items.getValue(path.removeSuffix("/snooze").substringAfterLast('/'))
                check(item.getString("state") == "active")
                item.put("snoozed_until", until.toString()).put("read_at", Instant.now().toString()).put("unread", false)
                200 to JSONObject().put("snoozed_until", until.toString())
            }
            request.method == "PUT" && path == "/v1/notifications/settings" -> {
                check(body.keys().asSequence().toSet() == setOf("quiet_start", "quiet_end", "muted_topics", "revision"))
                if (body.getInt("revision") != settings.getInt("revision")) 409 to JSONObject().put("error", "conflict")
                else {
                    settings = copy(body).put("revision", body.getInt("revision") + 1)
                        .put("topics", settings.getJSONArray("topics"))
                    200 to settings()
                }
            }
            request.method == "POST" && path == "/v1/notifications/test" -> {
                check(body.length() == 0)
                items[TEST_NOTIFICATION_ID] = notification(TEST_NOTIFICATION_ID, "Synthetic notification test", "/notifications")
                    .put("source", "app").put("topic", "app.test")
                200 to JSONObject().put("id", TEST_NOTIFICATION_ID).put("written", true)
            }
            request.method == "POST" && path == "/v1/notifications/schedule/ack" -> {
                check(body.keys().asSequence().toSet() == setOf("snapshot_id", "installed"))
                val installed = body.getJSONArray("installed").strings()
                val known = issuedSnapshots.getValue(body.getString("snapshot_id"))
                check(installed.size == installed.toSet().size && known.containsAll(installed))
                if (ackOnline) 200 to JSONObject().put("installed", JSONArray(installed)) else 503 to JSONObject()
            }
            else -> error("Unknown synthetic notification write: ${request.method} $path")
        }
        if (key != null && response.first < 500) receipts[key] = fingerprint to (response.first to copy(response.second))
        return response
    }

    private fun notification(id: String, title: String, link: String?, topic: String = "timers.fired") = JSONObject()
        .put("id", id).put("source", "timers").put("topic", topic).put("severity", "important")
        .put("title", title).put("body", "Synthetic notification body").put("link", link ?: JSONObject.NULL)
        .put("state", "active").put("resolution", JSONObject.NULL).put("count", 1)
        .put("updated_at", Instant.now().toString()).put("read_at", JSONObject.NULL)
        .put("resolved_at", JSONObject.NULL).put("snoozed_until", JSONObject.NULL).put("unread", true)

    private fun copy(value: JSONObject) = JSONObject(value.toString())
    private fun JSONArray.strings(): List<String> = (0 until length()).map(::getString)
    private fun JSONArray.objects(): List<JSONObject> = (0 until length()).map(::getJSONObject)

    companion object {
        const val TASK_NOTIFICATION_ID = "91000000-0000-4000-8000-000000000001"
        const val TIMER_NOTIFICATION_ID = "91000000-0000-4000-8000-000000000002"
        const val RESOLVED_NOTIFICATION_ID = "91000000-0000-4000-8000-000000000003"
        const val TEST_NOTIFICATION_ID = "91000000-0000-4000-8000-000000000004"
    }
}
