package assistant.mobile.android.ambient

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AmbientMessagesTest {
    @Test
    fun uploadTimeoutAndThrottleRetryWithoutClaimingRecordingStopped() {
        for (status in listOf(408, 429, 500, 502, 503, 504)) {
            assertTrue("HTTP $status must retain the queued head and retry", AmbientMessages.retryableStatus(status))
            assertEquals("network_unavailable", AmbientMessages.code(AmbientHttpException(status)))
        }
    }

    @Test
    fun permanentRefusalsAndStorageQuotaStopInsteadOfRetryingForever() {
        for (status in listOf(301, 400, 401, 403, 404, 409, 410, 413, 422, 507)) {
            assertFalse("HTTP $status must stop recording and preserve the queue", AmbientMessages.retryableStatus(status))
        }
        assertEquals("not_authenticated", AmbientMessages.code(AmbientHttpException(401)))
        assertEquals("batch_expired", AmbientMessages.code(AmbientHttpException(410)))
        assertEquals("server_rejected", AmbientMessages.code(AmbientHttpException(507)))
    }

}
