package io.legado.app.ui.components.markdown

import org.commonmark.ext.gfm.tables.*
import org.commonmark.node.*
import org.commonmark.parser.Parser

sealed interface MarkdownInline {
    data class Text(
        val text: String,
        val bold: Boolean = false,
        val italic: Boolean = false,
        val code: Boolean = false,
        val link: String? = null,
    ) : MarkdownInline

    data class Image(val source: String, val description: String, val link: String? = null) :
        MarkdownInline
}

sealed interface MarkdownBlock {
    data class Paragraph(val content: List<MarkdownInline>, val heading: Int = 0) : MarkdownBlock

    data class Code(val content: String, val language: String = "") : MarkdownBlock

    data class Quote(val blocks: List<MarkdownBlock>) : MarkdownBlock

    data class ListBlock(val items: List<List<MarkdownBlock>>, val start: Int? = null) :
        MarkdownBlock

    data class Table(val rows: List<List<Cell>>) : MarkdownBlock {
        data class Cell(
            val content: List<MarkdownInline>,
            val header: Boolean,
            val alignment: String?,
        )
    }

    data object Divider : MarkdownBlock
}

fun parseMarkdownDocument(markdown: String): List<MarkdownBlock> {
    val parser = Parser.builder().extensions(listOf(TablesExtension.create())).build()
    return blocks(parser.parse(markdown))
}

private fun children(node: Node): List<Node> = buildList {
    var child = node.firstChild
    while (child != null) {
        add(child)
        child = child.next
    }
}

private fun blocks(parent: Node): List<MarkdownBlock> =
    children(parent).flatMap { node ->
        when (node) {
            is Paragraph -> listOf(MarkdownBlock.Paragraph(inlines(node)))
            is Heading -> listOf(MarkdownBlock.Paragraph(inlines(node), node.level))
            is FencedCodeBlock -> listOf(MarkdownBlock.Code(node.literal, node.info.orEmpty()))
            is IndentedCodeBlock -> listOf(MarkdownBlock.Code(node.literal))
            is BlockQuote -> listOf(MarkdownBlock.Quote(blocks(node)))
            is BulletList -> listOf(MarkdownBlock.ListBlock(children(node).map(::blocks)))
            is OrderedList ->
                listOf(MarkdownBlock.ListBlock(children(node).map(::blocks), node.startNumber))
            is ThematicBreak -> listOf(MarkdownBlock.Divider)
            is HtmlBlock ->
                listOf(MarkdownBlock.Paragraph(listOf(MarkdownInline.Text(node.literal))))
            is TableBlock ->
                listOf(
                    MarkdownBlock.Table(
                        children(node).flatMap { section ->
                            children(section).map { row ->
                                children(row).filterIsInstance<TableCell>().map {
                                    MarkdownBlock.Table.Cell(
                                        inlines(it),
                                        it.isHeader,
                                        it.alignment?.name,
                                    )
                                }
                            }
                        }
                    )
                )
            else -> blocks(node)
        }
    }

private fun inlines(
    parent: Node,
    bold: Boolean = false,
    italic: Boolean = false,
    code: Boolean = false,
    link: String? = null,
): List<MarkdownInline> =
    children(parent).flatMap { node ->
        when (node) {
            is Text -> listOf(MarkdownInline.Text(node.literal, bold, italic, code, link))
            is Code -> listOf(MarkdownInline.Text(node.literal, bold, italic, true, link))
            is SoftLineBreak,
            is HardLineBreak -> listOf(MarkdownInline.Text("\n", bold, italic, code, link))
            is StrongEmphasis -> inlines(node, true, italic, code, link)
            is Emphasis -> inlines(node, bold, true, code, link)
            is Link -> inlines(node, bold, italic, code, node.destination)
            is Image ->
                listOf(
                    MarkdownInline.Image(
                        node.destination,
                        inlines(node).filterIsInstance<MarkdownInline.Text>().joinToString("") {
                            it.text
                        },
                        link,
                    )
                )
            is HtmlInline -> listOf(MarkdownInline.Text(node.literal, bold, italic, code, link))
            else -> inlines(node, bold, italic, code, link)
        }
    }
