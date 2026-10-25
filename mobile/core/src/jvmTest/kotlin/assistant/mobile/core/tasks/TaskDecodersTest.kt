package assistant.mobile.core.tasks

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertNull

/** Sanitized shapes from web api/history tests and server TasksHTTP, SharedTracker and BoardPages. */
class TaskDecodersTest {
    @Test
    fun discussionIsBoundToItsTaskChannelAndDoesNotInventAHumanActorForTheAgent() {
        val discussion = decodeTaskDiscussion(taskFixture("discussion"), "task-channel")
        assertEquals("task-channel", discussion.channelId)
        assertEquals("owner", discussion.messages[0].actorUserId)
        assertEquals("Материалы привезут утром", discussion.messages[0].text)
        assertNull(discussion.messages[1].actorUserId)
        assertEquals("assistant", discussion.messages[1].author)
        assertEquals("synthetic-messages-next", discussion.nextCursor)
        assertFails { decodeTaskDiscussion(taskFixture("discussion"), "unrelated-channel") }
    }
}

internal fun taskFixture(name: String): ByteArray = requireNotNull(TaskDecodersTest::class.java.getResourceAsStream("/tasks/$name.json"))
    .use { it.readBytes() }
