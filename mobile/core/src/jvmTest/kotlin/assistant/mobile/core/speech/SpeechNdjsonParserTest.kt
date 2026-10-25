package assistant.mobile.core.speech

import io.ktor.utils.io.ByteChannel
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.writeStringUtf8
import kotlinx.coroutines.async
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith

@OptIn(ExperimentalCoroutinesApi::class)
class SpeechNdjsonParserTest {
    @Test
    fun pcmArrivesBeforeDoneAndOnlyCleanEofCompletes() = runTest {
        val channel = ByteChannel(autoFlush = true)
        val chunks = mutableListOf<ByteArray>()
        val result = async { SpeechNdjsonParser(expectation).read(200, headers(), channel) { bytes, _ -> chunks += bytes } }
        channel.writeStringUtf8(PCM_START + AUDIO)
        runCurrent()
        assertEquals(1, chunks.size)
        assertContentEquals(byteArrayOf(0, 1, 2, 3), chunks.single())
        assertFalse(result.isCompleted)
        channel.writeStringUtf8(DONE)
        runCurrent()
        assertFalse(result.isCompleted)
        channel.close()
        assertContentEquals(chunks.single(), result.await().audio)
    }

    @Test
    fun missingDoneAndMalformedOrderNeverProduceCompleteAudio() = runTest {
        for (body in listOf(PCM_START + AUDIO, AUDIO + DONE, PCM_START + DONE,
            PCM_START + AUDIO + DONE + AUDIO, PCM_START + PCM_START + AUDIO + DONE,
            PCM_START + "{\"type\":\"future\"}\n", "[]\n", "null\n")) {
            assertEquals(SpeechPlaybackProblem.INVALID_RESPONSE, assertFailsWith<SpeechPlaybackFailure> {
                SpeechNdjsonParser(expectation).read(200, headers(), ByteReadChannel(body.encodeToByteArray()))
            }.problem)
        }
    }

    @Test
    fun rejectsIncorrectHeadersBeforePlayingAnyChunk() = runTest {
        for (replacement in listOf(
            mapOf("content-type" to "audio/pcm"), mapOf("x-speech-segment" to "1"),
            mapOf("x-speech-segments" to "2"), mapOf("x-speech-cache-key" to "b".repeat(64)),
            mapOf("x-speech-segment" to "+0"), mapOf("x-speech-segments" to "1.0"),
        )) {
            var writes = 0
            assertFailsWith<SpeechPlaybackFailure> {
                SpeechNdjsonParser(expectation).read(200, headers() + replacement,
                    ByteReadChannel((PCM_START + AUDIO + DONE).encodeToByteArray())) { _, _ -> writes++ }
            }
            assertEquals(0, writes)
        }
        assertFailsWith<SpeechPlaybackFailure> {
            SpeechNdjsonParser(expectation).read(200, headers() - "x-speech-segment", ByteReadChannel((PCM_START + AUDIO + DONE).encodeToByteArray()))
        }
        assertFailsWith<SpeechPlaybackFailure> {
            SpeechNdjsonParser(expectation).read(206, headers(), ByteReadChannel((PCM_START + AUDIO + DONE).encodeToByteArray()))
        }
    }

    @Test
    fun oversizedLineAndAudioAreRejected() = runTest {
        assertFailsWith<SpeechPlaybackFailure> {
            SpeechNdjsonParser(expectation).read(200, headers(), ByteReadChannel(" ".repeat(SPEECH_LINE_MAX_BYTES + 1).encodeToByteArray()))
        }
        val frame = "{\"type\":\"audio\",\"data\":\"" + "AAAA".repeat(4096) + "\"}\n"
        val body = PCM_START + frame.repeat(SPEECH_SEGMENT_MAX_BYTES / 12288 + 1) + DONE
        assertFailsWith<SpeechPlaybackFailure> {
            SpeechNdjsonParser(expectation).read(200, headers(), ByteReadChannel(body.encodeToByteArray()))
        }
    }
}

private const val PCM_START = "{\"type\":\"start\",\"content_type\":\"audio/pcm\",\"sample_rate\":24000,\"channels\":1}\n"
private const val AUDIO = "{\"type\":\"audio\",\"data\":\"AAECAw==\"}\n"
private const val DONE = "{\"type\":\"done\"}\n"
private val expectation = SpeechSegmentExpectation(0, 1, "a".repeat(64))
private fun headers() = mapOf("content-type" to "application/x-ndjson; charset=utf-8",
    "x-speech-segment" to "0", "x-speech-segments" to "1", "x-speech-cache-key" to "a".repeat(64))
