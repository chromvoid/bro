package assistant.mobile.android.glasses

import org.junit.Assert.*
import org.junit.Test

class G2ProtocolTest {
    private fun hex(s: String) = s.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    @Test fun stockAuthenticationAndHeartbeatMatchKnownWireVectors() {
        assertEquals(0x29b1, G2Protocol.crc("123456789".toByteArray())) // CRC-16/CCITT-FALSE check value.
        val auth = G2Protocol.authentication(1_700_000_000)
        assertEquals(7, auth.size)
        assertArrayEquals(hex("aa21010c010180000804100c1a0408011004c6bc"), auth.first())
        // Timestamp 1700000000 is 80 e2 cf aa 06; auth field 128 has 17 bytes.
        assertArrayEquals(hex("088001100f8208110880e2cfaa0610e8ffffffffffffffff01"), auth[2].copyOfRange(8, auth[2].size - 2))
        assertArrayEquals(hex("aa21210401018000082561e0"), G2Protocol.heartbeat(33))
    }

    @Test fun nativePageUsesTextContainerAndUtf8ByteLengths() {
        // Independently specified protobuf fixture: one 576x288 text container, padding8,
        // id1, name assistant, capture1, content "Hi", widget10000, magic2.
        assertArrayEquals(hex("10021a2208011a1b18c00420a002400848015209617373697374616e7458016202486928904e"), G2Protocol.textPage("Hi", true, 2))
        val russian = G2Protocol.textPage("Привет", true, 2)
        assertTrue(russian.toList().windowed(14).any { it.toByteArray().contentEquals(hex("620cd09fd180d0b8d0b2d0b5d182")) })
        assertThrows(IllegalArgumentException::class.java) { G2Protocol.textPage(" ", true, 2) }
        assertThrows(IllegalArgumentException::class.java) { G2Protocol.textPage("я".repeat(501), true, 2) }
    }

    @Test fun fragmentsFitAttMtuAndCarryExactlyOneCompleteChecksum() {
        for (mtu in listOf(23, 64, 247, 517)) for (length in listOf(1, 10, 11, 12, 51, 52, 53, 229, 230, 231, 232, 233, 1000)) {
            val payload = ByteArray(length) { (it * 31).toByte() }
            val frames = G2Protocol.frames(42, 0xe0, 0x20, payload, mtu)
            frames.forEachIndexed { i, frame ->
                assertTrue("MTU=$mtu length=$length", frame.size <= mtu - 3)
                assertEquals(42, frame[2].toInt() and 255)
                assertEquals(frame.size - 8, frame[3].toInt() and 255)
                assertEquals(frames.size, frame[4].toInt() and 255)
                assertEquals(i + 1, frame[5].toInt() and 255)
            }
            val reassembled = frames.flatMap { it.drop(8) }.toByteArray()
            assertArrayEquals(payload, reassembled.copyOfRange(0, length))
            assertEquals(length + 2, reassembled.size)
            assertTrue(frames.last().size >= 10)
            assertEquals(G2Protocol.crc(payload), (reassembled[length].toInt() and 255) or ((reassembled[length + 1].toInt() and 255) shl 8))
        }
    }

    @Test fun discoveriesRequireAnExplicitG2Side() {
        assertEquals(G2Side.Left, G2Device.fromAdvertisement("A", "Even G2_42_L_ABC")?.side)
        assertEquals(G2Side.Right, G2Device.fromAdvertisement("B", "Even G2_R_DEF")?.side)
        assertNull(G2Device.fromAdvertisement("C", "Even R1_42"))
        assertNull(G2Device.fromAdvertisement("C", "Even G1_L_ABC"))
        assertNull(G2Device.fromAdvertisement("C", "Even G2_ABC"))
        assertNull(G2Device.fromAdvertisement("C", "Even G2_L_R_ABC"))
        assertNull(G2Device.fromAdvertisement("C", null))
    }

}
