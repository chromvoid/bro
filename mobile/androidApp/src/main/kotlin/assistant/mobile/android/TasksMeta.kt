package assistant.mobile.android

import assistant.mobile.core.tasks.Task
import assistant.mobile.core.tasks.TaskList
import assistant.mobile.core.tasks.TaskScope
import assistant.mobile.core.tasks.TaskSelection
import assistant.mobile.core.tasks.TaskState
import assistant.mobile.design.R as DesignR
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

internal enum class TaskMetaTone { Neutral, Warning, Destructive }

internal data class TaskMetaPart(
    val text: String,
    val tone: TaskMetaTone = TaskMetaTone.Neutral,
    val calendar: Boolean = false,
)

internal fun taskMeta(
    task: Task,
    today: String,
    selection: TaskSelection,
    lists: List<TaskList>,
    text: (Int, String?) -> String,
): List<TaskMetaPart> = buildList {
    when (task.state) {
        TaskState.InProgress -> add(TaskMetaPart(text(DesignR.string.tasks_states_in_progress, null)))
        TaskState.Waiting -> add(TaskMetaPart(text(DesignR.string.tasks_states_waiting, null)))
        else -> Unit
    }
    if (selection.listId == null && selection.scope != TaskScope.Personal) {
        lists.find { it.id == task.listId }?.let { list ->
            add(TaskMetaPart(if (list.personalUserId != null) text(DesignR.string.tasks_places_personal, null) else list.name))
        }
    }
    if (task.parentId != null) {
        add(TaskMetaPart(task.parent?.let { text(DesignR.string.tasks_parent, it.title) }
            ?: text(DesignR.string.tasks_subtask, null)))
    }
    if (task.priority == 1) add(TaskMetaPart(text(DesignR.string.tasks_priorities_1, null)))
    addAll(taskMetaDates(task, today, text))
}

internal fun taskMetaDates(task: Task, today: String, text: (Int, String?) -> String): List<TaskMetaPart> {
    if (task.status == "done") return emptyList()
    val current = LocalDate.parse(today)
    return buildList {
        task.plannedOn?.let { day ->
            val tone = if (LocalDate.parse(day) < current) TaskMetaTone.Warning else TaskMetaTone.Neutral
            add(TaskMetaPart(text(DesignR.string.tasks_planned, taskMetaDayLabel(day, today, text)), tone,
                calendar = tone == TaskMetaTone.Warning))
        }
        task.dueOn?.let { day ->
            val date = LocalDate.parse(day)
            val overdue = task.overdue || date < current
            val tone = when {
                overdue -> TaskMetaTone.Destructive
                date <= current.plusDays(3) -> TaskMetaTone.Warning
                else -> TaskMetaTone.Neutral
            }
            val label = if (overdue) DesignR.string.tasks_overdue else DesignR.string.tasks_due
            add(TaskMetaPart(text(label, taskMetaDayLabel(day, today, text)), tone,
                calendar = tone == TaskMetaTone.Warning))
        }
    }
}

internal fun taskMetaDayLabel(day: String, today: String, text: (Int, String?) -> String): String {
    val date = LocalDate.parse(day)
    val current = LocalDate.parse(today)
    return when (date) {
        current -> text(DesignR.string.day_today, null)
        current.minusDays(1) -> text(DesignR.string.day_yesterday, null)
        current.plusDays(1) -> text(DesignR.string.day_tomorrow, null)
        else -> date.format(DateTimeFormatter.ofPattern(
            if (date.year == current.year) "d MMM" else "d MMM yyyy", Locale.forLanguageTag("ru-RU")))
    }
}
