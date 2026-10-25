package assistant.mobile.platform.ring

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class RingMeasurementCommandsTest {
    private val owner = RingWorkoutBinding("https://synthetic-ring.example", "person", "ring", "collector",
        "00:11:22:33:44:55", 7, "Europe/Moscow")
    private val workout = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"
    private fun expected() = obj { put("user_id", owner.userId); put("source_id", owner.sourceId); put("binding_generation", owner.grantGeneration) }
    private fun caps(kind: String, status: String = "verified", night: Boolean = false, safe: Boolean = false) = obj {
        put("capabilities", JsonArray(listOf(obj {
            put("kind", kind); put("status", status); put("autonomous_night", night); put("safe_stop_verified", safe)
            put("readable", true); put("writable", true)
            put("supported_intervals_minutes", JsonArray(listOf(JsonPrimitive(1), JsonPrimitive(5))))
        })))
    }
    private fun fails(code: String, action: () -> Unit) {
        try { action(); fail("Expected $code") } catch (failure: RingFailure) { assertEquals(code, failure.code) }
    }
    @Test fun expectedIdentityIsRequiredAndStrippedBeforeCommandParsing() {
        val payload = JsonObject(expected() + ("metric" to JsonPrimitive("heart_rate")))
        val command = RingMeasurementCommands.parse("ring.measurement.measure", RingMeasurementPayload.validate(payload, owner), obj {}).single()
        assertEquals("heart_rate", command.string("metric")); assertEquals("measure_once", command.string("type"))
        for (field in listOf("user_id", "source_id", "binding_generation"))
            fails("invalid_binding") { RingMeasurementPayload.validate(JsonObject(payload - field), owner) }
        fails("invalid_binding") { RingMeasurementPayload.validate(JsonObject(payload + ("binding_generation" to JsonPrimitive("7"))), owner) }
        fails("invalid_binding") { RingMeasurementPayload.validate(JsonObject(payload + ("source_id" to JsonPrimitive("other"))), owner) }
    }
    @Test fun verifiedSchedulesRequireAdvertisedIntervalsAndTypedValues() {
        val native = caps("heart_rate_schedule")
        assertEquals(5, RingMeasurementCommands.parse("ring.measurement.configure", obj { put("hr_interval_minutes", 5) }, native).single().number("interval_minutes"))
        fails("unsupported_interval") { RingMeasurementCommands.parse("ring.measurement.configure", obj { put("hr_interval_minutes", 2) }, native) }
        fails("bad_payload") { RingMeasurementCommands.parse("ring.measurement.configure", obj { put("hr_interval_minutes", "5") }, native) }
    }
    @Test fun separatelyQualifiedReadDoesNotEnableMutationAndNeverUsesASetCommand() {
        val readOnly = obj { put("capabilities", JsonArray(listOf(obj {
            put("kind", "heart_rate_schedule"); put("status", "protocol_unverified")
            put("readable", true); put("writable", false); put("evidence_ref", "synthetic-read-proof")
        }))) }
        assertTrue(RingMeasurementCommands.readable(readOnly, "heart_rate"))
        assertFalse(RingMeasurementCommands.supported(readOnly, "heart_rate_schedule"))
        val read = RingMeasurementCommands.parse("ring.measurement.read", obj {
            put("kinds", JsonArray(listOf(JsonPrimitive("heart_rate"), JsonPrimitive("temperature"))))
        }, readOnly)
        assertEquals(listOf("heart_rate", "temperature"), read.map { it.string("kind") })
        assertTrue(read.all { it.string("type") == "read_settings" && "enabled" !in it && "interval_minutes" !in it })
        fails("bad_payload") { RingMeasurementCommands.parse("ring.measurement.read", obj {
            put("kinds", JsonArray(listOf(JsonPrimitive("heart_rate"), JsonPrimitive("heart_rate"))))
        }, readOnly) }
        assertFalse(RingMeasurementCommands.readable(caps("heart_rate_schedule").let {
            obj { put("capabilities", JsonArray(listOf(JsonObject((it["capabilities"] as JsonArray).single().jsonObject - "readable")))) }
        }, "heart_rate"))
    }
}
