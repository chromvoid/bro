package assistant.mobile.android.glasses

/** Stock G2 wire contract. Sources and pinned revisions: mobile/docs/even-g2.md. */
internal object G2Protocol {
    const val WRITE_UUID = "00002760-08c2-11e1-9073-0e8ac72e5401"
    const val NOTIFY_UUID = "00002760-08c2-11e1-9073-0e8ac72e5402"

    fun crc(bytes: ByteArray): Int {
        var crc = 0xffff
        for (byte in bytes) {
            crc = crc xor ((byte.toInt() and 255) shl 8)
            repeat(8) { crc = ((crc shl 1) xor if (crc and 0x8000 != 0) 0x1021 else 0) and 0xffff }
        }
        return crc
    }

    private fun varint(value: Long): ByteArray {
        require(value >= 0)
        var remaining = value
        val bytes = ArrayList<Byte>()
        do {
            val low = (remaining and 127).toInt()
            remaining = remaining ushr 7
            bytes += (low or if (remaining != 0L) 128 else 0).toByte()
        } while (remaining != 0L)
        return bytes.toByteArray()
    }

    private fun uint(field: Int, value: Int): ByteArray =
        if (value == 0) byteArrayOf() else varint((field * 8).toLong()) + varint(value.toLong())
    private fun message(field: Int, bytes: ByteArray) =
        varint((field * 8 + 2).toLong()) + varint(bytes.size.toLong()) + bytes
    private fun string(field: Int, value: String) = message(field, value.toByteArray(Charsets.UTF_8))
    private fun hex(value: String) = value.split(' ').map { it.toInt(16).toByte() }.toByteArray()

    /** ATT values have MTU-3 bytes; the G2 header consumes another eight. */
    fun frames(sequence: Int, service: Int, flag: Int, payload: ByteArray, mtu: Int): List<ByteArray> {
        require(mtu >= 23 && payload.isNotEmpty())
        val capacity = minOf(232, mtu - 11)
        // Reserve both CRC bytes in the final fragment, including exact-boundary payloads.
        val parts = payload.toList().chunked(capacity - 2)
        require(parts.size <= 255)
        val crc = crc(payload)
        return parts.mapIndexed { index, part ->
            val body = part.toByteArray() + if (index == parts.lastIndex) byteArrayOf(crc.toByte(), (crc ushr 8).toByte()) else byteArrayOf()
            byteArrayOf(0xaa.toByte(), 0x21, sequence.toByte(), body.size.toByte(), parts.size.toByte(), (index + 1).toByte(), service.toByte(), flag.toByte()) + body
        }
    }

    fun authentication(timestampSeconds: Long): List<ByteArray> {
        val timestamp = varint(timestampSeconds)
        // The timestamp message's length must follow the varint size (including after 2038).
        val clock = message(128, hex("08") + timestamp + hex("10 E8 FF FF FF FF FF FF FF FF 01"))
        val payloads = listOf(
            hex("08 04 10 0C 1A 04 08 01 10 04"), hex("08 05 10 0E 22 02 08 02"),
            hex("08 80 01 10 0F") + clock, hex("08 04 10 10 1A 04 08 01 10 04"),
            hex("08 04 10 11 1A 04 08 01 10 04"), hex("08 05 10 12 22 02 08 01"),
            hex("08 80 01 10 13") + clock,
        )
        return payloads.mapIndexed { i, p -> frames(i + 1, 0x80, if (i in listOf(1, 2, 5, 6)) 0x20 else 0, p, 247).single() }
    }

    fun heartbeat(sequence: Int) = frames(sequence, 0x80, 0, hex("08 25"), 247).single()
    fun prelude() = hex("AA 21 92 13 01 01 01 20 08 02 10 9C 01 22 0A 1A 08 12 06 12 04 08 00 10 00 A1 42")

    fun textPage(text: String, create: Boolean, magic: Int): ByteArray {
        require(text.isNotBlank() && text.toByteArray(Charsets.UTF_8).size <= 1000)
        return nativeTextPage(text, create, magic, borderWidth = 0)
    }

    // A space keeps a valid text container while leaving only the outline visible.
    fun boundsPage(create: Boolean, magic: Int) = nativeTextPage(" ", create, magic, borderWidth = 1)

    private fun nativeTextPage(text: String, create: Boolean, magic: Int, borderWidth: Int): ByteArray {
        // X/Y and corner radius default to zero; border sits on the full 576x288 container.
        val border = if (borderWidth > 0) uint(5, borderWidth) + uint(6, 15) else uint(8, 8)
        val container = uint(3, 576) + uint(4, 288) + border + uint(9, 1) +
            string(10, "assistant") + uint(11, 1) + string(12, text)
        val page = uint(1, 1) + message(3, container) + if (create) uint(5, 10000) else byteArrayOf()
        return uint(1, if (create) 0 else 7) + uint(2, magic) + message(if (create) 3 else 7, page)
    }
    fun pluginHeartbeat(magic: Int) = uint(1, 12) + uint(2, magic) + message(14, byteArrayOf())
    fun updateText(text: String, magic: Int): ByteArray {
        val bytes = text.toByteArray(Charsets.UTF_8)
        require(text.isNotBlank() && bytes.size <= 1000)
        val update = uint(1, 1) + string(2, "assistant") + uint(4, bytes.size) + message(5, bytes)
        return uint(1, 5) + uint(2, magic) + message(9, update)
    }
    fun shutdown(magic: Int) = uint(1, 9) + uint(2, magic) + message(11, byteArrayOf())
}

internal enum class G2Side { Left, Right }
internal data class G2Device(val address: String, val name: String, val side: G2Side) {
    companion object {
        fun fromAdvertisement(address: String, name: String?): G2Device? {
            if (name == null || !name.startsWith("Even G2", ignoreCase = true)) return null
            val tokens = name.uppercase().split('_', ' ', '-')
            val left = tokens.any { it == "L" || it == "LEFT" }
            val right = tokens.any { it == "R" || it == "RIGHT" }
            if (left == right) return null // Never guess a side or silently combine two people's glasses.
            return G2Device(address, name, if (left) G2Side.Left else G2Side.Right)
        }
    }
}
