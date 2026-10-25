package assistant.mobile.core.dictation

import assistant.mobile.core.LiveConnection
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.time.TimeSource

/**
 * A foreground dictation with explicit replay of failed audio. Actions and PCM callbacks share
 * the UI dispatcher.
 * Socket callbacks may arrive on any thread and are dispatched to that same scope. A generation
 * owns every callback, so cancellation cannot apply a late final to this or a later draft.
 */
class DictationController(
    private val scope: CoroutineScope,
    private val readiness: DictationReadiness,
    private val source: DictationAudioSource,
    private val live: LiveConnection,
    private val currentDraft: () -> DictationDraftSnapshot,
    private val applyDraft: (String) -> Unit,
    private val telemetry: (DictationLatency) -> Unit = {},
    private val nowMillis: () -> Long = monotonicClock(),
    private val isAudioAvailable: () -> Boolean = { true },
    private val createSpeechDetector: () -> DictationSpeechDetector,
) {
    private val mutableState = MutableStateFlow(DictationState())
    val state: StateFlow<DictationState> = mutableState.asStateFlow()
    private var generation = 0L
    private var recording: DictationSession? = null
    private var startup: Job? = null
    private var timer: Job? = null
    private var recordingTicker: Job? = null
    private var recordingStartedAt: Long? = null
    private var draft: DictationDraft? = null
    private var audioBytes = 0L
    private val bufferedFrames = ArrayDeque<ByteArray>()
    private var bufferedBytes = 0L
    private var serverReady = false
    private var cachedReadiness: DictationReadinessResult? = null
    private var readinessRefresh: Deferred<DictationReadinessResult>? = null
    private var finishSent = false
    private var stopAt = 0L
    private var finishAt = 0L
    private var detector: DictationSpeechDetector? = null
    private var speechCheck: Job? = null
    private var speechChecked = false
    private var pendingFailure: Pair<DictationPhase, DictationProblem>? = null
    private val keptFrames = mutableListOf<ByteArray>()
    private var replaying = false
    private var replay: Job? = null
    private var closed = false

    /** Fetch ahead on chat entry and foregrounding; a tap reuses known configured readiness. */
    fun refreshReadiness() {
        if (!closed && readinessRefresh?.isActive != true) requestReadiness()
    }

    private fun requestReadiness(): Deferred<DictationReadinessResult> {
        readinessRefresh?.cancel()
        return scope.async {
            val result = try {
                readiness.check()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                DictationReadinessResult.Unavailable(DictationProblem.CONNECTION)
            }
            currentCoroutineContext().ensureActive()
            cachedReadiness = result
            result
        }.also { readinessRefresh = it }
    }

    fun start() {
        if (closed || state.value.active || !isAudioAvailable()) return
        discardAudio()
        val token = ++generation
        draft = DictationDraft(currentDraft())
        serverReady = false
        finishSent = false
        speechChecked = false
        replaying = false
        mutableState.value = DictationState(phase = DictationPhase.PERMISSION)
        val available = cachedReadiness
        startup = scope.launch {
            try {
                val preparation = source.prepare()
                // Cancellation already released this generation. A permission dialog can return
                // uncancellably after a new session has started; it must not release that capture.
                if (!owns(token, DictationPhase.PERMISSION)) return@launch
                when (preparation) {
                    DictationPreparationResult.NotAnswered -> {
                        end(DictationPhase.IDLE)
                        return@launch
                    }
                    is DictationPreparationResult.Unavailable -> {
                        end(DictationPhase.FAILED, preparation.problem)
                        return@launch
                    }
                    DictationPreparationResult.Ready -> Unit
                }
                recordingStartedAt = nowMillis()
                detector = runCatching { createSpeechDetector() }.getOrNull()
                move(DictationPhase.RECORDING)
                try {
                    source.start(
                        { bytes, level -> if (owns(token)) frame(bytes, level) },
                        { problem -> if (owns(token)) interrupt(problem) },
                    )
                } catch (_: Exception) {
                    end(DictationPhase.INTERRUPTED, DictationProblem.DEVICE)
                }
                if (!owns(token)) return@launch
                timeout(token, 35_000)
                recordingTicker = scope.launch {
                    while (owns(token, DictationPhase.RECORDING)) {
                        delay(1000)
                        if (owns(token, DictationPhase.RECORDING)) {
                            updateElapsedSeconds()
                            if (state.value.elapsedSeconds == DICTATION_MAX_SECONDS) reachedLimit()
                        }
                    }
                }
                if (available != DictationReadinessResult.Configured) {
                    val request = readinessRefresh?.takeIf { available == null && it.isActive } ?: requestReadiness()
                    launch readinessCheck@{
                        val checked = request.await()
                        if (!owns(token)) return@readinessCheck
                        if (checked is DictationReadinessResult.Unavailable) {
                            end(DictationPhase.FAILED, checked.problem)
                        }
                    }
                }
                val opened = live.startDictation(listener(token))
                if (!owns(token)) opened.cancel() else recording = opened
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                if (owns(token)) end(DictationPhase.FAILED, DictationProblem.CONNECTION)
            }
        }
    }

    /** Stops capture synchronously; finish follows the ordered buffered audio once ready. */
    fun stop() {
        if (state.value.phase != DictationPhase.RECORDING) return
        updateElapsedSeconds()
        stopAt = nowMillis()
        move(DictationPhase.FINALIZING)
        recordingTicker?.cancel()
        try { source.stop() } catch (_: Exception) { source.release() }
        if (state.value.phase != DictationPhase.FINALIZING) return
        if (audioBytes < DICTATION_BYTES_PER_SECOND * 700L / 1000) {
            end(DictationPhase.CANCELLED)
            return
        }
        checkSpeech()
    }

    private fun checkSpeech() {
        if (speechCheck?.isActive == true) return
        val token = generation
        val currentDetector = detector
        speechCheck = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            val heardSpeech = try {
                currentDetector?.finish() ?: true
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                true
            }
            if (generation != token || state.value.phase != DictationPhase.FINALIZING) return@launch
            speechChecked = true
            val failure = pendingFailure
            if (!heardSpeech) end(DictationPhase.CANCELLED)
            else if (failure != null) end(failure.first, failure.second)
            else if (serverReady) sendFinish()
        }
    }

    private fun sendFinish() {
        if (finishSent) return
        val activeSocket = recording
        if (activeSocket == null || activeSocket.queuedBytes > DICTATION_MAX_BACKLOG_BYTES) {
            end(DictationPhase.INTERRUPTED, DictationProblem.BACKLOG)
            return
        }
        finishSent = true
        finishAt = nowMillis()
        if (!runCatching { activeSocket.finish() }.getOrDefault(false)) {
            end(DictationPhase.INTERRUPTED, DictationProblem.CONNECTION)
            return
        }
        finishAt = nowMillis()
        if (state.value.phase == DictationPhase.FINALIZING) timeout(generation, 65_000)
    }

    fun cancel() {
        if (state.value.active || state.value.canRetry) end(DictationPhase.CANCELLED)
    }

    /** Retry the same immutable PCM without acquiring the microphone or re-running VAD. */
    fun retry() {
        if (closed || !state.value.canRetry || !isAudioAvailable()) return
        val token = ++generation
        draft = DictationDraft(currentDraft())
        serverReady = false
        finishSent = false
        speechChecked = true
        replaying = true
        stopAt = nowMillis()
        mutableState.value = DictationState(phase = DictationPhase.FINALIZING)
        timeout(token, 35_000)
        try {
            recording = live.startDictation(listener(token))
        } catch (_: Exception) {
            end(DictationPhase.FAILED, DictationProblem.CONNECTION)
        }
    }

    fun interrupt(problem: DictationProblem = DictationProblem.BACKGROUNDED) {
        if (state.value.active) end(DictationPhase.INTERRUPTED, problem)
    }

    /** Account changes erase both active work and review text from the previous owner. */
    fun reset() {
        release()
        readinessRefresh?.cancel()
        readinessRefresh = null
        cachedReadiness = null
        draft = null
        mutableState.value = DictationState()
    }

    fun close() {
        closed = true
        readinessRefresh?.cancel()
        if (state.value.active || state.value.canRetry) end(DictationPhase.CANCELLED) else release()
    }

    private fun listener(token: Long) = object : DictationSessionListener {
        override fun text(value: String) {
            val receivedAt = nowMillis()
            scope.launch { if (owns(token)) received(value, receivedAt) }
        }
        override fun failed() {
            scope.launch { if (owns(token)) connectionLost() }
        }
    }

    private fun connectionLost() = end(
        if (!serverReady) DictationPhase.FAILED else DictationPhase.INTERRUPTED,
        DictationProblem.CONNECTION,
    )

    private fun received(value: String, receivedAt: Long) {
        val message = runCatching { decodeDictationMessage(value) }.getOrElse {
            end(DictationPhase.FAILED, DictationProblem.SERVER)
            return
        }
        when (message.type) {
            "dictation.ready" -> {
                if (!isAudioAvailable()) {
                    end(DictationPhase.INTERRUPTED, DictationProblem.AUDIO_FOCUS)
                    return
                }
                if (serverReady || state.value.phase !in setOf(DictationPhase.RECORDING, DictationPhase.FINALIZING)) {
                    end(DictationPhase.FAILED, DictationProblem.SERVER)
                    return
                }
                if (message.sampleRate != DICTATION_SAMPLE_RATE) {
                    end(DictationPhase.FAILED, DictationProblem.SERVER)
                    return
                }
                serverReady = true
                flushBufferedFrames()
            }
            "dictation.partial" -> {
                if (state.value.phase !in setOf(DictationPhase.RECORDING, DictationPhase.FINALIZING) ||
                    message.text == null || message.text.length > 4000) {
                    end(DictationPhase.FAILED, DictationProblem.SERVER)
                    return
                }
                mutableState.value = state.value.copy(partial = message.text)
            }
            "dictation.final" -> {
                if (state.value.phase != DictationPhase.FINALIZING || !finishSent) {
                    end(DictationPhase.FAILED, DictationProblem.SERVER)
                    return
                }
                if (message.text.isNullOrBlank()) {
                    end(DictationPhase.FAILED, DictationProblem.NOT_RECOGNIZED)
                    return
                }
                if (message.text.length > 4000 || message.transcriptionId.isNullOrBlank()) {
                    end(DictationPhase.FAILED, DictationProblem.SERVER)
                    return
                }
                val result = checkNotNull(draft).final(currentDraft(), message.text)
                if (!result.preserved) applyDraft(result.text)
                val appliedAt = nowMillis()
                val finishDelay = (finishAt - stopAt).coerceAtLeast(0)
                val deliveryDelay = (appliedAt - receivedAt).coerceAtLeast(0)
                mutableState.value = state.value.copy(phase = DictationPhase.REVIEW, level = 0f,
                    finalText = message.text, transcriptionId = message.transcriptionId, draftPreserved = result.preserved)
                // Apply final immediately; releasing the recording leaves the live socket open.
                release()
                runCatching { telemetry(DictationLatency((appliedAt - stopAt).coerceAtLeast(0),
                    finishDelay, deliveryDelay, finishDelay + deliveryDelay)) }
            }
            "dictation.error" -> if (message.error == "empty_transcript") end(DictationPhase.FAILED, DictationProblem.NOT_RECOGNIZED)
                else end(DictationPhase.FAILED, dictationServerProblem(message.error))
            else -> end(DictationPhase.FAILED, DictationProblem.SERVER)
        }
    }

    private fun frame(bytes: ByteArray, level: Float) {
        val phase = state.value.phase
        if (phase != DictationPhase.RECORDING && !(phase == DictationPhase.FINALIZING && !finishSent)) return
        if (bytes.isEmpty() || bytes.size > DICTATION_MAX_FRAME_BYTES || bytes.size % 2 != 0) {
            end(DictationPhase.INTERRUPTED, DictationProblem.INVALID_AUDIO)
            return
        }
        val remaining = DICTATION_BYTES_PER_SECOND * DICTATION_MAX_SECONDS - audioBytes
        if (remaining <= 0) { reachedLimit(); return }
        val block = bytes.copyOf(minOf(bytes.size.toLong(), remaining).toInt())
        keptFrames.add(block)
        audioBytes += block.size
        try {
            detector?.accept(block)
        } catch (_: Exception) {
            runCatching { detector?.cancel() }
            detector = null // A failed detector must never discard possibly spoken audio.
        }
        if (serverReady) {
            if (!sendFrame(block)) return
        } else {
            if (bufferedBytes + block.size > DICTATION_MAX_BUFFER_BYTES) {
                end(DictationPhase.INTERRUPTED, DictationProblem.CONNECTION)
                return
            }
            bufferedFrames.addLast(block)
            bufferedBytes += block.size
        }
        mutableState.value = state.value.copy(level = if (level.isFinite()) level.coerceIn(0f, 1f) else 0f)
        if (audioBytes == DICTATION_BYTES_PER_SECOND * DICTATION_MAX_SECONDS.toLong()) reachedLimit()
    }

    private fun sendFrame(bytes: ByteArray): Boolean {
        val activeSocket = recording
        if (activeSocket == null) {
            end(DictationPhase.INTERRUPTED, DictationProblem.CONNECTION)
            return false
        }
        if (activeSocket.queuedBytes + bytes.size > DICTATION_MAX_BACKLOG_BYTES) {
            end(DictationPhase.INTERRUPTED, DictationProblem.BACKLOG)
            return false
        }
        if (!runCatching { activeSocket.sendPcm(bytes) }.getOrDefault(false)) {
            end(DictationPhase.INTERRUPTED, DictationProblem.CONNECTION)
            return false
        }
        if (activeSocket.queuedBytes > DICTATION_MAX_BACKLOG_BYTES) {
            end(DictationPhase.INTERRUPTED, DictationProblem.BACKLOG)
            return false
        }
        return true
    }

    private fun flushBufferedFrames() {
        timer?.cancel()
        if (replaying) {
            replay = scope.launch(start = CoroutineStart.UNDISPATCHED) {
                val token = generation
                timeout(token, 35_000)
                for (block in keptFrames) {
                    while (owns(token) && (recording?.queuedBytes ?: 0) + block.size > DICTATION_MAX_BACKLOG_BYTES) {
                        delay(20)
                    }
                    if (!owns(token) || !sendFrame(block)) return@launch
                    timeout(token, 35_000)
                }
                if (owns(token)) sendFinish()
            }
            return
        }
        while (bufferedFrames.isNotEmpty()) {
            val block = bufferedFrames.removeFirst()
            bufferedBytes -= block.size
            if (!sendFrame(block)) return
        }
        if (state.value.phase == DictationPhase.FINALIZING && speechChecked) sendFinish()
    }

    private fun reachedLimit() {
        mutableState.value = state.value.copy(reachedLimit = true)
        stop()
    }

    private fun updateElapsedSeconds() {
        val startedAt = recordingStartedAt ?: return
        val elapsed = ((nowMillis() - startedAt).coerceAtLeast(0) / 1000)
            .coerceAtMost(DICTATION_MAX_SECONDS.toLong()).toInt()
        mutableState.value = state.value.copy(elapsedSeconds = elapsed)
    }

    private fun timeout(token: Long, millis: Long) {
        timer?.cancel()
        timer = scope.launch {
            delay(millis)
            if (owns(token)) end(DictationPhase.INTERRUPTED, DictationProblem.TIMEOUT)
        }
    }

    private fun owns(token: Long, phase: DictationPhase? = null): Boolean =
        generation == token && state.value.active && pendingFailure == null &&
            (phase == null || state.value.phase == phase)

    private fun move(phase: DictationPhase) {
        mutableState.value = state.value.copy(phase = phase, level = 0f)
    }

    private fun end(phase: DictationPhase, problem: DictationProblem? = null) {
        val retryable = problem in setOf(
            DictationProblem.NOT_RECOGNIZED, DictationProblem.CONNECTION, DictationProblem.TIMEOUT,
            DictationProblem.PROVIDER_ERROR, DictationProblem.SERVER, DictationProblem.BUSY, DictationProblem.BACKLOG,
        )
        if (retryable && !speechChecked && state.value.phase in setOf(DictationPhase.RECORDING, DictationPhase.FINALIZING)) {
            if (audioBytes < DICTATION_BYTES_PER_SECOND * 700L / 1000) {
                end(DictationPhase.CANCELLED)
                return
            }
            // Freeze this recording and ignore late socket/capture callbacks while its VAD drains.
            pendingFailure = phase to checkNotNull(problem)
            move(DictationPhase.FINALIZING)
            timer?.cancel()
            recordingTicker?.cancel()
            startup?.cancel()
            runCatching { source.release() }
            val previous = recording
            recording = null
            runCatching { previous?.cancel() }
            checkSpeech()
            return
        }
        val canRetry = retryable && speechChecked && audioBytes >= DICTATION_BYTES_PER_SECOND * 700L / 1000
        mutableState.value = if (phase == DictationPhase.CANCELLED) DictationState(phase = phase)
            else state.value.copy(phase = phase, problem = problem, level = 0f, canRetry = canRetry)
        release(keepAudio = canRetry)
    }

    private fun release(keepAudio: Boolean = false) {
        generation++
        pendingFailure = null
        speechCheck?.cancel()
        speechCheck = null
        runCatching { detector?.cancel() }
        detector = null
        replay?.cancel()
        replay = null
        if (!keepAudio) discardAudio()
        timer?.cancel()
        timer = null
        recordingTicker?.cancel()
        recordingTicker = null
        recordingStartedAt = null
        startup?.cancel()
        startup = null
        bufferedFrames.clear()
        bufferedBytes = 0
        runCatching { source.release() }
        val ownedSocket = recording
        recording = null
        runCatching { ownedSocket?.cancel() }
    }

    private fun discardAudio() {
        keptFrames.clear()
        audioBytes = 0
    }
}

private fun monotonicClock(): () -> Long {
    val origin = TimeSource.Monotonic.markNow()
    return { origin.elapsedNow().inWholeMilliseconds }
}
