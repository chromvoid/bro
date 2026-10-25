package assistant.mobile.platform

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaDataSource
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class RecordingPlayerState(
    val loading: Boolean = false,
    val ready: Boolean = false,
    val playing: Boolean = false,
    val positionMs: Int = 0,
    val durationMs: Int = 0,
    val failed: Boolean = false,
    val canUseCompatible: Boolean = false,
)

/** Private seekable playback. Neither audio nor the authenticated request reaches a file or URI. */
class AndroidRecordingPlayer(context: Context) : AutoCloseable {
    private val manager = context.applicationContext.getSystemService(AudioManager::class.java)
    private val handler = Handler(Looper.getMainLooper())
    private val mutableState = MutableStateFlow(RecordingPlayerState())
    val state: StateFlow<RecordingPlayerState> = mutableState.asStateFlow()
    private var player: MediaPlayer? = null
    private var source: MemorySource? = null
    private var focus: AudioFocusRequest? = null
    private var focusGeneration = 0L
    private val tick = object : Runnable {
        override fun run() {
            val current = player ?: return
            if (mutableState.value.ready) {
                val position = runCatching { current.currentPosition }.getOrElse { clear(); return }
                mutableState.value = mutableState.value.copy(positionMs = position)
                if (mutableState.value.playing) handler.postDelayed(this, 200)
            }
        }
    }

    fun loading() { clear(); mutableState.value = RecordingPlayerState(loading = true) }

    fun failed() { clear(); mutableState.value = RecordingPlayerState(failed = true) }

    /** Called on the main thread, after the session controller checked ownership of the reply. */
    fun play(bytes: ByteArray, canUseCompatible: Boolean) {
        clear()
        val current = MediaPlayer()
        val memory = MemorySource(bytes)
        player = current
        source = memory
        mutableState.value = RecordingPlayerState(loading = true)
        try {
            current.setAudioAttributes(attributes())
            current.setDataSource(memory)
            current.setOnPreparedListener {
                if (player !== current) return@setOnPreparedListener
                mutableState.value = RecordingPlayerState(ready = true, durationMs = current.duration.coerceAtLeast(0))
                resume()
            }
            current.setOnCompletionListener {
                if (player === current) {
                    handler.removeCallbacks(tick)
                    mutableState.value = mutableState.value.copy(playing = false, positionMs = mutableState.value.durationMs)
                    abandonFocus()
                }
            }
            current.setOnErrorListener { _, what, extra ->
                if (player === current) {
                    val decoder = what == MediaPlayer.MEDIA_ERROR_UNKNOWN ||
                        extra == MediaPlayer.MEDIA_ERROR_MALFORMED || extra == MediaPlayer.MEDIA_ERROR_UNSUPPORTED
                    clear()
                    mutableState.value = RecordingPlayerState(failed = true, canUseCompatible = canUseCompatible && decoder)
                }
                true
            }
            current.prepareAsync()
        } catch (_: Exception) {
            clear()
            mutableState.value = RecordingPlayerState(failed = true, canUseCompatible = canUseCompatible)
        }
    }

    fun pause() {
        val current = player ?: return
        if (!mutableState.value.ready) return
        runCatching { current.pause() }.onFailure { failed(); return }
        handler.removeCallbacks(tick)
        mutableState.value = mutableState.value.copy(playing = false)
        abandonFocus()
    }

    fun resume() {
        val current = player ?: return
        if (!mutableState.value.ready || mutableState.value.playing) return
        if (!acquireFocus()) { failed(); return }
        runCatching {
            if (mutableState.value.positionMs >= mutableState.value.durationMs) current.seekTo(0)
            current.start()
            mutableState.value = mutableState.value.copy(playing = true, positionMs = current.currentPosition)
            handler.post(tick)
        }.onFailure { failed() }
    }

    fun seek(positionMs: Int) {
        val current = player ?: return
        if (!mutableState.value.ready) return
        val position = positionMs.coerceIn(0, mutableState.value.durationMs)
        runCatching { current.seekTo(position) }.onFailure { failed(); return }
        mutableState.value = mutableState.value.copy(positionMs = position)
    }

    fun clear() {
        handler.removeCallbacks(tick)
        val old = player
        player = null
        old?.setOnPreparedListener(null)
        old?.setOnCompletionListener(null)
        old?.setOnErrorListener(null)
        runCatching { old?.release() }
        source?.close()
        source = null
        abandonFocus()
        mutableState.value = RecordingPlayerState()
    }

    override fun close() = clear()

    private fun acquireFocus(): Boolean {
        if (focus != null) return true
        val generation = ++focusGeneration
        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(attributes())
            .setAcceptsDelayedFocusGain(false)
            .setWillPauseWhenDucked(true)
            .setOnAudioFocusChangeListener({ change ->
                // A call, dictation or spoken reply owns the next turn. Resumption is explicit.
                if (change != AudioManager.AUDIOFOCUS_GAIN && generation == focusGeneration) clear()
            }, handler).build()
        focus = request
        return (manager.requestAudioFocus(request) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED && focus === request).also {
            if (!it) abandonFocus()
        }
    }

    private fun abandonFocus() {
        focusGeneration++
        val previous = focus
        focus = null
        previous?.let(manager::abandonAudioFocusRequest)
    }

    private class MemorySource(bytes: ByteArray) : MediaDataSource() {
        private var bytes: ByteArray? = bytes
        @Synchronized override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
            val audio = bytes ?: return -1
            if (size == 0) return 0
            if (position < 0 || position >= audio.size) return -1
            val count = minOf(size, audio.size - position.toInt())
            audio.copyInto(buffer, offset, position.toInt(), position.toInt() + count)
            return count
        }
        @Synchronized override fun getSize(): Long = bytes?.size?.toLong() ?: 0
        @Synchronized override fun close() { bytes = null }
    }

    private fun attributes() = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()
}
