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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.*
import androidx.compose.ui.text.font.*
import androidx.compose.ui.text.style.*
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import io.legado.app.data.image.AnimatedDrawableResource
import io.legado.app.data.repository.MarkdownImageRepository
import io.legado.app.ui.components.image.LifecycleDrawablePainter
import kotlinx.coroutines.awaitCancellation

/** Selectable Markdown blocks without embedding a TextView; callers own vertical scrolling. */
@Composable fun ComposeMarkdown(document: List<MarkdownBlock>, imageRepository: MarkdownImageRepository, onLink: (String) -> Unit,
    modifier: Modifier = Modifier) {
    SelectionContainer(modifier) { MarkdownBlocks(document, imageRepository, onLink, "root") }
}
@Composable private fun MarkdownBlocks(blocks: List<MarkdownBlock>, repository: MarkdownImageRepository, onLink: (String) -> Unit, path: String) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        blocks.forEachIndexed { index, block -> val key = "$path-$index"
            when (block) {
                is MarkdownBlock.Paragraph -> MarkdownInlines(block.content, repository, onLink, key, block.heading)
                is MarkdownBlock.Code -> Text(block.content, Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.onSurface.copy(alpha = .06f))
                    .horizontalScroll(rememberScrollState()).padding(8.dp).testTag("markdown-code-$key"), fontFamily = FontFamily.Monospace)
                is MarkdownBlock.Quote -> {
                    val color = MaterialTheme.colorScheme.secondary
                    Box(Modifier.fillMaxWidth().drawBehind { drawLine(color, Offset.Zero, Offset(0f, size.height), 3.dp.toPx()) }.padding(start = 12.dp)) {
                        MarkdownBlocks(block.blocks, repository, onLink, "$key-quote")
                    }
                }
                is MarkdownBlock.ListBlock -> Column(verticalArrangement = Arrangement.spacedBy(4.dp)) { block.items.forEachIndexed { item, children ->
                    Row { Text(block.start?.let { "${it + item}." } ?: "•", Modifier.width(28.dp), color = MaterialTheme.colorScheme.secondary)
                        Box(Modifier.weight(1f)) { MarkdownBlocks(children, repository, onLink, "$key-item-$item") } }
                } }
                is MarkdownBlock.Table -> BoxWithConstraints(Modifier.fillMaxWidth().testTag("markdown-table-$key")) {
                    val columns = block.rows.maxOfOrNull { it.size } ?: 1
                    val width = maxOf(maxWidth / columns.coerceAtLeast(1), 140.dp)
                    Column(Modifier.horizontalScroll(rememberScrollState())) {
                        block.rows.forEachIndexed { row, cells -> Row {
                            cells.forEachIndexed { column, cell ->
                                val tint = if (cell.header) MaterialTheme.colorScheme.onSurface.copy(alpha = .04f) else Color.Transparent
                                Box(Modifier.width(width).border(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = .18f)).background(tint).padding(8.dp)) {
                                    MarkdownInlines(cell.content, repository, onLink, "$key-cell-$row-$column", bold = cell.header,
                                        alignment = when (cell.alignment) { "CENTER" -> TextAlign.Center; "RIGHT" -> TextAlign.End; else -> TextAlign.Start })
                                }
                            }
                        } }
                    }
                }
                MarkdownBlock.Divider -> HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = .18f))
            }
        }
    }
}
@Composable private fun MarkdownInlines(content: List<MarkdownInline>, repository: MarkdownImageRepository, onLink: (String) -> Unit,
    path: String, heading: Int = 0, bold: Boolean = false, alignment: TextAlign = TextAlign.Start) {
    val parts = remember(content) { buildList<List<MarkdownInline>> {
        var text = mutableListOf<MarkdownInline>()
        content.forEach { if (it is MarkdownInline.Image) { if (text.isNotEmpty()) add(text.toList()); text = mutableListOf(); add(listOf(it)) } else text += it }
        if (text.isNotEmpty()) add(text.toList())
    } }
    Column(Modifier.fillMaxWidth()) { parts.forEachIndexed { part, inlines ->
        val key = "$path-$part"; val image = inlines.firstOrNull() as? MarkdownInline.Image
        if (image != null) MarkdownImage(image, repository, onLink, key)
        else {
            val text = markdownAnnotatedText(inlines.filterIsInstance<MarkdownInline.Text>(), MaterialTheme.colorScheme.secondary,
                MaterialTheme.colorScheme.onSurface.copy(alpha = .1f), onLink)
            val size = 16f * if (heading > 0) listOf(1.45f, 1.3f, 1.15f, 1.05f, 1f, 1f)[(heading - 1).coerceIn(0, 5)] else 1f
            Text(text, Modifier.fillMaxWidth().testTag("markdown-text-$key"), fontSize = size.sp,
                fontWeight = if (heading > 0 || bold) FontWeight.Bold else FontWeight.Normal, textAlign = alignment, lineHeight = (size * 1.3f).sp)
        }
    } }
}
internal fun markdownAnnotatedText(content: List<MarkdownInline.Text>, linkColor: Color, codeColor: Color, onLink: (String) -> Unit): AnnotatedString = buildAnnotatedString {
    content.forEach { run ->
        val start = length; append(run.text)
        addStyle(SpanStyle(fontWeight = if (run.bold) FontWeight.Bold else null, fontStyle = if (run.italic) FontStyle.Italic else null,
            fontFamily = if (run.code) FontFamily.Monospace else null, background = if (run.code) codeColor else Color.Unspecified), start, length)
        run.link?.let { url -> addLink(LinkAnnotation.Url(url, TextLinkStyles(SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline)),
            LinkInteractionListener { onLink(url) }), start, length) }
    }
}
@Composable private fun MarkdownImage(image: MarkdownInline.Image, repository: MarkdownImageRepository, onLink: (String) -> Unit, path: String) {
    BoxWithConstraints(Modifier.fillMaxWidth().testTag("markdown-image-$path")) {
        val width = with(androidx.compose.ui.platform.LocalDensity.current) { maxWidth.roundToPx() }.coerceIn(1, 8192)
        var resource by remember(image.source, repository) { mutableStateOf<AnimatedDrawableResource?>(null) }
        LaunchedEffect(image.source, repository, width) {
            resource = null; val loaded = repository.load(image.source, width) ?: return@LaunchedEffect
            try { resource = loaded; awaitCancellation() } finally { resource = null; loaded.release() }
        }
        resource?.let { value ->
            val painter = remember(value) { LifecycleDrawablePainter(value) }; val owner = LocalLifecycleOwner.current
            DisposableEffect(painter, owner) {
                val observer = LifecycleEventObserver { _, _ -> if (owner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) painter.start() else painter.stop() }
                owner.lifecycle.addObserver(observer); if (owner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) painter.start()
                onDispose { owner.lifecycle.removeObserver(observer); painter.stop() }
            }
            val ratio = painter.intrinsicSize.width / painter.intrinsicSize.height
            Image(painter, image.description, Modifier.fillMaxWidth().aspectRatio(ratio.coerceAtLeast(.001f)).testTag("markdown-loaded-$path")
                .then(image.link?.let { Modifier.clickable { onLink(it) } } ?: Modifier), contentScale = ContentScale.Fit)
        } ?: Text(image.description.ifBlank { image.source }, Modifier.padding(vertical = 8.dp))
    }
}
