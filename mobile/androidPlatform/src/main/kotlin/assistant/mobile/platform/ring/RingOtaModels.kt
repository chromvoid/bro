package assistant.mobile.platform.ring

import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull

/** The screen's grant generation is checked again before every native action. */
data class RingOtaOwner(val userId: String, val sourceId: String, val bindingGeneration: Long)

sealed interface RingOtaAction {
    data class Prepare(val manifestId: String) : RingOtaAction
    data class Confirm(val operationId: String, val confirmationToken: String) : RingOtaAction
    data class Reconcile(val operationId: String) : RingOtaAction
}

interface RingOtaController {
    val otaStates: StateFlow<RingOtaState>
    fun otaCatalog(owner: RingOtaOwner): RingOtaCatalogState
    fun ota(owner: RingOtaOwner, action: RingOtaAction)
    fun otaScreenDetached(owner: RingOtaOwner)
}

data class RingOtaCatalogEntry(
    val manifestId: String, val title: String, val targetFirmware: String, val sizeBytes: Int,
    val sha256: String, val availability: String, val reason: String,
)
data class RingOtaCatalogState(
    val entries: List<RingOtaCatalogEntry> = emptyList(),
    val reason: String = "official_image_and_loader_unqualified",
)
data class RingOtaPrepared(
    val operationId: String, val confirmationToken: String, val sourceIdentity: RingIdentity,
    val targetFirmware: String, val sizeBytes: Int, val sha256: String, val expiresAtMs: Long,
)
data class RingOtaState(
    val userId: String? = null, val sourceId: String? = null, val bindingGeneration: Long = 0,
    val phase: String = "idle", val reason: String = "official_image_and_loader_unqualified",
    val physicalFence: RingPhysicalFence = RingPhysicalFence(), val operationId: String? = null,
    val progressBytes: Int = 0, val totalBytes: Int = 0,
    val expectedFirmware: String? = null, val actualFirmware: String? = null,
    val prepared: RingOtaPrepared? = null,
) {
    val owner: RingOtaOwner? get() = userId?.let { user -> sourceId?.takeIf { bindingGeneration > 0 }
        ?.let { RingOtaOwner(user, it, bindingGeneration) } }
    val active: Boolean get() = phase in setOf("preparing", "starting", "initializing", "transferring", "checking", "finalizing", "verifying", "reconciling")

    companion object {
        internal fun fromJournalProjection(value: JsonObject): RingOtaState {
            fun JsonObject.text(key: String) = (this[key] as? JsonPrimitive)?.contentOrNull
            fun JsonObject.int(key: String) = (this[key] as? JsonPrimitive)?.intOrNull ?: 0
            fun JsonObject.long(key: String) = (this[key] as? JsonPrimitive)?.longOrNull ?: 0L
            fun JsonObject.bool(key: String) = (this[key] as? JsonPrimitive)?.booleanOrNull ?: false
            val fence = value["physical_fence"] as? JsonObject
            val prepared = value["prepared"] as? JsonObject
            val source = prepared?.get("source_identity") as? JsonObject
            return RingOtaState(
                userId = value.text("user_id"), sourceId = value.text("source_id"), bindingGeneration = value.long("binding_generation"),
                phase = value.text("phase") ?: "idle", reason = value.text("reason") ?: "official_image_and_loader_unqualified",
                physicalFence = RingPhysicalFence(fence?.bool("blocked") ?: false, fence?.text("reason"),
                    canReconcile = fence?.bool("can_reconcile") ?: false),
                operationId = value.text("operation_id"), progressBytes = value.int("progress_bytes"), totalBytes = value.int("total_bytes"),
                expectedFirmware = value.text("expected_firmware"), actualFirmware = value.text("actual_firmware"),
                prepared = prepared?.let { RingOtaPrepared(it.text("operation_id").orEmpty(), it.text("confirmation_token").orEmpty(),
                    RingIdentity(source?.text("model"), source?.text("hardware"), source?.text("firmware")),
                    it.text("target_firmware").orEmpty(), it.int("size_bytes"), it.text("sha256").orEmpty(), it.long("expires_at_ms")) },
            )
        }
    }
}
