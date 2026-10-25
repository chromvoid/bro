package assistant.mobile.core.assistant

import org.intellij.markdown.IElementType
import org.intellij.markdown.MarkdownElementTypes as Elements
import org.intellij.markdown.MarkdownTokenTypes as Tokens
import org.intellij.markdown.ast.ASTNode
import org.intellij.markdown.ast.getParentOfType
import org.intellij.markdown.ast.getTextInNode
import org.intellij.markdown.flavours.commonmark.CommonMarkMarkerProcessor
import org.intellij.markdown.flavours.gfm.GFMElementTypes
import org.intellij.markdown.flavours.gfm.GFMFlavourDescriptor
import org.intellij.markdown.flavours.gfm.GFMTokenTypes
import org.intellij.markdown.flavours.gfm.StrikeThroughDelimiterParser
import org.intellij.markdown.flavours.gfm.table.GitHubTableMarkerProvider
import org.intellij.markdown.html.HtmlGenerator
import org.intellij.markdown.html.entities.EntityConverter
import org.intellij.markdown.parser.LinkMap
import org.intellij.markdown.parser.CancellationToken
import org.intellij.markdown.parser.MarkerProcessor
import org.intellij.markdown.parser.MarkerProcessorFactory
import org.intellij.markdown.parser.MarkdownParser
import org.intellij.markdown.parser.ProductionHolder
import org.intellij.markdown.parser.constraints.CommonMarkdownConstraints
import org.intellij.markdown.parser.markerblocks.providers.HtmlBlockProvider
import org.intellij.markdown.parser.sequentialparsers.EmphasisLikeParser
import org.intellij.markdown.parser.sequentialparsers.SequentialParser
import org.intellij.markdown.parser.sequentialparsers.SequentialParserManager
import org.intellij.markdown.parser.sequentialparsers.impl.BacktickParser
import org.intellij.markdown.parser.sequentialparsers.impl.EmphStrongDelimiterParser
import org.intellij.markdown.parser.sequentialparsers.impl.ImageParser
import org.intellij.markdown.parser.sequentialparsers.impl.InlineLinkParser
import org.intellij.markdown.parser.sequentialparsers.impl.ReferenceLinkParser

/** Inert presentation data: Markdown never grants authority or turns prose into an operation. */
data class AssistantMarkdownSpan(
    val text: String,
    val bold: Boolean = false,
    val italic: Boolean = false,
    val struck: Boolean = false,
    val code: Boolean = false,
    val url: String? = null,
)

sealed interface AssistantMarkdownBlock {
    data class Text(val spans: List<AssistantMarkdownSpan>, val headingLevel: Int = 0) : AssistantMarkdownBlock
    data class ListBlock(val items: List<List<AssistantMarkdownBlock>>, val start: Int? = null) : AssistantMarkdownBlock
    data class Quote(val children: List<AssistantMarkdownBlock>) : AssistantMarkdownBlock
    data class Code(val text: String) : AssistantMarkdownBlock
    data class Table(
        val header: List<List<AssistantMarkdownSpan>>,
        val rows: List<List<List<AssistantMarkdownSpan>>>,
        val alignments: List<AssistantMarkdownAlignment>,
    ) : AssistantMarkdownBlock
    data object Rule : AssistantMarkdownBlock
}

enum class AssistantMarkdownAlignment { Start, Center, End }

/**
 * CommonMark with the web's table and strike extensions. No HTML, task checkboxes,
 * automatic links, math or highlighting extensions. JetBrains owns all Markdown syntax.
 */
private class AssistantMarkdownFlavour : GFMFlavourDescriptor() {
    override val markerProcessorFactory = object : MarkerProcessorFactory {
        override fun createMarkerProcessor(productionHolder: ProductionHolder): MarkerProcessor<*> =
            object : CommonMarkMarkerProcessor(productionHolder, CommonMarkdownConstraints.BASE) {
                private val providers =
                    super.getMarkerBlockProviders().filterNot { it is HtmlBlockProvider } + GitHubTableMarkerProvider()
                override fun getMarkerBlockProviders() = providers
            }
    }

    override val sequentialParserManager = object : SequentialParserManager() {
        override fun getParserSequence(): List<SequentialParser> = listOf(
            BacktickParser(), ImageParser(), InlineLinkParser(), ReferenceLinkParser(),
            EmphasisLikeParser(EmphStrongDelimiterParser(), StrikeThroughDelimiterParser()),
        )
    }
}

/**
 * Parsing and projection live in the shared core; native renderers consume only this closed model.
 * The caller supplies its existing platform URL boundary. Without one all links stay inert.
 */
fun parseAssistantMarkdown(
    text: String,
    allowedLinks: List<String> = emptyList(),
    normalizeLink: (String) -> String? = { null },
): List<AssistantMarkdownBlock> {
    val source = text.replace("\r\n", "\n").replace('\r', '\n')
    val root = MarkdownParser(
        AssistantMarkdownFlavour(), assertionsEnabled = false, cancellationToken = CancellationToken.NonCancellable,
    ).buildMarkdownTreeFromString(source as CharSequence)
    return AssistantMarkdownProjection(source, root, allowedLinks.mapNotNull(normalizeLink).toSet(), normalizeLink)
        .blocks(root.children)
}

private class AssistantMarkdownProjection(
    private val source: String,
    root: ASTNode,
    private val allowed: Set<String>,
    private val normalizeLink: (String) -> String?,
) {
    private val links = LinkMap.buildLinkMap(root, source)
    private fun raw(node: ASTNode) = node.getTextInNode(source).toString()

    fun blocks(nodes: List<ASTNode>): List<AssistantMarkdownBlock> = nodes.mapNotNull { node ->
        when (node.type) {
            Elements.PARAGRAPH -> AssistantMarkdownBlock.Text(trim(inlines(node.children)))
            Elements.UNORDERED_LIST, Elements.ORDERED_LIST -> AssistantMarkdownBlock.ListBlock(
                items = node.children.filter { it.type == Elements.LIST_ITEM }.map { blocks(it.children) },
                start = if (node.type == Elements.ORDERED_LIST) node.children.firstOrNull { it.type == Elements.LIST_ITEM }
                    ?.children?.firstOrNull { it.type == Tokens.LIST_NUMBER }?.let { raw(it).trimStart().takeWhile(Char::isDigit).toIntOrNull() } ?: 1
                else null,
            )
            Elements.BLOCK_QUOTE -> AssistantMarkdownBlock.Quote(blocks(node.children))
            Elements.CODE_FENCE -> AssistantMarkdownBlock.Code(fencedCode(node))
            Elements.CODE_BLOCK -> AssistantMarkdownBlock.Code(node.children.filter {
                it.type == Tokens.CODE_LINE || it.type == Tokens.EOL
            }.joinToString("") { if (it.type == Tokens.CODE_LINE) HtmlGenerator.trimIndents(raw(it), 4).toString() else "\n" }.let {
                if (it.endsWith('\n')) it else "$it\n"
            })
            GFMElementTypes.TABLE -> table(node)
            Tokens.HORIZONTAL_RULE -> AssistantMarkdownBlock.Rule
            in headingTypes -> {
                val content = node.children.firstOrNull { it.type == Tokens.ATX_CONTENT || it.type == Tokens.SETEXT_CONTENT }
                AssistantMarkdownBlock.Text(content?.let { trim(inlines(it.children)) }.orEmpty(), headingTypes.getValue(node.type))
            }
            else -> null // Container markers, blank lines and link definitions never become content.
        }
    }

    private fun fencedCode(node: ASTNode): String {
        val children = node.children
        val beginning = children.indexOfFirst { it.type == Tokens.EOL }
        if (beginning < 0) return ""
        val content = children.drop(beginning + 1).takeWhile { it.type != Tokens.CODE_FENCE_END }.filter {
            it.type == Tokens.CODE_FENCE_CONTENT || it.type == Tokens.EOL
        }
        val indent = raw(node).takeWhile { it == ' ' }.length
        return content.joinToString("") { child ->
            if (child.type == Tokens.EOL) "\n" else HtmlGenerator.trimIndents(raw(child), indent).toString()
        }.let { if (content.lastOrNull()?.type == Tokens.CODE_FENCE_CONTENT) "$it\n" else it }
    }

    private fun table(node: ASTNode): AssistantMarkdownBlock.Table {
        fun cells(row: ASTNode) = row.children.filter { it.type == GFMTokenTypes.CELL }.map { trim(inlines(it.children)) }
        val header = node.children.firstOrNull { it.type == GFMElementTypes.HEADER }?.let(::cells).orEmpty()
        val separator = node.children.firstOrNull { it.type == GFMTokenTypes.TABLE_SEPARATOR }?.let(::raw).orEmpty()
        // The parser has already established this as the GFM delimiter row, not prose.
        val alignments = separator.trim().removePrefix("|").removeSuffix("|").split('|').map { delimiter ->
            val value = delimiter.trim()
            when {
                value.startsWith(':') && value.endsWith(':') -> AssistantMarkdownAlignment.Center
                value.endsWith(':') -> AssistantMarkdownAlignment.End
                else -> AssistantMarkdownAlignment.Start
            }
        }
        return AssistantMarkdownBlock.Table(header, node.children.filter { it.type == GFMElementTypes.ROW }.map(::cells), alignments)
    }

    private fun inlines(nodes: List<ASTNode>, style: AssistantMarkdownSpan = AssistantMarkdownSpan("")): List<AssistantMarkdownSpan> =
        nodes.flatMap { node ->
            when (node.type) {
                Elements.STRONG -> inlines(delimited(node, Tokens.EMPH), style.copy(bold = true))
                Elements.EMPH -> inlines(delimited(node, Tokens.EMPH), style.copy(italic = true))
                GFMElementTypes.STRIKETHROUGH -> inlines(delimited(node, GFMTokenTypes.TILDE), style.copy(struck = true))
                Elements.CODE_SPAN -> {
                    val inTable = node.getParentOfType(GFMTokenTypes.CELL) != null
                    var content = node.children.drop(1).dropLast(1).filterNot { it.type == Tokens.BLOCK_QUOTE && !inTable }
                        .joinToString("") { raw(it) }.replace('\n', ' ')
                    if (inTable) content = content.replace("\\|", "|")
                    if (content.startsWith(' ') && content.endsWith(' ') && content.any { it != ' ' }) content = content.drop(1).dropLast(1)
                    listOf(style.copy(text = content, code = true))
                }
                Elements.INLINE_LINK, Elements.FULL_REFERENCE_LINK, Elements.SHORT_REFERENCE_LINK -> link(node, style)
                Elements.IMAGE -> listOf(style.copy(text = raw(node)))
                Tokens.EOL -> listOf(style.copy(text = "\n"))
                Tokens.HARD_LINE_BREAK -> emptyList() // EOL owns the single rendered break.
                Tokens.BLOCK_QUOTE -> if (node.getParentOfType(GFMTokenTypes.CELL) != null) listOf(style.copy(text = raw(node).trimStart())) else emptyList()
                else -> if (node.children.isEmpty()) listOf(style.copy(text = decoded(raw(node)))) else inlines(node.children, style)
            }
        }.filter { it.text.isNotEmpty() }

    private fun link(node: ASTNode, style: AssistantMarkdownSpan): List<AssistantMarkdownSpan> {
        val label = node.children.firstOrNull { it.type == Elements.LINK_TEXT || it.type == Elements.LINK_LABEL }
            ?: return listOf(style.copy(text = raw(node)))
        val definition = if (node.type == Elements.INLINE_LINK) node else {
            val reference = node.children.firstOrNull { it.type == Elements.LINK_LABEL }
            reference?.let { links.getLinkInfo(it.getTextInNode(source))?.node }
        }
        val destination = definition?.children?.firstOrNull { it.type == Elements.LINK_DESTINATION }
            ?.let { decoded(raw(it).removeSurrounding("<", ">")) }
            ?: return listOf(style.copy(text = raw(node)))
        val url = normalizeLink(destination)?.takeIf { it in allowed }
        fun containsImage(part: ASTNode): Boolean = part.type == Elements.IMAGE || part.children.any(::containsImage)
        if (url == null || containsImage(label)) return listOf(style.copy(text = raw(node)))
        return inlines(label.children.drop(1).dropLast(1), style.copy(url = url))
    }

    private fun delimited(node: ASTNode, delimiter: IElementType): List<ASTNode> {
        var from = 0
        var until = node.children.size
        while (from < until && node.children[from].type == delimiter && node.children[until - 1].type == delimiter) {
            from++
            until--
        }
        return node.children.subList(from, until)
    }

    private fun trim(spans: List<AssistantMarkdownSpan>): List<AssistantMarkdownSpan> {
        if (spans.isEmpty()) return spans
        val result = spans.toMutableList()
        while (result.isNotEmpty() && result.first().text.isBlank()) result.removeAt(0)
        while (result.isNotEmpty() && result.last().text.isBlank()) result.removeAt(result.lastIndex)
        if (result.isNotEmpty()) {
            result[0] = result.first().copy(text = result.first().text.trimStart())
            result[result.lastIndex] = result.last().copy(text = result.last().text.trimEnd())
        }
        return result
    }

    private fun decoded(value: String): String = EntityConverter.replaceEntities(value, true, true)
        .replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&amp;", "&")

    private companion object {
        val headingTypes = mapOf(
            Elements.ATX_1 to 1, Elements.ATX_2 to 2, Elements.ATX_3 to 3,
            Elements.ATX_4 to 4, Elements.ATX_5 to 5, Elements.ATX_6 to 6,
            Elements.SETEXT_1 to 1, Elements.SETEXT_2 to 2,
        )
    }
}
