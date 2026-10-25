package assistant.mobile.android.glasses

import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class G2SessionTest {
    private class Link(private val failOpen: Boolean = false) : G2Link {
        override val mtu = 64
        val incoming = Channel<G2Notification>(64)
        override val notifications = incoming.receiveAsFlow()
        var opened = false
        var closed = false
        val disconnected = CompletableDeferred<Unit>()
        val packets = mutableListOf<ByteArray>()
        override suspend fun open() { if (failOpen) throw G2Failure("unavailable"); opened = true }
        override suspend fun write(bytes: ByteArray) {
            check(opened && !closed)
            packets += bytes
        }
        override suspend fun awaitDisconnect() { disconnected.await() }
        override fun close() { closed = true }
    }

    @Test fun textAndPluginCommandsOnlyGoToRightArmAfterBothAuthenticate() = runTest {
        val left = Link(); val right = Link(); val session = G2Session(left, right)
        session.connect(1_700_000_000)
        assertEquals(7, left.packets.size); assertEquals(7, right.packets.size)
        left.packets.zip(right.packets).forEach { (l, r) -> assertArrayEquals(l, r) }
        session.display("Hello")
        assertEquals(7, left.packets.size)
        assertArrayEquals(G2Protocol.prelude(), right.packets[7])
        val create = right.packets.drop(8)
        assertTrue(create.all { (it[6].toInt() and 255) == 0xe0 && it.size <= right.mtu - 3 })
        val previous = right.packets.size
        session.display("Second message")
        assertEquals(7, left.packets.size)
        assertEquals(0xe0, right.packets[previous][6].toInt() and 255) // No second prelude.
        assertEquals(8, right.packets[previous][8].toInt())
        assertEquals(7, right.packets[previous][9].toInt()) // Rebuild, not a second CREATE.
        session.releasePage()
        session.close()
        assertTrue(left.closed && right.closed)
    }

    @Test fun failingSecondArmClosesFirstAndDoesNotPermitDisplay() = runTest {
        val left = Link(); val right = Link(failOpen = true); val session = G2Session(left, right)
        try { session.connect(1_700_000_000); fail("Must fail") } catch (_: G2Failure) { }
        assertTrue(left.closed && right.closed)
        assertTrue(left.packets.isEmpty())
        try { session.display("Hello"); fail("Must not send") } catch (_: IllegalStateException) { }
    }

    @Test fun heartbeatStopsWhenAnArmDisconnects() = runTest {
        val left = Link(); val right = Link(); val session = G2Session(left, right)
        session.connect(1_700_000_000)
        var failure: Exception? = null
        val job = launch {
            try { session.maintain() } catch (e: G2Failure) { failure = e } finally { session.close() }
        }
        runCurrent()
        assertEquals(8, left.packets.size)
        advanceTimeBy(1501)
        assertEquals(9, left.packets.size)
        right.disconnected.complete(Unit)
        job.join()
        assertNotNull(failure)
        val count = left.packets.size
        advanceTimeBy(5000)
        assertEquals(count, left.packets.size)
        assertTrue(left.closed && right.closed)
    }

}
