package assistant.mobile.platform.ring

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test

class RingProtocolWriteTest {
    @Test fun optionalSubmissionAndAttFailuresCompleteFalseAndAllowTheNextWrite() = runBlocking {
        for (query in listOf("running_steps:0:0", "declared_capabilities:core_3c")) for (code in listOf("write_failed", "gatt_error")) {
            val events = mutableListOf<String>()
            RingProtocolWrite.execute(query,
                write = { events += "optional_write"; throw RingFailure(code, "synthetic rejection") },
                complete = { events += "optional_complete:$it" })
            RingProtocolWrite.execute("steps:0:0",
                write = { events += "steps_write" }, complete = { events += "steps_complete:$it" })
            assertEquals(listOf("optional_write", "optional_complete:false", "steps_write", "steps_complete:true"), events)
        }
    }

    @Test fun cancelledAndTimedOutOptionalWritesCannotBeTreatedAsRejectedAttWrites() = runBlocking {
        for (query in listOf("running_steps:0:0", "declared_capabilities:core_3c")) {
            var completed = false
            val cancelled = CancellationException("synthetic cancellation")
            try {
                RingProtocolWrite.execute(query, write = { throw cancelled }, complete = { completed = true })
                fail("Cancellation must propagate")
            } catch (actual: CancellationException) { assertSame(cancelled, actual) }
            assertFalse(completed)
            try {
                RingProtocolWrite.execute(query,
                    write = { withTimeout(10) { awaitCancellation() } }, complete = { completed = true })
                fail("A missing GATT completion must abort collection")
            } catch (_: TimeoutCancellationException) { }
            assertFalse(completed)
        }
    }
}
