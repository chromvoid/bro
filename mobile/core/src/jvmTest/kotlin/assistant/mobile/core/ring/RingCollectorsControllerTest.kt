package assistant.mobile.core.ring

import assistant.mobile.core.FEATURE_NOW
import assistant.mobile.core.FEATURE_ORIGIN
import assistant.mobile.core.FeatureSessionSecrets
import assistant.mobile.core.NativeHttpClient
import assistant.mobile.core.NativeJson
import assistant.mobile.core.SessionManager
import assistant.mobile.core.featureBody
import assistant.mobile.core.featureRecord
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockEngineConfig
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpMethod
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class RingCollectorsControllerTest {
    @Test fun expiredLeaseClearsPrivateSourcesAndDiscardsADelayedRead() = runTest {
        val fixture = RingCollectorsFixture(this)
        try {
            fixture.start()
            val gate = CompletableDeferred<Unit>()
            fixture.listGate = gate
            fixture.payload = ringCollectorsPayload(source = "source-from-expired-lease")
            val pending = async { fixture.controller.refresh() }
            runCurrent()
            assertFalse(pending.isCompleted)
            fixture.sessions.expire(fixture.sessions.lease()!!)
            runCurrent()
            assertNull(fixture.controller.state.value)
            gate.complete(Unit)
            pending.await()
            runCurrent()
            assertNull(fixture.controller.state.value)
            assertEquals(2, fixture.requests.size)
        } finally { fixture.close() }
    }

    @Test fun lostDisableResponseRereadsTheSourceWithoutRepeatingTheWrite() = runTest {
        val fixture = RingCollectorsFixture(this)
        try {
            fixture.start()
            fixture.requests.clear()
            fixture.loseDisableResponse = true
            assertFalse(fixture.controller.disable(COLLECTOR))
            val requests = fixture.requests.toList()
            assertEquals(listOf(HttpMethod.Post, HttpMethod.Get), requests.map { it.method })
            val write = requests.first()
            assertEquals("/v1/personal/rings/$COLLECTOR/disable", write.url.encodedPath)
            assertEquals(listOf(fixture.record.namespace.userId), write.headers.getAll("X-Expected-User"))
            assertEquals("Bearer ${fixture.record.credential}", write.headers["Authorization"])
            assertNull(write.headers["Cookie"])
            assertNull(write.headers["Idempotency-Key"], "Disable has no receipt lookup and must not be retried as an outbox operation")
            assertEquals("{}", featureBody(write.body).decodeToString())
            val state = fixture.controller.state.value!!
            assertFalse(state.items.single().enabled)
            assertEquals("disabled", state.items.single().status)
            assertNull(state.busyCollector)
            assertNotNull(state.error)
            fixture.controller.refresh()
            fixture.sessions.restore()
            runCurrent()
            assertFalse(fixture.controller.disable(COLLECTOR))
            assertEquals(1, fixture.requests.count { it.method == HttpMethod.Post })
        } finally { fixture.close() }
    }

    @Test fun malformedConfirmedReadbackAndCapabilityEvidenceCannotReplaceTheLastGoodRead() = runTest {
        val fixture = RingCollectorsFixture(this)
        try {
            fixture.start()
            val previous = fixture.controller.state.value!!.items
            val root = NativeJson.parseToJsonElement(ringCollectorsPayload()).jsonObject
            val ring = root.getValue("rings").jsonArray.single().jsonObject
            val device = ring.getValue("device_state").jsonObject
            val settings = device.getValue("last_confirmed_settings").jsonObject
            val setting = settings.getValue("heart_rate").jsonObject
            val declaration = device.getValue("declared_capabilities").jsonObject.getValue("core_01").jsonObject
            val malformed = listOf(
                "last_confirmed_settings" to JsonObject(mapOf("heart_rate" to JsonObject(setting + ("readback" to JsonPrimitive(false))))),
                "capabilities" to JsonObject(mapOf("temperature" to JsonObject(mapOf("status" to JsonPrimitive("verified"))))),
                "declared_capabilities" to JsonObject(mapOf("core_99" to declaration)),
                "declared_capabilities" to JsonObject(mapOf("core_01" to JsonObject(declaration + ("validation_status" to JsonPrimitive("verified"))))),
            )
            for ((field, invalid) in malformed) {
                fixture.payload = JsonObject(root + ("rings" to JsonArray(listOf(
                    JsonObject(ring + ("device_state" to JsonObject(device + (field to invalid)))),
                )))).toString()
                fixture.controller.refresh()
                val state = fixture.controller.state.value!!
                assertEquals(previous, state.items, "Invalid $field must not replace the last confirmed source")
                assertNotNull(state.error, "Invalid $field must be reported")
                assertFalse(state.loading)
            }
            assertEquals(5, fixture.requests.size)
            assertTrue(fixture.requests.all { it.method == HttpMethod.Get })
        } finally { fixture.close() }
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
private class RingCollectorsFixture(private val test: TestScope) {
    val record = featureRecord().let { it.copy(identity = it.identity.copy(scopes = listOf("personal.read", "personal.write"))) }
    private val secrets = FeatureSessionSecrets(record)
    val sessions = SessionManager("synthetic", FEATURE_ORIGIN, secrets, {}, { FEATURE_NOW })
    val requests = mutableListOf<HttpRequestData>()
    var payload = ringCollectorsPayload()
    var listGate: CompletableDeferred<Unit>? = null
    var loseDisableResponse = false
    val http = NativeHttpClient(MockEngine(MockEngineConfig().apply {
        dispatcher = StandardTestDispatcher(test.testScheduler)
        addHandler { request ->
            requests += request
            assertEquals("synthetic.invalid", request.url.host)
            assertEquals("Bearer ${record.credential}", request.headers["Authorization"])
            assertEquals(listOf(record.namespace.userId), request.headers.getAll("X-Expected-User"))
            when {
                request.method == HttpMethod.Get && request.url.encodedPath == "/v1/personal/rings" -> {
                    val captured = payload
                    listGate?.await()
                    respond(captured)
                }
                request.method == HttpMethod.Post && request.url.encodedPath == "/v1/personal/rings/$COLLECTOR/disable" -> {
                    payload = ringCollectorsPayload(enabled = false)
                    if (loseDisableResponse) throw IOException("Synthetic lost response after disable admission")
                    respond("{\"disabled\":true}")
                }
                else -> error("Unexpected collector request: ${request.method.value} ${request.url.encodedPath}")
            }
        }
    }), FEATURE_ORIGIN, sessions, { FEATURE_NOW })
    val controller = RingCollectorsController(test.backgroundScope, sessions, http)
    suspend fun start() {
        sessions.restore()
        controller.activate("devices")
        test.runCurrent()
        assertEquals("source-initial", controller.state.value!!.items.single().sourceId)
    }
    fun close() { controller.close(); http.close() }
}

private const val COLLECTOR = "synthetic-ring-collector"

/** The existing web Rings wire schema, with independent receipt, capability and capture evidence. */
private fun ringCollectorsPayload(source: String = "source-initial", enabled: Boolean = true): String = """
    {"rings":[{
      "collector_id":"$COLLECTOR","source_id":"$source","name":"Synthetic ring","profile":"qring-r20",
      "generation":1,"enabled":$enabled,"last_received_at":"2026-10-16T11:59:00Z","cursor":{"steps":7},
      "error":null,"status":"${if (enabled) "connected" else "disabled"}",
      "device_state":{
        "observed_at":"2026-10-15T12:00:00Z","received_at":"2026-10-16T11:59:00Z","revision":7,"stale":true,
        "battery_percent":47,"charging":false,"battery_observed_at":"2026-10-15T12:00:00Z",
        "charging_observed_at":"2026-10-16T11:59:00Z","battery_stale":true,"charging_stale":false,
        "firmware":"synthetic-fw","hardware":"synthetic-hw",
        "capabilities":{"heart_rate":{"status":"supported","readable":true,"writable":true,"evidence_ref":"receipt:hr"},
          "temperature":{"status":"unknown","reason":"protocol_unverified"}},
        "declared_capabilities":{"core_01":{"captured_at":"2026-10-16T11:59:00Z","opcode":1,"payload_hex":"0100",
          "flags":{"temperature":true},"source_values":{"temperature":1},"identity":{"hardware":"synthetic-hw","firmware":"synthetic-fw"},
          "decoder_version":"synthetic-v1","evidence_ref":"receipt:declaration","validation_status":"declared_only"}},
        "last_confirmed_settings":{"heart_rate":{"value":{"enabled":true,"interval_minutes":10},
          "confirmed_at":"2026-10-15T12:00:00Z","readback":true,"action":"read","evidence_ref":"receipt:hr"}}
      },
      "metrics":[{
        "metric":"steps","status":"available","last_measured_at":"2026-10-16T11:58:00Z","last_received_at":"2026-10-16T11:59:00Z",
        "coverage":{"status":"partial","shape":"day_counter_snapshot","start_at":"2026-10-16T00:00:00Z",
          "end_at":"2026-10-16T11:58:00Z","quality":["partial_day"]},"import_status":"imported","reason":null,
        "last_capture_at":"2026-10-16T11:58:00Z","capture_status":"complete","capture_scope":"day",
        "capture_date":"2026-10-16","capture_context":"background","capture_error_code":null,"raw_report_count":0
      },{
        "metric":"temperature","status":"unverified","last_measured_at":null,"last_received_at":"2026-10-16T11:59:00Z",
        "coverage":{"status":"unknown","shape":"raw_report","start_at":null,"end_at":null,"quality":[]},
        "import_status":"raw_only","reason":"semantic_unknown","last_capture_at":"2026-10-16T11:58:00Z",
        "capture_status":"complete","capture_scope":"history","capture_date":null,"capture_context":"background",
        "capture_error_code":null,"raw_report_count":1,"original_kind":"temperature_raw"
      }]
    }]}
""".trimIndent()
