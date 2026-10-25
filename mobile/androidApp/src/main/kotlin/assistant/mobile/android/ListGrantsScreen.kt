package assistant.mobile.android

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import assistant.mobile.core.GeneratedStrings
import assistant.mobile.core.SessionLease
import assistant.mobile.core.sharing.*
import kotlinx.coroutines.launch

@Composable
internal fun ListGrantsScreen(controller: SharingController, lease: SessionLease, listId: String) {
    val observed by controller.state.collectAsStateWithLifecycle()
    val state = observed?.takeIf { it.namespace == lease.namespace && it.surface == SharingSurface.ListGrants && it.listId == listId }
    val scope = rememberCoroutineScope()
    var memberAccess by remember(lease.namespace, listId) { mutableStateOf("edit") }
    var userId by remember(lease.namespace, listId) { mutableStateOf("") }
    var permission by remember(lease.namespace, listId) { mutableStateOf(ListPermission.View) }
    SharingLifecycle(controller, lease, SharingSurface.ListGrants, listId = listId)
    LaunchedEffect(state?.grants?.data?.resource?.memberAccess) { state?.grants?.data?.resource?.memberAccess?.let { memberAccess = it } }
    ScreenColumn("screen_ListGrants", title = localized(GeneratedStrings.Circle.listAccess)) {
        state?.list?.let { Text(it.name, style = MaterialTheme.typography.titleMedium) }
        CircleError(state?.error, state?.serverError, "sharing_error")
        if (state == null) StatusMessage(GeneratedStrings.State.loading, "list_grants_loading")
        state?.let { current ->
            if (current.gone) CircleNotice(GeneratedStrings.Circle.goneTitle, GeneratedStrings.Circle.goneText, "list_grants_gone")
            else {
                SharingPending(controller, current)
                CircleQueryStatus(current.lists, "list_access_list") { scope.launch { controller.refresh() } }
                CircleQueryStatus(current.grants, "list_grants") { scope.launch { controller.refresh() } }
                current.grants.data?.let { grants ->
                    CircleHeading(GeneratedStrings.Circle.defaultAccess)
                    listOf("view", "edit").forEach { option ->
                        SharingChoice(localized(if (option == "view") GeneratedStrings.Circle.MemberAccess.view else GeneratedStrings.Circle.MemberAccess.edit),
                            localized(listPermissionDescription(if (option == "view") ListPermission.View else ListPermission.Edit)), memberAccess == option,
                            "list_default_$option") { memberAccess = option }
                    }
                    Button(onClick = { scope.launch { controller.saveDefault(memberAccess, grants.resource.revision) } },
                        enabled = !current.busy && memberAccess != grants.resource.memberAccess,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("list_default_save")) { Text(localized(GeneratedStrings.Common.save)) }
                    CircleHeading(GeneratedStrings.Circle.personalRights)
                    if (grants.grants.isEmpty()) Text(localized(GeneratedStrings.Circle.noGrants))
                    grants.grants.forEach { grant ->
                        OutlinedCard(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(grant.name); Text(localized(listPermissionLabel(grant.permission)))
                                TextButton(onClick = { userId = grant.userId; permission = grant.permission }, enabled = !current.busy,
                                    modifier = Modifier.heightIn(min = 48.dp).testTag("list_grant_edit_${grant.userId}")) { Text(localized(GeneratedStrings.Common.edit)) }
                                TextButton(onClick = { scope.launch { controller.grantList(grant.userId, null, grants.resource.revision) } }, enabled = !current.busy,
                                    modifier = Modifier.heightIn(min = 48.dp).testTag("list_grant_revoke_${grant.userId}")) { Text(localized(GeneratedStrings.Access.revoke)) }
                            }
                        }
                    }
                    CircleHeading(GeneratedStrings.Circle.chooseMember)
                    CircleQueryStatus(current.members, "list_grant_members") { scope.launch { controller.refresh() } }
                    current.grantable.forEach { member -> SharingChoice(member.name, member.login?.let { "@$it" }, userId == member.userId,
                        "list_member_${member.userId}") { userId = member.userId } }
                    CircleHeading(GeneratedStrings.Circle.levelTitle)
                    ListPermission.entries.forEach { option ->
                        SharingChoice(localized(listPermissionLabel(option)), null, permission == option, "list_permission_${option.wire}") { permission = option }
                    }
                    Text(localized(listPermissionDescription(permission)))
                    Button(onClick = { scope.launch {
                        if (controller.grantList(userId, permission, grants.resource.revision)) userId = ""
                    } }, enabled = userId.isNotEmpty() && !current.busy && current.grantable.any { it.userId == userId },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("list_grant_save")) { Text(localized(GeneratedStrings.Circle.grant)) }
                }
            }
        }
    }
}

private fun listPermissionLabel(permission: ListPermission) = when (permission) {
    ListPermission.View -> GeneratedStrings.Circle.Permission.view
    ListPermission.Edit -> GeneratedStrings.Circle.Permission.edit
    ListPermission.Manage -> GeneratedStrings.Circle.Permission.manage
}
private fun listPermissionDescription(permission: ListPermission) = when (permission) {
    ListPermission.View -> GeneratedStrings.Circle.Level.view
    ListPermission.Edit -> GeneratedStrings.Circle.Level.edit
    ListPermission.Manage -> GeneratedStrings.Circle.Level.manage
}
