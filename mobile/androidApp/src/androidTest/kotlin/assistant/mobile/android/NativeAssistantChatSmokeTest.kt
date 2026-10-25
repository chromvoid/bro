package assistant.mobile.android

import android.content.Intent
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

/** Opens the real native chat against synthetic HTTP data. */
@RunWith(AndroidJUnit4::class)
class NativeAssistantChatSmokeTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private lateinit var server: NativeLoopbackServer
    private lateinit var scenario: ActivityScenario<MainActivity>
    private lateinit var runtime: NativeRuntime

    @Before fun launchIsolatedClient() {
        configureStoreCapture(true)
        val context = instrumentation.targetContext
        context.getSharedPreferences("appearance", 0).edit().clear().commit()
        server = NativeLoopbackServer()
        val instance = "assistant-chat-smoke-${UUID.randomUUID()}"
        NativeRuntimeFactory.testFactory = { runtimeContext ->
            NativeRuntime(runtimeContext, origin = server.origin, instance = instance).also { runtime = it }
        }
        scenario = ActivityScenario.launch(Intent(context, MainActivity::class.java))
        awaitTag("screen_Login")
    }

    @After fun releaseClient() {
        try {
            if (::runtime.isInitialized) runBlocking {
                if (runtime.core.sessions.lease() != null) runtime.core.sessions.logout()
            }
        } finally {
            if (::scenario.isInitialized) scenario.close()
            configureStoreCapture(false)
            NativeRuntimeFactory.testFactory = null
            if (::server.isInitialized) server.close()
            instrumentation.targetContext.getSharedPreferences("appearance", 0).edit().clear().commit()
        }
    }

    @Test fun chatSectionOpensWithFixtureData() {
        server.seedStoreConversation()
        login()
        click("tab_Assistant")
        awaitDraft("")
        assertChatChrome()
        awaitTag("assistant_user_history-0000")
        awaitTag("assistant_reply_turn-history-0000")
        compose.onNodeWithTag("assistant_user_history-0000").assertIsDisplayed().assertTextContains("Как сделать вечер спокойнее?")
        compose.onNodeWithTag("assistant_reply_turn-history-0000").assertIsDisplayed()
        assertNoMessage()
        assertFixtureHealthy()
        compose.captureScreen("play-assistant")
    }

    private fun login() {
        compose.loginNativeClient("person", "Synthetic Password 123!") {
            var closed = false
            scenario.onActivity { closed = it.window.decorView.isImeClosed() }
            closed
        }
    }

    private fun assertChatChrome() {
        compose.onNodeWithTag("screen_Assistant").assertIsDisplayed()
        compose.onNodeWithTag("native_tab_bar").assertDoesNotExist()
        listOf("open_devices_sheet", "notifications_bell", "profile_menu").forEach {
            compose.onNodeWithTag(it).assertDoesNotExist()
        }
        compose.onNodeWithTag("back").assertIsDisplayed().assertHasClickAction()
        compose.onNodeWithTag("assistant_search_open").assertIsDisplayed()
    }

    private fun awaitDraft(text: String) {
        awaitTag("draft")
        compose.waitUntil(10_000) {
            val node = compose.onNodeWithTag("draft").fetchSemanticsNode()
            !node.config.contains(SemanticsProperties.Disabled) && node.config[SemanticsProperties.EditableText].text == text
        }
    }

    private fun messages() = server.requests.filter { it.method == "POST" && it.path == "/v1/conversation/messages" }

    private fun assertNoMessage() = assertTrue(messages().isEmpty())

    private fun click(tag: String) {
        awaitTag(tag)
        compose.onNodeWithTag(tag).assertIsDisplayed().performClick()
    }

    private fun awaitTag(tag: String) {
        compose.waitUntil(15_000) { compose.onAllNodes(hasTestTag(tag)).fetchSemanticsNodes().isNotEmpty() }
    }

    private fun assertFixtureHealthy() = assertTrue("Chat fixture failures: ${server.failures}", server.failures.isEmpty())
}
