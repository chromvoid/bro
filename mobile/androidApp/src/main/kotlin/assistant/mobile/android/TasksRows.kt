package assistant.mobile.android

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import assistant.mobile.core.photos.PrivateConversationImages
import assistant.mobile.core.tasks.Task
import assistant.mobile.core.tasks.TaskDiscussionCount
import assistant.mobile.core.tasks.TaskState
import assistant.mobile.core.tasks.TasksState
import assistant.mobile.design.ui.ActionIconButton
import assistant.mobile.design.ui.Spacing
import assistant.mobile.design.R as DesignR
import java.time.LocalDate

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun TaskRow(
    task: Task,
    state: TasksState,
    onOpen: () -> Unit,
    onToggle: () -> Unit = {},
    onMove: (() -> Unit)? = null,
    onMoveUp: (() -> Unit)? = null,
    onDiscussion: (() -> Unit)? = null,
    today: String? = null,
    board: Boolean = false,
    images: PrivateConversationImages? = null,
) {
    val resources = LocalContext.current.resources
    val referenceDay = today ?: (if (board) state.board.data?.today else state.page.data?.today)
        ?: state.children.data?.today ?: LocalDate.now().toString()
    val parts = taskMeta(task, referenceDay, state.selection, state.lists.data.orEmpty()) { id, argument ->
        if (argument == null) resources.getString(id) else resources.getString(id, argument)
    }
    val done = task.state == TaskState.Done
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp)
            .clickable(role = Role.Button, onClick = onOpen)
            .testTag("tasks_item_${task.id}")
            .padding(start = Spacing.xs, end = Spacing.xs, top = Spacing.s, bottom = Spacing.s),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
    ) {
        if (state.canWrite && task.deletedAt == null) Checkbox(
            checked = done,
            onCheckedChange = { onToggle() },
            enabled = !state.busy,
            modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp)
                .testTag("task_complete_${task.id}").semantics { contentDescription = task.title },
        )
        Column(Modifier.weight(1f).padding(horizontal = Spacing.xs), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                task.title,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                color = if (done) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                textDecoration = if (done) TextDecoration.LineThrough else null,
                maxLines = if (board) 3 else 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (board) task.assignedTo?.let { id -> state.members.data.orEmpty().find { it.userId == id }?.let { member ->
                Text(member.name, style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            } }
            if (board) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                    if (parts.isNotEmpty()) TaskRowMeta(task.id, parts)
                    task.discussion?.takeIf { it.total > 0 }?.let { discussion ->
                        TaskRowDiscussion(task.id, discussion, onDiscussion ?: onOpen)
                    }
                    if (images != null) task.titleImages.firstOrNull()?.let { photo ->
                        TaskPrivatePhoto(task.id, photo.id, images, onOpen,
                            Modifier.size(40.dp).clip(MaterialTheme.shapes.small))
                    }
                }
            } else if (parts.isNotEmpty()) TaskRowMeta(task.id, parts)
        }
        if (!board && images != null) task.titleImages.firstOrNull()?.let { photo ->
            TaskPrivatePhoto(task.id, photo.id, images, onOpen,
                Modifier.size(40.dp).clip(MaterialTheme.shapes.small))
        }
        if (!board) task.discussion?.takeIf { it.total > 0 }?.let { discussion ->
            TaskRowDiscussion(task.id, discussion, onDiscussion ?: onOpen)
        }
        if (state.canWrite) {
            if (!board) onMoveUp?.let { move -> ActionIconButton(
                DesignR.drawable.lucide_arrow_up,
                stringResource(DesignR.string.tasks_move_up), move, enabled = !state.busy,
                testTag = "task_move_up_${task.id}",
            ) }
            onMove?.let { move -> ActionIconButton(
                DesignR.drawable.lucide_ellipsis,
                stringResource(DesignR.string.tasks_board_move_to), move, enabled = !state.busy,
                testTag = "task_move_${task.id}",
            ) }
        }
    }
}

@Composable
private fun TaskRowMeta(taskId: String, parts: List<TaskMetaPart>) {
    val neutral = MaterialTheme.colorScheme.onSurfaceVariant
    val warning = activityPalette().getValue("warning")
    val destructive = MaterialTheme.colorScheme.error
    fun color(part: TaskMetaPart): Color = when (part.tone) {
        TaskMetaTone.Neutral -> neutral
        TaskMetaTone.Warning -> warning
        TaskMetaTone.Destructive -> destructive
    }
    val iconSize = with(LocalDensity.current) { 14.dp.toSp() }
    val inline = parts.mapIndexedNotNull { index, part ->
        if (!part.calendar) null else "calendar_$index" to InlineTextContent(
            Placeholder(iconSize, iconSize, PlaceholderVerticalAlign.Center),
        ) {
            Icon(painterResource(DesignR.drawable.lucide_calendar_clock), contentDescription = null,
                tint = color(part), modifier = Modifier.size(14.dp))
        }
    }.toMap()
    val text = buildAnnotatedString {
        parts.forEachIndexed { index, part ->
            if (index > 0) append(" · ")
            withStyle(SpanStyle(color = color(part))) {
                if (part.calendar) { appendInlineContent("calendar_$index", " "); append(" ") }
                append(part.text)
            }
        }
    }
    Text(text, inlineContent = inline, style = MaterialTheme.typography.bodyMedium, color = neutral,
        maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.testTag("task_meta_$taskId"))
}

@Composable
private fun TaskRowDiscussion(taskId: String, discussion: TaskDiscussionCount, onClick: () -> Unit) {
    val unread = discussion.unread > 0
    val description = stringResource(
        if (unread) DesignR.string.tasks_discussion_badge_unread else DesignR.string.tasks_discussion_badge_messages,
        discussion.total.toString(), discussion.unread.toString(),
    )
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(percent = 50),
        color = if (unread) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceVariant,
        contentColor = if (unread) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.minimumInteractiveComponentSize().height(26.dp)
            .testTag("task_discussion_count_$taskId")
            .semantics(mergeDescendants = true) { contentDescription = description },
    ) {
        Row(Modifier.padding(horizontal = Spacing.s), horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
            verticalAlignment = Alignment.CenterVertically) {
            Icon(painterResource(DesignR.drawable.lucide_message_circle), contentDescription = null, modifier = Modifier.size(14.dp))
            Text(discussion.total.toString(), style = MaterialTheme.typography.labelSmall,
                fontWeight = if (unread) FontWeight.SemiBold else FontWeight.Normal,
                modifier = Modifier.clearAndSetSemantics {})
            if (unread) Box(Modifier.size(6.dp).background(MaterialTheme.colorScheme.primary, CircleShape))
        }
    }
}
