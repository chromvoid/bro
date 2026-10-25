package assistant.mobile.platform.ring

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class RingOtaPowerTest {
    private val identity = obj { put("model", "synthetic"); put("hardware", "hw"); put("firmware", "source-fw") }
    @Test fun qualifiedPowerConditionsRequireActualFreshBatteryAndChargingWhenSpecified() {
        val conditions = RingOtaPowerConditions(60, RingOtaChargingRequirement.REQUIRED, "synthetic-power-only")
        assertFalse(conditions.satisfied(RingOtaDevice("synthetic", identity, null, true)))
        assertFalse(conditions.satisfied(RingOtaDevice("synthetic", identity, 59, true)))
        assertFalse(conditions.satisfied(RingOtaDevice("synthetic", identity, 100, null)))
        assertFalse(conditions.satisfied(RingOtaDevice("synthetic", identity, 100, false)))
        assertTrue(conditions.satisfied(RingOtaDevice("synthetic", identity, 60, true)))
        val noChargingRequirement = RingOtaPowerConditions(20, RingOtaChargingRequirement.NOT_REQUIRED, "synthetic-not-required")
        assertFalse(noChargingRequirement.satisfied(RingOtaDevice("synthetic", identity, 29, null)))
        assertTrue(noChargingRequirement.satisfied(RingOtaDevice("synthetic", identity, 30, null)))
    }

}
