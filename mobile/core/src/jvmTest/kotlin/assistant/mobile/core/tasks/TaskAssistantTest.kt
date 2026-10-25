package assistant.mobile.core.tasks

import assistant.mobile.core.AccountNamespace
import assistant.mobile.core.journal.Submission
import assistant.mobile.core.journal.SubmissionState
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Synthetic wire cases from web TaskAssistant.test.ts and tasks.assistant service receipts. */
class TaskAssistantTest {
    @Test
    fun createPayloadUsesCurrentTaskRevisionAndOnlyExplicitCustomGrants() {
        val task = Task("task", "Проверить варианты", "open", 7, "list")
        val draft = TaskAssistantDraft("task", instruction = "  Изучить варианты  ")
        val operation = taskDelegationOperation(task, draft)
        assertEquals("delegation.create", operation.kind)
        assertEquals(mapOf("task" to 7L), operation.expectedRevisions)
        assertEquals("Изучить варианты", operation.payload.getValue("instruction").jsonPrimitive.content)
        assertFalse("grants" in operation.payload)
        assertEquals("[]", operation.payload.getValue("completion_criteria").jsonObject.getValue("required_checks").toString())
        val custom = taskDelegationOperation(task, draft.copy(completion = TaskCompletionMode.Auto, customGrants = true,
            grants = TaskAssistantGrants(tools = listOf("tasks.get"), resources = listOf("other"))))
        assertEquals("[\"other\",\"list\"]", custom.payload.getValue("grants").jsonObject.getValue("resources").toString())
        assertEquals(4, custom.payload.getValue("completion_criteria").jsonObject.getValue("required_checks").jsonArraySize())
        assertFailsWith<IllegalArgumentException> { taskDelegationOperation(task.copy(id = "elsewhere"), draft) }
        assertFailsWith<IllegalArgumentException> { taskDelegationOperation(task.copy(status = "done"), draft) }
        assertFailsWith<IllegalArgumentException> { taskDelegationOperation(task, draft.copy(completion = TaskCompletionMode.Auto, checks = emptyList())) }
    }
    @Test
    fun executorPermissionAndMonitorBoundsAreDeterministic() {
        val draft = TaskAssistantDraft("task", instruction = "Изучить", mode = TaskAssistantMode.Monitor)
        assertTrue(draft.valid)
        assertFalse(draft.copy(interval = "4").valid)
        assertFalse(draft.copy(interval = "10081").valid)
        assertFalse(draft.copy(interval = "5.5").valid)
        val worker = draft.copy(customGrants = true, grants = TaskAssistantGrants(executors = listOf("worker")))
        assertTrue(worker.executorPermissionsMissing)
        assertFalse(worker.valid)
        assertTrue(worker.copy(grants = worker.grants.copy(tools = listOf("workers.run"), actions = listOf("workers.run"))).valid)
    }
    @Test
    fun resultCompletionRequiresBothResultAndTaskRevision() {
        val task = Task("task", "Проверить варианты", "open", 7, "list")
        val result = decodeTaskAssistantSnapshot(assistantSnapshot().encodeToByteArray(), "task").results.single()
        assertEquals(mapOf("result" to 4L), taskResultAcceptOperation(task, result, false).expectedRevisions)
        assertEquals(mapOf("result" to 4L, "task" to 7L), taskResultAcceptOperation(task, result, true).expectedRevisions)
        assertEquals("result.revise", taskResultReviseOperation(result, "Добавь цены").kind)
    }
    @Test
    fun receiptValidationRejectsOtherRecordTaskRevisionAndAnswer() {
        val operation = taskQuestionAnswerOperation(decodeTaskAssistantSnapshot(assistantSnapshot().encodeToByteArray(), "task").questions.first(), "До 5000")
            .let { it.copy(expectedRevisions = it.expectedRevisions + ("task" to 7L)) }
        val submission = assistantSubmission(operation)
        val receipt = """{"operation_id":"op","kind":"question.answer","task_id":"task","question":{"id":"question","task_id":"task","revision":3,"status":"answered","answer":"До 5000"}}"""
        validateTaskAssistantSubmissionReceipt(submission, receipt.encodeToByteArray())
        for (document in listOf(receipt.replace("\"id\":\"question\"", "\"id\":\"other\""), receipt.replace("\"task_id\":\"task\"", "\"task_id\":\"elsewhere\""),
            receipt.replace("\"revision\":3", "\"revision\":2"), receipt.replace("До 5000", "До 9000"))) {
            assertFailsWith<IllegalArgumentException> { validateTaskAssistantSubmissionReceipt(submission, document.encodeToByteArray()) }
        }
    }
}
private fun kotlinx.serialization.json.JsonElement.jsonArraySize() = (this as kotlinx.serialization.json.JsonArray).size
internal fun assistantSubmission(operation: TaskOperation) = Submission(AccountNamespace("synthetic", "https://synthetic.invalid", "user"), "key", TASK_OPERATIONS,
    TASK_LOOKUP + "key", "POST", operation.bytes(), "tasks", emptyMap(), null, SubmissionState.RESOLVED, 1)
internal fun assistantCapabilities() = """{"enabled":true,"default_grants":{"tools":["tasks.get"],"actions":["result.add"],"resources":["list"],"executors":[]},"tools":[{"id":"tasks.get","title":"Читать задачу и её картинки"}],"actions":[{"id":"result.add","title":"Предлагать результат"}],"resources":[{"id":"list","title":"Личный список"}],"executors":[]}"""
internal fun assistantSnapshot() = """{"delegation":{"id":"delegation","task_id":"task","user_id":"user","instruction":"Изучить варианты","mode":"oneoff","completion_mode":"manual","status":"waiting_input","revision":3,"grants":{"tools":["tasks.get"],"actions":["result.add"],"resources":["list"],"executors":[]}},"can_manage":true,"runs":[{"id":"run","status":"waiting_input","created_at":"2026-10-17T10:00:00Z"}],"questions":[{"id":"question","task_id":"task","text":"Какая цена?","status":"pending","kind":"question","revision":2,"audience_private":true},{"id":"answered","task_id":"task","text":"Можно продолжить?","status":"answered","kind":"proposal","revision":3,"audience_private":false,"answer":"До 5000","decision":"approve"}],"results":[{"id":"result","task_id":"task","body":"Варианты со ссылками","status":"pending","revision":4,"audience_private":true,"sources":[{"title":"Источник","url":"https://example.invalid/source"}]}]}"""
