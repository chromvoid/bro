package assistant.mobile.android

import android.os.ParcelFileDescriptor
import android.os.Build
import android.os.SystemClock
import android.graphics.Bitmap
import android.view.accessibility.AccessibilityNodeInfo
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.io.IOException

/**
 * Saves the whole emulator screen, with system bars, sheets, menus and the keyboard, for a person
 * to review the look. tools/android_ci.py copies the files into build/reports/native-ci/screens,
 * which the Android lane uploads. Nothing asserts on them; a failed capture never fails a test.
 */
internal fun ComposeTestRule.captureScreen(name: String) {
    require(name.isNotEmpty() && name.all { it.isLowerCase() || it.isDigit() || it == '-' })
    waitForIdle()
    val instrumentation = InstrumentationRegistry.getInstrumentation()
    instrumentation.waitForIdleSync()
    val screen = instrumentation.uiAutomation.takeScreenshot() ?: return
    try {
        val directory = File(instrumentation.targetContext.filesDir, "native-ci-screens").apply { mkdirs() }
        File(directory, "$name.png").outputStream().use { screen.compress(Bitmap.CompressFormat.PNG, 100, it) }
    } catch (ignored: IOException) {
        // Review evidence only: a full or missing files directory leaves the test's verdict alone.
    } finally {
        screen.recycle()
    }
}

/** Render store evidence at a phone resolution on the isolated CI emulator. */
internal fun configureStoreCapture(enabled: Boolean) {
    val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
    for (command in if (enabled) listOf("wm size 1080x2160", "wm density 440")
        else listOf("wm size reset", "wm density reset")) {
        ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand(command)).use { it.readBytes() }
    }
}

/** Owner-approved Play permission evidence, recorded only on the disposable CI emulator. */
internal fun recordPermissionVideo(name: String, action: () -> Unit) {
    require(name.isNotEmpty() && name.all { it.isLowerCase() || it.isDigit() || it == '-' })
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    val temporary = "/data/local/tmp/$name.mp4"
    val pid = permissionVideoShell("screenrecord --bit-rate 2000000 --time-limit 120 $temporary >/dev/null 2>&1 & echo \$!").trim().toInt()
    SystemClock.sleep(1_000)
    var actionFailure: Throwable? = null
    try {
        action()
    } catch (failure: Throwable) {
        actionFailure = failure
        throw failure
    } finally {
        try {
            permissionVideoShell("kill -2 $pid")
            SystemClock.sleep(2_000)
            val directory = File(context.filesDir, "native-ci-screens").apply { mkdirs() }
            val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
            val bytes = ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand("cat $temporary")).use { input ->
                File(directory, "$name.mp4").outputStream().use { input.copyTo(it) }
            }
            permissionVideoShell("rm $temporary")
            check(bytes > 0) { "Permission video was not recorded" }
        } catch (failure: Throwable) {
            if (actionFailure == null) throw failure
            actionFailure.addSuppressed(failure)
        }
    }
}

internal fun permissionVideoShell(command: String): String {
    if (Build.VERSION.SDK_INT < 31) error("Permission videos require the CI Android emulator")
    val pipes = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommandRw("sh")
    ParcelFileDescriptor.AutoCloseOutputStream(pipes[1]).use { it.write((command + "\n").toByteArray()) }
    return ParcelFileDescriptor.AutoCloseInputStream(pipes[0]).bufferedReader().use { it.readText() }
}

/** Android settings and notification controls are outside the Compose semantics tree. */
internal fun clickPermissionSystemText(text: String) {
    val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
    val labels = mutableListOf<String>()
    fun find(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        node.text?.toString()?.takeIf(String::isNotBlank)?.let { labels += it.take(120) }
        if (node.text?.toString()?.equals(text, ignoreCase = true) == true ||
            node.contentDescription?.toString()?.equals(text, ignoreCase = true) == true) return node
        for (index in 0 until node.childCount) {
            node.getChild(index)?.let { child -> find(child)?.let { return it } }
        }
        return null
    }
    val deadline = SystemClock.uptimeMillis() + 15_000
    while (SystemClock.uptimeMillis() < deadline) {
        if (Build.VERSION.SDK_INT >= 33) automation.clearCache()
        labels.clear()
        // Compose virtual nodes are traversable even when provider-side text search is absent.
        var node = automation.rootInActiveWindow?.let(::find)
        while (node != null && !node.isClickable) node = node.parent
        if (node?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true) {
            automation.waitForIdle(500, 5_000)
            return
        }
        SystemClock.sleep(200)
    }
    error("Android control was not found: $text; visible labels: ${labels.take(40)}")
}
