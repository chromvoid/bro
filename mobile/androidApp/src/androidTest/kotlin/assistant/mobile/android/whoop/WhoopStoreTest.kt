package assistant.mobile.android.whoop

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.Base64
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Real SQLite and Android Keystore, isolated from the app collector and all family data. */
@RunWith(AndroidJUnit4::class)
class WhoopStoreTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val origin = "https://synthetic-whoop.invalid"
    private val name = "whoop-test-${UUID.randomUUID()}.sqlite"
    private var store = WhoopStore(context, origin, name)

    @After fun cleanup() {
        try { store.resetUnreadable() } finally { store.close(); context.deleteDatabase(name) }
    }

    private fun ciphertext(): Map<String, List<List<String>>> = listOf(
        "metadata", "segments", "batches", "closed_bindings", "closed_metadata", "closed_segments", "closed_batches",
    ).associateWith { table ->
        store.readableDatabase.rawQuery("SELECT * FROM $table ORDER BY rowid", null).use { rows ->
            buildList { while (rows.moveToNext()) add((0 until rows.columnCount).map { column ->
                if (rows.getType(column) == android.database.Cursor.FIELD_TYPE_BLOB)
                    rows.getBlob(column).joinToString("") { "%02x".format(it) }
                else rows.getString(column)
            }) }
        }
    }

    @Test fun concurrentLegacyHelpersUpgradeOnceWithoutChangingGrantFramesOrFrozenBatch() {
        val other = WhoopStore(context, origin, name)
        val executor = Executors.newFixedThreadPool(2)
        try {
            repeat(4) {
                store.resetUnreadable()
                val grant = obj { put("user_id", "synthetic-owner"); put("source_id", "synthetic-source")
                    put("collector_id", "synthetic-collector"); put("token", "synthetic-only") }
                store.put("grant", grant)
                store.append(listOf(byteArrayOf(0xAA.toByte(), 1, 2, 3), byteArrayOf(4, 5, 6)))
                val frozen = requireNotNull(store.batch(JsonNull, null))
                val before = ciphertext()
                val db = store.writableDatabase
                // Recreate the exact legacy schema around the existing authenticated rows.
                for (table in listOf("closed_bindings", "closed_metadata", "closed_segments", "closed_batches"))
                    db.execSQL("DROP TABLE $table")
                db.version = 1
                store.close(); other.close()
                val ready = CountDownLatch(2)
                val start = CountDownLatch(1)
                val reader = executor.submit<android.database.sqlite.SQLiteDatabase> {
                    ready.countDown(); check(start.await(10, TimeUnit.SECONDS))
                    store.readableDatabase
                }
                val writer = executor.submit<android.database.sqlite.SQLiteDatabase> {
                    ready.countDown(); check(start.await(10, TimeUnit.SECONDS))
                    other.writableDatabase
                }
                assertTrue("Both legacy helpers must reach the migration gate", ready.await(10, TimeUnit.SECONDS))
                start.countDown()
                assertEquals(2, reader.get(10, TimeUnit.SECONDS).version)
                assertEquals(2, writer.get(10, TimeUnit.SECONDS).version)
                assertEquals(before, ciphertext())
                assertEquals(grant, other.get("grant"))
                assertEquals(1, other.count()); assertEquals(7L, other.bytes())
                assertEquals(frozen, other.batch(JsonPrimitive("cursor-must-not-change"), null))
                other.close()
            }
        } finally {
            executor.shutdownNow(); executor.awaitTermination(10, TimeUnit.SECONDS)
            other.close()
        }
    }

    @Test fun exactFramesAreDeduplicatedAndFrozenBatchSurvivesRestart() {
        val frames = listOf(byteArrayOf(0xAA.toByte(), 1, 2, 3), byteArrayOf(4, 5, 6))
        assertTrue(store.append(frames))
        assertTrue(store.append(frames))
        assertEquals(1, store.count())
        assertEquals(7L, store.bytes())
        val batch = store.batch(JsonNull, null)!!
        val segment = ((batch["body"] as JsonObject)["whoop_segments"] as JsonArray).single() as JsonObject
        assertArrayEquals(frames[0] + frames[1], Base64.getDecoder().decode(segment.string("frames")))
        store.close()
        store = WhoopStore(context, origin, name)
        assertEquals(batch, store.batch(JsonPrimitive("changed-server-cursor"), obj { put("battery_percent", 20) }))
    }

    @Test fun originBindingCannotReadAnotherOriginCiphertext() {
        store.put("grant", JsonPrimitive("synthetic-secret"))
        val other = WhoopStore(context, "https://another-synthetic-origin.invalid", name)
        try {
            try { other.get("grant"); fail("Origin binding must authenticate every value") } catch (_: Exception) { }
        } finally {
            other.close()
            WhoopCrypto("assistant.native.whoop.${sha256("https://another-synthetic-origin.invalid")}.$name").delete()
        }
        assertEquals(JsonPrimitive("synthetic-secret"), store.get("grant"))
    }
}
