package assistant.mobile.core.devices

import assistant.mobile.core.NativeJson
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

internal fun devicesFixture(name: String): ByteArray = requireNotNull(
    DevicesModelsTest::class.java.classLoader.getResourceAsStream("devices/$name.json"),
).use { it.readBytes() }

/** Synthetic copies of the web API example and server settings/list projections.
 * Sources: web/src/sections/devices/api.test.ts; server/display/settings.py;
 * tests/integration/test_display.py::test_settings_preview_and_revocation;
 * server/identity/devices.py::Devices.list. No personal device data is used.
 */
class DevicesModelsTest {
    @Test
    fun decodesListProjectionWithCurrentSessionTelemetryAndAgentGrants() {
        val devices = decodeDevices(devicesFixture("list"))
        assertEquals(listOf("display", "android", "browser", "agent"), devices.map { it.kind })
        assertEquals(-60.0, devices.first().status!!.rssi)
        assertEquals(DeviceUptime(1, 2, 3), deviceUptimeParts(devices.first().status!!.uptime!!))
        assertTrue(devices[1].current)
        assertNull(devices[1].lastSeenAt)
        assertEquals(listOf("nutrition", "tasks"), devices.last().grants!!.writes)
        assertTrue(devices.last().grants!!.diagnostics)
        assertFailsWith<IllegalArgumentException> {
            decodeDevices(devicesFixture("list").decodeToString().replace("\"created_at\":", "\"other\":").encodeToByteArray())
        }
    }

    @Test
    fun savePayloadDropsBlankFieldsAndUsesTheOriginalRevision() {
        val current = decodeDisplayDescription(devicesFixture("display-web"))
        val settings = DisplaySettings(listOf(
            DisplayPage(DisplayLayout.Single, listOf("clock"), "   ", emptyList(), 30),
            DisplayPage(DisplayLayout.Single, listOf("clock"), " Экран ", listOf("clock")),
        ), 300)
        val json = NativeJson.parseToJsonElement(displaySaveBody(current, settings, "  Стол  ").decodeToString()).jsonObject
        assertEquals("2", json.getValue("revision").toString())
        assertEquals("\"Стол\"", json.getValue("name").toString())
        val cleaned = decodeDisplaySettings(json.getValue("settings").toString().encodeToByteArray())
        assertNull(cleaned.pages[0].title)
        assertNull(cleaned.pages[0].ticker)
        assertNull(cleaned.pages[0].tickerSeconds)
        assertEquals("Экран", cleaned.pages[1].title)
        assertEquals(12, cleaned.pages[1].tickerSeconds)
        assertFalse(displaySaveBody(current, settings, " ").decodeToString().contains("\"name\""))
    }
}
