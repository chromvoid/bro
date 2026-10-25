package assistant.mobile.android

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import assistant.mobile.core.GeneratedStrings
import assistant.mobile.trading.TradingScreen
import assistant.mobile.pluginapi.PluginUiFallback
import assistant.mobile.pluginapi.PluginUiMatch
import assistant.mobile.pluginapi.matchUi

@Composable
internal fun NativePluginScreen(
    plugins: NativePluginsController,
    installationId: String,
    screen: String,
    coin: String?,
    navigate: (String, String?) -> Unit,
) {
    val state by plugins.state.collectAsStateWithLifecycle()
    val installation = state.installations.firstOrNull { it.installationId == installationId }
    val shownScreen = when {
        screen == "home" -> "portfolio"
        screen == "settings" -> "sync"
        screen == "pnl" && coin != null -> "position"
        screen == "position" && coin == null -> "pnl"
        else -> screen
    }
    val match = installation?.matchUi(NATIVE_PLUGIN_MODULES)
    val knownScreen = screen in setOf(
        "home", "portfolio", "pnl", "position", "orders", "trades", "cashflow", "sync", "connect", "settings",
    )
    val trading = if (match is PluginUiMatch.Supported && knownScreen) plugins.trading(installationId) else null
    val title = installation?.title ?: localized(GeneratedStrings.Plugins.title)
    if (trading != null) Column(Modifier.fillMaxSize()) {
        NativeTopBar(title)
        TradingScreen(trading, shownScreen, coin, navigate, modifier = Modifier.weight(1f).fillMaxWidth())
    } else ScreenColumn("trading_unsupported", title = title) {
        when {
            installation == null && state.loading -> StatusMessage(GeneratedStrings.State.loading)
            installation == null && state.error != null -> PluginCatalogFailure(plugins)
            installation == null -> {
                StatusMessage(GeneratedStrings.Plugins.missing)
                StatusMessage(GeneratedStrings.Plugins.missingText)
            }
            else -> {
                val reason = (match as? PluginUiMatch.Unsupported)?.reason ?: PluginUiFallback.NoUi
                StatusMessage(when (reason) {
                    PluginUiFallback.NoUi -> GeneratedStrings.Plugins.Fallback.no_ui
                    PluginUiFallback.ClientApi -> GeneratedStrings.Plugins.Fallback.client_api
                    PluginUiFallback.MissingTools -> GeneratedStrings.Plugins.Fallback.missing_tools
                    PluginUiFallback.Unavailable -> GeneratedStrings.Plugins.Fallback.unavailable
                })
                if (reason == PluginUiFallback.Unavailable) installation.note?.let { Text(it) }
                StatusMessage(GeneratedStrings.Plugins.others)
            }
        }
    }
}

@Composable
private fun PluginCatalogFailure(plugins: NativePluginsController) {
    ErrorMessage(GeneratedStrings.State.errorTitle, "plugins_error")
    TextButton(onClick = plugins::reload, modifier = Modifier.heightIn(min = 48.dp).testTag("plugins_retry")) {
        Text(localized(GeneratedStrings.Common.retry))
    }
}
