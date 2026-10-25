package assistant.mobile.android.whoop

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class WhoopModelTest {
    @Test fun stateHasNoMeasurementTrustOrCollectorCredentialFields() {
        val state = WhoopState().json()
        assertEquals(JsonPrimitive("whoop-5"), state["profile"])
        assertNull(state["token"])
        assertNull(state["measurements"])
        assertNull(state["metric_status"])
        assertNull(state["verified"])
        assertEquals(JsonNull, state["history_complete"])
    }
}
