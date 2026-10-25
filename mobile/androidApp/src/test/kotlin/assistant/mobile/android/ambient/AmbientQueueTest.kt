package assistant.mobile.android.ambient

import java.io.File
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AmbientQueueTest {
    @get:Rule val temporary = TemporaryFolder()
    private val binding = AmbientBinding(
        "https://assistant.example", "00000000-0000-4000-8000-000000000001",
        "00000000-0000-4000-8000-000000000002", "2026-10-06T12:00:00Z",
    )
    private val sessionId = "00000000-0000-4000-8000-000000000003"
    private val startedAt = "2026-10-05T12:00:00Z"

    @Test
    fun restartRestoresVerifiedBatchesAndIgnoresUnfinishedWrites() {
        val directory = temporary.newFolder()
        val queue = AmbientQueue(directory)
        val batch = queue.enqueue(binding, sessionId, 0, startedAt, byteArrayOf(1, 2, 3, 4))
        val partial = File(directory, "${UUID.randomUUID()}.partial").apply { mkdir() }
        File(partial, "audio.wav").writeBytes(byteArrayOf(9, 9))
        val restarted = AmbientQueue(directory)
        assertEquals(listOf(batch), restarted.list())
        assertEquals(binding, restarted.storedBinding())
        assertEquals(2L, batch.sampleCount)
        assertEquals(1, restarted.snapshot().count)
        assertEquals(48L, restarted.snapshot().bytes)
        assertTrue(restarted.snapshot().diskBytes > restarted.snapshot().bytes)
        assertTrue(partial.exists())
    }

    @Test
    fun onlyAnExactAcknowledgmentRemovesTheBatch() {
        val queue = AmbientQueue(temporary.newFolder())
        val batch = queue.enqueue(binding, sessionId, 0, startedAt, byteArrayOf(1, 2))
        assertFalse(queue.acknowledge(batch.id, "b".repeat(64)))
        assertFalse(queue.acknowledge(UUID.randomUUID().toString(), batch.sha256))
        assertFalse(queue.acknowledge("../escape", batch.sha256))
        assertEquals(listOf(batch), queue.list())
        assertTrue(queue.acknowledge(batch.id, batch.sha256))
        assertEquals(0, queue.snapshot().count)
        assertFalse(queue.acknowledge(batch.id, batch.sha256))
    }

    @Test
    fun capacityIncludesInterruptedWritesAndTheWholeNextFragment() {
        val directory = temporary.newFolder()
        File(directory, "orphan.partial").writeBytes(ByteArray(50))
        val queue = AmbientQueue(directory, maxBytes = 100)
        queue.ensureCapacity(50)
        expectFull { queue.ensureCapacity(51) }
        expectFull { queue.ensureCapacity() }
        expectFull { queue.enqueue(binding, sessionId, 0, startedAt, byteArrayOf(1, 2)) }
        assertEquals(0, queue.list().size)
        queue.clear()
        assertEquals(0L, queue.snapshot().diskBytes)
    }

    @Test
    fun payloadTamperingIsDetectedBeforeUploadAndMissingManifestsSurfaceAnError() {
        val directory = temporary.newFolder()
        val queue = AmbientQueue(directory)
        val batch = queue.enqueue(binding, sessionId, 0, startedAt, byteArrayOf(1, 2))
        val wav = queue.wavFile(batch)
        wav.writeBytes(wav.readBytes().apply { this[lastIndex] = 7 })
        val restarted = AmbientQueue(directory)
        assertEquals(listOf(batch), restarted.list())
        expectCorrupt { restarted.wavFile(batch) }
        expectCorrupt { queue.acknowledge(batch.id, batch.sha256) }
        assertTrue(wav.exists())
        queue.clear()
        assertEquals(0, queue.list().size)
        assertNull(queue.storedBinding())
        File(directory, UUID.randomUUID().toString()).mkdir()
        expectCorrupt { queue.snapshot() }
        queue.clear()
    }

    @Test
    fun filteringKeepsEachOwnerAndSessionExpirySeparate() {
        val queue = AmbientQueue(temporary.newFolder())
        val first = queue.enqueue(binding, sessionId, 0, startedAt, byteArrayOf(1, 2))
        val changed = binding.copy(deviceId = sessionId)
        val second = queue.enqueue(changed, sessionId, 1, startedAt, byteArrayOf(3, 4))
        assertEquals(listOf(first), queue.list(binding.copy(expiresAt = "2026-10-06T12:00:00+00:00")))
        assertEquals(listOf(second), queue.list(changed))
        assertEquals(1, queue.snapshot(binding).count)
        queue.clear(binding)
        assertEquals(listOf(second), queue.list())
        assertEquals(changed, queue.storedBinding())
    }

    @Test
    fun explicitClearRemovesPartialAndAcknowledgedDirectories() {
        val directory = temporary.newFolder()
        val queue = AmbientQueue(directory)
        queue.rememberBinding(binding)
        for (suffix in listOf("partial", "acknowledged")) {
            val folder = File(directory, "${UUID.randomUUID()}.$suffix").apply { mkdir() }
            File(folder, "audio.wav").writeBytes(byteArrayOf(1, 2))
        }
        queue.clear()
        assertTrue(directory.listFiles()!!.isEmpty())
        assertNull(queue.storedBinding())
    }

    @Test
    fun restartFinishesOnlyAcknowledgedRemovalAndPreservesQueuedAndPartialAudio() {
        val directory = temporary.newFolder()
        val queue = AmbientQueue(directory)
        val accepted = queue.enqueue(binding, sessionId, 0, startedAt, byteArrayOf(1, 2))
        val remaining = queue.enqueue(binding, sessionId, 1, startedAt, byteArrayOf(3, 4))
        val acknowledged = File(directory, "${accepted.id}.acknowledged")
        java.nio.file.Files.move(File(directory, accepted.id).toPath(), acknowledged.toPath())
        val partial = File(directory, "${UUID.randomUUID()}.partial").apply { mkdir() }
        File(partial, "audio.wav").writeBytes(byteArrayOf(5, 6))
        val restarted = AmbientQueue(directory)
        assertFalse(acknowledged.exists())
        assertEquals(listOf(remaining), restarted.list())
        assertTrue(restarted.wavFile(remaining).exists())
        assertTrue(partial.exists())
        assertTrue(restarted.snapshot().diskBytes > remaining.byteSize)
    }

    @Test
    fun mixedLegacyAndCompressedQueueRestartsWithoutRewritingOldAudioOrIds() {
        val directory = temporary.newFolder()
        val queue = AmbientQueue(directory)
        val legacy = queue.enqueue(binding, sessionId, 0, startedAt, byteArrayOf(1, 2))
        val legacyBytes = queue.wavFile(legacy).readBytes()
        val legacyManifest = File(File(directory, legacy.id), "manifest.json").readText()
        assertFalse(legacyManifest.contains("\"format\""))
        assertFalse(legacyManifest.contains("encoded_sample_count"))
        val aac = queue.enqueueEncoded(binding, sessionId, 1, startedAt, 1_024, AmbientAudioFormat.AAC_ADTS, AmbientCodecFixtures.adts())
        val opus = queue.enqueueEncoded(binding, sessionId, 2, startedAt, 320, AmbientAudioFormat.OGG_OPUS, AmbientCodecFixtures.ogg())
        var fullReads = 0
        val restarted = AmbientQueue(directory, AmbientQueue.DEFAULT_MAX_BYTES) { file -> fullReads++; file.readBytes() }
        assertEquals(listOf(legacy, aac, opus), restarted.list())
        assertEquals(0, fullReads)
        assertEquals(1, legacy.version)
        assertEquals(2, aac.version)
        assertEquals(2_048L, aac.encodedSampleCount)
        assertEquals(1_024L, aac.sampleCount)
        assertEquals(16_000, aac.encodedSampleRate)
        assertEquals(960L, opus.encodedSampleCount)
        assertEquals(48_000, opus.encodedSampleRate)
        assertEquals("audio.aac", restarted.audioFile(aac).name)
        assertEquals("audio.ogg", restarted.audioFile(opus).name)
        org.junit.Assert.assertArrayEquals(legacyBytes, restarted.wavFile(legacy).readBytes())
        assertEquals(legacyManifest, File(File(directory, legacy.id), "manifest.json").readText())
        assertTrue(restarted.acknowledge(aac.id, aac.sha256))
        assertEquals(listOf(legacy, opus), restarted.list())
    }

    @Test
    fun compressedPayloadIsFreshlyHashedBeforeUploadAndDeletion() {
        val queue = AmbientQueue(temporary.newFolder())
        val batch = queue.enqueueEncoded(binding, sessionId, 0, startedAt, 1_024, AmbientAudioFormat.AAC_ADTS, AmbientCodecFixtures.adts())
        val file = queue.audioFile(batch)
        val modified = java.nio.file.Files.getLastModifiedTime(file.toPath())
        file.writeBytes(file.readBytes().apply { this[lastIndex] = (this[lastIndex].toInt() xor 1).toByte() })
        java.nio.file.Files.setLastModifiedTime(file.toPath(), modified)
        expectCorrupt { queue.audioFile(batch) }
        expectCorrupt { queue.acknowledge(batch.id, batch.sha256) }
        assertTrue(file.exists())
    }

    @Test
    fun restartRejectsChangedOpusSourceTimeEvenWhenEncodedTimingAndHashAreUnchanged() {
        val directory = temporary.newFolder()
        val queue = AmbientQueue(directory)
        val batch = queue.enqueueEncoded(binding, sessionId, 0, startedAt, 320, AmbientAudioFormat.OGG_OPUS, AmbientCodecFixtures.ogg(320))
        val manifest = File(File(directory, batch.id), "manifest.json")
        val original = manifest.readText()
        val changed = original.replace("\"sample_count\":320", "\"sample_count\":321")
        assertFalse(original == changed)
        manifest.writeText(changed)
        expectCorrupt { AmbientQueue(directory).list() }
        assertTrue(File(File(directory, batch.id), "audio.ogg").exists())
    }

    private fun expectFull(block: () -> Unit) {
        try { block(); throw AssertionError("Expected full queue") } catch (_: QueueFullException) { }
    }

    private fun expectCorrupt(block: () -> Unit) {
        try { block(); throw AssertionError("Expected corrupted queue") } catch (_: QueueCorruptException) { }
    }
}
