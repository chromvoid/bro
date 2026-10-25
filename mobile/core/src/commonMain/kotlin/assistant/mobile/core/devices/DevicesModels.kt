package assistant.mobile.core.devices

import assistant.mobile.core.AccountNamespace
import assistant.mobile.core.LocalizedString
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class ClientDeviceStatus(
    val rssi: Double? = null,
    val firmware: String? = null,
    val uptime: Double? = null,
    val heap: Double? = null,
)

@Serializable
data class ClientDeviceGrants(val writes: List<String> = emptyList(), val diagnostics: Boolean = false)

@Serializable
data class ClientDevice(
    val id: String,
    val kind: String,
    val name: String,
    @SerialName("created_at") val createdAt: String,
    @SerialName("last_seen_at") val lastSeenAt: String? = null,
    val status: ClientDeviceStatus? = null,
    val current: Boolean = false,
    val revision: Long? = null,
    val grants: ClientDeviceGrants? = null,
)

@Serializable
data class PairedDevice(val id: String, val kind: String, val name: String)

@Serializable
enum class DisplayLayout(val wire: String, val slots: Int) {
    @SerialName("single") Single("single", 1),
    @SerialName("split") Split("split", 2),
    @SerialName("grid") Grid("grid", 4),
}

@Serializable
data class DisplayPage(
    val layout: DisplayLayout,
    val cards: List<String>,
    val title: String? = null,
    val ticker: List<String>? = null,
    @SerialName("ticker_s") val tickerSeconds: Int? = null,
)

@Serializable
data class DisplaySettings(
    val pages: List<DisplayPage>,
    @SerialName("home_after_s") val homeAfterSeconds: Int,
)

@Serializable
data class DisplayCard(val id: String, val title: String, val available: Boolean, val brief: Boolean = false)

@Serializable
data class DisplayDescription(
    val device: ClientDevice,
    val settings: DisplaySettings,
    @SerialName("default") val defaults: DisplaySettings,
    val online: Boolean,
    val cards: List<DisplayCard>,
)

enum class PreviewInput(val wire: String) { Short("short"), Long("long") }

data class DevicesQuery<T>(
    val data: T? = null,
    val loading: Boolean = false,
    val error: LocalizedString? = null,
    val serverError: String? = null,
)

data class DevicesState(
    val namespace: AccountNamespace,
    val devices: DevicesQuery<List<ClientDevice>> = DevicesQuery(),
    val busy: Boolean = false,
    val error: LocalizedString? = null,
    val serverError: String? = null,
)

data class DisplayState(
    val namespace: AccountNamespace,
    val deviceId: String,
    val display: DevicesQuery<DisplayDescription> = DevicesQuery(),
    val draft: DisplaySettings? = null,
    val name: String = "",
    val saving: Boolean = false,
    val pressing: Boolean = false,
    val revoking: Boolean = false,
    val error: LocalizedString? = null,
    val serverError: String? = null,
    val saved: Boolean = false,
    val closed: Boolean = false,
    val preview: ByteArray? = null,
    val previewVersion: Long = 0,
) {
    val changed: Boolean get() = display.data?.let { current -> draft?.let {
        cleanDisplaySettings(it) != cleanDisplaySettings(current.settings) || name.trim() != current.device.name
    } } ?: false
}

val DISPLAY_TICKER_PERIODS = listOf(6, 12, 20, 30, 60)
val DISPLAY_HOME_PERIODS = listOf(0, 60, 300, 900, 3600)
const val DISPLAY_MAX_PAGES = 8
const val DISPLAY_MAX_TICKER = 6

/** Code syntax is a protocol alphabet, not an interpretation of human language. */
fun pairingCode(input: String): String? {
    val code = input.uppercase().filterNot { it.isWhitespace() || it == '-' }
    if (code.length != 8 || code.any { it !in "ABCDEFGHJKLMNPQRSTUVWXYZ23456789" }) return null
    return code.take(4) + "-" + code.drop(4)
}

/** Same ordered slot filling and title/strip cleanup as the web editor. */
fun withDisplayLayout(page: DisplayPage, layout: DisplayLayout, fallback: String): DisplayPage =
    page.copy(layout = layout, cards = List(layout.slots) { page.cards.getOrNull(it) ?: fallback })

fun cleanDisplaySettings(settings: DisplaySettings): DisplaySettings = settings.copy(pages = settings.pages.map { page ->
    val ticker = page.ticker?.takeIf { it.isNotEmpty() }
    page.copy(title = page.title?.trim()?.takeIf { it.isNotEmpty() }, ticker = ticker,
        tickerSeconds = if (ticker == null) null else page.tickerSeconds ?: 12)
})

fun toggledDisplayTicker(page: DisplayPage, card: String, on: Boolean): DisplayPage {
    val previous = page.ticker.orEmpty().filterNot { it == card }
    return page.copy(ticker = (if (on) previous + card else previous).take(DISPLAY_MAX_TICKER))
}

data class DeviceUptime(val days: Int, val hours: Int, val minutes: Int)

fun deviceUptimeParts(seconds: Double): DeviceUptime {
    val minutes = kotlin.math.floor(seconds / 60).toInt()
    return DeviceUptime(minutes / 1440, (minutes / 60) % 24, minutes % 60)
}
