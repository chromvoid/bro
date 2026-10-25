package assistant.mobile.platform.ring

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Only native query failure identifiers belong in persisted capture metadata. */
object RingCaptureFailure {
    private val knownCodes = setOf(
        "response_timeout",
        "write_failed",
        "running_ambiguous_time",
        "invalid_running_snapshot",
        "invalid_legacy_hr_context",
    )

    fun code(effect: JsonObject): String? {
        val status = effect["status"] as? JsonPrimitive
        if (status?.isString != true || status.content != "failed") return null
        val cause = effect["failure_code"] as? JsonPrimitive
        if (cause?.isString != true || cause.content.length !in 1..64) return "query_failed"
        return cause.content.takeIf { it in knownCodes } ?: "query_failed"
    }
}
