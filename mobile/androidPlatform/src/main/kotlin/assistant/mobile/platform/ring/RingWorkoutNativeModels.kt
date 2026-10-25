package assistant.mobile.platform.ring

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.*

/** A screen observes this collector generation; it never owns the accepted recording. */
data class RingWorkoutScreenLease(val generation: Long, val userId: String, val sourceId: String,
    val grantGeneration: Long, val canRead: Boolean = true, val canWrite: Boolean = true)
enum class RingWorkoutPermission { ACTIVITY, LOCATION, BACKGROUND_LOCATION, NOTIFICATIONS }
sealed interface RingWorkoutAction {
    data object Refresh : RingWorkoutAction
    data class Start(val sportId: Int = 7, val gpsRequested: Boolean = false) : RingWorkoutAction
    data class Pause(val workoutId: String) : RingWorkoutAction
    data class Resume(val workoutId: String) : RingWorkoutAction
    data class Stop(val workoutId: String) : RingWorkoutAction
}
data class RingWorkoutHeartRate(val bpm: Int, val observedAt: String, val elapsedRealtimeMs: Long,
    val quality: String, val phase: String)
data class RingWorkoutGpsFix(val at: String, val latitude: Double, val longitude: Double,
    val accuracyMeters: Double, val quality: String)
data class RingWorkoutGpsState(val permission: String = "denied", val backgroundPermission: String = "required",
    val locationEnabled: Boolean = false, val status: String = "idle", val reason: String? = null,
    val lastFix: RingWorkoutGpsFix? = null, val gapCount: Int = 0)
data class RingWorkoutRecognitionState(val available: Boolean = false, val permission: String = "required",
    val registered: Boolean = false, val error: String? = null)
data class RingWorkoutSportEntry(val requestedId: Int, val name: String, val family: String,
    val appOutdoorGps: Boolean, val candidateWireMode: Int?, val qualification: String,
    val evidenceRef: String, val mappingBasis: String?, val resolvedActivityId: Int?)
data class RingWorkoutPauseInterval(val startAt: String, val endAt: String)
data class RingWorkoutNativeState(
    val userId: String? = null, val sourceId: String? = null, val bindingGeneration: Long = 0,
    val phase: String = "idle", val workoutId: String? = null, val ownership: String = "manual",
    val trigger: String? = null, val startedAt: String? = null, val endedAt: String? = null,
    val lastHr: RingWorkoutHeartRate? = null, val samples: List<RingWorkoutHeartRate> = emptyList(),
    val gaps: Int = 0, val capabilities: List<RingCapability> = emptyList(), val error: RingNativeError? = null,
    val commandUnknown: Boolean = false, val autoBlocked: Boolean = false, val sportType: String = "running",
    val sportPlan: RingSportPlan? = null, val sportCatalog: List<RingWorkoutSportEntry> = emptyList(),
    val gpsRequested: Boolean = false, val elapsedSeconds: Long? = null, val activeDurationSeconds: Long? = null,
    val pauses: List<RingWorkoutPauseInterval> = emptyList(), val pausedAt: String? = null,
    val recordingSource: String = "phone_only", val canPause: Boolean = false, val canResume: Boolean = false,
    val hrStatus: String = "idle", val hrReason: String? = null, val gps: RingWorkoutGpsState = RingWorkoutGpsState(),
    val activityRecognition: RingWorkoutRecognitionState = RingWorkoutRecognitionState(),
) {
    companion object {
        internal fun fromJson(value: JsonObject): RingWorkoutNativeState {
            val gps = value["gps"] as? JsonObject
            val recognition = value["activity_recognition"] as? JsonObject
            val controls = value["workout_controls"] as? JsonObject
            fun hr(point: JsonObject) = RingWorkoutHeartRate(point.number("bpm") ?: 0, point.string("observed_at") ?: "",
                point["elapsed_realtime_ms"]?.jsonPrimitive?.longOrNull ?: 0, point.string("quality") ?: "unknown", point.string("phase") ?: "running")
            return RingWorkoutNativeState(
                userId = value.string("user_id"), sourceId = value.string("source_id"),
                bindingGeneration = value["binding_generation"]?.jsonPrimitive?.longOrNull ?: 0,
                phase = value.string("phase") ?: "idle", workoutId = value.string("workout_id"),
                ownership = value.string("ownership") ?: "manual", trigger = value.string("trigger"),
                startedAt = value.string("started_at"), endedAt = value.string("ended_at"),
                lastHr = (value["last_hr"] as? JsonObject)?.let(::hr),
                samples = (value["samples"] as? JsonArray)?.mapNotNull { (it as? JsonObject)?.let(::hr) } ?: emptyList(),
                gaps = value.number("gaps") ?: 0,
                capabilities = (value["capabilities"] as? JsonArray)?.mapNotNull { (it as? JsonObject)?.let { c ->
                    RingCapability(c.string("kind") ?: return@mapNotNull null, c.string("status") ?: "protocol_unverified",
                        c.string("reason") ?: "", c.bool("readable") ?: false, c.bool("writable") ?: false)
                } } ?: emptyList(),
                error = (value["error"] as? JsonObject)?.let { RingNativeError(it.string("code") ?: "unavailable", it.string("message") ?: "") },
                commandUnknown = value.bool("command_unknown") ?: false, autoBlocked = value.bool("auto_blocked") ?: false,
                sportType = value.string("sport_type") ?: "running", sportPlan = (value["sport_plan"] as? JsonObject)?.let(RingSportPlan::from),
                sportCatalog = (value["sport_catalog"] as? JsonArray)?.mapNotNull { (it as? JsonObject)?.let { sport ->
                    RingWorkoutSportEntry(sport.number("requested_id") ?: return@mapNotNull null,
                        sport.string("name") ?: "", sport.string("family") ?: "", sport.bool("app_outdoor_gps") ?: false,
                        sport.number("candidate_wire_mode"), sport.string("qualification") ?: "protocol_unverified",
                        sport.string("evidence_ref") ?: "", sport.string("mapping_basis"), sport.number("resolved_activity_id"))
                } } ?: emptyList(), gpsRequested = value.bool("gps_requested") ?: false,
                elapsedSeconds = value["elapsed_seconds"]?.jsonPrimitive?.longOrNull,
                activeDurationSeconds = value["active_duration_seconds"]?.jsonPrimitive?.longOrNull,
                pauses = (value["pauses"] as? JsonArray)?.mapNotNull { (it as? JsonObject)?.let { p ->
                    RingWorkoutPauseInterval(p.string("start_at") ?: return@mapNotNull null, p.string("end_at") ?: return@mapNotNull null)
                } } ?: emptyList(), pausedAt = value.string("paused_at"), recordingSource = value.string("recording_source") ?: "phone_only",
                canPause = controls?.bool("pause") ?: false, canResume = controls?.bool("resume") ?: false,
                hrStatus = value.string("hr_status") ?: "idle", hrReason = value.string("hr_reason"),
                gps = gps?.let { g -> RingWorkoutGpsState(g.string("permission") ?: "denied", g.string("background_permission") ?: "required",
                    g.bool("location_enabled") ?: false, g.string("status") ?: "idle", g.string("reason"),
                    (g["last_fix"] as? JsonObject)?.let { f -> RingWorkoutGpsFix(f.string("at") ?: "", f["latitude"]!!.jsonPrimitive.double,
                        f["longitude"]!!.jsonPrimitive.double, f["accuracy_meters"]!!.jsonPrimitive.double, f.string("quality") ?: "unknown") },
                    g.number("gap_count") ?: 0) } ?: RingWorkoutGpsState(),
                activityRecognition = recognition?.let { RingWorkoutRecognitionState(it.bool("available") ?: false,
                    it.string("permission") ?: "required", it.bool("registered") ?: false, it.string("error")) } ?: RingWorkoutRecognitionState(),
            )
        }
    }
}
internal object RingWorkoutNativeDefaults { val states: StateFlow<RingWorkoutNativeState> = MutableStateFlow(RingWorkoutNativeState()) }
