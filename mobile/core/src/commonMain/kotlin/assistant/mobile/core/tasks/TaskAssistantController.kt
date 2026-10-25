package assistant.mobile.core.tasks

import assistant.mobile.core.AccountDrafts
import assistant.mobile.core.EventDomain
import assistant.mobile.core.EventInvalidation
import assistant.mobile.core.GeneratedStrings
import assistant.mobile.core.HttpOutcome
import assistant.mobile.core.NativeHttpClient
import assistant.mobile.core.NativeJson
import assistant.mobile.core.SessionLease
import assistant.mobile.core.SessionManager
import assistant.mobile.core.nativeError
import io.ktor.http.encodeURLParameter
import io.ktor.http.encodeURLPathPart
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.encodeToString

/** Task-scoped reads and drafts; all writes use the Tasks journal and its original operation key. */
class TaskAssistantController(
    private val scope: CoroutineScope,
    private val sessions: SessionManager,
    private val http: NativeHttpClient,
    private val drafts: AccountDrafts,
    private val tasks: TasksController,
) {
    private val mutableState = MutableStateFlow<TaskAssistantState?>(null)
    val state: StateFlow<TaskAssistantState?> = mutableState.asStateFlow()
    private val activeTask = MutableStateFlow<String?>(null)
    private var boundLease: SessionLease? = null
    private val readLock = Mutex()
    private val writeLock = Mutex()
    private val draftLock = Mutex()
    private var draftVersion = 0L
    private val taskDrafts = mutableMapOf<String, TaskAssistantDraft>()
    private val taskDraftVersions = mutableMapOf<String, Long>()

    init {
        scope.launch {
            combine(sessions.leases, sessions.identity, activeTask) { lease, identity, taskId -> Triple(lease, identity, taskId) }
                .collectLatest { (lease, identity, taskId) ->
                    if (lease == null || identity == null) {
                        boundLease = null
                        taskDrafts.clear()
                        taskDraftVersions.clear()
                        mutableState.value = null
                        return@collectLatest
                    }
                    if (taskId == null) return@collectLatest
                    bind(lease, taskId)
                    restoreDraft(lease, taskId)
                    while (owns(lease, taskId) && activeTask.value == taskId) {
                        refresh()
                        delay(10_000)
                    }
                }
        }
    }

    /** Selecting a task reads state; it never creates or resumes a delegation. */
    fun activate(taskId: String) {
        require(taskId.isNotBlank())
        sessions.lease()?.let { bind(it, taskId) }
        activeTask.value = taskId
    }
    fun deactivate() { activeTask.value = null }

    suspend fun refresh() {
        val taskId = activeTask.value ?: return
        val lease = sessions.lease() ?: return
        if (!owns(lease, taskId)) return
        readLock.withLock {
            if (!owns(lease, taskId)) return@withLock
            change(lease, taskId) { copy(snapshot = snapshot.copy(loading = true, error = null), capabilities = capabilities.copy(loading = true, error = null)) }
            coroutineScope {
                launch {
                    val response = http.request("GET", "/v1/tasks/items/${taskId.encodeURLPathPart()}", lease = lease)
                    val task = (response as? HttpOutcome.Success)?.let { runCatching { decodeTask(it.body) }.getOrNull() }?.takeIf { it.id == taskId }
                    if (task != null) change(lease, taskId) { copy(task = task, canWrite = tasks.canWrite(task)) }
                    else change(lease, taskId) { copy(canWrite = false, error = nativeError(if (response is HttpOutcome.Success) HttpOutcome.InvalidResponse else response)) }
                }
                launch {
                    val response = http.request("GET", "/v1/tasks/items/${taskId.encodeURLPathPart()}/assistant", lease = lease)
                    val decoded = (response as? HttpOutcome.Success)?.let { runCatching { decodeTaskAssistantSnapshot(it.body, taskId) }.getOrNull() }
                    change(lease, taskId) { copy(snapshot = if (decoded != null) TaskAssistantQuery(decoded) else
                        snapshot.copy(loading = false, stale = snapshot.data != null, error = nativeError(if (response is HttpOutcome.Success) HttpOutcome.InvalidResponse else response))) }
                }
                launch {
                    val response = http.request("GET", "/v1/tasks/assistant/capabilities?task_id=${taskId.encodeURLParameter()}", lease = lease)
                    val decoded = (response as? HttpOutcome.Success)?.let { runCatching { decodeTaskAssistantCapabilities(it.body) }.getOrNull() }
                    change(lease, taskId) { copy(capabilities = if (decoded != null) TaskAssistantQuery(decoded) else
                        capabilities.copy(loading = false, stale = capabilities.data != null, error = nativeError(if (response is HttpOutcome.Success) HttpOutcome.InvalidResponse else response))) }
                }
            }
        }
    }

    suspend fun invalidate(invalidation: EventInvalidation) {
        if (invalidation is EventInvalidation.Domains && EventDomain.Tasks !in invalidation.values) return
        mutableState.update { it?.copy(snapshot = it.snapshot.copy(stale = it.snapshot.data != null), capabilities = it.capabilities.copy(stale = it.capabilities.data != null)) }
        refresh()
    }

    fun updateDraft(draft: TaskAssistantDraft) {
        val lease = sessions.lease() ?: return
        if (!owns(lease, draft.taskId) || state.value?.busy == true) return
        draftVersion++
        val version = draftVersion
        taskDrafts[draft.taskId] = draft
        taskDraftVersions[draft.taskId] = version
        change(lease, draft.taskId) { copy(draft = draft, error = null) }
        scope.launch { persistSafely(lease, draft, version) }
    }
    fun beginEdit() {
        val before = state.value ?: return
        val item = before.snapshot.data?.delegation ?: return
        if (!before.canEditAuthority) return
        updateDraft(before.draft.copy(instruction = item.instruction, mode = item.mode, completion = item.completion,
            interval = (item.intervalMinutes ?: 60).toString(), checks = item.checks, grants = item.grants,
            customGrants = false, editingId = item.id, editingRevision = item.revision))
    }
    fun cancelEdit() {
        val before = state.value ?: return
        updateDraft(before.draft.copy(editingId = null, editingRevision = 0))
    }
    fun customize() {
        val before = state.value ?: return
        val selected = before.snapshot.data?.delegation?.grants ?: before.capabilities.data?.defaultGrants ?: return
        if (!before.draft.customGrants) updateDraft(before.draft.copy(customGrants = true, grants = selected.copy(
            resources = (selected.resources + listOfNotNull(before.task?.listId)).distinct())))
    }
    fun selectGrant(kind: TaskGrantKind, id: String, selected: Boolean) {
        val before = state.value ?: return
        if (before.capabilities.data?.choices(kind)?.none { it.id == id } != false ||
            kind == TaskGrantKind.Resources && id == before.task?.listId) return
        var grants = before.draft.grants.selecting(kind, id, selected)
        if (kind == TaskGrantKind.Executors && selected) {
            listOf(TaskGrantKind.Tools, TaskGrantKind.Actions).forEach { permission ->
                if (requireNotNull(before.capabilities.data).choices(permission).any { it.id == "workers.run" }) grants = grants.selecting(permission, "workers.run", true)
            }
        }
        updateDraft(before.draft.copy(grants = grants, customGrants = true))
    }

    suspend fun save(): TaskReceipt? {
        val taskId = state.value?.taskId ?: return null
        val lease = sessions.lease() ?: return null
        val submitted = state.value?.draft ?: return null
        val version = draftVersion
        val receipt = mutate { before ->
            val task = before.task ?: return@mutate null
            if (before.capabilities.data?.enabled != true || before.draft.editingId != null && !before.canEditAuthority) return@mutate null
            val current = before.snapshot.data?.delegation
            val draft = if (tasks.state.value?.conflict == true && before.draft.editingId != null && current?.id == before.draft.editingId)
                before.draft.copy(editingRevision = requireNotNull(current).revision) else before.draft
            taskDelegationOperation(task, draft)
        }
        if (receipt != null && owns(lease, taskId) && draftVersion == version && state.value?.draft == submitted) state.value?.let { before ->
            updateDraft(TaskAssistantDraft(taskId, answers = before.draft.answers, revisions = before.draft.revisions))
        }
        return receipt
    }

    suspend fun control(action: String) = mutate { before ->
        val item = before.snapshot.data?.delegation ?: return@mutate null
        if (!before.canManage || action == "resume" && !before.canEditAuthority) return@mutate null
        taskDelegationControlOperation(item, action)
    }
    suspend fun answer(questionId: String): TaskReceipt? {
        val taskId = state.value?.taskId ?: return null
        val lease = sessions.lease() ?: return null
        val submitted = state.value?.draft ?: return null
        val version = draftVersion
        val receipt = mutate { before ->
            if (!before.canManage) return@mutate null
            val question = before.snapshot.data?.questions?.firstOrNull { it.id == questionId } ?: return@mutate null
            taskQuestionAnswerOperation(question, before.draft.answers[questionId].orEmpty())
        }
        if (receipt != null && owns(lease, taskId) && draftVersion == version && state.value?.draft == submitted) state.value?.let { updateDraft(it.draft.copy(answers = it.draft.answers - questionId)) }
        return receipt
    }

    suspend fun decide(questionId: String, decision: String) = mutate { before ->
        if (!before.canManage) return@mutate null
        before.snapshot.data?.questions?.firstOrNull { it.id == questionId }?.let { taskQuestionDecisionOperation(it, decision) }
    }
    suspend fun accept(resultId: String, complete: Boolean) = mutate { before ->
        if (!before.canManage) return@mutate null
        val task = before.task ?: return@mutate null
        before.snapshot.data?.results?.firstOrNull { it.id == resultId }?.let { taskResultAcceptOperation(task, it, complete) }
    }
    suspend fun revise(resultId: String): TaskReceipt? {
        val taskId = state.value?.taskId ?: return null
        val lease = sessions.lease() ?: return null
        val submitted = state.value?.draft ?: return null
        val version = draftVersion
        val receipt = mutate { before ->
            if (!before.canEditAuthority || before.capabilities.data?.enabled != true) return@mutate null
            before.snapshot.data?.results?.firstOrNull { it.id == resultId }?.let { taskResultReviseOperation(it, before.draft.revisions[resultId].orEmpty()) }
        }
        if (receipt != null && owns(lease, taskId) && draftVersion == version && state.value?.draft == submitted) state.value?.let { updateDraft(it.draft.copy(revisions = it.draft.revisions - resultId)) }
        return receipt
    }

    suspend fun openRun(runId: String) {
        val before = state.value ?: return
        if (before.snapshot.data?.runs?.none { it.id == runId } != false) return
        val lease = sessions.lease() ?: return
        change(lease, before.taskId) { copy(run = TaskAssistantQuery(loading = true)) }
        val response = http.request("GET", "/v1/tasks/assistant/runs/${runId.encodeURLPathPart()}", lease = lease)
        val run = (response as? HttpOutcome.Success)?.let { runCatching { decodeTaskAssistantRun(it.body, before.taskId, runId) }.getOrNull() }
        change(lease, before.taskId) { copy(run = if (run != null) TaskAssistantQuery(run) else
            TaskAssistantQuery(error = nativeError(if (response is HttpOutcome.Success) HttpOutcome.InvalidResponse else response))) }
    }

    private suspend fun mutate(operation: (TaskAssistantState) -> TaskOperation?): TaskReceipt? = writeLock.withLock {
        val before = state.value ?: return@withLock null
        val lease = sessions.lease() ?: return@withLock null
        if (!owns(lease, before.taskId) || !before.canWrite || before.busy) return@withLock null
        val command = try { operation(before) } catch (_: IllegalArgumentException) {
            change(lease, before.taskId) { copy(error = GeneratedStrings.Errors.invalid) }; null
        } ?: return@withLock null
        change(lease, before.taskId) { copy(busy = true, error = null) }
        try {
            persist(lease, before.draft, draftVersion)
            val task = before.task ?: return@withLock null
            val scoped = command.copy(expectedRevisions = command.expectedRevisions + (task.id to task.revision))
            val receipt = tasks.perform(scoped)
            refresh()
            receipt
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { change(lease, before.taskId) { copy(error = GeneratedStrings.Errors.server) }; null }
        finally { change(lease, before.taskId) { copy(busy = false) } }
    }

    private fun bind(lease: SessionLease, taskId: String) {
        if (boundLease != lease || mutableState.value?.taskId != taskId) {
            if (boundLease != lease) {
                taskDrafts.clear()
                taskDraftVersions.clear()
            }
            boundLease = lease
            draftVersion++
            mutableState.value = TaskAssistantState(taskId, userId = lease.namespace.userId,
                draft = taskDrafts[taskId] ?: TaskAssistantDraft(taskId))
        }
    }
    private fun owns(lease: SessionLease, taskId: String) = sessions.isCurrent(lease) && boundLease == lease && state.value?.taskId == taskId
    private fun change(lease: SessionLease, taskId: String, transform: TaskAssistantState.() -> TaskAssistantState) {
        if (owns(lease, taskId)) mutableState.update { it?.takeIf { current -> current.taskId == taskId }?.transform() ?: it }
    }
    private suspend fun restoreDraft(lease: SessionLease, taskId: String) {
        if (taskId in taskDrafts) return
        val version = draftVersion
        try {
            val saved = draftLock.withLock { drafts.read(lease, target(taskId)) } ?: return
            val decoded = NativeJson.decodeFromString<TaskAssistantDraft>(saved.json.decodeToString())
            require(decoded.taskId == taskId)
            if (version == draftVersion && owns(lease, taskId) && taskId !in taskDrafts) {
                taskDrafts[taskId] = decoded
                change(lease, taskId) { copy(draft = decoded) }
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { change(lease, taskId) { copy(error = GeneratedStrings.Assistant.draftStorageError) } }
    }
    private suspend fun persistSafely(lease: SessionLease, draft: TaskAssistantDraft, version: Long) {
        try { persist(lease, draft, version) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { change(lease, draft.taskId) { copy(error = GeneratedStrings.Assistant.draftStorageError) } }
    }
    private suspend fun persist(lease: SessionLease, draft: TaskAssistantDraft, version: Long) = draftLock.withLock {
        // Navigation does not revoke a draft write: only a newer edit of this same task or
        // a changed session can supersede it. The account journal still owns logout erasure.
        if (sessions.isCurrent(lease) && taskDraftVersions[draft.taskId] == version)
            drafts.save(lease, target(draft.taskId), NativeJson.encodeToString(draft).encodeToByteArray(), draft.editingRevision)
    }
    private fun target(taskId: String) = "task-assistant/$taskId"
}
