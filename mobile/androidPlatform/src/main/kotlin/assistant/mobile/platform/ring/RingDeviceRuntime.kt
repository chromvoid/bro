package assistant.mobile.platform.ring

import assistant.mobile.platform.R

import android.content.Context
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.*

/** Application-owned device settings, receipts and local physical events. */
class RingDeviceRuntime(
    private val context: Context, origin: String,
    private val binding: () -> RingWorkoutBinding?, private val capabilities: () -> JsonObject,
    private val execute: suspend (JsonObject, String, RingWorkoutBinding, Boolean) -> JsonObject,
    private val foregroundUser: () -> String?, private val radioBusy: () -> Boolean,
    private val lifecycle: (String, RingWorkoutBinding) -> Unit = { _, _ -> },
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val store = RingDeviceStore(context, origin)
    private var storageUnavailable = false
    private var error: JsonObject? = null
    private val events = RingDeviceEvents(origin)
    private var settings: JsonObject = obj {}
    private var wireStates: JsonObject = obj {}
    private var settingsIdentity: JsonObject? = null
    private var result: JsonObject? = null
    private var operation: String? = null
    private var job: Job? = null
    private val mutable = MutableStateFlow(obj {})
    val states: StateFlow<JsonObject> get() = mutable
    init {
        try { binding()?.let { store.recoveries(it) } }
        catch (_: Exception) { storageUnavailable = true; error = failure("device_store_unavailable", RingText.text(R.string.ring_native_453d24790ede)) }
        publish()
    }
    private fun failure(code: String, message: String) = obj { put("code", code); put("message", message) }
    private fun native() = try { capabilities() } catch (_: Exception) {
        obj { put("identity", JsonNull); put("capabilities", JsonArray(emptyList())) }
    }
    @Synchronized private fun pending(owner: RingWorkoutBinding?): List<JsonObject> = if (owner == null || storageUnavailable) emptyList()
        else try { store.recoveries(owner) } catch (_: Exception) { storageUnavailable = true; emptyList() }
    @Synchronized private fun fenced(owner: RingWorkoutBinding?): Boolean = owner != null && try { store.blocked(owner) }
        catch (_: Exception) { storageUnavailable = true; true }
    @Synchronized fun busy(): Boolean = storageUnavailable || operation != null || fenced(binding())
    @Synchronized fun refreshFence() { publish() }
    @Synchronized fun snapshot(): JsonObject { publish(); return mutable.value }
    @Synchronized private fun publish() {
        val owner = binding()?.takeIf { foregroundUser() == it.userId }
        val native = native()
        if (settingsIdentity != null && native["identity"] != settingsIdentity) {
            settings = obj {}; wireStates = obj {}; settingsIdentity = null
            if (result?.string("status") == "verified") result = null
        }
        val recoveries = pending(owner)
        val physicalFence = if (owner == null) obj { put("blocked", false); put("reason", JsonNull); put("refresh_required", false); put("can_reconcile", false) }
            else try { store.physicalFence(owner) } catch (_: Exception) { storageUnavailable = true
                obj { put("blocked", true); put("reason", "device_store_unavailable"); put("refresh_required", false); put("can_reconcile", false) } }
        val pendingOperations = if (owner == null || storageUnavailable) JsonArray(emptyList()) else try { store.pendingOperations(owner) }
            catch (_: Exception) { storageUnavailable = true; JsonArray(emptyList()) }
        val interrupted = recoveries.lastOrNull()?.let { recovery ->
            val request = recovery["request"] as? JsonObject
            val id = request?.string("operation_id")
            val command = request?.get("command") as? JsonObject
            if (id.isNullOrBlank() || command == null) { storageUnavailable = true; null }
            else obj { put("operation_id", id); put("command", command); put("status", "unknown"); put("reason", "process_interrupted") }
        }
        mutable.value = obj {
            put("user_id", owner?.userId?.let(::JsonPrimitive) ?: JsonNull)
            put("source_id", owner?.sourceId?.let(::JsonPrimitive) ?: JsonNull)
            put("binding_generation", owner?.grantGeneration ?: 0)
            put("identity", if (owner != null) native["identity"] ?: JsonNull else JsonNull)
            put("capabilities", if (owner != null) native["capabilities"] ?: JsonArray(emptyList()) else JsonArray(emptyList()))
            put("settings", if (owner != null) settings else obj {})
            put("physical_fence", physicalFence); put("pending_operations", pendingOperations)
            put("wire_states", if (owner != null) wireStates else obj {})
            put("operation_id", operation?.takeIf { owner != null }?.let(::JsonPrimitive) ?: JsonNull)
            put("result", if (owner != null && !storageUnavailable) result ?: interrupted ?: JsonNull else JsonNull)
            put("events", if (owner != null) events.snapshot() else RingDeviceEvents("").snapshot())
            put("error", error ?: if (storageUnavailable) failure("device_store_unavailable", RingText.text(R.string.ring_native_13df7f4c7df0)) else JsonNull)
        }
    }
    private fun owner(payload: JsonObject): Pair<RingWorkoutBinding, JsonObject> {
        val owner = binding() ?: throw RingFailure("not_authenticated", RingText.text(R.string.ring_native_59f65e17d26f))
        if (foregroundUser() != owner.userId) throw RingFailure("not_authenticated", RingText.text(R.string.ring_native_2c01a5684186))
        return owner to RingMeasurementPayload.validate(payload, owner)
    }
    @Synchronized fun command(type: String, payload: JsonObject): JsonObject {
        if (type == "ring.device.state") return snapshot()
        val (owner, fields) = owner(payload)
        if (storageUnavailable) throw RingFailure("device_store_unavailable", RingText.text(R.string.ring_native_13df7f4c7df0))
        when (type) {
            "ring.device.refresh" -> {
                if (fields.isNotEmpty()) throw RingFailure("bad_payload", RingText.text(R.string.ring_native_71905f384372))
                if (operation != null || radioBusy()) throw RingFailure("ring_busy", RingText.text(R.string.ring_native_ed3bd83b34b7))
                start(owner, obj { put("type", "identity_probe") }, UUID.randomUUID().toString(), false); return snapshot()
            }
            "ring.device.reconcile" -> {
                if (fields.keys != setOf("operation_id")) throw RingFailure("bad_payload", RingText.text(R.string.ring_native_2f2e943e6490))
                val id = fields.string("operation_id") ?: throw RingFailure("bad_payload", RingText.text(R.string.ring_native_861cda0e1c5e))
                val recovery = store.recovery(owner, id)
                val control = if (recovery == null) store.controlRecovery(owner, id) else null
                val command = if (control != null) obj { put("type", "reconcile_control"); put("operation_id", id) }
                    else ((recovery?.get("request") as? JsonObject)?.get("command") as? JsonObject)
                        ?: throw RingFailure("operation_unknown", RingText.text(R.string.ring_native_f8a2b64606b2))
                if (operation != null || radioBusy()) throw RingFailure("ring_busy", RingText.text(R.string.ring_native_ed3bd83b34b7))
                start(owner, command, id, true); return snapshot()
            }
            "ring.device.read", "ring.device.update", "ring.device.action" -> {
                val command = RingDeviceCommands.parse(type, fields)
                RingDeviceCommands.requireQualified(native(), command)
                if (operation != null || radioBusy() ||
                    command.string("type") != "read" && fenced(owner))
                    throw RingFailure("ring_busy", RingText.text(R.string.ring_native_babf11fd41f6))
                start(owner, command, UUID.randomUUID().toString(), false); return snapshot()
            }
            else -> throw RingFailure("unknown_type", RingText.text(R.string.ring_native_d4f4c2f4f3eb))
        }
    }
    private fun start(owner: RingWorkoutBinding, command: JsonObject, id: String, reconcile: Boolean) {
        val requestedIdentity = native()["identity"] as? JsonObject
        operation = id; result = null; error = null
        if (!reconcile && command.string("action") == "tasbih_start") events.localStart()
        publish()
        job = scope.launch {
            try {
                val reply = execute(command, id, owner, reconcile)
                synchronized(this@RingDeviceRuntime) {
                    if (binding() != owner || operation != id) return@synchronized
                    val presented = if (command.string("type") == "reconcile_control") JsonObject(reply + ("command" to command)) else reply
                    val identityMatches = requestedIdentity != null && native()["identity"] == requestedIdentity
                    result = JsonObject(presented.filterKeys { it in setOf("operation_id", "command", "status", "reason", "snapshot", "wire_state", "observed_at") &&
                        (identityMatches || it !in setOf("snapshot", "wire_state")) })
                    if (reply.string("status") == "verified") {
                        if (identityMatches) settingsIdentity = requestedIdentity
                        (reply["snapshot"] as? JsonObject)?.takeIf { identityMatches }?.let { snapshot ->
                            val kind = snapshot.string("kind") ?: return@let
                            settings = JsonObject(settings + (kind to snapshot))
                        }
                        (reply["wire_state"] as? JsonObject)?.takeIf { identityMatches }?.let { wire ->
                            val kind = wire.string("kind") ?: return@let
                            wireStates = JsonObject(wireStates + (kind to wire))
                        }
                        (command.string("action"))?.takeIf { it in setOf("unbind", "factory_reset", "power_off") }?.let {
                            lifecycle(it, owner) // Only qualified semantic receipts trigger repository cleanup.
                        }
                    } else error = failure(reply.string("reason") ?: "command_unknown", RingText.text(R.string.ring_native_58de6c584dd5))
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (problem: Exception) {
                synchronized(this@RingDeviceRuntime) {
                    if (binding() == owner && operation == id) {
                        result = obj { put("operation_id", id); put("command", command); put("status", "unknown")
                            put("reason", (problem as? RingFailure)?.code ?: "transport_lost") }
                        error = failure("command_unknown", RingText.text(R.string.ring_native_2c5765229e02))
                    }
                }
            } finally { synchronized(this@RingDeviceRuntime) { if (binding() == owner && operation == id) { operation = null; publish() } } }
        }
    }
    /** Called only by the current native GATT executor; there is no WebView event ingress. */
    @Synchronized fun acceptEvent(owner: RingWorkoutBinding, generation: Long, physicalSourceId: String, event: JsonObject) {
        if (binding() != owner || foregroundUser()?.let { it != owner.userId } == true) return
        val identity = native()["identity"] as? JsonObject ?: return
        if (events.accept(owner, generation, physicalSourceId, identity, event, android.os.SystemClock.elapsedRealtime(),
                java.time.Instant.now().toString())) publish()
    }
    @Synchronized fun eventDisconnected() { events.reset(); publish() }
    @Synchronized fun bindingChanged(clear: Boolean) {
        job?.cancel(); operation = null; result = null; settings = obj {}; wireStates = obj {}; settingsIdentity = null; events.reset()
        // Forgetting the Bro binding cannot prove the outcome of a previous ring write.
        // Keep its encrypted owner-scoped recovery/idempotency journal; never replay it.
        publish()
    }
    @Synchronized fun foregroundDetached() {
        job?.cancel(); operation = null; result = null; settings = obj {}; wireStates = obj {}; settingsIdentity = null; events.reset(); publish()
    }
    fun close() {
        synchronized(this) { scope.cancel() }
        // Publishers acquire this runtime's monitor while unwinding, so join outside it.
        runBlocking { scope.coroutineContext[Job]?.join() }
    }
}
