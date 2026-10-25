package assistant.mobile.platform

import android.content.Context
import android.util.AtomicFile
import assistant.mobile.core.AccountNamespace
import assistant.mobile.core.NativeJson
import assistant.mobile.core.assistant.AssistantHistory
import assistant.mobile.core.assistant.AssistantHistoryCache
import assistant.mobile.core.assistant.decodeAssistantHistory
import assistant.mobile.core.assistant.encodeAssistantHistory
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString

/** Disposable, account-isolated last-page cache. Never stores activity previews or credentials. */
class FileAssistantHistoryCache(context: Context) : AssistantHistoryCache {
    private val directory = File(context.applicationContext.noBackupFilesDir, "assistant-history")
    private val gate = Mutex()

    override suspend fun read(namespace: AccountNamespace): AssistantHistory? = withContext(Dispatchers.IO) {
        gate.withLock {
            val file = AtomicFile(path(namespace))
            try {
                file.openRead().use { input ->
                    val output = java.io.ByteArrayOutputStream()
                    val buffer = ByteArray(8_192)
                    while (output.size() <= MAX_BYTES) {
                        val count = input.read(buffer, 0, minOf(buffer.size, MAX_BYTES + 1 - output.size()))
                        if (count < 0) break
                        output.write(buffer, 0, count)
                    }
                    val bytes = output.toByteArray()
                    if (bytes.size > MAX_BYTES) null else decodeAssistantHistory(bytes)
                }
            } catch (_: java.io.IOException) {
                null
            } catch (_: IllegalArgumentException) {
                file.delete()
                null
            }
        }
    }

    override suspend fun write(namespace: AccountNamespace, page: AssistantHistory): Unit = withContext(Dispatchers.IO) {
        val bytes = encodeAssistantHistory(page)
        gate.withLock {
            val file = AtomicFile(path(namespace))
            if (bytes.size > MAX_BYTES) {
                file.delete()
                return@withLock
            }
            check(directory.isDirectory || directory.mkdirs()) { "History cache is unavailable" }
            val output = file.startWrite()
            try {
                output.write(bytes)
                file.finishWrite(output)
            } catch (failure: Exception) {
                file.failWrite(output)
                throw failure
            }
        }
    }

    override suspend fun clear(namespace: AccountNamespace): Unit = withContext(Dispatchers.IO) {
        gate.withLock {
            val file = path(namespace)
            AtomicFile(file).delete()
            check(!file.exists() && !File(file.path + ".bak").exists() && !File(file.path + ".new").exists()) {
                "History cache could not be cleared"
            }
        }
    }

    /** Legacy cache names hash the account; the single active server discards the whole cache. */
    suspend fun clearForServerSwitch(): Unit = withContext(Dispatchers.IO) {
        gate.withLock {
            check(!directory.exists() || directory.deleteRecursively()) { "History cache could not be cleared" }
        }
    }

    private fun path(namespace: AccountNamespace): File {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(NativeJson.encodeToString(namespace).toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
        return File(directory, "$digest.json")
    }

    private companion object {
        const val MAX_BYTES = 4 * 1024 * 1024
    }
}
