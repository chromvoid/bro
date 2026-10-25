package assistant.mobile.platform.collector

import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import okhttp3.Call
import okhttp3.Callback
import okhttp3.CookieJar
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.Response
import okio.BufferedSink

/** A source-bound authority, separate from the foreground native person session. */
data class NativeCollectorGrant(val value: JsonObject) {
    val user get() = requireNotNull(value.string("user_id"))
    val source get() = requireNotNull(value.string("source_id"))
    val token get() = requireNotNull(value.string("token"))
    val identity get() = requireNotNull(value.string("collector_id"))
    override fun toString() = "NativeCollectorGrant(redacted)"
}

class NativeCollectorFailure(val code: String) : Exception(code)

data class NativeCollectorReply(val status: Int, val value: JsonObject) {
    override fun toString() = "NativeCollectorReply(status=$status, body=redacted)"
}

/** Ring and WHOOP share their native enrollment and narrow collector ingest contract. */
class NativeCollectorHttp(origin: String, private val profile: String,
    private val serverOrigin: () -> String = { origin }) {
    /** No redirects, cookie jar, disk cache, automatic retry or credential/health-data logging. */
    suspend fun request(method: String, path: String, body: JsonObject? = null,
        token: String? = null, headers: Map<String, String> = emptyMap(),
        received: ((NativeCollectorReply) -> Unit)? = null, timeoutMs: Int? = null): NativeCollectorReply = suspendCancellableCoroutine { continuation ->
        require((path == "/v1/me" || path.startsWith("/v1/personal/rings/")) && !path.contains(".."))
        val bytes = body?.toString()?.toByteArray(Charsets.UTF_8)
        val requestBody = bytes?.let { payload ->
            object : RequestBody() {
                override fun contentType() = "application/json".toMediaType()
                override fun contentLength() = payload.size.toLong()
                override fun isOneShot() = true
                override fun writeTo(sink: BufferedSink) { sink.write(payload) }
            }
        }
        val request = Request.Builder().url(serverOrigin() + path).apply {
            header("Accept", "application/json")
            token?.let { header("Authorization", "Bearer $it") }
            headers.forEach { (key, value) -> header(key, value) }
            method(method, requestBody)
        }.build()
        val call = (if (timeoutMs == null) client else client.newBuilder()
            .connectTimeout(timeoutMs.toLong(), TimeUnit.MILLISECONDS)
            .readTimeout(timeoutMs.toLong(), TimeUnit.MILLISECONDS).build()).newCall(request)
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, failure: IOException) {
                continuation.resumeWithException(failure)
            }

            override fun onResponse(call: Call, response: Response) {
                try {
                    val reply = response.use {
                        val status = response.code
                        if (status in 300..399) throw NativeCollectorFailure("redirect_rejected")
                        val responseBytes = response.body?.byteStream()?.use { stream ->
                            val output = java.io.ByteArrayOutputStream()
                            val chunk = ByteArray(4096)
                            while (true) {
                                val count = stream.read(chunk)
                                if (count < 0) break
                                if (output.size() + count > 1_048_576) throw NativeCollectorFailure("response_too_large")
                                output.write(chunk, 0, count)
                            }
                            output.toByteArray()
                        } ?: ByteArray(0)
                        val value = if (responseBytes.isEmpty() && status !in 200..299) buildJsonObject {} else try {
                            json.parseToJsonElement(responseBytes.toString(Charsets.UTF_8)) as JsonObject
                        } catch (_: Exception) {
                            // A denial may intentionally have an empty or non-JSON body; status remains authoritative.
                            if (status !in 200..299) buildJsonObject {} else throw NativeCollectorFailure("invalid_response")
                        }
                        NativeCollectorReply(status, value).also { received?.invoke(it) }
                    }
                    continuation.resume(reply)
                } catch (failure: Exception) {
                    continuation.resumeWithException(failure)
                }
            }
        })
    }

    suspend fun requirePerson(bearer: String, user: String, checkpoint: () -> Unit = {}) {
        checkpoint()
        val reply = request("GET", "/v1/me", token = bearer, received = { checkpoint() })
        checkpoint()
        if (reply.status != 200 || reply.value.string("id") != user) throw NativeCollectorFailure("not_authenticated")
    }

    suspend fun enroll(bearer: String, user: String, installation: String, name: String,
        checkpoint: () -> Unit = {}, issued: (NativeCollectorGrant) -> Unit = {}): NativeCollectorGrant {
        checkpoint()
        currentCoroutineContext().ensureActive()
        // Enrollment must capture its issued authority before the caller's cancellation cleanup runs.
        val result = withContext(NonCancellable) {
            request("POST", "/v1/personal/rings/enroll", buildJsonObject {
                put("installation_id", installation); put("name", name); put("profile", profile)
            }, token = bearer, headers = mapOf("X-Expected-User" to user), received = { reply ->
                if (reply.status in 200..299 && reply.value.string("user_id") == user && !reply.value.string("token").isNullOrEmpty()) {
                    issued(NativeCollectorGrant(reply.value))
                }
                checkpoint()
            })
        }
        currentCoroutineContext().ensureActive()
        checkpoint()
        if (result.status !in 200..299 || result.value.string("user_id") != user || result.value.string("token").isNullOrEmpty()) {
            throw NativeCollectorFailure("enrollment_failed")
        }
        return NativeCollectorGrant(result.value)
    }

    suspend fun state(grant: NativeCollectorGrant, checkpoint: () -> Unit = {}, timeoutMs: Int? = null): JsonObject {
        checkpoint()
        val result = request("GET", "/v1/personal/rings/collector/state", token = grant.token,
            received = { checkpoint() }, timeoutMs = timeoutMs)
        checkpoint()
        if (result.status in setOf(401, 403, 404)) throw NativeCollectorFailure("grant_revoked")
        if (result.status != 200 || result.value.string("source_id") != grant.source ||
            result.value.string("collector_id") != grant.identity || result.value["generation"] != grant.value["generation"]) {
            throw NativeCollectorFailure("invalid_binding")
        }
        return result.value
    }

    suspend fun receipt(grant: NativeCollectorGrant, key: String): NativeCollectorReply {
        require(key.isNotEmpty() && key.all { it.isLetterOrDigit() || it == '-' })
        return request("GET", "/v1/personal/rings/collector/receipts/$key", token = grant.token)
    }

    suspend fun send(grant: NativeCollectorGrant, batch: JsonObject): NativeCollectorReply =
        request("POST", "/v1/personal/rings/collector/batches", batch["body"] as JsonObject,
            token = grant.token, headers = mapOf("Idempotency-Key" to requireNotNull(batch.string("key"))))

    suspend fun revoke(grant: NativeCollectorGrant) {
        val reply = request("POST", "/v1/personal/rings/collector/revoke", buildJsonObject {}, token = grant.token)
        if (reply.status !in 200..299 && reply.status !in setOf(401, 403, 404)) throw NativeCollectorFailure("revoke_failed")
    }

    private companion object {
        val json = Json { ignoreUnknownKeys = true }
        val client = OkHttpClient.Builder()
            .followRedirects(false)
            .followSslRedirects(false)
            .retryOnConnectionFailure(false)
            .cookieJar(CookieJar.NO_COOKIES)
            .cache(null)
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .writeTimeout(0, TimeUnit.MILLISECONDS)
            .addNetworkInterceptor { chain ->
                val response = chain.proceed(chain.request())
                // OkHttp otherwise replays HTTP 503 with Retry-After: 0, even with retries disabled.
                if (response.code == 503) response.newBuilder().removeHeader("Retry-After").build() else response
            }
            .build()
    }
}

private fun JsonObject.string(key: String) = (this[key] as? JsonPrimitive)?.contentOrNull
