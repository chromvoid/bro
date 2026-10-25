package assistant.mobile.platform.ring

import kotlinx.coroutines.flow.StateFlow

/** Settings actions carry the screen's grant generation, so a late tap cannot configure a replacement ring. */
data class RingPhoneOwner(val userId: String, val sourceId: String, val bindingGeneration: Long) {
    fun matches(binding: RingWorkoutBinding): Boolean = userId == binding.userId && sourceId == binding.sourceId &&
        bindingGeneration == binding.grantGeneration
    companion object {
        fun from(binding: RingWorkoutBinding) = RingPhoneOwner(binding.userId, binding.sourceId, binding.grantGeneration)
    }
}

interface RingPhoneController {
    val notificationStates: StateFlow<RingNotificationState>
    val gestureStates: StateFlow<RingGestureState>
    fun notification(action: RingNotificationAction)
    fun gesture(action: RingGestureActionRequest)
    fun refreshPhoneControls()
}

data class RingNotificationApp(val packageName: String, val label: String)
data class RingNotificationState(
    val owner: RingPhoneOwner? = null, val accessGranted: Boolean = false, val enabled: Boolean = false,
    val allowedPackages: Set<String> = emptySet(), val apps: List<RingNotificationApp> = emptyList(),
    val error: RingNativeError? = null,
)
sealed interface RingNotificationAction {
    val owner: RingPhoneOwner
    data class OpenAccess(override val owner: RingPhoneOwner) : RingNotificationAction
    data class Configure(override val owner: RingPhoneOwner, val enabled: Boolean, val allowedPackages: Set<String>) : RingNotificationAction
}

enum class RingGestureInput(val wire: String) {
    TAP("tap"), DOUBLE_TAP("double_tap"), SWIPE_LEFT("swipe_left"), SWIPE_RIGHT("swipe_right"), SWIPE_UP("swipe_up"), SWIPE_DOWN("swipe_down")
}
enum class RingGestureAction(val wire: String) {
    NONE("none"), BACK("back"), HOME("home"), RECENTS("recents"), NOTIFICATIONS("notifications"),
    VOLUME_UP("volume_up"), VOLUME_DOWN("volume_down"), PLAY_PAUSE("play_pause"), NEXT("next"), PREVIOUS("previous"),
    TAP_CENTER("tap_center"), DOUBLE_TAP_CENTER("double_tap_center"), TAP_LEFT("tap_left"), TAP_RIGHT("tap_right"),
    SWIPE_UP("swipe_up"), SWIPE_DOWN("swipe_down"), SWIPE_LEFT("swipe_left"), SWIPE_RIGHT("swipe_right")
}
enum class RingGestureResult(val wire: String) {
    DISPATCHING("dispatching"), ANDROID_ACCEPTED("android_accepted"), ANDROID_COMPLETED("android_completed"),
    ANDROID_CANCELLED("android_cancelled"), ANDROID_REJECTED("android_rejected")
}
data class RingGestureOutcome(val operationId: String, val action: RingGestureAction, val result: RingGestureResult)
data class RingGestureState(
    val owner: RingPhoneOwner? = null, val accessGranted: Boolean = false, val enabled: Boolean = false,
    val qualified: Boolean = false, val reason: String = "physical_hid_source_unqualified",
    val mappings: Map<RingGestureInput, RingGestureAction> = RingGestureInput.entries.associateWith { RingGestureAction.NONE },
    val lastAction: RingGestureOutcome? = null,
)
sealed interface RingGestureActionRequest {
    val owner: RingPhoneOwner
    data class OpenAccess(override val owner: RingPhoneOwner) : RingGestureActionRequest
    data class Configure(override val owner: RingPhoneOwner, val enabled: Boolean,
        val mappings: Map<RingGestureInput, RingGestureAction>) : RingGestureActionRequest
}
