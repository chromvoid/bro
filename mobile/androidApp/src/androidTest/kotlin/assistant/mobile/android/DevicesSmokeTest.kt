package assistant.mobile.android

import android.content.Intent
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasText
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
import assistant.mobile.design.R as DesignR
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

/** Stage 19 uses real native navigation and private HTTP/PNG transport on emulator loopback. */
@RunWith(AndroidJUnit4::class)
class DevicesSmokeTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private lateinit var server: NativeLoopbackServer
    private lateinit var scenario: ActivityScenario<MainActivity>
    private lateinit var runtime: NativeRuntime

    @Before fun launchClient() {
        val context = instrumentation.targetContext
        context.getSharedPreferences("appearance", 0).edit().clear().commit()
        server = NativeLoopbackServer().apply { devicesEnabled = true }
        val instance = "devices-${UUID.randomUUID()}"
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

    @Test fun devicesSectionOpensWithFixtureData() {
        openDevices()
        val android = instrumentation.targetContext.getString(DesignR.string.devices_kind_android)
        compose.onNodeWithTag("devices_item_synthetic-device").performScrollTo()
            .assert(hasText(android, substring = true) or hasAnyDescendant(hasText(android, substring = true)))
        compose.onNodeWithTag("devices_item_${NativeDevicesFixture.AGENT_ID}")
            .performScrollTo().assert(hasText("Synthetic agent") or hasAnyDescendant(hasText("Synthetic agent")))
        compose.onNodeWithTag("devices_item_${NativeDevicesFixture.DISPLAY_ID}")
            .performScrollTo().assertIsDisplayed().assertTextContains(NativeDevicesFixture.DISPLAY_NAME)
        assertFixtureHealthy()
    }

    private fun openDevices() {
        click("profile_menu")
        click("open_Settings")
        awaitTag("screen_Settings")
        click("open_Devices")
        awaitTag("screen_Devices")
        awaitTag("devices_item_${NativeDevicesFixture.DISPLAY_ID}")
    }

    private fun assertFixtureHealthy() = assertTrue(server.failures.joinToString(), server.failures.isEmpty())

    private fun click(tag: String, ancestor: String? = null) {
        val matcher = hasTestTag(tag).let { if (ancestor == null) it else it and hasAnyAncestor(hasTestTag(ancestor)) }
        compose.waitUntil(15_000) { compose.onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty() }
        val target = compose.onNode(matcher)
        if (generateSequence(target.fetchSemanticsNode().parent) { it.parent }
            .any { it.config.contains(SemanticsActions.ScrollBy) }) target.performScrollTo()
        target.assertIsDisplayed().assertHeightIsAtLeast(48.dp).performClick()
    }

    private fun awaitTag(tag: String) {
        compose.waitUntil(15_000) { compose.onAllNodes(hasTestTag(tag)).fetchSemanticsNodes().isNotEmpty() }
    }
}
