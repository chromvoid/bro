package assistant.mobile.android

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import assistant.mobile.core.GeneratedStrings
import assistant.mobile.platform.ring.RingAlertThresholds
import assistant.mobile.platform.ring.RingController
import assistant.mobile.platform.ring.RingManualMetric
import assistant.mobile.platform.ring.RingMeasurementAction
import assistant.mobile.platform.ring.RingMeasurementConfiguration
import assistant.mobile.platform.ring.RingMeasurementState

@Composable
internal fun RingMeasurementControls(controller: RingController, timezone: String = "UTC", expectedUserId: String? = null) {
    val state by controller.measurementStates.collectAsStateWithLifecycle()
    val ring by controller.states.collectAsStateWithLifecycle()
    if (expectedUserId != null && state.userId != expectedUserId) return
    val actions = remember(state.userId, state.sourceId) { RingUiActions() }
    RingMeasurementControlsContent(state, timezone,
        disabled = ring.operationId != null || ring.error?.code == "store_unavailable",
        onAction = { action -> actions.run { controller.measurement(action) } })
    actions.error?.let { RingError(it.code, it.message, "ring_measurement_action_error") }
}

/** Only capabilities confirmed for the current device identity enable a measurement write. */
@Composable
internal fun RingMeasurementControlsContent(
    state: RingMeasurementState,
    timezone: String = "UTC",
    disabled: Boolean = false,
    onAction: (RingMeasurementAction) -> Unit,
) {
    val busy = state.writeBlocked
    val active = state.activeMeasurement
    fun writable(kind: String) = state.capability(kind)?.canWrite == true
    fun configure(value: RingMeasurementConfiguration) { onAction(RingMeasurementAction.Configure(value)) }
    Column(Modifier.fillMaxWidth().testTag("ring_measurement_controls"), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        HorizontalDivider()
        RingHeading(GeneratedStrings.Ring.Measurement.title)
        RingManualMetric.entries.forEach { metric ->
            val capability = state.capability(metric.scheduleKind)
            Column(Modifier.testTag("ring_schedule_${metric.wireValue}"), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(ringMetric(metric.wireValue))
                RingAvailability(capability?.canRead == true, capability?.status, false)
                RingAvailability(capability?.canWrite == true, capability?.status, true)
                state.settings[metric]?.let { setting ->
                    Text(localized(if (setting.enabled) GeneratedStrings.Ring.Measurement.enabled else GeneratedStrings.Ring.Measurement.disabled))
                    setting.intervalMinutes?.let { Text(localized(GeneratedStrings.Ring.Measurement.savedInterval(it.toString()))) }
                    Text(localized(when (setting.readback) {
                        true -> GeneratedStrings.Ring.Measurement.readbackConfirmed
                        false -> GeneratedStrings.Ring.Measurement.readbackUnconfirmed
                        null -> GeneratedStrings.Ring.Measurement.readbackMissing
                    }))
                    Text(localized(GeneratedStrings.Ring.Measurement.lastRead) + ": " +
                        if (setting.confirmedAt != null) ringMoment(setting.confirmedAt, timezone)
                        else localized(GeneratedStrings.Ring.Measurement.readTimeMissing))
                }
                RingButton(GeneratedStrings.Ring.Measurement.read, "ring_measurement_read_${metric.wireValue}",
                    !disabled && active == null && capability?.canRead == true) {
                    onAction(RingMeasurementAction.Read(listOf(metric)))
                }
            }
        }
        Text(localized(GeneratedStrings.Ring.Measurement.keepSettings))
        RingDetails(localized(GeneratedStrings.Ring.Measurement.capabilityDetails), "ring_measurement_capabilities") {
            state.capabilities.forEach { Text("${it.kind} · ${it.status} · ${it.reason}") }
        }
        active?.let {
            RingStatus(when (it.phase) {
                "starting" -> GeneratedStrings.Ring.Measurement.Phase.starting
                "stopping" -> GeneratedStrings.Ring.Measurement.Phase.stopping
                else -> GeneratedStrings.Ring.Measurement.Phase.measuring
            }, "ring_measurement_progress")
            Text(ringMetric(it.metric.wireValue))
            RingButton(GeneratedStrings.Ring.Measurement.cancel, "ring_measurement_cancel", !disabled && it.phase != "stopping") {
                onAction(RingMeasurementAction.Cancel(it.operationId))
            }
        }
        state.settings[RingManualMetric.HEART_RATE]?.heartRateAlerts?.let { alerts ->
            key(state.userId, state.sourceId, alerts) {
                var low by remember(state.userId, state.sourceId, alerts) { mutableStateOf(alerts.lowBpm) }
                var high by remember(state.userId, state.sourceId, alerts) { mutableStateOf(alerts.highBpm) }
                var lowValid by remember { mutableStateOf(alerts.lowBpm in 0..255) }
                var highValid by remember { mutableStateOf(alerts.highBpm in 0..255) }
                RingHeading(GeneratedStrings.Ring.Measurement.alertsTitle)
                Text(localized(GeneratedStrings.Ring.Measurement.alertsSourceOnly(alerts.mainSwitchRaw.toString())))
                RingNumberField("alert_low", low, !disabled && !busy, GeneratedStrings.Ring.Measurement.alertLow,
                    onInvalid = { lowValid = false }) { low = it; lowValid = it in 0..255 }
                RingNumberField("alert_high", high, !disabled && !busy, GeneratedStrings.Ring.Measurement.alertHigh,
                    onInvalid = { highValid = false }) { high = it; highValid = it in 0..255 }
                RingButton(GeneratedStrings.Ring.Measurement.alertsApply, "ring_measurement_alerts_configure",
                    !disabled && !busy && lowValid && highValid && writable("heart_rate_alerts")) {
                    configure(RingMeasurementConfiguration(heartRateAlerts = RingAlertThresholds(low, high)))
                }
            }
        }
        state.settings[RingManualMetric.HEART_RATE]?.let { setting ->
            RingButton(if (setting.enabled) GeneratedStrings.Ring.Measurement.heartRateDisable else GeneratedStrings.Ring.Measurement.heartRateEnable,
                "ring_measurement_heart_rate_toggle", !disabled && !busy && writable("heart_rate_schedule")) {
                configure(RingMeasurementConfiguration(hrEnabled = !setting.enabled))
            }
        }
        state.settings[RingManualMetric.TEMPERATURE]?.let { setting ->
            RingButton(if (setting.enabled) GeneratedStrings.Ring.Measurement.temperatureDisable else GeneratedStrings.Ring.Measurement.temperatureEnable,
                "ring_measurement_temperature_toggle", !disabled && !busy && writable("temperature_schedule")) {
                configure(RingMeasurementConfiguration(temperatureEnabled = !setting.enabled))
            }
        }
        RingButton(GeneratedStrings.Ring.Measurement.heartRateFiveMinutes, "ring_measurement_configure",
            !disabled && !busy && writable("heart_rate_schedule") && state.capability("heart_rate_schedule")?.supportedIntervalsMinutes?.contains(5) == true) {
            configure(RingMeasurementConfiguration(hrIntervalMinutes = 5))
        }
        val temperature = state.capability("temperature_schedule")
        val temperatureInterval = listOf(1, 5).firstOrNull { temperature?.supportedIntervalsMinutes?.contains(it) == true }
        RingButton(GeneratedStrings.Ring.Measurement.autonomousTemperature, "ring_measurement_temperature_configure",
            !disabled && !busy && writable("temperature_schedule") && temperature?.autonomousNight == true && temperatureInterval != null) {
            configure(RingMeasurementConfiguration(temperatureIntervalMinutes = temperatureInterval))
        }
        state.settings[RingManualMetric.SPO2]?.let { setting ->
            RingButton(if (setting.enabled) GeneratedStrings.Ring.Measurement.spo2Disable else GeneratedStrings.Ring.Measurement.spo2Enable,
                "ring_measurement_spo2_configure", !disabled && !busy && writable("spo2_schedule") && state.capability("spo2_schedule")?.autonomousNight == true) {
                configure(RingMeasurementConfiguration(spo2Night = !setting.enabled))
            }
        }
        state.settings[RingManualMetric.HRV]?.let { setting ->
            RingButton(if (setting.enabled) GeneratedStrings.Ring.Measurement.hrvDisable else GeneratedStrings.Ring.Measurement.hrvEnable,
                "ring_measurement_hrv_configure", !disabled && !busy && writable("hrv_schedule")) {
                configure(RingMeasurementConfiguration(hrvEnabled = !setting.enabled))
            }
        }
        state.settings[RingManualMetric.STRESS]?.let { setting ->
            RingButton(if (setting.enabled) GeneratedStrings.Ring.Measurement.stressDisable else GeneratedStrings.Ring.Measurement.stressEnable,
                "ring_measurement_stress_configure", !disabled && !busy && writable("stress_schedule")) {
                configure(RingMeasurementConfiguration(stressEnabled = !setting.enabled))
            }
        }
        RingManualMetric.entries.forEach { metric ->
            RingButton(GeneratedStrings.Ring.Measurement.measure(ringMetric(metric.wireValue)), "ring_measure_${metric.wireValue}",
                !disabled && !busy && writable(metric.manualKind)) { onAction(RingMeasurementAction.Measure(metric)) }
        }
        RingButton(GeneratedStrings.Ring.Measurement.refresh, "ring_measurement_refresh", !disabled) { onAction(RingMeasurementAction.Refresh) }
        val result = state.result
        val measured = state.measuredResult?.takeIf { measured ->
            val numeric = measured.value?.toDoubleOrNull()
            active == null && numeric != null && numeric.isFinite() && (measured.metric != RingManualMetric.HEART_RATE || numeric > 0) &&
                (result?.commandType != "measure_once" || result.status == "verified" && result.reason == "measurement_received" && result.metric == measured.metric)
        }
        measured?.let {
            Column(Modifier.testTag("ring_spot_result"), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(localized(if (result?.commandType == "measure_once") GeneratedStrings.Ring.Measurement.currentResult
                    else GeneratedStrings.Ring.Measurement.lastSavedResult))
                Text("${ringMetric(it.metric.wireValue)} · ${it.value} ${it.unit}")
                Text(localized(GeneratedStrings.Ring.Measurement.receivedByPhone) + ": " + ringMoment(it.measuredAt, timezone))
                it.quality.forEach { quality -> PersonalQuality(listOf(quality)) }
            }
        }
        if (result != null && active == null) {
            val message = if (result.commandType == "measure_once") when {
                result.status == "unknown" -> GeneratedStrings.Ring.Measurement.ManualResult.stopUnconfirmed
                result.status == "rejected" -> GeneratedStrings.Ring.Measurement.ManualResult.rejected
                result.reason == "measurement_cancelled" -> GeneratedStrings.Ring.Measurement.ManualResult.cancelled
                result.reason == "measurement_device_error" -> GeneratedStrings.Ring.Measurement.ManualResult.deviceError
                result.reason == "measurement_timeout" -> GeneratedStrings.Ring.Measurement.ManualResult.timeout
                measured != null -> GeneratedStrings.Ring.Measurement.ManualResult.completed
                else -> GeneratedStrings.Ring.Measurement.ManualResult.noValue
            } else when (result.status) {
                "verified" -> GeneratedStrings.Ring.Measurement.Result.verified
                "rejected" -> GeneratedStrings.Ring.Measurement.Result.rejected
                else -> GeneratedStrings.Ring.Measurement.Result.unknown
            }
            RingStatus(message, "ring_measurement_result")
            RingDetails(localized(GeneratedStrings.Ring.Measurement.resultDetails), "ring_measurement_result_details") {
                Text(result.reason)
                result.operationId?.let { Text(it) }
            }
        }
        state.error?.let { RingError(it.code, it.message, "ring_measurement_error") }
    }
}
