package assistant.mobile.core.notifications

import kotlinx.serialization.json.*
import kotlin.test.*
import kotlin.time.Instant

class NotificationModelsTest {
    @Test
    fun tomorrowAtEightUsesAccountCalendarAcrossDstAndDateBoundary() {
        fun morning(now: String, zone: String) = notificationNextMorning(Instant.parse(now).toEpochMilliseconds(), zone)
        assertEquals("2026-10-25T07:00:00Z", morning("2026-10-24T20:00:00Z", "Europe/Berlin"))
        assertEquals("2027-01-01T19:00:00Z", morning("2026-12-31T23:00:00Z", "Pacific/Auckland"))
    }

    @Test
    fun settingsRequirePairOfTimesAndSendOnlyKnownTopicsAndCurrentRevision() {
        val settings = NotificationSettings("23:00", "08:00", emptyList(), 0, listOf(NotificationTopic("app.test", "important")))
        val body = notificationObject(notificationSettingsBody(settings.draft().copy(mutedTopics = listOf("app.test", "app.test")), settings))
        assertEquals(0, body.getValue("revision").jsonPrimitive.int)
        assertEquals(1, body.getValue("muted_topics").jsonArray.size)
        for (time in listOf("24:00", "1:00", "22:60", "xx:00")) {
            assertFailsWith<IllegalArgumentException> { notificationSettingsBody(settings.draft().copy(start = time), settings) }
        }
        assertFailsWith<IllegalArgumentException> { notificationSettingsBody(settings.draft().copy(mutedTopics = listOf("unknown")), settings) }
        assertFailsWith<IllegalArgumentException> { decodeNotificationSettings("""{"quiet_start":"23:00","quiet_end":null,"muted_topics":[],"revision":0,"topics":[]}""".encodeToByteArray()) }
    }

}
