package assistant.mobile.android

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import assistant.mobile.core.GeneratedStrings
import assistant.mobile.core.LocalizedString
import assistant.mobile.core.SessionLease
import assistant.mobile.core.circle.CirclePerson
import assistant.mobile.core.journal.SubmissionState
import assistant.mobile.core.sharing.*
import kotlinx.coroutines.launch

@Composable
internal fun SharingLifecycle(controller: SharingController, lease: SessionLease, surface: SharingSurface,
    sharedId: String? = null, listId: String? = null, initialDay: String? = null) {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val owner = remember(controller, lease, surface, sharedId, listId) { Any() }
    DisposableEffect(controller, lease, lifecycle, surface, sharedId, listId, initialDay, owner) {
        val observer = LifecycleEventObserver { _, event -> when (event) {
            Lifecycle.Event.ON_START -> controller.activate(surface, sharedId, listId, initialDay, owner)
            Lifecycle.Event.ON_STOP -> controller.deactivate(owner)
            else -> Unit
        } }
        lifecycle.addObserver(observer)
        if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) controller.activate(surface, sharedId, listId, initialDay, owner)
        onDispose { lifecycle.removeObserver(observer); controller.deactivate(owner) }
    }
}

private data class SharingEditor(val section: SharingSection, val person: CirclePerson? = null,
    val permission: SharingPermission = SharingPermission.Summary)

@Composable
internal fun SharingScreen(controller: SharingController, lease: SessionLease,
    onMembers: (String) -> Unit, onList: (String) -> Unit, onFriends: () -> Unit) {
    val observed by controller.state.collectAsStateWithLifecycle()
    val state = observed?.takeIf { it.namespace == lease.namespace && it.surface == SharingSurface.Settings }
    val scope = rememberCoroutineScope()
    var editor by remember(lease.namespace) { mutableStateOf<SharingEditor?>(null) }
    var revoke by remember(lease.namespace) { mutableStateOf<Pair<SharingSection, SharingGrant>?>(null) }
    SharingLifecycle(controller, lease, SharingSurface.Settings)
    ScreenColumn("screen_SharingSettings", title = localized(GeneratedStrings.Sharing.title)) {
        CircleHeading(GeneratedStrings.Sharing.mine)
        Text(localized(GeneratedStrings.Sharing.mineText))
        CircleNotice(GeneratedStrings.Access.readOnly, GeneratedStrings.Access.readOnlyText)
        CircleError(state?.error, state?.serverError, "sharing_error")
        if (state == null) StatusMessage(GeneratedStrings.State.loading, "sharing_loading")
        state?.let { current ->
            SharingPending(controller, current)
            CircleQueryStatus(current.overview, "sharing_overview") { scope.launch { controller.refresh() } }
            current.overview.data?.let { overview -> overview.sections.forEach { section ->
                OutlinedCard(Modifier.fillMaxWidth().testTag("sharing_resource_${section.id}")) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        CircleHeading(sharingSectionLabel(section.name))
                        Text(localized(if (section.name == "nutrition") GeneratedStrings.Access.SectionText.nutrition else GeneratedStrings.Access.SectionText.personal))
                        val grants = sharingGrantsOf(overview, section.id)
                        if (grants.isEmpty()) Text(localized(GeneratedStrings.Access.nobody))
                        grants.forEach { grant ->
                            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(grant.name + grant.login?.let { " · @$it" }.orEmpty(), style = MaterialTheme.typography.titleMedium)
                                Text(localized(sharingPermissionLabel(grant.permission)))
                                TextButton(onClick = {
                                    controller.clearPerson()
                                    editor = SharingEditor(section, CirclePerson(grant.userId, grant.name, grant.login.orEmpty()), grant.permission)
                                }, enabled = !current.busy, modifier = Modifier.heightIn(min = 48.dp).testTag("sharing_edit_${grant.userId}")) {
                                    Text(localized(GeneratedStrings.Common.edit))
                                }
                                TextButton(onClick = { revoke = section to grant }, enabled = !current.busy,
                                    modifier = Modifier.heightIn(min = 48.dp).testTag("sharing_revoke_${grant.userId}")) {
                                    Text(localized(GeneratedStrings.Access.revoke), color = MaterialTheme.colorScheme.error)
                                }
                            }
                        }
                        OutlinedButton(onClick = { controller.clearPerson(); editor = SharingEditor(section) }, enabled = !current.busy,
                            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("sharing_grant_add_${section.id}")) {
                            Text(localized(GeneratedStrings.Access.open))
                        }
                    }
                }
            } }
            CircleHeading(GeneratedStrings.Sharing.spaces)
            Text(localized(GeneratedStrings.Sharing.spacesText))
            CircleQueryStatus(current.spaces, "sharing_spaces") { scope.launch { controller.refresh() } }
            current.activeSpaces.forEach { space -> CircleRow(space.name, localized(circleRoleLabel(space.role)), "sharing_space_${space.id}",
                badge = localized(GeneratedStrings.Sharing.membersLink)) { onMembers(space.id) } }
            if (!current.spaces.loading && current.activeSpaces.isEmpty()) Text(localized(GeneratedStrings.Sharing.spacesEmpty))
            if ("tasks.read" in current.scopes) {
                CircleHeading(GeneratedStrings.Sharing.lists)
                Text(localized(GeneratedStrings.Sharing.listsText))
                CircleQueryStatus(current.lists, "sharing_lists") { scope.launch { controller.refresh() } }
                current.sharedLists.forEach { list -> CircleRow(list.name, localized(sharingListPermissionLabel(list.effective)), "sharing_list_${list.id}") { onList(list.id) } }
                if (!current.lists.loading && current.sharedLists.isEmpty()) Text(localized(GeneratedStrings.Sharing.listsEmpty))
            }
            CircleHeading(GeneratedStrings.Sharing.people)
            CircleQueryStatus(current.relationships, "sharing_people") { scope.launch { controller.refresh() } }
            CircleRow(localized(GeneratedStrings.Circle.friends), localized(GeneratedStrings.Sharing.peopleCount(
                current.people.friends.size.toString(), current.people.incoming.size.toString(), current.people.blocked.size.toString())), "sharing_friends", onClick = onFriends)
            Text(localized(GeneratedStrings.Sharing.blockNote))
        }
    }
    editor?.let { entry ->
        SharingEditorSheet(controller, lease, entry, state, onDismiss = { if (state?.busy != true) { editor = null; controller.clearPerson() } },
            onSaved = { editor = null })
    }
    revoke?.let { (section, grant) ->
        AlertDialog(onDismissRequest = { if (state?.busy != true) revoke = null },
            title = { Text(localized(GeneratedStrings.Access.revokeTitle)) },
            text = { Text(localized(GeneratedStrings.Access.revokeText(grant.name, localized(sharingSectionLabel(section.name)).lowercase()))) },
            confirmButton = { TextButton(onClick = { scope.launch {
                val revision = controller.state.value?.overview?.data?.sections?.firstOrNull { it.id == section.id }?.revision ?: section.revision
                if (controller.revoke(section.id, grant.userId, revision)) revoke = null
            } }, enabled = state?.busy != true, modifier = Modifier.heightIn(min = 48.dp).testTag("sharing_revoke_confirm")) { Text(localized(GeneratedStrings.Access.revoke)) } },
            dismissButton = { TextButton(onClick = { revoke = null }, modifier = Modifier.heightIn(min = 48.dp)) { Text(localized(GeneratedStrings.Common.cancel)) } })
    }
}

@Composable
private fun SharingEditorSheet(controller: SharingController, lease: SessionLease, entry: SharingEditor,
    state: SharingState?, onDismiss: () -> Unit, onSaved: () -> Unit) {
    val scope = rememberCoroutineScope()
    var login by remember(lease.namespace, entry) { mutableStateOf(entry.person?.login.orEmpty()) }
    var permission by remember(lease.namespace, entry) { mutableStateOf(entry.permission) }
    var person by remember(lease.namespace, entry) { mutableStateOf(entry.person) }
    var preview by remember(lease.namespace, entry) { mutableStateOf(false) }
    var revision by remember(lease.namespace, entry) { mutableStateOf(entry.section.revision) }
    CircleSheet(localized(if (preview) GeneratedStrings.Access.checkTitle else GeneratedStrings.Access.openTitle), "sharing_editor", onDismiss,
        onBack = if (preview) { { if (state?.busy != true) preview = false } } else null) {
        if (!preview) {
            if (entry.person == null) {
                OutlinedTextField(value = login, onValueChange = { login = it }, singleLine = true,
                    label = { Text(localized(GeneratedStrings.Access.login)) }, placeholder = { Text(localized(GeneratedStrings.Access.loginExample)) },
                    modifier = Modifier.fillMaxWidth().testTag("sharing_grantee"))
                Text(localized(GeneratedStrings.Access.loginHint))
            } else Text(entry.person.name + " · @" + entry.person.login)
            CircleHeading(GeneratedStrings.Access.what)
            SharingPermission.entries.forEach { option ->
                SharingChoice(localized(sharingPermissionLabel(option)), localized(sharingPreview(entry.section.name, option)), permission == option,
                    "sharing_permission_${option.wire}") { permission = option }
            }
            CircleError(state?.error, state?.serverError, "sharing_editor_error")
            Button(onClick = { scope.launch {
                val target = entry.person ?: controller.searchPerson(login)
                if (target != null) { person = target; preview = true }
            } }, enabled = state?.busy != true && state?.searching != true,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("sharing_continue")) { Text(localized(GeneratedStrings.Common.`continue`)) }
        } else person?.let { target ->
            OutlinedCard(Modifier.fillMaxWidth().testTag("sharing_preview")) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(localized(GeneratedStrings.Access.willSee(target.name, target.login, localized(sharingSectionLabel(entry.section.name)).lowercase())))
                    Text(localized(sharingPreview(entry.section.name, permission)))
                    Text(localized(GeneratedStrings.Access.cannot))
                }
            }
            CircleError(state?.error, state?.serverError, "sharing_editor_error")
            Button(onClick = { scope.launch {
                if (controller.grant(entry.section.id, target.id, permission, revision)) onSaved()
                else {
                    revision = controller.state.value?.overview?.data?.sections?.firstOrNull { it.id == entry.section.id }?.revision ?: revision
                    preview = false; if (entry.person == null) person = null
                }
            } }, enabled = state?.busy != true,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("sharing_save")) { Text(localized(GeneratedStrings.Access.confirm)) }
        }
    }
}

@Composable
internal fun SharingChoice(title: String, description: String?, selected: Boolean, tag: String, onSelect: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onSelect).heightIn(min = 48.dp).testTag(tag), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        RadioButton(selected = selected, onClick = onSelect)
        Column(Modifier.weight(1f).padding(vertical = 12.dp)) { Text(title); description?.let { Text(it, style = MaterialTheme.typography.bodySmall) } }
    }
}

@Composable
internal fun SharingPending(controller: SharingController, state: SharingState) {
    val scope = rememberCoroutineScope()
    state.pending.forEach { entry ->
        OutlinedCard(Modifier.fillMaxWidth().testTag("sharing_pending_${entry.key}")) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                CircleHeading(GeneratedStrings.Outcome.unknownTitle)
                Text(localized(GeneratedStrings.Outcome.unknownText))
                TextButton(onClick = { scope.launch { controller.checkPending(entry.key) } }, enabled = !state.busy,
                    modifier = Modifier.heightIn(min = 48.dp).testTag("sharing_pending_check_${entry.key}")) { Text(localized(GeneratedStrings.Outcome.check)) }
                if (entry.state != SubmissionState.SENDING) TextButton(onClick = { scope.launch { controller.forgetPending(entry.key) } }, enabled = !state.busy,
                    modifier = Modifier.heightIn(min = 48.dp).testTag("sharing_pending_forget_${entry.key}")) { Text(localized(GeneratedStrings.Outcome.abandon)) }
            }
        }
    }
}

internal fun sharingSectionLabel(name: String): LocalizedString = if (name == "nutrition") GeneratedStrings.Access.Section.nutrition else GeneratedStrings.Access.Section.personal
internal fun sharingPermissionLabel(permission: SharingPermission): LocalizedString = if (permission == SharingPermission.Summary) GeneratedStrings.Access.Permission.summary else GeneratedStrings.Access.Permission.detail
internal fun sharingPreview(section: String, permission: SharingPermission): LocalizedString = when {
    section == "nutrition" && permission == SharingPermission.Summary -> GeneratedStrings.Access.Preview.Nutrition.summary
    section == "nutrition" -> GeneratedStrings.Access.Preview.Nutrition.detail
    permission == SharingPermission.Summary -> GeneratedStrings.Access.Preview.Personal.summary
    else -> GeneratedStrings.Access.Preview.Personal.detail
}
internal fun sharingListPermissionLabel(permission: String): LocalizedString = when (permission) {
    "manage" -> GeneratedStrings.Circle.Effective.manage
    "edit" -> GeneratedStrings.Circle.Effective.edit
    "view" -> GeneratedStrings.Circle.Effective.view
    else -> GeneratedStrings.Circle.Effective.none
}
