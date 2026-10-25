// Generated from web/src/i18n.ts and component-scoped locale imports by python3 web/tooling/generate_android_strings.py; do not edit.
package assistant.mobile.core

import kotlin.math.abs
import kotlin.math.floor

data class StringKey(
    val webKey: String,
    val resourceName: String,
    val parameters: List<String>,
    val pluralForms: Int,
)

/** Placeholder values are caller-formatted text, never guessed numeric types. */
data class LocalizedString(
    val key: StringKey,
    val arguments: List<String> = emptyList(),
    val quantity: Double? = null,
)

/** Same choice semantics as russianPlural in web/src/i18n.ts, including explicit zero. */
object RussianPlural {
    fun index(choice: Double, forms: Int): Int {
        require(choice.isFinite() && forms in 1..4)
        if (forms < 3) return minOf(if (choice == 1.0) 0 else 1, forms - 1)
        if (forms == 4 && choice == 0.0) return 0
        val absolute = abs(choice)
        val whole = floor(absolute)
        // Intl defaults to 3 fraction digits and halfExpand. Only rounding to
        // an integer changes the Russian category; other fractions stay other.
        // Compare before subtracting/multiplying to keep 1.0005 and huge values exact.
        val rounded = when {
            absolute == whole -> absolute
            absolute < whole + 0.0005 -> whole
            absolute >= whole + 0.9995 -> whole + 1.0
            else -> absolute
        }
        val category = when {
            rounded % 1.0 != 0.0 -> 2
            rounded % 10.0 == 1.0 && rounded % 100.0 != 11.0 -> 0
            rounded % 10.0 in 2.0..4.0 && rounded % 100.0 !in 12.0..14.0 -> 1
            else -> 2
        }
        return category + if (forms == 4) 1 else 0
    }
}

/** Every leaf of the effective web tree, including indexed array entries. */
object GeneratedStrings {
    object `AmbientAudio` {
        val `title`: LocalizedString get() = LocalizedString(
            key = StringKey("ambientAudio.title", "ambient_audio_title", emptyList(), 1),
        )
        val `description`: LocalizedString get() = LocalizedString(
            key = StringKey("ambientAudio.description", "ambient_audio_description", emptyList(), 1),
        )
        val `retention`: LocalizedString get() = LocalizedString(
            key = StringKey("ambientAudio.retention", "ambient_audio_retention", emptyList(), 1),
        )
        val `recording`: LocalizedString get() = LocalizedString(
            key = StringKey("ambientAudio.recording", "ambient_audio_recording", emptyList(), 1),
        )
        val `stopped`: LocalizedString get() = LocalizedString(
            key = StringKey("ambientAudio.stopped", "ambient_audio_stopped", emptyList(), 1),
        )
        fun `queue`(`count`: String): LocalizedString = LocalizedString(
            key = StringKey("ambientAudio.queue", "ambient_audio_queue", listOf("count"), 1),
            arguments = listOf(`count`),
        )
        val `offline`: LocalizedString get() = LocalizedString(
            key = StringKey("ambientAudio.offline", "ambient_audio_offline", emptyList(), 1),
        )
        fun `uploaded`(`when`: String): LocalizedString = LocalizedString(
            key = StringKey("ambientAudio.uploaded", "ambient_audio_uploaded", listOf("when"), 1),
            arguments = listOf(`when`),
        )
        val `controls`: LocalizedString get() = LocalizedString(
            key = StringKey("ambientAudio.controls", "ambient_audio_controls", emptyList(), 1),
        )
        val `stop`: LocalizedString get() = LocalizedString(
            key = StringKey("ambientAudio.stop", "ambient_audio_stop", emptyList(), 1),
        )
        object `Errors` {
            val `permission`: LocalizedString get() = LocalizedString(
                key = StringKey("ambientAudio.errors.permission", "ambient_audio_errors_permission", emptyList(), 1),
            )
            val `session`: LocalizedString get() = LocalizedString(
                key = StringKey("ambientAudio.errors.session", "ambient_audio_errors_session", emptyList(), 1),
            )
            val `full`: LocalizedString get() = LocalizedString(
                key = StringKey("ambientAudio.errors.full", "ambient_audio_errors_full", emptyList(), 1),
            )
            val `interrupted`: LocalizedString get() = LocalizedString(
                key = StringKey("ambientAudio.errors.interrupted", "ambient_audio_errors_interrupted", emptyList(), 1),
            )
            val `upload`: LocalizedString get() = LocalizedString(
                key = StringKey("ambientAudio.errors.upload", "ambient_audio_errors_upload", emptyList(), 1),
            )
            val `queue`: LocalizedString get() = LocalizedString(
                key = StringKey("ambientAudio.errors.queue", "ambient_audio_errors_queue", emptyList(), 1),
            )
            val `server`: LocalizedString get() = LocalizedString(
                key = StringKey("ambientAudio.errors.server", "ambient_audio_errors_server", emptyList(), 1),
            )
            val `codec`: LocalizedString get() = LocalizedString(
                key = StringKey("ambientAudio.errors.codec", "ambient_audio_errors_codec", emptyList(), 1),
            )
            val `vad`: LocalizedString get() = LocalizedString(
                key = StringKey("ambientAudio.errors.vad", "ambient_audio_errors_vad", emptyList(), 1),
            )
            val `capture`: LocalizedString get() = LocalizedString(
                key = StringKey("ambientAudio.errors.capture", "ambient_audio_errors_capture", emptyList(), 1),
            )
            val `unknown`: LocalizedString get() = LocalizedString(
                key = StringKey("ambientAudio.errors.unknown", "ambient_audio_errors_unknown", emptyList(), 1),
            )
        }
        object `Control` {
            val `idle`: LocalizedString get() = LocalizedString(
                key = StringKey("ambientAudio.control.idle", "ambient_audio_control_idle", emptyList(), 1),
            )
            val `recording`: LocalizedString get() = LocalizedString(
                key = StringKey("ambientAudio.control.recording", "ambient_audio_control_recording", emptyList(), 1),
            )
            val `error`: LocalizedString get() = LocalizedString(
                key = StringKey("ambientAudio.control.error", "ambient_audio_control_error", emptyList(), 1),
            )
        }
    }
    object `App` {
        val `title`: LocalizedString get() = LocalizedString(
            key = StringKey("app.title", "app_title", emptyList(), 1),
        )
        val `skip`: LocalizedString get() = LocalizedString(
            key = StringKey("app.skip", "app_skip", emptyList(), 1),
        )
        val `notFound`: LocalizedString get() = LocalizedString(
            key = StringKey("app.notFound", "app_not_found", emptyList(), 1),
        )
        val `notFoundText`: LocalizedString get() = LocalizedString(
            key = StringKey("app.notFoundText", "app_not_found_text", emptyList(), 1),
        )
    }
    object `Nav` {
        val `main`: LocalizedString get() = LocalizedString(
            key = StringKey("nav.main", "nav_main", emptyList(), 1),
        )
        val `today`: LocalizedString get() = LocalizedString(
            key = StringKey("nav.today", "nav_today", emptyList(), 1),
        )
        val `assistant`: LocalizedString get() = LocalizedString(
            key = StringKey("nav.assistant", "nav_assistant", emptyList(), 1),
        )
        val `food`: LocalizedString get() = LocalizedString(
            key = StringKey("nav.food", "nav_food", emptyList(), 1),
        )
        val `tasks`: LocalizedString get() = LocalizedString(
            key = StringKey("nav.tasks", "nav_tasks", emptyList(), 1),
        )
        val `timers`: LocalizedString get() = LocalizedString(
            key = StringKey("nav.timers", "nav_timers", emptyList(), 1),
        )
        val `circle`: LocalizedString get() = LocalizedString(
            key = StringKey("nav.circle", "nav_circle", emptyList(), 1),
        )
        val `personal`: LocalizedString get() = LocalizedString(
            key = StringKey("nav.personal", "nav_personal", emptyList(), 1),
        )
        val `settings`: LocalizedString get() = LocalizedString(
            key = StringKey("nav.settings", "nav_settings", emptyList(), 1),
        )
        val `admin`: LocalizedString get() = LocalizedString(
            key = StringKey("nav.admin", "nav_admin", emptyList(), 1),
        )
        val `accounts`: LocalizedString get() = LocalizedString(
            key = StringKey("nav.accounts", "nav_accounts", emptyList(), 1),
        )
        val `workers`: LocalizedString get() = LocalizedString(
            key = StringKey("nav.workers", "nav_workers", emptyList(), 1),
        )
        val `activity`: LocalizedString get() = LocalizedString(
            key = StringKey("nav.activity", "nav_activity", emptyList(), 1),
        )
        val `recordings`: LocalizedString get() = LocalizedString(
            key = StringKey("nav.recordings", "nav_recordings", emptyList(), 1),
        )
        val `places`: LocalizedString get() = LocalizedString(
            key = StringKey("nav.places", "nav_places", emptyList(), 1),
        )
        val `more`: LocalizedString get() = LocalizedString(
            key = StringKey("nav.more", "nav_more", emptyList(), 1),
        )
        val `moreSections`: LocalizedString get() = LocalizedString(
            key = StringKey("nav.moreSections", "nav_more_sections", emptyList(), 1),
        )
    }
    object `Profile` {
        val `open`: LocalizedString get() = LocalizedString(
            key = StringKey("profile.open", "profile_open", emptyList(), 1),
        )
        val `title`: LocalizedString get() = LocalizedString(
            key = StringKey("profile.title", "profile_title", emptyList(), 1),
        )
        val `sections`: LocalizedString get() = LocalizedString(
            key = StringKey("profile.sections", "profile_sections", emptyList(), 1),
        )
        val `logout`: LocalizedString get() = LocalizedString(
            key = StringKey("profile.logout", "profile_logout", emptyList(), 1),
        )
    }
    object `Theme` {
        val `title`: LocalizedString get() = LocalizedString(
            key = StringKey("theme.title", "theme_title", emptyList(), 1),
        )
        val `system`: LocalizedString get() = LocalizedString(
            key = StringKey("theme.system", "theme_system", emptyList(), 1),
        )
        val `light`: LocalizedString get() = LocalizedString(
            key = StringKey("theme.light", "theme_light", emptyList(), 1),
        )
        val `dark`: LocalizedString get() = LocalizedString(
            key = StringKey("theme.dark", "theme_dark", emptyList(), 1),
        )
    }
    object `NativeAccount` {
        val `openManagement`: LocalizedString get() = LocalizedString(
            key = StringKey("nativeAccount.openManagement", "native_account_open_management", emptyList(), 1),
        )
        fun `version`(`version`: String): LocalizedString = LocalizedString(
            key = StringKey("nativeAccount.version", "native_account_version", listOf("version"), 1),
            arguments = listOf(`version`),
        )
    }
    object `NativeServer` {
        val `title`: LocalizedString get() = LocalizedString(
            key = StringKey("nativeServer.title", "native_server_title", emptyList(), 1),
        )
        fun `selected`(`name`: String, `host`: String): LocalizedString = LocalizedString(
            key = StringKey("nativeServer.selected", "native_server_selected", listOf("name", "host"), 1),
            arguments = listOf(`name`, `host`),
        )
        val `change`: LocalizedString get() = LocalizedString(
            key = StringKey("nativeServer.change", "native_server_change", emptyList(), 1),
        )
        val `inviteTitle`: LocalizedString get() = LocalizedString(
            key = StringKey("nativeServer.inviteTitle", "native_server_invite_title", emptyList(), 1),
        )
        val `inviteInput`: LocalizedString get() = LocalizedString(
            key = StringKey("nativeServer.inviteInput", "native_server_invite_input", emptyList(), 1),
        )
        val `ownTitle`: LocalizedString get() = LocalizedString(
            key = StringKey("nativeServer.ownTitle", "native_server_own_title", emptyList(), 1),
        )
        val `address`: LocalizedString get() = LocalizedString(
            key = StringKey("nativeServer.address", "native_server_address", emptyList(), 1),
        )
        val `addressPlaceholder`: LocalizedString get() = LocalizedString(
            key = StringKey("nativeServer.addressPlaceholder", "native_server_address_placeholder", emptyList(), 1),
        )
        val `inviteCode`: LocalizedString get() = LocalizedString(
            key = StringKey("nativeServer.inviteCode", "native_server_invite_code", emptyList(), 1),
        )
        val `check`: LocalizedString get() = LocalizedString(
            key = StringKey("nativeServer.check", "native_server_check", emptyList(), 1),
        )
        val `connect`: LocalizedString get() = LocalizedString(
            key = StringKey("nativeServer.connect", "native_server_connect", emptyList(), 1),
        )
        val `returnDefault`: LocalizedString get() = LocalizedString(
            key = StringKey("nativeServer.returnDefault", "native_server_return_default", emptyList(), 1),
        )
        val `switchTitle`: LocalizedString get() = LocalizedString(
            key = StringKey("nativeServer.switchTitle", "native_server_switch_title", emptyList(), 1),
        )
        fun `switchMessage`(`name`: String): LocalizedString = LocalizedString(
            key = StringKey("nativeServer.switchMessage", "native_server_switch_message", listOf("name"), 1),
            arguments = listOf(`name`),
        )
        val `switchAction`: LocalizedString get() = LocalizedString(
            key = StringKey("nativeServer.switchAction", "native_server_switch_action", emptyList(), 1),
        )
        object `Errors` {
            val `invalidAddress`: LocalizedString get() = LocalizedString(
                key = StringKey("nativeServer.errors.invalidAddress", "native_server_errors_invalid_address", emptyList(), 1),
            )
            val `unreachable`: LocalizedString get() = LocalizedString(
                key = StringKey("nativeServer.errors.unreachable", "native_server_errors_unreachable", emptyList(), 1),
            )
            val `notBro`: LocalizedString get() = LocalizedString(
                key = StringKey("nativeServer.errors.notBro", "native_server_errors_not_bro", emptyList(), 1),
            )
            val `unsupported`: LocalizedString get() = LocalizedString(
                key = StringKey("nativeServer.errors.unsupported", "native_server_errors_unsupported", emptyList(), 1),
            )
            val `appTooOld`: LocalizedString get() = LocalizedString(
                key = StringKey("nativeServer.errors.appTooOld", "native_server_errors_app_too_old", emptyList(), 1),
            )
            val `firmwareUpdateActive`: LocalizedString get() = LocalizedString(
                key = StringKey("nativeServer.errors.firmwareUpdateActive", "native_server_errors_firmware_update_active", emptyList(), 1),
            )
            val `eraseFailed`: LocalizedString get() = LocalizedString(
                key = StringKey("nativeServer.errors.eraseFailed", "native_server_errors_erase_failed", emptyList(), 1),
            )
        }
    }
    object `Common` {
        val `back`: LocalizedString get() = LocalizedString(
            key = StringKey("common.back", "common_back", emptyList(), 1),
        )
        val `retry`: LocalizedString get() = LocalizedString(
            key = StringKey("common.retry", "common_retry", emptyList(), 1),
        )
        val `cancel`: LocalizedString get() = LocalizedString(
            key = StringKey("common.cancel", "common_cancel", emptyList(), 1),
        )
        val `save`: LocalizedString get() = LocalizedString(
            key = StringKey("common.save", "common_save", emptyList(), 1),
        )
        val `close`: LocalizedString get() = LocalizedString(
            key = StringKey("common.close", "common_close", emptyList(), 1),
        )
        val `delete`: LocalizedString get() = LocalizedString(
            key = StringKey("common.delete", "common_delete", emptyList(), 1),
        )
        val `edit`: LocalizedString get() = LocalizedString(
            key = StringKey("common.edit", "common_edit", emptyList(), 1),
        )
        val `continue`: LocalizedString get() = LocalizedString(
            key = StringKey("common.continue", "common_continue", emptyList(), 1),
        )
        val `done`: LocalizedString get() = LocalizedString(
            key = StringKey("common.done", "common_done", emptyList(), 1),
        )
        val `more`: LocalizedString get() = LocalizedString(
            key = StringKey("common.more", "common_more", emptyList(), 1),
        )
        val `dataNotes`: LocalizedString get() = LocalizedString(
            key = StringKey("common.dataNotes", "common_data_notes", emptyList(), 1),
        )
        val `dateNotSet`: LocalizedString get() = LocalizedString(
            key = StringKey("common.dateNotSet", "common_date_not_set", emptyList(), 1),
        )
        val `clearDate`: LocalizedString get() = LocalizedString(
            key = StringKey("common.clearDate", "common_clear_date", emptyList(), 1),
        )
        val `search`: LocalizedString get() = LocalizedString(
            key = StringKey("common.search", "common_search", emptyList(), 1),
        )
        val `undo`: LocalizedString get() = LocalizedString(
            key = StringKey("common.undo", "common_undo", emptyList(), 1),
        )
        val `undone`: LocalizedString get() = LocalizedString(
            key = StringKey("common.undone", "common_undone", emptyList(), 1),
        )
        val `undoFailed`: LocalizedString get() = LocalizedString(
            key = StringKey("common.undoFailed", "common_undo_failed", emptyList(), 1),
        )
    }
    object `Day` {
        val `pick`: LocalizedString get() = LocalizedString(
            key = StringKey("day.pick", "day_pick", emptyList(), 1),
        )
        val `previous`: LocalizedString get() = LocalizedString(
            key = StringKey("day.previous", "day_previous", emptyList(), 1),
        )
        val `next`: LocalizedString get() = LocalizedString(
            key = StringKey("day.next", "day_next", emptyList(), 1),
        )
        val `today`: LocalizedString get() = LocalizedString(
            key = StringKey("day.today", "day_today", emptyList(), 1),
        )
        val `yesterday`: LocalizedString get() = LocalizedString(
            key = StringKey("day.yesterday", "day_yesterday", emptyList(), 1),
        )
        val `tomorrow`: LocalizedString get() = LocalizedString(
            key = StringKey("day.tomorrow", "day_tomorrow", emptyList(), 1),
        )
    }
    object `State` {
        val `loading`: LocalizedString get() = LocalizedString(
            key = StringKey("state.loading", "state_loading", emptyList(), 1),
        )
        val `errorTitle`: LocalizedString get() = LocalizedString(
            key = StringKey("state.errorTitle", "state_error_title", emptyList(), 1),
        )
        val `refreshFailed`: LocalizedString get() = LocalizedString(
            key = StringKey("state.refreshFailed", "state_refresh_failed", emptyList(), 1),
        )
        val `noAccessTitle`: LocalizedString get() = LocalizedString(
            key = StringKey("state.noAccessTitle", "state_no_access_title", emptyList(), 1),
        )
        val `noAccessText`: LocalizedString get() = LocalizedString(
            key = StringKey("state.noAccessText", "state_no_access_text", emptyList(), 1),
        )
        val `stale`: LocalizedString get() = LocalizedString(
            key = StringKey("state.stale", "state_stale", emptyList(), 1),
        )
    }
    object `Outcome` {
        val `unknownTitle`: LocalizedString get() = LocalizedString(
            key = StringKey("outcome.unknownTitle", "outcome_unknown_title", emptyList(), 1),
        )
        val `unknownText`: LocalizedString get() = LocalizedString(
            key = StringKey("outcome.unknownText", "outcome_unknown_text", emptyList(), 1),
        )
        val `check`: LocalizedString get() = LocalizedString(
            key = StringKey("outcome.check", "outcome_check", emptyList(), 1),
        )
        val `resend`: LocalizedString get() = LocalizedString(
            key = StringKey("outcome.resend", "outcome_resend", emptyList(), 1),
        )
        val `abandon`: LocalizedString get() = LocalizedString(
            key = StringKey("outcome.abandon", "outcome_abandon", emptyList(), 1),
        )
    }
    object `Errors` {
        val `network`: LocalizedString get() = LocalizedString(
            key = StringKey("errors.network", "errors_network", emptyList(), 1),
        )
        val `timeout`: LocalizedString get() = LocalizedString(
            key = StringKey("errors.timeout", "errors_timeout", emptyList(), 1),
        )
        val `session`: LocalizedString get() = LocalizedString(
            key = StringKey("errors.session", "errors_session", emptyList(), 1),
        )
        val `forbidden`: LocalizedString get() = LocalizedString(
            key = StringKey("errors.forbidden", "errors_forbidden", emptyList(), 1),
        )
        val `notFound`: LocalizedString get() = LocalizedString(
            key = StringKey("errors.notFound", "errors_not_found", emptyList(), 1),
        )
        val `conflict`: LocalizedString get() = LocalizedString(
            key = StringKey("errors.conflict", "errors_conflict", emptyList(), 1),
        )
        val `tooLarge`: LocalizedString get() = LocalizedString(
            key = StringKey("errors.tooLarge", "errors_too_large", emptyList(), 1),
        )
        val `invalid`: LocalizedString get() = LocalizedString(
            key = StringKey("errors.invalid", "errors_invalid", emptyList(), 1),
        )
        val `limit`: LocalizedString get() = LocalizedString(
            key = StringKey("errors.limit", "errors_limit", emptyList(), 1),
        )
        val `server`: LocalizedString get() = LocalizedString(
            key = StringKey("errors.server", "errors_server", emptyList(), 1),
        )
        val `unknown`: LocalizedString get() = LocalizedString(
            key = StringKey("errors.unknown", "errors_unknown", emptyList(), 1),
        )
    }
    object `Status` {
        val `title`: LocalizedString get() = LocalizedString(
            key = StringKey("status.title", "status_title", emptyList(), 1),
        )
        fun `more`(`count`: String): LocalizedString = LocalizedString(
            key = StringKey("status.more", "status_more", listOf("count"), 1),
            arguments = listOf(`count`),
        )
        val `open`: LocalizedString get() = LocalizedString(
            key = StringKey("status.open", "status_open", emptyList(), 1),
        )
        object `Ambient` {
            val `recording`: LocalizedString get() = LocalizedString(
                key = StringKey("status.ambient.recording", "status_ambient_recording", emptyList(), 1),
            )
            val `uploadIssue`: LocalizedString get() = LocalizedString(
                key = StringKey("status.ambient.uploadIssue", "status_ambient_upload_issue", emptyList(), 1),
            )
            val `stopped`: LocalizedString get() = LocalizedString(
                key = StringKey("status.ambient.stopped", "status_ambient_stopped", emptyList(), 1),
            )
            fun `queued`(`count`: String): LocalizedString = LocalizedString(
                key = StringKey("status.ambient.queued", "status_ambient_queued", listOf("count"), 1),
                arguments = listOf(`count`),
            )
        }
        val `offline`: LocalizedString get() = LocalizedString(
            key = StringKey("status.offline", "status_offline", emptyList(), 1),
        )
        object `Speech` {
            val `failed`: LocalizedString get() = LocalizedString(
                key = StringKey("status.speech.failed", "status_speech_failed", emptyList(), 1),
            )
        }
    }
    object `Connection` {
        val `offline`: LocalizedString get() = LocalizedString(
            key = StringKey("connection.offline", "connection_offline", emptyList(), 1),
        )
        val `unavailableTitle`: LocalizedString get() = LocalizedString(
            key = StringKey("connection.unavailableTitle", "connection_unavailable_title", emptyList(), 1),
        )
    }
    object `Auth` {
        val `title`: LocalizedString get() = LocalizedString(
            key = StringKey("auth.title", "auth_title", emptyList(), 1),
        )
        val `joinTitle`: LocalizedString get() = LocalizedString(
            key = StringKey("auth.joinTitle", "auth_join_title", emptyList(), 1),
        )
        val `joinResetTitle`: LocalizedString get() = LocalizedString(
            key = StringKey("auth.joinResetTitle", "auth_join_reset_title", emptyList(), 1),
        )
        val `downloadAndroid`: LocalizedString get() = LocalizedString(
            key = StringKey("auth.downloadAndroid", "auth_download_android", emptyList(), 1),
        )
        val `continueInBrowser`: LocalizedString get() = LocalizedString(
            key = StringKey("auth.continueInBrowser", "auth_continue_in_browser", emptyList(), 1),
        )
        val `welcomeBack`: LocalizedString get() = LocalizedString(
            key = StringKey("auth.welcomeBack", "auth_welcome_back", emptyList(), 1),
        )
        val `signInHint`: LocalizedString get() = LocalizedString(
            key = StringKey("auth.signInHint", "auth_sign_in_hint", emptyList(), 1),
        )
        val `login`: LocalizedString get() = LocalizedString(
            key = StringKey("auth.login", "auth_login", emptyList(), 1),
        )
        val `password`: LocalizedString get() = LocalizedString(
            key = StringKey("auth.password", "auth_password", emptyList(), 1),
        )
        val `signIn`: LocalizedString get() = LocalizedString(
            key = StringKey("auth.signIn", "auth_sign_in", emptyList(), 1),
        )
        val `signingIn`: LocalizedString get() = LocalizedString(
            key = StringKey("auth.signingIn", "auth_signing_in", emptyList(), 1),
        )
        val `showPassword`: LocalizedString get() = LocalizedString(
            key = StringKey("auth.showPassword", "auth_show_password", emptyList(), 1),
        )
        val `hidePassword`: LocalizedString get() = LocalizedString(
            key = StringKey("auth.hidePassword", "auth_hide_password", emptyList(), 1),
        )
        val `checkTitle`: LocalizedString get() = LocalizedString(
            key = StringKey("auth.checkTitle", "auth_check_title", emptyList(), 1),
        )
        val `checkText`: LocalizedString get() = LocalizedString(
            key = StringKey("auth.checkText", "auth_check_text", emptyList(), 1),
        )
        val `failedTitle`: LocalizedString get() = LocalizedString(
            key = StringKey("auth.failedTitle", "auth_failed_title", emptyList(), 1),
        )
        val `pauseTitle`: LocalizedString get() = LocalizedString(
            key = StringKey("auth.pauseTitle", "auth_pause_title", emptyList(), 1),
        )
        fun `pauseText`(`seconds`: String): LocalizedString = LocalizedString(
            key = StringKey("auth.pauseText", "auth_pause_text", listOf("seconds"), 1),
            arguments = listOf(`seconds`),
        )
        val `expiredTitle`: LocalizedString get() = LocalizedString(
            key = StringKey("auth.expiredTitle", "auth_expired_title", emptyList(), 1),
        )
        val `expiredText`: LocalizedString get() = LocalizedString(
            key = StringKey("auth.expiredText", "auth_expired_text", emptyList(), 1),
        )
        val `forgot`: LocalizedString get() = LocalizedString(
            key = StringKey("auth.forgot", "auth_forgot", emptyList(), 1),
        )
        val `restoreTitle`: LocalizedString get() = LocalizedString(
            key = StringKey("auth.restoreTitle", "auth_restore_title", emptyList(), 1),
        )
        val `restoreText`: LocalizedString get() = LocalizedString(
            key = StringKey("auth.restoreText", "auth_restore_text", emptyList(), 1),
        )
        val `restoreLink`: LocalizedString get() = LocalizedString(
            key = StringKey("auth.restoreLink", "auth_restore_link", emptyList(), 1),
        )
        val `backToSignIn`: LocalizedString get() = LocalizedString(
            key = StringKey("auth.backToSignIn", "auth_back_to_sign_in", emptyList(), 1),
        )
        val `inviteTitle`: LocalizedString get() = LocalizedString(
            key = StringKey("auth.inviteTitle", "auth_invite_title", emptyList(), 1),
        )
        val `inviteHint`: LocalizedString get() = LocalizedString(
            key = StringKey("auth.inviteHint", "auth_invite_hint", emptyList(), 1),
        )
        val `resetTitle`: LocalizedString get() = LocalizedString(
            key = StringKey("auth.resetTitle", "auth_reset_title", emptyList(), 1),
        )
        val `resetHint`: LocalizedString get() = LocalizedString(
            key = StringKey("auth.resetHint", "auth_reset_hint", emptyList(), 1),
        )
        val `name`: LocalizedString get() = LocalizedString(
            key = StringKey("auth.name", "auth_name", emptyList(), 1),
        )
        val `nameRequired`: LocalizedString get() = LocalizedString(
            key = StringKey("auth.nameRequired", "auth_name_required", emptyList(), 1),
        )
        val `loginRule`: LocalizedString get() = LocalizedString(
            key = StringKey("auth.loginRule", "auth_login_rule", emptyList(), 1),
        )
        fun `ruleBroken`(`rule`: String): LocalizedString = LocalizedString(
            key = StringKey("auth.ruleBroken", "auth_rule_broken", listOf("rule"), 1),
            arguments = listOf(`rule`),
        )
        val `loginTaken`: LocalizedString get() = LocalizedString(
            key = StringKey("auth.loginTaken", "auth_login_taken", emptyList(), 1),
        )
        val `newPassword`: LocalizedString get() = LocalizedString(
            key = StringKey("auth.newPassword", "auth_new_password", emptyList(), 1),
        )
        fun `passwordRule`(`count`: String): LocalizedString = LocalizedString(
            key = StringKey("auth.passwordRule", "auth_password_rule", listOf("count"), 1),
            arguments = listOf(`count`),
        )
        val `repeatPassword`: LocalizedString get() = LocalizedString(
            key = StringKey("auth.repeatPassword", "auth_repeat_password", emptyList(), 1),
        )
        val `repeatMismatch`: LocalizedString get() = LocalizedString(
            key = StringKey("auth.repeatMismatch", "auth_repeat_mismatch", emptyList(), 1),
        )
        val `timezone`: LocalizedString get() = LocalizedString(
            key = StringKey("auth.timezone", "auth_timezone", emptyList(), 1),
        )
        val `timezoneHint`: LocalizedString get() = LocalizedString(
            key = StringKey("auth.timezoneHint", "auth_timezone_hint", emptyList(), 1),
        )
        val `timezoneUnknown`: LocalizedString get() = LocalizedString(
            key = StringKey("auth.timezoneUnknown", "auth_timezone_unknown", emptyList(), 1),
        )
        val `acceptInvite`: LocalizedString get() = LocalizedString(
            key = StringKey("auth.acceptInvite", "auth_accept_invite", emptyList(), 1),
        )
        val `savePassword`: LocalizedString get() = LocalizedString(
            key = StringKey("auth.savePassword", "auth_save_password", emptyList(), 1),
        )
        val `oneTimeLink`: LocalizedString get() = LocalizedString(
            key = StringKey("auth.oneTimeLink", "auth_one_time_link", emptyList(), 1),
        )
        val `resetDoneTitle`: LocalizedString get() = LocalizedString(
            key = StringKey("auth.resetDoneTitle", "auth_reset_done_title", emptyList(), 1),
        )
        val `resetDoneText`: LocalizedString get() = LocalizedString(
            key = StringKey("auth.resetDoneText", "auth_reset_done_text", emptyList(), 1),
        )
        val `linkExpiredTitle`: LocalizedString get() = LocalizedString(
            key = StringKey("auth.linkExpiredTitle", "auth_link_expired_title", emptyList(), 1),
        )
        val `linkExpiredText`: LocalizedString get() = LocalizedString(
            key = StringKey("auth.linkExpiredText", "auth_link_expired_text", emptyList(), 1),
        )
    }
    object `Welcome` {
        fun `title`(`name`: String): LocalizedString = LocalizedString(
            key = StringKey("welcome.title", "welcome_title", listOf("name"), 1),
            arguments = listOf(`name`),
        )
        val `text`: LocalizedString get() = LocalizedString(
            key = StringKey("welcome.text", "welcome_text", emptyList(), 1),
        )
        val `food`: LocalizedString get() = LocalizedString(
            key = StringKey("welcome.food", "welcome_food", emptyList(), 1),
        )
        val `foodText`: LocalizedString get() = LocalizedString(
            key = StringKey("welcome.foodText", "welcome_food_text", emptyList(), 1),
        )
        val `ask`: LocalizedString get() = LocalizedString(
            key = StringKey("welcome.ask", "welcome_ask", emptyList(), 1),
        )
        val `askText`: LocalizedString get() = LocalizedString(
            key = StringKey("welcome.askText", "welcome_ask_text", emptyList(), 1),
        )
        val `task`: LocalizedString get() = LocalizedString(
            key = StringKey("welcome.task", "welcome_task", emptyList(), 1),
        )
        val `taskText`: LocalizedString get() = LocalizedString(
            key = StringKey("welcome.taskText", "welcome_task_text", emptyList(), 1),
        )
        val `later`: LocalizedString get() = LocalizedString(
            key = StringKey("welcome.later", "welcome_later", emptyList(), 1),
        )
    }
    object `Dictation` {
        val `start`: LocalizedString get() = LocalizedString(
            key = StringKey("dictation.start", "dictation_start", emptyList(), 1),
        )
        val `stop`: LocalizedString get() = LocalizedString(
            key = StringKey("dictation.stop", "dictation_stop", emptyList(), 1),
        )
        val `stopDictation`: LocalizedString get() = LocalizedString(
            key = StringKey("dictation.stopDictation", "dictation_stop_dictation", emptyList(), 1),
        )
        val `cancel`: LocalizedString get() = LocalizedString(
            key = StringKey("dictation.cancel", "dictation_cancel", emptyList(), 1),
        )
        val `recording`: LocalizedString get() = LocalizedString(
            key = StringKey("dictation.recording", "dictation_recording", emptyList(), 1),
        )
        val `finalizing`: LocalizedString get() = LocalizedString(
            key = StringKey("dictation.finalizing", "dictation_finalizing", emptyList(), 1),
        )
        val `review`: LocalizedString get() = LocalizedString(
            key = StringKey("dictation.review", "dictation_review", emptyList(), 1),
        )
        val `limit`: LocalizedString get() = LocalizedString(
            key = StringKey("dictation.limit", "dictation_limit", emptyList(), 1),
        )
        val `denied`: LocalizedString get() = LocalizedString(
            key = StringKey("dictation.denied", "dictation_denied", emptyList(), 1),
        )
        val `unavailable`: LocalizedString get() = LocalizedString(
            key = StringKey("dictation.unavailable", "dictation_unavailable", emptyList(), 1),
        )
        val `unsupported`: LocalizedString get() = LocalizedString(
            key = StringKey("dictation.unsupported", "dictation_unsupported", emptyList(), 1),
        )
        val `busy`: LocalizedString get() = LocalizedString(
            key = StringKey("dictation.busy", "dictation_busy", emptyList(), 1),
        )
        val `connection`: LocalizedString get() = LocalizedString(
            key = StringKey("dictation.connection", "dictation_connection", emptyList(), 1),
        )
        val `backgrounded`: LocalizedString get() = LocalizedString(
            key = StringKey("dictation.backgrounded", "dictation_backgrounded", emptyList(), 1),
        )
        val `device`: LocalizedString get() = LocalizedString(
            key = StringKey("dictation.device", "dictation_device", emptyList(), 1),
        )
        val `too_long`: LocalizedString get() = LocalizedString(
            key = StringKey("dictation.too_long", "dictation_too_long", emptyList(), 1),
        )
        val `server`: LocalizedString get() = LocalizedString(
            key = StringKey("dictation.server", "dictation_server", emptyList(), 1),
        )
        val `not_recognized`: LocalizedString get() = LocalizedString(
            key = StringKey("dictation.not_recognized", "dictation_not_recognized", emptyList(), 1),
        )
        fun `elapsed`(`time`: String): LocalizedString = LocalizedString(
            key = StringKey("dictation.elapsed", "dictation_elapsed", listOf("time"), 1),
            arguments = listOf(`time`),
        )
        val `not_configured`: LocalizedString get() = LocalizedString(
            key = StringKey("dictation.not_configured", "dictation_not_configured", emptyList(), 1),
        )
        val `no_access`: LocalizedString get() = LocalizedString(
            key = StringKey("dictation.no_access", "dictation_no_access", emptyList(), 1),
        )
        val `auth_required`: LocalizedString get() = LocalizedString(
            key = StringKey("dictation.auth_required", "dictation_auth_required", emptyList(), 1),
        )
        val `session_expired`: LocalizedString get() = LocalizedString(
            key = StringKey("dictation.session_expired", "dictation_session_expired", emptyList(), 1),
        )
        val `application_limit`: LocalizedString get() = LocalizedString(
            key = StringKey("dictation.application_limit", "dictation_application_limit", emptyList(), 1),
        )
        val `provider_limit`: LocalizedString get() = LocalizedString(
            key = StringKey("dictation.provider_limit", "dictation_provider_limit", emptyList(), 1),
        )
        val `timeout`: LocalizedString get() = LocalizedString(
            key = StringKey("dictation.timeout", "dictation_timeout", emptyList(), 1),
        )
        val `provider_error`: LocalizedString get() = LocalizedString(
            key = StringKey("dictation.provider_error", "dictation_provider_error", emptyList(), 1),
        )
        val `invalid_audio`: LocalizedString get() = LocalizedString(
            key = StringKey("dictation.invalid_audio", "dictation_invalid_audio", emptyList(), 1),
        )
        val `secret_unavailable`: LocalizedString get() = LocalizedString(
            key = StringKey("dictation.secret_unavailable", "dictation_secret_unavailable", emptyList(), 1),
        )
        val `execution_disabled`: LocalizedString get() = LocalizedString(
            key = StringKey("dictation.execution_disabled", "dictation_execution_disabled", emptyList(), 1),
        )
        val `incomplete`: LocalizedString get() = LocalizedString(
            key = StringKey("dictation.incomplete", "dictation_incomplete", emptyList(), 1),
        )
        val `configure`: LocalizedString get() = LocalizedString(
            key = StringKey("dictation.configure", "dictation_configure", emptyList(), 1),
        )
        fun `retryAfter`(`seconds`: String): LocalizedString = LocalizedString(
            key = StringKey("dictation.retryAfter", "dictation_retry_after", listOf("seconds"), 1),
            arguments = listOf(`seconds`),
        )
    }
    object `Voice` {
        val `title`: LocalizedString get() = LocalizedString(
            key = StringKey("voice.title", "voice_title", emptyList(), 1),
        )
        val `hint`: LocalizedString get() = LocalizedString(
            key = StringKey("voice.hint", "voice_hint", emptyList(), 1),
        )
        val `transcript`: LocalizedString get() = LocalizedString(
            key = StringKey("voice.transcript", "voice_transcript", emptyList(), 1),
        )
        val `toChat`: LocalizedString get() = LocalizedString(
            key = StringKey("voice.toChat", "voice_to_chat", emptyList(), 1),
        )
        val `again`: LocalizedString get() = LocalizedString(
            key = StringKey("voice.again", "voice_again", emptyList(), 1),
        )
        val `discard`: LocalizedString get() = LocalizedString(
            key = StringKey("voice.discard", "voice_discard", emptyList(), 1),
        )
        val `typeInstead`: LocalizedString get() = LocalizedString(
            key = StringKey("voice.typeInstead", "voice_type_instead", emptyList(), 1),
        )
        val `saved`: LocalizedString get() = LocalizedString(
            key = StringKey("voice.saved", "voice_saved", emptyList(), 1),
        )
        val `handoffFailed`: LocalizedString get() = LocalizedString(
            key = StringKey("voice.handoffFailed", "voice_handoff_failed", emptyList(), 1),
        )
    }
    object `Assistant` {
        val `title`: LocalizedString get() = LocalizedString(
            key = StringKey("assistant.title", "assistant_title", emptyList(), 1),
        )
        val `placeholder`: LocalizedString get() = LocalizedString(
            key = StringKey("assistant.placeholder", "assistant_placeholder", emptyList(), 1),
        )
        val `messageLabel`: LocalizedString get() = LocalizedString(
            key = StringKey("assistant.messageLabel", "assistant_message_label", emptyList(), 1),
        )
        val `send`: LocalizedString get() = LocalizedString(
            key = StringKey("assistant.send", "assistant_send", emptyList(), 1),
        )
        val `attachPhoto`: LocalizedString get() = LocalizedString(
            key = StringKey("assistant.attachPhoto", "assistant_attach_photo", emptyList(), 1),
        )
        val `photoPreview`: LocalizedString get() = LocalizedString(
            key = StringKey("assistant.photoPreview", "assistant_photo_preview", emptyList(), 1),
        )
        val `photoPreparing`: LocalizedString get() = LocalizedString(
            key = StringKey("assistant.photoPreparing", "assistant_photo_preparing", emptyList(), 1),
        )
        val `photoUploading`: LocalizedString get() = LocalizedString(
            key = StringKey("assistant.photoUploading", "assistant_photo_uploading", emptyList(), 1),
        )
        val `removePhoto`: LocalizedString get() = LocalizedString(
            key = StringKey("assistant.removePhoto", "assistant_remove_photo", emptyList(), 1),
        )
        val `photoUnsupported`: LocalizedString get() = LocalizedString(
            key = StringKey("assistant.photoUnsupported", "assistant_photo_unsupported", emptyList(), 1),
        )
        val `photoTooLarge`: LocalizedString get() = LocalizedString(
            key = StringKey("assistant.photoTooLarge", "assistant_photo_too_large", emptyList(), 1),
        )
        fun `replyingTo`(`text`: String): LocalizedString = LocalizedString(
            key = StringKey("assistant.replyingTo", "assistant_replying_to", listOf("text"), 1),
            arguments = listOf(`text`),
        )
        val `previousReply`: LocalizedString get() = LocalizedString(
            key = StringKey("assistant.previousReply", "assistant_previous_reply", emptyList(), 1),
        )
        val `draftOccupied`: LocalizedString get() = LocalizedString(
            key = StringKey("assistant.draftOccupied", "assistant_draft_occupied", emptyList(), 1),
        )
        val `unsentDraft`: LocalizedString get() = LocalizedString(
            key = StringKey("assistant.unsentDraft", "assistant_unsent_draft", emptyList(), 1),
        )
        val `draftStorageError`: LocalizedString get() = LocalizedString(
            key = StringKey("assistant.draftStorageError", "assistant_draft_storage_error", emptyList(), 1),
        )
        val `draftRestoreError`: LocalizedString get() = LocalizedString(
            key = StringKey("assistant.draftRestoreError", "assistant_draft_restore_error", emptyList(), 1),
        )
        val `recoveredFailure`: LocalizedString get() = LocalizedString(
            key = StringKey("assistant.recoveredFailure", "assistant_recovered_failure", emptyList(), 1),
        )
        val `cancelReply`: LocalizedString get() = LocalizedString(
            key = StringKey("assistant.cancelReply", "assistant_cancel_reply", emptyList(), 1),
        )
        val `reply`: LocalizedString get() = LocalizedString(
            key = StringKey("assistant.reply", "assistant_reply", emptyList(), 1),
        )
        val `answerActions`: LocalizedString get() = LocalizedString(
            key = StringKey("assistant.answerActions", "assistant_answer_actions", emptyList(), 1),
        )
        val `copy`: LocalizedString get() = LocalizedString(
            key = StringKey("assistant.copy", "assistant_copy", emptyList(), 1),
        )
        val `copied`: LocalizedString get() = LocalizedString(
            key = StringKey("assistant.copied", "assistant_copied", emptyList(), 1),
        )
        val `copyFailed`: LocalizedString get() = LocalizedString(
            key = StringKey("assistant.copyFailed", "assistant_copy_failed", emptyList(), 1),
        )
        val `you`: LocalizedString get() = LocalizedString(
            key = StringKey("assistant.you", "assistant_you", emptyList(), 1),
        )
        val `assistant`: LocalizedString get() = LocalizedString(
            key = StringKey("assistant.assistant", "assistant_assistant", emptyList(), 1),
        )
        val `processing`: LocalizedString get() = LocalizedString(
            key = StringKey("assistant.processing", "assistant_processing", emptyList(), 1),
        )
        object `Progress` {
            val `searchingFood`: LocalizedString get() = LocalizedString(
                key = StringKey("assistant.progress.searchingFood", "assistant_progress_searching_food", emptyList(), 1),
            )
            val `searchingPackaged`: LocalizedString get() = LocalizedString(
                key = StringKey("assistant.progress.searchingPackaged", "assistant_progress_searching_packaged", emptyList(), 1),
            )
            val `searchingBarcode`: LocalizedString get() = LocalizedString(
                key = StringKey("assistant.progress.searchingBarcode", "assistant_progress_searching_barcode", emptyList(), 1),
            )
            val `searchingWeb`: LocalizedString get() = LocalizedString(
                key = StringKey("assistant.progress.searchingWeb", "assistant_progress_searching_web", emptyList(), 1),
            )
            val `calculating`: LocalizedString get() = LocalizedString(
                key = StringKey("assistant.progress.calculating", "assistant_progress_calculating", emptyList(), 1),
            )
            val `comparingFood`: LocalizedString get() = LocalizedString(
                key = StringKey("assistant.progress.comparingFood", "assistant_progress_comparing_food", emptyList(), 1),
            )
            val `addingFood`: LocalizedString get() = LocalizedString(
                key = StringKey("assistant.progress.addingFood", "assistant_progress_adding_food", emptyList(), 1),
            )
            val `editingFood`: LocalizedString get() = LocalizedString(
                key = StringKey("assistant.progress.editingFood", "assistant_progress_editing_food", emptyList(), 1),
            )
            val `removingFood`: LocalizedString get() = LocalizedString(
                key = StringKey("assistant.progress.removingFood", "assistant_progress_removing_food", emptyList(), 1),
            )
            val `undoingFood`: LocalizedString get() = LocalizedString(
                key = StringKey("assistant.progress.undoingFood", "assistant_progress_undoing_food", emptyList(), 1),
            )
            val `editingDraft`: LocalizedString get() = LocalizedString(
                key = StringKey("assistant.progress.editingDraft", "assistant_progress_editing_draft", emptyList(), 1),
            )
            val `cancellingDraft`: LocalizedString get() = LocalizedString(
                key = StringKey("assistant.progress.cancellingDraft", "assistant_progress_cancelling_draft", emptyList(), 1),
            )
            val `savingCatalog`: LocalizedString get() = LocalizedString(
                key = StringKey("assistant.progress.savingCatalog", "assistant_progress_saving_catalog", emptyList(), 1),
            )
            val `savingComposition`: LocalizedString get() = LocalizedString(
                key = StringKey("assistant.progress.savingComposition", "assistant_progress_saving_composition", emptyList(), 1),
            )
            val `savingPortion`: LocalizedString get() = LocalizedString(
                key = StringKey("assistant.progress.savingPortion", "assistant_progress_saving_portion", emptyList(), 1),
            )
            val `updatingFavorites`: LocalizedString get() = LocalizedString(
                key = StringKey("assistant.progress.updatingFavorites", "assistant_progress_updating_favorites", emptyList(), 1),
            )
            val `readingDiary`: LocalizedString get() = LocalizedString(
                key = StringKey("assistant.progress.readingDiary", "assistant_progress_reading_diary", emptyList(), 1),
            )
            val `readingDrafts`: LocalizedString get() = LocalizedString(
                key = StringKey("assistant.progress.readingDrafts", "assistant_progress_reading_drafts", emptyList(), 1),
            )
            val `readingCompositions`: LocalizedString get() = LocalizedString(
                key = StringKey("assistant.progress.readingCompositions", "assistant_progress_reading_compositions", emptyList(), 1),
            )
            val `readingProfile`: LocalizedString get() = LocalizedString(
                key = StringKey("assistant.progress.readingProfile", "assistant_progress_reading_profile", emptyList(), 1),
            )
            val `updatingProfile`: LocalizedString get() = LocalizedString(
                key = StringKey("assistant.progress.updatingProfile", "assistant_progress_updating_profile", emptyList(), 1),
            )
            val `savingWeight`: LocalizedString get() = LocalizedString(
                key = StringKey("assistant.progress.savingWeight", "assistant_progress_saving_weight", emptyList(), 1),
            )
            val `preparingGoal`: LocalizedString get() = LocalizedString(
                key = StringKey("assistant.progress.preparingGoal", "assistant_progress_preparing_goal", emptyList(), 1),
            )
            val `reading`: LocalizedString get() = LocalizedString(
                key = StringKey("assistant.progress.reading", "assistant_progress_reading", emptyList(), 1),
            )
            val `researching`: LocalizedString get() = LocalizedString(
                key = StringKey("assistant.progress.researching", "assistant_progress_researching", emptyList(), 1),
            )
            val `lookingUp`: LocalizedString get() = LocalizedString(
                key = StringKey("assistant.progress.lookingUp", "assistant_progress_looking_up", emptyList(), 1),
            )
            val `updatingSources`: LocalizedString get() = LocalizedString(
                key = StringKey("assistant.progress.updatingSources", "assistant_progress_updating_sources", emptyList(), 1),
            )
            val `readingTasks`: LocalizedString get() = LocalizedString(
                key = StringKey("assistant.progress.readingTasks", "assistant_progress_reading_tasks", emptyList(), 1),
            )
            val `changingTasks`: LocalizedString get() = LocalizedString(
                key = StringKey("assistant.progress.changingTasks", "assistant_progress_changing_tasks", emptyList(), 1),
            )
            val `delegatingTask`: LocalizedString get() = LocalizedString(
                key = StringKey("assistant.progress.delegatingTask", "assistant_progress_delegating_task", emptyList(), 1),
            )
            val `answeringTask`: LocalizedString get() = LocalizedString(
                key = StringKey("assistant.progress.answeringTask", "assistant_progress_answering_task", emptyList(), 1),
            )
            val `reviewingTaskResult`: LocalizedString get() = LocalizedString(
                key = StringKey("assistant.progress.reviewingTaskResult", "assistant_progress_reviewing_task_result", emptyList(), 1),
            )
            val `readingTimers`: LocalizedString get() = LocalizedString(
                key = StringKey("assistant.progress.readingTimers", "assistant_progress_reading_timers", emptyList(), 1),
            )
            val `startingTimer`: LocalizedString get() = LocalizedString(
                key = StringKey("assistant.progress.startingTimer", "assistant_progress_starting_timer", emptyList(), 1),
            )
            val `settingReminder`: LocalizedString get() = LocalizedString(
                key = StringKey("assistant.progress.settingReminder", "assistant_progress_setting_reminder", emptyList(), 1),
            )
            val `cancellingTimer`: LocalizedString get() = LocalizedString(
                key = StringKey("assistant.progress.cancellingTimer", "assistant_progress_cancelling_timer", emptyList(), 1),
            )
            val `cancellingReminder`: LocalizedString get() = LocalizedString(
                key = StringKey("assistant.progress.cancellingReminder", "assistant_progress_cancelling_reminder", emptyList(), 1),
            )
            val `readingNotifications`: LocalizedString get() = LocalizedString(
                key = StringKey("assistant.progress.readingNotifications", "assistant_progress_reading_notifications", emptyList(), 1),
            )
            val `updatingNotifications`: LocalizedString get() = LocalizedString(
                key = StringKey("assistant.progress.updatingNotifications", "assistant_progress_updating_notifications", emptyList(), 1),
            )
            val `readingPersonal`: LocalizedString get() = LocalizedString(
                key = StringKey("assistant.progress.readingPersonal", "assistant_progress_reading_personal", emptyList(), 1),
            )
            val `checkingSources`: LocalizedString get() = LocalizedString(
                key = StringKey("assistant.progress.checkingSources", "assistant_progress_checking_sources", emptyList(), 1),
            )
            val `syncingSource`: LocalizedString get() = LocalizedString(
                key = StringKey("assistant.progress.syncingSource", "assistant_progress_syncing_source", emptyList(), 1),
            )
            val `savingMeasurement`: LocalizedString get() = LocalizedString(
                key = StringKey("assistant.progress.savingMeasurement", "assistant_progress_saving_measurement", emptyList(), 1),
            )
            val `removingMeasurement`: LocalizedString get() = LocalizedString(
                key = StringKey("assistant.progress.removingMeasurement", "assistant_progress_removing_measurement", emptyList(), 1),
            )
            val `readingWeather`: LocalizedString get() = LocalizedString(
                key = StringKey("assistant.progress.readingWeather", "assistant_progress_reading_weather", emptyList(), 1),
            )
            val `readingPlaces`: LocalizedString get() = LocalizedString(
                key = StringKey("assistant.progress.readingPlaces", "assistant_progress_reading_places", emptyList(), 1),
            )
            val `checkingSystem`: LocalizedString get() = LocalizedString(
                key = StringKey("assistant.progress.checkingSystem", "assistant_progress_checking_system", emptyList(), 1),
            )
            val `applyingProposal`: LocalizedString get() = LocalizedString(
                key = StringKey("assistant.progress.applyingProposal", "assistant_progress_applying_proposal", emptyList(), 1),
            )
            val `cancellingProposal`: LocalizedString get() = LocalizedString(
                key = StringKey("assistant.progress.cancellingProposal", "assistant_progress_cancelling_proposal", emptyList(), 1),
            )
            val `processing`: LocalizedString get() = LocalizedString(
                key = StringKey("assistant.progress.processing", "assistant_progress_processing", emptyList(), 1),
            )
        }
        val `queued`: LocalizedString get() = LocalizedString(
            key = StringKey("assistant.queued", "assistant_queued", emptyList(), 1),
        )
        object `ResultActions` {
            val `unavailable`: LocalizedString get() = LocalizedString(
                key = StringKey("assistant.resultActions.unavailable", "assistant_result_actions_unavailable", emptyList(), 1),
            )
            val `opening`: LocalizedString get() = LocalizedString(
                key = StringKey("assistant.resultActions.opening", "assistant_result_actions_opening", emptyList(), 1),
            )
        }
        val `responseFailed`: LocalizedString get() = LocalizedString(
            key = StringKey("assistant.responseFailed", "assistant_response_failed", emptyList(), 1),
        )
        val `responseFailedText`: LocalizedString get() = LocalizedString(
            key = StringKey("assistant.responseFailedText", "assistant_response_failed_text", emptyList(), 1),
        )
        val `outcomeUnknown`: LocalizedString get() = LocalizedString(
            key = StringKey("assistant.outcomeUnknown", "assistant_outcome_unknown", emptyList(), 1),
        )
        val `question`: LocalizedString get() = LocalizedString(
            key = StringKey("assistant.question", "assistant_question", emptyList(), 1),
        )
        val `confirmTitle`: LocalizedString get() = LocalizedString(
            key = StringKey("assistant.confirmTitle", "assistant_confirm_title", emptyList(), 1),
        )
        val `confirm`: LocalizedString get() = LocalizedString(
            key = StringKey("assistant.confirm", "assistant_confirm", emptyList(), 1),
        )
        val `decline`: LocalizedString get() = LocalizedString(
            key = StringKey("assistant.decline", "assistant_decline", emptyList(), 1),
        )
        val `confirmText`: LocalizedString get() = LocalizedString(
            key = StringKey("assistant.confirmText", "assistant_confirm_text", emptyList(), 1),
        )
        val `declineText`: LocalizedString get() = LocalizedString(
            key = StringKey("assistant.declineText", "assistant_decline_text", emptyList(), 1),
        )
        object `Proposal` {
            val `resolved`: LocalizedString get() = LocalizedString(
                key = StringKey("assistant.proposal.resolved", "assistant_proposal_resolved", emptyList(), 1),
            )
            val `cancelled`: LocalizedString get() = LocalizedString(
                key = StringKey("assistant.proposal.cancelled", "assistant_proposal_cancelled", emptyList(), 1),
            )
            val `superseded`: LocalizedString get() = LocalizedString(
                key = StringKey("assistant.proposal.superseded", "assistant_proposal_superseded", emptyList(), 1),
            )
            val `expired`: LocalizedString get() = LocalizedString(
                key = StringKey("assistant.proposal.expired", "assistant_proposal_expired", emptyList(), 1),
            )
        }
        val `unknownTurn`: LocalizedString get() = LocalizedString(
            key = StringKey("assistant.unknownTurn", "assistant_unknown_turn", emptyList(), 1),
        )
        val `details`: LocalizedString get() = LocalizedString(
            key = StringKey("assistant.details", "assistant_details", emptyList(), 1),
        )
        val `detailsTitle`: LocalizedString get() = LocalizedString(
            key = StringKey("assistant.detailsTitle", "assistant_details_title", emptyList(), 1),
        )
        val `detailsServices`: LocalizedString get() = LocalizedString(
            key = StringKey("assistant.detailsServices", "assistant_details_services", emptyList(), 1),
        )
        val `detailsEmpty`: LocalizedString get() = LocalizedString(
            key = StringKey("assistant.detailsEmpty", "assistant_details_empty", emptyList(), 1),
        )
        val `detailsDiagnostic`: LocalizedString get() = LocalizedString(
            key = StringKey("assistant.detailsDiagnostic", "assistant_details_diagnostic", emptyList(), 1),
        )
        object `State` {
            val `completed`: LocalizedString get() = LocalizedString(
                key = StringKey("assistant.state.completed", "assistant_state_completed", emptyList(), 1),
            )
            val `rejected`: LocalizedString get() = LocalizedString(
                key = StringKey("assistant.state.rejected", "assistant_state_rejected", emptyList(), 1),
            )
            val `interrupted`: LocalizedString get() = LocalizedString(
                key = StringKey("assistant.state.interrupted", "assistant_state_interrupted", emptyList(), 1),
            )
            val `unknown`: LocalizedString get() = LocalizedString(
                key = StringKey("assistant.state.unknown", "assistant_state_unknown", emptyList(), 1),
            )
            val `failed`: LocalizedString get() = LocalizedString(
                key = StringKey("assistant.state.failed", "assistant_state_failed", emptyList(), 1),
            )
        }
        val `failed`: LocalizedString get() = LocalizedString(
            key = StringKey("assistant.failed", "assistant_failed", emptyList(), 1),
        )
        val `sendAgain`: LocalizedString get() = LocalizedString(
            key = StringKey("assistant.sendAgain", "assistant_send_again", emptyList(), 1),
        )
        val `editAgain`: LocalizedString get() = LocalizedString(
            key = StringKey("assistant.editAgain", "assistant_edit_again", emptyList(), 1),
        )
        object `Controls` {
            val `stop`: LocalizedString get() = LocalizedString(
                key = StringKey("assistant.controls.stop", "assistant_controls_stop", emptyList(), 1),
            )
            val `queued`: LocalizedString get() = LocalizedString(
                key = StringKey("assistant.controls.queued", "assistant_controls_queued", emptyList(), 1),
            )
            val `sendNow`: LocalizedString get() = LocalizedString(
                key = StringKey("assistant.controls.sendNow", "assistant_controls_send_now", emptyList(), 1),
            )
            val `withdraw`: LocalizedString get() = LocalizedString(
                key = StringKey("assistant.controls.withdraw", "assistant_controls_withdraw", emptyList(), 1),
            )
            val `editQueued`: LocalizedString get() = LocalizedString(
                key = StringKey("assistant.controls.editQueued", "assistant_controls_edit_queued", emptyList(), 1),
            )
            val `stopping`: LocalizedString get() = LocalizedString(
                key = StringKey("assistant.controls.stopping", "assistant_controls_stopping", emptyList(), 1),
            )
            val `stopped`: LocalizedString get() = LocalizedString(
                key = StringKey("assistant.controls.stopped", "assistant_controls_stopped", emptyList(), 1),
            )
            val `withdrawn`: LocalizedString get() = LocalizedString(
                key = StringKey("assistant.controls.withdrawn", "assistant_controls_withdrawn", emptyList(), 1),
            )
            val `keptChanges`: LocalizedString get() = LocalizedString(
                key = StringKey("assistant.controls.keptChanges", "assistant_controls_kept_changes", emptyList(), 1),
            )
            val `unknownChanges`: LocalizedString get() = LocalizedString(
                key = StringKey("assistant.controls.unknownChanges", "assistant_controls_unknown_changes", emptyList(), 1),
            )
            val `awaitingReceipt`: LocalizedString get() = LocalizedString(
                key = StringKey("assistant.controls.awaitingReceipt", "assistant_controls_awaiting_receipt", emptyList(), 1),
            )
            val `editReady`: LocalizedString get() = LocalizedString(
                key = StringKey("assistant.controls.editReady", "assistant_controls_edit_ready", emptyList(), 1),
            )
            val `savedEnvelope`: LocalizedString get() = LocalizedString(
                key = StringKey("assistant.controls.savedEnvelope", "assistant_controls_saved_envelope", emptyList(), 1),
            )
            val `restoreEdit`: LocalizedString get() = LocalizedString(
                key = StringKey("assistant.controls.restoreEdit", "assistant_controls_restore_edit", emptyList(), 1),
            )
            val `check`: LocalizedString get() = LocalizedString(
                key = StringKey("assistant.controls.check", "assistant_controls_check", emptyList(), 1),
            )
            val `retry`: LocalizedString get() = LocalizedString(
                key = StringKey("assistant.controls.retry", "assistant_controls_retry", emptyList(), 1),
            )
            val `rejected`: LocalizedString get() = LocalizedString(
                key = StringKey("assistant.controls.rejected", "assistant_controls_rejected", emptyList(), 1),
            )
            val `dismiss`: LocalizedString get() = LocalizedString(
                key = StringKey("assistant.controls.dismiss", "assistant_controls_dismiss", emptyList(), 1),
            )
            object `Reasons` {
                val `request_started`: LocalizedString get() = LocalizedString(
                    key = StringKey("assistant.controls.reasons.request_started", "assistant_controls_reasons_request_started", emptyList(), 1),
                )
                val `request_queued`: LocalizedString get() = LocalizedString(
                    key = StringKey("assistant.controls.reasons.request_queued", "assistant_controls_reasons_request_queued", emptyList(), 1),
                )
                val `request_finished`: LocalizedString get() = LocalizedString(
                    key = StringKey("assistant.controls.reasons.request_finished", "assistant_controls_reasons_request_finished", emptyList(), 1),
                )
                val `generation_changed`: LocalizedString get() = LocalizedString(
                    key = StringKey("assistant.controls.reasons.generation_changed", "assistant_controls_reasons_generation_changed", emptyList(), 1),
                )
                val `control_unavailable`: LocalizedString get() = LocalizedString(
                    key = StringKey("assistant.controls.reasons.control_unavailable", "assistant_controls_reasons_control_unavailable", emptyList(), 1),
                )
            }
        }
        object `Failure` {
            val `model_unavailable`: LocalizedString get() = LocalizedString(
                key = StringKey("assistant.failure.model_unavailable", "assistant_failure_model_unavailable", emptyList(), 1),
            )
            val `pi_unavailable`: LocalizedString get() = LocalizedString(
                key = StringKey("assistant.failure.pi_unavailable", "assistant_failure_pi_unavailable", emptyList(), 1),
            )
            val `harness_interrupted`: LocalizedString get() = LocalizedString(
                key = StringKey("assistant.failure.harness_interrupted", "assistant_failure_harness_interrupted", emptyList(), 1),
            )
            val `interrupted`: LocalizedString get() = LocalizedString(
                key = StringKey("assistant.failure.interrupted", "assistant_failure_interrupted", emptyList(), 1),
            )
            val `default`: LocalizedString get() = LocalizedString(
                key = StringKey("assistant.failure.default", "assistant_failure_default", emptyList(), 1),
            )
            val `model_no_account_access`: LocalizedString get() = LocalizedString(
                key = StringKey("assistant.failure.model_no_account_access", "assistant_failure_model_no_account_access", emptyList(), 1),
            )
            val `model_no_compatible_connection`: LocalizedString get() = LocalizedString(
                key = StringKey("assistant.failure.model_no_compatible_connection", "assistant_failure_model_no_compatible_connection", emptyList(), 1),
            )
            val `model_auth_required`: LocalizedString get() = LocalizedString(
                key = StringKey("assistant.failure.model_auth_required", "assistant_failure_model_auth_required", emptyList(), 1),
            )
            val `model_auth_unavailable`: LocalizedString get() = LocalizedString(
                key = StringKey("assistant.failure.model_auth_unavailable", "assistant_failure_model_auth_unavailable", emptyList(), 1),
            )
            val `model_identity_changed`: LocalizedString get() = LocalizedString(
                key = StringKey("assistant.failure.model_identity_changed", "assistant_failure_model_identity_changed", emptyList(), 1),
            )
            val `model_quota_exhausted`: LocalizedString get() = LocalizedString(
                key = StringKey("assistant.failure.model_quota_exhausted", "assistant_failure_model_quota_exhausted", emptyList(), 1),
            )
            val `model_rate_limited`: LocalizedString get() = LocalizedString(
                key = StringKey("assistant.failure.model_rate_limited", "assistant_failure_model_rate_limited", emptyList(), 1),
            )
            val `model_access_denied`: LocalizedString get() = LocalizedString(
                key = StringKey("assistant.failure.model_access_denied", "assistant_failure_model_access_denied", emptyList(), 1),
            )
            val `model_execution_disabled`: LocalizedString get() = LocalizedString(
                key = StringKey("assistant.failure.model_execution_disabled", "assistant_failure_model_execution_disabled", emptyList(), 1),
            )
            val `model_deadline`: LocalizedString get() = LocalizedString(
                key = StringKey("assistant.failure.model_deadline", "assistant_failure_model_deadline", emptyList(), 1),
            )
            val `model_invalid_response`: LocalizedString get() = LocalizedString(
                key = StringKey("assistant.failure.model_invalid_response", "assistant_failure_model_invalid_response", emptyList(), 1),
            )
            val `model_transport_error`: LocalizedString get() = LocalizedString(
                key = StringKey("assistant.failure.model_transport_error", "assistant_failure_model_transport_error", emptyList(), 1),
            )
            val `model_unsupported_model`: LocalizedString get() = LocalizedString(
                key = StringKey("assistant.failure.model_unsupported_model", "assistant_failure_model_unsupported_model", emptyList(), 1),
            )
            val `model_refused`: LocalizedString get() = LocalizedString(
                key = StringKey("assistant.failure.model_refused", "assistant_failure_model_refused", emptyList(), 1),
            )
            val `model_content_filtered`: LocalizedString get() = LocalizedString(
                key = StringKey("assistant.failure.model_content_filtered", "assistant_failure_model_content_filtered", emptyList(), 1),
            )
            val `model_context_limit`: LocalizedString get() = LocalizedString(
                key = StringKey("assistant.failure.model_context_limit", "assistant_failure_model_context_limit", emptyList(), 1),
            )
            val `step_limit`: LocalizedString get() = LocalizedString(
                key = StringKey("assistant.failure.step_limit", "assistant_failure_step_limit", emptyList(), 1),
            )
            val `turn_deadline`: LocalizedString get() = LocalizedString(
                key = StringKey("assistant.failure.turn_deadline", "assistant_failure_turn_deadline", emptyList(), 1),
            )
        }
        val `sendingNow`: LocalizedString get() = LocalizedString(
            key = StringKey("assistant.sendingNow", "assistant_sending_now", emptyList(), 1),
        )
        val `delivered`: LocalizedString get() = LocalizedString(
            key = StringKey("assistant.delivered", "assistant_delivered", emptyList(), 1),
        )
        val `openMessage`: LocalizedString get() = LocalizedString(
            key = StringKey("assistant.openMessage", "assistant_open_message", emptyList(), 1),
        )
        val `undatedSubmissions`: LocalizedString get() = LocalizedString(
            key = StringKey("assistant.undatedSubmissions", "assistant_undated_submissions", emptyList(), 1),
        )
        val `pendingCheck`: LocalizedString get() = LocalizedString(
            key = StringKey("assistant.pendingCheck", "assistant_pending_check", emptyList(), 1),
        )
        val `pendingResend`: LocalizedString get() = LocalizedString(
            key = StringKey("assistant.pendingResend", "assistant_pending_resend", emptyList(), 1),
        )
        val `pendingAbandon`: LocalizedString get() = LocalizedString(
            key = StringKey("assistant.pendingAbandon", "assistant_pending_abandon", emptyList(), 1),
        )
        val `pendingDismiss`: LocalizedString get() = LocalizedString(
            key = StringKey("assistant.pendingDismiss", "assistant_pending_dismiss", emptyList(), 1),
        )
        val `pendingSaving`: LocalizedString get() = LocalizedString(
            key = StringKey("assistant.pendingSaving", "assistant_pending_saving", emptyList(), 1),
        )
        val `rejectedTitle`: LocalizedString get() = LocalizedString(
            key = StringKey("assistant.rejectedTitle", "assistant_rejected_title", emptyList(), 1),
        )
        val `earlier`: LocalizedString get() = LocalizedString(
            key = StringKey("assistant.earlier", "assistant_earlier", emptyList(), 1),
        )
        val `newer`: LocalizedString get() = LocalizedString(
            key = StringKey("assistant.newer", "assistant_newer", emptyList(), 1),
        )
        val `refreshFailed`: LocalizedString get() = LocalizedString(
            key = StringKey("assistant.refreshFailed", "assistant_refresh_failed", emptyList(), 1),
        )
        val `latest`: LocalizedString get() = LocalizedString(
            key = StringKey("assistant.latest", "assistant_latest", emptyList(), 1),
        )
        val `newMessages`: LocalizedString get() = LocalizedString(
            key = StringKey("assistant.newMessages", "assistant_new_messages", emptyList(), 1),
        )
        fun `newAnswers`(quantity: Double, `count`: String): LocalizedString = LocalizedString(
            key = StringKey("assistant.newAnswers", "assistant_new_answers", listOf("count"), 3),
            arguments = listOf(`count`),
            quantity = quantity,
        )
        val `search`: LocalizedString get() = LocalizedString(
            key = StringKey("assistant.search", "assistant_search", emptyList(), 1),
        )
        val `searchPlaceholder`: LocalizedString get() = LocalizedString(
            key = StringKey("assistant.searchPlaceholder", "assistant_search_placeholder", emptyList(), 1),
        )
        val `searchEmpty`: LocalizedString get() = LocalizedString(
            key = StringKey("assistant.searchEmpty", "assistant_search_empty", emptyList(), 1),
        )
        val `searchHint`: LocalizedString get() = LocalizedString(
            key = StringKey("assistant.searchHint", "assistant_search_hint", emptyList(), 1),
        )
        val `searchAssistant`: LocalizedString get() = LocalizedString(
            key = StringKey("assistant.searchAssistant", "assistant_search_assistant", emptyList(), 1),
        )
        val `searchAfter`: LocalizedString get() = LocalizedString(
            key = StringKey("assistant.searchAfter", "assistant_search_after", emptyList(), 1),
        )
        val `searchThrough`: LocalizedString get() = LocalizedString(
            key = StringKey("assistant.searchThrough", "assistant_search_through", emptyList(), 1),
        )
        val `searchAnyDate`: LocalizedString get() = LocalizedString(
            key = StringKey("assistant.searchAnyDate", "assistant_search_any_date", emptyList(), 1),
        )
        fun `searchTimezone`(`zone`: String): LocalizedString = LocalizedString(
            key = StringKey("assistant.searchTimezone", "assistant_search_timezone", listOf("zone"), 1),
            arguments = listOf(`zone`),
        )
        val `searchDateError`: LocalizedString get() = LocalizedString(
            key = StringKey("assistant.searchDateError", "assistant_search_date_error", emptyList(), 1),
        )
        val `searchMore`: LocalizedString get() = LocalizedString(
            key = StringKey("assistant.searchMore", "assistant_search_more", emptyList(), 1),
        )
        val `searchMoreError`: LocalizedString get() = LocalizedString(
            key = StringKey("assistant.searchMoreError", "assistant_search_more_error", emptyList(), 1),
        )
        val `contextOf`: LocalizedString get() = LocalizedString(
            key = StringKey("assistant.contextOf", "assistant_context_of", emptyList(), 1),
        )
        val `emptyTitle`: LocalizedString get() = LocalizedString(
            key = StringKey("assistant.emptyTitle", "assistant_empty_title", emptyList(), 1),
        )
        val `emptyText`: LocalizedString get() = LocalizedString(
            key = StringKey("assistant.emptyText", "assistant_empty_text", emptyList(), 1),
        )
        object `Examples` {
            val item0: LocalizedString get() = LocalizedString(
                key = StringKey("assistant.examples.0", "assistant_examples_0", emptyList(), 1),
            )
            val item1: LocalizedString get() = LocalizedString(
                key = StringKey("assistant.examples.1", "assistant_examples_1", emptyList(), 1),
            )
            val item2: LocalizedString get() = LocalizedString(
                key = StringKey("assistant.examples.2", "assistant_examples_2", emptyList(), 1),
            )
            val item3: LocalizedString get() = LocalizedString(
                key = StringKey("assistant.examples.3", "assistant_examples_3", emptyList(), 1),
            )
        }
        object `Suggestions` {
            val `label`: LocalizedString get() = LocalizedString(
                key = StringKey("assistant.suggestions.label", "assistant_suggestions_label", emptyList(), 1),
            )
            val `breakfast`: LocalizedString get() = LocalizedString(
                key = StringKey("assistant.suggestions.breakfast", "assistant_suggestions_breakfast", emptyList(), 1),
            )
            val `breakfastStart`: LocalizedString get() = LocalizedString(
                key = StringKey("assistant.suggestions.breakfastStart", "assistant_suggestions_breakfast_start", emptyList(), 1),
            )
            val `lunch`: LocalizedString get() = LocalizedString(
                key = StringKey("assistant.suggestions.lunch", "assistant_suggestions_lunch", emptyList(), 1),
            )
            val `lunchStart`: LocalizedString get() = LocalizedString(
                key = StringKey("assistant.suggestions.lunchStart", "assistant_suggestions_lunch_start", emptyList(), 1),
            )
            val `dinner`: LocalizedString get() = LocalizedString(
                key = StringKey("assistant.suggestions.dinner", "assistant_suggestions_dinner", emptyList(), 1),
            )
            val `dinnerStart`: LocalizedString get() = LocalizedString(
                key = StringKey("assistant.suggestions.dinnerStart", "assistant_suggestions_dinner_start", emptyList(), 1),
            )
            val `dayAhead`: LocalizedString get() = LocalizedString(
                key = StringKey("assistant.suggestions.dayAhead", "assistant_suggestions_day_ahead", emptyList(), 1),
            )
            val `dayLeft`: LocalizedString get() = LocalizedString(
                key = StringKey("assistant.suggestions.dayLeft", "assistant_suggestions_day_left", emptyList(), 1),
            )
            val `summary`: LocalizedString get() = LocalizedString(
                key = StringKey("assistant.suggestions.summary", "assistant_suggestions_summary", emptyList(), 1),
            )
        }
        val `sources`: LocalizedString get() = LocalizedString(
            key = StringKey("assistant.sources", "assistant_sources", emptyList(), 1),
        )
        val `sourcesTitle`: LocalizedString get() = LocalizedString(
            key = StringKey("assistant.sourcesTitle", "assistant_sources_title", emptyList(), 1),
        )
        val `supports`: LocalizedString get() = LocalizedString(
            key = StringKey("assistant.supports", "assistant_supports", emptyList(), 1),
        )
        val `contradicts`: LocalizedString get() = LocalizedString(
            key = StringKey("assistant.contradicts", "assistant_contradicts", emptyList(), 1),
        )
        val `limitations`: LocalizedString get() = LocalizedString(
            key = StringKey("assistant.limitations", "assistant_limitations", emptyList(), 1),
        )
        val `accountLogin`: LocalizedString get() = LocalizedString(
            key = StringKey("assistant.accountLogin", "assistant_account_login", emptyList(), 1),
        )
        val `openAccounts`: LocalizedString get() = LocalizedString(
            key = StringKey("assistant.openAccounts", "assistant_open_accounts", emptyList(), 1),
        )
        val `answerInterrupted`: LocalizedString get() = LocalizedString(
            key = StringKey("assistant.answerInterrupted", "assistant_answer_interrupted", emptyList(), 1),
        )
        val `resultUnknown`: LocalizedString get() = LocalizedString(
            key = StringKey("assistant.resultUnknown", "assistant_result_unknown", emptyList(), 1),
        )
        val `noChanges`: LocalizedString get() = LocalizedString(
            key = StringKey("assistant.noChanges", "assistant_no_changes", emptyList(), 1),
        )
        val `confirmedChanges`: LocalizedString get() = LocalizedString(
            key = StringKey("assistant.confirmedChanges", "assistant_confirmed_changes", emptyList(), 1),
        )
        val `partialResult`: LocalizedString get() = LocalizedString(
            key = StringKey("assistant.partialResult", "assistant_partial_result", emptyList(), 1),
        )
        val `fixAccess`: LocalizedString get() = LocalizedString(
            key = StringKey("assistant.fixAccess", "assistant_fix_access", emptyList(), 1),
        )
        val `contactAdmin`: LocalizedString get() = LocalizedString(
            key = StringKey("assistant.contactAdmin", "assistant_contact_admin", emptyList(), 1),
        )
        object `Availability` {
            val `no_account_access`: LocalizedString get() = LocalizedString(
                key = StringKey("assistant.availability.no_account_access", "assistant_availability_no_account_access", emptyList(), 1),
            )
            val `no_compatible_connection`: LocalizedString get() = LocalizedString(
                key = StringKey("assistant.availability.no_compatible_connection", "assistant_availability_no_compatible_connection", emptyList(), 1),
            )
            val `auth_required`: LocalizedString get() = LocalizedString(
                key = StringKey("assistant.availability.auth_required", "assistant_availability_auth_required", emptyList(), 1),
            )
            val `auth_unavailable`: LocalizedString get() = LocalizedString(
                key = StringKey("assistant.availability.auth_unavailable", "assistant_availability_auth_unavailable", emptyList(), 1),
            )
            val `execution_disabled`: LocalizedString get() = LocalizedString(
                key = StringKey("assistant.availability.execution_disabled", "assistant_availability_execution_disabled", emptyList(), 1),
            )
            val `chat_disabled`: LocalizedString get() = LocalizedString(
                key = StringKey("assistant.availability.chat_disabled", "assistant_availability_chat_disabled", emptyList(), 1),
            )
            val `quota_exhausted`: LocalizedString get() = LocalizedString(
                key = StringKey("assistant.availability.quota_exhausted", "assistant_availability_quota_exhausted", emptyList(), 1),
            )
            val `daily_limit`: LocalizedString get() = LocalizedString(
                key = StringKey("assistant.availability.daily_limit", "assistant_availability_daily_limit", emptyList(), 1),
            )
            val `transport_error`: LocalizedString get() = LocalizedString(
                key = StringKey("assistant.availability.transport_error", "assistant_availability_transport_error", emptyList(), 1),
            )
            val `checking`: LocalizedString get() = LocalizedString(
                key = StringKey("assistant.availability.checking", "assistant_availability_checking", emptyList(), 1),
            )
            val `checkFailed`: LocalizedString get() = LocalizedString(
                key = StringKey("assistant.availability.checkFailed", "assistant_availability_check_failed", emptyList(), 1),
            )
            val `unknown`: LocalizedString get() = LocalizedString(
                key = StringKey("assistant.availability.unknown", "assistant_availability_unknown", emptyList(), 1),
            )
            val `unavailable`: LocalizedString get() = LocalizedString(
                key = StringKey("assistant.availability.unavailable", "assistant_availability_unavailable", emptyList(), 1),
            )
            fun `retryAt`(`at`: String): LocalizedString = LocalizedString(
                key = StringKey("assistant.availability.retryAt", "assistant_availability_retry_at", listOf("at"), 1),
                arguments = listOf(`at`),
            )
            val `refresh`: LocalizedString get() = LocalizedString(
                key = StringKey("assistant.availability.refresh", "assistant_availability_refresh", emptyList(), 1),
            )
        }
        object `DeliveryDiagnostic` {
            val `timeout`: LocalizedString get() = LocalizedString(
                key = StringKey("assistant.deliveryDiagnostic.timeout", "assistant_delivery_diagnostic_timeout", emptyList(), 1),
            )
            val `network`: LocalizedString get() = LocalizedString(
                key = StringKey("assistant.deliveryDiagnostic.network", "assistant_delivery_diagnostic_network", emptyList(), 1),
            )
            fun `http`(`status`: String): LocalizedString = LocalizedString(
                key = StringKey("assistant.deliveryDiagnostic.http", "assistant_delivery_diagnostic_http", listOf("status"), 1),
                arguments = listOf(`status`),
            )
            val `csrf`: LocalizedString get() = LocalizedString(
                key = StringKey("assistant.deliveryDiagnostic.csrf", "assistant_delivery_diagnostic_csrf", emptyList(), 1),
            )
            val `request`: LocalizedString get() = LocalizedString(
                key = StringKey("assistant.deliveryDiagnostic.request", "assistant_delivery_diagnostic_request", emptyList(), 1),
            )
            val `response`: LocalizedString get() = LocalizedString(
                key = StringKey("assistant.deliveryDiagnostic.response", "assistant_delivery_diagnostic_response", emptyList(), 1),
            )
            fun `duration`(`seconds`: String): LocalizedString = LocalizedString(
                key = StringKey("assistant.deliveryDiagnostic.duration", "assistant_delivery_diagnostic_duration", listOf("seconds"), 1),
                arguments = listOf(`seconds`),
            )
            val `trace`: LocalizedString get() = LocalizedString(
                key = StringKey("assistant.deliveryDiagnostic.trace", "assistant_delivery_diagnostic_trace", emptyList(), 1),
            )
        }
        object `Activity` {
            val `waiting_model`: LocalizedString get() = LocalizedString(
                key = StringKey("assistant.activity.waiting_model", "assistant_activity_waiting_model", emptyList(), 1),
            )
            val `thinking`: LocalizedString get() = LocalizedString(
                key = StringKey("assistant.activity.thinking", "assistant_activity_thinking", emptyList(), 1),
            )
            val `generating`: LocalizedString get() = LocalizedString(
                key = StringKey("assistant.activity.generating", "assistant_activity_generating", emptyList(), 1),
            )
            val `tool`: LocalizedString get() = LocalizedString(
                key = StringKey("assistant.activity.tool", "assistant_activity_tool", emptyList(), 1),
            )
            val `compacting`: LocalizedString get() = LocalizedString(
                key = StringKey("assistant.activity.compacting", "assistant_activity_compacting", emptyList(), 1),
            )
            val `model`: LocalizedString get() = LocalizedString(
                key = StringKey("assistant.activity.model", "assistant_activity_model", emptyList(), 1),
            )
            fun `seconds`(`value`: String): LocalizedString = LocalizedString(
                key = StringKey("assistant.activity.seconds", "assistant_activity_seconds", listOf("value"), 1),
                arguments = listOf(`value`),
            )
            val `verifying`: LocalizedString get() = LocalizedString(
                key = StringKey("assistant.activity.verifying", "assistant_activity_verifying", emptyList(), 1),
            )
            val `details`: LocalizedString get() = LocalizedString(
                key = StringKey("assistant.activity.details", "assistant_activity_details", emptyList(), 1),
            )
            object `Step` {
                val `running`: LocalizedString get() = LocalizedString(
                    key = StringKey("assistant.activity.step.running", "assistant_activity_step_running", emptyList(), 1),
                )
                val `completed`: LocalizedString get() = LocalizedString(
                    key = StringKey("assistant.activity.step.completed", "assistant_activity_step_completed", emptyList(), 1),
                )
                val `failed`: LocalizedString get() = LocalizedString(
                    key = StringKey("assistant.activity.step.failed", "assistant_activity_step_failed", emptyList(), 1),
                )
            }
        }
        object `Usage` {
            val `title`: LocalizedString get() = LocalizedString(
                key = StringKey("assistant.usage.title", "assistant_usage_title", emptyList(), 1),
            )
            val `unknown`: LocalizedString get() = LocalizedString(
                key = StringKey("assistant.usage.unknown", "assistant_usage_unknown", emptyList(), 1),
            )
            val `calls`: LocalizedString get() = LocalizedString(
                key = StringKey("assistant.usage.calls", "assistant_usage_calls", emptyList(), 1),
            )
            val `inputTotal`: LocalizedString get() = LocalizedString(
                key = StringKey("assistant.usage.inputTotal", "assistant_usage_input_total", emptyList(), 1),
            )
            val `inputUncached`: LocalizedString get() = LocalizedString(
                key = StringKey("assistant.usage.inputUncached", "assistant_usage_input_uncached", emptyList(), 1),
            )
            val `cacheRead`: LocalizedString get() = LocalizedString(
                key = StringKey("assistant.usage.cacheRead", "assistant_usage_cache_read", emptyList(), 1),
            )
            val `cacheWrite`: LocalizedString get() = LocalizedString(
                key = StringKey("assistant.usage.cacheWrite", "assistant_usage_cache_write", emptyList(), 1),
            )
            val `output`: LocalizedString get() = LocalizedString(
                key = StringKey("assistant.usage.output", "assistant_usage_output", emptyList(), 1),
            )
            val `total`: LocalizedString get() = LocalizedString(
                key = StringKey("assistant.usage.total", "assistant_usage_total", emptyList(), 1),
            )
            val `modelDuration`: LocalizedString get() = LocalizedString(
                key = StringKey("assistant.usage.modelDuration", "assistant_usage_model_duration", emptyList(), 1),
            )
            val `firstPublicText`: LocalizedString get() = LocalizedString(
                key = StringKey("assistant.usage.firstPublicText", "assistant_usage_first_public_text", emptyList(), 1),
            )
            fun `milliseconds`(`value`: String): LocalizedString = LocalizedString(
                key = StringKey("assistant.usage.milliseconds", "assistant_usage_milliseconds", listOf("value"), 1),
                arguments = listOf(`value`),
            )
            val `note`: LocalizedString get() = LocalizedString(
                key = StringKey("assistant.usage.note", "assistant_usage_note", emptyList(), 1),
            )
        }
    }
    object `Services` {
        val `timers`: LocalizedString get() = LocalizedString(
            key = StringKey("services.timers", "services_timers", emptyList(), 1),
        )
        val `research`: LocalizedString get() = LocalizedString(
            key = StringKey("services.research", "services_research", emptyList(), 1),
        )
        val `general`: LocalizedString get() = LocalizedString(
            key = StringKey("services.general", "services_general", emptyList(), 1),
        )
    }
    object `Food` {
        val `water`: LocalizedString get() = LocalizedString(
            key = StringKey("food.water", "food_water", emptyList(), 1),
        )
        val `group`: LocalizedString get() = LocalizedString(
            key = StringKey("food.group", "food_group", emptyList(), 1),
        )
        val `select`: LocalizedString get() = LocalizedString(
            key = StringKey("food.select", "food_select", emptyList(), 1),
        )
        val `draftPreserved`: LocalizedString get() = LocalizedString(
            key = StringKey("food.draftPreserved", "food_draft_preserved", emptyList(), 1),
        )
        val `receipt`: LocalizedString get() = LocalizedString(
            key = StringKey("food.receipt", "food_receipt", emptyList(), 1),
        )
        val `recipeEstimate`: LocalizedString get() = LocalizedString(
            key = StringKey("food.recipeEstimate", "food_recipe_estimate", emptyList(), 1),
        )
        val `title`: LocalizedString get() = LocalizedString(
            key = StringKey("food.title", "food_title", emptyList(), 1),
        )
        val `eaten`: LocalizedString get() = LocalizedString(
            key = StringKey("food.eaten", "food_eaten", emptyList(), 1),
        )
        fun `of`(`target`: String): LocalizedString = LocalizedString(
            key = StringKey("food.of", "food_of", listOf("target"), 1),
            arguments = listOf(`target`),
        )
        val `kcal`: LocalizedString get() = LocalizedString(
            key = StringKey("food.kcal", "food_kcal", emptyList(), 1),
        )
        val `noGoal`: LocalizedString get() = LocalizedString(
            key = StringKey("food.noGoal", "food_no_goal", emptyList(), 1),
        )
        val `setGoal`: LocalizedString get() = LocalizedString(
            key = StringKey("food.setGoal", "food_set_goal", emptyList(), 1),
        )
        fun `setGoalFor`(`name`: String): LocalizedString = LocalizedString(
            key = StringKey("food.setGoalFor", "food_set_goal_for", listOf("name"), 1),
            arguments = listOf(`name`),
        )
        val `setLimit`: LocalizedString get() = LocalizedString(
            key = StringKey("food.setLimit", "food_set_limit", emptyList(), 1),
        )
        fun `remaining`(`value`: String): LocalizedString = LocalizedString(
            key = StringKey("food.remaining", "food_remaining", listOf("value"), 1),
            arguments = listOf(`value`),
        )
        fun `over`(`value`: String): LocalizedString = LocalizedString(
            key = StringKey("food.over", "food_over", listOf("value"), 1),
            arguments = listOf(`value`),
        )
        val `incomplete`: LocalizedString get() = LocalizedString(
            key = StringKey("food.incomplete", "food_incomplete", emptyList(), 1),
        )
        val `atLeast`: LocalizedString get() = LocalizedString(
            key = StringKey("food.atLeast", "food_at_least", emptyList(), 1),
        )
        val `noData`: LocalizedString get() = LocalizedString(
            key = StringKey("food.noData", "food_no_data", emptyList(), 1),
        )
        val `goalReached`: LocalizedString get() = LocalizedString(
            key = StringKey("food.goalReached", "food_goal_reached", emptyList(), 1),
        )
        val `goalPassed`: LocalizedString get() = LocalizedString(
            key = StringKey("food.goalPassed", "food_goal_passed", emptyList(), 1),
        )
        object `Macros` {
            val `protein_g`: LocalizedString get() = LocalizedString(
                key = StringKey("food.macros.protein_g", "food_macros_protein_g", emptyList(), 1),
            )
            val `fat_g`: LocalizedString get() = LocalizedString(
                key = StringKey("food.macros.fat_g", "food_macros_fat_g", emptyList(), 1),
            )
            val `carbohydrate_g`: LocalizedString get() = LocalizedString(
                key = StringKey("food.macros.carbohydrate_g", "food_macros_carbohydrate_g", emptyList(), 1),
            )
            val `fiber_g`: LocalizedString get() = LocalizedString(
                key = StringKey("food.macros.fiber_g", "food_macros_fiber_g", emptyList(), 1),
            )
        }
        fun `grams`(`value`: String): LocalizedString = LocalizedString(
            key = StringKey("food.grams", "food_grams", listOf("value"), 1),
            arguments = listOf(`value`),
        )
        fun `millilitres`(`value`: String): LocalizedString = LocalizedString(
            key = StringKey("food.millilitres", "food_millilitres", listOf("value"), 1),
            arguments = listOf(`value`),
        )
        fun `gramsOf`(`value`: String, `target`: String): LocalizedString = LocalizedString(
            key = StringKey("food.gramsOf", "food_grams_of", listOf("value", "target"), 1),
            arguments = listOf(`value`, `target`),
        )
        val `add`: LocalizedString get() = LocalizedString(
            key = StringKey("food.add", "food_add", emptyList(), 1),
        )
        val `addTitle`: LocalizedString get() = LocalizedString(
            key = StringKey("food.addTitle", "food_add_title", emptyList(), 1),
        )
        val `mealKind`: LocalizedString get() = LocalizedString(
            key = StringKey("food.mealKind", "food_meal_kind", emptyList(), 1),
        )
        object `MealKinds` {
            val `breakfast`: LocalizedString get() = LocalizedString(
                key = StringKey("food.mealKinds.breakfast", "food_meal_kinds_breakfast", emptyList(), 1),
            )
            val `lunch`: LocalizedString get() = LocalizedString(
                key = StringKey("food.mealKinds.lunch", "food_meal_kinds_lunch", emptyList(), 1),
            )
            val `dinner`: LocalizedString get() = LocalizedString(
                key = StringKey("food.mealKinds.dinner", "food_meal_kinds_dinner", emptyList(), 1),
            )
            val `snack`: LocalizedString get() = LocalizedString(
                key = StringKey("food.mealKinds.snack", "food_meal_kinds_snack", emptyList(), 1),
            )
        }
        val `noMealKind`: LocalizedString get() = LocalizedString(
            key = StringKey("food.noMealKind", "food_no_meal_kind", emptyList(), 1),
        )
        val `changeMealKind`: LocalizedString get() = LocalizedString(
            key = StringKey("food.changeMealKind", "food_change_meal_kind", emptyList(), 1),
        )
        fun `mealKindSet`(`kind`: String): LocalizedString = LocalizedString(
            key = StringKey("food.mealKindSet", "food_meal_kind_set", listOf("kind"), 1),
            arguments = listOf(`kind`),
        )
        val `mealKindCleared`: LocalizedString get() = LocalizedString(
            key = StringKey("food.mealKindCleared", "food_meal_kind_cleared", emptyList(), 1),
        )
        val `addLabel`: LocalizedString get() = LocalizedString(
            key = StringKey("food.addLabel", "food_add_label", emptyList(), 1),
        )
        val `addPhoto`: LocalizedString get() = LocalizedString(
            key = StringKey("food.addPhoto", "food_add_photo", emptyList(), 1),
        )
        val `askAssistant`: LocalizedString get() = LocalizedString(
            key = StringKey("food.askAssistant", "food_ask_assistant", emptyList(), 1),
        )
        val `photoPrompt`: LocalizedString get() = LocalizedString(
            key = StringKey("food.photoPrompt", "food_photo_prompt", emptyList(), 1),
        )
        fun `photoPromptDay`(`day`: String): LocalizedString = LocalizedString(
            key = StringKey("food.photoPromptDay", "food_photo_prompt_day", listOf("day"), 1),
            arguments = listOf(`day`),
        )
        val `photoReplaceTitle`: LocalizedString get() = LocalizedString(
            key = StringKey("food.photoReplaceTitle", "food_photo_replace_title", emptyList(), 1),
        )
        val `photoReplaceText`: LocalizedString get() = LocalizedString(
            key = StringKey("food.photoReplaceText", "food_photo_replace_text", emptyList(), 1),
        )
        val `photoReplace`: LocalizedString get() = LocalizedString(
            key = StringKey("food.photoReplace", "food_photo_replace", emptyList(), 1),
        )
        val `photoHandoffFailed`: LocalizedString get() = LocalizedString(
            key = StringKey("food.photoHandoffFailed", "food_photo_handoff_failed", emptyList(), 1),
        )
        val `entries`: LocalizedString get() = LocalizedString(
            key = StringKey("food.entries", "food_entries", emptyList(), 1),
        )
        val `emptyTitle`: LocalizedString get() = LocalizedString(
            key = StringKey("food.emptyTitle", "food_empty_title", emptyList(), 1),
        )
        val `emptyText`: LocalizedString get() = LocalizedString(
            key = StringKey("food.emptyText", "food_empty_text", emptyList(), 1),
        )
        val `estimated`: LocalizedString get() = LocalizedString(
            key = StringKey("food.estimated", "food_estimated", emptyList(), 1),
        )
        val `estimatedLegend`: LocalizedString get() = LocalizedString(
            key = StringKey("food.estimatedLegend", "food_estimated_legend", emptyList(), 1),
        )
        fun `dishOf`(quantity: Double, `count`: String): LocalizedString = LocalizedString(
            key = StringKey("food.dishOf", "food_dish_of", listOf("count"), 3),
            arguments = listOf(`count`),
            quantity = quantity,
        )
        val `personal`: LocalizedString get() = LocalizedString(
            key = StringKey("food.personal", "food_personal", emptyList(), 1),
        )
        val `drafts`: LocalizedString get() = LocalizedString(
            key = StringKey("food.drafts", "food_drafts", emptyList(), 1),
        )
        fun `draftText`(`fields`: String): LocalizedString = LocalizedString(
            key = StringKey("food.draftText", "food_draft_text", listOf("fields"), 1),
            arguments = listOf(`fields`),
        )
        val `resolve`: LocalizedString get() = LocalizedString(
            key = StringKey("food.resolve", "food_resolve", emptyList(), 1),
        )
        object `Missing` {
            val `snapshot_id`: LocalizedString get() = LocalizedString(
                key = StringKey("food.missing.snapshot_id", "food_missing_snapshot_id", emptyList(), 1),
            )
            val `quantity`: LocalizedString get() = LocalizedString(
                key = StringKey("food.missing.quantity", "food_missing_quantity", emptyList(), 1),
            )
            val `unit`: LocalizedString get() = LocalizedString(
                key = StringKey("food.missing.unit", "food_missing_unit", emptyList(), 1),
            )
        }
        val `searchTitle`: LocalizedString get() = LocalizedString(
            key = StringKey("food.searchTitle", "food_search_title", emptyList(), 1),
        )
        val `searchPlaceholder`: LocalizedString get() = LocalizedString(
            key = StringKey("food.searchPlaceholder", "food_search_placeholder", emptyList(), 1),
        )
        val `searchPersonal`: LocalizedString get() = LocalizedString(
            key = StringKey("food.searchPersonal", "food_search_personal", emptyList(), 1),
        )
        val `searchResults`: LocalizedString get() = LocalizedString(
            key = StringKey("food.searchResults", "food_search_results", emptyList(), 1),
        )
        val `searchNothing`: LocalizedString get() = LocalizedString(
            key = StringKey("food.searchNothing", "food_search_nothing", emptyList(), 1),
        )
        val `searchNothingText`: LocalizedString get() = LocalizedString(
            key = StringKey("food.searchNothingText", "food_search_nothing_text", emptyList(), 1),
        )
        fun `per`(`kcal`: String, `basis`: String, `unit`: String): LocalizedString = LocalizedString(
            key = StringKey("food.per", "food_per", listOf("kcal", "basis", "unit"), 1),
            arguments = listOf(`kcal`, `basis`, `unit`),
        )
        val `portionTitle`: LocalizedString get() = LocalizedString(
            key = StringKey("food.portionTitle", "food_portion_title", emptyList(), 1),
        )
        val `quantity`: LocalizedString get() = LocalizedString(
            key = StringKey("food.quantity", "food_quantity", emptyList(), 1),
        )
        val `portions`: LocalizedString get() = LocalizedString(
            key = StringKey("food.portions", "food_portions", emptyList(), 1),
        )
        val `estimate`: LocalizedString get() = LocalizedString(
            key = StringKey("food.estimate", "food_estimate", emptyList(), 1),
        )
        val `record`: LocalizedString get() = LocalizedString(
            key = StringKey("food.record", "food_record", emptyList(), 1),
        )
        val `recording`: LocalizedString get() = LocalizedString(
            key = StringKey("food.recording", "food_recording", emptyList(), 1),
        )
        fun `recorded`(`name`: String, `kcal`: String): LocalizedString = LocalizedString(
            key = StringKey("food.recorded", "food_recorded", listOf("name", "kcal"), 1),
            arguments = listOf(`name`, `kcal`),
        )
        fun `recordedNoKcal`(`name`: String): LocalizedString = LocalizedString(
            key = StringKey("food.recordedNoKcal", "food_recorded_no_kcal", listOf("name"), 1),
            arguments = listOf(`name`),
        )
        val `entryTitle`: LocalizedString get() = LocalizedString(
            key = StringKey("food.entryTitle", "food_entry_title", emptyList(), 1),
        )
        val `save`: LocalizedString get() = LocalizedString(
            key = StringKey("food.save", "food_save", emptyList(), 1),
        )
        val `saved`: LocalizedString get() = LocalizedString(
            key = StringKey("food.saved", "food_saved", emptyList(), 1),
        )
        val `delete`: LocalizedString get() = LocalizedString(
            key = StringKey("food.delete", "food_delete", emptyList(), 1),
        )
        fun `deleteConfirm`(`name`: String): LocalizedString = LocalizedString(
            key = StringKey("food.deleteConfirm", "food_delete_confirm", listOf("name"), 1),
            arguments = listOf(`name`),
        )
        val `deleted`: LocalizedString get() = LocalizedString(
            key = StringKey("food.deleted", "food_deleted", emptyList(), 1),
        )
        val `date`: LocalizedString get() = LocalizedString(
            key = StringKey("food.date", "food_date", emptyList(), 1),
        )
        val `ingredientHint`: LocalizedString get() = LocalizedString(
            key = StringKey("food.ingredientHint", "food_ingredient_hint", emptyList(), 1),
        )
        val `dishTitle`: LocalizedString get() = LocalizedString(
            key = StringKey("food.dishTitle", "food_dish_title", emptyList(), 1),
        )
        val `components`: LocalizedString get() = LocalizedString(
            key = StringKey("food.components", "food_components", emptyList(), 1),
        )
        val `dishQuantity`: LocalizedString get() = LocalizedString(
            key = StringKey("food.dishQuantity", "food_dish_quantity", emptyList(), 1),
        )
        val `scaleHint`: LocalizedString get() = LocalizedString(
            key = StringKey("food.scaleHint", "food_scale_hint", emptyList(), 1),
        )
        val `editComponents`: LocalizedString get() = LocalizedString(
            key = StringKey("food.editComponents", "food_edit_components", emptyList(), 1),
        )
        val `saveComponents`: LocalizedString get() = LocalizedString(
            key = StringKey("food.saveComponents", "food_save_components", emptyList(), 1),
        )
        val `remember`: LocalizedString get() = LocalizedString(
            key = StringKey("food.remember", "food_remember", emptyList(), 1),
        )
        val `rememberName`: LocalizedString get() = LocalizedString(
            key = StringKey("food.rememberName", "food_remember_name", emptyList(), 1),
        )
        fun `remembered`(`name`: String): LocalizedString = LocalizedString(
            key = StringKey("food.remembered", "food_remembered", listOf("name"), 1),
            arguments = listOf(`name`),
        )
        val `memoryEmpty`: LocalizedString get() = LocalizedString(
            key = StringKey("food.memoryEmpty", "food_memory_empty", emptyList(), 1),
        )
        val `favoritesEmpty`: LocalizedString get() = LocalizedString(
            key = StringKey("food.favoritesEmpty", "food_favorites_empty", emptyList(), 1),
        )
        val `recent`: LocalizedString get() = LocalizedString(
            key = StringKey("food.recent", "food_recent", emptyList(), 1),
        )
        val `favorites`: LocalizedString get() = LocalizedString(
            key = StringKey("food.favorites", "food_favorites", emptyList(), 1),
        )
        val `dishesTab`: LocalizedString get() = LocalizedString(
            key = StringKey("food.dishesTab", "food_dishes_tab", emptyList(), 1),
        )
        val `labelShort`: LocalizedString get() = LocalizedString(
            key = StringKey("food.labelShort", "food_label_short", emptyList(), 1),
        )
        val `favorite`: LocalizedString get() = LocalizedString(
            key = StringKey("food.favorite", "food_favorite", emptyList(), 1),
        )
        val `unfavorite`: LocalizedString get() = LocalizedString(
            key = StringKey("food.unfavorite", "food_unfavorite", emptyList(), 1),
        )
        fun `lastTime`(`value`: String): LocalizedString = LocalizedString(
            key = StringKey("food.lastTime", "food_last_time", listOf("value"), 1),
            arguments = listOf(`value`),
        )
        fun `lastTimeKcal`(`value`: String, `kcal`: String): LocalizedString = LocalizedString(
            key = StringKey("food.lastTimeKcal", "food_last_time_kcal", listOf("value", "kcal"), 1),
            arguments = listOf(`value`, `kcal`),
        )
        fun `loggedAt`(`time`: String): LocalizedString = LocalizedString(
            key = StringKey("food.loggedAt", "food_logged_at", listOf("time"), 1),
            arguments = listOf(`time`),
        )
        fun `mealLoggedAt`(`time`: String): LocalizedString = LocalizedString(
            key = StringKey("food.mealLoggedAt", "food_meal_logged_at", listOf("time"), 1),
            arguments = listOf(`time`),
        )
        val `week`: LocalizedString get() = LocalizedString(
            key = StringKey("food.week", "food_week", emptyList(), 1),
        )
        fun `weekOf`(`day`: String, `kcal`: String, `target`: String): LocalizedString = LocalizedString(
            key = StringKey("food.weekOf", "food_week_of", listOf("day", "kcal", "target"), 1),
            arguments = listOf(`day`, `kcal`, `target`),
        )
        fun `weekOver`(`day`: String, `kcal`: String, `target`: String): LocalizedString = LocalizedString(
            key = StringKey("food.weekOver", "food_week_over", listOf("day", "kcal", "target"), 1),
            arguments = listOf(`day`, `kcal`, `target`),
        )
        object `WeekLegend` {
            val `within`: LocalizedString get() = LocalizedString(
                key = StringKey("food.weekLegend.within", "food_week_legend_within", emptyList(), 1),
            )
            val `over`: LocalizedString get() = LocalizedString(
                key = StringKey("food.weekLegend.over", "food_week_legend_over", emptyList(), 1),
            )
            val `closed`: LocalizedString get() = LocalizedString(
                key = StringKey("food.weekLegend.closed", "food_week_legend_closed", emptyList(), 1),
            )
        }
        fun `weekKcal`(`day`: String, `kcal`: String): LocalizedString = LocalizedString(
            key = StringKey("food.weekKcal", "food_week_kcal", listOf("day", "kcal"), 1),
            arguments = listOf(`day`, `kcal`),
        )
        fun `weekEmpty`(`day`: String): LocalizedString = LocalizedString(
            key = StringKey("food.weekEmpty", "food_week_empty", listOf("day"), 1),
            arguments = listOf(`day`),
        )
        val `noTime`: LocalizedString get() = LocalizedString(
            key = StringKey("food.noTime", "food_no_time", emptyList(), 1),
        )
        fun `repeatLast`(`name`: String): LocalizedString = LocalizedString(
            key = StringKey("food.repeatLast", "food_repeat_last", listOf("name"), 1),
            arguments = listOf(`name`),
        )
        fun `logOneServing`(`name`: String): LocalizedString = LocalizedString(
            key = StringKey("food.logOneServing", "food_log_one_serving", listOf("name"), 1),
            arguments = listOf(`name`),
        )
        val `dishesTitle`: LocalizedString get() = LocalizedString(
            key = StringKey("food.dishesTitle", "food_dishes_title", emptyList(), 1),
        )
        val `dishesEmpty`: LocalizedString get() = LocalizedString(
            key = StringKey("food.dishesEmpty", "food_dishes_empty", emptyList(), 1),
        )
        val `servings`: LocalizedString get() = LocalizedString(
            key = StringKey("food.servings", "food_servings", emptyList(), 1),
        )
        val `logDish`: LocalizedString get() = LocalizedString(
            key = StringKey("food.logDish", "food_log_dish", emptyList(), 1),
        )
        val `labelTitle`: LocalizedString get() = LocalizedString(
            key = StringKey("food.labelTitle", "food_label_title", emptyList(), 1),
        )
        val `labelHint`: LocalizedString get() = LocalizedString(
            key = StringKey("food.labelHint", "food_label_hint", emptyList(), 1),
        )
        val `name`: LocalizedString get() = LocalizedString(
            key = StringKey("food.name", "food_name", emptyList(), 1),
        )
        val `brand`: LocalizedString get() = LocalizedString(
            key = StringKey("food.brand", "food_brand", emptyList(), 1),
        )
        val `basis`: LocalizedString get() = LocalizedString(
            key = StringKey("food.basis", "food_basis", emptyList(), 1),
        )
        val `energy`: LocalizedString get() = LocalizedString(
            key = StringKey("food.energy", "food_energy", emptyList(), 1),
        )
        val `protein`: LocalizedString get() = LocalizedString(
            key = StringKey("food.protein", "food_protein", emptyList(), 1),
        )
        val `fat`: LocalizedString get() = LocalizedString(
            key = StringKey("food.fat", "food_fat", emptyList(), 1),
        )
        val `carbs`: LocalizedString get() = LocalizedString(
            key = StringKey("food.carbs", "food_carbs", emptyList(), 1),
        )
        val `fiber`: LocalizedString get() = LocalizedString(
            key = StringKey("food.fiber", "food_fiber", emptyList(), 1),
        )
        val `createProduct`: LocalizedString get() = LocalizedString(
            key = StringKey("food.createProduct", "food_create_product", emptyList(), 1),
        )
        val `goalTitle`: LocalizedString get() = LocalizedString(
            key = StringKey("food.goalTitle", "food_goal_title", emptyList(), 1),
        )
        val `goalHint`: LocalizedString get() = LocalizedString(
            key = StringKey("food.goalHint", "food_goal_hint", emptyList(), 1),
        )
        val `calories`: LocalizedString get() = LocalizedString(
            key = StringKey("food.calories", "food_calories", emptyList(), 1),
        )
        val `from`: LocalizedString get() = LocalizedString(
            key = StringKey("food.from", "food_from", emptyList(), 1),
        )
        val `goalSaved`: LocalizedString get() = LocalizedString(
            key = StringKey("food.goalSaved", "food_goal_saved", emptyList(), 1),
        )
        val `exportTitle`: LocalizedString get() = LocalizedString(
            key = StringKey("food.exportTitle", "food_export_title", emptyList(), 1),
        )
        val `exportCsv`: LocalizedString get() = LocalizedString(
            key = StringKey("food.exportCsv", "food_export_csv", emptyList(), 1),
        )
        val `exportJson`: LocalizedString get() = LocalizedString(
            key = StringKey("food.exportJson", "food_export_json", emptyList(), 1),
        )
        val `exportHint`: LocalizedString get() = LocalizedString(
            key = StringKey("food.exportHint", "food_export_hint", emptyList(), 1),
        )
        val `complete`: LocalizedString get() = LocalizedString(
            key = StringKey("food.complete", "food_complete", emptyList(), 1),
        )
        val `completeTitle`: LocalizedString get() = LocalizedString(
            key = StringKey("food.completeTitle", "food_complete_title", emptyList(), 1),
        )
        val `completeText`: LocalizedString get() = LocalizedString(
            key = StringKey("food.completeText", "food_complete_text", emptyList(), 1),
        )
        val `completeConfirm`: LocalizedString get() = LocalizedString(
            key = StringKey("food.completeConfirm", "food_complete_confirm", emptyList(), 1),
        )
        val `completed`: LocalizedString get() = LocalizedString(
            key = StringKey("food.completed", "food_completed", emptyList(), 1),
        )
        object `CannotComplete` {
            val `empty`: LocalizedString get() = LocalizedString(
                key = StringKey("food.cannotComplete.empty", "food_cannot_complete_empty", emptyList(), 1),
            )
            val `drafts`: LocalizedString get() = LocalizedString(
                key = StringKey("food.cannotComplete.drafts", "food_cannot_complete_drafts", emptyList(), 1),
            )
            val `incomplete`: LocalizedString get() = LocalizedString(
                key = StringKey("food.cannotComplete.incomplete", "food_cannot_complete_incomplete", emptyList(), 1),
            )
        }
        val `more`: LocalizedString get() = LocalizedString(
            key = StringKey("food.more", "food_more", emptyList(), 1),
        )
        fun `unresolved`(`kind`: String): LocalizedString = LocalizedString(
            key = StringKey("food.unresolved", "food_unresolved", listOf("kind"), 1),
            arguments = listOf(`kind`),
        )
        val `conflict`: LocalizedString get() = LocalizedString(
            key = StringKey("food.conflict", "food_conflict", emptyList(), 1),
        )
        val `invalidQuantity`: LocalizedString get() = LocalizedString(
            key = StringKey("food.invalidQuantity", "food_invalid_quantity", emptyList(), 1),
        )
        val `nameRequired`: LocalizedString get() = LocalizedString(
            key = StringKey("food.nameRequired", "food_name_required", emptyList(), 1),
        )
        fun `notNumbers`(`fields`: String): LocalizedString = LocalizedString(
            key = StringKey("food.notNumbers", "food_not_numbers", listOf("fields"), 1),
            arguments = listOf(`fields`),
        )
        val `dishesSearch`: LocalizedString get() = LocalizedString(
            key = StringKey("food.dishesSearch", "food_dishes_search", emptyList(), 1),
        )
    }
    object `Stats` {
        val `title`: LocalizedString get() = LocalizedString(
            key = StringKey("stats.title", "stats_title", emptyList(), 1),
        )
        val `week`: LocalizedString get() = LocalizedString(
            key = StringKey("stats.week", "stats_week", emptyList(), 1),
        )
        val `month`: LocalizedString get() = LocalizedString(
            key = StringKey("stats.month", "stats_month", emptyList(), 1),
        )
        val `periodKind`: LocalizedString get() = LocalizedString(
            key = StringKey("stats.periodKind", "stats_period_kind", emptyList(), 1),
        )
        val `earlier`: LocalizedString get() = LocalizedString(
            key = StringKey("stats.earlier", "stats_earlier", emptyList(), 1),
        )
        val `later`: LocalizedString get() = LocalizedString(
            key = StringKey("stats.later", "stats_later", emptyList(), 1),
        )
        val `empty`: LocalizedString get() = LocalizedString(
            key = StringKey("stats.empty", "stats_empty", emptyList(), 1),
        )
        val `emptyText`: LocalizedString get() = LocalizedString(
            key = StringKey("stats.emptyText", "stats_empty_text", emptyList(), 1),
        )
        val `chart`: LocalizedString get() = LocalizedString(
            key = StringKey("stats.chart", "stats_chart", emptyList(), 1),
        )
        object `Legend` {
            val `goal`: LocalizedString get() = LocalizedString(
                key = StringKey("stats.legend.goal", "stats_legend_goal", emptyList(), 1),
            )
            val `within`: LocalizedString get() = LocalizedString(
                key = StringKey("stats.legend.within", "stats_legend_within", emptyList(), 1),
            )
            val `over`: LocalizedString get() = LocalizedString(
                key = StringKey("stats.legend.over", "stats_legend_over", emptyList(), 1),
            )
        }
        fun `dayBar`(`day`: String, `kcal`: String, `target`: String): LocalizedString = LocalizedString(
            key = StringKey("stats.dayBar", "stats_day_bar", listOf("day", "kcal", "target"), 1),
            arguments = listOf(`day`, `kcal`, `target`),
        )
        fun `dayEmpty`(`day`: String): LocalizedString = LocalizedString(
            key = StringKey("stats.dayEmpty", "stats_day_empty", listOf("day"), 1),
            arguments = listOf(`day`),
        )
        val `perDay`: LocalizedString get() = LocalizedString(
            key = StringKey("stats.perDay", "stats_per_day", emptyList(), 1),
        )
        fun `loggedDays`(`logged`: String, `days`: String): LocalizedString = LocalizedString(
            key = StringKey("stats.loggedDays", "stats_logged_days", listOf("logged", "days"), 1),
            arguments = listOf(`logged`, `days`),
        )
        fun `goal`(`value`: String): LocalizedString = LocalizedString(
            key = StringKey("stats.goal", "stats_goal", listOf("value"), 1),
            arguments = listOf(`value`),
        )
        fun `completedDays`(quantity: Double, `count`: String, `total`: String): LocalizedString = LocalizedString(
            key = StringKey("stats.completedDays", "stats_completed_days", listOf("count", "total"), 3),
            arguments = listOf(`count`, `total`),
            quantity = quantity,
        )
        fun `nearGoal`(quantity: Double, `near`: String, `total`: String): LocalizedString = LocalizedString(
            key = StringKey("stats.nearGoal", "stats_near_goal", listOf("near", "total"), 3),
            arguments = listOf(`near`, `total`),
            quantity = quantity,
        )
        fun `overGoal`(`count`: String): LocalizedString = LocalizedString(
            key = StringKey("stats.overGoal", "stats_over_goal", listOf("count"), 1),
            arguments = listOf(`count`),
        )
        fun `underGoal`(`count`: String): LocalizedString = LocalizedString(
            key = StringKey("stats.underGoal", "stats_under_goal", listOf("count"), 1),
            arguments = listOf(`count`),
        )
        val `byKind`: LocalizedString get() = LocalizedString(
            key = StringKey("stats.byKind", "stats_by_kind", emptyList(), 1),
        )
    }
    object `Today` {
        val `title`: LocalizedString get() = LocalizedString(
            key = StringKey("today.title", "today_title", emptyList(), 1),
        )
        val `attention`: LocalizedString get() = LocalizedString(
            key = StringKey("today.attention", "today_attention", emptyList(), 1),
        )
        fun `attentionText`(quantity: Double, `count`: String): LocalizedString = LocalizedString(
            key = StringKey("today.attentionText", "today_attention_text", listOf("count"), 3),
            arguments = listOf(`count`),
            quantity = quantity,
        )
        val `open`: LocalizedString get() = LocalizedString(
            key = StringKey("today.open", "today_open", emptyList(), 1),
        )
        val `quick`: LocalizedString get() = LocalizedString(
            key = StringKey("today.quick", "today_quick", emptyList(), 1),
        )
        val `quickFood`: LocalizedString get() = LocalizedString(
            key = StringKey("today.quickFood", "today_quick_food", emptyList(), 1),
        )
        val `quickTask`: LocalizedString get() = LocalizedString(
            key = StringKey("today.quickTask", "today_quick_task", emptyList(), 1),
        )
        val `quickWeight`: LocalizedString get() = LocalizedString(
            key = StringKey("today.quickWeight", "today_quick_weight", emptyList(), 1),
        )
        val `quickTimer`: LocalizedString get() = LocalizedString(
            key = StringKey("today.quickTimer", "today_quick_timer", emptyList(), 1),
        )
        val `food`: LocalizedString get() = LocalizedString(
            key = StringKey("today.food", "today_food", emptyList(), 1),
        )
        val `foodEmpty`: LocalizedString get() = LocalizedString(
            key = StringKey("today.foodEmpty", "today_food_empty", emptyList(), 1),
        )
        object `Sections` {
            val `nutrition`: LocalizedString get() = LocalizedString(
                key = StringKey("today.sections.nutrition", "today_sections_nutrition", emptyList(), 1),
            )
            val `tasks`: LocalizedString get() = LocalizedString(
                key = StringKey("today.sections.tasks", "today_sections_tasks", emptyList(), 1),
            )
            val `timers`: LocalizedString get() = LocalizedString(
                key = StringKey("today.sections.timers", "today_sections_timers", emptyList(), 1),
            )
            val `personal`: LocalizedString get() = LocalizedString(
                key = StringKey("today.sections.personal", "today_sections_personal", emptyList(), 1),
            )
            val `conversation`: LocalizedString get() = LocalizedString(
                key = StringKey("today.sections.conversation", "today_sections_conversation", emptyList(), 1),
            )
        }
        val `tasks`: LocalizedString get() = LocalizedString(
            key = StringKey("today.tasks", "today_tasks", emptyList(), 1),
        )
        val `tasksEmpty`: LocalizedString get() = LocalizedString(
            key = StringKey("today.tasksEmpty", "today_tasks_empty", emptyList(), 1),
        )
        val `allTasks`: LocalizedString get() = LocalizedString(
            key = StringKey("today.allTasks", "today_all_tasks", emptyList(), 1),
        )
        val `timers`: LocalizedString get() = LocalizedString(
            key = StringKey("today.timers", "today_timers", emptyList(), 1),
        )
        val `personal`: LocalizedString get() = LocalizedString(
            key = StringKey("today.personal", "today_personal", emptyList(), 1),
        )
        val `weight`: LocalizedString get() = LocalizedString(
            key = StringKey("today.weight", "today_weight", emptyList(), 1),
        )
        fun `weightValue`(`value`: String): LocalizedString = LocalizedString(
            key = StringKey("today.weightValue", "today_weight_value", listOf("value"), 1),
            arguments = listOf(`value`),
        )
        fun `measured`(`when`: String): LocalizedString = LocalizedString(
            key = StringKey("today.measured", "today_measured", listOf("when"), 1),
            arguments = listOf(`when`),
        )
        val `noWeight`: LocalizedString get() = LocalizedString(
            key = StringKey("today.noWeight", "today_no_weight", emptyList(), 1),
        )
        val `cardError`: LocalizedString get() = LocalizedString(
            key = StringKey("today.cardError", "today_card_error", emptyList(), 1),
        )
        val `startFood`: LocalizedString get() = LocalizedString(
            key = StringKey("today.startFood", "today_start_food", emptyList(), 1),
        )
        val `startTask`: LocalizedString get() = LocalizedString(
            key = StringKey("today.startTask", "today_start_task", emptyList(), 1),
        )
        val `startWeight`: LocalizedString get() = LocalizedString(
            key = StringKey("today.startWeight", "today_start_weight", emptyList(), 1),
        )
        val `notifications`: LocalizedString get() = LocalizedString(
            key = StringKey("today.notifications", "today_notifications", emptyList(), 1),
        )
    }
    object `Notifications` {
        val `title`: LocalizedString get() = LocalizedString(
            key = StringKey("notifications.title", "notifications_title", emptyList(), 1),
        )
        val `one`: LocalizedString get() = LocalizedString(
            key = StringKey("notifications.one", "notifications_one", emptyList(), 1),
        )
        val `new`: LocalizedString get() = LocalizedString(
            key = StringKey("notifications.new", "notifications_new", emptyList(), 1),
        )
        fun `times`(quantity: Double, `count`: String): LocalizedString = LocalizedString(
            key = StringKey("notifications.times", "notifications_times", listOf("count"), 3),
            arguments = listOf(`count`),
            quantity = quantity,
        )
        val `open`: LocalizedString get() = LocalizedString(
            key = StringKey("notifications.open", "notifications_open", emptyList(), 1),
        )
        val `openAll`: LocalizedString get() = LocalizedString(
            key = StringKey("notifications.openAll", "notifications_open_all", emptyList(), 1),
        )
        val `markRead`: LocalizedString get() = LocalizedString(
            key = StringKey("notifications.markRead", "notifications_mark_read", emptyList(), 1),
        )
        val `readAll`: LocalizedString get() = LocalizedString(
            key = StringKey("notifications.readAll", "notifications_read_all", emptyList(), 1),
        )
        val `laterHour`: LocalizedString get() = LocalizedString(
            key = StringKey("notifications.laterHour", "notifications_later_hour", emptyList(), 1),
        )
        val `laterMorning`: LocalizedString get() = LocalizedString(
            key = StringKey("notifications.laterMorning", "notifications_later_morning", emptyList(), 1),
        )
        fun `snoozedUntil`(`when`: String): LocalizedString = LocalizedString(
            key = StringKey("notifications.snoozedUntil", "notifications_snoozed_until", listOf("when"), 1),
            arguments = listOf(`when`),
        )
        val `resolved`: LocalizedString get() = LocalizedString(
            key = StringKey("notifications.resolved", "notifications_resolved", emptyList(), 1),
        )
        fun `resolvedAt`(`when`: String): LocalizedString = LocalizedString(
            key = StringKey("notifications.resolvedAt", "notifications_resolved_at", listOf("when"), 1),
            arguments = listOf(`when`),
        )
        val `resolvedGroup`: LocalizedString get() = LocalizedString(
            key = StringKey("notifications.resolvedGroup", "notifications_resolved_group", emptyList(), 1),
        )
        val `allClear`: LocalizedString get() = LocalizedString(
            key = StringKey("notifications.allClear", "notifications_all_clear", emptyList(), 1),
        )
        fun `details`(`title`: String): LocalizedString = LocalizedString(
            key = StringKey("notifications.details", "notifications_details", listOf("title"), 1),
            arguments = listOf(`title`),
        )
        fun `more`(quantity: Double, `count`: String): LocalizedString = LocalizedString(
            key = StringKey("notifications.more", "notifications_more", listOf("count"), 3),
            arguments = listOf(`count`),
            quantity = quantity,
        )
        fun `bellUnread`(quantity: Double, `count`: String): LocalizedString = LocalizedString(
            key = StringKey("notifications.bellUnread", "notifications_bell_unread", listOf("count"), 3),
            arguments = listOf(`count`),
            quantity = quantity,
        )
        val `emptyTitle`: LocalizedString get() = LocalizedString(
            key = StringKey("notifications.emptyTitle", "notifications_empty_title", emptyList(), 1),
        )
        val `emptyText`: LocalizedString get() = LocalizedString(
            key = StringKey("notifications.emptyText", "notifications_empty_text", emptyList(), 1),
        )
        val `badgeOverflow`: LocalizedString get() = LocalizedString(
            key = StringKey("notifications.badgeOverflow", "notifications_badge_overflow", emptyList(), 1),
        )
    }
    object `Settings` {
        val `title`: LocalizedString get() = LocalizedString(
            key = StringKey("settings.title", "settings_title", emptyList(), 1),
        )
        val `location`: LocalizedString get() = LocalizedString(
            key = StringKey("settings.location", "settings_location", emptyList(), 1),
        )
        val `locationText`: LocalizedString get() = LocalizedString(
            key = StringKey("settings.locationText", "settings_location_text", emptyList(), 1),
        )
        val `account`: LocalizedString get() = LocalizedString(
            key = StringKey("settings.account", "settings_account", emptyList(), 1),
        )
        val `name`: LocalizedString get() = LocalizedString(
            key = StringKey("settings.name", "settings_name", emptyList(), 1),
        )
        val `timezone`: LocalizedString get() = LocalizedString(
            key = StringKey("settings.timezone", "settings_timezone", emptyList(), 1),
        )
        val `locale`: LocalizedString get() = LocalizedString(
            key = StringKey("settings.locale", "settings_locale", emptyList(), 1),
        )
        val `timezoneHint`: LocalizedString get() = LocalizedString(
            key = StringKey("settings.timezoneHint", "settings_timezone_hint", emptyList(), 1),
        )
        val `saved`: LocalizedString get() = LocalizedString(
            key = StringKey("settings.saved", "settings_saved", emptyList(), 1),
        )
        val `appearance`: LocalizedString get() = LocalizedString(
            key = StringKey("settings.appearance", "settings_appearance", emptyList(), 1),
        )
        val `signOut`: LocalizedString get() = LocalizedString(
            key = StringKey("settings.signOut", "settings_sign_out", emptyList(), 1),
        )
        val `signOutHint`: LocalizedString get() = LocalizedString(
            key = StringKey("settings.signOutHint", "settings_sign_out_hint", emptyList(), 1),
        )
        val `accountText`: LocalizedString get() = LocalizedString(
            key = StringKey("settings.accountText", "settings_account_text", emptyList(), 1),
        )
        val `nameRequired`: LocalizedString get() = LocalizedString(
            key = StringKey("settings.nameRequired", "settings_name_required", emptyList(), 1),
        )
        val `bodyElsewhere`: LocalizedString get() = LocalizedString(
            key = StringKey("settings.bodyElsewhere", "settings_body_elsewhere", emptyList(), 1),
        )
        val `limits`: LocalizedString get() = LocalizedString(
            key = StringKey("settings.limits", "settings_limits", emptyList(), 1),
        )
        object `Limit` {
            val `assistant`: LocalizedString get() = LocalizedString(
                key = StringKey("settings.limit.assistant", "settings_limit_assistant", emptyList(), 1),
            )
            val `research`: LocalizedString get() = LocalizedString(
                key = StringKey("settings.limit.research", "settings_limit_research", emptyList(), 1),
            )
            val `dictation_ms`: LocalizedString get() = LocalizedString(
                key = StringKey("settings.limit.dictation_ms", "settings_limit_dictation_ms", emptyList(), 1),
            )
            val `tts_chars`: LocalizedString get() = LocalizedString(
                key = StringKey("settings.limit.tts_chars", "settings_limit_tts_chars", emptyList(), 1),
            )
        }
        fun `used`(`used`: String, `maximum`: String): LocalizedString = LocalizedString(
            key = StringKey("settings.used", "settings_used", listOf("used", "maximum"), 1),
            arguments = listOf(`used`, `maximum`),
        )
        fun `usedMinutes`(`used`: String, `maximum`: String): LocalizedString = LocalizedString(
            key = StringKey("settings.usedMinutes", "settings_used_minutes", listOf("used", "maximum"), 1),
            arguments = listOf(`used`, `maximum`),
        )
        fun `usedUnlimited`(`used`: String): LocalizedString = LocalizedString(
            key = StringKey("settings.usedUnlimited", "settings_used_unlimited", listOf("used"), 1),
            arguments = listOf(`used`),
        )
        val `usedUp`: LocalizedString get() = LocalizedString(
            key = StringKey("settings.usedUp", "settings_used_up", emptyList(), 1),
        )
        fun `reset`(`when`: String): LocalizedString = LocalizedString(
            key = StringKey("settings.reset", "settings_reset", listOf("when"), 1),
            arguments = listOf(`when`),
        )
        val `more`: LocalizedString get() = LocalizedString(
            key = StringKey("settings.more", "settings_more", emptyList(), 1),
        )
        val `capabilities`: LocalizedString get() = LocalizedString(
            key = StringKey("settings.capabilities", "settings_capabilities", emptyList(), 1),
        )
        val `capabilitiesText`: LocalizedString get() = LocalizedString(
            key = StringKey("settings.capabilitiesText", "settings_capabilities_text", emptyList(), 1),
        )
        val `admin`: LocalizedString get() = LocalizedString(
            key = StringKey("settings.admin", "settings_admin", emptyList(), 1),
        )
        val `adminText`: LocalizedString get() = LocalizedString(
            key = StringKey("settings.adminText", "settings_admin_text", emptyList(), 1),
        )
        val `leaveTitle`: LocalizedString get() = LocalizedString(
            key = StringKey("settings.leaveTitle", "settings_leave_title", emptyList(), 1),
        )
        fun `leaveText`(quantity: Double, `count`: String): LocalizedString = LocalizedString(
            key = StringKey("settings.leaveText", "settings_leave_text", listOf("count"), 3),
            arguments = listOf(`count`),
            quantity = quantity,
        )
        val `stay`: LocalizedString get() = LocalizedString(
            key = StringKey("settings.stay", "settings_stay", emptyList(), 1),
        )
        val `leaveAnyway`: LocalizedString get() = LocalizedString(
            key = StringKey("settings.leaveAnyway", "settings_leave_anyway", emptyList(), 1),
        )
        val `devices`: LocalizedString get() = LocalizedString(
            key = StringKey("settings.devices", "settings_devices", emptyList(), 1),
        )
        val `devicesText`: LocalizedString get() = LocalizedString(
            key = StringKey("settings.devicesText", "settings_devices_text", emptyList(), 1),
        )
        val `notifications`: LocalizedString get() = LocalizedString(
            key = StringKey("settings.notifications", "settings_notifications", emptyList(), 1),
        )
        val `notificationsText`: LocalizedString get() = LocalizedString(
            key = StringKey("settings.notificationsText", "settings_notifications_text", emptyList(), 1),
        )
        val `developer`: LocalizedString get() = LocalizedString(
            key = StringKey("settings.developer", "settings_developer", emptyList(), 1),
        )
        val `developerShow`: LocalizedString get() = LocalizedString(
            key = StringKey("settings.developerShow", "settings_developer_show", emptyList(), 1),
        )
        val `developerText`: LocalizedString get() = LocalizedString(
            key = StringKey("settings.developerText", "settings_developer_text", emptyList(), 1),
        )
        val `sharing`: LocalizedString get() = LocalizedString(
            key = StringKey("settings.sharing", "settings_sharing", emptyList(), 1),
        )
        val `sharingText`: LocalizedString get() = LocalizedString(
            key = StringKey("settings.sharingText", "settings_sharing_text", emptyList(), 1),
        )
    }
    object `Tasks` {
        object `Images` {
            val `add`: LocalizedString get() = LocalizedString(
                key = StringKey("tasks.images.add", "tasks_images_add", emptyList(), 1),
            )
            fun `label`(`field`: String): LocalizedString = LocalizedString(
                key = StringKey("tasks.images.label", "tasks_images_label", listOf("field"), 1),
                arguments = listOf(`field`),
            )
            fun `open`(`index`: String): LocalizedString = LocalizedString(
                key = StringKey("tasks.images.open", "tasks_images_open", listOf("index"), 1),
                arguments = listOf(`index`),
            )
            fun `remove`(`index`: String): LocalizedString = LocalizedString(
                key = StringKey("tasks.images.remove", "tasks_images_remove", listOf("index"), 1),
                arguments = listOf(`index`),
            )
            val `uploading`: LocalizedString get() = LocalizedString(
                key = StringKey("tasks.images.uploading", "tasks_images_uploading", emptyList(), 1),
            )
            val `duplicate`: LocalizedString get() = LocalizedString(
                key = StringKey("tasks.images.duplicate", "tasks_images_duplicate", emptyList(), 1),
            )
            val `unavailable`: LocalizedString get() = LocalizedString(
                key = StringKey("tasks.images.unavailable", "tasks_images_unavailable", emptyList(), 1),
            )
            fun `limit`(quantity: Double, `count`: String): LocalizedString = LocalizedString(
                key = StringKey("tasks.images.limit", "tasks_images_limit", listOf("count"), 3),
                arguments = listOf(`count`),
                quantity = quantity,
            )
            fun `count`(quantity: Double, `count`: String): LocalizedString = LocalizedString(
                key = StringKey("tasks.images.count", "tasks_images_count", listOf("count"), 3),
                arguments = listOf(`count`),
                quantity = quantity,
            )
            val `none`: LocalizedString get() = LocalizedString(
                key = StringKey("tasks.images.none", "tasks_images_none", emptyList(), 1),
            )
            val `viewer`: LocalizedString get() = LocalizedString(
                key = StringKey("tasks.images.viewer", "tasks_images_viewer", emptyList(), 1),
            )
        }
        val `title`: LocalizedString get() = LocalizedString(
            key = StringKey("tasks.title", "tasks_title", emptyList(), 1),
        )
        val `tasks`: LocalizedString get() = LocalizedString(
            key = StringKey("tasks.tasks", "tasks_tasks", emptyList(), 1),
        )
        object `Views` {
            val `today`: LocalizedString get() = LocalizedString(
                key = StringKey("tasks.views.today", "tasks_views_today", emptyList(), 1),
            )
            val `upcoming`: LocalizedString get() = LocalizedString(
                key = StringKey("tasks.views.upcoming", "tasks_views_upcoming", emptyList(), 1),
            )
            val `open`: LocalizedString get() = LocalizedString(
                key = StringKey("tasks.views.open", "tasks_views_open", emptyList(), 1),
            )
            val `done`: LocalizedString get() = LocalizedString(
                key = StringKey("tasks.views.done", "tasks_views_done", emptyList(), 1),
            )
            val `all`: LocalizedString get() = LocalizedString(
                key = StringKey("tasks.views.all", "tasks_views_all", emptyList(), 1),
            )
            val `trash`: LocalizedString get() = LocalizedString(
                key = StringKey("tasks.views.trash", "tasks_views_trash", emptyList(), 1),
            )
        }
        val `search`: LocalizedString get() = LocalizedString(
            key = StringKey("tasks.search", "tasks_search", emptyList(), 1),
        )
        object `Filters` {
            val `action`: LocalizedString get() = LocalizedString(
                key = StringKey("tasks.filters.action", "tasks_filters_action", emptyList(), 1),
            )
            fun `actionCount`(`count`: String): LocalizedString = LocalizedString(
                key = StringKey("tasks.filters.actionCount", "tasks_filters_action_count", listOf("count"), 1),
                arguments = listOf(`count`),
            )
            val `combine`: LocalizedString get() = LocalizedString(
                key = StringKey("tasks.filters.combine", "tasks_filters_combine", emptyList(), 1),
            )
            val `important`: LocalizedString get() = LocalizedString(
                key = StringKey("tasks.filters.important", "tasks_filters_important", emptyList(), 1),
            )
            val `overdue`: LocalizedString get() = LocalizedString(
                key = StringKey("tasks.filters.overdue", "tasks_filters_overdue", emptyList(), 1),
            )
            val `assignee`: LocalizedString get() = LocalizedString(
                key = StringKey("tasks.filters.assignee", "tasks_filters_assignee", emptyList(), 1),
            )
            object `Assignees` {
                val `any`: LocalizedString get() = LocalizedString(
                    key = StringKey("tasks.filters.assignees.any", "tasks_filters_assignees_any", emptyList(), 1),
                )
                val `me`: LocalizedString get() = LocalizedString(
                    key = StringKey("tasks.filters.assignees.me", "tasks_filters_assignees_me", emptyList(), 1),
                )
                val `unassigned`: LocalizedString get() = LocalizedString(
                    key = StringKey("tasks.filters.assignees.unassigned", "tasks_filters_assignees_unassigned", emptyList(), 1),
                )
            }
            val `completedRecent`: LocalizedString get() = LocalizedString(
                key = StringKey("tasks.filters.completedRecent", "tasks_filters_completed_recent", emptyList(), 1),
            )
            val `completedAll`: LocalizedString get() = LocalizedString(
                key = StringKey("tasks.filters.completedAll", "tasks_filters_completed_all", emptyList(), 1),
            )
            val `searchAllHistory`: LocalizedString get() = LocalizedString(
                key = StringKey("tasks.filters.searchAllHistory", "tasks_filters_search_all_history", emptyList(), 1),
            )
            val `recentOnly`: LocalizedString get() = LocalizedString(
                key = StringKey("tasks.filters.recentOnly", "tasks_filters_recent_only", emptyList(), 1),
            )
            val `allHistory`: LocalizedString get() = LocalizedString(
                key = StringKey("tasks.filters.allHistory", "tasks_filters_all_history", emptyList(), 1),
            )
            val `reset`: LocalizedString get() = LocalizedString(
                key = StringKey("tasks.filters.reset", "tasks_filters_reset", emptyList(), 1),
            )
            val `active`: LocalizedString get() = LocalizedString(
                key = StringKey("tasks.filters.active", "tasks_filters_active", emptyList(), 1),
            )
            fun `remove`(`filter`: String): LocalizedString = LocalizedString(
                key = StringKey("tasks.filters.remove", "tasks_filters_remove", listOf("filter"), 1),
                arguments = listOf(`filter`),
            )
            fun `query`(`query`: String): LocalizedString = LocalizedString(
                key = StringKey("tasks.filters.query", "tasks_filters_query", listOf("query"), 1),
                arguments = listOf(`query`),
            )
            fun `results`(`count`: String): LocalizedString = LocalizedString(
                key = StringKey("tasks.filters.results", "tasks_filters_results", listOf("count"), 1),
                arguments = listOf(`count`),
            )
            val `updating`: LocalizedString get() = LocalizedString(
                key = StringKey("tasks.filters.updating", "tasks_filters_updating", emptyList(), 1),
            )
            fun `projectScope`(`project`: String): LocalizedString = LocalizedString(
                key = StringKey("tasks.filters.projectScope", "tasks_filters_project_scope", listOf("project"), 1),
                arguments = listOf(`project`),
            )
            val `noneInProject`: LocalizedString get() = LocalizedString(
                key = StringKey("tasks.filters.noneInProject", "tasks_filters_none_in_project", emptyList(), 1),
            )
            val `noneInColumn`: LocalizedString get() = LocalizedString(
                key = StringKey("tasks.filters.noneInColumn", "tasks_filters_none_in_column", emptyList(), 1),
            )
            val `none`: LocalizedString get() = LocalizedString(
                key = StringKey("tasks.filters.none", "tasks_filters_none", emptyList(), 1),
            )
        }
        val `quickAdd`: LocalizedString get() = LocalizedString(
            key = StringKey("tasks.quickAdd", "tasks_quick_add", emptyList(), 1),
        )
        val `dateToday`: LocalizedString get() = LocalizedString(
            key = StringKey("tasks.dateToday", "tasks_date_today", emptyList(), 1),
        )
        val `dateTomorrow`: LocalizedString get() = LocalizedString(
            key = StringKey("tasks.dateTomorrow", "tasks_date_tomorrow", emptyList(), 1),
        )
        val `dateNone`: LocalizedString get() = LocalizedString(
            key = StringKey("tasks.dateNone", "tasks_date_none", emptyList(), 1),
        )
        val `datePick`: LocalizedString get() = LocalizedString(
            key = StringKey("tasks.datePick", "tasks_date_pick", emptyList(), 1),
        )
        val `remindAbout`: LocalizedString get() = LocalizedString(
            key = StringKey("tasks.remindAbout", "tasks_remind_about", emptyList(), 1),
        )
        val `reminder`: LocalizedString get() = LocalizedString(
            key = StringKey("tasks.reminder", "tasks_reminder", emptyList(), 1),
        )
        object `RemindPreset` {
            val `hour`: LocalizedString get() = LocalizedString(
                key = StringKey("tasks.remindPreset.hour", "tasks_remind_preset_hour", emptyList(), 1),
            )
            val `evening`: LocalizedString get() = LocalizedString(
                key = StringKey("tasks.remindPreset.evening", "tasks_remind_preset_evening", emptyList(), 1),
            )
            val `morning`: LocalizedString get() = LocalizedString(
                key = StringKey("tasks.remindPreset.morning", "tasks_remind_preset_morning", emptyList(), 1),
            )
        }
        val `remindOther`: LocalizedString get() = LocalizedString(
            key = StringKey("tasks.remindOther", "tasks_remind_other", emptyList(), 1),
        )
        val `remindSet`: LocalizedString get() = LocalizedString(
            key = StringKey("tasks.remindSet", "tasks_remind_set", emptyList(), 1),
        )
        fun `remindedAt`(`when`: String): LocalizedString = LocalizedString(
            key = StringKey("tasks.remindedAt", "tasks_reminded_at", listOf("when"), 1),
            arguments = listOf(`when`),
        )
        val `remindSilent`: LocalizedString get() = LocalizedString(
            key = StringKey("tasks.remindSilent", "tasks_remind_silent", emptyList(), 1),
        )
        val `reminderOfTask`: LocalizedString get() = LocalizedString(
            key = StringKey("tasks.reminderOfTask", "tasks_reminder_of_task", emptyList(), 1),
        )
        val `add`: LocalizedString get() = LocalizedString(
            key = StringKey("tasks.add", "tasks_add", emptyList(), 1),
        )
        val `added`: LocalizedString get() = LocalizedString(
            key = StringKey("tasks.added", "tasks_added", emptyList(), 1),
        )
        fun `planned`(`date`: String): LocalizedString = LocalizedString(
            key = StringKey("tasks.planned", "tasks_planned", listOf("date"), 1),
            arguments = listOf(`date`),
        )
        fun `due`(`date`: String): LocalizedString = LocalizedString(
            key = StringKey("tasks.due", "tasks_due", listOf("date"), 1),
            arguments = listOf(`date`),
        )
        fun `overdue`(`date`: String): LocalizedString = LocalizedString(
            key = StringKey("tasks.overdue", "tasks_overdue", listOf("date"), 1),
            arguments = listOf(`date`),
        )
        val `complete`: LocalizedString get() = LocalizedString(
            key = StringKey("tasks.complete", "tasks_complete", emptyList(), 1),
        )
        val `reopen`: LocalizedString get() = LocalizedString(
            key = StringKey("tasks.reopen", "tasks_reopen", emptyList(), 1),
        )
        val `completed`: LocalizedString get() = LocalizedString(
            key = StringKey("tasks.completed", "tasks_completed", emptyList(), 1),
        )
        val `reopened`: LocalizedString get() = LocalizedString(
            key = StringKey("tasks.reopened", "tasks_reopened", emptyList(), 1),
        )
        val `more`: LocalizedString get() = LocalizedString(
            key = StringKey("tasks.more", "tasks_more", emptyList(), 1),
        )
        object `Empty` {
            val `today`: LocalizedString get() = LocalizedString(
                key = StringKey("tasks.empty.today", "tasks_empty_today", emptyList(), 1),
            )
            val `upcoming`: LocalizedString get() = LocalizedString(
                key = StringKey("tasks.empty.upcoming", "tasks_empty_upcoming", emptyList(), 1),
            )
            val `open`: LocalizedString get() = LocalizedString(
                key = StringKey("tasks.empty.open", "tasks_empty_open", emptyList(), 1),
            )
            val `done`: LocalizedString get() = LocalizedString(
                key = StringKey("tasks.empty.done", "tasks_empty_done", emptyList(), 1),
            )
            val `all`: LocalizedString get() = LocalizedString(
                key = StringKey("tasks.empty.all", "tasks_empty_all", emptyList(), 1),
            )
            val `trash`: LocalizedString get() = LocalizedString(
                key = StringKey("tasks.empty.trash", "tasks_empty_trash", emptyList(), 1),
            )
        }
        val `emptyText`: LocalizedString get() = LocalizedString(
            key = StringKey("tasks.emptyText", "tasks_empty_text", emptyList(), 1),
        )
        val `hiddenByView`: LocalizedString get() = LocalizedString(
            key = StringKey("tasks.hiddenByView", "tasks_hidden_by_view", emptyList(), 1),
        )
        val `showOpen`: LocalizedString get() = LocalizedString(
            key = StringKey("tasks.showOpen", "tasks_show_open", emptyList(), 1),
        )
        fun `searchEmpty`(`q`: String): LocalizedString = LocalizedString(
            key = StringKey("tasks.searchEmpty", "tasks_search_empty", listOf("q"), 1),
            arguments = listOf(`q`),
        )
        val `detail`: LocalizedString get() = LocalizedString(
            key = StringKey("tasks.detail", "tasks_detail", emptyList(), 1),
        )
        val `moreActions`: LocalizedString get() = LocalizedString(
            key = StringKey("tasks.moreActions", "tasks_more_actions", emptyList(), 1),
        )
        val `cardNavigation`: LocalizedString get() = LocalizedString(
            key = StringKey("tasks.cardNavigation", "tasks_card_navigation", emptyList(), 1),
        )
        val `previousTask`: LocalizedString get() = LocalizedString(
            key = StringKey("tasks.previousTask", "tasks_previous_task", emptyList(), 1),
        )
        val `nextTask`: LocalizedString get() = LocalizedString(
            key = StringKey("tasks.nextTask", "tasks_next_task", emptyList(), 1),
        )
        val `saveBeforeNavigation`: LocalizedString get() = LocalizedString(
            key = StringKey("tasks.saveBeforeNavigation", "tasks_save_before_navigation", emptyList(), 1),
        )
        val `name`: LocalizedString get() = LocalizedString(
            key = StringKey("tasks.name", "tasks_name", emptyList(), 1),
        )
        val `description`: LocalizedString get() = LocalizedString(
            key = StringKey("tasks.description", "tasks_description", emptyList(), 1),
        )
        val `plannedOn`: LocalizedString get() = LocalizedString(
            key = StringKey("tasks.plannedOn", "tasks_planned_on", emptyList(), 1),
        )
        val `dueOn`: LocalizedString get() = LocalizedString(
            key = StringKey("tasks.dueOn", "tasks_due_on", emptyList(), 1),
        )
        val `noDate`: LocalizedString get() = LocalizedString(
            key = StringKey("tasks.noDate", "tasks_no_date", emptyList(), 1),
        )
        val `plannedAfterDue`: LocalizedString get() = LocalizedString(
            key = StringKey("tasks.plannedAfterDue", "tasks_planned_after_due", emptyList(), 1),
        )
        val `save`: LocalizedString get() = LocalizedString(
            key = StringKey("tasks.save", "tasks_save", emptyList(), 1),
        )
        val `saved`: LocalizedString get() = LocalizedString(
            key = StringKey("tasks.saved", "tasks_saved", emptyList(), 1),
        )
        val `toTrash`: LocalizedString get() = LocalizedString(
            key = StringKey("tasks.toTrash", "tasks_to_trash", emptyList(), 1),
        )
        val `trashed`: LocalizedString get() = LocalizedString(
            key = StringKey("tasks.trashed", "tasks_trashed", emptyList(), 1),
        )
        val `restore`: LocalizedString get() = LocalizedString(
            key = StringKey("tasks.restore", "tasks_restore", emptyList(), 1),
        )
        val `restored`: LocalizedString get() = LocalizedString(
            key = StringKey("tasks.restored", "tasks_restored", emptyList(), 1),
        )
        val `trashHint`: LocalizedString get() = LocalizedString(
            key = StringKey("tasks.trashHint", "tasks_trash_hint", emptyList(), 1),
        )
        object `Status` {
            val `open`: LocalizedString get() = LocalizedString(
                key = StringKey("tasks.status.open", "tasks_status_open", emptyList(), 1),
            )
            val `done`: LocalizedString get() = LocalizedString(
                key = StringKey("tasks.status.done", "tasks_status_done", emptyList(), 1),
            )
            val `trash`: LocalizedString get() = LocalizedString(
                key = StringKey("tasks.status.trash", "tasks_status_trash", emptyList(), 1),
            )
        }
        fun `updated`(`time`: String): LocalizedString = LocalizedString(
            key = StringKey("tasks.updated", "tasks_updated", listOf("time"), 1),
            arguments = listOf(`time`),
        )
        val `conflictTitle`: LocalizedString get() = LocalizedString(
            key = StringKey("tasks.conflictTitle", "tasks_conflict_title", emptyList(), 1),
        )
        val `conflictText`: LocalizedString get() = LocalizedString(
            key = StringKey("tasks.conflictText", "tasks_conflict_text", emptyList(), 1),
        )
        val `onServer`: LocalizedString get() = LocalizedString(
            key = StringKey("tasks.onServer", "tasks_on_server", emptyList(), 1),
        )
        val `yours`: LocalizedString get() = LocalizedString(
            key = StringKey("tasks.yours", "tasks_yours", emptyList(), 1),
        )
        val `apply`: LocalizedString get() = LocalizedString(
            key = StringKey("tasks.apply", "tasks_apply", emptyList(), 1),
        )
        val `discard`: LocalizedString get() = LocalizedString(
            key = StringKey("tasks.discard", "tasks_discard", emptyList(), 1),
        )
        val `draftRestored`: LocalizedString get() = LocalizedString(
            key = StringKey("tasks.draftRestored", "tasks_draft_restored", emptyList(), 1),
        )
        val `stale`: LocalizedString get() = LocalizedString(
            key = StringKey("tasks.stale", "tasks_stale", emptyList(), 1),
        )
        fun `unresolved`(`kind`: String): LocalizedString = LocalizedString(
            key = StringKey("tasks.unresolved", "tasks_unresolved", listOf("kind"), 1),
            arguments = listOf(`kind`),
        )
        val `timersEmpty`: LocalizedString get() = LocalizedString(
            key = StringKey("tasks.timersEmpty", "tasks_timers_empty", emptyList(), 1),
        )
        val `timersTitle`: LocalizedString get() = LocalizedString(
            key = StringKey("tasks.timersTitle", "tasks_timers_title", emptyList(), 1),
        )
        val `timer`: LocalizedString get() = LocalizedString(
            key = StringKey("tasks.timer", "tasks_timer", emptyList(), 1),
        )
        object `TimersPush` {
            val `on`: LocalizedString get() = LocalizedString(
                key = StringKey("tasks.timersPush.on", "tasks_timers_push_on", emptyList(), 1),
            )
            val `off`: LocalizedString get() = LocalizedString(
                key = StringKey("tasks.timersPush.off", "tasks_timers_push_off", emptyList(), 1),
            )
            val `unsupported`: LocalizedString get() = LocalizedString(
                key = StringKey("tasks.timersPush.unsupported", "tasks_timers_push_unsupported", emptyList(), 1),
            )
            val `enable`: LocalizedString get() = LocalizedString(
                key = StringKey("tasks.timersPush.enable", "tasks_timers_push_enable", emptyList(), 1),
            )
        }
        val `newTimer`: LocalizedString get() = LocalizedString(
            key = StringKey("tasks.newTimer", "tasks_new_timer", emptyList(), 1),
        )
        val `minutes`: LocalizedString get() = LocalizedString(
            key = StringKey("tasks.minutes", "tasks_minutes", emptyList(), 1),
        )
        val `label`: LocalizedString get() = LocalizedString(
            key = StringKey("tasks.label", "tasks_label", emptyList(), 1),
        )
        val `start`: LocalizedString get() = LocalizedString(
            key = StringKey("tasks.start", "tasks_start", emptyList(), 1),
        )
        val `started`: LocalizedString get() = LocalizedString(
            key = StringKey("tasks.started", "tasks_started", emptyList(), 1),
        )
        val `cancel`: LocalizedString get() = LocalizedString(
            key = StringKey("tasks.cancel", "tasks_cancel", emptyList(), 1),
        )
        val `cancelled`: LocalizedString get() = LocalizedString(
            key = StringKey("tasks.cancelled", "tasks_cancelled", emptyList(), 1),
        )
        val `finished`: LocalizedString get() = LocalizedString(
            key = StringKey("tasks.finished", "tasks_finished", emptyList(), 1),
        )
        val `recent`: LocalizedString get() = LocalizedString(
            key = StringKey("tasks.recent", "tasks_recent", emptyList(), 1),
        )
        val `presets`: LocalizedString get() = LocalizedString(
            key = StringKey("tasks.presets", "tasks_presets", emptyList(), 1),
        )
        fun `minutesShort`(`count`: String): LocalizedString = LocalizedString(
            key = StringKey("tasks.minutesShort", "tasks_minutes_short", listOf("count"), 1),
            arguments = listOf(`count`),
        )
        val `reminders`: LocalizedString get() = LocalizedString(
            key = StringKey("tasks.reminders", "tasks_reminders", emptyList(), 1),
        )
        val `remindersEmpty`: LocalizedString get() = LocalizedString(
            key = StringKey("tasks.remindersEmpty", "tasks_reminders_empty", emptyList(), 1),
        )
        val `remindersText`: LocalizedString get() = LocalizedString(
            key = StringKey("tasks.remindersText", "tasks_reminders_text", emptyList(), 1),
        )
        val `reminderText`: LocalizedString get() = LocalizedString(
            key = StringKey("tasks.reminderText", "tasks_reminder_text", emptyList(), 1),
        )
        val `reminderAt`: LocalizedString get() = LocalizedString(
            key = StringKey("tasks.reminderAt", "tasks_reminder_at", emptyList(), 1),
        )
        val `remind`: LocalizedString get() = LocalizedString(
            key = StringKey("tasks.remind", "tasks_remind", emptyList(), 1),
        )
        val `reminded`: LocalizedString get() = LocalizedString(
            key = StringKey("tasks.reminded", "tasks_reminded", emptyList(), 1),
        )
        val `reminderCancel`: LocalizedString get() = LocalizedString(
            key = StringKey("tasks.reminderCancel", "tasks_reminder_cancel", emptyList(), 1),
        )
        val `reminderCancelled`: LocalizedString get() = LocalizedString(
            key = StringKey("tasks.reminderCancelled", "tasks_reminder_cancelled", emptyList(), 1),
        )
        val `reminderPast`: LocalizedString get() = LocalizedString(
            key = StringKey("tasks.reminderPast", "tasks_reminder_past", emptyList(), 1),
        )
        val `project`: LocalizedString get() = LocalizedString(
            key = StringKey("tasks.project", "tasks_project", emptyList(), 1),
        )
        val `newProject`: LocalizedString get() = LocalizedString(
            key = StringKey("tasks.newProject", "tasks_new_project", emptyList(), 1),
        )
        val `projectSettings`: LocalizedString get() = LocalizedString(
            key = StringKey("tasks.projectSettings", "tasks_project_settings", emptyList(), 1),
        )
        val `projectName`: LocalizedString get() = LocalizedString(
            key = StringKey("tasks.projectName", "tasks_project_name", emptyList(), 1),
        )
        val `projectOutcome`: LocalizedString get() = LocalizedString(
            key = StringKey("tasks.projectOutcome", "tasks_project_outcome", emptyList(), 1),
        )
        val `projectAudience`: LocalizedString get() = LocalizedString(
            key = StringKey("tasks.projectAudience", "tasks_project_audience", emptyList(), 1),
        )
        val `onlyMe`: LocalizedString get() = LocalizedString(
            key = StringKey("tasks.onlyMe", "tasks_only_me", emptyList(), 1),
        )
        val `optional`: LocalizedString get() = LocalizedString(
            key = StringKey("tasks.optional", "tasks_optional", emptyList(), 1),
        )
        val `createProject`: LocalizedString get() = LocalizedString(
            key = StringKey("tasks.createProject", "tasks_create_project", emptyList(), 1),
        )
        val `members`: LocalizedString get() = LocalizedString(
            key = StringKey("tasks.members", "tasks_members", emptyList(), 1),
        )
        fun `progress`(`done`: String, `total`: String): LocalizedString = LocalizedString(
            key = StringKey("tasks.progress", "tasks_progress", listOf("done", "total"), 1),
            arguments = listOf(`done`, `total`),
        )
        fun `projectProgress`(`done`: String, `total`: String): LocalizedString = LocalizedString(
            key = StringKey("tasks.projectProgress", "tasks_project_progress", listOf("done", "total"), 1),
            arguments = listOf(`done`, `total`),
        )
        fun `projectProgressShort`(`done`: String, `total`: String): LocalizedString = LocalizedString(
            key = StringKey("tasks.projectProgressShort", "tasks_project_progress_short", listOf("done", "total"), 1),
            arguments = listOf(`done`, `total`),
        )
        val `layoutLabel`: LocalizedString get() = LocalizedString(
            key = StringKey("tasks.layoutLabel", "tasks_layout_label", emptyList(), 1),
        )
        object `Places` {
            val `label`: LocalizedString get() = LocalizedString(
                key = StringKey("tasks.places.label", "tasks_places_label", emptyList(), 1),
            )
            val `all`: LocalizedString get() = LocalizedString(
                key = StringKey("tasks.places.all", "tasks_places_all", emptyList(), 1),
            )
            val `assigned`: LocalizedString get() = LocalizedString(
                key = StringKey("tasks.places.assigned", "tasks_places_assigned", emptyList(), 1),
            )
            val `personal`: LocalizedString get() = LocalizedString(
                key = StringKey("tasks.places.personal", "tasks_places_personal", emptyList(), 1),
            )
            val `projects`: LocalizedString get() = LocalizedString(
                key = StringKey("tasks.places.projects", "tasks_places_projects", emptyList(), 1),
            )
            val `lists`: LocalizedString get() = LocalizedString(
                key = StringKey("tasks.places.lists", "tasks_places_lists", emptyList(), 1),
            )
            val `finished`: LocalizedString get() = LocalizedString(
                key = StringKey("tasks.places.finished", "tasks_places_finished", emptyList(), 1),
            )
            val `newProject`: LocalizedString get() = LocalizedString(
                key = StringKey("tasks.places.newProject", "tasks_places_new_project", emptyList(), 1),
            )
        }
        val `openProject`: LocalizedString get() = LocalizedString(
            key = StringKey("tasks.openProject", "tasks_open_project", emptyList(), 1),
        )
        object `Scopes` {
            val `all`: LocalizedString get() = LocalizedString(
                key = StringKey("tasks.scopes.all", "tasks_scopes_all", emptyList(), 1),
            )
            val `assigned`: LocalizedString get() = LocalizedString(
                key = StringKey("tasks.scopes.assigned", "tasks_scopes_assigned", emptyList(), 1),
            )
            val `personal`: LocalizedString get() = LocalizedString(
                key = StringKey("tasks.scopes.personal", "tasks_scopes_personal", emptyList(), 1),
            )
        }
        object `ProjectStates` {
            val `active`: LocalizedString get() = LocalizedString(
                key = StringKey("tasks.projectStates.active", "tasks_project_states_active", emptyList(), 1),
            )
            val `done`: LocalizedString get() = LocalizedString(
                key = StringKey("tasks.projectStates.done", "tasks_project_states_done", emptyList(), 1),
            )
            val `archived`: LocalizedString get() = LocalizedString(
                key = StringKey("tasks.projectStates.archived", "tasks_project_states_archived", emptyList(), 1),
            )
        }
        object `States` {
            val `todo`: LocalizedString get() = LocalizedString(
                key = StringKey("tasks.states.todo", "tasks_states_todo", emptyList(), 1),
            )
            val `in_progress`: LocalizedString get() = LocalizedString(
                key = StringKey("tasks.states.in_progress", "tasks_states_in_progress", emptyList(), 1),
            )
            val `waiting`: LocalizedString get() = LocalizedString(
                key = StringKey("tasks.states.waiting", "tasks_states_waiting", emptyList(), 1),
            )
            val `done`: LocalizedString get() = LocalizedString(
                key = StringKey("tasks.states.done", "tasks_states_done", emptyList(), 1),
            )
        }
        val `state`: LocalizedString get() = LocalizedString(
            key = StringKey("tasks.state", "tasks_state", emptyList(), 1),
        )
        val `saveBeforeState`: LocalizedString get() = LocalizedString(
            key = StringKey("tasks.saveBeforeState", "tasks_save_before_state", emptyList(), 1),
        )
        val `priority`: LocalizedString get() = LocalizedString(
            key = StringKey("tasks.priority", "tasks_priority", emptyList(), 1),
        )
        object `Priorities` {
            val item1: LocalizedString get() = LocalizedString(
                key = StringKey("tasks.priorities.1", "tasks_priorities_1", emptyList(), 1),
            )
            val item2: LocalizedString get() = LocalizedString(
                key = StringKey("tasks.priorities.2", "tasks_priorities_2", emptyList(), 1),
            )
            val item3: LocalizedString get() = LocalizedString(
                key = StringKey("tasks.priorities.3", "tasks_priorities_3", emptyList(), 1),
            )
        }
        val `important`: LocalizedString get() = LocalizedString(
            key = StringKey("tasks.important", "tasks_important", emptyList(), 1),
        )
        fun `assignedTo`(`name`: String): LocalizedString = LocalizedString(
            key = StringKey("tasks.assignedTo", "tasks_assigned_to", listOf("name"), 1),
            arguments = listOf(`name`),
        )
        val `waitingReason`: LocalizedString get() = LocalizedString(
            key = StringKey("tasks.waitingReason", "tasks_waiting_reason", emptyList(), 1),
        )
        val `sort`: LocalizedString get() = LocalizedString(
            key = StringKey("tasks.sort", "tasks_sort", emptyList(), 1),
        )
        object `Sorts` {
            val `priority`: LocalizedString get() = LocalizedString(
                key = StringKey("tasks.sorts.priority", "tasks_sorts_priority", emptyList(), 1),
            )
            val `due`: LocalizedString get() = LocalizedString(
                key = StringKey("tasks.sorts.due", "tasks_sorts_due", emptyList(), 1),
            )
            val `created`: LocalizedString get() = LocalizedString(
                key = StringKey("tasks.sorts.created", "tasks_sorts_created", emptyList(), 1),
            )
            val `manual`: LocalizedString get() = LocalizedString(
                key = StringKey("tasks.sorts.manual", "tasks_sorts_manual", emptyList(), 1),
            )
        }
        val `listView`: LocalizedString get() = LocalizedString(
            key = StringKey("tasks.listView", "tasks_list_view", emptyList(), 1),
        )
        val `boardView`: LocalizedString get() = LocalizedString(
            key = StringKey("tasks.boardView", "tasks_board_view", emptyList(), 1),
        )
        object `Board` {
            val `label`: LocalizedString get() = LocalizedString(
                key = StringKey("tasks.board.label", "tasks_board_label", emptyList(), 1),
            )
            val `empty`: LocalizedString get() = LocalizedString(
                key = StringKey("tasks.board.empty", "tasks_board_empty", emptyList(), 1),
            )
            val `noneDone`: LocalizedString get() = LocalizedString(
                key = StringKey("tasks.board.noneDone", "tasks_board_none_done", emptyList(), 1),
            )
            val `doneWeek`: LocalizedString get() = LocalizedString(
                key = StringKey("tasks.board.doneWeek", "tasks_board_done_week", emptyList(), 1),
            )
            val `allDone`: LocalizedString get() = LocalizedString(
                key = StringKey("tasks.board.allDone", "tasks_board_all_done", emptyList(), 1),
            )
            val `columns`: LocalizedString get() = LocalizedString(
                key = StringKey("tasks.board.columns", "tasks_board_columns", emptyList(), 1),
            )
            fun `moveTask`(`title`: String): LocalizedString = LocalizedString(
                key = StringKey("tasks.board.moveTask", "tasks_board_move_task", listOf("title"), 1),
                arguments = listOf(`title`),
            )
            val `moveTo`: LocalizedString get() = LocalizedString(
                key = StringKey("tasks.board.moveTo", "tasks_board_move_to", emptyList(), 1),
            )
            val `open`: LocalizedString get() = LocalizedString(
                key = StringKey("tasks.board.open", "tasks_board_open", emptyList(), 1),
            )
            fun `moved`(`state`: String): LocalizedString = LocalizedString(
                key = StringKey("tasks.board.moved", "tasks_board_moved", listOf("state"), 1),
                arguments = listOf(`state`),
            )
            val `waitingTitle`: LocalizedString get() = LocalizedString(
                key = StringKey("tasks.board.waitingTitle", "tasks_board_waiting_title", emptyList(), 1),
            )
            val `moveWaiting`: LocalizedString get() = LocalizedString(
                key = StringKey("tasks.board.moveWaiting", "tasks_board_move_waiting", emptyList(), 1),
            )
            val `add`: LocalizedString get() = LocalizedString(
                key = StringKey("tasks.board.add", "tasks_board_add", emptyList(), 1),
            )
            val `newTask`: LocalizedString get() = LocalizedString(
                key = StringKey("tasks.board.newTask", "tasks_board_new_task", emptyList(), 1),
            )
            fun `newTaskIn`(`state`: String): LocalizedString = LocalizedString(
                key = StringKey("tasks.board.newTaskIn", "tasks_board_new_task_in", listOf("state"), 1),
                arguments = listOf(`state`),
            )
            fun `addIn`(`state`: String): LocalizedString = LocalizedString(
                key = StringKey("tasks.board.addIn", "tasks_board_add_in", listOf("state"), 1),
                arguments = listOf(`state`),
            )
            val `dueOn`: LocalizedString get() = LocalizedString(
                key = StringKey("tasks.board.dueOn", "tasks_board_due_on", emptyList(), 1),
            )
            val `optionalFields`: LocalizedString get() = LocalizedString(
                key = StringKey("tasks.board.optionalFields", "tasks_board_optional_fields", emptyList(), 1),
            )
            fun `created`(`title`: String): LocalizedString = LocalizedString(
                key = StringKey("tasks.board.created", "tasks_board_created", listOf("title"), 1),
                arguments = listOf(`title`),
            )
            fun `createdMany`(`count`: String): LocalizedString = LocalizedString(
                key = StringKey("tasks.board.createdMany", "tasks_board_created_many", listOf("count"), 1),
                arguments = listOf(`count`),
            )
            fun `pasted`(`count`: String): LocalizedString = LocalizedString(
                key = StringKey("tasks.board.pasted", "tasks_board_pasted", listOf("count"), 1),
                arguments = listOf(`count`),
            )
            fun `addMany`(quantity: Double, `count`: String): LocalizedString = LocalizedString(
                key = StringKey("tasks.board.addMany", "tasks_board_add_many", listOf("count"), 3),
                arguments = listOf(`count`),
                quantity = quantity,
            )
            fun `addingMany`(`done`: String, `count`: String): LocalizedString = LocalizedString(
                key = StringKey("tasks.board.addingMany", "tasks_board_adding_many", listOf("done", "count"), 1),
                arguments = listOf(`done`, `count`),
            )
            val `asOne`: LocalizedString get() = LocalizedString(
                key = StringKey("tasks.board.asOne", "tasks_board_as_one", emptyList(), 1),
            )
            val `openCreated`: LocalizedString get() = LocalizedString(
                key = StringKey("tasks.board.openCreated", "tasks_board_open_created", emptyList(), 1),
            )
            val `toTop`: LocalizedString get() = LocalizedString(
                key = StringKey("tasks.board.toTop", "tasks_board_to_top", emptyList(), 1),
            )
            val `atTop`: LocalizedString get() = LocalizedString(
                key = StringKey("tasks.board.atTop", "tasks_board_at_top", emptyList(), 1),
            )
            val `show`: LocalizedString get() = LocalizedString(
                key = StringKey("tasks.board.show", "tasks_board_show", emptyList(), 1),
            )
            object `Short` {
                val `todo`: LocalizedString get() = LocalizedString(
                    key = StringKey("tasks.board.short.todo", "tasks_board_short_todo", emptyList(), 1),
                )
                val `in_progress`: LocalizedString get() = LocalizedString(
                    key = StringKey("tasks.board.short.in_progress", "tasks_board_short_in_progress", emptyList(), 1),
                )
                val `waiting`: LocalizedString get() = LocalizedString(
                    key = StringKey("tasks.board.short.waiting", "tasks_board_short_waiting", emptyList(), 1),
                )
                val `done`: LocalizedString get() = LocalizedString(
                    key = StringKey("tasks.board.short.done", "tasks_board_short_done", emptyList(), 1),
                )
            }
        }
        object `Queue` {
            val `label`: LocalizedString get() = LocalizedString(
                key = StringKey("tasks.queue.label", "tasks_queue_label", emptyList(), 1),
            )
            val `top`: LocalizedString get() = LocalizedString(
                key = StringKey("tasks.queue.top", "tasks_queue_top", emptyList(), 1),
            )
            val `up`: LocalizedString get() = LocalizedString(
                key = StringKey("tasks.queue.up", "tasks_queue_up", emptyList(), 1),
            )
            val `down`: LocalizedString get() = LocalizedString(
                key = StringKey("tasks.queue.down", "tasks_queue_down", emptyList(), 1),
            )
            val `end`: LocalizedString get() = LocalizedString(
                key = StringKey("tasks.queue.end", "tasks_queue_end", emptyList(), 1),
            )
            val `moved`: LocalizedString get() = LocalizedString(
                key = StringKey("tasks.queue.moved", "tasks_queue_moved", emptyList(), 1),
            )
            val `useManual`: LocalizedString get() = LocalizedString(
                key = StringKey("tasks.queue.useManual", "tasks_queue_use_manual", emptyList(), 1),
            )
            val `filtered`: LocalizedString get() = LocalizedString(
                key = StringKey("tasks.queue.filtered", "tasks_queue_filtered", emptyList(), 1),
            )
            val `partialTail`: LocalizedString get() = LocalizedString(
                key = StringKey("tasks.queue.partialTail", "tasks_queue_partial_tail", emptyList(), 1),
            )
        }
        val `listActions`: LocalizedString get() = LocalizedString(
            key = StringKey("tasks.listActions", "tasks_list_actions", emptyList(), 1),
        )
        val `viewLabel`: LocalizedString get() = LocalizedString(
            key = StringKey("tasks.viewLabel", "tasks_view_label", emptyList(), 1),
        )
        object `QuickViews` {
            val `today`: LocalizedString get() = LocalizedString(
                key = StringKey("tasks.quickViews.today", "tasks_quick_views_today", emptyList(), 1),
            )
            val `upcoming`: LocalizedString get() = LocalizedString(
                key = StringKey("tasks.quickViews.upcoming", "tasks_quick_views_upcoming", emptyList(), 1),
            )
            val `open`: LocalizedString get() = LocalizedString(
                key = StringKey("tasks.quickViews.open", "tasks_quick_views_open", emptyList(), 1),
            )
        }
        val `moreViews`: LocalizedString get() = LocalizedString(
            key = StringKey("tasks.moreViews", "tasks_more_views", emptyList(), 1),
        )
        val `moveUp`: LocalizedString get() = LocalizedString(
            key = StringKey("tasks.moveUp", "tasks_move_up", emptyList(), 1),
        )
        val `subtask`: LocalizedString get() = LocalizedString(
            key = StringKey("tasks.subtask", "tasks_subtask", emptyList(), 1),
        )
        fun `parent`(`title`: String): LocalizedString = LocalizedString(
            key = StringKey("tasks.parent", "tasks_parent", listOf("title"), 1),
            arguments = listOf(`title`),
        )
        val `parentDeleted`: LocalizedString get() = LocalizedString(
            key = StringKey("tasks.parentDeleted", "tasks_parent_deleted", emptyList(), 1),
        )
        val `parentCompleted`: LocalizedString get() = LocalizedString(
            key = StringKey("tasks.parentCompleted", "tasks_parent_completed", emptyList(), 1),
        )
        val `detach`: LocalizedString get() = LocalizedString(
            key = StringKey("tasks.detach", "tasks_detach", emptyList(), 1),
        )
        val `reopenBeforeSubtask`: LocalizedString get() = LocalizedString(
            key = StringKey("tasks.reopenBeforeSubtask", "tasks_reopen_before_subtask", emptyList(), 1),
        )
        object `Completion` {
            val `title`: LocalizedString get() = LocalizedString(
                key = StringKey("tasks.completion.title", "tasks_completion_title", emptyList(), 1),
            )
            fun `open_subtasks`(`count`: String): LocalizedString = LocalizedString(
                key = StringKey("tasks.completion.open_subtasks", "tasks_completion_open_subtasks", listOf("count"), 1),
                arguments = listOf(`count`),
            )
            fun `incomplete_checklist`(`count`: String): LocalizedString = LocalizedString(
                key = StringKey("tasks.completion.incomplete_checklist", "tasks_completion_incomplete_checklist", listOf("count"), 1),
                arguments = listOf(`count`),
            )
            val `parent_closed`: LocalizedString get() = LocalizedString(
                key = StringKey("tasks.completion.parent_closed", "tasks_completion_parent_closed", emptyList(), 1),
            )
            val `changed`: LocalizedString get() = LocalizedString(
                key = StringKey("tasks.completion.changed", "tasks_completion_changed", emptyList(), 1),
            )
            val `return`: LocalizedString get() = LocalizedString(
                key = StringKey("tasks.completion.return", "tasks_completion_return", emptyList(), 1),
            )
            val `confirm`: LocalizedString get() = LocalizedString(
                key = StringKey("tasks.completion.confirm", "tasks_completion_confirm", emptyList(), 1),
            )
            val `inspect`: LocalizedString get() = LocalizedString(
                key = StringKey("tasks.completion.inspect", "tasks_completion_inspect", emptyList(), 1),
            )
        }
        val `subtasks`: LocalizedString get() = LocalizedString(
            key = StringKey("tasks.subtasks", "tasks_subtasks", emptyList(), 1),
        )
        val `newSubtask`: LocalizedString get() = LocalizedString(
            key = StringKey("tasks.newSubtask", "tasks_new_subtask", emptyList(), 1),
        )
        val `checklist`: LocalizedString get() = LocalizedString(
            key = StringKey("tasks.checklist", "tasks_checklist", emptyList(), 1),
        )
        val `newStep`: LocalizedString get() = LocalizedString(
            key = StringKey("tasks.newStep", "tasks_new_step", emptyList(), 1),
        )
        val `removeStep`: LocalizedString get() = LocalizedString(
            key = StringKey("tasks.removeStep", "tasks_remove_step", emptyList(), 1),
        )
        object `Tabs` {
            val `details`: LocalizedString get() = LocalizedString(
                key = StringKey("tasks.tabs.details", "tasks_tabs_details", emptyList(), 1),
            )
            val `assistant`: LocalizedString get() = LocalizedString(
                key = StringKey("tasks.tabs.assistant", "tasks_tabs_assistant", emptyList(), 1),
            )
            val `discussion`: LocalizedString get() = LocalizedString(
                key = StringKey("tasks.tabs.discussion", "tasks_tabs_discussion", emptyList(), 1),
            )
            val `history`: LocalizedString get() = LocalizedString(
                key = StringKey("tasks.tabs.history", "tasks_tabs_history", emptyList(), 1),
            )
        }
        object `History` {
            val `scope`: LocalizedString get() = LocalizedString(
                key = StringKey("tasks.history.scope", "tasks_history_scope", emptyList(), 1),
            )
            val `empty`: LocalizedString get() = LocalizedString(
                key = StringKey("tasks.history.empty", "tasks_history_empty", emptyList(), 1),
            )
            val `loadMore`: LocalizedString get() = LocalizedString(
                key = StringKey("tasks.history.loadMore", "tasks_history_load_more", emptyList(), 1),
            )
            val `loadMoreFailed`: LocalizedString get() = LocalizedString(
                key = StringKey("tasks.history.loadMoreFailed", "tasks_history_load_more_failed", emptyList(), 1),
            )
            val `notSet`: LocalizedString get() = LocalizedString(
                key = StringKey("tasks.history.notSet", "tasks_history_not_set", emptyList(), 1),
            )
            val `unassigned`: LocalizedString get() = LocalizedString(
                key = StringKey("tasks.history.unassigned", "tasks_history_unassigned", emptyList(), 1),
            )
            val `unknownPerson`: LocalizedString get() = LocalizedString(
                key = StringKey("tasks.history.unknownPerson", "tasks_history_unknown_person", emptyList(), 1),
            )
            val `changedTo`: LocalizedString get() = LocalizedString(
                key = StringKey("tasks.history.changedTo", "tasks_history_changed_to", emptyList(), 1),
            )
            val `memberLeft`: LocalizedString get() = LocalizedString(
                key = StringKey("tasks.history.memberLeft", "tasks_history_member_left", emptyList(), 1),
            )
            val `accountDeleted`: LocalizedString get() = LocalizedString(
                key = StringKey("tasks.history.accountDeleted", "tasks_history_account_deleted", emptyList(), 1),
            )
            object `Kinds` {
                val `created`: LocalizedString get() = LocalizedString(
                    key = StringKey("tasks.history.kinds.created", "tasks_history_kinds_created", emptyList(), 1),
                )
                val `updated`: LocalizedString get() = LocalizedString(
                    key = StringKey("tasks.history.kinds.updated", "tasks_history_kinds_updated", emptyList(), 1),
                )
                val `state_changed`: LocalizedString get() = LocalizedString(
                    key = StringKey("tasks.history.kinds.state_changed", "tasks_history_kinds_state_changed", emptyList(), 1),
                )
                val `assigned`: LocalizedString get() = LocalizedString(
                    key = StringKey("tasks.history.kinds.assigned", "tasks_history_kinds_assigned", emptyList(), 1),
                )
                val `unassigned`: LocalizedString get() = LocalizedString(
                    key = StringKey("tasks.history.kinds.unassigned", "tasks_history_kinds_unassigned", emptyList(), 1),
                )
                val `deleted`: LocalizedString get() = LocalizedString(
                    key = StringKey("tasks.history.kinds.deleted", "tasks_history_kinds_deleted", emptyList(), 1),
                )
                val `restored`: LocalizedString get() = LocalizedString(
                    key = StringKey("tasks.history.kinds.restored", "tasks_history_kinds_restored", emptyList(), 1),
                )
                val `moved`: LocalizedString get() = LocalizedString(
                    key = StringKey("tasks.history.kinds.moved", "tasks_history_kinds_moved", emptyList(), 1),
                )
                val `reordered`: LocalizedString get() = LocalizedString(
                    key = StringKey("tasks.history.kinds.reordered", "tasks_history_kinds_reordered", emptyList(), 1),
                )
                val `parent_changed`: LocalizedString get() = LocalizedString(
                    key = StringKey("tasks.history.kinds.parent_changed", "tasks_history_kinds_parent_changed", emptyList(), 1),
                )
                val `result_accepted`: LocalizedString get() = LocalizedString(
                    key = StringKey("tasks.history.kinds.result_accepted", "tasks_history_kinds_result_accepted", emptyList(), 1),
                )
            }
            object `Fields` {
                val `title`: LocalizedString get() = LocalizedString(
                    key = StringKey("tasks.history.fields.title", "tasks_history_fields_title", emptyList(), 1),
                )
                val `description`: LocalizedString get() = LocalizedString(
                    key = StringKey("tasks.history.fields.description", "tasks_history_fields_description", emptyList(), 1),
                )
                val `checklist`: LocalizedString get() = LocalizedString(
                    key = StringKey("tasks.history.fields.checklist", "tasks_history_fields_checklist", emptyList(), 1),
                )
                val `waiting_reason`: LocalizedString get() = LocalizedString(
                    key = StringKey("tasks.history.fields.waiting_reason", "tasks_history_fields_waiting_reason", emptyList(), 1),
                )
                val `title_images`: LocalizedString get() = LocalizedString(
                    key = StringKey("tasks.history.fields.title_images", "tasks_history_fields_title_images", emptyList(), 1),
                )
                val `description_images`: LocalizedString get() = LocalizedString(
                    key = StringKey("tasks.history.fields.description_images", "tasks_history_fields_description_images", emptyList(), 1),
                )
                val `list_id`: LocalizedString get() = LocalizedString(
                    key = StringKey("tasks.history.fields.list_id", "tasks_history_fields_list_id", emptyList(), 1),
                )
                val `parent_id`: LocalizedString get() = LocalizedString(
                    key = StringKey("tasks.history.fields.parent_id", "tasks_history_fields_parent_id", emptyList(), 1),
                )
                val `position`: LocalizedString get() = LocalizedString(
                    key = StringKey("tasks.history.fields.position", "tasks_history_fields_position", emptyList(), 1),
                )
            }
        }
        val `discussionAudience`: LocalizedString get() = LocalizedString(
            key = StringKey("tasks.discussionAudience", "tasks_discussion_audience", emptyList(), 1),
        )
        object `Agent` {
            val `disabled`: LocalizedString get() = LocalizedString(
                key = StringKey("tasks.agent.disabled", "tasks_agent_disabled", emptyList(), 1),
            )
            val `delegate`: LocalizedString get() = LocalizedString(
                key = StringKey("tasks.agent.delegate", "tasks_agent_delegate", emptyList(), 1),
            )
            val `edit`: LocalizedString get() = LocalizedString(
                key = StringKey("tasks.agent.edit", "tasks_agent_edit", emptyList(), 1),
            )
            val `instruction`: LocalizedString get() = LocalizedString(
                key = StringKey("tasks.agent.instruction", "tasks_agent_instruction", emptyList(), 1),
            )
            val `instructionPlaceholder`: LocalizedString get() = LocalizedString(
                key = StringKey("tasks.agent.instructionPlaceholder", "tasks_agent_instruction_placeholder", emptyList(), 1),
            )
            val `mode`: LocalizedString get() = LocalizedString(
                key = StringKey("tasks.agent.mode", "tasks_agent_mode", emptyList(), 1),
            )
            object `Modes` {
                val `oneoff`: LocalizedString get() = LocalizedString(
                    key = StringKey("tasks.agent.modes.oneoff", "tasks_agent_modes_oneoff", emptyList(), 1),
                )
                val `monitor`: LocalizedString get() = LocalizedString(
                    key = StringKey("tasks.agent.modes.monitor", "tasks_agent_modes_monitor", emptyList(), 1),
                )
            }
            val `explanation`: LocalizedString get() = LocalizedString(
                key = StringKey("tasks.agent.explanation", "tasks_agent_explanation", emptyList(), 1),
            )
            val `interval`: LocalizedString get() = LocalizedString(
                key = StringKey("tasks.agent.interval", "tasks_agent_interval", emptyList(), 1),
            )
            val `monitorHint`: LocalizedString get() = LocalizedString(
                key = StringKey("tasks.agent.monitorHint", "tasks_agent_monitor_hint", emptyList(), 1),
            )
            fun `monitorInfo`(`minutes`: String): LocalizedString = LocalizedString(
                key = StringKey("tasks.agent.monitorInfo", "tasks_agent_monitor_info", listOf("minutes"), 1),
                arguments = listOf(`minutes`),
            )
            fun `nextRun`(`time`: String): LocalizedString = LocalizedString(
                key = StringKey("tasks.agent.nextRun", "tasks_agent_next_run", listOf("time"), 1),
                arguments = listOf(`time`),
            )
            val `completion`: LocalizedString get() = LocalizedString(
                key = StringKey("tasks.agent.completion", "tasks_agent_completion", emptyList(), 1),
            )
            object `Completions` {
                val `manual`: LocalizedString get() = LocalizedString(
                    key = StringKey("tasks.agent.completions.manual", "tasks_agent_completions_manual", emptyList(), 1),
                )
                val `auto`: LocalizedString get() = LocalizedString(
                    key = StringKey("tasks.agent.completions.auto", "tasks_agent_completions_auto", emptyList(), 1),
                )
            }
            val `requiredChecks`: LocalizedString get() = LocalizedString(
                key = StringKey("tasks.agent.requiredChecks", "tasks_agent_required_checks", emptyList(), 1),
            )
            val `autoHint`: LocalizedString get() = LocalizedString(
                key = StringKey("tasks.agent.autoHint", "tasks_agent_auto_hint", emptyList(), 1),
            )
            val `permissions`: LocalizedString get() = LocalizedString(
                key = StringKey("tasks.agent.permissions", "tasks_agent_permissions", emptyList(), 1),
            )
            val `permissionsHint`: LocalizedString get() = LocalizedString(
                key = StringKey("tasks.agent.permissionsHint", "tasks_agent_permissions_hint", emptyList(), 1),
            )
            object `GrantKinds` {
                val `tools`: LocalizedString get() = LocalizedString(
                    key = StringKey("tasks.agent.grantKinds.tools", "tasks_agent_grant_kinds_tools", emptyList(), 1),
                )
                val `actions`: LocalizedString get() = LocalizedString(
                    key = StringKey("tasks.agent.grantKinds.actions", "tasks_agent_grant_kinds_actions", emptyList(), 1),
                )
                val `executors`: LocalizedString get() = LocalizedString(
                    key = StringKey("tasks.agent.grantKinds.executors", "tasks_agent_grant_kinds_executors", emptyList(), 1),
                )
                val `resources`: LocalizedString get() = LocalizedString(
                    key = StringKey("tasks.agent.grantKinds.resources", "tasks_agent_grant_kinds_resources", emptyList(), 1),
                )
            }
            val `noChoices`: LocalizedString get() = LocalizedString(
                key = StringKey("tasks.agent.noChoices", "tasks_agent_no_choices", emptyList(), 1),
            )
            val `start`: LocalizedString get() = LocalizedString(
                key = StringKey("tasks.agent.start", "tasks_agent_start", emptyList(), 1),
            )
            val `pause`: LocalizedString get() = LocalizedString(
                key = StringKey("tasks.agent.pause", "tasks_agent_pause", emptyList(), 1),
            )
            val `resume`: LocalizedString get() = LocalizedString(
                key = StringKey("tasks.agent.resume", "tasks_agent_resume", emptyList(), 1),
            )
            val `revoke`: LocalizedString get() = LocalizedString(
                key = StringKey("tasks.agent.revoke", "tasks_agent_revoke", emptyList(), 1),
            )
            val `question`: LocalizedString get() = LocalizedString(
                key = StringKey("tasks.agent.question", "tasks_agent_question", emptyList(), 1),
            )
            val `answer`: LocalizedString get() = LocalizedString(
                key = StringKey("tasks.agent.answer", "tasks_agent_answer", emptyList(), 1),
            )
            val `approve`: LocalizedString get() = LocalizedString(
                key = StringKey("tasks.agent.approve", "tasks_agent_approve", emptyList(), 1),
            )
            val `decline`: LocalizedString get() = LocalizedString(
                key = StringKey("tasks.agent.decline", "tasks_agent_decline", emptyList(), 1),
            )
            val `results`: LocalizedString get() = LocalizedString(
                key = StringKey("tasks.agent.results", "tasks_agent_results", emptyList(), 1),
            )
            val `accept`: LocalizedString get() = LocalizedString(
                key = StringKey("tasks.agent.accept", "tasks_agent_accept", emptyList(), 1),
            )
            val `acceptComplete`: LocalizedString get() = LocalizedString(
                key = StringKey("tasks.agent.acceptComplete", "tasks_agent_accept_complete", emptyList(), 1),
            )
            val `revise`: LocalizedString get() = LocalizedString(
                key = StringKey("tasks.agent.revise", "tasks_agent_revise", emptyList(), 1),
            )
            val `revisionInstruction`: LocalizedString get() = LocalizedString(
                key = StringKey("tasks.agent.revisionInstruction", "tasks_agent_revision_instruction", emptyList(), 1),
            )
            val `continue`: LocalizedString get() = LocalizedString(
                key = StringKey("tasks.agent.continue", "tasks_agent_continue", emptyList(), 1),
            )
            val `history`: LocalizedString get() = LocalizedString(
                key = StringKey("tasks.agent.history", "tasks_agent_history", emptyList(), 1),
            )
            val `runError`: LocalizedString get() = LocalizedString(
                key = StringKey("tasks.agent.runError", "tasks_agent_run_error", emptyList(), 1),
            )
            object `Statuses` {
                val `active`: LocalizedString get() = LocalizedString(
                    key = StringKey("tasks.agent.statuses.active", "tasks_agent_statuses_active", emptyList(), 1),
                )
                val `running`: LocalizedString get() = LocalizedString(
                    key = StringKey("tasks.agent.statuses.running", "tasks_agent_statuses_running", emptyList(), 1),
                )
                val `queued`: LocalizedString get() = LocalizedString(
                    key = StringKey("tasks.agent.statuses.queued", "tasks_agent_statuses_queued", emptyList(), 1),
                )
                val `pending`: LocalizedString get() = LocalizedString(
                    key = StringKey("tasks.agent.statuses.pending", "tasks_agent_statuses_pending", emptyList(), 1),
                )
                val `waiting`: LocalizedString get() = LocalizedString(
                    key = StringKey("tasks.agent.statuses.waiting", "tasks_agent_statuses_waiting", emptyList(), 1),
                )
                val `waiting_user`: LocalizedString get() = LocalizedString(
                    key = StringKey("tasks.agent.statuses.waiting_user", "tasks_agent_statuses_waiting_user", emptyList(), 1),
                )
                val `waiting_worker`: LocalizedString get() = LocalizedString(
                    key = StringKey("tasks.agent.statuses.waiting_worker", "tasks_agent_statuses_waiting_worker", emptyList(), 1),
                )
                val `paused`: LocalizedString get() = LocalizedString(
                    key = StringKey("tasks.agent.statuses.paused", "tasks_agent_statuses_paused", emptyList(), 1),
                )
                val `revoked`: LocalizedString get() = LocalizedString(
                    key = StringKey("tasks.agent.statuses.revoked", "tasks_agent_statuses_revoked", emptyList(), 1),
                )
                val `completed`: LocalizedString get() = LocalizedString(
                    key = StringKey("tasks.agent.statuses.completed", "tasks_agent_statuses_completed", emptyList(), 1),
                )
                val `done`: LocalizedString get() = LocalizedString(
                    key = StringKey("tasks.agent.statuses.done", "tasks_agent_statuses_done", emptyList(), 1),
                )
                val `failed`: LocalizedString get() = LocalizedString(
                    key = StringKey("tasks.agent.statuses.failed", "tasks_agent_statuses_failed", emptyList(), 1),
                )
                val `cancelled`: LocalizedString get() = LocalizedString(
                    key = StringKey("tasks.agent.statuses.cancelled", "tasks_agent_statuses_cancelled", emptyList(), 1),
                )
                val `ready`: LocalizedString get() = LocalizedString(
                    key = StringKey("tasks.agent.statuses.ready", "tasks_agent_statuses_ready", emptyList(), 1),
                )
                val `proposed`: LocalizedString get() = LocalizedString(
                    key = StringKey("tasks.agent.statuses.proposed", "tasks_agent_statuses_proposed", emptyList(), 1),
                )
                val `review`: LocalizedString get() = LocalizedString(
                    key = StringKey("tasks.agent.statuses.review", "tasks_agent_statuses_review", emptyList(), 1),
                )
                val `pending_review`: LocalizedString get() = LocalizedString(
                    key = StringKey("tasks.agent.statuses.pending_review", "tasks_agent_statuses_pending_review", emptyList(), 1),
                )
                val `accepted`: LocalizedString get() = LocalizedString(
                    key = StringKey("tasks.agent.statuses.accepted", "tasks_agent_statuses_accepted", emptyList(), 1),
                )
                val `rejected`: LocalizedString get() = LocalizedString(
                    key = StringKey("tasks.agent.statuses.rejected", "tasks_agent_statuses_rejected", emptyList(), 1),
                )
                val `revising`: LocalizedString get() = LocalizedString(
                    key = StringKey("tasks.agent.statuses.revising", "tasks_agent_statuses_revising", emptyList(), 1),
                )
                val `succeeded`: LocalizedString get() = LocalizedString(
                    key = StringKey("tasks.agent.statuses.succeeded", "tasks_agent_statuses_succeeded", emptyList(), 1),
                )
                val `unchanged`: LocalizedString get() = LocalizedString(
                    key = StringKey("tasks.agent.statuses.unchanged", "tasks_agent_statuses_unchanged", emptyList(), 1),
                )
                val `budget_exhausted`: LocalizedString get() = LocalizedString(
                    key = StringKey("tasks.agent.statuses.budget_exhausted", "tasks_agent_statuses_budget_exhausted", emptyList(), 1),
                )
                val `waiting_input`: LocalizedString get() = LocalizedString(
                    key = StringKey("tasks.agent.statuses.waiting_input", "tasks_agent_statuses_waiting_input", emptyList(), 1),
                )
                val `waiting_review`: LocalizedString get() = LocalizedString(
                    key = StringKey("tasks.agent.statuses.waiting_review", "tasks_agent_statuses_waiting_review", emptyList(), 1),
                )
                val `revision_requested`: LocalizedString get() = LocalizedString(
                    key = StringKey("tasks.agent.statuses.revision_requested", "tasks_agent_statuses_revision_requested", emptyList(), 1),
                )
                val `blocked`: LocalizedString get() = LocalizedString(
                    key = StringKey("tasks.agent.statuses.blocked", "tasks_agent_statuses_blocked", emptyList(), 1),
                )
            }
            val `private`: LocalizedString get() = LocalizedString(
                key = StringKey("tasks.agent.private", "tasks_agent_private", emptyList(), 1),
            )
            fun `budget`(`minutes`: String, `calls`: String, `runs`: String): LocalizedString = LocalizedString(
                key = StringKey("tasks.agent.budget", "tasks_agent_budget", listOf("minutes", "calls", "runs"), 1),
                arguments = listOf(`minutes`, `calls`, `runs`),
            )
            val `workerPermissionHint`: LocalizedString get() = LocalizedString(
                key = StringKey("tasks.agent.workerPermissionHint", "tasks_agent_worker_permission_hint", emptyList(), 1),
            )
            object `Checks` {
                val `has_result`: LocalizedString get() = LocalizedString(
                    key = StringKey("tasks.agent.checks.has_result", "tasks_agent_checks_has_result", emptyList(), 1),
                )
                val `all_subtasks_done`: LocalizedString get() = LocalizedString(
                    key = StringKey("tasks.agent.checks.all_subtasks_done", "tasks_agent_checks_all_subtasks_done", emptyList(), 1),
                )
                val `all_checklist_done`: LocalizedString get() = LocalizedString(
                    key = StringKey("tasks.agent.checks.all_checklist_done", "tasks_agent_checks_all_checklist_done", emptyList(), 1),
                )
                val `no_pending_questions`: LocalizedString get() = LocalizedString(
                    key = StringKey("tasks.agent.checks.no_pending_questions", "tasks_agent_checks_no_pending_questions", emptyList(), 1),
                )
            }
        }
        val `moveToProject`: LocalizedString get() = LocalizedString(
            key = StringKey("tasks.moveToProject", "tasks_move_to_project", emptyList(), 1),
        )
        val `chooseProject`: LocalizedString get() = LocalizedString(
            key = StringKey("tasks.chooseProject", "tasks_choose_project", emptyList(), 1),
        )
        val `moveHistoryWarning`: LocalizedString get() = LocalizedString(
            key = StringKey("tasks.moveHistoryWarning", "tasks_move_history_warning", emptyList(), 1),
        )
        val `discussionChecked`: LocalizedString get() = LocalizedString(
            key = StringKey("tasks.discussionChecked", "tasks_discussion_checked", emptyList(), 1),
        )
        object `DiscussionBadge` {
            fun `messages`(`total`: String): LocalizedString = LocalizedString(
                key = StringKey("tasks.discussionBadge.messages", "tasks_discussion_badge_messages", listOf("total"), 1),
                arguments = listOf(`total`),
            )
            fun `unread`(`total`: String, `unread`: String): LocalizedString = LocalizedString(
                key = StringKey("tasks.discussionBadge.unread", "tasks_discussion_badge_unread", listOf("total", "unread"), 1),
                arguments = listOf(`total`, `unread`),
            )
        }
        val `earlierMessages`: LocalizedString get() = LocalizedString(
            key = StringKey("tasks.earlierMessages", "tasks_earlier_messages", emptyList(), 1),
        )
    }
    object `Personal` {
        object `Caliper` {
            val `title`: LocalizedString get() = LocalizedString(
                key = StringKey("personal.caliper.title", "personal_caliper_title", emptyList(), 1),
            )
            val `intro`: LocalizedString get() = LocalizedString(
                key = StringKey("personal.caliper.intro", "personal_caliper_intro", emptyList(), 1),
            )
            val `add`: LocalizedString get() = LocalizedString(
                key = StringKey("personal.caliper.add", "personal_caliper_add", emptyList(), 1),
            )
            val `historyAdd`: LocalizedString get() = LocalizedString(
                key = StringKey("personal.caliper.historyAdd", "personal_caliper_history_add", emptyList(), 1),
            )
            val `journal`: LocalizedString get() = LocalizedString(
                key = StringKey("personal.caliper.journal", "personal_caliper_journal", emptyList(), 1),
            )
            val `save`: LocalizedString get() = LocalizedString(
                key = StringKey("personal.caliper.save", "personal_caliper_save", emptyList(), 1),
            )
            val `saved`: LocalizedString get() = LocalizedString(
                key = StringKey("personal.caliper.saved", "personal_caliper_saved", emptyList(), 1),
            )
            fun `result`(`value`: String): LocalizedString = LocalizedString(
                key = StringKey("personal.caliper.result", "personal_caliper_result", listOf("value"), 1),
                arguments = listOf(`value`),
            )
            val `empty`: LocalizedString get() = LocalizedString(
                key = StringKey("personal.caliper.empty", "personal_caliper_empty", emptyList(), 1),
            )
            val `history`: LocalizedString get() = LocalizedString(
                key = StringKey("personal.caliper.history", "personal_caliper_history", emptyList(), 1),
            )
            fun `more`(`count`: String): LocalizedString = LocalizedString(
                key = StringKey("personal.caliper.more", "personal_caliper_more", listOf("count"), 1),
                arguments = listOf(`count`),
            )
            val `less`: LocalizedString get() = LocalizedString(
                key = StringKey("personal.caliper.less", "personal_caliper_less", emptyList(), 1),
            )
            fun `truncated`(`count`: String): LocalizedString = LocalizedString(
                key = StringKey("personal.caliper.truncated", "personal_caliper_truncated", listOf("count"), 1),
                arguments = listOf(`count`),
            )
            val `mm`: LocalizedString get() = LocalizedString(
                key = StringKey("personal.caliper.mm", "personal_caliper_mm", emptyList(), 1),
            )
            val `gender`: LocalizedString get() = LocalizedString(
                key = StringKey("personal.caliper.gender", "personal_caliper_gender", emptyList(), 1),
            )
            val `age`: LocalizedString get() = LocalizedString(
                key = StringKey("personal.caliper.age", "personal_caliper_age", emptyList(), 1),
            )
            val `note`: LocalizedString get() = LocalizedString(
                key = StringKey("personal.caliper.note", "personal_caliper_note", emptyList(), 1),
            )
            val `formHint`: LocalizedString get() = LocalizedString(
                key = StringKey("personal.caliper.formHint", "personal_caliper_form_hint", emptyList(), 1),
            )
            val `primaryFolds`: LocalizedString get() = LocalizedString(
                key = StringKey("personal.caliper.primaryFolds", "personal_caliper_primary_folds", emptyList(), 1),
            )
            val `optionalFolds`: LocalizedString get() = LocalizedString(
                key = StringKey("personal.caliper.optionalFolds", "personal_caliper_optional_folds", emptyList(), 1),
            )
            fun `optionalFilled`(`count`: String): LocalizedString = LocalizedString(
                key = StringKey("personal.caliper.optionalFilled", "personal_caliper_optional_filled", listOf("count"), 1),
                arguments = listOf(`count`),
            )
            val `optionalHint`: LocalizedString get() = LocalizedString(
                key = StringKey("personal.caliper.optionalHint", "personal_caliper_optional_hint", emptyList(), 1),
            )
            val `metadataHint`: LocalizedString get() = LocalizedString(
                key = StringKey("personal.caliper.metadataHint", "personal_caliper_metadata_hint", emptyList(), 1),
            )
            val `ageFromProfile`: LocalizedString get() = LocalizedString(
                key = StringKey("personal.caliper.ageFromProfile", "personal_caliper_age_from_profile", emptyList(), 1),
            )
            val `ageMissing`: LocalizedString get() = LocalizedString(
                key = StringKey("personal.caliper.ageMissing", "personal_caliper_age_missing", emptyList(), 1),
            )
            fun `protocol`(`sites`: String): LocalizedString = LocalizedString(
                key = StringKey("personal.caliper.protocol", "personal_caliper_protocol", listOf("sites"), 1),
                arguments = listOf(`sites`),
            )
            val `protocolUnset`: LocalizedString get() = LocalizedString(
                key = StringKey("personal.caliper.protocolUnset", "personal_caliper_protocol_unset", emptyList(), 1),
            )
            val `foldRequired`: LocalizedString get() = LocalizedString(
                key = StringKey("personal.caliper.foldRequired", "personal_caliper_fold_required", emptyList(), 1),
            )
            fun `foldRange`(`site`: String): LocalizedString = LocalizedString(
                key = StringKey("personal.caliper.foldRange", "personal_caliper_fold_range", listOf("site"), 1),
                arguments = listOf(`site`),
            )
            val `ageRange`: LocalizedString get() = LocalizedString(
                key = StringKey("personal.caliper.ageRange", "personal_caliper_age_range", emptyList(), 1),
            )
            fun `snapshot`(`gender`: String, `age`: String): LocalizedString = LocalizedString(
                key = StringKey("personal.caliper.snapshot", "personal_caliper_snapshot", listOf("gender", "age"), 1),
                arguments = listOf(`gender`, `age`),
            )
            fun `incomplete`(`fields`: String): LocalizedString = LocalizedString(
                key = StringKey("personal.caliper.incomplete", "personal_caliper_incomplete", listOf("fields"), 1),
                arguments = listOf(`fields`),
            )
            val `ageUnsupported`: LocalizedString get() = LocalizedString(
                key = StringKey("personal.caliper.ageUnsupported", "personal_caliper_age_unsupported", emptyList(), 1),
            )
            val `unsupported`: LocalizedString get() = LocalizedString(
                key = StringKey("personal.caliper.unsupported", "personal_caliper_unsupported", emptyList(), 1),
            )
            val `delete`: LocalizedString get() = LocalizedString(
                key = StringKey("personal.caliper.delete", "personal_caliper_delete", emptyList(), 1),
            )
            fun `deleteAt`(`when`: String): LocalizedString = LocalizedString(
                key = StringKey("personal.caliper.deleteAt", "personal_caliper_delete_at", listOf("when"), 1),
                arguments = listOf(`when`),
            )
            fun `deleteConfirm`(`when`: String): LocalizedString = LocalizedString(
                key = StringKey("personal.caliper.deleteConfirm", "personal_caliper_delete_confirm", listOf("when"), 1),
                arguments = listOf(`when`),
            )
            val `deleted`: LocalizedString get() = LocalizedString(
                key = StringKey("personal.caliper.deleted", "personal_caliper_deleted", emptyList(), 1),
            )
            val `about`: LocalizedString get() = LocalizedString(
                key = StringKey("personal.caliper.about", "personal_caliper_about", emptyList(), 1),
            )
            val `maleGuideTitle`: LocalizedString get() = LocalizedString(
                key = StringKey("personal.caliper.maleGuideTitle", "personal_caliper_male_guide_title", emptyList(), 1),
            )
            val `maleGuideHint`: LocalizedString get() = LocalizedString(
                key = StringKey("personal.caliper.maleGuideHint", "personal_caliper_male_guide_hint", emptyList(), 1),
            )
            val `maleGuideMarkers`: LocalizedString get() = LocalizedString(
                key = StringKey("personal.caliper.maleGuideMarkers", "personal_caliper_male_guide_markers", emptyList(), 1),
            )
            val `maleGuideTiming`: LocalizedString get() = LocalizedString(
                key = StringKey("personal.caliper.maleGuideTiming", "personal_caliper_male_guide_timing", emptyList(), 1),
            )
            val `maleGuideAlt`: LocalizedString get() = LocalizedString(
                key = StringKey("personal.caliper.maleGuideAlt", "personal_caliper_male_guide_alt", emptyList(), 1),
            )
            val `fullSizeGuide`: LocalizedString get() = LocalizedString(
                key = StringKey("personal.caliper.fullSizeGuide", "personal_caliper_full_size_guide", emptyList(), 1),
            )
            val `method`: LocalizedString get() = LocalizedString(
                key = StringKey("personal.caliper.method", "personal_caliper_method", emptyList(), 1),
            )
            val `technique`: LocalizedString get() = LocalizedString(
                key = StringKey("personal.caliper.technique", "personal_caliper_technique", emptyList(), 1),
            )
            val `limits`: LocalizedString get() = LocalizedString(
                key = StringKey("personal.caliper.limits", "personal_caliper_limits", emptyList(), 1),
            )
            val `calculator`: LocalizedString get() = LocalizedString(
                key = StringKey("personal.caliper.calculator", "personal_caliper_calculator", emptyList(), 1),
            )
            val `researchMale`: LocalizedString get() = LocalizedString(
                key = StringKey("personal.caliper.researchMale", "personal_caliper_research_male", emptyList(), 1),
            )
            val `researchFemale`: LocalizedString get() = LocalizedString(
                key = StringKey("personal.caliper.researchFemale", "personal_caliper_research_female", emptyList(), 1),
            )
            val `protocolSource`: LocalizedString get() = LocalizedString(
                key = StringKey("personal.caliper.protocolSource", "personal_caliper_protocol_source", emptyList(), 1),
            )
            val `source`: LocalizedString get() = LocalizedString(
                key = StringKey("personal.caliper.source", "personal_caliper_source", emptyList(), 1),
            )
            object `Sites` {
                val `abdomen`: LocalizedString get() = LocalizedString(
                    key = StringKey("personal.caliper.sites.abdomen", "personal_caliper_sites_abdomen", emptyList(), 1),
                )
                val `suprailiac`: LocalizedString get() = LocalizedString(
                    key = StringKey("personal.caliper.sites.suprailiac", "personal_caliper_sites_suprailiac", emptyList(), 1),
                )
                val `chest`: LocalizedString get() = LocalizedString(
                    key = StringKey("personal.caliper.sites.chest", "personal_caliper_sites_chest", emptyList(), 1),
                )
                val `thigh`: LocalizedString get() = LocalizedString(
                    key = StringKey("personal.caliper.sites.thigh", "personal_caliper_sites_thigh", emptyList(), 1),
                )
                val `triceps`: LocalizedString get() = LocalizedString(
                    key = StringKey("personal.caliper.sites.triceps", "personal_caliper_sites_triceps", emptyList(), 1),
                )
            }
            object `TechniqueSite` {
                val `abdomen`: LocalizedString get() = LocalizedString(
                    key = StringKey("personal.caliper.techniqueSite.abdomen", "personal_caliper_technique_site_abdomen", emptyList(), 1),
                )
                val `suprailiac`: LocalizedString get() = LocalizedString(
                    key = StringKey("personal.caliper.techniqueSite.suprailiac", "personal_caliper_technique_site_suprailiac", emptyList(), 1),
                )
                val `chest`: LocalizedString get() = LocalizedString(
                    key = StringKey("personal.caliper.techniqueSite.chest", "personal_caliper_technique_site_chest", emptyList(), 1),
                )
                val `thigh`: LocalizedString get() = LocalizedString(
                    key = StringKey("personal.caliper.techniqueSite.thigh", "personal_caliper_technique_site_thigh", emptyList(), 1),
                )
                val `triceps`: LocalizedString get() = LocalizedString(
                    key = StringKey("personal.caliper.techniqueSite.triceps", "personal_caliper_technique_site_triceps", emptyList(), 1),
                )
            }
            val `missingTitle`: LocalizedString get() = LocalizedString(
                key = StringKey("personal.caliper.missingTitle", "personal_caliper_missing_title", emptyList(), 1),
            )
            fun `missingNotice`(`fields`: String): LocalizedString = LocalizedString(
                key = StringKey("personal.caliper.missingNotice", "personal_caliper_missing_notice", listOf("fields"), 1),
                arguments = listOf(`fields`),
            )
            val `missingProfileHint`: LocalizedString get() = LocalizedString(
                key = StringKey("personal.caliper.missingProfileHint", "personal_caliper_missing_profile_hint", emptyList(), 1),
            )
            val `readyProfileHint`: LocalizedString get() = LocalizedString(
                key = StringKey("personal.caliper.readyProfileHint", "personal_caliper_ready_profile_hint", emptyList(), 1),
            )
            val `profileBirthAction`: LocalizedString get() = LocalizedString(
                key = StringKey("personal.caliper.profileBirthAction", "personal_caliper_profile_birth_action", emptyList(), 1),
            )
            val `profileGenderAction`: LocalizedString get() = LocalizedString(
                key = StringKey("personal.caliper.profileGenderAction", "personal_caliper_profile_gender_action", emptyList(), 1),
            )
            val `complete`: LocalizedString get() = LocalizedString(
                key = StringKey("personal.caliper.complete", "personal_caliper_complete", emptyList(), 1),
            )
            val `completed`: LocalizedString get() = LocalizedString(
                key = StringKey("personal.caliper.completed", "personal_caliper_completed", emptyList(), 1),
            )
            val `completionHint`: LocalizedString get() = LocalizedString(
                key = StringKey("personal.caliper.completionHint", "personal_caliper_completion_hint", emptyList(), 1),
            )
            val `missingFoldsHint`: LocalizedString get() = LocalizedString(
                key = StringKey("personal.caliper.missingFoldsHint", "personal_caliper_missing_folds_hint", emptyList(), 1),
            )
            val `addFull`: LocalizedString get() = LocalizedString(
                key = StringKey("personal.caliper.addFull", "personal_caliper_add_full", emptyList(), 1),
            )
            val `profileMetadata`: LocalizedString get() = LocalizedString(
                key = StringKey("personal.caliper.profileMetadata", "personal_caliper_profile_metadata", emptyList(), 1),
            )
            fun `profileAge`(`age`: String): LocalizedString = LocalizedString(
                key = StringKey("personal.caliper.profileAge", "personal_caliper_profile_age", listOf("age"), 1),
                arguments = listOf(`age`),
            )
            val `profileAgeUnknown`: LocalizedString get() = LocalizedString(
                key = StringKey("personal.caliper.profileAgeUnknown", "personal_caliper_profile_age_unknown", emptyList(), 1),
            )
            fun `profileGender`(`gender`: String): LocalizedString = LocalizedString(
                key = StringKey("personal.caliper.profileGender", "personal_caliper_profile_gender", listOf("gender"), 1),
                arguments = listOf(`gender`),
            )
            val `profileGenderUnknown`: LocalizedString get() = LocalizedString(
                key = StringKey("personal.caliper.profileGenderUnknown", "personal_caliper_profile_gender_unknown", emptyList(), 1),
            )
            val `measurementOverrides`: LocalizedString get() = LocalizedString(
                key = StringKey("personal.caliper.measurementOverrides", "personal_caliper_measurement_overrides", emptyList(), 1),
            )
            val `overridesHint`: LocalizedString get() = LocalizedString(
                key = StringKey("personal.caliper.overridesHint", "personal_caliper_overrides_hint", emptyList(), 1),
            )
            val `ageFromProfileHint`: LocalizedString get() = LocalizedString(
                key = StringKey("personal.caliper.ageFromProfileHint", "personal_caliper_age_from_profile_hint", emptyList(), 1),
            )
            val `profileHistoryHint`: LocalizedString get() = LocalizedString(
                key = StringKey("personal.caliper.profileHistoryHint", "personal_caliper_profile_history_hint", emptyList(), 1),
            )
            val `completeMetadata`: LocalizedString get() = LocalizedString(
                key = StringKey("personal.caliper.completeMetadata", "personal_caliper_complete_metadata", emptyList(), 1),
            )
            val `profileNotificationTitle`: LocalizedString get() = LocalizedString(
                key = StringKey("personal.caliper.profileNotificationTitle", "personal_caliper_profile_notification_title", emptyList(), 1),
            )
            val `profileFormHint`: LocalizedString get() = LocalizedString(
                key = StringKey("personal.caliper.profileFormHint", "personal_caliper_profile_form_hint", emptyList(), 1),
            )
        }
        object `Archive` {
            val `title`: LocalizedString get() = LocalizedString(
                key = StringKey("personal.archive.title", "personal_archive_title", emptyList(), 1),
            )
            fun `version`(`revision`: String): LocalizedString = LocalizedString(
                key = StringKey("personal.archive.version", "personal_archive_version", listOf("revision"), 1),
                arguments = listOf(`revision`),
            )
            val `readOnly`: LocalizedString get() = LocalizedString(
                key = StringKey("personal.archive.readOnly", "personal_archive_read_only", emptyList(), 1),
            )
            val `waiting`: LocalizedString get() = LocalizedString(
                key = StringKey("personal.archive.waiting", "personal_archive_waiting", emptyList(), 1),
            )
            val `narrowPeriod`: LocalizedString get() = LocalizedString(
                key = StringKey("personal.archive.narrowPeriod", "personal_archive_narrow_period", emptyList(), 1),
            )
            val `sourceData`: LocalizedString get() = LocalizedString(
                key = StringKey("personal.archive.sourceData", "personal_archive_source_data", emptyList(), 1),
            )
            val `detail`: LocalizedString get() = LocalizedString(
                key = StringKey("personal.archive.detail", "personal_archive_detail", emptyList(), 1),
            )
            val `summary`: LocalizedString get() = LocalizedString(
                key = StringKey("personal.archive.summary", "personal_archive_summary", emptyList(), 1),
            )
            fun `saved`(`date`: String): LocalizedString = LocalizedString(
                key = StringKey("personal.archive.saved", "personal_archive_saved", listOf("date"), 1),
                arguments = listOf(`date`),
            )
            val `search`: LocalizedString get() = LocalizedString(
                key = StringKey("personal.archive.search", "personal_archive_search", emptyList(), 1),
            )
            val `searchHint`: LocalizedString get() = LocalizedString(
                key = StringKey("personal.archive.searchHint", "personal_archive_search_hint", emptyList(), 1),
            )
            val `searching`: LocalizedString get() = LocalizedString(
                key = StringKey("personal.archive.searching", "personal_archive_searching", emptyList(), 1),
            )
            val `searchFailed`: LocalizedString get() = LocalizedString(
                key = StringKey("personal.archive.searchFailed", "personal_archive_search_failed", emptyList(), 1),
            )
            fun `count`(`count`: String): LocalizedString = LocalizedString(
                key = StringKey("personal.archive.count", "personal_archive_count", listOf("count"), 1),
                arguments = listOf(`count`),
            )
            val `noMatches`: LocalizedString get() = LocalizedString(
                key = StringKey("personal.archive.noMatches", "personal_archive_no_matches", emptyList(), 1),
            )
            val `null`: LocalizedString get() = LocalizedString(
                key = StringKey("personal.archive.null", "personal_archive_null", emptyList(), 1),
            )
            val `true`: LocalizedString get() = LocalizedString(
                key = StringKey("personal.archive.true", "personal_archive_true", emptyList(), 1),
            )
            val `false`: LocalizedString get() = LocalizedString(
                key = StringKey("personal.archive.false", "personal_archive_false", emptyList(), 1),
            )
            val `emptyArray`: LocalizedString get() = LocalizedString(
                key = StringKey("personal.archive.emptyArray", "personal_archive_empty_array", emptyList(), 1),
            )
            val `emptyObject`: LocalizedString get() = LocalizedString(
                key = StringKey("personal.archive.emptyObject", "personal_archive_empty_object", emptyList(), 1),
            )
            val `emptyString`: LocalizedString get() = LocalizedString(
                key = StringKey("personal.archive.emptyString", "personal_archive_empty_string", emptyList(), 1),
            )
            fun `textPart`(`start`: String, `end`: String, `total`: String): LocalizedString = LocalizedString(
                key = StringKey("personal.archive.textPart", "personal_archive_text_part", listOf("start", "end", "total"), 1),
                arguments = listOf(`start`, `end`, `total`),
            )
            val `previousText`: LocalizedString get() = LocalizedString(
                key = StringKey("personal.archive.previousText", "personal_archive_previous_text", emptyList(), 1),
            )
            val `nextText`: LocalizedString get() = LocalizedString(
                key = StringKey("personal.archive.nextText", "personal_archive_next_text", emptyList(), 1),
            )
            val `pages`: LocalizedString get() = LocalizedString(
                key = StringKey("personal.archive.pages", "personal_archive_pages", emptyList(), 1),
            )
            val `previous`: LocalizedString get() = LocalizedString(
                key = StringKey("personal.archive.previous", "personal_archive_previous", emptyList(), 1),
            )
            val `next`: LocalizedString get() = LocalizedString(
                key = StringKey("personal.archive.next", "personal_archive_next", emptyList(), 1),
            )
            fun `page`(`page`: String, `total`: String): LocalizedString = LocalizedString(
                key = StringKey("personal.archive.page", "personal_archive_page", listOf("page", "total"), 1),
                arguments = listOf(`page`, `total`),
            )
            object `Fields` {
                val `targetWeight`: LocalizedString get() = LocalizedString(
                    key = StringKey("personal.archive.fields.targetWeight", "personal_archive_fields_target_weight", emptyList(), 1),
                )
                val `recommendedCalories`: LocalizedString get() = LocalizedString(
                    key = StringKey("personal.archive.fields.recommendedCalories", "personal_archive_fields_recommended_calories", emptyList(), 1),
                )
                val `createdAt`: LocalizedString get() = LocalizedString(
                    key = StringKey("personal.archive.fields.createdAt", "personal_archive_fields_created_at", emptyList(), 1),
                )
                val `id`: LocalizedString get() = LocalizedString(
                    key = StringKey("personal.archive.fields.id", "personal_archive_fields_id", emptyList(), 1),
                )
            }
        }
        val `title`: LocalizedString get() = LocalizedString(
            key = StringKey("personal.title", "personal_title", emptyList(), 1),
        )
        val `conflict`: LocalizedString get() = LocalizedString(
            key = StringKey("personal.conflict", "personal_conflict", emptyList(), 1),
        )
        fun `unresolved`(`kind`: String): LocalizedString = LocalizedString(
            key = StringKey("personal.unresolved", "personal_unresolved", listOf("kind"), 1),
            arguments = listOf(`kind`),
        )
        object `Operations` {
            val `body_presentation_set`: LocalizedString get() = LocalizedString(
                key = StringKey("personal.operations.body_presentation_set", "personal_operations_body_presentation_set", emptyList(), 1),
            )
            val `caliper_add`: LocalizedString get() = LocalizedString(
                key = StringKey("personal.operations.caliper_add", "personal_operations_caliper_add", emptyList(), 1),
            )
            val `caliper_delete`: LocalizedString get() = LocalizedString(
                key = StringKey("personal.operations.caliper_delete", "personal_operations_caliper_delete", emptyList(), 1),
            )
            val `weight_add`: LocalizedString get() = LocalizedString(
                key = StringKey("personal.operations.weight_add", "personal_operations_weight_add", emptyList(), 1),
            )
            val `profile_update`: LocalizedString get() = LocalizedString(
                key = StringKey("personal.operations.profile_update", "personal_operations_profile_update", emptyList(), 1),
            )
            val `derived_backfill`: LocalizedString get() = LocalizedString(
                key = StringKey("personal.operations.derived_backfill", "personal_operations_derived_backfill", emptyList(), 1),
            )
            val `source_create`: LocalizedString get() = LocalizedString(
                key = StringKey("personal.operations.source_create", "personal_operations_source_create", emptyList(), 1),
            )
            val `source_update`: LocalizedString get() = LocalizedString(
                key = StringKey("personal.operations.source_update", "personal_operations_source_update", emptyList(), 1),
            )
            val `preference_set`: LocalizedString get() = LocalizedString(
                key = StringKey("personal.operations.preference_set", "personal_operations_preference_set", emptyList(), 1),
            )
            val `record_correct`: LocalizedString get() = LocalizedString(
                key = StringKey("personal.operations.record_correct", "personal_operations_record_correct", emptyList(), 1),
            )
            val `source_sync_request`: LocalizedString get() = LocalizedString(
                key = StringKey("personal.operations.source_sync_request", "personal_operations_source_sync_request", emptyList(), 1),
            )
            val `source_automatic_update`: LocalizedString get() = LocalizedString(
                key = StringKey("personal.operations.source_automatic_update", "personal_operations_source_automatic_update", emptyList(), 1),
            )
            val `caliper_complete`: LocalizedString get() = LocalizedString(
                key = StringKey("personal.operations.caliper_complete", "personal_operations_caliper_complete", emptyList(), 1),
            )
            val `steps_set`: LocalizedString get() = LocalizedString(
                key = StringKey("personal.operations.steps_set", "personal_operations_steps_set", emptyList(), 1),
            )
            val `steps_clear`: LocalizedString get() = LocalizedString(
                key = StringKey("personal.operations.steps_clear", "personal_operations_steps_clear", emptyList(), 1),
            )
            val `resolution_policy_set`: LocalizedString get() = LocalizedString(
                key = StringKey("personal.operations.resolution_policy_set", "personal_operations_resolution_policy_set", emptyList(), 1),
            )
            val `resolution_policies_set`: LocalizedString get() = LocalizedString(
                key = StringKey("personal.operations.resolution_policies_set", "personal_operations_resolution_policies_set", emptyList(), 1),
            )
            val `resolution_source_link`: LocalizedString get() = LocalizedString(
                key = StringKey("personal.operations.resolution_source_link", "personal_operations_resolution_source_link", emptyList(), 1),
            )
            val `resolution_source_unlink`: LocalizedString get() = LocalizedString(
                key = StringKey("personal.operations.resolution_source_unlink", "personal_operations_resolution_source_unlink", emptyList(), 1),
            )
            val `sensor_exclude`: LocalizedString get() = LocalizedString(
                key = StringKey("personal.operations.sensor_exclude", "personal_operations_sensor_exclude", emptyList(), 1),
            )
            val `sensor_restore`: LocalizedString get() = LocalizedString(
                key = StringKey("personal.operations.sensor_restore", "personal_operations_sensor_restore", emptyList(), 1),
            )
        }
        object `Ddx` {
            val `intro`: LocalizedString get() = LocalizedString(
                key = StringKey("personal.ddx.intro", "personal_ddx_intro", emptyList(), 1),
            )
            val `connect`: LocalizedString get() = LocalizedString(
                key = StringKey("personal.ddx.connect", "personal_ddx_connect", emptyList(), 1),
            )
            val `connectHint`: LocalizedString get() = LocalizedString(
                key = StringKey("personal.ddx.connectHint", "personal_ddx_connect_hint", emptyList(), 1),
            )
            val `phone`: LocalizedString get() = LocalizedString(
                key = StringKey("personal.ddx.phone", "personal_ddx_phone", emptyList(), 1),
            )
            val `getCode`: LocalizedString get() = LocalizedString(
                key = StringKey("personal.ddx.getCode", "personal_ddx_get_code", emptyList(), 1),
            )
            val `code`: LocalizedString get() = LocalizedString(
                key = StringKey("personal.ddx.code", "personal_ddx_code", emptyList(), 1),
            )
            val `codeHint`: LocalizedString get() = LocalizedString(
                key = StringKey("personal.ddx.codeHint", "personal_ddx_code_hint", emptyList(), 1),
            )
            val `verify`: LocalizedString get() = LocalizedString(
                key = StringKey("personal.ddx.verify", "personal_ddx_verify", emptyList(), 1),
            )
            fun `expires`(`when`: String): LocalizedString = LocalizedString(
                key = StringKey("personal.ddx.expires", "personal_ddx_expires", listOf("when"), 1),
                arguments = listOf(`when`),
            )
            val `resend`: LocalizedString get() = LocalizedString(
                key = StringKey("personal.ddx.resend", "personal_ddx_resend", emptyList(), 1),
            )
            fun `resendWait`(`seconds`: String): LocalizedString = LocalizedString(
                key = StringKey("personal.ddx.resendWait", "personal_ddx_resend_wait", listOf("seconds"), 1),
                arguments = listOf(`seconds`),
            )
            val `uncertain`: LocalizedString get() = LocalizedString(
                key = StringKey("personal.ddx.uncertain", "personal_ddx_uncertain", emptyList(), 1),
            )
            val `checkStatus`: LocalizedString get() = LocalizedString(
                key = StringKey("personal.ddx.checkStatus", "personal_ddx_check_status", emptyList(), 1),
            )
            val `retryStart`: LocalizedString get() = LocalizedString(
                key = StringKey("personal.ddx.retryStart", "personal_ddx_retry_start", emptyList(), 1),
            )
            val `checking`: LocalizedString get() = LocalizedString(
                key = StringKey("personal.ddx.checking", "personal_ddx_checking", emptyList(), 1),
            )
            val `unavailable`: LocalizedString get() = LocalizedString(
                key = StringKey("personal.ddx.unavailable", "personal_ddx_unavailable", emptyList(), 1),
            )
            val `reconnect`: LocalizedString get() = LocalizedString(
                key = StringKey("personal.ddx.reconnect", "personal_ddx_reconnect", emptyList(), 1),
            )
            val `samePhone`: LocalizedString get() = LocalizedString(
                key = StringKey("personal.ddx.samePhone", "personal_ddx_same_phone", emptyList(), 1),
            )
            val `legacyConnection`: LocalizedString get() = LocalizedString(
                key = StringKey("personal.ddx.legacyConnection", "personal_ddx_legacy_connection", emptyList(), 1),
            )
            val `changeAccount`: LocalizedString get() = LocalizedString(
                key = StringKey("personal.ddx.changeAccount", "personal_ddx_change_account", emptyList(), 1),
            )
            val `replaceHint`: LocalizedString get() = LocalizedString(
                key = StringKey("personal.ddx.replaceHint", "personal_ddx_replace_hint", emptyList(), 1),
            )
            val `manage`: LocalizedString get() = LocalizedString(
                key = StringKey("personal.ddx.manage", "personal_ddx_manage", emptyList(), 1),
            )
            val `disconnect`: LocalizedString get() = LocalizedString(
                key = StringKey("personal.ddx.disconnect", "personal_ddx_disconnect", emptyList(), 1),
            )
            val `disconnectHint`: LocalizedString get() = LocalizedString(
                key = StringKey("personal.ddx.disconnectHint", "personal_ddx_disconnect_hint", emptyList(), 1),
            )
            val `previousConnections`: LocalizedString get() = LocalizedString(
                key = StringKey("personal.ddx.previousConnections", "personal_ddx_previous_connections", emptyList(), 1),
            )
            val `historyKept`: LocalizedString get() = LocalizedString(
                key = StringKey("personal.ddx.historyKept", "personal_ddx_history_kept", emptyList(), 1),
            )
            val `automatic`: LocalizedString get() = LocalizedString(
                key = StringKey("personal.ddx.automatic", "personal_ddx_automatic", emptyList(), 1),
            )
            val `hourly`: LocalizedString get() = LocalizedString(
                key = StringKey("personal.ddx.hourly", "personal_ddx_hourly", emptyList(), 1),
            )
            val `manualOnly`: LocalizedString get() = LocalizedString(
                key = StringKey("personal.ddx.manualOnly", "personal_ddx_manual_only", emptyList(), 1),
            )
            val `syncNow`: LocalizedString get() = LocalizedString(
                key = StringKey("personal.ddx.syncNow", "personal_ddx_sync_now", emptyList(), 1),
            )
            val `syncing`: LocalizedString get() = LocalizedString(
                key = StringKey("personal.ddx.syncing", "personal_ddx_syncing", emptyList(), 1),
            )
            fun `syncWait`(`seconds`: String): LocalizedString = LocalizedString(
                key = StringKey("personal.ddx.syncWait", "personal_ddx_sync_wait", listOf("seconds"), 1),
                arguments = listOf(`seconds`),
            )
            val `lastMeasurement`: LocalizedString get() = LocalizedString(
                key = StringKey("personal.ddx.lastMeasurement", "personal_ddx_last_measurement", emptyList(), 1),
            )
            val `noMeasurements`: LocalizedString get() = LocalizedString(
                key = StringKey("personal.ddx.noMeasurements", "personal_ddx_no_measurements", emptyList(), 1),
            )
            val `lastSync`: LocalizedString get() = LocalizedString(
                key = StringKey("personal.ddx.lastSync", "personal_ddx_last_sync", emptyList(), 1),
            )
            val `nextSync`: LocalizedString get() = LocalizedString(
                key = StringKey("personal.ddx.nextSync", "personal_ddx_next_sync", emptyList(), 1),
            )
            val `notSynced`: LocalizedString get() = LocalizedString(
                key = StringKey("personal.ddx.notSynced", "personal_ddx_not_synced", emptyList(), 1),
            )
            fun `syncCounts`(`added`: String, `updated`: String): LocalizedString = LocalizedString(
                key = StringKey("personal.ddx.syncCounts", "personal_ddx_sync_counts", listOf("added", "updated"), 1),
                arguments = listOf(`added`, `updated`),
            )
            object `Connection` {
                val `active`: LocalizedString get() = LocalizedString(
                    key = StringKey("personal.ddx.connection.active", "personal_ddx_connection_active", emptyList(), 1),
                )
                val `auth_required`: LocalizedString get() = LocalizedString(
                    key = StringKey("personal.ddx.connection.auth_required", "personal_ddx_connection_auth_required", emptyList(), 1),
                )
                val `disconnected`: LocalizedString get() = LocalizedString(
                    key = StringKey("personal.ddx.connection.disconnected", "personal_ddx_connection_disconnected", emptyList(), 1),
                )
            }
            object `Login` {
                val `sending`: LocalizedString get() = LocalizedString(
                    key = StringKey("personal.ddx.login.sending", "personal_ddx_login_sending", emptyList(), 1),
                )
                val `waiting_code`: LocalizedString get() = LocalizedString(
                    key = StringKey("personal.ddx.login.waiting_code", "personal_ddx_login_waiting_code", emptyList(), 1),
                )
                val `verifying`: LocalizedString get() = LocalizedString(
                    key = StringKey("personal.ddx.login.verifying", "personal_ddx_login_verifying", emptyList(), 1),
                )
                val `staged`: LocalizedString get() = LocalizedString(
                    key = StringKey("personal.ddx.login.staged", "personal_ddx_login_staged", emptyList(), 1),
                )
                val `ready`: LocalizedString get() = LocalizedString(
                    key = StringKey("personal.ddx.login.ready", "personal_ddx_login_ready", emptyList(), 1),
                )
                val `failed`: LocalizedString get() = LocalizedString(
                    key = StringKey("personal.ddx.login.failed", "personal_ddx_login_failed", emptyList(), 1),
                )
                val `cancelled`: LocalizedString get() = LocalizedString(
                    key = StringKey("personal.ddx.login.cancelled", "personal_ddx_login_cancelled", emptyList(), 1),
                )
                val `expired`: LocalizedString get() = LocalizedString(
                    key = StringKey("personal.ddx.login.expired", "personal_ddx_login_expired", emptyList(), 1),
                )
                val `interrupted`: LocalizedString get() = LocalizedString(
                    key = StringKey("personal.ddx.login.interrupted", "personal_ddx_login_interrupted", emptyList(), 1),
                )
            }
            object `Run` {
                val `queued`: LocalizedString get() = LocalizedString(
                    key = StringKey("personal.ddx.run.queued", "personal_ddx_run_queued", emptyList(), 1),
                )
                val `running`: LocalizedString get() = LocalizedString(
                    key = StringKey("personal.ddx.run.running", "personal_ddx_run_running", emptyList(), 1),
                )
                val `succeeded`: LocalizedString get() = LocalizedString(
                    key = StringKey("personal.ddx.run.succeeded", "personal_ddx_run_succeeded", emptyList(), 1),
                )
                val `failed`: LocalizedString get() = LocalizedString(
                    key = StringKey("personal.ddx.run.failed", "personal_ddx_run_failed", emptyList(), 1),
                )
                val `partial`: LocalizedString get() = LocalizedString(
                    key = StringKey("personal.ddx.run.partial", "personal_ddx_run_partial", emptyList(), 1),
                )
                val `cancelled`: LocalizedString get() = LocalizedString(
                    key = StringKey("personal.ddx.run.cancelled", "personal_ddx_run_cancelled", emptyList(), 1),
                )
            }
            object `Error` {
                val `invalid_phone`: LocalizedString get() = LocalizedString(
                    key = StringKey("personal.ddx.error.invalid_phone", "personal_ddx_error_invalid_phone", emptyList(), 1),
                )
                val `invalid_verification_code`: LocalizedString get() = LocalizedString(
                    key = StringKey("personal.ddx.error.invalid_verification_code", "personal_ddx_error_invalid_verification_code", emptyList(), 1),
                )
                val `invalid_code`: LocalizedString get() = LocalizedString(
                    key = StringKey("personal.ddx.error.invalid_code", "personal_ddx_error_invalid_code", emptyList(), 1),
                )
                val `invalid_request`: LocalizedString get() = LocalizedString(
                    key = StringKey("personal.ddx.error.invalid_request", "personal_ddx_error_invalid_request", emptyList(), 1),
                )
                val `request_changed`: LocalizedString get() = LocalizedString(
                    key = StringKey("personal.ddx.error.request_changed", "personal_ddx_error_request_changed", emptyList(), 1),
                )
                val `login_in_progress`: LocalizedString get() = LocalizedString(
                    key = StringKey("personal.ddx.error.login_in_progress", "personal_ddx_error_login_in_progress", emptyList(), 1),
                )
                val `already_connected`: LocalizedString get() = LocalizedString(
                    key = StringKey("personal.ddx.error.already_connected", "personal_ddx_error_already_connected", emptyList(), 1),
                )
                val `connection_changed`: LocalizedString get() = LocalizedString(
                    key = StringKey("personal.ddx.error.connection_changed", "personal_ddx_error_connection_changed", emptyList(), 1),
                )
                val `same_account`: LocalizedString get() = LocalizedString(
                    key = StringKey("personal.ddx.error.same_account", "personal_ddx_error_same_account", emptyList(), 1),
                )
                val `resend_cooldown`: LocalizedString get() = LocalizedString(
                    key = StringKey("personal.ddx.error.resend_cooldown", "personal_ddx_error_resend_cooldown", emptyList(), 1),
                )
                val `too_many_attempts`: LocalizedString get() = LocalizedString(
                    key = StringKey("personal.ddx.error.too_many_attempts", "personal_ddx_error_too_many_attempts", emptyList(), 1),
                )
                val `login_unavailable`: LocalizedString get() = LocalizedString(
                    key = StringKey("personal.ddx.error.login_unavailable", "personal_ddx_error_login_unavailable", emptyList(), 1),
                )
                val `provider_unavailable`: LocalizedString get() = LocalizedString(
                    key = StringKey("personal.ddx.error.provider_unavailable", "personal_ddx_error_provider_unavailable", emptyList(), 1),
                )
                val `interrupted`: LocalizedString get() = LocalizedString(
                    key = StringKey("personal.ddx.error.interrupted", "personal_ddx_error_interrupted", emptyList(), 1),
                )
                val `code_expired`: LocalizedString get() = LocalizedString(
                    key = StringKey("personal.ddx.error.code_expired", "personal_ddx_error_code_expired", emptyList(), 1),
                )
                val `account_mismatch`: LocalizedString get() = LocalizedString(
                    key = StringKey("personal.ddx.error.account_mismatch", "personal_ddx_error_account_mismatch", emptyList(), 1),
                )
                val `request_failed`: LocalizedString get() = LocalizedString(
                    key = StringKey("personal.ddx.error.request_failed", "personal_ddx_error_request_failed", emptyList(), 1),
                )
                val `enrollment_failed`: LocalizedString get() = LocalizedString(
                    key = StringKey("personal.ddx.error.enrollment_failed", "personal_ddx_error_enrollment_failed", emptyList(), 1),
                )
                val `connection_unavailable`: LocalizedString get() = LocalizedString(
                    key = StringKey("personal.ddx.error.connection_unavailable", "personal_ddx_error_connection_unavailable", emptyList(), 1),
                )
                val `transport_failed`: LocalizedString get() = LocalizedString(
                    key = StringKey("personal.ddx.error.transport_failed", "personal_ddx_error_transport_failed", emptyList(), 1),
                )
                val `token_storage_failed`: LocalizedString get() = LocalizedString(
                    key = StringKey("personal.ddx.error.token_storage_failed", "personal_ddx_error_token_storage_failed", emptyList(), 1),
                )
                val `network_error`: LocalizedString get() = LocalizedString(
                    key = StringKey("personal.ddx.error.network_error", "personal_ddx_error_network_error", emptyList(), 1),
                )
                val `rate_limited`: LocalizedString get() = LocalizedString(
                    key = StringKey("personal.ddx.error.rate_limited", "personal_ddx_error_rate_limited", emptyList(), 1),
                )
                val `login_expired`: LocalizedString get() = LocalizedString(
                    key = StringKey("personal.ddx.error.login_expired", "personal_ddx_error_login_expired", emptyList(), 1),
                )
                val `login_busy`: LocalizedString get() = LocalizedString(
                    key = StringKey("personal.ddx.error.login_busy", "personal_ddx_error_login_busy", emptyList(), 1),
                )
                val `revision_conflict`: LocalizedString get() = LocalizedString(
                    key = StringKey("personal.ddx.error.revision_conflict", "personal_ddx_error_revision_conflict", emptyList(), 1),
                )
                val `generation_conflict`: LocalizedString get() = LocalizedString(
                    key = StringKey("personal.ddx.error.generation_conflict", "personal_ddx_error_generation_conflict", emptyList(), 1),
                )
                val `automatic_disabled`: LocalizedString get() = LocalizedString(
                    key = StringKey("personal.ddx.error.automatic_disabled", "personal_ddx_error_automatic_disabled", emptyList(), 1),
                )
                val `connection_busy`: LocalizedString get() = LocalizedString(
                    key = StringKey("personal.ddx.error.connection_busy", "personal_ddx_error_connection_busy", emptyList(), 1),
                )
                val `auth_required`: LocalizedString get() = LocalizedString(
                    key = StringKey("personal.ddx.error.auth_required", "personal_ddx_error_auth_required", emptyList(), 1),
                )
            }
            val `phonePlaceholder`: LocalizedString get() = LocalizedString(
                key = StringKey("personal.ddx.phonePlaceholder", "personal_ddx_phone_placeholder", emptyList(), 1),
            )
            val `title`: LocalizedString get() = LocalizedString(
                key = StringKey("personal.ddx.title", "personal_ddx_title", emptyList(), 1),
            )
        }
        object `Metric` {
            val `weight`: LocalizedString get() = LocalizedString(
                key = StringKey("personal.metric.weight", "personal_metric_weight", emptyList(), 1),
            )
            val `sleep`: LocalizedString get() = LocalizedString(
                key = StringKey("personal.metric.sleep", "personal_metric_sleep", emptyList(), 1),
            )
            val `steps`: LocalizedString get() = LocalizedString(
                key = StringKey("personal.metric.steps", "personal_metric_steps", emptyList(), 1),
            )
            val `active_minutes`: LocalizedString get() = LocalizedString(
                key = StringKey("personal.metric.active_minutes", "personal_metric_active_minutes", emptyList(), 1),
            )
            val `body_fat_percent`: LocalizedString get() = LocalizedString(
                key = StringKey("personal.metric.body_fat_percent", "personal_metric_body_fat_percent", emptyList(), 1),
            )
            val `body_fat_mass`: LocalizedString get() = LocalizedString(
                key = StringKey("personal.metric.body_fat_mass", "personal_metric_body_fat_mass", emptyList(), 1),
            )
            val `skeletal_muscle_mass`: LocalizedString get() = LocalizedString(
                key = StringKey("personal.metric.skeletal_muscle_mass", "personal_metric_skeletal_muscle_mass", emptyList(), 1),
            )
            val `fat_free_mass`: LocalizedString get() = LocalizedString(
                key = StringKey("personal.metric.fat_free_mass", "personal_metric_fat_free_mass", emptyList(), 1),
            )
            val `body_water`: LocalizedString get() = LocalizedString(
                key = StringKey("personal.metric.body_water", "personal_metric_body_water", emptyList(), 1),
            )
            val `body_protein`: LocalizedString get() = LocalizedString(
                key = StringKey("personal.metric.body_protein", "personal_metric_body_protein", emptyList(), 1),
            )
            val `body_minerals`: LocalizedString get() = LocalizedString(
                key = StringKey("personal.metric.body_minerals", "personal_metric_body_minerals", emptyList(), 1),
            )
            val `bmi`: LocalizedString get() = LocalizedString(
                key = StringKey("personal.metric.bmi", "personal_metric_bmi", emptyList(), 1),
            )
            val `basal_metabolic_rate`: LocalizedString get() = LocalizedString(
                key = StringKey("personal.metric.basal_metabolic_rate", "personal_metric_basal_metabolic_rate", emptyList(), 1),
            )
            val `visceral_fat_level`: LocalizedString get() = LocalizedString(
                key = StringKey("personal.metric.visceral_fat_level", "personal_metric_visceral_fat_level", emptyList(), 1),
            )
            val `nutrition`: LocalizedString get() = LocalizedString(
                key = StringKey("personal.metric.nutrition", "personal_metric_nutrition", emptyList(), 1),
            )
            val `running_steps`: LocalizedString get() = LocalizedString(
                key = StringKey("personal.metric.running_steps", "personal_metric_running_steps", emptyList(), 1),
            )
            val `running_workouts`: LocalizedString get() = LocalizedString(
                key = StringKey("personal.metric.running_workouts", "personal_metric_running_workouts", emptyList(), 1),
            )
            val `workouts`: LocalizedString get() = LocalizedString(
                key = StringKey("personal.metric.workouts", "personal_metric_workouts", emptyList(), 1),
            )
            val `active_energy`: LocalizedString get() = LocalizedString(
                key = StringKey("personal.metric.active_energy", "personal_metric_active_energy", emptyList(), 1),
            )
            val `distance`: LocalizedString get() = LocalizedString(
                key = StringKey("personal.metric.distance", "personal_metric_distance", emptyList(), 1),
            )
        }
        object `Origin` {
            val `corrected`: LocalizedString get() = LocalizedString(
                key = StringKey("personal.origin.corrected", "personal_origin_corrected", emptyList(), 1),
            )
            val `estimate`: LocalizedString get() = LocalizedString(
                key = StringKey("personal.origin.estimate", "personal_origin_estimate", emptyList(), 1),
            )
            val `manual`: LocalizedString get() = LocalizedString(
                key = StringKey("personal.origin.manual", "personal_origin_manual", emptyList(), 1),
            )
            val `device`: LocalizedString get() = LocalizedString(
                key = StringKey("personal.origin.device", "personal_origin_device", emptyList(), 1),
            )
        }
        object `ValueOf` {
            val `weight`: LocalizedString get() = LocalizedString(
                key = StringKey("personal.valueOf.weight", "personal_value_of_weight", emptyList(), 1),
            )
            val `sleep`: LocalizedString get() = LocalizedString(
                key = StringKey("personal.valueOf.sleep", "personal_value_of_sleep", emptyList(), 1),
            )
            val `steps`: LocalizedString get() = LocalizedString(
                key = StringKey("personal.valueOf.steps", "personal_value_of_steps", emptyList(), 1),
            )
            val `active_minutes`: LocalizedString get() = LocalizedString(
                key = StringKey("personal.valueOf.active_minutes", "personal_value_of_active_minutes", emptyList(), 1),
            )
            val `body_fat_percent`: LocalizedString get() = LocalizedString(
                key = StringKey("personal.valueOf.body_fat_percent", "personal_value_of_body_fat_percent", emptyList(), 1),
            )
            val `body_fat_mass`: LocalizedString get() = LocalizedString(
                key = StringKey("personal.valueOf.body_fat_mass", "personal_value_of_body_fat_mass", emptyList(), 1),
            )
            val `skeletal_muscle_mass`: LocalizedString get() = LocalizedString(
                key = StringKey("personal.valueOf.skeletal_muscle_mass", "personal_value_of_skeletal_muscle_mass", emptyList(), 1),
            )
            val `fat_free_mass`: LocalizedString get() = LocalizedString(
                key = StringKey("personal.valueOf.fat_free_mass", "personal_value_of_fat_free_mass", emptyList(), 1),
            )
            val `body_water`: LocalizedString get() = LocalizedString(
                key = StringKey("personal.valueOf.body_water", "personal_value_of_body_water", emptyList(), 1),
            )
            val `body_protein`: LocalizedString get() = LocalizedString(
                key = StringKey("personal.valueOf.body_protein", "personal_value_of_body_protein", emptyList(), 1),
            )
            val `body_minerals`: LocalizedString get() = LocalizedString(
                key = StringKey("personal.valueOf.body_minerals", "personal_value_of_body_minerals", emptyList(), 1),
            )
            val `bmi`: LocalizedString get() = LocalizedString(
                key = StringKey("personal.valueOf.bmi", "personal_value_of_bmi", emptyList(), 1),
            )
            val `basal_metabolic_rate`: LocalizedString get() = LocalizedString(
                key = StringKey("personal.valueOf.basal_metabolic_rate", "personal_value_of_basal_metabolic_rate", emptyList(), 1),
            )
            val `visceral_fat_level`: LocalizedString get() = LocalizedString(
                key = StringKey("personal.valueOf.visceral_fat_level", "personal_value_of_visceral_fat_level", emptyList(), 1),
            )
            val `active_energy`: LocalizedString get() = LocalizedString(
                key = StringKey("personal.valueOf.active_energy", "personal_value_of_active_energy", emptyList(), 1),
            )
            val `distance`: LocalizedString get() = LocalizedString(
                key = StringKey("personal.valueOf.distance", "personal_value_of_distance", emptyList(), 1),
            )
        }
        object `SourceState` {
            val `receiving`: LocalizedString get() = LocalizedString(
                key = StringKey("personal.sourceState.receiving", "personal_source_state_receiving", emptyList(), 1),
            )
            val `waiting`: LocalizedString get() = LocalizedString(
                key = StringKey("personal.sourceState.waiting", "personal_source_state_waiting", emptyList(), 1),
            )
            val `disabled`: LocalizedString get() = LocalizedString(
                key = StringKey("personal.sourceState.disabled", "personal_source_state_disabled", emptyList(), 1),
            )
            val `unavailable`: LocalizedString get() = LocalizedString(
                key = StringKey("personal.sourceState.unavailable", "personal_source_state_unavailable", emptyList(), 1),
            )
        }
        val `kg`: LocalizedString get() = LocalizedString(
            key = StringKey("personal.kg", "personal_kg", emptyList(), 1),
        )
        val `addWeight`: LocalizedString get() = LocalizedString(
            key = StringKey("personal.addWeight", "personal_add_weight", emptyList(), 1),
        )
        val `weightKg`: LocalizedString get() = LocalizedString(
            key = StringKey("personal.weightKg", "personal_weight_kg", emptyList(), 1),
        )
        val `measuredAt`: LocalizedString get() = LocalizedString(
            key = StringKey("personal.measuredAt", "personal_measured_at", emptyList(), 1),
        )
        fun `zoneHint`(`zone`: String): LocalizedString = LocalizedString(
            key = StringKey("personal.zoneHint", "personal_zone_hint", listOf("zone"), 1),
            arguments = listOf(`zone`),
        )
        val `saveWeight`: LocalizedString get() = LocalizedString(
            key = StringKey("personal.saveWeight", "personal_save_weight", emptyList(), 1),
        )
        val `weightSaved`: LocalizedString get() = LocalizedString(
            key = StringKey("personal.weightSaved", "personal_weight_saved", emptyList(), 1),
        )
        val `weightRange`: LocalizedString get() = LocalizedString(
            key = StringKey("personal.weightRange", "personal_weight_range", emptyList(), 1),
        )
        val `timeRequired`: LocalizedString get() = LocalizedString(
            key = StringKey("personal.timeRequired", "personal_time_required", emptyList(), 1),
        )
        val `futureTime`: LocalizedString get() = LocalizedString(
            key = StringKey("personal.futureTime", "personal_future_time", emptyList(), 1),
        )
        val `profile`: LocalizedString get() = LocalizedString(
            key = StringKey("personal.profile", "personal_profile", emptyList(), 1),
        )
        val `profileHint`: LocalizedString get() = LocalizedString(
            key = StringKey("personal.profileHint", "personal_profile_hint", emptyList(), 1),
        )
        val `profileSummary`: LocalizedString get() = LocalizedString(
            key = StringKey("personal.profileSummary", "personal_profile_summary", emptyList(), 1),
        )
        val `height`: LocalizedString get() = LocalizedString(
            key = StringKey("personal.height", "personal_height", emptyList(), 1),
        )
        val `birthDate`: LocalizedString get() = LocalizedString(
            key = StringKey("personal.birthDate", "personal_birth_date", emptyList(), 1),
        )
        val `timezone`: LocalizedString get() = LocalizedString(
            key = StringKey("personal.timezone", "personal_timezone", emptyList(), 1),
        )
        val `timezoneHint`: LocalizedString get() = LocalizedString(
            key = StringKey("personal.timezoneHint", "personal_timezone_hint", emptyList(), 1),
        )
        val `saveProfile`: LocalizedString get() = LocalizedString(
            key = StringKey("personal.saveProfile", "personal_save_profile", emptyList(), 1),
        )
        val `profileSaved`: LocalizedString get() = LocalizedString(
            key = StringKey("personal.profileSaved", "personal_profile_saved", emptyList(), 1),
        )
        val `heightRange`: LocalizedString get() = LocalizedString(
            key = StringKey("personal.heightRange", "personal_height_range", emptyList(), 1),
        )
        val `birthInvalid`: LocalizedString get() = LocalizedString(
            key = StringKey("personal.birthInvalid", "personal_birth_invalid", emptyList(), 1),
        )
        val `zoneInvalid`: LocalizedString get() = LocalizedString(
            key = StringKey("personal.zoneInvalid", "personal_zone_invalid", emptyList(), 1),
        )
        val `goalElsewhere`: LocalizedString get() = LocalizedString(
            key = StringKey("personal.goalElsewhere", "personal_goal_elsewhere", emptyList(), 1),
        )
        val `unnamedSource`: LocalizedString get() = LocalizedString(
            key = StringKey("personal.unnamedSource", "personal_unnamed_source", emptyList(), 1),
        )
        val `noValue`: LocalizedString get() = LocalizedString(
            key = StringKey("personal.noValue", "personal_no_value", emptyList(), 1),
        )
        val `correctTitle`: LocalizedString get() = LocalizedString(
            key = StringKey("personal.correctTitle", "personal_correct_title", emptyList(), 1),
        )
        val `excluded`: LocalizedString get() = LocalizedString(
            key = StringKey("personal.excluded", "personal_excluded", emptyList(), 1),
        )
        fun `originalValue`(`value`: String): LocalizedString = LocalizedString(
            key = StringKey("personal.originalValue", "personal_original_value", listOf("value"), 1),
            arguments = listOf(`value`),
        )
        val `startedAt`: LocalizedString get() = LocalizedString(
            key = StringKey("personal.startedAt", "personal_started_at", emptyList(), 1),
        )
        val `endedAt`: LocalizedString get() = LocalizedString(
            key = StringKey("personal.endedAt", "personal_ended_at", emptyList(), 1),
        )
        fun `correctHint`(`zone`: String): LocalizedString = LocalizedString(
            key = StringKey("personal.correctHint", "personal_correct_hint", listOf("zone"), 1),
            arguments = listOf(`zone`),
        )
        val `saveCorrection`: LocalizedString get() = LocalizedString(
            key = StringKey("personal.saveCorrection", "personal_save_correction", emptyList(), 1),
        )
        val `corrected`: LocalizedString get() = LocalizedString(
            key = StringKey("personal.corrected", "personal_corrected", emptyList(), 1),
        )
        val `valueRequired`: LocalizedString get() = LocalizedString(
            key = StringKey("personal.valueRequired", "personal_value_required", emptyList(), 1),
        )
        val `exclude`: LocalizedString get() = LocalizedString(
            key = StringKey("personal.exclude", "personal_exclude", emptyList(), 1),
        )
        val `include`: LocalizedString get() = LocalizedString(
            key = StringKey("personal.include", "personal_include", emptyList(), 1),
        )
        val `excludedDone`: LocalizedString get() = LocalizedString(
            key = StringKey("personal.excludedDone", "personal_excluded_done", emptyList(), 1),
        )
        val `included`: LocalizedString get() = LocalizedString(
            key = StringKey("personal.included", "personal_included", emptyList(), 1),
        )
        val `reset`: LocalizedString get() = LocalizedString(
            key = StringKey("personal.reset", "personal_reset", emptyList(), 1),
        )
        val `resetDone`: LocalizedString get() = LocalizedString(
            key = StringKey("personal.resetDone", "personal_reset_done", emptyList(), 1),
        )
        val `disagree`: LocalizedString get() = LocalizedString(
            key = StringKey("personal.disagree", "personal_disagree", emptyList(), 1),
        )
        val `unavailable`: LocalizedString get() = LocalizedString(
            key = StringKey("personal.unavailable", "personal_unavailable", emptyList(), 1),
        )
        val `noData`: LocalizedString get() = LocalizedString(
            key = StringKey("personal.noData", "personal_no_data", emptyList(), 1),
        )
        val `noDataText`: LocalizedString get() = LocalizedString(
            key = StringKey("personal.noDataText", "personal_no_data_text", emptyList(), 1),
        )
        val `periodOnly`: LocalizedString get() = LocalizedString(
            key = StringKey("personal.periodOnly", "personal_period_only", emptyList(), 1),
        )
        val `sleepOverlap`: LocalizedString get() = LocalizedString(
            key = StringKey("personal.sleepOverlap", "personal_sleep_overlap", emptyList(), 1),
        )
        val `sleepStages`: LocalizedString get() = LocalizedString(
            key = StringKey("personal.sleepStages", "personal_sleep_stages", emptyList(), 1),
        )
        object `SleepNight` {
            val `latency`: LocalizedString get() = LocalizedString(
                key = StringKey("personal.sleepNight.latency", "personal_sleep_night_latency", emptyList(), 1),
            )
            val `efficiency`: LocalizedString get() = LocalizedString(
                key = StringKey("personal.sleepNight.efficiency", "personal_sleep_night_efficiency", emptyList(), 1),
            )
            val `awakenings`: LocalizedString get() = LocalizedString(
                key = StringKey("personal.sleepNight.awakenings", "personal_sleep_night_awakenings", emptyList(), 1),
            )
            fun `awakeningCount`(quantity: Double, `count`: String, `duration`: String): LocalizedString = LocalizedString(
                key = StringKey("personal.sleepNight.awakeningCount", "personal_sleep_night_awakening_count", listOf("count", "duration"), 4),
                arguments = listOf(`count`, `duration`),
                quantity = quantity,
            )
            val `turns`: LocalizedString get() = LocalizedString(
                key = StringKey("personal.sleepNight.turns", "personal_sleep_night_turns", emptyList(), 1),
            )
            fun `turnRate`(`rate`: String): LocalizedString = LocalizedString(
                key = StringKey("personal.sleepNight.turnRate", "personal_sleep_night_turn_rate", listOf("rate"), 1),
                arguments = listOf(`rate`),
            )
            val `heartRate`: LocalizedString get() = LocalizedString(
                key = StringKey("personal.sleepNight.heartRate", "personal_sleep_night_heart_rate", emptyList(), 1),
            )
            fun `heartRateValue`(`lowest`: String, `mean`: String): LocalizedString = LocalizedString(
                key = StringKey("personal.sleepNight.heartRateValue", "personal_sleep_night_heart_rate_value", listOf("lowest", "mean"), 1),
                arguments = listOf(`lowest`, `mean`),
            )
            val `hrv`: LocalizedString get() = LocalizedString(
                key = StringKey("personal.sleepNight.hrv", "personal_sleep_night_hrv", emptyList(), 1),
            )
            fun `hrvValue`(`value`: String): LocalizedString = LocalizedString(
                key = StringKey("personal.sleepNight.hrvValue", "personal_sleep_night_hrv_value", listOf("value"), 1),
                arguments = listOf(`value`),
            )
            val `midpoint`: LocalizedString get() = LocalizedString(
                key = StringKey("personal.sleepNight.midpoint", "personal_sleep_night_midpoint", emptyList(), 1),
            )
        }
        object `SleepChart` {
            val `inBed`: LocalizedString get() = LocalizedString(
                key = StringKey("personal.sleepChart.inBed", "personal_sleep_chart_in_bed", emptyList(), 1),
            )
            val `turns`: LocalizedString get() = LocalizedString(
                key = StringKey("personal.sleepChart.turns", "personal_sleep_chart_turns", emptyList(), 1),
            )
            fun `turnCount`(quantity: Double, `count`: String): LocalizedString = LocalizedString(
                key = StringKey("personal.sleepChart.turnCount", "personal_sleep_chart_turn_count", listOf("count"), 3),
                arguments = listOf(`count`),
                quantity = quantity,
            )
        }
        object `SleepStage` {
            val `light`: LocalizedString get() = LocalizedString(
                key = StringKey("personal.sleepStage.light", "personal_sleep_stage_light", emptyList(), 1),
            )
            val `deep`: LocalizedString get() = LocalizedString(
                key = StringKey("personal.sleepStage.deep", "personal_sleep_stage_deep", emptyList(), 1),
            )
            val `rem`: LocalizedString get() = LocalizedString(
                key = StringKey("personal.sleepStage.rem", "personal_sleep_stage_rem", emptyList(), 1),
            )
            val `awake`: LocalizedString get() = LocalizedString(
                key = StringKey("personal.sleepStage.awake", "personal_sleep_stage_awake", emptyList(), 1),
            )
            val `unknown`: LocalizedString get() = LocalizedString(
                key = StringKey("personal.sleepStage.unknown", "personal_sleep_stage_unknown", emptyList(), 1),
            )
            val `sleeping`: LocalizedString get() = LocalizedString(
                key = StringKey("personal.sleepStage.sleeping", "personal_sleep_stage_sleeping", emptyList(), 1),
            )
            val `awake_in_bed`: LocalizedString get() = LocalizedString(
                key = StringKey("personal.sleepStage.awake_in_bed", "personal_sleep_stage_awake_in_bed", emptyList(), 1),
            )
            val `out_of_bed`: LocalizedString get() = LocalizedString(
                key = StringKey("personal.sleepStage.out_of_bed", "personal_sleep_stage_out_of_bed", emptyList(), 1),
            )
        }
        object `SleepQuality` {
            val `unknown_sleep_stage`: LocalizedString get() = LocalizedString(
                key = StringKey("personal.sleepQuality.unknown_sleep_stage", "personal_sleep_quality_unknown_sleep_stage", emptyList(), 1),
            )
            val `duration_mismatch`: LocalizedString get() = LocalizedString(
                key = StringKey("personal.sleepQuality.duration_mismatch", "personal_sleep_quality_duration_mismatch", emptyList(), 1),
            )
            val `ambiguous_time`: LocalizedString get() = LocalizedString(
                key = StringKey("personal.sleepQuality.ambiguous_time", "personal_sleep_quality_ambiguous_time", emptyList(), 1),
            )
        }
        val `incomplete`: LocalizedString get() = LocalizedString(
            key = StringKey("personal.incomplete", "personal_incomplete", emptyList(), 1),
        )
        val `sourceDown`: LocalizedString get() = LocalizedString(
            key = StringKey("personal.sourceDown", "personal_source_down", emptyList(), 1),
        )
        object `SourceError` {
            val `history_restricted`: LocalizedString get() = LocalizedString(
                key = StringKey("personal.sourceError.history_restricted", "personal_source_error_history_restricted", emptyList(), 1),
            )
            val `auth_required`: LocalizedString get() = LocalizedString(
                key = StringKey("personal.sourceError.auth_required", "personal_source_error_auth_required", emptyList(), 1),
            )
            val `access_denied`: LocalizedString get() = LocalizedString(
                key = StringKey("personal.sourceError.access_denied", "personal_source_error_access_denied", emptyList(), 1),
            )
            val `rate_limited`: LocalizedString get() = LocalizedString(
                key = StringKey("personal.sourceError.rate_limited", "personal_source_error_rate_limited", emptyList(), 1),
            )
            val `credentials_unavailable`: LocalizedString get() = LocalizedString(
                key = StringKey("personal.sourceError.credentials_unavailable", "personal_source_error_credentials_unavailable", emptyList(), 1),
            )
            val `network_error`: LocalizedString get() = LocalizedString(
                key = StringKey("personal.sourceError.network_error", "personal_source_error_network_error", emptyList(), 1),
            )
            val `unsupported_response`: LocalizedString get() = LocalizedString(
                key = StringKey("personal.sourceError.unsupported_response", "personal_source_error_unsupported_response", emptyList(), 1),
            )
            val `projection_incomplete`: LocalizedString get() = LocalizedString(
                key = StringKey("personal.sourceError.projection_incomplete", "personal_source_error_projection_incomplete", emptyList(), 1),
            )
            val `storage_unavailable`: LocalizedString get() = LocalizedString(
                key = StringKey("personal.sourceError.storage_unavailable", "personal_source_error_storage_unavailable", emptyList(), 1),
            )
            val `sync_failed`: LocalizedString get() = LocalizedString(
                key = StringKey("personal.sourceError.sync_failed", "personal_source_error_sync_failed", emptyList(), 1),
            )
            val `schema_unverified`: LocalizedString get() = LocalizedString(
                key = StringKey("personal.sourceError.schema_unverified", "personal_source_error_schema_unverified", emptyList(), 1),
            )
        }
        val `seeSources`: LocalizedString get() = LocalizedString(
            key = StringKey("personal.seeSources", "personal_see_sources", emptyList(), 1),
        )
        val `provenance`: LocalizedString get() = LocalizedString(
            key = StringKey("personal.provenance", "personal_provenance", emptyList(), 1),
        )
        fun `target`(`value`: String): LocalizedString = LocalizedString(
            key = StringKey("personal.target", "personal_target", listOf("value"), 1),
            arguments = listOf(`value`),
        )
        val `targetLine`: LocalizedString get() = LocalizedString(
            key = StringKey("personal.targetLine", "personal_target_line", emptyList(), 1),
        )
        fun `weightChange`(`change`: String): LocalizedString = LocalizedString(
            key = StringKey("personal.weightChange", "personal_weight_change", listOf("change"), 1),
            arguments = listOf(`change`),
        )
        fun `lastWeight`(`when`: String): LocalizedString = LocalizedString(
            key = StringKey("personal.lastWeight", "personal_last_weight", listOf("when"), 1),
            arguments = listOf(`when`),
        )
        val `noWeight`: LocalizedString get() = LocalizedString(
            key = StringKey("personal.noWeight", "personal_no_weight", emptyList(), 1),
        )
        val `history`: LocalizedString get() = LocalizedString(
            key = StringKey("personal.history", "personal_history", emptyList(), 1),
        )
        val `bodyComposition`: LocalizedString get() = LocalizedString(
            key = StringKey("personal.bodyComposition", "personal_body_composition", emptyList(), 1),
        )
        val `bodyHistory`: LocalizedString get() = LocalizedString(
            key = StringKey("personal.bodyHistory", "personal_body_history", emptyList(), 1),
        )
        val `latestBodyMeasurements`: LocalizedString get() = LocalizedString(
            key = StringKey("personal.latestBodyMeasurements", "personal_latest_body_measurements", emptyList(), 1),
        )
        val `aboutBodyMetrics`: LocalizedString get() = LocalizedString(
            key = StringKey("personal.aboutBodyMetrics", "personal_about_body_metrics", emptyList(), 1),
        )
        val `bodyNotReceived`: LocalizedString get() = LocalizedString(
            key = StringKey("personal.bodyNotReceived", "personal_body_not_received", emptyList(), 1),
        )
        val `restrictedHistory`: LocalizedString get() = LocalizedString(
            key = StringKey("personal.restrictedHistory", "personal_restricted_history", emptyList(), 1),
        )
        val `bodyHistoryEmpty`: LocalizedString get() = LocalizedString(
            key = StringKey("personal.bodyHistoryEmpty", "personal_body_history_empty", emptyList(), 1),
        )
        val `bodyHistoryEmptyText`: LocalizedString get() = LocalizedString(
            key = StringKey("personal.bodyHistoryEmptyText", "personal_body_history_empty_text", emptyList(), 1),
        )
        val `bodyTrend`: LocalizedString get() = LocalizedString(
            key = StringKey("personal.bodyTrend", "personal_body_trend", emptyList(), 1),
        )
        fun `bodyTrendLabel`(`kind`: String): LocalizedString = LocalizedString(
            key = StringKey("personal.bodyTrendLabel", "personal_body_trend_label", listOf("kind"), 1),
            arguments = listOf(`kind`),
        )
        val `bodyTrendEmpty`: LocalizedString get() = LocalizedString(
            key = StringKey("personal.bodyTrendEmpty", "personal_body_trend_empty", emptyList(), 1),
        )
        fun `analysisCount`(quantity: Double, `count`: String): LocalizedString = LocalizedString(
            key = StringKey("personal.analysisCount", "personal_analysis_count", listOf("count"), 3),
            arguments = listOf(`count`),
            quantity = quantity,
        )
        fun `analysisDetails`(`count`: String): LocalizedString = LocalizedString(
            key = StringKey("personal.analysisDetails", "personal_analysis_details", listOf("count"), 1),
            arguments = listOf(`count`),
        )
        fun `comparedWith`(`when`: String): LocalizedString = LocalizedString(
            key = StringKey("personal.comparedWith", "personal_compared_with", listOf("when"), 1),
            arguments = listOf(`when`),
        )
        val `noPreviousAnalysis`: LocalizedString get() = LocalizedString(
            key = StringKey("personal.noPreviousAnalysis", "personal_no_previous_analysis", emptyList(), 1),
        )
        val `correctMeasurement`: LocalizedString get() = LocalizedString(
            key = StringKey("personal.correctMeasurement", "personal_correct_measurement", emptyList(), 1),
        )
        fun `correctMetric`(`kind`: String): LocalizedString = LocalizedString(
            key = StringKey("personal.correctMetric", "personal_correct_metric", listOf("kind"), 1),
            arguments = listOf(`kind`),
        )
        val `year`: LocalizedString get() = LocalizedString(
            key = StringKey("personal.year", "personal_year", emptyList(), 1),
        )
        val `analysisPages`: LocalizedString get() = LocalizedString(
            key = StringKey("personal.analysisPages", "personal_analysis_pages", emptyList(), 1),
        )
        val `previousPeriod`: LocalizedString get() = LocalizedString(
            key = StringKey("personal.previousPeriod", "personal_previous_period", emptyList(), 1),
        )
        val `nextPeriod`: LocalizedString get() = LocalizedString(
            key = StringKey("personal.nextPeriod", "personal_next_period", emptyList(), 1),
        )
        object `BodyMeaning` {
            val `weight`: LocalizedString get() = LocalizedString(
                key = StringKey("personal.bodyMeaning.weight", "personal_body_meaning_weight", emptyList(), 1),
            )
            val `body_fat_percent`: LocalizedString get() = LocalizedString(
                key = StringKey("personal.bodyMeaning.body_fat_percent", "personal_body_meaning_body_fat_percent", emptyList(), 1),
            )
            val `body_fat_mass`: LocalizedString get() = LocalizedString(
                key = StringKey("personal.bodyMeaning.body_fat_mass", "personal_body_meaning_body_fat_mass", emptyList(), 1),
            )
            val `skeletal_muscle_mass`: LocalizedString get() = LocalizedString(
                key = StringKey("personal.bodyMeaning.skeletal_muscle_mass", "personal_body_meaning_skeletal_muscle_mass", emptyList(), 1),
            )
            val `fat_free_mass`: LocalizedString get() = LocalizedString(
                key = StringKey("personal.bodyMeaning.fat_free_mass", "personal_body_meaning_fat_free_mass", emptyList(), 1),
            )
            val `body_water`: LocalizedString get() = LocalizedString(
                key = StringKey("personal.bodyMeaning.body_water", "personal_body_meaning_body_water", emptyList(), 1),
            )
            val `body_protein`: LocalizedString get() = LocalizedString(
                key = StringKey("personal.bodyMeaning.body_protein", "personal_body_meaning_body_protein", emptyList(), 1),
            )
            val `body_minerals`: LocalizedString get() = LocalizedString(
                key = StringKey("personal.bodyMeaning.body_minerals", "personal_body_meaning_body_minerals", emptyList(), 1),
            )
            val `bmi`: LocalizedString get() = LocalizedString(
                key = StringKey("personal.bodyMeaning.bmi", "personal_body_meaning_bmi", emptyList(), 1),
            )
            val `basal_metabolic_rate`: LocalizedString get() = LocalizedString(
                key = StringKey("personal.bodyMeaning.basal_metabolic_rate", "personal_body_meaning_basal_metabolic_rate", emptyList(), 1),
            )
            val `visceral_fat_level`: LocalizedString get() = LocalizedString(
                key = StringKey("personal.bodyMeaning.visceral_fat_level", "personal_body_meaning_visceral_fat_level", emptyList(), 1),
            )
        }
        val `dayMetrics`: LocalizedString get() = LocalizedString(
            key = StringKey("personal.dayMetrics", "personal_day_metrics", emptyList(), 1),
        )
        val `noFood`: LocalizedString get() = LocalizedString(
            key = StringKey("personal.noFood", "personal_no_food", emptyList(), 1),
        )
        val `estimated`: LocalizedString get() = LocalizedString(
            key = StringKey("personal.estimated", "personal_estimated", emptyList(), 1),
        )
        val `more`: LocalizedString get() = LocalizedString(
            key = StringKey("personal.more", "personal_more", emptyList(), 1),
        )
        val `sources`: LocalizedString get() = LocalizedString(
            key = StringKey("personal.sources", "personal_sources", emptyList(), 1),
        )
        fun `sourcesSummary`(quantity: Double, `count`: String): LocalizedString = LocalizedString(
            key = StringKey("personal.sourcesSummary", "personal_sources_summary", listOf("count"), 4),
            arguments = listOf(`count`),
            quantity = quantity,
        )
        val `historyTitle`: LocalizedString get() = LocalizedString(
            key = StringKey("personal.historyTitle", "personal_history_title", emptyList(), 1),
        )
        val `kindFilter`: LocalizedString get() = LocalizedString(
            key = StringKey("personal.kindFilter", "personal_kind_filter", emptyList(), 1),
        )
        val `allKinds`: LocalizedString get() = LocalizedString(
            key = StringKey("personal.allKinds", "personal_all_kinds", emptyList(), 1),
        )
        val `period`: LocalizedString get() = LocalizedString(
            key = StringKey("personal.period", "personal_period", emptyList(), 1),
        )
        fun `days`(`count`: String): LocalizedString = LocalizedString(
            key = StringKey("personal.days", "personal_days", listOf("count"), 1),
            arguments = listOf(`count`),
        )
        fun `weightChart`(`count`: String): LocalizedString = LocalizedString(
            key = StringKey("personal.weightChart", "personal_weight_chart", listOf("count"), 1),
            arguments = listOf(`count`),
        )
        val `newWeighing`: LocalizedString get() = LocalizedString(
            key = StringKey("personal.newWeighing", "personal_new_weighing", emptyList(), 1),
        )
        val `correctLatest`: LocalizedString get() = LocalizedString(
            key = StringKey("personal.correctLatest", "personal_correct_latest", emptyList(), 1),
        )
        val `historyEmpty`: LocalizedString get() = LocalizedString(
            key = StringKey("personal.historyEmpty", "personal_history_empty", emptyList(), 1),
        )
        val `whereFrom`: LocalizedString get() = LocalizedString(
            key = StringKey("personal.whereFrom", "personal_where_from", emptyList(), 1),
        )
        val `manual`: LocalizedString get() = LocalizedString(
            key = StringKey("personal.manual", "personal_manual", emptyList(), 1),
        )
        val `manualText`: LocalizedString get() = LocalizedString(
            key = StringKey("personal.manualText", "personal_manual_text", emptyList(), 1),
        )
        fun `lastDelivery`(`when`: String): LocalizedString = LocalizedString(
            key = StringKey("personal.lastDelivery", "personal_last_delivery", listOf("when"), 1),
            arguments = listOf(`when`),
        )
        val `forSetup`: LocalizedString get() = LocalizedString(
            key = StringKey("personal.forSetup", "personal_for_setup", emptyList(), 1),
        )
        val `forSetupText`: LocalizedString get() = LocalizedString(
            key = StringKey("personal.forSetupText", "personal_for_setup_text", emptyList(), 1),
        )
        val `turnOn`: LocalizedString get() = LocalizedString(
            key = StringKey("personal.turnOn", "personal_turn_on", emptyList(), 1),
        )
        val `turnOff`: LocalizedString get() = LocalizedString(
            key = StringKey("personal.turnOff", "personal_turn_off", emptyList(), 1),
        )
        val `sourceOn`: LocalizedString get() = LocalizedString(
            key = StringKey("personal.sourceOn", "personal_source_on", emptyList(), 1),
        )
        val `sourceOff`: LocalizedString get() = LocalizedString(
            key = StringKey("personal.sourceOff", "personal_source_off", emptyList(), 1),
        )
        val `noImport`: LocalizedString get() = LocalizedString(
            key = StringKey("personal.noImport", "personal_no_import", emptyList(), 1),
        )
        val `register`: LocalizedString get() = LocalizedString(
            key = StringKey("personal.register", "personal_register", emptyList(), 1),
        )
        val `registerHint`: LocalizedString get() = LocalizedString(
            key = StringKey("personal.registerHint", "personal_register_hint", emptyList(), 1),
        )
        val `sourceName`: LocalizedString get() = LocalizedString(
            key = StringKey("personal.sourceName", "personal_source_name", emptyList(), 1),
        )
        val `sourceExample`: LocalizedString get() = LocalizedString(
            key = StringKey("personal.sourceExample", "personal_source_example", emptyList(), 1),
        )
        val `sourceKinds`: LocalizedString get() = LocalizedString(
            key = StringKey("personal.sourceKinds", "personal_source_kinds", emptyList(), 1),
        )
        val `registerButton`: LocalizedString get() = LocalizedString(
            key = StringKey("personal.registerButton", "personal_register_button", emptyList(), 1),
        )
        val `sourceNameRequired`: LocalizedString get() = LocalizedString(
            key = StringKey("personal.sourceNameRequired", "personal_source_name_required", emptyList(), 1),
        )
        val `kindsRequired`: LocalizedString get() = LocalizedString(
            key = StringKey("personal.kindsRequired", "personal_kinds_required", emptyList(), 1),
        )
        fun `registered`(`name`: String): LocalizedString = LocalizedString(
            key = StringKey("personal.registered", "personal_registered", listOf("name"), 1),
            arguments = listOf(`name`),
        )
        val `registeredText`: LocalizedString get() = LocalizedString(
            key = StringKey("personal.registeredText", "personal_registered_text", emptyList(), 1),
        )
        val `toSources`: LocalizedString get() = LocalizedString(
            key = StringKey("personal.toSources", "personal_to_sources", emptyList(), 1),
        )
        val `toMetrics`: LocalizedString get() = LocalizedString(
            key = StringKey("personal.toMetrics", "personal_to_metrics", emptyList(), 1),
        )
        val `preferredSource`: LocalizedString get() = LocalizedString(
            key = StringKey("personal.preferredSource", "personal_preferred_source", emptyList(), 1),
        )
        fun `targetOf`(`unit`: String): LocalizedString = LocalizedString(
            key = StringKey("personal.targetOf", "personal_target_of", listOf("unit"), 1),
            arguments = listOf(`unit`),
        )
        val `targetSaved`: LocalizedString get() = LocalizedString(
            key = StringKey("personal.targetSaved", "personal_target_saved", emptyList(), 1),
        )
        fun `was`(`value`: String): LocalizedString = LocalizedString(
            key = StringKey("personal.was", "personal_was", listOf("value"), 1),
            arguments = listOf(`value`),
        )
        object `Atlas` {
            val `title`: LocalizedString get() = LocalizedString(
                key = StringKey("personal.atlas.title", "personal_atlas_title", emptyList(), 1),
            )
            val `eyebrow`: LocalizedString get() = LocalizedString(
                key = StringKey("personal.atlas.eyebrow", "personal_atlas_eyebrow", emptyList(), 1),
            )
            val `open`: LocalizedString get() = LocalizedString(
                key = StringKey("personal.atlas.open", "personal_atlas_open", emptyList(), 1),
            )
            val `layers`: LocalizedString get() = LocalizedString(
                key = StringKey("personal.atlas.layers", "personal_atlas_layers", emptyList(), 1),
            )
            object `Character` {
                val `title`: LocalizedString get() = LocalizedString(
                    key = StringKey("personal.atlas.character.title", "personal_atlas_character_title", emptyList(), 1),
                )
                val `sculpture`: LocalizedString get() = LocalizedString(
                    key = StringKey("personal.atlas.character.sculpture", "personal_atlas_character_sculpture", emptyList(), 1),
                )
                val `spartan`: LocalizedString get() = LocalizedString(
                    key = StringKey("personal.atlas.character.spartan", "personal_atlas_character_spartan", emptyList(), 1),
                )
                val `maleOnly`: LocalizedString get() = LocalizedString(
                    key = StringKey("personal.atlas.character.maleOnly", "personal_atlas_character_male_only", emptyList(), 1),
                )
                val `savedIncompatible`: LocalizedString get() = LocalizedString(
                    key = StringKey("personal.atlas.character.savedIncompatible", "personal_atlas_character_saved_incompatible", emptyList(), 1),
                )
                val `loadFailed`: LocalizedString get() = LocalizedString(
                    key = StringKey("personal.atlas.character.loadFailed", "personal_atlas_character_load_failed", emptyList(), 1),
                )
                val `refreshFailed`: LocalizedString get() = LocalizedString(
                    key = StringKey("personal.atlas.character.refreshFailed", "personal_atlas_character_refresh_failed", emptyList(), 1),
                )
                val `saving`: LocalizedString get() = LocalizedString(
                    key = StringKey("personal.atlas.character.saving", "personal_atlas_character_saving", emptyList(), 1),
                )
            }
            object `View` {
                val `title`: LocalizedString get() = LocalizedString(
                    key = StringKey("personal.atlas.view.title", "personal_atlas_view_title", emptyList(), 1),
                )
                val `front`: LocalizedString get() = LocalizedString(
                    key = StringKey("personal.atlas.view.front", "personal_atlas_view_front", emptyList(), 1),
                )
                val `side`: LocalizedString get() = LocalizedString(
                    key = StringKey("personal.atlas.view.side", "personal_atlas_view_side", emptyList(), 1),
                )
                val `back`: LocalizedString get() = LocalizedString(
                    key = StringKey("personal.atlas.view.back", "personal_atlas_view_back", emptyList(), 1),
                )
                val `three-quarter`: LocalizedString get() = LocalizedString(
                    key = StringKey("personal.atlas.view.three-quarter", "personal_atlas_view_three_quarter", emptyList(), 1),
                )
            }
            val `selectAnalysis`: LocalizedString get() = LocalizedString(
                key = StringKey("personal.atlas.selectAnalysis", "personal_atlas_select_analysis", emptyList(), 1),
            )
            val `older`: LocalizedString get() = LocalizedString(
                key = StringKey("personal.atlas.older", "personal_atlas_older", emptyList(), 1),
            )
            val `newer`: LocalizedString get() = LocalizedString(
                key = StringKey("personal.atlas.newer", "personal_atlas_newer", emptyList(), 1),
            )
            fun `scanNumber`(`number`: String): LocalizedString = LocalizedString(
                key = StringKey("personal.atlas.scanNumber", "personal_atlas_scan_number", listOf("number"), 1),
                arguments = listOf(`number`),
            )
            val `truncated`: LocalizedString get() = LocalizedString(
                key = StringKey("personal.atlas.truncated", "personal_atlas_truncated", emptyList(), 1),
            )
            val `schematic`: LocalizedString get() = LocalizedString(
                key = StringKey("personal.atlas.schematic", "personal_atlas_schematic", emptyList(), 1),
            )
            object `Layer` {
                val `composition`: LocalizedString get() = LocalizedString(
                    key = StringKey("personal.atlas.layer.composition", "personal_atlas_layer_composition", emptyList(), 1),
                )
                val `muscle`: LocalizedString get() = LocalizedString(
                    key = StringKey("personal.atlas.layer.muscle", "personal_atlas_layer_muscle", emptyList(), 1),
                )
                val `fat`: LocalizedString get() = LocalizedString(
                    key = StringKey("personal.atlas.layer.fat", "personal_atlas_layer_fat", emptyList(), 1),
                )
                val `water`: LocalizedString get() = LocalizedString(
                    key = StringKey("personal.atlas.layer.water", "personal_atlas_layer_water", emptyList(), 1),
                )
            }
            object `Note` {
                val `composition`: LocalizedString get() = LocalizedString(
                    key = StringKey("personal.atlas.note.composition", "personal_atlas_note_composition", emptyList(), 1),
                )
                val `muscle`: LocalizedString get() = LocalizedString(
                    key = StringKey("personal.atlas.note.muscle", "personal_atlas_note_muscle", emptyList(), 1),
                )
                val `fat`: LocalizedString get() = LocalizedString(
                    key = StringKey("personal.atlas.note.fat", "personal_atlas_note_fat", emptyList(), 1),
                )
                val `water`: LocalizedString get() = LocalizedString(
                    key = StringKey("personal.atlas.note.water", "personal_atlas_note_water", emptyList(), 1),
                )
            }
            val `genderMissing`: LocalizedString get() = LocalizedString(
                key = StringKey("personal.atlas.genderMissing", "personal_atlas_gender_missing", emptyList(), 1),
            )
            val `heightMissing`: LocalizedString get() = LocalizedString(
                key = StringKey("personal.atlas.heightMissing", "personal_atlas_height_missing", emptyList(), 1),
            )
            val `specifyGender`: LocalizedString get() = LocalizedString(
                key = StringKey("personal.atlas.specifyGender", "personal_atlas_specify_gender", emptyList(), 1),
            )
            val `specifyHeight`: LocalizedString get() = LocalizedString(
                key = StringKey("personal.atlas.specifyHeight", "personal_atlas_specify_height", emptyList(), 1),
            )
            val `profileLoading`: LocalizedString get() = LocalizedString(
                key = StringKey("personal.atlas.profileLoading", "personal_atlas_profile_loading", emptyList(), 1),
            )
            val `profileLoadFailed`: LocalizedString get() = LocalizedString(
                key = StringKey("personal.atlas.profileLoadFailed", "personal_atlas_profile_load_failed", emptyList(), 1),
            )
            val `compare`: LocalizedString get() = LocalizedString(
                key = StringKey("personal.atlas.compare", "personal_atlas_compare", emptyList(), 1),
            )
            val `closeComparison`: LocalizedString get() = LocalizedString(
                key = StringKey("personal.atlas.closeComparison", "personal_atlas_close_comparison", emptyList(), 1),
            )
            val `compareUnavailable`: LocalizedString get() = LocalizedString(
                key = StringKey("personal.atlas.compareUnavailable", "personal_atlas_compare_unavailable", emptyList(), 1),
            )
            val `compareFrom`: LocalizedString get() = LocalizedString(
                key = StringKey("personal.atlas.compareFrom", "personal_atlas_compare_from", emptyList(), 1),
            )
            val `before`: LocalizedString get() = LocalizedString(
                key = StringKey("personal.atlas.before", "personal_atlas_before", emptyList(), 1),
            )
            val `selected`: LocalizedString get() = LocalizedString(
                key = StringKey("personal.atlas.selected", "personal_atlas_selected", emptyList(), 1),
            )
            fun `wasValue`(`value`: String): LocalizedString = LocalizedString(
                key = StringKey("personal.atlas.wasValue", "personal_atlas_was_value", listOf("value"), 1),
                arguments = listOf(`value`),
            )
            val `scrub`: LocalizedString get() = LocalizedString(
                key = StringKey("personal.atlas.scrub", "personal_atlas_scrub", emptyList(), 1),
            )
            fun `scrubPosition`(`percent`: String): LocalizedString = LocalizedString(
                key = StringKey("personal.atlas.scrubPosition", "personal_atlas_scrub_position", listOf("percent"), 1),
                arguments = listOf(`percent`),
            )
            fun `heldAxes`(`axes`: String): LocalizedString = LocalizedString(
                key = StringKey("personal.atlas.heldAxes", "personal_atlas_held_axes", listOf("axes"), 1),
                arguments = listOf(`axes`),
            )
            object `Calibration` {
                object `Axis` {
                    val `fat`: LocalizedString get() = LocalizedString(
                        key = StringKey("personal.atlas.calibration.axis.fat", "personal_atlas_calibration_axis_fat", emptyList(), 1),
                    )
                    val `muscle`: LocalizedString get() = LocalizedString(
                        key = StringKey("personal.atlas.calibration.axis.muscle", "personal_atlas_calibration_axis_muscle", emptyList(), 1),
                    )
                }
                object `Direction` {
                    val `below`: LocalizedString get() = LocalizedString(
                        key = StringKey("personal.atlas.calibration.direction.below", "personal_atlas_calibration_direction_below", emptyList(), 1),
                    )
                    val `above`: LocalizedString get() = LocalizedString(
                        key = StringKey("personal.atlas.calibration.direction.above", "personal_atlas_calibration_direction_above", emptyList(), 1),
                    )
                }
                fun `axisBoundary`(`axis`: String, `direction`: String): LocalizedString = LocalizedString(
                    key = StringKey("personal.atlas.calibration.axisBoundary", "personal_atlas_calibration_axis_boundary", listOf("axis", "direction"), 1),
                    arguments = listOf(`axis`, `direction`),
                )
                fun `hint`(`boundaries`: String): LocalizedString = LocalizedString(
                    key = StringKey("personal.atlas.calibration.hint", "personal_atlas_calibration_hint", listOf("boundaries"), 1),
                    arguments = listOf(`boundaries`),
                )
                fun `comparisonHint`(`boundaries`: String): LocalizedString = LocalizedString(
                    key = StringKey("personal.atlas.calibration.comparisonHint", "personal_atlas_calibration_comparison_hint", listOf("boundaries"), 1),
                    arguments = listOf(`boundaries`),
                )
            }
            object `Axis` {
                val `fat`: LocalizedString get() = LocalizedString(
                    key = StringKey("personal.atlas.axis.fat", "personal_atlas_axis_fat", emptyList(), 1),
                )
                val `muscle`: LocalizedString get() = LocalizedString(
                    key = StringKey("personal.atlas.axis.muscle", "personal_atlas_axis_muscle", emptyList(), 1),
                )
            }
            val `staticView`: LocalizedString get() = LocalizedString(
                key = StringKey("personal.atlas.staticView", "personal_atlas_static_view", emptyList(), 1),
            )
            object `Motion` {
                val `idle`: LocalizedString get() = LocalizedString(
                    key = StringKey("personal.atlas.motion.idle", "personal_atlas_motion_idle", emptyList(), 1),
                )
                val `standing`: LocalizedString get() = LocalizedString(
                    key = StringKey("personal.atlas.motion.standing", "personal_atlas_motion_standing", emptyList(), 1),
                )
                val `paused`: LocalizedString get() = LocalizedString(
                    key = StringKey("personal.atlas.motion.paused", "personal_atlas_motion_paused", emptyList(), 1),
                )
                val `comparing`: LocalizedString get() = LocalizedString(
                    key = StringKey("personal.atlas.motion.comparing", "personal_atlas_motion_comparing", emptyList(), 1),
                )
                val `reduced`: LocalizedString get() = LocalizedString(
                    key = StringKey("personal.atlas.motion.reduced", "personal_atlas_motion_reduced", emptyList(), 1),
                )
                val `unavailable`: LocalizedString get() = LocalizedString(
                    key = StringKey("personal.atlas.motion.unavailable", "personal_atlas_motion_unavailable", emptyList(), 1),
                )
                val `loading`: LocalizedString get() = LocalizedString(
                    key = StringKey("personal.atlas.motion.loading", "personal_atlas_motion_loading", emptyList(), 1),
                )
            }
        }
        val `gender`: LocalizedString get() = LocalizedString(
            key = StringKey("personal.gender", "personal_gender", emptyList(), 1),
        )
        val `genderUnset`: LocalizedString get() = LocalizedString(
            key = StringKey("personal.genderUnset", "personal_gender_unset", emptyList(), 1),
        )
        val `genderMale`: LocalizedString get() = LocalizedString(
            key = StringKey("personal.genderMale", "personal_gender_male", emptyList(), 1),
        )
        val `genderFemale`: LocalizedString get() = LocalizedString(
            key = StringKey("personal.genderFemale", "personal_gender_female", emptyList(), 1),
        )
        val `genderHint`: LocalizedString get() = LocalizedString(
            key = StringKey("personal.genderHint", "personal_gender_hint", emptyList(), 1),
        )
        val `bodyProfileTitle`: LocalizedString get() = LocalizedString(
            key = StringKey("personal.bodyProfileTitle", "personal_body_profile_title", emptyList(), 1),
        )
        val `bodyProfileHint`: LocalizedString get() = LocalizedString(
            key = StringKey("personal.bodyProfileHint", "personal_body_profile_hint", emptyList(), 1),
        )
        val `birthHint`: LocalizedString get() = LocalizedString(
            key = StringKey("personal.birthHint", "personal_birth_hint", emptyList(), 1),
        )
        val `age`: LocalizedString get() = LocalizedString(
            key = StringKey("personal.age", "personal_age", emptyList(), 1),
        )
        fun `ageYears`(quantity: Double, `count`: String): LocalizedString = LocalizedString(
            key = StringKey("personal.ageYears", "personal_age_years", listOf("count"), 4),
            arguments = listOf(`count`),
            quantity = quantity,
        )
        val `ageHint`: LocalizedString get() = LocalizedString(
            key = StringKey("personal.ageHint", "personal_age_hint", emptyList(), 1),
        )
        val `resetProfile`: LocalizedString get() = LocalizedString(
            key = StringKey("personal.resetProfile", "personal_reset_profile", emptyList(), 1),
        )
        val `profileReturn`: LocalizedString get() = LocalizedString(
            key = StringKey("personal.profileReturn", "personal_profile_return", emptyList(), 1),
        )
        object `SleepAnalysis` {
            fun `recognized`(`duration`: String): LocalizedString = LocalizedString(
                key = StringKey("personal.sleepAnalysis.recognized", "personal_sleep_analysis_recognized", listOf("duration"), 1),
                arguments = listOf(`duration`),
            )
            fun `unclassified`(`duration`: String): LocalizedString = LocalizedString(
                key = StringKey("personal.sleepAnalysis.unclassified", "personal_sleep_analysis_unclassified", listOf("duration"), 1),
                arguments = listOf(`duration`),
            )
            val `reportConflict`: LocalizedString get() = LocalizedString(
                key = StringKey("personal.sleepAnalysis.reportConflict", "personal_sleep_analysis_report_conflict", emptyList(), 1),
            )
            fun `forecast`(`duration`: String): LocalizedString = LocalizedString(
                key = StringKey("personal.sleepAnalysis.forecast", "personal_sleep_analysis_forecast", listOf("duration"), 1),
                arguments = listOf(`duration`),
            )
            fun `conflictSubset`(`duration`: String): LocalizedString = LocalizedString(
                key = StringKey("personal.sleepAnalysis.conflictSubset", "personal_sleep_analysis_conflict_subset", listOf("duration"), 1),
                arguments = listOf(`duration`),
            )
            fun `window`(`duration`: String): LocalizedString = LocalizedString(
                key = StringKey("personal.sleepAnalysis.window", "personal_sleep_analysis_window", listOf("duration"), 1),
                arguments = listOf(`duration`),
            )
            object `Legend` {
                val `sleep`: LocalizedString get() = LocalizedString(
                    key = StringKey("personal.sleepAnalysis.legend.sleep", "personal_sleep_analysis_legend_sleep", emptyList(), 1),
                )
                val `awake`: LocalizedString get() = LocalizedString(
                    key = StringKey("personal.sleepAnalysis.legend.awake", "personal_sleep_analysis_legend_awake", emptyList(), 1),
                )
                val `unknown`: LocalizedString get() = LocalizedString(
                    key = StringKey("personal.sleepAnalysis.legend.unknown", "personal_sleep_analysis_legend_unknown", emptyList(), 1),
                )
                val `conflict`: LocalizedString get() = LocalizedString(
                    key = StringKey("personal.sleepAnalysis.legend.conflict", "personal_sleep_analysis_legend_conflict", emptyList(), 1),
                )
            }
            fun `coverage`(`percent`: String): LocalizedString = LocalizedString(
                key = StringKey("personal.sleepAnalysis.coverage", "personal_sleep_analysis_coverage", listOf("percent"), 1),
                arguments = listOf(`percent`),
            )
            fun `bounds`(`min`: String, `max`: String): LocalizedString = LocalizedString(
                key = StringKey("personal.sleepAnalysis.bounds", "personal_sleep_analysis_bounds", listOf("min", "max"), 1),
                arguments = listOf(`min`, `max`),
            )
            fun `forecastBasis`(`count`: String, `duration`: String): LocalizedString = LocalizedString(
                key = StringKey("personal.sleepAnalysis.forecastBasis", "personal_sleep_analysis_forecast_basis", listOf("count", "duration"), 1),
                arguments = listOf(`count`, `duration`),
            )
            val `evidence`: LocalizedString get() = LocalizedString(
                key = StringKey("personal.sleepAnalysis.evidence", "personal_sleep_analysis_evidence", emptyList(), 1),
            )
            fun `validation`(`count`: String, `mae`: String, `p90`: String): LocalizedString = LocalizedString(
                key = StringKey("personal.sleepAnalysis.validation", "personal_sleep_analysis_validation", listOf("count", "mae", "p90"), 1),
                arguments = listOf(`count`, `mae`, `p90`),
            )
            fun `originalTotal`(`source`: String, `duration`: String): LocalizedString = LocalizedString(
                key = StringKey("personal.sleepAnalysis.originalTotal", "personal_sleep_analysis_original_total", listOf("source", "duration"), 1),
                arguments = listOf(`source`, `duration`),
            )
            object `Reason` {
                val `insufficient_history`: LocalizedString get() = LocalizedString(
                    key = StringKey("personal.sleepAnalysis.reason.insufficient_history", "personal_sleep_analysis_reason_insufficient_history", emptyList(), 1),
                )
                val `insufficient_validation`: LocalizedString get() = LocalizedString(
                    key = StringKey("personal.sleepAnalysis.reason.insufficient_validation", "personal_sleep_analysis_reason_insufficient_validation", emptyList(), 1),
                )
                val `validation_failed`: LocalizedString get() = LocalizedString(
                    key = StringKey("personal.sleepAnalysis.reason.validation_failed", "personal_sleep_analysis_reason_validation_failed", emptyList(), 1),
                )
                val `unsupported_report`: LocalizedString get() = LocalizedString(
                    key = StringKey("personal.sleepAnalysis.reason.unsupported_report", "personal_sleep_analysis_reason_unsupported_report", emptyList(), 1),
                )
                val `unsupported_quality`: LocalizedString get() = LocalizedString(
                    key = StringKey("personal.sleepAnalysis.reason.unsupported_quality", "personal_sleep_analysis_reason_unsupported_quality", emptyList(), 1),
                )
                val `unsupported_gap`: LocalizedString get() = LocalizedString(
                    key = StringKey("personal.sleepAnalysis.reason.unsupported_gap", "personal_sleep_analysis_reason_unsupported_gap", emptyList(), 1),
                )
                val `conflicting_evidence`: LocalizedString get() = LocalizedString(
                    key = StringKey("personal.sleepAnalysis.reason.conflicting_evidence", "personal_sleep_analysis_reason_conflicting_evidence", emptyList(), 1),
                )
                val `duration_mismatch`: LocalizedString get() = LocalizedString(
                    key = StringKey("personal.sleepAnalysis.reason.duration_mismatch", "personal_sleep_analysis_reason_duration_mismatch", emptyList(), 1),
                )
                val `sleep_taxonomy_unverified`: LocalizedString get() = LocalizedString(
                    key = StringKey("personal.sleepAnalysis.reason.sleep_taxonomy_unverified", "personal_sleep_analysis_reason_sleep_taxonomy_unverified", emptyList(), 1),
                )
                val `protocol_unverified`: LocalizedString get() = LocalizedString(
                    key = StringKey("personal.sleepAnalysis.reason.protocol_unverified", "personal_sleep_analysis_reason_protocol_unverified", emptyList(), 1),
                )
                val `estimated_stages`: LocalizedString get() = LocalizedString(
                    key = StringKey("personal.sleepAnalysis.reason.estimated_stages", "personal_sleep_analysis_reason_estimated_stages", emptyList(), 1),
                )
                val `reported`: LocalizedString get() = LocalizedString(
                    key = StringKey("personal.sleepAnalysis.reason.reported", "personal_sleep_analysis_reason_reported", emptyList(), 1),
                )
                val `agreement`: LocalizedString get() = LocalizedString(
                    key = StringKey("personal.sleepAnalysis.reason.agreement", "personal_sleep_analysis_reason_agreement", emptyList(), 1),
                )
                val `gap_filled`: LocalizedString get() = LocalizedString(
                    key = StringKey("personal.sleepAnalysis.reason.gap_filled", "personal_sleep_analysis_reason_gap_filled", emptyList(), 1),
                )
                val `source_priority`: LocalizedString get() = LocalizedString(
                    key = StringKey("personal.sleepAnalysis.reason.source_priority", "personal_sleep_analysis_reason_source_priority", emptyList(), 1),
                )
                val `state_disagreement`: LocalizedString get() = LocalizedString(
                    key = StringKey("personal.sleepAnalysis.reason.state_disagreement", "personal_sleep_analysis_reason_state_disagreement", emptyList(), 1),
                )
                val `stage_disagreement`: LocalizedString get() = LocalizedString(
                    key = StringKey("personal.sleepAnalysis.reason.stage_disagreement", "personal_sleep_analysis_reason_stage_disagreement", emptyList(), 1),
                )
                val `unclassified`: LocalizedString get() = LocalizedString(
                    key = StringKey("personal.sleepAnalysis.reason.unclassified", "personal_sleep_analysis_reason_unclassified", emptyList(), 1),
                )
                val `complete_report`: LocalizedString get() = LocalizedString(
                    key = StringKey("personal.sleepAnalysis.reason.complete_report", "personal_sleep_analysis_reason_complete_report", emptyList(), 1),
                )
            }
        }
        object `Dashboard` {
            val `bodySubtitle`: LocalizedString get() = LocalizedString(
                key = StringKey("personal.dashboard.bodySubtitle", "personal_dashboard_body_subtitle", emptyList(), 1),
            )
            val `allBodyMetrics`: LocalizedString get() = LocalizedString(
                key = StringKey("personal.dashboard.allBodyMetrics", "personal_dashboard_all_body_metrics", emptyList(), 1),
            )
            val `previousAnalysis`: LocalizedString get() = LocalizedString(
                key = StringKey("personal.dashboard.previousAnalysis", "personal_dashboard_previous_analysis", emptyList(), 1),
            )
            val `latestAnalysis`: LocalizedString get() = LocalizedString(
                key = StringKey("personal.dashboard.latestAnalysis", "personal_dashboard_latest_analysis", emptyList(), 1),
            )
            val `skeletalMuscle`: LocalizedString get() = LocalizedString(
                key = StringKey("personal.dashboard.skeletalMuscle", "personal_dashboard_skeletal_muscle", emptyList(), 1),
            )
            object `Change` {
                fun `body_fat_percent`(`value`: String): LocalizedString = LocalizedString(
                    key = StringKey("personal.dashboard.change.body_fat_percent", "personal_dashboard_change_body_fat_percent", listOf("value"), 1),
                    arguments = listOf(`value`),
                )
                fun `skeletal_muscle_mass`(`value`: String): LocalizedString = LocalizedString(
                    key = StringKey("personal.dashboard.change.skeletal_muscle_mass", "personal_dashboard_change_skeletal_muscle_mass", listOf("value"), 1),
                    arguments = listOf(`value`),
                )
            }
            fun `sourceRecords`(quantity: Double, `count`: String): LocalizedString = LocalizedString(
                key = StringKey("personal.dashboard.sourceRecords", "personal_dashboard_source_records", listOf("count"), 3),
                arguments = listOf(`count`),
                quantity = quantity,
            )
            val `noDeviceAnalysis`: LocalizedString get() = LocalizedString(
                key = StringKey("personal.dashboard.noDeviceAnalysis", "personal_dashboard_no_device_analysis", emptyList(), 1),
            )
            val `separateMeasurements`: LocalizedString get() = LocalizedString(
                key = StringKey("personal.dashboard.separateMeasurements", "personal_dashboard_separate_measurements", emptyList(), 1),
            )
        }
    }
    object `Access` {
        val `readOnly`: LocalizedString get() = LocalizedString(
            key = StringKey("access.readOnly", "access_read_only", emptyList(), 1),
        )
        val `readOnlyText`: LocalizedString get() = LocalizedString(
            key = StringKey("access.readOnlyText", "access_read_only_text", emptyList(), 1),
        )
        object `Section` {
            val `personal`: LocalizedString get() = LocalizedString(
                key = StringKey("access.section.personal", "access_section_personal", emptyList(), 1),
            )
            val `nutrition`: LocalizedString get() = LocalizedString(
                key = StringKey("access.section.nutrition", "access_section_nutrition", emptyList(), 1),
            )
        }
        object `SectionText` {
            val `personal`: LocalizedString get() = LocalizedString(
                key = StringKey("access.sectionText.personal", "access_section_text_personal", emptyList(), 1),
            )
            val `nutrition`: LocalizedString get() = LocalizedString(
                key = StringKey("access.sectionText.nutrition", "access_section_text_nutrition", emptyList(), 1),
            )
        }
        object `Permission` {
            val `summary`: LocalizedString get() = LocalizedString(
                key = StringKey("access.permission.summary", "access_permission_summary", emptyList(), 1),
            )
            val `detail`: LocalizedString get() = LocalizedString(
                key = StringKey("access.permission.detail", "access_permission_detail", emptyList(), 1),
            )
        }
        object `Preview` {
            object `Personal` {
                val `summary`: LocalizedString get() = LocalizedString(
                    key = StringKey("access.preview.personal.summary", "access_preview_personal_summary", emptyList(), 1),
                )
                val `detail`: LocalizedString get() = LocalizedString(
                    key = StringKey("access.preview.personal.detail", "access_preview_personal_detail", emptyList(), 1),
                )
            }
            object `Nutrition` {
                val `summary`: LocalizedString get() = LocalizedString(
                    key = StringKey("access.preview.nutrition.summary", "access_preview_nutrition_summary", emptyList(), 1),
                )
                val `detail`: LocalizedString get() = LocalizedString(
                    key = StringKey("access.preview.nutrition.detail", "access_preview_nutrition_detail", emptyList(), 1),
                )
            }
        }
        val `nobody`: LocalizedString get() = LocalizedString(
            key = StringKey("access.nobody", "access_nobody", emptyList(), 1),
        )
        val `open`: LocalizedString get() = LocalizedString(
            key = StringKey("access.open", "access_open", emptyList(), 1),
        )
        val `openTitle`: LocalizedString get() = LocalizedString(
            key = StringKey("access.openTitle", "access_open_title", emptyList(), 1),
        )
        val `checkTitle`: LocalizedString get() = LocalizedString(
            key = StringKey("access.checkTitle", "access_check_title", emptyList(), 1),
        )
        val `login`: LocalizedString get() = LocalizedString(
            key = StringKey("access.login", "access_login", emptyList(), 1),
        )
        val `loginExample`: LocalizedString get() = LocalizedString(
            key = StringKey("access.loginExample", "access_login_example", emptyList(), 1),
        )
        val `loginHint`: LocalizedString get() = LocalizedString(
            key = StringKey("access.loginHint", "access_login_hint", emptyList(), 1),
        )
        val `loginRequired`: LocalizedString get() = LocalizedString(
            key = StringKey("access.loginRequired", "access_login_required", emptyList(), 1),
        )
        val `notFound`: LocalizedString get() = LocalizedString(
            key = StringKey("access.notFound", "access_not_found", emptyList(), 1),
        )
        val `notYourself`: LocalizedString get() = LocalizedString(
            key = StringKey("access.notYourself", "access_not_yourself", emptyList(), 1),
        )
        val `what`: LocalizedString get() = LocalizedString(
            key = StringKey("access.what", "access_what", emptyList(), 1),
        )
        fun `willSee`(`name`: String, `login`: String, `section`: String): LocalizedString = LocalizedString(
            key = StringKey("access.willSee", "access_will_see", listOf("name", "login", "section"), 1),
            arguments = listOf(`name`, `login`, `section`),
        )
        val `cannot`: LocalizedString get() = LocalizedString(
            key = StringKey("access.cannot", "access_cannot", emptyList(), 1),
        )
        val `confirm`: LocalizedString get() = LocalizedString(
            key = StringKey("access.confirm", "access_confirm", emptyList(), 1),
        )
        fun `opened`(`name`: String): LocalizedString = LocalizedString(
            key = StringKey("access.opened", "access_opened", listOf("name"), 1),
            arguments = listOf(`name`),
        )
        val `changed`: LocalizedString get() = LocalizedString(
            key = StringKey("access.changed", "access_changed", emptyList(), 1),
        )
        val `revoke`: LocalizedString get() = LocalizedString(
            key = StringKey("access.revoke", "access_revoke", emptyList(), 1),
        )
        val `revokeTitle`: LocalizedString get() = LocalizedString(
            key = StringKey("access.revokeTitle", "access_revoke_title", emptyList(), 1),
        )
        fun `revokeText`(`name`: String, `section`: String): LocalizedString = LocalizedString(
            key = StringKey("access.revokeText", "access_revoke_text", listOf("name", "section"), 1),
            arguments = listOf(`name`, `section`),
        )
        fun `revoked`(`name`: String): LocalizedString = LocalizedString(
            key = StringKey("access.revoked", "access_revoked", listOf("name"), 1),
            arguments = listOf(`name`),
        )
    }
    object `Sharing` {
        val `title`: LocalizedString get() = LocalizedString(
            key = StringKey("sharing.title", "sharing_title", emptyList(), 1),
        )
        val `mine`: LocalizedString get() = LocalizedString(
            key = StringKey("sharing.mine", "sharing_mine", emptyList(), 1),
        )
        val `mineText`: LocalizedString get() = LocalizedString(
            key = StringKey("sharing.mineText", "sharing_mine_text", emptyList(), 1),
        )
        val `spaces`: LocalizedString get() = LocalizedString(
            key = StringKey("sharing.spaces", "sharing_spaces", emptyList(), 1),
        )
        val `spacesText`: LocalizedString get() = LocalizedString(
            key = StringKey("sharing.spacesText", "sharing_spaces_text", emptyList(), 1),
        )
        val `spacesEmpty`: LocalizedString get() = LocalizedString(
            key = StringKey("sharing.spacesEmpty", "sharing_spaces_empty", emptyList(), 1),
        )
        val `membersLink`: LocalizedString get() = LocalizedString(
            key = StringKey("sharing.membersLink", "sharing_members_link", emptyList(), 1),
        )
        val `lists`: LocalizedString get() = LocalizedString(
            key = StringKey("sharing.lists", "sharing_lists", emptyList(), 1),
        )
        val `listsText`: LocalizedString get() = LocalizedString(
            key = StringKey("sharing.listsText", "sharing_lists_text", emptyList(), 1),
        )
        val `listsEmpty`: LocalizedString get() = LocalizedString(
            key = StringKey("sharing.listsEmpty", "sharing_lists_empty", emptyList(), 1),
        )
        val `people`: LocalizedString get() = LocalizedString(
            key = StringKey("sharing.people", "sharing_people", emptyList(), 1),
        )
        fun `peopleCount`(`friends`: String, `incoming`: String, `blocked`: String): LocalizedString = LocalizedString(
            key = StringKey("sharing.peopleCount", "sharing_people_count", listOf("friends", "incoming", "blocked"), 1),
            arguments = listOf(`friends`, `incoming`, `blocked`),
        )
        val `blockNote`: LocalizedString get() = LocalizedString(
            key = StringKey("sharing.blockNote", "sharing_block_note", emptyList(), 1),
        )
    }
    object `Shared` {
        val `title`: LocalizedString get() = LocalizedString(
            key = StringKey("shared.title", "shared_title", emptyList(), 1),
        )
        fun `of`(`name`: String): LocalizedString = LocalizedString(
            key = StringKey("shared.of", "shared_of", listOf("name"), 1),
            arguments = listOf(`name`),
        )
        val `empty`: LocalizedString get() = LocalizedString(
            key = StringKey("shared.empty", "shared_empty", emptyList(), 1),
        )
        val `emptyText`: LocalizedString get() = LocalizedString(
            key = StringKey("shared.emptyText", "shared_empty_text", emptyList(), 1),
        )
        object `Level` {
            val `summary`: LocalizedString get() = LocalizedString(
                key = StringKey("shared.level.summary", "shared_level_summary", emptyList(), 1),
            )
            val `detail`: LocalizedString get() = LocalizedString(
                key = StringKey("shared.level.detail", "shared_level_detail", emptyList(), 1),
            )
            val `manage`: LocalizedString get() = LocalizedString(
                key = StringKey("shared.level.manage", "shared_level_manage", emptyList(), 1),
            )
        }
        val `revoked`: LocalizedString get() = LocalizedString(
            key = StringKey("shared.revoked", "shared_revoked", emptyList(), 1),
        )
        val `revokedText`: LocalizedString get() = LocalizedString(
            key = StringKey("shared.revokedText", "shared_revoked_text", emptyList(), 1),
        )
        val `eaten`: LocalizedString get() = LocalizedString(
            key = StringKey("shared.eaten", "shared_eaten", emptyList(), 1),
        )
        fun `goal`(`value`: String): LocalizedString = LocalizedString(
            key = StringKey("shared.goal", "shared_goal", listOf("value"), 1),
            arguments = listOf(`value`),
        )
        val `noEntries`: LocalizedString get() = LocalizedString(
            key = StringKey("shared.noEntries", "shared_no_entries", emptyList(), 1),
        )
        val `readOnly`: LocalizedString get() = LocalizedString(
            key = StringKey("shared.readOnly", "shared_read_only", emptyList(), 1),
        )
    }
    object `Capabilities` {
        val `title`: LocalizedString get() = LocalizedString(
            key = StringKey("capabilities.title", "capabilities_title", emptyList(), 1),
        )
        object `Screen` {
            val `title`: LocalizedString get() = LocalizedString(
                key = StringKey("capabilities.screen.title", "capabilities_screen_title", emptyList(), 1),
            )
            val `insets`: LocalizedString get() = LocalizedString(
                key = StringKey("capabilities.screen.insets", "capabilities_screen_insets", emptyList(), 1),
            )
            val `viewport`: LocalizedString get() = LocalizedString(
                key = StringKey("capabilities.screen.viewport", "capabilities_screen_viewport", emptyList(), 1),
            )
            val `visual`: LocalizedString get() = LocalizedString(
                key = StringKey("capabilities.screen.visual", "capabilities_screen_visual", emptyList(), 1),
            )
            val `scheme`: LocalizedString get() = LocalizedString(
                key = StringKey("capabilities.screen.scheme", "capabilities_screen_scheme", emptyList(), 1),
            )
            val `theme`: LocalizedString get() = LocalizedString(
                key = StringKey("capabilities.screen.theme", "capabilities_screen_theme", emptyList(), 1),
            )
            val `shell`: LocalizedString get() = LocalizedString(
                key = StringKey("capabilities.screen.shell", "capabilities_screen_shell", emptyList(), 1),
            )
            val `browser`: LocalizedString get() = LocalizedString(
                key = StringKey("capabilities.screen.browser", "capabilities_screen_browser", emptyList(), 1),
            )
        }
        val `yours`: LocalizedString get() = LocalizedString(
            key = StringKey("capabilities.yours", "capabilities_yours", emptyList(), 1),
        )
        object `Feature` {
            val `assistant`: LocalizedString get() = LocalizedString(
                key = StringKey("capabilities.feature.assistant", "capabilities_feature_assistant", emptyList(), 1),
            )
            val `nutrition`: LocalizedString get() = LocalizedString(
                key = StringKey("capabilities.feature.nutrition", "capabilities_feature_nutrition", emptyList(), 1),
            )
            val `tasks`: LocalizedString get() = LocalizedString(
                key = StringKey("capabilities.feature.tasks", "capabilities_feature_tasks", emptyList(), 1),
            )
            val `timers`: LocalizedString get() = LocalizedString(
                key = StringKey("capabilities.feature.timers", "capabilities_feature_timers", emptyList(), 1),
            )
            val `personal`: LocalizedString get() = LocalizedString(
                key = StringKey("capabilities.feature.personal", "capabilities_feature_personal", emptyList(), 1),
            )
            val `challenges`: LocalizedString get() = LocalizedString(
                key = StringKey("capabilities.feature.challenges", "capabilities_feature_challenges", emptyList(), 1),
            )
        }
        object `FeatureText` {
            val `assistant`: LocalizedString get() = LocalizedString(
                key = StringKey("capabilities.featureText.assistant", "capabilities_feature_text_assistant", emptyList(), 1),
            )
            val `nutrition`: LocalizedString get() = LocalizedString(
                key = StringKey("capabilities.featureText.nutrition", "capabilities_feature_text_nutrition", emptyList(), 1),
            )
            val `tasks`: LocalizedString get() = LocalizedString(
                key = StringKey("capabilities.featureText.tasks", "capabilities_feature_text_tasks", emptyList(), 1),
            )
            val `timers`: LocalizedString get() = LocalizedString(
                key = StringKey("capabilities.featureText.timers", "capabilities_feature_text_timers", emptyList(), 1),
            )
            val `personal`: LocalizedString get() = LocalizedString(
                key = StringKey("capabilities.featureText.personal", "capabilities_feature_text_personal", emptyList(), 1),
            )
            val `challenges`: LocalizedString get() = LocalizedString(
                key = StringKey("capabilities.featureText.challenges", "capabilities_feature_text_challenges", emptyList(), 1),
            )
        }
        val `available`: LocalizedString get() = LocalizedString(
            key = StringKey("capabilities.available", "capabilities_available", emptyList(), 1),
        )
        val `notAvailable`: LocalizedString get() = LocalizedString(
            key = StringKey("capabilities.notAvailable", "capabilities_not_available", emptyList(), 1),
        )
        val `managed`: LocalizedString get() = LocalizedString(
            key = StringKey("capabilities.managed", "capabilities_managed", emptyList(), 1),
        )
        val `managedText`: LocalizedString get() = LocalizedString(
            key = StringKey("capabilities.managedText", "capabilities_managed_text", emptyList(), 1),
        )
        val `installation`: LocalizedString get() = LocalizedString(
            key = StringKey("capabilities.installation", "capabilities_installation", emptyList(), 1),
        )
        fun `checked`(`time`: String): LocalizedString = LocalizedString(
            key = StringKey("capabilities.checked", "capabilities_checked", listOf("time"), 1),
            arguments = listOf(`time`),
        )
        val `refresh`: LocalizedString get() = LocalizedString(
            key = StringKey("capabilities.refresh", "capabilities_refresh", emptyList(), 1),
        )
        val `modules`: LocalizedString get() = LocalizedString(
            key = StringKey("capabilities.modules", "capabilities_modules", emptyList(), 1),
        )
        val `plugins`: LocalizedString get() = LocalizedString(
            key = StringKey("capabilities.plugins", "capabilities_plugins", emptyList(), 1),
        )
        val `noPlugins`: LocalizedString get() = LocalizedString(
            key = StringKey("capabilities.noPlugins", "capabilities_no_plugins", emptyList(), 1),
        )
        fun `version`(`version`: String): LocalizedString = LocalizedString(
            key = StringKey("capabilities.version", "capabilities_version", listOf("version"), 1),
            arguments = listOf(`version`),
        )
        object `Status` {
            val `connected`: LocalizedString get() = LocalizedString(
                key = StringKey("capabilities.status.connected", "capabilities_status_connected", emptyList(), 1),
            )
            val `disabled`: LocalizedString get() = LocalizedString(
                key = StringKey("capabilities.status.disabled", "capabilities_status_disabled", emptyList(), 1),
            )
            val `unavailable`: LocalizedString get() = LocalizedString(
                key = StringKey("capabilities.status.unavailable", "capabilities_status_unavailable", emptyList(), 1),
            )
            val `unknown`: LocalizedString get() = LocalizedString(
                key = StringKey("capabilities.status.unknown", "capabilities_status_unknown", emptyList(), 1),
            )
        }
    }
    object `Limits` {
        object `Reached` {
            val `assistant`: LocalizedString get() = LocalizedString(
                key = StringKey("limits.reached.assistant", "limits_reached_assistant", emptyList(), 1),
            )
            val `research`: LocalizedString get() = LocalizedString(
                key = StringKey("limits.reached.research", "limits_reached_research", emptyList(), 1),
            )
            val `dictation_ms`: LocalizedString get() = LocalizedString(
                key = StringKey("limits.reached.dictation_ms", "limits_reached_dictation_ms", emptyList(), 1),
            )
            val `tts_chars`: LocalizedString get() = LocalizedString(
                key = StringKey("limits.reached.tts_chars", "limits_reached_tts_chars", emptyList(), 1),
            )
        }
        fun `resetAt`(`when`: String): LocalizedString = LocalizedString(
            key = StringKey("limits.resetAt", "limits_reset_at", listOf("when"), 1),
            arguments = listOf(`when`),
        )
        val `manual`: LocalizedString get() = LocalizedString(
            key = StringKey("limits.manual", "limits_manual", emptyList(), 1),
        )
        val `addFood`: LocalizedString get() = LocalizedString(
            key = StringKey("limits.addFood", "limits_add_food", emptyList(), 1),
        )
        val `openTasks`: LocalizedString get() = LocalizedString(
            key = StringKey("limits.openTasks", "limits_open_tasks", emptyList(), 1),
        )
        val `see`: LocalizedString get() = LocalizedString(
            key = StringKey("limits.see", "limits_see", emptyList(), 1),
        )
    }
    object `Plugins` {
        val `title`: LocalizedString get() = LocalizedString(
            key = StringKey("plugins.title", "plugins_title", emptyList(), 1),
        )
        val `missing`: LocalizedString get() = LocalizedString(
            key = StringKey("plugins.missing", "plugins_missing", emptyList(), 1),
        )
        val `missingText`: LocalizedString get() = LocalizedString(
            key = StringKey("plugins.missingText", "plugins_missing_text", emptyList(), 1),
        )
        val `others`: LocalizedString get() = LocalizedString(
            key = StringKey("plugins.others", "plugins_others", emptyList(), 1),
        )
        object `Fallback` {
            val `no_ui`: LocalizedString get() = LocalizedString(
                key = StringKey("plugins.fallback.no_ui", "plugins_fallback_no_ui", emptyList(), 1),
            )
            val `client_api`: LocalizedString get() = LocalizedString(
                key = StringKey("plugins.fallback.client_api", "plugins_fallback_client_api", emptyList(), 1),
            )
            val `missing_tools`: LocalizedString get() = LocalizedString(
                key = StringKey("plugins.fallback.missing_tools", "plugins_fallback_missing_tools", emptyList(), 1),
            )
            val `unavailable`: LocalizedString get() = LocalizedString(
                key = StringKey("plugins.fallback.unavailable", "plugins_fallback_unavailable", emptyList(), 1),
            )
        }
    }
    object `Circle` {
        val `waiting`: LocalizedString get() = LocalizedString(
            key = StringKey("circle.waiting", "circle_waiting", emptyList(), 1),
        )
        val `title`: LocalizedString get() = LocalizedString(
            key = StringKey("circle.title", "circle_title", emptyList(), 1),
        )
        val `invitations`: LocalizedString get() = LocalizedString(
            key = StringKey("circle.invitations", "circle_invitations", emptyList(), 1),
        )
        val `invited`: LocalizedString get() = LocalizedString(
            key = StringKey("circle.invited", "circle_invited", emptyList(), 1),
        )
        fun `invitedBy`(`name`: String): LocalizedString = LocalizedString(
            key = StringKey("circle.invitedBy", "circle_invited_by", listOf("name"), 1),
            arguments = listOf(`name`),
        )
        val `spaces`: LocalizedString get() = LocalizedString(
            key = StringKey("circle.spaces", "circle_spaces", emptyList(), 1),
        )
        val `noSpaces`: LocalizedString get() = LocalizedString(
            key = StringKey("circle.noSpaces", "circle_no_spaces", emptyList(), 1),
        )
        val `create`: LocalizedString get() = LocalizedString(
            key = StringKey("circle.create", "circle_create", emptyList(), 1),
        )
        val `unread`: LocalizedString get() = LocalizedString(
            key = StringKey("circle.unread", "circle_unread", emptyList(), 1),
        )
        object `Role` {
            val `owner`: LocalizedString get() = LocalizedString(
                key = StringKey("circle.role.owner", "circle_role_owner", emptyList(), 1),
            )
            val `admin`: LocalizedString get() = LocalizedString(
                key = StringKey("circle.role.admin", "circle_role_admin", emptyList(), 1),
            )
            val `member`: LocalizedString get() = LocalizedString(
                key = StringKey("circle.role.member", "circle_role_member", emptyList(), 1),
            )
        }
        object `RoleText` {
            val `owner`: LocalizedString get() = LocalizedString(
                key = StringKey("circle.roleText.owner", "circle_role_text_owner", emptyList(), 1),
            )
            val `admin`: LocalizedString get() = LocalizedString(
                key = StringKey("circle.roleText.admin", "circle_role_text_admin", emptyList(), 1),
            )
            val `member`: LocalizedString get() = LocalizedString(
                key = StringKey("circle.roleText.member", "circle_role_text_member", emptyList(), 1),
            )
        }
        val `roleTitle`: LocalizedString get() = LocalizedString(
            key = StringKey("circle.roleTitle", "circle_role_title", emptyList(), 1),
        )
        val `saveRole`: LocalizedString get() = LocalizedString(
            key = StringKey("circle.saveRole", "circle_save_role", emptyList(), 1),
        )
        val `roleSaved`: LocalizedString get() = LocalizedString(
            key = StringKey("circle.roleSaved", "circle_role_saved", emptyList(), 1),
        )
        val `ownerWarning`: LocalizedString get() = LocalizedString(
            key = StringKey("circle.ownerWarning", "circle_owner_warning", emptyList(), 1),
        )
        val `friends`: LocalizedString get() = LocalizedString(
            key = StringKey("circle.friends", "circle_friends", emptyList(), 1),
        )
        val `friendsText`: LocalizedString get() = LocalizedString(
            key = StringKey("circle.friendsText", "circle_friends_text", emptyList(), 1),
        )
        val `newSpace`: LocalizedString get() = LocalizedString(
            key = StringKey("circle.newSpace", "circle_new_space", emptyList(), 1),
        )
        val `spaceName`: LocalizedString get() = LocalizedString(
            key = StringKey("circle.spaceName", "circle_space_name", emptyList(), 1),
        )
        val `spaceExample`: LocalizedString get() = LocalizedString(
            key = StringKey("circle.spaceExample", "circle_space_example", emptyList(), 1),
        )
        val `created`: LocalizedString get() = LocalizedString(
            key = StringKey("circle.created", "circle_created", emptyList(), 1),
        )
        val `historyShared`: LocalizedString get() = LocalizedString(
            key = StringKey("circle.historyShared", "circle_history_shared", emptyList(), 1),
        )
        val `historySharedText`: LocalizedString get() = LocalizedString(
            key = StringKey("circle.historySharedText", "circle_history_shared_text", emptyList(), 1),
        )
        val `afterJoin`: LocalizedString get() = LocalizedString(
            key = StringKey("circle.afterJoin", "circle_after_join", emptyList(), 1),
        )
        val `afterJoinText`: LocalizedString get() = LocalizedString(
            key = StringKey("circle.afterJoinText", "circle_after_join_text", emptyList(), 1),
        )
        val `join`: LocalizedString get() = LocalizedString(
            key = StringKey("circle.join", "circle_join", emptyList(), 1),
        )
        val `later`: LocalizedString get() = LocalizedString(
            key = StringKey("circle.later", "circle_later", emptyList(), 1),
        )
        fun `joined`(`name`: String): LocalizedString = LocalizedString(
            key = StringKey("circle.joined", "circle_joined", listOf("name"), 1),
            arguments = listOf(`name`),
        )
        val `exactLogin`: LocalizedString get() = LocalizedString(
            key = StringKey("circle.exactLogin", "circle_exact_login", emptyList(), 1),
        )
        val `find`: LocalizedString get() = LocalizedString(
            key = StringKey("circle.find", "circle_find", emptyList(), 1),
        )
        object `Group` {
            val `incoming`: LocalizedString get() = LocalizedString(
                key = StringKey("circle.group.incoming", "circle_group_incoming", emptyList(), 1),
            )
            val `friends`: LocalizedString get() = LocalizedString(
                key = StringKey("circle.group.friends", "circle_group_friends", emptyList(), 1),
            )
            val `outgoing`: LocalizedString get() = LocalizedString(
                key = StringKey("circle.group.outgoing", "circle_group_outgoing", emptyList(), 1),
            )
            val `blocked`: LocalizedString get() = LocalizedString(
                key = StringKey("circle.group.blocked", "circle_group_blocked", emptyList(), 1),
            )
        }
        val `requestIn`: LocalizedString get() = LocalizedString(
            key = StringKey("circle.requestIn", "circle_request_in", emptyList(), 1),
        )
        val `requestOut`: LocalizedString get() = LocalizedString(
            key = StringKey("circle.requestOut", "circle_request_out", emptyList(), 1),
        )
        val `blocked`: LocalizedString get() = LocalizedString(
            key = StringKey("circle.blocked", "circle_blocked", emptyList(), 1),
        )
        val `friend`: LocalizedString get() = LocalizedString(
            key = StringKey("circle.friend", "circle_friend", emptyList(), 1),
        )
        val `new`: LocalizedString get() = LocalizedString(
            key = StringKey("circle.new", "circle_new", emptyList(), 1),
        )
        val `noFriends`: LocalizedString get() = LocalizedString(
            key = StringKey("circle.noFriends", "circle_no_friends", emptyList(), 1),
        )
        val `noFriendsText`: LocalizedString get() = LocalizedString(
            key = StringKey("circle.noFriendsText", "circle_no_friends_text", emptyList(), 1),
        )
        val `notConnected`: LocalizedString get() = LocalizedString(
            key = StringKey("circle.notConnected", "circle_not_connected", emptyList(), 1),
        )
        val `addFriend`: LocalizedString get() = LocalizedString(
            key = StringKey("circle.addFriend", "circle_add_friend", emptyList(), 1),
        )
        val `requested`: LocalizedString get() = LocalizedString(
            key = StringKey("circle.requested", "circle_requested", emptyList(), 1),
        )
        val `accept`: LocalizedString get() = LocalizedString(
            key = StringKey("circle.accept", "circle_accept", emptyList(), 1),
        )
        val `accepted`: LocalizedString get() = LocalizedString(
            key = StringKey("circle.accepted", "circle_accepted", emptyList(), 1),
        )
        val `write`: LocalizedString get() = LocalizedString(
            key = StringKey("circle.write", "circle_write", emptyList(), 1),
        )
        val `label`: LocalizedString get() = LocalizedString(
            key = StringKey("circle.label", "circle_label", emptyList(), 1),
        )
        val `labelExample`: LocalizedString get() = LocalizedString(
            key = StringKey("circle.labelExample", "circle_label_example", emptyList(), 1),
        )
        val `labelSaved`: LocalizedString get() = LocalizedString(
            key = StringKey("circle.labelSaved", "circle_label_saved", emptyList(), 1),
        )
        val `block`: LocalizedString get() = LocalizedString(
            key = StringKey("circle.block", "circle_block", emptyList(), 1),
        )
        fun `blockedDone`(`name`: String): LocalizedString = LocalizedString(
            key = StringKey("circle.blockedDone", "circle_blocked_done", listOf("name"), 1),
            arguments = listOf(`name`),
        )
        val `unblock`: LocalizedString get() = LocalizedString(
            key = StringKey("circle.unblock", "circle_unblock", emptyList(), 1),
        )
        val `unblocked`: LocalizedString get() = LocalizedString(
            key = StringKey("circle.unblocked", "circle_unblocked", emptyList(), 1),
        )
        val `remove`: LocalizedString get() = LocalizedString(
            key = StringKey("circle.remove", "circle_remove", emptyList(), 1),
        )
        val `removed`: LocalizedString get() = LocalizedString(
            key = StringKey("circle.removed", "circle_removed", emptyList(), 1),
        )
        val `cancelRequest`: LocalizedString get() = LocalizedString(
            key = StringKey("circle.cancelRequest", "circle_cancel_request", emptyList(), 1),
        )
        fun `blockedTitle`(`name`: String): LocalizedString = LocalizedString(
            key = StringKey("circle.blockedTitle", "circle_blocked_title", listOf("name"), 1),
            arguments = listOf(`name`),
        )
        val `blockedText`: LocalizedString get() = LocalizedString(
            key = StringKey("circle.blockedText", "circle_blocked_text", emptyList(), 1),
        )
        val `checkAccess`: LocalizedString get() = LocalizedString(
            key = StringKey("circle.checkAccess", "circle_check_access", emptyList(), 1),
        )
        val `goneTitle`: LocalizedString get() = LocalizedString(
            key = StringKey("circle.goneTitle", "circle_gone_title", emptyList(), 1),
        )
        val `goneText`: LocalizedString get() = LocalizedString(
            key = StringKey("circle.goneText", "circle_gone_text", emptyList(), 1),
        )
        val `toSpaces`: LocalizedString get() = LocalizedString(
            key = StringKey("circle.toSpaces", "circle_to_spaces", emptyList(), 1),
        )
        val `toAssistant`: LocalizedString get() = LocalizedString(
            key = StringKey("circle.toAssistant", "circle_to_assistant", emptyList(), 1),
        )
        val `chat`: LocalizedString get() = LocalizedString(
            key = StringKey("circle.chat", "circle_chat", emptyList(), 1),
        )
        val `assistant`: LocalizedString get() = LocalizedString(
            key = StringKey("circle.assistant", "circle_assistant", emptyList(), 1),
        )
        val `you`: LocalizedString get() = LocalizedString(
            key = StringKey("circle.you", "circle_you", emptyList(), 1),
        )
        val `participant`: LocalizedString get() = LocalizedString(
            key = StringKey("circle.participant", "circle_participant", emptyList(), 1),
        )
        fun `onlyYouAnd`(`name`: String): LocalizedString = LocalizedString(
            key = StringKey("circle.onlyYouAnd", "circle_only_you_and", listOf("name"), 1),
            arguments = listOf(`name`),
        )
        fun `everyoneIn`(`name`: String): LocalizedString = LocalizedString(
            key = StringKey("circle.everyoneIn", "circle_everyone_in", listOf("name"), 1),
            arguments = listOf(`name`),
        )
        val `startTalking`: LocalizedString get() = LocalizedString(
            key = StringKey("circle.startTalking", "circle_start_talking", emptyList(), 1),
        )
        val `reply`: LocalizedString get() = LocalizedString(
            key = StringKey("circle.reply", "circle_reply", emptyList(), 1),
        )
        val `cancelReply`: LocalizedString get() = LocalizedString(
            key = StringKey("circle.cancelReply", "circle_cancel_reply", emptyList(), 1),
        )
        val `message`: LocalizedString get() = LocalizedString(
            key = StringKey("circle.message", "circle_message", emptyList(), 1),
        )
        fun `messageTo`(`name`: String): LocalizedString = LocalizedString(
            key = StringKey("circle.messageTo", "circle_message_to", listOf("name"), 1),
            arguments = listOf(`name`),
        )
        val `send`: LocalizedString get() = LocalizedString(
            key = StringKey("circle.send", "circle_send", emptyList(), 1),
        )
        val `askAssistant`: LocalizedString get() = LocalizedString(
            key = StringKey("circle.askAssistant", "circle_ask_assistant", emptyList(), 1),
        )
        val `friendsOnly`: LocalizedString get() = LocalizedString(
            key = StringKey("circle.friendsOnly", "circle_friends_only", emptyList(), 1),
        )
        val `unsentText`: LocalizedString get() = LocalizedString(
            key = StringKey("circle.unsentText", "circle_unsent_text", emptyList(), 1),
        )
        val `notArrived`: LocalizedString get() = LocalizedString(
            key = StringKey("circle.notArrived", "circle_not_arrived", emptyList(), 1),
        )
        val `dontSend`: LocalizedString get() = LocalizedString(
            key = StringKey("circle.dontSend", "circle_dont_send", emptyList(), 1),
        )
        val `askTitle`: LocalizedString get() = LocalizedString(
            key = StringKey("circle.askTitle", "circle_ask_title", emptyList(), 1),
        )
        fun `answerDirect`(`name`: String): LocalizedString = LocalizedString(
            key = StringKey("circle.answerDirect", "circle_answer_direct", listOf("name"), 1),
            arguments = listOf(`name`),
        )
        fun `answerSpace`(`name`: String): LocalizedString = LocalizedString(
            key = StringKey("circle.answerSpace", "circle_answer_space", listOf("name"), 1),
            arguments = listOf(`name`),
        )
        val `answerText`: LocalizedString get() = LocalizedString(
            key = StringKey("circle.answerText", "circle_answer_text", emptyList(), 1),
        )
        val `askHere`: LocalizedString get() = LocalizedString(
            key = StringKey("circle.askHere", "circle_ask_here", emptyList(), 1),
        )
        fun `askHereDirect`(`name`: String): LocalizedString = LocalizedString(
            key = StringKey("circle.askHereDirect", "circle_ask_here_direct", listOf("name"), 1),
            arguments = listOf(`name`),
        )
        val `askPrivately`: LocalizedString get() = LocalizedString(
            key = StringKey("circle.askPrivately", "circle_ask_privately", emptyList(), 1),
        )
        val `space`: LocalizedString get() = LocalizedString(
            key = StringKey("circle.space", "circle_space", emptyList(), 1),
        )
        val `members`: LocalizedString get() = LocalizedString(
            key = StringKey("circle.members", "circle_members", emptyList(), 1),
        )
        fun `membersCount`(quantity: Double, `count`: String): LocalizedString = LocalizedString(
            key = StringKey("circle.membersCount", "circle_members_count", listOf("count"), 3),
            arguments = listOf(`count`),
            quantity = quantity,
        )
        val `sharedChat`: LocalizedString get() = LocalizedString(
            key = StringKey("circle.sharedChat", "circle_shared_chat", emptyList(), 1),
        )
        fun `newMessages`(quantity: Double, `count`: String): LocalizedString = LocalizedString(
            key = StringKey("circle.newMessages", "circle_new_messages", listOf("count"), 3),
            arguments = listOf(`count`),
            quantity = quantity,
        )
        val `everyoneSees`: LocalizedString get() = LocalizedString(
            key = StringKey("circle.everyoneSees", "circle_everyone_sees", emptyList(), 1),
        )
        object `Effective` {
            val `view`: LocalizedString get() = LocalizedString(
                key = StringKey("circle.effective.view", "circle_effective_view", emptyList(), 1),
            )
            val `edit`: LocalizedString get() = LocalizedString(
                key = StringKey("circle.effective.edit", "circle_effective_edit", emptyList(), 1),
            )
            val `manage`: LocalizedString get() = LocalizedString(
                key = StringKey("circle.effective.manage", "circle_effective_manage", emptyList(), 1),
            )
            val `none`: LocalizedString get() = LocalizedString(
                key = StringKey("circle.effective.none", "circle_effective_none", emptyList(), 1),
            )
        }
        val `challenges`: LocalizedString get() = LocalizedString(
            key = StringKey("circle.challenges", "circle_challenges", emptyList(), 1),
        )
        val `challengesText`: LocalizedString get() = LocalizedString(
            key = StringKey("circle.challengesText", "circle_challenges_text", emptyList(), 1),
        )
        val `membersAndAccess`: LocalizedString get() = LocalizedString(
            key = StringKey("circle.membersAndAccess", "circle_members_and_access", emptyList(), 1),
        )
        fun `youAre`(`role`: String): LocalizedString = LocalizedString(
            key = StringKey("circle.youAre", "circle_you_are", listOf("role"), 1),
            arguments = listOf(`role`),
        )
        val `newList`: LocalizedString get() = LocalizedString(
            key = StringKey("circle.newList", "circle_new_list", emptyList(), 1),
        )
        val `listName`: LocalizedString get() = LocalizedString(
            key = StringKey("circle.listName", "circle_list_name", emptyList(), 1),
        )
        val `listExample`: LocalizedString get() = LocalizedString(
            key = StringKey("circle.listExample", "circle_list_example", emptyList(), 1),
        )
        val `createList`: LocalizedString get() = LocalizedString(
            key = StringKey("circle.createList", "circle_create_list", emptyList(), 1),
        )
        val `listCreated`: LocalizedString get() = LocalizedString(
            key = StringKey("circle.listCreated", "circle_list_created", emptyList(), 1),
        )
        val `alreadyMember`: LocalizedString get() = LocalizedString(
            key = StringKey("circle.alreadyMember", "circle_already_member", emptyList(), 1),
        )
        val `invite`: LocalizedString get() = LocalizedString(
            key = StringKey("circle.invite", "circle_invite", emptyList(), 1),
        )
        fun `inviteTitle`(`name`: String): LocalizedString = LocalizedString(
            key = StringKey("circle.inviteTitle", "circle_invite_title", listOf("name"), 1),
            arguments = listOf(`name`),
        )
        val `inviteText`: LocalizedString get() = LocalizedString(
            key = StringKey("circle.inviteText", "circle_invite_text", emptyList(), 1),
        )
        val `sendInvite`: LocalizedString get() = LocalizedString(
            key = StringKey("circle.sendInvite", "circle_send_invite", emptyList(), 1),
        )
        fun `inviteSent`(`name`: String): LocalizedString = LocalizedString(
            key = StringKey("circle.inviteSent", "circle_invite_sent", listOf("name"), 1),
            arguments = listOf(`name`),
        )
        fun `removedMember`(`name`: String): LocalizedString = LocalizedString(
            key = StringKey("circle.removedMember", "circle_removed_member", listOf("name"), 1),
            arguments = listOf(`name`),
        )
        val `removeMember`: LocalizedString get() = LocalizedString(
            key = StringKey("circle.removeMember", "circle_remove_member", emptyList(), 1),
        )
        fun `soleOwner`(`name`: String): LocalizedString = LocalizedString(
            key = StringKey("circle.soleOwner", "circle_sole_owner", listOf("name"), 1),
            arguments = listOf(`name`),
        )
        val `leave`: LocalizedString get() = LocalizedString(
            key = StringKey("circle.leave", "circle_leave", emptyList(), 1),
        )
        val `leaveTitle`: LocalizedString get() = LocalizedString(
            key = StringKey("circle.leaveTitle", "circle_leave_title", emptyList(), 1),
        )
        fun `leaveText`(`name`: String): LocalizedString = LocalizedString(
            key = StringKey("circle.leaveText", "circle_leave_text", listOf("name"), 1),
            arguments = listOf(`name`),
        )
        val `stay`: LocalizedString get() = LocalizedString(
            key = StringKey("circle.stay", "circle_stay", emptyList(), 1),
        )
        fun `left`(`name`: String): LocalizedString = LocalizedString(
            key = StringKey("circle.left", "circle_left", listOf("name"), 1),
            arguments = listOf(`name`),
        )
        val `archive`: LocalizedString get() = LocalizedString(
            key = StringKey("circle.archive", "circle_archive", emptyList(), 1),
        )
        fun `archiveTitle`(`name`: String): LocalizedString = LocalizedString(
            key = StringKey("circle.archiveTitle", "circle_archive_title", listOf("name"), 1),
            arguments = listOf(`name`),
        )
        val `archiveText`: LocalizedString get() = LocalizedString(
            key = StringKey("circle.archiveText", "circle_archive_text", emptyList(), 1),
        )
        val `keep`: LocalizedString get() = LocalizedString(
            key = StringKey("circle.keep", "circle_keep", emptyList(), 1),
        )
        fun `archived`(`name`: String): LocalizedString = LocalizedString(
            key = StringKey("circle.archived", "circle_archived", listOf("name"), 1),
            arguments = listOf(`name`),
        )
        val `list`: LocalizedString get() = LocalizedString(
            key = StringKey("circle.list", "circle_list", emptyList(), 1),
        )
        val `listAccess`: LocalizedString get() = LocalizedString(
            key = StringKey("circle.listAccess", "circle_list_access", emptyList(), 1),
        )
        val `newTask`: LocalizedString get() = LocalizedString(
            key = StringKey("circle.newTask", "circle_new_task", emptyList(), 1),
        )
        val `listEmpty`: LocalizedString get() = LocalizedString(
            key = StringKey("circle.listEmpty", "circle_list_empty", emptyList(), 1),
        )
        val `listEmptyText`: LocalizedString get() = LocalizedString(
            key = StringKey("circle.listEmptyText", "circle_list_empty_text", emptyList(), 1),
        )
        val `noAssignee`: LocalizedString get() = LocalizedString(
            key = StringKey("circle.noAssignee", "circle_no_assignee", emptyList(), 1),
        )
        val `assignee`: LocalizedString get() = LocalizedString(
            key = StringKey("circle.assignee", "circle_assignee", emptyList(), 1),
        )
        val `defaultAccess`: LocalizedString get() = LocalizedString(
            key = StringKey("circle.defaultAccess", "circle_default_access", emptyList(), 1),
        )
        object `MemberAccess` {
            val `view`: LocalizedString get() = LocalizedString(
                key = StringKey("circle.memberAccess.view", "circle_member_access_view", emptyList(), 1),
            )
            val `edit`: LocalizedString get() = LocalizedString(
                key = StringKey("circle.memberAccess.edit", "circle_member_access_edit", emptyList(), 1),
            )
        }
        object `Level` {
            val `view`: LocalizedString get() = LocalizedString(
                key = StringKey("circle.level.view", "circle_level_view", emptyList(), 1),
            )
            val `edit`: LocalizedString get() = LocalizedString(
                key = StringKey("circle.level.edit", "circle_level_edit", emptyList(), 1),
            )
            val `manage`: LocalizedString get() = LocalizedString(
                key = StringKey("circle.level.manage", "circle_level_manage", emptyList(), 1),
            )
        }
        object `Permission` {
            val `view`: LocalizedString get() = LocalizedString(
                key = StringKey("circle.permission.view", "circle_permission_view", emptyList(), 1),
            )
            val `edit`: LocalizedString get() = LocalizedString(
                key = StringKey("circle.permission.edit", "circle_permission_edit", emptyList(), 1),
            )
            val `manage`: LocalizedString get() = LocalizedString(
                key = StringKey("circle.permission.manage", "circle_permission_manage", emptyList(), 1),
            )
        }
        val `personalRights`: LocalizedString get() = LocalizedString(
            key = StringKey("circle.personalRights", "circle_personal_rights", emptyList(), 1),
        )
        val `noGrants`: LocalizedString get() = LocalizedString(
            key = StringKey("circle.noGrants", "circle_no_grants", emptyList(), 1),
        )
        val `chooseMember`: LocalizedString get() = LocalizedString(
            key = StringKey("circle.chooseMember", "circle_choose_member", emptyList(), 1),
        )
        val `levelTitle`: LocalizedString get() = LocalizedString(
            key = StringKey("circle.levelTitle", "circle_level_title", emptyList(), 1),
        )
        val `grant`: LocalizedString get() = LocalizedString(
            key = StringKey("circle.grant", "circle_grant", emptyList(), 1),
        )
        val `accessSaved`: LocalizedString get() = LocalizedString(
            key = StringKey("circle.accessSaved", "circle_access_saved", emptyList(), 1),
        )
        val `accessRevoked`: LocalizedString get() = LocalizedString(
            key = StringKey("circle.accessRevoked", "circle_access_revoked", emptyList(), 1),
        )
        val `changed`: LocalizedString get() = LocalizedString(
            key = StringKey("circle.changed", "circle_changed", emptyList(), 1),
        )
        val `noChallenges`: LocalizedString get() = LocalizedString(
            key = StringKey("circle.noChallenges", "circle_no_challenges", emptyList(), 1),
        )
        val `noChallengesText`: LocalizedString get() = LocalizedString(
            key = StringKey("circle.noChallengesText", "circle_no_challenges_text", emptyList(), 1),
        )
        val `newChallenge`: LocalizedString get() = LocalizedString(
            key = StringKey("circle.newChallenge", "circle_new_challenge", emptyList(), 1),
        )
        val `challengeName`: LocalizedString get() = LocalizedString(
            key = StringKey("circle.challengeName", "circle_challenge_name", emptyList(), 1),
        )
        val `challengeExample`: LocalizedString get() = LocalizedString(
            key = StringKey("circle.challengeExample", "circle_challenge_example", emptyList(), 1),
        )
        val `challengeStart`: LocalizedString get() = LocalizedString(
            key = StringKey("circle.challengeStart", "circle_challenge_start", emptyList(), 1),
        )
        val `challengeEnd`: LocalizedString get() = LocalizedString(
            key = StringKey("circle.challengeEnd", "circle_challenge_end", emptyList(), 1),
        )
        val `tolerance`: LocalizedString get() = LocalizedString(
            key = StringKey("circle.tolerance", "circle_tolerance", emptyList(), 1),
        )
        val `startFuture`: LocalizedString get() = LocalizedString(
            key = StringKey("circle.startFuture", "circle_start_future", emptyList(), 1),
        )
        val `startFutureText`: LocalizedString get() = LocalizedString(
            key = StringKey("circle.startFutureText", "circle_start_future_text", emptyList(), 1),
        )
        val `createChallenge`: LocalizedString get() = LocalizedString(
            key = StringKey("circle.createChallenge", "circle_create_challenge", emptyList(), 1),
        )
        val `challengeCreated`: LocalizedString get() = LocalizedString(
            key = StringKey("circle.challengeCreated", "circle_challenge_created", emptyList(), 1),
        )
        val `challengeNameRequired`: LocalizedString get() = LocalizedString(
            key = StringKey("circle.challengeNameRequired", "circle_challenge_name_required", emptyList(), 1),
        )
        val `challengeDates`: LocalizedString get() = LocalizedString(
            key = StringKey("circle.challengeDates", "circle_challenge_dates", emptyList(), 1),
        )
        val `challengeFuture`: LocalizedString get() = LocalizedString(
            key = StringKey("circle.challengeFuture", "circle_challenge_future", emptyList(), 1),
        )
        val `challengeTolerance`: LocalizedString get() = LocalizedString(
            key = StringKey("circle.challengeTolerance", "circle_challenge_tolerance", emptyList(), 1),
        )
        object `ChallengeState` {
            val `upcoming`: LocalizedString get() = LocalizedString(
                key = StringKey("circle.challengeState.upcoming", "circle_challenge_state_upcoming", emptyList(), 1),
            )
            val `running`: LocalizedString get() = LocalizedString(
                key = StringKey("circle.challengeState.running", "circle_challenge_state_running", emptyList(), 1),
            )
            val `finished`: LocalizedString get() = LocalizedString(
                key = StringKey("circle.challengeState.finished", "circle_challenge_state_finished", emptyList(), 1),
            )
        }
        val `rankingShows`: LocalizedString get() = LocalizedString(
            key = StringKey("circle.rankingShows", "circle_ranking_shows", emptyList(), 1),
        )
        val `rankingShowsText`: LocalizedString get() = LocalizedString(
            key = StringKey("circle.rankingShowsText", "circle_ranking_shows_text", emptyList(), 1),
        )
        val `challenge`: LocalizedString get() = LocalizedString(
            key = StringKey("circle.challenge", "circle_challenge", emptyList(), 1),
        )
        val `rulesText`: LocalizedString get() = LocalizedString(
            key = StringKey("circle.rulesText", "circle_rules_text", emptyList(), 1),
        )
        val `othersSee`: LocalizedString get() = LocalizedString(
            key = StringKey("circle.othersSee", "circle_others_see", emptyList(), 1),
        )
        val `daysAndStreak`: LocalizedString get() = LocalizedString(
            key = StringKey("circle.daysAndStreak", "circle_days_and_streak", emptyList(), 1),
        )
        val `noGoalTitle`: LocalizedString get() = LocalizedString(
            key = StringKey("circle.noGoalTitle", "circle_no_goal_title", emptyList(), 1),
        )
        val `noGoalText`: LocalizedString get() = LocalizedString(
            key = StringKey("circle.noGoalText", "circle_no_goal_text", emptyList(), 1),
        )
        val `goalForLater`: LocalizedString get() = LocalizedString(
            key = StringKey("circle.goalForLater", "circle_goal_for_later", emptyList(), 1),
        )
        val `pendingTitle`: LocalizedString get() = LocalizedString(
            key = StringKey("circle.pendingTitle", "circle_pending_title", emptyList(), 1),
        )
        val `pendingText`: LocalizedString get() = LocalizedString(
            key = StringKey("circle.pendingText", "circle_pending_text", emptyList(), 1),
        )
        val `activeTitle`: LocalizedString get() = LocalizedString(
            key = StringKey("circle.activeTitle", "circle_active_title", emptyList(), 1),
        )
        val `goalAtStart`: LocalizedString get() = LocalizedString(
            key = StringKey("circle.goalAtStart", "circle_goal_at_start", emptyList(), 1),
        )
        fun `range`(`tolerance`: String): LocalizedString = LocalizedString(
            key = StringKey("circle.range", "circle_range", listOf("tolerance"), 1),
            arguments = listOf(`tolerance`),
        )
        val `termsFixed`: LocalizedString get() = LocalizedString(
            key = StringKey("circle.termsFixed", "circle_terms_fixed", emptyList(), 1),
        )
        val `joinClosed`: LocalizedString get() = LocalizedString(
            key = StringKey("circle.joinClosed", "circle_join_closed", emptyList(), 1),
        )
        val `participate`: LocalizedString get() = LocalizedString(
            key = StringKey("circle.participate", "circle_participate", emptyList(), 1),
        )
        val `joinedChallenge`: LocalizedString get() = LocalizedString(
            key = StringKey("circle.joinedChallenge", "circle_joined_challenge", emptyList(), 1),
        )
        val `leaveRanking`: LocalizedString get() = LocalizedString(
            key = StringKey("circle.leaveRanking", "circle_leave_ranking", emptyList(), 1),
        )
        val `leaveRankingTitle`: LocalizedString get() = LocalizedString(
            key = StringKey("circle.leaveRankingTitle", "circle_leave_ranking_title", emptyList(), 1),
        )
        val `leaveRankingText`: LocalizedString get() = LocalizedString(
            key = StringKey("circle.leaveRankingText", "circle_leave_ranking_text", emptyList(), 1),
        )
        val `noRejoin`: LocalizedString get() = LocalizedString(
            key = StringKey("circle.noRejoin", "circle_no_rejoin", emptyList(), 1),
        )
        val `leftChallenge`: LocalizedString get() = LocalizedString(
            key = StringKey("circle.leftChallenge", "circle_left_challenge", emptyList(), 1),
        )
        val `ranking`: LocalizedString get() = LocalizedString(
            key = StringKey("circle.ranking", "circle_ranking", emptyList(), 1),
        )
        val `rankingEmpty`: LocalizedString get() = LocalizedString(
            key = StringKey("circle.rankingEmpty", "circle_ranking_empty", emptyList(), 1),
        )
        val `days`: LocalizedString get() = LocalizedString(
            key = StringKey("circle.days", "circle_days", emptyList(), 1),
        )
        val `streak`: LocalizedString get() = LocalizedString(
            key = StringKey("circle.streak", "circle_streak", emptyList(), 1),
        )
        val `todayNotCounted`: LocalizedString get() = LocalizedString(
            key = StringKey("circle.todayNotCounted", "circle_today_not_counted", emptyList(), 1),
        )
        val `decline`: LocalizedString get() = LocalizedString(
            key = StringKey("circle.decline", "circle_decline", emptyList(), 1),
        )
        val `sharedText`: LocalizedString get() = LocalizedString(
            key = StringKey("circle.sharedText", "circle_shared_text", emptyList(), 1),
        )
        val `rankingPlace`: LocalizedString get() = LocalizedString(
            key = StringKey("circle.rankingPlace", "circle_ranking_place", emptyList(), 1),
        )
        fun `toleranceValue`(`value`: String): LocalizedString = LocalizedString(
            key = StringKey("circle.toleranceValue", "circle_tolerance_value", listOf("value"), 1),
            arguments = listOf(`value`),
        )
    }
    object `Calls` {
        fun `callTo`(`name`: String): LocalizedString = LocalizedString(
            key = StringKey("calls.callTo", "calls_call_to", listOf("name"), 1),
            arguments = listOf(`name`),
        )
        val `incoming`: LocalizedString get() = LocalizedString(
            key = StringKey("calls.incoming", "calls_incoming", emptyList(), 1),
        )
        val `answer`: LocalizedString get() = LocalizedString(
            key = StringKey("calls.answer", "calls_answer", emptyList(), 1),
        )
        val `decline`: LocalizedString get() = LocalizedString(
            key = StringKey("calls.decline", "calls_decline", emptyList(), 1),
        )
        val `cancel`: LocalizedString get() = LocalizedString(
            key = StringKey("calls.cancel", "calls_cancel", emptyList(), 1),
        )
        val `hangUp`: LocalizedString get() = LocalizedString(
            key = StringKey("calls.hangUp", "calls_hang_up", emptyList(), 1),
        )
        val `calling`: LocalizedString get() = LocalizedString(
            key = StringKey("calls.calling", "calls_calling", emptyList(), 1),
        )
        fun `reachHint`(`name`: String): LocalizedString = LocalizedString(
            key = StringKey("calls.reachHint", "calls_reach_hint", listOf("name"), 1),
            arguments = listOf(`name`),
        )
        val `connecting`: LocalizedString get() = LocalizedString(
            key = StringKey("calls.connecting", "calls_connecting", emptyList(), 1),
        )
        val `connected`: LocalizedString get() = LocalizedString(
            key = StringKey("calls.connected", "calls_connected", emptyList(), 1),
        )
        val `unstable`: LocalizedString get() = LocalizedString(
            key = StringKey("calls.unstable", "calls_unstable", emptyList(), 1),
        )
        val `mute`: LocalizedString get() = LocalizedString(
            key = StringKey("calls.mute", "calls_mute", emptyList(), 1),
        )
        val `unmute`: LocalizedString get() = LocalizedString(
            key = StringKey("calls.unmute", "calls_unmute", emptyList(), 1),
        )
        val `muted`: LocalizedString get() = LocalizedString(
            key = StringKey("calls.muted", "calls_muted", emptyList(), 1),
        )
        val `enableSound`: LocalizedString get() = LocalizedString(
            key = StringKey("calls.enableSound", "calls_enable_sound", emptyList(), 1),
        )
        val `elsewhere`: LocalizedString get() = LocalizedString(
            key = StringKey("calls.elsewhere", "calls_elsewhere", emptyList(), 1),
        )
        val `elsewhereCalling`: LocalizedString get() = LocalizedString(
            key = StringKey("calls.elsewhereCalling", "calls_elsewhere_calling", emptyList(), 1),
        )
        val `denied`: LocalizedString get() = LocalizedString(
            key = StringKey("calls.denied", "calls_denied", emptyList(), 1),
        )
        val `unavailable`: LocalizedString get() = LocalizedString(
            key = StringKey("calls.unavailable", "calls_unavailable", emptyList(), 1),
        )
        val `unsupported`: LocalizedString get() = LocalizedString(
            key = StringKey("calls.unsupported", "calls_unsupported", emptyList(), 1),
        )
        object `Ended` {
            val `completed`: LocalizedString get() = LocalizedString(
                key = StringKey("calls.ended.completed", "calls_ended_completed", emptyList(), 1),
            )
            val `declined`: LocalizedString get() = LocalizedString(
                key = StringKey("calls.ended.declined", "calls_ended_declined", emptyList(), 1),
            )
            val `missed`: LocalizedString get() = LocalizedString(
                key = StringKey("calls.ended.missed", "calls_ended_missed", emptyList(), 1),
            )
            val `cancelled`: LocalizedString get() = LocalizedString(
                key = StringKey("calls.ended.cancelled", "calls_ended_cancelled", emptyList(), 1),
            )
            val `failed`: LocalizedString get() = LocalizedString(
                key = StringKey("calls.ended.failed", "calls_ended_failed", emptyList(), 1),
            )
            val `lost`: LocalizedString get() = LocalizedString(
                key = StringKey("calls.ended.lost", "calls_ended_lost", emptyList(), 1),
            )
        }
        object `Error` {
            val `busy`: LocalizedString get() = LocalizedString(
                key = StringKey("calls.error.busy", "calls_error_busy", emptyList(), 1),
            )
            val `inCall`: LocalizedString get() = LocalizedString(
                key = StringKey("calls.error.inCall", "calls_error_in_call", emptyList(), 1),
            )
            val `notFriends`: LocalizedString get() = LocalizedString(
                key = StringKey("calls.error.notFriends", "calls_error_not_friends", emptyList(), 1),
            )
            val `notAvailable`: LocalizedString get() = LocalizedString(
                key = StringKey("calls.error.notAvailable", "calls_error_not_available", emptyList(), 1),
            )
            val `tooMany`: LocalizedString get() = LocalizedString(
                key = StringKey("calls.error.tooMany", "calls_error_too_many", emptyList(), 1),
            )
            val `answeredElsewhere`: LocalizedString get() = LocalizedString(
                key = StringKey("calls.error.answeredElsewhere", "calls_error_answered_elsewhere", emptyList(), 1),
            )
            val `ended`: LocalizedString get() = LocalizedString(
                key = StringKey("calls.error.ended", "calls_error_ended", emptyList(), 1),
            )
        }
        object `Line` {
            fun `call`(`duration`: String): LocalizedString = LocalizedString(
                key = StringKey("calls.line.call", "calls_line_call", listOf("duration"), 1),
                arguments = listOf(`duration`),
            )
            fun `lost`(`duration`: String): LocalizedString = LocalizedString(
                key = StringKey("calls.line.lost", "calls_line_lost", listOf("duration"), 1),
                arguments = listOf(`duration`),
            )
            val `declined`: LocalizedString get() = LocalizedString(
                key = StringKey("calls.line.declined", "calls_line_declined", emptyList(), 1),
            )
            val `missedIn`: LocalizedString get() = LocalizedString(
                key = StringKey("calls.line.missedIn", "calls_line_missed_in", emptyList(), 1),
            )
            val `missedOut`: LocalizedString get() = LocalizedString(
                key = StringKey("calls.line.missedOut", "calls_line_missed_out", emptyList(), 1),
            )
        }
        object `Dev` {
            val `signal`: LocalizedString get() = LocalizedString(
                key = StringKey("calls.dev.signal", "calls_dev_signal", emptyList(), 1),
            )
            val `on`: LocalizedString get() = LocalizedString(
                key = StringKey("calls.dev.on", "calls_dev_on", emptyList(), 1),
            )
            val `off`: LocalizedString get() = LocalizedString(
                key = StringKey("calls.dev.off", "calls_dev_off", emptyList(), 1),
            )
            val `ice`: LocalizedString get() = LocalizedString(
                key = StringKey("calls.dev.ice", "calls_dev_ice", emptyList(), 1),
            )
            val `pair`: LocalizedString get() = LocalizedString(
                key = StringKey("calls.dev.pair", "calls_dev_pair", emptyList(), 1),
            )
            val `rtt`: LocalizedString get() = LocalizedString(
                key = StringKey("calls.dev.rtt", "calls_dev_rtt", emptyList(), 1),
            )
            val `loss`: LocalizedString get() = LocalizedString(
                key = StringKey("calls.dev.loss", "calls_dev_loss", emptyList(), 1),
            )
            val `kinds`: LocalizedString get() = LocalizedString(
                key = StringKey("calls.dev.kinds", "calls_dev_kinds", emptyList(), 1),
            )
            val `probe`: LocalizedString get() = LocalizedString(
                key = StringKey("calls.dev.probe", "calls_dev_probe", emptyList(), 1),
            )
            val `probeText`: LocalizedString get() = LocalizedString(
                key = StringKey("calls.dev.probeText", "calls_dev_probe_text", emptyList(), 1),
            )
            fun `probeResult`(`kinds`: String, `types`: String, `protocols`: String): LocalizedString = LocalizedString(
                key = StringKey("calls.dev.probeResult", "calls_dev_probe_result", listOf("kinds", "types", "protocols"), 1),
                arguments = listOf(`kinds`, `types`, `protocols`),
            )
        }
    }
    object `Admin` {
        val `title`: LocalizedString get() = LocalizedString(
            key = StringKey("admin.title", "admin_title", emptyList(), 1),
        )
        val `subtitle`: LocalizedString get() = LocalizedString(
            key = StringKey("admin.subtitle", "admin_subtitle", emptyList(), 1),
        )
        val `someone`: LocalizedString get() = LocalizedString(
            key = StringKey("admin.someone", "admin_someone", emptyList(), 1),
        )
        val `users`: LocalizedString get() = LocalizedString(
            key = StringKey("admin.users", "admin_users", emptyList(), 1),
        )
        val `usersText`: LocalizedString get() = LocalizedString(
            key = StringKey("admin.usersText", "admin_users_text", emptyList(), 1),
        )
        val `accounts`: LocalizedString get() = LocalizedString(
            key = StringKey("admin.accounts", "admin_accounts", emptyList(), 1),
        )
        val `accountsText`: LocalizedString get() = LocalizedString(
            key = StringKey("admin.accountsText", "admin_accounts_text", emptyList(), 1),
        )
        val `workers`: LocalizedString get() = LocalizedString(
            key = StringKey("admin.workers", "admin_workers", emptyList(), 1),
        )
        val `workerGrantsTitle`: LocalizedString get() = LocalizedString(
            key = StringKey("admin.workerGrantsTitle", "admin_worker_grants_title", emptyList(), 1),
        )
        val `workerGrantsHelp`: LocalizedString get() = LocalizedString(
            key = StringKey("admin.workerGrantsHelp", "admin_worker_grants_help", emptyList(), 1),
        )
        val `workerGrantUser`: LocalizedString get() = LocalizedString(
            key = StringKey("admin.workerGrantUser", "admin_worker_grant_user", emptyList(), 1),
        )
        val `workerGrantSave`: LocalizedString get() = LocalizedString(
            key = StringKey("admin.workerGrantSave", "admin_worker_grant_save", emptyList(), 1),
        )
        val `workerGrantRevoke`: LocalizedString get() = LocalizedString(
            key = StringKey("admin.workerGrantRevoke", "admin_worker_grant_revoke", emptyList(), 1),
        )
        val `workerGrantActive`: LocalizedString get() = LocalizedString(
            key = StringKey("admin.workerGrantActive", "admin_worker_grant_active", emptyList(), 1),
        )
        val `workerGrantInactive`: LocalizedString get() = LocalizedString(
            key = StringKey("admin.workerGrantInactive", "admin_worker_grant_inactive", emptyList(), 1),
        )
        val `workersText`: LocalizedString get() = LocalizedString(
            key = StringKey("admin.workersText", "admin_workers_text", emptyList(), 1),
        )
        val `capabilitiesText`: LocalizedString get() = LocalizedString(
            key = StringKey("admin.capabilitiesText", "admin_capabilities_text", emptyList(), 1),
        )
        val `auditTitle`: LocalizedString get() = LocalizedString(
            key = StringKey("admin.auditTitle", "admin_audit_title", emptyList(), 1),
        )
        object `Audit` {
            val `access_share`: LocalizedString get() = LocalizedString(
                key = StringKey("admin.audit.access_share", "admin_audit_access_share", emptyList(), 1),
            )
            val `access_update`: LocalizedString get() = LocalizedString(
                key = StringKey("admin.audit.access_update", "admin_audit_access_update", emptyList(), 1),
            )
            val `agent_connect`: LocalizedString get() = LocalizedString(
                key = StringKey("admin.audit.agent_connect", "admin_audit_agent_connect", emptyList(), 1),
            )
            val `community_report_resolve`: LocalizedString get() = LocalizedString(
                key = StringKey("admin.audit.community_report_resolve", "admin_audit_community_report_resolve", emptyList(), 1),
            )
            val `device_pair`: LocalizedString get() = LocalizedString(
                key = StringKey("admin.audit.device_pair", "admin_audit_device_pair", emptyList(), 1),
            )
            val `device_revoke`: LocalizedString get() = LocalizedString(
                key = StringKey("admin.audit.device_revoke", "admin_audit_device_revoke", emptyList(), 1),
            )
            val `identity_access_update`: LocalizedString get() = LocalizedString(
                key = StringKey("admin.audit.identity_access_update", "admin_audit_identity_access_update", emptyList(), 1),
            )
            val `identity_bootstrap`: LocalizedString get() = LocalizedString(
                key = StringKey("admin.audit.identity_bootstrap", "admin_audit_identity_bootstrap", emptyList(), 1),
            )
            val `identity_invite`: LocalizedString get() = LocalizedString(
                key = StringKey("admin.audit.identity_invite", "admin_audit_identity_invite", emptyList(), 1),
            )
            val `identity_invite_redeem`: LocalizedString get() = LocalizedString(
                key = StringKey("admin.audit.identity_invite_redeem", "admin_audit_identity_invite_redeem", emptyList(), 1),
            )
            val `identity_reset_issue`: LocalizedString get() = LocalizedString(
                key = StringKey("admin.audit.identity_reset_issue", "admin_audit_identity_reset_issue", emptyList(), 1),
            )
            val `identity_reset_redeem`: LocalizedString get() = LocalizedString(
                key = StringKey("admin.audit.identity_reset_redeem", "admin_audit_identity_reset_redeem", emptyList(), 1),
            )
            val `personal_ring_enroll`: LocalizedString get() = LocalizedString(
                key = StringKey("admin.audit.personal_ring_enroll", "admin_audit_personal_ring_enroll", emptyList(), 1),
            )
            val `location_collector_enroll`: LocalizedString get() = LocalizedString(
                key = StringKey("admin.audit.location_collector_enroll", "admin_audit_location_collector_enroll", emptyList(), 1),
            )
            val `location_collector_disable`: LocalizedString get() = LocalizedString(
                key = StringKey("admin.audit.location_collector_disable", "admin_audit_location_collector_disable", emptyList(), 1),
            )
            val `nutrition_catalog_refresh`: LocalizedString get() = LocalizedString(
                key = StringKey("admin.audit.nutrition_catalog_refresh", "admin_audit_nutrition_catalog_refresh", emptyList(), 1),
            )
            val `nutrition_catalog_cancel`: LocalizedString get() = LocalizedString(
                key = StringKey("admin.audit.nutrition_catalog_cancel", "admin_audit_nutrition_catalog_cancel", emptyList(), 1),
            )
            val `nutrition_catalog_activate`: LocalizedString get() = LocalizedString(
                key = StringKey("admin.audit.nutrition_catalog_activate", "admin_audit_nutrition_catalog_activate", emptyList(), 1),
            )
            val `speech_connection_delete`: LocalizedString get() = LocalizedString(
                key = StringKey("admin.audit.speech_connection_delete", "admin_audit_speech_connection_delete", emptyList(), 1),
            )
            val `speech_connection_save`: LocalizedString get() = LocalizedString(
                key = StringKey("admin.audit.speech_connection_save", "admin_audit_speech_connection_save", emptyList(), 1),
            )
            val `speech_legacy_grant`: LocalizedString get() = LocalizedString(
                key = StringKey("admin.audit.speech_legacy_grant", "admin_audit_speech_legacy_grant", emptyList(), 1),
            )
            val `speech_preferences_save`: LocalizedString get() = LocalizedString(
                key = StringKey("admin.audit.speech_preferences_save", "admin_audit_speech_preferences_save", emptyList(), 1),
            )
            val `other`: LocalizedString get() = LocalizedString(
                key = StringKey("admin.audit.other", "admin_audit_other", emptyList(), 1),
            )
            val `identity_token_issue`: LocalizedString get() = LocalizedString(
                key = StringKey("admin.audit.identity_token_issue", "admin_audit_identity_token_issue", emptyList(), 1),
            )
            val `usage_limits_update`: LocalizedString get() = LocalizedString(
                key = StringKey("admin.audit.usage_limits_update", "admin_audit_usage_limits_update", emptyList(), 1),
            )
            val `pool_update`: LocalizedString get() = LocalizedString(
                key = StringKey("admin.audit.pool_update", "admin_audit_pool_update", emptyList(), 1),
            )
            val `resource_grant`: LocalizedString get() = LocalizedString(
                key = StringKey("admin.audit.resource_grant", "admin_audit_resource_grant", emptyList(), 1),
            )
            val `resource_members`: LocalizedString get() = LocalizedString(
                key = StringKey("admin.audit.resource_members", "admin_audit_resource_members", emptyList(), 1),
            )
            val `space_membership`: LocalizedString get() = LocalizedString(
                key = StringKey("admin.audit.space_membership", "admin_audit_space_membership", emptyList(), 1),
            )
            val `space_archive`: LocalizedString get() = LocalizedString(
                key = StringKey("admin.audit.space_archive", "admin_audit_space_archive", emptyList(), 1),
            )
            val `chat_settings`: LocalizedString get() = LocalizedString(
                key = StringKey("admin.audit.chat_settings", "admin_audit_chat_settings", emptyList(), 1),
            )
            val `plugin_settings`: LocalizedString get() = LocalizedString(
                key = StringKey("admin.audit.plugin_settings", "admin_audit_plugin_settings", emptyList(), 1),
            )
        }
        val `changed`: LocalizedString get() = LocalizedString(
            key = StringKey("admin.changed", "admin_changed", emptyList(), 1),
        )
        val `saved`: LocalizedString get() = LocalizedString(
            key = StringKey("admin.saved", "admin_saved", emptyList(), 1),
        )
        val `administrator`: LocalizedString get() = LocalizedString(
            key = StringKey("admin.administrator", "admin_administrator", emptyList(), 1),
        )
        object `Status` {
            val `active`: LocalizedString get() = LocalizedString(
                key = StringKey("admin.status.active", "admin_status_active", emptyList(), 1),
            )
            val `disabled`: LocalizedString get() = LocalizedString(
                key = StringKey("admin.status.disabled", "admin_status_disabled", emptyList(), 1),
            )
        }
        val `privateTitle`: LocalizedString get() = LocalizedString(
            key = StringKey("admin.privateTitle", "admin_private_title", emptyList(), 1),
        )
        val `privateText`: LocalizedString get() = LocalizedString(
            key = StringKey("admin.privateText", "admin_private_text", emptyList(), 1),
        )
        val `newInvite`: LocalizedString get() = LocalizedString(
            key = StringKey("admin.newInvite", "admin_new_invite", emptyList(), 1),
        )
        val `inviteTitle`: LocalizedString get() = LocalizedString(
            key = StringKey("admin.inviteTitle", "admin_invite_title", emptyList(), 1),
        )
        val `inviteText`: LocalizedString get() = LocalizedString(
            key = StringKey("admin.inviteText", "admin_invite_text", emptyList(), 1),
        )
        object `Group` {
            val `assistant`: LocalizedString get() = LocalizedString(
                key = StringKey("admin.group.assistant", "admin_group_assistant", emptyList(), 1),
            )
            val `nutrition`: LocalizedString get() = LocalizedString(
                key = StringKey("admin.group.nutrition", "admin_group_nutrition", emptyList(), 1),
            )
            val `personal`: LocalizedString get() = LocalizedString(
                key = StringKey("admin.group.personal", "admin_group_personal", emptyList(), 1),
            )
            val `tasks`: LocalizedString get() = LocalizedString(
                key = StringKey("admin.group.tasks", "admin_group_tasks", emptyList(), 1),
            )
            val `circle`: LocalizedString get() = LocalizedString(
                key = StringKey("admin.group.circle", "admin_group_circle", emptyList(), 1),
            )
            val `research`: LocalizedString get() = LocalizedString(
                key = StringKey("admin.group.research", "admin_group_research", emptyList(), 1),
            )
            val `other`: LocalizedString get() = LocalizedString(
                key = StringKey("admin.group.other", "admin_group_other", emptyList(), 1),
            )
            val `activity`: LocalizedString get() = LocalizedString(
                key = StringKey("admin.group.activity", "admin_group_activity", emptyList(), 1),
            )
            val `weather`: LocalizedString get() = LocalizedString(
                key = StringKey("admin.group.weather", "admin_group_weather", emptyList(), 1),
            )
            val `places`: LocalizedString get() = LocalizedString(
                key = StringKey("admin.group.places", "admin_group_places", emptyList(), 1),
            )
        }
        object `Scope` {
            val `session_control`: LocalizedString get() = LocalizedString(
                key = StringKey("admin.scope.session_control", "admin_scope_session_control", emptyList(), 1),
            )
            val `nutrition_read`: LocalizedString get() = LocalizedString(
                key = StringKey("admin.scope.nutrition_read", "admin_scope_nutrition_read", emptyList(), 1),
            )
            val `nutrition_write`: LocalizedString get() = LocalizedString(
                key = StringKey("admin.scope.nutrition_write", "admin_scope_nutrition_write", emptyList(), 1),
            )
            val `nutrition_profile`: LocalizedString get() = LocalizedString(
                key = StringKey("admin.scope.nutrition_profile", "admin_scope_nutrition_profile", emptyList(), 1),
            )
            val `personal_read`: LocalizedString get() = LocalizedString(
                key = StringKey("admin.scope.personal_read", "admin_scope_personal_read", emptyList(), 1),
            )
            val `personal_write`: LocalizedString get() = LocalizedString(
                key = StringKey("admin.scope.personal_write", "admin_scope_personal_write", emptyList(), 1),
            )
            val `tasks_read`: LocalizedString get() = LocalizedString(
                key = StringKey("admin.scope.tasks_read", "admin_scope_tasks_read", emptyList(), 1),
            )
            val `tasks_write`: LocalizedString get() = LocalizedString(
                key = StringKey("admin.scope.tasks_write", "admin_scope_tasks_write", emptyList(), 1),
            )
            val `timers_read`: LocalizedString get() = LocalizedString(
                key = StringKey("admin.scope.timers_read", "admin_scope_timers_read", emptyList(), 1),
            )
            val `timers_write`: LocalizedString get() = LocalizedString(
                key = StringKey("admin.scope.timers_write", "admin_scope_timers_write", emptyList(), 1),
            )
            val `challenges_read`: LocalizedString get() = LocalizedString(
                key = StringKey("admin.scope.challenges_read", "admin_scope_challenges_read", emptyList(), 1),
            )
            val `challenges_write`: LocalizedString get() = LocalizedString(
                key = StringKey("admin.scope.challenges_write", "admin_scope_challenges_write", emptyList(), 1),
            )
            val `research_read`: LocalizedString get() = LocalizedString(
                key = StringKey("admin.scope.research_read", "admin_scope_research_read", emptyList(), 1),
            )
            val `research_write`: LocalizedString get() = LocalizedString(
                key = StringKey("admin.scope.research_write", "admin_scope_research_write", emptyList(), 1),
            )
            val `calls_read`: LocalizedString get() = LocalizedString(
                key = StringKey("admin.scope.calls_read", "admin_scope_calls_read", emptyList(), 1),
            )
            val `calls_write`: LocalizedString get() = LocalizedString(
                key = StringKey("admin.scope.calls_write", "admin_scope_calls_write", emptyList(), 1),
            )
            val `activity_read`: LocalizedString get() = LocalizedString(
                key = StringKey("admin.scope.activity_read", "admin_scope_activity_read", emptyList(), 1),
            )
            val `activity_write`: LocalizedString get() = LocalizedString(
                key = StringKey("admin.scope.activity_write", "admin_scope_activity_write", emptyList(), 1),
            )
            val `weather_read`: LocalizedString get() = LocalizedString(
                key = StringKey("admin.scope.weather_read", "admin_scope_weather_read", emptyList(), 1),
            )
            val `places_read`: LocalizedString get() = LocalizedString(
                key = StringKey("admin.scope.places_read", "admin_scope_places_read", emptyList(), 1),
            )
        }
        val `createLink`: LocalizedString get() = LocalizedString(
            key = StringKey("admin.createLink", "admin_create_link", emptyList(), 1),
        )
        val `oneTime`: LocalizedString get() = LocalizedString(
            key = StringKey("admin.oneTime", "admin_one_time", emptyList(), 1),
        )
        val `oneTimeText`: LocalizedString get() = LocalizedString(
            key = StringKey("admin.oneTimeText", "admin_one_time_text", emptyList(), 1),
        )
        val `link`: LocalizedString get() = LocalizedString(
            key = StringKey("admin.link", "admin_link", emptyList(), 1),
        )
        val `copy`: LocalizedString get() = LocalizedString(
            key = StringKey("admin.copy", "admin_copy", emptyList(), 1),
        )
        val `copied`: LocalizedString get() = LocalizedString(
            key = StringKey("admin.copied", "admin_copied", emptyList(), 1),
        )
        val `copyManually`: LocalizedString get() = LocalizedString(
            key = StringKey("admin.copyManually", "admin_copy_manually", emptyList(), 1),
        )
        val `rights`: LocalizedString get() = LocalizedString(
            key = StringKey("admin.rights", "admin_rights", emptyList(), 1),
        )
        val `adminHasAll`: LocalizedString get() = LocalizedString(
            key = StringKey("admin.adminHasAll", "admin_admin_has_all", emptyList(), 1),
        )
        val `saveRights`: LocalizedString get() = LocalizedString(
            key = StringKey("admin.saveRights", "admin_save_rights", emptyList(), 1),
        )
        val `rightsSaved`: LocalizedString get() = LocalizedString(
            key = StringKey("admin.rightsSaved", "admin_rights_saved", emptyList(), 1),
        )
        val `limitsTitle`: LocalizedString get() = LocalizedString(
            key = StringKey("admin.limitsTitle", "admin_limits_title", emptyList(), 1),
        )
        object `Limit` {
            val `assistant`: LocalizedString get() = LocalizedString(
                key = StringKey("admin.limit.assistant", "admin_limit_assistant", emptyList(), 1),
            )
            val `research`: LocalizedString get() = LocalizedString(
                key = StringKey("admin.limit.research", "admin_limit_research", emptyList(), 1),
            )
            val `dictation_ms`: LocalizedString get() = LocalizedString(
                key = StringKey("admin.limit.dictation_ms", "admin_limit_dictation_ms", emptyList(), 1),
            )
            val `tts_chars`: LocalizedString get() = LocalizedString(
                key = StringKey("admin.limit.tts_chars", "admin_limit_tts_chars", emptyList(), 1),
            )
        }
        val `unlimited`: LocalizedString get() = LocalizedString(
            key = StringKey("admin.unlimited", "admin_unlimited", emptyList(), 1),
        )
        val `limitsHint`: LocalizedString get() = LocalizedString(
            key = StringKey("admin.limitsHint", "admin_limits_hint", emptyList(), 1),
        )
        val `saveLimits`: LocalizedString get() = LocalizedString(
            key = StringKey("admin.saveLimits", "admin_save_limits", emptyList(), 1),
        )
        val `limitsSaved`: LocalizedString get() = LocalizedString(
            key = StringKey("admin.limitsSaved", "admin_limits_saved", emptyList(), 1),
        )
        val `limitNumber`: LocalizedString get() = LocalizedString(
            key = StringKey("admin.limitNumber", "admin_limit_number", emptyList(), 1),
        )
        val `resetLink`: LocalizedString get() = LocalizedString(
            key = StringKey("admin.resetLink", "admin_reset_link", emptyList(), 1),
        )
        fun `resetText`(`name`: String): LocalizedString = LocalizedString(
            key = StringKey("admin.resetText", "admin_reset_text", listOf("name"), 1),
            arguments = listOf(`name`),
        )
        val `disable`: LocalizedString get() = LocalizedString(
            key = StringKey("admin.disable", "admin_disable", emptyList(), 1),
        )
        val `enable`: LocalizedString get() = LocalizedString(
            key = StringKey("admin.enable", "admin_enable", emptyList(), 1),
        )
        val `disabled`: LocalizedString get() = LocalizedString(
            key = StringKey("admin.disabled", "admin_disabled", emptyList(), 1),
        )
        val `enabled`: LocalizedString get() = LocalizedString(
            key = StringKey("admin.enabled", "admin_enabled", emptyList(), 1),
        )
        fun `disableTitle`(`name`: String): LocalizedString = LocalizedString(
            key = StringKey("admin.disableTitle", "admin_disable_title", listOf("name"), 1),
            arguments = listOf(`name`),
        )
        fun `disableText`(`name`: String): LocalizedString = LocalizedString(
            key = StringKey("admin.disableText", "admin_disable_text", listOf("name"), 1),
            arguments = listOf(`name`),
        )
        val `keepActive`: LocalizedString get() = LocalizedString(
            key = StringKey("admin.keepActive", "admin_keep_active", emptyList(), 1),
        )
        val `withoutSubscriptions`: LocalizedString get() = LocalizedString(
            key = StringKey("admin.withoutSubscriptions", "admin_without_subscriptions", emptyList(), 1),
        )
        val `saveSubscriptions`: LocalizedString get() = LocalizedString(
            key = StringKey("admin.saveSubscriptions", "admin_save_subscriptions", emptyList(), 1),
        )
        val `subscriptionsSaved`: LocalizedString get() = LocalizedString(
            key = StringKey("admin.subscriptionsSaved", "admin_subscriptions_saved", emptyList(), 1),
        )
        val `speechConnections`: LocalizedString get() = LocalizedString(
            key = StringKey("admin.speechConnections", "admin_speech_connections", emptyList(), 1),
        )
        val `shareAccount`: LocalizedString get() = LocalizedString(
            key = StringKey("admin.shareAccount", "admin_share_account", emptyList(), 1),
        )
        val `noAccounts`: LocalizedString get() = LocalizedString(
            key = StringKey("admin.noAccounts", "admin_no_accounts", emptyList(), 1),
        )
        val `accessRule`: LocalizedString get() = LocalizedString(
            key = StringKey("admin.accessRule", "admin_access_rule", emptyList(), 1),
        )
        val `accessRuleText`: LocalizedString get() = LocalizedString(
            key = StringKey("admin.accessRuleText", "admin_access_rule_text", emptyList(), 1),
        )
        val `accountsUnavailable`: LocalizedString get() = LocalizedString(
            key = StringKey("admin.accountsUnavailable", "admin_accounts_unavailable", emptyList(), 1),
        )
        val `accountsUnavailableText`: LocalizedString get() = LocalizedString(
            key = StringKey("admin.accountsUnavailableText", "admin_accounts_unavailable_text", emptyList(), 1),
        )
        val `connectTitle`: LocalizedString get() = LocalizedString(
            key = StringKey("admin.connectTitle", "admin_connect_title", emptyList(), 1),
        )
        val `connect`: LocalizedString get() = LocalizedString(
            key = StringKey("admin.connect", "admin_connect", emptyList(), 1),
        )
        val `connectUnavailable`: LocalizedString get() = LocalizedString(
            key = StringKey("admin.connectUnavailable", "admin_connect_unavailable", emptyList(), 1),
        )
        val `connectHint`: LocalizedString get() = LocalizedString(
            key = StringKey("admin.connectHint", "admin_connect_hint", emptyList(), 1),
        )
        val `connectStarted`: LocalizedString get() = LocalizedString(
            key = StringKey("admin.connectStarted", "admin_connect_started", emptyList(), 1),
        )
        val `loginTitle`: LocalizedString get() = LocalizedString(
            key = StringKey("admin.loginTitle", "admin_login_title", emptyList(), 1),
        )
        val `reconnect`: LocalizedString get() = LocalizedString(
            key = StringKey("admin.reconnect", "admin_reconnect", emptyList(), 1),
        )
        val `reconnectRequired`: LocalizedString get() = LocalizedString(
            key = StringKey("admin.reconnectRequired", "admin_reconnect_required", emptyList(), 1),
        )
        fun `reconnectOnMachine`(`machine`: String): LocalizedString = LocalizedString(
            key = StringKey("admin.reconnectOnMachine", "admin_reconnect_on_machine", listOf("machine"), 1),
            arguments = listOf(`machine`),
        )
        val `provider`: LocalizedString get() = LocalizedString(
            key = StringKey("admin.provider", "admin_provider", emptyList(), 1),
        )
        val `subscriptionName`: LocalizedString get() = LocalizedString(
            key = StringKey("admin.subscriptionName", "admin_subscription_name", emptyList(), 1),
        )
        val `subscriptionExample`: LocalizedString get() = LocalizedString(
            key = StringKey("admin.subscriptionExample", "admin_subscription_example", emptyList(), 1),
        )
        val `startLogin`: LocalizedString get() = LocalizedString(
            key = StringKey("admin.startLogin", "admin_start_login", emptyList(), 1),
        )
        val `subscriptions`: LocalizedString get() = LocalizedString(
            key = StringKey("admin.subscriptions", "admin_subscriptions", emptyList(), 1),
        )
        val `subscriptionsHint`: LocalizedString get() = LocalizedString(
            key = StringKey("admin.subscriptionsHint", "admin_subscriptions_hint", emptyList(), 1),
        )
        val `noSubscriptions`: LocalizedString get() = LocalizedString(
            key = StringKey("admin.noSubscriptions", "admin_no_subscriptions", emptyList(), 1),
        )
        fun `resetCredits`(`value`: String): LocalizedString = LocalizedString(
            key = StringKey("admin.resetCredits", "admin_reset_credits", listOf("value"), 1),
            arguments = listOf(`value`),
        )
        val `stale`: LocalizedString get() = LocalizedString(
            key = StringKey("admin.stale", "admin_stale", emptyList(), 1),
        )
        val `inUse`: LocalizedString get() = LocalizedString(
            key = StringKey("admin.inUse", "admin_in_use", emptyList(), 1),
        )
        val `paused`: LocalizedString get() = LocalizedString(
            key = StringKey("admin.paused", "admin_paused", emptyList(), 1),
        )
        val `pauseNew`: LocalizedString get() = LocalizedString(
            key = StringKey("admin.pauseNew", "admin_pause_new", emptyList(), 1),
        )
        val `resume`: LocalizedString get() = LocalizedString(
            key = StringKey("admin.resume", "admin_resume", emptyList(), 1),
        )
        fun `observed`(`when`: String): LocalizedString = LocalizedString(
            key = StringKey("admin.observed", "admin_observed", listOf("when"), 1),
            arguments = listOf(`when`),
        )
        object `Quota` {
            val `total`: LocalizedString get() = LocalizedString(
                key = StringKey("admin.quota.total", "admin_quota_total", emptyList(), 1),
            )
            val `requests`: LocalizedString get() = LocalizedString(
                key = StringKey("admin.quota.requests", "admin_quota_requests", emptyList(), 1),
            )
            val `week`: LocalizedString get() = LocalizedString(
                key = StringKey("admin.quota.week", "admin_quota_week", emptyList(), 1),
            )
            fun `days`(`count`: String): LocalizedString = LocalizedString(
                key = StringKey("admin.quota.days", "admin_quota_days", listOf("count"), 1),
                arguments = listOf(`count`),
            )
            fun `hours`(`count`: String): LocalizedString = LocalizedString(
                key = StringKey("admin.quota.hours", "admin_quota_hours", listOf("count"), 1),
                arguments = listOf(`count`),
            )
            fun `minutes`(`count`: String): LocalizedString = LocalizedString(
                key = StringKey("admin.quota.minutes", "admin_quota_minutes", listOf("count"), 1),
                arguments = listOf(`count`),
            )
            val `exhausted`: LocalizedString get() = LocalizedString(
                key = StringKey("admin.quota.exhausted", "admin_quota_exhausted", emptyList(), 1),
            )
            val `unknown`: LocalizedString get() = LocalizedString(
                key = StringKey("admin.quota.unknown", "admin_quota_unknown", emptyList(), 1),
            )
            fun `remaining`(`value`: String): LocalizedString = LocalizedString(
                key = StringKey("admin.quota.remaining", "admin_quota_remaining", listOf("value"), 1),
                arguments = listOf(`value`),
            )
            val `waiting`: LocalizedString get() = LocalizedString(
                key = StringKey("admin.quota.waiting", "admin_quota_waiting", emptyList(), 1),
            )
            val `awaiting`: LocalizedString get() = LocalizedString(
                key = StringKey("admin.quota.awaiting", "admin_quota_awaiting", emptyList(), 1),
            )
            fun `resets`(`when`: String): LocalizedString = LocalizedString(
                key = StringKey("admin.quota.resets", "admin_quota_resets", listOf("when"), 1),
                arguments = listOf(`when`),
            )
        }
        val `connections`: LocalizedString get() = LocalizedString(
            key = StringKey("admin.connections", "admin_connections", emptyList(), 1),
        )
        fun `refreshOwner`(`owner`: String): LocalizedString = LocalizedString(
            key = StringKey("admin.refreshOwner", "admin_refresh_owner", listOf("owner"), 1),
            arguments = listOf(`owner`),
        )
        val `turnOff`: LocalizedString get() = LocalizedString(
            key = StringKey("admin.turnOff", "admin_turn_off", emptyList(), 1),
        )
        val `turnOn`: LocalizedString get() = LocalizedString(
            key = StringKey("admin.turnOn", "admin_turn_on", emptyList(), 1),
        )
        val `policy`: LocalizedString get() = LocalizedString(
            key = StringKey("admin.policy", "admin_policy", emptyList(), 1),
        )
        val `policyText`: LocalizedString get() = LocalizedString(
            key = StringKey("admin.policyText", "admin_policy_text", emptyList(), 1),
        )
        val `policyEdit`: LocalizedString get() = LocalizedString(
            key = StringKey("admin.policyEdit", "admin_policy_edit", emptyList(), 1),
        )
        val `policyChat`: LocalizedString get() = LocalizedString(
            key = StringKey("admin.policyChat", "admin_policy_chat", emptyList(), 1),
        )
        val `policyBackground`: LocalizedString get() = LocalizedString(
            key = StringKey("admin.policyBackground", "admin_policy_background", emptyList(), 1),
        )
        val `reserve`: LocalizedString get() = LocalizedString(
            key = StringKey("admin.reserve", "admin_reserve", emptyList(), 1),
        )
        val `automatic`: LocalizedString get() = LocalizedString(
            key = StringKey("admin.automatic", "admin_automatic", emptyList(), 1),
        )
        val `noReserve`: LocalizedString get() = LocalizedString(
            key = StringKey("admin.noReserve", "admin_no_reserve", emptyList(), 1),
        )
        val `policyHint`: LocalizedString get() = LocalizedString(
            key = StringKey("admin.policyHint", "admin_policy_hint", emptyList(), 1),
        )
        fun `unresolved`(`kind`: String): LocalizedString = LocalizedString(
            key = StringKey("admin.unresolved", "admin_unresolved", listOf("kind"), 1),
            arguments = listOf(`kind`),
        )
        object `Login` {
            val `checking`: LocalizedString get() = LocalizedString(
                key = StringKey("admin.login.checking", "admin_login_checking", emptyList(), 1),
            )
            val `unknown`: LocalizedString get() = LocalizedString(
                key = StringKey("admin.login.unknown", "admin_login_unknown", emptyList(), 1),
            )
            val `open`: LocalizedString get() = LocalizedString(
                key = StringKey("admin.login.open", "admin_login_open", emptyList(), 1),
            )
            val `enterCode`: LocalizedString get() = LocalizedString(
                key = StringKey("admin.login.enterCode", "admin_login_enter_code", emptyList(), 1),
            )
            val `pasteHint`: LocalizedString get() = LocalizedString(
                key = StringKey("admin.login.pasteHint", "admin_login_paste_hint", emptyList(), 1),
            )
            val `code`: LocalizedString get() = LocalizedString(
                key = StringKey("admin.login.code", "admin_login_code", emptyList(), 1),
            )
            val `finish`: LocalizedString get() = LocalizedString(
                key = StringKey("admin.login.finish", "admin_login_finish", emptyList(), 1),
            )
            val `after`: LocalizedString get() = LocalizedString(
                key = StringKey("admin.login.after", "admin_login_after", emptyList(), 1),
            )
            val `codeSent`: LocalizedString get() = LocalizedString(
                key = StringKey("admin.login.codeSent", "admin_login_code_sent", emptyList(), 1),
            )
            val `readyText`: LocalizedString get() = LocalizedString(
                key = StringKey("admin.login.readyText", "admin_login_ready_text", emptyList(), 1),
            )
            val `sameAccount`: LocalizedString get() = LocalizedString(
                key = StringKey("admin.login.sameAccount", "admin_login_same_account", emptyList(), 1),
            )
            val `reconnected`: LocalizedString get() = LocalizedString(
                key = StringKey("admin.login.reconnected", "admin_login_reconnected", emptyList(), 1),
            )
            val `reconnectedText`: LocalizedString get() = LocalizedString(
                key = StringKey("admin.login.reconnectedText", "admin_login_reconnected_text", emptyList(), 1),
            )
            val `cancel`: LocalizedString get() = LocalizedString(
                key = StringKey("admin.login.cancel", "admin_login_cancel", emptyList(), 1),
            )
            val `cancelled`: LocalizedString get() = LocalizedString(
                key = StringKey("admin.login.cancelled", "admin_login_cancelled", emptyList(), 1),
            )
            object `State` {
                val `queued`: LocalizedString get() = LocalizedString(
                    key = StringKey("admin.login.state.queued", "admin_login_state_queued", emptyList(), 1),
                )
                val `starting`: LocalizedString get() = LocalizedString(
                    key = StringKey("admin.login.state.starting", "admin_login_state_starting", emptyList(), 1),
                )
                fun `waiting_user`(`provider`: String): LocalizedString = LocalizedString(
                    key = StringKey("admin.login.state.waiting_user", "admin_login_state_waiting_user", listOf("provider"), 1),
                    arguments = listOf(`provider`),
                )
                val `verifying`: LocalizedString get() = LocalizedString(
                    key = StringKey("admin.login.state.verifying", "admin_login_state_verifying", emptyList(), 1),
                )
                val `ready`: LocalizedString get() = LocalizedString(
                    key = StringKey("admin.login.state.ready", "admin_login_state_ready", emptyList(), 1),
                )
                val `cancelled`: LocalizedString get() = LocalizedString(
                    key = StringKey("admin.login.state.cancelled", "admin_login_state_cancelled", emptyList(), 1),
                )
                val `expired`: LocalizedString get() = LocalizedString(
                    key = StringKey("admin.login.state.expired", "admin_login_state_expired", emptyList(), 1),
                )
                val `failed`: LocalizedString get() = LocalizedString(
                    key = StringKey("admin.login.state.failed", "admin_login_state_failed", emptyList(), 1),
                )
            }
            object `Error` {
                val `interrupted`: LocalizedString get() = LocalizedString(
                    key = StringKey("admin.login.error.interrupted", "admin_login_error_interrupted", emptyList(), 1),
                )
                val `expired`: LocalizedString get() = LocalizedString(
                    key = StringKey("admin.login.error.expired", "admin_login_error_expired", emptyList(), 1),
                )
                val `login_failed`: LocalizedString get() = LocalizedString(
                    key = StringKey("admin.login.error.login_failed", "admin_login_error_login_failed", emptyList(), 1),
                )
                val `login_start_failed`: LocalizedString get() = LocalizedString(
                    key = StringKey("admin.login.error.login_start_failed", "admin_login_error_login_start_failed", emptyList(), 1),
                )
                val `provider_login_failed`: LocalizedString get() = LocalizedString(
                    key = StringKey("admin.login.error.provider_login_failed", "admin_login_error_provider_login_failed", emptyList(), 1),
                )
                val `authorization_rejected`: LocalizedString get() = LocalizedString(
                    key = StringKey("admin.login.error.authorization_rejected", "admin_login_error_authorization_rejected", emptyList(), 1),
                )
                val `provider_access_denied`: LocalizedString get() = LocalizedString(
                    key = StringKey("admin.login.error.provider_access_denied", "admin_login_error_provider_access_denied", emptyList(), 1),
                )
                val `provider_tls_failed`: LocalizedString get() = LocalizedString(
                    key = StringKey("admin.login.error.provider_tls_failed", "admin_login_error_provider_tls_failed", emptyList(), 1),
                )
                val `provider_network_failed`: LocalizedString get() = LocalizedString(
                    key = StringKey("admin.login.error.provider_network_failed", "admin_login_error_provider_network_failed", emptyList(), 1),
                )
                val `credential_storage_failed`: LocalizedString get() = LocalizedString(
                    key = StringKey("admin.login.error.credential_storage_failed", "admin_login_error_credential_storage_failed", emptyList(), 1),
                )
                val `credential_export_failed`: LocalizedString get() = LocalizedString(
                    key = StringKey("admin.login.error.credential_export_failed", "admin_login_error_credential_export_failed", emptyList(), 1),
                )
                val `credential_import_failed`: LocalizedString get() = LocalizedString(
                    key = StringKey("admin.login.error.credential_import_failed", "admin_login_error_credential_import_failed", emptyList(), 1),
                )
                val `account_identity_failed`: LocalizedString get() = LocalizedString(
                    key = StringKey("admin.login.error.account_identity_failed", "admin_login_error_account_identity_failed", emptyList(), 1),
                )
                val `account_registration_failed`: LocalizedString get() = LocalizedString(
                    key = StringKey("admin.login.error.account_registration_failed", "admin_login_error_account_registration_failed", emptyList(), 1),
                )
                val `account_mismatch`: LocalizedString get() = LocalizedString(
                    key = StringKey("admin.login.error.account_mismatch", "admin_login_error_account_mismatch", emptyList(), 1),
                )
                val `connection_changed`: LocalizedString get() = LocalizedString(
                    key = StringKey("admin.login.error.connection_changed", "admin_login_error_connection_changed", emptyList(), 1),
                )
                val `login_cleanup_failed`: LocalizedString get() = LocalizedString(
                    key = StringKey("admin.login.error.login_cleanup_failed", "admin_login_error_login_cleanup_failed", emptyList(), 1),
                )
            }
        }
        val `workersUnavailable`: LocalizedString get() = LocalizedString(
            key = StringKey("admin.workersUnavailable", "admin_workers_unavailable", emptyList(), 1),
        )
        val `workersUnavailableText`: LocalizedString get() = LocalizedString(
            key = StringKey("admin.workersUnavailableText", "admin_workers_unavailable_text", emptyList(), 1),
        )
        val `runs`: LocalizedString get() = LocalizedString(
            key = StringKey("admin.runs", "admin_runs", emptyList(), 1),
        )
        val `noRuns`: LocalizedString get() = LocalizedString(
            key = StringKey("admin.noRuns", "admin_no_runs", emptyList(), 1),
        )
        val `newRun`: LocalizedString get() = LocalizedString(
            key = StringKey("admin.newRun", "admin_new_run", emptyList(), 1),
        )
        object `RunState` {
            val `queued`: LocalizedString get() = LocalizedString(
                key = StringKey("admin.runState.queued", "admin_run_state_queued", emptyList(), 1),
            )
            val `starting`: LocalizedString get() = LocalizedString(
                key = StringKey("admin.runState.starting", "admin_run_state_starting", emptyList(), 1),
            )
            val `pausing`: LocalizedString get() = LocalizedString(
                key = StringKey("admin.runState.pausing", "admin_run_state_pausing", emptyList(), 1),
            )
            val `paused`: LocalizedString get() = LocalizedString(
                key = StringKey("admin.runState.paused", "admin_run_state_paused", emptyList(), 1),
            )
            val `resuming`: LocalizedString get() = LocalizedString(
                key = StringKey("admin.runState.resuming", "admin_run_state_resuming", emptyList(), 1),
            )
            val `running`: LocalizedString get() = LocalizedString(
                key = StringKey("admin.runState.running", "admin_run_state_running", emptyList(), 1),
            )
            val `waiting_input`: LocalizedString get() = LocalizedString(
                key = StringKey("admin.runState.waiting_input", "admin_run_state_waiting_input", emptyList(), 1),
            )
            val `blocked`: LocalizedString get() = LocalizedString(
                key = StringKey("admin.runState.blocked", "admin_run_state_blocked", emptyList(), 1),
            )
            val `ready`: LocalizedString get() = LocalizedString(
                key = StringKey("admin.runState.ready", "admin_run_state_ready", emptyList(), 1),
            )
            val `failed`: LocalizedString get() = LocalizedString(
                key = StringKey("admin.runState.failed", "admin_run_state_failed", emptyList(), 1),
            )
            val `cancelled`: LocalizedString get() = LocalizedString(
                key = StringKey("admin.runState.cancelled", "admin_run_state_cancelled", emptyList(), 1),
            )
        }
        object `Reason` {
            val `quota_wait`: LocalizedString get() = LocalizedString(
                key = StringKey("admin.reason.quota_wait", "admin_reason_quota_wait", emptyList(), 1),
            )
            val `quota_exhausted`: LocalizedString get() = LocalizedString(
                key = StringKey("admin.reason.quota_exhausted", "admin_reason_quota_exhausted", emptyList(), 1),
            )
            val `busy`: LocalizedString get() = LocalizedString(
                key = StringKey("admin.reason.busy", "admin_reason_busy", emptyList(), 1),
            )
            val `executor_unavailable`: LocalizedString get() = LocalizedString(
                key = StringKey("admin.reason.executor_unavailable", "admin_reason_executor_unavailable", emptyList(), 1),
            )
            val `deadline`: LocalizedString get() = LocalizedString(
                key = StringKey("admin.reason.deadline", "admin_reason_deadline", emptyList(), 1),
            )
            val `outcome_unknown`: LocalizedString get() = LocalizedString(
                key = StringKey("admin.reason.outcome_unknown", "admin_reason_outcome_unknown", emptyList(), 1),
            )
            val `auth_unavailable`: LocalizedString get() = LocalizedString(
                key = StringKey("admin.reason.auth_unavailable", "admin_reason_auth_unavailable", emptyList(), 1),
            )
        }
        val `stopRequested`: LocalizedString get() = LocalizedString(
            key = StringKey("admin.stopRequested", "admin_stop_requested", emptyList(), 1),
        )
        val `machines`: LocalizedString get() = LocalizedString(
            key = StringKey("admin.machines", "admin_machines", emptyList(), 1),
        )
        val `online`: LocalizedString get() = LocalizedString(
            key = StringKey("admin.online", "admin_online", emptyList(), 1),
        )
        val `offline`: LocalizedString get() = LocalizedString(
            key = StringKey("admin.offline", "admin_offline", emptyList(), 1),
        )
        fun `workspaces`(quantity: Double, `count`: String): LocalizedString = LocalizedString(
            key = StringKey("admin.workspaces", "admin_workspaces", listOf("count"), 3),
            arguments = listOf(`count`),
            quantity = quantity,
        )
        val `stopNewRuns`: LocalizedString get() = LocalizedString(
            key = StringKey("admin.stopNewRuns", "admin_stop_new_runs", emptyList(), 1),
        )
        val `machineName`: LocalizedString get() = LocalizedString(
            key = StringKey("admin.machineName", "admin_machine_name", emptyList(), 1),
        )
        val `addMachine`: LocalizedString get() = LocalizedString(
            key = StringKey("admin.addMachine", "admin_add_machine", emptyList(), 1),
        )
        val `machineAdded`: LocalizedString get() = LocalizedString(
            key = StringKey("admin.machineAdded", "admin_machine_added", emptyList(), 1),
        )
        val `firstConnect`: LocalizedString get() = LocalizedString(
            key = StringKey("admin.firstConnect", "admin_first_connect", emptyList(), 1),
        )
        val `firstConnectText`: LocalizedString get() = LocalizedString(
            key = StringKey("admin.firstConnectText", "admin_first_connect_text", emptyList(), 1),
        )
        val `setups`: LocalizedString get() = LocalizedString(
            key = StringKey("admin.setups", "admin_setups", emptyList(), 1),
        )
        val `paseoSetupText`: LocalizedString get() = LocalizedString(
            key = StringKey("admin.paseoSetupText", "admin_paseo_setup_text", emptyList(), 1),
        )
        val `workerHarness`: LocalizedString get() = LocalizedString(
            key = StringKey("admin.workerHarness", "admin_worker_harness", emptyList(), 1),
        )
        val `machine`: LocalizedString get() = LocalizedString(
            key = StringKey("admin.machine", "admin_machine", emptyList(), 1),
        )
        val `workspace`: LocalizedString get() = LocalizedString(
            key = StringKey("admin.workspace", "admin_workspace", emptyList(), 1),
        )
        val `binding`: LocalizedString get() = LocalizedString(
            key = StringKey("admin.binding", "admin_binding", emptyList(), 1),
        )
        val `verifyWorker`: LocalizedString get() = LocalizedString(
            key = StringKey("admin.verifyWorker", "admin_verify_worker", emptyList(), 1),
        )
        val `setupStarted`: LocalizedString get() = LocalizedString(
            key = StringKey("admin.setupStarted", "admin_setup_started", emptyList(), 1),
        )
        object `SetupStep` {
            val `inspect`: LocalizedString get() = LocalizedString(
                key = StringKey("admin.setupStep.inspect", "admin_setup_step_inspect", emptyList(), 1),
            )
            val `configure`: LocalizedString get() = LocalizedString(
                key = StringKey("admin.setupStep.configure", "admin_setup_step_configure", emptyList(), 1),
            )
            val `authenticate`: LocalizedString get() = LocalizedString(
                key = StringKey("admin.setupStep.authenticate", "admin_setup_step_authenticate", emptyList(), 1),
            )
            val `verify`: LocalizedString get() = LocalizedString(
                key = StringKey("admin.setupStep.verify", "admin_setup_step_verify", emptyList(), 1),
            )
            val `register`: LocalizedString get() = LocalizedString(
                key = StringKey("admin.setupStep.register", "admin_setup_step_register", emptyList(), 1),
            )
        }
        object `SetupReason` {
            val `daemon_unavailable`: LocalizedString get() = LocalizedString(
                key = StringKey("admin.setupReason.daemon_unavailable", "admin_setup_reason_daemon_unavailable", emptyList(), 1),
            )
            val `version_mismatch`: LocalizedString get() = LocalizedString(
                key = StringKey("admin.setupReason.version_mismatch", "admin_setup_reason_version_mismatch", emptyList(), 1),
            )
            fun `provider_unavailable`(`provider`: String): LocalizedString = LocalizedString(
                key = StringKey("admin.setupReason.provider_unavailable", "admin_setup_reason_provider_unavailable", listOf("provider"), 1),
                arguments = listOf(`provider`),
            )
            fun `identity_unavailable`(`provider`: String): LocalizedString = LocalizedString(
                key = StringKey("admin.setupReason.identity_unavailable", "admin_setup_reason_identity_unavailable", listOf("provider"), 1),
                arguments = listOf(`provider`),
            )
            fun `model_unavailable`(`provider`: String): LocalizedString = LocalizedString(
                key = StringKey("admin.setupReason.model_unavailable", "admin_setup_reason_model_unavailable", listOf("provider"), 1),
                arguments = listOf(`provider`),
            )
            val `sandbox_unavailable`: LocalizedString get() = LocalizedString(
                key = StringKey("admin.setupReason.sandbox_unavailable", "admin_setup_reason_sandbox_unavailable", emptyList(), 1),
            )
            val `registration_failed`: LocalizedString get() = LocalizedString(
                key = StringKey("admin.setupReason.registration_failed", "admin_setup_reason_registration_failed", emptyList(), 1),
            )
        }
        val `setupFailedText`: LocalizedString get() = LocalizedString(
            key = StringKey("admin.setupFailedText", "admin_setup_failed_text", emptyList(), 1),
        )
        object `SetupState` {
            val `pending`: LocalizedString get() = LocalizedString(
                key = StringKey("admin.setupState.pending", "admin_setup_state_pending", emptyList(), 1),
            )
            val `running`: LocalizedString get() = LocalizedString(
                key = StringKey("admin.setupState.running", "admin_setup_state_running", emptyList(), 1),
            )
            val `waiting_user`: LocalizedString get() = LocalizedString(
                key = StringKey("admin.setupState.waiting_user", "admin_setup_state_waiting_user", emptyList(), 1),
            )
            val `ready`: LocalizedString get() = LocalizedString(
                key = StringKey("admin.setupState.ready", "admin_setup_state_ready", emptyList(), 1),
            )
            val `failed`: LocalizedString get() = LocalizedString(
                key = StringKey("admin.setupState.failed", "admin_setup_state_failed", emptyList(), 1),
            )
            val `cancelled`: LocalizedString get() = LocalizedString(
                key = StringKey("admin.setupState.cancelled", "admin_setup_state_cancelled", emptyList(), 1),
            )
        }
        fun `nativeWorkerSignIn`(`provider`: String): LocalizedString = LocalizedString(
            key = StringKey("admin.nativeWorkerSignIn", "admin_native_worker_sign_in", listOf("provider"), 1),
            arguments = listOf(`provider`),
        )
        val `verifyAgain`: LocalizedString get() = LocalizedString(
            key = StringKey("admin.verifyAgain", "admin_verify_again", emptyList(), 1),
        )
        val `cancelSetup`: LocalizedString get() = LocalizedString(
            key = StringKey("admin.cancelSetup", "admin_cancel_setup", emptyList(), 1),
        )
        val `executors`: LocalizedString get() = LocalizedString(
            key = StringKey("admin.executors", "admin_executors", emptyList(), 1),
        )
        val `allowAgent`: LocalizedString get() = LocalizedString(
            key = StringKey("admin.allowAgent", "admin_allow_agent", emptyList(), 1),
        )
        val `forbidAgent`: LocalizedString get() = LocalizedString(
            key = StringKey("admin.forbidAgent", "admin_forbid_agent", emptyList(), 1),
        )
        val `profiles`: LocalizedString get() = LocalizedString(
            key = StringKey("admin.profiles", "admin_profiles", emptyList(), 1),
        )
        object `Permissions` {
            val `read_only`: LocalizedString get() = LocalizedString(
                key = StringKey("admin.permissions.read_only", "admin_permissions_read_only", emptyList(), 1),
            )
            val `workspace_write`: LocalizedString get() = LocalizedString(
                key = StringKey("admin.permissions.workspace_write", "admin_permissions_workspace_write", emptyList(), 1),
            )
        }
        val `permissionsTitle`: LocalizedString get() = LocalizedString(
            key = StringKey("admin.permissionsTitle", "admin_permissions_title", emptyList(), 1),
        )
        val `permissionsHint`: LocalizedString get() = LocalizedString(
            key = StringKey("admin.permissionsHint", "admin_permissions_hint", emptyList(), 1),
        )
        val `model`: LocalizedString get() = LocalizedString(
            key = StringKey("admin.model", "admin_model", emptyList(), 1),
        )
        val `runGoal`: LocalizedString get() = LocalizedString(
            key = StringKey("admin.runGoal", "admin_run_goal", emptyList(), 1),
        )
        val `executor`: LocalizedString get() = LocalizedString(
            key = StringKey("admin.executor", "admin_executor", emptyList(), 1),
        )
        val `reserveExecutor`: LocalizedString get() = LocalizedString(
            key = StringKey("admin.reserveExecutor", "admin_reserve_executor", emptyList(), 1),
        )
        val `noRotation`: LocalizedString get() = LocalizedString(
            key = StringKey("admin.noRotation", "admin_no_rotation", emptyList(), 1),
        )
        val `hours`: LocalizedString get() = LocalizedString(
            key = StringKey("admin.hours", "admin_hours", emptyList(), 1),
        )
        val `start`: LocalizedString get() = LocalizedString(
            key = StringKey("admin.start", "admin_start", emptyList(), 1),
        )
        val `runStarted`: LocalizedString get() = LocalizedString(
            key = StringKey("admin.runStarted", "admin_run_started", emptyList(), 1),
        )
        val `runInvalid`: LocalizedString get() = LocalizedString(
            key = StringKey("admin.runInvalid", "admin_run_invalid", emptyList(), 1),
        )
        val `run`: LocalizedString get() = LocalizedString(
            key = StringKey("admin.run", "admin_run", emptyList(), 1),
        )
        fun `deadline`(`when`: String): LocalizedString = LocalizedString(
            key = StringKey("admin.deadline", "admin_deadline", listOf("when"), 1),
            arguments = listOf(`when`),
        )
        fun `workspaceIs`(`workspace`: String): LocalizedString = LocalizedString(
            key = StringKey("admin.workspaceIs", "admin_workspace_is", listOf("workspace"), 1),
            arguments = listOf(`workspace`),
        )
        val `stopWaitTitle`: LocalizedString get() = LocalizedString(
            key = StringKey("admin.stopWaitTitle", "admin_stop_wait_title", emptyList(), 1),
        )
        val `stopWaitText`: LocalizedString get() = LocalizedString(
            key = StringKey("admin.stopWaitText", "admin_stop_wait_text", emptyList(), 1),
        )
        val `needAnswer`: LocalizedString get() = LocalizedString(
            key = StringKey("admin.needAnswer", "admin_need_answer", emptyList(), 1),
        )
        val `answerContinue`: LocalizedString get() = LocalizedString(
            key = StringKey("admin.answerContinue", "admin_answer_continue", emptyList(), 1),
        )
        val `answered`: LocalizedString get() = LocalizedString(
            key = StringKey("admin.answered", "admin_answered", emptyList(), 1),
        )
        val `unknownEffects`: LocalizedString get() = LocalizedString(
            key = StringKey("admin.unknownEffects", "admin_unknown_effects", emptyList(), 1),
        )
        val `unknownEffectsText`: LocalizedString get() = LocalizedString(
            key = StringKey("admin.unknownEffectsText", "admin_unknown_effects_text", emptyList(), 1),
        )
        val `effectsReviewed`: LocalizedString get() = LocalizedString(
            key = StringKey("admin.effectsReviewed", "admin_effects_reviewed", emptyList(), 1),
        )
        val `preparePause`: LocalizedString get() = LocalizedString(
            key = StringKey("admin.preparePause", "admin_prepare_pause", emptyList(), 1),
        )
        val `stopAndCancel`: LocalizedString get() = LocalizedString(
            key = StringKey("admin.stopAndCancel", "admin_stop_and_cancel", emptyList(), 1),
        )
        val `readyTitle`: LocalizedString get() = LocalizedString(
            key = StringKey("admin.readyTitle", "admin_ready_title", emptyList(), 1),
        )
        val `readyText`: LocalizedString get() = LocalizedString(
            key = StringKey("admin.readyText", "admin_ready_text", emptyList(), 1),
        )
        val `artifacts`: LocalizedString get() = LocalizedString(
            key = StringKey("admin.artifacts", "admin_artifacts", emptyList(), 1),
        )
        val `progress`: LocalizedString get() = LocalizedString(
            key = StringKey("admin.progress", "admin_progress", emptyList(), 1),
        )
        val `noProgress`: LocalizedString get() = LocalizedString(
            key = StringKey("admin.noProgress", "admin_no_progress", emptyList(), 1),
        )
        val `continueRun`: LocalizedString get() = LocalizedString(
            key = StringKey("admin.continueRun", "admin_continue_run", emptyList(), 1),
        )
        val `resumed`: LocalizedString get() = LocalizedString(
            key = StringKey("admin.resumed", "admin_resumed", emptyList(), 1),
        )
        val `followupTitle`: LocalizedString get() = LocalizedString(
            key = StringKey("admin.followupTitle", "admin_followup_title", emptyList(), 1),
        )
        val `followupText`: LocalizedString get() = LocalizedString(
            key = StringKey("admin.followupText", "admin_followup_text", emptyList(), 1),
        )
        val `followupLabel`: LocalizedString get() = LocalizedString(
            key = StringKey("admin.followupLabel", "admin_followup_label", emptyList(), 1),
        )
        val `sendFollowup`: LocalizedString get() = LocalizedString(
            key = StringKey("admin.sendFollowup", "admin_send_followup", emptyList(), 1),
        )
        val `followupSent`: LocalizedString get() = LocalizedString(
            key = StringKey("admin.followupSent", "admin_followup_sent", emptyList(), 1),
        )
        val `requestStop`: LocalizedString get() = LocalizedString(
            key = StringKey("admin.requestStop", "admin_request_stop", emptyList(), 1),
        )
        val `stopAsked`: LocalizedString get() = LocalizedString(
            key = StringKey("admin.stopAsked", "admin_stop_asked", emptyList(), 1),
        )
        object `Auth` {
            val `ready`: LocalizedString get() = LocalizedString(
                key = StringKey("admin.auth.ready", "admin_auth_ready", emptyList(), 1),
            )
            val `auth_required`: LocalizedString get() = LocalizedString(
                key = StringKey("admin.auth.auth_required", "admin_auth_auth_required", emptyList(), 1),
            )
            val `identity_changed`: LocalizedString get() = LocalizedString(
                key = StringKey("admin.auth.identity_changed", "admin_auth_identity_changed", emptyList(), 1),
            )
            val `expired`: LocalizedString get() = LocalizedString(
                key = StringKey("admin.auth.expired", "admin_auth_expired", emptyList(), 1),
            )
            val `failed`: LocalizedString get() = LocalizedString(
                key = StringKey("admin.auth.failed", "admin_auth_failed", emptyList(), 1),
            )
            val `refreshing`: LocalizedString get() = LocalizedString(
                key = StringKey("admin.auth.refreshing", "admin_auth_refreshing", emptyList(), 1),
            )
        }
        val `accessChecking`: LocalizedString get() = LocalizedString(
            key = StringKey("admin.accessChecking", "admin_access_checking", emptyList(), 1),
        )
        val `accessUnknown`: LocalizedString get() = LocalizedString(
            key = StringKey("admin.accessUnknown", "admin_access_unknown", emptyList(), 1),
        )
        fun `accessPeople`(`names`: String): LocalizedString = LocalizedString(
            key = StringKey("admin.accessPeople", "admin_access_people", listOf("names"), 1),
            arguments = listOf(`names`),
        )
        val `selectedPeople`: LocalizedString get() = LocalizedString(
            key = StringKey("admin.selectedPeople", "admin_selected_people", emptyList(), 1),
        )
        val `manageAccess`: LocalizedString get() = LocalizedString(
            key = StringKey("admin.manageAccess", "admin_manage_access", emptyList(), 1),
        )
        val `accessNobody`: LocalizedString get() = LocalizedString(
            key = StringKey("admin.accessNobody", "admin_access_nobody", emptyList(), 1),
        )
        val `finishAccess`: LocalizedString get() = LocalizedString(
            key = StringKey("admin.finishAccess", "admin_finish_access", emptyList(), 1),
        )
        val `accessWho`: LocalizedString get() = LocalizedString(
            key = StringKey("admin.accessWho", "admin_access_who", emptyList(), 1),
        )
        val `accessExplicit`: LocalizedString get() = LocalizedString(
            key = StringKey("admin.accessExplicit", "admin_access_explicit", emptyList(), 1),
        )
        val `onlyMe`: LocalizedString get() = LocalizedString(
            key = StringKey("admin.onlyMe", "admin_only_me", emptyList(), 1),
        )
    }
    object `Shell` {
        val `glasses`: LocalizedString get() = LocalizedString(
            key = StringKey("shell.glasses", "shell_glasses", emptyList(), 1),
        )
        val `failed`: LocalizedString get() = LocalizedString(
            key = StringKey("shell.failed", "shell_failed", emptyList(), 1),
        )
    }
    object `Devices` {
        val `title`: LocalizedString get() = LocalizedString(
            key = StringKey("devices.title", "devices_title", emptyList(), 1),
        )
        val `text`: LocalizedString get() = LocalizedString(
            key = StringKey("devices.text", "devices_text", emptyList(), 1),
        )
        val `add`: LocalizedString get() = LocalizedString(
            key = StringKey("devices.add", "devices_add", emptyList(), 1),
        )
        val `addTitle`: LocalizedString get() = LocalizedString(
            key = StringKey("devices.addTitle", "devices_add_title", emptyList(), 1),
        )
        val `addText`: LocalizedString get() = LocalizedString(
            key = StringKey("devices.addText", "devices_add_text", emptyList(), 1),
        )
        val `code`: LocalizedString get() = LocalizedString(
            key = StringKey("devices.code", "devices_code", emptyList(), 1),
        )
        val `codeInvalid`: LocalizedString get() = LocalizedString(
            key = StringKey("devices.codeInvalid", "devices_code_invalid", emptyList(), 1),
        )
        val `name`: LocalizedString get() = LocalizedString(
            key = StringKey("devices.name", "devices_name", emptyList(), 1),
        )
        val `namePlaceholder`: LocalizedString get() = LocalizedString(
            key = StringKey("devices.namePlaceholder", "devices_name_placeholder", emptyList(), 1),
        )
        val `confirm`: LocalizedString get() = LocalizedString(
            key = StringKey("devices.confirm", "devices_confirm", emptyList(), 1),
        )
        fun `paired`(`name`: String): LocalizedString = LocalizedString(
            key = StringKey("devices.paired", "devices_paired", listOf("name"), 1),
            arguments = listOf(`name`),
        )
        fun `seen`(`when`: String): LocalizedString = LocalizedString(
            key = StringKey("devices.seen", "devices_seen", listOf("when"), 1),
            arguments = listOf(`when`),
        )
        fun `added`(`when`: String): LocalizedString = LocalizedString(
            key = StringKey("devices.added", "devices_added", listOf("when"), 1),
            arguments = listOf(`when`),
        )
        val `current`: LocalizedString get() = LocalizedString(
            key = StringKey("devices.current", "devices_current", emptyList(), 1),
        )
        val `revoke`: LocalizedString get() = LocalizedString(
            key = StringKey("devices.revoke", "devices_revoke", emptyList(), 1),
        )
        val `revokeTitle`: LocalizedString get() = LocalizedString(
            key = StringKey("devices.revokeTitle", "devices_revoke_title", emptyList(), 1),
        )
        val `revokeText`: LocalizedString get() = LocalizedString(
            key = StringKey("devices.revokeText", "devices_revoke_text", emptyList(), 1),
        )
        fun `revoked`(`name`: String): LocalizedString = LocalizedString(
            key = StringKey("devices.revoked", "devices_revoked", listOf("name"), 1),
            arguments = listOf(`name`),
        )
        object `Kind` {
            val `browser`: LocalizedString get() = LocalizedString(
                key = StringKey("devices.kind.browser", "devices_kind_browser", emptyList(), 1),
            )
            val `android`: LocalizedString get() = LocalizedString(
                key = StringKey("devices.kind.android", "devices_kind_android", emptyList(), 1),
            )
            val `ios`: LocalizedString get() = LocalizedString(
                key = StringKey("devices.kind.ios", "devices_kind_ios", emptyList(), 1),
            )
            val `display`: LocalizedString get() = LocalizedString(
                key = StringKey("devices.kind.display", "devices_kind_display", emptyList(), 1),
            )
            val `agent`: LocalizedString get() = LocalizedString(
                key = StringKey("devices.kind.agent", "devices_kind_agent", emptyList(), 1),
            )
        }
        val `connectAgent`: LocalizedString get() = LocalizedString(
            key = StringKey("devices.connectAgent", "devices_connect_agent", emptyList(), 1),
        )
        object `Status` {
            val `title`: LocalizedString get() = LocalizedString(
                key = StringKey("devices.status.title", "devices_status_title", emptyList(), 1),
            )
            val `ring`: LocalizedString get() = LocalizedString(
                key = StringKey("devices.status.ring", "devices_status_ring", emptyList(), 1),
            )
            fun `ringCharge`(`value`: String): LocalizedString = LocalizedString(
                key = StringKey("devices.status.ringCharge", "devices_status_ring_charge", listOf("value"), 1),
                arguments = listOf(`value`),
            )
            val `ringUnknown`: LocalizedString get() = LocalizedString(
                key = StringKey("devices.status.ringUnknown", "devices_status_ring_unknown", emptyList(), 1),
            )
            val `ambient`: LocalizedString get() = LocalizedString(
                key = StringKey("devices.status.ambient", "devices_status_ambient", emptyList(), 1),
            )
            val `ambientIdle`: LocalizedString get() = LocalizedString(
                key = StringKey("devices.status.ambientIdle", "devices_status_ambient_idle", emptyList(), 1),
            )
            val `ambientRecording`: LocalizedString get() = LocalizedString(
                key = StringKey("devices.status.ambientRecording", "devices_status_ambient_recording", emptyList(), 1),
            )
            val `whoop`: LocalizedString get() = LocalizedString(
                key = StringKey("devices.status.whoop", "devices_status_whoop", emptyList(), 1),
            )
            fun `whoopCharge`(`value`: String): LocalizedString = LocalizedString(
                key = StringKey("devices.status.whoopCharge", "devices_status_whoop_charge", listOf("value"), 1),
                arguments = listOf(`value`),
            )
            val `whoopUnknown`: LocalizedString get() = LocalizedString(
                key = StringKey("devices.status.whoopUnknown", "devices_status_whoop_unknown", emptyList(), 1),
            )
        }
    }
    object `Display` {
        val `title`: LocalizedString get() = LocalizedString(
            key = StringKey("display.title", "display_title", emptyList(), 1),
        )
        val `preview`: LocalizedString get() = LocalizedString(
            key = StringKey("display.preview", "display_preview", emptyList(), 1),
        )
        val `online`: LocalizedString get() = LocalizedString(
            key = StringKey("display.online", "display_online", emptyList(), 1),
        )
        val `offline`: LocalizedString get() = LocalizedString(
            key = StringKey("display.offline", "display_offline", emptyList(), 1),
        )
        val `previewAlt`: LocalizedString get() = LocalizedString(
            key = StringKey("display.previewAlt", "display_preview_alt", emptyList(), 1),
        )
        val `next`: LocalizedString get() = LocalizedString(
            key = StringKey("display.next", "display_next", emptyList(), 1),
        )
        val `home`: LocalizedString get() = LocalizedString(
            key = StringKey("display.home", "display_home", emptyList(), 1),
        )
        val `previewHint`: LocalizedString get() = LocalizedString(
            key = StringKey("display.previewHint", "display_preview_hint", emptyList(), 1),
        )
        val `state`: LocalizedString get() = LocalizedString(
            key = StringKey("display.state", "display_state", emptyList(), 1),
        )
        val `lastSeen`: LocalizedString get() = LocalizedString(
            key = StringKey("display.lastSeen", "display_last_seen", emptyList(), 1),
        )
        val `neverSeen`: LocalizedString get() = LocalizedString(
            key = StringKey("display.neverSeen", "display_never_seen", emptyList(), 1),
        )
        val `wifi`: LocalizedString get() = LocalizedString(
            key = StringKey("display.wifi", "display_wifi", emptyList(), 1),
        )
        val `firmware`: LocalizedString get() = LocalizedString(
            key = StringKey("display.firmware", "display_firmware", emptyList(), 1),
        )
        val `uptime`: LocalizedString get() = LocalizedString(
            key = StringKey("display.uptime", "display_uptime", emptyList(), 1),
        )
        fun `uptimeDays`(`days`: String, `hours`: String): LocalizedString = LocalizedString(
            key = StringKey("display.uptimeDays", "display_uptime_days", listOf("days", "hours"), 1),
            arguments = listOf(`days`, `hours`),
        )
        fun `uptimeHours`(`hours`: String, `minutes`: String): LocalizedString = LocalizedString(
            key = StringKey("display.uptimeHours", "display_uptime_hours", listOf("hours", "minutes"), 1),
            arguments = listOf(`hours`, `minutes`),
        )
        val `pages`: LocalizedString get() = LocalizedString(
            key = StringKey("display.pages", "display_pages", emptyList(), 1),
        )
        fun `page`(`number`: String): LocalizedString = LocalizedString(
            key = StringKey("display.page", "display_page", listOf("number"), 1),
            arguments = listOf(`number`),
        )
        val `up`: LocalizedString get() = LocalizedString(
            key = StringKey("display.up", "display_up", emptyList(), 1),
        )
        val `down`: LocalizedString get() = LocalizedString(
            key = StringKey("display.down", "display_down", emptyList(), 1),
        )
        val `remove`: LocalizedString get() = LocalizedString(
            key = StringKey("display.remove", "display_remove", emptyList(), 1),
        )
        val `layout`: LocalizedString get() = LocalizedString(
            key = StringKey("display.layout", "display_layout", emptyList(), 1),
        )
        object `Layouts` {
            val `single`: LocalizedString get() = LocalizedString(
                key = StringKey("display.layouts.single", "display_layouts_single", emptyList(), 1),
            )
            val `split`: LocalizedString get() = LocalizedString(
                key = StringKey("display.layouts.split", "display_layouts_split", emptyList(), 1),
            )
            val `grid`: LocalizedString get() = LocalizedString(
                key = StringKey("display.layouts.grid", "display_layouts_grid", emptyList(), 1),
            )
        }
        val `pageTitle`: LocalizedString get() = LocalizedString(
            key = StringKey("display.pageTitle", "display_page_title", emptyList(), 1),
        )
        val `pageTitleHint`: LocalizedString get() = LocalizedString(
            key = StringKey("display.pageTitleHint", "display_page_title_hint", emptyList(), 1),
        )
        fun `slot`(`number`: String): LocalizedString = LocalizedString(
            key = StringKey("display.slot", "display_slot", listOf("number"), 1),
            arguments = listOf(`number`),
        )
        val `unavailable`: LocalizedString get() = LocalizedString(
            key = StringKey("display.unavailable", "display_unavailable", emptyList(), 1),
        )
        val `addPage`: LocalizedString get() = LocalizedString(
            key = StringKey("display.addPage", "display_add_page", emptyList(), 1),
        )
        val `homeAfter`: LocalizedString get() = LocalizedString(
            key = StringKey("display.homeAfter", "display_home_after", emptyList(), 1),
        )
        object `HomeOptions` {
            val item0: LocalizedString get() = LocalizedString(
                key = StringKey("display.homeOptions.0", "display_home_options_0", emptyList(), 1),
            )
            val item60: LocalizedString get() = LocalizedString(
                key = StringKey("display.homeOptions.60", "display_home_options_60", emptyList(), 1),
            )
            val item300: LocalizedString get() = LocalizedString(
                key = StringKey("display.homeOptions.300", "display_home_options_300", emptyList(), 1),
            )
            val item900: LocalizedString get() = LocalizedString(
                key = StringKey("display.homeOptions.900", "display_home_options_900", emptyList(), 1),
            )
            val item3600: LocalizedString get() = LocalizedString(
                key = StringKey("display.homeOptions.3600", "display_home_options_3600", emptyList(), 1),
            )
        }
        val `saved`: LocalizedString get() = LocalizedString(
            key = StringKey("display.saved", "display_saved", emptyList(), 1),
        )
        val `changed`: LocalizedString get() = LocalizedString(
            key = StringKey("display.changed", "display_changed", emptyList(), 1),
        )
        val `reset`: LocalizedString get() = LocalizedString(
            key = StringKey("display.reset", "display_reset", emptyList(), 1),
        )
        val `revoke`: LocalizedString get() = LocalizedString(
            key = StringKey("display.revoke", "display_revoke", emptyList(), 1),
        )
        val `revokeTitle`: LocalizedString get() = LocalizedString(
            key = StringKey("display.revokeTitle", "display_revoke_title", emptyList(), 1),
        )
        val `revokeText`: LocalizedString get() = LocalizedString(
            key = StringKey("display.revokeText", "display_revoke_text", emptyList(), 1),
        )
        val `ticker`: LocalizedString get() = LocalizedString(
            key = StringKey("display.ticker", "display_ticker", emptyList(), 1),
        )
        val `tickerHint`: LocalizedString get() = LocalizedString(
            key = StringKey("display.tickerHint", "display_ticker_hint", emptyList(), 1),
        )
        val `tickerPeriod`: LocalizedString get() = LocalizedString(
            key = StringKey("display.tickerPeriod", "display_ticker_period", emptyList(), 1),
        )
        fun `everySeconds`(`count`: String): LocalizedString = LocalizedString(
            key = StringKey("display.everySeconds", "display_every_seconds", listOf("count"), 1),
            arguments = listOf(`count`),
        )
    }
    object `Agents` {
        val `title`: LocalizedString get() = LocalizedString(
            key = StringKey("agents.title", "agents_title", emptyList(), 1),
        )
        val `text`: LocalizedString get() = LocalizedString(
            key = StringKey("agents.text", "agents_text", emptyList(), 1),
        )
        val `name`: LocalizedString get() = LocalizedString(
            key = StringKey("agents.name", "agents_name", emptyList(), 1),
        )
        val `namePlaceholder`: LocalizedString get() = LocalizedString(
            key = StringKey("agents.namePlaceholder", "agents_name_placeholder", emptyList(), 1),
        )
        val `writes`: LocalizedString get() = LocalizedString(
            key = StringKey("agents.writes", "agents_writes", emptyList(), 1),
        )
        val `writesHint`: LocalizedString get() = LocalizedString(
            key = StringKey("agents.writesHint", "agents_writes_hint", emptyList(), 1),
        )
        object `Sections` {
            val `tasks`: LocalizedString get() = LocalizedString(
                key = StringKey("agents.sections.tasks", "agents_sections_tasks", emptyList(), 1),
            )
            val `timers`: LocalizedString get() = LocalizedString(
                key = StringKey("agents.sections.timers", "agents_sections_timers", emptyList(), 1),
            )
            val `nutrition`: LocalizedString get() = LocalizedString(
                key = StringKey("agents.sections.nutrition", "agents_sections_nutrition", emptyList(), 1),
            )
            val `personal`: LocalizedString get() = LocalizedString(
                key = StringKey("agents.sections.personal", "agents_sections_personal", emptyList(), 1),
            )
            val `trading`: LocalizedString get() = LocalizedString(
                key = StringKey("agents.sections.trading", "agents_sections_trading", emptyList(), 1),
            )
            val `research`: LocalizedString get() = LocalizedString(
                key = StringKey("agents.sections.research", "agents_sections_research", emptyList(), 1),
            )
        }
        val `diagnostics`: LocalizedString get() = LocalizedString(
            key = StringKey("agents.diagnostics", "agents_diagnostics", emptyList(), 1),
        )
        val `diagnosticsHint`: LocalizedString get() = LocalizedString(
            key = StringKey("agents.diagnosticsHint", "agents_diagnostics_hint", emptyList(), 1),
        )
        val `create`: LocalizedString get() = LocalizedString(
            key = StringKey("agents.create", "agents_create", emptyList(), 1),
        )
        val `tokenTitle`: LocalizedString get() = LocalizedString(
            key = StringKey("agents.tokenTitle", "agents_token_title", emptyList(), 1),
        )
        val `tokenText`: LocalizedString get() = LocalizedString(
            key = StringKey("agents.tokenText", "agents_token_text", emptyList(), 1),
        )
        val `claude`: LocalizedString get() = LocalizedString(
            key = StringKey("agents.claude", "agents_claude", emptyList(), 1),
        )
        val `codex`: LocalizedString get() = LocalizedString(
            key = StringKey("agents.codex", "agents_codex", emptyList(), 1),
        )
        val `copy`: LocalizedString get() = LocalizedString(
            key = StringKey("agents.copy", "agents_copy", emptyList(), 1),
        )
        val `copied`: LocalizedString get() = LocalizedString(
            key = StringKey("agents.copied", "agents_copied", emptyList(), 1),
        )
        val `copyFailed`: LocalizedString get() = LocalizedString(
            key = StringKey("agents.copyFailed", "agents_copy_failed", emptyList(), 1),
        )
        val `done`: LocalizedString get() = LocalizedString(
            key = StringKey("agents.done", "agents_done", emptyList(), 1),
        )
        val `readOnly`: LocalizedString get() = LocalizedString(
            key = StringKey("agents.readOnly", "agents_read_only", emptyList(), 1),
        )
        fun `writesList`(`list`: String): LocalizedString = LocalizedString(
            key = StringKey("agents.writesList", "agents_writes_list", listOf("list"), 1),
            arguments = listOf(`list`),
        )
        val `withDiagnostics`: LocalizedString get() = LocalizedString(
            key = StringKey("agents.withDiagnostics", "agents_with_diagnostics", emptyList(), 1),
        )
    }
    object `Places` {
        val `title`: LocalizedString get() = LocalizedString(
            key = StringKey("places.title", "places_title", emptyList(), 1),
        )
        val `emptyTitle`: LocalizedString get() = LocalizedString(
            key = StringKey("places.emptyTitle", "places_empty_title", emptyList(), 1),
        )
        val `emptyText`: LocalizedString get() = LocalizedString(
            key = StringKey("places.emptyText", "places_empty_text", emptyList(), 1),
        )
        val `dayEmptyTitle`: LocalizedString get() = LocalizedString(
            key = StringKey("places.dayEmptyTitle", "places_day_empty_title", emptyList(), 1),
        )
        val `unnamed`: LocalizedString get() = LocalizedString(
            key = StringKey("places.unnamed", "places_unnamed", emptyList(), 1),
        )
        fun `minutes`(`minutes`: String): LocalizedString = LocalizedString(
            key = StringKey("places.minutes", "places_minutes", listOf("minutes"), 1),
            arguments = listOf(`minutes`),
        )
        fun `hours`(`hours`: String, `minutes`: String): LocalizedString = LocalizedString(
            key = StringKey("places.hours", "places_hours", listOf("hours", "minutes"), 1),
            arguments = listOf(`hours`, `minutes`),
        )
        fun `hoursOnly`(`hours`: String): LocalizedString = LocalizedString(
            key = StringKey("places.hoursOnly", "places_hours_only", listOf("hours"), 1),
            arguments = listOf(`hours`),
        )
        fun `km`(`km`: String): LocalizedString = LocalizedString(
            key = StringKey("places.km", "places_km", listOf("km"), 1),
            arguments = listOf(`km`),
        )
        fun `meters`(`meters`: String): LocalizedString = LocalizedString(
            key = StringKey("places.meters", "places_meters", listOf("meters"), 1),
            arguments = listOf(`meters`),
        )
        val `map`: LocalizedString get() = LocalizedString(
            key = StringKey("places.map", "places_map", emptyList(), 1),
        )
        val `attribution`: LocalizedString get() = LocalizedString(
            key = StringKey("places.attribution", "places_attribution", emptyList(), 1),
        )
    }
    object `LocationSettings` {
        val `title`: LocalizedString get() = LocalizedString(
            key = StringKey("locationSettings.title", "location_settings_title", emptyList(), 1),
        )
        val `device`: LocalizedString get() = LocalizedString(
            key = StringKey("locationSettings.device", "location_settings_device", emptyList(), 1),
        )
        object `Permission` {
            val `granted`: LocalizedString get() = LocalizedString(
                key = StringKey("locationSettings.permission.granted", "location_settings_permission_granted", emptyList(), 1),
            )
            val `prompt`: LocalizedString get() = LocalizedString(
                key = StringKey("locationSettings.permission.prompt", "location_settings_permission_prompt", emptyList(), 1),
            )
            val `denied`: LocalizedString get() = LocalizedString(
                key = StringKey("locationSettings.permission.denied", "location_settings_permission_denied", emptyList(), 1),
            )
            val `unavailable`: LocalizedString get() = LocalizedString(
                key = StringKey("locationSettings.permission.unavailable", "location_settings_permission_unavailable", emptyList(), 1),
            )
        }
        val `allow`: LocalizedString get() = LocalizedString(
            key = StringKey("locationSettings.allow", "location_settings_allow", emptyList(), 1),
        )
        val `history`: LocalizedString get() = LocalizedString(
            key = StringKey("locationSettings.history", "location_settings_history", emptyList(), 1),
        )
        val `none`: LocalizedString get() = LocalizedString(
            key = StringKey("locationSettings.none", "location_settings_none", emptyList(), 1),
        )
        fun `accuracy`(`meters`: String): LocalizedString = LocalizedString(
            key = StringKey("locationSettings.accuracy", "location_settings_accuracy", listOf("meters"), 1),
            arguments = listOf(`meters`),
        )
        fun `points`(quantity: Double, `count`: String, `since`: String): LocalizedString = LocalizedString(
            key = StringKey("locationSettings.points", "location_settings_points", listOf("count", "since"), 3),
            arguments = listOf(`count`, `since`),
            quantity = quantity,
        )
        val `forget`: LocalizedString get() = LocalizedString(
            key = StringKey("locationSettings.forget", "location_settings_forget", emptyList(), 1),
        )
        val `forgetTitle`: LocalizedString get() = LocalizedString(
            key = StringKey("locationSettings.forgetTitle", "location_settings_forget_title", emptyList(), 1),
        )
        val `forgetText`: LocalizedString get() = LocalizedString(
            key = StringKey("locationSettings.forgetText", "location_settings_forget_text", emptyList(), 1),
        )
        val `forgetConfirm`: LocalizedString get() = LocalizedString(
            key = StringKey("locationSettings.forgetConfirm", "location_settings_forget_confirm", emptyList(), 1),
        )
        val `keep`: LocalizedString get() = LocalizedString(
            key = StringKey("locationSettings.keep", "location_settings_keep", emptyList(), 1),
        )
        val `forgotten`: LocalizedString get() = LocalizedString(
            key = StringKey("locationSettings.forgotten", "location_settings_forgotten", emptyList(), 1),
        )
        val `background`: LocalizedString get() = LocalizedString(
            key = StringKey("locationSettings.background", "location_settings_background", emptyList(), 1),
        )
        val `backgroundOn`: LocalizedString get() = LocalizedString(
            key = StringKey("locationSettings.backgroundOn", "location_settings_background_on", emptyList(), 1),
        )
        val `backgroundOff`: LocalizedString get() = LocalizedString(
            key = StringKey("locationSettings.backgroundOff", "location_settings_background_off", emptyList(), 1),
        )
        val `backgroundDenied`: LocalizedString get() = LocalizedString(
            key = StringKey("locationSettings.backgroundDenied", "location_settings_background_denied", emptyList(), 1),
        )
    }
    object `NotificationSettings` {
        val `title`: LocalizedString get() = LocalizedString(
            key = StringKey("notificationSettings.title", "notification_settings_title", emptyList(), 1),
        )
        val `text`: LocalizedString get() = LocalizedString(
            key = StringKey("notificationSettings.text", "notification_settings_text", emptyList(), 1),
        )
        val `device`: LocalizedString get() = LocalizedString(
            key = StringKey("notificationSettings.device", "notification_settings_device", emptyList(), 1),
        )
        val `deviceHint`: LocalizedString get() = LocalizedString(
            key = StringKey("notificationSettings.deviceHint", "notification_settings_device_hint", emptyList(), 1),
        )
        val `deviceOn`: LocalizedString get() = LocalizedString(
            key = StringKey("notificationSettings.deviceOn", "notification_settings_device_on", emptyList(), 1),
        )
        val `deviceOff`: LocalizedString get() = LocalizedString(
            key = StringKey("notificationSettings.deviceOff", "notification_settings_device_off", emptyList(), 1),
        )
        val `unsupported`: LocalizedString get() = LocalizedString(
            key = StringKey("notificationSettings.unsupported", "notification_settings_unsupported", emptyList(), 1),
        )
        val `denied`: LocalizedString get() = LocalizedString(
            key = StringKey("notificationSettings.denied", "notification_settings_denied", emptyList(), 1),
        )
        val `serverOff`: LocalizedString get() = LocalizedString(
            key = StringKey("notificationSettings.serverOff", "notification_settings_server_off", emptyList(), 1),
        )
        val `shell`: LocalizedString get() = LocalizedString(
            key = StringKey("notificationSettings.shell", "notification_settings_shell", emptyList(), 1),
        )
        val `test`: LocalizedString get() = LocalizedString(
            key = StringKey("notificationSettings.test", "notification_settings_test", emptyList(), 1),
        )
        val `testSent`: LocalizedString get() = LocalizedString(
            key = StringKey("notificationSettings.testSent", "notification_settings_test_sent", emptyList(), 1),
        )
        val `quiet`: LocalizedString get() = LocalizedString(
            key = StringKey("notificationSettings.quiet", "notification_settings_quiet", emptyList(), 1),
        )
        val `from`: LocalizedString get() = LocalizedString(
            key = StringKey("notificationSettings.from", "notification_settings_from", emptyList(), 1),
        )
        val `to`: LocalizedString get() = LocalizedString(
            key = StringKey("notificationSettings.to", "notification_settings_to", emptyList(), 1),
        )
        val `quietHint`: LocalizedString get() = LocalizedString(
            key = StringKey("notificationSettings.quietHint", "notification_settings_quiet_hint", emptyList(), 1),
        )
        val `topicsTitle`: LocalizedString get() = LocalizedString(
            key = StringKey("notificationSettings.topicsTitle", "notification_settings_topics_title", emptyList(), 1),
        )
        val `topicsHint`: LocalizedString get() = LocalizedString(
            key = StringKey("notificationSettings.topicsHint", "notification_settings_topics_hint", emptyList(), 1),
        )
        val `save`: LocalizedString get() = LocalizedString(
            key = StringKey("notificationSettings.save", "notification_settings_save", emptyList(), 1),
        )
        val `saved`: LocalizedString get() = LocalizedString(
            key = StringKey("notificationSettings.saved", "notification_settings_saved", emptyList(), 1),
        )
        object `Topics` {
            val `app_test`: LocalizedString get() = LocalizedString(
                key = StringKey("notificationSettings.topics.app_test", "notification_settings_topics_app_test", emptyList(), 1),
            )
            val `timers_fired`: LocalizedString get() = LocalizedString(
                key = StringKey("notificationSettings.topics.timers_fired", "notification_settings_topics_timers_fired", emptyList(), 1),
            )
            val `timers_reminder`: LocalizedString get() = LocalizedString(
                key = StringKey("notificationSettings.topics.timers_reminder", "notification_settings_topics_timers_reminder", emptyList(), 1),
            )
            val `agents_message`: LocalizedString get() = LocalizedString(
                key = StringKey("notificationSettings.topics.agents_message", "notification_settings_topics_agents_message", emptyList(), 1),
            )
            val `system_backup`: LocalizedString get() = LocalizedString(
                key = StringKey("notificationSettings.topics.system_backup", "notification_settings_topics_system_backup", emptyList(), 1),
            )
            val `system_deploy`: LocalizedString get() = LocalizedString(
                key = StringKey("notificationSettings.topics.system_deploy", "notification_settings_topics_system_deploy", emptyList(), 1),
            )
            val `system_disk`: LocalizedString get() = LocalizedString(
                key = StringKey("notificationSettings.topics.system_disk", "notification_settings_topics_system_disk", emptyList(), 1),
            )
            val `system_errors`: LocalizedString get() = LocalizedString(
                key = StringKey("notificationSettings.topics.system_errors", "notification_settings_topics_system_errors", emptyList(), 1),
            )
            val `system_plugin`: LocalizedString get() = LocalizedString(
                key = StringKey("notificationSettings.topics.system_plugin", "notification_settings_topics_system_plugin", emptyList(), 1),
            )
            val `calls_missed`: LocalizedString get() = LocalizedString(
                key = StringKey("notificationSettings.topics.calls_missed", "notification_settings_topics_calls_missed", emptyList(), 1),
            )
            val `nutrition_refined`: LocalizedString get() = LocalizedString(
                key = StringKey("notificationSettings.topics.nutrition_refined", "notification_settings_topics_nutrition_refined", emptyList(), 1),
            )
            val `research_sources`: LocalizedString get() = LocalizedString(
                key = StringKey("notificationSettings.topics.research_sources", "notification_settings_topics_research_sources", emptyList(), 1),
            )
            val `research_ready`: LocalizedString get() = LocalizedString(
                key = StringKey("notificationSettings.topics.research_ready", "notification_settings_topics_research_ready", emptyList(), 1),
            )
            val `tasks_assistant_result`: LocalizedString get() = LocalizedString(
                key = StringKey("notificationSettings.topics.tasks_assistant_result", "notification_settings_topics_tasks_assistant_result", emptyList(), 1),
            )
            val `tasks_assistant_question`: LocalizedString get() = LocalizedString(
                key = StringKey("notificationSettings.topics.tasks_assistant_question", "notification_settings_topics_tasks_assistant_question", emptyList(), 1),
            )
            val `tasks_assistant_failed`: LocalizedString get() = LocalizedString(
                key = StringKey("notificationSettings.topics.tasks_assistant_failed", "notification_settings_topics_tasks_assistant_failed", emptyList(), 1),
            )
        }
        val `schedulePending`: LocalizedString get() = LocalizedString(
            key = StringKey("notificationSettings.schedulePending", "notification_settings_schedule_pending", emptyList(), 1),
        )
    }
    object `ChatSettings` {
        val `title`: LocalizedString get() = LocalizedString(
            key = StringKey("chatSettings.title", "chat_settings_title", emptyList(), 1),
        )
        val `linkText`: LocalizedString get() = LocalizedString(
            key = StringKey("chatSettings.linkText", "chat_settings_link_text", emptyList(), 1),
        )
        val `text`: LocalizedString get() = LocalizedString(
            key = StringKey("chatSettings.text", "chat_settings_text", emptyList(), 1),
        )
        val `chat`: LocalizedString get() = LocalizedString(
            key = StringKey("chatSettings.chat", "chat_settings_chat", emptyList(), 1),
        )
        val `model`: LocalizedString get() = LocalizedString(
            key = StringKey("chatSettings.model", "chat_settings_model", emptyList(), 1),
        )
        val `reasoning`: LocalizedString get() = LocalizedString(
            key = StringKey("chatSettings.reasoning", "chat_settings_reasoning", emptyList(), 1),
        )
        object `Purposes` {
            object `Research` {
                val `title`: LocalizedString get() = LocalizedString(
                    key = StringKey("chatSettings.purposes.research.title", "chat_settings_purposes_research_title", emptyList(), 1),
                )
                val `text`: LocalizedString get() = LocalizedString(
                    key = StringKey("chatSettings.purposes.research.text", "chat_settings_purposes_research_text", emptyList(), 1),
                )
            }
            object `Quick_search` {
                val `title`: LocalizedString get() = LocalizedString(
                    key = StringKey("chatSettings.purposes.quick_search.title", "chat_settings_purposes_quick_search_title", emptyList(), 1),
                )
                val `text`: LocalizedString get() = LocalizedString(
                    key = StringKey("chatSettings.purposes.quick_search.text", "chat_settings_purposes_quick_search_text", emptyList(), 1),
                )
            }
            object `Food_search` {
                val `title`: LocalizedString get() = LocalizedString(
                    key = StringKey("chatSettings.purposes.food_search.title", "chat_settings_purposes_food_search_title", emptyList(), 1),
                )
                val `text`: LocalizedString get() = LocalizedString(
                    key = StringKey("chatSettings.purposes.food_search.text", "chat_settings_purposes_food_search_text", emptyList(), 1),
                )
            }
        }
        object `Efforts` {
            val `low`: LocalizedString get() = LocalizedString(
                key = StringKey("chatSettings.efforts.low", "chat_settings_efforts_low", emptyList(), 1),
            )
            val `medium`: LocalizedString get() = LocalizedString(
                key = StringKey("chatSettings.efforts.medium", "chat_settings_efforts_medium", emptyList(), 1),
            )
            val `high`: LocalizedString get() = LocalizedString(
                key = StringKey("chatSettings.efforts.high", "chat_settings_efforts_high", emptyList(), 1),
            )
            val `xhigh`: LocalizedString get() = LocalizedString(
                key = StringKey("chatSettings.efforts.xhigh", "chat_settings_efforts_xhigh", emptyList(), 1),
            )
            val `max`: LocalizedString get() = LocalizedString(
                key = StringKey("chatSettings.efforts.max", "chat_settings_efforts_max", emptyList(), 1),
            )
        }
        val `accountsHint`: LocalizedString get() = LocalizedString(
            key = StringKey("chatSettings.accountsHint", "chat_settings_accounts_hint", emptyList(), 1),
        )
        val `save`: LocalizedString get() = LocalizedString(
            key = StringKey("chatSettings.save", "chat_settings_save", emptyList(), 1),
        )
        val `saved`: LocalizedString get() = LocalizedString(
            key = StringKey("chatSettings.saved", "chat_settings_saved", emptyList(), 1),
        )
        val `defaults`: LocalizedString get() = LocalizedString(
            key = StringKey("chatSettings.defaults", "chat_settings_defaults", emptyList(), 1),
        )
        val `unavailable`: LocalizedString get() = LocalizedString(
            key = StringKey("chatSettings.unavailable", "chat_settings_unavailable", emptyList(), 1),
        )
    }
    object `FoodCatalog` {
        val `title`: LocalizedString get() = LocalizedString(
            key = StringKey("foodCatalog.title", "food_catalog_title", emptyList(), 1),
        )
        val `linkText`: LocalizedString get() = LocalizedString(
            key = StringKey("foodCatalog.linkText", "food_catalog_link_text", emptyList(), 1),
        )
        val `text`: LocalizedString get() = LocalizedString(
            key = StringKey("foodCatalog.text", "food_catalog_text", emptyList(), 1),
        )
        val `active`: LocalizedString get() = LocalizedString(
            key = StringKey("foodCatalog.active", "food_catalog_active", emptyList(), 1),
        )
        val `empty`: LocalizedString get() = LocalizedString(
            key = StringKey("foodCatalog.empty", "food_catalog_empty", emptyList(), 1),
        )
        fun `release`(`date`: String): LocalizedString = LocalizedString(
            key = StringKey("foodCatalog.release", "food_catalog_release", listOf("date"), 1),
            arguments = listOf(`date`),
        )
        val `imported`: LocalizedString get() = LocalizedString(
            key = StringKey("foodCatalog.imported", "food_catalog_imported", emptyList(), 1),
        )
        val `products`: LocalizedString get() = LocalizedString(
            key = StringKey("foodCatalog.products", "food_catalog_products", emptyList(), 1),
        )
        val `ml`: LocalizedString get() = LocalizedString(
            key = StringKey("foodCatalog.ml", "food_catalog_ml", emptyList(), 1),
        )
        val `withPackage`: LocalizedString get() = LocalizedString(
            key = StringKey("foodCatalog.withPackage", "food_catalog_with_package", emptyList(), 1),
        )
        val `units`: LocalizedString get() = LocalizedString(
            key = StringKey("foodCatalog.units", "food_catalog_units", emptyList(), 1),
        )
        object `UnitSource` {
            val `declared`: LocalizedString get() = LocalizedString(
                key = StringKey("foodCatalog.unitSource.declared", "food_catalog_unit_source_declared", emptyList(), 1),
            )
            val `from_quantity_text`: LocalizedString get() = LocalizedString(
                key = StringKey("foodCatalog.unitSource.from_quantity_text", "food_catalog_unit_source_from_quantity_text", emptyList(), 1),
            )
            val `read_by_model`: LocalizedString get() = LocalizedString(
                key = StringKey("foodCatalog.unitSource.read_by_model", "food_catalog_unit_source_read_by_model", emptyList(), 1),
            )
            val `undeclared`: LocalizedString get() = LocalizedString(
                key = StringKey("foodCatalog.unitSource.undeclared", "food_catalog_unit_source_undeclared", emptyList(), 1),
            )
        }
        val `update`: LocalizedString get() = LocalizedString(
            key = StringKey("foodCatalog.update", "food_catalog_update", emptyList(), 1),
        )
        val `refresh`: LocalizedString get() = LocalizedString(
            key = StringKey("foodCatalog.refresh", "food_catalog_refresh", emptyList(), 1),
        )
        val `cancel`: LocalizedString get() = LocalizedString(
            key = StringKey("foodCatalog.cancel", "food_catalog_cancel", emptyList(), 1),
        )
        object `Stage` {
            val `download`: LocalizedString get() = LocalizedString(
                key = StringKey("foodCatalog.stage.download", "food_catalog_stage_download", emptyList(), 1),
            )
            val `units`: LocalizedString get() = LocalizedString(
                key = StringKey("foodCatalog.stage.units", "food_catalog_stage_units", emptyList(), 1),
            )
            val `load`: LocalizedString get() = LocalizedString(
                key = StringKey("foodCatalog.stage.load", "food_catalog_stage_load", emptyList(), 1),
            )
            val `done`: LocalizedString get() = LocalizedString(
                key = StringKey("foodCatalog.stage.done", "food_catalog_stage_done", emptyList(), 1),
            )
        }
        fun `downloaded`(`done`: String, `total`: String): LocalizedString = LocalizedString(
            key = StringKey("foodCatalog.downloaded", "food_catalog_downloaded", listOf("done", "total"), 1),
            arguments = listOf(`done`, `total`),
        )
        fun `downloadedSoFar`(`done`: String): LocalizedString = LocalizedString(
            key = StringKey("foodCatalog.downloadedSoFar", "food_catalog_downloaded_so_far", listOf("done"), 1),
            arguments = listOf(`done`),
        )
        fun `lines`(`count`: String): LocalizedString = LocalizedString(
            key = StringKey("foodCatalog.lines", "food_catalog_lines", listOf("count"), 1),
            arguments = listOf(`count`),
        )
        fun `kept`(`count`: String): LocalizedString = LocalizedString(
            key = StringKey("foodCatalog.kept", "food_catalog_kept", listOf("count"), 1),
            arguments = listOf(`count`),
        )
        fun `unitsAsked`(`done`: String, `total`: String): LocalizedString = LocalizedString(
            key = StringKey("foodCatalog.unitsAsked", "food_catalog_units_asked", listOf("done", "total"), 1),
            arguments = listOf(`done`, `total`),
        )
        fun `unitsRead`(`count`: String): LocalizedString = LocalizedString(
            key = StringKey("foodCatalog.unitsRead", "food_catalog_units_read", listOf("count"), 1),
            arguments = listOf(`count`),
        )
        fun `loaded`(`done`: String, `total`: String): LocalizedString = LocalizedString(
            key = StringKey("foodCatalog.loaded", "food_catalog_loaded", listOf("done", "total"), 1),
            arguments = listOf(`done`, `total`),
        )
        fun `attempt`(`attempt`: String, `total`: String): LocalizedString = LocalizedString(
            key = StringKey("foodCatalog.attempt", "food_catalog_attempt", listOf("attempt", "total"), 1),
            arguments = listOf(`attempt`, `total`),
        )
        fun `unread`(`count`: String): LocalizedString = LocalizedString(
            key = StringKey("foodCatalog.unread", "food_catalog_unread", listOf("count"), 1),
            arguments = listOf(`count`),
        )
        val `unreadUnknown`: LocalizedString get() = LocalizedString(
            key = StringKey("foodCatalog.unreadUnknown", "food_catalog_unread_unknown", emptyList(), 1),
        )
        object `Outcome` {
            val `succeeded`: LocalizedString get() = LocalizedString(
                key = StringKey("foodCatalog.outcome.succeeded", "food_catalog_outcome_succeeded", emptyList(), 1),
            )
            val `failed`: LocalizedString get() = LocalizedString(
                key = StringKey("foodCatalog.outcome.failed", "food_catalog_outcome_failed", emptyList(), 1),
            )
            val `cancelled`: LocalizedString get() = LocalizedString(
                key = StringKey("foodCatalog.outcome.cancelled", "food_catalog_outcome_cancelled", emptyList(), 1),
            )
            val `interrupted`: LocalizedString get() = LocalizedString(
                key = StringKey("foodCatalog.outcome.interrupted", "food_catalog_outcome_interrupted", emptyList(), 1),
            )
        }
        object `Error` {
            val `download_failed`: LocalizedString get() = LocalizedString(
                key = StringKey("foodCatalog.error.download_failed", "food_catalog_error_download_failed", emptyList(), 1),
            )
            val `extract_failed`: LocalizedString get() = LocalizedString(
                key = StringKey("foodCatalog.error.extract_failed", "food_catalog_error_extract_failed", emptyList(), 1),
            )
            val `extract_invalid`: LocalizedString get() = LocalizedString(
                key = StringKey("foodCatalog.error.extract_invalid", "food_catalog_error_extract_invalid", emptyList(), 1),
            )
            val `empty_extract`: LocalizedString get() = LocalizedString(
                key = StringKey("foodCatalog.error.empty_extract", "food_catalog_error_empty_extract", emptyList(), 1),
            )
            val `release_exists`: LocalizedString get() = LocalizedString(
                key = StringKey("foodCatalog.error.release_exists", "food_catalog_error_release_exists", emptyList(), 1),
            )
            val `load_failed`: LocalizedString get() = LocalizedString(
                key = StringKey("foodCatalog.error.load_failed", "food_catalog_error_load_failed", emptyList(), 1),
            )
            val `internal_error`: LocalizedString get() = LocalizedString(
                key = StringKey("foodCatalog.error.internal_error", "food_catalog_error_internal_error", emptyList(), 1),
            )
        }
        val `started`: LocalizedString get() = LocalizedString(
            key = StringKey("foodCatalog.started", "food_catalog_started", emptyList(), 1),
        )
        val `cancelled`: LocalizedString get() = LocalizedString(
            key = StringKey("foodCatalog.cancelled", "food_catalog_cancelled", emptyList(), 1),
        )
        val `earlier`: LocalizedString get() = LocalizedString(
            key = StringKey("foodCatalog.earlier", "food_catalog_earlier", emptyList(), 1),
        )
        fun `productCount`(`count`: String): LocalizedString = LocalizedString(
            key = StringKey("foodCatalog.productCount", "food_catalog_product_count", listOf("count"), 1),
            arguments = listOf(`count`),
        )
        val `activate`: LocalizedString get() = LocalizedString(
            key = StringKey("foodCatalog.activate", "food_catalog_activate", emptyList(), 1),
        )
        fun `activateTitle`(`date`: String): LocalizedString = LocalizedString(
            key = StringKey("foodCatalog.activateTitle", "food_catalog_activate_title", listOf("date"), 1),
            arguments = listOf(`date`),
        )
        val `activateText`: LocalizedString get() = LocalizedString(
            key = StringKey("foodCatalog.activateText", "food_catalog_activate_text", emptyList(), 1),
        )
        val `activated`: LocalizedString get() = LocalizedString(
            key = StringKey("foodCatalog.activated", "food_catalog_activated", emptyList(), 1),
        )
    }
    object `ResearchSources` {
        val `title`: LocalizedString get() = LocalizedString(
            key = StringKey("researchSources.title", "research_sources_title", emptyList(), 1),
        )
        val `linkText`: LocalizedString get() = LocalizedString(
            key = StringKey("researchSources.linkText", "research_sources_link_text", emptyList(), 1),
        )
        val `text`: LocalizedString get() = LocalizedString(
            key = StringKey("researchSources.text", "research_sources_text", emptyList(), 1),
        )
        val `add`: LocalizedString get() = LocalizedString(
            key = StringKey("researchSources.add", "research_sources_add", emptyList(), 1),
        )
        val `sources`: LocalizedString get() = LocalizedString(
            key = StringKey("researchSources.sources", "research_sources_sources", emptyList(), 1),
        )
        fun `pending`(`count`: String): LocalizedString = LocalizedString(
            key = StringKey("researchSources.pending", "research_sources_pending", listOf("count"), 1),
            arguments = listOf(`count`),
        )
        val `settings`: LocalizedString get() = LocalizedString(
            key = StringKey("researchSources.settings", "research_sources_settings", emptyList(), 1),
        )
        fun `retired`(`count`: String): LocalizedString = LocalizedString(
            key = StringKey("researchSources.retired", "research_sources_retired", listOf("count"), 1),
            arguments = listOf(`count`),
        )
        val `restore`: LocalizedString get() = LocalizedString(
            key = StringKey("researchSources.restore", "research_sources_restore", emptyList(), 1),
        )
        val `restored`: LocalizedString get() = LocalizedString(
            key = StringKey("researchSources.restored", "research_sources_restored", emptyList(), 1),
        )
        val `nothingPending`: LocalizedString get() = LocalizedString(
            key = StringKey("researchSources.nothingPending", "research_sources_nothing_pending", emptyList(), 1),
        )
        val `privacy`: LocalizedString get() = LocalizedString(
            key = StringKey("researchSources.privacy", "research_sources_privacy", emptyList(), 1),
        )
        val `approve`: LocalizedString get() = LocalizedString(
            key = StringKey("researchSources.approve", "research_sources_approve", emptyList(), 1),
        )
        val `approved`: LocalizedString get() = LocalizedString(
            key = StringKey("researchSources.approved", "research_sources_approved", emptyList(), 1),
        )
        val `reject`: LocalizedString get() = LocalizedString(
            key = StringKey("researchSources.reject", "research_sources_reject", emptyList(), 1),
        )
        val `rejected`: LocalizedString get() = LocalizedString(
            key = StringKey("researchSources.rejected", "research_sources_rejected", emptyList(), 1),
        )
        val `saved`: LocalizedString get() = LocalizedString(
            key = StringKey("researchSources.saved", "research_sources_saved", emptyList(), 1),
        )
        val `settingsSaved`: LocalizedString get() = LocalizedString(
            key = StringKey("researchSources.settingsSaved", "research_sources_settings_saved", emptyList(), 1),
        )
        val `changed`: LocalizedString get() = LocalizedString(
            key = StringKey("researchSources.changed", "research_sources_changed", emptyList(), 1),
        )
        val `editText`: LocalizedString get() = LocalizedString(
            key = StringKey("researchSources.editText", "research_sources_edit_text", emptyList(), 1),
        )
        val `name`: LocalizedString get() = LocalizedString(
            key = StringKey("researchSources.name", "research_sources_name", emptyList(), 1),
        )
        val `domains`: LocalizedString get() = LocalizedString(
            key = StringKey("researchSources.domains", "research_sources_domains", emptyList(), 1),
        )
        val `entry`: LocalizedString get() = LocalizedString(
            key = StringKey("researchSources.entry", "research_sources_entry", emptyList(), 1),
        )
        val `topics`: LocalizedString get() = LocalizedString(
            key = StringKey("researchSources.topics", "research_sources_topics", emptyList(), 1),
        )
        val `priority`: LocalizedString get() = LocalizedString(
            key = StringKey("researchSources.priority", "research_sources_priority", emptyList(), 1),
        )
        val `bestFor`: LocalizedString get() = LocalizedString(
            key = StringKey("researchSources.bestFor", "research_sources_best_for", emptyList(), 1),
        )
        val `save`: LocalizedString get() = LocalizedString(
            key = StringKey("researchSources.save", "research_sources_save", emptyList(), 1),
        )
        val `retire`: LocalizedString get() = LocalizedString(
            key = StringKey("researchSources.retire", "research_sources_retire", emptyList(), 1),
        )
        val `retiredDone`: LocalizedString get() = LocalizedString(
            key = StringKey("researchSources.retiredDone", "research_sources_retired_done", emptyList(), 1),
        )
        object `Origin` {
            val `seed`: LocalizedString get() = LocalizedString(
                key = StringKey("researchSources.origin.seed", "research_sources_origin_seed", emptyList(), 1),
            )
            val `agent`: LocalizedString get() = LocalizedString(
                key = StringKey("researchSources.origin.agent", "research_sources_origin_agent", emptyList(), 1),
            )
            val `observed`: LocalizedString get() = LocalizedString(
                key = StringKey("researchSources.origin.observed", "research_sources_origin_observed", emptyList(), 1),
            )
            val `model`: LocalizedString get() = LocalizedString(
                key = StringKey("researchSources.origin.model", "research_sources_origin_model", emptyList(), 1),
            )
        }
        object `Switch` {
            object `Unlisted_stats` {
                val `title`: LocalizedString get() = LocalizedString(
                    key = StringKey("researchSources.switch.unlisted_stats.title", "research_sources_switch_unlisted_stats_title", emptyList(), 1),
                )
                val `text`: LocalizedString get() = LocalizedString(
                    key = StringKey("researchSources.switch.unlisted_stats.text", "research_sources_switch_unlisted_stats_text", emptyList(), 1),
                )
            }
            object `Model_discovery` {
                val `title`: LocalizedString get() = LocalizedString(
                    key = StringKey("researchSources.switch.model_discovery.title", "research_sources_switch_model_discovery_title", emptyList(), 1),
                )
                val `text`: LocalizedString get() = LocalizedString(
                    key = StringKey("researchSources.switch.model_discovery.text", "research_sources_switch_model_discovery_text", emptyList(), 1),
                )
            }
        }
    }
    object `SpeechPlayback` {
        val `play`: LocalizedString get() = LocalizedString(
            key = StringKey("speechPlayback.play", "speech_playback_play", emptyList(), 1),
        )
        val `stop`: LocalizedString get() = LocalizedString(
            key = StringKey("speechPlayback.stop", "speech_playback_stop", emptyList(), 1),
        )
        val `player`: LocalizedString get() = LocalizedString(
            key = StringKey("speechPlayback.player", "speech_playback_player", emptyList(), 1),
        )
        val `loading`: LocalizedString get() = LocalizedString(
            key = StringKey("speechPlayback.loading", "speech_playback_loading", emptyList(), 1),
        )
        val `playing`: LocalizedString get() = LocalizedString(
            key = StringKey("speechPlayback.playing", "speech_playback_playing", emptyList(), 1),
        )
        val `previewLoading`: LocalizedString get() = LocalizedString(
            key = StringKey("speechPlayback.previewLoading", "speech_playback_preview_loading", emptyList(), 1),
        )
        val `previewPlaying`: LocalizedString get() = LocalizedString(
            key = StringKey("speechPlayback.previewPlaying", "speech_playback_preview_playing", emptyList(), 1),
        )
        val `returnToConversation`: LocalizedString get() = LocalizedString(
            key = StringKey("speechPlayback.returnToConversation", "speech_playback_return_to_conversation", emptyList(), 1),
        )
        val `aiVoice`: LocalizedString get() = LocalizedString(
            key = StringKey("speechPlayback.aiVoice", "speech_playback_ai_voice", emptyList(), 1),
        )
        object `Errors` {
            val `not_configured`: LocalizedString get() = LocalizedString(
                key = StringKey("speechPlayback.errors.not_configured", "speech_playback_errors_not_configured", emptyList(), 1),
            )
            val `no_access`: LocalizedString get() = LocalizedString(
                key = StringKey("speechPlayback.errors.no_access", "speech_playback_errors_no_access", emptyList(), 1),
            )
            val `auth_required`: LocalizedString get() = LocalizedString(
                key = StringKey("speechPlayback.errors.auth_required", "speech_playback_errors_auth_required", emptyList(), 1),
            )
            val `application_limit`: LocalizedString get() = LocalizedString(
                key = StringKey("speechPlayback.errors.application_limit", "speech_playback_errors_application_limit", emptyList(), 1),
            )
            val `provider_limit`: LocalizedString get() = LocalizedString(
                key = StringKey("speechPlayback.errors.provider_limit", "speech_playback_errors_provider_limit", emptyList(), 1),
            )
            val `busy`: LocalizedString get() = LocalizedString(
                key = StringKey("speechPlayback.errors.busy", "speech_playback_errors_busy", emptyList(), 1),
            )
            val `timeout`: LocalizedString get() = LocalizedString(
                key = StringKey("speechPlayback.errors.timeout", "speech_playback_errors_timeout", emptyList(), 1),
            )
            val `connection`: LocalizedString get() = LocalizedString(
                key = StringKey("speechPlayback.errors.connection", "speech_playback_errors_connection", emptyList(), 1),
            )
            val `provider_error`: LocalizedString get() = LocalizedString(
                key = StringKey("speechPlayback.errors.provider_error", "speech_playback_errors_provider_error", emptyList(), 1),
            )
            val `secret_unavailable`: LocalizedString get() = LocalizedString(
                key = StringKey("speechPlayback.errors.secret_unavailable", "speech_playback_errors_secret_unavailable", emptyList(), 1),
            )
            val `execution_disabled`: LocalizedString get() = LocalizedString(
                key = StringKey("speechPlayback.errors.execution_disabled", "speech_playback_errors_execution_disabled", emptyList(), 1),
            )
            val `session_expired`: LocalizedString get() = LocalizedString(
                key = StringKey("speechPlayback.errors.session_expired", "speech_playback_errors_session_expired", emptyList(), 1),
            )
            val `playback`: LocalizedString get() = LocalizedString(
                key = StringKey("speechPlayback.errors.playback", "speech_playback_errors_playback", emptyList(), 1),
            )
        }
    }
    object `SpeechSettings` {
        val `title`: LocalizedString get() = LocalizedString(
            key = StringKey("speechSettings.title", "speech_settings_title", emptyList(), 1),
        )
        val `linkText`: LocalizedString get() = LocalizedString(
            key = StringKey("speechSettings.linkText", "speech_settings_link_text", emptyList(), 1),
        )
        val `adminTitle`: LocalizedString get() = LocalizedString(
            key = StringKey("speechSettings.adminTitle", "speech_settings_admin_title", emptyList(), 1),
        )
        val `adminText`: LocalizedString get() = LocalizedString(
            key = StringKey("speechSettings.adminText", "speech_settings_admin_text", emptyList(), 1),
        )
        val `personalTitle`: LocalizedString get() = LocalizedString(
            key = StringKey("speechSettings.personalTitle", "speech_settings_personal_title", emptyList(), 1),
        )
        val `sharedTitle`: LocalizedString get() = LocalizedString(
            key = StringKey("speechSettings.sharedTitle", "speech_settings_shared_title", emptyList(), 1),
        )
        val `personalHint`: LocalizedString get() = LocalizedString(
            key = StringKey("speechSettings.personalHint", "speech_settings_personal_hint", emptyList(), 1),
        )
        val `sharedHint`: LocalizedString get() = LocalizedString(
            key = StringKey("speechSettings.sharedHint", "speech_settings_shared_hint", emptyList(), 1),
        )
        val `access`: LocalizedString get() = LocalizedString(
            key = StringKey("speechSettings.access", "speech_settings_access", emptyList(), 1),
        )
        val `add`: LocalizedString get() = LocalizedString(
            key = StringKey("speechSettings.add", "speech_settings_add", emptyList(), 1),
        )
        val `edit`: LocalizedString get() = LocalizedString(
            key = StringKey("speechSettings.edit", "speech_settings_edit", emptyList(), 1),
        )
        val `empty`: LocalizedString get() = LocalizedString(
            key = StringKey("speechSettings.empty", "speech_settings_empty", emptyList(), 1),
        )
        val `sharedEmpty`: LocalizedString get() = LocalizedString(
            key = StringKey("speechSettings.sharedEmpty", "speech_settings_shared_empty", emptyList(), 1),
        )
        val `emptyHint`: LocalizedString get() = LocalizedString(
            key = StringKey("speechSettings.emptyHint", "speech_settings_empty_hint", emptyList(), 1),
        )
        val `provider`: LocalizedString get() = LocalizedString(
            key = StringKey("speechSettings.provider", "speech_settings_provider", emptyList(), 1),
        )
        val `label`: LocalizedString get() = LocalizedString(
            key = StringKey("speechSettings.label", "speech_settings_label", emptyList(), 1),
        )
        val `key`: LocalizedString get() = LocalizedString(
            key = StringKey("speechSettings.key", "speech_settings_key", emptyList(), 1),
        )
        val `replaceKey`: LocalizedString get() = LocalizedString(
            key = StringKey("speechSettings.replaceKey", "speech_settings_replace_key", emptyList(), 1),
        )
        val `keepKey`: LocalizedString get() = LocalizedString(
            key = StringKey("speechSettings.keepKey", "speech_settings_keep_key", emptyList(), 1),
        )
        val `secretHint`: LocalizedString get() = LocalizedString(
            key = StringKey("speechSettings.secretHint", "speech_settings_secret_hint", emptyList(), 1),
        )
        val `keyPresent`: LocalizedString get() = LocalizedString(
            key = StringKey("speechSettings.keyPresent", "speech_settings_key_present", emptyList(), 1),
        )
        val `keyStoreUnavailable`: LocalizedString get() = LocalizedString(
            key = StringKey("speechSettings.keyStoreUnavailable", "speech_settings_key_store_unavailable", emptyList(), 1),
        )
        val `keyMissing`: LocalizedString get() = LocalizedString(
            key = StringKey("speechSettings.keyMissing", "speech_settings_key_missing", emptyList(), 1),
        )
        val `enabled`: LocalizedString get() = LocalizedString(
            key = StringKey("speechSettings.enabled", "speech_settings_enabled", emptyList(), 1),
        )
        val `disabled`: LocalizedString get() = LocalizedString(
            key = StringKey("speechSettings.disabled", "speech_settings_disabled", emptyList(), 1),
        )
        val `saved`: LocalizedString get() = LocalizedString(
            key = StringKey("speechSettings.saved", "speech_settings_saved", emptyList(), 1),
        )
        val `removed`: LocalizedString get() = LocalizedString(
            key = StringKey("speechSettings.removed", "speech_settings_removed", emptyList(), 1),
        )
        val `remove`: LocalizedString get() = LocalizedString(
            key = StringKey("speechSettings.remove", "speech_settings_remove", emptyList(), 1),
        )
        val `removeTitle`: LocalizedString get() = LocalizedString(
            key = StringKey("speechSettings.removeTitle", "speech_settings_remove_title", emptyList(), 1),
        )
        val `removeText`: LocalizedString get() = LocalizedString(
            key = StringKey("speechSettings.removeText", "speech_settings_remove_text", emptyList(), 1),
        )
        val `save`: LocalizedString get() = LocalizedString(
            key = StringKey("speechSettings.save", "speech_settings_save", emptyList(), 1),
        )
        val `cancel`: LocalizedString get() = LocalizedString(
            key = StringKey("speechSettings.cancel", "speech_settings_cancel", emptyList(), 1),
        )
        val `selectionTitle`: LocalizedString get() = LocalizedString(
            key = StringKey("speechSettings.selectionTitle", "speech_settings_selection_title", emptyList(), 1),
        )
        val `asr`: LocalizedString get() = LocalizedString(
            key = StringKey("speechSettings.asr", "speech_settings_asr", emptyList(), 1),
        )
        val `auto`: LocalizedString get() = LocalizedString(
            key = StringKey("speechSettings.auto", "speech_settings_auto", emptyList(), 1),
        )
        val `autoHint`: LocalizedString get() = LocalizedString(
            key = StringKey("speechSettings.autoHint", "speech_settings_auto_hint", emptyList(), 1),
        )
        val `backup`: LocalizedString get() = LocalizedString(
            key = StringKey("speechSettings.backup", "speech_settings_backup", emptyList(), 1),
        )
        val `noBackup`: LocalizedString get() = LocalizedString(
            key = StringKey("speechSettings.noBackup", "speech_settings_no_backup", emptyList(), 1),
        )
        val `backupHint`: LocalizedString get() = LocalizedString(
            key = StringKey("speechSettings.backupHint", "speech_settings_backup_hint", emptyList(), 1),
        )
        val `unavailableChoice`: LocalizedString get() = LocalizedString(
            key = StringKey("speechSettings.unavailableChoice", "speech_settings_unavailable_choice", emptyList(), 1),
        )
        val `tts`: LocalizedString get() = LocalizedString(
            key = StringKey("speechSettings.tts", "speech_settings_tts", emptyList(), 1),
        )
        val `noTts`: LocalizedString get() = LocalizedString(
            key = StringKey("speechSettings.noTts", "speech_settings_no_tts", emptyList(), 1),
        )
        val `ttsHint`: LocalizedString get() = LocalizedString(
            key = StringKey("speechSettings.ttsHint", "speech_settings_tts_hint", emptyList(), 1),
        )
        val `autoTtsHint`: LocalizedString get() = LocalizedString(
            key = StringKey("speechSettings.autoTtsHint", "speech_settings_auto_tts_hint", emptyList(), 1),
        )
        val `codexTtsUnavailable`: LocalizedString get() = LocalizedString(
            key = StringKey("speechSettings.codexTtsUnavailable", "speech_settings_codex_tts_unavailable", emptyList(), 1),
        )
        val `ttsUnavailable`: LocalizedString get() = LocalizedString(
            key = StringKey("speechSettings.ttsUnavailable", "speech_settings_tts_unavailable", emptyList(), 1),
        )
        val `model`: LocalizedString get() = LocalizedString(
            key = StringKey("speechSettings.model", "speech_settings_model", emptyList(), 1),
        )
        val `asrModel`: LocalizedString get() = LocalizedString(
            key = StringKey("speechSettings.asrModel", "speech_settings_asr_model", emptyList(), 1),
        )
        val `backupModel`: LocalizedString get() = LocalizedString(
            key = StringKey("speechSettings.backupModel", "speech_settings_backup_model", emptyList(), 1),
        )
        val `primaryModelShort`: LocalizedString get() = LocalizedString(
            key = StringKey("speechSettings.primaryModelShort", "speech_settings_primary_model_short", emptyList(), 1),
        )
        val `duplicateBackupModel`: LocalizedString get() = LocalizedString(
            key = StringKey("speechSettings.duplicateBackupModel", "speech_settings_duplicate_backup_model", emptyList(), 1),
        )
        val `selectModel`: LocalizedString get() = LocalizedString(
            key = StringKey("speechSettings.selectModel", "speech_settings_select_model", emptyList(), 1),
        )
        val `modelHint`: LocalizedString get() = LocalizedString(
            key = StringKey("speechSettings.modelHint", "speech_settings_model_hint", emptyList(), 1),
        )
        val `openrouterHint`: LocalizedString get() = LocalizedString(
            key = StringKey("speechSettings.openrouterHint", "speech_settings_openrouter_hint", emptyList(), 1),
        )
        val `modelsLoading`: LocalizedString get() = LocalizedString(
            key = StringKey("speechSettings.modelsLoading", "speech_settings_models_loading", emptyList(), 1),
        )
        val `modelsUnavailable`: LocalizedString get() = LocalizedString(
            key = StringKey("speechSettings.modelsUnavailable", "speech_settings_models_unavailable", emptyList(), 1),
        )
        object `CatalogErrors` {
            val `application_limit`: LocalizedString get() = LocalizedString(
                key = StringKey("speechSettings.catalogErrors.application_limit", "speech_settings_catalog_errors_application_limit", emptyList(), 1),
            )
            val `provider_limit`: LocalizedString get() = LocalizedString(
                key = StringKey("speechSettings.catalogErrors.provider_limit", "speech_settings_catalog_errors_provider_limit", emptyList(), 1),
            )
            val `busy`: LocalizedString get() = LocalizedString(
                key = StringKey("speechSettings.catalogErrors.busy", "speech_settings_catalog_errors_busy", emptyList(), 1),
            )
            val `timeout`: LocalizedString get() = LocalizedString(
                key = StringKey("speechSettings.catalogErrors.timeout", "speech_settings_catalog_errors_timeout", emptyList(), 1),
            )
            val `connection`: LocalizedString get() = LocalizedString(
                key = StringKey("speechSettings.catalogErrors.connection", "speech_settings_catalog_errors_connection", emptyList(), 1),
            )
            val `secret_unavailable`: LocalizedString get() = LocalizedString(
                key = StringKey("speechSettings.catalogErrors.secret_unavailable", "speech_settings_catalog_errors_secret_unavailable", emptyList(), 1),
            )
        }
        val `modelsEmpty`: LocalizedString get() = LocalizedString(
            key = StringKey("speechSettings.modelsEmpty", "speech_settings_models_empty", emptyList(), 1),
        )
        fun `modelCounts`(`total`: String, `asr`: String, `tts`: String): LocalizedString = LocalizedString(
            key = StringKey("speechSettings.modelCounts", "speech_settings_model_counts", listOf("total", "asr", "tts"), 1),
            arguments = listOf(`total`, `asr`, `tts`),
        )
        val `voicesMissing`: LocalizedString get() = LocalizedString(
            key = StringKey("speechSettings.voicesMissing", "speech_settings_voices_missing", emptyList(), 1),
        )
        val `voicesMissingShort`: LocalizedString get() = LocalizedString(
            key = StringKey("speechSettings.voicesMissingShort", "speech_settings_voices_missing_short", emptyList(), 1),
        )
        val `modelUnavailableShort`: LocalizedString get() = LocalizedString(
            key = StringKey("speechSettings.modelUnavailableShort", "speech_settings_model_unavailable_short", emptyList(), 1),
        )
        val `fileAsrHint`: LocalizedString get() = LocalizedString(
            key = StringKey("speechSettings.fileAsrHint", "speech_settings_file_asr_hint", emptyList(), 1),
        )
        object `NoModelsFor` {
            val `asr`: LocalizedString get() = LocalizedString(
                key = StringKey("speechSettings.noModelsFor.asr", "speech_settings_no_models_for_asr", emptyList(), 1),
            )
            val `tts`: LocalizedString get() = LocalizedString(
                key = StringKey("speechSettings.noModelsFor.tts", "speech_settings_no_models_for_tts", emptyList(), 1),
            )
        }
        val `unavailableModel`: LocalizedString get() = LocalizedString(
            key = StringKey("speechSettings.unavailableModel", "speech_settings_unavailable_model", emptyList(), 1),
        )
        val `refreshModels`: LocalizedString get() = LocalizedString(
            key = StringKey("speechSettings.refreshModels", "speech_settings_refresh_models", emptyList(), 1),
        )
        val `voice`: LocalizedString get() = LocalizedString(
            key = StringKey("speechSettings.voice", "speech_settings_voice", emptyList(), 1),
        )
        val `defaultVoice`: LocalizedString get() = LocalizedString(
            key = StringKey("speechSettings.defaultVoice", "speech_settings_default_voice", emptyList(), 1),
        )
        val `singleVoiceHint`: LocalizedString get() = LocalizedString(
            key = StringKey("speechSettings.singleVoiceHint", "speech_settings_single_voice_hint", emptyList(), 1),
        )
        val `unavailableVoice`: LocalizedString get() = LocalizedString(
            key = StringKey("speechSettings.unavailableVoice", "speech_settings_unavailable_voice", emptyList(), 1),
        )
        val `preview`: LocalizedString get() = LocalizedString(
            key = StringKey("speechSettings.preview", "speech_settings_preview", emptyList(), 1),
        )
        val `stop`: LocalizedString get() = LocalizedString(
            key = StringKey("speechSettings.stop", "speech_settings_stop", emptyList(), 1),
        )
        val `preferencesSaved`: LocalizedString get() = LocalizedString(
            key = StringKey("speechSettings.preferencesSaved", "speech_settings_preferences_saved", emptyList(), 1),
        )
        val `changed`: LocalizedString get() = LocalizedString(
            key = StringKey("speechSettings.changed", "speech_settings_changed", emptyList(), 1),
        )
        val `unknown`: LocalizedString get() = LocalizedString(
            key = StringKey("speechSettings.unknown", "speech_settings_unknown", emptyList(), 1),
        )
        val `reload`: LocalizedString get() = LocalizedString(
            key = StringKey("speechSettings.reload", "speech_settings_reload", emptyList(), 1),
        )
        val `notConfigured`: LocalizedString get() = LocalizedString(
            key = StringKey("speechSettings.notConfigured", "speech_settings_not_configured", emptyList(), 1),
        )
        val `noAccess`: LocalizedString get() = LocalizedString(
            key = StringKey("speechSettings.noAccess", "speech_settings_no_access", emptyList(), 1),
        )
        val `authRequired`: LocalizedString get() = LocalizedString(
            key = StringKey("speechSettings.authRequired", "speech_settings_auth_required", emptyList(), 1),
        )
        val `unavailable`: LocalizedString get() = LocalizedString(
            key = StringKey("speechSettings.unavailable", "speech_settings_unavailable", emptyList(), 1),
        )
        val `codexAccounts`: LocalizedString get() = LocalizedString(
            key = StringKey("speechSettings.codexAccounts", "speech_settings_codex_accounts", emptyList(), 1),
        )
        val `asrCapability`: LocalizedString get() = LocalizedString(
            key = StringKey("speechSettings.asrCapability", "speech_settings_asr_capability", emptyList(), 1),
        )
        val `ttsCapability`: LocalizedString get() = LocalizedString(
            key = StringKey("speechSettings.ttsCapability", "speech_settings_tts_capability", emptyList(), 1),
        )
    }
    object `AccountDeletion` {
        val `title`: LocalizedString get() = LocalizedString(
            key = StringKey("accountDeletion.title", "account_deletion_title", emptyList(), 1),
        )
        val `description`: LocalizedString get() = LocalizedString(
            key = StringKey("accountDeletion.description", "account_deletion_description", emptyList(), 1),
        )
        val `lastAdmin`: LocalizedString get() = LocalizedString(
            key = StringKey("accountDeletion.lastAdmin", "account_deletion_last_admin", emptyList(), 1),
        )
        fun `successor`(`name`: String): LocalizedString = LocalizedString(
            key = StringKey("accountDeletion.successor", "account_deletion_successor", listOf("name"), 1),
            arguments = listOf(`name`),
        )
        val `archive`: LocalizedString get() = LocalizedString(
            key = StringKey("accountDeletion.archive", "account_deletion_archive", emptyList(), 1),
        )
        val `password`: LocalizedString get() = LocalizedString(
            key = StringKey("accountDeletion.password", "account_deletion_password", emptyList(), 1),
        )
        val `confirm`: LocalizedString get() = LocalizedString(
            key = StringKey("accountDeletion.confirm", "account_deletion_confirm", emptyList(), 1),
        )
        val `pending`: LocalizedString get() = LocalizedString(
            key = StringKey("accountDeletion.pending", "account_deletion_pending", emptyList(), 1),
        )
        val `completed`: LocalizedString get() = LocalizedString(
            key = StringKey("accountDeletion.completed", "account_deletion_completed", emptyList(), 1),
        )
        val `retry`: LocalizedString get() = LocalizedString(
            key = StringKey("accountDeletion.retry", "account_deletion_retry", emptyList(), 1),
        )
        val `check`: LocalizedString get() = LocalizedString(
            key = StringKey("accountDeletion.check", "account_deletion_check", emptyList(), 1),
        )
        val `checking`: LocalizedString get() = LocalizedString(
            key = StringKey("accountDeletion.checking", "account_deletion_checking", emptyList(), 1),
        )
    }
    object `Moderation` {
        val `title`: LocalizedString get() = LocalizedString(
            key = StringKey("moderation.title", "moderation_title", emptyList(), 1),
        )
        val `queueText`: LocalizedString get() = LocalizedString(
            key = StringKey("moderation.queueText", "moderation_queue_text", emptyList(), 1),
        )
        val `report`: LocalizedString get() = LocalizedString(
            key = StringKey("moderation.report", "moderation_report", emptyList(), 1),
        )
        val `description`: LocalizedString get() = LocalizedString(
            key = StringKey("moderation.description", "moderation_description", emptyList(), 1),
        )
        val `reason`: LocalizedString get() = LocalizedString(
            key = StringKey("moderation.reason", "moderation_reason", emptyList(), 1),
        )
        val `submit`: LocalizedString get() = LocalizedString(
            key = StringKey("moderation.submit", "moderation_submit", emptyList(), 1),
        )
        val `sent`: LocalizedString get() = LocalizedString(
            key = StringKey("moderation.sent", "moderation_sent", emptyList(), 1),
        )
        val `empty`: LocalizedString get() = LocalizedString(
            key = StringKey("moderation.empty", "moderation_empty", emptyList(), 1),
        )
        val `dismiss`: LocalizedString get() = LocalizedString(
            key = StringKey("moderation.dismiss", "moderation_dismiss", emptyList(), 1),
        )
        val `hide`: LocalizedString get() = LocalizedString(
            key = StringKey("moderation.hide", "moderation_hide", emptyList(), 1),
        )
        val `disable`: LocalizedString get() = LocalizedString(
            key = StringKey("moderation.disable", "moderation_disable", emptyList(), 1),
        )
    }
    object `Installation` {
        val `title`: LocalizedString get() = LocalizedString(
            key = StringKey("installation.title", "installation_title", emptyList(), 1),
        )
        fun `data`(`name`: String): LocalizedString = LocalizedString(
            key = StringKey("installation.data", "installation_data", listOf("name"), 1),
            arguments = listOf(`name`),
        )
        fun `providers`(`providers`: String): LocalizedString = LocalizedString(
            key = StringKey("installation.providers", "installation_providers", listOf("providers"), 1),
            arguments = listOf(`providers`),
        )
        val `privacy`: LocalizedString get() = LocalizedString(
            key = StringKey("installation.privacy", "installation_privacy", emptyList(), 1),
        )
        val `support`: LocalizedString get() = LocalizedString(
            key = StringKey("installation.support", "installation_support", emptyList(), 1),
        )
    }
    object `Activity` {
        val `title`: LocalizedString get() = LocalizedString(
            key = StringKey("activity.title", "activity_title", emptyList(), 1),
        )
        val `description`: LocalizedString get() = LocalizedString(
            key = StringKey("activity.description", "activity_description", emptyList(), 1),
        )
        val `sections`: LocalizedString get() = LocalizedString(
            key = StringKey("activity.sections", "activity_sections", emptyList(), 1),
        )
        val `session`: LocalizedString get() = LocalizedString(
            key = StringKey("activity.session", "activity_session", emptyList(), 1),
        )
        val `addSession`: LocalizedString get() = LocalizedString(
            key = StringKey("activity.addSession", "activity_add_session", emptyList(), 1),
        )
        val `editSession`: LocalizedString get() = LocalizedString(
            key = StringKey("activity.editSession", "activity_edit_session", emptyList(), 1),
        )
        val `saved`: LocalizedString get() = LocalizedString(
            key = StringKey("activity.saved", "activity_saved", emptyList(), 1),
        )
        val `kind`: LocalizedString get() = LocalizedString(
            key = StringKey("activity.kind", "activity_kind", emptyList(), 1),
        )
        val `allKinds`: LocalizedString get() = LocalizedString(
            key = StringKey("activity.allKinds", "activity_all_kinds", emptyList(), 1),
        )
        val `customName`: LocalizedString get() = LocalizedString(
            key = StringKey("activity.customName", "activity_custom_name", emptyList(), 1),
        )
        val `sessionTitle`: LocalizedString get() = LocalizedString(
            key = StringKey("activity.sessionTitle", "activity_session_title", emptyList(), 1),
        )
        val `sessionDate`: LocalizedString get() = LocalizedString(
            key = StringKey("activity.sessionDate", "activity_session_date", emptyList(), 1),
        )
        val `sessionTime`: LocalizedString get() = LocalizedString(
            key = StringKey("activity.sessionTime", "activity_session_time", emptyList(), 1),
        )
        val `duration`: LocalizedString get() = LocalizedString(
            key = StringKey("activity.duration", "activity_duration", emptyList(), 1),
        )
        val `distance`: LocalizedString get() = LocalizedString(
            key = StringKey("activity.distance", "activity_distance", emptyList(), 1),
        )
        val `note`: LocalizedString get() = LocalizedString(
            key = StringKey("activity.note", "activity_note", emptyList(), 1),
        )
        val `minutes`: LocalizedString get() = LocalizedString(
            key = StringKey("activity.minutes", "activity_minutes", emptyList(), 1),
        )
        val `kilometers`: LocalizedString get() = LocalizedString(
            key = StringKey("activity.kilometers", "activity_kilometers", emptyList(), 1),
        )
        val `unknown`: LocalizedString get() = LocalizedString(
            key = StringKey("activity.unknown", "activity_unknown", emptyList(), 1),
        )
        val `invalidSession`: LocalizedString get() = LocalizedString(
            key = StringKey("activity.invalidSession", "activity_invalid_session", emptyList(), 1),
        )
        val `checkWriteFirst`: LocalizedString get() = LocalizedString(
            key = StringKey("activity.checkWriteFirst", "activity_check_write_first", emptyList(), 1),
        )
        val `confirmDeleteSession`: LocalizedString get() = LocalizedString(
            key = StringKey("activity.confirmDeleteSession", "activity_confirm_delete_session", emptyList(), 1),
        )
        fun `period`(`days`: String): LocalizedString = LocalizedString(
            key = StringKey("activity.period", "activity_period", listOf("days"), 1),
            arguments = listOf(`days`),
        )
        val `periodLabel`: LocalizedString get() = LocalizedString(
            key = StringKey("activity.periodLabel", "activity_period_label", emptyList(), 1),
        )
        val `until`: LocalizedString get() = LocalizedString(
            key = StringKey("activity.until", "activity_until", emptyList(), 1),
        )
        val `changePeriod`: LocalizedString get() = LocalizedString(
            key = StringKey("activity.changePeriod", "activity_change_period", emptyList(), 1),
        )
        val `toToday`: LocalizedString get() = LocalizedString(
            key = StringKey("activity.toToday", "activity_to_today", emptyList(), 1),
        )
        val `emptyPeriod`: LocalizedString get() = LocalizedString(
            key = StringKey("activity.emptyPeriod", "activity_empty_period", emptyList(), 1),
        )
        val `previousPeriod`: LocalizedString get() = LocalizedString(
            key = StringKey("activity.previousPeriod", "activity_previous_period", emptyList(), 1),
        )
        val `nextPeriod`: LocalizedString get() = LocalizedString(
            key = StringKey("activity.nextPeriod", "activity_next_period", emptyList(), 1),
        )
        val `sessionsCount`: LocalizedString get() = LocalizedString(
            key = StringKey("activity.sessionsCount", "activity_sessions_count", emptyList(), 1),
        )
        val `knownDuration`: LocalizedString get() = LocalizedString(
            key = StringKey("activity.knownDuration", "activity_known_duration", emptyList(), 1),
        )
        fun `missingDuration`(`count`: String): LocalizedString = LocalizedString(
            key = StringKey("activity.missingDuration", "activity_missing_duration", listOf("count"), 1),
            arguments = listOf(`count`),
        )
        val `workingSets`: LocalizedString get() = LocalizedString(
            key = StringKey("activity.workingSets", "activity_working_sets", emptyList(), 1),
        )
        val `distances`: LocalizedString get() = LocalizedString(
            key = StringKey("activity.distances", "activity_distances", emptyList(), 1),
        )
        fun `distanceGraph`(`kind`: String): LocalizedString = LocalizedString(
            key = StringKey("activity.distanceGraph", "activity_distance_graph", listOf("kind"), 1),
            arguments = listOf(`kind`),
        )
        val `noDurationData`: LocalizedString get() = LocalizedString(
            key = StringKey("activity.noDurationData", "activity_no_duration_data", emptyList(), 1),
        )
        val `dailyMovement`: LocalizedString get() = LocalizedString(
            key = StringKey("activity.dailyMovement", "activity_daily_movement", emptyList(), 1),
        )
        val `steps`: LocalizedString get() = LocalizedString(
            key = StringKey("activity.steps", "activity_steps", emptyList(), 1),
        )
        val `stepsToday`: LocalizedString get() = LocalizedString(
            key = StringKey("activity.stepsToday", "activity_steps_today", emptyList(), 1),
        )
        val `activeMinutes`: LocalizedString get() = LocalizedString(
            key = StringKey("activity.activeMinutes", "activity_active_minutes", emptyList(), 1),
        )
        val `noPersonalAccess`: LocalizedString get() = LocalizedString(
            key = StringKey("activity.noPersonalAccess", "activity_no_personal_access", emptyList(), 1),
        )
        val `noStepsData`: LocalizedString get() = LocalizedString(
            key = StringKey("activity.noStepsData", "activity_no_steps_data", emptyList(), 1),
        )
        val `noActiveData`: LocalizedString get() = LocalizedString(
            key = StringKey("activity.noActiveData", "activity_no_active_data", emptyList(), 1),
        )
        fun `manualDays`(`days`: String): LocalizedString = LocalizedString(
            key = StringKey("activity.manualDays", "activity_manual_days", listOf("days"), 1),
            arguments = listOf(`days`),
        )
        val `sourceHistory`: LocalizedString get() = LocalizedString(
            key = StringKey("activity.sourceHistory", "activity_source_history", emptyList(), 1),
        )
        val `recentSessions`: LocalizedString get() = LocalizedString(
            key = StringKey("activity.recentSessions", "activity_recent_sessions", emptyList(), 1),
        )
        val `noSessions`: LocalizedString get() = LocalizedString(
            key = StringKey("activity.noSessions", "activity_no_sessions", emptyList(), 1),
        )
        val `noSessionsHint`: LocalizedString get() = LocalizedString(
            key = StringKey("activity.noSessionsHint", "activity_no_sessions_hint", emptyList(), 1),
        )
        val `truncated`: LocalizedString get() = LocalizedString(
            key = StringKey("activity.truncated", "activity_truncated", emptyList(), 1),
        )
        fun `exerciseCount`(`count`: String): LocalizedString = LocalizedString(
            key = StringKey("activity.exerciseCount", "activity_exercise_count", listOf("count"), 1),
            arguments = listOf(`count`),
        )
        val `allSessions`: LocalizedString get() = LocalizedString(
            key = StringKey("activity.allSessions", "activity_all_sessions", emptyList(), 1),
        )
        val `manualSteps`: LocalizedString get() = LocalizedString(
            key = StringKey("activity.manualSteps", "activity_manual_steps", emptyList(), 1),
        )
        val `stepsTotal`: LocalizedString get() = LocalizedString(
            key = StringKey("activity.stepsTotal", "activity_steps_total", emptyList(), 1),
        )
        val `stepsHint`: LocalizedString get() = LocalizedString(
            key = StringKey("activity.stepsHint", "activity_steps_hint", emptyList(), 1),
        )
        val `stepsRange`: LocalizedString get() = LocalizedString(
            key = StringKey("activity.stepsRange", "activity_steps_range", emptyList(), 1),
        )
        val `stepsSaved`: LocalizedString get() = LocalizedString(
            key = StringKey("activity.stepsSaved", "activity_steps_saved", emptyList(), 1),
        )
        val `stepsCleared`: LocalizedString get() = LocalizedString(
            key = StringKey("activity.stepsCleared", "activity_steps_cleared", emptyList(), 1),
        )
        val `clearSteps`: LocalizedString get() = LocalizedString(
            key = StringKey("activity.clearSteps", "activity_clear_steps", emptyList(), 1),
        )
        val `exerciseCatalog`: LocalizedString get() = LocalizedString(
            key = StringKey("activity.exerciseCatalog", "activity_exercise_catalog", emptyList(), 1),
        )
        val `newExercise`: LocalizedString get() = LocalizedString(
            key = StringKey("activity.newExercise", "activity_new_exercise", emptyList(), 1),
        )
        val `editExercise`: LocalizedString get() = LocalizedString(
            key = StringKey("activity.editExercise", "activity_edit_exercise", emptyList(), 1),
        )
        val `exerciseName`: LocalizedString get() = LocalizedString(
            key = StringKey("activity.exerciseName", "activity_exercise_name", emptyList(), 1),
        )
        val `equipment`: LocalizedString get() = LocalizedString(
            key = StringKey("activity.equipment", "activity_equipment", emptyList(), 1),
        )
        val `variant`: LocalizedString get() = LocalizedString(
            key = StringKey("activity.variant", "activity_variant", emptyList(), 1),
        )
        val `loadBasis`: LocalizedString get() = LocalizedString(
            key = StringKey("activity.loadBasis", "activity_load_basis", emptyList(), 1),
        )
        val `noExercises`: LocalizedString get() = LocalizedString(
            key = StringKey("activity.noExercises", "activity_no_exercises", emptyList(), 1),
        )
        val `addExercise`: LocalizedString get() = LocalizedString(
            key = StringKey("activity.addExercise", "activity_add_exercise", emptyList(), 1),
        )
        val `chooseExercise`: LocalizedString get() = LocalizedString(
            key = StringKey("activity.chooseExercise", "activity_choose_exercise", emptyList(), 1),
        )
        val `exerciseNote`: LocalizedString get() = LocalizedString(
            key = StringKey("activity.exerciseNote", "activity_exercise_note", emptyList(), 1),
        )
        val `exerciseHistory`: LocalizedString get() = LocalizedString(
            key = StringKey("activity.exerciseHistory", "activity_exercise_history", emptyList(), 1),
        )
        val `noSessionExercises`: LocalizedString get() = LocalizedString(
            key = StringKey("activity.noSessionExercises", "activity_no_session_exercises", emptyList(), 1),
        )
        val `sets`: LocalizedString get() = LocalizedString(
            key = StringKey("activity.sets", "activity_sets", emptyList(), 1),
        )
        val `noSets`: LocalizedString get() = LocalizedString(
            key = StringKey("activity.noSets", "activity_no_sets", emptyList(), 1),
        )
        val `addSet`: LocalizedString get() = LocalizedString(
            key = StringKey("activity.addSet", "activity_add_set", emptyList(), 1),
        )
        val `editSet`: LocalizedString get() = LocalizedString(
            key = StringKey("activity.editSet", "activity_edit_set", emptyList(), 1),
        )
        val `repetitions`: LocalizedString get() = LocalizedString(
            key = StringKey("activity.repetitions", "activity_repetitions", emptyList(), 1),
        )
        val `repetitionsShort`: LocalizedString get() = LocalizedString(
            key = StringKey("activity.repetitionsShort", "activity_repetitions_short", emptyList(), 1),
        )
        val `load`: LocalizedString get() = LocalizedString(
            key = StringKey("activity.load", "activity_load", emptyList(), 1),
        )
        val `loadUnit`: LocalizedString get() = LocalizedString(
            key = StringKey("activity.loadUnit", "activity_load_unit", emptyList(), 1),
        )
        val `implementCount`: LocalizedString get() = LocalizedString(
            key = StringKey("activity.implementCount", "activity_implement_count", emptyList(), 1),
        )
        val `setKind`: LocalizedString get() = LocalizedString(
            key = StringKey("activity.setKind", "activity_set_kind", emptyList(), 1),
        )
        val `setNote`: LocalizedString get() = LocalizedString(
            key = StringKey("activity.setNote", "activity_set_note", emptyList(), 1),
        )
        val `confirmDeleteExercise`: LocalizedString get() = LocalizedString(
            key = StringKey("activity.confirmDeleteExercise", "activity_confirm_delete_exercise", emptyList(), 1),
        )
        val `confirmDeleteSessionExercise`: LocalizedString get() = LocalizedString(
            key = StringKey("activity.confirmDeleteSessionExercise", "activity_confirm_delete_session_exercise", emptyList(), 1),
        )
        val `confirmDeleteSet`: LocalizedString get() = LocalizedString(
            key = StringKey("activity.confirmDeleteSet", "activity_confirm_delete_set", emptyList(), 1),
        )
        val `chooseForProgress`: LocalizedString get() = LocalizedString(
            key = StringKey("activity.chooseForProgress", "activity_choose_for_progress", emptyList(), 1),
        )
        val `noProgress`: LocalizedString get() = LocalizedString(
            key = StringKey("activity.noProgress", "activity_no_progress", emptyList(), 1),
        )
        val `noProgressHint`: LocalizedString get() = LocalizedString(
            key = StringKey("activity.noProgressHint", "activity_no_progress_hint", emptyList(), 1),
        )
        fun `implements`(`count`: String): LocalizedString = LocalizedString(
            key = StringKey("activity.implements", "activity_implements", listOf("count"), 1),
            arguments = listOf(`count`),
        )
        val `assistanceHint`: LocalizedString get() = LocalizedString(
            key = StringKey("activity.assistanceHint", "activity_assistance_hint", emptyList(), 1),
        )
        val `lowestAssistance`: LocalizedString get() = LocalizedString(
            key = StringKey("activity.lowestAssistance", "activity_lowest_assistance", emptyList(), 1),
        )
        val `heaviestLoad`: LocalizedString get() = LocalizedString(
            key = StringKey("activity.heaviestLoad", "activity_heaviest_load", emptyList(), 1),
        )
        val `mostRepetitions`: LocalizedString get() = LocalizedString(
            key = StringKey("activity.mostRepetitions", "activity_most_repetitions", emptyList(), 1),
        )
        val `loadGraph`: LocalizedString get() = LocalizedString(
            key = StringKey("activity.loadGraph", "activity_load_graph", emptyList(), 1),
        )
        val `repetitionsGraph`: LocalizedString get() = LocalizedString(
            key = StringKey("activity.repetitionsGraph", "activity_repetitions_graph", emptyList(), 1),
        )
        val `noLoadData`: LocalizedString get() = LocalizedString(
            key = StringKey("activity.noLoadData", "activity_no_load_data", emptyList(), 1),
        )
        val `noRepetitionData`: LocalizedString get() = LocalizedString(
            key = StringKey("activity.noRepetitionData", "activity_no_repetition_data", emptyList(), 1),
        )
        val `setHistory`: LocalizedString get() = LocalizedString(
            key = StringKey("activity.setHistory", "activity_set_history", emptyList(), 1),
        )
        val `loadNormalized`: LocalizedString get() = LocalizedString(
            key = StringKey("activity.loadNormalized", "activity_load_normalized", emptyList(), 1),
        )
        fun `unresolved`(`kind`: String): LocalizedString = LocalizedString(
            key = StringKey("activity.unresolved", "activity_unresolved", listOf("kind"), 1),
            arguments = listOf(`kind`),
        )
        object `Tabs` {
            val `overview`: LocalizedString get() = LocalizedString(
                key = StringKey("activity.tabs.overview", "activity_tabs_overview", emptyList(), 1),
            )
            val `journal`: LocalizedString get() = LocalizedString(
                key = StringKey("activity.tabs.journal", "activity_tabs_journal", emptyList(), 1),
            )
            val `progress`: LocalizedString get() = LocalizedString(
                key = StringKey("activity.tabs.progress", "activity_tabs_progress", emptyList(), 1),
            )
            val `exercises`: LocalizedString get() = LocalizedString(
                key = StringKey("activity.tabs.exercises", "activity_tabs_exercises", emptyList(), 1),
            )
        }
        object `Kinds` {
            val `walking`: LocalizedString get() = LocalizedString(
                key = StringKey("activity.kinds.walking", "activity_kinds_walking", emptyList(), 1),
            )
            val `running`: LocalizedString get() = LocalizedString(
                key = StringKey("activity.kinds.running", "activity_kinds_running", emptyList(), 1),
            )
            val `cycling`: LocalizedString get() = LocalizedString(
                key = StringKey("activity.kinds.cycling", "activity_kinds_cycling", emptyList(), 1),
            )
            val `strength`: LocalizedString get() = LocalizedString(
                key = StringKey("activity.kinds.strength", "activity_kinds_strength", emptyList(), 1),
            )
            val `swimming`: LocalizedString get() = LocalizedString(
                key = StringKey("activity.kinds.swimming", "activity_kinds_swimming", emptyList(), 1),
            )
            val `mobility`: LocalizedString get() = LocalizedString(
                key = StringKey("activity.kinds.mobility", "activity_kinds_mobility", emptyList(), 1),
            )
            val `other`: LocalizedString get() = LocalizedString(
                key = StringKey("activity.kinds.other", "activity_kinds_other", emptyList(), 1),
            )
        }
        object `LoadBases` {
            val `total`: LocalizedString get() = LocalizedString(
                key = StringKey("activity.loadBases.total", "activity_load_bases_total", emptyList(), 1),
            )
            val `per_implement`: LocalizedString get() = LocalizedString(
                key = StringKey("activity.loadBases.per_implement", "activity_load_bases_per_implement", emptyList(), 1),
            )
            val `additional`: LocalizedString get() = LocalizedString(
                key = StringKey("activity.loadBases.additional", "activity_load_bases_additional", emptyList(), 1),
            )
            val `assistance`: LocalizedString get() = LocalizedString(
                key = StringKey("activity.loadBases.assistance", "activity_load_bases_assistance", emptyList(), 1),
            )
            val `bodyweight`: LocalizedString get() = LocalizedString(
                key = StringKey("activity.loadBases.bodyweight", "activity_load_bases_bodyweight", emptyList(), 1),
            )
        }
        object `LoadUnits` {
            val `kg`: LocalizedString get() = LocalizedString(
                key = StringKey("activity.loadUnits.kg", "activity_load_units_kg", emptyList(), 1),
            )
            val `lb`: LocalizedString get() = LocalizedString(
                key = StringKey("activity.loadUnits.lb", "activity_load_units_lb", emptyList(), 1),
            )
        }
        object `SetKinds` {
            val `warmup`: LocalizedString get() = LocalizedString(
                key = StringKey("activity.setKinds.warmup", "activity_set_kinds_warmup", emptyList(), 1),
            )
            val `working`: LocalizedString get() = LocalizedString(
                key = StringKey("activity.setKinds.working", "activity_set_kinds_working", emptyList(), 1),
            )
        }
        object `Operations` {
            val `session_add`: LocalizedString get() = LocalizedString(
                key = StringKey("activity.operations.session_add", "activity_operations_session_add", emptyList(), 1),
            )
            val `session_update`: LocalizedString get() = LocalizedString(
                key = StringKey("activity.operations.session_update", "activity_operations_session_update", emptyList(), 1),
            )
            val `session_delete`: LocalizedString get() = LocalizedString(
                key = StringKey("activity.operations.session_delete", "activity_operations_session_delete", emptyList(), 1),
            )
            val `exercise_add`: LocalizedString get() = LocalizedString(
                key = StringKey("activity.operations.exercise_add", "activity_operations_exercise_add", emptyList(), 1),
            )
            val `exercise_update`: LocalizedString get() = LocalizedString(
                key = StringKey("activity.operations.exercise_update", "activity_operations_exercise_update", emptyList(), 1),
            )
            val `exercise_delete`: LocalizedString get() = LocalizedString(
                key = StringKey("activity.operations.exercise_delete", "activity_operations_exercise_delete", emptyList(), 1),
            )
            val `session_exercise_add`: LocalizedString get() = LocalizedString(
                key = StringKey("activity.operations.session_exercise_add", "activity_operations_session_exercise_add", emptyList(), 1),
            )
            val `session_exercise_update`: LocalizedString get() = LocalizedString(
                key = StringKey("activity.operations.session_exercise_update", "activity_operations_session_exercise_update", emptyList(), 1),
            )
            val `session_exercise_delete`: LocalizedString get() = LocalizedString(
                key = StringKey("activity.operations.session_exercise_delete", "activity_operations_session_exercise_delete", emptyList(), 1),
            )
            val `set_add`: LocalizedString get() = LocalizedString(
                key = StringKey("activity.operations.set_add", "activity_operations_set_add", emptyList(), 1),
            )
            val `set_update`: LocalizedString get() = LocalizedString(
                key = StringKey("activity.operations.set_update", "activity_operations_set_update", emptyList(), 1),
            )
            val `set_delete`: LocalizedString get() = LocalizedString(
                key = StringKey("activity.operations.set_delete", "activity_operations_set_delete", emptyList(), 1),
            )
        }
        object `Running` {
            val `steps`: LocalizedString get() = LocalizedString(
                key = StringKey("activity.running.steps", "activity_running_steps", emptyList(), 1),
            )
            val `stepUnit`: LocalizedString get() = LocalizedString(
                key = StringKey("activity.running.stepUnit", "activity_running_step_unit", emptyList(), 1),
            )
            val `snapshot`: LocalizedString get() = LocalizedString(
                key = StringKey("activity.running.snapshot", "activity_running_snapshot", emptyList(), 1),
            )
            fun `observed`(`when`: String): LocalizedString = LocalizedString(
                key = StringKey("activity.running.observed", "activity_running_observed", listOf("when"), 1),
                arguments = listOf(`when`),
            )
            val `noAddition`: LocalizedString get() = LocalizedString(
                key = StringKey("activity.running.noAddition", "activity_running_no_addition", emptyList(), 1),
            )
            val `deviceWorkouts`: LocalizedString get() = LocalizedString(
                key = StringKey("activity.running.deviceWorkouts", "activity_running_device_workouts", emptyList(), 1),
            )
            val `workout`: LocalizedString get() = LocalizedString(
                key = StringKey("activity.running.workout", "activity_running_workout", emptyList(), 1),
            )
            val `otherWorkout`: LocalizedString get() = LocalizedString(
                key = StringKey("activity.running.otherWorkout", "activity_running_other_workout", emptyList(), 1),
            )
            val `ringSource`: LocalizedString get() = LocalizedString(
                key = StringKey("activity.running.ringSource", "activity_running_ring_source", emptyList(), 1),
            )
            val `detectedSource`: LocalizedString get() = LocalizedString(
                key = StringKey("activity.running.detectedSource", "activity_running_detected_source", emptyList(), 1),
            )
            val `strapEffort`: LocalizedString get() = LocalizedString(
                key = StringKey("activity.running.strapEffort", "activity_running_strap_effort", emptyList(), 1),
            )
            fun `heartRate`(`mean`: String, `max`: String): LocalizedString = LocalizedString(
                key = StringKey("activity.running.heartRate", "activity_running_heart_rate", listOf("mean", "max"), 1),
                arguments = listOf(`mean`, `max`),
            )
            val `zones`: LocalizedString get() = LocalizedString(
                key = StringKey("activity.running.zones", "activity_running_zones", emptyList(), 1),
            )
            fun `zone`(`zone`: String, `from`: String, `to`: String): LocalizedString = LocalizedString(
                key = StringKey("activity.running.zone", "activity_running_zone", listOf("zone", "from", "to"), 1),
                arguments = listOf(`zone`, `from`, `to`),
            )
            fun `load`(`value`: String): LocalizedString = LocalizedString(
                key = StringKey("activity.running.load", "activity_running_load", listOf("value"), 1),
                arguments = listOf(`value`),
            )
            fun `effort`(`rest`: String, `max`: String, `basis`: String): LocalizedString = LocalizedString(
                key = StringKey("activity.running.effort", "activity_running_effort", listOf("rest", "max", "basis"), 1),
                arguments = listOf(`rest`, `max`, `basis`),
            )
            object `EffortBasis` {
                val `tanaka`: LocalizedString get() = LocalizedString(
                    key = StringKey("activity.running.effortBasis.tanaka", "activity_running_effort_basis_tanaka", emptyList(), 1),
                )
                val `default`: LocalizedString get() = LocalizedString(
                    key = StringKey("activity.running.effortBasis.default", "activity_running_effort_basis_default", emptyList(), 1),
                )
            }
            fun `count`(`count`: String): LocalizedString = LocalizedString(
                key = StringKey("activity.running.count", "activity_running_count", listOf("count"), 1),
                arguments = listOf(`count`),
            )
            val `unknownDuration`: LocalizedString get() = LocalizedString(
                key = StringKey("activity.running.unknownDuration", "activity_running_unknown_duration", emptyList(), 1),
            )
            val `separateSummary`: LocalizedString get() = LocalizedString(
                key = StringKey("activity.running.separateSummary", "activity_running_separate_summary", emptyList(), 1),
            )
            object `Status` {
                val `serverUnsupported`: LocalizedString get() = LocalizedString(
                    key = StringKey("activity.running.status.serverUnsupported", "activity_running_status_server_unsupported", emptyList(), 1),
                )
                val `unverified`: LocalizedString get() = LocalizedString(
                    key = StringKey("activity.running.status.unverified", "activity_running_status_unverified", emptyList(), 1),
                )
                val `no_data`: LocalizedString get() = LocalizedString(
                    key = StringKey("activity.running.status.no_data", "activity_running_status_no_data", emptyList(), 1),
                )
                val `unsupported`: LocalizedString get() = LocalizedString(
                    key = StringKey("activity.running.status.unsupported", "activity_running_status_unsupported", emptyList(), 1),
                )
                val `invalid`: LocalizedString get() = LocalizedString(
                    key = StringKey("activity.running.status.invalid", "activity_running_status_invalid", emptyList(), 1),
                )
                val `timeout`: LocalizedString get() = LocalizedString(
                    key = StringKey("activity.running.status.timeout", "activity_running_status_timeout", emptyList(), 1),
                )
            }
            object `WorkoutStatus` {
                val `unverified`: LocalizedString get() = LocalizedString(
                    key = StringKey("activity.running.workoutStatus.unverified", "activity_running_workout_status_unverified", emptyList(), 1),
                )
                val `no_data`: LocalizedString get() = LocalizedString(
                    key = StringKey("activity.running.workoutStatus.no_data", "activity_running_workout_status_no_data", emptyList(), 1),
                )
                val `unsupported`: LocalizedString get() = LocalizedString(
                    key = StringKey("activity.running.workoutStatus.unsupported", "activity_running_workout_status_unsupported", emptyList(), 1),
                )
                val `invalid`: LocalizedString get() = LocalizedString(
                    key = StringKey("activity.running.workoutStatus.invalid", "activity_running_workout_status_invalid", emptyList(), 1),
                )
                val `timeout`: LocalizedString get() = LocalizedString(
                    key = StringKey("activity.running.workoutStatus.timeout", "activity_running_workout_status_timeout", emptyList(), 1),
                )
                val `data`: LocalizedString get() = LocalizedString(
                    key = StringKey("activity.running.workoutStatus.data", "activity_running_workout_status_data", emptyList(), 1),
                )
            }
        }
    }
    object `Ring` {
        val `title`: LocalizedString get() = LocalizedString(
            key = StringKey("ring.title", "ring_title", emptyList(), 1),
        )
        val `metrics`: LocalizedString get() = LocalizedString(
            key = StringKey("ring.metrics", "ring_metrics", emptyList(), 1),
        )
        val `notSelected`: LocalizedString get() = LocalizedString(
            key = StringKey("ring.notSelected", "ring_not_selected", emptyList(), 1),
        )
        val `initializing`: LocalizedString get() = LocalizedString(
            key = StringKey("ring.initializing", "ring_initializing", emptyList(), 1),
        )
        val `checkConnection`: LocalizedString get() = LocalizedString(
            key = StringKey("ring.checkConnection", "ring_check_connection", emptyList(), 1),
        )
        object `Phase` {
            val `idle`: LocalizedString get() = LocalizedString(
                key = StringKey("ring.phase.idle", "ring_phase_idle", emptyList(), 1),
            )
            val `scanning`: LocalizedString get() = LocalizedString(
                key = StringKey("ring.phase.scanning", "ring_phase_scanning", emptyList(), 1),
            )
            val `connecting`: LocalizedString get() = LocalizedString(
                key = StringKey("ring.phase.connecting", "ring_phase_connecting", emptyList(), 1),
            )
            val `syncing`: LocalizedString get() = LocalizedString(
                key = StringKey("ring.phase.syncing", "ring_phase_syncing", emptyList(), 1),
            )
            val `uploading`: LocalizedString get() = LocalizedString(
                key = StringKey("ring.phase.uploading", "ring_phase_uploading", emptyList(), 1),
            )
            val `error`: LocalizedString get() = LocalizedString(
                key = StringKey("ring.phase.error", "ring_phase_error", emptyList(), 1),
            )
        }
        object `Metric` {
            val `steps`: LocalizedString get() = LocalizedString(
                key = StringKey("ring.metric.steps", "ring_metric_steps", emptyList(), 1),
            )
            val `sleep`: LocalizedString get() = LocalizedString(
                key = StringKey("ring.metric.sleep", "ring_metric_sleep", emptyList(), 1),
            )
            val `heart_rate`: LocalizedString get() = LocalizedString(
                key = StringKey("ring.metric.heart_rate", "ring_metric_heart_rate", emptyList(), 1),
            )
            val `spo2`: LocalizedString get() = LocalizedString(
                key = StringKey("ring.metric.spo2", "ring_metric_spo2", emptyList(), 1),
            )
            val `temperature`: LocalizedString get() = LocalizedString(
                key = StringKey("ring.metric.temperature", "ring_metric_temperature", emptyList(), 1),
            )
            val `hrv`: LocalizedString get() = LocalizedString(
                key = StringKey("ring.metric.hrv", "ring_metric_hrv", emptyList(), 1),
            )
            val `running_steps`: LocalizedString get() = LocalizedString(
                key = StringKey("ring.metric.running_steps", "ring_metric_running_steps", emptyList(), 1),
            )
            val `active_minutes`: LocalizedString get() = LocalizedString(
                key = StringKey("ring.metric.active_minutes", "ring_metric_active_minutes", emptyList(), 1),
            )
            val `running_workouts`: LocalizedString get() = LocalizedString(
                key = StringKey("ring.metric.running_workouts", "ring_metric_running_workouts", emptyList(), 1),
            )
            val `workouts`: LocalizedString get() = LocalizedString(
                key = StringKey("ring.metric.workouts", "ring_metric_workouts", emptyList(), 1),
            )
            val `stress`: LocalizedString get() = LocalizedString(
                key = StringKey("ring.metric.stress", "ring_metric_stress", emptyList(), 1),
            )
            val `active_energy`: LocalizedString get() = LocalizedString(
                key = StringKey("ring.metric.active_energy", "ring_metric_active_energy", emptyList(), 1),
            )
            val `distance`: LocalizedString get() = LocalizedString(
                key = StringKey("ring.metric.distance", "ring_metric_distance", emptyList(), 1),
            )
        }
        object `MetricStatus` {
            val `unverified`: LocalizedString get() = LocalizedString(
                key = StringKey("ring.metricStatus.unverified", "ring_metric_status_unverified", emptyList(), 1),
            )
            val `data`: LocalizedString get() = LocalizedString(
                key = StringKey("ring.metricStatus.data", "ring_metric_status_data", emptyList(), 1),
            )
            val `no_data`: LocalizedString get() = LocalizedString(
                key = StringKey("ring.metricStatus.no_data", "ring_metric_status_no_data", emptyList(), 1),
            )
            val `unsupported`: LocalizedString get() = LocalizedString(
                key = StringKey("ring.metricStatus.unsupported", "ring_metric_status_unsupported", emptyList(), 1),
            )
            val `invalid`: LocalizedString get() = LocalizedString(
                key = StringKey("ring.metricStatus.invalid", "ring_metric_status_invalid", emptyList(), 1),
            )
            val `timeout`: LocalizedString get() = LocalizedString(
                key = StringKey("ring.metricStatus.timeout", "ring_metric_status_timeout", emptyList(), 1),
            )
        }
        val `background`: LocalizedString get() = LocalizedString(
            key = StringKey("ring.background", "ring_background", emptyList(), 1),
        )
        val `backgroundHint`: LocalizedString get() = LocalizedString(
            key = StringKey("ring.backgroundHint", "ring_background_hint", emptyList(), 1),
        )
        val `connectHint`: LocalizedString get() = LocalizedString(
            key = StringKey("ring.connectHint", "ring_connect_hint", emptyList(), 1),
        )
        val `bluetoothOff`: LocalizedString get() = LocalizedString(
            key = StringKey("ring.bluetoothOff", "ring_bluetooth_off", emptyList(), 1),
        )
        val `permission`: LocalizedString get() = LocalizedString(
            key = StringKey("ring.permission", "ring_permission", emptyList(), 1),
        )
        val `scan`: LocalizedString get() = LocalizedString(
            key = StringKey("ring.scan", "ring_scan", emptyList(), 1),
        )
        val `select`: LocalizedString get() = LocalizedString(
            key = StringKey("ring.select", "ring_select", emptyList(), 1),
        )
        val `sync`: LocalizedString get() = LocalizedString(
            key = StringKey("ring.sync", "ring_sync", emptyList(), 1),
        )
        val `forget`: LocalizedString get() = LocalizedString(
            key = StringKey("ring.forget", "ring_forget", emptyList(), 1),
        )
        val `resetStorage`: LocalizedString get() = LocalizedString(
            key = StringKey("ring.resetStorage", "ring_reset_storage", emptyList(), 1),
        )
        val `resetStorageHint`: LocalizedString get() = LocalizedString(
            key = StringKey("ring.resetStorageHint", "ring_reset_storage_hint", emptyList(), 1),
        )
        val `forgetHint`: LocalizedString get() = LocalizedString(
            key = StringKey("ring.forgetHint", "ring_forget_hint", emptyList(), 1),
        )
        fun `battery`(`value`: String): LocalizedString = LocalizedString(
            key = StringKey("ring.battery", "ring_battery", listOf("value"), 1),
            arguments = listOf(`value`),
        )
        val `charging`: LocalizedString get() = LocalizedString(
            key = StringKey("ring.charging", "ring_charging", emptyList(), 1),
        )
        fun `firmware`(`value`: String): LocalizedString = LocalizedString(
            key = StringKey("ring.firmware", "ring_firmware", listOf("value"), 1),
            arguments = listOf(`value`),
        )
        fun `hardware`(`value`: String): LocalizedString = LocalizedString(
            key = StringKey("ring.hardware", "ring_hardware", listOf("value"), 1),
            arguments = listOf(`value`),
        )
        val `lastSync`: LocalizedString get() = LocalizedString(
            key = StringKey("ring.lastSync", "ring_last_sync", emptyList(), 1),
        )
        val `lastUpload`: LocalizedString get() = LocalizedString(
            key = StringKey("ring.lastUpload", "ring_last_upload", emptyList(), 1),
        )
        val `queue`: LocalizedString get() = LocalizedString(
            key = StringKey("ring.queue", "ring_queue", emptyList(), 1),
        )
        val `never`: LocalizedString get() = LocalizedString(
            key = StringKey("ring.never", "ring_never", emptyList(), 1),
        )
        val `disableImport`: LocalizedString get() = LocalizedString(
            key = StringKey("ring.disableImport", "ring_disable_import", emptyList(), 1),
        )
        fun `received`(`when`: String): LocalizedString = LocalizedString(
            key = StringKey("ring.received", "ring_received", listOf("when"), 1),
            arguments = listOf(`when`),
        )
        val `protocolUnverified`: LocalizedString get() = LocalizedString(
            key = StringKey("ring.protocolUnverified", "ring_protocol_unverified", emptyList(), 1),
        )
        val `sensorEmpty`: LocalizedString get() = LocalizedString(
            key = StringKey("ring.sensorEmpty", "ring_sensor_empty", emptyList(), 1),
        )
        fun `reportedRange`(`min`: String, `max`: String): LocalizedString = LocalizedString(
            key = StringKey("ring.reportedRange", "ring_reported_range", listOf("min", "max"), 1),
            arguments = listOf(`min`, `max`),
        )
        fun `intervalEnd`(`when`: String): LocalizedString = LocalizedString(
            key = StringKey("ring.intervalEnd", "ring_interval_end", listOf("when"), 1),
            arguments = listOf(`when`),
        )
        val `loadMore`: LocalizedString get() = LocalizedString(
            key = StringKey("ring.loadMore", "ring_load_more", emptyList(), 1),
        )
        val `nextPageFailed`: LocalizedString get() = LocalizedString(
            key = StringKey("ring.nextPageFailed", "ring_next_page_failed", emptyList(), 1),
        )
        object `Measurement` {
            val `title`: LocalizedString get() = LocalizedString(
                key = StringKey("ring.measurement.title", "ring_measurement_title", emptyList(), 1),
            )
            object `Availability` {
                val `readAvailable`: LocalizedString get() = LocalizedString(
                    key = StringKey("ring.measurement.availability.readAvailable", "ring_measurement_availability_read_available", emptyList(), 1),
                )
                val `readUnavailable`: LocalizedString get() = LocalizedString(
                    key = StringKey("ring.measurement.availability.readUnavailable", "ring_measurement_availability_read_unavailable", emptyList(), 1),
                )
                val `readUnconfirmed`: LocalizedString get() = LocalizedString(
                    key = StringKey("ring.measurement.availability.readUnconfirmed", "ring_measurement_availability_read_unconfirmed", emptyList(), 1),
                )
                val `writeAvailable`: LocalizedString get() = LocalizedString(
                    key = StringKey("ring.measurement.availability.writeAvailable", "ring_measurement_availability_write_available", emptyList(), 1),
                )
                val `writeUnavailable`: LocalizedString get() = LocalizedString(
                    key = StringKey("ring.measurement.availability.writeUnavailable", "ring_measurement_availability_write_unavailable", emptyList(), 1),
                )
                val `writeUnconfirmed`: LocalizedString get() = LocalizedString(
                    key = StringKey("ring.measurement.availability.writeUnconfirmed", "ring_measurement_availability_write_unconfirmed", emptyList(), 1),
                )
            }
            fun `savedInterval`(`minutes`: String): LocalizedString = LocalizedString(
                key = StringKey("ring.measurement.savedInterval", "ring_measurement_saved_interval", listOf("minutes"), 1),
                arguments = listOf(`minutes`),
            )
            val `readbackConfirmed`: LocalizedString get() = LocalizedString(
                key = StringKey("ring.measurement.readbackConfirmed", "ring_measurement_readback_confirmed", emptyList(), 1),
            )
            val `readbackUnconfirmed`: LocalizedString get() = LocalizedString(
                key = StringKey("ring.measurement.readbackUnconfirmed", "ring_measurement_readback_unconfirmed", emptyList(), 1),
            )
            val `readbackMissing`: LocalizedString get() = LocalizedString(
                key = StringKey("ring.measurement.readbackMissing", "ring_measurement_readback_missing", emptyList(), 1),
            )
            val `lastRead`: LocalizedString get() = LocalizedString(
                key = StringKey("ring.measurement.lastRead", "ring_measurement_last_read", emptyList(), 1),
            )
            val `readTimeMissing`: LocalizedString get() = LocalizedString(
                key = StringKey("ring.measurement.readTimeMissing", "ring_measurement_read_time_missing", emptyList(), 1),
            )
            val `keepSettings`: LocalizedString get() = LocalizedString(
                key = StringKey("ring.measurement.keepSettings", "ring_measurement_keep_settings", emptyList(), 1),
            )
            val `capabilityDetails`: LocalizedString get() = LocalizedString(
                key = StringKey("ring.measurement.capabilityDetails", "ring_measurement_capability_details", emptyList(), 1),
            )
            val `resultDetails`: LocalizedString get() = LocalizedString(
                key = StringKey("ring.measurement.resultDetails", "ring_measurement_result_details", emptyList(), 1),
            )
            val `applyProfile`: LocalizedString get() = LocalizedString(
                key = StringKey("ring.measurement.applyProfile", "ring_measurement_apply_profile", emptyList(), 1),
            )
            fun `measure`(`metric`: String): LocalizedString = LocalizedString(
                key = StringKey("ring.measurement.measure", "ring_measurement_measure", listOf("metric"), 1),
                arguments = listOf(`metric`),
            )
            val `cancel`: LocalizedString get() = LocalizedString(
                key = StringKey("ring.measurement.cancel", "ring_measurement_cancel", emptyList(), 1),
            )
            object `Phase` {
                val `starting`: LocalizedString get() = LocalizedString(
                    key = StringKey("ring.measurement.phase.starting", "ring_measurement_phase_starting", emptyList(), 1),
                )
                val `measuring`: LocalizedString get() = LocalizedString(
                    key = StringKey("ring.measurement.phase.measuring", "ring_measurement_phase_measuring", emptyList(), 1),
                )
                val `stopping`: LocalizedString get() = LocalizedString(
                    key = StringKey("ring.measurement.phase.stopping", "ring_measurement_phase_stopping", emptyList(), 1),
                )
            }
            val `currentResult`: LocalizedString get() = LocalizedString(
                key = StringKey("ring.measurement.currentResult", "ring_measurement_current_result", emptyList(), 1),
            )
            val `lastSavedResult`: LocalizedString get() = LocalizedString(
                key = StringKey("ring.measurement.lastSavedResult", "ring_measurement_last_saved_result", emptyList(), 1),
            )
            object `ManualResult` {
                val `completed`: LocalizedString get() = LocalizedString(
                    key = StringKey("ring.measurement.manualResult.completed", "ring_measurement_manual_result_completed", emptyList(), 1),
                )
                val `cancelled`: LocalizedString get() = LocalizedString(
                    key = StringKey("ring.measurement.manualResult.cancelled", "ring_measurement_manual_result_cancelled", emptyList(), 1),
                )
                val `deviceError`: LocalizedString get() = LocalizedString(
                    key = StringKey("ring.measurement.manualResult.deviceError", "ring_measurement_manual_result_device_error", emptyList(), 1),
                )
                val `timeout`: LocalizedString get() = LocalizedString(
                    key = StringKey("ring.measurement.manualResult.timeout", "ring_measurement_manual_result_timeout", emptyList(), 1),
                )
                val `stopUnconfirmed`: LocalizedString get() = LocalizedString(
                    key = StringKey("ring.measurement.manualResult.stopUnconfirmed", "ring_measurement_manual_result_stop_unconfirmed", emptyList(), 1),
                )
                val `rejected`: LocalizedString get() = LocalizedString(
                    key = StringKey("ring.measurement.manualResult.rejected", "ring_measurement_manual_result_rejected", emptyList(), 1),
                )
                val `noValue`: LocalizedString get() = LocalizedString(
                    key = StringKey("ring.measurement.manualResult.noValue", "ring_measurement_manual_result_no_value", emptyList(), 1),
                )
            }
            val `stressEnable`: LocalizedString get() = LocalizedString(
                key = StringKey("ring.measurement.stressEnable", "ring_measurement_stress_enable", emptyList(), 1),
            )
            val `stressDisable`: LocalizedString get() = LocalizedString(
                key = StringKey("ring.measurement.stressDisable", "ring_measurement_stress_disable", emptyList(), 1),
            )
            val `heartRateEnable`: LocalizedString get() = LocalizedString(
                key = StringKey("ring.measurement.heartRateEnable", "ring_measurement_heart_rate_enable", emptyList(), 1),
            )
            val `heartRateDisable`: LocalizedString get() = LocalizedString(
                key = StringKey("ring.measurement.heartRateDisable", "ring_measurement_heart_rate_disable", emptyList(), 1),
            )
            val `temperatureEnable`: LocalizedString get() = LocalizedString(
                key = StringKey("ring.measurement.temperatureEnable", "ring_measurement_temperature_enable", emptyList(), 1),
            )
            val `temperatureDisable`: LocalizedString get() = LocalizedString(
                key = StringKey("ring.measurement.temperatureDisable", "ring_measurement_temperature_disable", emptyList(), 1),
            )
            val `alertsTitle`: LocalizedString get() = LocalizedString(
                key = StringKey("ring.measurement.alertsTitle", "ring_measurement_alerts_title", emptyList(), 1),
            )
            val `alertLow`: LocalizedString get() = LocalizedString(
                key = StringKey("ring.measurement.alertLow", "ring_measurement_alert_low", emptyList(), 1),
            )
            val `alertHigh`: LocalizedString get() = LocalizedString(
                key = StringKey("ring.measurement.alertHigh", "ring_measurement_alert_high", emptyList(), 1),
            )
            val `alertsApply`: LocalizedString get() = LocalizedString(
                key = StringKey("ring.measurement.alertsApply", "ring_measurement_alerts_apply", emptyList(), 1),
            )
            fun `alertsSourceOnly`(`raw`: String): LocalizedString = LocalizedString(
                key = StringKey("ring.measurement.alertsSourceOnly", "ring_measurement_alerts_source_only", listOf("raw"), 1),
                arguments = listOf(`raw`),
            )
            val `refresh`: LocalizedString get() = LocalizedString(
                key = StringKey("ring.measurement.refresh", "ring_measurement_refresh", emptyList(), 1),
            )
            object `Result` {
                val `verified`: LocalizedString get() = LocalizedString(
                    key = StringKey("ring.measurement.result.verified", "ring_measurement_result_verified", emptyList(), 1),
                )
                val `rejected`: LocalizedString get() = LocalizedString(
                    key = StringKey("ring.measurement.result.rejected", "ring_measurement_result_rejected", emptyList(), 1),
                )
                val `unknown`: LocalizedString get() = LocalizedString(
                    key = StringKey("ring.measurement.result.unknown", "ring_measurement_result_unknown", emptyList(), 1),
                )
            }
            val `autonomousTemperature`: LocalizedString get() = LocalizedString(
                key = StringKey("ring.measurement.autonomousTemperature", "ring_measurement_autonomous_temperature", emptyList(), 1),
            )
            val `disabled`: LocalizedString get() = LocalizedString(
                key = StringKey("ring.measurement.disabled", "ring_measurement_disabled", emptyList(), 1),
            )
            val `enabled`: LocalizedString get() = LocalizedString(
                key = StringKey("ring.measurement.enabled", "ring_measurement_enabled", emptyList(), 1),
            )
            val `heartRateFiveMinutes`: LocalizedString get() = LocalizedString(
                key = StringKey("ring.measurement.heartRateFiveMinutes", "ring_measurement_heart_rate_five_minutes", emptyList(), 1),
            )
            val `hrvDisable`: LocalizedString get() = LocalizedString(
                key = StringKey("ring.measurement.hrvDisable", "ring_measurement_hrv_disable", emptyList(), 1),
            )
            val `hrvEnable`: LocalizedString get() = LocalizedString(
                key = StringKey("ring.measurement.hrvEnable", "ring_measurement_hrv_enable", emptyList(), 1),
            )
            fun `interval`(`minutes`: String): LocalizedString = LocalizedString(
                key = StringKey("ring.measurement.interval", "ring_measurement_interval", listOf("minutes"), 1),
                arguments = listOf(`minutes`),
            )
            val `read`: LocalizedString get() = LocalizedString(
                key = StringKey("ring.measurement.read", "ring_measurement_read", emptyList(), 1),
            )
            val `receivedByPhone`: LocalizedString get() = LocalizedString(
                key = StringKey("ring.measurement.receivedByPhone", "ring_measurement_received_by_phone", emptyList(), 1),
            )
            val `spo2Disable`: LocalizedString get() = LocalizedString(
                key = StringKey("ring.measurement.spo2Disable", "ring_measurement_spo2_disable", emptyList(), 1),
            )
            val `spo2Enable`: LocalizedString get() = LocalizedString(
                key = StringKey("ring.measurement.spo2Enable", "ring_measurement_spo2_enable", emptyList(), 1),
            )
            val `valueMissing`: LocalizedString get() = LocalizedString(
                key = StringKey("ring.measurement.valueMissing", "ring_measurement_value_missing", emptyList(), 1),
            )
        }
        object `Workout` {
            val `title`: LocalizedString get() = LocalizedString(
                key = StringKey("ring.workout.title", "ring_workout_title", emptyList(), 1),
            )
            val `start`: LocalizedString get() = LocalizedString(
                key = StringKey("ring.workout.start", "ring_workout_start", emptyList(), 1),
            )
            val `takeover`: LocalizedString get() = LocalizedString(
                key = StringKey("ring.workout.takeover", "ring_workout_takeover", emptyList(), 1),
            )
            val `stop`: LocalizedString get() = LocalizedString(
                key = StringKey("ring.workout.stop", "ring_workout_stop", emptyList(), 1),
            )
            val `refresh`: LocalizedString get() = LocalizedString(
                key = StringKey("ring.workout.refresh", "ring_workout_refresh", emptyList(), 1),
            )
            val `offlineHint`: LocalizedString get() = LocalizedString(
                key = StringKey("ring.workout.offlineHint", "ring_workout_offline_hint", emptyList(), 1),
            )
            val `connectHint`: LocalizedString get() = LocalizedString(
                key = StringKey("ring.workout.connectHint", "ring_workout_connect_hint", emptyList(), 1),
            )
            val `unverified`: LocalizedString get() = LocalizedString(
                key = StringKey("ring.workout.unverified", "ring_workout_unverified", emptyList(), 1),
            )
            val `lastPulse`: LocalizedString get() = LocalizedString(
                key = StringKey("ring.workout.lastPulse", "ring_workout_last_pulse", emptyList(), 1),
            )
            val `bpm`: LocalizedString get() = LocalizedString(
                key = StringKey("ring.workout.bpm", "ring_workout_bpm", emptyList(), 1),
            )
            val `stale`: LocalizedString get() = LocalizedString(
                key = StringKey("ring.workout.stale", "ring_workout_stale", emptyList(), 1),
            )
            val `recent`: LocalizedString get() = LocalizedString(
                key = StringKey("ring.workout.recent", "ring_workout_recent", emptyList(), 1),
            )
            val `qualityUnknown`: LocalizedString get() = LocalizedString(
                key = StringKey("ring.workout.qualityUnknown", "ring_workout_quality_unknown", emptyList(), 1),
            )
            fun `gaps`(`count`: String): LocalizedString = LocalizedString(
                key = StringKey("ring.workout.gaps", "ring_workout_gaps", listOf("count"), 1),
                arguments = listOf(`count`),
            )
            val `unknownHint`: LocalizedString get() = LocalizedString(
                key = StringKey("ring.workout.unknownHint", "ring_workout_unknown_hint", emptyList(), 1),
            )
            val `permission`: LocalizedString get() = LocalizedString(
                key = StringKey("ring.workout.permission", "ring_workout_permission", emptyList(), 1),
            )
            val `autoReady`: LocalizedString get() = LocalizedString(
                key = StringKey("ring.workout.autoReady", "ring_workout_auto_ready", emptyList(), 1),
            )
            val `autoPermissionHint`: LocalizedString get() = LocalizedString(
                key = StringKey("ring.workout.autoPermissionHint", "ring_workout_auto_permission_hint", emptyList(), 1),
            )
            val `phoneSource`: LocalizedString get() = LocalizedString(
                key = StringKey("ring.workout.phoneSource", "ring_workout_phone_source", emptyList(), 1),
            )
            val `showHeartRate`: LocalizedString get() = LocalizedString(
                key = StringKey("ring.workout.showHeartRate", "ring_workout_show_heart_rate", emptyList(), 1),
            )
            val `hideHeartRate`: LocalizedString get() = LocalizedString(
                key = StringKey("ring.workout.hideHeartRate", "ring_workout_hide_heart_rate", emptyList(), 1),
            )
            val `loadingHistory`: LocalizedString get() = LocalizedString(
                key = StringKey("ring.workout.loadingHistory", "ring_workout_loading_history", emptyList(), 1),
            )
            val `noSamples`: LocalizedString get() = LocalizedString(
                key = StringKey("ring.workout.noSamples", "ring_workout_no_samples", emptyList(), 1),
            )
            object `Chart` {
                val `running`: LocalizedString get() = LocalizedString(
                    key = StringKey("ring.workout.chart.running", "ring_workout_chart_running", emptyList(), 1),
                )
                val `workout`: LocalizedString get() = LocalizedString(
                    key = StringKey("ring.workout.chart.workout", "ring_workout_chart_workout", emptyList(), 1),
                )
                val `recovery`: LocalizedString get() = LocalizedString(
                    key = StringKey("ring.workout.chart.recovery", "ring_workout_chart_recovery", emptyList(), 1),
                )
                val `actualSamples`: LocalizedString get() = LocalizedString(
                    key = StringKey("ring.workout.chart.actualSamples", "ring_workout_chart_actual_samples", emptyList(), 1),
                )
            }
            object `Status` {
                val `completed`: LocalizedString get() = LocalizedString(
                    key = StringKey("ring.workout.status.completed", "ring_workout_status_completed", emptyList(), 1),
                )
                val `interrupted`: LocalizedString get() = LocalizedString(
                    key = StringKey("ring.workout.status.interrupted", "ring_workout_status_interrupted", emptyList(), 1),
                )
            }
            object `Trigger` {
                val `manual`: LocalizedString get() = LocalizedString(
                    key = StringKey("ring.workout.trigger.manual", "ring_workout_trigger_manual", emptyList(), 1),
                )
                val `automatic`: LocalizedString get() = LocalizedString(
                    key = StringKey("ring.workout.trigger.automatic", "ring_workout_trigger_automatic", emptyList(), 1),
                )
                val `manual_takeover`: LocalizedString get() = LocalizedString(
                    key = StringKey("ring.workout.trigger.manual_takeover", "ring_workout_trigger_manual_takeover", emptyList(), 1),
                )
            }
            object `Phase` {
                val `idle`: LocalizedString get() = LocalizedString(
                    key = StringKey("ring.workout.phase.idle", "ring_workout_phase_idle", emptyList(), 1),
                )
                val `starting`: LocalizedString get() = LocalizedString(
                    key = StringKey("ring.workout.phase.starting", "ring_workout_phase_starting", emptyList(), 1),
                )
                val `pausing`: LocalizedString get() = LocalizedString(
                    key = StringKey("ring.workout.phase.pausing", "ring_workout_phase_pausing", emptyList(), 1),
                )
                val `paused`: LocalizedString get() = LocalizedString(
                    key = StringKey("ring.workout.phase.paused", "ring_workout_phase_paused", emptyList(), 1),
                )
                val `resuming`: LocalizedString get() = LocalizedString(
                    key = StringKey("ring.workout.phase.resuming", "ring_workout_phase_resuming", emptyList(), 1),
                )
                val `recording`: LocalizedString get() = LocalizedString(
                    key = StringKey("ring.workout.phase.recording", "ring_workout_phase_recording", emptyList(), 1),
                )
                val `grace`: LocalizedString get() = LocalizedString(
                    key = StringKey("ring.workout.phase.grace", "ring_workout_phase_grace", emptyList(), 1),
                )
                val `recovery`: LocalizedString get() = LocalizedString(
                    key = StringKey("ring.workout.phase.recovery", "ring_workout_phase_recovery", emptyList(), 1),
                )
                val `stopping`: LocalizedString get() = LocalizedString(
                    key = StringKey("ring.workout.phase.stopping", "ring_workout_phase_stopping", emptyList(), 1),
                )
                val `interrupted`: LocalizedString get() = LocalizedString(
                    key = StringKey("ring.workout.phase.interrupted", "ring_workout_phase_interrupted", emptyList(), 1),
                )
                val `unknown`: LocalizedString get() = LocalizedString(
                    key = StringKey("ring.workout.phase.unknown", "ring_workout_phase_unknown", emptyList(), 1),
                )
                val `unavailable`: LocalizedString get() = LocalizedString(
                    key = StringKey("ring.workout.phase.unavailable", "ring_workout_phase_unavailable", emptyList(), 1),
                )
            }
            fun `activeDuration`(`duration`: String): LocalizedString = LocalizedString(
                key = StringKey("ring.workout.activeDuration", "ring_workout_active_duration", listOf("duration"), 1),
                arguments = listOf(`duration`),
            )
            val `autoBlocked`: LocalizedString get() = LocalizedString(
                key = StringKey("ring.workout.autoBlocked", "ring_workout_auto_blocked", emptyList(), 1),
            )
            val `backgroundLocationPermission`: LocalizedString get() = LocalizedString(
                key = StringKey("ring.workout.backgroundLocationPermission", "ring_workout_background_location_permission", emptyList(), 1),
            )
            fun `commandCandidate`(`mode`: String): LocalizedString = LocalizedString(
                key = StringKey("ring.workout.commandCandidate", "ring_workout_command_candidate", listOf("mode"), 1),
                arguments = listOf(`mode`),
            )
            val `commandPendingHint`: LocalizedString get() = LocalizedString(
                key = StringKey("ring.workout.commandPendingHint", "ring_workout_command_pending_hint", emptyList(), 1),
            )
            fun `gpsAccuracy`(`time`: String, `accuracy`: String, `quality`: String): LocalizedString = LocalizedString(
                key = StringKey("ring.workout.gpsAccuracy", "ring_workout_gps_accuracy", listOf("time", "accuracy", "quality"), 1),
                arguments = listOf(`time`, `accuracy`, `quality`),
            )
            fun `gpsGaps`(`count`: String): LocalizedString = LocalizedString(
                key = StringKey("ring.workout.gpsGaps", "ring_workout_gps_gaps", listOf("count"), 1),
                arguments = listOf(`count`),
            )
            val `gpsPermissionHint`: LocalizedString get() = LocalizedString(
                key = StringKey("ring.workout.gpsPermissionHint", "ring_workout_gps_permission_hint", emptyList(), 1),
            )
            val `independentStreams`: LocalizedString get() = LocalizedString(
                key = StringKey("ring.workout.independentStreams", "ring_workout_independent_streams", emptyList(), 1),
            )
            val `locationPermission`: LocalizedString get() = LocalizedString(
                key = StringKey("ring.workout.locationPermission", "ring_workout_location_permission", emptyList(), 1),
            )
            val `modeNotVerified`: LocalizedString get() = LocalizedString(
                key = StringKey("ring.workout.modeNotVerified", "ring_workout_mode_not_verified", emptyList(), 1),
            )
            val `modeUnverified`: LocalizedString get() = LocalizedString(
                key = StringKey("ring.workout.modeUnverified", "ring_workout_mode_unverified", emptyList(), 1),
            )
            val `modeVerified`: LocalizedString get() = LocalizedString(
                key = StringKey("ring.workout.modeVerified", "ring_workout_mode_verified", emptyList(), 1),
            )
            val `noCandidate`: LocalizedString get() = LocalizedString(
                key = StringKey("ring.workout.noCandidate", "ring_workout_no_candidate", emptyList(), 1),
            )
            val `pause`: LocalizedString get() = LocalizedString(
                key = StringKey("ring.workout.pause", "ring_workout_pause", emptyList(), 1),
            )
            val `pausedHint`: LocalizedString get() = LocalizedString(
                key = StringKey("ring.workout.pausedHint", "ring_workout_paused_hint", emptyList(), 1),
            )
            val `phoneGps`: LocalizedString get() = LocalizedString(
                key = StringKey("ring.workout.phoneGps", "ring_workout_phone_gps", emptyList(), 1),
            )
            val `phoneGpsSuffix`: LocalizedString get() = LocalizedString(
                key = StringKey("ring.workout.phoneGpsSuffix", "ring_workout_phone_gps_suffix", emptyList(), 1),
            )
            val `planDetails`: LocalizedString get() = LocalizedString(
                key = StringKey("ring.workout.planDetails", "ring_workout_plan_details", emptyList(), 1),
            )
            val `pulseTitle`: LocalizedString get() = LocalizedString(
                key = StringKey("ring.workout.pulseTitle", "ring_workout_pulse_title", emptyList(), 1),
            )
            val `rawPulse`: LocalizedString get() = LocalizedString(
                key = StringKey("ring.workout.rawPulse", "ring_workout_raw_pulse", emptyList(), 1),
            )
            val `recordGps`: LocalizedString get() = LocalizedString(
                key = StringKey("ring.workout.recordGps", "ring_workout_record_gps", emptyList(), 1),
            )
            object `RecordingSource` {
                val `phone_only`: LocalizedString get() = LocalizedString(
                    key = StringKey("ring.workout.recordingSource.phone_only", "ring_workout_recording_source_phone_only", emptyList(), 1),
                )
                val `phone_with_ring_hr`: LocalizedString get() = LocalizedString(
                    key = StringKey("ring.workout.recordingSource.phone_with_ring_hr", "ring_workout_recording_source_phone_with_ring_hr", emptyList(), 1),
                )
                val `ring_controlled`: LocalizedString get() = LocalizedString(
                    key = StringKey("ring.workout.recordingSource.ring_controlled", "ring_workout_recording_source_ring_controlled", emptyList(), 1),
                )
            }
            val `resume`: LocalizedString get() = LocalizedString(
                key = StringKey("ring.workout.resume", "ring_workout_resume", emptyList(), 1),
            )
            fun `ringMode`(`mode`: String): LocalizedString = LocalizedString(
                key = StringKey("ring.workout.ringMode", "ring_workout_ring_mode", listOf("mode"), 1),
                arguments = listOf(`mode`),
            )
            val `routeNotSelected`: LocalizedString get() = LocalizedString(
                key = StringKey("ring.workout.routeNotSelected", "ring_workout_route_not_selected", emptyList(), 1),
            )
            val `routeSelected`: LocalizedString get() = LocalizedString(
                key = StringKey("ring.workout.routeSelected", "ring_workout_route_selected", emptyList(), 1),
            )
            val `sport`: LocalizedString get() = LocalizedString(
                key = StringKey("ring.workout.sport", "ring_workout_sport", emptyList(), 1),
            )
            val `sportModeHint`: LocalizedString get() = LocalizedString(
                key = StringKey("ring.workout.sportModeHint", "ring_workout_sport_mode_hint", emptyList(), 1),
            )
            fun `staleTimer`(`error`: String): LocalizedString = LocalizedString(
                key = StringKey("ring.workout.staleTimer", "ring_workout_stale_timer", listOf("error"), 1),
                arguments = listOf(`error`),
            )
            object `StreamStatus` {
                val `idle`: LocalizedString get() = LocalizedString(
                    key = StringKey("ring.workout.streamStatus.idle", "ring_workout_stream_status_idle", emptyList(), 1),
                )
                val `waiting_sample`: LocalizedString get() = LocalizedString(
                    key = StringKey("ring.workout.streamStatus.waiting_sample", "ring_workout_stream_status_waiting_sample", emptyList(), 1),
                )
                val `waiting_fix`: LocalizedString get() = LocalizedString(
                    key = StringKey("ring.workout.streamStatus.waiting_fix", "ring_workout_stream_status_waiting_fix", emptyList(), 1),
                )
                val `recording`: LocalizedString get() = LocalizedString(
                    key = StringKey("ring.workout.streamStatus.recording", "ring_workout_stream_status_recording", emptyList(), 1),
                )
                val `unavailable`: LocalizedString get() = LocalizedString(
                    key = StringKey("ring.workout.streamStatus.unavailable", "ring_workout_stream_status_unavailable", emptyList(), 1),
                )
                val `gap`: LocalizedString get() = LocalizedString(
                    key = StringKey("ring.workout.streamStatus.gap", "ring_workout_stream_status_gap", emptyList(), 1),
                )
                val `stopped`: LocalizedString get() = LocalizedString(
                    key = StringKey("ring.workout.streamStatus.stopped", "ring_workout_stream_status_stopped", emptyList(), 1),
                )
                val `unknown`: LocalizedString get() = LocalizedString(
                    key = StringKey("ring.workout.streamStatus.unknown", "ring_workout_stream_status_unknown", emptyList(), 1),
                )
            }
            fun `totalDuration`(`duration`: String): LocalizedString = LocalizedString(
                key = StringKey("ring.workout.totalDuration", "ring_workout_total_duration", listOf("duration"), 1),
                arguments = listOf(`duration`),
            )
        }
        object `Error` {
            val `request_failed`: LocalizedString get() = LocalizedString(
                key = StringKey("ring.error.request_failed", "ring_error_request_failed", emptyList(), 1),
            )
            val `response_timeout`: LocalizedString get() = LocalizedString(
                key = StringKey("ring.error.response_timeout", "ring_error_response_timeout", emptyList(), 1),
            )
            val `write_failed`: LocalizedString get() = LocalizedString(
                key = StringKey("ring.error.write_failed", "ring_error_write_failed", emptyList(), 1),
            )
            val `running_ambiguous_time`: LocalizedString get() = LocalizedString(
                key = StringKey("ring.error.running_ambiguous_time", "ring_error_running_ambiguous_time", emptyList(), 1),
            )
            val `invalid_running_snapshot`: LocalizedString get() = LocalizedString(
                key = StringKey("ring.error.invalid_running_snapshot", "ring_error_invalid_running_snapshot", emptyList(), 1),
            )
            val `invalid_legacy_hr_context`: LocalizedString get() = LocalizedString(
                key = StringKey("ring.error.invalid_legacy_hr_context", "ring_error_invalid_legacy_hr_context", emptyList(), 1),
            )
            val `query_failed`: LocalizedString get() = LocalizedString(
                key = StringKey("ring.error.query_failed", "ring_error_query_failed", emptyList(), 1),
            )
            val `operation_changed`: LocalizedString get() = LocalizedString(
                key = StringKey("ring.error.operation_changed", "ring_error_operation_changed", emptyList(), 1),
            )
            val `operation_busy`: LocalizedString get() = LocalizedString(
                key = StringKey("ring.error.operation_busy", "ring_error_operation_busy", emptyList(), 1),
            )
            val `store_unavailable`: LocalizedString get() = LocalizedString(
                key = StringKey("ring.error.store_unavailable", "ring_error_store_unavailable", emptyList(), 1),
            )
            val `permission_required`: LocalizedString get() = LocalizedString(
                key = StringKey("ring.error.permission_required", "ring_error_permission_required", emptyList(), 1),
            )
            val `permission_denied`: LocalizedString get() = LocalizedString(
                key = StringKey("ring.error.permission_denied", "ring_error_permission_denied", emptyList(), 1),
            )
            val `bluetooth_disabled`: LocalizedString get() = LocalizedString(
                key = StringKey("ring.error.bluetooth_disabled", "ring_error_bluetooth_disabled", emptyList(), 1),
            )
            val `queue_full`: LocalizedString get() = LocalizedString(
                key = StringKey("ring.error.queue_full", "ring_error_queue_full", emptyList(), 1),
            )
            val `auth_required`: LocalizedString get() = LocalizedString(
                key = StringKey("ring.error.auth_required", "ring_error_auth_required", emptyList(), 1),
            )
            val `grant_revoked`: LocalizedString get() = LocalizedString(
                key = StringKey("ring.error.grant_revoked", "ring_error_grant_revoked", emptyList(), 1),
            )
            val `timeout`: LocalizedString get() = LocalizedString(
                key = StringKey("ring.error.timeout", "ring_error_timeout", emptyList(), 1),
            )
            val `unavailable`: LocalizedString get() = LocalizedString(
                key = StringKey("ring.error.unavailable", "ring_error_unavailable", emptyList(), 1),
            )
        }
        object `Collector` {
            object `ImportStatus` {
                val `available`: LocalizedString get() = LocalizedString(
                    key = StringKey("ring.collector.importStatus.available", "ring_collector_import_status_available", emptyList(), 1),
                )
                val `unverified`: LocalizedString get() = LocalizedString(
                    key = StringKey("ring.collector.importStatus.unverified", "ring_collector_import_status_unverified", emptyList(), 1),
                )
                val `missing`: LocalizedString get() = LocalizedString(
                    key = StringKey("ring.collector.importStatus.missing", "ring_collector_import_status_missing", emptyList(), 1),
                )
                val `unsupported`: LocalizedString get() = LocalizedString(
                    key = StringKey("ring.collector.importStatus.unsupported", "ring_collector_import_status_unsupported", emptyList(), 1),
                )
                val `no_observation`: LocalizedString get() = LocalizedString(
                    key = StringKey("ring.collector.importStatus.no_observation", "ring_collector_import_status_no_observation", emptyList(), 1),
                )
                val `unsupported_evidenced`: LocalizedString get() = LocalizedString(
                    key = StringKey("ring.collector.importStatus.unsupported_evidenced", "ring_collector_import_status_unsupported_evidenced", emptyList(), 1),
                )
                val `source_unavailable`: LocalizedString get() = LocalizedString(
                    key = StringKey("ring.collector.importStatus.source_unavailable", "ring_collector_import_status_source_unavailable", emptyList(), 1),
                )
                val `protocol_unverified`: LocalizedString get() = LocalizedString(
                    key = StringKey("ring.collector.importStatus.protocol_unverified", "ring_collector_import_status_protocol_unverified", emptyList(), 1),
                )
                val `semantic_unknown`: LocalizedString get() = LocalizedString(
                    key = StringKey("ring.collector.importStatus.semantic_unknown", "ring_collector_import_status_semantic_unknown", emptyList(), 1),
                )
                val `ambiguous_layout`: LocalizedString get() = LocalizedString(
                    key = StringKey("ring.collector.importStatus.ambiguous_layout", "ring_collector_import_status_ambiguous_layout", emptyList(), 1),
                )
                val `workout_time_basis_unconfirmed`: LocalizedString get() = LocalizedString(
                    key = StringKey("ring.collector.importStatus.workout_time_basis_unconfirmed", "ring_collector_import_status_workout_time_basis_unconfirmed", emptyList(), 1),
                )
                val `interval_increment`: LocalizedString get() = LocalizedString(
                    key = StringKey("ring.collector.importStatus.interval_increment", "ring_collector_import_status_interval_increment", emptyList(), 1),
                )
                val `day_total`: LocalizedString get() = LocalizedString(
                    key = StringKey("ring.collector.importStatus.day_total", "ring_collector_import_status_day_total", emptyList(), 1),
                )
                val `counter`: LocalizedString get() = LocalizedString(
                    key = StringKey("ring.collector.importStatus.counter", "ring_collector_import_status_counter", emptyList(), 1),
                )
                val `day_counter_snapshot`: LocalizedString get() = LocalizedString(
                    key = StringKey("ring.collector.importStatus.day_counter_snapshot", "ring_collector_import_status_day_counter_snapshot", emptyList(), 1),
                )
                val `workout_report`: LocalizedString get() = LocalizedString(
                    key = StringKey("ring.collector.importStatus.workout_report", "ring_collector_import_status_workout_report", emptyList(), 1),
                )
                val `point`: LocalizedString get() = LocalizedString(
                    key = StringKey("ring.collector.importStatus.point", "ring_collector_import_status_point", emptyList(), 1),
                )
                val `interval_mean`: LocalizedString get() = LocalizedString(
                    key = StringKey("ring.collector.importStatus.interval_mean", "ring_collector_import_status_interval_mean", emptyList(), 1),
                )
                val `sample_window_mean`: LocalizedString get() = LocalizedString(
                    key = StringKey("ring.collector.importStatus.sample_window_mean", "ring_collector_import_status_sample_window_mean", emptyList(), 1),
                )
                val `range`: LocalizedString get() = LocalizedString(
                    key = StringKey("ring.collector.importStatus.range", "ring_collector_import_status_range", emptyList(), 1),
                )
                val `sleep_report`: LocalizedString get() = LocalizedString(
                    key = StringKey("ring.collector.importStatus.sleep_report", "ring_collector_import_status_sleep_report", emptyList(), 1),
                )
                val `reported_unknown`: LocalizedString get() = LocalizedString(
                    key = StringKey("ring.collector.importStatus.reported_unknown", "ring_collector_import_status_reported_unknown", emptyList(), 1),
                )
                val `reported_range`: LocalizedString get() = LocalizedString(
                    key = StringKey("ring.collector.importStatus.reported_range", "ring_collector_import_status_reported_range", emptyList(), 1),
                )
                val `reported_window`: LocalizedString get() = LocalizedString(
                    key = StringKey("ring.collector.importStatus.reported_window", "ring_collector_import_status_reported_window", emptyList(), 1),
                )
                val `partial`: LocalizedString get() = LocalizedString(
                    key = StringKey("ring.collector.importStatus.partial", "ring_collector_import_status_partial", emptyList(), 1),
                )
                val `unknown`: LocalizedString get() = LocalizedString(
                    key = StringKey("ring.collector.importStatus.unknown", "ring_collector_import_status_unknown", emptyList(), 1),
                )
                val `connected`: LocalizedString get() = LocalizedString(
                    key = StringKey("ring.collector.importStatus.connected", "ring_collector_import_status_connected", emptyList(), 1),
                )
                val `disabled`: LocalizedString get() = LocalizedString(
                    key = StringKey("ring.collector.importStatus.disabled", "ring_collector_import_status_disabled", emptyList(), 1),
                )
                val `unavailable`: LocalizedString get() = LocalizedString(
                    key = StringKey("ring.collector.importStatus.unavailable", "ring_collector_import_status_unavailable", emptyList(), 1),
                )
            }
            object `CaptureStatus` {
                val `complete`: LocalizedString get() = LocalizedString(
                    key = StringKey("ring.collector.captureStatus.complete", "ring_collector_capture_status_complete", emptyList(), 1),
                )
                val `empty`: LocalizedString get() = LocalizedString(
                    key = StringKey("ring.collector.captureStatus.empty", "ring_collector_capture_status_empty", emptyList(), 1),
                )
                val `failed`: LocalizedString get() = LocalizedString(
                    key = StringKey("ring.collector.captureStatus.failed", "ring_collector_capture_status_failed", emptyList(), 1),
                )
            }
            object `CaptureScopes` {
                val `day`: LocalizedString get() = LocalizedString(
                    key = StringKey("ring.collector.captureScopes.day", "ring_collector_capture_scopes_day", emptyList(), 1),
                )
                val `history`: LocalizedString get() = LocalizedString(
                    key = StringKey("ring.collector.captureScopes.history", "ring_collector_capture_scopes_history", emptyList(), 1),
                )
                val `live`: LocalizedString get() = LocalizedString(
                    key = StringKey("ring.collector.captureScopes.live", "ring_collector_capture_scopes_live", emptyList(), 1),
                )
                val `workout`: LocalizedString get() = LocalizedString(
                    key = StringKey("ring.collector.captureScopes.workout", "ring_collector_capture_scopes_workout", emptyList(), 1),
                )
                val `spot`: LocalizedString get() = LocalizedString(
                    key = StringKey("ring.collector.captureScopes.spot", "ring_collector_capture_scopes_spot", emptyList(), 1),
                )
            }
            object `CaptureContexts` {
                val `background`: LocalizedString get() = LocalizedString(
                    key = StringKey("ring.collector.captureContexts.background", "ring_collector_capture_contexts_background", emptyList(), 1),
                )
                val `running`: LocalizedString get() = LocalizedString(
                    key = StringKey("ring.collector.captureContexts.running", "ring_collector_capture_contexts_running", emptyList(), 1),
                )
                val `workout`: LocalizedString get() = LocalizedString(
                    key = StringKey("ring.collector.captureContexts.workout", "ring_collector_capture_contexts_workout", emptyList(), 1),
                )
                val `recovery`: LocalizedString get() = LocalizedString(
                    key = StringKey("ring.collector.captureContexts.recovery", "ring_collector_capture_contexts_recovery", emptyList(), 1),
                )
                val `manual`: LocalizedString get() = LocalizedString(
                    key = StringKey("ring.collector.captureContexts.manual", "ring_collector_capture_contexts_manual", emptyList(), 1),
                )
            }
            val `title`: LocalizedString get() = LocalizedString(
                key = StringKey("ring.collector.title", "ring_collector_title", emptyList(), 1),
            )
            val `hint`: LocalizedString get() = LocalizedString(
                key = StringKey("ring.collector.hint", "ring_collector_hint", emptyList(), 1),
            )
            fun `rawReports`(`count`: String): LocalizedString = LocalizedString(
                key = StringKey("ring.collector.rawReports", "ring_collector_raw_reports", listOf("count"), 1),
                arguments = listOf(`count`),
            )
            val `viewOriginals`: LocalizedString get() = LocalizedString(
                key = StringKey("ring.collector.viewOriginals", "ring_collector_view_originals", emptyList(), 1),
            )
            val `lastMeasurement`: LocalizedString get() = LocalizedString(
                key = StringKey("ring.collector.lastMeasurement", "ring_collector_last_measurement", emptyList(), 1),
            )
            val `lastCapture`: LocalizedString get() = LocalizedString(
                key = StringKey("ring.collector.lastCapture", "ring_collector_last_capture", emptyList(), 1),
            )
            val `captureUnknown`: LocalizedString get() = LocalizedString(
                key = StringKey("ring.collector.captureUnknown", "ring_collector_capture_unknown", emptyList(), 1),
            )
            val `captureDate`: LocalizedString get() = LocalizedString(
                key = StringKey("ring.collector.captureDate", "ring_collector_capture_date", emptyList(), 1),
            )
            val `captureScope`: LocalizedString get() = LocalizedString(
                key = StringKey("ring.collector.captureScope", "ring_collector_capture_scope", emptyList(), 1),
            )
            val `captureContext`: LocalizedString get() = LocalizedString(
                key = StringKey("ring.collector.captureContext", "ring_collector_capture_context", emptyList(), 1),
            )
            val `received`: LocalizedString get() = LocalizedString(
                key = StringKey("ring.collector.received", "ring_collector_received", emptyList(), 1),
            )
            val `deliveryUnknown`: LocalizedString get() = LocalizedString(
                key = StringKey("ring.collector.deliveryUnknown", "ring_collector_delivery_unknown", emptyList(), 1),
            )
            val `shape`: LocalizedString get() = LocalizedString(
                key = StringKey("ring.collector.shape", "ring_collector_shape", emptyList(), 1),
            )
        }
        object `Device` {
            object `Labels` {
                val `alarms`: LocalizedString get() = LocalizedString(
                    key = StringKey("ring.device.labels.alarms", "ring_device_labels_alarms", emptyList(), 1),
                )
                val `dnd`: LocalizedString get() = LocalizedString(
                    key = StringKey("ring.device.labels.dnd", "ring_device_labels_dnd", emptyList(), 1),
                )
                val `sedentary`: LocalizedString get() = LocalizedString(
                    key = StringKey("ring.device.labels.sedentary", "ring_device_labels_sedentary", emptyList(), 1),
                )
                val `power_saving`: LocalizedString get() = LocalizedString(
                    key = StringKey("ring.device.labels.power_saving", "ring_device_labels_power_saving", emptyList(), 1),
                )
                val `gestures`: LocalizedString get() = LocalizedString(
                    key = StringKey("ring.device.labels.gestures", "ring_device_labels_gestures", emptyList(), 1),
                )
                val `counter`: LocalizedString get() = LocalizedString(
                    key = StringKey("ring.device.labels.counter", "ring_device_labels_counter", emptyList(), 1),
                )
                val `drink_reminders`: LocalizedString get() = LocalizedString(
                    key = StringKey("ring.device.labels.drink_reminders", "ring_device_labels_drink_reminders", emptyList(), 1),
                )
                val `tasbih_start`: LocalizedString get() = LocalizedString(
                    key = StringKey("ring.device.labels.tasbih_start", "ring_device_labels_tasbih_start", emptyList(), 1),
                )
                val `tasbih_stop`: LocalizedString get() = LocalizedString(
                    key = StringKey("ring.device.labels.tasbih_stop", "ring_device_labels_tasbih_stop", emptyList(), 1),
                )
                val `profile`: LocalizedString get() = LocalizedString(
                    key = StringKey("ring.device.labels.profile", "ring_device_labels_profile", emptyList(), 1),
                )
                val `clock`: LocalizedString get() = LocalizedString(
                    key = StringKey("ring.device.labels.clock", "ring_device_labels_clock", emptyList(), 1),
                )
                val `find_ring`: LocalizedString get() = LocalizedString(
                    key = StringKey("ring.device.labels.find_ring", "ring_device_labels_find_ring", emptyList(), 1),
                )
                val `bind`: LocalizedString get() = LocalizedString(
                    key = StringKey("ring.device.labels.bind", "ring_device_labels_bind", emptyList(), 1),
                )
                val `unbind`: LocalizedString get() = LocalizedString(
                    key = StringKey("ring.device.labels.unbind", "ring_device_labels_unbind", emptyList(), 1),
                )
                val `factory_reset`: LocalizedString get() = LocalizedString(
                    key = StringKey("ring.device.labels.factory_reset", "ring_device_labels_factory_reset", emptyList(), 1),
                )
                val `power_off`: LocalizedString get() = LocalizedString(
                    key = StringKey("ring.device.labels.power_off", "ring_device_labels_power_off", emptyList(), 1),
                )
                val `notifications`: LocalizedString get() = LocalizedString(
                    key = StringKey("ring.device.labels.notifications", "ring_device_labels_notifications", emptyList(), 1),
                )
                val `ota`: LocalizedString get() = LocalizedString(
                    key = StringKey("ring.device.labels.ota", "ring_device_labels_ota", emptyList(), 1),
                )
            }
            object `NumberLabels` {
                val `start_minute`: LocalizedString get() = LocalizedString(
                    key = StringKey("ring.device.numberLabels.start_minute", "ring_device_number_labels_start_minute", emptyList(), 1),
                )
                val `end_minute`: LocalizedString get() = LocalizedString(
                    key = StringKey("ring.device.numberLabels.end_minute", "ring_device_number_labels_end_minute", emptyList(), 1),
                )
                val `interval_minutes`: LocalizedString get() = LocalizedString(
                    key = StringKey("ring.device.numberLabels.interval_minutes", "ring_device_number_labels_interval_minutes", emptyList(), 1),
                )
                val `weekdays`: LocalizedString get() = LocalizedString(
                    key = StringKey("ring.device.numberLabels.weekdays", "ring_device_number_labels_weekdays", emptyList(), 1),
                )
                val `age`: LocalizedString get() = LocalizedString(
                    key = StringKey("ring.device.numberLabels.age", "ring_device_number_labels_age", emptyList(), 1),
                )
                val `height_cm`: LocalizedString get() = LocalizedString(
                    key = StringKey("ring.device.numberLabels.height_cm", "ring_device_number_labels_height_cm", emptyList(), 1),
                )
                val `weight_kg`: LocalizedString get() = LocalizedString(
                    key = StringKey("ring.device.numberLabels.weight_kg", "ring_device_number_labels_weight_kg", emptyList(), 1),
                )
                val `systolic_mm_hg`: LocalizedString get() = LocalizedString(
                    key = StringKey("ring.device.numberLabels.systolic_mm_hg", "ring_device_number_labels_systolic_mm_hg", emptyList(), 1),
                )
                val `diastolic_mm_hg`: LocalizedString get() = LocalizedString(
                    key = StringKey("ring.device.numberLabels.diastolic_mm_hg", "ring_device_number_labels_diastolic_mm_hg", emptyList(), 1),
                )
                val `heart_rate_warning_bpm`: LocalizedString get() = LocalizedString(
                    key = StringKey("ring.device.numberLabels.heart_rate_warning_bpm", "ring_device_number_labels_heart_rate_warning_bpm", emptyList(), 1),
                )
                val `year`: LocalizedString get() = LocalizedString(
                    key = StringKey("ring.device.numberLabels.year", "ring_device_number_labels_year", emptyList(), 1),
                )
                val `month`: LocalizedString get() = LocalizedString(
                    key = StringKey("ring.device.numberLabels.month", "ring_device_number_labels_month", emptyList(), 1),
                )
                val `day`: LocalizedString get() = LocalizedString(
                    key = StringKey("ring.device.numberLabels.day", "ring_device_number_labels_day", emptyList(), 1),
                )
                val `hour`: LocalizedString get() = LocalizedString(
                    key = StringKey("ring.device.numberLabels.hour", "ring_device_number_labels_hour", emptyList(), 1),
                )
                val `minute`: LocalizedString get() = LocalizedString(
                    key = StringKey("ring.device.numberLabels.minute", "ring_device_number_labels_minute", emptyList(), 1),
                )
                val `second`: LocalizedString get() = LocalizedString(
                    key = StringKey("ring.device.numberLabels.second", "ring_device_number_labels_second", emptyList(), 1),
                )
                val `language`: LocalizedString get() = LocalizedString(
                    key = StringKey("ring.device.numberLabels.language", "ring_device_number_labels_language", emptyList(), 1),
                )
            }
            val `stateFailed`: LocalizedString get() = LocalizedString(
                key = StringKey("ring.device.stateFailed", "ring_device_state_failed", emptyList(), 1),
            )
            val `operationUnconfirmed`: LocalizedString get() = LocalizedString(
                key = StringKey("ring.device.operationUnconfirmed", "ring_device_operation_unconfirmed", emptyList(), 1),
            )
            val `invalidSetting`: LocalizedString get() = LocalizedString(
                key = StringKey("ring.device.invalidSetting", "ring_device_invalid_setting", emptyList(), 1),
            )
            val `identityRequired`: LocalizedString get() = LocalizedString(
                key = StringKey("ring.device.identityRequired", "ring_device_identity_required", emptyList(), 1),
            )
            val `fenced`: LocalizedString get() = LocalizedString(
                key = StringKey("ring.device.fenced", "ring_device_fenced", emptyList(), 1),
            )
            val `pendingControl`: LocalizedString get() = LocalizedString(
                key = StringKey("ring.device.pendingControl", "ring_device_pending_control", emptyList(), 1),
            )
            val `pendingSetting`: LocalizedString get() = LocalizedString(
                key = StringKey("ring.device.pendingSetting", "ring_device_pending_setting", emptyList(), 1),
            )
            val `readAvailable`: LocalizedString get() = LocalizedString(
                key = StringKey("ring.device.readAvailable", "ring_device_read_available", emptyList(), 1),
            )
            val `readUnavailable`: LocalizedString get() = LocalizedString(
                key = StringKey("ring.device.readUnavailable", "ring_device_read_unavailable", emptyList(), 1),
            )
            val `readUnconfirmed`: LocalizedString get() = LocalizedString(
                key = StringKey("ring.device.readUnconfirmed", "ring_device_read_unconfirmed", emptyList(), 1),
            )
            val `writeAvailable`: LocalizedString get() = LocalizedString(
                key = StringKey("ring.device.writeAvailable", "ring_device_write_available", emptyList(), 1),
            )
            val `writeUnavailable`: LocalizedString get() = LocalizedString(
                key = StringKey("ring.device.writeUnavailable", "ring_device_write_unavailable", emptyList(), 1),
            )
            val `writeUnconfirmed`: LocalizedString get() = LocalizedString(
                key = StringKey("ring.device.writeUnconfirmed", "ring_device_write_unconfirmed", emptyList(), 1),
            )
            val `alarm`: LocalizedString get() = LocalizedString(
                key = StringKey("ring.device.alarm", "ring_device_alarm", emptyList(), 1),
            )
            val `water`: LocalizedString get() = LocalizedString(
                key = StringKey("ring.device.water", "ring_device_water", emptyList(), 1),
            )
            val `actionUnavailable`: LocalizedString get() = LocalizedString(
                key = StringKey("ring.device.actionUnavailable", "ring_device_action_unavailable", emptyList(), 1),
            )
            val `actionUnconfirmed`: LocalizedString get() = LocalizedString(
                key = StringKey("ring.device.actionUnconfirmed", "ring_device_action_unconfirmed", emptyList(), 1),
            )
            val `androidAccessGranted`: LocalizedString get() = LocalizedString(
                key = StringKey("ring.device.androidAccessGranted", "ring_device_android_access_granted", emptyList(), 1),
            )
            val `androidAccessDenied`: LocalizedString get() = LocalizedString(
                key = StringKey("ring.device.androidAccessDenied", "ring_device_android_access_denied", emptyList(), 1),
            )
            val `enabled`: LocalizedString get() = LocalizedString(
                key = StringKey("ring.device.enabled", "ring_device_enabled", emptyList(), 1),
            )
            val `disabled`: LocalizedString get() = LocalizedString(
                key = StringKey("ring.device.disabled", "ring_device_disabled", emptyList(), 1),
            )
            val `notificationsUnsupported`: LocalizedString get() = LocalizedString(
                key = StringKey("ring.device.notificationsUnsupported", "ring_device_notifications_unsupported", emptyList(), 1),
            )
            val `notificationsUnavailable`: LocalizedString get() = LocalizedString(
                key = StringKey("ring.device.notificationsUnavailable", "ring_device_notifications_unavailable", emptyList(), 1),
            )
            val `notificationsUnconfirmed`: LocalizedString get() = LocalizedString(
                key = StringKey("ring.device.notificationsUnconfirmed", "ring_device_notifications_unconfirmed", emptyList(), 1),
            )
            val `verified`: LocalizedString get() = LocalizedString(
                key = StringKey("ring.device.verified", "ring_device_verified", emptyList(), 1),
            )
            val `unknown`: LocalizedString get() = LocalizedString(
                key = StringKey("ring.device.unknown", "ring_device_unknown", emptyList(), 1),
            )
            val `rejected`: LocalizedString get() = LocalizedString(
                key = StringKey("ring.device.rejected", "ring_device_rejected", emptyList(), 1),
            )
            val `title`: LocalizedString get() = LocalizedString(
                key = StringKey("ring.device.title", "ring_device_title", emptyList(), 1),
            )
            val `confirmationHint`: LocalizedString get() = LocalizedString(
                key = StringKey("ring.device.confirmationHint", "ring_device_confirmation_hint", emptyList(), 1),
            )
            val `identityRefresh`: LocalizedString get() = LocalizedString(
                key = StringKey("ring.device.identityRefresh", "ring_device_identity_refresh", emptyList(), 1),
            )
            val `pendingCommands`: LocalizedString get() = LocalizedString(
                key = StringKey("ring.device.pendingCommands", "ring_device_pending_commands", emptyList(), 1),
            )
            val `reconcileWithoutRepeat`: LocalizedString get() = LocalizedString(
                key = StringKey("ring.device.reconcileWithoutRepeat", "ring_device_reconcile_without_repeat", emptyList(), 1),
            )
            val `setting`: LocalizedString get() = LocalizedString(
                key = StringKey("ring.device.setting", "ring_device_setting", emptyList(), 1),
            )
            val `read`: LocalizedString get() = LocalizedString(
                key = StringKey("ring.device.read", "ring_device_read", emptyList(), 1),
            )
            val `refresh`: LocalizedString get() = LocalizedString(
                key = StringKey("ring.device.refresh", "ring_device_refresh", emptyList(), 1),
            )
            val `mode`: LocalizedString get() = LocalizedString(
                key = StringKey("ring.device.mode", "ring_device_mode", emptyList(), 1),
            )
            val `media`: LocalizedString get() = LocalizedString(
                key = StringKey("ring.device.media", "ring_device_media", emptyList(), 1),
            )
            val `video`: LocalizedString get() = LocalizedString(
                key = StringKey("ring.device.video", "ring_device_video", emptyList(), 1),
            )
            val `ebook`: LocalizedString get() = LocalizedString(
                key = StringKey("ring.device.ebook", "ring_device_ebook", emptyList(), 1),
            )
            val `game`: LocalizedString get() = LocalizedString(
                key = StringKey("ring.device.game", "ring_device_game", emptyList(), 1),
            )
            val `hand`: LocalizedString get() = LocalizedString(
                key = StringKey("ring.device.hand", "ring_device_hand", emptyList(), 1),
            )
            val `left`: LocalizedString get() = LocalizedString(
                key = StringKey("ring.device.left", "ring_device_left", emptyList(), 1),
            )
            val `right`: LocalizedString get() = LocalizedString(
                key = StringKey("ring.device.right", "ring_device_right", emptyList(), 1),
            )
            val `sex`: LocalizedString get() = LocalizedString(
                key = StringKey("ring.device.sex", "ring_device_sex", emptyList(), 1),
            )
            val `male`: LocalizedString get() = LocalizedString(
                key = StringKey("ring.device.male", "ring_device_male", emptyList(), 1),
            )
            val `female`: LocalizedString get() = LocalizedString(
                key = StringKey("ring.device.female", "ring_device_female", emptyList(), 1),
            )
            val `units`: LocalizedString get() = LocalizedString(
                key = StringKey("ring.device.units", "ring_device_units", emptyList(), 1),
            )
            val `metric`: LocalizedString get() = LocalizedString(
                key = StringKey("ring.device.metric", "ring_device_metric", emptyList(), 1),
            )
            val `imperial`: LocalizedString get() = LocalizedString(
                key = StringKey("ring.device.imperial", "ring_device_imperial", emptyList(), 1),
            )
            val `clock24`: LocalizedString get() = LocalizedString(
                key = StringKey("ring.device.clock24", "ring_device_clock24", emptyList(), 1),
            )
            val `hour`: LocalizedString get() = LocalizedString(
                key = StringKey("ring.device.hour", "ring_device_hour", emptyList(), 1),
            )
            val `minute`: LocalizedString get() = LocalizedString(
                key = StringKey("ring.device.minute", "ring_device_minute", emptyList(), 1),
            )
            val `repeatDaysHint`: LocalizedString get() = LocalizedString(
                key = StringKey("ring.device.repeatDaysHint", "ring_device_repeat_days_hint", emptyList(), 1),
            )
            val `sedentaryDaysHint`: LocalizedString get() = LocalizedString(
                key = StringKey("ring.device.sedentaryDaysHint", "ring_device_sedentary_days_hint", emptyList(), 1),
            )
            val `languageHint`: LocalizedString get() = LocalizedString(
                key = StringKey("ring.device.languageHint", "ring_device_language_hint", emptyList(), 1),
            )
            val `save`: LocalizedString get() = LocalizedString(
                key = StringKey("ring.device.save", "ring_device_save", emptyList(), 1),
            )
            val `wireOnly`: LocalizedString get() = LocalizedString(
                key = StringKey("ring.device.wireOnly", "ring_device_wire_only", emptyList(), 1),
            )
            val `unread`: LocalizedString get() = LocalizedString(
                key = StringKey("ring.device.unread", "ring_device_unread", emptyList(), 1),
            )
            val `wireHint`: LocalizedString get() = LocalizedString(
                key = StringKey("ring.device.wireHint", "ring_device_wire_hint", emptyList(), 1),
            )
            val `actions`: LocalizedString get() = LocalizedString(
                key = StringKey("ring.device.actions", "ring_device_actions", emptyList(), 1),
            )
            val `cancel`: LocalizedString get() = LocalizedString(
                key = StringKey("ring.device.cancel", "ring_device_cancel", emptyList(), 1),
            )
            val `notifications`: LocalizedString get() = LocalizedString(
                key = StringKey("ring.device.notifications", "ring_device_notifications", emptyList(), 1),
            )
            val `notificationsPrivacy`: LocalizedString get() = LocalizedString(
                key = StringKey("ring.device.notificationsPrivacy", "ring_device_notifications_privacy", emptyList(), 1),
            )
            val `notificationsAccessHint`: LocalizedString get() = LocalizedString(
                key = StringKey("ring.device.notificationsAccessHint", "ring_device_notifications_access_hint", emptyList(), 1),
            )
            val `notificationsAccess`: LocalizedString get() = LocalizedString(
                key = StringKey("ring.device.notificationsAccess", "ring_device_notifications_access", emptyList(), 1),
            )
            val `notificationsBlocked`: LocalizedString get() = LocalizedString(
                key = StringKey("ring.device.notificationsBlocked", "ring_device_notifications_blocked", emptyList(), 1),
            )
            val `allowedApps`: LocalizedString get() = LocalizedString(
                key = StringKey("ring.device.allowedApps", "ring_device_allowed_apps", emptyList(), 1),
            )
            val `appsUnavailable`: LocalizedString get() = LocalizedString(
                key = StringKey("ring.device.appsUnavailable", "ring_device_apps_unavailable", emptyList(), 1),
            )
            val `notificationsEnable`: LocalizedString get() = LocalizedString(
                key = StringKey("ring.device.notificationsEnable", "ring_device_notifications_enable", emptyList(), 1),
            )
            val `notificationsDisable`: LocalizedString get() = LocalizedString(
                key = StringKey("ring.device.notificationsDisable", "ring_device_notifications_disable", emptyList(), 1),
            )
            val `resultDetails`: LocalizedString get() = LocalizedString(
                key = StringKey("ring.device.resultDetails", "ring_device_result_details", emptyList(), 1),
            )
            val `reconcile`: LocalizedString get() = LocalizedString(
                key = StringKey("ring.device.reconcile", "ring_device_reconcile", emptyList(), 1),
            )
            val `capabilities`: LocalizedString get() = LocalizedString(
                key = StringKey("ring.device.capabilities", "ring_device_capabilities", emptyList(), 1),
            )
            fun `wireTitle`(`setting`: String): LocalizedString = LocalizedString(
                key = StringKey("ring.device.wireTitle", "ring_device_wire_title", listOf("setting"), 1),
                arguments = listOf(`setting`),
            )
            fun `confirmActionHint`(`action`: String): LocalizedString = LocalizedString(
                key = StringKey("ring.device.confirmActionHint", "ring_device_confirm_action_hint", listOf("action"), 1),
                arguments = listOf(`action`),
            )
            fun `confirmAction`(`action`: String): LocalizedString = LocalizedString(
                key = StringKey("ring.device.confirmAction", "ring_device_confirm_action", listOf("action"), 1),
                arguments = listOf(`action`),
            )
            fun `allowedAppsSelected`(`count`: String): LocalizedString = LocalizedString(
                key = StringKey("ring.device.allowedAppsSelected", "ring_device_allowed_apps_selected", listOf("count"), 1),
                arguments = listOf(`count`),
            )
        }
        object `Events` {
            val `title`: LocalizedString get() = LocalizedString(
                key = StringKey("ring.events.title", "ring_events_title", emptyList(), 1),
            )
            val `hint`: LocalizedString get() = LocalizedString(
                key = StringKey("ring.events.hint", "ring_events_hint", emptyList(), 1),
            )
            val `tasbih`: LocalizedString get() = LocalizedString(
                key = StringKey("ring.events.tasbih", "ring_events_tasbih", emptyList(), 1),
            )
            fun `confirmedCount`(`count`: String): LocalizedString = LocalizedString(
                key = StringKey("ring.events.confirmedCount", "ring_events_confirmed_count", listOf("count"), 1),
                arguments = listOf(`count`),
            )
            val `noCount`: LocalizedString get() = LocalizedString(
                key = StringKey("ring.events.noCount", "ring_events_no_count", emptyList(), 1),
            )
            fun `localReset`(`count`: String): LocalizedString = LocalizedString(
                key = StringKey("ring.events.localReset", "ring_events_local_reset", listOf("count"), 1),
                arguments = listOf(`count`),
            )
            fun `candidateCount`(`count`: String): LocalizedString = LocalizedString(
                key = StringKey("ring.events.candidateCount", "ring_events_candidate_count", listOf("count"), 1),
                arguments = listOf(`count`),
            )
            val `game`: LocalizedString get() = LocalizedString(
                key = StringKey("ring.events.game", "ring_events_game", emptyList(), 1),
            )
            fun `confirmedEvents`(`count`: String): LocalizedString = LocalizedString(
                key = StringKey("ring.events.confirmedEvents", "ring_events_confirmed_events", listOf("count"), 1),
                arguments = listOf(`count`),
            )
            val `noEvents`: LocalizedString get() = LocalizedString(
                key = StringKey("ring.events.noEvents", "ring_events_no_events", emptyList(), 1),
            )
            fun `candidateEvents`(`count`: String): LocalizedString = LocalizedString(
                key = StringKey("ring.events.candidateEvents", "ring_events_candidate_events", listOf("count"), 1),
                arguments = listOf(`count`),
            )
            val `lastEvent`: LocalizedString get() = LocalizedString(
                key = StringKey("ring.events.lastEvent", "ring_events_last_event", emptyList(), 1),
            )
        }
        object `HostGestures` {
            val `title`: LocalizedString get() = LocalizedString(
                key = StringKey("ring.hostGestures.title", "ring_host_gestures_title", emptyList(), 1),
            )
            val `accessGranted`: LocalizedString get() = LocalizedString(
                key = StringKey("ring.hostGestures.accessGranted", "ring_host_gestures_access_granted", emptyList(), 1),
            )
            val `accessDenied`: LocalizedString get() = LocalizedString(
                key = StringKey("ring.hostGestures.accessDenied", "ring_host_gestures_access_denied", emptyList(), 1),
            )
            val `hint`: LocalizedString get() = LocalizedString(
                key = StringKey("ring.hostGestures.hint", "ring_host_gestures_hint", emptyList(), 1),
            )
            val `access`: LocalizedString get() = LocalizedString(
                key = StringKey("ring.hostGestures.access", "ring_host_gestures_access", emptyList(), 1),
            )
            val `save`: LocalizedString get() = LocalizedString(
                key = StringKey("ring.hostGestures.save", "ring_host_gestures_save", emptyList(), 1),
            )
            val `enable`: LocalizedString get() = LocalizedString(
                key = StringKey("ring.hostGestures.enable", "ring_host_gestures_enable", emptyList(), 1),
            )
            val `disable`: LocalizedString get() = LocalizedString(
                key = StringKey("ring.hostGestures.disable", "ring_host_gestures_disable", emptyList(), 1),
            )
            object `Inputs` {
                val `tap`: LocalizedString get() = LocalizedString(
                    key = StringKey("ring.hostGestures.inputs.tap", "ring_host_gestures_inputs_tap", emptyList(), 1),
                )
                val `doubleTap`: LocalizedString get() = LocalizedString(
                    key = StringKey("ring.hostGestures.inputs.doubleTap", "ring_host_gestures_inputs_double_tap", emptyList(), 1),
                )
                val `swipeLeft`: LocalizedString get() = LocalizedString(
                    key = StringKey("ring.hostGestures.inputs.swipeLeft", "ring_host_gestures_inputs_swipe_left", emptyList(), 1),
                )
                val `swipeRight`: LocalizedString get() = LocalizedString(
                    key = StringKey("ring.hostGestures.inputs.swipeRight", "ring_host_gestures_inputs_swipe_right", emptyList(), 1),
                )
                val `swipeUp`: LocalizedString get() = LocalizedString(
                    key = StringKey("ring.hostGestures.inputs.swipeUp", "ring_host_gestures_inputs_swipe_up", emptyList(), 1),
                )
                val `swipeDown`: LocalizedString get() = LocalizedString(
                    key = StringKey("ring.hostGestures.inputs.swipeDown", "ring_host_gestures_inputs_swipe_down", emptyList(), 1),
                )
            }
            object `Actions` {
                val `none`: LocalizedString get() = LocalizedString(
                    key = StringKey("ring.hostGestures.actions.none", "ring_host_gestures_actions_none", emptyList(), 1),
                )
                val `back`: LocalizedString get() = LocalizedString(
                    key = StringKey("ring.hostGestures.actions.back", "ring_host_gestures_actions_back", emptyList(), 1),
                )
                val `home`: LocalizedString get() = LocalizedString(
                    key = StringKey("ring.hostGestures.actions.home", "ring_host_gestures_actions_home", emptyList(), 1),
                )
                val `recents`: LocalizedString get() = LocalizedString(
                    key = StringKey("ring.hostGestures.actions.recents", "ring_host_gestures_actions_recents", emptyList(), 1),
                )
                val `notifications`: LocalizedString get() = LocalizedString(
                    key = StringKey("ring.hostGestures.actions.notifications", "ring_host_gestures_actions_notifications", emptyList(), 1),
                )
                val `volumeUp`: LocalizedString get() = LocalizedString(
                    key = StringKey("ring.hostGestures.actions.volumeUp", "ring_host_gestures_actions_volume_up", emptyList(), 1),
                )
                val `volumeDown`: LocalizedString get() = LocalizedString(
                    key = StringKey("ring.hostGestures.actions.volumeDown", "ring_host_gestures_actions_volume_down", emptyList(), 1),
                )
                val `playPause`: LocalizedString get() = LocalizedString(
                    key = StringKey("ring.hostGestures.actions.playPause", "ring_host_gestures_actions_play_pause", emptyList(), 1),
                )
                val `next`: LocalizedString get() = LocalizedString(
                    key = StringKey("ring.hostGestures.actions.next", "ring_host_gestures_actions_next", emptyList(), 1),
                )
                val `previous`: LocalizedString get() = LocalizedString(
                    key = StringKey("ring.hostGestures.actions.previous", "ring_host_gestures_actions_previous", emptyList(), 1),
                )
                val `tapCenter`: LocalizedString get() = LocalizedString(
                    key = StringKey("ring.hostGestures.actions.tapCenter", "ring_host_gestures_actions_tap_center", emptyList(), 1),
                )
                val `doubleTapCenter`: LocalizedString get() = LocalizedString(
                    key = StringKey("ring.hostGestures.actions.doubleTapCenter", "ring_host_gestures_actions_double_tap_center", emptyList(), 1),
                )
                val `tapLeft`: LocalizedString get() = LocalizedString(
                    key = StringKey("ring.hostGestures.actions.tapLeft", "ring_host_gestures_actions_tap_left", emptyList(), 1),
                )
                val `tapRight`: LocalizedString get() = LocalizedString(
                    key = StringKey("ring.hostGestures.actions.tapRight", "ring_host_gestures_actions_tap_right", emptyList(), 1),
                )
                val `swipeUp`: LocalizedString get() = LocalizedString(
                    key = StringKey("ring.hostGestures.actions.swipeUp", "ring_host_gestures_actions_swipe_up", emptyList(), 1),
                )
                val `swipeDown`: LocalizedString get() = LocalizedString(
                    key = StringKey("ring.hostGestures.actions.swipeDown", "ring_host_gestures_actions_swipe_down", emptyList(), 1),
                )
                val `swipeLeft`: LocalizedString get() = LocalizedString(
                    key = StringKey("ring.hostGestures.actions.swipeLeft", "ring_host_gestures_actions_swipe_left", emptyList(), 1),
                )
                val `swipeRight`: LocalizedString get() = LocalizedString(
                    key = StringKey("ring.hostGestures.actions.swipeRight", "ring_host_gestures_actions_swipe_right", emptyList(), 1),
                )
            }
            object `Results` {
                val `dispatching`: LocalizedString get() = LocalizedString(
                    key = StringKey("ring.hostGestures.results.dispatching", "ring_host_gestures_results_dispatching", emptyList(), 1),
                )
                val `androidAccepted`: LocalizedString get() = LocalizedString(
                    key = StringKey("ring.hostGestures.results.androidAccepted", "ring_host_gestures_results_android_accepted", emptyList(), 1),
                )
                val `androidCompleted`: LocalizedString get() = LocalizedString(
                    key = StringKey("ring.hostGestures.results.androidCompleted", "ring_host_gestures_results_android_completed", emptyList(), 1),
                )
                val `androidCancelled`: LocalizedString get() = LocalizedString(
                    key = StringKey("ring.hostGestures.results.androidCancelled", "ring_host_gestures_results_android_cancelled", emptyList(), 1),
                )
                val `androidRejected`: LocalizedString get() = LocalizedString(
                    key = StringKey("ring.hostGestures.results.androidRejected", "ring_host_gestures_results_android_rejected", emptyList(), 1),
                )
            }
        }
        object `Stored` {
            object `Names` {
                val `heart_rate`: LocalizedString get() = LocalizedString(
                    key = StringKey("ring.stored.names.heart_rate", "ring_stored_names_heart_rate", emptyList(), 1),
                )
                val `spo2`: LocalizedString get() = LocalizedString(
                    key = StringKey("ring.stored.names.spo2", "ring_stored_names_spo2", emptyList(), 1),
                )
                val `temperature`: LocalizedString get() = LocalizedString(
                    key = StringKey("ring.stored.names.temperature", "ring_stored_names_temperature", emptyList(), 1),
                )
                val `hrv`: LocalizedString get() = LocalizedString(
                    key = StringKey("ring.stored.names.hrv", "ring_stored_names_hrv", emptyList(), 1),
                )
                val `stress`: LocalizedString get() = LocalizedString(
                    key = StringKey("ring.stored.names.stress", "ring_stored_names_stress", emptyList(), 1),
                )
                val `notifications`: LocalizedString get() = LocalizedString(
                    key = StringKey("ring.stored.names.notifications", "ring_stored_names_notifications", emptyList(), 1),
                )
                val `ecg`: LocalizedString get() = LocalizedString(
                    key = StringKey("ring.stored.names.ecg", "ring_stored_names_ecg", emptyList(), 1),
                )
                val `prayer`: LocalizedString get() = LocalizedString(
                    key = StringKey("ring.stored.names.prayer", "ring_stored_names_prayer", emptyList(), 1),
                )
                val `moslin`: LocalizedString get() = LocalizedString(
                    key = StringKey("ring.stored.names.moslin", "ring_stored_names_moslin", emptyList(), 1),
                )
                val `lover`: LocalizedString get() = LocalizedString(
                    key = StringKey("ring.stored.names.lover", "ring_stored_names_lover", emptyList(), 1),
                )
                val `plate`: LocalizedString get() = LocalizedString(
                    key = StringKey("ring.stored.names.plate", "ring_stored_names_plate", emptyList(), 1),
                )
                val `feature`: LocalizedString get() = LocalizedString(
                    key = StringKey("ring.stored.names.feature", "ring_stored_names_feature", emptyList(), 1),
                )
                val `rtk_mc`: LocalizedString get() = LocalizedString(
                    key = StringKey("ring.stored.names.rtk_mc", "ring_stored_names_rtk_mc", emptyList(), 1),
                )
                val `e_card`: LocalizedString get() = LocalizedString(
                    key = StringKey("ring.stored.names.e_card", "ring_stored_names_e_card", emptyList(), 1),
                )
                val `one_key`: LocalizedString get() = LocalizedString(
                    key = StringKey("ring.stored.names.one_key", "ring_stored_names_one_key", emptyList(), 1),
                )
                val `menstruation`: LocalizedString get() = LocalizedString(
                    key = StringKey("ring.stored.names.menstruation", "ring_stored_names_menstruation", emptyList(), 1),
                )
                val `wallpaper`: LocalizedString get() = LocalizedString(
                    key = StringKey("ring.stored.names.wallpaper", "ring_stored_names_wallpaper", emptyList(), 1),
                )
                val `weather`: LocalizedString get() = LocalizedString(
                    key = StringKey("ring.stored.names.weather", "ring_stored_names_weather", emptyList(), 1),
                )
                val `wechat`: LocalizedString get() = LocalizedString(
                    key = StringKey("ring.stored.names.wechat", "ring_stored_names_wechat", emptyList(), 1),
                )
                val `contacts`: LocalizedString get() = LocalizedString(
                    key = StringKey("ring.stored.names.contacts", "ring_stored_names_contacts", emptyList(), 1),
                )
                val `gesture`: LocalizedString get() = LocalizedString(
                    key = StringKey("ring.stored.names.gesture", "ring_stored_names_gesture", emptyList(), 1),
                )
                val `music`: LocalizedString get() = LocalizedString(
                    key = StringKey("ring.stored.names.music", "ring_stored_names_music", emptyList(), 1),
                )
                val `video`: LocalizedString get() = LocalizedString(
                    key = StringKey("ring.stored.names.video", "ring_stored_names_video", emptyList(), 1),
                )
                val `ebook`: LocalizedString get() = LocalizedString(
                    key = StringKey("ring.stored.names.ebook", "ring_stored_names_ebook", emptyList(), 1),
                )
                val `game`: LocalizedString get() = LocalizedString(
                    key = StringKey("ring.stored.names.game", "ring_stored_names_game", emptyList(), 1),
                )
                val `camera`: LocalizedString get() = LocalizedString(
                    key = StringKey("ring.stored.names.camera", "ring_stored_names_camera", emptyList(), 1),
                )
                val `phone_call`: LocalizedString get() = LocalizedString(
                    key = StringKey("ring.stored.names.phone_call", "ring_stored_names_phone_call", emptyList(), 1),
                )
                val `touch`: LocalizedString get() = LocalizedString(
                    key = StringKey("ring.stored.names.touch", "ring_stored_names_touch", emptyList(), 1),
                )
                val `interval_spo2`: LocalizedString get() = LocalizedString(
                    key = StringKey("ring.stored.names.interval_spo2", "ring_stored_names_interval_spo2", emptyList(), 1),
                )
                val `interval_temperature`: LocalizedString get() = LocalizedString(
                    key = StringKey("ring.stored.names.interval_temperature", "ring_stored_names_interval_temperature", emptyList(), 1),
                )
                val `blood_pressure`: LocalizedString get() = LocalizedString(
                    key = StringKey("ring.stored.names.blood_pressure", "ring_stored_names_blood_pressure", emptyList(), 1),
                )
                val `manual_heart`: LocalizedString get() = LocalizedString(
                    key = StringKey("ring.stored.names.manual_heart", "ring_stored_names_manual_heart", emptyList(), 1),
                )
                val `new_sleep`: LocalizedString get() = LocalizedString(
                    key = StringKey("ring.stored.names.new_sleep", "ring_stored_names_new_sleep", emptyList(), 1),
                )
                val `ble_pair`: LocalizedString get() = LocalizedString(
                    key = StringKey("ring.stored.names.ble_pair", "ring_stored_names_ble_pair", emptyList(), 1),
                )
                val `app_revision`: LocalizedString get() = LocalizedString(
                    key = StringKey("ring.stored.names.app_revision", "ring_stored_names_app_revision", emptyList(), 1),
                )
                val `location`: LocalizedString get() = LocalizedString(
                    key = StringKey("ring.stored.names.location", "ring_stored_names_location", emptyList(), 1),
                )
                val `heart_rate_schedule`: LocalizedString get() = LocalizedString(
                    key = StringKey("ring.stored.names.heart_rate_schedule", "ring_stored_names_heart_rate_schedule", emptyList(), 1),
                )
                val `heart_rate_alerts`: LocalizedString get() = LocalizedString(
                    key = StringKey("ring.stored.names.heart_rate_alerts", "ring_stored_names_heart_rate_alerts", emptyList(), 1),
                )
                val `spo2_schedule`: LocalizedString get() = LocalizedString(
                    key = StringKey("ring.stored.names.spo2_schedule", "ring_stored_names_spo2_schedule", emptyList(), 1),
                )
                val `temperature_schedule`: LocalizedString get() = LocalizedString(
                    key = StringKey("ring.stored.names.temperature_schedule", "ring_stored_names_temperature_schedule", emptyList(), 1),
                )
                val `hrv_schedule`: LocalizedString get() = LocalizedString(
                    key = StringKey("ring.stored.names.hrv_schedule", "ring_stored_names_hrv_schedule", emptyList(), 1),
                )
                val `stress_schedule`: LocalizedString get() = LocalizedString(
                    key = StringKey("ring.stored.names.stress_schedule", "ring_stored_names_stress_schedule", emptyList(), 1),
                )
                val `workout`: LocalizedString get() = LocalizedString(
                    key = StringKey("ring.stored.names.workout", "ring_stored_names_workout", emptyList(), 1),
                )
                val `live_heart_rate`: LocalizedString get() = LocalizedString(
                    key = StringKey("ring.stored.names.live_heart_rate", "ring_stored_names_live_heart_rate", emptyList(), 1),
                )
                val `manual_heart_rate`: LocalizedString get() = LocalizedString(
                    key = StringKey("ring.stored.names.manual_heart_rate", "ring_stored_names_manual_heart_rate", emptyList(), 1),
                )
                val `manual_spo2`: LocalizedString get() = LocalizedString(
                    key = StringKey("ring.stored.names.manual_spo2", "ring_stored_names_manual_spo2", emptyList(), 1),
                )
                val `manual_temperature`: LocalizedString get() = LocalizedString(
                    key = StringKey("ring.stored.names.manual_temperature", "ring_stored_names_manual_temperature", emptyList(), 1),
                )
                val `manual_hrv`: LocalizedString get() = LocalizedString(
                    key = StringKey("ring.stored.names.manual_hrv", "ring_stored_names_manual_hrv", emptyList(), 1),
                )
                val `manual_stress`: LocalizedString get() = LocalizedString(
                    key = StringKey("ring.stored.names.manual_stress", "ring_stored_names_manual_stress", emptyList(), 1),
                )
                val `alarms`: LocalizedString get() = LocalizedString(
                    key = StringKey("ring.stored.names.alarms", "ring_stored_names_alarms", emptyList(), 1),
                )
                val `drink_reminders`: LocalizedString get() = LocalizedString(
                    key = StringKey("ring.stored.names.drink_reminders", "ring_stored_names_drink_reminders", emptyList(), 1),
                )
                val `dnd`: LocalizedString get() = LocalizedString(
                    key = StringKey("ring.stored.names.dnd", "ring_stored_names_dnd", emptyList(), 1),
                )
                val `sedentary`: LocalizedString get() = LocalizedString(
                    key = StringKey("ring.stored.names.sedentary", "ring_stored_names_sedentary", emptyList(), 1),
                )
                val `power_saving`: LocalizedString get() = LocalizedString(
                    key = StringKey("ring.stored.names.power_saving", "ring_stored_names_power_saving", emptyList(), 1),
                )
                val `gestures`: LocalizedString get() = LocalizedString(
                    key = StringKey("ring.stored.names.gestures", "ring_stored_names_gestures", emptyList(), 1),
                )
                val `counter`: LocalizedString get() = LocalizedString(
                    key = StringKey("ring.stored.names.counter", "ring_stored_names_counter", emptyList(), 1),
                )
                val `profile`: LocalizedString get() = LocalizedString(
                    key = StringKey("ring.stored.names.profile", "ring_stored_names_profile", emptyList(), 1),
                )
                val `clock`: LocalizedString get() = LocalizedString(
                    key = StringKey("ring.stored.names.clock", "ring_stored_names_clock", emptyList(), 1),
                )
                val `find_ring`: LocalizedString get() = LocalizedString(
                    key = StringKey("ring.stored.names.find_ring", "ring_stored_names_find_ring", emptyList(), 1),
                )
                val `bind`: LocalizedString get() = LocalizedString(
                    key = StringKey("ring.stored.names.bind", "ring_stored_names_bind", emptyList(), 1),
                )
                val `unbind`: LocalizedString get() = LocalizedString(
                    key = StringKey("ring.stored.names.unbind", "ring_stored_names_unbind", emptyList(), 1),
                )
                val `factory_reset`: LocalizedString get() = LocalizedString(
                    key = StringKey("ring.stored.names.factory_reset", "ring_stored_names_factory_reset", emptyList(), 1),
                )
                val `power_off`: LocalizedString get() = LocalizedString(
                    key = StringKey("ring.stored.names.power_off", "ring_stored_names_power_off", emptyList(), 1),
                )
                val `vibration_intensity`: LocalizedString get() = LocalizedString(
                    key = StringKey("ring.stored.names.vibration_intensity", "ring_stored_names_vibration_intensity", emptyList(), 1),
                )
                val `restart`: LocalizedString get() = LocalizedString(
                    key = StringKey("ring.stored.names.restart", "ring_stored_names_restart", emptyList(), 1),
                )
            }
            val `timeUnknown`: LocalizedString get() = LocalizedString(
                key = StringKey("ring.stored.timeUnknown", "ring_stored_time_unknown", emptyList(), 1),
            )
            val `title`: LocalizedString get() = LocalizedString(
                key = StringKey("ring.stored.title", "ring_stored_title", emptyList(), 1),
            )
            val `received`: LocalizedString get() = LocalizedString(
                key = StringKey("ring.stored.received", "ring_stored_received", emptyList(), 1),
            )
            val `observed`: LocalizedString get() = LocalizedString(
                key = StringKey("ring.stored.observed", "ring_stored_observed", emptyList(), 1),
            )
            val `stale`: LocalizedString get() = LocalizedString(
                key = StringKey("ring.stored.stale", "ring_stored_stale", emptyList(), 1),
            )
            val `battery`: LocalizedString get() = LocalizedString(
                key = StringKey("ring.stored.battery", "ring_stored_battery", emptyList(), 1),
            )
            val `batteryStale`: LocalizedString get() = LocalizedString(
                key = StringKey("ring.stored.batteryStale", "ring_stored_battery_stale", emptyList(), 1),
            )
            val `charging`: LocalizedString get() = LocalizedString(
                key = StringKey("ring.stored.charging", "ring_stored_charging", emptyList(), 1),
            )
            val `connected`: LocalizedString get() = LocalizedString(
                key = StringKey("ring.stored.connected", "ring_stored_connected", emptyList(), 1),
            )
            val `disconnected`: LocalizedString get() = LocalizedString(
                key = StringKey("ring.stored.disconnected", "ring_stored_disconnected", emptyList(), 1),
            )
            val `chargingStale`: LocalizedString get() = LocalizedString(
                key = StringKey("ring.stored.chargingStale", "ring_stored_charging_stale", emptyList(), 1),
            )
            val `firmware`: LocalizedString get() = LocalizedString(
                key = StringKey("ring.stored.firmware", "ring_stored_firmware", emptyList(), 1),
            )
            val `hardware`: LocalizedString get() = LocalizedString(
                key = StringKey("ring.stored.hardware", "ring_stored_hardware", emptyList(), 1),
            )
            val `confirmedSettings`: LocalizedString get() = LocalizedString(
                key = StringKey("ring.stored.confirmedSettings", "ring_stored_confirmed_settings", emptyList(), 1),
            )
            val `confirmedSettingsHint`: LocalizedString get() = LocalizedString(
                key = StringKey("ring.stored.confirmedSettingsHint", "ring_stored_confirmed_settings_hint", emptyList(), 1),
            )
            val `declaredCapabilities`: LocalizedString get() = LocalizedString(
                key = StringKey("ring.stored.declaredCapabilities", "ring_stored_declared_capabilities", emptyList(), 1),
            )
            val `declaredCapabilitiesHint`: LocalizedString get() = LocalizedString(
                key = StringKey("ring.stored.declaredCapabilitiesHint", "ring_stored_declared_capabilities_hint", emptyList(), 1),
            )
            val `declared`: LocalizedString get() = LocalizedString(
                key = StringKey("ring.stored.declared", "ring_stored_declared", emptyList(), 1),
            )
            val `undeclared`: LocalizedString get() = LocalizedString(
                key = StringKey("ring.stored.undeclared", "ring_stored_undeclared", emptyList(), 1),
            )
            val `sourceFieldsHint`: LocalizedString get() = LocalizedString(
                key = StringKey("ring.stored.sourceFieldsHint", "ring_stored_source_fields_hint", emptyList(), 1),
            )
            val `availability`: LocalizedString get() = LocalizedString(
                key = StringKey("ring.stored.availability", "ring_stored_availability", emptyList(), 1),
            )
            val `availabilityHint`: LocalizedString get() = LocalizedString(
                key = StringKey("ring.stored.availabilityHint", "ring_stored_availability_hint", emptyList(), 1),
            )
            val `supported`: LocalizedString get() = LocalizedString(
                key = StringKey("ring.stored.supported", "ring_stored_supported", emptyList(), 1),
            )
            val `unsupported`: LocalizedString get() = LocalizedString(
                key = StringKey("ring.stored.unsupported", "ring_stored_unsupported", emptyList(), 1),
            )
            val `unconfirmed`: LocalizedString get() = LocalizedString(
                key = StringKey("ring.stored.unconfirmed", "ring_stored_unconfirmed", emptyList(), 1),
            )
            val `read`: LocalizedString get() = LocalizedString(
                key = StringKey("ring.stored.read", "ring_stored_read", emptyList(), 1),
            )
            val `write`: LocalizedString get() = LocalizedString(
                key = StringKey("ring.stored.write", "ring_stored_write", emptyList(), 1),
            )
            val `allowed`: LocalizedString get() = LocalizedString(
                key = StringKey("ring.stored.allowed", "ring_stored_allowed", emptyList(), 1),
            )
            val `disallowed`: LocalizedString get() = LocalizedString(
                key = StringKey("ring.stored.disallowed", "ring_stored_disallowed", emptyList(), 1),
            )
            val `allowanceUnknown`: LocalizedString get() = LocalizedString(
                key = StringKey("ring.stored.allowanceUnknown", "ring_stored_allowance_unknown", emptyList(), 1),
            )
        }
    }
    object `Whoop` {
        val `title`: LocalizedString get() = LocalizedString(
            key = StringKey("whoop.title", "whoop_title", emptyList(), 1),
        )
        val `metrics`: LocalizedString get() = LocalizedString(
            key = StringKey("whoop.metrics", "whoop_metrics", emptyList(), 1),
        )
        val `notSelected`: LocalizedString get() = LocalizedString(
            key = StringKey("whoop.notSelected", "whoop_not_selected", emptyList(), 1),
        )
        val `initializing`: LocalizedString get() = LocalizedString(
            key = StringKey("whoop.initializing", "whoop_initializing", emptyList(), 1),
        )
        val `checkConnection`: LocalizedString get() = LocalizedString(
            key = StringKey("whoop.checkConnection", "whoop_check_connection", emptyList(), 1),
        )
        object `Phase` {
            val `idle`: LocalizedString get() = LocalizedString(
                key = StringKey("whoop.phase.idle", "whoop_phase_idle", emptyList(), 1),
            )
            val `scanning`: LocalizedString get() = LocalizedString(
                key = StringKey("whoop.phase.scanning", "whoop_phase_scanning", emptyList(), 1),
            )
            val `pairing`: LocalizedString get() = LocalizedString(
                key = StringKey("whoop.phase.pairing", "whoop_phase_pairing", emptyList(), 1),
            )
            val `connecting`: LocalizedString get() = LocalizedString(
                key = StringKey("whoop.phase.connecting", "whoop_phase_connecting", emptyList(), 1),
            )
            val `syncing`: LocalizedString get() = LocalizedString(
                key = StringKey("whoop.phase.syncing", "whoop_phase_syncing", emptyList(), 1),
            )
            val `error`: LocalizedString get() = LocalizedString(
                key = StringKey("whoop.phase.error", "whoop_phase_error", emptyList(), 1),
            )
        }
        object `Metric` {
            val `heart_rate`: LocalizedString get() = LocalizedString(
                key = StringKey("whoop.metric.heart_rate", "whoop_metric_heart_rate", emptyList(), 1),
            )
            val `hrv`: LocalizedString get() = LocalizedString(
                key = StringKey("whoop.metric.hrv", "whoop_metric_hrv", emptyList(), 1),
            )
            val `temperature`: LocalizedString get() = LocalizedString(
                key = StringKey("whoop.metric.temperature", "whoop_metric_temperature", emptyList(), 1),
            )
            val `steps`: LocalizedString get() = LocalizedString(
                key = StringKey("whoop.metric.steps", "whoop_metric_steps", emptyList(), 1),
            )
        }
        val `background`: LocalizedString get() = LocalizedString(
            key = StringKey("whoop.background", "whoop_background", emptyList(), 1),
        )
        val `connectHint`: LocalizedString get() = LocalizedString(
            key = StringKey("whoop.connectHint", "whoop_connect_hint", emptyList(), 1),
        )
        val `bluetoothOff`: LocalizedString get() = LocalizedString(
            key = StringKey("whoop.bluetoothOff", "whoop_bluetooth_off", emptyList(), 1),
        )
        val `permission`: LocalizedString get() = LocalizedString(
            key = StringKey("whoop.permission", "whoop_permission", emptyList(), 1),
        )
        val `scan`: LocalizedString get() = LocalizedString(
            key = StringKey("whoop.scan", "whoop_scan", emptyList(), 1),
        )
        val `select`: LocalizedString get() = LocalizedString(
            key = StringKey("whoop.select", "whoop_select", emptyList(), 1),
        )
        val `sync`: LocalizedString get() = LocalizedString(
            key = StringKey("whoop.sync", "whoop_sync", emptyList(), 1),
        )
        val `forget`: LocalizedString get() = LocalizedString(
            key = StringKey("whoop.forget", "whoop_forget", emptyList(), 1),
        )
        val `forgetHint`: LocalizedString get() = LocalizedString(
            key = StringKey("whoop.forgetHint", "whoop_forget_hint", emptyList(), 1),
        )
        val `resetStorage`: LocalizedString get() = LocalizedString(
            key = StringKey("whoop.resetStorage", "whoop_reset_storage", emptyList(), 1),
        )
        val `resetStorageHint`: LocalizedString get() = LocalizedString(
            key = StringKey("whoop.resetStorageHint", "whoop_reset_storage_hint", emptyList(), 1),
        )
        fun `battery`(`value`: String): LocalizedString = LocalizedString(
            key = StringKey("whoop.battery", "whoop_battery", listOf("value"), 1),
            arguments = listOf(`value`),
        )
        fun `firmware`(`value`: String): LocalizedString = LocalizedString(
            key = StringKey("whoop.firmware", "whoop_firmware", listOf("value"), 1),
            arguments = listOf(`value`),
        )
        val `lastSync`: LocalizedString get() = LocalizedString(
            key = StringKey("whoop.lastSync", "whoop_last_sync", emptyList(), 1),
        )
        val `lastUpload`: LocalizedString get() = LocalizedString(
            key = StringKey("whoop.lastUpload", "whoop_last_upload", emptyList(), 1),
        )
        val `queue`: LocalizedString get() = LocalizedString(
            key = StringKey("whoop.queue", "whoop_queue", emptyList(), 1),
        )
        val `queueEmpty`: LocalizedString get() = LocalizedString(
            key = StringKey("whoop.queueEmpty", "whoop_queue_empty", emptyList(), 1),
        )
        fun `queueSize`(`count`: String, `size`: String): LocalizedString = LocalizedString(
            key = StringKey("whoop.queueSize", "whoop_queue_size", listOf("count", "size"), 1),
            arguments = listOf(`count`, `size`),
        )
        val `never`: LocalizedString get() = LocalizedString(
            key = StringKey("whoop.never", "whoop_never", emptyList(), 1),
        )
        val `disableImport`: LocalizedString get() = LocalizedString(
            key = StringKey("whoop.disableImport", "whoop_disable_import", emptyList(), 1),
        )
        fun `received`(`when`: String): LocalizedString = LocalizedString(
            key = StringKey("whoop.received", "whoop_received", listOf("when"), 1),
            arguments = listOf(`when`),
        )
        object `Error` {
            val `request_failed`: LocalizedString get() = LocalizedString(
                key = StringKey("whoop.error.request_failed", "whoop_error_request_failed", emptyList(), 1),
            )
            val `store_unavailable`: LocalizedString get() = LocalizedString(
                key = StringKey("whoop.error.store_unavailable", "whoop_error_store_unavailable", emptyList(), 1),
            )
            val `permission_denied`: LocalizedString get() = LocalizedString(
                key = StringKey("whoop.error.permission_denied", "whoop_error_permission_denied", emptyList(), 1),
            )
            val `bluetooth_off`: LocalizedString get() = LocalizedString(
                key = StringKey("whoop.error.bluetooth_off", "whoop_error_bluetooth_off", emptyList(), 1),
            )
            val `bluetooth_unavailable`: LocalizedString get() = LocalizedString(
                key = StringKey("whoop.error.bluetooth_unavailable", "whoop_error_bluetooth_unavailable", emptyList(), 1),
            )
            val `scan_failed`: LocalizedString get() = LocalizedString(
                key = StringKey("whoop.error.scan_failed", "whoop_error_scan_failed", emptyList(), 1),
            )
            val `not_scanned`: LocalizedString get() = LocalizedString(
                key = StringKey("whoop.error.not_scanned", "whoop_error_not_scanned", emptyList(), 1),
            )
            val `already_bound`: LocalizedString get() = LocalizedString(
                key = StringKey("whoop.error.already_bound", "whoop_error_already_bound", emptyList(), 1),
            )
            val `busy`: LocalizedString get() = LocalizedString(
                key = StringKey("whoop.error.busy", "whoop_error_busy", emptyList(), 1),
            )
            val `whoop_pairing_failed`: LocalizedString get() = LocalizedString(
                key = StringKey("whoop.error.whoop_pairing_failed", "whoop_error_whoop_pairing_failed", emptyList(), 1),
            )
            val `whoop_not_paired`: LocalizedString get() = LocalizedString(
                key = StringKey("whoop.error.whoop_not_paired", "whoop_error_whoop_not_paired", emptyList(), 1),
            )
            val `whoop_no_reply`: LocalizedString get() = LocalizedString(
                key = StringKey("whoop.error.whoop_no_reply", "whoop_error_whoop_no_reply", emptyList(), 1),
            )
            val `whoop_history_refused`: LocalizedString get() = LocalizedString(
                key = StringKey("whoop.error.whoop_history_refused", "whoop_error_whoop_history_refused", emptyList(), 1),
            )
            val `wrong_device`: LocalizedString get() = LocalizedString(
                key = StringKey("whoop.error.wrong_device", "whoop_error_wrong_device", emptyList(), 1),
            )
            val `disconnected`: LocalizedString get() = LocalizedString(
                key = StringKey("whoop.error.disconnected", "whoop_error_disconnected", emptyList(), 1),
            )
            val `timeout`: LocalizedString get() = LocalizedString(
                key = StringKey("whoop.error.timeout", "whoop_error_timeout", emptyList(), 1),
            )
            val `queue_full`: LocalizedString get() = LocalizedString(
                key = StringKey("whoop.error.queue_full", "whoop_error_queue_full", emptyList(), 1),
            )
            val `not_authenticated`: LocalizedString get() = LocalizedString(
                key = StringKey("whoop.error.not_authenticated", "whoop_error_not_authenticated", emptyList(), 1),
            )
            val `grant_revoked`: LocalizedString get() = LocalizedString(
                key = StringKey("whoop.error.grant_revoked", "whoop_error_grant_revoked", emptyList(), 1),
            )
            val `upload_failed`: LocalizedString get() = LocalizedString(
                key = StringKey("whoop.error.upload_failed", "whoop_error_upload_failed", emptyList(), 1),
            )
            val `unavailable`: LocalizedString get() = LocalizedString(
                key = StringKey("whoop.error.unavailable", "whoop_error_unavailable", emptyList(), 1),
            )
        }
        fun `signal`(`value`: String): LocalizedString = LocalizedString(
            key = StringKey("whoop.signal", "whoop_signal", listOf("value"), 1),
            arguments = listOf(`value`),
        )
    }
    object `Recordings` {
        val `title`: LocalizedString get() = LocalizedString(
            key = StringKey("recordings.title", "recordings_title", emptyList(), 1),
        )
        val `refresh`: LocalizedString get() = LocalizedString(
            key = StringKey("recordings.refresh", "recordings_refresh", emptyList(), 1),
        )
        val `date`: LocalizedString get() = LocalizedString(
            key = StringKey("recordings.date", "recordings_date", emptyList(), 1),
        )
        val `allDates`: LocalizedString get() = LocalizedString(
            key = StringKey("recordings.allDates", "recordings_all_dates", emptyList(), 1),
        )
        fun `zone`(`zone`: String): LocalizedString = LocalizedString(
            key = StringKey("recordings.zone", "recordings_zone", listOf("zone"), 1),
            arguments = listOf(`zone`),
        )
        fun `usage`(`used`: String, `maximum`: String): LocalizedString = LocalizedString(
            key = StringKey("recordings.usage", "recordings_usage", listOf("used", "maximum"), 1),
            arguments = listOf(`used`, `maximum`),
        )
        val `usageFailed`: LocalizedString get() = LocalizedString(
            key = StringKey("recordings.usageFailed", "recordings_usage_failed", emptyList(), 1),
        )
        fun `retention`(`days`: String): LocalizedString = LocalizedString(
            key = StringKey("recordings.retention", "recordings_retention", listOf("days"), 1),
            arguments = listOf(`days`),
        )
        val `retentionUnlimited`: LocalizedString get() = LocalizedString(
            key = StringKey("recordings.retentionUnlimited", "recordings_retention_unlimited", emptyList(), 1),
        )
        fun `duration`(`seconds`: String): LocalizedString = LocalizedString(
            key = StringKey("recordings.duration", "recordings_duration", listOf("seconds"), 1),
            arguments = listOf(`seconds`),
        )
        fun `minutes`(`minutes`: String): LocalizedString = LocalizedString(
            key = StringKey("recordings.minutes", "recordings_minutes", listOf("minutes"), 1),
            arguments = listOf(`minutes`),
        )
        fun `fragments`(quantity: Double, `count`: String): LocalizedString = LocalizedString(
            key = StringKey("recordings.fragments", "recordings_fragments", listOf("count"), 3),
            arguments = listOf(`count`),
            quantity = quantity,
        )
        object `Progress` {
            fun `pending`(`done`: String, `total`: String): LocalizedString = LocalizedString(
                key = StringKey("recordings.progress.pending", "recordings_progress_pending", listOf("done", "total"), 1),
                arguments = listOf(`done`, `total`),
            )
            fun `failed`(`failed`: String, `total`: String): LocalizedString = LocalizedString(
                key = StringKey("recordings.progress.failed", "recordings_progress_failed", listOf("failed", "total"), 1),
                arguments = listOf(`failed`, `total`),
            )
        }
        val `emptyTitle`: LocalizedString get() = LocalizedString(
            key = StringKey("recordings.emptyTitle", "recordings_empty_title", emptyList(), 1),
        )
        val `emptyText`: LocalizedString get() = LocalizedString(
            key = StringKey("recordings.emptyText", "recordings_empty_text", emptyList(), 1),
        )
        val `more`: LocalizedString get() = LocalizedString(
            key = StringKey("recordings.more", "recordings_more", emptyList(), 1),
        )
        val `listen`: LocalizedString get() = LocalizedString(
            key = StringKey("recordings.listen", "recordings_listen", emptyList(), 1),
        )
        val `playbackFailed`: LocalizedString get() = LocalizedString(
            key = StringKey("recordings.playbackFailed", "recordings_playback_failed", emptyList(), 1),
        )
        val `compatible`: LocalizedString get() = LocalizedString(
            key = StringKey("recordings.compatible", "recordings_compatible", emptyList(), 1),
        )
        val `transcript`: LocalizedString get() = LocalizedString(
            key = StringKey("recordings.transcript", "recordings_transcript", emptyList(), 1),
        )
        fun `expires`(`when`: String): LocalizedString = LocalizedString(
            key = StringKey("recordings.expires", "recordings_expires", listOf("when"), 1),
            arguments = listOf(`when`),
        )
        val `gone`: LocalizedString get() = LocalizedString(
            key = StringKey("recordings.gone", "recordings_gone", emptyList(), 1),
        )
        val `delete`: LocalizedString get() = LocalizedString(
            key = StringKey("recordings.delete", "recordings_delete", emptyList(), 1),
        )
        val `deleteConfirm`: LocalizedString get() = LocalizedString(
            key = StringKey("recordings.deleteConfirm", "recordings_delete_confirm", emptyList(), 1),
        )
        val `retry`: LocalizedString get() = LocalizedString(
            key = StringKey("recordings.retry", "recordings_retry", emptyList(), 1),
        )
        val `retryConfirm`: LocalizedString get() = LocalizedString(
            key = StringKey("recordings.retryConfirm", "recordings_retry_confirm", emptyList(), 1),
        )
        val `retryRepeatConfirm`: LocalizedString get() = LocalizedString(
            key = StringKey("recordings.retryRepeatConfirm", "recordings_retry_repeat_confirm", emptyList(), 1),
        )
        val `mutationUnknown`: LocalizedString get() = LocalizedString(
            key = StringKey("recordings.mutationUnknown", "recordings_mutation_unknown", emptyList(), 1),
        )
        object `Status` {
            val `pending`: LocalizedString get() = LocalizedString(
                key = StringKey("recordings.status.pending", "recordings_status_pending", emptyList(), 1),
            )
            val `processing`: LocalizedString get() = LocalizedString(
                key = StringKey("recordings.status.processing", "recordings_status_processing", emptyList(), 1),
            )
            val `ready`: LocalizedString get() = LocalizedString(
                key = StringKey("recordings.status.ready", "recordings_status_ready", emptyList(), 1),
            )
            val `empty`: LocalizedString get() = LocalizedString(
                key = StringKey("recordings.status.empty", "recordings_status_empty", emptyList(), 1),
            )
            val `failed`: LocalizedString get() = LocalizedString(
                key = StringKey("recordings.status.failed", "recordings_status_failed", emptyList(), 1),
            )
        }
        object `Errors` {
            val `connection`: LocalizedString get() = LocalizedString(
                key = StringKey("recordings.errors.connection", "recordings_errors_connection", emptyList(), 1),
            )
            val `access`: LocalizedString get() = LocalizedString(
                key = StringKey("recordings.errors.access", "recordings_errors_access", emptyList(), 1),
            )
            val `limit`: LocalizedString get() = LocalizedString(
                key = StringKey("recordings.errors.limit", "recordings_errors_limit", emptyList(), 1),
            )
            val `unknownOutcome`: LocalizedString get() = LocalizedString(
                key = StringKey("recordings.errors.unknownOutcome", "recordings_errors_unknown_outcome", emptyList(), 1),
            )
            val `audio`: LocalizedString get() = LocalizedString(
                key = StringKey("recordings.errors.audio", "recordings_errors_audio", emptyList(), 1),
            )
            val `transcription`: LocalizedString get() = LocalizedString(
                key = StringKey("recordings.errors.transcription", "recordings_errors_transcription", emptyList(), 1),
            )
        }
    }
    object `RecordQuality` {
        val `codes`: LocalizedString get() = LocalizedString(
            key = StringKey("recordQuality.codes", "record_quality_codes", emptyList(), 1),
        )
        val `unknown`: LocalizedString get() = LocalizedString(
            key = StringKey("recordQuality.unknown", "record_quality_unknown", emptyList(), 1),
        )
        object `Flags` {
            val `protocol_verified`: LocalizedString get() = LocalizedString(
                key = StringKey("recordQuality.flags.protocol_verified", "record_quality_flags_protocol_verified", emptyList(), 1),
            )
            val `protocol_unverified`: LocalizedString get() = LocalizedString(
                key = StringKey("recordQuality.flags.protocol_unverified", "record_quality_flags_protocol_unverified", emptyList(), 1),
            )
            val `estimated_stages`: LocalizedString get() = LocalizedString(
                key = StringKey("recordQuality.flags.estimated_stages", "record_quality_flags_estimated_stages", emptyList(), 1),
            )
            val `invalid`: LocalizedString get() = LocalizedString(
                key = StringKey("recordQuality.flags.invalid", "record_quality_flags_invalid", emptyList(), 1),
            )
            val `hrv_statistic_unknown`: LocalizedString get() = LocalizedString(
                key = StringKey("recordQuality.flags.hrv_statistic_unknown", "record_quality_flags_hrv_statistic_unknown", emptyList(), 1),
            )
            val `stress_scale_unknown`: LocalizedString get() = LocalizedString(
                key = StringKey("recordQuality.flags.stress_scale_unknown", "record_quality_flags_stress_scale_unknown", emptyList(), 1),
            )
            val `partial_day`: LocalizedString get() = LocalizedString(
                key = StringKey("recordQuality.flags.partial_day", "record_quality_flags_partial_day", emptyList(), 1),
            )
            val `partial`: LocalizedString get() = LocalizedString(
                key = StringKey("recordQuality.flags.partial", "record_quality_flags_partial", emptyList(), 1),
            )
            val `incomplete`: LocalizedString get() = LocalizedString(
                key = StringKey("recordQuality.flags.incomplete", "record_quality_flags_incomplete", emptyList(), 1),
            )
            val `identity_collision`: LocalizedString get() = LocalizedString(
                key = StringKey("recordQuality.flags.identity_collision", "record_quality_flags_identity_collision", emptyList(), 1),
            )
            val `sparse`: LocalizedString get() = LocalizedString(
                key = StringKey("recordQuality.flags.sparse", "record_quality_flags_sparse", emptyList(), 1),
            )
            val `missing`: LocalizedString get() = LocalizedString(
                key = StringKey("recordQuality.flags.missing", "record_quality_flags_missing", emptyList(), 1),
            )
            val `gap`: LocalizedString get() = LocalizedString(
                key = StringKey("recordQuality.flags.gap", "record_quality_flags_gap", emptyList(), 1),
            )
            val `out_of_range`: LocalizedString get() = LocalizedString(
                key = StringKey("recordQuality.flags.out_of_range", "record_quality_flags_out_of_range", emptyList(), 1),
            )
            val `ambiguous_layout`: LocalizedString get() = LocalizedString(
                key = StringKey("recordQuality.flags.ambiguous_layout", "record_quality_flags_ambiguous_layout", emptyList(), 1),
            )
            val `temperature_layout_unconfirmed`: LocalizedString get() = LocalizedString(
                key = StringKey("recordQuality.flags.temperature_layout_unconfirmed", "record_quality_flags_temperature_layout_unconfirmed", emptyList(), 1),
            )
            val `ambiguous_time`: LocalizedString get() = LocalizedString(
                key = StringKey("recordQuality.flags.ambiguous_time", "record_quality_flags_ambiguous_time", emptyList(), 1),
            )
            val `sleep_taxonomy_unverified`: LocalizedString get() = LocalizedString(
                key = StringKey("recordQuality.flags.sleep_taxonomy_unverified", "record_quality_flags_sleep_taxonomy_unverified", emptyList(), 1),
            )
            val `epoch_unknown`: LocalizedString get() = LocalizedString(
                key = StringKey("recordQuality.flags.epoch_unknown", "record_quality_flags_epoch_unknown", emptyList(), 1),
            )
            val `unit_unknown`: LocalizedString get() = LocalizedString(
                key = StringKey("recordQuality.flags.unit_unknown", "record_quality_flags_unit_unknown", emptyList(), 1),
            )
            val `sport_type_unknown`: LocalizedString get() = LocalizedString(
                key = StringKey("recordQuality.flags.sport_type_unknown", "record_quality_flags_sport_type_unknown", emptyList(), 1),
            )
            val `hr_cadence_unknown`: LocalizedString get() = LocalizedString(
                key = StringKey("recordQuality.flags.hr_cadence_unknown", "record_quality_flags_hr_cadence_unknown", emptyList(), 1),
            )
            val `home_current_wrapper_unavailable`: LocalizedString get() = LocalizedString(
                key = StringKey("recordQuality.flags.home_current_wrapper_unavailable", "record_quality_flags_home_current_wrapper_unavailable", emptyList(), 1),
            )
            val `home_current_wrapper_future`: LocalizedString get() = LocalizedString(
                key = StringKey("recordQuality.flags.home_current_wrapper_future", "record_quality_flags_home_current_wrapper_future", emptyList(), 1),
            )
            val `home_current_reference_zero`: LocalizedString get() = LocalizedString(
                key = StringKey("recordQuality.flags.home_current_reference_zero", "record_quality_flags_home_current_reference_zero", emptyList(), 1),
            )
            val `home_day_db_source_query_unqualified`: LocalizedString get() = LocalizedString(
                key = StringKey("recordQuality.flags.home_day_db_source_query_unqualified", "record_quality_flags_home_day_db_source_query_unqualified", emptyList(), 1),
            )
            val `home_day_db_hr_span_query_unqualified`: LocalizedString get() = LocalizedString(
                key = StringKey("recordQuality.flags.home_day_db_hr_span_query_unqualified", "record_quality_flags_home_day_db_hr_span_query_unqualified", emptyList(), 1),
            )
            val `home_anchor_source_query_unqualified`: LocalizedString get() = LocalizedString(
                key = StringKey("recordQuality.flags.home_anchor_source_query_unqualified", "record_quality_flags_home_anchor_source_query_unqualified", emptyList(), 1),
            )
            val `home_anchor_sleep_timestamp_order`: LocalizedString get() = LocalizedString(
                key = StringKey("recordQuality.flags.home_anchor_sleep_timestamp_order", "record_quality_flags_home_anchor_sleep_timestamp_order", emptyList(), 1),
            )
            val `home_anchor_hourly_source_unqualified`: LocalizedString get() = LocalizedString(
                key = StringKey("recordQuality.flags.home_anchor_hourly_source_unqualified", "record_quality_flags_home_anchor_hourly_source_unqualified", emptyList(), 1),
            )
            val `home_anchor_hourly_timestamp_order`: LocalizedString get() = LocalizedString(
                key = StringKey("recordQuality.flags.home_anchor_hourly_timestamp_order", "record_quality_flags_home_anchor_hourly_timestamp_order", emptyList(), 1),
            )
        }
    }
    object `Resolution` {
        val `result`: LocalizedString get() = LocalizedString(
            key = StringKey("resolution.result", "resolution_result", emptyList(), 1),
        )
        val `allSources`: LocalizedString get() = LocalizedString(
            key = StringKey("resolution.allSources", "resolution_all_sources", emptyList(), 1),
        )
        val `mode`: LocalizedString get() = LocalizedString(
            key = StringKey("resolution.mode", "resolution_mode", emptyList(), 1),
        )
        val `why`: LocalizedString get() = LocalizedString(
            key = StringKey("resolution.why", "resolution_why", emptyList(), 1),
        )
        fun `factsCount`(quantity: Double, `count`: String): LocalizedString = LocalizedString(
            key = StringKey("resolution.factsCount", "resolution_facts_count", listOf("count"), 3),
            arguments = listOf(`count`),
            quantity = quantity,
        )
        val `selected`: LocalizedString get() = LocalizedString(
            key = StringKey("resolution.selected", "resolution_selected", emptyList(), 1),
        )
        val `alternatives`: LocalizedString get() = LocalizedString(
            key = StringKey("resolution.alternatives", "resolution_alternatives", emptyList(), 1),
        )
        val `noSelected`: LocalizedString get() = LocalizedString(
            key = StringKey("resolution.noSelected", "resolution_no_selected", emptyList(), 1),
        )
        val `protocolUnverified`: LocalizedString get() = LocalizedString(
            key = StringKey("resolution.protocolUnverified", "resolution_protocol_unverified", emptyList(), 1),
        )
        fun `coverage`(`duration`: String): LocalizedString = LocalizedString(
            key = StringKey("resolution.coverage", "resolution_coverage", listOf("duration"), 1),
            arguments = listOf(`duration`),
        )
        val `coverageUnknown`: LocalizedString get() = LocalizedString(
            key = StringKey("resolution.coverageUnknown", "resolution_coverage_unknown", emptyList(), 1),
        )
        fun `measuredAt`(`when`: String): LocalizedString = LocalizedString(
            key = StringKey("resolution.measuredAt", "resolution_measured_at", listOf("when"), 1),
            arguments = listOf(`when`),
        )
        val `excluded`: LocalizedString get() = LocalizedString(
            key = StringKey("resolution.excluded", "resolution_excluded", emptyList(), 1),
        )
        val `exclude`: LocalizedString get() = LocalizedString(
            key = StringKey("resolution.exclude", "resolution_exclude", emptyList(), 1),
        )
        val `restore`: LocalizedString get() = LocalizedString(
            key = StringKey("resolution.restore", "resolution_restore", emptyList(), 1),
        )
        val `excludedDone`: LocalizedString get() = LocalizedString(
            key = StringKey("resolution.excludedDone", "resolution_excluded_done", emptyList(), 1),
        )
        val `restoredDone`: LocalizedString get() = LocalizedString(
            key = StringKey("resolution.restoredDone", "resolution_restored_done", emptyList(), 1),
        )
        val `sensorExcludeHint`: LocalizedString get() = LocalizedString(
            key = StringKey("resolution.sensorExcludeHint", "resolution_sensor_exclude_hint", emptyList(), 1),
        )
        val `points`: LocalizedString get() = LocalizedString(
            key = StringKey("resolution.points", "resolution_points", emptyList(), 1),
        )
        val `pointMean`: LocalizedString get() = LocalizedString(
            key = StringKey("resolution.pointMean", "resolution_point_mean", emptyList(), 1),
        )
        fun `count`(`count`: String): LocalizedString = LocalizedString(
            key = StringKey("resolution.count", "resolution_count", listOf("count"), 1),
            arguments = listOf(`count`),
        )
        val `min`: LocalizedString get() = LocalizedString(
            key = StringKey("resolution.min", "resolution_min", emptyList(), 1),
        )
        val `max`: LocalizedString get() = LocalizedString(
            key = StringKey("resolution.max", "resolution_max", emptyList(), 1),
        )
        val `intervalMeans`: LocalizedString get() = LocalizedString(
            key = StringKey("resolution.intervalMeans", "resolution_interval_means", emptyList(), 1),
        )
        val `intervalMean`: LocalizedString get() = LocalizedString(
            key = StringKey("resolution.intervalMean", "resolution_interval_mean", emptyList(), 1),
        )
        val `intervalExtrema`: LocalizedString get() = LocalizedString(
            key = StringKey("resolution.intervalExtrema", "resolution_interval_extrema", emptyList(), 1),
        )
        val `ranges`: LocalizedString get() = LocalizedString(
            key = StringKey("resolution.ranges", "resolution_ranges", emptyList(), 1),
        )
        val `rangeNoMean`: LocalizedString get() = LocalizedString(
            key = StringKey("resolution.rangeNoMean", "resolution_range_no_mean", emptyList(), 1),
        )
        object `SampleWindows` {
            val `title`: LocalizedString get() = LocalizedString(
                key = StringKey("resolution.sampleWindows.title", "resolution_sample_windows_title", emptyList(), 1),
            )
            fun `count`(`windows`: String, `usable`: String, `total`: String): LocalizedString = LocalizedString(
                key = StringKey("resolution.sampleWindows.count", "resolution_sample_windows_count", listOf("windows", "usable", "total"), 1),
                arguments = listOf(`windows`, `usable`, `total`),
            )
            val `mean`: LocalizedString get() = LocalizedString(
                key = StringKey("resolution.sampleWindows.mean", "resolution_sample_windows_mean", emptyList(), 1),
            )
            fun `seconds`(`count`: String): LocalizedString = LocalizedString(
                key = StringKey("resolution.sampleWindows.seconds", "resolution_sample_windows_seconds", listOf("count"), 1),
                arguments = listOf(`count`),
            )
            fun `ratio`(`percent`: String): LocalizedString = LocalizedString(
                key = StringKey("resolution.sampleWindows.ratio", "resolution_sample_windows_ratio", listOf("percent"), 1),
                arguments = listOf(`percent`),
            )
            fun `maxGap`(`seconds`: String): LocalizedString = LocalizedString(
                key = StringKey("resolution.sampleWindows.maxGap", "resolution_sample_windows_max_gap", listOf("seconds"), 1),
                arguments = listOf(`seconds`),
            )
            val `discreteHint`: LocalizedString get() = LocalizedString(
                key = StringKey("resolution.sampleWindows.discreteHint", "resolution_sample_windows_discrete_hint", emptyList(), 1),
            )
        }
        val `unmatchedPages`: LocalizedString get() = LocalizedString(
            key = StringKey("resolution.unmatchedPages", "resolution_unmatched_pages", emptyList(), 1),
        )
        val `refresh`: LocalizedString get() = LocalizedString(
            key = StringKey("resolution.refresh", "resolution_refresh", emptyList(), 1),
        )
        val `linksTitle`: LocalizedString get() = LocalizedString(
            key = StringKey("resolution.linksTitle", "resolution_links_title", emptyList(), 1),
        )
        val `link`: LocalizedString get() = LocalizedString(
            key = StringKey("resolution.link", "resolution_link", emptyList(), 1),
        )
        val `linked`: LocalizedString get() = LocalizedString(
            key = StringKey("resolution.linked", "resolution_linked", emptyList(), 1),
        )
        val `unlink`: LocalizedString get() = LocalizedString(
            key = StringKey("resolution.unlink", "resolution_unlink", emptyList(), 1),
        )
        val `unlinked`: LocalizedString get() = LocalizedString(
            key = StringKey("resolution.unlinked", "resolution_unlinked", emptyList(), 1),
        )
        val `manual`: LocalizedString get() = LocalizedString(
            key = StringKey("resolution.manual", "resolution_manual", emptyList(), 1),
        )
        object `Status` {
            val `available`: LocalizedString get() = LocalizedString(
                key = StringKey("resolution.status.available", "resolution_status_available", emptyList(), 1),
            )
            val `no_data`: LocalizedString get() = LocalizedString(
                key = StringKey("resolution.status.no_data", "resolution_status_no_data", emptyList(), 1),
            )
            val `conflict`: LocalizedString get() = LocalizedString(
                key = StringKey("resolution.status.conflict", "resolution_status_conflict", emptyList(), 1),
            )
            val `needs_source_selection`: LocalizedString get() = LocalizedString(
                key = StringKey("resolution.status.needs_source_selection", "resolution_status_needs_source_selection", emptyList(), 1),
            )
            val `analysis_conflict`: LocalizedString get() = LocalizedString(
                key = StringKey("resolution.status.analysis_conflict", "resolution_status_analysis_conflict", emptyList(), 1),
            )
            val `analysis_policy_conflict`: LocalizedString get() = LocalizedString(
                key = StringKey("resolution.status.analysis_policy_conflict", "resolution_status_analysis_policy_conflict", emptyList(), 1),
            )
            val `missing_in_latest_analysis`: LocalizedString get() = LocalizedString(
                key = StringKey("resolution.status.missing_in_latest_analysis", "resolution_status_missing_in_latest_analysis", emptyList(), 1),
            )
            val `unavailable`: LocalizedString get() = LocalizedString(
                key = StringKey("resolution.status.unavailable", "resolution_status_unavailable", emptyList(), 1),
            )
            val `partial`: LocalizedString get() = LocalizedString(
                key = StringKey("resolution.status.partial", "resolution_status_partial", emptyList(), 1),
            )
            val `unknown`: LocalizedString get() = LocalizedString(
                key = StringKey("resolution.status.unknown", "resolution_status_unknown", emptyList(), 1),
            )
        }
        object `Reason` {
            val `excluded`: LocalizedString get() = LocalizedString(
                key = StringKey("resolution.reason.excluded", "resolution_reason_excluded", emptyList(), 1),
            )
            val `duplicate`: LocalizedString get() = LocalizedString(
                key = StringKey("resolution.reason.duplicate", "resolution_reason_duplicate", emptyList(), 1),
            )
            val `confirmed_duplicate`: LocalizedString get() = LocalizedString(
                key = StringKey("resolution.reason.confirmed_duplicate", "resolution_reason_confirmed_duplicate", emptyList(), 1),
            )
            val `superseded`: LocalizedString get() = LocalizedString(
                key = StringKey("resolution.reason.superseded", "resolution_reason_superseded", emptyList(), 1),
            )
            val `superseded_revision`: LocalizedString get() = LocalizedString(
                key = StringKey("resolution.reason.superseded_revision", "resolution_reason_superseded_revision", emptyList(), 1),
            )
            val `lower_priority`: LocalizedString get() = LocalizedString(
                key = StringKey("resolution.reason.lower_priority", "resolution_reason_lower_priority", emptyList(), 1),
            )
            val `overlap`: LocalizedString get() = LocalizedString(
                key = StringKey("resolution.reason.overlap", "resolution_reason_overlap", emptyList(), 1),
            )
            val `partial_overlap`: LocalizedString get() = LocalizedString(
                key = StringKey("resolution.reason.partial_overlap", "resolution_reason_partial_overlap", emptyList(), 1),
            )
            val `same_source_overlap`: LocalizedString get() = LocalizedString(
                key = StringKey("resolution.reason.same_source_overlap", "resolution_reason_same_source_overlap", emptyList(), 1),
            )
            val `superseded_report`: LocalizedString get() = LocalizedString(
                key = StringKey("resolution.reason.superseded_report", "resolution_reason_superseded_report", emptyList(), 1),
            )
            val `sleep_report_conflict`: LocalizedString get() = LocalizedString(
                key = StringKey("resolution.reason.sleep_report_conflict", "resolution_reason_sleep_report_conflict", emptyList(), 1),
            )
            val `sleep_report_epoch_conflict`: LocalizedString get() = LocalizedString(
                key = StringKey("resolution.reason.sleep_report_epoch_conflict", "resolution_reason_sleep_report_epoch_conflict", emptyList(), 1),
            )
            val `sleep_report_correction_conflict`: LocalizedString get() = LocalizedString(
                key = StringKey("resolution.reason.sleep_report_correction_conflict", "resolution_reason_sleep_report_correction_conflict", emptyList(), 1),
            )
            val `daily_total`: LocalizedString get() = LocalizedString(
                key = StringKey("resolution.reason.daily_total", "resolution_reason_daily_total", emptyList(), 1),
            )
            val `daily_total_alternative`: LocalizedString get() = LocalizedString(
                key = StringKey("resolution.reason.daily_total_alternative", "resolution_reason_daily_total_alternative", emptyList(), 1),
            )
            val `needs_source_selection`: LocalizedString get() = LocalizedString(
                key = StringKey("resolution.reason.needs_source_selection", "resolution_reason_needs_source_selection", emptyList(), 1),
            )
            val `incompatible_semantics`: LocalizedString get() = LocalizedString(
                key = StringKey("resolution.reason.incompatible_semantics", "resolution_reason_incompatible_semantics", emptyList(), 1),
            )
            val `cross_day_interval`: LocalizedString get() = LocalizedString(
                key = StringKey("resolution.reason.cross_day_interval", "resolution_reason_cross_day_interval", emptyList(), 1),
            )
            val `open_interval`: LocalizedString get() = LocalizedString(
                key = StringKey("resolution.reason.open_interval", "resolution_reason_open_interval", emptyList(), 1),
            )
            val `counter_baseline_unknown`: LocalizedString get() = LocalizedString(
                key = StringKey("resolution.reason.counter_baseline_unknown", "resolution_reason_counter_baseline_unknown", emptyList(), 1),
            )
            val `unknown_semantics`: LocalizedString get() = LocalizedString(
                key = StringKey("resolution.reason.unknown_semantics", "resolution_reason_unknown_semantics", emptyList(), 1),
            )
            val `older_measurement`: LocalizedString get() = LocalizedString(
                key = StringKey("resolution.reason.older_measurement", "resolution_reason_older_measurement", emptyList(), 1),
            )
            val `different_method`: LocalizedString get() = LocalizedString(
                key = StringKey("resolution.reason.different_method", "resolution_reason_different_method", emptyList(), 1),
            )
            val `not_selected`: LocalizedString get() = LocalizedString(
                key = StringKey("resolution.reason.not_selected", "resolution_reason_not_selected", emptyList(), 1),
            )
            val `future_observation`: LocalizedString get() = LocalizedString(
                key = StringKey("resolution.reason.future_observation", "resolution_reason_future_observation", emptyList(), 1),
            )
            val `invalid_quality`: LocalizedString get() = LocalizedString(
                key = StringKey("resolution.reason.invalid_quality", "resolution_reason_invalid_quality", emptyList(), 1),
            )
            val `invalid_observation`: LocalizedString get() = LocalizedString(
                key = StringKey("resolution.reason.invalid_observation", "resolution_reason_invalid_observation", emptyList(), 1),
            )
            val `lineage_conflict`: LocalizedString get() = LocalizedString(
                key = StringKey("resolution.reason.lineage_conflict", "resolution_reason_lineage_conflict", emptyList(), 1),
            )
            val `counter_epoch_required`: LocalizedString get() = LocalizedString(
                key = StringKey("resolution.reason.counter_epoch_required", "resolution_reason_counter_epoch_required", emptyList(), 1),
            )
            val `missing_value`: LocalizedString get() = LocalizedString(
                key = StringKey("resolution.reason.missing_value", "resolution_reason_missing_value", emptyList(), 1),
            )
            val `counter_baseline`: LocalizedString get() = LocalizedString(
                key = StringKey("resolution.reason.counter_baseline", "resolution_reason_counter_baseline", emptyList(), 1),
            )
            val `counter_time_conflict`: LocalizedString get() = LocalizedString(
                key = StringKey("resolution.reason.counter_time_conflict", "resolution_reason_counter_time_conflict", emptyList(), 1),
            )
            val `counter_reset`: LocalizedString get() = LocalizedString(
                key = StringKey("resolution.reason.counter_reset", "resolution_reason_counter_reset", emptyList(), 1),
            )
            val `day_crossing_interval`: LocalizedString get() = LocalizedString(
                key = StringKey("resolution.reason.day_crossing_interval", "resolution_reason_day_crossing_interval", emptyList(), 1),
            )
            val `interval_outside_period`: LocalizedString get() = LocalizedString(
                key = StringKey("resolution.reason.interval_outside_period", "resolution_reason_interval_outside_period", emptyList(), 1),
            )
            val `source_selection_required`: LocalizedString get() = LocalizedString(
                key = StringKey("resolution.reason.source_selection_required", "resolution_reason_source_selection_required", emptyList(), 1),
            )
            val `simultaneous_measurements`: LocalizedString get() = LocalizedString(
                key = StringKey("resolution.reason.simultaneous_measurements", "resolution_reason_simultaneous_measurements", emptyList(), 1),
            )
            val `unsupported_semantics`: LocalizedString get() = LocalizedString(
                key = StringKey("resolution.reason.unsupported_semantics", "resolution_reason_unsupported_semantics", emptyList(), 1),
            )
            val `unknown_activity_definition`: LocalizedString get() = LocalizedString(
                key = StringKey("resolution.reason.unknown_activity_definition", "resolution_reason_unknown_activity_definition", emptyList(), 1),
            )
            val `equal_priority_points`: LocalizedString get() = LocalizedString(
                key = StringKey("resolution.reason.equal_priority_points", "resolution_reason_equal_priority_points", emptyList(), 1),
            )
            val `lower_priority_point_source`: LocalizedString get() = LocalizedString(
                key = StringKey("resolution.reason.lower_priority_point_source", "resolution_reason_lower_priority_point_source", emptyList(), 1),
            )
            val `equal_priority_sleep_source`: LocalizedString get() = LocalizedString(
                key = StringKey("resolution.reason.equal_priority_sleep_source", "resolution_reason_equal_priority_sleep_source", emptyList(), 1),
            )
            val `lower_priority_sleep_report`: LocalizedString get() = LocalizedString(
                key = StringKey("resolution.reason.lower_priority_sleep_report", "resolution_reason_lower_priority_sleep_report", emptyList(), 1),
            )
            val `equal_priority_overlap`: LocalizedString get() = LocalizedString(
                key = StringKey("resolution.reason.equal_priority_overlap", "resolution_reason_equal_priority_overlap", emptyList(), 1),
            )
            val `lower_priority_overlap`: LocalizedString get() = LocalizedString(
                key = StringKey("resolution.reason.lower_priority_overlap", "resolution_reason_lower_priority_overlap", emptyList(), 1),
            )
            val `incompatible_definition`: LocalizedString get() = LocalizedString(
                key = StringKey("resolution.reason.incompatible_definition", "resolution_reason_incompatible_definition", emptyList(), 1),
            )
            val `older_analysis`: LocalizedString get() = LocalizedString(
                key = StringKey("resolution.reason.older_analysis", "resolution_reason_older_analysis", emptyList(), 1),
            )
            val `lower_priority_analysis`: LocalizedString get() = LocalizedString(
                key = StringKey("resolution.reason.lower_priority_analysis", "resolution_reason_lower_priority_analysis", emptyList(), 1),
            )
            val `conflicting_source_policies`: LocalizedString get() = LocalizedString(
                key = StringKey("resolution.reason.conflicting_source_policies", "resolution_reason_conflicting_source_policies", emptyList(), 1),
            )
        }
        val `targets`: LocalizedString get() = LocalizedString(
            key = StringKey("resolution.targets", "resolution_targets", emptyList(), 1),
        )
        fun `firstMeasurement`(`when`: String): LocalizedString = LocalizedString(
            key = StringKey("resolution.firstMeasurement", "resolution_first_measurement", listOf("when"), 1),
            arguments = listOf(`when`),
        )
        fun `latestMeasurement`(`when`: String): LocalizedString = LocalizedString(
            key = StringKey("resolution.latestMeasurement", "resolution_latest_measurement", listOf("when"), 1),
            arguments = listOf(`when`),
        )
        object `Context` {
            object `Measurement` {
                val `measured`: LocalizedString get() = LocalizedString(
                    key = StringKey("resolution.context.measurement.measured", "resolution_context_measurement_measured", emptyList(), 1),
                )
                val `observed`: LocalizedString get() = LocalizedString(
                    key = StringKey("resolution.context.measurement.observed", "resolution_context_measurement_observed", emptyList(), 1),
                )
                val `resting`: LocalizedString get() = LocalizedString(
                    key = StringKey("resolution.context.measurement.resting", "resolution_context_measurement_resting", emptyList(), 1),
                )
                val `sample_window_mean`: LocalizedString get() = LocalizedString(
                    key = StringKey("resolution.context.measurement.sample_window_mean", "resolution_context_measurement_sample_window_mean", emptyList(), 1),
                )
                val `skin`: LocalizedString get() = LocalizedString(
                    key = StringKey("resolution.context.measurement.skin", "resolution_context_measurement_skin", emptyList(), 1),
                )
                val `device_estimate`: LocalizedString get() = LocalizedString(
                    key = StringKey("resolution.context.measurement.device_estimate", "resolution_context_measurement_device_estimate", emptyList(), 1),
                )
                val `unknown`: LocalizedString get() = LocalizedString(
                    key = StringKey("resolution.context.measurement.unknown", "resolution_context_measurement_unknown", emptyList(), 1),
                )
            }
            object `Location` {
                val `skin`: LocalizedString get() = LocalizedString(
                    key = StringKey("resolution.context.location.skin", "resolution_context_location_skin", emptyList(), 1),
                )
                val `body`: LocalizedString get() = LocalizedString(
                    key = StringKey("resolution.context.location.body", "resolution_context_location_body", emptyList(), 1),
                )
                val `wrist`: LocalizedString get() = LocalizedString(
                    key = StringKey("resolution.context.location.wrist", "resolution_context_location_wrist", emptyList(), 1),
                )
                val `finger`: LocalizedString get() = LocalizedString(
                    key = StringKey("resolution.context.location.finger", "resolution_context_location_finger", emptyList(), 1),
                )
                val `unknown`: LocalizedString get() = LocalizedString(
                    key = StringKey("resolution.context.location.unknown", "resolution_context_location_unknown", emptyList(), 1),
                )
            }
            object `Temperature` {
                val `absolute`: LocalizedString get() = LocalizedString(
                    key = StringKey("resolution.context.temperature.absolute", "resolution_context_temperature_absolute", emptyList(), 1),
                )
                val `deviation`: LocalizedString get() = LocalizedString(
                    key = StringKey("resolution.context.temperature.deviation", "resolution_context_temperature_deviation", emptyList(), 1),
                )
                val `baseline_deviation`: LocalizedString get() = LocalizedString(
                    key = StringKey("resolution.context.temperature.baseline_deviation", "resolution_context_temperature_baseline_deviation", emptyList(), 1),
                )
                val `unknown`: LocalizedString get() = LocalizedString(
                    key = StringKey("resolution.context.temperature.unknown", "resolution_context_temperature_unknown", emptyList(), 1),
                )
            }
            object `Context` {
                val `resting`: LocalizedString get() = LocalizedString(
                    key = StringKey("resolution.context.context.resting", "resolution_context_context_resting", emptyList(), 1),
                )
                val `exercise`: LocalizedString get() = LocalizedString(
                    key = StringKey("resolution.context.context.exercise", "resolution_context_context_exercise", emptyList(), 1),
                )
                val `running`: LocalizedString get() = LocalizedString(
                    key = StringKey("resolution.context.context.running", "resolution_context_context_running", emptyList(), 1),
                )
                val `workout`: LocalizedString get() = LocalizedString(
                    key = StringKey("resolution.context.context.workout", "resolution_context_context_workout", emptyList(), 1),
                )
                val `recovery`: LocalizedString get() = LocalizedString(
                    key = StringKey("resolution.context.context.recovery", "resolution_context_context_recovery", emptyList(), 1),
                )
                val `sleep`: LocalizedString get() = LocalizedString(
                    key = StringKey("resolution.context.context.sleep", "resolution_context_context_sleep", emptyList(), 1),
                )
            }
        }
        val `explanationTruncated`: LocalizedString get() = LocalizedString(
            key = StringKey("resolution.explanationTruncated", "resolution_explanation_truncated", emptyList(), 1),
        )
    }
    object `Sources` {
        val `title`: LocalizedString get() = LocalizedString(
            key = StringKey("sources.title", "sources_title", emptyList(), 1),
        )
        val `devices`: LocalizedString get() = LocalizedString(
            key = StringKey("sources.devices", "sources_devices", emptyList(), 1),
        )
        val `whereFrom`: LocalizedString get() = LocalizedString(
            key = StringKey("sources.whereFrom", "sources_where_from", emptyList(), 1),
        )
        fun `line`(`names`: String): LocalizedString = LocalizedString(
            key = StringKey("sources.line", "sources_line", listOf("names"), 1),
            arguments = listOf(`names`),
        )
        val `connect`: LocalizedString get() = LocalizedString(
            key = StringKey("sources.connect", "sources_connect", emptyList(), 1),
        )
        val `connectTitle`: LocalizedString get() = LocalizedString(
            key = StringKey("sources.connectTitle", "sources_connect_title", emptyList(), 1),
        )
        fun `disabledDevices`(`count`: String): LocalizedString = LocalizedString(
            key = StringKey("sources.disabledDevices", "sources_disabled_devices", listOf("count"), 1),
            arguments = listOf(`count`),
        )
        object `Group` {
            val `activity`: LocalizedString get() = LocalizedString(
                key = StringKey("sources.group.activity", "sources_group_activity", emptyList(), 1),
            )
            val `sleep`: LocalizedString get() = LocalizedString(
                key = StringKey("sources.group.sleep", "sources_group_sleep", emptyList(), 1),
            )
            val `vitals`: LocalizedString get() = LocalizedString(
                key = StringKey("sources.group.vitals", "sources_group_vitals", emptyList(), 1),
            )
            val `body`: LocalizedString get() = LocalizedString(
                key = StringKey("sources.group.body", "sources_group_body", emptyList(), 1),
            )
        }
        object `Row` {
            val `steps`: LocalizedString get() = LocalizedString(
                key = StringKey("sources.row.steps", "sources_row_steps", emptyList(), 1),
            )
            val `active_minutes`: LocalizedString get() = LocalizedString(
                key = StringKey("sources.row.active_minutes", "sources_row_active_minutes", emptyList(), 1),
            )
            val `active_energy`: LocalizedString get() = LocalizedString(
                key = StringKey("sources.row.active_energy", "sources_row_active_energy", emptyList(), 1),
            )
            val `distance`: LocalizedString get() = LocalizedString(
                key = StringKey("sources.row.distance", "sources_row_distance", emptyList(), 1),
            )
            val `workouts`: LocalizedString get() = LocalizedString(
                key = StringKey("sources.row.workouts", "sources_row_workouts", emptyList(), 1),
            )
            val `sleep`: LocalizedString get() = LocalizedString(
                key = StringKey("sources.row.sleep", "sources_row_sleep", emptyList(), 1),
            )
            val `heart_rate`: LocalizedString get() = LocalizedString(
                key = StringKey("sources.row.heart_rate", "sources_row_heart_rate", emptyList(), 1),
            )
            val `hrv`: LocalizedString get() = LocalizedString(
                key = StringKey("sources.row.hrv", "sources_row_hrv", emptyList(), 1),
            )
            val `spo2`: LocalizedString get() = LocalizedString(
                key = StringKey("sources.row.spo2", "sources_row_spo2", emptyList(), 1),
            )
            val `temperature`: LocalizedString get() = LocalizedString(
                key = StringKey("sources.row.temperature", "sources_row_temperature", emptyList(), 1),
            )
            val `stress`: LocalizedString get() = LocalizedString(
                key = StringKey("sources.row.stress", "sources_row_stress", emptyList(), 1),
            )
            val `weight`: LocalizedString get() = LocalizedString(
                key = StringKey("sources.row.weight", "sources_row_weight", emptyList(), 1),
            )
            val `body`: LocalizedString get() = LocalizedString(
                key = StringKey("sources.row.body", "sources_row_body", emptyList(), 1),
            )
        }
        object `Device` {
            val `ring`: LocalizedString get() = LocalizedString(
                key = StringKey("sources.device.ring", "sources_device_ring", emptyList(), 1),
            )
            val `whoop`: LocalizedString get() = LocalizedString(
                key = StringKey("sources.device.whoop", "sources_device_whoop", emptyList(), 1),
            )
            val `ddx`: LocalizedString get() = LocalizedString(
                key = StringKey("sources.device.ddx", "sources_device_ddx", emptyList(), 1),
            )
            val `caliper`: LocalizedString get() = LocalizedString(
                key = StringKey("sources.device.caliper", "sources_device_caliper", emptyList(), 1),
            )
            val `collector`: LocalizedString get() = LocalizedString(
                key = StringKey("sources.device.collector", "sources_device_collector", emptyList(), 1),
            )
            val `custom`: LocalizedString get() = LocalizedString(
                key = StringKey("sources.device.custom", "sources_device_custom", emptyList(), 1),
            )
        }
        object `Short` {
            val `ring`: LocalizedString get() = LocalizedString(
                key = StringKey("sources.short.ring", "sources_short_ring", emptyList(), 1),
            )
            val `whoop`: LocalizedString get() = LocalizedString(
                key = StringKey("sources.short.whoop", "sources_short_whoop", emptyList(), 1),
            )
            val `ddx`: LocalizedString get() = LocalizedString(
                key = StringKey("sources.short.ddx", "sources_short_ddx", emptyList(), 1),
            )
            val `caliper`: LocalizedString get() = LocalizedString(
                key = StringKey("sources.short.caliper", "sources_short_caliper", emptyList(), 1),
            )
        }
        fun `since`(`name`: String, `date`: String): LocalizedString = LocalizedString(
            key = StringKey("sources.since", "sources_since", listOf("name", "date"), 1),
            arguments = listOf(`name`, `date`),
        )
        val `automatic`: LocalizedString get() = LocalizedString(
            key = StringKey("sources.automatic", "sources_automatic", emptyList(), 1),
        )
        val `automaticText`: LocalizedString get() = LocalizedString(
            key = StringKey("sources.automaticText", "sources_automatic_text", emptyList(), 1),
        )
        fun `always`(`name`: String): LocalizedString = LocalizedString(
            key = StringKey("sources.always", "sources_always", listOf("name"), 1),
            arguments = listOf(`name`),
        )
        val `alwaysText`: LocalizedString get() = LocalizedString(
            key = StringKey("sources.alwaysText", "sources_always_text", emptyList(), 1),
        )
        val `effectiveFrom`: LocalizedString get() = LocalizedString(
            key = StringKey("sources.effectiveFrom", "sources_effective_from", emptyList(), 1),
        )
        val `today`: LocalizedString get() = LocalizedString(
            key = StringKey("sources.today", "sources_today", emptyList(), 1),
        )
        val `todayNone`: LocalizedString get() = LocalizedString(
            key = StringKey("sources.todayNone", "sources_today_none", emptyList(), 1),
        )
        val `compare`: LocalizedString get() = LocalizedString(
            key = StringKey("sources.compare", "sources_compare", emptyList(), 1),
        )
        fun `pair`(`a`: String, `b`: String): LocalizedString = LocalizedString(
            key = StringKey("sources.pair", "sources_pair", listOf("a", "b"), 1),
            arguments = listOf(`a`, `b`),
        )
        fun `meanDifference`(`value`: String): LocalizedString = LocalizedString(
            key = StringKey("sources.meanDifference", "sources_mean_difference", listOf("value"), 1),
            arguments = listOf(`value`),
        )
        fun `matches`(quantity: Double, `count`: String): LocalizedString = LocalizedString(
            key = StringKey("sources.matches", "sources_matches", listOf("count"), 3),
            arguments = listOf(`count`),
            quantity = quantity,
        )
        fun `hours`(quantity: Double, `count`: String): LocalizedString = LocalizedString(
            key = StringKey("sources.hours", "sources_hours", listOf("count"), 3),
            arguments = listOf(`count`),
            quantity = quantity,
        )
        fun `more`(`name`: String, `value`: String): LocalizedString = LocalizedString(
            key = StringKey("sources.more", "sources_more", listOf("name", "value"), 1),
            arguments = listOf(`name`, `value`),
        )
        fun `less`(`name`: String, `value`: String): LocalizedString = LocalizedString(
            key = StringKey("sources.less", "sources_less", listOf("name", "value"), 1),
            arguments = listOf(`name`, `value`),
        )
        val `same`: LocalizedString get() = LocalizedString(
            key = StringKey("sources.same", "sources_same", emptyList(), 1),
        )
        fun `outside`(`count`: String): LocalizedString = LocalizedString(
            key = StringKey("sources.outside", "sources_outside", listOf("count"), 1),
            arguments = listOf(`count`),
        )
        val `noComparison`: LocalizedString get() = LocalizedString(
            key = StringKey("sources.noComparison", "sources_no_comparison", emptyList(), 1),
        )
        val `save`: LocalizedString get() = LocalizedString(
            key = StringKey("sources.save", "sources_save", emptyList(), 1),
        )
        val `saved`: LocalizedString get() = LocalizedString(
            key = StringKey("sources.saved", "sources_saved", emptyList(), 1),
        )
        object `Role` {
            val `primary`: LocalizedString get() = LocalizedString(
                key = StringKey("sources.role.primary", "sources_role_primary", emptyList(), 1),
            )
            val `backup`: LocalizedString get() = LocalizedString(
                key = StringKey("sources.role.backup", "sources_role_backup", emptyList(), 1),
            )
        }
        val `data`: LocalizedString get() = LocalizedString(
            key = StringKey("sources.data", "sources_data", emptyList(), 1),
        )
        val `dayEmpty`: LocalizedString get() = LocalizedString(
            key = StringKey("sources.dayEmpty", "sources_day_empty", emptyList(), 1),
        )
        val `choose`: LocalizedString get() = LocalizedString(
            key = StringKey("sources.choose", "sources_choose", emptyList(), 1),
        )
        fun `chart`(`metric`: String): LocalizedString = LocalizedString(
            key = StringKey("sources.chart", "sources_chart", listOf("metric"), 1),
            arguments = listOf(`metric`),
        )
        fun `extremes`(`min`: String, `max`: String): LocalizedString = LocalizedString(
            key = StringKey("sources.extremes", "sources_extremes", listOf("min", "max"), 1),
            arguments = listOf(`min`, `max`),
        )
        fun `readings`(quantity: Double, `count`: String): LocalizedString = LocalizedString(
            key = StringKey("sources.readings", "sources_readings", listOf("count"), 3),
            arguments = listOf(`count`),
            quantity = quantity,
        )
        fun `workouts`(quantity: Double, `count`: String): LocalizedString = LocalizedString(
            key = StringKey("sources.workouts", "sources_workouts", listOf("count"), 3),
            arguments = listOf(`count`),
            quantity = quantity,
        )
        object `RoleDay` {
            val `primary`: LocalizedString get() = LocalizedString(
                key = StringKey("sources.roleDay.primary", "sources_role_day_primary", emptyList(), 1),
            )
            val `backup`: LocalizedString get() = LocalizedString(
                key = StringKey("sources.roleDay.backup", "sources_role_day_backup", emptyList(), 1),
            )
            val `unused`: LocalizedString get() = LocalizedString(
                key = StringKey("sources.roleDay.unused", "sources_role_day_unused", emptyList(), 1),
            )
        }
        val `details`: LocalizedString get() = LocalizedString(
            key = StringKey("sources.details", "sources_details", emptyList(), 1),
        )
        fun `connectedAt`(`date`: String): LocalizedString = LocalizedString(
            key = StringKey("sources.connectedAt", "sources_connected_at", listOf("date"), 1),
            arguments = listOf(`date`),
        )
        fun `battery`(`value`: String): LocalizedString = LocalizedString(
            key = StringKey("sources.battery", "sources_battery", listOf("value"), 1),
            arguments = listOf(`value`),
        )
        fun `dataAt`(`when`: String): LocalizedString = LocalizedString(
            key = StringKey("sources.dataAt", "sources_data_at", listOf("when"), 1),
            arguments = listOf(`when`),
        )
        val `noData`: LocalizedString get() = LocalizedString(
            key = StringKey("sources.noData", "sources_no_data", emptyList(), 1),
        )
        val `off`: LocalizedString get() = LocalizedString(
            key = StringKey("sources.off", "sources_off", emptyList(), 1),
        )
        val `turnOn`: LocalizedString get() = LocalizedString(
            key = StringKey("sources.turnOn", "sources_turn_on", emptyList(), 1),
        )
        val `turnOff`: LocalizedString get() = LocalizedString(
            key = StringKey("sources.turnOff", "sources_turn_off", emptyList(), 1),
        )
        val `turnedOn`: LocalizedString get() = LocalizedString(
            key = StringKey("sources.turnedOn", "sources_turned_on", emptyList(), 1),
        )
        val `turnedOff`: LocalizedString get() = LocalizedString(
            key = StringKey("sources.turnedOff", "sources_turned_off", emptyList(), 1),
        )
        val `history`: LocalizedString get() = LocalizedString(
            key = StringKey("sources.history", "sources_history", emptyList(), 1),
        )
        val `setupId`: LocalizedString get() = LocalizedString(
            key = StringKey("sources.setupId", "sources_setup_id", emptyList(), 1),
        )
        val `notFound`: LocalizedString get() = LocalizedString(
            key = StringKey("sources.notFound", "sources_not_found", emptyList(), 1),
        )
        val `phone`: LocalizedString get() = LocalizedString(
            key = StringKey("sources.phone", "sources_phone", emptyList(), 1),
        )
        val `androidOnly`: LocalizedString get() = LocalizedString(
            key = StringKey("sources.androidOnly", "sources_android_only", emptyList(), 1),
        )
        val `existing`: LocalizedString get() = LocalizedString(
            key = StringKey("sources.existing", "sources_existing", emptyList(), 1),
        )
        val `custom`: LocalizedString get() = LocalizedString(
            key = StringKey("sources.custom", "sources_custom", emptyList(), 1),
        )
        val `goals`: LocalizedString get() = LocalizedString(
            key = StringKey("sources.goals", "sources_goals", emptyList(), 1),
        )
        val `settingsText`: LocalizedString get() = LocalizedString(
            key = StringKey("sources.settingsText", "sources_settings_text", emptyList(), 1),
        )
        object `Unit` {
            val `bpm`: LocalizedString get() = LocalizedString(
                key = StringKey("sources.unit.bpm", "sources_unit_bpm", emptyList(), 1),
            )
            val `Key0025`: LocalizedString get() = LocalizedString(
                key = StringKey("sources.unit.%", "sources_unit_u0025", emptyList(), 1),
            )
            val `ms`: LocalizedString get() = LocalizedString(
                key = StringKey("sources.unit.ms", "sources_unit_ms", emptyList(), 1),
            )
            val `Key00b00043`: LocalizedString get() = LocalizedString(
                key = StringKey("sources.unit.°C", "sources_unit_u00b0c", emptyList(), 1),
            )
            val `steps`: LocalizedString get() = LocalizedString(
                key = StringKey("sources.unit.steps", "sources_unit_steps", emptyList(), 1),
            )
            val `min`: LocalizedString get() = LocalizedString(
                key = StringKey("sources.unit.min", "sources_unit_min", emptyList(), 1),
            )
            val `kcal`: LocalizedString get() = LocalizedString(
                key = StringKey("sources.unit.kcal", "sources_unit_kcal", emptyList(), 1),
            )
            val `m`: LocalizedString get() = LocalizedString(
                key = StringKey("sources.unit.m", "sources_unit_m", emptyList(), 1),
            )
            val `kg`: LocalizedString get() = LocalizedString(
                key = StringKey("sources.unit.kg", "sources_unit_kg", emptyList(), 1),
            )
        }
    }
    object `NativePhotos` {
        val `title`: LocalizedString get() = LocalizedString(
            key = StringKey("nativePhotos.title", "native_photos_title", emptyList(), 1),
        )
        val `all`: LocalizedString get() = LocalizedString(
            key = StringKey("nativePhotos.all", "native_photos_all", emptyList(), 1),
        )
        val `gridAccess`: LocalizedString get() = LocalizedString(
            key = StringKey("nativePhotos.gridAccess", "native_photos_grid_access", emptyList(), 1),
        )
        val `selectMore`: LocalizedString get() = LocalizedString(
            key = StringKey("nativePhotos.selectMore", "native_photos_select_more", emptyList(), 1),
        )
        val `camera`: LocalizedString get() = LocalizedString(
            key = StringKey("nativePhotos.camera", "native_photos_camera", emptyList(), 1),
        )
        val `share`: LocalizedString get() = LocalizedString(
            key = StringKey("nativePhotos.share", "native_photos_share", emptyList(), 1),
        )
    }
    object `NativeDictation` {
        val `denied`: LocalizedString get() = LocalizedString(
            key = StringKey("nativeDictation.denied", "native_dictation_denied", emptyList(), 1),
        )
        val `openSettings`: LocalizedString get() = LocalizedString(
            key = StringKey("nativeDictation.openSettings", "native_dictation_open_settings", emptyList(), 1),
        )
    }
    object `LiveClarification` {
        val `check`: LocalizedString get() = LocalizedString(
            key = StringKey("liveClarification.check", "live_clarification_check", emptyList(), 1),
        )
        val `open`: LocalizedString get() = LocalizedString(
            key = StringKey("liveClarification.open", "live_clarification_open", emptyList(), 1),
        )
        val `origin`: LocalizedString get() = LocalizedString(
            key = StringKey("liveClarification.origin", "live_clarification_origin", emptyList(), 1),
        )
        val `queued`: LocalizedString get() = LocalizedString(
            key = StringKey("liveClarification.queued", "live_clarification_queued", emptyList(), 1),
        )
        object `Reason` {
            val `generation_changed`: LocalizedString get() = LocalizedString(
                key = StringKey("liveClarification.reason.generation_changed", "live_clarification_reason_generation_changed", emptyList(), 1),
            )
            val `budget_exhausted`: LocalizedString get() = LocalizedString(
                key = StringKey("liveClarification.reason.budget_exhausted", "live_clarification_reason_budget_exhausted", emptyList(), 1),
            )
            val `run_interrupted`: LocalizedString get() = LocalizedString(
                key = StringKey("liveClarification.reason.run_interrupted", "live_clarification_reason_run_interrupted", emptyList(), 1),
            )
        }
        object `State` {
            val `accepted`: LocalizedString get() = LocalizedString(
                key = StringKey("liveClarification.state.accepted", "live_clarification_state_accepted", emptyList(), 1),
            )
            val `delivered`: LocalizedString get() = LocalizedString(
                key = StringKey("liveClarification.state.delivered", "live_clarification_state_delivered", emptyList(), 1),
            )
            val `applied`: LocalizedString get() = LocalizedString(
                key = StringKey("liveClarification.state.applied", "live_clarification_state_applied", emptyList(), 1),
            )
            val `queued`: LocalizedString get() = LocalizedString(
                key = StringKey("liveClarification.state.queued", "live_clarification_state_queued", emptyList(), 1),
            )
            val `rejected`: LocalizedString get() = LocalizedString(
                key = StringKey("liveClarification.state.rejected", "live_clarification_state_rejected", emptyList(), 1),
            )
            val `paused`: LocalizedString get() = LocalizedString(
                key = StringKey("liveClarification.state.paused", "live_clarification_state_paused", emptyList(), 1),
            )
        }
    }
    object `ActivityDay` {
        val `title`: LocalizedString get() = LocalizedString(
            key = StringKey("activityDay.title", "activity_day_title", emptyList(), 1),
        )
        val `steps`: LocalizedString get() = LocalizedString(
            key = StringKey("activityDay.steps", "activity_day_steps", emptyList(), 1),
        )
        val `activeMinutes`: LocalizedString get() = LocalizedString(
            key = StringKey("activityDay.activeMinutes", "activity_day_active_minutes", emptyList(), 1),
        )
        fun `details`(`metric`: String, `date`: String): LocalizedString = LocalizedString(
            key = StringKey("activityDay.details", "activity_day_details", listOf("metric", "date"), 1),
            arguments = listOf(`metric`, `date`),
        )
        fun `goal`(`value`: String): LocalizedString = LocalizedString(
            key = StringKey("activityDay.goal", "activity_day_goal", listOf("value"), 1),
            arguments = listOf(`value`),
        )
        val `complete`: LocalizedString get() = LocalizedString(
            key = StringKey("activityDay.complete", "activity_day_complete", emptyList(), 1),
        )
        val `partial`: LocalizedString get() = LocalizedString(
            key = StringKey("activityDay.partial", "activity_day_partial", emptyList(), 1),
        )
        val `coverageUnknown`: LocalizedString get() = LocalizedString(
            key = StringKey("activityDay.coverageUnknown", "activity_day_coverage_unknown", emptyList(), 1),
        )
        val `unverified`: LocalizedString get() = LocalizedString(
            key = StringKey("activityDay.unverified", "activity_day_unverified", emptyList(), 1),
        )
        val `weekTitle`: LocalizedString get() = LocalizedString(
            key = StringKey("activityDay.weekTitle", "activity_day_week_title", emptyList(), 1),
        )
        fun `weekLabel`(`from`: String, `to`: String): LocalizedString = LocalizedString(
            key = StringKey("activityDay.weekLabel", "activity_day_week_label", listOf("from", "to"), 1),
            arguments = listOf(`from`, `to`),
        )
        fun `dayLabel`(`date`: String, `value`: String, `quality`: String): LocalizedString = LocalizedString(
            key = StringKey("activityDay.dayLabel", "activity_day_day_label", listOf("date", "value", "quality"), 1),
            arguments = listOf(`date`, `value`, `quality`),
        )
        val `correctSteps`: LocalizedString get() = LocalizedString(
            key = StringKey("activityDay.correctSteps", "activity_day_correct_steps", emptyList(), 1),
        )
        val `history`: LocalizedString get() = LocalizedString(
            key = StringKey("activityDay.history", "activity_day_history", emptyList(), 1),
        )
        val `date`: LocalizedString get() = LocalizedString(
            key = StringKey("activityDay.date", "activity_day_date", emptyList(), 1),
        )
        val `amount`: LocalizedString get() = LocalizedString(
            key = StringKey("activityDay.amount", "activity_day_amount", emptyList(), 1),
        )
        val `quality`: LocalizedString get() = LocalizedString(
            key = StringKey("activityDay.quality", "activity_day_quality", emptyList(), 1),
        )
        val `activeHint`: LocalizedString get() = LocalizedString(
            key = StringKey("activityDay.activeHint", "activity_day_active_hint", emptyList(), 1),
        )
    }
    object `ActivityDetail` {
        val `catalog`: LocalizedString get() = LocalizedString(
            key = StringKey("activityDetail.catalog", "activity_detail_catalog", emptyList(), 1),
        )
        val `addStrength`: LocalizedString get() = LocalizedString(
            key = StringKey("activityDetail.addStrength", "activity_detail_add_strength", emptyList(), 1),
        )
        val `noExercises`: LocalizedString get() = LocalizedString(
            key = StringKey("activityDetail.noExercises", "activity_detail_no_exercises", emptyList(), 1),
        )
        val `noExercisesHint`: LocalizedString get() = LocalizedString(
            key = StringKey("activityDetail.noExercisesHint", "activity_detail_no_exercises_hint", emptyList(), 1),
        )
        val `chooseExerciseHint`: LocalizedString get() = LocalizedString(
            key = StringKey("activityDetail.chooseExerciseHint", "activity_detail_choose_exercise_hint", emptyList(), 1),
        )
        val `latestSets`: LocalizedString get() = LocalizedString(
            key = StringKey("activityDetail.latestSets", "activity_detail_latest_sets", emptyList(), 1),
        )
        val `previousDay`: LocalizedString get() = LocalizedString(
            key = StringKey("activityDetail.previousDay", "activity_detail_previous_day", emptyList(), 1),
        )
        val `noPreviousDay`: LocalizedString get() = LocalizedString(
            key = StringKey("activityDetail.noPreviousDay", "activity_detail_no_previous_day", emptyList(), 1),
        )
        fun `moreSets`(quantity: Double, `count`: String): LocalizedString = LocalizedString(
            key = StringKey("activityDetail.moreSets", "activity_detail_more_sets", listOf("count"), 3),
            arguments = listOf(`count`),
            quantity = quantity,
        )
        val `chartsAndSets`: LocalizedString get() = LocalizedString(
            key = StringKey("activityDetail.chartsAndSets", "activity_detail_charts_and_sets", emptyList(), 1),
        )
        val `workingSetsDetails`: LocalizedString get() = LocalizedString(
            key = StringKey("activityDetail.workingSetsDetails", "activity_detail_working_sets_details", emptyList(), 1),
        )
        val `comparisonBasis`: LocalizedString get() = LocalizedString(
            key = StringKey("activityDetail.comparisonBasis", "activity_detail_comparison_basis", emptyList(), 1),
        )
        val `separateMaxima`: LocalizedString get() = LocalizedString(
            key = StringKey("activityDetail.separateMaxima", "activity_detail_separate_maxima", emptyList(), 1),
        )
        val `assistanceMaxima`: LocalizedString get() = LocalizedString(
            key = StringKey("activityDetail.assistanceMaxima", "activity_detail_assistance_maxima", emptyList(), 1),
        )
        fun `assistanceSet`(`load`: String, `repetitions`: String): LocalizedString = LocalizedString(
            key = StringKey("activityDetail.assistanceSet", "activity_detail_assistance_set", listOf("load", "repetitions"), 1),
            arguments = listOf(`load`, `repetitions`),
        )
        fun `setPair`(`load`: String, `repetitions`: String): LocalizedString = LocalizedString(
            key = StringKey("activityDetail.setPair", "activity_detail_set_pair", listOf("load", "repetitions"), 1),
            arguments = listOf(`load`, `repetitions`),
        )
        fun `bodyweightSet`(`repetitions`: String): LocalizedString = LocalizedString(
            key = StringKey("activityDetail.bodyweightSet", "activity_detail_bodyweight_set", listOf("repetitions"), 1),
            arguments = listOf(`repetitions`),
        )
        fun `setsForDay`(`date`: String): LocalizedString = LocalizedString(
            key = StringKey("activityDetail.setsForDay", "activity_detail_sets_for_day", listOf("date"), 1),
            arguments = listOf(`date`),
        )
    }
    object `ActivityNative` {
        val `title`: LocalizedString get() = LocalizedString(
            key = StringKey("activityNative.title", "activity_native_title", emptyList(), 1),
        )
        fun `started`(`time`: String): LocalizedString = LocalizedString(
            key = StringKey("activityNative.started", "activity_native_started", listOf("time"), 1),
            arguments = listOf(`time`),
        )
        val `openRecorder`: LocalizedString get() = LocalizedString(
            key = StringKey("activityNative.openRecorder", "activity_native_open_recorder", emptyList(), 1),
        )
        val `details`: LocalizedString get() = LocalizedString(
            key = StringKey("activityNative.details", "activity_native_details", emptyList(), 1),
        )
        val `stop`: LocalizedString get() = LocalizedString(
            key = StringKey("activityNative.stop", "activity_native_stop", emptyList(), 1),
        )
        val `noPulse`: LocalizedString get() = LocalizedString(
            key = StringKey("activityNative.noPulse", "activity_native_no_pulse", emptyList(), 1),
        )
    }
    object `ActivityRedesign` {
        val `dynamics`: LocalizedString get() = LocalizedString(
            key = StringKey("activityRedesign.dynamics", "activity_redesign_dynamics", emptyList(), 1),
        )
        val `recentEntries`: LocalizedString get() = LocalizedString(
            key = StringKey("activityRedesign.recentEntries", "activity_redesign_recent_entries", emptyList(), 1),
        )
        fun `recentRange`(`range`: String): LocalizedString = LocalizedString(
            key = StringKey("activityRedesign.recentRange", "activity_redesign_recent_range", listOf("range"), 1),
            arguments = listOf(`range`),
        )
        val `noEntries`: LocalizedString get() = LocalizedString(
            key = StringKey("activityRedesign.noEntries", "activity_redesign_no_entries", emptyList(), 1),
        )
        val `manual`: LocalizedString get() = LocalizedString(
            key = StringKey("activityRedesign.manual", "activity_redesign_manual", emptyList(), 1),
        )
        val `manualSessions`: LocalizedString get() = LocalizedString(
            key = StringKey("activityRedesign.manualSessions", "activity_redesign_manual_sessions", emptyList(), 1),
        )
        val `movementData`: LocalizedString get() = LocalizedString(
            key = StringKey("activityRedesign.movementData", "activity_redesign_movement_data", emptyList(), 1),
        )
        val `strengthHistory`: LocalizedString get() = LocalizedString(
            key = StringKey("activityRedesign.strengthHistory", "activity_redesign_strength_history", emptyList(), 1),
        )
        val `backToDynamics`: LocalizedString get() = LocalizedString(
            key = StringKey("activityRedesign.backToDynamics", "activity_redesign_back_to_dynamics", emptyList(), 1),
        )
        val `chooseSubject`: LocalizedString get() = LocalizedString(
            key = StringKey("activityRedesign.chooseSubject", "activity_redesign_choose_subject", emptyList(), 1),
        )
        val `chooseMeasure`: LocalizedString get() = LocalizedString(
            key = StringKey("activityRedesign.chooseMeasure", "activity_redesign_choose_measure", emptyList(), 1),
        )
        val `dailyTable`: LocalizedString get() = LocalizedString(
            key = StringKey("activityRedesign.dailyTable", "activity_redesign_daily_table", emptyList(), 1),
        )
        fun `availableDays`(`count`: String): LocalizedString = LocalizedString(
            key = StringKey("activityRedesign.availableDays", "activity_redesign_available_days", listOf("count"), 1),
            arguments = listOf(`count`),
        )
        val `historyCoverage`: LocalizedString get() = LocalizedString(
            key = StringKey("activityRedesign.historyCoverage", "activity_redesign_history_coverage", emptyList(), 1),
        )
        val `openDynamics`: LocalizedString get() = LocalizedString(
            key = StringKey("activityRedesign.openDynamics", "activity_redesign_open_dynamics", emptyList(), 1),
        )
        object `Subjects` {
            val `steps`: LocalizedString get() = LocalizedString(
                key = StringKey("activityRedesign.subjects.steps", "activity_redesign_subjects_steps", emptyList(), 1),
            )
            val `active_minutes`: LocalizedString get() = LocalizedString(
                key = StringKey("activityRedesign.subjects.active_minutes", "activity_redesign_subjects_active_minutes", emptyList(), 1),
            )
            val `sessions`: LocalizedString get() = LocalizedString(
                key = StringKey("activityRedesign.subjects.sessions", "activity_redesign_subjects_sessions", emptyList(), 1),
            )
            val `strength`: LocalizedString get() = LocalizedString(
                key = StringKey("activityRedesign.subjects.strength", "activity_redesign_subjects_strength", emptyList(), 1),
            )
        }
        object `SessionMeasures` {
            val `sessions`: LocalizedString get() = LocalizedString(
                key = StringKey("activityRedesign.sessionMeasures.sessions", "activity_redesign_session_measures_sessions", emptyList(), 1),
            )
            val `duration_minutes`: LocalizedString get() = LocalizedString(
                key = StringKey("activityRedesign.sessionMeasures.duration_minutes", "activity_redesign_session_measures_duration_minutes", emptyList(), 1),
            )
            val `working_sets`: LocalizedString get() = LocalizedString(
                key = StringKey("activityRedesign.sessionMeasures.working_sets", "activity_redesign_session_measures_working_sets", emptyList(), 1),
            )
        }
        fun `addDated`(`date`: String): LocalizedString = LocalizedString(
            key = StringKey("activityRedesign.addDated", "activity_redesign_add_dated", listOf("date"), 1),
            arguments = listOf(`date`),
        )
        fun `addForDate`(`date`: String): LocalizedString = LocalizedString(
            key = StringKey("activityRedesign.addForDate", "activity_redesign_add_for_date", listOf("date"), 1),
            arguments = listOf(`date`),
        )
    }
    object `ActivityReports` {
        val `title`: LocalizedString get() = LocalizedString(
            key = StringKey("activityReports.title", "activity_reports_title", emptyList(), 1),
        )
        val `description`: LocalizedString get() = LocalizedString(
            key = StringKey("activityReports.description", "activity_reports_description", emptyList(), 1),
        )
        val `source`: LocalizedString get() = LocalizedString(
            key = StringKey("activityReports.source", "activity_reports_source", emptyList(), 1),
        )
        val `allSources`: LocalizedString get() = LocalizedString(
            key = StringKey("activityReports.allSources", "activity_reports_all_sources", emptyList(), 1),
        )
        val `incomplete`: LocalizedString get() = LocalizedString(
            key = StringKey("activityReports.incomplete", "activity_reports_incomplete", emptyList(), 1),
        )
        val `refresh`: LocalizedString get() = LocalizedString(
            key = StringKey("activityReports.refresh", "activity_reports_refresh", emptyList(), 1),
        )
        val `empty`: LocalizedString get() = LocalizedString(
            key = StringKey("activityReports.empty", "activity_reports_empty", emptyList(), 1),
        )
        val `previousVersion`: LocalizedString get() = LocalizedString(
            key = StringKey("activityReports.previousVersion", "activity_reports_previous_version", emptyList(), 1),
        )
        val `unknownDisposition`: LocalizedString get() = LocalizedString(
            key = StringKey("activityReports.unknownDisposition", "activity_reports_unknown_disposition", emptyList(), 1),
        )
        val `technicalDisposition`: LocalizedString get() = LocalizedString(
            key = StringKey("activityReports.technicalDisposition", "activity_reports_technical_disposition", emptyList(), 1),
        )
        val `runningSteps`: LocalizedString get() = LocalizedString(
            key = StringKey("activityReports.runningSteps", "activity_reports_running_steps", emptyList(), 1),
        )
        val `activeEnergyOriginal`: LocalizedString get() = LocalizedString(
            key = StringKey("activityReports.activeEnergyOriginal", "activity_reports_active_energy_original", emptyList(), 1),
        )
        val `distanceOriginal`: LocalizedString get() = LocalizedString(
            key = StringKey("activityReports.distanceOriginal", "activity_reports_distance_original", emptyList(), 1),
        )
        val `kcal`: LocalizedString get() = LocalizedString(
            key = StringKey("activityReports.kcal", "activity_reports_kcal", emptyList(), 1),
        )
        val `meters`: LocalizedString get() = LocalizedString(
            key = StringKey("activityReports.meters", "activity_reports_meters", emptyList(), 1),
        )
        val `counterSnapshot`: LocalizedString get() = LocalizedString(
            key = StringKey("activityReports.counterSnapshot", "activity_reports_counter_snapshot", emptyList(), 1),
        )
        val `restore`: LocalizedString get() = LocalizedString(
            key = StringKey("activityReports.restore", "activity_reports_restore", emptyList(), 1),
        )
        val `exclude`: LocalizedString get() = LocalizedString(
            key = StringKey("activityReports.exclude", "activity_reports_exclude", emptyList(), 1),
        )
        val `loadMore`: LocalizedString get() = LocalizedString(
            key = StringKey("activityReports.loadMore", "activity_reports_load_more", emptyList(), 1),
        )
        val `notReported`: LocalizedString get() = LocalizedString(
            key = StringKey("activityReports.notReported", "activity_reports_not_reported", emptyList(), 1),
        )
        object `Disposition` {
            val `current`: LocalizedString get() = LocalizedString(
                key = StringKey("activityReports.disposition.current", "activity_reports_disposition_current", emptyList(), 1),
            )
            val `excluded`: LocalizedString get() = LocalizedString(
                key = StringKey("activityReports.disposition.excluded", "activity_reports_disposition_excluded", emptyList(), 1),
            )
            val `conflict`: LocalizedString get() = LocalizedString(
                key = StringKey("activityReports.disposition.conflict", "activity_reports_disposition_conflict", emptyList(), 1),
            )
            val `superseded`: LocalizedString get() = LocalizedString(
                key = StringKey("activityReports.disposition.superseded", "activity_reports_disposition_superseded", emptyList(), 1),
            )
        }
    }
    object `WorkoutDevice` {
        val `hideTrack`: LocalizedString get() = LocalizedString(
            key = StringKey("workoutDevice.hideTrack", "workout_device_hide_track", emptyList(), 1),
        )
        val `showTrack`: LocalizedString get() = LocalizedString(
            key = StringKey("workoutDevice.showTrack", "workout_device_show_track", emptyList(), 1),
        )
        fun `activeDuration`(`duration`: String): LocalizedString = LocalizedString(
            key = StringKey("workoutDevice.activeDuration", "workout_device_active_duration", listOf("duration"), 1),
            arguments = listOf(`duration`),
        )
        val `sportPlan`: LocalizedString get() = LocalizedString(
            key = StringKey("workoutDevice.sportPlan", "workout_device_sport_plan", emptyList(), 1),
        )
        fun `requested`(`id`: String, `candidate`: String, `actual`: String): LocalizedString = LocalizedString(
            key = StringKey("workoutDevice.requested", "workout_device_requested", listOf("id", "candidate", "actual"), 1),
            arguments = listOf(`id`, `candidate`, `actual`),
        )
        val `noCandidate`: LocalizedString get() = LocalizedString(
            key = StringKey("workoutDevice.noCandidate", "workout_device_no_candidate", emptyList(), 1),
        )
        val `unconfirmedMode`: LocalizedString get() = LocalizedString(
            key = StringKey("workoutDevice.unconfirmedMode", "workout_device_unconfirmed_mode", emptyList(), 1),
        )
        fun `pauses`(`count`: String): LocalizedString = LocalizedString(
            key = StringKey("workoutDevice.pauses", "workout_device_pauses", listOf("count"), 1),
            arguments = listOf(`count`),
        )
        val `savedFields`: LocalizedString get() = LocalizedString(
            key = StringKey("workoutDevice.savedFields", "workout_device_saved_fields", emptyList(), 1),
        )
        val `savedFieldsDescription`: LocalizedString get() = LocalizedString(
            key = StringKey("workoutDevice.savedFieldsDescription", "workout_device_saved_fields_description", emptyList(), 1),
        )
    }
    object `WorkoutPulse` {
        val `qualityWarning`: LocalizedString get() = LocalizedString(
            key = StringKey("workoutPulse.qualityWarning", "workout_pulse_quality_warning", emptyList(), 1),
        )
        val `noConfirmedSamples`: LocalizedString get() = LocalizedString(
            key = StringKey("workoutPulse.noConfirmedSamples", "workout_pulse_no_confirmed_samples", emptyList(), 1),
        )
        val `incomplete`: LocalizedString get() = LocalizedString(
            key = StringKey("workoutPulse.incomplete", "workout_pulse_incomplete", emptyList(), 1),
        )
        val `rawSamples`: LocalizedString get() = LocalizedString(
            key = StringKey("workoutPulse.rawSamples", "workout_pulse_raw_samples", emptyList(), 1),
        )
        val `refresh`: LocalizedString get() = LocalizedString(
            key = StringKey("workoutPulse.refresh", "workout_pulse_refresh", emptyList(), 1),
        )
    }
    object `WorkoutTrack` {
        val `zoomIn`: LocalizedString get() = LocalizedString(
            key = StringKey("workoutTrack.zoomIn", "workout_track_zoom_in", emptyList(), 1),
        )
        val `zoomOut`: LocalizedString get() = LocalizedString(
            key = StringKey("workoutTrack.zoomOut", "workout_track_zoom_out", emptyList(), 1),
        )
        val `panLeft`: LocalizedString get() = LocalizedString(
            key = StringKey("workoutTrack.panLeft", "workout_track_pan_left", emptyList(), 1),
        )
        val `panUp`: LocalizedString get() = LocalizedString(
            key = StringKey("workoutTrack.panUp", "workout_track_pan_up", emptyList(), 1),
        )
        val `panDown`: LocalizedString get() = LocalizedString(
            key = StringKey("workoutTrack.panDown", "workout_track_pan_down", emptyList(), 1),
        )
        val `panRight`: LocalizedString get() = LocalizedString(
            key = StringKey("workoutTrack.panRight", "workout_track_pan_right", emptyList(), 1),
        )
        val `fit`: LocalizedString get() = LocalizedString(
            key = StringKey("workoutTrack.fit", "workout_track_fit", emptyList(), 1),
        )
        val `mapLabel`: LocalizedString get() = LocalizedString(
            key = StringKey("workoutTrack.mapLabel", "workout_track_map_label", emptyList(), 1),
        )
        object `Contexts` {
            val `running`: LocalizedString get() = LocalizedString(
                key = StringKey("workoutTrack.contexts.running", "workout_track_contexts_running", emptyList(), 1),
            )
            val `workout`: LocalizedString get() = LocalizedString(
                key = StringKey("workoutTrack.contexts.workout", "workout_track_contexts_workout", emptyList(), 1),
            )
            val `recovery`: LocalizedString get() = LocalizedString(
                key = StringKey("workoutTrack.contexts.recovery", "workout_track_contexts_recovery", emptyList(), 1),
            )
        }
        val `mapExplanation`: LocalizedString get() = LocalizedString(
            key = StringKey("workoutTrack.mapExplanation", "workout_track_map_explanation", emptyList(), 1),
        )
        val `mapAttributionPrefix`: LocalizedString get() = LocalizedString(
            key = StringKey("workoutTrack.mapAttributionPrefix", "workout_track_map_attribution_prefix", emptyList(), 1),
        )
        val `mapAttributionName`: LocalizedString get() = LocalizedString(
            key = StringKey("workoutTrack.mapAttributionName", "workout_track_map_attribution_name", emptyList(), 1),
        )
        val `mapAttributionSuffix`: LocalizedString get() = LocalizedString(
            key = StringKey("workoutTrack.mapAttributionSuffix", "workout_track_map_attribution_suffix", emptyList(), 1),
        )
        val `empty`: LocalizedString get() = LocalizedString(
            key = StringKey("workoutTrack.empty", "workout_track_empty", emptyList(), 1),
        )
        val `unknownDistance`: LocalizedString get() = LocalizedString(
            key = StringKey("workoutTrack.unknownDistance", "workout_track_unknown_distance", emptyList(), 1),
        )
        fun `coordinates`(`usable`: String, `reported`: String): LocalizedString = LocalizedString(
            key = StringKey("workoutTrack.coordinates", "workout_track_coordinates", listOf("usable", "reported"), 1),
            arguments = listOf(`usable`, `reported`),
        )
        fun `coverage`(`seconds`: String, `gaps`: String): LocalizedString = LocalizedString(
            key = StringKey("workoutTrack.coverage", "workout_track_coverage", listOf("seconds", "gaps"), 1),
            arguments = listOf(`seconds`, `gaps`),
        )
        fun `pausedExcluded`(`count`: String): LocalizedString = LocalizedString(
            key = StringKey("workoutTrack.pausedExcluded", "workout_track_paused_excluded", listOf("count"), 1),
            arguments = listOf(`count`),
        )
        val `loadedSegments`: LocalizedString get() = LocalizedString(
            key = StringKey("workoutTrack.loadedSegments", "workout_track_loaded_segments", emptyList(), 1),
        )
        val `incomplete`: LocalizedString get() = LocalizedString(
            key = StringKey("workoutTrack.incomplete", "workout_track_incomplete", emptyList(), 1),
        )
        val `refresh`: LocalizedString get() = LocalizedString(
            key = StringKey("workoutTrack.refresh", "workout_track_refresh", emptyList(), 1),
        )
        val `loadMore`: LocalizedString get() = LocalizedString(
            key = StringKey("workoutTrack.loadMore", "workout_track_load_more", emptyList(), 1),
        )
        val `currentDetails`: LocalizedString get() = LocalizedString(
            key = StringKey("workoutTrack.currentDetails", "workout_track_current_details", emptyList(), 1),
        )
        val `versions`: LocalizedString get() = LocalizedString(
            key = StringKey("workoutTrack.versions", "workout_track_versions", emptyList(), 1),
        )
        val `versionsIncomplete`: LocalizedString get() = LocalizedString(
            key = StringKey("workoutTrack.versionsIncomplete", "workout_track_versions_incomplete", emptyList(), 1),
        )
        val `refreshVersions`: LocalizedString get() = LocalizedString(
            key = StringKey("workoutTrack.refreshVersions", "workout_track_refresh_versions", emptyList(), 1),
        )
        val `noVersions`: LocalizedString get() = LocalizedString(
            key = StringKey("workoutTrack.noVersions", "workout_track_no_versions", emptyList(), 1),
        )
        fun `version`(`revision`: String, `status`: String): LocalizedString = LocalizedString(
            key = StringKey("workoutTrack.version", "workout_track_version", listOf("revision", "status"), 1),
            arguments = listOf(`revision`, `status`),
        )
        val `latest`: LocalizedString get() = LocalizedString(
            key = StringKey("workoutTrack.latest", "workout_track_latest", emptyList(), 1),
        )
        val `previous`: LocalizedString get() = LocalizedString(
            key = StringKey("workoutTrack.previous", "workout_track_previous", emptyList(), 1),
        )
        fun `gpsDistance`(`distance`: String): LocalizedString = LocalizedString(
            key = StringKey("workoutTrack.gpsDistance", "workout_track_gps_distance", listOf("distance"), 1),
            arguments = listOf(`distance`),
        )
        val `unknown`: LocalizedString get() = LocalizedString(
            key = StringKey("workoutTrack.unknown", "workout_track_unknown", emptyList(), 1),
        )
        fun `formula`(`version`: String): LocalizedString = LocalizedString(
            key = StringKey("workoutTrack.formula", "workout_track_formula", listOf("version"), 1),
            arguments = listOf(`version`),
        )
        fun `segments`(`count`: String): LocalizedString = LocalizedString(
            key = StringKey("workoutTrack.segments", "workout_track_segments", listOf("count"), 1),
            arguments = listOf(`count`),
        )
        val `unknownSegments`: LocalizedString get() = LocalizedString(
            key = StringKey("workoutTrack.unknownSegments", "workout_track_unknown_segments", emptyList(), 1),
        )
        fun `quality`(`quality`: String): LocalizedString = LocalizedString(
            key = StringKey("workoutTrack.quality", "workout_track_quality", listOf("quality"), 1),
            arguments = listOf(`quality`),
        )
        fun `pausedCoordinates`(`count`: String): LocalizedString = LocalizedString(
            key = StringKey("workoutTrack.pausedCoordinates", "workout_track_paused_coordinates", listOf("count"), 1),
            arguments = listOf(`count`),
        )
        fun `pauseDuration`(`seconds`: String): LocalizedString = LocalizedString(
            key = StringKey("workoutTrack.pauseDuration", "workout_track_pause_duration", listOf("seconds"), 1),
            arguments = listOf(`seconds`),
        )
        fun `speedLimit`(`value`: String): LocalizedString = LocalizedString(
            key = StringKey("workoutTrack.speedLimit", "workout_track_speed_limit", listOf("value"), 1),
            arguments = listOf(`value`),
        )
        val `notSavedMasculine`: LocalizedString get() = LocalizedString(
            key = StringKey("workoutTrack.notSavedMasculine", "workout_track_not_saved_masculine", emptyList(), 1),
        )
        fun `speedValue`(`value`: String): LocalizedString = LocalizedString(
            key = StringKey("workoutTrack.speedValue", "workout_track_speed_value", listOf("value"), 1),
            arguments = listOf(`value`),
        )
        fun `maxInterval`(`value`: String): LocalizedString = LocalizedString(
            key = StringKey("workoutTrack.maxInterval", "workout_track_max_interval", listOf("value"), 1),
            arguments = listOf(`value`),
        )
        fun `secondsValue`(`value`: String): LocalizedString = LocalizedString(
            key = StringKey("workoutTrack.secondsValue", "workout_track_seconds_value", listOf("value"), 1),
            arguments = listOf(`value`),
        )
        fun `maxAccuracy`(`value`: String): LocalizedString = LocalizedString(
            key = StringKey("workoutTrack.maxAccuracy", "workout_track_max_accuracy", listOf("value"), 1),
            arguments = listOf(`value`),
        )
        val `notSavedFeminine`: LocalizedString get() = LocalizedString(
            key = StringKey("workoutTrack.notSavedFeminine", "workout_track_not_saved_feminine", emptyList(), 1),
        )
        fun `metersValue`(`value`: String): LocalizedString = LocalizedString(
            key = StringKey("workoutTrack.metersValue", "workout_track_meters_value", listOf("value"), 1),
            arguments = listOf(`value`),
        )
        fun `pausePolicy`(`policy`: String): LocalizedString = LocalizedString(
            key = StringKey("workoutTrack.pausePolicy", "workout_track_pause_policy", listOf("policy"), 1),
            arguments = listOf(`policy`),
        )
        val `excludePaused`: LocalizedString get() = LocalizedString(
            key = StringKey("workoutTrack.excludePaused", "workout_track_exclude_paused", emptyList(), 1),
        )
        val `notSavedNeuter`: LocalizedString get() = LocalizedString(
            key = StringKey("workoutTrack.notSavedNeuter", "workout_track_not_saved_neuter", emptyList(), 1),
        )
        fun `reportRef`(`id`: String, `revision`: String): LocalizedString = LocalizedString(
            key = StringKey("workoutTrack.reportRef", "workout_track_report_ref", listOf("id", "revision"), 1),
            arguments = listOf(`id`, `revision`),
        )
        fun `inputCount`(`count`: String): LocalizedString = LocalizedString(
            key = StringKey("workoutTrack.inputCount", "workout_track_input_count", listOf("count"), 1),
            arguments = listOf(`count`),
        )
        fun `inputRef`(`id`: String, `sequence`: String, `hash`: String): LocalizedString = LocalizedString(
            key = StringKey("workoutTrack.inputRef", "workout_track_input_ref", listOf("id", "sequence", "hash"), 1),
            arguments = listOf(`id`, `sequence`, `hash`),
        )
        val `noInputs`: LocalizedString get() = LocalizedString(
            key = StringKey("workoutTrack.noInputs", "workout_track_no_inputs", emptyList(), 1),
        )
        val `allVersionFields`: LocalizedString get() = LocalizedString(
            key = StringKey("workoutTrack.allVersionFields", "workout_track_all_version_fields", emptyList(), 1),
        )
        val `loadMoreVersions`: LocalizedString get() = LocalizedString(
            key = StringKey("workoutTrack.loadMoreVersions", "workout_track_load_more_versions", emptyList(), 1),
        )
        val `noQualityLimits`: LocalizedString get() = LocalizedString(
            key = StringKey("workoutTrack.noQualityLimits", "workout_track_no_quality_limits", emptyList(), 1),
        )
        object `QualityFlags` {
            val `gps_estimate`: LocalizedString get() = LocalizedString(
                key = StringKey("workoutTrack.qualityFlags.gps_estimate", "workout_track_quality_flags_gps_estimate", emptyList(), 1),
            )
            val `paused`: LocalizedString get() = LocalizedString(
                key = StringKey("workoutTrack.qualityFlags.paused", "workout_track_quality_flags_paused", emptyList(), 1),
            )
            val `partial`: LocalizedString get() = LocalizedString(
                key = StringKey("workoutTrack.qualityFlags.partial", "workout_track_quality_flags_partial", emptyList(), 1),
            )
            val `no_usable_segments`: LocalizedString get() = LocalizedString(
                key = StringKey("workoutTrack.qualityFlags.no_usable_segments", "workout_track_quality_flags_no_usable_segments", emptyList(), 1),
            )
            val `missing_chunk`: LocalizedString get() = LocalizedString(
                key = StringKey("workoutTrack.qualityFlags.missing_chunk", "workout_track_quality_flags_missing_chunk", emptyList(), 1),
            )
            val `gap`: LocalizedString get() = LocalizedString(
                key = StringKey("workoutTrack.qualityFlags.gap", "workout_track_quality_flags_gap", emptyList(), 1),
            )
            val `approximate`: LocalizedString get() = LocalizedString(
                key = StringKey("workoutTrack.qualityFlags.approximate", "workout_track_quality_flags_approximate", emptyList(), 1),
            )
            val `unusable_fix`: LocalizedString get() = LocalizedString(
                key = StringKey("workoutTrack.qualityFlags.unusable_fix", "workout_track_quality_flags_unusable_fix", emptyList(), 1),
            )
            val `sparse`: LocalizedString get() = LocalizedString(
                key = StringKey("workoutTrack.qualityFlags.sparse", "workout_track_quality_flags_sparse", emptyList(), 1),
            )
            val `implausible_segment`: LocalizedString get() = LocalizedString(
                key = StringKey("workoutTrack.qualityFlags.implausible_segment", "workout_track_quality_flags_implausible_segment", emptyList(), 1),
            )
        }
    }
    object `AppUpdate` {
        val `check`: LocalizedString get() = LocalizedString(
            key = StringKey("appUpdate.check", "app_update_check", emptyList(), 1),
        )
        val `checking`: LocalizedString get() = LocalizedString(
            key = StringKey("appUpdate.checking", "app_update_checking", emptyList(), 1),
        )
        val `upToDate`: LocalizedString get() = LocalizedString(
            key = StringKey("appUpdate.upToDate", "app_update_up_to_date", emptyList(), 1),
        )
        fun `available`(`version`: String): LocalizedString = LocalizedString(
            key = StringKey("appUpdate.available", "app_update_available", listOf("version"), 1),
            arguments = listOf(`version`),
        )
        val `install`: LocalizedString get() = LocalizedString(
            key = StringKey("appUpdate.install", "app_update_install", emptyList(), 1),
        )
        fun `downloading`(`percent`: String): LocalizedString = LocalizedString(
            key = StringKey("appUpdate.downloading", "app_update_downloading", listOf("percent"), 1),
            arguments = listOf(`percent`),
        )
        val `verifying`: LocalizedString get() = LocalizedString(
            key = StringKey("appUpdate.verifying", "app_update_verifying", emptyList(), 1),
        )
        val `permission`: LocalizedString get() = LocalizedString(
            key = StringKey("appUpdate.permission", "app_update_permission", emptyList(), 1),
        )
        val `installing`: LocalizedString get() = LocalizedString(
            key = StringKey("appUpdate.installing", "app_update_installing", emptyList(), 1),
        )
        val `confirm`: LocalizedString get() = LocalizedString(
            key = StringKey("appUpdate.confirm", "app_update_confirm", emptyList(), 1),
        )
        val `installed`: LocalizedString get() = LocalizedString(
            key = StringKey("appUpdate.installed", "app_update_installed", emptyList(), 1),
        )
        object `Errors` {
            val `wrong_package`: LocalizedString get() = LocalizedString(
                key = StringKey("appUpdate.errors.wrong_package", "app_update_errors_wrong_package", emptyList(), 1),
            )
            val `downgrade`: LocalizedString get() = LocalizedString(
                key = StringKey("appUpdate.errors.downgrade", "app_update_errors_downgrade", emptyList(), 1),
            )
            val `manifest`: LocalizedString get() = LocalizedString(
                key = StringKey("appUpdate.errors.manifest", "app_update_errors_manifest", emptyList(), 1),
            )
            val `session`: LocalizedString get() = LocalizedString(
                key = StringKey("appUpdate.errors.session", "app_update_errors_session", emptyList(), 1),
            )
            val `download_failed`: LocalizedString get() = LocalizedString(
                key = StringKey("appUpdate.errors.download_failed", "app_update_errors_download_failed", emptyList(), 1),
            )
            val `integrity`: LocalizedString get() = LocalizedString(
                key = StringKey("appUpdate.errors.integrity", "app_update_errors_integrity", emptyList(), 1),
            )
            val `cancelled`: LocalizedString get() = LocalizedString(
                key = StringKey("appUpdate.errors.cancelled", "app_update_errors_cancelled", emptyList(), 1),
            )
            val `blocked`: LocalizedString get() = LocalizedString(
                key = StringKey("appUpdate.errors.blocked", "app_update_errors_blocked", emptyList(), 1),
            )
            val `incompatible`: LocalizedString get() = LocalizedString(
                key = StringKey("appUpdate.errors.incompatible", "app_update_errors_incompatible", emptyList(), 1),
            )
            val `storage`: LocalizedString get() = LocalizedString(
                key = StringKey("appUpdate.errors.storage", "app_update_errors_storage", emptyList(), 1),
            )
            val `other`: LocalizedString get() = LocalizedString(
                key = StringKey("appUpdate.errors.other", "app_update_errors_other", emptyList(), 1),
            )
        }
    }
    object `DashboardNutrition` {
        val `title`: LocalizedString get() = LocalizedString(
            key = StringKey("dashboardNutrition.title", "dashboard_nutrition_title", emptyList(), 1),
        )
        val `today`: LocalizedString get() = LocalizedString(
            key = StringKey("dashboardNutrition.today", "dashboard_nutrition_today", emptyList(), 1),
        )
        val `kcal`: LocalizedString get() = LocalizedString(
            key = StringKey("dashboardNutrition.kcal", "dashboard_nutrition_kcal", emptyList(), 1),
        )
        val `grams`: LocalizedString get() = LocalizedString(
            key = StringKey("dashboardNutrition.grams", "dashboard_nutrition_grams", emptyList(), 1),
        )
        fun `of`(`value`: String): LocalizedString = LocalizedString(
            key = StringKey("dashboardNutrition.of", "dashboard_nutrition_of", listOf("value"), 1),
            arguments = listOf(`value`),
        )
        fun `minimum`(`value`: String): LocalizedString = LocalizedString(
            key = StringKey("dashboardNutrition.minimum", "dashboard_nutrition_minimum", listOf("value"), 1),
            arguments = listOf(`value`),
        )
        val `noLimit`: LocalizedString get() = LocalizedString(
            key = StringKey("dashboardNutrition.noLimit", "dashboard_nutrition_no_limit", emptyList(), 1),
        )
        val `noMinimum`: LocalizedString get() = LocalizedString(
            key = StringKey("dashboardNutrition.noMinimum", "dashboard_nutrition_no_minimum", emptyList(), 1),
        )
        fun `remaining`(`value`: String): LocalizedString = LocalizedString(
            key = StringKey("dashboardNutrition.remaining", "dashboard_nutrition_remaining", listOf("value"), 1),
            arguments = listOf(`value`),
        )
        fun `above`(`value`: String): LocalizedString = LocalizedString(
            key = StringKey("dashboardNutrition.above", "dashboard_nutrition_above", listOf("value"), 1),
            arguments = listOf(`value`),
        )
        val `protein`: LocalizedString get() = LocalizedString(
            key = StringKey("dashboardNutrition.protein", "dashboard_nutrition_protein", emptyList(), 1),
        )
        val `fat`: LocalizedString get() = LocalizedString(
            key = StringKey("dashboardNutrition.fat", "dashboard_nutrition_fat", emptyList(), 1),
        )
        val `carbs`: LocalizedString get() = LocalizedString(
            key = StringKey("dashboardNutrition.carbs", "dashboard_nutrition_carbs", emptyList(), 1),
        )
        fun `known`(`value`: String): LocalizedString = LocalizedString(
            key = StringKey("dashboardNutrition.known", "dashboard_nutrition_known", listOf("value"), 1),
            arguments = listOf(`value`),
        )
        fun `drafts`(`count`: String): LocalizedString = LocalizedString(
            key = StringKey("dashboardNutrition.drafts", "dashboard_nutrition_drafts", listOf("count"), 1),
            arguments = listOf(`count`),
        )
        val `proteinPartial`: LocalizedString get() = LocalizedString(
            key = StringKey("dashboardNutrition.proteinPartial", "dashboard_nutrition_protein_partial", emptyList(), 1),
        )
        val `empty`: LocalizedString get() = LocalizedString(
            key = StringKey("dashboardNutrition.empty", "dashboard_nutrition_empty", emptyList(), 1),
        )
        val `goalsMissing`: LocalizedString get() = LocalizedString(
            key = StringKey("dashboardNutrition.goalsMissing", "dashboard_nutrition_goals_missing", emptyList(), 1),
        )
        val `dayOpen`: LocalizedString get() = LocalizedString(
            key = StringKey("dashboardNutrition.dayOpen", "dashboard_nutrition_day_open", emptyList(), 1),
        )
        val `energyUnknown`: LocalizedString get() = LocalizedString(
            key = StringKey("dashboardNutrition.energyUnknown", "dashboard_nutrition_energy_unknown", emptyList(), 1),
        )
        val `bothGoals`: LocalizedString get() = LocalizedString(
            key = StringKey("dashboardNutrition.bothGoals", "dashboard_nutrition_both_goals", emptyList(), 1),
        )
        val `overLimit`: LocalizedString get() = LocalizedString(
            key = StringKey("dashboardNutrition.overLimit", "dashboard_nutrition_over_limit", emptyList(), 1),
        )
        val `proteinLow`: LocalizedString get() = LocalizedString(
            key = StringKey("dashboardNutrition.proteinLow", "dashboard_nutrition_protein_low", emptyList(), 1),
        )
        val `proteinUnknown`: LocalizedString get() = LocalizedString(
            key = StringKey("dashboardNutrition.proteinUnknown", "dashboard_nutrition_protein_unknown", emptyList(), 1),
        )
        val `diary`: LocalizedString get() = LocalizedString(
            key = StringKey("dashboardNutrition.diary", "dashboard_nutrition_diary", emptyList(), 1),
        )
        val `shortDiary`: LocalizedString get() = LocalizedString(
            key = StringKey("dashboardNutrition.shortDiary", "dashboard_nutrition_short_diary", emptyList(), 1),
        )
        val `more`: LocalizedString get() = LocalizedString(
            key = StringKey("dashboardNutrition.more", "dashboard_nutrition_more", emptyList(), 1),
        )
        val `changeGoals`: LocalizedString get() = LocalizedString(
            key = StringKey("dashboardNutrition.changeGoals", "dashboard_nutrition_change_goals", emptyList(), 1),
        )
        val `setGoals`: LocalizedString get() = LocalizedString(
            key = StringKey("dashboardNutrition.setGoals", "dashboard_nutrition_set_goals", emptyList(), 1),
        )
        val `chart`: LocalizedString get() = LocalizedString(
            key = StringKey("dashboardNutrition.chart", "dashboard_nutrition_chart", emptyList(), 1),
        )
        val `period`: LocalizedString get() = LocalizedString(
            key = StringKey("dashboardNutrition.period", "dashboard_nutrition_period", emptyList(), 1),
        )
        fun `days`(`count`: String): LocalizedString = LocalizedString(
            key = StringKey("dashboardNutrition.days", "dashboard_nutrition_days", listOf("count"), 1),
            arguments = listOf(`count`),
        )
        fun `passed`(quantity: Double, `count`: String): LocalizedString = LocalizedString(
            key = StringKey("dashboardNutrition.passed", "dashboard_nutrition_passed", listOf("count"), 3),
            arguments = listOf(`count`),
            quantity = quantity,
        )
        val `selectHint`: LocalizedString get() = LocalizedString(
            key = StringKey("dashboardNutrition.selectHint", "dashboard_nutrition_select_hint", emptyList(), 1),
        )
        val `emptyHint`: LocalizedString get() = LocalizedString(
            key = StringKey("dashboardNutrition.emptyHint", "dashboard_nutrition_empty_hint", emptyList(), 1),
        )
        val `emptyPeriod`: LocalizedString get() = LocalizedString(
            key = StringKey("dashboardNutrition.emptyPeriod", "dashboard_nutrition_empty_period", emptyList(), 1),
        )
        fun `chartLabel`(`start`: String, `end`: String): LocalizedString = LocalizedString(
            key = StringKey("dashboardNutrition.chartLabel", "dashboard_nutrition_chart_label", listOf("start", "end"), 1),
            arguments = listOf(`start`, `end`),
        )
        fun `dateDetails`(`day`: String, `calories`: String, `limit`: String, `protein`: String, `minimum`: String, `status`: String): LocalizedString = LocalizedString(
            key = StringKey("dashboardNutrition.dateDetails", "dashboard_nutrition_date_details", listOf("day", "calories", "limit", "protein", "minimum", "status"), 1),
            arguments = listOf(`day`, `calories`, `limit`, `protein`, `minimum`, `status`),
        )
        fun `emptyDay`(`day`: String): LocalizedString = LocalizedString(
            key = StringKey("dashboardNutrition.emptyDay", "dashboard_nutrition_empty_day", listOf("day"), 1),
            arguments = listOf(`day`),
        )
        val `goalLegend`: LocalizedString get() = LocalizedString(
            key = StringKey("dashboardNutrition.goalLegend", "dashboard_nutrition_goal_legend", emptyList(), 1),
        )
        val `missedLegend`: LocalizedString get() = LocalizedString(
            key = StringKey("dashboardNutrition.missedLegend", "dashboard_nutrition_missed_legend", emptyList(), 1),
        )
        val `pendingLegend`: LocalizedString get() = LocalizedString(
            key = StringKey("dashboardNutrition.pendingLegend", "dashboard_nutrition_pending_legend", emptyList(), 1),
        )
        val `limitLegend`: LocalizedString get() = LocalizedString(
            key = StringKey("dashboardNutrition.limitLegend", "dashboard_nutrition_limit_legend", emptyList(), 1),
        )
        val `loadingGoals`: LocalizedString get() = LocalizedString(
            key = StringKey("dashboardNutrition.loadingGoals", "dashboard_nutrition_loading_goals", emptyList(), 1),
        )
        fun `selectedDay`(`day`: String): LocalizedString = LocalizedString(
            key = StringKey("dashboardNutrition.selectedDay", "dashboard_nutrition_selected_day", listOf("day"), 1),
            arguments = listOf(`day`),
        )
    }
    object `Dashboard` {
        val `details`: LocalizedString get() = LocalizedString(
            key = StringKey("dashboard.details", "dashboard_details", emptyList(), 1),
        )
        val `allMeasurements`: LocalizedString get() = LocalizedString(
            key = StringKey("dashboard.allMeasurements", "dashboard_all_measurements", emptyList(), 1),
        )
        val `caliperHistory`: LocalizedString get() = LocalizedString(
            key = StringKey("dashboard.caliperHistory", "dashboard_caliper_history", emptyList(), 1),
        )
        fun `caliperLast`(`when`: String): LocalizedString = LocalizedString(
            key = StringKey("dashboard.caliperLast", "dashboard_caliper_last", listOf("when"), 1),
            arguments = listOf(`when`),
        )
        val `caliperRaw`: LocalizedString get() = LocalizedString(
            key = StringKey("dashboard.caliperRaw", "dashboard_caliper_raw", emptyList(), 1),
        )
        val `caliperFailed`: LocalizedString get() = LocalizedString(
            key = StringKey("dashboard.caliperFailed", "dashboard_caliper_failed", emptyList(), 1),
        )
        fun `sourcesUnavailable`(`names`: String): LocalizedString = LocalizedString(
            key = StringKey("dashboard.sourcesUnavailable", "dashboard_sources_unavailable", listOf("names"), 1),
            arguments = listOf(`names`),
        )
        val `caliperTitle`: LocalizedString get() = LocalizedString(
            key = StringKey("dashboard.caliperTitle", "dashboard_caliper_title", emptyList(), 1),
        )
        val `caliperLoading`: LocalizedString get() = LocalizedString(
            key = StringKey("dashboard.caliperLoading", "dashboard_caliper_loading", emptyList(), 1),
        )
        val `caliperEmpty`: LocalizedString get() = LocalizedString(
            key = StringKey("dashboard.caliperEmpty", "dashboard_caliper_empty", emptyList(), 1),
        )
        val `caliperProfile`: LocalizedString get() = LocalizedString(
            key = StringKey("dashboard.caliperProfile", "dashboard_caliper_profile", emptyList(), 1),
        )
    }
    object `DashboardWeight` {
        val `kg`: LocalizedString get() = LocalizedString(
            key = StringKey("dashboardWeight.kg", "dashboard_weight_kg", emptyList(), 1),
        )
        val `more`: LocalizedString get() = LocalizedString(
            key = StringKey("dashboardWeight.more", "dashboard_weight_more", emptyList(), 1),
        )
        val `add`: LocalizedString get() = LocalizedString(
            key = StringKey("dashboardWeight.add", "dashboard_weight_add", emptyList(), 1),
        )
        val `addShort`: LocalizedString get() = LocalizedString(
            key = StringKey("dashboardWeight.addShort", "dashboard_weight_add_short", emptyList(), 1),
        )
        val `unavailable`: LocalizedString get() = LocalizedString(
            key = StringKey("dashboardWeight.unavailable", "dashboard_weight_unavailable", emptyList(), 1),
        )
        val `noWeight`: LocalizedString get() = LocalizedString(
            key = StringKey("dashboardWeight.noWeight", "dashboard_weight_no_weight", emptyList(), 1),
        )
        fun `latest`(`when`: String): LocalizedString = LocalizedString(
            key = StringKey("dashboardWeight.latest", "dashboard_weight_latest", listOf("when"), 1),
            arguments = listOf(`when`),
        )
        fun `change`(`value`: String): LocalizedString = LocalizedString(
            key = StringKey("dashboardWeight.change", "dashboard_weight_change", listOf("value"), 1),
            arguments = listOf(`value`),
        )
        fun `target`(`value`: String): LocalizedString = LocalizedString(
            key = StringKey("dashboardWeight.target", "dashboard_weight_target", listOf("value"), 1),
            arguments = listOf(`value`),
        )
        fun `showChart`(`count`: String): LocalizedString = LocalizedString(
            key = StringKey("dashboardWeight.showChart", "dashboard_weight_show_chart", listOf("count"), 1),
            arguments = listOf(`count`),
        )
        val `hideChart`: LocalizedString get() = LocalizedString(
            key = StringKey("dashboardWeight.hideChart", "dashboard_weight_hide_chart", emptyList(), 1),
        )
        val `period`: LocalizedString get() = LocalizedString(
            key = StringKey("dashboardWeight.period", "dashboard_weight_period", emptyList(), 1),
        )
        fun `days`(`count`: String): LocalizedString = LocalizedString(
            key = StringKey("dashboardWeight.days", "dashboard_weight_days", listOf("count"), 1),
            arguments = listOf(`count`),
        )
        val `weightLegend`: LocalizedString get() = LocalizedString(
            key = StringKey("dashboardWeight.weightLegend", "dashboard_weight_weight_legend", emptyList(), 1),
        )
        val `fatLegend`: LocalizedString get() = LocalizedString(
            key = StringKey("dashboardWeight.fatLegend", "dashboard_weight_fat_legend", emptyList(), 1),
        )
        fun `chartLabel`(`from`: String, `to`: String): LocalizedString = LocalizedString(
            key = StringKey("dashboardWeight.chartLabel", "dashboard_weight_chart_label", listOf("from", "to"), 1),
            arguments = listOf(`from`, `to`),
        )
        val `weightLoading`: LocalizedString get() = LocalizedString(
            key = StringKey("dashboardWeight.weightLoading", "dashboard_weight_weight_loading", emptyList(), 1),
        )
        val `fatLoading`: LocalizedString get() = LocalizedString(
            key = StringKey("dashboardWeight.fatLoading", "dashboard_weight_fat_loading", emptyList(), 1),
        )
        val `weightError`: LocalizedString get() = LocalizedString(
            key = StringKey("dashboardWeight.weightError", "dashboard_weight_weight_error", emptyList(), 1),
        )
        val `fatError`: LocalizedString get() = LocalizedString(
            key = StringKey("dashboardWeight.fatError", "dashboard_weight_fat_error", emptyList(), 1),
        )
        val `retry`: LocalizedString get() = LocalizedString(
            key = StringKey("dashboardWeight.retry", "dashboard_weight_retry", emptyList(), 1),
        )
        val `noPoints`: LocalizedString get() = LocalizedString(
            key = StringKey("dashboardWeight.noPoints", "dashboard_weight_no_points", emptyList(), 1),
        )
        val `noFat`: LocalizedString get() = LocalizedString(
            key = StringKey("dashboardWeight.noFat", "dashboard_weight_no_fat", emptyList(), 1),
        )
        val `fatSingle`: LocalizedString get() = LocalizedString(
            key = StringKey("dashboardWeight.fatSingle", "dashboard_weight_fat_single", emptyList(), 1),
        )
        val `measuredOnly`: LocalizedString get() = LocalizedString(
            key = StringKey("dashboardWeight.measuredOnly", "dashboard_weight_measured_only", emptyList(), 1),
        )
        fun `conflictDays`(`count`: String): LocalizedString = LocalizedString(
            key = StringKey("dashboardWeight.conflictDays", "dashboard_weight_conflict_days", listOf("count"), 1),
            arguments = listOf(`count`),
        )
        val `truncated`: LocalizedString get() = LocalizedString(
            key = StringKey("dashboardWeight.truncated", "dashboard_weight_truncated", emptyList(), 1),
        )
        val `offline`: LocalizedString get() = LocalizedString(
            key = StringKey("dashboardWeight.offline", "dashboard_weight_offline", emptyList(), 1),
        )
        val `table`: LocalizedString get() = LocalizedString(
            key = StringKey("dashboardWeight.table", "dashboard_weight_table", emptyList(), 1),
        )
        val `history`: LocalizedString get() = LocalizedString(
            key = StringKey("dashboardWeight.history", "dashboard_weight_history", emptyList(), 1),
        )
        val `detailsTitle`: LocalizedString get() = LocalizedString(
            key = StringKey("dashboardWeight.detailsTitle", "dashboard_weight_details_title", emptyList(), 1),
        )
        val `when`: LocalizedString get() = LocalizedString(
            key = StringKey("dashboardWeight.when", "dashboard_weight_when", emptyList(), 1),
        )
        val `metric`: LocalizedString get() = LocalizedString(
            key = StringKey("dashboardWeight.metric", "dashboard_weight_metric", emptyList(), 1),
        )
        val `value`: LocalizedString get() = LocalizedString(
            key = StringKey("dashboardWeight.value", "dashboard_weight_value", emptyList(), 1),
        )
        val `source`: LocalizedString get() = LocalizedString(
            key = StringKey("dashboardWeight.source", "dashboard_weight_source", emptyList(), 1),
        )
        val `selectedWeight`: LocalizedString get() = LocalizedString(
            key = StringKey("dashboardWeight.selectedWeight", "dashboard_weight_selected_weight", emptyList(), 1),
        )
        val `fat`: LocalizedString get() = LocalizedString(
            key = StringKey("dashboardWeight.fat", "dashboard_weight_fat", emptyList(), 1),
        )
        val `excluded`: LocalizedString get() = LocalizedString(
            key = StringKey("dashboardWeight.excluded", "dashboard_weight_excluded", emptyList(), 1),
        )
        val `corrected`: LocalizedString get() = LocalizedString(
            key = StringKey("dashboardWeight.corrected", "dashboard_weight_corrected", emptyList(), 1),
        )
        val `correct`: LocalizedString get() = LocalizedString(
            key = StringKey("dashboardWeight.correct", "dashboard_weight_correct", emptyList(), 1),
        )
        val `unknownSource`: LocalizedString get() = LocalizedString(
            key = StringKey("dashboardWeight.unknownSource", "dashboard_weight_unknown_source", emptyList(), 1),
        )
        fun `calendarDate`(`date`: String): LocalizedString = LocalizedString(
            key = StringKey("dashboardWeight.calendarDate", "dashboard_weight_calendar_date", listOf("date"), 1),
            arguments = listOf(`date`),
        )
        val `sourceDetails`: LocalizedString get() = LocalizedString(
            key = StringKey("dashboardWeight.sourceDetails", "dashboard_weight_source_details", emptyList(), 1),
        )
        val `recordDetails`: LocalizedString get() = LocalizedString(
            key = StringKey("dashboardWeight.recordDetails", "dashboard_weight_record_details", emptyList(), 1),
        )
        val `emptyTable`: LocalizedString get() = LocalizedString(
            key = StringKey("dashboardWeight.emptyTable", "dashboard_weight_empty_table", emptyList(), 1),
        )
        val `changeIncomplete`: LocalizedString get() = LocalizedString(
            key = StringKey("dashboardWeight.changeIncomplete", "dashboard_weight_change_incomplete", emptyList(), 1),
        )
        val `manual`: LocalizedString get() = LocalizedString(
            key = StringKey("dashboardWeight.manual", "dashboard_weight_manual", emptyList(), 1),
        )
        object `Origin` {
            val `corrected`: LocalizedString get() = LocalizedString(
                key = StringKey("dashboardWeight.origin.corrected", "dashboard_weight_origin_corrected", emptyList(), 1),
            )
            val `estimate`: LocalizedString get() = LocalizedString(
                key = StringKey("dashboardWeight.origin.estimate", "dashboard_weight_origin_estimate", emptyList(), 1),
            )
            val `manual`: LocalizedString get() = LocalizedString(
                key = StringKey("dashboardWeight.origin.manual", "dashboard_weight_origin_manual", emptyList(), 1),
            )
            val `device`: LocalizedString get() = LocalizedString(
                key = StringKey("dashboardWeight.origin.device", "dashboard_weight_origin_device", emptyList(), 1),
            )
        }
    }
    object `DerivedMissingInputs` {
        val `title`: LocalizedString get() = LocalizedString(
            key = StringKey("derivedMissingInputs.title", "derived_missing_inputs_title", emptyList(), 1),
        )
        val `codesTitle`: LocalizedString get() = LocalizedString(
            key = StringKey("derivedMissingInputs.codesTitle", "derived_missing_inputs_codes_title", emptyList(), 1),
        )
        val `unknown`: LocalizedString get() = LocalizedString(
            key = StringKey("derivedMissingInputs.unknown", "derived_missing_inputs_unknown", emptyList(), 1),
        )
        val `inputUnknown`: LocalizedString get() = LocalizedString(
            key = StringKey("derivedMissingInputs.inputUnknown", "derived_missing_inputs_input_unknown", emptyList(), 1),
        )
        fun `invalidInput`(`label`: String): LocalizedString = LocalizedString(
            key = StringKey("derivedMissingInputs.invalidInput", "derived_missing_inputs_invalid_input", listOf("label"), 1),
            arguments = listOf(`label`),
        )
        fun `futureInput`(`label`: String): LocalizedString = LocalizedString(
            key = StringKey("derivedMissingInputs.futureInput", "derived_missing_inputs_future_input", listOf("label"), 1),
            arguments = listOf(`label`),
        )
        object `Inputs` {
            val `home_sleep_minutes`: LocalizedString get() = LocalizedString(
                key = StringKey("derivedMissingInputs.inputs.home_sleep_minutes", "derived_missing_inputs_inputs_home_sleep_minutes", emptyList(), 1),
            )
            val `home_deep_minutes`: LocalizedString get() = LocalizedString(
                key = StringKey("derivedMissingInputs.inputs.home_deep_minutes", "derived_missing_inputs_inputs_home_deep_minutes", emptyList(), 1),
            )
            val `screen_sleep_minutes`: LocalizedString get() = LocalizedString(
                key = StringKey("derivedMissingInputs.inputs.screen_sleep_minutes", "derived_missing_inputs_inputs_screen_sleep_minutes", emptyList(), 1),
            )
            val `screen_deep_minutes`: LocalizedString get() = LocalizedString(
                key = StringKey("derivedMissingInputs.inputs.screen_deep_minutes", "derived_missing_inputs_inputs_screen_deep_minutes", emptyList(), 1),
            )
            val `screen_awake_minutes`: LocalizedString get() = LocalizedString(
                key = StringKey("derivedMissingInputs.inputs.screen_awake_minutes", "derived_missing_inputs_inputs_screen_awake_minutes", emptyList(), 1),
            )
            val `sleep_goal_minutes`: LocalizedString get() = LocalizedString(
                key = StringKey("derivedMissingInputs.inputs.sleep_goal_minutes", "derived_missing_inputs_inputs_sleep_goal_minutes", emptyList(), 1),
            )
            val `energy_sleep_minutes`: LocalizedString get() = LocalizedString(
                key = StringKey("derivedMissingInputs.inputs.energy_sleep_minutes", "derived_missing_inputs_inputs_energy_sleep_minutes", emptyList(), 1),
            )
            val `energy_deep_minutes`: LocalizedString get() = LocalizedString(
                key = StringKey("derivedMissingInputs.inputs.energy_deep_minutes", "derived_missing_inputs_inputs_energy_deep_minutes", emptyList(), 1),
            )
            val `energy_light_minutes`: LocalizedString get() = LocalizedString(
                key = StringKey("derivedMissingInputs.inputs.energy_light_minutes", "derived_missing_inputs_inputs_energy_light_minutes", emptyList(), 1),
            )
            val `energy_rem_minutes`: LocalizedString get() = LocalizedString(
                key = StringKey("derivedMissingInputs.inputs.energy_rem_minutes", "derived_missing_inputs_inputs_energy_rem_minutes", emptyList(), 1),
            )
            val `energy_awake_minutes`: LocalizedString get() = LocalizedString(
                key = StringKey("derivedMissingInputs.inputs.energy_awake_minutes", "derived_missing_inputs_inputs_energy_awake_minutes", emptyList(), 1),
            )
            val `ide_sleep_minutes`: LocalizedString get() = LocalizedString(
                key = StringKey("derivedMissingInputs.inputs.ide_sleep_minutes", "derived_missing_inputs_inputs_ide_sleep_minutes", emptyList(), 1),
            )
            val `ide_deep_minutes`: LocalizedString get() = LocalizedString(
                key = StringKey("derivedMissingInputs.inputs.ide_deep_minutes", "derived_missing_inputs_inputs_ide_deep_minutes", emptyList(), 1),
            )
            val `ide_light_minutes`: LocalizedString get() = LocalizedString(
                key = StringKey("derivedMissingInputs.inputs.ide_light_minutes", "derived_missing_inputs_inputs_ide_light_minutes", emptyList(), 1),
            )
            val `ide_rem_minutes`: LocalizedString get() = LocalizedString(
                key = StringKey("derivedMissingInputs.inputs.ide_rem_minutes", "derived_missing_inputs_inputs_ide_rem_minutes", emptyList(), 1),
            )
            val `ide_awake_minutes`: LocalizedString get() = LocalizedString(
                key = StringKey("derivedMissingInputs.inputs.ide_awake_minutes", "derived_missing_inputs_inputs_ide_awake_minutes", emptyList(), 1),
            )
            val `body_sleep_minutes`: LocalizedString get() = LocalizedString(
                key = StringKey("derivedMissingInputs.inputs.body_sleep_minutes", "derived_missing_inputs_inputs_body_sleep_minutes", emptyList(), 1),
            )
            val `body_deep_minutes`: LocalizedString get() = LocalizedString(
                key = StringKey("derivedMissingInputs.inputs.body_deep_minutes", "derived_missing_inputs_inputs_body_deep_minutes", emptyList(), 1),
            )
            val `body_deep_percent`: LocalizedString get() = LocalizedString(
                key = StringKey("derivedMissingInputs.inputs.body_deep_percent", "derived_missing_inputs_inputs_body_deep_percent", emptyList(), 1),
            )
            val `deep_percent`: LocalizedString get() = LocalizedString(
                key = StringKey("derivedMissingInputs.inputs.deep_percent", "derived_missing_inputs_inputs_deep_percent", emptyList(), 1),
            )
            val `hr_7d`: LocalizedString get() = LocalizedString(
                key = StringKey("derivedMissingInputs.inputs.hr_7d", "derived_missing_inputs_inputs_hr_7d", emptyList(), 1),
            )
            val `hr_24h`: LocalizedString get() = LocalizedString(
                key = StringKey("derivedMissingInputs.inputs.hr_24h", "derived_missing_inputs_inputs_hr_24h", emptyList(), 1),
            )
            val `hr_14d`: LocalizedString get() = LocalizedString(
                key = StringKey("derivedMissingInputs.inputs.hr_14d", "derived_missing_inputs_inputs_hr_14d", emptyList(), 1),
            )
            val `night_hr`: LocalizedString get() = LocalizedString(
                key = StringKey("derivedMissingInputs.inputs.night_hr", "derived_missing_inputs_inputs_night_hr", emptyList(), 1),
            )
            val `night_hrv`: LocalizedString get() = LocalizedString(
                key = StringKey("derivedMissingInputs.inputs.night_hrv", "derived_missing_inputs_inputs_night_hrv", emptyList(), 1),
            )
            val `night_rhr_baseline`: LocalizedString get() = LocalizedString(
                key = StringKey("derivedMissingInputs.inputs.night_rhr_baseline", "derived_missing_inputs_inputs_night_rhr_baseline", emptyList(), 1),
            )
            val `night_hrv_baseline`: LocalizedString get() = LocalizedString(
                key = StringKey("derivedMissingInputs.inputs.night_hrv_baseline", "derived_missing_inputs_inputs_night_hrv_baseline", emptyList(), 1),
            )
            val `home_steps_current`: LocalizedString get() = LocalizedString(
                key = StringKey("derivedMissingInputs.inputs.home_steps_current", "derived_missing_inputs_inputs_home_steps_current", emptyList(), 1),
            )
            val `home_steps_day_db`: LocalizedString get() = LocalizedString(
                key = StringKey("derivedMissingInputs.inputs.home_steps_day_db", "derived_missing_inputs_inputs_home_steps_day_db", emptyList(), 1),
            )
            val `home_steps_session`: LocalizedString get() = LocalizedString(
                key = StringKey("derivedMissingInputs.inputs.home_steps_session", "derived_missing_inputs_inputs_home_steps_session", emptyList(), 1),
            )
            val `home_steps_anchor`: LocalizedString get() = LocalizedString(
                key = StringKey("derivedMissingInputs.inputs.home_steps_anchor", "derived_missing_inputs_inputs_home_steps_anchor", emptyList(), 1),
            )
            val `steps_goal`: LocalizedString get() = LocalizedString(
                key = StringKey("derivedMissingInputs.inputs.steps_goal", "derived_missing_inputs_inputs_steps_goal", emptyList(), 1),
            )
            val `hrv_current`: LocalizedString get() = LocalizedString(
                key = StringKey("derivedMissingInputs.inputs.hrv_current", "derived_missing_inputs_inputs_hrv_current", emptyList(), 1),
            )
            val `hrv_7d`: LocalizedString get() = LocalizedString(
                key = StringKey("derivedMissingInputs.inputs.hrv_7d", "derived_missing_inputs_inputs_hrv_7d", emptyList(), 1),
            )
            val `hrv_14d_series`: LocalizedString get() = LocalizedString(
                key = StringKey("derivedMissingInputs.inputs.hrv_14d_series", "derived_missing_inputs_inputs_hrv_14d_series", emptyList(), 1),
            )
            val `hrv_baseline`: LocalizedString get() = LocalizedString(
                key = StringKey("derivedMissingInputs.inputs.hrv_baseline", "derived_missing_inputs_inputs_hrv_baseline", emptyList(), 1),
            )
            val `energy_spo2_current`: LocalizedString get() = LocalizedString(
                key = StringKey("derivedMissingInputs.inputs.energy_spo2_current", "derived_missing_inputs_inputs_energy_spo2_current", emptyList(), 1),
            )
            val `energy_stress_morning_or_latest`: LocalizedString get() = LocalizedString(
                key = StringKey("derivedMissingInputs.inputs.energy_stress_morning_or_latest", "derived_missing_inputs_inputs_energy_stress_morning_or_latest", emptyList(), 1),
            )
            val `energy_stress_today_mean`: LocalizedString get() = LocalizedString(
                key = StringKey("derivedMissingInputs.inputs.energy_stress_today_mean", "derived_missing_inputs_inputs_energy_stress_today_mean", emptyList(), 1),
            )
            val `hours_awake_estimate`: LocalizedString get() = LocalizedString(
                key = StringKey("derivedMissingInputs.inputs.hours_awake_estimate", "derived_missing_inputs_inputs_hours_awake_estimate", emptyList(), 1),
            )
            val `hours_awake`: LocalizedString get() = LocalizedString(
                key = StringKey("derivedMissingInputs.inputs.hours_awake", "derived_missing_inputs_inputs_hours_awake", emptyList(), 1),
            )
            val `body_hr_7d_mean`: LocalizedString get() = LocalizedString(
                key = StringKey("derivedMissingInputs.inputs.body_hr_7d_mean", "derived_missing_inputs_inputs_body_hr_7d_mean", emptyList(), 1),
            )
            val `body_hr_30d_mean`: LocalizedString get() = LocalizedString(
                key = StringKey("derivedMissingInputs.inputs.body_hr_30d_mean", "derived_missing_inputs_inputs_body_hr_30d_mean", emptyList(), 1),
            )
            val `body_hr_7d_max`: LocalizedString get() = LocalizedString(
                key = StringKey("derivedMissingInputs.inputs.body_hr_7d_max", "derived_missing_inputs_inputs_body_hr_7d_max", emptyList(), 1),
            )
            val `body_hrv_7d_mean`: LocalizedString get() = LocalizedString(
                key = StringKey("derivedMissingInputs.inputs.body_hrv_7d_mean", "derived_missing_inputs_inputs_body_hrv_7d_mean", emptyList(), 1),
            )
            val `body_hrv_30d_mean`: LocalizedString get() = LocalizedString(
                key = StringKey("derivedMissingInputs.inputs.body_hrv_30d_mean", "derived_missing_inputs_inputs_body_hrv_30d_mean", emptyList(), 1),
            )
            val `body_stress_7d_mean`: LocalizedString get() = LocalizedString(
                key = StringKey("derivedMissingInputs.inputs.body_stress_7d_mean", "derived_missing_inputs_inputs_body_stress_7d_mean", emptyList(), 1),
            )
            val `stress_peaks_24h`: LocalizedString get() = LocalizedString(
                key = StringKey("derivedMissingInputs.inputs.stress_peaks_24h", "derived_missing_inputs_inputs_stress_peaks_24h", emptyList(), 1),
            )
            val `body_spo2_7d`: LocalizedString get() = LocalizedString(
                key = StringKey("derivedMissingInputs.inputs.body_spo2_7d", "derived_missing_inputs_inputs_body_spo2_7d", emptyList(), 1),
            )
            val `height_cm`: LocalizedString get() = LocalizedString(
                key = StringKey("derivedMissingInputs.inputs.height_cm", "derived_missing_inputs_inputs_height_cm", emptyList(), 1),
            )
            val `female`: LocalizedString get() = LocalizedString(
                key = StringKey("derivedMissingInputs.inputs.female", "derived_missing_inputs_inputs_female", emptyList(), 1),
            )
            val `temp_delta_7d`: LocalizedString get() = LocalizedString(
                key = StringKey("derivedMissingInputs.inputs.temp_delta_7d", "derived_missing_inputs_inputs_temp_delta_7d", emptyList(), 1),
            )
            val `mood_hrv_current`: LocalizedString get() = LocalizedString(
                key = StringKey("derivedMissingInputs.inputs.mood_hrv_current", "derived_missing_inputs_inputs_mood_hrv_current", emptyList(), 1),
            )
            val `mood_hrv_14d`: LocalizedString get() = LocalizedString(
                key = StringKey("derivedMissingInputs.inputs.mood_hrv_14d", "derived_missing_inputs_inputs_mood_hrv_14d", emptyList(), 1),
            )
            val `mood_stress_current`: LocalizedString get() = LocalizedString(
                key = StringKey("derivedMissingInputs.inputs.mood_stress_current", "derived_missing_inputs_inputs_mood_stress_current", emptyList(), 1),
            )
            val `mood_sleep_minutes`: LocalizedString get() = LocalizedString(
                key = StringKey("derivedMissingInputs.inputs.mood_sleep_minutes", "derived_missing_inputs_inputs_mood_sleep_minutes", emptyList(), 1),
            )
            val `mood_steps_14d`: LocalizedString get() = LocalizedString(
                key = StringKey("derivedMissingInputs.inputs.mood_steps_14d", "derived_missing_inputs_inputs_mood_steps_14d", emptyList(), 1),
            )
            val `sleep_need_minutes`: LocalizedString get() = LocalizedString(
                key = StringKey("derivedMissingInputs.inputs.sleep_need_minutes", "derived_missing_inputs_inputs_sleep_need_minutes", emptyList(), 1),
            )
            val `breath_current`: LocalizedString get() = LocalizedString(
                key = StringKey("derivedMissingInputs.inputs.breath_current", "derived_missing_inputs_inputs_breath_current", emptyList(), 1),
            )
            val `breath_14d`: LocalizedString get() = LocalizedString(
                key = StringKey("derivedMissingInputs.inputs.breath_14d", "derived_missing_inputs_inputs_breath_14d", emptyList(), 1),
            )
            val `breath_today`: LocalizedString get() = LocalizedString(
                key = StringKey("derivedMissingInputs.inputs.breath_today", "derived_missing_inputs_inputs_breath_today", emptyList(), 1),
            )
            val `spo2_today`: LocalizedString get() = LocalizedString(
                key = StringKey("derivedMissingInputs.inputs.spo2_today", "derived_missing_inputs_inputs_spo2_today", emptyList(), 1),
            )
            val `spo2_instant`: LocalizedString get() = LocalizedString(
                key = StringKey("derivedMissingInputs.inputs.spo2_instant", "derived_missing_inputs_inputs_spo2_instant", emptyList(), 1),
            )
            val `spo2_7d_median`: LocalizedString get() = LocalizedString(
                key = StringKey("derivedMissingInputs.inputs.spo2_7d_median", "derived_missing_inputs_inputs_spo2_7d_median", emptyList(), 1),
            )
            val `ide_rhr`: LocalizedString get() = LocalizedString(
                key = StringKey("derivedMissingInputs.inputs.ide_rhr", "derived_missing_inputs_inputs_ide_rhr", emptyList(), 1),
            )
            val `ide_hrv_current`: LocalizedString get() = LocalizedString(
                key = StringKey("derivedMissingInputs.inputs.ide_hrv_current", "derived_missing_inputs_inputs_ide_hrv_current", emptyList(), 1),
            )
            val `ide_hrv_14d`: LocalizedString get() = LocalizedString(
                key = StringKey("derivedMissingInputs.inputs.ide_hrv_14d", "derived_missing_inputs_inputs_ide_hrv_14d", emptyList(), 1),
            )
            val `ide_weight_kg`: LocalizedString get() = LocalizedString(
                key = StringKey("derivedMissingInputs.inputs.ide_weight_kg", "derived_missing_inputs_inputs_ide_weight_kg", emptyList(), 1),
            )
            val `local_hour`: LocalizedString get() = LocalizedString(
                key = StringKey("derivedMissingInputs.inputs.local_hour", "derived_missing_inputs_inputs_local_hour", emptyList(), 1),
            )
            val `sleep_segments`: LocalizedString get() = LocalizedString(
                key = StringKey("derivedMissingInputs.inputs.sleep_segments", "derived_missing_inputs_inputs_sleep_segments", emptyList(), 1),
            )
            val `sleep_debt_durations`: LocalizedString get() = LocalizedString(
                key = StringKey("derivedMissingInputs.inputs.sleep_debt_durations", "derived_missing_inputs_inputs_sleep_debt_durations", emptyList(), 1),
            )
            val `ecg_waveform`: LocalizedString get() = LocalizedString(
                key = StringKey("derivedMissingInputs.inputs.ecg_waveform", "derived_missing_inputs_inputs_ecg_waveform", emptyList(), 1),
            )
            val `sdk_health_norm`: LocalizedString get() = LocalizedString(
                key = StringKey("derivedMissingInputs.inputs.sdk_health_norm", "derived_missing_inputs_inputs_sdk_health_norm", emptyList(), 1),
            )
            val `soul-home-sleep`: LocalizedString get() = LocalizedString(
                key = StringKey("derivedMissingInputs.inputs.soul-home-sleep", "derived_missing_inputs_inputs_soul_home_sleep", emptyList(), 1),
            )
            val `soul-sleep-screen-score`: LocalizedString get() = LocalizedString(
                key = StringKey("derivedMissingInputs.inputs.soul-sleep-screen-score", "derived_missing_inputs_inputs_soul_sleep_screen_score", emptyList(), 1),
            )
            val `soul-energy-sleep`: LocalizedString get() = LocalizedString(
                key = StringKey("derivedMissingInputs.inputs.soul-energy-sleep", "derived_missing_inputs_inputs_soul_energy_sleep", emptyList(), 1),
            )
            val `soul-ide-sleep`: LocalizedString get() = LocalizedString(
                key = StringKey("derivedMissingInputs.inputs.soul-ide-sleep", "derived_missing_inputs_inputs_soul_ide_sleep", emptyList(), 1),
            )
            val `soul-body-sleep`: LocalizedString get() = LocalizedString(
                key = StringKey("derivedMissingInputs.inputs.soul-body-sleep", "derived_missing_inputs_inputs_soul_body_sleep", emptyList(), 1),
            )
            val `soul-rhr-energy`: LocalizedString get() = LocalizedString(
                key = StringKey("derivedMissingInputs.inputs.soul-rhr-energy", "derived_missing_inputs_inputs_soul_rhr_energy", emptyList(), 1),
            )
            val `soul-rhr-night`: LocalizedString get() = LocalizedString(
                key = StringKey("derivedMissingInputs.inputs.soul-rhr-night", "derived_missing_inputs_inputs_soul_rhr_night", emptyList(), 1),
            )
            val `soul-night-recovery`: LocalizedString get() = LocalizedString(
                key = StringKey("derivedMissingInputs.inputs.soul-night-recovery", "derived_missing_inputs_inputs_soul_night_recovery", emptyList(), 1),
            )
            val `soul-energy-readiness`: LocalizedString get() = LocalizedString(
                key = StringKey("derivedMissingInputs.inputs.soul-energy-readiness", "derived_missing_inputs_inputs_soul_energy_readiness", emptyList(), 1),
            )
            val `soul-energy-recovery`: LocalizedString get() = LocalizedString(
                key = StringKey("derivedMissingInputs.inputs.soul-energy-recovery", "derived_missing_inputs_inputs_soul_energy_recovery", emptyList(), 1),
            )
            val `soul-energy-pns`: LocalizedString get() = LocalizedString(
                key = StringKey("derivedMissingInputs.inputs.soul-energy-pns", "derived_missing_inputs_inputs_soul_energy_pns", emptyList(), 1),
            )
            val `soul-body-cardio`: LocalizedString get() = LocalizedString(
                key = StringKey("derivedMissingInputs.inputs.soul-body-cardio", "derived_missing_inputs_inputs_soul_body_cardio", emptyList(), 1),
            )
            val `soul-body-recovery`: LocalizedString get() = LocalizedString(
                key = StringKey("derivedMissingInputs.inputs.soul-body-recovery", "derived_missing_inputs_inputs_soul_body_recovery", emptyList(), 1),
            )
            val `soul-body-activity`: LocalizedString get() = LocalizedString(
                key = StringKey("derivedMissingInputs.inputs.soul-body-activity", "derived_missing_inputs_inputs_soul_body_activity", emptyList(), 1),
            )
            val `soul-body-stress`: LocalizedString get() = LocalizedString(
                key = StringKey("derivedMissingInputs.inputs.soul-body-stress", "derived_missing_inputs_inputs_soul_body_stress", emptyList(), 1),
            )
            val `soul-body-respiratory`: LocalizedString get() = LocalizedString(
                key = StringKey("derivedMissingInputs.inputs.soul-body-respiratory", "derived_missing_inputs_inputs_soul_body_respiratory", emptyList(), 1),
            )
            val `soul-body-metabolism`: LocalizedString get() = LocalizedString(
                key = StringKey("derivedMissingInputs.inputs.soul-body-metabolism", "derived_missing_inputs_inputs_soul_body_metabolism", emptyList(), 1),
            )
        }
        object `Conditions` {
            val `actual_measurements`: LocalizedString get() = LocalizedString(
                key = StringKey("derivedMissingInputs.conditions.actual_measurements", "derived_missing_inputs_conditions_actual_measurements", emptyList(), 1),
            )
            val `formula_evidence`: LocalizedString get() = LocalizedString(
                key = StringKey("derivedMissingInputs.conditions.formula_evidence", "derived_missing_inputs_conditions_formula_evidence", emptyList(), 1),
            )
            val `night_hr_four_samples`: LocalizedString get() = LocalizedString(
                key = StringKey("derivedMissingInputs.conditions.night_hr_four_samples", "derived_missing_inputs_conditions_night_hr_four_samples", emptyList(), 1),
            )
            val `night_hrv_three_samples`: LocalizedString get() = LocalizedString(
                key = StringKey("derivedMissingInputs.conditions.night_hrv_three_samples", "derived_missing_inputs_conditions_night_hrv_three_samples", emptyList(), 1),
            )
            val `sleep_debt_three_weekly_rows`: LocalizedString get() = LocalizedString(
                key = StringKey("derivedMissingInputs.conditions.sleep_debt_three_weekly_rows", "derived_missing_inputs_conditions_sleep_debt_three_weekly_rows", emptyList(), 1),
            )
            val `sleep_debt_positive_integer_source_durations`: LocalizedString get() = LocalizedString(
                key = StringKey("derivedMissingInputs.conditions.sleep_debt_positive_integer_source_durations", "derived_missing_inputs_conditions_sleep_debt_positive_integer_source_durations", emptyList(), 1),
            )
            val `sleep_goal_minutes_integer_source_goal`: LocalizedString get() = LocalizedString(
                key = StringKey("derivedMissingInputs.conditions.sleep_goal_minutes_integer_source_goal", "derived_missing_inputs_conditions_sleep_goal_minutes_integer_source_goal", emptyList(), 1),
            )
            val `instant_evaluation`: LocalizedString get() = LocalizedString(
                key = StringKey("derivedMissingInputs.conditions.instant_evaluation", "derived_missing_inputs_conditions_instant_evaluation", emptyList(), 1),
            )
            val `conditions_not_supplied_by_soul_ring_path`: LocalizedString get() = LocalizedString(
                key = StringKey("derivedMissingInputs.conditions.conditions_not_supplied_by_soul_ring_path", "derived_missing_inputs_conditions_conditions_not_supplied_by_soul_ring_path", emptyList(), 1),
            )
        }
        object `SyntheticReasons` {
            val `referenceDefault`: LocalizedString get() = LocalizedString(
                key = StringKey("derivedMissingInputs.syntheticReasons.referenceDefault", "derived_missing_inputs_synthetic_reasons_reference_default", emptyList(), 1),
            )
            val `ageReference`: LocalizedString get() = LocalizedString(
                key = StringKey("derivedMissingInputs.syntheticReasons.ageReference", "derived_missing_inputs_synthetic_reasons_age_reference", emptyList(), 1),
            )
            val `unknown`: LocalizedString get() = LocalizedString(
                key = StringKey("derivedMissingInputs.syntheticReasons.unknown", "derived_missing_inputs_synthetic_reasons_unknown", emptyList(), 1),
            )
        }
    }
    object `DerivedScores` {
        val `title`: LocalizedString get() = LocalizedString(
            key = StringKey("derivedScores.title", "derived_scores_title", emptyList(), 1),
        )
        val `intro`: LocalizedString get() = LocalizedString(
            key = StringKey("derivedScores.intro", "derived_scores_intro", emptyList(), 1),
        )
        val `refreshPending`: LocalizedString get() = LocalizedString(
            key = StringKey("derivedScores.refreshPending", "derived_scores_refresh_pending", emptyList(), 1),
        )
        fun `refreshCounts`(`pending`: String, `failed`: String): LocalizedString = LocalizedString(
            key = StringKey("derivedScores.refreshCounts", "derived_scores_refresh_counts", listOf("pending", "failed"), 1),
            arguments = listOf(`pending`, `failed`),
        )
        fun `ownerRefreshCounts`(`pending`: String, `failed`: String): LocalizedString = LocalizedString(
            key = StringKey("derivedScores.ownerRefreshCounts", "derived_scores_owner_refresh_counts", listOf("pending", "failed"), 1),
            arguments = listOf(`pending`, `failed`),
        )
        val `empty`: LocalizedString get() = LocalizedString(
            key = StringKey("derivedScores.empty", "derived_scores_empty", emptyList(), 1),
        )
        fun `loadedCount`(`loaded`: String, `total`: String): LocalizedString = LocalizedString(
            key = StringKey("derivedScores.loadedCount", "derived_scores_loaded_count", listOf("loaded", "total"), 1),
            arguments = listOf(`loaded`, `total`),
        )
        object `Status` {
            val `available`: LocalizedString get() = LocalizedString(
                key = StringKey("derivedScores.status.available", "derived_scores_status_available", emptyList(), 1),
            )
            val `partial`: LocalizedString get() = LocalizedString(
                key = StringKey("derivedScores.status.partial", "derived_scores_status_partial", emptyList(), 1),
            )
            val `unavailable`: LocalizedString get() = LocalizedString(
                key = StringKey("derivedScores.status.unavailable", "derived_scores_status_unavailable", emptyList(), 1),
            )
            val `unqualified`: LocalizedString get() = LocalizedString(
                key = StringKey("derivedScores.status.unqualified", "derived_scores_status_unqualified", emptyList(), 1),
            )
        }
        fun `savedCalculation`(`version`: String, `when`: String): LocalizedString = LocalizedString(
            key = StringKey("derivedScores.savedCalculation", "derived_scores_saved_calculation", listOf("version", "when"), 1),
            arguments = listOf(`version`, `when`),
        )
        val `ownerContext`: LocalizedString get() = LocalizedString(
            key = StringKey("derivedScores.ownerContext", "derived_scores_owner_context", emptyList(), 1),
        )
        fun `instantCount`(`count`: String): LocalizedString = LocalizedString(
            key = StringKey("derivedScores.instantCount", "derived_scores_instant_count", listOf("count"), 1),
            arguments = listOf(`count`),
        )
        val `hideVersions`: LocalizedString get() = LocalizedString(
            key = StringKey("derivedScores.hideVersions", "derived_scores_hide_versions", emptyList(), 1),
        )
        val `allPointsVersions`: LocalizedString get() = LocalizedString(
            key = StringKey("derivedScores.allPointsVersions", "derived_scores_all_points_versions", emptyList(), 1),
        )
        val `versions`: LocalizedString get() = LocalizedString(
            key = StringKey("derivedScores.versions", "derived_scores_versions", emptyList(), 1),
        )
        val `changed`: LocalizedString get() = LocalizedString(
            key = StringKey("derivedScores.changed", "derived_scores_changed", emptyList(), 1),
        )
        val `loadMore`: LocalizedString get() = LocalizedString(
            key = StringKey("derivedScores.loadMore", "derived_scores_load_more", emptyList(), 1),
        )
        val `hideArchive`: LocalizedString get() = LocalizedString(
            key = StringKey("derivedScores.hideArchive", "derived_scores_hide_archive", emptyList(), 1),
        )
        val `allVersions`: LocalizedString get() = LocalizedString(
            key = StringKey("derivedScores.allVersions", "derived_scores_all_versions", emptyList(), 1),
        )
        val `refresh`: LocalizedString get() = LocalizedString(
            key = StringKey("derivedScores.refresh", "derived_scores_refresh", emptyList(), 1),
        )
    }
    object `DisplayUnits` {
        val `title`: LocalizedString get() = LocalizedString(
            key = StringKey("displayUnits.title", "display_units_title", emptyList(), 1),
        )
        val `metric`: LocalizedString get() = LocalizedString(
            key = StringKey("displayUnits.metric", "display_units_metric", emptyList(), 1),
        )
        val `imperial`: LocalizedString get() = LocalizedString(
            key = StringKey("displayUnits.imperial", "display_units_imperial", emptyList(), 1),
        )
        val `metricSummary`: LocalizedString get() = LocalizedString(
            key = StringKey("displayUnits.metricSummary", "display_units_metric_summary", emptyList(), 1),
        )
        val `imperialSummary`: LocalizedString get() = LocalizedString(
            key = StringKey("displayUnits.imperialSummary", "display_units_imperial_summary", emptyList(), 1),
        )
        val `hint`: LocalizedString get() = LocalizedString(
            key = StringKey("displayUnits.hint", "display_units_hint", emptyList(), 1),
        )
    }
    object `MetricDetails` {
        val `history`: LocalizedString get() = LocalizedString(
            key = StringKey("metricDetails.history", "metric_details_history", emptyList(), 1),
        )
        val `records`: LocalizedString get() = LocalizedString(
            key = StringKey("metricDetails.records", "metric_details_records", emptyList(), 1),
        )
        val `originalRecords`: LocalizedString get() = LocalizedString(
            key = StringKey("metricDetails.originalRecords", "metric_details_original_records", emptyList(), 1),
        )
        val `noValue`: LocalizedString get() = LocalizedString(
            key = StringKey("metricDetails.noValue", "metric_details_no_value", emptyList(), 1),
        )
        val `noRecords`: LocalizedString get() = LocalizedString(
            key = StringKey("metricDetails.noRecords", "metric_details_no_records", emptyList(), 1),
        )
        val `excluded`: LocalizedString get() = LocalizedString(
            key = StringKey("metricDetails.excluded", "metric_details_excluded", emptyList(), 1),
        )
        val `correct`: LocalizedString get() = LocalizedString(
            key = StringKey("metricDetails.correct", "metric_details_correct", emptyList(), 1),
        )
        object `Origin` {
            val `corrected`: LocalizedString get() = LocalizedString(
                key = StringKey("metricDetails.origin.corrected", "metric_details_origin_corrected", emptyList(), 1),
            )
            val `estimate`: LocalizedString get() = LocalizedString(
                key = StringKey("metricDetails.origin.estimate", "metric_details_origin_estimate", emptyList(), 1),
            )
            val `manual`: LocalizedString get() = LocalizedString(
                key = StringKey("metricDetails.origin.manual", "metric_details_origin_manual", emptyList(), 1),
            )
            val `device`: LocalizedString get() = LocalizedString(
                key = StringKey("metricDetails.origin.device", "metric_details_origin_device", emptyList(), 1),
            )
        }
    }
    object `PersonalForms` {
        val `saving`: LocalizedString get() = LocalizedString(
            key = StringKey("personalForms.saving", "personal_forms_saving", emptyList(), 1),
        )
        val `draftKept`: LocalizedString get() = LocalizedString(
            key = StringKey("personalForms.draftKept", "personal_forms_draft_kept", emptyList(), 1),
        )
        val `heightInputHint`: LocalizedString get() = LocalizedString(
            key = StringKey("personalForms.heightInputHint", "personal_forms_height_input_hint", emptyList(), 1),
        )
    }
    object `PersonalHistory` {
        val `scores`: LocalizedString get() = LocalizedString(
            key = StringKey("personalHistory.scores", "personal_history_scores", emptyList(), 1),
        )
        val `runningSteps`: LocalizedString get() = LocalizedString(
            key = StringKey("personalHistory.runningSteps", "personal_history_running_steps", emptyList(), 1),
        )
        val `workoutReports`: LocalizedString get() = LocalizedString(
            key = StringKey("personalHistory.workoutReports", "personal_history_workout_reports", emptyList(), 1),
        )
        val `runningReports`: LocalizedString get() = LocalizedString(
            key = StringKey("personalHistory.runningReports", "personal_history_running_reports", emptyList(), 1),
        )
        object `Versions` {
            val `manual`: LocalizedString get() = LocalizedString(
                key = StringKey("personalHistory.versions.manual", "personal_history_versions_manual", emptyList(), 1),
            )
            val `unnamedSource`: LocalizedString get() = LocalizedString(
                key = StringKey("personalHistory.versions.unnamedSource", "personal_history_versions_unnamed_source", emptyList(), 1),
            )
            val `current`: LocalizedString get() = LocalizedString(
                key = StringKey("personalHistory.versions.current", "personal_history_versions_current", emptyList(), 1),
            )
            val `previous`: LocalizedString get() = LocalizedString(
                key = StringKey("personalHistory.versions.previous", "personal_history_versions_previous", emptyList(), 1),
            )
            val `savedAtUnknown`: LocalizedString get() = LocalizedString(
                key = StringKey("personalHistory.versions.savedAtUnknown", "personal_history_versions_saved_at_unknown", emptyList(), 1),
            )
            val `receiptUnknown`: LocalizedString get() = LocalizedString(
                key = StringKey("personalHistory.versions.receiptUnknown", "personal_history_versions_receipt_unknown", emptyList(), 1),
            )
            fun `savedAt`(`when`: String): LocalizedString = LocalizedString(
                key = StringKey("personalHistory.versions.savedAt", "personal_history_versions_saved_at", listOf("when"), 1),
                arguments = listOf(`when`),
            )
            val `title`: LocalizedString get() = LocalizedString(
                key = StringKey("personalHistory.versions.title", "personal_history_versions_title", emptyList(), 1),
            )
            val `explanation`: LocalizedString get() = LocalizedString(
                key = StringKey("personalHistory.versions.explanation", "personal_history_versions_explanation", emptyList(), 1),
            )
            val `excluded`: LocalizedString get() = LocalizedString(
                key = StringKey("personalHistory.versions.excluded", "personal_history_versions_excluded", emptyList(), 1),
            )
            val `corrected`: LocalizedString get() = LocalizedString(
                key = StringKey("personalHistory.versions.corrected", "personal_history_versions_corrected", emptyList(), 1),
            )
            val `changed`: LocalizedString get() = LocalizedString(
                key = StringKey("personalHistory.versions.changed", "personal_history_versions_changed", emptyList(), 1),
            )
            val `reload`: LocalizedString get() = LocalizedString(
                key = StringKey("personalHistory.versions.reload", "personal_history_versions_reload", emptyList(), 1),
            )
            val `empty`: LocalizedString get() = LocalizedString(
                key = StringKey("personalHistory.versions.empty", "personal_history_versions_empty", emptyList(), 1),
            )
            fun `count`(`count`: String): LocalizedString = LocalizedString(
                key = StringKey("personalHistory.versions.count", "personal_history_versions_count", listOf("count"), 1),
                arguments = listOf(`count`),
            )
            val `annotations`: LocalizedString get() = LocalizedString(
                key = StringKey("personalHistory.versions.annotations", "personal_history_versions_annotations", emptyList(), 1),
            )
            fun `range`(`value1`: String, `value2`: String): LocalizedString = LocalizedString(
                key = StringKey("personalHistory.versions.range", "personal_history_versions_range", listOf("value1", "value2"), 1),
                arguments = listOf(`value1`, `value2`),
            )
            val `fullSnapshot`: LocalizedString get() = LocalizedString(
                key = StringKey("personalHistory.versions.fullSnapshot", "personal_history_versions_full_snapshot", emptyList(), 1),
            )
            fun `receipt`(`revision`: String, `value2`: String): LocalizedString = LocalizedString(
                key = StringKey("personalHistory.versions.receipt", "personal_history_versions_receipt", listOf("revision", "value2"), 1),
                arguments = listOf(`revision`, `value2`),
            )
            val `more`: LocalizedString get() = LocalizedString(
                key = StringKey("personalHistory.versions.more", "personal_history_versions_more", emptyList(), 1),
            )
        }
        object `Raw` {
            val `temperatureTitle`: LocalizedString get() = LocalizedString(
                key = StringKey("personalHistory.raw.temperatureTitle", "personal_history_raw_temperature_title", emptyList(), 1),
            )
            val `workoutTitle`: LocalizedString get() = LocalizedString(
                key = StringKey("personalHistory.raw.workoutTitle", "personal_history_raw_workout_title", emptyList(), 1),
            )
            val `temperatureSource`: LocalizedString get() = LocalizedString(
                key = StringKey("personalHistory.raw.temperatureSource", "personal_history_raw_temperature_source", emptyList(), 1),
            )
            val `workoutSource`: LocalizedString get() = LocalizedString(
                key = StringKey("personalHistory.raw.workoutSource", "personal_history_raw_workout_source", emptyList(), 1),
            )
            val `ringDate`: LocalizedString get() = LocalizedString(
                key = StringKey("personalHistory.raw.ringDate", "personal_history_raw_ring_date", emptyList(), 1),
            )
            val `phoneDate`: LocalizedString get() = LocalizedString(
                key = StringKey("personalHistory.raw.phoneDate", "personal_history_raw_phone_date", emptyList(), 1),
            )
            val `ambiguousLayout`: LocalizedString get() = LocalizedString(
                key = StringKey("personalHistory.raw.ambiguousLayout", "personal_history_raw_ambiguous_layout", emptyList(), 1),
            )
            val `unconfirmedUnits`: LocalizedString get() = LocalizedString(
                key = StringKey("personalHistory.raw.unconfirmedUnits", "personal_history_raw_unconfirmed_units", emptyList(), 1),
            )
            val `soul1230`: LocalizedString get() = LocalizedString(
                key = StringKey("personalHistory.raw.soul1230", "personal_history_raw_soul1230", emptyList(), 1),
            )
            val `soul1214`: LocalizedString get() = LocalizedString(
                key = StringKey("personalHistory.raw.soul1214", "personal_history_raw_soul1214", emptyList(), 1),
            )
            val `projectionUnavailable`: LocalizedString get() = LocalizedString(
                key = StringKey("personalHistory.raw.projectionUnavailable", "personal_history_raw_projection_unavailable", emptyList(), 1),
            )
            val `requestMidnight`: LocalizedString get() = LocalizedString(
                key = StringKey("personalHistory.raw.requestMidnight", "personal_history_raw_request_midnight", emptyList(), 1),
            )
            val `receiptMidnight`: LocalizedString get() = LocalizedString(
                key = StringKey("personalHistory.raw.receiptMidnight", "personal_history_raw_receipt_midnight", emptyList(), 1),
            )
            val `temperatureExplanation`: LocalizedString get() = LocalizedString(
                key = StringKey("personalHistory.raw.temperatureExplanation", "personal_history_raw_temperature_explanation", emptyList(), 1),
            )
            val `workoutExplanation`: LocalizedString get() = LocalizedString(
                key = StringKey("personalHistory.raw.workoutExplanation", "personal_history_raw_workout_explanation", emptyList(), 1),
            )
            fun `count`(`count`: String): LocalizedString = LocalizedString(
                key = StringKey("personalHistory.raw.count", "personal_history_raw_count", listOf("count"), 1),
                arguments = listOf(`count`),
            )
            val `allSources`: LocalizedString get() = LocalizedString(
                key = StringKey("personalHistory.raw.allSources", "personal_history_raw_all_sources", emptyList(), 1),
            )
            fun `received`(`value1`: String, `local_date`: String, `timezone`: String, `value4`: String): LocalizedString = LocalizedString(
                key = StringKey("personalHistory.raw.received", "personal_history_raw_received", listOf("value1", "local_date", "timezone", "value4"), 1),
                arguments = listOf(`value1`, `local_date`, `timezone`, `value4`),
            )
            fun `format`(`layout`: String, `qualification`: String, `value3`: String): LocalizedString = LocalizedString(
                key = StringKey("personalHistory.raw.format", "personal_history_raw_format", listOf("layout", "qualification", "value3"), 1),
                arguments = listOf(`layout`, `qualification`, `value3`),
            )
            fun `dateBasis`(`time_basis`: String): LocalizedString = LocalizedString(
                key = StringKey("personalHistory.raw.dateBasis", "personal_history_raw_date_basis", listOf("time_basis"), 1),
                arguments = listOf(`time_basis`),
            )
            val `interpretationWarning`: LocalizedString get() = LocalizedString(
                key = StringKey("personalHistory.raw.interpretationWarning", "personal_history_raw_interpretation_warning", emptyList(), 1),
            )
            val `invalidTimeContext`: LocalizedString get() = LocalizedString(
                key = StringKey("personalHistory.raw.invalidTimeContext", "personal_history_raw_invalid_time_context", emptyList(), 1),
            )
            val `missingTimeContext`: LocalizedString get() = LocalizedString(
                key = StringKey("personalHistory.raw.missingTimeContext", "personal_history_raw_missing_time_context", emptyList(), 1),
            )
            fun `timeContext`(`capture_local_date`: String, `day_start`: String, `timezone`: String, `sync_started_at`: String, `parsed_at`: String, `parse_local_date`: String, `ring_local_date`: String): LocalizedString = LocalizedString(
                key = StringKey("personalHistory.raw.timeContext", "personal_history_raw_time_context", listOf("capture_local_date", "day_start", "timezone", "sync_started_at", "parsed_at", "parse_local_date", "ring_local_date"), 1),
                arguments = listOf(`capture_local_date`, `day_start`, `timezone`, `sync_started_at`, `parsed_at`, `parse_local_date`, `ring_local_date`),
            )
            fun `legacyTimeContext`(`capture_local_date`: String, `day_start`: String, `ring_local_date`: String): LocalizedString = LocalizedString(
                key = StringKey("personalHistory.raw.legacyTimeContext", "personal_history_raw_legacy_time_context", listOf("capture_local_date", "day_start", "ring_local_date"), 1),
                arguments = listOf(`capture_local_date`, `day_start`, `ring_local_date`),
            )
            fun `interval`(`interval_minutes`: String, `as_of`: String): LocalizedString = LocalizedString(
                key = StringKey("personalHistory.raw.interval", "personal_history_raw_interval", listOf("interval_minutes", "as_of"), 1),
                arguments = listOf(`interval_minutes`, `as_of`),
            )
            val `emptyInterpretation`: LocalizedString get() = LocalizedString(
                key = StringKey("personalHistory.raw.emptyInterpretation", "personal_history_raw_empty_interpretation", emptyList(), 1),
            )
            fun `offset`(`offset_minutes`: String, `value2`: String, `value3`: String, `unit`: String): LocalizedString = LocalizedString(
                key = StringKey("personalHistory.raw.offset", "personal_history_raw_offset", listOf("offset_minutes", "value2", "value3", "unit"), 1),
                arguments = listOf(`offset_minutes`, `value2`, `value3`, `unit`),
            )
            val `legacyProjection`: LocalizedString get() = LocalizedString(
                key = StringKey("personalHistory.raw.legacyProjection", "personal_history_raw_legacy_projection", emptyList(), 1),
            )
            fun `legacyProjectionContext`(`formula_version`: String, `day_start`: String, `as_of`: String): LocalizedString = LocalizedString(
                key = StringKey("personalHistory.raw.legacyProjectionContext", "personal_history_raw_legacy_projection_context", listOf("formula_version", "day_start", "as_of"), 1),
                arguments = listOf(`formula_version`, `day_start`, `as_of`),
            )
            val `conversionBasis`: LocalizedString get() = LocalizedString(
                key = StringKey("personalHistory.raw.conversionBasis", "personal_history_raw_conversion_basis", emptyList(), 1),
            )
            val `packet`: LocalizedString get() = LocalizedString(
                key = StringKey("personalHistory.raw.packet", "personal_history_raw_packet", emptyList(), 1),
            )
            val `changed`: LocalizedString get() = LocalizedString(
                key = StringKey("personalHistory.raw.changed", "personal_history_raw_changed", emptyList(), 1),
            )
            val `reload`: LocalizedString get() = LocalizedString(
                key = StringKey("personalHistory.raw.reload", "personal_history_raw_reload", emptyList(), 1),
            )
            val `more`: LocalizedString get() = LocalizedString(
                key = StringKey("personalHistory.raw.more", "personal_history_raw_more", emptyList(), 1),
            )
        }
        object `Derived` {
            val `openSavedDefinition`: LocalizedString get() = LocalizedString(
                key = StringKey("personalHistory.derived.openSavedDefinition", "personal_history_derived_open_saved_definition", emptyList(), 1),
            )
            val `sourceUnspecified`: LocalizedString get() = LocalizedString(
                key = StringKey("personalHistory.derived.sourceUnspecified", "personal_history_derived_source_unspecified", emptyList(), 1),
            )
            val `ownerContext`: LocalizedString get() = LocalizedString(
                key = StringKey("personalHistory.derived.ownerContext", "personal_history_derived_owner_context", emptyList(), 1),
            )
            val `noData`: LocalizedString get() = LocalizedString(
                key = StringKey("personalHistory.derived.noData", "personal_history_derived_no_data", emptyList(), 1),
            )
            val `hideSaved`: LocalizedString get() = LocalizedString(
                key = StringKey("personalHistory.derived.hideSaved", "personal_history_derived_hide_saved", emptyList(), 1),
            )
            val `openSaved`: LocalizedString get() = LocalizedString(
                key = StringKey("personalHistory.derived.openSaved", "personal_history_derived_open_saved", emptyList(), 1),
            )
            val `moreContextPages`: LocalizedString get() = LocalizedString(
                key = StringKey("personalHistory.derived.moreContextPages", "personal_history_derived_more_context_pages", emptyList(), 1),
            )
            val `noContextVersions`: LocalizedString get() = LocalizedString(
                key = StringKey("personalHistory.derived.noContextVersions", "personal_history_derived_no_context_versions", emptyList(), 1),
            )
            val `currentContextVersion`: LocalizedString get() = LocalizedString(
                key = StringKey("personalHistory.derived.currentContextVersion", "personal_history_derived_current_context_version", emptyList(), 1),
            )
            val `previousContextVersion`: LocalizedString get() = LocalizedString(
                key = StringKey("personalHistory.derived.previousContextVersion", "personal_history_derived_previous_context_version", emptyList(), 1),
            )
            val `ownerAllSources`: LocalizedString get() = LocalizedString(
                key = StringKey("personalHistory.derived.ownerAllSources", "personal_history_derived_owner_all_sources", emptyList(), 1),
            )
            fun `source`(`source`: String): LocalizedString = LocalizedString(
                key = StringKey("personalHistory.derived.source", "personal_history_derived_source", listOf("source"), 1),
                arguments = listOf(`source`),
            )
            val `versionSource`: LocalizedString get() = LocalizedString(
                key = StringKey("personalHistory.derived.versionSource", "personal_history_derived_version_source", emptyList(), 1),
            )
            val `title`: LocalizedString get() = LocalizedString(
                key = StringKey("personalHistory.derived.title", "personal_history_derived_title", emptyList(), 1),
            )
            val `definition`: LocalizedString get() = LocalizedString(
                key = StringKey("personalHistory.derived.definition", "personal_history_derived_definition", emptyList(), 1),
            )
            val `requiredInputs`: LocalizedString get() = LocalizedString(
                key = StringKey("personalHistory.derived.requiredInputs", "personal_history_derived_required_inputs", emptyList(), 1),
            )
            val `asOf`: LocalizedString get() = LocalizedString(
                key = StringKey("personalHistory.derived.asOf", "personal_history_derived_as_of", emptyList(), 1),
            )
            val `coverage`: LocalizedString get() = LocalizedString(
                key = StringKey("personalHistory.derived.coverage", "personal_history_derived_coverage", emptyList(), 1),
            )
            val `availability`: LocalizedString get() = LocalizedString(
                key = StringKey("personalHistory.derived.availability", "personal_history_derived_availability", emptyList(), 1),
            )
            val `baseline`: LocalizedString get() = LocalizedString(
                key = StringKey("personalHistory.derived.baseline", "personal_history_derived_baseline", emptyList(), 1),
            )
            fun `baselineSource`(`as_of`: String, `value2`: String): LocalizedString = LocalizedString(
                key = StringKey("personalHistory.derived.baselineSource", "personal_history_derived_baseline_source", listOf("as_of", "value2"), 1),
                arguments = listOf(`as_of`, `value2`),
            )
            fun `points`(`points`: String): LocalizedString = LocalizedString(
                key = StringKey("personalHistory.derived.points", "personal_history_derived_points", listOf("points"), 1),
                arguments = listOf(`points`),
            )
            fun `estimator`(`estimator`: String): LocalizedString = LocalizedString(
                key = StringKey("personalHistory.derived.estimator", "personal_history_derived_estimator", listOf("estimator"), 1),
                arguments = listOf(`estimator`),
            )
            val `baselineEvidence`: LocalizedString get() = LocalizedString(
                key = StringKey("personalHistory.derived.baselineEvidence", "personal_history_derived_baseline_evidence", emptyList(), 1),
            )
            val `coverageExplanation`: LocalizedString get() = LocalizedString(
                key = StringKey("personalHistory.derived.coverageExplanation", "personal_history_derived_coverage_explanation", emptyList(), 1),
            )
            fun `synthetic`(`value1`: String): LocalizedString = LocalizedString(
                key = StringKey("personalHistory.derived.synthetic", "personal_history_derived_synthetic", listOf("value1"), 1),
                arguments = listOf(`value1`),
            )
            val `componentCode`: LocalizedString get() = LocalizedString(
                key = StringKey("personalHistory.derived.componentCode", "personal_history_derived_component_code", emptyList(), 1),
            )
            val `reason`: LocalizedString get() = LocalizedString(
                key = StringKey("personalHistory.derived.reason", "personal_history_derived_reason", emptyList(), 1),
            )
            val `componentSeries`: LocalizedString get() = LocalizedString(
                key = StringKey("personalHistory.derived.componentSeries", "personal_history_derived_component_series", emptyList(), 1),
            )
            val `inputVersions`: LocalizedString get() = LocalizedString(
                key = StringKey("personalHistory.derived.inputVersions", "personal_history_derived_input_versions", emptyList(), 1),
            )
            val `inputWindows`: LocalizedString get() = LocalizedString(
                key = StringKey("personalHistory.derived.inputWindows", "personal_history_derived_input_windows", emptyList(), 1),
            )
            val `definitionEvidence`: LocalizedString get() = LocalizedString(
                key = StringKey("personalHistory.derived.definitionEvidence", "personal_history_derived_definition_evidence", emptyList(), 1),
            )
            fun `immutableResult`(`version`: String, `result_id`: String): LocalizedString = LocalizedString(
                key = StringKey("personalHistory.derived.immutableResult", "personal_history_derived_immutable_result", listOf("version", "result_id"), 1),
                arguments = listOf(`version`, `result_id`),
            )
            val `savedEvidence`: LocalizedString get() = LocalizedString(
                key = StringKey("personalHistory.derived.savedEvidence", "personal_history_derived_saved_evidence", emptyList(), 1),
            )
            val `versionsTitle`: LocalizedString get() = LocalizedString(
                key = StringKey("personalHistory.derived.versionsTitle", "personal_history_derived_versions_title", emptyList(), 1),
            )
            val `versionsExplanation`: LocalizedString get() = LocalizedString(
                key = StringKey("personalHistory.derived.versionsExplanation", "personal_history_derived_versions_explanation", emptyList(), 1),
            )
            val `pending`: LocalizedString get() = LocalizedString(
                key = StringKey("personalHistory.derived.pending", "personal_history_derived_pending", emptyList(), 1),
            )
            fun `archiveCounts`(`total_versions`: String, `pending_runs`: String, `failed_runs`: String): LocalizedString = LocalizedString(
                key = StringKey("personalHistory.derived.archiveCounts", "personal_history_derived_archive_counts", listOf("total_versions", "pending_runs", "failed_runs"), 1),
                arguments = listOf(`total_versions`, `pending_runs`, `failed_runs`),
            )
            fun `ownerRefresh`(`owner_pending_runs`: String, `owner_failed_runs`: String): LocalizedString = LocalizedString(
                key = StringKey("personalHistory.derived.ownerRefresh", "personal_history_derived_owner_refresh", listOf("owner_pending_runs", "owner_failed_runs"), 1),
                arguments = listOf(`owner_pending_runs`, `owner_failed_runs`),
            )
            val `allSources`: LocalizedString get() = LocalizedString(
                key = StringKey("personalHistory.derived.allSources", "personal_history_derived_all_sources", emptyList(), 1),
            )
            val `shownInCard`: LocalizedString get() = LocalizedString(
                key = StringKey("personalHistory.derived.shownInCard", "personal_history_derived_shown_in_card", emptyList(), 1),
            )
            val `noValue`: LocalizedString get() = LocalizedString(
                key = StringKey("personalHistory.derived.noValue", "personal_history_derived_no_value", emptyList(), 1),
            )
            fun `savedAt`(`value1`: String, `value2`: String): LocalizedString = LocalizedString(
                key = StringKey("personalHistory.derived.savedAt", "personal_history_derived_saved_at", listOf("value1", "value2"), 1),
                arguments = listOf(`value1`, `value2`),
            )
            val `dayRecords`: LocalizedString get() = LocalizedString(
                key = StringKey("personalHistory.derived.dayRecords", "personal_history_derived_day_records", emptyList(), 1),
            )
            val `immutableEvidence`: LocalizedString get() = LocalizedString(
                key = StringKey("personalHistory.derived.immutableEvidence", "personal_history_derived_immutable_evidence", emptyList(), 1),
            )
            val `archiveChanged`: LocalizedString get() = LocalizedString(
                key = StringKey("personalHistory.derived.archiveChanged", "personal_history_derived_archive_changed", emptyList(), 1),
            )
            val `reload`: LocalizedString get() = LocalizedString(
                key = StringKey("personalHistory.derived.reload", "personal_history_derived_reload", emptyList(), 1),
            )
            object `Reference` {
                val `timeUnknown`: LocalizedString get() = LocalizedString(
                    key = StringKey("personalHistory.derived.reference.timeUnknown", "personal_history_derived_reference_time_unknown", emptyList(), 1),
                )
                val `owner`: LocalizedString get() = LocalizedString(
                    key = StringKey("personalHistory.derived.reference.owner", "personal_history_derived_reference_owner", emptyList(), 1),
                )
                val `confirmed`: LocalizedString get() = LocalizedString(
                    key = StringKey("personalHistory.derived.reference.confirmed", "personal_history_derived_reference_confirmed", emptyList(), 1),
                )
                val `unconfirmed`: LocalizedString get() = LocalizedString(
                    key = StringKey("personalHistory.derived.reference.unconfirmed", "personal_history_derived_reference_unconfirmed", emptyList(), 1),
                )
                fun `measurement`(`type`: String, `metric`: String, `when`: String, `source`: String): LocalizedString = LocalizedString(
                    key = StringKey("personalHistory.derived.reference.measurement", "personal_history_derived_reference_measurement", listOf("type", "metric", "when", "source"), 1),
                    arguments = listOf(`type`, `metric`, `when`, `source`),
                )
                fun `activityReport`(`when`: String, `source`: String): LocalizedString = LocalizedString(
                    key = StringKey("personalHistory.derived.reference.activityReport", "personal_history_derived_reference_activity_report", listOf("when", "source"), 1),
                    arguments = listOf(`when`, `source`),
                )
                fun `profile`(`metric`: String): LocalizedString = LocalizedString(
                    key = StringKey("personalHistory.derived.reference.profile", "personal_history_derived_reference_profile", listOf("metric"), 1),
                    arguments = listOf(`metric`),
                )
                fun `preference`(`metric`: String): LocalizedString = LocalizedString(
                    key = StringKey("personalHistory.derived.reference.preference", "personal_history_derived_reference_preference", listOf("metric"), 1),
                    arguments = listOf(`metric`),
                )
                fun `resolutionState`(`fingerprint`: String): LocalizedString = LocalizedString(
                    key = StringKey("personalHistory.derived.reference.resolutionState", "personal_history_derived_reference_resolution_state", listOf("fingerprint"), 1),
                    arguments = listOf(`fingerprint`),
                )
                fun `evaluationCapture`(`capturedAt`: String, `basis`: String): LocalizedString = LocalizedString(
                    key = StringKey("personalHistory.derived.reference.evaluationCapture", "personal_history_derived_reference_evaluation_capture", listOf("capturedAt", "basis"), 1),
                    arguments = listOf(`capturedAt`, `basis`),
                )
                fun `context`(`source`: String, `fingerprint`: String): LocalizedString = LocalizedString(
                    key = StringKey("personalHistory.derived.reference.context", "personal_history_derived_reference_context", listOf("source", "fingerprint"), 1),
                    arguments = listOf(`source`, `fingerprint`),
                )
                fun `softwareContext`(`version`: String): LocalizedString = LocalizedString(
                    key = StringKey("personalHistory.derived.reference.softwareContext", "personal_history_derived_reference_software_context", listOf("version"), 1),
                    arguments = listOf(`version`),
                )
                fun `identity`(`source`: String, `confirmed`: String, `physical`: String): LocalizedString = LocalizedString(
                    key = StringKey("personalHistory.derived.reference.identity", "personal_history_derived_reference_identity", listOf("source", "confirmed", "physical"), 1),
                    arguments = listOf(`source`, `confirmed`, `physical`),
                )
            }
            object `Component` {
                val `yes`: LocalizedString get() = LocalizedString(
                    key = StringKey("personalHistory.derived.component.yes", "personal_history_derived_component_yes", emptyList(), 1),
                )
                val `no`: LocalizedString get() = LocalizedString(
                    key = StringKey("personalHistory.derived.component.no", "personal_history_derived_component_no", emptyList(), 1),
                )
                fun `sleepStages`(`count`: String): LocalizedString = LocalizedString(
                    key = StringKey("personalHistory.derived.component.sleepStages", "personal_history_derived_component_sleep_stages", listOf("count"), 1),
                    arguments = listOf(`count`),
                )
                fun `numericSeries`(`count`: String): LocalizedString = LocalizedString(
                    key = StringKey("personalHistory.derived.component.numericSeries", "personal_history_derived_component_numeric_series", listOf("count"), 1),
                    arguments = listOf(`count`),
                )
            }
        }
        object `Backfill` {
            val `queued`: LocalizedString get() = LocalizedString(
                key = StringKey("personalHistory.backfill.queued", "personal_history_backfill_queued", emptyList(), 1),
            )
            val `succeeded`: LocalizedString get() = LocalizedString(
                key = StringKey("personalHistory.backfill.succeeded", "personal_history_backfill_succeeded", emptyList(), 1),
            )
            val `failed`: LocalizedString get() = LocalizedString(
                key = StringKey("personalHistory.backfill.failed", "personal_history_backfill_failed", emptyList(), 1),
            )
            val `cancelled`: LocalizedString get() = LocalizedString(
                key = StringKey("personalHistory.backfill.cancelled", "personal_history_backfill_cancelled", emptyList(), 1),
            )
            val `start`: LocalizedString get() = LocalizedString(
                key = StringKey("personalHistory.backfill.start", "personal_history_backfill_start", emptyList(), 1),
            )
            fun `explanation`(`from`: String, `to`: String): LocalizedString = LocalizedString(
                key = StringKey("personalHistory.backfill.explanation", "personal_history_backfill_explanation", listOf("from", "to"), 1),
                arguments = listOf(`from`, `to`),
            )
            fun `submitted`(`submittedDays`: String): LocalizedString = LocalizedString(
                key = StringKey("personalHistory.backfill.submitted", "personal_history_backfill_submitted", listOf("submittedDays"), 1),
                arguments = listOf(`submittedDays`),
            )
            fun `progress`(`processed_days`: String, `scheduled_days`: String, `results_saved`: String, `results_unchanged`: String): LocalizedString = LocalizedString(
                key = StringKey("personalHistory.backfill.progress", "personal_history_backfill_progress", listOf("processed_days", "scheduled_days", "results_saved", "results_unchanged"), 1),
                arguments = listOf(`processed_days`, `scheduled_days`, `results_saved`, `results_unchanged`),
            )
            fun `context`(`from`: String, `to`: String, `time_zone`: String, `as_of`: String): LocalizedString = LocalizedString(
                key = StringKey("personalHistory.backfill.context", "personal_history_backfill_context", listOf("from", "to", "time_zone", "as_of"), 1),
                arguments = listOf(`from`, `to`, `time_zone`, `as_of`),
            )
            val `refresh`: LocalizedString get() = LocalizedString(
                key = StringKey("personalHistory.backfill.refresh", "personal_history_backfill_refresh", emptyList(), 1),
            )
        }
        object `Record` {
            object `Fields` {
                val `provider`: LocalizedString get() = LocalizedString(
                    key = StringKey("personalHistory.record.fields.provider", "personal_history_record_fields_provider", emptyList(), 1),
                )
                val `device`: LocalizedString get() = LocalizedString(
                    key = StringKey("personalHistory.record.fields.device", "personal_history_record_fields_device", emptyList(), 1),
                )
                val `source`: LocalizedString get() = LocalizedString(
                    key = StringKey("personalHistory.record.fields.source", "personal_history_record_fields_source", emptyList(), 1),
                )
                val `record_id`: LocalizedString get() = LocalizedString(
                    key = StringKey("personalHistory.record.fields.record_id", "personal_history_record_fields_record_id", emptyList(), 1),
                )
                val `hardware`: LocalizedString get() = LocalizedString(
                    key = StringKey("personalHistory.record.fields.hardware", "personal_history_record_fields_hardware", emptyList(), 1),
                )
                val `firmware`: LocalizedString get() = LocalizedString(
                    key = StringKey("personalHistory.record.fields.firmware", "personal_history_record_fields_firmware", emptyList(), 1),
                )
                val `protocol`: LocalizedString get() = LocalizedString(
                    key = StringKey("personalHistory.record.fields.protocol", "personal_history_record_fields_protocol", emptyList(), 1),
                )
                val `opcode`: LocalizedString get() = LocalizedString(
                    key = StringKey("personalHistory.record.fields.opcode", "personal_history_record_fields_opcode", emptyList(), 1),
                )
                val `interpretation_version`: LocalizedString get() = LocalizedString(
                    key = StringKey("personalHistory.record.fields.interpretation_version", "personal_history_record_fields_interpretation_version", emptyList(), 1),
                )
                val `measurement_kind`: LocalizedString get() = LocalizedString(
                    key = StringKey("personalHistory.record.fields.measurement_kind", "personal_history_record_fields_measurement_kind", emptyList(), 1),
                )
                val `context`: LocalizedString get() = LocalizedString(
                    key = StringKey("personalHistory.record.fields.context", "personal_history_record_fields_context", emptyList(), 1),
                )
                val `sensor_location`: LocalizedString get() = LocalizedString(
                    key = StringKey("personalHistory.record.fields.sensor_location", "personal_history_record_fields_sensor_location", emptyList(), 1),
                )
                val `temperature_basis`: LocalizedString get() = LocalizedString(
                    key = StringKey("personalHistory.record.fields.temperature_basis", "personal_history_record_fields_temperature_basis", emptyList(), 1),
                )
                val `definition_id`: LocalizedString get() = LocalizedString(
                    key = StringKey("personalHistory.record.fields.definition_id", "personal_history_record_fields_definition_id", emptyList(), 1),
                )
                val `formula_version`: LocalizedString get() = LocalizedString(
                    key = StringKey("personalHistory.record.fields.formula_version", "personal_history_record_fields_formula_version", emptyList(), 1),
                )
                val `time_basis`: LocalizedString get() = LocalizedString(
                    key = StringKey("personalHistory.record.fields.time_basis", "personal_history_record_fields_time_basis", emptyList(), 1),
                )
                val `sport_type`: LocalizedString get() = LocalizedString(
                    key = StringKey("personalHistory.record.fields.sport_type", "personal_history_record_fields_sport_type", emptyList(), 1),
                )
                val `status`: LocalizedString get() = LocalizedString(
                    key = StringKey("personalHistory.record.fields.status", "personal_history_record_fields_status", emptyList(), 1),
                )
                val `trigger`: LocalizedString get() = LocalizedString(
                    key = StringKey("personalHistory.record.fields.trigger", "personal_history_record_fields_trigger", emptyList(), 1),
                )
                val `stop_reason`: LocalizedString get() = LocalizedString(
                    key = StringKey("personalHistory.record.fields.stop_reason", "personal_history_record_fields_stop_reason", emptyList(), 1),
                )
                val `recovery_end_at`: LocalizedString get() = LocalizedString(
                    key = StringKey("personalHistory.record.fields.recovery_end_at", "personal_history_record_fields_recovery_end_at", emptyList(), 1),
                )
                val `sample_count`: LocalizedString get() = LocalizedString(
                    key = StringKey("personalHistory.record.fields.sample_count", "personal_history_record_fields_sample_count", emptyList(), 1),
                )
                val `usable_sample_count`: LocalizedString get() = LocalizedString(
                    key = StringKey("personalHistory.record.fields.usable_sample_count", "personal_history_record_fields_usable_sample_count", emptyList(), 1),
                )
                val `sampled_seconds`: LocalizedString get() = LocalizedString(
                    key = StringKey("personalHistory.record.fields.sampled_seconds", "personal_history_record_fields_sampled_seconds", emptyList(), 1),
                )
                val `sample_coverage_ratio`: LocalizedString get() = LocalizedString(
                    key = StringKey("personalHistory.record.fields.sample_coverage_ratio", "personal_history_record_fields_sample_coverage_ratio", emptyList(), 1),
                )
                val `gap_count`: LocalizedString get() = LocalizedString(
                    key = StringKey("personalHistory.record.fields.gap_count", "personal_history_record_fields_gap_count", emptyList(), 1),
                )
                val `value`: LocalizedString get() = LocalizedString(
                    key = StringKey("personalHistory.record.fields.value", "personal_history_record_fields_value", emptyList(), 1),
                )
                val `reported_min`: LocalizedString get() = LocalizedString(
                    key = StringKey("personalHistory.record.fields.reported_min", "personal_history_record_fields_reported_min", emptyList(), 1),
                )
                val `reported_max`: LocalizedString get() = LocalizedString(
                    key = StringKey("personalHistory.record.fields.reported_max", "personal_history_record_fields_reported_max", emptyList(), 1),
                )
                val `unit`: LocalizedString get() = LocalizedString(
                    key = StringKey("personalHistory.record.fields.unit", "personal_history_record_fields_unit", emptyList(), 1),
                )
                val `shape`: LocalizedString get() = LocalizedString(
                    key = StringKey("personalHistory.record.fields.shape", "personal_history_record_fields_shape", emptyList(), 1),
                )
                val `unit_evidence`: LocalizedString get() = LocalizedString(
                    key = StringKey("personalHistory.record.fields.unit_evidence", "personal_history_record_fields_unit_evidence", emptyList(), 1),
                )
                val `date_basis`: LocalizedString get() = LocalizedString(
                    key = StringKey("personalHistory.record.fields.date_basis", "personal_history_record_fields_date_basis", emptyList(), 1),
                )
                val `provider_namespace`: LocalizedString get() = LocalizedString(
                    key = StringKey("personalHistory.record.fields.provider_namespace", "personal_history_record_fields_provider_namespace", emptyList(), 1),
                )
                val `lineage_key`: LocalizedString get() = LocalizedString(
                    key = StringKey("personalHistory.record.fields.lineage_key", "personal_history_record_fields_lineage_key", emptyList(), 1),
                )
                val `confidence`: LocalizedString get() = LocalizedString(
                    key = StringKey("personalHistory.record.fields.confidence", "personal_history_record_fields_confidence", emptyList(), 1),
                )
            }
            object `Values` {
                val `measured`: LocalizedString get() = LocalizedString(
                    key = StringKey("personalHistory.record.values.measured", "personal_history_record_values_measured", emptyList(), 1),
                )
                val `observed`: LocalizedString get() = LocalizedString(
                    key = StringKey("personalHistory.record.values.observed", "personal_history_record_values_observed", emptyList(), 1),
                )
                val `interval_mean`: LocalizedString get() = LocalizedString(
                    key = StringKey("personalHistory.record.values.interval_mean", "personal_history_record_values_interval_mean", emptyList(), 1),
                )
                val `reported_range`: LocalizedString get() = LocalizedString(
                    key = StringKey("personalHistory.record.values.reported_range", "personal_history_record_values_reported_range", emptyList(), 1),
                )
                val `sample_window_mean`: LocalizedString get() = LocalizedString(
                    key = StringKey("personalHistory.record.values.sample_window_mean", "personal_history_record_values_sample_window_mean", emptyList(), 1),
                )
                val `derived`: LocalizedString get() = LocalizedString(
                    key = StringKey("personalHistory.record.values.derived", "personal_history_record_values_derived", emptyList(), 1),
                )
                val `running`: LocalizedString get() = LocalizedString(
                    key = StringKey("personalHistory.record.values.running", "personal_history_record_values_running", emptyList(), 1),
                )
                val `recovery`: LocalizedString get() = LocalizedString(
                    key = StringKey("personalHistory.record.values.recovery", "personal_history_record_values_recovery", emptyList(), 1),
                )
                val `background`: LocalizedString get() = LocalizedString(
                    key = StringKey("personalHistory.record.values.background", "personal_history_record_values_background", emptyList(), 1),
                )
                val `unknown`: LocalizedString get() = LocalizedString(
                    key = StringKey("personalHistory.record.values.unknown", "personal_history_record_values_unknown", emptyList(), 1),
                )
                val `phone_recorded`: LocalizedString get() = LocalizedString(
                    key = StringKey("personalHistory.record.values.phone_recorded", "personal_history_record_values_phone_recorded", emptyList(), 1),
                )
                val `ring`: LocalizedString get() = LocalizedString(
                    key = StringKey("personalHistory.record.values.ring", "personal_history_record_values_ring", emptyList(), 1),
                )
                val `manual`: LocalizedString get() = LocalizedString(
                    key = StringKey("personalHistory.record.values.manual", "personal_history_record_values_manual", emptyList(), 1),
                )
                val `automatic`: LocalizedString get() = LocalizedString(
                    key = StringKey("personalHistory.record.values.automatic", "personal_history_record_values_automatic", emptyList(), 1),
                )
                val `manual_takeover`: LocalizedString get() = LocalizedString(
                    key = StringKey("personalHistory.record.values.manual_takeover", "personal_history_record_values_manual_takeover", emptyList(), 1),
                )
                val `completed`: LocalizedString get() = LocalizedString(
                    key = StringKey("personalHistory.record.values.completed", "personal_history_record_values_completed", emptyList(), 1),
                )
                val `interrupted`: LocalizedString get() = LocalizedString(
                    key = StringKey("personalHistory.record.values.interrupted", "personal_history_record_values_interrupted", emptyList(), 1),
                )
            }
            val `title`: LocalizedString get() = LocalizedString(
                key = StringKey("personalHistory.record.title", "personal_history_record_title", emptyList(), 1),
            )
            val `source`: LocalizedString get() = LocalizedString(
                key = StringKey("personalHistory.record.source", "personal_history_record_source", emptyList(), 1),
            )
            val `start`: LocalizedString get() = LocalizedString(
                key = StringKey("personalHistory.record.start", "personal_history_record_start", emptyList(), 1),
            )
            val `end`: LocalizedString get() = LocalizedString(
                key = StringKey("personalHistory.record.end", "personal_history_record_end", emptyList(), 1),
            )
            val `date`: LocalizedString get() = LocalizedString(
                key = StringKey("personalHistory.record.date", "personal_history_record_date", emptyList(), 1),
            )
            val `timezone`: LocalizedString get() = LocalizedString(
                key = StringKey("personalHistory.record.timezone", "personal_history_record_timezone", emptyList(), 1),
            )
            val `received`: LocalizedString get() = LocalizedString(
                key = StringKey("personalHistory.record.received", "personal_history_record_received", emptyList(), 1),
            )
            val `method`: LocalizedString get() = LocalizedString(
                key = StringKey("personalHistory.record.method", "personal_history_record_method", emptyList(), 1),
            )
            val `algorithm`: LocalizedString get() = LocalizedString(
                key = StringKey("personalHistory.record.algorithm", "personal_history_record_algorithm", emptyList(), 1),
            )
            val `revision`: LocalizedString get() = LocalizedString(
                key = StringKey("personalHistory.record.revision", "personal_history_record_revision", emptyList(), 1),
            )
            val `excluded`: LocalizedString get() = LocalizedString(
                key = StringKey("personalHistory.record.excluded", "personal_history_record_excluded", emptyList(), 1),
            )
            val `corrected`: LocalizedString get() = LocalizedString(
                key = StringKey("personalHistory.record.corrected", "personal_history_record_corrected", emptyList(), 1),
            )
            val `original`: LocalizedString get() = LocalizedString(
                key = StringKey("personalHistory.record.original", "personal_history_record_original", emptyList(), 1),
            )
            val `identifiers`: LocalizedString get() = LocalizedString(
                key = StringKey("personalHistory.record.identifiers", "personal_history_record_identifiers", emptyList(), 1),
            )
            val `record`: LocalizedString get() = LocalizedString(
                key = StringKey("personalHistory.record.record", "personal_history_record_record", emptyList(), 1),
            )
            val `deviceRecord`: LocalizedString get() = LocalizedString(
                key = StringKey("personalHistory.record.deviceRecord", "personal_history_record_device_record", emptyList(), 1),
            )
        }
        object `Sensor` {
            val `rangeOnly`: LocalizedString get() = LocalizedString(
                key = StringKey("personalHistory.sensor.rangeOnly", "personal_history_sensor_range_only", emptyList(), 1),
            )
            val `changed`: LocalizedString get() = LocalizedString(
                key = StringKey("personalHistory.sensor.changed", "personal_history_sensor_changed", emptyList(), 1),
            )
            val `reload`: LocalizedString get() = LocalizedString(
                key = StringKey("personalHistory.sensor.reload", "personal_history_sensor_reload", emptyList(), 1),
            )
        }
    }
    object `RingOta` {
        val `title`: LocalizedString get() = LocalizedString(
            key = StringKey("ringOta.title", "ring_ota_title", emptyList(), 1),
        )
        object `Phase` {
            val `idle`: LocalizedString get() = LocalizedString(
                key = StringKey("ringOta.phase.idle", "ring_ota_phase_idle", emptyList(), 1),
            )
            val `blocked`: LocalizedString get() = LocalizedString(
                key = StringKey("ringOta.phase.blocked", "ring_ota_phase_blocked", emptyList(), 1),
            )
            val `preparing`: LocalizedString get() = LocalizedString(
                key = StringKey("ringOta.phase.preparing", "ring_ota_phase_preparing", emptyList(), 1),
            )
            val `prepared`: LocalizedString get() = LocalizedString(
                key = StringKey("ringOta.phase.prepared", "ring_ota_phase_prepared", emptyList(), 1),
            )
            val `starting`: LocalizedString get() = LocalizedString(
                key = StringKey("ringOta.phase.starting", "ring_ota_phase_starting", emptyList(), 1),
            )
            val `initializing`: LocalizedString get() = LocalizedString(
                key = StringKey("ringOta.phase.initializing", "ring_ota_phase_initializing", emptyList(), 1),
            )
            val `checking`: LocalizedString get() = LocalizedString(
                key = StringKey("ringOta.phase.checking", "ring_ota_phase_checking", emptyList(), 1),
            )
            val `finalizing`: LocalizedString get() = LocalizedString(
                key = StringKey("ringOta.phase.finalizing", "ring_ota_phase_finalizing", emptyList(), 1),
            )
            val `transferring`: LocalizedString get() = LocalizedString(
                key = StringKey("ringOta.phase.transferring", "ring_ota_phase_transferring", emptyList(), 1),
            )
            val `unknown`: LocalizedString get() = LocalizedString(
                key = StringKey("ringOta.phase.unknown", "ring_ota_phase_unknown", emptyList(), 1),
            )
            val `reconciling`: LocalizedString get() = LocalizedString(
                key = StringKey("ringOta.phase.reconciling", "ring_ota_phase_reconciling", emptyList(), 1),
            )
            val `verifying`: LocalizedString get() = LocalizedString(
                key = StringKey("ringOta.phase.verifying", "ring_ota_phase_verifying", emptyList(), 1),
            )
            val `complete`: LocalizedString get() = LocalizedString(
                key = StringKey("ringOta.phase.complete", "ring_ota_phase_complete", emptyList(), 1),
            )
            val `failed`: LocalizedString get() = LocalizedString(
                key = StringKey("ringOta.phase.failed", "ring_ota_phase_failed", emptyList(), 1),
            )
        }
        object `Reason` {
            val `officialImageAndLoaderUnqualified`: LocalizedString get() = LocalizedString(
                key = StringKey("ringOta.reason.officialImageAndLoaderUnqualified", "ring_ota_reason_official_image_and_loader_unqualified", emptyList(), 1),
            )
            val `protocolUnverified`: LocalizedString get() = LocalizedString(
                key = StringKey("ringOta.reason.protocolUnverified", "ring_ota_reason_protocol_unverified", emptyList(), 1),
            )
        }
        fun `progressBytes`(`progress`: String, `total`: String): LocalizedString = LocalizedString(
            key = StringKey("ringOta.progressBytes", "ring_ota_progress_bytes", listOf("progress", "total"), 1),
            arguments = listOf(`progress`, `total`),
        )
        fun `actualFirmware`(`version`: String): LocalizedString = LocalizedString(
            key = StringKey("ringOta.actualFirmware", "ring_ota_actual_firmware", listOf("version"), 1),
            arguments = listOf(`version`),
        )
        val `checkCatalog`: LocalizedString get() = LocalizedString(
            key = StringKey("ringOta.checkCatalog", "ring_ota_check_catalog", emptyList(), 1),
        )
        val `unavailable`: LocalizedString get() = LocalizedString(
            key = StringKey("ringOta.unavailable", "ring_ota_unavailable", emptyList(), 1),
        )
        val `manifest`: LocalizedString get() = LocalizedString(
            key = StringKey("ringOta.manifest", "ring_ota_manifest", emptyList(), 1),
        )
        val `chooseManifest`: LocalizedString get() = LocalizedString(
            key = StringKey("ringOta.chooseManifest", "ring_ota_choose_manifest", emptyList(), 1),
        )
        val `prepare`: LocalizedString get() = LocalizedString(
            key = StringKey("ringOta.prepare", "ring_ota_prepare", emptyList(), 1),
        )
        fun `confirmationTitle`(`version`: String): LocalizedString = LocalizedString(
            key = StringKey("ringOta.confirmationTitle", "ring_ota_confirmation_title", listOf("version"), 1),
            arguments = listOf(`version`),
        )
        fun `confirmationSource`(`size`: String, `model`: String, `hardware`: String, `firmware`: String): LocalizedString = LocalizedString(
            key = StringKey("ringOta.confirmationSource", "ring_ota_confirmation_source", listOf("size", "model", "hardware", "firmware"), 1),
            arguments = listOf(`size`, `model`, `hardware`, `firmware`),
        )
        val `notRead`: LocalizedString get() = LocalizedString(
            key = StringKey("ringOta.notRead", "ring_ota_not_read", emptyList(), 1),
        )
        fun `digest`(`sha`: String): LocalizedString = LocalizedString(
            key = StringKey("ringOta.digest", "ring_ota_digest", listOf("sha"), 1),
            arguments = listOf(`sha`),
        )
        val `confirmation`: LocalizedString get() = LocalizedString(
            key = StringKey("ringOta.confirmation", "ring_ota_confirmation", emptyList(), 1),
        )
        val `confirm`: LocalizedString get() = LocalizedString(
            key = StringKey("ringOta.confirm", "ring_ota_confirm", emptyList(), 1),
        )
        val `cancel`: LocalizedString get() = LocalizedString(
            key = StringKey("ringOta.cancel", "ring_ota_cancel", emptyList(), 1),
        )
        val `reconcile`: LocalizedString get() = LocalizedString(
            key = StringKey("ringOta.reconcile", "ring_ota_reconcile", emptyList(), 1),
        )
        val `requestFailed`: LocalizedString get() = LocalizedString(
            key = StringKey("ringOta.requestFailed", "ring_ota_request_failed", emptyList(), 1),
        )
    }
    object `SensorDaySummary` {
        val `title`: LocalizedString get() = LocalizedString(
            key = StringKey("sensorDaySummary.title", "sensor_day_summary_title", emptyList(), 1),
        )
    }
}
