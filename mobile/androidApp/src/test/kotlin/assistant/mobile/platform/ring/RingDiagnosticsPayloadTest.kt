package assistant.mobile.platform.ring

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class RingDiagnosticsPayloadTest {
    @Test fun secretAndDeviceIdentityKeysAreRemovedRecursively() {
        val payload = obj {
            put("token", "synthetic-token"); put("Cookie", "synthetic-cookie")
            put("Authorization", "synthetic-authorization"); put("grant", obj { put("generation", 1) })
            put("address", "00:11:22:33:44:55"); put("MAC", "00:11:22:33:44:55")
            put("Device-Address", "00:11:22:33:44:55"); put("USER_ID", "synthetic-user")
            put("access_token", "synthetic-access"); put("Refresh-Token", "synthetic-refresh")
            put("csrf_token", "synthetic-csrf"); put("headers", obj { put("X-Private", "synthetic-header") })
            put("nested", JsonArray(listOf(obj {
                put("cookie", "nested-cookie"); put("bytes_hex", "390001ff"); put("opcode", 39)
            }, JsonNull, JsonPrimitive("technical-value"))))
        }
        val expected = obj {
            put("nested", JsonArray(listOf(obj { put("bytes_hex", "390001ff"); put("opcode", 39) },
                JsonNull, JsonPrimitive("technical-value"))))
        }
        assertEquals(expected, RingDiagnostics.safePayload(payload))
    }

}
