package assistant.mobile.platform

import android.Manifest
import android.annotation.SuppressLint
import android.app.AppOpsManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.Process
import assistant.mobile.core.dictation.DictationAudioSource
import assistant.mobile.core.dictation.DictationProblem
import assistant.mobile.core.dictation.DictationPermissionResult
import assistant.mobile.core.dictation.DictationPreparationResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.sqrt

/** Foreground chat capture only: no file, service, resampling, restart or audio retained on disk. */
class AndroidDictationAudioSource(
    context: Context,
    private val requestPermission: suspend () -> DictationPermissionResult,
    private val beforeCapture: () -> Unit = {},
) : DictationAudioSource, AutoCloseable {
    private val context = context.applicationContext
    private val audioManager = this.context.getSystemService(AudioManager::class.java)
    private val appOps = this.context.getSystemService(AppOpsManager::class.java)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val gate = Any()
    private var capture: Capture? = null

    override suspend fun prepare(): DictationPreparationResult {
        if (hasPermission()) return DictationPreparationResult.Ready
        return when (requestPermission()) {
            DictationPermissionResult.Granted -> if (hasPermission()) DictationPreparationResult.Ready
                else DictationPreparationResult.Unavailable(DictationProblem.PERMISSION_DENIED)
            DictationPermissionResult.NotAnswered -> DictationPreparationResult.NotAnswered
            DictationPermissionResult.Blocked -> DictationPreparationResult.Unavailable(DictationProblem.PERMISSION_DENIED)
        }
    }

    @SuppressLint("MissingPermission") // Checked again immediately before constructing AudioRecord.
    override fun start(
        onFrame: (ByteArray, Float) -> Unit,
        onInterrupted: (DictationProblem) -> Unit,
    ) {
        release()
        // Our two focus requests share an app UID, so explicitly stop playback before capture.
        beforeCapture()
        if (!hasPermission()) {
            onInterrupted(DictationProblem.PERMISSION_REVOKED)
            return
        }
        val current = Capture(onFrame, onInterrupted)
        synchronized(gate) { capture = current }
        try {
            val focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE)
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ASSISTANT)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build(),
                )
                .setAcceptsDelayedFocusGain(false)
                .setWillPauseWhenDucked(true)
                .setOnAudioFocusChangeListener({ change ->
                    if (change != AudioManager.AUDIOFOCUS_GAIN) interrupt(current, DictationProblem.AUDIO_FOCUS)
                }, Handler(Looper.getMainLooper()))
                .build()
            current.focus = focus
            if (audioManager.requestAudioFocus(focus) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
                interrupt(current, DictationProblem.AUDIO_FOCUS)
                return
            }
            registerGuards(current)
            synchronized(gate) {
                if (capture !== current) return
                val minimum = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
                if (minimum <= 0) {
                    interrupt(current, DictationProblem.UNAVAILABLE)
                    return
                }
                val recorder = AudioRecord.Builder()
                    .setAudioSource(MediaRecorder.AudioSource.VOICE_RECOGNITION)
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setSampleRate(RATE)
                            .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .build(),
                    )
                    .setBufferSizeInBytes(maxOf(minimum, FRAME_SAMPLES * 2 * 4))
                    .build()
                current.recorder = recorder
                if (recorder.state != AudioRecord.STATE_INITIALIZED || recorder.sampleRate != RATE || recorder.channelCount != 1) {
                    interrupt(current, DictationProblem.UNAVAILABLE)
                    return
                }
                recorder.startRecording()
                if (recorder.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                    interrupt(current, DictationProblem.UNAVAILABLE)
                    return
                }
                current.job = scope.launch(Dispatchers.IO) { read(current) }
            }
        } catch (_: SecurityException) {
            interrupt(current, DictationProblem.PERMISSION_REVOKED)
        } catch (_: Exception) {
            interrupt(current, DictationProblem.DEVICE)
        }
    }

    /** A Stop never joins the reader or waits for the next 20 ms frame. */
    override fun stop() {
        val current = synchronized(gate) { capture } ?: return
        if (!hasPermission()) {
            interrupt(current, DictationProblem.PERMISSION_REVOKED)
            return
        }
        val frames = synchronized(gate) {
            if (capture !== current) return
            current.stopping = true
            val available = mutableListOf<PcmFrame>()
            // Preserve the frame whose main-thread delivery was queued behind Stop.
            current.pending?.let(available::add)
            current.pending = null
            // Drain only audio already in the native buffer, once; never await a fresh sample.
            val tail = ShortArray(RATE)
            val count = try {
                current.recorder?.read(tail, 0, tail.size, AudioRecord.READ_NON_BLOCKING) ?: 0
            } catch (_: Exception) { 0 }
            if (count > 0) available.add(pcm(tail, count))
            available
        }
        releaseCapture(current)
        frames.forEach { current.onFrame(it.bytes, it.level) }
    }

    override fun release() {
        val current = synchronized(gate) { capture } ?: return
        releaseCapture(current)
    }

    override fun close() {
        release()
        scope.cancel()
    }

    private suspend fun read(current: Capture) {
        val samples = ShortArray(FRAME_SAMPLES)
        try {
            while (scope.isActive) {
                if (!hasPermission()) {
                    interrupt(current, DictationProblem.PERMISSION_REVOKED)
                    return
                }
                val frame = synchronized(gate) {
                    if (capture !== current || current.stopping) return
                    val count = current.recorder!!.read(samples, 0, samples.size, AudioRecord.READ_NON_BLOCKING)
                    if (count < 0) {
                        interrupt(current, if (hasPermission()) DictationProblem.DEVICE else DictationProblem.PERMISSION_REVOKED)
                        return
                    }
                    if (count == 0) null else pcm(samples, count).also { current.pending = it }
                }
                if (frame == null) {
                    delay(5)
                    continue
                }
                // At most one captured frame awaits main; there is no hidden PCM coroutine queue.
                withContext(Dispatchers.Main.immediate) {
                    val deliver = synchronized(gate) {
                        if (capture === current && current.pending === frame) {
                            current.pending = null
                            true
                        } else false
                    }
                    if (deliver) current.onFrame(frame.bytes, frame.level)
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: SecurityException) {
            interrupt(current, DictationProblem.PERMISSION_REVOKED)
        } catch (_: Exception) {
            interrupt(current, DictationProblem.DEVICE)
        }
    }

    private fun registerGuards(current: Capture) {
        val listener = AppOpsManager.OnOpChangedListener { operation, packageName ->
            if (operation == AppOpsManager.OPSTR_RECORD_AUDIO && (packageName == null || packageName == context.packageName)) {
                val mode = appOps.checkOpNoThrow(operation, Process.myUid(), context.packageName)
                if (!hasPermission() || mode == AppOpsManager.MODE_IGNORED || mode == AppOpsManager.MODE_ERRORED) {
                    interrupt(current, DictationProblem.PERMISSION_REVOKED)
                }
            }
        }
        current.permissions = listener
        appOps.startWatchingMode(AppOpsManager.OPSTR_RECORD_AUDIO, context.packageName, listener)
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action == Intent.ACTION_SCREEN_OFF) interrupt(current, DictationProblem.BACKGROUNDED)
            }
        }
        current.screenOff = receiver
        if (Build.VERSION.SDK_INT >= 33) {
            context.registerReceiver(receiver, IntentFilter(Intent.ACTION_SCREEN_OFF), Context.RECEIVER_NOT_EXPORTED)
        } else {
            context.registerReceiver(receiver, IntentFilter(Intent.ACTION_SCREEN_OFF))
        }
    }

    private fun interrupt(current: Capture, problem: DictationProblem) {
        if (!releaseCapture(current)) return
        scope.launch { current.onInterrupted(problem) }
    }

    /** Synchronous native stop/release, independent of coroutine cancellation or UI delivery. */
    private fun releaseCapture(current: Capture): Boolean {
        val recorder = synchronized(gate) {
            if (capture !== current) return false
            capture = null
            current.pending = null
            current.recorder.also { current.recorder = null }
        }
        current.job?.cancel()
        recorder?.let {
            try { it.stop() } catch (_: Exception) { }
            try { it.release() } catch (_: Exception) { }
        }
        current.focus?.let { audioManager.abandonAudioFocusRequest(it) }
        current.permissions?.let { appOps.stopWatchingMode(it) }
        current.screenOff?.let {
            try { context.unregisterReceiver(it) } catch (_: IllegalArgumentException) { }
        }
        return true
    }

    private fun hasPermission(): Boolean = context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    private fun pcm(samples: ShortArray, count: Int): PcmFrame {
        val bytes = ByteArray(count * 2)
        var squares = 0.0
        for (index in 0 until count) {
            val sample = samples[index].toInt()
            bytes[index * 2] = sample.toByte()
            bytes[index * 2 + 1] = (sample shr 8).toByte()
            squares += sample.toDouble() * sample
        }
        return PcmFrame(bytes, (sqrt(squares / count) / 32768.0).toFloat().coerceIn(0f, 1f))
    }

    private class Capture(
        val onFrame: (ByteArray, Float) -> Unit,
        val onInterrupted: (DictationProblem) -> Unit,
    ) {
        var recorder: AudioRecord? = null
        var job: Job? = null
        var focus: AudioFocusRequest? = null
        var permissions: AppOpsManager.OnOpChangedListener? = null
        var screenOff: BroadcastReceiver? = null
        var pending: PcmFrame? = null
        var stopping = false
    }

    private class PcmFrame(val bytes: ByteArray, val level: Float)

    private companion object {
        const val RATE = 16_000
        const val FRAME_SAMPLES = 320 // 20 ms, 640 bytes; the protocol permits up to 32,000 bytes.
    }
}
