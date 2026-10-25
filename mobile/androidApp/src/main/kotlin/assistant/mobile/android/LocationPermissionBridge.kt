package assistant.mobile.android

import kotlinx.coroutines.CompletableDeferred

/** Only a visible Activity may launch system dialogs, following an explicit settings action. */
class LocationPermissionBridge {
    private var owner: Any? = null
    private var foreground: (() -> Unit)? = null
    private var background: (() -> Unit)? = null
    private var rationale: (() -> Boolean)? = null
    private var foregroundAnswer: CompletableDeferred<Boolean>? = null
    private var backgroundAnswer: CompletableDeferred<Boolean>? = null

    fun attach(owner: Any, foreground: () -> Unit, background: () -> Unit, rationale: () -> Boolean) {
        this.owner = owner
        this.foreground = foreground
        this.background = background
        this.rationale = rationale
    }

    fun detach(owner: Any) {
        if (this.owner !== owner) return
        this.owner = null
        foreground = null
        background = null
        rationale = null
        foregroundResult(false)
        backgroundResult(false)
    }

    fun showRationale(): Boolean = rationale?.invoke() ?: false
    fun foregroundResult(granted: Boolean) { foregroundAnswer?.complete(granted); foregroundAnswer = null }
    fun backgroundResult(granted: Boolean) { backgroundAnswer?.complete(granted); backgroundAnswer = null }

    suspend fun requestForeground(): Boolean {
        val launch = foreground ?: return false
        if (foregroundAnswer != null) return false
        val answer = CompletableDeferred<Boolean>()
        foregroundAnswer = answer
        return try { launch(); answer.await() } finally {
            if (foregroundAnswer === answer) foregroundAnswer = null
        }
    }

    suspend fun requestBackground(): Boolean {
        val launch = background ?: return false
        if (backgroundAnswer != null) return false
        val answer = CompletableDeferred<Boolean>()
        backgroundAnswer = answer
        return try { launch(); answer.await() } finally {
            if (backgroundAnswer === answer) backgroundAnswer = null
        }
    }
}
