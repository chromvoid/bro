package assistant.mobile.core.body

import assistant.mobile.core.AccountNamespace
import assistant.mobile.core.EventDomain
import assistant.mobile.core.EventInvalidation
import assistant.mobile.core.GeneratedStrings
import assistant.mobile.core.HttpOutcome
import assistant.mobile.core.LocalizedString
import assistant.mobile.core.NativeHttpClient
import assistant.mobile.core.NativeJson
import assistant.mobile.core.SessionLease
import assistant.mobile.core.SessionManager
import assistant.mobile.core.journal.NewSubmission
import assistant.mobile.core.journal.StaleJournalSession
import assistant.mobile.core.journal.Submission
import assistant.mobile.core.journal.SubmissionCoordinator
import assistant.mobile.core.journal.SubmissionState
import assistant.mobile.core.nativeError
import assistant.mobile.core.personal.NativePersonalHistoryApi
import assistant.mobile.core.personal.PERSONAL_LOOKUP
import assistant.mobile.core.personal.PERSONAL_OPERATIONS
import assistant.mobile.core.personal.PersonalHistoryPage
import assistant.mobile.core.personal.PersonalHistoryReader
import assistant.mobile.core.personal.PersonalOperation
import assistant.mobile.core.personal.PersonalQuery
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.minus
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

const val BODY_PRESENTATION_PATH = "/v1/personal/body/presentation"
const val BODY_PRESENTATION_OPERATION = "personal.body.presentation.set"
data class BodyPresentation(val character: BodyCharacter, val revision: Long)
data class BodyPresentationState(
    val namespace: AccountNamespace,
    val canRead: Boolean,
    val canWrite: Boolean,
    val presentation: BodyPresentation? = null,
    val loading: Boolean = false,
    val busy: Boolean = false,
    val attempted: Boolean = false,
    val error: LocalizedString? = null,
    val pending: List<Submission> = emptyList(),
    val history: PersonalQuery<PersonalHistoryPage> = PersonalQuery(),
    val historyDay: String? = null,
    val historyTimezone: String? = null,
) {
    val ready: Boolean get() = presentation != null || attempted
    val character: BodyCharacter get() = presentation?.character ?: BodyCharacter.Sculpture
    val writable: Boolean get() = canWrite && presentation != null && !busy && pending.isEmpty()
}
fun decodeBodyPresentation(bytes: ByteArray): BodyPresentation = decodeBodyPresentation(
    NativeJson.parseToJsonElement(bytes.decodeToString(throwOnInvalidSequence = true)).jsonObject)
fun decodeBodyPresentation(row: JsonObject): BodyPresentation {
    val character = requireNotNull((row["character_id"] as? JsonPrimitive)?.takeIf { it.isString }?.content)
    val revision = requireNotNull((row["revision"] as? JsonPrimitive)?.takeUnless { it.isString }?.longOrNull).also { require(it >= 0) }
    return BodyPresentation(BodyCharacter.fromWire(character), revision)
}
fun bodyPresentationOperation(character: BodyCharacter, revision: Long): PersonalOperation {
    require(revision >= 0)
    return PersonalOperation(BODY_PRESENTATION_OPERATION, buildJsonObject { put("character_id", character.wire); put("revision", revision) })
}
fun isBodyPresentationSubmission(entry: Submission): Boolean = entry.endpoint == PERSONAL_OPERATIONS && runCatching {
    NativeJson.parseToJsonElement(entry.body.decodeToString()).jsonObject["kind"]?.jsonPrimitive?.content == BODY_PRESENTATION_OPERATION
}.getOrDefault(false)
fun validateBodyPresentationSubmissionReceipt(entry: Submission, response: ByteArray) {
    require(isBodyPresentationSubmission(entry))
    val command = NativeJson.parseToJsonElement(entry.body.decodeToString()).jsonObject
    val payload = command.getValue("payload").jsonObject
    val receipt = NativeJson.parseToJsonElement(response.decodeToString()).jsonObject
    require((receipt["operation_id"] as? JsonPrimitive)?.takeIf { it.isString }?.content?.isNotBlank() == true)
    require(receipt["kind"]?.jsonPrimitive?.content == BODY_PRESENTATION_OPERATION)
    val saved = decodeBodyPresentation(receipt.getValue("body_presentation").jsonObject)
    require(saved.character.wire == payload["character_id"]?.jsonPrimitive?.content)
    require(saved.revision == requireNotNull(payload["revision"]?.jsonPrimitive?.longOrNull) + 1)
}

/** Account-owned revisions use the existing write journal; recovery only looks up its saved key. */
class BodyPresentationController(
    private val scope: CoroutineScope,
    private val sessions: SessionManager,
    private val http: NativeHttpClient,
    private val submissions: SubmissionCoordinator,
    private val historyReader: PersonalHistoryReader = NativePersonalHistoryApi(http),
) {
    private val mutableState = MutableStateFlow<BodyPresentationState?>(null)
    val state: StateFlow<BodyPresentationState?> = mutableState.asStateFlow()
    private val visible = MutableStateFlow(false)
    private val owners = mutableMapOf<Any, SessionLease>()
    private var boundLease: SessionLease? = null
    private val reads = Mutex()
    private val writes = Mutex()
    private val historyReads = Mutex()
    init {
        scope.launch {
            combine(sessions.leases, sessions.identity, visible) { lease, identity, active -> Triple(lease, identity, active) }
                .collectLatest { (lease, _, active) ->
                    bind()
                    if (active && lease != null) refresh()
                }
        }
    }
    fun activate(day: String? = null, owner: Any = DEFAULT_OWNER) {
        val lease = bind() ?: return
        owners[owner] = lease
        if (day != null) {
            require(LocalDate.parse(day).toString() == day)
            change(lease) { if (historyDay == day) this else copy(historyDay = day, history = PersonalQuery()) }
        }
        visible.value = true
    }
    fun deactivate(owner: Any = DEFAULT_OWNER) { owners.remove(owner); visible.value = owners.values.any { it === boundLease } }
    suspend fun refresh() {
        val lease = bind() ?: return
        if (!visible.value || state.value?.canRead != true) return
        reads.withLock {
            if (!owned(lease)) return@withLock
            change(lease) { copy(loading = true, error = null) }
            try {
                val outcome = http.request("GET", BODY_PRESENTATION_PATH, lease = lease)
                if (outcome is HttpOutcome.Success) {
                    val saved = decodeBodyPresentation(outcome.body)
                    change(lease) { copy(presentation = if (presentation == null || saved.revision >= requireNotNull(presentation).revision) saved else presentation,
                        attempted = true, loading = false) }
                } else change(lease) { copy(loading = false, attempted = true, error = nativeError(outcome)) }
                val entries = submissions.pending(lease).filter(::isBodyPresentationSubmission)
                for (entry in entries) {
                    val found = if (entry.state in setOf(SubmissionState.SENDING, SubmissionState.UNCERTAIN, SubmissionState.ACCEPTED)) submissions.check(lease, entry.key) else entry
                    settle(lease, found)
                }
                pending(lease)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: StaleJournalSession) { /* A new session owns presentation. */ }
            catch (_: Exception) { change(lease) { copy(loading = false, attempted = true, error = GeneratedStrings.Errors.server) } }
            finally { change(lease) { copy(loading = false) } }
        }
        val before = state.value
        if (before?.historyDay != null && before.historyTimezone != null) history(before.historyDay, before.historyTimezone)
    }
    suspend fun invalidate(invalidation: EventInvalidation) {
        if (invalidation is EventInvalidation.Domains && EventDomain.Personal !in invalidation.values) return
        mutableState.update { it?.copy(history = it.history.copy(stale = it.history.data != null)) }
        refresh()
    }
    /** Dedicated overview query; opening its comparisons never changes the History screen range. */
    suspend fun history(day: String, timezone: String): PersonalHistoryPage? {
        require(LocalDate.parse(day).toString() == day); TimeZone.of(timezone)
        val lease = bind() ?: return null
        if (state.value?.canRead != true) return null
        val cached = state.value
        if (cached?.historyDay == day && cached.historyTimezone == timezone && cached.history.data != null && !cached.history.stale) return cached.history.data
        change(lease) { if (historyDay == day && historyTimezone == timezone) copy(history = history.copy(loading = true, error = null))
            else copy(historyDay = day, historyTimezone = timezone, history = PersonalQuery(loading = true)) }
        return historyReads.withLock {
            if (!owned(lease) || state.value?.historyDay != day || state.value?.historyTimezone != timezone) return@withLock null
            val current = state.value
            if (current?.history?.data != null && !current.history.stale && !current.history.loading) return@withLock current.history.data
            try {
                val page = historyReader.history(LocalDate.parse(day).minus(364, DateTimeUnit.DAY).toString(), day, "body", lease)
                change(lease) { if (historyDay == day && historyTimezone == timezone) copy(history = PersonalQuery(page)) else this }
                if (owned(lease)) page else null
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { change(lease) { if (historyDay == day && historyTimezone == timezone) copy(history = history.copy(loading = false,
                error = GeneratedStrings.Errors.network, stale = history.data != null)) else this }; null }
        }
    }
    suspend fun select(character: BodyCharacter) {
        val lease = bind() ?: return
        scope.async {
            writes.withLock {
                if (!owned(lease)) return@withLock
                try {
                    pending(lease)
                    val before = state.value ?: return@withLock
                    if (!before.writable || before.character == character) return@withLock
                    val revision = requireNotNull(before.presentation).revision
                    change(lease) { copy(busy = true, error = null) }
                    val result = submissions.submit(lease, NewSubmission(PERSONAL_OPERATIONS, PERSONAL_LOOKUP,
                        bodyPresentationOperation(character, revision).bytes(), "personal", revisions = mapOf("body_presentation" to revision.toString())))
                    settle(lease, result)
                    pending(lease)
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (_: StaleJournalSession) { /* A new session owns presentation. */ }
                catch (_: Exception) { change(lease) { copy(error = GeneratedStrings.Assistant.draftStorageError) } }
                finally { change(lease) { copy(busy = false) } }
            }
        }.await()
    }
    suspend fun check(key: String) {
        val lease = bind() ?: return
        writes.withLock {
            if (!owned(lease)) return@withLock
            try {
                if (submissions.pending(lease).none { it.key == key && isBodyPresentationSubmission(it) }) return@withLock
                change(lease) { copy(busy = true, error = null) }
                settle(lease, submissions.check(lease, key)); pending(lease)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: StaleJournalSession) { /* A new session owns presentation. */ }
            catch (_: Exception) { change(lease) { copy(error = GeneratedStrings.Errors.network) } }
            finally { change(lease) { copy(busy = false) } }
        }
    }
    private suspend fun settle(lease: SessionLease, result: Submission) {
        if (!owned(lease)) return
        if (result.state == SubmissionState.RESOLVED && result.response != null) {
            validateBodyPresentationSubmissionReceipt(result, result.response)
            val saved = decodeBodyPresentation(NativeJson.parseToJsonElement(result.response.decodeToString()).jsonObject.getValue("body_presentation").jsonObject)
            change(lease) { copy(presentation = if (presentation == null || saved.revision >= requireNotNull(presentation).revision) saved else presentation, error = null) }
            submissions.acknowledge(lease, result.key)
        } else if (result.state == SubmissionState.REJECTED) {
            change(lease) { copy(error = if (result.failureStatus == 409) GeneratedStrings.Personal.conflict else GeneratedStrings.Errors.server) }
            if (!result.mayHaveBeenAdmitted) submissions.discardRefused(lease, result.key)
        }
    }
    private suspend fun pending(lease: SessionLease) {
        val entries = submissions.pending(lease).filter { isBodyPresentationSubmission(it) && it.state !in setOf(SubmissionState.RESOLVED, SubmissionState.REJECTED) }
        change(lease) { copy(pending = entries) }
    }
    private fun bind(): SessionLease? {
        val lease = sessions.lease(); val identity = sessions.identity.value
        if (lease == null || identity == null || identity.id != lease.namespace.userId) {
            boundLease = null; mutableState.value = null; owners.clear(); visible.value = false; return null
        }
        if (boundLease !== lease) {
            boundLease = lease; owners.entries.removeAll { it.value !== lease }; visible.value = owners.isNotEmpty()
            mutableState.value = BodyPresentationState(lease.namespace, "personal.read" in identity.scopes, "personal.write" in identity.scopes)
        } else mutableState.update { old -> old?.let {
            if ("personal.read" !in identity.scopes) BodyPresentationState(lease.namespace, false, false)
            else it.copy(canRead = true, canWrite = "personal.write" in identity.scopes)
        } }
        return lease
    }
    private fun owned(lease: SessionLease) = sessions.isCurrent(lease) && boundLease === lease
    private fun change(lease: SessionLease, update: BodyPresentationState.() -> BodyPresentationState) {
        if (owned(lease) && "personal.read" in sessions.identity.value?.scopes.orEmpty()) mutableState.update { it?.update() }
    }
    private companion object { val DEFAULT_OWNER = Any() }
}
