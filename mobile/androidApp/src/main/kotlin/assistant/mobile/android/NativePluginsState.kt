package assistant.mobile.android

import assistant.mobile.core.AccountNamespace
import assistant.mobile.pluginapi.InvocationOutcome
import assistant.mobile.pluginapi.PluginInstallation

data class NativePluginsState(
    val namespace: AccountNamespace? = null,
    val loading: Boolean = false,
    val installations: List<PluginInstallation> = emptyList(),
    val error: InvocationOutcome? = null,
)
