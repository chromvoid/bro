package assistant.mobile.core.plugins

import assistant.mobile.core.HttpOutcome
import assistant.mobile.core.NativeHttpClient
import assistant.mobile.core.NativeJson
import assistant.mobile.core.SessionManager
import assistant.mobile.core.retryAfterDeadline
import assistant.mobile.pluginapi.InvocationOutcome
import assistant.mobile.pluginapi.PluginError
import assistant.mobile.pluginapi.PluginFieldError
import assistant.mobile.pluginapi.PluginInstallation
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlin.time.Clock

sealed interface PluginCatalogOutcome {
    data class Success(val installations: List<PluginInstallation>) : PluginCatalogOutcome
    data class Failure(val outcome: InvocationOutcome) : PluginCatalogOutcome
}

class NativePluginCatalog(private val sessions: SessionManager, private val http: NativeHttpClient) {
    suspend fun list(): PluginCatalogOutcome {
        val lease = sessions.lease() ?: return PluginCatalogOutcome.Failure(pluginDenied(401))
        return when (val response = http.request("GET", "/v1/plugins", lease = lease, preserveRejectionBody = true)) {
            is HttpOutcome.Success -> try {
                PluginCatalogOutcome.Success(decodePluginCatalog(response.body))
            } catch (_: IllegalArgumentException) {
                PluginCatalogOutcome.Failure(InvocationOutcome.Unknown(error = PluginError("unexpected_state")))
            }
            else -> PluginCatalogOutcome.Failure(pluginHttpOutcome(response))
        }
    }
}

/** A malformed installation never hides valid neighbours. */
fun decodePluginCatalog(bytes: ByteArray): List<PluginInstallation> {
    val root = requireNotNull(pluginObject(bytes))
    val entries = requireNotNull(root["installations"] as? JsonArray)
    return entries.mapNotNull { entry ->
        runCatching {
            NativeJson.decodeFromJsonElement(PluginInstallation.serializer(), entry).also {
                require(validInstallationId(it.installationId))
            }
        }.getOrNull()
    }
}

internal fun validInstallationId(value: String): Boolean =
    value.length in 1..200 && value !in setOf(".", "..") && value.all {
        it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it in "._~-"
    }

internal val pluginTerminalStates = setOf("completed", "failed", "cancelled")
internal val pluginNetworkError = PluginError("network_unavailable", "Нет связи с сервером")

internal fun pluginObject(bytes: ByteArray?): JsonObject? = try {
    bytes?.decodeToString(throwOnInvalidSequence = true)?.let(NativeJson::parseToJsonElement) as? JsonObject
} catch (_: Exception) { null }

internal fun JsonObject.pluginString(key: String): String? =
    (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

internal fun pluginResult(bytes: ByteArray?): JsonObject? = pluginObject(bytes)?.let {
    val result = if (it.pluginString("contract") == "assistant.lookup/v1") it["result"] as? JsonObject else it
    result?.takeIf { record -> record.pluginString("state") != null }
}

internal fun pluginResultOutcome(bytes: ByteArray?, key: String?): InvocationOutcome {
    val result = pluginResult(bytes)
        ?: return InvocationOutcome.Unknown(key, error = PluginError("unexpected_state"))
    val receipts = (result["receipts"] as? JsonArray)?.toList().orEmpty()
    return when (val state = result.pluginString("state")) {
        "completed" -> InvocationOutcome.Completed(result["data"] ?: JsonNull, receipts, key)
        "failed", "cancelled" -> InvocationOutcome.Failed(
            (result["error"] as? JsonObject)?.let(::pluginWireError) ?: PluginError(state), receipts, key,
        )
        "accepted", "running" -> if (key != null) InvocationOutcome.Pending(key, state, result["progress"] ?: JsonNull)
            else InvocationOutcome.Unknown(error = PluginError("unexpected_state"))
        else -> InvocationOutcome.Unknown(key, error = PluginError("unexpected_state"))
    }
}

internal fun pluginWireError(body: JsonObject): PluginError {
    val code = body.pluginString("code")?.take(64) ?: "unexpected_state"
    return PluginError(
        code, (body.pluginString("message")?.ifEmpty { code } ?: code).take(500),
        body.pluginString("recovery")?.takeIf {
            it in setOf("fix_arguments", "reauthenticate", "refresh_state", "lookup", "none")
        } ?: "none",
        (body["field_errors"] as? JsonArray).orEmpty().take(50).mapNotNull {
            val field = it as? JsonObject ?: return@mapNotNull null
            PluginFieldError(field.pluginString("path") ?: return@mapNotNull null,
                field.pluginString("code") ?: return@mapNotNull null)
        },
        (body["retry_after_ms"] as? JsonPrimitive)?.longOrNull,
    )
}

internal fun pluginDenied(status: Int, key: String? = null): InvocationOutcome.Rejected =
    InvocationOutcome.Rejected(PluginError(if (status == 401) "unauthorized" else "forbidden"), status, key)

internal fun pluginHttpOutcome(
    response: HttpOutcome, key: String? = null,
    nowMillis: Long = Clock.System.now().toEpochMilliseconds(),
): InvocationOutcome = when (response) {
    HttpOutcome.Unauthorized -> pluginDenied(401, key)
    HttpOutcome.Forbidden -> pluginDenied(403, key)
    is HttpOutcome.Failure -> {
        val root = pluginObject(response.body)
        val detail = (root?.get("detail") as? JsonObject) ?: root
        val parsed = detail?.takeIf { it.pluginString("code") != null }?.let(::pluginWireError)
            ?: PluginError("http_${response.status}", recovery = if (response.status == 504) "lookup" else "none")
        val retryAfter = response.headers.entries.firstOrNull { it.key.equals("retry-after", ignoreCase = true) }?.value
        val error = if (parsed.retryAfterMs == null && retryAfter != null) parsed.copy(
            retryAfterMs = (retryAfterDeadline(retryAfter, nowMillis) - nowMillis).coerceAtLeast(0)) else parsed
        if (response.status == 504) InvocationOutcome.Unknown(key, error = error)
        else InvocationOutcome.Rejected(error, response.status, key)
    }
    is HttpOutcome.Conflict -> {
        val root = pluginObject(response.body)
        val detail = (root?.get("detail") as? JsonObject) ?: root
        InvocationOutcome.Rejected(detail?.let(::pluginWireError) ?: PluginError("http_409"), 409, key)
    }
    is HttpOutcome.RateLimited -> InvocationOutcome.Rejected(PluginError("rate_limited",
        retryAfterMs = (response.retryAtMillis - nowMillis).coerceAtLeast(0)), 429, key)
    HttpOutcome.StaleSession -> InvocationOutcome.Rejected(PluginError("stale_session"), clientKey = key)
    HttpOutcome.StorageUnavailable -> InvocationOutcome.Unknown(key, error = PluginError("storage_unavailable"))
    HttpOutcome.RejectedOrigin, HttpOutcome.RejectedRequest -> InvocationOutcome.Rejected(PluginError("invalid_arguments"), clientKey = key)
    else -> InvocationOutcome.Unknown(key, error = pluginNetworkError)
}
