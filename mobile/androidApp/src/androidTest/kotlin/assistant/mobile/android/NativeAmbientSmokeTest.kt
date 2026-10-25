package assistant.mobile.android

import android.Manifest
import android.app.Notification
import android.app.NotificationManager
import android.content.Intent
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.ComposeTimeoutException
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import assistant.mobile.android.ambient.AmbientCaptureService
import assistant.mobile.android.ambient.AmbientRuntime
import assistant.mobile.core.GeneratedStrings
import assistant.mobile.design.localizedString
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

/** Actual native session, explicit capture consent and AudioRecord/FGS; HTTP is loopback. */
@RunWith(AndroidJUnit4::class)
class NativeAmbientSmokeTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val ambient get() = AmbientRuntime.get(context)
    private val notifications get() = context.getSystemService(NotificationManager::class.java)
    private lateinit var server: NativeLoopbackServer
    private lateinit var scenario: ActivityScenario<MainActivity>
    private lateinit var runtime: NativeRuntime
    private lateinit var launchIntent: Intent
    private val dictationAudio = SyntheticDictationAudioSource()
    private var originalFontScale = 1f

    @Before fun launchIsolatedNativeAudioClient() {
        originalFontScale = Settings.System.getFloat(context.contentResolver, Settings.System.FONT_SCALE, 1f)
        shell("settings put system font_scale 1.0")
        shell("pm grant ${context.packageName} ${Manifest.permission.RECORD_AUDIO}")
        if (Build.VERSION.SDK_INT >= 33) shell("pm grant ${context.packageName} ${Manifest.permission.POST_NOTIFICATIONS}")
        context.getSharedPreferences("appearance", 0).edit().clear().commit()
        instrumentation.runOnMainSync {
            ambient.setMicrophoneOwner("dictation", false)
            ambient.setMicrophoneOwner("call", false)
            ambient.clear(revokeAuthority = false)
        }
        server = NativeLoopbackServer().apply { ambientEnabled = true }
        NativeRuntimeFactory.testFactory = { runtimeContext ->
            NativeRuntime(runtimeContext, origin = server.origin, instance = "ambient-${UUID.randomUUID()}",
                dictationAudioSource = dictationAudio)
                .also { runtime = it }
        }
        scenario = ActivityScenario.launch(Intent(context, MainActivity::class.java))
        scenario.onActivity { launchIntent = Intent(it.intent) }
        login()
        await("the previous microphone owner released") { ambient.state.value.canStart && ambientNotification() == null }
    }

    private fun imeClosed(): Boolean {
        var closed = false
        scenario.onActivity { closed = it.window.decorView.isImeClosed() }
        return closed
    }

    @After fun releaseNativeAudioClient() {
        try {
            shell("input keyevent KEYCODE_WAKEUP")
            shell("wm dismiss-keyguard")
            shell("cmd statusbar collapse")
            if (::scenario.isInitialized) {
                context.startActivity(Intent(context, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
                scenario.onActivity { it.intent = Intent(launchIntent) }
                if (scenario.state != Lifecycle.State.RESUMED) scenario.moveToState(Lifecycle.State.CREATED)
                scenario.moveToState(Lifecycle.State.RESUMED)
            }
            if (::runtime.isInitialized) {
                instrumentation.runOnMainSync {
                    runtime.calls.teardown()
                    runtime.dictation.cancel()
                    runtime.speech.stop()
                    ambient.clear(revokeAuthority = false)
                }
                await("foreground microphone service removed") { ambientNotification() == null && ambient.state.value.canStart }
                runBlocking { if (runtime.core.sessions.lease() != null) runtime.core.sessions.logout() }
            }
        } finally {
            try {
                if (::scenario.isInitialized) {
                    scenario.onActivity { it.intent = Intent(launchIntent) }
                    scenario.close()
                }
            } finally {
                NativeRuntimeFactory.testFactory = null
                if (::server.isInitialized) server.close()
                shell("settings put system font_scale $originalFontScale")
                context.getSharedPreferences("appearance", 0).edit().clear().commit()
            }
        }
    }

    @Test fun onlyExplicitConsentStartsForegroundCaptureAndNotificationStopWorksWhileLocked() {
        assertFalse(ambient.state.value.recording)
        assertTrue(activeRecordings().isEmpty())
        click("today_ambient_controls")
        awaitTag("ambient_controls")
        compose.onNodeWithTag("ambient_action").performScrollTo().assertIsNotEnabled()
        assertFalse(ambient.state.value.recording)
        assertTrue(activeRecordings().isEmpty())
        assertEquals(null, ambientNotification())
        click("ambient_close")
        scenario.recreate()
        awaitTag("screen_Today")
        assertFalse(ambient.state.value.recording)
        startAmbientFromSpeech()
        assertForegroundNotification()
        click("ambient_close")
        instrumentation.runOnMainSync { runtime.openSection(FamilyScreen.Today) }
        awaitTag("today_ambient_status")
        compose.onNodeWithTag("today_ambient_controls").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("ambient_dot_recording", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithTag("today_ambient_status").performScrollTo().assertIsDisplayed()
            .assertTextEquals(context.localizedString(GeneratedStrings.AmbientAudio.recording))
        shell("input keyevent KEYCODE_SLEEP")
        await("the screen is locked while the foreground microphone keeps recording") {
            !context.getSystemService(PowerManager::class.java).isInteractive &&
                ambient.state.value.recording && ambientNotification() != null && activeRecordings().isNotEmpty()
        }
        assertForegroundNotification()
        requireNotNull(ambientNotification()).notification.actions.single().actionIntent.send()
        await("notification Stop releases the locked microphone") {
            !ambient.state.value.recording && ambientNotification() == null && activeRecordings().isEmpty()
        }
        shell("input keyevent KEYCODE_WAKEUP")
        shell("wm dismiss-keyguard")
        scenario.moveToState(Lifecycle.State.CREATED)
        scenario.moveToState(Lifecycle.State.RESUMED)
        awaitTag("screen_Today")
        scenario.recreate()
        awaitTag("screen_Today")
        assertFalse("Returning to the app never resumes capture by itself", ambient.state.value.recording)
        assertEquals(null, ambientNotification())
        assertFixtureHealthy()
    }

    @Test fun playMicrophoneVideoShowsConsentBackgroundNotificationAndStop() {
        deepLink("/settings/speech")
        awaitTag("screen_SpeechSettings")
        click("ambient_open_controls")
        awaitTag("ambient_controls")
        recordPermissionVideo("play-microphone") {
            SystemClock.sleep(4_000)
            click("ambient_consent")
            SystemClock.sleep(2_000)
            click("ambient_action")
            await("recording started") { ambient.state.value.recording && ambientNotification() != null }
            SystemClock.sleep(3_000)
            click("ambient_close")
            shell("input keyevent KEYCODE_HOME")
            shell("cmd statusbar expand-notifications")
            SystemClock.sleep(4_000)
            clickPermissionSystemText(context.getString(R.string.ambient_notification_stop))
            await("notification Stop released the microphone") { !ambient.state.value.recording && activeRecordings().isEmpty() }
            shell("cmd statusbar collapse")
            context.startActivity(Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
            awaitTag("screen_SpeechSettings")
            SystemClock.sleep(3_000)
        }
        assertFixtureHealthy()
    }

    private fun login() {
        awaitTag("screen_Login")
        compose.onNodeWithTag("login_login").performTextReplacement("person")
        compose.onNodeWithTag("login_password").performScrollTo().performTextReplacement("Synthetic Password 123!")
        click("login_submit")
        awaitTag("screen_Today")
        compose.awaitNativeTabBar(::imeClosed)
    }

    private fun startAmbientFromSpeech() {
        deepLink("/settings/speech")
        awaitTag("screen_SpeechSettings")
        click("ambient_open_controls")
        awaitTag("ambient_controls")
        await("an explicit capture start is available") { ambient.state.value.canStart }
        click("ambient_consent")
        click("ambient_action")
        await("the foreground service opens the actual AudioRecord", timeout = 30_000) {
            ambient.state.value.recording && activeRecordings().isNotEmpty() && ambientNotification() != null
        }
        assertForegroundNotification()
    }

    private fun activeRecordings() = context.getSystemService(AudioManager::class.java).activeRecordingConfigurations
    private fun ambientNotification() = notifications.activeNotifications.singleOrNull { it.id == AmbientCaptureService.NOTIFICATION_ID }
    private fun assertForegroundNotification() {
        val notification = requireNotNull(ambientNotification()).notification
        assertEquals(AmbientCaptureService.CHANNEL, notification.channelId)
        assertTrue(notification.flags and Notification.FLAG_FOREGROUND_SERVICE != 0)
        assertTrue(notification.flags and Notification.FLAG_ONGOING_EVENT != 0)
        assertEquals(1, notification.actions.size)
        assertNotNull(notification.actions.single().actionIntent)
    }

    private fun deepLink(path: String) {
        val marker = UUID.randomUUID().toString()
        scenario.onActivity { activity ->
            activity.startActivity(Intent(activity, MainActivity::class.java).setAction(Intent.ACTION_VIEW)
                .setData(Uri.parse(server.origin + path)).putExtra("ambient_smoke_link_attempt", marker)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP))
        }
        await("delivered $path") {
            var delivered = false
            scenario.onActivity { delivered = it.intent.getStringExtra("ambient_smoke_link_attempt") == marker }
            delivered
        }
        scenario.onActivity { it.intent = Intent(launchIntent) }
        compose.waitForIdle()
    }

    private fun click(tag: String) {
        awaitTag(tag)
        val node = compose.onNodeWithTag(tag)
        if (generateSequence(node.fetchSemanticsNode().parent) { it.parent }.any { it.config.contains(SemanticsActions.ScrollBy) }) node.performScrollTo()
        node.assertIsDisplayed().assertHeightIsAtLeast(48.dp).performClick()
    }

    private fun awaitTag(tag: String) = await("semantic node $tag") {
        compose.onAllNodes(hasTestTag(tag)).fetchSemanticsNodes().isNotEmpty()
    }

    private fun await(description: String, timeout: Long = 15_000, predicate: () -> Boolean) {
        try { compose.waitUntil(timeout, predicate) }
        catch (failure: ComposeTimeoutException) {
            throw AssertionError("$description; ambient=${ambient.state.value}; archive=${runtime.recordings.state.value?.items?.size}; " +
                "uploads=${server.ambient.uploadIds}; responses=${server.responses.takeLast(15)}; fixtureFailures=${server.failures}", failure)
        }
    }

    private fun assertFixtureHealthy() { assertTrue(server.failures.joinToString(), server.failures.isEmpty()) }
    private fun shell(command: String) {
        ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(command)).use { it.readBytes() }
    }
}
