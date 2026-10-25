package assistant.mobile.android

import android.content.Intent
import android.view.KeyEvent
import androidx.compose.ui.test.ComposeTimeoutException
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.core.view.ViewCompat
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

/** Real shell and Activity Back with a private synthetic HTTP fixture. */
@RunWith(AndroidJUnit4::class)
class NativeShellSmokeTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private lateinit var server: NativeLoopbackServer
    private lateinit var scenario: ActivityScenario<MainActivity>
    private lateinit var runtime: NativeRuntime

    @Before fun launchIsolatedClient() {
        val context = instrumentation.targetContext
        context.getSharedPreferences("appearance", 0).edit().clear().commit()
        server = NativeLoopbackServer()
        val instance = "shell-smoke-${UUID.randomUUID()}"
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
            try {
                if (::scenario.isInitialized) scenario.close()
            } finally {
                NativeRuntimeFactory.testFactory = null
                if (::server.isInitialized) server.close()
                instrumentation.targetContext.getSharedPreferences("appearance", 0).edit().clear().commit()
            }
        }
    }

    @Test fun standardBackReturnsThroughFoodStatsAndTodayThenFinishes() {
        server.nutritionEnabled = true
        login()
        click("tab_Food")
        awaitTag("screen_Food")
        assertTopLevelChrome()
        click("open_FoodStats")
        awaitTag("screen_FoodStats")
        compose.onNodeWithTag("back").assertIsDisplayed().assertHasClickAction()
            .assertHeightIsAtLeast(48.dp).assertWidthIsAtLeast(48.dp)
        compose.onNodeWithTag("notifications_bell").assertDoesNotExist()
        compose.onNodeWithTag("tab_Food").assertIsSelected()
        systemBack()
        awaitTag("screen_Food")
        assertTopLevelChrome()
        systemBack()
        awaitTag("screen_Today")
        assertTopLevelChrome()
        lateinit var activity: MainActivity
        scenario.onActivity { activity = it }
        systemBack(awaitComposeIdle = false)
        compose.waitUntil(10_000) {
            var finishing = false
            instrumentation.runOnMainSync { finishing = activity.isFinishing }
            finishing
        }
        assertTrue(activity.isFinishing)
    }

    @Test fun profileFiltersSectionsAndOpensInstalledPluginByItsTitle() {
        server.timersEnabled = true
        server.tradingEnabled = true
        server.trading.installationTitle = "Семейный портфель"
        login()
        compose.waitUntil(10_000) { server.requests.any { it.path == "/v1/plugins" } }
        val catalogReads = server.requests.count { it.path == "/v1/plugins" }
        click("profile_menu")
        compose.waitUntil(10_000) { server.requests.count { it.path == "/v1/plugins" } > catalogReads }
        compose.onNodeWithText("Person").assertIsDisplayed()
        compose.onNodeWithText("@person").assertIsDisplayed()
        listOf("Timers", "Recordings", "Circle", "Settings").forEach { section ->
            awaitTag("open_$section")
            compose.onNodeWithTag("open_$section").performScrollToIfScrollable().assertIsDisplayed()
        }
        listOf("Activity", "Personal", "Places").forEach { section ->
            compose.onNodeWithTag("open_$section").assertDoesNotExist()
        }
        val plugin = "plugin_open_${NativeTradingFixture.INSTALLATION_ID}"
        awaitTag(plugin)
        compose.onNodeWithTag(plugin).performScrollToIfScrollable().assertTextContains("Семейный портфель")
        click("open_Timers")
        awaitTag("screen_Timers")
        assertTopLevelChrome()
        compose.onNodeWithTag(plugin).assertDoesNotExist()
        systemBack()
        awaitTag("screen_Today")
        click("profile_menu")
        click(plugin)
        awaitTag("screen_Trading")
        compose.onNodeWithTag("screen_title").assertTextEquals("Семейный портфель")
        assertTopLevelChrome()
        systemBack()
        awaitTag("screen_Today")
        assertFixtureHealthy()
    }

    private fun login() {
        compose.loginNativeClient("person", "Synthetic Password 123!", ::imeClosed)
        awaitTag("tab_Today")
    }

    private fun assertTopLevelChrome() {
        compose.onNodeWithTag("back").assertDoesNotExist()
        listOf("open_devices_sheet", "notifications_bell", "profile_menu").forEach { tag ->
            compose.onNodeWithTag(tag).assertIsDisplayed().assertHasClickAction()
                .assertHeightIsAtLeast(48.dp).assertWidthIsAtLeast(48.dp)
        }
    }

    private fun click(tag: String) {
        awaitTag(tag)
        compose.onNodeWithTag(tag).performScrollToIfScrollable().assertIsDisplayed().performClick()
    }

    private fun awaitTag(tag: String, unmerged: Boolean = false) {
        try {
            compose.waitUntil(15_000) {
                compose.onAllNodes(hasTestTag(tag), useUnmergedTree = unmerged).fetchSemanticsNodes().isNotEmpty()
            }
        } catch (failure: ComposeTimeoutException) {
            throw AssertionError("Missing $tag; responses=${server.responses}; fixtureFailures=${server.failures}", failure)
        }
    }

    private fun imeClosed(): Boolean {
        var closed = false
        scenario.onActivity { closed = it.window.decorView.isImeClosed() }
        return closed
    }

    private fun systemBack(awaitComposeIdle: Boolean = true) {
        compose.waitUntil(10_000) {
            var ready = false
            scenario.onActivity {
                val view = it.window.decorView
                ready = view.hasWindowFocus() && view.isAttachedToWindow && ViewCompat.getRootWindowInsets(view) != null
            }
            ready
        }
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        if (awaitComposeIdle) compose.waitForIdle() else instrumentation.waitForIdleSync()
    }

    private fun assertFixtureHealthy() = assertTrue("Shell fixture failures: ${server.failures}", server.failures.isEmpty())
}
