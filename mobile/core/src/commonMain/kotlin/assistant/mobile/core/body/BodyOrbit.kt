package assistant.mobile.core.body

import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

const val BODY_STAGE_HEIGHT = 3.25
val BODY_DEFAULT_ORBIT_DISTANCE = sqrt(0.88 * 0.88 + 0.18 * 0.18 + 6.4 * 6.4)
val BODY_DEFAULT_ORBIT_PITCH = atan2(sqrt(0.88 * 0.88 + 6.4 * 6.4), 0.18)

data class BodyPoint(val x: Double, val y: Double, val z: Double)
data class BodyFrameNormalization(val scale: Double, val translation: BodyPoint) {
    val target = BodyPoint(0.0, BODY_STAGE_HEIGHT / 2, 0.0)
    fun transform(point: BodyPoint) = BodyPoint(
        point.x * scale + translation.x,
        point.y * scale + translation.y,
        point.z * scale + translation.z,
    )
}

/** Capture axis and bounds before animation; authored offsets never become the orbit center. */
fun normalizeBodyFrame(floor: Double, height: Double, restAxis: BodyPoint): BodyFrameNormalization {
    require(floor.isFinite() && height.isFinite() && height >= 0.1)
    require(restAxis.x.isFinite() && restAxis.y.isFinite() && restAxis.z.isFinite())
    val scale = BODY_STAGE_HEIGHT / height
    return BodyFrameNormalization(scale, BodyPoint(-restAxis.x * scale, -floor * scale, -restAxis.z * scale))
}

/** Spherical camera coordinates are relative to the fixed target, independent of the model. */
fun bodyOrbitPosition(target: BodyPoint, yaw: Double, pitch: Double, distance: Double): BodyPoint {
    require(yaw.isFinite() && pitch.isFinite() && pitch > 0 && pitch < PI)
    require(distance.isFinite() && distance > 0)
    val horizontal = sin(pitch) * distance
    return BodyPoint(target.x + sin(yaw) * horizontal, target.y + cos(pitch) * distance,
        target.z + cos(yaw) * horizontal)
}
