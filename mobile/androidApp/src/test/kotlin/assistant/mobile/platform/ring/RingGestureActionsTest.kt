package assistant.mobile.platform.ring

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class RingGestureActionsTest {
    @Test fun emptyPhysicalEvidenceNeverQualifiesANameOrKeyCode() {
        assertFalse(RingGestureProofs.matches(obj { put("model", "qring-r20"); put("firmware", "synthetic") },
            "00:11:22:33:44:55", "Soul ring synthetic", 1, 2, 0x101))
    }
}
