package assistant.mobile.core

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

internal const val FEATURE_ORIGIN = "https://synthetic.invalid"
internal val FEATURE_NOW = Instant.parse("2026-10-16T12:00:00Z").toEpochMilliseconds()

internal class FeatureSessionSecrets(var value: StoredSession? = null) : SessionSecretStore {
    override suspend fun load(instance: String, origin: String): StoredSession? = value
    override suspend fun save(session: StoredSession) { value = session }
    override suspend fun delete(instance: String, origin: String) { value = null }
}

internal fun featureRecord(): StoredSession = decodeNativeSession(contractBytes("native-session.json")).let {
    StoredSession(AccountNamespace("synthetic", FEATURE_ORIGIN, it.user.id), it.credential,
        it.user.copy(scopes = it.scopes), it.device.id, it.session.id, it.expiresAt)
}

internal fun featureBody(content: OutgoingContent): ByteArray =
    (content as OutgoingContent.ByteArrayContent).bytes()

@OptIn(ExperimentalCoroutinesApi::class)
class AuthControllerTest {
    @Test
    fun linksAcceptOnlyOneFragmentTokenAndRedactDiagnostics() {
        val link = assertNotNull(parseAuthLink("#kind=invite&token=synthetic%2Btoken"))
        assertEquals(AuthLinkKind.Invite, link.kind)
        assertEquals("synthetic+token", link.token)
        assertFalse(link.toString().contains(link.token))
        assertEquals(AuthLinkKind.Reset, parseAuthLink("kind=reset&token=synthetic")?.kind)
        for (fragment in listOf("?kind=invite&token=synthetic", "kind=unknown&token=synthetic",
            "kind=invite&token=", "kind=invite&token=a&token=b", "kind=invite&kind=reset&token=a",
            "kind=invite&token=%0A", "kind=invite&token=" + "a".repeat(129))) {
            assertNull(parseAuthLink(fragment), fragment)
        }
    }

    @Test
    fun loginShowsCredentialErrorsAndHonorsRetryAfterWithoutPersistingPassword() = runTest {
        val secrets = FeatureSessionSecrets()
        var now = FEATURE_NOW
        val sessions = SessionManager("synthetic", FEATURE_ORIGIN, secrets, {}, { now })
        var requests = 0
        val http = NativeHttpClient(MockEngine { request ->
            requests++
            assertEquals("/v1/auth/native/login", request.url.encodedPath)
            assertNull(request.headers["Authorization"])
            val body = NativeJson.parseToJsonElement(featureBody(request.body).decodeToString()).jsonObject
            assertEquals("synthetic-person", body.getValue("login").jsonPrimitive.content)
            assertEquals("request-only-password", body.getValue("password").jsonPrimitive.content)
            when (requests) {
                1 -> respond("", HttpStatusCode.Unauthorized)
                2 -> respond("", HttpStatusCode.TooManyRequests, headersOf("Retry-After", "7"))
                else -> respond(contractBytes("native-session.json"))
            }
        }, FEATURE_ORIGIN, sessions, { now })
        try {
            val auth = AuthController(backgroundScope, sessions, { now })
            runCurrent()
            auth.updateLogin(" synthetic-person ")
            auth.signIn("request-only-password")
            assertEquals(GeneratedStrings.Auth.checkText, auth.state.value.error)
            assertEquals(" synthetic-person ", auth.state.value.login)
            assertFalse(auth.state.value.toString().contains("request-only-password"))
            auth.signIn("request-only-password")
            assertEquals(7L, auth.state.value.retrySeconds(now))
            auth.signIn("request-only-password")
            assertEquals(2, requests)
            now += 7_000
            auth.signIn("request-only-password")
            assertTrue(auth.state.value.signedIn)
            assertEquals(3, requests)
            assertFalse(NativeJson.encodeToString(secrets.value).contains("request-only-password"))
        } finally { http.close() }
    }

    @Test
    fun invitationValidatesFieldsThenSignsInWithoutPublishingTokenOrPassword() = runTest {
        val secrets = FeatureSessionSecrets()
        val sessions = SessionManager("synthetic", FEATURE_ORIGIN, secrets, {}, { FEATURE_NOW })
        var requests = 0
        val http = NativeHttpClient(MockEngine { request ->
            requests++
            assertEquals("/v1/auth/native/redeem", request.url.encodedPath)
            val body = NativeJson.parseToJsonElement(featureBody(request.body).decodeToString()).jsonObject
            assertEquals("synthetic-invite-token", body.getValue("token").jsonPrimitive.content)
            assertEquals("synthetic-person", body.getValue("login").jsonPrimitive.content)
            assertEquals("Synthetic Name", body.getValue("name").jsonPrimitive.content)
            respond(contractBytes("native-session.json"))
        }, FEATURE_ORIGIN, sessions)
        try {
            val auth = AuthController(backgroundScope, sessions)
            runCurrent()
            auth.acceptLink(assertNotNull(parseAuthLink("#kind=invite&token=synthetic-invite-token")))
            auth.redeem("short", "different")
            assertEquals(setOf(AuthField.Name, AuthField.Login, AuthField.Password, AuthField.Repeat), auth.state.value.fieldErrors.keys)
            assertEquals(0, requests)
            auth.updateLogin("synthetic-person")
            auth.updateName(" Synthetic Name ")
            auth.updateTimezone("UTC")
            auth.redeem("request-only-password", "request-only-password")
            assertTrue(auth.state.value.signedIn)
            assertTrue(auth.state.value.welcome)
            assertFalse(auth.state.value.busy)
            assertFalse(auth.state.value.toString().contains("synthetic-invite-token"))
            assertFalse(auth.state.value.toString().contains("request-only-password"))
            assertEquals(1, requests)
        } finally { http.close() }
    }

    @Test
    fun expirationClosesAuthStateAndExplicitLogoutClearsExpiryNotice() = runTest {
        val secrets = FeatureSessionSecrets(featureRecord())
        val sessions = SessionManager("synthetic", FEATURE_ORIGIN, secrets, {}, { FEATURE_NOW })
        val http = NativeHttpClient(MockEngine { respond("{}") }, FEATURE_ORIGIN, sessions)
        try {
            sessions.restore()
            val auth = AuthController(backgroundScope, sessions)
            runCurrent()
            assertTrue(auth.state.value.signedIn)
            sessions.expire(sessions.lease()!!)
            runCurrent()
            assertTrue(auth.state.value.expired)
            assertFalse(auth.state.value.signedIn)
            auth.signedOut()
            assertFalse(auth.state.value.expired)
        } finally { http.close() }
    }

    @Test
    fun signOutKeepsItsOwnStageUntilLocalCleanupAndRevocationFinishBeforeAnotherLogin() = runTest {
        val cleanup = CompletableDeferred<Unit>()
        val revoke = CompletableDeferred<Unit>()
        val revokeStarted = CompletableDeferred<Unit>()
        val secrets = FeatureSessionSecrets(featureRecord())
        val sessions = SessionManager("synthetic", FEATURE_ORIGIN, secrets, { cleanup.await() }, { FEATURE_NOW })
        val requests = mutableListOf<String>()
        val http = NativeHttpClient(MockEngine { request ->
            requests += request.url.encodedPath
            if (request.url.encodedPath == "/v1/auth/native/logout") {
                revokeStarted.complete(Unit)
                revoke.await()
                respond("{}")
            } else respond(contractBytes("native-session.json"))
        }, FEATURE_ORIGIN, sessions)
        try {
            sessions.restore()
            val auth = AuthController(backgroundScope, sessions)
            runCurrent()
            val leaving = async { auth.signOut() }
            runCurrent()
            assertNull(sessions.lease())
            assertTrue(auth.state.value.signingOut)
            assertTrue(auth.state.value.busy)
            auth.updateLogin("next-person")
            auth.signIn("request-only-password")
            assertTrue(requests.isEmpty())
            cleanup.complete(Unit)
            revokeStarted.await()
            assertTrue(auth.state.value.signingOut)
            assertTrue(auth.state.value.busy)
            revoke.complete(Unit)
            leaving.await()
            runCurrent()
            assertFalse(auth.state.value.signingOut)
            assertFalse(auth.state.value.busy)
            auth.updateLogin("next-person")
            auth.signIn("request-only-password")
            assertEquals(listOf("/v1/auth/native/logout", "/v1/auth/native/login"), requests)
            assertTrue(auth.state.value.signedIn)
        } finally { cleanup.complete(Unit); revoke.complete(Unit); http.close() }
    }

    @Test
    fun redeemCanReplaceAnActiveSessionAndDeliberateLogoutIsNotExpiry() = runTest {
        val secrets = FeatureSessionSecrets(featureRecord())
        var purges = 0
        val sessions = SessionManager("synthetic", FEATURE_ORIGIN, secrets, { purges++ }, { FEATURE_NOW })
        val http = NativeHttpClient(MockEngine { request ->
            if (request.url.encodedPath == "/v1/auth/native/logout") respond("{}")
            else respond(contractBytes("native-session.json"))
        }, FEATURE_ORIGIN, sessions)
        try {
            sessions.restore()
            val auth = AuthController(backgroundScope, sessions)
            runCurrent()
            auth.acceptLink(assertNotNull(parseAuthLink("#kind=reset&token=synthetic-reset")))
            auth.redeem("request-only-password", "request-only-password")
            runCurrent()
            assertTrue(auth.state.value.signedIn)
            assertEquals(AuthMode.SignIn, auth.state.value.mode)
            auth.signOut()
            runCurrent()
            assertFalse(auth.state.value.expired)
            assertFalse(auth.state.value.signedIn)
            assertFalse(auth.state.value.busy)
            assertEquals(1, purges)
            assertNull(secrets.value)
            auth.restored(SessionResult.StorageUnavailable)
            assertEquals(GeneratedStrings.Errors.server, auth.state.value.error)
        } finally { http.close() }
    }
}
