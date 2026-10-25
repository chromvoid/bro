package assistant.mobile.core.assistant

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AssistantResultsTest {
    @Test
    fun failedAnswerKeepsAgentTextAndConfirmedEffects() {
        val reply = reply("""{
          "reply":"Записал.","error":"model_unavailable","question":"Секретная диагностика",
          "execution":{"effects":"confirmed","completion":"partial","safe_to_resubmit":false},
          "services":[
            {"service":"nutrition","status":"completed","receipt_text":"Йогурт 150 г — 90 ккал","operation_key":"key",
             "receipts":[{"operation_id":"op","kind":"entry.add_many","future":true}],
             "effects":[{"operation_ref":"op","kind":"entry.add_many","entities":[{"id":"food","kind":"entry","revision":"2","label":"Йогурт"}]}]},
            {"service":"tasks","status":"rejected","message":"Список не найден"},
            {"service":"tasks","status":"rejected","error":"internal_error","message":"raw diagnostics"}
          ]
        }""")
        assertEquals("Записал.", reply.text)
        assertEquals(null, reply.question)
        assertTrue(reply.committed)
        assertTrue(reply.responseFailed)
        assertEquals("partial", reply.execution?.completion)
    }

    @Test
    fun malformedResultAndFutureExecutionCannotCreateConfirmationOrGuessedOutcome() {
        val primitive = reply(""""Безопасный текст"""")
        assertTrue(primitive.unsupported)
        assertEquals("Безопасный текст", primitive.fallbackText)
        val unknown = reply("""{"reply":"Ответ","confirmation_pending":true,"execution":{"effects":"future","completion":"completed","safe_to_resubmit":true}}""")
        assertTrue(unknown.unsupported)
        assertFalse(unknown.confirmationPending)
        assertEquals(null, unknown.execution)
        assertFalse(unknown.committed)
        assertFalse(unknown.unknownOutcome)
    }

    @Test
    fun unknownOutcomeAndClosedProposalsRemainDistinctFromQuestions() {
        assertTrue(reply("""{"error":"outcome_pending"}""").unknownOutcome)
        assertTrue(reply("""{"services":[{"status":"unknown"}]}""").unknownOutcome)
        assertTrue(reply("""{"error":"harness_interrupted"}""").interrupted)
        val closed = reply("""{"question":"Подтвердить?","confirmation_pending":true,"proposal_status":"resolved"}""")
        assertEquals(null, closed.question)
        assertFalse(closed.confirmationPending)
        val question = reply("""{"question":"Как назвать?","confirmation_pending":false,"proposal_status":"resolved"}""")
        assertEquals("Как назвать?", question.question)
    }

    private fun history(result: String): AssistantHistory = decodeAssistantHistory("""{
        "messages":[{"id":"message","created_at":"2026-10-16T00:00:00Z","state":"completed","text":"Synthetic",
        "turns":[{"id":"turn","state":"completed","result":$result}]}],"before":null
    }""".encodeToByteArray())

    private fun reply(result: String): AssistantReply = history(result).messages.single().replies.single()
}
