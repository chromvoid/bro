package assistant.mobile.core.photos

import assistant.mobile.core.AccountNamespace
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PrivateImageCacheTest {
    private val account = AccountNamespace("synthetic", PHOTO_TEST_ORIGIN, "synthetic-photo-owner")

    @Test
    fun bytesEntriesAgeAndAccountBoundariesLimitPrivateMemory() {
        var now = 0L
        val cache = PrivateImageCache(maxBytes = 8, maxEntries = 2, ttlMillis = 100, nowMillis = { now })
        cache.put(account, "a", image(4))
        cache.put(account, "b", image(4))
        cache.get(account, "a")
        cache.put(account, "c", image(4))
        assertNull(cache.get(account, "b"))
        assertEquals(8, cache.byteCount)
        cache.put(account, "huge", image(9))
        assertEquals(2, cache.entryCount)
        now = 100
        assertNull(cache.get(account, "a"))
        cache.put(account, "new", image(2))
        assertEquals(2, cache.byteCount)
        cache.bind(account.copy(userId = "other-account"))
        assertEquals(0, cache.entryCount)
        assertEquals(0, cache.byteCount)
    }

    @Test
    fun callersCannotChangeRetainedPrivateBytesAndNullAccountPurges() {
        val cache = PrivateImageCache(maxBytes = 8, maxEntries = 1)
        val source = image(4)
        cache.put(account, "a", source)
        source.bytes[0] = 1
        val first = cache.get(account, "a")!!
        assertContentEquals(ByteArray(4), first.bytes)
        first.bytes[0] = 2
        assertContentEquals(ByteArray(4), cache.get(account, "a")!!.bytes)
        cache.put(account, "b", image(2))
        assertNull(cache.get(account, "a"))
        cache.bind(null)
        assertEquals(0, cache.byteCount)
    }

    private fun image(bytes: Int) = PrivateImage(ByteArray(bytes), "image/jpeg")
}
