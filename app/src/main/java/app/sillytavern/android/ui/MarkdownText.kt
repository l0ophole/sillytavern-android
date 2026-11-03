package app.sillytavern.android.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.*
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import app.sillytavern.android.appearance.*
import coil.compose.AsyncImage

/** Message text with markdown/HTML formatting; with [render] false the text is shown as is. */
@Composable
fun MarkdownText(text: String, modifier: Modifier = Modifier, render: Boolean = true, allowHtml: Boolean = true, style: TextStyle = MaterialTheme.typography.bodyLarge) {
    val colors = LocalChatColors.current
    if (!render) { Text(text, modifier, color = colors.text, style = style); return }
    val blocks = remember(text, allowHtml) { Markdown.parse(text, allowHtml) }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) { blocks.forEach { Block(it, style, colors) } }
}

@Composable
private fun Block(b: MdBlock, style: TextStyle, c: ChatColors) {
    when (b) {
        is MdBlock.Paragraph -> Text(annotate(b.spans, c), style = style, color = c.text)
        is MdBlock.Heading -> Text(annotate(b.spans, c), color = c.text, style = when (b.level) {
            1 -> MaterialTheme.typography.headlineSmall; 2 -> MaterialTheme.typography.titleLarge; 3 -> MaterialTheme.typography.titleMedium
            else -> MaterialTheme.typography.titleSmall
        }, fontWeight = FontWeight.Bold)
        is MdBlock.Quote -> Row(Modifier.height(IntrinsicSize.Min)) {
            Box(Modifier.width(3.dp).fillMaxHeight().background(c.italics))
            Column(Modifier.padding(start = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) { b.blocks.forEach { Block(it, style, c) } }
        }
        is MdBlock.Items -> Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            b.items.forEachIndexed { i, spans ->
                Row { Text(if (b.ordered) "${b.start + i}." else "•", Modifier.width(24.dp), style = style, color = c.text); Text(annotate(spans, c), style = style, color = c.text) }
            }
        }
        is MdBlock.Code -> Surface(shape = RoundedCornerShape(6.dp), color = Color.Black.copy(alpha = 0.25f)) {
            Text(b.text, Modifier.horizontalScroll(rememberScrollState()).padding(8.dp), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall, color = c.text, softWrap = false)
        }
        is MdBlock.Image -> if (b.url.startsWith("https://") || b.url.startsWith("http://")) AsyncImage(b.url, b.alt, Modifier.fillMaxWidth().heightIn(max = 360.dp))
            else Text(b.alt.ifBlank { b.url }, style = style, color = c.italics)
        MdBlock.Rule -> HorizontalDivider(Modifier.padding(vertical = 4.dp))
    }
}

private fun annotate(spans: List<MdSpan>, c: ChatColors): AnnotatedString = buildAnnotatedString {
    for (s in spans) {
        val color = when {
            s.link != null -> Color(0xFF64B5F6)
            s.code -> c.text
            s.quote && !s.italic -> c.quote
            s.italic -> c.italics
            s.underline -> c.underline
            else -> Color.Unspecified
        }
        val st = SpanStyle(
            color = color,
            fontWeight = if (s.bold) FontWeight.Bold else null,
            fontStyle = if (s.italic) FontStyle.Italic else null,
            fontFamily = if (s.code) FontFamily.Monospace else null,
            background = if (s.code) Color.Black.copy(alpha = 0.2f) else Color.Unspecified,
            textDecoration = listOfNotNull(
                TextDecoration.LineThrough.takeIf { s.strike }, TextDecoration.Underline.takeIf { s.underline || s.link != null },
            ).let { if (it.isEmpty()) null else TextDecoration.combine(it) },
        )
        val url = s.link?.takeIf(Markdown::isSafeUrl)
        if (url != null) withLink(LinkAnnotation.Url(url)) { withStyle(st) { append(s.text) } } else withStyle(st) { append(s.text) }
    }
}
