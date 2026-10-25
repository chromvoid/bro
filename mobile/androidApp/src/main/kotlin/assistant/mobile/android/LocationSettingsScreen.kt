package assistant.mobile.android

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import assistant.mobile.core.GeneratedStrings
import assistant.mobile.core.SessionLease
import assistant.mobile.core.location.LocationController
import assistant.mobile.core.location.PositionPermission
import java.util.Locale
import kotlinx.coroutines.launch

/** Read-only activation; permission launchers are reached only from explicit actions. */
@Composable
internal fun LocationSettingsScreen(controller: LocationController, lease: SessionLease, timezone: String = "UTC") {
    val observed by controller.state.collectAsStateWithLifecycle()
    val state = observed?.takeIf { it.namespace == lease.namespace }
    val scope = rememberCoroutineScope()
    var confirming by remember(lease.namespace) { mutableStateOf(false) }
    var forgotten by remember(lease.namespace) { mutableStateOf(false) }
    var backgroundDisclosure by remember(lease.namespace) { mutableStateOf(false) }
    LaunchedEffect(controller, lease) { controller.activate() }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(controller, lease, lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) scope.launch { controller.refresh() }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer); controller.deactivate() }
    }
    val query = state?.snapshot
    RefreshableScreen("screen_LocationSettings", title = localized(GeneratedStrings.LocationSettings.title),
        isRefreshing = query?.let { it.loading && it.data != null } == true,
        onRefresh = { scope.launch { controller.refresh() } }) {
        LocationSection(localized(GeneratedStrings.LocationSettings.device), "location_device") {
            state?.let { current ->
                Text(localized(when (current.position.permission) {
                    PositionPermission.GRANTED -> GeneratedStrings.LocationSettings.Permission.granted
                    PositionPermission.PROMPT -> GeneratedStrings.LocationSettings.Permission.prompt
                    PositionPermission.DENIED -> GeneratedStrings.LocationSettings.Permission.denied
                    PositionPermission.UNAVAILABLE -> GeneratedStrings.LocationSettings.Permission.unavailable
                }), modifier = Modifier.testTag("location_permission"))
                if (current.position.permission == PositionPermission.PROMPT) Button(
                    onClick = { scope.launch { controller.requestPosition() } }, enabled = !current.busy,
                    modifier = Modifier.heightIn(min = 48.dp).testTag("location_allow")) {
                    Text(localized(GeneratedStrings.LocationSettings.allow))
                }
                if (current.position.permission == PositionPermission.GRANTED) OutlinedButton(
                    onClick = { scope.launch { controller.requestPosition() } }, enabled = !current.busy,
                    modifier = Modifier.heightIn(min = 48.dp).testTag("location_current_request")) {
                    Text(localized(GeneratedStrings.LocationSettings.title))
                }
            }
        }
        if (state?.position?.permission == PositionPermission.GRANTED) LocationSection(
            localized(GeneratedStrings.LocationSettings.background), "location_background_section") {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                val enabled = state?.background?.enabled == true
                Text(localized(if (enabled) GeneratedStrings.LocationSettings.backgroundOn else GeneratedStrings.LocationSettings.backgroundOff),
                    modifier = Modifier.weight(1f))
                val label = localized(GeneratedStrings.LocationSettings.background)
                Switch(checked = enabled, onCheckedChange = { next ->
                    if (next) backgroundDisclosure = true else scope.launch { controller.disableBackground() }
                }, enabled = state?.busy != true, modifier = Modifier.heightIn(min = 48.dp).testTag("location_background")
                    .semantics { contentDescription = label })
            }
        }
        if (backgroundDisclosure) AlertDialog(onDismissRequest = { backgroundDisclosure = false },
            title = { Text(stringResource(R.string.privacy_background_location_title)) },
            text = { Text(stringResource(R.string.privacy_background_location_text)) },
            confirmButton = { TextButton(onClick = {
                backgroundDisclosure = false
                scope.launch { controller.enableBackground() }
            }) { Text(stringResource(R.string.privacy_continue)) } },
            dismissButton = { TextButton(onClick = { backgroundDisclosure = false }) {
                Text(stringResource(R.string.privacy_cancel))
            } })
        state?.error?.let { ErrorMessage(it, "location_error") }
        if (forgotten) StatusMessage(GeneratedStrings.LocationSettings.forgotten, "location_forgotten")
        if (query == null) StatusMessage(GeneratedStrings.State.loading, "location_loading")
        else {
            if (query.loading && query.data == null) StatusMessage(GeneratedStrings.State.loading, "location_loading")
            query.error?.let {
                if (query.data != null) StatusMessage(GeneratedStrings.State.refreshFailed, "location_refresh_failed")
                else ErrorMessage(it, "location_read_error")
                TextButton(onClick = { scope.launch { controller.refresh() } }, modifier = Modifier.heightIn(min = 48.dp)
                    .testTag("location_retry")) { Text(localized(GeneratedStrings.Common.retry)) }
            }
            query.data?.let { snapshot ->
                LocationSection(localized(GeneratedStrings.LocationSettings.history), "location_history") {
                    val current = snapshot.current
                    if (current == null) Text(localized(GeneratedStrings.LocationSettings.none), modifier = Modifier.testTag("location_none"))
                    else {
                        Text(current.place ?: String.format(Locale.US, "%.5f, %.5f", current.latitude, current.longitude),
                            style = MaterialTheme.typography.titleMedium, modifier = Modifier.testTag("location_current"))
                        Text(listOfNotNull(placesMoment(current.capturedAt, timezone),
                            localized(GeneratedStrings.LocationSettings.accuracy(current.accuracyMeters.toString())), current.device)
                            .filter(String::isNotBlank).joinToString(" · "), style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.testTag("location_current_details"))
                    }
                    val history = snapshot.history
                    if (history.points > 0) {
                        Text(localized(GeneratedStrings.LocationSettings.points(history.points.toDouble(), history.points.toString(),
                            history.since?.let { placesMoment(it, timezone) }.orEmpty())), style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.testTag("location_points"))
                        OutlinedButton(onClick = { controller.clearError(); confirming = true }, enabled = state?.busy != true,
                            modifier = Modifier.heightIn(min = 48.dp).testTag("location_forget")) {
                            Text(localized(GeneratedStrings.LocationSettings.forget))
                        }
                    }
                    PlacesAttribution()
                }
            }
        }
    }
    if (confirming) AlertDialog(onDismissRequest = { if (state?.busy != true) confirming = false },
        modifier = Modifier.testTag("location_forget_dialog"), title = { Text(localized(GeneratedStrings.LocationSettings.forgetTitle)) },
        text = { Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(localized(GeneratedStrings.LocationSettings.forgetText))
            state?.error?.let { ErrorMessage(it, "location_forget_error") }
        } }, dismissButton = {
            TextButton(onClick = { confirming = false }, enabled = state?.busy != true,
                modifier = Modifier.heightIn(min = 48.dp).testTag("location_forget_keep")) { Text(localized(GeneratedStrings.LocationSettings.keep)) }
        }, confirmButton = {
            TextButton(onClick = { scope.launch {
                if (controller.deleteHistory()) { confirming = false; forgotten = true }
            } }, enabled = state?.busy != true, modifier = Modifier.heightIn(min = 48.dp).testTag("location_forget_confirm")) {
                Text(localized(GeneratedStrings.LocationSettings.forgetConfirm), color = MaterialTheme.colorScheme.error)
            }
        })
}

@Composable
private fun LocationSection(title: String, tag: String, content: @Composable () -> Unit) {
    OutlinedCard(Modifier.fillMaxWidth().testTag(tag)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
            content()
        }
    }
}
