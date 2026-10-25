package assistant.mobile.core.tasks

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Synthetic cases from web taskFilterState.test.ts, api.test.ts and taskWorkspace.test.ts. */
class TaskModelsTest {
    @Test
    fun revisionWinsWhileTheFreshestReadOwnsDiscussionCounters() {
        val old = taskCopy("task", revision = 2).copy(discussion = TaskDiscussionCount(5, 2))
        val fresh = old.copy(revision = 4, title = "Новое название", discussion = null)
        val read = old.copy(discussion = TaskDiscussionCount(5, 0))
        assertEquals(fresh.copy(discussion = read.discussion), newestTask(old, fresh, read))
        assertEquals(fresh.copy(discussion = old.discussion), newestTask(fresh, old))
        assertEquals(old, newestTask(old, old.copy(title = "Другая копия")))
        assertEquals(old, newestTask(old, null, read.copy(id = "unrelated")))
        assertEquals(fresh, newestTask(null, fresh))
        assertNull(newestTask(null, null))
    }

}

private fun taskCopy(id: String, revision: Long = 1, parentId: String? = null, listId: String = "list") =
    Task(id, id, "open", revision, listId = listId, parentId = parentId)
