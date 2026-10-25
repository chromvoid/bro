package assistant.mobile.core.assistant

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertFailsWith

class AssistantSearchTest {
    @Test
    fun boundsUseInclusiveAccountCalendarDaysAndRejectInvalidRanges() {
        assertEquals(AssistantSearchBounds(null, null), assistantSearchBounds("", ""))
        assertEquals(AssistantSearchBounds("2026-12-31", "2027-01-01"), assistantSearchBounds("2026-12-31", "2026-12-31"))
        assertEquals(AssistantSearchBounds(null, "2024-03-01"), assistantSearchBounds("", "2024-02-29"))
        assertNull(assistantSearchBounds("2026-02-29", ""))
        assertNull(assistantSearchBounds("2026-10-17", "2026-10-16"))
        assertNull(assistantSearchBounds("2026-1-1", ""))
    }

    @Test
    fun searchWireValidatesOpaqueCursorAuthorsAndTimestamps() {
        val page = decodeAssistantSearch("""{"messages":[{"id":"reply","author":"assistant","occurred_at":"2026-10-16T00:00:00Z","text":"Synthetic"}],"cursor":"opaque+/=?"}""".encodeToByteArray())
        assertEquals("reply", page.messages.single().id)
        assertEquals("opaque+/=?", page.cursor)
        for (invalid in listOf(
            """{"messages":[],"cursor":""}""",
            """{"messages":[{"id":"a","author":"future","occurred_at":"2026-10-16T00:00:00Z","text":"Synthetic"}],"cursor":null}""",
            """{"messages":[{"id":"a","author":"user","occurred_at":"yesterday","text":"Synthetic"}],"cursor":null}""",
        )) assertFailsWith<IllegalArgumentException> { decodeAssistantSearch(invalid.encodeToByteArray()) }
    }
}
