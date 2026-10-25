package assistant.mobile.core

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.coroutines.Continuation
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

private const val BUILD = "https://build.synthetic.invalid"
private const val PREVIOUS = "https://previous.synthetic.invalid"
private const val CUSTOM = "https://custom.synthetic.invalid"
private const val INSTANCE = "11111111-1111-4111-8111-111111111111"
private const val SELECTED_AT = "2026-10-22T12:00:00Z"

@OptIn(ExperimentalCoroutinesApi::class)
class NativeServerSelectionTest {
    @Test fun absentAndDefaultSelectionPreserveTheBuildMoveDecision() {
        val selection = selected(NativeServerKind.DEFAULT, PREVIOUS)
        for (oldSession in listOf(false, true)) {
            for (state in listOf(null, OriginMoveState.MOVED, OriginMoveState.REJECTED)) {
                val move = state?.let { OriginMoveRecord(PREVIOUS, BUILD, INSTANCE, it, SELECTED_AT) }
                val existing = coordinator(null, move, oldSession)
                assertEquals(existing.resolve(), coordinator(selection, move, oldSession).resolve())
                assertEquals(if (state == OriginMoveState.MOVED || (oldSession && state == null)) PREVIOUS else BUILD,
                    existing.homeOrigin)
            }
        }
    }

    @Test fun customIgnoresPreviousSessionsAndDefaultMoveRecords() = runTest {
        for (state in OriginMoveState.entries) {
            val records = object : OriginMoveRecordStore {
                override fun read(): OriginMoveRecord? = error("Custom selection must not read default moves")
                override fun write(record: OriginMoveRecord) = error("Custom selection must not write default moves")
            }
            val origins = OriginMoveCoordinator(BUILD, PREVIOUS, noSessions, records, noProbe,
                selection = selected())
            assertEquals(OriginMoveResolution(CUSTOM, CUSTOM, false), origins.checkPending())
            assertEquals(CUSTOM, origins.homeOrigin)
            assertEquals(CUSTOM, origins.serverOrigin(origins.homeOrigin))
            val storedMove = OriginMoveRecord(CUSTOM, BUILD, INSTANCE, state, SELECTED_AT)
            assertEquals(origins.resolve(), coordinator(selected(), storedMove).resolve())
        }
    }

    @Test fun customLoginLinksAcceptOnlyTheSelectedOrigin() {
        val origins = coordinator(selected())
        assertTrue(origins.acceptsOrigin(CUSTOM))
        assertFalse(origins.acceptsOrigin(BUILD))
        assertFalse(origins.acceptsOrigin(PREVIOUS))
        assertFalse(origins.acceptsOrigin(null))
    }

    @Test fun defaultAcceptsPreviousHostWhileItsMoveIsPending() {
        val pending = coordinator(null)
        assertTrue(pending.acceptsOrigin(BUILD))
        assertTrue(pending.acceptsOrigin(PREVIOUS))
        val moved = coordinator(null, OriginMoveRecord(PREVIOUS, BUILD, INSTANCE, OriginMoveState.MOVED, SELECTED_AT))
        assertTrue(moved.acceptsOrigin(BUILD))
        assertFalse(moved.acceptsOrigin(PREVIOUS))
        val noSession = coordinator(null, oldSession = false)
        assertFalse(noSession.acceptsOrigin(PREVIOUS))
    }

    @Test fun incomingJoinCancelsManualProbeAndKeepsItsTargetAndInviteAfterLateSuccessOrFailure() = runTest {
        val manual = NativeServerCheckResult.Compatible(PREVIOUS, INSTANCE, "Synthetic manual server")
        val incoming = manual.copy(origin = BUILD, name = "Synthetic incoming server")
        for (lateResult in listOf(manual, NativeServerCheckResult.Unreachable)) {
            var pending: Continuation<NativeServerCheckResult>? = null
            var oldJob: Job? = null
            val switched = mutableListOf<Pair<NativeServerCheckResult.Compatible, AuthLink?>>()
            val destination = assertNotNull(parseJoinLink(
                "server=https%3A%2F%2Fbuild.synthetic.invalid&kind=invite&token=synthetic-incoming-invite"))
            val controller = NativeServerSelectionController(this, CUSTOM, check = { origin ->
                if (origin == PREVIOUS) {
                    oldJob = currentCoroutineContext()[Job]
                    // This callback deliberately ignores cancellation and returns after the newer check.
                    suspendCoroutine { pending = it }
                } else {
                    assertEquals(BUILD, origin)
                    incoming
                }
            }, switchServer = { target, invite -> switched += target to invite; NativeServerSwitchResult.Restarting },
                onRedeem = { error("The incoming server must be checked first") }, onClose = {})
            controller.probe(PREVIOUS, assertNotNull(parseNativeInviteCode("synthetic-manual-invite")))
            runCurrent()
            controller.accept(destination)
            runCurrent()
            assertTrue(assertNotNull(oldJob).isCancelled)
            val checked = assertNotNull(controller.state.value.selected)
            assertSame(incoming, checked.target)
            assertSame(destination.link, checked.invite)
            assertNotNull(pending).resume(lateResult)
            runCurrent()
            assertSame(checked, controller.state.value.selected)
            assertNull(controller.state.value.error)
            assertFalse(controller.state.value.busy)
            controller.requestConnect(hasSession = false, hasCurrentData = true)
            assertSame(checked, controller.state.value.confirmation)
            controller.confirmSwitch()
            runCurrent()
            assertEquals(1, switched.size)
            assertSame(incoming, switched.single().first)
            assertSame(destination.link, switched.single().second)
        }
    }

    @Test fun selectedServerJoinCancelsManualProbeAndIgnoresItsLateSuccess() = runTest {
        var pending: Continuation<NativeServerCheckResult>? = null
        var oldJob: Job? = null
        var redeemed: AuthLink? = null
        val destination = assertNotNull(parseJoinLink(
            "server=https%3A%2F%2Fcustom.synthetic.invalid&kind=reset&token=synthetic-selected-invite"))
        val controller = NativeServerSelectionController(this, CUSTOM, check = {
            oldJob = currentCoroutineContext()[Job]
            suspendCoroutine { pending = it }
        }, switchServer = { _, _ -> error("The selected server must not switch") },
            onRedeem = { redeemed = it }, onClose = {})
        controller.probe(PREVIOUS)
        runCurrent()
        controller.accept(destination)
        assertTrue(assertNotNull(oldJob).isCancelled)
        assertSame(destination.link, redeemed)
        assertNotNull(pending).resume(NativeServerCheckResult.Compatible(PREVIOUS, INSTANCE, "Synthetic old server"))
        runCurrent()
        assertEquals(NativeServerSelectionState(), controller.state.value)
    }

    @Test fun replacementJoinDismissesStaleConfirmationAndKeepsItsOwnInvite() = runTest {
        val manual = NativeServerCheckResult.Compatible(PREVIOUS, INSTANCE, "Synthetic manual server")
        val incoming = manual.copy(origin = BUILD)
        val destination = assertNotNull(parseJoinLink(
            "server=https%3A%2F%2Fbuild.synthetic.invalid&kind=invite&token=synthetic-next-invite"))
        val switched = mutableListOf<Pair<NativeServerCheckResult.Compatible, AuthLink?>>()
        val controller = NativeServerSelectionController(this, CUSTOM,
            check = { if (it == PREVIOUS) manual else incoming },
            switchServer = { target, invite -> switched += target to invite; NativeServerSwitchResult.Restarting },
            onRedeem = {}, onClose = {})
        controller.probe(PREVIOUS, assertNotNull(parseNativeInviteCode("synthetic-previous-invite")))
        runCurrent()
        controller.requestConnect(hasSession = true, hasCurrentData = false)
        assertNotNull(controller.state.value.confirmation)
        controller.accept(destination)
        controller.confirmSwitch()
        runCurrent()
        assertTrue(switched.isEmpty())
        assertNull(controller.state.value.confirmation)
        controller.requestConnect(hasSession = true, hasCurrentData = false)
        controller.confirmSwitch()
        runCurrent()
        assertSame(incoming, switched.single().first)
        assertSame(destination.link, switched.single().second)
    }

    @Test fun sessionOrHomeAndArchiveDataRequiresConfirmationEvenAtTheDisplayedOrigin() = runTest {
        val target = NativeServerCheckResult.Compatible(BUILD, INSTANCE, "Synthetic build server")
        for ((hasSession, hasData, requiresDialog) in listOf(
            Triple(true, false, true),
            // The data flag includes the previous home during a pending move and archive-only data.
            Triple(false, true, true),
            Triple(false, false, false),
        )) {
            var switches = 0
            val controller = NativeServerSelectionController(this, BUILD, check = { target },
                switchServer = { _, _ -> switches++; NativeServerSwitchResult.Restarting }, onRedeem = {}, onClose = {})
            controller.probe(BUILD)
            runCurrent()
            controller.requestConnect(hasSession = hasSession, hasCurrentData = hasData)
            if (requiresDialog) {
                assertSame(controller.state.value.selected, assertNotNull(controller.state.value.confirmation))
                assertEquals(0, switches)
                controller.confirmSwitch()
            } else assertNull(controller.state.value.confirmation)
            runCurrent()
            assertEquals(1, switches)
        }
    }
}

private fun selected(kind: NativeServerKind = NativeServerKind.CUSTOM, origin: String = CUSTOM) =
    NativeServerSelection(kind, origin, INSTANCE, "Synthetic Bro", SELECTED_AT)

private val noSessions = object : OriginMoveSessions {
    override fun exists(instance: String, origin: String): Boolean = false
    override suspend fun load(instance: String, origin: String): StoredSession? = error("No session should be loaded")
}

private val noProbe = object : OriginMoveProbe {
    override suspend fun config(origin: String): OriginMoveConfigResult = error("No probe should run")
    override suspend fun me(origin: String, session: StoredSession): OriginMoveMeResult = error("No probe should run")
}

private fun coordinator(selection: NativeServerSelection?, move: OriginMoveRecord? = null, oldSession: Boolean = true) =
    OriginMoveCoordinator(BUILD, PREVIOUS, object : OriginMoveSessions {
        override fun exists(instance: String, origin: String): Boolean = oldSession && origin == PREVIOUS
        override suspend fun load(instance: String, origin: String): StoredSession? = error("No session should be loaded")
    }, object : OriginMoveRecordStore {
        override fun read(): OriginMoveRecord? = move
        override fun write(record: OriginMoveRecord) = error("No move should be written")
    }, noProbe, selection = selection)
