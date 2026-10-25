package assistant.mobile.core.activity

import assistant.mobile.core.FEATURE_NOW
import assistant.mobile.core.FEATURE_ORIGIN
import assistant.mobile.core.FeatureSessionSecrets
import assistant.mobile.core.HttpOutcome
import assistant.mobile.core.NativeHttpClient
import assistant.mobile.core.SessionManager
import assistant.mobile.core.featureRecord
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockEngineConfig
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import kotlin.test.*

/** Transport checks use the same frozen synthetic web fixtures as ActivityReadModelsTest. */
@OptIn(ExperimentalCoroutinesApi::class)
class ActivityReadApiTest {
    @Test fun resolvedReadsEveryCursorPageAndRejectsChangedDecisionBeforePublishing() = runTest {
        for (changed in listOf(false, true)) {
            val record = featureRecord()
            val sessions = SessionManager("synthetic", FEATURE_ORIGIN, FeatureSessionSecrets(record), {}, { FEATURE_NOW })
            sessions.restore()
            val requests = mutableListOf<String?>()
            val root = activityObject(activityFixture("running"))
            val day = root.array("daily").last()
            val report = root.array("series").single().jsonObject
            val http = NativeHttpClient(MockEngine { request ->
                assertEquals(record.credential, request.headers["Authorization"]?.removePrefix("Bearer "))
                assertEquals("running_workouts", request.url.parameters["metric"])
                val cursor = request.url.parameters["cursor"]
                requests += cursor
                val page = JsonObject(root + mapOf("daily" to JsonArray(listOf(day)), "next_cursor" to if (cursor == null) JsonPrimitive("opaque cursor+/") else JsonNull, "fingerprint" to JsonPrimitive(if (changed && cursor != null) "changed" else "synthetic-running-page"), "series" to JsonArray(listOf(if (cursor == null) report else JsonObject(report + ("id" to JsonPrimitive("second")))))))
                respond(page.toString())
            }, FEATURE_ORIGIN, sessions, { FEATURE_NOW })
            try {
                val api = NativeActivityApi(http)
                if (changed) assertEquals(HttpOutcome.InvalidResponse, assertFailsWith<ActivityReadException> { api.resolved(ActivityRange("2026-10-01", "2026-10-06"), "running_workouts", sessions.lease()!!) }.outcome)
                else assertEquals(listOf("workout", "second"), api.resolved(ActivityRange("2026-10-01", "2026-10-06"), "running_workouts", sessions.lease()!!).workouts.map { it.id })
                assertEquals(listOf(null, "opaque cursor+/"), requests)
            } finally { http.close() }
        }
    }
    @Test fun trackCanNeverReadAnotherSourceUnderTheRequestedWorkout() = runTest {
        val sessions = SessionManager("synthetic", FEATURE_ORIGIN, FeatureSessionSecrets(featureRecord()), {}, { FEATURE_NOW })
        sessions.restore()
        val root = activityObject(activityFixture("track"))
        val http = NativeHttpClient(MockEngine { respond(JsonObject(root + ("source_id" to JsonPrimitive("other-source"))).toString()) }, FEATURE_ORIGIN, sessions, { FEATURE_NOW })
        try {
            assertEquals(HttpOutcome.InvalidResponse, assertFailsWith<ActivityReadException> { NativeActivityApi(http).track(root.getValue("workout_id").jsonPrimitive.content, root.getValue("source_id").jsonPrimitive.content, sessions.lease()!!) }.outcome)
        } finally { http.close() }
    }
    @Test fun mapTilesStayOnThePrivateOriginAndEnforceTypeAndStreamingSize() = runTest {
        for (mode in listOf("valid", "oversized", "wrong-type", "bad-signature")) {
            val record = featureRecord()
            val sessions = SessionManager("synthetic", FEATURE_ORIGIN, FeatureSessionSecrets(record), {}, { FEATURE_NOW })
            sessions.restore()
            val png = byteArrayOf(137.toByte(), 80, 78, 71, 13, 10, 26, 10)
            val bytes = when (mode) { "oversized" -> ByteArray(512 * 1024 + 1); "bad-signature" -> "not a png".encodeToByteArray(); else -> png }
            // The fake producer shares the deadline's clock; virtual time must not race IO.
            val http = NativeHttpClient(MockEngine(MockEngineConfig().apply {
                dispatcher = StandardTestDispatcher(testScheduler)
                addHandler { request ->
                    assertEquals("$FEATURE_ORIGIN/v1/maps/osm/3/4/5.png", request.url.toString())
                    assertEquals("Bearer ${record.credential}", request.headers["Authorization"])
                    respond(bytes, HttpStatusCode.OK, headersOf("Content-Type", if (mode == "wrong-type") "image/jpeg" else "image/png"))
                }
            }), FEATURE_ORIGIN, sessions, { FEATURE_NOW })
            try {
                val api = NativeActivityApi(http)
                if (mode == "valid") assertContentEquals(png, api.tile(3, 4, 5, sessions.lease()!!))
                else assertEquals(HttpOutcome.InvalidResponse, assertFailsWith<ActivityReadException> { api.tile(3, 4, 5, sessions.lease()!!) }.outcome)
                assertFailsWith<IllegalArgumentException> { api.tile(3, 8, 5, sessions.lease()!!) }
            } finally { http.close() }
        }
    }
}
