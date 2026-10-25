package assistant.mobile.platform.ring

import assistant.mobile.platform.R

/** Transport framing only; qualification and loader correlation remain in the OTA actor. */
class RingOtaNoticeAssembler {
    private var buffered = byteArrayOf()
    private var firstElapsedMs: Long? = null
    fun reset() { buffered = byteArrayOf(); firstElapsedMs = null }
    fun push(bytes: ByteArray, elapsedMs: Long): List<RingOtaNotice> {
        if (bytes.size > 32_768 || buffered.size + bytes.size > 49_158) malformed()
        if (buffered.isEmpty()) firstElapsedMs = elapsedMs
        buffered += bytes
        val notices = mutableListOf<RingOtaNotice>()
        while (buffered.isNotEmpty()) {
            if (buffered[0].toInt() and 255 != 0xbc) malformed()
            if (buffered.size < 6) break
            val size = (buffered[2].toInt() and 255) or ((buffered[3].toInt() and 255) shl 8)
            if (size > 16_384) malformed()
            if (buffered.size < size + 6) break
            val frame = buffered.copyOfRange(0, size + 6)
            val checksum = (frame[4].toInt() and 255) or ((frame[5].toInt() and 255) shl 8)
            if (RingOtaCodec.crc(frame.copyOfRange(6, frame.size)) != checksum) malformed()
            notices += RingOtaNotice(frame, firstElapsedMs ?: elapsedMs)
            buffered = buffered.copyOfRange(size + 6, buffered.size)
            firstElapsedMs = if (buffered.isEmpty()) null else elapsedMs
        }
        return notices
    }
    private fun malformed(): Nothing { reset(); throw RingFailure("ota_notification_invalid", RingText.text(R.string.ring_ota_7385528f6701)) }
}

/** Same bounded core03 battery read as the existing portable collector; never a loader command. */
data class RingOtaPowerReading(val percent: Int, val charging: Boolean)
object RingOtaBatteryRead {
    fun request(): ByteArray = ByteArray(16).also { it[0] = 3; it[15] = 3 }
    fun decode(frame: ByteArray): Int? = reading(frame)?.percent
    fun reading(frame: ByteArray): RingOtaPowerReading? {
        if (frame.size != 16 || frame[0].toInt() and 255 != 3 ||
            (frame.take(15).sumOf { it.toInt() and 255 } and 255) != (frame[15].toInt() and 255)) return null
        val percent = frame[1].toInt() and 255; val charging = frame[2].toInt() and 255
        return if (percent <= 100 && charging <= 1) RingOtaPowerReading(percent, charging == 1) else null
    }
}
