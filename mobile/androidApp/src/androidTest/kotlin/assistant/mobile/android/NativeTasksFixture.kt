package assistant.mobile.android

import android.graphics.BitmapFactory
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

/** Synthetic tasks use the web schemas and the server's operation envelopes and image transport. */
internal class NativeTasksFixture {
    val today: String = LocalDate.now(ZoneId.of("Europe/Moscow")).toString()
    private val tasks = linkedMapOf<String, JSONObject>()
    private val receipts = linkedMapOf<String, JSONObject>()
    private val bodies = linkedMapOf<String, String>()
    private val pictures = linkedMapOf<String, ByteArray>()
    private val history = mutableListOf<JSONObject>()
    private val messages = mutableListOf<JSONObject>()
    private var delegation: JSONObject? = null
    private var question: JSONObject? = null
    @Volatile var conflictNext = false
    @Volatile var lookupOnline = true
    @Volatile var disconnectAdmission = false

    init {
        tasks[TASK_ID] = seed(TASK_ID, "Synthetic task", PERSONAL_ID)
            .put("description", "Synthetic task description").put("planned_on", today)
            .put("due_on", today).put("revision", 7)
            .put("checklist", JSONArray().put(JSONObject().put("id", STEP_ID)
                .put("text", "Synthetic checklist step").put("done", false)))
        tasks[CHILD_ID] = seed(CHILD_ID, "Synthetic subtask", PERSONAL_ID)
            .put("parent_id", TASK_ID).put("revision", 3)
            .put("parent", JSONObject().put("id", TASK_ID).put("title", "Synthetic task")
                .put("state", "todo").put("deleted_at", JSONObject.NULL))
        tasks[BOARD_TASK_ID] = seed(BOARD_TASK_ID, "Synthetic project task", PROJECT_ID).put("revision", 4)
        history += JSONObject().put("id", HISTORY_ID).put("kind", "created")
            .put("created_at", "2026-10-16T12:00:00Z").put("actor", actor())
            .put("changes", JSONObject()).put("reason", JSONObject.NULL).put("task_revision", 1)
        messages += message(MESSAGE_ID, "Synthetic task discussion")
    }

    @Synchronized fun seedStoreTask() {
        tasks.getValue(TASK_ID).put("title", "Прогуляться после обеда")
            .put("description", "Выделить 20 минут для прогулки без спешки.")
    }

    @Synchronized fun task(id: String): JSONObject = copy(requireNotNull(tasks[id]))
    @Synchronized fun writeCount(): Int = receipts.size
    @Synchronized fun imageCount(): Int = pictures.size
    @Synchronized fun discussionCount(): Int = messages.size

    fun accepts(path: String): Boolean = path.substringBefore('?').let {
        it.startsWith("/v1/tasks/") || it.startsWith("/v1/channels/$CHANNEL_ID/") ||
            it == "/v1/spaces" || it == "/v1/spaces/$SPACE_ID/members"
    }

    /** A status of zero loses the socket response after durable admission. */
    @Synchronized fun respond(request: NativeLoopbackServer.Request): Reply {
        check(request.headers["authorization"] == "Bearer ${NativeLoopbackServer.credential(request.userId())}")
        check(!request.headers.containsKey("cookie"))
        if (request.method == "POST") check(request.headers["x-expected-user"] == request.userId())
        val path = request.path.substringBefore('?')
        val query = Uri.parse(request.path)
        return when {
            request.method == "GET" && path == "/v1/spaces" -> json(JSONObject().put("spaces", JSONArray()
                .put(JSONObject().put("id", SPACE_ID).put("name", "Synthetic family").put("revision", 1).put("role", "owner"))))
            request.method == "GET" && path == "/v1/spaces/$SPACE_ID/members" -> json(JSONObject().put("members", JSONArray()
                .put(JSONObject().put("user_id", "synthetic-user-a").put("name", "Person").put("role", "owner"))))
            request.method == "GET" && path == "/v1/tasks/lists" -> json(lists())
            request.method == "GET" && path == "/v1/tasks/items" -> json(page(query))
            request.method == "GET" && path == "/v1/tasks/board" -> json(board(query))
            request.method == "POST" && path == "/v1/tasks/operations" -> operate(request)
            request.method == "GET" && path.startsWith("/v1/tasks/operations/by-key/") -> {
                val receipt = receipts[path.substringAfterLast('/')]
                when {
                    !lookupOnline -> json(JSONObject(), 503)
                    receipt == null -> json(JSONObject(), 404)
                    else -> json(copy(receipt))
                }
            }
            request.method == "POST" && path == "/v1/tasks/images" -> upload(request)
            request.method == "GET" && path == "/v1/tasks/assistant/capabilities" -> {
                val task = requireNotNull(tasks[query.getQueryParameter("task_id")])
                json(capabilities(task.getString("list_id")))
            }
            request.method == "GET" && path == "/v1/tasks/assistant/runs/$RUN_ID" -> json(run())
            request.method == "GET" && path.startsWith("/v1/tasks/items/") -> {
                val segments = path.removePrefix("/v1/tasks/items/").split('/')
                val task = tasks[segments[0]] ?: return json(JSONObject(), 404)
                when {
                    segments.size == 1 -> json(copy(task))
                    segments[1] == "history" -> json(JSONObject().put("items", JSONArray(history.reversed()))
                        .put("people", JSONArray().put(actor())).put("next_cursor", JSONObject.NULL))
                    segments[1] == "discussion" -> json(JSONObject().put("task_id", task.getString("id"))
                        .put("channel_id", CHANNEL_ID))
                    segments[1] == "assistant" -> json(snapshot())
                    segments[1] == "images" -> {
                        val bytes = pictures[segments[2]] ?: return json(JSONObject(), 404)
                        Reply(200, bytes, "image/jpeg", mapOf("Cache-Control" to "private, max-age=31536000, immutable"))
                    }
                    else -> error("Unknown synthetic task route: $path")
                }
            }
            request.method == "GET" && path == "/v1/channels/$CHANNEL_ID/messages" -> json(JSONObject()
                .put("channel", JSONObject().put("id", CHANNEL_ID)).put("messages", JSONArray(messages))
                .put("next_cursor", JSONObject.NULL))
            request.method == "POST" && path == "/v1/channels/$CHANNEL_ID/messages" -> {
                UUID.fromString(requireNotNull(request.headers["idempotency-key"]))
                val message = message(UUID.randomUUID().toString(), request.json().getString("text"))
                messages += message
                json(JSONObject().put("message_id", message.getString("id")).put("state", "sent"), 201)
            }
            request.method == "POST" && path == "/v1/channels/$CHANNEL_ID/read" -> {
                check(messages.any { it.getString("id") == request.json().getString("through_message_id") })
                json(JSONObject().put("read", true))
            }
            else -> error("Unknown synthetic task route: ${request.method} $path")
        }
    }

    private fun page(query: Uri): JSONObject {
        val list = query.getQueryParameter("list_id")
        val parent = query.getQueryParameter("parent_id")
        val view = query.getQueryParameter("view") ?: "open"
        val scope = query.getQueryParameter("scope") ?: "personal"
        val state = query.getQueryParameter("state")
        val search = query.getQueryParameter("q").orEmpty()
        val selected = tasks.values.filter { task ->
            val deleted = !task.isNull("deleted_at")
            val done = task.getString("status") == "done"
            val due = task.optString("due_on", "").takeUnless { it == "null" }
            val plan = task.optString("planned_on", "").takeUnless { it == "null" }
            val dateMatches = when (view) {
                "today" -> !done && listOfNotNull(due, plan).any { it.isNotBlank() && it <= today }
                "upcoming" -> !done && listOfNotNull(due, plan).any { it > today }
                "done" -> done
                "open" -> !done
                else -> true
            }
            val parentMatches = when (parent) {
                "root", "board" -> task.isNull("parent_id")
                null -> true
                else -> task.optString("parent_id") == parent
            }
            (if (view == "trash") deleted else !deleted) && dateMatches && parentMatches &&
                (list == null || task.getString("list_id") == list) &&
                (scope != "personal" || task.getString("list_id") == PERSONAL_ID) &&
                (scope != "assigned" || task.optString("assigned_to") == "synthetic-user-a") &&
                (state == null || task.getString("state") == state) &&
                (search.isBlank() || (task.getString("title") + task.optString("description")).contains(search, true)) &&
                (query.getQueryParameter("important") != "1" || task.getInt("priority") == 1) &&
                (query.getQueryParameter("overdue") != "1" || (!done && due != null && due.isNotBlank() && due < today)) &&
                (query.getQueryParameter("assignee") != "unassigned" || task.isNull("assigned_to")) &&
                (query.getQueryParameter("assignee") != "me" || task.optString("assigned_to") == "synthetic-user-a")
        }.sortedWith(when (query.getQueryParameter("sort")) {
            "priority" -> compareBy<JSONObject> { it.getInt("priority") }.thenBy { it.getDouble("position") }
            "due" -> compareBy<JSONObject> { if (it.isNull("due_on")) "9999" else it.getString("due_on") }
                .thenBy { it.getDouble("position") }
            else -> compareBy { it.getDouble("position") }
        })
        return JSONObject().put("snapshot", "synthetic-snapshot-${receipts.size}")
            .put("items", JSONArray(selected.take(query.getQueryParameter("limit")?.toInt() ?: 50).map(::copy)))
            .put("total", selected.size).put("next_cursor", JSONObject.NULL).put("today", today)
            .put("timezone", "Europe/Moscow")
    }

    private fun board(query: Uri): JSONObject {
        check(query.getQueryParameter("list_id") == PROJECT_ID)
        val columns = JSONObject()
        STATES.forEach { state ->
            val columnUri = query.buildUpon().path("/v1/tasks/items").appendQueryParameter("scope", "all")
                .appendQueryParameter("view", if (state == "done") "done" else "open")
                .appendQueryParameter("parent_id", "board").appendQueryParameter("state", state).build()
            val page = page(columnUri)
            page.remove("today"); page.remove("timezone")
            columns.put(state, page)
        }
        return JSONObject().put("snapshot", "synthetic-snapshot-${receipts.size}").put("columns", columns)
            .put("total", STATES.sumOf { columns.getJSONObject(it).getInt("total") })
            .put("today", today).put("timezone", "Europe/Moscow")
    }

    private fun lists(): JSONObject = JSONObject().put("lists", JSONArray().put(
        list(PERSONAL_ID, "Личные задачи", "list").put("personal_user_id", "synthetic-user-a"),
    ).put(list(PROJECT_ID, "Synthetic project", "project"))
        .put(list(SHARED_ID, "Synthetic shared list", "list").put("space_id", SPACE_ID)))

    private fun list(id: String, name: String, kind: String): JSONObject = JSONObject().put("id", id)
        .put("name", name).put("space_id", JSONObject.NULL).put("member_access", "edit")
        .put("revision", 1).put("effective", "manage").put("list_kind", kind).put("outcome", JSONObject.NULL)
        .put("project_state", "active").put("personal_user_id", JSONObject.NULL)
        .put("counts", JSONObject().also { counts -> STATES.forEach { state ->
            counts.put(state, tasks.values.count { it.getString("list_id") == id && it.getString("state") == state && it.isNull("parent_id") })
        } })

    private fun operate(request: NativeLoopbackServer.Request): Reply {
        val key = requireNotNull(request.headers["idempotency-key"])
        UUID.fromString(key)
        receipts[key]?.let {
            check(bodies.getValue(key) == request.body) { "A tasks operation key changed its body" }
            return json(copy(it))
        }
        val body = request.json()
        val kind = body.getString("kind")
        val payload = body.getJSONObject("payload")
        val expected = body.optJSONObject("expected_revisions") ?: JSONObject()
        val receipt = JSONObject().put("operation_id", UUID.randomUUID().toString()).put("kind", kind)
        when {
            kind == "delegation.create" -> {
                val task = requireNotNull(tasks[payload.getString("task_id")])
                check(expected.getInt(task.getString("id")) == task.getInt("revision"))
                check(payload.getString("instruction").isNotBlank())
                val grants = payload.optJSONObject("grants") ?: grants(task.getString("list_id")).put("tools",
                    JSONArray(listOf("tasks.get", "tasks.list", "research.investigate", "tasks.add_subtask")))
                check(grants.getJSONArray("resources").toStrings().contains(task.getString("list_id")))
                check(grants.getJSONArray("tools").toStrings().all { it in listOf("tasks.get", "tasks.list", "research.investigate", "tasks.add_subtask") })
                check(grants.getJSONArray("actions").toStrings().all { it in listOf("result.add", "note.add", "task.create_subtask") })
                delegation = copy(payload).put("id", DELEGATION_ID).put("task_id", task.getString("id"))
                    .put("grants", copy(grants))
                    .put("user_id", "synthetic-user-a").put("status", "waiting_input").put("revision", 1)
                question = JSONObject().put("id", QUESTION_ID).put("task_id", task.getString("id"))
                    .put("text", "Synthetic task agent question").put("status", "pending").put("revision", 2)
                    .put("audience_private", false).put("kind", "question")
                receipt.put("task_id", task.getString("id")).put("delegation", copy(requireNotNull(delegation)))
            }
            kind == "question.answer" -> {
                check(payload.getString("question_id") == QUESTION_ID)
                val current = requireNotNull(question)
                check(expected.getInt(QUESTION_ID) == current.getInt("revision"))
                current.put("answer", payload.getString("answer")).put("status", "answered").put("revision", 3)
                requireNotNull(delegation).put("status", "active")
                receipt.put("task_id", requireNotNull(delegation).getString("task_id")).put("question", copy(current))
            }
            kind == "task.create" -> {
                val parentId = payload.optString("parent_id").takeIf { it.isNotBlank() && it != "null" }
                val parent = parentId?.let { requireNotNull(tasks[it]) }
                if (parent != null) check(expected.getInt(parentId) == parent.getInt("revision"))
                val id = UUID.randomUUID().toString()
                val task = seed(id, payload.getString("title"), body.optString("target_resource_id", PERSONAL_ID))
                payload.keys().forEach { field -> task.put(field, payload.get(field)) }
                tasks[id] = task
                receipt.put("tasks", JSONArray().put(copy(task)))
            }
            kind.startsWith("task.") -> {
                val task = requireNotNull(tasks[payload.getString("task_id")])
                val id = task.getString("id")
                if (conflictNext) {
                    conflictNext = false
                    task.put("title", "Synthetic concurrent task title").put("revision", task.getInt("revision") + 1)
                    return json(JSONObject().put("error", "conflict").put("message", "Задача уже изменена. Обновите её перед правкой"), 409)
                }
                if (expected.optInt(id, -1) != task.getInt("revision")) {
                    return json(JSONObject().put("error", "conflict").put("message", "Задача уже изменена. Обновите её перед правкой"), 409)
                }
                when (kind) {
                    "task.update" -> {
                        val changes = payload.getJSONObject("changes")
                        changes.keys().forEach { field ->
                            if (field in listOf("title_images", "description_images")) {
                                task.put(field, JSONArray(changes.getJSONArray(field).toStrings().map { imageId ->
                                    requireNotNull(pictures[imageId]); image(imageId)
                                }))
                            } else task.put(field, changes.get(field))
                        }
                    }
                    "task.complete" -> task.put("state", "done").put("status", "done").put("completed_at", Instant.now().toString())
                    "task.reopen" -> task.put("state", "todo").put("status", "open").put("completed_at", JSONObject.NULL)
                    "task.delete" -> task.put("deleted_at", Instant.now().toString())
                    "task.restore" -> task.put("deleted_at", JSONObject.NULL)
                    "task.set_state" -> task.put("state", payload.getString("state"))
                        .put("status", if (payload.getString("state") == "done") "done" else "open")
                    "task.move" -> task.put("list_id", payload.getString("list_id"))
                    else -> error("Unexpected synthetic task operation: $kind")
                }
                task.put("revision", task.getInt("revision") + 1)
                receipt.put("tasks", JSONArray().put(copy(task)))
                history += JSONObject().put("id", UUID.randomUUID().toString()).put("kind", "updated")
                    .put("created_at", Instant.now().toString()).put("actor", actor())
                    .put("changes", JSONObject()).put("reason", JSONObject.NULL).put("task_revision", task.getInt("revision"))
            }
            else -> error("Unexpected synthetic tasks operation: $kind")
        }
        bodies[key] = request.body
        receipts[key] = copy(receipt)
        if (disconnectAdmission) {
            disconnectAdmission = false
            return Reply(0, byteArrayOf())
        }
        return json(receipt, 201)
    }

    private fun upload(request: NativeLoopbackServer.Request): Reply {
        check(request.headers["content-type"]?.substringBefore(';') == "image/jpeg")
        val bitmap = requireNotNull(BitmapFactory.decodeByteArray(request.bytes, 0, request.bytes.size))
        check(bitmap.width <= 1600 && bitmap.height <= 1600)
        bitmap.recycle()
        val id = UUID.randomUUID().toString()
        pictures[id] = request.bytes
        return json(image(id), 201)
    }

    private fun image(id: String): JSONObject {
        val bytes = requireNotNull(pictures[id])
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
        return JSONObject().put("id", id).put("width", options.outWidth).put("height", options.outHeight)
    }

    private fun snapshot(): JSONObject = JSONObject().put("delegation", delegation?.let(::copy) ?: JSONObject.NULL)
        .put("can_manage", true).put("questions", JSONArray(listOfNotNull(question?.let(::copy))))
        .put("results", JSONArray()).put("runs", JSONArray(if (delegation == null) emptyList<JSONObject>() else listOf(run())))

    private fun run(): JSONObject = JSONObject().put("id", RUN_ID).put("task_id", requireNotNull(delegation).getString("task_id"))
        .put("delegation_id", DELEGATION_ID).put("status", "waiting_input").put("reason", JSONObject.NULL)
        .put("summary", "Synthetic task agent run").put("created_at", "2026-10-16T12:00:00Z")
        .put("finished_at", JSONObject.NULL).put("tool_calls", 1).put("deadline", "2026-10-16T12:05:00Z")

    private fun capabilities(listId: String): JSONObject = JSONObject().put("enabled", true).put("default_grants", grants(listId))
        .put("tools", choices("tasks.get", "tasks.list", "tasks.add_subtask"))
        .put("actions", choices("result.add", "note.add", "task.create_subtask"))
        .put("resources", JSONArray().put(JSONObject().put("id", PERSONAL_ID).put("title", "Личные задачи"))
            .put(JSONObject().put("id", PROJECT_ID).put("title", "Synthetic project")))
        .put("executors", JSONArray())

    private fun grants(listId: String): JSONObject = JSONObject().put("tools", JSONArray(listOf("tasks.get", "tasks.list", "tasks.add_subtask")))
        .put("actions", JSONArray(listOf("result.add", "note.add", "task.create_subtask")))
        .put("resources", JSONArray(listOf(listId))).put("executors", JSONArray())

    private fun choices(vararg ids: String): JSONArray = JSONArray(ids.map { JSONObject().put("id", it).put("title", it) })
    private fun message(id: String, text: String): JSONObject = JSONObject().put("id", id).put("author", "user")
        .put("actor_user_id", "synthetic-user-a").put("name", "Person").put("text", text)
        .put("created_at", "2026-10-16T12:00:00Z")
    private fun actor(): JSONObject = JSONObject().put("id", "synthetic-user-a").put("name", "Person")
    private fun copy(value: JSONObject): JSONObject = JSONObject(value.toString())
    private fun JSONArray.toStrings(): List<String> = (0 until length()).map(::getString)
    private fun json(value: JSONObject, status: Int = 200): Reply = Reply(status, value.toString().toByteArray(), "application/json")
    private fun seed(id: String, title: String, list: String): JSONObject = JSONObject().put("id", id)
        .put("list_id", list).put("title", title).put("description", JSONObject.NULL).put("status", "open")
        .put("state", "todo").put("waiting_reason", JSONObject.NULL).put("priority", 2).put("position", tasks.size + 1)
        .put("parent_id", JSONObject.NULL).put("parent", JSONObject.NULL).put("assigned_to", JSONObject.NULL)
        .put("checklist", JSONArray()).put("title_images", JSONArray()).put("description_images", JSONArray())
        .put("discussion_channel_id", CHANNEL_ID).put("discussion", JSONObject().put("total", 1).put("unread", 0))
        .put("planned_on", JSONObject.NULL).put("due_on", JSONObject.NULL).put("completed_at", JSONObject.NULL)
        .put("deleted_at", JSONObject.NULL).put("updated_at", "2026-10-16T12:00:00Z").put("revision", 1)
        .put("overdue", false).put("subtasks", JSONObject().put("total", if (id == TASK_ID) 1 else 0).put("done", 0))

    data class Reply(val status: Int, val bytes: ByteArray, val mediaType: String = "application/json",
        val headers: Map<String, String> = emptyMap())

    companion object {
        val STATES = listOf("todo", "in_progress", "waiting", "done")
        const val TASK_ID = "11111111-1111-4111-8111-111111111111"
        const val CHILD_ID = "22222222-2222-4222-8222-222222222222"
        const val STEP_ID = "33333333-3333-4333-8333-333333333333"
        const val PROJECT_ID = "44444444-4444-4444-8444-444444444444"
        const val BOARD_TASK_ID = "55555555-5555-4555-8555-555555555555"
        const val PERSONAL_ID = "66666666-6666-4666-8666-666666666666"
        const val SHARED_ID = "77777777-7777-4777-8777-777777777777"
        const val SPACE_ID = "88888888-8888-4888-8888-888888888888"
        const val CHANNEL_ID = "99999999-9999-4999-8999-999999999999"
        const val MESSAGE_ID = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"
        const val HISTORY_ID = "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"
        const val DELEGATION_ID = "cccccccc-cccc-4ccc-8ccc-cccccccccccc"
        const val QUESTION_ID = "dddddddd-dddd-4ddd-8ddd-dddddddddddd"
        const val RUN_ID = "eeeeeeee-eeee-4eee-8eee-eeeeeeeeeeee"
    }
}
