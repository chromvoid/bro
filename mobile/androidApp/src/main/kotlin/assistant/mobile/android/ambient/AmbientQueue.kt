package assistant.mobile.android.ambient

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.FileTime
import java.security.MessageDigest
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.encodeToJsonElement

class QueueFullException : IOException("The ambient audio queue is full")
class QueueCorruptException : IOException("The ambient audio queue needs to be cleared")

@Serializable
data class QueuedBatch(
    val id: String,
    @SerialName("session_id") val sessionId: String,
    val sequence: Long,
    @SerialName("started_at") val startedAt: String,
    val sha256: String,
    @SerialName("byte_size") val byteSize: Long,
    @SerialName("sample_count") val sampleCount: Long,
    val binding: AmbientBinding,
    val version: Int = 1,
    val format: AmbientAudioFormat = AmbientAudioFormat.WAV,
    @SerialName("encoded_sample_count") val encodedSampleCount: Long? = null,
    @SerialName("encoded_sample_rate") val encodedSampleRate: Int? = null,
) {
    val durationMillis: Long get() = sampleCount * 1_000 / AmbientWav.SAMPLE_RATE
}

data class QueueSnapshot(val count: Int, val bytes: Long, val diskBytes: Long)

/**
 * A private-directory outbox. An atomic directory rename commits audio and manifest together.
 * Interrupted writes are retained as .partial directories and never uploaded or silently purged.
 * All instances for this directory share a lock (service and uploader live in one app process).
 */
class AmbientQueue internal constructor(
    private val directory: File,
    private val maxBytes: Long,
    private val readAudio: (File) -> ByteArray,
) {
    constructor(directory: File, maxBytes: Long = DEFAULT_MAX_BYTES) : this(directory, maxBytes, { it.readBytes() })

    private val json = Json { encodeDefaults = true }
    private val lock = locks.computeIfAbsent(directory.canonicalPath) { Any() }
    private val verified = mutableMapOf<String, VerifiedBatch>()

    private data class FileStamp(val length: Long, val modified: FileTime, val key: String?)
    private data class Fingerprint(val manifest: FileStamp, val audio: FileStamp)
    private data class VerifiedBatch(val batch: QueuedBatch, val fingerprint: Fingerprint)

    init {
        require(maxBytes > 0)
        require(directory.isDirectory || directory.mkdirs()) { "Cannot open ambient queue" }
        synchronized(lock) {
            // The rename itself is a durable local ACK. Finish only these authorized deletions
            // after a death during removal; never discard unacknowledged or partial recordings.
            children().filter(::acknowledgedDirectory).forEach {
                if (!it.deleteRecursively()) throw IOException("Cannot finish acknowledged audio removal")
            }
        }
    }

    /** Reserve a whole next fragment before starting microphone capture. */
    fun ensureCapacity(reservedBytes: Long = NEXT_SEGMENT_RESERVE_BYTES): Unit = synchronized(lock) {
        require(reservedBytes >= 0)
        val current = diskBytes()
        if (reservedBytes > maxBytes || current > maxBytes - reservedBytes) throw QueueFullException()
    }

    fun enqueue(
        binding: AmbientBinding,
        sessionId: String,
        sequence: Long,
        startedAt: String,
        pcm: ByteArray,
    ): QueuedBatch = synchronized(lock) {
        val wav = AmbientWav.encode(pcm)
        enqueueAudio(binding, sessionId, sequence, startedAt, AmbientWav.validate(wav).sampleCount, AmbientAudioFormat.WAV, wav)
    }

    fun enqueueEncoded(
        binding: AmbientBinding,
        sessionId: String,
        sequence: Long,
        startedAt: String,
        sourcePcmSampleCount: Long,
        format: AmbientAudioFormat,
        encodedBytes: ByteArray,
    ): QueuedBatch = synchronized(lock) {
        require(format != AmbientAudioFormat.WAV) { "A compressed format is required" }
        enqueueAudio(binding, sessionId, sequence, startedAt, sourcePcmSampleCount, format, encodedBytes)
    }

    private fun enqueueAudio(
        binding: AmbientBinding, sessionId: String, sequence: Long, startedAt: String,
        sourceSamples: Long, format: AmbientAudioFormat, bytes: ByteArray,
    ): QueuedBatch {
        require(AmbientContract.isUuid(sessionId) && sequence >= 0) { "Invalid capture session" }
        require(sourceSamples in 1..AmbientAudioFormat.MAX_SOURCE_SAMPLES)
        val started = Instant.parse(startedAt).toString()
        val info = format.validate(bytes)
        if (format == AmbientAudioFormat.OGG_OPUS) {
            require(info.encodedSampleRate == 48_000 && info.encodedSampleCount == sourceSamples * 3) {
                "Opus duration does not match captured samples"
            }
        }
        val id = UUID.randomUUID().toString()
        val compressed = format != AmbientAudioFormat.WAV
        val batch = QueuedBatch(id, sessionId, sequence, started, digest(bytes), bytes.size.toLong(), sourceSamples, binding,
            if (compressed) 2 else 1, format,
            if (compressed) info.encodedSampleCount else null, if (compressed) info.encodedSampleRate else null)
        val serialized = json.encodeToJsonElement(batch) as JsonObject
        // Continue producing the old manifest shape for the compatibility PCM entry point.
        val manifest = (if (compressed) serialized else JsonObject(serialized.filterKeys {
            it !in setOf("format", "encoded_sample_count", "encoded_sample_rate")
        })).toString().toByteArray(Charsets.UTF_8)
        // The new binding is written after commit; reserve its temporary copy as well.
        val bindingBytes = json.encodeToString(binding).toByteArray(Charsets.UTF_8)
        ensureCapacity(bytes.size.toLong() + manifest.size + bindingBytes.size)
        val partial = File(directory, "$id.partial")
        if (!partial.mkdir()) throw IOException("Cannot begin ambient queue write")
        writeSynced(File(partial, format.fileName), bytes)
        writeSynced(File(partial, MANIFEST_NAME), manifest)
        Files.move(partial.toPath(), File(directory, id).toPath(), StandardCopyOption.ATOMIC_MOVE)
        rememberBinding(binding)
        // Both byte arrays were generated, checked and synchronously written under this lock.
        verified[id] = VerifiedBatch(batch, fingerprint(File(directory, id), batch))
        return batch
    }

    fun list(binding: AmbientBinding? = null): List<QueuedBatch> = synchronized(lock) {
        val folders = committed()
        verified.keys.retainAll(folders.map { it.name }.toSet())
        folders.map { readBatch(it) }.filter { binding == null || it.binding.matches(binding) }
            .sortedWith(compareBy<QueuedBatch> { Instant.parse(it.startedAt) }.thenBy { it.sequence }.thenBy { it.id })
    }

    fun snapshot(binding: AmbientBinding? = null): QueueSnapshot = synchronized(lock) {
        val batches = list(binding)
        QueueSnapshot(batches.size, batches.sumOf { it.byteSize }, diskBytes())
    }

    fun audioFile(batch: QueuedBatch): File = synchronized(lock) {
        require(AmbientContract.isUuid(batch.id))
        // Selected audio is rehashed even when its stat fingerprint is unchanged.
        val actual = readBatch(File(directory, batch.id), force = true)
        if (actual != batch) throw QueueCorruptException()
        File(File(directory, batch.id), batch.format.fileName)
    }

    fun wavFile(batch: QueuedBatch): File {
        require(batch.format == AmbientAudioFormat.WAV)
        return audioFile(batch)
    }

    /** False leaves the data untouched, including unknown IDs and mismatching hashes. */
    fun acknowledge(id: String, sha256: String): Boolean = synchronized(lock) {
        if (!AmbientContract.isUuid(id) || !AmbientContract.isSha256(sha256)) return@synchronized false
        val batchDirectory = File(directory, id)
        if (!batchDirectory.exists()) return@synchronized false
        val batch = readBatch(batchDirectory, force = true)
        if (batch.sha256 != sha256) return@synchronized false
        // A death during removal must never leave a half-removed committed segment.
        val acknowledged = File(directory, "$id$ACKNOWLEDGED_SUFFIX")
        Files.move(batchDirectory.toPath(), acknowledged.toPath(), StandardCopyOption.ATOMIC_MOVE)
        verified.remove(id)
        if (!acknowledged.deleteRecursively()) throw IOException("Cannot remove acknowledged ambient audio")
        true
    }

    fun rememberBinding(binding: AmbientBinding): Unit = synchronized(lock) {
        val bytes = json.encodeToString(binding).toByteArray(Charsets.UTF_8)
        ensureCapacity(bytes.size.toLong())
        val partial = File(directory, BINDING_PARTIAL)
        writeSynced(partial, bytes)
        Files.move(partial.toPath(), File(directory, BINDING_NAME).toPath(),
            StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    }

    fun storedBinding(): AmbientBinding? = synchronized(lock) {
        val file = File(directory, BINDING_NAME)
        if (!file.exists()) null else try {
            require(file.isFile && file.length() <= MAX_MANIFEST_BYTES)
            json.decodeFromString<AmbientBinding>(file.readText(Charsets.UTF_8))
        } catch (_: Exception) { throw QueueCorruptException() }
    }

    /** Explicit discard. Clearing everything also recovers partial/unknown/corrupted files. */
    fun clear(binding: AmbientBinding? = null): Unit = synchronized(lock) {
        if (binding == null) {
            children().forEach { if (!it.deleteRecursively()) throw IOException("Cannot clear ambient queue") }
            verified.clear()
        } else {
            list(binding).forEach { batch ->
                if (!File(directory, batch.id).deleteRecursively()) throw IOException("Cannot clear ambient owner queue")
                verified.remove(batch.id)
            }
            if (storedBinding()?.matches(binding) == true) {
                for (name in listOf(BINDING_NAME, BINDING_PARTIAL)) {
                    val file = File(directory, name)
                    if (file.exists() && !file.delete()) throw IOException("Cannot clear ambient owner binding")
                }
            }
        }
    }

    private fun committed(): List<File> = children().filter { entry ->
        if (entry.name.endsWith(".partial") || acknowledgedDirectory(entry) ||
            entry.name in setOf(BINDING_NAME, BINDING_PARTIAL)) false
        else {
            if (!AmbientContract.isUuid(entry.name) || !entry.isDirectory) throw QueueCorruptException()
            true
        }
    }

    private fun acknowledgedDirectory(entry: File): Boolean = entry.name.endsWith(ACKNOWLEDGED_SUFFIX) &&
        AmbientContract.isUuid(entry.name.removeSuffix(ACKNOWLEDGED_SUFFIX)) && entry.isDirectory &&
        !Files.isSymbolicLink(entry.toPath())

    /**
     * Committed files are immutable. Status scans use bounded codec probes and file stats.
     * Upload/deletion always reread the manifest, full payload hash and container structure.
     */
    private fun readBatch(folder: File, force: Boolean = false): QueuedBatch = try {
        require(folder.isDirectory && AmbientContract.isUuid(folder.name))
        val manifest = File(folder, MANIFEST_NAME)
        val manifestStamp = fileStamp(manifest, MAX_MANIFEST_BYTES)
        val cached = verified[folder.name]
        val batch = if (!force && cached?.fingerprint?.manifest == manifestStamp) cached.batch
            else json.decodeFromString<QueuedBatch>(manifest.readText(Charsets.UTF_8))
        require(batch.id == folder.name && AmbientContract.isUuid(batch.sessionId) && batch.sequence >= 0)
        require(AmbientContract.isSha256(batch.sha256) && Instant.parse(batch.startedAt).toString() == batch.startedAt)
        require(batch.sampleCount in 1..AmbientAudioFormat.MAX_SOURCE_SAMPLES)
        require((batch.version == 1 && batch.format == AmbientAudioFormat.WAV && batch.encodedSampleCount == null && batch.encodedSampleRate == null) ||
            (batch.version == 2 && batch.format != AmbientAudioFormat.WAV && batch.encodedSampleCount != null && batch.encodedSampleRate != null))
        if (batch.format == AmbientAudioFormat.OGG_OPUS) {
            require(batch.encodedSampleRate == 48_000 && batch.encodedSampleCount == batch.sampleCount * 3)
        }
        val audio = File(folder, batch.format.fileName)
        val stamp = Fingerprint(manifestStamp, fileStamp(audio, batch.format.maximumBytes.toLong()))
        require(stamp.audio.length == batch.byteSize)
        if (!force && cached?.fingerprint == stamp) cached.batch else {
            val info = if (force) {
                val bytes = readAudio(audio)
                require(digest(bytes) == batch.sha256)
                batch.format.validate(bytes)
            } else batch.format.probe(audio)
            if (batch.version == 1) require(info.encodedSampleCount == batch.sampleCount)
            else require(info.encodedSampleCount == batch.encodedSampleCount && info.encodedSampleRate == batch.encodedSampleRate)
            require(fingerprint(folder, batch) == stamp)
            verified[folder.name] = VerifiedBatch(batch, stamp)
            batch
        }
    } catch (_: Exception) {
        verified.remove(folder.name)
        throw QueueCorruptException()
    }

    private fun fingerprint(folder: File, batch: QueuedBatch): Fingerprint = Fingerprint(
        fileStamp(File(folder, MANIFEST_NAME), MAX_MANIFEST_BYTES),
        fileStamp(File(folder, batch.format.fileName), batch.format.maximumBytes.toLong()),
    )

    private fun fileStamp(file: File, maximum: Long): FileStamp {
        val attributes = Files.readAttributes(file.toPath(), BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
        require(attributes.isRegularFile && attributes.size() in 1..maximum)
        return FileStamp(attributes.size(), attributes.lastModifiedTime(), attributes.fileKey()?.toString())
    }

    private fun children(): List<File> = directory.listFiles()?.toList() ?: throw IOException("Cannot read ambient queue")

    private fun diskBytes(): Long = directory.walkTopDown().filter { it.isFile }.sumOf { it.length() }

    private fun writeSynced(file: File, bytes: ByteArray) {
        FileOutputStream(file).use { stream -> stream.write(bytes); stream.fd.sync() }
    }

    companion object {
        const val DEFAULT_MAX_BYTES = 512L * 1_024 * 1_024
        const val NEXT_SEGMENT_RESERVE_BYTES = 1_024L * 1_024
        private const val MANIFEST_NAME = "manifest.json"
        private const val BINDING_NAME = "binding.json"
        private const val BINDING_PARTIAL = "binding.json.partial"
        private const val ACKNOWLEDGED_SUFFIX = ".acknowledged"
        private const val MAX_MANIFEST_BYTES = 16_384L
        private val locks = ConcurrentHashMap<String, Any>()

        private fun digest(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
            .digest(bytes).joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }
}
