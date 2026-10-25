package assistant.mobile.core.timers

import assistant.mobile.core.*
import assistant.mobile.core.journal.*
import assistant.mobile.core.tasks.Task
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import kotlin.time.Clock
import kotlin.time.Instant

/** Account-bound reads and journal operations. The only local clock effect is a new GET. */
class TimersController(
    private val scope: CoroutineScope,
    private val sessions: SessionManager,
    private val http: NativeHttpClient,
    private val submissions: SubmissionCoordinator,
    private val drafts: AccountDrafts,
    private val clockMs: () -> Long = { Clock.System.now().toEpochMilliseconds() },
    private val timerClockMs: () -> Long = monotonicTimerClock(),
    private val onChanged: suspend () -> Unit = {},
) {
    private val mutableState = MutableStateFlow<TimersState?>(null)
    val state: StateFlow<TimersState?> = mutableState.asStateFlow()
    private val visible = MutableStateFlow(false)
    private val owners = mutableSetOf<Any>()
    private var boundLease: SessionLease? = null
    private var draftRestored = false
    private var draftVersion = 0L
    private var expiryReadAt: Long? = null
    private var expiryReadJob: Job? = null
    private val readLock = Mutex()
    private val mutationLock = Mutex()
    private val draftLock = Mutex()

    init {
        scope.launch {
            combine(sessions.leases, sessions.identity, visible) { lease, identity, active -> Triple(lease, identity, active) }
                .collectLatest { (lease, identity, active) ->
                    bind(lease, identity)
                    if (!active || lease == null || identity == null) return@collectLatest
                    coroutineScope {
                        launch { while (owned(lease)) { delay(250); tick() } }
                        refresh()
                    }
                }
        }
    }

    fun activate(owner: Any = DEFAULT_OWNER) {
        bind(sessions.lease(), sessions.identity.value)
        owners.add(owner)
        visible.value = owners.isNotEmpty()
    }
    fun deactivate(owner: Any = DEFAULT_OWNER) {
        owners.remove(owner)
        visible.value = owners.isNotEmpty()
        if (!visible.value) cancelExpiryRead()
    }
    suspend fun foreground() { refresh() }
    fun nowEpochMillis(): Long = clockMs()
    fun localAt(instant: String): String = reminderLocalInput(instant, state.value?.timezone ?: "UTC")
    fun setReminderAt(instant: String) {
        state.value?.draft?.let { updateDraft(it.copy(at = localAt(instant), exactAt = Instant.parse(instant).toString())) }
    }

    suspend fun refresh() {
        if (!visible.value) return
        val lease = sessions.lease() ?: return
        bind(lease, sessions.identity.value)
        if (state.value?.canRead != true) return
        if (!draftRestored && restoreDraft(lease)) {
            if (!owned(lease)) return
            draftRestored = true
            change(lease) { copy(draftReady = true) }
        }
        refreshReads(lease)
        try { mutationLock.withLock { if (owned(lease)) refreshPending(lease, consume = true) } }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { change(lease) { copy(error = GeneratedStrings.Assistant.draftStorageError) } }
    }

    suspend fun invalidate(invalidation: EventInvalidation) {
        if (invalidation is EventInvalidation.Domains && EventDomain.Timers !in invalidation.values) return
        mutableState.update { it?.copy(running = it.running.copy(stale = it.running.data != null),
            reminders = it.reminders.copy(stale = it.reminders.data != null)) }
        refresh()
    }

    /** A zero display does not mean fired. Leave the card until a server read removes it. */
    suspend fun tick() {
        if (!visible.value) return
        val lease = sessions.lease() ?: return
        val now = timerClockMs()
        change(lease) { copy(nowMillis = now) }
        val current = state.value ?: return
        if (current.running.data.orEmpty().none { current.remaining(it) == 0 }) { expiryReadAt = null; return }
        if (expiryReadJob?.isActive == true) return
        val due = expiryReadAt
        if (due == null) expiryReadAt = now + 2_000
        else if (now >= due) {
            expiryReadAt = null
            // A slow GET or an occupied read lock cannot pause other timers' local displays.
            // One in-flight expiry read also prevents ticks from queuing more of them.
            expiryReadJob = scope.launch { refreshReads(lease) }
        }
    }

    suspend fun start(minutes: Int, label: String = ""): TimerReceipt? = mutate { lease ->
        val operation = try { startTimerOperation(minutes, label) } catch (_: Exception) {
            change(lease) { copy(error = GeneratedStrings.Errors.invalid) }; return@mutate null
        }
        deliver(lease, operation)
    }

    suspend fun cancel(timer: Timer): TimerReceipt? = mutate { lease ->
        val latest = state.value?.running?.data?.firstOrNull { it.id == timer.id } ?: timer
        deliver(lease, cancelTimerOperation(latest))
    }

    fun beginReminder(task: Task? = null) {
        val current = state.value ?: return
        if (!current.canWrite || !current.draftReady || current.busy || current.pending.isNotEmpty() || task?.status == "done" || task?.deletedAt != null) return
        val instant = Instant.fromEpochMilliseconds(clockMs() + if (task == null) 3_600_000 else 7_200_000).toString()
        val draft = ReminderDraft(text = task?.title?.take(200).orEmpty(),
            at = localAt(instant), taskId = task?.id, exactAt = instant)
        updateDraft(draft)
    }

    fun updateDraft(draft: ReminderDraft) {
        val lease = sessions.lease() ?: return
        if (!owned(lease) || state.value?.draftReady != true || state.value?.busy == true || state.value?.pending?.isNotEmpty() == true) return
        draftVersion++
        val version = draftVersion
        change(lease) { copy(draft = draft, error = null) }
        scope.launch {
            try { persistDraft(lease, draft, version) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { change(lease) { copy(error = GeneratedStrings.Assistant.draftStorageError) } }
        }
    }

    suspend fun dismissDraft() {
        val lease = sessions.lease() ?: return
        if (!owned(lease) || state.value?.busy == true || state.value?.pending?.isNotEmpty() == true) return
        draftVersion++
        draftLock.withLock { drafts.delete(lease, REMINDER_DRAFT) }
        change(lease) { copy(draft = null, error = null) }
    }

    suspend fun submitReminder(): TimerReceipt? = mutate { lease ->
        val draft = state.value?.draft ?: return@mutate null
        val operation = try {
            createReminderOperation(draft, state.value?.timezone ?: "UTC").also {
                val instant = Instant.parse(it.payload.getValue("at").jsonPrimitive.content).toEpochMilliseconds()
                require(instant > clockMs() && instant <= clockMs() + 366L * 86_400_000)
            }
        } catch (_: Exception) {
            val past = runCatching { Instant.parse(reminderInstant(draft, state.value?.timezone ?: "UTC")).toEpochMilliseconds() <= clockMs() }.getOrDefault(false)
            change(lease) { copy(error = if (past) GeneratedStrings.Tasks.reminderPast else GeneratedStrings.Errors.invalid) }
            return@mutate null
        }
        persistDraft(lease, draft, draftVersion)
        deliver(lease, operation, REMINDER_DRAFT)
    }

    suspend fun cancelReminder(reminder: Reminder): TimerReceipt? = mutate { lease ->
        val latest = state.value?.reminders?.data?.firstOrNull { it.id == reminder.id } ?: reminder
        deliver(lease, cancelReminderOperation(latest))
    }

    suspend fun check(key: String) = mutationLock.withLock {
        val lease = sessions.lease() ?: return@withLock
        if (!owned(lease)) return@withLock
        try {
            val entry = submissions.pending(lease).firstOrNull { it.endpoint == TIMER_OPERATIONS && it.key == key } ?: return@withLock
            change(lease) { copy(busy = true, error = null) }
            settle(lease, submissions.check(lease, entry.key))
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { change(lease) { copy(error = GeneratedStrings.Errors.network) } }
        finally { change(lease) { copy(busy = false) }; pendingOrError(lease) }
    }

    private suspend fun mutate(block: suspend (SessionLease) -> TimerReceipt?): TimerReceipt? = mutationLock.withLock {
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

    private suspend fun deliver(lease: SessionLease, operation: TimerOperation, target: String? = null): TimerReceipt? =
        settle(lease, submissions.submit(lease, NewSubmission(TIMER_OPERATIONS, TIMER_LOOKUP, operation.bytes(), "timers",
            revisions = operation.expectedRevisions.mapValues { it.value.toString() }, draftTarget = target)))

    private suspend fun settle(lease: SessionLease, entry: Submission): TimerReceipt? {
        if (!owned(lease)) return null
        if (entry.state == SubmissionState.RESOLVED && entry.response != null) {
            val receipt = applyReceipt(lease, entry)
            if (visible.value) refreshReads(lease)
            onChanged()
            if (currentReadsSucceeded()) submissions.acknowledge(lease, entry.key)
            return receipt
        }
        if (entry.state == SubmissionState.REJECTED) {
            val conflict = entry.failureStatus == 409
            change(lease) { copy(error = if (conflict) GeneratedStrings.Errors.conflict else GeneratedStrings.Errors.server) }
            if (visible.value) refreshReads(lease)
        }
        return null
    }

    private suspend fun applyReceipt(lease: SessionLease, entry: Submission): TimerReceipt {
        val response = requireNotNull(entry.response)
        validateTimerSubmissionReceipt(entry, response)
        val receipt = decodeTimerReceipt(response)
        if (entry.draftTarget == REMINDER_DRAFT) draftLock.withLock {
            val stored = drafts.read(lease, REMINDER_DRAFT)?.let { NativeJson.decodeFromString<ReminderDraft>(it.json.decodeToString()) }
            if (stored != null) {
                val body = timerObject(entry.body)
                if (runCatching { timerObject(createReminderOperation(stored, state.value?.timezone ?: "UTC").bytes()) == body }.getOrDefault(false)) {
                    drafts.delete(lease, REMINDER_DRAFT)
                    if (state.value?.draft == stored) { draftVersion++; change(lease) { copy(draft = null) } }
                }
            }
        }
        change(lease) { copy(receipt = receipt, error = null) }
        return receipt
    }

    private suspend fun refreshPending(lease: SessionLease, consume: Boolean = false) {
        val entries = submissions.pending(lease).filter { it.endpoint == TIMER_OPERATIONS }.map {
            if (consume && it.state in setOf(SubmissionState.SENDING, SubmissionState.ACCEPTED, SubmissionState.UNCERTAIN)) submissions.check(lease, it.key) else it
        }
        if (consume) {
            val resolved = entries.filter { it.state == SubmissionState.RESOLVED }
            for (entry in resolved) applyReceipt(lease, entry)
            if (resolved.isNotEmpty()) { refreshReads(lease); onChanged() }
            if (currentReadsSucceeded()) for (entry in resolved) submissions.acknowledge(lease, entry.key)
        }
        change(lease) { copy(pending = entries.filter { it.state !in setOf(SubmissionState.RESOLVED, SubmissionState.REJECTED) }) }
    }

    private suspend fun pendingOrError(lease: SessionLease) {
        try { refreshPending(lease) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { change(lease) { copy(error = GeneratedStrings.Assistant.draftStorageError) } }
    }

    private fun currentReadsSucceeded(): Boolean = state.value?.let {
        it.running.data != null && it.running.error == null && it.reminders.data != null && it.reminders.error == null
    } == true

    private suspend fun refreshReads(lease: SessionLease) = readLock.withLock {
        if (!owned(lease) || state.value?.canRead != true) return@withLock
        change(lease) { copy(running = running.copy(loading = true, error = null), reminders = reminders.copy(loading = true, error = null)) }
        val running = read(lease, "/v1/timers/running", ::decodeTimers)
        val readAt = timerClockMs()
        change(lease) {
            val recent = if (running.data != null) this.running.data.orEmpty()
                .filter { old -> running.data.none { it.id == old.id } && finished.none { it.id == old.id } }
                .map { FinishedTimer(it.id, it.label) } else emptyList()
            copy(running = if (running.data != null) running else this.running.copy(loading = false, error = running.error),
                readAtMillis = if (running.data != null) readAt else readAtMillis, nowMillis = readAt,
                finished = recent + finished)
        }
        val reminders = read(lease, "/v1/timers/reminders", ::decodeReminders)
        change(lease) { copy(reminders = if (reminders.data != null) reminders else this.reminders.copy(loading = false, error = reminders.error)) }
    }

    private suspend fun <T> read(lease: SessionLease, path: String, decode: (ByteArray) -> T): TimerQuery<T> {
        val response = http.request("GET", path, lease = lease)
        return if (response is HttpOutcome.Success) try { TimerQuery(decode(response.body)) }
            catch (_: Exception) { TimerQuery(error = GeneratedStrings.Errors.server) }
        else TimerQuery(error = nativeError(response))
    }

    private suspend fun persistDraft(lease: SessionLease, draft: ReminderDraft, version: Long) = draftLock.withLock {
        if (version != draftVersion || state.value?.draft != draft) return@withLock
        drafts.save(lease, REMINDER_DRAFT, NativeJson.encodeToString(draft).encodeToByteArray(), version)
    }
    private suspend fun restoreDraft(lease: SessionLease): Boolean {
        val version = draftVersion
        try {
            val stored = drafts.read(lease, REMINDER_DRAFT) ?: return true
            val draft = NativeJson.decodeFromString<ReminderDraft>(stored.json.decodeToString())
            if (!owned(lease) || version != draftVersion) return false
            draftVersion = maxOf(draftVersion, stored.revision)
            change(lease) { copy(draft = draft) }
            return true
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { change(lease) { copy(error = GeneratedStrings.Assistant.draftStorageError) }; return false }
    }

    private fun bind(lease: SessionLease?, identity: NativeIdentity?) {
        if (lease == null || identity == null || identity.id != lease.namespace.userId) {
            cancelExpiryRead()
            boundLease = null; mutableState.value = null; draftRestored = false; return
        }
        val changed = boundLease !== lease
        if (changed) { draftRestored = false; draftVersion = 0; cancelExpiryRead() }
        boundLease = lease
        mutableState.update { old ->
            val read = "timers.read" in identity.scopes
            val write = "timers.write" in identity.scopes
            if (!read) { draftRestored = false; cancelExpiryRead() }
            if (changed || old == null || !read) TimersState(lease.namespace, read, write, identity.timezone, nowMillis = timerClockMs())
            else old.copy(canRead = read, canWrite = write, timezone = identity.timezone)
        }
    }
    private fun owned(lease: SessionLease) = boundLease === lease && sessions.isCurrent(lease)
    private fun cancelExpiryRead() {
        expiryReadJob?.cancel()
        expiryReadJob = null
        expiryReadAt = null
    }
    private fun change(lease: SessionLease, update: TimersState.() -> TimersState) {
        if (!owned(lease)) return
        val identity = sessions.identity.value ?: return
        mutableState.update { before ->
            if ("timers.read" !in identity.scopes) TimersState(lease.namespace, false, false, identity.timezone)
            else before?.copy(canRead = true, canWrite = "timers.write" in identity.scopes, timezone = identity.timezone)?.update()
        }
    }
    private companion object {
        val DEFAULT_OWNER = Any()
        const val REMINDER_DRAFT = "timers/reminder"
    }
}
