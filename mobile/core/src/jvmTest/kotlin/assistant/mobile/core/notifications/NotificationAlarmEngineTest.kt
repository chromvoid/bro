package assistant.mobile.core.notifications

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

class NotificationAlarmEngineTest {
    @Test
    fun receiptContainsOnlyAlarmsActuallyAcceptedByTheOs() {
        val fixture = Fixture()
        fixture.backend.refused += "cannot-arm"
        val installed = fixture.engine.replace(schedule(items = listOf(item("works"), item("cannot-arm"))), NOW, true)
        assertEquals(listOf("works"), installed)
        assertEquals(listOf("works"), fixture.engine.snapshot.pendingAck?.installed)
        assertEquals(listOf("works"), fixture.backend.saved!!.installed.map { it.key })
    }

    @Test
    fun logoutCancelsAlarmsDisplayedNotificationsAndDurableAuthority() {
        val fixture = Fixture()
        fixture.install(item("alarm"))
        fixture.engine.clear()
        assertTrue(fixture.backend.armed.isEmpty())
        assertNull(fixture.engine.snapshot.session)
        assertFalse(fixture.engine.snapshot.enabled)
        assertNull(fixture.engine.snapshot.pendingAck)
        assertEquals(NotificationAlarmSnapshot(), fixture.backend.saved)
        assertTrue(fixture.backend.displayCancellations > 0)
    }

    @Test
    fun revokedNativeSessionCannotRestoreOrFireAnyStoredAlarm() {
        val fixture = Fixture()
        fixture.install(item("alarm"))
        val token = fixture.engine.snapshot.installed.single().token
        fixture.engine.restore(NOW + 1_000, verified = null, permitted = true)
        assertTrue(fixture.backend.armed.isEmpty())
        assertNull(fixture.engine.consume(token, NOW + 60_000, null, true))
        assertNull(fixture.engine.snapshot.session)
    }

    @Test
    fun expiryClearsWholeSessionEvenWhenOneAlarmIntentArrivesLate() {
        val fixture = Fixture()
        fixture.install(item("alarm"))
        val token = fixture.engine.snapshot.installed.single().token
        assertNull(fixture.engine.consume(token, OWNER.expiresAt, OWNER, true))
        assertTrue(fixture.backend.armed.isEmpty())
        assertNull(fixture.engine.snapshot.session)
        assertNull(fixture.engine.snapshot.pendingAck)
    }

    @Test
    fun rebootReinstallsOnlyPersistedFutureAlarmsOfTheSameNativeLogin() {
        val fixture = Fixture()
        fixture.install(item("ahead"), item("already-past", dueAt = "2026-10-17T10:00:05Z"))
        val saved = fixture.backend.saved!!
        fixture.backend.armed.clear() // Android forgets alarms at boot.
        val restarted = NotificationAlarmEngine(fixture.backend, saved)
        restarted.restore(NOW + 10_000, OWNER, true)
        assertEquals(listOf("ahead"), fixture.backend.armed.values.map { it.key })
        assertEquals(listOf("ahead"), restarted.snapshot.installed.map { it.key })
        assertNull(restarted.snapshot.pendingAck) // A fresh projection must replace a changed receipt.
        assertTrue(restarted.snapshot.enabled)
    }

    @Test
    fun repeatedBroadcastAndRepeatedSnapshotCannotShowTheSameOccurrenceTwice() {
        val fixture = Fixture()
        fixture.install(item("once"))
        val token = fixture.engine.snapshot.installed.single().token
        assertNotNull(fixture.engine.consume(token, NOW + 60_000, OWNER, true))
        assertNull(fixture.engine.consume(token, NOW + 60_000, OWNER, true))
        // Server clock skew or a delayed projection must not resurrect a consumed occurrence.
        assertTrue(fixture.engine.replace(schedule(SECOND, listOf(item("once"))), NOW, true).isEmpty())
        assertTrue(fixture.engine.snapshot.installed.isEmpty())
        assertEquals(1, fixture.engine.snapshot.delivered.size)
        // A new revision is a separate occurrence, allowed to replace what the person saw.
        assertEquals(listOf("once"), fixture.engine.replace(schedule(SECOND, listOf(item("once", revision = 2))), NOW, true))
    }

    @Test
    fun deniedPermissionCancelsOldAlarmsAndNeverClaimsAnInstallation() {
        val fixture = Fixture()
        fixture.install(item("private"))
        assertTrue(fixture.engine.replace(schedule(SECOND, listOf(item("new"))), NOW, false).isEmpty())
        assertTrue(fixture.backend.armed.isEmpty())
        assertTrue(fixture.engine.snapshot.installed.isEmpty())
        assertNull(fixture.engine.snapshot.pendingAck)
    }

    @Test
    fun storageFailureCancelsTheOsAlarmAndLeavesNoFalseReceipt() {
        val fixture = Fixture()
        fixture.backend.rejectNextSave = true
        assertTrue(fixture.engine.replace(schedule(), NOW, true).isEmpty())
        assertTrue(fixture.backend.armed.isEmpty())
        assertNull(fixture.engine.snapshot.pendingAck)
        assertNull(fixture.engine.snapshot.session)
    }

    private class Fixture {
        val backend = Backend()
        val engine = NotificationAlarmEngine(backend)
        init { engine.bind(OWNER); engine.setEnabled(true); backend.cancelled.clear() }
        fun install(vararg items: NotificationScheduleItem) { engine.replace(schedule(items = items.toList()), NOW, true) }
    }

    private class Backend : NotificationAlarmBackend {
        var saved: NotificationAlarmSnapshot? = null
        val armed = linkedMapOf<String, InstalledNotificationAlarm>()
        val cancelled = mutableListOf<String>()
        val refused = mutableSetOf<String>()
        var rejectNextSave = false
        var displayCancellations = 0
        override fun save(snapshot: NotificationAlarmSnapshot): Boolean {
            if (rejectNextSave) { rejectNextSave = false; return false }
            saved = snapshot
            return true
        }
        override fun arm(alarm: InstalledNotificationAlarm): Boolean {
            if (alarm.key in refused) return false
            armed[alarm.token] = alarm
            return true
        }
        override fun cancel(alarm: InstalledNotificationAlarm) { cancelled += alarm.token; armed.remove(alarm.token) }
        override fun cancelDisplayed() { displayCancellations++ }
    }

    private companion object {
        val NOW = Instant.parse("2026-10-17T10:00:00Z").toEpochMilliseconds()
        val OWNER = NotificationSession("native", "https://assistant.invalid", "user", "device", "login-1", NOW + 3_600_000)
        const val FIRST = "10000000-0000-0000-0000-000000000001"
        const val SECOND = "10000000-0000-0000-0000-000000000002"
        fun item(key: String, revision: Long = 1, dueAt: String = "2026-10-17T10:01:00Z", link: String? = "/timers") =
            NotificationScheduleItem(key, revision, dueAt, "Напоминание", "", link)
        fun schedule(id: String = FIRST, items: List<NotificationScheduleItem> = listOf(item("one"))) =
            NotificationSchedule(id, "2026-10-17T10:00:00Z", "2026-10-17T11:00:00Z", items)
    }
}
