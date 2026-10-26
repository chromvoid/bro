package assistant.mobile.android.notify

import android.Manifest
import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.core.content.ContextCompat
import assistant.mobile.android.MainActivity
import assistant.mobile.android.R
import assistant.mobile.core.notifications.InstalledNotificationAlarm
import assistant.mobile.core.notifications.NotificationAlarmBackend
import assistant.mobile.core.notifications.NotificationAlarmEngine
import assistant.mobile.core.notifications.NotificationAlarmPlatform
import assistant.mobile.core.notifications.NotificationAlarmSnapshot
import assistant.mobile.core.notifications.NotificationDeviceState
import assistant.mobile.core.notifications.NotificationOpen
import assistant.mobile.core.notifications.NotificationPermission
import assistant.mobile.core.notifications.NotificationSchedule
import assistant.mobile.core.notifications.NotificationScheduleAck
import assistant.mobile.core.notifications.NotificationSession
import assistant.mobile.platform.KeystoreSessionSecretStore
import assistant.mobile.design.R as DesignR
import kotlinx.serialization.json.Json
import kotlin.time.Instant

/** This package owns its own preferences, alarm intents and channel, independently of the shell. */
class AndroidNotifications private constructor(private val context: Context) : NotificationAlarmPlatform {
    private val preferences = context.getSharedPreferences("native-notifications", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }
    private val alarms = context.getSystemService(AlarmManager::class.java)
    private val manager = context.getSystemService(NotificationManager::class.java)
    private val lock = Any()
    private val engine = NotificationAlarmEngine(object : NotificationAlarmBackend {
        override fun save(snapshot: NotificationAlarmSnapshot): Boolean = preferences.edit()
            .putString(SNAPSHOT, json.encodeToString(NotificationAlarmSnapshot.serializer(), snapshot)).commit()

        override fun arm(alarm: InstalledNotificationAlarm): Boolean {
            val intent = pendingIntent(alarm, PendingIntent.FLAG_UPDATE_CURRENT) ?: return false
            return try {
                if (exactAlarmsAllowed()) alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, alarm.fireAt, intent)
                else alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, alarm.fireAt, intent)
                true
            } catch (_: SecurityException) {
                // The exact-alarm grant can change between the check and the OS call.
                try { alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, alarm.fireAt, intent); true }
                catch (_: RuntimeException) { intent.cancel(); false }
            } catch (_: RuntimeException) { intent.cancel(); false }
        }

        override fun cancel(alarm: InstalledNotificationAlarm) {
            pendingIntent(alarm)?.let { alarms.cancel(it); it.cancel() }
            manager.cancel(notificationTag(alarm.token), alarm.id)
        }

        override fun cancelDisplayed() {
            manager.activeNotifications.filter { it.tag?.startsWith(TAG_PREFIX) == true }
                .forEach { manager.cancel(it.tag, it.id) }
        }
    }, preferences.getString(SNAPSHOT, null)?.let {
        runCatching { json.decodeFromString<NotificationAlarmSnapshot>(it) }.getOrNull()
    } ?: NotificationAlarmSnapshot())

    var asked: Boolean
        get() = preferences.getBoolean(ASKED, false)
        set(value) { preferences.edit().putBoolean(ASKED, value).commit() }

    val enabled: Boolean
        get() = synchronized(lock) { engine.snapshot.enabled }

    val installed: List<InstalledNotificationAlarm>
        get() = synchronized(lock) { engine.snapshot.installed.toList() }

    fun granted(): Boolean = (Build.VERSION.SDK_INT < 33 ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) &&
        manager.areNotificationsEnabled()

    fun exactAlarmsAllowed(): Boolean = Build.VERSION.SDK_INT < 31 || alarms.canScheduleExactAlarms()

    override fun state(): NotificationDeviceState = synchronized(lock) {
        if (engine.snapshot.session?.active(System.currentTimeMillis()) == false) clear()
        val granted = granted()
        NotificationDeviceState(when {
            granted -> NotificationPermission.Granted
            asked || Build.VERSION.SDK_INT < 33 -> NotificationPermission.Denied
            else -> NotificationPermission.NotDetermined
        }, granted && engine.snapshot.enabled && engine.snapshot.session?.active(System.currentTimeMillis()) == true,
            exactAlarmsAllowed(), engine.snapshot.pendingAck != null)
    }

    override fun bind(session: NotificationSession) = synchronized(lock) {
        engine.bind(session)
        armExpiry(session)
    }

    override fun clear() {
        synchronized(lock) {
            engine.clear()
            expiryIntent(PendingIntent.FLAG_NO_CREATE)?.let { alarms.cancel(it); it.cancel() }
        }
    }

    override fun setEnabled(enabled: Boolean) = synchronized(lock) { engine.setEnabled(enabled) }

    override fun install(snapshot: NotificationSchedule): List<String> = synchronized(lock) {
        engine.replace(snapshot, System.currentTimeMillis(), granted())
    }

    override fun pendingAck(): NotificationScheduleAck? = synchronized(lock) { engine.snapshot.pendingAck }

    override fun acknowledged(snapshotId: String) = synchronized(lock) { engine.acknowledged(snapshotId) }

    /** Native encrypted session storage replaces the old shell's cookie verifier. */
    suspend fun restore() {
        val owner = synchronized(lock) { engine.snapshot.session }
        val verified = verifiedSession(owner)
        synchronized(lock) {
            // A different login may have bound while the encrypted record was being read.
            if (engine.snapshot.session != owner) return
            engine.restore(System.currentTimeMillis(), verified, granted())
            engine.snapshot.session?.let(::armExpiry)
        }
    }

    suspend fun fire(token: String) {
        val owner = synchronized(lock) { engine.snapshot.session }
        val verified = verifiedSession(owner)
        synchronized(lock) {
            if (engine.snapshot.session != owner) return
            val reminder = engine.consume(token, System.currentTimeMillis(), verified, granted()) ?: return
            val session = engine.snapshot.session ?: return
            manager.createNotificationChannel(NotificationChannel(CHANNEL,
                context.getString(DesignR.string.tasks_timers_title), NotificationManager.IMPORTANCE_HIGH))
            val open = PendingIntent.getActivity(context, reminder.id,
                Intent(context, MainActivity::class.java).setAction(ACTION_OPEN)
                    .setData(openUri(reminder.token)).putExtra(EXTRA_TOKEN, reminder.token)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            val neutral = Notification.Builder(context, CHANNEL).setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(context.getString(DesignR.string.app_title))
                .setContentText(context.getString(DesignR.string.tasks_reminder)).build()
            val notification = Notification.Builder(context, CHANNEL).setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(reminder.title).setContentText(reminder.body.ifEmpty { null })
                .setStyle(Notification.BigTextStyle().bigText(reminder.body)).setCategory(Notification.CATEGORY_REMINDER)
                .setVisibility(if (session.previews) Notification.VISIBILITY_PUBLIC else Notification.VISIBILITY_PRIVATE)
                .setPublicVersion(neutral).setContentIntent(open).setAutoCancel(true).build()
            try { manager.notify(notificationTag(reminder.token), reminder.id, notification) }
            catch (_: SecurityException) { /* A concurrent permission revocation must not crash the receiver. */ }
        }
    }

    fun expire() = synchronized(lock) {
        if (engine.snapshot.session?.active(System.currentTimeMillis()) == false) clear()
    }

    /** Only a consumed, locally issued ticket of the still-active native session can open a screen. */
    fun opened(intent: Intent?): NotificationOpen? = synchronized(lock) {
        if (intent?.action != ACTION_OPEN) return null
        val token = intent.getStringExtra(EXTRA_TOKEN) ?: return null
        if (intent.data != openUri(token)) return null
        val owner = engine.snapshot.session?.takeIf { it.active(System.currentTimeMillis()) } ?: return null
        val occurrence = engine.snapshot.delivered.firstOrNull { it.token == token } ?: return null
        val destination = occurrence.destination ?: return null
        NotificationOpen(owner, destination, occurrence.key, occurrence.revision, token)
    }

    /** PendingIntent identity includes the snapshot token, preventing stale alarms and hash collisions. */
    fun pendingIntent(alarm: InstalledNotificationAlarm, flag: Int = PendingIntent.FLAG_NO_CREATE): PendingIntent? =
        PendingIntent.getBroadcast(context, alarm.id, Intent(context, NativeNotificationReceiver::class.java)
            .setAction(ACTION_FIRE).setData(Uri.Builder().scheme("assistant-native-notify").authority("alarm")
                .appendPath(alarm.token).build()).putExtra(EXTRA_TOKEN, alarm.token), PendingIntent.FLAG_IMMUTABLE or flag)

    private suspend fun verifiedSession(owner: NotificationSession?): NotificationSession? {
        if (owner == null || !owner.active(System.currentTimeMillis())) return null
        val record = runCatching { KeystoreSessionSecretStore(context).load(owner.instance, owner.origin) }.getOrNull() ?: return null
        val expires = runCatching { Instant.parse(record.expiresAt).toEpochMilliseconds() }.getOrNull() ?: return null
        return NotificationSession(record.namespace.instance, record.namespace.origin, record.namespace.userId,
            record.deviceId, record.sessionId, expires, owner.previews).takeIf { owner.sameSession(it) && it.active(System.currentTimeMillis()) }
    }

    private fun armExpiry(session: NotificationSession) {
        val intent = expiryIntent(PendingIntent.FLAG_UPDATE_CURRENT) ?: return
        try {
            if (exactAlarmsAllowed()) alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, session.expiresAt, intent)
            else alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, session.expiresAt, intent)
        } catch (_: SecurityException) {
            runCatching { alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, session.expiresAt, intent) }
        }
    }

    private fun expiryIntent(flag: Int): PendingIntent? = PendingIntent.getBroadcast(context, 0,
        Intent(context, NativeNotificationReceiver::class.java).setAction(ACTION_EXPIRE), PendingIntent.FLAG_IMMUTABLE or flag)

    private fun openUri(token: String): Uri = Uri.Builder().scheme("assistant-native-notify").authority("open").appendPath(token).build()

    companion object {
        const val ACTION_FIRE = "com.chromvoid.bro.notify.FIRE"
        const val ACTION_OPEN = "com.chromvoid.bro.notify.OPEN"
        const val ACTION_EXPIRE = "com.chromvoid.bro.notify.EXPIRE"
        const val EXTRA_TOKEN = "native_notification_token"
        const val CHANNEL = "native-reminders"
        private const val SNAPSHOT = "snapshot"
        private const val ASKED = "asked"
        private const val TAG_PREFIX = "native-reminder:"
        private fun notificationTag(token: String) = TAG_PREFIX + token
        @Volatile private var instance: AndroidNotifications? = null

        fun get(context: Context): AndroidNotifications = instance ?: synchronized(this) {
            instance ?: AndroidNotifications(context.applicationContext).also { instance = it }
        }
    }
}
