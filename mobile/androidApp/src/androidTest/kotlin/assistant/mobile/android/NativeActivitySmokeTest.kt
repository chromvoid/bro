package assistant.mobile.android

import android.content.Intent
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ComposeTimeoutException
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import assistant.mobile.core.activity.ActivityState
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

/** Real Compose, private-session HTTP, journal and account events against synthetic loopback data. */
@RunWith(AndroidJUnit4::class)
class NativeActivitySmokeTest {
    @get:Rule val compose = createEmptyComposeRule()
    private lateinit var server: NativeLoopbackServer
    private lateinit var scenario: ActivityScenario<MainActivity>
    private lateinit var runtime: NativeRuntime

    @Before fun launchIsolatedActivityClient() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        context.getSharedPreferences("appearance", 0).edit().clear().commit()
        server = NativeLoopbackServer().apply { activityEnabled = true }
        val instance = "activity-smoke-${UUID.randomUUID()}"
        NativeRuntimeFactory.testFactory = { runtimeContext ->
            NativeRuntime(runtimeContext, origin = server.origin, instance = instance).also { runtime = it }
        }
        scenario = ActivityScenario.launch(Intent(context, MainActivity::class.java))
        awaitTag("screen_Login")
    }

    @After fun releaseActivityClient() {
        try {
            if (::scenario.isInitialized) {
                scenario.moveToState(Lifecycle.State.RESUMED)
                if (::runtime.isInitialized) runBlocking {
                    if (runtime.core.sessions.lease() != null) runtime.core.sessions.logout()
                }
            }
        } finally {
            if (::scenario.isInitialized) scenario.close()
            NativeRuntimeFactory.testFactory = null
            if (::server.isInitialized) server.close()
            InstrumentationRegistry.getInstrumentation().targetContext
                .getSharedPreferences("appearance", 0).edit().clear().commit()
        }
    }

    @Test fun activitySectionOpensWithFixtureData() {
        login()
        openActivity()
        awaitText("activity_movement_steps", "2400")
        assertFalse(compactText("activity_movement_steps").contains("2820"))
        assertEquals(0, writes().size)
        assertFixtureHealthy()
    }

    private fun login() {
        compose.onNodeWithTag("login_login").performScrollTo().performTextReplacement("person")
        compose.onNodeWithTag("login_password").performScrollTo().performTextInput("Synthetic Password 123!")
        click("login_submit")
        awaitTag("screen_Today")
        awaitTabBar()
    }

    private fun openActivity() {
        click("profile_menu")
        click("open_Activity")
        awaitTag("screen_Activity")
        awaitState("initial movement and journal") { !it.overview.loading && it.overview.data != null &&
            !it.sessions.loading && it.sessions.data != null && !it.runningWorkouts.loading && it.runningWorkouts.data != null }
    }

    private fun writes() = server.requests.filter { it.method == "POST" && it.path == "/v1/activity/operations" }
    private fun awaitState(description: String, predicate: (ActivityState) -> Boolean) =
        await(description) { runtime.activity.state.value?.let(predicate) == true }

    private fun await(description: String, predicate: () -> Boolean) {
        try {
            compose.waitUntil(15_000) {
                if (server.failures.isNotEmpty()) throw AssertionError("Activity fixture failed: ${server.failures}")
                predicate()
            }
        } catch (timeout: ComposeTimeoutException) {
            val state = runtime.activity.state.value
            throw AssertionError("Activity $description; state=$state; posts=${writes().map { it.json().optString("kind") }}; " +
                "admitted=${server.activity.writeCount()}; responses=${server.responses}; fixtureFailures=${server.failures}", timeout)
        }
    }

    private fun awaitTag(tag: String, unmerged: Boolean = false) = await("visible tag $tag") {
        compose.onAllNodes(hasTestTag(tag), useUnmergedTree = unmerged).fetchSemanticsNodes().isNotEmpty()
    }

    private fun awaitText(tag: String, value: String, unmerged: Boolean = false) {
        awaitTag(tag, unmerged)
        await("$tag contains $value") { compactText(tag, unmerged).contains(value) }
        compose.onNodeWithTag(tag, useUnmergedTree = unmerged).performScrollTo().assertIsDisplayed()
    }

    private fun compactText(tag: String, unmerged: Boolean = false) = compose.onNodeWithTag(tag, useUnmergedTree = unmerged)
        .fetchSemanticsNode().config[SemanticsProperties.Text].joinToString { it.text }
        .filterNot { it.isWhitespace() || it == '\u00a0' || it == '\u202f' }

    private fun click(tag: String) {
        awaitTag(tag)
        await("$tag enabled") { !compose.onNodeWithTag(tag).fetchSemanticsNode().config.contains(SemanticsProperties.Disabled) }
        compose.onNodeWithTag(tag).performScrollToIfScrollable().performClick()
        compose.waitForIdle()
    }

    private fun awaitTabBar() = compose.awaitNativeTabBar {
        var closed = false
        scenario.onActivity { closed = it.window.decorView.isImeClosed() }
        closed
    }

    private fun assertFixtureHealthy() {
        assertTrue("Activity fixture failures: ${server.failures}", server.failures.isEmpty())
        writes().forEach {
            UUID.fromString(requireNotNull(it.headers["idempotency-key"]))
            assertEquals("synthetic-user-a", it.headers["x-expected-user"])
            assertEquals("Bearer ${NativeLoopbackServer.credential("synthetic-user-a")}", it.headers["authorization"])
        }
    }
}
