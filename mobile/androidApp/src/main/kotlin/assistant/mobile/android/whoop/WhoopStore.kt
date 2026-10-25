package assistant.mobile.android.whoop

import android.content.ContentValues
import android.content.Context
import assistant.mobile.android.R
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.security.MessageDigest
import java.util.Base64
import java.util.UUID
import kotlinx.serialization.json.*

/**
 * The strap's raw history waiting for the server, plus this phone's binding. Every value is
 * authenticated ciphertext under a Keystore key; frames are kept exactly as the strap sent them.
 */
internal class WhoopStore(private val context: Context, origin: String, private val databaseName: String = NAME) : SQLiteOpenHelper(context, databaseName, null, 2) {
    private val binding = sha256(origin)
    private val crypto = WhoopCrypto(if (databaseName == NAME) "assistant.whoop.$binding" else "assistant.native.whoop.$binding.$databaseName")
    private val cleanup = context.getSharedPreferences("whoop-cleanup", Context.MODE_PRIVATE)

    fun forgetPending(): Boolean = cleanup.getBoolean(binding, false)
    /** Repository startup persists an empty UI state; it does not establish a server binding. */
    @Synchronized fun hasData(): Boolean = forgetPending() || readableDatabase.rawQuery(
        "SELECT EXISTS(SELECT 1 FROM metadata WHERE key<>'state' UNION ALL SELECT 1 FROM segments UNION ALL SELECT 1 FROM batches " +
            "UNION ALL SELECT 1 FROM closed_bindings UNION ALL SELECT 1 FROM closed_metadata " +
            "UNION ALL SELECT 1 FROM closed_segments UNION ALL SELECT 1 FROM closed_batches)", null,
    ).use { it.moveToFirst(); it.getInt(0) != 0 }
    fun markForgetPending() {
        if (!cleanup.edit().putBoolean(binding, true).commit())
            throw WhoopFailure("store_unavailable", context.getString(R.string.whoop_runtime_store_unavailable))
    }
    override fun onCreate(db: SQLiteDatabase) {
        // The callback owns the write transaction; the helper's earlier version read
        // may be stale after another helper committed its complete target schema.
        val version = db.version
        if (version == 2) return
        check(version == 0) { "Unexpected WHOOP store creation version" }
        db.execSQL("CREATE TABLE metadata (key TEXT PRIMARY KEY, value BLOB NOT NULL)")
        db.execSQL("CREATE TABLE segments (id INTEGER PRIMARY KEY AUTOINCREMENT, digest TEXT NOT NULL UNIQUE, size INTEGER NOT NULL, payload BLOB NOT NULL)")
        db.execSQL("CREATE TABLE batches (key TEXT PRIMARY KEY, payload BLOB NOT NULL)")
        createClosedBindings(db)
    }
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        check(oldVersion == 1 && newVersion == 2) { "Unsupported WHOOP store version" }
        val version = db.version
        if (version == newVersion) return
        check(version == oldVersion) { "Unexpected WHOOP store migration version" }
        createClosedBindings(db)
    }
    private fun createClosedBindings(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE closed_bindings (id TEXT PRIMARY KEY, receipt BLOB NOT NULL)")
        db.execSQL("CREATE TABLE closed_metadata (closure TEXT NOT NULL, key TEXT NOT NULL, value BLOB NOT NULL, PRIMARY KEY(closure,key))")
        db.execSQL("CREATE TABLE closed_segments (closure TEXT NOT NULL, id INTEGER NOT NULL, digest TEXT NOT NULL, size INTEGER NOT NULL, payload BLOB NOT NULL, PRIMARY KEY(closure,id))")
        db.execSQL("CREATE TABLE closed_batches (closure TEXT NOT NULL, key TEXT NOT NULL, payload BLOB NOT NULL, PRIMARY KEY(closure,key))")
    }
    /** Only an explicit native enrollment closes the old queue; its ciphertext remains under the old key. */
    @Synchronized fun closeBinding(reason: String): JsonObject? {
        val original = get("grant") as? JsonObject ?: return null
        val id = UUID.randomUUID().toString()
        val receipt = obj {
            put("id", id); put("reason", reason); put("status", "closed_without_transfer")
            put("user_id", original["user_id"] ?: JsonNull); put("source_id", original["source_id"] ?: JsonNull)
            put("collector_id", original["collector_id"] ?: JsonNull); put("records", count()); put("bytes", bytes())
        }
        val db = writableDatabase; db.beginTransaction()
        try {
            db.insertOrThrow("closed_bindings", null, ContentValues().apply {
                put("id", id); put("receipt", seal(receipt.toString().toByteArray(), "closed:$id"))
            })
            for ((table, columns) in listOf("metadata" to "key,value", "segments" to "id,digest,size,payload", "batches" to "key,payload")) {
                db.execSQL("INSERT INTO closed_$table (closure,$columns) SELECT ?,$columns FROM $table", arrayOf(id))
                db.delete(table, null, null)
            }
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
        return receipt
    }
    @Synchronized fun closedRecordCount(): Int = readableDatabase.rawQuery("SELECT COUNT(*) FROM closed_segments", null).use {
        it.moveToFirst(); it.getInt(0)
    }
    @Synchronized fun closedBindings(): List<JsonObject> = readableDatabase.rawQuery("SELECT id,receipt FROM closed_bindings ORDER BY rowid", null).use {
        buildList { while (it.moveToNext()) add(whoopJson.parseToJsonElement(open(it.getBlob(1), "closed:${it.getString(0)}").toString(Charsets.UTF_8)) as JsonObject) }
    }
    private fun seal(value: ByteArray, tag: String) = crypto.seal(value, "$binding:$tag")
    private fun open(value: ByteArray, tag: String) = crypto.open(value, "$binding:$tag")

    @Synchronized fun get(key: String): JsonElement? = readableDatabase.rawQuery("SELECT value FROM metadata WHERE key=?", arrayOf(key)).use {
        if (it.moveToFirst()) whoopJson.parseToJsonElement(open(it.getBlob(0), "meta:$key").toString(Charsets.UTF_8)) else null
    }
    @Synchronized fun put(key: String, value: JsonElement) {
        val row = writableDatabase.insertWithOnConflict("metadata", null, ContentValues().apply {
            put("key", key); put("value", seal(value.toString().toByteArray(), "meta:$key"))
        }, SQLiteDatabase.CONFLICT_REPLACE)
        if (row == -1L) throw WhoopFailure("store_unavailable", context.getString(R.string.whoop_runtime_store_unavailable))
    }
    @Synchronized fun count(): Int = readableDatabase.rawQuery("SELECT COUNT(*) FROM segments", null).use { it.moveToFirst(); it.getInt(0) }
    @Synchronized fun bytes(): Long = readableDatabase.rawQuery("SELECT COALESCE(SUM(size),0) FROM segments", null).use { it.moveToFirst(); it.getLong(0) }
    fun full(): Boolean = bytes() >= WhoopPolicy.MAX_QUEUE_BYTES

    /** One part of history in one transaction, cut into segments of whole frames; false when the queue is full. */
    @Synchronized fun append(frames: List<ByteArray>): Boolean {
        val segments = WhoopPolicy.segments(frames)
        if (bytes() + segments.sumOf { it.size } > WhoopPolicy.MAX_QUEUE_BYTES) return false
        val db = writableDatabase
        db.beginTransaction()
        try {
            for (segment in segments) {
                val digest = hex(segment)
                db.insertWithOnConflict("segments", null, ContentValues().apply {
                    put("digest", digest); put("size", segment.size); put("payload", seal(segment, "segment:$digest"))
                }, SQLiteDatabase.CONFLICT_IGNORE)
            }
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
        return true
    }

    /** A frozen batch body under its key, reused unchanged after a lost response. */
    @Synchronized fun batch(serverCursor: JsonElement, deviceState: JsonObject?): JsonObject? {
        readableDatabase.rawQuery("SELECT key,payload FROM batches LIMIT 1", null).use {
            if (it.moveToFirst()) return whoopJson.parseToJsonElement(open(it.getBlob(1), "batch:${it.getString(0)}").toString(Charsets.UTF_8)) as JsonObject
        }
        val segments = mutableListOf<JsonObject>(); val ids = mutableListOf<JsonPrimitive>()
        var size = deviceState?.toString()?.length ?: 0
        readableDatabase.rawQuery("SELECT id,digest,payload FROM segments ORDER BY id", null).use {
            while (it.moveToNext() && segments.size < WhoopPolicy.BATCH_SEGMENTS) {
                val digest = it.getString(1)
                val encoded = Base64.getEncoder().encodeToString(open(it.getBlob(2), "segment:$digest"))
                if (segments.isNotEmpty() && size + encoded.length + 120 > WhoopPolicy.MAX_BATCH_BYTES) break
                segments += obj { put("external_id", digest); put("frames", encoded) }
                ids += JsonPrimitive(it.getLong(0)); size += encoded.length + 120
            }
        }
        if (segments.isEmpty() && deviceState == null) return null
        val key = UUID.randomUUID().toString()
        val body = obj {
            put("expected_cursor", serverCursor); put("cursor", UUID.randomUUID().toString())
            put("changes", JsonArray(emptyList())); put("whoop_segments", JsonArray(segments))
            deviceState?.let { put("device_state", it) }
        }
        val batch = obj { put("key", key); put("body", body); put("rows", JsonArray(ids)) }
        writableDatabase.insertOrThrow("batches", null, ContentValues().apply {
            put("key", key); put("payload", seal(batch.toString().toByteArray(), "batch:$key"))
        })
        return batch
    }
    /** After a conflicting cursor: the same segments under a new key and the server's cursor. */
    @Synchronized fun rebase(batch: JsonObject, cursor: JsonElement): JsonObject {
        val db = writableDatabase; db.beginTransaction()
        try {
            db.delete("batches", "key=?", arrayOf(batch.string("key")!!))
            val key = UUID.randomUUID().toString()
            val next = JsonObject(batch + ("key" to JsonPrimitive(key)) +
                ("body" to JsonObject((batch["body"] as JsonObject) + ("expected_cursor" to cursor))))
            db.insertOrThrow("batches", null, ContentValues().apply {
                put("key", key); put("payload", seal(next.toString().toByteArray(), "batch:$key"))
            })
            db.setTransactionSuccessful(); return next
        } finally { db.endTransaction() }
    }
    /** The server's receipt removes exactly the segments and device reading of this batch. */
    @Synchronized fun acknowledge(batch: JsonObject) {
        val db = writableDatabase; db.beginTransaction()
        try {
            val key = batch.string("key") ?: throw WhoopFailure("invalid_batch", context.getString(R.string.whoop_platform_invalid_batch))
            (batch["rows"] as JsonArray).forEach { db.delete("segments", "id=?", arrayOf(it.jsonPrimitive.content)) }
            db.delete("batches", "key=?", arrayOf(key))
            val body = batch["body"] as JsonObject
            put("server_cursor", body["cursor"]!!)
            if (body["device_state"] != null && get("device_state") == body["device_state"]) put("device_state", JsonNull)
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }
    @Synchronized fun clear() {
        val db = writableDatabase; db.beginTransaction()
        var preserveKey = true
        try {
            // A readable current binding and readable closure receipts are required before
            // mutating the active queue. Foreign/unknown encrypted archives remain untouched.
            db.rawQuery("SELECT key,value FROM metadata", null).use { rows ->
                while (rows.moveToNext()) whoopJson.parseToJsonElement(open(rows.getBlob(1), "meta:${rows.getString(0)}").toString(Charsets.UTF_8))
            }
            preserveKey = closedBindings().isNotEmpty()
            listOf("metadata", "segments", "batches").forEach { db.delete(it, null, null) }
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
        if (!preserveKey) crypto.delete()
        if (!cleanup.edit().remove(binding).commit()) throw WhoopFailure("store_unavailable", context.getString(R.string.whoop_runtime_store_unavailable))
    }
    /** Switching abandons the complete old-server store, including closed and unreadable bindings. */
    @Synchronized fun clearForServerSwitch() {
        close()
        if (!context.deleteDatabase(databaseName) && context.getDatabasePath(databaseName).exists())
            throw WhoopFailure("store_unavailable", context.getString(R.string.whoop_runtime_store_unavailable))
        crypto.delete()
        if (!cleanup.edit().remove(binding).commit()) throw WhoopFailure("store_unavailable", context.getString(R.string.whoop_runtime_store_unavailable))
    }

    /** Synthetic fixture cleanup only. */
    @Synchronized fun resetUnreadable() = clearForServerSwitch()

    companion object {
        const val NAME = "whoop-v1.sqlite"
        fun hex(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    }
}

internal object WhoopPolicy {
    const val MAX_SEGMENT = 48 * 1024
    const val BATCH_SEGMENTS = 8
    const val MAX_BATCH_BYTES = 230 * 1024
    const val MAX_QUEUE_BYTES = 192L * 1024 * 1024
    const val FOREGROUND_MS = 8 * 60_000L
    const val BACKGROUND_MS = 4 * 60_000L

    /** Whole frames in order, cut before a frame that would pass [MAX_SEGMENT]. */
    fun segments(frames: List<ByteArray>): List<ByteArray> {
        val result = mutableListOf<ByteArray>()
        val current = java.io.ByteArrayOutputStream()
        for (frame in frames) {
            if (current.size() > 0 && current.size() + frame.size > MAX_SEGMENT) { result += current.toByteArray(); current.reset() }
            current.write(frame)
        }
        if (current.size() > 0) result += current.toByteArray()
        return result
    }
}
