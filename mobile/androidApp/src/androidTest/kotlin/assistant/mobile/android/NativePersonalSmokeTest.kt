package assistant.mobile.android

import android.content.Intent
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
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

/** Real Compose/client/journal, synthetic loopback HTTP; never accesses family data. */
@RunWith(AndroidJUnit4::class)
class NativePersonalSmokeTest {
    @get:Rule val compose = createEmptyComposeRule()
    private lateinit var server: NativeLoopbackServer
    private lateinit var scenario: ActivityScenario<MainActivity>
    private lateinit var runtime: NativeRuntime

    @Before
    fun launchPersonalClient() {
        configureStoreCapture(true)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        context.getSharedPreferences("appearance", 0).edit().clear().commit()
        server = NativeLoopbackServer().apply {
            personalEnabled = true
            nutritionEnabled = true
        }
        val instance = "personal-smoke-${UUID.randomUUID()}"
        NativeRuntimeFactory.testFactory = { runtimeContext ->
            NativeRuntime(runtimeContext, origin = server.origin, instance = instance).also { runtime = it }
        }
        scenario = ActivityScenario.launch(Intent(context, MainActivity::class.java))
        awaitTag("screen_Login")
        compose.onNodeWithTag("login_login").performScrollTo().performTextReplacement("person")
        compose.onNodeWithTag("login_password").performScrollTo().performTextInput("Synthetic Password 123!")
        compose.onNodeWithTag("login_submit").performScrollTo().performClick()
        awaitTag("screen_Today")
        compose.awaitNativeTabBar(::imeClosed)
    }

    @After fun releasePersonalClient() {
        try {
            if (::runtime.isInitialized) runBlocking {
                if (runtime.core.sessions.lease() != null) runtime.core.sessions.logout()
            }
        } finally {
            if (::scenario.isInitialized) scenario.close()
            configureStoreCapture(false)
            NativeRuntimeFactory.testFactory = null
            if (::server.isInitialized) server.close()
            InstrumentationRegistry.getInstrumentation().targetContext
                .getSharedPreferences("appearance", 0).edit().clear().commit()
        }
    }

    @Test fun personalSectionOpensWithFixtureData() {
        openPersonal()
        awaitTag("personal_metric_weight")
        compose.onNodeWithTag("personal_metric_weight").performScrollTo().assertIsDisplayed()
        compose.captureScreen("play-personal")
        compose.onNodeWithTag("personal_metric_body_minerals").performScrollTo().assertTextContains("Нет данных")
        awaitTag("personal_nutrition_protein_g")
        compose.onNodeWithTag("personal_nutrition_protein_g").performScrollTo().assertTextContains("17,5 г")
        compose.onNodeWithTag("personal_nutrition_value").performScrollTo().assertTextContains("известно 428")
        assertEquals(0, server.personal.writeCount())
        assertTrue(server.failures.toString(), server.failures.isEmpty())
    }

    private fun openPersonal() {
        click("profile_menu")
        click("open_Personal")
        awaitTag("screen_Personal")
        awaitPersonalReads()
    }

    private fun awaitPersonalReads() {
        compose.waitUntil(10_000) { NativePersonalFixture.READ_PATHS.all { path ->
            server.responses.any { it == "GET $path 200" }
        } }
        compose.waitForIdle()
    }

    private fun click(tag: String) {
        awaitTag(tag)
        compose.waitUntil(10_000) { !compose.onNodeWithTag(tag).fetchSemanticsNode().config.contains(SemanticsProperties.Disabled) }
        compose.onNodeWithTag(tag).performScrollToIfScrollable().performClick()
        compose.waitForIdle()
    }

    private fun awaitTag(tag: String, unmerged: Boolean = false) {
        compose.waitUntil(15_000) { compose.onAllNodes(hasTestTag(tag), useUnmergedTree = unmerged)
            .fetchSemanticsNodes().isNotEmpty() }
    }

    private fun imeClosed(): Boolean {
        var closed = false
        scenario.onActivity { closed = it.window.decorView.isImeClosed() }
        return closed
    }
}
