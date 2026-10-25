package assistant.mobile.android

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import assistant.mobile.core.GeneratedStrings
import assistant.mobile.core.LocalizedString
import assistant.mobile.core.speech.SpeechPlaybackController
import assistant.mobile.core.speech.SpeechPlaybackPhase
import assistant.mobile.core.speech.SpeechPlaybackProblem

/** Outside navigation entries: screen disposal never owns the application player. */
@Composable
internal fun SpeechPlayerPanel(controller: SpeechPlaybackController, onOpenSettings: () -> Unit, navigationBarInset: Boolean = true) {
    val state by controller.state.collectAsStateWithLifecycle()
    if (!state.active && state.problem == null) return
    val preview = state.key == "preview"
    val label = state.problem?.let(::speechProblemLabel) ?: when (state.phase) {
        SpeechPlaybackPhase.PLAYING -> if (preview) GeneratedStrings.SpeechPlayback.previewPlaying else GeneratedStrings.SpeechPlayback.playing
        else -> if (preview) GeneratedStrings.SpeechPlayback.previewLoading else GeneratedStrings.SpeechPlayback.loading
    }
    Surface(color = MaterialTheme.colorScheme.background, tonalElevation = 2.dp) {
        Column(Modifier.fillMaxWidth().then(if (navigationBarInset) Modifier.navigationBarsPadding() else Modifier)
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .testTag("speech_player"), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(localized(label), modifier = Modifier.testTag("speech_player_status")
                .semantics { liveRegion = LiveRegionMode.Polite },
                color = if (state.problem == null) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.error)
            if (state.active) Text(localized(GeneratedStrings.SpeechPlayback.aiVoice), style = MaterialTheme.typography.bodySmall)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = controller::stop,
                    modifier = Modifier.heightIn(min = 48.dp).testTag("speech_player_stop")) {
                    Text(localized(GeneratedStrings.SpeechPlayback.stop))
                }
                if (state.problem != null) TextButton(onClick = controller::retry,
                    modifier = Modifier.heightIn(min = 48.dp).testTag("speech_player_retry")) {
                    Text(localized(GeneratedStrings.Common.retry))
                }
                if (state.problem != null) TextButton(onClick = onOpenSettings,
                    modifier = Modifier.heightIn(min = 48.dp).testTag("speech_player_settings")) {
                    Text(localized(GeneratedStrings.SpeechSettings.linkText))
                }
            }
        }
    }
}

internal fun speechProblemLabel(problem: SpeechPlaybackProblem): LocalizedString = when (problem) {
    SpeechPlaybackProblem.SESSION_EXPIRED -> GeneratedStrings.SpeechPlayback.Errors.session_expired
    SpeechPlaybackProblem.NO_ACCESS -> GeneratedStrings.SpeechPlayback.Errors.no_access
    SpeechPlaybackProblem.NOT_CONFIGURED -> GeneratedStrings.SpeechPlayback.Errors.not_configured
    SpeechPlaybackProblem.AUTH_REQUIRED -> GeneratedStrings.SpeechPlayback.Errors.auth_required
    SpeechPlaybackProblem.APPLICATION_LIMIT -> GeneratedStrings.SpeechPlayback.Errors.application_limit
    SpeechPlaybackProblem.PROVIDER_LIMIT -> GeneratedStrings.SpeechPlayback.Errors.provider_limit
    SpeechPlaybackProblem.BUSY -> GeneratedStrings.SpeechPlayback.Errors.busy
    SpeechPlaybackProblem.TIMEOUT -> GeneratedStrings.SpeechPlayback.Errors.timeout
    SpeechPlaybackProblem.CONNECTION -> GeneratedStrings.SpeechPlayback.Errors.connection
    SpeechPlaybackProblem.PROVIDER_ERROR -> GeneratedStrings.SpeechPlayback.Errors.provider_error
    SpeechPlaybackProblem.SECRET_UNAVAILABLE -> GeneratedStrings.SpeechPlayback.Errors.secret_unavailable
    SpeechPlaybackProblem.EXECUTION_DISABLED -> GeneratedStrings.SpeechPlayback.Errors.execution_disabled
    SpeechPlaybackProblem.INVALID_RESPONSE, SpeechPlaybackProblem.CHANGED,
    SpeechPlaybackProblem.PLAYBACK, SpeechPlaybackProblem.AUDIO_FOCUS -> GeneratedStrings.SpeechPlayback.Errors.playback
}
