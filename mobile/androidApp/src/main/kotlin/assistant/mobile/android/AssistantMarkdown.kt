package assistant.mobile.android

import assistant.mobile.core.assistant.AssistantMarkdownAlignment
import assistant.mobile.core.assistant.AssistantMarkdownBlock
import assistant.mobile.core.assistant.AssistantMarkdownSpan
import assistant.mobile.core.assistant.parseAssistantMarkdown
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import java.net.IDN
import java.net.URI

/** Native text only: no HTML, embedded media, automatic links or browser credentials. */
@Composable
internal fun AssistantMarkdown(text: String, allowedLinks: List<String> = emptyList(), modifier: Modifier = Modifier) {
    val blocks = remember(text, allowedLinks) { parseAssistantMarkdown(text, allowedLinks, ::normalizedAssistantLink) }
    AssistantMarkdownBlocks(blocks, modifier)
}

@Composable
private fun AssistantMarkdownBlocks(blocks: List<AssistantMarkdownBlock>, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        blocks.forEach { block ->
            when (block) {
                is AssistantMarkdownBlock.Text -> AssistantMarkdownText(
                    block.spans,
                    modifier = if (block.headingLevel > 0) Modifier.semantics { heading() } else Modifier,
                    headingLevel = block.headingLevel,
                )
                is AssistantMarkdownBlock.ListBlock -> Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    block.items.forEachIndexed { index, children ->
                        Row(Modifier.fillMaxWidth()) {
                            Text(block.start?.let { "${it + index}." } ?: "•",
                                modifier = Modifier.widthIn(min = 26.dp).padding(end = 8.dp),
                                style = MaterialTheme.typography.bodyLarge)
                            AssistantMarkdownBlocks(children, Modifier.weight(1f))
                        }
                    }
                }
                is AssistantMarkdownBlock.Quote -> Row(Modifier.height(IntrinsicSize.Min)) {
                    Box(Modifier.width(2.dp).fillMaxHeight().background(MaterialTheme.colorScheme.outlineVariant))
                    AssistantMarkdownBlocks(block.children, Modifier.padding(start = 12.dp))
                }
                is AssistantMarkdownBlock.Code -> Text(
                    block.text,
                    modifier = Modifier.fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(8.dp))
                        .horizontalScroll(rememberScrollState()).padding(12.dp),
                    style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                )
                is AssistantMarkdownBlock.Table -> Column(Modifier.horizontalScroll(rememberScrollState())) {
                    (listOf(block.header) + block.rows).forEachIndexed { index, cells ->
                        Row {
                            block.header.indices.forEach { column ->
                                Column(Modifier.width(144.dp)) {
                                    AssistantMarkdownText(
                                        cells.getOrElse(column) { emptyList() }.map { if (index == 0) it.copy(bold = true) else it },
                                        modifier = Modifier.fillMaxWidth().padding(8.dp),
                                        alignment = block.alignments.getOrElse(column) { AssistantMarkdownAlignment.Start },
                                    )
                                    HorizontalDivider()
                                }
                            }
                        }
                    }
                }
                AssistantMarkdownBlock.Rule -> HorizontalDivider()
            }
        }
    }
}

@Composable
private fun AssistantMarkdownText(
    spans: List<AssistantMarkdownSpan>,
    modifier: Modifier = Modifier,
    headingLevel: Int = 0,
    alignment: AssistantMarkdownAlignment = AssistantMarkdownAlignment.Start,
) {
    val linkColor = MaterialTheme.colorScheme.primary
    val codeColor = MaterialTheme.colorScheme.surfaceVariant
    val content = remember(spans, linkColor, codeColor) {
        buildAnnotatedString {
            for (span in spans) {
                val style = SpanStyle(
                    fontWeight = if (span.bold) FontWeight.Bold else null,
                    fontStyle = if (span.italic) FontStyle.Italic else FontStyle.Normal,
                    textDecoration = if (span.struck) TextDecoration.LineThrough else null,
                    fontFamily = if (span.code) FontFamily.Monospace else null,
                    background = if (span.code) codeColor else androidx.compose.ui.graphics.Color.Unspecified,
                )
                val url = span.url
                if (url == null) withStyle(style) { append(span.text) }
                else withLink(LinkAnnotation.Url(url, TextLinkStyles(
                    style = SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline),
                ))) { withStyle(style) { append(span.text) } }
            }
        }
    }
    val typography = when (headingLevel) {
        1 -> MaterialTheme.typography.titleLarge
        2 -> MaterialTheme.typography.titleMedium
        3, 4, 5, 6 -> MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold)
        else -> MaterialTheme.typography.bodyLarge
    }
    Text(content, modifier, style = typography, textAlign = when (alignment) {
        AssistantMarkdownAlignment.Start -> TextAlign.Start
        AssistantMarkdownAlignment.Center -> TextAlign.Center
        AssistantMarkdownAlignment.End -> TextAlign.End
    })
}

/** The same external-link boundary as the web: an exact cited URL, HTTP(S), no credentials. */
internal fun normalizedAssistantLink(value: String): String? = runCatching {
    require(value.isNotBlank() && value.none { it.isWhitespace() || it.isISOControl() })
    val parsed = URI(value)
    val scheme = parsed.scheme?.lowercase()
    require(scheme == "https" || scheme == "http")
    require(parsed.rawUserInfo == null)
    val authority = requireNotNull(parsed.rawAuthority)
    require('@' !in authority && '\\' !in authority)
    val host: String
    val port: String
    if (authority.startsWith('[')) {
        val close = authority.indexOf(']')
        require(close > 1)
        host = authority.substring(0, close + 1).lowercase()
        port = authority.substring(close + 1)
        require(port.isEmpty() || port.startsWith(':'))
    } else {
        val colon = authority.lastIndexOf(':')
        val rawHost = if (colon >= 0) authority.substring(0, colon) else authority
        port = if (colon >= 0) authority.substring(colon) else ""
        require(rawHost.isNotEmpty())
        host = IDN.toASCII(rawHost, IDN.USE_STD3_ASCII_RULES).lowercase()
    }
    if (port.isNotEmpty()) require(port.drop(1).toInt() in 1..65535)
    URI(buildString {
        append(scheme).append("://").append(host).append(port)
        append(parsed.rawPath.orEmpty())
        parsed.rawQuery?.let { append('?').append(it) }
        parsed.rawFragment?.let { append('#').append(it) }
    }).toASCIIString()
}.getOrNull()
