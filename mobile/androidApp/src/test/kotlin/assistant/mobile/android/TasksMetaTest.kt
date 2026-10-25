package assistant.mobile.android

import assistant.mobile.core.tasks.Task
import assistant.mobile.core.tasks.TaskList
import assistant.mobile.core.tasks.TaskParent
import assistant.mobile.core.tasks.TaskScope
import assistant.mobile.core.tasks.TaskSelection
import assistant.mobile.core.tasks.TaskState
import assistant.mobile.design.R as DesignR
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TasksMetaTest {
    private val today = "2026-10-22"
    private val task = Task("task", "Title", "open", 1, listId = "family")
    private val family = TaskList("family", "Family", 1, "edit", "edit")
    private val personal = TaskList("personal", "Personal account name", 1, "edit", "edit", personalUserId = "owner")

    @Test
    fun datesUseWarningForPastPlanAndDeadlinesUpToThreeDaysAway() {
        val parts = dates(task.copy(plannedOn = "2026-10-21", dueOn = "2026-10-25"))
        assertEquals(listOf("Planned: Yesterday", "Due: 25 окт."), parts.map { it.text })
        assertTrue(parts.all { it.tone == TaskMetaTone.Warning && it.calendar })
        assertEquals(TaskMetaTone.Warning, dates(task.copy(dueOn = today)).single().tone)
        val future = dates(task.copy(plannedOn = today, dueOn = "2026-10-26"))
        assertTrue(future.all { it.tone == TaskMetaTone.Neutral && !it.calendar })
    }

    @Test
    fun pastDeadlineAndServerOverdueAreDestructive() {
        for (overdueTask in listOf(task.copy(dueOn = "2026-10-21"), task.copy(dueOn = today, overdue = true))) {
            val part = dates(overdueTask).single()
            assertEquals(TaskMetaTone.Destructive, part.tone)
            assertTrue(part.text.startsWith("Overdue: "))
            assertFalse(part.calendar)
        }
    }

    @Test
    fun completedTasksShowNoDates() {
        assertEquals(emptyList<TaskMetaPart>(), dates(task.copy(status = "done", plannedOn = "2026-10-21", dueOn = "2026-10-20")))
    }

    @Test
    fun dayLabelsUseRelativeNamesAndYearOnlyOutsideCurrentYear() {
        assertEquals("Today", day(today))
        assertEquals("Yesterday", day("2026-10-21"))
        assertEquals("Tomorrow", day("2026-10-23"))
        assertEquals("6 окт.", day("2026-10-06"))
        assertEquals("6 окт. 2025", day("2025-10-06"))
        assertEquals("Tomorrow", taskMetaDayLabel("2027-01-01", "2026-12-31", ::text))
    }

    @Test
    fun placesAppearOnlyInMixedViewsAndPersonalListsUseChipName() {
        assertEquals(listOf("Family"), meta(task).map { it.text })
        assertEquals(listOf("Personal"), meta(task.copy(listId = "personal")).map { it.text })
        assertEquals(emptyList<TaskMetaPart>(), meta(task, TaskSelection(listId = "family")))
        assertEquals(emptyList<TaskMetaPart>(), meta(task, TaskSelection(scope = TaskScope.Personal)))
        assertEquals(listOf("Family"), meta(task, TaskSelection(scope = TaskScope.Assigned)).map { it.text })
    }

    @Test
    fun rowMetadataMatchesWebOrderWithoutTodoStateOrAssigneeAndCounters() {
        val detailed = task.copy(state = TaskState.InProgress, priority = 1, parentId = "parent",
            parent = TaskParent("parent", "Trip", TaskState.Todo, null), plannedOn = today, dueOn = "2026-10-24")
        assertEquals(listOf("In progress", "Family", "Parent: Trip", "Important", "Planned: Today", "Due: 24 окт."),
            meta(detailed).map { it.text })
        assertEquals(listOf("Waiting", "Family", "Subtask"), meta(task.copy(state = TaskState.Waiting, parentId = "parent")).map { it.text })
        assertEquals(listOf("Family"), meta(task.copy(state = TaskState.Todo, assignedTo = "owner")).map { it.text })
    }

    private fun dates(value: Task) = taskMetaDates(value, today, ::text)
    private fun day(value: String) = taskMetaDayLabel(value, today, ::text)
    private fun meta(value: Task, selection: TaskSelection = TaskSelection()) =
        taskMeta(value, today, selection, listOf(family, personal), ::text)

    private fun text(id: Int, argument: String?): String = when (id) {
        DesignR.string.day_today -> "Today"
        DesignR.string.day_yesterday -> "Yesterday"
        DesignR.string.day_tomorrow -> "Tomorrow"
        DesignR.string.tasks_states_in_progress -> "In progress"
        DesignR.string.tasks_states_waiting -> "Waiting"
        DesignR.string.tasks_places_personal -> "Personal"
        DesignR.string.tasks_parent -> "Parent: $argument"
        DesignR.string.tasks_subtask -> "Subtask"
        DesignR.string.tasks_priorities_1 -> "Important"
        DesignR.string.tasks_planned -> "Planned: $argument"
        DesignR.string.tasks_due -> "Due: $argument"
        DesignR.string.tasks_overdue -> "Overdue: $argument"
        else -> error("Unexpected task metadata string: $id")
    }
}
