package assistant.mobile.platform.ring

import assistant.mobile.platform.R

import kotlinx.serialization.json.*

/** Selected app activity, source candidate and physically confirmed ring mode are distinct facts. */
data class RingSportPlan(val requestedId: Int, val name: String, val family: String,
    val candidateWireMode: Int?, val actualWireMode: Int? = null,
    val qualification: String = "protocol_unverified", val evidenceRef: String,
    val resolvedActivityId: Int = requestedId, val mappingBasis: String = "direct_whitelist") {
    init {
        require(requestedId in 1..10_000 && name.length in 1..64 && family.length in 1..32)
        require(candidateWireMode == null || candidateWireMode in 0..255)
        require(actualWireMode == null || actualWireMode in 0..255 && qualification == "verified" && actualWireMode == candidateWireMode)
        require(qualification != "verified" || actualWireMode != null)
        require(qualification in setOf("protocol_unverified", "verified") && evidenceRef.length in 1..200)
        require(resolvedActivityId in 1..10_000 && mappingBasis in setOf("direct_whitelist", "family_fallback", "adapter_fallback"))
    }
    val sportType: String get() = if (name == "run") "running" else name
    fun json(): JsonObject = obj {
        put("requested_id", requestedId); put("name", name); put("family", family)
        put("candidate_wire_mode", candidateWireMode?.let(::JsonPrimitive) ?: JsonNull)
        put("actual_wire_mode", actualWireMode?.let(::JsonPrimitive) ?: JsonNull)
        put("qualification", qualification); put("evidence_ref", evidenceRef)
        put("resolved_activity_id", resolvedActivityId); put("mapping_basis", mappingBasis)
    }
    fun command(type: String): JsonObject = obj { put("type", type); put("plan", obj { put("requested_id", requestedId) }) }
    fun confirmed(result: JsonObject): RingSportPlan {
        val sport = result["sport"] as? JsonObject ?: return this
        if (result.string("status") != "verified" || sport["requested_id"]?.jsonPrimitive?.intOrNull != requestedId)
            return this
        val wire = sport["actual_wire_mode"]?.jsonPrimitive?.intOrNull ?: return this
        if (wire != candidateWireMode) return this
        val evidence = sport.string("evidence_ref")?.takeIf { it.isNotBlank() } ?: return this
        return copy(actualWireMode = wire, qualification = "verified", evidenceRef = evidence)
    }
    companion object {
        fun from(value: JsonObject): RingSportPlan = RingSportPlan(value["requested_id"]!!.jsonPrimitive.int,
            value.string("name")!!, value.string("family")!!, value["candidate_wire_mode"]?.jsonPrimitive?.intOrNull,
            value["actual_wire_mode"]?.jsonPrimitive?.intOrNull, value.string("qualification") ?: "protocol_unverified",
            value.string("evidence_ref")!!, value["resolved_activity_id"]?.jsonPrimitive?.intOrNull ?: value["requested_id"]!!.jsonPrimitive.int,
            value.string("mapping_basis") ?: "direct_whitelist")
        fun select(capabilities: JsonObject, requested: Int): RingSportPlan {
            val item = (capabilities["source_catalog"] as? JsonArray)?.mapNotNull { it as? JsonObject }
                ?.singleOrNull { it["requested_id"]?.jsonPrimitive?.intOrNull == requested }
                ?: throw RingFailure("sport_unavailable", RingText.text(R.string.ring_workout_89cd1e57f5df))
            // Only private native metadata is read here; the WebView supplies one requested ID.
            return from(JsonObject(item + mapOf("actual_wire_mode" to JsonNull,
                "qualification" to JsonPrimitive("protocol_unverified"))))
        }
        fun permitted(capabilities: JsonObject, plan: RingSportPlan, operation: String): Boolean =
            (capabilities["sport_capabilities"] as? JsonArray)?.mapNotNull { it as? JsonObject }
                ?.singleOrNull { it["requested_id"]?.jsonPrimitive?.intOrNull == plan.requestedId }
                ?.let { it.bool(operation) == true && !it.string("evidence_ref").isNullOrBlank() } == true
    }
}
