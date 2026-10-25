package assistant.mobile.android

import android.content.Intent
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

/** Opens the real native Food screen against an emulator-only HTTP fixture. */
@RunWith(AndroidJUnit4::class)
class NutritionFullSmokeTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private lateinit var server: NativeLoopbackServer
    private lateinit var scenario: ActivityScenario<MainActivity>
    private lateinit var runtime: NativeRuntime

    @Before fun launchClient() {
        configureStoreCapture(true)
        val context = instrumentation.targetContext
        context.getSharedPreferences("appearance", 0).edit().clear().commit()
        server = NativeLoopbackServer().apply { nutritionEnabled = true }
        val instance = "nutrition-full-${UUID.randomUUID()}"
        NativeRuntimeFactory.testFactory = { runtimeContext ->
            NativeRuntime(runtimeContext, origin = server.origin, instance = instance).also { runtime = it }
        }
        scenario = ActivityScenario.launch(Intent(context, MainActivity::class.java))
        awaitTag("screen_Login")
        compose.onNodeWithTag("login_login").performScrollTo().performTextReplacement("person")
        compose.onNodeWithTag("login_password").performScrollTo().performTextInput("Synthetic Password 123!")
        click("login_submit")
        awaitTag("screen_Today")
        compose.awaitNativeTabBar(::imeClosed)
        click("tab_Food")
        awaitTag("screen_Food")
        awaitTag("food_day_${server.nutrition.date}")
    }

    private fun imeClosed(): Boolean {
        var closed = false
        scenario.onActivity { closed = it.window.decorView.isImeClosed() }
        return closed
    }

    @After fun releaseClient() {
        try {
            if (::scenario.isInitialized) {
                if (::runtime.isInitialized) runBlocking {
                    if (runtime.core.sessions.lease() != null) runtime.core.sessions.logout()
                }
            }
        } finally {
            if (::scenario.isInitialized) scenario.close()
            configureStoreCapture(false)
            NativeRuntimeFactory.testFactory = null
            if (::server.isInitialized) server.close()
            instrumentation.targetContext.getSharedPreferences("appearance", 0).edit().clear().commit()
        }
    }

    @Test fun foodSectionOpensWithFixtureData() {
        compose.onNodeWithTag("screen_Food").assertIsDisplayed()
        compose.onNodeWithTag("food_day_${server.nutrition.date}").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("food_total_energy_kcal").performScrollTo().assertTextEquals("≥ 428")
        assertEquals(0, server.nutrition.writeCount())
        assertTrue(server.failures.joinToString(), server.failures.isEmpty())
        compose.onNodeWithTag("food_total_energy_kcal").performScrollTo()
        compose.captureScreen("play-food")
    }

    private fun click(tag: String) {
        awaitTag(tag)
        compose.onNodeWithTag(tag).performScrollToIfScrollable().assertHeightIsAtLeast(48.dp).performClick()
    }

    private fun awaitTag(tag: String) {
        compose.waitUntil(15_000) { compose.onAllNodes(hasTestTag(tag)).fetchSemanticsNodes().isNotEmpty() }
    }
}
