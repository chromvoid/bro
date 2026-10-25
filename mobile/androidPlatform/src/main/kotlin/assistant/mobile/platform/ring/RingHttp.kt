package assistant.mobile.platform.ring

import assistant.mobile.platform.R
import assistant.mobile.platform.collector.NativeCollectorFailure
import assistant.mobile.platform.collector.NativeCollectorGrant
import assistant.mobile.platform.collector.NativeCollectorHttp
import assistant.mobile.platform.collector.NativeCollectorReply
import kotlinx.serialization.json.JsonObject

/** Ring presentation and identity guards around the shared ring/WHOOP collector contract. */
class RingHttp(origin: String, profile: String = "qring-r20",
    serverOrigin: () -> String = { origin }) {
    private val collector = NativeCollectorHttp(origin, profile, serverOrigin)
    data class Reply(val status: Int, val value: JsonObject) {
        override fun toString() = "RingHttp.Reply(status=$status, body=redacted)"
    }
    suspend fun request(method: String, path: String, body: JsonObject? = null,
        token: String? = null, headers: Map<String, String> = emptyMap(),
        received: ((Reply) -> Unit)? = null, timeoutMs: Int? = null): Reply = translated {
        collector.request(method, path, body, token, headers,
            received = { received?.invoke(it.ringReply()) }, timeoutMs = timeoutMs).ringReply()
    }
    suspend fun enroll(bearer: String, user: String, installation: String, name: String,
        checkpoint: () -> Unit = {}, issued: (RingGrant) -> Unit = {}): RingGrant = translated {
        collector.requirePerson(bearer, user, checkpoint)
        RingGrant(collector.enroll(bearer, user, installation, name, checkpoint,
            issued = { issued(RingGrant(it.value)) }).value)
    }
    suspend fun state(grant: RingGrant, checkpoint: () -> Unit = {}, timeoutMs: Int? = null): JsonObject = translated {
        collector.state(NativeCollectorGrant(grant.value), checkpoint, timeoutMs)
    }
    suspend fun receipt(grant: RingGrant, key: String): Reply = translated {
        collector.receipt(NativeCollectorGrant(grant.value), key).ringReply()
    }
    suspend fun send(grant: RingGrant, batch: JsonObject): Reply = translated {
        collector.send(NativeCollectorGrant(grant.value), batch).ringReply()
    }
    suspend fun revoke(grant: RingGrant) = translated { collector.revoke(NativeCollectorGrant(grant.value)) }

    private fun NativeCollectorReply.ringReply() = Reply(status, value)
    private suspend fun <T> translated(block: suspend () -> T): T = try { block() }
    catch (failure: NativeCollectorFailure) {
        val message = when (failure.code) {
            "redirect_rejected" -> R.string.ring_native_659bbea325e9
            "response_too_large" -> R.string.ring_native_a1b170d6632b
            "not_authenticated" -> R.string.ring_native_315f2837398a
            "enrollment_failed" -> R.string.ring_native_c74043c9be06
            "grant_revoked" -> R.string.ring_native_b75b0deb2d0a
            "invalid_binding" -> R.string.ring_native_94653945ab40
            else -> R.string.ring_native_0dfe4f10ada2
        }
        throw RingFailure(failure.code, RingText.text(message))
    }
}
