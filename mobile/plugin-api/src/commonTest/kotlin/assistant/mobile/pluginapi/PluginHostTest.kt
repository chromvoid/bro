package assistant.mobile.pluginapi

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class PluginHostTest {
    @Test
    fun pluginDestinationsRetainTheirInstallation() {
        val json = Json { ignoreUnknownKeys = true }
        val destination = PluginScreen(InstallationId("synthetic-installation"), "portfolio")
        assertEquals(destination, json.decodeFromString<PluginScreen>(json.encodeToString(destination)))
        assertEquals(destination, json.decodeFromString<PluginScreen>(
            """{"installation":"synthetic-installation","screen":"portfolio","future":true}"""))
    }

    @Test
    fun unknownApiIsUnsupported() {
        val descriptor = PluginDescriptor(InstallationId("synthetic"), "trading", 1, setOf("portfolio"))
        assertEquals(PluginCompatibility.Supported, descriptor.compatibility())
        assertEquals(PluginCompatibility.Unsupported(2), descriptor.copy(clientApi = 2).compatibility())
    }

    @Test
    fun compiledUiRequiresBothDeclaredToolMajorsAndClientApi() {
        val module = NativePluginUi("trading", "trading.home", requiredTools = mapOf("portfolio.get" to 1))
        val installation = PluginInstallation("synthetic", PluginPackage("trading"), "Трейдинг", "ready",
            tools = listOf(PluginTool("portfolio.get", "1.2.0", "read"), PluginTool("position.chart", "1.0.0", "read")),
            ui = listOf(PluginContribution("trading.home", "native", "Трейдинг", 1, mapOf("position.chart" to 1))))
        assertIs<PluginUiMatch.Supported>(installation.matchUi(listOf(module)))
        assertEquals(PluginUiMatch.Unsupported(PluginUiFallback.ClientApi),
            installation.copy(ui = installation.ui.map { it.copy(clientApi = 2) }).matchUi(listOf(module)))
        assertEquals(PluginUiMatch.Unsupported(PluginUiFallback.ClientApi),
            installation.matchUi(listOf(module.copy(clientApi = 2))))
        assertEquals(PluginUiMatch.Unsupported(PluginUiFallback.MissingTools),
            installation.copy(tools = installation.tools.filter { it.id != "position.chart" }).matchUi(listOf(module)))
        assertEquals(PluginUiMatch.Unsupported(PluginUiFallback.MissingTools),
            installation.copy(tools = installation.tools.map { it.copy(version = "2.0.0") }).matchUi(listOf(module)))
        assertEquals(PluginUiMatch.Unsupported(PluginUiFallback.MissingTools),
            installation.matchUi(listOf(module.copy(requiredTools = mapOf("portfolio.get" to 3)))))
        assertEquals(PluginUiMatch.Unsupported(PluginUiFallback.NoUi),
            installation.copy(`package` = PluginPackage("unknown-package")).matchUi(listOf(module)))
    }

    @Test
    fun unknownOrRevokedInstallationNeverActivatesANativeModule() {
        val module = NativePluginUi("trading", "trading.home", requiredTools = emptyMap())
        val installation = PluginInstallation("synthetic", PluginPackage("trading"), "Трейдинг", "ready",
            ui = listOf(PluginContribution("trading.home", "native", "Трейдинг", 1)))
        for (status in listOf("disabled", "incompatible", "rejected", "future-status")) {
            assertEquals(PluginUiMatch.Unsupported(PluginUiFallback.Unavailable),
                installation.copy(status = status).matchUi(listOf(module)))
        }
        for (status in listOf("starting", "ready", "degraded", "unavailable")) {
            assertIs<PluginUiMatch.Supported>(installation.copy(status = status).matchUi(listOf(module)))
        }
    }
}
