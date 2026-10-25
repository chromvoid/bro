package assistant.mobile.platform.ring

import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.*

/** A foreground session lease. The bearer stays in memory and never enters a ring store. */
data class RingSession(val userId: String, val bearerToken: String, val generation: Long = 0,
    val instance: String? = null, val origin: String? = null, val nativeDeviceId: String? = null) {
    override fun toString(): String = "RingSession(userId=$userId, generation=$generation)"
}

/** Native screens observe typed snapshots; the protocol and its JSON remain inside the adapter. */
interface RingController {
    val states: StateFlow<RingNativeState>
    val deviceStates: StateFlow<RingDeviceState>
    val measurementStates: StateFlow<RingMeasurementState>
    val workoutStates: StateFlow<RingWorkoutNativeState> get() = RingWorkoutNativeDefaults.states
    fun workout(action: RingWorkoutAction) {}
    fun workoutScreen(visible: Boolean, generation: Long, userId: String, sourceId: String,
        grantGeneration: Long, canRead: Boolean = true, canWrite: Boolean = true) {}
    fun workoutPermissionNames(kind: RingWorkoutPermission): Array<String> = emptyArray()
    fun workoutPermissionResult(kind: RingWorkoutPermission, granted: Boolean) {}
    fun workoutLocationSettingsIntent(): android.content.Intent = android.content.Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
    fun permissionNames(): Array<String>
    fun permissionResult(granted: Boolean)
    fun scan()
    fun select(address: String)
    fun sync()
    fun background(enabled: Boolean)
    fun forget()
    fun device(action: RingDeviceAction)
    fun measurement(action: RingMeasurementAction)
    fun setForegroundVisible(visible: Boolean)
}

data class RingNativeError(val code: String, val message: String)
data class RingNativeDevice(val address: String, val name: String, val rssi: Int? = null)
data class RingIdentity(val model: String? = null, val hardware: String? = null, val firmware: String? = null)

data class RingNativeState(
    val profile: String = "qring-r20",
    val phase: String = "idle",
    val permission: String = "required",
    val bluetoothEnabled: Boolean = false,
    val selected: RingNativeDevice? = null,
    val battery: Int? = null,
    val charging: Boolean? = null,
    val firmware: String? = null,
    val hardware: String? = null,
    val backgroundEnabled: Boolean = false,
    val queueCount: Int = 0,
    val lastSync: String? = null,
    val lastUpload: String? = null,
    val error: RingNativeError? = null,
    val operationId: String? = null,
    val userId: String? = null,
    val sourceId: String? = null,
    val scanResults: List<RingNativeDevice> = emptyList(),
    val metricStatus: Map<String, String> = emptyMap(),
    val collectionSupport: Map<String, String> = emptyMap(),
) {
    val busy: Boolean get() = phase !in setOf("idle", "error")
    val syncBlocked: Boolean get() = busy || permission != "granted" || !bluetoothEnabled || error?.code == "store_unavailable"

    companion object {
        internal fun fromJson(value: JsonObject): RingNativeState = RingNativeState(
            profile = value.nativeString("profile") ?: "qring-r20",
            phase = value.nativeString("phase") ?: "idle",
            permission = value.nativeString("permission") ?: "required",
            bluetoothEnabled = value.nativeBool("bluetooth_enabled") ?: false,
            selected = value.nativeObject("selected")?.nativeDevice(),
            battery = value.nativeInt("battery"), charging = value.nativeBool("charging"),
            firmware = value.nativeString("firmware"), hardware = value.nativeString("hardware"),
            backgroundEnabled = value.nativeBool("background_enabled") ?: false,
            queueCount = value.nativeInt("queue_count") ?: 0,
            lastSync = value.nativeString("last_sync"), lastUpload = value.nativeString("last_upload"),
            error = value.nativeObject("error")?.nativeError(), operationId = value.nativeString("operation_id"),
            userId = value.nativeString("user_id"), sourceId = value.nativeString("source_id"),
            scanResults = value.nativeObjects("scan_results").mapNotNull { it.nativeDevice() },
            metricStatus = value.nativeObject("metric_status").nativeStringMap(),
            collectionSupport = value.nativeObject("collection_support").nativeStringMap(),
        )
    }
}

enum class RingDeviceSettingKind(val wireValue: String) {
    ALARMS("alarms"), DRINK_REMINDERS("drink_reminders"), DND("dnd"), SEDENTARY("sedentary"),
    POWER_SAVING("power_saving"), GESTURES("gestures"), PROFILE("profile"), CLOCK("clock");

    companion object { fun fromWire(value: String?): RingDeviceSettingKind? = entries.firstOrNull { it.wireValue == value } }
}

enum class RingDeviceOperation(val wireValue: String) {
    FIND_RING("find_ring"), BIND("bind"), UNBIND("unbind"), FACTORY_RESET("factory_reset"),
    POWER_OFF("power_off"), TASBIH_START("tasbih_start"), TASBIH_STOP("tasbih_stop");

    val capabilityKind: String get() = if (this == TASBIH_START || this == TASBIH_STOP) "counter" else wireValue
    val requiresConfirmation: Boolean get() = this in setOf(UNBIND, FACTORY_RESET, POWER_OFF)
}

data class RingAlarmSlot(val slot: Int, val enabled: Boolean, val hour: Int, val minute: Int, val weekdays: Int)

/** Field names are the fixed device setting schema; the runtime validates the whole setting before any BLE write. */
data class RingDeviceSetting(
    val kind: RingDeviceSettingKind,
    val enabled: Boolean? = null,
    val numbers: Map<String, Int> = emptyMap(),
    val choices: Map<String, String> = emptyMap(),
    val slots: List<RingAlarmSlot> = emptyList(),
    val is24Hour: Boolean? = null,
) {
    internal fun json(): JsonObject = buildJsonObject {
        put("kind", kind.wireValue)
        enabled?.let { put("enabled", it) }
        numbers.forEach { (key, value) -> put(key, value) }
        choices.forEach { (key, value) -> put(key, value) }
        is24Hour?.let { put("is_24_hour", it) }
        if (kind == RingDeviceSettingKind.ALARMS || kind == RingDeviceSettingKind.DRINK_REMINDERS) {
            put("slots", JsonArray(slots.map { slot -> buildJsonObject {
                put("slot", slot.slot); put("enabled", slot.enabled); put("hour", slot.hour)
                put("minute", slot.minute); put("weekdays", slot.weekdays)
            } }))
        }
    }

    companion object {
        internal fun fromJson(value: JsonObject): RingDeviceSetting? {
            val kind = RingDeviceSettingKind.fromWire(value.nativeString("kind")) ?: return null
            return RingDeviceSetting(
                kind = kind, enabled = value.nativeBool("enabled"), is24Hour = value.nativeBool("is_24_hour"),
                numbers = value.mapNotNull { (key, item) ->
                    (item as? JsonPrimitive)?.takeUnless { it.isString }?.intOrNull?.let { key to it }
                }.toMap(),
                choices = value.filterKeys { it in setOf("sex", "units", "mode", "hand") }.mapNotNull { (key, item) ->
                    (item as? JsonPrimitive)?.contentOrNull?.let { key to it }
                }.toMap(),
                slots = value.nativeObjects("slots").mapNotNull { slot ->
                    RingAlarmSlot(
                        slot.nativeInt("slot") ?: return@mapNotNull null,
                        slot.nativeBool("enabled") ?: return@mapNotNull null,
                        slot.nativeInt("hour") ?: return@mapNotNull null,
                        slot.nativeInt("minute") ?: return@mapNotNull null,
                        slot.nativeInt("weekdays") ?: return@mapNotNull null,
                    )
                },
            )
        }
    }
}

data class RingCapability(
    val kind: String,
    val status: String,
    val reason: String,
    val readable: Boolean = false,
    val writable: Boolean = false,
    val supportedIntervalsMinutes: List<Int> = emptyList(),
    val autonomousNight: Boolean? = null,
) {
    val canRead: Boolean get() = readable
    val canWrite: Boolean get() = status == "verified" && writable
}

data class RingPhysicalFence(
    val blocked: Boolean = false, val reason: String? = null,
    val refreshRequired: Boolean = false, val canReconcile: Boolean = false,
)
data class RingPendingOperation(val operationId: String, val domain: String, val canReconcile: Boolean, val reason: String)
data class RingDeviceWireState(val kind: String, val rawPayloadHex: List<String>, val semanticStatus: String)

data class RingOperationResult(
    val status: String, val reason: String, val operationId: String? = null,
    val commandType: String? = null, val metric: RingManualMetric? = null,
    val settingKind: RingDeviceSettingKind? = null, val deviceOperation: RingDeviceOperation? = null,
)

data class RingEventAvailability(val kind: String, val status: String, val reason: String, val evidenceRef: String?)
data class RingDeviceEvent(
    val qualification: String, val receivedAt: String?, val rawPayloadHex: String?,
    val candidateKind: String?, val candidateCount: Int?, val resolvedKind: String?, val resolvedCount: Int?,
    val physicalSourceId: String?, val sourceEvidenceRef: String?, val physicalEvidenceRef: String?,
    val identity: RingIdentity?, val generation: Long?, val receivedMonotonicMs: Long?,
)
data class RingDeviceEventState(
    val availability: List<RingEventAvailability> = emptyList(),
    val tasbihCount: Int? = null, val candidateCount: Int? = null, val localDisplayCount: Int? = null,
    val displayBasis: String? = null, val tapCount: Long = 0, val candidateTapCount: Long = 0,
    val tasbihLastEvent: RingDeviceEvent? = null, val gameLastEvent: RingDeviceEvent? = null,
    val lastEvent: RingDeviceEvent? = null,
)

data class RingDeviceState(
    val userId: String? = null, val sourceId: String? = null, val bindingGeneration: Long = 0,
    val identity: RingIdentity? = null, val capabilities: List<RingCapability> = emptyList(),
    val settings: Map<RingDeviceSettingKind, RingDeviceSetting> = emptyMap(),
    val physicalFence: RingPhysicalFence = RingPhysicalFence(),
    val pendingOperations: List<RingPendingOperation> = emptyList(),
    val wireStates: Map<RingDeviceSettingKind, RingDeviceWireState> = emptyMap(),
    val operationId: String? = null, val result: RingOperationResult? = null,
    val events: RingDeviceEventState = RingDeviceEventState(), val error: RingNativeError? = null,
) {
    val writeBlocked: Boolean get() = operationId != null || result?.status == "unknown" || physicalFence.blocked || physicalFence.refreshRequired
    fun capability(kind: String): RingCapability? = capabilities.singleOrNull { it.kind == kind }

    companion object {
        internal fun fromJson(value: JsonObject): RingDeviceState = RingDeviceState(
            userId = value.nativeString("user_id"), sourceId = value.nativeString("source_id"),
            bindingGeneration = value.nativeLong("binding_generation") ?: 0,
            identity = value.nativeObject("identity")?.nativeIdentity(),
            capabilities = value.nativeObjects("capabilities").mapNotNull { it.nativeCapability() },
            settings = value.nativeObject("settings")?.mapNotNull { (key, item) ->
                val setting = (item as? JsonObject)?.let { RingDeviceSetting.fromJson(it) }
                setting?.takeIf { it.kind.wireValue == key }?.let { it.kind to it }
            }?.toMap() ?: emptyMap(),
            physicalFence = value.nativeObject("physical_fence")?.let {
                RingPhysicalFence(it.nativeBool("blocked") ?: false, it.nativeString("reason"),
                    it.nativeBool("refresh_required") ?: false, it.nativeBool("can_reconcile") ?: false)
            } ?: RingPhysicalFence(),
            pendingOperations = value.nativeObjects("pending_operations").mapNotNull {
                RingPendingOperation(it.nativeString("operation_id") ?: return@mapNotNull null,
                    it.nativeString("domain") ?: return@mapNotNull null, it.nativeBool("can_reconcile") ?: false,
                    it.nativeString("reason") ?: "")
            },
            wireStates = value.nativeObject("wire_states")?.mapNotNull { (key, item) ->
                val kind = RingDeviceSettingKind.fromWire(key) ?: return@mapNotNull null
                val wire = item as? JsonObject ?: return@mapNotNull null
                if (wire.nativeString("kind") != key) return@mapNotNull null
                kind to RingDeviceWireState(key, wire.nativeStrings("raw_payload_hex"), wire.nativeString("semantic_status") ?: "")
            }?.toMap() ?: emptyMap(),
            operationId = value.nativeString("operation_id"), result = value.nativeObject("result")?.nativeResult(),
            events = value.nativeObject("events")?.nativeEvents() ?: RingDeviceEventState(),
            error = value.nativeObject("error")?.nativeError(),
        )
    }
}

enum class RingManualMetric(val wireValue: String) {
    HEART_RATE("heart_rate"), SPO2("spo2"), TEMPERATURE("temperature"), HRV("hrv"), STRESS("stress");

    val scheduleKind: String get() = "${wireValue}_schedule"
    val manualKind: String get() = "manual_$wireValue"
    companion object { fun fromWire(value: String?): RingManualMetric? = entries.firstOrNull { it.wireValue == value } }
}

data class RingHeartRateAlerts(val lowBpm: Int, val highBpm: Int, val mainSwitchRaw: Int, val qualification: String = "source_only")
data class RingMeasurementSetting(
    val enabled: Boolean, val intervalMinutes: Int? = null, val confirmedAt: String? = null,
    val readback: Boolean? = null, val heartRateAlerts: RingHeartRateAlerts? = null,
)
data class RingActiveMeasurement(
    val operationId: String, val metric: RingManualMetric, val phase: String,
    val startedAt: String, val deadlineAt: String, val stopReason: String? = null,
)
data class RingMeasuredResult(
    val metric: RingManualMetric, val value: String?, val unit: String, val measuredAt: String,
    val quality: List<String>, val externalId: String, val timestampBasis: String,
)
data class RingMeasurementState(
    val userId: String? = null, val sourceId: String? = null, val bindingGeneration: Long = 0,
    val identity: RingIdentity = RingIdentity(), val capabilities: List<RingCapability> = emptyList(),
    val operationId: String? = null, val result: RingOperationResult? = null,
    val settings: Map<RingManualMetric, RingMeasurementSetting> = emptyMap(),
    val activeMeasurement: RingActiveMeasurement? = null, val measuredResult: RingMeasuredResult? = null,
    val error: RingNativeError? = null,
) {
    val writeBlocked: Boolean get() = operationId != null || result?.status == "unknown"
    fun capability(kind: String): RingCapability? = capabilities.singleOrNull { it.kind == kind }

    companion object {
        internal fun fromJson(value: JsonObject): RingMeasurementState {
            val operationId = value.nativeString("operation_id")
            val active = value.nativeObject("active_measurement")?.let {
                val metric = RingManualMetric.fromWire(it.nativeString("metric")) ?: return@let null
                val id = it.nativeString("operation_id") ?: return@let null
                if (id != operationId) return@let null
                RingActiveMeasurement(id, metric, it.nativeString("phase") ?: "starting",
                    it.nativeString("started_at") ?: "", it.nativeString("deadline_at") ?: "", it.nativeString("stop_reason"))
            }
            return RingMeasurementState(
                userId = value.nativeString("user_id"), sourceId = value.nativeString("source_id"),
                bindingGeneration = value.nativeLong("binding_generation") ?: 0,
                identity = value.nativeObject("identity")?.nativeIdentity() ?: RingIdentity(),
                capabilities = value.nativeObjects("capabilities").mapNotNull { it.nativeCapability() },
                operationId = operationId, result = value.nativeObject("result")?.nativeResult(),
                settings = value.nativeObject("settings")?.mapNotNull { (key, item) ->
                    val metric = RingManualMetric.fromWire(key) ?: return@mapNotNull null
                    val setting = item as? JsonObject ?: return@mapNotNull null
                    val enabled = setting.nativeBool("enabled") ?: return@mapNotNull null
                    val alerts = setting.nativeObject("heart_rate_alerts")?.let { alert ->
                        RingHeartRateAlerts(alert.nativeInt("low_bpm") ?: return@let null,
                            alert.nativeInt("high_bpm") ?: return@let null, alert.nativeInt("main_switch_raw") ?: return@let null,
                            alert.nativeString("qualification") ?: "source_only")
                    }
                    metric to RingMeasurementSetting(enabled, setting.nativeInt("interval_minutes"),
                        setting.nativeString("confirmed_at"), setting.nativeBool("readback"), alerts)
                }?.toMap() ?: emptyMap(),
                activeMeasurement = active,
                measuredResult = value.nativeObject("measured_result")?.let {
                    val metric = RingManualMetric.fromWire(it.nativeString("metric")) ?: return@let null
                    RingMeasuredResult(metric, it.nativeString("value"), it.nativeString("unit") ?: "",
                        it.nativeString("measured_at") ?: "", it.nativeStrings("quality"),
                        it.nativeString("external_id") ?: "", it.nativeString("timestamp_basis") ?: "phone_received")
                },
                error = value.nativeObject("error")?.nativeError(),
            )
        }
    }
}

sealed interface RingDeviceAction {
    data object Refresh : RingDeviceAction
    data object RefreshIdentity : RingDeviceAction
    data class Read(val kind: RingDeviceSettingKind) : RingDeviceAction
    data class Update(val setting: RingDeviceSetting) : RingDeviceAction
    data class Perform(val operation: RingDeviceOperation) : RingDeviceAction
    data class Reconcile(val operationId: String) : RingDeviceAction

}

internal fun RingDeviceAction.toCommand(): Pair<String, JsonObject> = when (this) {
    RingDeviceAction.Refresh -> "ring.device.state" to buildJsonObject { }
    RingDeviceAction.RefreshIdentity -> "ring.device.refresh" to buildJsonObject { }
    is RingDeviceAction.Read -> "ring.device.read" to buildJsonObject { put("kind", kind.wireValue) }
    is RingDeviceAction.Update -> "ring.device.update" to buildJsonObject { put("setting", setting.json()) }
    is RingDeviceAction.Perform -> "ring.device.action" to buildJsonObject { put("action", operation.wireValue) }
    is RingDeviceAction.Reconcile -> "ring.device.reconcile" to buildJsonObject { put("operation_id", operationId) }
}

data class RingAlertThresholds(val lowBpm: Int, val highBpm: Int)
data class RingMeasurementConfiguration(
    val hrIntervalMinutes: Int? = null, val hrEnabled: Boolean? = null,
    val temperatureIntervalMinutes: Int? = null, val temperatureEnabled: Boolean? = null,
    val spo2Night: Boolean? = null, val hrvEnabled: Boolean? = null, val stressEnabled: Boolean? = null,
    val heartRateAlerts: RingAlertThresholds? = null,
) {
    internal fun json(): JsonObject = buildJsonObject {
        hrIntervalMinutes?.let { put("hr_interval_minutes", it) }; hrEnabled?.let { put("hr_enabled", it) }
        temperatureIntervalMinutes?.let { put("temperature_interval_minutes", it) }
        temperatureEnabled?.let { put("temperature_enabled", it) }; spo2Night?.let { put("spo2_night", it) }
        hrvEnabled?.let { put("hrv_enabled", it) }; stressEnabled?.let { put("stress_enabled", it) }
        heartRateAlerts?.let { put("heart_rate_alerts", buildJsonObject { put("low_bpm", it.lowBpm); put("high_bpm", it.highBpm) }) }
    }
}

sealed interface RingMeasurementAction {
    data object Refresh : RingMeasurementAction
    data class Read(val kinds: List<RingManualMetric>) : RingMeasurementAction
    data class Configure(val configuration: RingMeasurementConfiguration) : RingMeasurementAction
    data class Measure(val metric: RingManualMetric) : RingMeasurementAction
    data class Cancel(val operationId: String) : RingMeasurementAction
    data class Reconcile(val operationId: String) : RingMeasurementAction

}

internal fun RingMeasurementAction.toCommand(): Pair<String, JsonObject> = when (this) {
    RingMeasurementAction.Refresh -> "ring.measurement.state" to buildJsonObject { }
    is RingMeasurementAction.Read -> "ring.measurement.read" to buildJsonObject { put("kinds", JsonArray(kinds.map { JsonPrimitive(it.wireValue) })) }
    is RingMeasurementAction.Configure -> "ring.measurement.configure" to configuration.json()
    is RingMeasurementAction.Measure -> "ring.measurement.measure" to buildJsonObject { put("metric", metric.wireValue) }
    is RingMeasurementAction.Cancel -> "ring.measurement.cancel" to buildJsonObject { put("operation_id", operationId) }
    is RingMeasurementAction.Reconcile -> "ring.device.reconcile" to buildJsonObject { put("operation_id", operationId) }
}

private fun JsonObject.nativeObject(key: String): JsonObject? = this[key] as? JsonObject
private fun JsonObject.nativeString(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
private fun JsonObject.nativeBool(key: String): Boolean? = (this[key] as? JsonPrimitive)?.booleanOrNull
private fun JsonObject.nativeInt(key: String): Int? = (this[key] as? JsonPrimitive)?.intOrNull
private fun JsonObject.nativeLong(key: String): Long? = (this[key] as? JsonPrimitive)?.longOrNull
private fun JsonObject.nativeObjects(key: String): List<JsonObject> = (this[key] as? JsonArray)?.mapNotNull { it as? JsonObject } ?: emptyList()
private fun JsonObject.nativeStrings(key: String): List<String> = (this[key] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull } ?: emptyList()
private fun JsonObject?.nativeStringMap(): Map<String, String> = this?.mapNotNull { (key, item) ->
    (item as? JsonPrimitive)?.contentOrNull?.let { key to it }
}?.toMap() ?: emptyMap()
private fun JsonObject.nativeDevice(): RingNativeDevice? = nativeString("address")?.let {
    RingNativeDevice(it, nativeString("name") ?: "", nativeInt("rssi"))
}
private fun JsonObject.nativeError(): RingNativeError = RingNativeError(nativeString("code") ?: "request_failed", nativeString("message") ?: "")
private fun JsonObject.nativeIdentity(): RingIdentity = RingIdentity(nativeString("model"), nativeString("hardware"), nativeString("firmware"))
private fun JsonObject.nativeCapability(): RingCapability? {
    val kind = nativeString("kind") ?: return null
    val status = nativeString("status") ?: return null
    return RingCapability(kind, status, nativeString("reason") ?: "", nativeBool("readable") ?: (status == "verified"),
        nativeBool("writable") ?: (status == "verified"),
        (this["supported_intervals_minutes"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.intOrNull } ?: emptyList(),
        nativeBool("autonomous_night"))
}
private fun JsonObject.nativeResult(): RingOperationResult {
    val command = nativeObject("command")
    return RingOperationResult(nativeString("status") ?: "unknown", nativeString("reason") ?: "", nativeString("operation_id"),
        command?.nativeString("type"), RingManualMetric.fromWire(command?.nativeString("metric")),
        RingDeviceSettingKind.fromWire(command?.nativeObject("setting")?.nativeString("kind") ?: command?.nativeString("kind")),
        RingDeviceOperation.entries.firstOrNull { it.wireValue == command?.nativeString("action") })
}
private fun JsonObject.nativeEvent(): RingDeviceEvent = RingDeviceEvent(
    nativeString("qualification") ?: "protocol_unverified", nativeString("received_at"), nativeString("raw_payload_hex"),
    nativeObject("candidate")?.nativeString("kind"), nativeObject("candidate")?.nativeInt("count"),
    nativeObject("resolved")?.nativeString("kind"), nativeObject("resolved")?.nativeInt("count"),
    nativeString("physical_source_id"), nativeString("source_evidence_ref"), nativeString("physical_evidence_ref"),
    nativeObject("identity")?.nativeIdentity(), nativeLong("generation"), nativeLong("received_monotonic_ms"),
)
private fun JsonObject.nativeEvents(): RingDeviceEventState {
    val tasbih = nativeObject("tasbih")
    val game = nativeObject("game")
    return RingDeviceEventState(
        availability = nativeObjects("availability").mapNotNull {
            RingEventAvailability(it.nativeString("kind") ?: return@mapNotNull null, it.nativeString("status") ?: "protocol_unverified",
                it.nativeString("reason") ?: "", it.nativeString("evidence_ref"))
        },
        tasbihCount = tasbih?.nativeInt("count"), candidateCount = tasbih?.nativeInt("candidate_count"),
        localDisplayCount = tasbih?.nativeInt("local_display_count"), displayBasis = tasbih?.nativeString("display_basis"),
        tapCount = game?.nativeLong("tap_count") ?: 0, candidateTapCount = game?.nativeLong("candidate_tap_count") ?: 0,
        tasbihLastEvent = tasbih?.nativeObject("last_event")?.nativeEvent(),
        gameLastEvent = game?.nativeObject("last_event")?.nativeEvent(), lastEvent = nativeObject("last_event")?.nativeEvent(),
    )
}
