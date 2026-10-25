package assistant.mobile.core.activity

import kotlinx.serialization.json.*
import kotlin.test.*

/** Synthetic fixtures and adapted cases: runningFixtures.ts, Running.test.ts, ExerciseProgress.test.ts,
 * charts.test.ts, WorkoutHeartRateHistory.test.ts, WorkoutTrackHistory.test.ts, workoutTrack.test.ts and tests/test_activity.py.
 * Fixture values retain their source meanings; no personal records or credentials are copied. */
class ActivityReadModelsTest {
    @Test fun selectedReportRevisionUsesTheServerDayAndUnknownQuantitiesRemainUnknown() {
        val root = activityObject(activityFixture("running"))
        val report = root.array("series").single().jsonObject
        val changed = JsonObject(report + mapOf("local_date" to JsonPrimitive("2026-10-05"), "timezone" to JsonPrimitive("UTC"), "data" to buildJsonObject { put("sport_type", "running"); put("distance_raw", 900); put("steps", 0) }))
        val page = decodeActivityResolved(JsonObject(root + ("series" to JsonArray(listOf(changed)))).bytes())
        val workout = page.workouts.single()
        assertEquals("2026-10-06", workout.date)
        assertEquals("UTC", workout.timezone)
        assertEquals(0.0, workout.steps)
        assertNull(workout.distanceMeters)
        assertNull(workout.durationSeconds)
    }
    @Test fun dynamicsDoNotTurnMissingOrConflictingDaysIntoZero() {
        val history = decodeActivityHistory("""{"from":"2026-10-01","to":"2026-10-04","records":[],"daily":[{"date":"2026-10-01","kind":"steps","status":"available","value":"0","target":null},{"date":"2026-10-02","kind":"steps","status":"no_data","value":null,"target":null},{"date":"2026-10-03","kind":"steps","status":"conflict","value":"500","target":null},{"date":"2026-10-04","kind":"steps","status":"available","value":"8000","target":null}]}""".encodeToByteArray())
        assertEquals(listOf(0.0, 8000.0), dailyMetricPoints(history.daily, "steps").map { it.y })
        assertEquals("2026-09-30", activityRange("2026-10-06", 7).from)
        assertEquals("2026-09-07", activityRange("2026-10-06", 30).from)
    }
}
internal fun activityFixture(name: String): ByteArray = requireNotNull(ActivityReadModelsTest::class.java.getResourceAsStream("/activity/$name.json")).use { it.readBytes() }
internal fun JsonObject.array(name: String): JsonArray = getValue(name).jsonArray
internal fun JsonObject.bytes(): ByteArray = toString().encodeToByteArray()
