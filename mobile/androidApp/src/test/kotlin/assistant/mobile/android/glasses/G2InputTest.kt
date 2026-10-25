package assistant.mobile.android.glasses

import org.junit.Assert.*
import org.junit.Test

class G2InputTest {
    private fun hex(s: String) = s.split(' ').map { it.toInt(16).toByte() }.toByteArray()
    // Documented protobuf envelope, with CRC independently calculated using Python binascii.crc_hqx.
    private val click = hex("aa 12 01 15 01 01 e0 01 08 02 6a 0f 12 0d 08 01 12 09 61 73 73 69 73 74 61 6e 74 5c 1f")

    @Test fun omittedZeroMeansClickAndSystemDoubleTapIsRecognized() {
        val decoder = G2InputDecoder()
        assertEquals(G2Input(G2InputType.Click, 1234), decoder.receive(G2Notification(click, 1234)))
        val down = hex("aa 12 01 17 01 01 e0 01 08 02 6a 11 12 0f 08 01 12 09 61 73 73 69 73 74 61 6e 74 18 02 b1 61")
        assertEquals(G2InputType.Down, decoder.receive(G2Notification(down, 1400))?.type)
        val double = hex("aa 12 01 0a 01 01 e0 01 08 02 6a 04 1a 02 08 03 bd bb")
        assertEquals(G2InputType.DoubleClick, decoder.receive(G2Notification(double, 1600))?.type)
    }

    @Test fun fragmentsNeedCorrectOrderCrcAndTimelyCompletion() {
        val decoder = G2InputDecoder()
        val payload = click.copyOfRange(8, click.size - 2)
        val parts = G2Protocol.frames(9, 0xe0, 1, payload, 23)
        assertTrue(parts.size > 1)
        parts.dropLast(1).forEach { assertNull(decoder.receive(G2Notification(it, 100))) }
        assertEquals(G2InputType.Click, decoder.receive(G2Notification(parts.last(), 101))?.type)
        assertNull(decoder.receive(G2Notification(parts.last(), 110))) // No first fragment.
        decoder.receive(G2Notification(parts.first(), 200))
        assertNull(decoder.receive(G2Notification(parts.last(), 2500)))
        val damaged = click.copyOf().also { it[it.lastIndex] = 0 }
        assertNull(decoder.receive(G2Notification(damaged, 2600)))
        assertEquals(G2InputType.Click, decoder.receive(G2Notification(click, 2700))?.type)
    }

}
