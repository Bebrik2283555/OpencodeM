package com.opencode.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Done
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

internal sealed interface Block {
    class Para(val text: String) : Block
    class Heading(val level: Int, val text: String) : Block
    class Code(val lang: String, val text: String) : Block
    class Quote(val text: String) : Block
    class Item(val depth: Int, val marker: String, val text: String) : Block
    class Table(val rows: List<List<String>>) : Block
    data object Rule : Block
}

private val HEADING = Regex("^(#{1,6})\\s+(.*)$")
private val BULLET = Regex("^(\\s*)[-*+]\\s+(.*)$")
private val NUMBERED = Regex("^(\\s*)(\\d+)[.)]\\s+(.*)$")
private val RULE = Regex("^(-{3,}|\\*{3,}|_{3,})$")
private val SEPARATOR = Regex("^\\|?\\s*:?-{2,}:?\\s*(\\|\\s*:?-{2,}:?\\s*)*\\|?$")

private fun cells(line: String) = line.trim().removePrefix("|").removeSuffix("|").split('|').map { it.trim() }

internal fun blocks(source: String): List<Block> {
    val lines = source.replace("\r\n", "\n").split('\n')
    val out = mutableListOf<Block>()
    val para = mutableListOf<String>()
    fun flush() {
        if (para.isNotEmpty()) out += Block.Para(para.joinToString("\n"))
        para.clear()
    }
    var i = 0
    while (i < lines.size) {
        val line = lines[i]
        val trimmed = line.trim()
        when {
            trimmed.startsWith("```") || trimmed.startsWith("~~~") -> {
                flush()
                val fence = trimmed.take(3)
                val lang = trimmed.drop(3).trim()
                val code = mutableListOf<String>()
                i++
                // While the answer is still coming the fence may not be closed yet.
                while (i < lines.size && !lines[i].trim().startsWith(fence)) code += lines[i++]
                out += Block.Code(lang, code.joinToString("\n"))
            }
            trimmed.isEmpty() -> flush()
            HEADING.matches(trimmed) -> {
                flush()
                val m = HEADING.find(trimmed)!!
                out += Block.Heading(m.groupValues[1].length, m.groupValues[2])
            }
            RULE.matches(trimmed) -> { flush(); out += Block.Rule }
            trimmed.startsWith(">") -> {
                flush()
                val quote = mutableListOf<String>()
                while (i < lines.size && lines[i].trim().startsWith(">")) quote += lines[i++].trim().removePrefix(">").removePrefix(" ")
                out += Block.Quote(quote.joinToString("\n"))
                continue
            }
            trimmed.startsWith("|") && i + 1 < lines.size && SEPARATOR.matches(lines[i + 1].trim()) -> {
                flush()
                val rows = mutableListOf(cells(trimmed))
                i += 2
                while (i < lines.size && lines[i].trim().startsWith("|")) rows += cells(lines[i++])
                out += Block.Table(rows)
                continue
            }
            BULLET.matches(line) -> {
                flush()
                val m = BULLET.find(line)!!
                out += Block.Item(m.groupValues[1].length / 2, "\u2022", m.groupValues[2])
            }
            NUMBERED.matches(line) -> {
                flush()
                val m = NUMBERED.find(line)!!
                out += Block.Item(m.groupValues[1].length / 2, m.groupValues[2] + ".", m.groupValues[3])
            }
            else -> para += line.trimEnd()
        }
        i++
    }
    flush()
    return out
}

private val INLINE = Regex(
    """\*\*(.+?)\*\*|__(.+?)__|~~(.+?)~~|`([^`]+)`|\[([^\]]+)]\(([^)\s]+)\)|(?<![\w*])\*(?!\s)(.+?)(?<!\s)\*|(?<![\w_])_(?!\s)(.+?)(?<!\s)_|(https?://[^\s)>\]]+)""")

private fun inline(source: String, link: Color, shade: Color): AnnotatedString = buildAnnotatedString {
    val links = TextLinkStyles(SpanStyle(color = link, textDecoration = TextDecoration.Underline))
    val code = SpanStyle(fontFamily = FontFamily.Monospace, background = shade, fontSize = 0.92.em)
    fun walk(line: String) {
        var at = 0
        for (match in INLINE.findAll(line)) {
            append(line.substring(at, match.range.first))
            val g = match.groupValues
            when {
                g[1].isNotEmpty() -> withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { walk(g[1]) }
                g[2].isNotEmpty() -> withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { walk(g[2]) }
                g[3].isNotEmpty() -> withStyle(SpanStyle(textDecoration = TextDecoration.LineThrough)) { walk(g[3]) }
                g[4].isNotEmpty() -> withStyle(code) { append(g[4]) }
                g[5].isNotEmpty() -> withLink(LinkAnnotation.Url(g[6], links)) { append(g[5]) }
                g[7].isNotEmpty() -> withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { walk(g[7]) }
                g[8].isNotEmpty() -> withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { walk(g[8]) }
                g[9].isNotEmpty() -> withLink(LinkAnnotation.Url(g[9], links)) { append(g[9]) }
            }
            at = match.range.last + 1
        }
        append(line.substring(at))
    }
    walk(source)
}

/** Markdown as models write it: paragraphs, headings, lists, quotes, tables and code with a copy button. */
@Composable
fun Markdown(text: String, modifier: Modifier = Modifier, colour: Color = MaterialTheme.colorScheme.onSurface) {
    val parsed = remember(text) { blocks(text) }
    val scheme = MaterialTheme.colorScheme
    val body = MaterialTheme.typography.bodyLarge.copy(color = colour)
    fun rich(s: String) = inline(s, scheme.primary, scheme.surfaceContainerHighest)
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        for (b in parsed) when (b) {
            is Block.Para -> Text(rich(b.text), style = body)
            is Block.Heading -> Text(rich(b.text), style = when (b.level) {
                1 -> MaterialTheme.typography.titleLarge
                2 -> MaterialTheme.typography.titleMedium.copy(fontSize = 19.sp)
                else -> MaterialTheme.typography.titleMedium
            }.copy(fontWeight = FontWeight.Bold, color = colour), modifier = Modifier.padding(top = 4.dp))
            is Block.Item -> Row(Modifier.padding(start = (b.depth * 18).dp)) {
                Text(b.marker, style = body, modifier = Modifier.width(if (b.marker == "\u2022") 18.dp else 26.dp),
                    color = if (b.marker == "\u2022") scheme.primary else colour)
                Text(rich(b.text), style = body)
            }
            is Block.Quote -> Row(Modifier.height(IntrinsicSize.Min)) {
                Box(Modifier.width(3.dp).fillMaxHeight().background(scheme.primary, RoundedCornerShape(2.dp)))
                Text(rich(b.text), style = body.copy(color = scheme.onSurfaceVariant), modifier = Modifier.padding(start = 10.dp))
            }
            is Block.Code -> CodeBlock(b.lang, b.text)
            is Block.Table -> TableBlock(b.rows) { rich(it) }
            Block.Rule -> HorizontalDivider(Modifier.padding(vertical = 4.dp))
        }
    }
}

@Composable
private fun CodeBlock(lang: String, code: String) {
    val clipboard = LocalClipboardManager.current
    var copied by remember { mutableStateOf(false) }
    LaunchedEffect(copied) { if (copied) { delay(1500); copied = false } }
    Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, shape = RoundedCornerShape(14.dp),
        modifier = Modifier.fillMaxWidth()) {
        Column {
            Row(Modifier.fillMaxWidth().padding(start = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(lang.ifEmpty { "code" }, style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                IconButton(onClick = { clipboard.setText(AnnotatedString(code)); copied = true }) {
                    Icon(if (copied) Icons.Outlined.Done else Icons.Outlined.ContentCopy, null, Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Text(code, fontFamily = FontFamily.Monospace, fontSize = 13.sp, lineHeight = 19.sp, softWrap = false,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.horizontalScroll(rememberScrollState()).padding(start = 14.dp, end = 14.dp, bottom = 14.dp))
        }
    }
}

/** Columns as wide as their widest cell (up to a limit), rows as tall as their tallest; scrolls sideways. */
@Composable
private fun TableBlock(rows: List<List<String>>, rich: (String) -> AnnotatedString) {
    val scheme = MaterialTheme.colorScheme
    val columns = rows.maxOf { it.size }
    Surface(shape = RoundedCornerShape(12.dp), color = scheme.surfaceContainerLow, modifier = Modifier.fillMaxWidth()) {
        Box(Modifier.horizontalScroll(rememberScrollState())) {
            Layout(content = {
                rows.forEachIndexed { r, row ->
                    for (c in 0 until columns) {
                        Text(rich(row.getOrElse(c) { "" }), style = MaterialTheme.typography.bodyMedium.copy(
                            fontWeight = if (r == 0) FontWeight.Bold else FontWeight.Normal, color = scheme.onSurface),
                            modifier = Modifier.background(if (r == 0) scheme.surfaceContainerHigh else Color.Transparent)
                                .padding(horizontal = 12.dp, vertical = 8.dp))
                    }
                }
            }) { measurables, _ ->
                // Intrinsics first: a measurable may be measured only once.
                val cap = 260.dp.roundToPx()
                val widths = IntArray(columns) { c ->
                    rows.indices.maxOf { r -> measurables[r * columns + c].maxIntrinsicWidth(Constraints.Infinity).coerceAtMost(cap) }
                }
                val heights = IntArray(rows.size) { r ->
                    (0 until columns).maxOf { c -> measurables[r * columns + c].minIntrinsicHeight(widths[c]) }
                }
                val fitted = measurables.mapIndexed { i, m -> m.measure(Constraints.fixed(widths[i % columns], heights[i / columns])) }
                layout(widths.sum(), heights.sum()) {
                    var y = 0
                    for (r in rows.indices) {
                        var x = 0
                        for (c in 0 until columns) {
                            fitted[r * columns + c].place(x, y)
                            x += widths[c]
                        }
                        y += heights[r]
                    }
                }
            }
        }
    }
}
