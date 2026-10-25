package assistant.mobile.android.ambient

import java.time.Instant

/** Omitted silence still advances the source cursor; every clip retains its real UTC position. */
object AmbientCaptureTimeline {
    fun startedAt(anchor: Instant, startSample: Long): String {
        require(startSample >= 0)
        return anchor.plusSeconds(startSample / 16_000).plusNanos((startSample % 16_000) * 62_500).toString()
    }
}
