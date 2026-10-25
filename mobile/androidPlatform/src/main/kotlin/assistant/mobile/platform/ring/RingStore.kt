package assistant.mobile.platform.ring

import assistant.mobile.platform.R

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.util.UUID
import kotlinx.serialization.json.*

/** One installation/origin binding. All values and payloads are authenticated ciphertext. */
class RingStore(private val context: Context, origin: String) : SQLiteOpenHelper(context, "ring-v1.sqlite", null, 2) {
    init { RingText.initialize(context) }
    private val binding = sha256(origin)
    private val crypto = RingCrypto("assistant.ring.$binding")
    private val cleanup = context.getSharedPreferences("ring-cleanup", Context.MODE_PRIVATE)
    /** This non-secret fence must survive process death while the executor is still waiting on HTTP. */
    fun forgetPending(): Boolean = cleanup.getBoolean(binding, false)
    fun markForgetPending() {
        if (!cleanup.edit().putBoolean(binding, true).commit())
            throw RingFailure("store_unavailable", RingText.text(R.string.ring_native_4591b38a59dc))
    }
    private fun finishForget() {
        if (!cleanup.edit().remove(binding).commit())
            throw RingFailure("store_unavailable", RingText.text(R.string.ring_native_2823320f9c04))
    }
    override fun onCreate(db: SQLiteDatabase) {
        // SQLiteOpenHelper reads user_version before beginTransaction. A separate helper
        // may commit the target schema while this callback waits for that write transaction.
        val version = db.version
        if (version == 2) return
        check(version == 0) { "Unexpected ring store creation version" }
        db.execSQL("CREATE TABLE metadata (key TEXT PRIMARY KEY, value BLOB NOT NULL)")
        db.execSQL("CREATE TABLE revisions (identity TEXT PRIMARY KEY, hash TEXT NOT NULL, version INTEGER NOT NULL)")
        db.execSQL("CREATE TABLE outbox (id INTEGER PRIMARY KEY AUTOINCREMENT, identity TEXT NOT NULL, version INTEGER NOT NULL, payload BLOB NOT NULL, UNIQUE(identity,version))")
        db.execSQL("CREATE TABLE batches (key TEXT PRIMARY KEY, payload BLOB NOT NULL)")
        createClosedBindings(db)
    }
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        check(oldVersion == 1 && newVersion == 2) { "Unsupported ring store version" }
        val version = db.version
        if (version == newVersion) return
        check(version == oldVersion) { "Unexpected ring store migration version" }
        createClosedBindings(db)
    }
    private fun createClosedBindings(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE closed_bindings (id TEXT PRIMARY KEY, receipt BLOB NOT NULL)")
        db.execSQL("CREATE TABLE closed_metadata (closure TEXT NOT NULL, key TEXT NOT NULL, value BLOB NOT NULL, PRIMARY KEY(closure,key))")
        db.execSQL("CREATE TABLE closed_revisions (closure TEXT NOT NULL, identity TEXT NOT NULL, hash TEXT NOT NULL, version INTEGER NOT NULL, PRIMARY KEY(closure,identity))")
        db.execSQL("CREATE TABLE closed_outbox (closure TEXT NOT NULL, id INTEGER NOT NULL, identity TEXT NOT NULL, version INTEGER NOT NULL, payload BLOB NOT NULL, PRIMARY KEY(closure,id))")
        db.execSQL("CREATE TABLE closed_batches (closure TEXT NOT NULL, key TEXT NOT NULL, payload BLOB NOT NULL, PRIMARY KEY(closure,key))")
    }
    /** Explicit native enrollment closes an unproven binding without transferring or deleting its data. */
    @Synchronized fun closeBinding(reason: String): JsonObject? {
        val original = get("grant") as? JsonObject ?: return null
        val id = UUID.randomUUID().toString()
        val receipt = obj {
            put("id", id); put("reason", reason); put("status", "closed_without_transfer")
            put("user_id", original["user_id"] ?: JsonNull); put("source_id", original["source_id"] ?: JsonNull)
            put("collector_id", original["collector_id"] ?: JsonNull); put("records", count())
        }
        val db = writableDatabase; db.beginTransaction()
        try {
            db.insertOrThrow("closed_bindings", null, ContentValues().apply {
                put("id", id); put("receipt", seal(receipt, "closed:$id"))
            })
            for ((table, columns) in listOf("metadata" to "key,value", "revisions" to "identity,hash,version",
                "outbox" to "id,identity,version,payload", "batches" to "key,payload")) {
                db.execSQL("INSERT INTO closed_$table (closure,$columns) SELECT ?,$columns FROM $table", arrayOf(id))
                db.delete(table, null, null)
            }
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
        return receipt
    }
    @Synchronized fun closedRecordCount(): Int = readableDatabase.rawQuery("SELECT COUNT(*) FROM closed_outbox", null).use {
        it.moveToFirst(); it.getInt(0)
    }
    @Synchronized fun closedBindings(): List<JsonObject> = readableDatabase.rawQuery("SELECT id,receipt FROM closed_bindings ORDER BY rowid", null).use {
        buildList { while (it.moveToNext()) add(open(it.getBlob(1), "closed:${it.getString(0)}") as JsonObject) }
    }
    private fun seal(value: JsonElement, tag: String) = crypto.seal(value.toString().toByteArray(), "$binding:$tag")
    private fun open(value: ByteArray, tag: String): JsonElement = ringJson.parseToJsonElement(crypto.open(value, "$binding:$tag").toString(Charsets.UTF_8))
    @Synchronized fun get(key: String): JsonElement? = readableDatabase.rawQuery("SELECT value FROM metadata WHERE key=?", arrayOf(key)).use {
        if (it.moveToFirst()) open(it.getBlob(0), "meta:$key") else null
    }
    @Synchronized fun put(key: String, value: JsonElement) {
        val row = writableDatabase.insertWithOnConflict("metadata", null, ContentValues().apply {
            put("key", key); put("value", seal(value, "meta:$key"))
        }, SQLiteDatabase.CONFLICT_REPLACE)
        if (row == -1L) throw RingFailure("store_unavailable", RingText.text(R.string.ring_native_7d466bcd9b9f))
    }
    @Synchronized fun count(): Int = readableDatabase.rawQuery("SELECT COUNT(*) FROM outbox", null).use { it.moveToFirst(); it.getInt(0) }
    @Synchronized fun bytes(): Long = readableDatabase.rawQuery("SELECT COALESCE(SUM(LENGTH(payload)),0) FROM outbox", null).use { it.moveToFirst(); it.getLong(0) }

    /** Presence checks include unreadable archives without opening any authenticated payload. */
    @Synchronized fun hasData(): Boolean = forgetPending() || readableDatabase.rawQuery(
        "SELECT EXISTS(SELECT 1 FROM metadata WHERE key<>'state') OR " +
            listOf("revisions", "outbox", "batches", "closed_bindings", "closed_metadata", "closed_revisions",
                "closed_outbox", "closed_batches").joinToString(" OR ") { "EXISTS(SELECT 1 FROM $it)" },
        null,
    ).use { it.moveToFirst(); it.getInt(0) != 0 }

    /** Binding checks and the current BLE checkpoint share the durable outbox transaction. */
    @Synchronized fun appendWorkoutRecords(expected: RingWorkoutBinding, records: List<JsonObject>,
        currentBinding: () -> RingWorkoutBinding?): RingWorkoutTransferReceipt? {
        val db = writableDatabase
        db.beginTransaction()
        val receipt: RingWorkoutTransferReceipt
        try {
            fun ownsBinding(): Boolean {
                if (forgetPending() || sha256(expected.origin) != binding || currentBinding() != expected) return false
                val grant = get("grant") as? JsonObject ?: return false
                val selected = (get("state") as? JsonObject)?.get("selected") as? JsonObject ?: return false
                return grant.string("user_id") == expected.userId && grant.string("source_id") == expected.sourceId &&
                    grant.string("collector_id") == expected.collectorId &&
                    grant["generation"]?.jsonPrimitive?.longOrNull == expected.grantGeneration &&
                    selected.string("address")?.uppercase() == expected.address.uppercase()
            }
            if (!ownsBinding()) return null
            val ids = records.map { it.string("external_id") ?: throw RingFailure("invalid_record", RingText.text(R.string.ring_native_11053cce5226)) }
            if (ids.isEmpty() || ids.toSet().size != ids.size)
                throw RingFailure("invalid_record", RingText.text(R.string.ring_native_e9d8958a385b))
            val outcomes = append(records, get("checkpoint") ?: obj { put("completed_queries", JsonArray(emptyList())) })
            if (!ownsBinding()) throw RingFailure("invalid_binding", RingText.text(R.string.ring_native_b4a808e1ff08))
            if (outcomes.map { it.string("external_id") }.toSet() != ids.toSet())
                throw RingFailure("invalid_record", RingText.text(R.string.ring_native_cb866d9c169a))
            receipt = RingWorkoutTransferReceipt(expected.uploadIdentity, ids.toSet())
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
        return receipt
    }

    /** Records and the read checkpoint commit together; no checkpoint can skip an unpersisted record. */
    @Synchronized fun append(records: List<JsonObject>, checkpoint: JsonElement): List<JsonObject> {
        val outcomes = mutableListOf<JsonObject>()
        val db = writableDatabase
        db.beginTransaction()
        try {
            var size = bytes(); var count = count()
            records.forEach { record ->
                val external = record.string("external_id") ?: throw RingFailure("invalid_record", RingText.text(R.string.ring_native_120544250632))
                val identity = sha256(external)
                val semantic = JsonObject(record.filterKeys { it != "version" })
                val hash = observationFingerprint(semantic)
                val old = db.rawQuery("SELECT hash,version FROM revisions WHERE identity=?", arrayOf(identity)).use {
                    if (it.moveToFirst()) it.getString(0) to it.getLong(1) else null
                }
                val immutable = record["device_state"] is JsonObject || record["points"] is JsonArray && record.string("workout_id") != null
                if (old != null && old.first == hash) {
                    outcomes += obj { put("external_id", external); put("version", old.second); put("status", "deduplicated") }
                    return@forEach
                }
                if (immutable && old != null) throw RingFailure("immutable_record_changed", RingText.text(R.string.ring_native_b9beb7c260c9))
                val grantGeneration = (get("grant") as? JsonObject)?.get("generation")?.jsonPrimitive?.longOrNull
                    ?: throw RingFailure("invalid_binding", RingText.text(R.string.ring_native_a49de59e34fc))
                val version = RingPolicy.sourceVersion(grantGeneration, ((old?.second ?: 0) % 1_000_000) + 1)
                val value = if (immutable) semantic else JsonObject(semantic + ("version" to JsonPrimitive(version)))
                val payload = seal(value, "record:$identity:$version")
                if (count + 1 > RingPolicy.MAX_RECORDS || size + payload.size > RingPolicy.MAX_BYTES) {
                    throw RingFailure("queue_full", RingText.text(R.string.ring_native_ef08532b04f3))
                }
                db.insertOrThrow("outbox", null, ContentValues().apply {
                    put("identity", identity); put("version", version); put("payload", payload)
                })
                db.insertWithOnConflict("revisions", null, ContentValues().apply {
                    put("identity", identity); put("version", version); put("hash", hash)
                }, SQLiteDatabase.CONFLICT_REPLACE)
                count++; size += payload.size
                outcomes += obj { put("external_id", external); put("version", version); put("status", if (old == null) "new" else "revised") }
            }
            put("checkpoint", checkpoint)
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
        return outcomes
    }

    /** A batch body is durable and immutable under its key, including retry after a lost response. */
    @Synchronized fun batch(serverCursor: JsonElement, activityKinds: Set<String> = emptySet()): JsonObject? {
        readableDatabase.rawQuery("SELECT key,payload FROM batches LIMIT 1", null).use {
            if (it.moveToFirst()) {
                val frozen = open(it.getBlob(1), "batch:${it.getString(0)}") as JsonObject
                return if (RingBatchRecords.supportedBody(frozen["body"] as JsonObject, activityKinds)) frozen else null
            }
        }
        val rows = mutableListOf<JsonObject>(); val ids = mutableListOf<JsonElement>()
        val cursor = UUID.randomUUID().toString()
        fun body(records: List<JsonObject>) = RingBatchRecords.body(records, serverCursor, cursor)
        // A packet cannot contain two revisions of one logical observation. Older revisions drain first.
        // Unsupported extensions are retained, but must not starve ordinary observations behind them.
        readableDatabase.rawQuery("SELECT id,identity,version,payload FROM outbox WHERE id IN (SELECT MIN(id) FROM outbox GROUP BY identity) ORDER BY id", null).use {
            while (it.moveToNext()) {
                val record = open(it.getBlob(3), "record:${it.getString(1)}:${it.getLong(2)}") as JsonObject
                if (!RingBatchRecords.supported(record, activityKinds)) continue
                // device_state is a singular wire field. Every queued observation still gets its own ACK.
                if (record["device_state"] is JsonObject && rows.any { row -> row["device_state"] is JsonObject }) continue
                if (body(rows + record).toString().toByteArray(Charsets.UTF_8).size > RingPolicy.MAX_BATCH_BYTES) {
                    if (rows.isEmpty()) throw RingFailure("record_too_large", RingText.text(R.string.ring_native_d1ae86edb467))
                    break
                }
                rows += record
                ids += JsonPrimitive(it.getLong(0))
                if (rows.size == RingPolicy.BATCH_SIZE) break
            }
        }
        if (rows.isEmpty()) return null
        val key = UUID.randomUUID().toString()
        val batch = obj { put("key", key); put("body", body(rows)); put("rows", JsonArray(ids)) }
        saveBatch(batch)
        return batch
    }
    private fun saveBatch(batch: JsonObject) {
        val key = batch.string("key")!!
        writableDatabase.insertOrThrow("batches", null, ContentValues().apply { put("key", key); put("payload", seal(batch, "batch:$key")) })
    }
    @Synchronized fun rebase(batch: JsonObject, cursor: JsonElement): JsonObject {
        val db = writableDatabase; db.beginTransaction()
        try {
            db.delete("batches", "key=?", arrayOf(batch.string("key")!!))
            val old = batch["body"] as JsonObject
            val next = JsonObject(batch + ("key" to JsonPrimitive(UUID.randomUUID().toString())) +
                ("body" to JsonObject(old + ("expected_cursor" to cursor))))
            saveBatch(next); db.setTransactionSuccessful(); return next
        } finally { db.endTransaction() }
    }
    @Synchronized fun acknowledge(batch: JsonObject) {
        val db = writableDatabase; db.beginTransaction()
        try {
            val key = batch.string("key") ?: throw RingFailure("invalid_batch", RingText.text(R.string.ring_native_ef90352a0ba9))
            val saved = db.rawQuery("SELECT payload FROM batches WHERE key=?", arrayOf(key)).use {
                if (it.moveToFirst()) open(it.getBlob(0), "batch:$key") as JsonObject else null
            }
            if (saved != batch) throw RingFailure("invalid_batch", RingText.text(R.string.ring_native_e81866c0112e))
            val ids = batch["rows"] as JsonArray
            val records = RingBatchRecords.records(batch["body"] as JsonObject)
            if (ids.size != records.size || ids.distinct().size != ids.size || records.size !in 1..RingPolicy.BATCH_SIZE)
                throw RingFailure("invalid_batch", RingText.text(R.string.ring_native_d3cc1a6474d9))
            val serialized = records.map(::canonical).sorted()
            val stored = ids.map { id -> db.rawQuery("SELECT identity,version,payload FROM outbox WHERE id=?", arrayOf(id.jsonPrimitive.content)).use {
                if (!it.moveToFirst()) throw RingFailure("invalid_batch", RingText.text(R.string.ring_native_1c8b6122b2ab))
                canonical(open(it.getBlob(2), "record:${it.getString(0)}:${it.getLong(1)}"))
            } }.sorted()
            if (serialized != stored) throw RingFailure("invalid_batch", RingText.text(R.string.ring_native_0b33a26aa421))
            ids.forEach { db.delete("outbox", "id=?", arrayOf(it.jsonPrimitive.content)) }
            db.delete("batches", "key=?", arrayOf(key))
            put("server_cursor", (batch["body"] as JsonObject)["cursor"]!!)
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }
    @Synchronized fun clear() {
        // Observation data is deleted; an uncertain radio mutation must remain fenced after forgetting.
        val db = writableDatabase; db.beginTransaction()
        var preserveKey = true
        try {
            // Authenticate the active ownership metadata and every retained closure before deleting
            // anything. Lost keys or unreadable receipts cannot authorize an origin-wide reset.
            db.rawQuery("SELECT key,value FROM metadata", null).use { rows ->
                while (rows.moveToNext()) open(rows.getBlob(1), "meta:${rows.getString(0)}")
            }
            val preserveControl = get("control_recovery") is JsonObject
            preserveKey = closedBindings().isNotEmpty() || preserveControl
            db.delete("metadata", if (preserveControl) "key<>?" else null, if (preserveControl) arrayOf("control_recovery") else null)
            listOf("revisions", "outbox", "batches").forEach { db.delete(it, null, null) }
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
        if (!preserveKey) crypto.delete()
        finishForget()
    }
    /** Explicit server departure also erases closed bindings and physical command recovery. */
    @Synchronized fun clearForServerSwitch() {
        close()
        check(context.deleteDatabase("ring-v1.sqlite") || !context.getDatabasePath("ring-v1.sqlite").exists()) {
            "Ring database could not be cleared"
        }
        crypto.delete()
        finishForget()
    }
    /** Synthetic fixture cleanup only. */
    @Synchronized fun resetUnreadable() = clearForServerSwitch()

}
