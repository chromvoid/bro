package assistant.mobile.platform.ring

import assistant.mobile.platform.R

/** A transient foreground cookie is not the authority of an already-bound offline acquisition. */
object RingWorkoutAccountPolicy {
    fun interrupt(previousUser: String?, nextUser: String?, boundUser: String?, reason: String): Boolean {
        if (reason !in setOf("logout", "switch", "lapsed") || reason == "lapsed" && nextUser != null)
            throw RingFailure("bad_payload", RingText.text(R.string.ring_native_7de31f9d779e))
        if (reason == "lapsed") return false
        if (reason == "logout") return true
        if (previousUser == nextUser) return false
        // Restoring the same foreground owner does not change the authorized source or running recorder.
        if (previousUser == null && nextUser != null && nextUser == boundUser) return false
        return true
    }
}
