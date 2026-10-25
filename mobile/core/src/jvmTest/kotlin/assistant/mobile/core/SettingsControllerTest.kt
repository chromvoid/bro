package assistant.mobile.core

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class SettingsControllerTest {
    @Test
    fun profileSaveUsesActualApiAndReloadsNativeBindingAndLiveCapabilities() = runTest {
        val secrets = FeatureSessionSecrets(featureRecord())
        var identity = featureRecord().identity
        val sessions = SessionManager("synthetic", FEATURE_ORIGIN, secrets, {}, { FEATURE_NOW })
        val paths = mutableListOf<String>()
        val http = NativeHttpClient(MockEngine { request ->
            paths += "${request.method.value} ${request.url.encodedPath}"
            if (request.method.value == "POST") {
                assertNull(request.headers["Idempotency-Key"])
                val body = NativeJson.parseToJsonElement(featureBody(request.body).decodeToString()).jsonObject
                assertEquals(setOf("name", "locale", "timezone"), body.keys)
                identity = identity.copy(name = body.getValue("name").jsonPrimitive.content,
                    locale = body.getValue("locale").jsonPrimitive.content,
                    timezone = body.getValue("timezone").jsonPrimitive.content, revision = identity.revision + 1,
                    scopes = listOf("nutrition.read"))
                // Server POST /me returns a plain profile, with no native device/expiry snapshot.
                respond("{\"id\":\"${identity.id}\",\"name\":\"${identity.name}\"}")
            } else if (request.url.encodedPath == "/v1/me") respond(NativeJson.encodeToString(identity))
            else respond("""{"limits":[{"kind":"assistant","maximum":"50","used":2},{"kind":"dictation_ms","maximum":60000,"used":"12500"}],"timezone":"UTC"}""")
        }, FEATURE_ORIGIN, sessions)
        try {
            sessions.restore()
            val settings = SettingsController(backgroundScope, sessions, http)
            runCurrent()
            settings.refresh()
            assertEquals(2, settings.state.value!!.limits.size)
            assertEquals("settings.usedMinutes", settings.state.value!!.limits[1].usage.key.webKey)
            assertEquals(listOf("0.3", "1"), settings.state.value!!.limits[1].usage.arguments)
            settings.updateName(" New Name ")
            settings.updateLocale("en")
            settings.updateTimezone("UTC")
            settings.save()
            assertTrue(settings.state.value!!.saved)
            assertEquals("New Name", settings.state.value!!.name)
            assertEquals("en", settings.state.value!!.identity.locale)
            assertEquals(featureRecord().identity.deviceId, settings.state.value!!.identity.deviceId)
            assertEquals(listOf("nutrition"), settings.state.value!!.capabilities.filter { it.available }.map { it.key })
            assertEquals(listOf("GET /v1/me", "GET /v1/me/limits", "POST /v1/me", "GET /v1/me"), paths)
            assertEquals(identity, secrets.value!!.identity)
        } finally { http.close() }
    }

    @Test
    fun forbiddenRefreshesScopesAndKeepsFormWhereasUnauthorizedRemovesPrivateState() = runTest {
        val secrets = FeatureSessionSecrets(featureRecord())
        val sessions = SessionManager("synthetic", FEATURE_ORIGIN, secrets, {}, { FEATURE_NOW })
        var unauthorized = false
        val http = NativeHttpClient(MockEngine { request ->
            if (request.url.encodedPath == "/v1/me" && !unauthorized) {
                respond(NativeJson.encodeToString(featureRecord().identity.copy(scopes = listOf("tasks.read"))))
            } else respond("", if (unauthorized) HttpStatusCode.Unauthorized else HttpStatusCode.Forbidden)
        }, FEATURE_ORIGIN, sessions)
        try {
            sessions.restore()
            val settings = SettingsController(backgroundScope, sessions, http)
            runCurrent()
            settings.updateName("Local edit")
            settings.refreshLimits()
            runCurrent()
            assertEquals("Local edit", settings.state.value!!.name)
            assertEquals(listOf("tasks"), settings.state.value!!.capabilities.filter { it.available }.map { it.key })
            assertEquals(GeneratedStrings.Errors.forbidden, settings.state.value!!.limitsError)
            unauthorized = true
            settings.refreshLimits()
            runCurrent()
            assertNull(settings.state.value)
            assertNull(sessions.lease())
        } finally { http.close() }
    }

    @Test
    fun lateAccountALimitsDoNotAppearAfterSigningInAsB() = runTest {
        val secrets = FeatureSessionSecrets(featureRecord())
        val sessions = SessionManager("synthetic", FEATURE_ORIGIN, secrets, {}, { FEATURE_NOW })
        val entered = CompletableDeferred<Unit>()
        val released = CompletableDeferred<Unit>()
        val accountB = decodeNativeSession(contractBytes("native-session.json")).let {
            it.copy(user = it.user.copy(id = "44444444-4444-4444-8444-444444444444", name = "Account B"))
        }
        val http = NativeHttpClient(MockEngine { request ->
            if (request.url.encodedPath == "/v1/auth/native/login") respond(NativeJson.encodeToString(accountB))
            else {
                entered.complete(Unit)
                released.await()
                respond("""{"limits":[{"kind":"assistant","maximum":50,"used":49}]}""")
            }
        }, FEATURE_ORIGIN, sessions)
        try {
            sessions.restore()
            val settings = SettingsController(backgroundScope, sessions, http)
            runCurrent()
            val pending = async { settings.refreshLimits() }
            entered.await()
            sessions.login("synthetic-b", "request-only-password")
            runCurrent()
            assertEquals("Account B", settings.state.value!!.name)
            assertTrue(settings.state.value!!.limits.isEmpty())
            released.complete(Unit)
            pending.await()
            runCurrent()
            assertEquals(accountB.user.id, settings.state.value!!.identity.id)
            assertTrue(settings.state.value!!.limits.isEmpty())
        } finally { http.close() }
    }

}
