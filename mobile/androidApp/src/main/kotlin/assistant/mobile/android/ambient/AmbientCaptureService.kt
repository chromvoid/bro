package assistant.mobile.android.ambient

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioRecordingConfiguration
import android.media.MediaRecorder
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import androidx.core.content.ContextCompat
import androidx.core.app.ServiceCompat
import assistant.mobile.android.R
import assistant.mobile.android.MainActivity
import assistant.mobile.android.NativeOrigins
import android.net.Uri
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Only explicit Start from a visible native screen may create this microphone service. */
class AmbientCaptureService : Service() {
    private lateinit var runtime: AmbientRuntime
    @Volatile private var running = false
    @Volatile private var audio: AudioRecord? = null
    @Volatile private var worker: Thread? = null
    @Volatile private var stopReason: String? = null
    private val microphoneLock = Any()
    private var releasedAudio: AudioRecord? = null
    @Volatile private var wakeLock: PowerManager.WakeLock? = null

    override fun onCreate() {
        super.onCreate()
        runtime = AmbientRuntime.get(this)
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, getString(R.string.ambient_notification_channel), NotificationManager.IMPORTANCE_LOW).apply {
                description = getString(R.string.ambient_notification_description)
            },
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == STOP) {
            runtime.stop()
            stopSelf()
            return START_NOT_STICKY
        }
        if (running || worker?.isAlive == true) return START_NOT_STICKY
        val token = intent?.getLongExtra(TOKEN, -1) ?: -1
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            runtime.startFailed(this, token, "permission_denied")
            stopSelf()
            return START_NOT_STICKY
        }
        try {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, notification(),
                if (Build.VERSION.SDK_INT >= 30) ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else 0)
            val owner = runtime.attach(this, token)
            if (owner == null) {
                runtime.startFailed(this, token, "not_authenticated")
                stopSelf()
                return START_NOT_STICKY
            }
            running = true
            stopReason = null
            worker = Thread({ capture(token, owner) }, "ambient-microphone").apply { start() }
        } catch (_: SecurityException) {
            runtime.startFailed(this, token, "permission_denied")
            stopSelf()
        } catch (failure: Exception) {
            runtime.startFailed(this, token, AmbientMessages.code(failure))
            stopSelf()
        }
        return START_NOT_STICKY
    }

    fun requestStop(message: String? = null) {
        stopReason = message
        running = false
        synchronized(microphoneLock) { try { audio?.stop() } catch (_: IllegalStateException) { } }
        runtime.captureStopped(this, message)
        if (worker?.isAlive != true) stopSelf()
    }

    /** Hand microphone ownership to a call/dictation before its source is opened. */
    fun releaseMicrophoneNow(message: String) {
        requestStop(message)
        synchronized(microphoneLock) {
            val record = audio
            audio = null
            if (record != null) {
                try { record.stop() } catch (_: IllegalStateException) { }
                record.release()
                releasedAudio = record
            }
        }
    }

    private fun capture(token: Long, owner: AmbientBinding) {
        var record: AudioRecord? = null
        var detector: AmbientSileroVad? = null
        var vadWorker: AmbientVadWorker? = null
        var captureFailed = false
        var used = 0
        val pcm = ByteArray(AmbientVadGate.FRAME_SAMPLES * 2)
        val session = UUID.randomUUID().toString()
        var sequence = 0L
        lateinit var encoder: AmbientEncoder
        val pendingWrites = AtomicInteger()
        val writer = ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS, ArrayBlockingQueue<Runnable>(1)) { task -> Thread(task, "ambient-file-writer") }
        fun save(samples: ByteArray, number: Long, began: String) {
            pendingWrites.incrementAndGet()
            try {
                writer.execute {
                    try {
                        if (runtime.validToken(token)) {
                            val encoded = encoder.encode(samples) { runtime.validToken(token) }
                            runtime.enqueue(token, owner, session, number, began, samples.size / 2L, encoded)
                        }
                    } catch (failure: Exception) {
                        if (runtime.validToken(token)) requestStop(AmbientMessages.code(failure))
                    } finally { pendingWrites.decrementAndGet() }
                }
            } catch (_: java.util.concurrent.RejectedExecutionException) {
                pendingWrites.decrementAndGet()
                throw AmbientProblem("capture_failed")
            }
        }
        try {
            encoder = runtime.encoder(token)
            detector = runtime.takeVad(token)
            runtime.reserve(token)
            if (!running) return
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) throw AmbientProblem("permission_denied")
            val minimum = AudioRecord.getMinBufferSize(16_000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            check(minimum > 0) { "Микрофон не поддерживает нужный формат записи." }
            record = AudioRecord.Builder().setAudioSource(MediaRecorder.AudioSource.MIC)
                .setAudioFormat(AudioFormat.Builder().setSampleRate(16_000).setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
                .setBufferSizeInBytes(maxOf(minimum * 2, 16_000)).build()
            check(record.state == AudioRecord.STATE_INITIALIZED) { "Не удалось открыть микрофон." }
            synchronized(microphoneLock) {
                if (!running) return
                audio = record
            }
            if (Build.VERSION.SDK_INT >= 29) {
                record.registerAudioRecordingCallback(mainExecutor, object : AudioManager.AudioRecordingCallback() {
                    override fun onRecordingConfigChanged(configs: MutableList<AudioRecordingConfiguration>) {
                        if (configs.any { it.isClientSilenced }) requestStop("microphone_busy")
                    }
                })
            }
            val captureAnchor = Instant.now()
            runtime.beginCapture(token) {
                synchronized(microphoneLock) {
                    if (!running || audio !== record) throw AmbientProblem("microphone_busy")
                    record.startRecording()
                }
            }
            vadWorker = AmbientVadWorker(checkNotNull(detector), { runtime.validToken(token) }, { segment ->
                try {
                    runtime.reserve(token, pendingWrites.get())
                    val startedAt = AmbientCaptureTimeline.startedAt(captureAnchor, segment.startSample)
                    save(segment.pcm, sequence++, startedAt)
                } catch (failure: Exception) { throw AmbientProblem(AmbientMessages.code(failure)) }
            }, { code -> requestStop(code) })
            detector = null // The VAD worker closes its own session after its final inference.
            val awake = getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "assistant:ambient-capture").apply {
                setReferenceCounted(false)
                acquire(10 * 60 * 1000L)
            }
            wakeLock = awake
            var renewedAt = SystemClock.elapsedRealtime()
            val manager = getSystemService(AudioManager::class.java)
            while (running) {
                if (SystemClock.elapsedRealtime() - renewedAt >= 5 * 60 * 1000L) {
                    awake.acquire(10 * 60 * 1000L)
                    renewedAt = SystemClock.elapsedRealtime()
                }
                if (!runtime.validToken(token)) { requestStop("not_authenticated"); break }
                if (!owner.validAt(Instant.now())) { requestStop("not_authenticated"); break }
                if (manager.mode == AudioManager.MODE_IN_CALL || manager.mode == AudioManager.MODE_IN_COMMUNICATION) {
                    requestStop("microphone_busy"); break
                }
                if (Build.VERSION.SDK_INT >= 29 && record.activeRecordingConfiguration?.isClientSilenced == true) {
                    requestStop("microphone_busy"); break
                }
                val count = record.read(pcm, used, pcm.size - used, AudioRecord.READ_BLOCKING)
                if (count < 0) {
                    if (!running) break
                    error("Микрофон перестал передавать звук. Запись остановлена.")
                }
                if (count == 0) continue
                check(count % 2 == 0) { "Микрофон передал неполный PCM-сэмпл." }
                used += count
                if (used == pcm.size) {
                    if (!vadWorker.accept(pcm)) { requestStop(stopReason ?: "capture_failed"); break }
                    used = 0
                }
            }
        } catch (_: SecurityException) {
            captureFailed = true
            if (stopReason == null) stopReason = "permission_denied"
        } catch (failure: Exception) {
            captureFailed = true
            if (stopReason == null) stopReason = AmbientMessages.code(failure)
        } finally {
            running = false
            synchronized(microphoneLock) {
                if (record !== releasedAudio) {
                    audio = null
                    try { record?.stop() } catch (_: IllegalStateException) { }
                    record?.release()
                    releasedAudio = record
                }
            }
            runtime.captureStopped(this, stopReason)
            wakeLock?.let { if (it.isHeld) it.release() }
            wakeLock = null
            // Only an explicit, error-free Stop finalizes pending speech. Logout, lost microphone,
            // expiry and pipeline failures discard RAM buffers while retaining already queued clips.
            if (captureFailed || stopReason != null || !runtime.validToken(token)) vadWorker?.cancel()
            else if (used >= 2) {
                vadWorker?.accept(pcm.copyOf(used - used % 2))
            }
            vadWorker?.finish()
            detector?.close()
            pcm.fill(0)
            writer.shutdown()
            // The microphone has been released; finish at most two bounded encode/disk jobs before detaching.
            try { writer.awaitTermination(30, TimeUnit.SECONDS) }
            catch (_: InterruptedException) { Thread.currentThread().interrupt() }
            // Keep Start unavailable until the previous native session has actually closed.
            // UI and Stop stay responsive: this wait owns neither microphone nor wake lock.
            vadWorker?.awaitClosed()
            runtime.detached(this, stopReason)
            Handler(Looper.getMainLooper()).post {
                ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
    }

    private fun notification(): Notification {
        val origins = NativeOrigins.get(this)
        val server = origins.serverOrigin(runtime.nativeOrigin() ?: origins.homeOrigin)
        val open = PendingIntent.getActivity(this, 0, Intent(Intent.ACTION_VIEW, Uri.parse(server + "/settings/speech"), this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val stop = PendingIntent.getService(this, 1, Intent(this, AmbientCaptureService::class.java).setAction(STOP), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_launcher).setContentTitle(getString(R.string.ambient_notification_title))
            .setContentText(getString(R.string.ambient_notification_text))
            .setOngoing(true).setContentIntent(open)
            .addAction(Notification.Action.Builder(null, getString(R.string.ambient_notification_stop), stop).build()).build()
    }

    override fun onBind(intent: Intent?): IBinder? = null
    override fun onDestroy() {
        requestStop(stopReason)
        super.onDestroy()
    }

    companion object {
        const val TOKEN = "ambient-generation"
        private const val STOP = "assistant.ambient.STOP"
        internal const val CHANNEL = "ambient-audio"
        internal const val NOTIFICATION_ID = 9021
    }
}
