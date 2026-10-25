package assistant.mobile.platform.ring

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import kotlinx.serialization.json.*
import java.time.Instant
import java.time.ZoneId
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class RingNativeSmokeTest {
    @Test fun nativeManualHeartRateCrossesProgressStopReceiptAndOwnedCancellationContract() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val origin = "https://synthetic-manual-evidence-${UUID.randomUUID()}.invalid"
        val binding = RingWorkoutBinding(origin, "owner", "source", "collector", "00:11:22:33:44:55", 1, "Europe/Moscow")
        val journal = RingDiagnostics(context, origin)
        val store = RingStore(context, origin)
        val deviceStore = RingDeviceStore(context, origin)
        val linkToken = "synthetic-evidence-link"
        val anchor = Instant.parse("2026-10-10T06:00:00Z")
        var nowMs = 0L
        var evidence: RingManualDiagnostics? = null
        var lastWrite: JsonObject? = null
        var lastRecord: JsonObject? = null
        fun time(now: Long) = RingManualDiagnostics.Time(anchor.plusMillis(now), now, now * 1_000_000L + 123)
        fun beginEvidence(id: String) {
            val session = journal.session()
            evidence = RingManualDiagnostics(id, "heart_rate", binding.sourceId, binding.collectorId, binding.grantGeneration,
                { true }, { time(nowMs) }, { kind, payload -> journal.append(session, kind, payload) })
            evidence!!.admitted()
        }
        store.resetUnreadable(); store.put("grant", obj { put("generation", 1) })
        journal.bind(binding.collectorId)
        val config = """{"generation":1,"timezone":"Europe/Moscow","local_date":"2026-10-10","history_days":1,"clock":{"year":2026,"month":10,"day":10,"hour":9,"minute":0,"second":0,"language":10},"android":{"sdk":36,"model":"Synthetic"}}"""
        val handle = NativeRing.create(config.toByteArray())
        assertNotEquals(0L, handle)
        fun dispatch(type: String, fields: JsonObject = obj {}, now: Long): List<JsonObject> {
            nowMs = now
            // This JNI fixture simulates successful transport completion; it does not prove real radio callbacks.
            if (type == "write_complete") lastWrite?.let { evidence?.writeAwaitReturned(it) }
            val input = obj { put("type", type); put("generation", 1); fields.forEach { (key, value) -> put(key, value) } }
            val effects = (ringJson.parseToJsonElement(NativeRing.dispatch(handle, input.toString().toByteArray(), now)
                .toString(Charsets.UTF_8)) as JsonArray).map { it as JsonObject }
            effects.forEach { effect -> when (effect.string("type")) {
                "control_checkpoint" -> {
                    val recovery = effect["recovery"] as JsonObject
                    deviceStore.putControlRecovery(binding, recovery, linkToken); evidence?.checkpoint(recovery)
                }
                "control_progress" -> evidence?.progress(effect)
                "write" -> { lastWrite = effect; evidence?.writeStarted(effect) }
                "control_observation" -> {
                    evidence?.observation(effect)
                    val observation = effect["observation"] as JsonObject
                    val received = observation["received_monotonic_ms"]!!.jsonPrimitive.long
                    val record = RingManualObservation.encode(binding,
                        RingWorkoutClock(anchor.plusMillis(received), received, "synthetic-evidence-boot"), observation)!!
                    lastRecord = record
                    evidence!!.persistObservation(record.string("external_id")!!) {
                        store.append(listOf(record), obj { put("completed_queries", JsonArray(emptyList())) })
                    }
                }
                "control_result" -> {
                    evidence?.terminal(effect, stored = false); deviceStore.controlResult(binding, effect, linkToken)
                    evidence?.terminal(effect, stored = true); evidence?.returned(effect)
                }
            } }
            return effects
        }
        fun notify(opcode: Int, payload: ByteArray, now: Long): List<JsonObject> {
            val bytes = ByteArray(16); bytes[0] = opcode.toByte(); payload.copyInto(bytes, 1)
            bytes[15] = bytes.take(15).sumOf { it.toInt() and 0xff }.toByte()
            evidence?.notice("core", bytes, time(now))
            return dispatch("notification", obj { put("channel", "core"); put("bytes_hex", bytes.joinToString("") { "%02x".format(it) }) }, now)
        }
        val command = obj { put("type", "measure_once"); put("metric", "heart_rate") }
        fun request(id: String, now: Long) = dispatch("control_request", obj { put("request", obj {
            put("operation_id", id); put("command", command)
        }) }, now)
        try {
            dispatch("connected", now = 900)
            val identity = obj { put("model", "qring-r20"); put("hardware", "RF20_V2.1"); put("firmware", "RF20_2.10.07_260131") }
            deviceStore.verifiedIdentity(binding, identity, linkToken)
            val capabilities = dispatch("control_identity", obj { put("identity", identity) }, 950)
                .single { it.string("type") == "control_capabilities" }
            assertTrue(RingMeasurementCommands.supported(capabilities, "manual_heart_rate"))
            assertFalse(RingMeasurementCommands.capability(capabilities, "manual_heart_rate")!!.bool("safe_stop_verified")!!)
            for (metric in listOf("spo2", "temperature", "hrv", "stress")) assertFalse(RingMeasurementCommands.supported(capabilities, "manual_$metric"))
            val id = "synthetic-manual-heart-rate"
            beginEvidence(id); evidence!!.identity(identity)
            val beginning = request(id, 1_000)
            val start = beginning.single { it.string("type") == "control_progress" }
            assertEquals(id, start.string("operation_id")); assertEquals(command, start["command"])
            assertEquals("starting", start.string("phase")); assertEquals(JsonNull, start["stop_reason"])
            assertEquals(1_000L, start["started_at_ms"]!!.jsonPrimitive.long)
            assertEquals(46_000L, start["deadline_at_ms"]!!.jsonPrimitive.long)
            val measuring = dispatch("write_complete", obj { put("success", true) }, 1_001)
            assertEquals("measuring", measuring.single { it.string("type") == "control_progress" }.string("phase"))
            val zero = notify(0x69, byteArrayOf(1, 0, 0), 2_000)
            assertFalse(zero.any { it.string("type") in setOf("control_result", "control_observation") })
            val stopping = notify(0x69, byteArrayOf(1, 0, 75), 30_000)
            assertEquals("result", stopping.single { it.string("type") == "control_progress" }.string("stop_reason"))
            assertFalse(stopping.any { it.string("type") == "control_observation" })
            val late = notify(0x69, byteArrayOf(1, 0, 76), 30_100)
            assertFalse(late.any { it.string("type") in setOf("control_observation", "control_result") })
            val queuedFinal = notify(0x6a, byteArrayOf(1, 75), 30_300)
            assertFalse(queuedFinal.any { it.string("type") in setOf("control_observation", "control_result") })
            val completed = dispatch("write_complete", obj { put("success", true) }, 30_400)
            val sample = completed.single { it.string("type") == "control_observation" }
            val result = completed.single { it.string("type") == "control_result" }
            assertEquals(id, sample.string("operation_id")); assertEquals(id, result.string("operation_id"))
            assertEquals(command, result["command"]); assertEquals("verified", result.string("status"))
            assertEquals("measurement_received", result.string("reason"))
            assertEquals(75.0, (sample["observation"] as JsonObject)["value"]!!.jsonPrimitive.double, 0.0)
            assertEquals(30_300L, (sample["observation"] as JsonObject)["received_monotonic_ms"]!!.jsonPrimitive.long)
            assertTrue(completed.indexOf(sample) < completed.indexOf(result))
            val duplicate = store.append(listOf(lastRecord!!), obj { put("completed_queries", JsonArray(emptyList())) })
            evidence!!.observationStored(duplicate)
            assertEquals("deduplicated", duplicate.single().string("status")); assertEquals(1, store.count())
            val cancelledId = "synthetic-cancelled-heart-rate"
            beginEvidence(cancelledId)
            request(cancelledId, 40_000); dispatch("write_complete", obj { put("success", true) }, 40_001)
            evidence!!.cancellationRequested()
            val cancel = dispatch("control_cancel", obj { put("operation_id", cancelledId) }, 40_100)
            assertEquals("cancelled", cancel.single { it.string("type") == "control_progress" }.string("stop_reason"))
            dispatch("write_complete", obj { put("success", true) }, 40_101)
            val cancelled = notify(0x6a, byteArrayOf(1, 75), 40_200)
            assertFalse(cancelled.any { it.string("type") == "control_observation" })
            val receipt = cancelled.single { it.string("type") == "control_result" }
            assertEquals(cancelledId, receipt.string("operation_id")); assertEquals(command, receipt["command"])
            assertEquals("measurement_cancelled", receipt.string("reason"))
            assertFalse(dispatch("control_cancel", obj { put("operation_id", id) }, 40_300).any { it.string("type") == "write" })
            val unknownId = "synthetic-unknown-heart-rate"
            beginEvidence(unknownId)
            request(unknownId, 50_000); dispatch("write_complete", obj { put("success", true) }, 50_001)
            dispatch("deadline", now = 95_000); dispatch("write_complete", obj { put("success", true) }, 95_001)
            val unknown = dispatch("deadline", now = 103_001).single { it.string("type") == "control_result" }
            assertEquals("unknown", unknown.string("status")); assertTrue(deviceStore.blocked(binding))
            assertEquals(1, store.count())
            val rows = journal.export().decodeToString().lineSequence().filter(String::isNotBlank)
                .map { ringJson.parseToJsonElement(it).jsonObject }.drop(1).toList()
            fun operation(id: String) = rows.filter { (it["payload"] as JsonObject).string("operation_id") == id }
            val successRows = operation(id); val kinds = successRows.map { it.string("kind") }
            for ((before, after) in listOf("manual_checkpoint_stored" to "manual_write_started",
                "manual_write_started" to "manual_write_await_returned", "manual_notification_received" to "manual_decoder_observation",
                "manual_decoder_observation" to "manual_observation_stored", "manual_observation_stored" to "manual_native_terminal",
                "manual_native_terminal" to "manual_terminal_stored")) {
                val beforeIndex = kinds.indexOf(before); val afterIndex = kinds.indexOf(after)
                assertTrue("Missing $before", beforeIndex >= 0); assertTrue("Missing $after", afterIndex >= 0)
                assertTrue("$before must precede $after", beforeIndex < afterIndex)
            }
            assertEquals(1, successRows.map { it.string("session_id") }.distinct().size)
            val stored = successRows.filter { it.string("kind") == "manual_observation_stored" }.map {
                ((it["payload"] as JsonObject)["records"] as JsonArray).single().jsonObject }
            assertEquals(listOf("new", "deduplicated"), stored.map { it.string("status") })
            assertEquals(listOf(1_000_001L, 1_000_001L), stored.map { it["version"]!!.jsonPrimitive.long })
            assertEquals(lastRecord!!.string("external_id"), stored.first().string("external_id"))
            val finalNotice = successRows.last { it.string("kind") == "manual_notification_received" }["payload"] as JsonObject
            assertEquals(anchor.plusMillis(30_300).toString(), finalNotice.string("observed_at"))
            assertEquals(30_300_000_123L, finalNotice["observed_monotonic_nanos"]!!.jsonPrimitive.long)
            assertFalse(operation(cancelledId).any { it.string("kind") == "manual_observation_stored" })
            assertFalse(operation(unknownId).any { it.string("kind") == "manual_observation_stored" })
            assertEquals("unknown", (operation(unknownId).single { it.string("kind") == "manual_terminal_stored" }["payload"] as JsonObject).string("status"))
        } finally {
            NativeRing.destroy(handle); deviceStore.clear(binding); store.resetUnreadable(); store.close()
            journal.clear(); journal.close()
        }
    }
    @Test fun nativeRunningSnapshotReplaysThroughTheAndroidReportContract() {
        val config = """{"generation":1,"timezone":"Europe/Moscow","local_date":"2026-10-06","history_days":1,"collect_running_steps":true,"clock":{"year":2026,"month":10,"day":6,"hour":12,"minute":0,"second":0,"language":10},"android":{"sdk":36,"model":"Synthetic"}}"""
        val handle = NativeRing.create(config.toByteArray())
        assertNotEquals(0L, handle)
        fun dispatch(type: String, fields: JsonObject = obj {}, now: Long = 100): List<JsonObject> {
            val input = obj { put("type", type); put("generation", 1); fields.forEach { (key, value) -> put(key, value) } }
            return (ringJson.parseToJsonElement(NativeRing.dispatch(handle, input.toString().toByteArray(), now)
                .toString(Charsets.UTF_8)) as JsonArray).map { it as JsonObject }
        }
        fun packet(opcode: Int, payload: ByteArray = byteArrayOf()): String {
            val bytes = ByteArray(16); bytes[0] = opcode.toByte(); payload.copyInto(bytes, 1)
            bytes[15] = bytes.take(15).sumOf { it.toInt() and 0xff }.toByte()
            return bytes.joinToString("") { "%02x".format(it) }
        }
        fun notify(opcode: Int, payload: ByteArray = byteArrayOf(), proof: JsonObject? = null, now: Long = 100) =
            dispatch("notification", obj {
                put("channel", "core"); put("bytes_hex", packet(opcode, payload))
                proof?.let { put("running_clock", it) }
            }, now)
        try {
            dispatch("connected"); dispatch("sync")
            for (kind in listOf("firmware", "hardware")) dispatch("version", obj { put("kind", kind); put("value", "synthetic") })
            for (opcode in listOf(1, 4)) {
                dispatch("write_complete", obj { put("success", true) })
                val payload = if (opcode == 1) "0100000200000000010020000030".chunked(2).map { it.toInt(16).toByte() }.toByteArray() else byteArrayOf()
                val effects = notify(opcode, payload)
                if (opcode == 1) {
                    val declaration = effects.single { it.string("type") == "declared_capabilities" }
                    assertEquals(100L, declaration["received_monotonic_ms"]!!.jsonPrimitive.long)
                    val fields = declaration["declaration"] as JsonObject
                    assertEquals("declared_only", fields.string("validation_status"))
                    assertEquals("0100000200000000010020000030", fields.string("payload_hex"))
                    assertTrue((fields["flags"] as JsonObject).bool("hrv") == true)
                }
            }
            dispatch("write_complete", obj { put("success", true) })
            val capabilityQuery = notify(3, byteArrayOf(70, 0))
            assertTrue(capabilityQuery.any { it.string("query_id") == "declared_capabilities:core_3c" &&
                it.string("bytes_hex") == "3c00000000000000000000000000003c" })
            dispatch("write_complete", obj { put("success", true) })
            val running = notify(0x3c, "00ac273000000010000000000000".chunked(2).map { it.toInt(16).toByte() }.toByteArray())
            val support = running.single { it.string("type") == "declared_capabilities" }["declaration"] as JsonObject
            assertEquals(0x3c, support.number("opcode")); assertEquals("declared_only", support.string("validation_status"))
            assertTrue((support["flags"] as JsonObject).bool("gesture") == true)
            assertFalse((support["flags"] as JsonObject).bool("interval_temperature") == true)
            assertFalse(running.any { it.string("type") == "observation" })
            assertTrue(running.any { it.string("query_id") == "running_steps:0:0" })
            val zone = ZoneId.of("Europe/Moscow")
            val at = Instant.parse("2026-10-06T09:00:00Z")
            val proof = RingRunningClock(zone, at, 100, at, 100).proof(at.plusMillis(100), 200)
            val held = notify(72, byteArrayOf(0, 31, 64, 0, 2, 0, 0, 1, 64, 0, 0, 55, 0, 42), proof, 200)
            assertFalse(held.any { it.string("type") == "observation" }) // Ring response and ATT completion are independent.
            val accepted = dispatch("write_complete", obj { put("success", true) }, 200)
            val observations = accepted.filter { it.string("type") == "observation" }.map { it["observation"] as JsonObject }
            assertEquals(3, observations.size)
            assertEquals(setOf("running_steps", "active_energy", "distance"), observations.map { it.string("metric") }.toSet())
            assertTrue(observations.all { it.number("opcode") == 0x48 && it.string("source") == "oudmon_sdk_core" })
            val raw = observations.single { it.string("metric") == "running_steps" }
            val record = RingActivityReport.runningSnapshot(raw, zone, "RF20", "R20")!!
            assertEquals("qring-r20:running_steps:2026-10-06", record.string("external_id"))
            assertEquals(512, ((record["report"] as JsonObject)["data"] as JsonObject).number("value"))
            assertEquals("2026-10-06T09:00:00.100Z", (record["report"] as JsonObject).string("observed_at"))
            for ((metric, value, unit) in listOf(Triple("active_energy", "0.32", "kcal"), Triple("distance", "55", "m"))) {
                val quantity = RingActivityReport.quantitySnapshot(observations.single { it.string("metric") == metric },
                    zone, "RF20", "R20", at.plusSeconds(1))!!
                assertEquals("qring-r20:$metric:snapshot:2026-10-06", quantity.string("external_id"))
                val report = quantity["report"] as JsonObject
                assertEquals(metric, report.string("kind"))
                assertEquals("2026-10-06T09:00:00.100Z", report.string("observed_at"))
                val data = report["data"] as JsonObject
                assertEquals(value, data.string("value")); assertEquals(unit, data.string("unit"))
            }
            assertTrue(accepted.any { it.string("query_id") == "steps:0:0" })
        } finally { NativeRing.destroy(handle); NativeRing.destroy(handle) }
    }
    @Test fun nativeLibraryLoadsAndSessionOwnsItsBuffers() {
        assertEquals(1, NativeRing.apiVersion())
        val config = """{"generation":1,"timezone":"Europe/Moscow","local_date":"2026-10-05","history_days":30,"clock":{"year":2026,"month":10,"day":5,"hour":12,"minute":0,"second":0,"language":10},"android":{"sdk":36,"model":"Synthetic"}}"""
        val handle = NativeRing.create(config.toByteArray())
        assertNotEquals(0L, handle)
        try {
            val bytes = NativeRing.dispatch(handle, """{"type":"connected","generation":1}""".toByteArray(), 0)
            assertTrue(Json.parseToJsonElement(bytes.toString(Charsets.UTF_8)) is JsonArray)
            val stale = NativeRing.dispatch(handle, """{"type":"notification","generation":0,"channel":"core","bytes_hex":"03000000000000000000000000000003"}""".toByteArray(), 1)
            assertTrue(Json.parseToJsonElement(stale.toString(Charsets.UTF_8)) is JsonArray)
        } finally { NativeRing.destroy(handle); NativeRing.destroy(handle) }
    }
}
