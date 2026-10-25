package assistant.mobile.android

import androidx.compose.runtime.Composable
import assistant.mobile.core.GeneratedStrings
import assistant.mobile.core.NativeHttpClient
import assistant.mobile.core.SessionManager
import assistant.mobile.core.journal.SubmissionCoordinator
import assistant.mobile.pluginapi.PluginScreen
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** The public distribution contains no private native plugin or plugin catalog requests. */
@Suppress("UNUSED_PARAMETER")
class NativePluginsController(
    scope: CoroutineScope,
    private val sessions: SessionManager,
    http: NativeHttpClient,
    submissions: SubmissionCoordinator,
    navigate: (PluginScreen) -> Unit = {},
    closeScreen: () -> Unit = {},
) {
    private val mutableState = MutableStateFlow(NativePluginsState())
    val state: StateFlow<NativePluginsState> = mutableState.asStateFlow()

    suspend fun refresh() { reload() }
    fun reload() { mutableState.value = NativePluginsState(namespace = sessions.lease()?.namespace) }
    fun close() { mutableState.value = NativePluginsState() }
}

@Suppress("UNUSED_PARAMETER")
@Composable
internal fun NativePluginScreen(
    plugins: NativePluginsController,
    installationId: String,
    screen: String,
    coin: String?,
    navigate: (String, String?) -> Unit,
) {
    ScreenColumn("plugin_unavailable", title = localized(GeneratedStrings.Plugins.title)) {
        StatusMessage(GeneratedStrings.Plugins.missing)
        StatusMessage(GeneratedStrings.Plugins.missingText)
    }
}
