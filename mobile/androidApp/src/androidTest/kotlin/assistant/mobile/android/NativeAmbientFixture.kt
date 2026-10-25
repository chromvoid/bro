package assistant.mobile.android

import android.net.Uri
import assistant.mobile.android.ambient.AmbientAudioFormat
import assistant.mobile.android.ambient.AmbientWav
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

/** Synthetic native bearer archive, including admission followed by a lost HTTP response. */
internal class NativeAmbientFixture {
    data class Response(val status: Int, val bytes: ByteArray, val mediaType: String = "application/json")
    private data class Stored(val owner: String, val metadata: JSONObject, val audio: ByteArray, var transcript: JSONObject)
    private val stored = linkedMapOf<String, Stored>()
    val uploadIds = CopyOnWriteArrayList<String>()
    val playbackIds = CopyOnWriteArrayList<String>()
    val retryIds = CopyOnWriteArrayList<String>()
    val deletedIds = CopyOnWriteArrayList<String>()
    @Volatile var disconnectUploadAfterAdmission = false
    @Volatile var uploadsAvailable = true
    var expiresAt: String = Instant.now().plusSeconds(3_600).toString()

    fun accepts(path: String) = path.substringBefore('?').let {
        it == "/v1/ambient-audio" || it.startsWith("/v1/ambient-audio/")
    }

    @Synchronized fun count() = stored.size
    @Synchronized fun contains(id: String) = id in stored

    @Synchronized fun failTranscription(id: String, unknown: Boolean = false) {
        stored.getValue(id).transcript = transcript(id, "failed", null,
            if (unknown) "unknown_outcome" else "decode_failed", unknown)
    }

    @Synchronized fun respond(request: NativeLoopbackServer.Request): Response {
        val owner = request.userId()
        check(owner in setOf(USER_A, USER_B))
        check(request.headers["authorization"] == "Bearer ${NativeLoopbackServer.credential(owner)}")
        check(!request.headers.containsKey("cookie"))
        check(!request.path.contains("credential") && !request.path.contains("token"))
        if (request.method != "GET") check(request.headers["x-expected-user"] == owner)
        val path = request.path.substringBefore('?')
        val query = Uri.parse(request.path)
        when {
            request.method == "GET" && path == "/v1/ambient-audio" -> return json(200,
                JSONObject().put("used_bytes", stored.values.filter { it.owner == owner }.sumOf { it.audio.size.toLong() })
                    .put("accepted_formats", JSONArray(AmbientAudioFormat.entries.map { it.id }))
                    .put("limits", JSONObject().put("retention_days", 30).put("max_user_bytes", 64L * 1024 * 1024)
                        .put("max_compressed_batch_bytes", AmbientAudioFormat.MAX_COMPRESSED_BYTES)
                        .put("max_batch_duration_ms", AmbientWav.SEGMENT_SECONDS * 1_000)))
            request.method == "POST" && path == "/v1/ambient-audio/batches" -> {
                if (!uploadsAvailable) return json(503, JSONObject())
                val id = requireNotNull(request.headers["x-audio-batch-id"]).also(UUID::fromString)
                val session = requireNotNull(request.headers["x-audio-session-id"]).also(UUID::fromString)
                val sequence = requireNotNull(request.headers["x-audio-sequence"]).toLong().also { check(it >= 0) }
                val began = requireNotNull(request.headers["x-audio-started-at"]).also(Instant::parse)
                val sha = requireNotNull(request.headers["x-audio-sha256"])
                check(sha == digest(request.bytes))
                val format = AmbientAudioFormat.entries.single { it.mimeType == request.headers["content-type"] }
                val info = format.validate(request.bytes)
                uploadIds += id
                val previous = stored[id]
                if (previous == null) {
                    stored[id] = Stored(owner, JSONObject().put("id", id).put("session_id", session).put("sequence", sequence)
                        .put("sha256", sha).put("stored", true).put("format", format.id).put("bytes", request.bytes.size)
                        .put("duration_ms", info.encodedSampleCount * 1_000 / info.encodedSampleRate)
                        .put("expires_at", Instant.now().plusSeconds(30L * 86_400).toString())
                        .put("started_at", began).put("received_at", Instant.now().toString()).put("state", "stored"),
                        request.bytes.copyOf(), transcript(id, "ready", TRANSCRIPT))
                } else {
                    check(previous.owner == owner)
                    check(previous.metadata.getString("sha256") == sha && previous.audio.contentEquals(request.bytes))
                    check(previous.metadata.getString("session_id") == session && previous.metadata.getLong("sequence") == sequence)
                }
                if (disconnectUploadAfterAdmission) {
                    disconnectUploadAfterAdmission = false
                    return Response(0, byteArrayOf())
                }
                return json(200, JSONObject().put("id", id).put("sha256", sha).put("stored", true))
            }
            request.method == "GET" && path == "/v1/ambient-audio/recordings" -> {
                check(query.getQueryParameter("limit") == "30")
                val from = query.getQueryParameter("from")?.let(Instant::parse)
                val to = query.getQueryParameter("to")?.let(Instant::parse)
                val values = stored.values.filter { item ->
                    val began = Instant.parse(item.metadata.getString("started_at"))
                    item.owner == owner && (from == null || !began.isBefore(from)) && (to == null || began.isBefore(to))
                }.map { item -> JSONObject(item.metadata.toString()).put("transcription", summary(item.transcript)) }
                return json(200, JSONObject().put("recordings", JSONArray(values)).put("next_cursor", JSONObject.NULL))
            }
        }
        val rest = path.removePrefix("/v1/ambient-audio/batches/")
        val id = rest.substringBefore('/')
        val item = stored[id]?.takeIf { it.owner == owner } ?: return json(404, JSONObject())
        return when {
            request.method == "GET" && rest.endsWith("/transcription") -> json(200, item.transcript)
            request.method == "POST" && rest.endsWith("/transcription/retry") -> {
                check(item.transcript.getString("status") in setOf("failed", "empty"))
                retryIds += id
                item.transcript = transcript(id, "ready", RETRIED_TRANSCRIPT)
                json(200, item.transcript)
            }
            request.method == "DELETE" && rest == id -> {
                stored.remove(id)
                deletedIds += id
                json(200, JSONObject().put("id", id).put("deleted", true))
            }
            request.method == "GET" && rest == id -> {
                playbackIds += id
                Response(200, item.audio, AmbientAudioFormat.fromId(item.metadata.getString("format"))!!.mimeType)
            }
            request.method == "GET" && rest == "$id/playback" -> {
                check(query.getQueryParameter("format") == "wav")
                playbackIds += id
                Response(200, if (item.metadata.getString("format") == "wav") item.audio else AmbientWav.encode(tone()), "audio/wav")
            }
            else -> json(404, JSONObject())
        }
    }

    private fun json(status: Int, body: JSONObject) = Response(status, body.toString().toByteArray())
    private fun summary(value: JSONObject) = JSONObject().put("status", value.get("status"))
        .put("preview", value.opt("text") ?: JSONObject.NULL).put("error_code", value.get("error_code"))
        .put("updated_at", value.get("updated_at")).put("retry_may_repeat", value.get("retry_may_repeat"))

    private fun transcript(id: String, state: String, text: String?, error: String? = null, unknown: Boolean = false) = JSONObject()
        .put("id", id).put("status", state).put("text", text ?: JSONObject.NULL).put("error_code", error ?: JSONObject.NULL)
        .put("updated_at", Instant.now().toString()).put("retry_may_repeat", unknown)
        .put("provider", "synthetic").put("model", "synthetic").put("transcribed_at", if (state == "ready") Instant.now().toString() else JSONObject.NULL)

    companion object {
        const val USER_A = "22220000-0000-4000-8000-000000000001"
        const val USER_B = "22220000-0000-4000-8000-000000000002"
        const val DEVICE_ID = "22220000-0000-4000-8000-000000000003"
        const val TRANSCRIPT = "Synthetic recorded speech."
        const val RETRIED_TRANSCRIPT = "Synthetic repeated transcription."

        /** Four seconds of a deterministic triangle wave; it contains no recorded human audio. */
        fun tone(): ByteArray = ByteArray(AmbientWav.SAMPLE_RATE * 4 * 2).also { pcm ->
            repeat(pcm.size / 2) { index ->
                val phase = index % 80
                val sample = (if (phase < 40) phase else 80 - phase) * 400 - 8_000
                pcm[index * 2] = sample.toByte()
                pcm[index * 2 + 1] = (sample shr 8).toByte()
            }
        }

        private fun digest(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it.toInt() and 255) }
    }
}
