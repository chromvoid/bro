package assistant.mobile.android

import android.app.PendingIntent
import android.content.ComponentName
import android.content.Intent
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.security.keystore.KeyProperties
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import assistant.mobile.android.ambient.AmbientQueue
import assistant.mobile.android.whoop.WhoopStore
import assistant.mobile.android.whoop.WhoopRepository
import assistant.mobile.core.GeneratedStrings
import assistant.mobile.design.localizedString
import assistant.mobile.core.AccountNamespace
import assistant.mobile.core.SessionLease
import assistant.mobile.platform.KeystoreSessionSecretStore
import assistant.mobile.platform.ring.*
import java.io.File
import java.security.KeyStore
import java.security.MessageDigest
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Runs after adb install -r over the signed synthetic old shell, without clearing package data. */
@RunWith(AndroidJUnit4::class)
class NativeUpgradeSmokeTest {
    @Test fun sameUidKeysQueuesAndJournalsSurviveWithoutImportingOldLogin() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val fixture = context.getSharedPreferences("upgrade-fixture", Context.MODE_PRIVATE)
        if (BuildConfig.DEBUG) org.junit.Assume.assumeTrue("Upgrade fixture is installed by CI only", fixture.contains("uid"))
        val origin = requireNotNull(fixture.getString("origin", null))
        assertEquals(BuildConfig.ASSISTANT_ORIGIN, origin)
        assertEquals("dev.assistant.shell", context.packageName)
        assertEquals(fixture.getInt("uid", -1), android.os.Process.myUid())
        val owner = RingWorkoutBinding(origin, USER, "synthetic-ring", "synthetic-collector", ADDRESS, 7, "Europe/Moscow")
        val keys = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        for (alias in listOf("assistant.ring.${sha256(origin)}", "assistant.whoop.${sha256(origin)}",
            "assistant.ring.workouts.${sha256(origin)}", "assistant.ring.device.v1.${sha256(origin).take(24)}",
            "assistant.location.${shortDigest(origin)}")) {
            assertTrue("Upgrade lost existing Keystore alias $alias", keys.containsAlias(alias))
            assertEquals(KeyProperties.KEY_ALGORITHM_AES, keys.getKey(alias, null).algorithm)
        }
        assertNull(KeystoreSessionSecretStore(context).load("synthetic-instance", origin))
        assertFalse(File(context.noBackupFilesDir, "native-sessions").listFiles().orEmpty().any { it.isFile })
        assertTrue("The old shell verified its real cookie jar before replacement", fixture.getBoolean("old_cookie_verified", false))
        val ring = RingStore(context, origin)
        val expectedRingBatch = Json.parseToJsonElement(requireNotNull(fixture.getString("ring_batch", null)))
        assertEquals(1, ring.count())
        assertEquals(expectedRingBatch, ring.batch(JsonPrimitive("must-not-rewrite-frozen-batch")))
        val checkpoint = ring.get("checkpoint")
        val workouts = RingWorkoutStore(context, origin)
        assertTrue(workouts.load().autoBlocked)
        assertTrue(workouts.load().commandUnknown)
        val device = RingDeviceStore(context, origin)
        assertEquals("synthetic-unknown-command", device.recovery(owner, "synthetic-unknown-command")
            ?.get("request")?.jsonObject?.get("operation_id")?.jsonPrimitive?.content)
        val diagnosticPath = File(context.noBackupFilesDir, "ring-diagnostics-${sha256(origin)}.sqlite")
        val diagnosticRows = SQLiteDatabase.openDatabase(diagnosticPath.absolutePath, null, SQLiteDatabase.OPEN_READONLY).use { db ->
            db.rawQuery("SELECT count(*) FROM events", null).use { it.moveToFirst(); it.getInt(0) }
        }
        assertTrue("The seeded diagnostic events must retain their existing collector binding", diagnosticRows > 0)
        val whoop = WhoopStore(context, origin)
        val expectedWhoopBatch = Json.parseToJsonElement(requireNotNull(fixture.getString("whoop_batch", null)))
        assertEquals(1, whoop.count())
        assertEquals(expectedWhoopBatch, whoop.batch(JsonPrimitive("must-not-rewrite-frozen-batch"), null))
        // The older location store has a different binding/schema. Open exactly its old path and
        // alias, retain the grant and pending point, but never adopt its credential into native.
        SQLiteDatabase.openDatabase(context.getDatabasePath("location-v1.sqlite").absolutePath, null,
            SQLiteDatabase.OPEN_READONLY).use { db ->
            assertEquals(1, db.version)
            db.rawQuery("SELECT captured_at,sealed FROM points", null).use {
                assertTrue(it.moveToFirst())
                val plaintext = RingCrypto("assistant.location.${shortDigest(origin)}").captureReader()
                    .open(it.getBlob(1), "point:${it.getLong(0)}")
                assertEquals(55.75, Json.parseToJsonElement(plaintext.decodeToString()).jsonObject["latitude"]!!.jsonPrimitive.double, 0.0)
                assertFalse(it.moveToNext())
            }
        }
        val queue = AmbientQueue(File(context.filesDir, "ambient-audio"))
        val batch = queue.list().single()
        assertEquals(USER, batch.binding.userId)
        assertEquals(DEVICE, batch.binding.deviceId)
        assertTrue(queue.audioFile(batch).isFile)
        assertTrue(File(context.filesDir, "ambient-audio/$PARTIAL.partial/interrupted").isFile)
        val notificationPrefs = context.getSharedPreferences("shell-notifications", Context.MODE_PRIVATE)
        assertTrue(requireNotNull(notificationPrefs.getString("session", null)).contains(DEVICE))
        assertTrue(requireNotNull(notificationPrefs.getString("installed", null)).contains("synthetic-reminder"))
        assertFalse(notificationPrefs.getBoolean("enabled", true))
        assertTrue("The old installation scheduled a real synthetic alarm", fixture.getBoolean("old_alarm_verified", false))
        val oldAlarm = Intent().setComponent(ComponentName(context.packageName, "assistant.shell.notify.NotifyReceiver"))
            .setAction("assistant.shell.notify.FIRE")
        assertNull("Native upgrade must cancel the old login's alarm without erasing its binding evidence",
            PendingIntent.getBroadcast(context, 314159, oldAlarm, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_NO_CREATE))
        assertTrue(LegacyShellUpgrade.needsAcknowledgement(context))
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            assertEquals(Lifecycle.State.RESUMED, scenario.state)
            clickVisibleLabel(context.getString(R.string.upgrade_native_continue))
            // Read the production accessibility surface, with the same generated translations
            // used by the screen. No Compose testing API or debug factory enters this APK.
            awaitVisibleLabel(context.localizedString(GeneratedStrings.Auth.welcomeBack))
            awaitVisibleLabel(context.localizedString(GeneratedStrings.Auth.signIn), scrollForward = true)
        }
        // Merely starting the app or receiving another native login must neither erase nor replay
        // the old parent-bound outbox. A fresh native enrollment explicitly closes that lineage.
        val repository = RingRepository(context, origin)
        repository.setSession(RingSession("other-person", "synthetic-other-native", 1,
            instance = "synthetic-instance", origin = origin, nativeDeviceId = "new-native-device"))
        WhoopRepository.get(context, origin).updateSession(SessionLease(
            AccountNamespace("synthetic-instance", origin, "other-person"), 1, "synthetic-other-native"))
        assertTrue(repository.upload(owner.collectorId))
        assertEquals(1, ring.count())
        assertEquals(checkpoint, ring.get("checkpoint"))
        assertEquals(expectedRingBatch, ring.batch(JsonNull))
        assertEquals(1, whoop.count())
        assertEquals(expectedWhoopBatch, whoop.batch(JsonNull, null))
        assertEquals(batch.id, queue.list().single().id)
        assertNotNull(device.recovery(owner, "synthetic-unknown-command"))
        repository.close()
        ring.close()
        whoop.close()
        workouts.close()
    }

    private fun awaitVisibleLabel(label: String, scrollForward: Boolean = false): AccessibilityNodeInfo {
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        val deadline = SystemClock.uptimeMillis() + 30_000
        var lastScroll = 0L
        do {
            // Compose exposes virtual children through its platform provider, but does not
            // implement provider text-search. Walk that same tree a screen reader receives.
            val nodes = accessibilityChildren(automation.rootInActiveWindow)
            val found = nodes.firstOrNull {
                it.isVisibleToUser && (it.text?.toString() == label || it.contentDescription?.toString() == label)
            }
            if (found != null) return found
            val now = SystemClock.uptimeMillis()
            if (scrollForward && now - lastScroll >= 300) {
                nodes.firstOrNull { it.isVisibleToUser && it.isScrollable &&
                    it.actionList.any { action -> action.id == AccessibilityNodeInfo.ACTION_SCROLL_FORWARD }
                }?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
                lastScroll = now
            }
            SystemClock.sleep(100)
        } while (SystemClock.uptimeMillis() < deadline)
        throw AssertionError("No visible native accessibility node with resource label: $label")
    }

    private fun accessibilityChildren(root: AccessibilityNodeInfo?): List<AccessibilityNodeInfo> {
        if (root == null) return emptyList()
        val pending = ArrayDeque<AccessibilityNodeInfo>()
        val result = mutableListOf<AccessibilityNodeInfo>()
        pending.addLast(root)
        while (pending.isNotEmpty()) {
            val node = pending.removeFirst()
            result += node
            repeat(node.childCount) { index -> node.getChild(index)?.let(pending::addLast) }
        }
        return result
    }

    private fun clickVisibleLabel(label: String) {
        var target: AccessibilityNodeInfo? = awaitVisibleLabel(label)
        while (target != null && !target.isClickable) target = target.parent
        assertNotNull("The native resource label has no clickable control: $label", target)
        assertTrue("Cannot click native resource label: $label", requireNotNull(target)
            .performAction(AccessibilityNodeInfo.ACTION_CLICK))
    }

    private fun shortDigest(value: String) = MessageDigest.getInstance("SHA-256").digest(value.toByteArray())
        .take(8).joinToString("") { "%02x".format(it) }

    companion object {
        const val USER = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"
        const val DEVICE = "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"
        const val PARTIAL = "dddddddd-dddd-4ddd-8ddd-dddddddddddd"
        const val ADDRESS = "00:11:22:33:44:55"
    }
}
