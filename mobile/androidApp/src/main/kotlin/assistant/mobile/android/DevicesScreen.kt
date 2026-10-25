package assistant.mobile.android

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsPropertyKey
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import assistant.mobile.core.GeneratedStrings
import assistant.mobile.core.LocalizedString
import assistant.mobile.core.SessionLease
import assistant.mobile.core.ring.RingCollectorsController
import assistant.mobile.platform.ring.RingController
import assistant.mobile.core.devices.ClientDevice
import assistant.mobile.core.devices.DevicesController
import assistant.mobile.core.devices.DevicesQuery
import assistant.mobile.core.devices.DisplayController
import assistant.mobile.core.devices.DisplayDescription
import assistant.mobile.core.devices.DisplayLayout
import assistant.mobile.core.devices.DisplayPage
import assistant.mobile.core.devices.PreviewInput
import assistant.mobile.android.whoop.WhoopCard
import assistant.mobile.android.whoop.WhoopHeaderRow
import assistant.mobile.android.whoop.WhoopRepository
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.launch

internal val DevicePreviewVersionKey = SemanticsPropertyKey<Long>("DevicePreviewVersion")

/** The shared controller stays active with the signed-in app and also supplies the header sheet. */
@Composable
internal fun DevicesScreen(
    controller: DevicesController,
    lease: SessionLease,
    onOpenDisplay: (String) -> Unit,
    timezone: String = "UTC",
    whoop: WhoopRepository? = null,
    onOpenGlasses: () -> Unit = {},
    ring: RingController? = null,
    ringCollectors: RingCollectorsController? = null,
    onOpenRingOriginals: ((String) -> Unit)? = null,
) {
    val observed by controller.state.collectAsStateWithLifecycle()
    val state = observed?.takeIf { it.namespace == lease.namespace }
    val scope = rememberCoroutineScope()
    var adding by remember(lease.namespace) { mutableStateOf(false) }
    var revoking by remember(lease.namespace) { mutableStateOf<ClientDevice?>(null) }
    var notice by remember(lease.namespace) { mutableStateOf<LocalizedString?>(null) }
    LaunchedEffect(controller, lease) { controller.refresh() }
    RefreshableScreen(
        tag = "screen_Devices",
        title = localized(GeneratedStrings.Devices.title),
        isRefreshing = state?.devices?.let { it.loading && it.data != null } == true,
        onRefresh = { scope.launch { controller.refresh() } },
    ) {
        Text(localized(GeneratedStrings.Devices.text))
        GlassesCard(onOpenGlasses)
        whoop?.let { WhoopCard(it, timezone, accountUser = lease.namespace.userId) }
        ring?.let { RingCard(it, timezone, ringCollectors, lease, onOpenRingOriginals) }
        Button(onClick = { controller.clearError(); adding = true }, enabled = state?.busy != true,
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("devices_add")) {
            Text(localized(GeneratedStrings.Devices.add))
        }
        if (state == null) StatusMessage(GeneratedStrings.State.loading, "devices_loading")
        state?.let { current ->
            DevicesQueryStatus(current.devices, "devices") { scope.launch { controller.refresh() } }
            if (!adding) DeviceError(current.error, current.serverError, "devices_error")
            notice?.let { StatusMessage(it, "devices_notice") }
            current.devices.data?.let { devices ->
                OutlinedCard(Modifier.fillMaxWidth()) {
                    Column {
                        devices.forEachIndexed { index, device ->
                            if (index > 0) HorizontalDivider()
                            DeviceRow(device, timezone, !current.busy,
                                onOpen = { onOpenDisplay(device.id) }, onRevoke = { controller.clearError(); revoking = device })
                        }
                    }
                }
            }
        }
    }
    if (adding) PairDeviceDialog(controller, lease, onDismiss = { controller.clearError(); adding = false }, onPaired = { id, name ->
        adding = false
        notice = GeneratedStrings.Devices.paired(name)
        onOpenDisplay(id)
    })
    revoking?.let { device ->
        RevokeDeviceDialog(display = false, busy = state?.busy == true, onDismiss = { revoking = null },
            error = state?.error, serverError = state?.serverError) {
            scope.launch {
                if (controller.revoke(device.id)) {
                    notice = GeneratedStrings.Devices.revoked(device.name)
                    revoking = null
                }
            }
        }
    }
}

@Composable
private fun DeviceRow(device: ClientDevice, timezone: String, enabled: Boolean, onOpen: () -> Unit, onRevoke: () -> Unit) {
    val display = device.kind == "display"
    Row(
        Modifier.fillMaxWidth().then(if (display) Modifier.clickable(enabled = enabled, onClick = onOpen) else Modifier)
            .padding(16.dp).semantics(mergeDescendants = true) {}.testTag("devices_item_${device.id}"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(deviceTitle(device), style = MaterialTheme.typography.titleMedium)
            if (device.kind == "agent") Text(agentRights(device), style = MaterialTheme.typography.bodySmall)
            DeviceSeen(device, timezone)
        }
        if (!display) {
            if (device.current) Text(localized(GeneratedStrings.Devices.current), style = MaterialTheme.typography.labelMedium)
            else TextButton(onClick = onRevoke, enabled = enabled,
                modifier = Modifier.heightIn(min = 48.dp).testTag("devices_revoke_${device.id}")) {
                Text(localized(GeneratedStrings.Devices.revoke), color = MaterialTheme.colorScheme.error)
            }
        }
    }
}

@Composable
private fun deviceTitle(device: ClientDevice): String = when {
    device.kind == "display" || device.kind == "agent" -> device.name
    device.kind == "browser" && device.name.isNotEmpty() && device.name != "Web session" -> device.name
    device.kind == "browser" -> localized(GeneratedStrings.Devices.Kind.browser)
    device.kind == "android" -> localized(GeneratedStrings.Devices.Kind.android)
    device.kind == "ios" -> localized(GeneratedStrings.Devices.Kind.ios)
    else -> device.kind
}

@Composable
private fun agentRights(device: ClientDevice): String {
    val writes = device.grants?.writes.orEmpty()
    val sections = writes.map { sectionTitle(it) }
    val parts = mutableListOf(if (writes.isEmpty()) localized(GeneratedStrings.Agents.readOnly) else {
        localized(GeneratedStrings.Agents.writesList(sections.joinToString(", ")))
    })
    if (device.grants?.diagnostics == true) parts += localized(GeneratedStrings.Agents.withDiagnostics)
    return parts.joinToString(" · ")
}

@Composable
private fun sectionTitle(section: String): String = when (section) {
    "tasks" -> localized(GeneratedStrings.Agents.Sections.tasks)
    "timers" -> localized(GeneratedStrings.Agents.Sections.timers)
    "nutrition" -> localized(GeneratedStrings.Agents.Sections.nutrition)
    "personal" -> localized(GeneratedStrings.Agents.Sections.personal)
    "trading" -> localized(GeneratedStrings.Agents.Sections.trading)
    "research" -> localized(GeneratedStrings.Agents.Sections.research)
    else -> section
}

@Composable
private fun DeviceSeen(device: ClientDevice, timezone: String) {
    val whenSeen = deviceMoment(device.lastSeenAt ?: device.createdAt, timezone).replaceFirstChar { it.lowercase(Locale.forLanguageTag("ru")) }
    Text(localized(if (device.lastSeenAt == null) GeneratedStrings.Devices.added(whenSeen)
        else GeneratedStrings.Devices.seen(whenSeen)), style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun PairDeviceDialog(
    controller: DevicesController,
    lease: SessionLease,
    onDismiss: () -> Unit,
    onPaired: (String, String) -> Unit,
) {
    val observed by controller.state.collectAsStateWithLifecycle()
    val state = observed?.takeIf { it.namespace == lease.namespace }
    val scope = rememberCoroutineScope()
    var code by remember(lease.namespace) { mutableStateOf("") }
    var name by remember(lease.namespace) { mutableStateOf("") }
    var failed by remember { mutableStateOf(false) }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize().imePadding()) {
            ScreenColumn("devices_pair_dialog", title = localized(GeneratedStrings.Devices.addTitle), onBack = onDismiss) {
                Text(localized(GeneratedStrings.Devices.addText))
                OutlinedTextField(value = code, onValueChange = { code = it }, singleLine = true,
                    label = { Text(localized(GeneratedStrings.Devices.code)) }, placeholder = { Text("4K7Q-2MXA") },
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters, autoCorrectEnabled = false),
                    enabled = state?.busy != true, isError = failed,
                    modifier = Modifier.fillMaxWidth().testTag("devices_pair_code"))
                OutlinedTextField(value = name, onValueChange = { name = it }, singleLine = true,
                    label = { Text(localized(GeneratedStrings.Devices.name)) },
                    placeholder = { Text(localized(GeneratedStrings.Devices.namePlaceholder)) },
                    enabled = state?.busy != true, modifier = Modifier.fillMaxWidth().testTag("devices_pair_name"))
                if (failed) DeviceError(state?.error, state?.serverError, "devices_pair_error")
                Button(onClick = {
                    scope.launch {
                        val paired = controller.approve(code, name)
                        failed = paired == null
                        paired?.let { onPaired(it.id, it.name) }
                    }
                }, enabled = state?.busy != true,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("devices_pair_approve")) {
                    Text(localized(GeneratedStrings.Devices.confirm))
                }
                TextButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                    Text(localized(GeneratedStrings.Common.cancel))
                }
            }
        }
    }
}

/** Phone accessories and ESP use the same owners as their full device surfaces. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DevicesSheet(
    controller: DevicesController,
    lease: SessionLease,
    onDismiss: () -> Unit,
    onOpenDisplay: (String) -> Unit,
    timezone: String = "UTC",
    whoop: WhoopRepository? = null,
    onOpenSource: (String?) -> Unit = {},
    onOpenGlasses: () -> Unit = {},
    ring: RingController? = null,
    ringCollectors: RingCollectorsController? = null,
    onOpenRingOriginals: ((String) -> Unit)? = null,
) {
    val observed by controller.state.collectAsStateWithLifecycle()
    val state = observed?.takeIf { it.namespace == lease.namespace }
    val scope = rememberCoroutineScope()
    LaunchedEffect(controller, lease) { controller.refresh() }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(20.dp).testTag("devices_sheet"),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            DeviceHeading(GeneratedStrings.Devices.Status.title)
            whoop?.let { WhoopHeaderRow(it, timezone, onOpenSource, accountUser = lease.namespace.userId) }
            GlassesCard(onOpenGlasses)
            ring?.let { RingCard(it, timezone, ringCollectors, lease, onOpenRingOriginals) }
            if (state == null) StatusMessage(GeneratedStrings.State.loading)
            state?.let { current ->
                DevicesQueryStatus(current.devices, "devices_sheet") { scope.launch { controller.refresh() } }
                current.devices.data.orEmpty().filter { it.kind == "display" }.forEach { device ->
                    OutlinedCard(Modifier.fillMaxWidth().clickable {
                        onDismiss()
                        onOpenDisplay(device.id)
                    }.testTag("sheet_esp_${device.id}")) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(device.name, style = MaterialTheme.typography.titleMedium)
                            DeviceSeen(device, timezone)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun GlassesCard(onOpen: () -> Unit) {
    OutlinedCard(Modifier.fillMaxWidth().clickable(onClick = onOpen).testTag("devices_glasses")) {
        Text(localized(GeneratedStrings.Shell.glasses), style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(16.dp))
    }
}

@Composable
internal fun DisplayScreen(
    controller: DisplayController,
    lease: SessionLease,
    deviceId: String,
    onRevoked: () -> Unit,
    timezone: String = "UTC",
) {
    val observed by controller.state.collectAsStateWithLifecycle()
    val state = observed?.takeIf { it.namespace == lease.namespace && it.deviceId == deviceId }
    val owner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    var revoking by remember(lease.namespace, deviceId) { mutableStateOf(false) }
    DisposableEffect(controller, lease, deviceId, owner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> controller.activate(deviceId)
                Lifecycle.Event.ON_STOP -> controller.deactivate(deviceId)
                else -> Unit
            }
        }
        owner.lifecycle.addObserver(observer)
        if (owner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) controller.activate(deviceId)
        onDispose {
            owner.lifecycle.removeObserver(observer)
            controller.deactivate(deviceId)
        }
    }
    LaunchedEffect(state?.closed) { if (state?.closed == true) onRevoked() }
    ScreenColumn("screen_Display", title = state?.display?.data?.device?.name ?: localized(GeneratedStrings.Display.title)) {
        if (state == null) StatusMessage(GeneratedStrings.State.loading, "device_loading")
        state?.let { current ->
            DevicesQueryStatus(current.display, "device") { scope.launch { controller.refresh() } }
            DeviceError(current.error, current.serverError, "device_error")
            if (current.saved) StatusMessage(GeneratedStrings.Display.saved, "device_saved")
            val description = current.display.data
            val draft = current.draft
            if (description != null && draft != null) {
                DeviceHeading(GeneratedStrings.Display.preview)
                Text(localized(if (description.online) GeneratedStrings.Display.online else GeneratedStrings.Display.offline),
                    modifier = Modifier.testTag("device_online"), style = MaterialTheme.typography.labelLarge)
                val bitmap = remember(current.previewVersion, current.preview) {
                    current.preview?.let { bytes -> BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap() }
                }
                Box(Modifier.fillMaxWidth().aspectRatio(4f / 3f).testTag("device_preview")
                    .semantics { this[DevicePreviewVersionKey] = current.previewVersion }) {
                    bitmap?.let {
                        Image(it, localized(GeneratedStrings.Display.previewAlt), modifier = Modifier.fillMaxSize(),
                            filterQuality = FilterQuality.None)
                    }
                }
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { scope.launch { controller.press(PreviewInput.Short) } }, enabled = !current.pressing,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("device_preview_next")) {
                        Text(localized(GeneratedStrings.Display.next))
                    }
                    OutlinedButton(onClick = { scope.launch { controller.press(PreviewInput.Long) } }, enabled = !current.pressing,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("device_preview_home")) {
                        Text(localized(GeneratedStrings.Display.home))
                    }
                }
                Text(localized(GeneratedStrings.Display.previewHint), style = MaterialTheme.typography.bodySmall)
                DisplayDeviceState(description, timezone)
                DeviceHeading(GeneratedStrings.Display.pages)
                OutlinedTextField(value = current.name, onValueChange = { controller.updateName(it.take(60)) }, singleLine = true,
                    label = { Text(localized(GeneratedStrings.Devices.name)) },
                    modifier = Modifier.fillMaxWidth().testTag("device_name"))
                draft.pages.forEachIndexed { index, page ->
                    DisplayPageEditor(description, page, index, draft.pages.size, controller)
                }
                OutlinedButton(onClick = controller::addPage, enabled = draft.pages.size < 8,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("device_add_page")) {
                    Text(localized(GeneratedStrings.Display.addPage))
                }
                DeviceChoice(GeneratedStrings.Display.homeAfter, draft.homeAfterSeconds.toString(),
                    listOf(0 to GeneratedStrings.Display.HomeOptions.item0, 60 to GeneratedStrings.Display.HomeOptions.item60,
                        300 to GeneratedStrings.Display.HomeOptions.item300, 900 to GeneratedStrings.Display.HomeOptions.item900,
                        3600 to GeneratedStrings.Display.HomeOptions.item3600).map { it.first.toString() to localized(it.second) },
                    "device_home_after") { controller.setHomeAfter(it.toInt()) }
                Button(onClick = { scope.launch { controller.save() } }, enabled = current.changed && !current.saving,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("device_save")) {
                    Text(localized(GeneratedStrings.Common.save))
                }
                TextButton(onClick = controller::reset,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("device_reset")) {
                    Text(localized(GeneratedStrings.Display.reset))
                }
                OutlinedButton(onClick = { revoking = true }, enabled = !current.revoking,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("device_revoke")) {
                    Text(localized(GeneratedStrings.Display.revoke), color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
    if (revoking) RevokeDeviceDialog(display = true, busy = state?.revoking == true, onDismiss = { revoking = false }) {
        scope.launch {
            controller.revoke()
            revoking = false
        }
    }
}

@Composable
private fun DisplayDeviceState(description: DisplayDescription, timezone: String) {
    DeviceHeading(GeneratedStrings.Display.state)
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            DeviceStateField(GeneratedStrings.Display.lastSeen, description.device.lastSeenAt?.let { deviceMoment(it, timezone) }
                ?: localized(GeneratedStrings.Display.neverSeen))
            description.device.status?.rssi?.let { DeviceStateField(GeneratedStrings.Display.wifi, "${deviceNumber(it)} dBm") }
            description.device.status?.firmware?.takeIf { it.isNotEmpty() }?.let { DeviceStateField(GeneratedStrings.Display.firmware, it) }
            description.device.status?.uptime?.let { seconds ->
                val totalMinutes = seconds.toLong() / 60
                val days = totalMinutes / 1440
                val hours = totalMinutes / 60 % 24
                val minutes = totalMinutes % 60
                DeviceStateField(GeneratedStrings.Display.uptime, localized(if (days > 0)
                    GeneratedStrings.Display.uptimeDays(days.toString(), hours.toString())
                    else GeneratedStrings.Display.uptimeHours(hours.toString(), minutes.toString())))
            }
        }
    }
}

@Composable
private fun DeviceStateField(label: LocalizedString, value: String) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(localized(label), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value)
    }
}

@Composable
private fun DisplayPageEditor(
    description: DisplayDescription,
    page: DisplayPage,
    index: Int,
    pageCount: Int,
    controller: DisplayController,
) {
    OutlinedCard(Modifier.fillMaxWidth().testTag("device_page_$index")) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            DeviceHeading(GeneratedStrings.Display.page((index + 1).toString()))
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = { controller.movePage(index, index - 1) }, enabled = index > 0,
                    modifier = Modifier.weight(1f).heightIn(min = 48.dp).testTag("device_page_${index}_up")) {
                    Text(localized(GeneratedStrings.Display.up))
                }
                TextButton(onClick = { controller.movePage(index, index + 1) }, enabled = index < pageCount - 1,
                    modifier = Modifier.weight(1f).heightIn(min = 48.dp).testTag("device_page_${index}_down")) {
                    Text(localized(GeneratedStrings.Display.down))
                }
                TextButton(onClick = { controller.removePage(index) }, enabled = pageCount > 1,
                    modifier = Modifier.weight(1f).heightIn(min = 48.dp).testTag("device_page_${index}_remove")) {
                    Text(localized(GeneratedStrings.Display.remove), color = MaterialTheme.colorScheme.error)
                }
            }
            DeviceChoice(GeneratedStrings.Display.layout, page.layout.wire,
                listOf("single" to localized(GeneratedStrings.Display.Layouts.single), "split" to localized(GeneratedStrings.Display.Layouts.split),
                    "grid" to localized(GeneratedStrings.Display.Layouts.grid)), "device_layout_$index") { selected ->
                controller.setLayout(index, DisplayLayout.entries.first { it.wire == selected })
            }
            OutlinedTextField(value = page.title.orEmpty(), onValueChange = { controller.setTitle(index, it.take(40)) }, singleLine = true,
                label = { Text(localized(GeneratedStrings.Display.pageTitle)) },
                placeholder = { Text(localized(GeneratedStrings.Display.pageTitleHint)) },
                modifier = Modifier.fillMaxWidth().testTag("device_title_$index"))
            page.cards.forEachIndexed { slot, card ->
                val available = description.cards.filter { it.available }.map { it.id to it.title }
                val unavailable = card !in available.map { it.first }
                val options = if (unavailable) listOf(card to "${description.cards.find { it.id == card }?.title ?: card} · ${localized(GeneratedStrings.Display.unavailable)}") + available else available
                DeviceChoice(GeneratedStrings.Display.slot((slot + 1).toString()), card, options, "device_card_${index}_$slot",
                    disabled = if (unavailable) setOf(card) else emptySet()) { controller.setCard(index, slot, it) }
            }
            DeviceHeading(GeneratedStrings.Display.ticker)
            Text(localized(GeneratedStrings.Display.tickerHint), style = MaterialTheme.typography.bodySmall)
            description.cards.filter { it.brief && (it.available || it.id in page.ticker.orEmpty()) }.forEach { card ->
                Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).toggleable(value = card.id in page.ticker.orEmpty(), enabled = card.available,
                    role = Role.Checkbox, onValueChange = { controller.setTicker(index, card.id, it) })
                    .testTag("device_ticker_${index}_${card.id}"), verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = card.id in page.ticker.orEmpty(), onCheckedChange = null, enabled = card.available)
                    Text(card.title, modifier = Modifier.weight(1f))
                }
            }
            if (page.ticker.orEmpty().size > 1) DeviceChoice(GeneratedStrings.Display.tickerPeriod, (page.tickerSeconds ?: 12).toString(),
                listOf(6, 12, 20, 30, 60).map { it.toString() to localized(GeneratedStrings.Display.everySeconds(it.toString())) },
                "device_ticker_period_$index") { controller.setTickerPeriod(index, it.toInt()) }
        }
    }
}

@Composable
private fun DeviceChoice(
    label: LocalizedString,
    value: String,
    options: List<Pair<String, String>>,
    tag: String,
    disabled: Set<String> = emptySet(),
    onChange: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(localized(label), style = MaterialTheme.typography.labelLarge)
        Box {
            OutlinedButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag(tag)) {
                Text(options.find { it.first == value }?.second ?: value)
            }
            DropdownMenu(expanded, onDismissRequest = { expanded = false }) {
                options.forEach { (id, title) ->
                    DropdownMenuItem(text = { Text(title) }, onClick = { expanded = false; onChange(id) }, enabled = id !in disabled,
                        modifier = Modifier.heightIn(min = 48.dp).testTag("${tag}_option_$id"))
                }
            }
        }
    }
}

@Composable
private fun RevokeDeviceDialog(display: Boolean, busy: Boolean, onDismiss: () -> Unit,
    error: LocalizedString? = null, serverError: String? = null, onConfirm: () -> Unit) {
    AlertDialog(onDismissRequest = onDismiss,
        title = { Text(localized(if (display) GeneratedStrings.Display.revokeTitle else GeneratedStrings.Devices.revokeTitle)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(localized(if (display) GeneratedStrings.Display.revokeText else GeneratedStrings.Devices.revokeText))
                DeviceError(error, serverError, "devices_revoke_error")
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm, enabled = !busy,
                modifier = Modifier.heightIn(min = 48.dp).testTag(if (display) "device_revoke_confirm" else "devices_revoke_confirm")) {
                Text(localized(if (display) GeneratedStrings.Display.revoke else GeneratedStrings.Devices.revoke), color = MaterialTheme.colorScheme.error)
            }
        }, dismissButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = 48.dp)) { Text(localized(GeneratedStrings.Common.cancel)) }
        })
}

@Composable
private fun <T> DevicesQueryStatus(query: DevicesQuery<T>, tag: String, onRetry: () -> Unit) {
    if (query.loading && query.data == null) StatusMessage(GeneratedStrings.State.loading, "${tag}_loading")
    if (query.error != null || query.serverError != null) {
        if (query.data != null) StatusMessage(GeneratedStrings.State.refreshFailed, "${tag}_refresh_failed")
        else DeviceError(query.error, query.serverError, "${tag}_query_error")
        TextButton(onClick = onRetry, enabled = !query.loading,
            modifier = Modifier.heightIn(min = 48.dp).testTag("${tag}_retry")) { Text(localized(GeneratedStrings.Common.retry)) }
    }
}

@Composable
private fun DeviceError(error: LocalizedString?, serverError: String?, tag: String) {
    if (serverError != null) Text(serverError, color = MaterialTheme.colorScheme.error,
        modifier = Modifier.testTag(tag).semantics { liveRegion = LiveRegionMode.Assertive })
    else error?.let { ErrorMessage(it, tag) }
}

@Composable
private fun DeviceHeading(message: LocalizedString) {
    Text(localized(message), style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
}

/** Dates use the account's calendar, as in the web device list, never the phone's time zone. */
@Composable
internal fun deviceMoment(instant: String, timezone: String): String {
    val todayLabel = localized(GeneratedStrings.Day.today)
    val tomorrowLabel = localized(GeneratedStrings.Tasks.dateTomorrow)
    return runCatching {
        val zone = ZoneId.of(timezone)
        val moment = Instant.parse(instant).atZone(zone)
        val today = LocalDate.now(zone)
        val day = when (moment.toLocalDate()) {
            today -> todayLabel
            // The web date helper uses this same literal; it has no i18n leaf.
            today.minusDays(1) -> "Вчера"
            today.plusDays(1) -> tomorrowLabel
            else -> moment.format(DateTimeFormatter.ofPattern(if (moment.year == today.year) "d MMMM" else "d MMMM yyyy 'г.'", Locale.forLanguageTag("ru")))
        }
        "$day, ${moment.format(DateTimeFormatter.ofPattern("HH:mm", Locale.forLanguageTag("ru")))}"
    }.getOrDefault(instant)
}

private fun deviceNumber(value: Double): String = if (value == value.toLong().toDouble()) value.toLong().toString() else value.toString()
