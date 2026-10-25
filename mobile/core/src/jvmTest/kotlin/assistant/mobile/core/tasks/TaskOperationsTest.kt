package assistant.mobile.core.tasks

import assistant.mobile.core.AccountNamespace
import assistant.mobile.core.journal.Submission
import assistant.mobile.core.journal.SubmissionState
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Cases from web api.test.ts and server TasksHTTP, SharedTracker and queue receipt contracts. */
class TaskOperationsTest {
    @Test
    fun editingSendsOnlyChangedFieldsAndKeepsTheOriginalRevision() {
        val task = decodeTask(taskFixture("task"))
        val base = fieldsOf(task)
        val draft = TaskEditDraft(task.id, task.revision, base,
            base.copy(title = "  Забрать материалы утром  ", plannedOn = "", dueOn = "2026-10-12", assignedTo = ""), task.listId)
        val operation = editTaskOperation(draft)
        assertEquals("task.update", operation.kind)
        assertEquals(mapOf(task.id to 7L), operation.expectedRevisions)
        assertNull(operation.targetResourceId)
        assertEquals(task.id, operation.payload.getValue("task_id").jsonPrimitive.content)
        assertEquals(taskObject("""{"title":"Забрать материалы утром","planned_on":null,"due_on":"2026-10-12","assigned_to":null}""".encodeToByteArray()),
            operation.payload.getValue("changes"))
        val wire = taskObject(operation.bytes())
        assertEquals(setOf("kind", "payload", "expected_revisions"), wire.keys)
        assertEquals(7L, wire.getValue("expected_revisions").jsonObject.getValue(task.id).jsonPrimitive.content.toLong())
        assertEquals(operation, decodeTaskOperation(wire))
        assertFalse(operation.bytes().decodeToString().contains("Подтверждение склада"))
        assertFalse(operation.bytes().decodeToString().contains("Проверить комплектацию"))
    }

    @Test
    fun anExplicitSaveAfterConflictCanUseTheNewRevisionWithoutMutatingTheDraft() {
        val task = decodeTask(taskFixture("task"))
        val base = fieldsOf(task)
        val draft = TaskEditDraft(task.id, task.revision, base, base.copy(description = "Мой сохранённый черновик"), task.listId)
        assertEquals(mapOf(task.id to 10L), editTaskOperation(draft, revision = 10).expectedRevisions)
        assertEquals(7L, draft.revision)
        assertEquals("Мой сохранённый черновик", draft.fields.description)
        assertEquals("Сверить с заказом", draft.base.description)
    }

    @Test
    fun creatingAChildTargetsTheSelectedListAndGuardsTheParentRevision() {
        val fields = TaskFields(title = "  Новый шаг  ", description = "Описание", dueOn = "2026-10-12", priority = 1)
        val draft = TaskEditDraft(fields = fields, listId = "project", parentId = "parent")
        val child = editTaskOperation(draft, revision = 5)
        assertEquals("task.create", child.kind)
        assertEquals("project", child.targetResourceId)
        assertEquals(mapOf("parent" to 5L), child.expectedRevisions)
        assertEquals(taskObject("""{"title":"Новый шаг","description":"Описание","due_on":"2026-10-12","priority":1,"parent_id":"parent"}""".encodeToByteArray()), child.payload)
        assertEquals("project", taskObject(child.bytes()).getValue("target_resource_id").jsonPrimitive.content)
        val root = editTaskOperation(TaskEditDraft(fields = TaskFields(title = "Личное дело")))
        assertEquals(setOf("title"), root.payload.keys)
        assertEquals(emptyMap(), root.expectedRevisions)
        assertNull(root.targetResourceId)
    }

    @Test
    fun completeReopenDeleteAndRestoreAreRevisionGuardedServerTransitions() {
        val task = decodeTask(taskFixture("task"))
        for (kind in listOf("task.complete", "task.reopen", "task.delete", "task.restore")) {
            val operation = transitionTaskOperation(task, kind)
            assertEquals(kind, operation.kind)
            assertEquals(mapOf(task.id to task.revision), operation.expectedRevisions)
            assertEquals(buildJsonObject { put("task_id", task.id) }, operation.payload)
            assertEquals(TaskState.Waiting, task.state)
            assertEquals(7L, task.revision)
        }
        assertFails { transitionTaskOperation(task, "task.update") }
    }

    @Test
    fun aStateReceiptCarriesOnlyTheServerAuthoredGuardedUndoOperation() {
        val receipt = decodeTaskReceipt(taskFixture("receipt"))
        assertEquals("synthetic-state-operation", receipt.operationId)
        assertEquals("task.set_state", receipt.kind)
        val task = receipt.tasks.single()
        assertEquals(8L, task.revision)
        assertEquals(TaskState.InProgress, task.state)
        val undo = requireNotNull(receipt.undo)
        assertEquals("task.undo", undo.kind)
        assertEquals(mapOf(task.id to 8L), undo.expectedRevisions)
        assertEquals(taskObject("""{"task_id":"${task.id}","operation_id":"synthetic-state-operation"}""".encodeToByteArray()), undo.payload)
        assertFalse(undo.payload.containsKey("state"))
        assertFalse(undo.payload.containsKey("position"))
        assertContentEquals(undo.bytes(), decodeTaskOperation(taskObject(undo.bytes())).bytes())
    }

    @Test
    fun journalReceiptMustMatchTheOperationIdentityAndAdvanceItsExpectedRevision() {
        val task = decodeTask(taskFixture("task"))
        val operation = TaskOperation("task.set_state", buildJsonObject { put("task_id", task.id); put("state", "in_progress") }, mapOf(task.id to 7L))
        val sent = journalEntry(operation)
        validateTaskSubmissionReceipt(sent, taskFixture("receipt"))
        assertEquals(TASK_LOOKUP + sent.key, sent.lookup)
        for (reply in listOf(
            taskFixture("receipt").decodeToString().replace("\"task.set_state\"", "\"task.complete\""),
            taskFixture("receipt").decodeToString().replace("\"revision\": 8", "\"revision\": 7"),
            taskFixture("receipt").decodeToString().replace(task.id, "unrelated-task"),
            """{"operation_id":"op","kind":"task.set_state","tasks":[]}""",
            """{"operation_id":"","kind":"task.set_state","tasks":[]}""",
        )) assertFails(reply) { validateTaskSubmissionReceipt(sent, reply.encodeToByteArray()) }
    }

    @Test
    fun createAndListReceiptValidationRequiresTheAffectedResource() {
        val create = journalEntry(editTaskOperation(TaskEditDraft(fields = TaskFields(title = "Задача"))))
        assertFails { validateTaskSubmissionReceipt(create, """{"operation_id":"op","kind":"task.create","tasks":[]}""".encodeToByteArray()) }
        validateTaskSubmissionReceipt(create, """{"operation_id":"op","kind":"task.create","tasks":[{"id":"created","title":"Задача","status":"open","revision":1}]}""".encodeToByteArray())
        val updateList = journalEntry(TaskOperation("list.update", buildJsonObject { put("list_id", "project") }, mapOf("project" to 4L)))
        val lists = taskObject(taskFixture("lists")).getValue("lists") as JsonArray
        val project = lists.last().jsonObject
        fun receipt(revision: Long) = buildJsonObject {
            put("operation_id", "list-op"); put("kind", "list.update")
            put("lists", JsonArray(listOf(JsonObject(project + ("revision" to JsonPrimitive(revision))))))
        }.toString().encodeToByteArray()
        assertFails { validateTaskSubmissionReceipt(updateList, receipt(4)) }
        validateTaskSubmissionReceipt(updateList, receipt(5))
        assertTrue(project.containsKey("outcome"))
    }
}

private fun journalEntry(operation: TaskOperation): Submission = Submission(
    namespace = AccountNamespace("synthetic-instance", "https://synthetic.invalid", "owner"),
    key = "synthetic-task-key", endpoint = TASK_OPERATIONS, lookup = TASK_LOOKUP + "synthetic-task-key", method = "POST",
    body = operation.bytes(), audience = "Личные дела", revisions = operation.expectedRevisions.mapValues { it.value.toString() },
    draftTarget = "tasks:item", found = SubmissionState.RESOLVED, createdAtMillis = 0,
)
