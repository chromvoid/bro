package assistant.mobile.core.ring

import assistant.mobile.core.AccountNamespace
import assistant.mobile.core.LocalizedString
import assistant.mobile.core.NativeJson
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

@Serializable
data class RingCollector(
    @SerialName("collector_id") val collectorId: String,
    @SerialName("source_id") val sourceId: String,
    val name: String,
    val profile: String,
    val generation: Long,
    val enabled: Boolean,
    @SerialName("last_received_at") val lastReceivedAt: String?,
    val error: String?,
    val status: String,
    @SerialName("device_state") val deviceState: StoredRingDeviceState? = null,
    val metrics: List<RingCollectorMetric> = emptyList(),
)

@Serializable
data class RingCollectorMetric(
    val metric: String,
    val status: String,
    @SerialName("last_measured_at") val lastMeasuredAt: String?,
    @SerialName("last_received_at") val lastReceivedAt: String?,
    val coverage: RingMetricCoverage,
    @SerialName("import_status") val importStatus: String,
    val reason: String?,
    @SerialName("last_capture_at") val lastCaptureAt: String? = null,
    @SerialName("capture_status") val captureStatus: String? = null,
    @SerialName("capture_scope") val captureScope: String? = null,
    @SerialName("capture_date") val captureDate: String? = null,
    @SerialName("capture_context") val captureContext: String? = null,
    @SerialName("capture_error_code") val captureErrorCode: String? = null,
    @SerialName("raw_report_count") val rawReportCount: Int = 0,
    @SerialName("original_kind") val originalKind: String? = null,
)

@Serializable
data class RingMetricCoverage(
    val status: String,
    val shape: String,
    @SerialName("start_at") val startAt: String?,
    @SerialName("end_at") val endAt: String?,
    val quality: List<String>,
)

@Serializable
data class StoredRingDeviceState(
    @SerialName("observed_at") val observedAt: String,
    @SerialName("received_at") val receivedAt: String,
    val revision: Long,
    val stale: Boolean,
    @SerialName("battery_percent") val batteryPercent: Int? = null,
    val charging: Boolean? = null,
    @SerialName("battery_observed_at") val batteryObservedAt: String? = null,
    @SerialName("charging_observed_at") val chargingObservedAt: String? = null,
    @SerialName("battery_stale") val batteryStale: Boolean? = null,
    @SerialName("charging_stale") val chargingStale: Boolean? = null,
    val firmware: String? = null,
    val hardware: String? = null,
    val capabilities: Map<String, StoredRingCapability> = emptyMap(),
    @SerialName("declared_capabilities") val declaredCapabilities: Map<String, StoredRingDeclaration> = emptyMap(),
    @SerialName("last_confirmed_settings") val lastConfirmedSettings: Map<String, StoredRingSetting> = emptyMap(),
)

@Serializable
data class StoredRingCapability(
    val status: String,
    @SerialName("evidence_ref") val evidenceRef: String? = null,
    val reason: String? = null,
    val readable: Boolean? = null,
    val writable: Boolean? = null,
)

@Serializable
data class StoredRingSetting(
    val value: JsonElement,
    @SerialName("confirmed_at") val confirmedAt: String,
    val readback: Boolean,
    val action: String? = null,
    @SerialName("evidence_ref") val evidenceRef: String? = null,
)

@Serializable
data class StoredRingDeclaration(
    @SerialName("captured_at") val capturedAt: String,
    val opcode: Int,
    @SerialName("payload_hex") val payloadHex: String,
    val flags: Map<String, Boolean>,
    @SerialName("source_values") val sourceValues: Map<String, Int>,
    val identity: StoredRingIdentity,
    @SerialName("decoder_version") val decoderVersion: String,
    @SerialName("evidence_ref") val evidenceRef: String,
    @SerialName("validation_status") val validationStatus: String,
)

@Serializable
data class StoredRingIdentity(val hardware: String, val firmware: String)

data class RingCollectorsState(
    val namespace: AccountNamespace,
    val items: List<RingCollector> = emptyList(),
    val loading: Boolean = false,
    val busyCollector: String? = null,
    val error: LocalizedString? = null,
    val serverError: String? = null,
)

@Serializable
private data class RingCollectorsReply(val rings: List<RingCollector>)

/** The read model carries source evidence; a completed capture never implies complete coverage. */
fun decodeRingCollectors(bytes: ByteArray): List<RingCollector> =
    NativeJson.decodeFromString<RingCollectorsReply>(bytes.decodeToString()).rings.also { rings ->
        require(rings.map { it.collectorId }.distinct().size == rings.size)
        rings.forEach { ring ->
            require(ring.collectorId.isNotBlank() && ring.sourceId.isNotBlank() && ring.generation >= 1)
            require(ring.status in setOf("connected", "unavailable", "disabled"))
            ring.metrics.forEach { metric ->
                require(metric.status in setOf("available", "unverified", "missing", "unsupported"))
                require(metric.coverage.status in setOf("reported_window", "partial", "unknown"))
                require(metric.rawReportCount >= 0)
                require(metric.captureStatus == null || metric.captureStatus in setOf("complete", "empty", "failed"))
            }
            ring.deviceState?.let { state ->
                require(state.revision >= 1 && (state.batteryPercent == null || state.batteryPercent in 0..100))
                require(state.lastConfirmedSettings.values.all { it.readback })
                require(state.capabilities.values.all { it.status in setOf("supported", "unsupported", "unknown") })
                require(state.declaredCapabilities.keys.all { it in setOf("core_01", "core_3c") })
                require(state.declaredCapabilities.values.all { it.validationStatus == "declared_only" })
            }
        }
    }
