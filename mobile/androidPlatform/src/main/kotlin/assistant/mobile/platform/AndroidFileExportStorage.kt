package assistant.mobile.platform

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import assistant.mobile.core.files.ExportFile
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

interface AndroidExportStorage {
    val generation: Long
    fun stage(file: ExportFile, expectedGeneration: Long): File?
    fun save(source: File, destination: Uri, expectedGeneration: Long): Boolean
    fun release(file: File)
    fun retainForShare(file: File, expectedGeneration: Long)
    fun clear()
}

/** The only export staging area, outside shared storage and backups. */
class AndroidFileExportStorage(context: Context) : AndroidExportStorage {
    private val context = context.applicationContext
    private val directory = File(this.context.cacheDir, DIRECTORY)
    private val handler = Handler(Looper.getMainLooper())
    private val scheduled = ConcurrentHashMap<File, Runnable>()
    private val gate = Any()
    private var currentGeneration = 0L

    init { prune() }

    override val generation: Long get() = synchronized(gate) { currentGeneration }

    override fun stage(file: ExportFile, expectedGeneration: Long): File? {
        // Reserve the directory atomically with the account generation. Writing outside this
        // lock may finish on an unlinked descriptor after clear(), but can never recreate it.
        val parent = synchronized(gate) {
            if (expectedGeneration != currentGeneration) return null
            prune()
            File(directory, UUID.randomUUID().toString()).also {
                check(it.mkdirs()) { "Export storage is unavailable" }
            }
        }
        val target = File(parent, file.name)
        try { target.outputStream().use { it.write(file.bytes) } }
        catch (error: Throwable) {
            synchronized(gate) {
                parent.deleteRecursively()
                if (expectedGeneration != currentGeneration) return null
            }
            throw error
        }
        return synchronized(gate) {
            if (expectedGeneration == currentGeneration) target
            else { parent.deleteRecursively(); null }
        }
    }

    override fun save(source: File, destination: Uri, expectedGeneration: Long): Boolean {
        if (generation != expectedGeneration) return false
        try {
            val output = context.contentResolver.openOutputStream(destination, "w")
                ?: error("Export destination is unavailable")
            output.use {
                source.inputStream().use { input ->
                    val buffer = ByteArray(16 * 1024)
                    while (true) {
                        if (generation != expectedGeneration) return false
                        val read = input.read(buffer)
                        if (read < 0) break
                        it.write(buffer, 0, read)
                    }
                }
            }
            return generation == expectedGeneration
        } catch (error: Exception) {
            if (generation != expectedGeneration) return false
            throw error
        }
    }

    override fun release(file: File) = synchronized(gate) {
        scheduled.remove(file)?.let(handler::removeCallbacks)
        file.parentFile?.takeIf { it.parentFile == directory }?.deleteRecursively()
        Unit
    }

    /** A chooser result does not mean the receiving app has opened its URI yet. */
    override fun retainForShare(file: File, expectedGeneration: Long) = synchronized(gate) {
        if (expectedGeneration != currentGeneration || !file.exists()) { release(file); return@synchronized }
        val cleanup = Runnable { release(file) }
        scheduled.put(file, cleanup)?.let(handler::removeCallbacks)
        handler.postDelayed(cleanup, KEEP_MS)
        Unit
    }

    override fun clear() = synchronized(gate) {
        currentGeneration++
        scheduled.values.forEach(handler::removeCallbacks)
        scheduled.clear()
        directory.deleteRecursively()
        Unit
    }

    private fun prune() {
        val before = System.currentTimeMillis() - KEEP_MS
        directory.listFiles()?.filter { it.lastModified() < before }?.forEach { it.deleteRecursively() }
    }

    companion object {
        const val DIRECTORY = "file-exports"
        private const val KEEP_MS = 60 * 60 * 1000L
    }
}
