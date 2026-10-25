package assistant.mobile.core.journal

import assistant.mobile.core.AccountNamespace
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class RoomJournalStore(database: JournalDatabase) : JournalStore {
    private val dao = database.journalDao()

    /** Account-bound observation of durable delivery evidence, including recovery updates. */
    fun observeSubmissions(namespace: AccountNamespace): Flow<List<Submission>> =
        dao.observeSubmissions(namespace.instance, namespace.origin, namespace.userId)
            .map { rows -> rows.map { it.submission() } }

    override suspend fun insertSubmission(entry: Submission) = durable { dao.insertSubmission(entry.entity()) }
    override suspend fun saveSubmission(entry: Submission) = durable { dao.saveSubmission(entry.entity()) }
    override suspend fun submission(namespace: AccountNamespace, key: String): Submission? = durable {
        dao.submission(namespace.instance, namespace.origin, namespace.userId, key)?.submission()
    }
    override suspend fun submissions(namespace: AccountNamespace): List<Submission> = durable {
        dao.submissions(namespace.instance, namespace.origin, namespace.userId).map { it.submission() }
    }
    override suspend fun deleteSubmission(namespace: AccountNamespace, key: String) = durable {
        dao.deleteSubmission(namespace.instance, namespace.origin, namespace.userId, key)
    }
    override suspend fun saveDraft(draft: Draft) = durable {
        dao.saveDraft(DraftEntity(draft.namespace.instance, draft.namespace.origin, draft.namespace.userId, draft.target, draft.json.copyOf(), draft.revision, draft.updatedAtMillis))
    }
    override suspend fun draft(namespace: AccountNamespace, target: String): Draft? = durable {
        dao.draft(namespace.instance, namespace.origin, namespace.userId, target)?.let {
            Draft(namespace, it.target, it.json.copyOf(), it.revision, it.updatedAtMillis)
        }
    }
    override suspend fun deleteDraft(namespace: AccountNamespace, target: String) = durable {
        dao.deleteDraft(namespace.instance, namespace.origin, namespace.userId, target)
    }
    override suspend fun readCursor(namespace: AccountNamespace): String? = durable {
        dao.cursor(namespace.instance, namespace.origin, namespace.userId)
    }
    override suspend fun writeCursor(namespace: AccountNamespace, cursor: String) = durable {
        dao.saveCursor(CursorEntity(namespace.instance, namespace.origin, namespace.userId, cursor))
    }
    override suspend fun clearAccount(namespace: AccountNamespace) = durable {
        dao.clearAccount(namespace.instance, namespace.origin, namespace.userId)
    }
    override suspend fun clearOrigin(instance: String, origin: String) = durable {
        dao.clearOrigin(instance, origin)
    }

    suspend fun hasOriginData(instance: String, origin: String): Boolean = durable {
        dao.hasOriginData(instance, origin)
    }

    private suspend fun <T> durable(block: suspend () -> T): T = try {
        block()
    } catch (cancelled: CancellationException) {
        // Closing Room cancels its internal job even when our caller is still active.
        // Preserve caller cancellation, but report a closed/unavailable DB as storage failure.
        currentCoroutineContext().ensureActive()
        throw JournalStorageException(cancelled)
    } catch (failure: Exception) {
        throw JournalStorageException(failure)
    }
}

private fun Submission.entity() = SubmissionEntity(
    namespace.instance, namespace.origin, namespace.userId, key, endpoint, lookup, method,
    body.copyOf(), audience, Json.encodeToString(revisions), draftTarget, found.name, createdAtMillis,
    state.name, response?.copyOf(), mayHaveBeenAdmitted, attempt, disposition.name, failureStatus, retryAtMillis,
)

private fun SubmissionEntity.submission() = Submission(
    AccountNamespace(instance, origin, userId), key, endpoint, lookup, method, body.copyOf(), audience,
    Json.decodeFromString<Map<String, String>>(revisions), draftTarget, SubmissionState.valueOf(found),
    createdAtMillis, SubmissionState.valueOf(state), response?.copyOf(), mayHaveBeenAdmitted, attempt,
    AttemptDisposition.valueOf(disposition), failureStatus, retryAtMillis,
)
