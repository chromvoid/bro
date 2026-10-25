package assistant.mobile.android

import android.content.Intent
import android.net.Uri
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ComposeTimeoutException
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

/** Opens the real native task screen against synthetic HTTP data. */
@RunWith(AndroidJUnit4::class)
class NativeTasksSmokeTest {
    @get:Rule val compose = createEmptyComposeRule()
    private lateinit var server: NativeLoopbackServer
    private lateinit var scenario: ActivityScenario<MainActivity>
    private lateinit var runtime: NativeRuntime
    private var launched = false

    @Before fun launchIsolatedTasksClient() {
        configureStoreCapture(true)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        context.getSharedPreferences("appearance", 0).edit().clear().commit()
        server = NativeLoopbackServer().apply { tasksEnabled = true; tasks.seedStoreTask() }
        val instance = "tasks-smoke-${UUID.randomUUID()}"
        NativeRuntimeFactory.testFactory = { runtimeContext ->
            NativeRuntime(runtimeContext, origin = server.origin, instance = instance).also { runtime = it }
        }
        scenario = ActivityScenario.launch(Intent(context, MainActivity::class.java))
        launched = true
        awaitTag("screen_Login")
    }

    @After fun releaseTasksClient() {
        try {
            if (launched) {
                if (::runtime.isInitialized) runBlocking {
                    if (runtime.core.sessions.lease() != null) runtime.core.sessions.logout()
                }
            }
        } finally {
            if (launched) scenario.close()
            configureStoreCapture(false)
            launched = false
            NativeRuntimeFactory.testFactory = null
            if (::server.isInitialized) server.close()
            InstrumentationRegistry.getInstrumentation().targetContext
                .getSharedPreferences("appearance", 0).edit().clear().commit()
        }
    }

    @Test fun tasksSectionOpensWithFixtureData() {
        login()
        awaitTag("today_task_${NativeTasksFixture.TASK_ID}")
        compose.captureScreen("play-today")
        compose.onNodeWithTag("today_task_${NativeTasksFixture.TASK_ID}")
            .performScrollTo().assertHeightIsAtLeast(48.dp).performClick()
        awaitTag("tasks_detail_${NativeTasksFixture.TASK_ID}")
        compose.onNodeWithTag("tasks_detail_${NativeTasksFixture.TASK_ID}").assertIsDisplayed()
        compose.onNodeWithTag("task_title").performScrollTo().assert(
            SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString("Прогуляться после обеда")))
        assertTrue(server.requests.any { it.method == "GET" && it.path.substringBefore('?') == "/v1/tasks/items" &&
            Uri.parse(it.path).getQueryParameter("view") == "today" && Uri.parse(it.path).getQueryParameter("limit") == "5" })
        assertEquals(0, server.tasks.writeCount())
        assertFixtureHealthy()
    }

    private fun login() {
        compose.onNodeWithTag("login_login").performScrollTo().performTextReplacement("person")
        compose.onNodeWithTag("login_password").performScrollTo().performTextInput("Synthetic Password 123!")
        compose.onNodeWithTag("login_submit").performScrollTo().performClick()
        awaitTag("screen_Today")
        assertTrue("The signed-in fixture identity must grant tasks.read",
            runtime.core.sessions.identity.value?.scopes?.contains("tasks.read") == true)
        compose.awaitNativeTabBar(::imeClosed)
    }

    private fun writes(): List<NativeLoopbackServer.Request> = server.requests.filter {
        it.method == "POST" && it.path == "/v1/tasks/operations"
    }

    private fun awaitTag(tag: String) {
        try {
            compose.waitUntil(15_000) { compose.onAllNodes(hasTestTag(tag)).fetchSemanticsNodes().isNotEmpty() }
        } catch (error: ComposeTimeoutException) {
            throw AssertionError("Missing $tag; responses=${server.responses}; fixtureFailures=${server.failures}", error)
        }
    }

    private fun imeClosed(): Boolean {
        var closed = false
        scenario.onActivity { closed = it.window.decorView.isImeClosed() }
        return closed
    }

    private fun assertFixtureHealthy() {
        assertTrue("Task fixture failures: ${server.failures}", server.failures.isEmpty())
        writes().forEach {
            UUID.fromString(requireNotNull(it.headers["idempotency-key"]))
            assertEquals("synthetic-user-a", it.headers["x-expected-user"])
            assertEquals("Bearer ${NativeLoopbackServer.credential("synthetic-user-a")}", it.headers["authorization"])
        }
    }
}
