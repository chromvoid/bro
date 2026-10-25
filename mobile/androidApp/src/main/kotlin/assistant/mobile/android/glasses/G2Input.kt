package assistant.mobile.android.glasses

internal data class G2Notification(val bytes: ByteArray, val receivedAtMillis: Long)
internal enum class G2InputType(val wireValue: Int) {
    Click(0), Up(1), Down(2), DoubleClick(3), Enter(4), Exit(5), AbnormalExit(6), SystemExit(7),
}
internal data class G2Input(val type: G2InputType, val receivedAtMillis: Long)

/** Bounded, ordered reassembly for the right arm's EvenHub event channel. */
internal class G2InputDecoder {
    private var sequence = -1
    private var flag = -1
    private var total = 0
    private var next = 1
    private var started = 0L
    private var body = byteArrayOf()

    fun receive(notification: G2Notification): G2Input? {
        val bytes = notification.bytes
        fun u(index: Int) = bytes[index].toInt() and 255
        if (bytes.size < 8 || u(0) != 0xaa || u(6) != 0xe0 || u(7) !in listOf(1, 6)) return null
        if (bytes.size != u(3) + 8 || u(4) !in 1..32 || u(5) !in 1..u(4)) { reset(); return null }
        if (u(5) == 1) {
            reset(); sequence = u(2); flag = u(7); total = u(4); started = notification.receivedAtMillis
        }
        if (u(2) != sequence || u(7) != flag || u(4) != total || u(5) != next ||
            notification.receivedAtMillis - started !in 0..2000 || body.size + bytes.size - 8 > 4096) {
            reset(); return null
        }
        body += bytes.copyOfRange(8, bytes.size)
        next++
        if (u(5) != total) return null
        val complete = body
        reset()
        if (complete.size < 2) return null
        val payload = complete.copyOfRange(0, complete.size - 2)
        val crc = (complete[complete.lastIndex - 1].toInt() and 255) or ((complete.last().toInt() and 255) shl 8)
        if (G2Protocol.crc(payload) != crc) return null
        return try {
            val envelope = fields(payload)
            if (envelope.number(1) != 2L) return null // ACKs and private messages are not gestures.
            val event = fields(envelope.bytes(13) ?: return null)
            val kind = (1..3).singleOrNull { event.bytes(it) != null } ?: return null
            val item = fields(event.bytes(kind) ?: return null)
            if (kind != 3) {
                val id = item.number(1)
                val name = item.bytes(2)?.toString(Charsets.UTF_8)
                if (id != null && id != 1L || name != null && name != "assistant") return null
            }
            // Protobuf omits the default enum value: an absent EventType means CLICK.
            val value = item.number(when (kind) { 1 -> 5; 2 -> 3; else -> 1 }) ?: 0L
            val type = G2InputType.entries.singleOrNull { it.wireValue.toLong() == value } ?: return null
            G2Input(type, notification.receivedAtMillis)
        } catch (_: IllegalArgumentException) { null }
    }

    private fun reset() { sequence = -1; flag = -1; total = 0; next = 1; body = byteArrayOf() }

    private data class Field(val id: Int, val number: Long? = null, val bytes: ByteArray? = null)
    private fun List<Field>.number(id: Int): Long? {
        val field = lastOrNull { it.id == id } ?: return null
        return requireNotNull(field.number)
    }
    private fun List<Field>.bytes(id: Int): ByteArray? {
        val field = lastOrNull { it.id == id } ?: return null
        return requireNotNull(field.bytes)
    }

    /** Minimal protobuf reader for the documented event fields; unknown scalar fields are skipped. */
    private fun fields(bytes: ByteArray): List<Field> {
        var offset = 0
        fun varint(): Long {
            var value = 0L
            for (i in 0..9) {
                require(offset < bytes.size)
                val byte = bytes[offset++].toInt() and 255
                require(i < 9 || byte <= 1)
                value = value or ((byte and 127).toLong() shl (7 * i))
                if (byte < 128) return value
            }
            throw IllegalArgumentException("Invalid varint")
        }
        fun take(size: Int): ByteArray {
            require(size >= 0 && size <= bytes.size - offset)
            return bytes.copyOfRange(offset, offset + size).also { offset += size }
        }
        return buildList {
            while (offset < bytes.size) {
                val key = varint()
                require(key in 8..0xffffffffL)
                val id = (key ushr 3).toInt()
                when ((key and 7).toInt()) {
                    0 -> add(Field(id, number = varint()))
                    1 -> { take(8); add(Field(id)) }
                    2 -> { val size = varint(); require(size in 0..4096); add(Field(id, bytes = take(size.toInt()))) }
                    5 -> { take(4); add(Field(id)) }
                    else -> throw IllegalArgumentException("Unsupported wire type")
                }
            }
        }
    }
}
