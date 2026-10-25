package assistant.mobile.core.activity

import assistant.mobile.core.HttpOutcome
import assistant.mobile.core.NativeHttpClient
import assistant.mobile.core.SessionLease
import assistant.mobile.core.personal.NativePersonalApi
import assistant.mobile.core.personal.PersonalOverview
import assistant.mobile.core.personal.PersonalReadException
import io.ktor.http.encodeURLParameter
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.withTimeout
import kotlin.time.Instant

interface ActivityReader {
    suspend fun overview(day: String, lease: SessionLease): PersonalOverview
    suspend fun sessions(range: ActivityRange, lease: SessionLease): ActivitySessions
    suspend fun summary(range: ActivityRange, lease: SessionLease): ActivitySummary
    suspend fun session(id: String, lease: SessionLease): ActivitySession
    suspend fun exercises(lease: SessionLease): List<ExerciseDefinition>
    suspend fun progress(exerciseId: String, range: ActivityRange, lease: SessionLease): ExerciseProgress
    suspend fun resolved(range: ActivityRange, metric: String, lease: SessionLease): ActivityResolvedPage = error("Not implemented")
    suspend fun history(range: ActivityRange, kind: String, lease: SessionLease): ActivityHistory = error("Not implemented")
    suspend fun steps(day: String, lease: SessionLease): ActivityStepsReply = error("Not implemented")
    suspend fun effort(session: ActivitySession, lease: SessionLease): ActivityEffort? = null
    suspend fun reports(range: ActivityRange, lease: SessionLease): ActivityReportPage = error("Not implemented")
    suspend fun heartRate(workoutId: String, sourceId: String, lease: SessionLease): List<WorkoutHeartRatePage> = error("Not implemented")
    suspend fun track(workoutId: String, sourceId: String, lease: SessionLease): List<WorkoutTrackPage> = error("Not implemented")
    suspend fun trackVersions(workoutId: String, sourceId: String, lease: SessionLease): List<WorkoutTrackVersionsPage> = error("Not implemented")
    suspend fun tile(z: Int, x: Int, y: Int, lease: SessionLease): ByteArray = error("Not implemented")
}

class ActivityReadException(val outcome: HttpOutcome) : Exception("Activity request failed")

/** Every paginated read is pinned to one account lease and one server decision fingerprint. */
class NativeActivityApi(private val http: NativeHttpClient) : ActivityReader {
    override suspend fun overview(day: String, lease: SessionLease): PersonalOverview = try { NativePersonalApi(http).overview(day, lease) } catch (failure: PersonalReadException) { throw ActivityReadException(failure.outcome) }
    override suspend fun sessions(range: ActivityRange, lease: SessionLease): ActivitySessions = get("/v1/activity/sessions?${range.query()}", lease, ::decodeActivitySessions).also { page ->
        valid(page.sessions.all { it.date in range.from..range.to && (range.kind == null || it.kind == range.kind) })
    }
    override suspend fun summary(range: ActivityRange, lease: SessionLease): ActivitySummary = get("/v1/activity/summary?${range.query()}", lease, ::decodeActivitySummary).also { valid(it.from == range.from && it.to == range.to) }
    override suspend fun session(id: String, lease: SessionLease): ActivitySession = get("/v1/activity/sessions/${id.encodeURLParameter()}", lease, ::decodeActivitySession).also { valid(it.id == id) }
    override suspend fun exercises(lease: SessionLease): List<ExerciseDefinition> = get("/v1/activity/exercises", lease, ::decodeActivityExercises)
    override suspend fun progress(exerciseId: String, range: ActivityRange, lease: SessionLease): ExerciseProgress = get("/v1/activity/progress?exercise_id=${exerciseId.encodeURLParameter()}&${range.copy(kind = null).query()}", lease, ::decodeExerciseProgress).also { valid(it.exercise.id == exerciseId && it.from == range.from && it.to == range.to) }
    override suspend fun resolved(range: ActivityRange, metric: String, lease: SessionLease): ActivityResolvedPage {
        require(metric in setOf("steps", "active_minutes", "active_energy", "distance", "running_steps", "running_workouts", "workouts"))
        val pages = pages({ cursor -> "/v1/personal/resolved?${range.copy(kind = null).query()}&metric=${metric.encodeURLParameter()}&limit=100${cursor.queryCursor()}" }, lease, ::decodeActivityResolved, { it.nextCursor })
        val first = pages.first()
        valid(pages.all { it.from == range.from && it.to == range.to && it.fingerprint == first.fingerprint && it.timezone == first.timezone && it.daily.all { day -> day.metric.kind == metric && day.date in range.from..range.to } })
        return first.copy(workouts = pages.flatMap { it.workouts }.distinctBy { it.id }, nextCursor = null)
    }
    override suspend fun history(range: ActivityRange, kind: String, lease: SessionLease): ActivityHistory = get("/v1/personal/history?${range.copy(kind = null).query()}&kind=${kind.encodeURLParameter()}", lease, ::decodeActivityHistory).also { valid(it.from == range.from && it.to == range.to && it.daily.all { day -> day.date in range.from..range.to && day.metric.kind == kind }) }
    override suspend fun steps(day: String, lease: SessionLease): ActivityStepsReply = get("/v1/personal/steps?date=${day.encodeURLParameter()}", lease, ::decodeActivitySteps).also { valid(it.date == day) }
    override suspend fun effort(session: ActivitySession, lease: SessionLease): ActivityEffort? {
        val window = sessionWindow(session) ?: return null
        val from = Instant.fromEpochMilliseconds(window.first).toString().encodeURLParameter()
        val to = Instant.fromEpochMilliseconds(window.second).toString().encodeURLParameter()
        return get("/v1/personal/effort?from=$from&to=$to", lease, ::decodeActivityEffort)
    }
    override suspend fun reports(range: ActivityRange, lease: SessionLease): ActivityReportPage {
        val pages = pages({ cursor -> "/v1/personal/activity-reports?${range.copy(kind = null).query()}&limit=100${cursor.queryCursor()}" }, lease, ::decodeActivityReports, { it.nextCursor })
        val first = pages.first()
        valid(pages.all { it.from == range.from && it.to == range.to && it.fingerprint == first.fingerprint })
        return first.copy(items = pages.flatMap { it.items }, nextCursor = null)
    }
    override suspend fun heartRate(workoutId: String, sourceId: String, lease: SessionLease): List<WorkoutHeartRatePage> = pages({ cursor -> workoutPath(workoutId, sourceId, "heart-rate", cursor) }, lease, ::decodeWorkoutHeartRate, { it.nextCursor }).also { pages ->
        valid(pages.all { it.workoutId == workoutId && it.sourceId == sourceId } && coherentWorkoutHeartRatePages(pages))
    }
    override suspend fun track(workoutId: String, sourceId: String, lease: SessionLease): List<WorkoutTrackPage> = pages({ cursor -> workoutPath(workoutId, sourceId, "track", cursor) }, lease, ::decodeWorkoutTrack, { it.nextCursor }).also { pages ->
        valid(pages.all { it.workoutId == workoutId && it.sourceId == sourceId } && coherentWorkoutTrackPages(pages))
    }
    override suspend fun trackVersions(workoutId: String, sourceId: String, lease: SessionLease): List<WorkoutTrackVersionsPage> = pages({ cursor -> workoutPath(workoutId, sourceId, "track-summary-versions", cursor, 20) }, lease, ::decodeWorkoutTrackVersions, { it.nextCursor }).also { pages ->
        valid(pages.all { it.workoutId == workoutId && it.sourceId == sourceId } && coherentTrackVersionPages(pages))
    }
    override suspend fun tile(z: Int, x: Int, y: Int, lease: SessionLease): ByteArray {
        require(z in 1..19 && x >= 0 && y >= 0 && x < (1 shl z) && y < (1 shl z))
        var bytes: ByteArray? = null
        val response = withTimeout(30_000) {
            http.stream("/v1/maps/osm/$z/$x/$y.png", lease, mapOf("Accept" to "image/png")) { status, headers, channel ->
                valid(status == 200 && headers["content-type"]?.substringBefore(';')?.trim() == "image/png")
                val maximum = 512 * 1024
                val length = headers["content-length"]?.toLongOrNull()
                valid(length == null || length in 1..maximum.toLong())
                val chunks = mutableListOf<ByteArray>()
                val buffer = ByteArray(16 * 1024)
                var count = 0
                while (true) {
                    val read = channel.readAvailable(buffer)
                    if (read == -1) break
                    if (read == 0) continue
                    valid(read <= maximum - count)
                    chunks += buffer.copyOf(read)
                    count += read
                }
                valid(count > 0 && (length == null || length == count.toLong()))
                val result = ByteArray(count)
                var offset = 0
                chunks.forEach { chunk -> chunk.copyInto(result, offset); offset += chunk.size }
                valid(result.size >= 8 && result.take(8) == listOf(137, 80, 78, 71, 13, 10, 26, 10).map(Int::toByte))
                bytes = result
            }
        }
        if (response !is HttpOutcome.Success) throw ActivityReadException(response)
        return bytes ?: throw ActivityReadException(HttpOutcome.InvalidResponse)
    }
    private suspend fun <T> pages(path: (String?) -> String, lease: SessionLease, decode: (ByteArray) -> T, cursorOf: (T) -> String?): List<T> {
        val result = mutableListOf<T>()
        val seen = mutableSetOf<String>()
        var cursor: String? = null
        do {
            val page = get(path(cursor), lease, decode)
            result += page
            cursor = cursorOf(page)
            if (cursor != null) valid(seen.add(cursor))
        } while (cursor != null)
        return result
    }
    private suspend fun <T> get(path: String, lease: SessionLease, decode: (ByteArray) -> T): T {
        val response = http.request("GET", path, lease = lease)
        if (response !is HttpOutcome.Success) throw ActivityReadException(response)
        return try { decode(response.body) } catch (_: Exception) { throw ActivityReadException(HttpOutcome.InvalidResponse) }
    }
    private fun valid(value: Boolean) { if (!value) throw ActivityReadException(HttpOutcome.InvalidResponse) }
}
private fun ActivityRange.query(): String = "from=${from.encodeURLParameter()}&to=${to.encodeURLParameter()}" + (kind?.let { "&kind=${it.encodeURLParameter()}" } ?: "")
private fun String?.queryCursor(): String = this?.let { "&cursor=${it.encodeURLParameter()}" } ?: ""
private fun workoutPath(workout: String, source: String, resource: String, cursor: String?, limit: Int = 100): String = "/v1/personal/rings/workouts/${workout.encodeURLParameter()}/$resource?source_id=${source.encodeURLParameter()}&limit=$limit${cursor.queryCursor()}"
