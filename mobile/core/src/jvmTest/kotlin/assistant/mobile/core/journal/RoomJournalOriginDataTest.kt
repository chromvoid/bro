package assistant.mobile.core.journal

import assistant.mobile.core.AccountNamespace
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RoomJournalOriginDataTest {
    private val namespace = AccountNamespace("synthetic", "https://selected.invalid", "expired-user")

    @Test
    fun submissionsRequireConfirmationOnlyForTheirOwnOriginAndInstance() = runBlocking<Unit> {
        verifyOriginData { store, owner ->
            store.insertSubmission(Submission(owner, "synthetic-operation", "/v1/tasks/operations", null,
                "POST", "{}".encodeToByteArray(), "personal:${owner.userId}", emptyMap(), null,
                SubmissionState.RESOLVED, 100))
        }
    }

    @Test
    fun draftsRequireConfirmationOnlyForTheirOwnOriginAndInstance() = runBlocking<Unit> {
        verifyOriginData { store, owner ->
            store.saveDraft(Draft(owner, "composer", "{}".encodeToByteArray(), 1, 100))
        }
    }

    @Test
    fun eventCursorsRequireConfirmationOnlyForTheirOwnOriginAndInstance() = runBlocking<Unit> {
        verifyOriginData { store, owner -> store.writeCursor(owner, "synthetic-cursor") }
    }

    @Test
    fun unavailableDatabaseCannotBeReportedAsAnEmptyOrigin() = runBlocking<Unit> {
        val directory = Files.createTempDirectory("native-origin-data-closed").toFile()
        val database = openJournalDatabase(directory.resolve("journal.db"))
        val store = RoomJournalStore(database)
        try {
            assertFalse(store.hasOriginData(namespace.instance, namespace.origin))
            database.close()
            assertFailsWith<JournalStorageException> { store.hasOriginData(namespace.instance, namespace.origin) }
        } finally { database.close(); directory.deleteRecursively() }
    }

    private suspend fun verifyOriginData(insert: suspend (RoomJournalStore, AccountNamespace) -> Unit) {
        val directory = Files.createTempDirectory("native-origin-data").toFile()
        val database = openJournalDatabase(directory.resolve("journal.db"))
        val store = RoomJournalStore(database)
        try {
            assertFalse(store.hasOriginData(namespace.instance, namespace.origin))
            val otherOrigin = namespace.copy(origin = "https://other.invalid")
            val otherInstance = namespace.copy(instance = "another-installation")
            insert(store, otherOrigin)
            insert(store, otherInstance)
            assertFalse(store.hasOriginData(namespace.instance, namespace.origin))
            insert(store, namespace.copy(userId = "another-expired-user"))
            assertTrue(store.hasOriginData(namespace.instance, namespace.origin))
            store.clearOrigin(namespace.instance, namespace.origin)
            assertFalse(store.hasOriginData(namespace.instance, namespace.origin))
            assertTrue(store.hasOriginData(otherOrigin.instance, otherOrigin.origin))
            assertTrue(store.hasOriginData(otherInstance.instance, otherInstance.origin))
        } finally { database.close(); directory.deleteRecursively() }
    }
}
