package assistant.mobile.android.ambient

import assistant.mobile.core.AccountNamespace
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Credential-free proof of the native login that owns the current queue, independent of its live lease. */
@Serializable
internal data class AmbientNativeOwnership(val namespace: AccountNamespace, val binding: AmbientBinding) {
    init { require(namespace.origin == binding.origin && namespace.userId == binding.userId) }

    fun permitsCleanup(account: AccountNamespace, queuedOwner: AmbientBinding): Boolean =
        namespace == account && binding.matches(queuedOwner)
}

/** The proof survives process death and lease invalidation; the old shell never created this file. */
internal class AmbientNativeOwnershipStore(private val file: File) {
    fun load(): AmbientNativeOwnership? = if (file.exists())
        Json.decodeFromString(AmbientNativeOwnership.serializer(), file.readText()) else null

    fun save(owner: AmbientNativeOwnership) {
        val directory = requireNotNull(file.parentFile)
        check(directory.isDirectory || directory.mkdirs())
        val temporary = File(directory, file.name + ".new")
        FileOutputStream(temporary).use {
            it.write(Json.encodeToString(AmbientNativeOwnership.serializer(), owner).toByteArray())
            it.fd.sync()
        }
        Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    }

    fun clear() {
        Files.deleteIfExists(file.toPath())
        Files.deleteIfExists(File(file.parentFile, file.name + ".new").toPath())
    }
}
