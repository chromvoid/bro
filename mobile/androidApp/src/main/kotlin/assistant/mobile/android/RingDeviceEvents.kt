package assistant.mobile.android

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import assistant.mobile.core.GeneratedStrings
import assistant.mobile.platform.ring.RingDeviceEventState

@Composable
internal fun RingDeviceEvents(state: RingDeviceEventState) {
    Column(Modifier.fillMaxWidth().testTag("ring_device_events"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        RingHeading(GeneratedStrings.Ring.Events.title)
        Text(localized(GeneratedStrings.Ring.Events.hint))
        RingHeading(GeneratedStrings.Ring.Events.tasbih)
        val tasbihConfirmed = state.availability.any { it.kind == "tasbih_count" && it.status == "verified" }
        Text(localized(if (tasbihConfirmed && state.tasbihCount != null) GeneratedStrings.Ring.Events.confirmedCount(state.tasbihCount.toString())
            else GeneratedStrings.Ring.Events.noCount), Modifier.testTag("ring_tasbih_count"))
        if (state.displayBasis == "local_reset" && state.localDisplayCount != null) {
            Text(localized(GeneratedStrings.Ring.Events.localReset(state.localDisplayCount.toString())))
        }
        if (state.candidateCount != null && state.tasbihLastEvent?.qualification != "verified") {
            Text(localized(GeneratedStrings.Ring.Events.candidateCount(state.candidateCount.toString())))
        }
        RingHeading(GeneratedStrings.Ring.Events.game)
        Text(localized(if (state.availability.any { it.kind == "game_tap" && it.status == "verified" })
            GeneratedStrings.Ring.Events.confirmedEvents(state.tapCount.toString()) else GeneratedStrings.Ring.Events.noEvents),
            Modifier.testTag("ring_game_count"))
        if (state.candidateTapCount > 0) Text(localized(GeneratedStrings.Ring.Events.candidateEvents(state.candidateTapCount.toString())))
        state.availability.forEach { item ->
            Text(listOfNotNull(item.kind, item.status, item.reason, item.evidenceRef).joinToString(" · "))
        }
        state.lastEvent?.let { event ->
            RingDetails(localized(GeneratedStrings.Ring.Events.lastEvent), "ring_last_event") {
                Text(listOfNotNull(event.qualification, event.receivedAt).joinToString(" · "))
                event.identity?.let { Text(listOfNotNull(it.model, it.hardware, it.firmware).joinToString(" · ")) }
                listOfNotNull(event.physicalSourceId, event.sourceEvidenceRef, event.physicalEvidenceRef).forEach { Text(it) }
                Text(listOfNotNull(event.candidateKind, event.candidateCount?.toString(), event.resolvedKind, event.resolvedCount?.toString()).joinToString(" · "))
                event.rawPayloadHex?.let { Text(it) }
            }
        }
    }
}
