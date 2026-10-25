package assistant.mobile.core.tasks

import assistant.mobile.core.*
import assistant.mobile.core.journal.*
import io.ktor.http.encodeURLPathPart
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import kotlin.time.Clock
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/** Task screens share the account journal and event stream; refreshes never propose writes. */
class TasksController(
    private val scope: CoroutineScope,
    private val sessions: SessionManager,
    private val http: NativeHttpClient,
    private val submissions: SubmissionCoordinator,
    private val drafts: AccountDrafts,
    private val clockMs: () -> Long = { Clock.System.now().toEpochMilliseconds() },
) {
    private val mutableState = MutableStateFlow<TasksState?>(null)
    val state: StateFlow<TasksState?> = mutableState.asStateFlow()
    val draftGeneration: Long get() = selectionGeneration
    private val visible = MutableStateFlow(false)
    private var boundLease: SessionLease? = null
    private var selectedTaskId: String? = null
    private var selectionGeneration = 0L
    private var draftVersion = 0L
    private var placeRestored = false
    private var placeGeneration = 0L
    private var pendingListActivation: String? = null
    private var discussionVersion = 0L
    private val readLock = Mutex()
    private val mutationLock = Mutex()
    private val draftLock = Mutex()

    init {
        scope.launch {
            combine(sessions.leases, sessions.identity, visible) { lease, identity, active -> Triple(lease, identity, active) }
                .collectLatest { (lease, identity, active) ->
                    bind(lease, identity)
                    if (!active || lease == null || identity == null) return@collectLatest
                    if (!placeRestored) { restorePlace(lease); placeRestored = true }
                    refresh()
                }
        }
    }

    fun activate(listId: String? = null) {
        bind(sessions.lease(), sessions.identity.value)
        if (listId != null) {
            placeRestored = true; placeGeneration++; pendingListActivation = listId
            val project = state.value?.lists?.data?.firstOrNull { it.id == listId }?.isProject == true
            mutableState.update { it?.copy(selection = TaskSelection(listId = listId, view = TaskView.Open,
                layout = if (project) TaskLayout.Board else TaskLayout.List, sort = if (project) TaskSort.Manual else TaskSort.Priority)) }
        }
        visible.value = true
    }
    fun deactivate() { visible.value = false }
    fun canWrite(task: Task): Boolean = state.value?.let { current ->
        current.canWrite && (task.listId == null || current.lists.data?.firstOrNull { it.id == task.listId }?.writable == true)
    } == true

    suspend fun select(selection: TaskSelection) {
        val lease = sessions.lease() ?: return
        if (!owned(lease)) return
        require(selection.sort != TaskSort.Manual || selection.listId != null)
        placeGeneration++
        pendingListActivation = null
        placeRestored = true
        var requested = selection
        val current = state.value?.selection
        val project = selection.listId?.let { id -> state.value?.lists?.data?.firstOrNull { it.id == id && it.isProject } }
        if (project != null && current?.listId != project.id) {
            val remembered = drafts.read(lease, "tasks/project:${project.id}")?.let {
                runCatching { NativeJson.decodeFromString<TaskSelection>(it.json.decodeToString()) }.getOrNull()
            }
            requested = if (remembered != null) selection.copy(layout = remembered.layout, sort = remembered.sort)
                else selection.copy(layout = TaskLayout.Board, sort = TaskSort.Manual)
        }
        val normalized = if (current?.layout == TaskLayout.Board && requested.layout == TaskLayout.List &&
            requested.filters.completedScope == "all") requested.copy(view = TaskView.All) else requested
        change(lease) { copy(selection = normalized, page = TaskQuery(), board = TaskQuery(), error = null) }
        try {
            val bytes = NativeJson.encodeToString(normalized).encodeToByteArray()
            drafts.save(lease, PLACE_TARGET, bytes, clockMs().coerceAtLeast(0))
            if (project != null) drafts.save(lease, "tasks/project:${project.id}", bytes, clockMs().coerceAtLeast(0))
        }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { change(lease) { copy(error = GeneratedStrings.Assistant.draftStorageError) } }
        refresh()
    }

    suspend fun refresh() {
        if (!visible.value) return
        val lease = sessions.lease() ?: return
        bind(lease, sessions.identity.value)
        if (state.value?.canRead != true) return
        refreshReads(lease)
        try {
            // Reads may run beside a mutation, but recovery and pending publication share its
            // gate: an older empty journal read must never hide a delivery created meanwhile.
            mutationLock.withLock { if (owned(lease)) refreshPending(lease, consume = true) }
        }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { change(lease) { copy(error = GeneratedStrings.Assistant.draftStorageError) } }
    }
    suspend fun invalidate(invalidation: EventInvalidation) {
        if (invalidation is EventInvalidation.Domains && invalidation.values.none { it in setOf(EventDomain.Tasks, EventDomain.Community, EventDomain.Shared) }) return
        mutableState.update { it?.copy(lists = it.lists.copy(stale = it.lists.data != null), page = it.page.copy(stale = it.page.data != null),
            board = it.board.copy(stale = it.board.data != null), task = it.task.copy(stale = it.task.data != null),
            history = it.history.copy(stale = it.history.data != null), discussion = it.discussion.copy(stale = it.discussion.data != null)) }
        refresh()
    }

    suspend fun openTask(id: String) {
        require(id.isNotBlank())
        val lease = sessions.lease() ?: return
        bind(lease, sessions.identity.value)
        if (state.value?.canRead != true) return
        selectedTaskId = id
        selectionGeneration++
        change(lease) { copy(task = TaskQuery(loading = true), children = TaskQuery(), history = TaskQuery(), discussion = TaskQuery(),
            members = TaskQuery(), draft = null, discussionDraft = "", conflict = false, completion = null, error = null) }
        refreshSelected()
        val task = state.value?.task?.data?.takeIf { it.id == id } ?: return
        beginEdit(task)
        restoreDraft(lease, draftTarget(id), selectionGeneration)
        restoreDiscussionDraft(lease, id, selectionGeneration, discussionVersion)
    }

    fun closeTask() {
        val lease = sessions.lease()
        val closingId = selectedTaskId
        val discussionText = state.value?.discussionDraft.orEmpty()
        val closingDiscussionVersion = discussionVersion
        if (lease != null && closingId != null) scope.launch {
            try {
                draftLock.withLock {
                    if (selectedTaskId == closingId && discussionVersion != closingDiscussionVersion) return@withLock
                    drafts.save(lease, "tasks/discussion/$closingId", buildJsonObject { put("text", discussionText) }.toString().encodeToByteArray(), closingDiscussionVersion)
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { change(lease) { copy(error = GeneratedStrings.Assistant.draftStorageError) } }
        }
        val snapshot = state.value?.draft
        if (lease != null && snapshot != null) {
            val version = draftVersion
            scope.launch {
                try {
                    draftLock.withLock {
                        if (state.value?.draft?.taskId == snapshot.taskId && version != draftVersion) return@withLock
                        drafts.save(lease, draftTarget(snapshot.taskId), NativeJson.encodeToString(snapshot).encodeToByteArray(), version)
                    }
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { change(lease) { copy(error = GeneratedStrings.Assistant.draftStorageError) } }
            }
        }
        selectedTaskId = null
        selectionGeneration++
        mutableState.update { it?.copy(task = TaskQuery(), draft = null, children = TaskQuery(), history = TaskQuery(),
            discussion = TaskQuery(), discussionDraft = "", members = TaskQuery(), completion = null, conflict = false) }
    }
    fun beginCreate(listId: String? = state.value?.selection?.listId, parent: Task? = null) {
        if (state.value?.canWrite != true || state.value?.busy == true) return
        selectedTaskId = null
        selectionGeneration++
        val today = state.value?.page?.data?.today
        val fields = TaskFields(plannedOn = if (state.value?.selection?.view == TaskView.Today && state.value?.selection?.layout != TaskLayout.Board) today.orEmpty() else "")
        mutableState.update { it?.copy(task = TaskQuery(), children = TaskQuery(), history = TaskQuery(), discussion = TaskQuery(),
            draft = TaskEditDraft(revision = parent?.revision ?: 0, fields = fields, listId = listId ?: parent?.listId, parentId = parent?.id),
            completion = null, conflict = false, error = null) }
        val lease = sessions.lease() ?: return
        val generation = selectionGeneration
        val version = draftVersion
        scope.launch { restoreDraft(lease, CREATE_TARGET, generation, version) }
    }
    fun beginEdit(task: Task) {
        if (state.value?.canRead != true || state.value?.busy == true) return
        if (selectedTaskId != task.id) selectionGeneration++
        selectedTaskId = task.id
        val fields = fieldsOf(task)
        mutableState.update { it?.copy(draft = TaskEditDraft(task.id, task.revision, fields, fields, task.listId, task.parentId), error = null) }
    }
    fun updateDraft(draft: TaskEditDraft) {
        val lease = sessions.lease() ?: return
        if (!owned(lease) || state.value?.busy == true) return
        draftVersion++
        val version = draftVersion
        change(lease) { copy(draft = draft, error = null) }
        scope.launch {
            try { persistDraft(lease, draft, version) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { change(lease) { copy(error = GeneratedStrings.Assistant.draftStorageError) } }
        }
    }
    fun appendImage(image: TaskImage, field: String) {
        val draft = state.value?.draft ?: return
        val images = if (field == "title_images") draft.fields.titleImages else draft.fields.descriptionImages
        require(field in setOf("title_images", "description_images"))
        if (images.size >= if (field == "title_images") 4 else 20) return
        val fields = if (field == "title_images") draft.fields.copy(titleImages = images + image) else draft.fields.copy(descriptionImages = images + image)
        updateDraft(draft.copy(fields = fields))
    }
    fun removeImage(id: String, field: String) {
        val draft = state.value?.draft ?: return
        val fields = if (field == "title_images") draft.fields.copy(titleImages = draft.fields.titleImages.filterNot { it.id == id })
            else draft.fields.copy(descriptionImages = draft.fields.descriptionImages.filterNot { it.id == id })
        updateDraft(draft.copy(fields = fields))
    }
    suspend fun dismissEdit() {
        val lease = sessions.lease() ?: return
        if (!owned(lease) || state.value?.busy == true) return
        val target = draftTarget(state.value?.draft?.taskId)
        draftVersion++
        draftLock.withLock { drafts.delete(lease, target) }
        val task = state.value?.task?.data
        change(lease) { copy(draft = task?.let { val fields = fieldsOf(it); TaskEditDraft(it.id, it.revision, fields, fields, it.listId, it.parentId) }, conflict = false, error = null) }
    }
    suspend fun submitEdit(): TaskReceipt? = mutate { lease ->
        val snapshot = state.value?.draft ?: return@mutate null
        val revision = if (state.value?.conflict == true && snapshot.taskId != null)
            state.value?.task?.data?.takeIf { it.id == snapshot.taskId }?.revision ?: return@mutate null else snapshot.revision
        val operation = try { editTaskOperation(snapshot, revision) } catch (_: Exception) {
            change(lease) { copy(error = GeneratedStrings.Errors.invalid) }; return@mutate null
        }
        persistDraft(lease, snapshot, draftVersion)
        deliver(lease, operation, draftTarget(snapshot.taskId))
    }

    suspend fun perform(operation: TaskOperation): TaskReceipt? = mutate { lease -> deliver(lease, operation, null) }
    suspend fun transition(task: Task, kind: String): TaskReceipt? {
        if (!canWrite(task)) return null
        return perform(transitionTaskOperation(latest(task), kind))
    }
    suspend fun setTaskState(task: Task, state: TaskState, reason: String? = null, beforeId: String? = null): TaskReceipt? {
        if (!canWrite(task)) return null
        val current = latest(task)
        return perform(TaskOperation("task.set_state", buildJsonObject { put("task_id", current.id); put("state", state.wire)
            if (beforeId != null) put("before_id", beforeId)
            if (state == TaskState.Waiting) put("waiting_reason", reason?.trim()?.takeIf { it.isNotEmpty() }?.let(::JsonPrimitive) ?: JsonNull)
        }, mapOf(current.id to current.revision)))
    }
    suspend fun moveTask(task: Task, listId: String): TaskReceipt? {
        if (!canWrite(task) || state.value?.lists?.data?.none { it.id == listId && it.writable } != false) return null
        val current = latest(task)
        return perform(TaskOperation("task.move", buildJsonObject { put("task_id", current.id); put("list_id", listId) }, mapOf(current.id to current.revision)))
    }
    suspend fun detachTask(task: Task) = updateTask(task, buildJsonObject { put("parent_id", JsonNull) })
    suspend fun reorderTask(task: Task, beforeId: String?): TaskReceipt? {
        if (!canWrite(task) || state.value?.selection?.filters?.narrowed == true) return null
        val current = latest(task)
        return perform(TaskOperation("task.reorder", buildJsonObject { put("task_id", current.id); put("before_id", beforeId?.let(::JsonPrimitive) ?: JsonNull) }, mapOf(current.id to current.revision)))
    }
    suspend fun toggleStep(task: Task, id: String, done: Boolean) = changeSteps(task, latest(task).checklist.map { if (it.id == id) it.copy(done = done) else it })
    @OptIn(ExperimentalUuidApi::class)
    suspend fun addStep(task: Task, text: String): TaskReceipt? {
        if (text.isBlank() || text.trim().length > 1000 || latest(task).checklist.size >= 100) return null
        return changeSteps(task, latest(task).checklist + TaskStep(Uuid.random().toString(), text.trim(), false))
    }
    suspend fun removeStep(task: Task, id: String) = changeSteps(task, latest(task).checklist.filterNot { it.id == id })
    suspend fun createSubtask(parent: Task, title: String): TaskReceipt? {
        if (!canWrite(parent) || parent.parentId != null || title.isBlank()) return null
        val current = latest(parent)
        return perform(TaskOperation("task.create", buildJsonObject { put("title", title.trim()); put("parent_id", current.id) }, mapOf(current.id to current.revision), current.listId))
    }
    private suspend fun changeSteps(task: Task, steps: List<TaskStep>) = updateTask(task, buildJsonObject {
        put("checklist", JsonArray(steps.map { buildJsonObject { put("id", it.id); put("text", it.text); put("done", it.done) } }))
    })
    private suspend fun updateTask(task: Task, changes: JsonObject): TaskReceipt? {
        if (!canWrite(task)) return null
        val current = latest(task)
        return perform(TaskOperation("task.update", buildJsonObject { put("task_id", current.id); put("changes", changes) }, mapOf(current.id to current.revision)))
    }
    suspend fun createProject(name: String, outcome: String, spaceId: String? = null) = createListOperation(name, outcome, spaceId, true)
    suspend fun createList(name: String, spaceId: String? = null) = createListOperation(name, "", spaceId, false)
    private suspend fun createListOperation(name: String, outcome: String, spaceId: String?, project: Boolean): TaskReceipt? {
        if (name.isBlank() || name.trim().length > 120) return null
        return perform(TaskOperation("list.create", buildJsonObject { put("name", name.trim()); put("list_kind", if (project) "project" else "list")
            if (project) put("outcome", outcome.trim().takeIf { it.isNotEmpty() }?.let(::JsonPrimitive) ?: JsonNull)
            spaceId?.takeIf { it.isNotBlank() }?.let { put("space_id", it) }
        }))
    }
    suspend fun updateProject(project: TaskList, name: String, outcome: String, state: String): TaskReceipt? {
        require(state in setOf("active", "done", "archived"))
        return updateListOperation(project, buildJsonObject { put("name", name.trim()); put("list_kind", "project"); put("project_state", state)
            put("outcome", outcome.trim().takeIf { it.isNotEmpty() }?.let(::JsonPrimitive) ?: JsonNull) })
    }
    suspend fun updateList(list: TaskList, name: String) = updateListOperation(list, buildJsonObject { put("name", name.trim()) })
    private suspend fun updateListOperation(list: TaskList, changes: JsonObject): TaskReceipt? {
        if (!list.writable) return null
        return perform(TaskOperation("list.update", buildJsonObject { put("list_id", list.id); put("changes", changes) }, mapOf(list.id to list.revision)))
    }
    suspend fun undo(): TaskReceipt? = state.value?.receipt?.undo?.let { perform(it) }
    fun cancelCompletion() { mutableState.update { it?.copy(completion = null) } }
    suspend fun confirmCompletion(): TaskReceipt? {
        val confirmation = state.value?.completion?.takeIf { it.canConfirm } ?: return null
        cancelCompletion()
        val operation = confirmation.operation
        val confirmed = operation.copy(payload = JsonObject(operation.payload + ("confirm_checklist_revision" to JsonPrimitive(confirmation.revision))))
        return mutate { lease ->
            val target = state.value?.draft?.taskId?.takeIf { operation.kind == "task.update" && operation.payload["task_id"]?.jsonPrimitive?.content == it }?.let(::draftTarget)
            deliver(lease, confirmed, target)
        }
    }
    fun clearReceipt() { mutableState.update { it?.copy(receipt = null) } }

    /** A discussion has no receipt lookup in the server API; an uncertain send is never resent. */
    suspend fun sendDiscussion(text: String = state.value?.discussionDraft.orEmpty(), assistant: Boolean = false) = mutationLock.withLock {
        val lease = sessions.lease() ?: return@withLock
        val task = state.value?.task?.data ?: return@withLock
        val channel = state.value?.discussion?.data?.channelId ?: return@withLock
        if (!owned(lease) || !canWrite(task) || text.isBlank() || text.trim().length > 4000) return@withLock
        try {
            refreshPending(lease)
            if (state.value?.pending?.isNotEmpty() == true) return@withLock
            change(lease) { copy(busy = true, error = null) }
            val capturedVersion = discussionVersion
            val body = buildJsonObject { put("text", text.trim()); if (assistant) put("assistant", true) }.toString().encodeToByteArray()
            val result = submissions.submit(lease, NewSubmission(discussionEndpoint(channel), null, body, "tasks", draftTarget = "tasks/discussion/${task.id}"))
            if (result.state in setOf(SubmissionState.RESOLVED, SubmissionState.ACCEPTED)) {
                refreshSelected()
                if (owned(lease)) {
                    submissions.acknowledge(lease, result.key)
                    if (selectedTaskId == task.id && state.value?.discussionDraft == text && discussionVersion == capturedVersion) {
                        discussionVersion++
                        drafts.delete(lease, "tasks/discussion/${task.id}")
                        change(lease) { copy(discussionDraft = "") }
                    }
                }
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { change(lease) { copy(error = GeneratedStrings.Errors.network) } }
        finally { change(lease) { copy(busy = false) }; pendingOrError(lease) }
    }
    fun updateDiscussionDraft(text: String) {
        val lease = sessions.lease() ?: return
        val id = selectedTaskId ?: return
        if (!owned(lease)) return
        discussionVersion++
        val version = discussionVersion
        val value = text.take(4000)
        change(lease) { copy(discussionDraft = value) }
        scope.launch {
            try {
                draftLock.withLock {
                    if (discussionVersion != version || selectedTaskId != id) return@withLock
                    drafts.save(lease, "tasks/discussion/$id", buildJsonObject { put("text", value) }.toString().encodeToByteArray(), version)
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { change(lease) { copy(error = GeneratedStrings.Assistant.draftStorageError) } }
        }
    }
    private suspend fun restoreDiscussionDraft(lease: SessionLease, id: String, generation: Long, version: Long) {
        val stored = drafts.read(lease, "tasks/discussion/$id") ?: return
        val text = taskObject(stored.json).getValue("text").jsonPrimitive.content
        if (selected(lease, id, generation) && discussionVersion == version) {
            discussionVersion = maxOf(discussionVersion, stored.revision)
            change(lease) { copy(discussionDraft = text) }
        }
    }
    suspend fun markDiscussionRead() {
        val lease = sessions.lease() ?: return
        val discussion = state.value?.discussion?.data ?: return
        val latest = discussion.messages.lastOrNull()?.id ?: return
        val outcome = http.request("POST", "/v1/channels/${discussion.channelId.encodeURLPathPart()}/read",
            buildJsonObject { put("through_message_id", latest) }.toString().encodeToByteArray(), lease)
        if (outcome !is HttpOutcome.Success) change(lease) { copy(error = nativeError(outcome)) }
    }
    /** Refreshing the thread shows authoritative messages; choosing not to resend is explicit. */
    suspend fun resolveDiscussion(key: String) = mutationLock.withLock {
        val lease = sessions.lease() ?: return@withLock
        val entry = submissions.pending(lease).firstOrNull { it.key == key && isDiscussion(it) } ?: return@withLock
        refreshSelected()
        if (!owned(lease) || state.value?.discussion?.error != null || state.value?.discussion?.data?.channelId?.let(::discussionEndpoint) != entry.endpoint) return@withLock
        submissions.abandonUnkeyed(lease, entry.key)
        pendingOrError(lease)
    }

    suspend fun check(key: String) = mutationLock.withLock {
        val lease = sessions.lease() ?: return@withLock
        if (!owned(lease)) return@withLock
        try {
            val original = submissions.pending(lease).firstOrNull { it.key == key && it.endpoint == TASK_OPERATIONS } ?: return@withLock
            change(lease) { copy(busy = true, error = null) }
            settle(lease, submissions.check(lease, original.key))
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { change(lease) { copy(error = GeneratedStrings.Errors.network) } }
        finally { change(lease) { copy(busy = false) }; pendingOrError(lease) }
    }

    private suspend fun mutate(block: suspend (SessionLease) -> TaskReceipt?): TaskReceipt? = mutationLock.withLock {
        val lease = sessions.lease() ?: return@withLock null
        if (!owned(lease) || state.value?.canWrite != true) return@withLock null
        try {
            refreshPending(lease)
            if (state.value?.pending?.isNotEmpty() == true) return@withLock null
            change(lease) { copy(busy = true, error = null) }
            block(lease)
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: StaleJournalSession) { null }
        catch (_: Exception) { change(lease) { copy(error = GeneratedStrings.Assistant.draftStorageError) }; null }
        finally { change(lease) { copy(busy = false) }; if (owned(lease)) pendingOrError(lease) }
    }
    private suspend fun deliver(lease: SessionLease, operation: TaskOperation, target: String?): TaskReceipt? {
        val result = submissions.submit(lease, NewSubmission(TASK_OPERATIONS, TASK_LOOKUP, operation.bytes(), "tasks",
            revisions = operation.expectedRevisions.mapValues { it.value.toString() }, draftTarget = target))
        return settle(lease, result)
    }
    private suspend fun settle(lease: SessionLease, result: Submission): TaskReceipt? {
        if (!owned(lease)) return null
        if (result.state == SubmissionState.RESOLVED && result.response != null) {
            val receipt = applyReceipt(lease, result)
            if (visible.value) refreshReads(lease)
            if (visible.value && currentReadsSucceeded()) submissions.acknowledge(lease, result.key)
            return receipt
        }
        if (result.state == SubmissionState.REJECTED) {
            val conflict = result.failureStatus == 409
            val completion = if (conflict) result.response?.let { decodeCompletion(it, decodeTaskOperation(taskObject(result.body))) } else null
            change(lease) { copy(conflict = conflict && completion == null, completion = completion,
                error = if (completion != null) null else if (conflict) GeneratedStrings.Tasks.conflictTitle else GeneratedStrings.Errors.server) }
            if (visible.value) refreshReads(lease)
        }
        return null
    }
    private suspend fun applyReceipt(lease: SessionLease, entry: Submission): TaskReceipt {
        val bytes = requireNotNull(entry.response)
        validateTaskSubmissionReceipt(entry, bytes)
        val receipt = decodeTaskReceipt(bytes)
        change(lease) { copy(receipt = receipt, conflict = false, completion = null, error = null) }
        // A restart may resolve an offscreen operation. Its durable draft still belongs to
        // the delivery and must be consumed, while a newer draft is kept byte-for-byte.
        val target = entry.draftTarget
        if (target != null && (target == CREATE_TARGET || target.startsWith("task:"))) {
            draftLock.withLock {
                val stored = drafts.read(lease, target)?.let { NativeJson.decodeFromString<TaskEditDraft>(it.json.decodeToString()) }
                if (stored != null && runCatching {
                    val revision = stored.taskId?.let { entry.revisions[it]?.toLongOrNull() } ?: stored.revision
                    val delivered = taskObject(entry.body)
                    val payload = delivered.getValue("payload").jsonObject
                    val comparable = JsonObject(delivered + ("payload" to JsonObject(payload - "confirm_checklist_revision")))
                    taskObject(editTaskOperation(stored, revision).bytes()) == comparable
                }.getOrDefault(false)) {
                    drafts.delete(lease, target)
                    if (state.value?.draft == stored) {
                        selectedTaskId = null; selectionGeneration++
                        change(lease) { copy(draft = null, task = TaskQuery(), children = TaskQuery(), history = TaskQuery(), discussion = TaskQuery(), members = TaskQuery()) }
                    }
                }
            }
        }
        return receipt
    }
    private suspend fun refreshPending(lease: SessionLease, consume: Boolean = false) {
        val entries = submissions.pending(lease).filter { it.endpoint == TASK_OPERATIONS || isDiscussion(it) }.map {
            if (consume && it.endpoint == TASK_OPERATIONS && it.state in setOf(SubmissionState.SENDING, SubmissionState.ACCEPTED, SubmissionState.UNCERTAIN)) submissions.check(lease, it.key) else it
        }
        if (consume) {
            val resolved = entries.filter { it.endpoint == TASK_OPERATIONS && it.state == SubmissionState.RESOLVED }
            for (entry in resolved) applyReceipt(lease, entry)
            if (resolved.isNotEmpty()) refreshReads(lease)
            if (currentReadsSucceeded()) for (entry in resolved) submissions.acknowledge(lease, entry.key)
        }
        change(lease) { copy(pending = entries.filter { it.state !in setOf(SubmissionState.REJECTED, SubmissionState.RESOLVED) }) }
    }
    private suspend fun pendingOrError(lease: SessionLease) {
        try { refreshPending(lease) } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { change(lease) { copy(error = GeneratedStrings.Assistant.draftStorageError) } }
    }
    private fun currentReadsSucceeded(): Boolean = state.value?.let { it.lists.data != null && it.lists.error == null &&
        (if (it.selection.layout == TaskLayout.Board) it.board.data != null && it.board.error == null else it.page.data != null && it.page.error == null) } == true

    private suspend fun refreshReads(lease: SessionLease) = readLock.withLock {
        var selection = state.value?.selection ?: return@withLock
        change(lease) { copy(lists = lists.copy(loading = true, error = null)) }
        val lists = read(lease, "/v1/tasks/lists", ::decodeTaskLists)
        change(lease) { copy(lists = lists) }
        if (!owned(lease) || state.value?.selection != selection) return@withLock
        val currentList = lists.data?.firstOrNull { it.id == selection.listId }
        if (selection.listId != null && lists.data != null && currentList == null) {
            closeTask(); change(lease) { copy(page = TaskQuery(error = GeneratedStrings.Errors.notFound), board = TaskQuery(error = GeneratedStrings.Errors.notFound)) }; return@withLock
        }
        if (currentList != null && pendingListActivation == currentList.id) {
            val requested = selection
            val remembered = if (currentList.isProject) try {
                drafts.read(lease, "tasks/project:${currentList.id}")?.let {
                    NativeJson.decodeFromString<TaskSelection>(it.json.decodeToString())
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { change(lease) { copy(error = GeneratedStrings.Assistant.draftStorageError) }; null }
            else null
            if (!owned(lease) || state.value?.selection != requested || pendingListActivation != currentList.id) return@withLock
            pendingListActivation = null
            selection = if (currentList.isProject) requested.copy(layout = remembered?.layout ?: TaskLayout.Board,
                sort = remembered?.sort ?: TaskSort.Manual) else requested
            val normalized = selection
            change(lease) { copy(selection = normalized, page = TaskQuery(), board = TaskQuery()) }
            try {
                drafts.save(lease, PLACE_TARGET, NativeJson.encodeToString(normalized).encodeToByteArray(), clockMs().coerceAtLeast(0))
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { change(lease) { copy(error = GeneratedStrings.Assistant.draftStorageError) } }
        }
        if (selection.layout == TaskLayout.Board && currentList?.isProject == true) {
            change(lease) { copy(board = board.copy(loading = true, error = null)) }
            val board = read(lease, "/v1/tasks/board?${boardQuery(selection)}", ::decodeTaskBoard)
            changeSelection(lease, selection) { copy(board = board) }
        } else {
            change(lease) { copy(page = page.copy(loading = true, error = null)) }
            val page = read(lease, "/v1/tasks/items?${taskQuery(selection)}", ::decodeTaskPage)
            changeSelection(lease, selection) { copy(page = page) }
        }
        val spaces = read(lease, "/v1/spaces", ::decodeTaskSpaces)
        change(lease) { copy(spaces = spaces) }
        refreshSelected()
    }
    suspend fun refreshSelected() {
        val lease = sessions.lease() ?: return
        val id = selectedTaskId ?: return
        val generation = selectionGeneration
        if (!owned(lease) || state.value?.canRead != true) return
        val path = "/v1/tasks/items/${id.encodeURLPathPart()}"
        val task = read(lease, path, ::decodeTask)
        if (!selected(lease, id, generation)) return
        if (task.data == null) {
            change(lease) { copy(task = task, draft = null, discussionDraft = "", children = TaskQuery(), history = TaskQuery(),
                discussion = TaskQuery(), members = TaskQuery(), error = task.error) }
            return
        }
        val current = task.data
        change(lease) {
            val before = draft
            val freshDraft = if (before?.taskId == current.id && before.fields == before.base && !conflict) {
                val fields = fieldsOf(current)
                TaskEditDraft(current.id, current.revision, fields, fields, current.listId, current.parentId)
            } else before
            copy(task = task, draft = freshDraft)
        }
        if (current.parentId == null) {
            val children = read(lease, "/v1/tasks/items?${childrenQuery(id)}", ::decodeTaskPage)
            if (selected(lease, id, generation)) change(lease) { copy(children = children) }
        }
        val history = read(lease, "$path/history", ::decodeTaskHistory)
        if (selected(lease, id, generation)) change(lease) { copy(history = history) }
        if (current.deletedAt == null) {
            val channelOutcome = http.request("GET", "$path/discussion", lease = lease)
            if (selected(lease, id, generation)) {
                val channel = if (channelOutcome is HttpOutcome.Success) runCatching { taskObject(channelOutcome.body).getValue("channel_id").jsonPrimitive.content }.getOrNull() else null
                val discussion = if (channel != null) read(lease, discussionEndpoint(channel), { decodeTaskDiscussion(it, channel) })
                    else TaskQuery<TaskDiscussionPage>(error = if (channelOutcome is HttpOutcome.Success) GeneratedStrings.Errors.server else nativeError(channelOutcome))
                if (selected(lease, id, generation)) change(lease) { copy(discussion = discussion) }
            }
        }
        val list = state.value?.lists?.data?.firstOrNull { it.id == current.listId }
        val members = list?.spaceId?.let { read(lease, "/v1/spaces/${it.encodeURLPathPart()}/members", ::decodeTaskMembers) } ?: TaskQuery<List<TaskMember>>(emptyList())
        if (selected(lease, id, generation)) change(lease) { copy(members = members) }
    }

    suspend fun loadMore() = readLock.withLock {
        val lease = sessions.lease() ?: return@withLock
        val selection = state.value?.selection ?: return@withLock
        val old = state.value?.page?.data ?: return@withLock
        val cursor = old.nextCursor ?: return@withLock
        val outcome = http.request("GET", "/v1/tasks/items?${taskQuery(selection, cursor)}", lease = lease)
        if (outcome is HttpOutcome.Conflict) { rereadStale(lease, selection); return@withLock }
        val next = if (outcome is HttpOutcome.Success) runCatching { decodeTaskPage(outcome.body) }.getOrNull() else null
        if (next != null && next.snapshot == old.snapshot && next.nextCursor != cursor) {
            changeSelection(lease, selection) { copy(page = TaskQuery(next.copy(items = uniqueTasks(old.items + next.items)))) }
        } else changeSelection(lease, selection) { copy(page = page.copy(error = if (outcome is HttpOutcome.Success) GeneratedStrings.Errors.server else nativeError(outcome))) }
    }
    suspend fun loadMoreBoard(state: TaskState) = readLock.withLock {
        val lease = sessions.lease() ?: return@withLock
        val selection = this.state.value?.selection ?: return@withLock
        val old = this.state.value?.board?.data ?: return@withLock
        val column = old.columns.getValue(state)
        val cursor = column.nextCursor ?: return@withLock
        val outcome = http.request("GET", "/v1/tasks/items?${boardQuery(selection, state, cursor)}", lease = lease)
        val page = if (outcome is HttpOutcome.Success) runCatching { decodeTaskPage(outcome.body) }.getOrNull() else null
        if (outcome is HttpOutcome.Conflict || page != null && (page.snapshot != old.snapshot || page.nextCursor == cursor || page.nextCursor != null && uniqueTasks(column.items + page.items).size == column.items.size)) {
            rereadStale(lease, selection); return@withLock
        }
        if (page != null) changeSelection(lease, selection) { copy(board = TaskQuery(old.copy(columns = old.columns + (state to column.copy(items = uniqueTasks(column.items + page.items), nextCursor = page.nextCursor))))) }
        else changeSelection(lease, selection) { copy(board = board.copy(error = if (outcome is HttpOutcome.Success) GeneratedStrings.Errors.server else nativeError(outcome))) }
    }
    private suspend fun rereadStale(lease: SessionLease, selection: TaskSelection) {
        // Discard every cursor together. The stored edit remains untouched.
        if (selection.layout == TaskLayout.Board) {
            val board = read(lease, "/v1/tasks/board?${boardQuery(selection)}", ::decodeTaskBoard)
            changeSelection(lease, selection) { copy(board = board, error = GeneratedStrings.Tasks.stale) }
        } else {
            val page = read(lease, "/v1/tasks/items?${taskQuery(selection)}", ::decodeTaskPage)
            changeSelection(lease, selection) { copy(page = page, error = GeneratedStrings.Tasks.stale) }
        }
        refreshSelected()
    }
    suspend fun loadMoreChildren() {
        val lease = sessions.lease() ?: return
        val id = selectedTaskId ?: return
        val generation = selectionGeneration
        val old = state.value?.children?.data ?: return
        val cursor = old.nextCursor ?: return
        val next = read(lease, "/v1/tasks/items?${childrenQuery(id, cursor)}", ::decodeTaskPage)
        if (!selected(lease, id, generation)) return
        if (next.data != null && next.data.snapshot == old.snapshot) change(lease) { copy(children = TaskQuery(next.data.copy(items = uniqueTasks(old.items + next.data.items)))) }
        else refreshSelected()
    }
    suspend fun loadMoreHistory() {
        val lease = sessions.lease() ?: return
        val id = selectedTaskId ?: return
        val generation = selectionGeneration
        val old = state.value?.history?.data ?: return
        val cursor = old.nextCursor ?: return
        val next = read(lease, "/v1/tasks/items/${id.encodeURLPathPart()}/history?${taskParams(mapOf("cursor" to cursor))}", ::decodeTaskHistory)
        if (selected(lease, id, generation) && next.data != null) change(lease) { copy(history = TaskQuery(next.data.copy(items = (old.items + next.data.items).distinctBy { it.id }, people = (old.people + next.data.people).distinctBy { it.id }))) }
    }
    suspend fun loadMoreDiscussion() {
        val lease = sessions.lease() ?: return
        val id = selectedTaskId ?: return
        val generation = selectionGeneration
        val old = state.value?.discussion?.data ?: return
        val cursor = old.nextCursor ?: return
        val next = read(lease, discussionEndpoint(old.channelId) + "?${taskParams(mapOf("before" to cursor))}", { decodeTaskDiscussion(it, old.channelId) })
        if (selected(lease, id, generation) && next.data != null) change(lease) { copy(discussion = TaskQuery(next.data.copy(messages = (next.data.messages + old.messages).distinctBy { it.id }))) }
    }
    private suspend fun <T> read(lease: SessionLease, path: String, decode: (ByteArray) -> T): TaskQuery<T> {
        val outcome = http.request("GET", path, lease = lease)
        return if (outcome is HttpOutcome.Success) try { TaskQuery(decode(outcome.body)) }
            catch (_: Exception) { TaskQuery(error = GeneratedStrings.Errors.server) }
        else TaskQuery(error = nativeError(outcome))
    }
    private suspend fun restoreDraft(lease: SessionLease, target: String, generation: Long, version: Long = draftVersion) {
        try {
            val stored = drafts.read(lease, target) ?: return
            val draft = NativeJson.decodeFromString<TaskEditDraft>(stored.json.decodeToString())
            if (!owned(lease) || generation != selectionGeneration || version != draftVersion) return
            draftVersion = maxOf(draftVersion, stored.revision)
            change(lease) { copy(draft = draft,
                conflict = draft.taskId != null && task.data?.id == draft.taskId && task.data.revision != draft.revision) }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { change(lease) { copy(error = GeneratedStrings.Assistant.draftStorageError) } }
    }
    private suspend fun persistDraft(lease: SessionLease, draft: TaskEditDraft, version: Long) = draftLock.withLock {
        if (version != draftVersion || state.value?.draft != draft) return@withLock
        drafts.save(lease, draftTarget(draft.taskId), NativeJson.encodeToString(draft).encodeToByteArray(), version)
    }
    private suspend fun restorePlace(lease: SessionLease) {
        val generation = placeGeneration
        try {
            val stored = drafts.read(lease, PLACE_TARGET) ?: return
            val selection = NativeJson.decodeFromString<TaskSelection>(stored.json.decodeToString())
            if (generation == placeGeneration) change(lease) { copy(selection = selection) }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { change(lease) { copy(error = GeneratedStrings.Assistant.draftStorageError) } }
    }
    private fun bind(lease: SessionLease?, identity: NativeIdentity?): Boolean {
        if (lease == null || identity == null || identity.id != lease.namespace.userId) {
            boundLease = null; selectedTaskId = null; selectionGeneration++; mutableState.value = null; return false
        }
        val changed = boundLease !== lease
        if (changed) { selectedTaskId = null; selectionGeneration++; placeRestored = false; pendingListActivation = null; draftVersion = 0 }
        boundLease = lease
        mutableState.update { old ->
            val base = if (changed || old == null) TasksState(lease.namespace, "tasks.read" in identity.scopes, "tasks.write" in identity.scopes) else old
            if ("tasks.read" !in identity.scopes) TasksState(lease.namespace, false, false)
            else base.copy(canRead = true, canWrite = "tasks.write" in identity.scopes)
        }
        return changed
    }
    private fun latest(task: Task): Task = listOfNotNull(task, state.value?.task?.data,
        state.value?.page?.data?.items?.find { it.id == task.id }, state.value?.children?.data?.items?.find { it.id == task.id },
        state.value?.board?.data?.columns?.values?.flatMap { it.items }?.find { it.id == task.id })
        .filter { it.id == task.id }.maxBy { it.revision }
    private fun owned(lease: SessionLease) = boundLease === lease && sessions.isCurrent(lease)
    private fun selected(lease: SessionLease, id: String, generation: Long) = owned(lease) && selectedTaskId == id && selectionGeneration == generation
    private fun change(lease: SessionLease, transform: TasksState.() -> TasksState) { if (owned(lease)) mutableState.update { it?.transform() } }
    private fun changeSelection(lease: SessionLease, selection: TaskSelection, transform: TasksState.() -> TasksState) {
        change(lease) { if (this.selection == selection) transform() else this }
    }
    private companion object {
        const val CREATE_TARGET = "tasks/create"
        const val PLACE_TARGET = "tasks/place"
        fun draftTarget(id: String?) = id?.let { "task:$it" } ?: CREATE_TARGET
        fun discussionEndpoint(channel: String) = "/v1/channels/${channel.encodeURLPathPart()}/messages"
        fun isDiscussion(entry: Submission) = entry.draftTarget?.startsWith("tasks/discussion/") == true
        fun childrenQuery(id: String, cursor: String? = null) = taskParams(buildMap {
            put("parent_id", id); put("scope", "all"); put("view", "all"); put("completed_scope", "all"); put("limit", "100")
            cursor?.let { put("cursor", it) }
        })
        fun uniqueTasks(items: List<Task>) = items.distinctBy { it.id }
        fun decodeCompletion(bytes: ByteArray, operation: TaskOperation): TaskCompletion? = runCatching {
            val error = taskObject(bytes)
            val reason = error.getValue("error").jsonPrimitive.content
            require(reason in setOf("open_subtasks", "incomplete_checklist", "parent_closed"))
            val detail = error.getValue("detail").jsonObject
            val id = detail.getValue("task_id").jsonPrimitive.content
            val revision = detail.getValue("revision").jsonPrimitive.long.also { require(it > 0) }
            val open = detail.getValue("open_subtasks").jsonPrimitive.int.also { require(it >= 0) }
            val checklist = detail.getValue("incomplete_checklist").jsonPrimitive.int.also { require(it >= 0) }
            val completing = operation.kind in setOf("task.complete", "task.undo") || operation.kind == "task.set_state" && operation.payload["state"]?.jsonPrimitive?.content == "done" ||
                operation.kind == "task.update" && operation.payload["changes"]?.jsonObject?.get("state")?.jsonPrimitive?.content == "done" ||
                operation.kind == "result.accept" && operation.payload["complete_task"]?.jsonPrimitive?.booleanOrNull == true
            TaskCompletion(reason, id, revision, open, checklist, detail["parent_id"]?.takeUnless { it == JsonNull }?.jsonPrimitive?.content, operation,
                reason == "incomplete_checklist" && completing && open == 0 && checklist > 0 && operation.expectedRevisions[id] == revision)
        }.getOrNull()
    }
}
