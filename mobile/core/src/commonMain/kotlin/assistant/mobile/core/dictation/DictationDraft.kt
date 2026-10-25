package assistant.mobile.core.dictation

data class DictationDraftSnapshot(val text: String, val revision: Long) {
    override fun toString(): String = "DictationDraftSnapshot(revision=$revision, text=redacted)"
}

data class DictationDraftUpdate(val text: String, val preserved: Boolean) {
    override fun toString(): String = "DictationDraftUpdate(preserved=$preserved, text=redacted)"
}

/** A revision catches edits even if the owner later restores the original text. */
class DictationDraft(private val original: DictationDraftSnapshot) {
    fun final(current: DictationDraftSnapshot, recognized: String): DictationDraftUpdate {
        if (current.revision != original.revision || current.text != original.text)
            return DictationDraftUpdate(current.text, preserved = true)
        val separator = if (original.text.isNotEmpty() && !original.text.last().isWhitespace()) " " else ""
        return DictationDraftUpdate(original.text + separator + recognized, preserved = false)
    }
}
