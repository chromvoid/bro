package assistant.mobile.android

import assistant.mobile.core.NativeHttpClient
import assistant.mobile.core.SessionManager

import assistant.mobile.core.journal.SubmissionCoordinator
import assistant.mobile.core.plugins.NativePluginCatalog
import assistant.mobile.core.plugins.NativePluginHost
import assistant.mobile.core.plugins.PluginCatalogOutcome
import assistant.mobile.trading.TRADING_REQUIRED_TOOLS
import assistant.mobile.trading.TradingController
import assistant.mobile.pluginapi.NativePluginUi
import assistant.mobile.pluginapi.PluginScreen
import assistant.mobile.pluginapi.PluginUiMatch
import assistant.mobile.pluginapi.matchUi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

val NATIVE_PLUGIN_MODULES = listOf(NativePluginUi("trading", "trading.home", requiredTools = TRADING_REQUIRED_TOOLS))

/** Account-owned catalog and compiled modules. A refreshed catalog can revoke an open module. */
class NativePluginsController(
    private val scope: CoroutineScope,
    private val sessions: SessionManager,
    http: NativeHttpClient,
    private val submissions: SubmissionCoordinator,
    private val navigate: (PluginScreen) -> Unit = {},
    private val closeScreen: () -> Unit = {},
) {
    private val catalog = NativePluginCatalog(sessions, http)
    private val http = http
    private val mutableState = MutableStateFlow(NativePluginsState())
    val state: StateFlow<NativePluginsState> = mutableState.asStateFlow()
    private data class Module(val host: NativePluginHost, val trading: TradingController)
    private val modules = mutableMapOf<String, Module>()
    private var refreshSequence = 0L

    init {
        scope.launch {
            sessions.leases.collectLatest { lease ->
                clearModules()
                mutableState.value = NativePluginsState(namespace = lease?.namespace)
                if (lease != null) refresh()
            }
        }
    }

    suspend fun refresh() {
        val lease = sessions.lease() ?: return
        val sequence = ++refreshSequence
        mutableState.value = mutableState.value.copy(loading = true, error = null)
        val result = catalog.list()
        if (!sessions.isCurrent(lease) || sequence != refreshSequence) return
        when (result) {
            is PluginCatalogOutcome.Success -> {
                val supported = result.installations.filter { it.matchUi(NATIVE_PLUGIN_MODULES) is PluginUiMatch.Supported }
                    .associateBy { it.installationId }
                modules.keys.toList().forEach { id ->
                    val installation = supported[id]
                    if (installation == null) modules.remove(id)?.let { it.trading.close(); it.host.shutdown() }
                    else modules.getValue(id).host.update(installation)
                }
                mutableState.value = NativePluginsState(lease.namespace, installations = result.installations)
            }
            is PluginCatalogOutcome.Failure -> mutableState.value = mutableState.value.copy(loading = false, error = result.outcome)
        }
    }

    fun reload() { scope.launch { refresh() } }

    fun trading(installationId: String): TradingController? {
        val lease = sessions.lease() ?: return null
        if (state.value.namespace != lease.namespace) return null
        val installation = state.value.installations.firstOrNull { it.installationId == installationId } ?: return null
        if (installation.matchUi(NATIVE_PLUGIN_MODULES) !is PluginUiMatch.Supported) return null
        return modules.getOrPut(installationId) {
            val host = NativePluginHost(scope, sessions, http, submissions, installation, navigate, closeScreen)
            scope.launch { host.restore() }
            Module(host, TradingController(scope, host))
        }.trading
    }

    private fun clearModules() {
        ++refreshSequence
        modules.values.forEach { it.trading.close(); it.host.shutdown() }
        modules.clear()
    }

    fun close() { clearModules() }
}
