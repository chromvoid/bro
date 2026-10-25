package assistant.mobile.android

import assistant.mobile.core.tasks.TaskList
import assistant.mobile.core.tasks.TaskReceipt
import assistant.mobile.core.tasks.TaskScope
import assistant.mobile.core.tasks.TaskSelection
import assistant.mobile.core.tasks.TaskState
import assistant.mobile.core.tasks.TaskView
import assistant.mobile.design.R as DesignR

internal fun taskHasHiddenOpenTasks(selection: TaskSelection, lists: List<TaskList>): Boolean {
    if (selection.view !in setOf(TaskView.Today, TaskView.Upcoming) || selection.filters.narrowed ||
        selection.scope == TaskScope.Assigned) return false
    val counted = when {
        selection.listId != null -> lists.filter { it.id == selection.listId }
        selection.scope == TaskScope.Personal -> lists.filter { it.personalUserId != null }
        selection.scope == TaskScope.All -> lists.filter { it.projectState != "archived" }
        else -> emptyList()
    }
    return counted.any { list ->
        listOf("todo", "in_progress", "waiting").sumOf { list.counts[it] ?: 0 } > 0
    }
}

internal data class TaskReceiptToast(val message: Int, val state: TaskState? = null)

internal fun taskReceiptToast(receipt: TaskReceipt?): TaskReceiptToast? {
    if (receipt?.undo == null) return null
    return when (receipt.kind) {
        "task.complete" -> TaskReceiptToast(DesignR.string.tasks_completed)
        "task.reopen" -> TaskReceiptToast(DesignR.string.tasks_reopened)
        "task.set_state" -> TaskReceiptToast(DesignR.string.tasks_board_moved, receipt.tasks.first().state)
        "task.reorder" -> TaskReceiptToast(DesignR.string.tasks_queue_moved)
        "task.delete" -> TaskReceiptToast(DesignR.string.tasks_trashed)
        "task.restore" -> TaskReceiptToast(DesignR.string.tasks_restored)
        else -> TaskReceiptToast(DesignR.string.tasks_saved)
    }
}
