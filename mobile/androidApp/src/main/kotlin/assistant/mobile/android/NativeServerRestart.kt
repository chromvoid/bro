package assistant.mobile.android

import android.app.Activity
import android.app.ActivityManager
import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Process
import android.os.SystemClock
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import assistant.mobile.core.AuthLink
import assistant.mobile.core.canonicalOrigin
import assistant.mobile.core.parseAuthLink
import assistant.mobile.platform.AndroidNativeOrigins

/** Only the dispatcher process survives long enough to open the next process's home origin. */
internal object NativeServerRestart {
    private const val PROCESS_SUFFIX = ":nativeServerRestart"
    private const val EXTRA_PREVIOUS_PID = "assistant.native.restart.previous_pid"
    private const val EXTRA_ORIGIN = "assistant.native.restart.origin"
    private const val EXTRA_KIND = "assistant.native.restart.kind"
    private const val EXTRA_TOKEN = "assistant.native.restart.token"
    private const val EXTRA_ACTIVITY = "assistant.native.restart.activity"
    @Volatile private var pendingInvite: Pair<String, AuthLink>? = null

    fun restart(context: Context, targetOrigin: String, invite: AuthLink?) =
        restart(context, targetOrigin, invite, MainActivity::class.java)

    /** The debug process smoke supplies its MainActivity subclass so instrumentation survives. */
    internal fun restart(
        context: Context,
        targetOrigin: String,
        invite: AuthLink?,
        applicationActivity: Class<out MainActivity>,
    ) {
        require(BuildConfig.DEBUG || applicationActivity == MainActivity::class.java)
        pendingInvite = invite?.let { targetOrigin to it }
        val launch = Intent(context, NativeServerRestartActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            .putExtra(EXTRA_PREVIOUS_PID, Process.myPid())
            .putExtra(EXTRA_ACTIVITY, applicationActivity.name)
        putInvite(launch, targetOrigin, invite)
        context.startActivity(launch)
    }

    /** Retry only after selection was committed, keeping a denied launch's invite in memory. */
    internal fun resumeSelectedServer(
        context: Context,
        incoming: Pair<Uri, AuthLink?>? = null,
        restartApplication: (Context, String, AuthLink?) -> Unit = ::restart,
    ): Boolean {
        val target = AndroidNativeOrigins.pendingRestartOrigin(context)
        if (target == null) {
            pendingInvite = null
            return false
        }
        val incomingOrigin = incoming?.first?.let { uri ->
            if (uri.userInfo != null || uri.path != "/login" || uri.query != null) null else runCatching {
                canonicalOrigin("${uri.scheme}://${uri.encodedAuthority}", allowInsecureLoopback = BuildConfig.DEBUG)
            }.getOrNull()
        }
        val invite = pendingInvite?.takeIf { it.first == target }?.second
            ?: incoming?.second?.takeIf { incomingOrigin == target }
        pendingInvite = invite?.let { target to it }
        restartApplication(context, target, invite)
        return true
    }

    fun isRestartProcess(context: Context): Boolean {
        val name = if (Build.VERSION.SDK_INT >= 28) Application.getProcessName() else {
            context.getSystemService(ActivityManager::class.java).runningAppProcesses
                ?.firstOrNull { it.pid == Process.myPid() }?.processName
        }
        return name == context.packageName + PROCESS_SUFFIX
    }

    internal fun previousPid(source: Intent?): Int = source?.getIntExtra(EXTRA_PREVIOUS_PID, -1) ?: -1

    /** The returned intent is clean: the token travels through the disposable auth dispatcher. */
    internal fun applicationIntent(context: Context, source: Intent?): Intent {
        val requested = source?.getStringExtra(EXTRA_ACTIVITY)
        source?.removeExtra(EXTRA_ACTIVITY)
        val activity = if (BuildConfig.DEBUG && requested != null) {
            runCatching { Class.forName(requested).asSubclass(MainActivity::class.java) }.getOrNull()
                ?: MainActivity::class.java
        } else MainActivity::class.java
        return Intent(context, activity)
    }

    internal fun consumeInvite(source: Intent?): Pair<Uri, AuthLink?>? {
        val origin = source?.getStringExtra(EXTRA_ORIGIN)
        val kind = source?.getStringExtra(EXTRA_KIND)
        val token = source?.getStringExtra(EXTRA_TOKEN)
        source?.removeExtra(EXTRA_ORIGIN)
        source?.removeExtra(EXTRA_KIND)
        source?.removeExtra(EXTRA_TOKEN)
        if (origin == null || kind == null || token == null) return null
        val canonical = runCatching { canonicalOrigin(origin, allowInsecureLoopback = BuildConfig.DEBUG) }
            .getOrNull() ?: return null
        val fragment = Uri.Builder().appendQueryParameter("kind", kind)
            .appendQueryParameter("token", token).build().encodedQuery ?: return null
        val link = parseAuthLink(fragment) ?: return null
        return Uri.parse("$canonical/login") to link
    }

    internal fun destination(context: Context, application: Intent, invite: Pair<Uri, AuthLink?>?): Intent {
        if (invite?.second == null) return application.setAction(Intent.ACTION_MAIN)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        val launch = Intent(context, AuthLinkActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            .putExtra(EXTRA_ACTIVITY, application.component?.className)
        val uri = invite.first
        putInvite(launch, "${uri.scheme}://${uri.encodedAuthority}", invite.second)
        return launch
    }

    private fun putInvite(destination: Intent, targetOrigin: String, invite: AuthLink?) {
        if (invite == null) return
        destination.putExtra(EXTRA_ORIGIN, targetOrigin)
            .putExtra(EXTRA_KIND, invite.kind.name.lowercase())
            .putExtra(EXTRA_TOKEN, invite.tokenForProcessTransfer())
    }
}

/** No layout, saved state or application runtime is created in this short-lived process. */
class NativeServerRestartActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        val previousPid = NativeServerRestart.previousPid(intent)
        val application = NativeServerRestart.applicationIntent(this, intent)
        val invite = NativeServerRestart.consumeInvite(intent)
        intent?.replaceExtras(null as Bundle?)
        setIntent(Intent())
        super.onCreate(null)
        try {
            if (previousPid <= 0 || previousPid == Process.myPid()) return
            Process.killProcess(previousPid)
            val deadline = SystemClock.elapsedRealtime() + 10_000
            while (alive(previousPid) && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(10)
            // Attaching MainActivity to the surviving old process would preserve its frozen home.
            check(!alive(previousPid)) { "The previous application process did not exit" }
            startActivity(NativeServerRestart.destination(this, application, invite))
        } finally {
            finishAndRemoveTask()
            Process.killProcess(Process.myPid())
        }
    }

    private fun alive(pid: Int): Boolean = try {
        Os.kill(pid, 0)
        true
    } catch (failure: ErrnoException) {
        if (failure.errno != OsConstants.ESRCH) throw failure
        false
    }
}
