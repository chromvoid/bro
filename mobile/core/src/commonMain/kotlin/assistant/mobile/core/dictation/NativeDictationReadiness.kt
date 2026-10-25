package assistant.mobile.core.dictation

import assistant.mobile.core.HttpOutcome
import assistant.mobile.core.NativeHttpClient
import assistant.mobile.core.NativeJson
import assistant.mobile.core.SessionManager
import kotlinx.serialization.Serializable

/** Uses the same authenticated native HTTP client and current lease as the chat. */
class NativeDictationReadiness(
    private val http: NativeHttpClient,
    private val sessions: SessionManager,
) : DictationReadiness {
    override suspend fun check(): DictationReadinessResult {
        val lease = sessions.lease() ?: return unavailable(DictationProblem.SESSION_EXPIRED)
        return when (val result = http.request("GET", "/v1/me/speech/readiness?capability=asr", lease = lease)) {
            is HttpOutcome.Success -> runCatching {
                val response = NativeJson.decodeFromString<ReadinessReply>(result.body.decodeToString(throwOnInvalidSequence = true))
                when (response.asr.state) {
                    "configured" -> DictationReadinessResult.Configured
                    "unavailable" -> unavailable(response.asr.code?.let(::dictationServerProblem) ?: DictationProblem.NOT_CONFIGURED)
                    else -> unavailable(DictationProblem.SERVER)
                }
            }.getOrElse { unavailable(DictationProblem.SERVER) }
            HttpOutcome.Unauthorized, HttpOutcome.StaleSession -> unavailable(DictationProblem.SESSION_EXPIRED)
            HttpOutcome.Forbidden -> unavailable(DictationProblem.NO_ACCESS)
            is HttpOutcome.RateLimited -> unavailable(DictationProblem.APPLICATION_LIMIT)
            HttpOutcome.NetworkFailure -> unavailable(DictationProblem.CONNECTION)
            else -> unavailable(DictationProblem.SERVER)
        }
    }

    private fun unavailable(problem: DictationProblem) = DictationReadinessResult.Unavailable(problem)
}

@Serializable
private data class ReadinessReply(val asr: AsrReadiness)

@Serializable
private data class AsrReadiness(val state: String, val code: String? = null)
