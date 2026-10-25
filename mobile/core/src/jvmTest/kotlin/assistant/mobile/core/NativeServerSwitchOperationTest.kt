package assistant.mobile.core

import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class NativeServerSwitchOperationTest {
    private val home = "https://old-server.invalid"
    private val target = NativeServerCheckResult.Compatible("https://new-server.invalid",
        "22222222-2222-4222-8222-222222222222", "Synthetic server")

    @Test
    fun selectingHomeIsNoOpBeforeFirmwareOrPrivateDataAreTouched() = runTest {
        val operation = NativeServerSwitchOperation({ home },
            prepare = { error("Same-server selection must not prepare erasure") },
            select = { error("Same-server selection must not write the record") },
            restart = { _, _ -> error("Same-server selection must not restart") })
        assertEquals(NativeServerSwitchResult.NoOp, operation.switchServer(target.copy(origin = home)))
    }

    @Test
    fun activeFirmwareUpdateReturnsTypedRefusalWithoutChangingServer() = runTest {
        var preparations = 0
        val operation = NativeServerSwitchOperation({ home },
            prepare = { preparations++; NativeServerSwitchPreparation.FirmwareUpdateActive },
            select = { error("Firmware update must preserve the server record") },
            restart = { _, _ -> error("Firmware update must preserve the process") })
        assertEquals(NativeServerSwitchResult.FirmwareUpdateActive, operation.switchServer(target))
        assertEquals(1, preparations)
    }

    @Test
    fun erasesBeforeEveryRevocationThenSelectsAndPassesInviteOnlyToRestart() = runTest {
        val steps = mutableListOf<String>()
        val invite = assertNotNull(parseAuthLink("kind=invite&token=synthetic-memory-invitation"))
        val operation = NativeServerSwitchOperation({ home },
            prepare = {
                steps += "prepare"
                NativeServerSwitchPreparation.Ready {
                    steps += "clear"
                    listOf<suspend () -> Unit>(
                        { assertEquals("clear", steps[1]); steps += "session-revoke" },
                        { assertEquals("clear", steps[1]); steps += "collector-revoke" },
                    )
                }
            },
            select = {
                assertSame(target, it)
                assertEquals(setOf("session-revoke", "collector-revoke"), steps.drop(2).toSet())
                steps += "select"
            },
            restart = { selected, link ->
                assertSame(target, selected)
                assertSame(invite, link)
                assertEquals("select", steps.last())
                steps += "restart"
            })
        assertEquals(NativeServerSwitchResult.Restarting, operation.switchServer(target, invite))
        assertEquals(listOf("prepare", "clear"), steps.take(2))
        assertEquals(listOf("select", "restart"), steps.takeLast(2))
    }

    @Test
    fun failedAndSlowRevocationsShareTenSecondBudgetAndCannotBlockRestart() = runTest {
        val started = mutableSetOf<String>()
        val completed = mutableSetOf<String>()
        var selectedAt: Long? = null
        var restartedAt: Long? = null
        val operation = NativeServerSwitchOperation({ home },
            prepare = {
                NativeServerSwitchPreparation.Ready {
                    listOf<suspend () -> Unit>(
                        { started += "failed"; throw IOException("Synthetic revocation failure") },
                        { started += "fast"; delay(9_000); completed += "fast" },
                        { started += "slow"; delay(60_000); completed += "slow" },
                        { started += "also-slow"; delay(60_000); completed += "also-slow" },
                    )
                }
            },
            select = { selectedAt = testScheduler.currentTime },
            restart = { _, _ -> restartedAt = testScheduler.currentTime })
        assertEquals(NativeServerSwitchResult.Restarting, operation.switchServer(target))
        assertEquals(setOf("failed", "fast", "slow", "also-slow"), started)
        assertEquals(setOf("fast"), completed)
        assertEquals(10_000L, selectedAt)
        assertEquals(10_000L, restartedAt)
    }

    @Test
    fun localErasureFailureReturnsTypedResultAndRetriesTheSamePreparation() = runTest {
        var preparations = 0
        var attempts = 0
        val steps = mutableListOf<String>()
        val operation = NativeServerSwitchOperation({ home },
            prepare = {
                check(++preparations == 1) { "The departing graph is already fenced" }
                NativeServerSwitchPreparation.Ready {
                    steps += "clear"
                    if (++attempts == 1) throw IOException("Synthetic storage failure")
                    listOf<suspend () -> Unit>({ steps += "revoke" })
                }
            },
            select = { steps += "select" },
            restart = { _, _ -> steps += "restart" })
        assertEquals(NativeServerSwitchResult.EraseFailed, operation.switchServer(target))
        assertEquals(listOf("clear"), steps)
        assertEquals(NativeServerSwitchResult.Restarting, operation.switchServer(target))
        assertEquals(listOf("clear", "clear", "revoke", "select", "restart"), steps)
        assertEquals(1, preparations)
        assertEquals(2, attempts)
    }

    @Test
    fun cancelingCallerDuringErasureOrRevocationStillCompletesSelectionAndRestart() = runTest {
        for (cancelDuringClear in listOf(true, false)) {
            val reached = CompletableDeferred<Unit>()
            val proceed = CompletableDeferred<Unit>()
            val steps = mutableListOf<String>()
            val operation = NativeServerSwitchOperation({ home },
                prepare = {
                    NativeServerSwitchPreparation.Ready {
                        steps += "clear"
                        if (cancelDuringClear) {
                            reached.complete(Unit)
                            proceed.await()
                        }
                        listOf<suspend () -> Unit>({
                            steps += "revoke"
                            if (!cancelDuringClear) {
                                reached.complete(Unit)
                                proceed.await()
                            }
                        })
                    }
                },
                select = { steps += "select" },
                restart = { _, _ -> steps += "restart" })
            val caller = launch { operation.switchServer(target) }
            reached.await()
            caller.cancel()
            proceed.complete(Unit)
            caller.join()
            assertTrue(caller.isCancelled)
            assertEquals(listOf("clear", "revoke", "select", "restart"), steps)
        }
    }

    @Test
    fun sameServerUsesCurrentHttpRouteWhileErasureCanSwitchBackToHomeNamespace() = runTest {
        var effectiveOrigin = home
        val steps = mutableListOf<String>()
        val operation = NativeServerSwitchOperation({ effectiveOrigin },
            prepare = {
                NativeServerSwitchPreparation.Ready {
                    steps += "clear"
                    emptyList()
                }
            },
            select = { steps += "select:${it.origin}" },
            restart = { selected, _ -> steps += "restart:${selected.origin}" })
        effectiveOrigin = target.origin
        assertEquals(NativeServerSwitchResult.NoOp, operation.switchServer(target))
        assertTrue(steps.isEmpty())
        assertEquals(NativeServerSwitchResult.Restarting, operation.switchServer(target.copy(origin = home)))
        assertEquals(listOf("clear", "select:$home", "restart:$home"), steps)
    }
}
