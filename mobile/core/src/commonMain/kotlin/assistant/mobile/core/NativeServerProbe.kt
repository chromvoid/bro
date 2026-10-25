package assistant.mobile.core

import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

sealed interface NativeServerCheckResult {
    data class Compatible(val origin: String, val instanceId: String, val name: String) : NativeServerCheckResult
    data object InvalidAddress : NativeServerCheckResult
    data object Unreachable : NativeServerCheckResult
    data object NotBro : NativeServerCheckResult
    data object Unsupported : NativeServerCheckResult
    data object AppTooOld : NativeServerCheckResult
}

/** This client has no session, credentials, cookies, redirects or retries. */
class NativeServerProbe(
    engine: HttpClientEngine,
    private val allowInsecureLoopback: Boolean = false,
) {
    private val client = HttpClient(engine) {
        expectSuccess = false
        followRedirects = false
        install(HttpTimeout) {
            connectTimeoutMillis = TIMEOUT_MS
            requestTimeoutMillis = TIMEOUT_MS
            socketTimeoutMillis = TIMEOUT_MS
        }
    }

    suspend fun check(address: String): NativeServerCheckResult {
        val origin = runCatching { normalizeNativeServerAddress(address, allowInsecureLoopback) }
            .getOrNull() ?: return NativeServerCheckResult.InvalidAddress
        return try {
            // The outer deadline includes consuming the entire streamed response body.
            withTimeoutOrNull(TIMEOUT_MS) {
                client.prepareGet("$origin/v1/client/config") {
                    headers.append(HttpHeaders.Accept, ContentType.Application.Json.toString())
                }.execute { response ->
                    val contentType = runCatching { response.contentType()?.withoutParameters() }.getOrNull()
                    val lengthHeader = response.headers[HttpHeaders.ContentLength]
                    val declaredLength = lengthHeader?.toLongOrNull()
                    when {
                        response.status.value in 500..599 -> NativeServerCheckResult.Unreachable
                        response.status.value != 200 || contentType != ContentType.Application.Json ||
                            (lengthHeader != null && (declaredLength == null || declaredLength !in 0..MAX_RESPONSE_BYTES.toLong())) ->
                            NativeServerCheckResult.NotBro
                        else -> {
                            val bytes = ByteArray(MAX_RESPONSE_BYTES + 1)
                            val channel = response.bodyAsChannel()
                            var size = 0
                            while (size <= MAX_RESPONSE_BYTES) {
                                val count = channel.readAvailable(bytes, size, bytes.size - size)
                                if (count < 0) break
                                size += count
                            }
                            if (size > MAX_RESPONSE_BYTES) NativeServerCheckResult.NotBro
                            else decodeConfiguration(origin, bytes.copyOf(size))
                        }
                    }
                }
            } ?: NativeServerCheckResult.Unreachable
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            NativeServerCheckResult.Unreachable
        }
    }

    fun close() { client.close() }

    private companion object {
        const val TIMEOUT_MS = 10_000L
        const val MAX_RESPONSE_BYTES = 16 * 1024
    }
}

fun normalizeNativeServerAddress(address: String, allowInsecureLoopback: Boolean = false): String {
    val trimmed = address.trim()
    return canonicalOrigin(if ("://" in trimmed) trimmed else "https://$trimmed", allowInsecureLoopback)
}

@OptIn(ExperimentalUuidApi::class)
private fun decodeConfiguration(origin: String, bytes: ByteArray): NativeServerCheckResult {
    val fields = runCatching {
        NativeJson.parseToJsonElement(bytes.decodeToString(throwOnInvalidSequence = true)) as? JsonObject
    }.getOrNull() ?: return NativeServerCheckResult.NotBro
    fun string(key: String) = (fields[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
    fun integer(key: String) = (fields[key] as? JsonPrimitive)?.takeUnless { it.isString }?.intOrNull
    val instanceId = string("instance_id")?.let { runCatching { Uuid.parse(it).toString() }.getOrNull() }
        ?: return NativeServerCheckResult.NotBro
    val name = string("name")?.takeIf { it.length in 1..120 } ?: return NativeServerCheckResult.NotBro
    if (integer("client_api") != 1) return NativeServerCheckResult.NotBro
    if ("native_api" !in fields) return NativeServerCheckResult.Unsupported
    val nativeApi = (fields["native_api"] as? JsonPrimitive)?.takeUnless { it.isString }?.content
        ?.takeIf { it.isNotEmpty() && it[0] in '1'..'9' && it.all { digit -> digit in '0'..'9' } }
        ?: return NativeServerCheckResult.NotBro
    if (nativeApi != "1") return NativeServerCheckResult.AppTooOld
    return NativeServerCheckResult.Compatible(origin, instanceId, name)
}
