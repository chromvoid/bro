package assistant.mobile.android.whoop

import java.io.File
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class WhoopProtocolTest {
    private val contract = whoopJson.parseToJsonElement(File(System.getProperty("assistant.whoop.frames")!!).readText()) as JsonObject
    private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it) }
    private fun bytes(hex: String) = ByteArray(hex.length / 2) { hex.substring(2 * it, 2 * it + 2).toInt(16).toByte() }
    private val history = (contract["history"] as JsonArray).map { bytes(it.jsonPrimitive.content) }

    @Test fun commandsMatchThePublishedHelloAndTheSharedClockFrame() {
        assertEquals(contract["hello"]!!.jsonPrimitive.content, hex(WhoopProtocol.command(WhoopProtocol.GET_HELLO, byteArrayOf(1), 1)))
        val clock = contract["set_clock"] as JsonObject
        val frame = WhoopProtocol.command(WhoopProtocol.SET_CLOCK, WhoopProtocol.setClock(clock["epoch_ms"]!!.jsonPrimitive.long),
            clock["sequence"]!!.jsonPrimitive.int)
        assertEquals(clock["frame"]!!.jsonPrimitive.content, hex(frame))
    }

    @Test fun splitNotificationsReassembleIntoCheckedFramesAndBoundaries() {
        val stream = byteArrayOf(0x13, 0x37) + history.reduce { all, frame -> all + frame }
        val reassembler = WhoopProtocol.Reassembler()
        val frames = stream.toList().chunked(20).flatMap { reassembler.feed(it.toByteArray()) }
        assertEquals(history.map(::hex), frames.map { hex(it.bytes) })
        assertTrue(frames.all { it.ok })
        assertEquals(2L, reassembler.skipped)
        assertEquals(listOf(1, null, 2, 3), frames.map { WhoopProtocol.boundary(it)?.kind })
        assertEquals(contract["end_token"]!!.jsonPrimitive.content, hex(WhoopProtocol.boundary(frames[2])!!.token!!))
    }

    @Test fun aCorruptedBodyIsKeptButMarkedAndABrokenHeaderIsSkipped() {
        val record = history[1].copyOf().also { it[40] = (it[40].toInt() xor 1).toByte() }
        val header = history[0].copyOf().also { it[6] = (it[6].toInt() xor 1).toByte() }
        val frames = WhoopProtocol.Reassembler().feed(record + header + history[3])
        assertEquals(listOf(false, true), frames.map { it.ok })
        assertNull(WhoopProtocol.boundary(frames[0]))
        assertEquals(3, WhoopProtocol.boundary(frames[1])?.kind)
    }

    @Test fun responsesCarryCommandOriginResultAndBody() {
        val inner = byteArrayOf(36, 9, WhoopProtocol.GET_CLOCK.toByte(), 4, 1, 1, 0x80.toByte(), 0xe8.toByte(), 0xc2.toByte(), 0x6a, 0, 0)
        val head = byteArrayOf(0xAA.toByte(), 1, (inner.size + 4).toByte(), 0, 1, 0)
        val crc16 = WhoopProtocol.crc16(head)
        val crc32 = WhoopProtocol.crc32(inner, 0, inner.size)
        val frame = head + byteArrayOf(crc16.toByte(), (crc16 ushr 8).toByte()) + inner +
            ByteArray(4) { (crc32 ushr (8 * it)).toByte() }
        val response = WhoopProtocol.response(WhoopProtocol.Reassembler().feed(frame).single())!!
        assertEquals(listOf(WhoopProtocol.GET_CLOCK, 4, 1), listOf(response.command, response.origin, response.result))
        assertEquals(1791158400L, WhoopProtocol.clockSeconds(response.body))
    }

}
