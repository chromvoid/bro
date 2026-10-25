package assistant.mobile.core.dictation

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DictationDraftTest {
    @Test
    fun recognizedSuffixUsesExistingWhitespaceAndPreservesOriginalText() {
        for ((original, expected) in mapOf("" to "dictated", "typed" to "typed dictated",
            "typed " to "typed dictated", "typed\n" to "typed\ndictated")) {
            val snapshot = DictationDraftSnapshot(original, 1)
            val result = DictationDraft(snapshot).final(snapshot, "dictated")
            assertEquals(expected, result.text)
            assertFalse(result.preserved)
        }
    }

    @Test
    fun revisionAndTextBothGuardManualChanges() {
        val original = DictationDraftSnapshot("typed", 5)
        val guard = DictationDraft(original)
        for (current in listOf(original.copy(text = "edited"), original.copy(revision = 6))) {
            val result = guard.final(current, "dictated")
            assertEquals(current.text, result.text)
            assertTrue(result.preserved)
        }
    }
}
