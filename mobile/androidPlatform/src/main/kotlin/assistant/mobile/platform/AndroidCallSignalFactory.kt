package assistant.mobile.platform

import assistant.mobile.core.SessionLease
import assistant.mobile.core.SessionManager
import assistant.mobile.core.canonicalOrigin
import assistant.mobile.core.calls.CallClientFrame
import assistant.mobile.core.calls.CallSignal
import assistant.mobile.core.calls.CallSignalFactory
import assistant.mobile.core.calls.CallSignalListener
import assistant.mobile.core.calls.decodeCallServerFrame
import assistant.mobile.core.calls.encodeCallClientFrame
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.TimeUnit
import kotlin.math.min
import kotlin.random.Random

/** Existing call WS protocol with native-session headers and the web heartbeat/backoff. */
class AndroidCallSignalFactory(
    origin: String,
    private val session: SessionManager,
    private val allowInsecureLoopback: Boolean = false,
    private val serverOrigin: () -> String = { origin },
) : CallSignalFactory, AutoCloseable {
    private val origin = canonicalOrigin(origin, allowInsecureLoopback)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val client = OkHttpClient.Builder()
        .followRedirects(false)
        .followSslRedirects(false)
        .retryOnConnectionFailure(false)
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()
    private val sockets = mutableSetOf<Signal>()

    override fun open(callId: String, lease: SessionLease, listener: CallSignalListener): CallSignal {
        if (lease.namespace.origin != origin || !session.isCurrent(lease)) {
            scope.launch { listener.final(4001) }
            return Rejected
        }
        return Signal(callId, lease, listener).also {
            sockets.add(it)
            it.start()
        }
    }

    override fun close() {
        sockets.toList().forEach(Signal::close)
        scope.cancel()
        client.dispatcher.executorService.shutdown()
        client.connectionPool.evictAll()
    }

    private inner class Signal(
        private val callId: String,
        private val lease: SessionLease,
        private val listener: CallSignalListener,
    ) : CallSignal {
        private var socket: WebSocket? = null
        private var retry: Job? = null
        private var silence: Job? = null
        private var ping: Job? = null
        private var watcher: Job? = null
        private var attempts = 0
        private var closed = false
        @Volatile override var connected = false
            private set

        fun start() {
            connect()
            watcher = scope.launch {
                session.leases.collect { if (it !== lease) finish(4001) }
            }
        }

        override fun send(frame: CallClientFrame): Boolean {
            if (closed || !connected || !session.isCurrent(lease)) return false
            val text = runCatching { encodeCallClientFrame(frame) }.getOrNull() ?: return false
            return socket?.send(text) == true
        }

        override fun close() {
            if (closed) return
            closed = true
            watcher?.cancel()
            drop()
            sockets.remove(this)
        }

        private fun connect() {
            if (closed) return
            if (!session.isCurrent(lease)) return finish(4001)
            val url = canonicalOrigin(serverOrigin(), allowInsecureLoopback).toHttpUrl().newBuilder()
                .addPathSegments("v1/calls").addPathSegment(callId).addPathSegment("signal").build()
            val request = Request.Builder().url(url)
                .header("Authorization", "Bearer ${lease.credential}")
                .header("X-Expected-User", lease.namespace.userId).build()
            val next = client.newWebSocket(request, object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) = dispatch(webSocket) {
                    connected = true
                    attempts = 0
                    alive()
                    schedulePing()
                    listener.opened()
                }

                override fun onMessage(webSocket: WebSocket, text: String) = dispatch(webSocket) {
                    alive()
                    decodeCallServerFrame(text)?.let(listener::frame)
                }

                override fun onMessage(webSocket: WebSocket, bytes: okio.ByteString) = dispatch(webSocket) {
                    alive()
                }

                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                    webSocket.close(code, null)
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = dispatch(webSocket) {
                    if (code == 4000 || code == 4001) finish(code)
                    else reconnect()
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) = dispatch(webSocket) {
                    when (response?.code) {
                        401 -> {
                            finish(4001)
                            scope.launch { session.expire(lease) }
                        }
                        403, 404 -> finish(4001)
                        else -> reconnect()
                    }
                }
            })
            socket = next
            // A stalled upgrade counts as silence, just like a connected socket without pong.
            alive()
        }

        private fun dispatch(from: WebSocket, action: () -> Unit) {
            scope.launch {
                if (!closed && socket === from) action()
            }
        }

        private fun finish(code: Int) {
            if (closed) return
            close()
            listener.final(code)
        }

        private fun reconnect() {
            if (closed) return
            drop()
            listener.disconnected()
            val base = min(10_000L, 500L * (1L shl min(attempts, 5)))
            attempts += 1
            val pause = (base * (0.8 + 0.4 * Random.nextDouble())).toLong()
            retry = scope.launch {
                delay(pause)
                connect()
            }
        }

        private fun drop() {
            retry?.cancel()
            ping?.cancel()
            silence?.cancel()
            retry = null
            ping = null
            silence = null
            connected = false
            val previous = socket
            socket = null
            // Cancel instead of awaiting a close handshake on an unreachable network.
            previous?.cancel()
        }

        private fun alive() {
            silence?.cancel()
            silence = scope.launch {
                delay(25_000)
                reconnect()
            }
        }

        private fun schedulePing() {
            ping?.cancel()
            ping = scope.launch {
                while (!closed && connected) {
                    delay(15_000)
                    send(CallClientFrame.Ping)
                }
            }
        }
    }

    private object Rejected : CallSignal {
        override val connected = false
        override fun send(frame: CallClientFrame) = false
        override fun close() = Unit
    }
}
