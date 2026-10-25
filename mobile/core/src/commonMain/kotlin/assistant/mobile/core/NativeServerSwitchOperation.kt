package assistant.mobile.core

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

sealed interface NativeServerSwitchResult {
    data object NoOp : NativeServerSwitchResult
    data object FirmwareUpdateActive : NativeServerSwitchResult
    data object EraseFailed : NativeServerSwitchResult
    data object Restarting : NativeServerSwitchResult
}

sealed interface NativeServerSwitchPreparation {
    data object FirmwareUpdateActive : NativeServerSwitchPreparation
    /** Local erasure returns captured revocations addressed to their original servers. */
    data class Ready(val clear: suspend () -> List<suspend () -> Unit>) : NativeServerSwitchPreparation
}

/** One active server; local erasure is mandatory and remote revocation is bounded best effort. */
class NativeServerSwitchOperation(
    private val currentOrigin: () -> String,
    private val prepare: () -> NativeServerSwitchPreparation,
    private val select: (NativeServerCheckResult.Compatible) -> Unit,
    private val restart: (NativeServerCheckResult.Compatible, AuthLink?) -> Unit,
) {
    private val mutex = Mutex()
    private var pendingPreparation: NativeServerSwitchPreparation.Ready? = null

    suspend fun switchServer(
        target: NativeServerCheckResult.Compatible,
        invite: AuthLink? = null,
    ): NativeServerSwitchResult = mutex.withLock {
        if (target.origin == currentOrigin()) return@withLock NativeServerSwitchResult.NoOp
        // Erasure may dispose the caller's UI scope; selecting and restarting must still finish.
        withContext(NonCancellable) {
            val revocations = try {
                val preparation = pendingPreparation ?: when (val value = prepare()) {
                    NativeServerSwitchPreparation.FirmwareUpdateActive ->
                        return@withContext NativeServerSwitchResult.FirmwareUpdateActive
                    is NativeServerSwitchPreparation.Ready -> value.also { pendingPreparation = it }
                }
                preparation.clear()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                return@withContext NativeServerSwitchResult.EraseFailed
            }
            withTimeoutOrNull(10_000L) {
                coroutineScope {
                    for (revoke in revocations) launch {
                        try { revoke() } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (_: Exception) { }
                    }
                }
            }
            select(target)
            restart(target, invite)
            NativeServerSwitchResult.Restarting
        }
    }
}
