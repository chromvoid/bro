package assistant.mobile.android

import android.app.AlarmManager
import android.app.Application
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import androidx.work.WorkManager
import assistant.mobile.platform.location.LegacyLocationRegistration
import java.io.File
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/** Upgrade bookkeeping contains no cookies or collector credential. Old private stores stay in place. */
internal object LegacyShellUpgrade {
    private const val PREFERENCES = "native-shell-upgrade"
    private val json = Json { ignoreUnknownKeys = true }

    @Serializable
    private data class Reminder(val key: String, val id: Int, val fireAt: Long,
        val title: String, val body: String, val link: String)

    fun prepare(context: Context) {
        val status = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
        if (status.getBoolean("prepared", false)) return
        val notifications = context.getSharedPreferences("shell-notifications", Context.MODE_PRIVATE)
        val hadLegacyData = context.getDatabasePath("ring-v1.sqlite").exists() ||
            context.getDatabasePath("whoop-v1.sqlite").exists() ||
            context.getDatabasePath("location-v1.sqlite").exists() ||
            File(context.filesDir, "ambient-audio").exists() || notifications.contains("session")
        // Alarm payloads and their old owner remain readable as evidence, but the old login's
        // alarms must never be delivered under the new native login. The fresh native schedule
        // has its own typed binding and must be explicitly enabled after signing in.
        val storedInstalled = notifications.all["installed"]
        val saved = storedInstalled as? String
        val parsed = saved?.let { runCatching { json.decodeFromString(ListSerializer(Reminder.serializer()), it) } }
        // Unknown/corrupt legacy metadata is evidence too: retain it verbatim. Removed receiver
        // components cannot deliver those alarms, and the old binding is still explicitly closed.
        val installed = parsed?.getOrNull().orEmpty()
        val alarms = context.getSystemService(AlarmManager::class.java)
        val manager = context.getSystemService(NotificationManager::class.java)
        for (reminder in installed) {
            val intent = Intent().setComponent(ComponentName(context.packageName, "assistant.shell.notify.NotifyReceiver"))
                .setAction("assistant.shell.notify.FIRE")
            PendingIntent.getBroadcast(context, reminder.id, intent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_NO_CREATE)?.let {
                alarms.cancel(it)
                it.cancel()
            }
            manager.cancel(reminder.id)
        }
        check(notifications.edit().putBoolean("enabled", false).commit())
        // The retired workers cannot run (their classes were removed); stop their OS work too.
        // Location's old parent-bound grant and encrypted points remain in location-v1.sqlite.
        LegacyLocationRegistration.close(context)
        WorkManager.getInstance(context).cancelUniqueWork("location-upload")
        WorkManager.getInstance(context).cancelUniqueWork("location-upload-periodic")
        check(status.edit().putBoolean("prepared", true).putBoolean("has_legacy_data", hadLegacyData)
            .putString("location_queue", if (context.getDatabasePath("location-v1.sqlite").exists())
                "closed_preserved_reenrollment_required" else "absent")
            .putString("notifications", when {
                parsed?.isFailure == true || storedInstalled != null && saved == null -> "closed_unreadable_preserved"
                notifications.contains("session") -> "closed_preserved"
                else -> "absent"
            })
            .commit())
    }

    fun needsAcknowledgement(context: Context): Boolean = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
        .let { it.getBoolean("has_legacy_data", false) && !it.getBoolean("acknowledged", false) }

    fun acknowledge(context: Context) {
        check(context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).edit()
            .putBoolean("acknowledged", true).commit())
    }
}

class NativeApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        if (NativeServerRestart.isRestartProcess(this)) return
        if (BuildConfig.PLAY_STORE) WorkManager.getInstance(this).cancelAllWorkByTag("assistant.ring")
        LegacyShellUpgrade.prepare(this)
        NativeOrigins.checkPending(this)
    }
}
