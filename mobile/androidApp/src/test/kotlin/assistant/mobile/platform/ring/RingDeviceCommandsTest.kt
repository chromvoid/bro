package assistant.mobile.platform.ring

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class RingDeviceCommandsTest {
    private fun fails(action: () -> Unit) {
        try { action(); fail("Expected a typed rejection") } catch (_: RingFailure) { }
    }
    @Test fun readProofCannotAuthorizeAnUpdateOrAnInjectedOpcode() {
        val caps = obj { put("capabilities", JsonArray(listOf(obj {
            put("kind", "dnd"); put("status", "protocol_unverified"); put("readable", true); put("writable", false)
            put("evidence_ref", "synthetic-read-only"); put("reason", "write_recipe_unverified")
        }))) }
        RingDeviceCommands.requireQualified(caps, obj { put("type", "read"); put("kind", "dnd") })
        fails { RingDeviceCommands.requireQualified(caps, obj { put("type", "update"); put("setting", obj { put("kind", "dnd") }) }) }
        fails { RingDeviceCommands.parse("ring.device.action", obj { put("action", "find_ring"); put("opcode", 80) }) }
    }
    @Test fun alarmSlotsMustBeCompleteOrderedAndBounded() {
        val slots = (0..4).map { slot -> obj { put("slot", slot); put("enabled", true); put("hour", 7); put("minute", 30); put("weekdays", 0) } }
        RingDeviceCommands.validateSetting(obj { put("kind", "alarms"); put("slots", JsonArray(slots)) })
        fails { RingDeviceCommands.validateSetting(obj { put("kind", "alarms"); put("slots", JsonArray(slots.reversed())) }) }
        fails { RingDeviceCommands.validateSetting(obj { put("kind", "alarms"); put("slots", JsonArray(slots.take(4))) }) }
    }
    @Test fun bindUnbindResetAndPowerOffRemainDifferentIntents() {
        val commands = listOf("bind", "unbind", "factory_reset", "power_off").map {
            RingDeviceCommands.parse("ring.device.action", obj { put("action", it) })
        }
        assertEquals(4, commands.toSet().size)
        fails { RingDeviceCommands.parse("ring.device.action", obj { put("action", "restart") }) }
    }
}
