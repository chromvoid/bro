package assistant.mobile.android.ambient

import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class AmbientOpusConfigTest {
    @Test
    fun actualAndroidUnifiedHeaderUsesPackedDelayAt48kAndPreservesItsInput() {
        val original = hex(PHONE_CSD)
        val saved = original.copyOf()
        val result = AmbientOpusConfig.normalize(original)
        assertEquals(83, original.size)
        assertEquals(104, preSkip(original.copyOfRange(16, 35)))
        assertArrayEquals(head(preSkip = 312), result)
        assertEquals(312, preSkip(result))
        assertEquals(16_000, ByteBuffer.wrap(result).order(ByteOrder.LITTLE_ENDIAN).getInt(12))
        assertArrayEquals(saved, original)
    }

    @Test
    fun truncationExtraBytesDuplicatesUnknownMarkersAndLengthOverflowAreRejected() {
        val valid = hex(PHONE_CSD)
        for (size in 0 until valid.size) invalid { AmbientOpusConfig.normalize(valid.copyOf(size)) }
        invalid { AmbientOpusConfig.normalize(valid + byteArrayOf(0)) }
        invalid { AmbientOpusConfig.normalize(ByteArray(1024 * 1024)) }
        invalid { AmbientOpusConfig.normalize(valid.copyOf().apply { this[0] = 'X'.code.toByte() }) }
        invalid { AmbientOpusConfig.normalize(record("AOPUSHDR", head()) + record("AOPUSDLY", nanos(0)) + record("AOPUSDLY", nanos(0))) }
        invalid { AmbientOpusConfig.normalize(record("AOPUSHDR", head()) + record("AOPUSPRL", nanos(0)) + record("AOPUSPRL", nanos(0))) }
        invalid { AmbientOpusConfig.normalize(record("AOPUSHDR", head()) + record("AOPUSHDR", head()) + record("AOPUSDLY", nanos(0))) }
        for (length in listOf(-1L, 0L, 18L, 20L, Long.MIN_VALUE, Long.MAX_VALUE)) {
            invalid { AmbientOpusConfig.normalize(valid.copyOf().apply { ByteBuffer.wrap(this).order(ByteOrder.nativeOrder()).putLong(8, length) }) }
        }
        for (offset in listOf(43, 67)) {
            for (length in listOf(7L, 9L, Long.MAX_VALUE)) {
                invalid { AmbientOpusConfig.normalize(valid.copyOf().apply { ByteBuffer.wrap(this).order(ByteOrder.nativeOrder()).putLong(offset, length) }) }
            }
        }
    }

    private fun record(marker: String, payload: ByteArray): ByteArray = ByteBuffer.allocate(16 + payload.size)
        .order(ByteOrder.nativeOrder()).put(marker.toByteArray(Charsets.US_ASCII)).putLong(payload.size.toLong()).put(payload).array()

    private fun nanos(value: Long): ByteArray = ByteBuffer.allocate(8).order(ByteOrder.nativeOrder()).putLong(value).array()

    private fun head(preSkip: Int = 312, sourceRate: Int = 16_000): ByteArray = ByteBuffer.allocate(19)
        .order(ByteOrder.LITTLE_ENDIAN).put("OpusHead".toByteArray(Charsets.US_ASCII))
        .put(1).put(1).putShort(preSkip.toShort()).putInt(sourceRate).putShort(0).put(0).array()

    private fun preSkip(header: ByteArray): Int = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN).getShort(10).toInt() and 0xffff

    private fun hex(value: String): ByteArray = value.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    private fun invalid(block: () -> Unit) { assertThrows(IllegalArgumentException::class.java) { block() } }

    companion object {
        private const val PHONE_CSD = "414f50555348445213000000000000004f7075734865616401016800803e0000000000" +
            "414f505553444c590800000000000000a02e630000000000414f50555350524c080000000000000000b4c40400000000"
    }
}
