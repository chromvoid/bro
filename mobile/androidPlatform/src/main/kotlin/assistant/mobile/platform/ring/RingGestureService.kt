package assistant.mobile.platform.ring

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.media.AudioManager
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent

/** Local Android actions from a qualified physical HID source; no window-content reader. */
class RingGestureService : AccessibilityService() {
    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit
    override fun onInterrupt() { RingGestureRelay.revoked() }
    override fun onDestroy() { RingGestureRelay.revoked(); super.onDestroy() }
    override fun onKeyEvent(event: KeyEvent?): Boolean = event?.let { RingGestureRelay.key(it, ::execute) } == true
    private fun execute(request: RingGestureDispatch): Boolean {
        val action = request.action
        val accepted = when (action) {
            RingGestureAction.NONE -> false
            RingGestureAction.BACK -> performGlobalAction(GLOBAL_ACTION_BACK)
            RingGestureAction.HOME -> performGlobalAction(GLOBAL_ACTION_HOME)
            RingGestureAction.RECENTS -> performGlobalAction(GLOBAL_ACTION_RECENTS)
            RingGestureAction.NOTIFICATIONS -> performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS)
            RingGestureAction.VOLUME_UP, RingGestureAction.VOLUME_DOWN -> {
                val audio = getSystemService(AudioManager::class.java) ?: return false
                audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, if (action == RingGestureAction.VOLUME_UP) AudioManager.ADJUST_RAISE
                    else AudioManager.ADJUST_LOWER, AudioManager.FLAG_SHOW_UI); true
            }
            RingGestureAction.PLAY_PAUSE, RingGestureAction.NEXT, RingGestureAction.PREVIOUS -> {
                val code = when (action) { RingGestureAction.NEXT -> KeyEvent.KEYCODE_MEDIA_NEXT
                    RingGestureAction.PREVIOUS -> KeyEvent.KEYCODE_MEDIA_PREVIOUS; else -> KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE }
                val audio = getSystemService(AudioManager::class.java) ?: return false
                audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code)); audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code)); true
            }
            else -> return screen(request)
        }
        RingGestureRelay.outcome(request.id, if (accepted) RingGestureResult.ANDROID_ACCEPTED else RingGestureResult.ANDROID_REJECTED)
        return accepted
    }
    private fun screen(request: RingGestureDispatch): Boolean {
        val metrics = resources.displayMetrics
        if (metrics.widthPixels <= 0 || metrics.heightPixels <= 0) return false
        fun path(x: Float, y: Float, endX: Float = x, endY: Float = y) = Path().apply {
            moveTo(x * metrics.widthPixels, y * metrics.heightPixels)
            if (x != endX || y != endY) lineTo(endX * metrics.widthPixels, endY * metrics.heightPixels)
        }
        val builder = GestureDescription.Builder()
        val action = request.action
        if (action == RingGestureAction.DOUBLE_TAP_CENTER) {
            builder.addStroke(GestureDescription.StrokeDescription(path(.5f, .5f), 0, 60))
            builder.addStroke(GestureDescription.StrokeDescription(path(.5f, .5f), 150, 60))
        } else {
            val trace = when (action) {
                RingGestureAction.TAP_CENTER -> path(.5f, .5f); RingGestureAction.TAP_LEFT -> path(.2f, .5f); RingGestureAction.TAP_RIGHT -> path(.8f, .5f)
                RingGestureAction.SWIPE_UP -> path(.5f, .7f, .5f, .3f); RingGestureAction.SWIPE_DOWN -> path(.5f, .3f, .5f, .7f)
                RingGestureAction.SWIPE_LEFT -> path(.8f, .5f, .2f, .5f); RingGestureAction.SWIPE_RIGHT -> path(.2f, .5f, .8f, .5f)
                else -> return false
            }
            val tap = action in setOf(RingGestureAction.TAP_CENTER, RingGestureAction.TAP_LEFT, RingGestureAction.TAP_RIGHT)
            builder.addStroke(GestureDescription.StrokeDescription(trace, 0, if (tap) 60 else 180))
        }
        return try { dispatchGesture(builder.build(), object : GestureResultCallback() {
            override fun onCompleted(gesture: GestureDescription?) { RingGestureRelay.outcome(request.id, RingGestureResult.ANDROID_COMPLETED) }
            override fun onCancelled(gesture: GestureDescription?) { RingGestureRelay.outcome(request.id, RingGestureResult.ANDROID_CANCELLED) }
        }, null) } catch (_: RuntimeException) { false }
    }
}
