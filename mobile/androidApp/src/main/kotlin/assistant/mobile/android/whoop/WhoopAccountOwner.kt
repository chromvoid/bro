package assistant.mobile.android.whoop

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject

/** Foreground identity is distinct from the separately authorized background binding. */
internal class WhoopAccountOwner(private val cancelForeground: () -> Unit) {
    data class Token(val user: String, val revision: Long)
    @Volatile var user: String? = null
        private set
    private var revision = 1L

    @Synchronized fun change(next: String?, reason: String, boundUser: () -> String?): Boolean {
        if (reason !in setOf("logout", "switch", "lapsed") || reason == "lapsed" && next != null)
            throw WhoopFailure("bad_payload", "bad_account_state")
        // Read the durable binding under the same monitor as commit; a pre-read may miss a just-issued grant.
        val bound = boundUser()
        if (next != user || reason == "logout") {
            revision++
            user = next
            cancelForeground()
        }
        return reason != "lapsed" && (next == null || bound != null && bound != next)
    }
    @Synchronized fun <T> invalidateBinding(clear: () -> T): T {
        revision++
        cancelForeground()
        return clear()
    }
    @Synchronized fun capture(): Token = Token(user ?: throw WhoopFailure("not_authenticated", "not_authenticated"), revision)
    @Synchronized fun requireCurrent(token: Token) {
        if (token.user != user || token.revision != revision) throw CancellationException("WHOOP account changed")
    }
    @Synchronized private fun commit(token: Token, persist: () -> Unit) {
        requireCurrent(token)
        persist() // The account cannot change between this fence and durable binding writes.
    }

    /** A minted secret is captured before an HTTP response can be discarded by prompt cancellation. */
    suspend fun enroll(
        owner: Token,
        existing: WhoopGrant?,
        issue: suspend (checkpoint: () -> Unit, issued: (WhoopGrant) -> Unit) -> WhoopGrant,
        state: suspend (WhoopGrant, checkpoint: () -> Unit) -> JsonObject,
        persist: (WhoopGrant, JsonObject) -> Unit,
        revoke: suspend (WhoopGrant) -> Unit,
    ) {
        var minted: WhoopGrant? = null
        var committed = false
        val checkpoint = { requireCurrent(owner) }
        try {
            checkpoint()
            val grant = existing ?: issue(checkpoint) { issued -> minted = issued; checkpoint() }
            checkpoint()
            if (grant.user != owner.user) throw WhoopFailure("invalid_binding", "invalid_binding")
            val remote = state(grant, checkpoint)
            checkpoint()
            commit(owner) { persist(grant, remote); committed = true }
        } finally {
            val unused = minted
            if (!committed && unused != null) withContext(NonCancellable) {
                try { revoke(unused) } catch (_: Exception) { /* Lost session also revokes the server binding. */ }
            }
        }
    }
}

/** Required binding cleanup must survive a later foreground login or request. */
internal class WhoopTaskLifetime(private val scope: CoroutineScope) {
    @Volatile private var foreground: Job? = null
    @Volatile private var cleanup: Job? = null
    val busy: Boolean get() = foreground?.isActive == true || cleanup?.isCompleted == false
    @Synchronized fun cancelForeground() { foreground?.cancel() }
    @Synchronized fun launchForeground(block: suspend CoroutineScope.() -> Unit) {
        if (busy) throw WhoopFailure("busy", "busy")
        foreground = scope.launch(block = block)
    }
    @Synchronized fun launchCleanup(block: suspend CoroutineScope.() -> Unit): Job {
        val previous = cleanup
        // A later forget may commit a new durable fence while the preceding HTTP revoke is still running.
        // Keep every required clear, with foreground work blocked until the complete chain finishes.
        return scope.launch { previous?.join(); block() }.also { cleanup = it }
    }
}
