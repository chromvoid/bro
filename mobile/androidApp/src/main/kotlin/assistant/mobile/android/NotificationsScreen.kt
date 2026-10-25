package assistant.mobile.android

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import assistant.mobile.design.ui.ProgressBar
import assistant.mobile.core.GeneratedStrings
import assistant.mobile.core.SessionLease
import assistant.mobile.core.notifications.AppNotification
import assistant.mobile.core.notifications.NotificationQuery
import assistant.mobile.core.notifications.NotificationsController
import assistant.mobile.core.notifications.notificationDestination
import assistant.mobile.design.R as DesignR
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.launch

@Composable
internal fun NotificationsScreen(
    controller: NotificationsController,
    lease: SessionLease,
    onOpen: (AppNotification) -> Unit,
    modifier: Modifier = Modifier,
) {
    val observed by controller.state.collectAsStateWithLifecycle()
    val state = observed?.takeIf { it.namespace == lease.namespace }
    val scope = rememberCoroutineScope()
    var resolvedExpanded by remember(lease.namespace) { mutableStateOf(false) }
    NotificationsLifecycle(controller, lease)
    RefreshableScreen("screen_Notifications", title = localized(GeneratedStrings.Notifications.title),
        isRefreshing = state?.list?.let { it.loading && it.data != null } == true,
        onRefresh = { scope.launch { controller.refresh() } }, modifier = modifier) {
        if (state == null) StatusMessage(GeneratedStrings.State.loading, "notifications_loading")
        state?.let { current ->
            if (current.unread > 0) {
                OutlinedButton(onClick = { scope.launch { controller.read() } }, enabled = !current.busy,
                    modifier = Modifier.heightIn(min = 48.dp).testTag("notifications_read_all")) {
                    Text(localized(GeneratedStrings.Notifications.readAll))
                }
            }
            NotificationsQueryStatus(current.list, "list") { scope.launch { controller.refresh() } }
            current.error?.let { ErrorMessage(it, "notifications_error") }
            current.notice?.let { StatusMessage(it, "notifications_notice") }
            current.list.data?.let {
                if (current.items.isEmpty()) {
                    Text(localized(GeneratedStrings.Notifications.emptyTitle), style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.testTag("notifications_empty"))
                    Text(localized(GeneratedStrings.Notifications.emptyText))
                } else {
                    val active = current.items.filter { item -> item.active }
                    val resolved = current.items.filterNot { item -> item.active }
                    val cards: @Composable (AppNotification) -> Unit = { notification ->
                        NotificationCard(notification, current.timezone, current.busy,
                            onOpen = { scope.launch {
                                if (!notification.unread || controller.read(listOf(notification.id))) onOpen(notification)
                            } },
                            onRead = { scope.launch { controller.read(listOf(notification.id)) } },
                            onSnoozeHour = { scope.launch { controller.snooze(notification.id, 60) } },
                            onSnoozeMorning = { scope.launch { controller.snoozeUntil(notification.id, controller.nextMorning()) } })
                    }
                    active.forEach { notification -> cards(notification) }
                    if (active.isEmpty()) Text(localized(GeneratedStrings.Notifications.allClear),
                        modifier = Modifier.testTag("notifications_clear"))
                    if (resolved.isNotEmpty()) {
                        TextButton(onClick = { resolvedExpanded = !resolvedExpanded },
                            modifier = Modifier.heightIn(min = 48.dp).testTag("notifications_resolved_toggle")) {
                            Text("${localized(GeneratedStrings.Notifications.resolvedGroup)} · ${resolved.size}")
                        }
                        if (resolvedExpanded) resolved.forEach { notification -> cards(notification) }
                    }
                }
            }
        }
    }
}

/** Top-level bars and screens share one controller without owning its session. */
@Composable
internal fun NotificationsLifecycle(controller: NotificationsController, lease: SessionLease) {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val owner = remember(controller, lease) { Any() }
    DisposableEffect(controller, lease, lifecycle, owner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> controller.activate(owner)
                Lifecycle.Event.ON_STOP -> controller.deactivate(owner)
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) controller.activate(owner)
        onDispose { lifecycle.removeObserver(observer); controller.deactivate(owner) }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun NotificationCard(
    notification: AppNotification,
    timezone: String,
    busy: Boolean = false,
    feed: Boolean = false,
    onOpen: () -> Unit,
    onRead: () -> Unit,
    onSnoozeHour: () -> Unit,
    onSnoozeMorning: () -> Unit,
) {
    var expanded by remember(notification.id) { mutableStateOf(false) }
    val description = localized(GeneratedStrings.Notifications.one)
    val edge = when {
        notification.active && notification.unread && notification.severity == "urgent" -> MaterialTheme.colorScheme.error
        notification.active && notification.unread -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.outlineVariant
    }
    OutlinedCard(Modifier.fillMaxWidth().testTag("notification_item_${notification.id}").semantics { contentDescription = description },
        border = BorderStroke(1.dp, edge),
        colors = CardDefaults.outlinedCardColors(containerColor = if (notification.active) MaterialTheme.colorScheme.surfaceContainerLow
            else MaterialTheme.colorScheme.surfaceContainerLow.copy(alpha = .6f))) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (!notification.active) {
                val details = localized(GeneratedStrings.Notifications.details(notification.title))
                TextButton(onClick = { expanded = !expanded }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                    .testTag("notification_details_${notification.id}").semantics { contentDescription = details }) {
                    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(notification.title, style = MaterialTheme.typography.titleMedium)
                        Text(localized(GeneratedStrings.Notifications.resolvedAt(
                            taskTimestamp(notification.resolvedAt ?: notification.updatedAt, timezone))),
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                if (expanded) {
                    if (notification.body.isNotEmpty()) Text(notification.body)
                    Text(notification.resolution?.takeIf { it.isNotBlank() } ?: localized(GeneratedStrings.Notifications.resolved),
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } else {
                if (feed) Text(localized(GeneratedStrings.Notifications.one), style = MaterialTheme.typography.labelMedium)
                Text(notification.title, style = MaterialTheme.typography.titleMedium)
                if (notification.count > 1) Text(localized(GeneratedStrings.Notifications.times(
                    notification.count.toDouble(), notification.count.toString())), style = MaterialTheme.typography.bodySmall)
                if (notification.body.isNotEmpty()) Text(notification.body)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(if (feed) notificationTime(notification.updatedAt, timezone) else taskTimestamp(notification.updatedAt, timezone),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (notification.unread) Text(localized(GeneratedStrings.Notifications.new),
                        style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                }
                val snoozed = notification.snoozedUntil?.takeIf { until ->
                    runCatching { Instant.parse(until).isAfter(Instant.now()) }.getOrDefault(false)
                }
                snoozed?.let { until ->
                    Text(localized(GeneratedStrings.Notifications.snoozedUntil(taskTimestamp(until, timezone))),
                        style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("notification_snoozed_${notification.id}"))
                }
                if (!feed || notification.unread) FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (notificationDestination(notification.link) != null) OutlinedButton(onClick = onOpen, enabled = !busy,
                        modifier = Modifier.heightIn(min = 48.dp).testTag("notification_open_${notification.id}")) {
                        Text(localized(GeneratedStrings.Notifications.open))
                    }
                    if (notification.unread) OutlinedButton(onClick = onRead, enabled = !busy,
                        modifier = Modifier.heightIn(min = 48.dp).testTag("notification_read_${notification.id}")) {
                        Text(localized(GeneratedStrings.Notifications.markRead))
                    }
                    if (snoozed == null) {
                        TextButton(onClick = onSnoozeHour, enabled = !busy,
                            modifier = Modifier.heightIn(min = 48.dp).testTag("notification_snooze_hour_${notification.id}")) {
                            Text(localized(GeneratedStrings.Notifications.laterHour))
                        }
                        TextButton(onClick = onSnoozeMorning, enabled = !busy,
                            modifier = Modifier.heightIn(min = 48.dp).testTag("notification_snooze_morning_${notification.id}")) {
                            Text(localized(GeneratedStrings.Notifications.laterMorning))
                        }
                    }
                }
            }
        }
    }
}

private fun notificationTime(stamp: String, timezone: String): String = runCatching {
    Instant.parse(stamp).atZone(ZoneId.of(timezone)).format(DateTimeFormatter.ofPattern("HH:mm"))
}.getOrDefault(stamp)

@Composable
internal fun NotificationBell(unread: Int, onOpen: () -> Unit) {
    val label = localized(if (unread > 0) GeneratedStrings.Notifications.bellUnread(unread.toDouble(), unread.toString())
        else GeneratedStrings.Notifications.title)
    val iconColor = MaterialTheme.colorScheme.onSurface
    NativeToolbarIconButton(onClick = onOpen, modifier = Modifier.testTag("notifications_bell")
        .semantics { contentDescription = label }) {
        BadgedBox(badge = {
            if (unread > 0) Badge(Modifier.testTag("notifications_unread")) {
                Text(if (unread > 99) localized(GeneratedStrings.Notifications.badgeOverflow) else unread.toString())
            }
        }) {
            Icon(painterResource(DesignR.drawable.lucide_bell), contentDescription = null,
                modifier = Modifier.size(24.dp), tint = iconColor)
        }
    }
}

@Composable
internal fun <T> NotificationsQueryStatus(query: NotificationQuery<T>, tag: String, retry: () -> Unit) {
    if (query.loading) ProgressBar(modifier = Modifier.fillMaxWidth().testTag("notifications_loading_$tag"))
    query.error?.let {
        ErrorMessage(it, "notifications_error_$tag")
        TextButton(onClick = retry, enabled = !query.loading,
            modifier = Modifier.heightIn(min = 48.dp).testTag("notifications_refresh")) {
            Text(localized(GeneratedStrings.Common.retry))
        }
    }
    if (query.stale) StatusMessage(GeneratedStrings.State.stale, "notifications_stale_$tag")
}
