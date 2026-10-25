package assistant.mobile.core.activity

import assistant.mobile.core.personal.PersonalOverview
import assistant.mobile.core.personal.PersonalResolution
import assistant.mobile.core.personal.PersonalResolvedDay
import kotlinx.serialization.json.JsonObject

val ACTIVITY_KINDS = listOf("walking", "running", "cycling", "strength", "swimming", "mobility", "other")
val ACTIVITY_LOAD_BASES = listOf("total", "per_implement", "additional", "assistance", "bodyweight")
data class ActivityRange(val from: String, val to: String, val kind: String? = null)
data class ExerciseSnapshot(val name: String, val equipment: String?, val variant: String?, val loadBasis: String)
data class ExerciseDefinition(val id: String, val revision: Long, val name: String, val equipment: String?, val variant: String?, val loadBasis: String) {
    val snapshot: ExerciseSnapshot get() = ExerciseSnapshot(name, equipment, variant, loadBasis)
}
data class ActivitySet(val id: String, val revision: Long, val position: Int, val kind: String, val repetitions: Int?, val loadValue: String?, val loadUnit: String, val implementCount: Int, val note: String?)
data class SessionExercise(val id: String, val revision: Long, val exerciseId: String, val position: Int, val snapshot: ExerciseSnapshot, val note: String?, val sets: List<ActivitySet>)
data class ActivitySession(val id: String, val revision: Long, val kind: String, val title: String?, val date: String, val timezone: String, val startedAt: String?, val durationMinutes: String?, val distanceKm: String?, val note: String?, val exercises: List<SessionExercise>)
data class ActivitySessions(val sessions: List<ActivitySession>, val truncated: Boolean = false)
data class ActivitySummaryValues(val sessions: Int, val durationMinutes: String?, val missingDurationSessions: Int, val distanceKmByKind: Map<String, String?>, val workingSets: Int)
data class ActivitySummaryDay(val date: String, val sessions: Int, val durationMinutes: String?, val missingDurationSessions: Int, val distanceKmByKind: Map<String, String?>, val workingSets: Int)
data class ActivitySummary(val from: String, val to: String, val totals: ActivitySummaryValues, val daily: List<ActivitySummaryDay>)
data class ExerciseProgressPoint(val date: String, val sessionId: String, val sessionExerciseId: String, val setId: String, val repetitions: Int?, val loadValue: String?, val loadKg: String?)
data class ExerciseProgressSeries(val snapshot: ExerciseSnapshot, val loadUnit: String, val implementCount: Int, val points: List<ExerciseProgressPoint>)
data class ExerciseProgressDay(val date: String, val workingSets: Int)
data class ExerciseProgress(val exercise: ExerciseDefinition, val from: String, val to: String, val series: List<ExerciseProgressSeries>, val daily: List<ExerciseProgressDay>)
data class ActivityStepsOverride(val id: String, val revision: Long, val value: Int?, val active: Boolean)
data class ActivityStepsReply(val date: String, val override: ActivityStepsOverride?)
data class WorkoutPause(val startAt: String, val endAt: String)
data class WorkoutHeartRate(val mean: Double, val max: Double)
data class WorkoutEffortBasis(val rest: String, val max: String, val basis: String)
data class ActivityEffort(val sourceName: String, val heartRate: WorkoutHeartRate, val zoneSeconds: List<Double>, val trimp: String?, val effort: WorkoutEffortBasis?)
data class WorkoutSportPlan(val requestedId: Int, val name: String, val family: String, val candidateWireMode: Int?, val actualWireMode: Int?, val qualification: String, val evidenceRef: String, val mappingBasis: String?, val resolvedActivityId: Int?)
data class DeviceWorkout(
    val id: String, val revision: Long, val sourceId: String?, val sourceName: String?,
    val startAt: String, val endAt: String, val date: String, val timezone: String?,
    val originSource: String?, val workoutId: String?, val sportType: String,
    val durationSeconds: Double?, val activeDurationSeconds: Double?, val steps: Double?, val distanceMeters: Double?,
    val heartRate: WorkoutHeartRate?, val zoneSeconds: List<Double>, val trimp: String?, val effort: WorkoutEffortBasis?,
    val pauses: List<WorkoutPause> = emptyList(), val sportPlan: WorkoutSportPlan? = null,
    val resolution: PersonalResolution? = null, val originDetails: JsonObject? = null,
)
data class ActivityResolvedPage(val from: String, val to: String, val timezone: String, val computedAt: String, val fingerprint: String, val daily: List<PersonalResolvedDay>, val workouts: List<DeviceWorkout>, val nextCursor: String? = null, val contexts: List<ActivityDayContext> = emptyList())
data class DeviceWorkoutSummary(val count: Double?, val knownDurationSeconds: Double?, val unknownDurationCount: Double?)
data class ActivityDayContext(val date: String, val kind: String, val observedAt: String?, val snapshot: Boolean, val workoutSummary: DeviceWorkoutSummary?)
data class ActivityHistory(val from: String, val to: String, val daily: List<PersonalResolvedDay>, val truncated: Boolean = false)
data class ActivityReport(val id: String, val sourceId: String, val sourceName: String?, val revision: Long, val sourceVersion: Long, val kind: String, val shape: String, val date: String, val timezone: String, val observedAt: String, val startAt: String?, val endAt: String?, val data: JsonObject, val origin: JsonObject, val quality: List<String>, val isCurrent: Boolean?, val currentRevision: Long?, val annotationRevision: Long?, val excluded: Boolean?, val resolutionDisposition: String?, val snapshot: JsonObject, val workout: DeviceWorkout?) {
    val key: String get() = "$id:$revision:$sourceVersion"
}
data class ActivityReportPage(val from: String, val to: String, val items: List<ActivityReport>, val fingerprint: String, val nextCursor: String? = null)
data class WorkoutContext(val startAt: String, val endAt: String, val timezone: String, val status: String, val trigger: String, val kind: String? = null, val sportType: String? = null, val recoveryEndAt: String? = null, val stopReason: String? = null, val pauses: List<WorkoutPause> = emptyList(), val raw: JsonObject? = null)
data class HeartRateSample(val offsetMs: Long, val bpm: Int, val quality: String)
data class HeartRateGap(val startOffsetMs: Long, val endOffsetMs: Long, val reason: String)
data class WorkoutHeartRateChunk(val externalId: String, val workoutId: String, val sequence: Int, val startAt: String, val endAt: String, val samples: List<HeartRateSample>, val gaps: List<HeartRateGap>, val raw: JsonObject)
data class WorkoutHeartRatePage(val workoutId: String, val sourceId: String, val reportId: String, val reportRevision: Long, val fingerprint: String, val workout: WorkoutContext, val chunks: List<WorkoutHeartRateChunk>, val nextCursor: String?)
data class WorkoutPulse(val observedAt: String, val bpm: Int, val quality: String, val phase: String)
data class TrackPoint(val at: String, val latitude: Double, val longitude: Double, val accuracyMeters: Double?, val quality: String, val provider: String? = null, val paused: Boolean = false)
data class TrackSegment(val context: String, val quality: String, val points: List<TrackPoint>)
data class TrackContextSummary(val distanceMeters: Double?, val usablePoints: Int, val reportedPoints: Int, val gapCount: Int, val coverageSeconds: Double, val quality: List<String>, val pausedPoints: Int?, val pausedSeconds: Double?, val usableSegments: Int? = null, val rawQuality: String? = null)
data class WorkoutTrackSummary(val algorithmVersion: String, val valueMeters: Double?, val contexts: Map<String, TrackContextSummary>, val fingerprint: String?, val raw: JsonObject)
data class WorkoutTrackPage(val workoutId: String, val sourceId: String, val workout: WorkoutContext, val chunks: List<JsonObject>, val segments: List<TrackSegment>, val fingerprint: String, val summary: WorkoutTrackSummary?, val nextCursor: String?)
data class WorkoutTrackVersion(val revision: Long, val snapshot: WorkoutTrackSummary, val isLatest: Boolean)
data class WorkoutTrackVersionsPage(val workoutId: String, val sourceId: String, val items: List<WorkoutTrackVersion>, val latestRevision: Long?, val fingerprint: String, val nextCursor: String?)
data class ActivityChartPoint(val x: Double, val y: Double)
