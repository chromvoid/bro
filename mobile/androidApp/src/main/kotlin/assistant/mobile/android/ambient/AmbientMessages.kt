package assistant.mobile.android.ambient

import android.content.Context
import assistant.mobile.android.R

import java.io.IOException

class AmbientProblem(val code: String) : IOException(code)

object AmbientMessages {
    /** Slow uploads and throttling keep the same unacknowledged batch and recording alive. */
    fun retryableStatus(status: Int): Boolean = status == 408 || status == 429 || (status in 500..599 && status != 507)

    fun resource(code: String): Int = when (code) {
        "not_connected" -> R.string.ambient_error_not_connected
        "not_authenticated" -> R.string.ambient_error_not_authenticated
        "previous_queue" -> R.string.ambient_error_previous_queue
        "permission_denied" -> R.string.ambient_error_permission_denied
        "codec_unavailable" -> R.string.ambient_error_codec_unavailable
        "vad_unavailable" -> R.string.ambient_error_vad_unavailable
        "microphone_busy" -> R.string.ambient_error_microphone_busy
        "interrupted" -> R.string.ambient_error_interrupted
        "queue_full" -> R.string.ambient_error_queue_full
        "queue_corrupt" -> R.string.ambient_error_queue_corrupt
        "network_unavailable" -> R.string.ambient_error_network_unavailable
        "invalid_receipt" -> R.string.ambient_error_invalid_receipt
        "server_rejected" -> R.string.ambient_error_server_rejected
        "batch_expired" -> R.string.ambient_error_batch_expired
        else -> R.string.ambient_error_capture_failed
    }

    fun text(context: Context, code: String): String = context.getString(resource(code))

    fun code(failure: Exception): String = when (failure) {
        is AmbientProblem -> failure.code
        is QueueFullException -> "queue_full"
        is QueueCorruptException -> "queue_corrupt"
        is AmbientHttpException -> when (failure.status) {
            401 -> "not_authenticated"
            410 -> "batch_expired"
            408, 429 -> "network_unavailable"
            in 300..499, 507 -> "server_rejected"
            else -> "network_unavailable"
        }
        is IOException -> "network_unavailable"
        else -> "capture_failed"
    }
}
