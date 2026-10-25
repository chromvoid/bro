package assistant.mobile.core.photos

import assistant.mobile.core.HttpOutcome
import assistant.mobile.core.NativeHttpClient
import assistant.mobile.core.NativeJson
import assistant.mobile.core.SessionLease
import assistant.mobile.core.SessionManager
import io.ktor.http.Url
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

sealed interface PrivateImageResult {
    data class Success(val image: PrivateImage) : PrivateImageResult
    data class Failure(val outcome: HttpOutcome) : PrivateImageResult
}

@Serializable
data class UploadedConversationImage(
    val id: String,
    val width: Int,
    val height: Int,
    val url: String,
    @SerialName("preview_url") val previewUrl: String? = null,
)

@Serializable
data class UploadedTaskImage(val id: String, val width: Int, val height: Int)

enum class PrivateImageRoute(val uploadPath: String) {
    Conversation("/v1/conversation/images"),
    Tasks("/v1/tasks/images"),
}

/** Upload acknowledgements describe stored data; their URLs never grant navigation authority. */
fun decodeUploadedConversationImage(bytes: ByteArray, origin: String): UploadedConversationImage {
    val reply = NativeJson.decodeFromString<UploadedConversationImage>(bytes.decodeToString())
    require(validConversationImageId(reply.id))
    require(reply.width in 1..PHOTO_MAX_DIMENSION && reply.height in 1..PHOTO_MAX_DIMENSION)
    val path = imagePath(reply.id)
    require(privateImagePath(reply.url, origin, PrivateImageRoute.Conversation) == path)
    require(reply.previewUrl == null || privateImagePath(reply.previewUrl, origin, PrivateImageRoute.Conversation)?.substringBefore('?') == "$path/preview")
    return reply
}

/** Task upload receipts name stored pixels; a task operation attaches their descriptor later. */
fun decodeUploadedTaskImage(bytes: ByteArray): UploadedTaskImage {
    val reply = NativeJson.decodeFromString<UploadedTaskImage>(bytes.decodeToString())
    require(validConversationImageId(reply.id))
    require(reply.width in 1..PHOTO_MAX_DIMENSION && reply.height in 1..PHOTO_MAX_DIMENSION)
    return reply
}

/** Authenticated same-origin transport. Downloads are capped before accumulating response bytes. */
class PrivateConversationImages(
    scope: CoroutineScope,
    private val sessions: SessionManager,
    private val http: NativeHttpClient,
    val cache: PrivateImageCache = PrivateImageCache(),
    private val imageRoute: PrivateImageRoute = PrivateImageRoute.Conversation,
) : PhotoUploadTransport {
    private var boundLease = sessions.lease()
    private val sessionJob: Job

    init {
        cache.bind(boundLease?.namespace)
        sessionJob = scope.launch {
            sessions.leases.collect { lease ->
                if (lease !== boundLease) {
                    cache.clear()
                    cache.bind(lease?.namespace)
                    boundLease = lease
                }
            }
        }
    }

    override suspend fun upload(photo: NormalizedPhoto, lease: SessionLease, progress: (Long, Long?) -> Unit): String {
        requireCurrent(lease)
        val outcome = http.request("POST", imageRoute.uploadPath, photo.jpeg, lease,
            headers = mapOf("Accept" to "application/json"), bodyContentType = "image/jpeg",
            onUpload = { sent, total ->
                requireCurrent(lease)
                progress(sent, total)
            })
        if (outcome !is HttpOutcome.Success) throw PhotoFailure(outcome)
        if (outcome.status !in setOf(200, 201) || outcome.body.size > 16 * 1024) throw PhotoFailure(HttpOutcome.InvalidResponse)
        val id = try {
            when (imageRoute) {
                PrivateImageRoute.Conversation -> decodeUploadedConversationImage(outcome.body, http.serverOrigin).id
                PrivateImageRoute.Tasks -> decodeUploadedTaskImage(outcome.body).id
            }
        }
            catch (_: Exception) { throw PhotoFailure(HttpOutcome.InvalidResponse) }
        requireCurrent(lease)
        cache.put(lease.namespace, "${imageRoute.uploadPath}/$id", PrivateImage(photo.jpeg, "image/jpeg"))
        return id
    }

    /** Fresh uploads can be previewed before a task exists; this entry point never downloads. */
    fun uploadedImage(id: String): PrivateImage? {
        if (imageRoute != PrivateImageRoute.Tasks || !validConversationImageId(id)) return null
        val lease = sessions.lease() ?: return null
        if (boundLease !== lease) {
            cache.clear()
            cache.bind(lease.namespace)
            boundLease = lease
        }
        return cache.get(lease.namespace, "${imageRoute.uploadPath}/$id")
    }

    /** The history descriptor's URL is not used: an id can only name the installation's route. */
    suspend fun image(id: String, preview: Boolean = false): PrivateImageResult {
        if (imageRoute != PrivateImageRoute.Conversation || !validConversationImageId(id)) {
            return PrivateImageResult.Failure(HttpOutcome.RejectedRequest)
        }
        return load(imagePath(id) + if (preview) "/preview" else "")
    }

    /** Task access is checked by the server through the task, including shared task pictures. */
    suspend fun taskImage(taskId: String, id: String, preview: Boolean = false): PrivateImageResult {
        if (imageRoute != PrivateImageRoute.Tasks || !validConversationImageId(taskId) || !validConversationImageId(id)) {
            return PrivateImageResult.Failure(HttpOutcome.RejectedRequest)
        }
        return load("/v1/tasks/items/$taskId/images/$id" + if (preview) "/preview" else "")
    }

    /** Explicit URL entry point also refuses foreign origins, fragments, credentials and other routes. */
    suspend fun imageAt(url: String): PrivateImageResult {
        val path = privateImagePath(url, http.serverOrigin, imageRoute) ?: return PrivateImageResult.Failure(HttpOutcome.RejectedOrigin)
        return load(path)
    }

    fun clear() { cache.clear() }

    fun close() {
        sessionJob.cancel()
        clear()
    }

    private suspend fun load(path: String): PrivateImageResult {
        val lease = sessions.lease() ?: return PrivateImageResult.Failure(HttpOutcome.Unauthorized)
        // A new lease can arrive before the flow collector gets its turn.
        if (boundLease !== lease) {
            cache.clear()
            cache.bind(lease.namespace)
            boundLease = lease
        }
        cache.get(lease.namespace, path)?.let { return PrivateImageResult.Success(it) }
        var image: PrivateImage? = null
        return try {
            val result = http.stream(path, lease, mapOf("Accept" to "image/jpeg, image/webp, image/png")) { status, headers, channel ->
                if (status != 200) throw PhotoFailure(HttpOutcome.InvalidResponse)
                val type = headers["content-type"]?.substringBefore(';')?.trim()?.lowercase()
                if (type !in setOf("image/jpeg", "image/webp", "image/png")) throw PhotoFailure(HttpOutcome.InvalidResponse)
                val length = headers["content-length"]?.toLongOrNull()
                if (length != null && (length <= 0 || length > PHOTO_MAX_BYTES)) throw PhotoFailure(HttpOutcome.InvalidResponse)
                val chunks = mutableListOf<ByteArray>()
                val buffer = ByteArray(16 * 1024)
                var count = 0
                while (true) {
                    requireCurrent(lease)
                    val read = channel.readAvailable(buffer)
                    if (read == -1) break
                    if (read == 0) continue
                    if (read > PHOTO_MAX_BYTES - count) throw PhotoFailure(HttpOutcome.InvalidResponse)
                    chunks += buffer.copyOf(read)
                    count += read
                }
                if (count == 0 || (length != null && length != count.toLong())) throw PhotoFailure(HttpOutcome.InvalidResponse)
                val bytes = ByteArray(count)
                var offset = 0
                chunks.forEach { chunk -> chunk.copyInto(bytes, offset); offset += chunk.size }
                if (!imageSignatureMatches(bytes, type!!)) throw PhotoFailure(HttpOutcome.InvalidResponse)
                image = PrivateImage(bytes, type)
            }
            if (result !is HttpOutcome.Success) PrivateImageResult.Failure(result)
            else {
                requireCurrent(lease)
                val received = image ?: throw PhotoFailure(HttpOutcome.InvalidResponse)
                cache.put(lease.namespace, path, received)
                PrivateImageResult.Success(received)
            }
        } catch (cancelled: CancellationException) { throw cancelled
        } catch (failure: PhotoFailure) { PrivateImageResult.Failure(failure.outcome)
        } catch (_: Exception) { PrivateImageResult.Failure(HttpOutcome.NetworkFailure) }
    }

    private fun requireCurrent(lease: SessionLease) {
        if (!sessions.isCurrent(lease)) throw PhotoFailure(HttpOutcome.StaleSession)
    }
}

private const val IMAGES_PATH = "/v1/conversation/images"
private fun imagePath(id: String): String = "$IMAGES_PATH/$id"

private fun privateImagePath(value: String, origin: String, imageRoute: PrivateImageRoute): String? = runCatching {
    require(value.isNotBlank() && value.none { it.isISOControl() || it == '\\' } && '#' !in value)
    require(!value.startsWith("//"))
    val url = Url(if (value.startsWith('/')) origin + value else value)
    val base = Url(origin)
    require(url.protocol == base.protocol && url.host == base.host && url.port == base.port)
    require(url.user.isNullOrEmpty() && url.password.isNullOrEmpty())
    when (imageRoute) {
        PrivateImageRoute.Conversation -> {
            val rest = url.encodedPath.removePrefix("$IMAGES_PATH/")
            require(url.encodedPath.startsWith("$IMAGES_PATH/") && validConversationImageId(rest.substringBefore('/')))
            require(rest.substringAfter('/', "") in setOf("", "preview"))
        }
        PrivateImageRoute.Tasks -> {
            val prefix = "/v1/tasks/items/"
            require(url.encodedPath.startsWith(prefix))
            val parts = url.encodedPath.removePrefix(prefix).split('/')
            require(parts.size == 3 || parts.size == 4)
            require(validConversationImageId(parts[0]) && parts[1] == "images" && validConversationImageId(parts[2]))
            require(parts.size == 3 || parts[3] == "preview")
        }
    }
    require(url.parameters.names().all { it == "v" } && url.parameters.getAll("v").orEmpty().size <= 1)
    url.encodedPath + url.encodedQuery.takeIf { it.isNotEmpty() }?.let { "?$it" }.orEmpty()
}.getOrNull()

private fun imageSignatureMatches(bytes: ByteArray, type: String): Boolean = when (type) {
    "image/jpeg" -> bytes.size >= 3 && bytes[0] == 0xff.toByte() && bytes[1] == 0xd8.toByte() && bytes[2] == 0xff.toByte()
    "image/png" -> bytes.size >= 8 && bytes.take(8) == listOf(137, 80, 78, 71, 13, 10, 26, 10).map(Int::toByte)
    "image/webp" -> bytes.size >= 12 && bytes.copyOfRange(0, 4).decodeToString() == "RIFF" && bytes.copyOfRange(8, 12).decodeToString() == "WEBP"
    else -> false
}
