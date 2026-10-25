package assistant.mobile.android.whoop

import assistant.mobile.core.SessionLease
import assistant.mobile.platform.collector.NativeCollectorFailure
import assistant.mobile.platform.collector.NativeCollectorGrant
import assistant.mobile.platform.collector.NativeCollectorHttp
import assistant.mobile.platform.collector.NativeCollectorReply
import kotlinx.serialization.json.*

/** A separate authority from a person's native session; never serialized into UI state. */
internal data class WhoopGrant(val value: JsonObject) {
    val user get() = requireNotNull(value.string("user_id"))
    val source get() = requireNotNull(value.string("source_id"))
    val token get() = requireNotNull(value.string("token"))
    val identity get() = requireNotNull(value.string("collector_id"))
    override fun toString() = "WhoopGrant(redacted)"
}

internal class WhoopFailure(val code: String, override val message: String = code) : Exception(message)
internal data class WhoopReply(val status: Int, val value: JsonObject) {
    override fun toString() = "WhoopReply(status=$status, body=redacted)"
}

internal interface WhoopHttp {
    suspend fun enroll(lease: SessionLease, installation: String, name: String,
        checkpoint: () -> Unit, issued: (WhoopGrant) -> Unit): WhoopGrant
    suspend fun state(grant: WhoopGrant, checkpoint: () -> Unit = {}): JsonObject
    suspend fun receipt(grant: WhoopGrant, key: String): WhoopReply
    suspend fun send(grant: WhoopGrant, batch: JsonObject): WhoopReply
    suspend fun revoke(grant: WhoopGrant)
}

/** WHOOP's lease guard around the shared ring/WHOOP enrollment and ingest implementation. */
internal class NativeWhoopHttp(private val origin: String, serverOrigin: () -> String = { origin }) : WhoopHttp {
    private val collector = NativeCollectorHttp(origin, WhoopState.PROFILE, serverOrigin)

    override suspend fun enroll(lease: SessionLease, installation: String, name: String,
        checkpoint: () -> Unit, issued: (WhoopGrant) -> Unit): WhoopGrant = translated {
        require(lease.namespace.origin == origin)
        WhoopGrant(collector.enroll(lease.credential, lease.namespace.userId, installation, name, checkpoint,
            issued = { issued(WhoopGrant(it.value)) }).value)
    }
    override suspend fun state(grant: WhoopGrant, checkpoint: () -> Unit): JsonObject = translated {
        collector.state(NativeCollectorGrant(grant.value), checkpoint)
    }
    override suspend fun receipt(grant: WhoopGrant, key: String): WhoopReply = translated {
        collector.receipt(NativeCollectorGrant(grant.value), key).whoopReply()
    }
    override suspend fun send(grant: WhoopGrant, batch: JsonObject): WhoopReply = translated {
        collector.send(NativeCollectorGrant(grant.value), batch).whoopReply()
    }
    override suspend fun revoke(grant: WhoopGrant) = translated { collector.revoke(NativeCollectorGrant(grant.value)) }

    private fun NativeCollectorReply.whoopReply() = WhoopReply(status, value)
    private suspend fun <T> translated(block: suspend () -> T): T = try { block() }
    catch (failure: NativeCollectorFailure) { throw WhoopFailure(failure.code) }
}

internal object WhoopReceipts {
    fun matches(receipt: JsonObject, source: String, cursor: JsonElement): Boolean {
        val result = receipt["result"] as? JsonObject ?: receipt
        return result.string("source_id") == source && result["cursor"] == cursor
    }
}
