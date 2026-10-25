package assistant.mobile.core.journal

import assistant.mobile.core.AccountNamespace

/** Only client-owned drafts, delivery evidence and the replay cursor belong in this store. */
interface JournalStore {
    suspend fun insertSubmission(entry: Submission)
    suspend fun saveSubmission(entry: Submission)
    suspend fun submission(namespace: AccountNamespace, key: String): Submission?
    suspend fun submissions(namespace: AccountNamespace): List<Submission>
    suspend fun deleteSubmission(namespace: AccountNamespace, key: String)
    suspend fun saveDraft(draft: Draft)
    suspend fun draft(namespace: AccountNamespace, target: String): Draft?
    suspend fun deleteDraft(namespace: AccountNamespace, target: String)
    suspend fun readCursor(namespace: AccountNamespace): String?
    suspend fun writeCursor(namespace: AccountNamespace, cursor: String)
    /** Explicit logout only. Expiry must leave drafts and unknown delivery outcomes intact. */
    suspend fun clearAccount(namespace: AccountNamespace)
    /** Switching servers also erases accounts retained after session expiry. */
    suspend fun clearOrigin(instance: String, origin: String) {
        throw UnsupportedOperationException("Origin-wide journal erasure is unavailable")
    }
}

/** Failure is explicit: callers must never replace an unavailable journal with an empty one. */
class JournalStorageException(cause: Throwable) : Exception("Durable journal unavailable", cause)

/** In-memory generation changed while an operation was suspended. No response may be applied. */
class StaleJournalSession : Exception("The originating account is no longer current")

enum class SubmissionState { PREPARED, SENDING, ACCEPTED, RESOLVED, UNCERTAIN, REJECTED }
enum class AttemptDisposition { UNKNOWN, REFUSED, ADMITTED, NOT_DISPATCHED }

data class Draft(
    val namespace: AccountNamespace,
    val target: String,
    val json: ByteArray,
    val revision: Long,
    val updatedAtMillis: Long,
) {
    override fun toString(): String = "Draft(target=$target, json=redacted)"
}

/** No password, session secret, connection credential, image or audio belongs in [body]. */
data class Submission(
    val namespace: AccountNamespace,
    val key: String,
    val endpoint: String,
    val lookup: String?,
    val method: String,
    /** Exact original UTF-8 JSON bytes, never reconstructed or reserialized for retry. */
    val body: ByteArray,
    val audience: String,
    val revisions: Map<String, String>,
    val draftTarget: String?,
    val found: SubmissionState,
    val createdAtMillis: Long,
    val state: SubmissionState = SubmissionState.PREPARED,
    val response: ByteArray? = null,
    val mayHaveBeenAdmitted: Boolean = false,
    val attempt: Int = 0,
    val disposition: AttemptDisposition = AttemptDisposition.NOT_DISPATCHED,
    val failureStatus: Int? = null,
    val retryAtMillis: Long? = null,
) {
    override fun toString(): String = "Submission(key=$key, state=$state, body=redacted, response=redacted)"

    init {
        require(found == SubmissionState.ACCEPTED || found == SubmissionState.RESOLVED)
    }
}
