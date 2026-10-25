package assistant.mobile.android

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import assistant.mobile.core.GeneratedStrings
import assistant.mobile.core.LocalizedString
import assistant.mobile.platform.ring.RingController
import assistant.mobile.platform.ring.RingDeviceAction
import assistant.mobile.platform.ring.RingDeviceOperation
import assistant.mobile.platform.ring.RingDeviceSetting
import assistant.mobile.platform.ring.RingDeviceSettingKind
import assistant.mobile.platform.ring.RingDeviceState

@Composable
internal fun RingDeviceSettings(controller: RingController, expectedUserId: String? = null) {
    val state by controller.deviceStates.collectAsStateWithLifecycle()
    val ring by controller.states.collectAsStateWithLifecycle()
    if (expectedUserId != null && state.userId != expectedUserId) return
    val actions = remember(state.userId, state.sourceId) { RingUiActions() }
    RingDeviceSettingsContent(state,
        disabled = ring.operationId != null || ring.error?.code == "store_unavailable",
        onAction = { action -> actions.run { controller.device(action) } })
    actions.error?.let { RingError(it.code, it.message, "ring_device_action_error") }
}

/** Drafts start only from a complete, semantically confirmed read; no default setting is sent. */
@Composable
internal fun RingDeviceSettingsContent(state: RingDeviceState, disabled: Boolean = false, onAction: (RingDeviceAction) -> Unit) {
    var selected by remember(state.userId, state.sourceId) { mutableStateOf(RingDeviceSettingKind.ALARMS) }
    val current = state.settings[selected]
    var draft by remember(state.userId, state.sourceId, selected, current) { mutableStateOf(current) }
    var dirty by remember(state.userId, state.sourceId, selected, current) { mutableStateOf(false) }
    var invalidFields by remember(state.userId, state.sourceId, selected, current) { mutableStateOf(emptySet<String>()) }
    var confirmation by remember(state.userId, state.sourceId) { mutableStateOf<RingDeviceOperation?>(null) }
    val busy = state.operationId != null
    val blocked = disabled || busy || state.result?.status == "unknown" || state.physicalFence.blocked || state.physicalFence.refreshRequired
    fun allowed(kind: String, write: Boolean = false): Boolean = state.capabilities.find { it.kind == kind }?.let {
        if (write) it.status == "verified" && it.writable else it.readable
    } == true
    Column(Modifier.fillMaxWidth().testTag("ring_device_settings"), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        HorizontalDivider()
        RingHeading(GeneratedStrings.Ring.Device.title)
        Text(localized(GeneratedStrings.Ring.Device.confirmationHint))
        if (state.physicalFence.blocked || state.physicalFence.refreshRequired) {
            RingStatus(if (state.physicalFence.refreshRequired) GeneratedStrings.Ring.Device.identityRequired
                else GeneratedStrings.Ring.Device.fenced, "ring_physical_fence")
            state.physicalFence.reason?.let { Text(it) }
            if (state.physicalFence.refreshRequired) RingButton(GeneratedStrings.Ring.Device.identityRefresh,
                "ring_identity_refresh", !disabled && !busy) { onAction(RingDeviceAction.RefreshIdentity) }
        }
        if (state.pendingOperations.isNotEmpty()) {
            RingHeading(GeneratedStrings.Ring.Device.pendingCommands)
            state.pendingOperations.forEach { operation ->
                OutlinedCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(localized(if (operation.domain == "control") GeneratedStrings.Ring.Device.pendingControl
                            else GeneratedStrings.Ring.Device.pendingSetting))
                        Text(operation.operationId)
                        Text(operation.reason)
                        RingButton(GeneratedStrings.Ring.Device.reconcile, "ring_reconcile_${operation.operationId}",
                            !disabled && !busy && operation.canReconcile && state.physicalFence.canReconcile) {
                            onAction(RingDeviceAction.Reconcile(operation.operationId))
                        }
                    }
                }
            }
        }
        RingSettingPicker(selected, onSelected = { selected = it })
        val capability = state.capabilities.find { it.kind == selected.wireValue }
        RingAvailability(allowed(selected.wireValue), capability?.status, write = false)
        RingAvailability(allowed(selected.wireValue, true), capability?.status, write = true)
        RingButton(GeneratedStrings.Ring.Device.read, "ring_setting_read", !blocked && allowed(selected.wireValue)) {
            onAction(RingDeviceAction.Read(selected))
        }
        RingButton(GeneratedStrings.Ring.Device.refresh, "ring_device_refresh", !busy) { onAction(RingDeviceAction.Refresh) }
        draft?.let { setting ->
            key(state.userId, state.sourceId, selected, current) {
                RingSettingForm(setting, enabled = !blocked && allowed(selected.wireValue, true),
                    onChange = { draft = it; dirty = true }, onValidityChange = { field, valid ->
                        dirty = true
                        invalidFields = if (valid) invalidFields - field else invalidFields + field
                    })
            }
            if (invalidFields.isNotEmpty()) RingStatus(GeneratedStrings.Ring.Device.invalidSetting, "ring_setting_invalid")
            RingButton(GeneratedStrings.Ring.Device.save, "ring_setting_save", !blocked && dirty && invalidFields.isEmpty() && allowed(selected.wireValue, true)) {
                onAction(RingDeviceAction.Update(setting)); dirty = false
            }
        } ?: RingStatus(if (state.wireStates.containsKey(selected)) GeneratedStrings.Ring.Device.wireOnly
            else GeneratedStrings.Ring.Device.unread, "ring_setting_not_read")
        state.wireStates[selected]?.let { wire ->
            RingDetails(localized(GeneratedStrings.Ring.Device.wireTitle(ringSettingLabel(selected))), "ring_setting_wire") {
                Text(localized(GeneratedStrings.Ring.Device.wireHint))
                Text(wire.semanticStatus)
                wire.rawPayloadHex.forEach { Text(it) }
            }
        }
        RingHeading(GeneratedStrings.Ring.Device.actions)
        RingDeviceOperation.entries.forEach { operation ->
            val kind = if (operation == RingDeviceOperation.TASBIH_START || operation == RingDeviceOperation.TASBIH_STOP) "counter" else operation.wireValue
            val available = allowed(kind, true)
            RingButton(ringOperationLabel(operation), "ring_action_${operation.wireValue}", !blocked && available) {
                if (operation in setOf(RingDeviceOperation.UNBIND, RingDeviceOperation.FACTORY_RESET, RingDeviceOperation.POWER_OFF)) confirmation = operation
                else onAction(RingDeviceAction.Perform(operation))
            }
            if (!available) Text(localized(if (state.capabilities.find { it.kind == kind }?.status in setOf("verified", "unsupported"))
                GeneratedStrings.Ring.Device.actionUnavailable else GeneratedStrings.Ring.Device.actionUnconfirmed))
        }
        RingDeviceEvents(state.events)
        state.result?.let { result ->
            RingStatus(when (result.status) {
                "verified" -> GeneratedStrings.Ring.Device.verified
                "rejected" -> GeneratedStrings.Ring.Device.rejected
                else -> GeneratedStrings.Ring.Device.unknown
            }, "ring_device_result")
            Text(result.reason)
        }
        state.error?.let { RingError(it.code, it.message, "ring_device_error") }
    }
    confirmation?.let { action ->
        AlertDialog(onDismissRequest = { confirmation = null }, title = { Text(localized(ringOperationLabel(action))) },
            text = { Text(localized(GeneratedStrings.Ring.Device.confirmActionHint(localized(ringOperationLabel(action))))) },
            confirmButton = { Button(onClick = { confirmation = null; onAction(RingDeviceAction.Perform(action)) }, enabled = !blocked,
                modifier = Modifier.heightIn(min = 48.dp).testTag("ring_action_confirm")) {
                Text(localized(GeneratedStrings.Ring.Device.confirmAction(localized(ringOperationLabel(action)))))
            } }, dismissButton = { TextButton(onClick = { confirmation = null }) { Text(localized(GeneratedStrings.Common.cancel)) } })
    }
}

@Composable
private fun RingSettingForm(value: RingDeviceSetting, enabled: Boolean, onChange: (RingDeviceSetting) -> Unit, onValidityChange: (String, Boolean) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        value.enabled?.let { checked -> RingCheck(GeneratedStrings.Ring.Device.enabled, checked, enabled) { onChange(value.copy(enabled = it)) } }
        value.numbers.filterKeys { it != "weekdays" && it != "language" }.forEach { (key, number) ->
            RingNumberField(key, number, enabled, label = ringNumberLabel(key), onValidityChange = { onValidityChange(key, it) }) {
                onChange(value.copy(numbers = value.numbers + (key to it)))
            }
        }
        if (value.kind == RingDeviceSettingKind.PROFILE) {
            RingChoice("sex", value.choices["sex"], listOf("male", "female"), enabled) {
                onChange(value.copy(choices = value.choices + ("sex" to it)))
            }
            RingChoice("units", value.choices["units"], listOf("metric", "imperial"), enabled) {
                onChange(value.copy(choices = value.choices + ("units" to it)))
            }
            value.is24Hour?.let { checked -> RingCheck(GeneratedStrings.Ring.Device.clock24, checked, enabled) { onChange(value.copy(is24Hour = it)) } }
        }
        value.slots.forEach { slot ->
            OutlinedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("${localized(if (value.kind == RingDeviceSettingKind.ALARMS) GeneratedStrings.Ring.Device.alarm else GeneratedStrings.Ring.Device.water)} ${slot.slot + 1}")
                    RingCheck(GeneratedStrings.Ring.Device.enabled, slot.enabled, enabled) { checked ->
                        onChange(value.copy(slots = value.slots.map { if (it.slot == slot.slot) it.copy(enabled = checked) else it }))
                    }
                    RingNumberField("hour_${slot.slot}", slot.hour, enabled, GeneratedStrings.Ring.Device.NumberLabels.hour,
                        onValidityChange = { onValidityChange("hour_${slot.slot}", it) }) { number ->
                        onChange(value.copy(slots = value.slots.map { if (it.slot == slot.slot) it.copy(hour = number) else it }))
                    }
                    RingNumberField("minute_${slot.slot}", slot.minute, enabled, GeneratedStrings.Ring.Device.NumberLabels.minute,
                        onValidityChange = { onValidityChange("minute_${slot.slot}", it) }) { number ->
                        onChange(value.copy(slots = value.slots.map { if (it.slot == slot.slot) it.copy(minute = number) else it }))
                    }
                    Text(localized(GeneratedStrings.Ring.Device.repeatDaysHint))
                }
            }
        }
        if (value.kind == RingDeviceSettingKind.SEDENTARY) Text(localized(GeneratedStrings.Ring.Device.sedentaryDaysHint))
        if (value.kind == RingDeviceSettingKind.CLOCK) Text(localized(GeneratedStrings.Ring.Device.languageHint))
    }
}

@Composable
internal fun RingCheck(label: LocalizedString, checked: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked = checked, onCheckedChange = onChange, enabled = enabled)
        Text(localized(label), Modifier.weight(1f))
    }
}

@Composable
private fun RingSettingPicker(value: RingDeviceSettingKind, onSelected: (RingDeviceSettingKind) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Column {
        Text(localized(GeneratedStrings.Ring.Device.setting))
        TextButton(onClick = { open = true }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("ring_setting_kind")) {
            Text(ringSettingLabel(value))
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            RingDeviceSettingKind.entries.filter { it != RingDeviceSettingKind.GESTURES }.forEach { kind ->
                DropdownMenuItem(text = { Text(ringSettingLabel(kind)) }, onClick = { open = false; onSelected(kind) },
                    modifier = Modifier.testTag("ring_setting_kind_${kind.wireValue}"))
            }
        }
    }
}

@Composable
private fun RingChoice(key: String, value: String?, choices: List<String>, enabled: Boolean, onChange: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Column {
        Text(localized(if (key == "sex") GeneratedStrings.Ring.Device.sex else GeneratedStrings.Ring.Device.units))
        TextButton(onClick = { open = true }, enabled = enabled, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
            Text(localized(ringChoiceLabel(value.orEmpty())))
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            choices.forEach { choice -> DropdownMenuItem(text = { Text(localized(ringChoiceLabel(choice))) },
                onClick = { open = false; onChange(choice) }) }
        }
    }
}

@Composable
internal fun RingNumberField(key: String, value: Int, enabled: Boolean, label: LocalizedString, onInvalid: () -> Unit = {},
    onValidityChange: (Boolean) -> Unit = {}, onChange: (Int) -> Unit) {
    var text by remember(key, value) { mutableStateOf(value.toString()) }
    OutlinedTextField(value = text, onValueChange = { next ->
        text = next
        val number = next.toIntOrNull()
        onValidityChange(number != null)
        number?.let(onChange) ?: onInvalid()
    }, label = { Text(localized(label)) }, enabled = enabled, singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = Modifier.fillMaxWidth().testTag("ring_setting_$key"))
}

@Composable
internal fun RingAvailability(available: Boolean, status: String?, write: Boolean) {
    Text(localized(if (write) when {
        available -> GeneratedStrings.Ring.Measurement.Availability.writeAvailable
        status in setOf("verified", "unsupported") -> GeneratedStrings.Ring.Measurement.Availability.writeUnavailable
        else -> GeneratedStrings.Ring.Measurement.Availability.writeUnconfirmed
    } else when {
        available -> GeneratedStrings.Ring.Measurement.Availability.readAvailable
        status in setOf("verified", "unsupported") -> GeneratedStrings.Ring.Measurement.Availability.readUnavailable
        else -> GeneratedStrings.Ring.Measurement.Availability.readUnconfirmed
    }))
}

@Composable
internal fun RingDetails(title: String, tag: String, content: @Composable () -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().testTag(tag), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        TextButton(onClick = { expanded = !expanded }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("${tag}_toggle")) { Text(title) }
        if (expanded) content()
    }
}

@Composable
internal fun ringSettingLabel(kind: RingDeviceSettingKind): String = localized(when (kind) {
    RingDeviceSettingKind.ALARMS -> GeneratedStrings.Ring.Device.Labels.alarms
    RingDeviceSettingKind.DRINK_REMINDERS -> GeneratedStrings.Ring.Device.Labels.drink_reminders
    RingDeviceSettingKind.DND -> GeneratedStrings.Ring.Device.Labels.dnd
    RingDeviceSettingKind.SEDENTARY -> GeneratedStrings.Ring.Device.Labels.sedentary
    RingDeviceSettingKind.POWER_SAVING -> GeneratedStrings.Ring.Device.Labels.power_saving
    RingDeviceSettingKind.GESTURES -> GeneratedStrings.Ring.Device.Labels.gestures
    RingDeviceSettingKind.PROFILE -> GeneratedStrings.Ring.Device.Labels.profile
    RingDeviceSettingKind.CLOCK -> GeneratedStrings.Ring.Device.Labels.clock
})

private fun ringOperationLabel(operation: RingDeviceOperation): LocalizedString = when (operation) {
    RingDeviceOperation.FIND_RING -> GeneratedStrings.Ring.Device.Labels.find_ring
    RingDeviceOperation.BIND -> GeneratedStrings.Ring.Device.Labels.bind
    RingDeviceOperation.UNBIND -> GeneratedStrings.Ring.Device.Labels.unbind
    RingDeviceOperation.FACTORY_RESET -> GeneratedStrings.Ring.Device.Labels.factory_reset
    RingDeviceOperation.POWER_OFF -> GeneratedStrings.Ring.Device.Labels.power_off
    RingDeviceOperation.TASBIH_START -> GeneratedStrings.Ring.Device.Labels.tasbih_start
    RingDeviceOperation.TASBIH_STOP -> GeneratedStrings.Ring.Device.Labels.tasbih_stop
}

private fun ringChoiceLabel(value: String): LocalizedString = when (value) {
    "female" -> GeneratedStrings.Ring.Device.female
    "imperial" -> GeneratedStrings.Ring.Device.imperial
    "metric" -> GeneratedStrings.Ring.Device.metric
    else -> GeneratedStrings.Ring.Device.male
}

private fun ringNumberLabel(key: String): LocalizedString = when (key) {
    "start_minute" -> GeneratedStrings.Ring.Device.NumberLabels.start_minute
    "end_minute" -> GeneratedStrings.Ring.Device.NumberLabels.end_minute
    "interval_minutes" -> GeneratedStrings.Ring.Device.NumberLabels.interval_minutes
    "age" -> GeneratedStrings.Ring.Device.NumberLabels.age
    "height_cm" -> GeneratedStrings.Ring.Device.NumberLabels.height_cm
    "weight_kg" -> GeneratedStrings.Ring.Device.NumberLabels.weight_kg
    "systolic_mm_hg" -> GeneratedStrings.Ring.Device.NumberLabels.systolic_mm_hg
    "diastolic_mm_hg" -> GeneratedStrings.Ring.Device.NumberLabels.diastolic_mm_hg
    "heart_rate_warning_bpm" -> GeneratedStrings.Ring.Device.NumberLabels.heart_rate_warning_bpm
    "year" -> GeneratedStrings.Ring.Device.NumberLabels.year
    "month" -> GeneratedStrings.Ring.Device.NumberLabels.month
    "day" -> GeneratedStrings.Ring.Device.NumberLabels.day
    "hour" -> GeneratedStrings.Ring.Device.NumberLabels.hour
    "minute" -> GeneratedStrings.Ring.Device.NumberLabels.minute
    else -> GeneratedStrings.Ring.Device.NumberLabels.second
}
