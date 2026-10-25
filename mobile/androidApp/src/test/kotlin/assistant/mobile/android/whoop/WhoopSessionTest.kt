package assistant.mobile.android.whoop

import java.io.File
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

/** A scripted strap: answers each command and streams the shared history frames in small notifications. */
private class FakeStrap(private val history: List<ByteArray>, private val clock: Long, private val corrupt: Boolean = false) : WhoopLink {
    val commands = mutableListOf<Pair<Int, ByteArray>>()
    private val notices = Channel<WhoopNotice>(Channel.UNLIMITED)

    private fun frame(inner: ByteArray): ByteArray {
        val padded = inner + ByteArray((4 - inner.size % 4) % 4)
        val head = byteArrayOf(0xAA.toByte(), 1, (padded.size + 4).toByte(), ((padded.size + 4) ushr 8).toByte(), 1, 0)
        val crc16 = WhoopProtocol.crc16(head)
        val crc32 = WhoopProtocol.crc32(padded, 0, padded.size)
        return head + byteArrayOf(crc16.toByte(), (crc16 ushr 8).toByte()) + padded + ByteArray(4) { (crc32 ushr (8 * it)).toByte() }
    }
    private fun send(channel: String, bytes: ByteArray) = bytes.toList().chunked(17).forEach {
        notices.trySend(WhoopNotice(channel, it.toByteArray()))
    }
    private fun reply(command: Int, origin: Int, result: Int, body: ByteArray = ByteArray(0)) =
        send("cmd", frame(byteArrayOf(36, 0, command.toByte(), origin.toByte(), result.toByte()) + body))

    override suspend fun write(bytes: ByteArray) {
        val origin = bytes[9].toInt() and 0xFF
        val command = bytes[10].toInt() and 0xFF
        commands += command to bytes.copyOfRange(11, bytes.size - 4)
        when (command) {
            WhoopProtocol.GET_HELLO -> { reply(command, origin, WhoopProtocol.PENDING); reply(command, origin, 1, ByteArray(107)) }
            WhoopProtocol.GET_CLOCK -> reply(command, origin, 1, byteArrayOf(1) + ByteArray(4) { (clock ushr (8 * it)).toByte() } + ByteArray(2))
            WhoopProtocol.SEND_HISTORY -> {
                reply(command, origin, 1, ByteArray(3))
                for ((i, frame) in history.withIndex()) {
                    val sent = if (corrupt && i == 1) frame.copyOf().also { it[40] = (it[40].toInt() xor 1).toByte() } else frame
                    send("data", sent)
                    if (WhoopProtocol.boundary(WhoopProtocol.Frame(frame, true))?.kind == WhoopProtocol.HISTORY_END) break
                }
            }
            WhoopProtocol.HISTORY_RESULT -> {
                reply(command, origin, 1)
                history.dropWhile { WhoopProtocol.boundary(WhoopProtocol.Frame(it, true))?.kind != WhoopProtocol.HISTORY_END }
                    .drop(1).forEach { send("data", it) }
            }
            else -> reply(command, origin, 1)
        }
    }
    override suspend fun receive(): WhoopNotice = notices.receive()
}

class WhoopSessionTest {
    private val contract = whoopJson.parseToJsonElement(File(System.getProperty("assistant.whoop.frames")!!).readText()) as JsonObject
    private fun bytes(hex: String) = ByteArray(hex.length / 2) { hex.substring(2 * it, 2 * it + 2).toInt(16).toByte() }
    private val history = (contract["history"] as JsonArray).map { bytes(it.jsonPrimitive.content) }
    private val now = 1791158400_000L
    private val token = bytes(contract["end_token"]!!.jsonPrimitive.content)

    @Test fun aCommittedPartIsAcknowledgedWithItsTokenAndTheClockIsSet() = runBlocking {
        val strap = FakeStrap(history, clock = 0)
        val saved = mutableListOf<List<ByteArray>>()
        val outcome = WhoopSession(strap) { now }.run(Long.MAX_VALUE, idleMs = 200) { saved += it; true }
        assertTrue(outcome.complete)
        assertEquals(1, outcome.parts)
        assertTrue(outcome.clockSet)
        assertEquals(listOf(history.take(3).map { it.toList() }), saved.map { part -> part.map { it.toList() } })
        val commands = strap.commands.map { it.first }
        assertEquals(listOf(WhoopProtocol.GET_HELLO, WhoopProtocol.GET_CLOCK, WhoopProtocol.SET_CLOCK, WhoopProtocol.GET_DATA_RANGE,
            WhoopProtocol.SEND_HISTORY, WhoopProtocol.HISTORY_RESULT), commands)
        assertArrayEquals(byteArrayOf(1) + token, strap.commands.last().second) // 12-byte body, no padding needed
    }

    @Test fun aPartThatCannotBeKeptOrFailedItsChecksumIsNeverAcknowledged() = runBlocking {
        for ((corrupt, keep) in listOf(false to false, true to true)) {
            val strap = FakeStrap(history, clock = now / 1000, corrupt = corrupt)
            var calls = 0
            val outcome = WhoopSession(strap) { now }.run(Long.MAX_VALUE, idleMs = 200) { calls++; keep }
            assertEquals(0, outcome.parts)
            assertFalse(outcome.complete)
            assertEquals(if (corrupt) 0 else 1, calls)
            val commands = strap.commands.map { it.first }
            assertFalse(WhoopProtocol.HISTORY_RESULT in commands)
            assertEquals(WhoopProtocol.ABORT_HISTORY, commands.last())
        }
    }
}
