package assistant.mobile.platform.ring

import assistant.mobile.platform.R

import java.util.UUID
import kotlinx.serialization.json.*

enum class RingWorkoutCommand { START, PAUSE, RESUME, STOP }
data class RingRunningTransition(val kind: Kind, val elapsedRealtimeMs: Long,
    val elapsedRealtimeNanos: Long = elapsedRealtimeMs * 1_000_000) {
    init { require(elapsedRealtimeNanos / 1_000_000 == elapsedRealtimeMs) }
    enum class Kind { ENTER, EXIT }
}

data class RingWorkoutSession(
    val id: String, val binding: RingWorkoutBinding, val anchor: RingWorkoutClock,
    val startElapsedMs: Long, val trigger: String, val runEndElapsedMs: Long? = null,
    val recoveryEndElapsedMs: Long? = null, val stopReason: String? = null,
    val status: String = "completed", val lastObservedElapsedMs: Long = startElapsedMs,
    val lastObservedElapsedNanos: Long = lastObservedElapsedMs * 1_000_000,
    val heartRateRequested: Boolean = true, val heartRateLost: Boolean = false,
    val sportPlan: RingSportPlan? = null, val sportRequested: Boolean = false,
    val pausedAtElapsedMs: Long? = null, val pauses: List<RingWorkoutPause> = emptyList(),
    val gpsRequested: Boolean = true,
) {
    init {
        UUID.fromString(id)
        require(startElapsedMs >= 0 && trigger in setOf("manual", "automatic", "manual_takeover"))
        require(status in setOf("completed", "interrupted"))
        require(pausedAtElapsedMs == null || pausedAtElapsedMs >= startElapsedMs)
        require(pauses.size <= 300 && pauses.all { it.startElapsedMs >= startElapsedMs } &&
            pauses.zipWithNext().all { (a, b) -> a.endElapsedMs <= b.startElapsedMs })
        require(pausedAtElapsedMs == null || pausedAtElapsedMs >= (pauses.lastOrNull()?.endElapsedMs ?: startElapsedMs))
    }
}
data class RingWorkoutPause(val startElapsedMs: Long, val endElapsedMs: Long) {
    init { require(startElapsedMs >= 0 && endElapsedMs > startElapsedMs) }
}
/** Live timer and archive use the same phone monotonic intervals, including an unfinished pause. */
data class RingWorkoutDuration(val elapsedMs: Long, val pausedMs: Long) {
    val elapsedSeconds: Long get() = elapsedMs / 1000
    val activeSeconds: Long get() = maxOf(0, elapsedMs - pausedMs) / 1000
}
fun RingWorkoutSession.durationAt(nowElapsedMs: Long): RingWorkoutDuration {
    val end = maxOf(startElapsedMs, runEndElapsedMs ?: nowElapsedMs)
    val closed = pauses.sumOf { maxOf(0, minOf(it.endElapsedMs, end) - maxOf(it.startElapsedMs, startElapsedMs)) }
    val open = pausedAtElapsedMs?.let { maxOf(0, end - maxOf(it, startElapsedMs)) } ?: 0
    return RingWorkoutDuration(end - startElapsedMs, closed + open)
}

data class RingWorkoutState(
    val phase: String = "idle", val session: RingWorkoutSession? = null,
    val autoBlocked: Boolean = false, val lastTransitionElapsedMs: Long? = null,
    val transitionBootId: String? = null, val pendingCommand: RingWorkoutCommand? = null,
    val commandUnknown: Boolean = false,
    val lastTransitionNanos: Long? = null, val lastTransitionKinds: Set<RingRunningTransition.Kind> = emptySet(),
    val queuedEnter: RingWorkoutEnterCandidate? = null,
) {
    fun json(): JsonObject = obj {
        put("phase", phase); put("auto_blocked", autoBlocked); put("command_unknown", commandUnknown)
        put("pending_command", pendingCommand?.name?.lowercase()?.let(::JsonPrimitive) ?: JsonNull)
        put("workout_id", session?.id?.let(::JsonPrimitive) ?: JsonNull)
        put("trigger", session?.trigger?.let(::JsonPrimitive) ?: JsonNull)
        put("started_at", session?.let { JsonPrimitive(it.anchor.at(it.startElapsedMs).toString()) } ?: JsonNull)
        put("run_end_at", session?.let { s -> s.runEndElapsedMs?.let { JsonPrimitive(s.anchor.at(it).toString()) } } ?: JsonNull)
        put("recovery_end_at", session?.let { s -> s.recoveryEndElapsedMs?.let { JsonPrimitive(s.anchor.at(it).toString()) } } ?: JsonNull)
    }
}
data class RingWorkoutChange(val state: RingWorkoutState, val effect: RingWorkoutCommand? = null)
data class RingWorkoutEnterCandidate(val binding: RingWorkoutBinding, val elapsedMs: Long,
    val bootId: String, val sessionId: String, val elapsedNanos: Long = elapsedMs * 1_000_000,
    val recordHeartRate: Boolean = true, val recordGps: Boolean = true)

/** Whole batches reduce before an effect is returned. A delayed ENTER/EXIT cannot pulse the sensor. */
object RingWorkoutReducer {
    const val FRESH_ENTER_MS = 60_000L
    const val RECOVERY_MS = 180_000L
    private val active = setOf("starting", "recording", "grace", "recovery", "pausing", "paused", "resuming")

    fun transitions(state: RingWorkoutState, events: List<RingRunningTransition>, now: RingWorkoutClock,
        binding: RingWorkoutBinding, supported: Boolean, newSessionId: String = UUID.randomUUID().toString(),
        recordHeartRate: Boolean = true, recordGps: Boolean = true): RingWorkoutChange {
        var next = state
        if (state.transitionBootId != now.bootId) next = next.copy(lastTransitionElapsedMs = null, lastTransitionNanos = null,
            lastTransitionKinds = emptySet(), transitionBootId = now.bootId, queuedEnter = null)
        val existing = next.session?.takeIf { next.phase in active || next.phase in setOf("stopping", "unknown", "interrupted") }
        var newlyStarted = false
        var stopRequired = false
        var phoneCaptureFinished = false
        val previousTransition = next.lastTransitionNanos ?: next.lastTransitionElapsedMs?.times(1_000_000) ?: -1
        val seen = mutableSetOf<Pair<Long, RingRunningTransition.Kind>>()
        events.sortedWith(compareBy<RingRunningTransition> { it.elapsedRealtimeNanos }.thenBy { it.kind.ordinal }).forEach { event ->
            val time = event.elapsedRealtimeMs
            val nanos = event.elapsedRealtimeNanos
            if (time < 0 || nanos > now.elapsedRealtimeNanos || nanos < previousTransition ||
                nanos == previousTransition && event.kind in state.lastTransitionKinds || !seen.add(nanos to event.kind)) return@forEach
            // A fresh later ENTER cannot erase an EXIT whose grace expired before that ENTER.
            val deadline = next.session?.recoveryEndElapsedMs
            if (next.phase in setOf("grace", "recovery") && next.pendingCommand == null && deadline != null && deadline <= time) {
                val finished = finishCapture(next)
                next = finished.state
                stopRequired = finished.effect == RingWorkoutCommand.STOP
                phoneCaptureFinished = next.phase == "idle" && existing != null
            }
            next = next.copy(lastTransitionElapsedMs = time, lastTransitionNanos = nanos,
                lastTransitionKinds = if (next.lastTransitionNanos == nanos) next.lastTransitionKinds + event.kind else setOf(event.kind),
                transitionBootId = now.bootId)
            if (nanos == previousTransition && event.kind == RingRunningTransition.Kind.ENTER &&
                RingRunningTransition.Kind.EXIT in state.lastTransitionKinds) return@forEach
            when (event.kind) {
                RingRunningTransition.Kind.EXIT -> {
                    next = next.copy(autoBlocked = false,
                        queuedEnter = next.queuedEnter?.takeIf { it.elapsedMs > time })
                    val session = next.session
                    if (session != null && session.binding == binding && session.anchor.bootId == now.bootId &&
                        session.trigger == "automatic" && next.phase in setOf("starting", "recording", "grace")) {
                        next = next.copy(phase = if (next.pendingCommand == RingWorkoutCommand.START) "starting" else "grace",
                            session = session.copy(runEndElapsedMs = maxOf(time, session.startElapsedMs + 1),
                                recoveryEndElapsedMs = time + RECOVERY_MS, stopReason = "activity_exit"))
                    }
                }
                RingRunningTransition.Kind.ENTER -> {
                    if (now.elapsedRealtimeNanos - nanos > FRESH_ENTER_MS * 1_000_000 || next.autoBlocked) return@forEach
                    val session = next.session
                    if (session != null && session.binding == binding && session.anchor.bootId == now.bootId &&
                        session.trigger == "automatic" && next.phase in setOf("starting", "grace")) {
                        next = next.copy(phase = if (next.pendingCommand == RingWorkoutCommand.START) "starting" else "recording",
                            session = session.copy(runEndElapsedMs = null, recoveryEndElapsedMs = null, stopReason = null))
                    } else if (next.phase in setOf("idle", "unavailable") && next.pendingCommand == null && supported) {
                        if (phoneCaptureFinished) next = next.copy(queuedEnter = RingWorkoutEnterCandidate(binding, time,
                            now.bootId, newSessionId, nanos, recordHeartRate, recordGps))
                        else {
                            next = start(next, now, binding, newSessionId, time, "automatic", recordHeartRate, recordGps)
                            newlyStarted = true
                        }
                    } else if (next.phase in setOf("stopping", "unknown") && next.pendingCommand == RingWorkoutCommand.STOP &&
                        session?.binding == binding && supported) {
                        next = next.copy(queuedEnter = RingWorkoutEnterCandidate(binding, time, now.bootId, newSessionId, nanos, recordHeartRate, recordGps))
                    }
                }
            }
        }
        if (newlyStarted && existing == null) {
            if ((next.session?.recoveryEndElapsedMs ?: Long.MAX_VALUE) <= now.elapsedRealtimeMs) {
                return RingWorkoutChange(next.copy(phase = "idle", session = null, pendingCommand = null, commandUnknown = false))
            }
            return RingWorkoutChange(next, RingWorkoutCommand.START)
        }
        if (stopRequired) return RingWorkoutChange(next, RingWorkoutCommand.STOP)
        // Persist the old terminal before a later tick replaces its UUID with the queued phone-only episode.
        if (phoneCaptureFinished) return RingWorkoutChange(next)
        return tick(next, now)
    }

    fun manualStart(state: RingWorkoutState, now: RingWorkoutClock, binding: RingWorkoutBinding,
        supported: Boolean, newSessionId: String = UUID.randomUUID().toString(), recordHeartRate: Boolean = true,
        sportPlan: RingSportPlan? = null, sportRequested: Boolean = false, recordGps: Boolean = true): RingWorkoutChange {
        val session = state.session
        if (session != null && session.binding == binding && session.anchor.bootId == now.bootId && state.phase in active) {
            if (sportPlan != null && (session.sportPlan?.requestedId ?: 7) != sportPlan.requestedId)
                throw RingFailure("workout_busy", RingText.text(R.string.ring_workout_b4b34fb02477))
            if (state.phase in setOf("pausing", "paused", "resuming")) return RingWorkoutChange(state)
            return RingWorkoutChange(state.copy(phase = if (state.pendingCommand == RingWorkoutCommand.START) "starting" else "recording",
                session = session.copy(trigger = if (session.trigger == "automatic") "manual_takeover" else session.trigger,
                    runEndElapsedMs = null, recoveryEndElapsedMs = null, stopReason = null,
                    sportPlan = session.sportPlan ?: sportPlan,
                    sportRequested = session.sportRequested || session.sportPlan == null && session.heartRateRequested), queuedEnter = null))
        }
        if (state.pendingCommand != null || state.phase !in setOf("idle", "unavailable")) return RingWorkoutChange(state)
        if (!supported) return RingWorkoutChange(state.copy(phase = "unavailable"))
        val started = start(state, now, binding, newSessionId, now.elapsedRealtimeMs, "manual", recordHeartRate, recordGps)
        return RingWorkoutChange(started.copy(session = started.session?.copy(sportPlan = sportPlan, sportRequested = sportRequested)),
            if (recordHeartRate || recordGps) RingWorkoutCommand.START else null)
    }

    fun pause(state: RingWorkoutState, now: RingWorkoutClock): RingWorkoutChange {
        val session = state.session ?: return RingWorkoutChange(state)
        if (session.anchor.bootId != now.bootId || state.phase != "recording" || state.pendingCommand != null || state.commandUnknown)
            throw RingFailure("workout_busy", RingText.text(R.string.ring_workout_52b141b51054))
        if (session.pauses.size >= 300) throw RingFailure("workout_pause_limit", RingText.text(R.string.ring_workout_1d9248c039f7))
        val radio = session.sportRequested && !session.heartRateLost
        return RingWorkoutChange(state.copy(phase = if (radio) "pausing" else "paused", queuedEnter = null,
            pendingCommand = if (radio) RingWorkoutCommand.PAUSE else null,
            session = session.copy(trigger = if (session.trigger == "automatic") "manual_takeover" else session.trigger,
                pausedAtElapsedMs = maxOf(now.elapsedRealtimeMs, session.startElapsedMs),
                lastObservedElapsedMs = maxOf(session.lastObservedElapsedMs, now.elapsedRealtimeMs))), if (radio) RingWorkoutCommand.PAUSE else null)
    }
    fun resume(state: RingWorkoutState, now: RingWorkoutClock): RingWorkoutChange {
        val session = state.session ?: return RingWorkoutChange(state)
        if (session.anchor.bootId != now.bootId || state.phase != "paused" || state.pendingCommand != null || state.commandUnknown)
            throw RingFailure("workout_busy", RingText.text(R.string.ring_workout_418cf2b07958))
        val radio = session.sportRequested && !session.heartRateLost
        return RingWorkoutChange(state.copy(phase = if (radio) "resuming" else "recording",
            pendingCommand = if (radio) RingWorkoutCommand.RESUME else null,
            session = if (radio) session else closePause(session, now.elapsedRealtimeMs)), if (radio) RingWorkoutCommand.RESUME else null)
    }
    private fun closePause(session: RingWorkoutSession, end: Long): RingWorkoutSession {
        val start = session.pausedAtElapsedMs ?: return session
        return session.copy(pausedAtElapsedMs = null, pauses = if (end > start) session.pauses + RingWorkoutPause(start, end) else session.pauses)
    }
    /** End phone capture immediately without clearing or replaying an unresolved radio command. */
    fun stopPhoneWhileUnknown(state: RingWorkoutState, now: RingWorkoutClock): RingWorkoutChange {
        val session = state.session ?: return RingWorkoutChange(state)
        if (!state.commandUnknown) return manualStop(state, now)
        if (session.status == "interrupted" && session.runEndElapsedMs != null) return RingWorkoutChange(state.copy(autoBlocked = true, queuedEnter = null))
        val end = maxOf(now.elapsedRealtimeMs, session.startElapsedMs + 1)
        return RingWorkoutChange(state.copy(phase = "interrupted", pendingCommand = RingWorkoutCommand.STOP,
            commandUnknown = true, autoBlocked = true, queuedEnter = null,
            session = closePause(session, end).copy(status = "interrupted", heartRateLost = true,
                runEndElapsedMs = end, recoveryEndElapsedMs = end, stopReason = "manual_stop_pending_radio")))
    }

    fun manualStop(state: RingWorkoutState, now: RingWorkoutClock): RingWorkoutChange {
        if (state.commandUnknown) return stopPhoneWhileUnknown(state, now)
        if (state.pendingCommand == RingWorkoutCommand.PAUSE || state.pendingCommand == RingWorkoutCommand.RESUME)
            return stopPhoneWhileUnknown(state.copy(commandUnknown = true), now)
        val session = state.session ?: return RingWorkoutChange(state.copy(autoBlocked = true, queuedEnter = null))
        if (session.anchor.bootId != now.bootId) return recover(state.copy(autoBlocked = true), now)
        if (state.phase !in active && state.phase != "unknown") return RingWorkoutChange(state.copy(autoBlocked = true, queuedEnter = null))
        if (state.pendingCommand == RingWorkoutCommand.STOP) return RingWorkoutChange(state.copy(autoBlocked = true, queuedEnter = null))
        if (!session.heartRateRequested && !session.gpsRequested) {
            val end = maxOf(now.elapsedRealtimeMs, session.startElapsedMs + 1)
            return RingWorkoutChange(state.copy(phase = "idle", autoBlocked = true, queuedEnter = null,
                session = closePause(session, end).copy(runEndElapsedMs = end, recoveryEndElapsedMs = end, stopReason = "manual_stop")))
        }
        // A user stop never resubmits a START whose radio outcome is unknown.
        val uncertainStart = state.pendingCommand == RingWorkoutCommand.START
        return RingWorkoutChange(state.copy(autoBlocked = true, queuedEnter = null,
            phase = if (uncertainStart) "unknown" else "recovery", commandUnknown = state.commandUnknown || uncertainStart,
            session = closePause(session, now.elapsedRealtimeMs).copy(runEndElapsedMs = maxOf(now.elapsedRealtimeMs, session.startElapsedMs + 1),
                lastObservedElapsedMs = maxOf(session.lastObservedElapsedMs, now.elapsedRealtimeMs),
                recoveryEndElapsedMs = now.elapsedRealtimeMs + RECOVERY_MS, stopReason = "manual_stop")))
    }

    fun tick(state: RingWorkoutState, now: RingWorkoutClock): RingWorkoutChange {
        val candidate = state.queuedEnter
        if (state.phase == "idle" && state.pendingCommand == null && candidate != null) {
            if (candidate.bootId != now.bootId || state.autoBlocked || now.elapsedRealtimeNanos < candidate.elapsedNanos ||
                now.elapsedRealtimeNanos - candidate.elapsedNanos > FRESH_ENTER_MS * 1_000_000) return RingWorkoutChange(state.copy(queuedEnter = null))
            return RingWorkoutChange(start(state, now, candidate.binding, candidate.sessionId, candidate.elapsedMs, "automatic",
                candidate.recordHeartRate, candidate.recordGps), RingWorkoutCommand.START)
        }
        val session = state.session ?: return RingWorkoutChange(state)
        if (session.anchor.bootId != now.bootId && state.phase in active) return recover(state, now)
        if (state.phase == "recording" && !session.heartRateRequested)
            return RingWorkoutChange(state.copy(session = session.copy(lastObservedElapsedMs = maxOf(session.lastObservedElapsedMs, now.elapsedRealtimeMs))))
        if (state.phase == "paused") return RingWorkoutChange(state.copy(session = session.copy(
            lastObservedElapsedMs = maxOf(session.lastObservedElapsedMs, now.elapsedRealtimeMs))))
        if (state.phase !in setOf("grace", "recovery") || state.pendingCommand != null ||
            (session.recoveryEndElapsedMs ?: Long.MAX_VALUE) > now.elapsedRealtimeMs) return RingWorkoutChange(state)
        return finishCapture(state)
    }

    /** A failed radio stream does not change phone workout boundaries or stop GPS. No automatic reconnect/start. */
    fun radioLost(state: RingWorkoutState, unknown: Boolean): RingWorkoutChange {
        val session = state.session ?: return RingWorkoutChange(state)
        if (state.phase !in active && state.phase != "unknown") return RingWorkoutChange(state)
        val phase = if (session.pausedAtElapsedMs != null) "paused" else if (session.runEndElapsedMs == null) "recording"
            else if (session.stopReason == "activity_exit") "grace" else "recovery"
        return RingWorkoutChange(state.copy(phase = phase, pendingCommand = null,
            commandUnknown = unknown || state.commandUnknown, session = session.copy(heartRateLost = true)))
    }
    private fun finishCapture(state: RingWorkoutState): RingWorkoutChange {
        val session = state.session ?: return RingWorkoutChange(state)
        if (!session.heartRateRequested || session.heartRateLost && !state.commandUnknown)
            return RingWorkoutChange(state.copy(phase = "idle", pendingCommand = null, commandUnknown = false))
        if (session.heartRateLost) return RingWorkoutChange(state.copy(phase = "interrupted", pendingCommand = RingWorkoutCommand.STOP,
            commandUnknown = true, session = session.copy(status = "interrupted")))
        return RingWorkoutChange(state.copy(phase = "stopping", pendingCommand = RingWorkoutCommand.STOP,
            commandUnknown = false), RingWorkoutCommand.STOP)
    }

    /** null is a lost response. Neither null nor a rejected stop authorizes a repeated command. */
    fun commandResult(state: RingWorkoutState, command: RingWorkoutCommand, accepted: Boolean?, now: RingWorkoutClock): RingWorkoutChange {
        if (state.pendingCommand != command) return RingWorkoutChange(state)
        val session = state.session ?: return RingWorkoutChange(state)
        if (accepted == null || command == RingWorkoutCommand.STOP && !accepted) {
            return RingWorkoutChange(state.copy(phase = "unknown", commandUnknown = true))
        }
        if (command == RingWorkoutCommand.PAUSE) {
            val updated = if (accepted) session else closePause(session, now.elapsedRealtimeMs)
            return RingWorkoutChange(state.copy(phase = if (accepted) "paused" else "recording", pendingCommand = null, session = updated))
        }
        if (command == RingWorkoutCommand.RESUME) {
            return RingWorkoutChange(state.copy(phase = if (accepted) "recording" else "paused", pendingCommand = null,
                session = if (accepted) closePause(session, now.elapsedRealtimeMs) else session))
        }
        if (command == RingWorkoutCommand.START) {
            if (!accepted) return RingWorkoutChange(state.copy(phase = "idle", session = null, pendingCommand = null, commandUnknown = false))
            val phase = if (session.runEndElapsedMs == null) "recording" else if (session.stopReason == "activity_exit") "grace" else "recovery"
            return tick(state.copy(phase = phase, pendingCommand = null, commandUnknown = false), now)
        }
        if (session.status == "interrupted") return RingWorkoutChange(state.copy(phase = "idle", pendingCommand = null, commandUnknown = false))
        val end = maxOf(session.recoveryEndElapsedMs ?: session.startElapsedMs + 1, now.elapsedRealtimeMs)
        return RingWorkoutChange(state.copy(phase = "idle", pendingCommand = null, commandUnknown = false,
            session = session.copy(runEndElapsedMs = session.runEndElapsedMs ?: end, recoveryEndElapsedMs = end)))
    }

    /** Called once on process recreation; an interrupted radio command remains visible and fenced. */
    fun recover(state: RingWorkoutState, now: RingWorkoutClock): RingWorkoutChange {
        val session = state.session ?: return RingWorkoutChange(if (state.transitionBootId == now.bootId) state else state.copy(
            transitionBootId = now.bootId, lastTransitionElapsedMs = null, lastTransitionNanos = null,
            lastTransitionKinds = emptySet(), queuedEnter = null))
        if (session.status == "interrupted") return RingWorkoutChange(if (state.transitionBootId == now.bootId) state else state.copy(
            transitionBootId = now.bootId, lastTransitionElapsedMs = null, lastTransitionNanos = null,
            lastTransitionKinds = emptySet(), queuedEnter = null))
        if (state.phase !in active && state.phase !in setOf("stopping", "unknown")) return RingWorkoutChange(state)
        val last = maxOf(session.lastObservedElapsedMs, session.startElapsedMs + 1)
        val runEnd = minOf(session.runEndElapsedMs ?: last, last)
        return RingWorkoutChange(state.copy(phase = if (session.heartRateRequested) "interrupted" else "idle",
            pendingCommand = if (session.heartRateRequested) RingWorkoutCommand.STOP else null,
            commandUnknown = session.heartRateRequested, transitionBootId = now.bootId, lastTransitionElapsedMs = null,
            lastTransitionNanos = null, lastTransitionKinds = emptySet(), queuedEnter = null,
            session = closePause(session, last).copy(status = "interrupted", runEndElapsedMs = runEnd,
                recoveryEndElapsedMs = last, stopReason = if (session.anchor.bootId == now.bootId) "process_restart" else "phone_reboot")))
    }

    private fun start(state: RingWorkoutState, now: RingWorkoutClock, binding: RingWorkoutBinding,
        id: String, startElapsed: Long, trigger: String, recordHeartRate: Boolean = true, recordGps: Boolean = true) = state.copy(phase = if (recordHeartRate) "starting" else "recording",
        session = RingWorkoutSession(id, binding, now, startElapsed, trigger, heartRateRequested = recordHeartRate, sportRequested = recordHeartRate, gpsRequested = recordGps),
        pendingCommand = if (recordHeartRate) RingWorkoutCommand.START else null,
        commandUnknown = false, transitionBootId = now.bootId, queuedEnter = null)
}

data class RingWorkoutPoint(val elapsedRealtimeMs: Long, val bpm: Int, val quality: String,
    val elapsedRealtimeNanos: Long = elapsedRealtimeMs * 1_000_000) {
    init { require(elapsedRealtimeNanos / 1_000_000 == elapsedRealtimeMs); require(quality in setOf("valid", "invalid", "unknown")); require(bpm in if (quality == "valid") 20..250 else 0..300) }
}
data class RingWorkoutGap(val startElapsedMs: Long, val endElapsedMs: Long, val reason: String) {
    init { require(startElapsedMs >= 0 && endElapsedMs > startElapsedMs && reason.isNotBlank()) }
}
data class RingWorkoutChunk(val startElapsedMs: Long, val endElapsedMs: Long,
    val samples: List<RingWorkoutPoint>, val gaps: List<RingWorkoutGap>)

/** Boundaries are half-open and use real observation times; no interpolation creates extra points. */
object RingWorkoutChunker {
    const val MAX_DURATION_MS = 60_000L
    const val MAX_POINTS = 300
    fun split(points: List<RingWorkoutPoint>, gaps: List<RingWorkoutGap>): List<RingWorkoutChunk> {
        require(points.zipWithNext().all { (a, b) -> a.elapsedRealtimeNanos <= b.elapsedRealtimeNanos })
        require(gaps.zipWithNext().all { (a, b) -> a.endElapsedMs <= b.startElapsedMs })
        require(points.none { point -> gaps.any { point.elapsedRealtimeMs >= it.startElapsedMs && point.elapsedRealtimeMs < it.endElapsedMs } })
        val first = minOf(points.firstOrNull()?.elapsedRealtimeMs ?: Long.MAX_VALUE, gaps.firstOrNull()?.startElapsedMs ?: Long.MAX_VALUE)
        if (first == Long.MAX_VALUE) return emptyList()
        val last = maxOf(points.lastOrNull()?.elapsedRealtimeMs?.plus(1) ?: first, gaps.lastOrNull()?.endElapsedMs ?: first)
        val result = mutableListOf<RingWorkoutChunk>()
        var start = first
        while (start < last) {
            var end = minOf(start + MAX_DURATION_MS, last)
            val candidates = points.filter { it.elapsedRealtimeMs >= start && it.elapsedRealtimeMs < end }
            if (candidates.size > MAX_POINTS) {
                end = candidates[MAX_POINTS].elapsedRealtimeMs
                if (end <= start) throw RingFailure("workout_sample_overflow", RingText.text(R.string.ring_workout_c44f9958d071))
            }
            val overlapping = gaps.filter { it.startElapsedMs < end && it.endElapsedMs > start }
            if (overlapping.size > MAX_POINTS) end = minOf(end, overlapping[MAX_POINTS].startElapsedMs)
            val samples = points.filter { it.elapsedRealtimeMs >= start && it.elapsedRealtimeMs < end }
            val clipped = gaps.filter { it.startElapsedMs < end && it.endElapsedMs > start }
                .map { it.copy(startElapsedMs = maxOf(start, it.startElapsedMs), endElapsedMs = minOf(end, it.endElapsedMs)) }
            if (samples.isNotEmpty() || clipped.isNotEmpty()) result += RingWorkoutChunk(start, end, samples, clipped)
            start = end
        }
        return result
    }
}
