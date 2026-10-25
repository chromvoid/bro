package assistant.mobile.platform.ring

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.io.ByteArrayOutputStream
import java.io.File
import java.time.Instant
import java.util.UUID
import kotlinx.serialization.json.*

/** Private, bounded evidence only. This database never participates in outbox transactions. */
class RingDiagnostics(
    private val context: Context,
    origin: String,
    private val maxEvents: Int = 4096,
    private val maxBytes: Int = 8 * 1024 * 1024,
    private val maxEventBytes: Int = 192 * 1024,
    private val beforeExportDecrypt: (() -> Unit)? = null,
) {
    data class Session(val id: String, val epoch: Long)
    private data class ExportOwner(val epoch: Long, val account: String?)
    private data class EncryptedEvent(val id: Long, val payload: ByteArray)
    private data class ExportSnapshot(val owner: ExportOwner, val at: Instant, val status: JsonObject,
        val events: List<EncryptedEvent>, val reader: RingCrypto.Reader?, val truncated: Boolean)
    private class ExportBuffer : ByteArrayOutputStream() {
        fun erase() { buf.fill(0); reset() }
    }
    // Serialize only exporters. Producers, status and account cleanup never acquire this lock.
    private val exportLock = Any()
    private val appVersion: String = try {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "unknown"
    } catch (_: Exception) { "unknown" }
    private val binding = sha256(origin)
    private val file = File(context.noBackupFilesDir, "ring-diagnostics-$binding.sqlite")
    private val crypto = RingCrypto("assistant.ring.diagnostics.$binding")
    private val cleanup = context.getSharedPreferences("ring-diagnostics-cleanup", Context.MODE_PRIVATE)
    private var epoch = System.nanoTime()
    private var blocked = cleanup.getBoolean(binding, false)
    private var failures = 0L
    private var lastFailure: String? = null
    private var unreadable = 0L
    private var exportTruncated = false
    private val database = object : SQLiteOpenHelper(context, file.absolutePath, null, 1) {
        override fun onConfigure(db: SQLiteDatabase) {
            // A hard page cap also bounds free pages and temporary rollback storage.
            val pageSize = db.rawQuery("PRAGMA page_size", null).use { it.moveToFirst(); it.getLong(0) }
            val pages = ((maxBytes.toLong() + maxEventBytes + 1024 * 1024) / pageSize + 1)
            db.rawQuery("PRAGMA max_page_count=$pages", null).use { it.moveToFirst() }
            db.rawQuery("PRAGMA journal_size_limit=0", null).use { it.moveToFirst() }
        }
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL("CREATE TABLE events (id INTEGER PRIMARY KEY AUTOINCREMENT, payload BLOB NOT NULL)")
            db.execSQL("CREATE TABLE counters (key TEXT PRIMARY KEY, value INTEGER NOT NULL)")
        }
        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = error("Unsupported diagnostic journal")
    }

    init {
        RingText.initialize(context)
        require(maxEvents > 0 && maxBytes >= 1024 && maxEventBytes >= 512)
        if (blocked) clear()
    }

    @Synchronized fun session(): Session = Session(UUID.randomUUID().toString(), epoch)

    /** A second account fence covers a previous process whose diagnostic storage could not open. */
    @Synchronized fun bind(collector: String?, preservePrevious: Boolean = false) {
        try {
            val owner = sha256(collector ?: "unbound")
            if (cleanup.getString("owner:$binding", null) == owner) return
            if (preservePrevious) {
                archiveActiveEvidence()
            } else if (!clear()) return
            if (!cleanup.edit().putString("owner:$binding", owner).commit()) {
                blocked = true; failure("journal_owner_failed")
            }
        } catch (_: Exception) { blocked = true; failure("journal_owner_failed") }
    }

    /** Re-enrollment closes a grant generation even when the server reuses its collector ID. */
    @Synchronized fun closeBinding(): Boolean = try {
        archiveActiveEvidence()
        true
    } catch (_: Exception) {
        blocked = true; failure("journal_owner_failed"); false
    }

    private fun archiveActiveEvidence() {
        // A package upgrade or explicit re-enrollment must retain the former owner's evidence.
        // Archived ciphertext is never included in the current account's diagnostic export.
        val db = database.writableDatabase; db.beginTransaction()
        try {
            db.execSQL("CREATE TABLE IF NOT EXISTS closed_events (closure TEXT NOT NULL, id INTEGER NOT NULL, payload BLOB NOT NULL, PRIMARY KEY(closure,id))")
            db.execSQL("CREATE TABLE IF NOT EXISTS closed_counters (closure TEXT NOT NULL, key TEXT NOT NULL, value INTEGER NOT NULL, PRIMARY KEY(closure,key))")
            val closure = UUID.randomUUID().toString()
            db.execSQL("INSERT INTO closed_events (closure,id,payload) SELECT ?,id,payload FROM events", arrayOf(closure))
            db.execSQL("INSERT INTO closed_counters (closure,key,value) SELECT ?,key,value FROM counters", arrayOf(closure))
            db.delete("events", null, null); db.delete("counters", null, null)
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
        epoch++; failures = 0; lastFailure = null; unreadable = 0; exportTruncated = false
    }

    private fun failure(code: String) {
        failures++; lastFailure = code
        if (!blocked) try {
            val db = database.writableDatabase
            failures = maxOf(failures, counter(db, "failures") + 1)
            db.insertWithOnConflict("counters", null, ContentValues().apply {
                put("key", "failures"); put("value", failures)
            }, SQLiteDatabase.CONFLICT_REPLACE)
        } catch (_: Exception) { /* Export still reports the in-memory failure if storage is unavailable. */ }
    }
    private fun counter(db: SQLiteDatabase, key: String): Long = db.rawQuery(
        "SELECT value FROM counters WHERE key=?", arrayOf(key)).use { if (it.moveToFirst()) it.getLong(0) else 0 }
    private fun increment(db: SQLiteDatabase, key: String, amount: Long = 1) {
        db.insertWithOnConflict("counters", null, ContentValues().apply {
            put("key", key); put("value", counter(db, key) + amount)
        }, SQLiteDatabase.CONFLICT_REPLACE)
    }
    private fun totals(db: SQLiteDatabase): Pair<Long, Long> = db.rawQuery(
        "SELECT COUNT(*),COALESCE(SUM(LENGTH(payload)),0) FROM events", null).use {
        it.moveToFirst(); it.getLong(0) to it.getLong(1)
    }

    /** Producers pass protocol data or explicit HTTP identifiers, never requests, grants or state. */
    @Synchronized fun append(session: Session, kind: String, payload: JsonObject = obj {}) {
        if (blocked || session.epoch != epoch) return
        try {
            fun event(eventKind: String, data: JsonObject) = obj {
                put("session_id", session.id); put("at", Instant.now().toString())
                put("app_version", appVersion); put("kind", eventKind)
                put("payload", safePayload(data))
            }.toString().toByteArray(Charsets.UTF_8)
            var plain = event(kind, payload)
            val omitted = plain.size > minOf(maxEventBytes, maxBytes - 128)
            if (omitted) plain = event("journal_event_omitted", obj {
                put("original_kind", kind); put("original_bytes", plain.size)
            })
            val encrypted = crypto.seal(plain, "$binding:event")
            val db = database.writableDatabase
            db.beginTransaction()
            try {
                var (count, size) = totals(db)
                var evicted = 0L
                while (count >= maxEvents || size + encrypted.size > maxBytes) {
                    val oldest = db.rawQuery("SELECT id,LENGTH(payload) FROM events ORDER BY id LIMIT 1", null).use {
                        if (it.moveToFirst()) it.getLong(0) to it.getLong(1) else null
                    } ?: break
                    db.delete("events", "id=?", arrayOf(oldest.first.toString()))
                    count--; size -= oldest.second; evicted++
                }
                check(encrypted.size <= maxBytes)
                db.insertOrThrow("events", null, ContentValues().apply { put("payload", encrypted) })
                if (evicted > 0) increment(db, "evicted_events", evicted)
                if (omitted) increment(db, "omitted_events")
                if (failures > counter(db, "failures")) {
                    db.insertWithOnConflict("counters", null, ContentValues().apply {
                        put("key", "failures"); put("value", failures)
                    }, SQLiteDatabase.CONFLICT_REPLACE)
                }
                db.setTransactionSuccessful()
            } finally { db.endTransaction() }
        } catch (_: Exception) { failure("journal_write_failed") }
    }

    @Synchronized fun status(): JsonObject {
        var count = 0L; var bytes = 0L; var evicted = 0L; var omitted = 0L
        if (!blocked) try {
            val db = database.readableDatabase
            val totals = totals(db); count = totals.first; bytes = totals.second
            evicted = counter(db, "evicted_events"); omitted = counter(db, "omitted_events")
            failures = maxOf(failures, counter(db, "failures"))
        } catch (_: Exception) { failure("journal_read_failed") }
        return obj {
            put("healthy", !blocked && failures == 0L && unreadable == 0L)
            put("unavailable", blocked); put("event_count", count); put("ciphertext_bytes", bytes)
            put("evicted_events", evicted); put("omitted_events", omitted); put("unreadable_events", unreadable)
            put("truncated", evicted > 0 || omitted > 0 || unreadable > 0 || exportTruncated); put("export_truncated", exportTruncated)
            put("failures", failures); put("last_failure", lastFailure?.let(::JsonPrimitive) ?: JsonNull)
            put("max_events", maxEvents); put("max_bytes", maxBytes); put("app_version", appVersion)
        }
    }

    private fun exportOwner() = ExportOwner(epoch, cleanup.getString("owner:$binding", null))
    private fun sameOwner(owner: ExportOwner) = owner == exportOwner()
    @Synchronized private fun current(owner: ExportOwner) = sameOwner(owner)

    /** Copy bounded ciphertext and close the cursor before any per-event Keystore work. */
    @Synchronized private fun exportSnapshot(owner: ExportOwner): ExportSnapshot? {
        if (!sameOwner(owner)) return null
        val events = mutableListOf<EncryptedEvent>()
        var bytes = 0L
        var truncated = false
        var reader: RingCrypto.Reader? = null
        if (!blocked) try {
            database.readableDatabase.rawQuery("SELECT id,payload FROM events ORDER BY id", null).use { rows ->
                while (rows.moveToNext()) {
                    val payload = rows.getBlob(1)
                    if (events.size >= maxEvents || bytes + payload.size > maxBytes) { truncated = true; break }
                    events += EncryptedEvent(rows.getLong(0), payload)
                    bytes += payload.size
                }
            }
            // This is a read-only handle, not RingCrypto.open's create-if-missing key path.
            if (events.isNotEmpty()) reader = try { crypto.captureReader() } catch (_: Exception) { null }
        } catch (_: Exception) { truncated = true; failure("journal_export_failed") }
        return ExportSnapshot(owner, Instant.now(), status(), events, reader, truncated)
    }

    private fun header(status: JsonObject, at: Instant = Instant.now(), cancelled: Boolean = false): ByteArray =
        (obj {
            put("kind", "journal_status"); put("at", at.toString()); put("status", status)
            if (cancelled) put("export_cancelled", true)
        }.toString() + "\n").toByteArray(Charsets.UTF_8)

    @Synchronized private fun cancelledExport() = header(status(), cancelled = true)

    private fun exportStatus(snapshot: ExportSnapshot, unreadableCount: Long, truncated: Boolean): JsonObject {
        fun counter(key: String) = snapshot.status[key]?.jsonPrimitive?.longOrNull ?: 0
        return JsonObject(snapshot.status + mapOf(
            "healthy" to JsonPrimitive(snapshot.status.bool("unavailable") != true && counter("failures") == 0L && unreadableCount == 0L),
            "unreadable_events" to JsonPrimitive(unreadableCount),
            "export_truncated" to JsonPrimitive(truncated),
            "truncated" to JsonPrimitive(counter("evicted_events") > 0 || counter("omitted_events") > 0 || unreadableCount > 0 || truncated),
            "last_failure" to if (unreadableCount > 0) JsonPrimitive("journal_event_unreadable") else snapshot.status.getValue("last_failure"),
        ))
    }

    /** Header is always present. Export is bounded at maxBytes plus a 4 KiB health-header allowance. */
    fun export(): ByteArray {
        // A queued request retains its account fence, even if another export is still running.
        val owner = synchronized(this) { exportOwner() }
        return synchronized(exportLock) { decryptExport(owner) }
    }

    private fun decryptExport(owner: ExportOwner): ByteArray {
        val snapshot = exportSnapshot(owner) ?: return cancelledExport()
        val output = ExportBuffer()
        var unreadableCount = 0L
        var truncated = snapshot.truncated
        try {
            for (event in snapshot.events) {
                if (!current(owner)) return cancelledExport()
                beforeExportDecrypt?.invoke() // Controlled instrumentation gate; no production caller supplies it.
                if (!current(owner)) return cancelledExport()
                val plain = try {
                    val value = checkNotNull(snapshot.reader).open(event.payload, "$binding:event")
                    try {
                        check(value.size <= maxEventBytes)
                        check(ringJson.parseToJsonElement(value.toString(Charsets.UTF_8)) is JsonObject)
                    } catch (error: Exception) {
                        value.fill(0); throw error
                    }
                    value
                } catch (_: Exception) {
                    unreadableCount++
                    obj { put("kind", "journal_event_unreadable"); put("sequence", event.id) }.toString().toByteArray()
                }
                try {
                    if (!current(owner)) return cancelledExport()
                    if (output.size() + plain.size + 1 > maxBytes) { truncated = true; break }
                    output.write(plain); output.write('\n'.code)
                } finally { plain.fill(0) }
            }
            // Large buffer copies and serialization also stay outside the producer monitor.
            val exported = header(exportStatus(snapshot, unreadableCount, truncated), snapshot.at) + output.toByteArray()
            return synchronized(this) {
                if (!sameOwner(owner)) { exported.fill(0); cancelledExport() }
                else {
                    // A snapshot's counters describe its rows; concurrent appends appear on the next export.
                    unreadable = unreadableCount; exportTruncated = truncated
                    if (unreadableCount > 0) lastFailure = "journal_event_unreadable"
                    exported
                }
            }
        } catch (_: Exception) {
            return synchronized(this) {
                if (!sameOwner(owner)) cancelledExport()
                else { failure("journal_export_failed"); header(status()) }
            }
        } finally { output.erase() }
    }

    /** Fence old sessions before deletion, including delayed HTTP completion from an old account. */
    @Synchronized fun clear(): Boolean {
        epoch++; blocked = true
        try {
            check(cleanup.edit().putBoolean(binding, true).commit())
            val db = database.writableDatabase
            val retainedEvidence = listOf("closed_events", "closed_counters").any { table ->
                val exists = db.rawQuery("SELECT 1 FROM sqlite_master WHERE type='table' AND name=?", arrayOf(table)).use { it.moveToFirst() }
                exists && db.rawQuery("SELECT 1 FROM $table LIMIT 1", null).use { it.moveToFirst() }
            }
            if (retainedEvidence) {
                // A later account's logout must not erase the old upgrade/re-enrollment archive.
                // Its shared key remains; the durable cleanup fence and export epoch hide active rows.
                db.beginTransaction()
                try {
                    db.delete("events", null, null); db.delete("counters", null, null)
                    db.setTransactionSuccessful()
                } finally { db.endTransaction() }
            } else {
                database.close()
                // Without an archive, destroy the active owner's key before deleting its database.
                crypto.delete()
                val deleted = SQLiteDatabase.deleteDatabase(file)
                check(deleted || !file.exists())
            }
            check(cleanup.edit().remove(binding).commit())
            failures = 0; lastFailure = null; unreadable = 0; exportTruncated = false; blocked = false
            return true
        } catch (_: Exception) { failure("journal_clear_failed"); return false }
    }

    @Synchronized fun close() {
        epoch++
        try { database.close() } catch (_: Exception) { failure("journal_close_failed") }
    }

    @Synchronized fun hasData(): Boolean {
        if (blocked) return true
        val db = database.readableDatabase
        val archived = db.rawQuery("SELECT 1 FROM sqlite_master WHERE type='table' AND name='closed_events'", null)
            .use { it.moveToFirst() }
        val tables = listOf("events", "counters") + if (archived) listOf("closed_events", "closed_counters") else emptyList()
        return db.rawQuery("SELECT " + tables.joinToString(" OR ") { "EXISTS(SELECT 1 FROM $it)" }, null)
            .use { it.moveToFirst(); it.getInt(0) != 0 }
    }

    /** Explicit server departure removes both active diagnostics and retained binding archives. */
    @Synchronized fun clearForServerSwitch() {
        epoch++; blocked = true
        database.close()
        check(SQLiteDatabase.deleteDatabase(file) || !file.exists()) { "Ring diagnostics could not be cleared" }
        crypto.delete()
        check(cleanup.edit().remove(binding).remove("owner:$binding").commit()) {
            "Ring diagnostics preferences could not be cleared"
        }
    }

    companion object {
        private val sensitiveKeys = setOf("token", "accesstoken", "refreshtoken", "csrftoken", "cookie", "cookies",
            "authorization", "grant", "address", "mac", "deviceaddress", "userid", "headers")
        fun safePayload(value: JsonElement): JsonElement = when (value) {
            is JsonObject -> JsonObject(value.filterKeys { key ->
                key.lowercase().replace("_", "").replace("-", "") !in sensitiveKeys
            }.mapValues { safePayload(it.value) })
            is JsonArray -> JsonArray(value.map(::safePayload))
            else -> value
        }
    }
}
