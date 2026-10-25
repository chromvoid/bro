package assistant.mobile.platform.ring

import java.time.Instant
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class RingManualMeasurementTest {
    private val command = obj { put("type", "measure_once"); put("metric", "heart_rate") }
    private val anchor = RingWorkoutClock(Instant.parse("2026-10-10T06:00:00Z"), 1_000, "synthetic-boot")
    private val measurement = RingManualMeasurement("synthetic-manual-operation", command, anchor)
    private fun progress(phase: String, start: Long = 1_500, deadline: Long = 46_500, reason: String? = null) = obj {
        put("operation_id", measurement.operationId); put("command", command); put("phase", phase)
        put("started_at_ms", start); put("deadline_at_ms", deadline)
        put("stop_reason", reason?.let(::JsonPrimitive) ?: JsonNull)
    }
    @Test fun staleOperationWrongMetricAndMalformedDeadlinesCannotReplaceCurrentProgress() {
        assertNull(measurement.progress(JsonObject(progress("measuring") + ("operation_id" to JsonPrimitive("old-operation")))))
        assertNull(measurement.progress(JsonObject(progress("measuring") + ("command" to obj {
            put("type", "measure_once"); put("metric", "spo2")
        }))))
        assertNull(measurement.progress(progress("measuring", start = 999)))
        assertNull(measurement.progress(progress("stopping", deadline = 54_501, reason = "timeout")))
        assertNull(measurement.progress(progress("stopping", reason = "unknown")))
        assertNull(measurement.progress(progress("stopping")))
        assertNull(measurement.progress(progress("measuring", reason = "result")))
        assertEquals("cancelled", measurement.progress(progress("starting", reason = "cancelled"))!!.stopReason)
        assertNull(measurement.progress(JsonObject(progress("measuring") + ("deadline_at_ms" to JsonPrimitive("46500")))))
    }
    @Test fun cancellationRequiresExactCurrentOperationAndNoArbitraryControlFields() {
        val payload = obj { put("operation_id", measurement.operationId) }
        assertEquals(measurement.operationId, RingMeasurementPayload.cancelOperation(payload, measurement.operationId))
        for (id in listOf(null, "other-operation")) {
            try { RingMeasurementPayload.cancelOperation(payload, id); fail("Accepted stale cancellation") }
            catch (failure: RingFailure) { assertEquals("stale_measurement", failure.code) }
        }
        try {
            RingMeasurementPayload.cancelOperation(JsonObject(payload + ("bytes_hex" to JsonPrimitive("6a01"))), measurement.operationId)
            fail("Accepted a page-supplied wire command")
        } catch (failure: RingFailure) { assertEquals("bad_payload", failure.code) }
        try { RingMeasurementPayload.cancelOperation(obj { put("operation_id", 1) }, "1"); fail("Accepted numeric operation") }
        catch (failure: RingFailure) { assertEquals("bad_payload", failure.code) }
    }
}
