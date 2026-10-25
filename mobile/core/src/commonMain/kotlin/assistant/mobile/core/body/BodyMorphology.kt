package assistant.mobile.core.body

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

data class BodyComposition(val fat: Double = 0.4, val muscle: Double = 0.5)
val BODY_SHAPE_NAMES = List(30) { "shape_f${it / 5}_m${it % 5}" }
val BODY_CORRECTION_NAMES = listOf("pose_shoulder_raise_l", "pose_shoulder_raise_r", "pose_elbow_flex_l", "pose_elbow_flex_r")
const val BODY_NORMAL_STRENGTH = 0.6
fun normalizeComposition(value: BodyComposition) = BodyComposition(
    if (value.fat.isFinite()) value.fat.coerceIn(0.0, 1.0) else 0.4,
    if (value.muscle.isFinite()) value.muscle.coerceIn(0.0, 1.0) else 0.5,
)
fun bodyComposition(fat: Double?, muscle: Double?) = normalizeComposition(BodyComposition(fat ?: 0.4, muscle ?: 0.5))
fun bodyComposition(visual: BodyVisualState) = bodyComposition(visual.fat, visual.muscle)
fun bodyEase(value: Double): Double = value.coerceIn(0.0, 1.0).let { it * it * (3 - 2 * it) }

/** The four neighboring authored shapes form a convex grid; weights are never accumulated. */
fun compositionWeights(value: BodyComposition): List<Double> {
    val composition = normalizeComposition(value)
    val fat = composition.fat * 5; val muscle = composition.muscle * 4
    return List(30) { index -> (1 - abs(fat - index / 5)).coerceAtLeast(0.0) * (1 - abs(muscle - index % 5)).coerceAtLeast(0.0) }
}
fun bodyMorphWeights(fat: Double?, muscle: Double?) = compositionWeights(bodyComposition(fat, muscle))
fun bodyMorphWeights(visual: BodyVisualState) = bodyMorphWeights(visual.fat, visual.muscle)
fun interpolateComposition(from: BodyComposition, to: BodyComposition, progress: Double): BodyComposition {
    val amount = bodyEase(progress)
    return BodyComposition(from.fat + (to.fat - from.fat) * amount, from.muscle + (to.muscle - from.muscle) * amount)
}
fun reliefWeights(composition: BodyComposition, grid: List<List<Double>>): List<Double> {
    require(grid.size == 30 && grid.all { it.size == 3 })
    val weights = compositionWeights(composition)
    return List(3) { channel -> weights.indices.sumOf { weights[it] * grid[it][channel] } }
}

data class BodyQuaternion(val x: Double, val y: Double, val z: Double, val w: Double)
data class PoseCorrective(
    val name: String,
    val bone: String,
    val axis: List<Double>,
    val sign: Double,
    val startRadians: Double,
    val endRadians: Double,
    val compositionScales: List<Double>,
)

/** Signed twist from the bind pose, invariant under quaternion sign and complete turns. */
fun correctiveActivation(rotation: BodyQuaternion, rest: BodyQuaternion, correction: PoseCorrective): Double {
    val x = rest.w * rotation.x - rest.x * rotation.w - rest.y * rotation.z + rest.z * rotation.y
    val y = rest.w * rotation.y + rest.x * rotation.z - rest.y * rotation.w - rest.z * rotation.x
    val z = rest.w * rotation.z - rest.x * rotation.y + rest.y * rotation.x - rest.z * rotation.w
    val w = rest.w * rotation.w + rest.x * rotation.x + rest.y * rotation.y + rest.z * rotation.z
    val norm = sqrt(x * x + y * y + z * z + w * w)
    if (norm == 0.0 || !norm.isFinite()) return 0.0
    val hemisphere = if (w < 0) -1.0 else 1.0
    val projected = (x * correction.axis[0] + y * correction.axis[1] + z * correction.axis[2]) * hemisphere / norm
    val angle = correction.sign * 2 * atan2(projected, w * hemisphere / norm)
    return bodyEase((angle - correction.startRadians) / (correction.endRadians - correction.startRadians))
}
fun poseCorrectiveWeights(
    composition: BodyComposition, corrections: List<PoseCorrective>,
    rotations: Map<String, BodyQuaternion>, rest: Map<String, BodyQuaternion>,
): List<Double> {
    val shapes = compositionWeights(composition)
    return corrections.map { correction ->
        require(correction.compositionScales.size == 30)
        val scale = shapes.indices.sumOf { shapes[it] * correction.compositionScales[it] }
        correctiveActivation(requireNotNull(rotations[correction.bone]), requireNotNull(rest[correction.bone]), correction) * scale
    }
}

fun nearestViewYaw(from: Double, to: Double): Double = from + atan2(sin(to - from), cos(to - from))
