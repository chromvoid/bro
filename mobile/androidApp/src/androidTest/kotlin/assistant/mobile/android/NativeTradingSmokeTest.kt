package assistant.mobile.android

import android.content.Intent
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.ComposeTimeoutException
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
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

/** Stage 25a runs the compiled trading module against a private synthetic plugin installation. */
@RunWith(AndroidJUnit4::class)
class NativeTradingSmokeTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private lateinit var server: NativeLoopbackServer
    private lateinit var scenario: ActivityScenario<MainActivity>
    private lateinit var runtime: NativeRuntime

    @Before fun launchIsolatedClient() {
        val context = instrumentation.targetContext
        context.getSharedPreferences("appearance", 0).edit().clear().commit()
        server = NativeLoopbackServer().apply { tradingEnabled = true }
        val instance = "trading-smoke-${UUID.randomUUID()}"
        NativeRuntimeFactory.testFactory = { runtimeContext ->
            NativeRuntime(runtimeContext, origin = server.origin, instance = instance).also { runtime = it }
        }
        scenario = ActivityScenario.launch(Intent(context, MainActivity::class.java))
        awaitTag("screen_Login")
    }

    private fun imeClosed(): Boolean {
        var closed = false
        scenario.onActivity { closed = it.window.decorView.isImeClosed() }
        return closed
    }

    @After fun releaseClient() {
        try {
            if (::runtime.isInitialized) runBlocking {
                if (runtime.core.sessions.lease() != null) runtime.core.sessions.logout()
            }
        } finally {
            if (::scenario.isInitialized) scenario.close()
            NativeRuntimeFactory.testFactory = null
            if (::server.isInitialized) server.close()
            instrumentation.targetContext.getSharedPreferences("appearance", 0).edit().clear().commit()
        }
    }

    @Test fun tradingSectionOpensWithFixtureData() {
        login()
        openTrading()
        awaitTag("trading_portfolio_chart")
        compose.onNodeWithTag("trading_portfolio_chart").performScrollTo().assertIsDisplayed()
        assertTrue(invokes("portfolio.get").isNotEmpty())
        assertTrue(invokes("portfolio.history").isNotEmpty())
        assertFixtureHealthy()
    }

    private fun login() {
        compose.onNodeWithTag("login_login").performScrollTo().performTextReplacement("person")
        compose.onNodeWithTag("login_password").performScrollTo().performTextInput("Synthetic Password 123!")
        click("login_submit")
        awaitTag("screen_Today")
        compose.awaitNativeTabBar(::imeClosed)
    }

    private fun openTrading() {
        click("profile_menu")
        click("plugin_open_${NativeTradingFixture.INSTALLATION_ID}")
        awaitTag("screen_Trading")
    }

    private fun invokes(tool: String? = null): List<NativeLoopbackServer.Request> = server.requests.filter {
        it.method == "POST" && it.path == "${NativeTradingFixture.BASE}/invoke" &&
            (tool == null || it.json().getString("tool_id") == tool)
    }

    private fun assertFixtureHealthy() {
        assertTrue("Trading fixture failures: ${server.failures}", server.failures.isEmpty())
        val privateRequests = server.requests.filter { it.path == "/v1/plugins" || it.path.startsWith("${NativeTradingFixture.BASE}/") }
        assertTrue(privateRequests.isNotEmpty())
        privateRequests.forEach {
            assertEquals("Bearer ${NativeLoopbackServer.credential("synthetic-user-a")}", it.headers["authorization"])
            assertFalse(it.headers.containsKey("cookie"))
        }
    }

    private fun click(tag: String) {
        awaitTag(tag)
        val target = compose.onNodeWithTag(tag)
        if (generateSequence(target.fetchSemanticsNode().parent) { it.parent }
            .any { it.config.contains(SemanticsActions.ScrollBy) }) target.performScrollTo()
        target.assertIsDisplayed().assertHeightIsAtLeast(48.dp).performClick()
    }

    private fun awaitTag(tag: String) {
        try {
            compose.waitUntil(15_000) { compose.onAllNodes(hasTestTag(tag)).fetchSemanticsNodes().isNotEmpty() }
        } catch (failure: ComposeTimeoutException) {
            throw AssertionError("Missing $tag; responses=${server.responses}; fixtureFailures=${server.failures}", failure)
        }
    }
}
