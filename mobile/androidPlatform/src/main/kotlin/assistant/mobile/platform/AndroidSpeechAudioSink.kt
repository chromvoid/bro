package assistant.mobile.platform

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.media.MediaDataSource
import android.media.MediaPlayer
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import assistant.mobile.core.speech.SpeechAudioFormat
import assistant.mobile.core.speech.SpeechAudioSegment
import assistant.mobile.core.speech.SpeechAudioSink
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** One application playback port. Audio never leaves memory and focus spans the segment queue. */
class AndroidSpeechAudioSink(
    context: Context,
    private val onInterrupted: () -> Unit,
) : SpeechAudioSink, AutoCloseable {
    private val audioManager = context.applicationContext.getSystemService(AudioManager::class.java)
    private val main = Handler(Looper.getMainLooper())
    private val gate = Any()
    private var session: Playback? = null

    override suspend fun prepare(): Boolean = withContext(Dispatchers.Main.immediate) {
        stop()
        val current = Playback()
        val focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(attributes())
            .setAcceptsDelayedFocusGain(false)
            .setWillPauseWhenDucked(true)
            .setOnAudioFocusChangeListener({ change ->
                if (change != AudioManager.AUDIOFOCUS_GAIN && detach(current)) {
                    release(current)
                    onInterrupted()
                }
            }, main)
            .build()
        current.focus = focus
        val granted = synchronized(gate) {
            session = current
            audioManager.requestAudioFocus(focus) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        }
        if (!granted) {
            if (detach(current)) release(current)
            false
        } else synchronized(gate) { session === current }
    }

    override suspend fun startPcm(format: SpeechAudioFormat) = withContext(Dispatchers.IO) {
        require(format.contentType == "audio/pcm" && format.sampleRate == RATE && format.channels == 1)
        val current = active()
        val minimum = AudioTrack.getMinBufferSize(RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        check(minimum > 0) { "PCM playback is unavailable" }
        val track = AudioTrack.Builder()
            .setAudioAttributes(attributes())
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(RATE)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .build(),
            )
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setBufferSizeInBytes(maxOf(minimum, RATE * 2 / 5))
            .build()
        val pcm = PcmPlayback(track)
        try {
            check(track.state == AudioTrack.STATE_INITIALIZED && track.sampleRate == RATE && track.channelCount == 1) {
                "PCM playback is unavailable"
            }
            synchronized(gate) {
                requireActive(current)
                check(current.pcm == null && current.encoded == null) { "An audio segment is already playing" }
                current.pcm = pcm
                if (Build.VERSION.SDK_INT >= 31) track.setStartThresholdInFrames(1)
                track.play()
            }
        } catch (failure: Throwable) {
            synchronized(gate) { if (current.pcm === pcm) current.pcm = null }
            releasePcm(pcm)
            throw failure
        }
    }

    override suspend fun writePcm(bytes: ByteArray) = withContext(Dispatchers.IO) {
        val current = active()
        val pcm = synchronized(gate) {
            requireActive(current)
            checkNotNull(current.pcm) { "No PCM segment is playing" }
        }
        val audio = pcm.tail?.let { byteArrayOf(it) + bytes } ?: bytes
        val length = audio.size - audio.size % 2
        pcm.tail = if (length < audio.size) audio.last() else null
        writeNative(current, pcm, audio, length, countAudio = true)
    }

    private suspend fun writeNative(current: Playback, pcm: PcmPlayback, audio: ByteArray, length: Int, countAudio: Boolean) {
        var offset = 0
        var progressedAt = SystemClock.elapsedRealtime()
        while (offset < length) {
            currentCoroutineContext().ensureActive()
            synchronized(gate) { requireActive(current) }
            // Nonblocking native writes keep Stop/cancellation immediate even if an output stalls.
            val written = pcm.track.write(audio, offset, minOf(WRITE_BYTES, length - offset), AudioTrack.WRITE_NON_BLOCKING)
            if (written < 0) {
                synchronized(gate) { requireActive(current) }
                error("PCM playback failed ($written)")
            }
            if (written == 0) {
                check(SystemClock.elapsedRealtime() - progressedAt < 5_000) { "PCM playback stalled" }
                delay(5)
            } else {
                offset += written
                if (countAudio) pcm.writtenBytes += written
                progressedAt = SystemClock.elapsedRealtime()
            }
        }
    }

    /** Drain the native ring before the next segment, retaining audio focus for the whole queue. */
    override suspend fun finishPcm() = withContext(Dispatchers.IO) {
        val current = active()
        val pcm = synchronized(gate) {
            requireActive(current)
            checkNotNull(current.pcm) { "No PCM segment is playing" }
        }
        try {
            check(pcm.tail == null) { "Incomplete PCM sample" }
            val frames = pcm.writtenBytes / 2
            val remaining = (frames - playedFrames(pcm)).coerceAtLeast(0)
            val threshold = if (Build.VERSION.SDK_INT >= 31) pcm.track.startThresholdInFrames else pcm.track.bufferSizeInFrames
            if (remaining in 1 until threshold.toLong()) {
                // Older AudioTrack versions need a full startup buffer, also after an underrun.
                // EOF-only silence opens that gate for a short tail; it never enters the cache.
                val padding = ByteArray((threshold - remaining).toInt() * 2)
                writeNative(current, pcm, padding, padding.size, countAudio = false)
            }
            val deadline = SystemClock.elapsedRealtime() + maxOf(5_000, remaining * 1_000 / RATE + 2_000)
            while (playedFrames(pcm) < frames) {
                currentCoroutineContext().ensureActive()
                synchronized(gate) { requireActive(current) }
                check(SystemClock.elapsedRealtime() < deadline) { "PCM playback stalled" }
                delay(5)
            }
        } finally {
            synchronized(gate) { if (current.pcm === pcm) current.pcm = null }
            releasePcm(pcm)
        }
    }

    override suspend fun playEncoded(segment: SpeechAudioSegment) = withContext(Dispatchers.Main.immediate) {
        require(segment.format.contentType == "audio/wav" || segment.format.contentType == "audio/mpeg")
        val current = active()
        val encoded = EncodedPlayback(MediaPlayer(), MemoryAudioSource(segment.audio))
        try {
            synchronized(gate) {
                requireActive(current)
                check(current.pcm == null && current.encoded == null) { "An audio segment is already playing" }
                current.encoded = encoded
            }
            encoded.player.setAudioAttributes(attributes())
            encoded.player.setOnPreparedListener { player ->
                try {
                    synchronized(gate) {
                        requireActive(current)
                        player.start()
                    }
                } catch (failure: Exception) {
                    encoded.done.completeExceptionally(failure)
                }
            }
            encoded.player.setOnCompletionListener { encoded.done.complete(Unit) }
            encoded.player.setOnErrorListener { _, what, extra ->
                encoded.done.completeExceptionally(IllegalStateException("Audio playback failed ($what/$extra)"))
                true
            }
            encoded.player.setDataSource(encoded.source)
            encoded.player.prepareAsync()
            encoded.done.await()
        } finally {
            synchronized(gate) { if (current.encoded === encoded) current.encoded = null }
            withContext(NonCancellable + Dispatchers.Main.immediate) { releaseEncoded(encoded) }
        }
    }

    override fun stop() {
        val current = synchronized(gate) { session.also { session = null } } ?: return
        release(current)
    }

    override fun close() = stop()

    private fun active(): Playback = synchronized(gate) { session ?: throw stopped() }

    private fun requireActive(current: Playback) {
        if (session !== current) throw stopped()
    }

    private fun detach(current: Playback): Boolean = synchronized(gate) {
        if (session !== current) return false
        session = null
        true
    }

    private fun release(current: Playback) {
        val (pcm, encoded) = synchronized(gate) {
            (current.pcm to current.encoded).also {
                current.pcm = null
                current.encoded = null
            }
        }
        pcm?.let(::releasePcm)
        encoded?.let {
            // Wake a suspended encoded segment even if the platform never calls completion.
            it.done.completeExceptionally(stopped())
            releaseEncoded(it)
        }
        current.focus?.let { audioManager.abandonAudioFocusRequest(it) }
        current.focus = null
    }

    private fun releasePcm(pcm: PcmPlayback) {
        synchronized(pcm) {
            if (pcm.released) return
            pcm.released = true
            pcm.tail = null
            try { pcm.track.pause() } catch (_: IllegalStateException) { }
            try { pcm.track.flush() } catch (_: IllegalStateException) { }
            try { pcm.track.stop() } catch (_: IllegalStateException) { }
            pcm.track.release()
        }
    }

    private fun releaseEncoded(encoded: EncodedPlayback) {
        synchronized(encoded) {
            if (encoded.released) return
            encoded.released = true
            encoded.source.close()
            // MediaPlayer is created and controlled on main; off-main cancellation never waits on it.
            if (Looper.myLooper() == Looper.getMainLooper()) encoded.player.release()
            else main.post { encoded.player.release() }
        }
    }

    private fun playedFrames(pcm: PcmPlayback): Long = pcm.track.playbackHeadPosition.toLong() and 0xffff_ffffL

    private class Playback {
        var focus: AudioFocusRequest? = null
        var pcm: PcmPlayback? = null
        var encoded: EncodedPlayback? = null
    }

    private class PcmPlayback(val track: AudioTrack) {
        var writtenBytes = 0L
        var tail: Byte? = null
        var released = false
    }

    private class EncodedPlayback(val player: MediaPlayer, val source: MemoryAudioSource) {
        val done = CompletableDeferred<Unit>()
        var released = false
    }

    /** Seekable input for WAV/MP3 without a file, URI, base64 copy or disk cache. */
    private class MemoryAudioSource(audio: ByteArray) : MediaDataSource() {
        private var audio: ByteArray? = audio

        @Synchronized
        override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
            val bytes = audio ?: return -1
            if (position < 0 || position >= bytes.size) return -1
            if (size == 0) return 0
            val count = minOf(size, bytes.size - position.toInt())
            bytes.copyInto(buffer, offset, position.toInt(), position.toInt() + count)
            return count
        }

        @Synchronized
        override fun getSize(): Long = audio?.size?.toLong() ?: 0

        @Synchronized
        override fun close() { audio = null }
    }

    private companion object {
        const val RATE = 24_000
        const val WRITE_BYTES = 8_192

        fun attributes(): AudioAttributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ASSISTANT)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()

        fun stopped(): CancellationException = CancellationException("Speech playback stopped")
    }
}
