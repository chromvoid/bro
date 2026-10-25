package assistant.mobile.platform.ring

import assistant.mobile.platform.R

import android.content.Context
import android.os.SystemClock
import android.util.AtomicFile
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlinx.serialization.json.*

/** Commit-before-radio journal. Physical uncertainty survives changes of grant and owner. */
class RingDeviceStore(context: Context, private val origin: String) {
    init { RingText.initialize(context) }
    private data class Verified(val identity: JsonObject, val token: String?, val at: Long)
    private data class Entry(val bindingKey: String, val key: String, val record: JsonObject,
        val recovery: JsonObject, val domain: String, val id: String, val command: JsonObject, val fence: JsonObject?)
    companion object {
        private val locks = ConcurrentHashMap<String, ReentrantLock>()
        // Shared by the Repository and presentation Store instances, never persisted as proof.
        private val verified = ConcurrentHashMap<String, MutableMap<String, Verified>>()
    }
    private val originKey = sha256(origin)
    private val lock = locks.computeIfAbsent(originKey) { ReentrantLock() }
    private val identities = verified.computeIfAbsent(originKey) { linkedMapOf() }
    private val directory = context.noBackupFilesDir
    private val archivePrefix = "ring-device-receipts-${originKey.take(24)}-"
    private val file = AtomicFile(File(directory, "ring-device-${originKey.take(24)}.bin"))
    private val crypto = RingCrypto("assistant.ring.device.v1.${originKey.take(24)}")
    private val aad = "ring-device-v1\n$origin"
    private fun physical(binding: RingWorkoutBinding) = sha256("$origin\n${binding.address.uppercase()}")
    private fun person(binding: RingWorkoutBinding) = sha256("$origin\n${binding.userId}")
    private fun nativeIdentity(value: JsonObject): Boolean = value.keys == setOf("model", "hardware", "firmware") &&
        value.values.all { it is JsonPrimitive && it.isString && it.content.isNotBlank() && it.content.length <= 256 && '\u0000' !in it.content }
    private fun archive(bindingKey: String, id: String, domain: String = "device") = AtomicFile(File(directory,
        "$archivePrefix${if (domain == "device") "" else "control-"}${sha256(bindingKey).take(24)}-${sha256(id).take(2)}.bin"))
    private fun archiveAad(bindingKey: String, id: String, domain: String) =
        "$aad\n${if (domain == "device") "completed" else "control-completed"}\n$bindingKey\n${sha256(id).take(2)}"
    private fun loadArchive(bindingKey: String, id: String, domain: String = "device"): JsonObject {
        val shard = archive(bindingKey, id, domain)
        if (!shard.baseFile.exists() && !File(shard.baseFile.path + ".bak").exists()) return obj {}
        try {
            val bytes = shard.readFully(); require(bytes.size <= 512 * 1024)
            return ringJson.parseToJsonElement(crypto.open(bytes, archiveAad(bindingKey, id, domain)).toString(Charsets.UTF_8)) as JsonObject
        } catch (_: Exception) { throw RingFailure("device_store_unavailable", RingText.text(R.string.ring_native_98f0aecab6a1)) }
    }
    private fun saveArchive(bindingKey: String, id: String, domain: String, result: JsonObject) {
        val previous = loadArchive(bindingKey, id, domain)
        (previous[id] as? JsonObject)?.let { if (it != result) throw RingFailure("operation_id_conflict", RingText.text(R.string.ring_native_dab4e6df4358)) }
        val bytes = crypto.seal(JsonObject(previous + (id to result)).toString().toByteArray(Charsets.UTF_8), archiveAad(bindingKey, id, domain))
        if (bytes.size > 512 * 1024) throw RingFailure("operation_archive_capacity", RingText.text(R.string.ring_native_13aa9168519a))
        val shard = archive(bindingKey, id, domain); val output = shard.startWrite()
        try { output.write(bytes); shard.finishWrite(output) } catch (failure: Exception) { shard.failWrite(output); throw failure }
    }
    private fun load(): JsonObject {
        if (!file.baseFile.exists() && !File(file.baseFile.path + ".bak").exists()) return obj {}
        try {
            val bytes = file.readFully(); require(bytes.size <= 512 * 1024)
            return ringJson.parseToJsonElement(crypto.open(bytes, aad).toString(Charsets.UTF_8)) as JsonObject
        } catch (_: Exception) { throw RingFailure("device_store_unavailable", RingText.text(R.string.ring_native_550cd5762347)) }
    }
    private fun save(value: JsonObject) {
        val bytes = crypto.seal(value.toString().toByteArray(Charsets.UTF_8), aad)
        if (bytes.size > 512 * 1024) throw RingFailure("operation_capacity", RingText.text(R.string.ring_native_91df80317a2b))
        val output = file.startWrite()
        try { output.write(bytes); file.finishWrite(output) } catch (failure: Exception) { file.failWrite(output); throw failure }
    }
    private fun entries(all: JsonObject): List<Entry> = all.flatMap { (bindingKey, value) ->
        val records = value as? JsonObject ?: throw RingFailure("device_store_unavailable", RingText.text(R.string.ring_native_5b877d83b16e))
        records.mapNotNull { (key, item) ->
            val record = item as? JsonObject ?: throw RingFailure("device_store_unavailable", RingText.text(R.string.ring_native_fe4065519fc0))
            val recovery = record["recovery"] as? JsonObject ?: return@mapNotNull null
            val domain = record.string("domain") ?: "device"
            if (domain !in setOf("device", "control")) throw RingFailure("device_store_unavailable", RingText.text(R.string.ring_native_24c6d6640f64))
            val request = (if (domain == "device") recovery["request"] as? JsonObject else recovery)
                ?: throw RingFailure("device_store_unavailable", RingText.text(R.string.ring_native_fda80750756a))
            val id = request.string("operation_id")?.takeIf { it.isNotBlank() && it.length <= 128 }
                ?: throw RingFailure("device_store_unavailable", RingText.text(R.string.ring_native_32c0225a3d0a))
            val command = request["command"] as? JsonObject ?: throw RingFailure("device_store_unavailable", RingText.text(R.string.ring_native_11c20e100064))
            val identity = recovery["identity"] as? JsonObject
            if (identity == null || !nativeIdentity(identity) || command.string("type").isNullOrBlank() ||
                domain == "device" && recovery.bool("mutation_started") != true)
                throw RingFailure("device_store_unavailable", RingText.text(R.string.ring_native_10db9a2118c7))
            val fence = record["physical_fence"] as? JsonObject
            if (fence != null && (fence.keys != setOf("physical_id", "owner_id") || fence.values.any {
                    it !is JsonPrimitive || !it.isString || it.content.length != 64 || it.content.any { ch -> ch !in '0'..'9' && ch !in 'a'..'f' } }))
                throw RingFailure("device_store_unavailable", RingText.text(R.string.ring_native_d1c5ff141ab7))
            Entry(bindingKey, key, record, recovery, domain, id, command, fence)
        }
    }
    private fun completed(entry: Entry): JsonObject? = (loadArchive(entry.bindingKey, entry.id, entry.domain)[entry.id] as? JsonObject)
        ?.takeIf { it.string("status") == "verified" }?.also {
            if (it["command"] != entry.command) throw RingFailure("operation_id_conflict", RingText.text(R.string.ring_native_a3b9d8a581ff)) }
    private fun unresolved(all: JsonObject) = entries(all).filter { completed(it) == null }
    private fun remembered(binding: RingWorkoutBinding): Verified? = identities[binding.identity]?.takeIf {
        it.token != null || SystemClock.elapsedRealtime() - it.at in 0..300_000 }
    private fun authorized(binding: RingWorkoutBinding, entry: Entry): Boolean {
        if (entry.bindingKey == binding.identity) return true
        val fence = entry.fence ?: return false
        return fence.string("physical_id") == physical(binding) && fence.string("owner_id") == person(binding) &&
            remembered(binding)?.identity == entry.recovery["identity"]
    }
    private fun affected(binding: RingWorkoutBinding, entry: Entry) = entry.fence == null || entry.fence.string("physical_id") == physical(binding)
    private fun source(binding: RingWorkoutBinding, token: String, identity: JsonObject) {
        val current = identities[binding.identity]
        if (binding.origin != origin || token.isBlank() || current == null || current.token != token || current.identity != identity)
            throw RingFailure("identity_not_verified", RingText.text(R.string.ring_native_d65ea7401831))
    }
    /** Only a native GATT owner may call this after both current-connection reads. */
    fun verifiedIdentity(binding: RingWorkoutBinding, identity: JsonObject, token: String) = lock.withLock {
        if (binding.origin != origin || !nativeIdentity(identity) || token.isBlank() || token.length > 128)
            throw RingFailure("identity_not_verified", RingText.text(R.string.ring_native_9a25352d8dbc))
        identities[binding.identity] = Verified(identity, token, SystemClock.elapsedRealtime())
        while (identities.size > 32) identities.remove(identities.keys.first())
        sealBinding(binding)
    }
    private fun sealBinding(binding: RingWorkoutBinding) {
        if (binding.origin != origin) throw RingFailure("invalid_binding", RingText.text(R.string.ring_native_a8fd52432fef))
        val all = load(); entries(all) // Validate before attaching metadata; uncertainty cannot disappear.
        val own = all[binding.identity] as? JsonObject ?: return
        val fence = obj { put("physical_id", physical(binding)); put("owner_id", person(binding)) }
        val sealed = own.mapValues { (_, value) ->
            val record = value as JsonObject
            if (record["recovery"] is JsonObject) {
                if (record["physical_fence"] != null && record["physical_fence"] != fence)
                    throw RingFailure("operation_id_conflict", RingText.text(R.string.ring_native_d402d0bdba1c))
                JsonObject(record + ("physical_fence" to fence))
            } else record
        }
        val updated = JsonObject(sealed)
        if (updated != own) save(JsonObject(all + (binding.identity to updated)))
    }
    /** Metadata only: original binding must be retained before Bro forget/regrant; no GATT authority is granted. */
    fun preserveBinding(binding: RingWorkoutBinding) = lock.withLock { sealBinding(binding) }
    fun disconnected(binding: RingWorkoutBinding, token: String) = lock.withLock {
        identities[binding.identity]?.takeIf { it.token == token }?.let {
            identities[binding.identity] = it.copy(token = null, at = SystemClock.elapsedRealtime()) }
    }
    fun physicalFence(binding: RingWorkoutBinding): JsonObject = lock.withLock {
        val pending = unresolved(load()).filter { affected(binding, it) }
        val own = pending.filter { it.fence?.string("owner_id") == person(binding) || it.bindingKey == binding.identity }
        val needsIdentity = own.any { remembered(binding) == null }
        obj { put("blocked", pending.isNotEmpty()); put("refresh_required", pending.isNotEmpty() && own.size == pending.size && needsIdentity)
            put("can_reconcile", pending.any { authorized(binding, it) && remembered(binding)?.identity == it.recovery["identity"] })
            put("reason", when {
                pending.isEmpty() -> JsonNull
                pending.any { it.fence == null && it.bindingKey != binding.identity } -> JsonPrimitive("unattributed_command_unknown")
                own.size != pending.size -> JsonPrimitive("physical_command_unknown")
                needsIdentity -> JsonPrimitive("fresh_identity_required")
                own.any { remembered(binding)?.identity != it.recovery["identity"] } -> JsonPrimitive("physical_identity_changed")
                else -> JsonPrimitive("command_unknown")
            }) }
    }
    fun blocked(binding: RingWorkoutBinding): Boolean = physicalFence(binding).bool("blocked") == true
    fun pendingOperations(binding: RingWorkoutBinding): JsonArray = lock.withLock {
        JsonArray(unresolved(load()).filter { affected(binding, it) && authorized(binding, it) }.map { entry -> obj {
            val same = remembered(binding)?.identity == entry.recovery["identity"]
            put("operation_id", entry.id); put("domain", entry.domain); put("can_reconcile", same)
            put("reason", if (same) "read_only_reconciliation_available" else "fresh_identity_required")
        } })
    }
    private fun selected(binding: RingWorkoutBinding, domain: String, id: String, all: JsonObject): Entry? {
        val choices = entries(all).filter { it.domain == domain && it.id == id && authorized(binding, it) }
        if (choices.map { it.recovery }.distinct().size > 1) throw RingFailure("operation_id_conflict", RingText.text(R.string.ring_native_ce11c075978c))
        return choices.firstOrNull { it.bindingKey == binding.identity } ?: choices.firstOrNull()
    }
    private fun receiptInternal(binding: RingWorkoutBinding, id: String, command: JsonObject, domain: String): JsonObject? {
        val archived = loadArchive(binding.identity, id, domain)[id] as? JsonObject
        val entry = selected(binding, domain, id, load())
        val prior = archived ?: entry?.let { completed(it) ?: (it.record["result"] as? JsonObject) ?: obj {
            put("operation_id", id); put("command", it.command); put("status", "unknown"); put("reason", "process_interrupted") } } ?: return null
        return if (prior["command"] == command) prior else obj { put("operation_id", id); put("command", command)
            put("status", "rejected"); put("reason", "operation_id_conflict") }
    }
    fun receipt(binding: RingWorkoutBinding, id: String, command: JsonObject): JsonObject? = lock.withLock {
        receiptInternal(binding, id, command, "device") }
    fun controlReceipt(binding: RingWorkoutBinding, id: String, command: JsonObject): JsonObject? = lock.withLock {
        receiptInternal(binding, id, command, "control") }
    private fun put(binding: RingWorkoutBinding, recovery: JsonObject, domain: String, token: String?, legacy: Boolean = false) {
        if (binding.origin != origin) throw RingFailure("invalid_binding", RingText.text(R.string.ring_native_a8fd52432fef))
        val request = (if (domain == "device") recovery["request"] as? JsonObject else recovery)
            ?: throw RingFailure("native_response", RingText.text(R.string.ring_native_576c41093d27))
        val id = request.string("operation_id") ?: throw RingFailure("native_response", RingText.text(R.string.ring_native_d2ae1d6fc041))
        val command = request["command"] as? JsonObject ?: throw RingFailure("native_response", RingText.text(R.string.ring_native_4e0d5ad790be))
        if (command.string("type") == "notification") return
        val identity = recovery["identity"] as? JsonObject ?: throw RingFailure("native_response", RingText.text(R.string.ring_native_0804af2c69d6))
        if (!nativeIdentity(identity)) throw RingFailure("native_response", RingText.text(R.string.ring_native_3d665dee5e48))
        if (!legacy) source(binding, token ?: "", identity)
        if (id.isBlank() || id.length > 128 || command.string("type") in setOf("read", "read_settings") ||
            domain == "device" && recovery.bool("mutation_started") != true) throw RingFailure("native_response", RingText.text(R.string.ring_native_142e5177b75e))
        val prior = loadArchive(binding.identity, id, domain)[id] as? JsonObject
        if (prior != null) {
            if (legacy && prior["command"] == command && prior.string("status") == "verified") return
            throw RingFailure(if (prior["command"] == command) "operation_already_completed" else "operation_id_conflict", RingText.text(R.string.ring_native_f272377508d4))
        }
        val all = load(); val records = all[binding.identity] as? JsonObject ?: obj {}
        val key = if (domain == "device") id else "@control:${sha256(id)}"
        val old = records[key] as? JsonObject
        if (old?.get("recovery") != null && old["recovery"] != recovery)
            throw RingFailure("operation_id_conflict", RingText.text(R.string.ring_native_79a1b05bc9a1))
        if (!legacy && unresolved(all).any { affected(binding, it) && !(it.bindingKey == binding.identity && it.key == key) })
            throw RingFailure("physical_command_unknown", RingText.text(R.string.ring_native_991dbb5d0df3))
        if (old == null && unresolved(all).size >= 256) throw RingFailure("operation_capacity", RingText.text(R.string.ring_native_d19a5f377e56))
        val fence = obj { put("physical_id", physical(binding)); put("owner_id", person(binding)) }
        if (old?.get("physical_fence") != null && old["physical_fence"] != fence) throw RingFailure("operation_id_conflict", RingText.text(R.string.ring_native_0fd89bf9ad06))
        val record = obj { put("recovery", recovery); put("domain", domain); put("physical_fence", fence)
            old?.get("result")?.let { put("result", it) } }
        save(JsonObject(all + (binding.identity to JsonObject(records + (key to record)))))
    }
    fun putRecovery(binding: RingWorkoutBinding, recovery: JsonObject, token: String) = lock.withLock { put(binding, recovery, "device", token) }
    fun putControlRecovery(binding: RingWorkoutBinding, recovery: JsonObject, token: String) = lock.withLock { put(binding, recovery, "control", token) }
    /** Import only the matching original Bro binding before forget can remove its legacy envelope. */
    fun preserveLegacyControl(binding: RingWorkoutBinding, recovery: JsonObject) = lock.withLock { put(binding, recovery, "control", null, legacy = true) }
    fun recovery(binding: RingWorkoutBinding, id: String): JsonObject? = lock.withLock { selected(binding, "device", id, load())?.recovery }
    fun controlRecovery(binding: RingWorkoutBinding, id: String): JsonObject? = lock.withLock { selected(binding, "control", id, load())?.recovery }
    private fun recoveries(binding: RingWorkoutBinding, domain: String): List<JsonObject> = unresolved(load())
        .filter { it.domain == domain && authorized(binding, it) }.groupBy { it.id }.map { (_, same) ->
            if (same.map { it.recovery }.distinct().size > 1) throw RingFailure("operation_id_conflict", RingText.text(R.string.ring_native_b260e0e399af))
            same.first().recovery }
    fun recoveries(binding: RingWorkoutBinding): List<JsonObject> = lock.withLock { recoveries(binding, "device") }
    fun controlRecoveries(binding: RingWorkoutBinding): List<JsonObject> = lock.withLock { recoveries(binding, "control") }
    private fun finish(binding: RingWorkoutBinding, result: JsonObject, domain: String, token: String?) {
        val id = result.string("operation_id") ?: return
        if ((result["command"] as? JsonObject)?.string("type") == "notification") return
        if (result["command"] !is JsonObject || result.string("status") !in setOf("verified", "rejected", "unknown"))
            throw RingFailure("native_response", RingText.text(R.string.ring_native_d4843cee1c36))
        var all = load(); val entry = selected(binding, domain, id, all)
        if (entry != null && entry.command != result["command"]) throw RingFailure("operation_id_conflict", RingText.text(R.string.ring_native_4bef46587b2a))
        if (entry != null && result.string("status") != "verified") {
            val records = all[entry.bindingKey] as JsonObject
            save(JsonObject(all + (entry.bindingKey to JsonObject(records + (entry.key to JsonObject(entry.record + ("result" to result)))))))
            return
        }
        if (entry != null) source(binding, token ?: "", entry.recovery["identity"] as JsonObject)
        // Commit original lineage first, then the current grant's identical alias, then retire active state.
        val original = entry?.bindingKey ?: binding.identity
        saveArchive(original, id, domain, result)
        if (original != binding.identity) saveArchive(binding.identity, id, domain, result)
        if (entry != null) {
            val duplicates = entries(all).filter { it.domain == domain && it.id == id && authorized(binding, it) && it.recovery == entry.recovery }
            for (duplicate in duplicates) {
                // Preserve every pre-existing identical lineage before removing its active row.
                if (duplicate.bindingKey != original && duplicate.bindingKey != binding.identity)
                    saveArchive(duplicate.bindingKey, id, domain, result)
                val records = all[duplicate.bindingKey] as JsonObject
                all = JsonObject(all + (duplicate.bindingKey to JsonObject(records - duplicate.key)))
            }
            save(all)
        }
    }
    fun result(binding: RingWorkoutBinding, result: JsonObject, token: String? = null) = lock.withLock { finish(binding, result, "device", token) }
    fun controlResult(binding: RingWorkoutBinding, result: JsonObject, token: String) = lock.withLock { finish(binding, result, "control", token) }
    fun receipts(binding: RingWorkoutBinding): List<JsonObject> = lock.withLock {
        entries(load()).filter { it.domain == "device" && authorized(binding, it) }.mapNotNull { it.record["result"] as? JsonObject } }
    /** Fixture cleanup only; production forget never calls this journal erasure helper. */
    fun clear(binding: RingWorkoutBinding) = lock.withLock {
        save(JsonObject(load() - binding.identity)); identities.remove(binding.identity)
        for (domain in listOf("device", "control")) {
            val prefix = "$archivePrefix${if (domain == "device") "" else "control-"}${sha256(binding.identity).take(24)}-"
            directory.listFiles()?.filter { it.name.startsWith(prefix) &&
                (it.name.endsWith(".bin") || it.name.endsWith(".bin.bak") || it.name.endsWith(".bin.new")) }
                ?.map { File(it.path.removeSuffix(".bak").removeSuffix(".new")) }?.distinct()?.forEach { AtomicFile(it).delete() }
        }
    }
    fun hasData(): Boolean = lock.withLock {
        file.baseFile.exists() || File(file.baseFile.path + ".bak").exists() ||
            File(file.baseFile.path + ".new").exists() || directory.listFiles()?.any { it.name.startsWith(archivePrefix) } == true
    }

    /** Explicit server departure removes recovery and receipts of all its bindings. */
    fun clearForServerSwitch() = lock.withLock {
        file.delete()
        directory.listFiles()?.filter { it.name.startsWith(archivePrefix) }
            ?.forEach { check(it.delete() || !it.exists()) { "Ring device receipt could not be cleared" } }
        identities.clear()
        crypto.delete()
    }
}
