package assistant.mobile.core.notifications

import assistant.mobile.core.*
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockEngineConfig
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import java.io.IOException
import kotlin.test.*
import kotlin.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
class NotificationsControllerTest {
    @Test
    fun settingsUseCurrentRevisionAndReplaceAConflictWithTheServersSettings() = runTest {
        val fixture = NotificationsFixture(this)
        try {
            fixture.start()
            fixture.rejectSettings = true
            assertFalse(fixture.controller.saveSettings(NotificationSettingsDraft(false, mutedTopics = listOf("app.test"))))
            assertEquals(2, fixture.body(fixture.writes.single()).getValue("revision").jsonPrimitive.int)
            assertEquals(GeneratedStrings.Errors.conflict, fixture.controller.state.value!!.error)
            assertEquals(3L, fixture.controller.state.value!!.settings.data!!.revision)
            assertEquals("22:00", fixture.controller.state.value!!.settings.data!!.quietStart)
            fixture.rejectSettings = false
            assertTrue(fixture.controller.saveSettings(NotificationSettingsDraft(false, mutedTopics = listOf("app.test"))))
            val saved = fixture.body(fixture.writes.last())
            assertEquals(3, saved.getValue("revision").jsonPrimitive.int)
            assertEquals(JsonNull, saved.getValue("quiet_start"))
            assertEquals(JsonNull, saved.getValue("quiet_end"))
            assertEquals(GeneratedStrings.NotificationSettings.saved, fixture.controller.state.value!!.notice)
            assertNull(fixture.controller.state.value!!.settings.data!!.quietStart)
        } finally { fixture.close() }
    }

    @Test
    fun lostTestReplyIsRereadAndNeverResentByRefreshForegroundOrInvalidation() = runTest {
        val fixture = NotificationsFixture(this)
        try {
            fixture.start()
            fixture.loseTestReply = true
            assertFalse(fixture.controller.test())
            assertEquals(GeneratedStrings.Errors.network, fixture.controller.state.value!!.error)
            assertEquals(1, fixture.testWrites)
            assertEquals("Проверка", fixture.controller.state.value!!.items.single().title)
            fixture.controller.refresh()
            fixture.controller.foreground()
            fixture.controller.invalidate(EventInvalidation.Domains(setOf(EventDomain.Notifications)))
            assertEquals(1, fixture.testWrites)
            assertEquals(1, fixture.writes.size)
            assertEquals(0, fixture.changed)
        } finally { fixture.close() }
    }

    @Test
    fun revokedLeaseClearsStateAndCannotApplyAnInflightActionOrRefresh() = runTest {
        val fixture = NotificationsFixture(this)
        try {
            fixture.start()
            val gate = CompletableDeferred<Unit>()
            fixture.writeGate = gate
            val write = async { fixture.controller.read() }
            runCurrent()
            assertTrue(fixture.controller.state.value!!.busy)
            fixture.sessions.expire(fixture.sessions.lease()!!)
            runCurrent()
            assertNull(fixture.controller.state.value)
            gate.complete(Unit)
            assertFalse(write.await())
            fixture.controller.refresh()
            assertNull(fixture.controller.state.value)
            assertFalse(fixture.controller.test())
            assertEquals(1, fixture.writes.size)
            assertEquals(0, fixture.changed)
        } finally { fixture.close() }
    }

}

@OptIn(ExperimentalCoroutinesApi::class)
private class NotificationsFixture(private val test: TestScope) {
    val record = featureRecord().let { it.copy(identity = it.identity.copy(timezone = "Europe/Moscow")) }
    val sessions = SessionManager("synthetic", FEATURE_ORIGIN, FeatureSessionSecrets(record), {}, { FEATURE_NOW })
    val requests = mutableListOf<HttpRequestData>()
    val writes get() = requests.filter { it.method != HttpMethod.Get }
    var unread = 4
    var read = false
    var snoozedUntil: String? = null
    var quietStart: String? = "23:00"
    var quietEnd: String? = "08:00"
    var muted = emptyList<String>()
    var revision = 2L
    var rejectSettings = false
    var loseTestReply = false
    var malformedList = false
    var testWrites = 0
    var changed = 0
    var writeGate: CompletableDeferred<Unit>? = null
    val http = NativeHttpClient(MockEngine(MockEngineConfig().apply {
        dispatcher = StandardTestDispatcher(test.testScheduler)
        addHandler { request ->
            requests += request
            val path = request.url.encodedPath
            if (request.method != HttpMethod.Get) writeGate?.await()
            when {
                path == "/v1/notifications" -> respond(if (malformedList) "{}" else listing())
                path == "/v1/notifications/settings" && request.method == HttpMethod.Get -> respond(settings())
                path == "/v1/notifications/settings" -> {
                    if (rejectSettings) {
                        revision = 3; quietStart = "22:00"
                        respond("""{"error":"conflict"}""", HttpStatusCode.Conflict)
                    } else {
                        val value = body(request)
                        assertEquals(revision, value.getValue("revision").jsonPrimitive.long)
                        quietStart = value["quiet_start"]?.jsonPrimitive?.contentOrNull
                        quietEnd = value["quiet_end"]?.jsonPrimitive?.contentOrNull
                        muted = value.getValue("muted_topics").jsonArray.map { it.jsonPrimitive.content }
                        revision++
                        respond(settings())
                    }
                }
                path == "/v1/notifications/read" -> { read = true; unread = 0; snoozedUntil = null; respond("""{"read":1}""") }
                path == "/v1/notifications/n1/snooze" -> {
                    val value = body(request)
                    snoozedUntil = value["until"]?.jsonPrimitive?.content ?: Instant.fromEpochMilliseconds(FEATURE_NOW + value.getValue("minutes").jsonPrimitive.int * 60_000).toString()
                    read = true; unread = 0
                    respond(buildJsonObject { put("snoozed_until", snoozedUntil) }.toString())
                }
                path == "/v1/notifications/test" -> {
                    testWrites++
                    if (loseTestReply) { loseTestReply = false; throw IOException("synthetic lost response") }
                    respond("""{"id":"n1","written":true}""")
                }
                else -> error("Unexpected notification request: $path")
            }
        }
    }), FEATURE_ORIGIN, sessions, { FEATURE_NOW })
    val controller = NotificationsController(test.backgroundScope, sessions, http, { changed++ }, { FEATURE_NOW })

    fun body(request: HttpRequestData) = notificationObject(featureBody(request.body))
    private fun listing() = buildJsonObject {
        put("unread", unread); put("before", JsonNull)
        put("notifications", buildJsonArray { add(buildJsonObject {
            put("id", "n1"); put("source", "app"); put("topic", "app.test"); put("severity", "important")
            put("title", if (testWrites > 0) "Проверка" else "Оповещение"); put("body", "Сообщение"); put("link", "/notifications")
            put("state", "active"); put("resolution", JsonNull); put("count", 1); put("updated_at", "2026-10-16T12:00:00Z")
            put("read_at", if (read) JsonPrimitive("2026-10-16T12:00:00Z") else JsonNull)
            put("resolved_at", JsonNull); put("snoozed_until", snoozedUntil?.let(::JsonPrimitive) ?: JsonNull); put("unread", !read)
        }) })
    }.toString()
    private fun settings() = buildJsonObject {
        put("quiet_start", quietStart?.let(::JsonPrimitive) ?: JsonNull); put("quiet_end", quietEnd?.let(::JsonPrimitive) ?: JsonNull)
        put("muted_topics", JsonArray(muted.map(::JsonPrimitive))); put("revision", revision)
        put("topics", buildJsonArray { add(buildJsonObject { put("topic", "app.test"); put("severity", "important") }) })
    }.toString()
    suspend fun start() {
        sessions.restore(); controller.activate()
        controller.state.first { it?.list?.data != null && it.settings.data != null }
    }
    fun close() { controller.deactivate(); test.runCurrent(); http.close() }
}
