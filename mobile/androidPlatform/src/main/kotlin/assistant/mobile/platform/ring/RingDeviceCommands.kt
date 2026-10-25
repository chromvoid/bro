package assistant.mobile.platform.ring

import assistant.mobile.platform.R

import java.time.DateTimeException
import java.time.LocalDateTime
import kotlinx.serialization.json.*

/** Fixed device command schema; opcodes and qualification remain inside the protocol adapter. */
object RingDeviceCommands {
    val settings = setOf("alarms", "drink_reminders", "dnd", "sedentary", "power_saving", "gestures", "profile", "clock")
    val actions = setOf("find_ring", "bind", "unbind", "factory_reset", "power_off", "tasbih_start", "tasbih_stop")
    fun kind(command: JsonObject): String? = when (command.string("type")) {
        "read" -> command.string("kind")
        "update" -> (command["setting"] as? JsonObject)?.string("kind")
        "action" -> command.string("action")?.let { if (it in setOf("tasbih_start", "tasbih_stop")) "counter" else it }
        "notification" -> "notifications"
        else -> null
    }
    fun capability(native: JsonObject, kind: String): JsonObject? =
        (native["capabilities"] as? JsonArray)?.mapNotNull { it as? JsonObject }?.singleOrNull { it.string("kind") == kind }
    fun requireQualified(native: JsonObject, command: JsonObject) {
        val cap = kind(command)?.let { capability(native, it) }
        val allowed = if (command.string("type") == "read") cap?.bool("readable") == true
            else cap?.string("status") == "verified" && cap.bool("writable") == true
        if (!allowed || cap?.string("evidence_ref").isNullOrBlank())
            throw RingFailure(cap?.string("reason") ?: "protocol_unverified", RingText.text(R.string.ring_native_a8bc95ba7141))
    }
    private fun exact(value: JsonObject, keys: Set<String>) {
        if (value.keys != keys) throw RingFailure("bad_payload", RingText.text(R.string.ring_native_ca6e62224efa))
    }
    private fun number(value: JsonObject, key: String, range: LongRange): Long {
        val result = (value[key] as? JsonPrimitive)?.takeUnless { it.isString }?.longOrNull
        if (result == null || result !in range) throw RingFailure("bad_payload", RingText.text(R.string.ring_native_e6ee9aef1739))
        return result
    }
    private fun number(value: JsonObject, key: String, range: IntRange): Long =
        number(value, key, range.first.toLong()..range.last.toLong())
    private fun flag(value: JsonObject, key: String): Boolean =
        (value[key] as? JsonPrimitive)?.takeUnless { it.isString }?.booleanOrNull
            ?: throw RingFailure("bad_payload", RingText.text(R.string.ring_native_5b8b1234905a))
    fun validateSetting(value: JsonObject): JsonObject {
        when (value.string("kind")) {
            "alarms", "drink_reminders" -> {
                val count = if (value.string("kind") == "drink_reminders") 8 else 5
                exact(value, setOf("kind", "slots"))
                val slots = value["slots"] as? JsonArray ?: throw RingFailure("bad_payload", RingText.text(R.string.ring_native_e92971f4180f))
                if (slots.size != count) throw RingFailure("bad_payload", RingText.text(R.string.ring_native_e92971f4180f))
                slots.forEachIndexed { index, item ->
                    val slot = item as? JsonObject ?: throw RingFailure("bad_payload", RingText.text(R.string.ring_native_7b92f9913663))
                    exact(slot, setOf("slot", "enabled", "hour", "minute", "weekdays"))
                    number(slot, "slot", index.toLong()..index.toLong()); flag(slot, "enabled")
                    number(slot, "hour", 0..23); number(slot, "minute", 0..59); number(slot, "weekdays", 0..127)
                }
            }
            "dnd", "sedentary" -> {
                val sedentary = value.string("kind") == "sedentary"
                exact(value, setOf("kind", "enabled", "start_minute", "end_minute") + if (sedentary) setOf("weekdays", "interval_minutes") else emptySet())
                val enabled = flag(value, "enabled"); number(value, "start_minute", 0..1439); number(value, "end_minute", 0..1439)
                if (sedentary) {
                    val days = number(value, "weekdays", 0..127)
                    if (enabled && days == 0L || number(value, "interval_minutes", 30..90) !in setOf(30L, 60L, 90L))
                        throw RingFailure("bad_payload", RingText.text(R.string.ring_native_c11dd6e4171e))
                }
            }
            "power_saving" -> { exact(value, setOf("kind", "enabled")); flag(value, "enabled") }
            "gestures" -> {
                exact(value, setOf("kind", "enabled", "mode", "hand")); flag(value, "enabled")
                if (value.string("mode") !in setOf("media", "video", "ebook", "game") || value.string("hand") !in setOf("left", "right"))
                    throw RingFailure("bad_payload", RingText.text(R.string.ring_native_7118420b8da4))
            }
            "profile" -> {
                exact(value, setOf("kind", "sex", "age", "height_cm", "weight_kg", "is_24_hour", "units",
                    "systolic_mm_hg", "diastolic_mm_hg", "heart_rate_warning_bpm"))
                if (value.string("sex") !in setOf("male", "female") || value.string("units") !in setOf("metric", "imperial"))
                    throw RingFailure("bad_payload", RingText.text(R.string.ring_native_ee3ceb55c341))
                number(value, "age", 1..120); number(value, "height_cm", 1..255); number(value, "weight_kg", 1..255)
                flag(value, "is_24_hour")
                listOf("systolic_mm_hg", "diastolic_mm_hg", "heart_rate_warning_bpm").forEach { number(value, it, 0..255) }
            }
            "clock" -> {
                exact(value, setOf("kind", "year", "month", "day", "hour", "minute", "second", "language"))
                try { LocalDateTime.of(number(value, "year", 2000..2099).toInt(), number(value, "month", 1..12).toInt(),
                    number(value, "day", 1..31).toInt(), number(value, "hour", 0..23).toInt(), number(value, "minute", 0..59).toInt(),
                    number(value, "second", 0..59).toInt()) } catch (_: DateTimeException) { throw RingFailure("bad_payload", RingText.text(R.string.ring_native_fa51fb68469f)) }
                number(value, "language", 0..19)
            }
            else -> throw RingFailure("bad_payload", RingText.text(R.string.ring_native_7c659ba80ac8))
        }
        return value
    }
    fun parse(type: String, payload: JsonObject): JsonObject = when (type) {
        "ring.device.read" -> {
            exact(payload, setOf("kind"))
            val kind = payload.string("kind")?.takeIf { it in settings } ?: throw RingFailure("bad_payload", RingText.text(R.string.ring_native_7c659ba80ac8))
            obj { put("type", "read"); put("kind", kind) }
        }
        "ring.device.update" -> {
            exact(payload, setOf("setting"))
            val setting = validateSetting(payload["setting"] as? JsonObject ?: throw RingFailure("bad_payload", RingText.text(R.string.ring_native_53bee40a8800)))
            obj { put("type", "update"); put("setting", setting) }
        }
        "ring.device.action" -> {
            exact(payload, setOf("action"))
            val action = payload.string("action")?.takeIf { it in actions } ?: throw RingFailure("bad_payload", RingText.text(R.string.ring_native_51d0eafa8da7))
            obj { put("type", "action"); put("action", action) }
        }
        else -> throw RingFailure("unknown_type", RingText.text(R.string.ring_native_d4f4c2f4f3eb))
    }
}
