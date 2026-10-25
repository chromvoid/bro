package assistant.mobile.core

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.http.headersOf
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

private const val TEST_ORIGIN = "https://synthetic.invalid"
private val TEST_NOW = Instant.parse("2026-10-16T12:00:00Z").toEpochMilliseconds()

private class MemorySessionSecrets(var value: StoredSession? = null) : SessionSecretStore {
    var unavailable = false
    override suspend fun load(instance: String, origin: String): StoredSession? {
        check(!unavailable)
        return value
    }
    override suspend fun save(session: StoredSession) {
        check(!unavailable)
        value = session
    }
    override suspend fun delete(instance: String, origin: String) {
        check(!unavailable)
        value = null
    }
}

private fun fixtureRecord(userId: String = "11111111-1111-4111-8111-111111111111"): StoredSession {
    val reply = decodeNativeSession(contractBytes("native-session.json"))
    return StoredSession(
        AccountNamespace("synthetic-instance", TEST_ORIGIN, userId), reply.credential,
        reply.user.copy(id = userId, scopes = reply.scopes), reply.device.id, reply.session.id, reply.expiresAt,
    )
}

private fun bodyBytes(content: OutgoingContent): ByteArray = (content as OutgoingContent.ByteArrayContent).bytes()

class SessionHttpTest {
    @Test
    fun switchingCapturesRevocationAddressBeforeErasureAndNeverSendsOldCredentialToNewServer() = runTest {
        val record = fixtureRecord()
        val secrets = MemorySessionSecrets(record)
        val sessions = SessionManager(record.namespace.instance, TEST_ORIGIN, secrets, {}, { TEST_NOW })
        var server = "https://old-server.invalid"
        val requests = mutableListOf<String>()
        val http = NativeHttpClient(MockEngine { request ->
            requests += request.url.toString()
            assertEquals("https://old-server.invalid/v1/auth/native/logout", request.url.toString())
            assertEquals("Bearer ${record.credential}", request.headers["Authorization"])
            assertNull(sessions.lease())
            assertNull(secrets.value)
            respond("{}", HttpStatusCode.ServiceUnavailable)
        }, TEST_ORIGIN, sessions, { TEST_NOW }, serverOrigin = { server })
        try {
            sessions.restore()
            var erased = false
            val revocation = assertNotNull(sessions.clearForServerSwitch {
                erased = true
                server = "https://new-server.invalid"
            })
            assertTrue(erased)
            assertTrue(requests.isEmpty())
            assertEquals(503, assertIs<HttpOutcome.Failure>(revocation.revoke()).status)
            assertEquals(listOf("https://old-server.invalid/v1/auth/native/logout"), requests)
            assertNull(sessions.lease())
            assertNull(sessions.identity.value)
        } finally { http.close() }
    }

    @Test
    fun loginAndRedeemUseNativeEndpointsWithoutPersistingPassword() = runTest {
        val secrets = MemorySessionSecrets()
        val sessions = SessionManager("synthetic-instance", TEST_ORIGIN, secrets, {}, { TEST_NOW })
        val paths = mutableListOf<String>()
        val engine = MockEngine { request ->
            paths += request.url.encodedPath
            assertNull(request.headers["Authorization"])
            assertTrue(bodyBytes(request.body).decodeToString().contains("ephemeral-password"))
            respond(contractBytes("native-session.json"), HttpStatusCode.OK)
        }
        val http = NativeHttpClient(engine, TEST_ORIGIN, sessions, { TEST_NOW })
        try {
            assertIs<SessionResult.SignedIn>(sessions.login("synthetic-person", "ephemeral-password"))
            assertEquals(fixtureRecord().namespace, sessions.lease()!!.namespace)
            assertFalse(NativeJson.encodeToString(secrets.value).contains("ephemeral-password"))
            assertIs<SessionResult.SignedIn>(sessions.redeem("synthetic-invitation", "ephemeral-password", name = "Synthetic person"))
            assertEquals(listOf("/v1/auth/native/login", "/v1/auth/native/redeem"), paths)
            assertFalse(sessions.lease().toString().contains(fixtureRecord().credential))
        } finally { http.close() }
    }

    @Test
    fun serverMoveAffectsEachRequestAndStreamWhileSessionKeepsItsHome() = runTest {
        val record = fixtureRecord()
        val secrets = MemorySessionSecrets(record)
        val sessions = SessionManager("synthetic-instance", TEST_ORIGIN, secrets, {}, { TEST_NOW })
        var server = TEST_ORIGIN
        val requests = mutableListOf<String>()
        val deletionId = "22222222-2222-4222-8222-222222222222"
        val http = NativeHttpClient(MockEngine { request ->
            requests += request.url.toString()
            assertEquals("Bearer ${record.credential}", request.headers["Authorization"])
            if (!request.url.encodedPath.startsWith("/v1/account-deletions/")) {
                assertEquals(record.namespace.userId, request.headers["X-Expected-User"])
            }
            respond("{}")
        }, TEST_ORIGIN, sessions, { TEST_NOW }, serverOrigin = { server })
        try {
            assertIs<SessionResult.SignedIn>(sessions.restore())
            assertTrue(requests.isEmpty())
            val lease = sessions.lease()!!
            assertIs<HttpOutcome.Success>(http.request("GET", "/v1/me"))
            server = "https://moved.invalid"
            assertEquals(TEST_ORIGIN, http.origin)
            assertEquals(server, http.serverOrigin)
            assertEquals(record.namespace, lease.namespace)
            assertIs<HttpOutcome.Success>(http.request("GET", "/v1/me"))
            assertIs<HttpOutcome.Success>(http.stream("/v1/me/events", lease) { status, _, _ ->
                assertEquals(200, status)
            })
            assertIs<HttpOutcome.Success>(http.deletionStatus(deletionId, record.credential))
            assertEquals(record, secrets.value)
            assertIs<HttpOutcome.Success>(sessions.logout())
            assertEquals(listOf(
                "$TEST_ORIGIN/v1/me",
                "$server/v1/me",
                "$server/v1/me/events",
                "$server/v1/account-deletions/$deletionId",
                "$server/v1/auth/native/logout",
            ), requests)
        } finally { http.close() }
    }

    @Test
    fun otherOriginsReservedAuthorizationAndRedirectsCannotForwardCredential() = runTest {
        val secrets = MemorySessionSecrets(fixtureRecord())
        val sessions = SessionManager("synthetic-instance", TEST_ORIGIN, secrets, {}, { TEST_NOW })
        var requests = 0
        val http = NativeHttpClient(MockEngine {
            requests++
            respond("", HttpStatusCode.Found, headersOf("Location", "https://other.invalid/private"))
        }, TEST_ORIGIN, sessions, { TEST_NOW })
        try {
            sessions.restore()
            for (path in listOf("https://other.invalid/private", "//other.invalid/private", "/\\other.invalid/private")) {
                assertIs<HttpOutcome.RejectedOrigin>(http.request("GET", path))
            }
            assertIs<HttpOutcome.RejectedRequest>(http.request("GET", "/v1/me", headers = mapOf("Authorization" to "replacement")))
            val redirect = assertIs<HttpOutcome.Failure>(http.request("GET", "/v1/me"))
            assertEquals(302, redirect.status)
            assertEquals(1, requests)
        } finally { http.close() }
        assertFailsWith<IllegalArgumentException> { canonicalOrigin("http://synthetic.invalid") }
        assertFailsWith<IllegalArgumentException> { canonicalOrigin("http://synthetic.invalid", allowInsecureLoopback = true) }
        assertEquals("http://127.0.0.1:54321", canonicalOrigin("http://127.0.0.1:54321", allowInsecureLoopback = true))
    }

    @Test
    fun unauthorizedClosesSessionWithoutPurgingDraftNamespace() = runTest {
        val secrets = MemorySessionSecrets(fixtureRecord())
        var purges = 0
        val sessions = SessionManager("synthetic-instance", TEST_ORIGIN, secrets, { purges++ }, { TEST_NOW })
        val http = NativeHttpClient(MockEngine { respond("", HttpStatusCode.Unauthorized) }, TEST_ORIGIN, sessions)
        try {
            sessions.restore()
            assertIs<HttpOutcome.Unauthorized>(http.request("GET", "/v1/me"))
            assertNull(sessions.lease())
            assertNull(secrets.value)
            assertEquals(0, purges)
        } finally { http.close() }
    }

    @Test
    fun forbiddenRefreshesLiveRightsOnceWithoutLoginLoop() = runTest {
        val secrets = MemorySessionSecrets(fixtureRecord())
        val sessions = SessionManager("synthetic-instance", TEST_ORIGIN, secrets, {}, { TEST_NOW })
        val paths = mutableListOf<String>()
        var meIsForbidden = false
        val http = NativeHttpClient(MockEngine { request ->
            paths += request.url.encodedPath
            if (request.url.encodedPath == "/v1/me" && !meIsForbidden)
                respond(NativeJson.encodeToString(fixtureRecord().identity.copy(scopes = listOf("session.control"))))
            else respond("", HttpStatusCode.Forbidden)
        }, TEST_ORIGIN, sessions)
        try {
            sessions.restore()
            val lease = sessions.lease()
            assertIs<HttpOutcome.Forbidden>(http.request("GET", "/v1/tasks/items"))
            assertTrue(sessions.isCurrent(lease!!))
            assertEquals(listOf("session.control"), sessions.identity.value!!.scopes)
            assertEquals(listOf("/v1/tasks/items", "/v1/me"), paths)
            meIsForbidden = true
            paths.clear()
            assertIs<HttpOutcome.Forbidden>(http.request("GET", "/v1/tasks/items"))
            assertEquals(listOf("/v1/tasks/items", "/v1/me"), paths)
            assertTrue(sessions.isCurrent(lease))
        } finally { http.close() }
    }

    @Test
    fun lateAccountAResponseCannotAffectNewAccountB() = runTest {
        val secrets = MemorySessionSecrets(fixtureRecord())
        val sessions = SessionManager("synthetic-instance", TEST_ORIGIN, secrets, {}, { TEST_NOW })
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val b = decodeNativeSession(contractBytes("native-session.json")).let {
            it.copy(credential = "synthetic-account-b-credential", user = it.user.copy(id = "44444444-4444-4444-8444-444444444444"))
        }
        val http = NativeHttpClient(MockEngine { request ->
            if (request.url.encodedPath == "/v1/auth/native/login") respond(NativeJson.encodeToString(b))
            else {
                entered.complete(Unit)
                release.await()
                respond("", HttpStatusCode.Unauthorized)
            }
        }, TEST_ORIGIN, sessions)
        try {
            sessions.restore()
            val aLease = sessions.lease()!!
            val old = async { http.request("GET", "/v1/tasks/items", lease = aLease) }
            entered.await()
            assertIs<SessionResult.SignedIn>(sessions.login("synthetic-b", "synthetic-password"))
            release.complete(Unit)
            assertIs<HttpOutcome.StaleSession>(old.await())
            assertEquals(b.user.id, sessions.lease()!!.namespace.userId)
            assertFalse(sessions.isCurrent(aLease))
            assertEquals(b.credential, secrets.value!!.credential)
        } finally { http.close() }
    }

    @Test
    fun explicitLogoutPurgesBeforeRevocationEvenWhenServerReturnsUnauthorized() = runTest {
        val secrets = MemorySessionSecrets(fixtureRecord())
        val purged = mutableListOf<AccountNamespace>()
        val sessions = SessionManager("synthetic-instance", TEST_ORIGIN, secrets, { purged += it }, { TEST_NOW })
        val http = NativeHttpClient(MockEngine { request ->
            assertEquals("/v1/auth/native/logout", request.url.encodedPath)
            assertNull(sessions.lease())
            assertNull(secrets.value)
            assertEquals(listOf(fixtureRecord().namespace), purged)
            assertEquals("Bearer ${fixtureRecord().credential}", request.headers["Authorization"])
            respond("", HttpStatusCode.Unauthorized)
        }, TEST_ORIGIN, sessions)
        try {
            sessions.restore()
            assertIs<HttpOutcome.Unauthorized>(sessions.logout())
            assertEquals(1, purged.size)
        } finally { http.close() }
    }

    @Test
    fun unavailableSecretsCannotCreateAnActiveSession() = runTest {
        val secrets = MemorySessionSecrets(fixtureRecord()).also { it.unavailable = true }
        val sessions = SessionManager("synthetic-instance", TEST_ORIGIN, secrets, {}, { TEST_NOW })
        var requests = 0
        val http = NativeHttpClient(MockEngine { requests++; respond(contractBytes("native-session.json")) }, TEST_ORIGIN, sessions)
        try {
            assertIs<SessionResult.StorageUnavailable>(sessions.restore())
            assertNull(sessions.lease())
            assertIs<SessionResult.StorageUnavailable>(sessions.login("synthetic", "synthetic-password"))
            assertEquals(0, requests)
        } finally { http.close() }
    }

}
