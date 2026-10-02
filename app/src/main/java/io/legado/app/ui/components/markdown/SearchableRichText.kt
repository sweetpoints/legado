package io.legado.app.ui.components.markdown

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.*
import androidx.compose.ui.text.font.*
import androidx.compose.ui.text.style.*
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.legado.app.data.repository.MarkdownImageRepository

data class RichTextMatchPart(val leaf: Int, val start: Int, val end: Int)
data class RichTextMatch(val parts: List<RichTextMatchPart>)
data class RichTextGeometry(val leaf: Int, val start: Int, val end: Int, val y: Float, val layout: TextLayoutResult)
/** Search highlights are applied to the actual rendered text, with its layout returned for scroll targeting. */
@Composable internal fun SearchableRichText(document: RichDocument, matches: List<RichTextMatch>, current: Int,
    images: MarkdownImageRepository, link: (String) -> Unit, inspect: (String) -> Unit,
    geometry: (RichTextGeometry) -> Unit, modifier: Modifier = Modifier) {
    var root by remember { mutableStateOf<LayoutCoordinates?>(null) }
    val updatedGeometry by rememberUpdatedState(geometry)
    SelectionContainer(modifier.onGloballyPositioned { root = it }) {
        RichBlocks(document.blocks, matches, current, images, link, inspect, root, updatedGeometry)
    }
}
@Composable private fun RichBlocks(blocks: List<RichBlock>, matches: List<RichTextMatch>, current: Int, images: MarkdownImageRepository,
    link: (String) -> Unit, inspect: (String) -> Unit, root: LayoutCoordinates?, geometry: (RichTextGeometry) -> Unit) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        blocks.forEach { block -> when (block) {
            is RichBlock.Leaf -> RichLeaf(block, matches, current, images, link, inspect, root, geometry)
            is RichBlock.Quote -> {
                val color = MaterialTheme.colorScheme.secondary
                Box(Modifier.fillMaxWidth().drawBehind { drawLine(color, Offset.Zero, Offset(0f, size.height), 3.dp.toPx()) }.padding(start = 12.dp)) {
                    RichBlocks(block.children, matches, current, images, link, inspect, root, geometry)
                }
            }
            is RichBlock.ListBlock -> Column { block.items.forEachIndexed { index, items -> Row {
                Text(block.start?.let { "${it + index}." } ?: "•", Modifier.width(28.dp), color = MaterialTheme.colorScheme.secondary)
                Box(Modifier.weight(1f)) { RichBlocks(items, matches, current, images, link, inspect, root, geometry) }
            } } }
            is RichBlock.Table -> BoxWithConstraints(Modifier.fillMaxWidth().testTag("rich-table")) {
                val width = maxOf(maxWidth / (block.rows.maxOfOrNull { it.size } ?: 1).coerceAtLeast(1), 140.dp)
                Column(Modifier.horizontalScroll(rememberScrollState())) { block.rows.forEach { cells -> Row {
                    cells.forEach { cell -> Box(Modifier.width(width).border(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = .18f))
                        .background(if (cell.header) MaterialTheme.colorScheme.onSurface.copy(alpha = .04f) else Color.Transparent).padding(8.dp)) {
                        RichLeaf(cell, matches, current, images, link, inspect, root, geometry)
                    } }
                } } }
            }
            RichBlock.Divider -> HorizontalDivider()
        } }
    }
}
@Composable private fun RichLeaf(leaf: RichBlock.Leaf, matches: List<RichTextMatch>, current: Int, images: MarkdownImageRepository,
    link: (String) -> Unit, inspect: (String) -> Unit, root: LayoutCoordinates?, geometry: (RichTextGeometry) -> Unit) {
    val parts = remember(leaf) { buildList<Pair<Int, List<RichInline>>> {
        var offset = 0; var start = 0; var runs = mutableListOf<RichInline>()
        leaf.content.forEach { inline ->
            if (inline is RichInline.Image) {
                if (runs.isNotEmpty()) add(start to runs.toList()); runs = mutableListOf(); add(offset to listOf(inline)); offset++; start = offset
            } else { runs += inline; offset += (inline as RichInline.Text).text.length }
        }
        if (runs.isNotEmpty()) add(start to runs.toList())
    } }
    Column(Modifier.fillMaxWidth()) { parts.forEach { (offset, content) ->
        val image = content.firstOrNull() as? RichInline.Image
        if (image != null) RichTextImage(image, images, link, inspect, "${leaf.id}-$offset")
        else {
            val accent = MaterialTheme.colorScheme.secondary; val codeBackground = MaterialTheme.colorScheme.onSurface.copy(alpha = .1f)
            val currentLink by rememberUpdatedState(link)
            val text = remember(content, leaf.id, offset, matches, current, accent, codeBackground) {
                richAnnotatedText(content.filterIsInstance<RichInline.Text>(), leaf.id, offset, matches, current, accent, codeBackground) { currentLink(it) }
            }
            var layout by remember { mutableStateOf<TextLayoutResult?>(null) }; var coordinates by remember { mutableStateOf<LayoutCoordinates?>(null) }
            fun report() { val anchor = root; val position = coordinates; val value = layout
                if (anchor != null && position?.isAttached == true && anchor.isAttached && value != null)
                    geometry(RichTextGeometry(leaf.id, offset, offset + text.length, anchor.localPositionOf(position, Offset.Zero).y, value))
            }
            val size = 16f * if (leaf.heading > 0) listOf(1.45f, 1.3f, 1.15f, 1.05f, 1f, 1f)[(leaf.heading - 1).coerceIn(0, 5)] else 1f
            val base = Modifier.fillMaxWidth().testTag("rich-text-${leaf.id}-$offset").onGloballyPositioned { coordinates = it; report() }
            Text(text, if (leaf.code) base.background(MaterialTheme.colorScheme.onSurface.copy(alpha = .06f)).horizontalScroll(rememberScrollState()).padding(8.dp) else base,
                fontSize = size.sp, lineHeight = (size * 1.3f).sp, fontWeight = if (leaf.heading > 0 || leaf.header) FontWeight.Bold else FontWeight.Normal,
                fontFamily = if (leaf.code) FontFamily.Monospace else null,
                textAlign = when (leaf.alignment) { "CENTER" -> TextAlign.Center; "RIGHT" -> TextAlign.End; else -> TextAlign.Start }, onTextLayout = { layout = it; report() })
        }
    } }
}
internal fun richAnnotatedText(runs: List<RichInline.Text>, leaf: Int, offset: Int, matches: List<RichTextMatch>, current: Int,
    accent: Color, codeBackground: Color, link: (String) -> Unit): AnnotatedString = buildAnnotatedString {
    runs.forEach { run -> val start = length; append(run.text); val style = run.style
        val decorations = buildList { if (style.underline) add(TextDecoration.Underline); if (style.strike) add(TextDecoration.LineThrough) }
        addStyle(SpanStyle(fontWeight = if (style.bold) FontWeight.Bold else null, fontStyle = if (style.italic) FontStyle.Italic else null,
            fontFamily = if (style.code) FontFamily.Monospace else null, color = style.color?.let(::Color) ?: Color.Unspecified,
            background = style.background?.let(::Color) ?: if (style.code) codeBackground else Color.Unspecified,
            textDecoration = if (decorations.isEmpty()) null else TextDecoration.combine(decorations)), start, length)
        style.link?.let { url -> addLink(LinkAnnotation.Url(url, TextLinkStyles(SpanStyle(color = accent, textDecoration = TextDecoration.Underline)), LinkInteractionListener { link(url) }), start, length) }
    }
    matches.forEachIndexed { index, match -> match.parts.filter { it.leaf == leaf }.forEach { part ->
        val start = maxOf(0, part.start - offset); val end = minOf(length, part.end - offset)
        if (start < end) addStyle(SpanStyle(background = accent.copy(alpha = if (index == current) .5f else .25f)), start, end)
    } }
}
