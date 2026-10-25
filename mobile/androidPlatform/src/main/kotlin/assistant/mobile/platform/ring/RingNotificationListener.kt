package assistant.mobile.platform.ring

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

/** This listener has no BLE access, storage or uploader. */
class RingNotificationListener : NotificationListenerService() {
    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (sbn.packageName == packageName || sbn.notification.flags and Notification.FLAG_GROUP_SUMMARY != 0 ||
            sbn.notification.flags and Notification.FLAG_ONGOING_EVENT != 0) return
        val extras = sbn.notification.extras ?: return
        val text = listOfNotNull(extras.getCharSequence(Notification.EXTRA_TITLE)?.toString(),
            extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()).filter { it.isNotBlank() }.joinToString(": ")
        if (text.isNotBlank()) RingNotificationRelay.post(sbn.packageName, sbn.key, sbn.postTime, text)
    }
    override fun onListenerDisconnected() { RingNotificationRelay.disconnected() }
}

internal object RingNotificationRelay {
    private var owner: Any? = null
    private var receiver: ((String, String, Long, String) -> Unit)? = null
    private var revoked: (() -> Unit)? = null
    @Synchronized fun attach(token: Any, receive: (String, String, Long, String) -> Unit, accessLost: () -> Unit) {
        owner = token; receiver = receive; revoked = accessLost
    }
    @Synchronized fun detach(token: Any) { if (owner === token) { owner = null; receiver = null; revoked = null } }
    fun post(packageName: String, key: String, at: Long, text: String) { synchronized(this) { receiver }?.invoke(packageName, key, at, text) }
    fun disconnected() { synchronized(this) { revoked }?.invoke() }
}

object RingNotificationText {
    /** Cap the final UTF-8 stream without splitting a Unicode code point. */
    fun bounded(text: String, maxBytes: Int = 512): String {
        require(maxBytes in 1..512)
        val result = StringBuilder(); var count = 0; var index = 0
        while (index < text.length) {
            val codePoint = Character.codePointAt(text, index); index += Character.charCount(codePoint)
            if (codePoint == 0 || codePoint in 0xd800..0xdfff) continue
            val piece = String(Character.toChars(codePoint)); val bytes = piece.toByteArray(Charsets.UTF_8).size
            if (count + bytes > maxBytes) break
            result.append(piece); count += bytes
        }
        return result.toString()
    }
}
