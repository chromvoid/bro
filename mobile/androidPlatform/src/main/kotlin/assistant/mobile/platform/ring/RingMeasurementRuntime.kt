package assistant.mobile.platform.ring

import assistant.mobile.platform.R

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import androidx.core.content.ContextCompat
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.*

/** Durable recording lifetime belongs to the application; a screen only observes its current binding. */
class RingMeasurementRuntime(
    private val context: Context,
    private val origin: String,
    private val binding: () -> RingWorkoutBinding?,
    private val capabilities: () -> JsonObject,
    private val execute: suspend (JsonObject, String, RingWorkoutBinding, ReceiveChannel<Unit>?) -> JsonObject,
    private val transferRecords: (RingWorkoutBinding, List<JsonObject>) -> RingWorkoutTransferReceipt?,
    private val foregroundUser: () -> String? = { null },
    private val foregroundVisible: () -> Boolean = { false },
    private val radioMutationPending: () -> Boolean = { false },
    private val retryTransfer: (RingWorkoutTransferRetry, () -> Unit) -> Boolean = { _, _ -> false },
    private val readClock: (() -> RingWorkoutClock)? = null,
    private val startForegroundRecording: ((String) -> Unit)? = null,
    private val locationRecorder: RingWorkoutLocationPort? = null,
) {
    init { RingText.initialize(context) }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val store = RingWorkoutStore(context, origin)
    private val activity = RingWorkoutRecognition(context, origin)
    private var storageUnavailable = false
    private var value = try { store.load() } catch (_: Exception) { storageUnavailable = true; RingWorkoutState(phase = "unavailable") }
    private var lastHr: JsonObject? = null
    private val samples = ArrayDeque<JsonObject>()
    private var gapCount = 0
    private var lastError: JsonObject? = null
    private var operation: String? = null
    private var measurementJob: Job? = null
    private var measurementResult: JsonObject? = null
    private var settings = obj {}
    private var measuredResult: JsonObject? = null
    private var pendingMeasuredResult: JsonObject? = null
    private var activeMeasurement: RingManualMeasurement? = null
    private var manualCancellation: Channel<Unit>? = null
    private var measurementIdentity: JsonObject? = null
    private var hrReason: String? = null
    private var lastFlushElapsedMs = 0L
    private var transferRetryAttempt: RingWorkoutTransferRetry? = null
    private var transferRetryWake: Job? = null
    private var transferRetryWakeBinding: RingWorkoutBinding? = null
    private val mutableMeasurements = MutableStateFlow(obj {})
    private val mutableWorkouts = MutableStateFlow(obj {})
    val measurements: StateFlow<JsonObject> get() = mutableMeasurements
    val workouts: StateFlow<JsonObject> get() = mutableWorkouts
    private var permissionBinding: RingWorkoutBinding? = null
    private var locationPermissionBinding: RingWorkoutBinding? = null
    // These are process-local receipts, never restored as authority after a process restart.
    private var recorderSession: String? = null
    private var foregroundLaunchSession: String? = null
    private var foregroundLocationSession: String? = null
    private val location by lazy { locationRecorder ?: RingWorkoutLocation(context, ::trackPoint, ::trackGap, ::publish,
        { action -> synchronized(this@RingMeasurementRuntime) { action() } }) }
    init {
        if (!storageUnavailable) try {
        value = try { RingWorkoutReducer.recover(value, clock()).state }
        catch (_: Exception) {
            lastError = error("clock_basis_unavailable", RingText.text(R.string.ring_workout_848c5fe2a2a1))
            if (value.session != null && value.phase !in setOf("idle", "unavailable"))
                value.copy(phase = "interrupted", pendingCommand = RingWorkoutCommand.STOP, commandUnknown = true) else value
        }
        store.save(value)
        value = store.load()
        } catch (_: Exception) { storageUnavailable = true; value = RingWorkoutState(phase = "unavailable") }
        if (storageUnavailable) lastError = error("workout_store_unavailable", RingText.text(R.string.ring_error_workout_store_unavailable))
        drain()
        publish()
    }
    fun clock(): RingWorkoutClock {
        readClock?.let { return it() }
        // BOOT_COUNT is non-secret and stable across process restart, unlike a process-generated UUID.
        val boot = try { Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT, -1) } catch (_: Exception) { -1 }
        val bootId = if (boot >= 0) "android-boot:$boot" else try {
            "kernel-boot:" + UUID.fromString(java.io.File("/proc/sys/kernel/random/boot_id").readText().trim()).toString()
        } catch (_: Exception) { throw RingFailure("clock_basis_unavailable", RingText.text(R.string.ring_native_b03435b728a8)) }
        val nanos = SystemClock.elapsedRealtimeNanos()
        return RingWorkoutClock(Instant.now(), nanos / 1_000_000L, bootId, nanos)
    }
    private fun error(code: String, message: String) = obj { put("code", code); put("message", message) }
    private fun invalidateMeasurementIdentity(identity: JsonObject?) {
        if (measurementIdentity != null && identity != measurementIdentity) {
            settings = obj {}; measuredResult = null; pendingMeasuredResult = null; measurementIdentity = null
            if (measurementResult?.string("status") == "verified") measurementResult = null
        }
    }
    @Synchronized private fun publish() {
        val owner = binding()
        val native = try { capabilities() } catch (_: Exception) { obj { put("capabilities", JsonArray(emptyList())) } }
        val identity = native["identity"] as? JsonObject
        invalidateMeasurementIdentity(identity)
        val session = value.session?.takeIf { it.binding == owner }
        val duration = session?.let { it.durationAt(try { clock().elapsedRealtimeMs } catch (_: Exception) { it.lastObservedElapsedMs }) }
        val state = obj {
            put("user_id", owner?.userId?.let(::JsonPrimitive) ?: JsonNull)
            put("source_id", owner?.sourceId?.let(::JsonPrimitive) ?: JsonNull)
            put("binding_generation", owner?.grantGeneration ?: 0)
            put("phase", if (value.session != null && session == null) "idle" else value.phase)
            put("workout_id", session?.id?.let(::JsonPrimitive) ?: JsonNull)
            put("ownership", if (session?.trigger == "automatic") "automatic" else "manual")
            put("trigger", session?.trigger?.let(::JsonPrimitive) ?: JsonNull)
            put("started_at", session?.let { it.anchor.utcAt.plusMillis(it.startElapsedMs - it.anchor.elapsedRealtimeMs).toString() }?.let(::JsonPrimitive) ?: JsonNull)
            put("ended_at", session?.runEndElapsedMs?.let { session.anchor.utcAt.plusMillis(it - session.anchor.elapsedRealtimeMs).toString() }?.let(::JsonPrimitive) ?: JsonNull)
            put("last_hr", if (session != null && foregroundUser() == owner?.userId) lastHr ?: JsonNull else JsonNull)
            put("gaps", if (session != null) gapCount else 0)
            put("capabilities", native["capabilities"] ?: JsonArray(emptyList()))
            put("error", lastError ?: JsonNull)
            put("command_unknown", value.commandUnknown)
            put("auto_blocked", value.autoBlocked)
            put("sport_type", session?.sportPlan?.sportType ?: "running")
            put("sport_plan", session?.sportPlan?.json() ?: JsonNull)
            put("sport_catalog", native["source_catalog"] ?: JsonArray(emptyList()))
            put("gps_requested", session?.gpsRequested == true)
            put("elapsed_seconds", duration?.elapsedSeconds?.let(::JsonPrimitive) ?: JsonNull)
            put("active_duration_seconds", duration?.activeSeconds?.let(::JsonPrimitive) ?: JsonNull)
            put("pauses", JsonArray(session?.pauses?.map { pause -> obj {
                put("start_at", session.anchor.at(pause.startElapsedMs).toString()); put("end_at", session.anchor.at(pause.endElapsedMs).toString())
            } } ?: emptyList()))
            put("paused_at", session?.pausedAtElapsedMs?.let { session.anchor.at(it).toString() }?.let(::JsonPrimitive) ?: JsonNull)
            put("recording_source", when {
                session?.sportPlan?.actualWireMode != null -> "ring_controlled"
                session?.heartRateRequested == true -> "phone_with_ring_hr"
                else -> "phone_only"
            })
            put("workout_controls", obj {
                val hostOnly = session != null && !session.sportRequested
                put("pause", session != null && value.phase == "recording" && !value.commandUnknown &&
                    (hostOnly || session.sportPlan?.let { RingSportPlan.permitted(native, it, "pause") } == true))
                put("resume", session != null && value.phase == "paused" && !value.commandUnknown &&
                    (hostOnly || session.sportPlan?.let { RingSportPlan.permitted(native, it, "resume") } == true))
            })
            put("hr_status", when {
                session == null -> "idle"
                !session.heartRateRequested -> "unavailable"
                session.heartRateLost -> if (value.commandUnknown) "unknown" else "gap"
                value.phase in setOf("idle", "interrupted") -> "stopped"
                value.commandUnknown -> "unknown"
                lastHr == null -> "waiting_sample"
                else -> "recording"
            })
            put("hr_reason", (hrReason ?: if (session?.heartRateRequested == false) "protocol_unverified" else null)?.let(::JsonPrimitive) ?: JsonNull)
            put("gps", location.state(session != null && foregroundUser() == owner?.userId))
            put("samples", JsonArray(if (session != null && foregroundUser() == owner?.userId) samples.toList() else emptyList()))
            put("activity_recognition", activity.state())
        }
        mutableWorkouts.value = state
        val foregroundOwner = owner?.takeIf { foregroundUser() == it.userId }
        mutableMeasurements.value = obj {
            put("user_id", foregroundOwner?.userId?.let(::JsonPrimitive) ?: JsonNull)
            put("source_id", foregroundOwner?.sourceId?.let(::JsonPrimitive) ?: JsonNull)
            put("binding_generation", foregroundOwner?.grantGeneration ?: 0)
            put("identity", if (foregroundOwner == null) JsonNull else native["identity"] ?: obj { put("model", "qring-r20"); put("hardware", JsonNull); put("firmware", JsonNull) })
            put("capabilities", if (foregroundOwner == null) JsonNull else native["capabilities"] ?: JsonArray(emptyList()))
            put("operation_id", operation?.takeIf { foregroundOwner != null }?.let(::JsonPrimitive) ?: JsonNull)
            put("result", if (foregroundOwner == null) JsonNull else measurementResult ?: if (operation == null && native.bool("command_unknown") == true)
                native["pending_manual_result"] as? JsonObject ?: obj { put("status", "unknown"); put("reason", "command_unknown") } else JsonNull)
            put("error", if (foregroundOwner == null) JsonNull else lastError ?: JsonNull)
            put("settings", if (foregroundOwner == null) JsonNull else settings)
            put("measured_result", if (foregroundOwner == null) JsonNull else measuredResult ?: JsonNull)
            put("active_measurement", if (foregroundOwner == null) JsonNull else activeMeasurement?.snapshot() ?: JsonNull)
            put("activity_recognition", activity.state())
        }
    }
    @Synchronized fun measurementSnapshot(): JsonObject { publish(); return mutableMeasurements.value }
    @Synchronized fun refreshFence() { publish() }
    @Synchronized fun workoutSnapshot(): JsonObject {
        value.session?.takeIf { it.binding == binding() && !it.heartRateRequested && !location.capturing() }?.let {
            if (value.phase in setOf("recording", "paused", "grace", "recovery") && !storageUnavailable)
                accept(RingWorkoutReducer.tick(value, clock()), false)
        }
        drain(); publish(); return mutableWorkouts.value
    }
    @Synchronized fun busy(): Boolean = storageUnavailable || value.phase in setOf("starting", "recording", "grace", "recovery", "pausing", "paused", "resuming", "stopping", "unknown") ||
        value.phase == "interrupted" && value.pendingCommand == RingWorkoutCommand.STOP && value.session?.binding == binding()
    @Synchronized fun serviceSession(): String? = value.session?.takeIf { it.binding == binding() &&
        (it.heartRateRequested || it.gpsRequested && recorderSession == it.id) &&
        value.phase in setOf("starting", "recording", "grace", "recovery", "pausing", "paused", "resuming", "stopping", "unknown") }?.id
    @Synchronized fun pendingCommand(): RingWorkoutCommand? = value.pendingCommand
    @Synchronized fun radioOutcomeUnknown(): Boolean = value.commandUnknown
    @Synchronized fun activeBinding(): RingWorkoutBinding? = value.session?.binding
    @Synchronized fun sportPlan(): RingSportPlan? = value.session?.sportPlan
    @Synchronized fun sportRequested(): Boolean = value.session?.sportRequested == true
    @Synchronized fun sportConfirmed(result: JsonObject) {
        if (!reload()) return
        val session = value.session ?: return
        val plan = session.sportPlan ?: return
        accept(RingWorkoutChange(value.copy(session = session.copy(sportPlan = plan.confirmed(result)))), false)
    }
    @Synchronized fun radioBusy(): Boolean = operation != null || busy() && value.session?.heartRateRequested != false
    @Synchronized fun needsHeartRateRecorder(id: String): Boolean = serviceSession() == id && value.session?.let {
        it.heartRateRequested && !it.heartRateLost || value.pendingCommand == RingWorkoutCommand.STOP && safeStopForSession()
    } == true
    private fun locationForeground(session: RingWorkoutSession): Boolean = foregroundVisible() ||
        foregroundLaunchSession == session.id || foregroundLocationSession == session.id
    @Synchronized fun locationReady(): Boolean = value.session?.let { it.gpsRequested && location.ready(locationForeground(it)) } == true
    @Synchronized fun locationFailed(reason: String) {
        foregroundLaunchSession = null
        location.unavailable(reason)
    }
    @Synchronized fun startLocation(id: String) {
        val session = value.session?.takeIf { it.id == id && it.binding == binding() } ?: return
        // The service invokes this only after Android accepted the LOCATION foreground-service type.
        if (session.gpsRequested) foregroundLocationSession = session.id
        foregroundLaunchSession = null
        if (session.gpsRequested && value.phase in setOf("starting", "recording", "grace", "recovery", "unknown") && value.pendingCommand != RingWorkoutCommand.STOP && session.pausedAtElapsedMs == null)
            location.start(session, locationForeground(session))
    }
    private fun safeStopForSession(): Boolean {
        val session = value.session ?: return false
        val native = capabilities()
        val plan = session.sportPlan ?: return RingMeasurementCommands.safeStop(native)
        val live = RingMeasurementCommands.capability(native, "live_heart_rate")
        if (live?.bool("safe_stop_verified") != true || !RingMeasurementCommands.supported(native, "live_heart_rate")) return false
        return !session.sportRequested || RingSportPlan.permitted(native, plan, "stop") && RingSportPlan.permitted(native, plan, "safe_stop_verified")
    }
    private fun requireStorage() {
        if (storageUnavailable) throw RingFailure("workout_store_unavailable", RingText.text(R.string.ring_error_workout_store_unavailable))
    }
    private fun reload(): Boolean {
        if (storageUnavailable) return false
        return try { value = store.load(); true } catch (_: Exception) {
            storageUnavailable = true; value = RingWorkoutState(phase = "unavailable"); lastHr = null
            lastError = error("workout_store_unavailable", RingText.text(R.string.ring_error_workout_store_unavailable))
            publish(); false
        }
    }
    @Synchronized private fun accept(change: RingWorkoutChange, startService: Boolean = true) {
        requireStorage()
        if (change.state == value && change.effect == null) { drain(); return }
        if (change.state.phase in setOf("idle", "interrupted") || change.state.session?.id != value.session?.id) {
            recorderSession = null; foregroundLaunchSession = null; foregroundLocationSession = null
        }
        if (change.state.phase in setOf("idle", "interrupted", "pausing", "paused", "resuming") || change.state.session?.id != value.session?.id)
            location.stop(change.state.session?.takeIf { it.id == value.session?.id }?.let {
                it.pausedAtElapsedMs ?: it.recoveryEndElapsedMs
            })
        if (change.state.phase in setOf("pausing", "paused", "resuming")) lastHr = null
        if (change.state.session?.id != value.session?.id) { samples.clear(); lastHr = null; gapCount = 0; hrReason = null }
        store.save(change.state) // Session UUID and command fence reach encrypted storage before any BLE effect.
        value = store.load()
        publish()
        if (change.effect == RingWorkoutCommand.START && startService) {
            startRecorder()
        }
        if (change.effect == RingWorkoutCommand.STOP) publish()
        drain()
    }
    private fun startRecorder() {
            val session = value.session ?: return
            if (!session.heartRateRequested && !session.gpsRequested) return // A phone elapsed timer needs no continuous Android service.
            if (session.gpsRequested && foregroundVisible() && session.binding == binding() &&
                location.access().unavailableReason(true) == null) foregroundLaunchSession = session.id
            if (!session.heartRateRequested && !location.ready(locationForeground(session))) {
                location.defer(session, location.access().unavailableReason(foregroundVisible()) ?: "location_unavailable")
                return
            }
            recorderSession = session.id // An accepted dispatch remains identifiable if permission or UI changes before onStartCommand.
            try {
                startForegroundRecording?.let { it(session.id); return }
                ContextCompat.startForegroundService(context, Intent(context, RingWorkoutService::class.java)
                    .setAction(RingWorkoutService.START).putExtra(RingWorkoutService.SESSION, session.id))
            } catch (_: RuntimeException) {
                lastError = error("foreground_start_denied", RingText.text(R.string.ring_workout_87324ca83d2b))
                accept(if (value.pendingCommand == RingWorkoutCommand.START)
                    RingWorkoutReducer.commandResult(value, RingWorkoutCommand.START, false, clock())
                    else RingWorkoutReducer.recover(value, clock()), false)
            }
    }
    @Synchronized private fun drain(retrying: Boolean = false, expected: RingWorkoutBinding? = null): Boolean {
        if (storageUnavailable) return false
        val owner = binding() ?: return true
        if (expected != null && owner != expected) return owner.uploadIdentity != expected.uploadIdentity
        // A scheduled worker owns backoff after a copy failure; UI polls cannot spin that copy.
        if (!retrying && transferRetryAttempt?.binding == owner) return false
        try {
            val records = store.pendingRecords(owner)
            if (records.isEmpty()) { transferRetryAttempt = null; transferRetryWake?.cancel(); return true }
            val ids = records.mapNotNull { it.string("external_id") }.toSet()
            val receipt = transferRecords(owner, records)
            if (receipt != null && receipt.uploadIdentity == owner.uploadIdentity &&
                receipt.externalIds == ids && ids.size == records.size && binding() == owner) {
                store.acknowledge(receipt.externalIds, owner)
                transferRetryAttempt = null; transferRetryWake?.cancel()
                return true
            }
        } catch (_: Exception) { /* The encrypted producer remains authoritative until a durable copy is confirmed. */ }
        if (retrying) {
            if (transferRetryAttempt?.binding != owner) transferRetryAttempt = RingWorkoutTransferRetry(owner, UUID.randomUUID().toString())
        } else {
            val attempt = RingWorkoutTransferRetry(owner, UUID.randomUUID().toString())
            transferRetryAttempt = attempt
            try {
                if (!retryTransfer(attempt) { transferSchedulingFailed(attempt) }) transferSchedulingFailed(attempt)
            } catch (_: Exception) { transferSchedulingFailed(attempt) }
        }
        return false
    }
    /** Worker recovery copies only sealed records, without ticking or replaying a radio command. */
    @Synchronized fun retryPendingTransfers(expected: RingWorkoutBinding): Boolean = drain(retrying = true, expected = expected)
    @Synchronized private fun transferSchedulingFailed(attempt: RingWorkoutTransferRetry) {
        if (transferRetryAttempt != attempt) return
        transferRetryAttempt = null
        // If the scheduler cannot persist work and no screen is polling, retry once per backoff while this process lives.
        if (transferRetryWake?.isActive == true && transferRetryWakeBinding == attempt.binding) return
        transferRetryWake?.cancel(); transferRetryWakeBinding = attempt.binding
        transferRetryWake = scope.launch {
            delay(15 * 60_000L)
            synchronized(this@RingMeasurementRuntime) {
                transferRetryWake = null
                if (binding() == attempt.binding && transferRetryAttempt == null) drain()
            }
        }
    }
    @Synchronized fun transferWorkerFinished(uploadIdentity: String, attemptId: String) {
        transferRetryAttempt?.takeIf { it.binding.uploadIdentity == uploadIdentity && it.id == attemptId }
            ?.let(::transferSchedulingFailed)
    }
    @Synchronized fun command(type: String, payload: JsonObject): JsonObject {
        reload()
        if (type == "ring.measurement.state") return measurementSnapshot()
        if (type == "ring.workout.state") return workoutSnapshot()
        requireStorage()
        val owner = binding() ?: throw RingFailure("not_authenticated", RingText.text(R.string.ring_native_bde2c555b593))
        if (foregroundUser() != owner.userId) throw RingFailure("not_authenticated", RingText.text(R.string.ring_native_59f65e17d26f))
        val parameters = RingMeasurementPayload.validate(payload, owner, value.session?.id,
            type in setOf("ring.workout.stop", "ring.workout.pause", "ring.workout.resume"))
        when (type) {
            "ring.measurement.state" -> return measurementSnapshot()
            "ring.workout.state" -> return workoutSnapshot()
            "ring.workout.start" -> {
                if (parameters.keys.any { it !in setOf("sport_id", "gps_requested") }) throw RingFailure("bad_payload", RingText.text(R.string.ring_workout_2b5addde63d9))
                val sportId = if ("sport_id" in parameters) (parameters["sport_id"] as? JsonPrimitive)?.takeUnless { it.isString }?.intOrNull
                    ?: throw RingFailure("bad_payload", RingText.text(R.string.ring_workout_82655bc2f690)) else 7
                val native = capabilities()
                val plan = RingSportPlan.select(native, sportId)
                val gps = if ("gps_requested" in parameters) (parameters["gps_requested"] as? JsonPrimitive)?.takeUnless { it.isString }?.booleanOrNull
                    ?: throw RingFailure("bad_payload", RingText.text(R.string.ring_workout_f01a180f9c31)) else false
                val catalog = (native["source_catalog"] as? JsonArray)?.mapNotNull { it as? JsonObject }
                    ?.singleOrNull { it["requested_id"]?.jsonPrimitive?.intOrNull == sportId }
                if (gps && catalog?.bool("app_outdoor_gps") != true)
                    throw RingFailure("bad_payload", RingText.text(R.string.ring_workout_48ba76637471))
                lastError = null
                val manualBusy = activeMeasurement != null
                val qualifiedHeartRate = RingMeasurementCommands.supported(native, "live_heart_rate")
                val heartRate = !manualBusy && !radioMutationPending() && qualifiedHeartRate
                val sport = heartRate && RingSportPlan.permitted(native, plan, "start")
                accept(RingWorkoutReducer.manualStart(value, clock(), owner, true, recordHeartRate = heartRate,
                    sportPlan = plan, sportRequested = sport, recordGps = gps))
                if (manualBusy && qualifiedHeartRate && value.session?.heartRateRequested == false)
                    hrReason = "manual_measurement_busy"
                publish(); return workoutSnapshot()
            }
            "ring.workout.stop" -> {
                if (parameters.isNotEmpty()) throw RingFailure("bad_payload", RingText.text(R.string.ring_workout_0ace8017d157))
                if (value.commandUnknown) {
                    accept(RingWorkoutReducer.stopPhoneWhileUnknown(value, clock()), false)
                    lastError = error("command_unknown", RingText.text(R.string.ring_workout_8bcc94d036d5))
                } else accept(RingWorkoutReducer.manualStop(value, clock()))
                return workoutSnapshot()
            }
            "ring.workout.pause", "ring.workout.resume" -> {
                if (parameters.isNotEmpty()) throw RingFailure("bad_payload", RingText.text(R.string.ring_workout_f1d835413623))
                val session = value.session ?: throw RingFailure("stale_workout", RingText.text(R.string.ring_workout_39dd5955bf72))
                val action = if (type == "ring.workout.pause") "pause" else "resume"
                if (session.sportRequested && (session.sportPlan?.let { RingSportPlan.permitted(capabilities(), it, action) } != true))
                    throw RingFailure("protocol_unverified", RingText.text(R.string.ring_workout_ac742da494bf))
                accept(if (action == "pause") RingWorkoutReducer.pause(value, clock()) else RingWorkoutReducer.resume(value, clock()))
                if (action == "resume" && value.phase == "recording") startRecorder()
                return workoutSnapshot()
            }
            "ring.measurement.cancel" -> {
                RingMeasurementPayload.cancelOperation(parameters, activeMeasurement?.operationId)
                val channel = manualCancellation ?: throw RingFailure("stale_measurement", RingText.text(R.string.ring_native_74efddb23987))
                if (!channel.trySend(Unit).isSuccess) throw RingFailure("stale_measurement", RingText.text(R.string.ring_native_74efddb23987))
                return measurementSnapshot()
            }
            "ring.measurement.configure", "ring.measurement.measure", "ring.measurement.read" -> {
                if (radioBusy()) throw RingFailure("workout_busy", RingText.text(R.string.ring_workout_39e65cf8cb87))
                if (radioMutationPending()) throw RingFailure("device_command_unknown", RingText.text(R.string.ring_native_a3593b9d010e))
                if (operation != null) throw RingFailure("busy", RingText.text(R.string.ring_native_0b1f107452b4))
                val commands = RingMeasurementCommands.parse(type, parameters, capabilities())
                val requestedIdentity = capabilities()["identity"] as? JsonObject
                val id = UUID.randomUUID().toString()
                val manual = commands.singleOrNull()?.takeIf { it.string("type") == "measure_once" }
                val cancellation = if (manual != null) Channel<Unit>(Channel.CONFLATED) else null
                if (manual != null) {
                    measuredResult = null; pendingMeasuredResult = null
                    activeMeasurement = RingManualMeasurement(id, manual, clock())
                    manualCancellation = cancellation
                }
                operation = id; measurementResult = null; lastError = null; publish()
                measurementJob = scope.launch {
                    var currentCommand = commands.first()
                    var currentId = if (manual != null) id else "$id:0"
                    try {
                        for ((index, command) in commands.withIndex()) {
                            currentCommand = command
                            currentId = if (manual != null) id else "$id:$index"
                            synchronized(this@RingMeasurementRuntime) {
                                if (binding() != owner || operation != id) throw CancellationException("Binding changed")
                            }
                            val reply = execute(command, currentId, owner, cancellation)
                            if (reply.string("operation_id") != currentId || reply["command"] != command)
                                throw RingFailure("native_response", RingText.text(R.string.ring_native_40ea7ee0699f))
                            var identityMatches = false
                            synchronized(this@RingMeasurementRuntime) {
                                if (binding() != owner || operation != id) return@synchronized
                                identityMatches = requestedIdentity != null && capabilities()["identity"] == requestedIdentity
                                measurementResult = if (reply.string("status") == "verified" && !identityMatches) null
                                    else JsonObject(reply.filterKeys { it in setOf("status", "reason", "operation_id", "command") } +
                                        mapOf("operation_id" to JsonPrimitive(currentId), "command" to command))
                                if (manual != null) measuredResult = pendingMeasuredResult?.takeIf {
                                    reply.string("status") == "verified" && reply.string("reason") == "measurement_received" && identityMatches
                                }
                                if (identityMatches && reply.string("status") == "verified") measurementIdentity = requestedIdentity
                                (reply["settings"] as? JsonObject)?.let { setting ->
                                    val kind = setting.string("kind") ?: return@let
                                    if (identityMatches && reply.string("status") == "verified" && reply.string("observed_at") != null) {
                                        measurementIdentity = requestedIdentity
                                        settings = JsonObject(settings + (kind to
                                            JsonObject(setting.filterKeys { it in setOf("kind", "enabled", "interval_minutes", "heart_rate_alerts") } +
                                                mapOf("interval_minutes" to (setting["interval_minutes"] ?: JsonNull),
                                                    "confirmed_at" to reply["observed_at"]!!, "readback" to JsonPrimitive(true)))))
                                    }
                                }
                                if (reply.string("status") == "verified" && !identityMatches) {
                                    lastError = error("identity_changed", RingText.text(R.string.ring_native_b0689450a0aa))
                                } else if (reply.string("status") != "verified") {
                                    lastError = error(reply.string("reason") ?: "protocol_unverified", RingText.text(R.string.ring_native_9dc65e602a93))
                                }
                            }
                            if (reply.string("status") != "verified" || !identityMatches) break
                        }
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (failure: Exception) {
                        synchronized(this@RingMeasurementRuntime) {
                            if (binding() == owner && operation == id) {
                                measurementResult = obj { put("status", "unknown"); put("reason", (failure as? RingFailure)?.code ?: "unavailable")
                                    put("operation_id", currentId); put("command", currentCommand) }
                                if (manual != null) { measuredResult = null; pendingMeasuredResult = null }
                                lastError = error("command_unknown", RingText.text(R.string.ring_native_21b7aeee54cf))
                            }
                        }
                    } finally {
                        cancellation?.close()
                        synchronized(this@RingMeasurementRuntime) {
                            if (binding() == owner && operation == id) {
                                operation = null; activeMeasurement = null; pendingMeasuredResult = null; manualCancellation = null; publish()
                            }
                        }
                    }
                }
                return measurementSnapshot()
            }
            else -> throw RingFailure("unknown_type", RingText.text(R.string.ring_native_f73c727b06da))
        }
    }
    @Synchronized fun transitions(events: List<RingRunningTransition>) {
        val owner = binding() ?: return
        if (!reload()) return
        val manualBusy = activeMeasurement != null
        val qualifiedHeartRate = RingMeasurementCommands.workoutSupported(capabilities())
        val heartRate = !manualBusy && !radioMutationPending() && qualifiedHeartRate
        if (Build.VERSION.SDK_INT >= 33 && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val gps = location.access().unavailableReason(foregroundVisible()) == null
        accept(RingWorkoutReducer.transitions(value, events, clock(), owner, heartRate || gps, recordHeartRate = heartRate, recordGps = gps))
        if (manualBusy && qualifiedHeartRate && value.session?.heartRateRequested == false) {
            hrReason = "manual_measurement_busy"; publish()
        }
    }
    @Synchronized fun tick() {
        if (!reload()) return
        value.session?.takeIf { it.binding == binding() }?.let(location::tick)
        if (!reload()) return
        val now = clock()
        accept(RingWorkoutReducer.tick(value, now))
        if (now.elapsedRealtimeMs - lastFlushElapsedMs >= 60_000) { store.flush(now); lastFlushElapsedMs = now.elapsedRealtimeMs }
    }
    @Synchronized fun commandResult(command: RingWorkoutCommand, accepted: Boolean?) {
        if (!reload()) return
        if (accepted == null) lastError = error("command_unknown", RingText.text(R.string.ring_workout_3954b833d8dc))
        if (command == RingWorkoutCommand.START && accepted != true && location.capturing()) {
            hrReason = if (accepted == null) "command_unknown" else "start_rejected"
            accept(RingWorkoutReducer.radioLost(value, accepted == null), false); return
        }
        if (command == RingWorkoutCommand.STOP && accepted != true && value.session?.runEndElapsedMs != null) {
            val changed = RingWorkoutReducer.commandResult(value, command, accepted, clock())
            accept(changed.copy(state = changed.state.copy(phase = "interrupted", session = changed.state.session?.copy(status = "interrupted"))), false)
            return
        }
        accept(RingWorkoutReducer.commandResult(value, command, accepted, clock()), false)
    }
    @Synchronized fun point(clock: RingWorkoutClock, bpm: Int, quality: String) {
        val owner = binding() ?: return
        if (!reload()) return
        if (value.session?.binding != owner || value.phase !in setOf("recording", "grace", "recovery")) return
        if (!store.appendPoint(owner, clock, bpm, quality)) return
        lastHr = obj {
            put("bpm", bpm); put("observed_at", value.session!!.anchor.at(clock.elapsedRealtimeMs).toString()); put("elapsed_realtime_ms", clock.elapsedRealtimeMs)
            put("quality", quality); put("phase", if (value.phase in setOf("grace", "recovery")) "recovery" else "running")
        }
        lastHr?.let { sample -> if (samples.lastOrNull() != sample) { samples.addLast(sample); while (samples.size > 300) samples.removeFirst() } }
        publish() // appendPoint has already committed authenticated ciphertext.
    }
    @Synchronized fun interrupted(reason: String) {
        if (!reload()) return
        val session = value.session ?: return
        val now = clock()
        lastHr = null; gapCount++
        val gapStart = session.lastObservedElapsedNanos / 1_000_000 + 1
        if (session.binding == binding() && now.bootId == session.anchor.bootId && now.elapsedRealtimeMs > gapStart)
            store.appendGap(session.binding, RingWorkoutClock(session.anchor.at(gapStart), gapStart, now.bootId), now.elapsedRealtimeMs, reason)
        value = store.load()
        if (reason == "bluetooth_disconnected" && location.capturing()) {
            hrReason = reason
            accept(RingWorkoutReducer.radioLost(value, true), false); publish(); return
        }
        lastError = error(reason, RingText.text(R.string.ring_workout_572659ce9ac5))
        val recovered = RingWorkoutReducer.recover(value, now)
        accept(if (session.status != "interrupted") recovered.copy(state = recovered.state.copy(
            session = recovered.state.session?.copy(stopReason = reason))) else recovered, false)
        publish()
    }
    @Synchronized fun closeBinding(owner: RingWorkoutBinding) { store.closeBinding(owner) }
    @Synchronized fun bindingChanged(clear: Boolean, clearBinding: RingWorkoutBinding? = binding()) {
        manualCancellation?.close(); manualCancellation = null; activeMeasurement = null
        measurementJob?.cancel(); measurementJob = null
        activity.unregister()
        if (busy()) interrupted("binding_changed")
        recorderSession = null; foregroundLaunchSession = null; foregroundLocationSession = null
        context.stopService(Intent(context, RingWorkoutService::class.java))
        lastHr = null; operation = null; measurementResult = null; measuredResult = null; pendingMeasuredResult = null; settings = obj {}; measurementIdentity = null; hrReason = null
        permissionBinding = null; locationPermissionBinding = null
        if (clear) {
            if (storageUnavailable) throw RingFailure("workout_store_unavailable", RingText.text(R.string.ring_error_workout_store_unavailable))
            store.clear(clearBinding)
            value = store.load(); lastError = null
        }
        publish()
    }
    @Synchronized fun foregroundDetached() {
        manualCancellation?.close(); manualCancellation = null; activeMeasurement = null
        measurementJob?.cancel(); measurementJob = null
        lastHr = null; operation = null; measurementResult = null; measuredResult = null; pendingMeasuredResult = null; settings = obj {}; measurementIdentity = null
        publish()
    }
    @Synchronized fun refreshBinding() {
        val owner = binding()
        if (!storageUnavailable && owner != null && activity.permissionGranted()) activity.register(owner)
        publish(); drain()
    }
    @Synchronized fun permissionNames(payload: JsonObject): Array<String> {
        requireStorage()
        val owner = binding() ?: throw RingFailure("not_authenticated", RingText.text(R.string.ring_native_59f65e17d26f))
        if (foregroundUser() != owner.userId) throw RingFailure("not_authenticated", RingText.text(R.string.ring_native_59f65e17d26f))
        if (RingMeasurementPayload.validate(payload, owner).isNotEmpty()) throw RingFailure("bad_payload", RingText.text(R.string.ring_workout_955e5cacd621))
        permissionBinding = owner
        return if (Build.VERSION.SDK_INT >= 29) arrayOf(Manifest.permission.ACTIVITY_RECOGNITION) else emptyArray()
    }
    @Synchronized fun permissionResult(granted: Boolean) {
        val owner = permissionBinding; permissionBinding = null
        if (granted && owner != null && binding() == owner) activity.register(owner)
        publish()
    }
    @Synchronized fun locationPermissionNames(payload: JsonObject): Array<String> {
        val owner = binding() ?: throw RingFailure("not_authenticated", RingText.text(R.string.ring_native_59f65e17d26f))
        if (foregroundUser() != owner.userId || !foregroundVisible()) throw RingFailure("not_authenticated", RingText.text(R.string.ring_workout_1ac392cf374c))
        val parameters = RingMeasurementPayload.validate(payload, owner)
        if (parameters.keys.any { it != "background" } || parameters["background"] != null &&
            (parameters["background"] as? JsonPrimitive)?.takeUnless { it.isString }?.booleanOrNull == null)
            throw RingFailure("bad_payload", RingText.text(R.string.ring_workout_6b586a6133a3))
        locationPermissionBinding = owner
        if (parameters.bool("background") == true) {
            if (location.access().permission == "denied") throw RingFailure("foreground_permission_required", RingText.text(R.string.ring_workout_d7ed2ddae54b))
            return if (Build.VERSION.SDK_INT == 29) arrayOf(Manifest.permission.ACCESS_BACKGROUND_LOCATION) else emptyArray()
        }
        return arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION)
    }
    @Synchronized fun locationPermissionResult() {
        val owner = locationPermissionBinding; locationPermissionBinding = null
        if (owner != null && owner == binding()) { publish(); if (serviceSession() != null) startRecorder() }
    }
    @Synchronized fun locationStateChanged() { publish(); if (foregroundVisible() && serviceSession() != null) startRecorder() }
    @Synchronized fun manualProgress(effect: JsonObject, expected: RingWorkoutBinding, observedIdentity: JsonObject) {
        if (binding() != expected || foregroundUser() != expected.userId || capabilities()["identity"] != observedIdentity) return
        val current = activeMeasurement?.takeIf { operation == it.operationId } ?: return
        activeMeasurement = current.progress(effect) ?: return
        publish()
    }
    @Synchronized fun manualObservation(record: JsonObject, expected: RingWorkoutBinding, observedIdentity: JsonObject, operationId: String) {
        if (binding() != expected || foregroundUser() != expected.userId || capabilities()["identity"] != observedIdentity) return
        if (activeMeasurement?.let { operation == operationId && it.operationId == operationId &&
            it.command["metric"] == record["metric"] && it.phase == "stopping" && it.stopReason == "result" } != true) return
        invalidateMeasurementIdentity(observedIdentity)
        measurementIdentity = observedIdentity
        pendingMeasuredResult = obj {
            put("metric", record["metric"]!!); put("value", record["value"]!!); put("unit", record["unit"]!!)
            put("measured_at", record["start_at"]!!); put("quality", record["quality"]!!)
            put("external_id", record["external_id"]!!); put("timestamp_basis", "phone_received")
        }
        // The immutable sample is already queued, but the UI waits for the matching terminal receipt.
    }
    @Synchronized private fun trackPoint(session: RingWorkoutSession, point: RingTrackPoint): Boolean {
        val owner = binding() ?: return false
        if (session.binding != owner || !reload() || value.session?.id != session.id) return false
        return store.appendTrackPoint(owner, RingWorkoutClock(session.anchor.at(point.elapsedRealtimeMs), point.elapsedRealtimeMs,
            session.anchor.bootId, point.elapsedRealtimeNanos), point)
    }
    @Synchronized private fun trackGap(session: RingWorkoutSession, start: Long, end: Long, reason: String) {
        val owner = binding() ?: return
        if (end <= start || session.binding != owner || !reload() || value.session?.id != session.id) return
        store.appendTrackGap(owner, RingWorkoutClock(session.anchor.at(start), start, session.anchor.bootId), end, reason)
    }
    fun close() {
        synchronized(this) {
            manualCancellation?.close(); manualCancellation = null; activeMeasurement = null; pendingMeasuredResult = null
            location.stop(); scope.cancel(); activity.unregister()
        }
        runBlocking { scope.coroutineContext[Job]?.join() }
        store.close()
    }
    @Synchronized fun stopFromNotification() {
        if (!reload()) return
        if (value.session?.binding == binding()) accept(RingWorkoutReducer.manualStop(value, clock()))
    }
}
