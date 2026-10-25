package assistant.mobile.core.journal

import androidx.room.ConstructedBy
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.RoomDatabaseConstructor
import androidx.room.Transaction
import androidx.room.Upsert
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "submissions", primaryKeys = ["instance", "origin", "userId", "key"])
data class SubmissionEntity(
    val instance: String,
    val origin: String,
    val userId: String,
    val key: String,
    val endpoint: String,
    val lookup: String?,
    val method: String,
    val body: ByteArray,
    val audience: String,
    val revisions: String,
    val draftTarget: String?,
    val found: String,
    val createdAtMillis: Long,
    val state: String,
    val response: ByteArray?,
    val mayHaveBeenAdmitted: Boolean,
    val attempt: Int,
    val disposition: String,
    val failureStatus: Int?,
    val retryAtMillis: Long?,
)

@Entity(tableName = "drafts", primaryKeys = ["instance", "origin", "userId", "target"])
data class DraftEntity(
    val instance: String,
    val origin: String,
    val userId: String,
    val target: String,
    val json: ByteArray,
    val revision: Long,
    val updatedAtMillis: Long,
)

@Entity(tableName = "event_cursor", primaryKeys = ["instance", "origin", "userId"])
data class CursorEntity(val instance: String, val origin: String, val userId: String, val cursor: String)

@Dao
abstract class JournalDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    abstract suspend fun insertSubmission(entry: SubmissionEntity)

    @Upsert
    abstract suspend fun saveSubmission(entry: SubmissionEntity)

    @Query("SELECT * FROM submissions WHERE instance = :instance AND origin = :origin AND userId = :userId AND `key` = :key")
    abstract suspend fun submission(instance: String, origin: String, userId: String, key: String): SubmissionEntity?

    @Query("SELECT * FROM submissions WHERE instance = :instance AND origin = :origin AND userId = :userId ORDER BY createdAtMillis, `key`")
    abstract suspend fun submissions(instance: String, origin: String, userId: String): List<SubmissionEntity>

    @Query("SELECT * FROM submissions WHERE instance = :instance AND origin = :origin AND userId = :userId ORDER BY createdAtMillis, `key`")
    abstract fun observeSubmissions(instance: String, origin: String, userId: String): Flow<List<SubmissionEntity>>

    @Query("DELETE FROM submissions WHERE instance = :instance AND origin = :origin AND userId = :userId AND `key` = :key")
    abstract suspend fun deleteSubmission(instance: String, origin: String, userId: String, key: String)

    @Upsert
    abstract suspend fun saveDraft(entry: DraftEntity)

    @Query("SELECT * FROM drafts WHERE instance = :instance AND origin = :origin AND userId = :userId AND target = :target")
    abstract suspend fun draft(instance: String, origin: String, userId: String, target: String): DraftEntity?

    @Query("DELETE FROM drafts WHERE instance = :instance AND origin = :origin AND userId = :userId AND target = :target")
    abstract suspend fun deleteDraft(instance: String, origin: String, userId: String, target: String)

    @Upsert
    abstract suspend fun saveCursor(entry: CursorEntity)

    @Query("SELECT cursor FROM event_cursor WHERE instance = :instance AND origin = :origin AND userId = :userId")
    abstract suspend fun cursor(instance: String, origin: String, userId: String): String?

    @Query("DELETE FROM submissions WHERE instance = :instance AND origin = :origin AND userId = :userId")
    abstract suspend fun deleteSubmissions(instance: String, origin: String, userId: String)

    @Query("DELETE FROM drafts WHERE instance = :instance AND origin = :origin AND userId = :userId")
    abstract suspend fun deleteDrafts(instance: String, origin: String, userId: String)

    @Query("DELETE FROM event_cursor WHERE instance = :instance AND origin = :origin AND userId = :userId")
    abstract suspend fun deleteCursor(instance: String, origin: String, userId: String)

    @Transaction
    open suspend fun clearAccount(instance: String, origin: String, userId: String) {
        deleteSubmissions(instance, origin, userId)
        deleteDrafts(instance, origin, userId)
        deleteCursor(instance, origin, userId)
    }

    @Query("DELETE FROM submissions WHERE instance = :instance AND origin = :origin")
    abstract suspend fun deleteOriginSubmissions(instance: String, origin: String)

    @Query("DELETE FROM drafts WHERE instance = :instance AND origin = :origin")
    abstract suspend fun deleteOriginDrafts(instance: String, origin: String)

    @Query("DELETE FROM event_cursor WHERE instance = :instance AND origin = :origin")
    abstract suspend fun deleteOriginCursors(instance: String, origin: String)

    @Query("SELECT EXISTS(SELECT 1 FROM submissions WHERE instance = :instance AND origin = :origin " +
        "UNION ALL SELECT 1 FROM drafts WHERE instance = :instance AND origin = :origin " +
        "UNION ALL SELECT 1 FROM event_cursor WHERE instance = :instance AND origin = :origin)")
    abstract suspend fun hasOriginData(instance: String, origin: String): Boolean

    @Transaction
    open suspend fun clearOrigin(instance: String, origin: String) {
        deleteOriginSubmissions(instance, origin)
        deleteOriginDrafts(instance, origin)
        deleteOriginCursors(instance, origin)
    }
}

@Database(entities = [SubmissionEntity::class, DraftEntity::class, CursorEntity::class], version = 1, exportSchema = true)
@ConstructedBy(JournalDatabaseConstructor::class)
abstract class JournalDatabase : RoomDatabase() {
    abstract fun journalDao(): JournalDao
}

@Suppress("NO_ACTUAL_FOR_EXPECT")
expect object JournalDatabaseConstructor : RoomDatabaseConstructor<JournalDatabase> {
    override fun initialize(): JournalDatabase
}

/** Platform code supplies a private, backup-excluded path; SQLite is bundled on every target. */
fun buildJournalDatabase(builder: RoomDatabase.Builder<JournalDatabase>): JournalDatabase =
    builder.setDriver(BundledSQLiteDriver()).setQueryCoroutineContext(Dispatchers.IO).build()
