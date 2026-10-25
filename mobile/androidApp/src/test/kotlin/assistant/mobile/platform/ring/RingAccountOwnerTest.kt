package assistant.mobile.platform.ring

import kotlinx.coroutines.*
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.put
import org.junit.Assert.*
import org.junit.Test

class RingAccountOwnerTest {
    private val grantA = RingGrant(obj {
        put("user_id", "A"); put("token", "synthetic-token"); put("collector_id", "synthetic-collector")
        put("source_id", "synthetic-source"); put("generation", 1)
    })

    @Test fun lapseThenDifferentAccountCannotCommitSuspendedFreshEnrollment() = runBlocking {
        var pending: Job? = null
        var foregroundStops = 0
        val account = RingAccountOwner { pending?.cancel(); if (pending != null) foregroundStops++ }
        account.change("A", "switch") { null }
        val owner = account.capture()
        val issuedRequest = CompletableDeferred<Unit>()
        val response = CompletableDeferred<Unit>()
        val persisted = mutableListOf<String>()
        val revoked = mutableListOf<String>()
        var bleStarts = 0
        pending = launch {
            account.enroll(owner, null,
                issue = { checkpoint, issued ->
                    checkpoint() // /me and CSRF have returned; POST is still in flight.
                    withContext(NonCancellable) {
                        issuedRequest.complete(Unit); response.await()
                        issued(grantA) // Like blocking HTTP, retain the issued secret before cancellation drops the reply.
                        checkpoint(); grantA
                    }
                }, state = { _, checkpoint -> checkpoint(); obj {} },
                persist = { grant, _ -> persisted += grant.user }, revoke = { revoked += it.user })
            bleStarts++
        }
        issuedRequest.await()
        assertFalse(account.change(null, "lapsed") { null }) // Existing background grants would survive this.
        assertFalse(account.change("B", "switch") { null }) // There is no stored grant to detect the old owner.
        response.complete(Unit); pending.join()
        assertEquals("B", account.user)
        assertTrue(pending.isCancelled)
        assertTrue(foregroundStops >= 1)
        assertTrue(persisted.isEmpty()); assertEquals(0, bleStarts)
        assertEquals(listOf("A"), revoked)
    }

    @Test fun cookieLapseCancelsForegroundWithoutRevokingEstablishedBackgroundGrant() = runBlocking {
        var pending: Job? = null
        val account = RingAccountOwner { pending?.cancel() }
        account.change("A", "switch") { "A" }
        val owner = account.capture()
        val request = CompletableDeferred<Unit>()
        val response = CompletableDeferred<Unit>()
        var persisted = false
        var revoked = false
        pending = launch {
            account.enroll(owner, grantA,
                issue = { _, _ -> error("Existing grant must not be re-issued") },
                state = { _, checkpoint -> withContext(NonCancellable) {
                    request.complete(Unit); response.await(); checkpoint(); obj {}
                } }, persist = { _, _ -> persisted = true }, revoke = { revoked = true })
        }
        request.await()
        assertFalse(account.change(null, "lapsed") { "A" })
        response.complete(Unit); pending.join()
        assertFalse(persisted); assertFalse(revoked)
        assertEquals("A", grantA.user)
        // Even a same-user login cannot resurrect the foreground request from the prior session.
        account.change("A", "switch") { "A" }
        try { account.requireCurrent(owner); fail("The old owner revision must remain invalid") }
        catch (_: CancellationException) { }
    }

    @Test fun switchReadsBindingCommittedAfterAnEarlierEmptyLookup() = runBlocking {
        val account = RingAccountOwner {}
        var stored: RingGrant? = null
        account.change("A", "switch") { stored?.user }
        val staleLookup = stored?.user
        assertNull(staleLookup)
        // The first enrollment finishes immediately before the account transition takes its monitor.
        account.enroll(account.capture(), null,
            issue = { checkpoint, issued -> checkpoint(); issued(grantA); grantA },
            state = { _, checkpoint -> checkpoint(); obj {} },
            persist = { grant, _ -> stored = grant }, revoke = { fail("A committed grant is not an unused grant") })
        var seenByTransition: String? = null
        assertTrue(account.change("B", "switch") { stored?.user.also { seenByTransition = it } })
        assertEquals("A", seenByTransition)
        assertEquals("B", account.user)
    }

    @Test fun explicitForgetFencesPendingEnrollmentAndRevokesItsUnusedGrant() = runBlocking {
        var pending: Job? = null
        val account = RingAccountOwner { pending?.cancel() }
        account.change("A", "switch") { null }
        val owner = account.capture()
        val stateRequest = CompletableDeferred<Unit>()
        val response = CompletableDeferred<Unit>()
        var stored: RingGrant? = null
        var bleStarted = false
        val revoked = mutableListOf<RingGrant>()
        pending = launch {
            account.enroll(owner, null,
                issue = { checkpoint, issued -> checkpoint(); issued(grantA); grantA },
                state = { _, checkpoint -> withContext(NonCancellable) {
                    stateRequest.complete(Unit); response.await(); checkpoint(); obj {}
                } }, persist = { grant, _ -> stored = grant }, revoke = { revoked += it })
            bleStarted = true
        }
        stateRequest.await()
        val old = account.invalidateBinding { stored.also { stored = null } }
        assertNull(old) // The pending issued token has not become the durable binding.
        response.complete(Unit); pending.join()
        assertTrue(pending.isCancelled); assertNull(stored); assertFalse(bleStarted)
        assertEquals(listOf(grantA), revoked)
    }

    @Test fun logoutCleanupSurvivesImmediateSameUserLoginWhileWaitingForExecutor() = runBlocking {
        val tasks = RingTaskLifetime(this)
        val account = RingAccountOwner(tasks::cancelForeground)
        val executor = Mutex(locked = true)
        var stored: RingGrant? = grantA
        var queueCount = 2
        val revoked = mutableListOf<RingGrant>()
        account.change("A", "switch") { stored?.user }
        tasks.launchForeground { awaitCancellation() }
        assertTrue(account.change(null, "logout") { stored?.user })
        val old = account.invalidateBinding { stored }
        val cleanup = tasks.launchCleanup {
            executor.withLock { stored = null; queueCount = 0 }
            old?.let { revoked += it }
        }
        assertTrue(tasks.busy)
        assertFalse(account.change("A", "switch") { stored?.user })
        assertFalse(cleanup.isCancelled)
        var repeatedCleanupRan = false
        val repeated = tasks.launchCleanup { assertNull(stored); repeatedCleanupRan = true }
        assertNotSame(cleanup, repeated)
        executor.unlock(); cleanup.join(); repeated.join()
        assertTrue(repeatedCleanupRan)
        assertNull(stored); assertEquals(0, queueCount)
        assertEquals(listOf(grantA), revoked)
        assertFalse(tasks.busy)
    }

    @Test fun secondForgetWhileRevocationWaitsClearsItsNewDurableFence() = runBlocking {
        val tasks = RingTaskLifetime(this)
        var pendingFence = true
        val locallyCleared = CompletableDeferred<Unit>()
        val revokeReply = CompletableDeferred<Unit>()
        val first = tasks.launchCleanup {
            pendingFence = false; locallyCleared.complete(Unit)
            revokeReply.await() // HTTP still owns the preceding cleanup job.
        }
        locallyCleared.await()
        assertFalse(pendingFence)
        pendingFence = true // A second explicit forget synchronously commits its fence.
        val second = tasks.launchCleanup { pendingFence = false }
        assertTrue(tasks.busy); assertTrue(pendingFence)
        tasks.cancelForeground()
        assertFalse(first.isCancelled); assertFalse(second.isCancelled)
        try { tasks.launchForeground { fail("Foreground cannot overtake required cleanup") }; fail("Expected busy") }
        catch (failure: RingFailure) { assertEquals("busy", failure.code) }
        revokeReply.complete(Unit); second.join()
        assertFalse(pendingFence); assertFalse(tasks.busy)
    }
}
