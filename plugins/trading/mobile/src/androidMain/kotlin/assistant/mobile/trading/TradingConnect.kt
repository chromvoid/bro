package assistant.mobile.trading

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.launch

/** Secrets are transient fields, cleared before dispatch, when backgrounded and on disposal. */
@Composable
@OptIn(ExperimentalLayoutApi::class)
internal fun TradingConnect(state: TradingState, controller: TradingController, navigate: (String, String?) -> Unit) {
    val scope = rememberCoroutineScope()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val view = state.configuration.valueOrNull()
    var apiKey by remember(controller) { mutableStateOf("") }
    var apiSecret by remember(controller) { mutableStateOf("") }
    var environment by remember(controller) { mutableStateOf<String?>(null) }
    var label by remember(controller) { mutableStateOf("") }
    var historyDays by remember(controller) { mutableStateOf("") }
    var replaceAccount by remember(controller) { mutableStateOf(false) }
    var confirming by remember(controller) { mutableStateOf(false) }
    DisposableEffect(controller, lifecycle) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_STOP) { apiKey = ""; apiSecret = "" } }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer); apiKey = ""; apiSecret = "" }
    }
    val keyValid = apiKey.trim().matches(Regex("[A-Za-z0-9]{10,64}")) && apiSecret.trim().matches(Regex("[A-Za-z0-9]{10,128}"))
    val historyValid = historyDays.isBlank() || historyDays.all { it in '0'..'9' } && (historyDays.toIntOrNull() ?: 0) in 1..730
    val valid = keyValid && historyValid && label.length <= 64
    val refresh = { scope.launch { controller.refresh() }; Unit }
    Column(Modifier.testTag("trading_connect"), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(tradingText("connect_title"), style = MaterialTheme.typography.headlineMedium)
        TradingReadStatus(state.configuration, state, refresh, navigate)
        view?.let {
            TradingSection(tag = "trading_connection") {
                Text(tradingConnectionLabel(it.connectionState ?: it.status), style = MaterialTheme.typography.titleMedium)
                it.secretState["api_key"]?.hint?.let { hint -> Text(tradingText("connected_key", hint, it.settings.environment.orEmpty()), style = MaterialTheme.typography.bodySmall) }
                if (it.keyInfo?.ipBound == false) Text(tradingText("key_not_ip_bound"), color = tradingColor("warning"), style = MaterialTheme.typography.bodySmall)
                it.keyInfo?.expiresAt?.let { expires -> Text(tradingText("key_expires", TradingFormat.dateTime(expires)), style = MaterialTheme.typography.bodySmall) }
                it.readiness.issues.filter { issue -> issue.code !in setOf("not_configured", "key_not_ip_bound") }.forEach { issue ->
                    Text(tradingConnectionLabel(issue.code), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(tradingText("guide_read_only"), style = MaterialTheme.typography.bodySmall)
            Text(if (view?.egressIps?.isNotEmpty() == true) tradingText("guide_ip", view.egressIps.joinToString(", ")) else tradingText("guide_ip_unknown"), style = MaterialTheme.typography.bodySmall)
            Text(tradingText("guide_permissions"), style = MaterialTheme.typography.bodySmall)
            Text(tradingText("guide_secret"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        state.notice?.let { notice ->
            val text = when (notice) {
                ConnectNotice.Connected -> tradingText("connect_done")
                ConnectNotice.Disconnected -> tradingText("disconnect_done")
                ConnectNotice.Unknown -> tradingText("connect_unknown")
                is ConnectNotice.Failed -> tradingErrorText(notice.error, view?.egressIps.orEmpty())
            }
            Text(text, color = if (notice is ConnectNotice.Failed) MaterialTheme.colorScheme.error else tradingColor("success"),
                modifier = Modifier.testTag("trading_connect_notice").semantics { liveRegion = LiveRegionMode.Polite })
        }
        if (view != null && !state.configurationWritable) Text(tradingText("no_config_rights"), style = MaterialTheme.typography.bodySmall)
        if (view != null && state.configurationWritable) TradingSection {
            if (view.environments.size > 1) {
                Text(tradingText("environment"), style = MaterialTheme.typography.labelLarge)
                FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    view.environments.forEach { value -> FilterChip(selected = value == (environment ?: view.settings.environment),
                        onClick = { environment = value }, label = { Text(value) }, modifier = Modifier.heightIn(min = 48.dp)) }
                }
            }
            OutlinedTextField(value = apiKey, onValueChange = { apiKey = it.take(64) }, label = { Text(tradingText("api_key")) },
                singleLine = true, visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, keyboardType = KeyboardType.Password),
                modifier = Modifier.fillMaxWidth().testTag("trading_api_key"))
            OutlinedTextField(value = apiSecret, onValueChange = { apiSecret = it.take(128) }, label = { Text(tradingText("api_secret")) },
                singleLine = true, visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, keyboardType = KeyboardType.Password),
                modifier = Modifier.fillMaxWidth().testTag("trading_api_secret"))
            if ((apiKey.isNotEmpty() || apiSecret.isNotEmpty()) && !keyValid) Text(tradingText("key_format"), style = MaterialTheme.typography.bodySmall)
            OutlinedTextField(value = label, onValueChange = { label = it.take(64) }, label = { Text(tradingText("label_optional")) },
                modifier = Modifier.fillMaxWidth().testTag("trading_connection_label"), singleLine = true)
            OutlinedTextField(value = historyDays, onValueChange = { historyDays = it.take(3) }, label = { Text(tradingText("history_days")) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), isError = !historyValid,
                supportingText = if (!historyValid) ({ Text(tradingText("history_days_invalid")) }) else null,
                modifier = Modifier.fillMaxWidth().testTag("trading_history_days"), singleLine = true)
            if (view.configured) Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = replaceAccount, onCheckedChange = { replaceAccount = it }, modifier = Modifier.testTag("trading_replace_account"))
                Text(tradingText("replace_account"), style = MaterialTheme.typography.bodySmall)
            }
            Button(onClick = {
                val request = TradingConnectRequest(apiKey.trim(), apiSecret.trim(), environment, label,
                    historyDays.trim().takeIf { it.isNotEmpty() }?.toIntOrNull(), replaceAccount)
                apiKey = ""; apiSecret = ""; replaceAccount = false
                if (valid) scope.launch { controller.connect(request) }
            }, enabled = !state.acting && valid, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("trading_connect_submit")) {
                Text(tradingText(if (view.configured) "replace_key" else "connect_submit"))
            }
            if (view.configured) OutlinedButton(onClick = { confirming = true }, enabled = !state.acting,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("trading_disconnect")) { Text(tradingText("disconnect")) }
        }
    }
    if (confirming) AlertDialog(onDismissRequest = { confirming = false },
        title = { Text(tradingText("disconnect_title")) }, text = { Text(tradingText("disconnect_text")) },
        confirmButton = { TextButton(onClick = { confirming = false; scope.launch { controller.disconnect() } }, modifier = Modifier.heightIn(min = 48.dp)) { Text(tradingText("confirm_disconnect")) } },
        dismissButton = { TextButton(onClick = { confirming = false }, modifier = Modifier.heightIn(min = 48.dp)) { Text(tradingText("cancel")) } })
}
