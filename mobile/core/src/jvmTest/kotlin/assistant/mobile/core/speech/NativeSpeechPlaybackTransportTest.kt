package assistant.mobile.core.speech

import assistant.mobile.core.NativeHttpClient
import assistant.mobile.core.NativeJson
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockEngineConfig
import io.ktor.client.engine.mock.respond
import io.ktor.http.Headers
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.utils.io.ByteChannel
import io.ktor.utils.io.writeStringUtf8
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class NativeSpeechPlaybackTransportTest {
    @Test
    fun prepareAndSegmentUseNativePostAndPinnedCacheKeyWithoutJournalOrRetries() = runTest {
        val sessions = speechTestSessions()
        val requests = mutableListOf<Pair<String, JsonObject>>()
        // Keep the fake producer on the test clock: virtual read deadlines must not race IO.
        val http = NativeHttpClient(MockEngine(MockEngineConfig().apply {
            dispatcher = StandardTestDispatcher(testScheduler)
            addHandler { request ->
                assertEquals("POST", request.method.value)
                assertEquals("Bearer synthetic-credential", request.headers["Authorization"])
                assertEquals("synthetic-owner", request.headers["X-Expected-User"])
                assertEquals("application/json", request.body.contentType?.toString() ?: request.headers["Content-Type"])
                val body = NativeJson.decodeFromString<JsonObject>((request.body as OutgoingContent.ByteArrayContent).bytes().decodeToString())
                requests += request.url.encodedPath to body
                if (request.url.encodedPath.endsWith("/prepare")) respond("""{"cache_key":"${"a".repeat(64)}","segments":2,"connection_id":"connection-one"}""")
                else {
                    assertEquals("application/x-ndjson", request.headers["Accept"])
                    respond(wireAudio, HttpStatusCode.OK, streamHeaders(segments = 2))
                }
            }
        }), SPEECH_TEST_ORIGIN, sessions, { 0 })
        try {
            sessions.restore()
            val transport = NativeSpeechPlaybackTransport(http, sessions)
            val lease = sessions.lease()!!
            val plan = transport.prepare("turn-one", lease)
            var streamed = 0
            val result = transport.segment("turn-one", "synthetic-playback", plan, 0, lease) { _, _ -> streamed++ }
            assertEquals(1, streamed)
            assertContentEquals(byteArrayOf(0, 1, 2, 3), result.audio)
            assertEquals(listOf("/v1/conversation/turns/turn-one/speech/prepare", "/v1/conversation/turns/turn-one/speech"), requests.map { it.first })
            assertTrue(requests[0].second.isEmpty())
            assertEquals("synthetic-playback", requests[1].second["playback_id"]!!.jsonPrimitive.content)
            assertEquals("a".repeat(64), requests[1].second["cache_key"]!!.jsonPrimitive.content)
            assertEquals(0, requests[1].second["segment"]!!.jsonPrimitive.int)
        } finally { http.close() }
    }

    @Test
    fun wrongResponseHeadersRejectAudioBeforeTheFirstPlaybackWrite() = runTest {
        val sessions = speechTestSessions()
        var writes = 0
        val http = NativeHttpClient(MockEngine(MockEngineConfig().apply {
            dispatcher = StandardTestDispatcher(testScheduler)
            addHandler { respond(wireAudio, HttpStatusCode.OK, streamHeaders(key = "b".repeat(64))) }
        }), SPEECH_TEST_ORIGIN, sessions, { 0 })
        try {
            sessions.restore()
            val error = assertFailsWith<SpeechPlaybackFailure> {
                NativeSpeechPlaybackTransport(http, sessions).segment("turn-one", "playback-one",
                    SpeechPlaybackPlan("a".repeat(64), 1, "connection-one"), 0, sessions.lease()!!) { _, _ -> writes++ }
            }
            assertEquals(SpeechPlaybackProblem.INVALID_RESPONSE, error.problem)
            assertEquals(0, writes)
        } finally { http.close() }
    }

    @Test
    fun staleLeaseDuringAudioStopsFurtherCallbacksAndCannotReturnCompletedAudio() = runTest {
        val sessions = speechTestSessions()
        val channel = ByteChannel(autoFlush = true)
        val engine = MockEngine(MockEngineConfig().apply {
            dispatcher = StandardTestDispatcher(testScheduler)
            addHandler { respond(channel, HttpStatusCode.OK, streamHeaders()) }
        })
        val http = NativeHttpClient(engine, SPEECH_TEST_ORIGIN, sessions, { 0 })
        try {
            sessions.restore()
            val lease = sessions.lease()!!
            var writes = 0
            val firstPcm = CompletableDeferred<Unit>()
            val result = async {
                runCatching { NativeSpeechPlaybackTransport(http, sessions).segment("turn-one", "playback-one",
                    SpeechPlaybackPlan("a".repeat(64), 1, "connection-one"), 0, lease) { _, _ ->
                    writes++
                    firstPcm.complete(Unit)
                } }
            }
            runCurrent()
            channel.writeStringUtf8(wireStart + wireChunk)
            firstPcm.await()
            assertEquals(1, writes)
            assertFalse(result.isCompleted)
            sessions.expire(lease)
            channel.writeStringUtf8(wireChunk + "{\"type\":\"done\"}\n")
            channel.close()
            val error = result.await().exceptionOrNull() as SpeechPlaybackFailure
            assertEquals(SpeechPlaybackProblem.SESSION_EXPIRED, error.problem)
            assertEquals(1, writes)
        } finally { http.close() }
    }
}

private const val wireStart = "{\"type\":\"start\",\"content_type\":\"audio/pcm\",\"sample_rate\":24000,\"channels\":1}\n"
private const val wireChunk = "{\"type\":\"audio\",\"data\":\"AAECAw==\"}\n"
private const val wireAudio = wireStart + wireChunk + "{\"type\":\"done\"}\n"
private fun streamHeaders(segments: Int = 1, key: String? = "a".repeat(64)): Headers = Headers.build {
    append("Content-Type", "application/x-ndjson")
    append("X-Speech-Segments", segments.toString())
    append("X-Speech-Segment", "0")
    key?.let { append("X-Speech-Cache-Key", it) }
}
