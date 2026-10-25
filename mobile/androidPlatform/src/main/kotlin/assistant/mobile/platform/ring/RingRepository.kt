package assistant.mobile.platform.ring

import assistant.mobile.platform.R
import assistant.mobile.platform.AndroidNativeOrigins

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import android.net.ConnectivityManager
import androidx.work.*
import java.time.*
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*

sealed interface RingServerSwitchPreparation {
    data object FirmwareUpdateActive : RingServerSwitchPreparation
    class Ready internal constructor(private val erase: suspend () -> Unit, private val revokeGrant: suspend () -> Unit) : RingServerSwitchPreparation {
        suspend fun clear() = erase()
        suspend fun revoke() = revokeGrant()
    }
}

/** Application-owned BLE/outbox executor; native screens only observe its session-bound state. */
class RingRepository(private val context: Context, private val origin: String,
    private val lock: Mutex = Mutex(),
    private val enqueueOneTimeWork: ((String, ExistingWorkPolicy, OneTimeWorkRequest, () -> Unit) -> Unit)? = null) : RingController, RingPhoneController, RingOtaController {
    private val uploadLock = Mutex()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val uploadRetryMonitor = Any()
    private var uploadRetryWake: Job? = null
    private var uploadRetryOwner: String? = null
    private val store = RingStore(context, origin)
    private val diagnostics = try { RingDiagnostics(context, origin) } catch (_: Exception) { null }
    @Volatile private var storageUnavailable = false
    @Volatile private var forgetPending = false
    @Volatile private var serverSwitchStarted = false
    private var serverSwitchPreparation: RingServerSwitchPreparation.Ready? = null
    private val http = RingHttp(origin, serverOrigin = { AndroidNativeOrigins.serverOrigin(context, origin) })
    private val generation = AtomicLong(1)
    private val tasks = RingTaskLifetime(scope)
    @Volatile private var currentGatt: RingGatt? = null
    @Volatile private var foregroundGatt: RingGatt? = null
    private val account = RingAccountOwner {
        tasks.cancelForeground(); foregroundGatt?.close(); foregroundGatt = null
    }
    @Volatile private var nativeSession: RingSession? = null
    @Volatile private var sessionCurrent: () -> Boolean = { false }
    @Volatile private var foregroundVisible = false
    @Volatile private var workoutJob: Job? = null
    private val workoutScreenLease = MutableStateFlow<RingWorkoutScreenLease?>(null)
    private var workoutPermissionLease: Pair<RingWorkoutPermission, RingWorkoutScreenLease>? = null
    private val reportedUser: String? get() = account.user
    private val measurementRuntime: Lazy<RingMeasurementRuntime> = lazy { RingMeasurementRuntime(context, origin, ::workoutBinding, ::measurementCapabilities,
        ::executeMeasurement, ::queueWorkoutRecords, { reportedUser }, { foregroundVisible || workoutScreenLease.value != null }, { deviceMutationPending() || otaRadioBlocked() || phoneNotifications.busy() },
        ::enqueueWorkoutTransfer) }
    private val measurements: RingMeasurementRuntime by measurementRuntime
    private val otaRuntime: Lazy<RingOtaRuntime> = lazy { RingOtaRuntime(context, origin, ::workoutBinding, { reportedUser },
        { tasks.busy || lock.isLocked || measurements.radioBusy() }, otaTransport) }
    private val ota: RingOtaRuntime by otaRuntime
    private val otaTransport: RingOtaTransport = object : RingOtaTransport {
        override suspend fun exclusive(binding: RingWorkoutBinding, operation: suspend (RingOtaLink) -> Unit) {
            val foregroundOwner = account.capture()
            lock.withLock {
                account.requireCurrent(foregroundOwner)
                if (workoutBinding() != binding) throw CancellationException("OTA binding changed")
                if (measurements.radioBusy() || deviceMutationPending() || store.get("control_recovery") is JsonObject)
                    throw RingFailure("ring_busy", RingText.text(R.string.ring_ota_transport_28f62dea6d74))
                OtaLink(generation.get(), binding, foregroundOwner).use { operation(it) }
            }
        }
        override suspend fun readOnly(binding: RingWorkoutBinding, operation: suspend (RingOtaReadLink) -> Unit) {
            val foregroundOwner = account.capture()
            lock.withLock {
                currentCoroutineContext().ensureActive(); account.requireCurrent(foregroundOwner)
                if (workoutBinding() != binding) throw CancellationException("OTA binding changed")
                if (measurements.radioBusy()) throw RingFailure("ring_busy", RingText.text(R.string.ring_ota_transport_398d56f48337))
                // Read-only GATT version facts do not resolve or replay other unknown mutations.
                OtaLink(generation.get(), binding, foregroundOwner, readOnly = true).use { operation(it) }
            }
        }
    }

    private fun otaRadioBlocked(readOnly: Boolean = false): Boolean {
        val state = ota.states.value
        return state.active || !readOnly && state.physicalFence.blocked
    }
    private val deviceStore: RingDeviceStore by lazy { RingDeviceStore(context, origin) }
    private val deviceRuntime: Lazy<RingDeviceRuntime> = lazy { RingDeviceRuntime(context, origin, ::workoutBinding, ::deviceCapabilities,
        ::executeDevice, { reportedUser }, { tasks.busy || lock.isLocked || measurements.radioBusy() || phoneNotifications.busy() },
        { action, owner -> if (workoutBinding() == owner) {
            if (action in setOf("unbind", "factory_reset")) forget()
            else if (action == "power_off") { measurements.interrupted("ring_powered_off"); currentGatt?.close() }
        } }) }
    private val devices: RingDeviceRuntime by deviceRuntime
    private val notificationRuntime: Lazy<RingNotificationRuntime> = lazy { RingNotificationRuntime(context, origin, ::workoutBinding,
        { reportedUser }, ::deviceCapabilities,
        { tasks.busy || lock.isLocked || measurements.radioBusy() || devices.busy() || otaRadioBlocked() }, ::executeDevice) }
    private val phoneNotifications: RingNotificationRuntime by notificationRuntime
    private val gestureRuntime: Lazy<RingGestureRuntime> = lazy { RingGestureRuntime(context, origin, ::workoutBinding, { reportedUser }, ::deviceCapabilities) }
    private val phoneGestures: RingGestureRuntime by gestureRuntime
    override val notificationStates: StateFlow<RingNotificationState> get() = phoneNotifications.states
    override val gestureStates: StateFlow<RingGestureState> get() = phoneGestures.states
    override fun notification(action: RingNotificationAction) { ownedPayload(obj {}); phoneNotifications.action(action) }
    override fun gesture(action: RingGestureActionRequest) { ownedPayload(obj {}); phoneGestures.action(action) }
    override fun refreshPhoneControls() { if (!serverSwitchStarted) { phoneNotifications.refresh(); phoneGestures.refresh() } }
    private fun detachPhoneControls() {
        phoneNotifications.foregroundDetached(); phoneGestures.foregroundDetached(); ota.foregroundDetached()
    }
    private fun resetPhoneBinding(clear: Boolean) {
        phoneNotifications.bindingChanged(clear); phoneGestures.bindingChanged(clear); ota.bindingChanged()
    }
    private val mutableState = MutableStateFlow(loadState())
    override val states: StateFlow<RingNativeState> = mutableState.map { RingNativeState.fromJson(it) }
        .stateIn(scope, SharingStarted.Eagerly, RingNativeState.fromJson(mutableState.value))
    override val deviceStates: StateFlow<RingDeviceState> by lazy {
        devices.states.map { RingDeviceState.fromJson(it) }.stateIn(scope, SharingStarted.Eagerly, RingDeviceState.fromJson(devices.snapshot()))
    }
    override val measurementStates: StateFlow<RingMeasurementState> by lazy {
        measurements.measurements.map { RingMeasurementState.fromJson(it) }
            .stateIn(scope, SharingStarted.Eagerly, RingMeasurementState.fromJson(measurements.measurementSnapshot()))
    }
    override val workoutStates: StateFlow<RingWorkoutNativeState> by lazy {
        measurements.workouts.combine(workoutScreenLease) { state, lease ->
            val owner = workoutBinding()
            if (lease == null || !lease.canRead || !sessionCurrent() || reportedUser != lease.userId ||
                owner?.userId != lease.userId || owner.sourceId != lease.sourceId || owner.grantGeneration != lease.grantGeneration)
                RingWorkoutNativeState()
            else RingWorkoutNativeState.fromJson(state)
        }.stateIn(scope, SharingStarted.Eagerly, RingWorkoutNativeState())
    }
    private var stateValue = RingState()
    private val workoutGrantGuard = RingWorkoutGrantGuard(
        current = { measurements.activeBinding()?.takeIf { it == workoutBinding() && measurements.busy() } },
        verify = ::verifyWorkoutGrant,
        revoked = { owner -> revokeWorkoutBinding(owner) },
    )
    private suspend fun verifyWorkoutGrant(expected: RingWorkoutBinding): RingWorkoutGrantStatus {
        if (context.getSystemService(ConnectivityManager::class.java)?.activeNetwork == null)
            return RingWorkoutGrantStatus.UNAVAILABLE
        val captured = grant()?.takeIf { workoutBinding() == expected } ?: return RingWorkoutGrantStatus.UNAVAILABLE
        return try {
            val reply = http.request("GET", "/v1/personal/rings/collector/state", token = captured.token, timeoutMs = 1_500)
            when (reply.status) {
                401, 403, 404 -> RingWorkoutGrantStatus.REVOKED
                200 -> if (reply.value.string("source_id") == captured.source &&
                    reply.value.string("collector_id") == captured.identity && reply.value["generation"] == captured.value["generation"])
                    RingWorkoutGrantStatus.VERIFIED else RingWorkoutGrantStatus.REVOKED
                else -> RingWorkoutGrantStatus.UNAVAILABLE
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { RingWorkoutGrantStatus.UNAVAILABLE }
    }
    private val work get() = WorkManager.getInstance(context)
    private val adapter: BluetoothAdapter? get() = context.getSystemService(BluetoothManager::class.java)?.adapter
    private fun installation(): String {
        val preferences = context.getSharedPreferences("ring-installation", Context.MODE_PRIVATE)
        return preferences.getString("id", null) ?: UUID.randomUUID().toString().also { preferences.edit().putString("id", it).apply() }
    }
    init {
        val saved = mutableState.value
        stateValue = RingState(selected = saved["selected"] as? JsonObject,
            battery = saved.number("battery"), charging = saved.bool("charging"), firmware = saved.string("firmware"),
            hardware = saved.string("hardware"), backgroundEnabled = if (storageUnavailable || forgetPending) false else try { (store.get("background") as? JsonPrimitive)?.booleanOrNull ?: false } catch (_: Exception) { storageUnavailable = true; false },
            lastSync = saved.string("last_sync"), lastUpload = saved.string("last_upload"),
            metrics = JsonObject(RingState().metrics + (saved["metric_status"] as? JsonObject ?: obj {})),
            collectionSupport = saved["collection_support"] as? JsonObject ?: RingState().collectionSupport)
        publish()
        diagnostics?.bind(grant(includePending = true)?.identity, preservePrevious = true)
        if (forgetPending) try { forgetBinding() } catch (_: Exception) { storageUnavailable = true; publish() }
        else if (stateValue.backgroundEnabled) grant()?.let(::schedule)
        if (!storageUnavailable && !forgetPending) measurements.refreshFence()
        if (!storageUnavailable && !forgetPending && grant() != null && store.count() > 0) enqueueUpload()
        // BLE may be held for the whole workout and active streams stay local until terminal.
        // A separate narrow HTTP check observes remote revoke without depending on either path or UI.
        scope.launch {
            while (isActive) {
                delay(30_000)
                try { workoutGrantGuard.check() }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { /* A local/network failure is not proof of revocation. */ }
            }
        }
    }
    private fun loadState(): JsonObject = try {
        forgetPending = store.forgetPending()
        if (forgetPending) RingState().json() else store.get("state") as? JsonObject ?: RingState().json()
    }
        catch (_: Exception) { storageUnavailable = true; storageError().json() }
    private fun storageError() = RingState(phase = "error", error = obj {
        put("code", "store_unavailable"); put("message", RingText.text(R.string.ring_native_b305eb5c604e))
    })
    private fun grant(includePending: Boolean = false): RingGrant? {
        if (serverSwitchStarted) return null
        if (storageUnavailable || forgetPending && !includePending) return null
        if (!includePending && try { (store.get("collector_revoked") as? JsonPrimitive)?.booleanOrNull == true } catch (_: Exception) { true }) return null
        return try {
            val value = (store.get("grant") as? JsonObject)?.let(::RingGrant) ?: return null
            if (includePending || nativeGrantAllowed(value)) value else null
        } catch (_: Exception) { storageUnavailable = true; null }
    }
    private fun nativeGrantMarker(value: RingGrant, session: RingSession): JsonObject {
        val instance = session.instance?.takeIf { it.isNotBlank() }
        val device = session.nativeDeviceId?.takeIf { it.isNotBlank() }
        if (instance == null || device == null || session.origin != origin || session.userId != value.user)
            throw RingFailure("not_authenticated", RingText.text(R.string.ring_native_59f65e17d26f))
        return obj {
            put("instance", instance); put("origin", origin); put("user_id", value.user); put("device_id", device)
            put("collector_id", value.identity); put("source_id", value.source); put("generation", value.value["generation"] ?: JsonNull)
        }
    }
    /** A retired-shell credential has no proof of enrollment under a native login device. */
    private fun nativeGrantAllowed(value: RingGrant): Boolean {
        if ((store.get("native_enrollment_blocked") as? JsonPrimitive)?.booleanOrNull == true) return false
        val marker = store.get("native_enrollment") as? JsonObject ?: return false
        if (marker.string("instance").isNullOrBlank() || marker.string("device_id").isNullOrBlank() ||
            marker.string("origin") != origin || marker.string("user_id") != value.user ||
            marker.string("collector_id") != value.identity || marker.string("source_id") != value.source ||
            marker["generation"] != value.value["generation"]) return false
        // Authenticated native enrollment provenance survives process death and foreground expiry.
        // The collector state endpoint validates the original parent before transfer or connected collection.
        val session = nativeSession ?: return true
        if (session.instance.isNullOrBlank() || session.nativeDeviceId.isNullOrBlank() || session.origin != origin || session.userId != value.user)
            return false
        return marker == nativeGrantMarker(value, session)
    }
    private fun fenceNativeBinding() {
        if (grant(includePending = true) == null) return
        store.put("native_enrollment_blocked", JsonPrimitive(true))
        generation.incrementAndGet(); currentGatt?.close(); currentGatt = null
        work.cancelAllWorkByTag(WORK_TAG)
        measurements.bindingChanged(clear = false); workoutJob?.cancel()
        resetPhoneBinding(clear = false)
    }
    private fun supportedActivityKinds(): Set<String> = RingBatchRecords.supportedKinds(
        store.get("collector_capabilities") as? JsonObject ?: obj {})
    private fun rememberCollectorState(remote: JsonObject) {
        val kinds = RingBatchRecords.supportedKinds(remote)
        store.put("collector_capabilities", obj {
            put("supported_activity_report_kinds", JsonArray(kinds.filter { it in RingBatchRecords.activityKinds }.sorted().map(::JsonPrimitive)))
            put("supported_workout_hr_chunks", "workout_hr_chunks" in kinds)
            put("supported_workout_track_chunks", "workout_track_chunks" in kinds)
            put("supported_device_state", "device_state" in kinds)
            remote["supported_observation_kinds"]?.let { put("supported_observation_kinds", it) }
            remote["supported_sensor_metrics"]?.let { put("supported_sensor_metrics", it) }
            remote["supported_raw_report_metrics"]?.let { put("supported_raw_report_metrics", it) }
        })
        store.put("timezone", remote["timezone"] ?: JsonPrimitive(ZoneId.systemDefault().id))
        publish { it.copy(collectionSupport = obj {
            put("running_steps", if ("running_steps" in kinds) "enabled" else "server_unsupported")
            put("running_workouts", "protocol_unverified")
        }) }
    }
    /** Network absence does not prevent BLE. Revoked grants are not an offline capability fallback. */
    private suspend fun refreshCollectorCapabilities(grant: RingGrant, owner: Long) {
        if (context.getSystemService(ConnectivityManager::class.java)?.activeNetwork == null) return
        try {
            val remote = http.state(grant, timeoutMs = 1_500)
            requireGeneration(owner); rememberCollectorState(remote)
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) {
            requireGeneration(owner)
            if ((error as? RingFailure)?.code in setOf("grant_revoked", "invalid_binding")) {
                revokeWorkoutBinding()
                work.cancelUniqueWork(collectName(grant.identity)); work.cancelUniqueWork(continuationName(grant.identity))
                store.put("background", JsonPrimitive(false)); publish { it.copy(backgroundEnabled = false) }
                throw error
            }
        }
    }
    @Synchronized private fun publish(update: (RingState) -> RingState = { it }) {
        if (serverSwitchStarted) { mutableState.value = RingState().json(); return }
        val grant = grant()
        if (storageUnavailable) {
            stateValue = storageError().copy(user = reportedUser)
            mutableState.value = stateValue.json(); return
        }
        if (forgetPending) {
            stateValue = RingState(user = reportedUser)
            mutableState.value = stateValue.json(); return
        }
        val updated = update(stateValue)
        val permission = permissionState()
        try {
            stateValue = updated.copy(permission = if (permission == "required" && updated.permission == "denied") "denied" else permission, bluetoothEnabled = bluetoothEnabled(),
                queueCount = store.count(), user = grant?.user ?: reportedUser, source = grant?.source)
            store.put("state", stateValue.json())
            // Keep the original device identity in its encrypted store for journals, but offer explicit enrollment in UI.
            mutableState.value = if (grant == null && grant(includePending = true) != null) stateValue.copy(
                selected = null, source = null, user = reportedUser, backgroundEnabled = false,
                error = obj { put("code", "native_enrollment_required"); put("message", RingText.text(R.string.ring_native_enrollment_required)) },
            ).json() else stateValue.json()
        } catch (_: Exception) {
            storageUnavailable = true; stateValue = storageError().copy(user = reportedUser); mutableState.value = stateValue.json()
        }
    }
    fun snapshot(): JsonObject { publish(); return mutableState.value }
    /** Available only through the permission-checked native provider. */
    private fun diagnosticSession() = diagnostics?.session() ?: RingDiagnostics.Session(UUID.randomUUID().toString(), 0)
    private fun diagnosticLog(session: RingDiagnostics.Session, kind: String, payload: JsonObject = obj {}) {
        diagnostics?.append(session, kind, payload)
    }
    private fun journalStatus() = diagnostics?.status() ?: obj {
        put("healthy", false); put("unavailable", true); put("last_failure", "journal_init_failed")
    }
    fun diagnosticJournal(): ByteArray = diagnostics?.export() ?: (obj {
        put("kind", "journal_status"); put("status", journalStatus())
    }.toString() + "\n").toByteArray(Charsets.UTF_8)
    fun diagnosticStatus(): JsonObject = obj {
        put("journal", journalStatus()); put("phase", stateValue.phase)
        put("busy", tasks.busy || lock.isLocked); put("bound", grant() != null)
        put("storage_unavailable", storageUnavailable); put("forget_pending", forgetPending)
        put("permission", permissionState()); put("bluetooth_enabled", bluetoothEnabled())
        put("last_sync", stateValue.lastSync?.let(::JsonPrimitive) ?: JsonNull)
        put("last_upload", stateValue.lastUpload?.let(::JsonPrimitive) ?: JsonNull)
        put("firmware", stateValue.firmware?.let(::JsonPrimitive) ?: JsonNull)
        put("hardware", stateValue.hardware?.let(::JsonPrimitive) ?: JsonNull)
        put("metric_status", stateValue.metrics)
        put("error_code", stateValue.error?.get("code") ?: JsonNull)
        put("operation_id", stateValue.operationId?.let(::JsonPrimitive) ?: JsonNull)
    }
    fun diagnosticSync(freshHistory: Boolean = false): JsonObject {
        if (historyBlocked()) throw RingFailure("workout_busy", RingText.text(R.string.ring_native_7538c20db484))
        if (storageUnavailable || forgetPending) throw RingFailure("store_unavailable", RingText.text(R.string.ring_native_6ef6087e9a3e))
        if (grant() == null) throw RingFailure("not_bound", RingText.text(R.string.ring_native_b14cbf892a10))
        if (tasks.busy || lock.isLocked) throw RingFailure("busy", RingText.text(R.string.ring_native_501dfba1a328))
        val id = UUID.randomUUID().toString()
        val session = diagnosticSession()
        val owner = generation.get()
        tasks.launchForeground {
            try {
                lock.withLock {
                    requireGeneration(owner)
                    publish { it.copy(operationId = id, error = null) }
                    collectLocked(owner, foreground = true, freshHistory = freshHistory, session = session)
                    enqueueUpload()
                }
                if (owner == generation.get()) publish { it.copy(phase = "idle", operationId = null) }
            } catch (timeout: TimeoutCancellationException) {
                diagnosticLog(session, "diagnostic_sync_failed", obj { put("code", "timeout") })
                if (owner == generation.get()) fail(timeout)
            } catch (cancelled: CancellationException) {
                diagnosticLog(session, "diagnostic_sync_cancelled")
                clearCancelledOperation(owner, id)
                throw cancelled
            } catch (error: Exception) {
                diagnosticLog(session, "diagnostic_sync_failed", obj { put("code", (error as? RingFailure)?.code ?: "unavailable") })
                if (owner == generation.get()) fail(error)
            }
        }
        return obj { put("operation_id", id); put("session_id", session.id); put("fresh_history", freshHistory); put("status", "scheduled") }
    }
    override fun permissionNames(): Array<String> = if (Build.VERSION.SDK_INT >= 31)
        arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        else arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
    private fun permissionState(): String = if (permissionNames().all { context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }) "granted" else "required"
    private fun bluetoothEnabled(): Boolean = try {
        if (Build.VERSION.SDK_INT >= 31 && context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) false
        else adapter?.isEnabled == true
    } catch (_: SecurityException) { false }
    override fun permissionResult(granted: Boolean) { publish { it.copy(permission = if (granted) "granted" else "denied") } }

    /** A session credential never crosses into durable collector storage. */
    @Synchronized fun setSession(session: RingSession?, reason: String = "switch", isCurrent: () -> Boolean = { true }) {
        if (serverSwitchStarted) return
        val interruptWorkout = RingWorkoutAccountPolicy.interrupt(reportedUser, session?.userId, grant()?.user, reason)
        if (interruptWorkout) {
            measurements.bindingChanged(clear = false); workoutJob?.cancel(); currentGatt?.close()
        }
        val changed = nativeSession != session
        if (changed) {
            workoutScreenLease.value = null; workoutPermissionLease = null
            account.invalidateForeground()
            measurements.foregroundDetached(); devices.foregroundDetached(); detachPhoneControls()
        }
        val nativeLogout = reason == "logout" && grant() != null
        nativeSession = session
        sessionCurrent = isCurrent
        val changedAccount = account.change(session?.userId, reason) { grant(includePending = true)?.user }
        if (nativeLogout) { forgetBinding(); return }
        // A different account/login device cannot erase or acquire the old pending queue.
        if (changedAccount || session != null && grant(includePending = true) != null && grant() == null) fenceNativeBinding()
        measurements.refreshBinding(); devices.refreshFence(); refreshPhoneControls(); ota.snapshot()
        publish { it.copy(phase = "idle", operationId = null) }
        grant()?.let { value ->
            if (stateValue.backgroundEnabled) schedule(value)
            if (store.count() > 0) enqueueUpload()
        }
    }
    override fun scan() { command("ring.scan") }
    override fun select(address: String) { command("ring.select", obj { put("address", address) }) }
    override fun sync() { command("ring.sync") }
    override fun background(enabled: Boolean) { command("ring.background", obj { put("enabled", enabled) }) }
    override fun forget() { forgetBinding() }
    private fun ownedPayload(fields: JsonObject): JsonObject {
        if (serverSwitchStarted) throw CancellationException("Server switch started")
        if (!foregroundVisible) throw RingFailure("screen_detached", RingText.text(R.string.ring_native_9a6dc0329f6e))
        val owner = workoutBinding() ?: throw RingFailure("not_bound", RingText.text(R.string.ring_native_b14cbf892a10))
        val accountOwner = account.capture()
        if (!sessionCurrent()) throw CancellationException("Native session changed")
        if (accountOwner.user != owner.userId) throw RingFailure("not_authenticated", RingText.text(R.string.ring_native_59f65e17d26f))
        return JsonObject(fields + obj {
            put("user_id", owner.userId); put("source_id", owner.sourceId); put("binding_generation", owner.grantGeneration)
        })
    }
    override fun device(action: RingDeviceAction) {
        val (type, fields) = action.toCommand()
        if (action == RingDeviceAction.Refresh) devices.snapshot()
        else {
            val payload = ownedPayload(fields)
            if (otaRadioBlocked(readOnly = action == RingDeviceAction.RefreshIdentity))
                throw RingFailure("ring_busy", RingText.text(R.string.ring_native_ed3bd83b34b7))
            devices.command(type, payload)
        }
    }
    override fun measurement(action: RingMeasurementAction) {
        val (type, fields) = action.toCommand()
        when (action) {
            RingMeasurementAction.Refresh -> measurements.measurementSnapshot()
            is RingMeasurementAction.Reconcile -> devices.command(type, ownedPayload(fields))
            else -> measurements.command(type, ownedPayload(fields))
        }
    }

    @Synchronized override fun workoutScreen(visible: Boolean, generation: Long, userId: String, sourceId: String,
        grantGeneration: Long, canRead: Boolean, canWrite: Boolean) {
        if (!visible) {
            if (workoutScreenLease.value?.generation == generation) {
                workoutScreenLease.value = null
            }
            return
        }
        val owner = workoutBinding()
        if (!canRead || owner?.userId != userId || owner.sourceId != sourceId || owner.grantGeneration != grantGeneration ||
            reportedUser != userId || !sessionCurrent()) {
            workoutScreenLease.value = null; workoutPermissionLease = null
            return
        }
        val next = RingWorkoutScreenLease(generation, userId, sourceId, grantGeneration, canRead, canWrite)
        if (workoutPermissionLease?.second?.let { it != next } == true) workoutPermissionLease = null
        workoutScreenLease.value = next
        measurements.refreshBinding(); measurements.locationStateChanged(); measurements.workoutSnapshot()
    }
    private fun workoutOwnedPayload(fields: JsonObject, write: Boolean): JsonObject {
        val lease = workoutScreenLease.value ?: throw RingFailure("screen_detached", RingText.text(R.string.ring_native_9a6dc0329f6e))
        val owner = workoutBinding()
        if (!lease.canRead || write && !lease.canWrite || !sessionCurrent() || reportedUser != lease.userId ||
            owner?.userId != lease.userId || owner.sourceId != lease.sourceId || owner.grantGeneration != lease.grantGeneration)
            throw RingFailure("invalid_binding", RingText.text(R.string.ring_native_dd7938b1b3af))
        return JsonObject(fields + obj {
            put("user_id", owner.userId); put("source_id", owner.sourceId); put("binding_generation", owner.grantGeneration)
        })
    }
    override fun workout(action: RingWorkoutAction) {
        val fields = obj {
            when (action) {
                is RingWorkoutAction.Start -> { put("sport_id", action.sportId); put("gps_requested", action.gpsRequested) }
                is RingWorkoutAction.Pause -> put("workout_id", action.workoutId)
                is RingWorkoutAction.Resume -> put("workout_id", action.workoutId)
                is RingWorkoutAction.Stop -> put("workout_id", action.workoutId)
                RingWorkoutAction.Refresh -> Unit
            }
        }
        val parameters = workoutOwnedPayload(fields, action != RingWorkoutAction.Refresh)
        if (action is RingWorkoutAction.Start && Build.VERSION.SDK_INT >= 33 &&
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            throw RingFailure("notification_permission_required", RingText.text(R.string.ring_workout_87324ca83d2b))
        measurements.command(when (action) {
            RingWorkoutAction.Refresh -> "ring.workout.state"
            is RingWorkoutAction.Start -> "ring.workout.start"
            is RingWorkoutAction.Pause -> "ring.workout.pause"
            is RingWorkoutAction.Resume -> "ring.workout.resume"
            is RingWorkoutAction.Stop -> "ring.workout.stop"
        }, parameters)
    }
    @Synchronized override fun workoutPermissionNames(kind: RingWorkoutPermission): Array<String> {
        val fields = if (kind == RingWorkoutPermission.BACKGROUND_LOCATION) obj { put("background", true) } else obj {}
        val parameters = workoutOwnedPayload(fields, write = true)
        workoutPermissionLease = kind to workoutScreenLease.value!!
        return when (kind) {
            RingWorkoutPermission.ACTIVITY -> measurements.permissionNames(parameters)
            RingWorkoutPermission.LOCATION, RingWorkoutPermission.BACKGROUND_LOCATION -> measurements.locationPermissionNames(parameters)
            RingWorkoutPermission.NOTIFICATIONS -> if (Build.VERSION.SDK_INT >= 33 &&
                context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
                arrayOf(Manifest.permission.POST_NOTIFICATIONS) else emptyArray()
        }
    }
    @Synchronized override fun workoutPermissionResult(kind: RingWorkoutPermission, granted: Boolean) {
        val requested = workoutPermissionLease; workoutPermissionLease = null
        if (requested == null || requested.first != kind || requested.second != workoutScreenLease.value) return
        try { workoutOwnedPayload(obj {}, write = true) } catch (_: Exception) { return }
        when (kind) {
            RingWorkoutPermission.ACTIVITY -> measurements.permissionResult(granted)
            RingWorkoutPermission.LOCATION, RingWorkoutPermission.BACKGROUND_LOCATION -> measurements.locationPermissionResult()
            RingWorkoutPermission.NOTIFICATIONS -> Unit
        }
    }
    override fun workoutLocationSettingsIntent(): android.content.Intent {
        workoutOwnedPayload(obj {}, write = true)
        return android.content.Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            android.net.Uri.parse("package:${context.packageName}"))
    }

    /** A revoked collector is no longer offline authority; keep sealed records isolated for diagnosis. */
    @Synchronized private fun revokeWorkoutBinding(expected: RingWorkoutBinding? = null) {
        if (serverSwitchStarted) return
        if (expected != null && workoutBinding() != expected) return
        val revoked = grant()
        resetPhoneBinding(clear = false)
        measurements.bindingChanged(clear = false)
        workoutJob?.cancel(); currentGatt?.close()
        store.put("collector_revoked", JsonPrimitive(true)); store.put("background", JsonPrimitive(false))
        revoked?.let { work.cancelUniqueWork(collectName(it.identity)); work.cancelUniqueWork(continuationName(it.identity)) }
        workoutScreenLease.value = null; workoutPermissionLease = null
        measurements.refreshBinding(); devices.refreshFence(); refreshPhoneControls(); ota.snapshot()
        publish { it.copy(phase = "error", backgroundEnabled = false, error = obj {
            put("code", "grant_revoked"); put("message", RingText.text(R.string.ring_native_b75b0deb2d0a))
        }) }
    }

    private fun command(type: String, payload: JsonObject = obj {}): JsonObject {
        if (serverSwitchStarted) throw CancellationException("Server switch started")
        val session = nativeSession
        val sessionGuard = sessionCurrent
        val foregroundOwner = account.capture()
        if (!foregroundVisible) throw RingFailure("screen_detached", RingText.text(R.string.ring_native_9a6dc0329f6e))
        if (storageUnavailable) throw RingFailure("store_unavailable", RingText.text(R.string.ring_native_145fb0138b67))
        if (type !in setOf("ring.scan", "ring.select", "ring.sync", "ring.background")) throw RingFailure("unknown_type", RingText.text(R.string.ring_native_f73c727b06da))
        if (tasks.busy) throw RingFailure("busy", RingText.text(R.string.ring_native_501dfba1a328))
        if (historyBlocked() && (type !in setOf("ring.scan", "ring.select") || grant() != null))
            throw RingFailure("workout_busy", RingText.text(R.string.ring_native_7538c20db484))
        val id = UUID.randomUUID().toString()
        publish { it.copy(operationId = id, error = null) }
        val owner = generation.get()
        tasks.launchForeground {
            try {
                lock.withLock {
                    requireGeneration(owner)
                    account.requireCurrent(foregroundOwner)
                    if (!sessionGuard()) throw CancellationException("Native session changed")
                    when (type) {
                        "ring.scan" -> scan(owner)
                        "ring.select" -> select(payload, session ?: throw RingFailure("not_authenticated", RingText.text(R.string.ring_native_59f65e17d26f)), sessionGuard, owner)
                        "ring.sync" -> {
                            if (reportedUser != grant()?.user) throw RingFailure("not_authenticated", RingText.text(R.string.ring_native_59f65e17d26f))
                            collectLocked(owner, foreground = true)
                            enqueueUpload()
                        }
                        "ring.background" -> {
                            val enabled = payload.bool("enabled") ?: throw RingFailure("bad_payload", RingText.text(R.string.ring_native_bf2700ca6426))
                            val grant = grant() ?: throw RingFailure("not_bound", RingText.text(R.string.ring_native_b14cbf892a10))
                            if (reportedUser != grant.user) throw RingFailure("not_authenticated", RingText.text(R.string.ring_native_59f65e17d26f))
                            if (enabled) rememberCollectorState(http.state(grant)) // Confirm live authorization before scheduling.
                            requireGeneration(owner)
                            store.put("background", JsonPrimitive(enabled))
                            publish { it.copy(backgroundEnabled = enabled) }
                            if (enabled) { schedule(grant); enqueueUpload() }
                            else { work.cancelUniqueWork(collectName(grant.identity)); work.cancelUniqueWork(continuationName(grant.identity)) }
                        }
                    }
                }
                if (owner == generation.get()) publish { it.copy(phase = "idle", operationId = null) }
            } catch (timeout: TimeoutCancellationException) { if (owner == generation.get()) fail(timeout) }
            catch (cancelled: CancellationException) { clearCancelledOperation(owner, id); throw cancelled }
            catch (failure: Exception) { if (owner == generation.get()) fail(failure) }
        }
        return obj { put("operation_id", id); put("state", snapshot()) }
    }
    /** Cancellation retires presentation only; physical command recovery is kept in its journal. */
    @Synchronized private fun clearCancelledOperation(owner: Long, id: String) {
        if (owner == generation.get() && stateValue.operationId == id)
            publish { it.copy(phase = "idle", operationId = null) }
    }
    private fun requireGeneration(owner: Long) { if (owner != generation.get()) throw CancellationException("Binding changed") }
    private fun fail(error: Exception) {
        val failure = error as? RingFailure ?: if (error is TimeoutCancellationException)
            RingFailure("timeout", RingText.text(R.string.ring_native_06dd4cef6a85))
        else RingFailure("unavailable", RingText.text(R.string.ring_native_c35e434ae2d0))
        publish { it.copy(phase = "error", operationId = null, error = obj { put("code", failure.code); put("message", failure.message) }) }
    }
    private fun preserveOriginalJournal() {
        val saved = store.get("control_recovery") as? JsonObject
        val original = grant(includePending = true)
        val address = stateValue.selected?.string("address")
        if (original == null || address == null) {
            if (saved != null) throw RingFailure("control_owner_unknown", RingText.text(R.string.ring_native_e9e106cce5c4))
            return
        }
        val version = original.value["generation"]?.jsonPrimitive?.longOrNull
            ?: throw RingFailure("control_owner_unknown", RingText.text(R.string.ring_native_6b8d7413f8a2))
        val binding = RingWorkoutBinding(origin, original.user, original.source, original.identity, address, version,
            (store.get("timezone") as? JsonPrimitive)?.content ?: ZoneId.systemDefault().id)
        // Seal legacy device ownership while the original scope still exists, without granting fresh radio authority.
        deviceStore.preserveBinding(binding)
        if (saved == null) return
        val recovery = saved["recovery"] as? JsonObject
        if (saved.string("binding_identity") != binding.identity || recovery == null)
            throw RingFailure("control_owner_unknown", RingText.text(R.string.ring_native_5f31b6205002))
        deviceStore.preserveLegacyControl(binding, recovery)
        store.put("control_recovery", JsonNull) // Only after the independent physical journal commit.
    }
    private fun forgetBinding(): JsonObject {
        if (serverSwitchStarted) throw CancellationException("Server switch started")
        val forgottenWorkoutBinding = workoutBinding()
        preserveOriginalJournal()
        resetPhoneBinding(clear = true)
        measurements.bindingChanged(clear = false); devices.bindingChanged(clear = true); workoutJob?.cancel(); workoutScreenLease.value = null; workoutPermissionLease = null
        val old = account.invalidateBinding {
            val previous = grant(includePending = true)
            store.markForgetPending()
            forgetPending = true
            generation.incrementAndGet(); currentGatt?.close(); currentGatt = null
            diagnostics?.clear()
            previous
        }
        old?.let { work.cancelUniqueWork(collectName(it.identity)); work.cancelUniqueWork(uploadName(it.identity)); work.cancelUniqueWork(continuationName(it.identity)) }
        work.cancelAllWorkByTag(WORK_TAG)
        val id = UUID.randomUUID().toString()
        tasks.launchCleanup {
            try {
                lock.withLock {
                    measurements.bindingChanged(clear = true, clearBinding = forgottenWorkoutBinding)
                    if (storageUnavailable) throw RingFailure("store_unavailable", RingText.text(R.string.ring_native_b305eb5c604e))
                    store.clear()
                    storageUnavailable = false; forgetPending = false; stateValue = RingState(); publish()
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { storageUnavailable = true; publish(); return@launchCleanup }
            old?.let { try { http.revoke(it) } catch (_: Exception) { /* Local revoke is immediate; server logout also revokes. */ } }
        }
        return obj { put("operation_id", id); put("state", RingState(user = reportedUser).json()) }
    }
    @SuppressLint("MissingPermission")
    private suspend fun scan(owner: Long) {
        if (permissionState() != "granted") throw RingFailure("permission_denied", RingText.text(R.string.ring_native_bfddb96f88e5))
        if (!bluetoothEnabled()) throw RingFailure("bluetooth_off", RingText.text(R.string.ring_native_55166cb231a6))
        val scanner = adapter?.bluetoothLeScanner ?: throw RingFailure("bluetooth_unavailable", RingText.text(R.string.ring_native_8b00887dd420))
        val found = linkedMapOf<String, JsonObject>()
        val scanError = CompletableDeferred<Int>()
        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                if (owner != generation.get()) return
                val name = result.scanRecord?.deviceName ?: result.device.name ?: ""
                val services = result.scanRecord?.serviceUuids?.map { it.uuid } ?: emptyList()
                if (!name.startsWith("R20_", ignoreCase = true) && RingGatt.CORE_SERVICE !in services) return
                synchronized(found) { found[result.device.address] = obj { put("address", result.device.address); put("name", name.ifEmpty { "QRing R20" }); put("rssi", result.rssi) } }
            }
            override fun onScanFailed(errorCode: Int) { scanError.complete(errorCode) }
        }
        publish { it.copy(phase = "scanning", scanResults = emptyList()) }
        try {
            scanner.startScan(null, ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(), callback)
            val failure = withTimeoutOrNull(8_000) { scanError.await() }
            if (failure != null) throw RingFailure("scan_failed", RingText.text(R.string.ring_native_a6714bc043a6, failure))
            requireGeneration(owner)
            publish { it.copy(scanResults = synchronized(found) { found.values.toList() }) }
        } finally { try { scanner.stopScan(callback) } catch (_: SecurityException) { /* Permission revoked. */ } }
    }
    private suspend fun select(payload: JsonObject, session: RingSession, sessionGuard: () -> Boolean, owner: Long) {
        val address = payload.string("address") ?: throw RingFailure("bad_payload", RingText.text(R.string.ring_native_58264223abc5))
        val found = stateValue.scanResults.singleOrNull { it.string("address") == address }
            ?: throw RingFailure("not_scanned", RingText.text(R.string.ring_native_58f132c4ca28))
        val old = stateValue.selected
        if (grant() != null && old != null && old.string("address") != address) throw RingFailure("already_bound", RingText.text(R.string.ring_native_7c996f4d93c2))
        if (session.instance.isNullOrBlank() || session.nativeDeviceId.isNullOrBlank() || session.origin != origin)
            throw RingFailure("not_authenticated", RingText.text(R.string.ring_native_59f65e17d26f))
        if (grant() == null && grant(includePending = true) != null) {
            preserveOriginalJournal()
            val previous = grant(includePending = true)!!
            stateValue.selected?.string("address")?.let { oldAddress ->
                val oldGeneration = previous.value["generation"]?.jsonPrimitive?.longOrNull
                    ?: throw RingFailure("invalid_binding", RingText.text(R.string.ring_native_b14cbf892a10))
                measurements.closeBinding(RingWorkoutBinding(origin, previous.user, previous.source, previous.identity,
                    oldAddress, oldGeneration, (store.get("timezone") as? JsonPrimitive)?.content ?: ZoneId.systemDefault().id))
            }
            if (diagnostics?.closeBinding() == false)
                throw RingFailure("store_unavailable", RingText.text(R.string.ring_native_b305eb5c604e))
            store.closeBinding("explicit_native_enrollment")
            stateValue = RingState(scanResults = stateValue.scanResults)
            publish()
        }
        val accountOwner = account.capture()
        val user = accountOwner.user
        val binding = RingPolicy.bindingId(installation(), origin, user, address)
        val nativeCheckpoint = {
            account.requireCurrent(accountOwner)
            if (nativeSession != session || !sessionGuard()) throw CancellationException("Native session changed")
        }
        account.enroll(accountOwner, grant(),
            issue = { checkpoint, issued -> http.enroll(session.bearerToken, user, binding, found.string("name") ?: "HerzRing",
                { checkpoint(); nativeCheckpoint() }, issued) },
            state = { grant, checkpoint -> http.state(grant, { checkpoint(); nativeCheckpoint() }) },
            persist = { grant, remote ->
                nativeCheckpoint()
                requireGeneration(owner)
                preserveOriginalJournal()
                devices.bindingChanged(clear = false); resetPhoneBinding(clear = false)
                store.put("collector_revoked", JsonPrimitive(false))
                store.put("grant", grant.value)
                store.put("native_enrollment", nativeGrantMarker(grant, session))
                store.put("native_enrollment_blocked", JsonPrimitive(false))
                store.put("timezone", remote["timezone"] ?: JsonPrimitive(ZoneId.systemDefault().id))
                diagnostics?.bind(grant.identity, preservePrevious = true)
                store.put("server_cursor", remote["cursor"] ?: JsonNull)
                rememberCollectorState(remote)
                publish { it.copy(selected = found) }
                measurements.refreshBinding()
            }, revoke = http::revoke)
        account.requireCurrent(accountOwner)
        currentCoroutineContext().ensureActive()
        if (deviceMutationPending()) { devices.refreshFence(); return }
        collectLocked(owner, foreground = true); enqueueUpload()
    }

    private fun schedule(grant: RingGrant) {
        val request = PeriodicWorkRequestBuilder<RingCollectWorker>(1, TimeUnit.HOURS, 15, TimeUnit.MINUTES)
            .addTag(WORK_TAG)
            .setInputData(workDataOf("collector" to grant.identity, "origin" to origin)).build()
        work.enqueueUniquePeriodicWork(collectName(grant.identity), ExistingPeriodicWorkPolicy.UPDATE, request)
    }
    private fun enqueueUpload() {
        val grant = grant() ?: return
        val request = OneTimeWorkRequestBuilder<RingUploadWorker>()
            .addTag(WORK_TAG)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 15, TimeUnit.MINUTES)
            .setInputData(workDataOf("collector" to grant.identity, "origin" to origin)).build()
        enqueue(uploadName(grant.identity), request) { retryUploadEnqueue(grant.identity, grant.user, grant.source) }
    }
    private fun enqueue(name: String, request: OneTimeWorkRequest, failed: () -> Unit) {
        try {
            enqueueOneTimeWork?.let { it(name, ExistingWorkPolicy.APPEND_OR_REPLACE, request, failed); return }
            val operation = work.enqueueUniqueWork(name, ExistingWorkPolicy.APPEND_OR_REPLACE, request)
            operation.result.addListener({
                try { operation.result.get() } catch (_: Exception) { failed() }
            }, Executor { runnable -> scope.launch { runnable.run() } }) // The future is complete before this non-blocking listener runs.
        } catch (_: Exception) { failed() }
    }
    private fun retryUploadEnqueue(collector: String, user: String, source: String) = synchronized(uploadRetryMonitor) {
        val bound = grant()
        if (bound == null || bound.identity != collector || bound.user != user || bound.source != source) return@synchronized
        val identity = sha256(listOf(collector, user, source).joinToString("\n"))
        if (uploadRetryWake?.isActive == true && uploadRetryOwner == identity) return@synchronized
        uploadRetryWake?.cancel(); uploadRetryOwner = identity
        uploadRetryWake = scope.launch {
            delay(15 * 60_000L)
            synchronized(uploadRetryMonitor) { uploadRetryWake = null }
            val current = grant()
            if (current != null && current.identity == collector && current.user == user && current.source == source) enqueueUpload()
        }
    }
    private fun enqueueWorkoutTransfer(attempt: RingWorkoutTransferRetry, failed: () -> Unit): Boolean {
        val expected = attempt.binding
        if (workoutBinding() != expected) return false
        val request = OneTimeWorkRequestBuilder<RingWorkoutTransferWorker>()
            .addTag(WORK_TAG)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 15, TimeUnit.MINUTES)
            .setInputData(workDataOf("collector" to expected.collectorId, "upload_identity" to expected.uploadIdentity,
                "retry_id" to attempt.id, "origin" to origin)).build()
        // KEEP can drop a producer wake-up while the previous worker is finishing its empty drain.
        enqueue(transferName(expected.uploadIdentity), request, failed)
        return true
    }
    internal fun transferWorkoutRecords(collector: String, uploadIdentity: String, attemptId: String): Boolean {
        if (serverSwitchStarted) return true
        val expected = workoutBinding()?.takeIf { it.collectorId == collector && it.uploadIdentity == uploadIdentity }
        if (expected == null) {
            measurements.transferWorkerFinished(uploadIdentity, attemptId)
            return true // A stale worker cannot transfer or acknowledge another binding.
        }
        val copied = measurements.retryPendingTransfers(expected)
        val stale = workoutBinding()?.uploadIdentity != expected.uploadIdentity
        if (stale) measurements.transferWorkerFinished(uploadIdentity, attemptId)
        return copied || stale
    }
    private fun continueBackfill(grant: RingGrant) {
        val request = OneTimeWorkRequestBuilder<RingCollectWorker>()
            .addTag(WORK_TAG)
            .setInitialDelay(1, TimeUnit.MINUTES)
            .setInputData(workDataOf("collector" to grant.identity, "origin" to origin, "continuation" to true)).build()
        work.enqueueUniqueWork(continuationName(grant.identity), ExistingWorkPolicy.APPEND_OR_REPLACE, request)
    }
    suspend fun collect(collector: String, continuation: Boolean = false): Boolean {
        if (serverSwitchStarted) return true
        if (historyBlocked()) return true
        return lock.withLock {
            if (grant()?.identity != collector || (!stateValue.backgroundEnabled && !continuation)) return@withLock true
            if (historyBlocked()) return@withLock true
            try { collectLocked(generation.get()); enqueueUpload(); publish { it.copy(phase = "idle") }; true }
            catch (timeout: TimeoutCancellationException) { fail(timeout); true }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { fail(error); true } // Ring/permission absence waits for the next hourly run.
        }
    }
    suspend fun upload(collector: String): Boolean = uploadLock.withLock {
        val grant = grant()?.takeIf { it.identity == collector } ?: return@withLock true
        val owner = generation.get()
        val session = diagnosticSession()
        fun batchEvidence(batch: JsonObject) = obj {
            put("batch_key", batch["key"] ?: JsonNull)
            val body = batch["body"] as JsonObject
            put("cursor", body["cursor"] ?: JsonNull)
            put("records", JsonArray(RingBatchRecords.records(body).map { record -> obj {
                put("external_id", record["external_id"] ?: JsonNull); put("version", record["version"] ?: JsonNull)
            } }))
        }
        suspend fun receipt(batch: JsonObject): RingHttp.Reply {
            diagnosticLog(session, "receipt_request", obj { put("batch_key", batch["key"] ?: JsonNull) })
            return http.receipt(grant, batch.string("key")!!).also { reply ->
                diagnosticLog(session, "receipt_response", obj {
                    put("batch_key", batch["key"] ?: JsonNull); put("status", reply.status)
                    put("matches", RingPolicy.receiptMatches(reply.value, grant.source, (batch["body"] as JsonObject)["cursor"]!!))
                })
            }
        }
        suspend fun send(batch: JsonObject): RingHttp.Reply {
            diagnosticLog(session, "upload_request", batchEvidence(batch))
            return http.send(grant, batch).also { reply ->
                diagnosticLog(session, "upload_response", obj {
                    put("batch_key", batch["key"] ?: JsonNull); put("status", reply.status)
                    put("matches", RingPolicy.receiptMatches(reply.value, grant.source, (batch["body"] as JsonObject)["cursor"]!!))
                })
            }
        }
        fun acknowledge(batch: JsonObject) {
            store.acknowledge(batch)
            diagnosticLog(session, "batch_acknowledged", batchEvidence(batch))
        }
        diagnosticLog(session, "upload_started")
        try {
            publish { it.copy(phase = "uploading") }
            var remote = http.state(grant)
            requireGeneration(owner)
            rememberCollectorState(remote)
            for (packet in 0 until 50) {
                requireGeneration(owner)
                var batch = store.batch(remote["cursor"] ?: JsonNull, RingBatchRecords.supportedKinds(remote)) ?: break
                diagnosticLog(session, "batch_prepared", batchEvidence(batch))
                var reply = receipt(batch)
                if (reply.status == 404) reply = send(batch)
                requireGeneration(owner)
                if (reply.status == 409) {
                    // Re-check a possible committed response before assigning a new key/body.
                    val checked = receipt(batch)
                    if (checked.status == 200 && RingPolicy.receiptMatches(checked.value, grant.source, (batch["body"] as JsonObject)["cursor"]!!)) {
                        acknowledge(batch); remote = http.state(grant); rememberCollectorState(remote); continue
                    }
                    remote = http.state(grant); rememberCollectorState(remote)
                    if (!RingBatchRecords.supportedBody(batch["body"] as JsonObject, RingBatchRecords.supportedKinds(remote))) break
                    batch = store.rebase(batch, remote["cursor"] ?: JsonNull)
                    diagnosticLog(session, "batch_rebased", batchEvidence(batch))
                    reply = send(batch)
                }
                if (reply.status in setOf(401, 403, 404)) throw RingFailure("grant_revoked", RingText.text(R.string.ring_native_8bc8d3a7816b))
                if (reply.status !in 200..299) throw RingFailure("upload_failed", RingText.text(R.string.ring_native_f33f23fdbafa, reply.status))
                val cursor = (batch["body"] as JsonObject)["cursor"]!!
                if (!RingPolicy.receiptMatches(reply.value, grant.source, cursor)) throw RingFailure("invalid_receipt", RingText.text(R.string.ring_native_50c5b198dec7))
                acknowledge(batch); remote = http.state(grant); rememberCollectorState(remote)
            }
            publish { it.copy(phase = "idle", lastUpload = RingPolicy.timestamp(), error = null) }
            if (store.batch(remote["cursor"] ?: JsonNull, RingBatchRecords.supportedKinds(remote)) != null) enqueueUpload()
            diagnosticLog(session, "upload_finished")
            true
        } catch (cancelled: CancellationException) { diagnosticLog(session, "upload_cancelled"); throw cancelled }
        catch (error: Exception) {
            diagnosticLog(session, "upload_failed", obj { put("code", (error as? RingFailure)?.code ?: "unavailable") })
            if (owner == generation.get()) fail(error)
            if ((error as? RingFailure)?.code in setOf("grant_revoked", "invalid_binding")) {
                revokeWorkoutBinding()
                work.cancelUniqueWork(collectName(grant.identity)); work.cancelUniqueWork(continuationName(grant.identity))
                store.put("background", JsonPrimitive(false))
                publish { it.copy(backgroundEnabled = false) }; true
            } else false
        }
    }

    private suspend fun collectLocked(owner: Long, foreground: Boolean = false, freshHistory: Boolean = false,
        session: RingDiagnostics.Session = diagnosticSession()) {
        if (historyBlocked()) throw RingFailure("workout_busy", RingText.text(R.string.ring_native_6043eeac3217))
        diagnosticLog(session, "session_started", obj { put("foreground", foreground); put("fresh_history", freshHistory) })
        try {
            collectSession(owner, foreground, freshHistory, session)
            diagnosticLog(session, "session_finished")
        } catch (cancelled: CancellationException) {
            diagnosticLog(session, "session_cancelled", obj { put("timeout", cancelled is TimeoutCancellationException) })
            throw cancelled
        } catch (error: Exception) {
            diagnosticLog(session, "session_failed", obj { put("code", (error as? RingFailure)?.code ?: "unavailable") })
            throw error
        }
    }
    @SuppressLint("MissingPermission")
    private suspend fun collectSession(owner: Long, foreground: Boolean, freshHistory: Boolean, session: RingDiagnostics.Session) {
        val foregroundLease = nativeSession
        val foregroundLeaseCurrent = sessionCurrent
        fun requireOwner() {
            requireGeneration(owner)
            if (foreground && foregroundLease != null && (nativeSession != foregroundLease || !foregroundLeaseCurrent()))
                throw CancellationException("Native session changed")
        }
        val grant = grant() ?: throw RingFailure("not_bound", RingText.text(R.string.ring_native_b14cbf892a10))
        val selected = stateValue.selected ?: throw RingFailure("not_bound", RingText.text(R.string.ring_native_7b0923a9255a))
        if (permissionState() != "granted") throw RingFailure("permission_denied", RingText.text(R.string.ring_native_527ed8d4926c))
        if (!bluetoothEnabled()) throw RingFailure("bluetooth_off", RingText.text(R.string.ring_native_55166cb231a6))
        if (store.count() >= RingPolicy.MAX_RECORDS || store.bytes() >= RingPolicy.MAX_BYTES)
            throw RingFailure("queue_full", RingText.text(R.string.ring_native_099c701a42fa))
        refreshCollectorCapabilities(grant, owner)
        val timezone = (store.get("timezone") as? JsonPrimitive)?.content ?: ZoneId.systemDefault().id
        val zone = try { ZoneId.of(timezone) } catch (_: Exception) { throw RingFailure("invalid_timezone", RingText.text(R.string.ring_native_3f963cdfbb67)) }
        val local = ZonedDateTime.now(zone)
        val clockElapsed = SystemClock.elapsedRealtime()
        val savedCheckpoint = store.get("checkpoint") as? JsonObject ?: obj { put("completed_queries", JsonArray(emptyList())) }
        var checkpoint = RingHistoryCheckpoint.forSession(savedCheckpoint, local.toLocalDate().toString(), timezone, freshHistory)
        val config = obj {
            put("generation", owner); put("timezone", timezone); put("local_date", local.toLocalDate().toString()); put("history_days", 30)
            put("collect_running_steps", supportedActivityKinds().any { it in setOf("running_steps", "active_energy", "distance") })
            put("collect_workouts", "raw:workouts" in supportedActivityKinds())
            put("workout_cursor", RingHistoryCheckpoint.workoutCursor(checkpoint))
            put("completed_queries", checkpoint["completed_queries"] ?: JsonArray(emptyList()))
            put("clock", obj {
                put("year", local.year); put("month", local.monthValue); put("day", local.dayOfMonth)
                put("hour", local.hour); put("minute", local.minute); put("second", local.second); put("language", 10)
            })
            put("android", obj { put("sdk", Build.VERSION.SDK_INT); put("model", Build.MODEL) })
        }
        diagnosticLog(session, "native_config", obj {
            put("config", config); put("saved_checkpoint", savedCheckpoint); put("effective_checkpoint", checkpoint)
            put("fresh_history", freshHistory)
        })
        val device = adapter?.getRemoteDevice(selected.string("address") ?: throw RingFailure("not_bound", RingText.text(R.string.ring_native_e665cbb518c0))) ?: throw RingFailure("bluetooth_unavailable", RingText.text(R.string.ring_native_9324c80bb67d))
        if (NativeRing.apiVersion() != 1) throw RingFailure("native_version", RingText.text(R.string.ring_native_09fea3435a3e))
        val handle = NativeRing.create(config.toString().toByteArray())
        if (handle == 0L) throw RingFailure("native_init", RingText.text(R.string.ring_native_3550486109f4))
        val link = RingGatt(context, device)
        currentGatt = link
        if (foreground) foregroundGatt = link
        val pending = ArrayDeque<JsonObject>()
        val records = mutableListOf<JsonObject>()
        var nextDeadline: Long? = null
        var done = false
        val seenMetrics = mutableSetOf<String>()
        val invalidMetrics = mutableSetOf<String>()
        var madeProgress = false
        var runningClock: RingRunningClock? = null
        val captures = linkedMapOf<String, JsonObject>()
        val eventBinding = workoutBinding()?.takeIf { it.collectorId == grant.identity && it.address == selected.string("address") }
        val linkToken = UUID.randomUUID().toString()
        val temperatureCapture = RingTemperatureCapture(linkToken)
        val physicalSourceId = eventBinding?.let { sha256("$origin\n${it.address.uppercase()}") }
        val freshVersions = linkedMapOf<String, String>()
        val declarationClocks = linkedMapOf<Long, Instant>()
        val declarationWriteStarts = linkedMapOf<Int, Long>()
        var eventIdentity: JsonObject? = null
        fun temperatureOwner(): String? = if (owner == generation.get() && currentGatt === link && link.connected &&
            eventBinding != null && workoutBinding() == eventBinding) linkToken else null
        fun persistRecords(reason: String) {
            diagnosticLog(session, "checkpoint_commit_started", obj { put("reason", reason); put("record_count", records.size) })
            val outcomes = store.append(records.toList(), checkpoint)
            records.clear()
            diagnosticLog(session, "checkpoint_persisted", obj {
                put("reason", reason); put("checkpoint", checkpoint); put("records", JsonArray(outcomes))
            })
        }
        fun dispatch(input: JsonObject, now: Long = SystemClock.elapsedRealtime()): JsonArray {
            requireOwner()
            if (historyBlocked()) throw CancellationException("History postponed for workout")
            val value = NativeRing.dispatch(handle, input.toString().toByteArray(), now)
            val effects = ringJson.parseToJsonElement(value.toString(Charsets.UTF_8)) as? JsonArray
                ?: throw RingFailure("native_response", RingText.text(R.string.ring_native_b8f40ff751e7))
            val localEvent = effects.any { (it as? JsonObject)?.string("type") == "device_event" }
            val privateSource = input.string("type") == "device_event_source"
            // Device events and their physical source stay local, including the raw input frame.
            val loggedInput = if (localEvent || privateSource || input.string("type") == "notification" &&
                input.string("channel") == "core" && input.string("bytes_hex")?.take(2) == "73") obj {
                    put("type", input.string("type")!!); input["channel"]?.let { put("channel", it) }; put("local_device_event", true)
                } else input
            diagnosticLog(session, "native_input", obj { put("input", loggedInput); put("diagnostic_now_ms", now) })
            diagnosticLog(session, "native_effects", obj {
                put("effects", JsonArray(effects.filter { (it as? JsonObject)?.string("type") != "device_event" })) })
            effects.forEach { pending.addLast(it as JsonObject) }
            return effects
        }
        fun input(type: String, fields: JsonObject = obj {}) = obj {
            put("type", type); put("generation", owner); fields.forEach { (key, value) -> put(key, value) }
        }
        publish { it.copy(phase = "connecting") }
        try {
            withTimeoutOrNull(RingPolicy.SESSION_MS) {
                if (historyBlocked()) throw CancellationException("History postponed for workout")
                link.open(); requireOwner()
                publish { it.copy(phase = "syncing") }
                dispatch(input("connected")); dispatch(input("sync"))
                while (!done) {
                    requireOwner()
                    while (pending.isNotEmpty()) {
                        val effect = pending.removeFirst()
                        when (effect.string("type")) {
                            "read_version" -> {
                                val kind = effect.string("kind")!!
                                val value = link.readVersion(kind) ?: ""
                                publish { if (kind == "firmware") it.copy(firmware = value.ifEmpty { null }) else it.copy(hardware = value.ifEmpty { null }) }
                                devices.refreshFence() // An actual changed version invalidates current presentation immediately.
                                measurements.refreshFence()
                                if (value.isNotEmpty()) queueDeviceState(Instant.now().toString(), versionKind = kind, version = value)
                                dispatch(input("version", obj { put("kind", kind); put("value", value) }))
                                if (kind in setOf("hardware", "firmware") && value.isNotBlank()) freshVersions[kind] = value
                                if (eventIdentity == null && eventBinding != null && physicalSourceId != null &&
                                    freshVersions.keys.containsAll(setOf("hardware", "firmware"))) {
                                    requireOwner()
                                    if (workoutBinding() != eventBinding || currentGatt !== link)
                                        throw CancellationException("Device event binding changed")
                                    val identity = obj { put("model", "qring-r20"); put("hardware", freshVersions.getValue("hardware"))
                                        put("firmware", freshVersions.getValue("firmware")) }
                                    val proof = dispatch(input("device_identity", obj { put("identity", identity) }))
                                    if (proof.none { (it as? JsonObject)?.let { item -> item.string("type") == "device_capabilities" && item["identity"] == identity } == true })
                                        throw RingFailure("identity_not_verified", RingText.text(R.string.ring_native_abce00ae1896))
                                    val source = dispatch(input("device_event_source", obj { put("physical_source_id", physicalSourceId) }))
                                    if (source.any { (it as? JsonObject)?.string("type") == "error" })
                                        throw RingFailure("event_source_unavailable", RingText.text(R.string.ring_native_f64210487fe5))
                                    deviceStore.verifiedIdentity(eventBinding, identity, linkToken); eventIdentity = identity
                                    val currentCapabilities = compiledCapabilityTelemetry(owner, eventBinding.timezone,
                                        freshVersions.getValue("hardware"), freshVersions.getValue("firmware"))
                                    requireOwner()
                                    if (workoutBinding() != eventBinding || currentGatt !== link)
                                        throw CancellationException("Device capability binding changed")
                                    queueDeviceState(Instant.now().toString(), freshIdentity = obj {
                                        put("hardware", freshVersions.getValue("hardware")); put("firmware", freshVersions.getValue("firmware"))
                                    }, capabilityStates = currentCapabilities)
                                    devices.refreshFence()
                                    measurements.refreshFence()
                                }
                            }
                            "device_event" -> {
                                if (eventBinding != null && physicalSourceId != null && eventIdentity != null &&
                                    workoutBinding() == eventBinding && currentGatt === link) {
                                    val event = effect["event"] as? JsonObject ?: continue
                                    devices.acceptEvent(eventBinding, owner, physicalSourceId, event)
                                }
                            }
                            "write" -> {
                                val hex = effect.string("bytes_hex") ?: throw RingFailure("native_packet", RingText.text(R.string.ring_native_70c0fe150ec2))
                                require(hex.length % 2 == 0 && hex.length <= 131_082)
                                val bytes = ByteArray(hex.length / 2) { hex.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
                                if (effect.string("query_id") == "running_steps:0:0") {
                                    runningClock = RingRunningClock(zone, local.toInstant(), clockElapsed,
                                        Instant.now(), SystemClock.elapsedRealtime())
                                }
                                RingProtocolWrite.execute(effect.string("query_id"),
                                    write = {
                                        link.temperatureRequest(temperatureCapture.beforeWrite(effect.string("channel")!!,
                                            effect.string("query_id"), bytes, temperatureOwner(), ZoneId.systemDefault(),
                                            Instant.now(), SystemClock.elapsedRealtimeNanos()))
                                        diagnosticLog(session, "ble_write_started", effect)
                                        if (effect.string("channel") == "core" && effect.string("query_id") in setOf("clock", "declared_capabilities:core_3c"))
                                            declarationWriteStarts[bytes.first().toInt() and 0xff] = SystemClock.elapsedRealtimeNanos()
                                        link.write(effect.string("channel")!!, bytes, effect.bool("response") ?: true)
                                    },
                                    complete = { success -> dispatch(input("write_complete", obj { put("success", success) })) })
                            }
                            "deadline" -> nextDeadline = effect["at_ms"]?.jsonPrimitive?.longOrNull
                            "battery" -> {
                                publish { it.copy(battery = effect.number("percent"), charging = effect.bool("charging")) }
                                queueDeviceState(Instant.now().toString(), battery = effect.number("percent"), charging = effect.bool("charging"))
                            }
                            "declared_capabilities" -> {
                                val declaration = effect["declaration"] as? JsonObject ?: continue
                                val received = effect["received_monotonic_ms"]?.jsonPrimitive?.longOrNull ?: continue
                                val captured = declarationClocks.remove(received) ?: continue
                                val identity = eventIdentity ?: continue
                                requireOwner()
                                if (eventBinding == null || workoutBinding() != eventBinding || currentGatt !== link ||
                                    identity.string("hardware") != stateValue.hardware || identity.string("firmware") != stateValue.firmware) continue
                                val key = when (declaration.number("opcode")) { 0x01 -> "core_01"; 0x3c -> "core_3c"; else -> continue }
                                if (declaration.string("validation_status") != "declared_only") continue
                                queueDeviceState(maxOf(Instant.now(), captured).toString(), freshIdentity = identity,
                                    declaredCapabilities = obj { put(key, JsonObject(declaration +
                                        ("captured_at" to JsonPrimitive(captured.toString())) + ("identity" to obj {
                                            put("hardware", freshVersions.getValue("hardware")); put("firmware", freshVersions.getValue("firmware"))
                                        }))) })
                            }
                            "version" -> publish {
                                if (effect.string("kind") == "firmware") it.copy(firmware = effect.string("value")) else it.copy(hardware = effect.string("value"))
                            }
                            "observation" -> {
                                val observation = effect["observation"] as? JsonObject ?: continue
                                val metric = observation.string("metric") ?: continue
                                val conversion = if (metric == "running_steps") {
                                    val value = RingActivityReport.runningSnapshot(observation, zone, stateValue.firmware, stateValue.hardware)
                                    RingObservation.Conversion(value, if (value == null) "running_snapshot_rejected" else null)
                                } else RingObservation.explain(observation, zone, stateValue.firmware, stateValue.hardware)
                                val record = conversion.record
                                diagnosticLog(session, "observation_conversion", obj {
                                    put("metric", metric); put("opcode", observation["opcode"] ?: JsonNull)
                                    put("observation", observation)
                                    put("accepted", record != null); put("reason", conversion.reason?.let(::JsonPrimitive) ?: JsonNull)
                                    put("protocol_verified", conversion.protocolVerified)
                                    put("record", record ?: JsonNull)
                                })
                                if (record != null) {
                                    if (metric in setOf("active_energy", "distance")) captures[metric] = obj {
                                        put("captured_at", Instant.now().toString()); put("status", "complete")
                                        put("scope", "day"); put("context", "background"); put("date", local.toLocalDate().toString())
                                    }
                                    seenMetrics += metric
                                    records += record
                                    publish { it.copy(metrics = JsonObject(it.metrics + (metric to JsonPrimitive(conversion.metricStatus)))) }
                                } else if (metric !in seenMetrics && observation.string("quality") != "missing" &&
                                    !(metric == "steps" && observation.number("opcode") == 0x48)) {
                                    val status = if (observation.string("quality") in setOf("temperature_layout_unconfirmed", "ambiguous_time")) "unverified" else "invalid"
                                    invalidMetrics += metric
                                    publish { it.copy(metrics = JsonObject(it.metrics + (metric to JsonPrimitive(status)))) }
                                }
                            }
                            "progress" -> {
                                val captureMetric = effect.string("metric")
                                val captureStatus = effect.string("status")
                                if (captureMetric in setOf("steps", "sleep", "heart_rate", "spo2", "temperature", "hrv", "stress", "active_energy", "distance", "running_steps", "running_workouts", "workouts") &&
                                    captureStatus in setOf("complete", "empty", "failed")) {
                                    captures[captureMetric!!] = obj {
                                        put("captured_at", Instant.now().toString()); put("status", captureStatus!!)
                                        put("scope", if (captureMetric == "workouts") "history" else "day"); put("context", "background")
                                        if (captureMetric != "workouts") effect.number("day")?.let { day -> put("date", local.toLocalDate().minusDays(day.toLong()).toString()) }
                                        RingCaptureFailure.code(effect)?.let { put("error_code", it) }
                                    }
                                }
                                val next = effect["checkpoint"] as? JsonObject
                                if (next != null && effect.string("status") in setOf("complete", "empty")) {
                                    checkpoint = JsonObject(next + ("local_date" to JsonPrimitive(local.toLocalDate().toString())) + ("timezone" to JsonPrimitive(timezone)))
                                    persistRecords(effect.string("status")!!)
                                    madeProgress = true
                                }
                                val metric = effect.string("metric")?.takeUnless { it == "declared_capabilities" }
                                if (metric != null && effect.string("status") in setOf("empty", "complete") && metric !in seenMetrics && metric !in invalidMetrics)
                                    publish { it.copy(metrics = JsonObject(it.metrics + (metric to JsonPrimitive("no_data")))) }
                                if (effect.string("status") == "failed" && next != null) {
                                    checkpoint = JsonObject(next + ("local_date" to JsonPrimitive(local.toLocalDate().toString())) + ("timezone" to JsonPrimitive(timezone)))
                                    persistRecords("failed"); madeProgress = true
                                }
                                if (metric != null && effect.string("status") == "failed")
                                    publish { it.copy(metrics = JsonObject(it.metrics + (metric to JsonPrimitive("timeout")))) }
                            }
                            "completed" -> {
                                persistRecords("completed"); done = true
                            }
                            "error" -> {
                                val code = effect.string("code") ?: "protocol_error"
                                if (effect.bool("fatal") == false || effect.bool("retrying") == true || code in setOf("query_timeout", "retry_timeout", "metric_timeout")) {
                                    publish { it.copy(error = obj { put("code", code); put("message", RingText.text(R.string.ring_native_1725318870e4)) }) }
                                } else throw RingFailure(code, RingText.text(R.string.ring_native_8b7f290b331f, code))
                            }
                            "diagnostic" -> publish { it.copy(error = obj {
                                put("code", effect.string("code") ?: "protocol_diagnostic")
                                put("message", RingText.text(R.string.ring_native_c445b2bcaad2))
                            }) }
                        }
                    }
                    if (done) break
                    val wait = (nextDeadline?.minus(SystemClock.elapsedRealtime()) ?: 8_000).coerceIn(1, 8_000)
                    val notice = withTimeoutOrNull(wait) { link.notices.receive() }
                    if (notice == null) dispatch(input("deadline"))
                    else {
                        val declarationOpcode = notice.bytes.firstOrNull()?.toInt()?.and(0xff)
                        if (notice.channel == "core" && declarationOpcode in setOf(0x01, 0x3c)) {
                            val writtenAt = declarationWriteStarts[declarationOpcode]
                            if (writtenAt == null || notice.observedElapsedNanos < writtenAt) {
                                diagnosticLog(session, "declaration_notice_rejected", obj {
                                    put("opcode", declarationOpcode); put("reason", "before_current_gatt_write")
                                })
                                continue
                            }
                            declarationClocks[notice.observedElapsed] = notice.observedAt
                            while (declarationClocks.size > 8) declarationClocks.remove(declarationClocks.keys.first())
                        }
                        val temperatureParseElapsed = SystemClock.elapsedRealtime()
                        val temperatureTimeContext = if (notice.channel == "bulk") temperatureCapture.parsed(
                            notice.temperatureRequestId, notice.observedAt, notice.observedElapsedNanos,
                            temperatureOwner(), ZoneId.systemDefault(), Instant.now()) else null
                        dispatch(input("notification", obj {
                            put("channel", notice.channel); put("bytes_hex", notice.bytes.joinToString("") { "%02x".format(it) })
                            temperatureTimeContext?.let { put("temperature_time_context", it) }
                            if (notice.channel == "core" && notice.bytes.firstOrNull()?.toInt()?.and(0xff) == 0x48)
                                runningClock?.let { put("running_clock", it.proof(notice.observedAt, notice.observedElapsed)) }
                        }), if (temperatureTimeContext != null) temperatureParseElapsed else notice.observedElapsed)
                    }
                }
            }
            requireOwner()
            if (done) {
                // Terminal scans, including exhausted retries, restart on the next scheduled cycle.
                val restarted = RingHistoryCheckpoint.restart(checkpoint)
                store.put("checkpoint", restarted)
                diagnosticLog(session, "checkpoint_reset", obj { put("reason", "terminal_session"); put("checkpoint", restarted) })
            }
            if (!done && madeProgress) continueBackfill(grant)
            diagnosticLog(session, "collection_result", obj {
                put("completed", done); put("made_progress", madeProgress); put("continuation_scheduled", !done && madeProgress)
                put("unpersisted_records", records.size)
            })
            publish { it.copy(lastSync = RingPolicy.timestamp(), queueCount = store.count()) }
        } finally {
            try {
                if (owner == generation.get() && captures.isNotEmpty()) {
                    val observed = maxOf(Instant.now(), captures.values.map { Instant.parse(it.string("captured_at")!!) }.maxOrNull()!!)
                    queueDeviceState(observed.toString(), metricCaptures = JsonObject(captures))
                }
            } finally {
                try { link.close() }
                finally {
                    if (currentGatt === link) currentGatt = null
                    if (foregroundGatt === link) foregroundGatt = null
                    eventBinding?.let { deviceStore.disconnected(it, linkToken) }
                    devices.eventDisconnected(); devices.refreshFence()
                    NativeRing.destroy(handle)
                }
            }
        }
    }

    private fun workoutBinding(): RingWorkoutBinding? {
        val grant = grant() ?: return null
        if (reportedUser != null && reportedUser != grant.user) return null
        val address = stateValue.selected?.string("address") ?: return null
        val version = grant.value["generation"]?.jsonPrimitive?.longOrNull ?: return null
        val zone = (store.get("timezone") as? JsonPrimitive)?.content ?: ZoneId.systemDefault().id
        return RingWorkoutBinding(origin, grant.user, grant.source, grant.identity, address, version, zone)
    }
    private fun measurementCapabilities(): JsonObject {
        if (serverSwitchStarted) return obj { put("capabilities", JsonArray(emptyList())) }
        val native = try {
            RingMeasurementProtocol(generation.get(), workoutBinding()?.timezone ?: ZoneId.systemDefault().id,
                stateValue.hardware, stateValue.firmware).use { it.capabilities() }
        } catch (_: Exception) { obj { put("capabilities", JsonArray(emptyList())) } }
        catch (_: LinkageError) { obj { put("capabilities", JsonArray(emptyList())) } }
        val unknown = try { store.get("control_recovery") is JsonObject || deviceMutationPending() } catch (_: Exception) { true }
        val pendingManual = try {
            workoutBinding()?.let { binding -> deviceStore.controlRecoveries(binding).singleOrNull {
                (it["command"] as? JsonObject)?.string("type") == "measure_once"
            } }?.let { recovery -> obj {
                put("operation_id", recovery["operation_id"] ?: JsonNull); put("command", recovery["command"]!!)
                put("status", "unknown"); put("reason", "command_unknown")
            } }
        } catch (_: Exception) { null }
        return JsonObject(native + mapOf("command_unknown" to JsonPrimitive(unknown), "pending_manual_result" to (pendingManual ?: JsonNull)))
    }
    private fun deviceCapabilities(): JsonObject = try {
        RingMeasurementProtocol(generation.get(), workoutBinding()?.timezone ?: ZoneId.systemDefault().id,
            stateValue.hardware, stateValue.firmware).use { it.deviceCapabilities() }
    } catch (_: Exception) { obj { put("capabilities", JsonArray(emptyList())) } }
    catch (_: LinkageError) { obj { put("capabilities", JsonArray(emptyList())) } }
    private fun deviceMutationPending(): Boolean = try {
        workoutBinding()?.let { deviceStore.blocked(it) } == true
    } catch (_: Exception) { true }
    private fun historyBlocked(): Boolean = measurements.radioBusy() || devices.busy() || otaRadioBlocked() || phoneNotifications.busy()
    fun measurementSnapshot(): JsonObject = measurements.measurementSnapshot()
    fun deviceSnapshot(): JsonObject = devices.snapshot()
    override fun setForegroundVisible(visible: Boolean) {
        if (serverSwitchStarted) return
        foregroundVisible = visible
        if (!visible) {
            val owner = generation.get()
            val operation = stateValue.operationId
            measurements.foregroundDetached(); devices.foregroundDetached(); detachPhoneControls()
            tasks.cancelForeground(); foregroundGatt?.close()
            // A coroutine canceled before its first dispatch cannot run its cancellation handler.
            operation?.let { clearCancelledOperation(owner, it) }
        } else { measurements.locationStateChanged(); refreshPhoneControls(); ota.snapshot(); publish() }
    }
    fun workoutSnapshot(): JsonObject = measurements.workoutSnapshot()
    fun runningTransitions(events: List<RingRunningTransition>, identity: String?, user: String?, source: String?, bindingGeneration: Long) {
        val binding = workoutBinding() ?: return
        if (binding.identity == identity && binding.userId == user && binding.sourceId == source && binding.grantGeneration == bindingGeneration)
            measurements.transitions(events)
    }
    fun recoverWorkout() { if (!serverSwitchStarted) measurements.workoutSnapshot() }
    fun isWorkoutSession(id: String): Boolean = !serverSwitchStarted && measurements.serviceSession() == id
    fun currentWorkoutSession(): String? = if (serverSwitchStarted) null else measurements.serviceSession()
    fun workoutRecorderActive(): Boolean = workoutJob?.isActive == true
    fun workoutLocationReady(): Boolean = measurements.locationReady()
    fun workoutBluetoothReady(): Boolean = workoutBinding() != null &&
        (Build.VERSION.SDK_INT < 31 || context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED) &&
        bluetoothEnabled()
    fun workoutLocationFailed(reason: String) = measurements.locationFailed(reason)
    fun startWorkoutLocation(id: String) = measurements.startLocation(id)
    fun workoutTick() { if (!serverSwitchStarted) measurements.tick() }
    fun needsHeartRateRecorder(id: String) = measurements.needsHeartRateRecorder(id)
    fun stopWorkoutFromNotification(id: String?) {
        if (id != null && isWorkoutSession(id)) measurements.stopFromNotification()
    }
    fun workoutServiceFailed(id: String) {
        if (isWorkoutSession(id)) measurements.commandResult(RingWorkoutCommand.START, false)
    }
    fun workoutServiceDestroyed(id: String) {
        if (isWorkoutSession(id)) { measurements.interrupted("service_destroyed"); workoutJob?.cancel(); currentGatt?.close() }
    }
    private fun queueWorkoutRecords(owner: RingWorkoutBinding, records: List<JsonObject>): RingWorkoutTransferReceipt? {
        if (workoutBinding() != owner) return null
        if (records.isEmpty() || records.any { record ->
                val report = record["report"] as? JsonObject
                report != null && (report["origin"] as? JsonObject)?.string("source") != "phone_recorded"
            }) throw RingFailure("invalid_binding", RingText.text(R.string.ring_workout_5b89de59e76e))
        // Both queues are encrypted. A crash between append and acknowledge repeats identical records.
        val receipt = store.appendWorkoutRecords(owner, records, ::workoutBinding) ?: return null
        if (workoutBinding() != owner) return null
        enqueueUpload()
        return receipt
    }
    /** Only an actual receipt creates telemetry. Reading a cached UI snapshot never calls this. */
    private fun queueDeviceState(observedAt: String, setting: JsonObject? = null, action: String? = null,
        battery: Int? = null, charging: Boolean? = null, versionKind: String? = null, version: String? = null,
        metricCaptures: JsonObject? = null, evidenceRef: String? = null, freshIdentity: JsonObject? = null,
        capabilityStates: JsonObject? = null, declaredCapabilities: JsonObject? = null) {
        val owner = workoutBinding() ?: return
        val confirmedKind = if (setting != null) setting.string("kind") ?: return else null
        val telemetry = obj {
            put("observed_at", observedAt)
            freshIdentity?.string("firmware")?.let { put("firmware", it) }
            freshIdentity?.string("hardware")?.let { put("hardware", it) }
            capabilityStates?.let { put("capabilities", it) }
            declaredCapabilities?.let { put("declared_capabilities", it) }
            metricCaptures?.let { put("metric_captures", it) }
            battery?.takeIf { it in 0..100 }?.let { put("battery_percent", it) }; charging?.let { put("charging", it) }
            if (versionKind in setOf("firmware", "hardware") && !version.isNullOrBlank()) put(versionKind!!, version)
            if (setting != null) {
                stateValue.firmware?.let { put("firmware", it) }; stateValue.hardware?.let { put("hardware", it) }
                val confirmed = obj {
                    put("value", JsonObject(setting.filterKeys { it !in setOf("kind", "confirmed_at", "readback") }))
                    put("confirmed_at", observedAt); put("readback", true); action?.let { put("action", it) }
                    evidenceRef?.let { put("evidence_ref", it) }
                }
                put("last_confirmed_settings", obj { put(confirmedKind!!, confirmed) })
            }
            put("origin", obj { put("provider", "herzring"); put("profile", "qring-r20"); put("source", "android_receipt") })
        }
        if (workoutBinding() != owner) return
        store.append(listOf(obj { put("external_id", "device-state:$observedAt"); put("device_state", telemetry) }),
            store.get("checkpoint") ?: obj { put("completed_queries", JsonArray(emptyList())) })
        enqueueUpload()
    }

    private fun capabilityTelemetry(vararg states: JsonObject): JsonObject {
        val entries = states.flatMap { state ->
            (state["capabilities"] as? JsonArray)?.mapNotNull { it as? JsonObject } ?: emptyList()
        }
        return JsonObject(entries.mapNotNull { cap ->
            val kind = cap.string("kind") ?: return@mapNotNull null
            kind to obj {
                put("status", when {
                    cap.bool("readable") == true || cap.string("status") == "verified" && cap.bool("writable") == true -> "supported"
                    cap.string("status") == "unsupported" -> "unsupported"
                    else -> "unknown"
                })
                cap.bool("readable")?.let { put("readable", it) }
                cap.bool("writable")?.let { put("writable", it) }
                cap.string("evidence_ref")?.let { put("evidence_ref", it) }
                cap.string("reason")?.let { put("reason", it) }
            }
        }.toMap())
    }

    private fun compiledCapabilityTelemetry(owner: Long, timezone: String, hardware: String, firmware: String): JsonObject = try {
        RingMeasurementProtocol(owner, timezone, hardware, firmware).use { protocol ->
            capabilityTelemetry(protocol.capabilities(), protocol.deviceCapabilities())
        }
    } catch (cancelled: CancellationException) { throw cancelled }
    catch (_: Exception) { obj {} }
    catch (_: LinkageError) { obj {} }

    /** One native control session uses the repository's existing BLE mutex. HTTP uses uploadLock only. */
    private inner class MeasurementLink(private val owner: Long, private val binding: RingWorkoutBinding,
        private val foregroundOwner: RingAccountOwner.Token? = null,
        private val manualEvidence: RingManualDiagnostics? = null) : AutoCloseable {
        private val protocol = RingMeasurementProtocol(owner, binding.timezone, stateValue.hardware, stateValue.firmware)
        private var capabilities = protocol.capabilities()
        private var deviceCapabilities = protocol.deviceCapabilities()
        private val linkToken = UUID.randomUUID().toString()
        private val physicalSourceId = sha256("$origin\n${binding.address.uppercase()}")
        private var freshIdentity: JsonObject? = null
        private val pending = ArrayDeque<JsonObject>()
        private var link: RingGatt? = null
        private var readingClock: RingWorkoutClock? = null
        private var manualRequestId: String? = null
        private val foregroundSessionCurrent = if (foregroundOwner != null) sessionCurrent else null
        init { preserveOriginalJournal() }
        private fun current() {
            requireGeneration(owner)
            foregroundOwner?.let(account::requireCurrent)
            if (foregroundSessionCurrent?.invoke() == false) throw CancellationException("Native session changed")
            if (workoutBinding() != binding) throw CancellationException("Workout binding changed")
        }
        @SuppressLint("MissingPermission")
        private suspend fun connection(refreshIdentity: Boolean = false): RingGatt {
            current()
            link?.let { return it }
            if (permissionState() != "granted") throw RingFailure("permission_denied", RingText.text(R.string.ring_native_6b65f6b2dab7))
            if (!bluetoothEnabled()) throw RingFailure("bluetooth_off", RingText.text(R.string.ring_native_55166cb231a6))
            val device = adapter?.getRemoteDevice(binding.address) ?: throw RingFailure("bluetooth_unavailable", RingText.text(R.string.ring_native_9324c80bb67d))
            val opened = RingGatt(context, device)
            link = opened; currentGatt = opened
            if (foregroundOwner != null) foregroundGatt = opened
            opened.open(); current()
            val hardware = opened.readVersion("hardware")
            val firmware = opened.readVersion("firmware")
            current()
            if (hardware.isNullOrBlank() || firmware.isNullOrBlank()) {
                publish { it.copy(hardware = hardware, firmware = firmware) }
                devices.refreshFence(); measurements.refreshFence()
                throw RingFailure("identity_unknown", RingText.text(R.string.ring_native_07a7ba8e3669))
            }
            if (!refreshIdentity && (hardware != stateValue.hardware || firmware != stateValue.firmware)) {
                publish { it.copy(hardware = hardware, firmware = firmware) }
                devices.refreshFence(); measurements.refreshFence()
                throw RingFailure("identity_changed", RingText.text(R.string.ring_native_1e9f7db6f47e))
            }
            val identity = obj { put("model", "qring-r20"); put("hardware", hardware); put("firmware", firmware) }
            publish { it.copy(hardware = hardware, firmware = firmware) }
            pending.addAll(protocol.dispatch("connected"))
            val control = protocol.dispatch("control_identity", obj { put("identity", identity) })
            val deviceIdentity = protocol.dispatch("device_identity", obj { put("identity", identity) })
            capabilities = control.singleOrNull { it.string("type") == "control_capabilities" && it["identity"] == identity }
                ?: throw RingFailure("identity_not_verified", RingText.text(R.string.ring_native_afa92b30012c))
            deviceCapabilities = deviceIdentity.singleOrNull { it.string("type") == "device_capabilities" && it["identity"] == identity }
                ?: throw RingFailure("identity_not_verified", RingText.text(R.string.ring_native_a66a90e49758))
            pending.addAll(control); pending.addAll(deviceIdentity)
            val eventSource = protocol.dispatch("device_event_source", obj { put("physical_source_id", physicalSourceId) })
            if (eventSource.any { it.string("type") == "error" }) throw RingFailure("event_source_unavailable", RingText.text(R.string.ring_native_f64210487fe5))
            pending.addAll(eventSource)
            deviceStore.verifiedIdentity(binding, identity, linkToken); freshIdentity = identity
            manualEvidence?.identity(identity)
            deviceStore.controlRecoveries(binding).forEach { recovery ->
                pending.addAll(protocol.dispatch("control_restore", obj { put("recovery", recovery) }))
            }
            deviceStore.recoveries(binding).forEach { recovery ->
                pending.addAll(protocol.dispatch("device_restore", obj { put("recovery", recovery) }))
            }
            devices.refreshFence()
            measurements.refreshFence()
            queueDeviceState(Instant.now().toString(), freshIdentity = obj {
                put("hardware", hardware); put("firmware", firmware)
            }, capabilityStates = capabilityTelemetry(capabilities, deviceCapabilities))
            return opened
        }
        private suspend fun process(target: String? = null): JsonObject? {
            while (pending.isNotEmpty()) {
                currentCoroutineContext().ensureActive()
                current()
                val effect = pending.removeFirst()
                when (effect.string("type")) {
                    "control_checkpoint" -> {
                        val recovery = effect["recovery"] as? JsonObject ?: throw RingFailure("native_response", RingText.text(R.string.ring_native_1ff657700036))
                        deviceStore.putControlRecovery(binding, recovery, linkToken)
                        manualEvidence?.checkpoint(recovery)
                    }
                    "control_progress" -> {
                        val identity = freshIdentity ?: continue
                        if (effect.string("operation_id") == manualRequestId) {
                            manualEvidence?.progress(effect)
                            measurements.manualProgress(effect, binding, identity)
                        }
                    }
                    "device_event" -> {
                        if (link == null || currentGatt !== link || freshIdentity == null) continue
                        val event = effect["event"] as? JsonObject ?: continue
                        devices.acceptEvent(binding, owner, physicalSourceId, event)
                    }
                    "device_checkpoint" -> {
                        val recovery = effect["recovery"] as? JsonObject ?: throw RingFailure("native_response", RingText.text(R.string.ring_native_bf6cf3980b19))
                        deviceStore.putRecovery(binding, recovery, linkToken) // Synchronous authenticated commit precedes the next Write.
                    }
                    "device_result" -> {
                        val id = effect.string("operation_id")
                        val received = if (effect.string("status") == "verified") readingClock?.utcAt?.toString() else null
                        val result = if (received != null) JsonObject(effect + ("observed_at" to JsonPrimitive(received))) else effect
                        deviceStore.result(binding, result, linkToken)
                        devices.refreshFence()
                        if (id != null) pending.addAll(protocol.dispatch("device_receipt_stored", obj { put("operation_id", id) }))
                        val snapshot = effect["snapshot"] as? JsonObject
                        if (received != null && snapshot != null) queueDeviceState(received, setting = snapshot,
                            action = (effect["command"] as? JsonObject)?.string("type"),
                            evidenceRef = snapshot.string("kind")?.let { RingDeviceCommands.capability(deviceCapabilities, it)?.string("evidence_ref") })
                        if (target == null || id == target) return result
                    }
                    "control_result" -> {
                        val id = effect.string("operation_id")
                        val received = if (effect.string("status") == "verified" && effect["settings"] is JsonObject)
                            readingClock?.utcAt?.toString() else null
                        val result = if (received != null) JsonObject(effect + ("observed_at" to JsonPrimitive(received))) else effect
                        manualEvidence?.terminal(effect, stored = false)
                        deviceStore.controlResult(binding, result, linkToken)
                        manualEvidence?.terminal(result, stored = true)
                        devices.refreshFence()
                        if (received != null) queueDeviceState(received, setting = effect["settings"] as JsonObject,
                            action = (effect["command"] as? JsonObject)?.string("type"),
                            evidenceRef = (effect["settings"] as JsonObject).string("kind")?.let {
                                RingMeasurementCommands.capability(capabilities, "${it}_schedule")?.string("evidence_ref") })
                        if (target == null || id == target) return result
                    }
                    "write" -> {
                        val hex = effect.string("bytes_hex") ?: throw RingFailure("native_packet", RingText.text(R.string.ring_native_02fbf890f21c))
                        require(hex.length % 2 == 0 && hex.length <= 131_082)
                        val bytes = ByteArray(hex.length / 2) { hex.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
                        val radio = connection()
                        // ATT completion is transport evidence; only the portable protocol confirms the command.
                        var writeAwaitReturned = false
                        try {
                            manualEvidence?.writeStarted(effect)
                            radio.write(effect.string("channel")!!, bytes, effect.bool("response") ?: true)
                            val completedAt = SystemClock.elapsedRealtime()
                            writeAwaitReturned = true
                            manualEvidence?.writeAwaitReturned(effect)
                            pending.addAll(protocol.dispatch("write_complete", obj { put("success", true) }, completedAt))
                        } catch (failure: Exception) {
                            val failedAt = SystemClock.elapsedRealtime()
                            if (!writeAwaitReturned) manualEvidence?.writeFailed(effect, failure)
                            pending.addAll(protocol.dispatch("write_complete", obj { put("success", false) }, failedAt))
                            throw failure
                        }
                    }
                    "read_version" -> {
                        val kind = effect.string("kind") ?: continue
                        val read = connection().readVersion(kind) ?: ""
                        pending.addAll(protocol.dispatch("version", obj { put("kind", kind); put("value", read) }))
                    }
                    "live_heart_rate" -> {
                        val rawBpm = effect["bpm"]?.jsonPrimitive?.doubleOrNull ?: continue
                        if (!rawBpm.isFinite() || rawBpm % 1.0 != 0.0 || rawBpm !in 0.0..300.0) continue
                        val bpm = rawBpm.toInt()
                        val clock = readingClock ?: continue
                        val received = effect["received_monotonic_ms"]?.jsonPrimitive?.longOrNull ?: continue
                        if (effect.string("quality") == "protocol_verified" && received == clock.elapsedRealtimeMs)
                            measurements.point(clock, bpm, if (effect.string("sample_quality") == "invalid" || bpm !in 20..250) "invalid" else "valid")
                    }
                    "control_observation" -> {
                        val id = effect.string("operation_id")?.takeIf { it == manualRequestId } ?: continue
                        manualEvidence?.observation(effect)
                        val clock = readingClock
                        if (clock == null) { manualEvidence?.observationSkipped("receipt_clock_unavailable"); continue }
                        persistManualObservation(effect["observation"] as? JsonObject ?: continue, clock, id)
                    }
                    "error" -> if (effect.bool("fatal") != false) throw RingFailure(effect.string("code") ?: "protocol_error", RingText.text(R.string.ring_native_b996630a00cb))
                }
            }
            return null
        }
        private fun persistManualObservation(observation: JsonObject, clock: RingWorkoutClock, id: String) {
            val identity = freshIdentity
            if (identity == null) { manualEvidence?.observationSkipped("identity_unavailable"); return }
            val record = RingManualObservation.encode(binding, clock, observation)
            if (record == null) { manualEvidence?.conversion(null); return }
            val persist = { store.append(listOf(record), store.get("checkpoint") ?: obj { put("completed_queries", JsonArray(emptyList())) }) }
            if (manualEvidence != null) manualEvidence.persistObservation(record.string("external_id")!!, persist) else persist()
            measurements.manualObservation(record, binding, identity, id); enqueueUpload()
            RingManualObservation.capture(record)?.let { capture ->
                queueDeviceState(clock.utcAt.toString(), metricCaptures = capture)
            }
        }
        suspend fun request(command: JsonObject, id: String, cancellation: ReceiveChannel<Unit>? = null): JsonObject {
            current()
            // Cached registry support alone never authorizes a write on a different BLE connection.
            val admitted = if (command.string("type") in setOf("start_sport", "pause_sport", "resume_sport", "stop_sport")) {
                val requested = (command["plan"] as? JsonObject)?.number("requested_id")
                requested?.let { RingSportPlan.permitted(capabilities, RingSportPlan.select(capabilities, it), command.string("type")!!.removeSuffix("_sport")) } == true
            } else if (command.string("type") == "read_settings") command.string("kind")?.let {
                RingMeasurementCommands.readable(capabilities, it)
            } == true else RingMeasurementCommands.kind(command)?.let { RingMeasurementCommands.supported(capabilities, it) } == true
            if (admitted) {
                manualEvidence?.admitted()
                connection(); process(id)
                deviceStore.controlReceipt(binding, id, command)?.let { return it }
                if (command.string("type") != "read_settings" && deviceStore.blocked(binding))
                    throw RingFailure("physical_command_unknown", RingText.text(R.string.ring_native_1f6a0669c3fc))
            }
            val manual = command.string("type") == "measure_once"
            if (manual) manualRequestId = id
            pending.addAll(protocol.request(command, id))
            fun cancelRequested() {
                if (manual && cancellation?.tryReceive()?.isSuccess == true) {
                    val cancelledAt = SystemClock.elapsedRealtime()
                    manualEvidence?.cancellationRequested()
                    pending.addAll(protocol.dispatch("control_cancel", obj { put("operation_id", id) }, cancelledAt))
                }
            }
            cancelRequested()
            process(id)?.let { return it }
            // The core owns the 45 s measurement + 8 s stop deadlines. This outer bound
            // only protects against a lost native effect; it does not classify the ring as idle.
            return withTimeoutOrNull(if (manual) 60_000 else 10_000) {
                while (true) {
                    receive(500)
                    cancelRequested()
                    if (manual) pending.addAll(protocol.dispatch("deadline"))
                    process(id)?.let { return@withTimeoutOrNull it }
                }
                @Suppress("UNREACHABLE_CODE") obj {}
            } ?: obj { put("status", "unknown"); put("operation_id", id); put("command", command); put("reason", "response_lost") }
        }
        suspend fun deviceRequest(command: JsonObject, id: String, reconcile: Boolean): JsonObject {
            current()
            if (command.string("type") == "identity_probe") {
                connection(refreshIdentity = true); process(id)
                return obj { put("operation_id", id); put("command", command); put("status", "verified"); put("reason", "fresh_identity_confirmed")
                    put("snapshot", JsonNull); put("wire_state", JsonNull); put("observed_at", Instant.now().toString()) }
            }
            if (!reconcile) RingDeviceCommands.requireQualified(deviceCapabilities, command)
            connection(); process(id)
            if (!reconcile) {
                deviceStore.receipt(binding, id, command)?.let { return it }
                if (command.string("type") != "read" && deviceStore.blocked(binding))
                    throw RingFailure("physical_command_unknown", RingText.text(R.string.ring_native_1f6a0669c3fc))
            }
            val controlReconcile = reconcile && command.string("type") == "reconcile_control"
            if (controlReconcile && deviceStore.controlRecovery(binding, id) == null)
                throw RingFailure("operation_unknown", RingText.text(R.string.ring_native_660ccdceb413))
            pending.addAll(if (controlReconcile) protocol.dispatch("control_reconcile", obj { put("operation_id", id) })
                else if (reconcile) protocol.dispatch("device_reconcile", obj { put("operation_id", id) })
                else protocol.dispatch("device_request", obj { put("request", obj { put("operation_id", id); put("command", command) }) }))
            process(id)?.let { return it }
            return withTimeoutOrNull(10_000) {
                while (true) { receive(500); process(id)?.let { return@withTimeoutOrNull it } }
                @Suppress("UNREACHABLE_CODE") obj {}
            } ?: obj { put("status", "unknown"); put("operation_id", id); put("command", command); put("reason", "response_lost") }
        }
        suspend fun receive(timeout: Long = 1_000) {
            current()
            val radio = link
            if (radio == null) { delay(timeout); pending.addAll(protocol.dispatch("deadline")); return }
            val notice = withTimeoutOrNull(timeout) { radio.notices.receive() }
            if (notice == null) pending.addAll(protocol.dispatch("deadline"))
            else {
                manualEvidence?.notice(notice.channel, notice.bytes, RingManualDiagnostics.Time(
                    notice.observedAt, notice.observedElapsed, notice.observedElapsedNanos))
                val currentClock = measurements.clock()
                readingClock = RingWorkoutClock(notice.observedAt, notice.observedElapsed, currentClock.bootId, notice.observedElapsedNanos)
                pending.addAll(protocol.dispatch("notification", obj {
                    put("channel", notice.channel); put("bytes_hex", notice.bytes.joinToString("") { "%02x".format(it) })
                }, notice.observedElapsed))
            }
        }
        suspend fun observations() { process() }
        override fun close() {
            val old = link; link = null
            old?.close(); if (currentGatt === old) currentGatt = null
            if (foregroundGatt === old) foregroundGatt = null
            deviceStore.disconnected(binding, linkToken); devices.eventDisconnected(); devices.refreshFence()
            protocol.close()
        }
    }
    private suspend fun executeMeasurement(command: JsonObject, id: String, expected: RingWorkoutBinding, cancellation: ReceiveChannel<Unit>?): JsonObject {
        val foregroundOwner = account.capture()
        return lock.withLock {
            currentCoroutineContext().ensureActive()
            account.requireCurrent(foregroundOwner)
            if (workoutBinding() != expected) throw CancellationException("Measurement binding changed")
            // Capture one old-epoch session before any connection; callbacks never acquire a later account's session.
            val evidence = if (command.string("type") == "measure_once") try {
                val diagnosticGeneration = generation.get()
                val session = diagnosticSession()
                RingManualDiagnostics(id, command.string("metric")!!, expected.sourceId, expected.collectorId, expected.grantGeneration,
                    current = {
                        requireGeneration(diagnosticGeneration)
                        account.requireCurrent(foregroundOwner)
                        // Read directly: grant()/workoutBinding() can change storageUnavailable on a failed read.
                        if (storageUnavailable || forgetPending) false else {
                            val currentGrant = (store.get("grant") as? JsonObject)?.let(::RingGrant)
                            currentGrant?.user == expected.userId && currentGrant?.source == expected.sourceId &&
                                currentGrant?.identity == expected.collectorId &&
                                currentGrant?.value?.get("generation")?.jsonPrimitive?.longOrNull == expected.grantGeneration &&
                                stateValue.selected?.string("address") == expected.address &&
                                ((store.get("timezone") as? JsonPrimitive)?.content ?: ZoneId.systemDefault().id) == expected.timezone
                        }
                    }, clock = {
                        val nanos = SystemClock.elapsedRealtimeNanos()
                        RingManualDiagnostics.Time(Instant.now(), nanos / 1_000_000L, nanos)
                    }, append = { kind, payload -> diagnosticLog(session, kind, payload) })
            } catch (_: Exception) { null } else null
            try {
                deviceStore.controlReceipt(expected, id, command)?.let { evidence?.returned(it, cached = true); return@withLock it }
                if (deviceMutationPending() || otaRadioBlocked()) throw RingFailure("device_command_unknown", RingText.text(R.string.ring_native_c2b32b712e39))
                MeasurementLink(generation.get(), expected, foregroundOwner, evidence).use { it.request(command, id, cancellation) }
                    .also { evidence?.returned(it) }
            } catch (failure: Exception) { evidence?.failed(failure); throw failure }
        }
    }
    private suspend fun executeDevice(command: JsonObject, id: String, expected: RingWorkoutBinding, reconcile: Boolean): JsonObject {
        val foregroundOwner = if (command.string("type") == "notification") null else account.capture()
        val foregroundSessionCurrent = if (foregroundOwner != null) sessionCurrent else null
        return lock.withLock {
            currentCoroutineContext().ensureActive()
            foregroundOwner?.let(account::requireCurrent)
            if (foregroundSessionCurrent?.invoke() == false) throw CancellationException("Native session changed")
            if (workoutBinding() != expected) throw CancellationException("Device binding changed")
            if (!reconcile) deviceStore.receipt(expected, id, command)?.let { return@withLock it }
            if (measurements.radioBusy() || otaRadioBlocked(readOnly = command.string("type") == "identity_probe"))
                throw RingFailure("workout_busy", RingText.text(R.string.ring_native_dd2f2afe5fa5))
            if (!reconcile && command.string("type") !in setOf("read", "identity_probe") && deviceMutationPending())
                throw RingFailure("device_command_unknown", RingText.text(R.string.ring_native_bbf19be46aa4))
            MeasurementLink(generation.get(), expected, foregroundOwner).use { it.deviceRequest(command, id, reconcile) }
        }
    }
    fun recordWorkout(sessionId: String, finished: () -> Unit) {
        if (!isWorkoutSession(sessionId) || !needsHeartRateRecorder(sessionId) || workoutJob?.isActive == true) return
        tasks.cancelForeground(); foregroundGatt?.close(); currentGatt?.close()
        workoutJob = scope.launch {
            var activeId = sessionId
            try {
                lock.withLock {
                    val binding = measurements.activeBinding()?.takeIf { it == workoutBinding() }
                        ?: throw CancellationException("Binding changed")
                    MeasurementLink(generation.get(), binding).use { radio ->
                        while (isWorkoutSession(activeId)) {
                            if (measurements.pendingCommand() == RingWorkoutCommand.START) {
                                val plan = measurements.sportPlan()
                                if (measurements.sportRequested() || plan == null) {
                                    val started = radio.request(plan?.command("start_sport") ?: obj { put("type", "start_workout"); put("kind", "run") }, "$activeId:start")
                                    if (started.string("status") != "verified") {
                                        measurements.commandResult(RingWorkoutCommand.START, if (started.string("status") == "unknown") null else false)
                                        return@withLock
                                    }
                                    measurements.sportConfirmed(started)
                                }
                                val live = radio.request(obj { put("type", "start_live_heart_rate") }, "$activeId:live")
                                if (live.string("status") != "verified") {
                                    measurements.commandResult(RingWorkoutCommand.START, null)
                                    return@withLock
                                }
                                measurements.commandResult(RingWorkoutCommand.START, true)
                                if (measurements.radioOutcomeUnknown()) return@withLock
                            }
                            while (isWorkoutSession(activeId)) {
                                measurements.tick()
                                if (measurements.radioOutcomeUnknown()) return@withLock
                                val control = measurements.pendingCommand()
                                if (control == RingWorkoutCommand.PAUSE || control == RingWorkoutCommand.RESUME) {
                                    val plan = measurements.sportPlan() ?: throw RingFailure("sport_unavailable", RingText.text(R.string.ring_workout_d9cb519cb3a8))
                                    val reply = radio.request(plan.command(if (control == RingWorkoutCommand.PAUSE) "pause_sport" else "resume_sport"),
                                        "$activeId:${control.name.lowercase()}:${UUID.randomUUID()}")
                                    measurements.commandResult(control, when (reply.string("status")) { "verified" -> true; "rejected" -> false; else -> null })
                                    if (reply.string("status") == "unknown") return@withLock
                                }
                                if (measurements.pendingCommand() == RingWorkoutCommand.STOP) {
                                    // A later explicit safe-stop attempt has a new nonce; lost receipts are never retried automatically.
                                    val nonce = UUID.randomUUID().toString()
                                    val liveStop = radio.request(obj { put("type", "stop_live_heart_rate") }, "$activeId:live-stop:$nonce")
                                    val plan = measurements.sportPlan()
                                    val stopped = if (liveStop.string("status") == "verified" && (measurements.sportRequested() || plan == null))
                                        radio.request(plan?.command("stop_sport") ?: obj { put("type", "stop_workout") }, "$activeId:stop:$nonce") else liveStop
                                    measurements.commandResult(RingWorkoutCommand.STOP, if (stopped.string("status") == "verified") true else null)
                                    if (stopped.string("status") == "verified") measurements.tick()
                                    break
                                }
                                radio.receive(); radio.observations()
                            }
                            val next = measurements.serviceSession()
                            if (next == null || next == activeId || measurements.activeBinding() != binding) break
                            activeId = next // Old terminal was encrypted and copied before the next START was persisted.
                        }
                    }
                }
            } catch (cancelled: CancellationException) { if (isWorkoutSession(activeId)) measurements.interrupted("recording_cancelled"); throw cancelled }
            catch (_: Exception) { if (isWorkoutSession(activeId)) measurements.interrupted("bluetooth_disconnected") }
            finally {
                if (workoutJob === currentCoroutineContext()[Job]) workoutJob = null
                withContext(NonCancellable + Dispatchers.Main) { finished() }
            }
        }
    }

    private inner class OtaLink(private val owner: Long, private val binding: RingWorkoutBinding,
        private val foregroundOwner: RingAccountOwner.Token, private val readOnly: Boolean = false) : RingOtaLink, AutoCloseable {
        private var link: RingGatt? = null
        private val assembler = RingOtaNoticeAssembler()
        private val received = ArrayDeque<RingOtaNotice>()
        private var activated = false
        private fun current() {
            requireGeneration(owner); account.requireCurrent(foregroundOwner)
            if (workoutBinding() != binding) throw CancellationException("OTA binding changed")
        }
        @SuppressLint("MissingPermission")
        private suspend fun connection(): RingGatt {
            current(); link?.let { return it }
            if (permissionState() != "granted") throw RingFailure("permission_denied", RingText.text(R.string.ring_ota_transport_3e321dc48768))
            if (!bluetoothEnabled()) throw RingFailure("bluetooth_off", RingText.text(R.string.ring_native_55166cb231a6))
            val device = adapter?.getRemoteDevice(binding.address) ?: throw RingFailure("bluetooth_unavailable", RingText.text(R.string.ring_native_9324c80bb67d))
            val opened = RingGatt(context, device); link = opened; currentGatt = opened
            opened.open(); current(); return opened
        }
        private suspend fun freshIdentity(): JsonObject {
            val radio = connection()
            val hardware = radio.readVersion("hardware")?.takeIf { it.isNotBlank() }
                ?: throw RingFailure("ota_identity_unknown", RingText.text(R.string.ring_ota_transport_9f9e20a22cb8))
            val firmware = radio.readVersion("firmware")?.takeIf { it.isNotBlank() }
                ?: throw RingFailure("ota_identity_unknown", RingText.text(R.string.ring_ota_transport_0a3a5318083c))
            current()
            val currentCapabilities = compiledCapabilityTelemetry(owner, binding.timezone, hardware, firmware)
            current()
            publish { it.copy(hardware = hardware, firmware = firmware) }
            queueDeviceState(Instant.now().toString(), freshIdentity = obj {
                put("hardware", hardware); put("firmware", firmware)
            }, capabilityStates = currentCapabilities)
            devices.refreshFence() // Capability lookup now uses the fresh FW, never the previous image's proofs.
            measurements.refreshFence()
            return obj { put("model", "qring-r20"); put("hardware", hardware); put("firmware", firmware) }
        }
        override suspend fun freshDevice(): RingOtaDevice {
            if (readOnly) throw RingFailure("ota_read_only", RingText.text(R.string.ring_ota_transport_ef5c6871f394))
            val identity = freshIdentity(); val radio = connection()
            while (radio.notices.tryReceive().isSuccess) { /* Only a response after this fresh read can establish power. */ }
            val requestedAt = SystemClock.elapsedRealtime()
            radio.write("core", RingOtaBatteryRead.request(), true)
            val power = withTimeoutOrNull(5_000) {
                while (true) {
                    val notice = radio.notices.receive(); current()
                    if (notice.channel != "core" || notice.observedElapsed < requestedAt) continue
                    RingOtaBatteryRead.reading(notice.bytes)?.let { return@withTimeoutOrNull it }
                }
                @Suppress("UNREACHABLE_CODE") null
            }
            if (power != null) {
                current(); publish { it.copy(battery = power.percent, charging = power.charging) }
                queueDeviceState(Instant.now().toString(), battery = power.percent, charging = power.charging)
            }
            return RingOtaDevice(binding.address, identity, power?.percent, power?.charging)
        }
        override suspend fun activateLoader(frames: List<ByteArray>) {
            current(); if (readOnly) throw RingFailure("ota_read_only", RingText.text(R.string.ring_ota_transport_5044e23243c7))
            if (activated) throw RingFailure("ota_loader_repeated", RingText.text(R.string.ring_ota_transport_a221f3d4e9b6))
            require(frames.isNotEmpty() && frames.all { it.size == 16 })
            activated = true // Set before the first uncertain ATT completion; no retry can repeat activation.
            val radio = connection()
            for (frame in frames) { current(); radio.write("core", frame, true) }
        }
        override suspend fun writeBulk(frame: ByteArray) {
            current(); if (readOnly) throw RingFailure("ota_read_only", RingText.text(R.string.ring_ota_transport_b2a0fc62b47d))
            val radio = connection()
            require(frame.size in 6..16_390)
            var offset = 0
            while (offset < frame.size) {
                current(); val end = minOf(offset + radio.maximumWriteBytes, frame.size)
                radio.write("bulk", frame.copyOfRange(offset, end), false); offset = end
            }
        }
        override suspend fun receiveBulk(timeoutMs: Long): RingOtaNotice? {
            current(); received.removeFirstOrNull()?.let { return it }
            return withTimeoutOrNull(timeoutMs.coerceIn(1, 15_000)) {
                val radio = connection()
                while (true) {
                    val notice = radio.notices.receive(); current()
                    if (notice.channel != "bulk") continue
                    received.addAll(assembler.push(notice.bytes, notice.observedElapsed))
                    received.removeFirstOrNull()?.let { return@withTimeoutOrNull it }
                }
                @Suppress("UNREACHABLE_CODE") null
            }
        }
        override suspend fun reconnectForFirmware(): RingOtaDevice {
            current(); retire(); assembler.reset(); received.clear()
            // GATT HW/FW reads only: no core03 query, loader activation or flash frames.
            return RingOtaDevice(binding.address, freshIdentity(), null, null)
        }
        private fun retire() { val old = link; link = null; old?.close(); if (currentGatt === old) currentGatt = null }
        override fun close() { retire(); assembler.reset(); received.clear() }
    }

    override val otaStates: StateFlow<RingOtaState> get() = ota.states
    override fun otaCatalog(owner: RingOtaOwner): RingOtaCatalogState { ownedPayload(obj {}); return ota.catalog(owner) }
    @Synchronized override fun ota(owner: RingOtaOwner, action: RingOtaAction) { ownedPayload(obj {}); ota.command(owner, action) }
    override fun otaScreenDetached(owner: RingOtaOwner) {
        val binding = workoutBinding() ?: return
        if (binding.userId == owner.userId && binding.sourceId == owner.sourceId && binding.grantGeneration == owner.bindingGeneration)
            ota.foregroundDetached()
    }

    /** Server departure removes retained evidence even when no device or live queue is visible. */
    suspend fun hasServerData(): Boolean = withContext(Dispatchers.IO) {
        store.hasData() || RingWorkoutStore(context, origin).use { it.hasData() } || deviceStore.hasData() ||
            RingOtaStore(context, origin).hasData() || diagnostics?.hasData() != false ||
            listOf("notifications", "gestures").any {
                context.getSharedPreferences("ring-$it-${sha256(origin).take(24)}", Context.MODE_PRIVATE).all.isNotEmpty()
            }
    }

    /** The firmware check and the fence share the OTA entry point's monitor. */
    @Synchronized fun prepareServerSwitch(): RingServerSwitchPreparation {
        serverSwitchPreparation?.let { return it }
        if (otaRuntime.isInitialized() && ota.readOnlyBusy()) return RingServerSwitchPreparation.FirmwareUpdateActive
        val previous = grant(includePending = true)
        val previousServer = AndroidNativeOrigins.serverOrigin(context, origin)
        serverSwitchStarted = true
        nativeSession = null; sessionCurrent = { false }
        generation.incrementAndGet(); account.invalidateForeground()
        workoutScreenLease.value = null; workoutPermissionLease = null
        workoutJob?.cancel(); currentGatt?.close(); foregroundGatt?.close()
        scope.cancel()
        return RingServerSwitchPreparation.Ready(erase = {
            withContext(Dispatchers.IO) {
                work.cancelAllWorkByTag(WORK_TAG).result.get()
                context.stopService(Intent(context, RingWorkoutService::class.java))
                close()
                // WorkManager marks cancellation before a blocking worker has fully unwound.
                // Its radio/upload locks must be released before destroying the shared stores.
                uploadLock.withLock {
                    lock.withLock {
                        store.clearForServerSwitch()
                        RingWorkoutStore(context, origin).use { it.clearForServerSwitch() }
                        deviceStore.clearForServerSwitch()
                        RingOtaStore(context, origin).clearForServerSwitch()
                        (diagnostics ?: RingDiagnostics(context, origin)).clearForServerSwitch()
                        for (name in listOf("notifications", "gestures")) {
                            check(context.getSharedPreferences("ring-$name-${sha256(origin).take(24)}", Context.MODE_PRIVATE)
                                .edit().clear().commit()) { "Ring preferences could not be cleared" }
                            RingCrypto("assistant.ring.$name.v1.${sha256(origin).take(24)}").delete()
                        }
                    }
                }
            }
        }, revokeGrant = {
            previous?.let {
                RingHttp(previousServer).request("POST", "/v1/personal/rings/collector/revoke", obj {},
                    token = it.token, timeoutMs = 4_000)
            }
        }).also { serverSwitchPreparation = it }
    }

    /** Process-equivalent shutdown for host lifetime tests; Activity teardown keeps the repository alive. */
    fun close() {
        scope.cancel(); currentGatt?.close()
        runBlocking { scope.coroutineContext[Job]?.join() }
        // Closing a fixture must not create new subscribers or let cancelled publishers
        // reopen a database after its owner has removed it.
        if (notificationRuntime.isInitialized()) notificationRuntime.value.close()
        if (gestureRuntime.isInitialized()) gestureRuntime.value.close()
        if (otaRuntime.isInitialized()) otaRuntime.value.close()
        if (deviceRuntime.isInitialized()) deviceRuntime.value.close()
        if (measurementRuntime.isInitialized()) measurementRuntime.value.close()
        store.close(); diagnostics?.close()
        synchronized(Companion) { if (instance === this && !serverSwitchStarted) instance = null }
    }

    companion object {
        @Volatile private var instance: RingRepository? = null
        private const val WORK_TAG = "assistant.ring"
        fun get(context: Context, origin: String): RingRepository = instance ?: synchronized(this) {
            instance ?: RingRepository(context.applicationContext, origin).also { instance = it }
        }
        private fun collectName(id: String) = "ring.collect.$id"
        private fun uploadName(id: String) = "ring.upload.$id"
        private fun transferName(id: String) = "ring.workout-transfer.$id"
        fun configuredOrigin(context: Context): String? = try {
            AndroidNativeOrigins.homeOrigin(context)
        } catch (_: Exception) { null }
        private fun continuationName(id: String) = "ring.continue.$id"
    }
}
