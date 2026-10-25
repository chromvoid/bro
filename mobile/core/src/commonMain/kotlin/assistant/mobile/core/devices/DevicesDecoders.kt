package assistant.mobile.core.devices

import assistant.mobile.core.HttpOutcome
import assistant.mobile.core.NativeJson
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

@Serializable
private data class DevicesReply(val devices: List<ClientDevice>)

@Serializable
private data class PairingReply(val device: PairedDevice)

fun decodeDevices(bytes: ByteArray): List<ClientDevice> = NativeJson.decodeFromString<DevicesReply>(bytes.decodeToString()).devices.also { devices ->
    devices.forEach(::validateDevice)
    require(devices.map { it.id }.distinct().size == devices.size)
}

fun decodePairedDevice(bytes: ByteArray): PairedDevice = NativeJson.decodeFromString<PairingReply>(bytes.decodeToString()).device.also {
    require(it.id.isNotBlank() && it.kind == "display" && it.name.isNotBlank())
}

fun decodeDisplaySettings(bytes: ByteArray): DisplaySettings =
    NativeJson.decodeFromString<DisplaySettings>(bytes.decodeToString()).also(::validateSettings)

fun decodeDisplayDescription(bytes: ByteArray): DisplayDescription =
    NativeJson.decodeFromString<DisplayDescription>(bytes.decodeToString()).also {
        validateDevice(it.device)
        require(it.device.kind == "display" && it.device.revision != null)
        validateSettings(it.settings)
        validateSettings(it.defaults)
        require(it.cards.all { card -> card.id.isNotBlank() && card.title.isNotBlank() })
        require(it.cards.map { card -> card.id }.distinct().size == it.cards.size)
    }

private fun validateDevice(device: ClientDevice) {
    require(device.id.isNotBlank() && device.kind.isNotBlank() && device.createdAt.isNotBlank())
    require(device.revision == null || device.revision >= 0)
    device.status?.let { status ->
        require(listOfNotNull(status.rssi, status.uptime, status.heap).all { it.isFinite() })
    }
}

private fun validateSettings(settings: DisplaySettings) {
    require(settings.pages.size in 1..DISPLAY_MAX_PAGES && settings.homeAfterSeconds in 0..86400)
    settings.pages.forEach { page ->
        require(page.cards.size == page.layout.slots && page.cards.all { it.isNotBlank() })
        require(page.title == null || page.title.length <= 40)
        require(page.ticker.orEmpty().size <= DISPLAY_MAX_TICKER && page.ticker.orEmpty().all { it.isNotBlank() })
        require(page.ticker.orEmpty().distinct().size == page.ticker.orEmpty().size)
        require(page.tickerSeconds == null || page.tickerSeconds in 5..300)
    }
}

/** Optional fields are absent on the wire, as in cleanSettings in the web client. */
fun displaySaveBody(description: DisplayDescription, draft: DisplaySettings, name: String): ByteArray = buildJsonObject {
    put("revision", requireNotNull(description.device.revision))
    put("settings", NativeJson.parseToJsonElement(NativeJson.encodeToString(cleanDisplaySettings(draft))))
    name.trim().takeIf { it.isNotEmpty() }?.let { put("name", it) }
}.toString().encodeToByteArray()

/** Typed JSON messages and short text from 4xx replies follow the web's errorText contract. */
internal fun devicesServerError(outcome: HttpOutcome): String? {
    val bytes = when (outcome) {
        is HttpOutcome.Failure -> if (outcome.status in 400..499) outcome.body else null
        is HttpOutcome.Conflict -> outcome.body
        else -> null
    } ?: return null
    val text = bytes.decodeToString()
    val parsed = runCatching { NativeJson.parseToJsonElement(text) }.getOrNull()
    val message = when (parsed) {
        is JsonObject -> (parsed["message"] as? JsonPrimitive)?.takeIf { it.isString }?.content
        is JsonPrimitive -> parsed.takeIf { it.isString }?.content?.takeIf { it.length < 300 }
        null -> text.takeIf { it.length < 300 }
        else -> null
    }
    return message?.takeIf { it.isNotBlank() }
}

internal fun validDisplayPng(bytes: ByteArray, type: String?): Boolean =
    bytes.size in 8..1_048_576 && type?.substringBefore(';')?.trim()?.lowercase() == "image/png" &&
        bytes.take(8) == listOf(137, 80, 78, 71, 13, 10, 26, 10).map(Int::toByte)
