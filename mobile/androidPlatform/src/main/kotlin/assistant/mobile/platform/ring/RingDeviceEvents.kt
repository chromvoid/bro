package assistant.mobile.platform.ring

import kotlinx.serialization.json.*

/** Bounded local state. Candidates never become measured values or game actions. */
class RingDeviceEvents(private val origin: String) {
    private var generation: Long? = null
    private var source: String? = null
    private var last: JsonObject? = null
    private var tasbihLast: JsonObject? = null
    private var gameLast: JsonObject? = null
    private var count: Int? = null
    private var candidateCount: Int? = null
    private var displayCount: Int? = null
    private var displayBasis: String? = null
    private var taps = 0L
    private var candidateTaps = 0L
    private val seen = LinkedHashSet<String>()
    private val proven = mutableMapOf<String, JsonElement>()
    fun reset() {
        generation = null; source = null; last = null; tasbihLast = null; gameLast = null
        count = null; candidateCount = null; displayCount = null; displayBasis = null
        taps = 0; candidateTaps = 0; seen.clear(); proven.clear()
    }
    /** Soul's start resets its local display, not the measured hardware count. */
    fun localStart() { count = null; displayCount = 0; displayBasis = "local_reset" }
    fun accept(owner: RingWorkoutBinding, expectedGeneration: Long, expectedSource: String, identity: JsonObject,
        event: JsonObject, nowMonotonicMs: Long, receivedAt: String): Boolean {
        if (owner.origin != origin) return false
        if (expectedSource != sha256("$origin\n${owner.address.uppercase()}")) return false
        val at = (event["received_monotonic_ms"] as? JsonPrimitive)?.takeUnless { it.isString }?.longOrNull ?: return false
        val raw = event.string("raw_payload_hex") ?: return false
        val kind = event.string("qualification") ?: return false
        val dataType = (event["data_type"] as? JsonPrimitive)?.takeUnless { it.isString }?.intOrNull ?: return false
        val eventGeneration = (event["generation"] as? JsonPrimitive)?.takeUnless { it.isString }?.longOrNull
        val candidate = event["candidate"] as? JsonObject ?: return false
        val resolved = event["resolved"] as? JsonObject
        if (event.string("namespace") != "core" || (event["opcode"] as? JsonPrimitive)?.intOrNull != 115 ||
            event.string("profile_id") != "oudmon-r20" || event.string("decoder_id") != "soul1214-core73-device-notify-v1" ||
            event["identity"] != identity || eventGeneration != expectedGeneration || expectedGeneration < 0 ||
            event.string("physical_source_id") != expectedSource || at < 0 || at > nowMonotonicMs || nowMonotonicMs - at > 5_000 ||
            raw.length != 28 || raw.any { it !in '0'..'9' && it !in 'a'..'f' } ||
            raw.take(2).toIntOrNull(16) != dataType || event.string("source_evidence_ref").isNullOrBlank() ||
            kind !in setOf("verified", "protocol_unverified") ||
            kind == "verified" && (resolved != candidate || event.string("physical_evidence_ref").isNullOrBlank()) ||
            kind != "verified" && (resolved != null || event["physical_evidence_ref"] != JsonNull)) return false
        val meaning = candidate.string("kind")
        val value = (candidate["count"] as? JsonPrimitive)?.takeUnless { it.isString }?.intOrNull
        val decodedCount = raw.substring(4, 6).toInt(16) or (raw.substring(6, 8).toInt(16) shl 8)
        if (dataType == 56) {
            if (meaning != "tasbih_count" || candidate.keys != setOf("kind", "count") || value == null || value !in 0..65535 || value != decodedCount) return false
        } else if (dataType !in setOf(37, 41) || meaning != "game_tap" || candidate.keys != setOf("kind")) return false
        if (generation != null && (generation != expectedGeneration || source != expectedSource)) reset()
        generation = expectedGeneration; source = expectedSource
        if (!seen.add("$expectedGeneration:$expectedSource:$at:$raw")) return false
        while (seen.size > 64) seen.remove(seen.first())
        val stamped = JsonObject(event + ("received_at" to JsonPrimitive(receivedAt)))
        last = stamped
        if (kind == "verified") proven[meaning!!] = event["physical_evidence_ref"]!!
        if (dataType == 56) {
            candidateCount = value; tasbihLast = stamped
            if (kind == "verified") { count = value; displayCount = value; displayBasis = "qualified_event" }
        } else {
            gameLast = stamped
            if (kind == "verified") { if (taps < Long.MAX_VALUE) taps += 1 }
            else if (candidateTaps < Long.MAX_VALUE) candidateTaps += 1
        }
        return true
    }
    fun snapshot(): JsonObject = obj {
        put("availability", JsonArray(listOf("tasbih_count", "game_tap").map { meaning -> obj {
            put("kind", meaning); put("status", if (meaning in proven) "verified" else "protocol_unverified")
            put("reason", if (meaning in proven) "qualified_physical_event" else "physical_event_source_unqualified")
            put("evidence_ref", proven[meaning] ?: JsonNull)
        } }))
        put("tasbih", obj { put("count", count?.let(::JsonPrimitive) ?: JsonNull)
            put("candidate_count", candidateCount?.let(::JsonPrimitive) ?: JsonNull)
            put("local_display_count", displayCount?.let(::JsonPrimitive) ?: JsonNull)
            put("display_basis", displayBasis?.let(::JsonPrimitive) ?: JsonNull); put("last_event", tasbihLast ?: JsonNull) })
        put("game", obj { put("tap_count", taps); put("candidate_tap_count", candidateTaps); put("last_event", gameLast ?: JsonNull) })
        put("last_event", last ?: JsonNull)
    }
}
