package assistant.mobile.core.recordings

import assistant.mobile.core.FEATURE_NOW
import assistant.mobile.core.FEATURE_ORIGIN
import assistant.mobile.core.FeatureSessionSecrets
import assistant.mobile.core.NativeHttpClient
import assistant.mobile.core.NativeJson
import assistant.mobile.core.SessionManager
import assistant.mobile.core.featureRecord
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockEngineConfig
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class RecordingsControllerTest {
    @Test
    fun unknownRetryIsNeverRepeatedUntilTheOwnerRereadsTheState() = runTest {
        val fixture = ArchiveFixture(this)
        try {
            fixture.start()
            fixture.retryNetworkFailure = true
            assertFalse(fixture.archive.mutate(RecordingMutation.Retry, batchId(1)))
            assertTrue(fixture.archive.state.value!!.mutationUnknown)
            assertFalse(fixture.archive.mutate(RecordingMutation.Retry, batchId(1)))
            assertEquals(1, fixture.requests.count { it.method == HttpMethod.Post })
            fixture.archive.refresh()
            assertFalse(fixture.archive.state.value!!.mutationUnknown)
            fixture.retryNetworkFailure = false
            assertTrue(fixture.archive.mutate(RecordingMutation.Retry, batchId(1)))
            assertEquals(2, fixture.requests.count { it.method == HttpMethod.Post })
            assertTrue(fixture.requests.filter { it.method != HttpMethod.Get }.all { it.headers["Idempotency-Key"] == null })
        } finally { fixture.close() }
    }

    @Test
    fun deletionNeedsAnIdentityCheckedReceiptAndKeepsTheRowOnAnInvalidAcknowledgement() = runTest {
        val fixture = ArchiveFixture(this)
        try {
            fixture.start()
            fixture.invalidDeletion = true
            assertFalse(fixture.archive.mutate(RecordingMutation.Delete, batchId(1)))
            assertTrue(fixture.archive.state.value!!.mutationUnknown)
            assertEquals(batchId(1), fixture.archive.state.value!!.items.single().id)
            fixture.archive.refresh()
            fixture.invalidDeletion = false
            assertTrue(fixture.archive.mutate(RecordingMutation.Delete, batchId(1)))
            assertTrue(fixture.archive.state.value!!.items.isEmpty())
            assertTrue(fixture.archive.state.value!!.loaded)
            assertEquals(2, fixture.requests.count { it.method == HttpMethod.Delete })
        } finally { fixture.close() }
    }

    @Test
    fun forbiddenUsageClearsPreviouslyLoadedAudioAndTextAlongWithTheList() = runTest {
        val fixture = ArchiveFixture(this)
        try {
            fixture.start()
            fixture.archive.select(batchId(1))
            fixture.forbiddenUsage = true
            fixture.archive.refresh()
            val state = fixture.archive.state.value!!
            assertFalse(state.loaded)
            assertTrue(state.items.isEmpty())
            assertNull(state.transcript)
            assertNull(state.selectedId)
            assertNull(state.usage)
            assertNotNull(state.error)
        } finally { fixture.close() }
    }

    @Test
    fun privatePlaybackUsesNativeBearerWithoutFollowingRedirectsAndSupportsExplicitWavFallback() = runTest {
        val fixture = ArchiveFixture(this)
        try {
            fixture.start()
            val audio = assertNotNull(fixture.archive.playback(batchId(1)))
            assertContentEquals(SYNTHETIC_WAV, audio.bytes)
            fixture.redirectAudio = true
            assertNull(fixture.archive.playback(batchId(1)))
            assertTrue(fixture.requests.all { it.url.host == "synthetic.invalid" })
            fixture.redirectAudio = false
            assertNotNull(fixture.archive.playback(batchId(1), compatible = true))
            val fallback = fixture.requests.last()
            assertTrue(fallback.url.encodedPath.endsWith("/playback"))
            assertEquals("wav", fallback.url.parameters["format"])
            fixture.htmlAudio = true
            assertNull(fixture.archive.playback(batchId(1)))
        } finally { fixture.close() }
    }

    @Test
    fun expiringSessionWhilePlaybackIsLoadingDiscardsLatePrivateBytesAndArchiveState() = runTest {
        val fixture = ArchiveFixture(this)
        try {
            fixture.start()
            fixture.archive.select(batchId(1))
            val gate = CompletableDeferred<Unit>()
            fixture.audioGate = gate
            val pending = async { fixture.archive.playback(batchId(1)) }
            runCurrent()
            fixture.sessions.expire(fixture.sessions.lease()!!)
            runCurrent()
            assertNull(fixture.archive.state.value)
            gate.complete(Unit)
            assertNull(pending.await())
        } finally { fixture.close() }
    }

}

private const val SESSION_ID = "22222222-2222-4222-8222-222222222222"
private fun batchId(index: Int): String = "11111111-1111-4111-8111-${index.toString().padStart(12, '0')}"
private fun summary(status: TranscriptStatus = TranscriptStatus.Pending, preview: String? = null, updatedAt: String? = null) =
    RecordingSummary(status, preview, null, updatedAt, false)
private fun recording(index: Int): Recording = Recording(batchId(index), SESSION_ID, index.toLong(), "synthetic", true,
    RecordingFormat.Wav, 12, 10_000, null, "2026-10-17T09:00:00Z", "2026-10-17T09:01:00Z", "stored", summary())
private fun transcript(index: Int, status: TranscriptStatus = TranscriptStatus.Ready): RecordingTranscript = RecordingTranscript(
    batchId(index), status, if (status == TranscriptStatus.Ready) "Private synthetic transcript" else null,
    null, "2026-10-17T09:02:00Z", false, "synthetic", "synthetic", "2026-10-17T09:02:00Z")
private val SYNTHETIC_WAV = "RIFF0000WAVE".encodeToByteArray()

@OptIn(ExperimentalCoroutinesApi::class)
private class ArchiveFixture(private val test: TestScope) {
    private val record = featureRecord().let { it.copy(identity = it.identity.copy(timezone = "Europe/Moscow")) }
    val credential = record.credential
    val userId = record.namespace.userId
    val sessions = SessionManager("synthetic", FEATURE_ORIGIN, FeatureSessionSecrets(record), {}, { FEATURE_NOW })
    val requests = mutableListOf<HttpRequestData>()
    var page = listOf(recording(1))
    var more: List<Recording> = emptyList()
    var transcriptStatus = TranscriptStatus.Ready
    val transcripts = mutableMapOf<String, RecordingTranscript>()
    var retryNetworkFailure = false
    var invalidDeletion = false
    var forbiddenUsage = false
    var redirectAudio = false
    var htmlAudio = false
    var audioGate: CompletableDeferred<Unit>? = null
    val http = NativeHttpClient(MockEngine(MockEngineConfig().apply {
        dispatcher = StandardTestDispatcher(test.testScheduler)
        addHandler { request ->
            requests += request
            val path = request.url.encodedPath
            val id = path.split('/').getOrNull(4)
            when {
                path == "/v1/ambient-audio/recordings" -> {
                    val cursor = request.url.parameters["cursor"]
                    respond(NativeJson.encodeToString(RecordingPage(if (cursor == null) page else more, if (cursor == null) "cursor +/?=" else null)))
                }
                path == "/v1/ambient-audio" -> {
                    if (forbiddenUsage) respond("{}", HttpStatusCode.Forbidden)
                    else respond(NativeJson.encodeToString(RecordingUsage(12, RecordingLimits(30, 1_000_000))))
                }
                path.endsWith("/transcription/retry") -> {
                    if (retryNetworkFailure) error("Synthetic lost response")
                    respond(NativeJson.encodeToString(transcripts[id] ?: transcript(1, transcriptStatus).copy(id = requireNotNull(id))))
                }
                path.endsWith("/transcription") -> respond(NativeJson.encodeToString(
                    transcripts[id] ?: transcript(1, transcriptStatus).copy(id = requireNotNull(id))))
                request.method == HttpMethod.Delete -> {
                    if (!invalidDeletion) page = page.filterNot { it.id == id }
                    respond("{\"id\":\"${if (invalidDeletion) batchId(99) else id}\",\"deleted\":true}")
                }
                path.startsWith("/v1/ambient-audio/batches/") -> {
                    audioGate?.await()
                    when {
                        redirectAudio -> respond("", HttpStatusCode.Found, headersOf("Location", "https://other.invalid/private.wav"))
                        htmlAudio -> respond("<html>private</html>", HttpStatusCode.OK, headersOf("Content-Type", "text/html"))
                        else -> respond(SYNTHETIC_WAV, HttpStatusCode.OK, headersOf("Content-Type", "audio/wav"))
                    }
                }
                path == "/v1/me" -> respond(NativeJson.encodeToString(record.identity))
                else -> error("Unexpected archive request: ${request.method.value} $path")
            }
        }
    }), FEATURE_ORIGIN, sessions, { FEATURE_NOW })
    val archive = RecordingsController(test.backgroundScope, sessions, http)

    suspend fun start() {
        sessions.restore()
        archive.activate()
        archive.state.first { it?.loaded == true }
        test.runCurrent()
    }

    fun close() { archive.close(); http.close() }
}
