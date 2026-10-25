package assistant.mobile.android.ambient

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.RandomAccessFile
import java.util.UUID

/** A bounded Ogg/Opus container and packet-framing implementation; it never decodes audio. */
object AmbientOgg {
    private const val RATE = 48_000
    private const val MAX_SAMPLES = 31L * RATE
    private const val MAX_PACKET_SAMPLES = 5_760
    private const val MAX_PACKET_BYTES = 61_440
    private const val MAX_TAG_BYTES = 4_096
    private const val MAX_PAGE_BYTES = 65_307
    private val crcTable = IntArray(256) { value ->
        var crc = value shl 24
        repeat(8) { crc = if (crc and Int.MIN_VALUE != 0) (crc shl 1) xor 0x04c11db7 else crc shl 1 }
        crc
    }

    fun encode(opusHead: ByteArray, packets: List<ByteArray>, sourceSampleCount: Long): ByteArray {
        require(sourceSampleCount in 1..AmbientAudioFormat.MAX_SOURCE_SAMPLES && packets.isNotEmpty())
        val head = opusHead.copyOf()
        require(head.size == 19)
        put32(head, 12, AmbientWav.SAMPLE_RATE.toLong())
        val preSkip = headPreSkip(head)
        val samples = packets.map(::packetSampleCount)
        val total = samples.sumOf { it.toLong() }
        val finalGranule = preSkip + sourceSampleCount * 3
        require(total >= finalGranule && total - finalGranule <= samples.last()) { "Incomplete Opus encoder output" }
        val out = ByteArrayOutputStream()
        val serial = UUID.randomUUID().leastSignificantBits and 0xffff_ffffL
        var sequence = 0L
        fun emit(group: List<ByteArray>, granule: Long, flags: Int) {
            val page = page(group, granule, flags, serial, sequence++)
            require(out.size() + page.size <= AmbientAudioFormat.MAX_COMPRESSED_BYTES)
            out.write(page)
        }
        emit(listOf(head), 0, 2)
        emit(listOf("OpusTags".toByteArray(Charsets.US_ASCII) + ByteArray(8)), 0, 0)
        var granule = 0L
        val group = mutableListOf<ByteArray>()
        var groupSamples = 0
        var groupBytes = 0
        var groupLaces = 0
        packets.forEachIndexed { index, packet ->
            val laces = packet.size / 255 + 1
            if (group.isNotEmpty() && (groupSamples + samples[index] > RATE || groupBytes + packet.size > MAX_PACKET_BYTES || groupLaces + laces > 255)) {
                emit(group, granule, 0)
                group.clear(); groupSamples = 0; groupBytes = 0; groupLaces = 0
            }
            group.add(packet)
            groupSamples += samples[index]; groupBytes += packet.size; groupLaces += laces
            granule += samples[index]
            if (index == packets.lastIndex) emit(group, finalGranule, 4)
        }
        return out.toByteArray().also { validate(it) }
    }

    /** Packet duration and structural frame boundaries, in the fixed 48 kHz Ogg timebase. */
    fun packetSampleCount(packet: ByteArray): Int {
        require(packet.isNotEmpty() && packet.size <= MAX_PACKET_BYTES)
        val toc = packet[0].toInt() and 0xff
        val config = toc ushr 3
        val perFrame = when {
            config >= 16 -> 120 shl (config and 3)
            config >= 12 -> 480 shl (config and 1)
            config and 3 == 3 -> 2_880
            else -> 480 shl (config and 3)
        }
        var offset = 1
        var end = packet.size
        fun frameLength(): Int {
            require(offset < end)
            val first = packet[offset++].toInt() and 0xff
            if (first < 252) return first
            require(offset < end)
            return first + 4 * (packet[offset++].toInt() and 0xff)
        }
        val count = when (toc and 3) {
            0 -> { require(end - offset <= 1_275); 1 }
            1 -> { require((end - offset) % 2 == 0 && (end - offset) / 2 <= 1_275); 2 }
            2 -> {
                val first = frameLength()
                require(first <= 1_275 && first <= end - offset && end - offset - first <= 1_275)
                2
            }
            else -> {
                require(offset < end)
                val control = packet[offset++].toInt() and 0xff
                val frames = control and 63
                require(frames in 1..48 && frames * perFrame <= MAX_PACKET_SAMPLES)
                if (control and 64 != 0) {
                    var padding = 0
                    var part: Int
                    do {
                        require(offset < end)
                        part = packet[offset++].toInt() and 0xff
                        padding += if (part == 255) 254 else part
                        require(padding <= end - offset)
                    } while (part == 255)
                    end -= padding
                }
                if (control and 128 != 0) {
                    var lengths = 0
                    repeat(frames - 1) {
                        val length = frameLength()
                        require(length <= 1_275)
                        lengths += length
                        require(lengths <= end - offset)
                    }
                    require(end - offset - lengths in 0..1_275)
                } else require((end - offset) % frames == 0 && (end - offset) / frames <= 1_275)
                frames
            }
        }
        require(count * perFrame <= MAX_PACKET_SAMPLES)
        return count * perFrame
    }

    fun validate(bytes: ByteArray): AmbientAudioInfo {
        require(bytes.size in 1..AmbientAudioFormat.MAX_COMPRESSED_BYTES)
        var offset = 0
        var sequence = 0L
        var serial: Long? = null
        var packetIndex = 0
        var preSkip = 0L
        var previousGranule: Long? = null
        var totalSamples = 0L
        var finalGranule: Long? = null
        val pending = ByteArrayOutputStream()
        while (offset < bytes.size) {
            val page = readPage(bytes, offset)
            require(page.sequence == sequence++ && (serial == null || serial == page.serial))
            if (serial == null) serial = page.serial
            require((page.flags and 2 != 0) == (offset == 0))
            require((page.flags and 1 != 0) == (pending.size() > 0))
            require(crc(bytes, offset, page.size) == page.checksum)
            var payload = offset + page.headerBytes
            var pageSamples = 0L
            var lastPacketSamples = 0
            var completed = 0
            for (length in page.lacing) {
                require(pending.size() + length <= if (packetIndex == 1) MAX_TAG_BYTES else MAX_PACKET_BYTES)
                pending.write(bytes, payload, length)
                payload += length
                if (length < 255) {
                    val packet = pending.toByteArray()
                    pending.reset()
                    when (packetIndex) {
                        0 -> { preSkip = headPreSkip(packet); require(page.granule == 0L && page.flags == 2 && page.lacing.size == 1) }
                        1 -> { validateTags(packet); require(page.granule == 0L && completed == 0) }
                        else -> { lastPacketSamples = packetSampleCount(packet); pageSamples += lastPacketSamples }
                    }
                    packetIndex++
                    completed++
                }
            }
            if (pageSamples > 0) {
                require(packetIndex > 2 && page.granule >= 0)
                val expected = (previousGranule ?: 0L) + pageSamples
                if (page.flags and 4 == 0) require(page.granule == expected)
                else require(page.granule in (expected - lastPacketSamples)..expected)
                previousGranule = page.granule
                totalSamples += pageSamples
                require(totalSamples <= MAX_SAMPLES + preSkip + MAX_PACKET_SAMPLES)
            } else if (packetIndex > 2) {
                require(page.granule == -1L || (page.flags and 4 != 0 && page.granule == previousGranule))
            } else require(page.granule == 0L || (pending.size() > 0 && page.granule == -1L))
            if (packetIndex == 2 && completed > 0) require(pageSamples == 0L)
            if (page.flags and 4 != 0) {
                require(offset + page.size == bytes.size && pending.size() == 0 && packetIndex > 2)
                finalGranule = page.granule
            }
            offset += page.size
        }
        val samples = (finalGranule ?: throw IllegalArgumentException("Missing Opus EOS")) - preSkip
        require(samples in 1..MAX_SAMPLES && pending.size() == 0)
        return AmbientAudioInfo(samples, RATE)
    }

    /** Bound initial status reads to the header pages and one possible final Ogg page. */
    fun probe(file: File): AmbientAudioInfo = RandomAccessFile(file, "r").use { input ->
        val length = input.length()
        require(length in 1..AmbientAudioFormat.MAX_COMPRESSED_BYTES.toLong())
        fun headerPage(maximum: Int): ByteArray {
            val base = ByteArray(27)
            input.readFully(base)
            require(magic(base, 0, "OggS") && base[4].toInt() == 0)
            val segments = base[26].toInt() and 0xff
            val lacing = ByteArray(segments)
            input.readFully(lacing)
            val payload = lacing.sumOf { it.toInt() and 0xff }
            require(27 + segments + payload <= maximum)
            val body = ByteArray(payload)
            input.readFully(body)
            return base + lacing + body
        }
        val headPage = headerPage(27 + 1 + 19)
        val head = readPage(headPage, 0)
        require(head.flags == 2 && head.sequence == 0L && head.granule == 0L && head.lacing.contentEquals(intArrayOf(19)))
        require(crc(headPage, 0, head.size) == head.checksum)
        val preSkip = headPreSkip(headPage.copyOfRange(head.headerBytes, headPage.size))
        val tagsPage = headerPage(MAX_TAG_BYTES + 282)
        val tags = readPage(tagsPage, 0)
        require(tags.flags == 0 && tags.sequence == 1L && tags.serial == head.serial && tags.granule == 0L && tags.lacing.lastOrNull() != 255)
        require(tags.lacing.dropLast(1).all { it == 255 } && crc(tagsPage, 0, tags.size) == tags.checksum)
        validateTags(tagsPage.copyOfRange(tags.headerBytes, tags.size))
        val tail = ByteArray(minOf(length, MAX_PAGE_BYTES.toLong()).toInt())
        input.seek(length - tail.size)
        input.readFully(tail)
        var final: Page? = null
        for (candidate in (tail.size - 27) downTo 0) {
            if (!magic(tail, candidate, "OggS")) continue
            val page = try { readPage(tail, candidate) } catch (_: IllegalArgumentException) { continue }
            if (candidate + page.size == tail.size && page.flags and 4 != 0 && page.serial == head.serial && page.sequence >= 2 &&
                page.lacing.lastOrNull() != 255 && crc(tail, candidate, page.size) == page.checksum) {
                final = page
                break
            }
        }
        val samples = (final ?: throw IllegalArgumentException("Missing Opus EOS")).granule - preSkip
        require(samples in 1..MAX_SAMPLES)
        AmbientAudioInfo(samples, RATE)
    }

    private data class Page(val flags: Int, val granule: Long, val serial: Long, val sequence: Long, val checksum: Int,
                            val headerBytes: Int, val size: Int, val lacing: IntArray)

    private fun readPage(bytes: ByteArray, offset: Int): Page {
        require(offset >= 0 && bytes.size - offset >= 27 && magic(bytes, offset, "OggS"))
        require(bytes[offset + 4].toInt() == 0)
        val flags = bytes[offset + 5].toInt() and 0xff
        require(flags and 0xf8 == 0)
        val segments = bytes[offset + 26].toInt() and 0xff
        val header = 27 + segments
        require(bytes.size - offset >= header)
        val lacing = IntArray(segments) { bytes[offset + 27 + it].toInt() and 0xff }
        val size = header + lacing.sum()
        require(bytes.size - offset >= size)
        return Page(flags, read64(bytes, offset + 6), read32(bytes, offset + 14), read32(bytes, offset + 18),
            read32(bytes, offset + 22).toInt(), header, size, lacing)
    }

    private fun page(packets: List<ByteArray>, granule: Long, flags: Int, serial: Long, sequence: Long): ByteArray {
        val payload = packets.sumOf { it.size }
        require(payload <= MAX_PACKET_BYTES)
        val segments = packets.sumOf { it.size / 255 + 1 }
        require(segments <= 255)
        val header = 27 + segments
        val bytes = ByteArray(header + payload)
        "OggS".toByteArray(Charsets.US_ASCII).copyInto(bytes)
        bytes[5] = flags.toByte()
        put64(bytes, 6, granule); put32(bytes, 14, serial); put32(bytes, 18, sequence)
        bytes[26] = segments.toByte()
        var lace = 27
        var offset = header
        for (packet in packets) {
            repeat(packet.size / 255) { bytes[lace++] = 255.toByte() }
            bytes[lace++] = (packet.size % 255).toByte()
            packet.copyInto(bytes, offset)
            offset += packet.size
        }
        put32(bytes, 22, crc(bytes, 0, bytes.size).toLong())
        return bytes
    }

    private fun headPreSkip(head: ByteArray): Long {
        require(head.size == 19 && magic(head, 0, "OpusHead") && head[8].toInt() == 1 && head[9].toInt() == 1 && head[18].toInt() == 0)
        require(read32(head, 12) == AmbientWav.SAMPLE_RATE.toLong())
        require(head[16].toInt() == 0 && head[17].toInt() == 0) // Own encoder has no output gain.
        return (head[10].toInt() and 0xff).toLong() or ((head[11].toInt() and 0xff).toLong() shl 8)
    }

    private fun validateTags(packet: ByteArray) {
        require(packet.size in 16..MAX_TAG_BYTES && magic(packet, 0, "OpusTags"))
        var offset = 8
        val vendor = read32(packet, offset); offset += 4
        require(vendor <= packet.size - offset - 4)
        offset += vendor.toInt()
        val comments = read32(packet, offset); offset += 4
        require(comments <= MAX_TAG_BYTES / 4)
        repeat(comments.toInt()) {
            require(packet.size - offset >= 4)
            val length = read32(packet, offset); offset += 4
            require(length <= packet.size - offset)
            offset += length.toInt()
        }
    }

    private fun magic(bytes: ByteArray, offset: Int, value: String): Boolean = offset >= 0 && bytes.size - offset >= value.length &&
        value.indices.all { bytes[offset + it] == value[it].code.toByte() }

    private fun crc(bytes: ByteArray, offset: Int, length: Int): Int {
        var value = 0
        for (index in 0 until length) {
            val byte = if (index in 22..25) 0 else bytes[offset + index].toInt() and 0xff
            value = (value shl 8) xor crcTable[((value ushr 24) and 0xff) xor byte]
        }
        return value
    }

    private fun read32(bytes: ByteArray, offset: Int): Long {
        require(offset >= 0 && bytes.size - offset >= 4)
        var value = 0L
        repeat(4) { value = value or ((bytes[offset + it].toLong() and 0xff) shl (it * 8)) }
        return value
    }

    private fun read64(bytes: ByteArray, offset: Int): Long = read32(bytes, offset) or (read32(bytes, offset + 4) shl 32)
    private fun put32(bytes: ByteArray, offset: Int, value: Long) { repeat(4) { bytes[offset + it] = (value ushr (it * 8)).toByte() } }
    private fun put64(bytes: ByteArray, offset: Int, value: Long) { repeat(8) { bytes[offset + it] = (value ushr (it * 8)).toByte() } }
}
