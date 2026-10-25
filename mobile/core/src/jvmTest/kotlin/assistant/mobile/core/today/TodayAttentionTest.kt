package assistant.mobile.core.today

import assistant.mobile.core.AccountNamespace
import assistant.mobile.core.NativeIdentity
import assistant.mobile.core.SessionManager
import assistant.mobile.core.SessionSecretStore
import assistant.mobile.core.StoredSession
import assistant.mobile.core.journal.Submission
import assistant.mobile.core.journal.SubmissionState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class TodayAttentionTest {
    @Test
    fun anotherAccountCannotSeePreviousJournalEvidence() = runTest {
        val secrets = AttentionSecrets(record())
        val sessions = SessionManager("today", ORIGIN, secrets, {}, { 0L })
        sessions.restore()
        val firstJournal = MutableStateFlow(listOf(entry("private-key", SubmissionState.UNCERTAIN)))
        val controller = TodayAttentionController(backgroundScope, sessions) { namespace ->
            if (namespace.userId == "a") firstJournal else MutableStateFlow(emptyList())
        }
        controller.activate()
        runCurrent()
        secrets.value = record("b")
        sessions.restore()
        runCurrent()
        assertEquals("b", controller.state.value.namespace?.userId)
        assertTrue(controller.state.value.entries.isEmpty())
    }
}

private const val ORIGIN = "https://today.test"
private fun record(user: String = "a"): StoredSession {
    val identity = NativeIdentity(user, user, user, "ru", "UTC", false, 1,
        "device-$user", "2099-01-01T00:00:00Z", listOf("session.control"))
    return StoredSession(AccountNamespace("today", ORIGIN, user), "credential-$user", identity,
        identity.deviceId, "session-$user", identity.sessionExpiresAt)
}

private fun entry(key: String, state: SubmissionState, endpoint: String = "/v1/conversation/messages") =
    Submission(record().namespace, key, endpoint, null, "POST", "{}".encodeToByteArray(), "a",
        emptyMap(), null, SubmissionState.ACCEPTED, 0, state = state)

private class AttentionSecrets(var value: StoredSession?) : SessionSecretStore {
    override suspend fun load(instance: String, origin: String): StoredSession? = value
    override suspend fun save(session: StoredSession) { value = session }
    override suspend fun delete(instance: String, origin: String) { value = null }
}
