package assistant.mobile.platform.ring

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RingStoreTest {
    private val origin = "https://synthetic-ring.example"
    private fun record(value: Int) = obj {
        put("external_id", "synthetic:one"); put("metric", "heart_rate"); put("value", value.toString())
        put("start_at", "2026-10-04T22:00:00Z"); put("end_at", "2026-10-04T22:00:00Z")
        put("timezone", "Europe/Moscow"); put("unit", "bpm")
    }
    private fun checkpoint(page: Int) = obj { put("completed_queries", JsonArray(listOf(JsonPrimitive("hr:0:$page")))) }
    private fun workoutOwner() = RingWorkoutBinding(origin, "owner", "source", "collector", "00:11:22:33:44:55", 1, "Europe/Moscow")
    private fun bindWorkout(store: RingStore, owner: RingWorkoutBinding) {
        store.put("grant", obj { put("generation", owner.grantGeneration); put("collector_id", owner.collectorId)
            put("source_id", owner.sourceId); put("user_id", owner.userId); put("token", "synthetic-token") })
        store.put("state", RingState(selected = obj { put("address", owner.address) }).json())
    }
    private fun ciphertext(store: RingStore): Map<String, List<List<String>>> = listOf(
        "metadata", "revisions", "outbox", "batches", "closed_bindings", "closed_metadata", "closed_revisions", "closed_outbox", "closed_batches",
    ).associateWith { table ->
        store.readableDatabase.rawQuery("SELECT * FROM $table ORDER BY rowid", null).use { rows ->
            buildList { while (rows.moveToNext()) add((0 until rows.columnCount).map { column ->
                if (rows.getType(column) == android.database.Cursor.FIELD_TYPE_BLOB)
                    rows.getBlob(column).joinToString("") { "%02x".format(it) }
                else rows.getString(column)
            }) }
        }
    }

    @Test fun concurrentLegacyHelpersUpgradeOnceWithoutChangingEncryptedRows() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val first = RingStore(context, origin)
        val second = RingStore(context, origin)
        val executor = Executors.newFixedThreadPool(2)
        try {
            repeat(4) {
                first.resetUnreadable()
                bindWorkout(first, workoutOwner())
                first.append(listOf(record(72)), checkpoint(4))
                val frozen = first.batch(JsonNull)!!
                val before = ciphertext(first)
                val db = first.writableDatabase
                // Recreate the shell's exact pre-PR26 schema around its encrypted payloads.
                for (table in listOf("closed_bindings", "closed_metadata", "closed_revisions", "closed_outbox", "closed_batches")) {
                    db.execSQL("DROP TABLE $table")
                }
                db.version = 1
                first.close(); second.close()
                val start = CountDownLatch(1)
                val reader = executor.submit<android.database.sqlite.SQLiteDatabase> {
                    check(start.await(10, TimeUnit.SECONDS))
                    first.readableDatabase
                }
                val writer = executor.submit<android.database.sqlite.SQLiteDatabase> {
                    check(start.await(10, TimeUnit.SECONDS))
                    second.writableDatabase
                }
                start.countDown()
                assertEquals(2, reader.get(10, TimeUnit.SECONDS).version)
                assertEquals(2, writer.get(10, TimeUnit.SECONDS).version)
                assertEquals(before, ciphertext(first))
                assertEquals(frozen, second.batch(JsonPrimitive("cursor-must-not-change")))
                second.close()
            }
        } finally {
            executor.shutdownNow(); executor.awaitTermination(10, TimeUnit.SECONDS)
            second.close(); first.resetUnreadable(); first.close()
        }
    }

    @Test fun closedBindingRetainsExactEncryptedQueueCheckpointAndReceiptAcrossRestartAndActiveClear() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        var store = RingStore(context, origin)
        val owner = workoutOwner()
        try {
            store.resetUnreadable(); bindWorkout(store, owner)
            store.append(listOf(record(72)), checkpoint(4))
            val frozen = store.batch(JsonNull)!!
            val ciphertext = store.readableDatabase.rawQuery("SELECT payload FROM outbox", null).use {
                assertTrue(it.moveToFirst()); it.getBlob(0)
            }
            val receipt = store.closeBinding("explicit_native_enrollment")!!
            assertEquals("closed_without_transfer", receipt.string("status"))
            assertEquals(1, receipt["records"]!!.jsonPrimitive.int)
            assertEquals(0, store.count()); assertNull(store.get("grant")); assertEquals(1, store.closedRecordCount())
            store.readableDatabase.rawQuery("SELECT payload FROM closed_outbox", null).use {
                assertTrue(it.moveToFirst()); assertArrayEquals(ciphertext, it.getBlob(0))
            }
            store.readableDatabase.rawQuery("SELECT payload FROM closed_batches", null).use {
                assertTrue(it.moveToFirst())
                val clear = RingCrypto("assistant.ring.${sha256(origin)}").open(it.getBlob(0), "${sha256(origin)}:batch:${frozen.string("key")}")
                assertEquals(frozen, ringJson.parseToJsonElement(clear.toString(Charsets.UTF_8)))
            }
            store.clear(); store.close(); store = RingStore(context, origin)
            assertEquals(listOf(receipt), store.closedBindings()); assertEquals(1, store.closedRecordCount())
            store.readableDatabase.rawQuery("SELECT value FROM closed_metadata WHERE key='checkpoint'", null).use {
                assertTrue(it.moveToFirst())
                val clear = RingCrypto("assistant.ring.${sha256(origin)}").open(it.getBlob(0), "${sha256(origin)}:meta:checkpoint")
                assertEquals(checkpoint(4), ringJson.parseToJsonElement(clear.toString(Charsets.UTF_8)))
            }
        } finally { store.resetUnreadable(); store.close() }
    }

    @Test fun offlineVersionsDrainInSeparateImmutableBatches() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        var store = RingStore(context, origin)
        try {
            store.resetUnreadable(); store.put("grant", obj { put("generation", 1) })
            store.append(listOf(record(72)), checkpoint(0))
            store.append(listOf(record(73)), checkpoint(1))
            store.append(listOf(record(73)), checkpoint(1))
            assertEquals(2, store.count())
            val first = store.batch(JsonNull)!!
            assertEquals(1, ((first["body"] as JsonObject)["samples"] as JsonArray).size)
            store.close(); store = RingStore(context, origin)
            assertEquals(checkpoint(1), store.get("checkpoint"))
            assertEquals(2, store.count())
            assertEquals(first, store.batch(JsonPrimitive("different_cursor"))) // sealed retries preserve the body
            val firstRow = ((first["body"] as JsonObject)["samples"] as JsonArray).first() as JsonObject
            assertEquals(1_000_001L, firstRow["version"]!!.jsonPrimitive.long)
            store.acknowledge(first)
            val second = store.batch((first["body"] as JsonObject)["cursor"]!!)!!
            val secondRow = ((second["body"] as JsonObject)["samples"] as JsonArray).first() as JsonObject
            assertEquals(1_000_002L, secondRow["version"]!!.jsonPrimitive.long)
            assertNotEquals(first.string("key"), second.string("key"))
            store.acknowledge(second); assertEquals(0, store.count())
        } finally { store.resetUnreadable(); store.close() }
    }

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val workoutOrigin = "https://workout-${UUID.randomUUID()}.synthetic.invalid"
    private val id = "498e5138-0808-4655-a291-681ca366fb41"
    private val owner get() = RingWorkoutBinding(workoutOrigin, "owner", "source", "collector", "00:11:22:33:44:55", 1, "Europe/Moscow")
    private fun clock(elapsed: Long, boot: String = "synthetic-boot", shift: Long = 0) = RingWorkoutClock(
        Instant.parse("2026-10-08T10:00:00Z").plusMillis(elapsed + shift), elapsed, boot, elapsed * 1_000_000 + 123)
    private fun recording() = RingWorkoutState(phase = "recording", session = RingWorkoutSession(id, owner, clock(1_000), 1_000, "manual"))
    private fun finish(store: RingWorkoutStore, stoppedAt: Long = 5_000) {
        store.save(RingWorkoutReducer.manualStop(store.load(), clock(stoppedAt)).state)
        val stopping = RingWorkoutReducer.tick(store.load(), clock(stoppedAt + 180_000)).state
        store.save(stopping)
        store.save(RingWorkoutReducer.commandResult(stopping, RingWorkoutCommand.STOP, true, clock(stoppedAt + 180_001)).state)
    }
    private fun chunks(records: List<JsonObject>) = records.filter { it.string("workout_id") != null }
    private fun samples(records: List<JsonObject>) = chunks(records).flatMap { (it["samples"] as JsonArray).map { point -> point as JsonObject } }
    private fun bindOutbox(store: RingStore, binding: RingWorkoutBinding = owner) {
        store.put("grant", obj { put("generation", binding.grantGeneration); put("collector_id", binding.collectorId)
            put("source_id", binding.sourceId); put("user_id", binding.userId); put("token", "synthetic-token") })
        store.put("state", RingState(selected = obj { put("address", binding.address) }).json())
    }
    private fun transferRuntime(binding: () -> RingWorkoutBinding?,
        transfer: (RingWorkoutBinding, List<JsonObject>) -> RingWorkoutTransferReceipt?,
        retry: (RingWorkoutTransferRetry, () -> Unit) -> Boolean = { _, _ -> false }) = RingMeasurementRuntime(context, workoutOrigin, binding,
        { obj { put("capabilities", JsonArray(emptyList())) } },
        { _, _, _, _ -> error("Sealed workout recovery must not submit a radio command") }, transfer,
        retryTransfer = retry)

    @Test fun crashAfterCommonCopyBeforeProducerAckReplaysTheExactBatchWithoutAnotherVersion() {
        var producer = RingWorkoutStore(context, workoutOrigin)
        var outbox = RingStore(context, workoutOrigin)
        var runtime: RingMeasurementRuntime? = null
        val support = setOf("running_workouts", "workout_hr_chunks", "workout_track_chunks")
        try {
            outbox.resetUnreadable(); bindOutbox(outbox)
            producer.save(recording()); assertTrue(producer.appendPoint(owner, clock(2_000), 72)); finish(producer)
            val frozen = producer.pendingRecords(owner)
            runtime = transferRuntime({ owner }, { expected, records ->
                assertNotNull(outbox.appendWorkoutRecords(expected, records) { owner })
                throw RingFailure("unavailable", "Synthetic process death before the producer receipt")
            }, { _, _ -> true })
            assertEquals(frozen, producer.pendingRecords(owner)); assertEquals(frozen.size, outbox.count())
            val originalBatch = outbox.batch(JsonNull, support)!!
            runtime.close(); runtime = null; producer.close(); outbox.close()
            producer = RingWorkoutStore(context, workoutOrigin); outbox = RingStore(context, workoutOrigin)
            val rotated = owner.copy(grantGeneration = 2)
            bindOutbox(outbox, rotated)
            runtime = transferRuntime({ rotated }, { expected, records -> outbox.appendWorkoutRecords(expected, records) { rotated } })
            assertTrue(producer.pendingRecords(owner).isEmpty()); assertEquals(frozen.size, outbox.count())
            assertEquals(originalBatch, outbox.batch(JsonPrimitive("changed-server-cursor"), support))
            outbox.acknowledge(originalBatch); assertEquals(0, outbox.count())
            producer.save(producer.load()); assertTrue(producer.pendingRecords(owner).isEmpty())
            runtime.workoutSnapshot(); assertEquals(0, outbox.count())
        } finally { runtime?.close(); producer.resetUnreadable(); producer.close(); outbox.resetUnreadable(); outbox.close() }
    }

    @Test fun encryptedPointCommitsBeforeExposureAndSurvivesProcessReopen() {
        var store = RingWorkoutStore(context, workoutOrigin)
        try {
            store.save(recording())
            assertTrue(store.appendPoint(owner, clock(1_001), 72))
            context.openOrCreateDatabase("ring-workouts-${sha256(workoutOrigin)}.sqlite", Context.MODE_PRIVATE, null).use { db ->
                db.rawQuery("SELECT payload FROM points", null).use { rows ->
                    assertTrue(rows.moveToFirst()); val cipher = rows.getBlob(0)
                    assertEquals(1, cipher[0].toInt()); assertFalse(cipher.toString(Charsets.UTF_8).contains("elapsed_ms"))
                    assertFalse(cipher.toString(Charsets.UTF_8).contains("valid"))
                }
            }
            assertTrue(store.pendingRecords(owner).isEmpty()) // Chunks wait for their descriptor.
            store.close(); store = RingWorkoutStore(context, workoutOrigin)
            assertEquals(1_001L, store.load().session!!.lastObservedElapsedMs)
            assertEquals(clock(1_000), store.load().session!!.anchor)
            store.save(RingWorkoutReducer.recover(store.load(), clock(2_000)).state)
            val records = store.pendingRecords(owner)
            assertEquals("running_workouts", records.first().string("kind")); assertEquals(72, samples(records).single().number("bpm"))
            assertEquals("interrupted", (((records.first()["report"] as JsonObject)["origin"] as JsonObject)["details"] as JsonObject).string("status"))
            assertTrue(store.load().commandUnknown); assertEquals(RingWorkoutCommand.STOP, store.load().pendingCommand)
        } finally { store.resetUnreadable(); store.close() }
    }

    @Test fun anotherAccountOrSourceCannotCollectOrAcknowledgeButGrantRotationCanDrain() {
        val store = RingWorkoutStore(context, workoutOrigin)
        try {
            store.save(recording())
            val another = owner.copy(userId = "another")
            assertFalse(store.appendPoint(another, clock(2_000), 90)); assertTrue(store.appendPoint(owner, clock(2_000), 90))
            finish(store)
            val frozen = store.pendingRecords(owner)
            assertTrue(store.pendingRecords(another).isEmpty()); assertTrue(store.pendingRecords(owner.copy(sourceId = "other-source")).isEmpty())
            try { store.acknowledge(setOf(frozen.first().string("external_id")!!), another); fail("Foreign receipt") }
            catch (failure: RingFailure) { assertEquals("invalid_binding", failure.code) }
            assertEquals(frozen, store.pendingRecords(owner))
            val rotated = owner.copy(grantGeneration = 2)
            assertEquals(frozen, store.pendingRecords(rotated))
            store.acknowledge(frozen.map { it.string("external_id")!! }.toSet(), rotated)
            assertTrue(store.pendingRecords(owner).isEmpty())
        } finally { store.resetUnreadable(); store.close() }
    }

    @Test fun closeOldBindingThenNewNativeOwnerCleanupRetainsClosedWorkoutAndPreventsReplay() {
        var producer = RingWorkoutStore(context, workoutOrigin)
        var runtime: RingMeasurementRuntime? = null
        val next = owner.copy(userId = "next-owner", sourceId = "next-source", collectorId = "next-collector", grantGeneration = 2)
        try {
            producer.save(recording())
            assertTrue(producer.appendPoint(owner, clock(2_000), 72))
            assertTrue(producer.appendTrackPoint(owner, clock(2_000), RingTrackPoint(clock(2_000).elapsedRealtimeNanos, 55.0, 37.0, 5.0, "valid")))
            finish(producer)
            val frozen = producer.pendingRecords(owner)
            assertTrue(frozen.isNotEmpty())
            producer.closeBinding(owner)
            assertEquals(frozen, producer.pendingRecords(owner, includeClosed = true))
            assertTrue(producer.pendingRecords(owner).isEmpty())
            assertTrue(producer.pendingRecords(owner.copy(grantGeneration = 2)).isEmpty())
            val nextId = UUID.randomUUID().toString()
            producer.save(RingWorkoutState(phase = "recording", session = RingWorkoutSession(nextId, next, clock(1_000), 1_000, "manual")))
            assertTrue(producer.appendPoint(next, clock(2_000), 80)); finish(producer)
            assertTrue(producer.pendingRecords(next).isNotEmpty())
            runtime = transferRuntime({ next }, { _, _ -> throw RingFailure("queue_full", "Synthetic pending native outbox") })
            runtime.bindingChanged(clear = true, clearBinding = next)
            assertTrue(producer.pendingRecords(next).isEmpty())
            assertEquals(frozen, producer.pendingRecords(owner, includeClosed = true))
            runtime.close(); runtime = null; producer.close(); producer = RingWorkoutStore(context, workoutOrigin)
            assertEquals(frozen, producer.pendingRecords(owner, includeClosed = true))
            assertTrue(producer.pendingRecords(next).isEmpty()); assertNull(producer.load().session)
        } finally { runtime?.close(); producer.resetUnreadable(); producer.close() }
    }
}
