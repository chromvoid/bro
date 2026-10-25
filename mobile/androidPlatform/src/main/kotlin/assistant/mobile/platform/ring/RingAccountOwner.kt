package assistant.mobile.platform.ring

import assistant.mobile.platform.R

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject

/** Foreground identity is distinct from the separately authorized background binding. */
class RingAccountOwner(private val cancelForeground: () -> Unit) {
    data class Token(val user: String, val revision: Long)
    @Volatile var user: String? = null
        private set
    private var revision = 1L

    @Synchronized fun change(next: String?, reason: String, boundUser: () -> String?): Boolean {
        if (reason !in setOf("logout", "switch", "lapsed") || reason == "lapsed" && next != null)
            throw RingFailure("bad_payload", RingText.text(R.string.ring_native_7de31f9d779e))
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
    /** Replaced native leases fence the previous bearer even for the same person. */
    @Synchronized fun invalidateForeground() {
        revision++
        cancelForeground()
    }
    @Synchronized fun capture(): Token = Token(user ?: throw RingFailure("not_authenticated", RingText.text(R.string.ring_native_a4bdc0b45a45)), revision)
    @Synchronized fun requireCurrent(token: Token) {
        if (token.user != user || token.revision != revision) throw CancellationException("Ring account changed")
    }
    @Synchronized private fun commit(token: Token, persist: () -> Unit) {
        requireCurrent(token)
        persist() // The account cannot change between this fence and durable binding writes.
    }

    /** A minted secret is captured before an HTTP response can be discarded by prompt cancellation. */
    suspend fun enroll(
        owner: Token,
        existing: RingGrant?,
        issue: suspend (checkpoint: () -> Unit, issued: (RingGrant) -> Unit) -> RingGrant,
        state: suspend (RingGrant, checkpoint: () -> Unit) -> JsonObject,
        persist: (RingGrant, JsonObject) -> Unit,
        revoke: suspend (RingGrant) -> Unit,
    ) {
        var minted: RingGrant? = null
        var committed = false
        val checkpoint = { requireCurrent(owner) }
        try {
            checkpoint()
            val grant = existing ?: issue(checkpoint) { issued -> minted = issued; checkpoint() }
            checkpoint()
            if (grant.user != owner.user) throw RingFailure("invalid_binding", RingText.text(R.string.ring_native_9454ad67d793))
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
class RingTaskLifetime(private val scope: CoroutineScope) {
    @Volatile private var foreground: Job? = null
    @Volatile private var cleanup: Job? = null
    val busy: Boolean get() = foreground?.isActive == true || cleanup?.isCompleted == false
    @Synchronized fun cancelForeground() { foreground?.cancel() }
    @Synchronized fun launchForeground(block: suspend CoroutineScope.() -> Unit) {
        if (busy) throw RingFailure("busy", RingText.text(R.string.ring_native_501dfba1a328))
        foreground = scope.launch(block = block)
    }
    @Synchronized fun launchCleanup(block: suspend CoroutineScope.() -> Unit): Job {
        val previous = cleanup
        // A later forget may commit a new durable fence while the preceding HTTP revoke is still running.
        // Keep every required clear, with foreground work blocked until the complete chain finishes.
        return scope.launch { previous?.join(); block() }.also { cleanup = it }
    }
}
