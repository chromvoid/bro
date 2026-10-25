package assistant.mobile.core.photos

import assistant.mobile.core.HttpOutcome
import assistant.mobile.core.NativeHttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class PrivateConversationImagesTest {
    @Test
    fun rawJpegUploadUsesOnlyThePhotoEndpointAndItsReplyContract() = runTest {
        val sessions = photoTestSessions()
        val serverOrigin = "https://moved.invalid"
        val paths = mutableListOf<String>()
        val photo = photoTestImage()
        val http = NativeHttpClient(MockEngine { request ->
            paths += request.url.encodedPath
            assertEquals(serverOrigin, "${request.url.protocol.name}://${request.url.host}")
            assertEquals("POST", request.method.value)
            assertEquals("Bearer synthetic-credential", request.headers["Authorization"])
            assertEquals("synthetic-photo-owner", request.headers["X-Expected-User"])
            assertEquals("image/jpeg", request.body.contentType?.toString() ?: request.headers["Content-Type"])
            assertNull(request.headers["Idempotency-Key"])
            assertContentEquals(photo.jpeg, request.body.toByteArray())
            respond(uploadReply().replace("\"/v1/", "\"$serverOrigin/v1/"), HttpStatusCode.OK, headersOf("Content-Type", "application/json"))
        }, PHOTO_TEST_ORIGIN, sessions, { 0 }, serverOrigin = { serverOrigin })
        try {
            sessions.restore()
            val images = PrivateConversationImages(backgroundScope, sessions, http)
            assertEquals(PHOTO_TEST_ID, images.upload(photo, sessions.lease()!!) { _, _ -> })
            val original = assertIs<PrivateImageResult.Success>(images.image(PHOTO_TEST_ID))
            assertContentEquals(photo.jpeg, original.image.bytes)
            assertEquals(listOf("/v1/conversation/images"), paths)
            images.close()
        } finally { http.close() }
    }

    @Test
    fun taskAndConversationTransportsCannotReadEachOthersRoutes() = runTest {
        val sessions = photoTestSessions()
        var reads = 0
        val http = NativeHttpClient(MockEngine {
            reads++
            respond(photoTestImage().jpeg, HttpStatusCode.OK, headersOf("Content-Type", "image/jpeg"))
        }, PHOTO_TEST_ORIGIN, sessions, { 0 })
        try {
            sessions.restore()
            val tasks = PrivateConversationImages(backgroundScope, sessions, http, imageRoute = PrivateImageRoute.Tasks)
            val chat = PrivateConversationImages(backgroundScope, sessions, http)
            val taskPath = "/v1/tasks/items/$TASK_TEST_ID/images/$PHOTO_TEST_ID"
            for (url in listOf(
                "/v1/conversation/images/$PHOTO_TEST_ID",
                "/v1/tasks/images/$PHOTO_TEST_ID",
                "https://other.invalid$taskPath",
                "$taskPath#private",
                "$taskPath/../../me",
                "$taskPath/preview/extra",
                "$taskPath?token=private",
                "/v1/tasks/items/not-an-id/images/$PHOTO_TEST_ID",
            )) {
                assertEquals(HttpOutcome.RejectedOrigin, assertIs<PrivateImageResult.Failure>(tasks.imageAt(url)).outcome)
            }
            assertEquals(HttpOutcome.RejectedOrigin, assertIs<PrivateImageResult.Failure>(chat.imageAt(taskPath)).outcome)
            assertEquals(HttpOutcome.RejectedRequest, assertIs<PrivateImageResult.Failure>(tasks.image(PHOTO_TEST_ID)).outcome)
            assertEquals(HttpOutcome.RejectedRequest, assertIs<PrivateImageResult.Failure>(chat.taskImage(TASK_TEST_ID, PHOTO_TEST_ID)).outcome)
            assertEquals(HttpOutcome.RejectedRequest, assertIs<PrivateImageResult.Failure>(tasks.taskImage("../private", PHOTO_TEST_ID)).outcome)
            assertEquals(0, reads)
            assertIs<PrivateImageResult.Success>(tasks.imageAt("$taskPath/preview?v=1"))
            assertEquals(1, reads)
            tasks.close()
            chat.close()
        } finally { http.close() }
    }

    @Test
    fun taskCacheIsScopedByTaskAndClearedWhenTheSessionExpires() = runTest {
        val sessions = photoTestSessions()
        val paths = mutableListOf<String>()
        val http = NativeHttpClient(MockEngine { request ->
            paths += request.url.encodedPath
            respond(photoTestImage().jpeg, HttpStatusCode.OK, headersOf("Content-Type", "image/jpeg"))
        }, PHOTO_TEST_ORIGIN, sessions, { 0 })
        try {
            sessions.restore()
            val images = PrivateConversationImages(backgroundScope, sessions, http, imageRoute = PrivateImageRoute.Tasks)
            val anotherTask = "33333333-3333-4333-8333-333333333333"
            assertIs<PrivateImageResult.Success>(images.taskImage(TASK_TEST_ID, PHOTO_TEST_ID, true))
            assertIs<PrivateImageResult.Success>(images.taskImage(anotherTask, PHOTO_TEST_ID, true))
            assertIs<PrivateImageResult.Success>(images.taskImage(TASK_TEST_ID, PHOTO_TEST_ID, true))
            assertEquals(listOf(
                "/v1/tasks/items/$TASK_TEST_ID/images/$PHOTO_TEST_ID/preview",
                "/v1/tasks/items/$anotherTask/images/$PHOTO_TEST_ID/preview",
            ), paths)
            assertTrue(images.cache.byteCount > 0)
            sessions.expire(sessions.lease()!!)
            runCurrent()
            assertEquals(0, images.cache.byteCount)
            assertEquals(HttpOutcome.Unauthorized, assertIs<PrivateImageResult.Failure>(images.taskImage(TASK_TEST_ID, PHOTO_TEST_ID, true)).outcome)
            assertEquals(2, paths.size)
            images.close()
        } finally { http.close() }
    }

    @Test
    fun privatePreviewUsesBearerAndBoundedMemoryCacheAndExpiresWithTheSession() = runTest {
        val sessions = photoTestSessions()
        val serverOrigin = "https://moved.invalid"
        var reads = 0
        val bytes = photoTestImage().jpeg
        val http = NativeHttpClient(MockEngine { request ->
            reads++
            assertEquals(serverOrigin, "${request.url.protocol.name}://${request.url.host}")
            assertEquals("/v1/conversation/images/$PHOTO_TEST_ID/preview", request.url.encodedPath)
            assertEquals("Bearer synthetic-credential", request.headers["Authorization"])
            respond(bytes, HttpStatusCode.OK, headersOf("Content-Type", "image/jpeg"))
        }, PHOTO_TEST_ORIGIN, sessions, { 0 }, serverOrigin = { serverOrigin })
        try {
            sessions.restore()
            val images = PrivateConversationImages(backgroundScope, sessions, http)
            assertEquals(PHOTO_TEST_ORIGIN, sessions.lease()!!.namespace.origin)
            val first = assertIs<PrivateImageResult.Success>(images.imageAt("$serverOrigin/v1/conversation/images/$PHOTO_TEST_ID/preview"))
            first.image.bytes[0] = 0
            assertContentEquals(bytes, assertIs<PrivateImageResult.Success>(images.image(PHOTO_TEST_ID, true)).image.bytes)
            assertEquals(1, reads)
            assertTrue(images.cache.byteCount > 0)
            sessions.expire(sessions.lease()!!)
            runCurrent()
            assertEquals(0, images.cache.byteCount)
            assertEquals(HttpOutcome.Unauthorized, assertIs<PrivateImageResult.Failure>(images.image(PHOTO_TEST_ID)).outcome)
            images.close()
        } finally { http.close() }
    }

    @Test
    fun foreignUrlsAndRedirectsNeverReceiveOrForwardCredentials() = runTest {
        val sessions = photoTestSessions()
        var reads = 0
        val http = NativeHttpClient(MockEngine {
            reads++
            respond("", HttpStatusCode.Found, headersOf("Location", "https://other.invalid/private"))
        }, PHOTO_TEST_ORIGIN, sessions, { 0 })
        try {
            sessions.restore()
            val images = PrivateConversationImages(backgroundScope, sessions, http)
            for (url in listOf("https://other.invalid/v1/conversation/images/$PHOTO_TEST_ID", "//$PHOTO_TEST_ORIGIN/private",
                "/v1/conversation/images/$PHOTO_TEST_ID#private", "/v1/conversation/images/$PHOTO_TEST_ID/../../me", "/v1/me")) {
                assertEquals(HttpOutcome.RejectedOrigin, assertIs<PrivateImageResult.Failure>(images.imageAt(url)).outcome)
            }
            assertEquals(0, reads)
            val redirect = assertIs<HttpOutcome.Failure>(assertIs<PrivateImageResult.Failure>(images.image(PHOTO_TEST_ID)).outcome)
            assertEquals(302, redirect.status)
            assertEquals(1, reads)
            images.close()
        } finally { http.close() }
    }
}

private fun uploadReply() = """{"id":"$PHOTO_TEST_ID","width":20,"height":10,"url":"/v1/conversation/images/$PHOTO_TEST_ID","preview_url":"/v1/conversation/images/$PHOTO_TEST_ID/preview?v=1"}"""
private const val TASK_TEST_ID = "22222222-2222-4222-8222-222222222222"
