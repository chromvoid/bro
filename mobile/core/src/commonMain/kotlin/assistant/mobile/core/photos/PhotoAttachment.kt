package assistant.mobile.core.photos

import assistant.mobile.core.HttpOutcome
import assistant.mobile.core.AccountNamespace
import assistant.mobile.core.SessionLease
import assistant.mobile.core.SessionManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

const val PHOTO_MAX_BYTES = 10 * 1024 * 1024
const val PHOTO_MAX_DIMENSION = 1600

/** Fresh, oriented, opaque JPEG pixels from the platform decoder; never a source URI or EXIF. */
data class NormalizedPhoto(val jpeg: ByteArray, val width: Int, val height: Int) {
    init {
        require(jpeg.size in 3..PHOTO_MAX_BYTES)
        require(jpeg[0] == 0xff.toByte() && jpeg[1] == 0xd8.toByte() && jpeg[2] == 0xff.toByte())
        require(width in 1..PHOTO_MAX_DIMENSION && height in 1..PHOTO_MAX_DIMENSION)
    }
    override fun toString(): String = "NormalizedPhoto(width=$width, height=$height, jpeg=redacted)"
}

interface PhotoUploadTransport {
    suspend fun upload(photo: NormalizedPhoto, lease: SessionLease, progress: (Long, Long?) -> Unit): String
}

class PhotoFailure(val outcome: HttpOutcome) : Exception("Photo request failed")

enum class PhotoAttachmentStatus { Empty, Preparing, Uploading, Ready, Error }

data class PhotoAttachmentState(
    val selection: Long = 0,
    val status: PhotoAttachmentStatus = PhotoAttachmentStatus.Empty,
    val photo: NormalizedPhoto? = null,
    val imageId: String? = null,
    val uploadedBytes: Long = 0,
    val totalBytes: Long? = null,
    val error: HttpOutcome? = null,
    val namespace: AccountNamespace? = null,
) {
    val present: Boolean get() = status != PhotoAttachmentStatus.Empty
}

/** Photo state is independent of caption/dictation state. Only explicit send claims its image id. */
class PhotoAttachmentController(
    private val scope: CoroutineScope,
    private val sessions: SessionManager,
    private val transport: PhotoUploadTransport,
) {
    private val mutableState = MutableStateFlow(PhotoAttachmentState())
    val state: StateFlow<PhotoAttachmentState> = mutableState.asStateFlow()
    private var upload: Deferred<String?>? = null
    private var preparation: Job? = null
    private var epoch = 0L
    private var owner: SessionLease? = sessions.lease()
    private val sessionJob: Job

    init {
        sessionJob = scope.launch {
            sessions.leases.collect { lease ->
                if (owner !== lease) {
                    owner = lease
                    cancel()
                }
            }
        }
    }

    /** Capture before opening a picker/decoder, so late results cannot replace a newer choice. */
    fun beginSelection(): Long {
        preparation?.cancel()
        preparation = null
        upload?.cancel()
        upload = null
        val selection = ++epoch
        owner = sessions.lease()
        mutableState.value = PhotoAttachmentState(selection, PhotoAttachmentStatus.Preparing, namespace = owner?.namespace)
        return selection
    }

    /** Decoding belongs to runtime, so leaving the Compose scene cannot strand a preparation. */
    fun prepare(source: String, normalizer: PhotoNormalizer): Long {
        val selection = beginSelection()
        preparation = scope.launch {
            try {
                selected(selection, normalizer.normalize(source))
            } catch (cancelled: CancellationException) { throw cancelled
            } catch (failure: PhotoFailure) { preparationFailed(selection, failure.outcome)
            } catch (_: Exception) { preparationFailed(selection) }
        }
        return selection
    }

    fun selected(selection: Long, photo: NormalizedPhoto) {
        val lease = owner ?: return
        if (!current(selection, lease) || state.value.status != PhotoAttachmentStatus.Preparing) return
        mutableState.value = PhotoAttachmentState(selection, PhotoAttachmentStatus.Uploading, photo,
            totalBytes = photo.jpeg.size.toLong(), namespace = lease.namespace)
        startUpload(selection, photo, lease)
    }

    fun preparationFailed(selection: Long, outcome: HttpOutcome = HttpOutcome.InvalidResponse) {
        if (epoch != selection) return
        mutableState.value = PhotoAttachmentState(selection, PhotoAttachmentStatus.Error, error = outcome, namespace = owner?.namespace)
    }

    fun retry() {
        val state = state.value
        val photo = state.photo ?: return
        val lease = owner ?: return
        if (state.status != PhotoAttachmentStatus.Error || !current(state.selection, lease)) return
        mutableState.value = state.copy(status = PhotoAttachmentStatus.Uploading, uploadedBytes = 0, error = null)
        startUpload(state.selection, photo, lease)
    }

    fun cancel() {
        preparation?.cancel()
        preparation = null
        upload?.cancel()
        upload = null
        mutableState.value = PhotoAttachmentState(selection = ++epoch)
    }

    fun close() {
        sessionJob.cancel()
        cancel()
    }

    /** Clear only the attachment captured by the send; a newer choice belongs to a new draft. */
    fun handoff(selection: Long) {
        if (state.value.selection == selection) cancel()
    }

    /** Runtime owns both admission and photo release even if navigation cancels its UI caller. */
    suspend fun sendWithAttachment(restoredImageId: String?, submit: suspend (String?) -> Boolean): Boolean =
        scope.async(start = CoroutineStart.UNDISPATCHED) {
            val chosen = state.value
            val lease = sessions.lease() ?: return@async false
            if (chosen.present && (chosen.status != PhotoAttachmentStatus.Ready || !current(chosen.selection, lease))) return@async false
            val admitted = submit(chosen.imageId ?: restoredImageId)
            if (admitted) handoff(chosen.selection)
            admitted
        }.await()

    /** Does not dispatch a conversation request or alter the text draft. */
    suspend fun readyImageId(): String? {
        val selection = state.value.selection
        val ready = state.value.imageId
        if (ready != null && owner?.let(sessions::isCurrent) == true) return ready
        val result = try { upload?.await() } catch (cancelled: CancellationException) {
            if (state.value.selection == selection) throw cancelled else null
        }
        return result.takeIf { state.value.selection == selection && state.value.status == PhotoAttachmentStatus.Ready }
    }

    private fun startUpload(selection: Long, photo: NormalizedPhoto, lease: SessionLease) {
        upload = scope.async(start = CoroutineStart.LAZY) {
            try {
                val id = transport.upload(photo, lease) { sent, total ->
                    if (current(selection, lease)) mutableState.value = state.value.copy(
                        uploadedBytes = sent.coerceIn(0, photo.jpeg.size.toLong()),
                        totalBytes = total?.takeIf { it > 0 } ?: photo.jpeg.size.toLong(),
                    )
                }
                require(validConversationImageId(id))
                if (!current(selection, lease)) return@async null
                mutableState.value = state.value.copy(status = PhotoAttachmentStatus.Ready, imageId = id,
                    uploadedBytes = photo.jpeg.size.toLong(), totalBytes = photo.jpeg.size.toLong(), error = null)
                id
            } catch (cancelled: CancellationException) { throw cancelled
            } catch (failure: PhotoFailure) {
                if (current(selection, lease)) mutableState.value = state.value.copy(status = PhotoAttachmentStatus.Error, error = failure.outcome)
                null
            } catch (_: Exception) {
                if (current(selection, lease)) mutableState.value = state.value.copy(status = PhotoAttachmentStatus.Error, error = HttpOutcome.InvalidResponse)
                null
            }
        }.also { it.start() }
    }

    private fun current(selection: Long, lease: SessionLease): Boolean =
        epoch == selection && owner === lease && sessions.isCurrent(lease)
}

/** Image identifiers are UUIDs generated by the storage contract, never arbitrary paths/URLs. */
fun validConversationImageId(value: String): Boolean = value.length == 36 && value.indices.all { index ->
    if (index in listOf(8, 13, 18, 23)) value[index] == '-'
    else value[index] in '0'..'9' || value[index] in 'a'..'f' || value[index] in 'A'..'F'
}
