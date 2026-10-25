package assistant.mobile.platform.ring

import java.time.Instant
import java.time.ZoneId
import java.util.UUID
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.put

/** One BC25 attempt on one GATT owner; the wire protocol cannot distinguish retries. */
class RingTemperatureCapture(private val owner: String) {
    private data class Request(val id: String, val startedAt: Instant, val startedNanos: Long,
        val timezone: ZoneId, val qualified: Boolean)
    private var request: Request? = null
    private var attempted = false

    fun beforeWrite(channel: String, queryId: String?, bytes: ByteArray, currentOwner: String?,
        timezone: ZoneId, startedAt: Instant, startedNanos: Long): String? {
        request = null
        if (channel != "bulk" || queryId != "temperature_legacy:0:0" || bytes.size < 6 ||
            (bytes[0].toInt() and 0xff) != 0xbc || (bytes[1].toInt() and 0xff) != 0x25) return null
        val next = Request(UUID.randomUUID().toString(), startedAt, startedNanos, timezone,
            !attempted && currentOwner == owner && timezone.id in ZoneId.getAvailableZoneIds())
        attempted = true
        request = next
        return next.id
    }

    fun parsed(callbackRequestId: String?, callbackAt: Instant, callbackNanos: Long,
        currentOwner: String?, timezone: ZoneId, parsedAt: Instant): JsonObject? {
        val active = request ?: return null
        if (!active.qualified || callbackRequestId != active.id || currentOwner != owner ||
            timezone != active.timezone || callbackNanos < active.startedNanos ||
            callbackAt < active.startedAt || parsedAt < callbackAt) {
            // Any ambiguity lasts for this attempt, including a later timezone restoration.
            request = active.copy(qualified = false)
            return null
        }
        return obj {
            put("version", "bro-soul1230-temperature-capture-v1")
            put("sync_started_at", active.startedAt.toString()); put("parsed_at", parsedAt.toString())
            put("timezone", timezone.id); put("request_id", active.id)
        }
    }

    companion object {
        fun checkedContext(value: JsonElement, observedAt: Instant): JsonObject? {
            val context = value as? JsonObject ?: return null
            if (context.keys != setOf("version", "sync_started_at", "parsed_at", "timezone", "request_id") ||
                context.values.any { it !is JsonPrimitive || !it.isString } ||
                context.string("version") != "bro-soul1230-temperature-capture-v1" ||
                context.string("request_id")?.let { it.isNotEmpty() && it.length <= 200 } != true ||
                listOf("sync_started_at", "parsed_at", "timezone").any { context.string(it)!!.length !in 1..100 }) return null
            return try {
                val started = Instant.parse(context.string("sync_started_at") ?: return null)
                val parsed = Instant.parse(context.string("parsed_at") ?: return null)
                if (context.string("timezone")!! !in ZoneId.getAvailableZoneIds()) return null
                if (started > parsed || parsed > observedAt) null else context
            } catch (_: Exception) { null }
        }

        fun semanticContext(context: JsonObject): JsonObject = obj {
            put("version", context.string("version")!!); put("timezone", context.string("timezone")!!)
            put("sync_local_date", Instant.parse(context.string("sync_started_at")!!)
                .atZone(ZoneId.of(context.string("timezone")!!)).toLocalDate().toString())
        }
    }
}
