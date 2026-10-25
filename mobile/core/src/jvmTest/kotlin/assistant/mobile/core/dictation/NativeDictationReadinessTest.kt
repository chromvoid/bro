package assistant.mobile.core.dictation

import assistant.mobile.core.AccountNamespace
import assistant.mobile.core.NativeHttpClient
import assistant.mobile.core.NativeIdentity
import assistant.mobile.core.SessionManager
import assistant.mobile.core.SessionSecretStore
import assistant.mobile.core.StoredSession
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class NativeDictationReadinessTest {
    @Test
    fun readinessUsesAuthenticatedNativeEndpointAndRequestedCapability() = runTest {
        var requests = 0
        val sessions = sessions()
        val http = NativeHttpClient(MockEngine { request ->
            requests++
            assertEquals("/v1/me/speech/readiness", request.url.encodedPath)
            assertEquals("asr", request.url.parameters["capability"])
            assertEquals("Bearer synthetic-credential", request.headers["Authorization"])
            assertEquals("synthetic-owner", request.headers["X-Expected-User"])
            respond("""{"asr":{"state":"configured","provider":"synthetic","model":"fixture"}}""",
                HttpStatusCode.OK, headersOf("Content-Type", "application/json"))
        }, ORIGIN, sessions, { 0 })
        try {
            sessions.restore()
            assertEquals(DictationReadinessResult.Configured, NativeDictationReadiness(http, sessions).check())
            assertEquals(1, requests)
        } finally { http.close() }
    }

    @Test
    fun expiredSessionNeverIssuesReadinessWithoutBearer() = runTest {
        var requests = 0
        val sessions = sessions()
        val http = NativeHttpClient(MockEngine { requests++; respond("{}") }, ORIGIN, sessions, { 0 })
        try {
            assertEquals(DictationReadinessResult.Unavailable(DictationProblem.SESSION_EXPIRED),
                NativeDictationReadiness(http, sessions).check())
            assertEquals(0, requests)
        } finally { http.close() }
    }
}

private const val ORIGIN = "https://assistant.invalid"

private fun sessions(): SessionManager {
    val identity = NativeIdentity("synthetic-owner", "fixture", "Synthetic", "ru", "UTC", false,
        1, "synthetic-device", "2099-01-01T00:00:00Z")
    val stored = StoredSession(AccountNamespace("synthetic", ORIGIN, identity.id), "synthetic-credential",
        identity, identity.deviceId, "synthetic-session", identity.sessionExpiresAt)
    return SessionManager("synthetic", ORIGIN, object : SessionSecretStore {
        override suspend fun load(instance: String, origin: String) = stored
        override suspend fun save(session: StoredSession) = Unit
        override suspend fun delete(instance: String, origin: String) = Unit
    }, {}, { 0 })
}
