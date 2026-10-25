package assistant.mobile.android

import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.util.UUID
import kotlin.math.ceil

/** The timer read schemas and operation receipts from server/plugins/timers/service.py. */
internal class NativeTimersFixture {
    private val timers = linkedMapOf<String, JSONObject>()
    private val started = linkedMapOf<String, Long>()
    private val reminders = linkedMapOf<String, JSONObject>()
    private val receipts = linkedMapOf<String, JSONObject>()
    private val bodies = linkedMapOf<String, String>()
    @Volatile var lookupOnline = true
    @Volatile var disconnectAdmission = false

    fun accepts(path: String): Boolean = path.substringBefore('?').startsWith("/v1/timers/")

    @Synchronized fun timerCount(): Int = timers.size
    @Synchronized fun reminderCount(): Int = reminders.size
    @Synchronized fun writeCount(): Int = receipts.size
    @Synchronized fun timerIds(): List<String> = timers.keys.toList()
    @Synchronized fun reminderIds(): List<String> = reminders.keys.toList()
    @Synchronized fun reminder(id: String): JSONObject = copy(requireNotNull(reminders[id]))

    @Synchronized fun seedTimer(
        id: String = TIMER_ID,
        label: String = "Synthetic tea timer",
        remainingSeconds: Int = 542,
    ) {
        timers[id] = JSONObject().put("id", id).put("label", label).put("duration_seconds", 600)
            // Deliberately different from the countdown: only the read's remaining_seconds is authority.
            .put("due_at", Instant.now().plusSeconds(86_400).toString()).put("revision", 3)
            .put("remaining_seconds", remainingSeconds)
        started[id] = System.nanoTime()
    }

    @Synchronized fun finishTimer(id: String) {
        check(timers.remove(id) != null)
        started.remove(id)
    }

    /** Status zero closes the socket after admission, keeping the receipt available by its key. */
    @Synchronized fun respond(request: NativeLoopbackServer.Request): Pair<Int, JSONObject> {
        check(request.headers["authorization"] == "Bearer ${NativeLoopbackServer.credential(request.userId())}")
        check(!request.headers.containsKey("cookie"))
        val path = request.path.substringBefore('?')
        return when {
            request.method == "GET" && path == "/v1/timers/running" -> 200 to JSONObject().put("timers",
                JSONArray(timers.values.map { timer ->
                    val elapsed = (System.nanoTime() - started.getValue(timer.getString("id"))) / 1_000_000_000.0
                    copy(timer).put("remaining_seconds", ceil(timer.getInt("remaining_seconds") - elapsed).toInt().coerceAtLeast(0))
                }))
            request.method == "GET" && path == "/v1/timers/reminders" ->
                200 to JSONObject().put("reminders", JSONArray(reminders.values.map(::copy)))
            request.method == "GET" && path.startsWith("/v1/timers/operations/by-key/") -> {
                val receipt = receipts[path.substringAfterLast('/')]
                when {
                    !lookupOnline -> 503 to JSONObject()
                    receipt == null -> 404 to JSONObject()
                    else -> 200 to copy(receipt)
                }
            }
            request.method == "POST" && path == "/v1/timers/operations" -> operate(request)
            else -> error("Unknown synthetic timer route: ${request.method} $path")
        }
    }

    private fun operate(request: NativeLoopbackServer.Request): Pair<Int, JSONObject> {
        check(request.headers["x-expected-user"] == request.userId())
        check(request.headers["content-type"]?.substringBefore(';') == "application/json")
        val key = requireNotNull(request.headers["idempotency-key"])
        UUID.fromString(key)
        receipts[key]?.let {
            check(bodies.getValue(key) == request.body) { "A timer operation key changed its body" }
            return 200 to copy(it)
        }
        val body = request.json()
        check(body.keys().asSequence().all { it in setOf("kind", "payload", "expected_revisions") })
        val kind = body.getString("kind")
        val payload = body.getJSONObject("payload")
        val receipt = JSONObject().put("operation_id", UUID.randomUUID().toString())
            .put("kind", kind).put("event_seq", receipts.size + 1)
        when (kind) {
            "timer.start" -> {
                check(payload.keys().asSequence().all { it in setOf("duration_seconds", "label") })
                val seconds = payload.getInt("duration_seconds")
                check(seconds in 1..86_400)
                val id = UUID.randomUUID().toString()
                val timer = JSONObject().put("id", id).put("label", payload.opt("label") ?: JSONObject.NULL)
                    .put("duration_seconds", seconds).put("due_at", Instant.now().plusSeconds(seconds.toLong()).toString())
                timers[id] = copy(timer).put("revision", 1).put("remaining_seconds", seconds)
                started[id] = System.nanoTime()
                receipt.put("timers", JSONArray().put(timer))
            }
            "reminder.create" -> {
                check(payload.keys().asSequence().all { it in setOf("at", "text", "task_id") })
                check(payload.getString("text").isNotBlank())
                check(Instant.parse(payload.getString("at")).isAfter(Instant.now()))
                val task = payload.optString("task_id").takeUnless { it.isBlank() || it == "null" }
                if (task != null) check(task == NativeTasksFixture.TASK_ID)
                val id = UUID.randomUUID().toString()
                val reminder = JSONObject().put("id", id).put("text", payload.getString("text"))
                    .put("due_at", payload.getString("at")).put("task_id", task ?: JSONObject.NULL)
                reminders[id] = copy(reminder).put("revision", 1)
                receipt.put("reminders", JSONArray().put(reminder))
            }
            "timer.cancel", "reminder.cancel" -> {
                val isTimer = kind == "timer.cancel"
                val field = if (isTimer) "timer_ids" else "reminder_ids"
                check(payload.keys().asSequence().toList() == listOf(field))
                val ids = payload.getJSONArray(field)
                val expected = body.getJSONObject("expected_revisions")
                check(expected.length() == ids.length())
                val source = if (isTimer) timers else reminders
                val selected = (0 until ids.length()).map { requireNotNull(source[ids.getString(it)]) }
                if (selected.any { expected.getInt(it.getString("id")) != it.getInt("revision") }) {
                    return 409 to JSONObject().put("error", "conflict").put("message", "timer has already changed")
                }
                receipt.put(if (isTimer) "timers" else "reminders", JSONArray(selected.map { item ->
                    copy(item).apply { remove("revision"); remove("remaining_seconds"); if (isTimer) remove("due_at") }
                }))
                selected.forEach { source.remove(it.getString("id")); if (isTimer) started.remove(it.getString("id")) }
            }
            else -> error("Unknown synthetic timer operation: $kind")
        }
        receipts[key] = copy(receipt)
        bodies[key] = request.body
        if (disconnectAdmission) {
            disconnectAdmission = false
            return 0 to JSONObject()
        }
        return 200 to receipt
    }

    private fun copy(value: JSONObject): JSONObject = JSONObject(value.toString())

    companion object {
        const val TIMER_ID = "81000000-0000-4000-8000-000000000001"
    }
}
