package assistant.mobile.android.body.assets

import android.content.Context
import android.util.AtomicFile
import assistant.mobile.core.NativeHttpClient
import assistant.mobile.core.SessionManager
import assistant.mobile.core.bodyassets.BodyAsset
import assistant.mobile.core.bodyassets.BodyAssetError
import assistant.mobile.core.bodyassets.BodyAssetFailure
import assistant.mobile.core.bodyassets.BodyAssetLoader
import assistant.mobile.core.bodyassets.BodyAssetManifest
import assistant.mobile.core.bodyassets.BodyAssetProgress
import assistant.mobile.core.bodyassets.BodyAssetSink
import assistant.mobile.core.bodyassets.BodyAssetStore
import assistant.mobile.core.bodyassets.BodyAssetVerification
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

data class BodyAssetFiles(val manifest: BodyAssetManifest, val files: Map<String, File>) {
    operator fun get(name: String): File? = files[name]
    fun require(name: String): File = files[name] ?: throw BodyAssetFailure(BodyAssetError.InvalidManifest)
}

/** One repository per native runtime: repeat screen entry reads private files without HTTP. */
class AndroidBodyAssetRepository(
    context: Context,
    http: NativeHttpClient,
    sessions: SessionManager,
    cacheDirectory: File = File(context.cacheDir, "body-atlas/${sha256(http.origin.encodeToByteArray())}"),
) {
    private val lock = Mutex()
    private val store = AndroidBodyAssetStore(cacheDirectory)
    private val loader = BodyAssetLoader(http, sessions, store)
    private var checkedManifest = false

    /** Check the current web build once; a complete verified cache remains usable offline. */
    suspend fun load(names: Set<String>, onProgress: (BodyAssetProgress) -> Unit = {}): BodyAssetFiles = withContext(Dispatchers.IO) {
        lock.withLock {
            files(loader.load(names, revalidateManifest = !checkedManifest, onProgress = onProgress), names).also { checkedManifest = true }
        }
    }

    suspend fun refresh(names: Set<String>, onProgress: (BodyAssetProgress) -> Unit = {}): BodyAssetFiles = withContext(Dispatchers.IO) {
        lock.withLock {
            files(loader.load(names, revalidateManifest = true, onProgress = onProgress), names).also { checkedManifest = true }
        }
    }

    private fun files(manifest: BodyAssetManifest, names: Set<String>): BodyAssetFiles =
        BodyAssetFiles(manifest, names.associateWith { store.file(manifest.files.getValue(it).sha256) })
}

/** Called on the repository's IO dispatcher; content hashes are the private cache's keys. */
private class AndroidBodyAssetStore(private val directory: File) : BodyAssetStore {
    private val manifestFile get() = AtomicFile(File(directory, "manifest.json"))

    override suspend fun readManifest(): ByteArray? {
        ensureDirectory()
        val atomic = manifestFile
        return if (!atomic.baseFile.isFile && !File(directory, "manifest.json.bak").isFile) null else atomic.openRead().use { stream ->
            // Keep a corrupt disk manifest from making an unbounded allocation before parsing.
            if (manifestFile.baseFile.length() > 262_144) null else stream.readBytes()
        }
    }

    override suspend fun writeManifest(bytes: ByteArray) {
        ensureDirectory()
        val atomic = manifestFile
        val stream = atomic.startWrite()
        try {
            stream.write(bytes)
            atomic.finishWrite(stream)
        } catch (error: Exception) {
            atomic.failWrite(stream)
            throw error
        }
    }

    override suspend fun contains(asset: BodyAsset): Boolean {
        val cached = file(asset.sha256)
        if (!cached.isFile) return false
        val valid = cached.length() == asset.bytes && cached.inputStream().use { stream ->
            val digest = MessageDigest.getInstance("SHA-256")
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val count = stream.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
            digest.digest().hex() == asset.sha256
        }
        if (!valid && !cached.delete()) throw BodyAssetFailure(BodyAssetError.Storage)
        return valid
    }

    override suspend fun begin(asset: BodyAsset): BodyAssetSink {
        ensureDirectory()
        val target = file(asset.sha256)
        val partial = File(directory, "${asset.sha256}.part")
        if (partial.length() > asset.bytes && !partial.delete()) throw BodyAssetFailure(BodyAssetError.Storage)
        return AndroidBodyAssetSink(partial, target)
    }

    override suspend fun retain(hashes: Set<String>) {
        ensureDirectory()
        val keep = hashes + hashes.map { "$it.part" } + setOf("manifest.json", "manifest.json.bak", "manifest.json.new")
        for (candidate in directory.listFiles().orEmpty()) {
            if (candidate.name !in keep && !candidate.delete()) throw BodyAssetFailure(BodyAssetError.Storage)
        }
    }

    fun file(hash: String): File {
        if (hash.length != 64 || hash.any { it !in "0123456789abcdef" }) throw BodyAssetFailure(BodyAssetError.InvalidManifest)
        return File(directory, hash)
    }

    private fun ensureDirectory() {
        if (!directory.isDirectory && !directory.mkdirs()) throw BodyAssetFailure(BodyAssetError.Storage)
    }
}

private class AndroidBodyAssetSink(private val temporary: File, private val target: File) : BodyAssetSink {
    private val digest = MessageDigest.getInstance("SHA-256")
    override var bytes = temporary.length()
        private set
    private var verification: BodyAssetVerification? = null
    private var output: FileOutputStream? = null

    init {
        if (temporary.isFile) temporary.inputStream().use { stream ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val count = stream.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        output = FileOutputStream(temporary, true)
    }

    override suspend fun write(bytes: ByteArray, count: Int) {
        val stream = output ?: throw BodyAssetFailure(BodyAssetError.Storage)
        stream.write(bytes, 0, count)
        digest.update(bytes, 0, count)
        this.bytes += count
    }

    override suspend fun reset() {
        discard()
        digest.reset()
        bytes = 0
        verification = null
        output = FileOutputStream(temporary)
    }

    override suspend fun finish(): BodyAssetVerification {
        return verification ?: run {
            val stream = output ?: throw BodyAssetFailure(BodyAssetError.Storage)
            stream.fd.sync()
            stream.close()
            output = null
            BodyAssetVerification(bytes, digest.digest().hex()).also { verification = it }
        }
    }

    override suspend fun commit() {
        if (verification == null) throw BodyAssetFailure(BodyAssetError.Integrity)
        if (target.exists() && !target.delete()) throw BodyAssetFailure(BodyAssetError.Storage)
        if (!temporary.renameTo(target)) throw BodyAssetFailure(BodyAssetError.Storage)
    }

    override suspend fun close() {
        try { output?.close() } finally { output = null }
    }

    override suspend fun discard() {
        close()
        if (temporary.exists() && !temporary.delete()) throw BodyAssetFailure(BodyAssetError.Storage)
    }
}

private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).hex()
private fun ByteArray.hex(): String = joinToString("") { "%02x".format(it.toInt() and 255) }
