package assistant.mobile.core.bodyassets

import assistant.mobile.core.HttpOutcome
import assistant.mobile.core.NativeHttpClient
import assistant.mobile.core.SessionLease
import assistant.mobile.core.SessionManager
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

enum class BodyAssetPhase { Checking, Downloading, Verifying, Ready }
data class BodyAssetProgress(
    val phase: BodyAssetPhase,
    val filename: String? = null,
    val completedBytes: Long = 0,
    val totalBytes: Long = 0,
    val cached: Boolean = false,
) {
    val fraction: Float get() = if (totalBytes > 0) (completedBytes.toDouble() / totalBytes).toFloat().coerceIn(0f, 1f) else 0f
}

interface BodyAssetSink {
    suspend fun write(bytes: ByteArray, count: Int)
    suspend fun finish(): BodyAssetVerification
    /** Exposes an atomic file only after the caller has checked its digest. */
    suspend fun commit()
    suspend fun discard()
}

/** Non-personal assets survive sign-out; only the current manifest's hashes remain on disk. */
interface BodyAssetStore {
    suspend fun readManifest(): ByteArray?
    suspend fun writeManifest(bytes: ByteArray)
    suspend fun contains(asset: BodyAsset): Boolean
    suspend fun begin(asset: BodyAsset): BodyAssetSink
    suspend fun retain(hashes: Set<String>)
}

/** Same-origin native transport owns authentication, session fencing, redirects and cancellation. */
class BodyAssetLoader(
    private val http: NativeHttpClient,
    private val sessions: SessionManager,
    private val store: BodyAssetStore,
) {
    suspend fun load(
        revalidateManifest: Boolean = true,
        onProgress: (BodyAssetProgress) -> Unit = {},
    ): BodyAssetManifest {
        val lease = sessions.lease() ?: throw BodyAssetFailure(BodyAssetError.Unauthorized)
        try {
            onProgress(BodyAssetProgress(BodyAssetPhase.Checking))
            val cachedBytes = store.readManifest()
            val cachedManifest = cachedBytes?.let { runCatching { decodeBodyAssetManifest(it) }.getOrNull() }
            val manifest = if (revalidateManifest || cachedManifest == null) {
                val reply = http.request("GET", BODY_ASSET_MANIFEST_PATH, lease = lease)
                requireCurrent(lease)
                when {
                    reply is HttpOutcome.Success && reply.status == 200 -> {
                        decodeBodyAssetManifest(reply.body).also { fresh ->
                            store.writeManifest(reply.body)
                            store.retain(fresh.hashes)
                        }
                    }
                    // An already verified complete set remains usable when the device is offline.
                    reply == HttpOutcome.NetworkFailure && cachedManifest != null && complete(cachedManifest) -> cachedManifest
                    else -> throw BodyAssetFailure(outcomeError(reply))
                }
            } else cachedManifest
            store.retain(manifest.hashes)
            var completed = 0L
            var allCached = true
            for (asset in manifest.files.values) {
                requireCurrent(lease)
                if (!store.contains(asset)) {
                    allCached = false
                    download(asset, lease, completed, manifest.totalBytes, onProgress)
                }
                completed += asset.bytes
                onProgress(BodyAssetProgress(BodyAssetPhase.Verifying, asset.name, completed, manifest.totalBytes, allCached))
            }
            requireCurrent(lease)
            onProgress(BodyAssetProgress(BodyAssetPhase.Ready, completedBytes = completed, totalBytes = manifest.totalBytes, cached = allCached))
            return manifest
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (error: BodyAssetFailure) { throw error }
        catch (_: Exception) { throw BodyAssetFailure(BodyAssetError.Storage) }
    }

    private suspend fun complete(manifest: BodyAssetManifest): Boolean {
        for (asset in manifest.files.values) if (!store.contains(asset)) return false
        return true
    }

    private suspend fun download(
        asset: BodyAsset,
        lease: SessionLease,
        completed: Long,
        total: Long,
        onProgress: (BodyAssetProgress) -> Unit,
    ) {
        val target = store.begin(asset)
        try {
            var downloaded = 0L
            onProgress(BodyAssetProgress(BodyAssetPhase.Downloading, asset.name, completed, total))
            val reply = http.stream(asset.path, lease, headers = mapOf("Accept-Encoding" to "identity")) { status, headers, channel ->
                if (status != 200) throw BodyAssetFailure(BodyAssetError.Network)
                headers["content-length"]?.let {
                    if (it.toLongOrNull() != asset.bytes) throw BodyAssetFailure(BodyAssetError.Integrity)
                }
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val count = channel.readAvailable(buffer, 0, buffer.size)
                    if (count < 0) break
                    if (count == 0) continue
                    requireCurrent(lease)
                    downloaded += count
                    if (downloaded > asset.bytes) throw BodyAssetFailure(BodyAssetError.Integrity)
                    target.write(buffer, count)
                    onProgress(BodyAssetProgress(BodyAssetPhase.Downloading, asset.name, completed + downloaded, total))
                }
            }
            if (reply !is HttpOutcome.Success) throw BodyAssetFailure(outcomeError(reply))
            if (downloaded != asset.bytes) throw BodyAssetFailure(BodyAssetError.Integrity)
            onProgress(BodyAssetProgress(BodyAssetPhase.Verifying, asset.name, completed + downloaded, total))
            verifyBodyAssetHash(asset, target.finish())
            requireCurrent(lease)
            target.commit()
        } finally {
            withContext(NonCancellable) { target.discard() }
        }
    }

    private fun requireCurrent(lease: SessionLease) {
        if (!sessions.isCurrent(lease)) throw BodyAssetFailure(BodyAssetError.Unauthorized)
    }
    private fun outcomeError(reply: HttpOutcome): BodyAssetError = when (reply) {
        HttpOutcome.Unauthorized, HttpOutcome.StaleSession -> BodyAssetError.Unauthorized
        HttpOutcome.Forbidden -> BodyAssetError.Forbidden
        is HttpOutcome.Success, HttpOutcome.InvalidResponse, HttpOutcome.RejectedOrigin, HttpOutcome.RejectedRequest -> BodyAssetError.InvalidManifest
        else -> BodyAssetError.Network
    }
}
