package assistant.mobile.platform.ring

import org.junit.Assert.*
import org.junit.Test

class RingOtaCodecTest {
    @Test fun emptyStartIsOudmonBulkFrameAndLastBlockDoesNotPadTheImage() {
        assertArrayEquals(byteArrayOf(0xbc.toByte(), 1, 0, 0, 0xff.toByte(), 0xff.toByte()), RingOtaCodec.frame(1))
        val last = RingOtaCodec.block(ByteArray(1025) { 0x5a }, 2)
        assertEquals(9, last.size); assertArrayEquals(byteArrayOf(2, 0, 0x5a), last.copyOfRange(6, last.size))
        assertEquals(0x4b37, RingOtaCodec.crc("123456789".toByteArray()))
    }
    @Test fun rawStatusZeroDoesNotProveADataBlockWithoutAnEchoedIndex() {
        val statusOnly = RingOtaCodec.frame(3, byteArrayOf(0))
        assertNull(RingOtaCodec.receipt(statusOnly, 3, 1, RingOtaLoaderRecipe(emptyList(), null)))
        val indexed = RingOtaCodec.frame(3, byteArrayOf(0, 1, 0))
        val synthetic = RingOtaLoaderRecipe(emptyList(), 1)
        assertEquals(0, RingOtaCodec.receipt(indexed, 3, 1, synthetic))
        assertNull(RingOtaCodec.receipt(indexed, 3, 2, synthetic))
        indexed[6] = 1; assertNull(RingOtaCodec.receipt(indexed, 3, 1, synthetic))
    }
}
