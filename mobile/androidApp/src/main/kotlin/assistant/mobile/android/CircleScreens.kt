package assistant.mobile.android

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import assistant.mobile.core.GeneratedStrings
import assistant.mobile.core.LocalizedString
import assistant.mobile.core.SessionLease
import assistant.mobile.core.circle.*
import kotlinx.coroutines.launch

@Composable
internal fun CircleScreen(controller: CircleController, lease: SessionLease, onFriends: () -> Unit,
    onSpace: (String) -> Unit, onChannel: (String) -> Unit, onShared: () -> Unit = {}) {
    val observed by controller.state.collectAsStateWithLifecycle()
    val state = observed?.takeIf { it.namespace == lease.namespace }
    val scope = rememberCoroutineScope()
    var creating by remember(lease.namespace) { mutableStateOf(false) }
    var invitation by remember(lease.namespace) { mutableStateOf<CircleInvitation?>(null) }
    var name by remember(lease.namespace) { mutableStateOf("") }
    CircleLifecycle(controller, lease)
    RefreshableScreen(
        tag = "screen_Circle",
        title = localized(GeneratedStrings.Circle.title),
        isRefreshing = state?.spaces?.let { it.loading && it.data != null } == true,
        onRefresh = { scope.launch { controller.refresh() } },
    ) {
        if (state == null) StatusMessage(GeneratedStrings.State.loading, "circle_loading")
        state?.let { current ->
            CircleError(current.error, current.serverError)
            CircleQueryStatus(current.spaces, "circle_spaces") { scope.launch { controller.refresh() } }
            current.spaces.data?.let { spaces ->
                if (spaces.invitations.isNotEmpty()) CircleHeading(GeneratedStrings.Circle.invitations)
                spaces.invitations.forEach { item ->
                    CircleRow(item.name, localized(item.inviterName?.let { GeneratedStrings.Circle.invitedBy(it) }
                        ?: GeneratedStrings.Circle.invited), "circle_invitation_${item.spaceId}", onClick = { invitation = item })
                }
                val directUnread = current.conversations.filter { it.kind == ChannelKind.Direct }.sumOf { it.unread }
                CircleRow(localized(GeneratedStrings.Circle.friends), localized(GeneratedStrings.Circle.friendsText),
                    "circle_friends", badge = directUnread.takeIf { it > 0 }?.toString(), onClick = onFriends)
                CircleRow(localized(GeneratedStrings.Shared.title), localized(GeneratedStrings.Circle.sharedText),
                    "circle_shared", onClick = onShared)
                CircleHeading(GeneratedStrings.Circle.spaces)
                TextButton(onClick = { creating = true }, enabled = !current.busy,
                    modifier = Modifier.heightIn(min = 48.dp).testTag("circle_create_space")) { Text(localized(GeneratedStrings.Circle.create)) }
                if (current.activeSpaces.isEmpty()) Text(localized(GeneratedStrings.Circle.noSpaces))
                current.activeSpaces.forEach { space ->
                    val unread = current.conversations.filter { it.spaceId == space.id }.sumOf { it.unread }
                    CircleRow(space.name, localized(circleRoleLabel(space.role)), "circle_space_${space.id}",
                        badge = unread.takeIf { it > 0 }?.toString(), onClick = { onSpace(space.id) })
                }
            }
            CircleQueryStatus(current.channels, "circle_channels") { scope.launch { controller.refresh() } }
            // Existing conversations remain directly reachable, including resource-scoped channels.
            current.conversations.forEach { channel ->
                val spaceName = current.spaces.data?.spaces?.firstOrNull { it.id == channel.spaceId }?.name
                val audience = audienceOf(channel, spaceName)
                CircleRow(audience.title, localized(if (audience.kind == ChannelKind.Direct)
                    GeneratedStrings.Circle.onlyYouAnd(audience.title) else GeneratedStrings.Circle.everyoneIn(audience.title)),
                    "circle_channel_${channel.id}", badge = channel.unread.takeIf { it > 0 }?.toString(), onClick = { onChannel(channel.id) })
            }
        }
    }
    if (creating) CircleSheet(localized(GeneratedStrings.Circle.newSpace), "circle_create_dialog", onDismiss = { creating = false }) {
        OutlinedTextField(value = name, onValueChange = { name = it.take(100) }, singleLine = true,
            label = { Text(localized(GeneratedStrings.Circle.spaceName)) }, placeholder = { Text(localized(GeneratedStrings.Circle.spaceExample)) },
            modifier = Modifier.fillMaxWidth().testTag("circle_space_name"))
        CircleNotice(GeneratedStrings.Circle.historyShared, GeneratedStrings.Circle.historySharedText)
        CircleError(state?.error, state?.serverError)
        Button(onClick = { scope.launch { controller.createSpace(name.trim())?.let { id -> creating = false; name = ""; onSpace(id) } } },
            enabled = name.isNotBlank() && state?.busy != true, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("circle_create_confirm")) {
            Text(localized(GeneratedStrings.Circle.create))
        }
    }
    invitation?.let { item ->
        CircleSheet(item.name, "circle_invitation_dialog", onDismiss = { invitation = null }) {
            item.inviterName?.let { Text(localized(GeneratedStrings.Circle.invitedBy(it))) }
            CircleNotice(GeneratedStrings.Circle.afterJoin, GeneratedStrings.Circle.afterJoinText)
            CircleError(state?.error, state?.serverError)
            Button(onClick = { scope.launch { if (controller.joinSpace(item.spaceId)) { invitation = null; onSpace(item.spaceId) } } },
                enabled = state?.busy != true, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("circle_join_confirm")) {
                Text(localized(GeneratedStrings.Circle.join))
            }
            TextButton(onClick = { invitation = null }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("circle_invitation_later")) {
                Text(localized(GeneratedStrings.Circle.later))
            }
        }
    }
}

private data class CirclePersonCard(val id: String, val name: String, val login: String?)

@Composable
internal fun CircleFriendsScreen(controller: CircleController, lease: SessionLease, onChannel: (String) -> Unit) {
    val observed by controller.state.collectAsStateWithLifecycle()
    val state = observed?.takeIf { it.namespace == lease.namespace }
    val scope = rememberCoroutineScope()
    var login by remember(lease.namespace) { mutableStateOf("") }
    var person by remember(lease.namespace) { mutableStateOf<CirclePersonCard?>(null) }
    var label by remember(lease.namespace, person?.id) { mutableStateOf("") }
    CircleLifecycle(controller, lease)
    ScreenColumn("screen_CircleFriends", title = localized(GeneratedStrings.Circle.friends)) {
        OutlinedTextField(value = login, onValueChange = { login = it }, singleLine = true,
            label = { Text(localized(GeneratedStrings.Circle.exactLogin)) }, keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None),
            modifier = Modifier.fillMaxWidth().testTag("circle_search_login"))
        Button(onClick = { scope.launch { controller.searchPerson(login)?.let { person = CirclePersonCard(it.id, it.name, it.login) } } },
            enabled = state?.searching != true && login.isNotBlank(), modifier = Modifier.heightIn(min = 48.dp).testTag("circle_find")) {
            Text(localized(GeneratedStrings.Circle.find))
        }
        CircleError(state?.error, state?.serverError)
        if (state == null) StatusMessage(GeneratedStrings.State.loading, "circle_friends_loading")
        state?.let { current ->
            CircleQueryStatus(current.relationships, "circle_relationships") { scope.launch { controller.refresh() } }
            if (current.relationships.data?.isEmpty() == true) {
                CircleHeading(GeneratedStrings.Circle.noFriends)
                Text(localized(GeneratedStrings.Circle.noFriendsText))
            }
            val groups = listOf(GeneratedStrings.Circle.Group.incoming to current.groups.incoming,
                GeneratedStrings.Circle.Group.friends to current.groups.friends,
                GeneratedStrings.Circle.Group.outgoing to current.groups.outgoing,
                GeneratedStrings.Circle.Group.blocked to current.groups.blocked)
            groups.filter { it.second.isNotEmpty() }.forEach { (title, people) ->
                CircleHeading(title)
                people.forEach { item ->
                    CircleRow(item.name + item.login?.let { " · @$it" }.orEmpty(), relationshipDescription(item, lease.namespace.userId),
                        "circle_person_${item.userId}", onClick = {
                            person = CirclePersonCard(item.userId, item.name, item.login); label = item.label.orEmpty()
                        })
                }
            }
        }
    }
    person?.let { card ->
        val relationship = state?.relationships?.data?.firstOrNull { it.userId == card.id }
        LaunchedEffect(card.id) { label = relationship?.label.orEmpty() }
        CircleSheet(card.name, "circle_person_dialog", onDismiss = { person = null }) {
            card.login?.let { Text("@$it") }
            Text(relationship?.let { relationshipDescription(it, lease.namespace.userId) } ?: localized(GeneratedStrings.Circle.notConnected),
                modifier = Modifier.testTag("circle_relation_state"))
            CircleError(state?.error, state?.serverError)
            fun change(action: RelationshipAction, value: String = "") { scope.launch { controller.relationship(card.id, action, value) } }
            if (relationship == null) CircleAction(GeneratedStrings.Circle.addFriend, "circle_person_request", state?.busy != true) { change(RelationshipAction.Request) }
            if (relationship?.state == RelationshipState.Pending && relationship.recipientId == lease.namespace.userId)
                CircleAction(GeneratedStrings.Circle.accept, "circle_person_accept", state?.busy != true) { change(RelationshipAction.Accept) }
            if (relationship?.state == RelationshipState.Accepted) {
                CircleAction(GeneratedStrings.Circle.write, "circle_person_write", state?.busy != true) {
                    scope.launch { controller.openDirect(card.id)?.let { person = null; onChannel(it) } }
                }
                OutlinedTextField(value = label, onValueChange = { label = it.take(80) }, singleLine = true,
                    label = { Text(localized(GeneratedStrings.Circle.label)) }, placeholder = { Text(localized(GeneratedStrings.Circle.labelExample)) },
                    modifier = Modifier.fillMaxWidth().testTag("circle_person_label"))
                CircleAction(GeneratedStrings.Common.save, "circle_person_label_save", state?.busy != true) { change(RelationshipAction.Label, label.trim()) }
            }
            if (relationship?.state == RelationshipState.Blocked) {
                CircleNotice(GeneratedStrings.Circle.blockedTitle(card.name), GeneratedStrings.Circle.blockedText, "circle_blocked")
                CircleAction(GeneratedStrings.Circle.unblock, "circle_person_unblock", state?.busy != true) { change(RelationshipAction.Unblock) }
            } else CircleAction(GeneratedStrings.Circle.block, "circle_person_block", state?.busy != true, destructive = true) { change(RelationshipAction.Block) }
            if (relationship != null && relationship.state != RelationshipState.Blocked) CircleAction(
                if (relationship.state != RelationshipState.Pending) GeneratedStrings.Circle.remove
                else if (relationship.recipientId == lease.namespace.userId) GeneratedStrings.Circle.decline else GeneratedStrings.Circle.cancelRequest,
                "circle_person_remove", state?.busy != true) { change(RelationshipAction.Remove) }
            CircleReportButton(controller, lease, ReportTarget.User, card.id)
        }
    }
}

@Composable
private fun relationshipDescription(item: CircleRelationship, userId: String): String = when (item.state) {
    RelationshipState.Pending -> localized(if (item.recipientId == userId) GeneratedStrings.Circle.requestIn else GeneratedStrings.Circle.requestOut)
    RelationshipState.Blocked -> localized(GeneratedStrings.Circle.blocked)
    RelationshipState.Accepted -> item.label?.takeIf { it.isNotEmpty() } ?: localized(GeneratedStrings.Circle.friend)
}

@Composable
internal fun CircleSpaceScreen(controller: CircleController, lease: SessionLease, spaceId: String, onMembers: () -> Unit,
    onChannel: (String) -> Unit, onList: (String) -> Unit, onToCircle: () -> Unit, onToAssistant: () -> Unit,
    onChallenges: () -> Unit = {}, canReadChallenges: Boolean = true) {
    val observed by controller.state.collectAsStateWithLifecycle()
    val state = observed?.takeIf { it.namespace == lease.namespace && it.spaceId == spaceId }
    val scope = rememberCoroutineScope()
    var creatingList by remember(lease.namespace, spaceId) { mutableStateOf(false) }
    var listName by remember(lease.namespace, spaceId) { mutableStateOf("") }
    CircleLifecycle(controller, lease, spaceId = spaceId)
    ScreenColumn("screen_CircleSpace", title = state?.space?.name ?: localized(GeneratedStrings.Circle.space)) {
        if (state?.gone == true) { CircleGone(onToCircle, onToAssistant); return@ScreenColumn }
        if (state == null) StatusMessage(GeneratedStrings.State.loading, "circle_space_loading")
        state?.let { current ->
            CircleQueryStatus(current.spaces, "circle_spaces") { scope.launch { controller.refresh() } }
            CircleQueryStatus(current.members, "circle_members") { scope.launch { controller.refresh() } }
            CircleQueryStatus(current.channels, "circle_channels") { scope.launch { controller.refresh() } }
            CircleQueryStatus(current.lists, "circle_lists") { scope.launch { controller.refresh() } }
            CircleError(current.error, current.serverError)
            val members = current.members.data.orEmpty()
            if (members.isNotEmpty()) {
                Text(localized(GeneratedStrings.Circle.membersCount(members.size.toDouble(), members.size.toString())))
                Text(members.take(6).joinToString(" · ") { it.name })
            }
            current.spaceChat?.let { chat ->
                CircleRow(localized(GeneratedStrings.Circle.sharedChat), localized(if (chat.unread > 0)
                    GeneratedStrings.Circle.newMessages(chat.unread.toDouble(), chat.unread.toString()) else GeneratedStrings.Circle.everyoneSees),
                    "circle_space_chat", badge = chat.unread.takeIf { it > 0 }?.toString(), onClick = { onChannel(chat.id) })
            }
            current.spaceLists.forEach { list ->
                val permission = when (list.effective) {
                    "manage" -> GeneratedStrings.Circle.Effective.manage
                    "edit" -> GeneratedStrings.Circle.Effective.edit
                    "view" -> GeneratedStrings.Circle.Effective.view
                    else -> GeneratedStrings.Circle.Effective.none
                }
                CircleRow(list.name, localized(permission), "circle_list_${list.id}", onClick = { onList(list.id) })
            }
            if (canReadChallenges) CircleRow(localized(GeneratedStrings.Circle.challenges), localized(GeneratedStrings.Circle.challengesText),
                "circle_space_challenges", onClick = onChallenges)
            current.space?.let { space ->
                val role = localized(circleRoleLabel(space.role)).lowercase()
                CircleRow(localized(GeneratedStrings.Circle.membersAndAccess), localized(GeneratedStrings.Circle.youAre(role)),
                    "circle_space_members", onClick = onMembers)
            }
            // Same account capability as the task workspace; space permissions are enforced on POST.
            if (current.canWriteTasks) CircleAction(GeneratedStrings.Circle.newList, "circle_new_list", !current.busy) { creatingList = true }
        }
    }
    if (creatingList && state?.gone != true) CircleSheet(localized(GeneratedStrings.Circle.newList), "circle_list_dialog", onDismiss = { creatingList = false }) {
        OutlinedTextField(value = listName, onValueChange = { listName = it.take(100) }, singleLine = true,
            label = { Text(localized(GeneratedStrings.Circle.listName)) }, placeholder = { Text(localized(GeneratedStrings.Circle.listExample)) },
            modifier = Modifier.fillMaxWidth().testTag("circle_list_name"))
        CircleError(state?.error, state?.serverError)
        CircleAction(GeneratedStrings.Circle.createList, "circle_list_create", listName.isNotBlank() && state?.busy != true) {
            scope.launch { if (controller.createList(spaceId, listName.trim())) { creatingList = false; listName = "" } }
        }
    }
}

@Composable
internal fun CircleMembersScreen(controller: CircleController, lease: SessionLease, spaceId: String,
    onToCircle: () -> Unit, onToAssistant: () -> Unit) {
    val observed by controller.state.collectAsStateWithLifecycle()
    val state = observed?.takeIf { it.namespace == lease.namespace && it.spaceId == spaceId }
    val scope = rememberCoroutineScope()
    var selected by remember(lease.namespace, spaceId) { mutableStateOf<CircleMember?>(null) }
    var role by remember(lease.namespace, selected?.userId) { mutableStateOf(CircleRole.Member) }
    var inviting by remember(lease.namespace, spaceId) { mutableStateOf(false) }
    var inviteLogin by remember(lease.namespace, spaceId) { mutableStateOf("") }
    var invitePerson by remember(lease.namespace, spaceId) { mutableStateOf<CirclePerson?>(null) }
    var inviteError by remember(lease.namespace, spaceId) { mutableStateOf<LocalizedString?>(null) }
    var leaving by remember(lease.namespace, spaceId) { mutableStateOf(false) }
    var archiving by remember(lease.namespace, spaceId) { mutableStateOf(false) }
    CircleLifecycle(controller, lease, spaceId = spaceId)
    LaunchedEffect(state?.gone) { if (state?.gone == true) { selected = null; inviting = false; leaving = false; archiving = false } }
    val members = state?.members?.data.orEmpty()
    val me = state?.currentMember
    val can = me?.let { abilities(it.role) }
    ScreenColumn("screen_CircleMembers", title = localized(GeneratedStrings.Circle.members)) {
        if (state?.gone == true) { CircleGone(onToCircle, onToAssistant); return@ScreenColumn }
        state?.space?.name?.let { Text(it, style = MaterialTheme.typography.titleMedium) }
        if (state == null) StatusMessage(GeneratedStrings.State.loading, "circle_members_loading")
        state?.let { current ->
            CircleQueryStatus(current.members, "circle_members") { scope.launch { controller.refresh() } }
            CircleError(current.error, current.serverError)
            members.forEach { member ->
                val mine = member.userId == lease.namespace.userId
                CircleRow(member.name + if (mine) " · ${localized(GeneratedStrings.Circle.you).lowercase()}" else "", member.login?.let { "@$it" },
                    "circle_member_${member.userId}", badge = localized(circleRoleLabel(member.role)),
                    enabled = me != null && (can?.changeRoles == true || mayRemove(me, member)), onClick = { selected = member; role = member.role })
            }
            if (can?.invite == true) CircleAction(GeneratedStrings.Circle.invite, "circle_invite", !current.busy) {
                inviting = true; invitePerson = null; inviteError = null
            }
            if (current.members.data != null) {
                CircleNotice(GeneratedStrings.Circle.historyShared, GeneratedStrings.Circle.historySharedText)
                if (me != null && soleOwner(members, me.userId)) Text(localized(GeneratedStrings.Circle.soleOwner(current.space?.name.orEmpty())),
                    modifier = Modifier.testTag("circle_sole_owner"))
                else if (me != null) CircleAction(GeneratedStrings.Circle.leave, "circle_leave", !current.busy, destructive = true) { leaving = true }
                if (can?.archive == true) CircleAction(GeneratedStrings.Circle.archive, "circle_archive", !current.busy, destructive = true) { archiving = true }
            }
        }
    }
    if (inviting && state?.gone != true) CircleSheet(localized(GeneratedStrings.Circle.inviteTitle(state?.space?.name.orEmpty())),
        "circle_invite_dialog", onDismiss = { inviting = false },
        onBack = if (invitePerson != null) ({ invitePerson = null }) else null) {
        if (invitePerson == null) {
            OutlinedTextField(value = inviteLogin, onValueChange = { inviteLogin = it }, singleLine = true,
                label = { Text(localized(GeneratedStrings.Access.login)) }, placeholder = { Text(localized(GeneratedStrings.Access.loginExample)) },
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None),
                modifier = Modifier.fillMaxWidth().testTag("circle_invite_login"))
            inviteError?.let { ErrorMessage(it, "circle_invite_error") }
            CircleError(state?.error, state?.serverError)
            CircleAction(GeneratedStrings.Common.`continue`, "circle_invite_find", inviteLogin.isNotBlank() && state?.searching != true) {
                scope.launch {
                    inviteError = null
                    controller.searchPerson(inviteLogin)?.let { person ->
                        if (members.any { it.userId == person.id }) inviteError = GeneratedStrings.Circle.alreadyMember
                        else invitePerson = person
                    }
                }
            }
        } else invitePerson?.let { person ->
            CircleHeadingTextContent("${person.name} · @${person.login}")
            Text(localized(GeneratedStrings.Circle.inviteText))
            CircleError(state?.error, state?.serverError)
            CircleAction(GeneratedStrings.Circle.sendInvite, "circle_invite_send", state?.busy != true) {
                scope.launch { if (controller.invite(spaceId, person.id)) { inviting = false; invitePerson = null; inviteLogin = "" } }
            }
        }
    }
    selected?.takeIf { state?.gone != true }?.let { person ->
        CircleSheet(person.name, "circle_member_dialog", onDismiss = { selected = null }) {
            state?.space?.name?.let { Text(it) }
            CircleError(state?.error, state?.serverError)
            if (can?.changeRoles == true) {
                CircleHeading(GeneratedStrings.Circle.roleTitle)
                CircleRole.entries.reversed().forEach { option ->
                    Row(Modifier.fillMaxWidth().selectable(selected = role == option, role = Role.RadioButton,
                        onClick = { role = option }).padding(vertical = 8.dp).testTag("circle_member_role_${option.wire}")) {
                        RadioButton(selected = role == option, onClick = null)
                        Column(Modifier.weight(1f).padding(start = 8.dp)) {
                            Text(localized(circleRoleLabel(option)), style = MaterialTheme.typography.titleSmall)
                            Text(localized(circleRoleDescription(option)))
                        }
                    }
                }
                if (role == CircleRole.Owner && person.role != CircleRole.Owner) Text(localized(GeneratedStrings.Circle.ownerWarning))
                CircleAction(GeneratedStrings.Circle.saveRole, "circle_member_save_role", role != person.role && state?.busy != true &&
                    !(role != CircleRole.Owner && soleOwner(members, person.userId))) {
                    scope.launch { if (controller.changeRole(spaceId, person.userId, role)) selected = null }
                }
            }
            if (me != null && person.userId != me.userId && mayRemove(me, person))
                CircleAction(GeneratedStrings.Circle.removeMember, "circle_member_remove", state?.busy != true, destructive = true) {
                    scope.launch { if (controller.changeRole(spaceId, person.userId, null)) selected = null }
                }
        }
    }
    if (leaving) CircleConfirm(localized(GeneratedStrings.Circle.leaveTitle), localized(GeneratedStrings.Circle.leaveText(state?.space?.name.orEmpty())),
        GeneratedStrings.Circle.leave, GeneratedStrings.Circle.stay, "circle_leave_confirm", state?.busy != true,
        onDismiss = { leaving = false }, error = state?.error, serverError = state?.serverError) {
        scope.launch { if (controller.changeRole(spaceId, lease.namespace.userId, null)) { leaving = false; onToCircle() } }
    }
    if (archiving) CircleConfirm(localized(GeneratedStrings.Circle.archiveTitle(state?.space?.name.orEmpty())), localized(GeneratedStrings.Circle.archiveText),
        GeneratedStrings.Circle.archive, GeneratedStrings.Circle.keep, "circle_archive_confirm", state?.busy != true,
        onDismiss = { archiving = false }, error = state?.error, serverError = state?.serverError) {
        scope.launch { if (controller.archiveSpace(spaceId)) { archiving = false; onToCircle() } }
    }
}

@Composable
private fun CircleHeadingTextContent(title: String) { Text(title, style = MaterialTheme.typography.titleMedium) }

@Composable
internal fun CircleAction(label: LocalizedString, tag: String, enabled: Boolean = true, destructive: Boolean = false, onClick: () -> Unit) {
    OutlinedButton(onClick = onClick, enabled = enabled, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag(tag)) {
        Text(localized(label), color = if (destructive && enabled) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
    }
}

@Composable
private fun CircleConfirm(title: String, text: String, confirm: LocalizedString, dismiss: LocalizedString,
    tag: String, enabled: Boolean, onDismiss: () -> Unit, error: LocalizedString? = null, serverError: String? = null, onConfirm: () -> Unit) {
    AlertDialog(onDismissRequest = onDismiss, title = { Text(title) }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) { Text(text); CircleError(error, serverError) }
    },
        confirmButton = { TextButton(onClick = onConfirm, enabled = enabled, modifier = Modifier.heightIn(min = 48.dp).testTag(tag)) {
            Text(localized(confirm), color = MaterialTheme.colorScheme.error)
        } }, dismissButton = { TextButton(onClick = onDismiss, enabled = enabled, modifier = Modifier.heightIn(min = 48.dp)) { Text(localized(dismiss)) } })
}
