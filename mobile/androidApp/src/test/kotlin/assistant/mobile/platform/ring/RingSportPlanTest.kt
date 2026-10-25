package assistant.mobile.platform.ring

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class RingSportPlanTest {
    private fun capabilities(start: Boolean = false, evidence: String? = null): JsonObject = obj {
        put("source_catalog", JsonArray(listOf(obj {
            put("requested_id", 50); put("name", "outdoor_cycling"); put("family", "cycling")
            put("app_outdoor_gps", true); put("candidate_wire_mode", 50)
            put("qualification", "protocol_unverified"); put("evidence_ref", "synthetic-source-catalog")
        }, obj {
            put("requested_id", 333); put("name", "snow_car"); put("family", "winter")
            put("app_outdoor_gps", true); put("candidate_wire_mode", JsonNull)
            put("qualification", "protocol_unverified"); put("evidence_ref", "synthetic-source-catalog")
        })))
        put("sport_capabilities", JsonArray(listOf(obj {
            put("requested_id", 50); put("start", start); put("pause", false); put("resume", false); put("stop", false)
            evidence?.let { put("evidence_ref", it) }
        })))
    }
    @Test fun eachMutationRequiresItsOwnCompiledEvidence() {
        val plan = RingSportPlan.select(capabilities(), 50)
        assertFalse(RingSportPlan.permitted(capabilities(start = true), plan, "start"))
        val native = capabilities(start = true, evidence = "synthetic-physical-start")
        assertTrue(RingSportPlan.permitted(native, plan, "start"))
        assertFalse(RingSportPlan.permitted(native, plan, "pause"))
        assertFalse(RingSportPlan.permitted(native, plan, "stop"))
    }
    @Test fun onlyMatchingQualifiedSemanticReceiptCanConfirmCandidateMode() {
        val plan = RingSportPlan.select(capabilities(), 50)
        fun result(requested: Int = 50, actual: Int? = 50, status: String = "verified", evidence: String? = "synthetic-receipt") = obj {
            put("status", status); put("sport", obj {
                put("requested_id", requested); put("actual_wire_mode", actual?.let(::JsonPrimitive) ?: JsonNull)
                evidence?.let { put("evidence_ref", it) }
            })
        }
        for (unproved in listOf(result(status = "unknown"), result(requested = 7), result(actual = null), result(actual = 10), result(evidence = null)))
            assertEquals(plan, plan.confirmed(unproved))
        val confirmed = plan.confirmed(result())
        assertEquals(50, confirmed.actualWireMode); assertEquals("verified", confirmed.qualification)
        assertEquals(confirmed, RingSportPlan.from(confirmed.json()))
    }
}
