package assistant.mobile.android

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import assistant.mobile.core.GeneratedStrings
import assistant.mobile.core.ring.RingCollector
import assistant.mobile.core.ring.StoredRingDeviceState
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** The server's stored observations remain separate from the phone's live device state. */
@Composable
internal fun RingCollectorDetails(
    collector: RingCollector,
    timezone: String,
    active: Boolean,
    busy: Boolean,
    onDisable: () -> Unit,
    onOpenOriginals: ((String) -> Unit)? = null,
) {
    Column(Modifier.fillMaxWidth().testTag("ring_collector_${collector.collectorId}"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(collector.name)
        Text(localized(GeneratedStrings.Ring.received(ringMoment(collector.lastReceivedAt, timezone))))
        collector.error?.let { RingError(it, null, "ring_collector_error_${collector.collectorId}") }
        collector.deviceState?.let { RingStoredState(it, timezone) }
        if (collector.metrics.isNotEmpty()) RingDetails(localized(GeneratedStrings.Ring.Collector.title), "ring_import_metric_status") {
            Text(localized(GeneratedStrings.Ring.Collector.hint))
            collector.metrics.forEach { metric ->
                OutlinedCard(Modifier.fillMaxWidth().testTag("ring_import_metric_${metric.metric}")) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(ringMetric(metric.metric))
                        Text(ringImportStatus(metric.status))
                        metric.reason?.let { Text(ringImportStatus(it)) }
                        if (metric.rawReportCount > 0) {
                            Text(localized(GeneratedStrings.Ring.Collector.rawReports(metric.rawReportCount.toString())))
                            onOpenOriginals?.let { open ->
                                RingButton(GeneratedStrings.Ring.Collector.viewOriginals, "ring_originals_${metric.metric}") {
                                    open(metric.originalKind ?: metric.metric)
                                }
                            }
                        }
                        Text("${localized(GeneratedStrings.Ring.Collector.lastMeasurement)} ${ringMoment(metric.lastMeasuredAt, timezone)}")
                        metric.lastCaptureAt?.let { capture ->
                            Text("${localized(GeneratedStrings.Ring.Collector.lastCapture)} ${ringMoment(capture, timezone)} · " +
                                (metric.captureStatus?.let { ringCaptureStatus(it) } ?: localized(GeneratedStrings.Ring.Collector.captureUnknown)))
                            metric.captureDate?.let { Text("${localized(GeneratedStrings.Ring.Collector.captureDate)} $it") }
                            metric.captureScope?.let { Text("${localized(GeneratedStrings.Ring.Collector.captureScope)} ${ringCaptureScope(it)}") }
                            metric.captureContext?.let { Text("${localized(GeneratedStrings.Ring.Collector.captureContext)} ${ringCaptureContext(it)}") }
                            if (metric.captureStatus == "failed") metric.captureErrorCode?.let { RingError(it, null, "ring_capture_error_${metric.metric}") }
                        }
                        Text("${localized(GeneratedStrings.Ring.Collector.received)} ${ringMoment(metric.lastReceivedAt, timezone)} · ${ringImportStatus(metric.importStatus)}")
                        Text(ringImportStatus(metric.coverage.status))
                        metric.coverage.startAt?.let { Text("${ringMoment(it, timezone)} — ${ringMoment(metric.coverage.endAt, timezone)}") }
                        Text("${localized(GeneratedStrings.Ring.Collector.shape)} ${ringImportStatus(metric.coverage.shape)}")
                        PersonalQuality(metric.coverage.quality)
                    }
                }
            }
        }
        if (collector.enabled && !active) RingButton(GeneratedStrings.Ring.disableImport, "ring_disable_${collector.collectorId}", !busy, onDisable)
    }
}

@Composable
internal fun RingStoredState(state: StoredRingDeviceState, timezone: String) {
    RingDetails(localized(GeneratedStrings.Ring.Stored.title), "stored_ring_state") {
        Text("${localized(GeneratedStrings.Ring.Stored.received)} ${ringStoredMoment(state.receivedAt, timezone)} · " +
            "${localized(GeneratedStrings.Ring.Stored.observed)} ${ringStoredMoment(state.observedAt, timezone)}")
        if (state.stale) Text(localized(GeneratedStrings.Ring.Stored.stale))
        state.batteryPercent?.let {
            RingValue(GeneratedStrings.Ring.Stored.battery, "$it %")
            Text(ringStoredMoment(state.batteryObservedAt, timezone))
            if (state.batteryStale == true) Text(localized(GeneratedStrings.Ring.Stored.batteryStale))
        }
        state.charging?.let {
            RingValue(GeneratedStrings.Ring.Stored.charging, localized(if (it) GeneratedStrings.Ring.Stored.connected else GeneratedStrings.Ring.Stored.disconnected))
            Text(ringStoredMoment(state.chargingObservedAt, timezone))
            if (state.chargingStale == true) Text(localized(GeneratedStrings.Ring.Stored.chargingStale))
        }
        state.firmware?.let { RingValue(GeneratedStrings.Ring.Stored.firmware, it) }
        state.hardware?.let { RingValue(GeneratedStrings.Ring.Stored.hardware, it) }
        if (state.lastConfirmedSettings.isNotEmpty()) {
            RingHeading(GeneratedStrings.Ring.Stored.confirmedSettings)
            state.lastConfirmedSettings.forEach { (name, setting) ->
                RingDetails("${ringStoredName(name)} · ${ringStoredMoment(setting.confirmedAt, timezone)}", "stored_ring_setting_$name") {
                    Text(localized(GeneratedStrings.Ring.Stored.confirmedSettingsHint))
                    RingStoredValue(setting.value)
                    setting.evidenceRef?.let { Text(it) }
                }
            }
        }
        if (state.declaredCapabilities.isNotEmpty()) {
            RingHeading(GeneratedStrings.Ring.Stored.declaredCapabilities)
            Text(localized(GeneratedStrings.Ring.Stored.declaredCapabilitiesHint))
            state.declaredCapabilities.forEach { (channel, declaration) ->
                RingDetails("$channel · ${ringStoredMoment(declaration.capturedAt, timezone)}", "stored_ring_declaration_$channel") {
                    declaration.flags.forEach { (name, declared) ->
                        Text("${ringStoredName(name)}: ${localized(if (declared) GeneratedStrings.Ring.Stored.declared else GeneratedStrings.Ring.Stored.undeclared)}")
                    }
                    Text("${declaration.identity.hardware} · ${declaration.identity.firmware}")
                    Text(localized(GeneratedStrings.Ring.Stored.sourceFieldsHint))
                    declaration.sourceValues.forEach { (key, value) -> Text("${ringStoredName(key)}: $value") }
                    Text("${declaration.decoderVersion} · ${declaration.evidenceRef}")
                    Text(declaration.payloadHex)
                }
            }
        }
        if (state.capabilities.isNotEmpty()) RingDetails(localized(GeneratedStrings.Ring.Stored.availability), "stored_ring_capabilities") {
            Text(localized(GeneratedStrings.Ring.Stored.availabilityHint))
            state.capabilities.forEach { (name, capability) ->
                Text(ringStoredName(name))
                Text(localized(when (capability.status) {
                    "supported" -> GeneratedStrings.Ring.Stored.supported
                    "unsupported" -> GeneratedStrings.Ring.Stored.unsupported
                    else -> GeneratedStrings.Ring.Stored.unconfirmed
                }))
                Text("${localized(GeneratedStrings.Ring.Stored.read)} ${ringStoredAllowance(capability.readable)} · " +
                    "${localized(GeneratedStrings.Ring.Stored.write)} ${ringStoredAllowance(capability.writable)}")
                Text(listOfNotNull(capability.reason, capability.evidenceRef).joinToString(" · "))
            }
        }
    }
}

@Composable
private fun RingStoredValue(value: JsonElement) {
    when (value) {
        is JsonObject -> value.forEach { (name, child) ->
            Column(Modifier.padding(start = 8.dp)) { Text(ringStoredName(name)); RingStoredValue(child) }
        }
        is JsonArray -> value.forEach { RingStoredValue(it) }
        is JsonPrimitive -> Text(value.content)
    }
}

@Composable
private fun ringStoredMoment(value: String?, timezone: String): String =
    if (value == null) localized(GeneratedStrings.Ring.Stored.timeUnknown) else ringMoment(value, timezone)

@Composable
private fun ringStoredAllowance(value: Boolean?): String = localized(when (value) {
    true -> GeneratedStrings.Ring.Stored.allowed
    false -> GeneratedStrings.Ring.Stored.disallowed
    null -> GeneratedStrings.Ring.Stored.allowanceUnknown
})
