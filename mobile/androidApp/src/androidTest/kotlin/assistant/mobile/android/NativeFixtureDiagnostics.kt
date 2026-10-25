package assistant.mobile.android

import android.app.Activity
import android.app.Application
import android.os.Build
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.view.View
import android.view.ViewTreeObserver
import android.view.inspector.WindowInspector
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.platform.app.InstrumentationRegistry
import java.util.IdentityHashMap

/** Read-only, bounded per-test focus evidence retained directly in jni-smoke.txt. */
internal class NativeFixtureDiagnostics(
    private val application: Application,
    private val test: String,
) : Application.ActivityLifecycleCallbacks {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val started = SystemClock.elapsedRealtime()
    private val startedWallMillis = System.currentTimeMillis()
    private val events = ArrayDeque<String>()
    private val activities = IdentityHashMap<Activity, String>()
    private val observers = IdentityHashMap<Activity, FocusObserver>()

    init {
        instrumentation.runOnMainSync { application.registerActivityLifecycleCallbacks(this) }
    }

    /** Called before teardown changes font scale, logs out, or disposes any windows. */
    fun finish(runtime: NativeRuntime?, requests: List<NativeLoopbackServer.Request>, fixtureFailures: Int,
        responses: List<String> = emptyList(), screenTags: List<String> = emptyList()) {
        try {
            val lines = mutableListOf("\nNativeFixtureDiagnostics $test")
            lines += "screens=$screenTags"
            instrumentation.runOnMainSync {
                lines += events.map { "event $it" }
                activities.entries.take(MAX_ACTIVITIES).forEach { (activity, lifecycle) ->
                    val decor = activity.window.decorView
                    val insets = ViewCompat.getRootWindowInsets(decor)
                    lines += "window ${activityId(activity)} lifecycle=$lifecycle attached=${decor.isAttachedToWindow} " +
                        "focus=${decor.hasWindowFocus()} focused=${viewId(activity.currentFocus)} " +
                        "imeVisible=${insets?.isVisible(WindowInsetsCompat.Type.ime())} " +
                        "imeBottom=${insets?.getInsets(WindowInsetsCompat.Type.ime())?.bottom}"
                }
                if (Build.VERSION.SDK_INT >= 29) WindowInspector.getGlobalWindowViews().take(MAX_WINDOW_ROOTS).forEachIndexed { index, root ->
                    val focused = root.findFocus()
                    val insets = ViewCompat.getRootWindowInsets(root)
                    lines += "root[$index] ${viewId(root)} attached=${root.isAttachedToWindow} " +
                        "windowFocus=${root.hasWindowFocus()} focused=${viewId(focused)} " +
                        "imeVisible=${insets?.isVisible(WindowInsetsCompat.Type.ime())} " +
                        "imeBottom=${insets?.getInsets(WindowInsetsCompat.Type.ime())?.bottom}"
                }
            }
            val personal = runtime?.personal?.state?.value
            lines += "personal present=${personal != null} canRead=${personal?.canRead} busy=${personal?.busy} " +
                "conflict=${personal?.conflict} error=${personal?.error != null} " +
                "overviewLoading=${personal?.overview?.loading} overviewStale=${personal?.overview?.stale} " +
                "resolvedLoading=${personal?.resolved?.loading} derivedLoading=${personal?.derivedScores?.loading} " +
                "caliperLoading=${personal?.caliper?.loading} nutritionLoading=${personal?.nutritionDay?.loading} " +
                "pending=${personal?.pending?.size ?: 0}"
            val sessions = runtime?.core?.sessions
            val auth = runtime?.auth?.state?.value
            lines += "auth busy=${auth?.busy} signingOut=${auth?.signingOut} signedIn=${auth?.signedIn} " +
                "mode=${auth?.mode} error=${auth?.error?.key?.webKey} lease=${sessions?.lease() != null} " +
                "identity=${sessions?.identity?.value != null} events=${runtime?.core?.events?.state?.value}"
            val today = runtime?.today?.state?.value
            val food = today?.food
            lines += "today present=${today != null} currentAccount=${today?.namespace == sessions?.lease()?.namespace} " +
                "foodQuery=${food != null} foodData=${food?.data != null} foodEmpty=${food?.data?.empty} " +
                "foodEnergy=${food?.data?.energyKcal != null} foodLoading=${food?.loading} foodStale=${food?.stale} " +
                "foodError=${food?.error?.key?.webKey}"
            lines += "responses relevant=" + responses.filter { response ->
                DIAGNOSTIC_ROUTES.any { route -> " $route " in response }
            }.takeLast(48)
            val routeCounts = PERSONAL_ROUTES.associateWith { route -> requests.count { request ->
                request.path.substringBefore('?') == route
            } }
            lines += "requests total=${requests.size} personal=$routeCounts " +
                "personalLookups=${requests.count { it.path.startsWith("/v1/personal/operations/by-key/") }} " +
                "fixtureFailures=$fixtureFailures"
            lines += recentInputLogs().map { "logcat $it" }
            emit(lines.joinToString("\n") + "\n")
        } catch (failure: Exception) {
            emit("\nNativeFixtureDiagnostics $test unavailable=${failure.javaClass.simpleName}\n")
        } finally {
            instrumentation.runOnMainSync {
                application.unregisterActivityLifecycleCallbacks(this)
                observers.values.toList().forEach { it.close() }
                observers.clear()
                activities.clear()
            }
        }
    }

    private fun recentInputLogs(): List<String> {
        // UiAutomation executes arguments directly, without a shell. Filter numeric timestamps
        // here and retain the last matching lines; -d exits after the current buffer is read.
        val command = "logcat -d -v epoch Choreographer:I InputMethodManager:I ImeTracker:I *:S"
        val descriptor = instrumentation.uiAutomation.executeShellCommand(command)
        return ParcelFileDescriptor.AutoCloseInputStream(descriptor).bufferedReader().use { reader ->
            val recent = ArrayDeque<String>()
            reader.forEachLine { line ->
                val epochSeconds = line.trimStart().substringBefore(' ').toDoubleOrNull()
                if (epochSeconds != null && epochSeconds * 1000 >= startedWallMillis) {
                    if (recent.size == MAX_LOG_LINES) recent.removeFirst()
                    recent.addLast(line.take(MAX_LOG_LINE_CHARS))
                }
            }
            recent.toList()
        }
    }

    private fun emit(stream: String) {
        instrumentation.sendStatus(2, Bundle().apply { putString("stream", stream.take(MAX_OUTPUT_CHARS)) })
    }

    private fun record(event: String) {
        if (events.size == MAX_EVENTS) events.removeFirst()
        events.addLast("+${SystemClock.elapsedRealtime() - started}ms $event")
    }

    private fun lifecycle(activity: Activity, state: String) {
        if (activity !is MainActivity && activity !is AuthLinkActivity) return
        if (activities[activity] != state) record("${activityId(activity)} lifecycle=$state")
        activities[activity] = state
        if (state == "destroyed") observers.remove(activity)?.close()
        else if (!observers.containsKey(activity)) observers[activity] = FocusObserver(activity)
    }

    private inner class FocusObserver(activity: Activity) {
        private val name = activityId(activity)
        private val tree = activity.window.decorView.viewTreeObserver
        private val window = ViewTreeObserver.OnWindowFocusChangeListener { focused -> record("$name windowFocus=$focused") }
        private val focus = ViewTreeObserver.OnGlobalFocusChangeListener { old, new ->
            val before = viewId(old)
            val after = viewId(new)
            if (before != after) record("$name focused=$before->$after")
        }

        init {
            tree.addOnWindowFocusChangeListener(window)
            tree.addOnGlobalFocusChangeListener(focus)
        }

        fun close() {
            if (tree.isAlive) {
                tree.removeOnWindowFocusChangeListener(window)
                tree.removeOnGlobalFocusChangeListener(focus)
            }
        }
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = lifecycle(activity, "created")
    override fun onActivityStarted(activity: Activity) = lifecycle(activity, "started")
    override fun onActivityResumed(activity: Activity) = lifecycle(activity, "resumed")
    override fun onActivityPaused(activity: Activity) = lifecycle(activity, "paused")
    override fun onActivityStopped(activity: Activity) = lifecycle(activity, "stopped")
    override fun onActivityDestroyed(activity: Activity) = lifecycle(activity, "destroyed")
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit

    private fun activityId(activity: Activity): String = "${activity.javaClass.simpleName}@${System.identityHashCode(activity)}"
    private fun viewId(view: View?): String = view?.let {
        "${it.javaClass.simpleName}@${System.identityHashCode(it)}(id=${it.id},focused=${it.isFocused})"
    } ?: "none"

    companion object {
        private const val MAX_EVENTS = 16
        private const val MAX_ACTIVITIES = 8
        private const val MAX_WINDOW_ROOTS = 8
        private const val MAX_LOG_LINES = 24
        private const val MAX_LOG_LINE_CHARS = 300
        private const val MAX_OUTPUT_CHARS = 12_000
        private val PERSONAL_ROUTES = listOf("/v1/personal/overview", "/v1/personal/resolved", "/v1/personal/derived-scores",
            "/v1/personal/caliper", "/v1/personal/metrics/catalog", "/v1/personal/operations")
        private val DIAGNOSTIC_ROUTES = NativeLoopbackServer.TODAY_PATHS + setOf(
            "/v1/auth/native/login", "/v1/auth/native/logout", "/v1/me", "/v1/me/events")
    }
}
