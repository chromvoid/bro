package assistant.mobile.core.speech

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SpeechAudioCacheTest {
    @Test
    fun boundedCacheEvictsLeastRecentlyUsedAndRejectsOversizedAnswers() {
        val cache = SpeechAudioCache(maxBytes = 8, maxEntries = 2)
        cache.put("a", "connection", audio(4))
        cache.put("b", "connection", audio(4))
        cache.get("a", "connection")
        cache.put("c", "connection", audio(4))
        assertNull(cache.get("b", "connection"))
        assertEquals(8, cache.byteCount)
        cache.put("huge", "connection", audio(10))
        assertEquals(2, cache.entryCount)
        assertNull(cache.get("huge", "connection"))
        cache.put("empty", "connection", emptyList())
        assertEquals(8, cache.byteCount)
    }

    @Test
    fun entriesExpireAndConnectionChangesInvalidateAudio() {
        var now = 0L
        val cache = SpeechAudioCache(ttlMillis = 100, nowMillis = { now })
        cache.put("a", "connection", audio(4))
        now = 100
        assertNull(cache.get("a", "connection"))
        assertEquals(0, cache.byteCount)
        cache.put("a", "old", audio(4))
        assertNull(cache.get("a", "new"))
        cache.put("a", "one", audio(4))
        cache.put("b", "two", audio(4))
        cache.invalidateConnection("one")
        assertNull(cache.get("a", "one"))
        assertEquals(4, cache.byteCount)
        cache.clear()
        assertEquals(0, cache.entryCount)
        assertEquals(0, cache.byteCount)
    }
}

private fun audio(size: Int) = listOf(SpeechAudioSegment(SpeechAudioFormat("audio/pcm", 24000, 1), ByteArray(size)))
