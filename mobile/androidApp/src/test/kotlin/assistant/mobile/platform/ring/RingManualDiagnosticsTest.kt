package assistant.mobile.platform.ring

import java.time.Instant
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class RingManualDiagnosticsTest {
    private val id = "synthetic-manual-operation"
    private val command = obj { put("type", "measure_once"); put("metric", "heart_rate") }
    private val at = RingManualDiagnostics.Time(Instant.parse("2026-10-10T06:00:00Z"), 1_000, 1_000_123_456)
    private val packet = "6a014b000000000000000000000000b6"
    private fun effect() = obj {
        put("operation_id", id); put("command", command); put("phase", "stopping"); put("stop_reason", "result")
        put("started_at_ms", 500); put("deadline_at_ms", 9_000)
    }
    private fun recorder(events: MutableList<Pair<String, JsonObject>>, current: () -> Boolean = { true },
        clock: () -> RingManualDiagnostics.Time = { at }, append: (String, JsonObject) -> Unit = { kind, payload -> events += kind to payload }) =
        RingManualDiagnostics(id, "heart_rate", "synthetic-source", "synthetic-collector", 1, current, clock, append)

    @Test fun onlyOwnedManualFieldsAndBoundedCoreFramesBecomeEvidence() {
        val events = mutableListOf<Pair<String, JsonObject>>(); val evidence = recorder(events)
        val secrets = obj {
            put("cookie", "synthetic-private-cookie"); put("notification_text", "synthetic-private-notification")
            put("grant", obj { put("token", "synthetic-private-token") }); put("state", "synthetic-private-state")
        }
        evidence.identity(JsonObject(obj { put("model", "qring-r20"); put("hardware", "synthetic-hw"); put("firmware", "synthetic-fw") } + secrets))
        evidence.checkpoint(JsonObject(effect() + secrets))
        evidence.progress(JsonObject(effect() + secrets))
        evidence.progress(JsonObject(effect() + ("operation_id" to JsonPrimitive("stale-operation"))))
        evidence.progress(JsonObject(effect() + ("command" to obj { put("type", "measure_once"); put("metric", "spo2") })))
        evidence.notice("core", byteArrayOf(0x73, 1, 2), at)
        evidence.notice("bulk", byteArrayOf(0x69, 1, 2), at)
        evidence.notice("core", ByteArray(0), at)
        evidence.notice("core", ByteArray(17) { if (it == 0) 0x69 else 0x73 }, at)
        val bytes = packet.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        evidence.notice("core", bytes, at)
        evidence.observation(obj { put("operation_id", id); put("observation", obj {
            put("metric", "heart_rate"); put("value", 75); put("unit", "bpm"); put("received_monotonic_ms", 1_000)
            put("timestamp_basis", "received_monotonic"); put("sample_quality", "valid"); secrets.forEach { (key, value) -> put(key, value) }
        }) })
        val serialized = events.toString()
        assertFalse(serialized.contains("synthetic-private")); assertFalse(serialized.contains("stale-operation"))
        assertEquals(1, events.count { it.first == "manual_native_progress" })
        val notices = events.filter { it.first == "manual_notification_received" }.map { it.second }
        assertEquals(2, notices.size); assertEquals(JsonPrimitive(true), notices.first()["bytes_omitted"])
        assertFalse("An oversized frame must not leak its tail", "bytes_hex" in notices.first())
        assertEquals(packet, notices.last().string("bytes_hex")); assertEquals(16, notices.last().number("byte_count"))
        events.forEach { (_, payload) ->
            assertEquals(id, payload.string("operation_id")); assertEquals("synthetic-source", payload.string("source_id"))
            assertEquals(at.at.toString(), payload.string("observed_at")); assertEquals(1_000_123_456L, payload["observed_monotonic_nanos"]!!.jsonPrimitive.long)
        }
    }

    @Test fun actualPersistCommitsOnceBeforeStoredEvidenceAndFailureHasNoFalseCommittedEvent() {
        val events = mutableListOf<Pair<String, JsonObject>>()
        var writes = 0
        val outcomes = listOf(obj { put("external_id", "synthetic-point"); put("version", 1_000_001); put("status", "new") })
        val evidence = recorder(events)
        assertSame(outcomes, evidence.persistObservation("synthetic-point") {
            assertEquals(listOf("manual_observation_conversion"), events.map { it.first })
            writes++; outcomes
        })
        assertEquals(1, writes); assertEquals("manual_observation_stored", events.last().first)
        events.clear()
        val failure = RingFailure("queue_full", "synthetic-private-error")
        try {
            evidence.persistObservation("synthetic-point") { writes++; throw failure }
            fail("A failed real outbox append must propagate")
        } catch (actual: RingFailure) { assertSame(failure, actual) }
        assertEquals(2, writes); assertEquals(listOf("manual_observation_conversion"), events.map { it.first })
        val brokenDiagnostics = recorder(events, append = { _, _ -> throw IllegalStateException("synthetic-private-journal") })
        assertSame(outcomes, brokenDiagnostics.persistObservation("synthetic-point") { writes++; outcomes })
        assertEquals(3, writes)
    }
}
