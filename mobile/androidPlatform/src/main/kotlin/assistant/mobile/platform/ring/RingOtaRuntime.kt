package assistant.mobile.platform.ring

import assistant.mobile.platform.R

import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.util.AtomicFile
import androidx.core.content.ContextCompat
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.*

/** The repository implements this with its existing BLE mutex and GATT lifecycle. */
interface RingOtaTransport {
    suspend fun exclusive(binding: RingWorkoutBinding, operation: suspend (RingOtaLink) -> Unit)
    suspend fun readOnly(binding: RingWorkoutBinding, operation: suspend (RingOtaReadLink) -> Unit) {
        exclusive(binding) { operation(it) }
    }
}
interface RingOtaReadLink {
    /** Read-only fresh reconnect; no loader activation or flash blocks are available through this interface. */
    suspend fun reconnectForFirmware(): RingOtaDevice
}
interface RingOtaLink : RingOtaReadLink {
    /** Fresh hardware/firmware, battery and physical address on this connection. */
    suspend fun freshDevice(): RingOtaDevice
    suspend fun activateLoader(frames: List<ByteArray>)
    /** A single logical bulk write; the adapter serializes negotiated ATT fragments. No retries. */
    suspend fun writeBulk(frame: ByteArray)
    suspend fun receiveBulk(timeoutMs: Long): RingOtaNotice?
}
data class RingOtaDevice(val address: String, val identity: JsonObject, val batteryPercent: Int?, val charging: Boolean? = null)
enum class RingOtaChargingRequirement { REQUIRED, NOT_REQUIRED }
data class RingOtaPowerConditions(val minimumBatteryPercent: Int, val charging: RingOtaChargingRequirement, val evidenceRef: String) {
    fun validate() { require(minimumBatteryPercent in 1..100 && evidenceRef.isNotBlank() && evidenceRef.length <= 256 && '\u0000' !in evidenceRef) }
    fun satisfied(device: RingOtaDevice): Boolean {
        validate()
        // 30% is an additional software guard; the compiled proof supplies actual target-specific conditions.
        return device.batteryPercent?.let { it in maxOf(30, minimumBatteryPercent)..100 } == true &&
            (charging == RingOtaChargingRequirement.NOT_REQUIRED || device.charging == true)
    }
}
data class RingOtaNotice(val frame: ByteArray, val receivedElapsedMs: Long)

data class RingOtaManifest(
    val id: String, val title: String, val officialOrigin: String, val imagePath: String,
    val sourceIdentity: JsonObject, val targetFirmware: String, val size: Int, val sha256: String,
    val evidenceRef: String, val loader: RingOtaLoaderRecipe, val power: RingOtaPowerConditions,
) {
    fun validate() {
        power.validate()
        val origin = URI(officialOrigin); val path = URI(imagePath)
        require(origin.scheme == "https" && !origin.host.isNullOrBlank() && origin.userInfo == null &&
            origin.query == null && origin.fragment == null && origin.path.isNullOrEmpty() && origin.port in setOf(-1, 443))
        require(path.isAbsolute.not() && path.path.startsWith('/') && path.query == null && path.fragment == null &&
            path.rawAuthority == null && path.normalize() == path)
        require(id.isNotBlank() && id.length <= 128 && targetFirmware.isNotBlank() && targetFirmware.length <= 256 && '\u0000' !in targetFirmware)
        require(targetFirmware != sourceIdentity.string("firmware"))
        require(size in 1..RingOtaCodec.MAX_IMAGE_BYTES && sha256.length == 64 && sha256.all { it in '0'..'9' || it in 'a'..'f' })
        require(sourceIdentity.keys == setOf("model", "hardware", "firmware") && sourceIdentity.values.all {
            it is JsonPrimitive && it.isString && it.content.isNotBlank() && it.content.length <= 256 && '\u0000' !in it.content })
        require(evidenceRef.isNotBlank() && loader.activationFrames.isNotEmpty() && loader.blockIndexOffset != null)
    }
    fun review() = obj { put("manifest_id", id); put("title", title); put("target_firmware", targetFirmware)
        put("size_bytes", size); put("sha256", sha256); put("availability", "available"); put("reason", "qualified_official_target") }
}
data class RingOtaLoaderRecipe(val activationFrames: List<ByteArray>, val blockIndexOffset: Int?)
object RingOtaDeclaredLength {
    fun allowed(length: Long, expected: Int): Boolean = expected in 1..RingOtaCodec.MAX_IMAGE_BYTES && (length == -1L || length == expected.toLong())
}

object RingOtaCatalog {
    // Entries require public official provenance, exact target/image digest and an
    // actual loader/correlated response capture. Soul SDK presence is insufficient.
    private val qualified: List<RingOtaManifest> = emptyList()
    fun get(id: String): RingOtaManifest? = qualified.singleOrNull { it.id == id }?.also { it.validate() }
    fun snapshot() = RingOtaCatalogState(qualified.map {
        it.validate()
        RingOtaCatalogEntry(it.id, it.title, it.targetFirmware, it.size, it.sha256, "available", "qualified_official_target")
    }, if (qualified.isEmpty()) "official_image_and_loader_unqualified" else "qualified_catalog")
}

/** Independent fresh implementation of bounded Oudmon framing, not Nordic DFU. */
object RingOtaCodec {
    const val MAX_IMAGE_BYTES = 12_288_000
    const val BLOCK_BYTES = 1024
    fun crc(bytes: ByteArray): Int {
        var result = 0xffff
        for (byte in bytes) {
            result = result xor (byte.toInt() and 255)
            repeat(8) { result = if (result and 1 != 0) (result ushr 1) xor 0xa001 else result ushr 1 }
        }
        return result and 0xffff
    }
    fun frame(action: Int, payload: ByteArray = byteArrayOf()): ByteArray {
        require(action in 1..5 && payload.size <= BLOCK_BYTES + 2)
        val checksum = crc(payload)
        return byteArrayOf(0xbc.toByte(), action.toByte(), payload.size.toByte(), (payload.size ushr 8).toByte(),
            checksum.toByte(), (checksum ushr 8).toByte()) + payload
    }
    fun initialize(image: ByteArray): ByteArray {
        require(image.size in 1..MAX_IMAGE_BYTES)
        val crc = crc(image); val sum = image.sumOf { it.toInt() and 255 } and 0xffff
        return frame(2, byteArrayOf(1, image.size.toByte(), (image.size ushr 8).toByte(), (image.size ushr 16).toByte(),
            (image.size ushr 24).toByte(), crc.toByte(), (crc ushr 8).toByte(), sum.toByte(), (sum ushr 8).toByte()))
    }
    fun block(image: ByteArray, index: Int): ByteArray {
        require(image.size in 1..MAX_IMAGE_BYTES && index in 1..((image.size + BLOCK_BYTES - 1) / BLOCK_BYTES))
        val offset = (index - 1) * BLOCK_BYTES
        return frame(3, byteArrayOf(index.toByte(), (index ushr 8).toByte()) + image.copyOfRange(offset, minOf(offset + BLOCK_BYTES, image.size)))
    }
    fun receipt(frame: ByteArray, action: Int, block: Int?, recipe: RingOtaLoaderRecipe): Int? {
        if (frame.size < 7 || frame[0].toInt() and 255 != 0xbc || frame[1].toInt() and 255 != action) return null
        val size = (frame[2].toInt() and 255) or ((frame[3].toInt() and 255) shl 8)
        if (size !in 1..32 || frame.size != size + 6) return null
        val payload = frame.copyOfRange(6, frame.size)
        val checksum = (frame[4].toInt() and 255) or ((frame[5].toInt() and 255) shl 8)
        if (crc(payload) != checksum) return null
        if (block != null) {
            val index = recipe.blockIndexOffset ?: return null
            if (index < 1 || index + 1 >= payload.size) return null
            val echoed = (payload[index].toInt() and 255) or ((payload[index + 1].toInt() and 255) shl 8)
            if (echoed != block) return null
        }
        return payload[0].toInt() and 255
    }
    fun digest(image: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(image).joinToString("") { "%02x".format(it) }
}

/** Journal facts never carry reusable consent or a caller-supplied physical proof. */
object RingOtaJournal {
    private fun physical(origin: String, owner: RingWorkoutBinding) = sha256("$origin\n${owner.address.uppercase()}")
    fun attribution(origin: String, owner: RingWorkoutBinding): JsonObject {
        require(owner.origin == origin)
        return obj { put("origin_id", sha256(origin)); put("owner_id", sha256("$origin\n${owner.userId}")); put("physical_id", physical(origin, owner)) }
    }
    fun attributed(record: JsonObject): Boolean {
        val value = record["attribution"] as? JsonObject ?: return false
        return value.keys == setOf("origin_id", "owner_id", "physical_id") && value.values.all {
            it is JsonPrimitive && it.isString && it.content.length == 64 && it.content.all { ch -> ch in '0'..'9' || ch in 'a'..'f' } }
    }
    fun affected(origin: String, owner: RingWorkoutBinding?, record: JsonObject): Boolean =
        !attributed(record) || owner == null || owner.origin == origin &&
            (record["attribution"] as JsonObject).let { it.string("origin_id") == sha256(origin) && it.string("physical_id") == physical(origin, owner) }
    fun owned(origin: String, owner: RingWorkoutBinding?, record: JsonObject): Boolean = owner != null && owner.origin == origin &&
        if (attributed(record)) record["attribution"] == attribution(origin, owner) else record.string("binding") == owner.identity
    private fun validIdentity(value: JsonObject) = value.keys == setOf("model", "hardware", "firmware") && value.values.all {
        it is JsonPrimitive && it.isString && it.content.isNotBlank() && it.content.length <= 256 && '\u0000' !in it.content }
    fun complete(record: JsonObject): Boolean {
        val source = record["source_identity"] as? JsonObject ?: return false
        val readback = record["verified_readback"] as? JsonObject ?: return false
        val actual = readback["identity"] as? JsonObject ?: return false
        val scope = record["attribution"] as? JsonObject ?: return false
        val target = record.string("target_firmware")?.takeIf { it.isNotBlank() && it.length <= 256 && '\u0000' !in it } ?: return false
        val observed = readback.string("observed_at") ?: return false
        try { java.time.Instant.parse(observed) } catch (_: Exception) { return false }
        return record.string("phase") == "complete" && record.bool("write_started") == true && attributed(record) &&
            validIdentity(source) && validIdentity(actual) && target != source.string("firmware") &&
            actual.string("model") == source.string("model") && actual.string("hardware") == source.string("hardware") &&
            actual.string("firmware") == target && record.string("actual_firmware") == target &&
            readback.string("physical_id") == scope.string("physical_id")
    }
    fun pending(record: JsonObject) = record.bool("write_started") == true && !complete(record)
    fun reconcilable(record: JsonObject): Boolean {
        val source = record["source_identity"] as? JsonObject ?: return false
        val target = record.string("target_firmware") ?: return false
        return attributed(record) && pending(record) && validIdentity(source) && target.isNotBlank() && target.length <= 256 &&
            '\u0000' !in target && target != source.string("firmware")
    }
    /** Called only with the native transport's fresh same-connection read, never with a screen identity. */
    fun reconcile(origin: String, owner: RingWorkoutBinding, record: JsonObject, device: RingOtaDevice, observedAt: String): JsonObject {
        val source = record["source_identity"] as? JsonObject
        val target = record.string("target_firmware")
        if (!owned(origin, owner, record) || !reconcilable(record) || source == null ||
            !validIdentity(source) || target.isNullOrBlank() || target.length > 256 || '\u0000' in target || target == source.string("firmware") ||
            device.address.uppercase() != owner.address.uppercase() || !validIdentity(device.identity) ||
            device.identity.string("model") != source.string("model") || device.identity.string("hardware") != source.string("hardware") ||
            device.identity.string("firmware") != target)
            throw RingFailure("ota_firmware_not_confirmed", RingText.text(R.string.ring_ota_6cdc8c963eaa))
        val next = JsonObject(record + mapOf("phase" to JsonPrimitive("complete"), "actual_firmware" to JsonPrimitive(target),
            "verified_readback" to obj { put("identity", device.identity); put("physical_id", physical(origin, owner)); put("observed_at", observedAt) }))
        if (!complete(next)) throw RingFailure("ota_readback_invalid", RingText.text(R.string.ring_ota_4226fc9b1f41))
        return next
    }
}

class RingOtaStore(context: Context, private val origin: String) {
    companion object { private val locks = ConcurrentHashMap<String, ReentrantLock>() }
    private val lock = locks.computeIfAbsent(sha256(origin)) { ReentrantLock() }
    private val file = AtomicFile(File(context.noBackupFilesDir, "ring-ota-${sha256(origin).take(24)}.bin"))
    private val crypto = RingCrypto("assistant.ring.ota.v1.${sha256(origin).take(24)}")
    private val aad = "ring-ota-v1\n$origin"
    private fun entries(): JsonObject {
        if (!file.baseFile.exists() && !File(file.baseFile.path + ".bak").exists()) return obj {}
        try {
            val bytes = file.readFully(); require(bytes.size <= 512 * 1024)
            val raw = ringJson.parseToJsonElement(crypto.open(bytes, aad).toString(Charsets.UTF_8)) as JsonObject
            val values = if (raw["journal_version"]?.jsonPrimitive?.intOrNull == 2) raw["entries"] as JsonObject
                else obj { put(raw.string("operation_id") ?: throw IllegalArgumentException("Legacy operation ID missing"), raw) }
            values.forEach { (id, value) ->
                val record = value as JsonObject
                require(id.isNotBlank() && id.length <= 128 && record.string("operation_id") == id && !record.string("binding").isNullOrBlank())
                require(record.bool("write_started") == true)
                require("confirmation_token" !in record && "prepared" !in record)
                require(record["attribution"] == null || RingOtaJournal.attributed(record))
            }
            return values
        } catch (_: Exception) { throw RingFailure("ota_store_unavailable", RingText.text(R.string.ring_ota_6bc0e7d5251f)) }
    }
    private fun commit(values: JsonObject) {
        val bytes = crypto.seal(obj { put("journal_version", 2); put("entries", values) }.toString().toByteArray(Charsets.UTF_8), aad)
        if (bytes.size > 512 * 1024) throw RingFailure("ota_journal_capacity", RingText.text(R.string.ring_ota_90f09ae01147))
        val output = file.startWrite()
        try { output.write(bytes); file.finishWrite(output) } catch (failure: Exception) { file.failWrite(output); throw failure }
    }
    fun records(): List<JsonObject> = lock.withLock { entries().values.map { it as JsonObject } }
    fun load(): JsonObject? = lock.withLock { entries().values.lastOrNull() as? JsonObject }
    fun save(value: JsonObject) = lock.withLock {
        require("confirmation_token" !in value && "prepared" !in value)
        val id = value.string("operation_id")?.takeIf { it.isNotBlank() && it.length <= 128 } ?: throw IllegalArgumentException("Operation ID missing")
        require(!value.string("binding").isNullOrBlank() && value.bool("write_started") == true)
        require(value["attribution"] == null || RingOtaJournal.attributed(value))
        val all = entries(); val previous = all[id] as? JsonObject
        if (previous != null) {
            val immutable = setOf("binding", "operation_id", "manifest_id", "source_identity", "target_firmware", "sha256", "total_bytes", "attribution", "manifest_evidence_ref")
            if (immutable.any { previous[it] != value[it] } || RingOtaJournal.complete(previous) && previous != value)
                throw RingFailure("operation_id_conflict", RingText.text(R.string.ring_ota_c8012d05270c))
        } else if (RingOtaJournal.pending(value)) {
            val pending = all.values.map { it as JsonObject }.filter(RingOtaJournal::pending)
            if (pending.any { !RingOtaJournal.attributed(it) || !RingOtaJournal.attributed(value) ||
                    (it["attribution"] as JsonObject).string("physical_id") == (value["attribution"] as JsonObject).string("physical_id") })
                throw RingFailure("ota_command_unknown", RingText.text(R.string.ring_ota_c701ffdd3968))
            if (pending.size >= 64) throw RingFailure("ota_journal_capacity", RingText.text(R.string.ring_ota_611cdfc26526))
        }
        commit(JsonObject(all + (id to value)))
    }
    /** Exact original binding attribution only, without fresh GATT authority or consent. */
    fun preserveBinding(owner: RingWorkoutBinding) = lock.withLock {
        if (owner.origin != origin) throw RingFailure("invalid_binding", RingText.text(R.string.ring_ota_9dc2006fcbf7))
        val all = entries(); val updated = JsonObject(all.mapValues { (_, value) ->
            val record = value as JsonObject
            if (!RingOtaJournal.attributed(record) && record.string("binding") == owner.identity)
                JsonObject(record + ("attribution" to RingOtaJournal.attribution(origin, owner))) else record
        })
        if (updated != all) commit(updated)
    }
    /** Fixture cleanup only. Binding changes never erase flash uncertainty. */
    fun clear() = lock.withLock { file.delete() }
    fun hasData(): Boolean = lock.withLock {
        file.baseFile.exists() || File(file.baseFile.path + ".bak").exists() || File(file.baseFile.path + ".new").exists()
    }

    /** The repository rejects server departure while the firmware actor is active. */
    fun clearForServerSwitch() = lock.withLock { file.delete(); crypto.delete() }
}

/** An application-owned exclusive updater. Every uncertain write stops and survives process death. */
class RingOtaRuntime(
    private val context: Context, private val origin: String, private val binding: () -> RingWorkoutBinding?,
    private val foregroundUser: () -> String?, private val radioBusy: () -> Boolean,
    private val transport: RingOtaTransport?,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val store = RingOtaStore(context, origin)
    private var journal: JsonObject? = null
    private var storageUnavailable = false
    private var phase = "idle"
    private var reason = "official_image_and_loader_unqualified"
    private var job: Job? = null
    private var manifest: RingOtaManifest? = null
    private var image: ByteArray? = null
    private var token: String? = null
    private var tokenDeadline = 0L
    private var preparedOwner: RingWorkoutBinding? = null
    private var preparedId: String? = null
    @Volatile private var written = false
    @Volatile private var progress = 0
    private val mutable = MutableStateFlow(RingOtaState())
    val states: StateFlow<RingOtaState> get() = mutable
    init {
        RingText.initialize(context)
        try { binding()?.let(store::preserveBinding); journal = choose(binding()) }
        catch (_: Exception) { storageUnavailable = true; phase = "blocked"; reason = "ota_store_unavailable" }
        publish() // Confirmation tokens and image bytes intentionally never survive a process restart.
    }
    private fun records(): List<JsonObject> = try { store.records() } catch (_: Exception) { storageUnavailable = true; emptyList() }
    private fun choose(owner: RingWorkoutBinding?): JsonObject? = records().filter { RingOtaJournal.owned(origin, owner, it) }
        .let { owned -> owned.lastOrNull { RingOtaJournal.pending(it) } ?: owned.lastOrNull() }
    @Synchronized fun busy(): Boolean {
        val all = records()
        return storageUnavailable || job?.isActive == true || all.any { RingOtaJournal.pending(it) && RingOtaJournal.affected(origin, binding(), it) }
    }
    /** Durable uncertainty blocks mutation, never the read-only HW/FW probe. */
    @Synchronized fun readOnlyBusy(): Boolean = job?.isActive == true
    @Synchronized fun snapshot(): RingOtaState { publish(); return mutable.value }
    @Synchronized private fun publish() {
        val owner = binding()
        val all = records()
        val pending = all.filter { RingOtaJournal.pending(it) && RingOtaJournal.affected(origin, owner, it) }
        val operation = if (job?.isActive == true && journal != null && RingOtaJournal.owned(origin, owner, journal!!)) journal else choose(owner)
        val own = operation != null && RingOtaJournal.owned(origin, owner, operation)
        val preparedVisible = preparedOwner == owner && owner != null && foregroundUser() == owner.userId
        val visible = own && owner != null && foregroundUser() == owner.userId
        val operationPending = operation?.let(RingOtaJournal::pending) == true
        val verified = visible && operation?.let { RingOtaJournal.complete(it) && (preparedId == null || it.string("operation_id") == preparedId) } == true
        val foreign = pending.any { !RingOtaJournal.owned(origin, owner, it) }
        val fenceReason = when {
            storageUnavailable -> "ota_store_unavailable"
            pending.any { !RingOtaJournal.attributed(it) && !RingOtaJournal.owned(origin, owner, it) } -> "ota_unattributed_unknown"
            foreign -> "ota_owned_by_previous_binding"
            pending.isNotEmpty() -> "ota_command_unknown"
            else -> null
        }
        val displayedPhase = when {
            storageUnavailable -> "blocked"
            foreign -> "blocked"
            preparedVisible && (preparedId != null || job?.isActive == true) -> phase
            verified -> "complete"
            visible && operationPending -> if (job?.isActive == true) phase else "unknown"
            else -> "idle"
        }
        val completedVisible = verified && displayedPhase == "complete"
        val displayedReason = when {
            fenceReason != null && displayedPhase == "blocked" -> fenceReason
            verified -> "firmware_readback_confirmed"
            preparedVisible && preparedId != null -> reason
            visible && operationPending -> operation?.string("reason") ?: "ota_process_interrupted"
            else -> "official_image_and_loader_unqualified"
        }
        mutable.value = RingOtaState.fromJournalProjection(obj {
            put("user_id", owner?.takeIf { foregroundUser() == it.userId }?.userId?.let(::JsonPrimitive) ?: JsonNull)
            put("source_id", owner?.takeIf { foregroundUser() == it.userId }?.sourceId?.let(::JsonPrimitive) ?: JsonNull)
            put("binding_generation", owner?.takeIf { foregroundUser() == it.userId }?.grantGeneration ?: 0)
            put("phase", displayedPhase); put("reason", displayedReason)
            put("physical_fence", obj { put("blocked", storageUnavailable || pending.isNotEmpty()); put("reason", fenceReason?.let(::JsonPrimitive) ?: JsonNull)
                put("can_reconcile", !storageUnavailable && pending.any { RingOtaJournal.owned(origin, owner, it) && RingOtaJournal.reconcilable(it) }) })
            put("operation_id", if (preparedVisible && preparedId != null) JsonPrimitive(preparedId!!) else if (visible) operation!!["operation_id"] ?: JsonNull else JsonNull)
            put("progress_bytes", if (preparedVisible && preparedId != null) JsonPrimitive(progress) else if (visible) operation!!["progress_bytes"] ?: JsonPrimitive(0) else JsonPrimitive(0))
            put("total_bytes", if (preparedVisible && manifest != null) JsonPrimitive(manifest!!.size) else if (visible) operation!!["total_bytes"] ?: JsonPrimitive(0) else JsonPrimitive(0))
            put("expected_firmware", if (completedVisible) operation!!["target_firmware"] ?: JsonNull else JsonNull)
            put("actual_firmware", if (completedVisible) operation!!["actual_firmware"] ?: JsonNull else JsonNull)
            put("prepared", if (preparedVisible && phase == "prepared" && token != null && SystemClock.elapsedRealtime() < tokenDeadline) obj {
                val candidate = manifest!!
                put("operation_id", preparedId!!); put("confirmation_token", token!!); put("source_identity", candidate.sourceIdentity)
                put("target_firmware", candidate.targetFirmware); put("size_bytes", candidate.size); put("sha256", candidate.sha256)
                put("expires_at_ms", System.currentTimeMillis() + tokenDeadline - SystemClock.elapsedRealtime())
            } else JsonNull)
        })
    }
    private fun owner(expected: RingOtaOwner): RingWorkoutBinding {
        val owner = binding() ?: throw RingFailure("not_authenticated", RingText.text(R.string.ring_ota_59f65e17d26f))
        if (foregroundUser() != owner.userId) throw RingFailure("not_authenticated", RingText.text(R.string.ring_ota_2c01a5684186))
        if (owner.userId != expected.userId || owner.sourceId != expected.sourceId || owner.grantGeneration != expected.bindingGeneration)
            throw RingFailure("invalid_binding", RingText.text(R.string.ring_native_dd7938b1b3af))
        return owner
    }
    @Synchronized fun catalog(expected: RingOtaOwner): RingOtaCatalogState {
        owner(expected)
        return RingOtaCatalog.snapshot()
    }
    @Synchronized fun command(expected: RingOtaOwner, action: RingOtaAction): RingOtaState {
        val owner = owner(expected)
        if (storageUnavailable) throw RingFailure("ota_store_unavailable", RingText.text(R.string.ring_ota_b72cc7c217d7))
        when (action) {
            is RingOtaAction.Prepare -> {
                if (busy() || radioBusy()) throw RingFailure("ring_busy", RingText.text(R.string.ring_ota_e62128aab3cd))
                val selected = RingOtaCatalog.get(action.manifestId)
                if (selected == null || transport == null) throw RingFailure("official_image_and_loader_unqualified", RingText.text(R.string.ring_ota_c2c7c4b5ba73))
                invalidateConfirmation(); manifest = selected; preparedOwner = owner; preparedId = UUID.randomUUID().toString(); phase = "preparing"; reason = "verifying_official_image"; publish()
                job = scope.launch {
                    try {
                        val bytes = download(selected)
                        transport.exclusive(owner) { link -> requireDevice(link.freshDevice(), owner, selected, true) }
                        synchronized(this@RingOtaRuntime) {
                            if (binding() != owner || preparedOwner != owner) throw CancellationException("Binding changed")
                            image = bytes; token = UUID.randomUUID().toString(); tokenDeadline = SystemClock.elapsedRealtime() + 60_000
                            phase = "prepared"; reason = "explicit_flash_confirmation_required"; publish()
                        }
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (failure: Exception) { synchronized(this@RingOtaRuntime) { phase = "blocked"; reason = (failure as? RingFailure)?.code ?: "official_image_unavailable"; invalidateConfirmation(); publish() } }
                }
            }
            is RingOtaAction.Confirm -> {
                if (phase != "prepared" || preparedOwner != owner || action.operationId != preparedId ||
                    action.confirmationToken != token || token == null || SystemClock.elapsedRealtime() >= tokenDeadline)
                    throw RingFailure("ota_confirmation_expired", RingText.text(R.string.ring_ota_182565b3d9dd))
                if (radioBusy()) throw RingFailure("ring_busy", RingText.text(R.string.ring_ota_e62128aab3cd))
                val selected = manifest!!; val bytes = image!!; val id = preparedId!!
                invalidateConfirmation(keepImage = true); phase = "starting"; reason = "flash_confirmed"; publish()
                RingOtaServiceOwner.attach(id, this)
                try { ContextCompat.startForegroundService(context, Intent(context, RingOtaService::class.java)
                    .putExtra(RingOtaService.OPERATION, id)) }
                catch (_: RuntimeException) {
                    RingOtaServiceOwner.detach(id); invalidateConfirmation(); phase = "failed"; reason = "foreground_start_denied"; publish()
                    throw RingFailure("foreground_start_denied", RingText.text(R.string.ring_ota_ff83d8cf755d))
                }
                job = scope.launch { flash(owner, selected, bytes, id) }
            }
            is RingOtaAction.Reconcile -> {
                val id = action.operationId
                val record = records().singleOrNull { it.string("operation_id") == id && RingOtaJournal.owned(origin, owner, it) }
                if (record == null || !RingOtaJournal.pending(record) || !RingOtaJournal.attributed(record))
                    throw RingFailure("bad_payload", RingText.text(R.string.ring_ota_6caee0434c37))
                if (job?.isActive == true || radioBusy()) throw RingFailure("ring_busy", RingText.text(R.string.ring_ota_e62128aab3cd))
                if (transport == null) throw RingFailure("ota_transport_unavailable", RingText.text(R.string.ring_ota_6c7f822607db))
                if (!RingOtaJournal.reconcilable(record))
                    throw RingFailure("ota_recovery_receipt_unverifiable", RingText.text(R.string.ring_ota_fc4b902ac0b9))
                journal = record; written = true; phase = "reconciling"; reason = "fresh_firmware_read"; publish()
                job = scope.launch {
                    try { transport.readOnly(owner) { link -> verifyFirmware(link.reconnectForFirmware(), owner, record) } }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (failure: Exception) { unknown((failure as? RingFailure)?.code ?: "ota_firmware_not_confirmed") }
                    finally { synchronized(this@RingOtaRuntime) { publish() } }
                }
            }
        }
        return snapshot()
    }
    private suspend fun flash(owner: RingWorkoutBinding, selected: RingOtaManifest, bytes: ByteArray, id: String) {
        try {
            require(bytes.size == selected.size && RingOtaCodec.digest(bytes) == selected.sha256)
            val foregroundReady = withTimeoutOrNull(5_000) { RingOtaServiceOwner.readiness(id)?.await() } == true
            if (!foregroundReady) throw RingFailure("foreground_start_denied", RingText.text(R.string.ring_ota_19a9abab6e03))
            withTimeout(30 * 60_000L) { transport!!.exclusive(owner) { link ->
                requireDevice(link.freshDevice(), owner, selected, true)
                checkpoint(owner, selected, id, "starting", 0, "activate_loader", null)
                link.activateLoader(selected.loader.activationFrames)
                send(link, owner, selected, id, "starting", RingOtaWire.start(), 1, null)
                send(link, owner, selected, id, "initializing", RingOtaWire.initialize(bytes), 2, null)
                for (index in 1..((bytes.size + 1023) / 1024)) {
                    send(link, owner, selected, id, "transferring", RingOtaWire.block(bytes, index), 3, index)
                    synchronized(this@RingOtaRuntime) { progress = minOf(index * 1024, bytes.size) }
                    checkpoint(owner, selected, id, "transferring", progress, null, null)
                }
                send(link, owner, selected, id, "checking", RingOtaWire.check(), 4, null)
                send(link, owner, selected, id, "finalizing", RingOtaWire.end(), 5, null)
                checkpoint(owner, selected, id, "verifying", progress, null, null)
                verifyFirmware(link.reconnectForFirmware(), owner, journal ?: throw RingFailure("ota_store_unavailable", RingText.text(R.string.ring_ota_58ab02a76f43)))
            } }
        } catch (cancelled: CancellationException) { if (written) unknown("ota_interrupted"); throw cancelled }
        catch (failure: Exception) { if (written) unknown((failure as? RingFailure)?.code ?: "ota_write_outcome_unknown")
            else synchronized(this) { phase = "failed"; reason = (failure as? RingFailure)?.code ?: "ota_preconditions_changed"; publish() } }
        finally {
            synchronized(this) { image = null; publish() }
            RingOtaServiceOwner.detach(id); context.stopService(Intent(context, RingOtaService::class.java))
        }
    }
    private suspend fun send(link: RingOtaLink, owner: RingWorkoutBinding, selected: RingOtaManifest, id: String,
        next: String, frame: ByteArray, action: Int, block: Int?) {
        currentCoroutineContext().ensureActive()
        if (binding() != owner) throw CancellationException("Binding changed")
        checkpoint(owner, selected, id, next, progress, action.toString(), block)
        val writtenAt = SystemClock.elapsedRealtime()
        link.writeBulk(frame) // No retry; a throw cannot establish whether any ATT fragments reached the loader.
        val deadline = writtenAt + 15_000
        while (SystemClock.elapsedRealtime() < deadline) {
            val notice = link.receiveBulk(deadline - SystemClock.elapsedRealtime()) ?: break
            if (notice.receivedElapsedMs < writtenAt) continue
            if (SystemClock.elapsedRealtime() >= deadline || notice.receivedElapsedMs >= deadline) break
            val status = RingOtaCodec.receipt(notice.frame, action, block, selected.loader) ?: continue
            if (status != 0) throw RingFailure("loader_error_$status", RingText.text(R.string.ring_ota_76983c9f621f))
            return
        }
        throw RingFailure("ota_response_lost", RingText.text(R.string.ring_ota_0d4a8e251045))
    }
    @Synchronized private fun checkpoint(owner: RingWorkoutBinding, selected: RingOtaManifest, id: String,
        next: String, count: Int, action: String?, block: Int?) {
        val record = obj { put("binding", owner.identity); put("operation_id", id); put("manifest_id", selected.id)
            put("source_identity", selected.sourceIdentity); put("target_firmware", selected.targetFirmware); put("sha256", selected.sha256)
            put("attribution", RingOtaJournal.attribution(origin, owner)); put("manifest_evidence_ref", selected.evidenceRef)
            put("total_bytes", selected.size); put("progress_bytes", count); put("phase", next); put("write_started", true)
            put("pending_action", action?.let(::JsonPrimitive) ?: JsonNull); put("pending_block", block?.let(::JsonPrimitive) ?: JsonNull) }
        store.save(record); journal = record; written = true; phase = next; progress = count; publish()
    }
    private fun requireDevice(device: RingOtaDevice, owner: RingWorkoutBinding, selected: RingOtaManifest, battery: Boolean) {
        if (binding() != owner || device.address.uppercase() != owner.address.uppercase() || device.identity != selected.sourceIdentity)
            throw RingFailure("ota_identity_mismatch", RingText.text(R.string.ring_ota_60afbda560d6))
        if (battery && !selected.power.satisfied(device)) throw RingFailure("ota_power_conditions_unmet", RingText.text(R.string.ring_ota_b6e7b4d1177a))
    }
    @Synchronized private fun verifyFirmware(device: RingOtaDevice, owner: RingWorkoutBinding, original: JsonObject) {
        if (binding() != owner) throw CancellationException("Binding changed")
        val complete = RingOtaJournal.reconcile(origin, owner, original, device, java.time.Instant.now().toString())
        store.save(complete); journal = complete; phase = "complete"; reason = "firmware_readback_confirmed"; written = false; publish()
    }
    @Synchronized private fun unknown(code: String) {
        phase = "unknown"; reason = code; invalidateConfirmation()
        journal?.let { old -> val next = JsonObject(old + mapOf("phase" to JsonPrimitive("unknown"), "reason" to JsonPrimitive(code)))
            try { store.save(next); journal = next } catch (_: Exception) { storageUnavailable = true } }
        publish()
    }
    private fun download(selected: RingOtaManifest): ByteArray {
        selected.validate()
        val connection = URI(selected.officialOrigin + selected.imagePath).toURL().openConnection() as HttpURLConnection
        connection.instanceFollowRedirects = false; connection.connectTimeout = 15_000; connection.readTimeout = 15_000
        try {
            if (connection.responseCode != 200 || !RingOtaDeclaredLength.allowed(connection.contentLengthLong, selected.size))
                throw RingFailure("official_image_unavailable", RingText.text(R.string.ring_ota_0eedcd938919))
            val output = ByteArrayOutputStream(selected.size)
            connection.inputStream.use { input -> val buffer = ByteArray(8192)
                while (true) { val count = input.read(buffer); if (count < 0) break
                    if (output.size() + count > selected.size) throw RingFailure("image_size_mismatch", RingText.text(R.string.ring_ota_3f533cece48e))
                    output.write(buffer, 0, count) }
            }
            val bytes = output.toByteArray()
            if (bytes.size != selected.size || RingOtaCodec.digest(bytes) != selected.sha256)
                throw RingFailure("image_integrity_mismatch", RingText.text(R.string.ring_ota_603d93fbe623))
            return bytes
        } finally { connection.disconnect() }
    }
    private fun invalidateConfirmation(keepImage: Boolean = false) { token = null; tokenDeadline = 0; if (!keepImage) image = null }
    @Synchronized fun foregroundDetached() {
        invalidateConfirmation()
        // Confirmation hands execution to the application and FGS before the first ATT checkpoint.
        if (phase in setOf("preparing", "prepared")) { job?.cancel(); phase = "idle"; preparedOwner = null; preparedId = null }
        publish()
    }
    @Synchronized fun serviceStopped(id: String) {
        if (preparedId != id || phase !in setOf("starting", "initializing", "transferring", "checking", "finalizing", "verifying")) return
        job?.cancel()
        if (written) unknown("ota_foreground_service_stopped") else { phase = "failed"; reason = "foreground_start_denied"; publish() }
    }
    @Synchronized fun bindingChanged() {
        try { binding()?.let(store::preserveBinding) } catch (_: Exception) { storageUnavailable = true }
        job?.cancel(); invalidateConfirmation(); preparedOwner = null; preparedId = null
        if (written) unknown("ota_binding_changed") else { phase = "idle"; publish() }
    }
    fun close() {
        synchronized(this) { scope.cancel(); invalidateConfirmation() }
        // A fixture/application teardown waits until cancelled actors stop publishing to the durable store.
        runBlocking { scope.coroutineContext[Job]?.join() }
    }
}
