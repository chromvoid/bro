package assistant.mobile.core.journal

import assistant.mobile.core.HttpOutcome
import assistant.mobile.core.NativeHttpClient
import assistant.mobile.core.SessionLease
import assistant.mobile.core.SessionManager
import assistant.mobile.core.decodeSubmissionReceipt
import assistant.mobile.core.retryAfterDeadline
import assistant.mobile.core.assistant.AssistantClarificationStatus
import assistant.mobile.core.assistant.AssistantControlStatus
import assistant.mobile.core.assistant.validateAssistantClarificationReceipt
import assistant.mobile.core.assistant.validateAssistantControlReceipt
import assistant.mobile.core.plugins.pluginResult
import assistant.mobile.core.plugins.pluginString
import assistant.mobile.core.plugins.pluginTerminalStates
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid
import kotlin.time.Clock

/** A new user action, supplied as JSON bytes once; this is never a background command queue. */
data class NewSubmission(
    val endpoint: String,
    val lookupPrefix: String?,
    val body: ByteArray,
    val audience: String,
    val revisions: Map<String, String> = emptyMap(),
    val draftTarget: String? = null,
    val method: String = "POST",
    val found: SubmissionState = SubmissionState.RESOLVED,
    val key: String? = null,
) {
    override fun toString(): String = "NewSubmission(endpoint=$endpoint, body=redacted)"
}

/** The server may have admitted a write whose final local transaction failed. */
class UnpersistedSubmissionOutcome(val submission: Submission, cause: Throwable) :
    Exception("The delivery outcome could not be saved", cause)

class SubmissionRetryBlocked(message: String) : Exception(message)

/** Domain adapters validate receipts before a response can settle a command. */
fun interface SubmissionReceiptValidator {
    fun validate(entry: Submission, response: ByteArray)
}

private object SubmissionLocks {
    private val guard = Mutex()
    private val keys = mutableMapOf<Pair<assistant.mobile.core.AccountNamespace, String>, Mutex>()
    suspend fun <T> run(lease: SessionLease, key: String, block: suspend () -> T): T {
        val mutex = guard.withLock { keys.getOrPut(lease.namespace to key) { Mutex() } }
        return mutex.withLock { block() }
    }
}

/**
 * Write-ahead delivery, with no automatic resubmission. Startup and SSE recovery only GET a
 * stored lookup. A user retry reuses the original key, endpoint and exact body after lookup.
 */
class SubmissionCoordinator(
    private val store: JournalStore,
    private val http: NativeHttpClient,
    private val session: SessionManager,
    private val now: () -> Long = { Clock.System.now().toEpochMilliseconds() },
    private val validateReceipt: SubmissionReceiptValidator = SubmissionReceiptValidator { _, _ -> },
    private val createKey: () -> String = ::newSubmissionKey,
) {
    suspend fun submit(lease: SessionLease, request: NewSubmission): Submission {
        current(lease)
        require(request.method in setOf("POST", "PUT", "DELETE"))
        val lookupPrefix = journalLookupPrefix(request.endpoint)
        require(request.lookupPrefix == null || request.lookupPrefix == lookupPrefix) { "Unexpected receipt lookup route" }
        val bytes = request.body.copyOf()
        Json.parseToJsonElement(bytes.decodeToString(throwOnInvalidSequence = true))
        val key = request.key ?: createKey()
        requireSubmissionKey(key)
        val entry = Submission(
            namespace = lease.namespace,
            key = key,
            endpoint = request.endpoint,
            lookup = request.lookupPrefix?.let { it + key },
            method = request.method,
            body = bytes,
            audience = request.audience,
            revisions = request.revisions.toMap(),
            draftTarget = request.draftTarget,
            found = request.found,
            createdAtMillis = now(),
        )
        return SubmissionLocks.run(lease, key) {
            current(lease)
            // ABORT on an existing key: a second submit cannot overwrite durable evidence.
            session.withLease(lease) { store.insertSubmission(entry) } ?: throw StaleJournalSession()
            current(lease)
            transmit(lease, entry)
        }
    }

    suspend fun pending(lease: SessionLease): List<Submission> {
        current(lease)
        return store.submissions(lease.namespace).also { current(lease) }
    }

    /** Domain callers first prove durable history owns the admission and the draft handoff. */
    suspend fun acknowledge(lease: SessionLease, key: String) = SubmissionLocks.run(lease, key) {
        current(lease)
        val entry = store.submission(lease.namespace, key) ?: return@run
        current(lease)
        require(entry.state == SubmissionState.ACCEPTED || entry.state == SubmissionState.RESOLVED)
        session.withLease(lease) { store.deleteSubmission(lease.namespace, key) }
            ?: throw StaleJournalSession()
    }

    /** A definitive refusal was never admitted; its screen has displayed the typed error. */
    suspend fun discardRefused(lease: SessionLease, key: String) = SubmissionLocks.run(lease, key) {
        val entry = owned(lease, key)
        require(entry.state == SubmissionState.REJECTED && !entry.mayHaveBeenAdmitted)
        session.withLease(lease) { store.deleteSubmission(lease.namespace, key) } ?: throw StaleJournalSession()
    }

    /** Call only after the foreground identity refresh. No path here dispatches a command. */
    suspend fun recover(lease: SessionLease): List<Submission> {
        val result = mutableListOf<Submission>()
        for (snapshot in pending(lease)) {
            val recovered = SubmissionLocks.run(lease, snapshot.key) {
                current(lease)
                val entry = store.submission(lease.namespace, snapshot.key) ?: return@run null
                current(lease)
                if ((entry.state !in recoverable && entry.state != SubmissionState.ACCEPTED) || knownUnadmitted(entry)) return@run entry
                val uncertain = if (entry.state == SubmissionState.SENDING) {
                    save(lease, entry.copy(state = SubmissionState.UNCERTAIN))
                } else entry
                if (uncertain.lookup == null) uncertain else lookup(lease, uncertain)
            }
            if (recovered != null) result += recovered
        }
        return result
    }

    suspend fun check(
        lease: SessionLease, key: String,
        onLookupOutcome: ((HttpOutcome) -> Unit)? = null,
    ): Submission = SubmissionLocks.run(lease, key) {
        val entry = owned(lease, key)
        if (entry.lookup == null || knownUnadmitted(entry) || entry.state == SubmissionState.RESOLVED) entry
        else lookup(lease, entry, onLookupOutcome)
    }

    /** Explicitly stop tracking a non-keyed delivery after the person inspected its domain read. */
    suspend fun abandonUnkeyed(lease: SessionLease, key: String) = SubmissionLocks.run(lease, key) {
        val entry = owned(lease, key)
        require(entry.lookup == null && isUnkeyedJournalEndpoint(entry.endpoint))
        require(entry.state != SubmissionState.SENDING)
        session.withLease(lease) { store.deleteSubmission(lease.namespace, key) } ?: throw StaleJournalSession()
    }

    /** An explicit user action, never called by startup, network reconnect or SSE resync. */
    suspend fun retry(lease: SessionLease, key: String): Submission = SubmissionLocks.run(lease, key) {
        var entry = owned(lease, key)
        if (entry.state !in recoverable) throw SubmissionRetryBlocked("The write cannot be resent in ${entry.state}")
        if (entry.retryAtMillis?.let { now() < it } == true) throw SubmissionRetryBlocked("Retry-After has not elapsed")
        if (entry.mayHaveBeenAdmitted && entry.lookup == null) {
            throw SubmissionRetryBlocked("This write has no receipt lookup; refresh its state before a new action")
        }
        if (entry.lookup != null && !knownUnadmitted(entry)) {
            entry = lookup(lease, entry)
            if (entry.state != SubmissionState.UNCERTAIN) return@run entry
            // Only a definite 404 permits the explicit retry. Transport or auth failure does not.
            if (entry.failureStatus != 404) throw SubmissionRetryBlocked("The previous write could not be looked up")
        }
        transmit(lease, entry)
    }

    private suspend fun transmit(lease: SessionLease, entry: Submission): Submission {
        current(lease)
        val priorUnknown = entry.mayHaveBeenAdmitted
        val sending = save(lease, entry.copy(
            state = SubmissionState.SENDING,
            mayHaveBeenAdmitted = true,
            attempt = entry.attempt + 1,
            disposition = AttemptDisposition.UNKNOWN,
            failureStatus = null,
            retryAtMillis = null,
        ))
        current(lease)
        val outcome = try {
            http.request(sending.method, sending.endpoint, sending.body, lease, sending.key,
                preserveRejectionBody = sending.endpoint.startsWith("/v1/plugins/installations/") && sending.endpoint.endsWith("/invoke"))
        } catch (cancelled: CancellationException) {
            withContext(NonCancellable) {
                if (session.isCurrent(lease)) saveOutcome(lease, sending.copy(state = SubmissionState.UNCERTAIN))
            }
            throw cancelled
        }
        current(lease)
        val updated = when (outcome) {
            is HttpOutcome.Success -> {
                try {
                    val state = receiptState(sending, outcome, SubmissionState.RESOLVED)
                    sending.copy(
                        state = state,
                        response = outcome.body.copyOf(),
                        disposition = AttemptDisposition.ADMITTED,
                    )
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    sending.copy(state = SubmissionState.UNCERTAIN)
                }
            }
            is HttpOutcome.Conflict -> {
                if (sending.lookup == null && isUnkeyedJournalEndpoint(sending.endpoint) &&
                    (sending.endpoint.startsWith("/v1/resources/") || sending.endpoint.startsWith("/v1/challenges"))) {
                    refusal(sending, priorUnknown, 409).copy(response = outcome.body.copyOf())
                } else if (sending.lookup == null) sending.copy(state = SubmissionState.UNCERTAIN, failureStatus = 409)
                else {
                    val found = lookup(lease, sending.copy(state = SubmissionState.UNCERTAIN, failureStatus = 409))
                    if (found.failureStatus == 404 && found.state == SubmissionState.UNCERTAIN) {
                        refusal(found, priorUnknown, 409).copy(response = outcome.body.copyOf())
                    } else found
                }
            }
            HttpOutcome.Unauthorized -> refusal(sending, priorUnknown, 401)
            HttpOutcome.Forbidden -> refusal(sending, priorUnknown, 403)
            is HttpOutcome.RateLimited -> refusal(sending, priorUnknown, 429).copy(retryAtMillis = outcome.retryAtMillis)
            is HttpOutcome.Failure -> if (outcome.status in refusedStatuses)
                refusal(sending, priorUnknown, outcome.status).copy(response = outcome.body.copyOf(),
                    retryAtMillis = if (outcome.status == 429) retryAfterDeadline(outcome.headers["retry-after"], now()) else null)
                else sending.copy(state = SubmissionState.UNCERTAIN, failureStatus = outcome.status)
            HttpOutcome.NetworkFailure -> sending.copy(state = SubmissionState.UNCERTAIN)
            HttpOutcome.StaleSession -> throw StaleJournalSession()
            HttpOutcome.RejectedOrigin -> sending.copy(
                state = if (priorUnknown) SubmissionState.UNCERTAIN else SubmissionState.REJECTED,
                mayHaveBeenAdmitted = priorUnknown,
                disposition = AttemptDisposition.NOT_DISPATCHED,
            )
            else -> sending.copy(state = SubmissionState.UNCERTAIN)
        }
        return saveOutcome(lease, updated)
    }

    private suspend fun lookup(
        lease: SessionLease, entry: Submission,
        onLookupOutcome: ((HttpOutcome) -> Unit)? = null,
    ): Submission {
        current(lease)
        val path = entry.lookup ?: return entry
        val outcome = http.request("GET", path, lease = lease,
            preserveRejectionBody = entry.endpoint.startsWith("/v1/plugins/installations/") && entry.endpoint.endsWith("/invoke"))
        current(lease)
        onLookupOutcome?.invoke(outcome)
        val updated = when (outcome) {
            is HttpOutcome.Success -> {
                try {
                    val state = receiptState(entry, outcome, entry.found)
                    entry.copy(state = state, response = outcome.body.copyOf(), mayHaveBeenAdmitted = true,
                        disposition = AttemptDisposition.ADMITTED, failureStatus = null, retryAtMillis = null)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) { entry.copy(
                    state = if (entry.state == SubmissionState.ACCEPTED) SubmissionState.ACCEPTED else SubmissionState.UNCERTAIN,
                    failureStatus = null,
                ) }
            }
            is HttpOutcome.Failure -> if (outcome.status == 404) entry.copy(
                state = if (entry.state == SubmissionState.ACCEPTED) SubmissionState.ACCEPTED else SubmissionState.UNCERTAIN,
                failureStatus = 404,
            ) else entry.copy(failureStatus = outcome.status,
                retryAtMillis = if (outcome.status == 429) retryAfterDeadline(outcome.headers["retry-after"], now()) else entry.retryAtMillis)
            HttpOutcome.Unauthorized -> entry.copy(failureStatus = 401)
            HttpOutcome.Forbidden -> entry.copy(failureStatus = 403)
            is HttpOutcome.RateLimited -> entry.copy(failureStatus = 429, retryAtMillis = outcome.retryAtMillis)
            HttpOutcome.StaleSession -> throw StaleJournalSession()
            else -> entry.copy(failureStatus = null)
        }
        return save(lease, updated)
    }

    /** Resolved is the terminal delivery receipt, never proof of successful domain effects. */
    private fun receiptState(entry: Submission, outcome: HttpOutcome.Success, fallback: SubmissionState): SubmissionState {
        validateReceipt.validate(entry, outcome.body)
        if (entry.endpoint.startsWith("/v1/plugins/installations/") && entry.endpoint.endsWith("/invoke")) {
            val result = requireNotNull(pluginResult(outcome.body))
            return when (result.pluginString("state")) {
                in pluginTerminalStates -> SubmissionState.RESOLVED
                "accepted", "running" -> SubmissionState.ACCEPTED
                else -> throw IllegalArgumentException("Unsupported plugin result state")
            }
        }
        if (entry.endpoint == "/v1/conversation/clarifications") {
            val receipt = validateAssistantClarificationReceipt(entry.body, outcome.body, entry.key)
            return if (receipt.state in setOf(AssistantClarificationStatus.Accepted, AssistantClarificationStatus.Delivered)) {
                SubmissionState.ACCEPTED
            } else SubmissionState.RESOLVED
        }
        if (entry.endpoint == "/v1/conversation/controls") {
            val receipt = validateAssistantControlReceipt(entry.body, outcome.body, entry.key)
            return if (receipt.state == AssistantControlStatus.Accepted) SubmissionState.ACCEPTED else SubmissionState.RESOLVED
        }
        val conversationState = if (entry.endpoint == "/v1/conversation/messages") {
            val receipt = decodeSubmissionReceipt(outcome.body)
            require(receipt.submissionKey == entry.key && receipt.supportedState)
            if (receipt.state == "completed" || receipt.state == "failed") SubmissionState.RESOLVED
            else SubmissionState.ACCEPTED
        } else null
        return if (outcome.status == 202) SubmissionState.ACCEPTED else conversationState ?: fallback
    }

    private fun refusal(entry: Submission, priorUnknown: Boolean, status: Int): Submission = entry.copy(
        state = if (priorUnknown) SubmissionState.UNCERTAIN else SubmissionState.REJECTED,
        mayHaveBeenAdmitted = priorUnknown,
        disposition = AttemptDisposition.REFUSED,
        failureStatus = status,
    )

    private suspend fun owned(lease: SessionLease, key: String): Submission {
        current(lease)
        return (store.submission(lease.namespace, key) ?: throw IllegalArgumentException("Unknown submission")).also { current(lease) }
    }

    private suspend fun save(lease: SessionLease, entry: Submission): Submission {
        current(lease)
        session.withLease(lease) { store.saveSubmission(entry) } ?: throw StaleJournalSession()
        current(lease)
        return entry
    }

    private suspend fun saveOutcome(lease: SessionLease, entry: Submission): Submission = try {
        save(lease, entry)
    } catch (stale: StaleJournalSession) {
        throw stale
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: Exception) {
        throw UnpersistedSubmissionOutcome(entry, failure)
    }

    private fun current(lease: SessionLease) {
        if (!session.isCurrent(lease)) throw StaleJournalSession()
    }

    private companion object {
        val recoverable = setOf(SubmissionState.PREPARED, SubmissionState.SENDING, SubmissionState.UNCERTAIN)
        val refusedStatuses = setOf(400, 401, 403, 404, 413, 415, 422, 429)
        fun knownUnadmitted(entry: Submission) = entry.state == SubmissionState.REJECTED ||
            (entry.state == SubmissionState.PREPARED && !entry.mayHaveBeenAdmitted)
    }
}

@OptIn(ExperimentalUuidApi::class)
private fun newSubmissionKey(): String = Uuid.random().toString()

@OptIn(ExperimentalUuidApi::class)
private fun requireSubmissionKey(key: String) {
    require(Uuid.parse(key).toString() == key) { "A new submission requires a canonical UUID key" }
}

/** Only known domain commands enter the durable journal; credentials use transient HTTP. */
private fun journalLookupPrefix(endpoint: String): String? {
    val known = when (endpoint) {
        "/v1/conversation/messages" -> "/v1/conversation/submissions/by-key/"
        "/v1/conversation/controls" -> "/v1/conversation/controls/by-key/"
        "/v1/conversation/clarifications" -> "/v1/conversation/clarifications/by-key/"
        "/v1/nutrition/operations", "/v1/tasks/operations", "/v1/timers/operations",
        "/v1/activity/operations", "/v1/personal/operations" -> "$endpoint/by-key/"
        else -> null
    }
    if (known != null) return known
    if (isUnkeyedJournalEndpoint(endpoint)) return null
    val parts = endpoint.split('/')
    require(parts.size == 6 && parts.take(4) == listOf("", "v1", "plugins", "installations") &&
        parts[5] == "invoke" && parts[4].length in 1..200 && parts[4] !in setOf(".", "..") &&
        parts[4].all { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it in "._~-" }) {
        "This endpoint must not be persisted in the command journal"
    }
    return endpoint.removeSuffix("/invoke") + "/operations/by-key/"
}

/** These APIs accept a journal key but expose no server receipt lookup. Recovery only reads. */
private fun isUnkeyedJournalEndpoint(endpoint: String): Boolean {
    if (endpoint == "/v1/challenges") return true
    val parts = endpoint.split('/')
    val known = when {
        parts.size == 5 && parts.take(3) == listOf("", "v1", "channels") && parts[4] == "messages" -> true
        parts.size == 4 && parts.take(3) == listOf("", "v1", "resources") -> true
        parts.size == 5 && parts.take(3) == listOf("", "v1", "resources") && parts[4] == "grants" -> true
        parts.size == 5 && parts.take(3) == listOf("", "v1", "challenges") && parts[4] in setOf("join", "leave") -> true
        else -> false
    }
    if (!known) return false
    requireSubmissionKey(parts[3])
    return true
}
