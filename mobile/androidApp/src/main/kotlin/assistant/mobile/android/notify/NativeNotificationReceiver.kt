package assistant.mobile.android.notify

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** Alarm and boot work survives Activity absence; the native encrypted login remains its authority. */
class NativeNotificationReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        scope.launch {
            try {
                val notifications = AndroidNotifications.get(context)
                when (intent.action) {
                    AndroidNotifications.ACTION_FIRE -> intent.getStringExtra(AndroidNotifications.EXTRA_TOKEN)?.let { notifications.fire(it) }
                    AndroidNotifications.ACTION_EXPIRE -> notifications.expire()
                    Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED -> notifications.restore()
                }
            } finally { pending.finish() }
        }
    }

    private companion object { val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO) }
}
