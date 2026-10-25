package assistant.mobile.core.assistant

/** A mark includes the oldest visible range so that pages loaded above it never count as new. */
data class AssistantSeenMark(val replies: Set<String>, val from: String?)

fun assistantSeenMark(messages: List<AssistantMessage>): AssistantSeenMark = AssistantSeenMark(
    messages.flatMap { message -> message.turns.filter { it.state == "completed" }.map { it.id } }.toSet(),
    messages.firstOrNull()?.id,
)

fun assistantUnseenReplies(messages: List<AssistantMessage>, mark: AssistantSeenMark): Int {
    val start = messages.indexOfFirst { it.id == mark.from }.coerceAtLeast(0)
    return messages.drop(start).sumOf { message ->
        message.turns.count { it.state == "completed" && it.id !in mark.replies }
    }
}

data class AssistantFollowState(
    val following: Boolean = true,
    val atBottom: Boolean = true,
    val held: Boolean = false,
    val unseen: Int = 0,
    val seen: AssistantSeenMark? = null,
) {
    fun position(atBottom: Boolean, userScrolled: Boolean, messages: List<AssistantMessage>): AssistantFollowState {
        if (!userScrolled) return copy(atBottom = atBottom)
        if (atBottom) return AssistantFollowState(seen = assistantSeenMark(messages))
        return copy(following = false, atBottom = false, held = false, seen = seen ?: assistantSeenMark(messages))
    }

    fun updated(messages: List<AssistantMessage>): AssistantFollowState =
        if (following && !held) copy(unseen = 0, seen = assistantSeenMark(messages))
        else copy(unseen = seen?.let { assistantUnseenReplies(messages, it) } ?: 0)

    fun hold(messages: List<AssistantMessage>) = copy(
        following = false, held = true, unseen = 0, seen = assistantSeenMark(messages),
    )
}
