package assistant.mobile.android

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import assistant.mobile.core.GeneratedStrings
import assistant.mobile.core.LocalizedString
import assistant.mobile.platform.ring.RingCapability
import assistant.mobile.platform.ring.RingController
import assistant.mobile.platform.ring.RingIdentity
import assistant.mobile.platform.ring.RingOtaAction
import assistant.mobile.platform.ring.RingOtaCatalogState
import assistant.mobile.platform.ring.RingOtaController
import assistant.mobile.platform.ring.RingOtaState

@Composable
internal fun RingFirmwareUpdate(controller: RingController, ota: RingOtaController, expectedUserId: String? = null) {
    val state by ota.otaStates.collectAsStateWithLifecycle()
    val device by controller.deviceStates.collectAsStateWithLifecycle()
    val ring by controller.states.collectAsStateWithLifecycle()
    val owner = state.owner ?: return
    if (expectedUserId != null && owner.userId != expectedUserId || device.userId != owner.userId ||
        device.sourceId != owner.sourceId || device.bindingGeneration != owner.bindingGeneration) return
    var catalog by remember(owner) { mutableStateOf<RingOtaCatalogState?>(null) }
    val actions = remember(owner) { RingUiActions() }
    DisposableEffect(ota, owner) { onDispose { ota.otaScreenDetached(owner) } }
    RingFirmwareUpdateContent(state, catalog, device.capability("ota"), device.identity,
        disabled = ring.operationId != null || ring.error?.code == "store_unavailable",
        onCatalog = { actions.run { catalog = ota.otaCatalog(owner) } },
        onAction = { action -> actions.run { ota.ota(owner, action) } })
    actions.error?.let { RingError(it.code, it.message, "ring_ota_error") }
}

/** The compiled catalog is empty until an exact official image and loader have evidence. */
@Composable
internal fun RingFirmwareUpdateContent(
    state: RingOtaState,
    catalog: RingOtaCatalogState? = null,
    capability: RingCapability? = null,
    identity: RingIdentity? = null,
    disabled: Boolean = false,
    onCatalog: () -> Unit,
    onAction: (RingOtaAction) -> Unit,
) {
    var selected by remember(state.userId, state.sourceId, state.bindingGeneration) { mutableStateOf("") }
    var accepted by remember(state.userId, state.sourceId, state.bindingGeneration, state.prepared) { mutableStateOf(false) }
    val allowed = capability?.canWrite == true
    val busy = state.active
    val prepared = state.prepared
    Column(Modifier.fillMaxWidth().testTag("ring_firmware_update"), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        HorizontalDivider()
        RingHeading(GeneratedStrings.RingOta.title)
        Text(localized(ringOtaPhase(state.phase)) + if (state.reason.isNotEmpty()) " · ${ringOtaReason(state.reason)}" else "",
            Modifier.testTag("ring_ota_phase").semantics { liveRegion = LiveRegionMode.Polite })
        if (state.totalBytes > 0) Text(localized(GeneratedStrings.RingOta.progressBytes(state.progressBytes.toString(), state.totalBytes.toString())))
        state.actualFirmware?.let { Text(localized(GeneratedStrings.RingOta.actualFirmware(it)), Modifier.testTag("ring_ota_actual_firmware")) }
        RingButton(GeneratedStrings.RingOta.checkCatalog, "ring_ota_catalog", !busy && !disabled, onCatalog)
        if (!allowed) Text(capability?.reason?.let { ringOtaReason(it) } ?: localized(GeneratedStrings.RingOta.unavailable),
            style = MaterialTheme.typography.bodySmall)
        catalog?.reason?.takeIf { it.isNotEmpty() }?.let {
            Text(ringOtaReason(it), Modifier.testTag("ring_ota_catalog_reason"), style = MaterialTheme.typography.bodySmall)
        }
        val entries = catalog?.entries.orEmpty()
        if (entries.isNotEmpty()) {
            var expanded by remember { mutableStateOf(false) }
            Box {
                OutlinedButton(onClick = { expanded = true }, enabled = !busy && !disabled,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("ring_ota_manifest")) {
                    Text(entries.singleOrNull { it.manifestId == selected }?.title ?: localized(GeneratedStrings.RingOta.chooseManifest))
                }
                DropdownMenu(expanded, onDismissRequest = { expanded = false }) {
                    entries.forEach { image -> DropdownMenuItem(
                        text = { Text("${image.title} · ${image.targetFirmware} · ${ringOtaReason(image.reason)}") },
                        enabled = image.availability == "available", onClick = { selected = image.manifestId; expanded = false },
                        modifier = Modifier.testTag("ring_ota_manifest_${image.manifestId}")) }
                }
            }
        }
        if (selected.isNotEmpty()) RingButton(GeneratedStrings.RingOta.prepare, "ring_ota_prepare",
            !busy && !disabled && allowed && entries.any { it.manifestId == selected && it.availability == "available" }) {
            accepted = false; onAction(RingOtaAction.Prepare(selected))
        }
        prepared?.let { confirmation ->
            RingHeading(GeneratedStrings.RingOta.confirmationTitle(confirmation.targetFirmware))
            val notRead = localized(GeneratedStrings.RingOta.notRead)
            Text(localized(GeneratedStrings.RingOta.confirmationSource(confirmation.sizeBytes.toString(),
                identity?.model ?: notRead, identity?.hardware ?: notRead, identity?.firmware ?: notRead)))
            Text(localized(GeneratedStrings.RingOta.digest(confirmation.sha256)), style = MaterialTheme.typography.bodySmall)
            Row(Modifier.fillMaxWidth()) {
                Checkbox(accepted, onCheckedChange = { accepted = it }, modifier = Modifier.testTag("ring_ota_accept"))
                Text(localized(GeneratedStrings.RingOta.confirmation), Modifier.weight(1f))
            }
            RingButton(GeneratedStrings.RingOta.confirm, "ring_ota_confirm",
                !busy && !disabled && allowed && accepted && confirmation.expiresAtMs > System.currentTimeMillis()) {
                accepted = false; onAction(RingOtaAction.Confirm(confirmation.operationId, confirmation.confirmationToken))
            }
            TextButton(onClick = { accepted = false }, enabled = !busy,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("ring_ota_cancel")) { Text(localized(GeneratedStrings.RingOta.cancel)) }
        }
        state.operationId?.takeIf { state.phase in setOf("unknown", "failed") }?.let { operation ->
            RingButton(GeneratedStrings.RingOta.reconcile, "ring_ota_reconcile", !busy) { onAction(RingOtaAction.Reconcile(operation)) }
        }
    }
}

@Composable
private fun ringOtaReason(reason: String): String = when (reason) {
    "official_image_and_loader_unqualified" -> localized(GeneratedStrings.RingOta.Reason.officialImageAndLoaderUnqualified)
    "protocol_unverified" -> localized(GeneratedStrings.RingOta.Reason.protocolUnverified)
    else -> reason
}

private fun ringOtaPhase(phase: String): LocalizedString = when (phase) {
    "blocked" -> GeneratedStrings.RingOta.Phase.blocked
    "preparing" -> GeneratedStrings.RingOta.Phase.preparing
    "prepared" -> GeneratedStrings.RingOta.Phase.prepared
    "starting" -> GeneratedStrings.RingOta.Phase.starting
    "initializing" -> GeneratedStrings.RingOta.Phase.initializing
    "checking" -> GeneratedStrings.RingOta.Phase.checking
    "finalizing" -> GeneratedStrings.RingOta.Phase.finalizing
    "transferring" -> GeneratedStrings.RingOta.Phase.transferring
    "unknown" -> GeneratedStrings.RingOta.Phase.unknown
    "reconciling" -> GeneratedStrings.RingOta.Phase.reconciling
    "verifying" -> GeneratedStrings.RingOta.Phase.verifying
    "complete" -> GeneratedStrings.RingOta.Phase.complete
    "failed" -> GeneratedStrings.RingOta.Phase.failed
    else -> GeneratedStrings.RingOta.Phase.idle
}
