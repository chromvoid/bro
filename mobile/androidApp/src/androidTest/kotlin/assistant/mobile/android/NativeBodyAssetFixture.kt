package assistant.mobile.android

import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.time.LocalDate
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

/** Exact repository assets uploaded by CI into app-private files, never packaged in either APK. */
internal class NativeBodyAssetFixture(private val personal: NativePersonalFixture) {
    private val directory: File get() = File(InstrumentationRegistry.getInstrumentation().targetContext.filesDir, "body-fixtures")
    private val assets by lazy {
        val files = directory.listFiles()?.filter { it.isFile }?.sortedBy { it.name }.orEmpty()
        check(files.any { it.name == "male.glb" }) { "CI body fixture upload missing: $directory" }
        files.associateBy { "/assets/body-${sha256(it.readBytes())}-${it.name}" }
    }
    private val receipts = mutableMapOf<String, JSONObject>()
    private val bodies = mutableMapOf<String, String>()
    private var character = "sculpture"
    private var revision = 1
    val assetRequests = AtomicInteger()
    @Volatile var online = true
    @Volatile var corruptFile: String? = null

    data class Response(val status: Int, val bytes: ByteArray, val type: String = "application/json")

    fun accepts(request: NativeLoopbackServer.Request): Boolean {
        val path = request.path.substringBefore('?')
        return path == "/body-assets.json" || path.startsWith("/assets/body-") ||
            path == "/v1/personal/body/presentation" ||
            path in setOf("/v1/personal/overview", "/v1/personal/history") ||
            (path == "/v1/personal/operations" && request.json().optString("kind") == "personal.body.presentation.set") ||
            (path.startsWith("/v1/personal/operations/by-key/") && synchronized(this) { receipts.containsKey(path.substringAfterLast('/')) })
    }

    @Synchronized fun respond(request: NativeLoopbackServer.Request, history: NativePersonalHistoryFixture): Response {
        check(request.headers["authorization"]?.startsWith("Bearer ") == true) { "Body assets must use the native session" }
        val path = request.path.substringBefore('?')
        if (path == "/body-assets.json" || path.startsWith("/assets/body-")) {
            assetRequests.incrementAndGet()
            if (!online) return json(503, JSONObject())
            if (path == "/body-assets.json") {
                val entries = JSONObject()
                assets.forEach { (assetPath, file) ->
                    val hash = assetPath.removePrefix("/assets/body-").take(64)
                    entries.put(file.name, JSONObject().put("path", assetPath)
                        .put("sha256", hash).put("bytes", file.length()).put("content_type", type(file)))
                }
                return json(200, JSONObject().put("version", 1).put("files", entries))
            }
            val file = assets[path] ?: return json(404, JSONObject())
            val bytes = file.readBytes()
            if (corruptFile == file.name && bytes.isNotEmpty()) bytes[bytes.lastIndex] = (bytes.last().toInt() xor 1).toByte()
            return Response(200, bytes, type(file))
        }
        if (path == "/v1/personal/body/presentation") return json(200, presentation())
        if (path == "/v1/personal/overview") {
            val overview = personal.overview()
            val metrics = overview.getJSONObject("metrics")
            scanRecords().filter { it.getString("local_date") == personal.date }.forEach { record ->
                val kind = record.getString("kind")
                metrics.put(kind, JSONObject().put("kind", kind).put("unit", if (kind == "body_fat_percent") "%" else "kg")
                    .put("status", "available").put("value", record.getJSONObject("data").get("value"))
                    .put("records", JSONArray().put(record)))
            }
            return json(200, overview)
        }
        if (path == "/v1/personal/history") {
            val page = history.respond(request).second
            val records = page.getJSONArray("records")
            scanRecords().forEach { records.put(it) }
            return json(200, page)
        }
        if (path.startsWith("/v1/personal/operations/by-key/")) return json(200, receipts.getValue(path.substringAfterLast('/')))
        val key = requireNotNull(request.headers["idempotency-key"])
        UUID.fromString(key)
        receipts[key]?.let {
            check(bodies.getValue(key) == request.body)
            return json(200, it)
        }
        val body = request.json()
        val payload = body.getJSONObject("payload")
        check(body.getJSONObject("expected_revisions").length() == 0)
        if (payload.getInt("revision") != revision) return json(409, JSONObject().put("error", "conflict"))
        character = payload.getString("character_id").also { check(it in setOf("sculpture", "spartan")) }
        revision++
        val receipt = JSONObject().put("operation_id", UUID.randomUUID().toString()).put("kind", "personal.body.presentation.set")
            .put("event_seq", receipts.size + 1).put("body_presentation", presentation())
        receipts[key] = receipt
        bodies[key] = request.body
        return json(201, receipt)
    }

    @Synchronized fun presentation() = JSONObject().put("character_id", character).put("revision", revision)

    private fun scanRecords(): List<JSONObject> = listOf(0L, 1L).flatMap { age ->
        val date = LocalDate.parse(personal.date).minusDays(age).toString()
        val scan = "synthetic-body-$date"
        listOf("weight" to if (age == 0L) "72.45" else "75.0",
            "body_fat_percent" to if (age == 0L) "18.20" else "25.0",
            "skeletal_muscle_mass" to if (age == 0L) "31.0" else "28.0",
            "body_water" to if (age == 0L) "42.0" else "40.0").map { (kind, value) ->
            JSONObject().put("id", UUID.nameUUIDFromBytes("$scan-$kind".toByteArray()).toString())
                .put("kind", kind).put("source_id", "inbody-synthetic").put("source_name", "Synthetic InBody")
                .put("start_at", "${date}T07:00:00Z").put("end_at", "${date}T07:00:00Z")
                .put("timezone", "Europe/Moscow").put("local_date", date).put("method", "bioimpedance")
                .put("data", JSONObject().put("value", value)).put("revision", 1).put("quality", JSONArray())
                .put("origin", JSONObject().put("provider", "inbody").put("record_id", scan))
                .put("received_at", "${date}T07:00:00Z")
        }
    }

    private fun json(status: Int, value: JSONObject) = Response(status, value.toString().toByteArray())
    private fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    private fun type(file: File) = when (file.extension) {
        "glb" -> "model/gltf-binary"
        "webp" -> "image/webp"
        "png" -> "image/png"
        "json" -> "application/json"
        else -> "text/markdown; charset=utf-8"
    }
}
