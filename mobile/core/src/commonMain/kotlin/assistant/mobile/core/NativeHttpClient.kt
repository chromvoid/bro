package assistant.mobile.core

import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.HttpTimeoutConfig
import io.ktor.client.plugins.timeout
import io.ktor.client.plugins.onUpload
import io.ktor.client.request.prepareRequest
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsBytes
import io.ktor.http.HttpMethod
import io.ktor.http.URLBuilder
import io.ktor.http.URLProtocol
import io.ktor.http.Url
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlin.time.Clock

sealed interface HttpOutcome {
    data class Success(val status: Int, val body: ByteArray, val headers: Map<String, String>) : HttpOutcome {
        override fun toString(): String = "Success(status=$status, body=redacted)"
    }
    data object Unauthorized : HttpOutcome
    data object Forbidden : HttpOutcome
    data class Conflict(val body: ByteArray) : HttpOutcome {
        override fun toString(): String = "Conflict(body=redacted)"
    }
    data class RateLimited(val retryAtMillis: Long) : HttpOutcome
    data class Failure(val status: Int, val body: ByteArray, val headers: Map<String, String> = emptyMap()) : HttpOutcome {
        override fun toString(): String = "Failure(status=$status, body=redacted)"
    }
    data object NetworkFailure : HttpOutcome
    data object StaleSession : HttpOutcome
    data object RejectedOrigin : HttpOutcome
    data object RejectedRequest : HttpOutcome
    data object InvalidResponse : HttpOutcome
    data object StorageUnavailable : HttpOutcome
}

/** HTTPS in production; the only cleartext exception is an explicitly requested CI loopback. */
fun canonicalOrigin(value: String, allowInsecureLoopback: Boolean = false): String {
    require(value.none { it.isWhitespace() || it.isISOControl() || it in "\\?#@" })
    val schemeEnd = value.indexOf("://")
    require(schemeEnd > 0)
    val authority = value.substring(schemeEnd + 3).substringBefore('/')
    require(authority.isNotEmpty() && !authority.endsWith(':'))
    val portSuffix = authority.substringAfterLast(']', authority).substringAfter(':', "")
    require(portSuffix.isEmpty() || (portSuffix.all { it in '0'..'9' } &&
        portSuffix.toIntOrNull()?.let { it in 1..65535 } == true))
    val url = Url(value)
    require(url.host.isNotBlank())
    require(url.port in 1..65535)
    require(url.user.isNullOrEmpty() && url.password.isNullOrEmpty() && url.fragment.isEmpty())
    require(url.parameters.isEmpty() && url.encodedPath in setOf("", "/"))
    require(url.protocol == URLProtocol.HTTPS ||
        (allowInsecureLoopback && url.protocol == URLProtocol.HTTP &&
            url.host.lowercase().removeSurrounding("[", "]") in setOf("127.0.0.1", "localhost", "::1")))
    return URLBuilder(protocol = url.protocol, host = url.host.lowercase(), port = url.port)
        .buildString().removeSuffix("/")
}

/** The client installs no logging plugin, cookie jar, auth plugin, or automatic retry plugin. */
class NativeHttpClient(
    engine: HttpClientEngine,
    origin: String,
    private val session: SessionManager,
    private val clockMs: () -> Long = { Clock.System.now().toEpochMilliseconds() },
    private val allowInsecureLoopback: Boolean = false,
    serverOrigin: () -> String = { origin },
) {
    /** The home origin fences leases and local state even after a server move. */
    val origin = canonicalOrigin(origin, allowInsecureLoopback)
    private val resolveServerOrigin = serverOrigin
    val serverOrigin: String get() = canonicalOrigin(resolveServerOrigin(), allowInsecureLoopback)
    private val client = HttpClient(engine) {
        expectSuccess = false
        followRedirects = false
        install(HttpTimeout) {
            connectTimeoutMillis = 15_000
            requestTimeoutMillis = 30_000
            socketTimeoutMillis = 45_000
        }
    }
    private var retryAtMillis = 0L
    private val rateLimitMutex = Mutex()

    init { session.attach(this) }

    suspend fun request(
        method: String,
        path: String,
        body: ByteArray? = null,
        lease: SessionLease? = session.lease(),
        idempotencyKey: String? = null,
        headers: Map<String, String> = emptyMap(),
        refreshOnForbidden: Boolean = true,
        bodyContentType: String = "application/json",
        preserveRejectionBody: Boolean = false,
        onUpload: (suspend (sent: Long, total: Long?) -> Unit)? = null,
    ): HttpOutcome = execute(method, path, body, lease, idempotencyKey, headers, refreshOnForbidden, false,
        bodyContentType, onUpload, preserveRejectionBody)

    internal suspend fun revoke(lease: SessionLease, targetOrigin: String = serverOrigin): HttpOutcome = execute(
        "POST", "/v1/auth/native/logout", "{}".encodeToByteArray(), lease, null, emptyMap(), false, true,
        pinnedOrigin = targetOrigin,
    )

    /** A deletion may revoke the originating lease before its admission response arrives. */
    internal suspend fun deleteAccount(lease: SessionLease, body: ByteArray, key: String): HttpOutcome = execute(
        "DELETE", "/v1/me", body, lease, key, emptyMap(), false, true,
    )

    /** Receipt authority is confined to this route and never becomes a person session. */
    internal suspend fun deletionStatus(deletionId: String, receiptToken: String): HttpOutcome {
        if (!validDeletionId(deletionId) || !validSessionCredential(receiptToken)) return HttpOutcome.RejectedRequest
        rateLimit()?.let { return it }
        return try {
            val response = client.request("$serverOrigin/v1/account-deletions/$deletionId") {
                method = HttpMethod.Get
                headers.append("Authorization", "Bearer $receiptToken")
            }
            val headers = response.headers.entries().associate { it.key.lowercase() to it.value.joinToString(",") }
            outcome(response.status.value, response.bodyAsBytes(), headers, null, false)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            HttpOutcome.NetworkFailure
        }
    }

    private suspend fun execute(
        method: String,
        path: String,
        body: ByteArray?,
        lease: SessionLease?,
        idempotencyKey: String?,
        headers: Map<String, String>,
        refreshOnForbidden: Boolean,
        detached: Boolean,
        bodyContentType: String = "application/json",
        uploadProgress: (suspend (sent: Long, total: Long?) -> Unit)? = null,
        preserveRejectionBody: Boolean = false,
        pinnedOrigin: String? = null,
    ): HttpOutcome {
        val targetOrigin = pinnedOrigin ?: serverOrigin
        validate(targetOrigin, path, lease, headers, idempotencyKey, detached)?.let { return it }
        if (bodyContentType.isBlank() || bodyContentType.any(Char::isISOControl)) return HttpOutcome.RejectedRequest
        rateLimit()?.let { return it }
        return try {
            val response = client.request(targetOrigin + path) {
                this.method = HttpMethod.parse(method)
                if (lease != null) {
                    this.headers.append("Authorization", "Bearer ${lease.credential}")
                    this.headers.append("X-Expected-User", lease.namespace.userId)
                }
                idempotencyKey?.let { this.headers.append("Idempotency-Key", it) }
                for ((name, value) in headers) this.headers.append(name, value)
                if (body != null) {
                    this.headers.append("Content-Type", bodyContentType)
                    setBody(body)
                    uploadProgress?.let { progress -> onUpload { sent, total -> progress(sent, total) } }
                }
            }
            val responseHeaders = response.headers.entries().associate { it.key.lowercase() to it.value.joinToString(",") }
            val bytes = response.bodyAsBytes()
            outcome(response.status.value, bytes, responseHeaders, if (detached) null else lease,
                refreshOnForbidden, preserveRejectionBody)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            if (!detached && lease != null && !session.isCurrent(lease)) HttpOutcome.StaleSession else HttpOutcome.NetworkFailure
        }
    }

    /** The callback owns the response only during this call; consumers apply their own idle timer. */
    suspend fun stream(
        path: String,
        lease: SessionLease,
        headers: Map<String, String> = emptyMap(),
        method: String = "GET",
        body: ByteArray? = null,
        onResponse: suspend (status: Int, headers: Map<String, String>, channel: ByteReadChannel) -> Unit,
    ): HttpOutcome {
        val targetOrigin = serverOrigin
        validate(targetOrigin, path, lease, headers, null)?.let { return it }
        rateLimit()?.let { return it }
        return try {
            client.prepareRequest(targetOrigin + path) {
                this.method = HttpMethod.parse(method)
                this.headers.append("Authorization", "Bearer ${lease.credential}")
                this.headers.append("X-Expected-User", lease.namespace.userId)
                for ((name, value) in headers) this.headers.append(name, value)
                if (body != null) {
                    this.headers.append("Content-Type", "application/json")
                    setBody(body)
                }
                timeout { requestTimeoutMillis = HttpTimeoutConfig.INFINITE_TIMEOUT_MS }
            }.execute { response ->
                val responseHeaders = response.headers.entries().associate { it.key.lowercase() to it.value.joinToString(",") }
                val status = response.status.value
                if (status !in 200..299) {
                    outcome(status, response.bodyAsBytes(), responseHeaders, lease, true)
                } else if (!session.isCurrent(lease)) {
                    HttpOutcome.StaleSession
                } else {
                    try {
                        onResponse(status, responseHeaders, response.bodyAsChannel())
                    } catch (error: Throwable) {
                        throw StreamConsumerFailure(error)
                    }
                    if (session.isCurrent(lease)) HttpOutcome.Success(status, byteArrayOf(), responseHeaders)
                    else HttpOutcome.StaleSession
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (consumer: StreamConsumerFailure) {
            throw consumer.failure
        } catch (_: Exception) {
            if (!session.isCurrent(lease)) HttpOutcome.StaleSession else HttpOutcome.NetworkFailure
        }
    }

    fun close() { client.close() }

    private fun validate(
        targetOrigin: String,
        path: String,
        lease: SessionLease?,
        headers: Map<String, String>,
        key: String?,
        detached: Boolean = false,
    ): HttpOutcome? {
        if (!path.startsWith('/') || path.startsWith("//") || path.any { it == '\\' || it.isISOControl() } || '#' in path)
            return HttpOutcome.RejectedOrigin
        val target = runCatching { Url(targetOrigin + path) }.getOrNull() ?: return HttpOutcome.RejectedOrigin
        val base = Url(targetOrigin)
        if (target.protocol != base.protocol || target.host != base.host || target.port != base.port || !target.user.isNullOrEmpty())
            return HttpOutcome.RejectedOrigin
        if (lease != null && (lease.namespace.origin != origin || (!detached && !session.isCurrent(lease)))) return HttpOutcome.StaleSession
        if (headers.any { (name, value) -> name.lowercase() in reservedHeaders || name.any { it.isISOControl() } || value.any { it.isISOControl() } })
            return HttpOutcome.RejectedRequest
        if (key != null && (key.isBlank() || key.any { it.isISOControl() })) return HttpOutcome.RejectedRequest
        return null
    }

    private suspend fun outcome(
        status: Int,
        body: ByteArray,
        headers: Map<String, String>,
        lease: SessionLease?,
        refreshOnForbidden: Boolean,
        preserveRejectionBody: Boolean = false,
    ): HttpOutcome {
        if (lease != null && !session.isCurrent(lease)) return HttpOutcome.StaleSession
        return when (status) {
            in 200..299 -> HttpOutcome.Success(status, body, headers)
            401 -> {
                if (lease != null) session.expire(lease)
                HttpOutcome.Unauthorized
            }
            403 -> {
                if (lease != null && refreshOnForbidden) session.refreshIdentity(lease)
                if (lease != null && !session.isCurrent(lease)) HttpOutcome.StaleSession
                else if (preserveRejectionBody) HttpOutcome.Failure(status, body, headers)
                else HttpOutcome.Forbidden
            }
            409 -> HttpOutcome.Conflict(body)
            429 -> {
                rateLimitMutex.withLock {
                    retryAtMillis = maxOf(retryAtMillis, retryAfterDeadline(headers["retry-after"], clockMs()))
                    if (preserveRejectionBody) HttpOutcome.Failure(status, body, headers)
                    else HttpOutcome.RateLimited(retryAtMillis)
                }
            }
            else -> HttpOutcome.Failure(status, body, if (preserveRejectionBody) headers else emptyMap())
        }
    }

    private suspend fun rateLimit(): HttpOutcome.RateLimited? = rateLimitMutex.withLock {
        if (clockMs() < retryAtMillis) HttpOutcome.RateLimited(retryAtMillis) else null
    }

    private companion object {
        val reservedHeaders = setOf("authorization", "proxy-authorization", "cookie", "host", "origin", "x-expected-user", "idempotency-key")
    }
}

private class StreamConsumerFailure(val failure: Throwable) : Exception("Event consumer failed")

/** Retry-After supports both delta-seconds and the HTTP IMF-fixdate wire format. */
internal fun retryAfterDeadline(value: String?, now: Long): Long {
    val seconds = value?.trim()?.toLongOrNull()
    if (seconds != null && seconds >= 0) return now + seconds.coerceAtMost((Long.MAX_VALUE - now) / 1000) * 1000
    val parsed = runCatching {
        val parts = requireNotNull(value).trim().split(' ').filter { it.isNotEmpty() }
        require(parts.size == 6 && parts[5] == "GMT")
        val month = listOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec").indexOf(parts[2]) + 1
        val time = parts[4].split(':').map(String::toInt)
        require(time.size == 3 && month > 0)
        LocalDateTime(parts[3].toInt(), month, parts[1].toInt(), time[0], time[1], time[2])
            .toInstant(TimeZone.UTC).toEpochMilliseconds()
    }.getOrNull()
    return maxOf(now, parsed ?: (now + 1000))
}
