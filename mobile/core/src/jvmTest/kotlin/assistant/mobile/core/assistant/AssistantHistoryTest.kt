package assistant.mobile.core.assistant

import assistant.mobile.core.NativeJson
import assistant.mobile.core.contractBytes
import assistant.mobile.core.decodeSubmissionReceipt
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AssistantHistoryTest {
    @Test
    fun sharedHistoryAndSubmissionContractsKeepServerStatesAndDurableReplies() {
        val history = decodeAssistantHistory(contractBytes("chat-history.json"))
        assertEquals(6, history.messages.size)
        assertEquals(null, history.before)
        val answered = history.messages.single { it.id == "answered" }
        assertEquals("Здравствуйте! Чем помочь?", answered.replies.single().text)
        assertTrue(history.messages.single { it.id == "routing" }.pending)
        assertFalse(history.messages.single { it.id == "clarify" }.pending)
        assertEquals("choose_topic", history.messages.single { it.id == "clarify" }.error)
        assertEquals(1600, history.messages.single { it.id == "photo" }.images.single().width)
        assertEquals(history, decodeAssistantHistory(encodeAssistantHistory(history)))
        val receipt = decodeSubmissionReceipt(contractBytes("submission.json"))
        assertEquals("queued", receipt.state)
        assertTrue(receipt.supportedState)
        // Admission is its own object; no receipt state is copied onto durable history.
        assertEquals("completed", answered.state)
    }

    @Test
    fun additiveFieldsAreAcceptedButInvalidIdentitiesTimestampsAndCursorsAreRejected() {
        val document = NativeJson.parseToJsonElement(contractBytes("chat-history.json").decodeToString()).jsonObject
        val additive = JsonObject(document + ("future_optional" to JsonPrimitive(true)))
        assertEquals(decodeAssistantHistory(contractBytes("chat-history.json")), decodeAssistantHistory(additive.toString().encodeToByteArray()))
        assertFailsWith<IllegalArgumentException> { decodeAssistantHistory(JsonObject(document - "before").toString().encodeToByteArray()) }
        assertFailsWith<IllegalArgumentException> { decodeAssistantHistory(JsonObject(document + ("before" to JsonPrimitive(""))).toString().encodeToByteArray()) }
        assertFailsWith<IllegalArgumentException> {
            decodeAssistantHistory("""{"messages":[{"id":"m","created_at":"yesterday"}],"before":null}""".encodeToByteArray())
        }
        assertFailsWith<IllegalArgumentException> {
            decodeAssistantHistory("""{"messages":[{"id":"","created_at":"2026-10-16T00:00:00Z"}],"before":null}""".encodeToByteArray())
        }
    }

    @Test
    fun optionalMalformedPermissionStaysFalseAndUnknownStateStaysUnsupported() {
        val history = decodeAssistantHistory("""{"messages":[{"id":"m","created_at":"2026-10-16T00:00:00Z","state":"future","can_clarify":"true","text":null,"images":null,"turns":null}],"before":null}""".encodeToByteArray())
        val message = history.messages.single()
        assertFalse(message.canClarify)
        assertFalse(message.pending)
        assertFalse(message.supportedState)
        assertEquals("future", message.state)
        assertEquals("", message.text)
        assertEquals(emptyList(), message.images)
    }

    @Test
    fun chronologicalOrderRetainsMicrosecondsAndSettlesEqualTimeById() {
        val early = message(1).copy(createdAt = "2026-10-16T08:00:00.000100Z")
        val late = message(0).copy(createdAt = "2026-10-16T08:00:00.000200Z")
        assertTrue(assistantMessageOrder.compare(early, late) < 0)
        assertTrue(assistantMessageOrder.compare(early, early.copy(id = "z")) < 0)
    }

    @Test
    fun diskCacheContainsOnlyTheLatestDurableServerPage() {
        val window = AssistantHistoryWindow(listOf(AssistantHistory(range(1, 80), id(1))))
        val cached = checkNotNull(window.cacheHead)
        assertEquals((31..80).map(::id), cached.messages.map { it.id })
        assertEquals(id(31), cached.before)
        assertEquals(null, cached.after)
        assertEquals(null, AssistantHistoryWindow(listOf(AssistantHistory(range(1, 20), null, id(20)))).cacheHead)
    }

    private fun message(index: Int) = AssistantMessage(
        id(index), "2026-10-16T${(8 + index / 60).toString().padStart(2, '0')}:${(index % 60).toString().padStart(2, '0')}:00Z",
        state = "completed", text = "Synthetic $index",
    )
    private fun range(first: Int, last: Int) = (first..last).map(::message)
    private fun id(index: Int) = "m${index.toString().padStart(3, '0')}"
}
