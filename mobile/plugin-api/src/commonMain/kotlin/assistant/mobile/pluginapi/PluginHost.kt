package assistant.mobile.pluginapi

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlin.jvm.JvmInline

/** Native feature modules never receive a session secret. */
@Serializable
@JvmInline
value class InstallationId(val value: String) {
    init { require(value.isNotBlank()) }
}

@Serializable
data class PluginScreen(val installation: InstallationId, val screen: String) {
    init { require(screen.isNotBlank()) }
}

@Serializable
data class PluginDescriptor(
    val installation: InstallationId,
    val pluginId: String,
    val clientApi: Int,
    val screens: Set<String>,
)

sealed interface PluginCompatibility {
    data object Supported : PluginCompatibility
    data class Unsupported(val clientApi: Int) : PluginCompatibility
}

/** An unknown API never activates a guessed UI. */
fun PluginDescriptor.compatibility(): PluginCompatibility =
    if (clientApi == PluginHost.VERSION) PluginCompatibility.Supported
    else PluginCompatibility.Unsupported(clientApi)

@Serializable
data class PluginPackage(val id: String, val version: String? = null)

@Serializable
data class PluginTool(
    val id: String,
    val version: String,
    val effect: String,
    val scopes: List<String>? = null,
    val surfaces: List<String>? = null,
)

@Serializable
data class PluginContribution(
    val id: String,
    val kind: String,
    val title: String,
    @SerialName("client_api") val clientApi: Int? = null,
    @SerialName("requires_tools") val requiredTools: Map<String, Int>? = null,
)

@Serializable
data class PluginConfigurationAccess(val writable: Boolean)

@Serializable
data class PluginEventType(val type: String)

@Serializable
data class PluginInstallation(
    @SerialName("installation_id") val installationId: String,
    val `package`: PluginPackage,
    val title: String,
    val status: String,
    val description: String? = null,
    val note: String? = null,
    @SerialName("granted_scopes") val grantedScopes: List<String>? = null,
    val tools: List<PluginTool> = emptyList(),
    val ui: List<PluginContribution> = emptyList(),
    val configuration: PluginConfigurationAccess? = null,
    val events: List<PluginEventType> = emptyList(),
)

data class NativePluginUi(
    val packageId: String,
    val contributionId: String,
    val clientApi: Int = PluginHost.VERSION,
    val requiredTools: Map<String, Int>,
)

enum class PluginUiFallback { NoUi, ClientApi, MissingTools, Unavailable }

sealed interface PluginUiMatch {
    data class Supported(val module: NativePluginUi) : PluginUiMatch
    data class Unsupported(val reason: PluginUiFallback) : PluginUiMatch
}

/** The server's requirements and the compiled module's requirements both apply. */
fun PluginInstallation.matchUi(registry: List<NativePluginUi>): PluginUiMatch {
    if (!dispatchablePluginStatus(status)) return PluginUiMatch.Unsupported(PluginUiFallback.Unavailable)
    var fallback = PluginUiFallback.NoUi
    val majors = tools.associate { it.id to it.version.substringBefore('.').toIntOrNull() }
    for (contribution in ui.filter { it.kind == "native" }) {
        val module = registry.firstOrNull {
            it.packageId == `package`.id && it.contributionId == contribution.id
        } ?: continue
        if (contribution.clientApi != PluginHost.VERSION || module.clientApi != PluginHost.VERSION) {
            fallback = PluginUiFallback.ClientApi
            continue
        }
        if ((contribution.requiredTools.orEmpty().entries + module.requiredTools.entries)
                .any { (tool, major) -> majors[tool] != major }) {
            fallback = PluginUiFallback.MissingTools
            continue
        }
        return PluginUiMatch.Supported(module)
    }
    return PluginUiMatch.Unsupported(fallback)
}

fun dispatchablePluginStatus(status: String): Boolean =
    status in setOf("starting", "ready", "degraded", "unavailable")

data class PluginCapabilities(
    val status: String,
    val note: String?,
    val grantedScopes: List<String>,
    val tools: Map<String, PluginTool>,
    val configurationWritable: Boolean?,
    val events: List<String>,
)

fun PluginInstallation.capabilities(): PluginCapabilities = PluginCapabilities(
    status.takeIf { it in setOf("starting", "ready", "degraded", "unavailable", "incompatible", "rejected", "disabled") } ?: "unknown",
    note, grantedScopes.orEmpty(), tools.associateBy { it.id }, configuration?.writable, events.map { it.type },
)

/** Stable codes and argument-free field errors; protocol failures are values. */
data class PluginError(
    val code: String,
    val message: String = code,
    val recovery: String = "none",
    val fieldErrors: List<PluginFieldError> = emptyList(),
    val retryAfterMs: Long? = null,
)

data class PluginFieldError(val path: String, val code: String)

sealed interface InvocationOutcome {
    data class Completed(val data: JsonElement, val receipts: List<JsonElement> = emptyList(), val clientKey: String? = null) : InvocationOutcome
    data class Failed(val error: PluginError, val receipts: List<JsonElement> = emptyList(), val clientKey: String? = null) : InvocationOutcome
    data class Pending(val clientKey: String, val state: String, val progress: JsonElement = JsonNull) : InvocationOutcome
    data class Unknown(val clientKey: String? = null, val notFound: Boolean = false, val error: PluginError? = null) : InvocationOutcome
    data class Rejected(val error: PluginError, val status: Int? = null, val clientKey: String? = null) : InvocationOutcome
}

data class PendingPluginWrite(
    val clientKey: String,
    val tool: String,
    val arguments: JsonObject,
    val state: String,
    val createdAt: Long,
    val notFound: Boolean,
    val admitted: Boolean,
) {
    override fun toString(): String = "PendingPluginWrite(clientKey=$clientKey, state=$state, arguments=redacted)"
}

sealed interface PluginEvent {
    data class Ready(val cursor: String) : PluginEvent
    data class Resync(val cursor: String) : PluginEvent
    data class Delivered(
        val eventId: String,
        val type: String,
        val occurredAt: String,
        val cursor: String,
        val payload: JsonObject,
        val operationKey: String? = null,
    ) : PluginEvent
    data class Denied(val status: Int) : PluginEvent
}

/** Configuration never enters the journal or saved UI state; secrets exist only in a PUT body. */
interface PluginConfiguration {
    suspend fun get(): InvocationOutcome
    suspend fun put(
        settings: JsonObject,
        secrets: Map<String, String>? = null,
        expectedRevision: Long? = null,
        options: JsonObject? = null,
    ): InvocationOutcome
    suspend fun delete(expectedRevision: Long, purge: Boolean = true): InvocationOutcome
}

interface PluginHost {
    val installationId: String
    val packageId: String
    val locale: String
    val capabilities: StateFlow<PluginCapabilities>
    val pendingWrites: StateFlow<List<PendingPluginWrite>>
    val configuration: PluginConfiguration
    suspend fun invoke(tool: String, args: JsonObject = JsonObject(emptyMap())): InvocationOutcome
    suspend fun lookup(clientKey: String): InvocationOutcome
    /** Explicit user action: reuses the original key and exact bytes after a definite 404. */
    suspend fun retry(clientKey: String): InvocationOutcome
    suspend fun acknowledge(clientKey: String)
    /** Collect only while the screen is visible. Cancellation closes the stream. */
    fun observe(): Flow<PluginEvent>
    fun navigate(destination: PluginScreen)
    fun close()

    companion object { const val VERSION = 1 }
}
