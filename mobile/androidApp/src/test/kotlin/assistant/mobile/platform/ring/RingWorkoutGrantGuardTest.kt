package assistant.mobile.platform.ring

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test

class RingWorkoutGrantGuardTest {
    private val owner = RingWorkoutBinding("https://synthetic.invalid", "owner", "source", "collector",
        "00:11:22:33:44:55", 1, "UTC")

    @Test fun activeRecorderRevokeIsCheckedWithoutAnyUiOrUploadQueue() = runBlocking {
        val verified = mutableListOf<RingWorkoutBinding>()
        val revoked = mutableListOf<RingWorkoutBinding>()
        val guard = RingWorkoutGrantGuard({ owner }, { captured ->
            verified += captured
            RingWorkoutGrantStatus.REVOKED
        }, { revoked += it })
        guard.check()
        assertEquals(listOf(owner), verified)
        assertEquals(listOf(owner), revoked)
    }

    @Test fun unavailableOrVerifiedNetworkResultPreservesTheAcceptedRecorder() = runBlocking {
        for (status in listOf(RingWorkoutGrantStatus.UNAVAILABLE, RingWorkoutGrantStatus.VERIFIED)) {
            var current: RingWorkoutBinding? = owner
            var checked = false
            val guard = RingWorkoutGrantGuard({ current }, {
                assertEquals(owner, it); checked = true; status
            }, { current = null })
            guard.check()
            assertTrue(checked)
            assertEquals(owner, current)
        }
    }

    @Test fun inFlightRejectionCannotRevokeAReplacementUserSourceOrGrantGeneration() = runBlocking {
        for (replacement in listOf(owner.copy(userId = "other"), owner.copy(sourceId = "other-source"),
            owner.copy(grantGeneration = 2))) {
            var current: RingWorkoutBinding? = owner
            val started = CompletableDeferred<RingWorkoutBinding>()
            val reply = CompletableDeferred<RingWorkoutGrantStatus>()
            val revoked = mutableListOf<RingWorkoutBinding>()
            val guard = RingWorkoutGrantGuard({ current }, {
                started.complete(it)
                reply.await()
            }, { revoked += it })
            val check = launch { guard.check() }
            assertEquals(owner, withTimeout(5_000) { started.await() })
            current = replacement
            reply.complete(RingWorkoutGrantStatus.REVOKED)
            withTimeout(5_000) { check.join() }
            assertEquals(replacement, current)
            assertTrue(revoked.isEmpty())
        }
    }

    @Test fun stoppedRecorderDoesNotStartVerificationOrReceiveAnOldRejection() = runBlocking {
        var current: RingWorkoutBinding? = owner
        val started = CompletableDeferred<Unit>()
        val reply = CompletableDeferred<RingWorkoutGrantStatus>()
        val guard = RingWorkoutGrantGuard({ current }, {
            started.complete(Unit); reply.await()
        }, { fail("A stopped recorder received a stale rejection") })
        val check = launch { guard.check() }
        withTimeout(5_000) { started.await() }; current = null
        reply.complete(RingWorkoutGrantStatus.REVOKED); withTimeout(5_000) { check.join() }
        RingWorkoutGrantGuard({ null }, { error("No accepted recorder exists") }, { fail("No owner exists") }).check()
    }

}
