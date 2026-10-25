package assistant.mobile.core.assistant

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class AssistantMarkdownTest {
    private val source = "https://example.test/source_(one)"

    private fun parse(text: String, allowed: List<String> = emptyList()) =
        parseAssistantMarkdown(text, allowed) { value -> value.takeIf { it.startsWith("https://example.test/") } }

    private fun List<AssistantMarkdownSpan>.plain() = joinToString("") { it.text }

    @Test
    fun htmlImagesAndAutomaticUrlsAreOnlyText() {
        val input = "<div>**видно**</div>\n\n![картинка]($source) <https://example.test/source_(one)> https://example.test/source_(one)"
        val blocks = parse(input, listOf(source)).map { assertIs<AssistantMarkdownBlock.Text>(it) }
        assertEquals("<div>видно</div>", blocks.first().spans.plain())
        assertTrue(blocks.first().spans.single { it.text == "видно" }.bold)
        assertTrue(blocks.flatMap { it.spans }.all { it.url == null })
        assertTrue(blocks.last().spans.plain().startsWith("![картинка]($source)"))
    }

    @Test
    fun onlyAnExactNormalizedAllowedDestinationCanBeActive() {
        val block = assertIs<AssistantMarkdownBlock.Text>(parse("[**Источник**]($source) [другой](https://example.test/other) [код](javascript:alert)", listOf(source)).single())
        assertEquals(listOf(source), block.spans.mapNotNull { it.url })
        assertTrue(block.spans.single { it.text == "Источник" }.bold)
        assertTrue(block.spans.plain().contains("[другой](https://example.test/other)"))
        assertTrue(block.spans.plain().contains("[код](javascript:alert)"))
        val inert = assertIs<AssistantMarkdownBlock.Text>(parseAssistantMarkdown("[Источник]($source)", listOf(source)).single())
        assertTrue(inert.spans.all { it.url == null })
    }
}
