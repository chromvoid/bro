package assistant.mobile.android

import android.content.Intent
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
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

/** Real native journal, HTTP, navigation and countdown; only the server is replaced. */
@RunWith(AndroidJUnit4::class)
class NativeTimersSmokeTest {
    @get:Rule val compose = createEmptyComposeRule()
    private lateinit var server: NativeLoopbackServer
    private lateinit var scenario: ActivityScenario<MainActivity>
    private lateinit var runtime: NativeRuntime
    private var launched = false

    @Before fun launchIsolatedTimersClient() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        context.getSharedPreferences("appearance", 0).edit().clear().commit()
        server = NativeLoopbackServer().apply { timersEnabled = true; tasksEnabled = true }
        val instance = "timers-smoke-${UUID.randomUUID()}"
        NativeRuntimeFactory.testFactory = { runtimeContext ->
            NativeRuntime(runtimeContext, origin = server.origin, instance = instance).also { runtime = it }
        }
        scenario = ActivityScenario.launch(Intent(context, MainActivity::class.java))
        launched = true
        awaitTag("screen_Login")
    }

    @After fun releaseTimersClient() {
        try {
            if (launched) {
                scenario.moveToState(Lifecycle.State.RESUMED)
                if (::runtime.isInitialized) runBlocking {
                    if (runtime.core.sessions.lease() != null) runtime.core.sessions.logout()
                }
            }
        } finally {
            if (launched) scenario.close()
            launched = false
            NativeRuntimeFactory.testFactory = null
            if (::server.isInitialized) server.close()
            InstrumentationRegistry.getInstrumentation().targetContext
                .getSharedPreferences("appearance", 0).edit().clear().commit()
        }
    }

    @Test fun timersSectionOpensWithFixtureData() {
        server.timers.seedTimer()
        login()
        openTimers()
        val id = NativeTimersFixture.TIMER_ID
        awaitTag("timer_running_$id")
        compose.onNodeWithTag("timer_running_$id").performScrollTo().assertIsDisplayed()
        assertEquals(0, writes().size)
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

    private fun openTimers() {
        compose.awaitNativeTabBar(::imeClosed)
        compose.onNodeWithTag("profile_menu").performClick()
        compose.onNodeWithTag("open_Timers").performScrollTo().performClick()
        awaitTag("screen_Timers")
        awaitTag("timer_start")
    }

    private fun writes(): List<NativeLoopbackServer.Request> = server.requests.filter {
        it.method == "POST" && it.path == "/v1/timers/operations"
    }

    private fun awaitTag(tag: String, useUnmergedTree: Boolean = false) {
        try {
            compose.waitUntil(15_000) {
                compose.onAllNodes(hasTestTag(tag), useUnmergedTree = useUnmergedTree).fetchSemanticsNodes().isNotEmpty()
            }
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
        assertTrue("Timer fixture failures: ${server.failures}", server.failures.isEmpty())
        writes().forEach {
            UUID.fromString(requireNotNull(it.headers["idempotency-key"]))
            assertEquals("synthetic-user-a", it.headers["x-expected-user"])
            assertEquals("Bearer ${NativeLoopbackServer.credential("synthetic-user-a")}", it.headers["authorization"])
        }
    }
}
