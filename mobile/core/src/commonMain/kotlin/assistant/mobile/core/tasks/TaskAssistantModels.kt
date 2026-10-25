package assistant.mobile.core.tasks

import assistant.mobile.core.LocalizedString
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class TaskAssistantGrants(
    val tools: List<String> = emptyList(),
    val actions: List<String> = emptyList(),
    val resources: List<String> = emptyList(),
    val executors: List<String> = emptyList(),
) {
    fun values(kind: TaskGrantKind): List<String> = when (kind) {
        TaskGrantKind.Tools -> tools; TaskGrantKind.Actions -> actions
        TaskGrantKind.Resources -> resources; TaskGrantKind.Executors -> executors
    }
    fun selecting(kind: TaskGrantKind, id: String, selected: Boolean): TaskAssistantGrants {
        val next = if (selected) (values(kind) + id).distinct() else values(kind) - id
        return when (kind) {
            TaskGrantKind.Tools -> copy(tools = next); TaskGrantKind.Actions -> copy(actions = next)
            TaskGrantKind.Resources -> copy(resources = next); TaskGrantKind.Executors -> copy(executors = next)
        }
    }
}

enum class TaskGrantKind(val wire: String) { Tools("tools"), Actions("actions"), Resources("resources"), Executors("executors") }
@Serializable
enum class TaskAssistantMode(val wire: String) { @SerialName("oneoff") Oneoff("oneoff"), @SerialName("monitor") Monitor("monitor") }
@Serializable
enum class TaskCompletionMode(val wire: String) { @SerialName("manual") Manual("manual"), @SerialName("auto") Auto("auto") }
@Serializable
enum class TaskCompletionCheck(val wire: String) {
    @SerialName("has_result") HasResult("has_result"),
    @SerialName("all_subtasks_done") AllSubtasksDone("all_subtasks_done"),
    @SerialName("all_checklist_done") AllChecklistDone("all_checklist_done"),
    @SerialName("no_pending_questions") NoPendingQuestions("no_pending_questions"),
}

data class TaskAssistantChoice(val id: String, val title: String?, val description: String?)
data class TaskAssistantCapabilities(
    val enabled: Boolean,
    val defaultGrants: TaskAssistantGrants,
    val tools: List<TaskAssistantChoice>, val actions: List<TaskAssistantChoice>,
    val resources: List<TaskAssistantChoice>, val executors: List<TaskAssistantChoice>,
) {
    fun choices(kind: TaskGrantKind): List<TaskAssistantChoice> = when (kind) {
        TaskGrantKind.Tools -> tools; TaskGrantKind.Actions -> actions
        TaskGrantKind.Resources -> resources; TaskGrantKind.Executors -> executors
    }
}
data class TaskAssistantBudget(val activeSeconds: Long?, val toolCalls: Long?, val dailyRuns: Long?)
data class TaskDelegation(
    val id: String, val taskId: String, val userId: String, val revision: Long,
    val instruction: String, val mode: TaskAssistantMode, val completion: TaskCompletionMode,
    val status: String, val grants: TaskAssistantGrants, val checks: List<TaskCompletionCheck>,
    val intervalMinutes: Long?, val nextRunAt: String?, val budget: TaskAssistantBudget?,
) {
    val active: Boolean get() = status !in setOf("revoked", "completed", "done", "cancelled", "failed")
}
data class TaskAssistantQuestion(
    val id: String, val text: String, val status: String, val revision: Long,
    val private: Boolean, val kind: String, val answer: String?, val decision: String?,
) { val pending: Boolean get() = status in setOf("open", "pending", "waiting") }
data class TaskAssistantSource(val title: String?, val url: String)
data class TaskAssistantResult(
    val id: String, val body: String, val status: String, val revision: Long,
    val private: Boolean, val sources: List<TaskAssistantSource>, val createdAt: String?,
) { val reviewable: Boolean get() = status in setOf("ready", "pending", "proposed", "review", "pending_review") }
data class TaskAssistantRun(
    val id: String, val status: String, val createdAt: String?, val finishedAt: String?,
    val summary: String?, val reason: String?, val failed: Boolean,
    val taskId: String? = null, val delegationId: String? = null, val toolCalls: Long? = null,
)
data class TaskAssistantSnapshot(
    val delegation: TaskDelegation?, val runs: List<TaskAssistantRun>,
    val questions: List<TaskAssistantQuestion>, val results: List<TaskAssistantResult>, val canManage: Boolean?,
)

/** Task id is part of both the account-bound draft address and every delegated operation. */
@Serializable
data class TaskAssistantDraft(
    val taskId: String,
    val instruction: String = "", val mode: TaskAssistantMode = TaskAssistantMode.Oneoff,
    val completion: TaskCompletionMode = TaskCompletionMode.Manual, val interval: String = "60",
    val checks: List<TaskCompletionCheck> = TaskCompletionCheck.entries,
    val grants: TaskAssistantGrants = TaskAssistantGrants(), val customGrants: Boolean = false,
    val editingId: String? = null, val editingRevision: Long = 0,
    val answers: Map<String, String> = emptyMap(), val revisions: Map<String, String> = emptyMap(),
) {
    val executorPermissionsMissing: Boolean get() = customGrants && grants.executors.isNotEmpty() &&
        ("workers.run" !in grants.tools || "workers.run" !in grants.actions)
    val valid: Boolean get() = instruction.trim().isNotEmpty() && instruction.length <= 10000 &&
        interval.toLongOrNull()?.let { it in 5..10080 } == true && !executorPermissionsMissing &&
        (completion != TaskCompletionMode.Auto || checks.isNotEmpty())
}
data class TaskAssistantQuery<T>(val data: T? = null, val loading: Boolean = false, val error: LocalizedString? = null, val stale: Boolean = false)
data class TaskAssistantState(
    val taskId: String, val task: Task? = null, val canWrite: Boolean = false, val userId: String,
    val snapshot: TaskAssistantQuery<TaskAssistantSnapshot> = TaskAssistantQuery(),
    val capabilities: TaskAssistantQuery<TaskAssistantCapabilities> = TaskAssistantQuery(),
    val draft: TaskAssistantDraft = TaskAssistantDraft(taskId), val busy: Boolean = false,
    val error: LocalizedString? = null, val run: TaskAssistantQuery<TaskAssistantRun> = TaskAssistantQuery(),
) {
    val canManage: Boolean get() = canWrite && (snapshot.data?.canManage ?: (snapshot.data?.delegation?.userId?.let { it == userId } ?: true))
    val canEditAuthority: Boolean get() = canWrite && snapshot.data?.delegation?.userId == userId
}
