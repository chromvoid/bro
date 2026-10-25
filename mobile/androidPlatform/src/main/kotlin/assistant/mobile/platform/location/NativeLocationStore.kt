package assistant.mobile.platform.location

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import assistant.mobile.core.AccountNamespace
import assistant.mobile.core.location.LocationCollectorGrant
import assistant.mobile.core.location.LocationFix
import assistant.mobile.core.location.LocationQueuePolicy
import java.io.File
import java.security.KeyStore
import java.security.MessageDigest
import java.time.Instant
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject

internal data class StoredLocationCollector(
    val namespace: AccountNamespace,
    val installation: String,
    val grant: LocationCollectorGrant,
    val captureNotBeforeMs: Long,
) {
    init { require(captureNotBeforeMs > 0) }
    override fun toString(): String = "StoredLocationCollector(redacted)"
}

/** The native package owns this no-backup file and Keystore alias; shell credentials are never read. */
internal class NativeLocationStore(context: Context, origin: String) : SQLiteOpenHelper(
    context,
    File(context.noBackupFilesDir, "native-location-${locationDigest(origin)}.sqlite").absolutePath,
    null,
    1,
) {
    private val crypto = LocationCipher("assistant.native.location.${locationDigest(origin)}")
    private val json = Json { ignoreUnknownKeys = true }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE points (captured_at INTEGER PRIMARY KEY, sealed BLOB NOT NULL)")
        db.execSQL("CREATE TABLE acknowledged (captured_at INTEGER PRIMARY KEY)")
        db.execSQL("CREATE TABLE metadata (key TEXT PRIMARY KEY, sealed BLOB NOT NULL)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

    @Synchronized fun grant(): StoredLocationCollector? =
        readableDatabase.rawQuery("SELECT sealed FROM metadata WHERE key='grant'", null).use { cursor ->
            if (!cursor.moveToFirst()) null else runCatching {
                val record = json.parseToJsonElement(crypto.open(cursor.getBlob(0), "grant").decodeToString()).jsonObject
                StoredLocationCollector(
                    json.decodeFromJsonElement(AccountNamespace.serializer(), record.getValue("namespace")),
                    json.decodeFromJsonElement(kotlinx.serialization.serializer<String>(), record.getValue("installation")),
                    json.decodeFromJsonElement(LocationCollectorGrant.serializer(), record.getValue("grant")),
                    json.decodeFromJsonElement(kotlinx.serialization.serializer<Long>(), record.getValue("capture_not_before_ms")),
                )
            }.getOrNull()
        }

    @Synchronized fun saveGrant(record: StoredLocationCollector) {
        val value = buildJsonObject {
            put("namespace", json.encodeToJsonElement(AccountNamespace.serializer(), record.namespace))
            put("installation", json.encodeToJsonElement(kotlinx.serialization.serializer<String>(), record.installation))
            put("grant", json.encodeToJsonElement(LocationCollectorGrant.serializer(), record.grant))
            put("capture_not_before_ms", json.encodeToJsonElement(kotlinx.serialization.serializer<Long>(), record.captureNotBeforeMs))
        }
        writableDatabase.insertWithOnConflict("metadata", null, ContentValues().apply {
            put("key", "grant")
            put("sealed", crypto.seal(value.toString().encodeToByteArray(), "grant"))
        }, SQLiteDatabase.CONFLICT_REPLACE)
    }

    @Synchronized fun append(record: StoredLocationCollector, points: List<LocationFix>, nowMs: Long) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            points.forEach { point ->
                val time = Instant.parse(point.capturedAt).toEpochMilli()
                if (time < record.captureNotBeforeMs || LocationQueuePolicy.expiredPoint(time, nowMs) || time > nowMs + 2 * 60_000) return@forEach
                val acknowledged = db.rawQuery("SELECT 1 FROM acknowledged WHERE captured_at=?", arrayOf("$time"))
                    .use { it.moveToFirst() }
                if (!acknowledged) db.insertWithOnConflict("points", null, ContentValues().apply {
                    put("captured_at", time)
                    put("sealed", crypto.seal(json.encodeToString(LocationFix.serializer(), point).encodeToByteArray(), binding(record, time)))
                }, SQLiteDatabase.CONFLICT_IGNORE)
            }
            db.execSQL("DELETE FROM points WHERE captured_at NOT IN (SELECT captured_at FROM points ORDER BY captured_at DESC LIMIT $LIMIT)")
            db.execSQL("DELETE FROM acknowledged WHERE captured_at < ?", arrayOf(nowMs - MAX_AGE_MS))
            db.execSQL("DELETE FROM acknowledged WHERE captured_at NOT IN (SELECT captured_at FROM acknowledged ORDER BY captured_at DESC LIMIT $LIMIT)")
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }

    @Synchronized fun oldest(record: StoredLocationCollector, count: Int): List<Pair<Long, LocationFix?>> =
        readableDatabase.rawQuery("SELECT captured_at,sealed FROM points ORDER BY captured_at LIMIT ?", arrayOf("$count")).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    val time = cursor.getLong(0)
                    val point = runCatching {
                        json.decodeFromString(LocationFix.serializer(), crypto.open(cursor.getBlob(1), binding(record, time)).decodeToString()).normalized()
                    }.getOrNull()
                    add(time to point)
                }
            }
        }

    @Synchronized fun remove(times: List<Long>, acknowledged: Boolean = false) {
        if (times.isEmpty()) return
        val db = writableDatabase
        db.beginTransaction()
        try {
            if (acknowledged) times.forEach { time ->
                db.insertWithOnConflict("acknowledged", null, ContentValues().apply { put("captured_at", time) }, SQLiteDatabase.CONFLICT_IGNORE)
            }
            db.delete("points", "captured_at IN (${times.joinToString(",") { "?" }})", times.map { "$it" }.toTypedArray())
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }

    @Synchronized fun queued(): Int = readableDatabase.rawQuery("SELECT count(*) FROM points", null).use {
        it.moveToFirst(); it.getInt(0)
    }

    @Synchronized fun hasGrant(): Boolean = readableDatabase.rawQuery("SELECT 1 FROM metadata WHERE key='grant'", null)
        .use { it.moveToFirst() }

    @Synchronized fun hasData(): Boolean = readableDatabase.rawQuery(
        "SELECT EXISTS(SELECT 1 FROM points UNION ALL SELECT 1 FROM metadata UNION ALL SELECT 1 FROM acknowledged)", null,
    ).use { it.moveToFirst(); it.getInt(0) != 0 }

    @Synchronized fun clear() {
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.delete("points", null, null)
            db.delete("metadata", null, null)
            db.delete("acknowledged", null, null)
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
        crypto.delete()
    }

    private fun binding(record: StoredLocationCollector, time: Long) =
        "point:${record.namespace.instance}:${record.namespace.userId}:${record.installation}:$time"

    companion object {
        const val LIMIT = LocationQueuePolicy.MAX_POINTS
        const val MAX_AGE_MS = LocationQueuePolicy.MAX_AGE_MS
    }
}

internal fun locationDigest(value: String): String = MessageDigest.getInstance("SHA-256")
    .digest(value.encodeToByteArray()).take(16).joinToString("") { "%02x".format(it) }

private class LocationCipher(private val alias: String) {
    @Synchronized private fun key(create: Boolean): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        check(create) { "Location key is unavailable" }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setKeySize(256).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).setRandomizedEncryptionRequired(true).build())
        }.generateKey()
    }

    fun seal(value: ByteArray, binding: String): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key(true))
        cipher.updateAAD(binding.encodeToByteArray())
        return byteArrayOf(1) + cipher.iv + cipher.doFinal(value)
    }

    fun open(value: ByteArray, binding: String): ByteArray {
        require(value.size > 29 && value[0] == 1.toByte())
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(false), GCMParameterSpec(128, value.copyOfRange(1, 13)))
        cipher.updateAAD(binding.encodeToByteArray())
        return cipher.doFinal(value.copyOfRange(13, value.size))
    }

    fun delete() { KeyStore.getInstance("AndroidKeyStore").apply { load(null); deleteEntry(alias) } }
}
