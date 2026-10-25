package assistant.mobile.core

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

internal fun contractBytes(name: String): ByteArray =
    checkNotNull(NativeContractTest::class.java.getResourceAsStream("/$name")) { "Missing synthetic contract fixture" }
        .use { it.readBytes() }

class NativeContractTest {
    @Test
    fun nativeSessionUsesSharedServerFixtureAndAcceptsAdditiveFields() {
        val bytes = contractBytes("native-session.json")
        val reply = decodeNativeSession(bytes)
        assertEquals("synthetic-person", reply.user.login)
        assertEquals(reply.device.id, reply.user.deviceId)
        assertEquals("native", reply.session.transport)
        assertEquals(listOf("session.control", "tasks.read", "tasks.write"), reply.scopes)
        val document = NativeJson.parseToJsonElement(bytes.decodeToString()).jsonObject
        val extended = JsonObject(document + ("future_optional_field" to JsonPrimitive(true)))
        assertEquals(reply, decodeNativeSession(extended.toString().encodeToByteArray()))
        assertFalse(reply.toString().contains(reply.credential))
    }

    @Test
    fun nativeSessionRejectsMissingCredentialAndChangedTransportOrBinding() {
        val document = NativeJson.parseToJsonElement(contractBytes("native-session.json").decodeToString()).jsonObject
        assertFailsWith<IllegalArgumentException> {
            decodeNativeSession(JsonObject(document - "credential").toString().encodeToByteArray())
        }
        val session = document.getValue("session").jsonObject
        assertFailsWith<IllegalArgumentException> {
            decodeNativeSession(JsonObject(document + ("session" to JsonObject(session + ("transport" to JsonPrimitive("cookie"))))).toString().encodeToByteArray())
        }
        val user = document.getValue("user").jsonObject
        assertFailsWith<IllegalArgumentException> {
            decodeNativeSession(JsonObject(document + ("user" to JsonObject(user + ("device_id" to JsonPrimitive("different-device"))))).toString().encodeToByteArray())
        }
    }

    @Test
    fun submissionUsesSharedServerFixtureAndAcceptsUnknownOptionalFields() {
        val bytes = contractBytes("submission.json")
        val reply = decodeSubmissionReceipt(bytes)
        assertEquals("lost-response", reply.submissionKey)
        assertEquals("queued", reply.state)
        assertEquals(null, reply.traceId)
        assertTrue(reply.supportedState)
        val document = NativeJson.parseToJsonElement(bytes.decodeToString()).jsonObject
        assertEquals(reply, decodeSubmissionReceipt(JsonObject(document + ("future_optional" to JsonPrimitive(42))).toString().encodeToByteArray()))
    }

    @Test
    fun submissionRejectsInvalidRequiredFieldsAndKeepsUnknownStateUnsupported() {
        val document = NativeJson.parseToJsonElement(contractBytes("submission.json").decodeToString()).jsonObject
        assertFailsWith<IllegalArgumentException> {
            decodeSubmissionReceipt(JsonObject(document - "message_id").toString().encodeToByteArray())
        }
        assertFailsWith<IllegalArgumentException> {
            decodeSubmissionReceipt(JsonObject(document + ("admitted_at" to JsonPrimitive("not-an-instant"))).toString().encodeToByteArray())
        }
        val unknown = decodeSubmissionReceipt(JsonObject(document + ("state" to JsonPrimitive("future-state"))).toString().encodeToByteArray())
        assertEquals("future-state", unknown.state)
        assertFalse(unknown.supportedState)
    }

    @Test
    fun submissionRecognizesExactlyTheServerAdmissionStates() {
        val document = NativeJson.parseToJsonElement(contractBytes("submission.json").decodeToString()).jsonObject
        for (state in listOf("queued", "routing", "processing", "completed", "failed", "clarification")) {
            val reply = decodeSubmissionReceipt(JsonObject(document + ("state" to JsonPrimitive(state))).toString().encodeToByteArray())
            assertTrue(reply.supportedState, state)
        }
        for (state in listOf("running", "cancelled", "future-state")) {
            val reply = decodeSubmissionReceipt(JsonObject(document + ("state" to JsonPrimitive(state))).toString().encodeToByteArray())
            assertEquals(state, reply.state)
            assertFalse(reply.supportedState, state)
        }
    }

    @Test
    fun submissionChecksTraceIdentifierAndIdentifierBounds() {
        val document = NativeJson.parseToJsonElement(contractBytes("submission.json").decodeToString()).jsonObject
        for (traceId in listOf("a".repeat(32), "F".repeat(32))) {
            val reply = decodeSubmissionReceipt(JsonObject(document + ("trace_id" to JsonPrimitive(traceId))).toString().encodeToByteArray())
            assertEquals(traceId, reply.traceId)
        }
        for (traceId in listOf("", "a".repeat(31), "a".repeat(33), "g".repeat(32))) {
            assertFailsWith<IllegalArgumentException> {
                decodeSubmissionReceipt(JsonObject(document + ("trace_id" to JsonPrimitive(traceId))).toString().encodeToByteArray())
            }
        }
        for ((field, value) in listOf("submission_key" to "k".repeat(81), "message_id" to "m".repeat(129))) {
            assertFailsWith<IllegalArgumentException> {
                decodeSubmissionReceipt(JsonObject(document + (field to JsonPrimitive(value))).toString().encodeToByteArray())
            }
        }
    }
}
