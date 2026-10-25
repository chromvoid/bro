package assistant.mobile.android.ambient

import assistant.mobile.core.NativeIdentity
import assistant.mobile.core.SessionLease
import assistant.mobile.core.SessionManager
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.time.Instant
import kotlinx.coroutines.runBlocking

/** The foreground service borrows native authority; no credential is copied into its outbox. */
interface AmbientSessionPort {
    val origin: String
    val serverOrigin: String get() = origin
    fun lease(): SessionLease?
    fun identity(): NativeIdentity?
    fun isCurrent(lease: SessionLease): Boolean
    fun expire(lease: SessionLease)
}

class NativeAmbientSessionPort(
    private val sessions: SessionManager,
    private val resolveServerOrigin: () -> String = { sessions.origin },
) : AmbientSessionPort {
    override val origin: String get() = sessions.origin
    override val serverOrigin: String get() = resolveServerOrigin()
    override fun lease(): SessionLease? = sessions.lease()
    override fun identity(): NativeIdentity? = sessions.identity.value
    override fun isCurrent(lease: SessionLease): Boolean = sessions.isCurrent(lease)
    override fun expire(lease: SessionLease) { runBlocking { sessions.expire(lease) } }
}

/** Bounded native bearer requests survive Activity teardown while the microphone service runs. */
class AmbientHttp(private val session: AmbientSessionPort, private val current: () -> Boolean) {
    private val origin = session.origin
    @Volatile private var connection: HttpURLConnection? = null

    fun cancel() { connection?.disconnect() }

    fun verify(): Pair<AmbientBinding, SessionLease> {
        val lease = session.lease() ?: throw AmbientHttpException(401)
        val (status, body) = request("GET", "/v1/me", lease)
        if (status != 200) throw AmbientHttpException(status)
        val binding = AmbientContract.parseMeBinding(body, origin) ?: throw AmbientProblem("server_rejected")
        val identity = session.identity() ?: throw AmbientProblem("not_authenticated")
        if (!binding.validAt(Instant.now()) || binding.userId != lease.namespace.userId ||
            binding.deviceId != identity.deviceId || binding.expiresInstant != Instant.parse(identity.sessionExpiresAt)) {
            throw AmbientProblem("not_authenticated")
        }
        checkCredential(lease)
        return binding to lease
    }

    fun readiness(binding: AmbientBinding, credential: SessionLease): Set<AmbientAudioFormat> {
        check(binding.origin == origin && binding.userId == credential.namespace.userId)
        val (status, body) = request("GET", "/v1/ambient-audio", credential)
        if (status != 200) throw AmbientHttpException(status)
        return AmbientContract.acceptedFormats(body) ?: throw AmbientProblem("server_rejected")
    }

    fun upload(batch: QueuedBatch, audio: File, credential: SessionLease): AmbientReceipt {
        checkCredential(credential)
        val identity = session.identity() ?: throw AmbientProblem("not_authenticated")
        // The capture-time expiry is historical metadata. Only the current native lease
        // authorizes this request; ready audio still belongs to the same person and device.
        if (batch.binding.origin != origin || batch.binding.userId != credential.namespace.userId ||
            batch.binding.deviceId != identity.deviceId) throw AmbientProblem("not_authenticated")
        if (audio.length() !in 1..batch.format.maximumBytes.toLong()) throw QueueCorruptException()
        val headers = mapOf(
            "Content-Type" to batch.format.mimeType,
            "X-Audio-Batch-ID" to batch.id, "X-Audio-Session-ID" to batch.sessionId,
            "X-Audio-Sequence" to batch.sequence.toString(), "X-Audio-Started-At" to batch.startedAt,
            "X-Audio-SHA256" to batch.sha256,
        )
        val (status, body) = request("POST", "/v1/ambient-audio/batches", credential, headers, audio)
        if (status !in 200..299) throw AmbientHttpException(status)
        checkCredential(credential)
        return AmbientContract.parseReceipt(body)?.takeIf { it.id == batch.id && it.sha256 == batch.sha256 && it.stored }
            ?: throw AmbientProblem("invalid_receipt")
    }

    private fun checkCredential(credential: SessionLease) {
        val identity = session.identity() ?: throw AmbientProblem("not_authenticated")
        if (!current() || !session.isCurrent(credential) || credential.namespace.origin != origin ||
            credential.namespace.userId != identity.id) throw AmbientProblem("not_authenticated")
        if (runCatching { Instant.now().isBefore(Instant.parse(identity.sessionExpiresAt)) }.getOrDefault(false).not()) {
            session.expire(credential)
            throw AmbientProblem("not_authenticated")
        }
    }

    private fun request(method: String, path: String, credential: SessionLease,
        headers: Map<String, String> = emptyMap(), file: File? = null): Pair<Int, String> {
        checkCredential(credential)
        val server = session.serverOrigin
        require(AmbientContract.validOrigin(server)) { "Invalid ambient server origin" }
        val conn = URI(server + path).toURL().openConnection() as HttpURLConnection
        connection = conn
        try {
            conn.instanceFollowRedirects = false
            conn.connectTimeout = 10_000
            conn.readTimeout = 20_000
            conn.requestMethod = method
            conn.setRequestProperty("Authorization", "Bearer ${credential.credential}")
            conn.setRequestProperty("X-Expected-User", credential.namespace.userId)
            conn.setRequestProperty("Accept", "application/json")
            headers.forEach { (name, value) -> conn.setRequestProperty(name, value) }
            if (file != null) {
                check(file.length() in 1..AmbientWav.MAX_WAV_BYTES.toLong())
                conn.doOutput = true
                conn.setFixedLengthStreamingMode(file.length())
                checkCredential(credential)
                conn.outputStream.use { out -> file.inputStream().use { it.copyTo(out) } }
            }
            val status = conn.responseCode
            val stream = if (status in 200..299) conn.inputStream else conn.errorStream
            val bytes = stream?.use { input ->
                val out = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(4096)
                while (out.size() <= 65_536) {
                    val read = input.read(buffer, 0, minOf(buffer.size, 65_537 - out.size()))
                    if (read < 0) break
                    out.write(buffer, 0, read)
                }
                out.toByteArray()
            } ?: byteArrayOf()
            require(bytes.size <= 65_536) { "Ambient response exceeds its limit" }
            if (status == 401) {
                session.expire(credential)
                throw AmbientHttpException(status)
            }
            checkCredential(credential)
            return status to bytes.toString(Charsets.UTF_8)
        } finally {
            if (connection === conn) connection = null
            conn.disconnect()
        }
    }
}

class AmbientHttpException(val status: Int) : Exception("Ambient HTTP $status")
