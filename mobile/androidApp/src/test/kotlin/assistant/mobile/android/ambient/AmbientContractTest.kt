package assistant.mobile.android.ambient

import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AmbientContractTest {
    private val origin = "https://assistant.example"
    private val user = "00000000-0000-4000-8000-000000000001"
    private val device = "00000000-0000-4000-8000-000000000002"
    private val expiry = "2026-10-05T12:00:00Z"

    @Test
    fun bindingUsesVerifiedServerSessionAndSemanticExpiry() {
        val binding = AmbientContract.parseMeBinding(
            """{"id":"$user","device_id":"$device","session_expires_at":"$expiry","name":"Owner"}""", origin,
        )!!
        assertTrue(binding.matches(binding.copy(expiresAt = "2026-10-05T12:00:00+00:00")))
        assertFalse(binding.matches(binding.copy(deviceId = user)))
        assertFalse(binding.matches(binding.copy(expiresAt = "2026-10-05T13:00:00Z")))
        assertTrue(binding.validAt(Instant.parse("2026-10-05T11:59:59Z")))
        assertFalse(binding.validAt(Instant.parse(expiry)))
    }

    @Test
    fun malformedBindingAndInjectedCredentialsAreRejected() {
        val account = """{"user_id":"$user","device_id":"$device","expires_at":"$expiry"}"""
        assertEquals(user, AmbientContract.parseBinding(account, origin)?.userId)
        assertNull(AmbientContract.parseBinding(account.dropLast(1) + ",\"token\":\"secret\"}", origin))
        assertNull(AmbientContract.parseBinding(account.replace(expiry, "2026-10-05T12:00:00"), origin))
        assertNull(AmbientContract.parseMeBinding("""{"id":"$user","device_id":null,"session_expires_at":"$expiry"}""", origin))
        assertNull(AmbientContract.parseBinding(account, "https://assistant.example/other"))
    }

    @Test
    fun nativeBindingRejectsOriginsThatCouldRedirectOrExposeAccountCredentials() {
        val me = """{"id":"$user","device_id":"$device","session_expires_at":"$expiry"}"""
        for (unsafe in listOf(
            "https://person:private@assistant.example", "https://assistant.example?credential=private",
            "https://assistant.example#private", "https://assistant.example/other", "http://assistant.example",
        )) {
            assertFalse(AmbientContract.validOrigin(unsafe))
            assertNull(AmbientContract.parseMeBinding(me, unsafe))
        }
        assertTrue(AmbientContract.validOrigin(origin))
        assertTrue(AmbientContract.validOrigin("http://127.0.0.1:8766"))
    }

    @Test
    fun durableReceiptRequiresAnExactTypedConfirmation() {
        val hash = "a".repeat(64)
        val receipt = """{"id":"$device","sha256":"$hash","stored":true}"""
        assertEquals(AmbientReceipt(device, hash), AmbientContract.parseReceipt(receipt))
        assertNull(AmbientContract.parseReceipt(receipt.replace("true", "false")))
        assertNull(AmbientContract.parseReceipt(receipt.replace("true", "\"true\"")))
        assertNull(AmbientContract.parseReceipt(receipt.replace(hash, "abc")))
        assertEquals(AmbientReceipt(device, hash), AmbientContract.parseReceipt(
            """{"id":"$device","session_id":"$user","sequence":0,"sha256":"$hash","stored":true,"bytes":960044,"duration_ms":30000,"expires_at":null}""",
        ))
        assertNull(AmbientContract.parseReceipt("""{"id":"$device","sha256":"$hash"}"""))
        assertNull(AmbientContract.parseReceipt("not json"))
    }

    @Test
    fun recordingRequiresExplicitCompressedServerSupportAndBounds() {
        val ready = """{"accepted_formats":["ogg-opus","aac-adts","wav"],"limits":{"max_compressed_batch_bytes":262144,"max_batch_bytes":1048576,"max_batch_duration_ms":31000}}"""
        assertEquals(AmbientAudioFormat.entries.toSet(), AmbientContract.acceptedFormats(ready))
        assertNull(AmbientContract.acceptedFormats("""{"limits":{"max_batch_bytes":1048576,"max_batch_duration_ms":31000}}"""))
        assertNull(AmbientContract.acceptedFormats(ready.replace("262144", "262143")))
        assertNull(AmbientContract.acceptedFormats(ready.replace("31000", "29999")))
        assertNull(AmbientContract.acceptedFormats(ready.replace("[\"ogg-opus\",\"aac-adts\",\"wav\"]", "[\"wav\"]")))
        assertNull(AmbientContract.acceptedFormats(ready.replace("262144", "\"262144\"")))
        assertNull(AmbientContract.acceptedFormats(ready.replace("\"ogg-opus\"", "24")))
    }
}
