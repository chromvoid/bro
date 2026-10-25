package assistant.mobile.trading

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

private fun decimalInput(value: String): String = value.trim().replace(',', '.')
private fun unsignedDecimal(value: String): Boolean = value.matches(Regex("[0-9]+(?:\\.[0-9]+)?"))

@Composable
internal fun TradingMarkEditor(item: TradingFlow, state: TradingState, controller: TradingController, cancel: () -> Unit, saved: () -> Unit) {
    val scope = rememberCoroutineScope()
    var treatment by remember(item.id) { mutableStateOf(item.treatment.takeIf { it in setOf("auto", "own_wallet", "external") } ?: "auto") }
    var cost by remember(item.id) { mutableStateOf(item.unitCostUsd.orEmpty()) }
    val incoming = item.direction == "in"
    val usesCost = treatment == "external" && incoming
    val normalized = decimalInput(cost)
    val valid = !usesCost || normalized.isEmpty() || unsignedDecimal(normalized)
    val pending = state.pendingWrites.any { it.tool == "flows.mark" }
    val busy = state.savingFlow == item.id || pending
    Column(Modifier.fillMaxWidth().testTag("trading_mark_editor"), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(tradingText("mark_title"), style = MaterialTheme.typography.titleMedium)
        listOf("auto", "own_wallet", "external").forEach { value ->
            Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).selectable(selected = treatment == value, enabled = !busy,
                role = Role.RadioButton, onClick = { treatment = value }).testTag("trading_mark_treatment_$value"),
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                RadioButton(selected = treatment == value, onClick = null, enabled = !busy)
                Column {
                    Text(tradingTreatmentLabel(value, incoming))
                    Text(tradingText("mark_hint_${value}_${if (incoming) "in" else "out"}"), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        if (usesCost) OutlinedTextField(value = cost, onValueChange = { cost = it }, enabled = !busy, isError = !valid,
            label = { Text(tradingText("mark_unit_cost", item.coin)) }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            modifier = Modifier.fillMaxWidth().testTag("trading_mark_cost"))
        state.flowMarkError?.takeIf { it.id == item.id }?.let { TradingError(it.error) }
        Button(onClick = { scope.launch { if (controller.markFlow(item.id, treatment, normalized.takeIf { usesCost && it.isNotEmpty() })) saved() } },
            enabled = !busy && valid, modifier = Modifier.heightIn(min = 48.dp).testTag("trading_mark_save")) { Text(tradingText("mark_save")) }
        TextButton(onClick = cancel, enabled = !busy, modifier = Modifier.heightIn(min = 48.dp).testTag("trading_mark_cancel")) { Text(tradingText("cancel")) }
    }
}

@Composable
internal fun TradingCoreEditor(position: PositionItem, state: TradingState, controller: TradingController, cancel: () -> Unit, saved: () -> Unit) {
    val scope = rememberCoroutineScope()
    val core = position.core
    var qty by remember(position.coin) { mutableStateOf(core?.asked ?: position.openQty) }
    var since by remember(position.coin) { mutableStateOf(core?.since?.let { raw ->
        runCatching { Instant.parse(raw).atZone(ZoneId.systemDefault()).toLocalDate().toString() }.getOrNull()
    } ?: LocalDate.now().toString()) }
    val normalized = decimalInput(qty)
    val start = runCatching { LocalDate.parse(since) }.getOrNull()?.takeIf { it.toString() == since && !it.isAfter(LocalDate.now()) }
    val valid = unsignedDecimal(normalized) && TradingFormat.number(normalized)?.signum() == 1 && start != null
    val pending = state.pendingWrites.any { it.tool == "positions.core" }
    val busy = state.acting || pending || !state.canSetCore
    Column(Modifier.fillMaxWidth().testTag("trading_core_editor"), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        OutlinedTextField(value = qty, onValueChange = { qty = it }, enabled = !busy, isError = !valid,
            label = { Text(tradingText("core_qty", position.coin)) }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            modifier = Modifier.fillMaxWidth().testTag("trading_core_qty"))
        OutlinedTextField(value = since, onValueChange = { since = it }, enabled = !busy, isError = start == null,
            label = { Text(tradingText("core_since")) }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
            modifier = Modifier.fillMaxWidth().testTag("trading_core_since"))
        Text(tradingText("core_editor_hint", tradingText(if (state.shownMethod in setOf("lifo", "fifo")) "method_${state.shownMethod}" else "unknown_value")),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        state.saveError?.let { TradingError(it) }
        Button(onClick = { scope.launch {
            if (start != null && controller.setCore(position.coin, normalized, start.atStartOfDay(ZoneId.systemDefault()).toInstant().toString())) saved()
        } }, enabled = !busy && valid, modifier = Modifier.heightIn(min = 48.dp).testTag("trading_core_save")) { Text(tradingText("mark_save")) }
        TextButton(onClick = cancel, enabled = !busy, modifier = Modifier.heightIn(min = 48.dp).testTag("trading_core_cancel")) { Text(tradingText("cancel")) }
    }
}
