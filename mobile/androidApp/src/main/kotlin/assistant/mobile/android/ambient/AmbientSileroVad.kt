package assistant.mobile.android.ambient

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import assistant.mobile.android.BuildConfig
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.LongBuffer
import java.security.MessageDigest

/** The pinned offline Silero graph, with one continuous recurrent state per microphone session. */
class AmbientSileroVad private constructor(private val environment: OrtEnvironment, private val session: OrtSession) : AmbientVadDetector {
    private val input = floats(CONTEXT_SAMPLES + AmbientVadGate.FRAME_SAMPLES)
    private val state = floats(STATE_SAMPLES)
    private val sampleRate: LongBuffer = ByteBuffer.allocateDirect(8).order(ByteOrder.nativeOrder()).asLongBuffer().apply { put(0, 16_000) }
    private val context = FloatArray(CONTEXT_SAMPLES)
    private val runOptions = OrtSession.RunOptions()
    private val lifecycle = Any()
    private val tensors = linkedMapOf<String, OnnxTensor>()
    private var closed = false

    init {
        try {
            check(session.inputNames == setOf("input", "state", "sr"))
            check(session.outputNames.containsAll(setOf("output", "stateN")))
            tensors["input"] = OnnxTensor.createTensor(environment, input, longArrayOf(1, 576))
            tensors["state"] = OnnxTensor.createTensor(environment, state, longArrayOf(2, 1, 128))
            tensors["sr"] = OnnxTensor.createTensor(environment, sampleRate, longArrayOf())
            // Validate native loading, graph layout and execution before opening the microphone.
            probability(ByteArray(AmbientVadGate.FRAME_SAMPLES * 2))
            reset()
        } catch (failure: Throwable) {
            close()
            throw unavailable(failure)
        }
    }

    /** Infer exactly one padded 512-sample frame; callers retain only the actual supplied samples. */
    override fun probability(pcm: ByteArray): Float {
        if (closed || pcm.isEmpty() || pcm.size % 2 != 0 || pcm.size > AmbientVadGate.FRAME_SAMPLES * 2) throw AmbientProblem("vad_unavailable")
        try {
            for (index in context.indices) input.put(index, context[index])
            val count = pcm.size / 2
            for (index in 0 until AmbientVadGate.FRAME_SAMPLES) {
                val value = if (index < count) {
                    ((pcm[index * 2].toInt() and 255) or (pcm[index * 2 + 1].toInt() shl 8)).toShort().toFloat() / 32768f
                } else 0f
                input.put(CONTEXT_SAMPLES + index, value)
            }
            session.run(tensors, runOptions).use { result ->
                val prediction = (result.get("output").orElseThrow() as OnnxTensor).floatBuffer
                val nextState = (result.get("stateN").orElseThrow() as OnnxTensor).floatBuffer
                check(prediction.remaining() == 1 && nextState.remaining() == STATE_SAMPLES)
                val probability = prediction.get()
                check(probability.isFinite() && probability in 0f..1f)
                for (index in 0 until STATE_SAMPLES) {
                    val value = nextState.get()
                    check(value.isFinite())
                    state.put(index, value)
                }
                for (index in context.indices) context[index] = input.get(AmbientVadGate.FRAME_SAMPLES + index)
                return probability
            }
        } catch (failure: Throwable) { throw unavailable(failure) }
    }

    private fun reset() {
        context.fill(0f)
        for (index in 0 until STATE_SAMPLES) state.put(index, 0f)
    }

    override fun cancel() = synchronized(lifecycle) {
        if (!closed) try { runOptions.setTerminate(true) } catch (_: Exception) { }
    }

    override fun close() = synchronized(lifecycle) {
        if (closed) return@synchronized
        closed = true
        tensors.values.forEach { try { it.close() } catch (_: Exception) { } }
        try { session.close() } catch (_: Exception) { }
        try { runOptions.close() } catch (_: Exception) { }
        context.fill(0f)
        for (index in 0 until input.capacity()) input.put(index, 0f)
        for (index in 0 until state.capacity()) state.put(index, 0f)
    }

    companion object {
        private const val CONTEXT_SAMPLES = 64
        private const val STATE_SAMPLES = 256
        private const val MAX_MODEL_BYTES = 8 * 1024 * 1024

        fun fromAssets(context: Context): AmbientSileroVad = try {
            val bytes = context.assets.open(BuildConfig.AMBIENT_VAD_MODEL_FILE).use { source ->
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(65_536)
                while (true) {
                    val count = source.read(buffer)
                    if (count < 0) break
                    check(output.size() <= MAX_MODEL_BYTES - count)
                    output.write(buffer, 0, count)
                }
                output.toByteArray()
            }
            fromModel(bytes, BuildConfig.AMBIENT_VAD_MODEL_SHA256)
        } catch (failure: Throwable) { throw unavailable(failure) }

        /** Shared with host JNI smoke tests; no network path exists in the runtime. */
        fun fromModel(bytes: ByteArray, expectedSha256: String): AmbientSileroVad {
            var session: OrtSession? = null
            try {
                check(bytes.isNotEmpty() && bytes.size <= MAX_MODEL_BYTES && expectedSha256.matches(Regex("[0-9a-f]{64}")))
                val digest = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
                check(digest == expectedSha256)
                val environment = OrtEnvironment.getEnvironment()
                environment.setTelemetry(false)
                OrtSession.SessionOptions().use { options ->
                    options.setIntraOpNumThreads(1)
                    options.setInterOpNumThreads(1)
                    options.setExecutionMode(OrtSession.SessionOptions.ExecutionMode.SEQUENTIAL)
                    options.addConfigEntry("session.intra_op.allow_spinning", "0")
                    options.addConfigEntry("session.inter_op.allow_spinning", "0")
                    session = environment.createSession(bytes, options)
                }
                return AmbientSileroVad(environment, checkNotNull(session))
            } catch (failure: Throwable) {
                try { session?.close() } catch (_: Exception) { }
                throw unavailable(failure)
            }
        }

        private fun floats(size: Int): FloatBuffer = ByteBuffer.allocateDirect(size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
        private fun unavailable(failure: Throwable): AmbientProblem = AmbientProblem("vad_unavailable").apply { initCause(failure) }
    }
}
