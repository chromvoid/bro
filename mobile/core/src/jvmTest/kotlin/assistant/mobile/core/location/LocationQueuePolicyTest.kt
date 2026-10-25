package assistant.mobile.core.location

import assistant.mobile.core.NativeJson
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Instant

/** Receipt fixtures from tests/integration/test_location_store.py; these gates run before SQLite erasure. */
class LocationQueuePolicyTest {
    @Test fun bothNewPointsAndDuplicateRedeliveryCanAcknowledgeTheWholeBatch() {
        val first = NativeJson.decodeFromString<LocationAcknowledgement>("{\"accepted\":2,\"duplicates\":0}")
        val again = NativeJson.decodeFromString<LocationAcknowledgement>("{\"accepted\":0,\"duplicates\":1}")
        assertTrue(LocationQueuePolicy.acknowledges(first, 2))
        assertTrue(LocationQueuePolicy.acknowledges(again, 1))
        assertTrue(LocationQueuePolicy.acknowledges(LocationAcknowledgement(1, 2), 3))
        assertTrue(LocationQueuePolicy.acknowledges(LocationAcknowledgement(0, 300), 300))
    }

    @Test fun retryAfterALostResponseAcceptsServerDuplicatesAndAnIncompleteAckKeepsTheBatch() {
        // The real collector retains its original batch/timestamps on RETRY and erases it only on ACK.
        assertEquals(LocationUploadDisposition.RETRY, LocationQueuePolicy.uploadDisposition(503, null, 2))
        assertEquals(LocationUploadDisposition.ACK, LocationQueuePolicy.uploadDisposition(200, LocationAcknowledgement(0, 2), 2))
        assertEquals(LocationUploadDisposition.RETRY, LocationQueuePolicy.uploadDisposition(200, LocationAcknowledgement(0, 1), 2))
        assertEquals(LocationUploadDisposition.RETRY, LocationQueuePolicy.uploadDisposition(204, null, 2))
        assertEquals(LocationUploadDisposition.ACK, LocationQueuePolicy.uploadDisposition(200, LocationAcknowledgement(1, 1), 2))
    }

    @Test fun anUnusableAckCannotOverrideRevocationOrMakeAnHttpFailureSuccessful() {
        val ack = LocationAcknowledgement(2, 0)
        assertEquals(LocationUploadDisposition.REVOKE, LocationQueuePolicy.uploadDisposition(401, ack, 2))
        assertEquals(LocationUploadDisposition.REVOKE, LocationQueuePolicy.uploadDisposition(403, null, 2))
        assertEquals(LocationUploadDisposition.DISCARD, LocationQueuePolicy.uploadDisposition(422, null, 2))
        assertEquals(LocationUploadDisposition.RETRY, LocationQueuePolicy.uploadDisposition(429, ack, 2))
    }

    @Test fun collectorStopsAtTheEarlierParentOrGrantDeadlineIncludingTheExactBoundary() {
        val before = "2026-10-17T11:00:00Z"
        val after = "2026-10-17T12:00:00Z"
        for (effective in listOf(LocationQueuePolicy.effectiveCollectorExpiry(before, after),
            LocationQueuePolicy.effectiveCollectorExpiry(after, before))) {
            assertEquals(before, effective)
            val boundary = Instant.parse(before).toEpochMilliseconds()
            assertFalse(LocationQueuePolicy.expired(effective, boundary - 1))
            assertTrue(LocationQueuePolicy.expired(effective, boundary))
        }
        assertEquals(before, LocationQueuePolicy.effectiveCollectorExpiry(before, "2026-10-17T14:00:00+03:00"))
    }
}
