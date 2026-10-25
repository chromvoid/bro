package assistant.mobile.core.speech

import assistant.mobile.core.HttpOutcome
import assistant.mobile.core.NativeHttpClient
import assistant.mobile.core.NativeJson
import assistant.mobile.core.SessionLease
import assistant.mobile.core.SessionManager
import io.ktor.http.encodeURLPathPart
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Direct online requests: neither speech bodies nor audio enter the submission journal. */
class NativeSpeechPlaybackTransport(
    private val http: NativeHttpClient,
    private val sessions: SessionManager,
) : SpeechPlaybackTransport {
    override suspend fun prepare(turnId: String, lease: SessionLease): SpeechPlaybackPlan {
        requireCurrent(lease)
        val result = http.request("POST", turnPath(turnId) + "/prepare", "{}".encodeToByteArray(), lease)
        if (result !is HttpOutcome.Success) throw playbackOutcome(result)
        if (result.status != 200) throw SpeechPlaybackFailure(SpeechPlaybackProblem.INVALID_RESPONSE)
        val plan = try { NativeJson.decodeFromString<SpeechPlaybackPlan>(result.body.decodeToString()) }
            catch (_: Exception) { throw SpeechPlaybackFailure(SpeechPlaybackProblem.INVALID_RESPONSE) }
        plan.validate()
        requireCurrent(lease)
        return plan
    }

    override suspend fun segment(
        turnId: String, playbackId: String, plan: SpeechPlaybackPlan, segment: Int, lease: SessionLease,
        onPcm: suspend (ByteArray, SpeechAudioFormat) -> Unit,
    ): SpeechAudioSegment {
        plan.validate()
        if (segment !in 0 until plan.segments) throw SpeechPlaybackFailure(SpeechPlaybackProblem.INVALID_RESPONSE)
        return audio(turnPath(turnId), buildJsonObject {
            put("playback_id", playbackId)
            put("segment", segment)
            put("cache_key", plan.cacheKey)
        }.toString().encodeToByteArray(), lease, SpeechSegmentExpectation(segment, plan.segments, plan.cacheKey), onPcm)
    }

    override suspend fun preview(
        connectionId: String, voice: String, model: String?, lease: SessionLease,
        onPcm: suspend (ByteArray, SpeechAudioFormat) -> Unit,
    ): SpeechAudioSegment = audio("/v1/me/speech/preview", buildJsonObject {
        put("connection_id", connectionId)
        put("voice", voice)
        if (!model.isNullOrBlank()) put("tts_model", model)
    }.toString().encodeToByteArray(), lease, SpeechSegmentExpectation(0, 1, null), onPcm)

    private suspend fun audio(
        path: String, body: ByteArray, lease: SessionLease, expected: SpeechSegmentExpectation,
        onPcm: suspend (ByteArray, SpeechAudioFormat) -> Unit,
    ): SpeechAudioSegment {
        requireCurrent(lease)
        var audio: SpeechAudioSegment? = null
        try {
            val outcome = http.stream(path, lease, mapOf("Accept" to "application/x-ndjson"), method = "POST", body = body) { status, headers, channel ->
                audio = SpeechNdjsonParser(expected).read(status, headers, channel) { bytes, format ->
                    requireCurrent(lease)
                    onPcm(bytes, format)
                    requireCurrent(lease)
                }
            }
            if (outcome !is HttpOutcome.Success) throw playbackOutcome(outcome)
            requireCurrent(lease)
            return audio ?: throw SpeechPlaybackFailure(SpeechPlaybackProblem.INVALID_RESPONSE)
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: SpeechPlaybackFailure) { throw failure }
        catch (_: Exception) { throw SpeechPlaybackFailure(SpeechPlaybackProblem.CONNECTION) }
    }

    private fun requireCurrent(lease: SessionLease) {
        if (!sessions.isCurrent(lease)) throw SpeechPlaybackFailure(SpeechPlaybackProblem.SESSION_EXPIRED)
    }
}

private fun turnPath(id: String): String {
    if (id.isBlank() || id.length > 200 || id.any(Char::isISOControl))
        throw SpeechPlaybackFailure(SpeechPlaybackProblem.INVALID_RESPONSE)
    return "/v1/conversation/turns/${id.encodeURLPathPart()}/speech"
}

private fun playbackOutcome(outcome: HttpOutcome): SpeechPlaybackFailure = SpeechPlaybackFailure(when (outcome) {
    HttpOutcome.Unauthorized, HttpOutcome.StaleSession -> SpeechPlaybackProblem.SESSION_EXPIRED
    HttpOutcome.Forbidden -> SpeechPlaybackProblem.NO_ACCESS
    is HttpOutcome.RateLimited -> SpeechPlaybackProblem.APPLICATION_LIMIT
    is HttpOutcome.Conflict -> SpeechPlaybackProblem.CHANGED
    HttpOutcome.NetworkFailure -> SpeechPlaybackProblem.CONNECTION
    is HttpOutcome.Failure -> {
        val code = runCatching { NativeJson.decodeFromString<SpeechResponseError>(outcome.body.decodeToString()).error }.getOrNull()
        speechPlaybackProblem(code)
    }
    else -> SpeechPlaybackProblem.INVALID_RESPONSE
})

@Serializable
private data class SpeechResponseError(val error: String? = null) {
    override fun toString(): String = "SpeechResponseError(redacted)"
}
