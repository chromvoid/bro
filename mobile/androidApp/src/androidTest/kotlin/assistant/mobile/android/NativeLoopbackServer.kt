package assistant.mobile.android

import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.Closeable
import java.io.EOFException
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/** An in-process HTTP fixture: every byte stays on emulator loopback, including the SSE stream. */
internal class NativeLoopbackServer : Closeable {
    private val listener = ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"))
    private val workers = Executors.newCachedThreadPool { action ->
        Thread(action, "native-smoke-http").apply { isDaemon = true }
    }
    private val sockets = CopyOnWriteArrayList<Socket>()
    val requests = CopyOnWriteArrayList<Request>()
    val responses = CopyOnWriteArrayList<String>()
    val failures = CopyOnWriteArrayList<String>()
    val activeStreams = AtomicInteger()
    private val speechPreferences = JSONObject().put("revision", 0).put("asr", "auto")
        .put("fallback_asr", JSONObject.NULL).put("tts", "auto").put("voice", "alloy")
        .put("tts_model", JSONObject.NULL).put("asr_model", JSONObject.NULL).put("fallback_asr_model", JSONObject.NULL)
    private val assistantMessages = ConcurrentHashMap<String, JSONObject>()
    private val profiles = ConcurrentHashMap<String, Profile>()
    /** Dashboard fixtures are opt-in so unrelated smoke cases keep their original scope boundary. */
    @Volatile var todayEnabled = false
    /** The richer diary fixture is opt-in; existing dashboard cases retain their exact data. */
    @Volatile var nutritionEnabled = false
    val nutrition = NativeNutritionFixture()
    /** Full tasks are isolated from the small dashboard fixtures used by earlier stages. */
    @Volatile var tasksEnabled = false
    val tasks = NativeTasksFixture()
    @Volatile var devicesEnabled = false
    val devices = NativeDevicesFixture()
    /** Timers are isolated so earlier Today fixtures keep their original server response. */
    @Volatile var timersEnabled = false
    val timers = NativeTimersFixture()
    /** Notification inbox/settings/alarms are opt-in; older Today cases retain their fixture. */
    @Volatile var notificationsEnabled = false
    val notifications = NativeNotificationsFixture()
    @Volatile var personalEnabled = false
    val personal = NativePersonalFixture()
    @Volatile var bodyEnabled = false
    val body = NativeBodyAssetFixture(personal)
    val personalHistory = NativePersonalHistoryFixture(personal)
    @Volatile var tradingEnabled = false
    val trading = NativeTradingFixture()
    @Volatile var circleEnabled = false
    val circle = NativeCircleFixture()
    /** Activity owns its Personal movement reads so its snapshot cannot alter earlier fixtures. */
    @Volatile var placesEnabled = false
    val places = NativePlacesFixture()
    @Volatile var activityEnabled = false
    val activity = NativeActivityFixture()
    /** UUID ownership and a bearer-only audio archive are isolated from earlier fixtures. */
    @Volatile var ambientEnabled = false
    val ambient = NativeAmbientFixture()
    @Volatile var refreshExpiresAt: Long? = null
    @Volatile var deletionCompleted = false
    private val updateBytes = ByteArray(128 * 1024) { (it % 251).toByte() }
    @Volatile private var deletionAdmitted = false
    @Volatile private var running = true
    val origin = "http://127.0.0.1:${listener.localPort}"

    init {
        workers.execute {
            while (running) {
                try {
                    val socket = listener.accept()
                    sockets += socket
                    workers.execute { handle(socket) }
                } catch (_: SocketException) {
                    if (running) throw IllegalStateException("Loopback listener stopped unexpectedly")
                }
            }
        }
    }

    private fun handle(socket: Socket) {
        var route = "unparsed request"
        var streaming = false
        try {
            socket.use {
                socket.soTimeout = 10_000
                val input = BufferedInputStream(socket.getInputStream())
                val start = input.line().split(' ')
                require(start.size == 3)
                val headers = linkedMapOf<String, String>()
                while (true) {
                    val line = input.line()
                    if (line.isEmpty()) break
                    headers[line.substringBefore(':').lowercase()] = line.substringAfter(':').trim()
                }
                val size = headers["content-length"]?.toInt() ?: 0
                require(size in 0..10 * 1024 * 1024)
                val bytes = ByteArray(size)
                var offset = 0
                while (offset < size) {
                    val count = input.read(bytes, offset, size - offset)
                    if (count < 0) throw EOFException()
                    offset += count
                }
                val request = Request(start[0], start[1], headers, bytes.toString(Charsets.UTF_8), bytes)
                route = "${request.method} ${request.path.substringBefore('?')}"
                requests += request
                val output = socket.getOutputStream()
                fun reply(status: Int, body: String, replyHeaders: Map<String, String> = emptyMap()) {
                    responses += "$route $status"
                    respond(output, status, body, replyHeaders)
                }
                when {
                    ambientEnabled && ambient.accepts(request.path) -> {
                        val response = ambient.respond(request)
                        if (response.status == 0) {
                            responses += "$route disconnected-after-admission"
                            return
                        }
                        responses += "$route ${response.status}"
                        respondBytes(output, response.status, response.bytes, response.mediaType)
                    }
                    bodyEnabled && body.accepts(request) -> {
                        val response = body.respond(request, personalHistory)
                        responses += "$route ${response.status}"
                        respondBytes(output, response.status, response.bytes, response.type)
                    }
                    personalEnabled && request.method == "GET" && request.path == "/v1/personal/body/presentation" -> {
                        checkNutritionTransport(request)
                        reply(200, body.presentation().toString())
                    }
                    personalEnabled && request.method == "GET" && request.path == "/body-assets.json" -> {
                        // Older Personal cases exercise measurements with asset delivery offline.
                        // The renderer fixture opts in to exact GLBs above.
                        reply(503, "{}")
                    }
                    request.method == "GET" && request.path == "/v1/calls/current" -> {
                        reply(200, JSONObject().put("call", JSONObject.NULL).toString())
                    }
                    tradingEnabled && request.method == "GET" && trading.isEvents(request.path) -> {
                        streaming = true
                        responses += "$route 200"
                        trading.events(output, request)
                    }
                    tradingEnabled && trading.accepts(request.path) -> {
                        val response = trading.respond(request)
                        if (response.status == 0) {
                            responses += "$route disconnected-after-admission"
                            return
                        }
                        reply(response.status, response.body)
                    }
                    circleEnabled && circle.accepts(request.path) -> {
                        val result = circle.respond(request)
                        if (result.status != 0) reply(result.status, result.body.toString())
                    }
                    request.path.substringBefore('?') == "/v1/devices" && request.method == "GET" -> {
                        checkDevicesTransport(request)
                        reply(200, if (devicesEnabled) devices.listing().toString() else "{\"devices\":[]}")
                    }
                    devicesEnabled && request.path == "/v1/devices/pairings/approve" && request.method == "POST" -> {
                        checkDevicesTransport(request)
                        val result = devices.approve(request.json())
                        reply(result.first, result.second.toString())
                    }
                    devicesEnabled && request.path.startsWith("/v1/devices/") &&
                        request.path.endsWith("/revoke") && request.method == "POST" -> {
                        checkDevicesTransport(request)
                        check(request.json().length() == 0)
                        val result = devices.revoke(request.path.removeSuffix("/revoke").substringAfterLast('/'))
                        reply(result.first, result.second.toString())
                    }
                    devicesEnabled && request.path.substringBefore('?').startsWith("/v1/display/devices/") -> {
                        checkDevicesTransport(request)
                        val path = request.path.substringBefore('?')
                        val id = path.removePrefix("/v1/display/devices/").substringBefore('/')
                        when {
                            request.method == "GET" && path.endsWith("/preview.png") -> {
                                val found = devices.describe(id).first == 200
                                if (!found) reply(404, "{}")
                                else if (request.headers["if-none-match"] == devices.etag()) reply(304, "", mapOf("ETag" to devices.etag()))
                                else {
                                    responses += "$route 200"
                                    respondBytes(output, 200, devices.preview(), "image/png", mapOf("ETag" to devices.etag()))
                                }
                            }
                            request.method == "POST" && path.endsWith("/preview/input") -> {
                                val result = devices.input(id, request.json())
                                reply(result.first, result.second.toString())
                            }
                            request.method == "GET" -> {
                                val result = devices.describe(id)
                                reply(result.first, result.second.toString())
                            }
                            request.method == "PUT" -> {
                                val result = devices.save(id, request.json())
                                reply(result.first, result.second.toString())
                            }
                            else -> reply(404, "{}")
                        }
                    }
                    request.method == "GET" && nutritionEnabled && request.path.substringBefore('?') == "/v1/nutrition/foods/search" -> {
                        checkNutritionTransport(request)
                        reply(200, nutrition.search(request.path).toString())
                    }
                    request.method == "GET" && nutritionEnabled && request.path.substringBefore('?') == "/v1/nutrition/memory" -> {
                        checkNutritionTransport(request)
                        reply(200, nutrition.memory(request.path).toString())
                    }
                    request.method == "GET" && nutritionEnabled && request.path.substringBefore('?') == "/v1/nutrition/compositions" -> {
                        checkNutritionTransport(request)
                        reply(200, nutrition.compositions(request.path).toString())
                    }
                    request.method == "POST" && nutritionEnabled && request.path == "/v1/nutrition/estimate" -> {
                        checkNutritionTransport(request)
                        reply(200, nutrition.estimate(request).toString())
                    }
                    request.method == "GET" && nutritionEnabled && request.path.substringBefore('?') == "/v1/nutrition/range" -> {
                        checkNutritionTransport(request)
                        reply(200, nutrition.range(request.path).toString())
                    }
                    request.method == "GET" && nutritionEnabled && request.path.substringBefore('?') == "/v1/nutrition/export" -> {
                        checkNutritionTransport(request)
                        val (mediaType, body) = nutrition.export(request.path)
                        responses += "$route 200"
                        respondBytes(output, 200, body.toByteArray(), mediaType,
                            mapOf("Content-Disposition" to "attachment; filename=\"food-diary.${Uri.parse(request.path).getQueryParameter("format")}\""))
                    }
                    placesEnabled && places.accepts(request.path) -> {
                        val response = places.respond(request)
                        responses += "$route ${response.status}"
                        respondBytes(output, response.status, response.bytes, response.mediaType)
                    }
                    activityEnabled && activity.accepts(request.path) -> {
                        val response = activity.respond(request)
                        if (response.status == 0) {
                            responses += "$route disconnected-after-admission"
                            return
                        }
                        responses += "$route ${response.status}"
                        respondBytes(output, response.status, response.bytes, response.mediaType, response.headers)
                    }
                    notificationsEnabled && notifications.accepts(request.path) -> {
                        val response = notifications.respond(request)
                        reply(response.first, response.second.toString())
                    }
                    timersEnabled && timers.accepts(request.path) -> {
                        val response = timers.respond(request)
                        if (response.first == 0) {
                            responses += "$route disconnected-after-admission"
                            return
                        }
                        reply(response.first, response.second.toString())
                    }
                    tasksEnabled && tasks.accepts(request.path) -> {
                        val response = tasks.respond(request)
                        if (response.status == 0) {
                            responses += "$route disconnected-after-admission"
                            return
                        }
                        responses += "$route ${response.status}"
                        respondBytes(output, response.status, response.bytes, response.mediaType, response.headers)
                    }
                    request.method == "GET" && personalEnabled && request.path.substringBefore('?') in NativePersonalFixture.READ_PATHS -> {
                        checkNutritionTransport(request)
                        reply(200, personal.read(request.path).toString())
                    }
                    request.method == "POST" && personalEnabled && request.path == "/v1/personal/operations" -> {
                        checkNutritionTransport(request)
                        val result = personal.operate(request)
                        if (result == null) {
                            responses += "$route disconnected-after-admission"
                            return
                        }
                        reply(result.first, result.second.toString())
                    }
                    request.method == "GET" && personalEnabled && request.path.startsWith("/v1/personal/operations/by-key/") -> {
                        checkNutritionTransport(request)
                        val result = personal.lookup(request.path.substringAfterLast('/'))
                        reply(result.first, result.second.toString())
                    }
                    request.method == "GET" && nutritionEnabled && request.path.substringBefore('?') == "/v1/nutrition/day" -> {
                        checkNutritionTransport(request)
                        val date = Uri.parse(request.path).getQueryParameter("date") ?: nutrition.date
                        reply(200, nutrition.day(date).toString())
                    }
                    request.method == "GET" && request.path.substringBefore('?') == "/v1/nutrition/days" -> {
                        checkNutritionTransport(request)
                        reply(200, if (nutritionEnabled) nutrition.week(request.path).toString() else "{\"days\":[]}")
                    }
                    request.method == "GET" && nutritionEnabled && request.path.startsWith("/v1/nutrition/items/") -> {
                        checkNutritionTransport(request)
                        val segments = request.path.substringBefore('?').split('/')
                        val target = nutrition.item(segments[4], segments[5])
                        reply(if (target == null) 404 else 200, target?.toString() ?: "{}")
                    }
                    request.method == "POST" && nutritionEnabled && request.path == "/v1/nutrition/operations" -> {
                        checkNutritionTransport(request)
                        val result = nutrition.operate(request)
                        if (result == null) {
                            responses += "$route disconnected-after-admission"
                            return
                        }
                        reply(result.first, result.second.toString())
                    }
                    request.method == "GET" && nutritionEnabled && request.path.startsWith("/v1/nutrition/operations/by-key/") -> {
                        checkNutritionTransport(request)
                        val result = nutrition.lookup(request.path.substringAfterLast('/'))
                        reply(result.first, result.second.toString())
                    }
                    request.method == "GET" && request.path.substringBefore('?') in TODAY_PATHS -> {
                        reply(200, todayReply(request).toString())
                    }
                    request.method == "GET" && request.path == "/v1/me/speech" -> {
                        reply(200, speechSnapshot().toString())
                    }
                    request.method == "GET" && request.path == "/v1/me/speech/readiness?capability=asr" -> {
                        val availability = JSONObject().put("state", "configured")
                        reply(200, JSONObject().put("asr", availability).toString())
                    }
                    request.method == "GET" && request.path.substringBefore('?') == "/v1/conversation/messages" -> {
                        reply(200, assistantHistory(request.path).toString())
                    }
                    request.method == "GET" && request.path == "/v1/conversation/availability" -> {
                        reply(200, JSONObject().put("state", "configured").put("reason", JSONObject.NULL)
                            .put("action", JSONObject.NULL).put("retry_at", JSONObject.NULL)
                            .put("checked_at", Instant.now().toString()).toString())
                    }
                    request.method == "GET" && request.path == "/v1/shell/android" -> {
                        val release = JSONObject()
                            .put("package", "com.chromvoid.bro").put("version_code", 2)
                            .put("version_name", "synthetic-next")
                            .put("size", updateBytes.size)
                            .put("sha256", MessageDigest.getInstance("SHA-256").digest(updateBytes)
                                .joinToString("") { "%02x".format(it.toInt() and 0xff) })
                        reply(200, JSONObject().put("release", release).toString())
                    }
                    request.method == "POST" && request.path == "/v1/auth/native/login" -> {
                        refreshExpiresAt = null
                        reply(200, session(request.json().optString("login", "person")))
                    }
                    request.method == "POST" && request.path == "/v1/auth/native/redeem" -> {
                        refreshExpiresAt = null
                        reply(200, session(request.json().optString("login", "person")))
                    }
                    request.method == "POST" && request.path == "/v1/auth/native/logout" -> reply(200, "{}")
                    request.method == "GET" && request.path == "/v1/me" -> {
                        if (deletionAdmitted) reply(401, "{}")
                        else reply(200, identity(request.userId()).toString())
                    }
                    request.method == "POST" && request.path == "/v1/me" -> {
                        val body = request.json()
                        profiles[request.userId()] = Profile(body.getString("name"), body.getString("locale"), body.getString("timezone"))
                        reply(200, "{}")
                    }
                    request.path == "/v1/me/limits" -> reply(200,
                        """{"limits":[{"kind":"assistant","maximum":50,"used":2}],"timezone":"Europe/Moscow"}""")
                    request.path.substringBefore('?') == "/v1/me/events" -> {
                        streaming = true
                        responses += "$route 200"
                        stream(output)
                    }
                    request.method == "GET" && request.path == "/v1/me/account-deletion-options" ->
                        reply(200, """{"spaces":[],"last_admin":false}""")
                    request.method == "DELETE" && request.path == "/v1/me" -> {
                        deletionAdmitted = true
                        reply(202, JSONObject().put("deletion_id", DELETION_ID).put("status", "pending")
                            .put("receipt_token", DELETION_RECEIPT).put("status_url", "/v1/account-deletions/$DELETION_ID").toString())
                    }
                    request.method == "GET" && request.path == "/v1/account-deletions/$DELETION_ID" -> {
                        if (request.headers["authorization"] != "Bearer $DELETION_RECEIPT" || request.headers.containsKey("x-expected-user"))
                            reply(401, "{}")
                        else reply(200, JSONObject().put("deletion_id", DELETION_ID)
                            .put("status", if (deletionCompleted) "completed" else "pending").toString())
                    }
                    else -> reply(404, "{}")
                }
            }
        } catch (failure: Exception) {
            // Stream cancellation is expected. Other fixture failures expose type and route only.
            if (running && !streaming && failure !is SocketException && failure !is EOFException)
                failures += "$route ${failure.javaClass.simpleName}"
        } finally {
            sockets -= socket
        }
    }

    private fun stream(output: OutputStream) {
        activeStreams.incrementAndGet()
        try {
            output.write("HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\nConnection: close\r\n\r\n".toByteArray())
            while (running) {
                TimeUnit.MILLISECONDS.sleep(100)
                output.write(": synthetic heartbeat\n\n".toByteArray())
                output.flush()
            }
        } finally {
            activeStreams.decrementAndGet()
        }
    }

    private fun speechSnapshot(): JSONObject = JSONObject()
        .put("providers", JSONArray().put(JSONObject().put("id", "codex").put("label", "Subscription voice")
            .put("capabilities", JSONArray(listOf("asr", "tts"))).put("connection_kind", "subscription")
            .put("dynamic_models", false).put("voices", JSONArray(listOf("alloy")))
            .put("default_voice", "alloy").put("models", speechModels()))
            .put(JSONObject().put("id", "openai").put("label", "OpenAI API")
                .put("capabilities", JSONArray(listOf("asr", "tts"))).put("connection_kind", "api_key")
                .put("dynamic_models", true).put("voices", JSONArray(listOf("alloy")))
                .put("default_voice", "alloy").put("models", speechModels())))
        .put("connections", JSONArray())
        .put("preferences", speechPreferences)
        .put("asr", JSONObject().put("state", "configured"))
        .put("tts", JSONObject().put("state", "configured"))

    private fun speechModels(): JSONArray = JSONArray().put(JSONObject()
        .put("id", "synthetic-speech").put("name", "Synthetic speech")
        .put("capabilities", JSONArray(listOf("asr", "tts"))).put("asr_mode", "streaming")
        .put("voices", JSONArray(listOf("alloy"))).put("default_voice", "alloy")
        .put("tts_segment_chars", 1_000).put("selectable", true).put("unavailable_reason", JSONObject.NULL))

    fun seedAssistantHistory(count: Int) {
        check(assistantMessages.isEmpty())
        repeat(count) { index ->
            val id = "history-${index.toString().padStart(4, '0')}"
            val text = "Synthetic history $index"
            assistantMessages[id] = assistantMessage(id, text, "completed", "Saved answer $index")
                .put("created_at", Instant.parse("2026-10-01T12:00:00Z").plusSeconds(index.toLong()).toString())
        }
    }

    fun seedStoreConversation() {
        check(assistantMessages.isEmpty())
        assistantMessages["history-0000"] = assistantMessage("history-0000",
            "Как сделать вечер спокойнее?", "completed",
            "Попробуйте оставить немного времени для себя:\n\n" +
                "• Прогуляйтесь 20 минут без спешки.\n" +
                "• Приготовьте простой ужин.\n" +
                "• Запишите три дела на завтра.\n\n" +
                "Начните с одного небольшого шага — например, с прогулки.")
    }

    private fun todayReply(request: Request): JSONObject {
        check(request.headers["authorization"] == "Bearer ${credential(request.userId())}")
        check(!request.headers.containsKey("cookie"))
        val path = request.path.substringBefore('?')
        val query = Uri.parse(request.path)
        val day = query.getQueryParameter("date") ?: LocalDate.now(ZoneId.of("Europe/Moscow")).toString()
        val empty = !todayEnabled
        return when (path) {
            "/v1/nutrition/day" -> {
                val entries = JSONArray()
                if (!empty) entries.put(JSONObject().put("id", "synthetic-food-entry").put("revision", 1)
                    .put("quantity", "100.00").put("unit", "g").put("local_date", day).put("name", "Synthetic breakfast"))
                JSONObject().put("date", day).put("day_revision", 1).put("completed", false)
                    .put("entries", entries).put("items", entries).put("drafts", JSONArray())
                    .put("totals", JSONObject().put("energy_kcal", JSONObject()
                        .put("known", if (empty) "0" else "1234.50").put("complete", true)))
                    .put("goal", if (empty) JSONObject.NULL else JSONObject().put("id", "synthetic-food-goal")
                        .put("calorie_target", "2000.00").put("effective_local_date", day).put("revision", 1))
                    .put("remaining_kcal", if (empty) JSONObject.NULL else "765.50").put("remaining_complete", true)
            }
            "/v1/tasks/items" -> {
                check(query.getQueryParameter("view") == "today")
                check(query.getQueryParameter("limit") == "5")
                val items = JSONArray()
                if (!empty) items.put(JSONObject().put("id", "synthetic-today-task").put("title", "Synthetic today task")
                    .put("status", "open").put("state", "todo").put("revision", 1)
                    .put("due_on", day).put("overdue", false))
                JSONObject().put("today", day).put("timezone", "Europe/Moscow").put("items", items)
                    .put("total", items.length()).put("next_cursor", JSONObject.NULL)
            }
            "/v1/timers/running" -> {
                val timers = JSONArray()
                if (!empty) timers.put(JSONObject().put("id", "synthetic-today-timer").put("label", "Synthetic tea timer")
                    .put("duration_seconds", 600).put("due_at", Instant.now().plusSeconds(542).toString())
                    .put("revision", 1).put("remaining_seconds", 542))
                JSONObject().put("timers", timers)
            }
            "/v1/notifications" -> {
                val notifications = JSONArray()
                if (!empty) {
                    fun item(id: String, title: String, severity: String, state: String, unread: Boolean) = JSONObject()
                        .put("id", id).put("source", "system").put("topic", "system.backup").put("severity", severity)
                        .put("title", title).put("body", "").put("link", JSONObject.NULL).put("state", state)
                        .put("resolution", JSONObject.NULL).put("count", 1).put("updated_at", Instant.now().toString())
                        .put("read_at", JSONObject.NULL).put("snoozed_until", JSONObject.NULL).put("unread", unread)
                    notifications.put(item("synthetic-open", "Synthetic backup problem", "important", "active", true))
                    notifications.put(item("synthetic-resolved", "Synthetic resolved problem", "urgent", "resolved", true))
                    notifications.put(item("synthetic-info", "Synthetic informational timer", "info", "active", true))
                    notifications.put(item("synthetic-read", "Synthetic read problem", "important", "active", false))
                }
                JSONObject().put("notifications", notifications).put("unread", notifications.length()).put("before", JSONObject.NULL)
            }
            "/v1/personal/overview" -> {
                val metrics = JSONObject()
                listOf("weight" to "kg", "sleep" to "min", "steps" to "count", "active_minutes" to "min").forEach { (kind, unit) ->
                    val value = when {
                        empty -> JSONObject.NULL
                        kind == "weight" -> "72.45"
                        kind == "steps" -> "3200"
                        else -> JSONObject.NULL
                    }
                    metrics.put(kind, JSONObject().put("kind", kind).put("unit", unit).put("value", value)
                        .put("status", if (value == JSONObject.NULL) "no_data" else "available")
                        .put("target", if (kind == "steps" && !empty) "8000" else JSONObject.NULL)
                        .put("records", JSONArray()))
                }
                JSONObject().put("date", day).put("timezone", "Europe/Moscow").put("metrics", metrics)
                    .put("profile", JSONObject().put("user_id", "synthetic-user-a").put("timezone", "Europe/Moscow")
                        .put("height_cm", JSONObject.NULL).put("display_units", "metric").put("revision", 1))
                    .put("preferences", JSONObject()).put("sources", JSONArray())
            }
            "/v1/personal/steps" -> JSONObject().put("date", day).put("override", JSONObject.NULL)
            "/v1/personal/history" -> JSONObject().put("from", query.getQueryParameter("from"))
                .put("to", query.getQueryParameter("to")).put("records", JSONArray()).put("daily", JSONArray())
            else -> error("Unknown synthetic dashboard path")
        }
    }

    private fun checkNutritionTransport(request: Request) {
        check(request.headers["authorization"] == "Bearer ${credential(request.userId())}")
        check(!request.headers.containsKey("cookie"))
        if (request.method == "POST") check(request.headers["x-expected-user"] == request.userId())
    }

    private fun checkDevicesTransport(request: Request) {
        check(request.headers["authorization"] == "Bearer ${credential(request.userId())}")
        check(!request.headers.containsKey("cookie"))
        if (request.method != "GET") check(request.headers["x-expected-user"] == request.userId())
    }

    private fun assistantHistory(path: String): JSONObject {
        val query = Uri.parse(path)
        val exact = query.getQueryParameter("message_id")
        if (exact != null) {
            return JSONObject().put("messages", JSONArray(listOfNotNull(assistantRoot(exact))))
                .put("before", JSONObject.NULL).put("after", JSONObject.NULL)
        }
        val all = assistantMessages.values.sortedWith(compareBy<JSONObject> { it.getString("created_at") }
            .thenBy { it.getString("id") })
        val around = query.getQueryParameter("around")
        val before = query.getQueryParameter("before")
        val after = query.getQueryParameter("after")
        fun index(id: String): Int = all.indexOfFirst { it.getString("id") == id }.also {
            require(it >= 0) { "Unknown history cursor" }
        }
        val (from, until) = when {
            around != null -> {
                val anchor = index(requireNotNull(assistantRoot(around)).getString("id"))
                maxOf(0, anchor - 15) to minOf(all.size, anchor + 15)
            }
            before != null -> index(before).let { maxOf(0, it - 30) to it }
            after != null -> (index(after) + 1).let { it to minOf(all.size, it + 30) }
            else -> maxOf(0, all.size - 30) to all.size
        }
        val page = all.subList(from, until)
        return JSONObject().put("messages", JSONArray(page))
            .put("before", if (from > 0 && page.isNotEmpty()) page.first().getString("id") else JSONObject.NULL)
            .put("after", if (until < all.size && page.isNotEmpty()) page.last().getString("id") else JSONObject.NULL)
    }

    /** Both user and assistant message identities address the owning exchange window. */
    private fun assistantRoot(id: String): JSONObject? = assistantMessages[id] ?: assistantMessages.values.firstOrNull { message ->
        val turns = message.getJSONArray("turns")
        (0 until turns.length()).any { turns.getJSONObject(it).optString("reply_message_id") == id }
    }

    private fun assistantMessage(id: String, text: String, state: String, answer: String? = null): JSONObject = JSONObject()
        .put("id", id).put("created_at", Instant.now().toString()).put("text", text).put("state", state)
        .put("run_generation", ASSISTANT_GENERATION).put("intent_version", 2).put("intent_revision", 0)
        .put("can_clarify", state in setOf("routing", "processing"))
        .put("error", JSONObject.NULL).put("turns", assistantTurns(id, answer))

    private fun assistantTurns(id: String, answer: String?): JSONArray = JSONArray().also { turns ->
        if (answer != null) turns.put(JSONObject().put("id", "turn-$id").put("reply_message_id", "reply-$id")
            .put("state", "completed").put("result", JSONObject().put("reply", answer)
                .put("question", JSONObject.NULL).put("receipts", JSONArray())))
    }

    private fun session(login: String): String {
        val id = if (ambientEnabled) {
            if (login == "other") NativeAmbientFixture.USER_B else NativeAmbientFixture.USER_A
        } else if (login == "other") "synthetic-user-b" else "synthetic-user-a"
        val expiry = Instant.now().plusSeconds(3_600).toString()
        if (ambientEnabled) ambient.expiresAt = expiry
        return JSONObject()
            .put("credential", credential(id))
            .put("user", identity(id, expiry))
            .put("device", JSONObject().put("id", if (ambientEnabled) NativeAmbientFixture.DEVICE_ID else "synthetic-device")
                .put("kind", "android").put("name", "Emulator"))
            .put("session", JSONObject().put("id", "synthetic-session").put("transport", "native"))
            .put("expires_at", expiry)
            .put("scopes", scopes())
            .toString()
    }

    private fun identity(id: String, expiry: String? = null): JSONObject {
        val profile = profiles[id] ?: Profile(if (id.endsWith("-b")) "Other Person" else "Person", "ru", "Europe/Moscow")
        return JSONObject()
            .put("id", id).put("login", if (id.endsWith("-b")) "other" else "person")
            .put("name", profile.name).put("locale", profile.locale).put("timezone", profile.timezone)
            .put("system_admin", false).put("revision", 1)
            .put("device_id", if (ambientEnabled) NativeAmbientFixture.DEVICE_ID else "synthetic-device")
            .put("session_expires_at", expiry ?: if (ambientEnabled && refreshExpiresAt == null) ambient.expiresAt
                else Instant.ofEpochMilli(refreshExpiresAt ?: (System.currentTimeMillis() + 3_600_000)).toString())
            .put("scopes", scopes())
    }

    private fun scopes() = JSONArray(listOf("session.control", "conversation.read", "conversation.write") +
        (if (todayEnabled) setOf("nutrition.read", "tasks.read", "timers.read", "personal.read") else emptySet()) +
        (if (nutritionEnabled) setOf("nutrition.read", "nutrition.write") else emptySet()) +
        (if (circleEnabled) setOf("community.read", "community.write", "tasks.read") else emptySet()) +
        (if (ambientEnabled) setOf("ambient_audio.read", "ambient_audio.write", "calls.read", "calls.write") else emptySet()) +
        (if (tasksEnabled) setOf("tasks.read", "tasks.write", "community.read", "community.write") else emptySet()) +
        (if (timersEnabled) setOf("timers.read", "timers.write") else emptySet()) +
        (if (personalEnabled) setOf("personal.read", "personal.write") else emptySet()) +
        (if (tradingEnabled) setOf("trading.read", "trading.write") else emptySet()) +
        (if (activityEnabled) setOf("activity.read", "activity.write", "personal.read") else emptySet()) +
        (if (placesEnabled) setOf("places.read", "location.read", "location.write") else emptySet()))

    private fun respond(output: OutputStream, status: Int, body: String, headers: Map<String, String> = emptyMap()) {
        respondBytes(output, status, body.toByteArray(), "application/json", headers)
    }

    private fun respondBytes(output: OutputStream, status: Int, bytes: ByteArray, mediaType: String,
        headers: Map<String, String> = emptyMap()) {
        val head = buildString {
            append("HTTP/1.1 $status Fixture\r\nContent-Type: $mediaType\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n")
            headers.forEach { (name, value) -> append("$name: $value\r\n") }
            append("\r\n")
        }
        output.write(head.toByteArray())
        output.write(bytes)
        output.flush()
    }

    override fun close() {
        running = false
        listener.close()
        sockets.forEach { runCatching { it.close() } }
        workers.shutdownNow()
    }

    private data class Profile(val name: String, val locale: String, val timezone: String)

    class Request(val method: String, val path: String, val headers: Map<String, String>, val body: String,
        val bytes: ByteArray = body.toByteArray()) {
        fun json() = JSONObject(body)
        fun userId(): String = when (headers["authorization"]) {
            "Bearer ${credential("synthetic-user-b")}" -> "synthetic-user-b"
            "Bearer ${credential(NativeAmbientFixture.USER_A)}" -> NativeAmbientFixture.USER_A
            "Bearer ${credential(NativeAmbientFixture.USER_B)}" -> NativeAmbientFixture.USER_B
            else -> "synthetic-user-a"
        }
        override fun toString() = "Request(method=$method, path=${path.substringBefore('?')}, private=redacted)"
    }

    companion object {
        val TODAY_PATHS = setOf("/v1/nutrition/day", "/v1/tasks/items", "/v1/timers/running", "/v1/notifications",
            "/v1/personal/overview", "/v1/personal/steps", "/v1/personal/history")
        const val DELETION_ID = "11111111-1111-4111-8111-111111111111"
        const val DELETION_RECEIPT = "synthetic-deletion-receipt-never-valid"
        const val ASSISTANT_GENERATION = "33333333-3333-4333-8333-333333333333"
        fun credential(id: String) = "synthetic-native-$id-never-valid"
    }
}

private fun BufferedInputStream.line(): String {
    val result = StringBuilder()
    while (result.length <= 8_192) {
        val value = read()
        if (value < 0) throw EOFException()
        if (value == '\n'.code) return result.toString().removeSuffix("\r")
        result.append(value.toChar())
    }
    throw IllegalArgumentException("Fixture HTTP line exceeds limit")
}
