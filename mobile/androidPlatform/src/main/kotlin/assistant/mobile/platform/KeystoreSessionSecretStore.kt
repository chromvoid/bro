package assistant.mobile.platform

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import assistant.mobile.core.SessionSecretStore
import assistant.mobile.core.StoredSession
import java.io.File
import java.security.KeyStore
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

/** Private ciphertext, excluded from backup, authenticated to its installation and origin. */
class KeystoreSessionSecretStore(context: Context) : SessionSecretStore {
    private val directory = File(context.applicationContext.noBackupFilesDir, "native-sessions")
    private val json = Json { ignoreUnknownKeys = true }

    /** Selection at process startup must not decrypt or create a Keystore entry. */
    fun exists(instance: String, origin: String): Boolean =
        exists(AtomicFile(File(directory, storageId(instance, origin))))

    override suspend fun load(instance: String, origin: String): StoredSession? = io {
        val id = storageId(instance, origin)
        val file = AtomicFile(File(directory, id))
        if (!exists(file)) return@io null
        val bytes = file.readFully()
        check(bytes.size > 29 && bytes[0] == 1.toByte()) { "Invalid session storage" }
        val key = keyStore().getKey(alias(id), null) as? SecretKey
            ?: error("Session key is unavailable")
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, bytes.copyOfRange(1, 13)))
        cipher.updateAAD(binding(instance, origin))
        val plaintext = cipher.doFinal(bytes.copyOfRange(13, bytes.size))
        try {
            json.decodeFromString(StoredSession.serializer(), plaintext.decodeToString()).also {
                check(it.namespace.instance == instance && it.namespace.origin == origin) {
                    "Session namespace mismatch"
                }
            }
        } finally {
            plaintext.fill(0)
        }
    }

    override suspend fun save(session: StoredSession) = io {
        check(directory.isDirectory || directory.mkdirs()) { "Session storage is unavailable" }
        val namespace = session.namespace
        val id = storageId(namespace.instance, namespace.origin)
        val store = keyStore()
        val key = store.getKey(alias(id), null) as? SecretKey ?: KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore",
        ).apply {
            init(
                KeyGenParameterSpec.Builder(
                    alias(id), KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                ).setKeySize(256)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setRandomizedEncryptionRequired(true)
                    .build(),
            )
        }.generateKey()
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key)
        cipher.updateAAD(binding(namespace.instance, namespace.origin))
        val plaintext = json.encodeToString(StoredSession.serializer(), session).encodeToByteArray()
        val ciphertext = try {
            byteArrayOf(1) + cipher.iv + cipher.doFinal(plaintext)
        } finally {
            plaintext.fill(0)
        }
        val file = AtomicFile(File(directory, id))
        val output = file.startWrite()
        try {
            output.write(ciphertext)
            file.finishWrite(output)
        } catch (failure: Throwable) {
            file.failWrite(output)
            throw failure
        }
    }

    override suspend fun delete(instance: String, origin: String) = io {
        val id = storageId(instance, origin)
        // Removing the key also makes remnants of a failed file deletion unreadable.
        keyStore().deleteEntry(alias(id))
        val file = AtomicFile(File(directory, id))
        file.delete()
        check(!exists(file)) { "Cannot erase stored session" }
    }

    private fun binding(instance: String, origin: String) = "$instance\u0000$origin".encodeToByteArray()

    private fun storageId(instance: String, origin: String): String =
        MessageDigest.getInstance("SHA-256").digest(binding(instance, origin))
            .joinToString("") { "%02x".format(it) }

    private fun alias(id: String) = "assistant.native.session.$id"

    private fun exists(file: AtomicFile): Boolean =
        file.baseFile.exists() || File(file.baseFile.path + ".bak").exists()

    private fun keyStore() = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    private suspend fun <T> io(block: () -> T): T = withContext(Dispatchers.IO) {
        // AtomicFile does not itself serialize readers/writers, including separate adapters.
        storageMutex.withLock { block() }
    }

    private companion object {
        val storageMutex = Mutex()
    }
}
