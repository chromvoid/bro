package assistant.mobile.android.ambient

import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Build
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.util.concurrent.TimeUnit

data class AmbientEncodedAudio(val format: AmbientAudioFormat, val bytes: ByteArray)

/** Used by the bounded file worker only. Each segment owns and drains a fresh native encoder. */
class AmbientEncoder private constructor(private val options: List<Option>) {
    private data class Option(val format: AmbientAudioFormat, val name: String)
    private var preferredIndex = 0

    fun encode(pcm: ByteArray, current: () -> Boolean): AmbientEncodedAudio {
        require(pcm.isNotEmpty() && pcm.size % 2 == 0 && pcm.size <= AmbientWav.SEGMENT_PCM_BYTES)
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        var failure: Exception? = null
        for (index in preferredIndex until options.size) {
            if (!current()) throw AmbientProblem("not_authenticated")
            try {
                val bytes = encode(options[index], pcm, current, deadline)
                preferredIndex = index // Do not repeatedly initialize an encoder that failed on this device.
                return AmbientEncodedAudio(options[index].format, bytes)
            } catch (problem: Exception) {
                if (!current()) throw AmbientProblem("not_authenticated")
                failure = problem
            }
        }
        throw AmbientProblem("capture_failed").apply { failure?.let(::initCause) }
    }

    private fun encode(option: Option, pcm: ByteArray, current: () -> Boolean, deadline: Long): ByteArray {
        val opus = option.format == AmbientAudioFormat.OGG_OPUS
        // A final padded frame gives Opus enough lookahead even for a very short partial segment.
        // Only packets needed for the captured samples survive the Ogg end trim below.
        val input = if (opus) pcm.copyOf(((pcm.size / 2 + OPUS_FRAME_SAMPLES - 1) / OPUS_FRAME_SAMPLES + 1) * OPUS_FRAME_SAMPLES * 2)
            else AmbientAacInput.prepare(pcm)
        val codec = MediaCodec.createByCodecName(option.name)
        var started = false
        try {
            codec.configure(mediaFormat(option.format, codec.codecInfo), null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec.start()
            started = true
            var offset = 0
            var inputEnded = false
            var outputEnded = false
            var outputFormat: MediaFormat? = null
            var outputBytes = 0
            val packets = ArrayList<ByteArray>()
            val info = MediaCodec.BufferInfo()
            while (!outputEnded) {
                if (!current()) throw AmbientProblem("not_authenticated")
                if (System.nanoTime() >= deadline) throw AmbientProblem("capture_failed")
                if (!inputEnded) {
                    val slot = codec.dequeueInputBuffer(1_000)
                    if (slot >= 0) {
                        val buffer = codec.getInputBuffer(slot) ?: throw AmbientProblem("capture_failed")
                        buffer.clear()
                        val desired = if (opus) OPUS_FRAME_SAMPLES * 2 else AAC_FRAME_SAMPLES * 2
                        val count = minOf(buffer.remaining(), desired, input.size - offset).let { it - it % 2 }
                        if (offset < input.size && count == 0) throw AmbientProblem("capture_failed")
                        if (count > 0) buffer.put(input, offset, count)
                        val pts = (offset / 2).toLong() * 1_000_000 / AmbientWav.SAMPLE_RATE
                        val eos = offset == input.size
                        codec.queueInputBuffer(slot, 0, count, pts, if (eos) MediaCodec.BUFFER_FLAG_END_OF_STREAM else 0)
                        offset += count
                        inputEnded = eos
                    }
                }
                val slot = codec.dequeueOutputBuffer(info, 1_000)
                when {
                    slot == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        if (outputFormat != null) throw AmbientProblem("capture_failed")
                        outputFormat = codec.outputFormat.also { validateOutputFormat(it, option.format) }
                    }
                    slot >= 0 -> {
                        try {
                            if (info.flags and MediaCodec.BUFFER_FLAG_PARTIAL_FRAME != 0) throw AmbientProblem("capture_failed")
                            if (info.size > 0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                                if (outputFormat == null || info.size > MAX_ACCESS_UNIT_BYTES || packets.size >= MAX_PACKETS) throw AmbientProblem("capture_failed")
                                val buffer = codec.getOutputBuffer(slot) ?: throw AmbientProblem("capture_failed")
                                if (info.offset < 0 || info.size > buffer.capacity() - info.offset) throw AmbientProblem("capture_failed")
                                outputBytes += info.size
                                if (outputBytes > AmbientAudioFormat.MAX_COMPRESSED_BYTES) throw AmbientProblem("capture_failed")
                                val packet = ByteArray(info.size)
                                buffer.position(info.offset)
                                buffer.limit(info.offset + info.size)
                                buffer.get(packet)
                                if (opus) AmbientOgg.packetSampleCount(packet)
                                packets.add(packet)
                            }
                            outputEnded = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                        } finally { codec.releaseOutputBuffer(slot, false) }
                    }
                }
            }
            val format = outputFormat ?: throw AmbientProblem("capture_failed")
            if (packets.isEmpty()) throw AmbientProblem("capture_failed")
            return if (opus) {
                val head = opusHead(format)
                val preSkip = (head[10].toInt() and 255) or ((head[11].toInt() and 255) shl 8)
                val needed = preSkip + pcm.size / 2L * 3
                var available = 0L
                val audible = packets.takeWhile { packet ->
                    if (available >= needed) false else { available += AmbientOgg.packetSampleCount(packet); true }
                }
                AmbientOgg.encode(head, audible, pcm.size / 2L)
            } else {
                // Older AOSP AAC wrappers signal EOS without draining FDK's priming delay.
                // The padded input pushes the entire source through that delay. Newer
                // wrappers also flush it; retain only the source, priming and final frame.
                val retained = AmbientAacInput.retainedAccessUnits(pcm.size / 2L)
                if (packets.size < retained) throw AmbientProblem("capture_failed")
                val out = ByteArrayOutputStream()
                packets.take(retained).forEach { packet ->
                    val frame = AmbientAdts.frame(packet)
                    if (out.size() + frame.size > AmbientAudioFormat.MAX_COMPRESSED_BYTES) throw AmbientProblem("capture_failed")
                    out.write(frame)
                }
                out.toByteArray().also { AmbientAdts.validate(it) }
            }
        } finally {
            if (started) try { codec.stop() } catch (_: Exception) { }
            codec.release()
        }
    }

    companion object {
        private const val OPUS_FRAME_SAMPLES = 320
        private const val AAC_FRAME_SAMPLES = 1024
        private const val MAX_ACCESS_UNIT_BYTES = 8191 - 7
        private const val MAX_PACKETS = 4096

        /** Capability discovery and configure happen before microphone permission starts recording. */
        fun discover(accepted: Set<AmbientAudioFormat>): AmbientEncoder = try {
            val codecs = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.filter { it.isEncoder }
            val options = ArrayList<Option>()
            val preferred = AmbientEncodingPolicy.preferred(accepted, accepted, Build.VERSION.SDK_INT)
            for (format in preferred) {
                val mime = codecMime(format)
                val found = codecs.filter { codec ->
                    try { mime in codec.supportedTypes && codec.getCapabilitiesForType(mime).isFormatSupported(mediaFormat(format, codec)) }
                    catch (_: Exception) { false }
                }.sortedBy { if (it.name.startsWith("c2.android.") || it.name.startsWith("OMX.google.")) 0 else 1 }
                    .firstOrNull { codec ->
                        var native: MediaCodec? = null
                        try {
                            native = MediaCodec.createByCodecName(codec.name)
                            native.configure(mediaFormat(format, codec), null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                            validateOutputFormat(native.outputFormat, format)
                            true
                        } catch (_: Exception) { false }
                        finally { native?.release() }
                    }
                if (found != null) options.add(Option(format, found.name))
            }
            if (options.isEmpty()) throw AmbientProblem("codec_unavailable")
            AmbientEncoder(options)
        } catch (failure: Exception) {
            throw AmbientProblem("codec_unavailable").apply { initCause(failure) }
        }

        private fun codecMime(format: AmbientAudioFormat): String = when (format) {
            AmbientAudioFormat.OGG_OPUS -> MediaFormat.MIMETYPE_AUDIO_OPUS
            AmbientAudioFormat.AAC_ADTS -> MediaFormat.MIMETYPE_AUDIO_AAC
            AmbientAudioFormat.WAV -> throw IllegalArgumentException("New PCM batches are not allowed")
        }

        private fun mediaFormat(format: AmbientAudioFormat, info: MediaCodecInfo): MediaFormat =
            MediaFormat.createAudioFormat(codecMime(format), AmbientWav.SAMPLE_RATE, 1).apply {
                setInteger(MediaFormat.KEY_BIT_RATE, if (format == AmbientAudioFormat.OGG_OPUS) 24_000 else 32_000)
                setInteger(MediaFormat.KEY_PCM_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, AAC_FRAME_SAMPLES * 2)
                if (format == AmbientAudioFormat.AAC_ADTS) setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                if (format == AmbientAudioFormat.OGG_OPUS) {
                    val capabilities = info.getCapabilitiesForType(codecMime(format)).encoderCapabilities
                    val range = capabilities?.complexityRange
                    if (range != null) setInteger(MediaFormat.KEY_COMPLEXITY, 5.coerceIn(range.lower, range.upper))
                    val mode = when {
                        capabilities?.isBitrateModeSupported(MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR) == true -> MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR
                        capabilities?.isBitrateModeSupported(MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR) == true -> MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR
                        else -> null
                    }
                    if (mode != null) setInteger(MediaFormat.KEY_BITRATE_MODE, mode)
                }
                if (Build.VERSION.SDK_INT >= 35) {
                    // Unset batch size means one AU per output. Even size 1 requests the
                    // framework's asynchronous-only batching path, incompatible with dequeue.
                    setFeatureEnabled(MediaCodecInfo.CodecCapabilities.FEATURE_MultipleFrames, false)
                }
            }

        private fun validateOutputFormat(format: MediaFormat, wanted: AmbientAudioFormat) {
            if (format.getString(MediaFormat.KEY_MIME) != codecMime(wanted) ||
                format.getInteger(MediaFormat.KEY_SAMPLE_RATE) != AmbientWav.SAMPLE_RATE ||
                format.getInteger(MediaFormat.KEY_CHANNEL_COUNT) != 1) throw AmbientProblem("capture_failed")
            if (wanted == AmbientAudioFormat.AAC_ADTS) {
                if (format.containsKey(MediaFormat.KEY_AAC_PROFILE) && format.getInteger(MediaFormat.KEY_AAC_PROFILE) != MediaCodecInfo.CodecProfileLevel.AACObjectLC) throw AmbientProblem("capture_failed")
                format.getByteBuffer("csd-0")?.let { data ->
                    val asc = copy(data)
                    if (asc.size != 2 || asc[0] != 0x14.toByte() || asc[1] != 0x08.toByte()) throw AmbientProblem("capture_failed")
                }
            }
        }

        private fun opusHead(format: MediaFormat): ByteArray {
            val config = format.getByteBuffer("csd-0")?.let(::copy) ?: throw AmbientProblem("capture_failed")
            return AmbientOpusConfig.normalize(config, format.getByteBuffer("csd-1")?.let(::copy))
        }

        private fun copy(buffer: ByteBuffer): ByteArray = buffer.duplicate().let { data -> ByteArray(data.remaining()).also(data::get) }
    }
}
