package assistant.mobile.platform.ring

import org.junit.Assert.*
import org.junit.Test

class RingOtaBleTest {
    @Test fun splitHeaderAndCoalescedFramesKeepActualFirstFragmentTime() {
        val assembler = RingOtaNoticeAssembler()
        val first = RingOtaCodec.frame(2, byteArrayOf(0))
        val second = RingOtaCodec.frame(3, byteArrayOf(0, 1, 0))
        assertTrue(assembler.push(first.copyOfRange(0, 3), 100).isEmpty())
        val notices = assembler.push(first.copyOfRange(3, first.size) + second, 200)
        assertEquals(2, notices.size)
        assertArrayEquals(first, notices[0].frame); assertEquals(100L, notices[0].receivedElapsedMs)
        assertArrayEquals(second, notices[1].frame); assertEquals(200L, notices[1].receivedElapsedMs)
    }
    @Test fun malformedFrameDiscardsStreamAndNeverYieldsUncheckedReceipt() {
        val assembler = RingOtaNoticeAssembler()
        val corrupt = RingOtaCodec.frame(3, byteArrayOf(0, 1, 0)).also { it[6] = 1 }
        try { assembler.push(corrupt, 100); fail("Expected corrupt frame") }
        catch (failure: RingFailure) { assertEquals("ota_notification_invalid", failure.code) }
        val clean = RingOtaCodec.frame(4, byteArrayOf(0))
        assertArrayEquals(clean, assembler.push(clean, 200).single().frame)
        try { assembler.push(byteArrayOf(0xbc.toByte(), 3, 0xff.toByte(), 0xff.toByte(), 0, 0), 300); fail("Oversized frame") }
        catch (failure: RingFailure) { assertEquals("ota_notification_invalid", failure.code) }
    }
    @Test fun batteryReadRequiresExactOpcodeChecksumAndBoundedFields() {
        val request = RingOtaBatteryRead.request()
        assertEquals(16, request.size); assertEquals(3, request[0].toInt()); assertEquals(3, request[15].toInt())
        val reply = ByteArray(16).also { it[0] = 3; it[1] = 80; it[2] = 1; it[15] = 84 }
        assertEquals(80, RingOtaBatteryRead.decode(reply))
        assertNull(RingOtaBatteryRead.decode(reply.copyOf(15)))
        assertNull(RingOtaBatteryRead.decode(reply.copyOf().also { it[15] = 0 }))
        assertNull(RingOtaBatteryRead.decode(reply.copyOf().also { it[1] = 101; it[15] = 105 }))
        assertNull(RingOtaBatteryRead.decode(reply.copyOf().also { it[0] = 4; it[15] = 85 }))
    }

}
