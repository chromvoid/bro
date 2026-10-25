package assistant.mobile.android.whoop

import android.content.Context
import android.util.AtomicFile
import java.io.File
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.json.*

/** The durable queue is independent of Bluetooth, foreground sessions and worker lifetimes. */
internal interface WhoopStorePort {
    fun forgetPending(): Boolean
    fun markForgetPending()
    fun get(key: String): JsonElement?
    fun put(key: String, value: JsonElement)
    fun count(): Int
    fun bytes(): Long
    fun full(): Boolean
    fun append(frames: List<ByteArray>): Boolean
    fun batch(cursor: JsonElement, deviceState: JsonObject?): JsonObject?
    fun rebase(batch: JsonObject, cursor: JsonElement): JsonObject
    fun acknowledge(batch: JsonObject)
    fun closeBinding(reason: String): JsonObject?
    fun clear()
    fun clearForServerSwitch()
}

internal class AndroidWhoopStore(context: Context, origin: String) : WhoopStorePort {
    private val store = WhoopStore(context, origin)
    override fun forgetPending() = store.forgetPending()
    override fun markForgetPending() = store.markForgetPending()
    override fun get(key: String) = store.get(key)
    override fun put(key: String, value: JsonElement) = store.put(key, value)
    override fun count() = store.count()
    override fun bytes() = store.bytes()
    override fun full() = store.full()
    override fun append(frames: List<ByteArray>) = store.append(frames)
    override fun batch(cursor: JsonElement, deviceState: JsonObject?) = store.batch(cursor, deviceState)
    override fun rebase(batch: JsonObject, cursor: JsonElement) = store.rebase(batch, cursor)
    override fun acknowledge(batch: JsonObject) = store.acknowledge(batch)
    override fun closeBinding(reason: String) = store.closeBinding(reason)
    override fun clear() = store.clear()
    override fun clearForServerSwitch() = store.clearForServerSwitch()
}

internal interface WhoopWorkScheduler {
    fun schedule(collector: String)
    fun continueHistory(collector: String)
    fun upload(collector: String)
    fun cancelCollect(collector: String)
    fun cancelAll()
    suspend fun cancelAllForServerSwitch() { cancelAll() }
    fun revoke()
}

internal interface WhoopRevokeQueue {
    fun add(grant: WhoopGrant)
    fun all(): List<WhoopGrant>
    fun remove(grant: WhoopGrant)
}

/** Required revocation survives local queue deletion, process death and a subsequent login. */
internal class AndroidWhoopRevokeQueue(context: Context, private val origin: String) : WhoopRevokeQueue {
    private val binding = sha256(origin)
    private val directory = File(context.applicationContext.noBackupFilesDir, "native-whoop")
    private val file = AtomicFile(File(directory, "revoke-$binding"))
    private val crypto = WhoopCrypto("assistant.native.whoop.revoke.$binding")
    private val lock = queueLock(binding)

    override fun all(): List<WhoopGrant> = synchronized(lock) {
        if (!file.baseFile.exists() && !File(file.baseFile.path + ".bak").exists()) return@synchronized emptyList()
        val raw = crypto.open(file.readFully(), binding)
        val record = whoopJson.parseToJsonElement(raw.toString(Charsets.UTF_8))
        // Reading the old home queue upgrades it before a switch can orphan its origin.
        val grants = if (record is JsonArray) record else {
            require(record.jsonObject.string("origin") == origin)
            record.jsonObject["grants"]!!.jsonArray
        }
        grants.map { WhoopGrant(it.jsonObject) }.also { if (record is JsonArray) write(it) }
    }
    override fun add(grant: WhoopGrant) = synchronized(lock) { write(all().filterNot { it.identity == grant.identity } + grant) }
    override fun remove(grant: WhoopGrant) = synchronized(lock) { write(all().filterNot { it.identity == grant.identity }) }
    private fun write(grants: List<WhoopGrant>) {
        if (grants.isEmpty()) { file.delete(); crypto.delete(); return }
        check(directory.isDirectory || directory.mkdirs())
        val record = obj { put("origin", origin); put("grants", JsonArray(grants.map { it.value })) }
        val bytes = crypto.seal(record.toString().toByteArray(Charsets.UTF_8), binding)
        val output = file.startWrite()
        try { output.write(bytes); file.finishWrite(output) }
        catch (error: Throwable) { file.failWrite(output); throw error }
    }

    companion object {
        private val locks = mutableMapOf<String, Any>()
        private fun queueLock(binding: String): Any = synchronized(locks) { locks.getOrPut(binding) { Any() } }

        fun origins(context: Context): List<String> {
            val directory = File(context.applicationContext.noBackupFilesDir, "native-whoop")
            return directory.listFiles().orEmpty().map { it.name.removeSuffix(".bak") }.distinct()
                .filter { it.startsWith("revoke-") }.mapNotNull { name ->
                    val binding = name.removePrefix("revoke-")
                    if (binding.length != 64 || binding.any { it !in "0123456789abcdef" }) return@mapNotNull null
                    synchronized(queueLock(binding)) {
                        val file = AtomicFile(File(directory, name))
                        if (!file.baseFile.exists() && !File(file.baseFile.path + ".bak").exists()) return@synchronized null
                        val raw = WhoopCrypto("assistant.native.whoop.revoke.$binding").open(file.readFully(), binding)
                        val record = whoopJson.parseToJsonElement(raw.toString(Charsets.UTF_8)) as? JsonObject
                            ?: return@synchronized null
                        requireNotNull(record.string("origin")).also { require(sha256(it) == binding) }
                    }
                }
        }
    }
}

/** Queues retain their home namespaces; verified default-origin moves resolve their server addresses. */
internal suspend fun drainWhoopRevocations(
    origins: Iterable<String>,
    queue: (String) -> WhoopRevokeQueue,
    serverOrigin: (String) -> String,
): Boolean {
    var complete = true
    for (origin in origins.distinct()) {
        try {
            val pending = queue(origin)
            val endpoint = NativeWhoopHttp(origin) { serverOrigin(origin) }
            for (grant in pending.all()) {
                kotlinx.coroutines.currentCoroutineContext().ensureActive()
                try { endpoint.revoke(grant); pending.remove(grant) }
                catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                catch (_: Exception) { complete = false }
            }
        } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
        catch (_: Exception) { complete = false }
    }
    return complete
}
