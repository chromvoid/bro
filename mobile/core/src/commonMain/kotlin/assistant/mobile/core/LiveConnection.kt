package assistant.mobile.core

import assistant.mobile.core.dictation.DictationSession
import assistant.mobile.core.dictation.DictationSessionListener
import assistant.mobile.core.dictation.decodeDictationMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.random.Random
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

interface LiveSocketListener {
    fun opened()
    fun text(value: String)
    fun closed()
    fun failed(status: Int? = null)
}

fun interface LiveSocketFactory {
    /** Callbacks may arrive on any thread; authorization belongs in handshake headers. */
    fun open(lease: SessionLease, listener: LiveSocketListener): LiveSocket
}

interface LiveSocket {
    val queuedBytes: Long
    fun sendText(value: String): Boolean
    fun sendPcm(bytes: ByteArray): Boolean
    fun cancel()
}

/** One foreground socket per core. All state and dictation actions share the UI dispatcher. */
class LiveConnection(
    private val scope: CoroutineScope,
    private val sessions: SessionManager,
    private val sockets: LiveSocketFactory,
    private val random: () -> Double = { Random.nextDouble() },
) {
    private var foreground = false
    private var closed = false
    private var lease: SessionLease? = null
    private var socket: LiveSocket? = null
    private var opened = false
    private var generation = 0L
    private var retry: Job? = null
    private var backoff = 500L
    private var active: Recording? = null
    private val owner = scope.launch {
        try {
            sessions.leases.collect { reconcile() }
        } finally {
            drop()
            interruptDictation()
        }
    }

    fun setForeground(value: Boolean) {
        foreground = value
        reconcile()
    }

    /** Network recovery bypasses a pending delay or a stalled upgrade, without replaying audio. */
    fun networkAvailable() {
        reconcile()
        if (lease == null || opened || closed) return
        drop()
        backoff = 500L
        connect()
    }

    @OptIn(ExperimentalUuidApi::class)
    fun startDictation(listener: DictationSessionListener): DictationSession {
        reconcile()
        check(!closed && lease != null) { "Live connection is not foreground and signed in" }
        check(active == null) { "A dictation is already active" }
        return Recording(Uuid.random().toString(), listener).also {
            active = it
            if (socket == null) {
                drop()
                backoff = 500L
                connect()
            }
            if (opened) it.start()
        }
    }

    fun close() {
        closed = true
        owner.cancel()
        drop()
        interruptDictation()
        lease = null
    }

    private fun reconcile() {
        val next = if (foreground && !closed) sessions.lease() else null
        if (lease === next) return
        drop()
        interruptDictation()
        lease = next
        backoff = 500L
        if (next != null) connect()
    }

    private fun connect() {
        val current = lease?.takeIf(sessions::isCurrent) ?: return
        val token = ++generation
        try {
            socket = sockets.open(current, object : LiveSocketListener {
                private fun dispatch(action: () -> Unit) {
                    scope.launch {
                        if (!closed && foreground && generation == token && sessions.isCurrent(current)) action()
                    }
                }
                override fun opened() = dispatch {
                    opened = true
                    backoff = 500L
                    active?.start()
                }
                override fun text(value: String) = dispatch {
                    val message = runCatching { decodeDictationMessage(value) }.getOrElse {
                        lost()
                        return@dispatch
                    }
                    val recording = active ?: return@dispatch
                    if (message.id != recording.id) return@dispatch
                    if (message.type == "dictation.ready") recording.ready = true
                    if (message.type == "dictation.final" || message.type == "dictation.error") active = null
                    recording.listener.text(value)
                }
                override fun closed() = dispatch { lost() }
                override fun failed(status: Int?) = dispatch {
                    if (status == 401) {
                        drop()
                        interruptDictation()
                        scope.launch { sessions.expire(current) }
                    } else lost()
                }
            })
        } catch (_: Exception) {
            lost()
        }
    }

    private fun lost() {
        drop()
        interruptDictation()
        if (closed || !foreground || lease?.let(sessions::isCurrent) != true) return
        val pause = (backoff * (0.8 + 0.4 * random())).toLong()
        backoff = (backoff * 2).coerceAtMost(10_000)
        retry = scope.launch {
            delay(pause)
            retry = null
            connect()
        }
    }

    private fun drop() {
        generation++
        retry?.cancel()
        retry = null
        opened = false
        val previous = socket
        socket = null
        previous?.cancel()
    }

    private fun interruptDictation() {
        val previous = active
        active = null
        previous?.listener?.failed()
    }

    private inner class Recording(val id: String, val listener: DictationSessionListener) : DictationSession {
        private var started = false
        var ready = false
        private fun current(): Boolean = active === this && opened && lease?.let(sessions::isCurrent) == true
        override val queuedBytes: Long get() = if (current()) socket?.queuedBytes ?: 0 else 0

        fun start() {
            if (started || !current()) return
            started = true
            if (!control("start")) lost()
        }

        private fun control(action: String): Boolean =
            socket?.sendText("{\"type\":\"dictation.$action\",\"id\":\"$id\"}") == true

        override fun sendPcm(bytes: ByteArray): Boolean =
            current() && ready && (socket?.sendPcm(bytes) == true).also { if (!it) lost() }

        override fun finish(): Boolean = current() && ready && control("finish").also { if (!it) lost() }

        override fun cancel() {
            if (active !== this) return
            val sendCancel = current() && started
            active = null
            if (sendCancel && !control("cancel")) lost()
        }
    }
}
