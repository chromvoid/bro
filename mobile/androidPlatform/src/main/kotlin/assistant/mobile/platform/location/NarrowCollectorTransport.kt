package assistant.mobile.platform.location

import assistant.mobile.core.canonicalOrigin
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.JsonObject
import okhttp3.Call
import okhttp3.Callback
import okhttp3.CookieJar
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.Response
import okio.BufferedSink

data class CollectorReply(val status: Int, val body: String) {
    override fun toString(): String = "CollectorReply(status=$status, private=redacted)"
}

fun interface CollectorTransport {
    suspend fun post(path: String, body: JsonObject, token: String): CollectorReply
}

/** Independent transport: only collector endpoints, no cookie jar/cache/redirects or human credential. */
class NarrowCollectorTransport(origin: String,
    private val serverOrigin: () -> String = { origin }) : CollectorTransport {

    override suspend fun post(path: String, body: JsonObject, token: String): CollectorReply = suspendCancellableCoroutine { continuation ->
        require(path == NativeLocationCollector.POINTS || path == NativeLocationCollector.REVOKE)
        require(token.isNotBlank() && token.none { it == '\r' || it == '\n' })
        val bytes = body.toString().encodeToByteArray()
        val request = Request.Builder().url(canonicalOrigin(serverOrigin(), allowInsecureLoopback = true) + path)
            .header("Authorization", "Bearer $token")
            .header("Accept", "application/json")
            .post(object : RequestBody() {
                override fun contentType() = "application/json".toMediaType()
                override fun contentLength() = bytes.size.toLong()
                override fun isOneShot() = true
                override fun writeTo(sink: BufferedSink) { sink.write(bytes) }
            }).build()
        val connection = (if (path != NativeLocationCollector.REVOKE) client else client.newBuilder()
            .connectTimeout(3, TimeUnit.SECONDS).readTimeout(3, TimeUnit.SECONDS).build()).newCall(request)
        continuation.invokeOnCancellation { connection.cancel() }
        connection.enqueue(object : Callback {
            override fun onFailure(call: Call, failure: IOException) {
                continuation.resumeWithException(failure)
            }

            override fun onResponse(call: Call, response: Response) {
                try {
                    val reply = response.use {
                        val status = response.code
                        check(status !in 300..399) { "Location redirect rejected" }
                        val value = response.body?.byteStream()?.use { stream ->
                            val output = ByteArrayOutputStream()
                            val chunk = ByteArray(4096)
                            while (true) {
                                val count = stream.read(chunk)
                                if (count < 0) break
                                check(output.size() + count <= MAX_REPLY) { "Location reply is too large" }
                                output.write(chunk, 0, count)
                            }
                            output.toString("UTF-8")
                        }.orEmpty()
                        CollectorReply(status, value)
                    }
                    continuation.resume(reply)
                } catch (failure: Exception) {
                    continuation.resumeWithException(failure)
                }
            }
        })
    }

    private companion object {
        const val MAX_REPLY = 1_048_576
        val client = OkHttpClient.Builder()
            .followRedirects(false)
            .followSslRedirects(false)
            .retryOnConnectionFailure(false)
            .cookieJar(CookieJar.NO_COOKIES)
            .cache(null)
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .writeTimeout(0, TimeUnit.MILLISECONDS)
            .build()
    }
}
