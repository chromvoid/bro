package assistant.mobile.platform.ring

import assistant.mobile.platform.R

/** Phone location is an independent stream; its clock never advances the HR watermark. */
data class RingTrackPoint(
    val elapsedRealtimeNanos: Long, val latitude: Double, val longitude: Double,
    val accuracyMeters: Double, val quality: String, val provider: String? = null,
) {
    val elapsedRealtimeMs: Long get() = elapsedRealtimeNanos / 1_000_000
    init {
        require(elapsedRealtimeNanos >= 0)
        require(latitude.isFinite() && latitude in -90.0..90.0)
        require(longitude.isFinite() && longitude in -180.0..180.0)
        require(accuracyMeters.isFinite() && accuracyMeters > 0 && accuracyMeters <= 100_000)
        require(quality in setOf("valid", "approximate", "invalid", "unknown"))
        require(provider == null || provider.length in 1..32)
    }
}
data class RingTrackChunk(val startElapsedMs: Long, val endElapsedMs: Long,
    val points: List<RingTrackPoint>, val gaps: List<RingWorkoutGap>)

object RingTrackChunker {
    const val MAX_DURATION_MS = 60_000L
    const val MAX_POINTS = 300
    fun split(points: List<RingTrackPoint>, gaps: List<RingWorkoutGap>): List<RingTrackChunk> {
        require(points.zipWithNext().all { (a, b) -> a.elapsedRealtimeNanos < b.elapsedRealtimeNanos })
        require(gaps.zipWithNext().all { (a, b) -> a.endElapsedMs <= b.startElapsedMs })
        require(points.none { point -> gaps.any { point.elapsedRealtimeMs >= it.startElapsedMs && point.elapsedRealtimeMs < it.endElapsedMs } })
        val first = minOf(points.firstOrNull()?.elapsedRealtimeMs ?: Long.MAX_VALUE,
            gaps.firstOrNull()?.startElapsedMs ?: Long.MAX_VALUE)
        if (first == Long.MAX_VALUE) return emptyList()
        val last = maxOf(points.lastOrNull()?.elapsedRealtimeMs?.plus(1) ?: first,
            gaps.lastOrNull()?.endElapsedMs ?: first)
        val chunks = mutableListOf<RingTrackChunk>()
        var start = first
        while (start < last) {
            var end = minOf(start + MAX_DURATION_MS, last)
            val candidates = points.filter { it.elapsedRealtimeMs >= start && it.elapsedRealtimeMs < end }
            if (candidates.size > MAX_POINTS) end = minOf(end, candidates[MAX_POINTS].elapsedRealtimeMs)
            val overlaps = gaps.filter { it.startElapsedMs < end && it.endElapsedMs > start }
            if (overlaps.size > MAX_POINTS) end = minOf(end, overlaps[MAX_POINTS].startElapsedMs)
            if (end <= start) throw RingFailure("track_sample_overflow", RingText.text(R.string.ring_workout_44b279161164))
            val samples = points.filter { it.elapsedRealtimeMs >= start && it.elapsedRealtimeMs < end }
            val clipped = gaps.filter { it.startElapsedMs < end && it.endElapsedMs > start }.map {
                it.copy(startElapsedMs = maxOf(start, it.startElapsedMs), endElapsedMs = minOf(end, it.endElapsedMs))
            }
            if (samples.isNotEmpty() || clipped.isNotEmpty()) chunks += RingTrackChunk(start, end, samples, clipped)
            start = end
        }
        return chunks
    }
}

/** Foreground and background admission are distinct; denied GPS never disables ring collection. */
data class RingLocationAccess(val permission: String, val background: Boolean, val enabled: Boolean) {
    init { require(permission in setOf("precise", "approximate", "denied")) }
    fun unavailableReason(foregroundStart: Boolean): String? = when {
        permission == "denied" -> "permission_denied"
        !enabled -> "location_disabled"
        !foregroundStart && !background -> "background_limited"
        else -> null
    }
}

object RingLocationFixTime {
    fun fresh(registrationNanos: Long, fixNanos: Long, nowNanos: Long): Boolean =
        registrationNanos >= 0 && fixNanos >= registrationNanos && fixNanos <= nowNanos &&
            nowNanos - fixNanos <= 60_000_000_000L
}
