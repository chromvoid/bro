package assistant.mobile.android.glasses

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal class G2Failure(message: String, val arm: G2Side? = null) : Exception(message)

internal interface G2Link {
    val mtu: Int
    val notifications: Flow<G2Notification>
    suspend fun open()
    suspend fun write(bytes: ByteArray)
    suspend fun awaitDisconnect()
    fun close()
}

/** Owns both arms as one session. All writes, including heartbeats, are serialized. */
internal class G2Session(private val left: G2Link, private val right: G2Link) {
    private val writes = Mutex()
    private var sequence = 32
    private var magic = 1
    private var page = false
    private var ready = false

    suspend fun connect(timestampSeconds: Long) {
        try {
            left.open()
            right.open()
            for (packet in G2Protocol.authentication(timestampSeconds)) {
                left.write(packet)
                right.write(packet)
                delay(100)
            }
            delay(500)
            ready = true
        } catch (error: Throwable) {
            close()
            throw error
        }
    }

    suspend fun display(text: String) = displayPage { create, magic -> G2Protocol.textPage(text, create, magic) }
    suspend fun displayBounds() = displayPage(G2Protocol::boundsPage)

    private suspend fun displayPage(payloadFor: (Boolean, Int) -> ByteArray) = writes.withLock {
        check(ready) { "G2 session is not connected" }
        val payload = payloadFor(!page, nextMagic())
        if (!page) { right.write(G2Protocol.prelude()); delay(100) }
        sendPlugin(payload)
        page = true
    }

    suspend fun updateText(text: String): Boolean = writes.withLock {
        if (!ready || !page) return@withLock false
        sendPlugin(G2Protocol.updateText(text, nextMagic()))
        true
    }

    suspend fun maintain(onInput: suspend (G2Input) -> Unit = {}): Unit = coroutineScope {
        launch { left.awaitDisconnect(); throw G2Failure("Left arm disconnected", G2Side.Left) }
        launch { right.awaitDisconnect(); throw G2Failure("Right arm disconnected", G2Side.Right) }
        launch { left.notifications.collect { /* Native page input is owned by the right arm. */ } }
        launch {
            val decoder = G2InputDecoder()
            right.notifications.collect { notification ->
                val input = decoder.receive(notification) ?: return@collect
                if (input.type in setOf(G2InputType.Exit, G2InputType.AbnormalExit, G2InputType.SystemExit)) {
                    writes.withLock { page = false }
                }
                onInput(input)
            }
        }
        while (isActive) {
            writes.withLock {
                val packet = G2Protocol.heartbeat(nextSequence())
                left.write(packet)
                right.write(packet)
                if (page) sendPlugin(G2Protocol.pluginHeartbeat(nextMagic()))
            }
            delay(1500)
        }
    }

    suspend fun releasePage() = writes.withLock {
        if (page && ready) sendPlugin(G2Protocol.shutdown(nextMagic()))
        page = false
    }

    private suspend fun sendPlugin(payload: ByteArray) {
        for (frame in G2Protocol.frames(nextSequence(), 0xe0, 0x20, payload, right.mtu)) {
            right.write(frame)
            delay(20) // G2's reassembly task needs pacing even after Android accepts a write.
        }
    }
    private fun nextSequence(): Int { sequence = (sequence + 1) and 255; return sequence }
    private fun nextMagic(): Int { magic = magic % 120 + 1; return magic }

    fun close() {
        ready = false
        page = false
        left.close()
        right.close()
    }
}
