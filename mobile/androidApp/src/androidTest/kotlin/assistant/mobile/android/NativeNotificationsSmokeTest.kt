package assistant.mobile.android

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationManager
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.ParcelFileDescriptor
import android.view.accessibility.AccessibilityNodeInfo
import androidx.compose.ui.test.ComposeTimeoutException
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsOn
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
import assistant.mobile.android.notify.AndroidNotifications
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

/** Real permission controller, AlarmManager, notifications and typed navigation; HTTP is loopback. */
@RunWith(AndroidJUnit4::class)
class NativeNotificationsSmokeTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val delivery get() = AndroidNotifications.get(context)
    private val notifications get() = context.getSystemService(NotificationManager::class.java)
    private lateinit var server: NativeLoopbackServer
    private lateinit var scenario: ActivityScenario<MainActivity>
    private lateinit var runtime: NativeRuntime

    @Before fun launchIsolatedNotificationsClient() {
        context.getSharedPreferences("appearance", 0).edit().clear().commit()
        delivery.clear()
        delivery.asked = false
        notifications.cancelAll()
        // CI installs this APK freshly without -g; clearing prompt flags does not revoke permission.
        clearPermissionFlags()
        if (Build.VERSION.SDK_INT >= 31) {
            shell("appops set ${context.packageName} SCHEDULE_EXACT_ALARM allow")
            assertTrue("Exact-alarm setup failed", context.getSystemService(AlarmManager::class.java).canScheduleExactAlarms())
        }
        server = NativeLoopbackServer().apply {
            notificationsEnabled = true
            tasksEnabled = true
            timersEnabled = true
        }
        val instance = "notifications-${UUID.randomUUID()}"
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

    @After fun releaseNotificationsClient() {
        try {
            if (::runtime.isInitialized) runBlocking {
                if (runtime.core.sessions.lease() != null) runtime.core.sessions.logout()
            }
        } finally {
            delivery.clear()
            notifications.cancelAll()
            if (::scenario.isInitialized) scenario.close()
            NativeRuntimeFactory.testFactory = null
            if (::server.isInitialized) server.close()
            context.getSharedPreferences("appearance", 0).edit().clear().commit()
        }
    }

    @Test fun bPermissionIsRequestedOnlyByExplicitEnableAndGrantEnablesDelivery() {
        login()
        assertPermissionNotRequested()
        openSettings()
        assertPermissionNotRequested()
        click("notifications_device_toggle")
        clickPermission("permission_allow_button")
        compose.waitUntil(10_000) { context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED }
        awaitTag("notifications_device_toggle")
        compose.onNodeWithTag("notifications_device_toggle").performScrollTo().assertIsOn()
        assertTrue(delivery.enabled)
        assertWritesHealthy()
    }

    private fun login() {
        compose.onNodeWithTag("login_login").performScrollTo().performTextReplacement("person")
        compose.onNodeWithTag("login_password").performScrollTo().performTextInput("Synthetic Password 123!")
        click("login_submit")
        awaitTag("screen_Today")
        compose.awaitNativeTabBar(::imeClosed)
        awaitTag("notifications_bell")
    }

    private fun openSettings() {
        click("profile_menu", scroll = false)
        click("open_Settings")
        awaitTag("screen_Settings")
        click("open_NotificationSettings")
        awaitTag("screen_NotificationSettings")
        awaitTag("notifications_settings_save")
    }

    private fun clearPermissionFlags() {
        if (Build.VERSION.SDK_INT < 33) return
        shell("pm clear-permission-flags ${context.packageName} android.permission.POST_NOTIFICATIONS user-set user-fixed")
    }

    private fun assertPermissionNotRequested() {
        assertFalse(delivery.asked)
        assertEquals(PackageManager.PERMISSION_DENIED, context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS))
        assertFalse(permissionNodes("permission_allow_button").isNotEmpty())
    }

    private fun clickPermission(id: String) {
        compose.waitUntil(10_000) { permissionNodes(id).isNotEmpty() }
        assertTrue(permissionNodes(id).first().performAction(AccessibilityNodeInfo.ACTION_CLICK))
        instrumentation.waitForIdleSync()
    }

    private fun permissionNodes(id: String): List<AccessibilityNodeInfo> {
        val root = instrumentation.uiAutomation.rootInActiveWindow ?: return emptyList()
        return root.findAccessibilityNodeInfosByViewId("com.android.permissioncontroller:id/$id") +
            root.findAccessibilityNodeInfosByViewId("com.google.android.permissioncontroller:id/$id")
    }

    private fun assertWritesHealthy() {
        assertTrue("Notification fixture failures: ${server.failures}", server.failures.isEmpty())
        server.requests.filter { it.path.startsWith("/v1/notifications") && it.method != "GET" }.forEach {
            assertEquals("synthetic-user-a", it.headers["x-expected-user"])
            assertEquals("Bearer ${NativeLoopbackServer.credential("synthetic-user-a")}", it.headers["authorization"])
            assertFalse(it.headers.containsKey("cookie"))
        }
    }

    private fun click(tag: String, scroll: Boolean = true) {
        awaitTag(tag)
        val node = compose.onNodeWithTag(tag)
        if (scroll) node.performScrollTo()
        node.assertHeightIsAtLeast(48.dp).performClick()
    }

    private fun awaitTag(tag: String, unmerged: Boolean = false) {
        try {
            compose.waitUntil(15_000) {
                if (server.failures.isNotEmpty()) throw AssertionError("Notification fixture failed: ${server.failures}")
                // A notification tap can replace the Activity's Compose root between polls.
                compose.onAllNodes(hasTestTag(tag), useUnmergedTree = unmerged)
                    .fetchSemanticsNodes(atLeastOneRootRequired = false).isNotEmpty()
            }
        } catch (error: ComposeTimeoutException) {
            throw AssertionError("Missing $tag; responses=${server.responses}; fixtureFailures=${server.failures}", error)
        }
    }

    private fun shell(command: String): String = ParcelFileDescriptor.AutoCloseInputStream(
        instrumentation.uiAutomation.executeShellCommand(command),
    ).use { it.readBytes().toString(Charsets.UTF_8) }
}
