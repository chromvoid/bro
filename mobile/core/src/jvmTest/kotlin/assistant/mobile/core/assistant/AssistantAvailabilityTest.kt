package assistant.mobile.core.assistant

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AssistantAvailabilityTest {
    @Test
    fun unknownAvailabilityRetainsAVisibleUnsupportedState() {
        val configured = decodeAssistantAvailability("""{"state":"configured","reason":null,"retry_at":null,"checked_at":"2026-10-16T00:00:00Z"}""".encodeToByteArray())
        assertTrue(configured.supported)
        assertFalse(configured.copy(state = "future").supported)
        assertFalse(configured.copy(action = "execute_result").supported)
    }
}
