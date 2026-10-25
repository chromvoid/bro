package assistant.mobile.core.speech

import assistant.mobile.core.SessionLease
import assistant.mobile.core.SessionManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/** One application player. Every public action runs on its owning UI dispatcher. No automatic retry. */
class SpeechPlaybackController(
    private val scope: CoroutineScope,
    private val sessions: SessionManager,
    private val transport: SpeechPlaybackTransport,
    private val sink: SpeechAudioSink,
    private val cache: SpeechAudioCache = SpeechAudioCache(),
    private val newPlaybackId: () -> String = ::speechPlaybackId,
    private val isAudioAvailable: () -> Boolean = { true },
) {
    private sealed interface Request {
        data class Turn(val id: String) : Request
        data class Preview(val connection: String, val voice: String, val model: String?) : Request
    }
    private val mutableState = MutableStateFlow(SpeechPlaybackState())
    val state: StateFlow<SpeechPlaybackState> = mutableState.asStateFlow()
    private var work: Job? = null
    private var generation = 0L
    private var connection: String? = null
    private var remembered: Request? = null
    private var closed = false
    private var mutation = 0L
    private val invalidated = mutableMapOf<String, Long>()
    private var observedLease = sessions.lease()
    private val accountWatch = scope.launch {
        sessions.leases.collect { lease ->
            if (lease !== observedLease) {
                observedLease = lease
                reset()
            }
        }
    }

    fun turn(turnId: String) = play(Request.Turn(turnId))

    fun preview(connectionId: String, voice: String, model: String? = null) =
        play(Request.Preview(connectionId, voice, model))

    /** A fresh click makes a fresh playback id. Unfinished paid generation is never resumed. */
    fun retry() { if (!state.value.active) remembered?.let(::play) }

    fun stop() {
        generation++
        work?.cancel()
        work = null
        connection = null
        runCatching { sink.stop() }
        mutableState.value = SpeechPlaybackState()
    }

    fun interrupt(problem: SpeechPlaybackProblem = SpeechPlaybackProblem.AUDIO_FOCUS) {
        if (!state.value.active) return
        val previous = state.value
        stop()
        mutableState.value = previous.copy(phase = SpeechPlaybackPhase.FAILED, problem = problem)
    }

    fun invalidateConnection(id: String) {
        invalidated[id] = ++mutation
        cache.invalidateConnection(id)
        if (connection == id) stop()
    }

    fun reset() {
        stop()
        remembered = null
        cache.clear()
        invalidated.clear()
    }

    fun close() {
        closed = true
        reset()
        accountWatch.cancel()
    }

    private fun play(request: Request) {
        if (closed || !isAudioAvailable()) return
        stop()
        remembered = request
        val key = when (request) { is Request.Turn -> "turn:${request.id}"; is Request.Preview -> "preview" }
        val lease = sessions.lease()
        if (lease == null) {
            cache.clear()
            mutableState.value = SpeechPlaybackState(key, SpeechPlaybackPhase.FAILED, SpeechPlaybackProblem.SESSION_EXPIRED)
            return
        }
        val token = generation
        val started = mutation
        mutableState.value = SpeechPlaybackState(key, SpeechPlaybackPhase.PREPARING)
        work = scope.launch {
            try {
                requireOwned(token, lease)
                if (!output { sink.prepare() }) throw SpeechPlaybackFailure(SpeechPlaybackProblem.PLAYBACK)
                requireOwned(token, lease)
                val plan = if (request is Request.Turn) transport.prepare(request.id, lease).also { it.validate() } else null
                requireOwned(token, lease)
                connection = plan?.connectionId ?: (request as Request.Preview).connection
                if ((connection?.let { invalidated[it] } ?: 0L) > started) throw SpeechPlaybackFailure(SpeechPlaybackProblem.CHANGED)
                val cached = plan?.let { descriptor ->
                    cache.get(descriptor.cacheKey, descriptor.connectionId)?.takeIf { it.size == descriptor.segments }
                }
                val count = plan?.segments ?: 1
                val playbackId = newPlaybackId()
                val staged = mutableListOf<SpeechAudioSegment>()
                var stagedBytes = 0L
                var cacheable = plan != null
                for (index in 0 until count) {
                    requireOwned(token, lease)
                    mutableState.value = SpeechPlaybackState(key, SpeechPlaybackPhase.LOADING, segment = index, segments = count)
                    var pcmStarted = false
                    suspend fun pcm(bytes: ByteArray, format: SpeechAudioFormat) {
                        requireOwned(token, lease)
                        if (!pcmStarted) { output { sink.startPcm(format) }; pcmStarted = true }
                        output { sink.writePcm(bytes) }
                        requireOwned(token, lease)
                        mutableState.value = mutableState.value.copy(phase = SpeechPlaybackPhase.PLAYING)
                    }
                    val audio = cached?.get(index) ?: when (request) {
                        is Request.Turn -> transport.segment(request.id, playbackId, checkNotNull(plan), index, lease, ::pcm)
                        is Request.Preview -> transport.preview(request.connection, request.voice, request.model, lease, ::pcm)
                    }
                    requireOwned(token, lease)
                    if (cached == null && cacheable) {
                        stagedBytes += audio.audio.size
                        if (stagedBytes > cache.maxBytes) { staged.clear(); cacheable = false } else staged += audio
                    }
                    if (audio.format.contentType == "audio/pcm") {
                        if (!pcmStarted) pcm(audio.audio, audio.format)
                        output { sink.finishPcm() }
                    } else {
                        mutableState.value = mutableState.value.copy(phase = SpeechPlaybackPhase.PLAYING)
                        output { sink.playEncoded(audio) }
                    }
                    requireOwned(token, lease)
                }
                if (cacheable && plan != null && staged.size == count) cache.put(plan.cacheKey, plan.connectionId, staged)
                mutableState.value = SpeechPlaybackState()
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                if (generation == token) {
                    val problem = (error as? SpeechPlaybackFailure)?.problem ?: SpeechPlaybackProblem.CONNECTION
                    if (problem == SpeechPlaybackProblem.SESSION_EXPIRED) cache.clear()
                    mutableState.value = mutableState.value.copy(phase = SpeechPlaybackPhase.FAILED, problem = problem)
                }
            } finally {
                if (generation == token) {
                    runCatching { sink.stop() }
                    connection = null
                    work = null
                }
            }
        }
    }

    private fun requireOwned(token: Long, lease: SessionLease) {
        if (generation != token) throw CancellationException("Playback stopped")
        if (!isAudioAvailable()) throw SpeechPlaybackFailure(SpeechPlaybackProblem.AUDIO_FOCUS)
        if (!sessions.isCurrent(lease)) throw SpeechPlaybackFailure(SpeechPlaybackProblem.SESSION_EXPIRED)
    }

    private suspend fun <T> output(operation: suspend () -> T): T = try { operation() }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { throw SpeechPlaybackFailure(SpeechPlaybackProblem.PLAYBACK) }
}

@OptIn(ExperimentalUuidApi::class)
private fun speechPlaybackId(): String = Uuid.random().toString()
