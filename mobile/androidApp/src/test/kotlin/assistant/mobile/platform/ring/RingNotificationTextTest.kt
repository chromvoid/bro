package assistant.mobile.platform.ring

import org.junit.Assert.*
import org.junit.Test

class RingNotificationTextTest {
    @Test fun utf8BoundNeverSplitsSupplementaryCharacters() {
        assertEquals("ab😀", RingNotificationText.bounded("ab😀я", 6))
        assertEquals("ab", RingNotificationText.bounded("ab😀я", 5))
        assertTrue(RingNotificationText.bounded("😀".repeat(200)).toByteArray(Charsets.UTF_8).size <= 512)
    }
}
