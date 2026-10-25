package assistant.mobile.platform.ring

import assistant.mobile.platform.R
import kotlinx.serialization.json.*

object RingGestureActions {
    /** Soul mapping; exact qualified physical InputDevice identity is a separate mandatory gate. */
    fun input(keyCode: Int): RingGestureInput? = when (keyCode) {
        66, 23 -> RingGestureInput.DOUBLE_TAP; 79, 85 -> RingGestureInput.TAP
        87, 22 -> RingGestureInput.SWIPE_RIGHT; 88, 21 -> RingGestureInput.SWIPE_LEFT
        92, 19, 24 -> RingGestureInput.SWIPE_UP; 93, 20, 25 -> RingGestureInput.SWIPE_DOWN
        else -> null
    }
    fun validate(value: Map<RingGestureInput, RingGestureAction>): Map<RingGestureInput, RingGestureAction> {
        if (value.keys != RingGestureInput.entries.toSet()) throw RingFailure("bad_payload", RingText.text(R.string.ring_gestures_all_mappings))
        return value.toMap()
    }
    internal fun parse(value: JsonObject): Map<RingGestureInput, RingGestureAction> {
        if (value.keys != RingGestureInput.entries.map { it.wire }.toSet()) throw RingFailure("bad_payload", RingText.text(R.string.ring_gestures_all_mappings))
        return RingGestureInput.entries.associateWith { input ->
            RingGestureAction.entries.singleOrNull { it.wire == value.string(input.wire) }
                ?: throw RingFailure("bad_payload", RingText.text(R.string.ring_gestures_unknown_action))
        }
    }
    internal fun json(value: Map<RingGestureInput, RingGestureAction>) = JsonObject(validate(value).mapKeys { it.key.wire }.mapValues { JsonPrimitive(it.value.wire) })
}

/** Compiled evidence links exact HW/FW and physical ring to the Android HID descriptor. */
object RingGestureProofs {
    private data class Proof(val identity: JsonObject, val physicalAddressSha256: String, val descriptorSha256: String,
        val vendorId: Int, val productId: Int, val sources: Int)
    private val qualified: List<Proof> = emptyList() // No RF20 physical InputDevice/key capture exists yet.
    fun matches(identity: JsonObject, address: String, descriptor: String, vendorId: Int, productId: Int, sources: Int): Boolean =
        qualified.any { it.identity == identity && it.physicalAddressSha256 == sha256(address.uppercase()) &&
            it.descriptorSha256 == sha256(descriptor) && it.vendorId == vendorId && it.productId == productId && it.sources == sources }
}
