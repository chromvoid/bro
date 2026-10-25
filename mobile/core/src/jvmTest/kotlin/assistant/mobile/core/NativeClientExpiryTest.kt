package assistant.mobile.core

import assistant.mobile.core.LiveSocketFactory
import assistant.mobile.core.dictation.FakeLiveSocket

import assistant.mobile.core.journal.Draft
import assistant.mobile.core.journal.JournalStore
import assistant.mobile.core.journal.StaleJournalSession
import assistant.mobile.core.journal.Submission
import assistant.mobile.core.journal.SubmissionState
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockEngineConfig
import io.ktor.client.engine.mock.respond
import io.ktor.http.headersOf
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull

@OptIn(ExperimentalCoroutinesApi::class)
class NativeClientExpiryTest {
    @Test
    fun slidingExpiryReplacesDeadlineAndPreservesPrivateRecoveryData() = runTest {
        val namespace = AccountNamespace("synthetic", "https://expiry.invalid", "a")
        val identity = NativeIdentity(
            "a", "person", "Person", "ru", "UTC", false, 1, "device-a", "1970-01-01T00:00:01Z",
        )
        val secrets = ExpirySecrets(StoredSession(
            namespace, "synthetic-credential", identity, "device-a", "session-a", identity.sessionExpiresAt,
        ))
        val store = RetentionJournal()
        val json = "{\"text\":\"synthetic draft\"}".encodeToByteArray()
        val uncertain = Submission(
            namespace, "synthetic-key", "/v1/tasks/operations", "/v1/tasks/operations/by-key/synthetic-key",
            "POST", json, "self", emptyMap(), "tasks", SubmissionState.RESOLVED, 0,
            state = SubmissionState.UNCERTAIN, mayHaveBeenAdmitted = true,
        )
        store.insertSubmission(uncertain)
        var requests = 0
        val engine = MockEngine(MockEngineConfig().apply {
            dispatcher = StandardTestDispatcher(testScheduler)
            addHandler { request ->
                requests++
                assertEquals("/v1/me", request.url.encodedPath)
                respond(
                    NativeJson.encodeToString(identity.copy(sessionExpiresAt = "1970-01-01T00:00:02Z")),
                    headers = headersOf("Content-Type", "application/json"),
                )
            }
        })
        val core = NativeClientCore(
            backgroundScope, namespace.instance, namespace.origin, secrets, store, engine,
            object : NativeClientData {
                override suspend fun refetchAll(lease: SessionLease) = Unit
                override suspend fun invalidate(lease: SessionLease, invalidation: EventInvalidation) = Unit
            },
            liveSockets = LiveSocketFactory { _, listener -> FakeLiveSocket(listener) },
            clockMs = { testScheduler.currentTime },
        )
        try {
            core.restore()
            runCurrent()
            val lease = assertNotNull(core.sessions.lease())
            core.drafts.save(lease, "tasks", json, 1)
            advanceTimeBy(900)
            core.sessions.refreshIdentity(lease)
            runCurrent()
            advanceTimeBy(1_099)
            runCurrent()
            assertNotNull(core.sessions.lease())
            advanceTimeBy(1)
            runCurrent()
            assertNull(core.sessions.lease())
            assertNull(secrets.record)
            assertContentEquals(json, store.draft(namespace, "tasks")?.json)
            assertEquals(SubmissionState.UNCERTAIN, store.submission(namespace, uncertain.key)?.state)
            assertEquals(0, store.purges)
            assertEquals(1, requests)
            assertFailsWith<StaleJournalSession> { core.drafts.save(lease, "tasks", json, 2) }
        } finally {
            core.close()
        }
    }
}

private class ExpirySecrets(var record: StoredSession?) : SessionSecretStore {
    override suspend fun load(instance: String, origin: String) = record
    override suspend fun save(session: StoredSession) { record = session }
    override suspend fun delete(instance: String, origin: String) { record = null }
}

private class RetentionJournal : JournalStore {
    private val entries = mutableMapOf<Pair<AccountNamespace, String>, Submission>()
    private val drafts = mutableMapOf<Pair<AccountNamespace, String>, Draft>()
    private val cursors = mutableMapOf<AccountNamespace, String>()
    var purges = 0
    override suspend fun insertSubmission(entry: Submission) { entries[entry.namespace to entry.key] = entry }
    override suspend fun saveSubmission(entry: Submission) { entries[entry.namespace to entry.key] = entry }
    override suspend fun submission(namespace: AccountNamespace, key: String) = entries[namespace to key]
    override suspend fun submissions(namespace: AccountNamespace) = entries.values.filter { it.namespace == namespace }
    override suspend fun deleteSubmission(namespace: AccountNamespace, key: String) { entries.remove(namespace to key) }
    override suspend fun saveDraft(draft: Draft) { drafts[draft.namespace to draft.target] = draft }
    override suspend fun draft(namespace: AccountNamespace, target: String) = drafts[namespace to target]
    override suspend fun deleteDraft(namespace: AccountNamespace, target: String) { drafts.remove(namespace to target) }
    override suspend fun readCursor(namespace: AccountNamespace) = cursors[namespace]
    override suspend fun writeCursor(namespace: AccountNamespace, cursor: String) { cursors[namespace] = cursor }
    override suspend fun clearAccount(namespace: AccountNamespace) {
        purges++
        entries.keys.removeAll { it.first == namespace }
        drafts.keys.removeAll { it.first == namespace }
        cursors.remove(namespace)
    }
}
