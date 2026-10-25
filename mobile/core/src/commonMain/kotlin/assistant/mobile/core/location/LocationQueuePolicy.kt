package assistant.mobile.core.location

import kotlin.time.Instant

enum class LocationUploadDisposition { ACK, DISCARD, REVOKE, RETRY }

/** The sealed platform queue uses the shell's bounds and terminal HTTP decisions. */
object LocationQueuePolicy {
    const val MAX_AGE_MS = 29L * 24 * 3_600_000
    const val MAX_POINTS = 20_000
    const val BATCH_SIZE = 300
    fun expired(expiresAt: String, nowMillis: Long): Boolean =
        runCatching { Instant.parse(expiresAt).toEpochMilliseconds() <= nowMillis }.getOrDefault(true)
    fun expiredPoint(capturedAtMillis: Long, nowMillis: Long): Boolean = capturedAtMillis < nowMillis - MAX_AGE_MS
    /** A success may acknowledge server duplicates after a lost reply. Every sent row must be covered. */
    fun acknowledges(acknowledgement: LocationAcknowledgement?, batchSize: Int): Boolean =
        batchSize in 1..BATCH_SIZE && acknowledgement != null && acknowledgement.accepted in 0..batchSize &&
            acknowledgement.duplicates == batchSize - acknowledgement.accepted

    /** Only a complete receipt lets the sealed queue erase rows; an incomplete 2xx is retried unchanged. */
    fun uploadDisposition(status: Int, acknowledgement: LocationAcknowledgement?, batchSize: Int): LocationUploadDisposition {
        val disposition = retryDisposition(status)
        return if (disposition == LocationUploadDisposition.ACK && !acknowledges(acknowledgement, batchSize))
            LocationUploadDisposition.RETRY else disposition
    }

    /** The phone can enforce parent-session expiry offline even when the server grant lasts longer. */
    fun effectiveCollectorExpiry(serverExpiresAt: String, parentSessionExpiresAt: String): String =
        if (Instant.parse(parentSessionExpiresAt) < Instant.parse(serverExpiresAt)) parentSessionExpiresAt else serverExpiresAt

    fun retryDisposition(status: Int): LocationUploadDisposition = when (status) {
        in 200..299 -> LocationUploadDisposition.ACK
        401, 403 -> LocationUploadDisposition.REVOKE
        413, 422 -> LocationUploadDisposition.DISCARD
        else -> LocationUploadDisposition.RETRY
    }
}
