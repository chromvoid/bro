package assistant.mobile.android

import assistant.mobile.core.dictation.DictationPermissionResult
import kotlinx.coroutines.CompletableDeferred

/** The launcher belongs to the visible scene; no Activity is retained by the runtime. */
class DictationPermissionBridge {
    private var launch: (() -> Unit)? = null
    private var owner: Any? = null
    private var pending: CompletableDeferred<DictationPermissionResult>? = null
    private var launched: CompletableDeferred<DictationPermissionResult>? = null

    fun attach(owner: Any, request: () -> Unit) { this.owner = owner; launch = request }

    fun detach(owner: Any) {
        if (this.owner !== owner) return
        this.owner = null
        launch = null
        pending?.complete(DictationPermissionResult.NotAnswered)
        pending = null
    }

    fun result(granted: Boolean) = result(if (granted) DictationPermissionResult.Granted else DictationPermissionResult.Blocked)

    fun result(result: DictationPermissionResult) {
        val answered = launched ?: return
        launched = null
        if (pending === answered) {
            pending = null
            answered.complete(result)
        }
        launchPending()
    }

    suspend fun request(): Boolean = requestResult() == DictationPermissionResult.Granted

    suspend fun requestResult(): DictationPermissionResult {
        if (launch == null) return DictationPermissionResult.NotAnswered
        val answer = CompletableDeferred<DictationPermissionResult>()
        pending?.complete(DictationPermissionResult.NotAnswered)
        pending = answer
        return try {
            launchPending()
            answer.await()
        } finally {
            if (pending === answer) pending = null
        }
    }

    private fun launchPending() {
        val request = launch ?: return
        val answer = pending ?: return
        // Android does not identify the session in its result callback. Keep a cancelled
        // launch until that callback arrives, so it cannot answer the next session.
        if (launched != null) return
        launched = answer
        try {
            request()
        } catch (failure: Exception) {
            if (launched === answer) launched = null
            if (pending === answer) pending = null
            answer.completeExceptionally(failure)
        }
    }
}

/** Android can dismiss a prompt without recording a denial. */
internal fun classifyDictationPermission(
    granted: Boolean,
    before: Boolean,
    after: Boolean,
    deniedBefore: Boolean,
): DictationPermissionResult = when {
    granted -> DictationPermissionResult.Granted
    after -> DictationPermissionResult.NotAnswered
    before || deniedBefore -> DictationPermissionResult.Blocked
    else -> DictationPermissionResult.NotAnswered
}
