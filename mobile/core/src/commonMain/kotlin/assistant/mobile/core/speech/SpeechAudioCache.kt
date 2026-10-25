package assistant.mobile.core.speech

import kotlin.time.TimeSource

/** Complete answers only, retained in this session's memory; bounded by bytes, entries and age. */
class SpeechAudioCache(
    val maxBytes: Int = 32 * 1024 * 1024,
    private val maxEntries: Int = 16,
    private val ttlMillis: Long = 30 * 60 * 1000L,
    private val nowMillis: () -> Long = speechCacheClock(),
) {
    private data class Entry(val connection: String, val segments: List<SpeechAudioSegment>, val bytes: Int, val createdAt: Long)
    private val answers = linkedMapOf<String, Entry>()
    var byteCount: Int = 0
        private set
    val entryCount: Int get() = answers.size

    init { require(maxBytes >= 0 && maxEntries >= 0 && ttlMillis >= 0) }

    fun get(key: String, connection: String): List<SpeechAudioSegment>? {
        val entry = answers[key] ?: return null
        if (entry.connection != connection || nowMillis() - entry.createdAt >= ttlMillis) {
            remove(key)
            return null
        }
        answers.remove(key)
        answers[key] = entry
        return entry.segments.map { it.copy(audio = it.audio.copyOf()) }
    }

    fun put(key: String, connection: String, segments: List<SpeechAudioSegment>) {
        val bytes = segments.sumOf { it.audio.size.toLong() }
        if (segments.isEmpty() || segments.any { it.audio.isEmpty() } || bytes <= 0 || bytes > maxBytes || maxEntries < 1) return
        remove(key)
        answers.filterValues { nowMillis() - it.createdAt >= ttlMillis }.keys.toList().forEach(::remove)
        while (byteCount.toLong() + bytes > maxBytes || answers.size >= maxEntries) remove(answers.keys.first())
        answers[key] = Entry(connection, segments.map { it.copy(audio = it.audio.copyOf()) }, bytes.toInt(), nowMillis())
        byteCount += bytes.toInt()
    }

    fun invalidateConnection(connection: String) {
        answers.filterValues { it.connection == connection }.keys.toList().forEach(::remove)
    }

    fun clear() {
        answers.clear()
        byteCount = 0
    }

    private fun remove(key: String) {
        answers.remove(key)?.let { byteCount -= it.bytes }
    }
}

private fun speechCacheClock(): () -> Long {
    val mark = TimeSource.Monotonic.markNow()
    return { mark.elapsedNow().inWholeMilliseconds }
}
