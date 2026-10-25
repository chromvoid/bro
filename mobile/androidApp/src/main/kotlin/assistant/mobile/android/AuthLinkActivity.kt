package assistant.mobile.android

import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import assistant.mobile.core.AuthLink
import assistant.mobile.core.canonicalOrigin
import assistant.mobile.core.parseAuthLink
import assistant.mobile.core.parseJoinLink
import java.util.concurrent.atomic.AtomicReference

/** No token-bearing intent becomes the retained application's task/history entry. */
class AuthLinkActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        val application = NativeServerRestart.applicationIntent(this, intent)
        val transferred = NativeServerRestart.consumeInvite(intent)
        val link = consume(intent)
        if (transferred != null) application.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK)
        super.onCreate(null)
        openApplication(transferred ?: link, application)
    }

    override fun onNewIntent(intent: Intent) {
        val application = NativeServerRestart.applicationIntent(this, intent)
        val transferred = NativeServerRestart.consumeInvite(intent)
        val link = consume(intent)
        if (transferred != null) application.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK)
        super.onNewIntent(intent)
        openApplication(transferred ?: link, application)
    }

    private fun consume(source: Intent?): Pair<Uri, AuthLink?>? {
        val defaultOrigin = requireNotNull(packageManager.getApplicationInfo(packageName,
            PackageManager.GET_META_DATA).metaData?.getString("assistant.origin"))
        return consumeAuthLinkIntent(source, defaultOrigin)
    }

    private fun openApplication(link: Pair<Uri, AuthLink?>?, application: Intent) {
        PendingAuthLink.value.set(link)
        startActivity(application.setAction(Intent.ACTION_MAIN)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
        // singleInstance gives this dispatcher its own task even when launched by a browser.
        // Remove that task's original framework intent as well as the local Activity.intent.
        finishAndRemoveTask()
    }
}

internal fun consumeAuthLinkIntent(source: Intent?, defaultOrigin: String): Pair<Uri, AuthLink?>? {
    val uri = source?.data
    source?.data = null
    source?.clipData = null
    source?.selector = null
    if (uri == null || uri.userInfo != null || uri.query != null) return null
    val origin = runCatching {
        canonicalOrigin("${uri.scheme}://${uri.encodedAuthority}", allowInsecureLoopback = BuildConfig.DEBUG)
    }.getOrNull() ?: return null
    val fragment = uri.encodedFragment ?: return null
    return when (uri.encodedPath) {
        "/login" -> parseAuthLink(fragment)?.let { Uri.parse("$origin/login") to it }
        "/join" -> {
            if (origin != canonicalOrigin(defaultOrigin, allowInsecureLoopback = BuildConfig.DEBUG)) return null
            parseJoinLink(fragment)?.let {
                // Preserve the verified join provenance without retaining the token-bearing URL.
                Uri.parse("${it.origin}/join") to it.link
            }
        }
        else -> null
    }
}

/** Process memory only; tokens never enter retained intents, saved state, navigation or durable state. */
internal object PendingAuthLink {
    val value = AtomicReference<Pair<Uri, AuthLink?>?>()
}
