package assistant.mobile.core.photos

import assistant.mobile.core.AccountNamespace
import assistant.mobile.core.NativeIdentity
import assistant.mobile.core.SessionLease
import assistant.mobile.core.SessionManager
import assistant.mobile.core.SessionSecretStore
import assistant.mobile.core.StoredSession
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

@OptIn(ExperimentalCoroutinesApi::class)
class PhotoAttachmentTest {
    @Test
    fun preparationContinuesAfterTheUiCallerLeavesAndCancelRejectsLateDecoderResults() = runTest {
        val sessions = photoTestSessions().also { it.restore() }
        val decoded = CompletableDeferred<Unit>()
        var uploads = 0
        val controller = PhotoAttachmentController(backgroundScope, sessions, object : PhotoUploadTransport {
            override suspend fun upload(photo: NormalizedPhoto, lease: SessionLease, progress: (Long, Long?) -> Unit): String {
                uploads++
                return PHOTO_TEST_ID
            }
        })
        val ui = launch {
            controller.prepare("content://synthetic/photo", PhotoNormalizer {
                decoded.await()
                photoTestImage()
            })
            awaitCancellation()
        }
        runCurrent()
        assertEquals(PhotoAttachmentStatus.Preparing, controller.state.value.status)
        ui.cancelAndJoin()
        decoded.complete(Unit)
        runCurrent()
        assertEquals(PhotoAttachmentStatus.Ready, controller.state.value.status)
        assertEquals(1, uploads)
        val late = CompletableDeferred<Unit>()
        controller.prepare("content://synthetic/other", PhotoNormalizer {
            withContext(NonCancellable) { late.await() }
            photoTestImage()
        })
        runCurrent()
        controller.cancel()
        late.complete(Unit)
        runCurrent()
        assertEquals(PhotoAttachmentStatus.Empty, controller.state.value.status)
        assertEquals(1, uploads)
        controller.close()
    }

    @Test
    fun cancelAndLateDecoderOrUploadCannotReplaceANewerSelection() = runTest {
        val sessions = photoTestSessions().also { it.restore() }
        val gate = CompletableDeferred<Unit>()
        val controller = PhotoAttachmentController(backgroundScope, sessions, object : PhotoUploadTransport {
            override suspend fun upload(photo: NormalizedPhoto, lease: SessionLease, progress: (Long, Long?) -> Unit): String {
                withContext(NonCancellable) { gate.await() }
                return PHOTO_TEST_ID
            }
        })
        val old = controller.beginSelection()
        controller.selected(old, photoTestImage())
        runCurrent()
        controller.cancel()
        val fresh = controller.beginSelection()
        controller.selected(old, photoTestImage())
        controller.preparationFailed(old)
        controller.handoff(old)
        gate.complete(Unit)
        runCurrent()
        assertEquals(fresh, controller.state.value.selection)
        assertEquals(PhotoAttachmentStatus.Preparing, controller.state.value.status)
        assertNull(controller.state.value.imageId)
        controller.close()
    }

    @Test
    fun losingTheSessionPurgesThePhotoAndRejectsLateCompletion() = runTest {
        val sessions = photoTestSessions().also { it.restore() }
        val gate = CompletableDeferred<Unit>()
        val controller = PhotoAttachmentController(backgroundScope, sessions, object : PhotoUploadTransport {
            override suspend fun upload(photo: NormalizedPhoto, lease: SessionLease, progress: (Long, Long?) -> Unit): String {
                withContext(NonCancellable) { gate.await() }
                return PHOTO_TEST_ID
            }
        })
        controller.selected(controller.beginSelection(), photoTestImage())
        runCurrent()
        sessions.expire(sessions.lease()!!)
        runCurrent()
        assertEquals(PhotoAttachmentStatus.Empty, controller.state.value.status)
        gate.complete(Unit)
        runCurrent()
        assertNull(controller.readyImageId())
        assertNull(controller.state.value.photo)
        controller.close()
    }
}

internal const val PHOTO_TEST_ORIGIN = "https://assistant.invalid"
internal const val PHOTO_TEST_ID = "55555555-5555-4555-8555-555555555555"
internal fun photoTestImage() = NormalizedPhoto(byteArrayOf(0xff.toByte(), 0xd8.toByte(), 0xff.toByte(), 1, 2, 3), 20, 10)
internal fun photoTestSessions(): SessionManager {
    val identity = NativeIdentity("synthetic-photo-owner", "fixture", "Synthetic", "ru", "UTC", false,
        1, "synthetic-device", "2099-01-01T00:00:00Z")
    var stored: StoredSession? = StoredSession(AccountNamespace("synthetic", PHOTO_TEST_ORIGIN, identity.id), "synthetic-credential",
        identity, identity.deviceId, "synthetic-session", identity.sessionExpiresAt)
    return SessionManager("synthetic", PHOTO_TEST_ORIGIN, object : SessionSecretStore {
        override suspend fun load(instance: String, origin: String) = stored
        override suspend fun save(session: StoredSession) { stored = session }
        override suspend fun delete(instance: String, origin: String) { stored = null }
    }, {}, { 0 })
}
