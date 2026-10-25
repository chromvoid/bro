package assistant.mobile.core.body

const val BODY_LOOKAROUND_CLIP = "rokoko_lookaround"
const val BODY_LOOKAROUND_LOOP_BLEND_SECONDS = 1.2
data class BodyLookaroundSample(val sourceSeconds: Double, val blendSourceSeconds: Double?, val blendWeight: Double, val durationSeconds: Double)
/** The reviewed source clip overlaps its tail with its first 1.2 seconds, exactly as the web. */
fun lookaroundSample(elapsedSeconds: Double, sourceDurationSeconds: Double): BodyLookaroundSample {
    val blend = BODY_LOOKAROUND_LOOP_BLEND_SECONDS
    require(sourceDurationSeconds.isFinite() && blend < sourceDurationSeconds / 2)
    val duration = sourceDurationSeconds - blend
    val time = (if (elapsedSeconds.isFinite()) elapsedSeconds.coerceAtLeast(0.0) else 0.0) % duration
    val blendStart = duration - blend
    return BodyLookaroundSample(time + blend, if (time > blendStart) time - blendStart else null,
        if (time > blendStart) bodyEase((time - blendStart) / blend) else 0.0, duration)
}
data class BodyRendererFrame(
    val visual: BodyVisualState = BodyVisualState(),
    val layer: BodyLayer = BodyLayer.Composition,
    val view: BodyView = BodyView.Front,
    val yaw: Double = view.yaw,
    val pitch: Double = BODY_DEFAULT_ORBIT_PITCH,
    val distance: Double = BODY_DEFAULT_ORBIT_DISTANCE,
    val lookaroundElapsedSeconds: Double = 0.0,
    val animate: Boolean = true,
    val comparison: Boolean = false,
    val reducedMotion: Boolean = false,
)

/** Platform frame timestamps enter here; background and static frames accrue no animation debt. */
class BodyAnimationClock {
    private var lastFrameNanos: Long? = null
    private var lookaroundElapsed = 0.0
    fun reset() { lastFrameNanos = null }
    fun advance(frameNanos: Long, frame: BodyRendererFrame): BodyRendererFrame {
        val moving = frame.animate && !frame.reducedMotion && !frame.comparison
        val previous = lastFrameNanos
        val delta = if (moving && previous != null && frameNanos >= previous)
            ((frameNanos - previous) / 1_000_000_000.0).coerceAtMost(0.1) else 0.0
        lastFrameNanos = if (moving) frameNanos else null
        lookaroundElapsed += delta
        return frame.copy(lookaroundElapsedSeconds = lookaroundElapsed)
    }
}
