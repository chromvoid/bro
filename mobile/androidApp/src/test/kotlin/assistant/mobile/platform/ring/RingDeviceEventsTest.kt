package assistant.mobile.platform.ring

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class RingDeviceEventsTest {
    private val origin = "https://synthetic.invalid"
    private val owner = RingWorkoutBinding(origin, "user", "source", "collector", "AA:BB:CC:DD:EE:FF", 1, "UTC")
    private val source = sha256("$origin\n${owner.address}")
    private val identity = obj { put("model", "qring-r20"); put("hardware", "RF20_V2.1"); put("firmware", "RF20_2.10.07_260131") }
    private fun event(type: Int, at: Long = 10, generation: Long = 1, qualified: Boolean = false): JsonObject = obj {
        put("namespace", "core"); put("opcode", 115); put("data_type", type); put("profile_id", "oudmon-r20")
        put("decoder_id", "soul1214-core73-device-notify-v1"); put("identity", identity); put("generation", generation)
        put("physical_source_id", source); put("received_monotonic_ms", at)
        put("raw_payload_hex", if (type == 56) "38003412" + "00".repeat(10) else type.toString(16) + "00".repeat(13))
        put("qualification", if (qualified) "verified" else "protocol_unverified"); put("source_evidence_ref", "synthetic-source")
        put("physical_evidence_ref", if (qualified) JsonPrimitive("synthetic-only") else JsonNull)
        val meaning = obj { put("kind", if (type == 56) "tasbih_count" else "game_tap"); if (type == 56) put("count", 0x1234) }
        put("candidate", meaning); put("resolved", if (qualified) meaning else JsonNull)
    }
    private fun accept(state: RingDeviceEvents, value: JsonObject, generation: Long = 1, physicalSource: String = source): Boolean =
        state.accept(owner, generation, physicalSource, identity, value, 100, "2026-10-09T00:00:00Z")
    @Test fun candidateCountDoesNotBecomeHardwareCountOrOverwriteTheLocalReset() {
        val state = RingDeviceEvents(origin); state.localStart()
        assertTrue(accept(state, event(56)))
        val counter = state.snapshot()["tasbih"] as JsonObject
        assertEquals(JsonNull, counter["count"]); assertEquals(JsonPrimitive(0x1234), counter["candidate_count"])
        assertEquals(JsonPrimitive(0), counter["local_display_count"]); assertEquals("local_reset", counter.string("display_basis"))
        assertFalse(accept(state, event(56)))
        assertTrue(accept(state, event(56, at = 11, qualified = true)))
        assertEquals(JsonPrimitive(0x1234), (state.snapshot()["tasbih"] as JsonObject)["count"])
        state.reset(); assertEquals(JsonNull, (state.snapshot()["tasbih"] as JsonObject)["count"])
    }
    @Test fun wrongGenerationSourceIdentityNamespaceAndStaleEventAreRejected() {
        val state = RingDeviceEvents(origin)
        assertFalse(accept(state, event(56), generation = 2))
        assertFalse(accept(state, event(56), physicalSource = "a".repeat(64)))
        assertFalse(accept(state, JsonObject(event(56) + ("namespace" to JsonPrimitive("bulk")))))
        assertFalse(state.accept(owner, 1, source, obj {}, event(56), 100, "now"))
        assertFalse(state.accept(owner, 1, source, identity, event(56), 10_000, "now"))
        assertFalse(accept(state, JsonObject(event(56) + ("resolved" to obj { put("kind", "tasbih_count"); put("count", 0x1234) }))))
        assertFalse(accept(state, JsonObject(event(56, qualified = true) + ("physical_evidence_ref" to JsonNull))))
    }
}
