package assistant.mobile.android

import android.content.Context
import android.util.Log
import assistant.mobile.core.OriginMoveCoordinator
import assistant.mobile.platform.AndroidNativeOrigins
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** Shared by foreground runtimes and rate-limited startup/foreground verification attempts. */
internal object NativeOrigins {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun get(context: Context): OriginMoveCoordinator = AndroidNativeOrigins.get(context)

    fun checkPending(context: Context) {
        val origins = get(context)
        scope.launch {
            try {
                origins.checkPending()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // No decision was committed; a later foreground start can verify again.
                Log.w("NativeOrigins", "Origin move verification could not finish")
            }
        }
    }
}
