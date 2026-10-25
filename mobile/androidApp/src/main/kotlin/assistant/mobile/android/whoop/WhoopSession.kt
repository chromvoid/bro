package assistant.mobile.android.whoop

import kotlinx.coroutines.withTimeoutOrNull

/** A notification from one of the strap's fd4b channels. */
internal class WhoopNotice(val channel: String, val bytes: ByteArray)

/** The connected, bonded strap as the session sees it; the GATT link and tests implement it. */
internal interface WhoopLink {
    suspend fun write(bytes: ByteArray)
    /** The next notification; throws when the link is gone. */
    suspend fun receive(): WhoopNotice
}

/**
 * One exchange with the strap: hello, clock, then history until it is complete, the deadline
 * passes or the strap goes quiet. A part of history is acknowledged (which lets the strap trim
 * it) only after [WhoopSession.run]'s persist callback has committed every frame of it, and never
 * when one of its frames failed its checksum: the strap sends that part again next time.
 */
internal class WhoopSession(private val link: WhoopLink, private val now: () -> Long = System::currentTimeMillis) {
    data class Outcome(val clockSet: Boolean, val clockDrift: Long?, val parts: Int, val frames: Int, val complete: Boolean)

    private val streams = mutableMapOf<String, WhoopProtocol.Reassembler>()
    private val responses = ArrayDeque<WhoopProtocol.Response>()
    private val history = ArrayDeque<WhoopProtocol.Frame>()
    private var sequence = 0

    private suspend fun pump(timeoutMs: Long): Boolean {
        val notice = withTimeoutOrNull(timeoutMs) { link.receive() } ?: return false
        for (frame in streams.getOrPut(notice.channel) { WhoopProtocol.Reassembler() }.feed(notice.bytes)) {
            when (frame.type) {
                WhoopProtocol.RESPONSE -> WhoopProtocol.response(frame)?.let(responses::addLast)
                WhoopProtocol.HISTORY, WhoopProtocol.METADATA, 52, 56 -> history.addLast(frame)
            }
        }
        return true
    }

    /** The final reply to this request (or its first one, when [final] is false); null after [timeoutMs]. */
    suspend fun request(command: Int, body: ByteArray = ByteArray(0), timeoutMs: Long = 8_000,
        final: Boolean = true): WhoopProtocol.Response? {
        sequence = sequence % 255 + 1
        val origin = sequence
        link.write(WhoopProtocol.command(command, body, origin))
        val deadline = now() + timeoutMs
        while (true) {
            val found = responses.firstOrNull { it.command == command && it.origin == origin &&
                (!final || it.result != WhoopProtocol.PENDING) }
            if (found != null) { responses.remove(found); return found }
            val left = deadline - now()
            if (left <= 0 || !pump(left)) return null
        }
    }

    private suspend fun nextHistory(idleMs: Long): WhoopProtocol.Frame? {
        while (history.isEmpty()) if (!pump(idleMs)) return null
        return history.removeFirst()
    }

    suspend fun run(deadline: Long, idleMs: Long = 15_000, persist: suspend (List<ByteArray>) -> Boolean): Outcome {
        val hello = request(WhoopProtocol.GET_HELLO, byteArrayOf(1), 10_000)
        if (hello?.result != WhoopProtocol.SUCCESS) throw WhoopFailure("whoop_no_reply")
        val clock = request(WhoopProtocol.GET_CLOCK, byteArrayOf(1))?.takeIf { it.result == WhoopProtocol.SUCCESS }
            ?.let { WhoopProtocol.clockSeconds(it.body) }
        val drift = clock?.takeIf { it > 0 }?.let { it - now() / 1000 }
        val clockSet = if (drift == null || kotlin.math.abs(drift) > 2)
            request(WhoopProtocol.SET_CLOCK, WhoopProtocol.setClock(now()))?.result == WhoopProtocol.SUCCESS else false
        // WHOOP 5/MG asks for the stored range before history; its answer is not needed here.
        request(WhoopProtocol.GET_DATA_RANGE, timeoutMs = 10_000)
        val accepted = request(WhoopProtocol.SEND_HISTORY, byteArrayOf(0), final = false)
        if (accepted == null || accepted.result == 0) throw WhoopFailure("whoop_history_refused")
        val part = mutableListOf<WhoopProtocol.Frame>()
        var parts = 0; var frames = 0; var complete = false
        while (now() < deadline) {
            val frame = nextHistory(idleMs) ?: break
            part += frame
            val boundary = WhoopProtocol.boundary(frame) ?: continue
            if (boundary.kind == WhoopProtocol.HISTORY_COMPLETE) { complete = true; break }
            if (boundary.kind != WhoopProtocol.HISTORY_END) continue
            val token = boundary.token ?: break
            if (part.any { !it.ok } || !persist(part.map { it.bytes })) break
            request(WhoopProtocol.HISTORY_RESULT, byteArrayOf(1) + token, 3_000, final = false)
            parts++; frames += part.size; part.clear()
        }
        if (!complete) request(WhoopProtocol.ABORT_HISTORY, byteArrayOf(0), 3_000, final = false)
        return Outcome(clockSet, drift, parts, frames, complete)
    }
}
