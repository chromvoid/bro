package assistant.mobile.android

import assistant.mobile.core.tasks.Task
import assistant.mobile.core.tasks.TaskFilters
import assistant.mobile.core.tasks.TaskList
import assistant.mobile.core.tasks.TaskOperation
import assistant.mobile.core.tasks.TaskReceipt
import assistant.mobile.core.tasks.TaskScope
import assistant.mobile.core.tasks.TaskSelection
import assistant.mobile.core.tasks.TaskState
import assistant.mobile.core.tasks.TaskView
import assistant.mobile.design.R as DesignR
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TasksPresentationTest {
    private val personal = list("personal", mapOf("todo" to 1)).copy(personalUserId = "owner")
    private val shared = list("shared", mapOf("in_progress" to 1))
    private val archived = list("archived", mapOf("waiting" to 1)).copy(projectState = "archived")

    @Test
    fun onlyUnfilteredDateViewsOfferHiddenTasks() {
        TaskView.entries.forEach { view ->
            assertEquals(view.wire, view in setOf(TaskView.Today, TaskView.Upcoming),
                taskHasHiddenOpenTasks(TaskSelection(view = view), listOf(personal)))
        }
        listOf(TaskFilters(q = "trip"), TaskFilters(important = true), TaskFilters(overdue = true),
            TaskFilters(assignee = "me"), TaskFilters(assignee = "unassigned")).forEach { filters ->
            assertFalse(taskHasHiddenOpenTasks(TaskSelection(filters = filters), listOf(personal)))
        }
        assertTrue(taskHasHiddenOpenTasks(TaskSelection(filters = TaskFilters(completedScope = "all")), listOf(personal)))
    }

    @Test
    fun scopesCountOnlyTheirAvailableOpenTasks() {
        assertTrue(taskHasHiddenOpenTasks(TaskSelection(), listOf(shared)))
        assertFalse(taskHasHiddenOpenTasks(TaskSelection(), listOf(archived)))
        assertFalse(taskHasHiddenOpenTasks(TaskSelection(scope = TaskScope.Personal), listOf(shared)))
        assertTrue(taskHasHiddenOpenTasks(TaskSelection(scope = TaskScope.Personal), listOf(personal, shared)))
        assertFalse(taskHasHiddenOpenTasks(TaskSelection(scope = TaskScope.Assigned), listOf(personal, shared)))
        assertFalse(taskHasHiddenOpenTasks(TaskSelection(scope = TaskScope.Assigned, listId = "shared"), listOf(shared)))
        assertFalse(taskHasHiddenOpenTasks(TaskSelection(), emptyList()))
    }

    @Test
    fun selectedListCountsItsOwnOpenStatesIncludingArchivedLists() {
        listOf("todo", "in_progress", "waiting").forEach { state ->
            assertTrue(state, taskHasHiddenOpenTasks(TaskSelection(listId = "selected"),
                listOf(list("selected", mapOf(state to 1)))))
        }
        assertTrue(taskHasHiddenOpenTasks(TaskSelection(listId = "archived"), listOf(archived)))
        assertFalse(taskHasHiddenOpenTasks(TaskSelection(listId = "missing"), listOf(personal, shared)))
        assertFalse(taskHasHiddenOpenTasks(TaskSelection(listId = "done"),
            listOf(list("done", mapOf("done" to 5)), shared)))
    }

    @Test
    fun taskReceiptsUseTheConfirmationForTheirActualAction() {
        val expected = mapOf(
            "task.complete" to DesignR.string.tasks_completed,
            "task.reopen" to DesignR.string.tasks_reopened,
            "task.reorder" to DesignR.string.tasks_queue_moved,
            "task.delete" to DesignR.string.tasks_trashed,
            "task.restore" to DesignR.string.tasks_restored,
            "task.update" to DesignR.string.tasks_saved,
            "task.undo" to DesignR.string.tasks_saved,
        )
        expected.forEach { (kind, message) ->
            assertEquals(kind, TaskReceiptToast(message), taskReceiptToast(receipt(kind)))
        }
    }

    @Test
    fun boardMoveUsesTheStateReturnedByTheReceipt() {
        TaskState.entries.forEach { state ->
            assertEquals(TaskReceiptToast(DesignR.string.tasks_board_moved, state),
                taskReceiptToast(receipt("task.set_state", state)))
        }
    }

    @Test
    fun receiptsWithoutUndoDoNotOfferAnUndoToast() {
        assertNull(taskReceiptToast(null))
        listOf("task.complete", "task.set_state", "task.reorder", "task.update").forEach { kind ->
            assertNull(kind, taskReceiptToast(receipt(kind).copy(undo = null)))
        }
    }

    private fun list(id: String, counts: Map<String, Int>) = TaskList(id, id, 1, "edit", "edit", counts = counts)

    private fun receipt(kind: String, state: TaskState = TaskState.Todo) = TaskReceipt("operation", kind,
        listOf(Task("task", "Title", "open", 2, state = state)), emptyList(),
        TaskOperation("task.undo", JsonObject(emptyMap())), JsonObject(emptyMap()))
}
