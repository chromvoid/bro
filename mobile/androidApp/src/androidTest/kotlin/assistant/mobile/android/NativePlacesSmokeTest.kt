package assistant.mobile.android

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.os.SystemClock
import android.view.KeyEvent
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.unit.dp
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import assistant.mobile.platform.location.AndroidPositionSource
import assistant.mobile.platform.location.CurrentPositionProvider
import assistant.mobile.platform.location.NativeLocationCollector
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

/** Opens the real native Places screens against synthetic HTTP data. */
@RunWith(AndroidJUnit4::class)
class NativePlacesSmokeTest {
    @get:Rule val compose = createEmptyComposeRule()
    private lateinit var server: NativeLoopbackServer
    private lateinit var scenario: ActivityScenario<MainActivity>
    private lateinit var runtime: NativeRuntime

    @Before fun launchPlacesClient() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        context.getSharedPreferences("appearance", 0).edit().clear().commit()
        server = NativeLoopbackServer().apply { placesEnabled = true }
        val instance = "places-smoke-${UUID.randomUUID()}"
        NativeRuntimeFactory.testFactory = { runtimeContext ->
            val position = AndroidPositionSource(runtimeContext,
                requestPermission = { runtime.locationPermission.requestForeground() },
                provider = CurrentPositionProvider { syntheticPoint() })
            NativeRuntime(runtimeContext, origin = server.origin, instance = instance, positionSource = position).also { runtime = it }
        }
        scenario = ActivityScenario.launch(Intent(context, MainActivity::class.java))
        awaitTag("screen_Login")
    }

    private fun imeClosed(): Boolean {
        var closed = false
        scenario.onActivity { closed = it.window.decorView.isImeClosed() }
        return closed
    }

    @After fun releasePlacesClient() {
        try {
            if (::scenario.isInitialized) {
                val context = InstrumentationRegistry.getInstrumentation().targetContext
                context.startActivity(Intent(context, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
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

    @Test fun placesSectionOpensWithFixtureData() {
        login()
        compose.runOnIdle { runtime.openPlaces() }
        awaitTag("screen_Places")
        awaitTag("places_day_${server.places.date}")
        compose.onNodeWithTag("places_day_${server.places.date}").performScrollTo().assertHeightIsAtLeast(48.dp).performClick()
        awaitTag("screen_PlaceDay")
        awaitTag("places_timeline")
        compose.onNodeWithTag("places_stay_0").performScrollTo().assertTextEquals("Synthetic home")
        compose.onNodeWithTag("places_move_1").performScrollTo().assertTextContains("1,9 км", substring = true)
        compose.onNodeWithTag("places_stay_2").performScrollTo().assertTextEquals("Synthetic park")
        assertTrue(server.failures.toString(), server.failures.isEmpty())
    }

    @Test fun playLocationVideoShowsDisclosureBackgroundPermissionAndHistory() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        // Run before origin-move scenarios grant background access; revocation kills instrumentation.
        check(context.checkSelfPermission(Manifest.permission.ACCESS_BACKGROUND_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            "Location permission video requires background access to start ungranted"
        }
        for (permission in listOf(Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION)) {
            instrumentation.uiAutomation.grantRuntimePermission(context.packageName, permission)
        }
        login()
        compose.runOnIdle { runtime.openLocationSettings() }
        awaitTag("location_background")
        recordPermissionVideo("play-background-location") {
            click("location_background")
            compose.waitForIdle()
            SystemClock.sleep(5_000)
            compose.onNodeWithText(context.getString(R.string.privacy_continue)).performClick()
            compose.waitForIdle()
            clickPermissionSystemText("Permissions")
            SystemClock.sleep(2_000)
            clickPermissionSystemText("Location")
            SystemClock.sleep(2_000)
            clickPermissionSystemText("Allow all the time")
            SystemClock.sleep(2_000)
            repeat(3) {
                permissionVideoShell("input keyevent KEYCODE_BACK")
                SystemClock.sleep(500)
            }
            awaitTag("location_background")
            await("background collection enabled") { runtime.location.state.value?.background?.enabled == true }
            SystemClock.sleep(2_000)
            permissionVideoShell("input keyevent KEYCODE_HOME")
            // Synthetic GPS enters the real collector queue while the Activity is hidden.
            val collector = NativeLocationCollector.get(context, server.origin)
            collector.received(listOf(syntheticPoint()))
            runBlocking { assertTrue(collector.upload()) }
            assertTrue(server.places.backgroundPoints > 0)
            SystemClock.sleep(2_000)
            context.startActivity(Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
            awaitTag("location_points")
            compose.onNodeWithTag("location_points").performScrollTo().assertIsDisplayed()
            SystemClock.sleep(3_000)
            click("location_background")
            await("background collection stopped") { runtime.location.state.value?.background?.enabled == false }
        }
        assertTrue(server.failures.toString(), server.failures.isEmpty())
    }

    private fun syntheticPoint() = Location("synthetic-play-demo").apply {
        latitude = 55.76; longitude = 37.63; accuracy = 20f
        time = System.currentTimeMillis(); elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
    }

    private fun login() {
        compose.onNodeWithTag("login_login").performScrollTo().performTextReplacement("person")
        compose.onNodeWithTag("login_password").performScrollTo().performTextInput("Synthetic Password 123!")
        compose.waitForIdle()
        var imeVisible = false
        scenario.onActivity { imeVisible = ViewCompat.getRootWindowInsets(it.window.decorView)?.isVisible(WindowInsetsCompat.Type.ime()) == true }
        if (imeVisible) {
            InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
            compose.waitForIdle()
        }
        click("login_submit")
        awaitTag("screen_Today")
        compose.awaitNativeTabBar(::imeClosed)
    }
    private fun awaitTag(tag: String) = await("visible $tag") {
        compose.onAllNodes(hasTestTag(tag)).fetchSemanticsNodes().isNotEmpty()
    }
    private fun await(description: String, predicate: () -> Boolean) {
        compose.waitUntil(15_000) {
            if (server.failures.isNotEmpty()) throw AssertionError("Places $description: ${server.failures}")
            predicate()
        }
    }
    private fun click(tag: String, scroll: Boolean = true) {
        awaitTag(tag)
        await("enabled $tag") { !compose.onNodeWithTag(tag).fetchSemanticsNode().config.contains(SemanticsProperties.Disabled) }
        val node = compose.onNodeWithTag(tag)
        if (scroll) node.performScrollTo()
        node.assertIsDisplayed().assertIsEnabled().performClick()
        compose.waitForIdle()
    }
}
