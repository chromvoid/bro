package assistant.mobile.core.tasks

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

fun taskDelegationOperation(task: Task, draft: TaskAssistantDraft): TaskOperation {
    require(task.id == draft.taskId && task.status != "done" && task.revision > 0 && draft.valid)
    val fields = buildJsonObject {
        put("instruction", draft.instruction.trim())
        put("mode", draft.mode.wire)
        put("completion_mode", draft.completion.wire)
        put("interval_minutes", requireNotNull(draft.interval.toLongOrNull()))
        put("completion_criteria", buildJsonObject {
            put("required_checks", JsonArray(if (draft.completion == TaskCompletionMode.Auto) draft.checks.map { JsonPrimitive(it.wire) } else emptyList()))
        })
        if (draft.customGrants) put("grants", assistantGrantJson(draft.grants.copy(resources = (draft.grants.resources + listOfNotNull(task.listId)).distinct())))
    }
    return if (draft.editingId == null) TaskOperation("delegation.create", buildJsonObject {
        put("task_id", task.id); fields.forEach { (key, value) -> put(key, value) }
    }, mapOf(task.id to task.revision)) else {
        require(draft.editingRevision > 0)
        TaskOperation("delegation.update", buildJsonObject { put("delegation_id", draft.editingId); put("changes", fields) },
            mapOf(draft.editingId to draft.editingRevision))
    }
}

fun taskDelegationControlOperation(delegation: TaskDelegation, action: String): TaskOperation {
    require(action in setOf("pause", "resume", "revoke") && delegation.active)
    return TaskOperation("delegation.$action", buildJsonObject { put("delegation_id", delegation.id) }, mapOf(delegation.id to delegation.revision))
}
fun taskQuestionAnswerOperation(question: TaskAssistantQuestion, answer: String): TaskOperation {
    require(question.pending && question.kind == "question" && answer.trim().isNotEmpty() && answer.length <= 10000)
    return TaskOperation("question.answer", buildJsonObject { put("question_id", question.id); put("answer", answer.trim()) }, mapOf(question.id to question.revision))
}
fun taskQuestionDecisionOperation(question: TaskAssistantQuestion, decision: String): TaskOperation {
    require(question.pending && question.kind == "proposal" && decision in setOf("approve", "decline"))
    return TaskOperation("question.answer", buildJsonObject { put("question_id", question.id); put("decision", decision) }, mapOf(question.id to question.revision))
}
fun taskResultAcceptOperation(task: Task, result: TaskAssistantResult, complete: Boolean): TaskOperation {
    require(result.reviewable && (!complete || task.status != "done"))
    return TaskOperation("result.accept", buildJsonObject { put("result_id", result.id); put("complete_task", complete) },
        mapOf(result.id to result.revision) + if (complete) mapOf(task.id to task.revision) else emptyMap())
}
fun taskResultReviseOperation(result: TaskAssistantResult, instruction: String): TaskOperation {
    require(result.reviewable && instruction.trim().isNotEmpty() && instruction.length <= 10000)
    return TaskOperation("result.revise", buildJsonObject { put("result_id", result.id); put("instruction", instruction.trim()) }, mapOf(result.id to result.revision))
}
private fun assistantGrantJson(grants: TaskAssistantGrants): JsonObject = buildJsonObject {
    TaskGrantKind.entries.forEach { kind -> put(kind.wire, JsonArray(grants.values(kind).distinct().map(::JsonPrimitive))) }
}
