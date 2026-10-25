package assistant.mobile.android

import androidx.compose.runtime.Composable
import assistant.mobile.core.GeneratedStrings
import assistant.mobile.core.LocalizedString

@Composable
internal fun personalSourceRowLabel(key: String): String = localized(when (key) {
    "steps" -> GeneratedStrings.Sources.Row.steps
    "active_minutes" -> GeneratedStrings.Sources.Row.active_minutes
    "active_energy" -> GeneratedStrings.Sources.Row.active_energy
    "distance" -> GeneratedStrings.Sources.Row.distance
    "workouts" -> GeneratedStrings.Sources.Row.workouts
    "sleep" -> GeneratedStrings.Sources.Row.sleep
    "heart_rate" -> GeneratedStrings.Sources.Row.heart_rate
    "hrv" -> GeneratedStrings.Sources.Row.hrv
    "spo2" -> GeneratedStrings.Sources.Row.spo2
    "temperature" -> GeneratedStrings.Sources.Row.temperature
    "stress" -> GeneratedStrings.Sources.Row.stress
    "weight" -> GeneratedStrings.Sources.Row.weight
    "body" -> GeneratedStrings.Sources.Row.body
    else -> GeneratedStrings.Sources.data
})

internal fun personalDdxLoginLabel(state: String): LocalizedString = when (state) {
    "sending" -> GeneratedStrings.Personal.Ddx.Login.sending
    "waiting_code" -> GeneratedStrings.Personal.Ddx.Login.waiting_code
    "verifying" -> GeneratedStrings.Personal.Ddx.Login.verifying
    "staged" -> GeneratedStrings.Personal.Ddx.Login.staged
    "ready" -> GeneratedStrings.Personal.Ddx.Login.ready
    "cancelled" -> GeneratedStrings.Personal.Ddx.Login.cancelled
    "expired" -> GeneratedStrings.Personal.Ddx.Login.expired
    "interrupted" -> GeneratedStrings.Personal.Ddx.Login.interrupted
    else -> GeneratedStrings.Personal.Ddx.Login.failed
}

internal fun personalDdxRunLabel(state: String): LocalizedString = when (state) {
    "queued" -> GeneratedStrings.Personal.Ddx.Run.queued
    "running" -> GeneratedStrings.Personal.Ddx.Run.running
    "succeeded" -> GeneratedStrings.Personal.Ddx.Run.succeeded
    "partial" -> GeneratedStrings.Personal.Ddx.Run.partial
    "cancelled" -> GeneratedStrings.Personal.Ddx.Run.cancelled
    else -> GeneratedStrings.Personal.Ddx.Run.failed
}

internal fun personalDdxErrorLabel(code: String): LocalizedString = when (code) {
    "invalid_phone" -> GeneratedStrings.Personal.Ddx.Error.invalid_phone
    "invalid_verification_code" -> GeneratedStrings.Personal.Ddx.Error.invalid_verification_code
    "invalid_code" -> GeneratedStrings.Personal.Ddx.Error.invalid_code
    "invalid_request" -> GeneratedStrings.Personal.Ddx.Error.invalid_request
    "request_changed" -> GeneratedStrings.Personal.Ddx.Error.request_changed
    "login_in_progress" -> GeneratedStrings.Personal.Ddx.Error.login_in_progress
    "already_connected" -> GeneratedStrings.Personal.Ddx.Error.already_connected
    "connection_changed" -> GeneratedStrings.Personal.Ddx.Error.connection_changed
    "same_account" -> GeneratedStrings.Personal.Ddx.Error.same_account
    "resend_cooldown" -> GeneratedStrings.Personal.Ddx.Error.resend_cooldown
    "too_many_attempts" -> GeneratedStrings.Personal.Ddx.Error.too_many_attempts
    "login_unavailable" -> GeneratedStrings.Personal.Ddx.Error.login_unavailable
    "provider_unavailable" -> GeneratedStrings.Personal.Ddx.Error.provider_unavailable
    "interrupted" -> GeneratedStrings.Personal.Ddx.Error.interrupted
    "code_expired" -> GeneratedStrings.Personal.Ddx.Error.code_expired
    "account_mismatch" -> GeneratedStrings.Personal.Ddx.Error.account_mismatch
    "enrollment_failed" -> GeneratedStrings.Personal.Ddx.Error.enrollment_failed
    "connection_unavailable" -> GeneratedStrings.Personal.Ddx.Error.connection_unavailable
    "transport_failed" -> GeneratedStrings.Personal.Ddx.Error.transport_failed
    "token_storage_failed" -> GeneratedStrings.Personal.Ddx.Error.token_storage_failed
    "network_error" -> GeneratedStrings.Personal.Ddx.Error.network_error
    "rate_limited" -> GeneratedStrings.Personal.Ddx.Error.rate_limited
    "login_expired" -> GeneratedStrings.Personal.Ddx.Error.login_expired
    "login_busy" -> GeneratedStrings.Personal.Ddx.Error.login_busy
    "revision_conflict" -> GeneratedStrings.Personal.Ddx.Error.revision_conflict
    "generation_conflict" -> GeneratedStrings.Personal.Ddx.Error.generation_conflict
    "automatic_disabled" -> GeneratedStrings.Personal.Ddx.Error.automatic_disabled
    "connection_busy" -> GeneratedStrings.Personal.Ddx.Error.connection_busy
    "auth_required" -> GeneratedStrings.Personal.Ddx.Error.auth_required
    "history_restricted" -> GeneratedStrings.Personal.SourceError.history_restricted
    "projection_incomplete" -> GeneratedStrings.Personal.SourceError.projection_incomplete
    else -> GeneratedStrings.Personal.Ddx.Error.request_failed
}
