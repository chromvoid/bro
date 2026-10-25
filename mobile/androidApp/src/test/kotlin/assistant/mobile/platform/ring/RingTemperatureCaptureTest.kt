package assistant.mobile.platform.ring

import java.time.Instant
import java.time.ZoneId
import org.junit.Assert.*
import org.junit.Test

class RingTemperatureCaptureTest {
    private val zone = ZoneId.of("Europe/Moscow")
    private val start = Instant.parse("2026-10-09T20:59:59Z")
    private val command = byteArrayOf(0xbc.toByte(), 0x25, 1, 0, 0, 0, 0)
    private fun begin(capture: RingTemperatureCapture, owner: String? = "link-a") =
        capture.beforeWrite("bulk", "temperature_legacy:0:0", command, owner, zone, start, 100)

    @Test fun firstRequestRetainsSyncDayAndGenuineParseAfterMidnight() {
        val capture = RingTemperatureCapture("link-a")
        val id = begin(capture)!!
        val parsed = start.plusSeconds(2)
        val context = capture.parsed(id, start.plusSeconds(1), 200, "link-a", zone, parsed)!!
        assertEquals(start.toString(), context.string("sync_started_at"))
        assertEquals(parsed.toString(), context.string("parsed_at"))
        assertEquals(id, context.string("request_id"))
        assertEquals("2026-10-09", RingTemperatureCapture.semanticContext(context).string("sync_local_date"))
        assertEquals(context, RingTemperatureCapture.checkedContext(context, parsed))
    }

    @Test fun staleOwnerEarlyCallbackTimezoneChangeAndClockReversalInvalidateAttempt() {
        for (reason in listOf("owner", "early", "timezone", "clock", "callback_request")) {
            val capture = RingTemperatureCapture("link-a")
            val id = begin(capture)!!
            assertNull(reason, capture.parsed(if (reason == "callback_request") "older-request" else id,
                start, if (reason == "early") 99 else 101,
                if (reason == "owner") "link-b" else "link-a",
                if (reason == "timezone") ZoneId.of("UTC") else zone,
                if (reason == "clock") start.minusSeconds(1) else start))
            assertNull(reason, capture.parsed(id, start, 102, "link-a", zone, start))
        }
        val unowned = RingTemperatureCapture("link-a")
        val id = begin(unowned, null)
        assertNull(unowned.parsed(id, start, 101, "link-a", zone, start))
    }

}
