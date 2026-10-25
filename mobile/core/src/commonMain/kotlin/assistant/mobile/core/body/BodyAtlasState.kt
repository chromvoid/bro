package assistant.mobile.core.body

/** UI-independent choices for one scan; scrubbing never changes measurement values. */
data class BodyAtlasState(
    val layer: BodyLayer = BodyLayer.Composition,
    val view: BodyView = BodyView.Front,
    val yaw: Double? = null,
    val pitch: Double = BODY_DEFAULT_ORBIT_PITCH,
    val distance: Double = BODY_DEFAULT_ORBIT_DISTANCE,
    val comparing: Boolean = false,
    val comparisonId: String? = null,
    val comparisonFraction: Double = 1.0,
) {
    fun changedAnalysis() = copy(comparing = false, comparisonId = null, comparisonFraction = 1.0)
    fun compare(enabled: Boolean) = copy(comparing = enabled, comparisonFraction = 1.0)
    fun previous(id: String) = copy(comparisonId = id, comparisonFraction = 1.0)
    fun scrub(fraction: Double) = copy(comparisonFraction = if (fraction.isFinite()) fraction.coerceIn(0.0, 1.0) else 1.0)
    fun rotate(value: Double) = if (value.isFinite()) copy(yaw = value) else this
    fun setView(value: BodyView) = copy(view = value, yaw = null)
}
