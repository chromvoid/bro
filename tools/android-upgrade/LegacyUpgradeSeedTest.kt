package assistant.shell

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Intent
import android.content.ContentValues
import android.content.Context
import android.webkit.CookieManager
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import assistant.android.ring.*
import assistant.android.whoop.WhoopStore
import assistant.android.location.LocationStore
import assistant.shell.ambient.AmbientBinding
import assistant.shell.ambient.AmbientQueue
import java.io.File
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Compiled only inside the pinned, pre-replacement shell tree on CI. All data is synthetic. */
@RunWith(AndroidJUnit4::class)
class LegacyUpgradeSeedTest {
    @Test fun seedOldUidKeysQueuesAndUnknownJournals() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val origin = InstrumentationRegistry.getArguments().getString("origin") ?: "https://upgrade.invalid"
        val owner = RingWorkoutBinding(origin, USER, "synthetic-ring", "synthetic-collector", ADDRESS, 7, "Europe/Moscow")
        context.getSharedPreferences("upgrade-fixture", Context.MODE_PRIVATE).edit()
            .putInt("uid", android.os.Process.myUid()).putString("origin", origin).commit()
        val ring = RingStore(context, origin)
        ring.put("grant", obj {
            put("user_id", USER); put("source_id", owner.sourceId); put("collector_id", owner.collectorId)
            put("generation", owner.grantGeneration); put("token", "synthetic-old-ring-credential")
        })
        ring.put("state", RingState(selected = obj { put("address", ADDRESS); put("name", "Synthetic old ring") }).json())
        ring.put("background", JsonPrimitive(true))
        ring.append(listOf(obj {
            put("external_id", "synthetic:upgrade:heart_rate"); put("metric", "heart_rate"); put("value", "72")
            put("start_at", "2026-10-18T10:00:00Z"); put("end_at", "2026-10-18T10:00:00Z")
            put("timezone", "Europe/Moscow"); put("unit", "bpm")
        }), obj { put("completed_queries", JsonArray(listOf(JsonPrimitive("synthetic:page:4")))) })
        val batch = requireNotNull(ring.batch(JsonNull))
        context.getSharedPreferences("upgrade-fixture", Context.MODE_PRIVATE).edit().putString("ring_batch", batch.toString()).commit()
        assertEquals(1, ring.count())
        ring.close()

        val workouts = RingWorkoutStore(context, origin)
        workouts.save(RingWorkoutState(phase = "idle", autoBlocked = true, commandUnknown = true))
        workouts.close()
        val device = RingDeviceStore(context, origin)
        val recovery = obj {
            put("identity", obj { put("model", "synthetic"); put("hardware", "hw"); put("firmware", "fw") })
            put("mutation_started", true)
            put("request", obj { put("operation_id", "synthetic-unknown-command")
                put("command", obj { put("type", "action"); put("action", "find_ring") }) })
        }
        device.verifiedIdentity(owner, recovery["identity"] as JsonObject, "synthetic-link")
        device.putRecovery(owner, recovery, "synthetic-link")
        val diagnostics = RingDiagnostics(context, origin)
        diagnostics.bind(owner.collectorId)
        diagnostics.append(diagnostics.session(), "upgrade_fixture", obj { put("status", "synthetic_pending") })
        assertEquals("The old shell must persist its synthetic diagnostic event before replacement", 1,
            diagnostics.status()["event_count"]!!.jsonPrimitive.int)
        diagnostics.close()

        val whoop = WhoopStore(context, origin)
        whoop.put("grant", obj { put("user_id", USER); put("source_id", "synthetic-whoop")
            put("collector_id", "synthetic-whoop-collector"); put("generation", 7); put("token", "synthetic-old-whoop-credential") })
        whoop.put("state", obj { put("selected", obj { put("address", ADDRESS); put("name", "Synthetic WHOOP") }) })
        whoop.put("background", JsonPrimitive(true))
        val frame = byteArrayOf(1, 2, 3, 4, 5, 6)
        val digest = sha256Bytes(frame)
        whoop.writableDatabase.insertOrThrow("segments", null, ContentValues().apply {
            put("digest", digest); put("size", frame.size)
            put("payload", RingCrypto("assistant.whoop.${sha256(origin)}").seal(frame, "${sha256(origin)}:segment:$digest"))
        })
        val whoopBatch = requireNotNull(whoop.batch(JsonNull, null))
        context.getSharedPreferences("upgrade-fixture", Context.MODE_PRIVATE).edit().putString("whoop_batch", whoopBatch.toString()).commit()
        whoop.close()

        val location = LocationStore(context, origin)
        location.saveGrant(obj { put("user_id", USER); put("installation_id", "synthetic-old-location")
            put("token", "synthetic-old-location-credential") })
        location.append(listOf(obj { put("captured_at", "2026-10-18T10:00:00Z")
            put("latitude", 55.75); put("longitude", 37.61); put("accuracy_meters", 12) })) { 1792317600000L }
        assertEquals(1, location.queued())
        location.close()

        val queue = AmbientQueue(File(context.filesDir, "ambient-audio"))
        queue.enqueue(AmbientBinding(origin, USER, DEVICE, "2099-01-01T00:00:00Z"), SESSION, 0,
            "2026-10-18T10:00:00Z", ByteArray(320) { 1 })
        File(context.filesDir, "ambient-audio/$PARTIAL.partial").mkdirs()
        File(context.filesDir, "ambient-audio/$PARTIAL.partial/interrupted").writeText("synthetic interrupted audio")
        context.getSharedPreferences("shell-notifications", Context.MODE_PRIVATE).edit()
            .putString("session", """{"instanceId":"synthetic-instance","userId":"$USER","deviceId":"$DEVICE","expiresAt":4070908800000,"previews":false}""")
            .putBoolean("enabled", true).putBoolean("asked", true)
            .putString("installed", """[{"key":"synthetic-reminder","id":314159,"fireAt":4070908800000,"title":"Synthetic reminder","body":"Synthetic body","link":"/timers"}]""").commit()
        val alarmIntent = Intent().setComponent(ComponentName(context.packageName, "assistant.shell.notify.NotifyReceiver"))
            .setAction("assistant.shell.notify.FIRE").putExtra("key", "synthetic-reminder")
        val pendingAlarm = requireNotNull(PendingIntent.getBroadcast(context, 314159, alarmIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
        // Inexact AlarmManager.set needs no exact-alarm access and exercises the real retired
        // receiver/action/request-code identity that native upgrade must cancel.
        context.getSystemService(AlarmManager::class.java).set(AlarmManager.RTC_WAKEUP, 4070908800000L, pendingAlarm)
        assertNotNull(PendingIntent.getBroadcast(context, 314159, alarmIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_NO_CREATE))
        context.getSharedPreferences("upgrade-fixture", Context.MODE_PRIVATE).edit().putBoolean("old_alarm_verified", true).commit()
        val cookieSet = CountDownLatch(1)
        var accepted = false
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            CookieManager.getInstance().setCookie(origin,
                "assistant_session=synthetic-old-cookie; Path=/; HttpOnly; Secure") { saved ->
                accepted = saved
                cookieSet.countDown()
            }
        }
        assertTrue("Old cookie setup timed out", cookieSet.await(10, TimeUnit.SECONDS))
        assertTrue("Old shell rejected synthetic cookie", accepted)
        CookieManager.getInstance().flush()
        assertTrue(CookieManager.getInstance().getCookie(origin)?.contains("assistant_session=synthetic-old-cookie") == true)
        context.getSharedPreferences("upgrade-fixture", Context.MODE_PRIVATE).edit().putBoolean("old_cookie_verified", true).commit()
    }

    private fun sha256Bytes(bytes: ByteArray) = java.security.MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { "%02x".format(it) }

    companion object {
        const val USER = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"
        const val DEVICE = "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"
        const val SESSION = "cccccccc-cccc-4ccc-8ccc-cccccccccccc"
        const val PARTIAL = "dddddddd-dddd-4ddd-8ddd-dddddddddddd"
        const val ADDRESS = "00:11:22:33:44:55"
    }
}
