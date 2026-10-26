package assistant.mobile.core.bodyassets

import assistant.mobile.core.HttpOutcome
import assistant.mobile.core.NativeHttpClient
import assistant.mobile.core.SessionLease
import assistant.mobile.core.SessionManager
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
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
    val bytes: Long
    suspend fun write(bytes: ByteArray, count: Int)
    suspend fun reset()
    suspend fun finish(): BodyAssetVerification
    /** Exposes an atomic file only after the caller has checked its digest. */
    suspend fun commit()
    /** Closes the writer while preserving the partial file for another attempt. */
    suspend fun close()
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
        names: Set<String>,
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
                        decodeBodyAssetManifest(reply.body).also {
                            store.writeManifest(reply.body)
                        }
                    }
                    // An already verified complete set remains usable when the device is offline.
                    reply == HttpOutcome.NetworkFailure && cachedManifest != null && complete(cachedManifest, names) -> cachedManifest
                    else -> throw BodyAssetFailure(outcomeError(reply))
                }
            } else cachedManifest
            val required = requiredAssets(manifest, names)
            val total = required.sumOf { it.bytes }
            store.retain(manifest.hashes)
            var completed = 0L
            var allCached = true
            for (asset in required) {
                requireCurrent(lease)
                if (!store.contains(asset)) {
                    allCached = false
                    download(asset, lease, completed, total, onProgress)
                }
                completed += asset.bytes
                onProgress(BodyAssetProgress(BodyAssetPhase.Verifying, asset.name, completed, total, allCached))
            }
            requireCurrent(lease)
            onProgress(BodyAssetProgress(BodyAssetPhase.Ready, completedBytes = completed, totalBytes = total, cached = allCached))
            return manifest
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (error: BodyAssetFailure) { throw error }
        catch (_: Exception) { throw BodyAssetFailure(BodyAssetError.Storage) }
    }

    private fun requiredAssets(manifest: BodyAssetManifest, names: Set<String>): List<BodyAsset> {
        if (names.isEmpty()) throw BodyAssetFailure(BodyAssetError.InvalidManifest)
        return names.map { manifest.files[it] ?: throw BodyAssetFailure(BodyAssetError.InvalidManifest) }
    }

    private suspend fun complete(manifest: BodyAssetManifest, names: Set<String>): Boolean {
        for (asset in requiredAssets(manifest, names)) if (!store.contains(asset)) return false
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
            if (target.bytes > asset.bytes) target.reset()
            var reported = target.bytes
            var retries = 0
            onProgress(BodyAssetProgress(BodyAssetPhase.Downloading, asset.name, completed + target.bytes, total))
            while (target.bytes < asset.bytes) {
                requireCurrent(lease)
                val offset = target.bytes
                var restart = false
                try {
                    val requestHeaders = buildMap {
                        put("Accept-Encoding", "identity")
                        if (offset > 0) put("Range", "bytes=$offset-")
                    }
                    val reply = http.stream(asset.path, lease, headers = requestHeaders) { status, headers, channel ->
                        when (status) {
                            200 -> if (offset > 0) { target.reset(); reported = 0 }
                            206 -> if (headers["content-range"] != "bytes $offset-${asset.bytes - 1}/${asset.bytes}") {
                                restart = true
                                return@stream
                            }
                            else -> throw BodyAssetFailure(BodyAssetError.Network)
                        }
                        headers["content-length"]?.let {
                            if (it.toLongOrNull() != asset.bytes - target.bytes) throw BodyAssetFailure(BodyAssetError.Integrity)
                        }
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            // A channel failure is a network error; disk failures must not be retried.
                            val count = try { channel.readAvailable(buffer, 0, buffer.size) }
                            catch (cancelled: CancellationException) { throw cancelled }
                            catch (_: Exception) { throw BodyAssetFailure(BodyAssetError.Network) }
                            if (count < 0) {
                                // Ktor can report EOF for a channel closed with an error.
                                if (channel.closedCause != null) throw BodyAssetFailure(BodyAssetError.Network)
                                break
                            }
                            if (count == 0) continue
                            requireCurrent(lease)
                            if (target.bytes + count > asset.bytes) throw BodyAssetFailure(BodyAssetError.Integrity)
                            target.write(buffer, count)
                            if (target.bytes - reported >= 256 * 1024) {
                                reported = target.bytes
                                onProgress(BodyAssetProgress(BodyAssetPhase.Downloading, asset.name, completed + reported, total))
                            }
                        }
                    }
                    requireCurrent(lease)
                    if (restart || reply is HttpOutcome.Failure && reply.status == 416) {
                        target.reset()
                        reported = 0
                        if (offset == 0L) throw BodyAssetFailure(BodyAssetError.Integrity)
                        continue
                    }
                    if (reply !is HttpOutcome.Success) throw BodyAssetFailure(outcomeError(reply))
                    if (target.bytes != asset.bytes) throw BodyAssetFailure(BodyAssetError.Integrity)
                } catch (error: BodyAssetFailure) {
                    if (error.error != BodyAssetError.Network || retries == 3) throw error
                    requireCurrent(lease)
                    delay(1000L shl retries)
                    retries++
                }
            }
            onProgress(BodyAssetProgress(BodyAssetPhase.Verifying, asset.name, completed + target.bytes, total))
            verifyBodyAssetHash(asset, target.finish())
            requireCurrent(lease)
            target.commit()
        } catch (error: BodyAssetFailure) {
            if (error.error == BodyAssetError.Integrity) withContext(NonCancellable) { target.discard() }
            throw error
        } finally {
            withContext(NonCancellable) { target.close() }
        }
    }

    private fun requireCurrent(lease: SessionLease) {
        if (!sessions.isCurrent(lease)) throw BodyAssetFailure(BodyAssetError.Unauthorized)
    }
    private fun outcomeError(reply: HttpOutcome): BodyAssetError = when (reply) {
        HttpOutcome.Unauthorized, HttpOutcome.StaleSession -> BodyAssetError.Unauthorized
        HttpOutcome.Forbidden -> BodyAssetError.Forbidden
        is HttpOutcome.Failure -> when (reply.status) {
            401 -> BodyAssetError.Unauthorized
            403 -> BodyAssetError.Forbidden
            else -> BodyAssetError.Network
        }
        is HttpOutcome.Success, HttpOutcome.InvalidResponse, HttpOutcome.RejectedOrigin, HttpOutcome.RejectedRequest -> BodyAssetError.InvalidManifest
        else -> BodyAssetError.Network
    }
}
