package assistant.mobile.core.calls

import assistant.mobile.core.HttpOutcome
import assistant.mobile.core.NativeHttpClient
import assistant.mobile.core.NativeJson
import assistant.mobile.core.SessionLease
import io.ktor.http.encodeURLPathPart
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import kotlin.time.Instant

@Serializable private data class CallReply(val call: CallRecord)
@Serializable private data class CurrentCallReply(val call: CallRecord?)

fun decodeCall(bytes: ByteArray): CallRecord =
    NativeJson.decodeFromString<CallReply>(bytes.decodeToString()).call.also(::validateCall)
fun decodeCurrentCall(bytes: ByteArray): CallRecord? =
    NativeJson.decodeFromString<CurrentCallReply>(bytes.decodeToString()).call?.also(::validateCall)
private fun validateCall(call: CallRecord) {
    require(call.id.isNotBlank() && call.channelId.isNotBlank() && call.peer.id.isNotBlank())
    require(call.kind in setOf("audio", "video"))
    Instant.parse(call.createdAt)
    call.answeredAt?.let(Instant::parse)
    call.endedAt?.let(Instant::parse)
}

/** No local STUN/TURN fallback. An empty server list is the server's direct policy. */
fun decodeCallIce(bytes: ByteArray): CallIceConfiguration {
    val value = NativeJson.parseToJsonElement(bytes.decodeToString()).jsonObject
    val servers = value.getValue("ice_servers").jsonArray.map { item ->
        val server = item.jsonObject
        val urls = when (val raw = server.getValue("urls")) {
            is JsonArray -> raw.map { it.checkedString() }
            is JsonPrimitive -> listOf(raw.checkedString())
            else -> error("Invalid ICE URLs")
        }
        require(urls.isNotEmpty() && urls.all { url ->
            url.isNotBlank() && url.none(Char::isISOControl) &&
                listOf("stun:", "stuns:", "turn:", "turns:").any(url::startsWith)
        })
        CallIceServer(urls, server["username"]?.checkedString(), server["credential"]?.checkedString())
    }
    val policy = when (value["ice_transport_policy"]?.checkedString() ?: "all") {
        "all" -> CallIceTransportPolicy.ALL
        "relay" -> CallIceTransportPolicy.RELAY
        else -> error("Invalid ICE policy")
    }
    return CallIceConfiguration(servers, policy)
}

/** The server relays candidates inside full SDP; a standalone candidate frame is unsupported. */
fun decodeCallServerFrame(text: String): CallServerFrame? = runCatching {
    require(text.length <= 65_536)
    val frame = NativeJson.parseToJsonElement(text).jsonObject
    when (frame.getValue("type").checkedString()) {
        "state" -> CallServerFrame.State(
            NativeJson.decodeFromJsonElement<CallState>(frame.getValue("state")),
            NativeJson.decodeFromJsonElement<CallOutcome?>(frame.getValue("outcome")),
        )
        "peer" -> CallServerFrame.Peer(frame.getValue("present").let {
            require(it is JsonPrimitive && !it.isString)
            requireNotNull(it.booleanOrNull)
        })
        "offer" -> CallServerFrame.Offer(frame.getValue("sdp").checkedString().also(::validateSdp))
        "answer" -> CallServerFrame.Answer(frame.getValue("sdp").checkedString().also(::validateSdp))
        "pong" -> CallServerFrame.Pong
        "error" -> CallServerFrame.Error(frame.getValue("code").checkedString())
        else -> null
    }
}.getOrNull()

fun encodeCallClientFrame(frame: CallClientFrame): String = buildJsonObject {
    when (frame) {
        is CallClientFrame.Offer -> { validateSdp(frame.sdp); put("type", "offer"); put("sdp", frame.sdp) }
        is CallClientFrame.Answer -> { validateSdp(frame.sdp); put("type", "answer"); put("sdp", frame.sdp) }
        CallClientFrame.Ping -> put("type", "ping")
    }
}.toString()
private fun validateSdp(sdp: String) {
    require(sdp.length in 1..32_768 && sdp.startsWith("v=0") && sdp.none {
        it.isISOControl() && it != '\r' && it != '\n'
    })
}
private fun JsonElement.checkedString(): String {
    require(this is JsonPrimitive && isString)
    return content
}

class CallRequestFailure(val outcome: HttpOutcome) : Exception("Call request failed") {
    val transient: Boolean get() = outcome == HttpOutcome.NetworkFailure || outcome is HttpOutcome.Failure && outcome.status >= 500
    val code: String? get() = callErrorCode(outcome)
}

/** Only machine-defined response codes select an existing web string. */
fun callErrorKey(code: String?): String? = when (code) {
    "busy" -> "calls.error.busy"
    "in_call" -> "calls.error.inCall"
    "not_friends" -> "calls.error.notFriends"
    "not_available" -> "calls.error.notAvailable"
    "rate_limited" -> "calls.error.tooMany"
    "answered_elsewhere" -> "calls.error.answeredElsewhere"
    "ended" -> "calls.error.ended"
    else -> null
}
internal fun callErrorCode(outcome: HttpOutcome): String? {
    val body = when (outcome) {
        is HttpOutcome.Conflict -> outcome.body
        is HttpOutcome.Failure -> outcome.body
        else -> return null
    }
    return runCatching {
        NativeJson.parseToJsonElement(body.decodeToString()).jsonObject["error"]?.checkedString()
    }.getOrNull()
}

class NativeCallApi(private val http: NativeHttpClient) : CallApi {
    override suspend fun current(lease: SessionLease): CallRecord? = decodeCurrentCall(read("/v1/calls/current", lease))
    override suspend fun start(channelId: String, key: String, lease: SessionLease): CallRecord = decodeCall(
        post("/v1/calls", buildJsonObject { put("channel_id", channelId); put("kind", "audio") }, lease, key),
    )
    override suspend fun accept(id: String, lease: SessionLease): CallRecord = decodeCall(post(path(id, "accept"), buildJsonObject {}, lease))
    override suspend fun end(id: String, body: CallEndBody, lease: SessionLease): CallRecord = decodeCall(
        post(path(id, "end"), buildJsonObject {
            body.reason?.let { put("reason", NativeJson.encodeToJsonElement(it)) }
            body.summary?.let { summary -> put("summary", buildJsonObject {
                put("client", summary.client); put("ice", summary.ice)
            }) }
        }, lease),
    )
    override suspend fun ice(id: String, lease: SessionLease): CallIceConfiguration = decodeCallIce(read(path(id, "ice"), lease))
    private suspend fun read(path: String, lease: SessionLease): ByteArray = bytes(http.request("GET", path, lease = lease, preserveRejectionBody = true))
    private suspend fun post(path: String, body: JsonObject, lease: SessionLease, key: String? = null): ByteArray =
        bytes(http.request("POST", path, body.toString().encodeToByteArray(), lease, key, preserveRejectionBody = true))
    private fun bytes(outcome: HttpOutcome): ByteArray = (outcome as? HttpOutcome.Success)?.body ?: throw CallRequestFailure(outcome)
    private fun path(id: String, action: String) = "/v1/calls/${id.encodeURLPathPart()}/$action"
}
