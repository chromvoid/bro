package assistant.mobile.core.assistant

import assistant.mobile.core.FEATURE_ORIGIN
import assistant.mobile.core.FeatureSessionSecrets
import assistant.mobile.core.NativeHttpClient
import assistant.mobile.core.SessionManager
import assistant.mobile.core.featureRecord
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
class AssistantSuggestionsTest {
    @Test
    fun noPermissionsMeansNoDayReadsAndReadOnlyDiaryCannotSuggestWritingFood() = runTest {
        val record = featureRecord().let { it.copy(identity = it.identity.copy(timezone = "Europe/Moscow",
            scopes = listOf("nutrition.read"))) }
        val secrets = FeatureSessionSecrets(record)
        val sessions = SessionManager("synthetic", FEATURE_ORIGIN, secrets, {}, { NOW })
        val paths = mutableListOf<String>()
        val http = NativeHttpClient(MockEngine { request ->
            paths += request.url.encodedPath
            respond("""{"items":[],"drafts":[]}""")
        }, FEATURE_ORIGIN, sessions, { NOW })
        try {
            sessions.restore()
            val controller = AssistantSuggestionsController(backgroundScope, sessions, http) { NOW }
            controller.activate()
            controller.state.first { it.settled }
            assertEquals(listOf("/v1/nutrition/day"), paths)
            assertTrue(controller.state.value.items.isEmpty())
            secrets.value = record.copy(identity = record.identity.copy(scopes = emptyList()))
            sessions.restore()
            runCurrent()
            assertEquals(listOf("/v1/nutrition/day"), paths)
            assertTrue(controller.state.value.settled)
            assertEquals(4, assistantSuggestionItems(controller.state.value, true).size)
        } finally { http.close() }
    }
}

private val NOW = Instant.parse("2026-10-16T06:00:00Z").toEpochMilliseconds()
