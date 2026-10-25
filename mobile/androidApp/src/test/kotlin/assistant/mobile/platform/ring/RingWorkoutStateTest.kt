package assistant.mobile.platform.ring

import java.time.Instant
import org.junit.Assert.*
import org.junit.Test

class RingWorkoutStateTest {
    private val id = "c51b9d89-dc34-449a-aadb-de82efc63cbc"
    private val binding = RingWorkoutBinding("https://synthetic.invalid", "owner", "source", "collector",
        "00:11:22:33:44:55", 1, "Europe/Moscow")
    private fun clock(elapsed: Long, boot: String = "synthetic-boot") =
        RingWorkoutClock(Instant.parse("2026-10-08T10:00:00Z").plusMillis(elapsed), elapsed, boot)
    private fun enter(elapsed: Long) = RingRunningTransition(RingRunningTransition.Kind.ENTER, elapsed)
    private fun exit(elapsed: Long) = RingRunningTransition(RingRunningTransition.Kind.EXIT, elapsed)
    private fun recording(trigger: String = "automatic") = RingWorkoutState(phase = "recording",
        session = RingWorkoutSession(id, binding, clock(1_000), 1_000, trigger), transitionBootId = "synthetic-boot")

    @Test fun delayedExitUsesEventTimeAndEmitsOneStopForAnExistingSession() {
        val result = RingWorkoutReducer.transitions(recording(), listOf(exit(2_000)), clock(200_000), binding, true)
        assertEquals(RingWorkoutCommand.STOP, result.effect); assertEquals("stopping", result.state.phase)
        assertEquals(2_000L, result.state.session!!.runEndElapsedMs)
        assertEquals(182_000L, result.state.session!!.recoveryEndElapsedMs)
        assertNull(RingWorkoutReducer.tick(result.state, clock(210_000)).effect)
    }
    @Test fun unknownStartAndStopAreNeverRetriedByTicksOrAutomaticEvents() {
        val started = RingWorkoutReducer.manualStart(RingWorkoutState(), clock(1_000), binding, true, id)
        assertEquals(id, started.state.session!!.id); assertEquals(RingWorkoutCommand.START, started.effect)
        val unknown = RingWorkoutReducer.commandResult(started.state, RingWorkoutCommand.START, null, clock(2_000)).state
        assertEquals("unknown", unknown.phase); assertTrue(unknown.commandUnknown)
        assertNull(RingWorkoutReducer.manualStart(unknown, clock(3_000), binding, true).effect)
        assertNull(RingWorkoutReducer.manualStop(unknown, clock(4_000)).effect)
        assertNull(RingWorkoutReducer.transitions(unknown, listOf(exit(5_000), enter(6_000)), clock(6_000), binding, true).effect)
        val stopping = RingWorkoutReducer.tick(RingWorkoutReducer.manualStop(recording("manual"), clock(5_000)).state, clock(185_000)).state
        val lostStop = RingWorkoutReducer.commandResult(stopping, RingWorkoutCommand.STOP, null, clock(186_000)).state
        assertTrue(lostStop.commandUnknown); assertNull(RingWorkoutReducer.tick(lostStop, clock(300_000)).effect)
    }
    @Test fun processDeathAndRebootKeepPendingStopOnItsOriginalBinding() {
        val sampled = recording().copy(session = recording().session!!.copy(lastObservedElapsedMs = 9_000))
        for (boot in listOf("synthetic-boot", "new-boot")) {
            val recovered = RingWorkoutReducer.recover(sampled, clock(10_000, boot))
            assertNull(recovered.effect); assertEquals("interrupted", recovered.state.phase)
            assertEquals(RingWorkoutCommand.STOP, recovered.state.pendingCommand); assertTrue(recovered.state.commandUnknown)
            assertEquals(binding, recovered.state.session!!.binding); assertEquals("interrupted", recovered.state.session!!.status)
            assertEquals(9_000L, recovered.state.session!!.runEndElapsedMs)
            assertEquals(if (boot == "synthetic-boot") "process_restart" else "phone_reboot", recovered.state.session!!.stopReason)
        }
    }
    @Test fun chunkerBoundsActualPointsAndSplitsGapsAtEveryBoundary() {
        val points = (0 until 601).map { RingWorkoutPoint(it.toLong(), 100 + it % 20, "valid") }
        val chunks = RingWorkoutChunker.split(points, emptyList())
        assertEquals(listOf(300, 300, 1), chunks.map { it.samples.size })
        assertEquals(points, chunks.flatMap { it.samples })
        val gap = RingWorkoutGap(1_000, 181_000, "disconnected")
        val withGap = RingWorkoutChunker.split(listOf(RingWorkoutPoint(0, 72, "valid"), RingWorkoutPoint(181_000, 73, "valid")), listOf(gap))
        assertTrue(withGap.all { it.endElapsedMs - it.startElapsedMs <= 60_000 && it.samples.size <= 300 })
        assertEquals(180_000L, withGap.flatMap { it.gaps }.sumOf { it.endElapsedMs - it.startElapsedMs })
        assertEquals(2, withGap.sumOf { it.samples.size })
        assertTrue(withGap.all { chunk -> chunk.samples.all { it.elapsedRealtimeMs < chunk.endElapsedMs } })
    }
    @Test fun transitionIdentityIncludesNanosecondsAndKindAcrossRepeatedBatches() {
        val first = RingRunningTransition(RingRunningTransition.Kind.ENTER, 9_000, 9_000_000_100)
        val later = RingRunningTransition(RingRunningTransition.Kind.EXIT, 9_000, 9_000_000_200)
        val started = RingWorkoutReducer.transitions(RingWorkoutState(), listOf(first, first), clock(10_000), binding, true, id)
        assertEquals(RingWorkoutCommand.START, started.effect)
        val duplicate = RingWorkoutReducer.transitions(started.state, listOf(first), clock(10_000), binding, true)
        assertNull(duplicate.effect); assertNull(duplicate.state.session!!.runEndElapsedMs)
        val exit = RingWorkoutReducer.transitions(duplicate.state, listOf(first, later, later), clock(10_000), binding, true)
        assertNull(exit.effect); assertEquals(9_000_000_200L, exit.state.lastTransitionNanos)
        assertEquals(setOf(RingRunningTransition.Kind.EXIT), exit.state.lastTransitionKinds)
        assertNotNull(exit.state.session!!.runEndElapsedMs)
    }

    private val cycling = RingSportPlan(50, "outdoor_cycling", "cycling", 50, evidenceRef = "synthetic-catalog")
    @Test fun radioPauseAndResumeAreDurableCommandsAndUnknownDoesNotRetry() {
        val started = recording("manual").copy(session = recording("manual").session!!.copy(sportPlan = cycling, sportRequested = true))
        val pausing = RingWorkoutReducer.pause(started, clock(2_000))
        assertEquals(RingWorkoutCommand.PAUSE, pausing.effect); assertEquals("pausing", pausing.state.phase)
        val paused = RingWorkoutReducer.commandResult(pausing.state, RingWorkoutCommand.PAUSE, true, clock(2_100)).state
        val resuming = RingWorkoutReducer.resume(paused, clock(4_000))
        assertEquals(RingWorkoutCommand.RESUME, resuming.effect)
        val lost = RingWorkoutReducer.commandResult(resuming.state, RingWorkoutCommand.RESUME, null, clock(4_100)).state
        assertTrue(lost.commandUnknown); assertEquals(2_000L, lost.session!!.pausedAtElapsedMs)
        assertNull(RingWorkoutReducer.tick(lost, clock(10_000)).effect)
        val stop = RingWorkoutReducer.stopPhoneWhileUnknown(lost, clock(11_000))
        assertNull(stop.effect); assertEquals("interrupted", stop.state.phase)
        assertEquals(RingWorkoutCommand.STOP, stop.state.pendingCommand); assertTrue(stop.state.commandUnknown)
        assertEquals(11_000L, stop.state.session!!.runEndElapsedMs)
        assertEquals(listOf(RingWorkoutPause(2_000, 11_000)), stop.state.session!!.pauses)
        assertEquals(stop.state, RingWorkoutReducer.stopPhoneWhileUnknown(stop.state, clock(20_000)).state)
        assertNull(RingWorkoutReducer.manualStart(stop.state, clock(21_000), binding, true).effect)
    }
    @Test fun durationOnlySportNeedsNoServiceEffectAndRestartNeverClaimsContinuousRecording() {
        val started = RingWorkoutReducer.manualStart(RingWorkoutState(), clock(1_000), binding, true, id,
            recordHeartRate = false, sportPlan = cycling, recordGps = false)
        assertNull(started.effect); assertEquals("recording", started.state.phase)
        val observed = RingWorkoutReducer.tick(started.state, clock(5_000)).state
        for (boot in listOf("synthetic-boot", "new-boot")) {
            val recovered = RingWorkoutReducer.recover(observed, clock(9_000, boot))
            assertNull(recovered.effect); assertEquals("idle", recovered.state.phase)
            assertEquals("interrupted", recovered.state.session!!.status)
            assertEquals(5_000L, recovered.state.session!!.runEndElapsedMs)
            assertFalse(recovered.state.commandUnknown); assertNull(recovered.state.pendingCommand)
            assertEquals(cycling, recovered.state.session!!.sportPlan)
        }
        val stopped = RingWorkoutReducer.manualStop(observed, clock(7_000))
        assertNull(stopped.effect); assertEquals("idle", stopped.state.phase)
        assertEquals(7_000L, stopped.state.session!!.runEndElapsedMs)
        assertEquals(7_000L, stopped.state.session!!.recoveryEndElapsedMs)
        assertEquals(6L, stopped.state.session!!.durationAt(100_000).elapsedSeconds)
    }
}
