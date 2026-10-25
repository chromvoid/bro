package assistant.mobile.core.photos

import assistant.mobile.core.AccountNamespace
import kotlin.time.TimeSource

data class PrivateImage(val bytes: ByteArray, val contentType: String) {
    override fun toString(): String = "PrivateImage(contentType=$contentType, bytes=redacted)"
}

/** Session-memory LRU only: no shared HTTP/image-loader disk cache may retain private photos. */
class PrivateImageCache(
    val maxBytes: Int = 24 * 1024 * 1024,
    private val maxEntries: Int = 40,
    private val ttlMillis: Long = 30 * 60 * 1000L,
    private val nowMillis: () -> Long = imageCacheClock(),
) {
    private data class Entry(val image: PrivateImage, val createdAt: Long)
    private var namespace: AccountNamespace? = null
    private val entries = linkedMapOf<String, Entry>()
    var byteCount = 0
        private set
    val entryCount: Int get() = entries.size

    init { require(maxBytes >= 0 && maxEntries >= 0 && ttlMillis >= 0) }

    fun bind(account: AccountNamespace?) {
        if (namespace != account) {
            clear()
            namespace = account
        }
    }

    fun get(account: AccountNamespace, key: String): PrivateImage? {
        bind(account)
        val entry = entries[key] ?: return null
        if (nowMillis() - entry.createdAt >= ttlMillis) {
            remove(key)
            return null
        }
        entries.remove(key)
        entries[key] = entry
        return entry.image.copy(bytes = entry.image.bytes.copyOf())
    }

    fun put(account: AccountNamespace, key: String, image: PrivateImage) {
        bind(account)
        if (image.bytes.isEmpty() || image.bytes.size > maxBytes || maxEntries < 1) return
        remove(key)
        entries.filterValues { nowMillis() - it.createdAt >= ttlMillis }.keys.toList().forEach(::remove)
        while (byteCount.toLong() + image.bytes.size > maxBytes || entries.size >= maxEntries) remove(entries.keys.first())
        entries[key] = Entry(image.copy(bytes = image.bytes.copyOf()), nowMillis())
        byteCount += image.bytes.size
    }

    fun clear() {
        entries.clear()
        byteCount = 0
    }

    private fun remove(key: String) { entries.remove(key)?.let { byteCount -= it.image.bytes.size } }
}

private fun imageCacheClock(): () -> Long {
    val mark = TimeSource.Monotonic.markNow()
    return { mark.elapsedNow().inWholeMilliseconds }
}
