package assistant.mobile.platform

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.os.Handler
import android.os.Looper
import assistant.mobile.core.LiveSocket
import assistant.mobile.core.LiveSocketFactory
import assistant.mobile.core.LiveSocketListener
import assistant.mobile.core.SessionLease
import assistant.mobile.core.canonicalOrigin
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString.Companion.toByteString
import java.util.concurrent.TimeUnit

/** Transport only: the core owns session lifetime, dictations and reconnects. */
class AndroidLiveSocketFactory(
    context: Context,
    private val allowInsecureLoopback: Boolean = false,
    private val serverOrigin: () -> String,
) : LiveSocketFactory, AutoCloseable {
    private val connectivity = context.applicationContext.getSystemService(ConnectivityManager::class.java)
    private val main = Handler(Looper.getMainLooper())
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var closed = false
    private val client = OkHttpClient.Builder()
        .followRedirects(false)
        .followSslRedirects(false)
        .retryOnConnectionFailure(false)
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .pingInterval(15, TimeUnit.SECONDS)
        .build()

    fun observeNetwork(onAvailable: () -> Unit) {
        check(networkCallback == null && !closed)
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                main.post { if (!closed) onAvailable() }
            }
        }
        connectivity.registerDefaultNetworkCallback(callback)
        networkCallback = callback
    }

    override fun open(lease: SessionLease, listener: LiveSocketListener): LiveSocket {
        check(!closed)
        val origin = canonicalOrigin(serverOrigin(), allowInsecureLoopback)
        val request = Request.Builder().url("$origin/v1/live")
            .header("Authorization", "Bearer ${lease.credential}")
            .header("X-Expected-User", lease.namespace.userId)
            .build()
        val socket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) = listener.opened()

            override fun onMessage(webSocket: WebSocket, text: String) {
                if (text.length > 32_000) {
                    webSocket.cancel()
                    listener.failed()
                } else listener.text(text)
            }

            override fun onMessage(webSocket: WebSocket, bytes: okio.ByteString) {
                webSocket.cancel()
                listener.failed()
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                // Do not let an unreachable peer delay reconnection or microphone release.
                webSocket.cancel()
                listener.closed()
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = listener.closed()

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) =
                listener.failed(response?.code)
        })
        return object : LiveSocket {
            override val queuedBytes: Long get() = socket.queueSize()
            override fun sendText(value: String): Boolean = socket.send(value)
            override fun sendPcm(bytes: ByteArray): Boolean = socket.send(bytes.toByteString())
            override fun cancel() = socket.cancel()
        }
    }

    override fun close() {
        closed = true
        networkCallback?.let(connectivity::unregisterNetworkCallback)
        networkCallback = null
        client.dispatcher.executorService.shutdown()
        client.connectionPool.evictAll()
    }
}
