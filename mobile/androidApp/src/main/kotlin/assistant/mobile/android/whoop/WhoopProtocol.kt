package assistant.mobile.android.whoop

import java.io.ByteArrayOutputStream
import java.util.UUID
import java.util.zip.CRC32

/**
 * WHOOP 5.0 format-1 frames (docs/whoop-5.md): `AA 01`, length, roles, header CRC-16 Modbus, a body
 * padded to four bytes and its CRC-32. The phone only frames commands and finds history boundaries;
 * the server decodes what the records mean.
 */
internal object WhoopProtocol {
    val SERVICE: UUID = UUID.fromString("fd4b0001-cce1-4033-93ce-002d5875f58a")
    val COMMAND: UUID = UUID.fromString("fd4b0002-cce1-4033-93ce-002d5875f58a")
    val NOTIFY: Map<UUID, String> = mapOf(
        UUID.fromString("fd4b0003-cce1-4033-93ce-002d5875f58a") to "cmd",
        UUID.fromString("fd4b0004-cce1-4033-93ce-002d5875f58a") to "event",
        UUID.fromString("fd4b0005-cce1-4033-93ce-002d5875f58a") to "data",
        UUID.fromString("fd4b0007-cce1-4033-93ce-002d5875f58a") to "logs",
    )
    const val ABORT_HISTORY = 20
    const val SEND_HISTORY = 22
    const val HISTORY_RESULT = 23
    const val GET_DATA_RANGE = 34
    const val GET_HELLO = 145
    const val SET_CLOCK = 146
    const val GET_CLOCK = 147
    const val RESPONSE = 36
    const val HISTORY = 47
    const val METADATA = 49
    const val HISTORY_START = 1
    const val HISTORY_END = 2
    const val HISTORY_COMPLETE = 3
    const val SUCCESS = 1
    const val PENDING = 2
    private const val MAX_FRAME = 4096

    fun crc16(bytes: ByteArray, from: Int = 0, to: Int = bytes.size): Int {
        var crc = 0xFFFF
        for (i in from until to) {
            crc = crc xor (bytes[i].toInt() and 0xFF)
            repeat(8) { crc = if (crc and 1 != 0) (crc ushr 1) xor 0xA001 else crc ushr 1 }
        }
        return crc
    }
    fun crc32(bytes: ByteArray, from: Int, to: Int): Long = CRC32().apply { update(bytes, from, to - from) }.value
    fun u16(bytes: ByteArray, at: Int): Int = (bytes[at].toInt() and 0xFF) or ((bytes[at + 1].toInt() and 0xFF) shl 8)
    fun u32(bytes: ByteArray, at: Int): Long = u16(bytes, at).toLong() or (u16(bytes, at + 2).toLong() shl 16)
    private fun le(value: Long, size: Int) = ByteArray(size) { ((value ushr (8 * it)) and 0xFF).toByte() }

    /** A phone-to-strap command; the strap silently drops a body that is not padded to four bytes. */
    fun command(command: Int, body: ByteArray, sequence: Int): ByteArray {
        val raw = byteArrayOf(35, sequence.toByte(), command.toByte()) + body
        val inner = raw + ByteArray((4 - raw.size % 4) % 4)
        val head = byteArrayOf(0xAA.toByte(), 1) + le(inner.size + 4L, 2) + byteArrayOf(0, 1)
        return head + le(crc16(head).toLong(), 2) + inner + le(crc32(inner, 0, inner.size), 4)
    }
    fun setClock(epochMillis: Long): ByteArray =
        byteArrayOf(1) + le(epochMillis / 1000, 4) + le((epochMillis % 1000) * 32768 / 1000, 2)

    class Frame(val bytes: ByteArray, val ok: Boolean) {
        val type: Int get() = bytes[8].toInt() and 0xFF
    }

    /** Notifications can split a frame; bytes before a valid header are skipped and counted. */
    class Reassembler {
        private val buffer = ByteArrayOutputStream()
        var skipped = 0L
            private set
        fun feed(chunk: ByteArray): List<Frame> {
            buffer.write(chunk)
            var bytes = buffer.toByteArray()
            var at = 0
            val frames = mutableListOf<Frame>()
            while (true) {
                while (at < bytes.size && bytes[at] != 0xAA.toByte()) { at++; skipped++ }
                if (bytes.size - at < 8) break
                val size = 8 + u16(bytes, at + 2)
                if (bytes[at + 1].toInt() != 1 || u16(bytes, at + 6) != crc16(bytes, at, at + 6) || size < 12 || size > MAX_FRAME) {
                    at++; skipped++; continue
                }
                if (bytes.size - at < size) break
                val frame = bytes.copyOfRange(at, at + size)
                frames += Frame(frame, u32(frame, size - 4) == crc32(frame, 8, size - 4))
                at += size
            }
            bytes = bytes.copyOfRange(at, bytes.size)
            buffer.reset(); buffer.write(bytes)
            return frames
        }
    }

    data class Response(val command: Int, val origin: Int, val result: Int, val body: ByteArray)
    fun response(frame: Frame): Response? {
        val bytes = frame.bytes
        if (!frame.ok || frame.type != RESPONSE || bytes.size < 17) return null
        return Response(bytes[10].toInt() and 0xFF, bytes[11].toInt() and 0xFF, bytes[12].toInt() and 0xFF,
            bytes.copyOfRange(13, bytes.size - 4))
    }
    /** START, END or COMPLETE of a history transfer; END carries the eight bytes its acknowledgement echoes. */
    data class Boundary(val kind: Int, val token: ByteArray?)
    fun boundary(frame: Frame): Boundary? {
        val bytes = frame.bytes
        if (!frame.ok || frame.type != METADATA || bytes.size < 15) return null
        val kind = bytes[10].toInt() and 0xFF
        if (kind !in HISTORY_START..HISTORY_COMPLETE) return null
        return Boundary(kind, if (kind == HISTORY_END && bytes.size >= 33) bytes.copyOfRange(21, 29) else null)
    }
    /** GET_CLOCK: revision, Unix seconds, 1/32768 s; zero seconds mean an unset clock. */
    fun clockSeconds(body: ByteArray): Long? = if (body.size >= 7 && body[0].toInt() == 1) u32(body, 1) else null
}
