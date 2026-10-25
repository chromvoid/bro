package assistant.mobile.android

import android.content.Intent
import android.view.KeyEvent
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
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
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

/** Stage 16 runs real native screens and journals against two synthetic HTTP identities. */
@RunWith(AndroidJUnit4::class)
class NativeCircleSmokeTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private lateinit var server: NativeLoopbackServer
    private lateinit var scenario: ActivityScenario<MainActivity>
    private lateinit var runtime: NativeRuntime

    @Before fun launchCircleClient() {
        val context = instrumentation.targetContext
        context.getSharedPreferences("appearance", 0).edit().clear().commit()
        server = NativeLoopbackServer().apply { circleEnabled = true }
        val instance = "circle-${UUID.randomUUID()}"
        NativeRuntimeFactory.testFactory = { runtimeContext ->
            NativeRuntime(runtimeContext, origin = server.origin, instance = instance).also { runtime = it }
        }
        scenario = ActivityScenario.launch(Intent(context, MainActivity::class.java))
        awaitTag("screen_Login")
        replaceText("login_login", "person")
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

    @After fun releaseCircleClient() {
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

    @Test fun circleSectionOpensWithFixtureData() {
        openCircle()
        compose.onNodeWithTag("circle_space_${NativeCircleFixture.SPACE_ID}").performScrollTo().assertIsDisplayed()
        assertFixtureHealthy()
    }

    private fun openCircle() {
        click("profile_menu")
        click("open_Circle")
        awaitTag("screen_Circle")
        awaitTag("circle_space_${NativeCircleFixture.SPACE_ID}")
    }

    private fun assertFixtureHealthy() {
        assertTrue(server.failures.joinToString(), server.failures.isEmpty())
        server.requests.filter { it.path.startsWith("/v1/channels") || it.path.startsWith("/v1/spaces") ||
            it.path.startsWith("/v1/relationships") || it.path.startsWith("/v1/community/reports") }.forEach { request ->
            assertEquals("Bearer ${NativeLoopbackServer.credential(NativeCircleFixture.USER_A)}", request.headers["authorization"])
            assertFalse(request.headers.containsKey("cookie"))
            if (request.method == "POST") {
                assertEquals(NativeCircleFixture.USER_A, request.headers["x-expected-user"])
                if (request.path.endsWith("/messages") || request.path == "/v1/community/reports")
                    UUID.fromString(requireNotNull(request.headers["idempotency-key"]))
            }
        }
    }

    private fun click(tag: String) {
        awaitTag(tag)
        val target = compose.onNodeWithTag(tag)
        if (generateSequence(target.fetchSemanticsNode().parent) { it.parent }
            .any { it.config.contains(SemanticsActions.ScrollBy) }) target.performScrollTo()
        target.assertIsDisplayed().assertHeightIsAtLeast(48.dp).performClick()
    }

    private fun replaceText(tag: String, text: String) {
        awaitTag(tag)
        val target = compose.onNodeWithTag(tag)
        if (generateSequence(target.fetchSemanticsNode().parent) { it.parent }
            .any { it.config.contains(SemanticsActions.ScrollBy) }) target.performScrollTo()
        target.performTextReplacement(text)
        dismissIme()
    }

    private fun awaitTag(tag: String) {
        awaitCondition("visible semantic node $tag") {
            compose.onAllNodes(hasTestTag(tag)).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun awaitCondition(description: String, condition: () -> Boolean) {
        try {
            compose.waitUntil(15_000) {
                if (server.failures.isNotEmpty()) throw AssertionError("Circle fixture failed: ${server.failures}")
                condition()
            }
        } catch (error: ComposeTimeoutException) {
            throw AssertionError("Timed out: $description; responses=${server.responses}; failures=${server.failures}", error)
        }
    }

    private fun dismissIme() {
        compose.waitForIdle()
        var visible = false
        scenario.onActivity { visible = ViewCompat.getRootWindowInsets(it.window.decorView)?.isVisible(WindowInsetsCompat.Type.ime()) == true }
        if (visible) {
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
            compose.waitForIdle()
        }
    }
}
