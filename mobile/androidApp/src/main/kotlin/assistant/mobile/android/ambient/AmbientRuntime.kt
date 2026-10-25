package assistant.mobile.android.ambient

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.content.Intent
import androidx.core.content.ContextCompat
import assistant.mobile.core.AccountNamespace
import assistant.mobile.core.NativeHttpClient
import assistant.mobile.core.SessionManager
import assistant.mobile.core.SessionLease
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import java.io.File
import java.time.Instant
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

data class AmbientState(
    val recording: Boolean = false,
    val checking: Boolean = false,
    val canStart: Boolean = true,
    val queuedBatches: Int = 0,
    val queuedBytes: Long = 0,
    val lastUploadedAt: String? = null,
    val error: String? = null,
)

/** Application lifetime state: the Activity is a control surface, never the microphone owner. */
class AmbientRuntime private constructor(context: Context) {
    private val applicationContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var session: AmbientSessionPort? = null
    private var sessionObserver: Job? = null
    @Volatile private var boundSessions: SessionManager? = null
    private val microphoneOwners = mutableSetOf<String>()
    @Volatile private var checking = false
    private val mutableState = MutableStateFlow(AmbientState())
    val state: StateFlow<AmbientState> = mutableState.asStateFlow()
    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadScheduledExecutor()
    private val queue = AmbientQueue(File(context.filesDir, "ambient-audio"))
    private val nativeOwnershipStore = AmbientNativeOwnershipStore(File(context.noBackupFilesDir, "ambient-native-owner.json"))
    private var nativeOwnership: AmbientNativeOwnership? = null
    private val guard = Any()
    @Volatile private var generation = 0L
    @Volatile private var http: AmbientHttp? = null
    @Volatile private var hint: AmbientBinding? = null
    @Volatile private var hintKnown = false
    @Volatile var binding: AmbientBinding? = null
        private set
    @Volatile var recording = false
        private set
    @Volatile var error: String? = null
        private set
    @Volatile var lastUploadedAt: String? = null
        private set
    @Volatile private var queuedCount = 0
    @Volatile private var queuedBytes = 0L
    @Volatile private var queueDiskBytes = 0L
    @Volatile private var service: AmbientCaptureService? = null
    @Volatile private var serviceToken = -1L
    @Volatile private var encoder: AmbientEncoder? = null
    private var preparedVad: AmbientSileroVad? = null
    private var retrySeconds = 2L
    private var pumpScheduled = false
    @Volatile private var terminal = false

    init {
        try {
            nativeOwnership = nativeOwnershipStore.load()
            binding = queue.storedBinding()
            io.execute {
                try { synchronized(guard) { refreshQueue() }; schedulePump(0) }
                catch (failure: Exception) { fail(AmbientMessages.code(failure)) }
            }
        }
        catch (failure: Exception) { fail(AmbientMessages.code(failure)) }
    }

    private fun notifyState() {
        mutableState.value = AmbientState(recording, checking, canStart(), queuedCount, queuedBytes, lastUploadedAt, error)
    }
    private fun fail(message: String) { error = message; notifyState() }

    /** Authority stays with the native session manager even when an Activity is destroyed. */
    fun bind(sessions: SessionManager, nativeHttp: NativeHttpClient) {
        synchronized(guard) {
            if (boundSessions === sessions) return
            sessionObserver?.cancel()
            http?.cancel()
            boundSessions = sessions
            session = NativeAmbientSessionPort(sessions) { nativeHttp.serverOrigin }
        }
        sessionObserver = scope.launch {
            combine(sessions.leases, sessions.identity) { lease, identity -> lease to identity }
                .collect { (lease, _) ->
                    synchronized(guard) {
                        if (boundSessions !== sessions) return@collect
                        val identity = sessions.identity.value
                        val next = if (lease != null && sessions.isCurrent(lease) && identity != null) runCatching {
                            AmbientBinding(sessions.origin, lease.namespace.userId, identity.deviceId, identity.sessionExpiresAt)
                        }.getOrNull() else null
                        if (next != null && lease != null) {
                            // Logout invalidates the lease before its deterministic cleanup callback.
                            // Retain this exact native ownership proof when the observer later sees null.
                            val confirmed = AmbientNativeOwnership(lease.namespace, next)
                            nativeOwnershipStore.save(confirmed)
                            nativeOwnership = confirmed
                        }
                        account(next, preserveQueue = next == null)
                    }
                }
        }
    }

    /** A null lease means expiry until explicit logout separately clears that account's outbox. */
    private fun account(next: AmbientBinding?, preserveQueue: Boolean) {
        synchronized(guard) {
            val old = binding ?: hint
            val changed = next == null || (old != null && !old.matches(next))
            hintKnown = true
            hint = next
            if (changed) {
                generation++
                val interrupted = recording || service != null
                encoder = null
                closePreparedVad()
                http?.cancel()
                stop(AmbientAccountPolicy.afterChange(interrupted, queuedCount))
                if (preserveQueue || (old != null && queue.snapshot().diskBytes > 0)) {
                    terminal = true
                    // Session restoration can finish before the startup outbox scan. Inspect the
                    // durable owner now so an expired queued recording never looks empty/idle.
                    try {
                        refreshQueue()
                        error = if (next != null && old != null && !old.matches(next) && queueDiskBytes > 0)
                            "previous_queue" else AmbientAccountPolicy.afterChange(interrupted, queuedCount)
                    } catch (failure: Exception) { error = AmbientMessages.code(failure) }
                } else {
                    queue.clear()
                    binding = next
                    queuedCount = 0
                    queuedBytes = 0
                    queueDiskBytes = 0
                    terminal = false
                }
            } else if (next != null) {
                terminal = false
                error = AmbientAccountPolicy.afterConfirmed(error, recording)
            }
        }
        notifyState()
        if (!preserveQueue && !terminal) io.execute { schedulePump(0) }
    }

    fun clearAccount(namespace: AccountNamespace) {
        synchronized(guard) {
            val owner = binding ?: hint
            if (owner != null && nativeOwnership?.permitsCleanup(namespace, owner) == true) clear()
        }
    }

    /** A server switch abandons the one global outbox even after its native lease has expired. */
    fun clearForServerSwitch() {
        synchronized(guard) {
            sessionObserver?.cancel()
            sessionObserver = null
            boundSessions = null
            session = null
            clear()
        }
    }

    /** Calls and dictation stop ambient synchronously and never restart it without a new action. */
    fun setMicrophoneOwner(owner: String, claimed: Boolean) {
        synchronized(guard) {
            if (claimed) {
                if (!microphoneOwners.add(owner)) return
                if (service != null || recording || checking) {
                    stop("microphone_busy")
                    service?.releaseMicrophoneNow("microphone_busy")
                }
            } else microphoneOwners.remove(owner)
        }
        notifyState()
    }

    /** The visible UI calls this only after its explicit Start and permission response. */
    fun launchPreparedStart(context: Context, token: Long) {
        if (!validToken(token) || !canStart()) return
        try {
            ContextCompat.startForegroundService(context, Intent(context, AmbientCaptureService::class.java)
                .putExtra(AmbientCaptureService.TOKEN, token))
        } catch (_: Exception) { stop("interrupted") }
    }

    /** Clear invalidates permission/verification replies before touching the durable queue. */
    fun clear(revokeAuthority: Boolean = true) {
        synchronized(guard) {
            generation++
            encoder = null
            closePreparedVad()
            http?.cancel()
            stop(null)
            queue.clear()
            binding = null
            if (revokeAuthority) {
                hintKnown = true; hint = null
                nativeOwnershipStore.clear(); nativeOwnership = null
            }
            queuedCount = 0
            queuedBytes = 0
            queueDiskBytes = 0
            error = null
            terminal = false
        }
        notifyState()
    }

    fun stop(message: String? = null) {
        checking = false
        if (service == null) synchronized(guard) { generation++; http?.cancel(); closePreparedVad() }
        service?.requestStop(message)
        recording = false
        error = message
        notifyState()
    }

    fun nativeOrigin(): String? = session?.origin

    internal fun resumeQueuedUploads() {
        io.execute {
            synchronized(guard) { binding = queue.storedBinding(); refreshQueue() }
            terminal = false
            schedulePump(0)
        }
    }

    fun token(): Long = generation
    fun validToken(token: Long): Boolean = token == generation
    fun canStart(): Boolean = synchronized(guard) { service == null && !recording && !checking && microphoneOwners.isEmpty() }

    fun prepareStart(token: Long, complete: (AmbientBinding?) -> Unit) {
        synchronized(guard) {
            if (!validToken(token) || !canStart()) { complete(null); return }
            checking = true
        }
        notifyState()
        io.execute {
            var vad: AmbientSileroVad? = null
            try {
                check(validToken(token)) { "Запуск записи отменён." }
                val (verified, credential) = verified(token)
                val accepted = http!!.readiness(verified, credential)
                val preparedEncoder = AmbientEncoder.discover(accepted)
                vad = AmbientSileroVad.fromAssets(applicationContext)
                synchronized(guard) {
                    if (!validToken(token) || (hintKnown && hint?.matches(verified) != true)) throw AmbientProblem("not_authenticated")
                    if (service != null || recording || microphoneOwners.isNotEmpty()) throw AmbientProblem("interrupted")
                    val old = binding
                    if (old != null && !old.matches(verified)) {
                        // A new login device is never authority to delete the previous queue.
                        if (queue.snapshot().diskBytes > 0) throw AmbientProblem("previous_queue")
                        queue.clear(old)
                    }
                    queue.rememberBinding(verified)
                    queue.ensureCapacity(COMPRESSED_RESERVE_BYTES)
                    binding = verified
                    encoder = preparedEncoder
                    closePreparedVad()
                    preparedVad = vad
                    vad = null
                    terminal = false
                    retrySeconds = 2
                    error = null
                    refreshQueue()
                }
                main.post {
                    checking = false
                    notifyState()
                    if (validToken(token)) complete(verified) else complete(null)
                }
            } catch (failure: Exception) {
                if (validToken(token)) fail(AmbientMessages.code(failure))
                main.post { checking = false; notifyState(); complete(null) }
            } finally { vad?.close() }
        }
    }

    fun attach(service: AmbientCaptureService, token: Long): AmbientBinding? = synchronized(guard) {
        val verified = binding
        if (!validToken(token) || microphoneOwners.isNotEmpty() || encoder == null || preparedVad == null || verified?.validAt(Instant.now()) != true || (hintKnown && hint?.matches(verified) != true)) return null
        this.service = service
        serviceToken = token
        recording = true
        error = null
        notifyState()
        verified
    }

    /** Permission/foreground-service failures must release a prepared but untransferred ONNX session. */
    fun startFailed(service: AmbientCaptureService, token: Long, message: String) = synchronized(guard) {
        if (!validToken(token) || (this.service != null && this.service !== service)) return@synchronized
        generation++
        closePreparedVad()
        encoder = null
        http?.cancel()
        this.service = null
        recording = false
        error = message
        notifyState()
    }

    fun detached(service: AmbientCaptureService, message: String?) {
        if (this.service !== service) return
        this.service = null
        recording = false
        if (message != null && validToken(serviceToken)) error = message
        notifyState()
        io.execute { schedulePump(0) }
    }

    fun captureStopped(service: AmbientCaptureService, message: String?) = synchronized(guard) {
        if (this.service !== service) return@synchronized
        val nextError = if (validToken(serviceToken)) message else error
        if (recording || error != nextError) {
            recording = false
            error = nextError
            notifyState()
        }
    }

    fun reserve(token: Long, pendingWrites: Int = 0) {
        check(validToken(token)) { "Запись отменена." }
        // The microphone thread reads only the last writer/uploader snapshot. The writer
        // checks actual disk usage atomically before commit; no directory scan can stall input.
        val reserved = COMPRESSED_RESERVE_BYTES * (pendingWrites + 1)
        if (reserved > AmbientQueue.DEFAULT_MAX_BYTES || queueDiskBytes > AmbientQueue.DEFAULT_MAX_BYTES - reserved) throw QueueFullException()
    }

    fun encoder(token: Long): AmbientEncoder = synchronized(guard) {
        if (!validToken(token)) throw AmbientProblem("not_authenticated")
        encoder ?: throw AmbientProblem("capture_failed")
    }

    /** Transfer the prepared session to one VAD worker; invalidation never closes an in-flight inference. */
    fun takeVad(token: Long): AmbientSileroVad = synchronized(guard) {
        if (!validToken(token)) throw AmbientProblem("not_authenticated")
        val result = preparedVad ?: throw AmbientProblem("vad_unavailable")
        preparedVad = null
        result
    }

    /** Serialize the actual microphone start with logout/clear, not just its permission reply. */
    fun beginCapture(token: Long, start: () -> Unit) = synchronized(guard) {
        if (!validToken(token)) throw AmbientProblem("not_authenticated")
        if (service == null || !recording || microphoneOwners.isNotEmpty()) throw AmbientProblem("interrupted")
        start()
    }

    private fun closePreparedVad() {
        preparedVad?.close()
        preparedVad = null
    }

    fun enqueue(token: Long, owner: AmbientBinding, sessionId: String, sequence: Long, startedAt: String, sourceSamples: Long, audio: AmbientEncodedAudio) {
        synchronized(guard) {
            check(validToken(token) && binding?.matches(owner) == true) { "Аккаунт изменился." }
            queue.enqueueEncoded(owner, sessionId, sequence, startedAt, sourceSamples, audio.format, audio.bytes)
        }
        refreshQueue()
        io.execute { schedulePump(0) }
    }

    fun setError(message: String) { fail(message) }

    private fun refreshQueue() = synchronized(guard) {
        val owner = binding
        val snapshot = if (owner == null) null else queue.snapshot(owner)
        queuedCount = snapshot?.count ?: 0
        queuedBytes = snapshot?.bytes ?: 0
        queueDiskBytes = snapshot?.diskBytes ?: 0
        notifyState()
    }

    private fun schedulePump(delay: Long) {
        if (pumpScheduled || terminal || session == null) return
        pumpScheduled = true
        io.schedule({ pumpScheduled = false; pump() }, delay, TimeUnit.SECONDS)
    }

    private fun verified(token: Long): Pair<AmbientBinding, SessionLease> {
        val authority = session ?: throw AmbientProblem("not_authenticated")
        val client = AmbientHttp(authority) { validToken(token) && session === authority }
        http = client
        val result = client.verify()
        if (!validToken(token) || (hintKnown && hint?.matches(result.first) != true)) throw AmbientProblem("not_authenticated")
        return result
    }

    private fun pump() {
        val token = generation
        val owner = binding ?: return
        try {
            if (!owner.validAt(Instant.now())) {
                // At app start after an update the stored binding may simply be old: with nothing
                // queued it is forgotten quietly instead of reporting an ended session.
                val message = AmbientAccountPolicy.afterExpiry(queue.list(owner).size)
                if (message == null) {
                    synchronized(guard) {
                        if (!validToken(token)) return
                        queue.clear(owner)
                        binding = null
                        refreshQueue()
                    }
                    return
                }
                terminal = true
                stop(message)
                return
            }
            val batch = queue.list(owner).firstOrNull() ?: return
            val (verified, credential) = verified(token)
            if (!owner.matches(verified)) {
                synchronized(guard) {
                    if (!validToken(token)) return
                    generation++
                    stop("previous_queue")
                    terminal = true
                    refreshQueue()
                }
                return
            }
            val receipt = http!!.upload(batch, queue.audioFile(batch), credential)
            synchronized(guard) {
                if (!validToken(token)) return
                check(queue.acknowledge(receipt.id, receipt.sha256)) { "Подтверждение аудио не соответствует очереди." }
                lastUploadedAt = Instant.now().toString()
                retrySeconds = 2
                error = null
            }
            refreshQueue()
            schedulePump(0)
        } catch (failure: Exception) {
            if (!validToken(token)) return
            val status = (failure as? AmbientHttpException)?.status
            if ((status != null && !AmbientMessages.retryableStatus(status)) || failure is QueueCorruptException || failure is AmbientProblem) {
                terminal = true
                stop(AmbientMessages.code(failure))
            } else {
                fail(AmbientMessages.code(failure))
                schedulePump(retrySeconds)
                retrySeconds = minOf(300, retrySeconds * 2)
            }
        } finally { http = null }
    }

    companion object {
        private const val COMPRESSED_RESERVE_BYTES = AmbientAudioFormat.MAX_COMPRESSED_BYTES + 16_384L
        @Volatile private var instance: AmbientRuntime? = null
        fun get(context: Context): AmbientRuntime = instance ?: synchronized(this) {
            instance ?: AmbientRuntime(context.applicationContext).also { instance = it }
        }
    }
}
