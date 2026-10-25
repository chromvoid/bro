package assistant.mobile.platform.ring

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class RingCaptureFailureTest {
    private fun failed(cause: JsonElement? = null): JsonObject = obj {
        put("status", "failed")
        if (cause != null) put("failure_code", cause)
    }

    @Test fun eachKnownCauseIsPreservedWithoutUsingAnotherCapture() {
        val causes = listOf("response_timeout", "write_failed", "running_ambiguous_time",
            "invalid_running_snapshot", "invalid_legacy_hr_context")
        for (cause in causes) {
            val effect = JsonObject(failed(JsonPrimitive(cause)) + obj {
                put("query_id", "synthetic-workouts"); put("metric", "workouts")
                put("day", 0); put("page", 0)
                put("previous_capture", failed(JsonPrimitive("write_failed")))
            })
            assertEquals(cause, RingCaptureFailure.code(effect))
            assertEquals("synthetic-workouts", effect.string("query_id"))
            assertEquals("workouts", effect.string("metric"))
        }
    }

}
